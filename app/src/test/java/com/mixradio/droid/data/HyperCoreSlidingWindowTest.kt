// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志滑动窗口的回归守卫。
 *
 * 约束：超出上限时保留尾部约 [HyperCore] 的 PRUNE_TARGET_LENGTH 字符，
 * 且**优先对齐到换行**（避免切到行中间）；只有在整段没有换行时才硬截，
 * 硬截时必须避免从代理对中间切开（否则头部渲染成半个字符）。
 */
class HyperCoreSlidingWindowTest {

    @Test
    fun `未超上限时原样拼接`() {
        assertEquals("ab", HyperCore.appendWithSlidingWindow("a", "b"))
    }

    @Test
    fun `超上限时按换行裁剪并保留尾部`() {
        val line = "0123456789".repeat(6) + "\n" // 61 字符/行
        val big = line.repeat(5000)              // 约 305k 字符
        val result = HyperCore.appendWithSlidingWindow(big, "tail")
        assertTrue("必须显著变短", result.length < big.length)
        assertTrue("必须以完整行开头（不切到行中间）", result.startsWith(line))
        assertFalse("裁剪点之前的内容必须已被丢弃", result.startsWith(big))
    }

    @Test
    fun `无换行的超大输出硬截时头部长度受控`() {
        val huge = "A".repeat(245_000)
        val result = HyperCore.appendWithSlidingWindow(huge, "B".repeat(10_000))
        assertTrue(result.length in 179_000..181_000)
        assertTrue(result.startsWith("A"))
    }

    @Test
    fun `硬截不从代理对中间切开`() {
        // 构造：让硬截点正好落在 emoji 的低代理项上
        // 硬截下标 = 长度 - PRUNE_TARGET_LENGTH = 250_001 - 180_000 = 70_001
        val total = 250_001
        val cut = total - 180_000
        val prefixLen = cut - 1                       // 高代理项落在 cut-1、低代理项落在 cut
        val body = "A".repeat(prefixLen) + "\uD83D\uDE00" + "A".repeat(total - prefixLen - 2)
        val result = HyperCore.appendWithSlidingWindow(body, "")
        // 回退一位后，结果应以完整 emoji 开头，而不是落单的低代理项
        assertFalse(result[0].isLowSurrogate())
        assertEquals("\uD83D\uDE00" + "A".repeat(total - cut - 1), result)
    }
}
