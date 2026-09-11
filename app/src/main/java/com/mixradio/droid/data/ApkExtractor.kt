// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 已安装应用条目（「提取 APK」用）。
 *
 * [baseApk] / [splitApks] 为安装包在设备上的真实路径：
 * - 单包应用：只有 [baseApk]
 * - 分包应用（App Bundle 上架，现已是主流）：[baseApk] + [splitApks]，
 *   只导出 base 通常**装不上**，故提取时会把分包一并导出。
 */
data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val baseApk: String,
    val splitApks: List<String>
) {
    val isSplit: Boolean get() = splitApks.isNotEmpty()
}

/**
 * 已安装应用的 APK 提取器：导出到内部存储 `Download/`。
 *
 * 命名规则（用户指定，后缀必须是大写 `APK`）：
 * - 基础包：`<应用名>-<versionCode>.APK`
 * - 分包：`<应用名>-<versionCode>-split1.APK`、`-split2.APK` …
 *
 * 读取 `/data/app/...` 下的安装包需要 ROOT；无 ROOT 时仅系统应用（其 APK 位于
 * 全局可读的 `/system` 等分区）能直接读取。
 */
object ApkExtractor {

    /** 内部存储 Download 目录。应用持有 MANAGE_EXTERNAL_STORAGE，可直接写入。 */
    const val DOWNLOAD_DIR = "/storage/emulated/0/Download"

    data class Result(
        val ok: Boolean,
        val message: String,
        val files: List<String> = emptyList()
    )

    /**
     * 枚举已安装应用。
     * @param includeSystem true 时包含系统应用（[ApplicationInfo.FLAG_SYSTEM]）
     */
    suspend fun listInstalledApps(context: Context, includeSystem: Boolean): List<InstalledAppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledPackages(0).mapNotNull { pkg ->
                val ai = pkg.applicationInfo ?: return@mapNotNull null
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!includeSystem && isSystem) return@mapNotNull null
                val base = ai.sourceDir ?: return@mapNotNull null
                InstalledAppInfo(
                    packageName = pkg.packageName,
                    label = runCatching { pm.getApplicationLabel(ai).toString() }
                        .getOrDefault(pkg.packageName),
                    versionCode = versionCodeOf(pkg),
                    baseApk = base,
                    splitApks = ai.splitSourceDirs?.toList().orEmpty()
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }

    /** API 28+ 用 longVersionCode（versionCode 在 28 之后可能被截断/合成）。 */
    private fun versionCodeOf(pkg: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
        else @Suppress("DEPRECATION") pkg.versionCode.toLong()

    /** 基础包文件名：`<应用名>-<versionCode>.APK`。 */
    fun baseFileName(label: String, versionCode: Long): String =
        "${sanitizeFileName(label)}-$versionCode.APK"

    /** 分包文件名：`<应用名>-<versionCode>-split<N>.APK`。 */
    fun splitFileName(label: String, versionCode: Long, index: Int): String =
        "${sanitizeFileName(label)}-$versionCode-split$index.APK"

    /**
     * 本次提取会产出哪些文件：(目标文件名 → 源 APK 路径)。
     *
     * 单包应用 1 项；分包应用为 base + 每个 split 各一项（`-split1`、`-split2` …，序号从 1 起）。
     * 抽成纯函数：本机可能没有分包应用，靠单测覆盖这条分支的产物规划。
     */
    internal fun plannedTargets(app: InstalledAppInfo): List<Pair<String, String>> = buildList {
        add(baseFileName(app.label, app.versionCode) to app.baseApk)
        app.splitApks.forEachIndexed { idx, path ->
            add(splitFileName(app.label, app.versionCode, idx + 1) to path)
        }
    }

    /**
     * 文件名净化：应用名可能含 `/`、`:`、`*` 等非法字符或控制字符，统一下划线。
     * 纯函数，便于单测。
     */
    internal fun sanitizeFileName(name: String): String {
        val cleaned = buildString {
            name.forEach { c ->
                append(if (c in ILLEGAL_FILENAME_CHARS || c.isISOControl()) '_' else c)
            }
        }
        return cleaned.trim().trim('.').ifEmpty { "app" }
    }

    private const val ILLEGAL_FILENAME_CHARS = "/\\:*?\"<>|"

    /**
     * 提取 [app] 的全部安装包到 [DOWNLOAD_DIR]。
     * 单包应用产出 1 个文件；分包应用产出 base + 每个 split 各一个文件。
     */
    suspend fun extract(context: Context, app: InstalledAppInfo): Result = withContext(Dispatchers.IO) {
        val dir = File(DOWNLOAD_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Result(false, "无法创建目录 $DOWNLOAD_DIR")
        }

        val rootGranted = runCatching { RootService.isRootGranted == true }.getOrDefault(false)
        val myId = Process.myUid()

        val planned = plannedTargets(app)

        val written = ArrayList<String>(planned.size)
        for ((name, src) in planned) {
            val dst = File(dir, name)
            val one = copyOne(src, dst, rootGranted, myId)
            if (!one.ok) return@withContext Result(false, one.message)
            written += dst.absolutePath
        }

        // 通知媒体库，让提取出的 APK 立即出现在其他文件管理器 / 扫描结果里（尽力而为）
        runCatching {
            MediaScannerConnection.scanFile(
                context, written.toTypedArray(), null, null
            )
        }

        val splitNote = if (app.isSplit) "（含 ${app.splitApks.size} 个分包）" else ""
        Result(
            true,
            "已提取到 Download$splitNote：${written.size} 个文件",
            written
        )
    }

    private class OneResult(val ok: Boolean, val message: String)

    /**
     * 复制单个 APK。优先 ROOT（应用私有安装包只有 root 可读）；
     * 无 ROOT 时退回本地直读（系统分区的 APK 全局可读，此时可用）。
     */
    private fun copyOne(src: String, dst: File, rootGranted: Boolean, myId: Int): OneResult {
        if (rootGranted) {
            val escapedSrc = RootService.escapeShellArg(src)
            val escapedDst = RootService.escapeShellArg(dst.absolutePath)
            // cp 后修正属主与上下文：root 直接写入 /storage 的文件若属主/上下文不对，
            // 其他应用与媒体库可能看不到（chown/restorecon 失败不阻断，属尽力而为）。
            val cmd = "cp -f $escapedSrc $escapedDst && chmod 0644 $escapedDst; " +
                "chown $myId:$myId $escapedDst 2>/dev/null; restorecon $escapedDst 2>/dev/null; " +
                "test -s $escapedDst"
            val (code, out) = RootService.runCommandSync(cmd, 120_000L)
            if (code != 0 || dst.length() <= 0L) {
                return OneResult(false, "复制失败: ${out.trim().take(200).ifEmpty { src }}")
            }
            return OneResult(true, "")
        }

        // 无 ROOT：仅当应用自身可读时才可行（典型为 /system 下的系统应用）
        return try {
            File(src).inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            if (dst.length() > 0L) OneResult(true, "")
            else OneResult(false, "复制结果为空: $src")
        } catch (e: Exception) {
            OneResult(
                false,
                "读取安装包失败：需要 ROOT 权限（${e.javaClass.simpleName}）"
            )
        }
    }
}
