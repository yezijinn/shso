// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行环境的两条硬约束（源码文本断言）：
 * 1. 脚本在**伪终端**下执行（原生安装器要求 stdin 是终端，否则判「无效输入」退出）。
 * 2. **不得无条件导出 `LD_LIBRARY_PATH`**（会顶替系统库依赖，令原生程序 CANNOT LINK）。
 */
class ExecutionPtyAndLibsContractTest {

    private fun src(rel: String): String =
        java.io.File("src/main/java/com/mixradio/droid/$rel").readText()

    private val rootService = src("data/RootService.kt")
    private val embeddedEnv = src("data/EmbeddedEnv.kt")
    private val termuxPrefix = src("data/TermuxPrefix.kt")

    /** 执行必须经 PTY 包装（busybox script）。 */
    @Test
    fun executionMustUsePty() {
        assertTrue("需有 PTY 包装函数", rootService.contains("fun wrapWithPty("))
        assertTrue("需调用 busybox script", rootService.contains("script -q -c"))
        assertTrue("执行时使用包装后的命令", rootService.contains("wrapWithPty(execCmd)"))
    }

    /**
     * 环境接入必须逐段兜底：整块 runCatching 会把「前缀建立失败」连带成
     * 「内置 PATH 也丢失」，退回系统 PHP/curl 全部缺件的状态。
     */
    @Test
    fun envWiringFailuresAreIsolated() {
        assertTrue(
            "解压单独兜底",
            rootService.contains("runCatching { EmbeddedEnv.ensure(ctx) }")
        )
        assertTrue(
            "前缀建立单独兜底",
            rootService.contains("runCatching { TermuxPrefix.ensure(ctx) }")
        )
        assertTrue(
            "导出片段单独兜底",
            rootService.contains("runCatching { EmbeddedEnv.exportFragment() }")
        )
        val ensureIdx = rootService.indexOf("runCatching { EmbeddedEnv.ensure(ctx) }")
        val prefixIdx = rootService.indexOf("runCatching { TermuxPrefix.ensure(ctx) }")
        assertTrue("前缀建立应在解压成功之后", ensureIdx in 0 until prefixIdx)
    }

    /** LD_LIBRARY_PATH 只能在 TermuxPrefix 不可用时兜底，绝不能无条件导出。 */
    @Test
    fun ldLibraryPathMustBeConditional() {
        assertTrue("库路径需经 TermuxPrefix 解析", termuxPrefix.contains("/data/data/com.termux/files/usr"))
        // 内置环境导出片段中，LD_LIBRARY_PATH 必须处于 `if (!TermuxPrefix.ready)` 守卫内
        assertTrue("内置环境需条件导出", embeddedEnv.contains("if (!TermuxPrefix.ready)"))
        // 守卫必须紧邻导出语句（同一分支内），且导出语句在守卫之后
        val guardIdx = embeddedEnv.indexOf("if (!TermuxPrefix.ready)")
        val exportIdx = embeddedEnv.indexOf("export LD_LIBRARY_PATH=")
        assertTrue("导出语句应在守卫之后", guardIdx in 0 until exportIdx)
        assertFalse("执行路径不应再引用可下载扩展包", rootService.contains("EnvPack"))
    }
}
