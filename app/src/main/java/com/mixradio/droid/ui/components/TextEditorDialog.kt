// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.nio.charset.Charset
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.ChunkedFileReader
import com.mixradio.droid.data.CharsetDetector
import com.mixradio.droid.data.EditHistoryManager
import com.mixradio.droid.data.LineEnding
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.TextCompare
import com.mixradio.droid.data.TextStatistics
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════
//  TextEditorDialog — 文本编辑器完整版
//  支持：打开/编辑/保存/另存为/新建、大文件分段、未保存提醒、自动保存草稿、行号、
//  编辑历史、查找替换、字号调节、全文统计、编码检测+切换、换行风格保留。
// ═══════════════════════════════════════════════════════════════
@Composable
fun TextEditorDialog(filePath: String, onDismissRequest: () -> Unit) {
    TextEditorDialogContent(initialFilePath = filePath, isNewFile = false, onDismissRequest = onDismissRequest)
}

@Composable
fun NewTextFileDialog(initialDirectory: String, defaultExtension: String = "txt", onDismissRequest: () -> Unit) {
    TextEditorDialogContent(
        initialFilePath = null, isNewFile = true, defaultNewExtension = defaultExtension,
        initialDirectory = initialDirectory, onDismissRequest = onDismissRequest
    )
}

@Composable
private fun TextEditorDialogContent(
    initialFilePath: String?, isNewFile: Boolean, defaultNewExtension: String = "txt",
    initialDirectory: String = "/", onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { AppSettings.getInstance(context) }

    var currentFilePath by remember { mutableStateOf(initialFilePath) }
    var contentValue by remember { mutableStateOf(TextFieldValue("")) }
    val editorScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    var isLoading by remember { mutableStateOf(!isNewFile) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var currentCharset by remember { mutableStateOf(Charsets.UTF_8) }
    var currentLineEnding by remember { mutableStateOf(LineEnding.LF) }
    var hasBom by remember { mutableStateOf(false) }
    var overrideCharset by remember { mutableStateOf<Charset?>(null) }

    var dirty by remember { mutableStateOf(isNewFile) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }

    var fileTotalBytes by remember { mutableLongStateOf(0L) }
    var isLargeFile by remember { mutableStateOf(false) }
    var chunkedOffset by remember { mutableLongStateOf(0L) }
    var chunkedHasMore by remember { mutableStateOf(false) }

    var history by remember { mutableStateOf<List<EditHistoryManager.HistoryEntry>>(emptyList()) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showFindReplaceDialog by remember { mutableStateOf(false) }

    // ── 文本对比流程状态：选文件 → 选模式 → 执行（带进度/取消）──
    var showDiffPicker by remember { mutableStateOf(false) }
    var diffTargetPath by remember { mutableStateOf<String?>(null) }
    var diffRunning by remember { mutableStateOf(false) }
    var diffProgressLines by remember { mutableLongStateOf(0L) }
    var diffCancelFlag by remember { mutableStateOf(false) }

    var showLineNumber by remember { mutableStateOf(appSettings.editorShowLineNumber) }
    var fontSize by remember { mutableFloatStateOf(appSettings.editorFontSize) }
    var autoSaveSeconds by remember { mutableIntStateOf(appSettings.editorAutoSaveInterval) }
    var lastAutoSaveAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastSavedAtMs by remember { mutableLongStateOf(0L) }

    val stats by remember { derivedStateOf { TextStatistics.compute(contentValue.text) } }

    // 加载文件
    LaunchedEffect(initialFilePath, isNewFile, overrideCharset) {
        if (isNewFile) { isLoading = false; return@LaunchedEffect }
        val path = initialFilePath ?: return@LaunchedEffect
        isLoading = true
        loadError = null
        withContext(Dispatchers.IO) {
            try {
                val total = ChunkedFileReader.fileSize(path)
                fileTotalBytes = total
                isLargeFile = total > ChunkedFileReader.LARGE_FILE_THRESHOLD
                if (!isLargeFile) {
                    val load = ChunkedFileReader.loadAll(path)
                    currentCharset = overrideCharset ?: load.charset
                    hasBom = load.hasBom
                    currentLineEnding = LineEnding.detect(load.text)
                    contentValue = TextFieldValue(load.text, TextRange(load.text.length))
                } else {
                    val raw = ChunkedFileReader.readHead(path, ChunkedFileReader.CHUNK_BYTES.toInt())
                    val det = CharsetDetector.detect(raw)
                    currentCharset = overrideCharset ?: det.charset
                    hasBom = det.hasBom
                    currentLineEnding = LineEnding.detect(det.text)
                    contentValue = TextFieldValue(det.text, TextRange(det.text.length))
                    chunkedOffset = raw.size.toLong()
                    chunkedHasMore = raw.size.toLong() < total
                }
                history = EditHistoryManager.getHistory(path)
            } catch (e: Exception) { loadError = "读取失败: ${e.message}" }
            finally { isLoading = false }
        }
    }

    // 类 git 自动快照：编辑停顿 2.5s 且内容与最近版本不同 → 自动记录一条历史。
    // 保存(手动/另存为)时也会记录。上限 20 条由 EditHistoryManager 淘汰最旧。
    LaunchedEffect(contentValue.text, currentFilePath) {
        if (currentFilePath == null || !dirty) return@LaunchedEffect
        delay(2500L)
        val path = currentFilePath ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val latest = EditHistoryManager.getHistory(path).firstOrNull()
            if (latest == null || latest.content != contentValue.text) {
                EditHistoryManager.addHistory(path, contentValue.text)
                history = EditHistoryManager.getHistory(path)
            }
        }
    }

    DisposableEffect(initialFilePath) {
        onDispose {
            // 历史持久保留（类 git 仓库），关闭编辑器不清空。
            // 旧版在此 clearHistory 导致"永远只有一条历史"的 BUG，已移除。
        }
    }

    // 自动保存草稿
    LaunchedEffect(autoSaveSeconds, dirty, currentFilePath) {
        if (autoSaveSeconds <= 0) return@LaunchedEffect
        while (true) {
            delay(1000L)
            if (currentFilePath == null || !dirty) continue
            val now = System.currentTimeMillis()
            if (now - lastAutoSaveAt >= autoSaveSeconds * 1000L) {
                lastAutoSaveAt = now
                withContext(Dispatchers.IO) {
                    EditHistoryManager.addHistory(currentFilePath!!, contentValue.text)
                    history = EditHistoryManager.getHistory(currentFilePath!!)
                }
            }
        }
    }

    val language by remember(currentFilePath) {
        derivedStateOf { currentFilePath?.let { CodeHighlighter.languageOf(File(it).name) } }
    }
    // 语法高亮经 visualTransformation 在 BasicTextField 内部渲染。
    // 修复：旧实现用「静态 Text + 透明 BasicTextField(matchParentSize)」叠加，
    // 在 Row(verticalScroll) 滚动容器内产生 0 宽 Constraints → IllegalArgumentException 闪退（.sh），
    // 同一坏约束使普通分支 fillMaxSize 拿到 0 宽 → txt 内容不可见。
    val highlightTransformation = remember(language) {
        val lang = language
        if (lang == null) androidx.compose.ui.text.input.VisualTransformation.None
        else androidx.compose.ui.text.input.VisualTransformation { text ->
            // 超长文本降级纯文本（全量重扫描高亮在输入时会造成卡顿）
            if (text.text.isEmpty() || text.text.length > 100_000) {
                androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
            } else {
                androidx.compose.ui.text.input.TransformedText(
                    CodeHighlighter.highlight(text.text, lang.ext, AuroraTokens.Text),
                    androidx.compose.ui.text.input.OffsetMapping.Identity
                )
            }
        }
    }
    // 保存（工具栏「保存」/ 未保存提醒共用）：无路径时转「另存为」，无改动时提示。
    val doSave: () -> Unit = {
        when {
            currentFilePath == null -> showSaveAsDialog = true
            isLargeFile -> toastMessage = "分段只读模式：大文件不可编辑保存（防止数据截断）"
            !dirty -> toastMessage = "无改动"
            else -> {
                isSaving = true
                scope.launch {
                    val path = currentFilePath!!
                    // 成功: Triple(写盘结果, 错误信息, 最新历史)；历史读写一并放入 IO 线程
                    val r = withContext(Dispatchers.IO) {
                        val w = writeTextFile(path, contentValue.text, currentCharset, currentLineEnding, hasBom)
                        if (w.first) {
                            EditHistoryManager.addHistory(path, contentValue.text)
                            Triple(true, null as String?, EditHistoryManager.getHistory(path))
                        } else Triple(false, w.second, emptyList())
                    }
                    isSaving = false
                    if (r.first) {
                        dirty = false; lastSavedAtMs = System.currentTimeMillis()
                        history = r.third
                        toastMessage = "已保存"
                    } else { saveMessage = r.second ?: "保存失败" }
                }
            }
        }
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); toastMessage = null }
    }

    val dialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    Dialog(
        onDismissRequest = { if (dirty) showUnsavedDialog = true else onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AuroraTokens.DialogBg, shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, AuroraTokens.Stroke),
            modifier = Modifier.fillMaxWidth(0.98f).height(dialogHeight)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                // 顶栏
                EditorTopBar(
                    fileName = currentFilePath?.let { File(it).name }
                        ?: if (isNewFile) "新建${defaultNewExtension.uppercase()}" else "",
                    dirty = dirty, language = language, hasBom = hasBom,
                    onSettingsClick = { showSettingsDialog = true },
                    onFindClick = { showFindReplaceDialog = true },
                    onCompareClick = {
                        if (currentFilePath == null) {
                            toastMessage = "请先保存文件后再对比"
                        } else {
                            showDiffPicker = true
                        }
                    },
                    onSaveClick = doSave,
                    onHistoryClick = { showHistoryDialog = true },
                    historyCount = history.size,
                    onDismissRequest = { if (dirty) showUnsavedDialog = true else onDismissRequest() }
                )

                // 大文件提示条
                if (isLargeFile) {
                    ChunkedInfoBar(
                        offset = chunkedOffset, total = fileTotalBytes, hasMore = chunkedHasMore,
                        onLoadMore = {
                            scope.launch {
                                loadNextChunk(
                                    filePath = currentFilePath ?: return@launch, fromOffset = chunkedOffset,
                                    charset = currentCharset,
                                    onResult = { text, newOffset, hasMore ->
                                        val newText = contentValue.text + text
                                        contentValue = TextFieldValue(newText, TextRange(newText.length))
                                        chunkedOffset = newOffset
                                        chunkedHasMore = hasMore
                                    }
                                )
                            }
                        }
                    )
                }

                // 编辑区
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                    when {
                        isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("正在加载…", style = AuroraTextStyles.body2, color = AuroraTokens.TextSecondary)
                        }
                        loadError != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(loadError!!, style = AuroraTextStyles.body1, color = AuroraTokens.Error)
                        }
                        else -> EditorContentArea(
                            value = contentValue,
                            onValueChange = { contentValue = it; dirty = true },
                            highlightTransformation = if (isLargeFile) {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            } else highlightTransformation,
                            showLineNumber = showLineNumber, fontSize = fontSize.sp,
                            scrollState = editorScroll, hScroll = hScroll,
                            readOnly = isLargeFile
                        )
                    }
                }

                // 状态栏
                EditorStatusBar(
                    stats = stats, filePath = currentFilePath, isLargeFile = isLargeFile,
                    fileTotalBytes = fileTotalBytes, chunkedOffset = chunkedOffset,
                    lastSavedAtMs = lastSavedAtMs, autoSaveSeconds = autoSaveSeconds, dirty = dirty
                )

                if (saveMessage != null) {
                    Text(text = saveMessage!!, style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.Error, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    // 子弹窗
    if (showSettingsDialog) EditorSettingsDialog(
        text = contentValue.text,
        showLineNumber = showLineNumber,
        onShowLineNumberChange = { showLineNumber = it; appSettings.updateEditorShowLineNumber(it) },
        fontSize = fontSize, onFontSizeChange = { fontSize = it; appSettings.updateEditorFontSize(it) },
        autoSaveSeconds = autoSaveSeconds,
        onAutoSaveChange = { autoSaveSeconds = it; appSettings.updateEditorAutoSaveInterval(it) },
        charset = currentCharset,
        onCharsetChange = { cs ->
            currentCharset = cs; overrideCharset = cs
            if (!isNewFile) { contentValue = TextFieldValue(""); isLoading = true }
        },
        lineEnding = currentLineEnding,
        onLineEndingChange = { le -> currentLineEnding = le },
        onSaveAsClick = { showSaveAsDialog = true },
        onDismiss = { showSettingsDialog = false }
    )

    if (showFindReplaceDialog) FindReplaceDialog(
        text = contentValue.text, currentSelectionStart = contentValue.selection.start,
        onFindNext = { findText ->
            // 从光标位置循环查找下一个匹配项并选中（滚动到可见）
            val text = contentValue.text
            if (findText.isNotEmpty() && text.isNotEmpty()) {
                var idx = text.indexOf(findText, contentValue.selection.end)
                if (idx < 0) idx = text.indexOf(findText)  // 环绕查找
                if (idx >= 0) {
                    contentValue = TextFieldValue(text, TextRange(idx, idx + findText.length))
                }
            }
        },
        onReplace = { original, replacement ->
            // 仅替换当前选中的匹配项（若选中内容 == 查找词）
            val sel = contentValue.selection
            if (sel.max - sel.min == original.length) {
                val selText = contentValue.text.substring(sel.min, sel.min + original.length)
                if (selText == original) {
                    val newText = contentValue.text.replaceRange(sel.min, sel.min + original.length, replacement)
                    contentValue = TextFieldValue(newText, TextRange(sel.min + replacement.length)); dirty = true
                } else {
                    // 未选中匹配项：替换第一个
                    val idx = contentValue.text.indexOf(original)
                    if (idx >= 0) {
                        val newText = contentValue.text.replaceRange(idx, idx + original.length, replacement)
                        contentValue = TextFieldValue(newText, TextRange(idx + replacement.length)); dirty = true
                    }
                }
            } else {
                val idx = contentValue.text.indexOf(original)
                if (idx >= 0) {
                    val newText = contentValue.text.replaceRange(idx, idx + original.length, replacement)
                    contentValue = TextFieldValue(newText, TextRange(idx + replacement.length)); dirty = true
                }
            }
        },
        onReplaceAll = { original, replacement ->
            val newText = contentValue.text.replace(original, replacement)
            contentValue = TextFieldValue(newText, TextRange(newText.length)); dirty = true
        },
        onDismiss = { showFindReplaceDialog = false }
    )

    // ── 文本对比 ① 选择 2 号文件（复用主页选择器，注入「同后缀 + 排除自身」过滤）──
    val diffInitialDir = if (currentFilePath != null) {
        File(currentFilePath!!).parent ?: initialDirectory
    } else initialDirectory
    BuiltInFilePicker(
        appSettings = appSettings,
        show = showDiffPicker,
        initialDirectory = diffInitialDir,
        titleText = "选择对比文件",
        subtitleText = "仅显示与当前文件同后缀的文件（当前文件已隐藏）",
        emptyHint = "该目录下没有相同后缀的文件",
        fileFilter = currentFilePath?.let { TextCompare.sameExtensionFilter(it) },
        onDismissRequest = { showDiffPicker = false },
        onFileSelected = { path ->
            showDiffPicker = false
            diffTargetPath = path
        }
    )

    // ── 文本对比 ② 选择对比模式 ──
    if (diffTargetPath != null && !diffRunning) {
        DiffModeDialog(
            fileA = currentFilePath ?: "",
            fileB = diffTargetPath!!,
            onDismiss = { diffTargetPath = null },
            onConfirm = { mode ->
                val pathA = currentFilePath ?: return@DiffModeDialog
                val pathB = diffTargetPath ?: return@DiffModeDialog
                val charset = currentCharset
                diffTargetPath = null
                diffRunning = true
                diffProgressLines = 0L
                diffCancelFlag = false
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val dir = File(pathA).parent ?: "/"
                            val outPath = TextCompare.nextOutputPath(dir, pathA)
                            TextCompare.run(
                                tempDir = context.cacheDir.absolutePath,
                                pathA = pathA, pathB = pathB, outPath = outPath,
                                charset = charset, mode = mode,
                                onProgress = { n -> diffProgressLines = n },
                                isCancelled = { diffCancelFlag }
                            )
                        }
                        toastMessage = if (result.cancelled) {
                            "对比已取消"
                        } else {
                            val fileName = File(result.outPath).name
                            val cost = "耗时 ${result.elapsedMs}ms"
                            when (mode) {
                                TextCompare.Mode.LINE_BY_LINE ->
                                    "对比完成，相同 ${result.hitLines} 行（$cost）→ $fileName"
                                TextCompare.Mode.COMMON_LINES ->
                                    "对比完成，共同 ${result.hitLines} 行（$cost）→ $fileName"
                            }
                        }
                    } catch (e: Exception) {
                        saveMessage = e.message ?: "对比失败"
                    } finally {
                        diffRunning = false
                        diffCancelFlag = false
                    }
                }
            }
        )
    }

    // ── 文本对比 ③ 进度（可取消）──
    if (diffRunning) {
        DiffProgressDialog(
            progressLines = diffProgressLines,
            onCancel = { diffCancelFlag = true }
        )
    }

    if (showHistoryDialog) HistoryDialog(
        history = history,
        onRestore = { entry ->
            // 类 git 回退：恢复到所选历史版本。当前内容若与该版本不同，
            // 先把当前内容存为新历史（保证可再撤回），再恢复。
            scope.launch {
                withContext(Dispatchers.IO) {
                    val cur = contentValue.text
                    if (cur != entry.content) {
                        EditHistoryManager.addHistory(currentFilePath ?: "", cur)
                    }
                }
                history = EditHistoryManager.getHistory(currentFilePath ?: "")
                contentValue = TextFieldValue(entry.content, TextRange(entry.content.length)); dirty = true
                showHistoryDialog = false
            }
        },
        onClearAll = {
            scope.launch {
                withContext(Dispatchers.IO) { EditHistoryManager.clearHistory(currentFilePath ?: "") }
                history = emptyList()
            }
        },
        onDismiss = { showHistoryDialog = false }
    )

    if (showSaveAsDialog) SaveAsDialog(
        initialDirectory = currentFilePath?.let { File(it).parent } ?: initialDirectory,
        onSave = { newPath ->
            if (isLargeFile) {
                toastMessage = "分段只读模式：大文件不可另存为（内容不完整）"
                showSaveAsDialog = false
                return@SaveAsDialog
            }
            scope.launch {
                val (ok, msg) = withContext(Dispatchers.IO) {
                    writeTextFile(newPath, contentValue.text, currentCharset, currentLineEnding, hasBom)
                }
                if (ok) {
                    currentFilePath = newPath; dirty = false; lastSavedAtMs = System.currentTimeMillis()
                    EditHistoryManager.addHistory(newPath, contentValue.text)
                    history = EditHistoryManager.getHistory(newPath)
                    toastMessage = "已保存"
                } else { toastMessage = msg ?: "保存失败" }
                showSaveAsDialog = false
            }
        },
        onDismiss = { showSaveAsDialog = false }
    )

    if (showUnsavedDialog) UnsavedChangesDialog(
        onSave = {
            showUnsavedDialog = false
            if (currentFilePath == null) { showSaveAsDialog = true; return@UnsavedChangesDialog }
            isSaving = true
            scope.launch {
                val (ok, msg) = withContext(Dispatchers.IO) {
                    writeTextFile(currentFilePath!!, contentValue.text, currentCharset, currentLineEnding, hasBom)
                }
                isSaving = false
                if (ok) { dirty = false; lastSavedAtMs = System.currentTimeMillis(); onDismissRequest() }
                else toastMessage = msg ?: "保存失败"
            }
        },
        onDiscard = { showUnsavedDialog = false; onDismissRequest() },
        onCancel = { showUnsavedDialog = false }
    )
}

// ═══════════════════════════════════════════════════════════════
//  工具函数
// ═══════════════════════════════════════════════════════════════

/** 大文件读下一段（追加加载）。 */
private suspend fun loadNextChunk(
    filePath: String, fromOffset: Long, charset: java.nio.charset.Charset,
    onResult: (text: String, newOffset: Long, hasMore: Boolean) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val total = ChunkedFileReader.fileSize(filePath)
        val nextOffset = fromOffset + ChunkedFileReader.CHUNK_BYTES
        val hasMore = nextOffset < total
        val raw = ChunkedFileReader.readRange(filePath, fromOffset, ChunkedFileReader.CHUNK_BYTES)
        val text = String(raw, charset)
        onResult(text, nextOffset, hasMore)
    } catch (_: Throwable) {
        onResult("", fromOffset, false)
    }
}

/** 写入文本：root 走 /data/local/tmp 中转 + mv；无 root 直写。 */
private suspend fun writeTextFile(
    filePath: String, text: String, charset: java.nio.charset.Charset,
    lineEnding: LineEnding, writeBom: Boolean
): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
    try {
        val finalText = LineEnding.apply(text, lineEnding)
        val bytes = if (writeBom && (charset == Charsets.UTF_8 || charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE)) {
            // 拼接 BOM
            val bom = when (charset) {
                Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                else -> byteArrayOf()
            }
            bom + finalText.toByteArray(charset)
        } else {
            finalText.toByteArray(charset)
        }

        if (RootService.isRootGranted == true) {
            val tmpFile = "/data/local/tmp/_shso_edit_${System.currentTimeMillis()}.tmp"
            val writeProcess = ProcessBuilder("su", "-c", "cat > ${RootService.escapeShellArg(tmpFile)}")
                .redirectErrorStream(true).start()
            writeProcess.outputStream.use { out ->
                out.write(bytes); out.flush()
            }
            val finished = writeProcess.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { writeProcess.destroyForcibly(); return@withContext Pair(false, "写入临时文件超时") }
            val (mvCode, mvOut) = RootService.runCommandSync(
                "mv ${RootService.escapeShellArg(tmpFile)} ${RootService.escapeShellArg(filePath)}",
                60_000L
            )
            if (mvCode == 0) Pair(true, null) else Pair(false, "保存失败: ${mvOut.trim().ifEmpty { "未知错误" }}")
        } else {
            File(filePath).writeBytes(bytes)
            Pair(true, null)
        }
    } catch (e: Exception) { Pair(false, "保存失败: ${e.message}") }
}

// ═══════════════════════════════════════════════════════════════
//  EditorTopBar
// ═══════════════════════════════════════════════════════════════
@Composable
private fun EditorTopBar(
    fileName: String, dirty: Boolean,
    language: CodeHighlighter.Language?, hasBom: Boolean,
    onSettingsClick: () -> Unit, onFindClick: () -> Unit,
    onCompareClick: () -> Unit, onSaveClick: () -> Unit,
    onHistoryClick: () -> Unit, historyCount: Int,
    onDismissRequest: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (fileName.isEmpty()) "编辑器" else fileName,
                    style = AuroraTextStyles.title3, color = AuroraTokens.Text
                )
                if (dirty) Text("●", style = AuroraTextStyles.body2, color = AuroraTokens.Accent)
                if (language != null) {
                    Text(
                        text = language.displayName, style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary,
                        modifier = Modifier
                            .background(AuroraTokens.SurfaceHover, RoundedCornerShape(0.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "✕", style = AuroraTextStyles.body1, color = AuroraTokens.TextSecondary,
                modifier = Modifier.clickable { onDismissRequest() }.padding(4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EditorToolbarButton("查找", null, onFindClick)
            EditorToolbarButton("对比", null, onCompareClick)
            EditorToolbarButton("历史", if (historyCount > 0) "$historyCount" else null, onHistoryClick)
            EditorToolbarButton("保存", null, onSaveClick, tint = if (dirty) AuroraTokens.Accent else AuroraTokens.Text)
            EditorToolbarButton("设置", null, onSettingsClick)
            if (hasBom) {
                Text(
                    text = "BOM", style = AuroraTextStyles.footnote2, color = AuroraTokens.Warning,
                    modifier = Modifier
                        .background(AuroraTokens.SurfaceHover, RoundedCornerShape(0.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        HorizontalDivider(color = AuroraTokens.Stroke, thickness = 0.5.dp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun EditorToolbarButton(
    label: String, subLabel: String?, onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = AuroraTokens.Text
) {
    Row(
        modifier = Modifier
            .background(AuroraTokens.SurfaceHover, RoundedCornerShape(0.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = AuroraTextStyles.footnote2, color = tint)
        if (subLabel != null) {
            Text(text = subLabel, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  ChunkedInfoBar
// ═══════════════════════════════════════════════════════════════
@Composable
private fun ChunkedInfoBar(
    offset: Long, total: Long, hasMore: Boolean, onLoadMore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(AuroraTokens.SurfaceHover, RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "分段模式: ${formatBytes(offset)} / ${formatBytes(total)}",
            style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
        )
        if (hasMore) {
            Text(
                text = "加载更多 →", style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
                modifier = Modifier.clickable(onClick = onLoadMore).padding(4.dp)
            )
        } else {
            Text(text = "全文已加载", style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

// ═══════════════════════════════════════════════════════════════
//  EditorContentArea
//  修复版 v2：
//  · 崩溃根因：旧「静态Text+透明BasicTextField(matchParentSize)叠加」在滚动容器内产生
//    0 宽 Constraints → IllegalArgumentException 闪退（.sh 高亮路径）。
//  · 不可见根因：BasicTextField 放在 Row(verticalScroll) 内 weight(1f) 在无界高度
//    测量下失效，实际只测得 ~112px 宽（uiautomator 实测 bounds=[1313,448][1425,1767]），
//    文字/焦点/输入法全不可用。Row + weight 与滚动容器嵌套是官方文档明确反对的结构。
//  · 正解：BasicTextField 自带内部滚动，直接放 weight(1f) 的 Box 中（不在 verticalScroll
//    内），行号列用同高 Box 平铺，两者各自独立滚动同步（MT/MP-Manager 同款布局）。
// ═══════════════════════════════════════════════════════════════
@Composable
private fun EditorContentArea(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
    highlightTransformation: androidx.compose.ui.text.input.VisualTransformation,
    showLineNumber: Boolean, fontSize: androidx.compose.ui.unit.TextUnit,
    scrollState: androidx.compose.foundation.ScrollState, hScroll: androidx.compose.foundation.ScrollState,
    readOnly: Boolean = false
) {
    val scope = rememberCoroutineScope()
    // 编辑区 + 右侧细拖动条（贴紧边缘）：拖动可快速跳到目标行号。
    Box(modifier = Modifier.fillMaxSize()) {
        if (readOnly) {
            // 大文件（分段模式）：用 LazyColumn 按行懒加载渲染，只布局可见行，
            // 彻底规避 BasicTextField 对整段文本做 StaticLayout 全量布局导致的 OOM。
            val lines = remember(value.text) { value.text.split('\n') }
            val lazyState = rememberLazyListState()
            LazyColumn(
                state = lazyState,
                modifier = Modifier.fillMaxSize().padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                itemsIndexed(lines) { index, line ->
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        if (showLineNumber) {
                            Text(
                                text = "${index + 1}",
                                style = AuroraTextStyles.monospace.copy(fontSize = fontSize),
                                color = AuroraTokens.TextDisabled,
                                modifier = Modifier.padding(end = 8.dp, top = 1.dp)
                            )
                        }
                        Text(
                            text = line,
                            style = AuroraTextStyles.monospace.copy(fontSize = fontSize),
                            color = AuroraTokens.Text,
                            modifier = Modifier.padding(end = 200.dp),
                            softWrap = false
                        )
                    }
                }
            }
            LineScrollBar(
                lineCount = lines.size,
                getFraction = {
                    if (lines.size <= 1) 0f else lazyState.firstVisibleItemIndex.toFloat() / (lines.size - 1)
                },
                setFraction = { f ->
                    scope.launch { lazyState.scrollToItem((f * (lines.size - 1)).toInt().coerceAtLeast(0)) }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        } else {
            val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
            val lineNumberWidth = remember(lineCount) { "${lineCount}".length.coerceAtLeast(3) }

            Row(modifier = Modifier.fillMaxSize()) {
                // ── 行号列（与编辑区共享同一 scrollState，纵向同步滚动）──
                if (showLineNumber) {
                    Box(modifier = Modifier.width((lineNumberWidth * (fontSize.value * 0.7f)).dp + 12.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(end = 8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (i in 1..lineCount) {
                                Text(
                                    text = "$i",
                                    style = AuroraTextStyles.monospace.copy(fontSize = fontSize),
                                    color = AuroraTokens.TextDisabled,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                    // 竖直分隔线：必须用 VerticalDivider（fillMaxHeight）。
                    // 不可用 HorizontalDivider——其内部强制 fillMaxWidth()，在横向 Row 里会
                    // 吃掉几乎全部宽度，把 weight(1f) 的 BasicTextField 压成 ~112px 窄条
                    // （文字不可见 / 无法聚焦 / 输入法不弹的共同根因）。
                    androidx.compose.material3.VerticalDivider(
                        color = AuroraTokens.Stroke, thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                // ── 编辑区：BasicTextField 与行号列共享 scrollState，纵向滚动同步 ──
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = AuroraTextStyles.monospace.copy(
                        fontSize = fontSize,
                        color = AuroraTokens.Text
                    ),
                    cursorBrush = SolidColor(AuroraTokens.Accent),
                    visualTransformation = highlightTransformation,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                        .verticalScroll(scrollState)
                )
            }
            LineScrollBar(
                lineCount = lineCount,
                getFraction = {
                    if (scrollState.maxValue == 0) 0f else scrollState.value.toFloat() / scrollState.maxValue
                },
                setFraction = { f ->
                    scope.launch { scrollState.scrollTo((f * scrollState.maxValue).toInt().coerceAtLeast(0)) }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * 编辑区右侧贴边细拖动条：拖动快速跳到目标行号。
 * 轨道细（6dp），拖动时显示目标行号；用 [getFraction]/[setFraction] 与上层滚动状态双向绑定。
 */
@Composable
private fun LineScrollBar(
    lineCount: Int,
    getFraction: () -> Float,
    setFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lineCount <= 1) return
    val density = LocalDensity.current
    val thumbHPx = with(density) { 48.dp.toPx() }
    var trackHeight by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val getFractionState = rememberUpdatedState(getFraction)
    val setFractionState = rememberUpdatedState(setFraction)
    val lineCountState = rememberUpdatedState(lineCount)

    val frac = if (dragging) dragFrac else getFraction().coerceIn(0f, 1f)
    val maxTop = (trackHeight - thumbHPx).coerceAtLeast(0f)
    val topPx = (frac * maxTop).coerceIn(0f, maxTop)

    Box(
        modifier = modifier
            .width(6.dp)
            .fillMaxHeight()
            .background(AuroraTokens.Stroke.copy(alpha = 0.35f))
            .onGloballyPositioned { trackHeight = it.size.height }
            .pointerInput(trackHeight) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val f = ((offset.y - thumbHPx / 2f).coerceIn(0f, maxTop)) / maxTop.coerceAtLeast(1f)
                        dragFrac = f
                        setFractionState.value(f)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val f = ((change.position.y - thumbHPx / 2f).coerceIn(0f, maxTop)) / maxTop.coerceAtLeast(1f)
                        dragFrac = f
                        setFractionState.value(f)
                    },
                    onDragEnd = { dragging = false }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, topPx.roundToInt()) }
                .width(6.dp)
                .height(48.dp)
                .background(AuroraTokens.Accent.copy(alpha = 0.9f))
        )
        if (dragging) {
            val targetLine = ((dragFrac * (lineCountState.value - 1)) + 1).roundToInt()
                .coerceIn(1, lineCountState.value)
            Text(
                text = "$targetLine",
                style = AuroraTextStyles.footnote2,
                color = AuroraTokens.Text,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(-34, topPx.roundToInt()) }
                    .background(AuroraTokens.PillBg)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  EditorStatusBar
// ═══════════════════════════════════════════════════════════════
@Composable
private fun EditorStatusBar(
    stats: TextStatistics.Stats, filePath: String?, isLargeFile: Boolean,
    fileTotalBytes: Long, chunkedOffset: Long,
    lastSavedAtMs: Long, autoSaveSeconds: Int, dirty: Boolean
) {
    val df = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(AuroraTokens.SurfaceHover, RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "行数 ${stats.lines}",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
            )
            Text(
                text = "字节数 ${if (isLargeFile) "${formatBytes(chunkedOffset)} / ${formatBytes(fileTotalBytes)}" else stats.bytes}",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (autoSaveSeconds > 0) {
                Text(text = "自动保存 ${autoSaveSeconds}s", style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
            }
            if (lastSavedAtMs > 0) {
                Text(text = "已保存 ${df.format(Date(lastSavedAtMs))}", style = AuroraTextStyles.footnote2, color = AuroraTokens.Success)
            }
            if (dirty) {
                Text(text = "未保存", style = AuroraTextStyles.footnote2, color = AuroraTokens.Warning)
            }
        }
    }
}

// 编辑器可切换的字符集（编码设置页复用）
private val EDITOR_CHARSETS: List<Pair<Charset, String>> = listOf(
    Charsets.UTF_8 to "UTF-8", Charsets.UTF_16LE to "UTF-16 LE",
    Charsets.UTF_16BE to "UTF-16 BE", Charset.forName("GBK") to "GBK",
    Charset.forName("GB2312") to "GB2312", Charset.forName("GB18030") to "GB18030",
    Charsets.ISO_8859_1 to "ISO-8859-1", Charsets.US_ASCII to "ASCII"
).distinctBy { it.second }

// ═══════════════════════════════════════════════════════════════
//  FindReplaceDialog — 查找/替换（半透明紧凑版 v4）
//  · 40% 半透明暗色面板：能隐约看清底部编辑区文本；无标题、无矩形框
//  · 极光渐变彩色文字；关闭按钮偏红渐变
//  · 布局：查找框 → 替换框 → 动作行(查找下一个|替换|全部替换) → 关闭
//  · 点击动作按钮强制收起输入法（逻辑在 Dialog 内部，作用于 Dialog 自己的窗口）
// ═══════════════════════════════════════════════════════════════
@Composable
private fun FindReplaceDialog(
    text: String, currentSelectionStart: Int,
    onFindNext: (findText: String) -> Unit,
    onReplace: (original: String, replacement: String) -> Unit,
    onReplaceAll: (original: String, replacement: String) -> Unit,
    onDismiss: () -> Unit
) {
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    val matchCount = remember(findText, text) {
        if (findText.isEmpty()) 0
        else text.split(findText).size - 1
    }

    // 极光渐变画笔（青→紫→粉，主题同源）
    val auroraBrush = remember {
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(
                androidx.compose.ui.graphics.Color(0xFF00E5FF),
                androidx.compose.ui.graphics.Color(0xFF7C6BFF),
                androidx.compose.ui.graphics.Color(0xFFFF4081)
            )
        )
    }
    // 关闭用偏红渐变（红→橙红）
    val closeBrush = remember {
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(
                androidx.compose.ui.graphics.Color(0xFFFF5252),
                androidx.compose.ui.graphics.Color(0xFFFF6E40)
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // ⚠ 所有焦点/IME 操作必须在此 Dialog 内容作用域内进行：
        // LocalFocusManager / LocalView 在这里拿到的是 Dialog 自己的焦点体系与窗口，
        // 之前版本在 Dialog 外部获取导致 IME 永远收不起来（作用对象错成 Activity 窗口）。
        val dialogWindow = (LocalView.current.parent
                as? DialogWindowProvider)?.window
        val dialogFocusManager = LocalFocusManager.current
        val dialogView = LocalView.current
        val dialogContext = LocalContext.current

        // 窗口本身保持全透明 + 无暗化遮罩；
        // 40% 半透明加在下方小面板 Box 上（避免整屏被 60% 暗色盖住）。
        androidx.compose.runtime.SideEffect {
            try {
                dialogWindow?.apply {
                    setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    )
                    setDimAmount(0f)
                }
            } catch (_: Throwable) { }
        }

        // 收起输入法：三层兜底，全部作用于 Dialog 自己的窗口
        // 先把焦点转移到「焦点回收站」（隐藏的非输入可聚焦节点），
        // 确保输入框真正失焦——仅 clearFocus 在部分 ROM 上不触发 IME 收起。
        val focusSink = remember { androidx.compose.ui.focus.FocusRequester() }
        fun dismissIme() {
            try { focusSink.requestFocus() } catch (_: Throwable) { }
            // 1) 清除 Dialog 内 Compose 焦点（焦点离开文本框 → 系统自动收 IME）
            try { dialogFocusManager.clearFocus(force = true) } catch (_: Throwable) { }
            // 2) WindowInsetsController 隐藏 IME（Android 11+ 官方通道，直接作用于本 Dialog 窗口）
            try {
                dialogWindow?.let { w ->
                    val controller = WindowCompat.getInsetsController(w, w.decorView)
                    controller.hide(WindowInsetsCompat.Type.ime())
                }
            } catch (_: Throwable) { }
            // 3) 传统 IMM 兜底（用 Dialog 的 decorView token，而非 Activity token）
            try {
                val imm = dialogContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                dialogWindow?.decorView?.let { v ->
                    imm.hideSoftInputFromWindow(v.applicationWindowToken, 0)
                }
                dialogView.clearFocus()
            } catch (_: Throwable) { }
        }

        // 悬浮内容：40% 半透明暗色面板（60% 不透明 #0D1521），无边框、零圆角
        // 宽度 90%（APP 宽度的 90%）；字号整体 +5，行间距 / 内边距 ×1.3
        Box(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .background(androidx.compose.ui.graphics.Color(0x990D1521))
                .padding(horizontal = 18.dp, vertical = 13.dp)
        ) {
            // 焦点回收站：0 尺寸、不可见，仅用于承接焦点使输入框失焦
            Box(
                modifier = Modifier
                    .focusRequester(focusSink)
                    .focusable()
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 匹配计数（渐变小字）
                if (findText.isNotEmpty()) {
                    Text(
                        text = if (matchCount > 0) "共 $matchCount 处匹配" else "无匹配",
                        style = AuroraTextStyles.footnote2.copy(fontSize = 13.sp),
                        color = if (matchCount > 0) androidx.compose.ui.graphics.Color(0xFF00E5FF) else AuroraTokens.Warning,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                // 查找输入框
                BasicTextField(
                    value = findText,
                    onValueChange = { findText = it },
                    textStyle = AuroraTextStyles.monospace.copy(
                        fontSize = 19.sp, color = AuroraTokens.Text
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(AuroraTokens.Accent),
                    decorationBox = { inner ->
                        Column {
                            Text(
                                "查找内容",
                                style = AuroraTextStyles.footnote2.copy(
                                    fontSize = 13.sp,
                                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(
                                            androidx.compose.ui.graphics.Color(0xFF00E5FF),
                                            androidx.compose.ui.graphics.Color(0xFF69F0AE)
                                        )
                                    )
                                )
                            )
                            Box(modifier = Modifier.padding(top = 3.dp)) { inner() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // 分隔微线
                Box(
                    modifier = Modifier.fillMaxWidth().height(1.dp)
                        .background(AuroraTokens.Stroke)
                )
                // 替换输入框
                BasicTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    textStyle = AuroraTextStyles.monospace.copy(
                        fontSize = 19.sp, color = AuroraTokens.Text
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(AuroraTokens.AccentViolet),
                    decorationBox = { inner ->
                        Column {
                            Text(
                                "替换为",
                                style = AuroraTextStyles.footnote2.copy(
                                    fontSize = 13.sp,
                                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(
                                            androidx.compose.ui.graphics.Color(0xFFB58CFF),
                                            androidx.compose.ui.graphics.Color(0xFFFF4081)
                                        )
                                    )
                                )
                            )
                            Box(modifier = Modifier.padding(top = 3.dp)) { inner() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // 动作行：查找下一个 / 替换 / 全部替换 / 关闭
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val enabledNext = findText.isNotEmpty() && matchCount > 0
                    // ⚠ 禁用态也用彩色渐变（仅 alpha 50%），严禁灰——灰字看不清
                    val nextBrush = remember(enabledNext) {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            if (enabledNext) listOf(
                                androidx.compose.ui.graphics.Color(0xFF00E5FF),
                                androidx.compose.ui.graphics.Color(0xFF448AFF)
                            ) else listOf(
                                androidx.compose.ui.graphics.Color(0x8000E5FF),
                                androidx.compose.ui.graphics.Color(0x80448AFF)
                            )
                        )
                    }
                    Text(
                        text = "查找下一个",
                        style = AuroraTextStyles.body2.copy(
                            fontSize = 17.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            brush = nextBrush
                        ),
                        modifier = Modifier
                            .clickable(enabled = enabledNext) {
                                dismissIme(); onFindNext(findText)
                            }
                            .padding(vertical = 5.dp)
                    )
                    val replaceBrush = remember(enabledNext) {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            if (enabledNext) listOf(
                                androidx.compose.ui.graphics.Color(0xFF69F0AE),
                                androidx.compose.ui.graphics.Color(0xFF00E676)
                            ) else listOf(
                                androidx.compose.ui.graphics.Color(0x8069F0AE),
                                androidx.compose.ui.graphics.Color(0x8000E676)
                            )
                        )
                    }
                    Text(
                        text = "替换",
                        style = AuroraTextStyles.body2.copy(
                            fontSize = 17.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            brush = replaceBrush
                        ),
                        modifier = Modifier
                            .clickable(enabled = enabledNext) {
                                dismissIme(); onReplace(findText, replaceText)
                            }
                            .padding(vertical = 5.dp)
                    )
                    val replaceAllBrush = remember(enabledNext) {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            if (enabledNext) listOf(
                                androidx.compose.ui.graphics.Color(0xFFB58CFF),
                                androidx.compose.ui.graphics.Color(0xFF7C6BFF)
                            ) else listOf(
                                androidx.compose.ui.graphics.Color(0x80B58CFF),
                                androidx.compose.ui.graphics.Color(0x807C6BFF)
                            )
                        )
                    }
                    Text(
                        text = "全部替换",
                        style = AuroraTextStyles.body2.copy(
                            fontSize = 17.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            brush = replaceAllBrush
                        ),
                        modifier = Modifier
                            .clickable(enabled = enabledNext) {
                                dismissIme(); onReplaceAll(findText, replaceText)
                            }
                            .padding(vertical = 5.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    // 关闭：偏红渐变（同时收起输入法），字号 +5
                    Text(
                        text = "✕ 关闭",
                        style = AuroraTextStyles.body2.copy(
                            fontSize = 17.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            brush = closeBrush
                        ),
                        modifier = Modifier
                            .clickable { dismissIme(); onDismiss() }
                            .padding(vertical = 5.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  EditorSettingsDialog — 紧凑纯文本设置面板
//  规格（用户定制）：
//  · 入口项：编码 / 换行 / 另存为 / 显示行号 / 字号 / 自动保存 / 全文统计
//  · 全部控件禁止矩形背景（无 background 色块、无 Button），一律纯文本 + 贴边紧凑行
//  · 编码 / 换行以页内二级列表展开（不复套弹窗），选中项右侧 ✓
//  · 全文统计：仅在「打开设置面板」时计算一次（非实时，不随输入重算），
//    输出 英文 / 中文 / 数字 三项，计算在 Dispatchers.Default 执行
// ═══════════════════════════════════════════════════════════════
@Composable
private fun EditorSettingsDialog(
    text: String,
    showLineNumber: Boolean, onShowLineNumberChange: (Boolean) -> Unit,
    fontSize: Float, onFontSizeChange: (Float) -> Unit,
    autoSaveSeconds: Int, onAutoSaveChange: (Int) -> Unit,
    charset: Charset, onCharsetChange: (Charset) -> Unit,
    lineEnding: LineEnding, onLineEndingChange: (LineEnding) -> Unit,
    onSaveAsClick: () -> Unit, onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf("root") }          // root | charset | lineEnding
    var statResult by remember { mutableStateOf<TextStatistics.Stats?>(null) }
    var statBusy by remember { mutableStateOf(false) }

    // 打开设置面板时统计一次（点击「设置」即触发），此后不随文本变化重算
    LaunchedEffect(Unit) {
        statBusy = true
        val r = withContext(Dispatchers.Default) { TextStatistics.compute(text) }
        statResult = r
        statBusy = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AuroraTokens.DialogBg, shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, AuroraTokens.Stroke),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (page) {
                            "charset" -> "编码"
                            "lineEnding" -> "换行"
                            else -> "设置"
                        },
                        style = AuroraTextStyles.title3, color = AuroraTokens.Text
                    )
                    Text(
                        text = "✕", style = AuroraTextStyles.body2, color = AuroraTokens.TextSecondary,
                        modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                    )
                }
                HorizontalDivider(color = AuroraTokens.Stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))

                when (page) {
                    // ── 编码：二级列表 ──
                    "charset" -> {
                        EDITOR_CHARSETS.forEach { (cs, name) ->
                            CompactOptionRow(
                                text = name, selected = cs == charset,
                                onClick = { onCharsetChange(cs); page = "root" }
                            )
                        }
                        CompactBackRow { page = "root" }
                    }
                    // ── 换行：二级列表 ──
                    "lineEnding" -> {
                        LineEnding.entries.forEach { le ->
                            CompactOptionRow(
                                text = "${le.displayName()}  ${le.desc()}", selected = le == lineEnding,
                                onClick = { onLineEndingChange(le); page = "root" }
                            )
                        }
                        CompactBackRow { page = "root" }
                    }
                    // ── 根页 ──
                    else -> {
                        CompactSettingRow("编码", charset.displayName()) { page = "charset" }
                        CompactSettingRow("换行", lineEnding.displayName()) { page = "lineEnding" }
                        CompactSettingRow("另存为", "›") { onSaveAsClick(); onDismiss() }
                        CompactSettingRow("显示行号", if (showLineNumber) "开" else "关") {
                            onShowLineNumberChange(!showLineNumber)
                        }
                        // 字号：纯文本档位
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("字号", style = AuroraTextStyles.body2, color = AuroraTokens.Text)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(10, 12, 14, 16, 18, 20, 24).forEach { sz ->
                                    Text(
                                        text = "$sz",
                                        style = AuroraTextStyles.footnote1,
                                        color = if (sz == fontSize.toInt()) AuroraTokens.Accent else AuroraTokens.TextSecondary,
                                        modifier = Modifier.clickable { onFontSizeChange(sz.toFloat()) }
                                    )
                                }
                            }
                        }
                        // 自动保存：纯文本档位
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("自动保存", style = AuroraTextStyles.body2, color = AuroraTokens.Text)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf(0, 30, 60, 120, 300).forEach { sec ->
                                    Text(
                                        text = "${sec}s",
                                        style = AuroraTextStyles.footnote1,
                                        color = if (sec == autoSaveSeconds) AuroraTokens.Accent else AuroraTokens.TextSecondary,
                                        modifier = Modifier.clickable { onAutoSaveChange(sec) }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = AuroraTokens.Stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                        // ── 全文统计：打开设置面板时已统计一次，此处只展示 ──
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("全文统计", style = AuroraTextStyles.body2, color = AuroraTokens.Text)
                        }
                        val r = statResult
                        if (r != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                StatItem("英文", "${r.english}")
                                StatItem("中文", "${r.chinese}")
                                StatItem("数字", "${r.digits}")
                            }
                        } else if (statBusy) {
                            Text(
                                "统计中…", style = AuroraTextStyles.footnote2,
                                color = AuroraTokens.TextSecondary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  DiffModeDialog — 对比模式选择（紧凑纯文本，无矩形背景）
// ═══════════════════════════════════════════════════════════════
@Composable
private fun DiffModeDialog(
    fileA: String, fileB: String,
    onDismiss: () -> Unit, onConfirm: (TextCompare.Mode) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AuroraTokens.DialogBg, shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, AuroraTokens.Stroke),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("选择对比方式", style = AuroraTextStyles.title3, color = AuroraTokens.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${File(fileA).name}  ↔  ${File(fileB).name}",
                    style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider(color = AuroraTokens.Stroke, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                TextCompare.Mode.entries.forEach { mode ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(mode) }
                            .padding(vertical = 7.dp)
                    ) {
                        Text(mode.displayName, style = AuroraTextStyles.body2, color = AuroraTokens.Text)
                        Text(mode.desc, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "取消", style = AuroraTextStyles.footnote1, color = AuroraTokens.TextSecondary,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 7.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  DiffProgressDialog — 对比进度（可取消）
// ═══════════════════════════════════════════════════════════════
@Composable
private fun DiffProgressDialog(progressLines: Long, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AuroraTokens.DialogBg, shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, AuroraTokens.Stroke),
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("正在对比…", style = AuroraTextStyles.title3, color = AuroraTokens.Text)
                Spacer(Modifier.height(6.dp))
                Text(
                    "已处理 $progressLines 行",
                    style = AuroraTextStyles.footnote1,
                    color = AuroraTokens.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "取消", style = AuroraTextStyles.footnote1, color = AuroraTokens.Error,
                    modifier = Modifier.clickable(onClick = onCancel).padding(vertical = 6.dp)
                )
            }
        }
    }
}

/** 紧凑设置行：左侧标签、右侧值，无矩形背景。 */
@Composable
private fun CompactSettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = AuroraTextStyles.body2, color = AuroraTokens.Text)
        Text(
            text = value, style = AuroraTextStyles.footnote1,
            color = AuroraTokens.TextSecondary, fontFamily = FontFamily.Monospace
        )
    }
}

/** 紧凑选项行：选中项右侧 ✓，无矩形背景。 */
@Composable
private fun CompactOptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text, style = AuroraTextStyles.body2,
            color = if (selected) AuroraTokens.Accent else AuroraTokens.Text
        )
        if (selected) Text("✓", style = AuroraTextStyles.body2, color = AuroraTokens.Accent)
    }
}

/** 紧凑返回行。 */
@Composable
private fun CompactBackRow(onClick: () -> Unit) {
    Text(
        text = "‹ 返回", style = AuroraTextStyles.footnote1, color = AuroraTokens.Accent,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 7.dp)
    )
}

/** 统计数值项。 */
@Composable
private fun StatItem(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
        Text(value, style = AuroraTextStyles.footnote1, color = AuroraTokens.Text, fontFamily = FontFamily.Monospace)
    }
}

// ═══════════════════════════════════════════════════════════════
//  HistoryDialog — 类 git 版本历史（可回退 20 个版本）
//  · 每条历史 = 一个版本快照（时间 + 行数 + 预览）
//  · 点版本 → 回退到该版本（当前内容自动存为新版本，可再撤回）
//  · 最新版本在顶部
// ═══════════════════════════════════════════════════════════════
@Composable
private fun HistoryDialog(
    history: List<EditHistoryManager.HistoryEntry>,
    onRestore: (EditHistoryManager.HistoryEntry) -> Unit,
    onClearAll: () -> Unit, onDismiss: () -> Unit
) {
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("版本历史 (${history.size}/20)", style = AuroraTextStyles.title3)
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) { Text("清空", color = AuroraTokens.Error) }
                }
            }
        },
        text = {
            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无历史记录", style = AuroraTextStyles.body1, color = AuroraTokens.TextSecondary)
                        Text("编辑内容后自动记录版本（每 2.5 秒停顿/保存时）", style = AuroraTextStyles.footnote2, color = AuroraTokens.TextHint, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    itemsIndexed(history) { index, entry ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { onRestore(entry) }.padding(vertical = 8.dp, horizontal = 8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = if (index == 0) "最新" else "v${history.size - index}",
                                    style = AuroraTextStyles.footnote2, color = if (index == 0) AuroraTokens.Success else AuroraTokens.TextSecondary
                                )
                                Text(text = "${entry.content.lineSequence().count()} 行", style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
                            }
                            Text(text = df.format(Date(entry.timestamp)), style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
                            Text(
                                text = entry.content.take(80).replace("\n", " "),
                                style = AuroraTextStyles.body2, color = AuroraTokens.Text, maxLines = 2
                            )
                        }
                        androidx.compose.material3.HorizontalDivider(color = AuroraTokens.Stroke, thickness = 0.3.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ═══════════════════════════════════════════════════════════════
//  SaveAsDialog
// ═══════════════════════════════════════════════════════════════
@Composable
private fun SaveAsDialog(
    initialDirectory: String, onSave: (String) -> Unit, onDismiss: () -> Unit
) {
    var fileName by remember { mutableStateOf("") }
    var extension by remember { mutableStateOf("txt") }
    var dirPath by remember { mutableStateOf(initialDirectory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("另存为", style = AuroraTextStyles.title3) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = dirPath, onValueChange = { dirPath = it }, label = { Text("目录路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = fileName, onValueChange = { fileName = it }, label = { Text("文件名") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextField(value = extension, onValueChange = { extension = it.filter { c -> c.isLetterOrDigit() } }, label = { Text("扩展名") }, singleLine = true, modifier = Modifier.width(80.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val name = if (fileName.endsWith(".$extension")) fileName else "$fileName.$extension"
                    val fullPath = if (dirPath.endsWith("/") || dirPath.endsWith("\\")) "$dirPath$name" else "$dirPath/$name"
                    onSave(fullPath)
                },
                enabled = fileName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ═══════════════════════════════════════════════════════════════
//  UnsavedChangesDialog
// ═══════════════════════════════════════════════════════════════
@Composable
private fun UnsavedChangesDialog(
    onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("未保存的更改", style = AuroraTextStyles.title3) },
        text = { Text("文件有未保存的更改，是否保存？", style = AuroraTextStyles.body1, color = AuroraTokens.TextSecondary) },
        confirmButton = { Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)) { Text("保存") } },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDiscard) { Text("放弃") }
            TextButton(onClick = onCancel) { Text("取消") }
        }}
    )
}

// ═══════════════════════════════════════════════════════════════
//  LineEnding 扩展
// ═══════════════════════════════════════════════════════════════
private fun LineEnding.displayName(): String = when (this) {
    LineEnding.LF -> "LF"
    LineEnding.CRLF -> "CRLF"
    LineEnding.CR -> "CR"
}

private fun LineEnding.desc(): String = when (this) {
    LineEnding.LF -> "Unix / Linux / macOS"
    LineEnding.CRLF -> "Windows"
    LineEnding.CR -> "旧 Mac OS"
}

