// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import android.content.Context
import com.mixradio.droid.ShsoApplication
import com.mixradio.droid.data.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全审计日志（方案 §7）：先留痕，后执行；被拦截的命令同样记录。
 *
 * - 落盘：ROOT 时 `/data/adb/shso/audit.log`（追加写），无 ROOT 回退应用私有目录；
 * - 环形滚动：超 512KB 时裁剪保留后半（约 256KB），避免撑爆分区；
 * - 只记录用户可见动作（终端输入 / 脚本执行 / 拦截决策），不记录内部模板命令（噪声）。
 */
object SecurityAuditLog {

    private const val TAG = "SecurityAuditLog"
    private const val ROOT_LOG_PATH = "/data/adb/shso/audit.log"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_BYTES = 256 * 1024
    private const val TRIM_CHECK_EVERY = 24
    const val MAX_TAIL_LINES = 2_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var entryCounter = 0
    private val lock = Any()

    private fun logFile(): File {
        val ctx: Context = ShsoApplication.appContext
        return File(ctx.filesDir, "audit.log")
    }

    private fun useRootLog(): Boolean = try {
        RootService.isRootGranted == true
    } catch (_: Exception) {
        false
    }

    /** 记录一条审计（异步落盘，不阻塞调用方）。verdict: ALLOW/CONFIRM/BLOCK。 */
    fun log(
        source: CommandSource,
        verdict: String,
        ruleId: String?,
        level: RiskLevel,
        command: String,
        scriptSha256: String? = null,
        exitCode: Int? = null
    ) {
        // 档位 0 完全不记录
        if (PolicyEngine.currentLevel() <= SecurityLevels.OFF) return

        val ts = dateFormat.format(Date())
        val cmd = command
            .replace("\n", "\\n")
            .replace("\r", "")
            .let { if (it.length > 2048) it.take(2048) + "…[截断]" else it }
        val line = buildString {
            append(ts)
            append(" | ").append(level.name)
            append(" | ").append(source.name)
            append(" | ").append(verdict)
            if (!ruleId.isNullOrEmpty()) append(" | ").append(ruleId)
            if (scriptSha256 != null) append(" | sha256:").append(scriptSha256.take(12))
            if (exitCode != null) append(" | exit:").append(exitCode)
            append(" | ").append(cmd)
        }

        synchronized(lock) {
            entryCounter++
            val shouldTrim = entryCounter % TRIM_CHECK_EVERY == 0
            scope.launch {
                try {
                    if (useRootLog()) {
                        RootService.runCommandSync(
                            "mkdir -p /data/adb/shso", 5_000L
                        )
                        RootService.writeBytesAsRoot(ROOT_LOG_PATH, (line + "\n").toByteArray(Charsets.UTF_8), append = true)
                        if (shouldTrim) trimRootLog()
                    } else {
                        val f = logFile()
                        f.appendText(line + "\n", Charsets.UTF_8)
                        if (shouldTrim && f.length() > MAX_BYTES) trimLocalLog(f)
                    }
                } catch (_: Exception) {
                    // 审计失败不阻断业务
                }
            }
        }
    }

    /** 裁剪 root 侧日志（保留后半）。 */
    private fun trimRootLog() {
        try {
            val (code, sizeOut) = RootService.runCommandSync("wc -c < $ROOT_LOG_PATH", 5_000L)
            if (code != 0) return
            val size = sizeOut.trim().toLongOrNull() ?: return
            if (size <= MAX_BYTES) return
            RootService.runCommandSync(
                "tail -c $KEEP_BYTES $ROOT_LOG_PATH > ${ROOT_LOG_PATH}.tmp && mv ${ROOT_LOG_PATH}.tmp $ROOT_LOG_PATH",
                10_000L
            )
        } catch (_: Exception) {
        }
    }

    private fun trimLocalLog(f: File) {
        try {
            val bytes = f.readBytes()
            if (bytes.size > MAX_BYTES) {
                f.writeBytes(bytes.copyOfRange(bytes.size - KEEP_BYTES, bytes.size))
            }
        } catch (_: Exception) {
        }
    }

    /** 读取日志尾部（用于设置页查看）。 */
    suspend fun readTail(maxLines: Int = 200): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val safeMaxLines = boundedTailLines(maxLines)
        try {
            if (useRootLog()) {
                val (code, out) = RootService.runCommandSync("tail -n $safeMaxLines $ROOT_LOG_PATH", 10_000L)
                if (code == 0) out else "(读取失败 exit=$code)"
            } else {
                val f = logFile()
                if (!f.exists()) "(暂无审计记录)"
                else f.readLines().takeLast(safeMaxLines).joinToString("\n")
            }
        } catch (e: Exception) {
            "(读取失败: ${e.message})"
        }
    }

    fun boundedTailLines(maxLines: Int): Int = maxLines.coerceIn(1, MAX_TAIL_LINES)

    /** 清空审计日志。 */
    suspend fun clear(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            if (useRootLog()) {
                val (code, _) = RootService.runCommandSync(
                    "sh -c '> $ROOT_LOG_PATH'", 5_000L
                )
                code == 0
            } else {
                logFile().writeText("")
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
