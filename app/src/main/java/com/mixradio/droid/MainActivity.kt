// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.ExternalOpen
import com.mixradio.droid.data.ExternalOpenHub
import com.mixradio.droid.data.ExternalRequest
import com.mixradio.droid.data.ExternalRequestParser
import com.mixradio.droid.data.streamExtraToUri
import com.mixradio.droid.data.PermissionChecker
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.security.GuardModuleInstaller
import com.mixradio.droid.ui.components.DockBar
import com.mixradio.droid.ui.pages.FilePage
import com.mixradio.droid.ui.pages.HomePage
import com.mixradio.droid.ui.pages.SettingsPage
import com.mixradio.droid.ui.pages.TerminalPage
import com.mixradio.droid.ui.theme.AuroraColorScheme
import com.mixradio.droid.ui.theme.AuroraShapes
import com.mixradio.droid.ui.theme.AuroraTypography
import com.mixradio.droid.ui.theme.auroraBackground
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // 外部唤起（「打开方式」/「分享」）的待处理请求。Intent 本身不可比较，
    // 故解析为 ExternalRequest（含 URI 字符串）驱动重组。
    private var externalRequest by mutableStateOf<ExternalRequest?>(null)

    // 已消费标记：配置变更（旋转 / 分屏）会重建 Activity 并重放原始 intent，
    // 若不加守卫，外部唤起会被**重复处理** —— 表现为收件箱反复拷贝同名副本，
    // 以及「旋转后编辑器被原始内容重开、用户未保存的编辑被丢弃」。
    // 用 savedInstanceState 跨重建保持，使一次外部唤起只处理一次。
    private var externalConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 重建（savedInstanceState != null）时不重新解析外部 intent：
        // 那次唤起在首次创建时已处理过，重建只是恢复 UI。
        // onNewIntent 仍可覆盖，故真正的「再次唤起」不受影响。
        externalConsumed = savedInstanceState?.getBoolean(KEY_EXTERNAL_CONSUMED) ?: false
        if (!externalConsumed) {
            externalRequest = parseExternalRequest(intent)
        }

        val appSettings = AppSettings.getInstance(this)
        RootService.initSettings(appSettings)

        setContent {
            // 全局使用 Material 3 主题（极光玻璃配色 + 等宽字体），100% 原生控件。
            // 形态铁律：禁止任何大圆角，全部直角矩形。
            MaterialTheme(
                colorScheme = AuroraColorScheme,
                typography = AuroraTypography,
                shapes = AuroraShapes
            ) {
                // 唯一极光根层：主页四个 Tab 共用同一份背景，
                // 滚动与翻页时背景完全静止（drawBehind 不订阅任何 state）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .auroraBackground()
                ) {
                    AppRootContent(
                        appSettings = appSettings,
                        externalRequest = externalRequest,
                        onExternalRequestConsumed = {
                            externalRequest = null
                            externalConsumed = true
                        }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_EXTERNAL_CONSUMED, externalConsumed)
    }

    // singleTask：其他应用再次「打开方式」唤起时复用本实例，走此回调而非新建 Activity
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseExternalRequest(intent)?.let {
            // 新的唤起请求：清掉已消费标记，允许再次处理
            externalRequest = it
            externalConsumed = false
        }
    }

    private fun parseExternalRequest(intent: Intent?): ExternalRequest? {
        intent ?: return null
        // 仅当确实由两个 external alias 唤起时才处理，避免与普通启动混淆
        if (!ExternalRequestParser.isExternalComponent(intent.component?.className)) return null
        val uri = resolveIncomingUri(intent) ?: return null
        return ExternalRequestParser.build(
            action = intent.action,
            componentClassName = intent.component?.className,
            uriString = uri,
            // 发送方声明的 MIME：无扩展名文件（相册临时文件）靠它区分图片/文本
            mimeType = intent.type
        )
    }

    /**
     * 采集传入的目标 URI。顺序：`data`（VIEW）→ `EXTRA_STREAM`（SEND）→ `clipData`。
     * 后两者都要覆盖：真实分享应用对 stream 的类型不统一（Uri / String / ArrayList<Uri>），
     * 而 Google Photos / Chrome 这类只设 `clipData`、完全不设 `EXTRA_STREAM`。
     */
    private fun resolveIncomingUri(intent: Intent): String? {
        intent.dataString?.takeIf { it.isNotEmpty() }?.let { return it }
        @Suppress("DEPRECATION")
        streamExtraToUri(intent.extras?.get(Intent.EXTRA_STREAM))?.let { return it }
        // clipData 可能含多项（SEND_MULTIPLE），当前只处理第一项
        intent.clipData?.let { clip ->
            if (clip.itemCount > 0) return clip.getItemAt(0).uri?.toString()
        }
        return null
    }

    private companion object {
        const val KEY_EXTERNAL_CONSUMED = "shso_external_consumed"
    }
}

@Composable
fun AppRootContent(
    appSettings: AppSettings,
    externalRequest: ExternalRequest? = null,
    onExternalRequestConsumed: () -> Unit = {}
) {
    // 冷启动直进主页：不执行任何环境检测、不显示启动加载动画与提示文字。
    MainContainer(
        appSettings = appSettings,
        externalRequest = externalRequest,
        onExternalRequestConsumed = onExternalRequestConsumed
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainContainer(
    appSettings: AppSettings,
    externalRequest: ExternalRequest? = null,
    onExternalRequestConsumed: () -> Unit = {}
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 终端 ROOT 门禁状态：null=检测中，false=未获得，true=已获得
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }

    // ROOT 探测防并发/节流：已有探测在跑则跳过；距上次探测 < 1.5s 不重复发起，
    // 避免按 Home/从授权页快速往返时反复 fork su 进程、反复触发 Magisk 授权弹窗。
    var rootProbeInFlight by remember { mutableStateOf(false) }
    var lastRootProbeAt by remember { mutableLongStateOf(0L) }

    fun refreshRootGranted() {
        val now = System.currentTimeMillis()
        if (rootProbeInFlight || now - lastRootProbeAt < 1500L) return
        rootProbeInFlight = true
        // PermissionChecker.hasRootAccess() 内部在 IO 线程执行带超时的 su 探测
        coroutineScope.launch {
            try {
                val granted = PermissionChecker.hasRootAccess()
                lastRootProbeAt = System.currentTimeMillis()
                rootGranted = granted
                // 同步给 RootService：终端引擎横幅的「当前权限」行据此输出 ROOT/无ROOT 真实文案
                RootService.reportRootState(granted)
            } finally {
                rootProbeInFlight = false
            }
        }
    }

    // 运行时守卫自动安装
    // 档位 ≥2（标准/最强）时，用 APK 内置的 shso_guard.zip 静默安装守卫模块，
    // 使默认档位「开箱即有运行时防护」，而不是要求用户手动去设置页安装。
    // 早期实现是「守卫未安装 → 拒绝一切 root 执行」，导致默认档位 2 连 `ls` 都跑不了。
    // 现改为自动安装：失败也不阻断，由 RootService.reportGuardDegraded 降级为告警 + 审计。
    val context = LocalContext.current
    // 按档位重置尝试标记，使「改档位后」会重新尝试安装
    var guardEnsureAttempted by remember(appSettings.securityLevel) { mutableStateOf(false) }
    LaunchedEffect(rootGranted, appSettings.securityLevel) {
        if (rootGranted != true) return@LaunchedEffect
        if (!GuardModuleInstaller.requiresRuntimeGuard(appSettings.securityLevel)) return@LaunchedEffect
        if (guardEnsureAttempted) return@LaunchedEffect
        guardEnsureAttempted = true
        // ensureInstalled：已就绪直接返回 true；否则走 su 静默安装（失败返回 false，不抛异常）
        if (GuardModuleInstaller.ensureInstalled(context)) {
            // 启动时必须同步守卫的运行模式：模块自带的 policy.conf 默认 mode=enforce，但
            // 用户可编辑的那份 /data/adb/shso_guard/policy.conf **跨重装保留**。若它残留
            // off/log（例如曾在档位 0/1 下写过），运行时会静默不拦截 —— 表现为「装好了守卫却没用」。
            // 之前只有「用户手动改档位」才会同步，冷启动无档位变更就永远不同步。
            GuardModuleInstaller.syncPolicyMode(appSettings.securityLevel)
        }
    }

    // 首次进入即检测；每次回到前台（用户授权完跳回/切换页面）自动重查
    LaunchedEffect(Unit) {
        refreshRootGranted()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshRootGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 外部唤起：解析 URI → 投递到 Hub → 切到文件页（页面消费后跳目录并高亮）。
    // 解析是阻塞 IO（不透明 URI 需拷贝），放在 IO 线程。
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(externalRequest) {
        val request = externalRequest ?: return@LaunchedEffect
        val resolved = try {
            ExternalOpen.resolve(appContext, Uri.parse(request.uri))
        } catch (e: Exception) {
            ExternalOpen.Resolved.Failed("打开失败：${e.message ?: "未知错误"}")
        }
        when (resolved) {
            is ExternalOpen.Resolved.Real -> {
                ExternalOpenHub.post(
                    path = resolved.path,
                    mode = request.mode,
                    mimeType = request.mimeType,
                    copied = false
                )
                coroutineScope.launch { pagerState.animateScrollToPage(2) }
            }
            is ExternalOpen.Resolved.Copied -> {
                ExternalOpenHub.post(
                    path = resolved.path,
                    mode = request.mode,
                    mimeType = request.mimeType,
                    copied = true
                )
                coroutineScope.launch { pagerState.animateScrollToPage(2) }
            }
            is ExternalOpen.Resolved.Failed -> {
                Toast.makeText(appContext, resolved.reason, Toast.LENGTH_LONG).show()
            }
        }
        onExternalRequestConsumed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true,
            // 保留全部 4 页于组合树中（默认只保留当前页，离屏即销毁）。
            // 否则切换标签会丢掉页面内状态：终端输入框内容、文件页的多选/滚动位置、
            // 乃至「终端高危命令确认弹窗」（用户输入命令后翻页，弹窗会被静默丢弃 → 高风险命令无声作废）。
            beyondViewportPageCount = 3
        ) { page ->
            when (page) {
                0 -> HomePage(
                    appSettings = appSettings,
                    onNavigateToTerminal = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    }
                )
                // 终端页对无 ROOT 用户同样开放：ROOT 只影响 DockBar「终端」字样颜色
                // （无 ROOT 时红色提示），不再拦截页面打开与翻页。
                1 -> TerminalPage(appSettings = appSettings)
                2 -> FilePage(
                    appSettings = appSettings,
                    onExecuteFileAndNavigate = { filePath, runAsRoot, riskApproved ->
                        // 透传确认框的用户选择与风险确认标记（档位 3 的「脚本默认非 Root」依赖此项）
                        RootService.executeFile(filePath, runAsRoot, riskApproved)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    }
                )
                3 -> SettingsPage(appSettings = appSettings)
            }
        }

        if (!WindowInsets.isImeVisible) {
            DockBar(
                selectedPage = pagerState.currentPage,
                onTabSelected = { targetPage ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(targetPage)
                    }
                },
                terminalLocked = rootGranted != true,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
