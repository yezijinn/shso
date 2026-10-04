// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                var endsWithLf = false
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
                        // UTF-16 末单元可能是奇数长度残留，只认完整单元
                        val tailAt = if (raw.size % 2 == 0) raw.size - 2 else raw.size - 1
                        endsWithLf = tailAt >= 0 && tailAt + 1 < raw.size &&
                            (if (charset == Charsets.UTF_16BE) raw[tailAt] == nul && raw[tailAt + 1] == lf
                             else raw[tailAt] == lf && raw[tailAt + 1] == nul)
                    } else {
                        for (b in raw.indices) {
                            if (raw[b] == lf) {
                                count++
                                if (count % GRANULARITY == 0) list.add(pos + b + 1L)
                            }
                        }
                        endsWithLf = raw[raw.size - 1] == lf
                    }
                    pos += raw.size
                }
                // 扫描完整性校验：必须真的读到 total 字节。
                // 读取中途失败（root 通道 su 被拒 / 授权弹窗未确认 / 60s 超时且丢弃了
                // 已读部分）会让上面 `raw.isEmpty()` 直接 break，count 停在半路甚至为 0，
                // 而 `getOrNull()` 不抛异常 → 依然返回 lines=1 的「有效」索引。
                // 调用方随即 `chunkedLines = emptyList()` 把本来正确的首块行全丢掉，
                // UI 变成 totalLines=1 的单行空白、loadError 为空、无任何提示：
                // 整个 >32MB 文件「内容不可见」，用户以为文件是空的。
                // 扫不全就返回 null，让调用方继续用首块行兜底。
                if (pos < total) return@withContext null
                // 行数口径必须与分段回退路径（TextEditorDialog 里 `split('\n')` + 末尾空串则 dropLast）
                // 以及 TextCompare.countLines 一致：文件以换行结尾时**不多算一行**。
                // 原实现无条件 `count + 1`，于是几乎所有 POSIX 文本文件（都以换行结尾）
                // 的行号数比真实值多 1，且随「索引是否建成」在 N 与 N+1 之间跳变。
                val lines = if (endsWithLf) count else count + 1
                SparseLineIndex(list.toLongArray(), lines, total, GRANULARITY)
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

    /**
     * 返回第 [chunkIndex] 块（文件区间 [chunkIndex*chunkSize, chunkSize)）的原始字节，命中则免 IO。
     *
     * 必须加锁：本类的唯一调用方 [IndexedLineProvider.load] 挂在 `Dispatchers.IO` 上，而
     * 巨型文件的 `LazyColumn` 里每个可见行各自 launch 一次 load，十几个可见行会同时进到这里。
     * `LinkedHashMap(accessOrder=true)` 的 `get()` 走 `afterNodeAccess` 改写 before/after 双向链表，
     * `put()` 走 `afterNodeInsertion` + `removeEldestEntry` 遍历 —— JDK/Android 均标注该组合
     * **非线程安全**。并发下会出现链表自环（`getNode` 死循环，IO 线程永久卡死）、
     * `ConcurrentModificationException`，或扩容期丢 entry 导致 `getChunk` 返回错位块
     * （虚拟列表静默显示错行内容）。
     *
     * 锁粒度取整个 `getChunk`：块大小 256KB，读一次本身就要走 IO，
     * 持锁期间不会成为热点；换来的是缓存与返回值的强一致。
     */
    /** 块长度（字节）。供上层判断某次读取是否为「中途短读」。 */
    val chunkSizeBytes: Int get() = chunkSize

    @Synchronized
    fun getChunk(chunkIndex: Int): ByteArray {
        cache[chunkIndex]?.let { return it }
        val off = chunkIndex.toLong() * chunkSize
        val b = reader.read(filePath, off, chunkSize.toLong())
        // 只缓存**满块**。短读（末尾块，或文件被截断后读到的残块）一旦进缓存，
        // 会在整个 LRU 生命周期内稳定返回过期内容，而上层无从察觉。
        if (b.size == chunkSize) cache[chunkIndex] = b
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

    /**
     * 加载第 [i] 行：从最近索引点按 256KB 块读取、整体解码后切出第 i 行，返回其完整文本。
     *
     * 两条约束（缺一即产生静默错误结果）：
     *  1. 换行计数必须**增量**。原实现在循环条件里对已累积的全部字节 `toByteArray()` 再全量数一遍，
     *     累积到 4MB 上限要 16 轮，单次 load 的复制+扫描量约 8MB，而每个可见行都独立付一次。
     *  2. 命中 [MAX_READ_BYTES] 上限说明**这一行本身就超过 4MB**，此时手上的字节只是前缀。
     *     原实现直接 `split('\n').getOrNull(...)` 把前缀里的残缺行当成完整行返回，
     *     只读浏览里该行尾部被静默丢弃，用户复制/比对该行拿到的就是残缺数据。
     *     现显式标记截断并追加可见标记，不再冒充完整行。
     */
    suspend fun load(i: Int): String = withContext(Dispatchers.IO) {
        val floor = (i / granularity) * granularity
        val startOffset = index.lineStartOffset(floor)   // 索引点 = 换行后，必为字符边界（含 UTF-16）
        val firstChunk = (startOffset / CHUNK_SIZE).toInt()
        val skip = (startOffset - firstChunk.toLong() * CHUNK_SIZE).toInt()
        val out = ByteArrayOutputStream()
        val first = doc.getChunk(firstChunk)
        // 索引失效守卫：startOffset 落在首块**之外**说明 `lineStartOffset` 返回了陈旧偏移
        // （文件被外部截断/重写，索引与块缓存全程无 size/mtime 校验）。
        // 此时首块读到的 `first.size` 全部 ≤ skip，若仍让游标从 firstChunk+1 起步，
        // 首个稀疏点覆盖的 1024 行会整体前移 —— split('\n').getOrNull(i - floor)
        // 取到的是文件里更靠后的另一行，而行号看起来仍然「正确」。
        // 短读还会被 getChunk 当成完整块缓存，错位在整个 LRU 生命周期内不自愈。
        // 正确做法是判索引失效并返回空，由上层重建，而不是跳过首块继续累积。
        if (firstChunk >= 0 && first.size <= skip) return@withContext ""
        if (first.size > skip) out.write(first, skip, first.size - skip)
        // 增量换行计数：只扫新写入的块并累加
        var newlines = if (first.size > skip) countNewlines(first, skip, first.size) else 0
        val need = i - floor + 1
        var ci = firstChunk + 1
        var truncated = false
        while (newlines < need) {
            // 行滑出可视窗口时协程已被取消，但 IO 调度器的取消是**协作式**的：
            // 不会中断阻塞中的 getChunk，循环会一路读到凑够换行数或 EOF。
            // 快速拖动滑块时几十个已取消的 load 同时跑满 IO，
            // 且各自持有最多 4MB 的 ByteArrayOutputStream → GC 抖动、正常行排队，
            // 极端情况 OOM。与 ArchiveExtractor.copyStream 是同一类遗漏。
            currentCoroutineContext().ensureActive()
            if (out.size() > MAX_READ_BYTES) {          // 极端超长行保护（≈4MB 仍无换行）
                truncated = true
                break
            }
            val chunk = doc.getChunk(ci)
            if (chunk.isEmpty()) break                   // EOF：最后一行无尾随换行也到此终止
            // **中途短读 = 读取与索引不再可信**，必须停在这里。
            //
            // `getChunk` 只缓存满块（见 ChunkedDocument.getChunk），所以非满块必然来自
            // reader 的短读：root 路径 `dd` 读到被并发截断的文件、或 `RandomAccessFile.read`
            // 契约允许的短读。此时若继续 `ci++`，该块剩余的字节会被**永久跳过** ——
            // `newlines` 随之少计，后续行全部前移，而 `i - floor` 取到的是文件里更靠后的
            // 另一行，行号却仍然「看起来正确」。用户复制该行拿到的就是错的数据。
            //
            // 首块已有等价守卫（上面 `first.size <= skip`），循环内此前没有。
            val chunkStart = ci.toLong() * doc.chunkSizeBytes
            if (chunk.size < doc.chunkSizeBytes && chunkStart + chunk.size < index.totalBytes) {
                return@withContext ""                    // 索引失效：交给上层重建，不冒充有效行
            }
            out.write(chunk)
            newlines += countNewlines(chunk, 0, chunk.size)
            ci++
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return@withContext ""
        // 截断时**必须保留已读到的内容**。`truncated = true` 的前提就是 `newlines < need`，
        // 而 `split('\n')` 最多产出 `newlines + 1` 段，`getOrNull(i - floor)` 必然为 null ——
        // 直接 `?: ""` 等于把已经读到的 4MB 全部丢掉，用户只看到一行「已截断」提示。
        val parts = String(bytes, charset).split('\n')
        val line = parts.getOrNull(i - floor) ?: parts.last()
        // 截断标记必须让用户看得见：只读浏览里不能把残缺行当完整行渲染/复制
        if (truncated) "$line…（本行超过 ${MAX_READ_BYTES / 1024 / 1024}MB，已截断显示）" else line
    }

    /** charset 感知的区间换行计数：UTF-16 按 2 字节 LF 单元，其余按单字节 0x0A。 */
    private fun countNewlines(bytes: ByteArray): Int = countNewlines(bytes, 0, bytes.size)

    private fun countNewlines(bytes: ByteArray, from: Int, to: Int): Int {
        val utf16 = charset == Charsets.UTF_16 || charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE
        if (!utf16) {
            var n = 0
            for (i in from until to) if (bytes[i] == 0x0A.toByte()) n++
            return n
        }
        val be = charset == Charsets.UTF_16BE
        var n = 0
        var b = if (from % 2 == 0) from else from + 1   // UTF-16 单元须 2 字节对齐
        while (b + 1 < to) {
            val isLf = if (be) bytes[b] == 0.toByte() && bytes[b + 1] == 0x0A.toByte()
            else bytes[b] == 0x0A.toByte() && bytes[b + 1] == 0.toByte()
            if (isLf) n++
            b += 2
        }
        return n
    }
}
