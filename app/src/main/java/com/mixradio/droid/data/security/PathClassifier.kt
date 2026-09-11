// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

/**
 * 路径分级（方案 §5.3）：词法归一化 + 四级分级。
 *
 * 归一化只做**词法**层面（解析 `..` / `.` / `//` / 尾部斜杠 / 通配符基路径），
 * 不做 symlink 的 realpath（那需要对每个路径发 stat，交互式输入无法承受；
 * 符号链接解析交给 shso_guard 模块的运行时守卫，App 侧是提示层）。
 */
object PathClassifier {

    enum class PathClass { SAFE, WARNING, DANGEROUS, CRITICAL }

    /** 词法归一化：剥引号残留、解析 . 与 ..、折叠多斜杠、去尾部斜杠。 */
    fun normalize(rawPath: String): String {
        var p = rawPath.trim()
            .removePrefix("'").removeSuffix("'")
            .removePrefix("\"").removeSuffix("\"")
        val hasGlob = p.contains('*') || p.contains('?')
        // 通配符：取基路径（/data/media/* → /data/media）
        if (hasGlob) {
            val cut = p.indexOfFirst { it == '*' || it == '?' }
            if (cut > 0) p = p.substring(0, cut)
        }
        if (!p.startsWith("/")) return p
        val parts = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * 分级：先精确豁免（SAFE 前缀）→ 警告前缀 → 危急前缀 → /data 兜底；
     * 最后通配符基路径再**至少**提一级（批量操作比单文件危险得多）。
     *
     * 升级映射:SAFE→WARNING,WARNING→DANGEROUS,DANGEROUS/CRITICAL→CRITICAL（已达上限）。
     */
    fun classify(rawPath: String): PathClass {
        val p = rawPath.trim()
        if (p.isEmpty()) return PathClass.WARNING
        // 变量 / 未解析替换：无法预判，保守 WARNING
        if (p.startsWith("$") || p.contains("${'$'}")) return PathClass.WARNING
        // 相对路径：依赖 cwd，无法判定
        if (!p.startsWith("/")) return PathClass.WARNING

        val n = normalize(p)
        if (n == "/") return PathClass.CRITICAL

        // 通配符本身意味着批量操作：先按基路径分级，再统一提一级
        val hasGlob = p.contains('*') || p.contains('?')

        val base = when {
            matchesPrefix(n, SAFE_PREFIXES) -> PathClass.SAFE
            matchesPrefix(n, WARNING_PREFIXES) -> PathClass.WARNING
            matchesPrefix(n, CRITICAL_PREFIXES) || n == "/init" || n.startsWith("/init.") -> PathClass.CRITICAL
            n == "/data" || n.startsWith("/data/") || matchesPrefix(n, DANGEROUS_PREFIXES) -> PathClass.DANGEROUS
            else -> PathClass.WARNING
        }

        return if (hasGlob) base.upgrade() else base
    }

    /** 升级:SAFE→WARNING,WARNING→DANGEROUS,DANGEROUS/CRITICAL→CRITICAL。 */
    private fun PathClass.upgrade(): PathClass = when (this) {
        PathClass.SAFE -> PathClass.WARNING
        PathClass.WARNING -> PathClass.DANGEROUS
        PathClass.DANGEROUS, PathClass.CRITICAL -> PathClass.CRITICAL
    }

    private fun matchesPrefix(path: String, prefixes: Array<String>): Boolean =
        prefixes.any { path == it || path.startsWith("$it/") }

    private val SAFE_PREFIXES = arrayOf(
        "/sdcard", "/storage", "/mnt/media_rw", "/mnt/user",
        "/data/media",        // /data 的用户存储映射（豁免 /data 整体危险级）
        "/data/adb/shso",     // 本 App 工作区
        "/data/local/tmp"     // 临时目录
    )

    private val WARNING_PREFIXES = arrayOf(
        "/data/app", "/data/user", "/data/data",
        "/data/dalvik-cache", "/data/local"
    )

    private val CRITICAL_PREFIXES = arrayOf(
        "/system", "/system_ext", "/product", "/vendor", "/odm", "/apex",
        "/proc", "/sys", "/dev"
    )

    private val DANGEROUS_PREFIXES = arrayOf(
        "/metadata", "/persist", "/config", "/mnt/adb",
        // Root 方案自身的数据：删除/破坏 = 丢 root 且常被恶意脚本当作第一步。
        // 定为 DANGEROUS（弹确认）而非 CRITICAL（硬拦）：管理 Magisk 模块属正常操作，
        // 硬拦会误伤；确认框足以让用户看到「正在动 root 环境」。
        // （注意 /data/adb/shso 在 SAFE 前缀中先命中，不受影响。）
        "/data/adb/modules", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap"
    )
}
