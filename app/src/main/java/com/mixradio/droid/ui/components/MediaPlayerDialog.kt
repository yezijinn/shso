// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mixradio.droid.data.FileItem
import com.mixradio.droid.data.MediaSource
import com.mixradio.droid.data.formatMediaTime
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraThinSlider
import com.mixradio.droid.ui.theme.AuroraTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 按住多久开始加速。短于它视为「点一下」= 播放/暂停。 */
private const val LONG_PRESS_BOOST_MS = 400L

/**
 * 点按判定的死区：与 [LONG_PRESS_BOOST_MS] 的计时来自两套时钟
 * （`delay` 调度 vs 指针事件的 `SystemClock`），阈值完全相等时，
 * 恰在 400ms 抬起会出现「boost 已生效、同时被判成点按 → 又暂停」。
 * 留 40ms 死区后两者不可能同时成立。
 */
private const val TAP_SLACK_MS = 40L

/** 长按加速倍率。 */
private const val BOOST_SPEED = 2f

/**
 * 媒体播放弹窗（视频 + 音频）。
 *
 * 依赖取向：只用 framework 的 [MediaPlayer]，不引入 media3/exoplayer ——
 * 后者的 dex + 原生解码器会把 APK 顶大数 MB，而本应用要的是「最基本的播放」。
 *
 * 手势语义（用户要的是「长按屏幕加速」）：
 * - 按下后 **<400ms 抬起** = 点一下 → 播放 / 暂停；
 * - 按下 **≥400ms** = 长按 → 立刻升到 2×，**松开即回 1×**（按住持续加速，不是「切换倍速」）。
 *
 * 生命周期要点：
 * - 播放器在 `LaunchedEffect(playablePath)` 内创建，`awaitCancellation()` + `finally` 保证
 *   关窗 / 旋屏 / 进程回收时一定 `release()`；
 * - 视频用 [SurfaceView] 经 [AndroidView] 嵌入，**surface 有效后才 start()**，
 *   否则部分机型只有声音没有画面；
 * - 离开前台（ON_STOP）暂停，不在后台继续出声（本应用没有前台服务与通知）。
 *
 * @param item 待播放文件（只需 name/path/size/扩展名判定）
 * @param onDismiss 关闭回调
 */
@Composable
fun MediaPlayerDialog(item: FileItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isVideo = item.isVideo

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    /** 用户「请求」的倍速（松开长按即回 1×）。 */
    var speed by remember { mutableFloatStateOf(1f) }
    /** 播放器内部**实际**生效的倍速：只在这里记录成功下发过的值。 */
    var appliedSpeed by remember { mutableFloatStateOf(1f) }
    var videoAspect by remember { mutableStateOf<Float?>(null) }
    var playablePath by remember { mutableStateOf<String?>(null) }
    var tempCopy by remember { mutableStateOf<File?>(null) }
    var displayTarget by remember { mutableStateOf<SurfaceHolder?>(null) }
    /** 已 prepare 但还在等 surface：等到了才 start()。 */
    var needsStart by remember { mutableStateOf(false) }
    /** 长按加速期间为 true（由手势层写，速度 effect 读）。 */
    var pressed by remember { mutableStateOf(false) }

    // 旋转重建后从上次位置续播。播放器本身必然重建，位置单独保存即可。
    var resumeAtMs by rememberSaveable { mutableIntStateOf(0) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var focusRequest by remember { mutableStateOf<AudioFocusRequest?>(null) }

    val focusListener = remember {
        AudioManager.OnAudioFocusChangeListener { change ->
            // 来电 / 其它应用抢占：立刻暂停，不与之抢声音。
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                player?.let { mp -> runCatching { if (mp.isPlaying) mp.pause() } }
                playing = false
                needsStart = false
            }
        }
    }

    val surfaceCallback = remember(isVideo) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                displayTarget = holder
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                displayTarget = holder
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // 只清「自己这一个」：AndroidView 重建时，旧 SurfaceView 的 destroyed
                // 可能晚于新 SurfaceView 的 created 派发。无脑置 null 会把刚就绪的
                // 新 surface 摘掉 —— needsStart 又已是 false，画面永久黑屏。
                if (displayTarget === holder) displayTarget = null
            }
        }
    }

    fun takeAudioFocus() {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAudioAttributes(isVideo))
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        runCatching { audioManager.requestAudioFocus(request) }
    }

    /**
     * 把 [target] 真正下发到播放器。
     *
     * 只在**正在播放**时下发：对已暂停的 MediaPlayer 调 `setPlaybackParams` 会让部分机型
     * 把状态改成 started（用户明明点了暂停却又出声）。下发失败不更新 [appliedSpeed]，
     * 由下一次 [startPlayback] 重试。
     *
     * 刻意不读播放器当前的 PlaybackParams：该 getter 在 prepare 前会抛 `IllegalStateException`，
     * 被外层 `runCatching` 吞掉就等于「倍速静默不生效」。新建一个参数对象即可。
     */
    fun pushSpeed(target: Float) {
        val mp = player ?: return
        if (!prepared) return
        if (!runCatching { mp.isPlaying }.getOrDefault(false)) return
        if (runCatching { mp.setPlaybackParams(speedParams(target)) }.isSuccess) {
            appliedSpeed = target
        }
    }

    /** 记录「请求倍速」并尝试立即生效；不能生效的差额留给 [startPlayback] 补。 */
    fun applySpeed(target: Float) {
        speed = target
        pushSpeed(target)
    }

    fun pausePlayback() {
        val mp = player ?: return
        runCatching { if (mp.isPlaying) mp.pause() }
        playing = false
        needsStart = false
    }

    fun startPlayback() {
        val mp = player ?: return
        if (!prepared) return
        takeAudioFocus()
        if (!runCatching { mp.start() }.isSuccess) return
        playing = true
        needsStart = false
        // start() 之后补下发差额：MediaPlayer 会**保留**上一次的 PlaybackParams。
        // 若只在 speed != 1f 时下发，「按住加速中播完/切后台 → 松开 → 再点播放」
        // 就会出现 UI 1× 而实际 2×（applySpeed 在非播放态只写 speed、不下发）。
        if (appliedSpeed != speed) pushSpeed(speed)
    }

    /** prepare 完成与 surface 就绪都可能后到，两边都调它；未就绪就挂起等到齐。 */
    fun attachSurfaceAndStart() {
        val mp = player ?: return
        if (!prepared) return
        if (isVideo) {
            val holder = displayTarget
            if (holder == null || !holder.surface.isValid) {
                needsStart = true
                return
            }
            runCatching { mp.setDisplay(holder) }
        }
        startPlayback()
    }

    // ── 1. 解析可播路径（可能经 ROOT 复制到缓存）────────────────────────
    LaunchedEffect(item.path) {
        loading = true
        errorMessage = null
        val result = withContext(Dispatchers.IO) {
            MediaSource.resolve(context, item.path, item.size)
        }
        when (result) {
            is MediaSource.Result.Ready -> {
                tempCopy = result.tempCopy
                playablePath = result.path
            }
            is MediaSource.Result.Failed -> {
                errorMessage = result.reason
                loading = false
            }
        }
    }

    // ── 2. 创建 / 释放播放器 ────────────────────────────────────────────
    LaunchedEffect(playablePath, isVideo) {
        val path = playablePath ?: return@LaunchedEffect
        val mp = MediaPlayer()
        mp.setAudioAttributes(mediaAudioAttributes(isVideo))
        mp.setOnPreparedListener { p ->
            prepared = true
            loading = false
            durationMs = runCatching { p.duration }.getOrDefault(0).coerceAtLeast(0)
            val resume = resumeAtMs
            if (resume > 0 && (durationMs <= 0 || resume < durationMs - 500)) {
                runCatching { p.seekTo(resume) }
                positionMs = resume
            }
            attachSurfaceAndStart()
        }
        mp.setOnCompletionListener {
            playing = false
            positionMs = 0
            // resumeAtMs 必须一起归零：否则旋屏后从「片尾前一秒」续播，看起来像卡死。
            resumeAtMs = 0
            needsStart = false
            // 播完回到起点：再点播放从头开始，不留在片尾一动不动
            runCatching { it.seekTo(0) }
        }
        mp.setOnErrorListener { _, what, extra ->
            errorMessage = playbackErrorText(what, extra)
            loading = false
            prepared = false
            playing = false
            needsStart = false
            true
        }
        mp.setOnVideoSizeChangedListener { _, width, height ->
            if (width > 0 && height > 0) videoAspect = width.toFloat() / height.toFloat()
        }
        player = mp
        // 新播放器的参数是默认 1×，账面同步，避免沿用上一个实例的记录。
        appliedSpeed = 1f
        try {
            mp.setDataSource(path)
            mp.prepareAsync()
        } catch (e: Exception) {
            errorMessage = "无法打开该文件：${e.message ?: "未知错误"}"
            loading = false
            runCatching { mp.release() }
            if (player === mp) player = null
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            if (player === mp) player = null
            prepared = false
            playing = false
            needsStart = false
            runCatching { mp.setDisplay(null) }
            runCatching { if (mp.isPlaying) mp.pause() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
    }

    // ── 3. surface 绑定（视频）──────────────────────────────────────────
    LaunchedEffect(player, displayTarget) {
        val mp = player ?: return@LaunchedEffect
        val holder = displayTarget
        if (holder != null && holder.surface.isValid) {
            runCatching { mp.setDisplay(holder) }
            if (needsStart) attachSurfaceAndStart()
        } else {
            runCatching { mp.setDisplay(null) }
        }
    }

    // ── 4. 长按加速：按住 >400ms 升 2×，松开回 1× ───────────────────────
    // 计时放在独立协程里，指针域只管 down/up —— 绝不在 awaitPointerEventScope 内用
    // withTimeout：超时取消会打断指针事件续体，倍速可能永远回不到 1×。
    LaunchedEffect(pressed) {
        if (!pressed) {
            applySpeed(1f)
            return@LaunchedEffect
        }
        delay(LONG_PRESS_BOOST_MS)
        applySpeed(BOOST_SPEED)
    }

    // ── 5. 进度 ticker（同时把「播放器真实状态」同步回 UI）───────────────
    LaunchedEffect(prepared) {
        while (prepared) {
            val mp = player
            if (mp != null) {
                val nowPlaying = runCatching { mp.isPlaying }.getOrDefault(false)
                if (nowPlaying != playing) playing = nowPlaying
                if (nowPlaying && scrubFraction == null) {
                    positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
                    val dur = runCatching { mp.duration }.getOrDefault(durationMs)
                    if (dur > 0 && dur != durationMs) durationMs = dur
                    if (positionMs - resumeAtMs >= 1000 || resumeAtMs - positionMs >= 1000) {
                        resumeAtMs = positionMs
                    }
                }
            }
            delay(200)
        }
    }

    // ── 6. 拖动进度：停手 160ms 后才 seek，位置回填完再交还 ticker ───────
    LaunchedEffect(scrubFraction) {
        val fraction = scrubFraction ?: return@LaunchedEffect
        delay(160)
        val target = if (durationMs > 0) (fraction * durationMs).toInt() else 0
        val mp = player ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        runCatching { mp.seekTo(target) }
        positionMs = target
        resumeAtMs = target
        delay(240)
        scrubFraction = null
    }

    // ── 7. 进后台暂停 ──────────────────────────────────────────────────
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) pausePlayback()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 8. 放弃音频焦点 ────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        }
    }

    // ── 9. 删除 ROOT 副本：以 tempCopy 为键，组合被销毁（含旋屏）即触发 ──
    // tempCopy 由 null 变 File 时，旧 effect 的 onDispose 拿到 null（空操作），
    // 新 effect 持有真实 File；关窗/旋屏时该 composable 离开组合，onDispose
    // 以真实 File 执行 → 删除。旋屏后新组合会重新 resolve（重新复制一次），旧副本已删。
    DisposableEffect(tempCopy) {
        val copy = tempCopy
        onDispose { MediaSource.cleanup(copy) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AuroraTokens.Surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = AuroraTextStyles.body2,
                            color = AuroraTokens.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                errorMessage != null -> "播放失败"
                                loading -> "正在加载…"
                                isVideo -> "视频 · ${item.formattedSize}"
                                else -> "音频 · ${item.formattedSize}"
                            },
                            style = AuroraTextStyles.footnote2,
                            color = AuroraTokens.TextSecondary
                        )
                    }
                    Text(
                        text = "✕",
                        style = AuroraTextStyles.title3,
                        color = AuroraTokens.Text,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                // 画面区：点一下播放/暂停；按住 ≥400ms 升到 2×，松开回 1×
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downAt = SystemClock.uptimeMillis()
                                pressed = true
                                var released = false
                                try {
                                    // 官方原语：正常抬起返回 change，被取消/被其它组件消费返回 null。
                                    // 自己写 awaitPointerEvent 循环会把「changes 里没有该 id」误判成抬起。
                                    released = waitForUpOrCancellation() != null
                                } finally {
                                    // 手势被取消（父级滚动/窗口收起）也要把倍速放回去
                                    pressed = false
                                }
                                if (released &&
                                    SystemClock.uptimeMillis() - downAt <
                                    LONG_PRESS_BOOST_MS - TAP_SLACK_MS
                                ) {
                                    if (playing) pausePlayback() else startPlayback()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isVideo) {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val ratio = videoAspect
                            val videoModifier = if (ratio != null && ratio > 0f && maxHeight.value > 0f) {
                                val containerRatio = maxWidth.value / maxHeight.value
                                if (ratio >= containerRatio) {
                                    Modifier
                                        .fillMaxWidth()
                                        .height((maxWidth.value / ratio).dp)
                                } else {
                                    Modifier
                                        .fillMaxHeight()
                                        .width((maxHeight.value * ratio).dp)
                                }
                            } else {
                                Modifier.fillMaxSize()
                            }
                            AndroidView(
                                factory = { ctx ->
                                    SurfaceView(ctx).also { view ->
                                        val callback = surfaceCallback
                                        view.holder.addCallback(callback)
                                        // AndroidView 重建 / 关闭时把回调摘掉：holder 是 view 的
                                        // 内部对象，旧 view 不销毁就仍会把事件打到共享的
                                        // displayTarget 上（见 surfaceDestroyed 的时序说明）。
                                        view.addOnAttachStateChangeListener(
                                            object : View.OnAttachStateChangeListener {
                                                override fun onViewAttachedToWindow(v: View) = Unit
                                                override fun onViewDetachedFromWindow(v: View) {
                                                    // v 是 View 类型（无 holder 属性），用外层捕获的 SurfaceView 摘回调。
                                                    view.holder.removeCallback(callback)
                                                    v.removeOnAttachStateChangeListener(this)
                                                }
                                            }
                                        )
                                    }
                                },
                                modifier = videoModifier
                            )
                        }
                    } else {
                        // 音频没有画面：给一个可点的占位，长按加速同样落在这里
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = item.realExtension.uppercase(Locale.ROOT),
                                style = AuroraTextStyles.headline2,
                                color = AuroraTokens.AccentBright
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "音频 · 点一下暂停 · 长按 2× 加速",
                                style = AuroraTextStyles.footnote2,
                                color = AuroraTokens.TextSecondary
                            )
                        }
                    }

                    if (loading) {
                        CircularProgressIndicator(color = AuroraTokens.Accent)
                    }
                    errorMessage?.let { message ->
                        Text(
                            text = "$message\n\n关闭后可用动作菜单的「打开方式」交给第三方播放器",
                            style = AuroraTextStyles.body2,
                            color = AuroraTokens.Error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                // 进度条：拖动期间用本地比例显示，不跟着旧 position 抖回去
                val shownFraction = scrubFraction
                    ?: if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatMediaTime(positionMs),
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                    AuroraThinSlider(
                        value = shownFraction,
                        onValueChange = { scrubFraction = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )
                    Text(
                        text = formatMediaTime(durationMs),
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                }

                // 操作行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (playing) "暂停" else "播放",
                        style = AuroraTextStyles.body2,
                        color = if (prepared) AuroraTokens.Accent else AuroraTokens.TextDisabled,
                        modifier = Modifier
                            .clickable(enabled = prepared) {
                                if (playing) pausePlayback() else startPlayback()
                            }
                            .padding(horizontal = 6.dp, vertical = 10.dp)
                    )
                    Text(
                        text = "${speed.toInt()}×",
                        style = AuroraTextStyles.footnote2,
                        color = if (speed > 1f) AuroraTokens.AccentBright else AuroraTokens.TextSecondary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "点一下暂停 · 长按 2× 加速",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextUnselected
                    )
                }
            }
        }
    }
}

/** 视频/音频的音频属性（影响音量键归属与系统混音策略）。 */
private fun mediaAudioAttributes(video: Boolean): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(
            if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC
        )
        .build()

/** 倍速参数：`setPitch(1f)` 表示请求保持原音高（是否真做时间拉伸取决于设备解码器）。 */
private fun speedParams(target: Float): PlaybackParams =
    PlaybackParams().setSpeed(target).setPitch(1f)

/**
 * MediaPlayer 错误码 → 用户能据此行动的中文说明。
 *
 * 不能统一成「播放失败」：格式不支持要引导去「打开方式」，
 * I/O 错误要提示文件/存储问题，两者处置完全不同。
 */
private fun playbackErrorText(what: Int, extra: Int): String = when (what) {
    MediaPlayer.MEDIA_ERROR_UNSUPPORTED ->
        "系统播放器不支持该格式：容器或编码不在本机解码器范围内"
    MediaPlayer.MEDIA_ERROR_TIMED_OUT ->
        "播放超时：文件可能损坏或存储无响应"
    else -> when (extra) {
        MediaPlayer.MEDIA_ERROR_IO -> "读取文件失败（I/O 错误）"
        MediaPlayer.MEDIA_ERROR_MALFORMED -> "文件格式损坏或不完整"
        MediaPlayer.MEDIA_ERROR_UNSUPPORTED ->
            "系统播放器不支持该格式：容器或编码不在本机解码器范围内"
        else -> "播放失败（$what/$extra）"
    }
}
