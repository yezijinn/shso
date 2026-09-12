// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.IncrementalAnsiParser
import com.mixradio.droid.data.ParsedAnsiResult
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.security.CommandSource
import com.mixradio.droid.data.security.Finding
import com.mixradio.droid.data.security.RiskLevel
import com.mixradio.droid.data.security.RootCommandGateway
import com.mixradio.droid.data.security.Verdict
import com.mixradio.droid.ui.components.ColorWheelDialog
import com.mixradio.droid.ui.components.CommandRiskDialog
import com.mixradio.droid.ui.theme.AuroraSwitchPreference
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraThinSlider
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 终端输出行的 LazyColumn key：**只取行序号**。
 *
 * 为什么不取内容：终端里重复行极常见（空行、重复提示符、回显、`\r` 原地覆盖产生的同文本行），
 * 一旦两行内容相同，内容派生的 key 就重复，LazyColumn 会抛
 * `IllegalArgumentException("Key ... was already used")` 直接崩溃。
 * 本函数刻意忽略 [line]，size 变化即代表追加/裁剪，序号唯一性由列表下标保证。
 */
internal fun terminalLineKey(index: Int, @Suppress("UNUSED_PARAMETER") line: AnnotatedString): Int =
    index

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var pendingCommand by remember { mutableStateOf<String?>(null) }
    var pendingFindings by remember { mutableStateOf<List<Finding>>(emptyList()) }
    val listState = rememberLazyListState()

    var showTerminalSettings by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }

    val terminalDefaultColor = remember(appSettings.terminalTextColor) {
        Color(appSettings.terminalTextColor)
    }

    // 增量解析器：跨 flush 维护「当前未完成行 / SGR 状态 / \r 光标列 / 截断的 ESC 序列」，
    // 因此每次发布只需解析**新增尾部**，不再全量重扫 250k 窗口。
    // 颜色是解析器的构造参数，换色即重建（key 为 terminalDefaultColor）。
    //
    // HorizontalPager 离屏即销毁本页，重进会重建状态；日志窗口可达 250k 字符，
    // 同步全量解析约 200ms+。只缓存快照会丢失解析器的跨行 / SGR 状态，
    // 因此连解析器实例与进度一并缓存：重进时续用，新日志只走增量，首帧不在主线程解析。
    val cachedParse = TerminalParseCache.state?.takeIf { it.color == terminalDefaultColor }
    val ansiParser = remember(terminalDefaultColor) {
        cachedParse?.parser ?: IncrementalAnsiParser(terminalDefaultColor)
    }
    // 已消费的输入前缀，用于判定「本次是追加还是整体替换」。
    var consumedLog by remember(terminalDefaultColor) {
        mutableStateOf(cachedParse?.consumedLog ?: "")
    }
    var parsedOutput by remember(terminalDefaultColor) {
        mutableStateOf(cachedParse?.result ?: ParsedAnsiResult(emptyList()))
    }

    // 解析移出主线程：成本与整个日志窗口（250k）成正比，每次发布都会重解析。
    // conflate() 保证同一时刻只有一个解析在跑，中间值直接丢弃。
    LaunchedEffect(terminalDefaultColor) {
        // 仅「换色 / 首次进入」需要清空重建；复用缓存解析器时保留其状态与进度。
        if (ansiParser !== cachedParse?.parser) {
            ansiParser.reset()
            consumedLog = ""
        }
        snapshotFlow { RootService.outputLog }
            .conflate()
            .collect { log ->
                // 复用缓存时首轮日志常与缓存进度一致，此时无需任何解析。
                if (log == consumedLog) return@collect
                val prev = consumedLog
                val snap = withContext(Dispatchers.Default) {
                    if (log.length > prev.length && log.startsWith(prev)) {
                        // 追加：只解析新增部分
                        ansiParser.feed(log.substring(prev.length))
                    } else {
                        // 整体替换（清屏 / 横幅重生成 / 滑动窗口裁剪掉了头部）：无法复用状态，回落全量解析。
                        ansiParser.reset()
                        ansiParser.feed(log)
                    }
                    ansiParser.snapshot()
                }
                // 回主线程再写状态（避免后台线程写 Compose state），并更新缓存供下次重进复用。
                consumedLog = log
                parsedOutput = snap
                TerminalParseCache.state = TerminalParseState(terminalDefaultColor, ansiParser, log, snap)
            }
    }

    val isImeVisible = WindowInsets.isImeVisible

    // 「跟随尾部」意图：只在用户手动滚动（拖动/惯性）时更新，不受新日志追加影响。
    // 注意不可直接用 `!canScrollForward` 判定「是否在底部」——新内容一追加 canScrollForward 立刻变 true，
    // 会被误判成「用户已向上回看」而永久停止自动滚动。此处只在滚动进行中采样用户真实落点。
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                // 滚动停在底部时 canForward 为 false → 恢复跟随；停在中间/顶部则停止跟随
                if (scrolling) followTail = !canForward
            }
    }

    // 自动滚动到底部：跟随意图为真时即时跳到底（scrollToItem 而非 animateScrollTo，避免每帧动画 churn）。
    // key 用行数而非字符串长度，避免每次 flush 都取消并重启协程。
    LaunchedEffect(parsedOutput.lines.size, isImeVisible) {
        if (followTail && parsedOutput.lines.isNotEmpty()) {
            listState.scrollToItem(parsedOutput.lines.lastIndex)
        }
    }

    fun handleSend(textToSend: String = inputText) {
        val text = textToSend.trim()
        if (text.isEmpty()) {
            inputText = ""
            return
        }
        // 安全门控：先经 RootCommandGateway 判定，Block 直接拒绝；Confirm 弹风险确认框
        when (val v = RootCommandGateway.check(text, CommandSource.USER_TERMINAL)) {
            is Verdict.Block -> {
                Toast.makeText(context, "命令已被安全策略拦截：${v.findings.firstOrNull()?.message ?: "见审计日志"}", Toast.LENGTH_LONG).show()
                return
            }
            is Verdict.Confirm -> {
                pendingCommand = text
                pendingFindings = v.findings
            }
            Verdict.Allow -> {
                RootService.sendInput(text, confirmed = true)
                inputText = ""
            }
        }
    }

    fun onConfirmRiskSend() {
        val cmd = pendingCommand ?: return
        RootService.sendInput(cmd, confirmed = true)
        pendingCommand = null
        pendingFindings = emptyList()
        inputText = ""
    }

    fun onCancelRiskSend() {
        pendingCommand = null
        pendingFindings = emptyList()
    }

    fun copyOutput() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val textToCopy = parsedOutput.plainText
            val clip = ClipData.newPlainText("TerminalOutput", textToCopy)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "终端输出已复制到剪贴板", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    val taskRunning = RootService.isTaskRunning

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AuroraTokens.Surface)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // IDLE/RUNNING 状态：占左侧。空闲绿「待命中」、运行中红「运行中」+ 循环点（. .. ... .... .....）
                var runDots by remember { mutableIntStateOf(0) }
                LaunchedEffect(taskRunning) {
                    if (!taskRunning) { runDots = 0; return@LaunchedEffect }
                    while (true) {
                        delay(400)
                        runDots = (runDots + 1) % 5
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(
                                if (taskRunning) AuroraTokens.Error
                                else AuroraTokens.Success
                            )
                    )
                    val statusText = if (taskRunning) {
                        "运行中" + ".".repeat(runDots + 1)
                    } else "待命中"
                    Text(
                        text = statusText,
                        color = if (taskRunning) AuroraTokens.Error else AuroraTokens.Success,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                // 弹性间隔把按钮推右侧
                Spacer(modifier = Modifier.weight(1f))

                // 4 个裸文字操作按钮（无背景无描边）：复制输出 / 结束进程 / 重启终端 / 设置
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "复制输出",
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = AuroraTokens.Text,
                        modifier = Modifier
                            .clickable { copyOutput() }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )

                    Text(
                        text = "结束进程",
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (taskRunning) AuroraTokens.Error else AuroraTokens.TextUnselected,
                        modifier = Modifier
                            .clickable(enabled = taskRunning) { RootService.killCurrentProcess() }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )

                    Text(
                        text = "重启终端",
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = AuroraTokens.Accent,
                        modifier = Modifier
                            .clickable { RootService.restartTerminal() }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )

                    // 设置：弹出终端设置对话框（文字颜色 / HyperCore 提示 / shso 提示）
                    Text(
                        text = "设置",
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = AuroraTokens.Text,
                        modifier = Modifier
                            .clickable { showTerminalSettings = true }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        val bottomNavPadding = if (isImeVisible) 0.dp else innerPadding.calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomNavPadding
                )
                .imePadding()
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 4.dp,
                    // 仅给底部 DockBar(约 58dp) 让位，按钮行紧贴 DockBar 上沿，无额外大留白
                    bottom = if (isImeVisible) 0.dp else 58.dp
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.dp))
                    .background(AuroraTokens.BgDeep)
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 用「行序号」做 stable key：终端日志只向后追加，既有行序号恒定，
                        // 跨 flush 可跳过重组；窗口裁剪头部时整批序号平移，属可接受代价。
                        //
                        // 禁止用行内容（text/hashCode）做 key：终端里重复行极其常见——空行、
                        // 重复提示符、回显与 CR 原地覆盖产生的同文本行。
                        // 相同 key 会让 LazyColumn 抛 "Key ... was already used"。
                        itemsIndexed(parsedOutput.lines, key = { idx, line -> terminalLineKey(idx, line) }) { _, line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = appSettings.terminalFontSize.sp,
                                lineHeight = (appSettings.terminalFontSize + 5f).sp
                            )
                        }
                    }
                }
            }

            // 输入行（整行，紧凑）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("请输入命令...") },
                    colors = auroraTextFieldColors(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            handleSend(inputText)
                        }
                    ),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(0.dp)),
                    trailingIcon = {
                        if (inputText.isNotEmpty()) {
                            IconButton(onClick = { inputText = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "清空输入"
                                )
                            }
                        }
                    }
                )
            }

            // 动作行：中断 / 清屏 / Enter / 发送（紧凑右对齐，裸文字无背景）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "中断",
                    fontSize = 12.sp,
                    color = if (taskRunning) AuroraTokens.Error else AuroraTokens.TextUnselected,
                    modifier = Modifier
                        .clickable(enabled = taskRunning) { RootService.sendInterrupt() }
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                )

                Text(
                    text = "清屏",
                    fontSize = 12.sp,
                    color = AuroraTokens.Text,
                    modifier = Modifier
                        .clickable { RootService.clearOutput() }
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                )

                Text(
                    text = "Enter",
                    fontSize = 12.sp,
                    color = AuroraTokens.Text,
                    modifier = Modifier
                        .clickable { handleSend("") }
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                )

                Text(
                    text = "发送",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (inputText.isNotBlank()) AuroraTokens.Accent else AuroraTokens.TextUnselected,
                    modifier = Modifier
                        .clickable(enabled = inputText.isNotBlank()) { handleSend(inputText) }
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                )
            }
        }
    }

    if (showTerminalSettings) {
        AuroraWindowDialog(
            show = true,
            title = "终端设置",
            onDismissRequest = { showTerminalSettings = false }
        ) {
            // 行1：终端文字颜色 —— 与下方 AuroraSwitchPreference 等结构（min 48dp 统一行高 + 行间距），右侧用 Switch 同尺寸的胶囊色块（32×16dp / 8dp 圆角 = 完全胶囊），点击弹色轮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { showColorDialog = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "终端文字颜色",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text
                    )
                }
                // 视觉与下方 AuroraSwitchPreference 完全同构：同 Switch 控件 + scale(0.5f)，
                // 体积、垂直中心、行间距逐像素一致；轨道填当前文字色，thumb 用弹窗底色
                Switch(
                    checked = true,
                    onCheckedChange = { showColorDialog = true },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = terminalDefaultColor,
                        checkedThumbColor = AuroraTokens.DialogBg,
                        uncheckedTrackColor = terminalDefaultColor,
                        uncheckedThumbColor = AuroraTokens.DialogBg,
                        disabledCheckedTrackColor = terminalDefaultColor,
                        disabledUncheckedTrackColor = terminalDefaultColor
                    ),
                    modifier = Modifier.scale(0.5f)
                )
            }

            // 行2：终端字体大小 —— 「文字颜色」下一行，与「文件列表设置」同款：标签+实时值 + 小/大滑杆
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "终端字体大小",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text
                    )
                }
                Text(
                    text = "${appSettings.terminalFontSize.roundToInt()} sp",
                    style = AuroraTextStyles.footnote1,
                    color = AuroraTokens.Accent
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "小",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )
                AuroraThinSlider(
                    value = appSettings.terminalFontSize,
                    onValueChange = { appSettings.updateTerminalFontSize(it) },
                    valueRange = 5f..30f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "大",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )
            }

            AuroraSwitchPreference(
                title = "HyperCore 终端提示",
                checked = appSettings.showHyperCoreBanner,
                onCheckedChange = { appSettings.setHyperCoreBanner(it) }
            )

            AuroraSwitchPreference(
                title = "shso 终端提示",
                checked = appSettings.showShsoBanner,
                onCheckedChange = { appSettings.setShsoBanner(it) }
            )
        }
    }

    if (showColorDialog) {
        ColorWheelDialog(
            show = true,
            initialColor = Color(appSettings.terminalTextColor),
            onDismissRequest = { showColorDialog = false },
            onColorSelected = { color ->
                appSettings.setTerminalColor(color)
            }
        )
    }

    // 安全：终端高危命令风险确认弹窗
    if (pendingCommand != null) {
        CommandRiskDialog(
            show = true,
            findings = pendingFindings,
            level = pendingFindings.maxByOrNull { it.level.ordinal }?.level ?: RiskLevel.SAFE,
            onDismiss = { onCancelRiskSend() },
            onConfirm = { onConfirmRiskSend() }
        )
    }
}

/**
 * 终端解析状态（解析器实例 + 已消费日志进度 + 快照）。
 * 供 [TerminalParseCache] 在页面被 HorizontalPager 销毁/重建时复用，避免重进终端页重跑全量解析。
 * [color] 为解析所用默认色，颜色变化即失效（与 `remember(terminalDefaultColor)` 的 key 对齐）。
 */
private class TerminalParseState(
    val color: Color,
    val parser: IncrementalAnsiParser,
    val consumedLog: String,
    val result: ParsedAnsiResult
)

/** 单例缓存：同一时刻只有终端页在用，故仅保留最近一次 [TerminalParseState]。 */
private object TerminalParseCache {
    @Volatile
    var state: TerminalParseState? = null
}
