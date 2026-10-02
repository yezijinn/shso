// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * 压缩包智能解压。
 *
 * 支持格式：归档型 zip / 7z / tar / tgz / tar.gz / tar.xz / tar.bz2 / tar.lz4，
 * 单文件压缩型 gz / xz / bz2 / lz4（直接解压为去后缀原文件名）。
 * rar 为专有商业格式不支持；zstd（.zst）已移除：原生库占 release 包近半体积，与使用场景不匹配。
 *
 * 智能解压：根目录仅 1 个顶层目录（条件 A）→ 直接解压到当前目录（剥离顶层前缀避免嵌套）；
 * 否则（条件 B）→ 在当前目录新建「压缩包名（去后缀）」文件夹解压。重名冲突自动追加 _N。
 *
 * 加密包（zip 条目加密 / 7z 头加密）返回 [ExtractResult.NeedPassword] 由 UI 收集密码重试；
 * zip 用 zip4j（ZipCrypto + WinZip AES），7z 用 commons-compress SevenZFile.Builder。
 */
object ArchiveExtractor {

    /** 防压缩炸弹预算：外部唤起与手动解压共用，避免小包无限膨胀耗尽存储。 */
    internal const val MAX_EXTRACT_BYTES = 1L * 1024 * 1024 * 1024
    internal const val MAX_EXTRACT_ENTRY_BYTES = 512L * 1024 * 1024
    internal const val MAX_EXTRACT_ENTRIES = 20_000

    /**
     * 连续零读（`read()` 返回 0）的容忍上限。
     *
     * 单次 0 读可能是压缩流分块边界的正常现象；连续多次说明底层流已无法推进，
     * 再循环下去就是死循环。取 64 次足以覆盖任何合法分块。
     */
    private const val MAX_ZERO_READS = 64

    internal class ExtractionBudget {
        var entries = 0
            private set
        var bytes = 0L
            private set

        fun beginEntry(declaredSize: Long? = null) {
            if (entries >= MAX_EXTRACT_ENTRIES) {
                throw ExtractionLimitException("压缩包条目数超过 $MAX_EXTRACT_ENTRIES")
            }
            if (declaredSize != null && declaredSize >= 0L && declaredSize > MAX_EXTRACT_ENTRY_BYTES) {
                throw ExtractionLimitException("单个压缩条目超过 ${MAX_EXTRACT_ENTRY_BYTES / 1024 / 1024}MB")
            }
            entries++
        }

        fun consume(count: Int) {
            bytes += count.toLong()
            if (bytes > MAX_EXTRACT_BYTES) {
                throw ExtractionLimitException("解压总大小超过 ${MAX_EXTRACT_BYTES / 1024 / 1024 / 1024}GB")
            }
        }
    }

    internal class ExtractionLimitException(message: String) : Exception(message)

    /**
     * **预算前置**：在让 ZIP 解析器构造 central directory 之前，先用
     * [ZipEntryCountProbe] 读文件尾部 64KB 拿到条目数。
     *
     * 顺序很关键 —— 原来的 `zip.fileHeaders` 一行就已经把 N 万个 `FileHeader`
     * 对象分配进内存了，「条目数超过上限」的判断发生在**分配之后**，对
     * 「只靠条目数爆炸」的 zip bomb 完全无效。
     *
     * 探针读不到（空文件 / 非 ZIP / 截断）时返回 null 并放行给后续解析器，
     * 由它给出更准确的报错；这里只负责**提前拦住能提前判断的情况**。
     */
    internal fun probeZipEntryCountOrThrow(path: String) {
        val count = ZipEntryCountProbe.probe(File(path)) ?: return
        if (count >= MAX_EXTRACT_ENTRIES) {
            throw ExtractionLimitException("压缩包条目数超过 $MAX_EXTRACT_ENTRIES")
        }
    }

    /** 压缩包/文件类型分类。 */
    private enum class Kind { ZIP, SEVENZ, TAR, SINGLE }

    /** TAR 归档型扩展（含双后缀，判定优先于单文件压缩型）。 */
    private val TAR_EXTENSIONS = listOf(
        ".tar.gz", ".tar.xz", ".tar.bz2", ".tar.lz4",
        ".tgz", ".tar"
    )

    /** 单文件压缩型扩展。 */
    private val SINGLE_EXTENSIONS = listOf(".gz", ".xz", ".bz2", ".lz4")

    fun isKnownArchive(name: String): Boolean = kindOf(name) != null

    /** 所有已知格式均可解压（rar 已彻底移除）。 */
    fun isExtractable(name: String): Boolean = kindOf(name) != null

    /**
     * 识别压缩包类型；未知格式返回 null。
     *
     * 后缀比对必须用**区域无关**的小写化（Kotlin 的无参 `lowercase()`，等价 `Locale.ROOT`）。
     * 原先的 `lowercase(Locale.getDefault())` 在土耳其语等 locale 下会把 `I` 映射成
     * `ı`（点无 i），`.ZIP` → `.zıp`，于是**用户切换系统语言后所有 zip 都识别不出来**。
     * 同一文件其余位置（`FileItem`）本来就用无参形式，此处统一。
     */
    private fun kindOf(name: String): Kind? {
        val lower = archiveName(name).lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".zip") -> Kind.ZIP
            lower.endsWith(".7z") -> Kind.SEVENZ
            TAR_EXTENSIONS.any { lower.endsWith(it) } -> Kind.TAR
            SINGLE_EXTENSIONS.any { lower.endsWith(it) } -> Kind.SINGLE
            else -> null
        }
    }

    /** 去除全部压缩/归档后缀后的基础名（如 a.tar.gz → a；a.gz → a）。 */
    fun baseName(name: String): String {
        val normalized = archiveName(name)
        val lower = normalized.lowercase(Locale.ROOT)
        val suffix = TAR_EXTENSIONS.firstOrNull { lower.endsWith(it) }
            ?: SINGLE_EXTENSIONS.firstOrNull { lower.endsWith(it) }
            ?: ".zip".takeIf { lower.endsWith(".zip") }
            ?: ".7z".takeIf { lower.endsWith(".7z") }
        if (suffix != null) {
            val n = normalized.substring(0, normalized.length - suffix.length)
            if (n.isNotEmpty()) return n
        }
        return name
    }

    /** Strip downloader suffixes such as `.1` before archive classification. */
    private fun archiveName(name: String): String =
        name.replace(Regex("\\.\\d+$"), "")

    sealed class ExtractResult {
        data class Success(val targetDir: String) : ExtractResult()
        data class NeedPassword(val archivePath: String) : ExtractResult()
        data class Failure(val message: String) : ExtractResult()
    }

    /** 一级目录结构预读结果：顶层条目名集合 + 其中属于目录的集合。 */
    private data class RootPeek(
    val topLevel: Set<String>,
    val topLevelDirs: Set<String>,
    /** 结构解析是否失败（与「合法的空归档」区分）。 */
    val parseFailed: Boolean = false
) {
        /** 条件 A：仅 1 个顶层条目且为目录。 */
        val singleTopFolder: String?
            get() = if (topLevel.size == 1) topLevelDirs.firstOrNull() else null
    }

    /**
     * 预读归档型压缩包根目录一级结构。
     */
    private fun peekRoot(path: String): RootPeek = try {
        when (kindOf(path)) {
            Kind.ZIP -> {
                Zip4jFile(path).use { zip ->
                    val headers = zip.fileHeaders
                    if (headers.size > MAX_EXTRACT_ENTRIES) {
                        throw ExtractionLimitException("压缩包条目数超过 $MAX_EXTRACT_ENTRIES")
                    }
                    val names = mutableSetOf<String>()
                    val dirs = mutableSetOf<String>()
                    for (h in headers) {
                        val first = firstSegment(h.fileName)
                        if (first.isEmpty()) continue
                        names.add(first)
                        if (h.isDirectory) dirs.add(first)
                    }
                    RootPeek(names, dirs)
                }
            }
            Kind.SEVENZ -> {
                org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder().setFile(File(path)).get().use { sevenZ ->
                    val names = mutableSetOf<String>()
                    val dirs = mutableSetOf<String>()
                    var count = 0
                    for (entry in sevenZ.entries) {
                        count++
                        if (count > MAX_EXTRACT_ENTRIES) {
                            throw ExtractionLimitException("压缩包条目数超过 $MAX_EXTRACT_ENTRIES")
                        }
                        val first = firstSegment(entry.name)
                        if (first.isEmpty()) continue
                        names.add(first)
                        if (entry.isDirectory) dirs.add(first)
                    }
                    RootPeek(names, dirs)
                }
            }
            Kind.TAR -> openTar(path).use { tarIn ->
                val names = mutableSetOf<String>()
                val dirs = mutableSetOf<String>()
                var count = 0
                while (true) {
                    val entry = tarIn.nextEntry ?: break
                    count++
                    if (count > MAX_EXTRACT_ENTRIES) {
                        throw ExtractionLimitException("压缩包条目数超过 $MAX_EXTRACT_ENTRIES")
                    }
                    val first = firstSegment(entry.name)
                    if (first.isEmpty()) continue
                    names.add(first)
                    if (entry.isDirectory) dirs.add(first)
                }
                RootPeek(names, dirs)
            }
            else -> RootPeek(emptySet(), emptySet())
        }
    } catch (e: ExtractionLimitException) {
        throw e
    } catch (_: Exception) {
        // 解析失败必须与「空归档」区分开。原实现两者都返回空结构，
        // 于是损坏/截断的 7z 走到下面的加密头预检分支 → 反复向用户索要密码，
        // 真实原因（文件已损坏）被永久隐藏。
        RootPeek(emptySet(), emptySet(), parseFailed = true)
    }

    /** 取条目路径的第一段（去掉前导 / 与 ./）。 */
    private fun firstSegment(name: String): String {
        var n = name.replace('\\', '/')
        while (n.startsWith("/") || n.startsWith("./")) n = n.removePrefix("/").removePrefix("./")
        return n.substringBefore('/').trim()
    }

    /** 按格式打开解压流（tar 或单文件压缩型）。 */
    private fun openDecompress(path: String): InputStream {
        val base = BufferedInputStream(FileInputStream(path))
        val lower = archiveName(path).lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".tar") -> base
            lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".gz") ->
                GzipCompressorInputStream(base)
            lower.endsWith(".tar.xz") || lower.endsWith(".xz") -> XZCompressorInputStream(base)
            lower.endsWith(".tar.bz2") || lower.endsWith(".bz2") -> BZip2CompressorInputStream(base)
            lower.endsWith(".tar.lz4") || lower.endsWith(".lz4") -> FramedLZ4CompressorInputStream(base)
            else -> base
        }
    }

    /** 打开 tar 归档流（按压缩格式自动包装）。 */
    private fun openTar(path: String): TarArchiveInputStream = TarArchiveInputStream(openDecompress(path))

    /**
     * 智能解压主入口。
     *
     * @param archivePath 压缩包绝对路径
     * @param targetParent 当前工作目录（解压目标所在目录）
     * @param password 加密包密码（由 UI 提供，可为 null）
     */
    suspend fun extract(
        archivePath: String,
        targetParent: String,
        password: String? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        val kind = kindOf(archivePath)
        if (kind == null) {
            return@withContext ExtractResult.Failure("暂不支持解压该格式")
        }

        // 单文件压缩型：直接解压为去掉压缩后缀的原文件名
        if (kind == Kind.SINGLE) {
            var targetFile: File? = null
            return@withContext try {
                val outName = baseName(File(archivePath).name)
                targetFile = reserveTargetFile(targetParent, outName)
                    ?: return@withContext ExtractResult.Failure("无法创建解压目标文件")
                val budget = ExtractionBudget()
                budget.beginEntry()
                openDecompress(archivePath).use { input -> copyStream(input, targetFile, budget) }
                ExtractResult.Success(targetFile.absolutePath)
            } catch (e: CancellationException) {
                runCatching { targetFile?.delete() }
                throw e
            } catch (e: Exception) {
                runCatching { targetFile?.delete() }
                ExtractResult.Failure("解压失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        // ZIP 条目数预算前置：必须在任何解析器触碰 central directory 之前
        if (kind == Kind.ZIP) {
            try {
                probeZipEntryCountOrThrow(archivePath)
            } catch (e: ExtractionLimitException) {
                return@withContext ExtractResult.Failure(e.message ?: "压缩包条目数超限")
            }
        }

        try {
            val rootPeek = peekRoot(archivePath)

            // 7z 加密头预检：peekRoot 读取加密头失败会得到空结构。
            // 若 7z 且无密码，先尝试以无密码打开确认是否为加密导致，是则返回 NeedPassword。
            // 但必须先排除「解析失败」：损坏/截断的 7z 同样得到空结构，
            // 会被误判成加密 → 用户被反复索要对密码、永远解不出来，真实原因被隐藏。
            if (rootPeek.parseFailed) {
                return@withContext ExtractResult.Failure("压缩包已损坏或不是有效的归档文件")
            }
            if (kind == Kind.SEVENZ && password.isNullOrEmpty() && rootPeek.topLevel.isEmpty()) {
                val sevenZ = try {
                    org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder().setFile(File(archivePath)).get()
                } catch (_: Exception) {
                    null
                }
                if (sevenZ == null) {
                    return@withContext ExtractResult.NeedPassword(archivePath)
                }
                sevenZ.close()
            }

            val finalTarget: File = rootPeek.singleTopFolder?.let { topFolder ->
                // 条件 A：直接将顶层文件夹解压到当前目录（需剥离顶层文件夹前缀，避免 abc/abc 嵌套）
                reserveTargetDirectory(targetParent, topFolder)
            } ?: run {
                // 条件 B：新建以压缩包名（去后缀）命名的文件夹
                reserveTargetDirectory(targetParent, baseName(File(archivePath).name))
            } ?: return@withContext ExtractResult.Failure("无法创建解压目标目录")

            // 条件 A 命中时剥离顶层文件夹前缀；条件 B 原样保留条目结构
            val stripPrefix = rootPeek.singleTopFolder

            val result = try {
                when (kind) {
                    Kind.ZIP -> extractZip(archivePath, finalTarget.absolutePath, password, stripPrefix)
                    Kind.SEVENZ -> extract7z(archivePath, finalTarget.absolutePath, password, stripPrefix)
                    Kind.TAR -> extractTar(archivePath, finalTarget.absolutePath, stripPrefix)
                    // SINGLE 已在此前提前返回，此处不可达；显式列出以保持枚举穷尽且避免 NoWhenBranchMatchedException
                    Kind.SINGLE -> ExtractResult.Failure("暂不支持解压该格式")
                }
            } catch (e: CancellationException) {
                // The directory was atomically reserved by this invocation.
                // Remove only that owned target, never a pre-existing sibling.
                runCatching { finalTarget.deleteRecursively() }
                throw e
            }

            when (result) {
                is ExtractResult.Success -> ExtractResult.Success(finalTarget.absolutePath)
                is ExtractResult.NeedPassword -> {
                    // 清理可能已创建的空白目标目录
                    if (finalTarget.listFiles()?.isEmpty() == true) {
                        finalTarget.delete()
                    }
                    result
                }
                is ExtractResult.Failure -> {
                    // 目标由本次 resolveTargetPath 新建，失败时整体清理，避免残留半包数据。
                    finalTarget.deleteRecursively()
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExtractResult.Failure("解压失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 目标路径冲突消解：同名存在时自动追加 _1、_2… 数字后缀（文件与目录通用）。 */
    private fun resolveTargetPath(parent: String, baseName: String): String {
        val safe = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var candidate = File(parent, safe)
        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, "${safe}_$index")
            index++
        }
        return candidate.absolutePath
    }

    /** Reserve a directory atomically so failed extraction cannot delete a rival target. */
    private fun reserveTargetDirectory(parent: String, baseName: String): File? {
        val safe = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var index = 0
        while (index < 100_000) {
            val name = if (index == 0) safe else "${safe}_$index"
            val candidate = File(parent, name)
            if (candidate.mkdir()) return candidate
            if (!candidate.exists()) return null
            index++
        }
        return null
    }

    /** Reserve a file atomically before opening the decompressor output stream. */
    private fun reserveTargetFile(parent: String, baseName: String): File? {
        val safe = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 0
        while (index < 100_000) {
            val name = if (index == 0) safe else "${stem}_$index$ext"
            val candidate = File(parent, name)
            try {
                if (candidate.createNewFile()) return candidate
            } catch (_: Exception) {
                return null
            }
            index++
        }
        return null
    }

    private fun extractZip(path: String, target: String, password: String?, stripPrefix: String?): ExtractResult {
        return try {
            val budget = ExtractionBudget()
            Zip4jFile(path).use { zip ->
                // 中文密码：开启 UTF-8 密码编码（zip4j 默认 CP437，中文密码必须显式 UTF-8）
                if (!password.isNullOrEmpty()) {
                    zip.setUseUtf8CharsetForPasswords(true)
                    zip.setPassword(password.toCharArray())
                }

                if (zip.isEncrypted && password.isNullOrEmpty()) {
                    return ExtractResult.NeedPassword(path)
                }

                // 使用流式解压以兼容条件 A/B 的预建目录结构
                val headers = zip.fileHeaders
                val writtenPaths = HashSet<String>()
                for (h in headers) {
                    budget.beginEntry(if (h.isDirectory) 0L else h.uncompressedSize)
                    val entryName = stripPrefix?.let { stripTopFolder(h.fileName, it) } ?: h.fileName
                    val dest = safeDest(target, entryName)
                    if (!writtenPaths.add(dest.canonicalPath)) {
                        return ExtractResult.Failure("归档包含重复条目: $entryName")
                    }
                    if (h.isDirectory) {
                        dest.mkdirs()
                        continue
                    }
                    dest.parentFile?.mkdirs()
                    try {
                        zip.getInputStream(h).use { input -> copyStream(input, dest, budget) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        dest.delete()
                        val msg = e.message ?: ""
                        if (msg.contains("password", ignoreCase = true) || msg.contains("Wrong Password", ignoreCase = true)) {
                            return ExtractResult.Failure(if (password.isNullOrEmpty()) "该压缩包已加密，需要密码" else "密码错误，请重试")
                        }
                        return ExtractResult.Failure("解压条目失败: $msg")
                    }
                }
            }
            ExtractResult.Success(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("password", ignoreCase = true) || msg.contains("Wrong Password", ignoreCase = true) || msg.contains("Invalid password", ignoreCase = true)) {
                ExtractResult.Failure(if (password.isNullOrEmpty()) "该压缩包已加密，需要密码" else "密码错误，请重试")
            } else {
                ExtractResult.Failure(msg.ifEmpty { "ZIP 解压异常" })
            }
        }
    }

    private fun extractTar(path: String, target: String, stripPrefix: String?): ExtractResult {
        return try {
            val budget = ExtractionBudget()
            val writtenPaths = HashSet<String>()
            openTar(path).use { tarIn ->
                while (true) {
                    val entry = tarIn.nextEntry ?: break
                    budget.beginEntry(if (entry.isDirectory) 0L else entry.size)
                    val entryName = stripPrefix?.let { stripTopFolder(entry.name, it) } ?: entry.name
                    val dest = safeDest(target, entryName)
                    if (!writtenPaths.add(dest.canonicalPath)) {
                        return ExtractResult.Failure("归档包含重复条目: $entryName")
                    }
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        copyStream(tarIn, dest, budget)
                    }
                }
            }
            ExtractResult.Success(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExtractResult.Failure(e.message ?: "TAR 解压异常")
        }
    }

    private fun extract7z(path: String, target: String, password: String?, stripPrefix: String?): ExtractResult {
        return try {
            val budget = ExtractionBudget()
            val writtenPaths = HashSet<String>()
            val file = File(path)
            val archive = if (password.isNullOrEmpty()) {
                org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder().setFile(file).get()
            } else {
                org.apache.commons.compress.archivers.sevenz.SevenZFile.Builder().setFile(file).setPassword(password.toCharArray()).get()
            }
            archive.use { sevenZ ->
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    if (!entry.isDirectory && entry.size < 0L) {
                        throw IllegalStateException("7Z 条目大小未知，拒绝解压")
                    }
                    budget.beginEntry(if (entry.isDirectory) 0L else entry.size)
                    val entryName = stripPrefix?.let { stripTopFolder(entry.name, it) } ?: entry.name
                    val dest = safeDest(target, entryName)
                    if (!writtenPaths.add(dest.canonicalPath)) {
                        return ExtractResult.Failure("归档包含重复条目: $entryName")
                    }
                    if (entry.isDirectory) {
                        dest.mkdirs()
                        continue
                    }
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        // 连续零读上限：commons-compress 的 SevenZFile 在部分畸形/加密
                        // 条目上会返回 0 而不推进，原实现是 `if (read == 0) continue`，
                        // 于是 `while (total < entry.size)` 永远转下去 —— 表现为
                        // 「解压卡住、界面无任何反应」，且目标文件已被 createNewFile 占用。
                        // 单次 0 读可能是合法的分块边界，连续多次则说明流已无法推进。
                        var zeroReads = 0
                        while (total < entry.size) {
                            val read = sevenZ.read(buffer)
                            if (read < 0) throw IllegalStateException("7Z 条目提前结束")
                            if (read == 0) {
                                if (++zeroReads > MAX_ZERO_READS) {
                                    throw IllegalStateException("7Z 条目读取无进展，已中止")
                                }
                                continue
                            }
                            zeroReads = 0
                            budget.consume(read)
                            out.write(buffer, 0, read)
                            total += read
                        }
                    }
                }
            }
            ExtractResult.Success(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("password", ignoreCase = true)) {
                ExtractResult.Failure(
                    if (password.isNullOrEmpty()) "该压缩包已加密，需要密码" else "密码错误，请重试"
                )
            } else {
                ExtractResult.Failure(msg.ifEmpty { "7Z 解压异常" })
            }
        }
    }

    /** 剥离条目名中的顶层文件夹前缀（条件 A 用），未命中前缀时原样返回。 */
    private fun stripTopFolder(entryName: String, topFolder: String): String {
        var n = entryName.replace('\\', '/')
        while (n.startsWith("/") || n.startsWith("./")) n = n.removePrefix("/").removePrefix("./")
        val prefix = topFolder.trimEnd('/')
        return if (n == prefix || n.startsWith("$prefix/")) {
            n.removePrefix(prefix).removePrefix("/")
        } else {
            entryName
        }
    }

    /**
     * 解压目标父目录是否**可写**（纯函数，便于单测）。
     *
     * 解压以应用自身 uid 落盘（`File.mkdirs()` + `FileOutputStream`），受两类限制：
     * DAC 权限位与 SELinux（MAC）—— `/data/adb/shso` 即便 `chmod 777`，
     * 应用 uid 建目录仍可能 `Permission denied`，因此必须实际检测而非只看权限位。
     *
     * `File.canWrite()` 底层走 `access(W_OK)` 系统调用，能同时反映 MAC 限制。
     */
    internal fun canExtractTo(dirPath: String): Boolean = runCatching {
        val file = File(dirPath)
        file.isDirectory && file.canWrite()
    }.getOrDefault(false)

    /**
     * 安全化条目名并解析为目标文件。
     *
     * **双层防御（Zip Slip）**：
     * ① 词法层：剥离绝对路径与前缀 `../`，把行内 `..` 段折叠成 `/`；
     * ② 真实路径层：用 `canonicalFile` 解析 `..` 与**符号链接**后，结果必须仍落在 target 之内；
     *    越界则丢弃目录部分、只保留文件名（fail-closed，绝不写到 target 之外）。
     *
     * 仅靠 ① 不够：词法层看不到符号链接，`<target>/link -> /system` 之类仍可逃逸；
     * 而 ② 在异常（如 canonical 解析失败）时同样退化为「只保留文件名」，不留 fail-open 口子。
     */
    internal fun safeDest(target: String, entryName: String): File {
        var n = entryName.replace('\\', '/')
        while (n.startsWith("/")) n = n.substring(1)
        while (n.startsWith("../")) n = n.removePrefix("../")
        n = n.replace(Regex("(^|/)\\.\\.(/|$)"), "/").trim()
        // 单点段（`.`）也要剥掉：`./` 与 `.` 归一后同样只剩 target 自身。
        while (n.startsWith("./")) n = n.removePrefix("./")
        n = n.trim('/')
        // 归一后可能什么都不剩（条目名是 `..`、`.`、`./` 或空串，
        // 例如 stripTopFolder 在条目名恰等于顶层前缀时会产出空串）。
        // `File(target, "")` 会被归一化成 **target 本身**，于是
        // `FileOutputStream(target)` 抛异常后，调用方的 `dest.delete()` 会把本次
        // 原子预留的目标目录删掉，后续条目写到 `dest.parentFile` 之外被丢弃。
        // 这里显式拒绝：条目名无效时用一个稳定且唯一的占位名。
        if (n.isEmpty() || n == "." || n == "..") {
            val stamp = Integer.toHexString(entryName.hashCode())
            return File(target, "unnamed_$stamp")
        }
        val candidate = File(target, n)
        val fallback = File(target, candidate.name.ifEmpty { "unnamed" })
        return try {
            val base = File(target).canonicalFile
            val real = candidate.canonicalFile
            val basePath = base.path
            if (real.path == basePath) {
                // 解析结果就是 target 自身：同样不可写（与空名同因）
                fallback
            } else if (real.path.startsWith(basePath + File.separator)) {
                real
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun copyStream(input: InputStream, dest: File, budget: ExtractionBudget) {
        BufferedOutputStream(FileOutputStream(dest)).use { out ->
            val buffer = ByteArray(64 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                budget.consume(count)
                out.write(buffer, 0, count)
            }
        }
    }
}
