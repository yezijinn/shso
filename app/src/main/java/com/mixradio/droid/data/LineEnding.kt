// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

/**
 * 换行符风格识别与还原。
 *  统计 LF / CRLF / CR 频次，按主导风格给出默认值；写入时按指定风格生成字节序列。
 */
enum class LineEnding(val literal: String) {
    LF("\n"), CRLF("\r\n"), CR("\r");

    fun toBytes(): ByteArray = literal.toByteArray(Charsets.UTF_8)

    companion object {
        fun detect(text: String): LineEnding {
            if (text.isEmpty()) return LF
            var lf = 0; var crlf = 0; var cr = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r') {
                    if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i += 2 } else { cr++; i++ }
                } else if (c == '\n') { lf++; i++ } else { i++ }
            }
            // 主导判定（优先 CRLF > LF > CR；混合时取最大）
            val max = maxOf(lf, crlf, cr)
            return when {
                max == crlf -> CRLF
                max == cr -> CR
                else -> LF
            }
        }

        /** 把文本中的换行统一替换为指定风格（避免异构）。 */
        fun apply(text: String, style: LineEnding): String {
            // 先把全部统一为 LF，再转目标
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            return when (style) {
                LF -> normalized
                CRLF -> normalized.replace("\n", "\r\n")
                CR -> normalized.replace("\n", "\r")
            }
        }
    }
}
