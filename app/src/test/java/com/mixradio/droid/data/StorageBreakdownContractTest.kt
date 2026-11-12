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
        val end = s.indexOf("private fun extractedApkBytes(", start)
        val body = s.substring(start, if (end > start) end else s.length)
        assertFalse(
            "oat 目录不存在时不得提前 return，否则 dalvik-cache 产物被漏掉",
            body.contains("if (c != 0 && !ok) return label to null")
        )
        assertTrue("仍须统计 dalvik-cache 本包条目", body.contains("/data/dalvik-cache"))
    }
}
