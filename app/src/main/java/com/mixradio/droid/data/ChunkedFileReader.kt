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
    /**
     * 大于 2MB 的文件强制分段（只读 LazyColumn）加载。
     * 注：Compose BasicTextField 对整段大文本做全量 StaticLayout，
     * 实测 ~4MB 即触发主线程卡死/崩溃；故阈值取保守的 2MB，
     * 2MB 以上一律走按行懒加载的只读安全路径。
     */
    const val LARGE_FILE_THRESHOLD = 2L * 1024L * 1024L

    /**
     * `loadAll` 一次性载入的**字节上限**。
     *
     * 唯一调用方（文本编辑器）只在文件 ≤ [LARGE_FILE_THRESHOLD]（2MB）时才走 [loadAll]，
     * 32MB 已有 16 倍余量。设上限是为了兜住「未来调用方传入超大文件」：
     * 旧实现 `ByteArrayOutputStream(total.toInt().coerceAtMost(Int.MAX_VALUE))` 在 >2GB 时
     * `total.toInt()` 溢出为**负数** → `IllegalArgumentException: Negative initial size`；
     * 在 1–2GB 区间则尝试申请等量内存 → OOM。
     */
    const val MAX_LOAD_BYTES = 32L * 1024L * 1024L

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
            // 兜底
            return try { File(filePath).readBytes().let { if (it.size > headBytes) it.copyOf(headBytes) else it } } catch (_: Throwable) { ByteArray(0) }
        }
        return try { File(filePath).readBytes().let { if (it.size > headBytes) it.copyOf(headBytes) else it } } catch (_: Throwable) { ByteArray(0) }
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
            // 大文件分块读取。**必须按 MAX_LOAD_BYTES 封顶**：旧实现把 total 直接 toInt() 当初始容量，
            // >2GB 会溢出为负（ByteArrayOutputStream 抛 Negative initial size），1–2GB 则直接 OOM。
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
