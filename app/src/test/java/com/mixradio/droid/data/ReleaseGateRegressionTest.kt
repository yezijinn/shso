// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 发布链路与 procfs 解析口径的回归护栏。
 *
 * 三条都对应已确认的真实缺口：
 * ① 发布 / 构建工作流只 `assembleRelease`，不跑单测 —— 单测里大量是「防回归删分支」的
 *    源码契约断言，发布路径不跑等于让这些守护失效；
 * ② 安装确认弹窗的暂存副本（`cacheDir` 下 `install-confirm` 目录里的暂存 apk）无启动清扫 ——
 *    确认期间被强杀时删除代码不执行，永久残留；
 * ③ shell 侧按固定字段号解析 `/proc/<pid>/stat` —— comm（进程名）可含空格与右括号，
 *    字段错位会把 state 当 pgrp / 把别的字段当 starttime。
 */
class ReleaseGateRegressionTest {

    @Test
    fun `发布与构建工作流必须先跑单元测试`() {
        listOf(
            "publish-release.yml",
            "build-apk.yml"
        ).forEach { name ->
            val f = File("../.github/workflows/$name")
            assertTrue("找不到工作流 $name（cwd=${File(".").absolutePath}）", f.isFile)
            val text = f.readText()
            val testAt = text.indexOf("testDebugUnitTest")
            val buildAt = text.indexOf("./gradlew assembleRelease")
            assertTrue("$name 必须运行单元测试", testAt > 0)
            assertTrue("$name 必须编译 Release", buildAt > 0)
            assertTrue("$name 单测必须在编译之前，失败即中止发布", testAt < buildAt)
        }
    }

    @Test
    fun `安装暂存副本必须在启动时清扫`() {
        val text = File("src/main/java/com/mixradio/droid/data/RootService.kt").readText()
        val start = text.indexOf("private fun sweepExecutionTempFiles()")
        assertTrue("必须存在启动清扫入口", start > 0)
        val body = text.substring(start, minOf(text.length, start + 2000))
        assertTrue("必须清扫安装确认弹窗的暂存目录 install-confirm", body.contains("install-confirm"))
        // 只能按 mtime 阈值删：并行安装可能仍在读刚写入的副本
        assertTrue("必须按 mtime 阈值删除，避免误删在途副本", body.contains("lastModified()"))
    }

    @Test
    fun `构建配置不得残留多平台模板项`() {
        // 工程为纯 Android：settings.gradle.kts 仅 include(":app")，app 插件仅 androidApplication +
        // composeCompiler。gradle.properties 若留着 KMP / iOS / Native 模板项，会让读者误以为是
        // 多平台工程，升级 AGP / Kotlin 时也容易被无效属性误导。
        val text = File("../gradle.properties").readText()
        listOf(
            "xcodeproj", "kotlin.mpp.", "kotlin.native.",
            "kotlin.incremental.multiplatform", "kotlin.incremental.js",
            "kotlin.incremental.native", "kotlin.incremental.wasm",
            "compose.experimental.macos", "xcodeCompatibility"
        ).forEach { key ->
            assertFalse("纯 Android 工程不应残留多平台配置：$key", text.contains(key))
        }
    }

    @Test
    fun `OBB 锁脚本不得按固定字段号解析 procfs stat`() {
        val script = ApkInstaller.buildObbLockAcquireScript("'/x.lock'", "'t|1|2|3'", "'/x.q'")
        assertFalse("不得用 awk 固定字段号取 starttime", script.contains("awk '{print \$22}'"))
        assertFalse("不得用 cut -f5 取 pgrp", script.contains("cut -d' ' -f5"))
        assertTrue("必须先剥离 comm 再取字段", script.contains("sed 's/.*) //'"))
    }
}
