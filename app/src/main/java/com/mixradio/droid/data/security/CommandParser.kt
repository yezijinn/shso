// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

/**
 * 命令解析器（方案 §4）：词法切分 → 拆原子命令 → 递归展开命令替换。
 *
 * 不能用整串正则匹配——`rm -rf /` 藏在 `$(...)`、反引号或 `sh -c '...'` 里就漏了。
 * 本解析器把命令拆成原子命令列表，供 [PolicyEngine] 逐条判定。
 *
 * 处理能力：
 * - 引号（'...' 全字面 / "..." / \ 转义）；
 * - 分隔符 `;` `&&` `||` `|` `&` 换行；
 * - 递归展开 `$(...)`、反引号、`sh -c '...'`（内层命令同样产生原子）；
 * - 前缀剥离：`/system/bin/toybox rm`、`busybox rm`、`env VAR=x rm`、`nohup rm` → `rm`；
 * - 注释：引号外的 `#` 到行尾（保留 `#!`）。
 *
 * 防御：token 数 / 原子数 / 递归深度超限 → truncated = true（策略层 fail-closed）。
 */
object CommandParser {

    /** 一条原子命令：程序名 + 参数 + 操作数（非 flag 参数，视作路径）+ 所在段序号。 */
    data class Atom(
        /** 程序名 basename，已剥 busybox/toybox/env/nohup 等前缀 */
        val program: String,
        val args: List<String>,
        /** 非 flag 参数（潜在路径操作数） */
        val operands: List<String>,
        val raw: String,
        /** 是否来自 $() / 反引号 / sh -c 的内层 */
        val nested: Boolean,
        /** 段序号（同一管道内的相邻段序号连续，用于 curl|sh 等管道检测） */
        val segmentId: Int
    )

    data class Parsed(
        val atoms: List<Atom>,
        /** 超限 / 解析异常：策略层必须按高危处理（fail-closed） */
        val truncated: Boolean
    )

    private const val MAX_LEN = 32 * 1024
    private const val MAX_TOKENS = 400
    private const val MAX_ATOMS = 128
    private const val MAX_DEPTH = 6

    private class Overflow : Exception()

    private val WRAPPER_PREFIXES = setOf("busybox", "toybox", "magisk", "nohup", "timeout", "stdbuf", "sudo")
    private val SHELL_PROGRAMS = setOf("sh", "bash", "ash", "dash", "mksh")

    /** 解析主入口。任何内部异常都归一为 truncated=true，绝不抛出。 */
    fun parse(command: String): Parsed {
        if (command.length > MAX_LEN) return Parsed(emptyList(), truncated = true)
        val atoms = ArrayList<Atom>()
        val state = ScanState()
        try {
            walk(command, depth = 0, atoms = atoms, state = state, nested = false)
            emitAtomIfAny(state, atoms, depth = 0)
        } catch (_: Overflow) {
            state.truncated = true
        } catch (_: Exception) {
            // 任何解析异常：按 fail-closed 处理
            state.truncated = true
        }
        return Parsed(atoms, truncated = state.truncated)
    }

    /** 扫描状态：当前段 token 累积 + 全局计数 + 本层原子是否来自内层替换。 */
    private class ScanState(val nested: Boolean = false) {
        var tokens: MutableList<String> = ArrayList()
        var segmentId = 0
        var tokenCount = 0
        var truncated = false

        fun newSegment() {
            segmentId++
            tokens = ArrayList()
        }
    }

    /** 扫描一段文本：按分隔符切段；遇到 $(...) / 反引号时递归解析内层。 */
    private fun walk(text: String, depth: Int, atoms: ArrayList<Atom>, state: ScanState, nested: Boolean) {
        if (depth > MAX_DEPTH) throw Overflow()
        var i = 0
        val n = text.length
        val current = StringBuilder()

        fun endToken() {
            if (current.isNotEmpty()) {
                if (++state.tokenCount > MAX_TOKENS) {
                    state.truncated = true
                    throw Overflow()
                }
                state.tokens.add(current.toString())
                current.setLength(0)
            }
        }

        fun endSegment() {
            endToken()
            emitAtomIfAny(state, atoms, depth)
            state.newSegment()
        }

        while (i < n) {
            val c = text[i]
            when {
                // 单引号：全字面
                c == '\'' -> {
                    val close = text.indexOf('\'', i + 1)
                    if (close == -1) { current.append(text, i + 1, n); i = n } else {
                        current.append(text, i + 1, close); i = close + 1
                    }
                }
                // 双引号：内容保留，但内层命令替换仍需递归
                c == '"' -> {
                    var j = i + 1
                    var closed = false
                    while (j < n) {
                        val d = text[j]
                        when {
                            d == '\\' && j + 1 < n -> { current.append(text[j + 1]); j += 2 }
                            d == '$' && j + 1 < n && text[j + 1] == '(' -> {
                                val inner = extractParen(text, j + 1)
                                if (inner.first != null) expandSubstitution(inner.first!!, depth, atoms, state)
                                j = inner.second
                            }
                            d == '`' -> {
                                val close = text.indexOf('`', j + 1)
                                if (close == -1) { j = n } else {
                                    expandSubstitution(text.substring(j + 1, close), depth, atoms, state)
                                    j = close + 1
                                }
                            }
                            d == '"' -> { closed = true; j++ }
                            else -> { current.append(d); j++ }
                        }
                    }
                    i = if (closed) j else n
                }
                // 反斜杠转义
                c == '\\' && i + 1 < n -> { current.append(text[i + 1]); i += 2 }
                // 命令替换 $(...)（$((...)) 算术：不透明跳过，不产生可执行原子）
                c == '$' && i + 1 < n && text[i + 1] == '(' -> {
                    val inner = extractParen(text, i + 1)
                    if (inner.first != null) expandSubstitution(inner.first!!, depth, atoms, state)
                    i = inner.second
                }
                // 反引号替换
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close == -1) { i = n } else {
                        expandSubstitution(text.substring(i + 1, close), depth, atoms, state)
                        i = close + 1
                    }
                }
                // 分隔符：; && || | |& & 换行
                c == ';' || c == '|' || c == '&' || c == '\n' -> {
                    if ((c == '|' || c == '&') && i + 1 < n && text[i + 1] == c) i++
                    endSegment()
                    i++
                }
                // 行注释：词首 # （#! 由 parse 调用方剥离，正常不会出现）
                c == '#' && current.isEmpty() -> {
                    val nl = text.indexOf('\n', i)
                    i = if (nl == -1) n else nl
                }
                c.isWhitespace() -> { endToken(); i++ }
                else -> { current.append(c); i++ }
            }
        }
        endToken()
    }

    /**
     * 提取 "(" 起始的括号内容。
     * @return (内容或 null[表示 $((...)) 算术展开]，右括号后下标)
     */
    private fun extractParen(text: String, openIndex: Int): Pair<String?, Int> {
        if (openIndex + 1 < text.length && text[openIndex + 1] == '(') {
            var depth = 2
            var i = openIndex + 2
            while (i < text.length) {
                if (text[i] == '(') depth++
                else if (text[i] == ')') {
                    depth--
                    if (depth == 0) return Pair(null, i + 1)
                }
                i++
            }
            return Pair(null, text.length)
        }
        var depth = 1
        var i = openIndex + 1
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> {
                    val close = text.indexOf('\'', i + 1)
                    if (close == -1) { sb.append(text, i + 1, text.length); i = text.length } else {
                        sb.append(text, i, close + 1); i = close + 1
                    }
                }
                c == '"' -> {
                    val close = text.indexOf('"', i + 1)
                    if (close == -1) { sb.append(text, i, text.length); i = text.length } else {
                        sb.append(text, i, close + 1); i = close + 1
                    }
                }
                c == '\\' && i + 1 < text.length -> { sb.append(text[i]).append(text[i + 1]); i += 2 }
                c == '(' -> { depth++; sb.append(c); i++ }
                c == ')' -> {
                    depth--
                    if (depth == 0) return Pair(sb.toString(), i + 1)
                    sb.append(c); i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        return Pair(sb.toString(), text.length)
    }

    /** 展开一层命令替换：内层文本用全新段状态递归解析（原子标记 nested=true）。 */
    private fun expandSubstitution(inner: String, depth: Int, atoms: ArrayList<Atom>, state: ScanState) {
        if (inner.isBlank()) return
        if (++state.tokenCount > MAX_TOKENS) {
            state.truncated = true
            throw Overflow()
        }
        val subState = ScanState(nested = true)
        walk(inner, depth + 1, atoms, subState, nested = true)
        emitAtomIfAny(subState, atoms, depth + 1)
    }

    /** 当前段有 token 则构造原子。 */
    private fun emitAtomIfAny(state: ScanState, atoms: ArrayList<Atom>, depth: Int) {
        if (state.tokens.isEmpty()) return
        if (atoms.size >= MAX_ATOMS) {
            state.truncated = true
            throw Overflow()
        }
        buildAtom(state.tokens, state.segmentId, nested = state.nested, atoms = atoms, depth = depth)
    }

    /** 由 token 列表构造原子：剥前缀、识别 sh -c 递归、提取操作数。 */
    private fun buildAtom(tokens: MutableList<String>, segmentId: Int, nested: Boolean, atoms: ArrayList<Atom>, depth: Int) {
        val words = tokens.toList()

        // 1) 程序名 basename + 前缀剥离（busybox/toybox/env/nohup/timeout/stdbuf/sudo）
        var program = words.first().substringAfterLast('/')
        var consumed = 1
        var guardCount = 0
        while (program in WRAPPER_PREFIXES && consumed < words.size && guardCount++ < 8) {
            var next = words[consumed].substringAfterLast('/')
            consumed++
            // env 可能带 VAR=value 前缀参数：跳过赋值形态
            var assignGuard = 0
            while (next.indexOf('=') > 0 && consumed < words.size && assignGuard++ < 16) {
                next = words[consumed].substringAfterLast('/')
                consumed++
            }
            program = next
        }

        val args = words.drop(consumed)
        // 2) 操作数：非 flag 参数（保守视作路径操作数；重定向目标如 /dev/... 也会进入，利于 dd 规则）
        val operands = args.filter { it.isNotEmpty() && !it.startsWith("-") }

        // 3) sh -c '...' / bash -c "..."：内层字符串本身是命令，递归解析（nested=true）
        val cIdx = args.indexOfFirst { it == "-c" }
        if (program in SHELL_PROGRAMS && cIdx >= 0 && cIdx + 1 < args.size) {
            val innerCmd = args[cIdx + 1]
            if (innerCmd.isNotBlank()) {
                val subState = ScanState(nested = true)
                try {
                    walk(innerCmd, depth + 1, atoms, subState, nested = true)
                    emitAtomIfAny(subState, atoms, depth + 1)
                } catch (_: Overflow) {
                    throw Overflow()
                }
            }
        }

        atoms.add(
            Atom(
                program = program,
                args = args,
                operands = operands,
                raw = words.joinToString(" "),
                nested = nested,
                segmentId = segmentId
            )
        )
    }
}
