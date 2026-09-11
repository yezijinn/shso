// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * ANSI 解析结果。
 * @param lines 按行切分的渲染结果，每行一个 [AnnotatedString]（不含行尾 '\n'）；
 *   空行对应空 [AnnotatedString]，与 `split('\n')` 语义一致（"a\n" → ["a", ""]）。
 */
class ParsedAnsiResult(
    val lines: List<AnnotatedString>
) {
    /**
     * 已剥离转义序列、CR 已处理的纯文本（与历史语义一致）。
     * 惰性求值：它只在「复制输出」时被读取，若每次 flush 都拼接整段（250k）会造成大量
     * 重复分配，故改为按需计算并缓存。
     */
    val plainText: String by lazy { lines.joinToString("\n") { it.text } }
}

/** 仅设置 color / fontWeight 两个字段，故比较只认这两项（对象身份不可靠）。 */
private fun sameStyle(a: SpanStyle?, b: SpanStyle?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    return a.color == b.color && a.fontWeight == b.fontWeight
}

private val COLOR_MAP = mapOf(
    30 to Color(0xFF4E4E4E),
    31 to Color(0xFFFF5252),
    32 to Color(0xFF4CAF50),
    33 to Color(0xFFFFD54F),
    34 to Color(0xFF448AFF),
    35 to Color(0xFFE040FB),
    36 to Color(0xFF18FFFF),
    37 to Color(0xFFEEEEEE),
    90 to Color(0xFF757575),
    91 to Color(0xFFFF8A80),
    92 to Color(0xFFB9F6CA),
    93 to Color(0xFFFFFF8D),
    94 to Color(0xFF82B1FF),
    95 to Color(0xFFEA80FC),
    96 to Color(0xFF84FFFF),
    97 to Color(0xFFFFFFFF)
)

/**
 * xterm 256 色调色板（CLUT）到颜色：
 * 0-15 映射 ANSI 基础色，16-231 为 6×6×6 色块，232-255 为 24 阶灰度。
 */
private fun ansi256ToColor(index: Int): Color {
    return when (index) {
        in 0..7 -> COLOR_MAP.getValue(30 + index)
        in 8..15 -> COLOR_MAP.getValue(90 + index - 8)
        in 16..231 -> {
            val v = index - 16
            val component = { x: Int -> if (x == 0) 0 else 55 + x * 40 }
            Color(
                component(v / 36),
                component((v / 6) % 6),
                component(v % 6)
            )
        }
        else -> {
            val gray = 8 + (index - 232) * 10
            Color(gray.coerceIn(0, 255), gray.coerceIn(0, 255), gray.coerceIn(0, 255))
        }
    }
}

object AnsiParser {

    /**
     * 全量解析（兼容入口）。内部即「新建增量解析器 + 一次喂完 + 收尾」。
     * 供测试、横幅等一次性场景使用；终端洪流请复用 [IncrementalAnsiParser] 只喂增量。
     */
    fun parseAnsi(raw: String, defaultColor: Color): ParsedAnsiResult =
        IncrementalAnsiParser(defaultColor).apply {
            feed(raw)
            finish()
        }.snapshot()
}

/**
 * 增量 ANSI 解析器：把终端输出当作**字符流**逐块喂入，跨块维护
 *   - 已完成的行列表；
 *   - 当前未完成行（文本 + 逐列样式 + 光标列）；
 *   - SGR 状态（前景色 / 粗体）；
 *   - 被块边界截断的转义序列缓冲。
 *
 * 这样做同时解决三件事：
 * 1. **增量**：每次 flush 只解析新增尾部，不再全量重扫 250k 窗口；
 * 2. **`\r` 原地覆盖**：光标列是解析器的固有状态，天然支持真实终端的
 *    「回到行首、原地覆盖」——进度条 `10%\r20%\r30%` 只占 1 行且最终显示 `30%`，
 *    不再堆叠成 3 行（堆叠会让日志体积与后续解析成本随刷新次数线性增长）；
 * 3. **跨块安全**：ESC 序列被 flush 边界截断时会缓冲到下一块，不会解析出错。
 *
 * 语义约定：
 * - `\n` 结束当前行；`\r\n` 同样是换行（`\r` 先把光标归零，随后 `\n` 收行）；
 * - 孤立 `\r` = 光标回到第 0 列，**后续输出从行首覆盖**（不做换行）；
 * - 覆盖是**按字符**的：`abcdef\rXY` → `XYcdef`（与真实终端一致，不是整行清空）。
 */
class IncrementalAnsiParser(private val defaultColor: Color) {

    private val completed = ArrayList<AnnotatedString>()

    private var curText = StringBuilder()
    private var curStyles = ArrayList<AnnotatedString.Range<SpanStyle>>()
    /** 覆盖模式（本行出现过 `\r`）下按列记录样式；null = 仍是纯追加，用区间更快。 */
    private var curCols: ArrayList<SpanStyle?>? = null
    /** 光标列。等于 curText.length 时后续写入走「快路径追加」。 */
    private var curCol = 0

    private var currentColor = defaultColor
    private var isBold = false

    /** 被块边界截断、尚未凑齐的转义序列（通常 < 32 字符）。 */
    private var pendingEscape: String? = null

    private fun defaultSpan(): SpanStyle =
        SpanStyle(color = defaultColor, fontWeight = FontWeight.Normal)

    private fun currentSpan(): SpanStyle =
        SpanStyle(color = currentColor, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal)

    fun reset() {
        completed.clear()
        curText = StringBuilder()
        curStyles = ArrayList()
        curCols = null
        curCol = 0
        currentColor = defaultColor
        isBold = false
        pendingEscape = null
    }

    /**
     * 喂入一块新增输出。可跨多次调用；调用方只传**增量**即可。
     */
    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        val pending = pendingEscape
        val input = if (pending != null) {
            pendingEscape = null
            pending + chunk
        } else {
            chunk
        }
        feedInternal(input)
    }

    /**
     * 收尾：把末尾残缺的转义序列按普通文本输出（旧正则不匹配时也是当文本处理）。
     * 只有**确定不会再有后续输入**时才调用（如 [AnsiParser.parseAnsi]）；
     * 持续运行的终端不应调用——真实终端同样会等待序列补全。
     */
    fun finish() {
        val pending = pendingEscape ?: return
        pendingEscape = null
        writeSegment(pending, 0, pending.length)
    }

    /** 取当前快照（已完成行 + 当前未完成行）。不修改解析状态，可重复调用。 */
    fun snapshot(): ParsedAnsiResult {
        // 完全没内容时返回 0 行（与 split('\n') 对 "" 的结果一致），
        // 避免「空终端」渲染出一条多余的空行。
        if (completed.isEmpty() && curText.isEmpty()) return ParsedAnsiResult(emptyList())
        val all = ArrayList<AnnotatedString>(completed.size + 1)
        all.addAll(completed)
        all.add(buildCurrentLine())
        return ParsedAnsiResult(all)
    }

    // ────────────────────────────── 扫描 ──────────────────────────────

    private fun feedInternal(input: String) {
        val n = input.length
        var i = 0
        var textStart = 0
        while (i < n) {
            when (input[i]) {
                '\n' -> {
                    if (i > textStart) writeSegment(input, textStart, i)
                    endLine()
                    textStart = i + 1
                    i++
                }
                '\r' -> {
                    if (i > textStart) writeSegment(input, textStart, i)
                    carriageReturn()
                    textStart = i + 1
                    i++
                }
                '\u001B' -> {
                    if (i > textStart) writeSegment(input, textStart, i)
                    val end = escapeEnd(input, i)
                    if (end < 0) {
                        // 序列被块边界截断：缓冲，等下一块补齐
                        pendingEscape = input.substring(i)
                        return
                    }
                    if (end > i + 1 && input[i + 1] == '[') {
                        applySgr(input.substring(i + 2, end - 1))
                    } else {
                        // 非 CSI（ESC 后不是 `[`）或畸形序列：ESC 本身按普通文本输出，
                        // 与旧实现「正则不匹配即原文保留」一致
                        writeSegment(input, i, i + 1)
                    }
                    textStart = end
                    i = end
                }
                else -> i++
            }
        }
        if (textStart < n) writeSegment(input, textStart, n)
    }

    /**
     * 从 ESC 处探测 CSI 序列结束位置（不含）。
     * @return 结束下标；返回负数 = 序列被输入末尾截断，需要更多字符。
     */
    private fun escapeEnd(input: String, start: Int): Int {
        if (start + 1 >= input.length) return -1
        if (input[start + 1] != '[') return start + 1
        var j = start + 2
        while (j < input.length) {
            val ch = input[j]
            when {
                ch in '0'..'9' || ch == ';' -> j++
                ch in 'a'..'z' || ch in 'A'..'Z' -> return j + 1
                else -> return start + 1 // 出现非法字符：不是合法 CSI
            }
        }
        return -1
    }

    // ────────────────────────── 当前行写入 ──────────────────────────

    private fun writeSegment(s: String, from: Int, to: Int) {
        if (from >= to) return
        val style = currentSpan()
        val colsSnapshot = curCols
        if (colsSnapshot == null && curCol == curText.length) {
            // 快路径：纯追加（绝大多数行）。整段一次 append + 记一个区间。
            val start = curText.length
            curText.append(s, from, to)
            val end = curText.length
            if (end > start) {
                // 合并相邻的同样式区间。
                // 增量喂入时一次 feed 就是一个区间，若不合并，一行会被切成上千个
                // 1 字符区间（文本正确，但 AnnotatedString 构建与渲染都变慢）。
                val last = curStyles.lastOrNull()
                if (last != null && last.end == start && sameStyle(last.item, style)) {
                    curStyles[curStyles.lastIndex] = AnnotatedString.Range(style, last.start, end)
                } else {
                    curStyles.add(AnnotatedString.Range(style, start, end))
                }
            }
            curCol = end
            return
        }
        // 覆盖模式：逐字符按列写（真实终端语义）
        val cols = colsSnapshot ?: materializeCols()
        var k = from
        while (k < to) {
            if (curCol < curText.length) {
                curText.setCharAt(curCol, s[k])
                cols[curCol] = style
            } else {
                curText.append(s[k])
                cols.add(style)
            }
            curCol++
            k++
        }
    }

    private fun carriageReturn() {
        if (curCol == 0) return // 已在行首，无需进入覆盖模式
        if (curCols == null) materializeCols()
        curCol = 0
    }

    /** 把「区间式样式」展开为「逐列样式」，供覆盖写使用。 */
    private fun materializeCols(): ArrayList<SpanStyle?> {
        val arr = ArrayList<SpanStyle?>(curText.length)
        var i = 0
        while (i < curText.length) {
            arr.add(null)
            i++
        }
        for (r in curStyles) {
            var k = r.start
            while (k < r.end && k < arr.size) {
                arr[k] = r.item
                k++
            }
        }
        curCols = arr
        return arr
    }

    private fun endLine() {
        completed.add(buildCurrentLine())
        curText = StringBuilder()
        curStyles = ArrayList()
        curCols = null
        curCol = 0
    }

    private fun buildCurrentLine(): AnnotatedString {
        val cols = curCols
        val text = curText
        val str = text.toString()
        if (cols == null) {
            return AnnotatedString(str, spanStyles = curStyles)
        }
        // 逐列压缩为区间
        val def = defaultSpan()
        val ranges = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var i = 0
        while (i < str.length) {
            val st = cols.getOrNull(i) ?: def
            var j = i + 1
            while (j < str.length && sameStyle(cols.getOrNull(j) ?: def, st)) j++
            ranges.add(AnnotatedString.Range(st, i, j))
            i = j
        }
        return AnnotatedString(str, spanStyles = ranges)
    }

    // ────────────────────────────── SGR ──────────────────────────────

    private fun applySgr(codeStr: String) {
        val codes = codeStr.split(";").mapNotNull { it.toIntOrNull() }
        if (codes.isEmpty() || codes.contains(0)) {
            currentColor = defaultColor
            isBold = false
            return
        }
        // 索引遍历：38;5;n（256 色）与 38;2;r;g;b（真彩色）为可变长度参数，
        // 解析后跳过其参数，避免把 5/2 或颜色分量误当独立 SGR 码处理
        var i = 0
        while (i < codes.size) {
            val code = codes[i]
            when {
                code == 1 -> isBold = true
                code == 22 -> isBold = false
                code == 39 -> currentColor = defaultColor
                code in 30..37 || code in 90..97 -> {
                    currentColor = COLOR_MAP[code] ?: defaultColor
                }
                // 前景扩展色：256 色（38;5;n）与真彩色（38;2;r;g;b）
                code == 38 && i + 1 < codes.size -> {
                    when (codes[i + 1]) {
                        5 -> if (i + 2 < codes.size) {
                            currentColor = ansi256ToColor(codes[i + 2])
                            i += 2
                        }
                        2 -> if (i + 4 < codes.size) {
                            currentColor = Color(
                                codes[i + 2].coerceIn(0, 255),
                                codes[i + 3].coerceIn(0, 255),
                                codes[i + 4].coerceIn(0, 255)
                            )
                            i += 4
                        }
                    }
                }
                // 背景色（48/34 等）、下划线等未识别码保持忽略，与历史行为一致
            }
            i++
        }
    }
}
