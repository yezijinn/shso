// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserLinesTest {

    // 默认的「无 ANSI 文本」色，便于断言「回退为默认色」的区间
    private val defaultColor = Color(0xFFE0E0E0)
    private val red = Color(0xFFFF5252) // COLOR_MAP[31]
    private val green = Color(0xFF4CAF50) // COLOR_MAP[32]

    /** 找到覆盖指定偏移（任意位置）的第一个区间的颜色，找不到则返回 null */
    private fun colorAt(line: AnnotatedString, offset: Int): Color? {
        return line.spanStyles.firstOrNull { offset in it.start until it.end }?.item?.color
    }

    @Test
    fun noAnsi_keepsTrailingEmptyLine() {
        val result = AnsiParser.parseAnsi("a\nb\n", defaultColor)
        assertEquals(3, result.lines.size)
        assertEquals("a", result.lines[0].text)
        assertEquals("b", result.lines[1].text)
        assertEquals("", result.lines[2].text)
        assertEquals("a\nb\n", result.plainText)
    }

    @Test
    fun sgrColorResetsAfterZero() {
        val input = "\u001B[31mred\u001B[0m plain"
        val result = AnsiParser.parseAnsi(input, defaultColor)
        assertEquals(1, result.lines.size)
        assertEquals("red plain", result.lines[0].text)
        // 前 3 个字符为红
        assertEquals(red, colorAt(result.lines[0], 0))
        assertEquals(red, colorAt(result.lines[0], 2))
        // 空格及之后的 「plain」 回退为默认色
        assertEquals(defaultColor, colorAt(result.lines[0], 3))
        assertEquals(defaultColor, colorAt(result.lines[0], 8))
    }

    @Test
    fun zeroThenColorAppliesRemainingCodes() {
        // 回归守卫：`\e[0;32m` 是先复位再设绿，大量 CLI 会这样输出。
        // 约束：codes 含 0（复位）后仍需继续处理后续码，不得提前 return 丢弃 32（否则渲染成默认色）。
        val result = AnsiParser.parseAnsi("\u001B[0;32mgreen", defaultColor)
        assertEquals(1, result.lines.size)
        assertEquals("green", result.lines[0].text)
        assertEquals(green, colorAt(result.lines[0], 0))
        assertEquals(green, colorAt(result.lines[0], 4))
    }

    @Test
    fun colorPersistsAcrossLines() {
        val input = "\u001B[32mgreen\nstill green\u001B[0m"
        val result = AnsiParser.parseAnsi(input, defaultColor)
        assertEquals(2, result.lines.size)
        assertEquals("green", result.lines[0].text)
        assertEquals("still green", result.lines[1].text)
        // 跨行颜色延续：两行都应为绿色（状态机跨行不丢色）
        assertEquals(green, colorAt(result.lines[0], 0))
        assertEquals(green, colorAt(result.lines[1], 0))
        assertEquals(green, colorAt(result.lines[1], 10))
    }

    @Test
    fun crlfIsNewline() {
        // \r\n 视为一次换行（\r 先把光标归零，随后 \n 收行）
        val result = AnsiParser.parseAnsi("a\r\nb", defaultColor)
        assertEquals(2, result.lines.size)
        assertEquals("a", result.lines[0].text)
        assertEquals("b", result.lines[1].text)
        assertEquals("a\nb", result.plainText)
    }

    @Test
    fun loneCrOverwritesInPlace() {
        // 真实终端语义：孤立 \r = 光标回到第 0 列，后续输出**原地覆盖**，不是换行。
        // 进度条 10% -> 20% -> 30% 只占 1 行，最终显示最后一次的内容（不再堆叠成 3 行）。
        val result = AnsiParser.parseAnsi("10%\r20%\r30%", defaultColor)
        assertEquals(1, result.lines.size)
        assertEquals("30%", result.lines[0].text)
    }

    @Test
    fun carriageReturnOverwritesCharByChar() {
        // 覆盖是按字符的，不是整行清空：abcdef\rXY -> XYcdef
        val result = AnsiParser.parseAnsi("abcdef\rXY", defaultColor)
        assertEquals(1, result.lines.size)
        assertEquals("XYcdef", result.lines[0].text)
    }

    @Test
    fun carriageReturnKeepsPrecedingLineIntact() {
        // \r 的作用域仅限当前行，不得影响已收行的历史内容
        val result = AnsiParser.parseAnsi("keep\nold\rnew", defaultColor)
        assertEquals(2, result.lines.size)
        assertEquals("keep", result.lines[0].text)
        assertEquals("new", result.lines[1].text)
    }

    @Test
    fun carriageReturnThenNewlineDoesNotLoseText() {
        // CRLF 与「写完再 \r 再换行」都不应丢字符
        val result = AnsiParser.parseAnsi("abc\r\ndef\r\n", defaultColor)
        assertEquals(3, result.lines.size)
        assertEquals("abc", result.lines[0].text)
        assertEquals("def", result.lines[1].text)
        assertEquals("", result.lines[2].text)
    }

    @Test
    fun emptyInput() {
        val result = AnsiParser.parseAnsi("", defaultColor)
        assertEquals("", result.plainText)
        assertTrue(result.lines.isEmpty())
    }
}
