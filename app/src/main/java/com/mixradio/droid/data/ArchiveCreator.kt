// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 创建压缩包（zip）。
 *
 * 走内置环境的 `zip`（随基础环境分发，见 [EmbeddedEnv]）经 root 执行：
 * 这样对 `/data/adb` 一类应用读不到的路径同样可用，也能正确处理文件夹递归与权限位。
 * 内置 zip 缺失（环境未就绪）时退回系统 `zip`（多半不存在，会如实报错）。
 */
object ArchiveCreator {

    /**
     * 把 [sourcePaths]（同目录下的若干条目）打包为 [targetZipPath]。
     *
     * @return (是否成功, 提示语)
     */
    suspend fun createZip(sourcePaths: List<String>, targetZipPath: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            if (sourcePaths.isEmpty()) return@withContext Pair(false, "没有可打包的项目")
            // 冷启动时后台解压可能尚未完成：先确保内置环境就绪，再读取其目录；
            // 失败（资产缺失 / 解压失败）才退回系统 zip，与旧行为一致。
            val envReady = runCatching {
                EmbeddedEnv.ensure(com.mixradio.droid.ShsoApplication.appContext)
            }.getOrDefault(false)
            val first = File(sourcePaths.first())
            val parent = first.parent ?: "/"
            val names = sourcePaths.map { File(it).name }
            // 所有源必须在同一目录（菜单入口即同目录单/多选）
            if (sourcePaths.any { (File(it).parent ?: "/") != parent }) {
                return@withContext Pair(false, "仅支持打包同一目录下的项目")
            }

            val embeddedZip = if (envReady) {
                EmbeddedEnv.binDir?.let { "$it/zip" }?.takeIf { File(it).isFile }
            } else null
            val zipBin = embeddedZip ?: "zip"
            val libDir = if (embeddedZip != null) EmbeddedEnv.libDir else null

            val escapedParent = RootService.escapeShellArg(parent)
            val escapedZip = RootService.escapeShellArg(targetZipPath)
            val escapedNames = names.joinToString(" ") { RootService.escapeShellArg(it) }
            val libExport = if (libDir != null) {
                "LD_LIBRARY_PATH=" + RootService.escapeShellArg(libDir) + ":${'$'}LD_LIBRARY_PATH "
            } else ""

            val cmd = "cd $escapedParent && rm -f $escapedZip && " +
                libExport + RootService.escapeShellArg(zipBin) + " -r -q $escapedZip $escapedNames && " +
                "test -s $escapedZip"
            val (code, out) = RootService.runCommandSync(cmd, timeoutMs = 180_000L)
            if (code == 0) {
                true to "已创建压缩包: ${File(targetZipPath).name}"
            } else {
                false to "创建压缩包失败: " + out.trim().take(200).ifEmpty { "退出码 $code" }
            }
        }
}
