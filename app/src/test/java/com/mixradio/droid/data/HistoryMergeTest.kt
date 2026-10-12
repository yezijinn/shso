// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 历史条目合并规则：相同内容不新增条目，但**来源更强时升级**（自动快照 → 手动保存）。
 * 这是历史面板「来源标记」可信的前提（否则同一内容会同时存在两条不同来源）。
 */
class HistoryMergeTest {

    private fun e(content: String, ts: Long, source: EditHistoryManager.HistorySource) =
        EditHistoryManager.HistoryEntry(content, ts, source)

    private val DRAFT = EditHistoryManager.HistorySource.DRAFT
    private val AUTO = EditHistoryManager.HistorySource.AUTO
    private val SAVE = EditHistoryManager.HistorySource.SAVE

    @Test
    fun `内容与最新一致且来源不更强时无需写入`() {
        val existing = listOf(e("a", 100L, SAVE))
        assertNull(HistoryMerge.apply(existing, e("a", 200L, DRAFT)))
        assertNull(HistoryMerge.apply(existing, e("a", 200L, SAVE)))
    }

    @Test
    fun `相同内容来源更强时原地升级且保留首次时间`() {
        val existing = listOf(e("a", 100L, DRAFT), e("b", 50L, AUTO))
        val merged = HistoryMerge.apply(existing, e("a", 200L, SAVE))!!
        assertEquals(2, merged.size)
        assertEquals(SAVE, merged[0].source)
        assertEquals("升级不应改变时间戳（保留该内容首次出现时刻）", 100L, merged[0].timestamp)
        assertEquals("b", merged[1].content)
    }

    @Test
    fun `内容不同时插入到最前并丢弃同内容的旧条目`() {
        val existing = listOf(e("b", 50L, AUTO), e("a", 40L, AUTO), e("b", 30L, AUTO))
        val merged = HistoryMerge.apply(existing, e("a", 60L, SAVE))!!
        assertEquals(listOf("a", "b"), merged.map { it.content })
        assertEquals(SAVE, merged.first().source)
        assertEquals("同内容旧条目只保留最新一条", 1, merged.count { it.content == "b" })
    }

    @Test
    fun `条数上限为 19 条旧记录加 1 条新记录`() {
        val existing = (1..25).map { e("c$it", it.toLong(), AUTO) }
        val merged = HistoryMerge.apply(existing, e("new", 999L, SAVE))!!
        assertEquals(EditHistoryManager.maxHistoryPerFile(), merged.size)
        assertEquals("new", merged.first().content)
    }

    @Test
    fun `空历史直接插入`() {
        val merged = HistoryMerge.apply(emptyList(), e("a", 1L, DRAFT))!!
        assertEquals(1, merged.size)
        assertEquals("a", merged[0].content)
    }

    @Test
    fun `新文件恢复历史不得用空串 key 落盘`() {
        // 回归护栏：新建未保存的文件没有路径，`currentFilePath ?: ""` 会落成空串 key
        // （`history:`），把这份内容塞进所有新文件共享的同一个 key —— 既无从取回，
        // 又会被另一个新文件的恢复操作顶掉。无归属就不写。
        val s = File("src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt").readText()
        assertTrue(
            "须先确认路径存在再写历史",
            s.contains("if (cur != entry.content && path != null)")
        )
        assertTrue(
            "写历史须用非空 path",
            s.contains("EditHistoryManager.addHistory(path, cur, EditHistoryManager.HistorySource.DRAFT)")
        )
        assertFalse(
            "不得把空串当历史 key",
            s.contains("addHistory(currentFilePath ?: \"\"")
        )
    }
}
