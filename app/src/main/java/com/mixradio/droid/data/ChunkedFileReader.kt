// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
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
     * 大于 128KB 的文件强制分段（只读 LazyColumn）加载。
     * 依据：BasicTextField 对整段文本做全量 StaticLayout，开销随体积快速放大，故超过即走按行懒加载的只读路径（不可编辑）。
     */
    const val LARGE_FILE_THRESHOLD = 128L * 1024L

    /**
     * `loadAll` 一次性载入的字节上限（仅当文件 ≤ [LARGE_FILE_THRESHOLD] 时走 [loadAll]，32MB 已有余量）。
     * 兜住超大文件：`total.toInt()` 在 >2GB 时溢出为负（`IllegalArgumentException`），1–2GB 区间直接 OOM。
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
    val offsetBytes: Long,
    /**
     * 是否**确实**读到了文件的全部内容。
     *
     * `false` 有两种成因，调用方都必须区别对待：
     *  - 读取失败 / 短读：内容残缺（见 [isComplete] 的说明）；
     *  - 大小不可知或目标非普通文件：根本不该进入编辑流程。
     */
    val readFailed: Boolean = false
) {
    /**
     * 是否读到了文件的全部内容。
     *
     * 分块读取途中任何一次 `readRange` 返回空（ROOT 通道的 `dd|base64` 失败、被拒、超时）
     * 或**短读**（`dd` 输出被截断时 `readRangeRoot` 只 `copyOfRange` 到 `raw.size`），
     * `loadAll` 的循环都会 `break`，而调用方此前只看 `text` 不看字节数，
     * 于是把残缺内容当成完整原文载入编辑器并标记为「未修改」。
     * 用户看不出任何异常，一次无关编辑后 `writeTextFile` 就用这份残缺内容
     * **整文件覆盖**原文件 —— 截断或内容错位，且不可撤销。
     *
     * 唯一的调用点（`TextEditorDialog`）只在 `total <= MAX_LOAD_BYTES` 时调 `loadAll`，
     * 此时 `cappedLoadBytes` 不封顶，故 `loadedBytes < totalBytes` 一定是读失败而非有意截断。
     *
     * [readFailed] 为 true 时一律判为不完整：大小探测本身失败时 `totalBytes <= 0`，
     * 而 `0` 与「空文件」的 0 无法从字节数区分 —— 旧实现在此直接判 `true`，
     * 于是 procfs、sysfs 下的节点（`stat %s` 恒为 0 却有内容）被当成
     * 「完整的空文件」载入编辑器，用户一保存就整文件覆盖（对可写节点即数据销毁）。
     */
    val isComplete: Boolean
        get() = !readFailed && totalBytes >= 0L && loadedBytes.toLong() >= totalBytes
}

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
                    val bytesRead = fis.read(buf, read, headBytes - read)
                    if (bytesRead <= 0) break
                    read += bytesRead
                }
                if (read > 0) buf.copyOf(read) else ByteArray(0)
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    /**
     * 探测「能否实读到字节」，返回实际读到的字节数（上限 [PROBE_BYTES]）。
     *
     * 这是判定「`stat %s` 为 0 到底是真空文件还是 procfs 节点」的唯一可靠依据。
     * 真机实测（PACM00 / Android 10）：
     *   - `stat -L -c %s /proc/cpuinfo`   → 0，但 `wc -c` 为 1517（**有内容**）
     *   - `stat -L -c %s /proc/uptime`    → 0，但 `wc -c` 为 21
     *   - `stat -L -c %s <真空文件>`       → 0，`wc -c` 为 0
     * 三者的 `stat %s` 完全相同，只有实读能区分。
     * 而 `test -f` 对 `/proc/cpuinfo` 返回**真**（procfs 项都是普通文件类型），
     * 故 `isRegularFile` 同样拦不住 —— 必须以「读得出字节」为准。
     *
     * @return 读到的字节数；读失败返回 -1
     */
    fun probeReadableBytes(filePath: String, probeBytes: Int = PROBE_BYTES): Int {
        val escaped = RootService.escapeShellArg(filePath)
        // 优先 dd：root 通道下对 procfs 同样有效，且不依赖 stat 的实现差异。
        if (RootService.isRootGranted == true) {
            val (code, out) = RootService.runCommandSync(
                "dd if=$escaped bs=1 count=$probeBytes 2>/dev/null | wc -c",
                timeoutMs = 10_000L
            )
            if (code == 0) out.trim().toIntOrNull()?.let { return it }
        }
        return try {
            java.io.FileInputStream(filePath).use { fis ->
                val buf = ByteArray(probeBytes)
                var read = 0
                while (read < probeBytes) {
                    val n = fis.read(buf, read, probeBytes - read)
                    if (n <= 0) break
                    read += n
                }
                read
            }
        } catch (_: Throwable) {
            // 读不出来（不存在 / 无权限）即失败，绝不能返回 0 ——
            // 0 会被当作「空文件」，正是本缺陷的源头。
            -1
        }
    }

    /**
     * 加载文件总大小；**探测失败返回 -1**，与「真的是 0 字节」区分。
     *
     * 旧实现两条路径都返回 0，调用方无法分辨「空文件」与「探测失败」。
     */
    fun fileSize(filePath: String): Long {
        if (RootService.isRootGranted == true) {
            val (code, out) = RootService.runCommandSync(
                "stat -L -c %s ${RootService.escapeShellArg(filePath)}",
                timeoutMs = 10_000L
            )
            if (code == 0) {
                val parsed = out.trim().toLongOrNull()
                if (parsed != null && parsed >= 0L) return parsed
                // 输出不可解析（如 stat 不支持该格式、被中间层污染）
            }
        }
        return try {
            val f = File(filePath)
            // 本地通道能明确区分「不存在」与「存在但取不到长度」
            if (f.exists()) f.length() else -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    /** 探测用字节数：足以判定「有无内容」，又不会因大文件而拖慢。 */
    private const val PROBE_BYTES = 1

    /**
     * 从 offset 字节起读取 [count] 字节的原始字节（不强制按行）。
     * ROOT 路径：dd 以 4KB 块为单位读取（skip/count 单位为 bs），再精确截断到目标区间，
     * 避免 bs=1 逐字节读取带来的百万次系统调用开销。
     */
    fun readRange(filePath: String, offset: Long, count: Long): ByteArray {
        if (count <= 0L) return ByteArray(0)
        // 负 offset 会让下游 copyOfRange / RandomAccessFile.seek 抛越界异常并逃出
        // 本函数（readRangeRoot 的 copyOfRange 不在 try 内），调用方只防住了
        // Throwable 的一处仍会崩。入口即拒绝。
        if (offset < 0L) return ByteArray(0)
        // count > 2GB 时 toInt() 溢出为负 → NegativeArraySizeException。
        // 夹到 Int.MAX_VALUE，且不允许单次分配超过 MAX_LOAD_BYTES（1GB 也会 OOM）。
        val safeCount = count.coerceAtMost(MAX_LOAD_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (safeCount <= 0) return ByteArray(0)
        if (RootService.isRootGranted == true) {
            val root = readRangeRoot(filePath, offset, safeCount.toLong())
            if (root.isNotEmpty()) return root
        }
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(safeCount)
                val read = raf.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        } catch (_: Throwable) { ByteArray(0) }
    }

    /** ROOT 高效区间读取：dd bs=4096 + 精确截断。返回空表示读取失败。 */
    private fun readRangeRoot(filePath: String, offset: Long, count: Long): ByteArray = try {
        val bs = 4096L
        val startBlock = offset / bs
        val blockCount = (count + bs - 1) / bs
        val cmd = "dd if=${RootService.escapeShellArg(filePath)} bs=$bs skip=$startBlock count=$blockCount 2>/dev/null | base64 -w 0"
        val (code, out) = RootService.runCommandSync(cmd, timeoutMs = 60_000L)
        if (code != 0 || out.isBlank()) ByteArray(0)
        else {
            val raw = android.util.Base64.decode(out.trim(), android.util.Base64.DEFAULT)
            val startInBlock = (offset % bs).toInt()
            if (startInBlock >= raw.size) ByteArray(0)
            else raw.copyOfRange(startInBlock, minOf(startInBlock + count.toInt(), raw.size))
        }
    } catch (_: Throwable) {
        // base64 解码失败、copyOfRange 越界等都必须在此收口：
        // 本函数是 readRange 的 root 分支，异常逃出会跳过本地兜底。
        ByteArray(0)
    }

    /**
     * 加载整个文件（小文件直读；大文件按块读取并拼接）。
     *  - 大文件分块读原始字节后整体解码
     *  - 不做按行裁剪，保证编码检测可工作于首块
     */
    fun loadAll(filePath: String): LoadResult {
        val total = fileSize(filePath)
        if (total < 0L) {
            // 大小探测失败（stat 被拒/超时/输出不可解析）：不是空文件。
            // 仍尝试读一小段，好让用户至少看到内容，而不是一个空编辑器。
            val head = readHead(filePath, CHUNK_BYTES.toInt())
            val det = CharsetDetector.detect(head)
            return LoadResult(
                det.text, det.charset, det.hasBom,
                totalBytes = -1L, loadedBytes = head.size, offsetBytes = 0L,
                readFailed = true
            )
        }
        if (total == 0L) {
            // 真的是 0 字节：**必须**再实读一次确认没有内容。
            // procfs、sysfs 节点与 FIFO 的 `stat %s` 恒为 0 却有内容，
            // 而 `test -f` 对它们返回真（procfs 项类型就是普通文件），两者都拦不住。
            // 唯一可靠的判据是「读得出字节」。
            // 不确认就返回「空且完整」，编辑器一保存即整文件覆盖
            // （对 root 可写的节点就是真实数据销毁）。
            val readable = probeReadableBytes(filePath)
            if (readable != 0) {
                val head = readHead(filePath, CHUNK_BYTES.toInt())
                val det = CharsetDetector.detect(head)
                return LoadResult(
                    det.text, det.charset, det.hasBom,
                    totalBytes = 0L, loadedBytes = head.size, offsetBytes = 0L,
                    readFailed = true
                )
            }
            // readable == -1：连一个字节都读不出来（不存在 / 无权限），
            // 同样不能当空文件。
            if (readable < 0) {
                return LoadResult("", Charsets.UTF_8, false, -1L, 0, 0L, readFailed = true)
            }
            return LoadResult("", Charsets.UTF_8, false, 0L, 0, 0L)
        }

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
