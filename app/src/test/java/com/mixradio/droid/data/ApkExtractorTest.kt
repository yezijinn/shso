// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「提取 APK」的命名规则（纯函数部分）。
 * 命名约定由用户指定：**后缀必须是大写 `APK`**，文件名形如 `<应用名>-<versionCode>.APK`。
 */
class ApkExtractorTest {

    @Test
    fun `基础包文件名为 应用名-versionCode 且后缀为大写 APK`() {
        assertEquals("微信-1234.APK", ApkExtractor.baseFileName("微信", 1234))
        assertEquals("Chrome-5000123.APK", ApkExtractor.baseFileName("Chrome", 5000123))
        assertTrue(ApkExtractor.baseFileName("X", 1).endsWith(".APK"))
        assertFalse(ApkExtractor.baseFileName("X", 1).endsWith(".apk"))
    }

    @Test
    fun `分包文件名为 应用名-versionCode-splitN`() {
        assertEquals("Chrome-5000123-split1.APK", ApkExtractor.splitFileName("Chrome", 5000123, 1))
        assertEquals("Chrome-5000123-split12.APK", ApkExtractor.splitFileName("Chrome", 5000123, 12))
    }

    @Test
    fun `文件名净化非法字符与控制字符`() {
        assertEquals("A_B_C", ApkExtractor.sanitizeFileName("A/B:C"))
        assertEquals("a_b_c_d_e_f_g_h", ApkExtractor.sanitizeFileName("a\\b*c?d\"e<f>g|h"))
        assertEquals("x_y", ApkExtractor.sanitizeFileName("x\u0000y"))
        assertEquals("正常名称", ApkExtractor.sanitizeFileName("正常名称"))
    }

    @Test
    fun `文件名首尾的空白与点被清理`() {
        assertEquals("hello", ApkExtractor.sanitizeFileName("  ..hello..  "))
        assertEquals("app", ApkExtractor.sanitizeFileName(""))
        assertEquals("app", ApkExtractor.sanitizeFileName("..."))
        assertEquals("app", ApkExtractor.sanitizeFileName("   "))
    }

    @Test
    fun `提取目录固定为内部存储 Download`() {
        assertEquals("/storage/emulated/0/Download", ApkExtractor.DOWNLOAD_DIR)
    }

    @Test
    fun `失败回滚只清除本次新建的产物`() {
        // 分包应用是 base + 多个 split 的一组，中途失败若把已复制的留在原地，Download 里
        // 就是「装不上的半套」；但同名覆盖刷新时目标本已存在，删掉等于销毁用户上一次
        // 提取的可用产物 —— 回滚必须按「本次是否新建」区分。
        val dir = java.nio.file.Files.createTempDirectory("shso-apk-rollback").toFile()
        try {
            val fresh1 = File(dir, "分包应用-100.APK").apply { writeText("base") }
            val fresh2 = File(dir, "分包应用-100-split1.APK").apply { writeText("split1") }
            val preExisting = File(dir, "分包应用-100-split2.APK").apply { writeText("旧产物") }

            val removed = ApkExtractor.rollbackNewlyCreated(
                listOf(fresh1.absolutePath, fresh2.absolutePath)
            )

            assertEquals("本次新建的两个文件都应清除", 2, removed)
            assertFalse(fresh1.exists())
            assertFalse(fresh2.exists())
            assertTrue("先前已有的产物不得被删", preExisting.exists())
            assertEquals("旧产物", preExisting.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `提取失败必须回滚且逐文件先写临时名再改名`() {
        // 两条路径各自出过问题：失败分支直接 return（已复制的留成半套）；直接写目标名时
        // cp / 流拷贝中途失败会留下 size>0 的半截 APK。断言源码里这两处防线都在。
        val s = File("src/main/java/com/mixradio/droid/data/ApkExtractor.kt").readText()
        val failBranch = s.substringAfter("if (!one.ok) {").substringBefore("return@withContext Result(false")
        assertTrue("失败分支必须回滚本次新建的目标", failBranch.contains("rollbackNewlyCreated(created)"))
        // 失败时当前目标也可能已落盘（shell 侧 cp+mv 都成功、随后整条命令超时或被杀），
        // 而它按定义不在 created 里 —— 必须再按「循环开始前是否存在」清一次。
        assertTrue(
            "失败时循环开始前不存在的目标也须清理",
            failBranch.contains("if (existed) 0 else rollbackNewlyCreated(listOf(dst.absolutePath))")
        )
        assertTrue("回滚只针对本次新建", s.contains("if (!existed) created += dst.absolutePath"))
        assertTrue("拷贝必须先写同目录临时名", s.contains("\"\${dst.name}.part-\${System.nanoTime()}\""))
        assertTrue("成功后改名到目标名", s.contains("mv -f \$escapedTmp \$escapedDst") || s.contains("tmp.renameTo(dst)"))
        // 目标被同名目录占住时 mv 会把文件塞进该目录内并返回成功（test -s <目录> 恒真），
        // 于是界面报「已提取」而产物落在嵌套路径 —— 必须在拷贝前拦下。
        assertTrue("目标为目录时须提前失败", s.contains("if (dst.isDirectory) {"))
        assertTrue("目录占用必须报错而非静默成功", s.contains("目标路径已是目录，未提取"))
    }

    // 产物规划（单包 / 分包）
    // 本机扫描未遇到任何分包应用，故分包分支靠纯函数单测覆盖。

    @Test
    fun `单包应用只产出一个基础包文件`() {
        val app = InstalledAppInfo(
            packageName = "com.example.single",
            label = "示例应用",
            versionCode = 42,
            baseApk = "/data/app/~~x/com.example.single-y/base.apk",
            splitApks = emptyList()
        )
        assertFalse(app.isSplit)
        assertEquals(
            listOf("示例应用-42.APK" to app.baseApk),
            ApkExtractor.plannedTargets(app)
        )
    }

    @Test
    fun `分包应用产出基础包加全部分包且序号从 1 起`() {
        val app = InstalledAppInfo(
            packageName = "com.example.split",
            label = "分包应用",
            versionCode = 100,
            baseApk = "/data/app/~~x/com.example.split-y/base.apk",
            splitApks = listOf(
                "/data/app/~~x/com.example.split-y/split_config.arm64_v8a.apk",
                "/data/app/~~x/com.example.split-y/split_config.xxhdpi.apk"
            )
        )
        assertTrue(app.isSplit)
        assertEquals(
            listOf(
                "分包应用-100.APK" to app.baseApk,
                "分包应用-100-split1.APK" to app.splitApks[0],
                "分包应用-100-split2.APK" to app.splitApks[1]
            ),
            ApkExtractor.plannedTargets(app)
        )
        // 所有产出文件都必须是大写 .APK 后缀
        assertTrue(ApkExtractor.plannedTargets(app).all { it.first.endsWith(".APK") })
    }

    @Test
    fun `同名冲突后缀拼进标签段使 base 与 split 的套件 stem 保持相等`() {
        val app = InstalledAppInfo(
            packageName = "com.example.dual",
            label = "示例应用",
            versionCode = 42,
            baseApk = "/data/app/~~x/com.example.dual-y/base.apk",
            splitApks = listOf("/data/app/~~x/com.example.dual-y/split_config.arm64_v8a.apk")
        )
        // 后缀为空：常规覆盖刷新，文件名与既有契约一致。
        assertEquals(
            listOf(
                "示例应用-42.APK" to app.baseApk,
                "示例应用-42-split1.APK" to app.splitApks[0]
            ),
            ApkExtractor.plannedTargets(app)
        )
        // 异包同名：后缀拼进标签段，base 与 split 共享同一 stem（末尾追加会破坏配对）。
        val planned = ApkExtractor.plannedTargets(app, "-com.example.dual")
        assertEquals("示例应用-com.example.dual-42.APK", planned[0].first)
        assertEquals("示例应用-com.example.dual-42-split1.APK", planned[1].first)
        assertEquals(
            "base 与 split 的套件 stem 必须相等",
            ApkInstaller.apkSetStem(planned[0].first),
            ApkInstaller.apkSetStem(planned[1].first)
        )
        assertTrue("带后缀的 split 仍须被识别为分包", ApkInstaller.isSplitName(planned[1].first))
        assertFalse("带后缀的 base 不得被识别为分包", ApkInstaller.isSplitName(planned[0].first))
    }
}
