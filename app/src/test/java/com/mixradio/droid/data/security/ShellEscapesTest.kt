// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellEscapes] 的行为契约。
 *
 * 这两个函数是 shell 拼串的唯一出口：改错不会编译失败，只会在真机上表现为
 * 路径被拆词、脚本指向错误目录，或注入被放行，故用纯 JVM 单测钉死。
 */
class ShellEscapesTest {

    /** 空参数必须显式落到 `''`：裸空串在拼串后消失，后续参数会整体左移。 */
    @Test
    fun emptyArgBecomesQuotedEmpty() {
        assertEquals("''", ShellEscapes.escapeShellArg(""))
    }

    /** 白名单内的普通路径原样输出，不加引号（保持现有产品行为）。 */
    @Test
    fun plainPathStaysUnquoted() {
        val path = "/data/user/0/com.mixradio.droid/files/env/bin"
        assertEquals(path, ShellEscapes.escapeShellArg(path))
    }

    /** 白名单字符集：字母、数字、下划线、点、斜杠、连字符、冒号。 */
    @Test
    fun whitelistCharsStayUnquoted() {
        val arg = "Az09_./-:"
        assertEquals(arg, ShellEscapes.escapeShellArg(arg))
    }

    /** 含空格的路径必须整体加引号，否则被拆成多个参数。 */
    @Test
    fun pathWithSpaceIsQuoted() {
        val path = "/sdcard/My Scripts/a.sh"
        assertEquals("'/sdcard/My Scripts/a.sh'", ShellEscapes.escapeShellArg(path))
    }

    /** 路径含单引号时按 `'\''` 收尾重开，结果仍是单个字面量。 */
    @Test
    fun pathWithSingleQuoteEscapesByClosingAndReopening() {
        val path = "/sdcard/it's.sh"
        assertEquals("'/sdcard/it'\\''s.sh'", ShellEscapes.escapeShellArg(path))
    }

    /** 注入字符一律进单引号分支，不得原样拼接。 */
    @Test
    fun injectionCharsAreQuoted() {
        listOf("/tmp/a;rm -rf /", "/tmp/\$(id)", "/tmp/*.sh", "/tmp/a\"b").forEach {
            assertTrue("含特殊字符的参数必须被引号包裹: $it", ShellEscapes.escapeShellArg(it).startsWith("'"))
        }
        assertEquals("'/tmp/a;id'", ShellEscapes.escapeShellArg("/tmp/a;id"))
    }

    /** ERE 字面量匹配串：正则元字符逐个转义，`/` 与 `-` 保持原样。 */
    @Test
    fun ereLiteralEscapesMetaCharsOnly() {
        assertEquals(
            "/data/adb/shso/v1\\.2\\.sh",
            ShellEscapes.escapeEreLiteral("/data/adb/shso/v1.2.sh")
        )
        assertEquals(
            "a-b/c\\(d\\)\\[e\\]\\*\\+\\?\\|\\^\\$\\\\",
            ShellEscapes.escapeEreLiteral("a-b/c(d)[e]*+?|^$\\")
        )
    }

    /** security 包必须保持纯 Kotlin：引入 Android 依赖会让 JVM 单测无法加载。 */
    @Test
    fun securityPackageStaysAndroidFree() {
        val src = java.io.File("src/main/java/com/mixradio/droid/data/security/ShellEscapes.kt").readText()
        assertFalse("security 包不得依赖 Android", src.contains("import android."))
    }

    /** 转义实现只此一份：EmbeddedEnv / TermuxPrefix / RootService 必须委托过来。 */
    @Test
    fun envCallersDelegateToShellEscapes() {
        listOf("data/EmbeddedEnv.kt", "data/TermuxPrefix.kt").forEach { rel ->
            val src = java.io.File("src/main/java/com/mixradio/droid/$rel").readText()
            assertTrue("$rel 需使用 ShellEscapes.escapeShellArg", src.contains("ShellEscapes.escapeShellArg("))
            assertFalse("$rel 不得保留私有 escape 副本", src.contains("private fun escape(s: String)"))
            assertFalse("$rel 不得再复制白名单正则", src.contains("a-zA-Z0-9_./"))
        }
    }

    /** RootService 是 Android 侧唯一门面，同样只转发；Pattern 与白名单不得回流。 */
    @Test
    fun rootServiceDelegatesWithoutDuplicatingPattern() {
        val src = java.io.File(
            "src/main/java/com/mixradio/droid/data/RootService.kt"
        ).readText()
        assertTrue(
            "RootService.escapeShellArg 需转发到 ShellEscapes",
            src.contains("ShellEscapes.escapeShellArg(arg)")
        )
        assertFalse("不得再持有 SAFE_ARG 白名单", src.contains("SAFE_ARG"))
        assertFalse("不得再引入 java.util.regex.Pattern", src.contains("java.util.regex.Pattern"))
        assertFalse("不得再复制白名单正则", src.contains("a-zA-Z0-9_./"))
    }
}
