// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.FileItem
import com.qihoo360.mobilesafe.data.RootFileManager
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主页「从文件管理器选择」弹窗：整体模仿「文件」页——顶栏 data/storage/shso 快捷入口 +
 * 设置齿轮、路径行、38dp 图标 + 名称/权限副行的列表行、内缩分隔线。
 *
 * 窗口接近全屏（左右留 5px、上下留 10px），便于浏览文件；底部「选定该文件 / 取消」
 * 为裸文字操作（无按钮底）。
 *
 * 字号 / 隐藏文件 / 排序与「文件」页共享同一份 AppSettings（applyFileViewSettings 与
 * FileListSettingsDialog 同实现），任意一处修改即时双向同步。
 */
@Composable
fun BuiltInFilePicker(
    appSettings: AppSettings,
    show: Boolean,
    initialDirectory: String = "/storage/emulated/0",
    onDismissRequest: () -> Unit,
    onFileSelected: (String) -> Unit
) {
    if (!show) return

    val scope = rememberCoroutineScope()
    var currentDir by remember { mutableStateOf(initialDirectory) }
    var fileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<FileItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showListSettings by remember { mutableStateOf(false) }

    fun loadDirectory(path: String) {
        isLoading = true
        selectedFile = null
        scope.launch {
            try {
                fileList = RootFileManager.listFiles(path)
                currentDir = path
            } catch (_: Exception) {
                fileList = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // 建目录与列目录无关，改为后台并行，不再串行阻塞列表首屏加载
        launch { RootFileManager.ensureShsoDir() }
        loadDirectory(initialDirectory)
    }

    // 与「文件」页共用同一过滤/排序逻辑与同一份偏好
    val displayFileList = remember(fileList, appSettings.showHiddenFiles, appSettings.fileSortMode) {
        applyFileViewSettings(fileList, appSettings.showHiddenFiles, appSettings.fileSortMode)
    }
    val listFontSize = appSettings.fileListFontSize.sp
    val listSecondaryFontSize = (appSettings.fileListFontSize - 5f).coerceAtLeast(8f).sp

    val canConfirm = selectedFile != null

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 按设备像素密度换算：左右 5px、上下 10px 的窗口外边距
        val density = LocalDensity.current
        val sideInset = with(density) { 5f.toDp() }
        val topBottomInset = with(density) { 10f.toDp() }
        val configuration = LocalConfiguration.current

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(configuration.screenHeightDp.dp)
                .padding(horizontal = sideInset, vertical = topBottomInset)
        ) {
            // 与 AuroraWindowDialog 相同的矩形外观：DialogBg 底 + 1dp 描边 + 零圆角
            Surface(
                color = AuroraTokens.DialogBg,
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, AuroraTokens.Stroke),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "选择执行文件",
                        style = AuroraTextStyles.title3,
                        color = AuroraTokens.Text
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "支持选择 .sh 脚本与 .so 二进制程序",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 主体区占满剩余高度：顶栏 / 路径 / 列表(weight=1) / 底部操作
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ① 顶栏：仿「文件」页（返回 + data/storage/shso + 设置齿轮，等宽矩形色块）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(AuroraTokens.SurfaceHover)
                                    .clickable(enabled = currentDir != "/") {
                                        val parent = File(currentDir).parent ?: "/"
                                        loadDirectory(parent)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回上一级",
                                    tint = if (currentDir != "/") AuroraTokens.Text else AuroraTokens.TextDisabled,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            FileShortcutButton(
                                label = "data",
                                selected = currentDir == "/",
                                onClick = { loadDirectory("/") },
                                modifier = Modifier.weight(1f)
                            )
                            FileShortcutButton(
                                label = "storage",
                                selected = currentDir == "/storage/emulated/0",
                                onClick = { loadDirectory("/storage/emulated/0") },
                                modifier = Modifier.weight(1f)
                            )
                            FileShortcutButton(
                                label = "shso",
                                selected = currentDir == RootFileManager.DEFAULT_SHSO_DIR,
                                onClick = { loadDirectory(RootFileManager.DEFAULT_SHSO_DIR) },
                                modifier = Modifier.weight(1f)
                            )

                            // 设置齿轮：打开与「文件」页完全相同的列表设置弹窗，双向同步
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(AuroraTokens.SurfaceHover)
                                    .clickable { showListSettings = true },
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

                        // ② 路径行 + 全宽分隔线（对齐「文件」页 路径行 → 分隔线 → 列表 节奏）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 32.dp)
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = currentDir,
                                style = AuroraTextStyles.footnote1,
                                fontFamily = FontFamily.Monospace,
                                color = AuroraTokens.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.7.dp)
                                .background(AuroraTokens.SurfaceHover.copy(alpha = 0.6f))
                        )

                        // ③ 列表区：占满剩余高度（weight=1），行样式同「文件」页
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            if (displayFileList.isEmpty() && !isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "当前目录为空",
                                        style = AuroraTextStyles.footnote1,
                                        color = AuroraTokens.TextSecondary
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(displayFileList, key = { index, item -> "${item.path}_$index" }) { _, item ->
                                        val isSelected = selectedFile?.path == item.path
                                        val isSupported = item.isSupportedExecutable

                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (isSelected) AuroraTokens.Accent.copy(0.12f)
                                                        else Color.Transparent
                                                    )
                                                    .clickable {
                                                        if (item.isDirectory) {
                                                            loadDirectory(item.path)
                                                        } else if (isSupported) {
                                                            selectedFile = if (isSelected) null else item
                                                        }
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // 类型图标：无底色方框、左右零间隙，直接裸文字
                                                Text(
                                                    text = when {
                                                        item.isDirectory -> "📁"
                                                        item.isExecutableScript -> "SH"
                                                        item.isExecutableBinary -> "SO"
                                                        else -> "📄"
                                                    },
                                                    fontSize = if (item.isDirectory || !isSupported) 16.sp else 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when {
                                                        item.isDirectory -> AuroraTokens.Accent
                                                        item.isExecutableScript -> AuroraTokens.Accent
                                                        item.isExecutableBinary -> AuroraTokens.GlowBlue
                                                        else -> AuroraTokens.TextSecondary
                                                    }
                                                )

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.name,
                                                        style = AuroraTextStyles.body1,
                                                        fontSize = listFontSize,
                                                        fontWeight = FontWeight.Normal,
                                                        color = if (item.isDirectory || isSupported) AuroraTokens.Text
                                                        else AuroraTokens.TextSecondary.copy(0.6f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
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

                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = "已选中",
                                                        tint = AuroraTokens.Accent,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }

                                            // inset 分隔线（图标无底色方框，线从行内容起点起）
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 12.dp)
                                                    .height(0.7.dp)
                                                    .background(AuroraTokens.SurfaceHover.copy(alpha = 0.6f))
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ④ 底部操作：裸文字（无按钮底），右对齐
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "取消",
                                fontSize = 14.sp,
                                color = AuroraTokens.TextSecondary,
                                modifier = Modifier
                                    .clickable(onClick = onDismissRequest)
                                    .padding(horizontal = 6.dp, vertical = 8.dp)
                            )

                            Spacer(modifier = Modifier.width(20.dp))

                            Text(
                                text = "选定该文件",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (canConfirm) AuroraTokens.Accent else AuroraTokens.TextDisabled,
                                modifier = Modifier
                                    .clickable(enabled = canConfirm) {
                                        selectedFile?.let {
                                            onFileSelected(it.path)
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showListSettings) {
        FileListSettingsDialog(
            appSettings = appSettings,
            onDismissRequest = { showListSettings = false }
        )
    }
}
