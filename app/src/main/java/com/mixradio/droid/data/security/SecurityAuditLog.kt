// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

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
        "AUDIT_CLEARED", "APK_INSTALL"
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
     * 记录一条审计（异步落盘，不阻塞调用方）。
     *
     * [verdict] 用 [AuditVerdict] 枚举而不是裸字符串：此前 `BLOCK` 既表示「被拒绝」
     * 又表示「放行但守卫降级」，看日志无法区分「命令没跑」与「跑了但没防护」。
     *
     * 档位 0 下普通事件不记录；[ALWAYS_AUDITED] 中的配置类事件始终记录。
     */
    fun log(
        source: CommandSource,
        verdict: AuditVerdict,
        ruleId: String?,
        level: RiskLevel,
        command: String,
        scriptSha256: String? = null,
        exitCode: Int? = null
    ) {
        // 降级执行必须留痕：它是「当时这台设备没有运行时守卫」的唯一证据，
        // 属于配置类事实，不随档位 0 一起静默。
        if (PolicyEngine.currentLevel() <= SecurityLevels.OFF &&
            ruleId !in ALWAYS_AUDITED &&
            verdict != AuditVerdict.DEGRADED
        ) return

        val line = formatLine(now(), source, verdict.name, ruleId, level, command, scriptSha256, exitCode)

        synchronized(lock) {
            entryCounter++
            val shouldTrim = entryCounter % TRIM_CHECK_EVERY == 0
            scope.launch {
                try {
                    if (useRootLog()) {
                        // 校验与写入必须在**同一个 su 进程**内完成。
                        //
                        // 此前是两次独立 su：第 1 次 prepareRootTarget() 判软链/属主/权限，
                        // 第 2 次由 writeBytesAsRoot 执行 `cat >> <path>`。而目标目录
                        // `/data/adb/shso` 是 0777 且**无 sticky 位**（可写性是硬性要求），
                        // 两次 su 之间有几十毫秒窗口：任意第三方应用在此期间
                        // `unlink(audit.log)` + `symlink(audit.log, <某模块>/post-fs-data.sh)`，
                        // 第 2 个 su 的 `>>` 跟随软链 → **以 root 把审计行追加进引导期脚本**，
                        // 即下次开机的 root 代码执行。clear() 的 `sh -c '> …'` 同理且是截断。
                        //
                        // 现合并为一个脚本：先判软链/非常规（失败 exit 9），
                        // 再在同一进程内 `exec 3>>file` 打开并写入 ——
                        // 打开紧跟校验，中间没有可插入的窗口。
                        val ok = appendRootLogLine(line)
                        if (!ok) recordFailure("root 写入审计失败") else if (shouldTrim) trimRootLog()
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
     *
     * 额外校验**属主与权限位**。`/data/adb/shso` 按产品要求必须是 0777（供第三方文件管理器互访，
     * 见 [RootFileManager.ensureShsoDir]），因此该目录本身不可信：
     * 任意应用都能 `unlink(audit.log)` 再放入自己的普通文件，或直接 `chmod 666`。
     * 原实现只判「是否软链 / 是否常规文件」，攻击者自建的**普通文件**完全通过检查，
     * 随后的 `cat >> ` 把伪造记录混进唯一的事后追溯依据。
     *
     * 判据：存在时必须是 root 属主（`[ -O ]`，以 root 身份执行即判当前属主为 root），
     * 且组/其它位无写权限（`find -perm /022`）。
     * 不满足即视为「已被第三方接管」，删除后重建 root 私有文件；删不掉则放弃写入。
     *
     * 权限位判定用 `find -perm /022` 而非 `$(( 0$m & 022 ))`：Android 的 mksh **不支持**
     * 算术展开里的位运算符（真机实测 `sh -c 'echo $(( 0600 & 022 ))'` 返回非 0），
     * 那种写法会恒判为「不可信」而把正常审计文件反复删掉。
     */
    /**
     * 追加一行审计到 root 日志：**校验与写入在同一个 su 进程内**完成。
     *
     * 这是 [appendRootLogLine] 存在的唯一理由：拆成两次 su 会留下 TOCTOU 窗口 ——
     * 目标目录 0777 无 sticky，第三方可在两次 su 之间把日志文件换成指向任意 root 文件的
     * 软链，第二个 su 的 `>>` 就会跟随它写入。
     *
     * 流程（单进程）：
     * 1. `mkdir -p` 目标目录
     * 2. 目标是软链 / 存在但不是普通文件 → `exit 9`（fail-closed，放弃本次写入）
     * 3. 存在但属主不是 root、或可被组/其他用户写 → 先删除重建（沿用原有语义）
     * 4. `umask 077` + 创建（若不存在）
     * 5. **再次**判软链 —— 这一步与第 6 步之间没有任何可插入点
     * 6. `exec 3>> <path>` 在本进程内打开，随后 `printf '%s\n' "$payload" >&3`
     *
     * 第 5 步是冗余的，但保留它可以把「打开前最后一刻」也纳入判定，代价只有一次 `[ -L ]`。
     *
     * @return true 表示已写入；false 表示目标不可用（调用方负责记失败）
     */
    private fun appendRootLogLine(line: String): Boolean {
        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
        // 单行审计的字段已由 sanitizeField 转义（| \n 控制字符），不会破坏 shell 单引号；
        // 仍用单引号包裹并对内嵌单引号做 '\'' 转义，与 writeBytesAsRoot 同款。
        val quoted = payload.joinToString("") { b ->
            val ch = b.toInt().toChar()
            if (ch == '\'') "'\\''" else ch.toString()
        }
        val script = buildString {
            append("mkdir -p $ROOT_LOG_DIR; ")
            append("if [ -L $ROOT_LOG_PATH ] || { [ -e $ROOT_LOG_PATH ] && [ ! -f $ROOT_LOG_PATH ]; }; then exit 9; fi; ")
            append("if [ -e $ROOT_LOG_PATH ]; then ")
            append("if ! [ -O $ROOT_LOG_PATH ]; then /system/bin/rm -f -- $ROOT_LOG_PATH || exit 9; fi; ")
            append("if [ -n \"\$(find $ROOT_LOG_PATH -maxdepth 0 -type f -perm /022 2>/dev/null)\" ]; then ")
            append("/system/bin/rm -f -- $ROOT_LOG_PATH || exit 9; fi; ")
            append("fi; ")
            append("[ -e $ROOT_LOG_PATH ] || { umask 077 && : > $ROOT_LOG_PATH; }; ")
            append("chmod 600 $ROOT_LOG_PATH 2>/dev/null; ")
            // 打开前最后一刻的判定，与下面的 exec 之间无窗口
            append("if [ -L $ROOT_LOG_PATH ] || { [ -e $ROOT_LOG_PATH ] && [ ! -f $ROOT_LOG_PATH ]; }; then exit 9; fi; ")
            append("exec 3>> $ROOT_LOG_PATH || exit 9; ")
            append("printf '%s' '$quoted' >&3; ")
            append("exec 3>&-")
        }
        return try {
            RootService.runCommandSync(script, 5_000L).first == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun prepareRootTarget(): Boolean {
        val script = buildString {
            append("mkdir -p $ROOT_LOG_DIR; ")
            append("if [ -L $ROOT_LOG_PATH ] || { [ -e $ROOT_LOG_PATH ] && [ ! -f $ROOT_LOG_PATH ]; }; then ")
            append("/system/bin/rm -f -- $ROOT_LOG_PATH || exit 9; fi; ")
            append("if [ -e $ROOT_LOG_PATH ]; then ")
            append("if ! [ -O $ROOT_LOG_PATH ]; then /system/bin/rm -f -- $ROOT_LOG_PATH || exit 9; fi; ")
            append("if [ -n \"\$(find $ROOT_LOG_PATH -maxdepth 0 -type f -perm /022 2>/dev/null)\" ]; then ")
            append("/system/bin/rm -f -- $ROOT_LOG_PATH || exit 9; fi; ")
            append("fi; ")
            append("if [ -L $ROOT_LOG_PATH ] || { [ -e $ROOT_LOG_PATH ] && [ ! -f $ROOT_LOG_PATH ]; }; then exit 9; fi; ")
            // 新建时收紧为 0600，避免又被第三方改写
            append("[ -e $ROOT_LOG_PATH ] || { umask 077 && : > $ROOT_LOG_PATH; }; ")
            append("chmod 600 $ROOT_LOG_PATH 2>/dev/null; true")
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
            // 临时文件用 mktemp 在同目录创建（O_EXCL + 不可预测名）。
            //
            // 原实现用固定名 + PID 后缀，并以 `[ -e $tmp ]` 作为「是否已存在」的判据：
            // 对**悬空软链** `[ -e ]` 为 false，于是既不删除、也不拒绝，
            // 随后的 `> $tmp` 由 root 跟随该软链在攻击者指定路径创建文件。
            // 落点若是 Magisk/KernelSU 模块目录，等于下一次开机的 root 代码执行。
            // 目录本身是 0777（见 RootFileManager.ensureShsoDir），预置软链无门槛。
            val dir = ROOT_LOG_PATH.substringBeforeLast('/', "/data/adb/shso")
            val script = buildString {
                append("d=").append(RootService.escapeShellArg(dir)).append("; ")
                append("t=\$(mktemp \"\$d/.audit.XXXXXX\" 2>/dev/null) || exit 1; ")
                append("[ -n \"\$t\" ] && [ ! -L \"\$t\" ] || { rm -f -- \"\$t\"; exit 1; }; ")
                append("tail -c $KEEP_BYTES -- $ROOT_LOG_PATH > \"\$t\" ")
                append("|| { /system/bin/rm -f -- \"\$t\"; exit 1; }; ")
                append("[ -f \"\$t\" ] && [ ! -L \"\$t\" ] ")
                append("|| { /system/bin/rm -f -- \"\$t\"; exit 1; }; ")
                append("mv -f -- \"\$t\" $ROOT_LOG_PATH")
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
                // 读取前确认目标是普通文件：软链会让 `tail` 读出链接目标的内容并展示到 UI。
                // 只探测不删除 —— 读取是只读操作，不应产生副作用（清除只发生在写入/清空路径）。
                val (probeCode, _) = RootService.runCommandSync(
                    "[ ! -L $ROOT_LOG_PATH ] && { [ ! -e $ROOT_LOG_PATH ] || [ -f $ROOT_LOG_PATH ]; }",
                    5_000L
                )
                if (probeCode != 0) {
                    "(审计日志不是普通文件（疑似软链），已拒绝读取)"
                } else {
                    val (code, out) = RootService.runCommandSync("tail -n $safeMaxLines $ROOT_LOG_PATH", 10_000L)
                    if (code == 0) out else "(读取失败 exit=$code)"
                }
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
                val auditFile = logFile()
                // 与 log() 保持一致：私有目录内的软链不可能是正常状态，清空前先移除，
                // 否则 writeText 会跟随软链写穿到目标文件。
                if (Files.isSymbolicLink(auditFile.toPath())) {
                    auditFile.delete()
                    recordFailure("本地审计文件曾被替换为软链，已清除")
                }
                auditFile.writeText(marker)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
