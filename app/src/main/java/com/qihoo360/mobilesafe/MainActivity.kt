// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.PermissionChecker
import com.qihoo360.mobilesafe.data.RootService
import com.qihoo360.mobilesafe.ui.components.DockBar
import com.qihoo360.mobilesafe.ui.components.TerminalIcon
import com.qihoo360.mobilesafe.ui.pages.FilePage
import com.qihoo360.mobilesafe.ui.pages.HomePage
import com.qihoo360.mobilesafe.ui.pages.PermissionGatePage
import com.qihoo360.mobilesafe.ui.pages.SettingsPage
import com.qihoo360.mobilesafe.ui.pages.SplashPage
import com.qihoo360.mobilesafe.ui.pages.TerminalPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun createCustomTextStyles(fontFamily: FontFamily): TextStyles {
    val base = MiuixTheme.textStyles
    return base.copy(
        main = base.main.copy(fontFamily = fontFamily),
        paragraph = base.paragraph.copy(fontFamily = fontFamily),
        body1 = base.body1.copy(fontFamily = fontFamily),
        body2 = base.body2.copy(fontFamily = fontFamily),
        button = base.button.copy(fontFamily = fontFamily),
        footnote1 = base.footnote1.copy(fontFamily = fontFamily),
        footnote2 = base.footnote2.copy(fontFamily = fontFamily),
        headline1 = base.headline1.copy(fontFamily = fontFamily),
        headline2 = base.headline2.copy(fontFamily = fontFamily),
        subtitle = base.subtitle.copy(fontFamily = fontFamily),
        title1 = base.title1.copy(fontFamily = fontFamily),
        title2 = base.title2.copy(fontFamily = fontFamily),
        title3 = base.title3.copy(fontFamily = fontFamily),
        title4 = base.title4.copy(fontFamily = fontFamily)
    )
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appSettings = AppSettings.getInstance(this)
        RootService.initSettings(appSettings)

        setContent {
            val colorSchemeMode = when (appSettings.darkModeOption) {
                1 -> ColorSchemeMode.MonetLight
                2 -> ColorSchemeMode.MonetDark
                else -> ColorSchemeMode.MonetSystem
            }

            val themeController = remember(colorSchemeMode) {
                ThemeController(
                    colorSchemeMode = colorSchemeMode,
                    keyColor = Color(0xFF0B57D0)
                )
            }

            // APP 全局固定使用等宽字体（Typeface.MONOSPACE），不随系统/自定义字体变化
            val activeFontFamily = FontFamily.Monospace

            val customTextStyles = createCustomTextStyles(activeFontFamily)

            MiuixTheme(
                controller = themeController,
                textStyles = customTextStyles
            ) {
                AppRootContent(appSettings = appSettings)
            }
        }
    }
}

enum class AppLaunchState {
    SPLASH,
    PERMISSION_GATE,
    MAIN
}

@Composable
fun AppRootContent(appSettings: AppSettings) {
    var launchState by remember { mutableStateOf(AppLaunchState.SPLASH) }

    AnimatedContent(
        targetState = launchState,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "AppLaunchTransition"
    ) { state ->
        when (state) {
            AppLaunchState.SPLASH -> {
                SplashPage(
                    onCheckPassed = {
                        launchState = AppLaunchState.MAIN
                    },
                    onCheckFailed = {
                        launchState = AppLaunchState.PERMISSION_GATE
                    }
                )
            }
            AppLaunchState.PERMISSION_GATE -> {
                PermissionGatePage(
                    onPermissionsGranted = {
                        launchState = AppLaunchState.MAIN
                    }
                )
            }
            AppLaunchState.MAIN -> {
                MainContainer(appSettings = appSettings)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainContainer(appSettings: AppSettings) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 终端 ROOT 门禁状态：null=检测中，false=未获得，true=已获得
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }

    fun refreshRootGranted() {
        // PermissionChecker.hasRootAccess() 内部在 IO 线程执行带超时的 su 探测
        coroutineScope.launch {
            rootGranted = PermissionChecker.hasRootAccess()
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
            .background(MiuixTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
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
                // 终端页仅在确认 ROOT 后渲染；检测中/未获得一律显示 ROOT 占位页，
                // 保证无 ROOT 用户无论通过点击、滑动还是跳转都看不到 TerminalPage
                1 -> if (rootGranted == true) {
                    TerminalPage(appSettings = appSettings)
                } else {
                    RootRequiredNotice()
                }
                2 -> FilePage(
                    appSettings = appSettings,
                    onExecuteFileAndNavigate = { filePath ->
                        RootService.executeFile(filePath)
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

/**
 * 无 ROOT 时终端页的占位内容：居中红色警示，提示终端功能不可用。
 */
@Composable
private fun RootRequiredNotice() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = TerminalIcon,
                contentDescription = null,
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "需要 ROOT 权限",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF5252),
                textAlign = TextAlign.Center
            )
            Text(
                text = "未检测到 ROOT 权限，终端功能不可用",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
