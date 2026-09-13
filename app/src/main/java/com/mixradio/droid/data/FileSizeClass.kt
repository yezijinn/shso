// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

/**
 * 按文件体积分派编辑/浏览策略（对齐 MP-Manager 项目解析文档：
 * 「大体积文档编辑功能Kotlin移植可行性分析」§6.2 / 「Kotlin高性能文本编辑器实现指南」§6.2·§9.1）。
 *
 * 四档阈值（与 MP-Manager 完全一致）：
 *  - SMALL  < 5MB       → 全量读入，正常编辑（对应 shso 非大文件直载路径）
 *  - MEDIUM 5–50MB      → 全量读入 + 谨慎编辑（shso 大文件仍走稀疏行索引只读优先，编辑需确认）
 *  - LARGE  50–200MB    → 分块加载 / 只读预览（shso 稀疏行索引 + 虚拟滚动）
 *  - HUGE   > 200MB     → 只读预览，禁止文本编辑（shso 稀疏行索引只读；编辑入口在 enterEditMode 已被
 *                         ChunkedFileReader.MAX_LOAD_BYTES 拦截，这里仅用于 UI 显式标注策略）
 *
 * 注：shso 另有自身的「双渲染通道」铁律（≤6.4 万字符走 Compose BasicTextField，>6.4 万走原生 EditText）
 * 与「安全三层」（静态审查 + 运行时守卫 + 审计），与本文档的纯体积策略正交，互不影响。
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
