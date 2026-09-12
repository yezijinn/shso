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
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mixradio.droid.data.security.Finding
import com.mixradio.droid.data.security.RiskLevel
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import com.mixradio.droid.ui.theme.auroraTextFieldColors

/**
 * 终端高危命令风险确认弹窗（方案 §5.4 escalation）。
 *
 * - 展示 [Finding] 列表（规则 / 等级 / 描述 / 命中片段）；
 * - CRITICAL 级需输入 EXECUTE 才能确认（防手滑）：**精确匹配大写**，输入前会 `trim()`；
 * - [不要执行] / [确认执行] 双按钮。
 */
@Composable
fun CommandRiskDialog(
    show: Boolean,
    findings: List<Finding>,
    level: RiskLevel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var typed by remember(findings) { mutableStateOf("") }
    val needTyped = level == RiskLevel.CRITICAL
    val typedOk = !needTyped || typed.trim() == "EXECUTE"

    AuroraWindowDialog(
        show = show && findings.isNotEmpty(),
        title = "⚠️ 高风险指令",
        onDismissRequest = onDismiss
    ) {
        Text(
            text = "即将执行的命令命中以下风险规则：",
            style = AuroraTextStyles.body2,
            color = AuroraTokens.Error
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 最多展示 8 项：弹窗高度受限，其余折叠为计数，避免长列表把按钮挤出视口
        findings.take(8).forEach { f ->
            Column(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "[${f.level}] ${f.message}",
                    style = AuroraTextStyles.body2,
                    color = if (f.level == RiskLevel.CRITICAL) AuroraTokens.Error else AuroraTokens.Text,
                    fontFamily = FontFamily.Monospace
                )
                if (f.snippet.isNotBlank()) {
                    Text(
                        text = f.snippet.take(160),
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        if (findings.size > 8) {
            Text(
                text = "…另有 ${findings.size - 8} 项",
                style = AuroraTextStyles.footnote2,
                color = AuroraTokens.TextSecondary
            )
        }

        if (needTyped) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "CRITICAL 级风险，输入 EXECUTE 以解锁执行：",
                style = AuroraTextStyles.body2,
                color = AuroraTokens.Error
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                textStyle = AuroraTextStyles.body2.copy(fontFamily = FontFamily.Monospace),
                colors = auroraTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
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
                enabled = typedOk,
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
