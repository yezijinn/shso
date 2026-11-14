// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 存储组成测量口径的护栏。 */
class StorageBreakdownContractTest {

    private fun src(): String =
        File("src/main/java/com/mixradio/droid/data/StorageBreakdown.kt").readText()

    @Test
    fun `asset 按 APK 内压缩后体积统计`() {
        val s = src()
        val start = s.indexOf("private fun assetBytes(")
        assertTrue("应能找到 assetBytes", start > 0)
        val body = s.substring(start, start + 500)
        assertTrue("压缩型 asset 必须走 ZipFile compressedSize", body.contains("compressedSize"))
        assertFalse("不得用 available() 代替（返回未压缩大小）", body.contains("available()"))
        assertFalse("不得用 openFd（压缩型 asset 会抛异常）", body.contains("openFd"))
    }

    @Test
    fun `oat 缺失不得阻断 dalvik-cache 统计`() {
        val s = src()
        val start = s.indexOf("private fun oatDirBytes(")
        assertTrue("应能找到 oatDirBytes", start > 0)
        // 锚点必须是 oatDirBytes 之后**必然存在**的函数：原先用 extractedApkBytes，
        // 它随「不统计 Download」被删除，end=-1 会让区间退化成文件剩余全部，
        // 断言仍「通过」但等于没测。
        val end = s.indexOf("private fun assetBytes(", start)
        assertTrue("应能找到 assetBytes 作为区间右界", end > start)
        val body = s.substring(start, end)
        assertFalse(
            "oat 目录不存在时不得提前 return，否则 dalvik-cache 产物被漏掉",
            body.contains("if (c != 0 && !ok) return label to null")
        )
        assertTrue("仍须统计 dalvik-cache 本包条目", body.contains("/data/dalvik-cache"))
    }

    @Test
    fun `运行期统计不得再包含 Download`() {
        val s = src()
        assertFalse("不得再统计收件箱 Download/shso", s.contains("Download/shso"))
        assertFalse("不得再引用提取 APK 的度量函数", s.contains("extractedApkBytes"))
        assertFalse("不得留死代码：提取 APK 的度量函数必须删除", s.contains("private fun extractedApk"))
        assertFalse("不得再引用 Download 口径变量", s.contains("val internal = INTERNAL_STORAGE_PATH"))
    }

    @Test
    fun `env 必须按顶层组件分列而不是一个总数`() {
        val s = src()
        assertTrue("须有组件表", s.contains("ENV_COMPONENTS"))
        listOf("\"bin\"", "\"lib\"", "\"libexec\"", "\"share\"", "\"etc\"").forEach { top ->
            assertTrue("缺组成项 $top", s.contains(top))
        }
        assertFalse(
            "不得再保留「工具链解压目录」单一总数条目（会与组件重复计入合计）",
            s.contains("\"工具链解压目录\"")
        )
        assertTrue(
            "根文件必须走不递归的顶层求和，否则 bin/lib 会被重复计入",
            s.contains("topLevelFileBytes(envRoot)")
        )
    }
}
