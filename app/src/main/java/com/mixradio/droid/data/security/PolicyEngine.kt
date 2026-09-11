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

    private val RM_LIKE = setOf("rm", "rmdir", "shred", "unlink")
    private val MKFS_LIKE = setOf("mkfs", "mke2fs", "make_f2fs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.f2fs", "mkfs.vfat", "mkfs.exfat", "mkfs.ntfs")
    private val FETCHERS = setOf("curl", "wget")
    private val SHELL_PROGRAMS = setOf("sh", "bash", "ash", "dash", "mksh")
    /** 解码/解压工具：与 shell 组合可执行「加密脚本」（混淆恶意脚本最常见的手法）。 */
    private val DECODERS = setOf(
        "base64", "openssl", "xxd", "uudecode", "gunzip", "gzip", "zcat",
        "bunzip2", "bzcat", "bzip2", "unxz", "xz", "unlzma", "lzma", "lz4", "zstd", "unzip", "cpio"
    )
    /** 可内联执行代码的解释器：`python -c "…"` 里塞 base64 载荷同样属混淆执行。 */
    private val INTERPRETERS = setOf("python", "python3", "perl", "ruby", "node", "php", "lua")
    /** 分区表 / 刷机类工具：误用即「格机」，一律硬拦。 */
    private val BRICK_TOOLS = setOf(
        "sgdisk", "parted", "fdisk", "sfdisk", "gdisk", "cgdisk", "wipefs",
        "flash_image", "mtd", "nandwrite", "fastbootd", "odin", "heimdall"
    )
    /** 写入型工具（目标为末操作数）：cp / install / ln / rsync。 */
    private val COPY_LIKE = setOf("cp", "install", "ln", "rsync")
    /** 未解析变量 + 这些程序 = 无法静态判定目标，按无条件最高危处理（fail-closed）。 */
    private val CRITICAL_UNRESOLVED = setOf(
        "dd", "wipe", "fastboot", "shred", "truncate", "sgdisk", "parted", "fdisk",
        "sfdisk", "gdisk", "cgdisk", "wipefs", "flash_image", "mtd", "nandwrite"
    )
    /** 未解析变量 + 这些程序 = 提升为需确认的高危（rm -rf "$T" 在脚本里极常见，不宜一律硬拦）。 */
    private val DANGEROUS_UNRESOLVED = setOf(
        "rm", "rmdir", "chmod", "chown", "chgrp", "find", "sed"
    )
    /** 重定向写入这些设备属正常操作（重定向目标不做路径分级，避免 `ls > /dev/null` 误报）。 */
    private val SAFE_REDIRECT_DEVICES = setOf(
        "/dev/null", "/dev/zero", "/dev/random", "/dev/urandom",
        "/dev/stdout", "/dev/stderr", "/dev/tty"
    )

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
            evaluateAtom(atom, source, line, findings)
        }

        // 管道检测：curl/wget/base64 | sh（段序号相邻 + 后段程序为 shell）
        detectPipeToShell(atoms, source, line, findings)
        // 解释器内联执行 + 解码载荷（python -c "base64..." 等）
        detectInterpreterPayload(atoms, source, line, findings)
        // eval 动态执行：eval 本身 + 解码器 / 未解析变量 → 无法审计内容
        detectEvalDynamic(atoms, source, line, findings)

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
    private fun evaluateAtom(
        atom: CommandParser.Atom,
        source: CommandSource,
        line: Int?,
        findings: ArrayList<Finding>
    ) {
        val snippet = atom.raw.take(200)

        // 0) fail-closed 前置：程序名本身含变量（$CMD / r$IFSm / rm$IFS-rf…）→ 真实命令不可知。
        //    必须在这里拦，因为 basename 可能被 `$IFS` 之类拼成任意字符串（如 `system`），
        //    落到下面任何规则集里都匹配不到。
        if (atom.programUnresolved) {
            findings.add(
                Finding(
                    "UNRESOLVED_PROGRAM", obfuscationLevel(source),
                    "命令名含未解析变量（如 \$IFS 拼接），无法确定真实要执行的程序", snippet, line
                )
            )
            return
        }

        // fail-closed 前置：无法确定真实程序 → 不能假设它安全
        if (atom.programAmbiguous) {
            findings.add(
                Finding(
                    "AMBIGUOUS_PROGRAM", RiskLevel.CRITICAL,
                    "无法确定要执行的真实命令（疑似被 wrapper 选项混淆），已按最高危处理", snippet, line
                )
            )
            return
        }

        // 1) 未解析变量：破坏性程序 + 变量 = 目标不可静态判定
        if (atom.hasUnresolvedVar) {
            val p = atom.program
            if (p in CRITICAL_UNRESOLVED || p.startsWith("mkfs")) {
                findings.add(
                    Finding(
                        "UNRESOLVED_DESTRUCTIVE", RiskLevel.CRITICAL,
                        "高危命令的操作目标含未解析变量，无法判定影响范围: $p", snippet, line
                    )
                )
            } else if (p in DANGEROUS_UNRESOLVED) {
                findings.add(
                    Finding(
                        "UNRESOLVED_DESTRUCTIVE_CONFIRM", RiskLevel.DANGEROUS,
                        "命令的操作目标含未解析变量（$p），执行前请确认实际路径", snippet, line
                    )
                )
            }
        }

        when (atom.program) {
            in RM_LIKE -> evaluateRm(atom, line, snippet, findings)
            "dd" -> evaluateDd(atom, line, snippet, findings)
            "truncate" -> evaluateTruncate(atom, line, snippet, findings)
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
            in BRICK_TOOLS -> findings.add(
                Finding("BRICK_TOOL", RiskLevel.CRITICAL, "分区表 / 刷机类高危工具（可能直接导致设备无法启动）", snippet, line)
            )
            "tee" -> evaluateTee(atom, line, snippet, findings)
            in COPY_LIKE -> evaluateCopyLike(atom, line, snippet, findings)
            "mv" -> evaluateMove(atom, line, snippet, findings)
        }

        // 2) 重定向写入：覆盖 `cat img > /dev/block/by-name/boot` 这类不经 dd 的写入
        evaluateRedirects(atom, line, snippet, findings)
    }

    /** truncate：把目标截断为 0/指定大小，对系统/数据分区等同破坏。 */
    private fun evaluateTruncate(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        for (t in atom.operands) {
            when (PathClassifier.classify(t)) {
                PathClassifier.PathClass.CRITICAL -> findings.add(
                    Finding("TRUNCATE_SYSTEM", RiskLevel.CRITICAL, "截断系统/设备文件: ${t.take(120)}", snippet, line)
                )
                PathClassifier.PathClass.DANGEROUS -> findings.add(
                    Finding("TRUNCATE_DATA", RiskLevel.DANGEROUS, "截断数据分区文件: ${t.take(120)}", snippet, line)
                )
                else -> {}
            }
        }
    }

    /**
     * cp / install / ln / rsync：**目标**是系统或设备路径 → 等于往系统里写文件。
     * 静态层此前完全没有这类规则，只能靠运行时守卫的 PATH 包装器兜底 ——
     * 脚本自动执行链路里就漏了。
     */
    private fun evaluateCopyLike(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val target = atom.operands.lastOrNull() ?: return
        when (PathClassifier.classify(target)) {
            PathClassifier.PathClass.CRITICAL -> findings.add(
                Finding("COPY_SYSTEM", RiskLevel.CRITICAL, "写入系统/设备路径: ${target.take(120)}", snippet, line)
            )
            PathClassifier.PathClass.DANGEROUS -> findings.add(
                Finding("COPY_DATA", RiskLevel.DANGEROUS, "写入数据分区: ${target.take(120)}", snippet, line)
            )
            else -> {}
        }
    }

    /**
     * mv：目标是系统/设备路径 → 覆盖写入；**源**是系统路径 → 把系统文件移走同样等于破坏。
     * 两者合并判定，取最高等级。
     */
    private fun evaluateMove(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        if (atom.operands.isEmpty()) return
        val target = atom.operands.last()
        when (PathClassifier.classify(target)) {
            PathClassifier.PathClass.CRITICAL -> findings.add(
                Finding("MOVE_SYSTEM", RiskLevel.CRITICAL, "移动到系统/设备路径: ${target.take(120)}", snippet, line)
            )
            PathClassifier.PathClass.DANGEROUS -> findings.add(
                Finding("MOVE_DATA", RiskLevel.DANGEROUS, "移动到数据分区: ${target.take(120)}", snippet, line)
            )
            else -> {}
        }
        for (src in atom.operands.dropLast(1)) {
            when (PathClassifier.classify(src)) {
                PathClassifier.PathClass.CRITICAL -> findings.add(
                    Finding("MOVE_SYSTEM_SRC", RiskLevel.DANGEROUS, "从系统路径移走文件: ${src.take(120)}", snippet, line)
                )
                else -> {}
            }
        }
    }

    /** tee：把内容写入列出的文件；目标是系统/设备文件即高危。 */
    private fun evaluateTee(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        for (t in atom.operands) {
            if (t == "-a" || t == "--append" || t.startsWith("-")) continue
            when (PathClassifier.classify(t)) {
                PathClassifier.PathClass.CRITICAL -> findings.add(
                    Finding("TEE_SYSTEM", RiskLevel.CRITICAL, "写入系统/设备文件: ${t.take(120)}", snippet, line)
                )
                PathClassifier.PathClass.DANGEROUS -> findings.add(
                    Finding("TEE_DATA", RiskLevel.DANGEROUS, "写入数据分区文件: ${t.take(120)}", snippet, line)
                )
                else -> {}
            }
        }
    }

    /**
     * 重定向目标分级（`>` / `>>` / `2>` / `&>`）。
     * 只对**绝对路径**目标判定，相对目标（如 `> out.txt`）与安全设备文件（/dev/null 等）跳过，
     * 避免把 `cmd > /dev/null`、`cmd > log.txt` 这类正常写法误报。
     */
    private fun evaluateRedirects(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        for (target in atom.redirects) {
            if (target.startsWith("/dev/fd/") || target.startsWith("/dev/pts/")) continue
            if (target in SAFE_REDIRECT_DEVICES) continue
            if (target == "/proc/sysrq-trigger") {
                findings.add(
                    Finding("REDIRECT_SYSRQ", RiskLevel.CRITICAL, "写入 /proc/sysrq-trigger（可致系统立即崩溃/重启）", snippet, line)
                )
                continue
            }
            when (PathClassifier.classify(target)) {
                PathClassifier.PathClass.CRITICAL -> findings.add(
                    Finding("REDIRECT_SYSTEM", RiskLevel.CRITICAL, "重定向写入系统/设备路径: ${target.take(120)}", snippet, line)
                )
                PathClassifier.PathClass.DANGEROUS -> findings.add(
                    Finding("REDIRECT_DATA", RiskLevel.DANGEROUS, "重定向写入数据分区: ${target.take(120)}", snippet, line)
                )
                else -> {}
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

    /**
     * curl/wget | sh 与 base64/解压 | sh：远程/编码内容直接进 shell。
     *
     * 分级按来源区分：交互终端里用户是**显式输入**了这条命令，给可确认的 DANGEROUS；
     * 脚本文件里出现则说明作者刻意隐藏载荷 → CRITICAL，自动执行链路直接拦截。
     */
    private fun detectPipeToShell(
        atoms: List<CommandParser.Atom>,
        source: CommandSource,
        line: Int?,
        findings: ArrayList<Finding>
    ) {
        val bySegment = atoms.groupBy { it.segmentId }
        val sorted = bySegment.keys.sorted()
        for (idx in 0 until sorted.size - 1) {
            val cur = bySegment[sorted[idx]]?.firstOrNull() ?: continue
            val next = bySegment[sorted[idx + 1]]?.firstOrNull() ?: continue
            if (next.program in SHELL_PROGRAMS) {
                when {
                    cur.program in FETCHERS -> findings.add(
                        Finding("REMOTE_PIPE_SHELL", RiskLevel.DANGEROUS, "远程内容直接执行（${cur.program} | sh）", cur.raw.take(200), line)
                    )
                    cur.program in DECODERS -> findings.add(
                        Finding(
                            "ENCODED_PIPE_SHELL",
                            if (source == CommandSource.SCRIPT_FILE) RiskLevel.CRITICAL else RiskLevel.DANGEROUS,
                            "编码/压缩内容直接交给 shell 执行（${cur.program} | sh），常见于加密混淆脚本",
                            cur.raw.take(200), line
                        )
                    )
                }
            }
        }
    }

    /**
     * 解释器内联执行解码载荷：`python -c "import base64;exec(base64.b64decode(…))"`、
     * `perl -e "…"`、`node -e "…"` 等。这类命令的内容不在命令流里，静态规则看不到。
     */
    private fun detectInterpreterPayload(
        atoms: List<CommandParser.Atom>,
        source: CommandSource,
        line: Int?,
        findings: ArrayList<Finding>
    ) {
        for (a in atoms) {
            if (a.program !in INTERPRETERS) continue
            val joined = a.args.joinToString(" ")
            if (containsDecoderMarker(joined)) {
                findings.add(
                    Finding(
                        "INTERPRETER_PAYLOAD", obfuscationLevel(source),
                        "解释器内联执行解码后的载荷（${a.program} -c/-e …），属混淆执行",
                        a.raw.take(200), line
                    )
                )
            }
        }
    }

    /**
     * 混淆类行为的风险等级：脚本文件里出现 → CRITICAL（自动执行直接拦），
     * 交互终端里是用户显式输入 → DANGEROUS（弹窗确认后仍可执行）。
     */
    private fun obfuscationLevel(source: CommandSource): RiskLevel =
        if (source == CommandSource.SCRIPT_FILE) RiskLevel.CRITICAL else RiskLevel.DANGEROUS

    /**
     * `eval` 动态执行：eval 的内容在运行时才拼装，静态无法审计。
     * - eval + 解码器 / 未解析变量 → CRITICAL（几乎必然是混淆载荷）；
     * - 其它 eval → DANGEROUS 提示。
     */
    private fun detectEvalDynamic(
        atoms: List<CommandParser.Atom>,
        source: CommandSource,
        line: Int?,
        findings: ArrayList<Finding>
    ) {
        val evalAtoms = atoms.filter { it.program == "eval" }
        if (evalAtoms.isEmpty()) return
        val hasDecoder = atoms.any { it.program in DECODERS && isDecodeInvocation(it) }
        val hasVar = evalAtoms.any { it.hasUnresolvedVar }
        val snippet = evalAtoms.first().raw.take(200)
        if (hasDecoder || hasVar) {
            findings.add(
                Finding(
                    "EVAL_DYNAMIC", obfuscationLevel(source),
                    "eval 动态执行解码/变量拼装的内容，静态无法审计（常见于加密脚本）", snippet, line
                )
            )
        } else {
            findings.add(Finding("EVAL", RiskLevel.DANGEROUS, "eval 动态求值并执行", snippet, line))
        }
    }

    /** 该原子是否在「解码/解压」而非编码（如 base64 -d、xxd -r、openssl enc -d、gunzip）。 */
    private fun isDecodeInvocation(a: CommandParser.Atom): Boolean = when (a.program) {
        "xxd" -> a.args.any { it == "-r" }
        "openssl" -> a.args.any { it == "enc" || it == "dgst" } || a.args.any { it == "-d" || it == "-D" }
        "base64" -> a.args.any { it == "-d" || it == "--decode" }
        "gunzip", "zcat", "bunzip2", "bzcat", "unxz", "unlzma", "lz4", "zstd", "unzip", "uudecode", "cpio" -> true
        else -> false
    }

    /** 文本中是否出现「解码器」特征（大小写不敏感）。 */
    private fun containsDecoderMarker(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("base64") || t.contains("b64decode") || t.contains("b64encode") ||
            t.contains("frombase64") || t.contains("atob(") || t.contains("unhexlify") ||
            t.contains("xxd -r") || t.contains("openssl enc") || t.contains("\\x") ||
            t.contains("bytes.fromhex") || t.contains("codecs.decode")
    }

    /** 当前安全档位（读 AppSettings；未初始化时保守取 STANDARD）。 */
    fun currentLevel(): Int = try {
        RootService.appSettings?.securityLevel ?: SecurityLevels.STANDARD
    } catch (_: Exception) {
        SecurityLevels.STANDARD
    }
}
