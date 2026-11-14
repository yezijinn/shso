// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「检查更新」右侧必须是箭头而不是胶囊，且进行态仍有文字反馈。 */
class SettingsUpdateArrowTest {

    private val source = File("src/main/java/com/mixradio/droid/ui/pages/SettingsPage.kt").readText()

    private fun updateBlock(): String {
        val titleAt = source.indexOf("title = \"检查更新\"")
        assertTrue("应能找到检查更新条目", titleAt > 0)
        // 从「检查更新」的 AuroraArrowPreference( 调用处起算：该调用在 title 行之前，
        // 只从 title 起截会把组件名排除在外，导致「复用箭头组件」的断言恒假。
        val callAt = source.lastIndexOf("AuroraArrowPreference(", titleAt)
        val start = if (callAt > 0) callAt else titleAt
        // 截到下一个设置项（结果弹窗注释）为止，避免窗口覆盖到后续弹窗代码
        val end = source.indexOf("// 检查更新结果弹窗", start)
        return source.substring(start, if (end > start) end else (start + 900).coerceAtMost(source.length))
    }

    @Test
    fun `检查更新不得再用胶囊开关`() {
        val block = updateBlock()
        assertFalse("检查不是开关，右侧必须是 chevron 箭头", block.contains("statusSwitch"))
        assertTrue("仍须走 AuroraArrowPreference（与存储组成同款箭头）", block.contains("AuroraArrowPreference"))
    }

    @Test
    fun `进行态用摘要文案表达`() {
        val block = updateBlock()
        assertTrue("检查中必须有可见文字反馈", block.contains("正在检查"))
        assertTrue("进行态文案由 updateChecking 驱动", block.contains("updateChecking"))
        assertTrue("点击仍触发检查", block.contains("checkForUpdate()"))
    }

    @Test
    fun `权限行的胶囊不受影响`() {
        val partials = File("src/main/java/com/mixradio/droid/ui/pages/SettingsPagePartials.kt").readText()
        assertTrue("权限行仍需胶囊展示授权状态", partials.contains("statusSwitch = root"))
    }
}
