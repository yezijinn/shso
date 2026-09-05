// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

data class ParsedAnsiResult(
    val text: AnnotatedString,
    val plainText: String
)

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
        // CR 归一化：\r\n 视为换行，孤立 \r 剥离（终端回车符在文本容器中会导致光标/显示异常）
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isEmpty()) {
            return ParsedAnsiResult(AnnotatedString(""), "")
        }

        if (!normalized.contains('\u001B')) {
            val singleStyle = SpanStyle(color = defaultColor, fontWeight = FontWeight.Normal)
            val annotated = AnnotatedString(normalized, spanStyles = listOf(AnnotatedString.Range(singleStyle, 0, normalized.length)))
            return ParsedAnsiResult(annotated, normalized)
        }

        val plainSb = StringBuilder(normalized.length)
        val annotated = buildAnnotatedString {
            var currentColor = defaultColor
            var isBold = false
            var lastIndex = 0

            ANSI_REGEX.findAll(normalized).forEach { matchResult ->
                if (matchResult.range.first > lastIndex) {
                    val segment = normalized.substring(lastIndex, matchResult.range.first)
                    plainSb.append(segment)
                    append(segment)
                    addStyle(
                        style = SpanStyle(
                            color = currentColor,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                        ),
                        start = length - segment.length,
                        end = length
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
                plainSb.append(tail)
                append(tail)
                addStyle(
                    style = SpanStyle(
                        color = currentColor,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                    ),
                    start = length - tail.length,
                    end = length
                )
            }
        }

        return ParsedAnsiResult(annotated, plainSb.toString())
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
