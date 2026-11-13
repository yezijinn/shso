// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「提取 APK」搜索过滤：纯函数，JVM 直接验，不依赖 PackageManager。 */
class AppSearchFilterTest {

    private val apps = listOf(
        InstalledAppInfo("com.mixradio.droid", "shso", 20261114L, "/data/app/a/base.apk", emptyList()),
        InstalledAppInfo("com.android.settings", "Settings", 30L, "/system/priv-app/S/S.apk", emptyList()),
        InstalledAppInfo("com.tencent.mm", "微信", 2600L, "/data/app/mm/base.apk", listOf("/data/app/mm/split_1.apk"))
    )

    @Test
    fun `空串与纯空白还原全部`() {
        assertSame("空串必须原样返回同一列表实例", apps, filterInstalledApps(apps, ""))
        assertEquals(3, filterInstalledApps(apps, "   ").size)
    }

    @Test
    fun `按包名与名称大小写不敏感匹配`() {
        assertEquals(listOf("com.mixradio.droid"), filterInstalledApps(apps, "MIXRADIO").map { it.packageName })
        assertEquals(listOf("com.android.settings"), filterInstalledApps(apps, "setti").map { it.packageName })
        assertEquals(listOf("com.tencent.mm"), filterInstalledApps(apps, "微信").map { it.packageName })
        // label 与 packageName 同时命中时不得重复出现
        assertEquals(1, filterInstalledApps(apps, "com.mix").size)
    }

    @Test
    fun `无命中返回空表且不改动入参`() {
        assertTrue(filterInstalledApps(apps, "不存在的东西").isEmpty())
        assertEquals(3, apps.size)
    }
}
