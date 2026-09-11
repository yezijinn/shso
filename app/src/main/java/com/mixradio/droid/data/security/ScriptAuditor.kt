// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import com.mixradio.droid.data.RootService
import java.io.File

/**
 * 脚本专项审查（方案 §6）：执行前逐行扫描内容，产出带行号的风险报告。
 *
 * 报告并入 ExecuteConfirmDialog 的「脚本风险扫描」区展示。
 * 行级扫描是启发式（不跨行追踪 heredoc / 多行字符串），定位是提示层——
 * 真正的防线：档位 3 脚本默认非 Root + shso_guard 运行时守卫。
 */
object ScriptAuditor {

    data class Report(
        val findings: List<Finding>,
        val maxLevel: RiskLevel,
        /** 扫描不完整（超大 / 不可读 / 二进制）：按 fail-closed 展示 */
        val truncated: Boolean,
        val scannedLines: Int,
        val totalLines: Int,
        /** 无法读取内容时的原因（null = 已正常扫描） */
        val note: String? = null
    ) {
        val clean: Boolean get() = findings.isEmpty() && !truncated
    }

    private const val MAX_SCAN_BYTES = 2 * 1024 * 1024

    /** 扫描脚本文本（调用方先读好内容；.sh 之外的二进制请传 note 跳过）。 */
    fun audit(content: String): Report {
        // 逻辑行：行尾 \ 续行合并；heredoc 不追踪（启发式）
        val logicalLines = ArrayList<String>()
        val physical = content.split('\n')
        var buffer = StringBuilder()
        for (raw in physical) {
            val line = raw.removeSuffix("\r")
            if (line.endsWith("\\") && line.length > 1) {
                buffer.append(line, 0, line.length - 1).append(' ')
            } else {
                buffer.append(line)
                logicalLines.add(buffer.toString())
                buffer = StringBuilder()
            }
        }
        if (buffer.isNotEmpty()) logicalLines.add(buffer.toString())

        val findings = ArrayList<Finding>()
        var truncated = false

        // 0) 加密/编码载荷：内容本身不是明文 → 无法审计，按最高危（fail-closed）。
        //    这是「加密脚本」的第一道拦截：无论里面是什么，先拒绝自动执行。
        if (looksEncrypted(content)) {
            findings.add(
                Finding(
                    "OBFUSCATED_PAYLOAD", RiskLevel.CRITICAL,
                    "脚本内容疑似加密/编码混淆（非明文），无法审计其行为，已按最高危处理",
                    content.take(80), null
                )
            )
        }

        for ((idx, line) in logicalLines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parsed = CommandParser.parse(line)
            if (parsed.truncated) {
                // fail-closed：解析不了就不放行。调用方只拦 CRITICAL，故此处必须是 CRITICAL，
                // 否则超长单行 / 超多 token 的混淆脚本会被自动执行（真实绕过）。
                findings.add(
                    Finding(
                        "LINE_TOO_COMPLEX", RiskLevel.CRITICAL,
                        "第 ${idx + 1} 行过长或结构复杂，无法完整解析，已按最高危处理",
                        trimmed.take(100), idx + 1
                    )
                )
                truncated = true
                continue
            }
            // 单行完整判定（原子规则 + 管道检测一并完成）
            when (val v = PolicyEngine.evaluateParsed(parsed, CommandSource.SCRIPT_FILE, idx + 1)) {
                is Verdict.Confirm -> findings.addAll(v.findings)
                is Verdict.Block -> findings.addAll(v.findings)
                Verdict.Allow -> {}
            }
        }

        val max = findings.maxByOrNull { it.level.ordinal }?.level ?: RiskLevel.SAFE
        return Report(findings, max, truncated, logicalLines.size, logicalLines.size)
    }

    /**
     * 判断内容是否「不像明文脚本」——加密 / 二进制 / 超长 base64 单行。
     * 命中即视为无法审计（fail-closed）。抽样前 8KB，避免大文件全量遍历。
     */
    internal fun looksEncrypted(content: String): Boolean {
        if (content.isEmpty()) return false
        val n = content.length.coerceAtMost(8192)
        var binaryChars = 0
        for (i in 0 until n) {
            val c = content[i]
            if (c == '\u0000' || c == '\uFFFD') binaryChars++
        }
        // NUL / 替换符 占比 ≥5%：基本可判定为二进制或错误编码
        if (binaryChars * 100 / n >= 5) return true

        // 超长「纯 base64 字符集」单行：混淆载荷的典型形态（minified JS 含 {}(); 等符号，不会命中）
        val firstLine = content.lineSequence().firstOrNull() ?: return false
        if (firstLine.length > 2048 && firstLine.length % 4 == 0 &&
            firstLine.none { it == ' ' || it == '\t' } &&
            firstLine.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        ) return true

        return false
    }

    /**
     * 读取脚本内容（本地直读失败走 su cat；限 [MAX_SCAN_BYTES]）。
     *
     * 超过上限时**返回 null + note**（而不是只扫描前 2MB 就当 ok）：
     * 否则「先塞 2MB 正常内容、再藏载荷」的脚本会绕过审计 —— 这是真实的漏检路径。
     * @return (内容或 null, note)——null 时 note 说明原因
     */
    fun readScriptContent(path: String): Pair<String?, String> {
        return try {
            val f = File(path)
            if (f.canRead()) {
                if (f.length() > MAX_SCAN_BYTES) {
                    return Pair(null, "文件超过 2MB（${f.length()} 字节），无法完整扫描，已按保守策略处理")
                }
                return Pair(f.readText(Charsets.UTF_8), "ok")
            }
            // Root-only：先取真实大小，超限直接拒绝（不再截断扫描）
            val escaped = RootService.escapeShellArg(path)
            val (sizeCode, sizeOut) = RootService.runCommandSync("stat -c %s $escaped", 10_000L)
            val size = if (sizeCode == 0) sizeOut.trim().toLongOrNull() else null
            if (size != null && size > MAX_SCAN_BYTES) {
                return Pair(null, "文件超过 2MB（$size 字节），无法完整扫描，已按保守策略处理")
            }
            val (code, out) = RootService.runCommandSync(
                "cat $escaped 2>/dev/null | head -c $MAX_SCAN_BYTES",
                15_000L
            )
            if (code == 0 && out.isNotEmpty()) Pair(out, "ok")
            else Pair(null, "无法读取文件内容（su 返回 $code）")
        } catch (e: Exception) {
            Pair(null, "读取失败: ${e.message}")
        }
    }
}
