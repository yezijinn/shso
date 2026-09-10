// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * ANSI 解析结果。
 * @param lines 按行切分的渲染结果，每行一个 [AnnotatedString]（不含行尾 '\n'）；
 *   空行对应空 [AnnotatedString]，与 `split('\n')` 语义一致（"a\n" → ["a", ""]）。
 */
class ParsedAnsiResult(
    val lines: List<AnnotatedString>
) {
    /**
     * 已剥离转义序列、CR 已归一化的纯文本（与历史语义一致）。
     * 惰性求值：它只在「复制输出」时被读取，若每次 flush 都拼接整段（250k）会造成大量
     * 重复分配，故改为按需计算并缓存。
     */
    val plainText: String by lazy { lines.joinToString("\n") { it.text } }
}

object AnsiParser {

    private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[a-zA-Z]")

    private val COLOR_MAP = mapOf(
        30 to Color(0xFF4E4E4E),
        31 to Color(0xFFFF5252),
        32 to Color(0xFF4CAF50),
        33 to Color(0xFFFFD54F),
        34 to Color(0xFF448AFF),
        35 to Color(0xFFE040FB),
        36 to Color(0xFF18FFFF),
        37 to Color(0xFFEEEEEE),
        90 to Color(0xFF757575),
        91 to Color(0xFFFF8A80),
        92 to Color(0xFFB9F6CA),
        93 to Color(0xFFFFFF8D),
        94 to Color(0xFF82B1FF),
        95 to Color(0xFFEA80FC),
        96 to Color(0xFF84FFFF),
        97 to Color(0xFFFFFFFF)
    )

    fun parseAnsi(raw: String, defaultColor: Color): ParsedAnsiResult {
        // CR 归一化不要无条件做：仅当含 '\r' 时才做单次归一化，避免每次 flush 两次全量 replace 分配。
        val normalized = if (raw.indexOf('\r') >= 0) {
            raw.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            raw
        }
        if (normalized.isEmpty()) {
            return ParsedAnsiResult(emptyList())
        }

        // 逐行构建：lineTexts 存每行纯文本，lineStyles 存每行相对该行的 SpanStyle 区间。
        val lineTexts = ArrayList<StringBuilder>()
        val lineStyles = ArrayList<MutableList<AnnotatedString.Range<SpanStyle>>>()
        var curText = StringBuilder()
        var curStyles = mutableListOf<AnnotatedString.Range<SpanStyle>>()

        // 把一段文本按 '\n' 切分后追加到当前行（记录区间的 start/end 为相对该行偏移），遇 '\n' 新起一行。
        fun emit(segment: String, style: SpanStyle) {
            var i = 0
            while (i < segment.length) {
                val nl = segment.indexOf('\n', i)
                if (nl == -1) {
                    val start = curText.length
                    curText.append(segment, i, segment.length)
                    val end = curText.length
                    if (end > start) {
                        curStyles.add(AnnotatedString.Range(style, start, end))
                    }
                    break
                } else {
                    val start = curText.length
                    curText.append(segment, i, nl)
                    val end = curText.length
                    if (end > start) {
                        curStyles.add(AnnotatedString.Range(style, start, end))
                    }
                    // 行结束：收尾当前行并开新行
                    lineTexts.add(curText)
                    lineStyles.add(curStyles)
                    curText = StringBuilder()
                    curStyles = mutableListOf()
                    i = nl + 1
                }
            }
        }

        if (!normalized.contains('\u001B')) {
            // 无 ANSI 快速路径：整段用默认色一次性按行 emit
            val singleStyle = SpanStyle(color = defaultColor, fontWeight = FontWeight.Normal)
            emit(normalized, singleStyle)
        } else {
            var currentColor = defaultColor
            var isBold = false
            var lastIndex = 0

            ANSI_REGEX.findAll(normalized).forEach { matchResult ->
                if (matchResult.range.first > lastIndex) {
                    val segment = normalized.substring(lastIndex, matchResult.range.first)
                    emit(
                        segment,
                        SpanStyle(
                            color = currentColor,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }

                val codeStr = matchResult.value
                val codes = codeStr.substring(2, codeStr.length - 1)
                    .split(";")
                    .mapNotNull { it.toIntOrNull() }

                if (codes.isEmpty() || codes.contains(0)) {
                    currentColor = defaultColor
                    isBold = false
                } else {
                    // 索引遍历：38;5;n（256 色）与 38;2;r;g;b（真彩色）为可变长度参数，
                    // 解析后跳过其参数，避免把 5/2 或颜色分量误当独立 SGR 码处理
                    var i = 0
                    while (i < codes.size) {
                        val code = codes[i]
                        when {
                            code == 1 -> isBold = true
                            code == 22 -> isBold = false
                            code == 39 -> currentColor = defaultColor
                            code in 30..37 || code in 90..97 -> {
                                currentColor = COLOR_MAP[code] ?: defaultColor
                            }
                            // 前景扩展色：256 色（38;5;n）与真彩色（38;2;r;g;b）
                            code == 38 && i + 1 < codes.size -> {
                                when (codes[i + 1]) {
                                    5 -> if (i + 2 < codes.size) {
                                        currentColor = ansi256ToColor(codes[i + 2])
                                        i += 2
                                    }
                                    2 -> if (i + 4 < codes.size) {
                                        currentColor = Color(
                                            codes[i + 2].coerceIn(0, 255),
                                            codes[i + 3].coerceIn(0, 255),
                                            codes[i + 4].coerceIn(0, 255)
                                        )
                                        i += 4
                                    }
                                }
                            }
                            // 背景色（48/34 等）、下划线等未识别码保持忽略，与历史行为一致
                        }
                        i++
                    }
                }

                lastIndex = matchResult.range.last + 1
            }

            if (lastIndex < normalized.length) {
                val tail = normalized.substring(lastIndex)
                emit(
                    tail,
                    SpanStyle(
                        color = currentColor,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                    )
                )
            }
        }

        // 收尾最后一行（句尾 '\n' 产生的空行也要保留，与 split('\n') 语义一致）
        lineTexts.add(curText)
        lineStyles.add(curStyles)

        val lines = lineTexts.mapIndexed { idx, sb ->
            AnnotatedString(sb.toString(), spanStyles = lineStyles[idx])
        }
        return ParsedAnsiResult(lines)
    }

    /**
     * xterm 256 色调色板（CLUT）到颜色：
     * 0-15 映射 ANSI 基础色，16-231 为 6×6×6 色块，232-255 为 24 阶灰度。
     */
    private fun ansi256ToColor(index: Int): Color {
        return when (index) {
            in 0..7 -> COLOR_MAP.getValue(30 + index)
            in 8..15 -> COLOR_MAP.getValue(90 + index - 8)
            in 16..231 -> {
                val v = index - 16
                val component = { x: Int -> if (x == 0) 0 else 55 + x * 40 }
                Color(
                    component(v / 36),
                    component((v / 6) % 6),
                    component(v % 6)
                )
            }
            else -> {
                val gray = 8 + (index - 232) * 10
                Color(gray.coerceIn(0, 255), gray.coerceIn(0, 255), gray.coerceIn(0, 255))
            }
        }
    }
}
