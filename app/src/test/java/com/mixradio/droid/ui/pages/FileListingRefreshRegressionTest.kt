// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「进目录后长时间看不见文件 / 偶发显示当前目录为空」的回归护栏。
 *
 * 三个症状同源：
 *  1. 每次刷新都清空列表 → 必然经过一段空列表窗口；
 *  2. 该窗口内骨架屏或空态文案接管，用户观感是「刷新期看不见文件」；
 *  3. 空态文案里「当前目录为空」会被渲染成对失败的事实断言 → 误报。
 *
 * 修法是「保留旧列表 + 禁用交互」，而不是清空：清空虽然能杜绝误删，
 * 但代价就是每次刷新都有空白窗口，且窗口内必然渲染一次空态文案。
 */
class FileListingRefreshRegressionTest {

    private fun page(): String = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    private fun refreshBody(): String {
        val page = page()
        val start = page.indexOf("fun refreshPane(paneIndex: Int, showToast: Boolean = false)")
        assertTrue("应能找到 refreshPane", start > 0)
        val end = page.indexOf("// 按指定冲突策略执行冲突目标集移动", start)
        return page.substring(start, if (end > 0) end else page.length)
    }

    @Test fun `刷新不得清空列表`() {
        val body = refreshBody()
        val launchAt = body.indexOf("pane.refreshJobRef[0] = scope.launch")
        assertTrue("应能找到刷新协程", launchAt > 0)

        assertTrue(
            "发起刷新时清空 displayFileList 会造成每次刷新都有一段空白窗口，" +
                "窗口内用户看到骨架屏甚至「当前目录为空」",
            body.indexOf("displayFileList = emptyList()") !in 0 until launchAt
        )
        assertTrue(
            "同样不得清空 fileList",
            body.indexOf("fileList = emptyList()") !in 0 until launchAt
        )
    }

    @Test fun `保留的旧列表必须被禁用交互`() {
        val page = page()
        // 这是保留列表的前提：不可操作才敢保留。否则会出现
        // 「路径栏是 B、列表是 A，长按删掉 A 里的文件」。
        assertTrue(
            "列表项必须用 enabled = !listIsStale 真正禁用点击与长按",
            page.contains("enabled = !pane.listIsStale")
        )
        val clickableAt = page.indexOf("combinedClickable(")
        val gateAt = page.indexOf("enabled = !pane.listIsStale")
        assertTrue("门禁必须落在列表项的 combinedClickable 上", clickableAt in 0 until gateAt)

        assertTrue(
            "列表可见但已过期时应有视觉提示（降透明度），否则用户点了没反应",
            page.contains("alpha(if (dimmed) 0.45f else 1f)")
        )
    }

    @Test fun `作废标记只在真有旧列表时置位`() {
        val body = refreshBody()
        assertTrue(
            "首屏没有任何历史列表时不该标记 stale（否则首屏列表恒不可点）",
            body.contains("listIsStale = pane.displayFileList.isNotEmpty()")
        )
    }

    @Test fun `空态文案不得把加载中说成目录为空`() {
        val page = page()
        // 加载中由骨架屏分支接管（isLoading && 列表为空），空态分支必须在其之后，
        // 否则加载完成的瞬间会先渲染一次「当前目录为空」。
        val skeletonAt = page.indexOf("if (pane.isLoading && pane.displayFileList.isEmpty())")
        val emptyAt = page.indexOf("} else if (pane.displayFileList.isEmpty())")
        assertTrue("应能找到骨架屏分支", skeletonAt > 0)
        assertTrue("应能找到空态分支", emptyAt > 0)
        assertTrue("骨架屏必须优先于空态", skeletonAt < emptyAt)

        val hintAt = page.indexOf("\"当前目录为空\"")
        assertTrue("应存在空目录文案", hintAt > 0)
        val hintWindow = page.substring(hintAt - 500, hintAt)
        assertTrue(
            "「当前目录为空」只能在既无搜索词、也无读取错误时出现",
            hintWindow.contains("errorText != null") && hintWindow.contains("paneQuery.isNotEmpty()")
        )
    }

    @Test fun `目录不存在必须给出原因而不是让空态断言为空`() {
        val page = page()
        val missingAt = page.indexOf("is DirectoryListing.Missing -> {")
        assertTrue("应能找到 Missing 分支", missingAt > 0)
        val branch = page.substring(missingAt, missingAt + 400)
        assertTrue(
            "Missing 不设 directoryLoadError 的话，空态会落到「当前目录为空」，" +
                "把「读不到」断言成「没有文件」",
            branch.contains("directoryLoadError =")
        )
    }

    @Test fun `同目录连续刷新必须节流`() {
        val body = refreshBody()
        assertTrue(
            "删除 / 重命名等操作常连着调多次 refresh，每次都 fork su 会让列表反复 loading",
            body.contains("pane.lastLoadedAtRef") && body.contains("refreshThrottleMs")
        )
        assertTrue(
            "手动刷新必须绕过节流",
            body.contains("!showToast")
        )
    }

    @Test fun `单次 su 完成存在性与列举`() {
        val rfm = File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        val start = rfm.indexOf("suspend fun listDirectory(")
        assertTrue("应能找到 listDirectory", start > 0)
        val end = rfm.indexOf("\n    private suspend fun listDirectoryLocal(", start)
        val body = rfm.substring(start, if (end > 0) end else rfm.length)

        val suCalls = body.windowed(3).count { it == "run" }
        assertTrue(
            "单次 su 内应只有一次 runCommandSync 调用（三次串行 su 既有延迟又有 TOCTOU 窗口）",
            body.split("RootService.runCommandSync").size - 1 == 1
        )
        assertTrue("存在性必须由退出码区分", body.contains("exit 3") && body.contains("exit 4"))
        assertTrue("su 失败必须被识别", body.contains("code == -1"))
        assertTrue(
            "输出不完整（标记缺失）必须报失败，不得当成空目录",
            body.contains("indexOf(META_SEP)") && body.contains("目录读取结果不完整")
        )
    }

    @Test fun `两个通道必须用 NUL 与固定标记隔开`() {
        val rfm = File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        assertTrue(
            "文件名必须走 NUL 通道：ext4 允许文件名含换行，按行切分会造出幻影条目",
            rfm.contains("-print0") && rfm.contains("split('\\u0000')")
        )
        assertTrue(
            "名称与元数据之间必须有固定标记，不能靠两次 find 的输出顺序配对",
            rfm.contains("META_SEP")
        )
    }
}
