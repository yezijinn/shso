// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package Kernel.Extend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kernel.Extend.data.FileItem
import Kernel.Extend.data.RootFileManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

@Composable
fun BuiltInFilePicker(
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
        RootFileManager.ensureKernelEXDir()
        loadDirectory(initialDirectory)
    }

    WindowDialog(
        show = show,
        title = "选择执行文件",
        summary = "支持选择 .sh 脚本与 .so 二进制程序",
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest.copy(0.6f))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val parent = File(currentDir).parent ?: "/"
                            loadDirectory(parent)
                        },
                        enabled = currentDir != "/"
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "上一级",
                            tint = if (currentDir != "/") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        )
                    }

                    Text(
                        text = currentDir,
                        style = MiuixTheme.textStyles.footnote1,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { loadDirectory("/") },
                        colors = ButtonDefaults.buttonColors(
                            color = if (currentDir == "/") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surface,
                            contentColor = if (currentDir == "/") MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                        ),
                        insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("根目录", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { loadDirectory("/storage/emulated/0") },
                        colors = ButtonDefaults.buttonColors(
                            color = if (currentDir == "/storage/emulated/0") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surface,
                            contentColor = if (currentDir == "/storage/emulated/0") MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                        ),
                        insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1.1f)
                    ) {
                        Text("内部存储", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { loadDirectory(RootFileManager.DEFAULT_KERNEL_EX_DIR) },
                        colors = ButtonDefaults.buttonColors(
                            color = if (currentDir == RootFileManager.DEFAULT_KERNEL_EX_DIR) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surface,
                            contentColor = if (currentDir == RootFileManager.DEFAULT_KERNEL_EX_DIR) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                        ),
                        insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("KernelEX", fontSize = 11.sp)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest.copy(0.3f))
            ) {
                if (fileList.isEmpty() && !isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "当前目录为空",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(fileList, key = { index, item -> "${item.path}_$index" }) { _, item ->
                            val isSelected = selectedFile?.path == item.path
                            val isSupported = item.isSupportedExecutable

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MiuixTheme.colorScheme.primary.copy(0.18f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        if (item.isDirectory) {
                                            loadDirectory(item.path)
                                        } else if (isSupported) {
                                            selectedFile = if (isSelected) null else item
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                item.isDirectory -> MiuixTheme.colorScheme.primary.copy(0.15f)
                                                item.isExecutableScript -> Color(0xFF4CAF50).copy(0.2f)
                                                item.isExecutableBinary -> Color(0xFF2196F3).copy(0.2f)
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
                                            else -> "📄"
                                        },
                                        fontSize = if (item.isDirectory || !isSupported) 14.sp else 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            item.isExecutableScript -> Color(0xFF2E7D32)
                                            item.isExecutableBinary -> Color(0xFF1565C0)
                                            else -> MiuixTheme.colorScheme.onSurfaceSecondary
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Normal,
                                        color = if (item.isDirectory || isSupported) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceSecondary.copy(0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (item.isDirectory) "文件夹" else item.formattedSize,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = MiuixIcons.Basic.Check,
                                        contentDescription = "已选中",
                                        tint = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MiuixTheme.colorScheme.onSurface
                    )
                ) {
                    Text("取消")
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    enabled = selectedFile != null,
                    onClick = {
                        selectedFile?.let {
                            onFileSelected(it.path)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("选定该文件")
                }
            }
        }
    }
}
