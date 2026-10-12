// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import android.os.Environment

/**
 * 路径显示层替换：仅影响 UI 展示，实际路径字符串不变。
 *
 * 约定：当前用户的共享存储根统一显示为「内部存储」。
 * 例（主用户）：`/storage/emulated/0/Android/data/com1` → `内部存储/Android/data/com1`。
 */
const val INTERNAL_STORAGE_LABEL = "内部存储"

/** 主用户（userId 0）的共享存储根；运行时推导不可用时的兜底。 */
const val PRIMARY_STORAGE_PATH = "/storage/emulated/0"

/** 系统按 userId 挂载共享存储：主用户是 `/storage/emulated/0`，次用户 / 工作资料是 `/storage/emulated/10` 等。 */
private val SHARED_STORAGE_ROOT = Regex("^/storage/emulated/[0-9]+$")

/**
 * 当前进程所属用户的共享存储根。
 *
 * 写死 user 0 会让次用户 / 工作资料下的提取落点、收件箱、文件页起点与「内部存储」显示
 * 整体指向**别的用户**的存储。取值失败或形状不符（不是 `/storage/emulated/<数字>`，
 * 例如个别 ROM 返回软链路径）时回落到 [PRIMARY_STORAGE_PATH]：主用户与 JVM 单测
 * （`Environment` 未桩）下即该字面量，行为与写死时完全一致。
 */
@Suppress("DEPRECATION") // 共享存储根没有替代的非废弃入口
val INTERNAL_STORAGE_PATH: String = runCatching {
    Environment.getExternalStorageDirectory()?.absolutePath
}.getOrNull()?.takeIf { SHARED_STORAGE_ROOT.matches(it) } ?: PRIMARY_STORAGE_PATH

/** 把当前用户共享存储根前缀替换为「内部存储」，其余路径原样返回。 */
fun displayPath(path: String): String =
    if (path == INTERNAL_STORAGE_PATH || path.startsWith("$INTERNAL_STORAGE_PATH/")) {
        INTERNAL_STORAGE_LABEL + path.removePrefix(INTERNAL_STORAGE_PATH)
    } else {
        path
    }
