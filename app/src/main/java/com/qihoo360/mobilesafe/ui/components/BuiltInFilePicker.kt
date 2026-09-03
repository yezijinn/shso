// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihoo360.mobilesafe.data.FileItem
import com.qihoo360.mobilesafe.data.RootFileManager
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.AuroraWindowDialog
import kotlinx.coroutines.launch
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
        RootFileManager.ensureShsoDir()
        loadDirectory(initialDirectory)
    }

    AuroraWindowDialog(
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
                    .clip(RoundedCornerShape(0.dp))
                    .background(AuroraTokens.SurfaceHover.copy(0.6f))
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上一级",
                            tint = if (currentDir != "/") AuroraTokens.Accent else AuroraTokens.TextDisabled
                        )
                    }

                    Text(
                        text = currentDir,
                        style = AuroraTextStyles.footnote1,
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
                            containerColor = if (currentDir == "/") AuroraTokens.Accent else AuroraTokens.Surface,
                            contentColor = if (currentDir == "/") AuroraTokens.OnAccent else AuroraTokens.Text
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("根目录", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { loadDirectory("/storage/emulated/0") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentDir == "/storage/emulated/0") AuroraTokens.Accent else AuroraTokens.Surface,
                            contentColor = if (currentDir == "/storage/emulated/0") AuroraTokens.OnAccent else AuroraTokens.Text
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1.1f)
                    ) {
                        Text("内部存储", fontSize = 11.sp)
                    }

                    Button(
                        onClick = { loadDirectory(RootFileManager.DEFAULT_SHSO_DIR) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentDir == RootFileManager.DEFAULT_SHSO_DIR) AuroraTokens.Accent else AuroraTokens.Surface,
                            contentColor = if (currentDir == RootFileManager.DEFAULT_SHSO_DIR) AuroraTokens.OnAccent else AuroraTokens.Text
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("shso", fontSize = 11.sp)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(AuroraTokens.SurfaceHover.copy(0.3f))
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
                            style = AuroraTextStyles.footnote1,
                            color = AuroraTokens.TextSecondary
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
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(
                                        if (isSelected) AuroraTokens.Accent.copy(0.18f)
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
                                        .clip(RoundedCornerShape(0.dp))
                                        .background(
                                            when {
                                                item.isDirectory -> AuroraTokens.Accent.copy(0.15f)
                                                item.isExecutableScript -> AuroraTokens.Accent.copy(0.2f)
                                                item.isExecutableBinary -> AuroraTokens.GlowBlue.copy(0.2f)
                                                else -> AuroraTokens.SurfaceHover
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
                                            item.isDirectory -> AuroraTokens.Accent
                                            item.isExecutableScript -> AuroraTokens.Accent
                                            item.isExecutableBinary -> AuroraTokens.GlowBlue
                                            else -> AuroraTokens.TextSecondary
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = AuroraTextStyles.body2,
                                        fontWeight = FontWeight.Normal,
                                        color = if (item.isDirectory || isSupported) AuroraTokens.Text else AuroraTokens.TextSecondary.copy(0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (item.isDirectory) "文件夹" else item.formattedSize,
                                        style = AuroraTextStyles.footnote2,
                                        color = AuroraTokens.TextSecondary
                                    )
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
                        containerColor = AuroraTokens.SurfaceHover,
                        contentColor = AuroraTokens.Text
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
                        containerColor = AuroraTokens.Accent,
                        contentColor = AuroraTokens.OnAccent
                    )
                ) {
                    Text("选定该文件")
                }
            }
        }
    }
}
