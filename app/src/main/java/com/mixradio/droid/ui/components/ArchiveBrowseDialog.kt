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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
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
import com.mixradio.droid.data.ArchiveBrowser
import com.mixradio.droid.data.ArchiveBrowser.ArchiveEntry
import com.mixradio.droid.data.ArchiveExtractor
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.computeSha256Strict
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 写回的执行作用域：**不随组合销毁**。
 *
 * 写回是「副本改写 + 校验 + 替换原包」的一次不可撤销磁盘操作。页面级
 * `rememberCoroutineScope()` 会在旋屏/Activity 重建时被取消 —— 而那正好可能落在
 * 副本已改完、原包还没替换的窗口里（收尾清理与结果提示一并丢失）。
 * 与 `ApkExtractDialog` 的提取作用域同一口径。
 */
private val archiveJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** 写回进行中标志：进程级。旋转后入口不复活，避免对同一压缩包叠加两条写回。 */
private var archiveWriteInFlight by mutableStateOf(false)

/** 写回结果：进程级。用户可能在写回期间关掉弹窗，结果必须还能看到。 */
private var archiveWriteStatus by mutableStateOf<String?>(null)

/**
 * 写回成功代数：进程级。
 *
 * 宿主页面观察它来刷新列表 —— 用回调做不到这件事：写回协程活过组合时，
 * 旧闭包里的 `composed` 早已为 false，旋屏后的列表就会停在旧体积上。
 */
internal var archiveWriteGeneration by mutableStateOf(0)
    private set

/**
 * 清掉上一次的结果文案。**写回进行中不清**：那会把正在显示的进度提示抹掉。
 *
 * 打开另一个压缩包时若不清，用户会看到上一次的「已写回原压缩包：a.txt」，
 * 误以为当前包也被改过。
 */
internal fun resetArchiveWriteStatus() {
    if (!archiveWriteInFlight) archiveWriteStatus = null
}

/**
 * 包内编辑会话：**编辑器打开期间**跨配置变更存活。
 *
 * 只存标量（路径 + 原始摘要），`TextEditorDialog` 自己保住正文；
 * 两者一起才能保证「旋转后仍在编辑、保存仍回到同一个包内条目」。
 * 注意：编辑器正文本身不跨旋屏保留（与普通文本编辑一致），
 * 因此旋屏 = 放弃未保存的改动；写回判据是字节比较，不会误写。
 */
private data class EditSession(
    val entryPath: String,
    val cachePath: String,
    val originalSha256: String
)

private val editSessionSaver = listSaver<EditSession?, Any>(
    save = { s ->
        if (s == null) emptyList()
        else listOf(s.entryPath, s.cachePath, s.originalSha256)
    },
    restore = { v ->
        if (v.size != 3) null
        else {
            val entryPath = v[0] as? String
            val cachePath = v[1] as? String
            val sha = v[2] as? String
            if (entryPath == null || cachePath == null || sha == null) null
            else EditSession(entryPath, cachePath, sha)
        }
    }
)

/**
 * 压缩包免解压浏览弹窗。
 *
 * - 只读 central directory / 逐条取流，不整体落盘；目录层级由条目路径合成；
 * - 点文件：文本（仅 ZIP 可写回）→ 编辑器 → 关闭时按字节是否变化决定是否写回；
 *           图片 → 图片查看器（单张）；
 *           只读格式的文本 / 其它类型 → 只给原因，**不抽盘**；
 * - 编辑/查看弹窗是**本弹窗的兄弟节点**（不在 Dialog 里套 Dialog），
 *   避免双层 window 的返回键与焦点竞争。
 *
 * @param onDismiss 关闭浏览
 */
@Composable
internal fun ArchiveBrowseDialog(
    archivePath: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    val archiveName = remember(archivePath) { File(archivePath).name }

    // 根授权状态参与写回能力判定。冷启动时它可能还是 null（未探测完），
    // 因此它被读进组合（值变化会触发重组）并作为 `list` 的 key —— 落定后自动重判。
    val rootGranted = RootService.isRootGranted == true

    var currentDir by rememberSaveable { mutableStateOf("") }
    // 密码不进 saved state：Bundle 可能落盘。旋屏后重输，目录与编辑会话仍保留。
    var passwordInput by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<ArchiveBrowser.ArchiveInfo?>(null) }
    var visible by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var needPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var openingEntry by remember { mutableStateOf<String?>(null) }
    var pendingEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var editSession by rememberSaveable(stateSaver = editSessionSaver) {
        mutableStateOf<EditSession?>(null)
    }
    var imagePath by rememberSaveable { mutableStateOf<String?>(null) }

    val writable = info?.writeSupport == ArchiveBrowser.WriteSupport.ZIP_REWRITE

    fun commitEdit(session: EditSession) {
        archiveJobScope.launch {
            archiveWriteInFlight = true
            archiveWriteStatus = "正在写回压缩包…"
            var keepCache = false
            try {
                val nowSha = computeSha256Strict(session.cachePath)
                when {
                    nowSha == null -> {
                        // 读不到结果 ≠ 可以丢：缓存留下，并把文件名告诉用户。
                        keepCache = true
                        archiveWriteStatus =
                            "无法读取编辑结果，未写回；内容仍在缓存（${File(session.cachePath).name}）"
                    }
                    // 内容未变：不碰原包。判据是字节，不是「编辑器是否点过保存」——
                    // 编辑器可能只改了编码/换行又改回来，也可能「另存为」到别处。
                    nowSha == session.originalSha256 ->
                        archiveWriteStatus = "内容未改动，未写回"
                    else -> {
                        val r = ArchiveBrowser.replaceEntry(
                            archivePath = archivePath,
                            entryPath = session.entryPath,
                            newContent = File(session.cachePath),
                            cacheDir = context.cacheDir,
                            rootGranted = RootService.isRootGranted == true
                        )
                        when (r) {
                            is ArchiveBrowser.WriteResult.Success -> {
                                archiveWriteStatus = "已写回原压缩包：${session.entryPath}"
                                archiveWriteGeneration++
                                reloadKey++
                            }
                            is ArchiveBrowser.WriteResult.Failure -> {
                                // 不丢用户的字：保留缓存并重开编辑器（基线换成当前摘要，
                                // 再关闭不会重复写回），用户仍可「另存为」。
                                keepCache = true
                                archiveWriteStatus = "写回失败：${r.message}；" +
                                    "内容已留在缓存（${File(session.cachePath).name}），可在编辑器中另存为"
                                editSession = EditSession(session.entryPath, session.cachePath, nowSha)
                            }
                        }
                    }
                }
            } finally {
                if (!keepCache) {
                    runCatching { File(session.cachePath).delete() }
                    File(session.cachePath).parentFile?.let { d ->
                        runCatching { if (d.listFiles()?.isEmpty() == true) d.delete() }
                    }
                }
                archiveWriteInFlight = false
            }
        }
    }

    fun openEntry(entry: ArchiveEntry) {
        if (entry.isDirectory) {
            currentDir = entry.path
            return
        }
        if (openingEntry != null) return
        // 复用同源类型谓词：包内 .prop / .txt 与文件页同一套判定，不另写扩展名表。
        val item = FileItem(entry.name, entry.path, false, if (entry.size > 0) entry.size else 0L)
        // 只读格式的文本：抽出来也只能改在缓存里，写不回去就是假承诺 → 不抽盘、不置位。
        if (item.isEditableText && !writable) {
            message = "该格式不支持写回，已禁用编辑；需要编辑请先「自动解压」"
            return
        }
        if (!item.isEditableText && !item.isViewableImage) {
            message = "暂不支持直接打开该类型，可先「自动解压」"
            return
        }
        // 先置标志再 launch：否则连点两下会在协程启动前都通过判定，抽两遍。
        openingEntry = entry.path
        message = null
        scope.launch {
            try {
                when (val r = ArchiveBrowser.openEntry(
                    archivePath = archivePath,
                    entryPath = entry.path,
                    password = password.ifEmpty { null },
                    cacheDir = context.cacheDir
                )) {
                    is ArchiveBrowser.OpenResult.Success -> when {
                        item.isViewableImage -> imagePath = r.cachePath
                        // 文本扩展名不等于文本内容：APK 里的 AndroidManifest.xml 是二进制 AXML，
                        // 送进编辑器再保存会写坏条目（并作废整个包）。
                        ArchiveBrowser.isLikelyBinary(r.cachePath) -> {
                            runCatching { File(r.cachePath).delete() }
                            message = "疑似二进制文件，未以文本打开"
                        }
                        else -> {
                            val sha = computeSha256Strict(r.cachePath)
                            if (sha == null) {
                                runCatching { File(r.cachePath).delete() }
                                message = "无法读取该条目"
                            } else {
                                editSession = EditSession(entry.path, r.cachePath, sha)
                            }
                        }
                    }
                    ArchiveBrowser.OpenResult.NeedPassword -> {
                        pendingEntry = entry
                        message = "该条目已加密，请输入密码后重试"
                    }
                    is ArchiveBrowser.OpenResult.TooLarge ->
                        message = "条目超过 ${ArchiveBrowser.MAX_ENTRY_OPEN_BYTES / 1024 / 1024}MB，无法直接打开"
                    is ArchiveBrowser.OpenResult.Failure -> message = r.message
                }
            } finally {
                openingEntry = null
            }
        }
    }

    LaunchedEffect(archivePath, reloadKey, password, rootGranted) {
        loading = true
        error = null
        needPassword = false
        when (val r = ArchiveBrowser.list(
            archivePath = archivePath,
            password = password.ifEmpty { null },
            rootGranted = rootGranted
        )) {
            is ArchiveBrowser.BrowseResult.Success -> {
                info = r.info
                // 写回可能让当前目录消失（例如该目录下最后一项被替换）
                if (currentDir.isNotEmpty() &&
                    r.info.entries.none { it.path == currentDir || it.path.startsWith("$currentDir/") }
                ) {
                    currentDir = ""
                }
            }
            ArchiveBrowser.BrowseResult.NeedPassword -> {
                info = null
                needPassword = true
            }
            is ArchiveBrowser.BrowseResult.Failure -> {
                info = null
                error = r.message
            }
        }
        loading = false
    }

    // 目录内容由纯函数合成：只有（条目快照, 当前目录）变化才重算
    LaunchedEffect(info, currentDir) {
        visible = info?.let { ArchiveBrowser.childrenOf(it.entries, currentDir) } ?: emptyList()
    }

    // 拿到密码后自动续开刚才被拒的条目
    LaunchedEffect(password, info) {
        val pending = pendingEntry ?: return@LaunchedEffect
        if (info != null) {
            pendingEntry = null
            openEntry(pending)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                Text(
                    text = archiveName,
                    style = AuroraTextStyles.title4,
                    fontWeight = FontWeight.Bold,
                    color = AuroraTokens.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
                )

                // 摘要：格式 · 条目数 · 越界剔除 · 可写性
                val summary = remember(info) {
                    val i = info ?: return@remember ""
                    buildString {
                        append(containerLabel(i.kind))
                        append(" · 共 ").append(i.entries.size).append(" 项")
                        if (i.skippedUnsafe > 0) {
                            append(" · 已忽略 ").append(i.skippedUnsafe).append(" 个越界条目")
                        }
                        append(if (i.writeSupport == ArchiveBrowser.WriteSupport.ZIP_REWRITE) " · 可写回" else " · 只读")
                    }
                }
                if (summary.isNotEmpty()) {
                    Text(
                        text = summary,
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                }
                val blocked = info?.writeBlockedReason
                if (blocked != null) {
                    Text(
                        text = blocked,
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.Warning,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // 面包屑 / 上退
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "‹ 上一级",
                        style = AuroraTextStyles.footnote1,
                        fontSize = 13.sp,
                        color = if (currentDir.isEmpty()) AuroraTokens.TextDisabled else AuroraTokens.Accent,
                        modifier = Modifier
                            .clickable(enabled = currentDir.isNotEmpty()) {
                                currentDir = ArchiveBrowser.parentOf(currentDir)
                            }
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                    )
                    Text(
                        text = if (currentDir.isEmpty()) "/" else "/$currentDir",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 10.dp)
                    )
                    if (currentDir.isNotEmpty()) {
                        Text(
                            text = "根目录",
                            style = AuroraTextStyles.footnote2,
                            color = AuroraTokens.Accent,
                            modifier = Modifier
                                .clickable { currentDir = "" }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        )
                    }
                }

                // 状态行：写回 / 加载 / 错误 / 交互提示
                val status = when {
                    archiveWriteInFlight -> archiveWriteStatus
                    archiveWriteStatus != null -> archiveWriteStatus
                    loading -> "正在读取压缩包…"
                    error != null -> error
                    needPassword -> "该压缩包已加密，请输入密码"
                    openingEntry != null -> "正在打开 ${openingEntry?.substringAfterLast('/')}…"
                    message != null -> message
                    else -> null
                }
                Text(
                    text = status ?: "",
                    style = AuroraTextStyles.footnote2,
                    color = when {
                        error != null -> AuroraTokens.Error
                        message != null || archiveWriteStatus?.startsWith("写回失败") == true -> AuroraTokens.Error
                        else -> AuroraTokens.TextSecondary
                    },
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                // 密码面板（7z 头加密 / 加密条目）
                if (needPassword || pendingEntry != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("密码") },
                            singleLine = true,
                            colors = auroraTextFieldColors(),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(0.dp))
                        )
                        Button(
                            onClick = { password = passwordInput },
                            enabled = passwordInput.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AuroraTokens.Accent,
                                contentColor = AuroraTokens.OnAccent
                            ),
                            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(0.dp))
                        ) {
                            Text("解锁", fontSize = 13.sp)
                        }
                    }
                }

                if (info != null && visible.isEmpty() && !loading) {
                    Text(
                        text = "此目录为空",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(visible, key = { it.path }) { entry ->
                        val childItem = remember(entry.path) {
                            FileItem(
                                entry.name, entry.path, entry.isDirectory,
                                if (entry.size > 0) entry.size else 0L
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = openingEntry == null) { openEntry(entry) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    entry.isDirectory -> "📁"
                                    childItem.isEditableText -> "TXT"
                                    childItem.isViewableImage -> "IMG"
                                    else -> "📄"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    entry.isDirectory -> AuroraTokens.Accent
                                    childItem.isEditableText -> AuroraTokens.GlowBlue
                                    childItem.isViewableImage -> AuroraTokens.AccentViolet
                                    else -> AuroraTokens.TextSecondary
                                },
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = AuroraTextStyles.body1,
                                    color = AuroraTokens.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (entry.isDirectory) "文件夹" else sizeLabel(entry),
                                    style = AuroraTextStyles.footnote2,
                                    color = AuroraTokens.TextSecondary,
                                    maxLines = 1
                                )
                            }
                            // 行尾先给结论：只读格式的文本写「只读」，点之前就知道存不回去。
                            Text(
                                text = when {
                                    entry.isDirectory -> "›"
                                    childItem.isEditableText && !writable -> "只读"
                                    else -> "打开"
                                },
                                style = AuroraTextStyles.footnote1,
                                fontSize = 13.sp,
                                color = when {
                                    entry.isDirectory -> AuroraTokens.TextSecondary
                                    childItem.isEditableText && !writable -> AuroraTokens.TextSecondary
                                    else -> AuroraTokens.Accent
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = onDismiss,
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

    // 编辑器 / 图片查看器：与浏览弹窗并列的兄弟 Dialog（不嵌套窗口）
    val session = editSession
    if (session != null) {
        TextEditorDialog(
            filePath = session.cachePath,
            onDismissRequest = {
                editSession = null
                commitEdit(session)
            }
        )
    }

    val img = imagePath
    if (img != null) {
        ImageViewerDialog(
            images = listOf(img),
            initialIndex = 0,
            onDismiss = {
                imagePath = null
                runCatching { File(img).delete() }
                File(img).parentFile?.let { d ->
                    runCatching { if (d.listFiles()?.isEmpty() == true) d.delete() }
                }
            }
        )
    }
}

private fun containerLabel(kind: ArchiveExtractor.ContainerKind): String = when (kind) {
    ArchiveExtractor.ContainerKind.ZIP -> "ZIP"
    ArchiveExtractor.ContainerKind.SEVENZ -> "7Z"
    ArchiveExtractor.ContainerKind.TAR -> "TAR"
    ArchiveExtractor.ContainerKind.SINGLE -> "单文件压缩"
}

private fun sizeLabel(entry: ArchiveEntry): String =
    if (entry.size < 0L) "大小未知"
    else FileItem(entry.name, entry.path, false, entry.size).formattedSize
