// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 播放源解析：把「用户点的那条路径」变成一个 MediaPlayer 真能打开的路径。
 *
 * 为什么需要它：文件页用 ROOT 列举，能看到应用自己 uid 读不到的文件
 * （`/data/adb/…`、其它应用私有目录）。`MediaPlayer.setDataSource(path)` 是**本进程**读，
 * 权限不足只会得到一个模糊的 `MEDIA_ERROR_IO`。此处先判本地可读；不可读且已授权 ROOT 时，
 * 用 root 复制一份到 cache 再播，退出时删除。
 *
 * **属主必须改回本应用**：`cp` 以 root 身份执行，新建文件属主是 root；
 * 只 `chmod 600` 会让应用自己读不了（owner=root 且只给 owner 读），
 * 播放必然失败且错误码只有 MEDIA_ERROR_IO，极难定位。
 *
 * 判定顺序刻意是「chown 尽力 → chmod 600 → canRead 定性 → 644 兜底」：
 * chown 只是让属主回到应用，真正的判据是 `canRead()`。不把 chown 写成 `&&` 链，
 * 否则少数只接受 `OWNER:GROUP` 的 toybox 会让整条命令在 chown 处中断、chmod 都不执行。
 */
internal object MediaSource {

    /** 允许复制到缓存播放的单文件上限。 */
    const val MAX_COPY_BYTES = 512L * 1024 * 1024

    /** 复制前要求 cache 至少剩余的余量：填满 cache 会把其它临时文件（安装暂存、对比结果）挤掉。 */
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024

    sealed interface Result {
        /** [tempCopy] 非空表示这是为播放临时复制的副本，退出时必须删。 */
        data class Ready(val path: String, val tempCopy: File?) : Result
        data class Failed(val reason: String) : Result
    }

    fun resolve(context: Context, path: String, declaredSize: Long): Result {
        val direct = File(path)
        if (runCatching { direct.isFile && direct.canRead() }.getOrDefault(false)) {
            return Result.Ready(path, null)
        }
        if (RootService.isRootGranted != true) {
            return Result.Failed("无法读取该文件（当前权限不足，请先授权 ROOT）")
        }
        // 列目录拿到的 size 对 root-only 文件常常是 0：那时上限与余量检查会整体失效，
        // 512MB 的视频会直接把 cache 写满。用 root stat 补一次，仍未知才按 0 放行。
        val size = sizeOf(path, declaredSize)
        if (size > MAX_COPY_BYTES) {
            return Result.Failed("文件超过 ${MAX_COPY_BYTES / 1024 / 1024}MB，不适合复制到缓存播放")
        }
        val usable = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
        if (size > 0L && usable > 0L && size + MIN_FREE_BYTES > usable) {
            return Result.Failed(
                "缓存空间不足：需要约 ${size / 1024 / 1024}MB，" +
                    "当前可用 ${usable / 1024 / 1024}MB"
            )
        }
        val dir = File(context.cacheDir, "mediaplay")
        runCatching { dir.mkdirs() }
        // 只清一小时前的残留：进程被杀会留下副本，但刚写出的副本可能正被本次播放使用。
        val staleBefore = System.currentTimeMillis() - 60L * 60 * 1000
        runCatching {
            dir.listFiles()?.forEach { if (it.lastModified() < staleBefore) it.delete() }
        }
        val target = File(dir, ExternalOpen.sanitizeSharedName(direct.name))
        val src = RootService.escapeShellArg(path)
        val dst = RootService.escapeShellArg(target.absolutePath)
        // 纯数字，不含任何 shell 元字符 —— 拼进命令不存在注入面。
        val uid = Process.myUid()
        // rm -f 先清掉上一次 root 留下的同名残留（应用自己删不掉 root 属主的文件）。
        // 三条命令用 ';' 串联：cp 失败由后续 isFile 判定，chown 失败不阻断 chmod。
        val cmd = "rm -f $dst; cp -f $src $dst; " +
            "chown $uid:$uid $dst 2>/dev/null || chown $uid $dst 2>/dev/null; " +
            "chmod 600 $dst"
        val (code, out) = RootService.runCommandSync(cmd, timeoutMs = 60_000L)
        if (code != 0 || !target.isFile) {
            runCatching { target.delete() }
            return Result.Failed(
                "复制到缓存失败：" + out.trim().take(160).ifEmpty { "su 返回 $code" }
            )
        }
        // cp 半截（ENOSPC / 中断）必须当失败：否则播放器解到中途报 MALFORMED。
        if (size > 0L && target.length() != size) {
            runCatching { target.delete() }
            return Result.Failed("复制不完整：期望 ${size}B，实际 ${target.length()}B")
        }
        // 极少数 ROM 的 su 会调整新建文件的安全上下文，导致属主正确仍不可读。
        // 一次放宽到 644 兜底（父目录 0700，外部仍进不来）。
        if (!runCatching { target.canRead() }.getOrDefault(false)) {
            RootService.runCommandSync("chmod 644 $dst", timeoutMs = 10_000L)
        }
        if (!runCatching { target.canRead() }.getOrDefault(false)) {
            runCatching { target.delete() }
            return Result.Failed("复制到缓存后仍不可读（system 权限策略限制）")
        }
        return Result.Ready(target.absolutePath, target)
    }

    /** 单文件大小：优先用列目录给的值，为 0 时经 root `stat` 补测。 */
    private fun sizeOf(path: String, declared: Long): Long {
        if (declared > 0L) return declared
        val (_, out) = RootService.runCommandSync(
            "stat -c %s ${RootService.escapeShellArg(path)} 2>/dev/null",
            timeoutMs = 10_000L
        )
        return out.trim().toLongOrNull() ?: 0L
    }

    /** 退出播放时删掉 [Result.Ready.tempCopy]。 */
    fun cleanup(tempCopy: File?) {
        if (tempCopy == null) return
        runCatching { tempCopy.delete() }
    }
}
