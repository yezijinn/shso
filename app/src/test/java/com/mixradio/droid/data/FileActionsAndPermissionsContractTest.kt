// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口迁移、权限行、文件第二列动作的落地契约（源码文本断言）。
 */
class FileActionsAndPermissionsContractTest {

    private fun src(rel: String): String =
        java.io.File("src/main/java/com/mixradio/droid/$rel").readText()

    private val settingsPage = src("ui/pages/SettingsPage.kt")
    private val terminalPage = src("ui/pages/TerminalPage.kt")
    private val partials = src("ui/pages/SettingsPagePartials.kt")
    private val filePage = src("ui/pages/FilePage.kt")
    private val manifest = java.io.File("src/main/AndroidManifest.xml").readText()

    /** 环境一律来自内置 EmbeddedEnv，不再提供可下载的扩展环境包入口。 */
    @Test
    fun envComesFromEmbeddedEnvOnly() {
        assertFalse("设置页不应有环境包入口", settingsPage.contains("EnvPack"))
        assertFalse("终端设置不应再有环境包入口", terminalPage.contains("EnvPack"))
        assertTrue(
            "内置环境需在启动时准备",
            src("ShsoApplication.kt").contains("EmbeddedEnv.ensure(")
        )
    }

    /** 「获取应用列表」权限行位于「安装应用」上一行。 */
    @Test
    fun appListPermissionRowAboveInstall() {
        assertTrue("需有该权限行", partials.contains("获取应用列表"))
        val appListIdx = partials.indexOf("获取应用列表")
        val installIdx = partials.indexOf("\"安装应用\"")
        assertTrue("安装应用行必须存在", installIdx > 0)
        assertTrue("获取应用列表应在安装应用之前", appListIdx in 1 until installIdx)
        assertTrue("清单需声明应用列表权限", manifest.contains("QUERY_ALL_PACKAGES"))
    }

    /** 文件单击菜单第二列：五个新动作齐备，且接入对应实现。 */
    @Test
    fun fileMenuSecondColumnActions() {
        listOf("添加到压缩包", "复制 ->", "移动 ->", "解压 ->", "打开方式", "分享").forEach {
            assertTrue("缺少动作：$it", filePage.contains(it))
        }
        assertTrue("打包须走 ArchiveCreator", filePage.contains("ArchiveCreator.createZip("))
        assertTrue("复制到另一列", filePage.contains("RootFileManager.copyToDirectory("))
        assertTrue("移动到另一列", filePage.contains("moveToOtherPane("))
        assertTrue("解压到另一列", filePage.contains("extractTo(item, otherPaneDirectory())"))
        assertTrue("打开方式", filePage.contains("OpenWith.open("))
        assertTrue("分享", filePage.contains("OpenWith.share("))
    }

    /**
     * 刷新契约在去重后反转：菜单框架**不再**无条件刷新。
     *
     * 旧契约「每个功能按钮点击后立即刷新」造成两类浪费：
     *  · 只开子弹窗 / 只进多选的动作也整列列举一趟（一次 su 往返），并让整列灰一下；
     *  · 真正改盘的动作自己收尾还要再刷一次 —— 同一操作两趟 su。
     * 新契约：框架只转发点击，刷新由动作体在收尾处按需调用，
     * 且「写到另一列」的动作必须刷到目标列（见 crossPaneActionsRefreshBothPanes）。
     */
    @Test
    fun actionMenuRowDelegatesRefreshToActionBody() {
        val rowIdx = filePage.indexOf("fun ActionTextRow(")
        assertTrue("缺少 ActionTextRow", rowIdx > 0)
        // 按「下一个块」截取，不用固定字窗：函数变长时固定窗会切进动作体
        val end = filePage.indexOf("if (showActionDialog && selectedItem != null)", rowIdx)
        assertTrue("应能找到动作菜单入口", end > rowIdx)
        val body = filePage.substring(rowIdx, end)
        assertTrue("菜单行须仍可点击（禁用态由 enabled 控制）", body.contains(".clickable(enabled = enabled)"))
        assertFalse(
            "菜单框架不得无条件刷新：不改磁盘的动作白跑一趟 su，改磁盘的动作重复刷",
            body.contains("refresh()")
        )
    }

    /** 输出到另一列的动作必须刷新另一列（双列刷新）。 */
    @Test
    fun crossPaneActionsRefreshBothPanes() {
        assertTrue("需有双列刷新工具", filePage.contains("fun refreshBothPanes()"))
        val copyIdx = filePage.indexOf("fun copyToOtherPane(")
        val moveIdx = filePage.indexOf("fun moveToOtherPane(")
        val extractIdx = filePage.indexOf("fun extractTo(")
        assertTrue("copyToOtherPane 应双列刷新",
            filePage.substring(copyIdx, (copyIdx + 700).coerceAtMost(filePage.length)).contains("refreshBothPanes()"))
        assertTrue("moveToOtherPane 应双列刷新",
            filePage.substring(moveIdx, (moveIdx + 700).coerceAtMost(filePage.length)).contains("refreshBothPanes()"))
        assertTrue("extractTo 应双列刷新",
            filePage.substring(extractIdx, (extractIdx + 1200).coerceAtMost(filePage.length)).contains("refreshBothPanes()"))
    }

    /** 「打开方式」需共享副本并声明 provider 路径。 */
    @Test
    fun openWithSharesCacheCopy() {
        val openWith = src("data/OpenWith.kt")
        assertTrue("需用 FileProvider 共享", openWith.contains("FileProvider.getUriForFile"))
        assertTrue("需授予读权限", openWith.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue("需支持分享（ACTION_SEND）", openWith.contains("Intent.ACTION_SEND"))
        assertTrue("分享需携带 EXTRA_STREAM", openWith.contains("EXTRA_STREAM"))
        val paths = java.io.File("src/main/res/xml/file_provider_paths.xml").readText()
        assertTrue("需暴露 open-with 缓存目录", paths.contains("open-with/"))
    }
}
