// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.ApkInstaller
import com.mixradio.droid.data.ArchiveExtractor
import com.mixradio.droid.data.ExternalMode
import com.mixradio.droid.data.ExternalAction
import com.mixradio.droid.data.ExternalOpen
import com.mixradio.droid.data.ExternalOpenHub
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.FileItemSaver
import com.mixradio.droid.data.FilePermissionMetadata
import com.mixradio.droid.data.decideExternalAction
import com.mixradio.droid.data.INTERNAL_STORAGE_LABEL
import com.mixradio.droid.data.INTERNAL_STORAGE_PATH
import com.mixradio.droid.data.MoveDestinationConflict
import com.mixradio.droid.data.computeSha256Strict
import com.mixradio.droid.data.syntax.SyntaxPackTags
import com.mixradio.droid.data.RootFileManager
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.security.CommandSource
import com.mixradio.droid.data.security.RiskLevel
import com.mixradio.droid.data.security.AuditVerdict
import com.mixradio.droid.data.security.SecurityAuditLog
import com.mixradio.droid.data.displayPath
import com.mixradio.droid.ui.components.ApkExtractDialog
import com.mixradio.droid.ui.components.BookmarksDialog
import com.mixradio.droid.ui.components.BuiltInFilePicker
import com.mixradio.droid.ui.components.ExecuteConfirmDialog
import com.mixradio.droid.ui.components.FileListSettingsDialog
import com.mixradio.droid.ui.components.FilePermissionDialog
import com.mixradio.droid.ui.components.FileShortcutButton
import com.mixradio.droid.ui.components.ImageViewerDialog
import com.mixradio.droid.ui.components.InstallConfirmDialog
import com.mixradio.droid.ui.components.TextEditorDialog
import com.mixradio.droid.ui.components.applyFileViewSettings
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilePage(
    appSettings: AppSettings,
    onExecuteFileAndNavigate: (path: String, runAsRoot: Boolean?, riskApproved: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * 批量删除的执行 scope：**不随组合销毁**。
     *
     * 用页面级 `rememberCoroutineScope()` 时，旋屏/Activity 重建会取消正在跑的循环，
     * 而 `RootFileManager.delete` 每一项各自 `withContext(IO)`，取消只在**下一项入口**
     * 才被观察 —— 于是删到第 k 个即静默停止，`feedbackMessage` 与 `refresh()` 永不执行，
     * 列表仍显示已删文件，用户既不知道删到哪、也不知道重跑会把已删项计为失败。
     */
    val batchScope = rememberCoroutineScope()
    // 批量任务开始时冻结的目录：执行期间用户切目录/被外部唤起改写目录，
    // 删除仍会继续落在旧目录，而收尾 refresh 的是新目录 —— 用户全程看不见删了什么。
    val batchDirGuard = remember { arrayOf<String?>(null) }

    // 外部唤起（「打开方式 / 分享」）待处理目标：由 ExternalOpenHub 投递。
    // 直接订阅单槽位，定位完成后由 Hub 清空；配置变更不会重投（MainActivity 侧已守卫）。
    val externalTarget = ExternalOpenHub.pending

    // 本次目录跳转是否由外部 Intent 驱动：refresh() 据此决定要不要写「上次浏览目录」。
    val pendingExternalDirectory = remember { booleanArrayOf(false) }

    // 记忆操作路径：开启时沿用进程内记住的上次目录（无效则回退初始目录），关闭时恒为初始目录
    val initialDirectory = if (appSettings.rememberDirectory) {
        RootFileManager.rememberedDirectory ?: INTERNAL_STORAGE_PATH
    } else {
        INTERNAL_STORAGE_PATH
    }
    var currentDirectory by remember { mutableStateOf(initialDirectory) }
    var fileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    // 过滤+排序后的展示列表：在 Dispatchers.Default 计算后写入，组合期不再做 O(N log N) 排序
    var displayFileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    // 名称过滤（搜索）：只过滤当前目录的展示列表，切目录即清空；
    // 过滤与排序在同一次后台遍历里完成（见 applyFileViewSettings(nameQuery)）。
    var showSearch by remember { mutableStateOf(false) }
    var nameQuery by remember { mutableStateOf("") }
    var directoryLoadFailed by remember { mutableStateOf(false) }

    // 执行确认：点击「执行」先暂存待执行文件，弹窗确认后再真正执行。
    // 用 rememberSaveable：确认框属用户显式意图，旋转/分屏后不应被静默丢弃
    // （终端页的待确认命令同样用 rememberSaveable，此处对齐）。
    var pendingExecuteItem by rememberSaveable(stateSaver = FileItemSaver) { mutableStateOf<FileItem?>(null) }
    // 安装确认：安装走 pm install 静默完成，必须经用户确认；同样跨重建保留
    var pendingInstallItem by rememberSaveable(stateSaver = FileItemSaver) { mutableStateOf<FileItem?>(null) }

    var selectedItem by remember { mutableStateOf<FileItem?>(null) }
    // 各弹窗的**目标快照**。不能一律回读共享的 selectedItem：
    // 外部唤起（singleTask → onNewIntent）会无条件改写该槽位，而 show*Dialog 标志不会被清，
    // 于是弹窗先被隐藏、用户下次点任意文件又以新目标重现 —— 标题/预填值是旧文件，
    // 确认时作用在另一个文件上（删除、改名、chmod/chown 均不可撤销）。
    var renameTargetItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteTargetItem by remember { mutableStateOf<FileItem?>(null) }
    var permissionTargetItem by remember { mutableStateOf<FileItem?>(null) }
    var modeTargetItem by remember { mutableStateOf<FileItem?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    // 图片浏览弹窗的宿主开关与目标项必须 rememberSaveable：
    // 旋转/分屏时 Activity 重建会让普通 remember 回到初值，弹窗被静默关掉。
    // 对文本编辑器而言这是**数据丢失**：编辑器内部的 rememberSaveable 状态随
    // composable 一起被卸载重建，连同未落盘的改动一起消失，用户毫不知情。
    var showImageViewerDialog by rememberSaveable { mutableStateOf(false) }
    var viewerImageList by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var viewerImageIndex by rememberSaveable { mutableIntStateOf(0) }
    var showTextEditorDialog by rememberSaveable { mutableStateOf(false) }
    var viewerTargetItem by rememberSaveable(stateSaver = FileItemSaver) { mutableStateOf<FileItem?>(null) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    /** 批量删除待确认的路径快照：确认框打开后不再随选择集变化。 */
    var batchDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 批量任务进行中：屏蔽并发触发与目录切换（见 batchScope 的说明）。 */
    var isBatchRunning by remember { mutableStateOf(false) }

    var showJumpPathDialog by remember { mutableStateOf(false) }
    var jumpPathInput by remember { mutableStateOf("") }

    var showFontPreviewDialog by remember { mutableStateOf(false) }
    var previewFontItem by remember { mutableStateOf<FileItem?>(null) }

    var showFileSettingsDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }

    // 自动解压：密码输入弹窗状态
    var showExtractPasswordDialog by remember { mutableStateOf(false) }
    var extractPasswordInput by remember { mutableStateOf("") }
    var extractTargetItem by remember { mutableStateOf<FileItem?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var isCopying by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var moveFiles by remember { mutableStateOf<List<String>?>(null) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var conflictTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var conflictDestination by remember { mutableStateOf("") }
    var isMoving by remember { mutableStateOf(false) }

    // 多选模式状态：进入后单击文件=切换选中（仅文件，文件夹不参与）；长按文件弹批量菜单
    var multiSelectMode by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateListOf<String>() }
    var showModeDialog by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showBatchRenameDialog by remember { mutableStateOf(false) }
    var batchRenameInput by remember { mutableStateOf("") }

    // 按目录重建 LazyListState：切目录即天然回到顶部（新状态初始位置为 0），无需调用可挂起的
    // `scrollToItem(0)`——那样会阻塞 LaunchedEffect（列表尚未组合时该调用会一直挂起），
    // 使紧随其后的 refresh() 永不执行，表现为「进入目录后一直空白、点刷新才出来」。
    val listState = remember(currentDirectory) { LazyListState() }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var installStatusMessage by remember { mutableStateOf<String?>(null) }
    var installAlertMessage by remember { mutableStateOf<String?>(null) }

    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var newFileExt by remember { mutableStateOf("") }
    // 「提取 APK」弹窗（设置菜单入口）
    var showExtractApkDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionMetadata by remember { mutableStateOf<FilePermissionMetadata?>(null) }

    // 刷新代次（非 Compose 状态，赋值不触发重组）：每发起一次刷新 +1，用于作废更早的刷新结果。
    val refreshGenRef = remember { intArrayOf(0) }
    // 刷新任务引用：切目录时取消上一次，省掉无谓的 root 列目录开销。
    val refreshJobRef = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }

    /**
     * 当前目录是否可作为解压目标（null = 未测或正在测）。
     *
     * `ArchiveExtractor.canExtractTo` 是阻塞的文件系统探测，放在组合期（`remember`）
     * 会在每次切目录时于主线程执行一次。改为异步测量：未测出前不禁用入口
     * （null 视为可写），测出不可写才禁用并说明原因。
     */
    var extractTargetWritable by remember { mutableStateOf<Boolean?>(null) }

    fun refreshExtractTargetWritable() {
        val dir = currentDirectory
        extractTargetWritable = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ArchiveExtractor.canExtractTo(dir) }
            // 目录已切走则丢弃本次结果，避免用旧目录的可写性误禁新目录的入口
            if (dir == currentDirectory) extractTargetWritable = ok
        }
    }

    /**
     * 统一切目录入口：批量破坏性任务进行中时**拒绝**切换。
     *
     * 批量删除的循环跑在 `batchScope`（不被旋屏取消），期间用户仍可点顶栏快捷键、
     * 点文件夹进入、甚至被 singleTask 的外部 Intent 改写 `currentDirectory`。
     * 删除会继续落在**旧目录**，而收尾 `refresh()` 刷的是新目录 ——
     * Toast 浮在无关列表上，旧目录删了哪些文件用户全程不可见、不可中断、不可撤销。
     */
    fun navigateTo(dir: String) {
        if (isBatchRunning) {
            feedbackMessage = "批量操作进行中，暂不能切换目录"
            return
        }
        currentDirectory = dir
    }

    fun refresh(showToast: Boolean = false) {
        // 记录本次要加载的目录。切目录时旧协程不会被自动取消（它挂在页面级 scope 上，
        // 不是 LaunchedEffect(currentDirectory) 的子协程），若不校验就会用**旧目录的结果覆盖新目录**：
        // 表现是路径栏已是 B、列表却是 A，随后的删除/移动会作用到错误路径（数据风险）。
        val requestedDir = currentDirectory
        val gen = ++refreshGenRef[0]
        refreshJobRef[0]?.cancel()
        isLoading = true
        directoryLoadFailed = false
        refreshJobRef[0] = scope.launch {
            try {
                // 先探测目录是否真实存在（不可用 `fileList.isEmpty()` 判断——合法空目录也返回空列表）
                val exists = RootFileManager.pathExists(requestedDir)
                val loaded = if (exists) {
                    RootFileManager.listFiles(requestedDir)
                } else {
                    emptyList()
                }
                // 过滤 + 排序放在后台线程，避免组合期在主线程做 O(N log N) 排序
                val showHidden = appSettings.showHiddenFiles
                val sortMode = appSettings.fileSortMode
                val display = withContext(Dispatchers.Default) {
                    applyFileViewSettings(loaded, showHidden, sortMode, nameQuery)
                }
                // 已有更新的刷新接替（切目录或重复刷新）：本次结果过期，直接丢弃。
                if (gen != refreshGenRef[0]) return@launch
                // 两个状态之间没有挂起点：只产生一次重组，不会出现「新目录列表 + 旧排序结果」的中间帧
                fileList = loaded
                displayFileList = display
                if (!exists) {
                    // 记忆的目录已失效（被删除/不可达）：随后回退初始目录
                    directoryLoadFailed = true
                } else {
                    // 目录加载成功（含合法空目录）：开启记忆时记录为「上次浏览目录」。
                    // 外部 Intent 驱动的跳转**不得**落盘：否则任意应用发一条 Intent
                    // 就能把「上次浏览目录」永久改成它指定的任意路径（进程级持久状态篡改，
                    // 且下次冷启动直接落在那里）。
                    // 标志在本次落盘判定后清空：用户后续的主动导航应恢复正常记忆行为。
                    val skipPersist = pendingExternalDirectory[0]
                    if (skipPersist) pendingExternalDirectory[0] = false
                    if (appSettings.rememberDirectory && !skipPersist) {
                        RootFileManager.rememberedDirectory = requestedDir
                    }
                }
            } catch (_: Exception) {
                if (gen != refreshGenRef[0]) return@launch
                fileList = emptyList()
                displayFileList = emptyList()
            } finally {
                // 仅最新一代收尾：否则被取消的旧刷新会误清新刷新的加载态（cancel 的 finally 异步执行）
                if (gen == refreshGenRef[0]) {
                    isLoading = false
                    // 用户手动点击「刷新」时给出明确反馈，避免「点了没反应」的错觉
                    if (showToast) feedbackMessage = "已刷新"
                }
            }
        }
    }

    // 按指定冲突策略执行冲突目标集移动，完成后退出多选态回到普通浏览
    fun runMoveWithConflict(conflict: MoveDestinationConflict) {
        val targets = conflictTargets
        val destination = conflictDestination
        if (targets.isEmpty() || destination.isEmpty()) return
        conflictTargets = emptyList()
        conflictDestination = ""
        scope.launch {
            isMoving = true
            var ok = 0
            var fail = 0
            targets.forEach { sourcePath ->
                val (success) = RootFileManager.moveFile(sourcePath, destination, onConflict = conflict)
                if (success) ok++ else fail++
            }
            isMoving = false
            feedbackMessage = if (fail == 0) "已移动 $ok 项" else "移动完成：$ok 成功 / $fail 失败"
            selectedPaths.clear()
            multiSelectMode = false
            refresh()
        }
    }

    // 记忆目录失效时自动回退初始目录
    LaunchedEffect(directoryLoadFailed) {
        if (directoryLoadFailed) {
            directoryLoadFailed = false
            currentDirectory = INTERNAL_STORAGE_PATH
            if (appSettings.rememberDirectory) {
                RootFileManager.rememberedDirectory = INTERNAL_STORAGE_PATH
            }
        }
    }

    // 外部唤起目标落点（由 ExternalOpenHub 投递）：跳目录 + 高亮；OPEN 模式按类型分派动作。
    // highlightPath 仅用于视觉定位，切目录或用户点击后清空。
    var highlightPath by remember { mutableStateOf<String?>(null) }

    // ===== 单文件动作体：抽为局部函数，供动作菜单与「外部唤起」共用（不产生第二条执行路径）=====

    // 自动解压到当前目录
    fun startExtract(item: FileItem) {
        scope.launch {
            isExtracting = true
            val result = try {
                ArchiveExtractor.extract(archivePath = item.path, targetParent = currentDirectory)
            } finally {
                isExtracting = false
            }
            when (result) {
                is ArchiveExtractor.ExtractResult.Success ->
                    feedbackMessage = "已解压到: ${result.targetDir}"
                is ArchiveExtractor.ExtractResult.NeedPassword -> {
                    extractTargetItem = item
                    extractPasswordInput = ""
                    showExtractPasswordDialog = true
                }
                is ArchiveExtractor.ExtractResult.Failure ->
                    feedbackMessage = result.message
            }
            refresh()
        }
    }

    fun startInstall(
        item: FileItem,
        installAsRootOverride: Boolean? = null,
        confirmedSha256: String? = null,
        stagedPath: String? = null
    ) {
        scope.launch {
            isInstalling = true
            installAlertMessage = null
            installStatusMessage = "正在安装 ${item.name}，请勿重复操作"
            val installMode = if (installAsRootOverride == true) "root" else "system"
            SecurityAuditLog.log(CommandSource.INTERNAL_APP, AuditVerdict.START, "APK_INSTALL", RiskLevel.WARNING,
                "path=${item.path} | sha256=${confirmedSha256 ?: "unconfirmed"} | mode=$installMode")
            val result = try {
                val installAsRoot = installAsRootOverride ?: (RootService.isRootGranted == true)
                val installPath = stagedPath ?: item.path
                val currentHash = computeSha256Strict(installPath)
                if (currentHash == null || !currentHash.equals(confirmedSha256, ignoreCase = true)) {
                    ApkInstaller.InstallResult.Failure("安装包在确认后发生变化，已取消安装")
                } else if (installAsRoot) {
                    if (item.realExtension == "apk") {
                        // 第三个参数传原始路径：兄弟分片要从原始目录发现，
                        // 而被点的这一个仍用已校验的暂存副本安装。
                        ApkInstaller.installApk(context, installPath, item.path)
                    } else {
                        ApkInstaller.installXapk(context, installPath)
                    }
                } else {
                    if (item.realExtension == "apk") {
                        if (ApkInstaller.collectApkSet(context, item.path).isSplit) {
                            ApkInstaller.InstallResult.Failure(
                                "该应用为分包应用，无 ROOT 时无法整套安装；请将同目录的 base 与 -splitN 文件一并交给 SAI / MT 管理器安装"
                            )
                        } else {
                            ApkInstaller.installApkViaSystem(context, installPath)
                        }
                    } else {
                        ApkInstaller.InstallResult.Failure("XAPK 分片安装需 ROOT 静默权限，请先授权 ROOT")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ApkInstaller.InstallResult.Failure("安装失败: ${e.message ?: "未知错误"}")
            }
            isInstalling = false
            val resultMessage = when (result) {
                is ApkInstaller.InstallResult.Success -> result.message
                is ApkInstaller.InstallResult.Failure -> result.message
            }
            installAlertMessage = when (result) {
                is ApkInstaller.InstallResult.Success -> "安装完成：$resultMessage"
                is ApkInstaller.InstallResult.Failure -> "安装失败：$resultMessage"
            }
            feedbackMessage = resultMessage
            SecurityAuditLog.log(
                CommandSource.INTERNAL_APP,
                if (result is ApkInstaller.InstallResult.Success) AuditVerdict.FINISHED else AuditVerdict.FAILED,
                "APK_INSTALL",
                RiskLevel.WARNING,
                "path=${item.path} | sha256=${confirmedSha256 ?: "unconfirmed"} | mode=$installMode | result=$resultMessage"
            )
            stagedPath?.let { File(it).delete() }
        }
    }

    fun openImageViewer(item: FileItem) {
        // 相册左右滑动列表：按扩展名收集同目录图片。
        // 但目标本身可能**无扩展名**（外部唤起时依 MIME 判定为图片），
        // 它不在 isViewableImage 集合里，必须显式并入，否则列表为空、对话框渲染条件不成立。
        val imageList = buildList {
            displayFileList.filter { it.isViewableImage }.forEach { add(it.path) }
            if (none { it == item.path }) add(item.path)
        }
        val idx = imageList.indexOf(item.path).coerceAtLeast(0)
        viewerImageList = imageList
        viewerImageIndex = idx
        showImageViewerDialog = true
    }

    fun openTextEditor(item: FileItem) {
        viewerTargetItem = item
        showTextEditorDialog = true
    }

    /**
     * 外部唤起（OPEN 模式）按类型启动。有扩展名时判定条件与文件页动作菜单同源；
     * 无扩展名（相册临时文件）时以发送方 MIME 兜底，避免把图片当文本打开。
     * 执行类与安装类都不直接动作，而是弹确认框；无法判定则退回动作菜单。
     */
    fun dispatchExternalOpen(item: FileItem, mimeType: String?) {
        when (decideExternalAction(
            isExtensionless = item.isExtensionlessText,
            isInstallable = item.isInstallable,
            isSupportedExecutable = item.isSupportedExecutable,
            isViewableImage = item.isViewableImage,
            isEditableText = item.isEditableText,
            isArchive = item.isArchive,
            mimeType = mimeType
        )) {
            ExternalAction.INSTALL -> pendingInstallItem = item
            ExternalAction.EXECUTE -> pendingExecuteItem = item
            ExternalAction.VIEW_IMAGE -> openImageViewer(item)
            ExternalAction.EDIT_TEXT -> openTextEditor(item)
            // 外部 OPEN 只定位并弹动作菜单；解压会写入多个文件，必须由用户明确点击。
            ExternalAction.EXTRACT -> {
                selectedItem = item
                showActionDialog = true
            }
            ExternalAction.BROWSE -> {
                selectedItem = item
                showActionDialog = true
            }
        }
    }

    LaunchedEffect(externalTarget) {
        val target = externalTarget ?: return@LaunchedEffect
        // 收件箱副本先给一次提示：文件已复制到 Download/shso，与原位置不同
        if (target.copiedFromExternal) {
            feedbackMessage = "已将文件复制到 ${ExternalOpen.INBOX_DIR}"
        }
        val parent = File(target.path).parent ?: INTERNAL_STORAGE_PATH
        highlightPath = target.path
        // 关闭任何停留的旧弹窗，避免遮挡定位结果。
        // 四个破坏性/改属性弹窗必须连**目标快照**一起清：只清 show 标志的话，
        // 弹窗会因 selectedItem 变 null 而隐藏、但标志仍为 true，用户下次点任意文件
        // 又以新目标重现（标题与预填值还是旧文件），确认时作用在另一个文件上。
        showActionDialog = false
        showRenameDialog = false; renameTargetItem = null
        showDeleteDialog = false; deleteTargetItem = null
        showPermissionDialog = false; permissionTargetItem = null; permissionMetadata = null
        showModeDialog = false; modeTargetItem = null
        selectedItem = null
        multiSelectMode = false
        selectedPaths.clear()
        if (isBatchRunning) {
            // 批量破坏性任务进行中不接受目录改写：删除循环继续落在旧目录，
            // 而收尾刷新的是新目录，用户全程看不见删了什么。
            feedbackMessage = "批量操作进行中，已忽略本次外部定位"
            return@LaunchedEffect
        }
        pendingExternalDirectory[0] = true   // 本次跳转不落盘为「上次浏览目录」
        currentDirectory = parent
        // OPEN 模式按类型立即分派。**不能等目标出现在目录列表里**：
        // 隐藏文件（点开头）默认被列表过滤，收件箱副本也可能尚未列出，
        // 等列表就会永远等不到、整个唤起静默失效。此处直接取单文件属性。
        try {
            if (target.mode == ExternalMode.OPEN) {
                val item = RootFileManager.statFilePath(target.path)
                if (item == null) {
                    feedbackMessage = "无法读取目标文件或路径不合法"
                } else {
                    dispatchExternalOpen(item, target.mimeType)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            feedbackMessage = "打开文件失败：${e.message ?: "未知错误"}"
        } finally {
            ExternalOpenHub.consume(target)
        }
    }

    // 待高亮文件出现在列表且列表已组合后再滚动；用 key 定位而非下标，避免排序/过滤后错位
    LaunchedEffect(highlightPath, displayFileList) {
        val path = highlightPath ?: return@LaunchedEffect
        val index = displayFileList.indexOfFirst { it.path == path }
        if (index >= 0) listState.scrollToItem(index)
    }

    LaunchedEffect(currentDirectory) {
        // 切目录：退出多选、清空选中。
        // 多选态下若保留旧的 selectedPaths，批量删除/拷贝会作用到旧目录里同名路径（用户还看不见）。
        // 滚动位置由上面的 `remember(currentDirectory) { LazyListState() }` 自动归零，无挂起调用。
        multiSelectMode = false
        selectedPaths.clear()
        // 建目录与列目录并行执行，避免阻塞列表首屏加载。
        launch { RootFileManager.ensureShsoDir() }
        refresh()
    }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            feedbackMessage = null
        }
    }

    LaunchedEffect(installAlertMessage) {
        if (installAlertMessage != null) {
            delay(3000)
            installAlertMessage = null
        }
    }

    // 隐藏文件 / 排序偏好变化时后台重算展示列表，无需重新列目录
    LaunchedEffect(appSettings.showHiddenFiles, appSettings.fileSortMode, nameQuery) {
        // 抓取基准代次：refresh() 有 refreshGenRef 守卫，本特效此前**没有任何守卫**，
        // 且抓到的是当时的 fileList。进新目录（refresh 在 IO 中）时立刻改搜索/排序，
        // 就会基于**旧目录**的列表算一遍；若它晚于 refresh 落盘，路径栏是新目录、
        // 列表是旧目录内容，而此后 key 不再变化、永远不会再重算 ——
        // 随后的删除/移动/重命名作用到用户看不见的路径。
        val gen = refreshGenRef[0]
        val source = fileList
        val showHidden = appSettings.showHiddenFiles
        val sortMode = appSettings.fileSortMode
        val query = nameQuery
        val computed = withContext(Dispatchers.Default) {
            applyFileViewSettings(source, showHidden, sortMode, query)
        }
        // 期间目录已被刷新/切换：refresh 会自己算出正确的 displayFileList，丢弃本次
        if (gen != refreshGenRef[0]) return@LaunchedEffect
        displayFileList = computed
    }

    // 切目录清空过滤词：否则新目录会沿用旧关键字，表现为「目录打不开」（实为空结果）
    LaunchedEffect(currentDirectory) {
        if (nameQuery.isNotEmpty()) nameQuery = ""
    }

    val listFontSize = appSettings.fileListFontSize.sp
    val listSecondaryFontSize = (appSettings.fileListFontSize - 5f).coerceAtLeast(8f).sp

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AuroraTokens.Surface)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .clickable(enabled = currentDirectory != "/") {
                            val parent = File(currentDirectory).parent ?: "/"
                            currentDirectory = parent
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回上一级",
                        tint = if (currentDirectory != "/") {
                            AuroraTokens.Text
                        } else {
                            AuroraTokens.TextDisabled
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                FileShortcutButton(
                    label = "data",
                    selected = currentDirectory == "/",
                    onClick = { navigateTo("/") },
                    modifier = Modifier.weight(1f)
                )

                FileShortcutButton(
                    label = INTERNAL_STORAGE_LABEL,
                    selected = currentDirectory == INTERNAL_STORAGE_PATH,
                    onClick = { navigateTo(INTERNAL_STORAGE_PATH) },
                    modifier = Modifier.weight(1f)
                )

                FileShortcutButton(
                    label = "shso",
                    selected = currentDirectory == RootFileManager.DEFAULT_SHSO_DIR,
                    onClick = { navigateTo(RootFileManager.DEFAULT_SHSO_DIR) },
                    modifier = Modifier.weight(1f)
                )

                // 书签：仅图标（星形），点击弹出书签管理弹窗；有书签时高亮为强调色
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .clickable { showBookmarksDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "书签",
                        tint = if (appSettings.bookmarks.isNotEmpty()) {
                            AuroraTokens.Accent
                        } else {
                            AuroraTokens.Text
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .clickable { showFileSettingsDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "文件列表设置",
                        tint = AuroraTokens.Text,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 搜索栏：只过滤当前目录的展示列表，不改变目录本身
            if (showSearch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AuroraTokens.Surface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(AuroraTokens.SurfaceHover)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (nameQuery.isEmpty()) {
                            Text(
                                "输入名称筛选（子串匹配）",
                                style = AuroraTextStyles.footnote1,
                                color = AuroraTokens.TextDisabled
                            )
                        }
                        BasicTextField(
                            value = nameQuery,
                            onValueChange = { nameQuery = it },
                            singleLine = true,
                            textStyle = AuroraTextStyles.body2.copy(color = AuroraTokens.Text),
                            cursorBrush = SolidColor(AuroraTokens.Accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // 匹配数：过滤时即时反馈，避免「列表空了」被误判为目录为空
                    if (nameQuery.isNotEmpty()) {
                        Text(
                            text = displayFileList.size.toString() + " 项",
                            style = AuroraTextStyles.footnote1,
                            color = if (displayFileList.isEmpty()) AuroraTokens.Warning else AuroraTokens.Accent
                        )
                    }
                    Text(
                        text = "✕",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.TextSecondary,
                        modifier = Modifier.clickable {
                            nameQuery = ""
                            showSearch = false
                        }
                    )
                }
            }

            // 路径行：独占一整行，固定可容纳两行文本的高度，点击仍弹「跳转路径」
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clickable {
                        jumpPathInput = currentDirectory
                        showJumpPathDialog = true
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = displayPath(currentDirectory),
                    style = AuroraTextStyles.footnote1,
                    fontFamily = FontFamily.Monospace,
                    color = AuroraTokens.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 路径行与列表区的分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.7.dp)
                    .background(AuroraTokens.SurfaceHover.copy(alpha = 0.6f))
            )

            if (isInstalling) {
                installStatusMessage?.let { message ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(0.dp))
                            .background(AuroraTokens.Accent.copy(alpha = 0.16f))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = message,
                            style = AuroraTextStyles.footnote1,
                            color = AuroraTokens.Accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            installAlertMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = message,
                        style = AuroraTextStyles.footnote1,
                        color = if (message.startsWith("安装失败")) {
                            AuroraTokens.Error
                        } else {
                            AuroraTokens.Text
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 文件列表区（占满）+ 悬浮三按钮：外层 Box 包裹，列表 fillMaxSize 占满。
            // 列表底端必须恰好止于 DockBar 上沿：DockBar 是透明玻璃叠层，若列表继续延伸
            // 到其下方，会透过导航栏看到文件行。Scaffold 的 innerPadding 已承担系统导航条
            // inset，因此列表 Box 只需再预留 DockBar 内容高度 56.dp（不要再加 navigationBarsPadding，
            // 否则与 innerPadding 重复计算，会多出一道系统导航条高度的空白带）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 56.dp)
            ) {
                // 列表容器：占满外层 Box
                Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading && displayFileList.isEmpty()) {
                    // 骨架占位：加载期间先铺出列表轮廓，消除首屏空白观感
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(10) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 18.dp, height = 12.dp)
                                            .clip(RoundedCornerShape(0.dp))
                                            .background(AuroraTokens.SurfaceHover)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.55f)
                                                .height(12.dp)
                                                .clip(RoundedCornerShape(0.dp))
                                                .background(AuroraTokens.SurfaceHover)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.28f)
                                                .height(9.dp)
                                                .clip(RoundedCornerShape(0.dp))
                                                .background(AuroraTokens.SurfaceHover)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp)
                                        .height(0.7.dp)
                                        .background(AuroraTokens.SurfaceHover.copy(alpha = 0.6f))
                                )
                            }
                        }
                    }
                } else if (displayFileList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (nameQuery.isNotEmpty()) "无匹配项：" + nameQuery else "当前目录为空",
                            style = AuroraTextStyles.body2,
                            color = AuroraTokens.TextSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // key 只用唯一的 item.path：混入下标会在删除/排序导致位移时使后续项 key 全变，
                        // LazyColumn 复用失效、整段重建（滚动抖动、状态错位）。path 由 listFiles 去重保证唯一。
                        itemsIndexed(displayFileList, key = { _, item -> item.path }) { _, item ->
                            val isExecutable = item.isExecutableScript || item.isExecutableBinary
                            val isFontFile = !item.isDirectory && (item.name.endsWith(".ttf", ignoreCase = true) || item.name.endsWith(".otf", ignoreCase = true))

                            Column(modifier = Modifier.fillMaxWidth()) {
                                val isSelected = multiSelectMode && selectedPaths.contains(item.path)
                                // 外部唤起定位：命中行加强调色底，便于一眼找到
                                val isHighlighted = item.path == highlightPath
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (isHighlighted) highlightPath = null
                                                if (item.isDirectory) {
                                                    // 文件夹：非多选模式单击进入；多选模式文件夹不参与选择
                                                    if (!multiSelectMode) navigateTo(item.path)
                                                } else {
                                                    if (multiSelectMode) {
                                                        // 多选模式：单击文件 = 切换选中状态
                                                        if (selectedPaths.contains(item.path)) selectedPaths.remove(item.path)
                                                        else selectedPaths.add(item.path)
                                                    } else {
                                                        // 非多选：单击文件弹动作菜单
                                                        selectedItem = item
                                                        showActionDialog = true
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (isHighlighted) highlightPath = null
                                                if (item.isDirectory) {
                                                    // 文件夹长按：始终弹动作菜单
                                                    selectedItem = item
                                                    showActionDialog = true
                                                } else {
                                                    selectedItem = item
                                                    if (multiSelectMode) {
                                                        // 多选模式：长按弹批量操作菜单
                                                        showBatchDialog = true
                                                    } else {
                                                        // 非多选：长按弹 进入/退出多选模式
                                                        showModeDialog = true
                                        modeTargetItem = item
                                                    }
                                                }
                                            }
                                        )
                                        .background(
                                            when {
                                                isSelected -> AuroraTokens.Accent.copy(alpha = 0.16f)
                                                isHighlighted -> AuroraTokens.Accent.copy(alpha = 0.22f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .padding(horizontal = 16.dp, vertical = 0.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                // 类型图标：无底色方框、左右零间隙，直接裸文字
                                // 文本文件优先显示**具体语言**（由已导入语法包决定），无法识别时才用通用 TXT
                                Text(
                                    text = when {
                                        item.isDirectory -> "📁"
                                        item.isExecutableScript -> "SH"
                                        item.isExecutableBinary -> "SO"
                                        item.isInstallable -> "APK"
                                        isFontFile -> if (item.name.endsWith(".otf", ignoreCase = true)) "OTF" else "TTF"
                                        item.isViewableImage -> "IMG"
                                        item.isEditableText -> SyntaxPackTags.tagFor(context, item.name) ?: "TXT"
                                        else -> "📄"
                                    },
                                    fontSize = if (item.isDirectory || (!isExecutable && !isFontFile && !item.isInstallable && !item.isViewableImage && !item.isEditableText)) 16.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        item.isDirectory -> AuroraTokens.Accent
                                        item.isExecutableScript -> AuroraTokens.Accent
                                        item.isExecutableBinary -> AuroraTokens.GlowBlue
                                        item.isInstallable -> AuroraTokens.AccentViolet
                                        isFontFile -> AuroraTokens.AccentViolet
                                        item.isViewableImage -> AuroraTokens.AccentViolet
                                        item.isEditableText -> AuroraTokens.GlowBlue
                                        else -> AuroraTokens.TextSecondary
                                    }
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = AuroraTextStyles.body1,
                                        fontSize = listFontSize,
                                        fontWeight = FontWeight.Normal,
                                        color = AuroraTokens.Text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (item.isDirectory) "文件夹" else item.formattedSize,
                                            style = AuroraTextStyles.footnote2,
                                            fontSize = listSecondaryFontSize,
                                            color = AuroraTokens.TextSecondary
                                        )

                                        if (item.permissions.isNotEmpty()) {
                                            Text(
                                                text = item.permissions,
                                                style = AuroraTextStyles.footnote2,
                                                fontSize = listSecondaryFontSize,
                                                fontFamily = FontFamily.Monospace,
                                                color = AuroraTokens.TextSecondary.copy(0.7f)
                                            )
                                        }
                                    }
                                }

                                // 多选模式选中标记：极光渐变对勾（无底色方块，纯文字）
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        style = AuroraTextStyles.title3.copy(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Black,
                                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                colors = listOf(AuroraTokens.GlowCyan, AuroraTokens.GlowBlue, AuroraTokens.AccentViolet)
                                            )
                                        ),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }

                                if (isExecutable) {
                                    // 「执行」按钮：去掉矩形底，直接裸文字 + 红色加粗（与终端页按钮裸文字化风格一致）
                                    Text(
                                        text = "执行",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AuroraTokens.Error,
                                        modifier = Modifier
                                            .clickable { pendingExecuteItem = item }
                                            .padding(horizontal = 6.dp, vertical = 8.dp)
                                    )
                                } else if (isFontFile) {
                                    Button(
                                        onClick = {
                                            previewFontItem = item
                                            showFontPreviewDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AuroraTokens.Accent,
                                            contentColor = AuroraTokens.OnAccent
                                        ),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                                        modifier = Modifier.clip(RoundedCornerShape(0.dp))
                                    ) {
                                        Text("预览", fontSize = 12.sp)
                                    }
                                }
                                }

                                // inset 分割线：图标已无底色方框，线从行内容起点起
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp)
                                        .height(0.7.dp)
                                        .background(AuroraTokens.SurfaceHover.copy(alpha = 0.6f))
                                )
                            }
                        }
                    }
                }
            }

            // 文件列表底部：全局三按钮（透明背景、极光渐变图标，靠右、距右边 50dp，位于 DockBar 上方）
            // 图标样式复刻「浏览图片」查看器：40sp Black + 青→蓝→紫极光渐变
            // 回到顶部 / 直达底部 / 立即刷新文件列表；整体靠右排列，置于可能出现的「执行」按钮左侧
            val navIconBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                colors = listOf(AuroraTokens.GlowCyan, AuroraTokens.GlowBlue, AuroraTokens.AccentViolet)
            )
            val navIconStyle = AuroraTextStyles.title3.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                brush = navIconBrush
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .zIndex(1f)
                    .padding(bottom = 8.dp, end = 50.dp)
                    .height(60.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val navActions: List<Pair<String, () -> Unit>> = listOf(
                    "⤒" to { scope.launch { if (displayFileList.isNotEmpty()) listState.scrollToItem(0) } },
                    "⤓" to { scope.launch { if (displayFileList.isNotEmpty()) listState.scrollToItem(displayFileList.lastIndex) } },
                    "⟳" to { refresh(showToast = true) }
                )
                navActions.forEach { (sym, action) ->
                    Text(
                        text = sym,
                        style = navIconStyle,
                        modifier = Modifier
                            .clickable(onClick = action)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            // 文件列表右侧贴边细拖动条：拖动快速跳转（复刻文本编辑器 LineScrollBar 样式）
            if (displayFileList.size > 1) {
                ListScrollBar(
                    listState = listState,
                    itemCount = displayFileList.size,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp)
                        .zIndex(1f)
                )
            }
            }
        }
    }

    // 「全选文件」状态：当前是否**已全选所有文件**（不含文件夹）。用于把设置项文案切换为「取消全选」。
    val allFilesSelected = run {
        val filePaths = displayFileList.filter { !it.isDirectory }.map { it.path }
        filePaths.isNotEmpty() && multiSelectMode && selectedPaths.containsAll(filePaths)
    }

    if (showFileSettingsDialog) {
        FileListSettingsDialog(
            appSettings = appSettings,
            onDismissRequest = { showFileSettingsDialog = false },
            onNewFileRequest = {
                // 预填：文件名 = 今天日期到毫秒的纯数字；扩展名 = txt
                val sdf = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US)
                newFileName = sdf.format(Date())
                newFileExt = "txt"
                showFileSettingsDialog = false
                showNewFileDialog = true
            },
            onSelectAllFilesRequest = {
                // 全选**文件**（不含文件夹）：多选模式只针对文件，文件夹不参与选中与批量操作。
                // 第一次点击 = 全选；已全选状态下再点击 = 取消全选（清空选择并退出多选模式）
                val filePaths = displayFileList.filter { !it.isDirectory }.map { it.path }
                showFileSettingsDialog = false
                if (filePaths.isEmpty()) {
                    feedbackMessage = "当前目录没有可全选的文件"
                } else if (multiSelectMode && selectedPaths.containsAll(filePaths)) {
                    selectedPaths.clear()
                    multiSelectMode = false
                    feedbackMessage = "已取消全选（${filePaths.size} 个文件）"
                } else {
                    selectedPaths.clear()
                    selectedPaths.addAll(filePaths)
                    multiSelectMode = true
                    feedbackMessage = "已全选 ${filePaths.size} 个文件（不含文件夹）"
                }
            },
            allFilesSelected = allFilesSelected,
            onSearchRequest = {
                // 搜索入口从文件页顶部移入此处：打开名称过滤栏（仅过滤当前目录）。
                // 关闭弹窗再展开，避免弹窗遮挡搜索栏；保留当前 nameQuery 以便续筛。
                showFileSettingsDialog = false
                showSearch = true
            },
            onExtractApkRequest = {
                showFileSettingsDialog = false
                showExtractApkDialog = true
            }
        )
    }

    if (showExtractApkDialog) {
        ApkExtractDialog(
            onDismissRequest = { showExtractApkDialog = false },
            onResult = { ok, message ->
                feedbackMessage = message
                // 提取产物落在 Download：若当前正浏览该目录则立即刷新出来
                if (ok) refresh()
            }
        )
    }

    if (showBookmarksDialog) {
        BookmarksDialog(
            appSettings = appSettings,
            currentDirectory = currentDirectory,
            onDismissRequest = { showBookmarksDialog = false },
            onNavigate = { path ->
                showBookmarksDialog = false
                currentDirectory = path
            }
        )
    }

    // 纯文字紧凑菜单行：无矩形底色、无圆角，仅文字 + 点击
    @Composable
    fun ActionTextRow(
        label: String,
        color: Color = AuroraTokens.Text,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 11.dp, horizontal = 8.dp)
        ) {
            Text(
                text = label,
                style = AuroraTextStyles.body1,
                color = if (enabled) color else AuroraTokens.TextDisabled
            )
        }
    }

    if (showActionDialog && selectedItem != null) {
        val item = selectedItem!!
        AuroraWindowDialog(
            show = true,
            title = item.name,
            onDismissRequest = { showActionDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // 纯文字紧凑行：无矩形底色、无圆角
                ActionTextRow("添加到shso", AuroraTokens.Accent) {
                    showActionDialog = false
                    scope.launch {
                        val (success, resultPath) = RootFileManager.addFileToShso(
                            sourcePath = item.path,
                            useIndependentFolder = appSettings.useIndependentFolder,
                            autoDeleteSource = appSettings.autoDeleteAfterAdding
                        )
                        if (success) {
                            feedbackMessage = "已添加到 shso: $resultPath"
                            refresh()
                            if (appSettings.autoExecuteAfterAdding && (item.isExecutableScript || item.isExecutableBinary)) {
                                // 自动执行链路：未经确认框，故 riskApproved=false（executeFile 仍会扫描脚本内容），
                                // runAsRoot=null 表示按档位自动（档位 3 默认非 Root）。
                                onExecuteFileAndNavigate(resultPath, null, false)
                            }
                        } else {
                            feedbackMessage = resultPath
                        }
                    }
                }

                // 自动解压：仅已知压缩包显示；所有已知格式均可解压
                if (item.isArchive) {
                    // 解压以应用自身 uid 落盘：受 SELinux 限制的目录（如 `/data/adb/`）
                    // 即便 chmod 777 也可能写不进去。
                    // 先检测可写性，不可写则禁用入口并在标签上说明原因。
                    //
                    // 可写性检测是**阻塞的文件系统调用**，不能放在组合期：
                    // `remember(currentDirectory)` 在每次切目录后都会重跑一次
                    // `File.canWrite()`，大目录/慢存储下就是一次可感知的卡顿。
                    // 改为在进入动作菜单后异步测一次，测完写回状态。
                    LaunchedEffect(currentDirectory) { refreshExtractTargetWritable() }
                    ActionTextRow(
                        label = when {
                            isExtracting -> "正在解压…"
                            extractTargetWritable == false -> "自动解压文件（当前目录不可写）"
                            else -> "自动解压文件"
                        },
                        color = AuroraTokens.Accent,
                        enabled = !isExtracting && extractTargetWritable != false
                    ) {
                        showActionDialog = false
                        startExtract(item)
                    }
                }

                // 安装 APK/XAPK：普通用户即可安装（无 ROOT 走系统安装器），
                // 仅当授权 ROOT 时优先走静默安装；用 realExtension 兼容 .1 尾缀。
                // 统一经安装确认框：ROOT 下 pm install 静默完成，应先让用户确认来源。
                if (item.isInstallable) {
                    ActionTextRow(
                        label = if (isInstalling) "正在安装…" else "安装 APK/XAPK",
                        color = AuroraTokens.Accent,
                        enabled = !isInstalling
                    ) {
                        showActionDialog = false
                        pendingInstallItem = item
                    }
                }

                // 浏览图片：jpg/jpeg/png/bmp/gif/webp/ico/tiff/tif
                if (item.isViewableImage) {
                    ActionTextRow("浏览图片", AuroraTokens.AccentViolet) {
                        showActionDialog = false
                        openImageViewer(item)
                    }
                }

                // 编辑文本：txt/md/json/xml/yaml/conf/properties 等
                if (item.isEditableText) {
                    ActionTextRow("编辑文本", AuroraTokens.AccentViolet) {
                        showActionDialog = false
                        openTextEditor(item)
                    }
                }

                if (RootFileManager.isAllowedDataPath(item.path)) {
                    ActionTextRow("权限/属性", AuroraTokens.Accent) {
                        showActionDialog = false
                        scope.launch {
                            val (metadata, message) = RootFileManager.readPermissionMetadata(item.path)
                            if (metadata == null) {
                                feedbackMessage = message
                            } else {
                    permissionMetadata = metadata
                    permissionTargetItem = item
                    showPermissionDialog = true
                            }
                        }
                    }
                }

                ActionTextRow("重命名", AuroraTokens.Text) {
                showActionDialog = false
                renameInput = item.name
                renameTargetItem = item
                showRenameDialog = true
                }

                // 拷贝：仅文件（文件夹不显示），复制为同级 _n 递增序号副本
                if (!item.isDirectory) {
                    ActionTextRow(if (isCopying) "正在拷贝…" else "原地拷贝", AuroraTokens.Text, enabled = !isCopying) {
                        showActionDialog = false
                        scope.launch {
                            isCopying = true
                            val (success, resultPath) = RootFileManager.copyFile(item.path)
                            isCopying = false
                            if (success) {
                                feedbackMessage = "已拷贝: ${File(resultPath).name}"
                                refresh()
                            } else {
                                feedbackMessage = resultPath
                            }
                        }
                    }
                }

                ActionTextRow("移动文件", AuroraTokens.Accent) {
                    showActionDialog = false
                    moveFiles = listOf(item.path)
                    showMoveDialog = true
                }

                ActionTextRow("删除", AuroraTokens.Error) {
                    showActionDialog = false
                    deleteTargetItem = item
                    showDeleteDialog = true
                }
            }
        }
    }

    if (showMoveDialog && moveFiles != null) {
        BuiltInFilePicker(
            appSettings = appSettings,
            show = true,
            initialDirectory = currentDirectory,
            titleText = "移动文件",
            subtitleText = "选择目标文件夹",
            emptyHint = "当前目录没有子文件夹",
            directoryOnly = true,
            onDismissRequest = {
                if (!isMoving) {
                    showMoveDialog = false
                    moveFiles = null
                }
            },
            onFileSelected = {},
            onDirectorySelected = { destinationDirectory ->
                val targets = moveFiles
                if (targets.isNullOrEmpty()) return@BuiltInFilePicker
                showMoveDialog = false
                moveFiles = null
                scope.launch {
                    isMoving = true
                    // 先探测是否存在同名冲突，有冲突则弹出决策框
                    val conflicted = targets.any {
                        RootFileManager.moveDestinationCollides(it, destinationDirectory)
                    }
                    if (conflicted) {
                        conflictTargets = targets
                        conflictDestination = destinationDirectory
                        isMoving = false
                        showConflictDialog = true
                    } else {
                        var ok = 0
                        var fail = 0
                        targets.forEach { sourcePath ->
                            val (success) = RootFileManager.moveFile(sourcePath, destinationDirectory)
                            if (success) ok++ else fail++
                        }
                        isMoving = false
                        feedbackMessage = if (fail == 0) "已移动 $ok 项" else "移动完成：$ok 成功 / $fail 失败"
                        // 移动完成后退出多选态并回到普通浏览
                        selectedPaths.clear()
                        multiSelectMode = false
                        refresh()
                    }
                }
            }
        )
    }

    if (showConflictDialog) {
        AuroraWindowDialog(
            show = true,
            title = "目标存在同名项",
            summary = "目标文件夹中存在同名文件或文件夹，请选择处理方式",
            onDismissRequest = { showConflictDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                ActionTextRow("覆盖替换", AuroraTokens.Error) {
                    showConflictDialog = false
                    runMoveWithConflict(MoveDestinationConflict.OVERWRITE)
                }
                ActionTextRow("自动改名", AuroraTokens.Accent) {
                    showConflictDialog = false
                    runMoveWithConflict(MoveDestinationConflict.RENAME)
                }
                ActionTextRow("同名不动", AuroraTokens.Text) {
                    showConflictDialog = false
                    runMoveWithConflict(MoveDestinationConflict.SKIP)
                }
                ActionTextRow("直接退出", AuroraTokens.TextDisabled) {
                    showConflictDialog = false
                    // 放弃移动，只退出多选态回到普通浏览
                    conflictTargets = emptyList()
                    conflictDestination = ""
                    selectedPaths.clear()
                    multiSelectMode = false
                    feedbackMessage = "已放弃移动"
                }
            }
        }
    }

    if (showRenameDialog && renameTargetItem != null) {
        val item = renameTargetItem!!
        AuroraWindowDialog(
            show = true,
            title = "重命名",
            onDismissRequest = { showRenameDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("输入新名称") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showRenameDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = renameInput.isNotBlank() && renameInput != item.name,
                        onClick = {
                            val targetName = renameInput.trim()
                            showRenameDialog = false
                            scope.launch {
                                val (success, message) = RootFileManager.rename(item.path, targetName)
                                feedbackMessage = if (success) "重命名成功" else "重命名失败: $message"
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("确认")
                    }
                }
            }
        }
    }

    if (showDeleteDialog && (deleteTargetItem != null || batchDeletePaths.isNotEmpty())) {
        val single = deleteTargetItem
        val batchPaths = batchDeletePaths
        val isBatch = batchPaths.isNotEmpty()
        AuroraWindowDialog(
            show = true,
            title = if (isBatch) "确认批量删除" else "确认删除",
            summary = if (isBatch) {
                "确定要删除选中的 ${batchPaths.size} 个项目吗？此操作无法撤销。"
            } else {
                "您确定要删除 \"${single?.name}\" 吗？此操作无法撤销。"
            },
            onDismissRequest = { showDeleteDialog = false; batchDeletePaths = emptyList() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { showDeleteDialog = false; batchDeletePaths = emptyList() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.SurfaceHover,
                        contentColor = AuroraTokens.Text
                    )
                ) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        showDeleteDialog = false
                        batchDeletePaths = emptyList()
                        deleteTargetItem = null
                        if (isBatch) {
                            isBatchRunning = true
                            batchDirGuard[0] = currentDirectory
                            batchScope.launch {
                                var ok = 0
                                val failures = ArrayList<String>()
                                try {
                                    batchPaths.forEach { p ->
                                        val (s, reason) = RootFileManager.delete(p)
                                        if (s) ok++ else failures += "${File(p).name}（$reason）"
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) {
                                        isBatchRunning = false
                                        batchDirGuard[0] = null
                                        feedbackMessage = if (failures.isEmpty()) {
                                            "已删除 $ok 个项目"
                                        } else {
                                            "删除完成：$ok 成功 / ${failures.size} 失败：" +
                                                failures.take(2).joinToString("；")
                                        }
                                        selectedPaths.clear()
                                        multiSelectMode = false
                                        refresh()
                                    }
                                }
                            }
                        } else if (single != null) {
                            val target = single
                            scope.launch {
                                val (success, message) = RootFileManager.delete(target.path)
                                feedbackMessage = if (success) "删除成功" else "删除失败: $message"
                                refresh()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.Error,
                        contentColor = Color.White
                    )
                ) {
                    Text("确认删除")
                }
            }
        }
    }

    if (showPermissionDialog && permissionTargetItem != null && permissionMetadata != null) {
        val item = permissionTargetItem!!
        val metadata = permissionMetadata!!

        /**
         * 三步改权限中途失败时把权限位回滚到进弹窗时的值。
         * 属主/用户组不做回滚：用户输入的 uid/组名未必解析得到，且
         * `chmod` 是这里唯一带越权后果的一步（777/666 会让任何人可写）。
         */
        suspend fun rollbackMode(path: String, originalMode: String?, reason: String): Pair<Boolean, String> {
            val original = originalMode?.trim()
            if (original.isNullOrEmpty() || original == "—") {
                return false to "$reason（未能读取原权限以回滚，请手动核对）"
            }
            val rolled = RootFileManager.changePermissions(path, original)
            return if (rolled.first) {
                false to "$reason；已回滚权限为 $original"
            } else {
                false to "$reason；回滚到 $original 也失败：${rolled.second}"
            }
        }

        FilePermissionDialog(
            show = true,
            path = item.path,
            initialMode = metadata.mode,
            initialOwner = metadata.owner,
            initialGroup = metadata.group,
            onDismiss = {
                showPermissionDialog = false
                permissionMetadata = null
            },
            onSubmitSuccess = {
                feedbackMessage = "文件属性已更新"
                refresh()
            },
            onSubmit = { mode, owner, group ->
                // 三步是「部分成功」语义：chmod 落盘后 chown 失败，磁盘上已是新权限，
                // 但返回值只带最后一步的错误。此前弹窗提示「保存失败」并留在原地，
                // 权限却已改完 —— 最坏是用户想把 777 收紧为 700，chgrp 失败后文件仍是 777
                // 而用户以为已锁死。任一步失败即回滚到进弹窗时读到的原始 mode，
                // 并把已执行/未执行的部分说清楚。
                // 取组合期捕获的 metadata（进弹窗时的真实权限），而不是提交时再读
                // permissionMetadata —— 用户在提交途中关掉弹窗会把那个 state 置 null。
                val originalMode = metadata.mode
                val permResult = RootFileManager.changePermissions(item.path, mode)
                if (!permResult.first) return@FilePermissionDialog permResult

                val ownerResult = RootFileManager.changeOwner(item.path, owner)
                if (!ownerResult.first) {
                    return@FilePermissionDialog rollbackMode(
                        item.path, originalMode,
                        "权限已改为 $mode，但属主修改失败：${ownerResult.second}"
                    )
                }
                val groupResult = RootFileManager.changeGroup(item.path, group)
                if (!groupResult.first) {
                    return@FilePermissionDialog rollbackMode(
                        item.path, originalMode,
                        "权限与属主已改（$mode / $owner），但用户组修改失败：${groupResult.second}"
                    )
                }
                groupResult
            }
        )
    }

    if (showJumpPathDialog) {
        AuroraWindowDialog(
            show = true,
            title = "跳转路径",
            summary = "请输入要跳转的目标文件夹绝对路径：",
            onDismissRequest = { showJumpPathDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = jumpPathInput,
                    onValueChange = { jumpPathInput = it },
                    label = { Text("路径（例如 /data/adb/modules）") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showJumpPathDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = jumpPathInput.isNotBlank(),
                        onClick = {
                            var targetPath = jumpPathInput.trim()
                            if (!targetPath.startsWith("/")) {
                                targetPath = "/$targetPath"
                            }
                            showJumpPathDialog = false
                            currentDirectory = targetPath
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("跳转")
                    }
                }
            }
        }
    }

    if (showFontPreviewDialog && previewFontItem != null) {
        val targetItem = previewFontItem!!
        val targetFontFamily = remember(targetItem.path) {
            try {
                FontFamily(android.graphics.Typeface.createFromFile(File(targetItem.path)))
            } catch (_: Exception) {
                FontFamily.Default
            }
        }
        var customTestText by remember { mutableStateOf("") }

        AuroraWindowDialog(
            show = true,
            title = "字体预览",
            summary = "${targetItem.name} (${targetItem.formattedSize})",
            onDismissRequest = { showFontPreviewDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                        .background(AuroraTokens.SurfaceHover)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (customTestText.isNotEmpty()) customTestText else "shso 任务调度引擎",
                            fontFamily = targetFontFamily,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuroraTokens.Text
                        )
                        Text(
                            text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz 0123456789",
                            fontFamily = targetFontFamily,
                            fontSize = 12.sp,
                            color = AuroraTokens.Text
                        )
                        Text(
                            text = "天地玄黄 宇宙洪荒 日月盈昃 辰宿列张\n极速流式任务调度 高并发内核增强",
                            fontFamily = targetFontFamily,
                            fontSize = 12.sp,
                            color = AuroraTokens.TextSecondary
                        )
                    }
                }

                TextField(
                    value = customTestText,
                    onValueChange = { customTestText = it },
                    label = { Text("输入任意文字实时预览效果...") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            try {
                                val destFile = File(context.filesDir, "custom_app_font.ttf")
                                File(targetItem.path).copyTo(destFile, overwrite = true)
                                appSettings.setCustomFont(destFile.absolutePath, targetItem.name)
                                Toast.makeText(context, "已成功应用为软件字体: ${targetItem.name}", Toast.LENGTH_LONG).show()
                                showFontPreviewDialog = false
                            } catch (e: Exception) {
                                Toast.makeText(context, "应用字体失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("应用为软件字体", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { showFontPreviewDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("关闭", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // 解压密码输入弹窗：压缩包检测到加密时弹出
    if (showExtractPasswordDialog && extractTargetItem != null) {
        val targetItem = extractTargetItem!!
        AuroraWindowDialog(
            show = true,
            title = "输入解压密码",
            summary = "压缩包「${targetItem.name}」已加密，请输入密码后继续解压：",
            onDismissRequest = { showExtractPasswordDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    value = extractPasswordInput,
                    onValueChange = { extractPasswordInput = it },
                    label = { Text("密码") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showExtractPasswordDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = extractPasswordInput.isNotBlank(),
                        onClick = {
                            val pwd = extractPasswordInput
                            showExtractPasswordDialog = false
                            scope.launch {
                                isExtracting = true
                                val result = ArchiveExtractor.extract(
                                    archivePath = targetItem.path,
                                    targetParent = currentDirectory,
                                    password = pwd
                                )
                                isExtracting = false
                                when (result) {
                                    is ArchiveExtractor.ExtractResult.Success ->
                                        feedbackMessage = "已解压到: ${result.targetDir}"
                                    is ArchiveExtractor.ExtractResult.NeedPassword ->
                                        feedbackMessage = "该压缩包需要密码"
                                    is ArchiveExtractor.ExtractResult.Failure ->
                                        feedbackMessage = result.message
                                }
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("解压")
                    }
                }
            }
        }
    }

    // 图片浏览弹窗（伪全屏，支持上一张/下一张/旋转）
    if (showImageViewerDialog && viewerImageList.isNotEmpty()) {
        ImageViewerDialog(
            images = viewerImageList,
            initialIndex = viewerImageIndex,
            onDismiss = {
                showImageViewerDialog = false
                viewerImageList = emptyList()
                viewerImageIndex = 0
            }
        )
    }

    // 文本编辑弹窗
    if (showTextEditorDialog && viewerTargetItem != null) {
        TextEditorDialog(
            filePath = viewerTargetItem!!.path,
            onDismissRequest = {
                showTextEditorDialog = false
                viewerTargetItem = null
            }
        )
    }

    // 多选模式：长按文件弹出的「进入/退出多选模式」
    if (showModeDialog && modeTargetItem != null) {
        val item = modeTargetItem!!
        AuroraWindowDialog(
            show = true,
            title = "多选模式",
            onDismissRequest = { showModeDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                ActionTextRow("进入多选模式", AuroraTokens.Accent) {
                    showModeDialog = false
                    multiSelectMode = true
                    if (!selectedPaths.contains(item.path)) selectedPaths.add(item.path)
                }
                ActionTextRow("退出多选模式", AuroraTokens.Text) {
                    showModeDialog = false
                    multiSelectMode = false
                    selectedPaths.clear()
                }
            }
        }
    }

    // 多选模式：批量操作菜单（删除 / 拷贝 / 重命名）
    if (showBatchDialog) {
        AuroraWindowDialog(
            show = true,
            title = "批量操作（已选 ${selectedPaths.size} 项）",
            onDismissRequest = { showBatchDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                ActionTextRow("删除", AuroraTokens.Error) {
                    showBatchDialog = false
                    // 批量删除必须经确认：单文件路径有「此操作无法撤销」确认框，
                    // 批量却一点即逐个 `rm -rf`，误触（多选时手指落点偏移是常事）
                    // 就是不可逆的批量丢失，且无回收站、失败项无明细。
                    batchDeletePaths = selectedPaths.toList()
                    showDeleteDialog = true
                }

                ActionTextRow("原地拷贝", AuroraTokens.Text) {
                    showBatchDialog = false
                    scope.launch {
                        var ok = 0
                        var fail = 0
                        selectedPaths.toList().forEach { p ->
                            val (s) = RootFileManager.copyFile(p)
                            if (s) ok++ else fail++
                        }
                        feedbackMessage = if (fail == 0) "已拷贝 $ok 个文件" else "拷贝完成：$ok 成功 / $fail 失败"
                        selectedPaths.clear()
                        multiSelectMode = false
                        refresh()
                    }
                }

                ActionTextRow("重命名", AuroraTokens.Text) {
                    showBatchDialog = false
                    batchRenameInput = ""
                    showBatchRenameDialog = true
                }

                ActionTextRow("移动文件", AuroraTokens.Accent) {
                    showBatchDialog = false
                    moveFiles = selectedPaths.toList()
                    showMoveDialog = true
                }

                ActionTextRow("退出多选模式", AuroraTokens.Text) {
                    showBatchDialog = false
                    selectedPaths.clear()
                    multiSelectMode = false
                    refresh()
                }
            }
        }
    }

    // 多选批量重命名：统一名称 + _n，保留原扩展名
    if (showBatchRenameDialog) {
        AuroraWindowDialog(
            show = true,
            title = "批量重命名",
            summary = "输入统一名称，将依次命名为 名称_0、名称_1…（保留原扩展名）",
            onDismissRequest = { showBatchRenameDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TextField(
                    value = batchRenameInput,
                    onValueChange = { batchRenameInput = it },
                    label = { Text("统一名称") },
                    singleLine = true,
                    colors = auroraTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(0.dp))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showBatchRenameDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        enabled = batchRenameInput.isNotBlank(),
                        onClick = {
                            val base = batchRenameInput.trim()
                            showBatchRenameDialog = false
                            // 必须按 rename 的真实返回值统计：原实现丢弃返回值，
                            // 无论成功与否都提示「已批量重命名 N 个文件」——
                            // 失败项被静默吞掉，用户以为改完了。
                            val targets = selectedPaths.toList()
                            scope.launch {
                                var ok = 0
                                val failures = ArrayList<String>()
                                targets.forEachIndexed { i, p ->
                                    val file = File(p)
                                    val ext = file.extension
                                    val suffix = if (ext.isNotEmpty()) ".$ext" else ""
                                    val (success, reason) =
                                        RootFileManager.rename(p, "${base}_$i$suffix")
                                    if (success) ok++ else failures += "${file.name}（$reason）"
                                }
                                feedbackMessage = if (failures.isEmpty()) {
                                    "已批量重命名 $ok 个文件"
                                } else {
                                    "已重命名 $ok 个，失败 ${failures.size} 个：${failures.take(2).joinToString("；")}"
                                }
                                selectedPaths.clear()
                                multiSelectMode = false
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("确认")
                    }
                }
            }
        }
    }

    if (showNewFileDialog) {
        AuroraWindowDialog(
            show = true,
            title = "新建文件",
            onDismissRequest = { showNewFileDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        colors = auroraTextFieldColors(),
                        modifier = Modifier
                            .weight(2f)
                            .clip(RoundedCornerShape(0.dp))
                    )
                    TextField(
                        value = newFileExt,
                        onValueChange = { newFileExt = it },
                        label = { Text("扩展名") },
                        singleLine = true,
                        colors = auroraTextFieldColors(),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(0.dp))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showNewFileDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val name = newFileName.trim()
                            val ext = newFileExt.trim()
                            if (name.isEmpty()) {
                                feedbackMessage = "文件名不能为空"
                                return@Button
                            }
                            val finalName = if (ext.isNotEmpty()) "$name.$ext" else name
                            showNewFileDialog = false
                            scope.launch {
                                val (ok, msg) = RootFileManager.createEmptyFile(currentDirectory, finalName)
                                feedbackMessage = if (ok) "已创建: $finalName" else msg
                                if (ok) refresh()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.Accent,
                            contentColor = AuroraTokens.OnAccent
                        )
                    ) {
                        Text("创建")
                    }
                }
            }
        }
    }

    // ===== 执行确认弹窗：任何「执行」点击都必须先经风险确认 =====
    ExecuteConfirmDialog(
        show = pendingExecuteItem != null,
        fileItem = pendingExecuteItem,
        // 批次6 修复：实参传当前档位，之前默认 STANDARD=2 导致档位 0/1 仍扫描 + 档位 3 不显示默认非 Root
        securityLevel = RootService.currentSecurityLevel(),
        onDismiss = { pendingExecuteItem = null },
        onConfirm = { runAsRoot ->
            // 关键：把确认框里用户的实际选择（是否以 Root 执行）与「已获风险确认」一并透传，
            // 否则档位 3 的「脚本默认非 Root + 用户可勾选以 Root」永远不会生效（死代码）。
            val target = pendingExecuteItem?.path
            pendingExecuteItem = null
            if (target != null) onExecuteFileAndNavigate(target, runAsRoot, true)
        }
    )

    // ===== 安装确认弹窗：ROOT 下 pm install 静默完成，必须显式确认后安装 =====
    //
    // ROOT 状态经 `LaunchedEffect` 落到本地 state，而不是在组合期直接读
    // `RootService.isRootGranted`（那是全局 mutableStateOf）：直接读会让
    // 「用户授予/撤销 ROOT」这一变化触发本文件两千多行的整页重组。
    // 语义不变 —— 确认框弹出前会先 probe 一次，见下。
    var rootGrantedForInstall by remember { mutableStateOf(RootService.isRootGranted == true) }
    LaunchedEffect(pendingInstallItem) {
        if (pendingInstallItem == null) return@LaunchedEffect
        rootGrantedForInstall = withContext(Dispatchers.IO) {
            RootService.isRootGranted == true || RootService.checkRoot()
        }
    }
    InstallConfirmDialog(
        show = pendingInstallItem != null,
        fileItem = pendingInstallItem,
        willInstallAsRoot = rootGrantedForInstall,
        onDismiss = { pendingInstallItem = null },
        onConfirm = { confirmedSha256, installAsRoot, stagedPath ->
            val target = pendingInstallItem
            pendingInstallItem = null
            if (target != null) startInstall(target, installAsRoot, confirmedSha256, stagedPath)
            else File(stagedPath).delete()
        }
    )
}

/**
 * 文件列表右侧贴边细拖动条（复刻文本编辑器 [com.mixradio.droid.ui.components.TextEditorDialog]
 * 中 LineScrollBar 的样式）：拖动可快速跳转到目标位置。
 * 轨道细（6dp）、半透明描边底；拇指为 48dp 高的 Accent 色块；拖动时左侧浮出「当前位置 / 总数」。
 * 通过 [listState] 与上层列表双向绑定（不依赖系统滚动条，视觉风格统一）。
 */
@Composable
private fun ListScrollBar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    if (itemCount <= 1) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val thumbHPx = with(density) { 48.dp.toPx() }
    var trackHeight by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val itemCountState = rememberUpdatedState(itemCount)

    // 首可见项下标随滚动高频变化，用 derivedStateOf 隔离组合主体读取
    val firstVisibleIndex by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    val frac = if (dragging) dragFrac else (
        firstVisibleIndex.toFloat() / (itemCount - 1).coerceAtLeast(1)
    ).coerceIn(0f, 1f)
    val maxTop = (trackHeight - thumbHPx).coerceAtLeast(0f)
    val topPx = (frac * maxTop).coerceIn(0f, maxTop)

    Box(
        modifier = modifier
            .width(6.dp)
            .fillMaxHeight()
            .background(AuroraTokens.Stroke.copy(alpha = 0.35f))
            .onGloballyPositioned { trackHeight = it.size.height }
            .pointerInput(trackHeight) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val thumbOffset = ((offset.y - thumbHPx / 2f).coerceIn(0f, maxTop)) / maxTop.coerceAtLeast(1f)
                        dragFrac = thumbOffset
                        scope.launch {
                            listState.scrollToItem((thumbOffset * (itemCount - 1)).toInt().coerceAtLeast(0))
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val thumbOffset = ((change.position.y - thumbHPx / 2f).coerceIn(0f, maxTop)) / maxTop.coerceAtLeast(1f)
                        dragFrac = thumbOffset
                        scope.launch {
                            listState.scrollToItem((thumbOffset * (itemCount - 1)).toInt().coerceAtLeast(0))
                        }
                    },
                    onDragEnd = { dragging = false }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, topPx.roundToInt()) }
                .width(6.dp)
                .height(48.dp)
                .background(AuroraTokens.Accent.copy(alpha = 0.9f))
        )
        if (dragging) {
            val pos = ((dragFrac * (itemCountState.value - 1)) + 1).roundToInt()
                .coerceIn(1, itemCountState.value)
            Text(
                text = "$pos / ${itemCountState.value}",
                style = AuroraTextStyles.footnote2,
                color = AuroraTokens.Text,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            with(density) { (-56).dp.toPx() }.roundToInt(),
                            topPx.roundToInt()
                        )
                    }
                    .background(AuroraTokens.PillBg)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}
