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
 * ## 为什么不能只读尾部自报字段
 *
 * EOCD 里的「本盘/总条目数」是**攻击者可控**的：中央目录实际写上百万条 header 记录、
 * 该字段写 1，预检就得到 1 → 放行；而 zip4j 的 `HeaderReader` 是**从中央目录起点逐条扫到
 * EOCD 签名为止**、不以自报计数为上界 → 百万个 `FileHeader` 一次性进堆 → OOM。
 *
 * ## 为什么不能改用 cdSize 折算
 *
 * 中央目录里每条 header 记录**至少** 46 字节，故 `cdSize / 46` 是条目数的**上界**
 * （`N ≤ cdSize / 46`），不是下界。把它与自报值取大者虽然堵住了上面的漏判，
 * 却会**误拒合法归档**：文件名占几十字节时每条记录远大于 46 字节，折算值成倍放大。
 *
 * 实测：条目名约 65 字节的合法 ZIP，9000 条目被折算成 22228，触发 20000 的上限而被
 * 拒绝 —— 报错文案还写着「条目数超过 20000」，与事实相反。而 1~3 万文件的大型 APK
 * 很常见，等效真实上限被压到 8000 条上下。
 *
 * ## 本实现：零分配的结构遍历
 *
 * 精确条目数其实不需要信任任何自报字段：**逐条走中央目录**即可。每条记录的下一条位置由
 * 其固定头里的三个长度字段（名长/extra 长/注释长）唯一确定，所以能从 `cdOffset` 一路
 * 顺序跳到 `cdOffset + cdSize`，数出**真实**条目数。
 *
 * 这个遍历只读字节、不构造任何对象，因此「预算前置」依然成立：
 *
 * ```
 * 逐条读 46 字节定长头 → 跳过变长部分 → 计数
 *     计数达到上限？→ 立即返回（零分配）
 *     走完 cdSize？ → 得到精确条目数
 * ```
 *
 * 既不漏判（完全不信自报字段），也不误判（给出精确值而非放大的上界）。
 */
internal object ZipEntryCountProbe {

    /** EOCD 固定部分长度。 */
    const val EOCD_MIN_SIZE = 22

    /** EOCD 注释字段最大长度（2 字节）。 */
    const val MAX_COMMENT_LENGTH = 0xFFFF

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_DIR_SIGNATURE = 0x02014b50

    /** 中央目录单条 header 记录的定长部分长度。 */
    const val CENTRAL_DIR_HEADER_SIZE = 46

    /** 读尾部所需的最大字节数：EOCD 固定部分 + 最长注释。 */
    const val TAIL_WINDOW = EOCD_MIN_SIZE + MAX_COMMENT_LENGTH

    /**
     * 允许遍历的中央目录字节上限。
     *
     * 每条记录最大 46 + 3×65535 ≈ 196KB，最坏情况「上限条数 × 单条最大体积」会逼近 4GB，
     * 必须设总量闸门。定 32MB 的依据：合法归档在 20000 条目内，平均每条只需 1.6KB
     * （定长头 46 + 名/extra/注释合计 1.6KB）就已用满 —— 真实归档的平均记录长度在
     * 100 字节量级，留了 300 倍余量。超出即视为异常构造，直接按超限拒绝。
     */
    const val MAX_WALK_BYTES = 32 * 1024 * 1024

    /**
     * 从**文件尾部字节**读出自报条目数。纯函数，便于 JVM 单测。
     *
     * @param tail 文件最后一段字节（调用方需保证覆盖 TAIL_WINDOW 或整个文件）
     * @param fileSize 文件总长度
     * @return 自报条目数；无法确定返回 null
     */
    fun entryCountFromTail(tail: ByteArray, fileSize: Long): Int? {
        val eocd = locateEocd(tail, fileSize) ?: return null
        val raw = readShortLE(tail, eocd + 10)
        // 0xFFFF == ZIP64 哨兵，真实值在 ZIP64 扩展记录里；本项目上限远低于 65535，
        // 按超限处理既正确又省掉一套 ZIP64 解析。
        return raw
    }

    /**
     * 定位自洽的 EOCD。
     *
     * 不用「EOCD 结束位置必须正好等于文件长度」的严格判据：zip4j 2.11.1
     * `HeaderReader.locateOffsetOfEndOfCentralDirectoryByReverseSeek` 反向逐字节找签名后
     * **直接返回，不做 EOF 对齐或注释长度校验**（已用 javap 核对字节码）。所以「真 EOCD
     * 之后还挂着垃圾字节」的 zip（拼接下载、对齐填充、自定义工具追加）zip4j 能照常解析，
     * 严格判据会判成「非 zip」返回 null，预算检查被静默绕过 —— 正是本探针要防的事。
     *
     * 注释区可以伪造签名，因此要求候选**自洽**（起点 + 22 + 注释长度不超过文件长度），
     * 并在自洽候选里取**自报条目数最大**的那个（相同则取更靠后的，即反向扫描时先遇到的
     * 真 EOCD 之前的最后一个）。这样无论 zip4j 最终反查命中哪个候选，拿到的自报值都不
     * 小于它，预检因此偏保守而非漏判。
     */
    private fun locateEocd(tail: ByteArray, fileSize: Long): Int? {
        if (tail.size < EOCD_MIN_SIZE || fileSize < tail.size) return null
        var bestOffset: Int? = null
        var bestCount = -1
        var i = 0
        while (i + EOCD_MIN_SIZE <= tail.size) {
            if (readIntLE(tail, i) == EOCD_SIGNATURE) {
                val commentLength = readShortLE(tail, i + 20)
                val endExclusive = i.toLong() + EOCD_MIN_SIZE + commentLength.toLong()
                if (endExclusive <= fileSize) {
                    val count = readShortLE(tail, i + 10)
                    if (count >= bestCount) {
                        bestCount = count
                        bestOffset = i
                    }
                }
            }
            i++
        }
        return bestOffset
    }

    /**
     * 精确条目数：结构遍历中央目录，**不构造任何对象**。
     *
     * @param maxEntries 预算上限；计数达到它即提前返回，避免为超大归档空跑
     * @return 精确条目数；达到/超过 [maxEntries] 时返回不小于它的值；
     *         归档结构性损坏（遍历走不下去）时同样返回不小于上限的值（fail-closed），
     *         只有「根本不是 ZIP」才返回 null 交由后续解析器报错
     */
    fun exactEntryCount(file: File, maxEntries: Int): Int? {
        if (!file.isFile) return null
        val fileSize = file.length()
        if (fileSize < EOCD_MIN_SIZE) return null

        return RandomAccessFile(file, "r").use { raf ->
            val window = minOf(fileSize, TAIL_WINDOW.toLong()).toInt()
            val tail = ByteArray(window)
            raf.seek(fileSize - window)
            raf.readFully(tail)
            val eocd = locateEocd(tail, fileSize) ?: return@use null

            // 自报值只作廉价预筛：达到上限就不必再遍历，也避免为明显超限的归档付 I/O。
            val selfReported = readShortLE(tail, eocd + 10)
            if (selfReported >= maxEntries) return@use maxEntries

            val rawSize = readIntLE(tail, eocd + 12)
            val rawOffset = readIntLE(tail, eocd + 16)
            // 负值 = 对应字段是 0xFFFFFFFF，即 ZIP64：真实偏移/体积在 ZIP64 扩展记录里，
            // 本实现不解析那套结构。自报值又已经被攻击者控制，此时回退它等于把预算检查
            // 交给对方 → 直接按超限拒绝。
            if (rawSize < 0 || rawOffset < 0) return@use maxEntries
            if (rawSize == 0) return@use 0

            val cdSize = rawSize.toLong()
            val cdOffset = rawOffset.toLong() and 0xFFFFFFFFL
            // 越界的中央目录是自相矛盾的构造；自报值不可信，只能保守拒绝。
            if (cdOffset > fileSize || cdOffset + cdSize > fileSize) return@use maxEntries

            // 体积闸门：每条记录最大约 196KB，「上限条数 × 单条最大」会逼近 4GB。
            // 32MB 对应平均每条 1.6KB，真实归档平均在 100 字节量级。
            if (cdSize > MAX_WALK_BYTES) return@use maxEntries

            var pos = cdOffset
            val cdEnd = cdOffset + cdSize
            val header = ByteArray(CENTRAL_DIR_HEADER_SIZE)
            var count = 0
            while (pos < cdEnd) {
                if (count >= maxEntries) return@use maxEntries
                if (pos + CENTRAL_DIR_HEADER_SIZE > cdEnd) return@use maxEntries
                raf.seek(pos)
                raf.readFully(header)
                if (readIntLE(header, 0) != CENTRAL_DIR_SIGNATURE) {
                    // 中央目录里出现非 header 签名 = 结构性损坏。zip4j 的
                    // readCentralDirectoryHeader 在同一位置也会抛
                    // ZipException("Invalid central directory file header signature")，
                    // 即它不会再分配对象；这里仍按超限返回，不依赖对下游行为的假设。
                    return@use maxEntries
                }
                val nameLen = readShortLE(header, 28)
                val extraLen = readShortLE(header, 30)
                val commentLen = readShortLE(header, 32)
                val next = pos + CENTRAL_DIR_HEADER_SIZE + nameLen + extraLen + commentLen
                if (next > cdEnd) return@use maxEntries
                pos = next
                count++
            }
            count
        }
    }

    /**
     * 读取归档尾部并返回条目数；非 ZIP / 读失败返回 null（交由后续解析器报更准确的错）。
     *
     * 只在自报值不足以定论时才遍历中央目录，因此对绝大多数正常归档仍是「读末尾 64KB」。
     */
    fun probe(file: File, maxEntries: Int): Int? {
        if (!file.isFile || file.length() < EOCD_MIN_SIZE) return null
        return runCatching { exactEntryCount(file, maxEntries) }.getOrNull()
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
}
