// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import androidx.compose.foundation.clickable
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
import com.mixradio.droid.data.security.RiskLevel
import com.mixradio.droid.data.security.ScriptAuditor
import com.mixradio.droid.data.security.SecurityLevels
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 执行文件前的强制风险确认弹窗（安全改造增强版）。
 *
 * 四段式结构：① 风险说明 → ② 文件信息（剖析）→ ③ 脚本风险扫描（带行号，档位 ≥2）→
 * ④ 按钮区 [不要执行] [确认执行]。
 *
 * - 档位 3（最强防护）：增加「以 Root 权限执行」勾选行，默认**不勾选**（脚本默认非 Root）；
 *   且扫描发现 CRITICAL 项时需输入 EXECUTE 才能点亮「确认执行」。
 * - 档位 2（标准防护）：扫描 CRITICAL 项只做普通提示，**不需要打字**，普通确认即可。
 *
 * @param show          是否展示（通常绑定 pendingItem != null）
 * @param fileItem      待执行文件；为 null 时不渲染
 * @param securityLevel 当前安全档位（0-3）
 * @param onDismiss     点击「不要执行」或对话框外区域
 * @param onConfirm     用户点「确认执行」后的回调（runAsRoot = 用户勾选结果）
 */
@Composable
fun ExecuteConfirmDialog(
    show: Boolean,
    fileItem: FileItem?,
    securityLevel: Int = SecurityLevels.STANDARD,
    onDismiss: () -> Unit,
    onConfirm: (runAsRoot: Boolean) -> Unit
) {
    var info by remember { mutableStateOf<ExecutionInfo?>(null) }
    var scanReport by remember { mutableStateOf<ScriptAuditor.Report?>(null) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    var runAsRootChecked by remember { mutableStateOf(false) }
    var typedConfirm by remember { mutableStateOf("") }

    val isSh = fileItem?.name?.endsWith(".sh", ignoreCase = true) == true
    val scanEnabled = securityLevel >= SecurityLevels.STANDARD

    LaunchedEffect(show, fileItem?.path) {
        if (show && fileItem != null) {
            info = null
            scanReport = null
            scanNote = null
            typedConfirm = ""
            // 档位 3 默认非 Root（每次打开重置）
            runAsRootChecked = securityLevel < SecurityLevels.MAXIMUM
            info = withContext(Dispatchers.IO) { analyzeExecution(fileItem) }
            if (isSh && scanEnabled) {
                val (content, note) = withContext(Dispatchers.IO) { ScriptAuditor.readScriptContent(fileItem.path) }
                if (content != null) {
                    scanReport = withContext(Dispatchers.Default) { ScriptAuditor.audit(content) }
                } else {
                    scanNote = note
                }
            }
        } else {
            info = null
            scanReport = null
            scanNote = null
        }
    }

    val report = scanReport
    val hasCritical = report != null && report.findings.any { it.level == RiskLevel.CRITICAL }
    // 「输入 EXECUTE」属**档位 3 专属**能力：档位 2 只需普通确认（点「确认执行」即可）。
    // 早期实现用 scanEnabled（档位 ≥2）判定，使档位 2 也强制打字 —— 与文档语义不符，已收归到档位 3。
    val needTypedConfirm = needTypedExecuteConfirm(securityLevel, hasCritical)
    val typedOk = !needTypedConfirm || typedConfirm.trim() == "EXECUTE"

    AuroraWindowDialog(
        show = show && fileItem != null,
        title = "⚠️ 高风险操作",
        onDismissRequest = onDismiss
    ) {
        // ① 风险说明
        Text(
            text = "即将执行此文件。\n" +
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
            InfoRow("Root 权限", if (securityLevel >= SecurityLevels.MAXIMUM) "默认非 Root（可勾选）" else i.rootLabel)
            InfoRow("文件内容", i.contentLabel)
        } ?: Text(
            text = "正在分析文件…",
            style = AuroraTextStyles.body2,
            color = AuroraTokens.TextSecondary,
            textAlign = TextAlign.Start
        )

        // ③ 脚本风险扫描（档位 ≥2）
        if (scanEnabled && isSh) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "── 脚本风险扫描 ──",
                style = AuroraTextStyles.footnote2,
                color = AuroraTokens.TextSecondary
            )
            when {
                scanNote != null -> Text(
                    text = "⚠ $scanNote",
                    style = AuroraTextStyles.body2,
                    color = AuroraTokens.Error
                )
                report == null -> Text(
                    text = "正在扫描…",
                    style = AuroraTextStyles.body2,
                    color = AuroraTokens.TextSecondary
                )
                report.clean -> Text(
                    text = "未发现高危模式（静态扫描，不保证穷尽）",
                    style = AuroraTextStyles.body2,
                    color = AuroraTokens.Text
                )
                else -> {
                    report.findings.take(8).forEach { f ->
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "第 ${f.line} 行 [${f.level}] ${f.message}",
                                style = AuroraTextStyles.body2,
                                color = if (f.level == RiskLevel.CRITICAL) AuroraTokens.Error else AuroraTokens.Text,
                                fontFamily = FontFamily.Monospace
                            )
                            if (f.snippet.isNotBlank() && f.snippet != f.message) {
                                Text(
                                    text = f.snippet.take(160),
                                    style = AuroraTextStyles.footnote2,
                                    color = AuroraTokens.TextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    if (report.findings.size > 8) {
                        Text(
                            text = "…另有 ${report.findings.size - 8} 项",
                            style = AuroraTextStyles.footnote2,
                            color = AuroraTokens.TextSecondary
                        )
                    }
                }
            }
        }

        // ③' 档位 3：Root 勾选行（纯文本贴边，遵守弹窗纯文本风格）
        if (securityLevel >= SecurityLevels.MAXIMUM) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runAsRootChecked = !runAsRootChecked }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (runAsRootChecked) "☑" else "□",
                    style = AuroraTextStyles.body2,
                    color = if (runAsRootChecked) AuroraTokens.Error else AuroraTokens.TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(
                    text = "以 Root 权限执行" + if (runAsRootChecked) "（风险自负）" else "（默认非 Root，更安全）",
                    style = AuroraTextStyles.body2,
                    color = if (runAsRootChecked) AuroraTokens.Error else AuroraTokens.Text
                )
            }
        }

        // ③'' CRITICAL 项需输入 EXECUTE
        if (needTypedConfirm) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "扫描发现 CRITICAL 级风险，输入 EXECUTE 以解锁执行：",
                style = AuroraTextStyles.body2,
                color = AuroraTokens.Error
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextField(
                value = typedConfirm,
                onValueChange = { typedConfirm = it },
                singleLine = true,
                textStyle = AuroraTextStyles.body2.copy(fontFamily = FontFamily.Monospace),
                colors = auroraTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ④ 按钮
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
                onClick = { onConfirm(runAsRootChecked) },
                enabled = info != null && typedOk,
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

/**
 * 「CRITICAL 需输入 EXECUTE」是否生效（纯函数，便于单测）。
 *
 * 仅**档位 3（最强防护）**+ 扫描发现 CRITICAL 时才要求打字确认；
 * 档位 2（标准防护）走普通确认框即可。档位 ≤1 不做脚本扫描，故 never 触发。
 */
internal fun needTypedExecuteConfirm(securityLevel: Int, hasCritical: Boolean): Boolean =
    securityLevel >= SecurityLevels.MAXIMUM && hasCritical

/** 单行信息：标签（次要色）+ 等宽值（便于路径 / 哈希断行对齐）。 */
@Composable
private fun InfoRow(label: String, value: String) {    Column(
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
