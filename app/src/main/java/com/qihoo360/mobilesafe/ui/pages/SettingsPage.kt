// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.PowerManager
import com.qihoo360.mobilesafe.R
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.ui.components.ColorWheelDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles

/**
 * Creates a copy of the current [MiuixTheme.textStyles] where every field's font size is
 * reduced by [deltaSp] (sp). Non-size attributes (font family, weight, line height...) are kept.
 */
@Composable
fun createShrunkTextStyles(deltaSp: Float): TextStyles {
    val base = MiuixTheme.textStyles
    fun shrink(style: TextStyle): TextStyle {
        val size = style.fontSize
        return if (size.isSp) {
            style.copy(fontSize = (size.value - deltaSp).sp)
        } else {
            style
        }
    }
    return base.copy(
        main = shrink(base.main),
        paragraph = shrink(base.paragraph),
        body1 = shrink(base.body1),
        body2 = shrink(base.body2),
        button = shrink(base.button),
        footnote1 = shrink(base.footnote1),
        footnote2 = shrink(base.footnote2),
        headline1 = shrink(base.headline1),
        headline2 = shrink(base.headline2),
        subtitle = shrink(base.subtitle),
        title1 = shrink(base.title1),
        title2 = shrink(base.title2),
        title3 = shrink(base.title3),
        title4 = shrink(base.title4)
    )
}

@Composable
fun SettingsPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var isIgnoringBattery by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var showColorDialog by remember { mutableStateOf(false) }

    val darkModeOptions = remember {
        listOf(
            DropdownItem(text = "开启"),
            DropdownItem(text = "关闭"),
            DropdownItem(text = "跟随系统")
        )
    }

    val selectedDarkModeIndex = when (appSettings.darkModeOption) {
        2 -> 0
        1 -> 1
        else -> 2
    }

    fun openInBrowserOnly(url: String) {
        try {
            val uri = Uri.parse(url)
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val resolveInfos = context.packageManager.queryIntentActivities(browserIntent, 0)
            val browserPackages = resolveInfos.map { it.activityInfo.packageName }
                .filter { pkg -> pkg != "com.github.android" && !pkg.contains("github") }

            val targetIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (browserPackages.isNotEmpty()) {
                val preferred = browserPackages.firstOrNull {
                    it.contains("browser") || it.contains("chrome") || it.contains("edge") || it.contains("firefox")
                } ?: browserPackages.first()
                targetIntent.setPackage(preferred)
                context.startActivity(targetIntent)
            } else {
                val chooser = Intent.createChooser(targetIntent, "选择浏览器打开").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        } catch (_: Exception) {
            try {
                val generalIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(generalIntent)
            } catch (_: Exception) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(context, "未能调起浏览器，链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val shrunkTextStyles = createShrunkTextStyles(4f)
    MiuixTheme(
        colors = MiuixTheme.colorScheme,
        textStyles = shrunkTextStyles
    ) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "设置",
                    color = MiuixTheme.colorScheme.surface
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start
            ) {
                SmallTitle(
                    text = "文件",
                    insideMargin = PaddingValues(start = 0.dp, top = 8.dp, bottom = 4.dp)
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    SwitchPreference(
                        title = "使用独立文件夹存储",
                        summary = "在添加到KernelEX时新建独立文件夹进行存储",
                        checked = appSettings.useIndependentFolder,
                        onCheckedChange = { appSettings.setIndependentFolder(it) }
                    )

                    SwitchPreference(
                        title = "添加后自动删除文件",
                        summary = "将文件复制到KernelEX后自动清理源文件",
                        checked = appSettings.autoDeleteAfterAdding,
                        onCheckedChange = { appSettings.setAutoDelete(it) }
                    )

                    SwitchPreference(
                        title = "添加后自动执行文件",
                        summary = "添加到KernelEX后自动跳转终端并开始执行",
                        checked = appSettings.autoExecuteAfterAdding,
                        onCheckedChange = { appSettings.setAutoExecute(it) }
                    )
                }

                SmallTitle(
                    text = "主题",
                    insideMargin = PaddingValues(start = 0.dp, top = 8.dp, bottom = 4.dp)
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    ArrowPreference(
                        title = "终端文字颜色",
                        summary = "自定义终端控制台文本的显示高亮颜色",
                        onClick = { showColorDialog = true },
                        endActions = {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(appSettings.terminalTextColor))
                            )
                        }
                    )

                    OverlaySpinnerPreference(
                        title = "深色模式",
                        items = darkModeOptions,
                        selectedIndex = selectedDarkModeIndex,
                        onSelectedIndexChange = { index ->
                            val mode = when (index) {
                                0 -> 2
                                1 -> 1
                                else -> 0
                            }
                            appSettings.setDarkMode(mode)
                        }
                    )
                }

                SmallTitle(
                    text = "终端",
                    insideMargin = PaddingValues(start = 0.dp, top = 8.dp, bottom = 4.dp)
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    SwitchPreference(
                        title = "HyperCore 终端提示",
                        summary = "控制是否在终端显示 HyperCore 引擎初始化及环境检测标头",
                        checked = appSettings.showHyperCoreBanner,
                        onCheckedChange = { appSettings.setHyperCoreBanner(it) }
                    )

                    SwitchPreference(
                        title = "KernelEX 终端提示",
                        summary = "控制是否在终端显示任务启动、路径及退出状态信息",
                        checked = appSettings.showKernelEXBanner,
                        onCheckedChange = { appSettings.setKernelEXBanner(it) }
                    )

                    ArrowPreference(
                        title = "忽略电池优化 (后台保活)",
                        summary = if (isIgnoringBattery) "✓ 已开启忽略电池优化，任务可持久在后台运行" else "未开启，点击前往申请开启以防后台被杀",
                        onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    val intent2 = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent2)
                                } catch (_: Exception) {
                                    try {
                                        val intent3 = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent3)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    )
                }

                SmallTitle(
                    text = "关于",
                    insideMargin = PaddingValues(start = 0.dp, top = 8.dp, bottom = 4.dp)
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_kernelex),
                                contentDescription = "KernelEX 图标",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "KernelEX",
                                    style = MiuixTheme.textStyles.title2,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Start
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "v9.0.2 (KernelEX)",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }

                        Button(
                            onClick = { openInBrowserOnly("https://github.com/yezijinn") },
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary,
                                contentColor = MiuixTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text(
                                text = "Github",
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(70.dp))
            }
        }

        if (showColorDialog) {
            ColorWheelDialog(
                show = true,
                initialColor = Color(appSettings.terminalTextColor),
                onDismissRequest = { showColorDialog = false },
                onColorSelected = { color ->
                    appSettings.setTerminalColor(color)
                }
            )
        }
    }
}
