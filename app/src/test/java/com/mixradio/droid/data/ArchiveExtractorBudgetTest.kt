// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

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
    fun `总输出超过上限时拒绝`() {
        val budget = ArchiveExtractor.ExtractionBudget()
        budget.consume(ArchiveExtractor.MAX_EXTRACT_BYTES.toInt())
        assertThrows(ArchiveExtractor.ExtractionLimitException::class.java) {
            budget.consume(1)
        }
    }
}
