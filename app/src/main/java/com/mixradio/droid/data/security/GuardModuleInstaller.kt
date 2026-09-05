// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import android.content.Context
import com.mixradio.droid.data.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * shso_guard 守卫模块安装器（方案 §10）。
 *
 * 安装方式 = 复制安装：APK 内置 shso_guard.zip → 解压到缓存 → root 复制到
 * /data/adb/modules/shso_guard → chmod 0755。无需刷机、无需重启，装完立即生效
 * （拦截靠 PATH 前置，不靠开机脚本）。
 *
 * 卸载用绝对路径 /system/bin/rm 绕过守卫自身（守卫 protect=/data 会拦截对模块目录的
 * 常规 rm -rf——这正是守卫的自保护设计，App 卸载走受信路径绕行）。
 */
object GuardModuleInstaller {

    const val MODULE_ID = "shso_guard"
    const val MODULE_DIR = "/data/adb/modules/shso_guard"
    const val GUARD_BIN_DIR = "$MODULE_DIR/guard"
    private const val ASSET_ZIP = "shso_guard.zip"

    sealed class GuardStatus {
        data object NotInstalled : GuardStatus()
        data class Installed(val version: String, val disabled: Boolean) : GuardStatus()
        data class Unknown(val reason: String) : GuardStatus()
    }

    /** 查询守卫模块状态（一次 su 探测）。 */
    suspend fun status(): GuardStatus = withContext(Dispatchers.IO) {
        try {
            val (code, out) = RootService.runCommandSync(
                "if [ -f $MODULE_DIR/module.prop ]; then echo INSTALLED; " +
                    "grep '^version=' $MODULE_DIR/module.prop | head -1; " +
                    "if [ -e $MODULE_DIR/disable ]; then echo DISABLED; fi; " +
                    "else echo MISSING; fi",
                8_000L
            )
            if (code != 0) return@withContext GuardStatus.Unknown("su 返回 $code")
            val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
            when {
                lines.isEmpty() || lines.first() != "INSTALLED" -> GuardStatus.NotInstalled
                else -> GuardStatus.Installed(
                    version = lines.getOrNull(1)?.removePrefix("version=") ?: "?",
                    disabled = lines.getOrNull(2) == "DISABLED"
                )
            }
        } catch (e: Exception) {
            GuardStatus.Unknown(e.message ?: "异常")
        }
    }

    /** 守卫 bin 目录是否就绪（executeFile PATH 前置判定用，结果缓存 60s）。 */
    @Volatile
    private var guardReadyCache: Boolean? = null
    @Volatile
    private var guardReadyAt: Long = 0

    fun guardBinDirReady(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && guardReadyCache != null && now - guardReadyAt < 60_000) {
            return guardReadyCache!!
        }
        val ready = try {
            RootService.runCommandSync("test -x $GUARD_BIN_DIR/rm", 5_000L).first == 0
        } catch (_: Exception) {
            false
        }
        guardReadyCache = ready
        guardReadyAt = now
        return ready
    }

    /**
     * 安装/覆盖安装：assets/shso_guard.zip → cacheDir 解压 → root 复制。
     * @return (成功, 消息)
     */
    suspend fun install(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 1) 从 APK assets 解压到应用缓存目录
            val staging = File(context.cacheDir, "guard_install").apply { mkdirs() }
            val moduleDir = File(staging, MODULE_ID)
            moduleDir.deleteRecursively()
            moduleDir.mkdirs()

            context.assets.open(ASSET_ZIP).use { asset ->
                ZipInputStream(asset.buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.removePrefix("./").removePrefix("/")
                        val target = File(moduleDir, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                        zis.closeEntry()
                    }
                }
            }
            if (!File(moduleDir, "module.prop").exists()) {
                return@withContext Pair(false, "安装包内缺少 module.prop（打包异常）")
            }

            // 2) root 复制安装（覆盖旧安装用绝对路径 rm 绕过守卫自保护）
            val stagingPath = RootService.escapeShellArg(moduleDir.absolutePath)
            RootService.runCommandSync(
                "/system/bin/rm -rf $MODULE_DIR 2>/dev/null; mkdir -p /data/adb/modules && " +
                    "cp -R $stagingPath $MODULE_DIR && chmod -R 0755 $MODULE_DIR",
                60_000L
            ).let { (code, out) ->
                if (code != 0) return@withContext Pair(false, "root 复制失败: ${out.trim().take(200)}")
            }

            // 3) 校验
            val ok = RootService.runCommandSync(
                "test -f $MODULE_DIR/module.prop -a -x $GUARD_BIN_DIR/rm", 8_000L
            ).first == 0
            if (!ok) return@withContext Pair(false, "安装校验失败（guard/rm 不可执行）")

            guardBinDirReady(forceRefresh = true)
            SecurityAuditLog.log(
                CommandSource.INTERNAL_APP, "ALLOW", "GUARD_INSTALL", RiskLevel.SAFE,
                "安装守卫模块 shso_guard → $MODULE_DIR"
            )
            Pair(true, "守卫模块已安装，立即生效（管理器中可见需重启）")
        } catch (e: Exception) {
            Pair(false, "安装失败: ${e.message}")
        }
    }

    /** 卸载守卫模块。 */
    suspend fun uninstall(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 绝对路径绕过守卫自保护（protect=/data）
            val (code, out) = RootService.runCommandSync(
                "/system/bin/rm -rf $MODULE_DIR", 30_000L
            )
            if (code != 0) return@withContext Pair(false, "卸载失败: ${out.trim().take(200)}")
            guardReadyCache = false
            guardReadyAt = System.currentTimeMillis()
            SecurityAuditLog.log(
                CommandSource.INTERNAL_APP, "ALLOW", "GUARD_UNINSTALL", RiskLevel.SAFE,
                "卸载守卫模块 $MODULE_DIR"
            )
            Pair(true, "守卫模块已卸载")
        } catch (e: Exception) {
            Pair(false, "卸载失败: ${e.message}")
        }
    }
}
