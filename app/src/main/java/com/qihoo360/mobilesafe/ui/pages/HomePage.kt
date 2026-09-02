// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "shso",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            if (RootService.isTaskRunning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Text(
                                text = "有任务正在进行中",
                                style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = "正在执行: ${RootService.currentTaskName ?: "后台脚本"}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )

                        Text(
                            text = "路径: ${RootService.currentTaskPath ?: ""}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary.copy(0.7f)
                        )

                        Text(
                            text = "已运行时间: ${elapsedSeconds}s",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )

                        Button(
                            onClick = onNavigateToTerminal,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary,
                                contentColor = MiuixTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("返回终端查看进度")
                        }
                    }
                }
            }

            Text(
                text = "执行目标",
                modifier = Modifier.padding(PaddingValues(start = 0.dp, top = 8.dp, bottom = 4.dp)),
                style = MiuixTheme.textStyles.subtitle.copy(fontSize = (MiuixTheme.textStyles.subtitle.fontSize.value - 5f).sp),
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    TextField(
                        value = filePathInput,
                        onValueChange = {
                            filePathInput = it
                            validationError = null
                        },
                        label = "请输入文件路径",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        textStyle = MiuixTheme.textStyles.main.copy(
                            fontSize = (MiuixTheme.textStyles.main.fontSize.value - 5f).sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { showFilePicker = true },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MiuixTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("从文件管理器选择", fontSize = (MiuixTheme.textStyles.main.fontSize.value - 5f).sp, fontWeight = FontWeight.Medium)
                    }

                    if (validationError != null) {
                        Text(
                            text = validationError ?: "",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                            fontSize = 7.sp
                        )
                    }

                    Text(
                        text = "支持输入文件路径或选择文件进行执行，目前仅支持 .sh 和 .so 文件",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        fontSize = (MiuixTheme.textStyles.footnote2.fontSize.value - 1f).sp
                    )

                    Button(
                        enabled = filePathInput.isNotBlank(),
                        onClick = { execute(filePathInput) },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = if (RootService.isTaskRunning) "任务运行中 (点击覆盖启动)" else "立即执行",
                            fontSize = (MiuixTheme.textStyles.main.fontSize.value - 5f).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SmallTitle(
                        text = "shso 目录文件",
                        insideMargin = PaddingValues(start = 0.dp, top = 8.dp, bottom = 2.dp)
                    )
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
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
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
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回上级",
                                tint = MiuixTheme.colorScheme.primary,
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
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "刷新",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                if (shsoFiles.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "当前目录暂无文件或文件夹",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Text(
                            text = "可在「文件」页面长按任意文件选择「添加到shso」",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary.copy(0.7f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
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
                                        if (isSelected) MiuixTheme.colorScheme.primary.copy(0.08f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                fileItem.isDirectory -> MiuixTheme.colorScheme.primary.copy(0.15f)
                                                fileItem.isExecutableScript -> Color(0xFF4CAF50).copy(0.2f)
                                                fileItem.isExecutableBinary -> Color(0xFF2196F3).copy(0.2f)
                                                else -> MiuixTheme.colorScheme.surfaceContainerHighest
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = when {
                                            fileItem.isDirectory -> "📁"
                                            fileItem.isExecutableScript -> "SH"
                                            fileItem.isExecutableBinary -> "SO"
                                            else -> "📄"
                                        },
                                        fontSize = if (fileItem.isDirectory || !isExecutable) 14.sp else 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            fileItem.isDirectory -> MiuixTheme.colorScheme.primary
                                            fileItem.isExecutableScript -> Color(0xFF2E7D32)
                                            fileItem.isExecutableBinary -> Color(0xFF1565C0)
                                            else -> MiuixTheme.colorScheme.onSurfaceSecondary
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileItem.name,
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Normal,
                                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (fileItem.isDirectory) "文件夹 (点击进入)" else fileItem.formattedSize,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                }

                                if (!fileItem.isDirectory) {
                                    Button(
                                        onClick = {
                                            filePathInput = fileItem.path
                                            validationError = null
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
                                            contentColor = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                                        ),
                                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                    ) {
                                        Text(
                                            text = if (isSelected) "已选择" else "选择",
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            if (index < shsoFiles.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(70.dp))
        }
    }

    if (showFilePicker) {
        BuiltInFilePicker(
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
