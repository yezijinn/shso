// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 私有模式 / 带中间字节的 CSI 序列（`ESC[?...h/l`、`ESC[>...c` 等）必须被**整体吞掉**，
 * 不得作为字面文本渲染。
 *
 * 背景：这类序列在真实终端里极其常见——`ESC[?25l`/`ESC[?25h`（隐藏/显示光标，进度条与动画几乎必发）、
 * `ESC[?2004h/l`（括号粘贴，readline/sh 系）、`ESC[?1049h/l`（备用屏幕）。它们参数区以
 * `? < = >` 开头，不属于 `[0-9;]`。若解析器只认数字/分号参数，就会把 ESC 之后的 `[?25l`
 * 当作普通文本输出，终端里出现 `[?25l` 之类的乱码。
 */
class AnsiParserPrivateModeTest {

    private val defaultColor = Color(0xFFE0E0E0)
    private val green = Color(0xFF4CAF50) // COLOR_MAP[32]

    /** 输入以 '\n' 结尾时，snapshot 末尾会带一条「当前未完成空行」，与 split('\n') 语义一致。 */
    private fun texts(input: String) = AnsiParser.parseAnsi(input, defaultColor).lines.map { it.text }

    @Test
    fun `隐藏光标序列 ESC-?-25l 不得泄漏为文本`() {
        assertEquals(listOf("AB", ""), texts("A\u001B[?25lB\n"))
    }

    @Test
    fun `显示光标序列 ESC-?-25h 不得泄漏为文本`() {
        assertEquals(listOf("AB", ""), texts("A\u001B[?25hB\n"))
    }

    @Test
    fun `括号粘贴模式 ESC-?-2004h 不得泄漏为文本`() {
        assertEquals(listOf("AB", ""), texts("A\u001B[?2004hB\n"))
    }

    @Test
    fun `备用屏幕切换 ESC-?-1049h 不得泄漏为文本`() {
        assertEquals(listOf("screen", ""), texts("\u001B[?1049hscreen\n"))
    }

    @Test
    fun `私有模式序列不得清掉已设置的 SGR 颜色`() {
        // ESC[?25l 中间夹在彩色输出里：后续文本必须仍是绿色
        val result = AnsiParser.parseAnsi("\u001B[32mgreen\u001B[?25l still green\n", defaultColor)
        assertEquals(listOf("green still green", ""), result.lines.map { it.text })
        assertEquals(green, result.lines[0].spanStyles.last().item.color)
    }

    @Test
    fun `常规 SGR 与普通文本不受影响`() {
        val result = AnsiParser.parseAnsi("\u001B[1;32mbold\nplain\n", defaultColor)
        assertEquals(listOf("bold", "plain", ""), result.lines.map { it.text })
        assertEquals(green, result.lines[0].spanStyles.first().item.color)
    }
}
