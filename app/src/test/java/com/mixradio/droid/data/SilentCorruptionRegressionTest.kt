// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文本处理链路的「静默出错」回归护栏。
 *
 * 这一组的共同特征是**不崩溃**：用户看不到任何报错，只会看到内容不对 ——
 * 错位的行、空白的行、裂开的 emoji。崩溃反而更容易被发现，因此更需要钉死。
 */
class SilentCorruptionRegressionTest {

    // ========================================================================
    // 1) 代理对跨块续接
    // ========================================================================

    private fun plain(p: IncrementalAnsiParser): String =
        p.snapshot().lines.joinToString("") { it.text }

    /** 😀 的高/低代理，单独构造以便把分块边界切在代理对中间。 */
    private val HI = "\uD83D"
    private val LO = "\uDE00"
    private val EMOJI = "$HI$LO"

    @Test fun `代理对被分块切开时必须续接`() {
        // RootService 用 CharArray(1024) 读 stdout 后整块入队，分块边界与码点无关，
        // emoji 的高低代理完全可能分处两次 feed()。此前只有转义序列跨块续接，
        // 孤立高代理直接落进文本：渲染成豆腐块且多占一列，复制输出还会写坏剪贴板。
        val parser = IncrementalAnsiParser(Color.White)
        parser.feed("A$HI")      // 第一块以**高**代理结尾
        parser.feed("${LO}B")    // 第二块以**低**代理开头
        val text = plain(parser)
        assertTrue("emoji 必须完整保留，实际=[$text]", text.contains(EMOJI))
        assertTrue("两侧内容都不能丢", text.contains("A") && text.contains("B"))
    }

    @Test fun `孤立高代理在收尾时按替换字符落地`() {
        val parser = IncrementalAnsiParser(Color.White)
        parser.feed("X$HI")
        parser.finish()
        val text = plain(parser)
        assertTrue("原有内容必须保留", text.contains("X"))
        assertFalse("收尾后不得残留孤立高代理", text.contains(HI))
    }

    @Test fun `单块内的代理对不受影响`() {
        val parser = IncrementalAnsiParser(Color.White)
        parser.feed("hi $EMOJI ok")
        assertEquals("hi $EMOJI ok", plain(parser))
    }

    @Test fun `连续多块反复切开也不得累积孤立代理`() {
        val parser = IncrementalAnsiParser(Color.White)
        parser.feed("1$HI")
        parser.feed("${LO}2$HI")
        parser.feed("${LO}3")
        parser.finish()
        assertEquals("1${EMOJI}2${EMOJI}3", plain(parser))
    }

    // ========================================================================
    // 2) 稀疏索引：中途短读不得静默跳过字节
    // ========================================================================

    /** 可控制「哪些块短读」的内存 reader。 */
    private class ShortReadReader(
        private val full: ByteArray,
        private val shortAt: Set<Int>,
        private val shortLen: Int
    ) : ByteRangeReader {
        override fun size(path: String): Long = full.size.toLong()
        override fun read(path: String, offset: Long, count: Long): ByteArray {
            val cs = 256L * 1024L
            val start = offset.toInt()
            val end = minOf(start + count.toInt(), full.size)
            if (start >= end) return ByteArray(0)
            var n = end - start
            if ((offset / cs).toInt() in shortAt) n = minOf(n, shortLen)
            return full.copyOfRange(start, start + n)
        }
    }

    @Test fun `中途短读不得返回错位的行`() = runBlocking {
        // 块缓存只存满块，非满块必然来自 reader 短读（root 路径 dd 读到被并发截断的文件、
        // 或 RandomAccessFile.read 契约允许的短读）。原实现在循环里无条件 ci++，
        // 该块剩余字节被**永久跳过** → 换行计数少计 → 后续行整体前移，
        // 而行号仍然「看起来正确」，用户复制该行拿到的却是文件里更靠后的另一行。
        val sb = StringBuilder()
        repeat(4000) { sb.append("block0-").append(it).append('\n') }
        repeat(4000) { sb.append("block1-").append(it).append('\n') }
        repeat(4000) { sb.append("block2-").append(it).append('\n') }
        repeat(4000) { sb.append("block3-").append(it).append('\n') }
        val bytes = sb.toString().toByteArray()
        val tmp = File.createTempFile("shortread", ".log")
        try {
            tmp.writeBytes(bytes)
            val healthy = object : ByteRangeReader {
                override fun size(path: String) = bytes.size.toLong()
                override fun read(path: String, offset: Long, count: Long): ByteArray {
                    val s = offset.toInt()
                    val e = minOf(s + count.toInt(), bytes.size)
                    return if (s >= e) ByteArray(0) else bytes.copyOfRange(s, e)
                }
            }
            val index = requireNotNull(SparseLineIndex.build(tmp.absolutePath, Charsets.UTF_8, healthy))
            val target = index.totalLines - 5
            val expected = IndexedLineProvider(index, tmp.absolutePath, Charsets.UTF_8, healthy).load(target)
            assertTrue("基线本身应能读到 block3 内容，实际=[$expected]", expected.startsWith("block3-"))

            // 第 1 块只给 1000 字节：其后 261144 字节在旧实现里会被整段跳过
            val shortReader = ShortReadReader(bytes, shortAt = setOf(1), shortLen = 1000)
            val got = IndexedLineProvider(index, tmp.absolutePath, Charsets.UTF_8, shortReader).load(target)

            assertFalse(
                "短读时不得返回看起来正常但内容错位的行，实际=[$got] 期望=[$expected]",
                got.isNotEmpty() && got != expected
            )
        } finally {
            tmp.delete()
        }
    }

    // ========================================================================
    // 3) 超长行截断：已读到的内容必须保留
    // ========================================================================

    @Test fun `超长行被截断时必须保留已读内容`() = runBlocking {
        // truncated = true 的前提就是 newlines < need，而 split('\n') 最多产出
        // newlines + 1 段，getOrNull(i - floor) 必为 null —— 旧实现 `?: ""` 把已经
        // 读到的 4MB 全部丢掉，用户只看到一行「已截断」提示、一个字内容都没有。
        val f = File.createTempFile("longline", ".log")
        try {
            f.writeText("X".repeat(5 * 1024 * 1024))   // 单行 5MB，超过 4MB 上限
            val reader = object : ByteRangeReader {
                override fun size(path: String) = f.length()
                override fun read(path: String, offset: Long, count: Long): ByteArray =
                    java.io.RandomAccessFile(path, "r").use { raf ->
                        raf.seek(offset)
                        val buf = ByteArray(count.toInt())
                        val n = raf.read(buf)
                        if (n <= 0) ByteArray(0) else buf.copyOf(n)
                    }
            }
            val index = requireNotNull(SparseLineIndex.build(f.absolutePath, Charsets.UTF_8, reader))
            val got = IndexedLineProvider(index, f.absolutePath, Charsets.UTF_8, reader)
                .load(index.totalLines - 1)
            assertTrue("必须真的读到内容而不是只剩提示，实际长度=${got.length}", got.length > 1000)
            assertTrue("必须含截断标记", got.contains("已截断显示"))
        } finally {
            f.delete()
        }
    }

    // ========================================================================
    // 4) 跨线程可见性与代次守卫
    // ========================================================================

    @Test fun `跨线程字段必须声明 volatile`() {
        // batchFlushEpoch 明确加了 @Volatile 并注明「跨线程可见」，batchFlushJob 却是
        // 同文件同用法的裸 var：主线程 stop 时可能读到旧值 → `job !== owner` 成立 →
        // 静默 return，循环根本没停，旧循环继续 drain 队列与命令收尾争抢，输出顺序错乱。
        val s = File("src/main/java/com/mixradio/droid/data/HyperCore.kt").readText()
        assertTrue(
            "batchFlushJob 必须 @Volatile",
            s.contains("@Volatile\n    private var batchFlushJob")
        )
    }

    @Test fun `发布循环退出前必须校验清屏代次`() {
        // 循环体用代次丢弃清屏前的积压，finally 却无守卫：清屏只递增 epoch、清不掉循环
        // 局部 pending，用户点清屏后循环恰好退出时会把整段旧输出回灌到屏幕。
        val s = File("src/main/java/com/mixradio/droid/data/HyperCore.kt").readText()
        val fn = s.indexOf("fun startBatchFlushLoop")
        assertTrue("应能找到 startBatchFlushLoop", fn > 0)
        val body = s.substring(fn, fn + 4000)
        val fin = body.indexOf("} finally {")
        assertTrue("应存在 finally 块", fin > 0)
        val finBody = body.substring(fin, minOf(fin + 400, body.length))
        assertTrue(
            "finally 内的 flush 必须带代次守卫",
            finBody.contains("batchFlushEpoch == seenEpoch")
        )
    }
}
