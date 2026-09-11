// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import java.io.File
import java.io.RandomAccessFile

/**
 * 大文件分段加载（避免 OOM）。
 *  - ROOT：走 `dd if=... bs=1 skip=A count=B` 精确读区间
 *  - 无 ROOT：RandomAccessFile seek + read
 *  - 解码：经 CharsetDetector 解析 BOM/编码
 */
object ChunkedFileReader {

    /** 默认分块大小：1MB；总大小超过此值走分段加载。 */
    const val CHUNK_BYTES = 1024L * 1024L

    /** 换行符字节（LF）。分段加载按它对齐到完整行边界。 */
    private const val LINE_FEED: Byte = 0x0A
    /**
     * 大于 **128KB** 的文件强制分段（只读 LazyColumn）加载。
     *
     * 阈值依据：Compose 的 BasicTextField 会对整段文本做全量 StaticLayout，开销随体积快速放大。
     * 低端机（BIYLBAFQQSS8DA69）实测：32KB 秒开，256KB 主线程持续约 30 秒，2MB 数分钟无响应。
     * 因此阈值取 128KB，超过即走按行懒加载的只读路径（不可编辑）。
     */
    const val LARGE_FILE_THRESHOLD = 128L * 1024L

    /**
     * `loadAll` 一次性载入的**字节上限**。
     *
     * 调用方只在文件 ≤ [LARGE_FILE_THRESHOLD] 时才走 [loadAll]，32MB 已有充足余量。
     * 设上限用于兜住超大文件：`total.toInt()` 在 >2GB 时溢出为负数
     * （`IllegalArgumentException: Negative initial size`），1–2GB 区间则直接 OOM。
     */
    const val MAX_LOAD_BYTES = 32L * 1024L * 1024L

    /**
     * 返回 [bytes] 中「最后一个完整行」的结束下标（**含**该行的换行符）。
     * 语义：`bytes[0, result)` 恰好包含整数个以 '\n' 结尾的行，可安全解码；
     * `bytes[result, size)` 是尚未完整的一行，应留给下一块拼接。无换行时返回 -1。
     *
     * 为什么按单个 0x0A 字节扫描是安全的：'\n' 在 UTF-8 中是单字节 0x0A，而所有多字节序列的
     * 续字节都 ≥0x80，因此 0x0A 一定落在字符边界上，不会从多字节字符中间截断。
     * （UTF-16 不满足此性质，调用方需自行跳过对齐，见 TextEditorDialog。）
     */
    internal fun lastCompleteLineEnd(bytes: ByteArray): Int {
        for (i in bytes.indices.reversed()) {
            if (bytes[i] == LINE_FEED) return i + 1
        }
        return -1
    }

    /** [loadAll] 实际最多读取的字节数（纯函数，便于单测）。 */
    internal fun cappedLoadBytes(total: Long): Long = when {
        total <= 0L -> 0L
        total > MAX_LOAD_BYTES -> MAX_LOAD_BYTES
        else -> total
    }

    data class LoadResult(
        val text: String,
        val charset: java.nio.charset.Charset,
        val hasBom: Boolean,
        val totalBytes: Long,
        val loadedBytes: Int,
        val offsetBytes: Long
    )

    /** 加载文件前若干字节用于编码检测；上限 1MB。 */
    fun readHead(filePath: String, headBytes: Int = (CHUNK_BYTES).toInt()): ByteArray {
        if (RootService.isRootGranted == true) {
            val raw = readRangeRoot(filePath, 0L, headBytes.toLong())
            if (raw.isNotEmpty()) return raw.copyOf(minOf(raw.size, headBytes))
            // 兜底（同样只读前 headBytes 字节）
            return readHeadLocal(filePath, headBytes)
        }
        return readHeadLocal(filePath, headBytes)
    }

    /**
     * 本地（非 root）有界读取前 [headBytes] 字节。
     *
     * 不能用 `File.readBytes()` 整读后再截断：走分段的文件可达数百 MB，必然 OOM。
     * 改为流式读取、读满即停。
     */
    internal fun readHeadLocal(filePath: String, headBytes: Int): ByteArray {
        if (headBytes <= 0) return ByteArray(0)
        return try {
            java.io.FileInputStream(filePath).use { fis ->
                val buf = ByteArray(headBytes)
                var read = 0
                while (read < headBytes) {
                    val r = fis.read(buf, read, headBytes - read)
                    if (r <= 0) break
                    read += r
                }
                if (read > 0) buf.copyOf(read) else ByteArray(0)
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    /**
     * 加载文件总大小。
     */
    fun fileSize(filePath: String): Long {
        if (RootService.isRootGranted == true) {
            val (code, out) = RootService.runCommandSync(
                "stat -L -c %s ${RootService.escapeShellArg(filePath)}",
                timeoutMs = 10_000L
            )
            if (code == 0) return out.trim().toLongOrNull() ?: 0L
        }
        return try { File(filePath).length() } catch (_: Throwable) { 0L }
    }

    /**
     * 从 offset 字节起读取 [count] 字节的原始字节（不强制按行）。
     * ROOT 路径：dd 以 4KB 块为单位读取（skip/count 单位为 bs），再精确截断到目标区间，
     * 避免 bs=1 逐字节读取带来的百万次系统调用开销。
     */
    fun readRange(filePath: String, offset: Long, count: Long): ByteArray {
        if (count <= 0) return ByteArray(0)
        if (RootService.isRootGranted == true) {
            val root = readRangeRoot(filePath, offset, count)
            if (root.isNotEmpty()) return root
        }
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(count.toInt())
                val read = raf.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        } catch (_: Throwable) { ByteArray(0) }
    }

    /** ROOT 高效区间读取：dd bs=4096 + 精确截断。返回空表示读取失败。 */
    private fun readRangeRoot(filePath: String, offset: Long, count: Long): ByteArray {
        val bs = 4096L
        val startBlock = offset / bs
        val blockCount = (count + bs - 1) / bs
        val cmd = "dd if=${RootService.escapeShellArg(filePath)} bs=$bs skip=$startBlock count=$blockCount 2>/dev/null | base64 -w 0"
        val (code, out) = RootService.runCommandSync(cmd, timeoutMs = 60_000L)
        if (code != 0 || out.isBlank()) return ByteArray(0)
        val raw = runCatching { android.util.Base64.decode(out.trim(), android.util.Base64.DEFAULT) }.getOrNull() ?: return ByteArray(0)
        val startInBlock = (offset % bs).toInt()
        val end = minOf(startInBlock + count.toInt(), raw.size)
        if (startInBlock >= raw.size) return ByteArray(0)
        return raw.copyOfRange(startInBlock, end)
    }

    /**
     * 加载整个文件（小文件直读；大文件按块读取并拼接）。
     *  - 大文件分块读原始字节后整体解码
     *  - 不做按行裁剪，保证编码检测可工作于首块
     */
    fun loadAll(filePath: String): LoadResult {
        val total = fileSize(filePath)
        if (total <= 0L) return LoadResult("", Charsets.UTF_8, false, 0L, 0, 0L)

        val raw: ByteArray = if (total <= LARGE_FILE_THRESHOLD) {
            // 小文件一次性读
            if (RootService.isRootGranted == true) {
                val (code, out) = RootService.runCommandSync(
                    "cat ${RootService.escapeShellArg(filePath)} | base64 -w 0",
                    timeoutMs = 60_000L
                )
                if (code == 0) android.util.Base64.decode(out.trim(), android.util.Base64.DEFAULT)
                else try { File(filePath).readBytes() } catch (_: Throwable) { ByteArray(0) }
            } else try { File(filePath).readBytes() } catch (_: Throwable) { ByteArray(0) }
        } else {
            // 必须按 MAX_LOAD_BYTES 封顶：total 直接 toInt() 当初始容量，
            // >2GB 溢出为负，1–2GB 直接 OOM。
            val cap = cappedLoadBytes(total)
            val buf = java.io.ByteArrayOutputStream(minOf(cap, CHUNK_BYTES).toInt())
            var off = 0L
            while (off < cap) {
                val len = minOf(CHUNK_BYTES, cap - off)
                val chunk = readRange(filePath, off, len)
                if (chunk.isEmpty()) break
                buf.write(chunk)
                off += chunk.size
            }
            buf.toByteArray()
        }
        val det = CharsetDetector.detect(raw)
        return LoadResult(det.text, det.charset, det.hasBom, total, raw.size, 0L)
    }
}
