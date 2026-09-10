// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import com.mixradio.droid.data.RootFileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
