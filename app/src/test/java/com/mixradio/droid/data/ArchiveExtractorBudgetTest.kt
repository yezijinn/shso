// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveExtractorBudgetTest {

    @Test
    fun `条目数超过上限时 fail closed`() {
        val budget = ArchiveExtractor.ExtractionBudget()
        repeat(ArchiveExtractor.MAX_EXTRACT_ENTRIES) { budget.beginEntry(0L) }
        assertThrows(ArchiveExtractor.ExtractionLimitException::class.java) {
            budget.beginEntry(0L)
        }
        assertEquals(ArchiveExtractor.MAX_EXTRACT_ENTRIES, budget.entries)
    }

    @Test
    fun `单条目超过上限时拒绝`() {
        val budget = ArchiveExtractor.ExtractionBudget()
        assertThrows(ArchiveExtractor.ExtractionLimitException::class.java) {
            budget.beginEntry(ArchiveExtractor.MAX_EXTRACT_ENTRY_BYTES + 1)
        }
    }

    @Test
    fun `未知条目大小按未知处理而不是零字节或超限`() {
        // 7Z 的未知条目以 null 传入（部分解析器以 -1）：两者都必须按「大小未知」放行。
        // 当成零字节会少一道校验；被 -1 撞上单条目上限则会把合法包误拒。
        val budget = ArchiveExtractor.ExtractionBudget()
        budget.beginEntry(null)
        budget.beginEntry(-1L)
        assertEquals("两个未知条目都应放行并计数", 2, budget.entries)
        assertEquals("未知大小不得计入已消费字节", 0L, budget.bytes)
    }

    @Test
    fun `总输出超过上限时拒绝`() {
        val budget = ArchiveExtractor.ExtractionBudget()
        budget.consume(ArchiveExtractor.MAX_EXTRACT_BYTES.toInt())
        assertThrows(ArchiveExtractor.ExtractionLimitException::class.java) {
            budget.consume(1)
        }
    }
}
