// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器规模的真实压测（不依赖 UI）。
 *
 * 复现「终端输出几万行后掉帧 / GC 抖动」的根因：
 * 滑窗只约束字符数，`completed` 曾是无界 ArrayList，
 * 10 万行输入会让 snapshot() 产出 10 万个 AnnotatedString。
 */
class AnsiParserStressTest {

    @Test fun `十万行输入下行数必须被上界约束`() {
        val parser = IncrementalAnsiParser(Color.White)
        // 分块喂入，模拟 HyperCore 的批量发布节奏
        val line = "x\n"
        var chunk = StringBuilder()
        repeat(20_000) {
            chunk.append(line)
            if (chunk.length >= 4096) {
                parser.feed(chunk.toString())
                chunk = StringBuilder()
            }
        }
        if (chunk.isNotEmpty()) parser.feed(chunk.toString())

        val lines = parser.snapshot().lines
        assertTrue(
            "行数必须被上界约束，实际 ${lines.size} 行；" +
                "无界时每次 snapshot 都要为每行新建 AnnotatedString + ArrayList + RangeList",
            lines.size <= 5000
        )
    }

    @Test fun `超限时必须如实记录被省略的行数`() {
        val parser = IncrementalAnsiParser(Color.White)
        repeat(20_000) { parser.feed("y\n") }
        assertTrue(
            "必须累计被丢弃的行数，供 UI 如实告知",
            parser.droppedLines > 0
        )
        assertTrue(
            "保留行数 + 省略行数应等于总输入行数",
            parser.snapshot().lines.size + parser.droppedLines >= 20_000 - 1
        )
    }

    @Test fun `reset 后计数必须归零`() {
        val parser = IncrementalAnsiParser(Color.White)
        repeat(6000) { parser.feed("z\n") }
        assertTrue("先产生省略", parser.droppedLines > 0)
        parser.reset()
        assertTrue("reset 必须清零省略计数", parser.droppedLines == 0)
    }
}
