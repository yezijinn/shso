// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二十四轮护栏：性能（「反应慢」）与无用嵌套。
 *
 * 这两类缺陷的共同点是**不会报错、不会崩，只让用户觉得卡**，
 * 因此极易在后续重构中被改回去。本类把当时的判断固化成回归网。
 */
class PerfAndNestingRegressionTest {

    private fun src(rel: String): String {
        val target = "src/main/java/com/mixradio/droid/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, target)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到主源码: $target")
    }

    private val fileItem = src("data/FileItem.kt")
    private val rootFileManager = src("data/RootFileManager.kt")
    private val archiveExtractor = src("data/ArchiveExtractor.kt")
    private val filePage = src("ui/pages/FilePage.kt")
    private val picker = src("ui/components/BuiltInFilePicker.kt")
    private val editor = src("ui/components/TextEditorDialog.kt")
    private val homePage = src("ui/pages/HomePage.kt")
    private val externalOpen = src("data/ExternalOpen.kt")

    // ── 反应慢：FileItem 的派生属性必须是构造期一次算好 ──────────────────

    @Test fun `FileItem 的派生属性不得是每次访问重算的 get()`() {
        val body = fileItem.substringAfter("data class FileItem(").substringBefore("companion object")
        // formattedDate 例外：它已复用预编译的 FILE_DATE_FORMATTER，构造期求值没有收益，
        // 排除掉以免断言误伤。
        val computed = Regex("""val\s+(?!formattedDate)\w+[^=]*\n\s*get\(\)""")
        assertTrue(
            "FileItem 的派生属性若仍是 get() 计算属性，文件列表每行每次重组都会重跑 " +
                "Regex 构造 + 多次 lowercase + String.format；必须改为构造期 val",
            !computed.containsMatchIn(body)
        )
    }

    @Test fun `数字尾缀正则必须提取到顶层只编译一次`() {
        // 顶层常量声明处**应当**有且仅有一次 Regex 构造；
        // 要禁的是「在派生属性里每次访问现场构造」。
        val occurrences = Regex("""Regex\("\\\\\.\\\\d\+\$"\)""").findAll(fileItem).count()
        assertEquals(
            "数字尾缀正则只允许在顶层常量处构造一次，不得出现在派生属性里",
            1,
            occurrences
        )
        assertTrue("顶层应存在预编译常量 NUMERIC_SUFFIX", fileItem.contains("private val NUMERIC_SUFFIX"))
        assertTrue("realExtension 必须复用该常量", fileItem.contains("NUMERIC_SUFFIX.find(name)"))
        assertTrue("realArchiveName 必须复用该常量", fileItem.contains("NUMERIC_SUFFIX.matches(name)"))
    }

    @Test fun `FileItem 的类型判定必须只依赖构造入参`() {
        // isExtensionlessText 依赖 isInstallable 等，构造期求值要求声明顺序正确。
        val body = fileItem.substringAfter("data class FileItem(")
        val extlessAt = body.indexOf("val isExtensionlessText")
        val installableAt = body.indexOf("val isInstallable")
        val viewableAt = body.indexOf("val isViewableImage")
        val archiveAt = body.indexOf("val isArchive")
        assertTrue("isInstallable 必须先于 isExtensionlessText", installableAt in 0 until extlessAt)
        assertTrue("isViewableImage 必须先于 isExtensionlessText", viewableAt in 0 until extlessAt)
        assertTrue("isArchive 必须先于 isExtensionlessText", archiveAt in 0 until extlessAt)
    }

    // ── 反应慢：缓存键身份 ──────────────────────────────────────────────

    @Test fun `fileFilter 必须用 remember 固定 lambda 身份`() {
        assertTrue(
            "sameExtensionFilter 每次返回新 lambda，而 BuiltInFilePicker 把它当 remember 的 key；" +
                "不固定身份则缓存每帧失效，每敲一个键都重跑过滤+排序+N 次 realpath",
            editor.contains("remember(currentFilePath)") &&
                editor.contains("TextCompare.sameExtensionFilter")
        )
    }

    @Test fun `选中集合查找不得使用线性扫描`() {
        assertTrue(
            "selectedPaths 是 SnapshotStateList，contains/containsAll 为线性扫描",
            filePage.contains("selectedPathSet")
        )
        assertTrue(
            "每帧执行的 isSelected 判定必须走 HashSet，不能线性扫描",
            filePage.contains("item.path in paneSelectedPaths")
        )
        assertTrue(
            "全选判定的 containsAll 也必须走 HashSet",
            filePage.contains("selectedPathSet.containsAll(allFilePaths)")
        )
    }

    @Test fun `全选判定只在弹窗打开时计算`() {
        val at = filePage.indexOf("val allFilesSelected")
        assertTrue("应能找到 allFilesSelected", at > 0)
        val dialogAt = filePage.indexOf("if (showFileSettingsDialog) {")
        assertTrue(
            "allFilesSelected 的唯一消费者是该弹窗，写在 if 之外就是每次重组白算",
            at > dialogAt
        )
    }

    // ── 反应慢：组合期重活 ──────────────────────────────────────────────

    @Test fun `搜索重算必须有防抖且 delay 在协程内部`() {
        val effectAt = filePage.indexOf("LaunchedEffect(appSettings.showHiddenFiles, appSettings.fileSortMode, pane.nameQuery)")
        assertTrue("应能找到重算协程", effectAt > 0)
        val body = filePage.substring(effectAt, effectAt + 2600)
        assertTrue("重算前必须 delay 防抖", body.contains("delay(searchDebounceMs)"))
        assertTrue(
            "防抖必须发生在 withContext(Dispatchers.Default) **之前**，否则只是并行 delay",
            body.indexOf("delay(searchDebounceMs)") < body.indexOf("withContext(Dispatchers.Default)")
        )
    }

    @Test fun `历史面板不得在组合期全文扫描`() {
        assertTrue(
            "entry.content 上限 20 万字符，每行 lineSequence().count() 在组合期重扫，" +
                "6 个可见行 ≈ 单次重组 120 万次字符比较",
            !editor.contains("""text = "${'$'}{entry.content.lineSequence().count()}""")
        )
        assertTrue("行数必须用 remember 固定", editor.contains("remember(entry) { entry.content.lineSequence().count() + 1 }"))
        assertTrue("历史列表必须有稳定 key", editor.contains("itemsIndexed(history, key ="))
    }

    @Test fun `主页行选中判定必须用 derivedStateOf 隔离`() {
        assertTrue(
            "在父组合域直接读 filePathInput 会让 N 行在每次击键时全部重组" +
                "（forEachIndexed 是 inline，没有窗口化可依赖）",
            homePage.contains("derivedStateOf { filePathInput == fileItem.path }")
        )
    }

    // ── 无用嵌套 ────────────────────────────────────────────────────────

    @Test fun `preferRoot 不得存在返回值相同的两条分支`() {
        val body = rootFileManager.substringAfter("private suspend fun preferRoot(").substringBefore("}")
        assertFalse(
            "if (awaitRootState()) return X / return X 两条分支逐字相同，纯装饰",
            Regex("""if\s*\([^)]*\)\s*return[^
]*
\s*return""").containsMatchIn(body)
        )
        assertTrue("应只等探测落定再读状态", body.contains("awaitRootState()"))
    }

    @Test fun `不得存在恒为 false 的失败标记`() {
        assertFalse(
            "rootFailed 从未被置 true，恒为 false 的死变量会让分支语义失真",
            rootFileManager.contains("rootFailed")
        )
    }

    @Test fun `同名副本探测不得重复执行同一次判断`() {
        // 注释里可以提及旧写法（作为反例说明），但不得再有定义或调用。
        val codeOnly = rootFileManager.lineSequence()
            .filter { !it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "pathExistsQuiet 的函数体就是 File(path).exists()，与左操作数逐字相同；" +
                "`||` 只在左为 true 时省下右，左为 false（正是需继续探测时）必然再做一次 stat",
            codeOnly.contains("pathExistsQuiet")
        )
        assertTrue(
            "拷贝时的同名探测应只调一次 exists",
            rootFileManager.contains("while (n < COPY_NAME_PROBE_LIMIT && File(destPath).exists())")
        )
    }

    @Test fun `不得保留实现完全相同的重复谓词`() {
        assertFalse(
            "isExtractable 与 isKnownArchive 函数体逐字相同，且注释停留在 rar 不支持的旧状态",
            archiveExtractor.contains("fun isExtractable(")
        )
        assertTrue("FileItem 的可解压判定应直接指向 isArchive", fileItem.contains("val isExtractableArchive: Boolean = isArchive"))
    }

    @Test fun `常量前缀的归一化结果必须缓存`() {
        assertTrue(
            "两组前缀都是模块级常量，归一化纯词法无副作用，每次调用重复计算属纯浪费",
            externalOpen.contains("normalizedPrefixCache")
        )
    }

    @Test fun `不得保留无上界且无调用点的探测函数`() {
        assertFalse(
            "uniqueFile 的 while (true) 没有上界（兄弟函数 reserveUniqueFile 有 1000 次上限），" +
                "且无生产调用点",
            externalOpen.contains("fun uniqueFile(")
        )
    }

    @Test fun `不得保留必被覆盖的死写`() {
        assertFalse(
            "try 体内的 isTransforming = false 必被紧随的 finally 覆盖",
            editor.contains("""pendingTransform = result
                isTransforming = false""")
        )
    }

    // ── 值守：下列语义不得回退 ──────────────────────────────────────

    @Test fun `保留旧列表的配套门禁必须仍在`() {
        // 上一轮修的「刷新不再清空列表」，其安全前提是列表不可操作。
        assertTrue("列表项必须仍受 listIsStale 门禁", filePage.contains("enabled = !pane.listIsStale"))
        assertTrue(
            "过期列表仍应有视觉提示",
            filePage.contains("alpha(if (dimmed) 0.45f else 1f)")
        )
        assertTrue("同目录刷新节流不得被移除", filePage.contains("refreshThrottleMs"))
    }

    @Test fun `目录列举仍是单次 su 且存在性由退出码承担`() {
        val start = rootFileManager.indexOf("suspend fun listDirectory(")
        val end = rootFileManager.indexOf("\n    private suspend fun listDirectoryLocal(", start)
        val body = rootFileManager.substring(start, if (end > 0) end else rootFileManager.length)
        assertEquals(
            "单次 su 内只应有一次 runCommandSync",
            1,
            body.split("RootService.runCommandSync").size - 1
        )
        assertTrue("存在性语义不得退回「看输出是否为空」", body.contains("exit 3") && body.contains("exit 4"))
    }
}
