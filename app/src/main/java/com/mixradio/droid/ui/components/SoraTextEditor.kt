// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import com.mixradio.droid.ui.theme.AuroraTokens
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

/**
 * Sora Editor 承载（MP-Manager 同款引擎，LGPL-2.1）。
 *
 * 编辑器文本只存在于 [CodeEditor] 内部（`Content` 为行索引增量结构），**不再经 Compose State 往返整串**：
 * 每次按键若把整篇文本拷进 `TextFieldValue`，4MB 文本就是每次 O(n) 拷贝 —— 这是旧实现慢的根因之一。
 * 因此本组件只在 [resetKey] 变化时 `setText`，编辑内容由 [controller] 按需读取。
 *
 * 与旧实现的差别：单一控件、打开即可编辑（无「只读/编辑」通道切换与双渲染通道阈值）。
 */
class SoraEditorController {
    internal var editor: CodeEditor? = null

    /** 当前全文（按需调用；大文本为 O(n) 一次遍历，勿逐键调用）。 */
    fun text(): String = editor?.text?.toString() ?: ""

    /** 字符数（O(1)，取自 Sora 的 Content 长度）。 */
    fun charCount(): Int = editor?.text?.length ?: 0

    /** 行数（O(1)，取自 Sora 的行索引）。 */
    fun lineCount(): Int = editor?.text?.lineCount ?: 0

    /** 检索（查找/替换共用）。空串表示清除检索。 */
    fun search(query: String, caseInsensitive: Boolean) {
        val ed = editor ?: return
        if (query.isEmpty()) { ed.searcher.stopSearch(); return }
        val options = io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(caseInsensitive, false)
        ed.searcher.search(query, options)
    }

    fun gotoNextMatch() { editor?.searcher?.gotoNext() }
    fun replaceAll(replacement: String) { editor?.searcher?.replaceAll(replacement) }
    fun replaceCurrentMatch(replacement: String) { editor?.searcher?.replaceCurrentMatch(replacement) }
    fun stopSearch() { editor?.searcher?.stopSearch() }
}

/** 依据 Aurora 暗色令牌构建 Sora 配色（底为 Darcula 预设，仅覆盖关键槽位）。 */
private fun auroraSoraScheme() = SchemeDarcula().apply {
    setColor(EditorColorScheme.WHOLE_BACKGROUND, AuroraTokens.DialogBg.toArgb())
    setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, AuroraTokens.DialogBg.toArgb())
    setColor(EditorColorScheme.LINE_NUMBER, AuroraTokens.TextDisabled.toArgb())
    setColor(EditorColorScheme.LINE_NUMBER_CURRENT, AuroraTokens.Accent.toArgb())
    setColor(EditorColorScheme.LINE_DIVIDER, AuroraTokens.Stroke.toArgb())
    setColor(EditorColorScheme.TEXT_NORMAL, AuroraTokens.Text.toArgb())
    setColor(EditorColorScheme.CURRENT_LINE, AuroraTokens.SurfaceHover.toArgb())
    setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, AuroraTokens.Accent.copy(alpha = 0.32f).toArgb())
    setColor(EditorColorScheme.SCROLL_BAR_THUMB, AuroraTokens.StrokeStrong.toArgb())
    setColor(EditorColorScheme.SCROLL_BAR_TRACK, AuroraTokens.Surface.toArgb())
    setColor(EditorColorScheme.BLOCK_LINE, AuroraTokens.SurfaceStrong.toArgb())
    setColor(EditorColorScheme.BLOCK_LINE_CURRENT, AuroraTokens.AccentDark.toArgb())
}

/**
 * 以 Sora `CodeEditor` 作为唯一编辑面。
 *
 * @param initialText 初始文本（仅在 [resetKey] 变化时写入）
 * @param resetKey 内容重置键（文件路径 / 编码 / 重新加载计数变化时递增）
 * @param onChanged 文本内容变化（仅回调，不回传文本，避免 O(n) 拷贝）
 */
@Composable
fun SoraTextEditor(
    initialText: String,
    resetKey: Any?,
    fontSize: TextUnit,
    showLineNumbers: Boolean,
    /** 文件名原始扩展名：用于选取 Monarch 语法（null 表示无高亮）。 */
    languageExt: String?,
    /** 语法包变更计数：变化时重新取语法（用于导入/删除语法包后即时生效）。 */
    syntaxRevision: Int = 0,
    modifier: Modifier = Modifier,
    controller: SoraEditorController,
    onChanged: () -> Unit
) {
    val scheme = remember { auroraSoraScheme() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 语法按扩展名缓存；超大文本不启用高亮：Monarch 分析虽按可视区增量执行，
    // 但超大文本的首次分析仍会抢占主线程，收益低于代价（与统计跳过阈值同量级）。
    val editorLanguage = remember(languageExt, initialText.length, syntaxRevision) {
        if (initialText.length <= HIGHLIGHT_MAX_CHARS) SoraMonarchGrammars.languageFor(context, languageExt) else null
    }
    // 语法生效时必须同时套用 Monarch 主题配色：无主题时令牌色为 0，文本会渲染成不可见。
    val monarchScheme = remember(editorLanguage) {
        if (editorLanguage != null) SoraMonarchGrammars.schemeFor(context) else null
    }
    // 写入内容（setText）也会派发 ContentChangeEvent；用抑制标志避免把「加载/重置」误判为用户编辑而置脏。
    val suppress = remember { androidx.compose.runtime.mutableStateOf(false) }
    AndroidView(
        modifier = modifier.fillMaxSize().background(AuroraTokens.DialogBg),
        factory = { ctx ->
            CodeEditor(ctx).apply {
                setColorScheme(monarchScheme ?: scheme)
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                setLineNumberEnabled(showLineNumbers)
                setTextSize(fontSize.value)
                setWordwrap(false)
                setTabWidth(4)
                setHighlightCurrentLine(true)
                setScrollBarEnabled(true)
                setLigatureEnabled(false)
                // 打开即可编辑：Sora 无「只读/编辑」通道切换。
                setEditable(true)
                suppress.value = true
                setText(initialText)
                suppress.value = false
                editorLanguage?.let { setEditorLanguage(it) }
                controller.editor = this
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    if (!suppress.value) onChanged()
                }
            }
        },
        update = { ed ->
            ed.setLineNumberEnabled(showLineNumbers)
            ed.setTextSize(fontSize.value)
            // 扩展名变化（另存为其它类型）时切换语法；重复设置会重置分析，故仅在实例不同时应用。
            if (ed.editorLanguage !== editorLanguage && editorLanguage != null) {
                ed.setEditorLanguage(editorLanguage)
                monarchScheme?.let { ed.setColorScheme(it) }
            }
        }
    )
    // 仅在重置键变化时写入内容：避免把每次按键都折回 Compose State。
    LaunchedEffect(resetKey) {
        val ed = controller.editor ?: return@LaunchedEffect
        suppress.value = true
        ed.setText(initialText)
        suppress.value = false
        // 打开/重置后停在文首（对齐 MP-Manager：打开即从头阅读，而不是跳文末）。
        ed.setSelection(0, 0)
    }
    DisposableEffect(controller) {
        onDispose { controller.editor = null }
    }
}

/** 启用语法高亮的文本上限（字符）：超过则不设语法，避免超大文本的首次分析开销。 */
private const val HIGHLIGHT_MAX_CHARS = 200_000
