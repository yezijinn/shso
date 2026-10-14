// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端提示清理、执行前自动清屏、外部应用识别的落地契约。
 *
 * 均为源码/清单文本断言（行为需真机验证），锁定「改错就静默退化」的关键点。
 */
class TerminalRunAndLinkContractTest {

    private fun src(rel: String): String =
        java.io.File("src/main/java/com/mixradio/droid/$rel").readText()

    private val rootService = src("data/RootService.kt")
    private val appSettings = src("data/AppSettings.kt")
    private val settingsPage = src("ui/pages/SettingsPage.kt")
    private val terminalPage = src("ui/pages/TerminalPage.kt")
    private val urlOpener = src("data/UrlOpener.kt")
    private val manifest = java.io.File("src/main/AndroidManifest.xml").readText()

    /** 「shso 终端提示」已移除：不再有任何横幅文案写进终端输出，避免污染脚本输出与日志窗口。 */
    @Test
    fun shsoBannerFeatureRemoved() {
        assertFalse("设置项应已移除", appSettings.contains("showShsoBanner") || appSettings.contains("setShsoBanner"))
        assertFalse("执行链路不得再写 [shso] 横幅", rootService.contains("showShsoBanner"))
        assertFalse("终端设置不得再出现旧开关", terminalPage.contains("shso 终端提示"))
        // 心跳计时行也属「提示」范畴，会打断交互提示的显示，一并移除
        assertFalse("心跳提示行应移除", rootService.contains("仍在执行，已运行"))
    }

    /** 新增「执行脚本前自动清屏」：设置项存在，且执行前会清空输出。 */
    @Test
    fun autoClearBeforeRunWired() {
        assertTrue("需有持久化键", appSettings.contains("KEY_AUTO_CLEAR_BEFORE_RUN"))
        assertTrue("需有设置项", terminalPage.contains("执行脚本前自动清屏"))
        assertTrue("执行前需读取该设置", rootService.contains("autoClearBeforeRun"))
    }

    /** 打开链接必须走统一打开器，且不再自建可能为空的选择器。 */
    @Test
    fun urlOpeningMustUseUrlOpener() {
        assertTrue("设置页应委托 UrlOpener", settingsPage.contains("UrlOpener.openUrl("))
        assertTrue("UrlOpener 用系统隐式意图兜底", urlOpener.contains("context.startActivity(target)"))
        assertTrue("探测查询需带 MATCH_ALL", urlOpener.contains("PackageManager.MATCH_ALL"))
    }

    /**
     * 清单需声明 http/https 的 VIEW 意图，保证 Android 11+ 包可见性下能列举浏览器/其他应用。
     */
    @Test
    fun manifestDeclaresUrlQueries() {
        assertTrue("需声明 queries", manifest.contains("<queries>"))
        assertTrue("需声明 http", manifest.contains("android:scheme=\"http\""))
        assertTrue("需声明 https", manifest.contains("android:scheme=\"https\""))
        assertTrue("需声明 BROWSABLE", manifest.contains("android.intent.category.BROWSABLE"))
    }
}
