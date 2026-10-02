// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mixradio.droid.data.ExecutionInfo
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.analyzeExecution
import com.mixradio.droid.data.stageApkForInstall
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安装 APK / XAPK 前的确认弹窗。
 *
 * 存在的理由：ROOT 授权下安装走 `pm install` **静默完成**（无系统安装器界面、无二次确认）。
 * 手动路径里「安装 APK/XAPK」是用户主动点选的显式意图，但**外部唤起**（其他应用「打开方式 / 分享」）
 * 属于被动触发 —— 诱导用户打开一个 APK 即可静默安装。故外部唤起必须经此确认。
 *
 * 四段结构：① 来源说明 → ② 文件信息（名称/路径/大小/SHA-256）→ ③ 安装方式 → ④ 按钮区。
 *
 * @param show              是否展示（通常绑定 pendingItem != null）
 * @param fileItem          待安装文件；为 null 时不渲染
 * @param willInstallAsRoot true = 将以 ROOT 静默安装（无系统确认），false = 交系统安装器
 * @param onDismiss         取消
 * @param onConfirm         确认安装，参数为确认时展示的 SHA-256 与锁定的安装模式
 */
@Composable
fun InstallConfirmDialog(
    show: Boolean,
    fileItem: FileItem?,
    willInstallAsRoot: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (confirmedSha256: String, installAsRoot: Boolean, stagedPath: String) -> Unit
) {
    val context = LocalContext.current.applicationContext
    var info by remember { mutableStateOf<ExecutionInfo?>(null) }
    var confirmedSha256 by remember { mutableStateOf<String?>(null) }
    var stagedPath by remember { mutableStateOf<String?>(null) }
    var lockedInstallAsRoot by remember { mutableStateOf(willInstallAsRoot) }
    // staged 副本的**所有权**标记。一旦 onConfirm 交出，弹窗就不再负责删除：
    // 否则 onConfirm 里把 pendingInstallItem 置 null 会让 show 变 false，
    // 本 LaunchedEffect 重启后走 else 分支把副本删掉，而 startInstall 的协程
    // 还要用它算 sha256、再喂给 ApkInstaller —— 两条 Main 派发谁先跑取决于帧时序，
    // 表现为「安装时好时坏 / 报安装包在确认后发生变化」。
    var stagedConsumed by remember { mutableStateOf(false) }

    LaunchedEffect(show, fileItem?.path) {
        info = if (show && fileItem != null) {
            lockedInstallAsRoot = willInstallAsRoot
            val currentInfo = withContext(Dispatchers.IO) { analyzeExecution(fileItem) }
            val staged = stageApkForInstall(context, fileItem.path)
            stagedPath = staged?.first
            confirmedSha256 = staged?.second
            stagedConsumed = false
            val sizeLabel = staged?.first?.let { java.io.File(it).length() }?.let { "$it B" }
            currentInfo.copy(
                sizeLabel = sizeLabel ?: currentInfo.sizeLabel,
                sha256 = confirmedSha256 ?: "无法生成安装副本"
            )
        } else {
            // 仅在所有权未交出时清理（用户直接关闭弹窗 / 换了目标文件）
            if (!stagedConsumed) stagedPath?.let { java.io.File(it).delete() }
            stagedPath = null
            confirmedSha256 = null
            null
        }
    }

    val handOffStaged = { stagedConsumed = true }

    AuroraWindowDialog(
        show = show && fileItem != null,
        title = "⚠️ 安装确认",
        onDismissRequest = onDismiss
    ) {
        // ① 来源说明
        Text(
            text = "即将安装此安装包。\n" +
                "安装包由其他应用传入或来自本机文件，请确认来源可信后再安装。",
            style = AuroraTextStyles.body2,
            color = AuroraTokens.Error,
            textAlign = TextAlign.Start
        )
        Spacer(modifier = Modifier.height(14.dp))

        // ② 文件信息
        info?.let { i ->
            InfoRow("文件名", i.name)
            InfoRow("文件路径", i.path)
            InfoRow("文件大小", i.sizeLabel)
            InfoRow("SHA-256", i.sha256)
        } ?: Text(
            text = "正在读取安装包信息…",
            style = AuroraTextStyles.body2,
            color = AuroraTokens.TextSecondary,
            textAlign = TextAlign.Start
        )

        // ③ 安装方式：静默安装是风险重点，必须显式告知
        Spacer(modifier = Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "安装方式",
                style = AuroraTextStyles.footnote2,
                color = AuroraTokens.TextSecondary
            )
            Text(
                text = if (lockedInstallAsRoot) {
                    "ROOT 静默安装（无系统安装器确认）"
                } else {
                    "系统安装器（需在系统界面确认）"
                },
                style = AuroraTextStyles.body2,
                color = if (lockedInstallAsRoot) AuroraTokens.Error else AuroraTokens.Text
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
                Text(text = "取消", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    val confirmed = info ?: return@Button
                    val sha = confirmedSha256 ?: return@Button
                    val staged = stagedPath ?: return@Button
                    // 先交出所有权，再回调：回调会把 pendingInstallItem 置 null，
                    // 本弹窗随即关闭并重启 LaunchedEffect，此时不得再删副本。
                    handOffStaged()
                    onConfirm(sha, lockedInstallAsRoot, staged)
                },
                enabled = info != null && confirmedSha256 != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AuroraTokens.Error,
                    contentColor = Color.White
                ),
                modifier = Modifier.auroraFilledButton()
            ) {
                Text(text = "确认安装", fontWeight = FontWeight.Bold)
            }
        }
    }
}
