// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.mixradio.droid.data.filterInstalledApps
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.auroraSwitchColors
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「提取 APK」的执行作用域：**不随组合销毁**。
 *
 * 提取底层是逐个 `cp` 到 Download 的不可撤销磁盘副作用。页面级
 * `rememberCoroutineScope()` 在旋屏/分屏重建时被取消，取消只在**下一个挂起点**生效：
 * 已经复制的分片留在 Download、未复制的没有 —— 用户拿到的是装不上的**半套**
 * （base + 部分 split），全程无任何提示。
 * 安装 / 批量 / 解压·拷贝·移动都已提到文件级作用域防这一类取消，提取此前是唯一遗漏。
 */
private val extractJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * 提取进行中状态：**进程级**，不随组合销毁。
 *
 * 页面级 remember 在旋转重建后归零、入口重新可点，而提取仍在进程作用域里跑 ——
 * 再点一次就对同一批目标文件名叠加一次并发 `cp -f`。两条状态都提到进程级，
 * 使「提取中」的判定与执行作用域一致。
 */
private var extractingPkg by mutableStateOf<String?>(null)
private var extractStatusMessage by mutableStateOf<String?>(null)

/**
 * 「提取 APK」弹窗：列出已安装应用，点击即把其安装包导出到内部存储 `Download/`。
 *
 * - 默认只列**用户应用**，顶部开关可切换「含系统应用」
 * - 搜索框按应用名 / 包名过滤**已加载**的列表（不触发重新枚举）
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
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp

    var includeSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // 搜索词用 rememberSaveable：旋屏/分屏重建后不该把用户刚敲的过滤条件清空
    //（列表会重新枚举，但过滤条件保留，用户看到的是同一批结果）。
    var query by rememberSaveable { mutableStateOf("") }

    // 切换「含系统应用」需重新枚举 → 以 includeSystem 为 key。
    // query **不进** key：敲字只需过滤已有列表，重新枚举会让每次按键都扫一遍 PackageManager。
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

    // 过滤结果只依赖（列表实例, 搜索词）：列表不变时同一搜索词只算一次。
    val filtered = remember(apps, query) { filterInstalledApps(apps, query) }

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

                // 搜索框：按应用名或包名过滤已加载列表
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索应用名或包名") },
                        singleLine = true,
                        colors = auroraTextFieldColors(),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(0.dp))
                    )
                    if (query.isNotEmpty()) {
                        Text(
                            text = "清除",
                            style = AuroraTextStyles.footnote1,
                            color = AuroraTokens.Accent,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { query = "" }
                                .padding(start = 12.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)
                        )
                    }
                }

                // 状态行（加载 / 错误 / 上次结果），**有搜索词时任何分支都带命中数**
                val countLabel = if (query.isBlank()) {
                    "共 ${apps.size} 个应用"
                } else {
                    "命中 ${filtered.size} 个 / 共 ${apps.size} 个"
                }
                val infoLine = when {
                    loading -> "正在读取应用列表…"
                    loadError != null -> loadError
                    extractStatusMessage != null -> "$extractStatusMessage · $countLabel"
                    else -> countLabel
                }
                Text(
                    text = infoLine ?: "",
                    style = AuroraTextStyles.footnote2,
                    color = if (loadError != null) AuroraTokens.Error else AuroraTokens.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // 应用列表（仅此区滚动）。key 用 packageName —— 过滤不改变任何项的 key，
                // LazyColumn 的复用与动画稳定；getInstalledPackages 只返回当前用户，
                // 同一包名不会重复出现。
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (filtered.isEmpty() && !loading && loadError == null) {
                        item {
                            Text(
                                text = if (apps.isEmpty()) "没有可提取的应用" else "没有匹配的应用",
                                style = AuroraTextStyles.footnote2,
                                color = AuroraTokens.TextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                    items(filtered, key = { it.packageName }) { app ->
                        val isExtracting = extractingPkg == app.packageName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = extractingPkg == null) {
                                    if (extractingPkg != null) return@clickable
                                    extractingPkg = app.packageName
                                    extractStatusMessage = null
                                    extractJobScope.launch {
                                        val result = ApkExtractor.extract(context, app)
                                        extractingPkg = null
                                        extractStatusMessage = result.message
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
