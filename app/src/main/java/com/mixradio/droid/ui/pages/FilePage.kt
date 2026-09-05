// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.pages

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.ApkInstaller
import com.mixradio.droid.data.ArchiveExtractor
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.INTERNAL_STORAGE_LABEL
import com.mixradio.droid.data.INTERNAL_STORAGE_PATH
import com.mixradio.droid.data.RootFileManager
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.displayPath
import com.mixradio.droid.ui.components.BookmarksDialog
import com.mixradio.droid.ui.components.FileListSettingsDialog
import com.mixradio.droid.ui.components.FileShortcutButton
import com.mixradio.droid.ui.components.ImageViewerDialog
import com.mixradio.droid.ui.components.TextEditorDialog
import com.mixradio.droid.ui.components.applyFileViewSettings
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilePage(
    appSettings: AppSettings,
    onExecuteFileAndNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 记忆操作路径：开启时沿用进程内记住的上次目录（无效则回退初始目录），关闭时恒为初始目录
    val initialDirectory = if (appSettings.rememberDirectory) {
        RootFileManager.rememberedDirectory ?: INTERNAL_STORAGE_PATH
    } else {
        INTERNAL_STORAGE_PATH
    }
    var currentDirectory by remember { mutableStateOf(initialDirectory) }
    var fileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var directoryLoadFailed by remember { mutableStateOf(false) }

    var selectedItem by remember { mutableStateOf<FileItem?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var showImageViewerDialog by remember { mutableStateOf(false) }
    var viewerImageList by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerImageIndex by remember { mutableIntStateOf(0) }
    var showTextEditorDialog by remember { mutableStateOf(false) }
    var viewerTargetItem by remember { mutableStateOf<FileItem?>(null) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var showJumpPathDialog by remember { mutableStateOf(false) }
    var jumpPathInput by remember { mutableStateOf("") }

    var showFontPreviewDialog by remember { mutableStateOf(false) }
    var previewFontItem by remember { mutableStateOf<FileItem?>(null) }

    var showFileSettingsDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }

    // 自动解压：密码输入弹窗状态
    var showExtractPasswordDialog by remember { mutableStateOf(false) }
    var extractPasswordInput by remember { mutableStateOf("") }
    var extractTargetItem by remember { mutableStateOf<FileItem?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var isCopying by remember { mutableStateOf(false) }

    // 多选模式状态：进入后单击文件=切换选中（仅文件，文件夹不参与）；长按文件弹批量菜单
    var multiSelectMode by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateListOf<String>() }
    var showModeDialog by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showBatchRenameDialog by remember { mutableStateOf(false) }
    var batchRenameInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileExt by remember { mutableStateOf("") }

    fun refresh(showToast: Boolean = false) {
        isLoading = true
        directoryLoadFailed = false
        scope.launch {
            try {
                // 先探测目录是否真实存在（不可用 `fileList.isEmpty()` 判断——合法空目录也返回空列表）
                val exists = RootFileManager.pathExists(currentDirectory)
                fileList = if (exists) {
                    RootFileManager.listFiles(currentDirectory)
                } else {
                    emptyList()
                }
                if (!exists) {
                    // 记忆的目录已失效（被删除/不可达）：随后回退初始目录
                    directoryLoadFailed = true
                } else {
                    // 目录加载成功（含合法空目录）：开启记忆时记录为「上次浏览目录」
                    if (appSettings.rememberDirectory) {
                        RootFileManager.rememberedDirectory = currentDirectory
                    }
                }
            } catch (_: Exception) {
                fileList = emptyList()
            } finally {
                isLoading = false
                // 用户手动点击「⟳」时给出明确反馈，避免「点了没反应」的错觉
                if (showToast) feedbackMessage = "已刷新"
            }
        }
    }

    // 记忆目录失效时自动回退初始目录
    LaunchedEffect(directoryLoadFailed) {
        if (directoryLoadFailed) {
            directoryLoadFailed = false
            currentDirectory = INTERNAL_STORAGE_PATH
            if (appSettings.rememberDirectory) {
                RootFileManager.rememberedDirectory = INTERNAL_STORAGE_PATH
            }
        }
    }

    LaunchedEffect(currentDirectory) {
        // 建目录与列目录无关，改为后台并行，不再串行阻塞列表首屏加载
        launch { RootFileManager.ensureShsoDir() }
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
                    label = INTERNAL_STORAGE_LABEL,
                    selected = currentDirectory == INTERNAL_STORAGE_PATH,
                    onClick = { currentDirectory = INTERNAL_STORAGE_PATH },
                    modifier = Modifier.weight(1f)
                )

                FileShortcutButton(
                    label = "shso",
                    selected = currentDirectory == RootFileManager.DEFAULT_SHSO_DIR,
                    onClick = { currentDirectory = RootFileManager.DEFAULT_SHSO_DIR },
                    modifier = Modifier.weight(1f)
                )

                // 书签：仅图标（星形），点击弹出书签管理弹窗；有书签时高亮为强调色
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .clickable { showBookmarksDialog = true },
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
                    text = displayPath(currentDirectory),
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

            // 文件列表区（占满）+ 悬浮三按钮：外层 Box 包裹，列表 fillMaxSize 占满。
            // 列表底端必须恰好止于 DockBar 上沿：DockBar 是透明玻璃叠层，若列表继续延伸
            // 到其下方，会透过导航栏看到文件行。Scaffold 的 innerPadding 已承担系统导航条
            // inset，因此列表 Box 只需再预留 DockBar 内容高度 56.dp（不要再加 navigationBarsPadding，
            // 否则与 innerPadding 重复计算，会多出一道系统导航条高度的空白带）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 56.dp)
            ) {
                // 列表容器：占满外层 Box
                Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading && displayFileList.isEmpty()) {
                    // 骨架占位：加载期间先铺出列表轮廓，消除首屏空白观感
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(10) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 18.dp, height = 12.dp)
                                            .clip(RoundedCornerShape(0.dp))
                                            .background(AuroraTokens.SurfaceHover)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.55f)
                                                .height(12.dp)
                                                .clip(RoundedCornerShape(0.dp))
                                                .background(AuroraTokens.SurfaceHover)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.28f)
                                                .height(9.dp)
                                                .clip(RoundedCornerShape(0.dp))
                                                .background(AuroraTokens.SurfaceHover)
                                        )
                                    }
                                }
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
                } else if (displayFileList.isEmpty()) {
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
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(displayFileList, key = { index, item -> "${item.path}_$index" }) { _, item ->
                            val isExecutable = item.isExecutableScript || item.isExecutableBinary
                            val isFontFile = !item.isDirectory && (item.name.endsWith(".ttf", ignoreCase = true) || item.name.endsWith(".otf", ignoreCase = true))

                            Column(modifier = Modifier.fillMaxWidth()) {
                                val isSelected = multiSelectMode && selectedPaths.contains(item.path)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (item.isDirectory) {
                                                    // 文件夹：非多选模式单击进入；多选模式文件夹不参与选择
                                                    if (!multiSelectMode) currentDirectory = item.path
                                                } else {
                                                    if (multiSelectMode) {
                                                        // 多选模式：单击文件 = 切换选中状态
                                                        if (selectedPaths.contains(item.path)) selectedPaths.remove(item.path)
                                                        else selectedPaths.add(item.path)
                                                    } else {
                                                        // 非多选：单击文件弹动作菜单
                                                        selectedItem = item
                                                        showActionDialog = true
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (item.isDirectory) {
                                                    // 文件夹长按：始终弹动作菜单
                                                    selectedItem = item
                                                    showActionDialog = true
                                                } else {
                                                    selectedItem = item
                                                    if (multiSelectMode) {
                                                        // 多选模式：长按弹批量操作菜单
                                                        showBatchDialog = true
                                                    } else {
                                                        // 非多选：长按弹 进入/退出多选模式
                                                        showModeDialog = true
                                                    }
                                                }
                                            }
                                        )
                                        .background(if (isSelected) AuroraTokens.Accent.copy(alpha = 0.16f) else Color.Transparent)
                                        .padding(horizontal = 16.dp, vertical = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                // 类型图标：无底色方框、左右零间隙，直接裸文字
                                Text(
                                    text = when {
                                        item.isDirectory -> "📁"
                                        item.isExecutableScript -> "SH"
                                        item.isExecutableBinary -> "SO"
                                        item.isInstallable -> "APK"
                                        isFontFile -> if (item.name.endsWith(".otf", ignoreCase = true)) "OTF" else "TTF"
                                        item.isViewableImage -> "IMG"
                                        item.isEditableText -> "TXT"
                                        else -> "📄"
                                    },
                                    fontSize = if (item.isDirectory || (!isExecutable && !isFontFile && !item.isInstallable && !item.isViewableImage && !item.isEditableText)) 16.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        item.isDirectory -> AuroraTokens.Accent
                                        item.isExecutableScript -> AuroraTokens.Accent
                                        item.isExecutableBinary -> AuroraTokens.GlowBlue
                                        item.isInstallable -> AuroraTokens.AccentViolet
                                        isFontFile -> AuroraTokens.AccentViolet
                                        item.isViewableImage -> AuroraTokens.AccentViolet
                                        item.isEditableText -> AuroraTokens.GlowBlue
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

                                // 多选模式选中标记：极光渐变 ✓（无底色方块，纯文字）
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        style = AuroraTextStyles.title3.copy(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Black,
                                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                colors = listOf(AuroraTokens.GlowCyan, AuroraTokens.GlowBlue, AuroraTokens.AccentViolet)
                                            )
                                        ),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
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

            // 文件列表底部：全局三按钮（透明背景、极光渐变图标，靠右、距右边 50dp，位于 DockBar 上方）
            // 图标样式复刻「浏览图片」查看器：40sp Black + 青→蓝→紫极光渐变
            // ⤒ 回到顶部 / ⤓ 直达底部 / ⟳ 立即刷新文件列表；整体靠右排列，置于可能出现的「执行」按钮左侧
            val navIconBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                colors = listOf(AuroraTokens.GlowCyan, AuroraTokens.GlowBlue, AuroraTokens.AccentViolet)
            )
            val navIconStyle = AuroraTextStyles.title3.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                brush = navIconBrush
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .zIndex(1f)
                    .padding(bottom = 8.dp, end = 50.dp)
                    .height(60.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val navActions: List<Pair<String, () -> Unit>> = listOf(
                    "⤒" to { scope.launch { if (displayFileList.isNotEmpty()) listState.scrollToItem(0) } },
                    "⤓" to { scope.launch { if (displayFileList.isNotEmpty()) listState.scrollToItem(displayFileList.lastIndex) } },
                    "⟳" to { refresh(showToast = true) }
                )
                navActions.forEach { (sym, action) ->
                    Text(
                        text = sym,
                        style = navIconStyle,
                        modifier = Modifier
                            .clickable(onClick = action)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            }
        }
    }

    if (showFileSettingsDialog) {
        FileListSettingsDialog(
            appSettings = appSettings,
            onDismissRequest = { showFileSettingsDialog = false },
            onNewFileRequest = {
                // 预填：文件名 = 今天日期到毫秒的纯数字；扩展名 = txt
                val sdf = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US)
                newFileName = sdf.format(Date())
                newFileExt = "txt"
                showFileSettingsDialog = false
                showNewFileDialog = true
            }
        )
    }

    if (showBookmarksDialog) {
        BookmarksDialog(
            appSettings = appSettings,
            currentDirectory = currentDirectory,
            onDismissRequest = { showBookmarksDialog = false },
            onNavigate = { path ->
                showBookmarksDialog = false
                currentDirectory = path
            }
        )
    }

    // 纯文字紧凑菜单行：无矩形底色、无圆角，仅文字 + 点击
    @Composable
    fun ActionTextRow(
        label: String,
        color: Color = AuroraTokens.Text,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 11.dp, horizontal = 8.dp)
        ) {
            Text(
                text = label,
                style = AuroraTextStyles.body1,
                color = if (enabled) color else AuroraTokens.TextDisabled
            )
        }
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
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // 纯文字紧凑行：无矩形底色、无圆角
                ActionTextRow("添加到shso", AuroraTokens.Accent) {
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
                }

                // 自动解压：仅已知压缩包显示；所有已知格式均可解压
                if (item.isArchive) {
                    ActionTextRow(
                        label = if (isExtracting) "正在解压…" else "自动解压文件",
                        color = AuroraTokens.Accent,
                        enabled = !isExtracting
                    ) {
                        showActionDialog = false
                        scope.launch {
                            isExtracting = true
                            val result = ArchiveExtractor.extract(
                                archivePath = item.path,
                                targetParent = currentDirectory
                            )
                            isExtracting = false
                            when (result) {
                                is ArchiveExtractor.ExtractResult.Success ->
                                    feedbackMessage = "已解压到: ${result.targetDir}"
                                is ArchiveExtractor.ExtractResult.NeedPassword -> {
                                    // 弹出密码输入框，用户输入后重试
                                    extractTargetItem = item
                                    extractPasswordInput = ""
                                    showExtractPasswordDialog = true
                                }
                                is ArchiveExtractor.ExtractResult.Failure ->
                                    feedbackMessage = result.message
                            }
                            refresh()
                        }
                    }
                }

                // 安装 APK/XAPK：普通用户即可安装（无 ROOT 走系统安装器），
                // 仅当授权 ROOT 时优先走静默安装；用 realExtension 兼容 .1 尾缀
                if (item.isInstallable) {
                    val rootGranted = RootService.isRootGranted == true
                    ActionTextRow(
                        label = if (isInstalling) "正在安装…" else "安装 APK/XAPK",
                        color = AuroraTokens.Accent,
                        enabled = !isInstalling
                    ) {
                        showActionDialog = false
                        scope.launch {
                            isInstalling = true
                            val result = if (rootGranted) {
                                if (item.realExtension == "apk") {
                                    ApkInstaller.installApk(item.path)
                                } else {
                                    ApkInstaller.installXapk(item.path)
                                }
                            } else {
                                if (item.realExtension == "apk") {
                                    ApkInstaller.installApkViaSystem(context, item.path)
                                } else {
                                    ApkInstaller.InstallResult.Failure("XAPK 分片安装需 ROOT 静默权限，请先授权 ROOT")
                                }
                            }
                            isInstalling = false
                            feedbackMessage = when (result) {
                                is ApkInstaller.InstallResult.Success -> result.message
                                is ApkInstaller.InstallResult.Failure -> result.message
                            }
                        }
                    }
                }

                // 浏览图片：jpg/jpeg/png/bmp/gif/webp/ico/tiff/tif
                if (item.isViewableImage) {
                    ActionTextRow("浏览图片", AuroraTokens.AccentViolet) {
                        showActionDialog = false
                        // 收集当前目录所有可浏览图片路径，定位当前项索引
                        val imageList = displayFileList
                            .filter { it.isViewableImage }
                            .map { it.path }
                        val idx = imageList.indexOf(item.path).coerceAtLeast(0)
                        viewerImageList = imageList
                        viewerImageIndex = idx
                        showImageViewerDialog = true
                    }
                }

                // 编辑文本：txt/md/json/xml/yaml/conf/properties 等
                if (item.isEditableText) {
                    ActionTextRow("编辑文本", AuroraTokens.AccentViolet) {
                        showActionDialog = false
                        viewerTargetItem = item
                        showTextEditorDialog = true
                    }
                }

                ActionTextRow("重命名", AuroraTokens.Text) {
                    showActionDialog = false
                    renameInput = item.name
                    showRenameDialog = true
                }

                // 拷贝：仅文件（文件夹不显示），复制为同级 _n 递增序号副本
                if (!item.isDirectory) {
                    ActionTextRow(if (isCopying) "正在拷贝…" else "拷贝", AuroraTokens.Text, enabled = !isCopying) {
                        showActionDialog = false
                        scope.launch {
                            isCopying = true
                            val (success, resultPath) = RootFileManager.copyFile(item.path)
                            isCopying = false
                            if (success) {
                                feedbackMessage = "已拷贝: ${File(resultPath).name}"
                                refresh()
                            } else {
                                feedbackMessage = resultPath
                            }
                        }
                    }
                }

                ActionTextRow("删除", AuroraTokens.Error) {
                    showActionDialog = false
                    showDeleteDialog = true
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

    // 解压密码输入弹窗：压缩包检测到加密时弹出
    if (showExtractPasswordDialog && extractTargetItem != null) {
        val targetItem = extractTargetItem!!
        AuroraWindowDialog(
            show = true,
            title = "输入解压密码",
            summary = "压缩包「${targetItem.name}」已加密，请输入密码后继续解压：",
            onDismissRequest = { showExtractPasswordDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = extractPasswordInput,
                    onValueChange = { extractPasswordInput = it },
                    label = { Text("密码") },
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
                        onClick = { showExtractPasswordDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = extractPasswordInput.isNotBlank(),
                        onClick = {
                            val pwd = extractPasswordInput
                            showExtractPasswordDialog = false
                            scope.launch {
                                isExtracting = true
                                val result = ArchiveExtractor.extract(
                                    archivePath = targetItem.path,
                                    targetParent = currentDirectory,
                                    password = pwd
                                )
                                isExtracting = false
                                when (result) {
                                    is ArchiveExtractor.ExtractResult.Success ->
                                        feedbackMessage = "已解压到: ${result.targetDir}"
                                    is ArchiveExtractor.ExtractResult.NeedPassword ->
                                        feedbackMessage = "该压缩包需要密码"
                                    is ArchiveExtractor.ExtractResult.Failure ->
                                        feedbackMessage = result.message
                                }
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("解压")
                    }
                }
            }
        }
    }

    // 图片浏览弹窗（伪全屏，支持上一张/下一张/旋转）
    if (showImageViewerDialog && viewerImageList.isNotEmpty()) {
        ImageViewerDialog(
            images = viewerImageList,
            initialIndex = viewerImageIndex,
            onDismiss = {
                showImageViewerDialog = false
                viewerImageList = emptyList()
                viewerImageIndex = 0
            }
        )
    }

    // 文本编辑弹窗
    if (showTextEditorDialog && viewerTargetItem != null) {
        TextEditorDialog(
            filePath = viewerTargetItem!!.path,
            onDismissRequest = {
                showTextEditorDialog = false
                viewerTargetItem = null
            }
        )
    }

    // ── 多选模式：长按文件弹出的「进入/退出多选模式」 ──
    if (showModeDialog && selectedItem != null) {
        val item = selectedItem!!
        AuroraWindowDialog(
            show = true,
            title = "多选模式",
            onDismissRequest = { showModeDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                ActionTextRow("进入多选模式", AuroraTokens.Accent) {
                    showModeDialog = false
                    multiSelectMode = true
                    if (!selectedPaths.contains(item.path)) selectedPaths.add(item.path)
                }
                ActionTextRow("退出多选模式", AuroraTokens.Text) {
                    showModeDialog = false
                    multiSelectMode = false
                    selectedPaths.clear()
                }
            }
        }
    }

    // ── 多选模式：批量操作菜单（删除 / 拷贝 / 重命名） ──
    if (showBatchDialog) {
        AuroraWindowDialog(
            show = true,
            title = "批量操作（已选 ${selectedPaths.size} 项）",
            onDismissRequest = { showBatchDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                ActionTextRow("删除", AuroraTokens.Error) {
                    showBatchDialog = false
                    scope.launch {
                        var ok = 0
                        var fail = 0
                        selectedPaths.toList().forEach { p ->
                            val (s) = RootFileManager.delete(p)
                            if (s) ok++ else fail++
                        }
                        feedbackMessage = if (fail == 0) "已删除 $ok 个文件" else "删除完成：$ok 成功 / $fail 失败"
                        selectedPaths.clear()
                        multiSelectMode = false
                        refresh()
                    }
                }

                ActionTextRow("拷贝", AuroraTokens.Text) {
                    showBatchDialog = false
                    scope.launch {
                        var ok = 0
                        var fail = 0
                        selectedPaths.toList().forEach { p ->
                            val (s) = RootFileManager.copyFile(p)
                            if (s) ok++ else fail++
                        }
                        feedbackMessage = if (fail == 0) "已拷贝 $ok 个文件" else "拷贝完成：$ok 成功 / $fail 失败"
                        selectedPaths.clear()
                        multiSelectMode = false
                        refresh()
                    }
                }

                ActionTextRow("重命名", AuroraTokens.Text) {
                    showBatchDialog = false
                    batchRenameInput = ""
                    showBatchRenameDialog = true
                }
            }
        }
    }

    // ── 多选批量重命名：统一名称 + _n，保留原扩展名 ──
    if (showBatchRenameDialog) {
        AuroraWindowDialog(
            show = true,
            title = "批量重命名",
            summary = "输入统一名称，将依次命名为 名称_0、名称_1…（保留原扩展名）",
            onDismissRequest = { showBatchRenameDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TextField(
                    value = batchRenameInput,
                    onValueChange = { batchRenameInput = it },
                    label = { Text("统一名称") },
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
                        onClick = { showBatchRenameDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = batchRenameInput.isNotBlank(),
                        onClick = {
                            val base = batchRenameInput.trim()
                            showBatchRenameDialog = false
                            scope.launch {
                                selectedPaths.toList().forEachIndexed { i, p ->
                                    val f = File(p)
                                    val ext = f.extension
                                    val suffix = if (ext.isNotEmpty()) ".$ext" else ""
                                    RootFileManager.rename(p, "${base}_$i$suffix")
                                }
                                feedbackMessage = "已批量重命名 ${selectedPaths.size} 个文件"
                                selectedPaths.clear()
                                multiSelectMode = false
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
                                feedbackMessage = "文件名不能为空"
                                return@Button
                            }
                            val finalName = if (ext.isNotEmpty()) "$name.$ext" else name
                            showNewFileDialog = false
                            scope.launch {
                                val (ok, msg) = RootFileManager.createEmptyFile(currentDirectory, finalName)
                                feedbackMessage = if (ok) "已创建: $finalName" else msg
                                if (ok) refresh()
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
