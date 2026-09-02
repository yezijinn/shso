// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

@Composable
fun FilePage(
    appSettings: AppSettings,
    onExecuteFileAndNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isMaterial = appSettings.appThemeOption == AppSettings.THEME_MATERIAL

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
        RootFileManager.ensureKernelEXDir()
        refresh()
    }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            feedbackMessage = null
        }
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
                    text = "文件管理器",
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
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(if (isMaterial) RoundedCornerShape(16.dp) else RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val parent = File(currentDirectory).parent ?: "/"
                            currentDirectory = parent
                        },
                        enabled = currentDirectory != "/"
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回上一级",
                            tint = if (currentDirectory != "/") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        )
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                jumpPathInput = currentDirectory
                                showJumpPathDialog = true
                            }
                            .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        val approxChars = (maxWidth.value / 7.6f).toInt().coerceAtLeast(16)
                        val displayPath = if (currentDirectory.length > approxChars) {
                            "..." + currentDirectory.takeLast(approxChars - 3)
                        } else {
                            currentDirectory
                        }
                        Text(
                            text = displayPath,
                            style = MiuixTheme.textStyles.footnote1,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { currentDirectory = "/" },
                        colors = ButtonDefaults.buttonColors(
                            color = if (currentDirectory == "/") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (currentDirectory == "/") MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                        ),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = if (isMaterial) Modifier.weight(1f).clip(RoundedCornerShape(20.dp)) else Modifier.weight(1f)
                    ) {
                        Text("根目录 (/)", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { currentDirectory = "/storage/emulated/0" },
                        colors = ButtonDefaults.buttonColors(
                            color = if (currentDirectory == "/storage/emulated/0") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (currentDirectory == "/storage/emulated/0") MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                        ),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = if (isMaterial) Modifier.weight(1.2f).clip(RoundedCornerShape(20.dp)) else Modifier.weight(1.2f)
                    ) {
                        Text("内部存储", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { currentDirectory = RootFileManager.DEFAULT_KERNEL_EX_DIR },
                        colors = ButtonDefaults.buttonColors(
                            color = if (currentDirectory == RootFileManager.DEFAULT_KERNEL_EX_DIR) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (currentDirectory == RootFileManager.DEFAULT_KERNEL_EX_DIR) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                        ),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = if (isMaterial) Modifier.weight(1.3f).clip(RoundedCornerShape(20.dp)) else Modifier.weight(1.3f)
                    ) {
                        Text("KernelEX目录", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (fileList.isEmpty() && !isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "当前目录为空",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(fileList, key = { index, item -> "${item.path}_$index" }) { _, item ->
                            val isExecutable = item.isExecutableScript || item.isExecutableBinary
                            val isFontFile = !item.isDirectory && (item.name.endsWith(".ttf", ignoreCase = true) || item.name.endsWith(".otf", ignoreCase = true))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(if (isMaterial) RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.surfaceContainer)
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
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                item.isDirectory -> MiuixTheme.colorScheme.primary.copy(0.15f)
                                                item.isExecutableScript -> Color(0xFF4CAF50).copy(0.2f)
                                                item.isExecutableBinary -> Color(0xFF2196F3).copy(0.2f)
                                                isFontFile -> Color(0xFF9C27B0).copy(0.2f)
                                                else -> MiuixTheme.colorScheme.surfaceContainerHighest
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
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
                                            item.isDirectory -> MiuixTheme.colorScheme.primary
                                            item.isExecutableScript -> Color(0xFF2E7D32)
                                            item.isExecutableBinary -> Color(0xFF1565C0)
                                            isFontFile -> Color(0xFF7B1FA2)
                                            else -> MiuixTheme.colorScheme.onSurfaceSecondary
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.Normal,
                                        color = MiuixTheme.colorScheme.onSurface,
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
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                                        )

                                        if (item.permissions.isNotEmpty()) {
                                            Text(
                                                text = item.permissions,
                                                style = MiuixTheme.textStyles.footnote2,
                                                fontFamily = FontFamily.Monospace,
                                                color = MiuixTheme.colorScheme.onSurfaceSecondary.copy(0.7f)
                                            )
                                        }
                                    }
                                }

                                if (isExecutable) {
                                    Button(
                                        onClick = { onExecuteFileAndNavigate(item.path) },
                                        colors = ButtonDefaults.buttonColors(
                                            color = MiuixTheme.colorScheme.primary,
                                            contentColor = MiuixTheme.colorScheme.onPrimary
                                        ),
                                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        modifier = if (isMaterial) Modifier.clip(RoundedCornerShape(20.dp)) else Modifier
                                    ) {
                                        Text("执行", fontSize = 12.sp)
                                    }
                                } else if (isFontFile) {
                                    Button(
                                        onClick = {
                                            previewFontItem = item
                                            showFontPreviewDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            color = MiuixTheme.colorScheme.primary,
                                            contentColor = MiuixTheme.colorScheme.onPrimary
                                        ),
                                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        modifier = if (isMaterial) Modifier.clip(RoundedCornerShape(20.dp)) else Modifier
                                    ) {
                                        Text("预览", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }

    if (showActionDialog && selectedItem != null) {
        val item = selectedItem!!
        WindowDialog(
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
                            val (success, resultPath) = RootFileManager.addFileToKernelEX(
                                sourcePath = item.path,
                                useIndependentFolder = appSettings.useIndependentFolder,
                                autoDeleteSource = appSettings.autoDeleteAfterAdding
                            )
                            if (success) {
                                feedbackMessage = "已添加到 KernelEX: $resultPath"
                                refresh()
                                if (appSettings.autoExecuteAfterAdding && (item.isExecutableScript || item.isExecutableBinary)) {
                                    onExecuteFileAndNavigate(resultPath)
                                }
                            } else {
                                feedbackMessage = resultPath
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("添加到KernelEX")
                }

                Button(
                    onClick = {
                        showActionDialog = false
                        renameInput = item.name
                        showRenameDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MiuixTheme.colorScheme.onSurface
                    )
                ) {
                    Text("重命名")
                }

                Button(
                    onClick = {
                        showActionDialog = false
                        showDeleteDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.error.copy(0.15f),
                        contentColor = MiuixTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            }
        }
    }

    if (showRenameDialog && selectedItem != null) {
        val item = selectedItem!!
        WindowDialog(
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
                    label = "输入新名称",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showRenameDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MiuixTheme.colorScheme.onSurface
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
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimary
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
        WindowDialog(
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
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MiuixTheme.colorScheme.onSurface
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
                        color = MiuixTheme.colorScheme.error,
                        contentColor = MiuixTheme.colorScheme.onError
                    )
                ) {
                    Text("确认删除")
                }
            }
        }
    }

    if (showJumpPathDialog) {
        WindowDialog(
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
                    label = "路径（例如 /data/adb/modules）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showJumpPathDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MiuixTheme.colorScheme.onSurface
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
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimary
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

        WindowDialog(
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (customTestText.isNotEmpty()) customTestText else "KernelEX 任务调度引擎",
                            fontFamily = targetFontFamily,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz 0123456789",
                            fontFamily = targetFontFamily,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "天地玄黄 宇宙洪荒 日月盈昃 辰宿列张\n极速流式任务调度 高并发内核增强",
                            fontFamily = targetFontFamily,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }

                TextField(
                    value = customTestText,
                    onValueChange = { customTestText = it },
                    label = "输入任意文字实时预览效果...",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("应用为软件字体", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { showFontPreviewDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MiuixTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text("关闭", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
