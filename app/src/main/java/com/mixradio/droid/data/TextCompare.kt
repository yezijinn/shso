// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 文本对比工具（10 万行级优化实现）。
 *
 * 性能设计：
 *  1. **零拷贝读取**：`FileChannel.map(READ_ONLY)` 得到 MappedByteBuffer；
 *     ROOT 不可直读的文件先 `cat` 到可读临时文件（一次性顺序拷贝，远快于 base64 管道），
 *     之后同样走 mmap，避免把全文读进 Java 堆。
 *  2. **字节级行解析**：手动扫描 `\n`(10) 字节定位行边界，行尾 `\r` 自动剔除（兼容 CRLF），
 *     绝不使用 `readLine()` / `split("\n")`，杜绝逐行对象分配。
 *  3. **逐行对比不解码**：先做定长字节比较（长度不等直接判否），只有命中的差异行才解码成 String，
 *     因此无差异的大文件几乎零分配。
 *  4. **重复对比按行数选择**：先字节扫描统计行数，行数较少者构建 `HashSet<String>`（按行数预设容量，
 *     避免 rehash），再流式扫描较大者查重；结果用 LinkedHashSet 去重并保持出现顺序。
 *  5. **单线程 Dispatchers.IO**：无锁竞争、无线程切换；结果先入 StringBuilder，最后一次性落盘。
 *  6. **释放**：映射引用显式置 null 并 close channel，结束时建议一次 GC。
 */
object TextCompare {

    enum class Mode(val displayName: String, val desc: String) {
        LINE_BY_LINE("逐行对比", "按行号一一对应，输出内容相同的行"),
        COMMON_LINES("重复对比", "忽略行号，输出共同行（去重，纯文本）")
    }

    data class Result(
        val outPath: String,
        val processedLines: Long,
        val hitLines: Int,
        val elapsedMs: Long,
        val cancelled: Boolean
    )

    class CompareException(message: String) : Exception(message)

    private const val LF: Byte = 10
    private const val CR: Byte = 13
    private const val PROGRESS_STEP = 5000L
    /** 编码探测取样字节数：与编辑器首块同量级，足以判定 BOM / NUL 分布 / 可解码性。 */
    private const val CHARSET_PROBE_BYTES = 1 shl 20
    private const val MISSING = "<缺失>"

    /**
     * 执行对比（必须在 Dispatchers.IO 调用）。
     *
     * @param tempDir 可读写的临时目录（ROOT 复制中转用，建议 context.cacheDir）
     */
    fun run(
        tempDir: String,
        pathA: String,
        pathB: String,
        outPath: String,
        charset: Charset,
        mode: Mode,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean
    ): Result {
        val t0 = System.currentTimeMillis()
        var srcA: MappedFile? = null
        var srcB: MappedFile? = null
        val sb = StringBuilder(1 shl 16)
        var processed = 0L
        var hits = 0
        var cancelled = false

        try {
            srcA = MappedFile.open(tempDir, pathA)
            srcB = MappedFile.open(tempDir, pathB)

            writeHeader(sb, mode, pathA, pathB)

            when (mode) {
                Mode.LINE_BY_LINE -> {
                    // 仅输出「内容相同的行」：行号 + 行内容；不相同的全部排除。
                    // 每个文件用**自己**探测出的字符集解码（见 MappedFile.detectedCharset）
                    val sa = LineScanner(srcA, srcA.detectedCharset)
                    val sbScan = LineScanner(srcB, srcB.detectedCharset)
                    var lineNo = 0L
                    var hasA = sa.next()
                    var hasB = sbScan.next()
                    while (hasA || hasB) {
                        lineNo++
                        processed = lineNo
                        if (lineNo % PROGRESS_STEP == 0L) {
                            if (isCancelled()) { cancelled = true; break }
                            onProgress(lineNo)
                        }
                        val same = hasA && hasB && sa.length == sbScan.length &&
                            bytesEqual(sa.buffer, sa.start, sa.length, sbScan.buffer, sbScan.start, sbScan.length)
                        if (same && hasA) {
                            hits++
                            sb.append('[').append(lineNo).append(']').append('\t').append(sa.text()).append('\n')
                        }
                        hasA = if (hasA) sa.next() else false
                        hasB = if (hasB) sbScan.next() else false
                    }
                    if (!cancelled) {
                        sb.append('\n').append("# 相同行: ").append(hits)
                            .append("    已比较行: ").append(processed).append('\n')
                    }
                }

                Mode.COMMON_LINES -> {
                    val linesA = srcA.countLines()
                    val linesB = srcB.countLines()
                    // 行数较少者作为「基准文件」：构建行集合，并同时记录每行最小行号
                    val (small, large) = if (linesA <= linesB) srcA to srcB else srcB to srcA
                    val cap = hashCapacity(minOf(linesA, linesB).coerceAtLeast(16))
                    val setSmall = HashSet<String>(cap)
                    val minInSmall = HashMap<String, Int>(cap)

                    val ss = LineScanner(small, small.detectedCharset)
                    var n = 0L
                    var lineNo = 0
                    while (ss.next()) {
                        n++; lineNo++
                        val line = ss.text()
                        setSmall.add(line)
                        val prev = minInSmall[line]
                        if (prev == null || lineNo < prev) minInSmall[line] = lineNo
                        if (n % PROGRESS_STEP == 0L) {
                            if (isCancelled()) { cancelled = true; break }
                            onProgress(n)
                        }
                    }
                    if (!cancelled) {
                        // 大文件流式扫描：命中小文件集合者即为共同行；记录全局最小行号（两文件取最小）
                        val resultMin = HashMap<String, Int>(hashCapacity(minInSmall.size.coerceAtLeast(16)))
                        val ls = LineScanner(large, large.detectedCharset)
                        lineNo = 0
                        while (ls.next()) {
                            n++; lineNo++
                            processed = n
                            if (n % PROGRESS_STEP == 0L) {
                                if (isCancelled()) { cancelled = true; break }
                                onProgress(n)
                            }
                            val line = ls.text()
                            if (setSmall.contains(line)) {
                                val cand = minOf(minInSmall[line] ?: Int.MAX_VALUE, lineNo)
                                val prev = resultMin[line]
                                if (prev == null || cand < prev) resultMin[line] = cand
                            }
                        }
                        if (!cancelled) {
                            // 内部按全局最小行号去重并排序；输出纯原文，不带行号。
                            val ordered = resultMin.entries.sortedBy { it.value }
                            ordered.forEach { (content, _) ->
                                sb.append(content).append('\n'); hits++
                            }
                            sb.append('\n').append("# 共同行(去重,最小行号): ").append(hits)
                                .append("    已扫描行: ").append(processed).append('\n')
                        }
                    }
                }
            }
        } catch (e: CompareException) {
            throw e
        } catch (e: Exception) {
            throw CompareException("对比失败: ${e.message}")
        } finally {
            runCatching { srcA?.close() }
            runCatching { srcB?.close() }
            srcA = null
            srcB = null
        }

        val elapsed = System.currentTimeMillis() - t0
        if (cancelled) return Result(outPath, processed, hits, elapsed, true)
        writeResult(outPath, charset, sb)
        // 映射已 close，此处仅建议一次 GC 促使 DirectByteBuffer 关联内存尽快回收
        System.gc()
        return Result(outPath, processed, hits, elapsed, false)
    }

    private fun hashCapacity(expected: Int): Int = (expected / 0.75f).toInt() + 1

    //  行解析 / 字节比较

    /** 定长字节比较：逐行对比的主路径，避免解码开销。 */
    private fun bytesEqual(
        a: MappedByteBuffer, aStart: Int, aLen: Int,
        b: MappedByteBuffer, bStart: Int, bLen: Int
    ): Boolean {
        var i = 0
        while (i < aLen) {
            if (a[aStart + i] != b[bStart + i]) return false
            i++
        }
        return true
    }

    /** 行游标：扫描 `\n` 定位行边界，行尾 `\r` 剔除；复用单个 ByteBuffer 视图解码。 */
    private class LineScanner(src: MappedFile, private val charset: Charset) {
        val buffer: MappedByteBuffer = src.buffer
        private val size: Int = src.size
        private val view: MappedByteBuffer = src.buffer.duplicate() as MappedByteBuffer
        private var pos = src.dataStart
        var start = 0
            private set
        var length = 0
            private set

        fun next(): Boolean {
            if (pos >= size) return false
            var i = pos
            while (i < size && buffer[i] != LF) i++
            var end = i
            if (end > pos && buffer[end - 1] == CR) end--
            start = pos
            length = end - pos
            pos = i + 1
            return true
        }

        fun text(): String {
            view.limit(size)
            view.position(start)
            view.limit(start + length)
            return charset.decode(view).toString()
        }
    }

    //  内存映射文件源
    private class MappedFile private constructor(
        private val channel: FileChannel,
        val buffer: MappedByteBuffer,
        val size: Int,
        /** 数据起始偏移：UTF-8 BOM(EF BB BF) 存在时跳过前 3 字节 */
        val dataStart: Int,
        private val tmp: String?
    ) {
        /**
         * 本文件**自身**的字符集（按首块内容探测）。
         *
         * 不能沿用调用方传入的「当前文件 A 的字符集」：`run()` 用同一个 charset 实例
         * 同时扫 A、B 并落盘。选文件对话框只按**后缀**过滤、不校验编码，于是
         * UTF-8 的 A 配 GB18030 的 B 时，B 的字节全被解成 U+FFFD，与 A 的行集合零交集 ——
         * 提示「对比完成，共同 **0** 行」，是**静默错误结果**，用户会据此认为两文件毫无共同内容。
         */
        val detectedCharset: Charset by lazy {
            if (dataStart >= size) return@lazy Charsets.UTF_8
            val n = minOf(CHARSET_PROBE_BYTES, size - dataStart)
            val head = ByteArray(n)
            val dup = buffer.duplicate()
            dup.position(dataStart)
            dup.get(head, 0, n)
            CharsetDetector.detect(head).charset
        }

        companion object {
            fun open(tempDir: String, path: String): MappedFile {
                // ① APP 自身可读 → 直接 mmap（零拷贝，最快路径）
                // 大小守卫必须**逃出 runCatching**：`CompareException` 原本被包在里面，
                // 抛出会被 `runCatching` 吞掉并继续往下走，于是「文件过大」这条分支
                // 实际不可达，>2GB 的文件仍会进 ROOT 分支被整份 cat 进 cacheDir ——
                // 白占数 GB 空间、可能把缓存写满，且最终报的是「无法读取文件」而非「过大」。
                if (File(path).isFile && File(path).canRead()) {
                    val file = File(path)
                    val ch = FileInputStream(file).channel
                    val len = runCatching { ch.size() }.getOrElse {
                        runCatching { ch.close() }
                        throw CompareException("无法获取文件大小: ${file.name}")
                    }
                    if (len > Int.MAX_VALUE) {
                        ch.close()
                        throw CompareException("文件过大（>${Int.MAX_VALUE / 1024 / 1024}MB）: ${file.name}")
                    }
                    if (len > 0L) {
                        val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, len) as MappedByteBuffer
                        return MappedFile(ch, buf, len.toInt(), bomOffset(buf, len.toInt()), null)
                    }
                    ch.close()
                }
                // ② ROOT：先 cat 到可读临时文件（顺序直拷），再 mmap
                if (RootService.isRootGranted == true) {
                    // 同样的守卫必须在 cat **之前**：否则超限文件已经被整份拷进 cacheDir
                    val (statCode, statOut) = RootService.runCommandSync(
                        "stat -c %s ${RootService.escapeShellArg(path)} 2>/dev/null", 10_000L
                    )
                    val declared = if (statCode == 0) statOut.trim().toLongOrNull() else null
                    if (declared != null && declared > Int.MAX_VALUE) {
                        throw CompareException(
                            "文件过大（>${Int.MAX_VALUE / 1024 / 1024}MB）: ${File(path).name}"
                        )
                    }
                    val tmp = "$tempDir/_shso_cmp_${System.currentTimeMillis()}.tmp"
                    val (code, err) = RootService.runCommandSync(
                        "cat ${RootService.escapeShellArg(path)} > ${RootService.escapeShellArg(tmp)}; " +
                            "chmod 644 ${RootService.escapeShellArg(tmp)}",
                        180_000L
                    )
                    if (code != 0) {
                        runCatching { File(tmp).delete() }
                        throw CompareException("无法读取文件: ${File(path).name}${err.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}")
                    }
                    val ch = FileInputStream(tmp).channel
                    val len = ch.size()
                    // 进入「已产出 tmp」阶段后，任何失败都必须清掉临时文件：
                    // 成功路径由 MappedFile.close() 负责删除（tmp 会随对象一起传给调用方）；
                    // 下方 throw 与 mmap 异常也必须删除临时文件，否则大文件对比残留的
                    // _shso_cmp_*.tmp 不易被察觉、持续占用空间。
                    try {
                        if (len <= 0L) { ch.close(); throw CompareException("文件为空或不可读: ${File(path).name}") }
                        if (len > Int.MAX_VALUE) {
                            ch.close()
                            throw CompareException("文件过大（>${Int.MAX_VALUE / 1024 / 1024}MB）: ${File(path).name}")
                        }
                        val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, len) as MappedByteBuffer
                        return MappedFile(ch, buf, len.toInt(), bomOffset(buf, len.toInt()), tmp)
                    } catch (e: Throwable) {
                        runCatching { File(tmp).delete() }
                        throw e
                    }
                }
                throw CompareException("无法读取文件: $path")
            }

            /** UTF-8 BOM 检测：EF BB BF → 数据从偏移 3 开始。 */
            private fun bomOffset(buf: MappedByteBuffer, size: Int): Int {
                if (size >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) return 3
                return 0
            }
        }

        fun countLines(): Int {
            if (dataStart >= size) return 0
            var count = 1
            var i = dataStart
            while (i < size) {
                if (buffer[i] == LF) count++
                i++
            }
            if (buffer[size - 1] == LF) count--
            return count
        }

        fun close() {
            runCatching { channel.close() }
            tmp?.let { runCatching { File(it).delete() } }
        }
    }

    //  输出
    private fun writeHeader(sb: StringBuilder, mode: Mode, pathA: String, pathB: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        sb.append("# 文本对比结果\n")
        sb.append("# 时间: ").append(stamp).append('\n')
        sb.append("# 模式: ").append(mode.displayName).append('（').append(mode.desc).append("）\n")
        sb.append("# 文件1: ").append(pathA).append('\n')
        sb.append("# 文件2: ").append(pathB).append('\n')
        sb.append('\n')
    }

    /** 结果一次性落盘（ROOT 走统一出口 writeBytesAsRoot + mv，随后放开读权限便于 APP 再次打开）。 */
    private fun writeResult(path: String, charset: Charset, sb: StringBuilder) {
        val bytes = sb.toString().toByteArray(charset)
        if (RootService.isRootGranted == true) {
            // 临时名必须**不可预测**：`/data/local/tmp` 是 1777，任意应用都能在里面建文件。
            // 原用 `System.currentTimeMillis()`，攻击者可读 `/proc/uptime` 推出秒级窗口后
            // 批量预置同名**悬空软链**（指向 /data/data/…/shared_prefs、/system/etc/hosts 等），
            // 而 writeBytesAsRoot 是 `cat > <path>` —— `>` 跟随软链，
            // 于是对比结果文本被 root 写进攻击者指定的任意路径。
            // 与 ApkInstaller 的 OBB 临时文件、审计轮转同一口径：UUID 命名。
            val tmp = "/data/local/tmp/_shso_diff_${java.util.UUID.randomUUID()}.tmp"
            val ok = RootService.writeBytesAsRoot(tmp, bytes)
            try {
                if (!ok) throw CompareException("写入结果失败")
                // 复核：临时文件必须是我们刚写的普通文件，不能是软链
                val (chk, _) = RootService.runCommandSync(
                    "[ -f ${RootService.escapeShellArg(tmp)} ] && " +
                        "[ ! -L ${RootService.escapeShellArg(tmp)} ]", 10_000L
                )
                if (chk != 0) throw CompareException("临时结果文件异常，已中止")
                val (code, out) = RootService.runCommandSync(
                    "mv ${RootService.escapeShellArg(tmp)} ${RootService.escapeShellArg(path)}", 60_000L
                )
                if (code != 0) throw CompareException("移动结果文件失败: ${out.trim().ifEmpty { "未知错误" }}")
            } finally {
                runCatching { RootService.runCommandSync("rm -f ${RootService.escapeShellArg(tmp)}", 10_000L) }
            }
        } else {
            // 非 root 也必须原子写：直接 `FileOutputStream(path)` 会先截断目标，
            // 写中进程被杀 / OOM 就只剩前缀；且 path 为软链时会跟着改写真身。
            // 与 writeTextFile 的非 root 分支同法：同目录临时名 + 原子 rename。
            val target = File(path)
            val tmpFile = File(target.parentFile ?: File("."), ".${target.name}.shso_tmp")
            try {
                FileOutputStream(tmpFile).use { it.write(bytes) }
                if (!tmpFile.renameTo(target)) {
                    tmpFile.copyTo(target, overwrite = true)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                runCatching { tmpFile.delete() }
                throw CompareException("写入结果失败: ${e.message ?: "未知错误"}")
            }
        }
        if (RootService.isRootGranted == true) {
            runCatching { RootService.runCommandSync("chmod 644 ${RootService.escapeShellArg(path)}", 10_000L) }
        }
    }

    //  命名与过滤

    /** 结果文件名：`文本对比_YYYYMMDD_n<ext>`，n 从 0 起按目录内已存在文件递增。 */
    suspend fun nextOutputPath(dir: String, currentFilePath: String): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val prefix = "文本对比_${date}_"
        val ext = currentFilePath.substringAfterLast('.', "").let {
            if (it.isEmpty() || it == currentFilePath) "" else ".$it"
        }
        val base = if (dir.endsWith("/")) dir else "$dir/"
        // 逐个 `test -e` 递增探测，**不依赖 listFiles**：
        // listFiles 在 root 通道下对 stat 失败不作成败判断（su 被拒/超时就返回空列表），
        // 原实现 `getOrDefault(emptySet())` 把「探测失败」当成「目录为空」，
        // 于是 outPath 与上一次的对比结果同名，末尾的 `mv` 直接无声覆盖。
        val rootGranted = RootService.isRootGranted == true
        var n = 0
        while (true) {
            val candidate = "$base$prefix$n$ext"
            val exists = if (rootGranted) {
                RootService.runCommandSync(
                    "test -e ${RootService.escapeShellArg(candidate)}", 10_000L
                ).first == 0
            } else {
                runCatching { File(candidate).exists() }.getOrDefault(false)
            }
            if (!exists) return candidate
            n++
            if (n >= MAX_OUTPUT_PROBE) {
                throw CompareException("同名对比结果过多（>${MAX_OUTPUT_PROBE}），请手动指定输出名")
            }
        }
    }

    /** 同名输出文件的重名探测上限，避免异常目录导致无限递增。 */
    private const val MAX_OUTPUT_PROBE = 1000

    /** 同后缀（忽略大小写）且不是文件自身。用于文件选择器过滤。 */
    fun sameExtensionFilter(currentPath: String): (FileItem) -> Boolean {
        val cur = canonical(currentPath)
        val ext = extensionOf(currentPath)
        return { item ->
            if (item.isDirectory) true
            else if (canonical(item.path) == cur) false
            else extensionOf(item.name) == ext
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").let {
            if (it.isEmpty() || it == name) "" else it.lowercase(Locale.ROOT)
        }

    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)
}
