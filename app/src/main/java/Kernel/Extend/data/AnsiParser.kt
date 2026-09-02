// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package Kernel.Extend.data

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
        if (raw.isEmpty()) {
            return ParsedAnsiResult(AnnotatedString(""), "")
        }

        if (!raw.contains('\u001B')) {
            val singleStyle = SpanStyle(color = defaultColor, fontWeight = FontWeight.Normal)
            val annotated = AnnotatedString(raw, spanStyles = listOf(AnnotatedString.Range(singleStyle, 0, raw.length)))
            return ParsedAnsiResult(annotated, raw)
        }

        val plainSb = StringBuilder(raw.length)
        val annotated = buildAnnotatedString {
            var currentColor = defaultColor
            var isBold = false
            var lastIndex = 0

            ANSI_REGEX.findAll(raw).forEach { matchResult ->
                if (matchResult.range.first > lastIndex) {
                    val segment = raw.substring(lastIndex, matchResult.range.first)
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
                    for (code in codes) {
                        when {
                            code == 1 -> isBold = true
                            code == 22 -> isBold = false
                            code in 30..37 || code in 90..97 -> {
                                currentColor = COLOR_MAP[code] ?: defaultColor
                            }
                            code == 39 -> currentColor = defaultColor
                        }
                    }
                }

                lastIndex = matchResult.range.last + 1
            }

            if (lastIndex < raw.length) {
                val tail = raw.substring(lastIndex)
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
}
