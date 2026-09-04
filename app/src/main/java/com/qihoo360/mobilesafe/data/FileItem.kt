// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val isExecutableScript: Boolean
        get() = !isDirectory && extension == "sh"

    val isExecutableBinary: Boolean
        get() = !isDirectory && extension == "so"

    val isSupportedExecutable: Boolean
        get() = isExecutableScript || isExecutableBinary

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
