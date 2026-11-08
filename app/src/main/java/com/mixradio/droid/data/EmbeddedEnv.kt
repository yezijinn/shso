// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import android.content.Context
import com.mixradio.droid.data.security.ShellEscapes
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * 内置完整 POSIX 工具链（bash / busybox / curl / GNU 工具 / git / python / openssl / ssh / rsync）。
 *
 * ## 为什么内置
 *
 * Android 原生**不带** `bash` / `wget` / `curl` / `git` / `python3` / `openssl` / `ssh`，
 * 现实中的 `.sh` 脚本却大量依赖它们：Termux 打包的自解压脚本 shebang 指向
 * `#!/data/data/com.termux/files/usr/bin/bash`，联网脚本调 `curl`，
 * 解包脚本调 `tar` / `unzip` / `xz`。设备未装 Termux 时这些命令全部缺失，
 * 脚本按 127 退出、只留一行 `not found` —— 表现为「环境缺失、执行不了」。
 *
 * 本类把整套 aarch64 工具链随 APK 分发，首次使用时解压到应用私有目录，
 * 执行前由 [RootService] 注入 `PATH` / `PREFIX` 与证书变量；语言运行时与 git 资源
 * 由编译期前缀定位（见 [TermuxPrefix] 的前缀补链），不再靠全局环境变量。
 *
 * ## 落点选择
 *
 * 解压到 `filesDir/env`。Android 10+ 的 W^X 只禁止**应用自身 uid**执行应用数据目录里的
 * 文件；本应用脚本一律经 `su -c` 以 root 执行，root 不受该限制（真机实测 root 可从
 * app 私有目录执行二进制）。
 *
 * ## 版本化与可重试
 *
 * 归档内含 `VERSION`；与 [ENV_VERSION] 不一致时整目录重建，
 * 避免旧版残留的半套库把新二进制带崩（`CANNOT LINK EXECUTABLE`）。
 * 解压先落到 `env.tmp` 并校验必需文件，再整体替换 —— 中途失败不破坏可用环境，
 * 且失败不固化状态，下次调用可重试。
 */
object EmbeddedEnv {

    /** 资产归档名（随 APK 分发）；内容本身是 gzip tar，构建配置关闭二次压缩。 */
    private const val ASSET = "env.bin"

    /** 解压目标目录名（应用私有目录下）。 */
    private const val DIR_NAME = "env"

    /** 解压暂存目录名（成功后整体替换 [DIR_NAME]）。 */
    private const val STAGING_NAME = "env.tmp"

    /** 环境版本。改动内置内容时同步 +1（与归档内 VERSION 一致）。 */
    private const val ENV_VERSION = "5"

    /** busybox applet 清单（归档内 share/applets.txt）。 */
    private const val APPLETS_FILE = "share/applets.txt"

    /**
     * shell applet 黑名单：**一律不生成**指向 busybox 的入口。
     *
     * 内置 busybox 的 shell applet 在本机直接执行即 SIGSEGV（`sh` 已实测，`ash` / `hush`
     * 走同一份 ash 实现；`mash` 亦然）。清单里出现这些名字时必须挡掉，
     * 否则 `#!/.../bin/sh`、`#!/.../bin/ash` 类脚本按字面路径命中崩溃入口。
     * sh 由 [TermuxPrefix] 链 `/system/bin/sh` 兜底，其余同名入口同样落到系统 sh。
     */
    private val SHELL_APPLETS = setOf("sh", "ash", "hush", "mash")

    /** 旧版「扩展环境包」目录名：已并入内置资产，安装成功后清理一次。 */
    private const val LEGACY_PACK_DIR = "envpack"

    /** 单次 [ensure] 的安装尝试次数；临时失败（存储抖动 / 资产未就绪）可重试。 */
    private const val INSTALL_ATTEMPTS = 2

    /**
     * 必需文件：缺任一项都视为环境不完整，需重建。
     *
     * 覆盖脚本最常见的入口——shell、POSIX 工具、git、python、openssl、ssh、rsync。
     */
    private val REQUIRED_FILES = listOf(
        "bin/bash",
        "bin/busybox",
        "bin/python3",
        "bin/git",
        "bin/openssl",
        "bin/ssh",
        "bin/rsync",
        "bin/sed",
        "bin/tar",
        "bin/wget",
        "bin/grep",
        "bin/find",
    )

    @Volatile
    private var envDir: File? = null

    val binDir: String? get() = envDir?.let { File(it, "bin").takeIf(File::isDirectory)?.absolutePath }
    val libDir: String? get() = envDir?.let { File(it, "lib").takeIf(File::isDirectory)?.absolutePath }
    val certFile: String? get() = envDir?.let { File(it, "etc/cert.pem").takeIf(File::isFile)?.absolutePath }

    /** 内置 bash 绝对路径（存在才算）。 */
    val bashPath: String? get() = envDir?.let { File(it, "bin/bash").takeIf(File::isFile)?.absolutePath }

    /**
     * 确保环境已解压就绪。首次调用做实际解压，之后直接返回缓存结果。
     *
     * 失败**不缓存**：返回 false 后再次调用会重新尝试（[INSTALL_ATTEMPTS] 次/调用），
     * 避免一次存储抖动把整个会话钉死在降级路径上。
     *
     * @return 是否可用（资产缺失 / 解压失败时为 false，调用方应降级到系统 PATH）。
     */
    fun ensure(context: Context): Boolean {
        envDir?.let { return true }
        synchronized(this) {
            envDir?.let { return true }
            repeat(INSTALL_ATTEMPTS) {
                val dir = runCatching { install(context) }.getOrNull()
                if (dir != null && isComplete(dir)) {
                    envDir = dir
                    return true
                }
            }
            envDir = null
            return false
        }
    }

    /**
     * 生成执行前导片段：注入 PATH、前缀变量与证书变量。
     *
     * 内置目录**前置**到 PATH：脚本调用 `curl` / `git` / `python3` 时优先命中内置版本，
     * 不依赖设备是否装了第三方工具箱。系统目录仍保留在其后。
     *
     * **刻意不导出** `PYTHONHOME` / `PYTHONPATH` / `GIT_EXEC_PATH` / `GIT_TEMPLATE_DIR` /
     * `OPENSSL_CONF`：这些变量会劫持**真实 Termux** 的同名工具（解释器被迫用内置 stdlib、
     * git 用内置 exec-path、openssl 用内置配置），导致装了 Termux 的设备反而环境错乱。
     * 内置工具自身按编译期前缀（`$PREFIX`）定位资源，由 [TermuxPrefix] 补齐缺失的
     * `bin/` / `lib/` / `libexec/` / `share/` 入口即可，无需环境变量。
     *
     * 未就绪时返回空串（调用方退回系统 PATH，行为与旧版一致）。
     */
    fun exportFragment(): String {
        val root = envDir ?: return ""
        val bin = binDir ?: return ""
        val lib = libDir ?: return ""
        val sb = StringBuilder()
        // 前置内置 bin；把原来的 PATH 接在后面，系统工具仍可用
        sb.append("export PATH=").append(ShellEscapes.escapeShellArg(bin)).append(":\$PATH; ")
        // Termux 系脚本普遍用 $PREFIX/bin、$PREFIX/etc 定位资源
        sb.append("export PREFIX=").append(ShellEscapes.escapeShellArg(root.absolutePath)).append("; ")
        sb.append("export TERMUX_PREFIX=").append(ShellEscapes.escapeShellArg(root.absolutePath)).append("; ")
        // bash / curl / zip 的 RUNPATH 指向 Termux 前缀。**优先**靠 TermuxPrefix 的前缀符号链接解析；
        // 仅在其不可用（无 root，无法建前缀）时才退回导出 LD_LIBRARY_PATH —— 后者会污染子进程，
        // 让内置 libz 等顶替系统库的依赖，导致脚本里的原生程序 CANNOT LINK EXECUTABLE。
        if (!TermuxPrefix.ready) {
            sb.append("export LD_LIBRARY_PATH=").append(ShellEscapes.escapeShellArg(lib)).append(":\$LD_LIBRARY_PATH; ")
        }
        certFile?.let { cert ->
            sb.append("export SSL_CERT_FILE=").append(ShellEscapes.escapeShellArg(cert)).append("; ")
            sb.append("export CURL_CA_BUNDLE=").append(ShellEscapes.escapeShellArg(cert)).append("; ")
        }
        // 归档不含 less / pager：git 遇到需要分页的命令会直接 `fatal: unable to execute pager`。
        // 固定 cat 直出，等价于无分页器的终端行为，且不会再报错。
        sb.append("export GIT_PAGER=cat; ")
        return sb.toString()
    }

    private fun install(context: Context): File? {
        val root = File(context.filesDir, DIR_NAME)
        if (isReady(root)) {
            // 上次升级中途失败可能留下整份 env.old（几十 MB），环境已可用时无保留价值。
            runCatching { File(context.filesDir, "$DIR_NAME.old").deleteRecursively() }
            sanitizeShellApplets(root)
            cleanupLegacy(context)
            return root
        }

        val staging = File(context.filesDir, STAGING_NAME)
        runCatching { staging.deleteRecursively() }
        if (!staging.mkdirs() && !staging.isDirectory) return null

        val ok = runCatching {
            extractAll(context, staging)
            createAppletLinks(staging)
            runCatching { File(staging, "VERSION").writeText(ENV_VERSION + "\n") }
            isComplete(staging)
        }.getOrDefault(false)

        if (!ok) {
            runCatching { staging.deleteRecursively() }
            return null
        }

        // 整体替换：旧目录先改名为备份，再 rename 暂存目录；任一步失败回滚旧环境。
        // 全程只用同一文件系统内的 rename（不复制、不跟随软链）：
        // 旧实现 rename 失败时 copyRecursively 会把归档里的软链展开成实体文件，
        // 让 lib/ 下几十个多版本 .so 实体化，存储峰值直接翻数倍。
        val backup = File(context.filesDir, "$DIR_NAME.old")
        runCatching { backup.deleteRecursively() }
        if (root.exists()) {
            // 改名失败说明无法原子替换：宁可保留**当前可用环境**并放弃本次升级，
            // 也不删旧目录去赌一次可能失败的搬移。
            val hadOld = runCatching { root.renameTo(backup) }.getOrDefault(false)
            if (!hadOld) {
                runCatching { staging.deleteRecursively() }
                return null
            }
        }

        val moved = runCatching { staging.renameTo(root) }.getOrDefault(false)
        if (!moved) {
            runCatching { staging.deleteRecursively() }
            // 回滚：备份若存在则原名复位（备份不存在时 rename 失败，根目录本就为空）。
            if (root.exists()) runCatching { root.deleteRecursively() }
            runCatching { backup.renameTo(root) }
            return null
        }
        if (!isComplete(root)) {
            runCatching { root.deleteRecursively() }
            runCatching { backup.renameTo(root) }
            return null
        }
        sanitizeShellApplets(root)
        runCatching { backup.deleteRecursively() }
        cleanupLegacy(context)
        return root
    }

    /**
     * 清掉旧版本装好、仍指向 busybox 的 shell applet 入口。
     *
     * 已安装环境版本号未变时不会重建，旧 `bin/sh` / `bin/ash` 会一直留在 `PATH` 里，
     * 脚本按命令名或 shebang 命中即 SIGSEGV。只删「软链 + 目标名为 busybox」的条目，
     * 真实文件与外部目标一律不动。
     */
    private fun sanitizeShellApplets(root: File) {
        val bin = File(root, "bin")
        for (name in SHELL_APPLETS) {
            val link = File(bin, name)
            if (!runCatching { Files.isSymbolicLink(link.toPath()) }.getOrDefault(false)) continue
            val target = runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull() ?: continue
            if (target.substringAfterLast('/') != "busybox") continue
            runCatching { link.delete() }
        }
    }

    /** 版本标记与必需文件齐备才算就绪。 */
    private fun isReady(root: File): Boolean {
        if (!root.isDirectory) return false
        val installed = runCatching { File(root, "VERSION").readText().trim() }.getOrNull()
        return installed == ENV_VERSION && isComplete(root)
    }

    private fun isComplete(root: File): Boolean = REQUIRED_FILES.all { File(root, it).isFile }

    /** 旧版扩展环境包已并入内置资产：删除目录与残留下载文件。 */
    private fun cleanupLegacy(context: Context) {
        if (File(context.filesDir, LEGACY_PACK_DIR).exists()) {
            runCatching { File(context.filesDir, LEGACY_PACK_DIR).deleteRecursively() }
        }
        if (File(context.cacheDir, "$LEGACY_PACK_DIR.download").exists()) {
            runCatching { File(context.cacheDir, "$LEGACY_PACK_DIR.download").delete() }
        }
    }

    /**
     * 解压全部条目。
     *
     * 分两趟：先目录与普通文件（落内容、补模式位），再软链。
     * 软链单独处理，保证其目标文件先就位；创建失败时退化为复制目标文件。
     */
    private fun extractAll(context: Context, root: File) {
        val links = ArrayList<Pair<String, String>>()
        context.assets.open(ASSET).use { raw ->
            GzipCompressorInputStream(raw).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = normalize(entry.name)
                        if (name != null) {
                            when {
                                entry.isDirectory -> ensureDir(root, name)
                                entry.isSymbolicLink -> links += name to entry.linkName
                                entry.isFile -> writeFile(root, name, tar, entry.mode)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
        for ((name, linkName) in links) createLink(root, name, linkName)
    }

    /** 归一条目名；绝对路径 / 含 `..` / 空名一律拒绝。 */
    private fun normalize(raw: String): String? {
        val name = raw.replace('\\', '/').removePrefix("./").trimEnd('/')
        if (name.isEmpty() || name.startsWith("/")) return null
        if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return name
    }

    private fun ensureDir(root: File, name: String) {
        val dir = File(root, name)
        if (!isInside(root, dir)) return
        dir.mkdirs()
        applyDirMode(dir)
    }

    private fun writeFile(root: File, name: String, tar: TarArchiveInputStream, mode: Int) {
        val target = File(root, name)
        if (!isInside(root, target)) return
        target.parentFile?.mkdirs()
        runCatching { target.delete() }
        target.outputStream().use { out -> tar.copyTo(out) }
        applyFileMode(target, name, mode)
    }

    /** 目录统一 0755（root 可进可读）。 */
    private fun applyDirMode(dir: File) {
        runCatching { dir.setReadable(true, false) }
        runCatching { dir.setExecutable(true, false) }
        runCatching { dir.setWritable(true, true) }
    }

    /** 文件按归档模式位落权限；`bin/` 与 `libexec/` 下的命令入口强制可执行。 */
    private fun applyFileMode(file: File, name: String, mode: Int) {
        val executable = name.startsWith("bin/") || name.startsWith("libexec/") || (mode and 0b001_001_001 != 0)
        runCatching { file.setReadable(true, false) }
        runCatching { file.setWritable(mode and 0b010_000_000 != 0, true) }
        runCatching { file.setExecutable(executable, false) }
    }

    /**
     * 建立软链。
     *
     * 目标按「父目录 + linkName」canonical 校验：**允许**合法的 `../`（Termux 包常见
     * `../../bin/git` 这类跨目录引用），解析结果必须仍在环境根内，越界一律拒绝。
     * 文件系统不支持软链或权限不足时，退化为复制已在同一目录树内的目标文件。
     */
    private fun createLink(root: File, name: String, linkName: String) {
        val link = File(root, name)
        if (!isInside(root, link)) return
        if (linkName.startsWith("/")) return
        val parent = link.parentFile ?: return
        val resolved = runCatching { File(parent, linkName).canonicalFile }.getOrNull() ?: return
        if (!isInside(root, resolved)) return
        parent.mkdirs()
        runCatching { link.delete() }
        runCatching {
            Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(linkName))
        }.onFailure {
            runCatching {
                if (resolved.isFile) resolved.copyTo(link, overwrite = true)
            }
        }
    }

    /** 路径是否位于 root 内（canonical 比较，符号链接与 `..` 均已展开）。 */
    private fun isInside(root: File, file: File): Boolean {
        val base = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return path.startsWith(base + File.separator)
    }

    /**
     * 为每个 busybox applet 建立 `bin/<applet> -> busybox` 符号链接。
     *
     * busybox 按 argv[0] 分派 applet；`<applet> <args>` 这种调用方式要求
     * 存在以 applet 命名的入口，否则脚本里写 `wget ...` 找不到命令。
     *
     * **不建 shell applet**（[SHELL_APPLETS]：sh / ash / hush / mash）：内置 busybox 的
     * shell applet 在本机直接执行即崩溃（SIGSEGV / 139）。缺这些名字时交由 [TermuxPrefix]
     * 链接 `/system/bin/sh`（mksh，静态可用）兜底，脚本常见的 `#!/.../bin/sh` 才能真正跑起来。
     *
     * 已存在的真实文件（bash / curl / busybox 自身）不覆盖。
     */
    private fun createAppletLinks(root: File) {
        val bin = File(root, "bin")
        val busybox = File(bin, "busybox")
        if (!busybox.isFile) return
        val list = runCatching { File(root, APPLETS_FILE).readLines() }.getOrDefault(emptyList())
        for (raw in list) {
            val name = raw.trim()
            if (name.isEmpty() || name == "busybox") continue
            // shell applet 一律跳过：busybox 的 shell 在本机直接执行会崩，入口由 TermuxPrefix 兜底
            if (name in SHELL_APPLETS) continue
            // 名称限制：拒绝路径分隔符与 .. 的条目
            if (name.contains('/') || name.contains('\\') || name.contains("..")) continue
            val link = File(bin, name)
            if (link.exists()) continue
            runCatching {
                Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("busybox"))
            }.onFailure {
                // 文件系统不支持符号链接时退化为硬链接，再不行则跳过
                runCatching { Files.createLink(link.toPath(), busybox.toPath()) }
            }
        }
    }
}
