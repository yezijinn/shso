// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * 压缩包智能解压（zip / tar / tgz / 7z）。
 *
 * 智能解压算法：
 * - 条件 A（单顶层文件夹模式）：压缩包根目录仅含 1 个顶层条目，且该条目为文件夹 →
 *   将该顶层文件夹及其内容直接解压到当前工作目录。
 * - 条件 B（多条目/单文件模式）：不满足条件 A →
 *   在当前目录新建以「压缩包名（去后缀）」命名的文件夹，将所有内容解压其中。
 *
 * 边界处理：
 * - 重名冲突：目标路径已存在同名文件夹 → 自动追加数字后缀（如 xxx_1）。
 * - 加密压缩包：zip 条目加密/7z 头加密时返回 [ExtractResult.NeedPassword]，由 UI 弹窗收集密码后重试。
 *   - zip 使用 zip4j（支持 ZipCrypto + WinZip AES，char[] 密码天然支持中文，UTF-8 密码开关）。
 *   - 7z 使用 commons-compress SevenZFile(file, password.toCharArray())。
 *   - rar 仅识别、暂不支持解压（设备无 7z 命令、无离线 junrar 库）。
 */
object ArchiveExtractor {

    /** 实际可解压的扩展名（小写）。rar 仅识别、暂不支持解压（设备无 7z 命令、无离线 junrar 库）。 */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("zip", "tar", "tgz", "7z")

    /** 已知压缩包扩展名（长按菜单显示「自动解压文件」按钮的判定集合）。 */
    val KNOWN_ARCHIVE_EXTENSIONS: Set<String> = SUPPORTED_EXTENSIONS + "rar"

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.getDefault())

    fun isKnownArchive(name: String): Boolean = extensionOf(name) in KNOWN_ARCHIVE_EXTENSIONS

    fun isExtractable(name: String): Boolean = extensionOf(name) in SUPPORTED_EXTENSIONS

    /** 去除压缩后缀后的基础名（.tar.gz/.tar.xz/.tgz 一并去除）。 */
    fun baseName(name: String): String {
        val lower = name.lowercase(Locale.getDefault())
        val n = when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz") -> name.substring(0, name.length - 7)
            else -> name.substringBeforeLast('.', name)
        }
        return n.ifEmpty { name }
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
     * 预读压缩包根目录一级结构。
     */
    private fun peekRoot(path: String): RootPeek = try {
        val ext = extensionOf(path)
        when (ext) {
            "zip" -> {
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
            "tar", "tgz" -> {
                openTar(path).use { tarIn ->
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
            }
            "7z" -> {
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

    private fun openTar(path: String): TarArchiveInputStream {
        val ext = extensionOf(path)
        val base = BufferedInputStream(FileInputStream(path))
        val raw: InputStream = if (ext == "tgz") GzipCompressorInputStream(base) else base
        return TarArchiveInputStream(raw)
    }

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
        try {
            if (!isExtractable(archivePath)) {
                return@withContext ExtractResult.Failure("暂不支持解压该格式（仅支持 zip/tar/tgz/7z）")
            }

            val rootPeek = peekRoot(archivePath)

            // 7z 加密头预检：peekRoot 读取加密头失败会得到空结构。
            // 若 7z 且无密码，先尝试以无密码打开确认是否为加密导致，是则返回 NeedPassword。
            val ext = extensionOf(archivePath)
            if (ext == "7z" && password.isNullOrEmpty() && rootPeek.topLevel.isEmpty()) {
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
                // 条件 A：直接将顶层文件夹解压到当前目录
                resolveTargetPath(targetParent, topFolder)
            } ?: run {
                // 条件 B：新建以压缩包名（去后缀）命名的文件夹
                resolveTargetPath(targetParent, baseName(File(archivePath).name))
            }

            File(finalTarget).mkdirs()

            val result = when (ext) {
                "zip" -> extractZip(archivePath, finalTarget, password)
                "tar", "tgz" -> extractTar(archivePath, finalTarget)
                "7z" -> extract7z(archivePath, finalTarget, password)
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

    /** 目标路径冲突消解：同名存在时自动追加 _1、_2… 数字后缀。 */
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

    private fun extractZip(path: String, target: String, password: String?): ExtractResult {
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
                    val dest = safeDest(target, h.fileName)
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

    private fun extractTar(path: String, target: String): ExtractResult {
        return try {
            openTar(path).use { tarIn ->
                while (true) {
                    val entry = tarIn.nextEntry ?: break
                    val dest = safeDest(target, entry.name)
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

    private fun extract7z(path: String, target: String, password: String?): ExtractResult {
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
                    val dest = safeDest(target, entry.name)
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
