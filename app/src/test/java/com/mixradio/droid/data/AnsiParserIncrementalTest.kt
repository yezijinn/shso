// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量解析（[IncrementalAnsiParser]）的等价性护栏。
 *
 * 核心不变式：**把同一份输入按任意切法分块喂入，结果必须与一次性全量解析完全一致。**
 * 这一条同时覆盖三处易错点：
 * - SGR 状态（颜色/粗体）跨块延续；
 * - 当前未完成行跨块延续（`\n` 才收行）；
 * - ESC 序列被块边界截断时缓冲到下一块。
 */
class AnsiParserIncrementalTest {

    private val defaultColor = Color(0xFFE0E0E0)
    private val red = Color(0xFFFF5252) // COLOR_MAP[31]
    private val green = Color(0xFF4CAF50) // COLOR_MAP[32]

    /** 行的可比较投影：文本 + 每个区间 (start,end,color) */
    private fun fingerprint(lines: List<AnnotatedString>): List<Pair<String, List<Triple<Int, Int, Color>>>> =
        lines.map { line ->
            line.text to line.spanStyles.map { Triple(it.start, it.end, it.item.color) }
        }

    private fun assertSameAsFullParse(input: String, chunkSizes: List<Int>) {
        val expected = fingerprint(AnsiParser.parseAnsi(input, defaultColor).lines)
        for (size in chunkSizes) {
            val parser = IncrementalAnsiParser(defaultColor)
            var i = 0
            while (i < input.length) {
                val end = (i + size).coerceAtMost(input.length)
                parser.feed(input.substring(i, end))
                i = end
            }
            parser.finish()
            assertEquals(
                "按 ${size} 字符分块喂入的结果应与全量解析一致（input=${input.replace("\u001B", "<ESC>")}）",
                expected,
                fingerprint(parser.snapshot().lines)
            )
        }
    }

    @Test
    fun `任意切法分块喂入都等价于全量解析`() {
        val samples = listOf(
            "plain text without escapes\nsecond line\n",
            "\u001B[31mred\u001B[0m plain\n",
            "\u001B[32mgreen\nstill green\u001B[0m\n",
            "\u001B[1m\u001B[31mbold red\u001B[22m normal\n",
            "\u001B[38;5;196mxterm256\u001B[0m tail",
            "\u001B[38;2;10;20;30mtruecolor\u001B[0m",
            "10%\r20%\r30%",
            "abc\r\ndef\r\n",
            "keep\nold\rnew",
            "",
            "\n\n\n",
            "no trailing newline"
        )
        // 1 会把每个 ESC 序列都切开，是最狠的切法；再覆盖若干常见 flush 尺寸
        val chunkSizes = listOf(1, 2, 3, 5, 7, 13, 64, 2048)
        for (sample in samples) assertSameAsFullParse(sample, chunkSizes)
    }

    @Test
    fun `SGR 状态跨块延续`() {
        // 颜色在第 1 块设置，文字在第 2 块才出现 —— 状态不得丢
        val parser = IncrementalAnsiParser(defaultColor)
        parser.feed("\u001B[32m")
        parser.feed("green text")
        val lines = parser.snapshot().lines
        assertEquals(1, lines.size)
        assertEquals("green text", lines[0].text)
        assertEquals(green, lines[0].spanStyles.first().item.color)
    }

    @Test
    fun `未完成的当前行跨块延续`() {
        val parser = IncrementalAnsiParser(defaultColor)
        parser.feed("hello ")
        assertEquals(1, parser.snapshot().lines.size)
        parser.feed("world\n")
        assertEquals(2, parser.snapshot().lines.size)
        assertEquals("hello world", parser.snapshot().lines[0].text)
    }

    @Test
    fun `被截断的 ESC 序列缓冲到下一块`() {
        val parser = IncrementalAnsiParser(defaultColor)
        parser.feed("\u001B[3")      // 序列未完，应缓冲而不是输出 "<ESC>[3"
        parser.feed("1mred")         // 补齐 -> 红色
        val lines = parser.snapshot().lines
        assertEquals(1, lines.size)
        assertEquals("red", lines[0].text)
        assertEquals(red, lines[0].spanStyles.first().item.color)
    }

    @Test
    fun `进度条跨块喂入仍只占一行`() {
        val parser = IncrementalAnsiParser(defaultColor)
        for (p in listOf("10%", "20%", "30%", "100%")) {
            parser.feed("\r$p")
        }
        val lines = parser.snapshot().lines
        assertEquals(1, lines.size)
        assertEquals("100%", lines[0].text)
    }

    @Test
    fun `进度条不再让日志无限增长`() {
        // 这正是做 \r 原地覆盖的动机：旧语义下 1000 次刷新 = 1000 行，
        // 日志体积与后续解析成本随刷新次数线性增长。
        val parser = IncrementalAnsiParser(defaultColor)
        repeat(1000) { parser.feed("\rprogress $it%") }
        val lines = parser.snapshot().lines
        assertEquals(1, lines.size)
        assertTrue(lines[0].text.startsWith("progress "))
    }

    @Test
    fun `reset 后回到初始状态`() {
        val parser = IncrementalAnsiParser(defaultColor)
        parser.feed("\u001B[31mred\nline")
        parser.reset()
        assertTrue(parser.snapshot().lines.isEmpty())
        parser.feed("fresh")
        assertEquals(listOf("fresh"), parser.snapshot().lines.map { it.text })
    }

    @Test
    fun `finish 把残缺 ESC 当普通文本收尾`() {
        // 与旧实现「正则不匹配即原文保留」一致；持续运行的终端不应调用 finish。
        val parser = IncrementalAnsiParser(defaultColor)
        parser.feed("a\u001B")
        parser.finish()
        assertEquals("a\u001B", parser.snapshot().lines.single().text)
    }
}
