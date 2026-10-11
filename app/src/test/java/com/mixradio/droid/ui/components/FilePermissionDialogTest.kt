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

    @Test fun `matrix index must follow group-major bit layout`() {
        // bits 组优先：bits[0..2]=用户、[3..5]=用户组、[6..8]=其他。
        // 矩阵行为权限（读/写/执行）、列为组（用户/用户组/其他），下标 = 列*3 + 行。
        // 若写成 行*3 + 列，矩阵整体转置，(读,用户组) 会落到 bits[1]（用户写位）。
        assertEquals(0, permissionMatrixIndex(0, 0))  // 读 · 用户
        assertEquals(3, permissionMatrixIndex(0, 1))  // 读 · 用户组
        assertEquals(6, permissionMatrixIndex(0, 2))  // 读 · 其他
        assertEquals(1, permissionMatrixIndex(1, 0))  // 写 · 用户
        assertEquals(4, permissionMatrixIndex(1, 1))  // 写 · 用户组
        assertEquals(7, permissionMatrixIndex(1, 2))  // 写 · 其他
        assertEquals(2, permissionMatrixIndex(2, 0))  // 执行 · 用户
        assertEquals(5, permissionMatrixIndex(2, 1))  // 执行 · 用户组
        assertEquals(8, permissionMatrixIndex(2, 2))  // 执行 · 其他
    }

    @Test fun `asymmetric mode reads back correctly through matrix indexing`() {
        // 750 的权限分布不对称（用户 rwx / 用户组 r-x / 其他 ---），
        // 755 这类多位取值巧合相同，无法暴露转置错误，必须用非对称 mode 锁定。
        val bits = octalToPermissionBits("750")
        fun at(row: Int, column: Int) = bits[permissionMatrixIndex(row, column)]
        // 读：用户 ✓、用户组 ✓、其他 ✗
        assertEquals(true, at(0, 0))
        assertEquals(true, at(0, 1))
        assertEquals(false, at(0, 2))
        // 写：仅用户 ✓
        assertEquals(true, at(1, 0))
        assertEquals(false, at(1, 1))
        assertEquals(false, at(1, 2))
        // 执行：用户 ✓、用户组 ✓、其他 ✗
        assertEquals(true, at(2, 0))
        assertEquals(true, at(2, 1))
        assertEquals(false, at(2, 2))
    }

    @Test fun `matrix toggle writes back to the intended group`() {
        // 从 750 起步，点「写 · 用户组」→ 用户组应带上写位 → 770。
        val bits = octalToPermissionBits("750").toMutableList()
        bits[permissionMatrixIndex(1, 1)] = true
        assertEquals("770", permissionBitsToOctal(bits))
    }
}
