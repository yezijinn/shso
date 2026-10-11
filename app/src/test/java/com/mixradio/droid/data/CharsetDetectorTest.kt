// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 编码探测的回归护栏。
 *
 * 核心缺陷：无 BOM 的 UTF-16 会被 ISO-8859-1 / UTF-8 兜底吞掉，编辑器满屏问号。
 * 判据依赖「ASCII 文本在 UTF-16 下每隔一个字节就是 0x00」这一特征，
 * 因此每条用例都给出真实字节形态而不是「随便造个字符串」。
 */
class CharsetDetectorTest {

    private fun utf16LeNoBom(text: String): ByteArray {
        val out = ByteArray(text.length * 2)
        text.forEachIndexed { i, c ->
            out[i * 2] = (c.code and 0xFF).toByte()
            out[i * 2 + 1] = (c.code shr 8).toByte()
        }
        return out
    }

    private fun utf16BeNoBom(text: String): ByteArray {
        val out = ByteArray(text.length * 2)
        text.forEachIndexed { i, c ->
            out[i * 2] = (c.code shr 8).toByte()
            out[i * 2 + 1] = (c.code and 0xFF).toByte()
        }
        return out
    }

    @Test fun `无 BOM 的 UTF-16LE ASCII 文本被正确识别`() {
        val bytes = utf16LeNoBom("hello world\nsecond line\n")
        val d = CharsetDetector.detect(bytes)
        assertEquals(StandardCharsets.UTF_16LE, d.charset)
        assertTrue("不应误报 BOM", !d.hasBom)
        assertEquals("hello world\nsecond line\n", d.text)
    }

    @Test fun `无 BOM 的 UTF-16BE ASCII 文本被正确识别`() {
        val bytes = utf16BeNoBom("hello world\nsecond line\n")
        val d = CharsetDetector.detect(bytes)
        assertEquals(StandardCharsets.UTF_16BE, d.charset)
        assertEquals("hello world\nsecond line\n", d.text)
    }

    @Test fun `无 BOM 的 UTF-16 中文文本仍可识别`() {
        // 中文在 UTF-16 下两字节都非 0，故 NUL 只出现在换行符处；
        // 判定要求「一侧显著更高」，这里验证换行占一定比例时仍能命中。
        val text = "第一行\n第二行\n第三行\n第四行\n第五行\n"
        val d = CharsetDetector.detect(utf16LeNoBom(text))
        assertEquals(StandardCharsets.UTF_16LE, d.charset)
        assertEquals(text, d.text)
    }

    @Test fun `纯 ASCII 文本不被误判为 UTF-16`() {
        val d = CharsetDetector.detect("plain ascii text without any special bytes".toByteArray(StandardCharsets.UTF_8))
        assertEquals(StandardCharsets.UTF_8, d.charset)
    }

    @Test fun `UTF-8 中文不被误判为 UTF-16`() {
        val d = CharsetDetector.detect("中文内容测试".toByteArray(StandardCharsets.UTF_8))
        assertEquals(StandardCharsets.UTF_8, d.charset)
        assertEquals("中文内容测试", d.text)
    }

    @Test fun `GB18030 中文不被误判为 UTF-16`() {
        val cs = java.nio.charset.Charset.forName("GB18030")
        val text = "中文内容测试第二行"
        val d = CharsetDetector.detect(text.toByteArray(cs))
        assertEquals("GB18030 中文必须走原路径而不是 UTF-16", cs, d.charset)
        assertEquals(text, d.text)
    }

    @Test fun `大段 GB18030 中文换行文本不被误判为 UTF-16`() {
        val cs = java.nio.charset.Charset.forName("GB18030")
        val text = (1..40).joinToString("\n") { "第 $it 行中文内容" }
        val d = CharsetDetector.detect(text.toByteArray(cs))
        assertEquals(cs, d.charset)
    }

    @Test fun `大段 UTF-8 中文换行文本不被误判为 UTF-16`() {
        val text = (1..40).joinToString("\n") { "第 $it 行中文内容" }
        val d = CharsetDetector.detect(text.toByteArray(StandardCharsets.UTF_8))
        assertEquals(StandardCharsets.UTF_8, d.charset)
    }

    /**
     * 在一段合法 UTF-8 的**字符边界**上插入一个非法字节，模拟「日志混入坏字节 / 尾部被截断」。
     *
     * 必须插在边界而非字节中点：落在多字节字符中间会把该字符切成两段非法序列、
     * 产生两个替换符，既有字符也不再完整，无法再断言「原文原样保住」。
     */
    private fun withStrayByte(valid: ByteArray, value: Byte = 0x80.toByte()): ByteArray {
        var at = valid.size / 2
        // 续接字节（10xxxxxx）之前不是字符边界，向后找到下一个起始字节
        while (at < valid.size && (valid[at].toInt() and 0xC0) == 0x80) at++
        val out = ByteArray(valid.size + 1)
        System.arraycopy(valid, 0, out, 0, at)
        out[at] = value
        System.arraycopy(valid, at, out, at + 1, valid.size - at)
        return out
    }

    @Test fun `长 UTF-8 中文混入一个非法字节仍判 UTF-8`() {
        // 此前判据以「字节数」为分母，文件越长阈值越松：长文件含 1 个坏字节会被降级判成
        // GB18030，中文全部乱码，保存一次就按 GB18030 全量重写 → 原始字节不可逆损坏。
        val text = (1..40).joinToString("\n") { "第 $it 行中文内容，样本长度足够" }
        val bytes = withStrayByte(text.toByteArray(StandardCharsets.UTF_8))
        val d = CharsetDetector.detect(bytes)
        assertEquals("长 UTF-8 文件含个别坏字节不得降级为 GB18030", StandardCharsets.UTF_8, d.charset)
        // 合法部分必须原样保住，只有那个坏字节变替换符
        assertEquals(text, d.text.replace("\uFFFD", ""))
    }

    @Test fun `短 UTF-8 中文混入一个非法字节仍判 UTF-8`() {
        // 短样本最容易被「按字节数取比例」的旧判据误伤：20 来个字节里 1 个坏字节，
        // 按字节算密度已到 1% 门槛边缘，按字符算则明显低于阈值。
        val text = "你好世界，中文测试"
        val bytes = withStrayByte(text.toByteArray(StandardCharsets.UTF_8))
        val d = CharsetDetector.detect(bytes)
        assertEquals(StandardCharsets.UTF_8, d.charset)
        assertEquals(text, d.text.replace("\uFFFD", ""))
    }

    @Test fun `纯 GB18030 中文仍判 GB18030（不被 UTF-8 兜底抢走）`() {
        val cs = java.nio.charset.Charset.forName("GB18030")
        val text = (1..40).joinToString("\n") { "第 $it 行中文内容" }
        val d = CharsetDetector.detect(text.toByteArray(cs))
        assertEquals(cs, d.charset)
        assertEquals(text, d.text)
    }

    @Test fun `ASCII 与 GB18030 中文混排仍判 GB18030`() {
        // 「大量 ASCII + 中文」是 Windows 中文文本的常见形态：ASCII 部分解码无替换符，
        // 会把整体替换符占比拉低（实测 0.26），阈值必须低于它，否则此类文件被判成 UTF-8、
        // 中文全乱码并在保存时按 UTF-8 全量重写。
        val cs = java.nio.charset.Charset.forName("GB18030")
        val text = (1..40).joinToString("\n") { "line $it: 中文内容 mixed with ascii 文本" }
        val d = CharsetDetector.detect(text.toByteArray(cs))
        assertEquals(cs, d.charset)
        assertEquals(text, d.text)
    }

    @Test fun `UTF-16LE 长中文文本解码内容完全一致`() {
        val text = (1..60).joinToString("\n") { "第 $it 行：中文与 ASCII mixed 混排" }
        val d = CharsetDetector.detect(utf16LeNoBom(text))
        assertEquals(StandardCharsets.UTF_16LE, d.charset)
        assertEquals(text, d.text)
    }

    @Test fun `UTF-16BE 长中文文本解码内容完全一致`() {
        val text = (1..60).joinToString("\n") { "第 $it 行：中文与 ASCII mixed 混排" }
        val d = CharsetDetector.detect(utf16BeNoBom(text))
        assertEquals(StandardCharsets.UTF_16BE, d.charset)
        assertEquals(text, d.text)
    }

    @Test fun `随机二进制不被误判为 UTF-16`() {
        // 伪随机字节：偶/奇位 NUL 分布接近，不可能有一侧占 30%
        val bytes = ByteArray(4096) { i -> ((i * 2654435761L) ushr 13).toByte() }
        assertNull(CharsetDetector.sniffUtf16WithoutBom(bytes))
    }

    @Test fun `含 NUL 的二进制不被误判为 UTF-16`() {
        val bytes = ByteArray(1024) { i -> if (i % 3 == 0) 0 else 'x'.code.toByte() }
        // NUL 均匀分布在两侧，比值都不超过 0.34 的 1/3… 实际 even=odd，不满足 2 倍优势
        assertNull(CharsetDetector.sniffUtf16WithoutBom(bytes))
    }

    @Test fun `过短的输入不嗅探`() {
        assertNull(CharsetDetector.sniffUtf16WithoutBom(ByteArray(0)))
        assertNull(CharsetDetector.sniffUtf16WithoutBom(byteArrayOf(0x61, 0x00, 0x62, 0x00)))
    }

    @Test fun `带 BOM 的路径不受影响`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val body = utf16LeNoBom("ab")
        val d = CharsetDetector.detect(bom + body)
        assertEquals(StandardCharsets.UTF_16LE, d.charset)
        assertTrue(d.hasBom)
        assertEquals("ab", d.text)
    }

    @Test fun `空输入不崩溃`() {
        val d = CharsetDetector.detect(ByteArray(0))
        assertEquals(StandardCharsets.UTF_8, d.charset)
        assertEquals("", d.text)
    }
}
