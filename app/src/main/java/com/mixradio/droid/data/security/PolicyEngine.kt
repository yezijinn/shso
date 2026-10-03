// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

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
    /**
     * 未解析变量 + 这些程序 = 提升为需确认的高危（rm -rf "$T" 在脚本里极常见，不宜一律硬拦）。
     *
     * 写入型与移动型工具同样必须在列：它们的目标是**末操作数**，
     * 而命令替换/变量能把目标拼成任意路径 —— `rsync -a /x $(echo /system)/bin/` 的末位
     * 只有 `/bin/`，替换出的 `/system` 若不参与分级就等于完全不可见。
     * 这类操作在脚本里不像 `rm -rf` 那样常见，故列 DANGEROUS 而非 CRITICAL。
     */
    private val DANGEROUS_UNRESOLVED = setOf(
        "rm", "rmdir", "chmod", "chown", "chgrp", "find", "sed",
        "cp", "install", "ln", "rsync", "mv"
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
            val program = atom.program
            if (program in CRITICAL_UNRESOLVED || program.startsWith("mkfs")) {
                findings.add(
                    Finding(
                        "UNRESOLVED_DESTRUCTIVE", RiskLevel.CRITICAL,
                        "高危命令的操作目标含未解析变量，无法判定影响范围: $program", snippet, line
                    )
                )
            } else if (program in DANGEROUS_UNRESOLVED) {
                findings.add(
                    Finding(
                        "UNRESOLVED_DESTRUCTIVE_CONFIRM", RiskLevel.DANGEROUS,
                        "命令的操作目标含未解析变量（$program），执行前请确认实际路径", snippet, line
                    )
                )
            }
        }

        when (atom.program) {
            in RM_LIKE -> evaluateRm(atom, line, snippet, findings)
            "dd" -> evaluateDd(atom, line, snippet, findings)
            "truncate" -> evaluateTruncate(atom, line, snippet, findings)
            in SHELL_PROGRAMS -> evaluateShellFileExecution(atom, source, line, snippet, findings)
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
            // 拆掉防护体系本身的原语。此前这些程序在 evaluateAtom 的 when 里**无任何分支**，
            // 落到末尾即 Verdict.Allow：守卫目录也没有对应包装器（PATH 前置对其完全无效），
            // 于是在最高档位下也能无声关掉 MAC、删光全部 root 模块（root 与守卫同时消失）。
            "setenforce" -> findings.add(
                Finding("SELINUX_TOGGLE", RiskLevel.CRITICAL, "切换 SELinux 模式（关闭后全部强制访问控制失效）", snippet, line)
            )
            "resetprop" -> findings.add(
                Finding("PROP_OVERRIDE", RiskLevel.CRITICAL, "修改 Android 系统属性（可绕过属性级安全策略）", snippet, line)
            )
            "mount", "umount" -> evaluateMount(atom, line, snippet, findings)
            "magisk" -> evaluateMagisk(atom, line, snippet, findings)
        }

        // 2) 重定向写入：覆盖 `cat img > /dev/block/by-name/boot` 这类不经 dd 的写入
        evaluateRedirects(atom, source, line, snippet, findings)
    }

    /**
     * mount / umount：改写挂载点即改写「路径 → 实际内容」的映射。
     * `mount -o remount,rw /system` 把只读系统分区变可写，此后任何普通写命令都能落盘；
     * 叠加 `mount -o bind <任意目录> /system/app` 更是把任意可写目录顶到系统目录下。
     */
    private fun evaluateMount(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val targets = atom.operands.filter { it.startsWith("/") }
        val remountRw = atom.args.any { it.contains("remount") } &&
            atom.args.any { it.contains("rw") }
        val bind = atom.args.any { it.contains("bind") }
        val touchesProtected = targets.any {
            PathClassifier.classify(it) == PathClassifier.PathClass.CRITICAL
        }
        val level = when {
            touchesProtected && (remountRw || bind) -> RiskLevel.CRITICAL
            remountRw || bind -> RiskLevel.DANGEROUS
            targets.isNotEmpty() -> RiskLevel.WARNING
            else -> return
        }
        val what = when {
            remountRw && bind -> "以读写方式 bind 挂载"
            remountRw -> "重挂载为可写"
            bind -> "bind 挂载"
            else -> "改挂载"
        }
        findings.add(
            Finding(
                "MOUNT_MODIFY", level,
                "$what：${targets.joinToString(", ").ifEmpty { "未指定挂载点" }}",
                snippet, line
            )
        )
    }

    /**
     * magisk：只拦「拆掉防护体系」的子命令，其余（`-v` / `list` 等只读查询）零干预。
     *
     * `magisk --remove-modules` 会删掉全部已安装模块 —— 包括本项目的守卫模块，
     * 即 root 能力与运行时防护同时消失，属不可逆的变砖级操作。
     * 注意 `magisk` 在解析层被当作 wrapper 前缀剥掉过（见 [CommandParser]），
     * 所以这里要同时看 program 与原始操作数。
     */
    private fun evaluateMagisk(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val all = atom.args + atom.operands
        val critical = all.any {
            it == "--remove-modules" || it == "--remove-module" ||
                it == "--uninstall" || it == "--resetprop" || it == "--remove"
        }
        if (critical) {
            findings.add(
                Finding(
                    "MAGISK_TAMPER", RiskLevel.CRITICAL,
                    "卸载/重置 Magisk 模块或属性（会同时移除 root 能力与本项目的守卫模块）",
                    snippet, line
                )
            )
            return
        }
        if (all.any { it.startsWith("--install") || it.startsWith("--patch") }) {
            findings.add(
                Finding("MAGISK_PATCH", RiskLevel.DANGEROUS, "安装或修补 Magisk 模块（引导期生效）", snippet, line)
            )
        }
    }

    /** truncate：把目标截断为 0/指定大小，对系统/数据分区等同破坏。 */
    private fun evaluateTruncate(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {        for (t in atom.operands) {
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
     * 静态层必须在此拦截：仅靠运行时守卫的 PATH 包装器无法覆盖脚本自动执行链路。
     *
     * ## 为什么不能只看 `operands.last()`
     *
     * `cp -t /system/bin a b` 的语义是「把所有源搬进 `/system/bin`」，此时
     * **最后一个操作数是源 `b`，真正的目标是选项 `-t` 的值**。只取末位会判成
     * 「目标 = b（普通相对路径）」→ 放行，规则被一句话绕过。
     * `mv -t` / `install -t` 同理。这里显式识别 `-t` / `--target-directory`，
     * 取其后的值作为目标，并对**所有**出现的 `-t` 取值都判一遍。
     */
    private fun evaluateCopyLike(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val explicitTargets = targetDirectoryArgs(atom.args)
        // 没有 -t 时才退回「末位操作数即目标」的常规语义
        val targets = if (explicitTargets.isNotEmpty()) explicitTargets else listOfNotNull(atom.operands.lastOrNull())
        for (target in targets) {
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
    }

    /**
     * 提取 `-t <dir>` / `--target-directory=<dir>` / `--target-directory <dir>` 的目录值。
     *
     * 支持 `--target-directory=/system` 的等号形式（GNU coreutils 与 toybox 都接受）。
     * 纯函数，便于单测。
     */
    internal fun targetDirectoryArgs(args: List<String>): List<String> {
        val result = ArrayList<String>(1)
        var expectNext = false
        for (arg in args) {
            if (expectNext) {
                result += arg
                expectNext = false
                continue
            }
            when {
                arg == "-t" || arg == "--target-directory" -> expectNext = true
                arg.startsWith("--target-directory=") -> result += arg.substringAfter('=')
            }
        }
        return result
    }

    /**
     * mv：目标是系统/设备路径 → 覆盖写入；**源**是系统路径 → 把系统文件移走同样等于破坏。
     * 两者合并判定，取最高等级。同样处理 `-t` 目标目录形式。
     */
    private fun evaluateMove(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        if (atom.operands.isEmpty()) return
        val explicitTargets = targetDirectoryArgs(atom.args)
        val targets = if (explicitTargets.isNotEmpty()) explicitTargets else listOf(atom.operands.last())
        for (target in targets) {
            when (PathClassifier.classify(target)) {
                PathClassifier.PathClass.CRITICAL -> findings.add(
                    Finding("MOVE_SYSTEM", RiskLevel.CRITICAL, "移动到系统/设备路径: ${target.take(120)}", snippet, line)
                )
                PathClassifier.PathClass.DANGEROUS -> findings.add(
                    Finding("MOVE_DATA", RiskLevel.DANGEROUS, "移动到数据分区: ${target.take(120)}", snippet, line)
                )
                else -> {}
            }
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
    private fun evaluateRedirects(atom: CommandParser.Atom, source: CommandSource, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        // 未解析的重定向目标：`> $T/build.prop`、`> "$OUT"`、`bash <(curl …)`。
        // 「写到哪」在静态层不可知，按 fail-closed 上报 —— 此前这些目标被解析层
        // 丢弃且从 words 中消费掉，导致 root 覆写系统文件零 finding。
        if (atom.unresolvedRedirects.isNotEmpty()) {
            findings.add(
                Finding(
                    "REDIRECT_UNRESOLVED", obfuscationLevel(source),
                    "重定向目标无法静态判定（可能写入任意路径）：" +
                        atom.unresolvedRedirects.joinToString(" ").take(120),
                    snippet, line
                )
            )
        }
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
    /**
     * `sh <文件>` / `bash <文件>`：把一个**文件**交给解释器执行。
     *
     * `SHELL_PROGRAMS` 此前只用在「管道终点」检测里，`evaluateAtom` 的 when
     * 没有对应分支 → `sh /sdcard/p.dat` 零 finding。
     *
     * 为什么这是缺口：脚本**内容审查**（[ScriptAuditor]）只在「执行文件」链路上被调用
     * （`RootService.executeFilePreflight`，且只对 `isSh` 为真的后缀生效）。
     * 于是把含 `rm -rf /system`、`curl …|sh`、`python3 -c …` 的文件命名成任意
     * 非 `.sh` 后缀，再在终端手输 `sh /sdcard/p.dat`，就完全绕过内容审查 ——
     * 同一份内容走文件页会被拦（且高档位直接拒绝自动执行），走终端一路放行。
     *
     * 分级理由与管道终点一致：交互终端里用户是**显式输入**了这条命令，给可确认的
     * DANGEROUS；脚本文件里出现说明作者刻意把载荷藏进非脚本后缀 → CRITICAL，
     * 直接拦停自动执行链路。
     *
     * 只在「存在非选项操作数」时判定：`sh`、`sh -x` 这类交互式读 stdin 的用法不受影响。
     */
    private fun evaluateShellFileExecution(
        atom: CommandParser.Atom,
        source: CommandSource,
        line: Int?,
        snippet: String,
        findings: ArrayList<Finding>
    ) {
        val scriptOperand = atom.operands.firstOrNull { !it.startsWith("-") } ?: return
        findings.add(
            Finding(
                "SHELL_FILE_EXECUTION", obfuscationLevel(source),
                "由解释器执行文件（${atom.program} $scriptOperand）：该文件内容不受命令文本审查，" +
                    "若其扩展名不是脚本后缀则同时绕过了脚本内容扫描",
                snippet, line
            )
        )
    }

    /**
     * `sh <文件>` 的判定：见 [evaluateShellFileExecution]。
     */

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

    /**
     * find -delete / -exec <破坏性命令>：等价递归删除，按搜索起点分级。
     *
     * ## 为什么要剥 wrapper 再比对
     *
     * 原判定只认裸 `rm`，于是 `find /system -exec /system/bin/rm {} +`、
     * `find /system -exec busybox rm {} +`、`find /system -exec toybox rm {} +`
     * 全部逃逸（守卫侧的 FIND 模式已经处理了 basename，这里是 App 侧静态层的同类漏洞）。
     * 统一做法：把 `-exec`/`-execdir` 之后的**第一个非选项 token** 取 basename，
     * 再按 basename 判定是否为破坏性命令 —— 绝对路径、busybox/toybox 派发一并覆盖。
     *
     * ## 分级与 ruleId 不变（重要）
     *
     * 命中破坏性 `-exec` 时**沿用与 `-delete` 相同的等级映射与 ruleId**（CRITICAL 路径 →
     * `FIND_DELETE` / CRITICAL）。两者语义等价（都是 `find` 遍历该起点后删除），若把
     * `-exec` 降一级或换 ruleId，`find /system -exec rm {} +` 就会从 Block 退成 Confirm、
     * 且审计侧的规则词表失稳 —— 都属于安全回退。触发方式的差异写进 message 而非 ruleId。
     */
    private fun evaluateFind(atom: CommandParser.Atom, line: Int?, snippet: String, findings: ArrayList<Finding>) {
        val hasDelete = atom.args.any { it == "-delete" }
        val execCommand = findExecCommand(atom.args)
        val execIsDestructive = execCommand != null && execCommand in FIND_EXEC_DESTRUCTIVE
        if (!hasDelete && !execIsDestructive) return
        val start = atom.operands.firstOrNull() ?: return
        val what = if (hasDelete) "find 递归删除" else "find -exec $execCommand"
        when (PathClassifier.classify(start)) {
            PathClassifier.PathClass.CRITICAL -> findings.add(
                Finding("FIND_DELETE", RiskLevel.CRITICAL, "$what 系统路径: ${start.take(120)}", snippet, line)
            )
            PathClassifier.PathClass.DANGEROUS -> findings.add(
                Finding("FIND_DELETE_DATA", RiskLevel.DANGEROUS, "$what 数据分区: ${start.take(120)}", snippet, line)
            )
            else -> {}
        }
    }

    /**
     * `-exec` / `-execdir` 之后**真正被执行的命令**的 basename；没有则返回 null。纯函数。
     *
     * 需要跳过**多二进制派发器**：`find /system -exec busybox rm {} +` 的第一个非选项
     * token 是 `busybox` 而不是 `rm`，直接取 basename 会把派发器名当成命令名，
     * 于是 `busybox rm` / `toybox rm` 两种形态逃逸（守卫侧 FIND 模式同样按 basename
     * 判定，此处必须对齐）。派发器名单与守卫的 `guard/<busybox|toybox>` 一致。
     */
    internal fun findExecCommand(args: List<String>): String? {
        var afterExec = false
        for (arg in args) {
            if (!afterExec) {
                if (arg == "-exec" || arg == "-execdir" || (arg.startsWith("-exec") && arg.length > 5)) {
                    afterExec = true
                }
                continue
            }
            if (arg.startsWith("-")) continue
            val base = arg.substringAfterLast('/')
            if (base in MULTI_BINARY_DISPATCHERS) continue
            return base
        }
        return null
    }

    /** 多二进制派发器：自身不是破坏命令，其后第一个 token 才是被执行的命令。 */
    private val MULTI_BINARY_DISPATCHERS = setOf("busybox", "toybox", "magisk", "nobox", "yash")

    /**
     * `find -exec/-execdir` 委托执行的破坏性命令（按 basename 匹配，覆盖
     * `/system/bin/rm`、`busybox rm`、`toybox rm` 等形态）。
     */
    private val FIND_EXEC_DESTRUCTIVE = setOf(
        "rm", "rmdir", "shred", "unlink", "truncate", "wipe", "dd", "mkfs",
        "mv", "chmod", "chown", "chgrp", "mknod", "ln", "tee", "install", "cp"
    )

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
            // 段内可能不止一个原子（`a && b | sh`），逐个判上游：
            // 原先只取 firstOrNull()，段内第二个原子正好是 curl/base64 时会被漏掉。
            val upstream = bySegment[sorted[idx]].orEmpty()
            val next = bySegment[sorted[idx + 1]]?.firstOrNull() ?: continue
            if (next.program !in SHELL_PROGRAMS) continue
            // 必须回溯到**管道起点**取全部上游，而不是只看紧邻的一段。
            // 只比相邻段时，`curl -fsSL url | cat | sh` 的两对相邻关系是
            // (curl→cat) 与 (cat→sh)：第一对的 next 不是 shell 而跳过，
            // 第二对的上游只有 cat（既非下载器也非解码器）→ 零 finding，
            // 远程载荷以 root 直接执行。只加一个中间段 `cat` 就击穿唯一防线。
            val pipelineUpstream = sorted.take(idx + 1).flatMap { bySegment[it].orEmpty() }
            val candidates = if (pipelineUpstream.size > upstream.size) pipelineUpstream else upstream
            for (cur in candidates) {
                when {
                    cur.program in FETCHERS -> findings.add(
                        Finding(
                            "REMOTE_PIPE_SHELL", obfuscationLevel(source),
                            "远程内容直接执行（${cur.program} | … | ${next.program}）", cur.raw.take(200), line
                        )
                    )
                    cur.program in DECODERS -> findings.add(
                        Finding(
                            "ENCODED_PIPE_SHELL",
                            obfuscationLevel(source),
                            "编码/压缩内容直接交给 shell 执行（${cur.program} | … | ${next.program}），常见于加密混淆脚本",
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

            // 内联代码开关：`-c` / `-e` / `-r` / `-m` / `--command` 及 `--command=`。
            // 这类参数后面的字符串**本身就是要在运行时执行的代码**，无法静态展开。
            //
            // 原实现只在 `containsDecoderMarker` 命中时才报 finding，而解码标记
            // （base64 / atob / xxd -r / openssl enc / \x / codecs.decode）只是
            // 混淆执行的**一种**形态。于是最直白的载荷一个标记都不含、零 finding：
            //   python3 -c "import shutil; shutil.rmtree('/system')"
            //   perl -e 'system("dd if=/dev/zero of=/dev/block/by-name/boot")'
            //   node -e "fs.rmSync('/system',{recursive:true})"
            // `evaluateAtom` 的 when 没有解释器分支 → Verdict.Allow，
            // `blocksUnattendedExecution` 为 false → 档位 2 自动执行，root 身份、
            // 无弹窗、无审计记录。
            //
            // 而守卫侧 `guard/` 下**没有** python/perl/node 包装器（已实测），
            // 顶层运行时不具备兜底能力 → 静态层是唯一防线，故按 fail-closed 上报。
            val inlineCode = a.args.any { isInterpreterInlineSwitch(it) }
            val hasDecoder = containsDecoderMarker(joined)
            if (inlineCode || hasDecoder) {
                findings.add(
                    Finding(
                        // 规则 ID 沿用 INTERPRETER_PAYLOAD：既有下游（审计展示、
                        // 档位收敛、回归测试）都按这个 ID 识别「解释器执行不可静态
                        // 展开的载荷」。新增「内联开关」这一触发条件而不是新造 ID，
                        // 避免同一个语义被拆成两个规则 ID 导致下游漏判。
                        "INTERPRETER_PAYLOAD", obfuscationLevel(source),
                        if (inlineCode) {
                            "解释器内联执行代码（${a.program} ${a.args.firstOrNull { isInterpreterInlineSwitch(it) }} …），" +
                                "该字符串会在运行时直接执行且无法静态展开"
                        } else {
                            "解释器内联执行解码后的载荷（${a.program} -c/-e …），属混淆执行"
                        },
                        a.raw.take(200), line
                    )
                )
            }
        }
    }

    /**
     * 是否是「内联执行代码」型开关。
     *
     * 覆盖短选项 `-c`/`-e`/`-r`/`-m` 与长选项 `--command`/`--eval` 及其 `=` 形态。
     * 单独出现（后面没有再跟参数）同样算命中 —— 参数个数无法静态确定时取保守侧。
     */
    private fun isInterpreterInlineSwitch(arg: String): Boolean = when {
        arg == "-c" || arg == "-e" || arg == "-r" || arg == "-m" -> true
        arg == "--command" || arg == "--eval" || arg == "--execute" -> true
        arg.startsWith("--command=") -> true
        arg.startsWith("--eval=") -> true
        arg.startsWith("--execute=") -> true
        else -> false
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
        val lowered = text.lowercase()
        return lowered.contains("base64") || lowered.contains("b64decode") || lowered.contains("b64encode") ||
            lowered.contains("frombase64") || lowered.contains("atob(") || lowered.contains("unhexlify") ||
            lowered.contains("xxd -r") || lowered.contains("openssl enc") || lowered.contains("\\x") ||
            lowered.contains("bytes.fromhex") || lowered.contains("codecs.decode")
    }

    /**
     * 当前安全档位（读 `AppSettings`）。
     *
     * 未初始化或读取异常时取 [SecurityLevels.OFF]（无防护），与 `AppSettings` 的默认值一致 ——
     * 此前注释写的是「保守取 STANDARD」而实现返回 OFF，属于注释与实现相反的误导性文档。
     *
     * 取 OFF 是**产品决策**而非疏漏：新装用户默认不擅自开启命令拦截与守卫安装，
     * 防护由用户在设置页主动开启。因此这里的「异常路径」不能用更高档位兜底，
     * 否则 AppSettings 尚未初始化的那几秒内会静默进入 STANDARD。
     */
    fun currentLevel(): Int = try {
        RootService.appSettings?.securityLevel ?: SecurityLevels.OFF
    } catch (_: Exception) {
        SecurityLevels.OFF
    }
}
