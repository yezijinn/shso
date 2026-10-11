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
import kotlinx.coroutines.isActive
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

    /** root 侧读脚本头部的等待上界：超出即认为 su 卡住，放弃本次判定。 */
    private const val ROOT_HEAD_TIMEOUT_MS = 5_000L

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

    /**
     * 以 root 读取文件头部字节（最多 [limit] 字节），供归一化判定使用。
     *
     * 用途单一：app 域被 SELinux 拦、`java.io.File` 读不到脚本时（典型 `/data/adb/shso`），
     * 归一化决策必须看到**真实字节**才能判断是否含内嵌载荷；否则只能保守地不改写，
     * CRLF/BOM 脚本会因此执行失败（A75 的原始缺陷）。
     *
     * 读回长度由调用方与 `stat` 结果比对（多读 1 字节以识别追加）：一致即整份文件到手，
     * 不一致说明两次 su 之间文件被改动，调用方保守判 DIRECT。
     * 读取失败（含超时）返回 null，由调用方沿用 app 侧结论。
     */
    private fun readFileHeadAsRoot(path: String, limit: Int): ByteArray? {
        val process = try {
            ProcessBuilder("su", "-c", "head -c $limit ${escapeShellArg(path)} 2>/dev/null")
                .redirectErrorStream(false)
                .start()
        } catch (_: Exception) {
            return null
        }
        // 本函数只读输出、从不喂输入；不关 stdin 时 head 不会阻塞，但统一保持与 runCommandSync 一致。
        runCatching { process.outputStream.close() }
        // 读取放到独立线程并设上界：su 若卡在授权弹窗（管理器无响应）会既不出数据也不关管道，
        // 直接 readBytes() 将永久阻塞，把 executeFile 的协程连同「运行中」状态一起挂死。
        // 超时后强制销毁进程 → 管道断开 → 读线程退出；本次判定以失败收场，
        // 由调用方沿用 app 侧结论（保守 DIRECT，脚本照旧按原字节执行）。
        var head: ByteArray? = null
        val reader = Thread {
            head = runCatching { process.inputStream.use { it.readBytes() } }.getOrNull()
        }
        reader.isDaemon = true
        reader.start()
        return try {
            reader.join(ROOT_HEAD_TIMEOUT_MS)
            if (reader.isAlive) {
                process.destroyForcibly()
                reader.join(1_000)
                null
            } else {
                process.waitFor(1_000, TimeUnit.MILLISECONDS)
                head
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * app 域读不到脚本（典型 `/data/adb/shso`，父目录 0700 且被 SELinux 拦）时的 root 侧判定。
     *
     * 先用 `stat -L -c %s` 取大小：超过完整扫描上限直接判 DIRECT，**不读文件**。
     * 否则按实际大小读取（而不是无条件读 16MB）——真机样本 clear.sh 有 21MB，
     * 每次执行都搬 16MB 进内存只是白花开销。取不到大小时退回读扫描上限字节。
     *
     * 读取上界刻意比 stat 结果多 1 字节，并要求读回长度与 stat 结果**严格相等**：
     * 两次 su 之间文件被追加时，尾部新增的内嵌载荷会落在上界之外，据此判 DIRECT 而非
     * 拿截断的字节去做归一化判定 —— 后者会把自解压脚本的载荷改坏，与 A75 同级。
     */
    private fun decideAsRoot(path: String, fallback: ShNormalization.Decision): ShNormalization.Decision {
        val (code, out) = runCommandSync("stat -L -c %s ${escapeShellArg(path)} 2>/dev/null")
        val size = if (code == 0) out.trim().toLongOrNull() else null
        if (size != null && size > ShNormalization.MAX_SCAN_BYTES) {
            return ShNormalization.Decision(ShNormalization.Plan.DIRECT, "超过完整扫描上限，保留原字节")
        }
        val limit = ((size ?: ShNormalization.MAX_SCAN_BYTES) + 1)
            .coerceAtMost(ShNormalization.MAX_SCAN_BYTES + 1).toInt()
        val head = readFileHeadAsRoot(path, limit) ?: return fallback
        // 判定必须建立在「整份文件都读到了」之上：
        //   head.size > MAX_SCAN_BYTES → 文件比扫描上限还大（或 stat 不可用而读满上界），
        //   size != null 且 head.size != size → stat 与 head 两次看到的不是同一份内容。
        // 两种情况都可能把尾部的内嵌载荷读漏，据此判 NORMALIZE 会把载荷改坏。
        if (head.size > ShNormalization.MAX_SCAN_BYTES || (size != null && head.size.toLong() != size)) {
            return ShNormalization.Decision(ShNormalization.Plan.DIRECT, "文件超出扫描上限或读取期间发生变化，保留原字节")
        }
        return ShNormalization.decideBytes(head)
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

    var outputLog by mutableStateOf("")
        private set

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
    private fun stillOwnsExecution(targetJob: Job?, targetSlot: Long = 0L): Boolean =
        (targetJob != null && executionJob === targetJob) ||
            // 终端命令：必须比对**精确令牌**，不能用「非 0 即归属」。
            // 「非 0」只说明存在某条终端命令，不说明是本次要收尾的那条：
            // 期间新命令接管槽位后判据仍为真，收尾就会把新命令的状态与发布循环清掉
            // （进程还在跑、UI 却显示待命中、「结束进程」被禁用 → 用户失去唯一出口）。
            (targetSlot != 0L && terminalSlotOwner.get() == targetSlot)

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
    }

    /**
     * 上报最新 ROOT 探测结果（MainActivity 每次前台 ON_RESUME 探测后调用）。
     *
     * 横幅已移除，这里只更新权限状态本身。
     */
    fun reportRootState(granted: Boolean) {
        isRootGranted = granted
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
     * - 主线程 waitFor(timeoutMs) 做超时控制，超时**整组回收**并返回退出码 -1；
     * - stdout/stderr 合并（redirectErrorStream），保证错误信息可见。
     *
     * 超时必须回收**进程组**，不能只杀 `su`：
     * `su -c` 会把命令放进新会话，`su` 只是其直接子进程。`destroyForcibly()` 只对
     * `su` 的 pid 发信号，被重挂到 init 的 `sh -c …`、`pm`、`cp` 会**继续以 root 运行**，
     * 在系统里留下无人回收的孤儿（本函数在全项目有 60+ 调用点，含安装、拷贝、
     * 解压、列目录）。上层拿到 -1 后立即把 `finally` 清理跑起来（如 `installApk`
     * 删掉临时 APK），而那个 root 进程还在读它 → 半安装且无法归因。
     *
     * 输出累积必须线程安全：读线程在 `append`，主线程在超时或结束后 `toString`。
     * 原实现用非线程安全的 `StringBuilder`，一旦超时路径下读线程尚未退出，
     * 两者并发即产生撕裂字符串或越界异常，而该异常被外层 `catch (e: Exception)`
     * 吞掉并替换成 `Pair(-1, e.message)` —— **已收集的输出全部丢失**。
     *
     * @param timeoutMs 超时毫秒（默认 120s）；耗时任务（安装大包等）可自行放宽。
     */
    fun runCommandSync(cmd: String, timeoutMs: Long = 120_000L): Pair<Int, String> {
        return try {
            // 先记下 su 的 pid：超时时用它反查进程组并整组回收。
            val process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val suPid = pidOfProcess(process)
            // 立即关闭子进程 stdin：本函数只读输出、从不喂输入。若不关闭，任何会读 stdin 的命令
            // （cat / read / 等 EOF 的交互式命令）都会一直阻塞到 timeoutMs（默认 120s）才返回。
            runCatching { process.outputStream.close() }
            val collector = CommandOutputCollector()
            val readerThread = Thread {
                try {
                    process.inputStream.use { stream ->
                        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(1024)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count == -1) break
                                collector.append(buffer, count)
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
                // 先整组回收，再 destroy su 本身：顺序反了的话组组长已消失，
                // 后续按 pgid 校验会 fail-closed 而漏掉子孙进程。
                forceKillProcessTree(process, suPid)
                readerThread.join(3000)
                return Pair(-1, collector.snapshot() + "\n[shso] 命令执行超时（>${timeoutMs}ms），已强制终止")
            }
            // 正常结束：管道写端全部关闭，读线程很快就会拿到 EOF。
            // join 必须留足时间，否则尾部输出会随线程一起被丢弃（静默截断）。
            readerThread.join(5000)
            if (readerThread.isAlive) {
                // join 超时：读线程仍卡在 read()（说明有后代进程还持着管道写端）。
                // 不再无限等待，但要让调用方知道输出可能不完整。
                return Pair(process.exitValue(), collector.snapshot() + "\n[shso] 输出读取未完全结束，结果可能不完整")
            }
            Pair(process.exitValue(), collector.snapshot())
        } catch (e: Exception) {
            Pair(-1, e.message ?: "执行异常")
        }
    }

    /**
     * 强制回收一条命令的**全部**进程：先按进程组整组 kill，再杀直接子进程。
     *
     * pgid 由 `/proc/<pid>/stat` 的第 5 字段（pgrp）取得，并交给
     * [buildProcessGroupKillCommand] 做三重校验（组长确认、不误杀本应用所在进程组）。
     * 校验不通过时只 kill `su` 本身 —— 此时子孙已无法可靠归属，
     * 宁可不杀也不能误杀无关进程组。
     */
    private fun forceKillProcessTree(process: Process, suPid: Int) {
        if (suPid > 1) {
            val pgrp = runCatching {
                val stat = File("/proc/$suPid/stat").readText()
                // 格式：pid (comm) state ppid pgrp …；comm 可能含空格与右括号，
                // 故从**最后一个** ')' 之后开始切分。
                stat.substringAfterLast(')').trim().split(' ').getOrNull(2)?.toIntOrNull()
            }.getOrNull() ?: 0
            if (pgrp > 1) {
                buildProcessGroupKillCommand(9, pgrp, android.os.Process.myPid())?.let {
                    runCommandSync(it, timeoutMs = 5_000L)
                }
            }
        }
        runCatching { process.destroyForcibly() }
        // 再补一次直接子进程：某些 su 实现不新建会话，此时组杀会被自身进程组校验挡下。
        if (suPid > 1) RootService.runCommandSync("kill -9 $suPid 2>/dev/null", timeoutMs = 5_000L)
    }

    /**
     * 命令输出的线程安全累积器，带总量上限。
     *
     * 为什么不用 `StringBuilder`：读线程 append 与主线程 `toString` 并发时会撕裂
     * （`AbstractStringBuilder` 非线程安全），异常还会被外层 catch 吞掉并把
     * 已收集输出替换成异常消息。
     *
     * 上限用于兜住「一条命令吐几百 MB」（`find /`、`logcat -d` 全文）：
     * 无上限时 StringBuilder 扩容峰值可达 2×，加上 50 万个条目对象直接 OOM。
     * 超限后停止累积但**继续 drain**，否则写端填满管道会让子进程永久阻塞。
     */
    private class CommandOutputCollector(private val limit: Int = MAX_SYNC_OUTPUT_CHARS) {
        private val buffer = StringBuilder()
        private var truncated = false

        @Synchronized
        fun append(chunk: CharArray, count: Int) {
            if (truncated) return
            if (buffer.length + count > limit) {
                buffer.append(chunk, 0, maxOf(0, limit - buffer.length))
                truncated = true
                return
            }
            buffer.append(chunk, 0, count)
        }

        @Synchronized
        fun snapshot(): String {
            val base = buffer.toString()
            return if (truncated) "$base\n[shso] 输出超过 $limit 字符上限，已截断" else base
        }
    }

    /** 单次同步命令的输出上限（约 4M 字符）。足以覆盖正常用法，又能兜住 OOM。 */
    private const val MAX_SYNC_OUTPUT_CHARS = 4 * 1024 * 1024

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
        val showShsoBanner = appSettings?.showShsoBanner ?: true

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

        appendOutputDirect("[shso] 执行身份: ${if (useRoot) "Root" else "非 Root"}\n")

        val fileFlushLoop = HyperCore.startBatchFlushLoop(scope, { isTaskRunning }) { flushedText ->
            appendOutputDirect(flushedText)
        }
        activeFlushLoop = fileFlushLoop

        // 启动新任务即作废「终端一次性命令」的代际：否则那条命令稍后退出时，
        // 会按自己的代际判断把本任务的状态误清成「待命中」。
        terminalCommandGeneration.incrementAndGet()
        // **必须清空槽位令牌**。上一条注释承诺了这一点，代码却只递增了 generation：
        // 于是终端命令的槽位令牌一直留在 terminalSlotOwner 里，直到那条命令自己结束。
        // 期间若 startExecution 因 isTaskRunning 先调 killCurrentProcess，
        // 它的收尾在两轮 su 往返后才判归属，此时令牌仍非 0 →
        // stillOwnsExecution 判真 → 把**本任务**的 isTaskRunning/currentTaskName/
        // currentTaskPath 一并清掉，还 stop 掉本任务的批量发布循环。
        // 结果：脚本真在跑，UI 显示「待命中」，「结束进程」被禁用，
        // currentTaskPath 与 pgid 也被清空 → 用户失去唯一的终止出口，进程成 root 孤儿。
        terminalSlotOwner.set(0L)
        executionJob?.cancel()

        // 心跳：脚本长时间无输出时，输出区会整段静止，用户无法区分「正在跑」与「已卡死」。
        // 每 10s 追加一行计时；脚本一旦产出内容即停止打扰（满屏心跳会淹没真实日志）。
        // 标志跨线程读写，用 AtomicBoolean 而非局部 @Volatile（后者不能修饰局部变量）。
        val producedAnyOutput = java.util.concurrent.atomic.AtomicBoolean(false)
        val heartbeatJob = scope.launch(Dispatchers.Main) {
            var elapsed = 0L
            while (isActive && isTaskRunning) {
                delay(1000)
                elapsed += 1000
                if (producedAnyOutput.get()) return@launch
                if (elapsed % 10_000L == 0L) {
                    appendOutputDirect("[shso] 仍在执行，已运行 ${formatElapsed(elapsed)}…\n")
                }
            }
        }

        executionJob = scope.launch(Dispatchers.IO) {
            // 本次执行的身份：pgid 回填与状态清理都必须按它自检，否则会写到别人的槽位。
            val myJob = coroutineContext[Job]
            var process: Process? = null
            var writer: OutputStreamWriter? = null
            // 记录执行前的权限，供 finally 还原（.so 直接执行需临时 +x，
            // 收尾必须还原，否则用户的文件会永久停留在放宽后的权限上）。
            val execAttrFile: File? = runCatching {
                File(com.mixradio.droid.ShsoApplication.appContext.filesDir, ".exec_attr_${System.nanoTime()}")
            }.getOrNull()
            // .sh 归一化用的 app 私有临时文件（执行后必删）。
            val normalizedShFile: File? = if (isSh) runCatching {
                File(com.mixradio.droid.ShsoApplication.appContext.filesDir, ".sh_norm_${System.nanoTime()}")
            }.getOrNull() else null
            val escapedParent = escapeShellArg(parentDir)
            val escapedFile = escapeShellArg(filePath)
            // 归一化：去 BOM（仅首 3 字节）+ 去全部 CR。临时文件建不出来时退化为直读原文件。
            //
            // 但不是每次都该归一化 —— 见 [ShNormalization]：无 CR 无 BOM 时重写没有收益，
            // 而脚本若按字节偏移寻址内嵌载荷，重写会整体移位载荷、必然打乱压缩流
            // （真机实测 `gzip: gzread: invalid distance too far back`，退出码 127）。
            val normalizationPlan = if (isSh) {
                val appSide = runCatching { ShNormalization.decide(File(filePath)) }
                    .getOrElse { ShNormalization.Decision(ShNormalization.Plan.DIRECT, "判定失败，按原样执行") }
                // app 域读不到时（/data/adb/shso 等被 SELinux 拦，File.isFile 恒 false），
                // 用 root 读同一份字节再判定 —— 否则归一化被静默跳过，CRLF/BOM 脚本退回 A75 缺陷。
                if (!appSide.appReadable && useRoot) {
                    decideAsRoot(filePath, appSide)
                } else {
                    appSide
                }
            } else {
                ShNormalization.Decision(ShNormalization.Plan.DIRECT, "非 .sh")
            }
            val needsNormalize = normalizationPlan.plan == ShNormalization.Plan.NORMALIZE
            val runShCmd: String = if (needsNormalize && normalizedShFile != null) {
                val tmp = normalizedShFile
                val t = escapeShellArg(tmp.absolutePath)
                val probe = escapeShellArg(tmp.absolutePath + ".bom")
                val prepare = "( head -c 3 $escapedFile 2>/dev/null | od -An -tx1 | tr -d ' \n' > $probe 2>/dev/null; " +
                    "if [ \"\$(cat $probe 2>/dev/null)\" = efbbbf ]; then " +
                    "tail -c +4 $escapedFile 2>/dev/null | tr -d '\r' > $t 2>/dev/null; " +
                    "else tr -d '\r' < $escapedFile > $t 2>/dev/null; fi; " +
                    "rm -f $probe ) ; "
                // 归一化产出为空但原文件非空 → 读取本身失败，此时必须回落原路径让真实报错浮现，
                // 不能拿空脚本假装执行成功。原文件本就为空则保留「空脚本 = 空操作」的旧语义。
                "$prepare" +
                    "if [ -s $t ] || [ ! -s $escapedFile ]; then sh $t; else sh $escapedFile; fi"
            } else "sh $escapedFile"
            try {
                // 开跑前清掉上一轮的记录并作废内存值，确保随后读到的 pgid 一定来自本次执行。
                runCatching { runPgidFile?.delete() }
                runPgid = 0
                // 把本次执行的「进程组组长 pid」落盘：su 会新建会话，`$$` 即组长 pid
                //（组长同时是进程组组长与会话组长）。仅 root 路径记录 —— 非 root 走 ProcessBuilder("sh")，
                // 子进程沿用应用自身进程组，记录后会诱导误杀本应用所在的组。
                val pgidRecorder = if (useRoot) {
                    runPgidFile?.let { "echo " + "\$\$" + " > " + escapeShellArg(it.absolutePath) + "; " } ?: ""
                } else ""
                // 权限还原：直接执行 .so 需要 +x，而 chmod 会**永久**改写用户文件的权限
                // （旧实现 chmod 755 且执行完不还原，等于擅自把 600 放宽成 world-readable）。
                // 这里先把原权限落到 app 私有目录，执行完再按它还原。
                val saveAttrCmd = execAttrFile?.let {
                    "stat -L -c %a $escapedFile > ${escapeShellArg(it.absolutePath)} 2>/dev/null; "
                } ?: ""
                val restoreAttrCmd = execAttrFile?.let {
                    // 只在确实改过 +x 时还原；chmod 失败也不能短路掉执行分支
                    "( [ -s ${escapeShellArg(it.absolutePath)} ] && chmod \$(cat ${escapeShellArg(it.absolutePath)}) $escapedFile 2>/dev/null ); "
                } ?: ""

                val execCmd = if (useRoot) {
                    if (isSh) {
                        // .sh：一律经 sh 运行，不给用户文件加执行位。
                        //
                        // 执行前归一化 CRLF 与 UTF-8 BOM，两条均为真机实证：
                        //  1. CRLF 的 shebang 直接执行必失败 —— 内核把 `#!/system/bin/sh\r`
                        //     当成解释器路径，实测报 `No such file or directory`；
                        //  2. CRLF 会把变量值尾部带上 \r —— 实测 `export V=abc\r` 之后
                        //     `${#V}` 为 4（应为 3），后续比较、路径拼接、字符串匹配全错。
                        // 本应用编辑器**本身就能写出 CRLF**（行尾风格可选）并可勾选写入 BOM，
                        // 即：能生成自己执行不了的脚本，且全程无任何提示。
                        //
                        // 归一化结果写 app 私有临时文件，而**不是管道喂 sh**：
                        // 实测 `cat x.sh | sh` 会让脚本里的 `read` 吞掉后续脚本文本
                        // （整个脚本无输出），`sh tmp.sh` 才能正常继承 stdin。
                        //
                        // BOM 判定用 od 比对首 3 字节，而非 `sed 1s|^\xEF\xBB\xBF||`：
                        // Android 自带 toybox sed **不支持 \xNN 转义**，实测原样输出不生效。
                        //
                        // 原文件保持不动：执行一次就把用户磁盘上的脚本改掉不可接受。
                        "${pgidRecorder}export TERM=xterm-256color && export LANG=en_US.UTF-8 && " +
                            "cd $escapedParent && $runShCmd"
                    } else {
                        // .so / ELF：直接执行需要 +x。
                        //
                        // 两处必须改：
                        // 1) `chmod 755` 把用户文件从 600/640 改成 world-readable+executable
                        //    且**执行完不还原**。改为先记录原权限、只补 owner 执行位，收尾还原。
                        // 2) `( $f || sh $f )` 的 `||` 语义错误：ELF 正常返回非 0 退出码时
                        //    会**回落执行 `sh <二进制>`**，把 ELF 字节当 shell 脚本喂进终端，
                        //    产出满屏 U+FFFD 与控制字符，真实退出码也被 sh 的失败码覆盖。
                        //    改为如实保留退出码，仅在 126/127（无法执行）时给出提示。
                        // 权限还原必须排在**执行之后**：放在执行前是 600→600 的空操作，
                        // 而真正把文件放宽的是紧随其后的 `chmod a+x`，之后若无还原，
                        // 文件就永久停在 711。
                        //
                        // 放在 shell 内还原（而不是只靠 Kotlin 的 finally）才能覆盖
                        // 「进程被强杀/OOM/用户划掉」这类 finally 根本不执行的路径：
                        // 实测 finally 未参与时，600 的 .so 执行后停在 711。
                        //
                        // saveAttrCmd 则必须排在 chmod 之前 —— 它才是「记录原权限」的那一步。
                        // 此前只拼了 restoreAttrCmd（还原），从未执行保存：属性文件恒为空，
                        // finally 读到的 mode 为 null，**还原被静默跳过**，
                        // 用户的 .so 就永久停留在 a+x 放宽后的权限上（实测 600 → 711 不再回退）。
                        "$pgidRecorder" + saveAttrCmd +
                            "export TERM=xterm-256color && export LANG=en_US.UTF-8 && " +
                            "cd $escapedParent && chmod a+x $escapedFile && $escapedFile; C=\$?; " +
                            restoreAttrCmd +
                            "if [ \$C -eq 126 ] || [ \$C -eq 127 ]; then " +
                            "echo \"[shso] \u65e0\u6cd5\u76f4\u63a5\u6267\u884c\uff08\u975e\u53ef\u6267\u884c ELF\uff09\"; fi; " +
                            "exit \$C"
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

                // 脚本是否产出过内容：静默脚本（只做 rm/touch、无任何 echo）在终端里
                // 除横幅与退出码外一片空白，用户会以为没执行。收尾时据此补一句说明。
                process.inputStream.use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(2048)
                        var count: Int
                        while (reader.read(buffer).also { count = it } != -1) {
                            if (ExecutionFeedback.hasVisibleOutput(String(buffer, 0, count))) {
                                producedAnyOutput.set(true)
                            }
                            val chunk = String(buffer, 0, count)
                            HyperCore.queueLogChunk(chunk)
                        }
                    }
                }

                val exitCode = process.waitFor()
                val elapsedMs = System.currentTimeMillis() - taskStartTime
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
                        heartbeatJob.cancel()
                        if (appSettings?.showShsoBanner != false) {
                            if (!producedAnyOutput.get()) {
                                appendOutputDirect("\n[shso] 脚本执行完成，未产生任何输出\n")
                            }
                            appendOutputDirect(
                                "[shso] 任务已退出，退出码: $exitCode，用时 ${formatElapsed(elapsedMs)}\n"
                            )
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
                                appendOutputDirect("\n[shso] 异常终止: ${e.message}\n")
                            }
                        }
                    }
                }
            } finally {
                // 心跳挂在 scope 上而非 executionJob 下，取消执行协程停不掉它；
                // 不在这里收掉就会在任务结束后继续往输出区插计时行。
                heartbeatJob.cancel()
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
                    // 还原 .so 执行前的权限并清理临时记录文件。
                    // 必须放在 NonCancellable 块内：协程被取消时 finally 仍会执行，
                    // 若权限未还原，用户的 .so 会永久停留在被放宽后的权限上。
                    if (execAttrFile != null) {
                        if (isSo) {
                            val mode = runCatching { execAttrFile.readText().trim() }.getOrNull()
                            runCatching {
                                if (!mode.isNullOrEmpty()) {
                                    RootService.runCommandSync(
                                        "chmod $mode ${escapeShellArg(filePath)}",
                                        timeoutMs = 10_000L
                                    )
                                }
                            }
                        }
                        runCatching { execAttrFile.delete() }
                    }
                    // 归一化脚本临时文件同样必须清理，否则每次执行 .sh 都在 filesDir 留一份。
                    runCatching { normalizedShFile?.delete() }
                    runCatching { normalizedShFile?.let { File(it.absolutePath + ".bom").delete() } }
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
                if (stillOwnsExecution(targetJob, targetSlot)) {
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
                    if (stillOwnsExecution(targetJob, targetSlot)) {
                        if (appSettings?.showShsoBanner != false) {
                            appendOutputDirect("\n[shso] 结束进程失败: ${e.message}\n")
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
                    if (stillOwnsExecution(targetJob, targetSlot)) {
                        // 停发布循环并等积压刷完，再写「已结束」文案，保证日志顺序
                        HyperCore.stopBatchFlushLoop(targetFlushLoop)
                        HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
                        isTaskRunning = false
                        currentTaskName = null
                        currentTaskPath = null
                        lastExitCode = 137
                        processPid = 0
                        if (appSettings?.showShsoBanner != false) {
                            appendOutputDirect("\n[shso] 用户已手动结束进程\n")
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
        // 与 killCurrentProcess 同理：捕获**本次**的槽位令牌用于归属判定。
        val targetSlot = terminalSlotOwner.get()
        scope.launch(Dispatchers.IO) {
            try {
                if (isTaskRunning) {
                    withContext(Dispatchers.Main) {
                        appendOutputDirect("^C\n")
                    }
                    // ProcessBuilder 起的子进程没有 TTY，经 stdin 写入 ETX(0x03) 只是普通字符，
                    // 不会触发 SIGINT；真正能中断的是下方的 `kill`，故此处只写换行、不写 ETX。
                    //
                    // 必须与 sendInput 共用同一把锁：两者都写 targetWriter，
                    // 而 OutputStreamWriter 内部的 StreamEncoder 非线程安全
                    // （byteBuffer/charBuffer 与 leftover 状态），并发写互相覆盖残留字节，
                    // 表现为写入脚本的 stdin 内容乱码/丢字符。
                    synchronized(interactiveWriteLock) {
                        targetWriter?.write("\n")
                        targetWriter?.flush()
                    }

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
                        if (isTaskRunning && stillOwnsExecution(targetJob, targetSlot)) {
                            appendOutputDirect("\n[shso] 进程未响应 SIGINT，可点击「结束进程」强制终止\n")
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
        // 同步捕获当前任务实体，只作用于这些旧实体，绝不误杀新任务。
        val targetJob = executionJob
        val targetPid = processPid
        val targetPgid = runPgid
        val targetProcess = activeProcess
        // 与 killCurrentProcess 同理：必须捕获**本次**的槽位令牌。
        // 否则收尾判据退化为「存在任意终端命令」，会误清新命令的状态与发布循环。
        val targetSlot = terminalSlotOwner.get()

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
                // 空闲态点「重启终端」必须有回应：静默返回会让用户以为按钮坏了。
                // 横幅已移除，这里给一句明确反馈。
                appendOutputDirect("[shso] 当前没有运行中的进程，终端已是最新状态\n")
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
                if (stillOwnsExecution(targetJob, targetSlot)) {
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
                    if (stillOwnsExecution(targetJob, targetSlot)) {
                        isTaskRunning = false
                        currentTaskName = null
                        currentTaskPath = null
                        taskStartTime = 0L
                        lastExitCode = null
                        processPid = 0
                        runPgid = 0
                    }
                }
            }
        }
    }

    fun clearOutput() {
        HyperCore.clearBatchQueue()
        outputLog = ""
    }

    private fun appendOutputDirect(text: String) {
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

/**
 * 执行反馈的纯判定逻辑（便于 JVM 单测；`RootService` 是 object，单测无法加载）。
 */
internal object ExecutionFeedback {

    /**
     * 一段输出是否算「有内容」。
     *
     * 纯空白（空行、缩进、裸换行）不携带任何信息，不能据此认为脚本真的干了活 ——
     * 否则静默脚本会被误判成有输出、收尾时又不给提示，用户依旧一头雾水。
     */
    fun hasVisibleOutput(text: String): Boolean = text.any { !it.isWhitespace() }

    /** 分块读取时任一块有内容即整体算有输出（读取循环按块累积，不能只看最后一块）。 */
    fun anyChunkHasContent(chunks: List<String>): Boolean = chunks.any { hasVisibleOutput(it) }
}

/**
 * 把耗时格式化为人类可读文本（**顶层纯函数**，便于 JVM 单测）。
 *
 * 结束行带耗时是「这脚本到底跑了没有」最直接的证据：静默脚本的输出区本来一片空白，
 * 有了耗时与退出码，至少能确认它真的执行过且执行了多久。
 */
internal fun formatElapsed(ms: Long): String = when {
    ms < 0 -> "0秒"
    ms < 1000 -> "${ms}毫秒"
    ms < 60_000 -> String.format(java.util.Locale.ROOT, "%.1f秒", ms / 1000.0)
    else -> {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val hour = min / 60
        if (hour > 0) "${hour}小时${min % 60}分${sec}秒" else "${min}分${sec}秒"
    }
}
