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
 * ## 必须与 zip4j 的实际解析口径对齐（zip4j 2.11.6，javap 核对）
 *
 * - `locateOffsetOfEndOfCentralDirectory`：先试 `len-22`，不中则**反向逐字节找签名并直接返回**，
 *   **不校验注释长度、不要求 EOCD 结束于 EOF**；最低覆盖到 `len-65558`。
 *   因此「zip4j 会采用哪个候选」= **尾部窗口内最后一处 EOCD 签名**。
 * - `readZipComment` 把 `readFully` 的 IOException 吞成 null —— EOCD 的注释长度字段可以被
 *   任意撒谎而 zip4j 照常解析。**所以注释长度不能用来筛掉候选**：筛掉一个 zip4j 仍会解析的
 *   候选，等于把预算检查交给攻击者（探针返回 null 时调用方直接放行）。
 * - `readCentralDirectory` 是 `for (i = 0; i < count; i++)` **计数驱动**，`sizeOfCentralDirectory`
 *   全程不参与，`count` 取自 EOCD（非 ZIP64 上限 65535）或 ZIP64 记录（8 字节，无界）。
 *   故真正的 OOM 边界 = **zip4j 采用的那个候选声明的计数**。
 *
 * ## 为什么不能只读尾部自报字段
 *
 * EOCD 里的「本盘/总条目数」是**攻击者可控**的：中央目录实际写上万条 header 记录、
 * 该字段写 1，只信它就得到 1 → 放行。结构遍历（逐条走中央目录、不构造任何对象）
 * 给出精确值，是「自报值撒谎」的兜底；它不能替代上面的计数预筛，因为候选可能走不动而
 * zip4j 仍按声明的 count 分配。
 *
 * ## 为什么不能改用 cdSize 折算
 *
 * 中央目录里每条 header 记录**至少** 46 字节，故 `cdSize / 46` 是条目数的**上界**
 * （`N ≤ cdSize / 46`），不是下界。把它与自报值取大者虽然堵住了上面的漏判，
 * 却会**误拒合法归档**：条目名占几十字节时每条记录远大于 46 字节，折算值成倍放大。
 *
 * 实测：条目名约 65 字节的合法 ZIP，9000 条目被折算成 22228，触发 20000 的上限而被
 * 拒绝 —— 报错文案还写着「条目数超过 20000」，与事实相反。而 1~3 万文件的大型 APK
 * 很常见，等效真实上限被压到 8000 条上下。
 *
 * ## 本实现
 *
 * ```
 * 读尾部窗口 → 收集**全部** EOCD 签名位置（不按注释长度过滤）
 *     最后一个签名（zip4j 唯一会采用的候选）是 ZIP64 哨兵？→ 无法核验 → 按超限拒绝
 *     逐候选：自报计数 ≥ 上限？→ 按超限拒绝
 *             计数字段是 ZIP64 哨兵？→ 非最后一处则跳过（它是包内归档的 EOCD）
 *             结构遍历能走通 → 精确条目数，取各候选最大值
 *             走不动 → 跳过（zip4j 在该候选上同样读不出 header；且它的计数已过预筛）
 * ```
 *
 * 既误拒（内嵌归档的 EOCD 不再否决外层），也不漏判（zip4j 真正采用的那个候选一旦
 * 不可核验就 fail-closed；撒谎的计数由结构遍历兜底）。
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

    /**
     * 读尾部所需的最大字节数：EOCD 固定部分 + 最长注释 + 1。
     *
     * **那个 +1 不是随手加的**：zip4j 的反查从 `len-23` 起最多 65536 次，最低能命中
     * `len-65558`。窗口只取 65557 时，尾随垃圾正好 65536 字节的归档会落在
     * 「zip4j 命中、探针看不到」的那一字节夹缝里 —— 探针返回 null、调用方直接放行，
     * 而 zip4j 照常解析出全部条目（实测：21000 条归档 + 65536 字节尾随垃圾，
     * zip4j 读满 21000 个头，探针返回 null）。
     */
    const val TAIL_WINDOW = EOCD_MIN_SIZE + MAX_COMMENT_LENGTH + 1

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
     * 只采信**自洽**的候选（起点 + 22 + 注释长度不越出窗口）：本函数只负责读「一个
     * 可信 EOCD 的自报值」，越界注释的候选按伪造处理。真正的预算判定不用它。
     *
     * @param tail 文件最后一段字节（调用方需保证覆盖 TAIL_WINDOW 或整个文件）
     * @param fileSize 文件总长度
     * @return 自报条目数；无法确定返回 null
     */
    fun entryCountFromTail(tail: ByteArray, fileSize: Long): Int? {
        if (tail.size < EOCD_MIN_SIZE || fileSize < tail.size) return null
        val eocd = locateEocd(tail) ?: return null
        val raw = readShortLE(tail, eocd + 10)
        // 0xFFFF == ZIP64 哨兵，真实值在 ZIP64 扩展记录里；本项目上限远低于 65535，
        // 按超限处理既正确又省掉一套 ZIP64 解析。
        return raw
    }

    /** 自洽候选里自报条目数最大的那个（相同取更靠后的）。仅供 [entryCountFromTail]。 */
    private fun locateEocd(tail: ByteArray): Int? {
        var bestOffset: Int? = null
        var bestCount = -1
        for (i in locateSignatureOffsets(tail)) {
            // 自洽判据必须落在**窗口内**：`i` 是 tail 内偏移，故只能与 tail.size 比较。
            if (i + EOCD_MIN_SIZE + readShortLE(tail, i + 20) > tail.size) continue
            val count = readShortLE(tail, i + 10)
            if (count >= bestCount) {
                bestCount = count
                bestOffset = i
            }
        }
        return bestOffset
    }

    /**
     * 窗口内**全部** EOCD 签名位置（升序）。
     *
     * 刻意**不**按注释长度过滤：zip4j 的 `readZipComment` 把越界注释的 IOException 吞成
     * null，注释字段撒谎的 EOCD 它照样采用。过滤掉这些候选 = 让 [exactEntryCount] 对
     * zip4j 会解析的归档返回 null，预算检查被静默绕过。
     */
    private fun locateSignatureOffsets(tail: ByteArray): List<Int> {
        if (tail.size < EOCD_MIN_SIZE) return emptyList()
        val out = ArrayList<Int>(2)
        var i = 0
        while (i + EOCD_MIN_SIZE <= tail.size) {
            if (readIntLE(tail, i) == EOCD_SIGNATURE) out += i
            i++
        }
        return out
    }

    /**
     * 单个候选的遍历结果。
     *
     * 必须区分「超限」与「走不动」：前者要立刻拒绝整个归档（真超限 / 自报值撒谎），
     * 后者只说明**这个候选不是外层 EOCD**（典型：包内 STORE 的 APK/ZIP 自带 EOCD），
     * 跳过它即可，不能因此把外层判成超限。
     */
    private sealed interface WalkOutcome {
        /** 走完中央目录，得到精确条目数；[walkedBytes] 计入共享遍历预算。 */
        data class Counted(val entries: Int, val walkedBytes: Long) : WalkOutcome
        /** 真实条目数已达上限，或中央目录体积超出遍历闸门 —— 立即按超限拒绝。 */
        object OverLimit : WalkOutcome
        /** 该候选的中央目录走不动（签名不符 / 越界 / ZIP64）—— 只跳过这一条。 */
        object Unwalkable : WalkOutcome
    }

    /**
     * 精确条目数：结构遍历中央目录，**不构造任何对象**。
     *
     * @param maxEntries 预算上限；计数达到它即提前返回，避免为超大归档空跑
     * @return 精确条目数；达到/超过 [maxEntries] 时返回不小于它的值；
     *         归档结构性损坏或 zip4j 采用的候选无法核验时同样返回不小于上限的值（fail-closed），
     *         只有「尾部窗口内根本没有 EOCD 签名」才返回 null 交由后续解析器报错
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
            val candidates = locateSignatureOffsets(tail)
            // 窗口内没有任何 EOCD 签名：不是 ZIP（或尾部已被破坏），交给下游解析器报真错。
            if (candidates.isEmpty()) return@use null

            // zip4j 反向找签名后**直接返回**，因此窗口内**最后一处**签名才是它会采用的候选。
            // 这个候选若无法核验（ZIP64 哨兵：真实计数/偏移在不解析的扩展记录里），
            // 必须 fail-closed —— 跳过它等于把预算检查交给包里那条小归档。
            val latest = candidates.last()
            if (readIntLE(tail, latest + 12) < 0 || readIntLE(tail, latest + 16) < 0) {
                return@use maxEntries
            }

            // 语义：每个能走通的候选都要数一遍，取最大值 —— 无论最终哪个候选生效，
            // 分配量都不会超过我们已核过的数。走不通的候选不是「坏包」，只是「这不是外层 EOCD」。
            var best: Int? = null
            var budget = MAX_WALK_BYTES.toLong()
            for (eocd in candidates) {
                if (budget <= 0L) return@use maxEntries
                val outcome = walkCentralDirectory(raf, tail, eocd, maxEntries, eocd == latest)
                when (outcome) {
                    is WalkOutcome.OverLimit -> return@use maxEntries
                    is WalkOutcome.Unwalkable -> Unit
                    is WalkOutcome.Counted -> {
                        budget -= outcome.walkedBytes
                        val prev = best
                        if (prev == null || outcome.entries > prev) best = outcome.entries
                    }
                }
            }
            // 所有候选都走不动 = 结构不可解析；自报值不可信，只能 fail-closed。
            best ?: maxEntries
        }
    }

    /** 从 [eocd] 这个候选出发，结构遍历它自己的中央目录。 */
    private fun walkCentralDirectory(
        raf: RandomAccessFile,
        tail: ByteArray,
        eocd: Int,
        maxEntries: Int,
        isLatest: Boolean
    ): WalkOutcome {
        // 自报值只作廉价预筛：达到上限就不必再遍历，也避免为明显超限的归档付 I/O。
        val selfReported = readShortLE(tail, eocd + 10)
        if (selfReported >= maxEntries) return WalkOutcome.OverLimit

        val rawSize = readIntLE(tail, eocd + 12)
        val rawOffset = readIntLE(tail, eocd + 16)
        // 负值 = 字段是 0xFFFFFFFF，即 ZIP64：真实偏移/体积在 ZIP64 扩展记录里，本实现不解析。
        // 最后一处签名已在上层按超限拒绝；走到这里的只可能是包内归档的 EOCD，跳过安全
        //（它声明的计数已过上面的预筛，zip4j 即使采用它也只会分配 < maxEntries 个头）。
        if (rawSize < 0 || rawOffset < 0) {
            return if (isLatest) WalkOutcome.OverLimit else WalkOutcome.Unwalkable
        }
        if (rawSize == 0) return WalkOutcome.Counted(0, 0L)

        val cdSize = rawSize.toLong()
        val cdOffset = rawOffset.toLong() and 0xFFFFFFFFL
        // 越界的中央目录属于自相矛盾的构造：这个候选无效，换下一个。
        if (cdOffset > raf.length() || cdOffset + cdSize > raf.length()) {
            return if (isLatest) WalkOutcome.OverLimit else WalkOutcome.Unwalkable
        }
        // 体积闸门：每条记录最大约 196KB，「上限条数 × 单条最大」会逼近 4GB。
        // 32MB 对应平均每条 1.6KB，真实归档平均在 100 字节量级。
        if (cdSize > MAX_WALK_BYTES) return WalkOutcome.OverLimit

        val header = ByteArray(CENTRAL_DIR_HEADER_SIZE)
        var pos = cdOffset
        val cdEnd = cdOffset + cdSize
        var count = 0
        while (pos < cdEnd) {
            if (count >= maxEntries) return WalkOutcome.OverLimit
            if (pos + CENTRAL_DIR_HEADER_SIZE > cdEnd) return WalkOutcome.Unwalkable
            raf.seek(pos)
            raf.readFully(header)
            if (readIntLE(header, 0) != CENTRAL_DIR_SIGNATURE) {
                // 非 header 签名：这个候选的中央目录不在它声称的位置（典型为包内归档的
                // EOCD）。跳过；zip4j 在这个候选上同样读不出 header。
                return WalkOutcome.Unwalkable
            }
            val nameLen = readShortLE(header, 28)
            val extraLen = readShortLE(header, 30)
            val commentLen = readShortLE(header, 32)
            val next = pos + CENTRAL_DIR_HEADER_SIZE + nameLen + extraLen + commentLen
            if (next > cdEnd) return WalkOutcome.Unwalkable
            pos = next
            count++
        }
        return WalkOutcome.Counted(count, cdSize)
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
