// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qihoo360.mobilesafe.data.RootService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionGatePage(
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCheckingRoot by remember { mutableStateOf(false) }
    var hasStoragePermission by remember { mutableStateOf(false) }
    var isIgnoringBattery by remember { mutableStateOf(false) }

    fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun checkBatteryOptimization(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun refreshAllChecks() {
        hasStoragePermission = checkStoragePermission()
        isIgnoringBattery = checkBatteryOptimization()
        scope.launch {
            // 刷新 ROOT 状态用于卡片展示（ROOT 为可选权限）
            RootService.checkRoot()
            // 仅存储权限就绪即可自动进入
            if (checkStoragePermission()) {
                onPermissionsGranted()
            }
        }
    }

    LaunchedEffect(Unit) {
        hasStoragePermission = checkStoragePermission()
        isIgnoringBattery = checkBatteryOptimization()
        RootService.checkRoot()

        while (isActive) {
            val storageOk = checkStoragePermission()
            hasStoragePermission = storageOk
            isIgnoringBattery = checkBatteryOptimization()
            // 刷新 ROOT 状态用于卡片展示（ROOT 为可选权限）
            RootService.checkRoot(force = true)

            // ROOT 为可选权限，仅存储权限就绪即可自动进入
            if (storageOk) {
                onPermissionsGranted()
                break
            }
            delay(1000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAllChecks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "环境检查",
                color = MiuixTheme.colorScheme.surface
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "欢迎使用 shso",
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "shso 是一款专为 ROOT 环境设计的高级执行工具。为了正常执行脚本、管理 /data/adb 文件并提供终端输出，需要授予以下运行权限。其中 ROOT 权限为可选项：未获取 ROOT 时仍可正常进入并使用软件，仅脚本执行等 ROOT 功能不可用。授权后页面将自动感知进入。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    when (RootService.isRootGranted) {
                                        true -> Color(0xFF00E676)
                                        false -> Color(0xFFFF5252)
                                        else -> Color(0xFFFFB300)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "ROOT 超级用户权限（可选）",
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = when (RootService.isRootGranted) {
                            true -> "✓ 已获取 ROOT 权限，可执行所有底层命令"
                            false -> "✗ 未获取 ROOT 权限，脚本执行等 ROOT 功能不可用（不影响进入软件）"
                            else -> "正在检查 ROOT 授权状态..."
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = when (RootService.isRootGranted) {
                            true -> Color(0xFF00E676)
                            false -> Color(0xFFFF5252)
                            else -> MiuixTheme.colorScheme.onSurfaceSecondary
                        },
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (RootService.isRootGranted != true) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isCheckingRoot = true
                                    val ok = RootService.checkRoot(force = true)
                                    isCheckingRoot = false
                                    if (ok && checkStoragePermission()) {
                                        onPermissionsGranted()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isCheckingRoot,
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary,
                                contentColor = MiuixTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (isCheckingRoot) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            } else {
                                Text("请求 ROOT 授权")
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (hasStoragePermission) Color(0xFF00E676) else Color(0xFFFF5252)
                                )
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "所有文件访问权限",
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = if (hasStoragePermission) {
                            "✓ 已获取全盘文件读写权限"
                        } else {
                            "✗ 缺少管理所有文件权限，无法读取外部存储脚本"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (hasStoragePermission) Color(0xFF00E676) else Color(0xFFFF5252),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!hasStoragePermission) {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        context.startActivity(intent)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MiuixTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("前往系统设置开启")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isIgnoringBattery) Color(0xFF00E676) else Color(0xFFFFB300)
                                )
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            text = "忽略电池优化 (后台保活 - 可选)",
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = if (isIgnoringBattery) {
                            "✓ 已开启忽略电池优化，任务可持久在后台运行"
                        } else {
                            "建议开启忽略电池优化，防止长时间后台执行任务时被系统休眠查杀。若暂不开启，后续可随时在「设置」中二次申请。"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (isIgnoringBattery) Color(0xFF00E676) else MiuixTheme.colorScheme.onSurfaceSecondary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isIgnoringBattery) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        val intent2 = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent2)
                                    } catch (_: Exception) {
                                        try {
                                            val intent3 = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent3)
                                        } catch (_: Exception) {}
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MiuixTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("申请忽略电池优化")
                        }
                    }
                }
            }

            // ROOT 为可选权限，进入门禁仅校验存储权限
            val allGranted = hasStoragePermission
            Button(
                onClick = onPermissionsGranted,
                enabled = allGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    color = if (allGranted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledSecondaryVariant,
                    contentColor = MiuixTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = if (allGranted) "存储权限就绪，进入软件" else "请先授予存储权限",
                    style = MiuixTheme.textStyles.title4
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
