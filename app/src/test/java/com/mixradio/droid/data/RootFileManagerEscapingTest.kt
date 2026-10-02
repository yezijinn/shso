// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

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

    @Test
    fun `parseSingleStatOutput keeps full path and converts seconds`() {
        val path = "/data/adb/shso/app.sh"
        val item = RootFileManager.parseSingleStatOutput(
            "-rwxr-xr-x|123|1789044590|$path",
            path
        )

        assertEquals("app.sh", item?.name)
        assertEquals(path, item?.path)
        assertEquals(123L, item?.size)
        assertEquals(1789044590000L, item?.lastModified)
        assertEquals("-rwxr-xr-x", item?.permissions)
        assertFalse(item?.isDirectory == true)
    }

    @Test
    fun `parseSingleStatOutput rejects malformed output`() {
        assertNull(RootFileManager.parseSingleStatOutput("", "/data/adb/shso/a.sh"))
        assertNull(RootFileManager.parseSingleStatOutput("bad|line", "/data/adb/shso/a.sh"))
        assertNull(
            RootFileManager.parseSingleStatOutput(
                "-rw-r--r--|bad|1789044590|/data/adb/shso/a.sh",
                "/data/adb/shso/a.sh"
            )
        )
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

    @Test fun `Java 删除回退路径必须先判符号链接`() {
        // 真实缺陷：回退分支用 `File.isDirectory`（走 stat，跟随链接）+
        // `deleteRecursively()`（对目录链接同样下潜）。于是「删除一个指向别处的链接」
        // 会静默清空链接目标整棵树 —— ROOT 不可用 + 用户删的是自己放在 /sdcard 下的链接时必现，
        // 且不可撤销。修复是在 deleteRecursively 之前用 lstat 判链接，只删链接本身。
        val s = java.io.File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        val fnStart = s.indexOf("suspend fun delete(")
        assertTrue("必须能定位到 delete 实现", fnStart > 0)
        val fnEnd = s.indexOf("private const val COPY_NAME_PROBE_LIMIT", fnStart)
        val body = s.substring(fnStart, if (fnEnd > 0) fnEnd else s.length)
        val linkAt = body.indexOf("if (Files.isSymbolicLink(targetFile.toPath()))")
        val recurseAt = body.indexOf("targetFile.deleteRecursively()")
        assertTrue("删除回退必须先判符号链接", linkAt > 0)
        assertTrue("仍然使用 deleteRecursively 处理真实目录", recurseAt > 0)
        assertTrue("符号链接判定必须早于递归删除", linkAt < recurseAt)
    }

    @Test fun `覆盖移动不得预先删除文件型目标`() {
        // 真实缺陷：OVERWRITE 分支先 delete(destination) 再 rename/mv。
        // `rename(2)` 与 `mv` 本身就会原子覆盖同类型文件目标，先删等于造出一个
        // 「移动失败则目标已丢」的窗口（跨挂载点 / 无权限 / 被守卫拦时 mv 必然失败，
        // 而目标已被删掉，用户数据不可恢复）。只有目录目标才需要先删。
        val s = java.io.File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        val start = s.indexOf("MoveDestinationConflict.OVERWRITE -> {")
        assertTrue("必须能定位到 OVERWRITE 分支", start > 0)
        val branch = s.substring(start, start + 900)
        assertTrue("覆盖前必须先判定目标类型", branch.contains("destType"))
        assertTrue("只有目录目标才允许预先删除", branch.contains("if (destType == 2)"))
    }
}
