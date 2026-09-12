// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

/**
 * 文本统计工具。
 *  - 行数：以 \n / \r\n / \r 切分；末尾无换行算最后一行
 *  - 纯英文数：仅 A-Z / a-z，任何其他字符（数字、空格、标点）都不算
 *  - 纯中文数：仅 Unicode CJK 统一表意文字（基本区 + 扩展 A-F + 兼容象形文字）
 *  - 纯数字数：仅 0-9（ASCII 半角数字），全角数字、小数点、正负号都不算
 *  - 其余字符（空白、标点、其他语种）归为 symbols，界面不展示，仅用于内部归类
 *  分类在一次码点遍历中完成，四类之和 = 码点总数。
 */
object TextStatistics {

    data class Stats(
        val chars: Int,
        val lines: Int,
        val english: Int,
        val chinese: Int,
        val digits: Int,
        val symbols: Int,
        val columns: Int,
        val bytes: Int
    )

    fun compute(text: String): Stats {
        if (text.isEmpty()) return Stats(0, 0, 0, 0, 0, 0, 0, 0)
        val lines = countLines(text)
        // 最长行（columns）：与分类遍历合并到同一个 while 循环，避免 split 分配整行列表。
        // 语义等价于 text.split('\n').maxOfOrNull { it.length }（仅按 '\n' 切分，末尾段也参与比较）。
        var maxLine = 0
        var currentLen = 0
        var english = 0
        var chinese = 0
        var digits = 0
        var symbols = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            // 仅按 '\n' 切分；遇换行即结算当前行长度并归零（与 split 的分段一致）。
            // 约束：'\n' 仍须计入 symbols（与既有统计口径保持一致），不可在此提前 continue 跳过分类。
            if (ch == '\n') {
                if (currentLen > maxLine) maxLine = currentLen
                currentLen = 0
                symbols++
                i++
                continue
            }
            if (ch.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) {
                val cp = Character.toCodePoint(ch, text[i + 1])
                if (isChineseCodePoint(cp)) chinese++ else symbols++
                currentLen += 2
                i += 2
                continue
            }
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' -> english++
                ch in '0'..'9' -> digits++
                isChineseCodePoint(ch.code) -> chinese++
                else -> symbols++
            }
            currentLen += 1
            i++
        }
        // 比较尾部段（split 出来的最后一段长度）
        if (currentLen > maxLine) maxLine = currentLen
        return Stats(
            chars = text.length,
            lines = lines,
            english = english,
            chinese = chinese,
            digits = digits,
            symbols = symbols,
            columns = maxLine,
            bytes = text.toByteArray(Charsets.UTF_8).size
        )
    }

    /**
     * 统计行数（按 \r\n / \n / \r 切分）。
     *  - "abc" → 1
     *  - "abc\n" → 1
     *  - "abc\ndef" → 2
     *  - "" → 0
     */
    fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 1
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch == '\n') {
                count++
                i++
            } else if (ch == '\r') {
                count++
                i++
                if (i < n && text[i] == '\n') i++
            } else {
                i++
            }
        }
        return count
    }

    /**
     * 统计纯汉字数（CJK 统一表意文字）。
     * 范围：
     *  - U+4E00..U+9FFF 基本区
     *  - U+3400..U+4DBF 扩展 A
     *  - U+20000..U+2A6DF 扩展 B
     *  - U+2A700..U+2B73F 扩展 C
     *  - U+2B740..U+2B81F 扩展 D
     *  - U+2B820..U+2CEAF 扩展 E
     *  - U+2CEB0..U+2EBEF 扩展 F
     *  - U+F900..U+FAFF CJK 兼容象形文字
     * 不含部首、笔画、符号等（按用户要求"必须是纯汉字"）。
     */
    fun countChinese(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch.isHighSurrogate() && i + 1 < n) {
                val low = text[i + 1]
                if (low.isLowSurrogate()) {
                    val codePoint = Character.toCodePoint(ch, low)
                    if (isChineseCodePoint(codePoint)) count++
                    i += 2
                    continue
                }
            }
            // BMP CJK 基本区
            if (ch.code in 0x4E00..0x9FFF ||
                ch.code in 0x3400..0x4DBF ||
                ch.code in 0xF900..0xFAFF) {
                count++
            }
            i++
        }
        return count
    }

    private fun isChineseCodePoint(cp: Int): Boolean {
        return cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x20000..0x2A6DF ||
                cp in 0x2A700..0x2B73F ||
                cp in 0x2B740..0x2B81F ||
                cp in 0x2B820..0x2CEAF ||
                cp in 0x2CEB0..0x2EBEF ||
                cp in 0xF900..0xFAFF
    }
}
