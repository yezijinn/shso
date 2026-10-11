// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import java.io.RandomAccessFile

/**
 * `.sh` 执行前的保守决策：任何无法确认安全的文件都按原字节执行。
 *
 * 判定原则是**不对称**的：
 * 漏判「内嵌载荷」的代价 = 自解压脚本被重写后压缩流必然损坏（真机实测
 * `gzip: gzread: invalid distance too far back`，退出码 127）；
 * 漏判「CRLF/BOM」的代价 = 脚本报 shebang 失败或变量尾部多一个 `\r`。
 * 前者不可恢复，后者可重试，因此凡不确定处一律判 [Plan.DIRECT]。
 */
internal object ShNormalization {

    /** 完整扫描上限：超过此值不做改写（载荷可能被截断，扫描结论不可信）。 */
    internal const val MAX_SCAN_BYTES = 16L * 1024 * 1024

    private val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())

    /**
     * 内嵌压缩载荷的强特征字节。
     *
     * 只收 **>=4 字节**的特征：3 字节短特征（如 LZMA 独立流的 `5D 00 00`）
     * 在纯文本脚本里也会偶发命中，会把「本应归一化的 CRLF 脚本」误判为 DIRECT，
     * 使 A75 的修复失效。LZMA 独立流本就不在本应用的解压格式内，去掉不损失识别能力。
     * bzip2 块头 `BZh` 同样只有 3 字节，故把级别数字一并纳入特征（见 [hasBzip2Header]）。
     */
    private val magics = listOf(
        byteArrayOf(0x1f, 0x8b.toByte()),                        // gzip
        byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00), // xz
        byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte()),    // zstd
        byteArrayOf(0x04, 0x22, 0x4d, 0x18),                      // lz4
        byteArrayOf(0x50, 0x4b, 0x03, 0x04)                       // zip
    )

    /**
     * 脚本按自身字节偏移寻址内嵌载荷的写法。
     *
     * 与 `$0` 的自引用判定**与**关系：只有脚本既引用了自身、又出现偏移取数，
     * 才认定它承载了内嵌载荷。正则刻意放宽（宁可多判 DIRECT）。
     */
    private val offsetReaders = listOf(
        // tail -c +N / tail -n +N / tail --bytes=+N，以及变量形式的 `tail $tail_n +$skip`
        // （真机样本 clear.sh 即 `tail $tail_n +$skip <"$0" | gzip -cd`，skip=50）。
        // 变量形式数量级上等同于字面量，必须一并命中，否则会漏判成「普通文本脚本」。
        Regex("\\btail\\b[^\\n]*\\+\\s*[0-9${'$'}]"),
        Regex("\\bhead\\b[^\\n]*\\s-c\\b"),             // head -c N
        Regex("\\bdd\\b[^\\n]*\\bskip\\s*="),           // dd if=$0 bs=1 skip=N
        Regex("\\bsed\\s+-n\\s+['\"]?\\d+[,;]")         // sed -n 'N,$p'
    )

    enum class Plan { DIRECT, NORMALIZE }

    /**
     * @param appReadable app 进程自身能否读取该文件。
     *   为 false 表示本次结论**不可靠地**偏保守（直接返回 DIRECT），
     *   调用方应改用 root 读取真实字节后重新判定（见 `RootService` 的 root 侧兜底）。
     */
    data class Decision(val plan: Plan, val reason: String, val appReadable: Boolean = true)

    fun decide(file: File): Decision {
        if (!file.isFile) {
            // app 域 stat 被 SELinux 拒绝（典型：/data/adb/shso，父目录 0700 root）时
            // isFile 恒为 false。这里**不能**当作「无需归一化」了结：那会让 CRLF/BOM
            // 脚本静默跳过归一化，直接退回 A75 已修的缺陷（shebang 带 \r 无法执行）。
            // 交给调用方以 root 读取后再判定。
            return Decision(Plan.DIRECT, "app 侧不可读，需 root 侧判定", appReadable = false)
        }
        val length = file.length()
        // 载荷可能在文件任意位置。无法完整扫描时不冒险改写。
        if (length > MAX_SCAN_BYTES) return Decision(Plan.DIRECT, "超过完整扫描上限，保留原字节")
        val bytes = try {
            RandomAccessFile(file, "r").use { raf ->
                ByteArray(length.toInt()).also(raf::readFully)
            }
        } catch (_: Exception) {
            return Decision(Plan.DIRECT, "读取失败，需 root 侧判定", appReadable = false)
        }
        return decideBytes(bytes)
    }

    internal fun decideBytes(bytes: ByteArray): Decision {
        val hasBom = bytes.size >= 3 && bytes[0] == bom[0] && bytes[1] == bom[1] && bytes[2] == bom[2]
        val hasCr = bytes.any { it == '\r'.code.toByte() }
        if (!hasBom && !hasCr) return Decision(Plan.DIRECT, "无需归一化")

        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 1024 * 1024))
            .toString(Charsets.ISO_8859_1)
        if (hasOffsetSelfReference(prefix)) {
            return Decision(Plan.DIRECT, "脚本按自身偏移读取载荷，保留原字节")
        }
        if (containsMagic(bytes)) return Decision(Plan.DIRECT, "发现内嵌压缩载荷，保留字节偏移")
        return Decision(Plan.NORMALIZE, "普通文本脚本，去 BOM 与 CR")
    }

    private fun hasOffsetSelfReference(prefix: String): Boolean {
        if (!referencesSelf(prefix)) return false
        return offsetReaders.any { it.containsMatchIn(prefix) }
    }

    /** 脚本是否引用自身路径（`$0` / `${0}` / `$BASH_SOURCE` / `${BASH_SOURCE}` 四种常见变体）。 */
    private fun referencesSelf(prefix: String): Boolean =
        "${'$'}0" in prefix || "${'$'}{0}" in prefix ||
            "${'$'}BASH_SOURCE" in prefix || "${'$'}{BASH_SOURCE" in prefix

    private fun containsMagic(bytes: ByteArray): Boolean = hasBzip2Header(bytes) || magics.any { magic ->
        if (magic.size > bytes.size) return@any false
        (0..bytes.size - magic.size).any { start ->
            magic.indices.all { index -> bytes[start + index] == magic[index] }
        }
    }

    /**
     * bzip2 块头为 `BZh` + 压缩级别 `1`~`9`（共 4 字节）。
     *
     * 裸 `BZh` 只有 3 字节、且全为可打印 ASCII，纯文本脚本里（注释、字符串常量）
     * 偶发命中就会把该归一化的 CRLF 脚本误判为 DIRECT。把紧跟其后的级别位一并要求后，
     * 特征长度与其他格式齐平，误命中概率降到可忽略。
     */
    private fun hasBzip2Header(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        for (start in 0..bytes.size - 4) {
            if (bytes[start] == 0x42.toByte() && bytes[start + 1] == 0x5a.toByte() &&
                bytes[start + 2] == 0x68.toByte() &&
                bytes[start + 3] >= 0x31.toByte() && bytes[start + 3] <= 0x39.toByte()
            ) {
                return true
            }
        }
        return false
    }
}
