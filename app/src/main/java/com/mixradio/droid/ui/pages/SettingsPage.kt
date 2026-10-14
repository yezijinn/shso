// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.core.net.toUri
import com.mixradio.droid.BuildConfig
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.mixradio.droid.data.EnvPack
import com.mixradio.droid.data.PermissionChecker
import com.mixradio.droid.data.RootService
import com.mixradio.droid.ui.components.dockBarBottomPadding
import com.mixradio.droid.ui.theme.AuroraAccentBar
import com.mixradio.droid.ui.theme.AuroraArrowPreference
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import com.mixradio.droid.ui.theme.auroraPrimaryButtonColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * 检查更新 UI 状态机（五态）。`source` 记录取数成功的源，供「去更新」选择跳转地址；
 * 成功源只用于跳转与日志，不写入用户可见文案。
 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val tag: Int, val source: String) : UpdateUiState
    data class Available(val tag: Int, val source: String) : UpdateUiState
    data object NetworkError : UpdateUiState
}

// 检查更新的进行态与结果必须跨配置变更存活：原先是 remember + rememberCoroutineScope，
// 旋转（或深色模式/字号变化）会重建整个组合 —— 作业被取消、状态归零，
// 于是「检查中」消失、结果弹窗永不出现，用户只看到点过的按钮又暗了下去。
// 与安装/批量任务的守卫同模式：状态与作业作用域都提到文件级（进程级）。
private var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
private var updateChecking by mutableStateOf(false)
private val updateJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** 「去更新」跳转：GitHub 走 releases 列表，Gitee 直达该 tag（发布 tag 为纯数字才能命中）。 */
private fun releasesUrl(source: String, latest: Int): String =
    if (source == "github") "https://github.com/yezijinn/shso/releases"
    else "https://gitee.com/yezijinn/shso/releases/tag/$latest"

/**
 * 两源取数：**Gitee 优先，GitHub 备选** —— 国内网络访问 GitHub 常超时/被重置，
 * 把它当首选会让「检查更新」在多数用户处直接落到网络异常；Gitee 可直连且公开仓库
 * 的 tags API 无需令牌。
 * 端点不同，不可合并：Gitee 走开放 API（`/api/v5/repos/{owner}/{repo}/tags`，JSON；
 * 网页版 `/tags` 返回 405），GitHub 走 `/tags`（HTML）。
 */
private val UPDATE_SOURCES = listOf(
    Triple(
        "gitee",
        "https://gitee.com/api/v5/repos/yezijinn/shso/tags",
        Regex(""""name"\s*:\s*"([^"]+)"""")
    ),
    Triple(
        "github",
        "https://github.com/yezijinn/shso/tags",
        // 仓库名在 GitHub 页面的链接里是全小写，正则必须忽略大小写，
        // 否则「有标签」会被误判成「无标签」→ 假的网络异常。
        Regex("""yezijinn/shso/(?:tree|releases/tag)/([^"'<>?#\s]+)""", RegexOption.IGNORE_CASE)
    ),
)

/**
 * 会声明处理 `http/https` 但并非浏览器的应用（精确包名）。用于「去更新 / 访问 Github」
 * 挑选浏览器时排除——例如 GitHub 客户端接管 github.com 后走应用内 WebView。
 */
private val NON_BROWSER_HANDLERS = setOf("com.github.android")

/**
 * 抓取远端最大的发布日标签，返回 (最新日期, 成功源)；**Gitee 优先、GitHub 备选**。
 *
 * 归一化规则两源一致：**只认 6..8 位纯数字**，且**不剥离 `v` 前缀** ——
 * 发布 tag 的合法形式只有纯数字，兼容 `v20260904` 这类违规写法
 * 等于让发布侧的问题长期隐藏（且 Gitee 的「去更新」链接按纯数字拼，带 `v` 必然 404）。
 * 两源皆失败才抛异常，由调用方转「网络异常」。
 */
private suspend fun fetchLatestDateTag(): Pair<Int, String> = withContext(Dispatchers.IO) {
    var lastError: Exception? = null
    for ((name, url, pat) in UPDATE_SOURCES) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "shso-update-check")
            }
            var latest: Int? = null
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    lastError = IOException("$name HTTP ${conn.responseCode}")
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    latest = pat.findAll(body).mapNotNull { m ->
                        m.groupValues[1]
                            .takeIf { it.length in 6..8 && it.all(Char::isDigit) }
                            ?.toIntOrNull()
                    }.maxOrNull()
                    if (latest == null) lastError = IOException("$name 无纯数字日期标签")
                }
            } finally {
                conn.disconnect()
            }
            if (latest != null) return@withContext latest to name
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw IOException("all update sources unreachable", lastError)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    appSettings: AppSettings
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 权限状态（首次进入即同步初查，前台返回时统一刷新）
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
        // minSdk 26 即 Android 8，canRequestPackageInstalls 始终可用，无需版本分支
        mutableStateOf(context.packageManager.canRequestPackageInstalls())
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
        permissionInstallGranted = context.packageManager.canRequestPackageInstalls()
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
    var showPackRemove by remember { mutableStateOf(false) }

    // 检查更新状态（updateState / updateChecking / updateJobScope 均为文件级，见上）

    fun checkForUpdate() {
        if (updateState == UpdateUiState.Checking) return
        updateState = UpdateUiState.Checking
        updateJobScope.launch {
            try {
                val (latest, source) = fetchLatestDateTag()
                val local = BuildConfig.VERSION_CODE
                Log.d("ShsoUpdate", "source=$source latest=$latest local=$local")
                updateState = if (latest > local) {
                    UpdateUiState.Available(latest, source)
                } else {
                    UpdateUiState.UpToDate(latest, source)
                }
            } catch (e: CancellationException) {
                // 取消不是「网络错误」，必须原样抛出，否则会把取消记成失败结果。
                throw e
            } catch (_: Exception) {
                updateState = UpdateUiState.NetworkError
            } finally {
                // 熄灭时机与结果对齐：两源各 10s 超时，固定 3s 熄灯会让用户误以为已经失败。
                updateChecking = false
            }
        }
    }

    fun openInBrowserOnly(url: String) {
        try {
            val uri = url.toUri()
            val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri()).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val resolveInfos = context.packageManager.queryIntentActivities(browserIntent, 0)
            // 只按**精确包名**排除已知的链接拦截类应用（GitHub 客户端会接管 github.com，
            // 但它是应用内浏览器，不是浏览器）。不能用 `contains("github")` 之类的子串启发式：
            // 它会连 `com.github.android` 之外的无关包一并排除，也会漏掉包名不含该词的拦截类应用。
            val browserPackages = resolveInfos.map { it.activityInfo.packageName }
                .filterNot { it in NON_BROWSER_HANDLERS }

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
                val generalIntent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
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
                            .minimumInteractiveComponentSize()
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
                .padding(bottom = dockBarBottomPadding(appSettings.hideDockBar))
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

            // 界面开关：隐藏底栏 / 左右提示
            SettingsInterfaceGroup(appSettings = appSettings)

            // 检查更新
            // 右侧胶囊与权限行一致；点击胶囊/整行触发检查，亮起 3 秒后自动回关
            AuroraArrowPreference(
                title = "检查更新",
                summary = "检查 gitee / github 是否发布了新的版本",
                statusSwitch = updateChecking,
                statusSwitchEnabled = true,
                onClick = {
                    if (!updateChecking) {
                        updateChecking = true
                        // 熄灭由 checkForUpdate 的 finally 负责（结果返回即熄灭），
                        // 不再固定 3 秒 —— 否则两源超时叠加时用户会误判为失败。
                        checkForUpdate()
                    }
                }
            )

            // 扩展环境包：未安装点击下载；下载/解压中显示进度；已安装点击可删除
            val packState = EnvPack.state
            AuroraArrowPreference(
                title = "扩展环境包",
                summary = when (packState) {
                    EnvPack.State.NotInstalled -> "下载 GNU 工具、git、python、openssl 等（约 35 MB）"
                    EnvPack.State.Downloading -> "下载中 ${EnvPack.progress}%"
                    EnvPack.State.Extracting -> "解压中…"
                    EnvPack.State.Installed -> "已安装，点击可删除"
                    is EnvPack.State.Failed -> "${packState.message}，点击重试"
                },
                statusSwitch = packState is EnvPack.State.Downloading || packState is EnvPack.State.Extracting,
                statusSwitchEnabled = true,
                onClick = {
                    when (packState) {
                        is EnvPack.State.Installed -> showPackRemove = true
                        EnvPack.State.Downloading, EnvPack.State.Extracting -> {}
                        else -> EnvPack.startDownload(context)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            }
        }
    }

    if (showPackRemove) {
        AuroraWindowDialog(
            show = true,
            title = "提示",
            onDismissRequest = { showPackRemove = false }
        ) {
            Text(
                text = "确认删除扩展环境包？删除后相关命令将不可用。",
                style = AuroraTextStyles.body2,
                color = AuroraTokens.Text
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "稍后",
                    style = AuroraTextStyles.body2,
                    color = AuroraTokens.TextSecondary,
                    modifier = Modifier
                        .clickable { showPackRemove = false }
                        .minimumInteractiveComponentSize()
                )
                Text(
                    text = "删除",
                    style = AuroraTextStyles.body2,
                    color = AuroraTokens.Error,
                    modifier = Modifier
                        .clickable {
                            showPackRemove = false
                            EnvPack.remove(context)
                        }
                        .minimumInteractiveComponentSize()
                )
            }
        }
    }

    // 检查更新结果弹窗（三种结果统一以弹窗呈现）
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
                        text = "你目前的版本：${BuildConfig.VERSION_CODE}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "在线最新版本：${s.tag}",
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
                            openInBrowserOnly(releasesUrl(s.source, s.tag))
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
                        text = "你目前的版本：${BuildConfig.VERSION_CODE}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "在线最新版本：${s.tag}",
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
                        text = "你目前的版本：${BuildConfig.VERSION_CODE}",
                        style = AuroraTextStyles.body2,
                        color = AuroraTokens.Text,
                        textAlign = TextAlign.Start
                    )
                    Text(
                        text = "在线最新版本：访问 gitee.com / github.com 失败",
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
