// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * 内置 POSIX 环境（bash + busybox + curl + 依赖库 + CA 证书）。
 *
 * ## 为什么内置
 *
 * Android 原生**不带** `bash` / `wget` / `curl` / `xz` / `zip` / `openssl`，
 * 现实中的 `.sh` 脚本却大量依赖它们：Termux 打包的自解压脚本 shebang 指向
 * `#!/data/data/com.termux/files/usr/bin/bash`，联网脚本调 `curl`，
 * 解包脚本调 `tar` / `unzip` / `xz`。设备未装 Termux 时这些命令全部缺失，
 * 脚本按 127 退出、只留一行 `not found` —— 表现为「环境缺失、执行不了」。
 *
 * 本类把一整套 aarch64 二进制随 APK 分发，首次使用时解压到应用私有目录，
 * 执行前由 [RootService] 注入 `PATH` / `LD_LIBRARY_PATH` 与证书变量。
 *
 * ## 落点选择
 *
 * 解压到 `filesDir/env`。Android 10+ 的 W^X 只禁止**应用自身 uid**执行应用数据目录里的
 * 文件；本应用脚本一律经 `su -c` 以 root 执行，root 不受该限制（真机实测 root 可从
 * app 私有目录执行二进制）。
 *
 * ## 版本化
 *
 * 归档内含 `VERSION`；与 [ENV_VERSION] 不一致时整目录重建，
 * 避免旧版残留的半套库把新二进制带崩（`CANNOT LINK EXECUTABLE`）。
 */
object EmbeddedEnv {

    /** 资产归档名（随 APK 分发）。刻意不用 `.gz` 后缀：打包工具会把 `.gz` 资产自动解压并改名。 */
    private const val ASSET = "env.bin"

    /** 解压目标目录名（应用私有目录下）。 */
    private const val DIR_NAME = "env"

    /** 环境版本。改动内置内容时同步 +1（与归档内 VERSION 一致）。 */
    private const val ENV_VERSION = "2"

    /** busybox applet 清单（归档内 share/applets.txt 的内容副本随解码读取）。 */
    private const val APPLETS_FILE = "share/applets.txt"

    private val prepared = AtomicBoolean(false)

    @Volatile
    private var envDir: File? = null

    val binDir: String? get() = envDir?.let { File(it, "bin").absolutePath }
    val libDir: String? get() = envDir?.let { File(it, "lib").absolutePath }
    val certFile: String? get() = envDir?.let { File(it, "etc/cert.pem").absolutePath }

    /** 内置 bash 绝对路径（存在才算）。 */
    val bashPath: String? get() = envDir?.let { File(it, "bin/bash").takeIf { f -> f.isFile }?.absolutePath }

    /**
     * 确保环境已解压就绪。首次调用做实际解压，之后直接返回缓存结果。
     *
     * @return 是否可用（资产缺失 / 解压失败时为 false，调用方应降级到系统 PATH）。
     */
    fun ensure(context: Context): Boolean {
        if (prepared.get()) return envDir != null
        synchronized(this) {
            if (prepared.get()) return envDir != null
            envDir = runCatching { install(context) }.getOrNull()
            prepared.set(true)
            return envDir != null
        }
    }

    /**
     * 生成执行前导片段：注入 PATH / LD_LIBRARY_PATH / 证书变量，并建立 applet 快捷方式。
     *
     * 内置目录**前置**到 PATH：脚本调用 `curl` / `wget` / `tar` 时优先命中内置版本，
     * 不依赖设备是否装了第三方工具箱。系统目录仍保留在其后。
     *
     * 未就绪时返回空串（调用方退回系统 PATH，行为与旧版一致）。
     */
    fun exportFragment(): String {
        val bin = binDir ?: return ""
        val lib = libDir ?: return ""
        val cert = certFile ?: ""
        val sb = StringBuilder()
        // 前置内置 bin；把原来的 PATH 接在后面，系统工具仍可用
        sb.append("export PATH=").append(escape(bin)).append(":\$PATH; ")
        // bash / curl / zip 的 RUNPATH 指向 Termux 前缀。**优先**靠 TermuxPrefix 的前缀符号链接解析；
        // 仅在其不可用（无 root，无法建前缀）时才退回导出 LD_LIBRARY_PATH —— 后者会污染子进程，
        // 让内置 libz 等顶替系统库的依赖，导致脚本里的原生程序 CANNOT LINK EXECUTABLE。
        if (!TermuxPrefix.ready) {
            sb.append("export LD_LIBRARY_PATH=").append(escape(lib)).append(":\$LD_LIBRARY_PATH; ")
        }
        if (cert.isNotEmpty()) {
            sb.append("export SSL_CERT_FILE=").append(escape(cert)).append("; ")
            sb.append("export CURL_CA_BUNDLE=").append(escape(cert)).append("; ")
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        if (s.matches(Regex("^[a-zA-Z0-9_./\\-:]+$"))) s else "'" + s.replace("'", "'\\''") + "'"

    private fun install(context: Context): File? {
        val root = File(context.filesDir, DIR_NAME)
        val marker = File(root, "VERSION")
        val hasBin = File(root, "bin/bash").isFile && File(root, "bin/busybox").isFile
        val installed = runCatching { marker.readText().trim() }.getOrNull()
        if (installed == ENV_VERSION && hasBin) return root
        runCatching { root.deleteRecursively() }
        if (!root.mkdirs() && !root.isDirectory) return null

        val ok = runCatching {
            context.assets.open(ASSET).use { raw ->
                GzipCompressorInputStream(raw).use { gz ->
                    TarArchiveInputStream(gz).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            extractEntry(root, entry, tar)
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)

        if (!ok) {
            runCatching { root.deleteRecursively() }
            return null
        }
        createAppletLinks(root)
        runCatching { marker.writeText(ENV_VERSION + "\n") }
        return root
    }

    /**
     * 为每个 busybox applet 建立 `bin/<applet> -> busybox` 符号链接。
     *
     * busybox 按 argv[0] 分派 applet；`<applet> <args>` 这种调用方式要求
     * 存在以 applet 命名的入口，否则脚本里写 `wget ...` 找不到命令。
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

    /**
     * 解压单个条目；目录 / 文件分开处理，bin 下的文件补执行位。
     *
     * 归档由本项目构建脚本产出，内容可信，仍逐条过滤 `..` 与绝对路径，避免畸形条目越界。
     */
    private fun extractEntry(root: File, entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry, tar: TarArchiveInputStream) {
        val name = entry.name.replace('\\', '/').removePrefix("./")
        if (name.isEmpty() || name.contains("..") || name.startsWith("/")) return
        val target = File(root, name)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) return

        if (entry.isDirectory) {
            target.mkdirs()
            return
        }
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> tar.copyTo(out) }
        if (name.startsWith("bin/")) {
            runCatching { target.setExecutable(true, false) }
        }
    }
}
