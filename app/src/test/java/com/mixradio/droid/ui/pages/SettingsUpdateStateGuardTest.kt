// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查更新」进行态与结果的跨配置变更存活契约。
 *
 * 原先是 `remember` + `rememberCoroutineScope`：旋转（或深色模式 / 字号变化）重建整个组合，
 * 作业被取消、状态归零 —— 「检查中」消失、结果弹窗永不出现，用户只看到按钮又暗了下去。
 * 提为文件级（进程级）后，改写回 remember 即回归，用源码契约钉住。
 */
class SettingsUpdateStateGuardTest {

    private val settings = File("src/main/java/com/mixradio/droid/ui/pages/SettingsPage.kt")

    @Test fun `检查更新状态与作业作用域必须是文件级`() {
        val s = settings.readText()
        assertTrue(
            "结果状态必须是文件级",
            s.contains("private var updateState by mutableStateOf<UpdateUiState>")
        )
        assertTrue(
            "进行态必须是文件级",
            s.contains("private var updateChecking by mutableStateOf(")
        )
        assertTrue(
            "作业作用域必须是文件级，否则旋转即取消在途检查",
            s.contains("private val updateJobScope = CoroutineScope(SupervisorJob()")
        )
        assertTrue("不得再在组合内 remember 结果状态", !s.contains("var updateState by remember"))
        assertTrue("不得再在组合内 remember 进行态", !s.contains("var updateChecking by remember"))
        assertTrue(
            "发起检查必须走文件级作用域",
            Regex("fun checkForUpdate\\(\\)[\\s\\S]{0,200}updateJobScope\\.launch").containsMatchIn(s)
        )
    }

    @Test fun `检查更新必须重抛取消而非记成网络错误`() {
        // try/catch 里 catch (Exception) 会把取消折成 NetworkError，取消信号丢失。
        val s = settings.readText()
        val idx = s.indexOf("fun checkForUpdate()")
        assertTrue("应能找到 checkForUpdate", idx > 0)
        val body = s.substring(idx, idx + 900)
        assertTrue(
            "必须先捕获并重抛 CancellationException",
            body.contains("catch (e: CancellationException)") && body.contains("throw e")
        )
    }
}
