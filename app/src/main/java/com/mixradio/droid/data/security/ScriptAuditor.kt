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
        for ((idx, line) in logicalLines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parsed = CommandParser.parse(line)
            if (parsed.truncated) {
                findings.add(
                    Finding(
                        "LINE_TOO_COMPLEX", RiskLevel.DANGEROUS,
                        "第 ${idx + 1} 行过长或结构复杂，无法完整解析",
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
     * 读取脚本内容（本地直读失败走 su cat；限 MAX_SCAN_BYTES）。
     * @return (内容或 null, note)——null 时 note 说明原因
     */
    fun readScriptContent(path: String): Pair<String?, String> {
        return try {
            val f = File(path)
            if (f.canRead()) {
                if (f.length() > MAX_SCAN_BYTES) return Pair(null, "文件超过 2MB，跳过内容扫描")
                return Pair(f.readText(Charsets.UTF_8), "ok")
            }
            // Root-only：su head 限长读取
            val (code, out) = RootService.runCommandSync(
                "head -c $MAX_SCAN_BYTES ${RootService.escapeShellArg(path)}",
                15_000L
            )
            if (code == 0 && out.isNotEmpty()) Pair(out, "ok")
            else Pair(null, "无法读取文件内容（su 返回 $code）")
        } catch (e: Exception) {
            Pair(null, "读取失败: ${e.message}")
        }
    }
}
