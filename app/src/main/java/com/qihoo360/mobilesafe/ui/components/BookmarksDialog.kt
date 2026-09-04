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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.auroraFilledButton
import com.qihoo360.mobilesafe.ui.theme.auroraTextFieldColors

/**
 * 书签管理弹窗：显示全部书签（点击跳转 / ✕ 删除），底部输入框添加路径。
 *
 * 布局规格（用户定制，三段式）：
 * - 宽度固定为 APP 宽度的 98%，水平居中，左右边距各 1%
 * - 高度上限 = APP 高度的 60%，垂直居中，上下边距各 20%
 * - 三段式结构：
 *   ① 固定标题栏（"书签" + 提示文案，不随内容滚动）
 *   ② 可滚动的书签列表（仅此区滚动，书签多时由 verticalScroll 承载）
 *   ③ 固定底栏（路径输入框 + 收藏当前目录/添加书签按钮，不随内容滚动）
 */
@Composable
fun BookmarksDialog(
    appSettings: AppSettings,
    currentDirectory: String,
    onDismissRequest: () -> Unit,
    onNavigate: (String) -> Unit
) {
    var addInput by remember { mutableStateOf(currentDirectory) }

    // 屏幕尺寸用于高度上限（APP 高度 60%）
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val maxContentHeight = (screenHeightDp * 0.6f).dp

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 宽度 98%（水平居中 → 左右各留 1%）；高度上限为 APP 高度 60%
        Surface(
            color = AuroraTokens.DialogBg,
            shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, AuroraTokens.Stroke),
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .heightIn(max = maxContentHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .padding(horizontal = 16.dp)
            ) {
                // ── ① 固定标题栏 ──
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "书签",
                    style = AuroraTextStyles.title3,
                    color = AuroraTokens.Text
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "点击书签跳转目录，点 ✕ 删除；书签永久保存",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // ── ② 可滚动书签列表（weight(1f) 只占剩余空间，超出滚动）──
                if (appSettings.bookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(AuroraTokens.SurfaceHover)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "暂无书签，可在下方输入路径添加",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = AuroraTokens.TextSecondary
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        appSettings.bookmarks.forEach { path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AuroraTokens.SurfaceHover)
                                    .clickable { onNavigate(path) }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = path,
                                    style = AuroraTextStyles.footnote1,
                                    fontFamily = FontFamily.Monospace,
                                    color = AuroraTokens.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "✕",
                                    fontSize = 14.sp,
                                    color = AuroraTokens.TextSecondary,
                                    modifier = Modifier
                                        .clickable { appSettings.removeBookmark(path) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }

                // ── ③ 固定底栏 ──
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = addInput,
                    onValueChange = { addInput = it },
                    label = { Text("输入要收藏的目录路径") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "收藏当前目录",
                        fontSize = 13.sp,
                        color = AuroraTokens.TextSecondary,
                        modifier = Modifier
                            .clickable {
                                val path = addInput.trim()
                                if (path.isNotEmpty()) {
                                    appSettings.addBookmark(path)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val path = addInput.trim()
                            if (path.isNotEmpty()) {
                                appSettings.addBookmark(path)
                            }
                        },
                        modifier = Modifier.auroraFilledButton(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("添加书签")
                    }
                }
            }
        }
    }
}