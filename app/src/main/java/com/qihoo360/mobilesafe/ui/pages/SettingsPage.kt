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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qihoo360.mobilesafe.R
import com.qihoo360.mobilesafe.data.AppSettings
import com.qihoo360.mobilesafe.data.PermissionChecker
import com.qihoo360.mobilesafe.data.RootService
import com.qihoo360.mobilesafe.ui.theme.AuroraAccentBar
import com.qihoo360.mobilesafe.ui.theme.AuroraArrowPreference
import com.qihoo360.mobilesafe.ui.theme.AuroraSwitchPreference
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.AuroraWindowDialog
import com.qihoo360.mobilesafe.ui.theme.auroraFilledButton
import com.qihoo360.mobilesafe.ui.theme.auroraPrimaryButtonColors
import kotlinx.coroutines.launch

/**
 * 权限 4 行右侧统一为胶囊开关（与下方 3 个 Switch 视觉一致）：ON=已获得 / OFF=未获得。
 * 只读展示——点击胶囊/整行触发 onClick（跳转系统设置），状态由系统检查结果驱动。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // ===== 权限状态（首次进入即同步初查，前台返回时统一刷新） =====
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

    // 未获得「存储空间」(Android 6-10) 时逐个请求剩余运行时权限
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
                refreshPermissionStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var showAboutDialog by remember { mutableStateOf(false) }

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
            TopAppBar(
                title = {
                    Column {
                        Text("设置", color = AuroraTokens.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        AuroraAccentBar(width = 36.dp, height = 3.dp)
                    }
                },
                actions = {
                    // 「关于」位于右上角，内容以对话框呈现
                    Box(
                        modifier = Modifier
                            .clickable { showAboutDialog = true }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "关于",
                            color = AuroraTokens.Text,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AuroraTokens.Surface,
                    titleContentColor = AuroraTokens.Text
                )
            )
        }
    ) { innerPadding ->
        // 全部条目单列表直排：不分组、无分割线、无空行
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            horizontalAlignment = Alignment.Start
        ) {
            AuroraArrowPreference(
                title = "存储空间",
                summary = "允许读取外部存储,所有文件访问权限",
                statusSwitch = permissionStorageGranted,
                onClick = {
                    if (permissionStorageGranted) {
                        Toast.makeText(context, "存储空间权限已获得", Toast.LENGTH_SHORT).show()
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

            AuroraArrowPreference(
                title = "省电策略",
                summary = "省电策略无限制  耗电保护允许后台",
                statusSwitch = permissionBatteryGranted,
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

            AuroraArrowPreference(
                title = "后台弹出",
                summary = "权限管理 其他权限 允许后台弹出页",
                statusSwitch = permissionBackgroundStartGranted,
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

            AuroraArrowPreference(
                title = "超级用户",
                summary = "Magisk KernelSU 超级用户授权",
                statusSwitch = permissionRootGranted == true,
                statusSwitchEnabled = permissionRootGranted != null,
                onClick = {
                    if (permissionRootGranted == true) {
                        Toast.makeText(context, "超级用户授权已获得", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "未检测到 ROOT，请在 Magisk / KernelSU 中为本应用授权后返回自动刷新", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // 无空行直连：权限区后紧跟三个开关项
            AuroraSwitchPreference(
                title = "独立存储",
                summary = "添加到 shso 时存到专用的文件夹",
                checked = appSettings.useIndependentFolder,
                onCheckedChange = { appSettings.setIndependentFolder(it) }
            )

            AuroraSwitchPreference(
                title = "自动删除",
                summary = "添加到 shso 后自动删除原始文件",
                checked = appSettings.autoDeleteAfterAdding,
                onCheckedChange = { appSettings.setAutoDelete(it) }
            )

            AuroraSwitchPreference(
                title = "自动执行",
                summary = "添加到 shso 时转到终端立即执行",
                checked = appSettings.autoExecuteAfterAdding,
                onCheckedChange = { appSettings.setAutoExecute(it) }
            )

            Spacer(modifier = Modifier.height(70.dp))
        }
    }

    if (showAboutDialog) {
        AuroraWindowDialog(
            show = true,
            title = "Jinn",
            onDismissRequest = { showAboutDialog = false }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "shso 图标",
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(0.dp))
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "shso",
                        style = AuroraTextStyles.title2,
                        fontWeight = FontWeight.Bold,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "v9.0.2",
                        style = AuroraTextStyles.footnote1,
                        color = AuroraTokens.TextSecondary,
                        textAlign = TextAlign.Start
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = { openInBrowserOnly("https://github.com/yezijinn") },
                    colors = auroraPrimaryButtonColors(),
                    modifier = Modifier.auroraFilledButton()
                ) {
                    Text(
                        text = "访问Github",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
