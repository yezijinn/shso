// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行反馈文案的两条纯逻辑：耗时格式化与「脚本是否算静默」。
 *
 * 静默脚本（只做 `rm`/`touch`、无任何 `echo`）在终端里除横幅与退出码外一片空白，
 * 用户会以为没执行。收尾时的「未产生任何输出」提示就是靠这里的两条判定给出的。
 */
class ExecutionFeedbackTest {

    // ---------- 耗时格式化 ----------

    @Test
    fun `毫秒级不丢精度`() {
        assertEquals("0毫秒", formatElapsed(0))
        assertEquals("1毫秒", formatElapsed(1))
        assertEquals("999毫秒", formatElapsed(999))
    }

    @Test
    fun `负耗时按零处理`() {
        // 系统时间被回拨时可能为负，不得显示成负数
        assertEquals("0秒", formatElapsed(-500))
    }

    @Test
    fun `秒级保留一位小数`() {
        assertEquals("1.0秒", formatElapsed(1000))
        assertEquals("1.5秒", formatElapsed(1500))
        assertEquals("59.9秒", formatElapsed(59_900))
    }

    @Test
    fun `分钟与小时级不出现小数`() {
        assertEquals("1分0秒", formatElapsed(60_000))
        assertEquals("2分5秒", formatElapsed(125_000))
        assertEquals("59分59秒", formatElapsed(3_599_000))
        assertEquals("1小时0分0秒", formatElapsed(3_600_000))
        assertEquals("2小时1分1秒", formatElapsed(7_261_000))
    }

    @Test
    fun `小数点使用点号而非本地化逗号`() {
        // Locale 影响 String.format 的小数分隔符；中文环境亦须稳定输出 "1.5秒" 而非 "1,5秒"
        assertTrue(formatElapsed(1500).contains('.'))
    }

    // ---------- 静默判定 ----------

    @Test
    fun `全空白不算产生输出`() {
        // 空行、缩进、纯换行都不算「有输出」：它们不携带任何信息
        assertEquals(false, ExecutionFeedback.hasVisibleOutput(""))
        assertEquals(false, ExecutionFeedback.hasVisibleOutput("   "))
        assertEquals(false, ExecutionFeedback.hasVisibleOutput("\n\n\n"))
        assertEquals(false, ExecutionFeedback.hasVisibleOutput("\t \r\n  \n"))
    }

    @Test
    fun `任意非空白字符即算产生输出`() {
        assertEquals(true, ExecutionFeedback.hasVisibleOutput("a"))
        assertEquals(true, ExecutionFeedback.hasVisibleOutput("中文"))
        assertEquals(true, ExecutionFeedback.hasVisibleOutput("🚀"))
        assertEquals(true, ExecutionFeedback.hasVisibleOutput("   x   "))
    }

    @Test
    fun `分块读取时任一块有内容即整体算有输出`() {
        // 读取循环按 2048 字符分块，判定必须逐块累积，不能只看最后一块
        val chunks = listOf("   \n", "\t", "done")
        assertEquals(true, ExecutionFeedback.anyChunkHasContent(chunks))
        assertEquals(false, ExecutionFeedback.anyChunkHasContent(listOf("", " ", "\n")))
    }
}
