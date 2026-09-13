// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 终端日志里常见的**非 SGR 控制序列 / C0 控制字符**必须被正确消化，不能变成可见乱码。
 *
 * 覆盖四类实际会遇到的输出：
 * - `OSC`（`ESC ] … BEL` 或 `ESC ] … ESC \`）：设置窗口标题、OSC 8 超链接，
 *   不识别会在日志里留下 `]0;标题` / `]8;;https://…` 这种尾巴；
 * - 两字符 / 带中间字节的 ESC 序列（`ESC(B` 选字符集、`ESC=`/`ESC>` 键盘模式、`ESC7`/`ESC8` 保存恢复光标）；
 * - 退格 `\b`：光标左移一列且后续输出覆盖该列（`\b` 本身不删字符）；
 * - `ESC[K` 行内擦除：进度条用 `\r ESC[K` 清行重绘，不处理会残留上一帧的旧字符。
 */
class AnsiParserControlSequenceTest {

    private val defaultColor = Color(0xFFE0E0E0)
    private val red = Color(0xFFFF5252) // COLOR_MAP[31]
    private val green = Color(0xFF4CAF50) // COLOR_MAP[32]

    private fun texts(input: String) = AnsiParser.parseAnsi(input, defaultColor).lines.map { it.text }

    // ---- OSC ----

    @Test
    fun `OSC 窗口标题（BEL 终止）不得泄漏为文本`() {
        assertEquals(listOf("AB", ""), texts("A\u001B]0;title\u0007B\n"))
    }

    @Test
    fun `OSC 超链接（ST 终止）不得泄漏为文本`() {
        assertEquals(
            listOf("AlinkB", ""),
            texts("A\u001B]8;;https://example.com\u001B\\link\u001B]8;;\u001B\\B\n")
        )
    }

    @Test
    fun `OSC 被块边界截断时缓冲到下一块`() {
        val parser = IncrementalAnsiParser(defaultColor)
        parser.feed("A\u001B]0;ti")
        parser.feed("tle\u0007B")
        assertEquals(listOf("AB"), parser.snapshot().lines.map { it.text })
    }

    // ---- 其它 ESC 序列 ----

    @Test
    fun `ESC 加中间字节的字符集切换不得留下残余字符`() {
        assertEquals(listOf("Atext", ""), texts("A\u001B(Btext\n"))
    }

    @Test
    fun `两字符 ESC 序列（键盘模式 保存恢复光标）不得泄漏为文本`() {
        assertEquals(listOf("ABCD", ""), texts("A\u001B=B\u001B>C\u001B7D\u001B8\n"))
    }

    // ---- 退格 ----

    @Test
    fun `退格左移光标且后续字符覆盖该列`() {
        // abc -> 光标 3 -> 两次退格到列 1 -> 写入 XY 覆盖 b、c
        assertEquals(listOf("aXY", ""), texts("abc\u0008\u0008XY\n"))
    }

    @Test
    fun `退格不删字符 也不越过行首`() {
        // 行首连续退格无副作用；ab 后退格两次回到列 0，写 X 覆盖 a
        assertEquals(listOf("Xb", ""), texts("\u0008\u0008ab\u0008\u0008\u0008X\n"))
    }

    // ---- 行内擦除 ----

    @Test
    fun `ESC K 从光标擦到行尾`() {
        assertEquals(listOf("xy", ""), texts("abc\r\u001B[Kxy\n"))
    }

    @Test
    fun `ESC 0K 与省略参数等价`() {
        assertEquals(listOf("xy", ""), texts("abc\r\u001B[0Kxy\n"))
    }

    @Test
    fun `ESC 2K 整行清空`() {
        assertEquals(listOf("z", ""), texts("abcdef\u001B[2Kz\n"))
    }

    @Test
    fun `ESC K 之后的着色不得被擦除前的样式区间污染`() {
        // 红色 abcdef 被 \r + ESC[K 清掉后写绿色 green：整行必须是绿色，不能残留红色区间
        val result = AnsiParser.parseAnsi("\u001B[31mabcdef\r\u001B[K\u001B[32mgreen\n", defaultColor)
        assertEquals(listOf("green", ""), result.lines.map { it.text })
        assertEquals(green, result.lines[0].spanStyles.first().item.color)
    }

    @Test
    fun `进度条 回车加擦行 只占一行且取最后一帧`() {
        val parser = IncrementalAnsiParser(defaultColor)
        for (p in listOf("10%", "20%", "100%")) {
            parser.feed("\r\u001B[K$p")
        }
        assertEquals(listOf("100%"), parser.snapshot().lines.map { it.text })
    }

    // ---- C0 控制字符 ----

    @Test
    fun `BEL NUL 换页与 DEL 等控制字符不进入文本`() {
        assertEquals(listOf("ABCDE", ""), texts("A\u0007B\u0000C\u000CD\u007FE\n"))
    }

    @Test
    fun `制表符保留为文本`() {
        assertEquals(listOf("A\tB", ""), texts("A\tB\n"))
    }
}
