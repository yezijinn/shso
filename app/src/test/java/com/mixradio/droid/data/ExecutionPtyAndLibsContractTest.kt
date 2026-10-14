// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

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
    private val envPack = src("data/EnvPack.kt")
    private val termuxPrefix = src("data/TermuxPrefix.kt")

    /** 执行必须经 PTY 包装（busybox script）。 */
    @Test
    fun executionMustUsePty() {
        assertTrue("需有 PTY 包装函数", rootService.contains("fun wrapWithPty("))
        assertTrue("需调用 busybox script", rootService.contains("script -q -c"))
        assertTrue("执行时使用包装后的命令", rootService.contains("wrapWithPty(execCmd)"))
    }

    /** LD_LIBRARY_PATH 只能在 TermuxPrefix 不可用时兜底，绝不能无条件导出。 */
    @Test
    fun ldLibraryPathMustBeConditional() {
        assertTrue("库路径需经 TermuxPrefix 解析", termuxPrefix.contains("/data/data/com.termux/files/usr"))
        // 两个导出片段中，LD_LIBRARY_PATH 必须处于 `if (!TermuxPrefix.ready)` 守卫内
        assertTrue("内置环境需条件导出", embeddedEnv.contains("if (!TermuxPrefix.ready)"))
        assertTrue("扩展包需条件导出", envPack.contains("if (!TermuxPrefix.ready)"))
        // 守卫必须紧邻导出语句（同一分支内），且导出语句在守卫之后
        val guardIdx = embeddedEnv.indexOf("if (!TermuxPrefix.ready)")
        val exportIdx = embeddedEnv.indexOf("export LD_LIBRARY_PATH=")
        assertTrue("导出语句应在守卫之后", guardIdx in 0 until exportIdx)
    }
}
