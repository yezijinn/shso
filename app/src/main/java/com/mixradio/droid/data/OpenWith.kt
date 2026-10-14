// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「打开方式」/「分享」：把文件交给系统解析并列出可处理的已安装应用。
 *
 * 目标文件可能位于应用读不到的受保护路径（`/data/adb` 等），故先把内容复制到
 * 应用私有缓存再以 `content://`（FileProvider）共享；直接传 `file://` 在 Android 7+
 * 会抛 `FileUriExposedException`。共享副本随后由缓存清理机制回收。
 */
object OpenWith {

    private const val DIR = "open-with"

    /** 「打开方式」：按文件类型列出可打开它的应用。 */
    suspend fun open(
        context: Context,
        path: String,
        displayName: String,
        extension: String,
    ): Boolean = launchChooser(context, path, displayName, extension, title = "打开方式") { uri, mime ->
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
        }
    }

    /** 「分享」：把文件发送给其他应用（部分应用只认 SEND 而非 VIEW）。 */
    suspend fun share(
        context: Context,
        path: String,
        displayName: String,
        extension: String,
    ): Boolean = launchChooser(context, path, displayName, extension, title = "分享") { uri, mime ->
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // 部分接收方按 clipData 取 URI 与授权，补一份更稳
            clipData = ClipData.newUri(context.contentResolver, sanitize(displayName), uri)
        }
    }

    /**
     * 复制到缓存 → 生成 content:// → 交由系统选择器。
     *
     * @param buildIntent 用 (uri, mime) 构造具体意图（VIEW 或 SEND）。
     */
    private suspend fun launchChooser(
        context: Context,
        path: String,
        displayName: String,
        extension: String,
        title: String,
        buildIntent: (android.net.Uri, String) -> Intent,
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val safeName = sanitize(displayName).ifEmpty { "file" }
        val dst = File(dir, safeName)
        runCatching { dst.delete() }

        if (!copyIn(path, dst)) return@withContext false

        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
        val authority = "${context.packageName}.fileprovider"
        val uri = runCatching { FileProvider.getUriForFile(context, authority, dst) }.getOrNull()
            ?: return@withContext false

        val intent = buildIntent(uri, mime).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            runCatching { context.startActivity(chooser) }.isSuccess
        }
    }

    /** 文件名净化：去掉路径分隔符与控制字符。 */
    private fun sanitize(name: String): String =
        name.replace('/', '_').replace('\\', '_')
            .filter { it.code >= 0x20 && it != '\u007f' }
            .take(120)

    /**
     * 把源内容写入 [dst]。app 域可读时直拷；否则经 root 读取后由应用写出
     * （不能让 root 直接建目标文件 —— 那样属主为 root，后续读取会受限）。
     */
    private fun copyIn(path: String, dst: File): Boolean {
        runCatching {
            val src = File(path)
            if (src.isFile && src.canRead()) {
                src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                if (dst.length() > 0) return true
            }
        }
        if (RootService.isRootGranted != true) return false
        return try {
            val process = ProcessBuilder("su", "-c", "cat -- " + RootService.escapeShellArg(path))
                .redirectErrorStream(false).start()
            var copied = false
            process.inputStream.use { ins ->
                dst.outputStream().use { out ->
                    ins.copyTo(out)
                    copied = true
                }
            }
            process.waitFor()
            copied && dst.length() > 0
        } catch (_: Exception) {
            false
        }
    }
}
