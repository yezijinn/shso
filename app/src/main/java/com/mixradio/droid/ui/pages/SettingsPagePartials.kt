// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.pages

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.security.SecurityLevels
import com.mixradio.droid.ui.theme.AuroraArrowPreference
import com.mixradio.droid.ui.theme.AuroraSwitchPreference

/**
 * 设置页五个权限项的子 Composable。
 *
 * 为什么是独立 Composable：原 SettingsPage 单 Composable 持有 9 个 state，权限刷新（从系统设置返回
 * 时一次性刷 5 个 state）会触发整页 815 行重新组合。抽出后 5 个权限 state 仅重组本节点，其它组
 * （文件行为 / 安全 / 更新 / 关于）的 Composable 因参数未变被 Compose 跳过。
 *
 * 所有回调都用 remember 包裹保证引用稳定——这样父 Composable 重组时，本子 Composable 接收到的
 * lambda 参数引用稳定（Compose 智能跳过条件之一），不会因父重组而强制刷新。
 *
 * 入参全部为 Boolean / Unit / () -> Unit 等 stable 类型，AppSettings 加 @Stable 后其他字段也按
 * Compose 规则走智能跳过。
 */
@Composable
fun SettingsPermissionsGroup(
    storage: Boolean,
    battery: Boolean,
    backgroundStart: Boolean,
    root: Boolean?,
    install: Boolean,
    onRequestStorage: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestInstall: () -> Unit,
) {
    val context = LocalContext.current

    val onStorage = androidx.compose.runtime.remember<() -> Unit>(storage) { {
        if (storage) {
            Toast.makeText(context, "存储空间权限已获得", Toast.LENGTH_SHORT).show()
        } else {
            onRequestStorage()
        }
    } }

    val onBattery = androidx.compose.runtime.remember<() -> Unit>(battery) { {
        if (battery) {
            Toast.makeText(context, "已获得省电策略豁免（忽略电池优化）", Toast.LENGTH_SHORT).show()
        } else {
            onRequestBattery()
        }
    } }

    val onBackground = androidx.compose.runtime.remember<() -> Unit>(backgroundStart) { {
        if (backgroundStart) {
            Toast.makeText(context, "已允许后台弹出页面", Toast.LENGTH_SHORT).show()
        } else {
            onRequestBackground()
        }
    } }

    val onRoot = androidx.compose.runtime.remember<() -> Unit>(root) { {
        if (root == true) {
            Toast.makeText(context, "超级用户授权已获得", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未检测到 ROOT，请在 Magisk / KernelSU 中为本应用授权后返回自动刷新", Toast.LENGTH_SHORT).show()
        }
    } }

    val onInstall = androidx.compose.runtime.remember<() -> Unit>(install) { {
        if (install) {
            Toast.makeText(context, "已允许安装外部来源应用", Toast.LENGTH_SHORT).show()
        } else {
            onRequestInstall()
        }
    } }

    AuroraArrowPreference(
        title = "存储空间",
        summary = "允许读取外部存储,所有文件访问权限",
        statusSwitch = storage,
        onClick = onStorage
    )
    AuroraArrowPreference(
        title = "省电策略",
        summary = "省电策略无限制  耗电保护允许后台",
        statusSwitch = battery,
        onClick = onBattery
    )
    AuroraArrowPreference(
        title = "后台弹出",
        summary = "权限管理 其他权限 允许后台弹出页",
        statusSwitch = backgroundStart,
        onClick = onBackground
    )
    AuroraArrowPreference(
        title = "超级用户",
        summary = "Magisk KernelSU 超级用户授权",
        statusSwitch = root == true,
        statusSwitchEnabled = root != null,
        onClick = onRoot
    )
    AuroraArrowPreference(
        title = "安装应用",
        summary = "无ROOT 手动允许安装外部来源应用",
        statusSwitch = install,
        onClick = onInstall
    )
}

/**
 * 设置页三个文件行为开关：独立存储 / 自动删除 / 自动执行。
 *
 * 全部走 AppSettings 单一来源（AppSettings 已 @Stable）。
 * 父 Composable 重组时本节点可通过稳定性检查跳过（参数全 stable）。
 */
@Composable
fun SettingsFileBehaviorGroup(
    appSettings: AppSettings,
) {
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
}

/**
 * 触发各权限项系统 Intent 的具体实现（保留在父 Composable 调用环境）。
 * 抽出后 SettingsPage 顶层仅传入回调引用，避免意图逻辑与 UI 节点强耦合。
 */
internal object SettingsPermissionIntents {
    fun openStorageSettings(context: android.content.Context, onLegacyRequest: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(context, "无法打开系统设置页面", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            onLegacyRequest()
        }
    }

    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 是 Play 商店受限权限，但本项目经 GitHub 分发、
    // 且后台执行需要豁免电池优化才能稳定保活，属于本应用的核心能力，故抑制该检查。
    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: android.content.Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            )
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开电池优化设置页面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openBackgroundSettings(context: android.content.Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            )
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开应用详情设置页面", Toast.LENGTH_SHORT).show()
        }
    }

    fun openInstallSettings(context: android.content.Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:${context.packageName}".toUri()
                }
            )
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开安装未知应用设置", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * 设置页安全相关三项：档位 / 审计日志 / 守卫模块安装。
 *
 * 抽出后档位变化（点击循环 0→1→2→3→0）只重组本节点，不波及权限组 / 文件行为组 / 更新组。
 * 副作用（写入 AppSettings + 触发守卫安装/策略同步 + Toast）由父 Composable 处理，
 * 本节点仅暴露回调。
 */
@Composable
fun SettingsSecurityGroup(
    currentLevel: Int,
    guardInstalled: Boolean,
    onLevelClicked: () -> Unit,
    onShowAuditLogClicked: () -> Unit,
    onInstallGuardClicked: () -> Unit,
) {
    val summary = androidx.compose.runtime.remember(currentLevel) {
        "当前：${SecurityLevels.nameOf(currentLevel)}（0 关 1 审计 2 标准 3 最高）"
    }

    AuroraArrowPreference(
        title = "安全档位",
        summary = summary,
        statusSwitch = currentLevel > AppSettings.SECURITY_OFF,
        statusSwitchEnabled = false,
        onClick = onLevelClicked
    )
    AuroraArrowPreference(
        title = "查看审计日志",
        summary = "最近50条拦截/放行/脚本扫描记录",
        statusSwitch = false,
        statusSwitchEnabled = false,
        onClick = onShowAuditLogClicked
    )
    AuroraArrowPreference(
        title = if (guardInstalled) "守卫模块：已安装" else "安装 shso_guard 守卫模块",
        summary = if (guardInstalled) "拦截 rm/dd/mkfs 等命令运行" else "复制本 APP 内置模块到 /data/adb/modules/",
        statusSwitch = guardInstalled,
        statusSwitchEnabled = false,
        onClick = onInstallGuardClicked
    )
}
