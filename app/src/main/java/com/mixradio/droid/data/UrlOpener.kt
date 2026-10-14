// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast

/**
 * 用外部应用打开链接。
 *
 * 直接用 `ACTION_VIEW` 隐式意图交给系统：默认浏览器或系统「打开方式」选择器由系统解析，
 * **不依赖本应用自身的应用可见性**，因此在任何机型与包可见性策略下都能列出候选应用。
 *
 * 仅在能可靠探测到浏览器时优先指定它（避免 GitHub 客户端之类接管 github.com 链接），
 * 探测不到就退回系统默认路径 —— 绝不构造空的选择器。
 */
object UrlOpener {

    /** 已知会接管链接但并非浏览器的应用（按精确包名排除）。 */
    private val NON_BROWSER_HANDLERS = setOf("com.github.android")

    /** 浏览器包名的常见特征词，用于在多候选中挑一个真正的浏览器。 */
    private val BROWSER_HINTS = listOf(
        "browser", "chrome", "edge", "firefox", "quark", "ucmobile", "heytap",
        "baidu", "sogou", "qihoo", "opera", "vivaldi", "via", "brave",
        "duckduckgo", "microsoft", "mozilla", "chromium", "samsung",
    )

    fun openUrl(context: Context, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val target = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 优先指定的浏览器（探测失败则为 null，走系统默认路径）
        val browser = detectBrowser(context)
        if (browser != null &&
            runCatching { context.startActivity(Intent(target).setPackage(browser)) }.isSuccess
        ) {
            return
        }

        try {
            // 系统默认应用或系统选择器：候选由系统按意图解析，天然包含全部浏览器
            context.startActivity(target)
        } catch (_: Throwable) {
            try {
                context.startActivity(
                    Intent.createChooser(target, "选择应用打开")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                copyToClipboard(context, url)
            }
        }
    }

    /**
     * 探测可处理 http/https 的浏览器包名；探测不到返回 null。
     *
     * 用**真实 https 链接**作探针（而非 `http://` 空主机），避免个别机型对空主机 URI 解析不出候选；
     * 查询带 `MATCH_ALL`，确保返回全部匹配活动而非仅默认项。
     */
    private fun detectBrowser(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val packages = runCatching {
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    probe,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            }
            infos.map { it.activityInfo.packageName }
                .distinct()
                .filterNot { it in NON_BROWSER_HANDLERS }
        }.getOrDefault(emptyList())

        if (packages.isEmpty()) return null
        return packages.firstOrNull { pkg -> BROWSER_HINTS.any { pkg.contains(it) } }
            ?: packages.firstOrNull()
    }

    private fun copyToClipboard(context: Context, url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(context, "未能调起其他应用，链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
