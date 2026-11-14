// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.util.Locale

/**
 * 常见视频容器扩展名（小写）。
 *
 * 刻意**不含** `ts` / `mts`：两者都在 [TEXT_EXTENSIONS] 里（TypeScript / 模板），
 * 收进媒体集合会让 `.ts` 源码同时长出「播放视频」与「编辑文本」两个入口，
 * 把最常用的文本编辑挤到次要位置。MPEG-TS 仍可经动作菜单「打开方式」交给系统播放器。
 *
 * 「在集合里」只表示**可以尝试播放**，是否真能解码取决于设备编解码器。
 */
val VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2",
    "mpg", "mpeg", "m2ts", "flv", "wmv", "ogv", "rmvb", "rm",
    "asf", "vob", "f4v", "mxf", "divx"
)

/** 常见音频扩展名（小写）。与 [TEXT_EXTENSIONS] / [IMAGE_EXTENSIONS] / [INSTALLABLE_EXTENSIONS] 无交集。 */
val AUDIO_EXTENSIONS = setOf(
    "mp3", "wav", "aac", "flac", "ogg", "oga", "opus", "m4a", "m4b", "m4r",
    "mka", "wma", "amr", "ape", "aiff", "aif", "mid", "midi",
    "ac3", "dts", "ra", "wv", "caf", "dsf", "dff"
)

/** 视频 ∪ 音频。 */
val MEDIA_EXTENSIONS: Set<String> = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS

/** 媒体类型。 */
enum class MediaKind { NONE, VIDEO, AUDIO }

/** 扩展名 → 媒体类型（大小写不敏感；空串与非媒体一律 NONE）。 */
fun mediaKindOfExtension(extension: String): MediaKind {
    val ext = extension.lowercase(Locale.ROOT)
    return when {
        ext in VIDEO_EXTENSIONS -> MediaKind.VIDEO
        ext in AUDIO_EXTENSIONS -> MediaKind.AUDIO
        else -> MediaKind.NONE
    }
}

/** 文件名 → 媒体类型。内部先剥「.数字」尾缀，与 [FileItem.realExtension] 完全同源。 */
fun mediaKindOf(fileName: String): MediaKind = mediaKindOfExtension(realExtensionOf(fileName))

/**
 * 毫秒 → `mm:ss`（≥1 小时为 `h:mm:ss`）。
 *
 * 负数 / 0 一律 `00:00`：MediaPlayer 在 prepare 完成前 duration 返回 -1，
 * 直接格式化会显示 `-1:-1`。
 */
fun formatMediaTime(millis: Int): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
