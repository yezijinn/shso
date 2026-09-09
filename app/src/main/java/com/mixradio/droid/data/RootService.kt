// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import com.mixradio.droid.data.security.CommandSource
import com.mixradio.droid.data.security.GuardModuleInstaller
import com.mixradio.droid.data.security.GuardPathPolicy
import com.mixradio.droid.data.security.PolicyEngine
import com.mixradio.droid.data.security.RiskLevel
import com.mixradio.droid.data.security.RootCommandGateway
import com.mixradio.droid.data.security.ScriptAuditor
import com.mixradio.droid.data.security.SecurityAuditLog
import com.mixradio.droid.data.security.SecurityLevels
import com.mixradio.droid.data.security.Verdict

object RootService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var appSettings: AppSettings? = null

    /**
     * Shell 参数白名单：仅含字母/数字/下划线/点/斜杠/连字符/冒号时视为安全，
     * 可直接原样拼接；含空格或其他字符一律用单引号包裹（内部单引号按 `'\''` 转义），
     * 杜绝注入，也保证含空格路径不被拆词。
     * 参考 MP-Manager RootManager.escapeShellArg 的 SAFE_ARG 方案。
     */
    private val SAFE_ARG = Pattern.compile("^[a-zA-Z0-9_./\\-:]+$")

    /** 生成可安全拼入 shell 命令的参数（防注入）。 */
    fun escapeShellArg(arg: String): String {
        if (arg.isEmpty()) return "''"
        return if (SAFE_ARG.matcher(arg).matches()) arg else "'" + arg.replace("'", "'\\''") + "'"
    }

    var isRootGranted by mutableStateOf<Boolean?>(null)
        private set

    var isTaskRunning by mutableStateOf(false)
        private set

    var currentTaskName by mutableStateOf<String?>(null)
        private set

    var currentTaskPath by mutableStateOf<String?>(null)
        private set

    var lastExecutedPath by mutableStateOf<String?>(null)
        private set

    var taskStartTime by mutableLongStateOf(0L)
        private set

    var outputLog by mutableStateOf(HyperCore.generateEngineBanner("工作中", isRootGranted))
        private set

    /**
     * 输出是否仍是「纯引擎横幅」（尚未混入任务/命令输出），以及该横幅对应的 ROOT 状态。
     * 用于 ROOT 探测完成后原位刷新横幅，避免误覆盖已跑完任务的日志。
     */
    private var outputIsPristineBanner: Boolean = true
    private var pristineBannerRoot: Boolean? = null

    var lastExitCode by mutableStateOf<Int?>(null)
        private set

    var processPid by mutableIntStateOf(0)
        private set

    private var activeProcess: Process? = null
    private var processWriter: OutputStreamWriter? = null
    private var executionJob: Job? = null

    fun initSettings(settings: AppSettings) {
        appSettings = settings
        refreshPristineBanner()
    }

    fun detectEnvironmentInfo(): String = HyperCore.detectEnvironmentInfo()
    fun detectKernelInfo(): String = HyperCore.detectKernelInfo()
    fun generateEngineBanner(statusText: String = "工作中"): String = HyperCore.generateEngineBanner(statusText, isRootGranted)

    /**
     * 将当前输出置为「纯引擎横幅」；横幅关闭时置空。
     */
    private fun refreshPristineBanner(statusText: String = "工作中") {
        val showBanner = appSettings?.showHyperCoreBanner ?: true
        pristineBannerRoot = if (showBanner) isRootGranted else null
        outputLog = if (showBanner) HyperCore.generateEngineBanner(statusText, isRootGranted) else ""
        outputIsPristineBanner = true
    }

    /**
     * 上报最新 ROOT 探测结果（MainActivity 每次前台 ON_RESUME 探测后调用）。
     * 仅在当前输出仍是纯横幅且无任务运行时原位重写横幅，保证权限行文案与真实探测一致，
     * 且不会覆盖已跑完任务的输出日志。
     */
    fun reportRootState(granted: Boolean) {
        isRootGranted = granted
        if (isTaskRunning || !outputIsPristineBanner) return
        val showBanner = appSettings?.showHyperCoreBanner ?: true
        if (!showBanner) return
        if (outputLog == HyperCore.generateEngineBanner("工作中", pristineBannerRoot)) {
            pristineBannerRoot = granted
            outputLog = HyperCore.generateEngineBanner("工作中", granted)
        }
    }

    suspend fun checkRoot(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && isRootGranted == true) return@withContext true

        val granted = withTimeoutOrNull(6000L) {
            try {
                val process = ProcessBuilder("su", "-c", "id").start()
                val output = process.inputStream.use { stream ->
                    InputStreamReader(stream).use { reader ->
                        val buffer = CharArray(256)
                        val count = reader.read(buffer)
                        if (count > 0) String(buffer, 0, count) else ""
                    }
                }
                val exitCode = process.waitFor()
                exitCode == 0 && output.contains("uid=0")
            } catch (_: Exception) {
                false
            }
        } ?: false

        withContext(Dispatchers.Main) {
            isRootGranted = granted
            // 若当前输出仍是纯横幅且无任务运行时，原位刷新横幅的 ROOT 权限行，
            // 保证引擎横幅与本次探测结果一致（与 MainActivity 路径 reportRootState 行为对齐）。
            reportRootState(granted)
        }
        granted
    }

    /**
     * 同步执行 root 命令，返回 (退出码, 合并输出)。
     *
     * 实现要点：
     * - 读流放在独立线程，避免输出超过管道缓冲时进程阻塞写、主线程阻塞读的死锁；
     * - 主线程 waitFor(timeoutMs) 做超时控制，超时强制 kill 并返回退出码 -1；
     * - stdout/stderr 合并（redirectErrorStream），保证错误信息可见。
     *
     * @param timeoutMs 超时毫秒（默认 120s）；耗时任务（安装大包等）可自行放宽。
     */
    fun runCommandSync(cmd: String, timeoutMs: Long = 120_000L): Pair<Int, String> {
        return try {
            val process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val output = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.use { stream ->
                        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(1024)
                            var count: Int
                            while (reader.read(buffer).also { count = it } != -1) {
                                output.append(buffer, 0, count)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(3000)
                return Pair(-1, output.toString() + "\n[shso] 命令执行超时（>${timeoutMs}ms），已强制终止")
            }
            readerThread.join(5000)
            Pair(process.exitValue(), output.toString())
        } catch (e: Exception) {
            Pair(-1, e.message ?: "执行异常")
        }
    }

    /**
     * 以 Root 把字节写入目标文件（覆盖或追加）。
     * 统一替代各处自建 `ProcessBuilder("su","-c","cat > …")` 的旁路出口
     * （TextCompare / TextEditorDialog / SecurityAuditLog），使 su 出口收敛。
     * 属内部模板命令（INTERNAL_APP），不走策略判定。
     */
    fun writeBytesAsRoot(targetPath: String, bytes: ByteArray, append: Boolean = false, timeoutSec: Long = 60): Boolean {
        return try {
            val redirect = if (append) ">>" else ">"
            val process = ProcessBuilder("su", "-c", "cat $redirect ${escapeShellArg(targetPath)}")
                .redirectErrorStream(true).start()
            process.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** 当前安全档位（AppSettings 未初始化时保守取标准防护）。 */
    fun currentSecurityLevel(): Int = appSettings?.securityLevel ?: SecurityLevels.STANDARD

    /** 返回 Root 执行的守卫 PATH；受保护档位下守卫不可用时返回 null。 */
    fun guardPathPrefix(): String? {
        val level = currentSecurityLevel()
        return GuardPathPolicy.prefixOrNull(
            securityLevel = level,
            guardReady = level < SecurityLevels.STANDARD || GuardModuleInstaller.guardBinDirReady()
        )
    }

    /**
     * 终端输入发送前评估（不动任何状态，主线程安全——纯字符串解析）。
     * @return null=可直接发送；Confirm=需弹风险确认框；Block=拒绝（调用方展示原因）
     */
    fun evaluateUserInput(text: String): Verdict? {
        if (text.isBlank()) return null
        return when (val v = RootCommandGateway.check(text, CommandSource.USER_TERMINAL)) {
            Verdict.Allow -> null
            is Verdict.Confirm -> v
            is Verdict.Block -> v
        }
    }

    /** 把拦截结果落审计并在终端输出拒绝原因。 */
    fun reportBlockedInput(block: Verdict.Block) {
        val reasons = block.findings.joinToString("\n") { "  · [${it.ruleId}] ${it.message}" }
        SecurityAuditLog.log(
            CommandSource.USER_TERMINAL, "BLOCK",
            block.findings.firstOrNull()?.ruleId, RiskLevel.CRITICAL, block.findings.firstOrNull()?.snippet ?: ""
        )
        appendOutputDirect("\n[shso 安全拦截] 已拒绝执行以下高危操作：\n$reasons\n")
    }

    /**
     * 执行脚本/二进制文件。
     *
     * 安全改造（方案 §6.1 / §8-P4）：
     * - [runAsRoot]：null=按档位自动（档位 3 默认非 Root，其余 Root）；true/false 显式指定（确认框勾选）；
     * - [riskApproved]：调用方（ExecuteConfirmDialog）已展示风险项并获用户确认；false 时（自动执行链路）
     *   档位 ≥2 下扫描脚本内容，CRITICAL 未获批直接拦截；
     * - chmod 777 → 仅 .so/.bin 等「直接执行」形态 chmod 755；.sh 一律经 `sh` 运行，不改动用户文件权限；
     * - 档位 ≥2 且守卫模块就绪时 PATH 前置 guard 目录（运行时拦截 rm/dd/mkfs 等）。
     */
    fun executeFile(filePath: String, runAsRoot: Boolean? = null, riskApproved: Boolean = false) {
        val file = File(filePath)
        val fileName = file.name
        val parentDir = file.parent ?: "/data/adb/shso"
        val isSh = fileName.endsWith(".sh", ignoreCase = true)
        val isSo = fileName.endsWith(".so", ignoreCase = true)

        if (!isSh && !isSo) {
            appendOutputDirect("\n[!] 错误: 不支持的文件格式，仅支持执行 .sh 和 .so 文件\n")
            return
        }

        val level = currentSecurityLevel()
        val useRoot = runAsRoot ?: (level < SecurityLevels.MAXIMUM)
        val guardPrefix = if (useRoot) guardPathPrefix() else ""
        if (guardPrefix == null) {
            SecurityAuditLog.log(
                CommandSource.SCRIPT_FILE, "BLOCK", "GUARD_UNAVAILABLE",
                RiskLevel.CRITICAL, filePath
            )
            appendOutputDirect("\n[shso 安全拦截] 守卫模块不可用，已拒绝 Root 执行\n")
            return
        }

        if (isTaskRunning) {
            killCurrentProcess()
        }

        // ── 安全门控：脚本内容扫描（自动执行等未经确认框的链路） ──
        if (level >= SecurityLevels.STANDARD && !riskApproved && isSh) {
            val (content, note) = ScriptAuditor.readScriptContent(filePath)
            if (content != null) {
                val report = ScriptAuditor.audit(content)
                val critical = report.findings.filter { it.level == RiskLevel.CRITICAL }
                if (critical.isNotEmpty()) {
                    val reasons = critical.joinToString("\n") { "  · 第 ${it.line} 行 [${it.ruleId}] ${it.message}" }
                    SecurityAuditLog.log(
                        CommandSource.SCRIPT_FILE, "BLOCK", critical.first().ruleId,
                        RiskLevel.CRITICAL, filePath
                    )
                    appendOutputDirect(
                        "\n[shso 安全拦截] 脚本内容含高危操作，已拒绝自动执行：\n$reasons\n" +
                            "（可在文件页手动点击「执行」并逐项确认风险后继续）\n"
                    )
                    return
                }
            } else if (note != "ok") {
                appendOutputDirect("\n[shso 安全提示] $note，已按保守策略拒绝自动执行\n")
                SecurityAuditLog.log(
                    CommandSource.SCRIPT_FILE, "BLOCK", "SCRIPT_UNREADABLE",
                    RiskLevel.DANGEROUS, filePath
                )
                return
            }
        }

        lastExecutedPath = filePath

        HyperCore.clearBatchQueue()
        val showHyperCore = appSettings?.showHyperCoreBanner ?: true
        val showShso = appSettings?.showShsoBanner ?: true

        refreshPristineBanner("工作中")
        isTaskRunning = true
        currentTaskName = fileName
        currentTaskPath = filePath
        taskStartTime = System.currentTimeMillis()
        lastExitCode = null

        // 拉起前台保活服务：切到后台后维持进程前台优先级，长脚本/下载/编译可持续运行。
        // 服务自行轮询 isTaskRunning，任务结束（完成/结束/重启）后自动 stopForeground+stopSelf。
        try {
            com.mixradio.droid.ShsoApplication.appContext.let { ctx ->
                ExecutionForegroundService.start(ctx)
            }
        } catch (_: Exception) {
        }

        if (showShso) {
            appendOutputDirect(HyperCore.generateTaskHeader(fileName, filePath, parentDir, showHyperCore))
        }
        appendOutputDirect("[shso Engine] 执行身份: ${if (useRoot) "Root" else "非 Root（档位 ${SecurityLevels.nameOf(level)}）"}\n")

        SecurityAuditLog.log(
            CommandSource.SCRIPT_FILE, "ALLOW", null, RiskLevel.SAFE,
            "$filePath (身份=${if (useRoot) "root" else "non-root"}, 档位=$level)"
        )

        HyperCore.startBatchFlushLoop(scope, { isTaskRunning }) { flushedText ->
            appendOutputDirect(flushedText)
        }

        executionJob?.cancel()
        executionJob = scope.launch(Dispatchers.IO) {
            var process: Process? = null
            var writer: OutputStreamWriter? = null
            try {
                val escapedParent = escapeShellArg(parentDir)
                val escapedFile = escapeShellArg(filePath)
                val execCmd = if (useRoot) {
                    if (isSh) {
                        // .sh：一律经 sh 运行，不给用户文件加执行位
                        "${guardPrefix}export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd $escapedParent && sh $escapedFile"
                    } else {
                        // .so：直接执行需要 +x，755 即可（不再 777）
                        "${guardPrefix}export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd $escapedParent && chmod 755 $escapedFile && ( $escapedFile || sh $escapedFile )"
                    }
                } else {
                    // 非 Root：普通 sh 执行（无 su 包装），改不动系统分区——档位 3 的主防线
                    "export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd $escapedParent 2>/dev/null; sh $escapedFile"
                }

                if (useRoot) {
                    process = ProcessBuilder("su", "-c", execCmd).redirectErrorStream(true).start()
                } else {
                    process = ProcessBuilder("sh", "-c", execCmd).redirectErrorStream(true).start()
                }
                activeProcess = process
                writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
                processWriter = writer

                try {
                    val pidField = process.javaClass.getDeclaredField("pid")
                    pidField.isAccessible = true
                    val pid = pidField.getInt(process)
                    withContext(Dispatchers.Main) {
                        processPid = pid
                    }
                } catch (_: Exception) {
                    processPid = 0
                }

                process.inputStream.use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(2048)
                        var count: Int
                        while (reader.read(buffer).also { count = it } != -1) {
                            val chunk = String(buffer, 0, count)
                            HyperCore.queueLogChunk(chunk)
                        }
                    }
                }

                val exitCode = process.waitFor()
                HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
                // 本轮执行仍是当前任务（代际判断）才写「退出」文案；被新任务/重启取代后由对方写
                withContext(Dispatchers.Main) {
                    if (executionJob === coroutineContext[Job]) {
                        lastExitCode = exitCode
                        if (appSettings?.showShsoBanner != false) {
                            appendOutputDirect("\n[shso Engine] 任务已退出，退出码: $exitCode\n")
                        }
                    }
                }
            } catch (e: Exception) {
                // 被取消（kill/restart/覆盖）时协程会抛 CancellationException，走到这里。
                // 「异常终止」文案仅在协程未取消、且仍是当前任务时输出；
                // flush/清理统一交给 finally 处理，避免取消路径上挂起引发二次抛错。
                if (coroutineContext[Job]?.isCancelled != true) {
                    withContext(Dispatchers.Main) {
                        if (executionJob === coroutineContext[Job]) {
                            lastExitCode = -1
                            if (appSettings?.showShsoBanner != false) {
                                appendOutputDirect("\n[shso Engine] 异常终止: ${e.message}\n")
                            }
                        }
                    }
                }
            } finally {
                // 任何取消路径（kill/restart/覆盖启动）必然走到这里；
                // 但只有本轮仍是当前执行协程（执行 Job 未被替换）时才清理 Compose 状态。
                // 关键：executionJob 在协程外已切换到新值（覆盖启动先 cancel 再赋新 job），
                // 因此 finally 里比较「执行 Job 是否仍是本轮协程」可判定代际。
                val isCurrentJob = executionJob === coroutineContext[Job]
                // 清理必须用局部引用：覆盖启动后全局 processWriter 已属于新任务，旧任务不得动它。
                // withContext(Dispatchers.Main)+NonCancellable：被取消协程的 finally 里不允许挂起切换，
                // 且必须保证「清理状态」这段即使协程已取消也完整执行。
                withContext(NonCancellable + Dispatchers.Main) {
                    try {
                        writer?.close()
                    } catch (_: Exception) {}
                    try {
                        process?.destroy()
                    } catch (_: Exception) {}
                    HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
                    if (isCurrentJob) {
                        isTaskRunning = false
                        currentTaskName = null
                        currentTaskPath = null
                        activeProcess = null
                        processWriter = null
                        processPid = 0
                    }
                }
            }
        }
    }

    /**
     * 发送终端输入。
     *
     * 安全改造（方案 §8-P1/P3）：
     * - 一次性命令分支（无任务运行）：[confirmed]=false 时先经 RootCommandGateway 判定，
     *   Block 拒绝并落审计；档位 ≥2 且守卫就绪时 PATH 前置守卫目录；
     * - 交互分支（任务运行中，输入直写常驻 shell）：无法整体拦截，档位 ≥2 时仅拦
     *   CRITICAL 硬规则（rm 系统 / dd 块设备 / mkfs / wipe 等），其余放行但落审计。
     *
     * @param confirmed 调用方已通过风险确认框获用户同意（CommandRiskDialog → 确认执行）
     */
    fun sendInput(text: String, confirmed: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                if (isTaskRunning && processWriter != null) {
                    // ── 交互态：硬规则拦截（fail on critical），其余放行 + 审计 ──
                    if (!confirmed) {
                        val hard = RootCommandGateway.checkInteractiveHardRules(text)
                        if (hard != null) {
                            withContext(Dispatchers.Main) {
                                reportBlockedInput(hard)
                            }
                            return@launch
                        }
                    }
                    withContext(Dispatchers.Main) {
                        appendOutputDirect(if (text.isEmpty()) "\n" else "$text\n")
                    }
                    SecurityAuditLog.log(
                        CommandSource.USER_TERMINAL, "ALLOW", null, RiskLevel.SAFE,
                        "[交互态] $text"
                    )
                    processWriter?.write(text + "\n")
                    processWriter?.flush()
                } else if (text.isNotEmpty()) {
                    // ── 一次性命令：完整策略判定 ──
                    if (!confirmed) {
                        when (val v = RootCommandGateway.check(text, CommandSource.USER_TERMINAL)) {
                            is Verdict.Block -> {
                                withContext(Dispatchers.Main) {
                                    reportBlockedInput(v)
                                }
                                return@launch
                            }
                            is Verdict.Confirm -> {
                                // 未经确认框的高危命令：拒绝（正常链路应由 TerminalPage 先弹框）
                                withContext(Dispatchers.Main) {
                                    SecurityAuditLog.log(
                                        CommandSource.USER_TERMINAL, "BLOCK",
                                        v.findings.firstOrNull()?.ruleId, v.level, text
                                    )
                                    appendOutputDirect("\n[shso 安全拦截] 高危命令需经风险确认（${v.findings.firstOrNull()?.message ?: ""}）\n")
                                }
                                return@launch
                            }
                            Verdict.Allow -> {}
                        }
                    }
                    withContext(Dispatchers.Main) {
                        appendOutputDirect("> $text\n")
                    }
                    val guardPrefix = guardPathPrefix()
                    if (guardPrefix == null) {
                        SecurityAuditLog.log(
                            CommandSource.USER_TERMINAL, "BLOCK", "GUARD_UNAVAILABLE",
                            RiskLevel.CRITICAL, text
                        )
                        withContext(Dispatchers.Main) {
                            appendOutputDirect("\n[shso 安全拦截] 守卫模块不可用，已拒绝 Root 命令\n")
                        }
                        return@launch
                    }
                    val guardedCmd = guardPrefix + text
                    val (exitCode, output) = runCommandSync(guardedCmd)
                    SecurityAuditLog.log(
                        CommandSource.USER_TERMINAL, "ALLOW", null, RiskLevel.SAFE, text, exitCode = exitCode
                    )
                    withContext(Dispatchers.Main) {
                        if (output.isNotEmpty()) {
                            appendOutputDirect(output)
                        }
                        if (exitCode != 0) {
                            appendOutputDirect("[退出码: $exitCode]\n")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutputDirect("[发送失败: ${e.message}]\n")
                }
            }
        }
    }

    fun killCurrentProcess() {
        // 同步捕获本轮任务实体（Job/pid/进程句柄/任务名）：
        // 之后主线程若启动新任务（覆盖启动），这些仍是旧实体，kill 只作用于它们，绝不误杀新任务。
        val targetJob = executionJob ?: return
        val targetPid = processPid
        val targetProcess = activeProcess
        val targetName = currentTaskName

        scope.launch(Dispatchers.IO) {
            try {
                if (targetPid > 0) {
                    runCommandSync("kill -9 $targetPid 2>/dev/null")
                }
                targetName?.let { taskName ->
                    // pkill -f 的 pattern 为 ERE 正则，文件名含正则元字符时由 shell 引号包裹兜底；
                    // 与旧实现 `pkill -f '$escapedTaskName'` 行为一致（仅做 shell 层防注入）
                    runCommandSync("pkill -9 -f ${escapeShellArg(taskName)} 2>/dev/null")
                }
                targetProcess?.destroyForcibly()
                // 本轮仍由本 kill 接管（执行 Job 未被替换）才撤销全局句柄；
                // 若期间新任务已启动，句柄属于新任务，由新任务线条负责。
                if (executionJob === targetJob) {
                    activeProcess = null
                    processWriter = null
                }
                // 进程已杀，任务实体已终止：显式取消执行协程并等待其 finally 收尾，
                // 保证旧任务在 kill 写状态之前完成清理，避免交叉写 Compose 状态。
                targetJob.cancel()
                targetJob.join()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (executionJob === targetJob) {
                        if (appSettings?.showShsoBanner != false) {
                            appendOutputDirect("\n[shso Engine] 结束进程失败: ${e.message}\n")
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    // 仅当本轮仍是当前执行协程时才 flush/清理/写文案：
                    // 若期间新任务已启动（executionJob 已替换），旧任务的残留日志不应混入新任务输出，
                    // 交由 executeFile 的 clearBatchQueue 与新的 flush loop 自行处理。
                    if (executionJob === targetJob) {
                        HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
                        isTaskRunning = false
                        currentTaskName = null
                        currentTaskPath = null
                        lastExitCode = 137
                        processPid = 0
                        if (appSettings?.showShsoBanner != false) {
                            appendOutputDirect("\n[shso Engine] 用户已手动结束进程\n")
                        }
                    }
                }
            }
        }
    }

    fun sendInterrupt() {
        // 捕获本轮任务实体，避免探测期间新任务替换导致误判
        val targetJob = executionJob
        scope.launch(Dispatchers.IO) {
            try {
                if (isTaskRunning) {
                    withContext(Dispatchers.Main) {
                        appendOutputDirect("^C\n")
                    }
                    processWriter?.write(3)
                    processWriter?.write("\n")
                    processWriter?.flush()

                    if (processPid > 0) {
                        runCommandSync("kill -2 $processPid 2>/dev/null")
                    }

                    // 进程未必响应 SIGINT：等待短暂窗口后仍未退出，则提示用户可用「结束进程」强制兜底，
                    // 避免 UI 一直显示 RUNNING 却无任何说明。
                    delay(3000)
                    withContext(Dispatchers.Main) {
                        if (isTaskRunning && executionJob === targetJob) {
                            appendOutputDirect("\n[shso Engine] 进程未响应 SIGINT，可点击「结束进程」强制终止\n")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutputDirect("[中断失败: ${e.message}]\n")
                }
            }
        }
    }

    fun restartTerminal() {
        // 同步捕获旧任务实体：重启只作用于旧实体，新任务启动后由新线条负责
        val targetJob = executionJob
        val targetPid = processPid
        val targetProcess = activeProcess

        scope.launch(Dispatchers.IO) {
            try {
                targetJob?.cancel()
                if (targetPid > 0) {
                    runCommandSync("kill -9 $targetPid 2>/dev/null")
                }
                targetProcess?.destroyForcibly()
                // 旧任务 finally 已通过代际判断清理状态；若期间新任务启动，
                // 不能再动全局句柄（属于新任务）
                if (executionJob === targetJob) {
                    activeProcess = null
                    processWriter = null
                }
                // 等待旧任务的 finally 清理完成，避免与下方状态写入并发
                targetJob?.join()
                HyperCore.clearBatchQueue()
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    // 仅当本轮仍是当前执行协程时才清理状态并恢复横幅；
                    // 若期间新任务已启动（executionJob 已替换），统一交由新任务线条处理
                    if (executionJob === targetJob || targetJob == null) {
                        isTaskRunning = false
                        currentTaskName = null
                        currentTaskPath = null
                        taskStartTime = 0L
                        lastExitCode = null
                        processPid = 0
                        refreshPristineBanner("工作中")
                    }
                }
            }
        }
    }

    fun clearOutput() {
        HyperCore.clearBatchQueue()
        outputIsPristineBanner = false
        outputLog = ""
    }

    private fun appendOutputDirect(text: String) {
        outputIsPristineBanner = false
        outputLog = HyperCore.appendWithSlidingWindow(outputLog, text)
    }
}
