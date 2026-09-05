// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

/**
 * 路径显示层替换：仅影响 UI 展示，实际路径字符串不变。
 *
 * 约定（用户定制 2026-09-04）：`/storage/emulated/0` 统一显示为「内部存储」。
 * 例：`/storage/emulated/0/Android/data/com1` → `内部存储/Android/data/com1`。
 */
const val INTERNAL_STORAGE_PATH = "/storage/emulated/0"
const val INTERNAL_STORAGE_LABEL = "内部存储"

/** 把路径前缀 `/storage/emulated/0` 替换为「内部存储」，其余路径原样返回。 */
fun displayPath(path: String): String =
    if (path == INTERNAL_STORAGE_PATH || path.startsWith("$INTERNAL_STORAGE_PATH/")) {
        INTERNAL_STORAGE_LABEL + path.removePrefix(INTERNAL_STORAGE_PATH)
    } else {
        path
    }
