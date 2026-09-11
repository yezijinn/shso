// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「安装套件」识别的纯函数部分（命名约定）。
 *
 * 背景：单文件 `pm install` 对分包应用必定失败（INSTALL_FAILED_MISSING_SPLIT），
 * 分包必须走会话安装；这里保证「同目录的 base + `-splitN` 能被正确聚成一套」，
 * 且点基础包或点任意分包得到的结果一致。
 */
class ApkInstallerSetTest {

    @Test
    fun `套件前缀剥离分包后缀`() {
        assertEquals("X-123", ApkInstaller.apkSetStem("X-123.APK"))
        assertEquals("X-123", ApkInstaller.apkSetStem("X-123-split2.APK"))
        assertEquals("WhatsApp-263507522", ApkInstaller.apkSetStem("WhatsApp-263507522-split1.APK"))
        // 非 apk 后缀不参与
        assertNull(ApkInstaller.apkSetStem("X-123.txt"))
        assertNull(ApkInstaller.apkSetStem("X-123"))
    }

    @Test
    fun `分包名判定`() {
        assertTrue(ApkInstaller.isSplitName("X-123-split1.APK"))
        assertTrue(ApkInstaller.isSplitName("X-123-split12.apk"))
        assertFalse(ApkInstaller.isSplitName("X-123.APK"))
        assertFalse(ApkInstaller.isSplitName("opopp-80721.APK"))
    }

    @Test
    fun `点基础包聚出整套`() {
        val r = ApkInstaller.nameBasedSet(
            tappedName = "WhatsApp-263507522.APK",
            siblingNames = listOf(
                "OPPO 社区-80721.APK",
                "WhatsApp-263507522.APK",
                "WhatsApp-263507522-split1.APK",
                "WhatsApp-263507522-split3.APK",
                "WhatsApp-263507522-split2.APK"
            )
        )
        assertEquals(
            "WhatsApp-263507522.APK" to listOf(
                "WhatsApp-263507522-split1.APK",
                "WhatsApp-263507522-split2.APK",
                "WhatsApp-263507522-split3.APK"
            ),
            r
        )
    }

    @Test
    fun `点任意分包同样聚出整套且基础包唯一`() {
        val siblings = listOf(
            "WhatsApp-263507522.APK",
            "WhatsApp-263507522-split1.APK",
            "WhatsApp-263507522-split2.APK"
        )
        val expected = "WhatsApp-263507522.APK" to
            listOf("WhatsApp-263507522-split1.APK", "WhatsApp-263507522-split2.APK")
        assertEquals(expected, ApkInstaller.nameBasedSet("WhatsApp-263507522-split2.APK", siblings))
        assertEquals(expected, ApkInstaller.nameBasedSet("WhatsApp-263507522-split1.APK", siblings))
    }

    @Test
    fun `没有分包时不构成套件`() {
        assertNull(
            ApkInstaller.nameBasedSet(
                "OPPO 社区-80721.APK",
                listOf("OPPO 社区-80721.APK", "Chrome-1.APK")
            )
        )
        // 只有分包、缺基础包时也不能猜
        assertNull(
            ApkInstaller.nameBasedSet(
                "X-1-split1.APK",
                listOf("X-1-split1.APK", "X-1-split2.APK")
            )
        )
    }

    @Test
    fun `前缀相同但非本套件的文件被排除`() {
        val r = ApkInstaller.nameBasedSet(
            tappedName = "App-7.APK",
            siblingNames = listOf(
                "App-7.APK",
                "App-7-copy.APK",        // 前缀不同（App-7-copy）
                "App-70.APK",            // 前缀不同（App-70）
                "App-7-split1.APK"
            )
        )
        assertEquals("App-7.APK" to listOf("App-7-split1.APK"), r)
    }

    @Test
    fun `应用名本身含 split 字样不会误判`() {
        // 应用名恰为 "Foo-split2"、版本 1：基础包是 Foo-split2-1.APK（不含 -split<数字> 结尾）
        assertFalse(ApkInstaller.isSplitName("Foo-split2-1.APK"))
        assertEquals("Foo-split2-1", ApkInstaller.apkSetStem("Foo-split2-1-split1.APK"))
        val r = ApkInstaller.nameBasedSet(
            "Foo-split2-1.APK",
            listOf("Foo-split2-1.APK", "Foo-split2-1-split1.APK")
        )
        assertEquals("Foo-split2-1.APK" to listOf("Foo-split2-1-split1.APK"), r)
    }

    @Test
    fun `ApkSet 的 all 把基础包排在最前`() {
        val set = ApkInstaller.ApkSet("/d/base.APK", listOf("/d/s1.APK", "/d/s2.APK"))
        assertTrue(set.isSplit)
        assertEquals(listOf("/d/base.APK", "/d/s1.APK", "/d/s2.APK"), set.all)
        assertFalse(ApkInstaller.ApkSet("/d/only.APK", emptyList()).isSplit)
    }
}
