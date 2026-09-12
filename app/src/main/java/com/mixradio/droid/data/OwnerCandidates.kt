// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「所有者 / 用户组」候选账户。
 *
 * chown 接受**账户名**或**数字 uid**：
 * - 系统账户（root / system / radio …）存在于 `/system/etc/passwd`，可直接用名字；
 * - 应用账户不上 passwd，只有 uid 有效，故应用条目一律回填**数字 uid**，
 *   列表里同时展示 Android 约定账户名（`u<userId>_a<appId-10000>`）便于识别。
 */
object OwnerCandidates {

    /**
     * @param name  chown 可用的实参：系统账户为账户名，应用为数字 uid
     * @param uid   数字 uid，用于列表展示与排序
     * @param label 展示标签：系统账户为「系统」，应用为应用名
     * @param isApp 是否来自已安装应用（决定回填 name 还是 uid）
     */
    data class Entry(
        val name: String,
        val uid: Int,
        val label: String,
        val isApp: Boolean,
    ) {
        /** 列表展示的账户名：应用显示 `u0_a216`，系统账户显示自身名字。 */
        val accountName: String get() = if (isApp) accountNameOf(uid) else name
    }

    /**
     * 常用系统账户。Android 的 uid 分配是 AOSP 固定契约，故直接内置，
     * 不必解析 `/system/etc/passwd`（该文件在不同 ROM 上内容不一致，且应用账户不在其中）。
     */
    private val SYSTEM_ACCOUNTS = listOf(
        Entry("root", 0, "系统", false),
        Entry("daemon", 1, "系统", false),
        Entry("bin", 2, "系统", false),
        Entry("system", 1000, "系统", false),
        Entry("radio", 1001, "系统", false),
        Entry("bluetooth", 1002, "系统", false),
        Entry("graphics", 1003, "系统", false),
        Entry("input", 1004, "系统", false),
        Entry("audio", 1005, "系统", false),
        Entry("camera", 1006, "系统", false),
        Entry("log", 1007, "系统", false),
        Entry("wifi", 1010, "系统", false),
        Entry("adb", 1011, "系统", false),
        Entry("install", 1012, "系统", false),
        Entry("media", 1013, "系统", false),
        Entry("sdcard_rw", 1015, "系统", false),
        Entry("vpn", 1016, "系统", false),
        Entry("keystore", 1017, "系统", false),
        Entry("usb", 1018, "系统", false),
        Entry("media_rw", 1023, "系统", false),
        Entry("mtp", 1024, "系统", false),
        Entry("nfc", 1027, "系统", false),
        Entry("sdcard_r", 1028, "系统", false),
        Entry("shell", 2000, "系统", false),
        Entry("cache", 2001, "系统", false),
        // 网络/外设类组：用户组常用于授权访问（如让应用组获得 inet 联网权限）
        Entry("net_bt_admin", 3001, "系统", false),
        Entry("net_bt", 3002, "系统", false),
        Entry("inet", 3003, "系统", false),
        Entry("net_raw", 3004, "系统", false),
        Entry("net_admin", 3005, "系统", false),
    )

    /** Android uid 约定：uid = userId * 100000 + appId，账户名 = `u<userId>_a<appId-10000>`。 */
    fun accountNameOf(uid: Int): String {
        val userId = uid / 100_000
        val appId = uid % 100_000
        return if (appId >= 10_000) "u${userId}_a${appId - 10_000}" else "u${userId}_a$appId"
    }

    /**
     * 追加当前文件属主：文件常属于某个系统账户（如 system、media_rw）而非常见项，
     * 缺失时用户无法在列表中找到当前值，会造成「列表里没有我现在这个所有者」的困惑。
     */
    private fun withCurrent(list: List<Entry>, current: String): List<Entry> {
        val value = current.trim()
        if (value.isEmpty()) return list
        val exists = list.any { it.name == value || it.uid.toString() == value }
        if (exists) return list
        val uid = value.toIntOrNull() ?: return list
        return list + Entry(value, uid, "当前所有者", false)
    }

    /**
     * 加载候选列表：系统账户 + 已安装应用（按 uid 去重、升序），并补入 [current]。
     * PackageManager 查询较重，必须在 IO 线程调用。
     */
    suspend fun load(context: Context, current: String = ""): List<Entry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .map { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName)
                    Entry(info.uid.toString(), info.uid, label, true)
                }
                .distinctBy { it.uid }
                .sortedBy { it.uid }
                .toList()
        }.getOrDefault(emptyList())

        withCurrent(SYSTEM_ACCOUNTS, current) + apps
    }
}
