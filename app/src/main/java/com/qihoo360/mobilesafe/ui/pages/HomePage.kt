// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState


import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.FileItem
import com.qihoo360.mobilesafe.data.RootFileManager
import com.qihoo360.mobilesafe.data.RootService
import com.qihoo360.mobilesafe.ui.components.BuiltInFilePicker
import com.qihoo360.mobilesafe.ui.theme.AuroraAccentBar
import com.qihoo360.mobilesafe.ui.theme.AuroraSectionTitle
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.auroraFilledButton
import com.qihoo360.mobilesafe.ui.theme.auroraTextFieldColors
import com.qihoo360.mobilesafe.ui.theme.auroraPrimaryButtonColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File

@Composable
fun HomePage(
    appSettings: AppSettings,
    onNavigateToTerminal: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var filePathInput by remember { mutableStateOf("") }
    var showFilePicker by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    var currentShsoDir by remember { mutableStateOf(RootFileManager.DEFAULT_SHSO_DIR) }
    var shsoFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isScanningShso by remember { mutableStateOf(false) }

    // shso 目录文件列表：字号跟随全局文件列表字号设置（与「文件」页一致）
    val listFontSize = appSettings.fileListFontSize.sp
    val listSecondaryFontSize = (appSettings.fileListFontSize - 5f).coerceAtLeast(8f).sp

    fun refreshShsoFiles(targetDir: String = currentShsoDir) {
        isScanningShso = true
        currentShsoDir = targetDir
        coroutineScope.launch {
            try {
                RootFileManager.ensureShsoDir()
                val files = RootFileManager.listFiles(targetDir)
                shsoFiles = files.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() }
                )
            } catch (_: Exception) {
                shsoFiles = emptyList()
            } finally {
                isScanningShso = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshShsoFiles(RootFileManager.DEFAULT_SHSO_DIR)
    }

    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(RootService.isTaskRunning, RootService.taskStartTime) {
        while (RootService.isTaskRunning) {
            val start = RootService.taskStartTime
            if (start > 0) {
                elapsedSeconds = (System.currentTimeMillis() - start) / 1000
            }
            delay(1000)
        }
    }

    fun execute(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            validationError = "请输入或选择要执行的文件路径"
            return
        }

        val isSh = trimmed.endsWith(".sh", ignoreCase = true)
        val isSo = trimmed.endsWith(".so", ignoreCase = true)

        if (!isSh && !isSo) {
            validationError = "格式不支持！shso 仅允许执行 .sh 脚本和 .so 二进制程序"
            return
        }

        validationError = null
        RootService.executeFile(trimmed)
        onNavigateToTerminal()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AuroraTokens.Surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "shso",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuroraTokens.Text
                )
                Spacer(modifier = Modifier.height(4.dp))
                AuroraAccentBar(width = 36.dp, height = 3.dp)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            if (RootService.isTaskRunning) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(0.dp))
                                .background(AuroraTokens.Accent)
                        )
                        Text(
                            text = "有任务正在进行中",
                            style = AuroraTextStyles.title4,
                            fontWeight = FontWeight.Bold,
                            color = AuroraTokens.Text
                        )
                    }

                    Text(
                        text = "正在执行: ${RootService.currentTaskName ?: "后台脚本"}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.TextSecondary
                    )

                    Text(
                        text = "路径: ${RootService.currentTaskPath ?: ""}",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary.copy(0.7f)
                    )

                    Text(
                        text = "已运行时间: ${elapsedSeconds}s",
                        style = AuroraTextStyles.footnote1,
                        color = AuroraTokens.Accent,
                        fontWeight = FontWeight.SemiBold
                    )

                    Button(
                        onClick = onNavigateToTerminal,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .auroraFilledButton(),
                        colors = auroraPrimaryButtonColors()
                    ) {
                        Text("返回终端查看进度")
                    }
                }
            }

            TextField(
                value = filePathInput,
                onValueChange = {
                    filePathInput = it
                    validationError = null
                },
                label = { Text("请输入文件路径") },
                singleLine = true,
                textStyle = AuroraTextStyles.main.copy(
                    fontSize = (AuroraTextStyles.main.fontSize.value - 5f).sp
                ),
                colors = auroraTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.dp))
            )

            // 「从文件管理器选择」入口：无框裸文字 + 底部小字说明，与输入框构成「键入 / 选择」二选一
            Text(
                text = "从文件管理器选择",
                fontSize = (AuroraTextStyles.main.fontSize.value - 5f).sp,
                fontWeight = FontWeight.Medium,
                color = AuroraTokens.Accent,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = { showFilePicker = true })
                    .padding(vertical = 6.dp)
            )

            Text(
                text = "支持输入文件路径或选择文件进行执行，目前仅支持 .sh 和 .so 文件",
                style = AuroraTextStyles.footnote2,
                color = AuroraTokens.TextSecondary,
                fontSize = (AuroraTextStyles.footnote2.fontSize.value - 1f).sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (validationError != null) {
                Text(
                    text = validationError ?: "",
                    style = AuroraTextStyles.footnote1,
                    color = AuroraTokens.Error,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Button(
                enabled = filePathInput.isNotBlank(),
                onClick = { execute(filePathInput) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .auroraFilledButton(),
                colors = auroraPrimaryButtonColors()
            ) {
                Text(
                    text = if (RootService.isTaskRunning) "任务运行中 (点击覆盖启动)" else "立即执行",
                    fontSize = (AuroraTextStyles.main.fontSize.value - 5f).sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AuroraSectionTitle(text = "shso 目录文件")
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        val approxChars = (maxWidth.value / 7.2f).toInt().coerceAtLeast(16)
                        val displayPath = if (currentShsoDir.length > approxChars) {
                            "..." + currentShsoDir.takeLast(approxChars - 3)
                        } else {
                            currentShsoDir
                        }
                        Text(
                            text = displayPath,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AuroraTokens.TextSecondary,
                            maxLines = 1
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentShsoDir != RootFileManager.DEFAULT_SHSO_DIR) {
                        IconButton(
                            onClick = {
                                val parent = File(currentShsoDir).parent ?: RootFileManager.DEFAULT_SHSO_DIR
                                refreshShsoFiles(parent)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回上级",
                                tint = AuroraTokens.Accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    IconButton(
                        onClick = { refreshShsoFiles() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = AuroraTokens.Accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (shsoFiles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "当前目录暂无文件或文件夹",
                        style = AuroraTextStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = AuroraTokens.TextSecondary
                    )
                    Text(
                        text = "可在「文件」页面长按任意文件选择「添加到shso」",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary.copy(0.7f)
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    shsoFiles.forEachIndexed { index, fileItem ->
                        val isSelected = filePathInput == fileItem.path
                        val isExecutable = fileItem.isExecutableScript || fileItem.isExecutableBinary

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (fileItem.isDirectory) {
                                        refreshShsoFiles(fileItem.path)
                                    } else {
                                        filePathInput = fileItem.path
                                        validationError = null
                                    }
                                }
                                .background(
                                    if (isSelected) AuroraTokens.Accent.copy(0.08f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 类型图标：无底色方框、左右零间隙，直接裸文字
                            Text(
                                text = when {
                                    fileItem.isDirectory -> "📁"
                                    fileItem.isExecutableScript -> "SH"
                                    fileItem.isExecutableBinary -> "SO"
                                    else -> "📄"
                                },
                                fontSize = if (fileItem.isDirectory || !isExecutable) 16.sp else 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    fileItem.isDirectory -> AuroraTokens.Accent
                                    fileItem.isExecutableScript -> AuroraTokens.Accent
                                    fileItem.isExecutableBinary -> AuroraTokens.GlowBlue
                                    else -> AuroraTokens.TextSecondary
                                }
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fileItem.name,
                                    style = AuroraTextStyles.body1,
                                    fontSize = listFontSize,
                                    fontWeight = FontWeight.Normal,
                                    color = if (isSelected) AuroraTokens.Accent else AuroraTokens.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (fileItem.isDirectory) "文件夹" else fileItem.formattedSize,
                                        style = AuroraTextStyles.footnote2,
                                        fontSize = listSecondaryFontSize,
                                        color = AuroraTokens.TextSecondary
                                    )
                                    if (fileItem.permissions.isNotEmpty()) {
                                        Text(
                                            text = fileItem.permissions,
                                            style = AuroraTextStyles.footnote2,
                                            fontSize = listSecondaryFontSize,
                                            fontFamily = FontFamily.Monospace,
                                            color = AuroraTokens.TextSecondary.copy(0.7f)
                                        )
                                    }
                                }
                            }

                            if (!fileItem.isDirectory) {
                                Button(
                                    onClick = {
                                        filePathInput = fileItem.path
                                        validationError = null
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) AuroraTokens.Accent else AuroraTokens.SurfaceHover,
                                        contentColor = if (isSelected) AuroraTokens.OnAccent else AuroraTokens.Text
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                    modifier = Modifier.clip(RoundedCornerShape(0.dp))
                                ) {
                                    Text(
                                        text = if (isSelected) "已选择" else "选择",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        if (index < shsoFiles.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .height(0.7.dp)
                                    .background(AuroraTokens.SurfaceHover.copy(alpha = 0.6f))
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(56.dp))
        }
    }

    if (showFilePicker) {
        BuiltInFilePicker(
            appSettings = appSettings,
            show = true,
            initialDirectory = "/storage/emulated/0",
            onDismissRequest = { showFilePicker = false },
            onFileSelected = { selectedPath ->
                filePathInput = selectedPath
                showFilePicker = false
                validationError = null
            }
        )
    }
}
