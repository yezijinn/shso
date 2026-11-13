// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * 压缩包**免整体解压**浏览 + 包内单条目抽取 + （仅 ZIP）条目写回。
 *
 * ## 为什么单独一个文件而不是塞进 [ArchiveExtractor]
 * [ArchiveExtractor] 的语义是「整包解压到目录并带 zip-bomb 预算」；这里是「只读元数据 +
 * 单条目按需取流 + 定向替换」，磁盘副作用方向相反（不建目录树、只写一个临时文件）。
 * 混在一起会让「预算」这套已经调好的防线长出第二套分支。
 *
 * ## 硬约束（与既有防护同口径，不另开一条路）
 *  - 条目数/单条目大小/总读取字节一律复用 [ArchiveExtractor] 的常量与 [ArchiveExtractor.ExtractionBudget]；
 *  - ZIP 条目数在触碰 central directory **之前**先跑 [ArchiveExtractor.probeZipEntryCountOrThrow]；
 *  - 条目路径统一归一化，越界（`..` 段）条目**只从列表里剔除并计数**，绝不参与打开或写回；
 *  - 抽取的临时文件落在 app 私有 cache 的**按条目隔离的子目录**，文件名是净化后的条目名。
 *
 * ## 能力边界（诚实结论，UI 必须照此禁用入口）
 *  - ZIP 未加密：可浏览 / 可打开 / **可写回**；
 *  - ZIP 加密、7Z、TAR、单文件压缩：可浏览、图片可看，**文本不提供打开、不可写回**；
 *  - 包本体 app 不可读（root-only 目录）：直接报不可浏览 —— `su` 只解决写，不解决读；
 *  - rar 不在 [ArchiveExtractor.isKnownArchive] 内，本层报「暂不支持浏览该格式」。
 */
object ArchiveBrowser {

    /** 单条目抽到 cache 的上限。编辑器可载入上限 32MB（[ChunkedFileReader.MAX_LOAD_BYTES]）：
     *  32–64MB 的文本会以只读方式打开（编辑器自带降级，保存被拒），不会误写；图片解码走降采样。 */
    const val MAX_ENTRY_OPEN_BYTES = 64L * 1024 * 1024

    /** 可写回的压缩包体积上限。写回 = 副本重写（zip4j 内部还多拷一遍），峰值磁盘约 2× 包体积。 */
    const val MAX_REWRITE_ARCHIVE_BYTES = 512L * 1024 * 1024

    /** 写回后做**内容级**校验的上限；超过只校验条目存在与大小。 */
    private const val VERIFY_COMPARE_BYTES = 8L * 1024 * 1024

    private const val COPY_BUFFER = 64 * 1024
    private const val MAX_ZERO_READS = 64
    private const val CACHE_SUBDIR = "archive-open"

    /** 二进制嗅探窗口：前 8KB 出现 NUL 即判二进制（UTF-16 BOM 例外）。 */
    private const val BINARY_PROBE_BYTES = 8192

    private const val UNREADABLE = "该压缩包位于应用不可读取的位置，无法浏览；可先复制到可读目录"
    private const val NOT_ZIP_WRITABLE = "仅 ZIP 支持写回；当前格式只能浏览"

    /** 写回能力。UI 只据此决定「打开编辑」是否可用，不自行推断格式。 */
    enum class WriteSupport { NONE, ZIP_REWRITE }

    /** 包内条目（路径已归一化；目录不含尾部斜杠）。 */
    data class ArchiveEntry(
        val path: String,
        val isDirectory: Boolean,
        /** 未压缩大小；未知（7z solid / 单文件压缩）为 -1。 */
        val size: Long,
        /** 压缩后大小；未知为 -1。 */
        val compressedSize: Long,
        val encrypted: Boolean
    ) {
        val name: String get() = path.substringAfterLast('/')
    }

    /** 一次列举的结果快照。 */
    data class ArchiveInfo(
        val kind: ArchiveExtractor.ContainerKind,
        val entries: List<ArchiveEntry>,
        /** 包里存在加密条目（ZIP）/ 头部加密且已用密码打开（7Z）。 */
        val encrypted: Boolean,
        /** 被剔除的越界条目数（`..` 段）：只提示，不展示。 */
        val skippedUnsafe: Int,
        val writeSupport: WriteSupport,
        /** writeSupport == NONE 时的可读原因，直接展示给用户。 */
        val writeBlockedReason: String?
    )

    sealed class BrowseResult {
        data class Success(val info: ArchiveInfo) : BrowseResult()
        object NeedPassword : BrowseResult()
        data class Failure(val message: String) : BrowseResult()
    }

    sealed class OpenResult {
        data class Success(val cachePath: String) : OpenResult()
        object NeedPassword : OpenResult()
        data class TooLarge(val size: Long) : OpenResult()
        data class Failure(val message: String) : OpenResult()
    }

    sealed class WriteResult {
        data class Success(val size: Long) : WriteResult()
        data class Failure(val message: String) : WriteResult()
    }

    private class EntryTooLargeException(val declaredSize: Long) : Exception("条目超过打开上限")

    // ── 纯函数区（JVM 单测覆盖） ────────────────────────────────────────────

    /**
     * 条目路径归一化：`\` → `/`、剥前导 `/` 与 `./`、折叠重复 `/`、去尾 `/`。
     *
     * 只用于**展示与匹配**，不用于落盘路径 —— 因此这里不做 `canonicalFile` 那套，
     * 安全性由「临时文件名不来自条目路径」保证。
     */
    internal fun normalizeEntryPath(raw: String): String {
        var n = raw.replace('\\', '/').trim()
        while (n.startsWith("/")) n = n.substring(1)
        while (n.startsWith("./")) n = n.removePrefix("./")
        n = n.trimEnd('/')
        while (n.contains("//")) n = n.replace("//", "/")
        return n
    }

    /** 越界路径：任一段为 `..`。这类条目只计数不展示，也不参与打开与写回。 */
    internal fun isUnsafeEntryPath(path: String): Boolean =
        path.split('/').any { it == ".." }

    /** 父目录路径；顶层返回空串。 */
    internal fun parentOf(path: String): String =
        if (path.contains('/')) path.substringBeforeLast('/') else ""

    /**
     * 列出 [dir] 的**直接子项**，目录优先、同类型按名称小写升序。
     *
     * 不依赖归档里存在显式目录条目：`res/values/strings.xml` 会合成出 `res`、`res/values`。
     * 显式目录条目与合成目录重名时保留**目录**语义（size 0）。
     */
    internal fun childrenOf(entries: List<ArchiveEntry>, dir: String): List<ArchiveEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val out = LinkedHashMap<String, ArchiveEntry>()
        for (e in entries) {
            if (prefix.isNotEmpty() && !e.path.startsWith(prefix)) continue
            val rest = if (prefix.isEmpty()) e.path else e.path.substring(prefix.length)
            if (rest.isEmpty()) continue
            val segment = rest.substringBefore('/')
            if (segment.isEmpty()) continue
            val childPath = if (dir.isEmpty()) segment else "$dir/$segment"
            val isNested = rest.contains('/')
            val child = when {
                isNested -> ArchiveEntry(childPath, true, 0L, 0L, false)
                e.isDirectory -> ArchiveEntry(childPath, true, 0L, 0L, e.encrypted)
                else -> ArchiveEntry(childPath, false, e.size, e.compressedSize, e.encrypted)
            }
            val prev = out[childPath]
            if (prev == null || (!prev.isDirectory && child.isDirectory)) out[childPath] = child
        }
        return out.values.sortedWith(
            compareByDescending<ArchiveEntry> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    /**
     * 缓存文件名：只用条目名本身（编辑器标题显示真实文件名），但净化到目录内安全：
     * 非 [字母/数字/./-/_] 一律换 `_`、去前导尾随点（不产生 `.`/`..`/隐藏名）、截断 48 字符。
     * 目录本身是 app 私有 cache 的子目录，名字里也不可能有 `/` —— 落盘路径与条目路径彻底解耦。
     */
    internal fun safeCacheName(entryPath: String): String {
        val base = entryPath.substringAfterLast('/')
        val cleaned = base.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("")
            .trim('.')
            .take(48)
            .trimEnd('.')
        return cleaned.ifEmpty { "entry_${UUID.randomUUID().toString().take(8)}" }
    }

    /**
     * 缓存子目录名：`sha256(包路径 \0 条目路径)` 前 16 位。
     *
     * 存在的理由不是安全（安全由 [safeCacheName] 保证），而是**隔离**：
     * `a/x.txt` 与 `b/x.txt`、以及两个不同压缩包里的 `x.txt` 若共用一个缓存文件名，
     * 后开的条目会覆盖前者，写回时就会把 A 的字节写进 B 的条目。
     * 哈希取「包 + 条目」两者，才排除跨包串味。
     */
    internal fun cacheSubdirName(archivePath: String, entryPath: String): String =
        sha256Hex("$archivePath\u0000$entryPath").take(16)

    /** 某条目在 cache 中的落盘路径（唯一、稳定、目录已建好）。 */
    internal fun cacheFileFor(cacheDir: File, archivePath: String, entryPath: String): File {
        val root = File(cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val dir = File(root, cacheSubdirName(archivePath, entryPath)).apply { mkdirs() }
        return File(dir, safeCacheName(entryPath))
    }

    /**
     * 二进制嗅探（纯函数，可 JVM 单测）：窗口内出现 NUL 即判二进制。
     *
     * 必要性：文本扩展名不等于文本内容 —— APK 里的 `AndroidManifest.xml` 是二进制 AXML，
     * 若送进文本编辑器并被保存，条目会被写坏（并且把整个 APK 的签名一并作废）。
     * UTF-16 文本天然含 NUL，故前两字节是 BOM 时直接放行，交给编辑器的编码检测。
     */
    internal fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length <= 0) return false
        if (length >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return false
        }
        for (i in 0 until length) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** 对磁盘文件做二进制嗅探；读不出来按「不是二进制」处理（交给后续流程报真实错误）。 */
    internal fun isLikelyBinary(path: String): Boolean = runCatching {
        File(path).inputStream().use { input ->
            val buffer = ByteArray(BINARY_PROBE_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            looksBinary(buffer, read)
        }
    }.getOrDefault(false)

    // ── 列举 ──────────────────────────────────────────────────────────────

    /**
     * 列举包内全部条目。
     *
     * @param password 密码（可为 null；ZIP 列目录不需要，7z 头加密需要）
     * @param rootGranted 当前是否已授权 ROOT —— 决定「目标目录不可写时是否仍可写回」
     */
    suspend fun list(
        archivePath: String,
        password: String?,
        rootGranted: Boolean
    ): BrowseResult = withContext(Dispatchers.IO) {
        val kind = ArchiveExtractor.containerKindOf(archivePath)
            ?: return@withContext BrowseResult.Failure("暂不支持浏览该格式")
        val archive = File(archivePath)
        if (!archive.isFile) return@withContext BrowseResult.Failure("压缩包不存在或不可读")
        if (!archive.canRead()) return@withContext BrowseResult.Failure(UNREADABLE)
        try {
            when (kind) {
                ArchiveExtractor.ContainerKind.ZIP -> listZip(archive, rootGranted)
                ArchiveExtractor.ContainerKind.SEVENZ -> listSevenZ(archive, password)
                ArchiveExtractor.ContainerKind.TAR -> listTar(archive)
                ArchiveExtractor.ContainerKind.SINGLE -> listSingle(archive)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BrowseResult.Failure("读取压缩包失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun listZip(archive: File, rootGranted: Boolean): BrowseResult {
        // 预算前置：先读尾部拿条目数，再让 zip4j 分配 FileHeader（顺序不能反）。
        try {
            ArchiveExtractor.probeZipEntryCountOrThrow(archive.path)
        } catch (e: ArchiveExtractor.ExtractionLimitException) {
            return BrowseResult.Failure(e.message ?: "压缩包条目数超限")
        }
        // 列目录只用 central directory：zip4j 的 getFileHeaders() 不需要密码。
        Zip4jFile(archive.path).use { zip ->
            val headers = zip.fileHeaders
            if (headers.size > ArchiveExtractor.MAX_EXTRACT_ENTRIES) {
                return BrowseResult.Failure("压缩包条目数超过 ${ArchiveExtractor.MAX_EXTRACT_ENTRIES}")
            }
            val entries = ArrayList<ArchiveEntry>(headers.size)
            var skipped = 0
            var encrypted = false
            for (h in headers) {
                val path = normalizeEntryPath(h.fileName)
                if (path.isEmpty()) continue
                if (isUnsafeEntryPath(path)) {
                    skipped++
                    continue
                }
                val enc = h.isEncrypted
                if (enc) encrypted = true
                entries.add(
                    ArchiveEntry(
                        path = path,
                        isDirectory = h.isDirectory,
                        size = if (h.isDirectory) 0L else h.uncompressedSize,
                        compressedSize = if (h.isDirectory) 0L else h.compressedSize,
                        encrypted = enc
                    )
                )
            }
            val reason = zipWriteBlockReason(archive, encrypted, rootGranted)
            return BrowseResult.Success(
                ArchiveInfo(
                    kind = ArchiveExtractor.ContainerKind.ZIP,
                    entries = entries,
                    encrypted = encrypted,
                    skippedUnsafe = skipped,
                    writeSupport = if (reason == null) WriteSupport.ZIP_REWRITE else WriteSupport.NONE,
                    writeBlockedReason = reason
                )
            )
        }
    }

    private suspend fun listSevenZ(archive: File, password: String?): BrowseResult {
        val sevenZ = try {
            openSevenZ(archive, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PasswordRequiredException) {
            return BrowseResult.NeedPassword
        } catch (e: Exception) {
            return if (isPasswordError(e)) BrowseResult.NeedPassword
            else BrowseResult.Failure("读取 7z 失败: ${e.message ?: e.javaClass.simpleName}")
        }
        sevenZ.use { z ->
            val entries = ArrayList<ArchiveEntry>()
            var skipped = 0
            var count = 0
            try {
                for (e in z.entries) {
                    currentCoroutineContext().ensureActive()
                    if (++count > ArchiveExtractor.MAX_EXTRACT_ENTRIES) {
                        return BrowseResult.Failure("压缩包条目数超过 ${ArchiveExtractor.MAX_EXTRACT_ENTRIES}")
                    }
                    val path = normalizeEntryPath(e.name)
                    if (path.isEmpty()) continue
                    if (isUnsafeEntryPath(path)) {
                        skipped++
                        continue
                    }
                    entries.add(
                        ArchiveEntry(
                            path = path,
                            isDirectory = e.isDirectory,
                            size = if (e.isDirectory) 0L else e.size,
                            compressedSize = -1L,
                            encrypted = false
                        )
                    )
                }
            } catch (e: CancellationException) {
                // 取消不是「读取失败」：必须向上抛，否则关掉弹窗后协程仍以为自己在跑。
                throw e
            } catch (e: Exception) {
                return if (isPasswordError(e)) BrowseResult.NeedPassword
                else BrowseResult.Failure("读取 7z 条目失败: ${e.message ?: e.javaClass.simpleName}")
            }
            return BrowseResult.Success(
                ArchiveInfo(
                    kind = ArchiveExtractor.ContainerKind.SEVENZ,
                    entries = entries,
                    // 能列出条目 ⇒ 要么没加密，要么密码正确；后者说明包里确有加密。
                    encrypted = !password.isNullOrEmpty(),
                    skippedUnsafe = skipped,
                    writeSupport = WriteSupport.NONE,
                    writeBlockedReason = "7Z 写回需整包重压，无法保证其它条目不变，已禁用"
                )
            )
        }
    }

    private suspend fun listTar(archive: File): BrowseResult {
        ArchiveExtractor.openTarStream(archive.path).use { tarIn ->
            val entries = ArrayList<ArchiveEntry>()
            var skipped = 0
            var count = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val e = tarIn.nextEntry ?: break
                if (++count > ArchiveExtractor.MAX_EXTRACT_ENTRIES) {
                    return BrowseResult.Failure("压缩包条目数超过 ${ArchiveExtractor.MAX_EXTRACT_ENTRIES}")
                }
                val path = normalizeEntryPath(e.name)
                if (path.isEmpty()) continue
                if (isUnsafeEntryPath(path)) {
                    skipped++
                    continue
                }
                entries.add(
                    ArchiveEntry(
                        path = path,
                        isDirectory = e.isDirectory,
                        size = if (e.isDirectory) 0L else e.size,
                        compressedSize = -1L,
                        encrypted = false
                    )
                )
            }
            return BrowseResult.Success(
                ArchiveInfo(
                    kind = ArchiveExtractor.ContainerKind.TAR,
                    entries = entries,
                    encrypted = false,
                    skippedUnsafe = skipped,
                    writeSupport = WriteSupport.NONE,
                    writeBlockedReason = "TAR 无增量重写能力，只能浏览；需要编辑请先「自动解压」"
                )
            )
        }
    }

    private fun listSingle(archive: File): BrowseResult {
        val name = ArchiveExtractor.baseName(archive.name).ifEmpty { archive.name }
        return BrowseResult.Success(
            ArchiveInfo(
                kind = ArchiveExtractor.ContainerKind.SINGLE,
                entries = listOf(ArchiveEntry(name, false, -1L, archive.length(), false)),
                encrypted = false,
                skippedUnsafe = 0,
                writeSupport = WriteSupport.NONE,
                writeBlockedReason = "单文件压缩包没有条目结构，只能浏览；需要编辑请先「自动解压」"
            )
        )
    }

    /**
     * ZIP 写回被禁用的原因；null 表示可写回。
     *
     * `rootGranted` 参与判定：目录 app 不可写但已授权 ROOT 时，「cache 改好 → root 覆盖」可行，
     * 不能因为 `canWrite() == false` 就一概禁用（Android FUSE 上 `canWrite()` 还会假阴性，
     * 所以下降一级用建删探针实测）。
     */
    private fun zipWriteBlockReason(archive: File, encrypted: Boolean, rootGranted: Boolean): String? = when {
        encrypted ->
            "加密压缩包只能浏览：写回会重建加密条目，密码/密钥强度无法保证一致"
        archive.length() > MAX_REWRITE_ARCHIVE_BYTES ->
            "压缩包超过 ${MAX_REWRITE_ARCHIVE_BYTES / 1024 / 1024}MB，写回需整体重写，已禁用"
        canWriteDirectory(archive.parentFile) -> null
        rootGranted -> null
        else -> "目标目录不可写且未授权 ROOT，无法写回"
    }

    // ── 单条目抽取 ────────────────────────────────────────────────────────

    /**
     * 把包内单条目流式抽到 app 私有 cache，返回磁盘路径供编辑器/查看器复用。
     *
     * 不整条读进内存：一律经 [copyBounded] 分块落盘，并受
     * [MAX_ENTRY_OPEN_BYTES] 与 [ArchiveExtractor.ExtractionBudget] 双重限制。
     */
    suspend fun openEntry(
        archivePath: String,
        entryPath: String,
        password: String?,
        cacheDir: File
    ): OpenResult = withContext(Dispatchers.IO) {
        val kind = ArchiveExtractor.containerKindOf(archivePath)
            ?: return@withContext OpenResult.Failure("暂不支持浏览该格式")
        if (isUnsafeEntryPath(entryPath)) return@withContext OpenResult.Failure("条目路径越界")
        val archive = File(archivePath)
        if (!archive.isFile) return@withContext OpenResult.Failure("压缩包不存在或不可读")
        if (!archive.canRead()) return@withContext OpenResult.Failure(UNREADABLE)

        val tmp = cacheFileFor(cacheDir, archivePath, entryPath)
        val entryDir = tmp.parentFile
        if (entryDir == null || !entryDir.isDirectory) {
            return@withContext OpenResult.Failure("无法创建临时目录")
        }
        pruneOldCache(entryDir.parentFile, entryDir.name)
        runCatching { if (tmp.exists()) tmp.delete() }

        try {
            val result = when (kind) {
                ArchiveExtractor.ContainerKind.ZIP -> openZipEntry(archive, entryPath, password, tmp)
                ArchiveExtractor.ContainerKind.SEVENZ -> openSevenZEntry(archive, entryPath, password, tmp)
                ArchiveExtractor.ContainerKind.TAR -> openTarEntry(archive, entryPath, tmp)
                ArchiveExtractor.ContainerKind.SINGLE -> openSingleEntry(archive, tmp)
            }
            if (result is OpenResult.Success) result else {
                runCatching { tmp.delete() }
                result
            }
        } catch (e: CancellationException) {
            runCatching { tmp.delete() }
            throw e
        } catch (e: EntryTooLargeException) {
            runCatching { tmp.delete() }
            OpenResult.TooLarge(e.declaredSize)
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            OpenResult.Failure("读取条目失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun openZipEntry(
        archive: File,
        entryPath: String,
        password: String?,
        tmp: File
    ): OpenResult {
        // 与 list 同口径的预算前置：openEntry 是公开入口，可能不经 list 直调。
        try {
            ArchiveExtractor.probeZipEntryCountOrThrow(archive.path)
        } catch (e: ArchiveExtractor.ExtractionLimitException) {
            return OpenResult.Failure(e.message ?: "压缩包条目数超限")
        }
        Zip4jFile(archive.path).use { zip ->
            if (!password.isNullOrEmpty()) {
                zip.setUseUtf8CharsetForPasswords(true)
                zip.setPassword(password.toCharArray())
            }
            val header = zip.fileHeaders.firstOrNull { normalizeEntryPath(it.fileName) == entryPath }
                ?: return OpenResult.Failure("压缩包内不存在该条目")
            if (header.isDirectory) return OpenResult.Failure("这是一个目录")
            if (header.isEncrypted && password.isNullOrEmpty()) return OpenResult.NeedPassword
            if (header.uncompressedSize > MAX_ENTRY_OPEN_BYTES) {
                return OpenResult.TooLarge(header.uncompressedSize)
            }
            val budget = ArchiveExtractor.ExtractionBudget()
            budget.beginEntry(if (header.uncompressedSize >= 0L) header.uncompressedSize else null)
            try {
                zip.getInputStream(header).use { input -> copyBounded(input, tmp, budget) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isPasswordError(e)) return OpenResult.NeedPassword
                throw e
            }
            return OpenResult.Success(tmp.absolutePath)
        }
    }

    private suspend fun openSevenZEntry(
        archive: File,
        entryPath: String,
        password: String?,
        tmp: File
    ): OpenResult {
        val sevenZ = try {
            openSevenZ(archive, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PasswordRequiredException) {
            return OpenResult.NeedPassword
        } catch (e: Exception) {
            return if (isPasswordError(e)) OpenResult.NeedPassword
            else OpenResult.Failure("读取 7z 失败: ${e.message ?: e.javaClass.simpleName}")
        }
        sevenZ.use { z ->
            var target: SevenZArchiveEntry? = null
            var count = 0
            try {
                for (e in z.entries) {
                    currentCoroutineContext().ensureActive()
                    if (++count > ArchiveExtractor.MAX_EXTRACT_ENTRIES) {
                        return OpenResult.Failure("压缩包条目数超过 ${ArchiveExtractor.MAX_EXTRACT_ENTRIES}")
                    }
                    if (!e.isDirectory && normalizeEntryPath(e.name) == entryPath) {
                        target = e
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return if (isPasswordError(e)) OpenResult.NeedPassword
                else OpenResult.Failure("读取 7z 条目失败: ${e.message ?: e.javaClass.simpleName}")
            }
            val entry = target ?: return OpenResult.Failure("压缩包内不存在该条目")
            if (entry.size > MAX_ENTRY_OPEN_BYTES) return OpenResult.TooLarge(entry.size)
            // 空文件 / 无流条目（hasStream()==false）不能走 getInputStream，直接落空文件。
            if (entry.size == 0L || !entry.hasStream()) {
                tmp.writeBytes(ByteArray(0))
                return OpenResult.Success(tmp.absolutePath)
            }
            val budget = ArchiveExtractor.ExtractionBudget()
            budget.beginEntry(entry.size)
            z.getInputStream(entry).use { input -> copyBounded(input, tmp, budget) }
            return OpenResult.Success(tmp.absolutePath)
        }
    }

    private suspend fun openTarEntry(archive: File, entryPath: String, tmp: File): OpenResult {
        // TAR 只能顺序访问：每开一条就重扫一遍头（CPU，不落盘）。
        ArchiveExtractor.openTarStream(archive.path).use { tarIn ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val e = tarIn.nextEntry ?: return OpenResult.Failure("压缩包内不存在该条目")
                if (e.isDirectory) continue
                if (normalizeEntryPath(e.name) != entryPath) continue
                if (e.size > MAX_ENTRY_OPEN_BYTES) return OpenResult.TooLarge(e.size)
                val budget = ArchiveExtractor.ExtractionBudget()
                budget.beginEntry(e.size)
                copyBounded(tarIn, tmp, budget)
                return OpenResult.Success(tmp.absolutePath)
            }
        }
    }

    private suspend fun openSingleEntry(archive: File, tmp: File): OpenResult {
        val budget = ArchiveExtractor.ExtractionBudget()
        budget.beginEntry()
        ArchiveExtractor.openSingleStream(archive.path).use { input -> copyBounded(input, tmp, budget) }
        return OpenResult.Success(tmp.absolutePath)
    }

    /**
     * 分块拷贝并压上限。零读保护与 `ArchiveExtractor` 同口径：连续 0 读说明流已无法推进，
     * 再循环就是死循环。每 64 块检查一次取消，使 64MB 级别的条目也能被「关掉弹窗」打断。
     */
    private suspend fun copyBounded(
        input: InputStream,
        dest: File,
        budget: ArchiveExtractor.ExtractionBudget
    ) {
        dest.outputStream().use { out ->
            val buffer = ByteArray(COPY_BUFFER)
            var written = 0L
            var zeroReads = 0
            var blocks = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) {
                    if (++zeroReads > MAX_ZERO_READS) throw IllegalStateException("条目读取无进展，已中止")
                    continue
                }
                zeroReads = 0
                blocks++
                if (blocks % 64 == 0) currentCoroutineContext().ensureActive()
                written += n
                if (written > MAX_ENTRY_OPEN_BYTES) throw EntryTooLargeException(written)
                budget.consume(n)
                out.write(buffer, 0, n)
            }
        }
    }

    /**
     * 清理 24 小时前的抽取残留（进程被杀时来不及删的那些）。
     *
     * 布局是「一个条目一个子目录」，因此按子目录整体回收；[keepDirName] 是本次要写入的目录，
     * 无论如何不动（否则会删掉刚解开、正被编辑器打开的那个文件）。
     *
     * @param root `cache/archive-open`，可为 null（cacheDir 不可用时静默跳过）
     */
    private fun pruneOldCache(root: File?, keepDirName: String) {
        if (root == null) return
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        runCatching {
            root.listFiles()?.forEach { f ->
                if (f.name == keepDirName) return@forEach
                if (f.lastModified() >= cutoff) return@forEach
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
    }

    // ── 写回（仅 ZIP） ────────────────────────────────────────────────────

    /**
     * 用 [newContent] 的内容替换包内 [entryPath]，**保留其它条目**。
     *
     * 流程与安全性：
     *  1. 从不直接改原包：先把原包复制成副本（同目录或 cache），只改副本；
     *  2. 单次 `addStream`：zip4j 内部**无条件** `removeFileIfExists`（按 `fileNameInZip`）
     *     再追加，同名条目必被替换；其它条目按**原始压缩字节区间**拷贝，内容与压缩字节都不变；
     *     代价是「重写 + 追加」两趟 IO，被替换条目移到 central directory 末尾；
     *  3. 发布前校验副本：条目数不变、目标条目存在、大小一致、未变加密、≤8MB 时逐字节 SHA-256 一致；
     *  4. 发布：app 可写目录 → 同文件系统 `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`；
     *     仅 root 可写 → `cp` 到同目录临时名 → 还原 mode/uid/gid → `mv -f` → `restorecon`；
     *  5. 任一步失败：删副本，**原包一个字节未动**（重命名/替换是最后一步且原子）。
     *
     * 不可取消：写回是用户显式提交的替换，中途放弃才会留下不一致状态；用 `NonCancellable`
     * 明确这一语义，进程级作用域保证旋屏不打断它。
     *
     * @return 成功时返回写入后的条目大小
     */
    suspend fun replaceEntry(
        archivePath: String,
        entryPath: String,
        newContent: File,
        cacheDir: File,
        rootGranted: Boolean
    ): WriteResult = withContext(Dispatchers.IO + NonCancellable) {
        replaceEntryBlocking(archivePath, entryPath, newContent, cacheDir, rootGranted)
    }

    private fun replaceEntryBlocking(
        archivePath: String,
        entryPath: String,
        newContent: File,
        cacheDir: File,
        rootGranted: Boolean
    ): WriteResult {
        val archive = File(archivePath)
        if (ArchiveExtractor.containerKindOf(archivePath) != ArchiveExtractor.ContainerKind.ZIP) {
            return WriteResult.Failure(NOT_ZIP_WRITABLE)
        }
        // 防御纵深：写回名必须来自已过滤的列表，越界名一律拒绝（不依赖调用方守规矩）。
        if (entryPath.isEmpty() || isUnsafeEntryPath(entryPath)) {
            return WriteResult.Failure("条目路径非法，未写回")
        }
        if (!archive.isFile) return WriteResult.Failure("压缩包不可读")
        if (!archive.canRead()) return WriteResult.Failure(UNREADABLE)
        if (!newContent.isFile) return WriteResult.Failure("待写回内容不存在")
        val newSize = newContent.length()
        if (newSize > ArchiveExtractor.MAX_EXTRACT_ENTRY_BYTES) {
            return WriteResult.Failure("条目超过 ${ArchiveExtractor.MAX_EXTRACT_ENTRY_BYTES / 1024 / 1024}MB 上限")
        }
        if (archive.length() > MAX_REWRITE_ARCHIVE_BYTES) {
            return WriteResult.Failure("压缩包超过 ${MAX_REWRITE_ARCHIVE_BYTES / 1024 / 1024}MB，写回已禁用")
        }
        val dir = archive.parentFile ?: return WriteResult.Failure("无法确定压缩包所在目录")
        val direct = canWriteDirectory(dir)
        if (!direct && !rootGranted) return WriteResult.Failure("目标目录不可写且未授权 ROOT，无法写回")
        val workDir = if (direct) dir else cacheDir.apply { mkdirs() }
        if (!workDir.isDirectory) return WriteResult.Failure("无法创建临时目录")
        val work = File(workDir, ".${archive.name}.shso_arc_${UUID.randomUUID()}.tmp")
        try {
            copyFileStream(archive, work)

            var headerName = ""
            var beforeCount = -1
            Zip4jFile(work.path).use { zip ->
                val headers = zip.fileHeaders
                beforeCount = headers.size
                if (headers.any { it.isEncrypted }) return WriteResult.Failure("加密压缩包不支持写回")
                val header = headers.firstOrNull { normalizeEntryPath(it.fileName) == entryPath }
                    ?: return WriteResult.Failure("压缩包内不存在该条目")
                if (header.isDirectory) return WriteResult.Failure("目录条目不可写")
                // 用原始 header 名（含原始大小写/前缀），否则「刚写的条目」按名找不到。
                headerName = header.fileName
                val params = ZipParameters().apply {
                    fileNameInZip = headerName
                    lastModifiedFileTime = System.currentTimeMillis()
                    // 保持原条目的压缩方式。STORE 不需要（也不能）给 entrySize/entryCRC：
                    // zip4j 在 addStream 里强制 entrySize=0 + extended local header，
                    // 写完由 updateLocalFileHeader 回填，CRC 由内部 CRC32 现算。
                    // DEFLATE 显式写出：默认值虽是 DEFLATE，但这里一旦漏写就会静默变成别的口径。
                    compressionMethod = if (header.compressionMethod == CompressionMethod.STORE) {
                        CompressionMethod.STORE
                    } else {
                        CompressionMethod.DEFLATE
                    }
                }
                newContent.inputStream().use { input -> zip.addStream(input, params) }
            }

            // 发布前校验：全部在副本上做，失败即丢弃副本（原包一个字节未动）。
            Zip4jFile(work.path).use { zip ->
                val headers = zip.fileHeaders
                if (headers.size != beforeCount) {
                    return WriteResult.Failure("写回后条目数变化（${headers.size} != $beforeCount），已放弃")
                }
                val h = headers.firstOrNull { it.fileName == headerName }
                    ?: return WriteResult.Failure("写回后目标条目丢失，已放弃")
                if (h.uncompressedSize != newSize) {
                    return WriteResult.Failure("写回后条目大小不符（${h.uncompressedSize} != $newSize），已放弃")
                }
                if (h.isEncrypted) return WriteResult.Failure("写回后条目变成加密，已放弃")
                if (newSize <= VERIFY_COMPARE_BYTES) {
                    val expect = sha256Of(newContent)
                    val actual = zip.getInputStream(h).use { sha256Of(it) }
                    if (expect != actual) return WriteResult.Failure("写回后内容校验失败，已放弃")
                }
            }

            return if (direct) publishDirect(work, archive, newSize) else publishAsRoot(work, archive, newSize)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return WriteResult.Failure("写回失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { if (work.exists()) work.delete() }
            cleanupZip4jTemp(work)
        }
    }

    /** app 自身可写：同文件系统原子替换，权限尽力保留（sdcard 上 POSIX 权限不适用，失败即忽略）。 */
    private fun publishDirect(work: File, archive: File, newSize: Long): WriteResult {
        runCatching {
            val perms = Files.getPosixFilePermissions(archive.toPath())
            Files.setPosixFilePermissions(work.toPath(), perms)
        }
        return try {
            try {
                Files.move(
                    work.toPath(), archive.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(work.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            WriteResult.Success(newSize)
        } catch (e: Exception) {
            WriteResult.Failure("替换原压缩包失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 仅 root 可写：副本（cache）→ `cp` 到目标同目录临时名 → 还原 mode/uid/gid → `mv -f` → `restorecon`。
     *
     * 必须先 `cp` 到目标目录再 `mv`：跨文件系统 `mv` 是「复制 + 删除」，
     * 中途失败会把原包变成半截文件，而这里的目标是「要么旧要么新」。
     */
    private fun publishAsRoot(work: File, archive: File, newSize: Long): WriteResult {
        val dir = archive.parent ?: return WriteResult.Failure("无法确定目标目录")
        val pubTmp = File(dir, ".${archive.name}.shso_pub_${UUID.randomUUID()}.tmp")
        try {
            val (statCode, statOut) = RootService.runCommandSync(
                "stat -c '%a %u %g' ${RootService.escapeShellArg(archive.path)} 2>/dev/null", 10_000L
            )
            val (cpCode, cpOut) = RootService.runCommandSync(
                "cp -f -- ${RootService.escapeShellArg(work.path)} ${RootService.escapeShellArg(pubTmp.path)}",
                300_000L
            )
            if (cpCode != 0) {
                return WriteResult.Failure("写入目标目录失败: ${cpOut.trim().ifEmpty { "未知错误" }}")
            }
            if (statCode == 0) restoreAttrs(statOut, pubTmp.path)
            val (mvCode, mvOut) = RootService.runCommandSync(
                "mv -f -- ${RootService.escapeShellArg(pubTmp.path)} ${RootService.escapeShellArg(archive.path)}",
                60_000L
            )
            if (mvCode != 0) {
                return WriteResult.Failure("替换原压缩包失败: ${mvOut.trim().ifEmpty { "未知错误" }}")
            }
            RootService.runCommandSync("restorecon ${RootService.escapeShellArg(archive.path)} 2>/dev/null", 30_000L)
            return WriteResult.Success(newSize)
        } finally {
            runCatching {
                if (pubTmp.exists()) {
                    RootService.runCommandSync("rm -f -- ${RootService.escapeShellArg(pubTmp.path)}", 10_000L)
                }
            }
        }
    }

    /** 还原 mode/uid/gid；输出必须严格是 `mode uid gid` 且字形合法，否则整条跳过（防注入）。 */
    private fun restoreAttrs(statOut: String, path: String) {
        val parts = statOut.trim().split(Regex("\\s+"))
        if (parts.size != 3) return
        val mode = parts[0]
        val uid = parts[1]
        val gid = parts[2]
        if (!mode.matches(Regex("[0-7]{1,4}"))) return
        if (!uid.all { it.isDigit() } || !gid.all { it.isDigit() }) return
        val p = RootService.escapeShellArg(path)
        RootService.runCommandSync("chmod $mode $p; chown $uid:$gid $p", 30_000L)
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    /**
     * 目录是否可写：`canWrite()` 在 Android FUSE 上有假阴性，为假时用「建删探针」实测一次。
     *
     * 两侧失真都按「不可写」处理更安全：判成可写却没写成，会在写回中途失败（原包不动、
     * 有明确文案）；判成不可写但目录其实可写，只是多走一次 root（或提示不可写）。
     * 探针必须建**再**删：只建不删会在用户目录里留下 `.shso_probe_*` 垃圾。
     */
    private fun canWriteDirectory(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory) return false
        if (dir.canWrite()) return true
        return runCatching {
            val probe = File(dir, ".shso_probe_${UUID.randomUUID().toString().take(8)}")
            val created = probe.createNewFile()
            if (!created) return@runCatching false
            val removed = probe.delete() || !probe.exists()
            removed
        }.getOrDefault(false)
    }

    private fun openSevenZ(archive: File, password: String?): SevenZFile =
        if (password.isNullOrEmpty()) {
            SevenZFile.Builder().setFile(archive).get()
        } else {
            SevenZFile.Builder().setFile(archive).setPassword(password.toCharArray()).get()
        }

    private fun isPasswordError(e: Throwable): Boolean {
        if (e is PasswordRequiredException) return true
        val msg = e.message ?: ""
        return msg.contains("password", ignoreCase = true) ||
            msg.contains("encrypted", ignoreCase = true) ||
            msg.contains("wrong", ignoreCase = true) ||
            msg.contains("mac check", ignoreCase = true) ||
            msg.contains("verif", ignoreCase = true)
    }

    private fun copyFileStream(src: File, dst: File) {
        src.inputStream().use { input ->
            dst.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    /**
     * zip4j 的内部临时文件是「zip 路径 + 0..9999 随机数」，**没有后缀**，进程被杀会残留。
     * 只清理「本工作副本名 + 纯数字」这一种形状（工作名含 UUID），别的文件绝不碰。
     */
    private fun cleanupZip4jTemp(work: File) {
        val dir = work.parentFile ?: return
        val prefix = work.name
        runCatching {
            dir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.length > prefix.length && n.startsWith(prefix) &&
                    n.substring(prefix.length).all { it.isDigit() }
                ) {
                    f.delete()
                }
            }
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Of(file: File): String = file.inputStream().use { sha256Of(it) }

    private fun sha256Of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
