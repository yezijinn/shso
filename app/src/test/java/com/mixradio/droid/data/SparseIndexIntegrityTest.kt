// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三轮深挖的回归：稀疏索引/分块读取的行数口径与静默数据损坏。
 *
 * 这类缺陷的共同特征是**不抛异常、不提示**，只在特定输入下产出错误结果，
 * 因此必须以断言把口径钉死。
 */
class SparseIndexIntegrityTest {

    private fun readerOf(path: String): ByteRangeReader = object : ByteRangeReader {
        override fun size(p: String): Long = java.io.File(path).length()
        override fun read(p: String, offset: Long, count: Long): ByteArray {
            val f = java.io.File(path)
            if (offset >= f.length()) return ByteArray(0)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(offset)
                val want = minOf(count, f.length() - offset).toInt()
                val buf = ByteArray(want)
                var got = 0
                while (got < want) {
                    val n = raf.read(buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                return if (got <= 0) ByteArray(0) else buf.copyOf(got)
            }
        }
    }

    private fun tmp(name: String, content: String): String {
        val f = java.io.File.createTempFile(name, ".txt")
        f.deleteOnExit()
        f.writeText(content, Charsets.UTF_8)
        return f.absolutePath
    }

    // ---------- ① 行数口径：末尾换行不多算一行 ----------

    @Test fun `以换行结尾的文件行数等于换行数_不多算一行`() = runBlocking {
        // "a\nb\nc\n" 有 3 个换行、3 行真实内容。旧实现无条件 `count + 1` → 4。
        val p = tmp("rows", "a\nb\nc\n")
        val idx = SparseLineIndex.build(p, Charsets.UTF_8, readerOf(p))
        assertNotNull("索引必须建成", idx)
        assertEquals("以换行结尾的行数不得多算", 3, idx!!.totalLines)
    }

    @Test fun `不以换行结尾的文件行数等于换行数加一`() = runBlocking {
        val p = tmp("rows2", "a\nb\nc")
        val idx = SparseLineIndex.build(p, Charsets.UTF_8, readerOf(p))
        assertNotNull(idx)
        assertEquals(3, idx!!.totalLines)
    }

    @Test fun `单行无换行文件行数为 1`() = runBlocking {
        val p = tmp("rows3", "abc")
        val idx = SparseLineIndex.build(p, Charsets.UTF_8, readerOf(p))
        assertNotNull(idx)
        assertEquals(1, idx!!.totalLines)
    }

    @Test fun `行号与索引 totalLines 在末尾换行文件上一致`() = runBlocking {
        // 稀疏索引与分段回退路径（编辑器 split('\n') + 末尾空串则 dropLast）
        // 曾经口径相反，导致同一文件行号数在 N 与 N+1 之间跳变。
        val p = tmp("rows4", "l1\nl2\nl3\nl4\n")
        val idx = SparseLineIndex.build(p, Charsets.UTF_8, readerOf(p))
        assertNotNull(idx)
        val segs = tmp("rows4", "l1\nl2\nl3\nl4\n")
            .let { java.io.File(it).readText().split('\n').toMutableList() }
        if (segs.isNotEmpty() && segs.last().isEmpty()) segs.removeAt(segs.lastIndex)
        assertEquals("索引与分段回退的行数必须一致", segs.size, idx!!.totalLines)
    }

    // ---------- ② 短读必须被识别为「读不满」 ----------

    @Test fun `读取字节数少于文件总长时 isComplete 为 false`() {
        // 调用方（编辑器）据此拒绝编辑：否则残缺文本会被标成「未修改」，
        // 用户随手一存就把原文件覆盖成截断内容。
        val full = ChunkedFileReader.LoadResult("x", Charsets.UTF_8, false, 100L, 100, 0L)
        val partial = ChunkedFileReader.LoadResult("x", Charsets.UTF_8, false, 100L, 40, 0L)
        assertTrue("读满时必须视为完整", full.isComplete)
        assertFalse("短读必须被识别为不完整", partial.isComplete)
    }

    @Test fun `isComplete 在空文件与边界上不误判`() {
        assertTrue(ChunkedFileReader.LoadResult("", Charsets.UTF_8, false, 0L, 0, 0L).isComplete)
        assertTrue(ChunkedFileReader.LoadResult("x", Charsets.UTF_8, false, 10L, 10, 0L).isComplete)
        assertFalse(ChunkedFileReader.LoadResult("x", Charsets.UTF_8, false, 10L, 9, 0L).isComplete)
    }

    // ---------- ③ 编辑历史截断必须打标，否则恢复会丢文件开头 ----------

    @Test fun `历史条目标记 truncated 以阻止破坏性恢复`() {
        val full = EditHistoryManager.HistoryEntry("abc", 1L)
        val partial = EditHistoryManager.HistoryEntry("bc", 2L, truncated = true)
        assertFalse("完整内容不得标记为截断", full.truncated)
        assertTrue("超限截断的内容必须打标", partial.truncated)
    }
}
