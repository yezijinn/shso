// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * 可选「扩展环境包」：为脚本执行补全 GNU 工具链（coreutils / findutils / gawk /
 * openssl / git / python / rsync / ssh / 压缩工具等）。
 *
 * 体积过大（约 35MB）不随 APK 分发，改为按需从发布附件下载后解压到应用私有目录。
 * 下载源 **Gitee 优先、GitHub 备选**（与「检查更新」同一约定，国内网络 GitHub 常不可达）。
 * 资产挂在非发布标签 `envpack-1` 下 —— 非发布 tag 不得用纯数字，避免与发布版本混淆。
 *
 * 未安装时 [exportFragment] 返回空串，执行环境退回内置基础环境，功能不受影响。
 */
object EnvPack {

    /** 解压目录名（应用私有目录下）。 */
    private const val DIR = "envpack"

    /** 内容版本；更新包内容时同步 +1（与包内 VERSION 一致）。 */
    private const val VERSION = "1"

    /** 下载地址：Gitee 优先，GitHub 备选。 */
    private val URLS = listOf(
        "https://gitee.com/yezijinn/shso/releases/download/envpack-1/envpack.tar.gz",
        "https://github.com/yezijinn/shso/releases/download/envpack-1/envpack.tar.gz",
    )

    /** 下载后校验的 SHA-256（空串表示不校验）。 */
    private const val SHA256 = "50dc6c0529d79290c34b260148c087c131c9c47221fd2e87c5976d50d8a9dc86"

    /** 单次下载上限，兜住异常响应体。 */
    private const val MAX_BYTES = 200L * 1024 * 1024

    sealed interface State {
        data object NotInstalled : State
        data object Downloading : State
        data object Extracting : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    var state: State by mutableStateOf(State.NotInstalled)
        private set

    /** 下载进度（0..100），仅 [State.Downloading] 期间有意义。 */
    var progress: Int by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    @Volatile
    private var dir: File? = null

    val binDir: String? get() = dir?.let { File(it, "bin").absolutePath }
    val libDir: String? get() = dir?.let { File(it, "lib").absolutePath }

    /** 进程启动时调用：已解压则标记为已安装。 */
    fun ensureLoaded(context: Context) {
        if (isInstalledOnDisk(context)) {
            dir = File(context.filesDir, DIR)
            state = State.Installed
            runCatching { TermuxPrefix.ensure(context) }
        }
    }

    private fun isInstalledOnDisk(context: Context): Boolean {
        val root = File(context.filesDir, DIR)
        val marker = File(root, "VERSION")
        val installed = runCatching { marker.readText().trim() }.getOrNull()
        return installed == VERSION && File(root, "bin/git").exists() && File(root, "bin/python3").exists()
    }

    /**
     * 生成执行前导片段：把扩展包接入 PATH / 库路径与各工具的前缀变量。
     *
     * 未安装返回空串。`PREFIX` 指向包根 —— Termux 系脚本普遍用 `$PREFIX/bin`、`$PREFIX/etc` 定位资源。
     */
    fun exportFragment(): String {
        val root = dir ?: return ""
        val bin = binDir ?: return ""
        val lib = libDir ?: return ""
        val sb = StringBuilder()
        sb.append("export PATH=").append(escape(bin)).append(":\$PATH; ")
        sb.append("export PREFIX=").append(escape(root.absolutePath)).append("; ")
        sb.append("export TERMUX_PREFIX=").append(escape(root.absolutePath)).append("; ")
        // 库路径优先靠 TermuxPrefix 前缀符号链接；仅无 root（前缀不可建）时才导出 LD_LIBRARY_PATH。
        if (!TermuxPrefix.ready) {
            sb.append("export LD_LIBRARY_PATH=").append(escape(lib)).append(":\$LD_LIBRARY_PATH; ")
        }
        sb.append("export PYTHONHOME=").append(escape(root.absolutePath)).append("; ")
        // python 标准库目录随版本变化，运行时探测
        pythonLibDir(root)?.let { sb.append("export PYTHONPATH=").append(escape(it)).append("; ") }
        File(root, "libexec/git-core").takeIf { it.isDirectory }?.let {
            sb.append("export GIT_EXEC_PATH=").append(escape(it.absolutePath)).append("; ")
        }
        File(root, "share/git-core/templates").takeIf { it.isDirectory }?.let {
            sb.append("export GIT_TEMPLATE_DIR=").append(escape(it.absolutePath)).append("; ")
        }
        File(root, "etc/tls/openssl.cnf").takeIf { it.isFile }?.let {
            sb.append("export OPENSSL_CONF=").append(escape(it.absolutePath)).append("; ")
        }
        File(root, "etc/cert.pem").takeIf { it.isFile }?.let {
            sb.append("export SSL_CERT_FILE=").append(escape(it.absolutePath)).append("; ")
            sb.append("export CURL_CA_BUNDLE=").append(escape(it.absolutePath)).append("; ")
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        if (s.matches(Regex("^[a-zA-Z0-9_./\\-:]+$"))) s else "'" + s.replace("'", "'\\''") + "'"

    private fun pythonLibDir(root: File): String? {
        val lib = File(root, "lib")
        return lib.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("python3.") }?.absolutePath
    }

    /** 下载并安装（异步）。重复触发被忽略。 */
    fun startDownload(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        state = State.Downloading
        progress = 0
        scope.launch {
            try {
                val appCtx = context.applicationContext
                val tmp = File(appCtx.cacheDir, "envpack.download")
                val ok = downloadTo(tmp)
                if (!ok) {
                    state = State.Failed("下载失败")
                    return@launch
                }
                if (SHA256.isNotEmpty()) {
                    val got = runCatching { sha256(tmp) }.getOrNull()
                    if (got == null || !got.equals(SHA256, ignoreCase = true)) {
                        runCatching { tmp.delete() }
                        state = State.Failed("校验失败")
                        return@launch
                    }
                }
                state = State.Extracting
                val root = File(appCtx.filesDir, DIR)
                val done = extract(tmp, root)
                runCatching { tmp.delete() }
                if (done) {
                    dir = root
                    runCatching { File(root, "VERSION").writeText(VERSION + "\n") }
                    state = State.Installed
                    // 把扩展包各库也链入 Termux 前缀，供其二进制解析依赖
                    runCatching { TermuxPrefix.ensure(appCtx) }
                } else {
                    runCatching { root.deleteRecursively() }
                    state = State.Failed("解压失败")
                }
            } catch (e: Exception) {
                state = State.Failed(e.message ?: "失败")
            } finally {
                busy.set(false)
            }
        }
    }

    /** 删除已安装的扩展包。 */
    fun remove(context: Context) {
        dir = null
        state = State.NotInstalled
        scope.launch {
            runCatching { File(context.filesDir, DIR).deleteRecursively() }
        }
    }

    /** 依次尝试各源；任一成功即返回 true，全部失败返回 false。 */
    private fun downloadTo(dst: File): Boolean {
        for (url in URLS) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "shso-envpack")
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) continue
                    val total = conn.contentLengthLong
                    conn.inputStream.use { ins -> save(ins, dst, total) }
                    if (dst.length() > 0) return true
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                runCatching { dst.delete() }
            }
        }
        return false
    }

    private fun save(ins: InputStream, dst: File, total: Long) {
        dst.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            var written = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                written += n
                if (written > MAX_BYTES) throw IllegalStateException("响应体过大")
                if (total > 0) progress = ((written * 100) / total).toInt().coerceIn(0, 100)
            }
            out.flush()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 解压 tar.gz 到 root（先清空）。符号链接 / 目录 / 文件分别处理。 */
    private fun extract(archive: File, root: File): Boolean {
        runCatching { root.deleteRecursively() }
        if (!root.mkdirs() && !root.isDirectory) return false
        return runCatching {
            archive.inputStream().use { raw ->
                GzipCompressorInputStream(raw).use { gz ->
                    TarArchiveInputStream(gz).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        while (entry != null) {
                            extractEntry(root, entry, tar)
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun extractEntry(root: File, entry: TarArchiveEntry, tar: TarArchiveInputStream) {
        val name = entry.name.replace('\\', '/').removePrefix("./")
        if (name.isEmpty() || name.contains("..") || name.startsWith("/")) return
        val target = File(root, name)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) return

        when {
            entry.isDirectory -> target.mkdirs()
            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                runCatching { target.delete() }
                runCatching {
                    Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
                }
            }
            else -> {
                target.parentFile?.mkdirs()
                runCatching { target.delete() }
                target.outputStream().use { out -> tar.copyTo(out) }
                val mode = entry.mode
                if (mode and 0b001_000_000 != 0) runCatching { target.setExecutable(true, false) }
            }
        }
    }
}
