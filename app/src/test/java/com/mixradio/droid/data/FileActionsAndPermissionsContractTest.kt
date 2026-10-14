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

    /** 扩展环境包入口已从设置页移入终端设置。 */
    @Test
    fun envPackEntryMovedToTerminalSettings() {
        assertFalse("设置页不应再有环境包入口", settingsPage.contains("EnvPack"))
        assertTrue("终端设置需含环境包入口", terminalPage.contains("扩展环境包"))
        assertTrue("终端设置需调用下载", terminalPage.contains("EnvPack.startDownload("))
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
        listOf("添加到压缩包", "复制 ->", "移动 ->", "解压 ->", "打开方式").forEach {
            assertTrue("缺少动作：$it", filePage.contains(it))
        }
        assertTrue("打包须走 ArchiveCreator", filePage.contains("ArchiveCreator.createZip("))
        assertTrue("复制到另一列", filePage.contains("RootFileManager.copyToDirectory("))
        assertTrue("移动到另一列", filePage.contains("moveToOtherPane("))
        assertTrue("解压到另一列", filePage.contains("extractTo(item, otherPaneDirectory())"))
        assertTrue("打开方式", filePage.contains("OpenWith.open("))
    }

    /** 「打开方式」需共享副本并声明 provider 路径。 */
    @Test
    fun openWithSharesCacheCopy() {
        val openWith = src("data/OpenWith.kt")
        assertTrue("需用 FileProvider 共享", openWith.contains("FileProvider.getUriForFile"))
        assertTrue("需授予读权限", openWith.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        val paths = java.io.File("src/main/res/xml/file_provider_paths.xml").readText()
        assertTrue("需暴露 open-with 缓存目录", paths.contains("open-with/"))
    }
}
