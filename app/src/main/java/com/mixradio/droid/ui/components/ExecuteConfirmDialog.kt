// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mixradio.droid.data.ExecutionInfo
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.analyzeExecution
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 执行文件前的强制风险确认弹窗。
 *
 * 三段式结构：① 风险说明（红色警告正文）→ ② 文件信息（文件名 / 路径 / 类型 / 大小 /
 * 修改时间 / SHA-256 / Root 权限 / 明文或加密）→ ③ 按钮 [不要执行] [确认执行]。
 *
 * 打开时异步剖析文件（SHA-256 等），剖析完成前「确认执行」禁用并显示「正在分析文件…」。
 *
 * @param show      是否展示（通常绑定 pendingItem != null）
 * @param fileItem  待执行文件；为 null 时不渲染
 * @param onDismiss 点击「不要执行」或对话框外区域
 * @param onConfirm 用户点「确认执行」后的回调（调用方执行真实动作）
 */
@Composable
fun ExecuteConfirmDialog(
    show: Boolean,
    fileItem: FileItem?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var info by remember { mutableStateOf<ExecutionInfo?>(null) }

    LaunchedEffect(show, fileItem?.path) {
        if (show && fileItem != null) {
            info = null
            info = withContext(Dispatchers.IO) { analyzeExecution(fileItem) }
        } else {
            info = null
        }
    }

    AuroraWindowDialog(
        show = show && fileItem != null,
        title = "⚠️ 高风险操作",
        onDismissRequest = onDismiss
    ) {
        // ① 风险说明
        Text(
            text = "即将以 Root 权限执行此文件。\n" +
                "文件内容可能修改、删除系统文件或执行其他高风险操作，错误操作可能导致系统异常、数据丢失甚至无法正常开机。",
            style = AuroraTextStyles.body2,
            color = AuroraTokens.Error,
            textAlign = TextAlign.Start
        )
        Spacer(modifier = Modifier.height(14.dp))

        // ② 文件信息
        info?.let { i ->
            InfoRow("文件名", i.name)
            InfoRow("文件路径", i.path)
            InfoRow("文件类型", i.typeLabel)
            InfoRow("文件大小", i.sizeLabel)
            InfoRow("最后修改时间", i.modifiedLabel)
            InfoRow("SHA-256", i.sha256)
            InfoRow("Root 权限", i.rootLabel)
            InfoRow("文件内容", i.contentLabel)
        } ?: Text(
            text = "正在分析文件…",
            style = AuroraTextStyles.body2,
            color = AuroraTokens.TextSecondary,
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ③ 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuroraTokens.SurfaceHover,
                    contentColor = AuroraTokens.Text
                ),
                modifier = Modifier.auroraFilledButton()
            ) {
                Text(text = "不要执行", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onConfirm,
                enabled = info != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuroraTokens.Error,
                    contentColor = Color.White
                ),
                modifier = Modifier.auroraFilledButton()
            ) {
                Text(text = "确认执行", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 单行信息：标签（次要色）+ 等宽值（便于路径 / 哈希断行对齐）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = AuroraTextStyles.footnote2,
            color = AuroraTokens.TextSecondary
        )
        Text(
            text = value,
            style = AuroraTextStyles.body2,
            color = AuroraTokens.Text,
            fontFamily = FontFamily.Monospace
        )
    }
}
