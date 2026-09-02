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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.PowerManager
import java.io.File
import com.qihoo360.mobilesafe.AppFontFamily
import com.qihoo360.mobilesafe.R
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.ui.components.ColorWheelDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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
    val isMaterial = appSettings.appThemeOption == AppSettings.THEME_MATERIAL
    var showColorDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                var fileName = "custom_font.ttf"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: "custom_font.ttf"
                    }
                }

                val destFile = File(context.filesDir, "custom_app_font.ttf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val typeface = android.graphics.Typeface.createFromFile(destFile)
                if (typeface != null) {
                    appSettings.setCustomFont(destFile.absolutePath, fileName)
                    Toast.makeText(context, "成功加载字体: $fileName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "字体解析失败，请选择有效的 TTF / OTF 文件", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "加载字体失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    val appThemeOptions = remember {
        listOf(
            DropdownItem(text = "Material"),
            DropdownItem(text = "Miuix")
        )
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
                    .clip(if (isMaterial) RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp))
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
                    .clip(if (isMaterial) RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp))
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(if (isMaterial) RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp))
                        .clickable { appSettings.setCustomFontEnabled(!appSettings.useCustomFont) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "自定义软件字体",
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (appSettings.useCustomFont) {
                                if (appSettings.customFontPath.isNotEmpty() && appSettings.customFontName.isNotEmpty())
                                    "当前已启用：${appSettings.customFontName}"
                                else
                                    "当前已启用：内置字体"
                            } else {
                                "当前使用：系统默认字体"
                            },
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }

                    IconButton(
                        onClick = { showFontDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Settings,
                            contentDescription = "配置字体",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Switch(
                        checked = appSettings.useCustomFont,
                        onCheckedChange = { enable ->
                            appSettings.setCustomFontEnabled(enable)
                        }
                    )
                }

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

                OverlaySpinnerPreference(
                    title = "应用主题",
                    items = appThemeOptions,
                    selectedIndex = appSettings.appThemeOption,
                    onSelectedIndexChange = { index ->
                        appSettings.setAppTheme(index)
                    }
                )

                if (appSettings.appThemeOption == AppSettings.THEME_MIUIX) {
                    SwitchPreference(
                        title = "悬浮底栏",
                        summary = "切换底部导航栏为悬浮胶囊样式",
                        checked = appSettings.enableFloatingDock,
                        onCheckedChange = { appSettings.setFloatingDock(it) }
                    )
                }
            }

            SmallTitle(
                text = "终端",
                insideMargin = PaddingValues(start = 0.dp, top = 8.dp, bottom = 4.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(if (isMaterial) RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp))
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
                    .clip(if (isMaterial) RoundedCornerShape(16.dp) else RoundedCornerShape(12.dp))
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
                                text = "v1.0.2 (Kernel.Extend)",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                textAlign = TextAlign.Start
                            )
                        }
                    }

                    Text(
                        text = "KernelEX 是一款专为 Android 打造的高性能 ROOT 执行工具。支持在安全、高效的环境中运行 .sh 脚本与 .so 二进制文件，提供交互式终端、ANSI 着色以及强大的全盘 ROOT 文件管理能力。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                openInBrowserOnly("https://github.com/KernelExtend/KernelEX")
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painterResource(id = R.drawable.ic_github),
                                contentDescription = "GitHub",
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "在 GitHub 上查看源代码",
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary.copy(0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                openInBrowserOnly("https://t.me/KernelEX")
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painterResource(id = R.drawable.ic_telegram),
                                contentDescription = "Telegram",
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "加入我们的 Telegram 频道",
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary.copy(0.6f),
                            modifier = Modifier.size(18.dp)
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

    if (showFontDialog) {
        val previewFontFamily = remember(appSettings.customFontPath, appSettings.useCustomFont) {
            if (appSettings.useCustomFont) {
                if (appSettings.customFontPath.isNotEmpty() && File(appSettings.customFontPath).exists()) {
                    try {
                        FontFamily(android.graphics.Typeface.createFromFile(File(appSettings.customFontPath)))
                    } catch (_: Exception) {
                        AppFontFamily
                    }
                } else {
                    AppFontFamily
                }
            } else {
                FontFamily.Default
            }
        }

        WindowDialog(
            show = true,
            title = "自定义软件字体",
            summary = "支持选择 .ttf 或 .otf 字体文件",
            onDismissRequest = { showFontDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前字体：",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = if (appSettings.useCustomFont) {
                            if (appSettings.customFontName.isNotEmpty()) appSettings.customFontName else "内置字体"
                        } else {
                            "系统默认字体"
                        },
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "字体实时预览 Preview",
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Text(
                            text = "KernelEX 任务调度引擎",
                            fontFamily = previewFontFamily,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\nabcdefghijklmnopqrstuvwxyz 0123456789",
                            fontFamily = previewFontFamily,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            fontPickerLauncher.launch("*/*")
                        },
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary,
                            contentColor = MiuixTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("上传 / 选择字体文件 (.ttf / .otf)")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val oldFile = File(context.filesDir, "custom_app_font.ttf")
                                if (oldFile.exists()) oldFile.delete()
                                appSettings.resetCustomFont()
                                Toast.makeText(context, "已恢复默认字体", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MiuixTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("恢复默认字体")
                        }

                        Button(
                            onClick = { showFontDialog = false },
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MiuixTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }
}
