// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.IncrementalAnsiParser
import com.mixradio.droid.data.ParsedAnsiResult
import com.mixradio.droid.data.RootService
import com.mixradio.droid.ui.components.ColorWheelDialog
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

/**
 * 单行的渲染字符上限。
 *
 * Compose 的 `Text` 会对整串文本做断行排版，成本与该行字符数成正比。实测单行 10 万字符
 * （`cat` 二进制 / minified JSON / 单行大文件这类输出）会让主线程排版约 20 秒——
 * Choreographer 跳帧 1210、`Davey! duration=20182ms`、直接触发 ANR，期间连输入事件都派发不出去。
 * 终端日志本就按「行」呈现，超长行只渲染前 N 字符并标注省略量；**模型不动**——
 * [ParsedAnsiResult.plainText] 仍是全文，「复制输出」拿到的是完整内容。
 */
internal const val MAX_RENDER_CHARS_PER_LINE = 4000

/**
 * 把一行投影为「可安全排版」的渲染文本（纯函数，供 JVM 单测锁定）。
 *
 * 超过 [maxChars] 时截断到该长度（**不切断代理对**，否则截断点会渲染成半个字符），
 * 并追加灰色省略标注说明本行实际有多少字符。
 */
internal fun renderableLine(
    line: AnnotatedString,
    markerStyle: SpanStyle? = null,
    maxChars: Int = MAX_RENDER_CHARS_PER_LINE
): AnnotatedString {
    if (maxChars <= 0 || line.length <= maxChars) return line
    val cut = if (line.text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
    val marker = " …（本行共 ${line.length} 字符，已截断显示）"
    return buildAnnotatedString {
        append(line.subSequence(0, cut))
        if (markerStyle != null) withStyle(markerStyle) { append(marker) } else append(marker)
    }
}

/**
 * 「命令历史」的存档器：`List<String>` 不是 Bundle 原生类型，直接交给 `rememberSaveable`
 * 会在保存（旋转屏幕）时抛异常，故显式转成可保存的列表形态。
 */
private val historySaver: Saver<List<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { it.toList() }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    // 输入框内容与待确认命令用 rememberSaveable：本工程未锁屏幕方向，旋转会重建 Activity，
    // 用普通 remember 会把「刚敲进去的命令」和「刚弹出/刚确认过的高危确认框」一起丢掉——
    // 与「切页丢确认弹窗」是同一类问题（高风险命令无声作废）。
    var inputText by rememberSaveable { mutableStateOf("") }
    // 命令历史：仅本次会话内存保留，不落盘 —— 命令常含密码/token 等敏感参数，
    // 写入 SharedPreferences 会以明文长期留在设备上。
    var cmdHistory by rememberSaveable(stateSaver = historySaver) { mutableStateOf(emptyList<String>()) }
    var showCmdHistory by remember { mutableStateOf(false) }
    // 「跟随尾部」意图：只在用户手动滚动（拖动/惯性）时更新，不受新日志追加影响。
    // 注意不可直接用 `!canScrollForward` 判定「是否在底部」——新内容一追加 canScrollForward 立刻变 true，
    // 会被误判成「用户已向上回看」而永久停止自动滚动。此处只在滚动进行中采样用户真实落点。
    // 发命令 / 清屏时要把它置回 true：否则用户上翻看日志时发出的命令，其回显与结果都落在屏外，
    // 界面看起来「点了没反应」（真实终端总把提示符带回底部）。
    var followTail by remember { mutableStateOf(true) }
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
    //
    // 进度（已消费的日志快照）与解析器、结果放在**同一个可变持有者**里，且在解析块内
    // （非挂起部分）随 feed 一起更新——旋转屏幕会重建 Activity 并取消本协程，
    // 若进度只在协程恢复后写，就会出现「解析器已前进、进度没记」，
    // 下次重进会把同一段再喂一遍，终端出现重复行。
    val parseHolder = remember(terminalDefaultColor) { ParseHolder(TerminalParseCache.state?.takeIf { it.color == terminalDefaultColor }) }
    val cachedParse = parseHolder.state
    val ansiParser = remember(terminalDefaultColor) {
        cachedParse?.parser ?: IncrementalAnsiParser(terminalDefaultColor)
    }
    // 解析代次：组件离/进组合或换色时 +1。in-flight 的解析块没有挂起点、取消打不断，
    // 用它判断「本结果是否已过期」，避免旧协程把 consumedLog 回写成较旧值。
    //
    // 必须用**前置**自增：表达式 `p[0]++` 的值是自增**前**的旧值，写进 p[0] 的是新值，
    // 于是 myGen 恒等于 p[0] - 1，两处 `p[0] == myGen` 守卫恒为假 —— 每次 collect 都在
    // 解析完的结果上直接 return，parsedOutput 永不更新，终端输出区永远空白。
    val parseGenRef = remember { intArrayOf(0) }
    val myGen = remember(terminalDefaultColor) { ++parseGenRef[0] }
    var parsedOutput by remember(terminalDefaultColor) {
        mutableStateOf(cachedParse?.result ?: ParsedAnsiResult(emptyList()))
    }

    // 解析移出主线程：成本与整个日志窗口（250k）成正比，每次发布都会重解析。
    // conflate() 保证同一时刻只有一个解析在跑，中间值直接丢弃。
    //
    // 解析器是**进程级缓存**（TerminalParseCache.state），旋转屏幕后新组合拿到的还是
    // 同一个实例。而下面这个块是纯 CPU、没有挂起点，协程取消打不断它 —— 于是旧 Activity
    // 那个 in-flight 块与新 Activity 的首个 collect 会在两个 Default worker 上同时
    // `curText.append` / `curCols.add` / `completed.add`：
    // ArrayIndexOutOfBounds / StringIndexOutOfBounds 穿出 LaunchedEffect 直接崩进程；
    // 即使不崩，旧协程把 consumedLog 回写成较旧值，下次 startsWith 判错 → 重复行或整段丢行。
    // 故整块同步，并加代次守卫禁止过期协程写回。
    LaunchedEffect(terminalDefaultColor) {
        // 仅「换色 / 首次进入」需要清空重建；复用缓存解析器时保留其状态与进度。
        if (parseHolder.state?.parser !== ansiParser) {
            synchronized(ansiParser) {
                ansiParser.reset()
            }
            parseHolder.state = null
        }
        snapshotFlow { RootService.outputLog }
            .conflate()
            .collect { log ->
                // 复用缓存时首轮日志常与缓存进度一致，此时无需任何解析。
                val prev = synchronized(ansiParser) { parseHolder.state?.consumedLog ?: "" }
                if (log == prev) return@collect
                val snap = withContext(Dispatchers.Default) {
                    val result = synchronized(ansiParser) {
                        if (log.length > prev.length && log.startsWith(prev)) {
                            // 追加：只解析新增部分
                            ansiParser.feed(log.substring(prev.length))
                        } else {
                            // 整体替换（清屏 / 横幅重生成 / 滑动窗口裁剪掉了头部）：无法复用状态，回落全量解析。
                            ansiParser.reset()
                            ansiParser.feed(log)
                        }
                        val r = ansiParser.snapshot()
                        // 进度与结果随 feed 在同一个非挂起块内落定：本协程随后被取消（旋转重建组合）也不会
                        // 出现「解析器已前进、进度没记」——否则重进会把同一段再喂一次，日志出现重复行。
                        // 代次守卫：解析期间若发生了新一轮（换色 / 组件离组合重建），
                        // 本结果已过期，写回会让新组合按错误的进度重喂。
                        if (parseGenRef[0] == myGen) {
                            parseHolder.state?.let { it.consumedLog = log; it.result = r }
                        }
                        r
                    }
                    result
                }
                if (parseGenRef[0] != myGen) return@collect
                if (parseHolder.state == null) {
                    parseHolder.state = TerminalParseState(terminalDefaultColor, ansiParser, log, snap)
                        .also { TerminalParseCache.state = it }
                }
                // 渲染用状态（回主线程写 Compose state）
                parsedOutput = snap
            }
    }

    val isImeVisible = WindowInsets.isImeVisible

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

    /** 记录一条已执行的命令：相邻去重（连续重复只留一条），最多保留 50 条。 */
    fun rememberCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty() || cmdHistory.firstOrNull() == trimmed) return
        cmdHistory = (listOf(trimmed) + cmdHistory).take(50)
    }

    fun handleSend(textToSend: String = inputText) {
        val text = textToSend.trim()
        if (text.isEmpty()) {
            // 「Enter」= 向运行中的交互进程发送一个空行（确认提示 / 翻页等）。
            // 绝不在此清空输入框：用户很可能已键入命令，误点「Enter」会静默丢掉输入且不执行。
            if (RootService.isTaskRunning) {
                RootService.sendInput("")
            } else {
                // 空闲时点「Enter」必须有回应：静默返回会让用户以为终端坏了。
                // 按键语义（提交输入框内容）交给「发送」，这里不代劳。
                Toast.makeText(context, "当前没有运行中的进程，「Enter」仅用于向交互进程发送空行", Toast.LENGTH_LONG).show()
            }
            return
        }
        // 命令直接发送：守卫功能已移除，不再做策略判定与风险确认。
        // 被拒绝（已有命令/任务在跑）时保留输入框内容，用户中断后可直接重发
        if (RootService.sendInput(text)) {
            rememberCommand(text)
            inputText = ""
            followTail = true
        } else {
            // 除终端里的提示外再给一次 Toast：命令没发出去是用户必须立刻知道的事，
            // 只在输出区留一行容易被滚动位置错过。
            Toast.makeText(context, "上一条命令仍在运行，本次未发送", Toast.LENGTH_LONG).show()
        }
    }

    fun copyOutput() {
        val textToCopy = parsedOutput.plainText
        // 无输出时直接说明，不去写剪贴板：写入空串会让用户以为复制成功，
        // 粘贴出来却是空的，反而更困惑。
        if (textToCopy.isBlank()) {
            Toast.makeText(context, "当前没有可复制的输出", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                Toast.makeText(context, "复制失败", Toast.LENGTH_LONG).show()
                return
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("TerminalOutput", textToCopy))
            Toast.makeText(context, "终端输出已复制到剪贴板", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "复制失败", Toast.LENGTH_LONG).show()
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
                            // 投影按行缓存：LazyColumn 复用项时不重复计算（见 MAX_RENDER_CHARS_PER_LINE）
                            val display = remember(line) {
                                renderableLine(line, markerStyle = SpanStyle(color = AuroraTokens.TextSecondary))
                            }
                            Text(
                                text = display,
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
                        .clickable {
                            RootService.clearOutput()
                            // 清屏后日志为空，视口必须回到尾部，否则停在旧滚动位置看不到新输出
                            followTail = true
                        }
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                )

                // 「历史」为空时不禁用：灰字点击后给出说明，否则用户反复点却不知为何无效。
                // 真正无内容的入口才用 enabled=false。
                Text(
                    text = "历史",
                    fontSize = 12.sp,
                    color = if (cmdHistory.isEmpty()) AuroraTokens.TextUnselected else AuroraTokens.Text,
                    modifier = Modifier
                        .clickable {
                            if (cmdHistory.isEmpty()) {
                                Toast.makeText(context, "暂无命令历史", Toast.LENGTH_LONG).show()
                            } else {
                                showCmdHistory = true
                            }
                        }
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                )

                // 「Enter」只发送空行（不携带输入框内容，也不会清空它）；输入框内容交给「发送」
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
                title = "shso 终端提示",
                checked = appSettings.showShsoBanner,
                onCheckedChange = { appSettings.setShsoBanner(it) }
            )
        }
    }


    AuroraWindowDialog(
        show = showCmdHistory,
        title = "命令历史",
        summary = "本次会话内执行过的命令（最多 50 条，仅存内存）",
        onDismissRequest = { showCmdHistory = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
        ) {
            cmdHistory.forEach { cmd ->
                Text(
                    text = cmd,
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { inputText = cmd; showCmdHistory = false }
                        .padding(horizontal = 4.dp, vertical = 10.dp)
                )
            }
        }
        Text(
            text = "清空历史",
            style = AuroraTextStyles.footnote2,
            color = AuroraTokens.Error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { cmdHistory = emptyList(); showCmdHistory = false }
                .padding(horizontal = 4.dp, vertical = 10.dp)
        )
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
}

/**
 * 终端解析状态（解析器实例 + 已消费日志进度 + 快照）。
 * 供 [TerminalParseCache] 在页面被 HorizontalPager 销毁/重建时复用，避免重进终端页重跑全量解析。
 * [color] 为解析所用默认色，颜色变化即失效（与 `remember(terminalDefaultColor)` 的 key 对齐）。
 *
 * [consumedLog] / [result] 可变且 `@Volatile`：解析在 Default 线程跑，进度必须与解析器状态一起
 * 落定（见 `TerminalPage` 的解析协程），跨线程可见性由此保证。
 */
private class TerminalParseState(
    val color: Color,
    val parser: IncrementalAnsiParser,
    @Volatile var consumedLog: String,
    @Volatile var result: ParsedAnsiResult
)

/**
 * 页面内经 `remember` 持有的可变引用。它与单例缓存可能指向**同一个** [TerminalParseState] 对象，
 * 因此就地更新该对象的字段即等于更新缓存，不需要每次解析都重建状态对象。
 */
private class ParseHolder(@Volatile var state: TerminalParseState?)

/** 单例缓存：同一时刻只有终端页在用，故仅保留最近一次 [TerminalParseState]。 */
private object TerminalParseCache {
    @Volatile
    var state: TerminalParseState? = null
}
