// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 文本编码检测（不依赖第三方库）：
 *  1. UTF-8 BOM / UTF-16LE/BE BOM 头判定
 *  2. UTF-8 严格解码校验（失败则降级）
 *  3. GB18030 兼容 GBK/GB2312（中文环境兜底）
 *  4. 兜底 ISO-8859-1（任意字节合法，永不失败）
 */
object CharsetDetector {

    data class Detection(
        val charset: Charset,
        val hasBom: Boolean,
        /** 该编码下将原始字节解码为字符串的结果（已剔除 BOM） */
        val text: String
    )

    /** 检测 + 解码；bytes 允许为文件前若干字节（建议不超过 1MB）。 */
    fun detect(bytes: ByteArray): Detection {
        // ① BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Detection(StandardCharsets.UTF_8, true,
                String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            return Detection(StandardCharsets.UTF_16LE, true, text)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            return Detection(StandardCharsets.UTF_16BE, true, text)
        }
        // ② UTF-8 严格：能 round-trip 解码视为 UTF-8
        if (isStrictUtf8(bytes)) {
            return Detection(StandardCharsets.UTF_8, false, String(bytes, StandardCharsets.UTF_8))
        }
        // ③ GB18030（中文 Windows 常见）
        try {
            val cs = Charset.forName("GB18030")
            return Detection(cs, false, String(bytes, cs))
        } catch (_: Throwable) {
            // 极端环境无 GB18030，回退 ISO-8859-1
        }
        return Detection(StandardCharsets.ISO_8859_1, false, String(bytes, StandardCharsets.ISO_8859_1))
    }

    /** 严格 UTF-8 校验：解码后必须 round-trip 回原字节。 */
    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            val tmp = String(bytes, StandardCharsets.UTF_8)
            tmp.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)
        } catch (_: Throwable) {
            false
        }
    }
}
