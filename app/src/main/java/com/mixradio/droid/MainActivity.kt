// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

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
import com.mixradio.droid.data.PermissionChecker
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.streamExtraToUris
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

/**
 * 「左右滑屏可切换菜单」提示是否已在本次进程启动中弹过。
 *
 * 该提示按「每次冷启动一次」计，故用进程级标记而不是 remember/rememberSaveable ——
 * Activity 因转屏、深色模式切换等原因重建时不应再弹。
 */
@Volatile
private var swipeHintShown = false

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
        val uris = resolveIncomingUris(intent)
        if (uris.isEmpty()) return null
        return ExternalRequestParser.build(
            action = intent.action,
            componentClassName = intent.component?.className,
            uriStrings = uris,
            // 发送方声明的 MIME：无扩展名文件（相册临时文件）靠它区分图片/文本
            mimeType = intent.type
        )
    }

    /**
     * 采集传入的全部目标 URI，按接收顺序去重。顺序：`data`（VIEW）→ `EXTRA_STREAM`（SEND）→ `clipData`。
     * 三者都要覆盖：真实分享应用对 stream 的类型不统一（Uri / String / ArrayList<Uri>），
     * 而 Google Photos / Chrome 这类只设 `clipData`、完全不设 `EXTRA_STREAM`；
     * `ACTION_SEND_MULTIPLE` 的多文件同时散落在 EXTRA_STREAM 列表与多条 clipData 中。
     */
    private fun resolveIncomingUris(intent: Intent): List<String> {
        // 整段包 runCatching：这是一条**对外**的入口，发送方完全不可信。
        // 任何一步抛出都不该让 shso 崩在 onCreate/onNewIntent 里
        // （singleTask 下每次外部唤起都崩，表现为「分享到 shso 直接闪退」）。
        return runCatching {
            val uris = LinkedHashSet<String>()
            intent.dataString?.takeIf { it.isNotEmpty() }?.let { uris.add(it) }
            // 按类型取，而不是 `extras.get(key)`：后者会先 unparcel 整个 Bundle，
            // 发送方塞入只有它自己 APK 才有定义的自定义 Parcelable 时，
            // 本进程 Class.forName 失败 → BadParcelableException。
            // 类型化取值为 null 而不是抛异常，正是这里要的语义。
            //
            // 单值与列表分两次取：`getParcelableExtra<Uri>` 对 ArrayList 会在强制转换处抛
            // ClassCastException，`getParcelableArrayListExtra` 对单 Uri 同理；各自包一层
            // runCatching，任一形态都不影响另一种被采集到。
            runCatching {
                @Suppress("DEPRECATION")
                val single: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                uris.addAll(streamExtraToUris(single))
            }
            runCatching {
                @Suppress("DEPRECATION")
                val many: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                uris.addAll(streamExtraToUris(many))
            }
            // 兼容把 stream 塞成字符串的发送方
            @Suppress("DEPRECATION")
            intent.getStringExtra(Intent.EXTRA_STREAM)
                ?.takeIf { it.isNotEmpty() }
                ?.let { uris.add(it) }
            // clipData 可能含多项（SEND_MULTIPLE），全部展开
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.toString()?.takeIf { it.isNotEmpty() }?.let { uris.add(it) }
                }
            }
            uris.toList()
        }.getOrDefault(emptyList())
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

    // 冷启动提示：勾选「左右提示」时每次冷启动弹一次；未勾选则永不弹。
    // 键取 Unit 而不是 showSwipeHint —— 提示只在冷启动出现，在设置页拨动开关不该
    // 顺带弹一条（同一进程内「关掉再打开」也就不会时而弹、时而不弹）。
    LaunchedEffect(Unit) {
        if (!appSettings.showSwipeHint || swipeHintShown) return@LaunchedEffect
        swipeHintShown = true
        Toast.makeText(appContext, "左右滑屏可切换菜单", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(externalRequest) {
        val request = externalRequest ?: return@LaunchedEffect
        // 多文件分享（SEND_MULTIPLE）只处理首个：下游的定位与动作分派都是单目标设计，
        // 逐个弹安装/编辑确认框也无从合并。其余文件计数后由文件页明确告知，不再静默丢弃。
        val first = request.uris.firstOrNull()
        val additional = (request.uris.size - 1).coerceAtLeast(0)
        val resolved = if (first == null) {
            ExternalOpen.Resolved.Failed("未收到可打开的文件")
        } else {
            try {
                ExternalOpen.resolve(appContext, Uri.parse(first))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ExternalOpen.Resolved.Failed("打开失败：${e.message ?: "未知错误"}")
            }
        }
        when (resolved) {
            is ExternalOpen.Resolved.Real -> {
                ExternalOpenHub.post(
                    path = resolved.path,
                    mode = request.mode,
                    mimeType = request.mimeType,
                    copied = false,
                    additionalCount = additional
                )
                coroutineScope.launch { pagerState.animateScrollToPage(2) }
            }
            is ExternalOpen.Resolved.Copied -> {
                ExternalOpenHub.post(
                    path = resolved.path,
                    mode = request.mode,
                    mimeType = request.mimeType,
                    copied = true,
                    additionalCount = additional
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
                // isVisible：4 页常驻组合树，终端页离屏时停掉日志解析（见 TerminalPage 说明）。
                1 -> TerminalPage(
                    appSettings = appSettings,
                    isVisible = pagerState.currentPage == 1
                )
                2 -> FilePage(
                    appSettings = appSettings,
                    onExecuteFileAndNavigate = { filePath, runAsRoot ->
                        RootService.executeFile(filePath, runAsRoot)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    }
                )
                3 -> SettingsPage(appSettings = appSettings)
            }
        }

        if (!WindowInsets.isImeVisible && !appSettings.hideDockBar) {
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
