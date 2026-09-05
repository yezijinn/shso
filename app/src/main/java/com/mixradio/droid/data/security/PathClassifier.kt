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

    /** 分级：先精确豁免（SAFE 前缀）→ 警告前缀 → 危急前缀 → /data 兜底。 */
    fun classify(rawPath: String): PathClass {
        val p = rawPath.trim()
        if (p.isEmpty()) return PathClass.WARNING
        // 变量 / 未解析替换：无法预判，保守 WARNING
        if (p.startsWith("$") || p.contains("${'$'}")) return PathClass.WARNING
        // 相对路径：依赖 cwd，无法判定
        if (!p.startsWith("/")) return PathClass.WARNING

        val n = normalize(p)
        if (n == "/") return PathClass.CRITICAL

        // 通配符本身意味着批量操作：按基路径分级后至少提一级
        val hasGlob = p.contains('*') || p.contains('?')

        // SAFE：用户存储 / App 工作区 / 临时目录
        if (matchesPrefix(n, SAFE_PREFIXES)) return PathClass.SAFE

        // WARNING：应用数据（删了丢数据但不破坏系统）
        if (matchesPrefix(n, WARNING_PREFIXES)) return PathClass.WARNING

        // CRITICAL：系统分区与虚拟文件系统
        if (matchesPrefix(n, CRITICAL_PREFIXES) || n == "/init" || n.startsWith("/init.")) {
            return PathClass.CRITICAL
        }

        // DANGEROUS：/data 整体（含 /data/adb 模块区）、持久配置分区
        if (n == "/data" || n.startsWith("/data/") ||
            matchesPrefix(n, DANGEROUS_PREFIXES)
        ) {
            return if (hasGlob) PathClass.CRITICAL else PathClass.DANGEROUS
        }

        // 未知顶级路径
        return PathClass.WARNING
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
        "/metadata", "/persist", "/config", "/mnt/adb"
    )
}
