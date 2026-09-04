// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * 书签管理弹窗（紧凑视图）。
 *
 * 布局规格（用户定制）：
 * - 宽度固定为 APP 宽度的 98%，水平居中；高度上限 = APP 高度的 60%，垂直居中
 * - 顶部标题贴顶边，无底色容器
 * - 中间书签列表为纯文本紧凑视图（参考「文件」列表样式：无底色矩形、0 行间隔、
 *   行内 padding 极小），超出时仅此区滚动
 * - 底部仅「添加书签」按钮贴底边（功能 = 收藏当前目录，重复点击由 AppSettings 去重）
 * - 已删除「输入要收藏的目录路径」输入框与「收藏当前目录」控件
 */
@Composable
fun BookmarksDialog(
    appSettings: AppSettings,
    currentDirectory: String,
    onDismissRequest: () -> Unit,
    onNavigate: (String) -> Unit
) {
    // 屏幕尺寸用于高度上限（APP 高度 60%）
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val maxContentHeight = (screenHeightDp * 0.6f).dp

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                    .padding(horizontal = 16.dp)
            ) {
                // ── ① 标题栏（贴顶边）──
                Text(
                    text = "书签",
                    style = AuroraTextStyles.title3,
                    color = AuroraTokens.Text,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = "点击书签跳转目录，点 ✕ 删除；书签永久保存",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )

                // ── ② 书签列表（纯文本紧凑视图：无底色、0 行间隔、贴边）──
                if (appSettings.bookmarks.isEmpty()) {
                    Text(
                        text = "暂无书签",
                        style = AuroraTextStyles.footnote1,
                        color = AuroraTokens.TextSecondary
                    )
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
                                    .clickable { onNavigate(path) }
                                    .padding(vertical = 4.dp),
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
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // ── ③ 底栏（仅「添加书签」按钮，贴底边；功能 = 收藏当前目录）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { appSettings.addBookmark(currentDirectory) },
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
