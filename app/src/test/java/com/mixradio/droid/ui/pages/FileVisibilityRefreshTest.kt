// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从别的 tab 切回文件页必须重列两列」「回前台必须重列」「写到另一列必须刷目标列」
 * 的护栏。
 *
 * 这类缺陷不崩、不报错：列表只是安静地停在切走前那份，用户看到
 * 「刚生成的文件切回来还是看不见」，只能手动点底栏「刷新」。
 *
 * 只能用源码断言：本模块的 FilePage 无法在 JVM 单测里加载 ——
 * 文件级 actionJobScope 取 Dispatchers.Main.immediate，
 * 测试环境没有 Main 调度器，类初始化即抛异常。仓库既有护栏同此风格。
 */
class FileVisibilityRefreshTest {

    private fun page(): String =
        File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    private fun main(): String =
        File("src/main/java/com/mixradio/droid/MainActivity.kt").readText()

    /** 从 [startMarker] 到下一个顶层局部函数声明之前（避免固定字窗随实现增长失效）。 */
    private fun funSlice(startMarker: String, maxSpan: Int = 2000): String {
        val p = page()
        val start = p.indexOf(startMarker)
        assertTrue("应能找到 $startMarker", start > 0)
        val next = p.indexOf("\n    fun ", start + startMarker.length)
        val stop = if (next > start) minOf(next, start + maxSpan) else start + maxSpan
        return p.substring(start, minOf(stop, p.length))
    }

    @Test
    fun `切回文件页必须重列两列`() {
        val p = page()
        assertTrue("文件页须对外暴露可见性开关", p.contains("isVisible: Boolean = true"))
        val at = p.indexOf("LaunchedEffect(isVisible)")
        assertTrue("须订阅可见性变化", at > 0)
        val body = p.substring(at, (at + 700).coerceAtMost(p.length))
        assertTrue(
            "只在「不可见 → 可见」跃迁时重列：其余情形都会与首帧列举叠加成多趟 su",
            body.contains("if (isVisible && !wasVisible) refreshBothPanes()")
        )
    }

    @Test
    fun `首帧不得重列`() {
        // 初值必须等于当前可见性：否则首帧判据成立，
        // 与两列自己的首屏列举撞在一起。
        assertTrue(
            "切回判据的初值必须取当前 isVisible",
            page().contains("val lastVisibleRef = remember { booleanArrayOf(isVisible) }")
        )
    }

    @Test
    fun `主容器按落定页判断文件页可见性`() {
        // 用 settledPage 而不是 currentPage：animateScrollToPage 跨页时会穿过第 2 页，
        // currentPage 随之瞬变 → 白刷两列 + 滑过时整列灰闪。
        assertTrue(
            "文件页（page 2）应传入落定页 == 2 的可见性",
            main().contains("isVisible = pagerState.settledPage == 2")
        )
    }

    @Test
    fun `回前台刷新必须防注册即补发`() {
        val p = page()
        assertTrue("须订阅生命周期", p.contains("DisposableEffect(pageLifecycleOwner)"))
        assertTrue("必须先记下真的 PAUSE 过", p.contains("Lifecycle.Event.ON_PAUSE -> sawPause = true"))
        assertTrue("回到前台须过闸", p.contains("Lifecycle.Event.ON_RESUME -> if (sawPause) {"))
        assertTrue("可见性须跟随最新值", p.contains("val visibleNow = rememberUpdatedState(isVisible)"))
        assertTrue("只在文件页可见时刷", p.contains("if (visibleNow.value) refreshBothPanes()"))
    }

    @Test
    fun `冲突策略移动必须刷两列`() {
        // 落点由 picker 任选，可能就是另一列的当前目录
        assertTrue(
            "目标列也要重列",
            funSlice("fun runMoveWithConflict(", 2000).contains("refreshBothPanes()")
        )
    }

    @Test
    fun `picker 选目录移动必须刷两列`() {
        val p = page()
        val pickerAt = p.indexOf("onDirectorySelected = { destinationDirectory ->")
        assertTrue("应能找到移动目标选择", pickerAt > 0)
        val conflictAt = p.indexOf("if (showConflictDialog)", pickerAt)
        val body = p.substring(pickerAt, if (conflictAt > pickerAt) conflictAt else p.length)
        assertTrue(
            "目标目录可能正是另一列当前目录：只刷聚焦列会让它停在旧列表",
            body.contains("refreshBothPanes()")
        )
    }

    @Test
    fun `两列同路径时镜像刷新`() {
        val body = funSlice("fun refresh(showToast: Boolean = false)", 1200)
        assertTrue(
            "两列停在同一路径时，聚焦列的增删改同样改变了另一列的条目",
            body.contains("normalizeSdCardAlias(panes[otherIndex].currentDirectory)")
        )
        assertTrue(
            "另一列刷新不得带 showToast（否则弹两条「已刷新」）",
            body.contains("refreshPane(otherIndex)")
        )
        assertTrue(
            "聚焦列仍要立即生效（手动刷新不得被节流挡住）",
            body.contains("refreshPane(focusedIndex, showToast)")
        )
    }
}
