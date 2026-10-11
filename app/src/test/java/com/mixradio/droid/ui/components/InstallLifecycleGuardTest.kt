// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安装链路的配置变更回归。
 *
 * `AndroidManifest.xml` 未声明 `configChanges`，旋转/分屏一律重建 Activity：
 * 页面级组合状态会归零、页面级 scope 会被取消。安装（含 ROOT 静默安装）是正在落地的
 * 系统副作用，必须对这两件事各自设防。
 */
class InstallLifecycleGuardTest {

    private val dialog = File("src/main/java/com/mixradio/droid/ui/components/InstallConfirmDialog.kt")
    private val filePage = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt")

    @Test fun `安装确认弹窗必须在组合销毁时清理未交出的暂存副本`() {
        val s = dialog.readText()
        // 旋转会让 stagedPath（普通 remember）归零，旧副本路径随之丢失；
        // 只有 DisposableEffect 的 onDispose 能在组合销毁时把它删掉。
        assertTrue(
            "必须按目标文件挂 DisposableEffect",
            s.contains("DisposableEffect(fileItem?.path)")
        )
        assertTrue(
            "onDispose 必须删除未交出的暂存副本",
            s.contains("if (!stagedConsumed) stagedPath?.let { java.io.File(it).delete() }")
        )
        // 已交出的副本归 startInstall，所有权标记必须跨配置变更存活，否则会被误删。
        assertTrue(
            "所有权标记必须 rememberSaveable",
            s.contains("var stagedConsumed by rememberSaveable")
        )
    }

    @Test fun `安装中状态与执行作用域必须跨配置变更`() {
        val s = filePage.readText()
        // 页面级 remember 在旋转后归零 → 安装入口重新可点，而 pm install 可能仍在进行。
        assertTrue(
            "安装中标志必须是文件级进程状态",
            s.contains("private var installInFlight by mutableStateOf(false)")
        )
        assertFalse(
            "不得再用页面级 remember 持有安装中标志",
            s.contains("var isInstalling by remember")
        )
        // 页面级 scope 被取消时，CancellationException 会跳过尾部的暂存副本清理。
        assertTrue(
            "安装必须跑在不随组合销毁的作用域上",
            s.contains("installJobScope.launch")
        )
        assertTrue(
            "安装作用域必须是文件级进程作用域",
            Regex("private val installJobScope = CoroutineScope\\(SupervisorJob\\(\\)").containsMatchIn(s)
        )
    }
}
