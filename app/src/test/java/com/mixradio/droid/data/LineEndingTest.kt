// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑器载入时把内存文本统一归一为 LF（Compose 的 BasicTextField 只按 '\n' 断行），
 * 保存时再按 [LineEnding] 还原。本用例锁定这两条契约，防止 CR-only 文本被显示成一行。
 */
class LineEndingTest {

    @Test
    fun `detect 识别主导换行风格`() {
        assertEquals(LineEnding.LF, LineEnding.detect("a\nb\n"))
        assertEquals(LineEnding.CRLF, LineEnding.detect("a\r\nb\r\n"))
        assertEquals(LineEnding.CR, LineEnding.detect("a\rb\r"))
        assertEquals(LineEnding.LF, LineEnding.detect(""))
    }

    @Test
    fun `apply 到 LF 会把 CRLF 与 CR 归一`() {
        assertEquals("a\nb\nc", LineEnding.apply("a\r\nb\r\nc", LineEnding.LF))
        assertEquals("a\nb\nc", LineEnding.apply("a\rb\rc", LineEnding.LF))
        assertEquals("a\nb", LineEnding.apply("a\nb", LineEnding.LF))
    }

    @Test
    fun `apply 能还原为 CRLF 与 CR`() {
        assertEquals("a\r\nb", LineEnding.apply("a\nb", LineEnding.CRLF))
        assertEquals("a\rb", LineEnding.apply("a\nb", LineEnding.CR))
    }

    @Test
    fun `CR-only 文本归一后行数与原文一致`() {
        val cr = "line1\rline2\rline3"
        val normalized = LineEnding.apply(cr, LineEnding.LF)
        assertEquals(3, TextStatistics.countLines(normalized))
        assertEquals(TextStatistics.countLines(cr), TextStatistics.countLines(normalized))
    }
}
