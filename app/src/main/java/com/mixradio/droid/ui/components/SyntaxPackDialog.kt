// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.window.DialogProperties
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.syntax.SyntaxPack
import com.mixradio.droid.data.syntax.SyntaxPackStore
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// BuiltInFilePicker 把 fileFilter 当 remember 的 key：内联 lambda 每次重组都是新实例，
// 缓存会逐帧失效并重跑过滤 + 排序。提到顶层做常量，身份跨重组稳定。
private val SYNTAX_PACK_FILE_FILTER: (FileItem) -> Boolean = { item ->
    val name = item.name.lowercase()
    name.endsWith(".zip") || name.endsWith(".json")
}

/**
 * 语法包管理：导入（本地文件 / https URL）、启用、删除。
 *
 * 导入在 IO 线程执行并做体积、SHA-256、JSON 结构校验；失败只在弹窗内提示，不影响内置语法。
 * 内置语法的仓库目录为 `syntax-packs/`，URL 导入默认指向其 Gitee raw 直链（GitHub 为备用源）。
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
    var pendingRemove by remember { mutableStateOf<SyntaxPack?>(null) }

    fun refresh(msg: String?) {
        packs = SyntaxPackStore.list(context)
        message = msg
        onChanged()
    }

    AuroraWindowDialog(
        show = show,
        title = "语法包",
        summary = "导入语法包压缩档（.zip，内含 index.json 与语法 JSON）；APK 不内置语法，导入后按扩展名生效",
        onDismissRequest = onDismissRequest,
        // 全宽：对齐 APP 主内容宽度（默认平台默认宽度会让窗口偏窄）
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (packs.isEmpty()) {
                Text(
                    "暂无外部语法包：编辑器按纯文本处理，可用下方按钮导入本地 zip 或直接从仓库下载。",
                    style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                // 汇总 + 批量操作：62 个包逐个点开关不现实
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已导入 ${packs.size} 个 · 启用 ${packs.count { it.enabled }} 个",
                        style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 裸文字操作：命中区按 M3 最小触控尺寸补齐（原来只有文字高度，约 14dp）。
                        // clickable 必须在 minimumInteractiveComponentSize 外层，否则命中区仍是文字本身。
                        Text(
                            "全部启用",
                            style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
                            modifier = Modifier
                                .clickable {
                                    SyntaxPackStore.setAllEnabled(context, true)
                                    refresh("已启用全部 ${packs.size} 个语法包")
                                }
                                .minimumInteractiveComponentSize()
                        )
                        Text(
                            "全部停用",
                            style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                            modifier = Modifier
                                .clickable {
                                    SyntaxPackStore.setAllEnabled(context, false)
                                    refresh("已停用全部语法包")
                                }
                                .minimumInteractiveComponentSize()
                        )
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                    items(packs, key = { it.id }) { pack ->
                        PackRow(
                            pack = pack,
                            onToggle = {
                                SyntaxPackStore.setEnabled(context, pack.id, !pack.enabled)
                                refresh("已${if (pack.enabled) "停用" else "启用"} ${pack.id}")
                            },
                            onRemove = { pendingRemove = pack }
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
                    onClick = { urlPrefill = SyntaxPackUrls.PACK_ZIP; showUrlInput = true },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Accent)
                ) { Text("从仓库下载") }
            }
            // 备用源：首选直链在个别网络下仍不可达时改走 GitHub，两处是同一 tag 下的同一份文件。
            // 整行可点，触控高度按行高约定取 48dp（首选源打不开时这行就是唯一出路，不能做成小字链接）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !busy) {
                        urlPrefill = SyntaxPackUrls.PACK_ZIP_ALT
                        showUrlInput = true
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    "Gitee 不可达？改用 GitHub 直链",
                    style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent
                )
            }
            if (busy) {
                Text("正在导入…", style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    // 删除为破坏性操作：先确认再执行（可重新导入，但误删会立即失去该语言的着色）
    pendingRemove?.let { pack ->
        ConfirmRemoveDialog(
            pack = pack,
            onDismiss = { pendingRemove = null },
            onConfirm = {
                SyntaxPackStore.remove(context, pack.id)
                pendingRemove = null
                refresh("已删除 ${pack.id}")
            }
        )
    }

    if (showFilePicker) {
        BuiltInFilePicker(
            appSettings = com.mixradio.droid.data.AppSettings.getInstance(context),
            show = true,
            titleText = "选择语法包",
            subtitleText = "支持语法包压缩档（.zip）或单个 Monarch 语法（.json）",
            fileFilter = SYNTAX_PACK_FILE_FILTER,
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
                pack.id,
                style = AuroraTextStyles.body2,
                color = if (pack.enabled) AuroraTokens.Text else AuroraTokens.TextDisabled,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // 展示真实匹配范围：多扩展名/无扩展名文件（如 cpp 覆盖 hpp/ino、cmake 覆盖 CMakeLists.txt）
            Text(
                (pack.exts.map { ".$it" } + pack.filenames).joinToString(" "),
                style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${pack.sizeBytes} B  ·  ${SyntaxPackUrls.sourceLabel(pack.source)}",
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            if (pack.enabled) "停用" else "启用",
            style = AuroraTextStyles.footnote2, color = AuroraTokens.Accent,
            modifier = Modifier
                .clickable { onToggle() }
                .minimumInteractiveComponentSize()
        )
        Text(
            "✕", style = AuroraTextStyles.body2, color = AuroraTokens.Error,
            modifier = Modifier
                .clickable { onRemove() }
                .minimumInteractiveComponentSize()
        )
    }
}

@Composable
private fun UrlImportDialog(prefillUrl: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var url by remember { mutableStateOf(prefillUrl) }
    var sha by remember { mutableStateOf("") }
    AuroraWindowDialog(
        show = true,
        title = "从仓库下载",
        summary = "支持 https 直链（Gitee / GitHub raw）。建议填写 SHA-256 以校验完整性",
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

/** 删除确认：破坏性操作不静默执行。 */
@Composable
private fun ConfirmRemoveDialog(pack: SyntaxPack, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AuroraWindowDialog(
        show = true,
        title = "删除语法包",
        summary = "删除后该语言将失去语法着色（可再次导入恢复）",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(pack.id, style = AuroraTextStyles.body2, color = AuroraTokens.Text, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text(
                (pack.exts.map { ".$it" } + pack.filenames).joinToString(" "),
                style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary, maxLines = 2
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraTokens.Error)
                ) { Text("删除") }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.SurfaceHover, contentColor = AuroraTokens.Text
                    )
                ) { Text("取消") }
            }
        }
    }
}

/**
 * 语法包压缩档的**永固直链**：指向仓库 `syntax-packs.zip`（整包：index.json + 全部语法）。
 * 直链通过 git tag 固定（`refs/tags/<tag>/…`、Gitee `/raw/<tag>/…`），仓库后续更新不影响已发布的链接。
 *
 * **两源同物、Gitee 优先**：国内网络访问 `raw.githubusercontent.com` 常超时或被重置，只给 GitHub
 * 一个入口会让「从仓库下载」在多数用户处直接落到网络异常（取舍与「检查更新」一致）。两端点是同一
 * tag 下的同一份文件，字节一致，可互相顶替。
 *
 * **更新语法包时**：重新生成 zip → 把 tag 重指到新提交 → 只改这里的 [TAG]，旧链接继续可用。
 */
object SyntaxPackUrls {
    private const val REPO = "yezijinn/shso"
    private const val TAG = "syntaxpacks-v2"

    /** (源名, 直链)，列表顺序即优先级。 */
    val SOURCES: List<Pair<String, String>> = listOf(
        "gitee" to "https://gitee.com/$REPO/raw/$TAG/syntax-packs.zip",
        "github" to "https://raw.githubusercontent.com/$REPO/refs/tags/$TAG/syntax-packs.zip",
    )

    /** 首选直链（Gitee）：整包 zip（62 种语言 / 187 个扩展名 + 无扩展名文件名匹配）。 */
    val PACK_ZIP: String get() = SOURCES.first().second

    /** 备用直链（GitHub）。 */
    val PACK_ZIP_ALT: String get() = SOURCES.last().second

    /** 已导入包的来源标签：命中已知直链显示源名，本地文件路径原样显示。 */
    fun sourceLabel(source: String): String =
        SOURCES.firstOrNull { it.second == source }?.first ?: source
}
