// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 创建压缩包（zip）。
 *
 * 走内置环境的 `zip`（随基础环境分发，见 [EmbeddedEnv]）经 root 执行：
 * 这样对 `/data/adb` 一类应用读不到的路径同样可用，也能正确处理文件夹递归与权限位。
 * 内置 zip 缺失（环境未就绪）时退回系统 `zip`（多半不存在，会如实报错）。
 */
object ArchiveCreator {

    /**
     * 把 [sourcePaths]（同目录下的若干条目）打包为 [targetZipPath]。
     *
     * @return (是否成功, 提示语)
     */
    suspend fun createZip(sourcePaths: List<String>, targetZipPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            if (sourcePaths.isEmpty()) return@withContext Pair(false, "没有可打包的项目")
            // 目标与源同名时命令前缀会先删掉唯一的源，zip 再报 Nothing to do —— 必须前置拒绝。
            sourceCollisionReason(sourcePaths, targetZipPath)?.let {
                return@withContext Pair(false, it)
            }
            // 冷启动时后台解压可能尚未完成：先确保内置环境就绪，再读取其目录；
            // 失败（资产缺失 / 解压失败）才退回系统 zip，与旧行为一致。
            val envReady = runCatching {
                EmbeddedEnv.ensure(com.mixradio.droid.ShsoApplication.appContext)
            }.getOrDefault(false)
            val first = File(sourcePaths.first())
            val parent = first.parent ?: "/"
            val names = sourcePaths.map { File(it).name }
            // 所有源必须在同一目录（菜单入口即同目录单/多选）
            if (sourcePaths.any { (File(it).parent ?: "/") != parent }) {
                return@withContext Pair(false, "仅支持打包同一目录下的项目")
            }

            val embeddedZip = if (envReady) {
                EmbeddedEnv.binDir?.let { "$it/zip" }?.takeIf { File(it).isFile }
            } else null
            val zipBin = embeddedZip ?: "zip"
            val libDir = if (embeddedZip != null) EmbeddedEnv.libDir else null

            val cmd = buildCreateZipCommand(parent, zipBin, targetZipPath, libDir, names)
            val (code, out) = RootService.runCommandSync(cmd, timeoutMs = 180_000L)
            if (code == 0) {
                true to "已创建压缩包: ${File(targetZipPath).name}"
            } else {
                false to "创建压缩包失败: " + out.trim().take(200).ifEmpty { "退出码 $code" }
            }
        }

    /**
     * 目标名与源同名的兜底拒绝。
     *
     * [buildCreateZipCommand] 的前缀是 `rm -f <target>`：目标名一旦与某个源相同，唯一的源
     * 会先被删掉，随后 `zip` 因「没有可打包的东西」退出 12 —— 用户只看到「创建失败」，
     * 数据已经没了（实机：`zip error: Nothing to do!`，rc=12，`test -s` rc=1）。
     */
    internal fun sourceCollisionReason(sourcePaths: List<String>, targetZipPath: String): String? =
        if (sourcePaths.any { samePath(it, targetZipPath) }) {
            "目标压缩包与待打包文件同名，已取消（未改动任何文件）"
        } else {
            null
        }

    /** 归一后比较两个路径；软链 / `.` / `..` 由 [File.getCanonicalPath] 处理，不可解析时退回绝对路径。 */
    private fun samePath(a: String, b: String): Boolean {
        val fa = File(a)
        val fb = File(b)
        return runCatching { fa.canonicalPath == fb.canonicalPath }
            .getOrElse { fa.absolutePath == fb.absolutePath }
    }

    /**
     * 为 [itemName] 选一个**不会撞名**的 zip 目标名。
     *
     * 规则：`<base>.zip`；若它与源同名（`foo.zip` → `foo.zip`，正是本条缺陷的触发形态）或
     * 已被占用，退到 `<全名>.zip`、`<base>(2).zip`…；99 次仍冲突返回 null。
     *
     * 两条硬约束：
     * 1. **绝不返回源路径**（命令前缀 `rm -f <target>` 会先删掉它）。比较按小写进行：
     *    共享存储上存在大小写不敏感卷，`a.ZIP` 与 `a.zip` 是同一个文件。
     * 2. **绝不返回已存在的路径**（否则静默覆盖用户原有归档）。
     *
     * [taken] 声明为 suspend：ROOT 目录（如 `/data/adb`）用应用自身 `File.exists()` 恒为
     * false，必须由调用方传 `RootFileManager.pathExists`（内部走 `su test -e`）。
     */
    internal suspend fun uniqueZipTarget(
        parent: String,
        itemName: String,
        taken: suspend (String) -> Boolean = { File(it).exists() }
    ): String? {
        fun join(name: String) = if (parent == "/") "/$name" else "$parent/$name"
        val lower = itemName.lowercase()
        fun isSourceName(name: String) = name.lowercase() == lower
        val base = itemName.substringBeforeLast('.', itemName).ifEmpty { itemName }
        val derivedName = "$base.zip"
        if (isSourceName(derivedName)) {
            // 派生名与源同名：先退到全名，保证永不返回源路径。
            val full = join("$itemName.zip")
            if (!taken(full)) return full
        } else {
            val derived = join(derivedName)
            if (!taken(derived)) return derived
        }
        for (n in 2..99) {
            val altName = "$base($n).zip"
            if (isSourceName(altName)) continue
            val alt = join(altName)
            if (!taken(alt)) return alt
        }
        return null
    }

    /**
     * 拼出打包命令（纯函数，可 JVM 单测）。
     *
     * **`--` 不可省**：`zip` 把任何以 `-` 开头的 argv 当选项，直到显式终止符。
     * 名为 `-m` 的文件会被当成「打包后删除源文件」，`-P` 当成口令，`-x` 让后续名字变排除表。
     * 加 `--` 之后全部按普通文件名入包，目录递归 `-r` 不受影响。
     * `--` 之后不得再出现任何选项。
     */
    internal fun buildCreateZipCommand(
        parent: String,
        zipBin: String,
        targetZipPath: String,
        libDir: String?,
        names: List<String>
    ): String {
        val escapedParent = RootService.escapeShellArg(parent)
        val escapedZip = RootService.escapeShellArg(targetZipPath)
        val escapedNames = names.joinToString(" ") { RootService.escapeShellArg(it) }
        val libExport = if (libDir != null) {
            "LD_LIBRARY_PATH=" + RootService.escapeShellArg(libDir) + ":${'$'}LD_LIBRARY_PATH "
        } else ""
        return "cd $escapedParent && rm -f $escapedZip && " +
            libExport + RootService.escapeShellArg(zipBin) + " -r -q $escapedZip -- $escapedNames && " +
            "test -s $escapedZip"
    }
}
