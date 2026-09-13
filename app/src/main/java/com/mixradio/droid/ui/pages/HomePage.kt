// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.pages

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.TextUnit
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.RootFileManager
import com.mixradio.droid.data.RootService
import com.mixradio.droid.ui.components.BuiltInFilePicker
import com.mixradio.droid.ui.components.ExecuteConfirmDialog
import com.mixradio.droid.ui.theme.AuroraAccentBar
import com.mixradio.droid.ui.theme.AuroraSectionTitle
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.auroraFilledButton
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import com.mixradio.droid.ui.theme.auroraPrimaryButtonColors
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

    // 执行确认：点击「立即执行」先暂存路径，弹窗确认后再真正执行
    var pendingExecutePath by remember { mutableStateOf<String?>(null) }

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
                // 仅保留可执行的 .sh 脚本与 .so 二进制（目录与其它无关文件不在此列表显示）
                val executables = files.filter { it.isSupportedExecutable }
                // 一次性预计算小写名，避免比较器内逐次 lowercase（O(N log N) 次临时字符串分配）
                val decorated = executables.map { FileItemSortKey(it, it.name.lowercase()) }
                shsoFiles = decorated
                    .sortedWith(
                        compareByDescending<FileItemSortKey> { it.item.isDirectory }
                            .thenBy { it.key }
                    )
                    .map { it.item }
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

    fun execute(path: String, runAsRoot: Boolean? = null, riskApproved: Boolean = false) {
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
        RootService.executeFile(trimmed, runAsRoot, riskApproved)
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
        // 与文件页保持一致的底部关系：
        // `innerPadding` 只承担**系统导航条** inset；DockBar 是叠在其上的透明玻璃层，
        // 若内容继续延伸到它的高度范围，就会透过导航栏看到内容（任务卡片 / shso 目录文件列表）。
        // 因此这里再预留 DockBar 内容高度 56.dp，让内容底端恰好止于 DockBar 上沿，
        // 其下方只显示极光背景。不要再叠加 navigationBarsPadding，否则与 innerPadding 重复计算，
        // 会多出一道系统导航条高度的空白带。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = 56.dp)
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

                    ElapsedRunningTimeText()

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
                onClick = { pendingExecutePath = filePathInput },
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

            if (isScanningShso && shsoFiles.isEmpty()) {
                // 扫描中且尚无结果时给出加载态：否则扫描期与读取失败都显示「暂无文件」，
                // 与真正的空目录无法区分。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "正在读取目录…",
                        style = AuroraTextStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = AuroraTokens.TextSecondary
                    )
                }
            } else if (shsoFiles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "当前目录暂无可执行的 .sh / .so 文件",
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
                        ShsoFileRow(
                            fileItem = fileItem,
                            isSelected = isSelected,
                            listFontSize = listFontSize,
                            listSecondaryFontSize = listSecondaryFontSize,
                            onSelect = {
                                if (fileItem.isDirectory) refreshShsoFiles(fileItem.path)
                                else { filePathInput = fileItem.path; validationError = null }
                            }
                        )
                        if (index < shsoFiles.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 2.dp)
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
            initialDirectory = if (appSettings.rememberDirectory) {
                RootFileManager.rememberedDirectory ?: "/storage/emulated/0"
            } else {
                "/storage/emulated/0"
            },
            onDismissRequest = { showFilePicker = false },
            onFileSelected = { selectedPath ->
                filePathInput = selectedPath
                showFilePicker = false
                validationError = null
            }
        )
    }

    // ===== 执行确认弹窗：点击「立即执行」必须先经风险确认 =====
    // 优先复用 shso 列表中的真实 FileItem（含正确大小/时间），否则按输入路径构造
    val execItem = remember(pendingExecutePath) {
        pendingExecutePath?.let { targetPath ->
            shsoFiles.firstOrNull { it.path == targetPath }
                ?: FileItem(name = File(targetPath).name, path = targetPath, isDirectory = false)
        }
    }
    ExecuteConfirmDialog(
        show = pendingExecutePath != null,
        fileItem = execItem,
        // 实参传当前档位，与 FilePage 一致——否则默认值 STANDARD=2 会覆盖用户实际档位
        securityLevel = RootService.currentSecurityLevel(),
        onDismiss = { pendingExecutePath = null },
        onConfirm = { runAsRoot ->
            // 关键：透传确认框里用户的实际选择与「已获风险确认」，
            // 否则档位 3 的「脚本默认非 Root + 用户可勾选以 Root」是死代码。
            val targetPath = pendingExecutePath
            pendingExecutePath = null
            if (targetPath != null) execute(targetPath, runAsRoot = runAsRoot, riskApproved = true)
        }
    )
}

/**
 * 排序辅助键：持有原始 [FileItem] 与其预计算的小写名，避免比较器内逐次 lowercase 产生临时字符串。
 */
private class FileItemSortKey(val item: FileItem, val key: String)

/**
 * 任务已运行时间。独立成 Composable + 自身状态，使其每秒变化只重组本节点，
 * 不再波及主页文件列表等无关内容。
 */
@Composable
private fun ElapsedRunningTimeText() {
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    // 仅在任务运行中刷新，不加归零、不设 else 分支。
    // 本组件仅在有任务进行中时才出现在组合树内，故这里的唯一收益是「把每秒重组限制在本节点」，
    // 不再牵连主页文件列表等无关内容。
    LaunchedEffect(RootService.isTaskRunning, RootService.taskStartTime) {
        while (RootService.isTaskRunning) {
            val start = RootService.taskStartTime
            if (start > 0) {
                elapsedSeconds = (System.currentTimeMillis() - start) / 1000
            }
            delay(1000)
        }
    }
    Text(
        text = "已运行时间: ${elapsedSeconds}s",
        style = AuroraTextStyles.footnote1,
        color = AuroraTokens.Accent,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * 单个 shso 文件行。独立成 Composable 以提升跳过性：外部重组（如选中态变化、滚动）时，
 * 未变化的行可独立跳过重绘，不再牵连整列。
 */
@Composable
private fun ShsoFileRow(
    fileItem: FileItem,
    isSelected: Boolean,
    listFontSize: TextUnit,
    listSecondaryFontSize: TextUnit,
    onSelect: () -> Unit
) {
    val isExecutable = fileItem.isExecutableScript || fileItem.isExecutableBinary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(
                if (isSelected) AuroraTokens.Accent.copy(0.08f)
                else Color.Transparent
            )
            .padding(horizontal = 2.dp, vertical = 1.dp),
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
            // 无底部容器：仅文本，加粗橘红（选中态用 Accent 青区分）
            Text(
                text = if (isSelected) "已选择" else "选择",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) AuroraTokens.Accent else AuroraTokens.AccentOrange,
                modifier = Modifier.clickable(onClick = onSelect)
            )
        }
    }
}
