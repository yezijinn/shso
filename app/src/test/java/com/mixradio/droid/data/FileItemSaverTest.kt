// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FileItemSaver]：让「待执行 / 待安装」这类确认流程在配置变更（旋转 / 分屏）后存活。
 *
 * 回归背景：确认框状态原先用 `remember`，旋转重建后静默丢失（终端页同类状态用
 * `rememberSaveable` 已解决，文件页遗漏）。Saver 必须能无损往返，否则重建后依然丢。
 */
class FileItemSaverTest {

    private val scope = SaverScope { true }

    private fun roundTrip(item: FileItem?): FileItem? {
        val saved = with(FileItemSaver) { scope.save(item) } ?: return null
        return FileItemSaver.restore(saved)
    }

    @Test
    fun `文件条目往返不丢字段`() {
        val item = FileItem(
            name = "app.apk",
            path = "/sdcard/Download/app.apk",
            isDirectory = false,
            size = 12345678L,
            lastModified = 1789044590000L,
            permissions = "-rw-rw-r--"
        )
        val restored = roundTrip(item)
        assertEquals(item, restored)
    }

    @Test
    fun `目录条目往返不丢字段`() {
        val item = FileItem(
            name = "shso_e2e",
            path = "/sdcard/Download/shso_e2e",
            isDirectory = true
        )
        // 默认值字段（size/lastModified/permissions）也必须原样带回
        assertEquals(item, roundTrip(item))
    }

    @Test
    fun `null 往返仍为 null`() {
        assertNull(roundTrip(null))
    }

    @Test
    fun `空列表还原为 null（对应从未有待确认项）`() {
        assertNull(FileItemSaver.restore(emptyList<Any>()))
    }

    @Test
    fun `损坏或旧格式状态安全还原为 null`() {
        assertNull(FileItemSaver.restore(listOf("name", "path")))
        assertNull(FileItemSaver.restore(listOf("name", "path", false, "bad", 0L, "644")))
    }

    @Test
    fun `路径含空格与中文不被破坏`() {
        val item = FileItem(
            name = "Jinn输入法-20260830.APK",
            path = "/sdcard/Download/my dir/Jinn输入法-20260830.APK",
            isDirectory = false,
            size = 11135741L
        )
        assertEquals(item, roundTrip(item))
    }
}
