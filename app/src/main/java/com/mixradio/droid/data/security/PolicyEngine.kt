// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import com.mixradio.droid.data.RootService

/**
 * 策略引擎（方案 §5）：白名单 / 黑名单 / 路径规则 → Verdict。
 *
 * 判定顺序（短路）：
 * 1. INTERNAL_APP → Allow（内部命令全部为模板构造 + escapeShellArg，注入安全）；
 * 2. 解析超限 → Confirm(CRITICAL)（fail-closed）；
 * 3. 硬拦截黑名单（rm 系统 / dd 块设备 / mkfs / wipe / fastboot erase / chmod -R 系统 / find -delete）→ Block；
 * 4. 危险规则（rm /data、dd /dev(星号)、chmod 777、远程管道执行 …）→ Confirm(DANGEROUS)；
 * 5. 警告规则 → Confirm(WARNING)；
 * 6. 其余 → Allow。
 *
 * 黑名单只是提示层：主防线是档位 3 的「脚本默认非 Root」+ shso_guard 运行时守卫。
 */
object PolicyEngine {

    private val RM_LIKE = setOf("rm", "rmdir", "shred", "wipe", "unlink")
    private val MKFS_LIKE = setOf("mkfs", "mke2fs", "make_f2fs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.f2fs", "mkfs.vfat", "mkfs.exfat", "mkfs.ntfs")
    private val FETCHERS = setOf("curl", "wget")
    private val DECODERS = setOf("base64", "openssl")

    /** 完整评估一条命令（终端输入 / 脚本行）。 */
    fun evaluate(command: String, source: CommandSource): Verdict {
        if (source == CommandSource.INTERNAL_APP) return Verdict.Allow
        val parsed = CommandParser.parse(command)
        return evaluateParsed(parsed, source)
    }

    /** 评估解析结果（ScriptAuditor 复用，附加行号）。 */
    fun evaluateParsed(parsed: CommandParser.Parsed, source: CommandSource, line: Int? = null): Verdict {
        if (source == CommandSource.INTERNAL_APP) return Verdict.Allow

        // fail-closed：解析超限 / 异常 → CRITICAL 确认
        if (parsed.truncated) {
            return Verdict.Confirm(
                listOf(
                    Finding(
                        ruleId = "PARSER_OVERFLOW",
                        level = RiskLevel.CRITICAL,
                        message = "命令过长或结构过于复杂，无法完整解析，需人工确认",
                        snippet = "",
                        line = line
                    )
                ),
                RiskLevel.CRITICAL
            )
        }

        val findings = ArrayList<Finding>()
        val atoms = parsed.atoms

        for (atom in atoms) {
            evaluateAtom(atom, line, findings)
        }

        // 管道检测：curl/wget/base64 | sh（段序号相邻 + 后段程序为 shell）
        detectPipeToShell(atoms, line, findings)

        if (findings.isEmpty()) return Verdict.Allow

        val blocked = findings.filter { it.level == RiskLevel.CRITICAL }
        if (blocked.isNotEmpty()) return Verdict.Block(blocked)

        val maxLevel = findings.maxOf { it.level }
        return if (maxLevel >= RiskLevel.DANGEROUS || maxLevel == RiskLevel.WARNING) {
            Verdict.Confirm(findings, maxLevel)
        } else {
            Verdict.Allow
        }
    }

    /** 单原子规则判定，命中的 Finding 追加进 findings。 */
    private fun evaluateAtom(atom: CommandParser.Atom, line: Int?, findings: ArrayList<Finding>) {
        val snippet = atom.raw.take(200)

        when (atom.program) {
            in RM_LIKE -> evaluateRm(atom, line, snippet, findings)
            "dd" -> evaluateDd(atom, line, snippet, findings)
            in MKFS_LIKE -> findings.add(
                Finding("MKFS", RiskLevel.CRITICAL, "格式化文件系统/分区", snippet, line)
            )
            "wipe" -> findings.add(
                Finding("WIPE", RiskLevel.CRITICAL, "擦除数据分区", snippet, line)
            )
            "fastboot" -> evaluateFastboot(atom, line, snippet, findings)
            "chmod" -> evaluateChmod(atom, line, snippet, findings)
            "chown", "chgrp" -> evaluateChown(atom, line, snippet, findings)
            "find" -> evaluateFind(atom, line, snippet, findings)
            "recovery" -> if (atom.args.any { it.contains("wipe") }) {
                findings.add(Finding("RECOVERY_WIPE", RiskLevel.DANGEROUS, "恢复模式清数据", snippet, line))
            }
        }
    }

    /** rm 族：递归删除 + 路径分级。 */
    private fun evaluateRm(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val recursive = atom.program != "unlink" && (
            atom.args.any { it.startsWith("-") && it.contains("r", ignoreCase = true) && it.none { c -> c == '=' } }
            )
        val targets = rmOperands(atom)

        for (t in targets) {
            val cls = PathClassifier.classify(t)
            val display = t.take(120)
            if (!recursive && cls != PathClassifier.PathClass.CRITICAL) continue
            when (cls) {
                PathClassifier.PathClass.CRITICAL -> findings.add(
                    Finding(
                        "RM_SYSTEM", RiskLevel.CRITICAL,
                        if (recursive) "递归删除系统分区/关键目录: $display" else "删除系统文件: $display",
                        snippet, line
                    )
                )
                PathClassifier.PathClass.DANGEROUS -> findings.add(
                    Finding("RM_DATA", RiskLevel.DANGEROUS, "递归删除数据分区: $display", snippet, line)
                )
                PathClassifier.PathClass.WARNING -> if (recursive) findings.add(
                    Finding("RM_APPDATA", RiskLevel.WARNING, "递归删除应用数据: $display", snippet, line)
                )
                PathClassifier.PathClass.SAFE -> {}
            }
        }
    }

    /** rm 的操作数：跳过 flag（通配符路径由 PathClassifier.normalize 取基路径分级）。 */
    private fun rmOperands(atom: CommandParser.Atom): List<String> =
        atom.args.filter { !it.startsWith("-") }

    /** dd：看 of= 输出目标（破坏来自输出端）。 */
    private fun evaluateDd(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val of = atom.args.firstOrNull { it.startsWith("of=") }?.removePrefix("of=")
            ?: atom.operands.firstOrNull { it.startsWith("/dev") } // 裸操作数形态
        if (of == null) return
        // 常见安全目标：/dev/null 等
        if (of == "/dev/null") return
        val cls = PathClassifier.classify(of)
        when {
            of.startsWith("/dev/block/") || of.startsWith("/dev/sd") ||
                of.startsWith("/dev/mmcblk") || of.startsWith("/dev/by-name/") ||
                of.startsWith("/dev/dm-") ->
                findings.add(Finding("DD_BLOCK_DEV", RiskLevel.CRITICAL, "直接覆写块设备: ${of.take(120)}", snippet, line))
            cls == PathClassifier.PathClass.CRITICAL ->
                findings.add(Finding("DD_DEV", RiskLevel.CRITICAL, "覆写设备文件: ${of.take(120)}", snippet, line))
            cls == PathClassifier.PathClass.DANGEROUS ->
                findings.add(Finding("DD_DATA", RiskLevel.DANGEROUS, "覆写数据分区文件: ${of.take(120)}", snippet, line))
            else -> {}
        }
    }

    /** fastboot：erase/format/wipe 一律 CRITICAL；flash 高危确认。 */
    private fun evaluateFastboot(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val sub = atom.operands.firstOrNull() ?: return
        when (sub) {
            "erase", "format", "wipe" -> findings.add(
                Finding("FASTBOOT_ERASE", RiskLevel.CRITICAL, "fastboot $sub 分区", snippet, line)
            )
            "flash" -> findings.add(
                Finding("FASTBOOT_FLASH", RiskLevel.DANGEROUS, "fastboot 刷写分区（如非本人操作请勿继续）", snippet, line)
            )
        }
    }

    /** chmod：-R 777/666 到系统分区 = 权限崩坏。 */
    private fun evaluateChmod(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val recursive = atom.args.any { it.startsWith("-") && it.contains("R") }
        val mode = atom.args.firstOrNull { it.length == 3 && it.all { c -> c in '0'..'7' } }
            ?: atom.args.firstOrNull { it.length == 4 && it.all { c -> c in '0'..'7' } }
        val worldWritable = mode != null && (mode.endsWith("7") || mode.endsWith("6") || mode.endsWith("3") || mode.endsWith("2"))
        for (t in atom.operands) {
            val cls = PathClassifier.classify(t)
            if (cls == PathClassifier.PathClass.CRITICAL && (recursive || worldWritable)) {
                findings.add(
                    Finding(
                        "CHMOD_SYSTEM", RiskLevel.CRITICAL,
                        "${if (recursive) "递归" else ""}放宽系统路径权限($mode): ${t.take(120)}",
                        snippet, line
                    )
                )
            } else if (cls == PathClassifier.PathClass.DANGEROUS && recursive && worldWritable) {
                findings.add(
                    Finding("CHMOD_DATA", RiskLevel.DANGEROUS, "递归放宽数据分区权限($mode)", snippet, line)
                )
            }
        }
    }

    /** chown：-R 指向系统分区。 */
    private fun evaluateChown(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val recursive = atom.args.any { it.startsWith("-") && it.contains("R") }
        if (!recursive) return
        for (t in atom.operands) {
            if (PathClassifier.classify(t) == PathClassifier.PathClass.CRITICAL) {
                findings.add(Finding("CHOWN_SYSTEM", RiskLevel.CRITICAL, "递归变更系统文件属主: ${t.take(120)}", snippet, line))
            }
        }
    }

    /** find -delete / -exec rm：等价递归删除，按搜索起点分级。 */
    private fun evaluateFind(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val hasDelete = atom.args.any { it == "-delete" }
        val hasExecRm = atom.args.any { it.startsWith("-exec") } &&
            atom.args.dropWhile { !it.startsWith("-exec") }.take(3).any { it == "rm" }
        if (!hasDelete && !hasExecRm) return
        val start = atom.operands.firstOrNull() ?: return
        when (PathClassifier.classify(start)) {
            PathClassifier.PathClass.CRITICAL -> findings.add(
                Finding("FIND_DELETE", RiskLevel.CRITICAL, "find 递归删除系统路径: ${start.take(120)}", snippet, line)
            )
            PathClassifier.PathClass.DANGEROUS -> findings.add(
                Finding("FIND_DELETE_DATA", RiskLevel.DANGEROUS, "find 递归删除数据分区: ${start.take(120)}", snippet, line)
            )
            else -> {}
        }
    }

    /** curl/wget | sh 与 base64 | sh：远程/编码内容直接进 shell。 */
    private fun detectPipeToShell(atoms: List<CommandParser.Atom>, line: Int?, findings: ArrayList<Finding>) {
        val bySegment = atoms.groupBy { it.segmentId }
        val sorted = bySegment.keys.sorted()
        for (idx in 0 until sorted.size - 1) {
            val cur = bySegment[sorted[idx]]?.firstOrNull() ?: continue
            val next = bySegment[sorted[idx + 1]]?.firstOrNull() ?: continue
            if (next.program in setOf("sh", "bash", "ash", "dash")) {
                when {
                    cur.program in FETCHERS -> findings.add(
                        Finding("REMOTE_PIPE_SHELL", RiskLevel.DANGEROUS, "远程内容直接执行（${cur.program} | sh）", cur.raw.take(200), line)
                    )
                    cur.program in DECODERS -> findings.add(
                        Finding("ENCODED_PIPE_SHELL", RiskLevel.DANGEROUS, "编码内容直接执行（${cur.program} | sh），常见于混淆恶意脚本", cur.raw.take(200), line)
                    )
                }
            }
        }
    }

    /** 当前安全档位（读 AppSettings；未初始化时保守取 STANDARD）。 */
    fun currentLevel(): Int = try {
        RootService.appSettings?.securityLevel ?: SecurityLevels.STANDARD
    } catch (_: Exception) {
        SecurityLevels.STANDARD
    }
}
