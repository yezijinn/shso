// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.qihoo360.mobilesafe.data.PermissionChecker
import com.qihoo360.mobilesafe.data.RootService
import com.qihoo360.mobilesafe.ui.components.ColorWheelDialog
import kotlinx.coroutines.launch
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

/**
 * 权限条目右侧的实时状态标签：已获得=绿色、未获得=红色/警示、检查中=次要色。
 */
@Composable
private fun PermissionStatusLabel(granted: Boolean?) {
    val (label, color) = when (granted) {
        true -> "已获得" to Color(0xFF00E676)
        false -> "未获得" to Color(0xFFFF5252)
        null -> "检查中" to MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

@Composable
fun SettingsPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var isIgnoringBattery by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    // ===== 权限分组状态（首次进入即同步初查，前台返回时统一刷新） =====
    var permissionStorageGranted by remember {
        mutableStateOf(PermissionChecker.isStorageGranted(context))
    }
    var permissionBatteryGranted by remember {
        mutableStateOf(PermissionChecker.isIgnoringBatteryOptimizations(context))
    }
    var permissionBackgroundStartGranted by remember {
        mutableStateOf(PermissionChecker.canStartBackgroundActivities(context))
    }
    var permissionRootGranted by remember {
        mutableStateOf<Boolean?>(RootService.isRootGranted)
    }

    fun refreshPermissionStates() {
        permissionStorageGranted = PermissionChecker.isStorageGranted(context)
        permissionBatteryGranted = PermissionChecker.isIgnoringBatteryOptimizations(context)
        permissionBackgroundStartGranted = PermissionChecker.canStartBackgroundActivities(context)
        // su 探测为 IO 阻塞任务（带超时），放到协程中执行，避免卡 UI
        scope.launch {
            permissionRootGranted = PermissionChecker.hasRootAccess()
        }
    }

    // 未获得「访问存储空间」(Android 6-10) 时逐个请求剩余运行时权限
    var legacyPermissionQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var permissionLauncherRef by remember { mutableStateOf<ActivityResultLauncher<String>?>(null) }

    fun requestNextLegacyPermission() {
        val next = legacyPermissionQueue.firstOrNull()
        legacyPermissionQueue = legacyPermissionQueue.drop(1)
        val launcher = permissionLauncherRef ?: return
        if (next != null) {
            launcher.launch(next)
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (legacyPermissionQueue.isNotEmpty()) {
            requestNextLegacyPermission()
        } else {
            refreshPermissionStates()
        }
    }
    LaunchedEffect(Unit) {
        permissionLauncherRef = requestPermissionLauncher
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                refreshPermissionStates()
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
                    text = "权限",
                    insideMargin = PaddingValues(start = 0.dp, top = 8.dp, bottom = 4.dp)
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    ArrowPreference(
                        title = "访问存储空间",
                        summary = "读取外部存储中的 .sh / .so 执行文件（Android 11+ 为“所有文件访问”）",
                        endActions = {
                            PermissionStatusLabel(granted = permissionStorageGranted)
                        },
                        onClick = {
                            if (permissionStorageGranted) {
                                Toast.makeText(context, "访问存储空间权限已获得", Toast.LENGTH_SHORT).show()
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "无法打开系统设置页面", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                legacyPermissionQueue = PermissionChecker.missingLegacyStoragePermissions(context)
                                requestNextLegacyPermission()
                            }
                        }
                    )

                    ArrowPreference(
                        title = "省电策略 / 耗电保护",
                        summary = "允许忽略电池优化，防止长时间后台执行任务时被系统休眠查杀",
                        endActions = {
                            PermissionStatusLabel(granted = permissionBatteryGranted)
                        },
                        onClick = {
                            if (permissionBatteryGranted) {
                                Toast.makeText(context, "已获得省电策略豁免（忽略电池优化）", Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "无法打开电池优化设置页面", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    )

                    ArrowPreference(
                        title = "后台弹出页面",
                        summary = "允许本应用在后台弹出界面（若系统 ROM 支持该开关）",
                        endActions = {
                            PermissionStatusLabel(granted = permissionBackgroundStartGranted)
                        },
                        onClick = {
                            if (permissionBackgroundStartGranted) {
                                Toast.makeText(context, "已允许后台弹出页面", Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "无法打开应用详情设置页面", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )

                    ArrowPreference(
                        title = "ROOT 权限",
                        summary = "真实执行 su 校验；需在 Magisk / KernelSU 授权管理中放行本应用",
                        endActions = {
                            PermissionStatusLabel(granted = permissionRootGranted)
                        },
                        onClick = {
                            if (permissionRootGranted == true) {
                                Toast.makeText(context, "ROOT 权限已获得", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "未检测到 ROOT，请在 Magisk / KernelSU 中为本应用授权后返回自动刷新", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

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
                        summary = "在添加到shso时新建独立文件夹进行存储",
                        checked = appSettings.useIndependentFolder,
                        onCheckedChange = { appSettings.setIndependentFolder(it) }
                    )

                    SwitchPreference(
                        title = "添加后自动删除文件",
                        summary = "将文件复制到shso后自动清理源文件",
                        checked = appSettings.autoDeleteAfterAdding,
                        onCheckedChange = { appSettings.setAutoDelete(it) }
                    )

                    SwitchPreference(
                        title = "添加后自动执行文件",
                        summary = "添加到shso后自动跳转终端并开始执行",
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
                        title = "shso 终端提示",
                        summary = "控制是否在终端显示任务启动、路径及退出状态信息",
                        checked = appSettings.showShsoBanner,
                        onCheckedChange = { appSettings.setShsoBanner(it) }
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
                                painter = painterResource(id = R.drawable.ic_shso),
                                contentDescription = "shso 图标",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "shso",
                                    style = MiuixTheme.textStyles.title2,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Start
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "v9.0.2 (shso)",
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
