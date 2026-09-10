// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mixradio.droid.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 命令执行的前台「保活」服务。
 *
 * 作用：当应用切到后台（被用户退回桌面 / 进入最近任务列表）时，维持本进程在系统眼中的
 * 前台优先级，避免激进 ROM 在后台立即回收进程，从而让长耗时脚本 / 下载 / 编译等
 * 子进程（由 RootService 以独立 Process 承载）得以继续运行。
 *
 * 实现要点：
 * - 本服务不承载执行逻辑（执行仍完全由 RootService 的 executionJob 负责），只做前台哨兵 +
 *   通知展示，从根上不触碰 RootService 的代际 / 取消 / 清理语义，改动最小。
 * - minSdk 26 起使用 `startForeground`，无需运行时通知权限跳转（通知渠道为 IMPORTANCE_LOW，
 *   系统允许静默展示）。
 * - 服务自行轮询 RootService.isTaskRunning：任务结束（正常退出 / 结束进程 / 重启）后
 *   stopSelf 自我回收，并由 onDestroy 显式移除前台状态与通知。
 */
class ExecutionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_KILL) {
            RootService.killCurrentProcess()
            return START_NOT_STICKY
        }
        startAsForeground()
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            // 任务进行中即循环刷新通知；任务结束即退出并回收前台服务。
            while (isActive && RootService.isTaskRunning) {
                updateNotification()
                delay(UPDATE_INTERVAL_MS)
            }
            // isTaskRunning 已为 false（任务完成 / 结束 / 重启），回收服务。
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        pollJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "任务保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示正在后台运行的任务"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            // targetSdk 35 / Android 14+ 需要声明前台服务类型，与 Manifest 中 foregroundServiceType 对齐。
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification() {
        val notification = buildNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val taskName = RootService.currentTaskName ?: "任务"
        val pid = RootService.processPid
        val elapsedMs = if (RootService.taskStartTime > 0) {
            System.currentTimeMillis() - RootService.taskStartTime
        } else 0L
        val elapsed = formatDuration(elapsedMs)
        val rootTag = if (RootService.isRootGranted == true) "Root" else "非 Root"

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val killIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ExecutionForegroundService::class.java).setAction(ACTION_KILL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)  // 使用系统下载态图标，避免依赖 app 自身资源
            .setContentTitle("shso · 任务运行中：$taskName")
            .setContentText("耗时 $elapsed · $rootTag" + if (pid > 0) " · PID $pid" else "")
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "结束进程", killIntent)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else -> String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    companion object {
        private const val CHANNEL_ID = "shso_task_keepalive"
        private const val NOTIFICATION_ID = 0x1337
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val ACTION_KILL = "com.mixradio.droid.ACTION_KILL_TASK"

        /** 任务启动时在后台拉起本服务以维持进程前台优先级。 */
        fun start(context: Context) {
            try {
                val intent = Intent(context, ExecutionForegroundService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }
    }
}
