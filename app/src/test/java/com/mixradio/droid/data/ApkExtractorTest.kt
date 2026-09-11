// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // ── 产物规划（单包 / 分包）────────────────────────────────────────────
    // 本机实测无任何分包应用（全量扫描为空），故分包分支靠这里的纯函数覆盖。

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
}
