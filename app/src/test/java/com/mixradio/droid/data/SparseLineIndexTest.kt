// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 回归守卫：超大文件只读浏览的「稀疏行索引 + 虚拟滚动」（对齐 MP-Manager 项目解析文档）。
 *
 * 核心不变量：
 *  - [SparseLineIndex.build] 只扫描一次文件、只记录每隔 [SparseLineIndex.GRANULARITY]=1024 行的字节偏移，
 *    不把任何行**内容**读进堆 —— 即使 200MB 日志，索引本身也只是约 (行数/1024) 个 Long。
 *    对齐「Kotlin高性能文本编辑器实现指南」§4.3 / 「大体积文档编辑功能Kotlin移植可行性分析」§7.3。
 *  - [IndexedLineProvider.load] 经 [ChunkedDocument]（256KB 块 + 16 块 LRU，§7.2/§9.2）按行号按需读取，
 *    内存恒为 O(窗口)，彻底绕开旧实现「滚到底把全文件行字符串累积进 `chunkedLines`」的 OOM 路径。
 *  - 正确性必须跨 UTF-8（含多字节/超长行）与 UTF-16 两种编码保持一致。
 *
 * 测试经 [MemoryByteRangeReader] 完全脱离 Android/RootService/文件系统，在 JVM 上确定性验证算法。
 */
class SparseLineIndexTest {

    /** 内存字节区间读取器：与 Android/RootService/文件系统解耦，用于确定性验证。 */
    private class MemoryByteRangeReader(private val bytes: ByteArray) : ByteRangeReader {
        override fun size(path: String): Long = bytes.size.toLong()
        override fun read(path: String, offset: Long, count: Long): ByteArray {
            val start = offset.toInt().coerceAtLeast(0)
            val end = minOf(start + count.toInt(), bytes.size)
            if (start >= bytes.size || end <= start) return ByteArray(0)
            return bytes.copyOfRange(start, end)
        }
    }

    private fun buildIndex(lines: List<String>, charset: Charset): Pair<SparseLineIndex, ByteRangeReader> {
        val bytes = lines.joinToString("\n").toByteArray(charset)
        val reader = MemoryByteRangeReader(bytes)
        val idx = runBlocking { SparseLineIndex.build("mem", charset, reader) }!!
        return idx to reader
    }

    @Test
    fun `build 统计 UTF-8 总行数，且不依赖行内容累积`() {
        val lines = (0 until 2000).map { "普通行 content $it" }
        val (idx, _) = buildIndex(lines, StandardCharsets.UTF_8)
        assertEquals(2000, idx.totalLines)
        // 索引内部只持有约 (2000/1024 + 1) 个偏移（Long 数组），远小于 2000 行字符串 —— 非全量累积。
        // 行寻址正确性由下面的 load 抽样证明。
    }

    @Test
    fun `load 对首中尾行返回精确内容，含超长行与多字节`() {
        val longLine = "A".repeat(500_000) // 单行长 500KB，跨多个 256KB 读取窗口（floor 窗口内多块拼接）
        val lines = (0 until 2000).map { i ->
            when (i) {
                100 -> "中文测试行内容包含汉字一二三四五六七八九十"
                500 -> longLine
                else -> "普通行 content $i"
            }
        }
        val (idx, reader) = buildIndex(lines, StandardCharsets.UTF_8)
        val provider = IndexedLineProvider(idx, "mem", StandardCharsets.UTF_8, reader)
        assertEquals("普通行 content 0", runBlocking { provider.load(0) })
        assertEquals("中文测试行内容包含汉字一二三四五六七八九十", runBlocking { provider.load(100) })
        val got500 = runBlocking { provider.load(500) }
        assertEquals("load(500) 长度应与 500KB 长行一致", longLine.length, got500.length)
        assertEquals("load(500) 内容应与 500KB 长行一致", longLine, got500)
        assertEquals("普通行 content 1999", runBlocking { provider.load(1999) })
        // 新设计：行级 LRU 已被 ChunkedDocument 的「原始字节块 LRU」取代（MP-Manager §9.2），
        // peek 不再持有任何已解码行 → 恒返回 null；目标行一律经 load 从块缓存现切。
        assertNull("行级缓存已移除，peek 恒为 null", provider.peek(0))
        assertNull(provider.peek(1999))
    }

    @Test
    fun `UTF-16LE 下 build与load 同样精确`() {
        val lines = (0 until 1500).map { i ->
            when (i) {
                200 -> "日本語のテスト行ねこ犬鳥魚"
                else -> "line $i"
            }
        }
        val (idx, reader) = buildIndex(lines, StandardCharsets.UTF_16LE)
        assertEquals(1500, idx.totalLines)
        val provider = IndexedLineProvider(idx, "mem", StandardCharsets.UTF_16LE, reader)
        assertEquals("line 0", runBlocking { provider.load(0) })
        assertEquals("日本語のテスト行ねこ犬鳥魚", runBlocking { provider.load(200) })
        assertEquals("line 1499", runBlocking { provider.load(1499) })
    }

    @Test
    fun `lineStartOffset 正确 floor 到索引点`() {
        // offsets[k] = 第 k*1024 行的起始偏移；共 4 个索引点（行 0 / 1024 / 2048 / 3072）。
        val idx = SparseLineIndex(longArrayOf(0L, 100L, 300L, 700L), 1000, 5000L, SparseLineIndex.GRANULARITY)
        assertEquals(0L, idx.lineStartOffset(0))
        assertEquals(0L, idx.lineStartOffset(1023))   // floor 0
        assertEquals(100L, idx.lineStartOffset(1024)) // floor 1024 -> k=1
        assertEquals(100L, idx.lineStartOffset(1500)) // 仍在本窗口内
        assertEquals(100L, idx.lineStartOffset(2047)) // floor 1024
        assertEquals(300L, idx.lineStartOffset(2048)) // floor 2048 -> k=2
        assertEquals(300L, idx.lineStartOffset(3071)) // floor 2048
        assertEquals(700L, idx.lineStartOffset(3072)) // floor 3072 -> k=3
        assertEquals(700L, idx.lineStartOffset(4000)) // floor 3072
        // 超出所有索引点：回退到文件尾，绝不返回错误偏移
        assertEquals(5000L, idx.lineStartOffset(99999))
    }

    @Test
    fun `空内容 build 返回 null，不阻断打开`() {
        val reader = MemoryByteRangeReader(ByteArray(0))
        val idx = runBlocking { SparseLineIndex.build("mem", StandardCharsets.UTF_8, reader) }
        assertNull("空文件不应建立索引（调用方回退旧策略）", idx)
    }

    @Test
    fun `FileSizeClass 四档阈值与策略标记`() {
        assertEquals(FileSizeClass.SMALL, FileSizeClass.of(0))
        assertEquals(FileSizeClass.SMALL, FileSizeClass.of(5L * 1024 * 1024 - 1))
        assertEquals(FileSizeClass.MEDIUM, FileSizeClass.of(5L * 1024 * 1024))
        assertEquals(FileSizeClass.MEDIUM, FileSizeClass.of(50L * 1024 * 1024 - 1))
        assertEquals(FileSizeClass.LARGE, FileSizeClass.of(50L * 1024 * 1024))
        assertEquals(FileSizeClass.LARGE, FileSizeClass.of(200L * 1024 * 1024 - 1))
        assertEquals(FileSizeClass.HUGE, FileSizeClass.of(200L * 1024 * 1024))
    }
}
