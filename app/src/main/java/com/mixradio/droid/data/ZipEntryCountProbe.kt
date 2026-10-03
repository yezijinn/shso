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

    /**
     * 中央目录单条 header 记录的**最小**字节数（签名4+版本2+标志2+压缩2+时间2+日期2
     * +CRC4+压缩后4+解压后4+名长2+extra长2+注释长2+磁盘号2+内部属性2+外部属性4
     * +本地头偏移4 = 46）。文件名/extra/注释是变长部分，故 `cdSize / 46` 是条目数下界。
     */
    private const val CENTRAL_DIR_HEADER_MIN_SIZE = 46

    /** 读尾部所需的最大字节数：EOCD 固定部分 + 最长注释。 */
    const val TAIL_WINDOW = EOCD_MIN_SIZE + MAX_COMMENT_LENGTH

    /**
     * 从**文件尾部字节**解析条目总数。纯函数，便于 JVM 单测。
     *
     * @param tail 文件最后一段字节（调用方需保证覆盖 TAIL_WINDOW 或整个文件）
     * @param fileSize 文件总长度
     * @return 条目数；无法确定（尾部无 EOCD 签名、tail 不完整）返回 null
     */
    fun entryCountFromTail(tail: ByteArray, fileSize: Long): Int? {
        if (tail.size < EOCD_MIN_SIZE || fileSize < tail.size) return null
        // 收集**全部**自洽的 EOCD 候选，取条目数最大者。
        //
        // 为什么不能用「EOCD 结束位置必须正好等于文件长度」的严格判据：
        // zip4j 2.11.1 `HeaderReader.locateOffsetOfEndOfCentralDirectoryByReverseSeek`
        // 反向逐字节找签名后**直接返回，不做任何 EOF 对齐或注释长度校验**
        // （已用 javap 反编译核对字节码确认）。所以「真 EOCD 之后还挂着垃圾字节」的 zip
        // （拼接下载、对齐填充、自定义工具追加）zip4j 能照常解析出全部 central directory。
        // 严格判据会判成「非 zip」返回 null，预算检查被静默绕过 —— 正是这个探针要防的事。
        //
        // 为什么取最大值而不是第一个命中：
        // 注释区可以伪造签名。严格等值判据能靠「必须正好等于文件长度」把伪造者滤掉，
        // 放宽后就得换个不依赖 EOF 的判据：要求候选**自洽**（EOCD 起点 + 22 + 注释长度
        // 不超过文件长度），并对所有自洽候选取最大条目数 —— 无论 zip4j 最终反查命中哪一个，
        // 探针给出的都是不小于它的上界，预算检查因此偏保守而非漏判。
        var best: Int? = null
        var i = 0
        while (i + EOCD_MIN_SIZE <= tail.size) {
if (readIntLE(tail, i) == EOCD_SIGNATURE) {
                val commentLength = readShortLE(tail, i + 20)
                val endExclusive = i.toLong() + EOCD_MIN_SIZE + commentLength.toLong()
                if (endExclusive <= fileSize) {
                    val raw = readShortLE(tail, i + 10)
                    // 0xFFFF == ZIP64，实际条目数在别处；见常量
                    val count = if (raw == ZIP64_SENTINEL) ZIP64_SENTINEL else raw
                    if (best == null || count > best) best = count
                    // **中央目录实际体积**是第二个独立上界，不能只看自报条目数。
                    //
                    // 自报字段（EOCD 的「本盘/总条目数」）是**攻击者可控**的：把中央目录
                    // 实际写上百万条 header 记录、而该字段写 1，预检就得到 1 → 放行；
                    // 而 zip4j 的 HeaderReader 是**从中央目录起点逐条扫到 EOCD 签名为止**，
                    // 不以自报计数为上界 → 百万个 FileHeader 一次性进堆 → OOM。
                    // 也就是说预检对它唯一要防的攻击形态完全失效。
                    //
                    // 每条中央目录 header 记录固定 46 字节（文件名/extra/comment 变长），
                    // 故 cdSize/46 是条目数的**下界**，用它兜住自报字段被改小的情况。
                    val cdSize = readIntLE(tail, i + 12)
                    // readIntLE 是有符号的：cdSize ≥ 0x80000000 时会返回负数（ZIP64 场景），
                    // 那种情况真实体积在 ZIP64 扩展字段里，自报条目数已是 0xFFFF 哨兵、
                    // 预检会走保守分支，故负值直接跳过。
                    if (cdSize >= 0) {
                        val bySize = cdSize / CENTRAL_DIR_HEADER_MIN_SIZE
                        if (bySize > (best ?: 0)) best = bySize
                    }
                }
            }
            i++
        }
        return best
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
