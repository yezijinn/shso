// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import com.mixradio.droid.data.IncrementalAnsiParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第六轮深挖的回归：终端覆盖写对 UTF-16 代理对的处理。
 *
 * 列号在 shso 里是 UTF-16 码元下标，而 emoji / 部分增补平面字符占**两个**码元。
 * 覆盖写（`\r` 之后重写同一行）与行尾擦除（`ESC[K`）此前都按单码元落笔，
 * 于是原地留下一个孤立代理 —— 显示成替换符，且 `plainText` 会把它带进剪贴板。
 *
 * 现实触发不是构造出来的：进度条/spinner 收尾缩窄、pv/pip 末帧、docker 拉层回显
 * 都会「打印一行 emoji 标题 + `\r` 覆盖成一行更短的 OK」。
 */
class AnsiSurrogateWriteTest {

    private fun parse(input: String): String {
        val p = IncrementalAnsiParser(Color.White)
        p.feed(input)
        p.finish()
        // finish() 会补一个尾换行（既有 AnsiParserLinesTest 的 noAnsi_keepsTrailingEmptyLine
        // 就是这条约定），断言文本内容时去掉它。
        return p.snapshot().plainText.trimEnd('\n')
    }

    private fun containsLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                // 高代理后面必须紧跟低代理，否则就是被劈开的半个字符
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return true // 低代理前面没有高代理 = 残缺
            i++
        }
        return false
    }

    @Test fun `回车覆盖 emoji 不得留下孤立代理`() {
        // printf '😀\rX\n'
        val out = parse("😀\rX\n")
        assertFalse("行内不得出现孤立代理：${out.map { it.code.toString(16) }}", containsLoneSurrogate(out))
        // 覆盖后应得到 "X"（emoji 所在的两格整体被替换）
        assertTrue("覆盖结果应含写入的字符：$out", out.contains("X"))
    }

    @Test fun `多格 emoji 被单字符覆盖时不得残留半个`() {
        // 两个 emoji 共 4 格，用 回车 + ESC[K 收尾
        val out = parse("😀😀\r[K\n")
        assertFalse("不得残留孤立代理：${out.map { it.code.toString(16) }}", containsLoneSurrogate(out))
    }

    @Test fun `行尾擦除不得把代理对截半`() {
        // ESC[K 从光标擦到行尾；光标退到 emoji 中间时不得留下半个
        val out = parse("😀X\b\b[K\n")
        assertFalse("擦除后不得残留孤立代理：${out.map { it.code.toString(16) }}", containsLoneSurrogate(out))
    }

    @Test fun `连续覆盖不得逐次累积孤立代理`() {
        // 模拟 spinner：反复 回车 + 覆盖，emoji 在行首
        val sb = StringBuilder()
        sb.append("😀").append("\rOK  ").append("\r").append("done")
        val out = parse(sb.toString())
        assertFalse("不得残留孤立代理：${out.map { it.code.toString(16) }}", containsLoneSurrogate(out))
    }

    @Test fun `未涉及覆盖的 emoji 必须原样保留`() {
        val out = parse("done 😀 ok\n")
        assertEquals("未经覆盖的 emoji 必须完整保留", "done 😀 ok", out)
    }

    @Test fun `多个代理对在同一行都必须完好`() {
        val out = parse("😀😁😂\n")
        assertEquals("一整行 emoji 必须逐字保留", "😀😁😂", out)
        assertFalse(containsLoneSurrogate(out))
    }
}