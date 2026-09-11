// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「执行文件」确认弹窗所需的文件剖析结果。
 * 包含风险告知所需的关键字段：文件名 / 路径 / 类型 / 大小 / 修改时间 / SHA-256 / Root 权限 / 明文或二进制。
 */
data class ExecutionInfo(
    val name: String,
    val path: String,
    val typeLabel: String,
    val sizeLabel: String,
    val modifiedLabel: String,
    val sha256: String,
    val rootLabel: String,
    val contentLabel: String
)

/** 入口：在 IO 线程剖析文件，产出 [ExecutionInfo]。 */
suspend fun analyzeExecution(item: FileItem): ExecutionInfo = withContext(Dispatchers.IO) {
    val file = File(item.path)
    val readable = runCatching { file.canRead() && file.isFile }.getOrDefault(false)

    val (typeLabel, contentLabel) = if (item.isDirectory) {
        "目录" to "—"
    } else if (readable) {
        detectContentLocal(file)
    } else {
        detectContentViaRoot(item.path, item.extension)
    }

    val sha = computeSha256(item.path, file, readable)

    ExecutionInfo(
        name = item.name,
        path = item.path,
        typeLabel = typeLabel,
        sizeLabel = item.formattedSize,
        modifiedLabel = item.formattedDate.ifEmpty { "—" },
        sha256 = sha,
        // 执行一律走 RootService（su -c），无论当前是否已授权，结果都以 Root 权限运行
        rootLabel = "是（将以 Root 权限执行）",
        contentLabel = contentLabel
    )
}

/** 本地可读文件：采样前 8KB 做 ELF 魔数 / 空字节 / 可打印比例判定。 */
private fun detectContentLocal(file: File): Pair<String, String> {
    val buf = ByteArray(8192)
    val len = runCatching { file.inputStream().use { it.read(buf) } }.getOrDefault(-1).let { if (it < 0) 0 else it }
    val sample = buf.copyOf(len)

    if (len >= 4 && sample[0] == 0x7F.toByte() && sample[1] == 'E'.code.toByte()
        && sample[2] == 'L'.code.toByte() && sample[3] == 'F'.code.toByte()
    ) {
        return "ELF 二进制 (.so / 可执行)" to "二进制 / 加密"
    }

    val hasNull = sample.any { it == 0.toByte() }
    val printable = sample.count { it in 9..13 || it in 32..126 }
    val ratio = if (len == 0) 1f else printable.toFloat() / len
    val ext = file.extension.lowercase()

    return if (hasNull || ratio < 0.7f) {
        extTypeLabel(ext, binary = true) to "二进制 / 加密"
    } else {
        extTypeLabel(ext, binary = false) to "明文代码"
    }
}

/**
 * 把 toybox `file` 的输出行归一化为 `(类型, 内容类别)`。
 *
 * 真机实测（2026-09-11，Android toybox）两条约束，均在此处收口：
 * 1. toybox 的 `file` **只支持 `-hL`，不支持 `-b`**。旧实现传 `-b` 必然失败并返回
 *    `file: Unknown option b`，而调用方丢弃了退出码，把这段错误文本当成文件内容去匹配关键词，
 *    于是普通 shell 脚本被判成「二进制 / 加密」（与类型标签自相矛盾）。
 * 2. 输出形如 `<path>: <描述>`，**必须剥掉路径前缀**再匹配：否则路径里的 `data` 等字样会
 *    污染判定（如 `/data/adb/...` 命中 `contains("data")` → 误判「未知二进制」）。
 */
internal fun classifyFileTypeLine(line: String, ext: String): Pair<String, String> {
    val lower = line.substringAfter(": ", line).lowercase()
    val typeLabel = when {
        lower.contains("elf") -> "ELF 二进制 (.so / 可执行)"
        lower.contains("shell script") -> "Shell Script"
        lower.contains("script") || lower.contains("text") -> "文本 / 脚本"
        lower.contains("data") -> "未知二进制"
        else -> extTypeLabel(ext, binary = !lower.contains("text"))
    }
    val contentLabel = if (lower.contains("elf") || lower.contains("data")
        || (!lower.contains("text") && !lower.contains("script"))
    ) "二进制 / 加密" else "明文代码"
    return typeLabel to contentLabel
}

/** 不可直接读（需 Root 的路径）：经 su 调 `file` 辅助判定，失败则回退扩展名（绝不把错误文本当内容）。 */
private fun detectContentViaRoot(path: String, ext: String): Pair<String, String> {
    val fallback = extTypeLabel(ext, binary = false) to "明文代码"
    return try {
        val (code, out) = RootService.runCommandSync("file " + RootService.escapeShellArg(path))
        if (code != 0) return fallback
        val line = out.lineSequence().firstOrNull { it.isNotBlank() } ?: return fallback
        classifyFileTypeLine(line, ext)
    } catch (_: Exception) {
        fallback
    }
}

/** SHA-256：本地可读直接算；超大文件（>50MB）跳过；否则经 su sha256sum 计算。 */
private fun computeSha256(path: String, file: File, readable: Boolean): String {
    val limit = 50L * 1024 * 1024
    return try {
        if (readable && file.length() <= limit) {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02X".format(it) }
        } else if (readable) {
            "文件过大 (>50MB) 未计算"
        } else {
            val (_, out) = RootService.runCommandSync("sha256sum " + RootService.escapeShellArg(path))
            val m = Regex("""^\s*([0-9a-fA-F]{64})""").find(out)
            m?.groupValues?.get(1)?.uppercase() ?: "计算失败（无 Root 或无法读取）"
        }
    } catch (e: Exception) {
        "计算失败：${e.message?.take(40) ?: "未知错误"}"
    }
}

private fun extTypeLabel(ext: String, binary: Boolean): String = when (ext) {
    "sh", "bash", "zsh" -> "Shell Script"
    "so" -> "ELF 二进制 (.so)"
    "py" -> "Python 脚本"
    "js", "ts" -> "JavaScript / TypeScript"
    "kt", "java", "c", "cpp", "h", "hpp", "go", "rs" -> "源代码"
    "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "prop", "properties" -> "配置文件"
    "txt", "log", "md", "csv" -> "文本文件"
    "apk", "xapk", "apks" -> "Android 安装包"
    else -> if (binary) "未知二进制" else "未知文本"
}
