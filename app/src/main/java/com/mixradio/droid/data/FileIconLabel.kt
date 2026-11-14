// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.util.Locale

/**
 * 行首扩展名文本最多保留几个字符。
 *
 * 双列下每列约 205dp，图标列与文件名列共享这点宽度。11sp 等宽字符约 6.6dp，
 * 5 字符约 33dp（与原先 16sp 的 📄 占宽相当），能容纳 WEBM / FLAC / DOCX / PPTX。
 */
const val EXTENSION_ICON_MAX_CHARS = 5

/** 字体文件：TTF/OTF 有专门图标（文件页与断言共用同一判据）。 */
fun isFontFileItem(item: FileItem): Boolean =
    !item.isDirectory &&
        (item.name.endsWith(".ttf", ignoreCase = true) ||
            item.name.endsWith(".otf", ignoreCase = true))

/**
 * 行首图标的**扩展名文本**。
 *
 * 返回 null 表示「该类型有专门图标，调用方不要用扩展名」；
 * 返回字符串表示「用这段文本当图标」。纯函数，无 Android / Compose 依赖，可 JVM 单测。
 *
 * 已知类型一律不回退：目录、脚本(SH)、二进制(SO)、安装包(APK)、字体(OTF/TTF)、
 * 图片(IMG)、文本(语言标签/TXT) 都保持既有文案 —— 否则会出现「zip 显示 ZIP、kt 也显示 KT」
 * 这种把已经能认出来的类型再降级一遍的结果。
 *
 * **不看 [FileItem.isArchive]**：压缩包没有专门图标，正是最需要扩展名的一类
 * （ZIP / 7Z / TAR / GZ 一看就懂，📄 则完全认不出）。
 */
fun fileExtensionLabel(item: FileItem, maxChars: Int = EXTENSION_ICON_MAX_CHARS): String? {
    if (item.isDirectory) return null
    if (item.isExecutableScript || item.isExecutableBinary) return null
    if (item.isInstallable) return null
    if (isFontFileItem(item)) return null
    if (item.isViewableImage) return null
    if (item.isEditableText) return null
    return extensionIconText(item.realExtension, maxChars)
}

/**
 * 扩展名 → 图标文本（大写、只保留字母数字、超长直接截断）。
 *
 * 不截断成 `ABC…`：省略号本身要占一格，窄列下反而少显示一个字符。
 * 无扩展名（空串）返回 null，由调用方降级为 `?`。
 */
fun extensionIconText(extension: String, maxChars: Int = EXTENSION_ICON_MAX_CHARS): String? {
    if (maxChars <= 0) return null
    val cleaned = extension.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
    if (cleaned.isEmpty()) return null
    return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars)
}
