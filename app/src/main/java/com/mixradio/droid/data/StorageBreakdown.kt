// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本应用在本机占用的全部存储成分。
 *
 * 口径：磁盘字节数。运行期数据按目录递归累加常规文件大小；符号链接按链接本身计入，
 * 不跟随，避免「内置环境 + Termux 前缀」成百上千条软链被重复统计。
 * APK 自身组成是安装包的静态构成，与运行期数据是两套账，分开列。
 */
object StorageBreakdown {

    data class Item(
        val group: String,
        val name: String,
        val path: String,
        val bytes: Long,
        val note: String = "",
        /** 该项是否无法测量（权限不足或路径不存在）。 */
        val unavailable: Boolean = false,
    )

    data class Report(
        val runtime: List<Item>,
        val apk: List<Item>,
        val generatedAtMillis: Long,
        /** 安装包总字节数，单列，不与 apk 分组项相加（分组项只是内部构成）。 */
        val apkFileBytes: Long,
    ) {
        val runtimeTotal: Long get() = runtime.sumOf { if (it.unavailable) 0 else it.bytes }
    }

    /**
     * 递归累加常规文件大小；符号链接跳过（其 length() 会读到链接目标，成百上千条
     * busybox/git 链会把同一份二进制反复计入）。不存在或不可读返回 null。
     */
    private fun dirBytes(path: String): Long? {
        val root = File(path)
        if (!root.exists()) return null
        return try {
            var total = 0L
            root.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                total += f.length()
            }
            total
        } catch (_: Exception) {
            null
        }
    }

    /** 单文件大小；不存在返回 null。 */
    private fun fileBytes(path: String): Long? {
        val f = File(path)
        if (!f.isFile) return null
        return try { f.length() } catch (_: Exception) { null }
    }

    /** ROOT 通道测量 /data 下应用 uid 看不到的目录，返回 (字节, 是否成功)。 */
    private fun rootDirBytes(path: String): Long? {
        val (code, out) = RootService.runCommandSync(
            "du -sb ${RootService.escapeShellArg(path)} 2>/dev/null | head -1",
            timeoutMs = 15_000L
        )
        if (code != 0) return null
        val first = out.lineSequence().firstOrNull()?.trim()?.substringBefore('\t')
            ?.substringBefore(' ')?.toLongOrNull()
        return first
    }

    private fun item(
        group: String, name: String, path: String,
        bytes: Long?, note: String = "", rootOnly: Boolean = false,
        hasRoot: Boolean = true,
    ): Item = when {
        bytes != null -> Item(group, name, path, bytes, note)
        // 只有「必须 ROOT 才能测」的项才标不可测；目录不存在属正常空，记为 0。
        rootOnly && !hasRoot -> Item(group, name, path, 0, "$note（需 ROOT 授权）", unavailable = true)
        else -> Item(group, name, path, 0, note)
    }

    suspend fun measure(context: Context): Report = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dataDir = app.applicationInfo.dataDir
        val filesDir = app.filesDir.absolutePath
        val cacheDir = app.cacheDir.absolutePath
        val prefsDir = File(dataDir, "shared_prefs").absolutePath
        val hasRoot = RootService.isRootGranted == true
        val internal = INTERNAL_STORAGE_PATH

        val r = ArrayList<Item>()

        // ── 1. 应用私有目录（filesDir 树）──────────────────────────────
        r += item("内置环境", "工具链解压目录", "$filesDir/env",
            dirBytes("$filesDir/env"), "首次执行时从 env.bin 解压，约 71 MB 源文件")
        r += item("内置环境", "升级暂存", "$filesDir/env.tmp",
            dirBytes("$filesDir/env.tmp"), "升级过程临时目录")
        r += item("内置环境", "升级备份", "$filesDir/env.old",
            dirBytes("$filesDir/env.old"), "升级失败时保留的旧版副本")
        r += item("内置环境", "旧版扩展包", "$filesDir/envpack",
            dirBytes("$filesDir/envpack"), "旧版遗留，启动即清理")
        r += item("语法包", "导入的语法文件", "$filesDir/syntax",
            dirBytes("$filesDir/syntax"), "用户导入的 Monarch 语法包，APK 不内置")
        r += item("私有数据", "files 其它", filesDir,
            otherFilesBytes(filesDir), "pgid 记录、另存临时文件、执行期临时文件等", hasRoot = hasRoot)
        r += item("私有数据", "编辑器历史", "$prefsDir/shso_editor.xml",
            fileBytes("$prefsDir/shso_editor.xml"), "每文件独立键的编辑快照")
        r += item("私有数据", "设置与书签", "$prefsDir/shso_settings.xml",
            fileBytes("$prefsDir/shso_settings.xml"), "偏好设置与书签")
        r += item("私有数据", "shared_prefs 其它", prefsDir,
            otherPrefsBytes(prefsDir), "其余偏好文件", hasRoot = hasRoot)

        // ── 2. 应用私有 cache 树 ──────────────────────────────────────
        r += item("应用缓存", "cache 全部", cacheDir,
            dirBytes(cacheDir), "安装确认暂存、XAPK 解压、分享副本、对比临时等", hasRoot = hasRoot)

        // ── 3. 私有目录下不使用的系统子目录（显式标注 0）──────────────
        r += Item("私有数据", "databases", File(dataDir, "databases").absolutePath,
            dirBytes(File(dataDir, "databases").absolutePath) ?: 0, "本应用不使用 SQLite")
        r += Item("私有数据", "no_backup", File(dataDir, "no_backup").absolutePath,
            dirBytes(File(dataDir, "no_backup").absolutePath) ?: 0, "本应用不写入")
        r += Item("私有数据", "code_cache", File(dataDir, "code_cache").absolutePath,
            dirBytes(File(dataDir, "code_cache").absolutePath) ?: 0, "系统管理的运行时代码缓存")

        // ── 4. ROOT 侧、落在本应用之外的产出 ───────────────────────────
        r += item("shso 工作目录", "添加到 shso 的文件", RootFileManager.DEFAULT_SHSO_DIR,
            if (hasRoot) rootDirBytes(RootFileManager.DEFAULT_SHSO_DIR) else dirBytes(RootFileManager.DEFAULT_SHSO_DIR),
            "「添加到 shso」的真实落点，root 创建 0777", rootOnly = true, hasRoot = hasRoot)
        r += item("shso 工作目录", "安装临时包", "/data/local/tmp",
            if (hasRoot) rootTmpBytes() else null,
            "_shso_install_ / _shso_split_ 安装残留", rootOnly = true, hasRoot = hasRoot)
        val oat = oatDirBytes(context)
        r += item("ART 编译产物", "oat / dalvik-cache", oat.first,
            oat.second, "系统编译产物，随使用增长", rootOnly = true, hasRoot = hasRoot)

        // ── 5. 共享存储（公共 Download）───────────────────────────────
        val inbox = "$internal/Download/shso"
        r += item("共享存储", "收件箱 Download/shso", inbox,
            dirBytes(inbox), "分享 / 打开方式的副本落点", hasRoot = hasRoot)
        val dl = "$internal/Download"
        r += item("共享存储", "提取的 APK（Download）", dl,
            extractedApkBytes(dl), "提取 APK 产出的 *.APK", hasRoot = hasRoot)
        r += Item("共享存储", "Android/data 私有目录", "$internal/Android/data/${context.packageName}",
            dirBytes("$internal/Android/data/${context.packageName}") ?: 0, "本应用不写入外部私有目录")

        // ── 6. APK 自身静态组成 ───────────────────────────────────────
        val a = ArrayList<Item>()
        a += Item("APK 组成", "内置环境归档 env.bin", "assets/env.bin",
            assetBytes(context, "env.bin"), "内置 aarch64 工具链压缩包")
        a += Item("APK 组成", "编辑器主题", "assets/sora-themes/shso-dark.json",
            assetBytes(context, "sora-themes/shso-dark.json"), "Sora Editor 主题")
        a += Item("APK 组成", "classes.dex", "classes.dex",
            apkEntryBytes(context, "classes.dex"), "应用代码（R8 收缩后）")
        a += Item("APK 组成", "原生库 lib/*.so", "lib/arm64-v8a",
            apkEntryBytes(context, "lib/"), "zstd-jni 等，仅 arm64-v8a")
        a += Item("APK 组成", "资源 resources.arsc + res", "res / resources.arsc",
            apkEntryBytes(context, "res/") + apkEntryBytes(context, "resources.arsc"),
            "已收缩资源，名称为随机短名")

        Report(r, a, System.currentTimeMillis(), apkFileBytes(context))
    }

    /** files 目录减去已单列的 env/env.tmp/env.old/envpack/syntax，避免重复计数。 */
    private fun otherFilesBytes(filesDir: String): Long? {
        val skip = setOf("env", "env.tmp", "env.old", "envpack", "syntax")
        val root = File(filesDir)
        if (!root.exists()) return null
        return try {
            var total = 0L
            root.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                val top = f.absolutePath.removePrefix(filesDir).trimStart('/')
                    .split('/').firstOrNull()
                if (top == null || top !in skip) total += f.length()
            }
            total
        } catch (_: Exception) { null }
    }

    /** shared_prefs 减去已单列的两个 xml。 */
    private fun otherPrefsBytes(prefsDir: String): Long? {
        val skip = setOf("shso_editor.xml", "shso_settings.xml")
        val root = File(prefsDir)
        if (!root.exists()) return null
        return try {
            root.listFiles()?.filter { it.isFile && it.name !in skip }
                ?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { null }
    }

    /** 只统计 /data/local/tmp 下 _shso_ 前缀的安装残留。 */
    private fun rootTmpBytes(): Long? {
        val (code, out) = RootService.runCommandSync(
            "du -sb /data/local/tmp/_shso_* 2>/dev/null", timeoutMs = 15_000L
        )
        if (code != 0 && out.isBlank()) return 0L
        return out.lineSequence().mapNotNull { line ->
            line.trim().substringBefore('\t').substringBefore(' ').toLongOrNull()
        }.sum().takeIf { it > 0 } ?: 0L
    }

    /** ART 产物：只统计本应用 oat 目录与 dalvik-cache 中含本包名的文件。 */
    private fun oatDirBytes(context: Context): Pair<String, Long?> {
        val pkg = context.packageName
        val (_, out) = RootService.runCommandSync("pm path $pkg", timeoutMs = 10_000L)
        val apk = out.lineSequence().firstOrNull()?.removePrefix("package:")?.trim()
        val parent = apk?.substringBeforeLast('/')
        val label = parent ?: "/data/app/..."
        if (RootService.isRootGranted != true) return label to null
        var total = 0L
        var ok = false
        // 1) 本包 oat 目录
        if (parent != null) {
            val (c, o) = RootService.runCommandSync(
                "du -sb ${RootService.escapeShellArg("$parent/oat")} 2>/dev/null | cut -f1",
                timeoutMs = 15_000L
            )
            o.lineSequence().firstOrNull()?.trim()?.toLongOrNull()?.let { total += it; ok = true }
            if (c != 0 && !ok) return label to null
        }
        // 2) dalvik-cache 中本包条目（精确匹配包名，不把其它应用算进来）
        val (c2, o2) = RootService.runCommandSync(
            "find /data/dalvik-cache -maxdepth 3 -name " +
                RootService.escapeShellArg("*$pkg*") +
                " -type f -exec stat -c %s {} \\; 2>/dev/null",
            timeoutMs = 20_000L
        )
        if (c2 == 0) {
            o2.lineSequence().mapNotNull { it.trim().toLongOrNull() }.forEach { total += it; ok = true }
        }
        return label to if (ok) total else null
    }

    /** Download 目录里本应用提取的 *.APK（不把用户其它文件算进来）。 */
    private fun extractedApkBytes(download: String): Long? {
        val root = File(download)
        if (!root.exists()) return null
        return try {
            root.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".APK", ignoreCase = true) }
                ?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { null }
    }

    private fun assetBytes(context: Context, name: String): Long = try {
        context.assets.openFd(name).length
    } catch (_: Exception) {
        try { context.assets.open(name).use { it.available().toLong() } } catch (_: Exception) { 0L }
    }

    private fun apkFileBytes(context: Context): Long = try {
        File(context.applicationInfo.sourceDir).length()
    } catch (_: Exception) { 0L }

    /** 用 ZipFile 取 APK 内某前缀条目的压缩后字节总和。 */
    private fun apkEntryBytes(context: Context, prefix: String): Long = try {
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { z ->
            val e = z.entries()
            var total = 0L
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.name.startsWith(prefix) ||
                    (prefix == "resources.arsc" && entry.name == "resources.arsc")) {
                    total += entry.compressedSize.coerceAtLeast(0)
                }
            }
            total
        }
    } catch (_: Exception) { 0L }
}
