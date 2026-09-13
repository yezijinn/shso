// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mixradio.droid.data.syntax.SyntaxPack
import com.mixradio.droid.data.syntax.SyntaxPackStore
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语法包管理：导入（本地文件 / https URL）、启用、删除。
 *
 * 导入在 IO 线程执行并做体积、SHA-256、JSON 结构校验；失败只在弹窗内提示，不影响内置语法。
 * 内置语法的仓库目录为 `syntax-packs/`，URL 导入默认指向其 GitHub raw 直链。
 */
@Composable
fun SyntaxPackDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    /** 语法发生变化（导入/删除/启停）时通知调用方刷新编辑器语法。 */
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var packs by remember { mutableStateOf(SyntaxPackStore.list(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlPrefill by remember { mutableStateOf("") }

    fun refresh(msg: String?) {
        packs = SyntaxPackStore.list(context)
        message = msg
        onChanged()
    }

    AuroraWindowDialog(
        show = show,
        title = "语法包",
        summary = "导入语法包压缩档（.zip，内含 index.json 与语法 JSON）；APK 不内置语法，导入后按扩展名生效",
        onDismissRequest = onDismissRequest
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (packs.isEmpty()) {
                Text(
                    "暂无外部语法包。内置语法始终可用。",
                    style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                    items(packs, key = { it.id }) { pack ->
                        PackRow(
                            pack = pack,
                            onToggle = {
                                SyntaxPackStore.setEnabled(context, pack.id, !pack.enabled)
                                refresh("已${if (pack.enabled) "停用" else "启用"} ${pack.id}")
                            },
                            onRemove = {
                                SyntaxPackStore.remove(context, pack.id)
                                refresh("已删除 ${pack.id}")
                            }
                        )
                    }
                }
            }

            message?.let {
                Text(
                    it, style = AuroraTextStyles.footnote2,
                    color = if (it.startsWith("已")) AuroraTokens.Success else AuroraTokens.Error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { showFilePicker = true },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.SurfaceHover, contentColor = AuroraTokens.Text)
                ) { Text("从文件导入") }
                Button(
                    onClick = { showUrlInput = true },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)
                ) { Text("从 URL 导入") }
            }
            if (busy) {
                Text("正在导入…", style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp))
            }
            // 仓库语法包压缩档的永固直链（点击即预填 URL）
            Text(
                "仓库语法包（点击预填直链）",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                modifier = Modifier.padding(top = 10.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                items(SyntaxPackUrls.PRESETS) { preset ->
                    Text(
                        "↓ ${preset.second}（${preset.first}）",
                        style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable {
                                urlPrefill = preset.second
                                showUrlInput = true
                            }
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }
    }

    if (showFilePicker) {
        BuiltInFilePicker(
            appSettings = com.mixradio.droid.data.AppSettings.getInstance(context),
            show = true,
            titleText = "选择语法包",
            subtitleText = "支持语法包压缩档（.zip）或单个 Monarch 语法（.json）",
            fileFilter = { it.name.lowercase().endsWith(".zip") || it.name.lowercase().endsWith(".json") },
            onDismissRequest = { showFilePicker = false },
            onFileSelected = { path ->
                showFilePicker = false
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { SyntaxPackStore.importFromFile(context, path) }
                    busy = false
                    refresh(result.fold({ "已导入 $it 个语法包" }, { "导入失败：${it.message}" }))
                }
            }
        )
    }

    if (showUrlInput) {
        UrlImportDialog(
            prefillUrl = urlPrefill,
            onDismiss = { showUrlInput = false },
            onConfirm = { url, sha ->
                showUrlInput = false
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { SyntaxPackStore.importFromUrl(context, url, sha.ifBlank { null }) }
                    busy = false
                    refresh(result.fold({ "已导入 $it 个语法包" }, { "导入失败：${it.message}" }))
                }
            }
        )
    }
}

@Composable
private fun PackRow(pack: SyntaxPack, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${pack.id}  ·  .${pack.id}",
                style = AuroraTextStyles.body2,
                color = if (pack.enabled) AuroraTokens.Text else AuroraTokens.TextDisabled,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${pack.sizeBytes} B  ·  ${pack.source}",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            if (pack.enabled) "停用" else "启用",
            style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
            modifier = Modifier.clickable { onToggle() }.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            "✕", style = AuroraTextStyles.body2, color = AuroraTokens.Error,
            modifier = Modifier.clickable { onRemove() }.padding(4.dp)
        )
    }
}

@Composable
private fun UrlImportDialog(prefillUrl: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var url by remember { mutableStateOf(prefillUrl) }
    var sha by remember { mutableStateOf("") }
    AuroraWindowDialog(
        show = true,
        title = "从 URL 导入",
        summary = "支持 https（含 GitHub raw）。建议填写 SHA-256 以校验完整性",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("https://…/xxx.json") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sha, onValueChange = { sha = it },
                label = { Text("SHA-256（可留空）") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onConfirm(url.trim(), sha.trim()) },
                    enabled = url.startsWith("https://"),
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)
                ) { Text("导入") }
            }
        }
    }
}

/**
 * 语法包压缩档的**永固直链**：指向仓库 `syntax-packs.zip`（整包：index.json + 全部语法）。
 * 直链通过 git tag 固定（`refs/tags/<tag>/...`），仓库后续更新不影响已发布的链接。
 */
object SyntaxPackUrls {
    private const val REPO = "yezijinn/shso"
    private const val TAG = "syntaxpacks-v1"

    /** 整包 zip（62 种语言 / 187 个扩展名）。 */
    const val PACK_ZIP = "https://raw.githubusercontent.com/$REPO/refs/tags/$TAG/syntax-packs.zip"

    /** 弹窗里展示的快捷导入项：(说明, 直链)。 */
    val PRESETS: List<Pair<String, String>> = listOf(
        "全部 62 种语言" to PACK_ZIP
    )
}
