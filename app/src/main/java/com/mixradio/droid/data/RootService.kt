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
import java.util.concurrent.atomic.AtomicBoolean
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

    /** 任务输出为空时的轮询间隔。用于把阻塞读换成可被协程取消打断的挂起等待。 */
    private const val OUTPUT_POLL_MS = 50L

    /** 直接子进程退出后，继续等待后代补输出的宽限轮数（约 300ms）。 */
    private const val OUTPUT_IDLE_POLLS_AFTER_EXIT = 6

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
     * 任务结束后不清零，但**只在仍有存活实体时**可被消费：实体消失后该 pgid 可能已被内核
     * 复用给无关进程组，此时一律弃用（见 [killCurrentProcess] / [restartTerminal] 的守卫）。
     * 下一轮执行开始时覆盖。
     */
    @Volatile
    private var runPgid: Int = 0

    /**
     * 当前任务的执行身份：true = 经 `su` 以 root 运行，false = 以应用自身 uid 运行。
     *
     * 收尾与回收通道依赖它选择 kill 通道 —— 非 Root 任务的 kill 若仍经 `su`，会平白弹出
     * 授权框，且对同 uid 进程而言 `su` 毫无必要（应用本来就有权杀自己的子进程）。
     */
    @Volatile
    private var execUsesRoot: Boolean = true

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
     * root 执行环境的临时目录。
     *
     * Android 应用进程不提供 `TMPDIR`，`su -c` 也不补。Termux 打包的自解压脚本
     * （makeself 风格：头部 + gzip 载荷）在 `TMPDIR` 为空时会回退到硬编码的
     * `/data/data/com.termux/files/usr/tmp`；设备未装 Termux 时该目录不存在，
     * `mktemp -d` 失败，脚本按 127 退出，且全程只报一行 mktemp 错误，难以归因。
     * `/data/local/tmp` 各机型恒存在且 root 可写。
     */
    private const val ROOT_TMP_DIR = "/data/local/tmp"

    /**
     * 追加到执行环境 `PATH` 末尾的候选工具目录。
     *
     * Android 原生不带 `curl` / `wget` / `python` / `node` 等，而联网型与脚本型
     * （加密、对暗号、自解压）脚本大量依赖它们；缺一个工具脚本就按 127 退出，
     * 日志里只剩一行 `curl: not found`，用户无从判断是脚本坏了还是环境缺件。
     *
     * 追加而非替换：系统目录仍优先，避免用第三方实现顶掉 toybox 的同名命令。
     * 只追加**确实存在**的目录（由 shell 侧 `[ -d ]` 逐个判定），
     * 不存在的不进 PATH，免得每条命令都多走几次失败的 stat。
     */
    private const val EXTRA_PATH_DIRS =
        "/data/data/com.termux/files/usr/bin:/data/local/tmp/bin:" +
            "/data/adb/modules/busybox-ndk/system/bin:/data/adb/magisk:/sbin/.magisk/busybox"

    /**
     * 生成 PATH 增强片段：把存在的候选目录追加到既有 PATH 末尾。
     *
     * 逐个判定而非整体拼接 —— 目录不存在时整体拼进去会让 PATH 里留下空条目，
     * 空条目等价于当前目录，命令解析会先在当前目录找可执行文件，属注入面。
     */
    private fun pathExportFragment(): String =
        "for d in \$(echo " + escapeShellArg(EXTRA_PATH_DIRS) + " | tr ':' ' '); do " +
            "[ -d \"\$d\" ] && PATH=\"\$PATH:\$d\"; done; export PATH; "

    fun initSettings(settings: AppSettings) {
        appSettings = settings
        sweepExecutionTempFiles()
    }

    /** 启动清扫只在本进程首次初始化时执行一次。 */
    private val sweptExecutionTemp = AtomicBoolean(false)

    /**
     * 「另存为」待清理临时文件的登记表（一行一条绝对路径，位于应用私有 files 目录）。
     *
     * 临时文件建在**目标同目录**（同文件系统内 rename/mv 才原子），目录由用户自由指定，
     * 无法用目录白名单覆盖扫描。若在「写完临时文件」与「替换目标」之间进程被强杀/OOM，
     * 残留的隐藏文件会落在用户可见目录里，且不在 [sweepExecutionTempFiles] 的固定目录范围内。
     * 故写入前先登记路径、替换或就地删除后撤销登记，下次启动据此精确清理：
     * 不依赖 mtime 阈值，也不遍历用户目录。
     */
    private const val SAVE_TEMP_JOURNAL = ".shso_save_pending"

    private val saveTempJournalLock = Any()

    /**
     * 登记一个「另存为」临时文件路径，供下次启动兜底清理。
     *
     * 仅接受形如另存为临时文件的路径（[isSaveTempPath]），避免调用方误传目标文件路径。
     */
    fun recordSaveTemp(path: String) {
        if (!isSaveTempPath(path)) return
        synchronized(saveTempJournalLock) {
            runCatching {
                File(com.mixradio.droid.ShsoApplication.appContext.filesDir, SAVE_TEMP_JOURNAL)
                    .appendText(path + "\n")
            }
        }
    }

    /** 临时文件已被 mv/rename 消费或已就地删除，撤销登记。 */
    fun clearSaveTemp(path: String) {
        synchronized(saveTempJournalLock) {
            runCatching {
                val journal = File(com.mixradio.droid.ShsoApplication.appContext.filesDir, SAVE_TEMP_JOURNAL)
                if (!journal.exists()) return
                val remain = journal.readLines().filter { it.isNotBlank() && it.trim() != path }
                if (remain.isEmpty()) journal.delete()
                else journal.writeText(remain.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /**
     * 清理登记在案的「另存为」临时文件残留。
     *
     * 只删名字符合临时文件命名的条目（[isSaveTempPath]）：登记表被截断产生脏行时，
     * 不至于把任意路径删掉。删不掉的条目保留在登记表里，留给下次启动重试
     * （root 路径在应用侧 `File.exists()` 恒为假，故 root 分支以 `rm` 退出码为准）。
     */
    private suspend fun sweepRegisteredSaveTemps() {
        val journal = File(com.mixradio.droid.ShsoApplication.appContext.filesDir, SAVE_TEMP_JOURNAL)
        val entries = runCatching { journal.readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { isSaveTempPath(it) }.distinct()
        if (entries.isEmpty()) {
            runCatching { journal.delete() }
            return
        }
        val remain = entries.filter { path ->
            val removed = if (isRootGranted == true) {
                val (code, _) = runCommandSync("rm -f " + escapeShellArg(path), 10_000L)
                code == 0
            } else {
                !File(path).exists() || runCatching { File(path).delete() }.getOrDefault(false)
            }
            !removed
        }
        runCatching {
            if (remain.isEmpty()) journal.delete()
            else journal.writeText(remain.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * 清扫上一次运行遗留的临时文件：执行期文件（`.exec_attr_*` / `.sh_norm_*`，在应用私有目录）、
     * 安装临时包（`/data/local/tmp/_shso_install_*.apk` / `_shso_split_*.apk`）与安装确认弹窗的
     * 暂存副本（`cacheDir` 下 `install-confirm` 目录里的暂存 apk）。
     *
     * 正常路径在协程 finally 里删除；App 被强杀、OOM，或执行协程卡在 native 管道读上时
     * finally 根本不执行，残留只能在启动时兜底。仅扫一次：运行中扫会误删正在使用的临时文件。
     *
     * 安装包与暂存副本多一层约束：`pm install` 超时分支**刻意**保留临时包（无法确认 su 是否仍在读它），
     * 暂存副本也可能正被并行安装使用，因此只删「超过 1 小时未变动」的（`-mmin +60` / mtime 阈值）。
     * 安装超时上限为 5 分钟，一小时足以让任何仍在进行的安装读完，不会误删在途文件。
     */
    private fun sweepExecutionTempFiles() {
        if (!sweptExecutionTemp.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                com.mixradio.droid.ShsoApplication.appContext.filesDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.startsWith(".exec_attr_") || name.startsWith(".sh_norm_")) {
                        runCatching { file.delete() }
                    }
                }
                // 安装确认弹窗的暂存副本：确认弹窗打开期间进程被强杀时，删除代码不执行，
                // 只能靠启动清扫兜底；否则每次安装残留一份（XAPK 整套可达数十 MB）。
                runCatching {
                    val stagedDir = File(com.mixradio.droid.ShsoApplication.appContext.cacheDir, "install-confirm")
                    val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
                    stagedDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
                    }
                }
                // 该目录属 root，只能经 su 清理；失败（未获 root）不影响启动。
                runCatching {
                    runCommandSync(
                        "find " + escapeShellArg(ROOT_TMP_DIR) + " -maxdepth 1 -type f " +
                            "\\( -name '_shso_install_*.apk' -o -name '_shso_split_*.apk' \\) " +
                            "-mmin +60 -exec rm -f {} + 2>/dev/null",
                        10_000L
                    )
                }
                // XAPK 解压暂存目录：正常路径由 installXapk 的 finally 删除，进程被强杀时不会执行。
                // 同样只删超过 1 小时未变动的，避免误删仍在进行的安装。
                runCatching {
                    val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
                    com.mixradio.droid.ShsoApplication.appContext.cacheDir.listFiles()?.forEach { f ->
                        if (f.name.startsWith(".shso_xapk_") && f.lastModified() < cutoff) {
                            runCatching { f.deleteRecursively() }
                        }
                    }
                }
                // 「另存为」临时文件残留：路径随用户目标目录而变，登记表是唯一线索。
                sweepRegisteredSaveTemps()
            }
        }
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
     * @param reclaimOnTimeout 超时是否走 [forceKillProcessTree] 做整组回收。默认 true；
     *   [forceKillProcessTree] 内部发起的 kill 命令必须传 false —— 否则
     *   「runCommandSync 超时 → forceKillProcessTree → runCommandSync（kill 命令）超时 →
     *   forceKillProcessTree …」互递归无上限：su 停在授权弹窗不回应时，每 5s 就多挂一个 su
     *   进程与一条读线程，链长仅由用户何时拒绝授权决定。
     */
    fun runCommandSync(
        cmd: String,
        timeoutMs: Long = 120_000L,
        reclaimOnTimeout: Boolean = true,
        runAsRoot: Boolean = true
    ): Pair<Int, String> {
        return try {
            // 先记下 su 的 pid：超时时用它反查进程组并整组回收。
            val process = (if (runAsRoot) ProcessBuilder("su", "-c", cmd) else ProcessBuilder("sh", "-c", cmd))
                .redirectErrorStream(true).start()
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
                if (reclaimOnTimeout && runAsRoot) {
                    // 先整组回收，再 destroy su 本身：顺序反了的话组组长已消失，
                    // 后续按 pgid 校验会 fail-closed 而漏掉子孙进程。
                    forceKillProcessTree(process, suPid)
                } else {
                    // 回收通道自身的超时：只强制销毁 su，绝不回跳 forceKillProcessTree（互递归无界）。
                    runCatching { process.destroyForcibly() }
                }
                readerThread.join(3000)
                // 关流迫使仍卡在 read() 的读线程退出，否则每次超时都泄漏一条常驻线程 + 管道 FD。
                if (readerThread.isAlive) forceCloseProcess(process)
                return Pair(-1, collector.snapshot() + "\n[shso] 命令执行超时（>${timeoutMs}ms），已强制终止")
            }
            // 正常结束：管道写端全部关闭，读线程很快就会拿到 EOF。
            // join 必须留足时间，否则尾部输出会随线程一起被丢弃（静默截断）。
            readerThread.join(5000)
            if (readerThread.isAlive) {
                // join 超时：读线程仍卡在 read()（说明有后代进程还持着管道写端）。
                // 关流迫使 read 返回、线程自尽；不再无限等待，但要让调用方知道输出可能不完整。
                // 不关流的话每次都泄漏一条常驻读线程 + 管道 FD + Process 句柄。
                forceCloseProcess(process)
                readerThread.join(500)
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
     * [buildProcessGroupKillCommand] 做三重校验（组成员存活、不误杀本应用所在进程组）。
     * 校验不通过时只 kill `su` 本身 —— 此时子孙已无法可靠归属，
     * 宁可不杀也不能误杀无关进程组。
     *
     * 本函数内部发起的两条 kill 命令都传 `reclaimOnTimeout = false`：它们自身超时时
     * 只销毁 su，不再回跳本函数，斩断互递归（见 [runCommandSync] 参数说明）。
     */
    private fun forceKillProcessTree(process: Process, suPid: Int, runAsRoot: Boolean = true) {
        if (suPid > 1) {
            val pgrp = runCatching {
                val stat = File("/proc/$suPid/stat").readText()
                // 格式：pid (comm) state ppid pgrp …；comm 可能含空格与右括号，
                // 故从**最后一个** ')' 之后开始切分。
                stat.substringAfterLast(')').trim().split(' ').getOrNull(2)?.toIntOrNull()
            }.getOrNull() ?: 0
            if (pgrp > 1) {
                buildProcessGroupKillCommand(9, pgrp, android.os.Process.myPid())?.let {
                    runCommandSync(it, timeoutMs = 5_000L, reclaimOnTimeout = false, runAsRoot = runAsRoot)
                }
            }
        }
        runCatching { process.destroyForcibly() }
        // 再补一次直接子进程：某些 su 实现不新建会话，此时组杀会被自身进程组校验挡下。
        if (suPid > 1) {
            runCommandSync("kill -9 $suPid 2>/dev/null", timeoutMs = 5_000L, reclaimOnTimeout = false, runAsRoot = runAsRoot)
        }
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
        // 终端命令恒经 su 执行，身份必须一并复位：否则上一次「非 Root 执行」遗留的 false
        // 会让本命令的「中断/结束」走应用自身 uid 的 kill 通道，杀不到 root 名下的进程组。
        execUsesRoot = true
        // 清残留必须发生在**启动进程之前**：进程一旦起来就可能立刻吐输出，
        // 之后再 clear 会把这段输出丢掉。
        HyperCore.clearBatchQueue()
        val pgidRecorder = runPgidFile?.let { "echo " + "\$\$" + " > " + escapeShellArg(it.absolutePath) + "; " } ?: ""
        // 终端同样补 TMPDIR：终端里直接跑 Termux 打包的自解压脚本会走到脚本内的
        // 空值回退分支（硬编码 /data/data/com.termux/…），未装 Termux 时 mktemp 即失败。
        val process = try {
            ProcessBuilder("su", "-c", pgidRecorder + pathExportFragment() +
                "export TMPDIR=" + ROOT_TMP_DIR + "; " + command)
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendOutputDirect("[执行失败: ${e.message}]\n") }
            return -1
        }
        // 长开 stdin 并发布 writer：终端一次性命令同样可能是交互式脚本
        // （read / 二次确认 / 菜单选择）。原先在这里 close 会导致脚本的 read 立刻拿到
        // EOF 而自行走完（表现出「遇到交互阶段直接结束」），与文件页「执行」链路不一致。
        // 写入口复用 [sendInput] —— 它在发送前按 [isTerminalBusy] 判定，
        // 而本函数起进程后会把 writer 发布到 [processWriter]，故 busy 判据自然成立。
        val terminalWriter = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        // 先记下 su 的 pid：超时时用它反查进程组并整组回收。
        val suPid = pidOfProcess(process)

        // 槽位归属自检：起进程期间可能已被 kill/新任务抢走，此时不得再写全局句柄，
        // 否则会覆盖别人的 activeProcess/currentTaskName，并 cancel 掉对方的发布循环。
        if (slotToken != 0L && terminalSlotOwner.get() != slotToken) {
            runCatching { terminalWriter.close() }
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
            processWriter = terminalWriter
            processPid = suPid
        }
        // 复用任务用的批量发布通道：它按 isTaskRunning 存活，命令结束即自行退出并 flush 残留。
        // 记下归属令牌：命令可重叠，收尾时只能停自己那个循环。
        val flushLoop = HyperCore.startBatchFlushLoop(scope, { isTaskRunning }) { appendOutputDirect(it) }
        activeFlushLoop = flushLoop
        // 长命令保活（与脚本任务同源）：切到后台后维持前台优先级，通知里也提供「结束进程」出口。
        // 必须在**此刻**拉起 —— 此时应用仍在前台；一旦转后台，targetSdk 35 下
        // startForegroundService 会抛 ForegroundServiceStartNotAllowedException 并被 runCatching
        // 静默吞掉，于是这个「保活」恰在最需要它的场景失效：进程转 cached 后被 LMK 杀（su 子进程
        // 收 SIGPIPE 暴亡，失去唯一的「结束进程」出口），或被 cached freezer 冻结导致读取协程停调度、
        // 子进程写满 64KB 管道缓冲后阻塞在 write。服务自身轮询 isTaskRunning，
        // 秒回命令结束后立即 stopSelf，通知只闪一下。
        runCatching { ExecutionForegroundService.start(com.mixradio.droid.ShsoApplication.appContext) }
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
                            // 迟到的输出不得混进已接管的新任务：新命令/新任务会 clearBatchQueue，
                            // 代际不符时投递的内容会绕过清屏直接追加到新任务的日志里。
                            if (terminalCommandGeneration.get() == generation) {
                                HyperCore.queueLogChunk(String(buffer, 0, count))
                            }
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
            // 只杀 su 会把 `sh -c …` 及其子孙留成 init 名下的 root 孤儿。与 [runCommandSync] 同法，
            // 用 su 的 pid 反查进程组整组回收（不依赖可能陈旧的 runPgid，故无 pid 复用误杀风险）。
            forceKillProcessTree(process, suPid)
            forceCloseProcess(process)
        }
        readerThread.join(3000)
        // 正常结束路径也要收流：后代进程持有管道写端时（`longjob &` 这类后台化命令）读线程会
        // 一直阻塞在 read()，泄漏一条常驻线程 + 管道 FD + Process 句柄。关流迫使 read 返回。
        if (readerThread.isAlive) forceCloseProcess(process)
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
                // 命令已结束：关闭写端，让脚本侧的 read 拿到干净的 EOF，
                // 同时避免 writer 与 Process 句柄泄漏。必须清 null —— 留着会让后续
                // 空闲期的 sendInput 写进已关闭的流，报「Stream closed」。
                runCatching { terminalWriter.close() }
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
            // 错误通道必须与 stdout 分开：合并后本函数从不读取该管道，
            // cat 写失败的错误文本会堆到写满并把子进程阻塞在 write 上 ——
            // 调用方因此干等满 timeoutSec 才收到 false，错误原因也一并丢失。
            val process = ProcessBuilder("su", "-c", "cat $redirect ${escapeShellArg(targetPath)}").start()
            // 错误通道单独 drain（只读不展示），避免子进程因 stderr 管道写满而永久阻塞。
            val errDrain = Thread { runCatching { process.errorStream.use { it.readBytes() } } }
            errDrain.isDaemon = true
            errDrain.start()
            // 写入必须放独立线程：su 若卡在授权弹窗，而内容超过管道缓冲（约 64KB），
            // 在调用线程直接 write 会永久阻塞，waitFor 的超时形同虚设。
            val writer = Thread {
                runCatching {
                    process.outputStream.use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                }
            }
            writer.isDaemon = true
            writer.start()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                // destroyForcibly 关闭管道，卡住的写线程随即以 IOException 退出。
                process.destroyForcibly()
                writer.join(1_000)
                errDrain.join(1_000)
                return false
            }
            writer.join(2_000)
            val ok = process.exitValue() == 0 && !writer.isAlive
            process.destroy()
            ok
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
    fun executeFile(filePath: String, runAsRoot: Boolean? = null) {
        val file = File(filePath)
        val fileName = file.name
        // 与 FileItem.isExecutableScript/Binary 同口径：复用 realExtensionOf 剥掉「.数字」尾缀再判格式。
        // 只放宽文件页谓词而这里按字面后缀判定，会让带尾缀的脚本出现「有执行入口却报不支持格式」。
        val realExtension = realExtensionOf(fileName)
        val isSh = realExtension == "sh"
        val isSo = realExtension == "so"

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
        // 同步段必须一并作废上一轮的执行实体：归一化判定在协程体内执行，其间要跨两次 su 往返
        // （stat 最长 120s + 读头部 5s），窗口内 runPgid / activeProcess / processPid 仍是上一轮的值。
        // 此时点「结束进程」会拿旧 pgid 整组发信号 —— 该 pid 已被内核复用为无关进程组组长时，
        // 三重校验只确认「是组长、非本应用组」，会以 root 误杀无关进程组。
        runPgid = 0
        activeProcess = null
        processPid = 0
        // 执行身份与上面几项同属「上一轮执行实体」：新任务的身份在起进程时才确定，
        // 若窗口内点到「结束进程」，用旧身份选 kill 通道会把非 Root 任务的 kill 送上 su。
        execUsesRoot = true
        // processWriter 同属上一轮执行实体：作废清单漏掉它时，旧任务 finally 因 isCurrentJob=false
        // 会跳过全局清理，但它对**局部引用** writer?.close() 是无条件执行的 —— 全局 processWriter
        // 随即指向一个已关闭的 writer。归一化窗口内（root 兜底最长约 125s）用户输入会因
        // isTerminalBusy 第二子句（stale writer 非空）判假而获受理，写入已关闭流抛 IOException，
        // 输入被吞成「[发送失败: Stream closed]」。
        processWriter = null
        // executionJob 也要在同步段一并作废：上面槽位令牌已做同样清零，唯独 Job 侧漏了 ——
        // 同步段到下方重赋新 Job 之间，executionJob 仍指向旧 JobA。若主线程这段被长 GC 拖住，
        // kill 协程的 su 往返先完成，stillOwnsExecution(JobA) 判真，kill 收尾会把新任务刚置的
        // isTaskRunning / currentTaskName 清掉 —— 脚本在跑，UI 却显示「待命中」。
        executionJob?.cancel()
        executionJob = null

        // 心跳：脚本长时间无输出时，输出区会整段静止，用户无法区分「正在跑」与「已卡死」。
        // 每 10s 追加一行计时；脚本一旦产出内容即停止打扰（满屏心跳会淹没真实日志）。
        // 标志跨线程读写，用 AtomicBoolean 而非局部 @Volatile（后者不能修饰局部变量）。
        val producedAnyOutput = java.util.concurrent.atomic.AtomicBoolean(false)
        val heartbeatGeneration = terminalCommandGeneration.get()
        val heartbeatJob = scope.launch(Dispatchers.Main) {
            var elapsed = 0L
            // 代际自检：新任务/新终端命令都会自增该计数。旧任务若因子孙进程持有管道而永远读不到
            // EOF，它的协程与 finally 都不执行，心跳就挂在下方的全局 isTaskRunning 上继续给
            // **新任务**追加计时行；比对代际可让它在 1s 内自杀（`executionJob === myJob` 在此处
            // 取不到 myJob，而代际计数与执行协程在同一处自增，等价且更简单）。
            while (isActive && isTaskRunning && terminalCommandGeneration.get() == heartbeatGeneration) {
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
                val tNoBom = escapeShellArg(tmp.absolutePath + ".nobom")
                // 先一次性读原文件、去 CR 写出副本，再在**副本**上探 BOM 并剥除。
                // 原实现「先读原文件头 3 字节探 BOM、再整读原文件去 CR」两次读的是两份可能不同的数据
                // （TOCTOU），且 od 读空时把「探测失败」当成「无 BOM」。改为只读原文件一次，
                // BOM 探测与剥除都作用在同一份已落盘的内容上。
                val prepare = "( tr -d '\r' < $escapedFile > $t 2>/dev/null; " +
                    "if [ \"\$(head -c 3 $t 2>/dev/null | od -An -tx1 | tr -d ' \n')\" = efbbbf ]; then " +
                    "tail -c +4 $t 2>/dev/null > $tNoBom && mv $tNoBom $t; fi; " +
                    "rm -f $tNoBom ) ; "
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
                //（组长同时是进程组组长与会话组长）。仅 root 路径记录 —— 非 root 侧由 setsid
                // 同步保证「组长就是直接子进程」，直接用其 pid，无需也无法经文件回填。
                val pgidRecorder = if (useRoot) {
                    runPgidFile?.let { "echo " + "\$\$" + " > " + escapeShellArg(it.absolutePath) + "; " } ?: ""
                } else ""
                // 权限还原：直接执行 .so 需要 +x，而 chmod 会**永久**改写用户文件的权限
                // （旧实现 chmod 755 且执行完不还原，等于擅自把 600 放宽成 world-readable）。
                // 这里先把原权限落到 app 私有目录，执行完再按它还原。
                val saveAttrCmd = execAttrFile?.let {
                    // 记录原权限。末尾补 chmod 644：该文件由 root（su）重定向创建，Magisk / KernelSU
                    // 的 umask 077 会把它建成 600 root:root，app 侧 readText() 读不到 → mode=null →
                    // 强杀路径的 Kotlin 侧还原被静默跳过，.so 永久停在放宽后的权限上。
                    // 文件位于应用私有目录，放宽到 644 无泄密面。
                    "stat -L -c %a $escapedFile > ${escapeShellArg(it.absolutePath)} 2>/dev/null; " +
                        "chmod 644 ${escapeShellArg(it.absolutePath)} 2>/dev/null; "
                } ?: ""
                val restoreAttrCmd = execAttrFile?.let {
                    // 只在确实改过 +x 时还原；chmod 失败也不能短路掉执行分支
                    "( [ -s ${escapeShellArg(it.absolutePath)} ] && chmod \$(cat ${escapeShellArg(it.absolutePath)}) $escapedFile 2>/dev/null ); "
                } ?: ""

                // 执行环境补 TMPDIR：Android 应用进程没有 TMPDIR，`su -c` 也不会补，
                // 于是脚本里的 `mktemp -d "${TMPDIR}…"` 拿到空串；Termux 打包的自解压脚本
                // （makeself 风格：头部 + gzip 载荷）更会把空值回退成硬编码的
                // /data/data/com.termux/files/usr/tmp，设备未装 Termux 时该目录不存在，
                // mktemp 失败 → 脚本按 127 退出，且全程只报一行 mktemp 错误，难以归因。
                // root 侧取 /data/local/tmp（root 可写且各机型恒存在）；
                // 非 root 侧取应用私有缓存目录（只有应用 uid 可写，与 root 侧互不影响）。
                val tmpDir = if (useRoot) {
                    ROOT_TMP_DIR
                } else {
                    com.mixradio.droid.ShsoApplication.appContext.cacheDir.absolutePath
                }
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
                        "${pgidRecorder}${pathExportFragment()}" +
                            "export TERM=xterm-256color && export LANG=en_US.UTF-8 && " +
                            "export TMPDIR=$tmpDir && cd $escapedParent && $runShCmd"
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
                        "$pgidRecorder" + pathExportFragment() + saveAttrCmd +
                            "export TERM=xterm-256color && export LANG=en_US.UTF-8 && export TMPDIR=$tmpDir && " +
                            "cd $escapedParent && " +
                            // 执行前必须确认是 ELF：mksh 对 execve 返回 ENOEXEC 的文件会按 POSIX 传统
                            // **隐式回退为 shell 解释**，把二进制/文本当脚本执行，产出满屏乱码，
                            // 且下方的 126/127 分支永远不可达。这里按魔数 7f454c46 提前拦住，
                            // 同时避免对非 ELF 文件做无谓的 chmod + 还原。
                            "if [ \"\$(head -c 4 $escapedFile 2>/dev/null | od -An -tx1 | tr -d ' \n')\" != 7f454c46 ]; then " +
                            "echo \"[shso] \u975e ELF \u53ef\u6267\u884c\u6587\u4ef6\uff0c\u5df2\u8df3\u8fc7\u6267\u884c\"; C=126; else " +
                            "chmod a+x $escapedFile && $escapedFile; C=\$?; " +
                            restoreAttrCmd +
                            "if [ \$C -eq 126 ] || [ \$C -eq 127 ]; then " +
                            "echo \"[shso] \u65e0\u6cd5\u76f4\u63a5\u6267\u884c\uff08\u975e\u53ef\u6267\u884c ELF\uff09\"; fi; fi; " +
                            "exit \$C"
                    }
                } else {
                    // 非 Root：普通 sh 执行（无 su 包装），改不动系统分区。
                    // 归一化同样适用于该身份：runShCmd 在无需归一化时直接交给 sh，
                    // 需要归一化时改写 app 私有临时文件再执行。旧实现硬编码直跑原文件，
                    // 于是 CRLF/BOM 脚本只在 Root 身份下被修正，非 Root 执行时 A75 缺陷原样复现。
                    val nonRootPrefix = pathExportFragment() +
                        "export TERM=xterm-256color && export LANG=en_US.UTF-8 && " +
                        "export TMPDIR=$tmpDir && cd $escapedParent 2>/dev/null; "
                    if (isSo) {
                        // .so 绝不能当脚本喂给 sh：mksh 对 execve 返回 ENOEXEC 的文件会隐式回退为
                        // shell 解释，把 ELF 字节按脚本执行 —— 满屏 U+FFFD、控制字符，真实退出码被
                        // sh 的失败码覆盖。非 Root 下以应用 uid 尽力直接执行（仅对应用可写的文件可行，
                        // /sdcard 等 noexec 挂载点必然失败），失败如实报 126/127，不再静默退化。
                        nonRootPrefix + saveAttrCmd +
                            "if [ \"\$(head -c 4 $escapedFile 2>/dev/null | od -An -tx1 | tr -d ' \n')\" != 7f454c46 ]; then " +
                            "echo \"[shso] 非 ELF 可执行文件，已跳过执行\"; C=126; else " +
                            "chmod u+x $escapedFile 2>/dev/null; $escapedFile; C=\$?; " +
                            restoreAttrCmd +
                            "if [ \$C -eq 126 ] || [ \$C -eq 127 ]; then " +
                            "echo \"[shso] 无法直接执行（非 Root 或文件系统不支持执行）\"; fi; fi; " +
                            "exit \$C"
                    } else {
                        nonRootPrefix + runShCmd
                    }
                }

                // 非 Root 走 setsid：ProcessBuilder 起的子进程继承本应用进程组，而它的 pid 是新分配的、
                // 必不等于该组 id，故子进程本身不是进程组组长 —— toybox setsid 的 setsid(2) 必然成功、
                // 不 fork，退出码原样透传（真机实测 `setsid sh -c 'exit 7'` 得 7），
                // 且 sh 自任会话/组长 ⇒ 新 pgid == 子进程 pid，无需读 /proc 复核。
                // 若不隔离，子进程沿用**本应用自身进程组**：组杀会连应用一起杀掉，
                // 于是「中断/结束」只能杀到外层 sh，内层脚本以应用 uid 继续跑（`sleep 600` 无尽的
                // 典型形态），而应用本来就有权杀同 uid 的进程组，隔离后这条回收通道才成立。
                var isolatedGroup = false
                process = if (useRoot) {
                    ProcessBuilder("su", "-c", execCmd).redirectErrorStream(true).start()
                } else {
                    val setsid = File("/system/bin/setsid")
                    if (setsid.canExecute()) {
                        try {
                            ProcessBuilder(setsid.absolutePath, "sh", "-c", execCmd)
                                .redirectErrorStream(true).start()
                                .also { isolatedGroup = true }
                        } catch (_: Exception) {
                            ProcessBuilder("sh", "-c", execCmd).redirectErrorStream(true).start()
                        }
                    } else {
                        ProcessBuilder("sh", "-c", execCmd).redirectErrorStream(true).start()
                    }
                }
                activeProcess = process
                writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
                processWriter = writer

                // 直接子进程（su）pid，仅用于日志与兜底 destroy。
                // 中断与回收的正确性由下面的进程组 kill 保证，不依赖此 pid。
                val childPid = pidOfProcess(process)
                withContext(Dispatchers.Main) {
                    processPid = childPid
                    execUsesRoot = useRoot
                    // 非 Root 且已隔离：sh 自任会话/进程组组长，pgid == 其 pid，无需等落盘回填。
                    if (isolatedGroup) runPgid = childPid
                }

                // 异步取回本次执行的进程组 id（不阻塞输出读取）。任务结束后不清零，但消费方
                // （killCurrentProcess / restartTerminal / sendInterrupt）一律以「仍有存活实体」
                // 为前提；实体消失后该值弃用，以防 pgid 被内核复用给无关进程组时误杀。
                //
                // 必须按本次 Job 自检：这个协程挂在页面级 scope 上，**不是** executionJob 的子任务，
                // 下一轮 `executionJob?.cancel()` 收不到它。轮询最长 1.5s，期间若新任务已起并
                // 写入了自己的 pgid，此处的迟到结果（尤其是超时归零）会把新任务的进程组冲掉，
                // 它的「中断/结束进程」退化成只杀 su，真正的 `sh -c …` 以 root 继续跑。
                //
                // 仅 Root 需要回填（非 Root 由 setsid 同步确定，且从不写 pgid 文件，
                // 回填只会把刚设好的值冲成 0）。
                if (useRoot) {
                    scope.launch {
                        val pgid = awaitRunPgid()
                        if (executionJob === myJob) runPgid = pgid
                    }
                }

                // 脚本是否产出过内容：静默脚本（只做 rm/touch、无任何 echo）在终端里
                // 除横幅与退出码外一片空白，用户会以为没执行。收尾时据此补一句说明。
                //
                // 读取循环**不做无条件的阻塞读**。native 阻塞读不响应协程取消：当脚本把
                // stdout 交给后台后代、而该后代逃逸到 init 名下长期持有写端时，read 永不返回
                // EOF —— 旧协程、它的 IO 线程与该 Process 句柄一同常驻；任务每被 kill / 覆盖
                // 启动一次就泄漏一份，重复触发会占满 IO 线程池，拖住后续所有文件与网络 IO。
                // 改为「有数据才读、无数据即挂起等待」后，取消（kill / 覆盖启动）在挂起点立即
                // 生效，循环随之退出并关闭流。进程退出后的空窗再保留一小段宽限，覆盖
                // `cmd & ` 这类直接子进程先退、后代稍后补输出的常见写法。
                //
                // 判空必须问 **reader**，不能问底层 stream：InputStreamReader 会预读一大块到自己的
                // 字符缓冲（实测一次 read 后底层 available 已归零，而 reader 里仍留着数千字节），
                // 进程随即退出 -> 循环误判「无数据」-> 缓冲里剩余的尾部输出被整段丢弃。
                // reader.ready() 同时反映缓冲与底层，用它判空才不会截断。
                process.inputStream.use { stream ->
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(2048)
                        var idlePollsAfterExit = 0
                        while (true) {
                            if (!reader.ready()) {
                                if (!process.isAlive) {
                                    if (++idlePollsAfterExit > OUTPUT_IDLE_POLLS_AFTER_EXIT) break
                                }
                                // 挂起等待：协程被取消（kill / 覆盖启动）时在此挂起点立即生效，
                                // 循环退出并关闭流，不再像阻塞读那样把 IO 线程与句柄一起留下。
                                delay(OUTPUT_POLL_MS)
                                continue
                            }
                            val count = reader.read(buffer)
                            if (count == -1) break
                            // 有可读字符却读出 0：解码器在等多字节字符的后续字节，挂起再试，
                            // 否则会在「ready 为真但读不出」的瞬间原地空转。
                            if (count == 0) {
                                delay(OUTPUT_POLL_MS)
                                continue
                            }
                            idlePollsAfterExit = 0
                            // 新任务接管后不得把迟到输出投进新任务的日志；但循环必须继续
                            // 排空管道 —— 提前 break 会让后代进程的写端填满而永久阻塞。
                            // 本协程此时已被取消（cancel 与 Job 赋值同处临界区）。
                            if (executionJob !== myJob) continue
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
                // 权限还原会发起一次 su 往返，而 runCommandSync 是**同步阻塞**函数：放在
                // Dispatchers.Main 上会让每次 .so 收尾都阻塞主线程直至返回，su 停在授权弹窗时
                // 最长卡满 10s 超时（ANR 级）；NonCancellable 又保证强杀路径照样执行 ——
                // 点「结束进程」后 UI 反而冻住。挪到 IO，语义零损失。
                // 仅 Root 场景需要在 Kotlin 侧兜底：shell 内的 restoreAttrCmd 以 root 身份还原，
                // 强杀时它可能没跑到；非 Root 场景由 shell 侧以应用 uid 还原，app 侧再发 su
                // 只会白白唤起授权弹窗。
                if (useRoot && isSo && execAttrFile != null) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val mode = runCatching { execAttrFile.readText().trim() }.getOrNull()
                        if (!mode.isNullOrEmpty()) {
                            runCatching {
                                RootService.runCommandSync(
                                    "chmod $mode ${escapeShellArg(filePath)}",
                                    timeoutMs = 10_000L
                                )
                            }
                        }
                    }
                }
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
                    runCatching { execAttrFile?.delete() }
                    // 归一化脚本临时文件同样必须清理，否则每次执行 .sh 都在 filesDir 留一份。
                    runCatching { normalizedShFile?.delete() }
                    runCatching { normalizedShFile?.let { File(it.absolutePath + ".nobom").delete() } }
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
        // 已有可写进程时**不算忙**：此时输入应直写该进程（无论它是脚本任务还是终端一次性命令）。
        // 原判据把「槽位被占」一票否决，而运行中的一次性命令本身就持有槽位直到结束，
        // 于是交互输入全程被判忙而拒绝 —— 表现为「输入发了却进不了脚本下一步」。
        !(isTaskRunning && processWriter != null) &&
            (terminalSlotOwner.get() != 0L || isTaskRunning)

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
        // 已有可写进程：一律优先直写它（交互态），**不占槽位、不当新命令派发**。
        // 这是「输入发送了也进不了脚本下一步」的根因所在：此前带文本的输入只在
        // isTaskRunning && writer != null 时直写，而槽位判定又先把它挡在门外。
        val existingWriter = processWriter
        if (isTaskRunning && existingWriter != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        appendOutputDirect(if (text.isEmpty()) "\n" else "$text\n")
                    }
                    synchronized(interactiveWriteLock) {
                        existingWriter.write(text + "\n")
                        existingWriter.flush()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendOutputDirect("[发送失败: ${e.message}]\n")
                    }
                }
            }
            return true
        }
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
                        // 归属判定：命令 A 在跑时启动脚本 B（startExecution 先 kill A），A 以 137 退出；
                        // 无此判定则 A 的退出码会落进 B 刚开始的日志，用户误以为 B 已退出。
                        // 读线程投递与 runTerminalCommand 自身收尾都有代际守卫，唯独这条出口漏了。
                        if (exitCode != 0 && terminalSlotOwner.get() == slotToken) {
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
        val targetUsesRoot = execUsesRoot
        // 只认「存活实体」，不认 stale pgid（与 restartTerminal 同口径）：
        // runPgid 在任务收尾时不清零，但那时组长与成员都已被内核回收，该 pgid 可能已被复用给
        // 一个完全无关的进程组 —— buildProcessGroupKillCommand 的校验②（组内有存活成员）
        // 恰好会被这类复用组满足（有成员即放行），所以**不能**据 pgid 放宽守卫。
        // 结论：实体消失后一律不做组杀；被中断后逃逸的子孙只有在执行实体仍存活
        // （执行协程未退出、isTaskRunning 仍为真）时才可能被回收。
        // targetName 通道保留（捕获时点可能与 Job 存在性不同步）。
        val hasLiveEntity = isTaskRunning || targetProcess != null || targetPid > 0
        if (!hasLiveEntity && targetName == null) return

        scope.launch(Dispatchers.IO) {
            try {
                // 先杀**整个进程组**：`su -c` 把命令放进新会话且组长被重挂到 init，
                // 中断后逃逸的子孙进程（`sh -c …`、真正的脚本进程）只有这样才能回收。
                if (targetPgid > 1) {
                    killProcessGroup(9, targetPgid, targetUsesRoot)
                    if (targetPid > 0) {
                        runKillSync("kill -9 $targetPid 2>/dev/null", targetUsesRoot)
                    }
                    targetProcess?.destroyForcibly()
                } else {
                    // 兜底：进程组不可用时（pgid 未落盘；或非 Root 且设备无 setsid，子进程沿用
                    // 本应用进程组，整组回收会误伤自身）按**完整路径**匹配残留子孙。
                    // 不可退化为按文件名匹配：同一脚本「覆盖启动」时，新任务的命令行里含同样的文件名，
                    // `pkill -9 -f <文件名>` 会把刚启动的新任务一并杀死（表现为覆盖启动后脚本秒退）。
                    targetPath?.takeIf { it.isNotBlank() }?.let { path ->
                        // 必须转 ERE 元字符：pkill -f 把参数当扩展正则，
                        // 路径里的 `.` `(` `+` 等会被解释而误杀无关进程（且以 root 身份执行）。
                        runKillSync("pkill -9 -f ${escapeShellArg(escapeEreLiteral(path))} 2>/dev/null", targetUsesRoot)
                    }
                    if (targetProcess != null && targetPid > 1) {
                        // 终端命令的 currentTaskPath 恒为 null（pkill 兜底整段跳过），若 pgid 又尚未回填
                        // ——命令刚发出，回填窗口最长 1.5s——就只剩「杀 su」一条路，`sh -c …` 会留成
                        // init 名下的 root 孤儿。这里用 su 的 pid 反查进程组整组回收（与超时路径同源）。
                        // 三重校验 fail-closed：su 未新建会话时组杀被挡下，退化为只杀直接子进程。
                        forceKillProcessTree(targetProcess, targetPid, targetUsesRoot)
                    } else {
                        if (targetPid > 0) {
                            runKillSync("kill -9 $targetPid 2>/dev/null", targetUsesRoot)
                        }
                        targetProcess?.destroyForcibly()
                    }
                }
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
    private fun killProcessGroup(signal: Int, pgid: Int, runAsRoot: Boolean) {
        val cmd = buildProcessGroupKillCommand(signal, pgid, android.os.Process.myPid()) ?: return
        runKillSync(cmd, runAsRoot)
    }

    /**
     * 按任务执行身份发送 kill 命令。
     *
     * Root 任务经 `su`；非 Root 任务以应用自身 uid 执行 —— 同 uid 进程本就允许互发信号，
     * 走 `su` 只会平白弹授权框，且授权框无人回应时 kill 停在超时窗口里，用户看到的是
     * 「点了结束进程但脚本还在跑」。一律 `reclaimOnTimeout = false`：kill 命令自身超时时
     * 不得再触发整组回收（避免与 [forceKillProcessTree] 互相递归）。
     */
    private fun runKillSync(cmd: String, runAsRoot: Boolean) {
        runCommandSync(cmd, timeoutMs = 5_000L, reclaimOnTimeout = false, runAsRoot = runAsRoot)
    }

    fun sendInterrupt() {
        // 同步捕获当前任务实体（Job/进程 writer/pid）：
        // 之后主线程若启动新任务（覆盖启动），这些仍是旧实体，中断只作用于它们，绝不误伤新任务。
        val targetJob = executionJob
        val targetWriter = processWriter
        val targetPid = processPid
        val targetPgid = runPgid
        val targetUsesRoot = execUsesRoot
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
                        killProcessGroup(2, targetPgid, targetUsesRoot)
                    }
                    // 兜底：直接子进程也发一次（个别 su 实现下它就是组长）
                    if (targetPid > 0) {
                        runKillSync("kill -2 $targetPid 2>/dev/null", targetUsesRoot)
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
        val targetUsesRoot = execUsesRoot
        // 与 killCurrentProcess 同理：必须捕获**本次**的槽位令牌。
        // 否则收尾判据退化为「存在任意终端命令」，会误清新命令的状态与发布循环。
        val targetSlot = terminalSlotOwner.get()

        // 无任何活动实体时**只复位横幅，不发信号**。
        // `runPgid` 在任务收尾时不清零，因此跑完任意命令后它仍非零；而那个进程组早已回收，
        // 该 pgid 可能已被内核复用给无关进程组，而 `buildProcessGroupKillCommand` 的
        // 「组内有存活成员」校验会被复用组满足 → 以 root 整组 SIGKILL 一个完全无关的进程组。
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
                    killProcessGroup(9, targetPgid, targetUsesRoot)
                }
                if (targetPid > 0) {
                    runKillSync("kill -9 $targetPid 2>/dev/null", targetUsesRoot)
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
                        execUsesRoot = true
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
 * 判定路径是否是「另存为」写入过程中产生的临时文件（顶层纯函数，便于 JVM 单测）。
 *
 * root 通道：`<目标目录>/.shso_edit_<uuid>.tmp`；
 * 非 root 通道：`<目标目录>/.<目标名>.shso_<uuid>.tmp`。
 * 两者共同特征：绝对路径、文件名为隐藏名（`.` 开头）、含 `.shso_`、以 `.tmp` 结尾。
 *
 * 含换行/回车的一律排除：登记表按行存储，被污染的行会被截断成两条，反而产生误删风险。
 */
internal fun isSaveTempPath(path: String): Boolean {
    if (path.isEmpty() || !path.startsWith("/")) return false
    if (path.contains('\n') || path.contains('\r') || path.contains('\u0000')) return false
    val name = path.substringAfterLast('/')
    return name.startsWith(".") && name.contains(".shso_") && name.endsWith(".tmp")
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
 * ② 该进程组**仍有存活成员**；
 * ③ 本应用自己的 pgrp != 该 pgid —— 防止某些 `su` 实现不新建会话时，误杀应用自身所在的进程组。
 *
 * 校验②为什么不能用「组长存活」（`/proc/<pgid>/stat` 存在）：组长死后组 id 仍有效，
 * 成员继续保留 pgid。而组长先退出的场景恰是常态 —— `SIGINT` 首先打到组长，
 * `sleep 600 & exit 0` 这类后台化脚本也让组长立即退出。旧校验把「组长已死、成员仍活」
 * 的整组回收短路成 no-op，子孙以 root 逃逸到 init 名下而 UI 报「已结束」。
 * 改为遍历 `/proc/<pid>/stat`，看是否存在 pgrp == P 的进程（纯 shell 内建，不派生外部进程，
 * 也不依赖 `ps -o PGID`——Android toybox 的该字段恒为 0）。
 *
 * `/proc/<pid>/stat` 不能按空格直接取第 N 字段：comm（进程名）可含空格与右括号
 * （prctl 改名，如 `sh -c x`），字段会整体错位，把 state 当成 pgrp。先跳到**最后一个**
 * 右括号之后，再取 `state ppid pgrp …` 的第 3 个字段 —— 与 [forceKillProcessTree]
 * Kotlin 侧 `substringAfterLast(')')` 的口径一致。
 *
 * 返回 `null` 表示不安全，调用方必须放弃 kill。
 */
internal fun buildProcessGroupKillCommand(signal: Int, pgid: Int, myPid: Int): String? {
    if (pgid <= 1) return null
    return "P=$pgid; M=$myPid; " +
        // ② 组内仍有存活成员即放行（组长死活无关）。
        "G=; for d in /proc/[0-9]*; do read -r L < \"\$d/stat\" 2>/dev/null; " +
        "L=\${L##*) }; set -- \$L; [ \"\$3\" = \"\$P\" ] && G=1; [ -n \"\$G\" ] && break; done; " +
        "[ -n \"\$G\" ] && " +
        // ③ 目标组不得是本应用自身所在组
        "[ \"\$(sed 's/.*) //' /proc/\$M/stat | cut -d' ' -f3)\" != \"\$P\" ] && " +
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
