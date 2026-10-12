// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui

import com.mixradio.droid.ui.components.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 虚假成功入口、死设置与线程内状态读取的回归护栏。
 *
 * 这一组同类：界面上给出「已生效」的结论，实际什么都没有发生 —— 点「应用为软件字体」
 * 弹成功提示但主题字体是硬编码等宽；设置里存着无人消费的暗色 / 字体开关；保存时越过
 * 主线程去 IO 上读 Compose 状态。都不崩溃，但用户对结果的判断是错的。
 */
class ConsistencyCleanupRegressionTest {

    private fun src(rel: String): String =
        File("src/main/java/com/mixradio/droid/$rel").readText()

    @Test
    fun `字体预览不得再提供应用为软件字体的入口`() {
        // 弹窗里的「应用为软件字体」把字体复制到 custom_app_font.ttf 并落一条设置，
        // 再弹「已应用」——可主题字体是硬编码的 FontFamily.Monospace（全工程 222 处
        // AuroraTextStyles 引用 + 30 处 AuroraFontFamily），那份设置没有任何消费点。
        // 用户以为全局换字体了，实际一个字形都没变。预览本体是真实可用的，保留。
        val s = src("ui/pages/FilePage.kt")
        assertFalse("不得再出现应用按钮文案", s.contains("应用为软件字体"))
        assertFalse("不得再复制字体到用户目录", s.contains("custom_app_font"))
        assertTrue(
            "字体预览本体必须保留",
            s.contains("FontFamily(android.graphics.Typeface.createFromFile")
        )
    }

    @Test
    fun `已无入口的字体与暗色设置必须清除`() {
        // darkModeOption / setDarkMode 无 UI 入口，主题固定暗色；自定义字体一族同样无人消费。
        // 留着会让后续改动以为这些开关是活的。
        val s = src("data/AppSettings.kt")
        listOf(
            "darkModeOption", "setDarkMode", "KEY_DARK_MODE",
            "useCustomFont", "customFontPath", "customFontName",
            "setCustomFont", "setCustomFontEnabled", "useBuiltInFont", "resetCustomFont"
        ).forEach { symbol ->
            assertFalse("残留死设置：$symbol", s.contains(symbol))
        }
    }

    @Test
    fun `隐藏底栏与左右提示两个开关必须真正生效`() {
        // 两个开关都跨了「设置存储 → MainActivity → 各页布局」三层，任一层漏接，
        // 表现都是「开关点得动但界面没变化」：隐藏底栏漏掉页面预留就只剩一个空白带，
        // 左右提示漏掉门控就成了关不掉的骚扰。
        val settings = src("data/AppSettings.kt")
        assertTrue(settings.contains("KEY_HIDE_DOCK_BAR = \"hide_dock_bar\""))
        assertTrue(settings.contains("KEY_SHOW_SWIPE_HINT = \"show_swipe_hint\""))
        assertTrue("隐藏底栏默认关", settings.contains("prefs.getBoolean(KEY_HIDE_DOCK_BAR, false)"))
        assertTrue("左右提示默认开", settings.contains("prefs.getBoolean(KEY_SHOW_SWIPE_HINT, true)"))

        val main = src("MainActivity.kt")
        assertTrue("底栏须受开关门控", main.contains("!WindowInsets.isImeVisible && !appSettings.hideDockBar"))
        assertTrue("提示文案须与开关同源", main.contains("左右滑屏可切换菜单"))
        // 冷启动一次：进程级标记，转屏导致 Activity 重建时不得重弹
        assertTrue(main.contains("private var swipeHintShown = false"))
        // 键不得取 showSwipeHint：那样在设置页拨动开关会顺带弹一条，
        // 且同一进程内「关掉再打开」的行为随是否已弹过而变。
        assertFalse(
            "提示不得以开关为 key 重组重弹",
            main.contains("LaunchedEffect(appSettings.showSwipeHint)")
        )

        val dock = src("ui/components/DockBar.kt")
        assertTrue(
            "预留高度须由开关换算",
            dock.contains("fun dockBarBottomPadding(hideDockBar: Boolean): Dp")
        )
        listOf("ui/pages/HomePage.kt", "ui/pages/FilePage.kt", "ui/pages/SettingsPage.kt").forEach { rel ->
            val s = src(rel)
            assertTrue("$rel 须经底栏预留换算", s.contains("dockBarBottomPadding(appSettings.hideDockBar)"))
            assertFalse("$rel 不得再写死底栏预留", s.contains("padding(bottom = 56.dp)"))
        }
        assertTrue(
            "终端页底部留白须随开关归零",
            src("ui/pages/TerminalPage.kt").contains("if (isImeVisible || appSettings.hideDockBar) 0.dp else 58.dp")
        )
    }

    @Test
    fun `保存分支的编码状态必须在主线程取快照`() {
        // 保存是 IO 挂起调用，期间用户可继续打字。编码 / 行尾 / BOM 与正文同为 Compose state，
        // 在 Dispatchers.IO 内读既不保证可见顺序，也可能写出的不是「点击保存那一刻」的组合。
        // 另存为分支此前已正确取快照，这里锁定普通保存分支不得再退化。
        val s = src("ui/components/TextEditorDialog.kt")
        val snapshotCount = Regex("val snapshotCharset = resolveCharset\\(\\)").findAll(s).count()
        assertTrue("保存与另存为都应在主线程取编码快照", snapshotCount >= 2)

        val at = s.indexOf("writeTextFile(path, snapshotText")
        assertTrue("应能找到普通保存的写盘调用", at > 0)
        val ioAt = s.lastIndexOf("withContext(Dispatchers.IO)", at)
        val ioBody = s.substring(ioAt, s.indexOf("Triple(false", ioAt))
        assertFalse("IO 块内不得再读 encoding", ioBody.contains("resolveCharset()"))
        assertFalse("IO 块内不得再读行尾", ioBody.contains("currentLineEnding"))
        assertFalse("IO 块内不得再读 BOM", ioBody.contains("hasBom"))
    }

    @Test
    fun `权限保存必须使用进程级作用域`() {
        // 提交要连跑 chmod / chown / chgrp，取消点若落在三步之间，文件停在「权限已改、
        // 属主未改」的半套状态且无提示 —— 本项目唯一带越权后果的操作。原先挂在页面级
        // rememberCoroutineScope 上，旋转 / 分屏重建即取消。
        val s = src("ui/components/FilePermissionDialog.kt")
        assertFalse(
            "不得再引入页面级 rememberCoroutineScope",
            s.contains("import androidx.compose.runtime.rememberCoroutineScope")
        )
        assertTrue("应定义文件级提交作用域", s.contains("private val permissionSubmitScope"))
        assertTrue("保存必须走该作用域", s.contains("permissionSubmitScope.launch {"))
    }

    @Test
    fun `体积格式化必须区域无关`() {
        // 编辑器分段加载条用 `"%.1f MB".format(...)`，走默认区域：逗号作小数点的区域显示
        // 「1,5 MB」，而文件列表（FileItem）固定 Locale.ROOT 显示「1.50 MB」——同一数值两种写法。
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
            assertEquals("1.5 GB", formatBytes(1536L * 1024 * 1024))
            assertEquals("3 KB", formatBytes(3072L))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `URI 解析不得保留未使用的 scheme 局部量`() {
        // resolveToRealPath 里 `val isFileScheme = … == "file"` 与紧随其后的 when 判定重复，
        // 且没有任何消费点 —— 留着会让人误以为 file:// 走的是另一条分支。
        val s = src("data/ExternalOpen.kt")
        assertFalse("未使用的局部量应删除", s.contains("isFileScheme"))
    }
}
