// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 集中实现设置页「权限」分组所需的实时检测逻辑。
 *
 * 所有方法均为同步轻量检测或挂起 IO 检测，可安全地在主线程/协程中调用；
 * UI 层不应自行实现 su 探测等阻塞逻辑。
 */
object PermissionChecker {

    /** 厂商 ROM「后台弹出界面」对应的 AppOps 内部 op 字符串。 */
    private const val BACKGROUND_ACTIVITY_START_OP = "android:allow-in-background-activity-start"

    /** su 探测超时，防止 Magisk 授权弹窗长时间卡住检测。 */
    private const val ROOT_CHECK_TIMEOUT_MS = 1500L

    /**
     * 「访问存储空间」是否已获得。
     *
     * - Android 11+（SDK >= 30）：使用 [Environment.isExternalStorageManager]。
     * - Android 10 及以下：READ/WRITE_EXTERNAL_STORAGE 运行时权限是否全部授予。
     */
    fun isStorageGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            readGranted && writeGranted
        }
    }

    /**
     * 返回 Android 10 及以下尚未授予的外部存储运行时权限列表（用于逐个请求）。
     * Android 11+ 一律返回空列表（应走“所有文件访问”系统设置页）。
     */
    fun missingLegacyStoragePermissions(context: Context): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return emptyList()
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return missing
    }

    /**
     * 「省电策略 / 耗电保护」是否已获得。
     * 系统服务缺失时按已获得处理，避免在异常 ROM 上误报。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 「后台弹出页面」是否已允许（仅查本应用自身 uid/包名）。
     *
     * 字符串 op 查询自 Android 10（Q）起可用；Android 9 及以下、原生 ROM 无此开关、
     * 查询抛异常或返回 MODE_DEFAULT 时一律按「已获得」处理，避免误报。
     */
    fun canStartBackgroundActivities(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return true
            val mode = appOps.checkOpNoThrow(
                BACKGROUND_ACTIVITY_START_OP,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 真实验证 ROOT（su）可用性：在 IO 线程执行带超时的 `su -c id`。
     *
     * 输出包含 uid=0 且退出码为 0 视为已获得；失败、异常或超时（如未放行导致
     * Magisk/KernelSU 弹授权窗）一律视为未获得。超时后强制销毁进程，避免挂起。
     */
    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(ROOT_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor()
                return@withContext false
            }
            val output = process.inputStream.use { stream ->
                InputStreamReader(stream).use { reader -> reader.readText() }
            }
            process.exitValue() == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }
}
