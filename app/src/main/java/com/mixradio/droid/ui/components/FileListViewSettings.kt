// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

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
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraThinSlider
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraSwitchColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 文件列表视图通用设置（「文件」页与主页选择弹窗共用同一实现与同一 AppSettings，
 * 因此字号 / 隐藏文件 / 排序任意一处修改都会同步到另一处）。
 */

/**
 * 对刚加载的文件列表应用视图偏好：按需过滤隐藏文件，目录恒在最前，按名称/时间升/降序。
 *
 * 名称排序键在排序前一次性预计算（O(N) 次 `lowercase`）。若写成
 * `compareBy { it.name.lowercase(...) }`，比较器每次比较都要新建临时字符串，
 * 总分配量是 O(N log N)。
 */
internal fun applyFileViewSettings(
    list: List<FileItem>,
    showHiddenFiles: Boolean,
    sortMode: Int
): List<FileItem> {
    val filtered = if (showHiddenFiles) list else list.filterNot { it.name.startsWith(".") }
    val directories = filtered.filter { it.isDirectory }
    val files = filtered.filterNot { it.isDirectory }

    return sortedForView(directories, sortMode) + sortedForView(files, sortMode)
}

/** 按当前排序模式返回升/降序副本；目录与文件分组后分别调用。 */
private fun sortedForView(items: List<FileItem>, sortMode: Int): List<FileItem> {
    if (items.isEmpty()) return items

    val descending = sortMode == AppSettings.FILE_SORT_NAME_DESC || sortMode == AppSettings.FILE_SORT_TIME_DESC
    return when (sortMode) {
        AppSettings.FILE_SORT_TIME_ASC, AppSettings.FILE_SORT_TIME_DESC -> {
            val byTime = compareBy<FileItem> { it.lastModified }
            items.sortedWith(if (descending) byTime.reversed() else byTime)
        }
        else -> {
            val locale = Locale.getDefault()
            val decorated = items.map { it.name.lowercase(locale) to it }
            val ordered = if (descending) {
                decorated.sortedWith(compareByDescending { it.first })
            } else {
                decorated.sortedWith(compareBy { it.first })
            }
            ordered.map { it.second }
        }
    }
}

/**
 * 排序方式选项：**纯文本**，无圆角矩形底色容器（与弹窗紧凑风格一致）。
 *
 * 仅以选中态区分：选中 = Accent 色 + SemiBold，未选中 = TextSecondary + Normal。
 */
@Composable
private fun SortModeTextOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AuroraTokens.Accent else AuroraTokens.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
 * 「文件列表设置」弹窗：字号一行（字号—拖动条—数值）/ 显示隐藏文件开关 / 四种排序方式（纯文本）/ 全选文件 + 新建文件（同一行）。
 * 绑定传入的 AppSettings，任何入口的修改即时写入同一份偏好。
 *
 * @param onSelectAllFilesRequest 全选/取消全选**文件**（不含文件夹）；为 null 表示该入口不支持多选（如文件选择器），不渲染该项。
 * @param allFilesSelected 当前是否已处于「全选文件」状态；用于把文案切换为「取消全选」。
 * @param onExtractApkRequest 打开「提取 APK」；为 null 表示该入口不提供（如文件选择器复用本弹窗时不渲染）。
 */
@Composable
internal fun FileListSettingsDialog(
    appSettings: AppSettings,
    onDismissRequest: () -> Unit,
    onNewFileRequest: () -> Unit,
    onSelectAllFilesRequest: (() -> Unit)? = null,
    allFilesSelected: Boolean = false,
    onExtractApkRequest: (() -> Unit)? = null
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
            // 字号一行：字号 — 拖动条 — 当前数值（不再单独一行标题 + 一行「小—大」标注）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "字号",
                    style = AuroraTextStyles.body1,
                    color = AuroraTokens.Text
                )
                AuroraThinSlider(
                    value = appSettings.fileListFontSize,
                    onValueChange = { appSettings.updateFileListFontSize(it) },
                    valueRange = 5f..30f,
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
                    SortModeTextOption(
                        text = "名称↓",
                        selected = appSettings.fileSortMode == AppSettings.FILE_SORT_NAME_DESC,
                        onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_NAME_DESC) },
                        modifier = Modifier.weight(1f)
                    )
                    SortModeTextOption(
                        text = "名称↑",
                        selected = appSettings.fileSortMode == AppSettings.FILE_SORT_NAME_ASC,
                        onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_NAME_ASC) },
                        modifier = Modifier.weight(1f)
                    )
                    SortModeTextOption(
                        text = "时间↑",
                        selected = appSettings.fileSortMode == AppSettings.FILE_SORT_TIME_ASC,
                        onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_TIME_ASC) },
                        modifier = Modifier.weight(1f)
                    )
                    SortModeTextOption(
                        text = "时间↓",
                        selected = appSettings.fileSortMode == AppSettings.FILE_SORT_TIME_DESC,
                        onClick = { appSettings.updateFileSortMode(AppSettings.FILE_SORT_TIME_DESC) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 「提取 APK」：从已安装应用导出安装包到 Download（仅文件页入口提供）
            if (onExtractApkRequest != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExtractApkRequest() }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = "提取APK",
                        style = AuroraTextStyles.body1,
                        color = AuroraTokens.Accent
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onSelectAllFilesRequest != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectAllFilesRequest() }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            text = if (allFilesSelected) "取消全选" else "全选文件",
                            style = AuroraTextStyles.body1,
                            color = AuroraTokens.Accent
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNewFileRequest() }
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    Text(
                        text = "新建文件",
                        style = AuroraTextStyles.body1,
                        color = AuroraTokens.Accent
                    )
                }
            }
        }
    }
}
