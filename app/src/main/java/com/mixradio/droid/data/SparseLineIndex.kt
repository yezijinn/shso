// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.LinkedHashMap

/**
 * 字节区间读取抽象。
 *
 * 生产默认实现 [ChunkedFileReaderAdapter] 走 [ChunkedFileReader]（含 ROOT/非 ROOT 双路径）；
 * 单测注入内存实现 [com.mixradio.droid.data.MemoryByteRangeReader]，与 Android/RootService 解耦，
 * 从而可在 JVM 上确定性验证稀疏行索引算法，不依赖真机或 android.jar。
 */
interface ByteRangeReader {
    /** 返回 [path] 的字节总长；≤0 视为不可读。 */
    fun size(path: String): Long
    /** 从 [offset] 起读取最多 [count] 字节（不足则截断，越界返回空）。 */
    fun read(path: String, offset: Long, count: Long): ByteArray
}

/** 生产默认读取器：委托给 [ChunkedFileReader]（ROOT/非ROOT 双路径）。 */
internal object ChunkedFileReaderAdapter : ByteRangeReader {
    override fun size(path: String): Long = ChunkedFileReader.fileSize(path)
    override fun read(path: String, offset: Long, count: Long): ByteArray = ChunkedFileReader.readRange(path, offset, count)
}

/** 块大小 256KB / 块（对齐 MP-Manager ChunkedDocument §7.2/§9.2）。 */
private const val CHUNK_SIZE = 256 * 1024
/** 块 LRU 上限：16 块 ≈ 4MB 常驻。 */
private const val CHUNK_CACHE_ENTRIES = 16
/** 单行加载的字节安全上限：≈4MB 仍无换行则放弃（极端超长行保护）。 */
private const val MAX_READ_BYTES = 4L * 1024L * 1024L

/**
 * 超大文件只读浏览的稀疏行索引。
 *
 * 不把任何行**内容**读进内存：仅扫描一次文件，记录每隔 [GRANULARITY] 行的字节起始偏移，
 * 渲染第 N 行时从 ≤ N 的最近索引点按需 `readRange` + 按 charset 解码，内存恒定在 O(窗口)。
 * 对齐 MP-Manager 项目解析文档：
 *  - 「Kotlin高性能文本编辑器实现指南」§4.3 稀疏字节偏移索引（`granularity: Int = 1024`）；
 *  - 「大体积文档编辑功能Kotlin移植可行性分析」§7.3 行索引可行性（记录每行偏移，O(1) 寻址）；
 *    MP-Manager 自身编辑器未实现该索引（其模块详解 §9.3 列为未来「可改进点」），此处 shso 已落地。
 * 彻底消除旧实现「滚到底把全文件行字符串累积进 `chunkedLines`」的 OOM 隐患。
 *
 * 调用方约束：本索引只在「只读分段浏览」态使用；进入编辑态前由上层清空并改走全文 contentValue。
 */
class SparseLineIndex(
    private val offsets: LongArray,   // offsets[k] = 第 k*GRANULARITY 行的字节起始偏移；offsets[0] = 0
    val totalLines: Int,
    val totalBytes: Long,
    val granularity: Int
) {
    /** 第 [line] 行（0-based）的字节起始偏移。floor 到最近的索引点，O(1)。 */
    fun lineStartOffset(line: Int): Long {
        if (line <= 0) return 0L
        val floor = (line / granularity) * granularity
        val k = floor / granularity
        return if (k in offsets.indices) offsets[k] else totalBytes
    }

    companion object {
        /**
         * 稀疏索引粒度（对齐 MP-Manager 「Kotlin高性能文本编辑器实现指南」§4.3 `buildSparseIndex(granularity = 1024)`）。
         * 每 [GRANULARITY] 行记一个字节偏移：渲染第 N 行时最多向前扫描 [GRANULARITY] 行，复杂度 O(granularity)。
         */
        const val GRANULARITY = 1024
        /** build 单次扫描的字节块大小。 */
        private const val READ_BLOCK = 256L * 1024L

        /**
         * 后台扫描 [filePath] 建立稀疏行索引。
         *  - non-UTF-16：按单字节 0x0A 扫描（多字节续字节 ≥0x80，0x0A 必落字符边界）。
         *  - UTF-16：按 2 字节单元 [0x0A,0x00]/[0x00,0x0A] 扫描（shso 对 MP-Manager 的 UTF-8 单假定做了补齐）。
         * 失败返回 null（调用方回退到旧的分段累积策略，不阻断打开）。
         */
        suspend fun build(
            filePath: String,
            charset: Charset,
            reader: ByteRangeReader = ChunkedFileReaderAdapter
        ): SparseLineIndex? = withContext(Dispatchers.IO) {
            runCatching {
                val total = reader.size(filePath)
                if (total <= 0L) return@withContext null
                val utf16 = charset == Charsets.UTF_16 || charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE
                val list = ArrayList<Long>(1024)
                list.add(0L)
                var count = 0      // 已遇到的换行数 = 当前行号（0-based 下一行）
                val lf: Byte = 0x0A.toByte()
                val nul: Byte = 0.toByte()
                var pos = 0L
                while (pos < total) {
                    val raw = reader.read(filePath, pos, minOf(READ_BLOCK, total - pos))
                    if (raw.isEmpty()) break
                    if (utf16) {
                        var b = 0
                        while (b + 1 < raw.size) {
                            val isLf = if (charset == Charsets.UTF_16BE)
                                raw[b] == nul && raw[b + 1] == lf
                            else
                                raw[b] == lf && raw[b + 1] == nul
                            if (isLf) {
                                count++
                                if (count % GRANULARITY == 0) list.add(pos + b + 2)
                            }
                            b += 2
                        }
                    } else {
                        for (b in raw.indices) {
                            if (raw[b] == lf) {
                                count++
                                if (count % GRANULARITY == 0) list.add(pos + b + 1L)
                            }
                        }
                    }
                    pos += raw.size
                }
                SparseLineIndex(list.toLongArray(), count + 1, total, GRANULARITY)
            }.getOrNull()
        }
    }
}

/**
 * 原始字节块缓存（对应 MP-Manager `ChunkedDocument`：
 * 「大体积文档编辑功能Kotlin移植可行性分析」§7.2 / 「Kotlin高性能文本编辑器实现指南」§9.2）。
 *
 *  - 固定 [CHUNK_SIZE]=256KB 块；[cache] 以 LRU（访问序）缓存至多 [CHUNK_CACHE_ENTRIES]=16 块（约 4MB），
 *    滚动时前后块不重复读磁盘。
 *  - 缓存**原始字节**而非解码后的字符串：MP-Manager 仅处理 UTF-8，而 shso 需兼容 UTF-16 检测。
 *    按字节边界缓存、再对「连续文件区间」整体解码，可避免跨块截断多字节字符（UTF-16 一个字符占 2 字节）。
 *  - 用 `java.util.LinkedHashMap(accessOrder=true)` 而非 `android.util.LruCache`：本模块为纯 Kotlin，
 *    单测在 JVM 上运行（不依赖 android.jar），故不能用 Android 框架类。
 */
class ChunkedDocument(
    private val reader: ByteRangeReader,
    private val filePath: String,
    private val charset: Charset,
    private val chunkSize: Int = CHUNK_SIZE
) {
    private val cache = object : LinkedHashMap<Int, ByteArray>(CHUNK_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(entry: MutableMap.MutableEntry<Int, ByteArray>): Boolean = size > CHUNK_CACHE_ENTRIES
    }

    /** 返回第 [chunkIndex] 块（文件区间 [chunkIndex*chunkSize, chunkSize)）的原始字节，命中则免 IO。 */
    fun getChunk(chunkIndex: Int): ByteArray {
        cache[chunkIndex]?.let { return it }
        val off = chunkIndex.toLong() * chunkSize
        val b = reader.read(filePath, off, chunkSize.toLong())
        if (b.isNotEmpty()) cache[chunkIndex] = b
        return b
    }
}

/**
 * 基于 [SparseLineIndex] + [ChunkedDocument] 的按需行加载器。
 *
 * LazyColumn 每个可见项按行号取文本：从 [SparseLineIndex] 最近索引点起，经 [ChunkedDocument] 按 256KB 块
 * 读取原始字节（块命中 LRU 免 IO），整体解码后按 charset 切出目标行。内存恒定 O(窗口) 而非 O(全文件行数)。
 * 对齐 MP-Manager §4.3「渲染第 N 行：找 N/granularity 偏移 → 向后最多扫 granularity 行」+ §9.2 块缓存。
 *
 * 设计取舍（相对旧实现）：旧 [IndexedLineProvider] 用「行级 LRU（4096 条）」缓存已解码行；
 * 现改为「原始字节块 LRU（16 块）」，更契合 MP-Manager 的 [ChunkedDocument]，且对超长行（>256KB 单行的日志）
 * 不再因行缓存的「跨窗口半成品」问题产生截断（旧实现已修复该 bug，新设计从结构上规避：只缓存整块字节，
 * 行永远从整块区间现切，不存在「半成品行被当成完整行缓存」的可能）。
 */
class IndexedLineProvider(
    private val index: SparseLineIndex,
    private val filePath: String,
    private val charset: Charset,
    private val reader: ByteRangeReader = ChunkedFileReaderAdapter
) {
    private val granularity = index.granularity
    private val doc = ChunkedDocument(reader, filePath, charset)

    /** 行级缓存已被 [ChunkedDocument] 的字节块 LRU 取代，故 peek 恒返回 null（按需行由 [load] 现取）。 */
    fun peek(i: Int): String? = null

    /** 加载第 [i] 行：从最近索引点按 256KB 块读取、整体解码后切出第 i 行，返回其完整文本。 */
    suspend fun load(i: Int): String = withContext(Dispatchers.IO) {
        val floor = (i / granularity) * granularity
        val startOffset = index.lineStartOffset(floor)   // 索引点 = 换行后，必为字符边界（含 UTF-16）
        val firstChunk = (startOffset / CHUNK_SIZE).toInt()
        val skip = (startOffset - firstChunk.toLong() * CHUNK_SIZE).toInt()
        val out = ByteArrayOutputStream()
        val first = doc.getChunk(firstChunk)
        if (first.size > skip) out.write(first, skip, first.size - skip)
        var ci = firstChunk + 1
        while (true) {
            // 已覆盖第 i 行（含其后的换行）即停止：countNewlines >= i-floor+1 表示 [floor, i] 均为完整行。
            if (countNewlines(out.toByteArray()) >= i - floor + 1) break
            if (out.size() > MAX_READ_BYTES) break        // 极端超长行保护（≈4MB 仍无换行则放弃）
            val chunk = doc.getChunk(ci)
            if (chunk.isEmpty()) break                    // EOF：最后一行无尾随换行也到此终止
            out.write(chunk)
            ci++
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return@withContext ""
        String(bytes, charset).split('\n').getOrNull(i - floor) ?: ""
    }

    /** charset 感知的换行计数：UTF-16 按 2 字节 LF 单元，其余按单字节 0x0A。 */
    private fun countNewlines(bytes: ByteArray): Int {
        val utf16 = charset == Charsets.UTF_16 || charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE
        if (!utf16) return bytes.count { it == 0x0A.toByte() }
        val be = charset == Charsets.UTF_16BE
        var n = 0
        var b = 0
        while (b + 1 < bytes.size) {
            val isLf = if (be) bytes[b] == 0.toByte() && bytes[b + 1] == 0x0A.toByte()
            else bytes[b] == 0x0A.toByte() && bytes[b + 1] == 0.toByte()
            if (isLf) n++
            b += 2
        }
        return n
    }
}
