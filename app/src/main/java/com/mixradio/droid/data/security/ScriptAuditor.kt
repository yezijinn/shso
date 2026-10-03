// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

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

    /**
     * 未经用户确认的自动执行（「添加到 shso 后自动执行」等链路）是否**必须**拒绝（fail-closed）。
     *
     * 两个条件任一成立即拒绝：
     *  1. 存在 CRITICAL 风险项；
     *  2. 扫描不完整（[Report.truncated]，如单行超长 / 原子超限）——规则看不全，不能假设安全。
     *
     * 单独抽成纯函数的原因：条件 2 是**冗余兜底**，当前 [audit] 在置 truncated 时总会同时产出
     * CRITICAL，所以线上走不到「只有 truncated」这条路；但它必须仍然正确 —— 一旦将来 audit 的
     * 产出行文调整，兜底就是唯一的防线。纯函数让这条分支可以被单测直接覆盖。
     */
    fun blocksUnattendedExecution(report: Report): Boolean =
        report.truncated || report.findings.any { it.level == RiskLevel.CRITICAL }

    /**
     * 拒绝自动执行时要展示/记账的风险项：优先 CRITICAL；没有则退回全部 finding
     * （「只有 truncated」时会得到**空列表**，调用方必须按空处理，不能 `first()`）。
     */
    fun blockingFindingsFor(report: Report): List<Finding> =
        report.findings.filter { it.level == RiskLevel.CRITICAL }.ifEmpty { report.findings }

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
        var nulChars = 0
        var replacementChars = 0
        for (i in 0 until n) {
            when (content[i]) {
                '\u0000' -> nulChars++
                // U+FFFD 单列：它是「解码失败」的产物，不是二进制本身的特征。
                // 两条读取链路（本地 readText(UTF_8) 与 root 通道 InputStreamReader(UTF_8)）
                // 都按 UTF-8 解码 → GBK/GB18030 脚本的中文注释会产出**大量** U+FFFD，
                // 与 NUL 同权计入会让中文用户自写的脚本被判成 CRITICAL 混淆载荷而拒绝自动执行，
                // 且提示「疑似加密/编码混淆」与真实原因（编码不符）不符。
                // 同一脚本少写几行注释又不命中 → 判定随内容长度跳变。
                '\uFFFD' -> replacementChars++
            }
        }
        // NUL 是二进制的硬特征，占比 ≥5% 即可判定
        if (nulChars * 100 / n >= 5) return true
        // U+FFFD 只在**同时**没有 NUL、且密度极高时才作为二进制线索：
        // 真二进制按 UTF-8 解码几乎必然产生大量替换符，而 GB18030 脚本虽也高但不含 NUL。
        // 阈值取 40%：GB18030 中文注释的替换符密度通常在 30% 上下（双字节里约 2/3 不是合法
        // UTF-8 序列但会合并成一个替换符），真二进制接近 100%。
        if (replacementChars * 100 / n >= 40) return true

        // 超长「纯 base64 字符集」单行：混淆载荷的典型形态（minified JS 含 {}(); 等符号，不会命中）。
        //
        // 原实现只取**第一行**，而任何可执行脚本第一行都是 `#!/system/bin/sh`（长度 <2048），
        // 于是该判据对脚本永久失效，把 base64 载荷放第 2 行即完全绕过 ——
        // 与注释宣称的「混淆载荷第一道拦截」覆盖面不符（fail-open 方向）。
        // 现遍历前若干行（跳过 shebang），逐行判定。
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#!")) continue          // shebang 不算载荷行
            if (line.length > 2048 && line.length % 4 == 0 &&
                line.none { it == ' ' || it == '\t' } &&
                line.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            ) return true
            break
        }

        return false
    }

    /**
     * 读取脚本内容（本地直读失败走 su cat；限 [MAX_SCAN_BYTES]）。
     *
     * 超过上限时**返回 null + note**（而不是只扫描前 2MB 就当 ok）：
     * 否则「先塞 2MB 正常内容、再藏载荷」的脚本会绕过审计 —— 这是真实的漏检路径。
     *
     * ## fail-closed 的三处关键判断
     *
     * ROOT 路径上大小未知时**不做截断扫描**，一律拒绝：
     * 1. `stat` 失败（路径非法 / su 被拒 / 文件已消失）→ size 为 null。此前直接跳过
     *    2MB 上限继续 `cat | head -c 2MB`，把**被截断的内容**按「已完整扫描」返回 ok，
     *    调用方据此放行自动执行 —— 攻击者只要让 `stat` 失败就能完全跳过审计。
     * 2. `stat` 成功但输出非数字 → 同样视为未知。
     * 3. 读取时实际字节数达到上限（而非「小于上限」）即判为可能超限：
     *    内容恰好等于 2MB 时无法区分「刚好 2MB」与「被 head 截断」，按拒绝处理。
     *
     * @return (内容或 null, note)——null 时 note 说明原因，调用方必须拒绝自动执行
     */
    fun readScriptContent(path: String): Pair<String?, String> {
        return try {
            val file = File(path)
            if (file.canRead()) {
                if (file.length() > MAX_SCAN_BYTES) {
                    return Pair(null, "文件超过 2MB（${file.length()} 字节），无法完整扫描")
                }
                return Pair(file.readText(Charsets.UTF_8), "ok")
            }
            // Root-only：先取真实大小，任何「拿不到大小」的情况都必须 fail-closed，
            // 否则 `stat` 失败即可绕过 2MB 上限与后续审计。
            val escaped = RootService.escapeShellArg(path)
            val (sizeCode, sizeOut) = RootService.runCommandSync("stat -c %s $escaped", 10_000L)
            if (sizeCode != 0) {
                return Pair(null, "无法确认文件大小（stat 退出码 $sizeCode），按保守策略拒绝自动执行")
            }
            val size = sizeOut.trim().toLongOrNull()
                ?: return Pair(null, "无法解析文件大小（stat 输出: ${sizeOut.trim().take(32)}），按保守策略拒绝自动执行")
            if (size > MAX_SCAN_BYTES) {
                return Pair(null, "文件超过 2MB（$size 字节），无法完整扫描")
            }
            val (code, out) = RootService.runCommandSync(
                "cat $escaped 2>/dev/null | head -c $MAX_SCAN_BYTES",
                15_000L
            )
            when {
                code != 0 -> Pair(null, "无法读取文件内容（su 返回 $code）")
                // 读到的字节数达到上限：无法区分「恰好等于上限」与「被截断」，按可能超限处理
                out.length >= MAX_SCAN_BYTES -> Pair(null, "文件大小无法确认未超限（读取已达 2MB 上限），无法完整扫描")
                out.isEmpty() && size > 0 -> Pair(null, "文件非空但读取结果为空，无法确认内容")
                out.isEmpty() -> Pair(out, "ok")
                else -> Pair(out, "ok")
            }
        } catch (e: Exception) {
            Pair(null, "读取失败: ${e.message}")
        }
    }
}
