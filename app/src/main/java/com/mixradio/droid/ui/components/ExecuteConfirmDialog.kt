// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton

/**
 * 执行身份确认：取消 / 无 ROOT / 有 ROOT。
 *
 * 为什么不直接执行：同一个脚本以 ROOT 和以普通用户身份跑，结果可能完全不同 ——
 * 能读到哪些文件、能写哪些目录、HOME 与 PATH 指向哪里都不一样。默认一律按 ROOT 跑，
 * 「以自己的身份跑一下」就没有入口；反过来替用户猜普通用户身份，又会在需要 ROOT
 * 的目录上莫名失败。这一步交给用户选。
 *
 * @param rootGranted 是否已授权 ROOT。未授权时「有 ROOT」置灰并说明原因 ——
 *   留着可点的话，用户得到的是一串来自 su 的失败输出，而不是一句「没授权」。
 */
@Composable
fun ExecuteConfirmDialog(
    show: Boolean,
    fileItem: FileItem?,
    rootGranted: Boolean,
    onDismiss: () -> Unit,
    onPick: (asRoot: Boolean) -> Unit
) {
    if (!show || fileItem == null) return
    AuroraWindowDialog(
        show = true,
        title = "执行 ${fileItem.name}",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            InfoRow(label = "路径", value = fileItem.path)

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (rootGranted) {
                    "ROOT 身份可读写应用自身访问不到的目录；普通用户身份以当前应用权限运行，" +
                        "读不到 /data/adb 等受限路径。"
                } else {
                    "尚未授权 ROOT，只能以普通用户身份执行。"
                },
                style = AuroraTextStyles.footnote2,
                color = if (rootGranted) AuroraTokens.TextSecondary else AuroraTokens.Warning
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 三枚按钮等分宽度。Material3 的 Button 有最小宽度与 24dp 内边距，
            // 三枚并排本就超出行宽：不定宽时最后一枚被压到最窄，文字逐字竖排
            // （真机实测「有 ROOT」变成「有 / R / O / O / T」）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.SurfaceHover,
                        contentColor = AuroraTokens.Text
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .auroraFilledButton()
                ) {
                    Text(text = "取消", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = { onPick(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.Accent,
                        contentColor = AuroraTokens.OnAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .auroraFilledButton()
                ) {
                    Text(text = "无ROOT", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = { onPick(true) },
                    enabled = rootGranted,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.Error,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .auroraFilledButton()
                ) {
                    Text(text = "有ROOT", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
