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
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.text.AnnotatedString
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
    val editorScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    var isLoading by remember { mutableStateOf(!isNewFile) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var currentCharset by remember { mutableStateOf(Charsets.UTF_8) }
    var currentLineEnding by remember { mutableStateOf(LineEnding.LF) }
    var hasBom by remember { mutableStateOf(false) }
    var overrideCharset by remember { mutableStateOf<Charset?>(null) }

    var dirty by remember { mutableStateOf(isNewFile) }
    // 进入编辑器一律是只读态：打开文件即编辑会让用户在无意间改动内容（尤其在看日志/配置时）。
    // 需显式点顶栏「只读/编辑」进入编辑态后才可改，且仅编辑态才产生历史快照与自动保存。
    var userReadOnly by remember { mutableStateOf(true) }
    // 大文件是否已加载全文进入编辑：分段只读与「可编辑」是两件事，
    // 用户主动点「只读/编辑」后不再按体积拒绝（卡顿可接受），但只有在全文完整读入后才允许切换。
    var editingLarge by remember { mutableStateOf(false) }
    // 超大文件的编辑风险确认（同一文件本会话内确认一次即可，不反复打扰）
    var pendingLargeEditConfirm by remember { mutableStateOf(false) }
    var largeEditConfirmed by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }

    var fileTotalBytes by remember { mutableLongStateOf(0L) }
    var isLargeFile by remember { mutableStateOf(false) }
    // 大文件分段模式：append-only 行列表，避免每次 load 都重新 split 整个累积文本
    //（那样会触发 O(N²) 扫描 + 重新分配新 List）；此处只追加新行，整体 split 降到 O(N) 线性。
    var chunkedLines by remember { mutableStateOf(listOf<String>()) }
    var chunkedOffset by remember { mutableLongStateOf(0L) }
    var chunkedHasMore by remember { mutableStateOf(false) }

    var history by remember { mutableStateOf<List<EditHistoryManager.HistoryEntry>>(emptyList()) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showFindReplaceDialog by remember { mutableStateOf(false) }
    /**
     * 查找游标（仅原生通道使用）。
     * 原生通道下 Compose 侧的 selection 由回写统一置为文末，用它做「查找下一个」的起点
     * 会导致每次都从文末开始环绕查找；改用独立游标，每次命中后推进。
     */
    var findCursor by remember { mutableStateOf(0) }

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
        snapshotFlow { contentValue.text }.collectLatest { t ->
            if (t.isEmpty()) { stats = statsEmpty; return@collectLatest }
            // 超大文本放弃统计：compute 单遍全字段扫描 + toByteArray，512KB 级别每次输入都要跑，
            // 结果写回 state 还会再触发一轮重组。状态栏保留上次数字，换输入流畅度。
            if (t.length > LARGE_EDIT_STATS_SKIP_CHARS) return@collectLatest
            val wasLarge = t.length > 64 * 1024
            if (wasLarge) delay(600L)
            // compute 期间用户可能又改了文本；若已变则丢弃这次结果（collectLatest 也会取消旧 launch，
            // 但 LaunchedEffect 重启不取消已在 IO 线程上跑的 compute，做二次保险）。
            if (t != contentValue.text) return@collectLatest
            val outcome = withContext(Dispatchers.Default) { TextStatistics.compute(t) }
            if (t != contentValue.text) return@collectLatest
            stats = outcome
        }
    }

    /**
     * 为大文件加载全文以便编辑。
     *
     * 不按体积拒绝（用户已明确要编辑），但必须防住两点：
     * ① 读取失败/被截断时**绝不进入编辑态** —— contentValue 不完整的话，一次保存就会截断原文件；
     * ② 超出 ChunkedFileReader 的读取上限时明确报错，而不是等 OOM 崩溃。
     */
    suspend fun enterEditForLargeFile(): Boolean {
        val path = currentFilePath ?: return false
        return try {
            val load = withContext(Dispatchers.IO) { ChunkedFileReader.loadAll(path) }
            currentCharset = overrideCharset ?: load.charset
            hasBom = load.hasBom
            currentLineEnding = LineEnding.detect(load.text)
            val normalized = LineEnding.apply(load.text, LineEnding.LF)
            contentValue = TextFieldValue(normalized, TextRange(normalized.length))
            chunkedLines = emptyList()
            // 刚载入的内容与磁盘一致，不应被判定为未保存修改
            dirty = false
            editingLarge = true
            true
        } catch (e: Exception) {
            toastMessage = "无法进入编辑：${e.message ?: "读取失败"}"
            false
        }
    }

    // 说明：overrideCharset 变化会重跑上面的加载 LaunchedEffect，其中已重置 editingLarge，
    // 因此重载后一律回到「分段浏览 + 只读」，需再次点「只读/编辑」才能编辑。
    fun replaceEditorText(newText: String) {
        if (newText != contentValue.text) {
            contentValue = TextFieldValue(newText, TextRange(newText.length))
            dirty = true
        }
    }

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
                // 任何重新加载都作废「大文件已进入编辑」：文本被重新读成首块，
                // 若保留该标记，编辑区会拿空的 contentValue 渲染出空白。
                editingLarge = false
                if (!isLargeFile) {
                    val load = ChunkedFileReader.loadAll(path)
                    currentCharset = overrideCharset ?: load.charset
                    hasBom = load.hasBom
                    currentLineEnding = LineEnding.detect(load.text)
                    // 内存统一归一为 LF 后再交给编辑器：Compose 的 BasicTextField 只按 '\n' 断行，
                    // CR-only（旧 Mac）文本若不归一，整篇会被显示成一行、行号恒为 1；CRLF 也会让
                    // 每行尾部残留一个不可见的 '\outcome'。保存时由 LineEnding.apply 按 currentLineEnding 还原。
                    val normalized = LineEnding.apply(load.text, LineEnding.LF)
                    contentValue = TextFieldValue(normalized, TextRange(normalized.length))
                    // 进入小文件模式时清空 chunkedLines，避免下次再切回大文件残留上次状态。
                    chunkedLines = emptyList()
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
                }
                history = EditHistoryManager.getHistory(path)
            } catch (e: Exception) { loadError = "读取失败: ${e.message}" }
            finally { isLoading = false }
        }
    }

    // 类 git 自动快照：编辑停顿 2.5s 且内容与最近版本不同 → 自动记录一条历史。
    // 保存(手动/另存为)时也会记录。上限 20 条由 EditHistoryManager 淘汰最旧。
    LaunchedEffect(contentValue.text, currentFilePath, userReadOnly) {
        // 只读态不记录历史：用户没在编辑，文本不可能变化，避免打开文件即产生一条无意义快照。
        if (userReadOnly) return@LaunchedEffect
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
            // 历史持久保留（类 git 仓库），关闭编辑器不清空——否则只剩一条历史。
        }
    }

    // 自动保存草稿
    LaunchedEffect(autoSaveSeconds, dirty, currentFilePath, userReadOnly) {
        if (autoSaveSeconds <= 0) return@LaunchedEffect
        while (true) {
            delay(1000L)
            // 只读态不自动保存：未进入编辑则跳过，避免开了自动保存后仅浏览也写历史。
            if (userReadOnly) continue
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
    // 不能用「静态 Text + 透明 BasicTextField(matchParentSize)」叠加：
    // 在 Row(verticalScroll) 内会产生 0 宽 Constraints，导致 IllegalArgumentException 闪退，
    // 或使普通分支 fillMaxSize 拿到 0 宽而内容不可见。
    // 语法高亮不在组合期同步计算：每次按键都会在主线程重组里全量跑 CodeHighlighter
    // （对每个标识符做 substring，上限 10 万字符），连续输入会掉帧。
    // 改为输入停顿 120ms 后在后台线程重算。
    //
    // 关键正确性约束：VisualTransformation 返回的文本必须与输入文本一致，否则会把**陈旧文本**渲染到
    // 输入框里。因此下面按 `hl.text == text.text` 校验：不等则回退为无高亮（仅短暂失色，文本与光标
    // 始终正确），待新高亮算完再套用。
    var highlightedText by remember { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(contentValue.text, language) {
        val lang = language
        val text = contentValue.text
        if (lang == null || text.isEmpty() || text.length > 100_000) {
            highlightedText = null
            return@LaunchedEffect
        }
        delay(120L)
        highlightedText = withContext(Dispatchers.Default) {
            CodeHighlighter.highlight(text, lang.ext, AuroraTokens.Text)
        }
    }
    val highlightTransformation = remember(highlightedText) {
        val hl = highlightedText
        if (hl == null) androidx.compose.ui.text.input.VisualTransformation.None
        else androidx.compose.ui.text.input.VisualTransformation { text ->
            if (hl.text == text.text) {
                androidx.compose.ui.text.input.TransformedText(hl, androidx.compose.ui.text.input.OffsetMapping.Identity)
            } else {
                androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
            }
        }
    }
    // 保存（工具栏「保存」/ 未保存提醒共用）：无路径时转「另存为」，无改动时提示。
    /**
     * 进入编辑态。
     *
     * - 大文件（分段浏览中）必须先全文载入成功再切换：半截内容一旦被保存会覆盖原文件；
     * - 加载中 / 保存中不接受切换，避免同一份文本被并发修改；
     * - 已在编辑态时是空操作（重复点击不产生副作用）。
     */
    fun enterEditMode() {
        if (!userReadOnly || isLoading || isSaving) return
        if (isLargeFile && !editingLarge) {
            // 超大文件先确认：该体量的全量布局会把主线程占满数十秒，直接进入等于无预警卡死。
            if (fileTotalBytes > LARGE_EDIT_WARN_BYTES && !largeEditConfirmed) {
                pendingLargeEditConfirm = true
                return
            }
            scope.launch {
                isLoading = true
                val ok = enterEditForLargeFile()
                isLoading = false
                if (ok) userReadOnly = false
            }
        } else {
            userReadOnly = false
        }
    }

    /**
     * 切回只读态。
     *
     * 不丢内容、不自动保存：修改留在内存里，再次点「编辑」可继续编辑与保存。
     * 有未保存修改时明确提示，避免用户以为「切回只读」等于已保存。
     */
    fun exitEditMode() {
        if (userReadOnly || isSaving) return
        if (dirty) {
            toastMessage = "已切回只读；修改未保存，可直接点「保存」落盘"
        }
        userReadOnly = true
    }

    /**
     * 只读态的写操作统一拦截：替换 / 历史回退 / 文本处理等入口都必须先过这里。
     * 返回 true 表示可以继续执行。
     */
    fun allowMutation(action: String): Boolean {
        if (userReadOnly) {
            toastMessage = "只读模式：点击顶栏「只读/编辑」后$action"
            return false
        }
        return true
    }

    val doSave: () -> Unit = save@{
        // 连点保护：保存中忽略后续点击。保存本身是幂等的，多点几次不会产生额外写入，
        // 但并发写同一文件会出现「后写的覆盖先写的」，故直接忽略进行中的重复请求。
        if (isSaving) return@save
        when {
            // 顺序即优先级，覆盖所有组合：
            // ⓪ 加载中：contentValue 可能仍为空或只有首块，此时写盘必然损坏文件
            isLoading -> toastMessage = "正在加载，请稍候再保存"
            // ① 读取失败 → 编辑器里的内容不是完整原文，保存会截断/覆盖原文件
            loadError != null -> toastMessage = "文件读取失败，已阻止保存以防损坏原文件"
            // ② 分段浏览（未载全文）→ contentValue 只有首块，保存会把文件截成一小段
            isLargeFile && !editingLarge -> toastMessage = "分段内容不完整，请先点「编辑」加载全文再保存"
            // ③ 新建文件（无路径）：无论只读还是编辑，都需要先确定保存位置
            currentFilePath == null -> showSaveAsDialog = true
            // ④ 内容未变
            !dirty -> toastMessage = "无改动"
            // 说明：只读态**不**拦截保存。只读约束的是「不接收输入」，
            // 而非「不允许落盘已有改动」——否则用户先编辑再切回只读后，
            // 改动既存不了、关闭时又被提示未保存，只能被迫再点一次「编辑」，纯属绕路。
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
                    dirty = dirty,
                    editing = !userReadOnly,
                    onReadOnlyClick = { exitEditMode() },
                    onEditClick = { enterEditMode() },
                    switchEnabled = !isLoading && !isSaving,
                    language = language, hasBom = hasBom,
                    onSettingsClick = { showSettingsDialog = true },
                    onFindClick = {
                        // 分段浏览时正文未载入内存（contentValue 为空），查找必然无效，先说明原因。
                        if (isLargeFile && !editingLarge) {
                            toastMessage = "分段浏览模式未载入全文，点「编辑」后即可查找"
                        } else {
                            showFindReplaceDialog = true
                        }
                    },
                    onCompareClick = {
                        if (currentFilePath == null) {
                            toastMessage = "请先保存文件后再对比"
                        } else if (isLargeFile && !editingLarge) {
                            // 分段浏览时正文只在 chunkedLines 里，contentValue 为空，比对结果是无意义的。
                            toastMessage = "分段浏览模式未载入全文，点「编辑」后即可对比"
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
                if (isLargeFile && !editingLarge) {
                    ChunkedInfoBar(
                        offset = chunkedOffset, total = fileTotalBytes, hasMore = chunkedHasMore,
                        onLoadMore = {
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
                        else -> EditorContentArea(
                            value = contentValue,
                            onValueChange = { contentValue = it; dirty = true },
                            highlightTransformation = if (isLargeFile) {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            } else highlightTransformation,
                            // 两种情况下不显示行号列：
                            //  ① 超大文本（>20 万字符）：行号列自身是 LazyColumn + 逐帧滚动同步，纯属额外负担；
                            //  ② 走原生 EditText 通道（>6.4 万字符）：行号列与原生控件是两套滚动体系，
                            //     无法同步 —— 显示出来就是错的，必须收起。
                            showLineNumber = showLineNumber &&
                                contentValue.text.length <= NATIVE_EDITOR_THRESHOLD,
                            fontSize = fontSize.sp,
                            scrollState = editorScroll, hScroll = hScroll,
                            // 分段模式正文来自 chunkedLines，必须显式传入：
                            // 漏传时只读 LazyColumn 会渲染空列表，表现为打开大文件一片空白。
                            chunkedLines = chunkedLines,
                            chunked = isLargeFile && !editingLarge,
                            editable = !userReadOnly
                        )
                    }
                }

                // 状态栏
                // 超大文本跳过了全字段统计（见 LARGE_EDIT_STATS_SKIP_CHARS），此时 stats 不会更新，
                // 直接把 0 行显示给用户是错的。这里只补一个行数：单遍扫描且用 remember 缓存，
                // 文本变化时才重算一次（512KB 约 0.5ms），不构成输入负担。
                val bigTextLineCount = remember(contentValue.text) {
                    if (contentValue.text.length > LARGE_EDIT_STATS_SKIP_CHARS) {
                        contentValue.text.count { it == '\n' } + 1
                    } else null
                }
                EditorStatusBar(
                    stats = bigTextLineCount?.let { statsEmpty.copy(lines = it) } ?: stats,
                    filePath = currentFilePath,
                    chunkedMode = isLargeFile && !editingLarge,
                    statsSkipped = bigTextLineCount != null,
                    fileTotalBytes = fileTotalBytes, chunkedOffset = chunkedOffset,
                    // 大文件模式下 contentValue 为空，行数必须取自 chunkedLines。
                    chunkedLineCount = chunkedLines.size,
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
        onTextChange = change@{ newText ->
            if (!allowMutation("才能修改文本")) return@change
            replaceEditorText(newText)
        },
        showLineNumber = showLineNumber,
        onShowLineNumberChange = { showLineNumber = it; appSettings.updateEditorShowLineNumber(it) },
        hasBom = hasBom, onHasBomChange = { hasBom = it },
        chunkedBrowsing = isLargeFile && !editingLarge,
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
                if (!isNewFile) { contentValue = TextFieldValue(""); isLoading = true }
            }
        },
        lineEnding = currentLineEnding,
        onLineEndingChange = { le -> currentLineEnding = le },
        onSaveAsClick = { showSaveAsDialog = true },
        onDismiss = { showSettingsDialog = false }
    )

    if (showFindReplaceDialog) FindReplaceDialog(
        text = contentValue.text, currentSelectionStart = contentValue.selection.start,
        onFindNext = { findText ->
            // 从「上次命中之后」继续查找并选中（找不到则环绕）。
            // 起点选择：原生通道用 findCursor（Compose 侧 selection 被回写为文末，不可信）；
            // Compose 通道用真实光标位置，符合「从光标处往下找」的直觉。
            val text = contentValue.text
            val nativeChannel = text.length > NATIVE_EDITOR_THRESHOLD
            if (findText.isNotEmpty() && text.isNotEmpty()) {
                val start = (if (nativeChannel) findCursor else contentValue.selection.end)
                    .coerceIn(0, text.length)
                var idx = text.indexOf(findText, start)
                if (idx < 0) idx = text.indexOf(findText)  // 环绕查找
                if (idx >= 0) {
                    if (nativeChannel) findCursor = idx + findText.length
                    contentValue = TextFieldValue(text, TextRange(idx, idx + findText.length))
                }
            }
        },
        onReplace = replace@{ original, replacement ->
            if (!allowMutation("才能替换")) return@replace
            // 文本即将改变，旧游标位置不再可信：归零，使下一次「查找下一个」从头开始。
            findCursor = 0
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
        onReplaceAll = replaceAll@{ original, replacement ->
            if (!allowMutation("才能替换")) return@replaceAll
            // 全文替换后长度与位置全变，游标归零。
            findCursor = 0
            val newText = contentValue.text.replace(original, replacement)
            contentValue = TextFieldValue(newText, TextRange(newText.length)); dirty = true
        },
        onDismiss = { showFindReplaceDialog = false }
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

    AuroraWindowDialog(
        show = pendingLargeEditConfirm,
        title = "超大文件编辑提示",
        summary = "文件 ${formatBytes(fileTotalBytes)}，超过 ${formatBytes(LARGE_EDIT_WARN_BYTES)}",
        onDismissRequest = { pendingLargeEditConfirm = false }
    ) {
        Text(
            "该体积的文本会让编辑器把主线程占满数十秒（实测约 30 秒，期间界面无响应，输入会排队），"
                + "这是编辑控件对超长文本的全量排版开销，与手机性能无关。",
            style = AuroraTextStyles.footnote2,
            color = AuroraTextStyles.footnote2.color.copy(alpha = 1f),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            "建议：用只读浏览查看内容；确需修改时，优先裁剪出要改的片段单独编辑。",
            style = AuroraTextStyles.footnote2,
            color = AuroraTokens.TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            Button(
                onClick = { pendingLargeEditConfirm = false },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuroraTokens.SurfaceHover,
                    contentColor = AuroraTokens.Text
                )
            ) { Text("取消") }
            Button(
                onClick = {
                    largeEditConfirmed = true
                    pendingLargeEditConfirm = false
                    enterEditMode()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)
            ) { Text("仍要编辑") }
        }
    }

    if (showHistoryDialog) HistoryDialog(
        history = history,
        onRestore = restore@{ entry ->
            if (!allowMutation("才能恢复历史版本")) return@restore
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
            // 只有「未加载全文的分段浏览」才不能另存为：此时 contentValue 只有首块，
            // 另存出去会得到残缺文件。已点「只读/编辑」加载全文的大文件不受此限。
            if (isLargeFile && !editingLarge) {
                toastMessage = "分段浏览模式内容不完整，请先点「只读/编辑」加载全文"
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
 * 写入文本：root 走 /data/local/tmp 中转 + mv；无 root 直写。
 *
 * root 路径的两处处理：
 *  1. 符号链接：`mv` 覆盖会把链接替换成普通文件。目标是软链时先 `readlink -f` 解析真实路径
 *     再写入真身，保持链接结构不变。
 *  2. 权限与属主：`mv` 后新文件沿用临时文件的 mode/owner（如 root:root 600）。
 *     写入前记录 `stat -c '%a %u %g'`，写入后还原，并尽力 `restorecon` 恢复 SELinux 上下文。
 */
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

            val tmpFile = "/data/local/tmp/_shso_edit_${System.currentTimeMillis()}.tmp"
            val writeOk = RootService.writeBytesAsRoot(tmpFile, bytes)
            if (!writeOk) return@withContext Pair(false, "写入临时文件失败")
            val (mvCode, mvOut) = RootService.runCommandSync(
                "mv ${RootService.escapeShellArg(tmpFile)} $escapedTarget",
                60_000L
            )
            if (mvCode != 0) {
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
            File(filePath).writeBytes(bytes)
            Pair(true, null)
        }
    } catch (e: Exception) { Pair(false, "保存失败: ${e.message}") }
}

//  EditorTopBar
@Composable
private fun EditorTopBar(
    fileName: String, dirty: Boolean,
    /** 当前是否为编辑态：用于「只读 / 编辑」两个按钮的选中高亮。 */
    editing: Boolean = false,
    onReadOnlyClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    /** 加载/保存进行中时禁止切换模式，避免同一份文本被并发改动。 */
    switchEnabled: Boolean = true,
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
            // 只读 / 编辑为两个独立按钮，当前状态高亮（Accent + 加粗），另一个为次要色。
            // 用户一眼可分辨当前处于哪个状态，而不是靠猜一个合并开关的含义。
            EditorToolbarButton(
                "只读", null, { if (switchEnabled) onReadOnlyClick() },
                tint = when {
                    !switchEnabled -> AuroraTokens.TextDisabled
                    editing -> AuroraTokens.TextSecondary
                    else -> AuroraTokens.Accent
                },
                emphasized = !editing
            )
            EditorToolbarButton(
                "编辑", null, { if (switchEnabled) onEditClick() },
                tint = when {
                    !switchEnabled -> AuroraTokens.TextDisabled
                    editing -> AuroraTokens.Accent
                    else -> AuroraTokens.TextSecondary
                },
                emphasized = editing
            )
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
            // 只显示加载进度：「只读/编辑」状态已由顶栏两个按钮表达，此处再标「只读」属重复。
            text = "分段加载 ${formatBytes(offset)} / ${formatBytes(total)}",
            style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
        )
        if (hasMore) {
            Text(
                text = "加载更多 →", style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
                modifier = Modifier.clickable(onClick = onLoadMore).padding(4.dp)
            )
        } else {
            Text(
                text = "全文已加载",
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

//  EditorContentArea
//  约束：BasicTextField 必须自带内部滚动，直接放在 weight(1f) 的 Box 中（不在 verticalScroll
//  内），行号列用同高 Box 平铺，两者各自独立滚动并同步（MT/MP-Manager 同款布局）。
//  「静态 Text + 透明 BasicTextField(matchParentSize) 叠加」会在滚动容器内产生 0 宽 Constraints
//  → IllegalArgumentException 闪退；BasicTextField 放进 Row(verticalScroll) 内 weight(1f) 则在无界高度
//  下测量失效（约 112px 宽），文字、焦点与输入法均不可用——Row + weight 与滚动容器嵌套是官方文档
//  明确反对的结构。
@Composable
private fun EditorContentArea(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
    highlightTransformation: androidx.compose.ui.text.input.VisualTransformation,
    showLineNumber: Boolean, fontSize: androidx.compose.ui.unit.TextUnit,
    scrollState: androidx.compose.foundation.ScrollState, hScroll: androidx.compose.foundation.ScrollState,
    // 刻意不给默认值：漏传时会静默渲染空白，必填可在编译期暴露问题。
    chunkedLines: List<String>,
    /** 大文件分段模式：走 LazyColumn 懒加载渲染（与「只读」是两件事，不可合并）。 */
    chunked: Boolean = false,
    /** 是否允许编辑。false 时仍用 BasicTextField 渲染（可选词复制），只是不接受输入。 */
    editable: Boolean = true
) {
    val scope = rememberCoroutineScope()
    // 编辑区 + 右侧细拖动条（贴紧边缘）：拖动可快速跳到目标行号。
    Box(modifier = Modifier.fillMaxSize()) {
        if (chunked) {
            // 大文件（分段模式）：用 LazyColumn 按行懒加载渲染，只布局可见行。
            // chunkedLines 由调用方 append-only 维护，本组件直接消费、不再 split → 避开旧版
            // 「每次 loadMore 重新分配整段累积文本 + 全量 split」的 O(N²) 退化。
            // 加 stable key = index：append 时旧行索引未变，LazyColumn 跳过其重组，仅新增 N 行更新。
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
        } else {
            val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
            val lineNumberWidth = remember(lineCount) { "${lineCount}".length.coerceAtLeast(3) }

            Row(modifier = Modifier.fillMaxSize()) {
                // 行号列（与编辑区共享同一 scrollState，纵向同步滚动）
                if (showLineNumber) {
                    Box(modifier = Modifier.width((lineNumberWidth * (fontSize.value * 0.7f)).dp + 12.dp)) {
                        EditorLineNumbers(
                            lineCount = lineCount,
                            fontSize = fontSize,
                            scrollState = scrollState
                        )
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
                // 编辑区：BasicTextField 与行号列共享 scrollState，纵向滚动同步。
                // 超长文本改走原生 EditText（排版量级差异见 NATIVE_EDITOR_THRESHOLD 说明）。
                // 条件只看文本量、不看是否可编辑：否则「编辑大文件后切回只读」会切回 Compose 通道，
                // 又触发一次整段排版（512KB 约 30–46s），表现为第二次卡死。
                if (value.text.length > NATIVE_EDITOR_THRESHOLD) {
                    NativeTextEditor(
                        text = value.text,
                        readOnly = !editable,
                        onTextChange = { newText ->
                            // 光标与选区完全由 EditText 自管：这里只回写文本。
                            // 回写后外层重组会再次进入本分支，但 NativeTextEditor 内部比对文本相等后
                            // 不做 setText，因此不会打断原生光标。
                            onValueChange(TextFieldValue(newText, TextRange(newText.length)))
                        },
                        fontSize = fontSize,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                    )
                } else {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    // 只读态仍渲染 BasicTextField（可用手选中/复制），但不接受输入：
                    // 若改成静态 Text，会丢掉选区与滚动同步，且行号列对齐会漂移。
                    readOnly = !editable,
                    textStyle = AuroraTextStyles.monospace.copy(
                        fontSize = fontSize,
                        // 显式行高，与行号列同步算法取同一系数（1.4）。
                        // 不指定时 Compose 需逐行做字体度量，行数上万（512KB ≈ 8192 行）时
                        // 该开销随行数线性累加，是首次布局卡顿的主要来源之一。
                        lineHeight = fontSize * 1.4f,
                        color = AuroraTokens.Text
                    ),
                    cursorBrush = SolidColor(AuroraTokens.Accent),
                    visualTransformation = highlightTransformation,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                        .verticalScroll(scrollState)
                )
                }
            }
            // 原生 EditText 通道下不显示自家滚动条：它绑定的是 Compose 的 scrollState，
            // 与原生控件的滚动互不相干 —— 拖动无效且位置错误（同「行号列」的两套滚动体系问题）。
            if (value.text.length <= NATIVE_EDITOR_THRESHOLD) {
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
}

/**
 * 编辑模式行号列（独立重组单元）。
 *
 * 行号列用 LazyColumn 按需组合可见行（约 30-50 行），而非非惰性 `Column` 一次性铺满：
 * 200k 行的文件上会把 200k 个 Text 节点塞进组合树，带来 O(N) 内存与首次 measure 开销，
 * 在大型编辑会话中接近 OOM。
 *
 * 与编辑区共享同一个 [scrollState]，纵向同步由 `firstVisibleLine` 派生状态驱动
 * `lazyState.scrollToItem(...)`，用户滚动 BasicTextField 时行号列即时跟随。
 *
 * @param lineCount 文件总行数（由 value.text.count('\n')+1 派生）
 * @param scrollState 与 BasicTextField 共用的滚动状态
 */
/**
 * 编辑态的原生长文本编辑器。
 *
 * 用途与取舍见 [NATIVE_EDITOR_THRESHOLD]：Compose 的 BasicTextField 对全文做整段排版，
 * 超长文本会占满主线程数十秒且每次输入重排；原生 EditText 走 DynamicLayout 增量排版，
 * 同体量在秒级可用。代价是没有 Compose 侧语法高亮（该体量文本本已超出高亮上限）。
 *
 * 状态协作（关键，避免死循环与光标跳动）：
 *  - 文本由 [text] 单向驱动，仅在 EditText 内容与之不等时才 setText；
 *  - 输入经 [onTextChange] 回写，外围 state 始终是唯一数据源；
 *  - 回写触发的重组会再次进入同步逻辑，此时文本已相等 → 不 setText → 原生光标不被打断。
 */
@Composable
private fun NativeTextEditor(
    text: String,
    onTextChange: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    /** 只读：禁止输入但保留滚动与选词复制（不置灰，视觉与编辑态一致）。 */
    readOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val currentText by androidx.compose.runtime.rememberUpdatedState(text)
    val currentOnChange by androidx.compose.runtime.rememberUpdatedState(onTextChange)

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.EditText(ctx).apply {
                // 视觉对齐 Compose 编辑区：透明背景、等宽字体、主题前景色、零内边距。
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                // 行高系数与 Compose 通道（1.4）一致，切换通道时段落节奏不跳。
                setLineSpacing(0f, 1.4f)
                setTextColor(AuroraTokens.Text.toArgb())
                setHintTextColor(AuroraTokens.TextSecondary.toArgb())
                // 关闭拼写建议：代码与日志不应被输入法改写。
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                setHorizontallyScrolling(false)
                isVerticalScrollBarEnabled = true
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val newText = s?.toString() ?: return
                        if (newText != currentText) currentOnChange(newText)
                    }
                })
            }
        },
        update = { ed ->
            ed.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize.value)
            // 只读用「清 keyListener + 关闭自动弹输入法」而不是 isEnabled=false：
            // 后者会连带禁用滚动并使文字变灰；前者可滚动、可长按选词复制，视觉不变。
            if (readOnly) {
                if (ed.keyListener != null) ed.keyListener = null
                ed.showSoftInputOnFocus = false
                ed.isCursorVisible = false
                ed.setTextIsSelectable(true)
            } else {
                if (ed.keyListener == null) {
                    ed.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    ed.showSoftInputOnFocus = true
                }
                ed.isCursorVisible = true
                ed.setTextIsSelectable(false)
            }
            // 引用比较短路：重组频繁，避免每次都做 O(N) 的 toString 比对（512KB 约 0.5ms/次）。
            if (ed.tag !== text) {
                ed.tag = text
                if (ed.text?.toString() != text) {
                    val selection = ed.selectionStart.coerceAtLeast(0)
                    ed.setText(text)
                    ed.setSelection(selection.coerceAtMost(ed.text?.length ?: 0))
                }
            }
        }
    )
}

@Composable
private fun EditorLineNumbers(
    lineCount: Int,
    fontSize: androidx.compose.ui.unit.TextUnit,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    if (lineCount <= 0) return
    val lazyState = rememberLazyListState()
    // 行高 ≈ fontSize × 1.4（Material/M3 默认 lineHeight 系数）。
    // 与 BasicTextField 内部排版存在亚像素级偏差，sync 时按整行滚动（scrollToItem），
    // 让 LazyListState 自带的「贴齐 item」行为吸收偏差，最终在视觉上完全一致。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    val lineHeightPx = (fontSizePx * 1.4f).coerceAtLeast(1f)

    // editor scrollState 像素偏移 → 行号列的「首个可见行」。
    val firstVisibleLine by remember(lineCount, lineHeightPx) {
        derivedStateOf {
            if (lineCount <= 0) 0
            else (scrollState.value / lineHeightPx).toInt().coerceIn(0, lineCount - 1)
        }
    }
    LaunchedEffect(firstVisibleLine) {
        // 仅当分歧 ≥1 行时 scrollToItem，避免每像素都触发滚动动画造成 churn。
        val cur = lazyState.firstVisibleItemIndex
        if (kotlin.math.abs(firstVisibleLine - cur) >= 1) {
            lazyState.scrollToItem(firstVisibleLine)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = lazyState, modifier = Modifier.fillMaxSize()) {
            items(lineCount, key = { it }) { i ->
                Text(
                    text = (i + 1).toString(),
                    style = AuroraTextStyles.monospace.copy(
                        fontSize = fontSize,
                        color = AuroraTokens.TextDisabled
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }
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
    stats: TextStatistics.Stats, filePath: String?,
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

/**
 * 超过该体积进入编辑前先确认。
 * 实测（arm64 / Android 10）：
 *  - 数据加载与解码约 0.25–0.43s，不是瓶颈；
 *  - 瓶颈是 Compose 文本控件对该体量文本的**全量布局**，512KB / 8192 行会阻塞主线程约 30s
 *    （Choreographer：Skipped 1931 frames），期间任何输入都会排队直至 ANR。
 * 因此超过阈值时把选择权交给用户，而不是直接卡死。
 */
private const val LARGE_EDIT_WARN_BYTES = 200L * 1024L

/**
 * 超过该字符数时，编辑态改用原生 `EditText`（见 [NativeTextEditor]）。
 *
 * 依据：Compose `BasicTextField` 对全文做整段排版，实测 512KB（约 8192 行）阻塞主线程 30–46s，
 * 且每次输入都重排；原生 `EditText` 走 `DynamicLayout` 增量排版，同体量在秒级完成。
 * 阈值取 64K：以下保留 Compose 通道（有语法高亮、排版开销可接受），以上切原生通道换可用性。
 * 与语法高亮自身的 10 万字符上限协同 —— 走原生通道的文本本来也已超过高亮上限。
 */
private const val NATIVE_EDITOR_THRESHOLD = 64_000

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
        else {
            // 用 indexOf 循环计数，避免 split 产生巨大临时 List/子串分配；
            // 步进 idx + findText.length 与原 split（非重叠）计数语义一致。
            var count = 0
            var idx = text.indexOf(findText)
            while (idx >= 0) {
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
            if (afterLines == 1 && result.length > NATIVE_EDITOR_THRESHOLD) {
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

