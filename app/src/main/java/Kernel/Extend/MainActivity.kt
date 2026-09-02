// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package Kernel.Extend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.io.File
import Kernel.Extend.data.AppSettings
import Kernel.Extend.data.RootService
import Kernel.Extend.ui.components.DockBar
import Kernel.Extend.ui.pages.FilePage
import Kernel.Extend.ui.pages.HomePage
import Kernel.Extend.ui.pages.PermissionGatePage
import Kernel.Extend.ui.pages.SettingsPage
import Kernel.Extend.ui.pages.SplashPage
import Kernel.Extend.ui.pages.TerminalPage
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController

val AppFontFamily = FontFamily(
    Font(R.font.app_font, FontWeight.Normal),
    Font(R.font.app_font, FontWeight.Medium),
    Font(R.font.app_font, FontWeight.Bold),
    Font(R.font.app_font, FontWeight.SemiBold)
)

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
            val isMaterial = appSettings.appThemeOption == AppSettings.THEME_MATERIAL

            val colorSchemeMode = if (isMaterial) {
                when (appSettings.darkModeOption) {
                    1 -> ColorSchemeMode.MonetLight
                    2 -> ColorSchemeMode.MonetDark
                    else -> ColorSchemeMode.MonetSystem
                }
            } else {
                when (appSettings.darkModeOption) {
                    1 -> ColorSchemeMode.Light
                    2 -> ColorSchemeMode.Dark
                    else -> ColorSchemeMode.System
                }
            }

            val themeController = remember(colorSchemeMode, isMaterial) {
                ThemeController(
                    colorSchemeMode = colorSchemeMode,
                    keyColor = if (isMaterial) Color(0xFF0B57D0) else null
                )
            }

            val activeFontFamily = remember(appSettings.useCustomFont, appSettings.customFontPath) {
                if (appSettings.useCustomFont) {
                    if (appSettings.customFontPath.isNotEmpty()) {
                        val file = File(appSettings.customFontPath)
                        if (file.exists()) {
                            try {
                                FontFamily(android.graphics.Typeface.createFromFile(file))
                            } catch (_: Exception) {
                                AppFontFamily
                            }
                        } else {
                            AppFontFamily
                        }
                    } else {
                        AppFontFamily
                    }
                } else {
                    FontFamily.Default
                }
            }

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
                1 -> TerminalPage(appSettings = appSettings)
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
                appTheme = appSettings.appThemeOption,
                isFloating = appSettings.enableFloatingDock,
                onTabSelected = { targetPage ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(targetPage)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
