// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 可安装的 APK 系扩展名（小写；比较前先 lowercase）。 */
val INSTALLABLE_EXTENSIONS = setOf("apk", "xapk", "apks", "aspk", "apkm")

/** 可编辑的常见文本扩展名（小写；比较前先 lowercase）。
 *  来源 docs/2026-09-05_00-43-02.txt：纯文本/代码/前端标记/Shell/系统配置。
 */
val TEXT_EXTENSIONS = setOf(
    // 纯文本
    "txt", "log", "text", "csv", "ini", "cfg", "conf", "properties", "env",
    // 代码
    "py", "java", "kt", "c", "cpp", "h", "hpp", "cc", "cs", "go", "rs",
    "swift", "rb", "php", "js", "ts", "jsx", "tsx",
    // 前端/标记
    "html", "htm", "xml", "css", "scss", "less", "json", "yaml", "yml",
    "toml", "md", "markdown", "rst",
    // Shell/脚本
    "sh", "bash", "zsh", "bat", "cmd", "ps1", "sql", "lua", "pl", "r",
    // 系统/配置
    "rc", "gradle", "cmake", "mk", "makefile",
    // 已有保留
    "tsv", "kts", "smali", "gitignore"
)

/** 可浏览的常见图片扩展名（小写；比较前先 lowercase）。 */
val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp", "ico", "tiff", "tif")

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val permissions: String = ""
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    /** 剥除「.数字」尾缀后的真实扩展名（兼容腾讯产品下载后追加 .1 的情况，如 qq.apk.1）。 */
    val realExtension: String
        get() {
            var base = name
            // 仅当以 ".<数字>" 结尾（如 .apk.1）时剥离一次，避免把 "file1" 误判
            val suffixMatch = Regex("\\.\\d+$").find(base)
            if (suffixMatch != null) {
                base = base.substring(0, suffixMatch.range.first)
            }
            return if (isDirectory) "" else base.substringAfterLast('.', "").lowercase()
        }

    val isExecutableScript: Boolean
        get() = !isDirectory && extension == "sh"

    val isExecutableBinary: Boolean
        get() = !isDirectory && extension == "so"

    val isSupportedExecutable: Boolean
        get() = isExecutableScript || isExecutableBinary

    /** 是否可安装的 APK 系文件（apk/xapk/apks/aspk/apkm），大小写不敏感，兼容 .1 尾缀。 */
    val isInstallable: Boolean
        get() = !isDirectory && realExtension in INSTALLABLE_EXTENSIONS

    /** 是否常见文本文档（可编辑保存）。 */
    val isEditableText: Boolean
        get() = !isDirectory && realExtension in TEXT_EXTENSIONS

    /** 是否常见图片（可浏览）。 */
    val isViewableImage: Boolean
        get() = !isDirectory && realExtension in IMAGE_EXTENSIONS

    /** 是否为已知压缩包（zip/tar/tgz/7z/gz/xz/bz2/zst/lz4 等）——长按菜单据此显示「自动解压文件」。 */
    val isArchive: Boolean
        get() = !isDirectory && ArchiveExtractor.isKnownArchive(name)

    /** 是否实际可解压（rar 仅识别，暂不支持解压）。 */
    val isExtractableArchive: Boolean
        get() = !isDirectory && ArchiveExtractor.isExtractable(name)

    val formattedSize: String
        get() {
            if (isDirectory) return "目录"
            val kb = size / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.getDefault(), "%.2f MB", mb)
                kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
                else -> "$size B"
            }
        }

    val formattedDate: String
        get() {
            if (lastModified <= 0) return ""
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(lastModified))
        }
}
