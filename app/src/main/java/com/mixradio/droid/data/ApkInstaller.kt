// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * APK / XAPK 安装器（root 静默）。
 *
 * 方案参考 MP-Manager RootManager：
 * - 单 APK：先 `cp` 到 /data/local/tmp 再 `pm install -r`，装完清理——从原路径直接
 *   `pm install` 在部分 ROM 上会因 SELinux/存储权限失败，拷 tmp 是最稳路径。
 * - split/XAPK：`pm install-create -r -S <总大小>` → 逐个 `pm install-write -S <size>
 *   <session> split<i> <path>` → `pm install-commit`；session id 从输出 `[<id>]` 解析。
 * - XAPK 本质是 zip：解出 base.apk + split_config.*.apk 落位 /data/local/tmp，
 *   OBB 数据拷到 /sdcard/Android/obb/<包名>/。
 * - 全程使用 [RootService.escapeShellArg] 防注入；每条命令带超时。
 */
object ApkInstaller {

    /** 安装结果：成功 / 失败(消息) / 需要系统确认(无 root 兜底)。 */
    sealed class InstallResult {
        data class Success(val message: String) : InstallResult()
        data class Failure(val message: String) : InstallResult()
    }

    private const val TMP_DIR = "/data/local/tmp"

    /** 安装大包允许的更长超时（拷贝 + pm 会话流可能超过默认 120s）。 */
    private const val INSTALL_TIMEOUT_MS = 300_000L
    internal const val MAX_XAPK_BYTES = 1L * 1024 * 1024 * 1024
    internal const val MAX_XAPK_ENTRY_BYTES = 512L * 1024 * 1024
    internal const val MAX_XAPK_ENTRIES = 20_000
    private const val MAX_XAPK_MANIFEST_BYTES = 1L * 1024 * 1024
    private val ANDROID_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    internal fun isValidAndroidPackageName(value: String): Boolean =
        ANDROID_PACKAGE_NAME.matches(value)

    internal fun isValidVersionCode(value: String): Boolean = value.matches(Regex("\\d+"))

    /**
     * 安装 APK（root 静默）。
     * 支持伪装名：qq.apk.1（腾讯下载追加 .1）、APK/Apk 等大小写变体——
     * 统一先拷到 /data/local/tmp 的规范名 _shso_install.apk 再安装。
     *
     * **分包自动识别**：若同目录存在同一套件的分包（shso 自己提取出的
     * `<名>-<版本>-splitN.APK`，或 SAI/MT 解包的 `split_config.*.apk`），
     * 则自动改装整套（单文件 `pm install` 对分包应用必然失败：
     * INSTALL_FAILED_MISSING_SPLIT）。点基础包或点任意分包都能装整套。
     */
    suspend fun installApk(context: Context, apkPath: String): InstallResult = withContext(Dispatchers.IO) {
        val file = File(apkPath)
        if (!file.exists()) return@withContext InstallResult.Failure("APK 文件不存在: $apkPath")

        // 同目录聚合出「安装套件」；只有基础包时退回单文件安装
        val set = collectApkSet(context, apkPath)
        if (set.isSplit) {
            return@withContext when (val r = installSplitApks(set.all)) {
                is InstallResult.Success -> InstallResult.Success("${r.message}（含 ${set.splits.size} 个分包）")
                is InstallResult.Failure -> r
            }
        }

        // 统一用规范名（.apk）落到 /data/local/tmp：pm install 对 .1 等非规范后缀可能拒绝
        val tmpApk = "$TMP_DIR/_shso_install_${UUID.randomUUID()}.apk"
        val cleanCmd = "rm -f ${RootService.escapeShellArg(tmpApk)}"
        try {
            RootService.runCommandSync(cleanCmd, INSTALL_TIMEOUT_MS)

            val copyCmd = "cp ${RootService.escapeShellArg(apkPath)} ${RootService.escapeShellArg(tmpApk)}"
            val (copyCode, copyOut) = RootService.runCommandSync(copyCmd, INSTALL_TIMEOUT_MS)
            if (copyCode != 0) {
                return@withContext InstallResult.Failure("复制 APK 到临时目录失败: $copyOut")
            }

            // 安装（-r 覆盖安装 -d 允许降级 -t 测试包）
            val installCmd = "pm install -r -d -t ${RootService.escapeShellArg(tmpApk)}"
            val (installCode, installOut) = RootService.runCommandSync(installCmd, INSTALL_TIMEOUT_MS)

            if (installCode == 0 && (installOut.contains("Success") || installOut.contains("success"))) {
                InstallResult.Success("安装成功")
            } else {
                InstallResult.Failure("安装失败: ${installOut.trim().ifEmpty { "未知错误" }}")
            }
        } finally {
            RootService.runCommandSync(cleanCmd, INSTALL_TIMEOUT_MS)
        }
    }

    /**
     * 安装 XAPK（含 split 分片与 OBB 数据）。
     *
     * @param xapkPath  .xapk/.apks/.aspk/.apkm 文件路径
     */
    @SuppressLint("SdCardPath")
    suspend fun installXapk(context: Context, xapkPath: String): InstallResult = withContext(Dispatchers.IO) {
        val file = File(xapkPath)
        if (!file.exists()) return@withContext InstallResult.Failure("文件不存在: $xapkPath")

        // 1. 解压 XAPK（zip4j，zip 条目名可能含中文，需 UTF-8）
        val stagingDir = File(File(xapkPath).parentFile ?: File(TMP_DIR), ".shso_xapk_${UUID.randomUUID()}")
        try {
            if (!stagingDir.exists()) stagingDir.mkdirs()

            if (file.length() > MAX_XAPK_BYTES) {
                return@withContext InstallResult.Failure("XAPK 文件超过 ${MAX_XAPK_BYTES / 1024 / 1024}MB 上限")
            }
            val apkFiles = mutableListOf<File>()
            val obbFiles = mutableListOf<File>()
            val extractedNames = mutableSetOf<String>()
            var extractedBytes = 0L
            var manifestJson: JSONObject? = null

            try {
                ZipFile(xapkPath).use { zip ->
                    val headers = zip.fileHeaders
                    if (headers.size > MAX_XAPK_ENTRIES) {
                        return@withContext InstallResult.Failure("XAPK 条目数超过 $MAX_XAPK_ENTRIES")
                    }
                    for (header in headers) {
                        val entryName = header.fileName
                        // 跳过目录条目与 manifest 之外的元数据
                        if (header.isDirectory) continue

                        val lower = entryName.lowercase()
                        when {
                            entryName == "manifest.json" -> {
                                if (header.uncompressedSize > MAX_XAPK_MANIFEST_BYTES) {
                                    return@withContext InstallResult.Failure("XAPK manifest.json 过大")
                                }
                                val content = zip.getInputStream(header).use {
                                    readBoundedUtf8(it, MAX_XAPK_MANIFEST_BYTES)
                                }
                                manifestJson = try { JSONObject(content) } catch (_: Exception) { null }
                            }
                            lower.endsWith(".apk") -> {
                                val name = File(entryName).name
                                if (!extractedNames.add(name)) return@withContext InstallResult.Failure("XAPK 包含重复文件名: $name")
                                val dest = File(stagingDir, name)
                                extractedBytes = copyXapkEntry(zip, header, dest, extractedBytes)
                                apkFiles.add(dest)
                            }
                            lower.endsWith(".obb") -> {
                                val name = File(entryName).name
                                if (!extractedNames.add(name)) return@withContext InstallResult.Failure("XAPK 包含重复文件名: $name")
                                val dest = File(stagingDir, name)
                                extractedBytes = copyXapkEntry(zip, header, dest, extractedBytes)
                                obbFiles.add(dest)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext InstallResult.Failure("XAPK 解压失败: ${e.message}")
            }

            if (apkFiles.isEmpty()) {
                return@withContext InstallResult.Failure("XAPK 中未找到任何 APK 文件")
            }

            // 2. OBB 数据落位 /sdcard/Android/obb/<包名>/
            // 该路径由 ROOT shell 侧使用（mkdir/cp），非 Java 文件 API，故不能用 Environment 构造。
            // Android 规范：OBB 文件名必须为 main.<versionCode>.<packageName>.obb
            val packageName = manifestJson?.optString("package_name")
                ?.takeIf(::isValidAndroidPackageName)
            val versionCode = manifestJson?.optString("version_code")
                ?.takeIf(::isValidVersionCode) ?: "1"
            if (obbFiles.isNotEmpty() && packageName == null) {
                return@withContext InstallResult.Failure("XAPK 含 OBB 但 manifest 缺少有效包名")
            }
            if (obbFiles.isNotEmpty()) {
                val obbDir = "/sdcard/Android/obb/$packageName"
                val mkdirCmd = "mkdir -p ${RootService.escapeShellArg(obbDir)}"
                RootService.runCommandSync(mkdirCmd, INSTALL_TIMEOUT_MS)
                for (obb in obbFiles) {
                    // 已符合 main.<vc>.<pkg>.obb 命名则原样拷贝，否则按规范重命名
                    val targetName = if (obb.name.matches(Regex("^(main|patch)\\.\\d+\\..+\\.obb$"))) {
                        obb.name
                    } else {
                        "main.$versionCode.$packageName.obb"
                    }
                    val copyCmd = "cp ${RootService.escapeShellArg(obb.absolutePath)} ${RootService.escapeShellArg("$obbDir/$targetName")}"
                    RootService.runCommandSync(copyCmd, INSTALL_TIMEOUT_MS)
                }
            }

            // 3. 单 APK 直接装；多 APK（split）走会话流
            return@withContext if (apkFiles.size == 1) {
                installApk(context, apkFiles[0].absolutePath)
            } else {
                installSplitApks(apkFiles.map { it.absolutePath })
            }
        } finally {
            // 清理解压的临时目录（保留 OBB 已拷走的副本）
            try {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    //  安装套件识别（基础包 + 分包）
    //
    //  为什么需要：单文件 `pm install` 对分包应用必定失败（INSTALL_FAILED_MISSING_SPLIT）。
    //  分包安装必须走 `pm install-create/-write/-commit` 会话流，且**分片要先拷到
    //  /data/local/tmp** —— `install-write` 直接读 /storage 会被 SELinux 拒绝
    //  （avc denied sdcardfs，system_server 无权读 emulated 存储）。

    /** 一个「安装套件」：基础包 + 其分包（单包应用 splits 为空）。 */
    internal data class ApkSet(val base: String, val splits: List<String>) {
        val all: List<String> get() = listOf(base) + splits
        val isSplit: Boolean get() = splits.isNotEmpty()
    }

    /** 同目录扫描上限：避免在塞满 APK 的目录里付出无谓的 manifest 解析开销。 */
    private const val MAX_SIBLING_SCAN = 200
    private const val MAX_SPLITS = 64

    private val SPLIT_SUFFIX_REGEX = Regex("-split(\\d+)$", RegexOption.IGNORE_CASE)
    private val SPLIT_NAME_REGEX = Regex("^split[_.-].*", RegexOption.IGNORE_CASE)

    /**
     * 由文件名推导「套件前缀」：`X-123.APK` → `X-123`；`X-123-split2.APK` → `X-123`。
     * 非 `.apk` 后缀返回 null。纯函数，便于单测。
     */
    internal fun apkSetStem(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "")
        if (!ext.equals("apk", ignoreCase = true)) return null
        return SPLIT_SUFFIX_REGEX.replace(fileName.substringBeforeLast('.'), "")
    }

    /** 文件名是否形如分包：`...-split<数字>.apk`（shso 提取命名）。纯函数。 */
    internal fun isSplitName(fileName: String): Boolean =
        SPLIT_SUFFIX_REGEX.containsMatchIn(fileName.substringBeforeLast('.', fileName))

    /**
     * 按命名约定从目录内挑出一个安装套件。纯函数（只吃文件名），便于单测。
     *
     * 约定：基础包 `<名>-<版本>.APK`、分包 `<名>-<版本>-split<序号>.APK`。
     * **点基础包或点任意分包都能得到同一套件**。
     *
     * @return (基础包文件名, 分包文件名列表)；不构成套件时返回 null
     */
    internal fun nameBasedSet(tappedName: String, siblingNames: List<String>): Pair<String, List<String>>? {
        val stem = apkSetStem(tappedName)?.takeIf { it.isNotEmpty() } ?: return null
        fun isBase(n: String) = apkSetStem(n) == stem && !isSplitName(n)
        fun isSplit(n: String) = apkSetStem(n) == stem && isSplitName(n)

        val base = if (isBase(tappedName)) tappedName else siblingNames.firstOrNull { isBase(it) } ?: return null
        val splits = (siblingNames.filter { isSplit(it) } + if (isSplit(tappedName)) listOf(tappedName) else emptyList())
            .distinct()
            .filter { it != base }
            .sorted()
        return if (splits.isEmpty()) null else base to splits
    }

    /**
     * 收集 [apkPath] 所属的安装套件。
     *
     * 先按命名约定（覆盖 shso 自己提取出来的产物，**不需要读 manifest**，因此对
     * 应用不可读的目录同样有效）；命名不规范时再按 manifest 的「同包名 + 同版本号」
     * 聚合（覆盖 SAI / MT / xapk 解包出来的 `split_config.*.apk`）。
     * 任何不确定的情形都退回「单文件」，绝不猜测。
     */
    internal fun collectApkSet(context: Context, apkPath: String): ApkSet {
        val tapped = File(apkPath)
        val fallback = ApkSet(apkPath, emptyList())
        val dir = tapped.parentFile ?: return fallback
        val siblings = runCatching {
            dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
        }.getOrNull()?.toList() ?: return fallback
        if (siblings.size < 2) return fallback

        val byName = siblings.associateBy { it.name }

        // A) 命名约定
        nameBasedSet(tapped.name, siblings.map { it.name }.take(MAX_SIBLING_SCAN))?.let { (baseName, splitNames) ->
            val base = byName[baseName]
            val splits = splitNames.mapNotNull { byName[it] }
            if (base != null && splits.isNotEmpty()) {
                return ApkSet(base.absolutePath, splits.map { it.absolutePath })
            }
        }

        // B) manifest 分组
        return manifestBasedSet(context, tapped, siblings) ?: fallback
    }

    private fun manifestBasedSet(context: Context, tapped: File, siblings: List<File>): ApkSet? {
        val pm = context.packageManager
        val tappedInfo = archiveInfo(pm, tapped) ?: return null
        val pkg = tappedInfo.packageName.takeIf { it.isNotBlank() } ?: return null
        val version = longVersionCode(tappedInfo)

        val group = ArrayList<Pair<File, android.content.pm.PackageInfo>>()
        group += tapped to tappedInfo
        for (f in siblings) {
            if (f.absolutePath == tapped.absolutePath) continue
            if (group.size > MAX_SPLITS) break
            val info = archiveInfo(pm, f) ?: continue
            if (info.packageName != pkg || longVersionCode(info) != version) continue
            group += f to info
        }
        if (group.size < 2) return null

        // 基础包必须能唯一定位：manifest 的 splitNames 为空且名字不像分包
        val bases = group.filter { (f, info) -> !isSplitArchive(f, info) }
        if (bases.size != 1) return null
        val base = bases.single().first
        val splits = group.filter { it.first.absolutePath != base.absolutePath }
            .map { it.first.absolutePath }
            .sorted()
        return if (splits.isEmpty()) null else ApkSet(base.absolutePath, splits)
    }

    private fun isSplitArchive(f: File, info: android.content.pm.PackageInfo): Boolean {
        if (!info.applicationInfo?.splitNames.isNullOrEmpty()) return true
        return isSplitName(f.name) || SPLIT_NAME_REGEX.containsMatchIn(f.name)
    }

    /** 解析 APK 归档的 manifest；不可读 / 非 APK 时返回 null。 */
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: android.content.pm.PackageManager, f: File): android.content.pm.PackageInfo? =
        runCatching { pm.getPackageArchiveInfo(f.absolutePath, 0) }.getOrNull()

    private fun longVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    /**
     * 安装 split 分片 APK（pm 会话流）。
     */
    private suspend fun installSplitApks(apkPaths: List<String>): InstallResult = withContext(Dispatchers.IO) {
        if (apkPaths.isEmpty()) return@withContext InstallResult.Failure("没有可安装的 APK")

        // 1. 拷贝所有分片到 /data/local/tmp（sdcard 直读可能受限）
        val token = UUID.randomUUID().toString()
        val tmpFiles = mutableListOf<String>()
        var sessionId: String? = null
        var committed = false
        try {
            for ((i, path) in apkPaths.withIndex()) {
                val tmp = "$TMP_DIR/_shso_split_${token}_$i.apk"
                val copyCmd = "cp ${RootService.escapeShellArg(path)} ${RootService.escapeShellArg(tmp)}"
                val (code, out) = RootService.runCommandSync(copyCmd, INSTALL_TIMEOUT_MS)
                if (code != 0) {
                    return@withContext InstallResult.Failure("复制分片 $i 失败: $out")
                }
                tmpFiles.add(tmp)
            }

            // 2. 计算总大小并创建会话
            val totalSize = apkPaths.sumOf { File(it).length() }
            val createCmd = "pm install-create -r -d -S $totalSize"
            val (createCode, createOut) = RootService.runCommandSync(createCmd, INSTALL_TIMEOUT_MS)
            if (createCode != 0) {
                return@withContext InstallResult.Failure("创建安装会话失败: ${createOut.trim()}")
            }

            // 会话 id 形如 "Success: created install session [123456789]"
            sessionId = SESSION_ID_REGEX.find(createOut)?.groupValues?.get(1)
                ?: return@withContext InstallResult.Failure("无法解析安装会话 ID: ${createOut.trim()}")

            // 3. 写入分片
            for ((i, tmp) in tmpFiles.withIndex()) {
                val size = File(tmp).length()
                val writeCmd = "pm install-write -S $size ${sessionId} split$i ${RootService.escapeShellArg(tmp)}"
                val (writeCode, writeOut) = RootService.runCommandSync(writeCmd, INSTALL_TIMEOUT_MS)
                if (writeCode != 0) {
                    RootService.runCommandSync("pm install-abandon ${sessionId}", INSTALL_TIMEOUT_MS)
                    return@withContext InstallResult.Failure("写入分片 $i 失败: ${writeOut.trim()}")
                }
            }

            // 4. 提交
            val commitCmd = "pm install-commit ${sessionId}"
            val (commitCode, commitOut) = RootService.runCommandSync(commitCmd, INSTALL_TIMEOUT_MS)
            if (commitCode != 0 || (!commitOut.contains("Success") && !commitOut.contains("success"))) {
                return@withContext InstallResult.Failure("提交安装失败: ${commitOut.trim().ifEmpty { "未知错误" }}")
            }

            committed = true
            InstallResult.Success("安装成功")
        } finally {
            if (!committed) sessionId?.let { RootService.runCommandSync("pm install-abandon $it", INSTALL_TIMEOUT_MS) }
            // 清理临时分片
            val cleanupCmd = tmpFiles.joinToString(";") { "rm -f ${RootService.escapeShellArg(it)}" }
            if (cleanupCmd.isNotBlank()) {
                RootService.runCommandSync(cleanupCmd, INSTALL_TIMEOUT_MS)
            }
        }
    }

    private val SESSION_ID_REGEX = Regex("\\[(\\d+)]")

    private fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalStateException("XAPK manifest.json 过大")
            out.write(buffer, 0, count)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun copyXapkEntry(
        zip: ZipFile,
        header: net.lingala.zip4j.model.FileHeader,
        dest: File,
        currentTotal: Long
    ): Long {
        val declared = header.uncompressedSize
        if (declared > MAX_XAPK_ENTRY_BYTES) throw IllegalStateException("XAPK 单个条目过大")
        var total = currentTotal
        zip.getInputStream(header).use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_XAPK_BYTES) throw IllegalStateException("XAPK 解压总大小超限")
                    output.write(buffer, 0, count)
                }
            }
        }
        return total
    }

    /**
     * 非 ROOT 安装：调用系统包安装器（ACTION_VIEW + FileProvider 内容 URI）。
     *
     * 这是「普通用户本就能安装 APK」的标准路径，不再强制依赖 ROOT：
     * 仅当授权 ROOT 时才由 [installApk] 走静默安装；无 ROOT 时回退到本方法，
     * 由系统安装器完成交互式安装。适用于普通用户可读取的 APK（如 /sdcard 下）；
     * 受保护路径（/data/adb 等）非 ROOT 不可读时会失败并给出明确提示。
     */
    fun installApkViaSystem(context: Context, apkPath: String): InstallResult {
        val file = File(apkPath)
        if (!file.exists()) {
            Log.e(TAG, "APK 文件不存在: $apkPath")
            return InstallResult.Failure("APK 文件不存在: $apkPath")
        }

        // Android 8+ 要求声明并动态授权 REQUEST_INSTALL_PACKAGES。
        // 未授权时系统安装器会直接 finish，表现为“点了安装但没有任何反应”。
        // minSdk 26 即 Android 8，该判断恒真，无需保留版本分支。
        val canInstall = context.packageManager.canRequestPackageInstalls()
        Log.i(TAG, "canRequestPackageInstalls=$canInstall, path=$apkPath")
        if (!canInstall) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:${context.packageName}".toUri()
                    addFlags(FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "已跳转设置页请求 REQUEST_INSTALL_PACKAGES")
                InstallResult.Failure("需要允许 shso 安装未知来源应用，请先在设置中开启后再试")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "跳转设置页失败", e)
                InstallResult.Failure("需要允许 shso 安装未知来源应用: ${e.message}")
            }
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "已启动系统安装器, uri=$uri")
            InstallResult.Success("已调用系统安装器，请在弹出的界面完成安装")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "启动系统安装器失败", e)
            InstallResult.Failure("启动系统安装器失败: ${e.message}")
        }
    }

    private const val TAG = "ApkInstaller"
}
