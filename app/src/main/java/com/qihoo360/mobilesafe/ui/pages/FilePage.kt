// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import android.widget.Toast
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
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

/**
 * Applies the FilePage list view preferences to a freshly loaded file list:
 * filters hidden files (if disabled) and sorts with directories always first.
 */
private fun applyFileViewSettings(
    list: List<FileItem>,
    showHiddenFiles: Boolean,
    sortMode: Int
): List<FileItem> {
    val filtered = if (showHiddenFiles) list else list.filter { !it.name.startsWith(".") }
    val directories = filtered.filter { it.isDirectory }
    val files = filtered.filter { !it.isDirectory }

    val sortByTime = sortMode == AppSettings.FILE_SORT_TIME_ASC || sortMode == AppSettings.FILE_SORT_TIME_DESC
    val descending = sortMode == AppSettings.FILE_SORT_NAME_DESC || sortMode == AppSettings.FILE_SORT_TIME_DESC

    val baseComparator = if (sortByTime) {
        compareBy<FileItem> { it.lastModified }
    } else {
        compareBy<FileItem> { it.name.lowercase(Locale.getDefault()) }
    }
    val comparator = if (descending) baseComparator.reversed() else baseComparator

    return directories.sortedWith(comparator) + files.sortedWith(comparator)
}

@Composable
private fun SortModePillButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
        ),
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Compact equal-width shortcut chip used in the FilePage top toolbar row.
 * data / storage / shso share the same height and visual weight as the
 * surrounding icon buttons.
 */
@Composable
private fun FileShortcutButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHighest
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

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
                    .background(MiuixTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                        .clickable(enabled = currentDirectory != "/") {
                            val parent = File(currentDirectory).parent ?: "/"
                            currentDirectory = parent
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "返回上一级",
                        tint = if (currentDirectory != "/") {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.disabledOnSecondaryVariant
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
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                        .clickable { showFileSettingsDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MiuixIcons.Settings,
                        contentDescription = "文件列表设置",
                        tint = MiuixTheme.colorScheme.onSurface,
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
                    style = MiuixTheme.textStyles.footnote1,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 路径行与列表区的分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
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
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
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
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
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
                                        fontSize = listFontSize,
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
                                            fontSize = listSecondaryFontSize,
                                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                                        )

                                        if (item.permissions.isNotEmpty()) {
                                            Text(
                                                text = item.permissions,
                                                style = MiuixTheme.textStyles.footnote2,
                                                fontSize = listSecondaryFontSize,
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
                                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
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
                                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                    ) {
                                        Text("预览", fontSize = 12.sp)
                                    }
                                }
                                }

                                // inset 分割线：起点与文件名文本对齐（16 行内 padding + 38 图标 + 12 间距）
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 66.dp)
                                        .height(0.7.dp)
                                        .background(MiuixTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
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
        WindowDialog(
            show = true,
            title = "文件列表设置",
            onDismissRequest = { showFileSettingsDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "列表字体大小",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${appSettings.fileListFontSize.roundToInt()} sp",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "小",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Slider(
                        value = appSettings.fileListFontSize,
                        onValueChange = { appSettings.updateFileListFontSize(it) },
                        valueRange = 12f..20f,
                        steps = 7,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "大",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "显示隐藏文件",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "关闭后将隐藏以 \".\" 开头的文件",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                    Switch(
                        checked = appSettings.showHiddenFiles,
                        onCheckedChange = { appSettings.updateShowHiddenFiles(it) }
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "排序方式",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortModePillButton(
                            text = "名称↑",
                            selected = appSettings.fileSortMode == AppSettings.FILE_SORT_NAME_ASC,
                            onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_NAME_ASC) },
                            modifier = Modifier.weight(1f)
                        )
                        SortModePillButton(
                            text = "名称↓",
                            selected = appSettings.fileSortMode == AppSettings.FILE_SORT_NAME_DESC,
                            onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_NAME_DESC) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortModePillButton(
                            text = "时间↑",
                            selected = appSettings.fileSortMode == AppSettings.FILE_SORT_TIME_ASC,
                            onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_TIME_ASC) },
                            modifier = Modifier.weight(1f)
                        )
                        SortModePillButton(
                            text = "时间↓",
                            selected = appSettings.fileSortMode == AppSettings.FILE_SORT_TIME_DESC,
                            onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_TIME_DESC) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary
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
                            text = if (customTestText.isNotEmpty()) customTestText else "shso 任务调度引擎",
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
