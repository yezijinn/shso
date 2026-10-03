// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

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

    /**
     * 生成 `pkill -f` 可用的**字面量**匹配串。
     *
     * [escapeShellArg] 只防 shell 解释，对 `pkill -f` 无效 —— 后者把参数当**扩展正则**（ERE）
     * 匹配整条 cmdline。于是路径里的正则元字符会被解释：`/data/adb/shso/v1.2.sh` 的 `.`
     * 匹配任意字符，能一并命中 `v1X2yzh`。该兜底路径以 root 身份执行，误杀的是任意 uid 的进程。
     *
     * 实现放在 [com.mixradio.droid.data.security.ShellEscapes]：纯字符串函数，
     * 不该挂在带 Android 静态初始化的 `RootService` 上，否则 JVM 单测无法加载。
     */
    internal fun escapeEreLiteral(literal: String): String =
        com.mixradio.droid.data.security.ShellEscapes.escapeEreLiteral(literal)

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
     * `su -c` 会把命令放进新的会话与进程组，`$$` 即组长 pid，且该组长会被重挂到 init。
     * 应用持有的 `Process` 只对应 `su` 本身，对它发信号杀不到 `sh -c …` 与真正的脚本进程。
     * 按进程组发信号（`kill -2 -- -<pgid>`，toybox `kill` 支持负 pid）可整组回收。
     *
     * 任务结束后刻意保留，供「结束进程」兜底回收逃逸的子孙进程；下一轮执行开始时覆盖。
     */
    @Volatile
    private var runPgid: Int = 0

    /**
     * 记录进程组 id 的文件。必须放应用私有目录（`files/`）；
     * `/data/adb/shso` 为 0777，任何应用都能伪造 pgid 使本应用以 root 杀任意进程组。
     */
    private val runPgidFile: File?
        get() = runCatching { File(com.mixradio.droid.ShsoApplication.appContext.filesDir, ".run.pgid") }.getOrNull()

    /**
     * 终端一次性命令的代际计数。
     *
     * 终端命令与脚本任务共用「当前活动进程」这一套全局状态（`isTaskRunning` / `activeProcess` /
     * `processPid` / `runPgid`），因此必须保证**旧命令退出时不会把新命令/新任务的状态误清成「待命中」**。
     * 启动新命令（[runTerminalCommand]）与新任务（[executeFile]）都会自增本计数，
     * 命令退出时只有计数未变才清理状态。
     */
    private val terminalCommandGeneration = AtomicLong(0)

    /**
     * 「当前活动进程」槽位的**同步占用标记**。
     *
     * 原实现只有 `isTaskRunning` 一个判据，而它要到 [runTerminalCommand] 内部才置 true，
     * 其间要跨过：派发到 IO → `withContext(Main)` 往返 → `ProcessBuilder("su")`。
     * 这段窗口里 [isTerminalBusy] 恒为 false，于是：
     *  - 连点两次「发送」→ 两条命令同时起来；
     *  - 终端命令与文件页「执行」并发 → 两条执行线各自写 `activeProcess` / `currentTaskName`，
     *    后者还会 cancel 掉前者的批量发布循环（**前者的输出从此不再显示**），
     *    且共用同一个 `.run.pgid` 互相覆盖，「结束进程」会杀错对象。
     *
     * 标记在**判定之前**同步置位（CAS），使判定与占用成为一个临界区；
     * [runTerminalCommand] 再按代际自检，标记已被别人抢走就直接杀掉自己刚起的进程。
     * 值为 0 表示空闲。
     */
    private val terminalSlotOwner = AtomicLong(0)

    /**
     * 尝试同步占用槽位。成功返回本次的占用令牌（从 1 开始），已被占用返回 0。
     * 令牌即发起方的 [nextSlotToken]，用于后续自检与释放。
     */
    private fun tryClaimTerminalSlot(): Long {
        val token = nextSlotToken.incrementAndGet()
        return if (terminalSlotOwner.compareAndSet(0L, token)) token else 0L
    }

    private fun releaseTerminalSlot(token: Long) {
        terminalSlotOwner.compareAndSet(token, 0L)
    }

    /**
     * 是否仍持有「执行权」。
     *
     * 不能只比 `executionJob`：终端一次性命令**从不写** executionJob，
     * 该判据对它是恒真的（两端同为 null，或同为上一次脚本的已完成 Job）。
     * 恒真的后果是：结束/重启一条命令时，其收尾会把**期间新启动的那条**的状态清掉、
     * 停掉它的批量发布循环 —— 进程还在跑，顶栏却显示「待命中」，
     * 「中断 / 结束进程」被禁用，用户失去唯一出口。
     *
     * 判据 = 「脚本任务看 executionJob」或「终端命令看槽位令牌」任一成立。
     * 槽位令牌在命令结束的 finally 里归还，startExecution 起新任务时置 0，
     * 因此「新任务已接管」时两者皆不成立。
     */
    private fun stillOwnsExecution(targetJob: Job?): Boolean =
        (targetJob != null && executionJob === targetJob) ||
            (targetJob == null && terminalSlotOwner.get() != 0L)

    private val nextSlotToken = AtomicLong(0)

    /** 交互态 stdin 写入互斥：同一进程可能同时有多个 sendInput 协程（连点两次「发送」）。 */
    private val interactiveWriteLock = Any()

    /**
     * 当前生效的批量发布循环（脚本任务或终端命令之一）。
     * [killCurrentProcess] / [restartTerminal] 的收尾要按它停止循环，
     * 否则旧任务的收尾会停掉期间新启动那条命令的循环。
     */
    @Volatile
    private var activeFlushLoop: Job? = null

    /**
     * 终端一次性命令的终止兜底上限。
     *
     * 终端命令由用户手动发起、手动终止（「中断 / 结束进程」），因此**不能**用 [runCommandSync]
     * 那种 120s 超时去砍——`curl` 大文件、`find /`、长时间日志采集都会超 2 分钟，
     * 被砍等于半途而废。此上限只用于兜住 `su` 授权弹窗挂起这类**永不返回**的情况
     * （脚本任务链路同理，靠用户终止）。
     */
    private const val TERMINAL_COMMAND_TIMEOUT_MS = 30 * 60_000L

    /**
     * 终端命令拉起后台保活服务的延迟：短命令（`ls` / `echo`）不值得闪一条通知，
     * 超过该时长的命令才进前台保活（切后台不被回收 + 通知内可「结束进程」）。
     */
    private const val KEEPALIVE_DELAY_MS = 5_000L

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
        val showHyperCoreBanner = appSettings?.showHyperCoreBanner ?: true
        pristineBannerRoot = if (showHyperCoreBanner) isRootGranted else null
        outputLog = if (showHyperCoreBanner) HyperCore.generateEngineBanner(statusText, isRootGranted) else ""
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
        val showHyperCoreBanner = appSettings?.showHyperCoreBanner ?: true
        if (!showHyperCoreBanner) return
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

        // runCommandSync 用独立读线程 + waitFor(timeout) + destroyForcibly 实现可中断超时；
        // 直接用 process.waitFor() 会阻塞在 JNI 上无法取消，授权弹窗挂起时会泄漏 su 进程。
        val (code, out) = runCommandSync("id", timeoutMs = 6000L)
        val granted = code == 0 && out.contains("uid=0")

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
            // 立即关闭子进程 stdin：本函数只读输出、从不喂输入。若不关闭，任何会读 stdin 的命令
            // （cat / read / 等 EOF 的交互式命令）都会一直阻塞到 timeoutMs（默认 120s）才返回。
            runCatching { process.outputStream.close() }
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
     * 直接子进程 pid。Android 的 `java.lang.Process` 没有 `pid()`，只能反射取；失败退回 0。
     * 仅用于展示与兜底 `destroy`，回收正确性由进程组 kill 保证，不依赖此值。
     */
    private fun pidOfProcess(process: Process): Int = runCatching {
        val pidField = process.javaClass.getDeclaredField("pid")
        pidField.isAccessible = true
        pidField.getInt(process)
    }.getOrDefault(0)

    /** 命令的展示名：折叠首尾空白并截断，用于「运行中」状态与通知标题。 */
    private fun commandDisplayName(command: String): String {
        val trimmed = command.trim()
        return if (trimmed.length > 48) trimmed.take(48) + "…" else trimmed
    }

    /**
     * 执行终端里键入的一次性命令，并把它登记为当前活动进程。
     *
     * 与 [runCommandSync] 的差别（面向交互式终端）：
     * - 输出**边读边回吐**（经 [HyperCore] 批量队列），不再缓冲到命令结束才一次性显示：
     *   此前 `ping` / 下载类命令全程无任何输出，直到超时才把结果一次刷出；
     * - 命令期间置 `isTaskRunning`，顶栏显示「运行中」、「中断 / 结束进程」可用；
     * - 记录 `su -c` 会话的进程组 pid（`echo $$`），使中断 / 结束进程能**整组**回收 ——
     *   只杀 `su` 会把 `sh -c …` 及其子孙留成 init 名下的孤儿。
     *
     * **刻意不写 `currentTaskPath`**：该字段是「结束进程」在进程组不可用时的 pkill 匹配路径，
     * 填命令文本会匹配到无关进程。
     *
     * @param command 完整的 shell 命令
     * @param displayName 展示名（「运行中」状态与通知标题）
     * @param slotToken 本次占用的槽位令牌；非 0 时在起进程后自检归属，被抢走就直接杀掉自己
     * @return 退出码；超时返回 -1
     */
    private suspend fun runTerminalCommand(command: String, displayName: String, slotToken: Long = 0L): Int {
        val generation = terminalCommandGeneration.incrementAndGet()
        // 先作废上一轮的 pgid 记录再启动，确保随后读到的一定来自本次命令（与 executeFile 同法）。
        runCatching { runPgidFile?.delete() }
        runPgid = 0
        // 清残留必须发生在**启动进程之前**：进程一旦起来就可能立刻吐输出，
        // 之后再 clear 会把这段输出丢掉。
        HyperCore.clearBatchQueue()
        val pgidRecorder = runPgidFile?.let { "echo " + "\$\$" + " > " + escapeShellArg(it.absolutePath) + "; " } ?: ""
        val process = try {
            ProcessBuilder("su", "-c", pgidRecorder + command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendOutputDirect("[执行失败: ${e.message}]\n") }
            return -1
        }
        // 关闭子进程 stdin：本通道只读输出、从不喂输入（交互输入走 processWriter 那条链路）。
        runCatching { process.outputStream.close() }

        // 槽位归属自检：起进程期间可能已被 kill/新任务抢走，此时不得再写全局句柄，
        // 否则会覆盖别人的 activeProcess/currentTaskName，并 cancel 掉对方的发布循环。
        if (slotToken != 0L && terminalSlotOwner.get() != slotToken) {
            runCatching { process.destroyForcibly() }
            withContext(Dispatchers.Main) {
                appendOutputDirect("\n[shso] 任务槽位已被接管，本次命令已取消\n")
            }
            return -1
        }

        withContext(Dispatchers.Main) {
            isTaskRunning = true
            currentTaskName = displayName
            currentTaskPath = null
            taskStartTime = System.currentTimeMillis()
            lastExitCode = null
            activeProcess = process
            processWriter = null
            processPid = pidOfProcess(process)
        }
        // 复用任务用的批量发布通道：它按 isTaskRunning 存活，命令结束即自行退出并 flush 残留。
        // 记下归属令牌：命令可重叠，收尾时只能停自己那个循环。
        val flushLoop = HyperCore.startBatchFlushLoop(scope, { isTaskRunning }) { appendOutputDirect(it) }
        activeFlushLoop = flushLoop
        // 长命令保活（与脚本任务同源）：切到后台后不被 ROM 立刻回收，通知里也提供「结束进程」出口。
        // 延迟 [KEEPALIVE_DELAY_MS] 再拉起，避免 `ls` / `echo` 这类秒回命令闪一下通知。
        scope.launch {
            delay(KEEPALIVE_DELAY_MS)
            if (isTaskRunning && terminalCommandGeneration.get() == generation) {
                runCatching { ExecutionForegroundService.start(com.mixradio.droid.ShsoApplication.appContext) }
            }
        }
        // 进程组 pid 必须按代际回填：脚本/命令启动后 1.5s 内会持续轮询，若期间已有新命令接管，
        // 旧轮询读到的新值是同一个（无害），但超时归零会把新命令的 pgid 冲掉，导致中断/结束进程失效。
        scope.launch {
            val pgid = awaitRunPgid()
            if (terminalCommandGeneration.get() == generation) runPgid = pgid
        }

        val readerThread = Thread {
            try {
                process.inputStream.use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(1024)
                        var count: Int
                        while (reader.read(buffer).also { count = it } != -1) {
                            HyperCore.queueLogChunk(String(buffer, 0, count))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val finished = runCatching { process.waitFor(TERMINAL_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            forceCloseProcess(process)
        }
        readerThread.join(3000)
        val exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1

        withContext(Dispatchers.Main) {
            // 先停发布循环（并等它刷完本地积压），再写超时说明——
            // 否则命令最后 ≤250ms 的输出会落在说明行之后。
            // 带令牌：只停本命令的循环，不误停重叠命令的。
            HyperCore.stopBatchFlushLoop(flushLoop)
            HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
            if (!finished) {
                appendOutputDirect("\n[shso] 命令长时间无结束（>${TERMINAL_COMMAND_TIMEOUT_MS / 60_000}分钟），已强制终止\n")
            }
            // 仅当没有更新的命令/任务接管时清理：否则会把新任务的状态误清成「待命中」。
            if (terminalCommandGeneration.get() == generation) {
                isTaskRunning = false
                currentTaskName = null
                currentTaskPath = null
                taskStartTime = 0L
                lastExitCode = exitCode
                activeProcess = null
                processWriter = null
                processPid = 0
            }
        }
        return exitCode
    }

    /**
     * 以 Root 把字节写入目标文件（覆盖或追加）。
     * 统一替代各处自建 `ProcessBuilder("su","-c","cat > …")` 的旁路出口
     * （TextCompare / TextEditorDialog），使 su 出口收敛。
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

    /**
     * 执行脚本/二进制文件。
     *
     * 本函数由点击直接调用（`MainActivity` / `HomePage` / `FilePage`）。
     * 命中格式校验后直接进入启动流程。
     */
    fun executeFile(filePath: String, runAsRoot: Boolean? = null, riskApproved: Boolean = false) {
        val file = File(filePath)
        val fileName = file.name
        val isSh = fileName.endsWith(".sh", ignoreCase = true)
        val isSo = fileName.endsWith(".so", ignoreCase = true)

        if (!isSh && !isSo) {
            appendOutputDirect("\n[!] 错误: 不支持的文件格式，仅支持执行 .sh 和 .so 文件\n")
            return
        }

        // 未显式指定时以 root 执行。
        val useRoot = runAsRoot ?: true
        startExecution(filePath, fileName, isSh, isSo, useRoot)
    }

    /** [executeFile] 的启动段：置状态、起前台服务、派发执行协程。 */
    private fun startExecution(
        filePath: String,
        fileName: String,
        isSh: Boolean,
        isSo: Boolean,
        useRoot: Boolean
    ) {
        val parentDir = File(filePath).parent ?: "/data/adb/shso"

        if (isTaskRunning) {
            killCurrentProcess()
        }

        lastExecutedPath = filePath

        HyperCore.clearBatchQueue()
        val showHyperCoreBanner = appSettings?.showHyperCoreBanner ?: true
        val showShsoBanner = appSettings?.showShsoBanner ?: true

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

        if (showShsoBanner) {
            appendOutputDirect(HyperCore.generateTaskHeader(fileName, filePath, parentDir, showHyperCoreBanner))
        }
        appendOutputDirect("[shso Engine] 执行身份: ${if (useRoot) "Root" else "非 Root"}\n")

        val fileFlushLoop = HyperCore.startBatchFlushLoop(scope, { isTaskRunning }) { flushedText ->
            appendOutputDirect(flushedText)
        }
        activeFlushLoop = fileFlushLoop

        // 启动新任务即作废「终端一次性命令」的代际：否则那条命令稍后退出时，
        // 会按自己的代际判断把本任务的状态误清成「待命中」。
        terminalCommandGeneration.incrementAndGet()
        executionJob?.cancel()
        executionJob = scope.launch(Dispatchers.IO) {
            // 本次执行的身份：pgid 回填与状态清理都必须按它自检，否则会写到别人的槽位。
            val myJob = coroutineContext[Job]
            var process: Process? = null
            var writer: OutputStreamWriter? = null
            try {
                val escapedParent = escapeShellArg(parentDir)
                val escapedFile = escapeShellArg(filePath)
                // 开跑前清掉上一轮的记录并作废内存值，确保随后读到的 pgid 一定来自本次执行。
                runCatching { runPgidFile?.delete() }
                runPgid = 0
                // 把本次执行的「进程组组长 pid」落盘：su 会新建会话，`$$` 即组长 pid
                //（组长同时是进程组组长与会话组长）。仅 root 路径记录 —— 非 root 走 ProcessBuilder("sh")，
                // 子进程沿用应用自身进程组，记录后会诱导误杀本应用所在的组。
                val pgidRecorder = if (useRoot) {
                    runPgidFile?.let { "echo " + "\$\$" + " > " + escapeShellArg(it.absolutePath) + "; " } ?: ""
                } else ""
                val execCmd = if (useRoot) {
                    if (isSh) {
                        // .sh：一律经 sh 运行，不给用户文件加执行位
                        "${pgidRecorder}export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd $escapedParent && sh $escapedFile"
                    } else {
                        // .so：直接执行需要 +x，755 即可（不再 777）
                        "${pgidRecorder}export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd $escapedParent && chmod 755 $escapedFile && ( $escapedFile || sh $escapedFile )"
                    }
                } else {
                    // 非 Root：普通 sh 执行（无 su 包装），改不动系统分区
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

                // 直接子进程（su）pid，仅用于日志与兜底 destroy。
                // 中断与回收的正确性由下面的进程组 kill 保证，不依赖此 pid。
                val childPid = pidOfProcess(process)
                withContext(Dispatchers.Main) {
                    processPid = childPid
                }

                // 异步取回本次执行的进程组 id（不阻塞输出读取）。任务结束后刻意保留，
                // 供「结束进程」兜底回收被中断后逃逸到 init 下的子孙进程。
                //
                // 必须按本次 Job 自检：这个协程挂在页面级 scope 上，**不是** executionJob 的子任务，
                // 下一轮 `executionJob?.cancel()` 收不到它。轮询最长 1.5s，期间若新任务已起并
                // 写入了自己的 pgid，此处的迟到结果（尤其是超时归零）会把新任务的进程组冲掉，
                // 它的「中断/结束进程」退化成只杀 su，真正的 `sh -c …` 以 root 继续跑。
                scope.launch {
                    val pgid = awaitRunPgid()
                    if (executionJob === myJob) runPgid = pgid
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
                // 本次执行仍是当前任务（代际判断）才写「退出」文案；被新任务/重启取代后由对方写
                withContext(Dispatchers.Main) {
                    // 必须比 myJob（协程入口处捕获的引用），不能比 coroutineContext[Job]：
                    // 后者在 withContext 块内是 kotlinx 为该次上下文创建的 ScopeCoroutine，
                    // 与 launch 返回并赋给 executionJob 的那个 Job 并非同一对象，
                    // 恒不相等 —— 于是本块整体被跳过：退出码永不显示、发布循环不停止、
                    // isTaskRunning 与 lastExitCode 不复位（任务早已结束，UI 却仍显示「运行中」）。
                    if (executionJob === myJob) {
                        // 先停发布循环并等它把积压刷完，再写「退出」文案：
                        // 否则循环里最后 ≤250ms 的输出会落在文案之后（看起来像退出码打在输出前面）。
                        HyperCore.stopBatchFlushLoop(fileFlushLoop)
                        HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
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
                        if (executionJob === myJob) {
                            lastExitCode = -1
                            if (appSettings?.showShsoBanner != false) {
                                appendOutputDirect("\n[shso Engine] 异常终止: ${e.message}\n")
                            }
                        }
                    }
                }
            } finally {
                // 任何取消路径（kill/restart/覆盖启动）必然走到这里；
                // 但只有本次仍是当前执行协程（执行 Job 未被替换）时才清理 Compose 状态。
                // 关键：executionJob 在协程外已切换到新值（覆盖启动先 cancel 再赋新 job），
                // 因此比较「执行 Job 是否仍是本协程」可判定代际 —— 比的必须是入口捕获的
                // myJob：finally 里带 NonCancellable 的 withContext 会把 coroutineContext[Job]
                // 换成 NonCancellable，拿它比恒为 false，清理永不执行（状态卡在「运行中」）。
                val isCurrentJob = executionJob === myJob
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
     * 终端是否已被占用：已有命令/任务在跑，且不是可写入的交互进程（交互态下输入直写常驻 shell，
     * 不占用新槽位）。
     */
    private fun isTerminalBusy(): Boolean =
        // 槽位已被同步占用（命令在途、尚未置 isTaskRunning）同样算忙
        terminalSlotOwner.get() != 0L || (isTaskRunning && processWriter == null)

    /** 说明为何拒绝本次终端输入。 */
    private suspend fun reportTerminalBusy() {
        withContext(Dispatchers.Main) {
            appendOutputDirect("\n[shso] 上一条命令仍在运行，请先「中断」或「结束进程」\n")
        }
    }

    /**
     * 发送终端输入。
     *
     * 命令不再经策略判定，也不落审计，直接按当前终端状态投递。
     *
     * @return 本次输入是否被接受。`false` = 已有一条命令/任务在运行且不是可写入的交互进程，
     *   输入未被发送（调用方应保留输入框内容，便于中断后重发）。
     */
    fun sendInput(text: String): Boolean {
        // 与协程内的分支判断保持一致：终端只有一个「当前活动进程」槽位，不接受并发命令
        //（并发时中断 / 结束进程只能作用到最新一条，先启动的那条会变成无法回收的孤儿）。
        // 这里同步返回受理结果，调用方据此决定要不要清空输入框。
        if (text.isNotEmpty() && isTerminalBusy()) {
            scope.launch { reportTerminalBusy() }
            return false
        }
        // 一次性命令：在派发协程**之前**同步占用槽位，闭合「判定通过 → isTaskRunning 置位」之间的窗口。
        // 交互输入（text 为空）不占用槽位 —— 它写的是已有进程。
        var slotToken = 0L
        if (text.isNotEmpty()) {
            slotToken = tryClaimTerminalSlot()
            if (slotToken == 0L) {
                scope.launch { reportTerminalBusy() }
                return false
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                // 交互态的 writer **一次性取到局部变量**：
                // 两次独立读全局 processWriter 会在任务收尾（把它置 null）落在
                // write 与 flush 之间时丢掉 flush，数据留在 StreamEncoder 的 8KB 缓冲里
                // 随 close 丢弃 —— 而输入早已回显到终端，脚本与用户都以为发出去了。
                val w = processWriter
                if (isTaskRunning && w != null) {                    // 交互态：直接写入
                    withContext(Dispatchers.Main) {
                        appendOutputDirect(if (text.isEmpty()) "\n" else "$text\n")
                    }
                    // 交互写入也走互斥：StreamEncoder 非线程安全，连点两次「发送」会并发写同一流。
                    synchronized(interactiveWriteLock) {
                        w.write(text + "\n")
                        w.flush()
                    }
                } else if (text.isNotEmpty()) {
                    // 槽位已在派发前同步占用；若被别人抢走（不应发生，兜底）立即退出。
                    if (terminalSlotOwner.get() != slotToken) {
                        reportTerminalBusy()
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        appendOutputDirect("> $text\n")
                    }
                    // 走可中断 + 流式回吐的通道：长命令期间顶栏「运行中」、
                    // 「中断 / 结束进程」可用，输出边跑边显示（见 runTerminalCommand）。
                    val exitCode = runTerminalCommand(text, commandDisplayName(text), slotToken)
                    withContext(Dispatchers.Main) {
                        if (exitCode != 0) {
                            appendOutputDirect("[退出码: $exitCode]\n")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutputDirect("[发送失败: ${e.message}]\n")
                }
            } finally {
                // 任何提前退出（异常 / 取消 / 槽位被抢）都必须归还槽位，否则终端永久占死
                if (slotToken != 0L) releaseTerminalSlot(slotToken)
            }
        }
        return true
    }

    fun killCurrentProcess() {
        // 同步捕获当前任务实体（Job/pid/进程组/进程句柄/任务名/发布循环）：
        // 之后主线程若启动新任务（覆盖启动），这些仍是旧实体，kill 只作用于它们，绝不误杀新任务。
        val targetJob = executionJob
        val targetPid = processPid
        val targetPgid = runPgid
        val targetProcess = activeProcess
        val targetName = currentTaskName
        val targetPath = currentTaskPath
        val targetFlushLoop = activeFlushLoop
        val targetSlot = terminalSlotOwner.get()
        // 只要还留有执行句柄、进程组记录或任务名就允许回收：
        // 中断后 UI 已回到待命中、但子孙进程仍可能在跑。
        if (!isTaskRunning && targetProcess == null && targetPgid <= 1 && targetName == null) return

        scope.launch(Dispatchers.IO) {
            try {
                // 先杀**整个进程组**：`su -c` 把命令放进新会话且组长被重挂到 init，
                // 中断后逃逸的子孙进程（`sh -c …`、真正的脚本进程）只有这样才能回收。
                if (targetPgid > 1) {
                    killProcessGroup(9, targetPgid)
                } else {
                    // 兜底：进程组不可用时（pgid 未落盘；或非 Root 执行——子进程沿用本应用进程组，
                    // 整组回收会误伤自身）按**完整路径**匹配残留子孙。
                    // 不可退化为按文件名匹配：同一脚本「覆盖启动」时，新任务的命令行里含同样的文件名，
                    // `pkill -9 -f <文件名>` 会把刚启动的新任务一并杀死（表现为覆盖启动后脚本秒退）。
                    targetPath?.takeIf { it.isNotBlank() }?.let { path ->
                        // 必须转 ERE 元字符：pkill -f 把参数当扩展正则，
                        // 路径里的 `.` `(` `+` 等会被解释而误杀无关进程（且以 root 身份执行）。
                        runCommandSync("pkill -9 -f ${escapeShellArg(escapeEreLiteral(path))} 2>/dev/null")
                    }
                }
                if (targetPid > 0) {
                    runCommandSync("kill -9 $targetPid 2>/dev/null")
                }
                targetProcess?.destroyForcibly()
                forceCloseProcess(targetProcess)
                // 当前仍由本 kill 接管时才撤销全局句柄；
                // 若期间新任务已启动，句柄属于新任务，由新任务线条负责。
                if (stillOwnsExecution(targetJob)) {
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
                    // 同上：不能用裸 executionJob === targetJob，终端命令路径下它恒真，
                    // 于是「结束 A 失败」的提示会写进期间新启动的 B 的日志。
                    if (stillOwnsExecution(targetJob)) {
                        if (appSettings?.showShsoBanner != false) {
                            appendOutputDirect("\n[shso Engine] 结束进程失败: ${e.message}\n")
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    // 仅当本次仍是当前执行协程时才 flush/清理/写文案：
                    // 若期间新任务已启动（executionJob 已替换），旧任务的残留日志不应混入新任务输出，
                    // 交由 executeFile 的 clearBatchQueue 与新的 flush loop 自行处理。
                    //
                    // 必须走统一判据而不是 `executionJob === targetJob`：
                    // 终端一次性命令**从不写** executionJob，于是该判据对它是恒真的。
                    // 时序：脚本任务 A 在跑 → 点「结束进程」（cancel 是 body 第一句，A 的 finally
                    // 立刻清 isTaskRunning，终端即刻空闲）→ 300~600ms 内（两次 su 往返）
                    // 启动终端命令 B → A 的 kill 收尾落到这里：会把 **B** 的状态清掉、
                    // 停掉 B 的发布循环，`isTaskRunning=false` 让顶栏显示「待命中」、
                    // 「中断/结束进程」被禁用 —— B 还在跑，但用户失去了唯一的出口。
                    if (stillOwnsExecution(targetJob)) {
                        // 停发布循环并等积压刷完，再写「已结束」文案，保证日志顺序
                        HyperCore.stopBatchFlushLoop(targetFlushLoop)
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

    /** 读取本次执行记录的进程组 id（root 侧执行 `echo $$ > file` 写入）。 */
    private fun readRunPgidFile(): Int =
        runCatching { runPgidFile?.readText()?.trim()?.toIntOrNull() ?: 0 }.getOrDefault(0)

    /** 有界轮询等待本次 pgid 落盘（脚本刚启动时文件可能尚未写出）。 */
    private suspend fun awaitRunPgid(): Int {
        repeat(15) {
            val value = readRunPgidFile()
            if (value > 1) return value
            delay(100)
        }
        return 0
    }

    /**
     * 对进程组发信号（负 pid）。只杀 `su` 或直接子进程会留下 `sh -c …` 与脚本进程继续运行。
     *
     * 安全校验见文件末尾的顶层纯函数 [buildProcessGroupKillCommand]（返回 null 即放弃）。
     */
    private fun killProcessGroup(signal: Int, pgid: Int) {
        val cmd = buildProcessGroupKillCommand(signal, pgid, android.os.Process.myPid()) ?: return
        runCommandSync(cmd)
    }

    fun sendInterrupt() {
        // 同步捕获当前任务实体（Job/进程 writer/pid）：
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
                    // ProcessBuilder 起的子进程没有 TTY，经 stdin 写入 ETX(0x03) 只是普通字符，
                    // 不会触发 SIGINT；真正能中断的是下方的 `kill`，故此处只写换行、不写 ETX。
                    targetWriter?.write("\n")
                    targetWriter?.flush()

                    // 对进程组发 SIGINT：只杀直接子进程会留下 `sh -c …` 与脚本进程继续运行。
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
                        // 必须用 stillOwnsExecution，不能用裸 `executionJob === targetJob`：
                        // 终端一次性命令这条路径上 targetJob 恒为 null、executionJob 也恒为 null，
                        // 判据恒真。于是 SIGINT 生效、进程 1 秒内退出、用户紧接着启动命令 B，
                        // t+3s 时 isTaskRunning 为 true（B 在跑）且 null === null 成立 →
                        // 把「进程未响应 SIGINT」写进 **B 的日志**，用户会据此去点
                        // 「结束进程」，把 B 杀掉。这正是 stillOwnsExecution 要消除的归属漏洞。
                        if (isTaskRunning && stillOwnsExecution(targetJob)) {
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
        val targetPgid = runPgid
        val targetProcess = activeProcess

        // 无任何活动实体时**只复位横幅，不发信号**。
        // `runPgid` 在任务收尾时被刻意保留（供「中断后子孙进程仍在」的场景回收），
        // 因此跑完任意命令后它仍非零；而那个 pid 早已被内核回收给别的进程，
        // `buildProcessGroupKillCommand` 只校验「是组长、且不等于本应用 pgrp」，
        // pid 复用后照样通过 → 以 root 整组 SIGKILL 一个完全无关的进程组。
        val hasLiveEntity = isTaskRunning || targetProcess != null || targetPid > 0
        if (!hasLiveEntity) {
            runPgid = 0
            scope.launch(Dispatchers.Main) {
                isTaskRunning = false
                currentTaskName = null
                currentTaskPath = null
                taskStartTime = 0L
                lastExitCode = null
                processPid = 0
                refreshPristineBanner("工作中")
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                targetJob?.cancel()
                // 与「结束进程」同源：必须先整组回收。只杀 su 本身的话，`sh -c …` 与脚本进程
                // 会变成 init 名下的孤儿继续跑（重启后横幅已复位、UI 显示待命中，用户以为停了）。
                if (targetPgid > 1) {
                    killProcessGroup(9, targetPgid)
                }
                if (targetPid > 0) {
                    runCommandSync("kill -9 $targetPid 2>/dev/null")
                }
                targetProcess?.destroyForcibly()
                forceCloseProcess(targetProcess)
                // 旧任务 finally 已通过代际判断清理状态；若期间新任务启动，
                // 不能再动全局句柄（属于新任务）
                if (stillOwnsExecution(targetJob)) {
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
                    // 仅当本次仍是当前执行协程时才清理状态并恢复横幅。
                    // 走 stillOwnsExecution 而非 `executionJob === targetJob`：后者对终端命令恒真，
                    // 会在「重启一条命令」时把期间新启动的命令状态误清成「待命中」并覆盖其横幅。
                    if (stillOwnsExecution(targetJob)) {
                        isTaskRunning = false
                        currentTaskName = null
                        currentTaskPath = null
                        taskStartTime = 0L
                        lastExitCode = null
                        processPid = 0
                        runPgid = 0
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
