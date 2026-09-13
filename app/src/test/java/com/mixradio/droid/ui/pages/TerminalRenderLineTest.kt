// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.pages

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.mixradio.droid.data.AnsiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端「单行渲染投影」的回归守卫。
 *
 * 背景：Compose 的 `Text` 会对整串文本做断行排版，成本与字符数成正比。实测单行 10 万字符
 * （`cat` 二进制 / minified JSON）会让主线程排版约 20 秒（Choreographer 跳帧 1210、
 * `Davey! duration=20182ms`、触发 ANR）。因此渲染前必须按行截断，
 * 而**模型层必须保持全文**（「复制输出」用的是 [com.mixradio.droid.data.ParsedAnsiResult.plainText]）。
 */
class TerminalRenderLineTest {

    private val defaultColor = Color(0xFFE0E0E0)

    @Test
    fun `短行原样返回（同一实例，不额外分配）`() {
        val line = AnnotatedString("ls -la /data/adb/shso")
        assertSame(line, renderableLine(line, maxChars = 4000))
    }

    @Test
    fun `超长行截断到上限并标注实际字符数`() {
        val line = AnnotatedString("A".repeat(200_000))
        val rendered = renderableLine(line, maxChars = 4000)
        assertTrue("截断后必须显著变短：${rendered.length}", rendered.length < 4100)
        assertTrue("截断内容必须是原文前缀", rendered.text.startsWith("A".repeat(100)))
        assertTrue("必须标注本行真实长度", rendered.text.contains("200000"))
        assertTrue("必须说明已截断", rendered.text.contains("已截断显示"))
    }

    @Test
    fun `截断点不切断代理对`() {
        // 第 9 个字符（下标 9）是 emoji 的高代理项：maxChars=10 时不能把它切一半
        val line = AnnotatedString("A".repeat(9) + "\uD83D\uDE00" + "B".repeat(50))
        val rendered = renderableLine(line, maxChars = 10)
        val text = rendered.text
        val markerStart = text.indexOf('…')
        val head = text.substring(0, markerStart).trimEnd()
        assertFalse("结尾不能是落单的高代理项", head.last().isHighSurrogate())
        assertTrue(head.startsWith("A".repeat(9)))
    }

    @Test
    fun `截断保留样式且样式区间不越过截断点`() {
        val red = SpanStyle(color = Color(0xFFFF5252))
        val line = buildAnnotatedString {
            withStyle(red) { append("R".repeat(5000)) }
        }
        val gray = SpanStyle(color = Color(0xFF757575))
        val rendered = renderableLine(line, markerStyle = gray, maxChars = 4000)
        // 原文样式仍在，且没有区间越过截断点（截断点之后只剩标注）
        val redSpans = rendered.spanStyles.filter { it.item.color == Color(0xFFFF5252) }
        assertEquals(1, redSpans.size)
        assertTrue("样式区间必须被裁到截断点内", redSpans.first().end <= 4000)
        // 标注使用传入的样式
        assertTrue(rendered.spanStyles.any { it.item.color == Color(0xFF757575) && it.start >= 4000 })
    }

    @Test
    fun `上限非法时不做截断`() {
        val line = AnnotatedString("A".repeat(10_000))
        assertSame(line, renderableLine(line, maxChars = 0))
    }

    @Test
    fun `模型层保留全文——截断只发生在渲染投影`() {
        // 20 万字符、无换行的单行输出
        val raw = "A".repeat(200_000) + "\nEND\n"
        val parsed = AnsiParser.parseAnsi(raw, defaultColor)
        val hugeLine = parsed.lines.first()
        assertEquals("解析结果必须保留整行", 200_000, hugeLine.length)
        assertTrue("复制输出（plainText）必须是全文", parsed.plainText.length >= 200_000)
        // 而渲染投影被截断
        assertTrue(renderableLine(hugeLine).length < 4100)
    }
}
