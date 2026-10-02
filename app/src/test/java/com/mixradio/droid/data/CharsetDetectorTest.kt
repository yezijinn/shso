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
