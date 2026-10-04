// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行入口的形态守护。
 *
 * 行内「执行」按钮在双列布局下已被移除：每列约 205dp，恒小于 220dp 的紧凑阈值，
 * 那枚按钮在手机上永远不渲染 —— 占着行内空间却点不到。执行改由动作菜单进入，
 * 并在执行前显式选择身份。
 *
 * 这里同时守一件已发生过的退化：`pendingExecuteItem` 曾经只声明、不接线，
 * 编译与测试都照常通过，而用户点了没有任何反应。
 */
class ExecuteEntryRegressionTest {

    private fun page(): String =
        File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    private fun dialog(): String =
        File("src/main/java/com/mixradio/droid/ui/components/ExecuteConfirmDialog.kt").readText()

    private fun listRow(): String {
        val page = page()
        val start = page.indexOf("itemsIndexed(pane.displayFileList")
        assertTrue("应能找到列表项构建", start > 0)
        val end = page.indexOf("fun PaneHeader(", start)
        return page.substring(start, if (end > 0) end else page.length)
    }

    private fun actionMenu(): String {
        val page = page()
        val start = page.indexOf("if (showActionDialog && selectedItem != null)")
        assertTrue("应能找到动作菜单", start > 0)
        val end = page.indexOf("ExecuteConfirmDialog(", start)
        return page.substring(start, if (end > 0) end else page.length)
    }

    @Test fun `行内执行按钮已移除`() {
        val row = listRow()
        assertTrue(
            "行内不得再有「执行」按钮：双列下每列约 205dp，小于 220dp 紧凑阈值，" +
                "该按钮在手机上永远不渲染",
            !row.contains("text = \"执行\"")
        )
        assertTrue(
            "行内不得再调用 onExecuteFileAndNavigate",
            !row.contains("onExecuteFileAndNavigate")
        )
    }

    @Test fun `字体预览按钮保留`() {
        val row = listRow()
        assertTrue(
            "字体预览是行内按钮，与被移除的执行按钮同处一个 if/else 链，" +
                "改结构时不应连带删掉",
            row.contains("showFontPreviewDialog = true")
        )
    }

    @Test fun `动作菜单对可执行文件提供执行项`() {
        val menu = actionMenu()
        assertTrue(
            "执行入口应在动作菜单里",
            menu.contains("ActionTextRow(\"执行\"")
        )
        assertTrue(
            "执行项须限定在脚本 / 可执行二进制",
            menu.contains("if (item.isExecutableScript || item.isExecutableBinary)")
        )
        assertTrue(
            "执行项必须写入待确认槽位，由弹窗决定身份",
            menu.contains("pendingExecuteItem = item")
        )
    }

    @Test fun `待执行槽位不得只声明不接线`() {
        val page = page()
        val declared = page.indexOf("var pendingExecuteItem by rememberSaveable")
        assertTrue("应能找到待执行槽位声明", declared > 0)
        val consumed = page.indexOf("fileItem = pendingExecuteItem,")
        assertTrue(
            "待执行槽位必须被弹窗消费。曾经的缺陷：只声明不接线，" +
                "编译与测试都通过，用户点了却没有反应",
            consumed > declared
        )
        assertTrue(
            "确认后必须把身份传给执行入口",
            page.contains("onExecuteFileAndNavigate(target.path, asRoot)")
        )
        assertTrue(
            "取消与选择两条路径都要清空槽位，否则下次点任意文件会以旧目标重现",
            page.contains("onDismiss = { pendingExecuteItem = null }") &&
                page.contains("pendingExecuteItem = null")
        )
    }

    @Test fun `确认框提供取消与两种身份`() {
        val dlg = dialog()
        listOf("取消", "无ROOT", "有ROOT").forEach { label ->
            assertTrue("确认框应提供「$label」", dlg.contains("\"$label\""))
        }
        assertTrue("取消走 onDismiss", dlg.contains("onClick = onDismiss"))
        assertTrue("普通用户身份传 false", dlg.contains("onPick(false)"))
        assertTrue("ROOT 身份传 true", dlg.contains("onPick(true)"))
    }

    @Test fun `三枚按钮等分宽度且不逐字换行`() {
        val dlg = dialog()
        assertEquals(
            "三枚按钮都应等分宽度。不定宽时 Material3 的最小宽度加内边距会超出对话框，" +
                "最后一枚被压到最窄、文字逐字竖排（真机实测「有ROOT」竖成一行一个字）",
            3,
            Regex("""\.weight\(1f\)""").findAll(dlg).count()
        )
        assertEquals(
            "每个按钮标签都应禁止换行",
            3,
            Regex("maxLines = 1, softWrap = false").findAll(dlg).count()
        )
    }

    @Test fun `不显示取不到值的权限行`() {
        val dlg = dialog()
        assertTrue(
            "目录列举得到的 FileItem 不带 permissions（该字段默认空串），" +
                "列出它只会得到一行空值",
            !dlg.contains("fileItem.permissions")
        )
    }

    @Test fun `未授权 ROOT 时禁用 ROOT 选项并说明原因`() {
        val dlg = dialog()
        val pickAt = dlg.indexOf("onPick(true)")
        assertTrue("应能找到 ROOT 选项按钮", pickAt > 0)
        val enabledAt = dlg.indexOf("enabled = rootGranted", pickAt)
        assertTrue(
            "ROOT 选项须受 rootGranted 约束。留着可点的话，" +
                "用户得到的是一串 su 失败输出而不是一句「没授权」",
            enabledAt in pickAt until (pickAt + 200)
        )
        assertTrue(
            "未授权时应说明只能以普通用户身份执行",
            dlg.contains("尚未授权 ROOT，只能以普通用户身份执行")
        )
    }

    @Test fun `待执行槽位跨重建保留`() {
        val page = page()
        assertTrue(
            "确认框属用户显式意图，旋转 / 分屏后不应被静默丢弃",
            page.contains("var pendingExecuteItem by rememberSaveable(stateSaver = FileItemSaver)")
        )
        assertEquals(
            "待执行槽位应只有一个，避免两处状态各写一半",
            1,
            Regex("var pendingExecuteItem by ").findAll(page).count()
        )
    }
}
