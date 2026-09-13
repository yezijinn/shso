// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本落盘编码。
 *
 * **必须严格**：`String.toByteArray(charset)` 会把目标字符集无法表示的字符**静默替换为 `?`**，
 * 用户看到「已保存」但文件内容已损坏（典型：GBK/ISO-8859-1 文件里输入了该字符集没有的字符）。
 * 这里改用 `CharsetEncoder` 并打开 REPORT，遇到不可映射字符直接失败，由调用方提示用户。
 */
object TextEncoder {

    /** 该字符集能否表示 [text] 的全部字符。 */
    fun canEncode(text: String, charset: Charset): Boolean = encode(text, charset, writeBom = false).isSuccess

    /**
     * 编码为字节数组（可选前缀 BOM，仅对 UTF-8/UTF-16 有意义）。
     * 失败时返回携带可读原因的 [Result]（不可映射字符 / 非法的代理对等）。
     */
    fun encode(text: String, charset: Charset, writeBom: Boolean): Result<ByteArray> = runCatching {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(text))
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val bom = if (!writeBom) ByteArray(0) else when (charset) {
            Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        bom + body
    }.recoverCatching { error ->
        if (error is CharacterCodingException) {
            throw IllegalArgumentException("内容含 ${charset.name()} 无法表示的字符")
        }
        throw error
    }
}
