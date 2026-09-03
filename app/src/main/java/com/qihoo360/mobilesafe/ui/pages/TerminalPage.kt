// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihoo360.mobilesafe.data.AnsiParser
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.RootService
import com.qihoo360.mobilesafe.ui.components.ColorWheelDialog
import com.qihoo360.mobilesafe.ui.theme.AuroraSwitchPreference
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraThinSlider
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.AuroraWindowDialog
import com.qihoo360.mobilesafe.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    var showTerminalSettings by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }

    val terminalDefaultColor = remember(appSettings.terminalTextColor) {
        Color(appSettings.terminalTextColor)
    }

    val parsedOutput = remember(RootService.outputLog, terminalDefaultColor) {
        AnsiParser.parseAnsi(RootService.outputLog, terminalDefaultColor)
    }

    val isImeVisible = WindowInsets.isImeVisible

    // 是否「停留在底部」：距底部容差 100px 内视为在底部。
    // 仅当用户已在底部时才自动滚动，防止新日志把正在向上回看的用户拽回底部。
    val isAtBottom = remember {
        derivedStateOf {
            scrollState.value >= scrollState.maxValue - 100
        }
    }

    LaunchedEffect(RootService.outputLog.length, isImeVisible) {
        delay(60)
        if (isAtBottom.value) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    fun handleSend(textToSend: String = inputText) {
        RootService.sendInput(textToSend)
        inputText = ""
    }

    fun copyOutput() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val textToCopy = parsedOutput.text.text
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
                // IDLE/RUNNING 状态：占左侧，色点 + 文本等宽，无矩形背景
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(
                                if (taskRunning) AuroraTokens.Accent
                                else AuroraTokens.TextUnselected
                            )
                    )
                    Text(
                        text = if (taskRunning) "RUNNING" else "IDLE",
                        color = if (taskRunning) AuroraTokens.Accent else AuroraTokens.TextSecondary,
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = parsedOutput.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = appSettings.terminalFontSize.sp,
                            lineHeight = (appSettings.terminalFontSize + 5f).sp
                        )
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
}
