// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.INTERNAL_STORAGE_LABEL
import com.mixradio.droid.data.INTERNAL_STORAGE_PATH
import com.mixradio.droid.data.RootFileManager
import com.mixradio.droid.data.displayPath
import com.mixradio.droid.ui.components.BookmarksDialog
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    initialDirectory: String = INTERNAL_STORAGE_PATH,
    titleText: String = "选择执行文件",
    subtitleText: String = "支持选择 .sh 脚本与 .so 二进制程序",
    emptyHint: String = "当前目录为空",
    /** 文件条目过滤（在列表生成阶段生效）；目录始终显示。为 null 时不过滤。 */
    fileFilter: ((FileItem) -> Boolean)? = null,
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
    var showBookmarks by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileExt by remember { mutableStateOf("") }

    fun loadDirectory(path: String) {
        isLoading = true
        selectedFile = null
        scope.launch {
            try {
                // 先探测目录是否真实存在（不可用 isEmpty 判断——合法空目录也返回空列表）
                val exists = RootFileManager.pathExists(path)
                fileList = if (exists) {
                    RootFileManager.listFiles(path)
                } else {
                    emptyList()
                }
                currentDir = if (exists) {
                    // 加载成功：开启记忆时记录为「上次浏览目录」
                    if (appSettings.rememberDirectory) {
                        RootFileManager.rememberedDirectory = path
                    }
                    path
                } else {
                    // 记忆目录已失效：回退初始目录
                    val fallback = if (appSettings.rememberDirectory) {
                        RootFileManager.rememberedDirectory?.takeIf { it != path && RootFileManager.pathExists(it) }
                            ?: INTERNAL_STORAGE_PATH
                    } else {
                        INTERNAL_STORAGE_PATH
                    }
                    if (appSettings.rememberDirectory) {
                        RootFileManager.rememberedDirectory = fallback
                    }
                    fallback
                }
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

    // 与「文件」页共用同一过滤/排序逻辑与同一份偏好；
    // 额外的 fileFilter（如文本对比的「同后缀 + 排除自身」）在列表生成阶段直接剔除，
    // 不依赖后续回调，列表与计数天然一致。
    val displayFileList = remember(fileList, appSettings.showHiddenFiles, appSettings.fileSortMode, fileFilter) {
        val base = applyFileViewSettings(fileList, appSettings.showHiddenFiles, appSettings.fileSortMode)
        if (fileFilter == null) base else base.filter { it.isDirectory || fileFilter.invoke(it) }
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
                        text = titleText,
                        style = AuroraTextStyles.title3,
                        color = AuroraTokens.Text
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitleText,
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
                                label = INTERNAL_STORAGE_LABEL,
                                selected = currentDir == INTERNAL_STORAGE_PATH,
                                onClick = { loadDirectory(INTERNAL_STORAGE_PATH) },
                                modifier = Modifier.weight(1f)
                            )
                            FileShortcutButton(
                                label = "shso",
                                selected = currentDir == RootFileManager.DEFAULT_SHSO_DIR,
                                onClick = { loadDirectory(RootFileManager.DEFAULT_SHSO_DIR) },
                                modifier = Modifier.weight(1f)
                            )

                            // 书签：仅图标（星形），点击弹出书签管理弹窗；有书签时高亮为强调色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(AuroraTokens.SurfaceHover)
                                    .clickable { showBookmarks = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = "书签",
                                    tint = if (appSettings.bookmarks.isNotEmpty()) {
                                        AuroraTokens.Accent
                                    } else {
                                        AuroraTokens.Text
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }

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
                                text = displayPath(currentDir),
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
                                        text = emptyHint,
                                        style = AuroraTextStyles.footnote1,
                                        color = AuroraTokens.TextSecondary
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(displayFileList, key = { index, item -> "${item.path}_$index" }) { _, item ->
                                        val isSelected = selectedFile?.path == item.path
                                        // 注入了 fileFilter 时，可选项以过滤器为准（如对比只允许同后缀文件）
                                        val isSupported = fileFilter?.invoke(item) ?: item.isSupportedExecutable

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
            onDismissRequest = { showListSettings = false },
            onNewFileRequest = {
                val sdf = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US)
                newFileName = sdf.format(Date())
                newFileExt = "txt"
                showListSettings = false
                showNewFileDialog = true
            }
        )
    }

    if (showBookmarks) {
        BookmarksDialog(
            appSettings = appSettings,
            currentDirectory = currentDir,
            onDismissRequest = { showBookmarks = false },
            onNavigate = { path ->
                showBookmarks = false
                loadDirectory(path)
            }
        )
    }

    if (showNewFileDialog) {
        AuroraWindowDialog(
            show = true,
            title = "新建文件",
            onDismissRequest = { showNewFileDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        colors = auroraTextFieldColors(),
                        modifier = Modifier
                            .weight(2f)
                            .clip(RoundedCornerShape(0.dp))
                    )
                    TextField(
                        value = newFileExt,
                        onValueChange = { newFileExt = it },
                        label = { Text("扩展名") },
                        singleLine = true,
                        colors = auroraTextFieldColors(),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(0.dp))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showNewFileDialog = false },
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
                            val name = newFileName.trim()
                            val ext = newFileExt.trim()
                            if (name.isEmpty()) {
                                return@Button
                            }
                            val finalName = if (ext.isNotEmpty()) "$name.$ext" else name
                            showNewFileDialog = false
                            scope.launch {
                                RootFileManager.createEmptyFile(currentDir, finalName)
                                loadDirectory(currentDir)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("创建")
                    }
                }
            }
        }
    }
}
