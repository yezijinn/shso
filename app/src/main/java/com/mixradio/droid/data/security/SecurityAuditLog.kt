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
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 安全审计日志（方案 §7）：先留痕，后执行；被拦截的命令同样记录。
 *
 * - 落盘：ROOT 时 `/data/adb/shso/audit.log`（追加写），无 ROOT 回退应用私有目录；
 * - 环形滚动：超 512KB 时裁剪保留后半（约 256KB），避免撑爆分区；
 * - 只记录用户可见动作（终端输入 / 脚本执行 / 拦截决策），不记录内部模板命令（噪声）。
 *
 * 不变式（改动前必读）：
 * 1. 审计目录为 **0777**（刻意为之，供第三方文件管理器访问），因此写入前必须确认目标是
 *    **普通文件**：`cat >>` 与 `>` 都会跟随软链，等于以 root 写任意文件。
 *    校验或清理失败时**放弃本次写入**，不得退化为"继续追加"。
 * 2. 审计行以 `|` 分隔字段：字段内的 `|`、换行、控制字符必须转义，否则记录可被
 *    错位或整行伪造（换行注入可伪造出完整假行）。
 * 3. 档位 0 静默普通事件，但**配置类事件恒留痕** —— 关闭/降级防护本身必须可追溯。
 */
object SecurityAuditLog {

    private const val ROOT_LOG_PATH = "/data/adb/shso/audit.log"
    private const val ROOT_LOG_DIR = "/data/adb/shso"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_BYTES = 256 * 1024
    private const val TRIM_CHECK_EVERY = 24
    private const val MAX_COMMAND_CHARS = 2048
    const val MAX_TAIL_LINES = 2_000

    /**
     * 无论档位如何都必须留痕的规则 ID：防护被安装 / 卸载 / 改档 / 降级都属于配置类事件，
     * 若它们随档位 0 一起静默，"谁在何时把防护关掉了"将无法回答。
     */
    private val ALWAYS_AUDITED = setOf(
        "GUARD_INSTALL", "GUARD_UNINSTALL", "GUARD_POLICY_MODE",
        "GUARD_AUTO_INSTALL_FAILED", "GUARD_UNAVAILABLE_DEGRADED",
        "GUARD_MODE_DRIFT", "AUDIT_CLEARED"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 约束：日志在 IO 协程并发写入，格式化器必须线程安全（DateTimeFormatter 不可变且线程安全）。
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private var entryCounter = 0
    private val lock = Any()

    /** 审计写入失败次数（目标非常规文件 / su 失败 / 轮转失败）。设置页据此提示"审计可能不完整"。 */
    @Volatile
    var writeFailureCount: Int = 0
        private set

    @Volatile
    private var lastFailureReason: String? = null

    /** 失败摘要（null = 无失败）。 */
    fun failureSummary(): String? =
        if (writeFailureCount == 0) null else "$lastFailureReason（累计 $writeFailureCount 次）"

    private fun recordFailure(reason: String) {
        synchronized(lock) {
            writeFailureCount++
            lastFailureReason = reason
        }
    }

    private fun logFile(): File {
        val ctx: Context = ShsoApplication.appContext
        return File(ctx.filesDir, "audit.log")
    }

    private fun useRootLog(): Boolean = try {
        RootService.isRootGranted == true
    } catch (_: Exception) {
        false
    }

    /**
     * 字段转义：维持「一行一条记录」的不变式。
     * `|` 会让字段错位，换行可注入完整伪造行，控制字符会污染终端回显。
     */
    internal fun sanitizeField(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when {
                ch == '|' -> sb.append("\\u007C")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> Unit
                ch.code < 0x20 -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 组装审计行（供 log 与 clear 复用）。 */
    private fun formatLine(
        ts: String,
        source: CommandSource,
        verdict: String,
        ruleId: String?,
        level: RiskLevel,
        command: String,
        scriptSha256: String?,
        exitCode: Int?
    ): String {
        val cmd = sanitizeField(command)
            .let { if (it.length > MAX_COMMAND_CHARS) it.take(MAX_COMMAND_CHARS) + "…[截断]" else it }
        return buildString {
            append(ts)
            append(" | ").append(level.name)
            append(" | ").append(source.name)
            append(" | ").append(verdict)
            if (!ruleId.isNullOrEmpty()) append(" | ").append(sanitizeField(ruleId))
            if (scriptSha256 != null) append(" | sha256:").append(scriptSha256.take(12))
            if (exitCode != null) append(" | exit:").append(exitCode)
            append(" | ").append(cmd)
        }
    }

    private fun now(): String = Instant.now().atZone(ZoneId.systemDefault()).format(dateFormat)

    /**
     * 记录一条审计（异步落盘，不阻塞调用方）。verdict: ALLOW/CONFIRM/BLOCK。
     *
     * 档位 0 下普通事件不记录；[ALWAYS_AUDITED] 中的配置类事件始终记录。
     */
    fun log(
        source: CommandSource,
        verdict: String,
        ruleId: String?,
        level: RiskLevel,
        command: String,
        scriptSha256: String? = null,
        exitCode: Int? = null
    ) {
        if (PolicyEngine.currentLevel() <= SecurityLevels.OFF && ruleId !in ALWAYS_AUDITED) return

        val line = formatLine(now(), source, verdict, ruleId, level, command, scriptSha256, exitCode)

        synchronized(lock) {
            entryCounter++
            val shouldTrim = entryCounter % TRIM_CHECK_EVERY == 0
            scope.launch {
                try {
                    if (useRootLog()) {
                        // 目标必须是普通文件：目录 0777，第三方可放置软链让 `>>` 跟随写入任意文件。
                        // 清理失败即放弃本次写入（fail-closed），不得继续追加。
                        if (!prepareRootTarget()) {
                            recordFailure("审计目标非常规文件（疑似软链），已放弃写入")
                        } else {
                            val ok = RootService.writeBytesAsRoot(
                                ROOT_LOG_PATH, (line + "\n").toByteArray(Charsets.UTF_8), append = true
                            )
                            if (!ok) recordFailure("root 写入审计失败") else if (shouldTrim) trimRootLog()
                        }
                    } else {
                        val auditFile = logFile()
                        if (Files.isSymbolicLink(auditFile.toPath())) {
                            // 私有目录内的软链不可能是正常状态，直接清除。
                            auditFile.delete()
                            recordFailure("本地审计文件曾被替换为软链，已清除")
                        }
                        if (auditFile.exists() && !auditFile.isFile) {
                            recordFailure("本地审计目标非常规文件，已放弃写入")
                        } else {
                            auditFile.appendText(line + "\n", Charsets.UTF_8)
                            if (shouldTrim && auditFile.length() > MAX_BYTES) trimLocalLog(auditFile)
                        }
                    }
                } catch (e: Exception) {
                    recordFailure("审计写入异常: ${e.message}")
                }
            }
        }
    }

    /**
     * ROOT 侧写入前置：目标是软链 / 非常规文件时先删除，删不掉就返回 false 让调用方放弃写入。
     * 删除后再确认一次，避免"删了但目录里仍是链接"（如挂载点 / 竞态）。
     */
    private fun prepareRootTarget(): Boolean {
        val script = buildString {
            append("mkdir -p $ROOT_LOG_DIR; ")
            append("if [ -L $ROOT_LOG_PATH ] || { [ -e $ROOT_LOG_PATH ] && [ ! -f $ROOT_LOG_PATH ]; }; then ")
            append("/system/bin/rm -f -- $ROOT_LOG_PATH || exit 9; fi; ")
            append("if [ -L $ROOT_LOG_PATH ] || { [ -e $ROOT_LOG_PATH ] && [ ! -f $ROOT_LOG_PATH ]; }; then exit 9; fi; true")
        }
        return try {
            RootService.runCommandSync(script, 5_000L).first == 0
        } catch (_: Exception) {
            false
        }
    }

    /** 裁剪 root 侧日志（保留后半）。 */
    private fun trimRootLog() {
        try {
            val (code, sizeOut) = RootService.runCommandSync("wc -c < $ROOT_LOG_PATH", 5_000L)
            if (code != 0) return
            val size = sizeOut.trim().toLongOrNull() ?: return
            if (size <= MAX_BYTES) return
            // 临时名带 PID：固定名会被并发轮转互踩，也可被预置软链接管 `>` 的落点。
            val tmp = "$ROOT_LOG_PATH.tmp.\$\$"
            val script = buildString {
                append("[ -e $tmp ] && /system/bin/rm -f -- $tmp; ")
                append("tail -c $KEEP_BYTES -- $ROOT_LOG_PATH > $tmp && [ -f $tmp ] ")
                append("|| { /system/bin/rm -f -- $tmp; exit 1; }; ")
                append("mv -f -- $tmp $ROOT_LOG_PATH")
            }
            if (RootService.runCommandSync(script, 10_000L).first != 0) recordFailure("审计轮转失败")
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
                val auditFile = logFile()
                if (!auditFile.exists()) "(暂无审计记录)"
                else auditFile.readLines().takeLast(safeMaxLines).joinToString("\n")
            }
        } catch (e: Exception) {
            "(读取失败: ${e.message})"
        }
    }

    fun boundedTailLines(maxLines: Int): Int = maxLines.coerceIn(1, MAX_TAIL_LINES)

    /**
     * 清空审计日志。
     *
     * 清空后立即写入一条 `AUDIT_CLEARED` 标记：清空动作本身也必须可追溯，
     * 否则"审计链在某个时间点被重置"无人可见。
     */
    suspend fun clear(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val marker = formatLine(
                now(), CommandSource.INTERNAL_APP, "ALLOW", "AUDIT_CLEARED",
                RiskLevel.WARNING, "清空审计日志", null, null
            ) + "\n"
            if (useRootLog()) {
                // 清空前同样必须先确认目标是普通文件：`>` 与 `>>` 都会跟随软链，
                // 否则「清空审计」会退化成以 root 截断任意文件（比追加更危险）。
                if (!prepareRootTarget()) {
                    recordFailure("审计目标非常规文件，已放弃清空")
                    return@withContext false
                }
                val (code, _) = RootService.runCommandSync("sh -c '> $ROOT_LOG_PATH'", 5_000L)
                if (code != 0) return@withContext false
                RootService.writeBytesAsRoot(ROOT_LOG_PATH, marker.toByteArray(Charsets.UTF_8), append = true)
            } else {
                logFile().writeText(marker)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
