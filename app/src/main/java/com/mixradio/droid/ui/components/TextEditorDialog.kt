// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.snapshotFlow
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
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import java.nio.charset.Charset
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.ChunkedFileReader
import com.mixradio.droid.data.SparseLineIndex
import com.mixradio.droid.data.TextEncoder
import com.mixradio.droid.data.IndexedLineProvider
import com.mixradio.droid.data.FileSizeClass
import com.mixradio.droid.data.syntax.SyntaxPackTags
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

//  TextEditorDialog — 文本编辑器完整版
//  支持：打开/编辑/保存/另存为/新建、大文件分段、未保存提醒、自动保存草稿、行号、
//  编辑历史、查找替换、字号调节、全文统计、编码检测+切换、换行风格保留。
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

    // Sora 编辑器（MP-Manager 同款引擎）：文本驻留在 CodeEditor 内部（行索引增量 Content），
    // **不回折 Compose State**。旧实现把整篇文本放进 TextFieldValue，每次按键都要 O(n) 拷贝 —— 大文本必然卡顿。
    val soraEditor = remember { com.mixradio.droid.ui.components.SoraEditorController() }
    // 内容变更计数：只递增计数、不携带文本，作为「惰性快照」的触发源。
    var textRevision by remember { mutableIntStateOf(0) }
    // 编辑器重置：仅在「加载 / 整体替换」时递增（连同 seed 文本），避免把逐键改动当成重置而清空用户输入。
    var editorResetKey by remember { mutableIntStateOf(0) }
    var editorSeedText by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(!isNewFile) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var currentCharset by remember { mutableStateOf(Charsets.UTF_8) }
    var currentLineEnding by remember { mutableStateOf(LineEnding.LF) }
    var hasBom by remember { mutableStateOf(false) }
    var overrideCharset by remember { mutableStateOf<Charset?>(null) }

    // 打开即可编辑，不区分「只读 / 编辑」（对齐 MP-Manager：其编辑器基于 Sora，无模式切换）。
    var dirty by remember { mutableStateOf(isNewFile) }

    /** 把编辑器当前全文同步到 [contentValue]（按需调用：保存 / 查找 / 对比 / 统计 / 历史）。 */
    fun syncSnapshot() {
        val t = soraEditor.text()
        if (t != contentValue.text) contentValue = TextFieldValue(t, TextRange(t.length))
    }

    /** 以 [newText] 重置编辑器内容（加载、整体替换、历史回退）。[markDirty]=false 用于刚载入的磁盘内容。 */
    fun setEditorContent(newText: String, markDirty: Boolean) {
        editorSeedText = newText
        editorResetKey++
        contentValue = TextFieldValue(newText, TextRange(newText.length))
        // 统计/历史的触发源是 textRevision：程序化换文同样要递增，
        // 否则打开文件后统计不刷新，状态栏行数恒为 0（直到用户敲键）。
        textRevision++
        if (markDirty) dirty = true
    }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }

    var fileTotalBytes by remember { mutableLongStateOf(0L) }
    var isLargeFile by remember { mutableStateOf(false) }
    // 文件体积档位（对齐 MP-Manager FileSizeClass §6.2/§9.1）：驱动「只读虚拟滚动」与 HUGE 只读预览策略。
    var fileSizeClass by remember { mutableStateOf(FileSizeClass.SMALL) }
    // 大文件分段模式：append-only 行列表，避免每次 load 都重新 split 整个累积文本
    //（那样会触发 O(N²) 扫描 + 重新分配新 List）；此处只追加新行，整体 split 降到 O(N) 线性。
    var chunkedLines by remember { mutableStateOf(listOf<String>()) }
    var chunkedOffset by remember { mutableLongStateOf(0L) }
    var chunkedHasMore by remember { mutableStateOf(false) }
    // 稀疏行索引（巨型文件只读虚拟滚动）：建立后接管行渲染；重新加载时清空。
    var chunkedIndex by remember { mutableStateOf<SparseLineIndex?>(null) }
    var chunkedIndexing by remember { mutableStateOf(false) }

    var history by remember { mutableStateOf<List<EditHistoryManager.HistoryEntry>>(emptyList()) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSyntaxPacks by remember { mutableStateOf(false) }
    /** 语法包变更计数：变化时编辑器重新取语法（导入/删除语法包后即时生效）。 */
    var syntaxRevision by remember { mutableIntStateOf(0) }
    var showFindReplaceDialog by remember { mutableStateOf(false) }
    /** 当前 Sora 检索词：查找词变化时重新检索，否则跳下一个匹配。 */
    var findQuery by remember { mutableStateOf("") }

    // 文本对比流程状态：选文件 → 选模式 → 执行（带进度/取消）
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

    // 文本统计：按文件体积分支，避免大文本连续按键时反复扫描浪费 CPU。
    //   - 小文件（≤ 64KB）：单次 compute < 5ms，直接在后台同步算，最新一帧 UI 即看到准确数字。
    //   - 大文件（> 64KB）：compute 是单遍全字段扫描 + toByteArray，全量代价线性增长；
    //     若不加防抖，按 launch 重启时每个字符仍触发扫描（且 LaunchedEffect 仅取消旧 launch
    //     不取消已 enqueue 的 compute）。先 delay(600ms) 等连按结束，期间用最新文本比对短路。
    val statsEmpty = TextStatistics.Stats(0, 0, 0, 0, 0, 0, 0, 0)
    var stats by remember { mutableStateOf(statsEmpty) }
    LaunchedEffect(Unit) {
        // 触发源是 textRevision（只递增计数），文本按需从 Sora 读取 —— 不再逐键把整串折进 Compose State。
        snapshotFlow { textRevision }.collectLatest {
            if (textRevision == 0) { stats = statsEmpty; return@collectLatest }
            // 防抖：连按结束后再取一次文本统计。
            delay(600L)
            val t = soraEditor.text()
            if (t.isEmpty()) { stats = statsEmpty; return@collectLatest }
            // 超大文本放弃统计：compute 单遍全字段扫描 + toByteArray，大文本每次输入都跑会拖慢输入。
            if (t.length > LARGE_EDIT_STATS_SKIP_CHARS) return@collectLatest
            stats = withContext(Dispatchers.Default) { TextStatistics.compute(t) }
        }
    }

    fun replaceEditorText(newText: String) {
        if (newText != soraEditor.text()) setEditorContent(newText, markDirty = true)
    }

    // 加载文件
    LaunchedEffect(initialFilePath, isNewFile, overrideCharset) {
        if (isNewFile) { isLoading = false; return@LaunchedEffect }
        val path = initialFilePath ?: return@LaunchedEffect
        isLoading = true
        loadError = null
        val openStartMs = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            try {
                val total = ChunkedFileReader.fileSize(path)
                fileTotalBytes = total
                fileSizeClass = FileSizeClass.of(total)
                // 重新加载作废旧行索引：编码/内容已变，旧偏移不再有效。
                chunkedIndex = null
                chunkedIndexing = false
                // 打开即可编辑（对齐 MP-Manager）：未超可载入上限的文件一律全文载入 Sora 编辑器；
                // 仅超过上限的巨型文件退回「稀疏行索引只读浏览」（全文入内存会 OOM，无法编辑）。
                val editable = total <= ChunkedFileReader.MAX_LOAD_BYTES
                isLargeFile = !editable          // 仅表示「巨型只读浏览」，驱动分段 UI
                if (editable) {
                    val load = ChunkedFileReader.loadAll(path)
                    currentCharset = overrideCharset ?: load.charset
                    hasBom = load.hasBom
                    currentLineEnding = LineEnding.detect(load.text)
                    // 统一归一为 LF 再交给编辑器（Sora 的 Content 亦按 '\n' 断行）；保存时按 currentLineEnding 还原。
                    val normalized = LineEnding.apply(load.text, LineEnding.LF)
                    setEditorContent(normalized, markDirty = false)
                    // 刚载入的内容与磁盘一致：显式清零脏标记（setText 派发的变更事件可能已把它置脏）。
                    dirty = false
                    chunkedLines = emptyList()
                    chunkedOffset = total
                    chunkedHasMore = false
                } else {
                    val raw = ChunkedFileReader.readHead(path, ChunkedFileReader.CHUNK_BYTES.toInt())
                    val detected = CharsetDetector.detect(raw)
                    // 首块也要对齐到完整行：否则会在行中间切断（同一逻辑行裂成两行、行号错位），
                    // 或在多字节字符中间切断（出现替换符）。UTF-16 下按 0x0A 扫描不安全，跳过对齐。
                    val alignedEnd =
                        if (isUtf16Charset(overrideCharset ?: detected.charset)) -1
                        else ChunkedFileReader.lastCompleteLineEnd(raw)
                    val det = if (alignedEnd >= 0) CharsetDetector.detect(raw.copyOf(alignedEnd)) else detected
                    currentCharset = overrideCharset ?: det.charset
                    hasBom = det.hasBom
                    currentLineEnding = LineEnding.detect(det.text)
                    // 首次 load：head 全量 split（单次 O(N)，可接受）。后续 loadMore 仅追加。
                    val headText = LineEnding.apply(det.text, LineEnding.LF)
                    chunkedLines = headText.split('\n').let { l ->
                        if (l.isNotEmpty() && l.last().isEmpty()) l.dropLast(1) else l
                    }
                    chunkedOffset = (if (alignedEnd >= 0) alignedEnd else raw.size).toLong()
                    chunkedHasMore = chunkedOffset < total
                    // 后台建立稀疏行索引，接管行渲染，释放 chunkedLines 的无限累积（防滚到底 OOM）。
                    chunkedIndexing = true
                    val built = SparseLineIndex.build(path, currentCharset)
                    if (built != null) {
                        chunkedIndex = built
                        chunkedLines = emptyList()   // 索引接管后不再需要首块行列表
                        chunkedOffset = total
                        chunkedHasMore = false
                    }
                    chunkedIndexing = false
                }
                history = EditHistoryManager.getHistory(path)
                if (com.mixradio.droid.BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "shso-perf",
                        "editor open $path size=$total cost=${System.currentTimeMillis() - openStartMs}ms"
                    )
                }
            } catch (e: Exception) { loadError = "读取失败: ${e.message}" }
            finally { isLoading = false }
        }
    }

    // 类 git 自动快照：编辑停顿 2.5s 且内容与最近版本不同 → 自动记录一条历史。
    // 保存(手动/另存为)时也会记录。上限 20 条由 EditHistoryManager 淘汰最旧。
    LaunchedEffect(textRevision, currentFilePath) {
        if (currentFilePath == null || !dirty) return@LaunchedEffect
        delay(2500L)
        val path = currentFilePath ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val t = soraEditor.text()
            val latest = EditHistoryManager.getHistory(path).firstOrNull()
            if (latest == null || latest.content != t) {
                EditHistoryManager.addHistory(path, t)
                history = EditHistoryManager.getHistory(path)
            }
        }
    }

    DisposableEffect(initialFilePath) {
        onDispose {
            // 历史持久保留（类 git 仓库），关闭编辑器不清空——否则只剩一条历史。
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
                    val t = soraEditor.text()
                    EditHistoryManager.addHistory(currentFilePath!!, t)
                    history = EditHistoryManager.getHistory(currentFilePath!!)
                }
            }
        }
    }

    // 语法匹配用**完整文件名**：语法包按扩展名匹配（cpp/rs/py…），也按无扩展名的常见文件名匹配
    // （Dockerfile / CMakeLists.txt / Makefile），故不经过 CodeHighlighter 的扩展名枚举。
    val grammarFileName by remember(currentFilePath) {
        derivedStateOf { currentFilePath?.let { File(it).name } }
    }
    // 顶栏语言名：优先取已导入语法包（覆盖 187 个扩展名 + 无扩展名文件），
    // 未导入时退回内置枚举；两者都没有则不显示徽标。
    val languageLabel by remember(currentFilePath, syntaxRevision) {
        derivedStateOf {
            SyntaxPackTags.displayNameFor(context, grammarFileName)
                ?: currentFilePath?.let { CodeHighlighter.languageOf(File(it).name)?.displayName }
        }
    }
    // 语法高亮改由编辑器引擎自绘（Sora 的可视区增量高亮），不再走 Compose `VisualTransformation`：
    // 旧方案需对全文做 AnnotatedString 计算且与输入文本逐帧校验，大文本是纯开销。
    val doSave: () -> Unit = save@{
        // 连点保护：保存中忽略后续点击。保存本身是幂等的，多点几次不会产生额外写入，
        // 但并发写同一文件会出现「后写的覆盖先写的」，故直接忽略进行中的重复请求。
        if (isSaving) return@save
        // 落盘前强制从编辑器取一次全文：contentValue 是惰性快照，刚输入完可能还没同步。
        syncSnapshot()
        when {
            // 顺序即优先级，覆盖所有组合：
            // ⓪ 加载中：contentValue 可能仍为空或只有首块，此时写盘必然损坏文件
            isLoading -> toastMessage = "正在加载，请稍候再保存"
            // ① 读取失败 → 编辑器里的内容不是完整原文，保存会截断/覆盖原文件
            loadError != null -> toastMessage = "文件读取失败，已阻止保存以防损坏原文件"
            // ② 巨型文件（只读浏览）未载入全文 → contentValue 为空或只有首块，保存会截断文件
            isLargeFile -> toastMessage = "文件超过可编辑上限，仅支持只读浏览，无法保存"
            // ③ 新建文件（无路径）：需先确定保存位置
            currentFilePath == null -> showSaveAsDialog = true
            // ④ 内容未变
            !dirty -> toastMessage = "无改动"
            else -> {
                isSaving = true
                scope.launch {
                    val path = currentFilePath!!
                    // 成功: Triple(写盘结果, 错误信息, 最新历史)；历史读写一并放入 IO 线程
                    val outcome = withContext(Dispatchers.IO) {
                        val writeResult = writeTextFile(path, contentValue.text, currentCharset, currentLineEnding, hasBom)
                        if (writeResult.first) {
                            EditHistoryManager.addHistory(path, contentValue.text)
                            Triple(true, null as String?, EditHistoryManager.getHistory(path))
                        } else Triple(false, writeResult.second, emptyList())
                    }
                    isSaving = false
                    if (outcome.first) {
                        dirty = false; lastSavedAtMs = System.currentTimeMillis()
                        history = outcome.third
                        toastMessage = "已保存"
                        // 每次保存成功都强制置空 saveMessage，确保状态栏只剩最新的有效提示
                        //（否则上一次失败的红字提示会在恢复后依旧停留）。
                        saveMessage = null
                    } else { saveMessage = outcome.second ?: "保存失败" }
                }
            }
        }
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); toastMessage = null }
    }

    val dialogHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    Dialog(
        onDismissRequest = {
            // 关闭前先取一次最新全文：未保存对话框的「保存并关闭」直接读 contentValue。
            syncSnapshot()
            if (dirty) showUnsavedDialog = true else onDismissRequest()
        },
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
                    dirty = dirty,
                    languageLabel = languageLabel, hasBom = hasBom,
                    onSettingsClick = { syncSnapshot(); showSettingsDialog = true },
                    onFindClick = {
                        // 巨型文件走只读浏览，正文未载入内存，查找必然无效，先说明原因。
                        if (isLargeFile) {
                            toastMessage = "文件超过可编辑上限，仅支持只读浏览，无法查找"
                        } else {
                            // contentValue 是惰性快照：打开查找前先取一次最新全文，避免用陈旧文本。
                            syncSnapshot()
                            showFindReplaceDialog = true
                        }
                    },
                    onCompareClick = {
                        if (currentFilePath == null) {
                            toastMessage = "请先保存文件后再对比"
                        } else if (isLargeFile) {
                            // 只读浏览态正文只在 chunkedLines 里，contentValue 为空，比对结果无意义。
                            toastMessage = "文件超过可编辑上限，仅支持只读浏览，无法对比"
                        } else {
                            showDiffPicker = true
                        }
                    },
                    onSaveClick = doSave,
                    onHistoryClick = { syncSnapshot(); showHistoryDialog = true },
                    historyCount = history.size,
                    onDismissRequest = {
                        syncSnapshot()
                        if (dirty) showUnsavedDialog = true else onDismissRequest()
                    }
                )

                // 大文件提示条
                if (isLargeFile) {
                    ChunkedInfoBar(
                        offset = chunkedOffset, total = fileTotalBytes, hasMore = chunkedHasMore,
                        indexing = chunkedIndexing, indexedLines = chunkedIndex?.totalLines,
                        sizeClass = fileSizeClass,
                        onLoadMore = {
                            // 已建立稀疏行索引后全文件虚拟滚动，无需再分段累积行列表。
                            if (chunkedIndex != null) return@ChunkedInfoBar
                            scope.launch {
                                loadNextChunk(
                                    filePath = currentFilePath ?: return@launch, fromOffset = chunkedOffset,
                                    charset = currentCharset,
                                    onResult = { text, newOffset, hasMore ->
                                        // 仅追加新行到行列表，不重建旧行 → O(chunkSize) 而非 O(totalLoaded)。
                                        // 行尾 \n 处理：「abc\n」split 后得到 ["abc", ""]，这里再 drop 末端空行
                                        // 以保证行号与文件字节偏移一致（每行自带一个 \n）。
                                        val newLines = text.split('\n').let { l ->
                                            if (l.isNotEmpty() && l.last().isEmpty()) l.dropLast(1) else l
                                        }
                                        chunkedLines = if (newLines.isEmpty()) chunkedLines
                                                       else chunkedLines + newLines
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
                        else -> if (isLargeFile) {
                            // 巨型文件（> 可载入上限）：稀疏行索引只读浏览。全文入内存会 OOM，无法编辑。
                            ChunkedReadOnlyArea(
                                chunkedLines = chunkedLines,
                                chunkedIndex = chunkedIndex,
                                chunkedFilePath = currentFilePath ?: "",
                                chunkedCharset = currentCharset,
                                showLineNumber = showLineNumber,
                                fontSize = fontSize.sp
                            )
                        } else {
                            // 打开即可编辑：单一 Sora 编辑器（MP-Manager 同款引擎），文本驻留在其内部。
                            // onChanged 只置脏并递增修订号 —— 不回传整串，避免逐键 O(n) 拷贝。
                            com.mixradio.droid.ui.components.SoraTextEditor(
                                initialText = editorSeedText,
                                resetKey = editorResetKey,
                                fontSize = fontSize.sp,
                                showLineNumbers = showLineNumber,
                                fileName = grammarFileName,
                                syntaxRevision = syntaxRevision,
                                controller = soraEditor,
                                onChanged = { dirty = true; textRevision++ }
                            )
                        }
                    }
                }

                // 状态栏
                // 超大文本跳过全字段统计（见 LARGE_EDIT_STATS_SKIP_CHARS），此时 stats 不更新，
                // 直接把 0 行显示给用户是错的。行数改从编辑器直接取（Content 的长度与行数均为 O(1)），
                // 以 textRevision 为触发源，编辑后即时正确，且不再依赖可能陈旧的 contentValue 快照。
                val bigTextLineCount = remember(textRevision) {
                    if (soraEditor.charCount() > LARGE_EDIT_STATS_SKIP_CHARS) soraEditor.lineCount() else null
                }
                EditorStatusBar(
                    stats = bigTextLineCount?.let { statsEmpty.copy(lines = it) } ?: stats,
                    chunkedMode = isLargeFile,
                    statsSkipped = bigTextLineCount != null,
                    fileTotalBytes = fileTotalBytes, chunkedOffset = chunkedOffset,
                    // 大文件模式下 contentValue 为空，行数取自分段计数；建立稀疏行索引后取索引总行数。
                    chunkedLineCount = chunkedIndex?.totalLines ?: chunkedLines.size,
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
        onTextChange = { newText ->
            replaceEditorText(newText)
        },
        showLineNumber = showLineNumber,
        onShowLineNumberChange = { showLineNumber = it; appSettings.updateEditorShowLineNumber(it) },
        hasBom = hasBom, onHasBomChange = { hasBom = it },
        chunkedBrowsing = isLargeFile,
        fontSize = fontSize, onFontSizeChange = { fontSize = it; appSettings.updateEditorFontSize(it) },
        autoSaveSeconds = autoSaveSeconds,
        onAutoSaveChange = { autoSaveSeconds = it; appSettings.updateEditorAutoSaveInterval(it) },
        charset = currentCharset,
        onCharsetChange = { cs ->
            // 切换编码 = 丢弃当前内存内容、按新编码从磁盘重新解码。
            // 有未保存修改时必须阻断：否则重载后编辑被静默覆盖；
            // 若重载失败，内容为空而 dirty 仍为 true，保存会把文件截断为 0 字节。
            if (!isNewFile && dirty) {
                toastMessage = "有未保存的修改，请先保存后再切换编码"
            } else if (cs != currentCharset) {
                currentCharset = cs; overrideCharset = cs
                if (!isNewFile) { setEditorContent("", markDirty = false); isLoading = true }
            }
        },
        lineEnding = currentLineEnding,
        onLineEndingChange = { le -> currentLineEnding = le },
        onSyntaxPacksClick = { showSyntaxPacks = true },
        onSaveAsClick = { syncSnapshot(); showSaveAsDialog = true },
        onDismiss = { showSettingsDialog = false }
    )

    // 语法包管理：导入/删除后使语法缓存失效并触发编辑器重新取语法。
    if (showSyntaxPacks) SyntaxPackDialog(
        show = true,
        onDismissRequest = { showSyntaxPacks = false },
        onChanged = {
            SoraMonarchGrammars.invalidate(context)
            syntaxRevision++
        }
    )

    if (showFindReplaceDialog) FindReplaceDialog(
        text = contentValue.text,
        onFindNext = { findText ->
            // 查找/替换交给 Sora 内置检索器：异步全文匹配 + 命中高亮 + 自动滚动到命中处。
            if (findText.isNotEmpty()) {
                if (findText != findQuery) {
                    findQuery = findText
                    soraEditor.search(findText, caseInsensitive = false)
                } else {
                    soraEditor.gotoNextMatch()
                }
            }
        },
        onReplace = { original, replacement ->
            if (findQuery != original) {
                findQuery = original
                soraEditor.search(original, caseInsensitive = false)
            }
            soraEditor.replaceCurrentMatch(replacement)
            dirty = true; textRevision++
        },
        onReplaceAll = { original, replacement ->
            if (findQuery != original) {
                findQuery = original
                soraEditor.search(original, caseInsensitive = false)
            }
            soraEditor.replaceAll(replacement)
            dirty = true; textRevision++
        },
        onDismiss = { soraEditor.stopSearch(); showFindReplaceDialog = false }
    )

    // 文本对比 ① 选择 2 号文件（复用主页选择器，注入「同后缀 + 排除自身」过滤）
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

    // 文本对比 ② 选择对比模式
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

    // 文本对比 ③ 进度（可取消）
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
                setEditorContent(entry.content, markDirty = true)
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
            // 巨型文件（只读浏览）未载入全文，另存出去会得到残缺文件。
            if (isLargeFile) {
                toastMessage = "文件超过可编辑上限，仅支持只读浏览，无法另存为"
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

//  工具函数

/**
 * 大文件读下一段（追加加载）。
 *
 * 读取区间必须对齐到完整行边界：按固定字节推进会在行中间切断（行号错乱），
 * 也会在多字节字符中间切断（出现替换符）。
 * 只解码到本块最后一个 '
'，offset 推进到该换行之后，不足一行的尾字节留到下一块重读。
 */
private suspend fun loadNextChunk(
    filePath: String, fromOffset: Long, charset: java.nio.charset.Charset,
    onResult: (text: String, newOffset: Long, hasMore: Boolean) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val total = ChunkedFileReader.fileSize(filePath)
        val raw = ChunkedFileReader.readRange(filePath, fromOffset, ChunkedFileReader.CHUNK_BYTES)
        if (raw.isEmpty()) {
            onResult("", fromOffset, false)
            return@withContext
        }
        val alignedEnd =
            if (isUtf16Charset(charset)) -1 else ChunkedFileReader.lastCompleteLineEnd(raw)
        val consumedLen = if (alignedEnd >= 0) alignedEnd else raw.size
        val bytes = if (alignedEnd >= 0) raw.copyOf(consumedLen) else raw
        val text = LineEnding.apply(String(bytes, charset), LineEnding.LF)
        val newOffset = fromOffset + consumedLen
        onResult(text, newOffset, newOffset < total)
    } catch (_: Throwable) {
        onResult("", fromOffset, false)
    }
}

/** UTF-16 系列编码下不能按单个 0x0A 字节做行对齐（换行是 2 字节，且 0x0A 可能出现在别的码元里）。 */
private fun isUtf16Charset(cs: java.nio.charset.Charset): Boolean =
    cs == Charsets.UTF_16 || cs == Charsets.UTF_16LE || cs == Charsets.UTF_16BE

/**
 * 依据 `stat -c '%a %u %g'` 的输出，生成「覆盖写入后还原权限与属主」的 root 命令。
 * 返回 null 表示输出不可用（此时不改动权限，保持现状，避免误改）。
 * 纯函数，便于单测；三段均做数字校验，杜绝把 stat 输出意外拼进 shell 造成注入。
 */
internal fun buildRestoreAttrsCommand(statOutput: String, escapedPath: String): String? {
    val parts = statOutput.trim().split(Regex("\\s+"))
    if (parts.size < 3) return null
    val mode = parts[0]
    val uid = parts[1]
    val gid = parts[2]
    if (!mode.matches(Regex("[0-7]{1,4}"))) return null
    if (!uid.matches(Regex("\\d+")) || !gid.matches(Regex("\\d+"))) return null
    return "chmod $mode $escapedPath; chown $uid:$gid $escapedPath"
}

/**
 * 写入文本：**临时文件 + 同目录 rename** 覆盖，保证原子性；root 走 root shell。
 *
 * 三处必须遵守的约束：
 *  1. 原子性：临时文件必须与目标**同目录**（同文件系统），`renameTo`/`mv` 才是原子替换。
 *     直接 `writeBytes` 到目标会先截断原文件——写入中途失败（磁盘满/进程被杀）即丢失原内容；
 *     跨文件系统 `mv` 实为「复制 + 删除」，同样非原子。
 *  2. 权限与属主：替换后新文件的 mode/owner 来自临时文件，故写入前记录 `stat -c '%a %u %g'`、
 *     写入后还原，并尽力 `restorecon`；非 root 路径用 `Os.chmod` 还原原 mode。
 *  3. 符号链接：`mv`/`rename` 覆盖会把链接替换成普通文件，故先 `readlink -f` 解析真实路径（root 路径）。
 *
 * 编码安全：[charset] 无法表示的字符**必须报错**而不是静默替换为 `?`（后者会悄悄损坏文件）。
 */
private suspend fun writeTextFile(
    filePath: String, text: String, charset: java.nio.charset.Charset,
    lineEnding: LineEnding, writeBom: Boolean
): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
    try {
        val finalText = LineEnding.apply(text, lineEnding)
        // 严格编码：不可映射字符（如用 GBK/ISO-8859-1 保存时输入了该字符集没有的字符）直接失败，
        // 不能走 String.getBytes 的静默 `?` 替换——那会让用户在毫无提示的情况下丢内容。
        val bytes = TextEncoder.encode(finalText, charset, writeBom)
            .getOrElse { return@withContext Pair(false, it.message ?: "编码失败") }
        val suffix = "${System.nanoTime()}_${kotlin.random.Random.nextInt(1000, 9999)}"

        if (RootService.isRootGranted == true) {
            // 解析真实路径（软链写入真身，不替换链接）；readlink 不可用/非软链时退回原路径
            val (linkCode, linkOut) = RootService.runCommandSync(
                "readlink -f ${RootService.escapeShellArg(filePath)} 2>/dev/null", 10_000L
            )
            val resolved = if (linkCode == 0) linkOut.trim().ifEmpty { filePath } else filePath
            val escapedTarget = RootService.escapeShellArg(resolved)

            // 覆盖前记录原权限/属主，用于写入后还原
            val (statCode, statOut) = RootService.runCommandSync(
                "stat -c '%a %u %g' $escapedTarget 2>/dev/null", 10_000L
            )

            // 临时文件放在目标同目录：跨文件系统 mv 等于复制+删除，失败会留下半截文件
            val targetDir = resolved.substringBeforeLast('/', "/").ifEmpty { "/" }
            val tmpFile = "$targetDir/.shso_edit_$suffix.tmp"
            val escapedTmp = RootService.escapeShellArg(tmpFile)
            val writeOk = RootService.writeBytesAsRoot(tmpFile, bytes)
            if (!writeOk) return@withContext Pair(false, "写入临时文件失败")
            val (mvCode, mvOut) = RootService.runCommandSync("mv $escapedTmp $escapedTarget", 60_000L)
            if (mvCode != 0) {
                // 失败时清理临时文件，避免在系统目录留下垃圾
                RootService.runCommandSync("rm -f $escapedTmp", 10_000L)
                return@withContext Pair(false, "保存失败: ${mvOut.trim().ifEmpty { "未知错误" }}")
            }

            if (statCode == 0) {
                val restore = buildRestoreAttrsCommand(statOut, escapedTarget)
                if (restore != null) RootService.runCommandSync(restore, 30_000L)
            }
            // 尽力恢复 SELinux 上下文；失败不视为保存失败
            RootService.runCommandSync("restorecon $escapedTarget 2>/dev/null", 30_000L)
            Pair(true, null)
        } else {
            // 同目录临时文件 + rename：同文件系统内 rename 为原子替换，写失败不影响原文件
            val target = File(filePath)
            val parent = target.parentFile ?: return@withContext Pair(false, "无法确定目标目录")
            val tmp = File(parent, ".${target.name}.shso_$suffix.tmp")
            val originalMode = if (target.exists()) {
                runCatching { android.system.Os.stat(target.absolutePath).st_mode }.getOrNull()
            } else null
            try {
                tmp.writeBytes(bytes)
                if (originalMode != null) runCatching { android.system.Os.chmod(tmp.absolutePath, originalMode) }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return@withContext Pair(false, "保存失败: 无法替换原文件")
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            Pair(true, null)
        }
    } catch (e: Exception) { Pair(false, "保存失败: ${e.message}") }
}

//  EditorTopBar
@Composable
private fun EditorTopBar(
    fileName: String, dirty: Boolean,
    languageLabel: String?, hasBom: Boolean,
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
                if (languageLabel != null) {
                    Text(
                        text = languageLabel, style = AuroraTextStyles.footnote2,
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
            // 打开即可编辑（对齐 MP-Manager）：无「只读 / 编辑」切换按钮。
            EditorToolbarButton("查找", null, onFindClick)
            EditorToolbarButton("对比", null, onCompareClick)
            EditorToolbarButton("历史", if (historyCount > 0) "$historyCount" else null, onHistoryClick)
            EditorToolbarButton(
                "保存", null, onSaveClick,
                // 不做任何禁用：保存按钮始终可点，无改动或状态不满足时点击不产生副作用。
                // 有改动时用强调色提示「有内容待保存」。
                tint = if (dirty) AuroraTokens.Accent else AuroraTokens.Text
            )
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
    tint: androidx.compose.ui.graphics.Color = AuroraTokens.Text,
    /** 选中态：字体加粗，与未选中项形成明确对比（项目风格禁用背景色块，故靠字重 + 颜色区分）。 */
    emphasized: Boolean = false
) {
    Row(
        modifier = Modifier
            .background(AuroraTokens.SurfaceHover, RoundedCornerShape(0.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (emphasized) AuroraTextStyles.footnote2.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            ) else AuroraTextStyles.footnote2,
            color = tint
        )
        if (subLabel != null) {
            Text(text = subLabel, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
        }
    }
}

//  ChunkedInfoBar
@Composable
private fun ChunkedInfoBar(
    offset: Long, total: Long, hasMore: Boolean,
    /** 稀疏行索引建立中：提示用户正在建索引，期间不显示「加载更多」。 */
    indexing: Boolean = false,
    /** 已建立稀疏行索引时的总行数；非 null 表示进入虚拟只读浏览，无需分段累积。 */
    indexedLines: Int? = null,
    /** 文件体积档位（MP-Manager FileSizeClass §6.2/§9.1）：HUGE 显式标注「只读预览」策略。 */
    sizeClass: FileSizeClass = FileSizeClass.SMALL,
    onLoadMore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(AuroraTokens.SurfaceHover, RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            indexing -> Text(
                text = "正在建立全文行索引…",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
            )
            indexedLines != null -> Text(
                text = "已索引 · 共 ${indexedLines} 行（只读·虚拟滚动）",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
            )
            else -> Text(
                // 只显示加载进度：操作入口已在顶栏，此处再标注状态属重复。
                text = "分段加载 ${formatBytes(offset)} / ${formatBytes(total)}",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
            )
        }
        if (hasMore && indexedLines == null && !indexing) {
            Text(
                text = "加载更多 →", style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
                modifier = Modifier.clickable(onClick = onLoadMore).padding(4.dp)
            )
        } else if (indexedLines != null) {
            // LARGE/HUGE 由稀疏行索引只读虚拟滚动接管：显式标注只读预览策略（对齐 MP-Manager §7.1）。
            val browseHint = when (sizeClass) {
                FileSizeClass.HUGE -> "全文可浏览 · HUGE 只读预览"
                FileSizeClass.LARGE -> "全文可浏览 · 只读预览"
                else -> "全文可浏览"
            }
            Text(
                text = browseHint,
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    // MB 段保留一位小数：整除法会把 2.00MB(2097151B) 显示成「1 MB」，与文件列表的「2.00 MB」自相矛盾。
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

//  巨型文件只读浏览区
//  超过可载入上限（ChunkedFileReader.MAX_LOAD_BYTES）的文件无法全文入内存，只读渲染：
//  稀疏行索引已建立时走虚拟滚动（按行号按需 readRange），索引建立失败时退回已加载行的 LazyColumn。
@Composable
private fun ChunkedReadOnlyArea(
    chunkedLines: List<String>,
    chunkedIndex: SparseLineIndex?,
    chunkedFilePath: String,
    chunkedCharset: java.nio.charset.Charset,
    showLineNumber: Boolean, fontSize: androidx.compose.ui.unit.TextUnit
) {
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize()) {
        if (chunkedIndex != null && chunkedFilePath.isNotEmpty()) {
            // 稀疏行索引：按行号按需 readRange，内存恒定 O(窗口)，不会滚到底 OOM。
            IndexedChunkedView(
                index = chunkedIndex, filePath = chunkedFilePath, charset = chunkedCharset,
                showLineNumber = showLineNumber, fontSize = fontSize, scope = scope
            )
        } else {
            // 索引建立失败时的兜底：已加载行用 LazyColumn 懒加载渲染（append-only 行列表）。
            val lines = chunkedLines
            val lazyState = rememberLazyListState()
            LazyColumn(
                state = lazyState,
                modifier = Modifier.fillMaxSize().padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                itemsIndexed(lines, key = { idx, _ -> idx }) { index, line ->
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
        }
    }
}

/**
 * 超大文件只读虚拟滚动视图。
 *
 * 由 [SparseLineIndex] 驱动：LazyColumn 只布局可见行，每行文本经 [IndexedLineProvider]
 * 按行号按需 `readRange` + 按 charset 解码，结果缓存在有界 LRU 中。内存恒定 O(窗口)，
 * 与文档「稀疏行索引 + 虚拟滚动」一致，滚到底也不会把全文件行字符串累积进内存。
 */
@Composable
private fun IndexedChunkedView(
    index: SparseLineIndex, filePath: String, charset: java.nio.charset.Charset,
    showLineNumber: Boolean, fontSize: androidx.compose.ui.unit.TextUnit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val total = index.totalLines
    if (total <= 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("（空文件）", style = AuroraTextStyles.body2, color = AuroraTokens.TextSecondary)
        }
        return
    }
    val lazyState = rememberLazyListState()
    val provider = remember(index, filePath, charset) { IndexedLineProvider(index, filePath, charset) }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyState,
            modifier = Modifier.fillMaxSize().padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            items(total, key = { it }) { i ->
                // peek 命中 LRU 时首帧即有文本；否则 LaunchedEffect 后台加载后回填。
                var text by remember(i) { mutableStateOf(provider.peek(i)) }
                LaunchedEffect(i) { if (text == null) text = provider.load(i) }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (showLineNumber) {
                        Text(
                            text = "${i + 1}",
                            style = AuroraTextStyles.monospace.copy(fontSize = fontSize),
                            color = AuroraTokens.TextDisabled,
                            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
                        )
                    }
                    Text(
                        text = text ?: "",
                        style = AuroraTextStyles.monospace.copy(fontSize = fontSize),
                        color = AuroraTokens.Text,
                        modifier = Modifier.padding(end = 200.dp),
                        softWrap = false
                    )
                }
            }
        }
        LineScrollBar(
            lineCount = total,
            getFraction = {
                if (total <= 1) 0f else lazyState.firstVisibleItemIndex.toFloat() / (total - 1)
            },
            setFraction = { f ->
                scope.launch { lazyState.scrollToItem((f * (total - 1)).toInt().coerceAtLeast(0)) }
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

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
                        val thumbOffset = ((offset.y - thumbHPx / 2f).coerceIn(0f, maxTop)) / maxTop.coerceAtLeast(1f)
                        dragFrac = thumbOffset
                        setFractionState.value(thumbOffset)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val thumbOffset = ((change.position.y - thumbHPx / 2f).coerceIn(0f, maxTop)) / maxTop.coerceAtLeast(1f)
                        dragFrac = thumbOffset
                        setFractionState.value(thumbOffset)
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

//  EditorStatusBar
@Composable
private fun EditorStatusBar(
    stats: TextStatistics.Stats,
    /** 是否处于「分段浏览」态（大文件未载全文）：此时行数与字节数来自分段计数。 */
    chunkedMode: Boolean,
    fileTotalBytes: Long, chunkedOffset: Long,
    chunkedLineCount: Int = 0,
    /** 超大文本已跳过全字段统计：字节数无法给出可信值，显示占位而非 0。 */
    statsSkipped: Boolean = false,
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
                // 分段浏览用分段计数；编辑态（含已载全文的大文件）用统计行数。
                text = "行数 ${if (chunkedMode) chunkedLineCount else stats.lines}",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
            )
            Text(
                text = "字节数 " + when {
                    chunkedMode -> "${formatBytes(chunkedOffset)} / ${formatBytes(fileTotalBytes)}"
                    // 统计被跳过时给出占位：显示 0 会让用户以为文件是空的。
                    statsSkipped -> "—"
                    else -> "${stats.bytes}"
                },
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
/**
 * 超过该字符数时放弃文本统计与行号列。
 * 两者都是「按全文扫描」的开销（统计还要 toByteArray），在几十万字符的文本上收益远低于代价。
 */
private const val LARGE_EDIT_STATS_SKIP_CHARS = 200_000

/** 文本处理结果只剩 1 行时的「超长单行」提示阈值（字符）。 */
private const val VERY_LONG_SINGLE_LINE_CHARS = 64_000

/** 查找匹配计数的显示上限：超过后只统计到此值，避免极端命中时长时间全量扫描。 */
private const val MATCH_COUNT_LIMIT = 5_000

private val EDITOR_CHARSETS: List<Pair<Charset, String>> = listOf(
    Charsets.UTF_8 to "UTF-8", Charsets.UTF_16LE to "UTF-16 LE",
    Charsets.UTF_16BE to "UTF-16 BE", Charset.forName("GBK") to "GBK",
    Charset.forName("GB2312") to "GB2312", Charset.forName("GB18030") to "GB18030",
    Charsets.ISO_8859_1 to "ISO-8859-1", Charsets.US_ASCII to "ASCII"
).distinctBy { it.second }

//  FindReplaceDialog — 查找/替换（半透明紧凑版 v4）
//  · 40% 半透明暗色面板：能隐约看清底部编辑区文本；无标题、无矩形框
//  · 极光渐变彩色文字；关闭按钮偏红渐变
//  · 布局：查找框 → 替换框 → 动作行(查找下一个|替换|全部替换) → 关闭
//  · 点击动作按钮强制收起输入法（逻辑在 Dialog 内部，作用于 Dialog 自己的窗口）
// lint 误报：Int.toDrawable 用于资源 id，此处传入的是颜色值，无 KTX 等价写法。
@SuppressLint("UseKtx")
@Composable
private fun FindReplaceDialog(
    text: String,
    onFindNext: (findText: String) -> Unit,
    onReplace: (original: String, replacement: String) -> Unit,
    onReplaceAll: (original: String, replacement: String) -> Unit,
    onDismiss: () -> Unit
) {
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    // 匹配计数：放后台线程并防抖，且设上限。
    // 原实现用 remember 在组合期（主线程）跑全量 indexOf 循环 —— 文本可达数十万字符、
    // 且输入查找词的每个字符都会重跑一遍，直接表现为输入掉帧。
    // 上限用于拦住「极端高频命中」时无意义的继续扫描（只影响显示数字，不影响替换）。
    var matchCount by androidx.compose.runtime.mutableIntStateOf(0)
    LaunchedEffect(findText, text) {
        if (findText.isEmpty()) {
            matchCount = 0
            return@LaunchedEffect
        }
        delay(200L)
        matchCount = withContext(Dispatchers.Default) {
            // 用 indexOf 循环计数，避免 split 产生巨大临时 List/子串分配；
            // 步进 idx + findText.length 与原 split（非重叠）计数语义一致。
            var count = 0
            var idx = text.indexOf(findText)
            while (idx >= 0 && count < MATCH_COUNT_LIMIT) {
                count++
                idx = text.indexOf(findText, idx + findText.length)
            }
            count
        }
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
        // 约束：所有焦点/IME 操作必须在此 Dialog 内容作用域内进行：
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
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    // 面板本身是 50% 不透明（用户指定），若外层不遮罩，面板下的正文会与面板文字糊在一起。
                    // 用 55% 暗化遮罩把底下的正文压下去，面板保持 50% 不透明的同时保证可读性。
                    // 必须显式加 FLAG_DIM_BEHIND：仅 setDimAmount 在 usePlatformDefaultWidth=false
                    // 的自定义窗口上不会生效（实测背景完全无暗化）。
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(0.55f)
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

        // 悬浮内容：半透明暗色面板，不透明度 50%（alpha 0x80 = 128/255），无边框、零圆角
        // 宽度 90%（APP 宽度的 90%）；字号整体 +5，行间距 / 内边距 ×1.3
        Box(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .background(androidx.compose.ui.graphics.Color(0x800D1521))
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
                        text = if (matchCount > 0) {
                            val suffix = if (matchCount >= MATCH_COUNT_LIMIT) "+" else ""
                            "共 $matchCount$suffix 处匹配"
                        } else "无匹配",
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
                    // 约束：禁用态也用彩色渐变（仅 alpha 50%），严禁灰——灰字看不清
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

//  EditorSettingsDialog — 紧凑纯文本设置面板
//  规格（用户定制）：
//  · 入口项：编码 / 换行 / 另存为 / 显示行号 / 字号 / 自动保存 / 全文统计
//  · 全部控件禁止矩形背景（无 background 色块、无 Button），一律纯文本 + 贴边紧凑行
// · 编码 / 换行以页内二级列表展开（不复套弹窗），选中项右侧 对勾
//  · 全文统计：仅在「打开设置面板」时计算一次（非实时，不随输入重算），
//    输出 英文 / 中文 / 数字 三项，计算在 Dispatchers.Default 执行
@Composable
private fun EditorSettingsDialog(
    text: String,
    onTextChange: (String) -> Unit,
    showLineNumber: Boolean, onShowLineNumberChange: (Boolean) -> Unit,
    fontSize: Float, onFontSizeChange: (Float) -> Unit,
    autoSaveSeconds: Int, onAutoSaveChange: (Int) -> Unit,
    charset: Charset, onCharsetChange: (Charset) -> Unit,
    lineEnding: LineEnding, onLineEndingChange: (LineEnding) -> Unit,
    /** 写盘时是否输出 BOM。原实现只能沿用读取到的状态，用户无法增删 BOM。 */
    hasBom: Boolean, onHasBomChange: (Boolean) -> Unit,
    /** 分段浏览（大文件未载全文）：正文不在内存，统计与文本处理都无从下手。 */
    chunkedBrowsing: Boolean = false,
    /** 打开「语法包」管理（导入/启用/删除 Monarch 语法）。 */
    onSyntaxPacksClick: () -> Unit = {},
    onSaveAsClick: () -> Unit, onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf("root") }          // root | charset | lineEnding
    var statResult by remember { mutableStateOf<TextStatistics.Stats?>(null) }
    var statBusy by remember { mutableStateOf(false) }
    var isTransforming by remember { mutableStateOf(false) }
    /** 待确认的文本处理：操作名 / 处理前行数 / 已算好的结果文本。 */
    var pendingTransform by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    val transformScope = rememberCoroutineScope()

    /**
     * 文本处理入口：先算出结果再弹确认，用户确认后才写回编辑器。
     *
     * 三个操作（删空行 / 整体缩进 / 删换行）都是**破坏性整篇改写**且没有撤销栈，
     * 误点一次就要靠关闭不保存来挽回，因此必须先让用户看到影响面。
     * 结果在这里一次算好并缓存，确认时直接套用，避免重复计算。
     */
    fun runTextTransform(name: String, transform: (String) -> String) {
        if (isTransforming) return
        isTransforming = true
        transformScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    Triple(name, text.count { it == '\n' } + 1, transform(text))
                }
                pendingTransform = result
                isTransforming = false
            } finally {
                isTransforming = false
            }
        }
    }

    // 打开设置面板时统计一次（点击「设置」即触发），此后不随文本变化重算
    LaunchedEffect(Unit) {
        // 分段浏览时 text 为空串，统计既没意义也白跑一遍全量扫描。
        if (chunkedBrowsing) {
            statBusy = false
            return@LaunchedEffect
        }
        statBusy = true
        val result = withContext(Dispatchers.Default) { TextStatistics.compute(text) }
        statResult = result
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
                    // 编码：二级列表
                    "charset" -> {
                        EDITOR_CHARSETS.forEach { (cs, name) ->
                            CompactOptionRow(
                                text = name, selected = cs == charset,
                                onClick = { onCharsetChange(cs); page = "root" }
                            )
                        }
                        CompactBackRow { page = "root" }
                    }
                    // 换行：二级列表
                    "lineEnding" -> {
                        LineEnding.entries.forEach { le ->
                            CompactOptionRow(
                                text = "${le.displayName()}  ${le.desc()}", selected = le == lineEnding,
                                onClick = { onLineEndingChange(le); page = "root" }
                            )
                        }
                        CompactBackRow { page = "root" }
                    }
                    // 根页
                    else -> {
                        CompactSettingRow("编码", charset.displayName()) { page = "charset" }
                        CompactSettingRow("换行", lineEnding.displayName()) { page = "lineEnding" }
                        CompactSettingRow("语法包", "›") { onSyntaxPacksClick(); onDismiss() }
                        CompactSettingRow("另存为", "›") { onSaveAsClick(); onDismiss() }
                        CompactSettingRow("显示行号", if (showLineNumber) "开" else "关") {
                            onShowLineNumberChange(!showLineNumber)
                        }
                        // BOM 开关：读取时按文件实际状态置位，用户可以显式增删 BOM 再保存。
                        // 只在 UTF-8 / UTF-16 下有意义（其他编码不做 BOM 处理，见 writeTextFile）。
                        CompactSettingRow(
                            "写入 BOM",
                            if (hasBom) "开" else "关",
                            enabled = charset == Charsets.UTF_8 ||
                                charset == Charsets.UTF_16LE ||
                                charset == Charsets.UTF_16BE
                        ) {
                            onHasBomChange(!hasBom)
                        }
                        CompactSettingRow("删除所有空行", if (isTransforming) "处理中…" else "执行", enabled = !isTransforming && !chunkedBrowsing) {
                            runTextTransform("删除所有空行", ::removeEmptyLines)
                        }
                        CompactSettingRow("整体缩进两格", if (isTransforming) "处理中…" else "执行", enabled = !isTransforming && !chunkedBrowsing) {
                            runTextTransform("整体缩进两格") { indentAllLines(it, 2) }
                        }
                        CompactSettingRow("删除所有换行", if (isTransforming) "处理中…" else "执行", enabled = !isTransforming && !chunkedBrowsing) {
                            runTextTransform("删除所有换行", ::removeAllLineBreaks)
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
                        // 全文统计：打开设置面板时已统计一次，此处只展示
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("全文统计", style = AuroraTextStyles.body2, color = AuroraTokens.Text)
                        }
                        val result = statResult
                        if (chunkedBrowsing) {
                            // contentValue 为空，统计出的 0 会让用户误以为文件是空的，直接说明原因。
                            Text(
                                "分段浏览模式未载入全文，无法统计",
                                style = AuroraTextStyles.footnote2,
                                color = AuroraTokens.TextSecondary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        } else if (result != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                StatItem("英文", "${result.english}")
                                StatItem("中文", "${result.chinese}")
                                StatItem("数字", "${result.digits}")
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

    // 文本处理确认：三个操作都是破坏性整篇改写，而编辑器没有撤销栈，必须先给出影响面。
    pendingTransform?.let { (name, beforeLines, result) ->
        val afterLines = result.count { it == '\n' } + 1
        AuroraWindowDialog(
            show = true,
            title = "确认$name",
            summary = "行数 $beforeLines → $afterLines",
            onDismissRequest = { pendingTransform = null }
        ) {
            Text(
                "该操作会改写编辑器内的全部内容，编辑器没有撤销；执行后若不想保留，"
                    + "请关闭编辑器并选择「不保存」。",
                style = AuroraTextStyles.footnote2,
                color = AuroraTokens.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            if (afterLines == 1 && result.length > VERY_LONG_SINGLE_LINE_CHARS) {
                Text(
                    "注意：结果只剩 1 行且长度较大，超长单行的排版会更慢。",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.Warning,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                Button(
                    onClick = { pendingTransform = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.SurfaceHover,
                        contentColor = AuroraTokens.Text
                    )
                ) { Text("取消") }
                Button(
                    onClick = {
                        onTextChange(result)
                        pendingTransform = null
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)
                ) { Text("执行") }
            }
        }
    }
}

//  DiffModeDialog — 对比模式选择（紧凑纯文本，无矩形背景）
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

//  DiffProgressDialog — 对比进度（可取消）
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
private fun CompactSettingRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = AuroraTextStyles.body2,
            color = if (enabled) AuroraTokens.Text else AuroraTokens.TextDisabled
        )
        Text(
            text = value, style = AuroraTextStyles.footnote1,
            color = if (enabled) AuroraTokens.TextSecondary else AuroraTokens.TextDisabled,
            fontFamily = FontFamily.Monospace
        )
    }
}

internal fun removeEmptyLines(text: String): String =
    text.split('\n').filterNot { it.trim('\r', ' ', '\t').isEmpty() }.joinToString("\n")

internal fun indentAllLines(text: String, spaces: Int = 2): String {
    if (text.isEmpty() || spaces <= 0) return text
    val prefix = " ".repeat(spaces)
    return text.split('\n').joinToString("\n") { "$prefix$it" }
}

internal fun removeAllLineBreaks(text: String): String =
    text.replace("\r\n", "").replace("\n", "").replace("\r", "")

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

//  HistoryDialog — 类 git 版本历史（可回退 20 个版本）
//  · 每条历史 = 一个版本快照（时间 + 行数 + 预览）
//  · 点版本 → 回退到该版本（当前内容自动存为新版本，可再撤回）
//  · 最新版本在顶部
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

//  SaveAsDialog
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

//  UnsavedChangesDialog
@Composable
private fun UnsavedChangesDialog(
    onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("未保存的更改", style = AuroraTextStyles.title3) },
        text = { Text("文件有未保存的更改，是否保存？", style = AuroraTextStyles.body1, color = AuroraTokens.TextSecondary) },
                // 该按钮保存成功后即关闭编辑器（见调用处 onSave），文案必须体现这一点，
        // 否则用户以为只是保存、编辑器却被关掉。
        confirmButton = { Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)) { Text("保存并关闭") } },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDiscard) { Text("放弃") }
            TextButton(onClick = onCancel) { Text("取消") }
        }}
    )
}

//  LineEnding 扩展
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

