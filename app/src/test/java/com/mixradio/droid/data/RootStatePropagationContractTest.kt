// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROOT 状态传播契约（源码文本断言）。
 *
 * 线上根因：前台弱探测失败（su 超时 / 授权窗未回）也被当成「无 root」，
 * 还把全局 ROOT 状态降级为 false，TermuxPrefix 因陈旧状态永不执行。
 * 锁定三条链路：
 * 1. 弱探测失败不刷新 MainActivity 的节流时间戳（否则授权后回前台被 1.5s 吞掉）；
 * 2. [RootService.reportRootState] 默认弱语义，不得把已确认的 true 降为 false；
 * 3. 只有权威探测 [RootService.checkRoot] 才允许降级。
 */
class RootStatePropagationContractTest {

    private fun src(rel: String): String =
        java.io.File("src/main/java/com/mixradio/droid/$rel").readText()

    private val mainSrc = src("MainActivity.kt")
    private val rootSrc = src("data/RootService.kt")

    /** 弱探测失败不刷新节流窗口，且状态先落、时间戳后记。 */
    @Test
    fun failedProbeMustNotRefreshThrottleWindow() {
        assertTrue(
            "仅 granted 才刷新探测时间戳",
            mainSrc.contains("if (granted) lastRootProbeAt = System.currentTimeMillis()")
        )
        assertEquals(
            "时间戳只允许在 granted 分支更新一处",
            1,
            Regex("lastRootProbeAt = System\\.currentTimeMillis\\(\\)").findAll(mainSrc).count()
        )
        val state = mainSrc.indexOf("rootGranted = granted")
        val stamp = mainSrc.indexOf("if (granted) lastRootProbeAt")
        assertTrue("先写状态再记时间戳", state in 0 until stamp)
        assertTrue(
            "弱探测仍需上报 RootService",
            mainSrc.contains("RootService.reportRootState(granted)")
        )
    }

    /** 弱探测不得把已确认的 true 降级；权威探测才可降级。 */
    @Test
    fun weakProbeMustNotDowngradeConfirmedRoot() {
        assertTrue(
            "reportRootState 需带 authoritative 参数（默认弱探测）",
            rootSrc.contains("fun reportRootState(granted: Boolean, authoritative: Boolean = false)")
        )
        assertTrue(
            "弱探测失败不得降级 true",
            rootSrc.contains("if (!authoritative && !granted && isRootGranted == true) return")
        )
        assertTrue(
            "checkRoot 是权威探测",
            rootSrc.contains("reportRootState(granted, authoritative = true)")
        )
        assertFalse("MainActivity 不得自封权威", mainSrc.contains("authoritative = true"))
    }
}
