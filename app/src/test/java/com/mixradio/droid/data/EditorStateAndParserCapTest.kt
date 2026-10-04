// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器「保存目标跨重建丢失」与「跨线程读编辑器内容」的回归护栏。
 */
class EditorStateAcrossRebuildTest {

    private val dialog = File("src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt")

    @Test fun `保存目标必须跨配置变更保留`() {
        // 生命周期不一致导致的状态自相矛盾：
        //   dirty / currentCharset / currentLineEnding / hasBom 用 rememberSaveable，
        //   而 currentFilePath 用 remember。
        //
        // 触发：打开 a.txt → 另存为 b.txt（currentFilePath 改为 b.txt）→ 在 b.txt 上继续编辑
        //      → 旋转 → 再编辑并保存。
        // 结果：重建后 currentFilePath 回到 initialFilePath（a.txt），
        //      写的是 a.txt → b.txt 停在旧内容、a.txt 被覆盖，**两个文件同时受损**。
        //
        // 该文件原有注释本就写着「编码 / 换行 / BOM 与文件路径、未保存标记一起用
        // rememberSaveable」，实现与承诺不一致。
        val s = dialog.readText()
        assertTrue(
            "currentFilePath 必须用 rememberSaveable",
            s.contains("var currentFilePath by rememberSaveable")
        )
        assertTrue(
            "不得回退为 remember（会与同组的 saveable 状态生命周期不一致）",
            !s.contains("var currentFilePath by remember {")
        )
    }

    @Test fun `不得在 IO 线程读取编辑器内容`() {
        // Sora 的 Content 只有 new Content(seq, threadSafe) 才线程安全，
        // 而 CodeEditor 用单参构造（lock = null、lines 为普通 ArrayList）。
        // 在 IO 线程 while 主线程正在 insert/delete 时遍历 lines，
        // 可读到扩容中的空洞（null → NPE）、错位下标（AIOOBE）或半截内容；
        // 所在协程无 try/catch → 异常直达崩溃。
        // 即便不崩，撕裂的内容会被写进历史，用户日后「恢复」它就换成乱码。
        val lines = dialog.readText().split('\n')
        val offenders = ArrayList<String>()
        lines.forEachIndexed { i, raw ->
            if (!raw.contains("soraEditor.text()")) return@forEachIndexed
            // 向上找最近的 withContext(Dispatchers.IO) / scope.launch，判断是否落在 IO 块内
            var depth = 0
            var j = i - 1
            var inIo = false
            while (j >= 0 && depth >= 0 && i - j < 40) {
                val l = lines[j]
                if (l.contains("}")) depth++
                if (l.contains("{")) depth--
                if (l.contains("withContext(Dispatchers.IO)") && depth <= 0) {
                    inIo = true
                    break
                }
                j--
            }
            if (inIo) offenders.add("${i + 1}: ${raw.trim().take(70)}")
        }
        assertTrue("不得在 IO 块内直接读编辑器内容: $offenders", offenders.isEmpty())
    }

    @Test fun `后台快照必须用 runCatching 收口`() {
        // 统计与历史快照都读编辑器内容，异常一律降级为「跳过本次」，
        // 绝不让异常逃出 LaunchedEffect（其 scope 无 CoroutineExceptionHandler）。
        val s = dialog.readText()
        val guards = Regex("runCatching\\s*\\{\\s*soraEditor\\.text\\(\\)\\s*\\}").findAll(s).count()
        assertTrue("读编辑器内容处必须 runCatching 收口，实际 $guards 处", guards >= 3)
    }
}

/**
 * 终端解析结果的规模必须有界。
 *
 * 滑窗（`HyperCore.MAX_LOG_LENGTH`）只约束**字符数**，裁剪点按 `\n` 对齐；
 * 对「全空行」输出等价于「一个字符 = 一行」—— 250k 字符即 250k 行。
 * 每行还要新建 `AnnotatedString` + 空 `ArrayList` + `RangeList`（`buildCurrentLine`），
 * 约 4 对象/行；滑窗满后每次 flush 都走 `reset()` + `feed(整段)` 全量重解析，
 * 单次 10~25MB、每秒 4 次，叠加新旧两份快照峰值 40~50MB → 掉帧 + GC Major + OOM。
 */
class AnsiParserLineCapTest {

    private val parser = File("src/main/main/java/com/mixradio/droid/data/AnsiParser.kt")
        .takeIf { it.exists() }
        ?: File("src/main/java/com/mixradio/droid/data/AnsiParser.kt")

    @Test fun `已完成行必须有硬上限`() {
        val s = parser.readText()
        assertTrue("必须定义行数上限", s.contains("MAX_COMPLETED_LINES"))
        assertTrue(
            "endLine 必须在超限时丢弃头部行",
            Regex("completed\\.size\\s*>\\s*MAX_COMPLETED_LINES").containsMatchIn(s)
        )
    }

    @Test fun `丢弃行必须一次性移除而非逐个搬移`() {
        // removeAt(0) 是 O(n) 搬移，在每行都触发时退化成 O(n²)。
        val s = parser.readText()
        // 定位「超限分支」而不是常量声明：声明处也含 MAX_COMPLETED_LINES。
        val idx = s.indexOf("completed.size > MAX_COMPLETED_LINES")
        assertTrue("应能找到超限分支", idx > 0)
        val branch = s.substring(idx, idx + 400)
        assertTrue(
            "必须用 subList().clear() 一次性移除",
            branch.contains("subList(0, excess).clear()")
        )
        // 只检查代码行：注释里为了说明「为何不用 removeAt(0)」会引用它。
        val codeOnly = branch.split('\n')
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "代码中不得逐个 removeAt(0)",
            !codeOnly.contains("removeAt(0)")
        )
    }

    @Test fun `省略行数必须被记录以供如实告知`() {
        // 丢弃多少行要让 UI 能说出来，否则用户看到「输出凭空少了一截」却无解释。
        val s = parser.readText()
        assertTrue("必须累计被丢弃的行数", s.contains("droppedLines"))
        val resetIdx = s.indexOf("fun reset()")
        val resetSeg = s.substring(resetIdx, resetIdx + 300)
        assertTrue(
            "reset 必须清零计数",
            resetSeg.contains("droppedLines = 0")
        )
    }

    @Test fun `上限取值必须显著小于滑窗字符上限对应的最大行数`() {
        val s = parser.readText()
        val m = Regex("MAX_COMPLETED_LINES\\s*=\\s*(\\d+)").find(s)
        assertTrue("应能解析出上限取值", m != null)
        val cap = m!!.groupValues[1].toInt()
        // HyperCore.MAX_LOG_LENGTH = 250_000 字符；若上限 >= 该值则等于没设限
        assertTrue("行数上限必须显著小于 250000，当前 $cap", cap in 1..20000)
    }
}
