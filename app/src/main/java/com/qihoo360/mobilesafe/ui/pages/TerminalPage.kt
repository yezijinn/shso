// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihoo360.mobilesafe.data.AnsiParser
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.RootService
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val terminalDefaultColor = remember(appSettings.terminalTextColor) {
        Color(appSettings.terminalTextColor)
    }

    val parsedOutput = remember(RootService.outputLog, terminalDefaultColor) {
        AnsiParser.parseAnsi(RootService.outputLog, terminalDefaultColor)
    }

    val isImeVisible = WindowInsets.isImeVisible

    LaunchedEffect(RootService.outputLog.length, isImeVisible) {
        delay(60)
        scrollState.animateScrollTo(scrollState.maxValue)
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

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "终端",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { copyOutput() },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MiuixTheme.colorScheme.onSurface
                        ),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("复制输出", fontSize = 12.sp)
                    }

                    Button(
                        enabled = RootService.isTaskRunning,
                        onClick = { RootService.killCurrentProcess() },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.error.copy(0.18f),
                            contentColor = MiuixTheme.colorScheme.error
                        ),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("结束进程", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { RootService.restartTerminal() },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary.copy(0.15f),
                            contentColor = MiuixTheme.colorScheme.primary
                        ),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("重启终端", fontSize = 12.sp)
                    }
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
                    bottom = if (isImeVisible) 0.dp else 4.dp
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF141416))
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
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(Color(0xFF222226).copy(0.85f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (RootService.isTaskRunning) Color(0xFF00E676)
                                else Color(0xFF757575)
                            )
                    )
                    Text(
                        text = if (RootService.isTaskRunning) "RUNNING" else "IDLE",
                        color = Color.White.copy(0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    enabled = RootService.isTaskRunning,
                    onClick = { RootService.sendInterrupt() },
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error.copy(0.18f),
                        contentColor = MiuixTheme.colorScheme.error
                    ),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                ) {
                    Text("中断", fontSize = 12.sp)
                }

                Button(
                    onClick = { RootService.clearOutput() },
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MiuixTheme.colorScheme.onSurface
                    ),
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                ) {
                    Text("清屏", fontSize = 12.sp)
                }

                if (!RootService.isTaskRunning && RootService.lastExecutedPath != null) {
                    Button(
                        onClick = {
                            RootService.lastExecutedPath?.let { path ->
                                RootService.executeFile(path)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary.copy(0.18f),
                            contentColor = MiuixTheme.colorScheme.primary
                        ),
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                    ) {
                        Text("重新运行", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { handleSend("") },
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MiuixTheme.colorScheme.onSurface
                    ),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                ) {
                    Text("Enter", fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = "请输入命令...",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            handleSend(inputText)
                        }
                    ),
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        if (inputText.isNotEmpty()) {
                            IconButton(onClick = { inputText = "" }) {
                                Icon(
                                    imageVector = MiuixIcons.Clear,
                                    contentDescription = "清空输入"
                                )
                            }
                        }
                    }
                )

                Button(
                    onClick = {
                        handleSend(inputText)
                    },
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary
                    ),
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                ) {
                    Text("发送", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            if (!isImeVisible) {
                Spacer(modifier = Modifier.height(76.dp))
            }
        }
    }
}
