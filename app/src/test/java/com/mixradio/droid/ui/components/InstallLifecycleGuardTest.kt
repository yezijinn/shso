// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.ui.components

import org.junit.Assert.assertEquals
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
    private val apkInstaller = File("src/main/java/com/mixradio/droid/data/ApkInstaller.kt")

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

    @Test fun `ROOT 探测结果到达后必须同步安装方式显示`() {
        val s = dialog.readText()
        // FilePage 在 pendingInstallItem 变化后才异步 probe ROOT，弹窗很可能在结果回来前
        // 就完成首次组合，把「尚未探测」的旧值（首次进入应用恒为 false）锁死。那会让
        // 「所见」与「所装」不一致：显示系统安装器却静默装，或把 ROOT 环境判成无 ROOT，
        // 使分包安装必然失败。必须有一个 key 含 willInstallAsRoot 的 effect 同步锁定值。
        assertTrue(
            "锁定值必须随 willInstallAsRoot 变化同步",
            s.contains("LaunchedEffect(willInstallAsRoot, show, fileItem?.path)")
        )
        assertTrue(
            "同步必须在交出所有权之前生效",
            s.contains("if (show && fileItem != null && !stagedConsumed)")
        )
    }

    @Test fun `暂存副本不得在系统安装器拉起后立即删除`() {
        val s = filePage.readText()
        // 系统安装器经 content:// URI **异步**读取副本，startActivity 返回只代表安装器
        // 已被拉起；此刻删除会让它读到不存在的文件（「解析软件包出现问题」，无 ROOT 时
        // 安装几乎必失败）。只有 ROOT 静默安装在返回前已把字节读尽，可以立即删。
        val all = Regex(Regex.escape("stagedPath?.let { File(it).delete() }")).findAll(s).count()
        val gated = Regex(
            Regex.escape("if (installAsRoot) stagedPath?.let { File(it).delete() }")
        ).findAll(s).count()
        assertTrue("必须存在暂存副本清理", all >= 1)
        assertEquals("每一处清理都必须限定在 ROOT 静默安装分支", all, gated)
    }

    @Test fun `分包暂存途中失败必须清掉已暂存的兄弟分片`() {
        val s = apkInstaller.readText()
        // 「无法校验分包」的提前返回绕过了下方的统一清理，不清会在
        // cache/install-confirm 里留下一整份套件。
        val failAt = s.indexOf("无法校验分包")
        val cleanAt = s.lastIndexOf("stagedHere.forEach { runCatching { File(it).delete() } }", failAt)
        assertTrue("暂存失败的提前返回前必须先清理已暂存副本", failAt > 0 && cleanAt in 0 until failAt)
    }
}
