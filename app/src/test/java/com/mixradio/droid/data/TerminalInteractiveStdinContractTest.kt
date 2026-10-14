// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交互脚本的 stdin 契约回归。
 *
 * 缺陷：终端「一次性命令」通道曾主动 `process.outputStream.close()`，
 * 脚本里的 `read` 立刻拿到 EOF 而自行走完 —— 表现为「遇到交互阶段直接结束」。
 * 修复后该通道必须长开 stdin 并把 writer 发布给 [RootService.sendInput]。
 *
 * 本测试以源码文本断言契约（该分支构造 Process 需真机 su，无法在 JVM 单测起进程），
 * 防止有人把 close 加回去却无人察觉。
 */
class TerminalInteractiveStdinContractTest {

    private val source: String = run {
        val url = Thread.currentThread().contextClassLoader
            ?.getResource("../../src/main/java/com/mixradio/droid/data/RootService.kt")
        if (url != null) {
            java.io.File(url.toURI()).readText()
        } else {
            // 回退：相对模块根目录定位（Gradle 单测工作目录为 app/）
            java.io.File("src/main/java/com/mixradio/droid/data/RootService.kt").readText()
        }
    }

    /**
     * 一次性命令通道不得关闭子进程 stdin。
     *
     * 判据：`runTerminalCommand` 里不再出现 `process.outputStream.close()`。
     * 该函数以 `private suspend fun runTerminalCommand` 起头，取其函数体区间判断。
     */
    @Test
    fun terminalCommandMustNotCloseStdin() {
        val start = source.indexOf("private suspend fun runTerminalCommand")
        assertTrue("未找到 runTerminalCommand", start >= 0)
        // 函数体截止到下一个顶层成员（缩进为 4 空格的 fun/val/const 或类尾）
        val rest = source.substring(start)
        val end = rest.indexOf("\n    fun ", 1).let { if (it > 0) it else rest.length }
            .coerceAtMost(rest.indexOf("\n    private fun ", 1).let { if (it > 0) it else rest.length })
        val body = rest.substring(0, if (end > 0) end else rest.length)

        assertFalse(
            "runTerminalCommand 不得关闭 stdin（交互脚本会立刻 EOF 而结束）",
            body.contains("process.outputStream.close()")
        )
    }

    /**
     * 一次性命令通道必须把 writer 发布到 processWriter，
     * 否则 sendInput 无从写入，交互输入永远送不进脚本。
     */
    @Test
    fun terminalCommandMustPublishWriter() {
        val start = source.indexOf("private suspend fun runTerminalCommand")
        assertTrue("未找到 runTerminalCommand", start >= 0)
        val body = source.substring(start, source.indexOf("private fun readRunPgidFile").let {
            if (it > start) it else source.length
        })
        assertTrue(
            "runTerminalCommand 必须把 writer 赋给 processWriter",
            body.contains("processWriter = terminalWriter")
        )
    }

    /**
     * 执行环境必须补充 PATH 候选目录。
     *
     * Android 原生不带 curl / wget / python / node，联网型脚本会按 127 退出，
     * 日志里只剩一行 `curl: not found`。已存在 Termux / Magisk busybox 时
     * 应能直接用上，故执行前缀必须带 [RootService] 的 PATH 增强片段。
     */
    @Test
    fun executionMustExtendPath() {
        assertTrue(
            "执行环境必须追加 PATH 候选目录（否则联网脚本因缺 curl/wget 而失败）",
            source.contains("pathExportFragment()")
        )
        // 增强片段必须只追加确实存在的目录：空条目等价于当前目录，属注入面
        assertTrue(
            "PATH 增强必须逐个判定目录存在性",
            source.contains("[ -d ")
        )
    }

    /**
     * isTerminalBusy 必须把「有可写进程」视为不忙，
     * 否则运行中的命令持有槽位会让交互输入全程被拒。
     */
    @Test
    fun busyCheckMustAllowInteractiveWrite() {
        val start = source.indexOf("private fun isTerminalBusy")
        assertTrue("未找到 isTerminalBusy", start >= 0)
        val body = source.substring(start, start + 400)
        assertTrue(
            "isTerminalBusy 必须在有可写进程时判为不忙",
            body.contains("isTaskRunning && processWriter != null")
        )
    }
}
