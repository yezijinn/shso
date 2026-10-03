// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 文本编码检测（不依赖第三方库）：
 *  1. UTF-8 BOM / UTF-16LE/BE BOM 头判定
 *  2. **无 BOM 的 UTF-16 靠 NUL 字节分布嗅探**（见 [sniffUtf16WithoutBom]）
 *  3. UTF-8 严格解码校验（失败则降级）
 *  4. GB18030 兼容 GBK/GB2312（中文环境兜底）
 *  5. 兜底 ISO-8859-1（任意字节合法，永不失败）
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
        // ② 无 BOM 的 UTF-16：ASCII 文本里每个字符的第二个字节恒为 0x00，
        //    这是最强特征。必须在 UTF-8 严格校验**之前**判定，否则 UTF-16LE 的
        //    NUL 字节会让 UTF-8 解码「碰巧成功」（ASCII 区间全是合法单字节序列），
        //    文件被当成 UTF-8 打开，满屏问号。
        sniffUtf16WithoutBom(bytes)?.let { (charset, _) ->
            return Detection(charset, false, String(bytes, charset))
        }
        // ③ UTF-8 严格：能 round-trip 解码视为 UTF-8
        if (isStrictUtf8(bytes)) {
            return Detection(StandardCharsets.UTF_8, false, String(bytes, StandardCharsets.UTF_8))
        }
        // ④ 非严格 UTF-8：可能是「少量非法字节的 UTF-8」，也可能是真正的 GB18030/其它编码。
        //    **不能无条件落 GB18030** —— 那会造成不可逆的字节改写：
        //    一个「基本是 ASCII、中间夹 1 个非法字节」的文件（日志里混进一行 GB18030 中文、
        //    日志被截断在多字节字符中间）会让 isStrictUtf8 因 round-trip 不等而失败，
        //    于是**整个文件**被按 GB18030 重解释：原本合法的 UTF-8 中文全部变乱码、
        //    非法字节变 U+FFFD。用户看不出异常（只是满屏乱码），改一个字点保存
        //    就按 GB18030 全量重写 → 原始字节被彻底替换，不可逆、不可撤销。
        //    故先判「这到底是不是 GB18030」：GB18030 覆盖全部 Unicode 码位，
        //    真正的 GB18030 文本不该含 U+FFFD；含得越多说明越不是它。
        try {
            val cs = Charset.forName("GB18030")
            val gbText = String(bytes, cs)
            val replacements = gbText.count { it == '\uFFFD' }
            // 阈值 1%：正常 GB18030 文本的 U+FFFD 密度应为 0，给一点量化余量
            if (replacements * 100 <= bytes.size) {
                return Detection(cs, false, gbText)
            }
        } catch (_: Throwable) {
            // 极端环境无 GB18030，回退 ISO-8859-1
        }
        // ⑤ 都不是：按 ISO-8859-1 兜底。该编码对**任意**字节序列都一一映射到 U+0000..U+00FF，
        //    往返无损，至少不会破坏用户的原始数据（GB18030 回落会把合法字节改成别的字符）。
        return Detection(StandardCharsets.ISO_8859_1, false, String(bytes, StandardCharsets.ISO_8859_1))
    }

    /**
     * 嗅探**无 BOM** 的 UTF-16LE / UTF-16BE，判不出返回 null。
     *
     * 需要**两个独立信号**同时成立，缺一不可：
     *
     * 1. **NUL 分布不对称**：ASCII 文本在 UTF-16 下每隔一个字节恒为 `0x00`；
     *    中日韩字符两字节都非 0，NUL 只出现在换行处，因此一侧占比会偏低但仍显著高于另一侧
     *    （实测纯中文 + 换行的 UTF-16LE 文本奇数位 NUL 占比约 0.2）。
     * 2. **按该编码解码后无控制字符噪声**：合法文本里除了 `\t \n \r` 不会出现其它 C0 控制字符。
     *    伪随机二进制按 UTF-16 解码几乎必然产生控制字符，这是主要的排除依据。
     *
     * 只用信号 1 会被「大量 NUL 的二进制」骗过，只用信号 2 在某些巧合分布下会误判，
     * 两者合取才稳。
     */
    fun sniffUtf16WithoutBom(bytes: ByteArray): Pair<Charset, Double>? {
        // 16 位码元需要偶数长度；末尾落单的 1 字节不参与判定
        val limit = minOf(bytes.size, 8192) and 1.inv()
        if (limit < MIN_UTF16_SNIFF_BYTES) return null

        var evenNul = 0
        var oddNul = 0
        for (i in 0 until limit) {
            if (bytes[i] == 0.toByte()) {
                if (i % 2 == 0) evenNul++ else oddNul++
            }
        }
        val pairs = limit / 2
        val evenRatio = evenNul.toDouble() / pairs
        val oddRatio = oddNul.toDouble() / pairs
        val high = maxOf(evenRatio, oddRatio)
        val low = minOf(evenRatio, oddRatio)
        // 信号 1：一侧占比达到下限，且明显高于另一侧
        if (high < UTF16_NUL_RATIO || high < low * 2.0) return null

        // 信号 2：按候选编码解码后必须是「干净文本」
        val le = StandardCharsets.UTF_16LE
        val be = StandardCharsets.UTF_16BE
        val leClean = isCleanText(String(bytes, 0, limit, le))
        val beClean = isCleanText(String(bytes, 0, limit, be))
        return when {
            // 奇数位 NUL 占优 → UTF-16LE（ASCII 字符高位字节落在奇数位）
            oddRatio > evenRatio && leClean -> le to oddRatio
            evenRatio > oddRatio && beClean -> be to evenRatio
            // 两侧 NUL 完全相等时无法定向；只要某一侧解码干净就按那一侧取值
            oddRatio == evenRatio && leClean -> le to oddRatio
            oddRatio == evenRatio && beClean -> be to evenRatio
            else -> null
        }
    }

    /** 文本是否不含异常 C0 控制字符（允许 TAB / LF / CR 与 C1 区替换）。 */
    private fun isCleanText(text: String): Boolean {
        for (ch in text) {
            if (ch == '\t' || ch == '\n' || ch == '\r') continue
            if (ch.code < 0x20 || ch.code == 0x7F) return false
            // UTF-16 的代理项单独出现也是「不是文本」的信号
            if (ch.code in 0xD800..0xDFFF) return false
        }
        return true
    }

    /** 某一侧的 NUL 占比达到该值才继续考虑解码。 */
    private const val UTF16_NUL_RATIO = 0.18

    /** 嗅探所需最小字节数（太短的样本不足以统计）。 */
    private const val MIN_UTF16_SNIFF_BYTES = 8

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
