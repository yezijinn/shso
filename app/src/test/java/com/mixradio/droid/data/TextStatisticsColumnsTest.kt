// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TextStatistics.compute 的回归锁：覆盖最长行（columns）与既有 lines/分类字段。
 * 断言值由当前实现语义推导（非改语义），其中 lines 沿用 countLines（末尾换行计入一行）。
 */
class TextStatisticsColumnsTest {

    @Test
    fun `多行混合：columns 取最长行，分类正确`() {
        // "abc\ndefg\nhi" → 段 ["abc","defg","hi"]
        val s = TextStatistics.compute("abc\ndefg\nhi")
        assertEquals(11, s.chars)      // a b c \n d e f g \n h i
        assertEquals(3, s.lines)       // 2 个 '\n'
        assertEquals(4, s.columns)    // "defg" 最长
        assertEquals(9, s.english)     // abc(3)+defg(4)+hi(2)
        assertEquals(0, s.chinese)
        assertEquals(0, s.digits)
        assertEquals(2, s.symbols)     // 2 个 '\n'
        assertEquals(11, s.bytes)      // 全 ASCII
    }

    @Test
    fun `末尾换行：columns 含空尾段，lines 不把尾部空行算作一行`() {
        // "a\n" → split('\n') = ["a",""] → 最长行仍是 1。
        // lines 必须按 TextStatistics.countLines 的 KDoc 契约（"abc\n" → 1）与
        // TextCompare.countLines、SparseLineIndex、编辑器行号口径统一：
        // 末尾换行之后的「空行」不是一行。此前本用例把「多算一行」当成期望值固化了，
        // 与 countLines 自身契约相反，也让统计面板的行数恒比真实值多 1。
        val s = TextStatistics.compute("a\n")
        assertEquals(2, s.chars)
        assertEquals(1, s.lines)
        assertEquals(1, s.columns)
        assertEquals(1, s.english)
        assertEquals(1, s.symbols)
        assertEquals(2, s.bytes)
    }

    @Test
    fun `空文本全零`() {
        val s = TextStatistics.compute("")
        assertEquals(TextStatistics.Stats(0, 0, 0, 0, 0, 0, 0, 0), s)
    }

    @Test
    fun `中文与英文混合：columns 按字符数，分类正确`() {
        // "汉字\nabcdef" → 段 ["汉字"(2),"abcdef"(6)]
        val s = TextStatistics.compute("汉字\nabcdef")
        assertEquals(9, s.chars)      // 汉字(2)+\n(1)+abcdef(6)
        assertEquals(2, s.lines)
        assertEquals(6, s.columns)    // "abcdef"
        assertEquals(6, s.english)    // abcdef
        assertEquals(2, s.chinese)    // 汉字
        assertEquals(1, s.symbols)    // '\n'
        assertEquals(13, s.bytes)     // 汉字(3*2)+\n(1)+abcdef(6)
    }

    @Test
    fun `含 CRLF：按换行切分，CR 计入 symbols`() {
        // "ab\r\ncde" → split('\n') = ["ab\r","cde"] → 最长 3
        val s = TextStatistics.compute("ab\r\ncde")
        assertEquals(7, s.chars)
        assertEquals(2, s.lines)      // \r 与 \n 共同触发一次换行
        assertEquals(3, s.columns)    // "ab\r" 与 "cde" 均为 3
        assertEquals(5, s.english)    // ab + cde
        assertEquals(2, s.symbols)    // '\r' 与 '\n'
        assertEquals(7, s.bytes)      // 全 ASCII
    }
}
