// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only coverage for the path validation predicates. */
class RootFileManagerEscapingTest {
    @Test fun `isUnsafePath rejects empty path`() {
        assertTrue(RootFileManager.isUnsafePath(""))
    }

    @Test fun `isUnsafePath rejects backslash and controls`() {
        assertTrue(RootFileManager.isUnsafePath("/a\\b"))
        assertTrue(RootFileManager.isUnsafePath("/a\nb"))
        assertTrue(RootFileManager.isUnsafePath("/a\rb"))
        assertTrue(RootFileManager.isUnsafePath("/a\u0000b"))
    }

    @Test fun `isUnsafePath rejects traversal segments`() {
        assertTrue(RootFileManager.isUnsafePath(".."))
        assertTrue(RootFileManager.isUnsafePath("/a/../b"))
        assertTrue(RootFileManager.isUnsafePath("../etc/passwd"))
    }

    @Test fun `isUnsafePath accepts valid paths`() {
        assertFalse(RootFileManager.isUnsafePath("/"))
        assertFalse(RootFileManager.isUnsafePath("/data/adb/shso/app.sh"))
        assertFalse(RootFileManager.isUnsafePath("/storage/My Music/it's.txt"))
    }

    @Test fun `isUnsafeFileName rejects separators traversal and controls`() {
        assertTrue(RootFileManager.isUnsafeFileName("a/b"))
        assertTrue(RootFileManager.isUnsafeFileName("a\\b"))
        assertTrue(RootFileManager.isUnsafeFileName(".."))
        assertTrue(RootFileManager.isUnsafeFileName("a..b"))
        assertTrue(RootFileManager.isUnsafeFileName("a\nb"))
        assertTrue(RootFileManager.isUnsafeFileName("a\rb"))
        assertTrue(RootFileManager.isUnsafeFileName("a\u0000b"))
    }

    @Test fun `isUnsafeFileName accepts benign names`() {
        assertFalse(RootFileManager.isUnsafeFileName("app.sh"))
        assertFalse(RootFileManager.isUnsafeFileName("my notes 中.txt"))
        assertFalse(RootFileManager.isUnsafeFileName("it's.txt"))
    }

    @Test fun `data path validation accepts only data tree`() {
        assertTrue(RootFileManager.isAllowedDataPath("/data"))
        assertTrue(RootFileManager.isAllowedDataPath("/data/app/file.apk"))
        assertFalse(RootFileManager.isAllowedDataPath("/"))
        assertFalse(RootFileManager.isAllowedDataPath("/system/bin/sh"))
        assertFalse(RootFileManager.isAllowedDataPath("/data/../system"))
        assertFalse(RootFileManager.isAllowedDataPath("/database/file"))
    }

    @Test fun `permission mode validation accepts three or four octal digits only`() {
        assertTrue(RootFileManager.isValidPermissionMode("644"))
        assertTrue(RootFileManager.isValidPermissionMode("0755"))
        assertFalse(RootFileManager.isValidPermissionMode("64"))
        assertFalse(RootFileManager.isValidPermissionMode("07555"))
        assertFalse(RootFileManager.isValidPermissionMode("0899"))
        assertFalse(RootFileManager.isValidPermissionMode("755;id"))
    }

    @Test fun `owner and group validation rejects shell injection`() {
        assertTrue(RootFileManager.isValidOwnerOrGroup("root"))
        assertTrue(RootFileManager.isValidOwnerOrGroup("1000"))
        assertTrue(RootFileManager.isValidOwnerOrGroup("system_u"))
        assertFalse(RootFileManager.isValidOwnerOrGroup("root;id"))
        assertFalse(RootFileManager.isValidOwnerOrGroup("root user"))
        assertFalse(RootFileManager.isValidOwnerOrGroup("root|id"))
        assertFalse(RootFileManager.isValidOwnerOrGroup(""))
    }

    @Test fun `permission string converts to octal mode`() {
        assertEquals("755", RootFileManager.permissionStringToOctal("-rwxr-xr-x"))
        assertEquals("644", RootFileManager.permissionStringToOctal("-rw-r--r--"))
        assertNull(RootFileManager.permissionStringToOctal("invalid"))
    }

    @Test fun `permission string preserves special mode bits`() {
        assertEquals("4755", RootFileManager.permissionStringToOctal("-rwsr-xr-x"))
        assertEquals("2755", RootFileManager.permissionStringToOctal("-rwxr-sr-x"))
        assertEquals("1755", RootFileManager.permissionStringToOctal("-rwxr-xr-t"))
        assertEquals("7777", RootFileManager.permissionStringToOctal("-rwsrwsrwt"))
        assertEquals("4644", RootFileManager.permissionStringToOctal("-rwSr--r--"))
        assertEquals("2644", RootFileManager.permissionStringToOctal("-rw-r-Sr--"))
        assertEquals("1644", RootFileManager.permissionStringToOctal("-rw-r--r-T"))
    }

    @Test fun `permission string rejects malformed permission positions`() {
        assertNull(RootFileManager.permissionStringToOctal("-rwxr-xr"))
        assertNull(RootFileManager.permissionStringToOctal("-rwxr-xr-z"))
        assertNull(RootFileManager.permissionStringToOctal("?rwxr-xr-x"))
    }

    @Test fun `stat 秒值换算为毫秒，避免时间显示成 1970`() {
        // 时间戳单位为秒，转 Date 需乘 1000，否则显示 1970 年。
        assertEquals(1789044590000L, RootFileManager.statSecondsToMillis(1789044590L))
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date(1789044590000L))
        assertEquals("2026", year)
    }

    @Test fun `stat 空值或非正值回退为 0`() {
        assertEquals(0L, RootFileManager.statSecondsToMillis(0L))
        assertEquals(0L, RootFileManager.statSecondsToMillis(-1L))
    }
}
