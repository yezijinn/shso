// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 [applyFileViewSettings] 的过滤与排序契约。
 *
 * 该函数是全工程唯一的文件列表视图转换入口（「文件」页与内置文件选择器共用），
 * 目录恒在最前、隐藏文件过滤、名称/时间升/降序四种模式的语义都在此固定。
 */
class FileListViewSettingsTest {

    private fun dir(name: String, lastModified: Long = 0L) =
        FileItem(name = name, path = "/root/$name", isDirectory = true, lastModified = lastModified)

    private fun file(name: String, lastModified: Long = 0L) =
        FileItem(name = name, path = "/root/$name", isDirectory = false, lastModified = lastModified)

    private fun names(items: List<FileItem>) = items.map { it.name }

    @Test
    fun `directories always stay before files regardless of sort mode`() {
        val list = listOf(file("a.txt"), dir("zzz"), file("b.txt"), dir("aaa"))

        // 名称升序 / 降序都只在「目录组」「文件组」内部生效，分组顺序不跨越
        assertEquals(
            listOf("aaa", "zzz", "a.txt", "b.txt"),
            names(applyFileViewSettings(list, true, AppSettings.FILE_SORT_NAME_ASC))
        )
        assertEquals(
            listOf("zzz", "aaa", "b.txt", "a.txt"),
            names(applyFileViewSettings(list, true, AppSettings.FILE_SORT_NAME_DESC))
        )
    }

    @Test
    fun `name sort is case insensitive ascending`() {
        val list = listOf(file("banana"), file("Apple"), file("cherry"))
        assertEquals(
            listOf("Apple", "banana", "cherry"),
            names(applyFileViewSettings(list, true, AppSettings.FILE_SORT_NAME_ASC))
        )
    }

    @Test
    fun `name sort descending reverses only within each group`() {
        val list = listOf(file("banana"), dir("zDir"), file("Apple"), dir("aDir"))
        assertEquals(
            listOf("zDir", "aDir", "banana", "Apple"),
            names(applyFileViewSettings(list, true, AppSettings.FILE_SORT_NAME_DESC))
        )
    }

    @Test
    fun `time sort orders by lastModified in both directions`() {
        val list = listOf(file("old", 100L), file("new", 300L), file("mid", 200L))
        assertEquals(
            listOf("old", "mid", "new"),
            names(applyFileViewSettings(list, true, AppSettings.FILE_SORT_TIME_ASC))
        )
        assertEquals(
            listOf("new", "mid", "old"),
            names(applyFileViewSettings(list, true, AppSettings.FILE_SORT_TIME_DESC))
        )
    }

    @Test
    fun `hidden entries are filtered out unless showHiddenFiles is set`() {
        val list = listOf(dir(".git"), file(".hidden"), file("visible.txt"))

        assertEquals(
            listOf("visible.txt"),
            names(applyFileViewSettings(list, showHiddenFiles = false, sortMode = AppSettings.FILE_SORT_NAME_ASC))
        )
        assertEquals(
            listOf(".git", ".hidden", "visible.txt"),
            names(applyFileViewSettings(list, showHiddenFiles = true, sortMode = AppSettings.FILE_SORT_NAME_ASC))
        )
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(
            emptyList<String>(),
            names(applyFileViewSettings(emptyList(), true, AppSettings.FILE_SORT_NAME_ASC))
        )
    }
}
