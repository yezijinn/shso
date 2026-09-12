// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mixradio.droid.data.ApkExtractor
import com.mixradio.droid.data.InstalledAppInfo
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.auroraSwitchColors
import kotlinx.coroutines.launch

/**
 * 「提取 APK」弹窗：列出已安装应用，点击即把其安装包导出到内部存储 `Download/`。
 *
 * - 默认只列**用户应用**，顶部开关可切换「含系统应用」
 * - 命名：基础包 `<应用名>-<versionCode>.APK`；分包应用追加 `-splitN.APK`
 * - 读取应用私有安装包需要 ROOT；无 ROOT 时仅系统分区上可直读的应用能提取
 *
 * @param onResult 提取结束回调（成功与否 + 提示文案），由调用方负责 Toast 与刷新列表
 */
@Composable
internal fun ApkExtractDialog(
    onDismissRequest: () -> Unit,
    onResult: (ok: Boolean, message: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp

    var includeSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var extractingPkg by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // 切换「含系统应用」需重新枚举 → 以 includeSystem 为 key
    LaunchedEffect(includeSystem) {
        loading = true
        loadError = null
        try {
            apps = ApkExtractor.listInstalledApps(context, includeSystem)
        } catch (e: Exception) {
            loadError = "读取应用列表失败: ${e.message}"
            apps = emptyList()
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = { if (extractingPkg == null) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AuroraTokens.DialogBg,
            shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, AuroraTokens.Stroke),
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .height(maxContentHeight)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                // 标题
                Text(
                    text = "提取 APK",
                    style = AuroraTextStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = AuroraTokens.Text,
                    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
                )
                Text(
                    text = "导出到 Download，文件名：应用名-版本号.APK",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )

                // 含系统应用开关
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "含系统应用",
                        style = AuroraTextStyles.body1,
                        color = AuroraTokens.Text,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = includeSystem,
                        onCheckedChange = { includeSystem = it },
                        colors = auroraSwitchColors()
                    )
                }

                // 状态行（加载 / 错误 / 上次结果）
                val infoLine = when {
                    loading -> "正在读取应用列表…"
                    loadError != null -> loadError
                    statusMessage != null -> statusMessage
                    else -> "共 ${apps.size} 个应用"
                }
                Text(
                    text = infoLine ?: "",
                    style = AuroraTextStyles.footnote2,
                    color = if (loadError != null) AuroraTokens.Error else AuroraTokens.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // 应用列表（仅此区滚动）
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        val isExtracting = extractingPkg == app.packageName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = extractingPkg == null) {
                                    if (extractingPkg != null) return@clickable
                                    extractingPkg = app.packageName
                                    statusMessage = null
                                    scope.launch {
                                        val result = ApkExtractor.extract(context, app)
                                        extractingPkg = null
                                        statusMessage = result.message
                                        onResult(result.ok, result.message)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = AuroraTextStyles.body1,
                                    color = AuroraTokens.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        append(app.packageName)
                                        append(" · v").append(app.versionCode)
                                        if (app.isSplit) append(" · 分包 ").append(app.splitApks.size)
                                    },
                                    style = AuroraTextStyles.footnote2,
                                    color = AuroraTokens.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (isExtracting) "提取中…" else "提取",
                                style = AuroraTextStyles.footnote1,
                                color = if (isExtracting) AuroraTokens.TextSecondary else AuroraTokens.Accent,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // 底部关闭
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = onDismissRequest,
                        enabled = extractingPkg == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        ),
                        modifier = Modifier.clip(RoundedCornerShape(0.dp))
                    ) {
                        Text("关闭", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
