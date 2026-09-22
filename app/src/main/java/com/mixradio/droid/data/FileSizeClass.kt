// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

/**
 * 按文件体积分派编辑/浏览策略。
 *  - SMALL  < 5MB       → 全量读入，正常编辑
 *  - MEDIUM 5–50MB      → 全量读入 + 谨慎编辑
 *  - LARGE  50–200MB    → 分块加载 / 只读预览
 *  - HUGE   > 200MB     → 只读预览，禁止文本编辑
 *
 * 与「双渲染通道」（≤6.4 万字符走 BasicTextField，>6.4 万走 EditText）及「安全三层」正交，互不影响。
 */
enum class FileSizeClass {
    SMALL,   // < 5 MB      —— 全量加载，正常编辑
    MEDIUM,  // 5–50 MB     —— 全量加载，谨慎编辑
    LARGE,   // 50–200 MB   —— 分块加载 / 只读预览
    HUGE;    // > 200 MB    —— 只读预览，禁止文本编辑

    companion object {
        fun of(size: Long): FileSizeClass = when {
            size < 5L * 1024 * 1024 -> SMALL
            size < 50L * 1024 * 1024 -> MEDIUM
            size < 200L * 1024 * 1024 -> LARGE
            else -> HUGE
        }
    }
}
