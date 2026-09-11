// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    AppRootContent(appSettings = appSettings)
                }
            }
        }
    }
}

@Composable
fun AppRootContent(appSettings: AppSettings) {
    // 冷启动直进主页：不执行任何环境检测、不显示启动加载动画与提示文字。
    MainContainer(appSettings = appSettings)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainContainer(appSettings: AppSettings) {
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

    // ── 运行时守卫自动安装 ──
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
