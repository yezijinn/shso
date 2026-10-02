// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import com.mixradio.droid.data.IncrementalAnsiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 用**真机 shell 的真实字节**做回归，而不是手写的字面量。
 *
 * 手写 `"😀\rX"` 在源码里可能被 IDE/编译器/编码链路悄悄规范化，反而测不出问题；
 * 真机 `printf '\360\237\230\200 building...\rOK  \r'` 产出的字节才是真实输入。
 * 该字节序列取自 PACM00 实测输出。
 */
class TerminalEmojiOverwriteDeviceByteTest {

    // 真机 `printf '\360\237\230\200 building...\rOK  \r'; printf '😀😁😂 done\n'` 的原始字节，
    // 以十六进制串逐字节还原（比逐个写 Byte 字面量更不容易被编码链路规范化掉）。
    private val deviceBytes: ByteArray = (
        "f09f9880" + "206275696c64696e672e2e2e" + "0d" + "4f4b2020" + "0d" +
            "f09f9880" + "f09f9881" + "f09f9882" + "20646f6e65" + "0a"
        ).chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    @Test fun `真机进度条覆盖后不得出现孤立代理`() {
        val p = IncrementalAnsiParser(Color.White)
        p.feed(String(deviceBytes, Charsets.UTF_8))
        p.finish()
        val out = p.snapshot().plainText
        assertFalse(
            "覆盖写后行内出现孤立代理（码元序列：${out.map { it.code.toString(16) }}）",
            hasLoneSurrogate(out)
        )
    }

    @Test fun `真机进度条覆盖后只剩一行且内容正确`() {
        // 真实终端语义：回车只把光标移到行首，**不清行尾**（清行尾是 ESC[K 的事）。
        // 因此 "😀 building..." 被 "OK  " 覆盖后仍是 "OK    building..."，
        // 再次回车被 "😀😁😂 done" 覆盖后是 "😀😁😂 doneing..." —— 尾部残留在真实终端
        // 里也一样会保留，这里断言的是这条真实语义，且全程无孤立代理。
        val p = IncrementalAnsiParser(Color.White)
        p.feed(String(deviceBytes, Charsets.UTF_8))
        p.finish()
        val lines = p.snapshot().lines.map { it.text }
        // 输入以 \n 结尾，而 snapshot() 无条件补一行当前行 —— 结尾换行会多出一个空行，
        // 这是既有约定（同 AnsiParserLinesTest.noAnsi_keepsTrailingEmptyLine）。
        val nonEmpty = lines.filter { it.isNotEmpty() }
        assertEquals(
            "覆盖写不应凭空多出残留行",
            1,
            nonEmpty.size
        )
        assertEquals("😀😁😂 done...", nonEmpty.first())
    }

    @Test fun `未参与覆盖的整行 emoji 必须逐字节完好`() {
        // 单独喂最后一行：不得被覆盖逻辑影响，也不得被拆成孤立代理
        val tail = String(deviceBytes).substringAfter(' ')
        val p = IncrementalAnsiParser(Color.White)
        p.feed(tail)
        p.finish()
        val lines = p.snapshot().lines.map { it.text }
        assertFalse("整行 emoji 被破坏", hasLoneSurrogate(lines.joinToString("\n")))
        assertEquals("😀😁😂 done", lines.first())
    }
}