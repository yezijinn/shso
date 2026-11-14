// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * 本应用在本机占用的全部存储成分。
 *
 * 两套账，互不相加：
 * 1. **运行期数据**：磁盘实际字节，按目录递归累加常规文件。符号链接不跟随、也不计自身 ——
 *    内置环境里有 373 条软链（busybox applet / git 子命令），跟随计数会把同一份二进制
 *    重复算几十遍。
 * 2. **APK 安装包**：安装包内部构成（静态），取 ZIP 条目的压缩后大小。
 *
 * 共享存储（Download）**不计入**：那里的文件归用户所有，不是本应用的占用；
 * 且 Download 下的 APK 无法与用户自己的文件区分，统计它只会把别人的文件算进来。
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
     * `filesDir/env` 的顶层组成部分。
     *
     * [top] 为 null 表示「顶层常规文件」（只有 VERSION），按文件求和而不是目录递归。
     * 顺序即展示顺序：可执行文件在前，版本标记在末。
     */
    internal data class EnvComponent(
        val top: String?,
        val label: String,
        val note: String,
    )

    /**
     * 与 `assets/env.bin` 的顶层结构一一对应（实测：bin / lib / libexec / share / etc /
     * 根 VERSION）。改这里必须同步 `tools/build_env_bin.py` 的归档布局，
     * 否则 UI 会凭空多出 0 字节条目。
     */
    internal val ENV_COMPONENTS: List<EnvComponent> = listOf(
        EnvComponent(
            "bin",
            "bin（可执行文件）",
            "bash、busybox、git、python3、curl、openssl、ssh、rsync 与 GNU sed/tar/wget/grep/find；" +
                "另有指向 busybox 的 applet 入口（软链，不计字节）"
        ),
        EnvComponent(
            "lib",
            "lib（共享库与标准库）",
            "python3.14 标准库主体，以及 libcrypto / libssl / libz / libcurl 等 .so"
        ),
        EnvComponent(
            "libexec",
            "libexec（git 辅助程序）",
            "git-core 下的子命令与辅助程序，只在 git 执行对应操作时才加载"
        ),
        EnvComponent(
            "share",
            "share（只读数据）",
            "applets.txt 清单、git-core 模板、terminfo 等只读资源"
        ),
        EnvComponent(
            "etc",
            "etc（配置与证书）",
            "tls/ 证书目录与 openssl.cnf、cert.pem —— curl / openssl 校验服务端证书的来源"
        ),
        EnvComponent(
            null,
            "根文件（VERSION）",
            "只有 VERSION 一个文件（内容为环境版本号）；版本缺失或与内置不符即整目录重建"
        ),
    )

    /**
     * 递归累加常规文件大小；符号链接跳过（其 length() 会读到链接目标，
     * 成百上千条 busybox/git 链会把同一份二进制反复计入）。不存在或不可读返回 null。
     */
    internal fun sumRegularFileBytes(root: File): Long? {
        if (!root.exists()) return null
        return try {
            var total = 0L
            root.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                if (Files.isSymbolicLink(f.toPath())) return@forEach
                total += f.length()
            }
            total
        } catch (_: Exception) {
            null
        }
    }

    /** 目录字节数（不存在返回 null，由 [item] 归一为 0）。 */
    private fun dirBytes(path: String): Long? = sumRegularFileBytes(File(path))

    /**
     * 目录下**顶层常规文件**的字节和（不递归、不计软链）。
     *
     * 用于 env 的根文件（VERSION）：走 [dirBytes] 会把 bin/lib/... 全部重复算一遍。
     */
    internal fun topLevelFileBytes(dirPath: String): Long? {
        val root = File(dirPath)
        if (!root.isDirectory) return null
        return try {
            root.listFiles()
                ?.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                ?.sumOf { it.length() } ?: 0L
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

        val r = ArrayList<Item>()

        // ── 1. 内置环境：env 解压目录按顶层组件分列 ──────────────────────
        // 不再给整个 env 目录放一条总数：组小计由存储页按组求和显示，
        // 再放一条总数会与 6 条组件重复计入 runtimeTotal。
        val envRoot = "$filesDir/env"
        for (component in ENV_COMPONENTS) {
            val path = if (component.top == null) envRoot else "$envRoot/${component.top}"
            val bytes = if (component.top == null) topLevelFileBytes(envRoot) else dirBytes(path)
            r += item("内置环境", component.label, path, bytes, component.note)
        }
        r += item("内置环境", "升级暂存", "$filesDir/env.tmp",
            dirBytes("$filesDir/env.tmp"), "升级过程临时目录，失败即删")
        r += item("内置环境", "升级备份", "$filesDir/env.old",
            dirBytes("$filesDir/env.old"), "升级失败时保留的旧版副本，环境可用时启动即删")
        r += item("内置环境", "旧版扩展包", "$filesDir/envpack",
            dirBytes("$filesDir/envpack"), "旧版遗留，启动即清理")

        // ── 2. 语法包 / 私有数据 ────────────────────────────────────────
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

        // ── 3. 应用私有 cache 树 ──────────────────────────────────────
        r += item("应用缓存", "cache 全部", cacheDir,
            dirBytes(cacheDir), "安装确认暂存、XAPK 解压、分享副本、播放副本、对比临时等",
            hasRoot = hasRoot)

        // ── 4. 私有目录下不使用的系统子目录（显式标注 0）──────────────
        r += Item("私有数据", "databases", File(dataDir, "databases").absolutePath,
            dirBytes(File(dataDir, "databases").absolutePath) ?: 0, "本应用不使用 SQLite")
        r += Item("私有数据", "no_backup", File(dataDir, "no_backup").absolutePath,
            dirBytes(File(dataDir, "no_backup").absolutePath) ?: 0, "本应用不写入")
        r += Item("私有数据", "code_cache", File(dataDir, "code_cache").absolutePath,
            dirBytes(File(dataDir, "code_cache").absolutePath) ?: 0, "系统管理的运行时代码缓存")

        // ── 5. ROOT 侧、落在本应用之外的产出 ───────────────────────────
        r += item("shso 工作目录", "添加到 shso 的文件", RootFileManager.DEFAULT_SHSO_DIR,
            if (hasRoot) rootDirBytes(RootFileManager.DEFAULT_SHSO_DIR)
            else dirBytes(RootFileManager.DEFAULT_SHSO_DIR),
            "「添加到 shso」的真实落点，root 创建 0777", rootOnly = true, hasRoot = hasRoot)
        r += item("shso 工作目录", "安装临时包", "/data/local/tmp",
            if (hasRoot) rootTmpBytes() else null,
            "_shso_install_ / _shso_split_ 安装残留", rootOnly = true, hasRoot = hasRoot)
        val oat = oatDirBytes(context)
        r += item("ART 编译产物", "oat / dalvik-cache", oat.first,
            oat.second, "系统编译产物，随使用增长", rootOnly = true, hasRoot = hasRoot)

        // ── 6. APK 自身静态组成 ───────────────────────────────────────
        val a = ArrayList<Item>()
        a += Item("APK 组成", "内置环境归档 env.bin", "assets/env.bin",
            assetBytes(context, "env.bin"),
            "gzip 压缩的 aarch64 工具链；解包后即上方「内置环境」的 6 项" +
                "（bin / lib / libexec / share / etc / 根文件），未压缩合计约 71.5 MB")
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

    /** files 目录减去已单列的 env 系列 / syntax，避免重复计数。 */
    private fun otherFilesBytes(filesDir: String): Long? {
        val skip = setOf("env", "env.tmp", "env.old", "envpack", "syntax")
        val root = File(filesDir)
        if (!root.exists()) return null
        return try {
            var total = 0L
            root.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                if (Files.isSymbolicLink(f.toPath())) return@forEach
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
        // 1) 本包 oat 目录（不存在时跳过，不影响下面 dalvik-cache 的统计）
        if (parent != null) {
            val (_, o) = RootService.runCommandSync(
                "du -sb ${RootService.escapeShellArg("$parent/oat")} 2>/dev/null | cut -f1",
                timeoutMs = 15_000L
            )
            o.lineSequence().firstOrNull()?.trim()?.toLongOrNull()?.let { total += it; ok = true }
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

    /** APK 内 asset 的压缩后体积；压缩型 asset 不能走 openFd（会抛异常）。 */
    private fun assetBytes(context: Context, name: String): Long = try {
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { z ->
            z.getEntry("assets/$name")?.compressedSize?.coerceAtLeast(0) ?: 0L
        }
    } catch (_: Exception) { 0L }

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
