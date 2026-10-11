// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import com.mixradio.droid.data.RootFileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FilePermissionDialogTest {
    @Test fun `octal mode converts to nine permission bits`() {
        assertEquals(
            listOf(true, true, true, true, false, true, true, false, true),
            octalToPermissionBits("0755")
        )
    }

    @Test fun `permission bits convert back to octal mode`() {
        assertEquals("755", permissionBitsToOctal(octalToPermissionBits("755")))
        assertEquals("644", permissionBitsToOctal(octalToPermissionBits("0644")))
    }

    @Test fun `invalid mode produces unchecked matrix`() {
        assertEquals(List(9) { false }, octalToPermissionBits("89"))
        assertFalse(RootFileManager.isValidPermissionMode("89"))
    }

    @Test fun `permission matrix conversion preserves every bit`() {
        val bits = listOf(true, false, true, false, true, false, true, true, false)
        assertEquals(bits, octalToPermissionBits(permissionBitsToOctal(bits)))
    }

    @Test fun `permission matrix conversion preserves special mode prefix`() {
        val bits = octalToPermissionBits("4755")
        assertEquals("4755", permissionBitsToOctal(bits, "4"))
        assertEquals("2644", permissionBitsToOctal(octalToPermissionBits("2644"), "2"))
    }

    @Test fun `permission matrix conversion rejects invalid special mode prefix`() {
        val bits = octalToPermissionBits("755")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            permissionBitsToOctal(bits, "8")
        }
    }

    @Test fun `special mode prefix only accepts octal leading digit`() {
        // 输入框只挡非数字字符，`8888` / `9999` 这类四位串能停留在「八进制权限」里。
        // 首位若原样当 setuid / setgid / sticky 前缀传给 permissionBitsToOctal，
        // 会命中其 require 抛 IllegalArgumentException —— 前缀只能取自合法模式。
        assertEquals("4", specialModePrefix("4755"))
        assertEquals("0", specialModePrefix("0755"))
        assertEquals("", specialModePrefix("8888"))
        assertEquals("", specialModePrefix("9999"))
        assertEquals("", specialModePrefix("1238"))
        assertEquals("", specialModePrefix("755"))
        assertEquals("", specialModePrefix(""))
    }

    @Test fun `invalid four digit input never crashes the matrix write back`() {
        // 与上一例同源：点任一权限开关都会回写八进制串，非法输入必须被前缀过滤挡下，
        // 而不是把异常抛到组合期。
        val bits = octalToPermissionBits("755")
        listOf("8888", "9999", "1238").forEach { input ->
            assertEquals("755", permissionBitsToOctal(bits, specialModePrefix(input)))
        }
    }
}
