// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史条目的时间显示。
 *
 * 纯函数（不依赖 Android），便于单测。近 7 天用相对时间（扫一眼就知道新旧），
 * 更早则给绝对时间（相对时间过了「几天前」就没有信息量了）。
 */
object RelativeTime {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val RELATIVE_LIMIT = 7 * DAY

    fun label(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return "时间未知"
        val delta = now - timestamp
        return when {
            delta < 0L -> "刚刚"                       // 时钟回拨/未来时间：不显示负数
            delta < MINUTE -> "刚刚"
            delta < HOUR -> "${delta / MINUTE} 分钟前"
            delta < DAY -> "${delta / HOUR} 小时前"
            delta < RELATIVE_LIMIT -> "${delta / DAY} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
