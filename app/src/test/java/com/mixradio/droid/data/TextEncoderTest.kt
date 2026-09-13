// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 落盘编码：不可映射字符必须**报错**而非静默变成 `?`，否则会在用户看到「已保存」的情况下损坏文件。
 */
class TextEncoderTest {

    @Test
    fun `utf8 编码中文与 emoji 正常`() {
        val text = "中文内容 😀"
        val result = TextEncoder.encode(text, Charsets.UTF_8, writeBom = false)
        assertTrue(result.isSuccess)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), result.getOrThrow())
    }

    @Test
    fun `utf8 带 BOM 时前缀为 EF BB BF`() {
        val result = TextEncoder.encode("shso", Charsets.UTF_8, writeBom = true)
        val bytes = result.getOrThrow()
        assertEquals(0xEF, bytes[0].toInt() and 0xFF)
        assertEquals(0xBB, bytes[1].toInt() and 0xFF)
        assertEquals(0xBF, bytes[2].toInt() and 0xFF)
        assertEquals("shso", String(bytes.copyOfRange(3, bytes.size), Charsets.UTF_8))
    }

    @Test
    fun `utf16le 带 BOM 时前缀为 FF FE`() {
        val bytes = TextEncoder.encode("a", Charsets.UTF_16LE, writeBom = true).getOrThrow()
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xFE, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `iso-8859-1 无法表示中文时必须失败而不是替换成问号`() {
        val charset = Charset.forName("ISO-8859-1")
        val result = TextEncoder.encode("中文", charset, writeBom = false)
        assertTrue("必须失败", result.isFailure)
        assertTrue(
            "原因需可读: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("ISO-8859-1") == true
        )
        // 对照：默认 toByteArray 会静默产出 '??'（这正是要避免的行为）
        assertEquals("??", String("中文".toByteArray(charset), charset))
    }

    @Test
    fun `iso-8859-1 能表示的字符仍可正常编码`() {
        val charset = Charset.forName("ISO-8859-1")
        val bytes = TextEncoder.encode("café", charset, writeBom = false).getOrThrow()
        assertEquals("café", String(bytes, charset))
    }

    @Test
    fun `gbk 无法表示 emoji 时失败`() {
        val charset = Charset.forName("GBK")
        assertTrue(TextEncoder.encode("中文", charset, writeBom = false).isSuccess)
        assertFalse("GBK 不含 emoji", TextEncoder.canEncode("😀", charset))
    }

    @Test
    fun `canEncode 与 encode 结论一致`() {
        assertTrue(TextEncoder.canEncode("hello 中文", Charsets.UTF_8))
        assertFalse(TextEncoder.canEncode("中文", Charset.forName("US-ASCII")))
    }
}
