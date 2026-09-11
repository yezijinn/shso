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
    @Volatile
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

    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var processWriter: OutputStreamWriter? = null
    @Volatile
    private var executionJob: Job? = null

    /**
     * 本次（或最近一次）执行所属**进程组组长 pid**。
     *
     * 为什么需要它：`su -c` 会把命令放进一个**新的会话/进程组**，`$$` 即组长 pid，
     * 而该组长会被重挂到 init（ppid=465）——应用手上的 `Process` 句柄只对应 `su` 本身，
     * 对它发信号既杀不到 `sh -c …`，也杀不到真正的脚本进程，于是中断后留下孤儿继续跑。
     * 实测：`kill -2 -- -<pgid>` 可一次回收整组（toybox `kill` 支持负 pid）。
     *
     * 该值跨任务结束后**刻意保留**，供「结束进程」兜底回收被中断后逃逸的子孙进程；
     * 只在下一轮执行开始时被覆盖。
     */
    @Volatile
    private var runPgid: Int = 0

    /**
     * 记录进程组 id 的文件。放**应用私有目录**（`files/`，仅本应用 uid 与 root 可写），
     * 绝不放 `/data/adb/shso`（实测 0777）——否则任何应用都能伪造一个 pgid，
     * 让本应用以 root 对任意进程组执行 `kill -9`。
     */
    private val runPgidFile: File?
        get() = runCatching { File(com.mixradio.droid.ShsoApplication.appContext.filesDir, ".run.pgid") }.getOrNull()

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
        // 在分页/拖动期间 outputLog 最大 250k 字符，== 仍是 O(N) 字节扫描。
        // 这里先把生成的 banner 缓存一次，再加长度快速短路：长度不等 ⇒ 一定不是当前横幅；
        // 长度相等再做一次完整 equals。在 250k 字符串场景下把最坏比较降到 1 次长度读取。
        val expected = HyperCore.generateEngineBanner("工作中", pristineBannerRoot)
        if (outputLog.length == expected.length && outputLog == expected) {
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
        val prefix = GuardPathPolicy.prefixOrNull(
            securityLevel = level,
            guardReady = level < SecurityLevels.STANDARD || GuardModuleInstaller.guardBinDirReady()
        )
        // 守卫恢复可用后重置降级告警，使其在「再次失效」时仍能提醒
        if (prefix != null) guardDegradeWarned = false
        return prefix
    }

    /** 守卫不可用告警是否已输出（只提醒一次，避免刷屏）。 */
    @Volatile
    private var guardDegradeWarned = false

    /**
     * 档位要求运行时守卫、但守卫不可用时的降级处理。
     *
     * 策略（用户已确认「自动安装 + 不再阻断」）：**不阻断，只告警**。
     * 原因：档位 2 是默认档位，早期实现下守卫未安装会拒绝一切 root 执行
     * （连 `ls` 都跑不了），使默认档位实际不可用。配合 `GuardModuleInstaller.ensureInstalled()`
     * 的启动期自动安装，正常情况下守卫就是就绪的；此处只处理安装失败的兜底。
     *
     * 每次都会落审计（ruleId=GUARD_UNAVAILABLE_DEGRADED），便于事后追溯到
     * 「这次执行发生时没有运行时守卫」。
     */
    private fun reportGuardDegraded(source: CommandSource, detail: String) {
        SecurityAuditLog.log(source, "BLOCK", "GUARD_UNAVAILABLE_DEGRADED", RiskLevel.DANGEROUS, detail)
        if (guardDegradeWarned) return
        guardDegradeWarned = true
        scope.launch(Dispatchers.Main) {
            appendOutputDirect(
                "\n[shso 安全提示] 当前档位要求运行时守卫，但守卫模块不可用。\n" +
                    "  本次执行仅有静态审查保护（无运行时拦截 rm/dd/mkfs 等）。\n" +
                    "  可在「设置 → 安全档位」中安装守卫模块以恢复完整防护。\n"
            )
        }
    }

    /** 取守卫 PATH 前缀；守卫不可用时落审计 + 首次告警并返回空串（不阻断执行）。 */
    private fun guardPrefixOrDegrade(source: CommandSource, detail: String): String {
        val prefix = guardPathPrefix()
        if (prefix != null) return prefix
        reportGuardDegraded(source, detail)
        return ""
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
        // 守卫不可用时不再阻断（档位 2 是默认档位，阻断会让默认档位完全不可用）；
        // 改为落审计 + 首次醒目告警后放行。守卫的自动安装由 GuardModuleInstaller.ensureInstalled 负责。
        val guardPrefix = if (useRoot) guardPrefixOrDegrade(CommandSource.SCRIPT_FILE, filePath) else ""

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
                // 开跑前清掉上一轮的记录并作废内存值，确保随后读到的 pgid 一定来自本次执行。
                runCatching { runPgidFile?.delete() }
                runPgid = 0
                // 把本次执行的「进程组组长 pid」落盘：su 会新建会话，`$$` 即组长 pid（真机已验证
                // `pid == pgrp == sid`）。仅 root 路径记录 —— 非 root 走 ProcessBuilder("sh")，
                // 子进程沿用应用自身进程组，记录后会诱导误杀本应用所在的组。
                val pgidRecorder = if (useRoot) {
                    runPgidFile?.let { "echo " + "\$\$" + " > " + escapeShellArg(it.absolutePath) + "; " } ?: ""
                } else ""
                val execCmd = if (useRoot) {
                    if (isSh) {
                        // .sh：一律经 sh 运行，不给用户文件加执行位
                        "${pgidRecorder}${guardPrefix}export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd $escapedParent && sh $escapedFile"
                    } else {
                        // .so：直接执行需要 +x，755 即可（不再 777）
                        "${pgidRecorder}${guardPrefix}export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd $escapedParent && chmod 755 $escapedFile && ( $escapedFile || sh $escapedFile )"
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

                // 直接子进程（su）pid：仅用于日志与兜底 destroy。
                // 注意：Android 的 java.lang.Process **没有** pid() 方法（实测编译不过），
                // 所以任务清单里「改用 Process.pid()」的建议不可行；此处保留反射并容错，
                // 失败退回 0 —— 中断与回收的正确性由下面的**进程组 kill** 保证，不再依赖此 pid。
                val childPid = runCatching {
                    val pidField = process.javaClass.getDeclaredField("pid")
                    pidField.isAccessible = true
                    pidField.getInt(process)
                }.getOrDefault(0)
                withContext(Dispatchers.Main) {
                    processPid = childPid
                }

                // 异步取回本次执行的进程组 id（不阻塞输出读取）。任务结束后刻意保留，
                // 供「结束进程」兜底回收被中断后逃逸到 init 下的子孙进程。
                scope.launch { runPgid = awaitRunPgid() }

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
                    // 守卫不可用时不再拒绝命令，改为告警后放行（见 reportGuardDegraded 说明）
                    val guardPrefix = guardPrefixOrDegrade(CommandSource.USER_TERMINAL, text)
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
        // 同步捕获本轮任务实体（Job/pid/进程组/进程句柄/任务名）：
        // 之后主线程若启动新任务（覆盖启动），这些仍是旧实体，kill 只作用于它们，绝不误杀新任务。
        val targetJob = executionJob
        val targetPid = processPid
        val targetPgid = runPgid
        val targetProcess = activeProcess
        val targetName = currentTaskName
        // 前置条件已放宽：旧实现 `if (!isTaskRunning) return` 使「中断后 UI 已显示待命中、
        // 但子孙进程仍在跑」的场景彻底无法回收（真机实测：点「结束进程」没有任何效果，
        // 孤儿 `sh flood.sh` 继续以 10% CPU 运行）。现在只要还留有执行句柄 / 进程组记录 /
        // 任务名，就仍允许兜底回收。
        if (!isTaskRunning && targetProcess == null && targetPgid <= 1 && targetName == null) return

        scope.launch(Dispatchers.IO) {
            try {
                // 先杀**整个进程组**：`su -c` 把命令放进新会话且组长被重挂到 init，
                // 中断后逃逸的子孙进程（`sh -c …`、真正的脚本进程）只有这样才能回收。
                if (targetPgid > 1) {
                    killProcessGroup(9, targetPgid)
                }
                if (targetPid > 0) {
                    runCommandSync("kill -9 $targetPid 2>/dev/null")
                }
                targetName?.let { taskName ->
                    // pkill -f 的 pattern 为 ERE 正则，文件名含正则元字符时由 shell 引号包裹兜底；
                    // 与旧实现 `pkill -f '$escapedTaskName'` 行为一致（仅做 shell 层防注入）
                    runCommandSync("pkill -9 -f ${escapeShellArg(taskName)} 2>/dev/null")
                }
                targetProcess?.destroyForcibly()
                forceCloseProcess(targetProcess)
                // 本轮仍由本 kill 接管（执行 Job 未被替换）才撤销全局句柄；
                // 若期间新任务已启动，句柄属于新任务，由新任务线条负责。
                if (executionJob === targetJob) {
                    activeProcess = null
                    processWriter = null
                }
                // 进程已杀，任务实体已终止：显式取消执行协程并等待其 finally 收尾，
                // 保证旧任务在 kill 写状态之前完成清理，避免交叉写 Compose 状态。
                // 有界等待，避免轮询线程被不响应的 finally 永久挂起。
                // （放宽前置条件后 targetJob 可能为 null，见函数头注释）
                targetJob?.cancel()
                withTimeoutOrNull(2000L) { targetJob?.join() }
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

    /**
     * destroyForcibly 后强制关闭进程管道三件套（stdin/stdout/stderr），
     * 使阻塞在 inputStream 读取上的执行协程立即收到 EOF 退出，彻底释放管道缓冲，消除僵尸句柄。
     * 各流关闭均捕获异常，单流失败不影响其余流。
     */
    private fun forceCloseProcess(targetProcess: Process?) {
        if (targetProcess == null) return
        try { targetProcess.inputStream.close() } catch (_: Exception) {}
        try { targetProcess.errorStream.close() } catch (_: Exception) {}
        try { targetProcess.outputStream.close() } catch (_: Exception) {}
    }

    /** 读取本轮执行记录的进程组 id（root 侧执行 `echo $$ > file` 写入）。 */
    private fun readRunPgidFile(): Int =
        runCatching { runPgidFile?.readText()?.trim()?.toIntOrNull() ?: 0 }.getOrDefault(0)

    /** 有界轮询等待本轮 pgid 落盘（脚本刚启动时文件可能尚未写出）。 */
    private suspend fun awaitRunPgid(): Int {
        repeat(15) {
            val v = readRunPgidFile()
            if (v > 1) return v
            delay(100)
        }
        return 0
    }

    /**
     * 对**整个进程组**发信号（负 pid）。这是回收 `su` 之下被重挂到 init 的子孙进程的唯一可靠手段
     * （实测：只杀 `su`/直接子进程会留下 `sh -c …` 与真正的脚本进程继续占 CPU）。
     *
     * 安全校验见文件末尾的顶层纯函数 [buildProcessGroupKillCommand]（返回 null 即放弃）。
     */
    private fun killProcessGroup(signal: Int, pgid: Int) {
        val cmd = buildProcessGroupKillCommand(signal, pgid, android.os.Process.myPid()) ?: return
        runCommandSync(cmd)
    }

    fun sendInterrupt() {
        // 同步捕获本轮任务实体（Job/进程 writer/pid）：
        // 之后主线程若启动新任务（覆盖启动），这些仍是旧实体，中断只作用于它们，绝不误伤新任务。
        val targetJob = executionJob
        val targetWriter = processWriter
        val targetPid = processPid
        val targetPgid = runPgid
        scope.launch(Dispatchers.IO) {
            try {
                if (isTaskRunning) {
                    withContext(Dispatchers.Main) {
                        appendOutputDirect("^C\n")
                    }
                    // ProcessBuilder 起的子进程没有 TTY，\u0003 (\u0003) 经 stdin 写入只是普通字符，
                    // 不会触发 SIGINT；真正能中断的是下方 `kill`。旧版两个都写会误导后来阅读
                    // 代码的人以为 ETX 起了作用，这里明确移除冗余 IO。
                    targetWriter?.write("\n")
                    targetWriter?.flush()

                    // 必须对**整个进程组**发 SIGINT：`su -c` 把命令放进新会话，组长会被重挂到
                    // init（ppid=465），只杀直接子进程会留下 `sh -c …` 与真正的脚本进程继续占 CPU
                    // （真机实测：flood.sh 被中断后仍以 10% CPU 运行数分钟）。
                    if (targetPgid > 1) {
                        killProcessGroup(2, targetPgid)
                    }
                    // 兜底：直接子进程也发一次（个别 su 实现下它就是组长）
                    if (targetPid > 0) {
                        runCommandSync("kill -2 $targetPid 2>/dev/null")
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
                forceCloseProcess(targetProcess)
                // 旧任务 finally 已通过代际判断清理状态；若期间新任务启动，
                // 不能再动全局句柄（属于新任务）
                if (executionJob === targetJob) {
                    activeProcess = null
                    processWriter = null
                }
                // 等待旧任务的 finally 清理完成，避免与下方状态写入并发；
                // 有界等待，避免被不响应的 finally 永久挂起。
                withTimeoutOrNull(2000L) { targetJob?.join() }
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

/**
 * 构造「对整进程组发信号」的 shell 命令（**顶层纯函数**，便于 JVM 单测；`RootService` 是 object，
 * 其初始化依赖 Android/Compose，单元测试里无法加载）。
 *
 * 背景：`su -c` 会把命令放进新的会话/进程组，组长随即被重挂到 init（ppid=465），
 * 因此只对直接子进程（`su`）发信号杀不到 `sh -c …` 与真正的脚本进程 —— 它们会成为孤儿继续占 CPU。
 * 正确做法是对整组发信号（负 pid，toybox `kill` 支持）。
 *
 * 三重安全校验（fail-closed，任一不满足即不杀，靠 `&&` 短路保证）：
 * ① `pgid > 1`；
 * ② `/proc/<pgid>/stat` 第 5 字段 == pgid —— 确认它确实是**进程组组长**；
 * ③ 本应用自己的 pgrp != 该 pgid —— 防止某些 `su` 实现不新建会话时，误杀应用自身所在的进程组。
 *
 * 返回 `null` 表示不安全，调用方必须放弃 kill。
 */
internal fun buildProcessGroupKillCommand(signal: Int, pgid: Int, myPid: Int): String? {
    if (pgid <= 1) return null
    return "P=$pgid; M=$myPid; " +
        "[ -r /proc/\$P/stat ] && " +
        "[ \"\$(cut -d' ' -f5 /proc/\$P/stat)\" = \"\$P\" ] && " +
        "[ \"\$(cut -d' ' -f5 /proc/\$M/stat)\" != \"\$P\" ] && " +
        "kill -$signal -- -\$P"
}
