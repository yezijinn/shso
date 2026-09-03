// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.RootFileManager
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.AuroraWindowDialog
import com.qihoo360.mobilesafe.ui.theme.auroraFilledButton
import com.qihoo360.mobilesafe.ui.theme.auroraTextFieldColors

/**
 * 书签管理弹窗：显示全部书签（点击跳转 / 长按删除 / 右侧删除按钮），
 * 底部提供输入框手动添加当前路径或其他绝对路径。
 *
 * 书签为永久存储（SharedPreferences），跨进程存活。
 */
@Composable
fun BookmarksDialog(
    appSettings: AppSettings,
    currentDirectory: String,
    onDismissRequest: () -> Unit,
    onNavigate: (String) -> Unit
) {
    var addInput by remember { mutableStateOf(currentDirectory) }

    AuroraWindowDialog(
        show = true,
        title = "书签",
        summary = "点击书签跳转目录，长按或点 ✕ 删除；书签永久保存",
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (appSettings.bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "暂无书签，可在下方输入路径添加",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    itemsIndexed(appSettings.bookmarks, key = { _, path -> path }) { _, path ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(0.dp))
                                .background(AuroraTokens.SurfaceHover)
                                .clickable { onNavigate(path) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = AuroraTokens.Accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
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
                modifier = Modifier.fillMaxWidth(),
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
