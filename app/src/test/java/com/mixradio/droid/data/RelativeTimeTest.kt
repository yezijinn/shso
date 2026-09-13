// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 历史条目的时间显示：近 7 天相对时间，更早给绝对时间。 */
class RelativeTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `刚刚`() {
        assertEquals("刚刚", RelativeTime.label(now, now))
        assertEquals("刚刚", RelativeTime.label(now - 59_000L, now))
    }

    @Test
    fun `分钟与小时与天`() {
        assertEquals("1 分钟前", RelativeTime.label(now - 60_000L, now))
        assertEquals("59 分钟前", RelativeTime.label(now - 59 * 60_000L, now))
        assertEquals("1 小时前", RelativeTime.label(now - 3_600_000L, now))
        assertEquals("23 小时前", RelativeTime.label(now - 23 * 3_600_000L, now))
        assertEquals("1 天前", RelativeTime.label(now - 24 * 3_600_000L, now))
        assertEquals("6 天前", RelativeTime.label(now - 6 * 86_400_000L, now))
    }

    @Test
    fun `超过 7 天给绝对时间`() {
        val label = RelativeTime.label(now - 8 * 86_400_000L, now)
        assertEquals("应为 yyyy-MM-dd HH:mm", 16, label.length)
        assertEquals('-', label[4])
        assertEquals(':', label[13])
    }

    @Test
    fun `时间戳缺失或为未来不产生负数`() {
        assertEquals("时间未知", RelativeTime.label(0L, now))
        assertEquals("刚刚", RelativeTime.label(now + 60_000L, now))
    }
}
