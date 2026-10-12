// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import com.mixradio.droid.data.RootFileManager
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双列状态隔离守护。
 *
 * 页面里绝大多数读写走 `PaneProp` 委托，语义是「聚焦列」。这条委托在单列时代
 * 恰好等价于页面状态，拆成两列后：**凡是本该按列的地方误用了委托，另一列就
 * 永远拿不到自己的状态**。这类缺陷不会崩、不会报错，只是安静地显示错的文案、
 * 不重排、不清词，回归测试要盯的正是这些「看起来能跑」的地方。
 */
class DualPaneStateRegressionTest {

    private fun page(): String =
        File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    private fun paneBody(): String {
        val page = page()
        val start = page.indexOf("fun PaneBody(pane: FilePaneState, paneIndex: Int, compact: Boolean)")
        assertTrue("应能找到 PaneBody", start > 0)
        val end = page.indexOf("fun PaneHeader(", start)
        return page.substring(start, if (end > 0) end else page.length)
    }

    private fun paneHeader(): String {
        val page = page()
        val start = page.indexOf("fun PaneHeader(")
        assertTrue("应能找到 PaneHeader", start > 0)
        val end = page.indexOf("fun RowScope.BottomBarButton(", start)
        return page.substring(start, if (end > 0) end else page.length)
    }

    @Test fun `空态文案必须读本列而非聚焦列`() {
        val body = paneBody()
        assertTrue(
            "空态的错误文案应取 pane.directoryLoadError",
            body.contains("pane.directoryLoadError")
        )
        assertTrue(
            "空态的过滤词应取 pane.nameQuery",
            body.contains("pane.nameQuery")
        )
        assertTrue(
            "空态里出现委托读法 val errorText = directoryLoadError：" +
                "非聚焦列失败时会拿聚焦列的 null 当自己的，把「读取失败」显示成「当前目录为空」",
            !body.contains("val errorText = directoryLoadError")
        )
        assertTrue(
            "空态里出现 nameQuery.isEmpty() 判定，同样会读到聚焦列的过滤词",
            !body.contains("nameQuery.isEmpty()")
        )
    }

    @Test fun `展示列表重算按列各挂一个`() {
        val page = page()
        val recomputeAt = page.indexOf(
            "LaunchedEffect(appSettings.showHiddenFiles, appSettings.fileSortMode, pane.nameQuery)"
        )
        assertTrue(
            "重算特化的键必须是 pane.nameQuery；用委托 nameQuery 就只重算聚焦列，" +
                "改排序后另一列保持旧顺序且再无重算时机",
            recomputeAt > 0
        )
        val forEachAt = page.lastIndexOf("panes.forEach { pane ->", recomputeAt)
        assertTrue("重算特化应包在按列的 panes.forEach 里", forEachAt in 0 until recomputeAt)
        assertTrue(
            "重算结果必须落回 pane.displayFileList",
            page.contains("pane.displayFileList = computed")
        )
        assertTrue(
            "重算的基准必须是 pane.fileList",
            page.contains("val source = pane.fileList")
        )
    }

    @Test fun `切目录按列清过滤词并重置自愈标记`() {
        val page = page()
        val start = page.indexOf("LaunchedEffect(pane.currentDirectory)")
        assertTrue("应能找到按列的切目录特化", start > 0)
        val end = page.indexOf("LaunchedEffect(feedbackMessage)", start)
        val body = page.substring(start, if (end > 0) end else page.length)

        assertTrue(
            "切目录必须清本列过滤词：不清的话右列带着上一个目录的 keyword 进新目录，" +
                "列表按旧词过滤，用户看到「无匹配项」",
            body.contains("pane.nameQuery = \"\"")
        )
        assertTrue(
            "切目录必须重置 retriedForEmptyOnce：「每列一次」是每个目录一次，" +
                "只在进程启动时置位的话冷启动用掉之后再也不重试",
            body.contains("pane.retriedForEmptyOnce = false")
        )
        assertTrue(
            "旧的「页面级切目录清词」写法应已移除：它经委托只清聚焦列",
            !page.contains("if (nameQuery.isNotEmpty()) nameQuery = \"\"")
        )
    }

    @Test fun `选中集 HashSet 建在列级且随内容变化重算`() {
        val page = page()
        val rememberAt = page.indexOf("val paneSelectedPaths by remember(pane.selectedPaths)")
        val listAt = page.indexOf("itemsIndexed(pane.displayFileList")
        assertTrue("应能找到选中集 HashSet", rememberAt > 0)
        assertTrue("应能找到列表构建", listAt > 0)
        assertTrue(
            "选中集 HashSet 必须早于 itemsIndexed。放进 item 作用域会得到「每个可见行各一份」：" +
                "既没起缓存作用，又把一次 O(N) 换成 O(可见行数 × N)",
            rememberAt < listAt
        )
        assertTrue(
            "列级选中集必须以 derivedStateOf 缓存：remember 以列表实例为键，" +
                "SnapshotStateList 内容变化不换实例，缓存首帧的空集会让 isSelected 恒为 false，" +
                "选中行既无底色也无行尾标记，多选在界面上完全没有反馈",
            page.contains("derivedStateOf { pane.selectedPaths.toHashSet() }")
        )
        assertTrue(
            "页面级 selectedPathSet 同因：remember(selectedPaths) 停在首帧空集，" +
                "「该范围已整体选中」判定恒为 false，范围弹窗里已选中的范围行不着色、" +
                "第二次点击也走不到整批退出的分支",
            page.contains("derivedStateOf { selectedPaths.toHashSet() }")
        )
    }

    @Test fun `多选切换写本列选中集且文件夹可选中`() {
        val body = paneBody()
        assertTrue(
            "行点击切换选中必须写 pane.selectedPaths：页面级 selectedPaths 指向聚焦列，" +
                "在非聚焦列点行会把项加进另一列的选中集，本列既不变色也选不中",
            body.contains("pane.selectedPaths.remove(item.path)") &&
                body.contains("pane.selectedPaths.add(item.path)")
        )
        assertTrue(
            "多选模式下文件夹必须可切换选中：「一切」范围会把文件夹选进来，" +
                "用户得能逐项退掉；不得保留「多选模式点文件夹什么都不做」的分支",
            !body.contains("if (!pane.multiSelectMode) navigateTo(item.path)")
        )
    }

    @Test fun `全选范围入口连通四类范围`() {
        val page = page()
        assertTrue(
            "范围清单应由 buildFileSelectScopes 从展示列表拆出（基准必须与旧全选口径一致，即筛选后的展示列表）",
            page.contains("buildFileSelectScopes(displayFileList)")
        )
        assertTrue("范围弹窗必须挂在文件页上", page.contains("FileSelectScopeDialog("))
        assertTrue(
            "设置弹窗的入口应改为打开范围弹窗，而不是直接动选中集",
            page.contains("onSelectRequest = {") && page.contains("showSelectScopeDialog = true")
        )
        assertTrue(
            "旧的无范围全选入口（allFilePaths / onSelectAllFilesRequest）应已移除",
            !page.contains("allFilePaths") && !page.contains("onSelectAllFilesRequest")
        )
        val settings = File("src/main/java/com/mixradio/droid/ui/components/FileListViewSettings.kt").readText()
        assertTrue(
            "设置弹窗里的入口文案应为「全选」，两处入口都要指向范围弹窗",
            settings.contains("ActionCell(\"全选\", onSelectRequest")
        )
    }

    @Test fun `作废标记不再保留恒假分支`() {
        val page = page()
        assertTrue(
            "requestedDir 刚从 pane.currentDirectory 取来，两者恒等，" +
                "「pane.currentDirectory != requestedDir」永远为假，属死分支",
            !page.contains("pane.currentDirectory != requestedDir")
        )
    }

    @Test fun `保留旧列表时失败原因仍可见`() {
        val header = paneHeader()
        assertTrue(
            "旧列表被保留时空态区不渲染，失败原因要有别的出口",
            header.contains("pane.directoryLoadError != null && pane.displayFileList.isNotEmpty()")
        )
    }

    @Test fun `两趟 find 出现新增名字时重试一次`() {
        val src = File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        assertTrue(
            "重试只能由「元数据通道看见名称通道没有的名字」触发：断链软链天然无元数据记录，两集合全等判据会白跑一整轮",
            src.contains("parsed.names.any { it !in nameSet }") && src.contains("attempt == 0")
        )
        assertTrue(
            "重试必须带 attempt 递增，否则持续变化的目录上无限递归",
            src.contains("listDirectory(targetPath, attempt + 1)")
        )
    }

    @Test fun `列举入口签名带重试计数`() {
        val src = File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
        assertTrue(
            "listDirectory 必须带默认参数 attempt，调用方才能要求重试而不必各自实现",
            src.contains("fun listDirectory(dirPath: String, attempt: Int = 0)")
        )
        val method = RootFileManager::class.java.methods.firstOrNull { it.name == "listDirectory" }
        assertTrue("应能找到 listDirectory", method != null)
        assertTrue(
            "listDirectory 的 JVM 签名应为「路径 + 重试计数 + 协程续体」三参",
            method!!.parameterCount == 3
        )
    }
}
