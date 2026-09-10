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
    fun crlfAndLoneCrNormalized() {
        // \r\n 视为换行；孤立 \r 剥离
        val result = AnsiParser.parseAnsi("a\r\nb\r c", defaultColor)
        assertEquals(3, result.lines.size)
        assertEquals("a", result.lines[0].text)
        assertEquals("b", result.lines[1].text)
        assertEquals(" c", result.lines[2].text)
        assertEquals("a\nb\n c", result.plainText)
    }

    @Test
    fun emptyInput() {
        val result = AnsiParser.parseAnsi("", defaultColor)
        assertEquals("", result.plainText)
        assertTrue(result.lines.isEmpty())
    }
}
