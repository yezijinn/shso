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
            val value = index - 16
            val component = { x: Int -> if (x == 0) 0 else 55 + x * 40 }
            Color(
                component(value / 36),
                component((value / 6) % 6),
                component(value % 6)
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
 * 增量 ANSI 解析器：把终端输出当作字符流逐块喂入，跨块维护已完成行、当前行
 * （文本 + 逐列样式 + 光标列）、SGR 状态（前景色 / 粗体）与被块边界截断的转义序列缓冲。
 *
 * 只喂增量，避免每次 flush 全量重扫整段窗口；光标列是固有状态，天然支持真实终端的
 * `\r` 原地覆盖（进度条 `10%\r20%\r30%` 只占 1 行且最终显示 `30%`，避免日志随刷新次数膨胀），
 * 以及 ESC 序列被 flush 边界截断时缓冲到下一块（不会解析出错）。
 *
 * 语义不变量：
 * - `\n` 收行；`\r\n` 同换行（`\r` 先归零，`\n` 收行）；
 * - 孤立 `\r` = 光标回第 0 列，**后续输出从行首覆盖**（不做换行）；
 * - 覆盖按字符：`abcdef\rXY` → `XYcdef`，与真实终端一致（非整行清空）。
 */
class IncrementalAnsiParser(private val defaultColor: Color) {

    private companion object {
        /**
         * 单个转义序列的缓冲上限。序列不可能无限长：超限即判定为「不是合法序列」
         * （典型是把二进制文件 cat 到终端，`ESC ]` 之后再无终止符），按普通文本吐出，
         * 避免 pending 无界增长、整段输出被永久吞掉。
         */
        const val MAX_ESCAPE_SEQUENCE = 1024
    }

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

    // 扫描

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
                '\b' -> {
                    if (i > textStart) writeSegment(input, textStart, i)
                    backspace()
                    textStart = i + 1
                    i++
                }
                '\u001B' -> {
                    if (i > textStart) writeSegment(input, textStart, i)
                    val end = escapeEnd(input, i)
                    if (end < 0) {
                        // 序列被块边界截断：缓冲，等下一块补齐。
                        // 但序列不可能无限长——超上限说明这不是合法序列（典型：把二进制文件 cat 到终端，
                        // `ESC ]` 之后再无 BEL）。此时按普通文本吐出，避免 pending 无界增长、整段输出被吞。
                        if (input.length - i > MAX_ESCAPE_SEQUENCE) {
                            writeSegment(input, i, i + 1)
                            textStart = i + 1
                            i++
                            continue
                        }
                        pendingEscape = input.substring(i)
                        return
                    }
                    if (end > i + 1 && input[i + 1] == '[') {
                        when (input[end - 1]) {
                            // SGR：唯一需要应用样式的一类
                            'm' -> applySgr(input.substring(i + 2, end - 1))
                            // 行内擦除：进度条 `\r ESC[K` 清行重绘
                            'K' -> eraseInLine(input.substring(i + 2, end - 1))
                            // 其它 CSI（光标移动 / 清屏 / 光标显隐 / 括号粘贴 / 备用屏幕…）：
                            // 屏幕控制指令，日志不做屏幕模拟，整体吞掉即可。
                        }
                    } else if (end > i + 1) {
                        // 非 CSI 的合法 ESC 序列（OSC / 字符集切换 / 两字符序列）：同样吞掉。
                    } else {
                        // 畸形序列（ESC 后跟控制字符）：ESC 按普通文本输出。
                        writeSegment(input, i, i + 1)
                    }
                    textStart = end
                    i = end
                }
                else -> {
                    val ch = input[i]
                    // 其余 C0 控制字符（NUL / BEL / VT / FF / SO…）与 DEL：真实终端忽略，
                    // 不能落进文本（否则会被复制到剪贴板、也占一行宽度）。制表符要保留。
                    if (ch != '\t' && (ch < ' ' || ch == '\u007F')) {
                        if (i > textStart) writeSegment(input, textStart, i)
                        textStart = i + 1
                    }
                    i++
                }
            }
        }
        if (textStart < n) writeSegment(input, textStart, n)
    }

    /**
     * 从 ESC 处探测转义序列结束位置（不含）。三类都整体吞掉（日志不做屏幕模拟）：
     *
     * - **CSI**（`ESC [`）：参数 `0x30-0x3F`（含 `?` `<` `=` `>` 私有前缀）→ 中间字节 `0x20-0x2F` → 最终字节 `0x40-0x7E`。
     *   只认 `[0-9;]` 参数会让 `ESC[?25l`（隐藏光标）判定失败，ESC 之后的 `[?25l` 就落到普通文本路径，终端里出现乱码。
     * - **OSC 等字符串序列**（`ESC ]`）：到 BEL(`0x07`) 或 ST(`ESC \`) 结束。窗口标题与 OSC 8 超链接都走它，
     *   不识别会在日志里留下 `]0;标题` / `]8;;https://…` 这种尾巴。
     * - **其它 ESC 序列**：`ESC` + 中间字节（可再接最终字节，如 `ESC(B`）/ `ESC` + 单字节（`ESC=` `ESC>` `ESC7` `ESCc`…）。
     *
     * @return 结束下标；返回负数 = 序列被输入末尾截断，需要更多字符。
     */
    private fun escapeEnd(input: String, start: Int): Int {
        if (start + 1 >= input.length) return -1
        return when (input[start + 1]) {
            '[' -> csiEnd(input, start)
            ']' -> stringSequenceEnd(input, start)
            in '\u0020'..'\u002F' -> {
                // ESC + 中间字节（可再接一个最终字节）
                val next = start + 2
                if (next >= input.length) -1
                else if (input[next] in '\u0030'..'\u007E') next + 1
                else next
            }
            in '\u0030'..'\u007E' -> start + 2 // ESC + 单字节
            else -> start + 1                  // 畸形：ESC 当普通文本处理
        }
    }

    /** CSI：参数 → 中间 → 最终字节。返回负数 = 被截断。 */
    private fun csiEnd(input: String, start: Int): Int {
        var j = start + 2
        while (j < input.length && input[j] in '\u0030'..'\u003F') j++
        while (j < input.length && input[j] in '\u0020'..'\u002F') j++
        if (j >= input.length) return -1
        return if (input[j] in '\u0040'..'\u007E') j + 1 else start + 1
    }

    /** OSC / DCS 等字符串序列：到 BEL(`0x07`) 或 ST(`ESC \`) 结束。返回负数 = 被截断。 */
    private fun stringSequenceEnd(input: String, start: Int): Int {
        var j = start + 2
        while (j < input.length) {
            when (input[j]) {
                '\u0007' -> return j + 1
                '\u001B' -> return if (j + 1 < input.length) (if (input[j + 1] == '\\') j + 2 else j + 1) else -1
                else -> j++
            }
        }
        return -1
    }

    // 当前行写入

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

    /**
     * 退格：光标左移一列。**不删字符**（与真实终端一致）——后续输出会覆盖该列。
     * 已在行首则不动（真实终端在行首退格也不回绕到上一行）。
     */
    private fun backspace() {
        if (curCol <= 0) return
        if (curCols == null) materializeCols()
        curCol--
    }

    /**
     * `ESC[K` 行内擦除：`0`/省略 = 从光标擦到行尾；`2` = 整行清空（光标回到行首）。
     * `1`（行首擦到光标）在日志里极少见且需要填空格，忽略。
     *
     * 与 `\r` 同理，这是「按列」的语义，必须同时裁掉样式记录，否则擦除后的新文本
     * 会继承被擦掉区间的样式。`2K` 按「清空 + 光标回行首」简化（真实终端保留光标列）。
     */
    private fun eraseInLine(params: String) {
        when (params.toIntOrNull() ?: 0) {
            0 -> {
                if (curCol >= curText.length) return
                curText.setLength(curCol)
                val cols = curCols
                if (cols != null) {
                    while (cols.size > curCol) cols.removeAt(cols.size - 1)
                } else {
                    curStyles.removeAll { it.start >= curCol }
                    for (k in curStyles.indices) {
                        val r = curStyles[k]
                        if (r.end > curCol) curStyles[k] = AnnotatedString.Range(r.item, r.start, curCol)
                    }
                }
            }
            2 -> {
                curText = StringBuilder()
                curStyles = ArrayList()
                curCols = null
                curCol = 0
            }
        }
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

    // SGR

    private fun applySgr(codeStr: String) {
        val codes = codeStr.split(";").mapNotNull { it.toIntOrNull() }
        // 空参数（"\e[m"）等价于单个 0（reset）。
        if (codes.isEmpty()) {
            currentColor = defaultColor
            isBold = false
            return
        }
        // 索引遍历：38;5;n（256 色）与 38;2;r;g;b（真彩色）为可变长度参数，
        // 解析后跳过其参数，避免把 5/2 或颜色分量误当独立 SGR 码处理。
        //
        // 0 必须作为循环内的一条指令就地处理，不能 `codes.contains(0)` 后直接 reset return：
        // `\e[0;32m`（先复位再设绿，CLI 常见输出）会丢掉 32，文本被渲染成默认色。
        var i = 0
        while (i < codes.size) {
            val code = codes[i]
            when {
                code == 0 -> { currentColor = defaultColor; isBold = false }
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
