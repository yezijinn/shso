// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
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
 * 支持的格式（14 种）：
 * - 归档型：zip / 7z / tar / tgz / tar.gz / tar.xz / tar.bz2 / tar.zst / tar.lz4
 * - 单文件压缩型：gz / xz / bz2 / zst / lz4（直接解压为原文件名）
 *
 * rar 为专有商业格式（UnRAR 许可证限制），不支持、不识别、不做任何处理。
 *
 * 智能解压算法（归档型）：
 * - 条件 A（单顶层文件夹模式）：压缩包根目录仅含 1 个顶层条目，且该条目为文件夹 →
 *   将该顶层文件夹及其内容直接解压到当前工作目录。
 * - 条件 B（多条目/单文件模式）：不满足条件 A →
 *   在当前目录新建以「压缩包名（去后缀）」命名的文件夹，将所有内容解压其中。
 *
 * 单文件压缩型直接解压为去掉压缩后缀的原文件名，同样执行重名冲突 _N 消解。
 *
 * 边界处理：
 * - 重名冲突：目标路径已存在同名路径 → 自动追加数字后缀（如 xxx_1）。
 * - 加密压缩包：zip 条目加密/7z 头加密时返回 [ExtractResult.NeedPassword]，由 UI 弹窗收集密码后重试。
 *   - zip 使用 zip4j（支持 ZipCrypto + WinZip AES，char[] 密码天然支持中文，UTF-8 密码开关）。
 *   - 7z 使用 commons-compress SevenZFile(file, password.toCharArray())。
 */
object ArchiveExtractor {

    /** 压缩包/文件类型分类。 */
    private enum class Kind { ZIP, SEVENZ, TAR, SINGLE }

    /** TAR 归档型扩展（含双后缀，判定优先于单文件压缩型）。 */
    private val TAR_EXTENSIONS = listOf(
        ".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tar.lz4",
        ".tgz", ".tar"
    )

    /** 单文件压缩型扩展。 */
    private val SINGLE_EXTENSIONS = listOf(".gz", ".xz", ".bz2", ".zst", ".lz4")

    fun isKnownArchive(name: String): Boolean = kindOf(name) != null

    /** 所有已知格式均可解压（rar 已彻底移除）。 */
    fun isExtractable(name: String): Boolean = kindOf(name) != null

    /** 识别压缩包类型；未知格式返回 null。 */
    private fun kindOf(name: String): Kind? {
        val lower = name.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".zip") -> Kind.ZIP
            lower.endsWith(".7z") -> Kind.SEVENZ
            TAR_EXTENSIONS.any { lower.endsWith(it) } -> Kind.TAR
            SINGLE_EXTENSIONS.any { lower.endsWith(it) } -> Kind.SINGLE
            else -> null
        }
    }

    /** 去除全部压缩/归档后缀后的基础名（如 a.tar.gz → a；a.zst → a）。 */
    fun baseName(name: String): String {
        val lower = name.lowercase(Locale.getDefault())
        val suffix = TAR_EXTENSIONS.firstOrNull { lower.endsWith(it) }
            ?: SINGLE_EXTENSIONS.firstOrNull { lower.endsWith(it) }
            ?: ".zip".takeIf { lower.endsWith(".zip") }
            ?: ".7z".takeIf { lower.endsWith(".7z") }
        if (suffix != null) {
            val n = name.substring(0, name.length - suffix.length)
            if (n.isNotEmpty()) return n
        }
        return name
    }

    sealed class ExtractResult {
        data class Success(val targetDir: String) : ExtractResult()
        data class NeedPassword(val archivePath: String) : ExtractResult()
        data class Failure(val message: String) : ExtractResult()
    }

    /** 一级目录结构预读结果：顶层条目名集合 + 其中属于目录的集合。 */
    private data class RootPeek(val topLevel: Set<String>, val topLevelDirs: Set<String>) {
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
                    val names = mutableSetOf<String>()
                    val dirs = mutableSetOf<String>()
                    for (h in zip.fileHeaders) {
                        val first = firstSegment(h.fileName)
                        if (first.isEmpty()) continue
                        names.add(first)
                        if (h.isDirectory) dirs.add(first)
                    }
                    RootPeek(names, dirs)
                }
            }
            Kind.SEVENZ -> {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(File(path)).use { sevenZ ->
                    val names = mutableSetOf<String>()
                    val dirs = mutableSetOf<String>()
                    for (e in sevenZ.entries) {
                        val first = firstSegment(e.name)
                        if (first.isEmpty()) continue
                        names.add(first)
                        if (e.isDirectory) dirs.add(first)
                    }
                    RootPeek(names, dirs)
                }
            }
            Kind.TAR -> openTar(path).use { tarIn ->
                val names = mutableSetOf<String>()
                val dirs = mutableSetOf<String>()
                while (true) {
                    val e = tarIn.nextEntry ?: break
                    val first = firstSegment(e.name)
                    if (first.isEmpty()) continue
                    names.add(first)
                    if (e.isDirectory) dirs.add(first)
                }
                RootPeek(names, dirs)
            }
            else -> RootPeek(emptySet(), emptySet())
        }
    } catch (_: Exception) {
        RootPeek(emptySet(), emptySet())
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
        val lower = path.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".tar") -> base
            lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".gz") ->
                GzipCompressorInputStream(base)
            lower.endsWith(".tar.xz") || lower.endsWith(".xz") -> XZCompressorInputStream(base)
            lower.endsWith(".tar.bz2") || lower.endsWith(".bz2") -> BZip2CompressorInputStream(base)
            lower.endsWith(".tar.zst") || lower.endsWith(".zst") -> ZstdCompressorInputStream(base)
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
            return@withContext try {
                val outName = baseName(File(archivePath).name)
                val targetFile = resolveTargetPath(targetParent, outName)
                File(targetFile).parentFile?.mkdirs()
                openDecompress(archivePath).use { input -> copyStream(input, File(targetFile)) }
                ExtractResult.Success(targetFile)
            } catch (e: Exception) {
                ExtractResult.Failure("解压失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        try {
            val rootPeek = peekRoot(archivePath)

            // 7z 加密头预检：peekRoot 读取加密头失败会得到空结构。
            // 若 7z 且无密码，先尝试以无密码打开确认是否为加密导致，是则返回 NeedPassword。
            if (kind == Kind.SEVENZ && password.isNullOrEmpty() && rootPeek.topLevel.isEmpty()) {
                val sevenZ = try {
                    org.apache.commons.compress.archivers.sevenz.SevenZFile(File(archivePath))
                } catch (_: Exception) {
                    null
                }
                if (sevenZ == null) {
                    return@withContext ExtractResult.NeedPassword(archivePath)
                }
                sevenZ.close()
            }

            val finalTarget: String = rootPeek.singleTopFolder?.let { topFolder ->
                // 条件 A：直接将顶层文件夹解压到当前目录（需剥离顶层文件夹前缀，避免 abc/abc 嵌套）
                resolveTargetPath(targetParent, topFolder)
            } ?: run {
                // 条件 B：新建以压缩包名（去后缀）命名的文件夹
                resolveTargetPath(targetParent, baseName(File(archivePath).name))
            }

            File(finalTarget).mkdirs()

            // 条件 A 命中时剥离顶层文件夹前缀；条件 B 原样保留条目结构
            val stripPrefix = rootPeek.singleTopFolder

            val result = when (kind) {
                Kind.ZIP -> extractZip(archivePath, finalTarget, password, stripPrefix)
                Kind.SEVENZ -> extract7z(archivePath, finalTarget, password, stripPrefix)
                Kind.TAR -> extractTar(archivePath, finalTarget, stripPrefix)
                else -> ExtractResult.Failure("暂不支持解压该格式")
            }

            when (result) {
                is ExtractResult.Success -> ExtractResult.Success(finalTarget)
                is ExtractResult.NeedPassword -> {
                    // 清理可能已创建的空白目标目录
                    if (File(finalTarget).listFiles()?.isEmpty() == true) {
                        File(finalTarget).delete()
                    }
                    result
                }
                is ExtractResult.Failure -> {
                    // 失败时清理可能遗留的空目标目录
                    if (File(finalTarget).listFiles()?.isEmpty() == true) {
                        File(finalTarget).delete()
                    }
                    result
                }
            }
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

    private fun extractZip(path: String, target: String, password: String?, stripPrefix: String?): ExtractResult {
        return try {
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
                for (h in headers) {
                    val entryName = stripPrefix?.let { stripTopFolder(h.fileName, it) } ?: h.fileName
                    val dest = safeDest(target, entryName)
                    if (h.isDirectory) {
                        dest.mkdirs()
                        continue
                    }
                    dest.parentFile?.mkdirs()
                    try {
                        zip.getInputStream(h).use { input -> copyStream(input, dest) }
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
            openTar(path).use { tarIn ->
                while (true) {
                    val entry = tarIn.nextEntry ?: break
                    val entryName = stripPrefix?.let { stripTopFolder(entry.name, it) } ?: entry.name
                    val dest = safeDest(target, entryName)
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        copyStream(tarIn, dest)
                    }
                }
            }
            ExtractResult.Success(target)
        } catch (e: Exception) {
            ExtractResult.Failure(e.message ?: "TAR 解压异常")
        }
    }

    private fun extract7z(path: String, target: String, password: String?, stripPrefix: String?): ExtractResult {
        return try {
            val file = File(path)
            val archive = if (password.isNullOrEmpty()) {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(file)
            } else {
                org.apache.commons.compress.archivers.sevenz.SevenZFile(file, password.toCharArray())
            }
            archive.use { sevenZ ->
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    val entryName = stripPrefix?.let { stripTopFolder(entry.name, it) } ?: entry.name
                    val dest = safeDest(target, entryName)
                    if (entry.isDirectory) {
                        dest.mkdirs()
                        continue
                    }
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (total < entry.size) {
                            val read = sevenZ.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            total += read
                        }
                    }
                }
            }
            ExtractResult.Success(target)
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

    /** 安全化条目名并解析为目标文件（剥离绝对路径与 ../ 穿越）。 */
    private fun safeDest(target: String, entryName: String): File {
        var n = entryName.replace('\\', '/')
        while (n.startsWith("/")) n = n.substring(1)
        while (n.startsWith("../")) n = n.removePrefix("../")
        n = n.replace(Regex("(^|/)\\.\\.(/|$)"), "/").trim()
        return File(target, n)
    }

    private fun copyStream(input: InputStream, dest: File) {
        BufferedOutputStream(FileOutputStream(dest)).use { out ->
            val buffer = ByteArray(64 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                out.write(buffer, 0, count)
            }
        }
    }
}