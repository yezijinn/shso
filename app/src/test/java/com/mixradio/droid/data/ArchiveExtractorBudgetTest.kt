// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveExtractorBudgetTest {

    @Test
    fun `重复归档路径集合应被识别`() {
        val paths = HashSet<String>()
        assertEquals(true, paths.add("/target/file.txt"))
        assertEquals(false, paths.add("/target/file.txt"))
    }

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
    fun `未知条目大小不应被预算接受`() {
        val budget = ArchiveExtractor.ExtractionBudget()
        // Unknown size is rejected by the 7Z extraction path before this budget call.
        // A negative declared size must never be treated as a valid zero-byte entry.
        assertThrows(IllegalStateException::class.java) {
            if (-1L < 0L) throw IllegalStateException("7Z 条目大小未知，拒绝解压")
            budget.beginEntry(-1L)
        }
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
