// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 媒体播放接线、生命周期与「能真的播出来」的前置条件守卫。 */
class MediaPlayerWiringContractTest {

    private val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
    private val dialog =
        File("src/main/java/com/mixradio/droid/ui/components/MediaPlayerDialog.kt").readText()
    private val source = File("src/main/java/com/mixradio/droid/data/MediaSource.kt").readText()

    @Test
    fun `动作菜单与外部唤起都接进播放器`() {
        assertTrue("菜单行须按媒体判定", page.contains("if (item.isMedia)"))
        assertTrue("须有播放文案", page.contains("\"播放视频\"") && page.contains("\"播放音频\""))
        assertTrue("须有弹窗宿主", page.contains("if (showMediaPlayerDialog && mediaPlayerTarget != null)"))
        assertTrue("外部唤起须直通播放", page.contains("ExternalAction.PLAY_MEDIA ->"))
    }

    @Test
    fun `播放器必须释放底层资源`() {
        assertTrue("必须 release", dialog.contains("mp.release()"))
        assertTrue("必须清 surface", dialog.contains("mp.setDisplay(null)"))
        assertTrue(
            "释放必须写在协程收尾，否则关窗即泄漏",
            dialog.contains("awaitCancellation()") && dialog.contains("} finally {")
        )
        assertTrue("必须有错误监听（否则 unsupported 静默无声）", dialog.contains("setOnErrorListener"))
        assertTrue("长按加速须下发倍速", dialog.contains("setPlaybackParams"))
        assertTrue("副本必须在组合销毁时删除（含旋屏）", dialog.contains("MediaSource.cleanup(copy)"))
    }

    @Test
    fun `长按加速不得在指针域内用超时`() {
        // 回归：withTimeout 会取消 awaitPointerEvent 的续体，抬起事件随之丢失，
        // 倍速可能永远停在 2×。计时必须放在独立协程。
        assertFalse(
            "指针域内不得出现 withTimeout/withTimeoutOrNull",
            dialog.contains("withTimeoutOrNull(") || dialog.contains("withTimeout(")
        )
        assertTrue("须有独立的加速计时 effect", dialog.contains("LaunchedEffect(pressed)"))
        assertTrue("须以按下时间为基准判定点按/长按", dialog.contains("SystemClock.uptimeMillis()"))
        assertTrue("松开必须复位", dialog.contains("applySpeed(1f)"))
        assertTrue(
            "抬起判定须用官方原语（自写循环会把 changes 缺 id 误判成抬起）",
            dialog.contains("waitForUpOrCancellation(")
        )
    }

    @Test
    fun `倍速不得残留且不得先读回播放器参数`() {
        // 回归 1：getPlaybackParams 在 prepare 前抛 IllegalStateException，
        // 被 runCatching 吞掉即「倍速静默不生效」。
        assertFalse("不得读 mp.playbackParams", dialog.contains("mp.playbackParams"))
        assertTrue("须新建参数对象", dialog.contains("PlaybackParams()"))
        // 回归 2：applySpeed 在非播放态只写 speed 不下发，
        // restart 时若只看 speed != 1f 就会漏发 → UI 1× 实际 2×。
        assertTrue("须记录播放器内实际生效的倍速", dialog.contains("appliedSpeed"))
        assertTrue(
            "start 后须补下发差额",
            dialog.contains("if (appliedSpeed != speed) pushSpeed(speed)")
        )
    }

    @Test
    fun `surface 重建不得把新画面摘掉`() {
        assertTrue(
            "destroyed 必须校验 holder 身份，否则旧 view 的 destroyed 会清掉新 surface",
            dialog.contains("displayTarget === holder")
        )
        assertTrue(
            "AndroidView 必须能解绑回调",
            dialog.contains("addOnAttachStateChangeListener") && dialog.contains("removeCallback")
        )
    }

    @Test
    fun `进度跳转须重建音轨且不得干扰播完续播`() {
        // 回归：起播阶段 seek 会让部分音轨编码的解码器失效 → 画面正常但静音，
        // 关窗重进才恢复。修复须等真正播放后 pause→seek→seekComplete 重建音轨，
        // 并用 userSeekInFlight 守卫避免播完 seekTo(0) 被误 start 死循环。
        assertTrue("须登记跳转目标", dialog.contains("pendingSeekMs = target"))
        assertTrue(
            "须等真正开始播放后才 seek",
            dialog.contains("while (waited < 2000 && !runCatching { mp.isPlaying }")
        )
        assertTrue("seek 前须 pause", dialog.contains("if (wasPlaying) runCatching { mp.pause() }"))
        assertTrue("seek 前须把倍速归 1", dialog.contains("speedParams(1f)"))
        assertTrue("seek 后须恢复用户倍速", dialog.contains("pushSpeed(speed)"))
        assertTrue(
            "seekComplete 回调须 setVolume 重建音轨并恢复播放",
            dialog.contains("p.setVolume(1f, 1f)") && dialog.contains("if (!p.isPlaying) p.start()")
        )
        assertTrue(
            "须用 userSeekInFlight 守卫，避免播完 seekTo(0) 被误 start 死循环",
            dialog.contains("if (!userSeekInFlight) return@setOnSeekCompleteListener")
        )
        assertTrue(
            "续播跳转也须走延迟队列，不得在 prepared 回调里直接 seek",
            !dialog.contains("runCatching { p.seekTo(resume) }")
        )
        assertTrue("prepare 前拖动须暂存而非丢弃", dialog.contains("pendingSeekMs = resume"))
        assertTrue("播放器重建须清进行中标志", dialog.contains("userSeekInFlight = false"))
    }

    @Test
    fun `视频旋转按钮须锁横屏且关闭时还原方向`() {
        // 旋转是 Activity 级状态：锁横屏必须经 requestedOrientation；
        // 关闭播放器必须还原 UNSPECIFIED，否则退出后整个应用横屏锁死。
        assertTrue("须声明横屏旋转", dialog.contains("SCREEN_ORIENTATION_LANDSCAPE"))
        assertTrue("须还原系统方向", dialog.contains("SCREEN_ORIENTATION_UNSPECIFIED"))
        assertTrue("须有旋转状态且跨重建保留", dialog.contains("landscapeLocked by rememberSaveable"))
        assertTrue("关闭时须还原方向", dialog.contains("restoreOrientation(context)"))
        assertTrue(
            "关闭按钮与返回键都走还原，不得直接 onDismiss",
            !dialog.contains("clickable(onClick = onDismiss)") &&
                dialog.contains("onDismissRequest = { dismissPlayer() }")
        )
        assertTrue("旋转按钮仅视频可见", dialog.contains("if (isVideo)"))
    }

    @Test
    fun `root 复制必须改回应用属主`() {
        // 回归：cp 以 root 执行、文件属主是 root，只 chmod 600 应用读不了，播放必失败。
        assertTrue("cp 后必须 chown 回应用 uid", source.contains("chown"))
        assertTrue("须用 Process.myUid()", source.contains("Process.myUid()"))
        assertTrue("复制后必须校验可读", source.contains("canRead()"))
        assertTrue(
            "chown 不得用 && 串在 chmod 前（失败会连 chmod 一起跳过）",
            source.contains("chown ${'$'}uid:${'$'}uid ${'$'}dst 2>/dev/null || chown ${'$'}uid ${'$'}dst 2>/dev/null")
        )
    }

    @Test
    fun `媒体判定不得依赖 Android 运行时`() {
        val types = File("src/main/java/com/mixradio/droid/data/MediaTypes.kt").readText()
        assertFalse("MediaTypes 必须纯 JVM 可测", types.contains("import android."))
        assertFalse(
            "FileIconLabel 必须纯 JVM 可测",
            File("src/main/java/com/mixradio/droid/data/FileIconLabel.kt")
                .readText().contains("import android.")
        )
    }
}
