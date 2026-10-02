// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import java.io.RandomAccessFile

/**
 * ZIP 中央目录条目数的**预算前置探针**。
 *
 * ## 要解决的问题
 *
 * zip4j（以及 java.util.zip）要枚举归档内容，必须先把整个 central directory 解析成
 * 内存里的 `FileHeader` 列表。也就是说「这个包有 100 万个条目」这件事，是在**已经分配完
 * 100 万个对象的之后**才被发现的。原先的写法是：
 *
 * ```
 * val headers = zip.fileHeaders          // ← 分配已经发生
 * if (headers.size > MAX_ENTRIES) ...   // ← 预算检查在之后
 * ```
 *
 * 恶意/畸形归档（zip bomb 的变体）可以只靠「条目数爆炸」就把应用 OOM 掉，
 * 预算检查形同虚设。
 *
 * ## 做法
 *
 * ZIP 的 End Of Central Directory（EOCD）记录固定在文件尾部（末尾还有一段最长 65535
 * 字节的注释），里面就写着条目总数。**读最后 64KB 就够了**，不需要触碰压缩数据。
 * 于是顺序变成：
 *
 * ```
 * 探针读尾部 22 字节 → 条目数超限？→ 立即拒绝（零分配）
 *                      → 通过？→ 才让 zip4j 解析 central directory
 * ```
 *
 * ## ZIP64
 *
 * EOCD 的条目数字段只有 2 字节，条目数 ≥ 65535 时写 `0xFFFF` 并把真实值挪到
 * ZIP64 EOCD。这里**不去解析 ZIP64 记录**：能走到 `0xFFFF` 分支说明条目数已达上限量级，
 * 而本项目的上限是 2 万，远低于 65535，因此「≥65535」与「>20000」在判定上等价 ——
 * 直接按超限拒绝既正确又省掉一套 ZIP64 解析。
 */
internal object ZipEntryCountProbe {

    /** EOCD 固定部分长度。 */
    const val EOCD_MIN_SIZE = 22

    /** EOCD 注释字段最大长度（2 字节）。 */
    const val MAX_COMMENT_LENGTH = 0xFFFF

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val ZIP64_SENTINEL = 0xFFFF

    /** 读尾部所需的最大字节数：EOCD 固定部分 + 最长注释。 */
    const val TAIL_WINDOW = EOCD_MIN_SIZE + MAX_COMMENT_LENGTH

    /**
     * 从**文件尾部字节**解析条目总数。纯函数，便于 JVM 单测。
     *
     * @param tail 文件最后一段字节（调用方需保证覆盖 TAIL_WINDOW 或整个文件）
     * @param fileSize 文件总长度，用于判断 tail 是否覆盖到了 EOCD 的起始位置
     * @return 条目数；无法确定（尾部无 EOCD 签名、tail 不完整）返回 null
     */
    fun entryCountFromTail(tail: ByteArray, fileSize: Long): Int? {
        if (tail.size < EOCD_MIN_SIZE || fileSize < tail.size) return null
        // 从尾部往前扫 EOCD 签名。注释里可能伪造签名，故不能只查最后一个位置；
        // 命中后还要校验「EOCD 结束位置 + 注释长度 == 文件长度」。
        var i = tail.size - EOCD_MIN_SIZE
        while (i >= 0) {
            if (readIntLE(tail, i) == EOCD_SIGNATURE) {
                val commentLength = readShortLE(tail, i + 20)
                // EOCD 起点 + 22 + 注释长度 必须正好等于文件长度，否则这个签名是伪造的
                if (i.toLong() + EOCD_MIN_SIZE + commentLength.toLong() == fileSize) {
                    val raw = readShortLE(tail, i + 10)
                    // 0xFFFF == ZIP64，实际条目数只会更大；按「已达上限量级」处理
                    return if (raw == ZIP64_SENTINEL) ZIP64_SENTINEL else raw
                }
            }
            i--
        }
        return null
    }

    /**
     * 读取归档尾部并返回条目数；非 ZIP / 读失败返回 null（交由后续解析器报更准确的错）。
     *
     * 只读末尾 [TAIL_WINDOW] 字节，不受归档体积影响。
     */
    fun probe(file: File): Int? = runCatching {
        if (!file.isFile || file.length() < EOCD_MIN_SIZE) return null
        RandomAccessFile(file, "r").use { raf ->
            val window = minOf(raf.length(), TAIL_WINDOW.toLong()).toInt()
            val tail = ByteArray(window)
            raf.seek(raf.length() - window)
            raf.readFully(tail)
            entryCountFromTail(tail, raf.length())
        }
    }.getOrNull()

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
}
