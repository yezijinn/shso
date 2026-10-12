// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
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
    fun `构建配置不得残留无关模板项`() {
        // 工程为纯 Android：settings.gradle.kts 仅 include(":app")，app 插件仅 androidApplication +
        // composeCompiler。gradle.properties 若留着 KMP / iOS / Native / Dokka 模板项，会让读者
        // 误以为是多平台工程或用了已移除的插件，升级 AGP / Kotlin 时也容易被无效属性误导。
        val text = File("../gradle.properties").readText()
        listOf(
            "xcodeproj", "kotlin.mpp.", "kotlin.native.",
            "kotlin.incremental.multiplatform", "kotlin.incremental.js",
            "kotlin.incremental.native", "kotlin.incremental.wasm",
            "compose.experimental.macos", "xcodeCompatibility",
            "org.jetbrains.dokka"
        ).forEach { key ->
            assertFalse("纯 Android 工程不应残留多平台配置：$key", text.contains(key))
        }
    }

    @Test
    fun `版本目录不得残留未引用声明`() {
        // 版本目录里留着没人引用的声明（尤其 jetbrains-compose-*）会让人误以为工程用
        // KMP 版 Compose，升级依赖时也要逐个判断死活。每条声明都必须能在构建脚本中找到引用。
        val catalog = File("../gradle/libs.versions.toml").readText()
        val gradleText = listOf(
            "../build.gradle.kts", "../settings.gradle.kts", "../app/build.gradle.kts"
        ).joinToString("\n") { File(it).readText() }
        // libs.<dotted> 访问器：库声明 androidx-compose-bom 对应 libs.androidx.compose.bom
        val accessors = Regex("libs\\.([A-Za-z0-9_.]+)")
            .findAll(gradleText).map { it.groupValues[1] }.toSet()

        catalogSectionKeys(catalog, "libraries").forEach { key ->
            assertTrue(
                "版本目录声明未被任何构建脚本引用：$key",
                key.replace('-', '.') in accessors
            )
        }
        catalogSectionKeys(catalog, "plugins").forEach { key ->
            assertTrue(
                "版本目录插件未被任何构建脚本引用：$key",
                "plugins.$key" in accessors
            )
        }
        // [versions] 允许被其他声明用 version.ref 引用，不允许无人引用
        val refs = Regex("version\\.ref\\s*=\\s*\"([^\"]+)\"")
            .findAll(catalog).map { it.groupValues[1] }.toSet()
        catalogSectionKeys(catalog, "versions").forEach { key ->
            assertTrue("版本目录 version 无人引用：$key", key in refs)
        }
    }

    @Test
    fun `文档中的 compose-bom 版本必须与版本目录一致`() {
        // compose-bom 升到 2026.09.00 时改了 PROJECT.md 与更新日志，CONTRIBUTING 中英两份漏改。
        val catalog = File("../gradle/libs.versions.toml").readText()
        val version = Regex("^androidx-compose-bom\\s*=\\s*\"([^\"]+)\"", RegexOption.MULTILINE)
            .find(catalog)?.groupValues?.get(1)
        assertTrue("版本目录必须声明 androidx-compose-bom", version != null)

        listOf("../CONTRIBUTING.md", "../CONTRIBUTING.en.md", "../docs/PROJECT.md").forEach { path ->
            val text = File(path).readText()
            val declared = Regex("compose-bom\\s+([0-9][0-9.]*)")
                .findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("$path 应写明 compose-bom 版本", declared.isNotEmpty())
            declared.forEach { v ->
                assertEquals("$path 的 compose-bom 版本与版本目录不一致", version, v)
            }
        }
    }

    /** 取版本目录某个 section 的键名，跳过注释与空行。 */
    private fun catalogSectionKeys(catalog: String, section: String): List<String> {
        var inside = false
        val keys = mutableListOf<String>()
        catalog.lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) {
                inside = line == "[$section]"
            } else if (inside && line.isNotEmpty() && !line.startsWith("#")) {
                val eq = line.indexOf('=')
                if (eq > 0) keys += line.substring(0, eq).trim()
            }
        }
        return keys
    }

    @Test
    fun `OBB 锁脚本不得按固定字段号解析 procfs stat`() {
        val script = ApkInstaller.buildObbLockAcquireScript("'/x.lock'", "'t|1|2|3'", "'/x.q'")
        assertFalse("不得用 awk 固定字段号取 starttime", script.contains("awk '{print \$22}'"))
        assertFalse("不得用 cut -f5 取 pgrp", script.contains("cut -d' ' -f5"))
        assertTrue("必须先剥离 comm 再取字段", script.contains("sed 's/.*) //'"))
    }
}
