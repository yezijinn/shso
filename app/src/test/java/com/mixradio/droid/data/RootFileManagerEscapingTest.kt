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
        assertTrue(RootFileManager.isUnsafeFileName("a\nb"))
        assertTrue(RootFileManager.isUnsafeFileName("a\rb"))
        assertTrue(RootFileManager.isUnsafeFileName("a\u0000b"))
    }

    @Test fun `isUnsafeFileName accepts benign names`() {
        assertFalse(RootFileManager.isUnsafeFileName("app.sh"))
        assertFalse(RootFileManager.isUnsafeFileName("my notes 中.txt"))
        assertFalse(RootFileManager.isUnsafeFileName("it's.txt"))
        // 连续两点只在整名等于 `..` 时才构成穿越；名字内嵌两点是合法文件名。
        assertFalse(RootFileManager.isUnsafeFileName("a..b"))
        assertFalse(RootFileManager.isUnsafeFileName("a..b.txt"))
        assertFalse(RootFileManager.isUnsafeFileName("v1..2"))
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
        // 前导连字符会被 chown 当选项（argument injection），必须拒绝
        assertFalse(RootFileManager.isValidOwnerOrGroup("-R"))
        assertFalse(RootFileManager.isValidOwnerOrGroup("--help"))
        assertTrue(RootFileManager.isValidOwnerOrGroup("u0_a216"))
    }

    @Test fun `chown 命令必须带选项终止符`() {
        // 纵深防御：owner/group 可含连字符且 escapeShellArg 不为其加引号，
        // 没有 `--` 时 `chown -R <path>` 会被当成递归选项执行。
        val body = java.io.File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        assertTrue(body.contains("\"chown -- "))
        assertTrue(body.contains("\"chown -- :"))
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

    // ========================================================================
    // 点开头文件名 / 门禁时序 / 越界检查
    // ========================================================================

    @Test fun `点开头文件名不得被拆成空基名`() {
        // File.nameWithoutExtension 对 `.env` 返回空串（lastIndexOf('.')==0 → substring(0,0)），
        // 于是拷贝名变成 `_0.env`，前导点丢失。同文件 moveFile.renamedDestPath 用
        // `dot > 0` 规避过，copyFile 此前是遗漏。
        assertEquals("", java.io.File(".env").nameWithoutExtension)
        // File.extension = name.substringAfterLast('.') → 对 `.env` 给出 "env"（不含点），
        // 于是 nameWithoutExtension="" + suffix=".env" 之外还会再拼一次扩展名，正是错名来源
        assertEquals("env", java.io.File(".env").extension)
        assertEquals(".env" to "", RootFileManager.splitCopyName(".env"))
        assertEquals(".gitignore" to "", RootFileManager.splitCopyName(".gitignore"))
        assertEquals("a" to ".apk", RootFileManager.splitCopyName("a.apk"))
        assertEquals("archive.tar" to ".gz", RootFileManager.splitCopyName("archive.tar.gz"))
        assertEquals("noext" to "", RootFileManager.splitCopyName("noext"))
        // 拼装结果：点开头文件序号追加在末尾，其余按「基名_序号.后缀」
        assertEquals(".env_0", RootFileManager.copyCandidatePath("/d", ".env", "", 0).substringAfterLast('/'))
        assertEquals("a_0.apk", RootFileManager.copyCandidatePath("/d", "a", ".apk", 0).substringAfterLast('/'))
    }

    @Test fun `resolveWritableTarget 的越界检查不得是空实现`() {
        // 该检查此前只有 if 条件与注释、函数体是空的，等于从未存在。
        val s = java.io.File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        val fn = s.indexOf("suspend fun resolveWritableTarget")
        assertTrue("应能找到 resolveWritableTarget", fn > 0)
        val body = s.substring(fn, fn + 2000)
        val at = body.indexOf("真实目标也必须仍在用户点选的路径之内")
        assertTrue("应保留该注释", at > 0)
        val after = body.substring(at)
        val retAt = after.indexOf("return resolved to \"\"")
        assertTrue("应能找到正常返回", retAt > 0)
        assertTrue(
            "越界时必须真的返回错误，而不是只有注释",
            after.substring(0, retAt).contains("不在你选择的路径")
        )
    }

    @Test fun `祖先目录为符号链接的正常路径不得被误拒`() {
        // path 的最终分量不是链接、但祖先目录是：readlink -f 给出的解析结果与 path 不同，
        // 只拿 path 做前缀比对会把这种正常选择判成越界。
        val selected = "/data/local/tmp/lnk/foo.sh"
        assertTrue(isResolvedInsideSelected("/data/adb/shso/foo.sh", selected, "/data/adb/shso"))
        // 文件本身就是链接且指向自身子树 → 原放行语义必须保留
        assertTrue(
            isResolvedInsideSelected(
                "/data/local/tmp/lnk/foo.sh/sub",
                selected,
                "/data/adb/shso"
            )
        )
        // 同名链接（/data/local/tmp/modules → /data/adb/modules）恰是「文件名相同」的越界写法，
        // 不能用基名相等放行
        assertFalse(
            isResolvedInsideSelected(
                "/data/adb/modules",
                "/data/local/tmp/modules",
                "/data/local/tmp"
            )
        )
        // 真实目标完全落在别处 → 仍须拦下
        assertFalse(isResolvedInsideSelected("/data/adb/modules", selected, "/data/local/tmp/lnk"))
    }

    @Test fun `越界检查须复用父目录解析结果`() {
        val s = java.io.File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        assertTrue(
            "resolveWritableTarget 必须调用可单测的比对函数，而不是就地拼前缀",
            s.contains("isResolvedInsideSelected(resolved, path, parentResolved)")
        )
        val fn = s.indexOf("suspend fun resolveWritableTarget")
        val body = s.substring(fn, fn + 2000)
        assertTrue(
            "取完整解析的同时必须取父目录解析，否则祖先为链接的正常路径会被误拒",
            body.contains("readlink -f \$escapedParent")
        )
    }

    @Test fun `复制目录必须递归改权限`() {
        // 只 chmod 顶层的话子文件仍是源权限（如 0700），第三方文件管理器读不到，
        // 与「让其他应用也能自由读写其中的文件」的设计目的相悖。
        val s = java.io.File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        assertTrue("目录分支必须用 chmod -R", s.contains("\"chmod -R 777\""))
    }

    @Test fun `拷贝文件不得抹掉来源的可执行位`() {
        // 真实回归：ROOT 路径先 `cp -p`（保留源权限，如 755），紧接着 `chmod 644`
        // 把权限改成 rw-r--r--，连普通 0755 的执行位也一并抹掉 —— 拷出的 .sh/.so
        // 立刻失去执行权限；Java 回退路径拷贝后完全没设权限，两条路径产物还不一致。
        // 现统一为「只减不增」：保留 rwx，只清 setuid/setgid 与 group/other 写位。
        val s = java.io.File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        val fn = s.indexOf("suspend fun copyFile(")
        assertTrue("应能找到 copyFile", fn > 0)
        val end = s.indexOf("suspend fun createEmptyFile(", fn)
        val body = s.substring(fn, if (end > 0) end else s.length)

        assertTrue("ROOT 路径必须只减不增", body.contains("chmod a-s,go-w"))
        assertFalse("不得再用 chmod 644 抹掉执行位", body.contains("chmod 644"))
        assertTrue(
            "Java 路径必须按来源 mode 补权限",
            body.contains("Os.chmod(destPath, Os.stat(sourcePath).st_mode and COPY_MODE_MASK)")
        )
        assertTrue(
            "掩码必须是 0755（保留执行位、清 g/o 写与 setuid/setgid）",
            s.contains("COPY_MODE_MASK = 0b111101101")
        )
    }
}
