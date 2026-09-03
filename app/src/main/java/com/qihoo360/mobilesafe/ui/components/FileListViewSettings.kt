// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.FileItem
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraThinSlider
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.AuroraWindowDialog
import com.qihoo360.mobilesafe.ui.theme.auroraSwitchColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 文件列表视图通用设置（「文件」页与主页选择弹窗共用同一实现与同一 AppSettings，
 * 因此字号 / 隐藏文件 / 排序任意一处修改都会同步到另一处）。
 */

/**
 * 对刚加载的文件列表应用视图偏好：按需过滤隐藏文件，目录恒在最前，按名称/时间升/降序。
 */
internal fun applyFileViewSettings(
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

/**
 * 排序方式选择小胶囊按钮（文件列表设置弹窗内）。
 */
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
            containerColor = if (selected) AuroraTokens.Accent else AuroraTokens.SurfaceHover,
            contentColor = if (selected) AuroraTokens.OnAccent else AuroraTokens.Text
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
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
 * 顶栏快捷入口（data / storage / shso / 任意自定义项）：等宽矩形色块 + 居中小字。
 */
@Composable
internal fun FileShortcutButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(0.dp))
            .background(
                if (selected) {
                    AuroraTokens.Accent
                } else {
                    AuroraTokens.SurfaceHover
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
                AuroraTokens.OnAccent
            } else {
                AuroraTokens.Text
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 「文件列表设置」弹窗：列表字体大小滑杆 / 显示隐藏文件开关 / 四种排序方式。
 * 绑定传入的 AppSettings，任何入口的修改即时写入同一份偏好。
 */
@Composable
internal fun FileListSettingsDialog(
    appSettings: AppSettings,
    onDismissRequest: () -> Unit
) {
    AuroraWindowDialog(
        show = true,
        title = null,
        onDismissRequest = onDismissRequest
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
                    style = AuroraTextStyles.body1,
                    color = AuroraTokens.Text,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${appSettings.fileListFontSize.roundToInt()} sp",
                    style = AuroraTextStyles.footnote1,
                    color = AuroraTokens.Accent
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "小",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )
                AuroraThinSlider(
                    value = appSettings.fileListFontSize,
                    onValueChange = { appSettings.updateFileListFontSize(it) },
                    valueRange = 5f..30f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "大",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "显示隐藏文件",
                        style = AuroraTextStyles.body1,
                        color = AuroraTokens.Text
                    )
                    Text(
                        text = "关闭后将隐藏以 \".\" 开头的文件",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                }
                Switch(
                    checked = appSettings.showHiddenFiles,
                    onCheckedChange = { appSettings.updateShowHiddenFiles(it) },
                    colors = auroraSwitchColors()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "记忆操作路径",
                        style = AuroraTextStyles.body1,
                        color = AuroraTokens.Text
                    )
                    Text(
                        text = "打开后保留上次浏览的目录，关闭则每次回到初始目录",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                }
                Switch(
                    checked = appSettings.rememberDirectory,
                    onCheckedChange = { appSettings.updateRememberDirectory(it) },
                    colors = auroraSwitchColors()
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "排序方式",
                    style = AuroraTextStyles.body1,
                    color = AuroraTokens.Text
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortModePillButton(
                        text = "名称↓",
                        selected = appSettings.fileSortMode == AppSettings.FILE_SORT_NAME_DESC,
                        onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_NAME_DESC) },
                        modifier = Modifier.weight(1f)
                    )
                    SortModePillButton(
                        text = "名称↑",
                        selected = appSettings.fileSortMode == AppSettings.FILE_SORT_NAME_ASC,
                        onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_NAME_ASC) },
                        modifier = Modifier.weight(1f)
                    )
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
