// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.pages

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
import com.mixradio.droid.BuildConfig
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mixradio.droid.R
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.PermissionChecker
import com.mixradio.droid.data.RootService
import com.mixradio.droid.data.security.GuardModuleInstaller
import com.mixradio.droid.data.security.SecurityAuditLog
import com.mixradio.droid.data.security.SecurityLevels
import com.mixradio.droid.ui.theme.AuroraAccentBar
import com.mixradio.droid.ui.theme.AuroraArrowPreference
import com.mixradio.droid.ui.theme.AuroraSwitchPreference
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import com.mixradio.droid.ui.theme.auroraPrimaryButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import android.util.Log

/**
 * 权限 4 行右侧统一为胶囊开关（与下方 3 个 Switch 视觉一致）：ON=已获得 / OFF=未获得。
 * 只读展示——点击胶囊/整行触发 onClick（跳转系统设置），状态由系统检查结果驱动。
 */

/**
 * 检查更新 UI 状态机。
 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val tag: Int) : UpdateUiState
    data class Available(val tag: Int) : UpdateUiState
    data object NetworkError : UpdateUiState
}

/**
 * 抓取 GitHub tags 页面，正则提取纯数字标签（兼容 v20260904 与 20260904 两种写法），
 * 返回其中最大的版本号（即最新的发布日期）；无可解析标签时返回 0。
 * 网络异常会向上抛出，由调用方转为「网络不佳」弹窗。
 */
private suspend fun fetchLatestGitHubDateTag(): Int = withContext(Dispatchers.IO) {
    val conn = (URL("https://github.com/yezijinn/shso/tags").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", "shso-update-check")
    }
    try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("GitHub tags HTTP ${conn.responseCode}")
        }
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        val linkRe = Regex("""yezijinn/shso/(?:tree|releases/tag)/([^"'<>?#\s]+)""")
        val nums = linkRe.findAll(html).mapNotNull { m ->
            m.groupValues[1].removePrefix("v")
                .takeIf { it.length in 6..8 && it.all(Char::isDigit) }
                ?.toIntOrNull()
        }
        nums.maxOrNull() ?: throw IOException("GitHub tags contain no numeric release tag")
    } finally {
        conn.disconnect()
    }
}

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
    var permissionInstallGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) true
            else context.packageManager.canRequestPackageInstalls()
        )
    }

    fun refreshPermissionStates() {
        permissionStorageGranted = PermissionChecker.isStorageGranted(context)
        permissionBatteryGranted = PermissionChecker.isIgnoringBatteryOptimizations(context)
        permissionBackgroundStartGranted = PermissionChecker.canStartBackgroundActivities(context)
        // su 探测为 IO 阻塞任务（带超时），放到协程中执行，避免卡 UI
        scope.launch {
            val granted = PermissionChecker.hasRootAccess()
            permissionRootGranted = granted
            // 回写 RootService：统一 ROOT 状态来源，保证终端 banner 与设置页开关一致
            RootService.reportRootState(granted)
        }
        permissionInstallGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) true
            else context.packageManager.canRequestPackageInstalls()
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

    // ===== 安全审计弹窗状态 =====
    var showAuditDialog by remember { mutableStateOf(false) }
    var auditDialogLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var installingGuard by remember { mutableStateOf(false) }
    var guardInstalled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        scope.launch {
            guardInstalled = GuardModuleInstaller.status() is GuardModuleInstaller.GuardStatus.Installed
        }
    }

    // ===== 检查更新状态 =====
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    // 胶囊视觉开关：点击「检查更新」后置为 true（亮起），3 秒后自动回关
    var updateChecking by remember { mutableStateOf(false) }

    fun checkForUpdate() {
        if (updateState == UpdateUiState.Checking) return
        updateState = UpdateUiState.Checking
        scope.launch {
            try {
                val latest = fetchLatestGitHubDateTag()
                val local = BuildConfig.VERSION_CODE
                Log.d("ShsoUpdate", "latest=$latest local=$local")
                updateState = if (latest > local) UpdateUiState.Available(latest) else UpdateUiState.UpToDate(latest)
            } catch (_: Exception) {
                updateState = UpdateUiState.NetworkError
            }
        }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = 56.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                horizontalAlignment = Alignment.Start
            ) {
            SettingsPermissionsGroup(
                storage = permissionStorageGranted,
                battery = permissionBatteryGranted,
                backgroundStart = permissionBackgroundStartGranted,
                root = permissionRootGranted,
                install = permissionInstallGranted,
                onRequestStorage = {
                    SettingsPermissionIntents.openStorageSettings(context) {
                        legacyPermissionQueue = PermissionChecker.missingLegacyStoragePermissions(context)
                        requestNextLegacyPermission()
                    }
                },
                onRequestBattery = { SettingsPermissionIntents.openBatterySettings(context) },
                onRequestBackground = { SettingsPermissionIntents.openBackgroundSettings(context) },
                onRequestInstall = { SettingsPermissionIntents.openInstallSettings(context) }
            )

            // 无空行直连：权限区后紧跟三个开关项
            SettingsFileBehaviorGroup(appSettings = appSettings)

            // ===== 检查更新 =====
            // 右侧胶囊与权限行一致；点击胶囊/整行触发检查，亮起 3 秒后自动回关
            AuroraArrowPreference(
                title = "检查更新",
                summary = "检查 github 是否发布了新的版本",
                statusSwitch = updateChecking,
                statusSwitchEnabled = true,
                onClick = {
                    if (!updateChecking) {
                        updateChecking = true
                        checkForUpdate()
                        scope.launch {
                            delay(3000)
                            updateChecking = false
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 安全（指令审查 / 拦截）=====
            // 三个副作用（写 AppSettings / 同步守卫 / Toast）都在父层做回调，本节点纯 UI
            SettingsSecurityGroup(
                currentLevel = appSettings.securityLevel,
                guardInstalled = guardInstalled,
                onLevelClicked = remember(appSettings.securityLevel) {
                    {
                        val next = (appSettings.securityLevel + 1) % 4
                        appSettings.updateSecurityLevel(next)
                        // ── 档位即时生效 ──
                        // ① 失效「守卫就绪」缓存，避免 60s TTL 内仍用旧判定；
                        // ② 切到受保护档位（≥2）时确保守卫已安装（未装则用内置 zip 静默安装）；
                        // ③ 把档位同步为守卫 policy.conf 的 mode（0→off / 1→log / 2,3→enforce），
                        //    否则会出现「App 说标准防护、模块实际 mode=off」的口径不一致。
                        GuardModuleInstaller.invalidateReadyCache()
                        scope.launch {
                            if (GuardModuleInstaller.requiresRuntimeGuard(next)) {
                                if (GuardModuleInstaller.ensureInstalled(context)) guardInstalled = true
                            }
                            GuardModuleInstaller.syncPolicyMode(next)
                        }
                        val tip = when (next) {
                            AppSettings.SECURITY_OFF -> "已关闭：不审查 / 不拦截 / 不审计"
                            AppSettings.SECURITY_AUDIT_ONLY -> "审计：仅留痕，不拦截命令"
                            AppSettings.SECURITY_STANDARD -> "标准：黑名单拦截 + 终端硬规则 + 守卫 PATH"
                            AppSettings.SECURITY_MAXIMUM -> "最高：脚本默认非 Root 执行 + 全档收口"
                            else -> ""
                        }
                        Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                    }
                },
                onShowAuditLogClicked = remember(Unit) {
                    {
                        scope.launch {
                            val tail = SecurityAuditLog.readTail(50)
                            showAuditDialog = true
                            auditDialogLines = tail.lines().filter { it.isNotBlank() }
                        }
                    }
                },
                onInstallGuardClicked = remember(guardInstalled) {
                    {
                        // 守卫已就绪：直接吐司提示，不触发安装逻辑（避免覆盖已部署模块）
                        if (guardInstalled) {
                            Toast.makeText(context, "模块已就绪", Toast.LENGTH_SHORT).show()
                        } else {
                            installingGuard = true
                            scope.launch {
                                val (ok, msg) = withContext(Dispatchers.IO) { GuardModuleInstaller.install(context) }
                                installingGuard = false
                                if (ok) guardInstalled = true
                                Toast.makeText(
                                    context,
                                    if (ok) "守卫模块已部署，PATH 已生效"
                                    else "部署失败：${msg.take(120)}",
                                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            )

            }
        }
    }

    // ===== 审计日志弹窗 =====
    if (showAuditDialog) {
        AuroraWindowDialog(
            show = true,
            title = "审计日志（最近 50 条）",
            onDismissRequest = { showAuditDialog = false }
        ) {
            if (auditDialogLines.isEmpty()) {
                Text(
                    text = "暂无审计记录",
                    style = AuroraTextStyles.body2,
                    color = AuroraTokens.TextSecondary
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    auditDialogLines.forEach { line ->
                        Text(
                            text = line,
                            style = AuroraTextStyles.footnote2,
                            fontFamily = FontFamily.Monospace,
                            color = if (line.contains(" BLOCK ")) AuroraTokens.Error else AuroraTokens.Text
                        )
                    }
                }
            }
        }
    }

    // ===== 检查更新结果弹窗（三种结果统一以弹窗呈现）=====
    when (val s = updateState) {
        UpdateUiState.Idle,
        UpdateUiState.Checking -> {}

        is UpdateUiState.Available -> {
            AuroraWindowDialog(
                show = true,
                title = "发现新版本",
                onDismissRequest = { updateState = UpdateUiState.Idle }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "你目前的版本:${BuildConfig.VERSION_CODE}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "在线最新版本:${s.tag}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "你已落伍,尽快升级",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    Button(
                        onClick = { updateState = UpdateUiState.Idle },
                        colors = auroraPrimaryButtonColors(),
                        modifier = Modifier.auroraFilledButton()
                    ) {
                        Text(text = "稍后", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            openInBrowserOnly("https://github.com/yezijinn/shso/releases")
                            updateState = UpdateUiState.Idle
                        },
                        colors = auroraPrimaryButtonColors(),
                        modifier = Modifier.auroraFilledButton()
                    ) {
                        Text(text = "去更新", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        is UpdateUiState.UpToDate -> {
            AuroraWindowDialog(
                show = true,
                title = "提示",
                onDismissRequest = { updateState = UpdateUiState.Idle }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "你目前的版本:${BuildConfig.VERSION_CODE}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "在线最新版本:${s.tag}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "已是最新版本 无需更新",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { updateState = UpdateUiState.Idle },
                        colors = auroraPrimaryButtonColors(),
                        modifier = Modifier.auroraFilledButton()
                    ) {
                        Text(text = "知道了", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        UpdateUiState.NetworkError -> {
            AuroraWindowDialog(
                show = true,
                title = "提示",
                onDismissRequest = { updateState = UpdateUiState.Idle }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "你目前的版本:${BuildConfig.VERSION_CODE}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "在线最新版本:访问github.com失败",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "网络不佳",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "建议开启科学上网",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { updateState = UpdateUiState.Idle },
                        colors = auroraPrimaryButtonColors(),
                        modifier = Modifier.auroraFilledButton()
                    ) {
                        Text(text = "知道了", fontWeight = FontWeight.Bold)
                    }
                }
            }
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
                        text = "v${BuildConfig.VERSION_CODE}",
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
