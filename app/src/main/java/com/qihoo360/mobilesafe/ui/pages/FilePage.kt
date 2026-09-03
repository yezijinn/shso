// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.FileItem
import com.qihoo360.mobilesafe.data.RootFileManager
import com.qihoo360.mobilesafe.ui.components.FileListSettingsDialog
import com.qihoo360.mobilesafe.ui.components.FileShortcutButton
import com.qihoo360.mobilesafe.ui.components.applyFileViewSettings
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.AuroraWindowDialog
import com.qihoo360.mobilesafe.ui.theme.auroraFilledButton
import com.qihoo360.mobilesafe.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FilePage(
    appSettings: AppSettings,
    onExecuteFileAndNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentDirectory by remember { mutableStateOf("/storage/emulated/0") }
    var fileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var selectedItem by remember { mutableStateOf<FileItem?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var showJumpPathDialog by remember { mutableStateOf(false) }
    var jumpPathInput by remember { mutableStateOf("") }

    var showFontPreviewDialog by remember { mutableStateOf(false) }
    var previewFontItem by remember { mutableStateOf<FileItem?>(null) }

    var showFileSettingsDialog by remember { mutableStateOf(false) }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        isLoading = true
        scope.launch {
            try {
                fileList = RootFileManager.listFiles(currentDirectory)
            } catch (_: Exception) {
                fileList = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(currentDirectory) {
        RootFileManager.ensureShsoDir()
        refresh()
    }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            feedbackMessage = null
        }
    }

    val displayFileList = remember(fileList, appSettings.showHiddenFiles, appSettings.fileSortMode) {
        applyFileViewSettings(fileList, appSettings.showHiddenFiles, appSettings.fileSortMode)
    }
    val listFontSize = appSettings.fileListFontSize.sp
    val listSecondaryFontSize = (appSettings.fileListFontSize - 5f).coerceAtLeast(8f).sp

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AuroraTokens.Surface)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .clickable(enabled = currentDirectory != "/") {
                            val parent = File(currentDirectory).parent ?: "/"
                            currentDirectory = parent
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回上一级",
                        tint = if (currentDirectory != "/") {
                            AuroraTokens.Text
                        } else {
                            AuroraTokens.TextDisabled
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                FileShortcutButton(
                    label = "data",
                    selected = currentDirectory == "/",
                    onClick = { currentDirectory = "/" },
                    modifier = Modifier.weight(1f)
                )

                FileShortcutButton(
                    label = "storage",
                    selected = currentDirectory == "/storage/emulated/0",
                    onClick = { currentDirectory = "/storage/emulated/0" },
                    modifier = Modifier.weight(1f)
                )

                FileShortcutButton(
                    label = "shso",
                    selected = currentDirectory == RootFileManager.DEFAULT_SHSO_DIR,
                    onClick = { currentDirectory = RootFileManager.DEFAULT_SHSO_DIR },
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .clickable { showFileSettingsDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "文件列表设置",
                        tint = AuroraTokens.Text,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 路径行：独占一整行，固定可容纳两行文本的高度，点击仍弹「跳转路径」
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clickable {
                        jumpPathInput = currentDirectory
                        showJumpPathDialog = true
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = currentDirectory,
                    style = AuroraTextStyles.footnote1,
                    fontFamily = FontFamily.Monospace,
                    color = AuroraTokens.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 路径行与列表区的分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(AuroraTokens.SurfaceHover.copy(alpha = 0.6f))
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (displayFileList.isEmpty() && !isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "当前目录为空",
                            style = AuroraTextStyles.body2,
                            color = AuroraTokens.TextSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(displayFileList, key = { index, item -> "${item.path}_$index" }) { _, item ->
                            val isExecutable = item.isExecutableScript || item.isExecutableBinary
                            val isFontFile = !item.isDirectory && (item.name.endsWith(".ttf", ignoreCase = true) || item.name.endsWith(".otf", ignoreCase = true))

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (item.isDirectory) {
                                                    currentDirectory = item.path
                                                }
                                            },
                                            onLongClick = {
                                                selectedItem = item
                                                showActionDialog = true
                                            }
                                        )
                                        .padding(horizontal = 16.dp, vertical = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                // 类型图标：无底色方框、左右零间隙，直接裸文字
                                Text(
                                    text = when {
                                        item.isDirectory -> "📁"
                                        item.isExecutableScript -> "SH"
                                        item.isExecutableBinary -> "SO"
                                        isFontFile -> if (item.name.endsWith(".otf", ignoreCase = true)) "OTF" else "TTF"
                                        else -> "📄"
                                    },
                                    fontSize = if (item.isDirectory || (!isExecutable && !isFontFile)) 16.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        item.isDirectory -> AuroraTokens.Accent
                                        item.isExecutableScript -> AuroraTokens.Accent
                                        item.isExecutableBinary -> AuroraTokens.GlowBlue
                                        isFontFile -> AuroraTokens.AccentViolet
                                        else -> AuroraTokens.TextSecondary
                                    }
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = AuroraTextStyles.body1,
                                        fontSize = listFontSize,
                                        fontWeight = FontWeight.Normal,
                                        color = AuroraTokens.Text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (item.isDirectory) "文件夹" else item.formattedSize,
                                            style = AuroraTextStyles.footnote2,
                                            fontSize = listSecondaryFontSize,
                                            color = AuroraTokens.TextSecondary
                                        )

                                        if (item.permissions.isNotEmpty()) {
                                            Text(
                                                text = item.permissions,
                                                style = AuroraTextStyles.footnote2,
                                                fontSize = listSecondaryFontSize,
                                                fontFamily = FontFamily.Monospace,
                                                color = AuroraTokens.TextSecondary.copy(0.7f)
                                            )
                                        }
                                    }
                                }

                                if (isExecutable) {
                                    // 「执行」按钮：去掉矩形底，直接裸文字 + 红色加粗（与终端页按钮裸文字化风格一致）
                                    Text(
                                        text = "执行",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AuroraTokens.Error,
                                        modifier = Modifier
                                            .clickable { onExecuteFileAndNavigate(item.path) }
                                            .padding(horizontal = 6.dp, vertical = 8.dp)
                                    )
                                } else if (isFontFile) {
                                    Button(
                                        onClick = {
                                            previewFontItem = item
                                            showFontPreviewDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AuroraTokens.Accent,
                                            contentColor = AuroraTokens.OnAccent
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        modifier = Modifier.clip(RoundedCornerShape(0.dp))
                                    ) {
                                        Text("预览", fontSize = 12.sp)
                                    }
                                }
                                }

                                // inset 分割线：图标已无底色方框，线从行内容起点起
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
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }

    if (showFileSettingsDialog) {
        FileListSettingsDialog(
            appSettings = appSettings,
            onDismissRequest = { showFileSettingsDialog = false }
        )
    }

    if (showActionDialog && selectedItem != null) {
        val item = selectedItem!!
        AuroraWindowDialog(
            show = true,
            title = item.name,
            onDismissRequest = { showActionDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        showActionDialog = false
                        scope.launch {
                            val (success, resultPath) = RootFileManager.addFileToShso(
                                sourcePath = item.path,
                                useIndependentFolder = appSettings.useIndependentFolder,
                                autoDeleteSource = appSettings.autoDeleteAfterAdding
                            )
                            if (success) {
                                feedbackMessage = "已添加到 shso: $resultPath"
                                refresh()
                                if (appSettings.autoExecuteAfterAdding && (item.isExecutableScript || item.isExecutableBinary)) {
                                    onExecuteFileAndNavigate(resultPath)
                                }
                            } else {
                                feedbackMessage = resultPath
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .auroraFilledButton(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.Accent,
                        contentColor = AuroraTokens.OnAccent
                    )
                ) {
                    Text("添加到shso")
                }

                Button(
                    onClick = {
                        showActionDialog = false
                        renameInput = item.name
                        showRenameDialog = true
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .auroraFilledButton(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.SurfaceHover,
                        contentColor = AuroraTokens.Text
                    )
                ) {
                    Text("重命名")
                }

                Button(
                    onClick = {
                        showActionDialog = false
                        showDeleteDialog = true
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .auroraFilledButton(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.Error.copy(0.15f),
                        contentColor = AuroraTokens.Error
                    )
                ) {
                    Text("删除")
                }
            }
        }
    }

    if (showRenameDialog && selectedItem != null) {
        val item = selectedItem!!
        AuroraWindowDialog(
            show = true,
            title = "重命名",
            onDismissRequest = { showRenameDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("输入新名称") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showRenameDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = renameInput.isNotBlank() && renameInput != item.name,
                        onClick = {
                            val targetName = renameInput.trim()
                            showRenameDialog = false
                            scope.launch {
                                val (success, message) = RootFileManager.rename(item.path, targetName)
                                feedbackMessage = if (success) "重命名成功" else "重命名失败: $message"
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("确认")
                    }
                }
            }
        }
    }

    if (showDeleteDialog && selectedItem != null) {
        val item = selectedItem!!
        AuroraWindowDialog(
            show = true,
            title = "确认删除",
            summary = "您确定要删除 \"${item.name}\" 吗？此操作无法撤销。",
            onDismissRequest = { showDeleteDialog = false }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showDeleteDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.SurfaceHover,
                        contentColor = AuroraTokens.Text
                    )
                ) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            val (success, message) = RootFileManager.delete(item.path)
                            feedbackMessage = if (success) "删除成功" else "删除失败: $message"
                            refresh()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.Error,
                        contentColor = Color.White
                    )
                ) {
                    Text("确认删除")
                }
            }
        }
    }

    if (showJumpPathDialog) {
        AuroraWindowDialog(
            show = true,
            title = "跳转路径",
            summary = "请输入要跳转的目标文件夹绝对路径：",
            onDismissRequest = { showJumpPathDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = jumpPathInput,
                    onValueChange = { jumpPathInput = it },
                    label = { Text("路径（例如 /data/adb/modules）") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showJumpPathDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = jumpPathInput.isNotBlank(),
                        onClick = {
                            var targetPath = jumpPathInput.trim()
                            if (!targetPath.startsWith("/")) {
                                targetPath = "/$targetPath"
                            }
                            showJumpPathDialog = false
                            currentDirectory = targetPath
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("跳转")
                    }
                }
            }
        }
    }

    if (showFontPreviewDialog && previewFontItem != null) {
        val targetItem = previewFontItem!!
        val targetFontFamily = remember(targetItem.path) {
            try {
                FontFamily(android.graphics.Typeface.createFromFile(File(targetItem.path)))
            } catch (_: Exception) {
                FontFamily.Default
            }
        }
        var customTestText by remember { mutableStateOf("") }

        AuroraWindowDialog(
            show = true,
            title = "字体预览",
            summary = "${targetItem.name} (${targetItem.formattedSize})",
            onDismissRequest = { showFontPreviewDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (customTestText.isNotEmpty()) customTestText else "shso 任务调度引擎",
                            fontFamily = targetFontFamily,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuroraTokens.Text
                        )
                        Text(
                            text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz 0123456789",
                            fontFamily = targetFontFamily,
                            fontSize = 12.sp,
                            color = AuroraTokens.Text
                        )
                        Text(
                            text = "天地玄黄 宇宙洪荒 日月盈昃 辰宿列张\n极速流式任务调度 高并发内核增强",
                            fontFamily = targetFontFamily,
                            fontSize = 12.sp,
                            color = AuroraTokens.TextSecondary
                        )
                    }
                }

                TextField(
                    value = customTestText,
                    onValueChange = { customTestText = it },
                    label = { Text("输入任意文字实时预览效果...") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            try {
                                val destFile = File(context.filesDir, "custom_app_font.ttf")
                                File(targetItem.path).copyTo(destFile, overwrite = true)
                                appSettings.setCustomFont(destFile.absolutePath, targetItem.name)
                                Toast.makeText(context, "已成功应用为软件字体: ${targetItem.name}", Toast.LENGTH_SHORT).show()
                                showFontPreviewDialog = false
                            } catch (e: Exception) {
                                Toast.makeText(context, "应用字体失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("应用为软件字体", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { showFontPreviewDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("关闭", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
