// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 编码探测回归。
 *
 * 非严格 UTF-8 时若**无条件**落 GB18030，单个非法字节即让整个文件被重解释，
 * 保存后原始字节不可逆损坏。
 */
class EncodingAndShellExecRegressionTest {

    private fun gb(name: String): Charset = Charset.forName(name)

    // ---------- 编码探测 ----------

    // ---------- ① 编码探测 ----------

    @Test fun `非法字节夹在 UTF-8 中间时不得整体重解释为 GB18030`() {
        // 「基本是 ASCII + 中间夹 1 个非法 UTF-8 字节」：日志里混进一行 GB18030 中文、
        // 或日志被截断在多字节字符中间，都会形成这种文件。
        val good = "hello world\n".toByteArray(Charsets.UTF_8)
        val bad = ByteArray(good.size + 1)
        good.copyInto(bad)
        bad[good.size] = 0xFF.toByte()   // 非法起始字节

        val d = CharsetDetector.detect(bad)
        assertNotEquals(
            "含非法字节的 UTF-8 不得被判成 GB18030（否则保存即不可逆改写整个文件）",
            gb("GB18030"), d.charset
        )
    }

    @Test fun `真正的 GB18030 中文仍须被正确识别`() {
        val bytes = "中文测试内容".toByteArray(gb("GB18030"))
        val d = CharsetDetector.detect(bytes)
        assertEquals("真正的 GB18030 不得被误伤", gb("GB18030"), d.charset)
        assertEquals("解码文本必须正确", "中文测试内容", d.text)
    }

    @Test fun `纯 UTF-8 中文不得被误判`() {
        val bytes = "中文测试内容".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, CharsetDetector.detect(bytes).charset)
    }

    @Test fun `无回退路径时按 ISO-8859-1 兜底且可无损往返`() {
        // ISO-8859-1 对任意字节序列一一映射，往返无损 —— 这是「不破坏用户数据」的最后保证
        val bad = byteArrayOf(0x41, 0xFF.toByte(), 0x42, 0x80.toByte())
        val d = CharsetDetector.detect(bad)
        val roundTrip = d.text.toByteArray(d.charset)
        assertTrue(
            "ISO-8859-1 路径必须字节级无损往返",
            roundTrip.contentEquals(bad)
        )
    }

}
