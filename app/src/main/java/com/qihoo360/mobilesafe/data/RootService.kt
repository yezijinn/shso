// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

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

object RootService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var appSettings: AppSettings? = null

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

    fun runCommandSync(cmd: String): Pair<Int, String> {
        return try {
            val process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val result = process.inputStream.use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buffer = CharArray(1024)
                    var count: Int
                    while (reader.read(buffer).also { count = it } != -1) {
                        sb.append(buffer, 0, count)
                    }
                    sb.toString()
                }
            }
            val exitCode = process.waitFor()
            Pair(exitCode, result)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "执行异常")
        }
    }

    fun executeFile(filePath: String) {
        if (isTaskRunning) {
            killCurrentProcess()
        }

        val file = File(filePath)
        val fileName = file.name
        val parentDir = file.parent ?: "/data/adb/shso"
        val isSh = fileName.endsWith(".sh", ignoreCase = true)
        val isSo = fileName.endsWith(".so", ignoreCase = true)

        if (!isSh && !isSo) {
            appendOutputDirect("\n[!] 错误: 不支持的文件格式，仅支持执行 .sh 和 .so 文件\n")
            return
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

        if (showShso) {
            appendOutputDirect(HyperCore.generateTaskHeader(fileName, filePath, parentDir, showHyperCore))
        }

        HyperCore.startBatchFlushLoop(scope, { isTaskRunning }) { flushedText ->
            appendOutputDirect(flushedText)
        }

        executionJob?.cancel()
        executionJob = scope.launch(Dispatchers.IO) {
            var process: Process? = null
            var writer: OutputStreamWriter? = null
            try {
                val escapedParent = parentDir.replace("'", "'\\''")
                val escapedFile = filePath.replace("'", "'\\''")

                val execCmd = "export PATH=/sbin:/system/sbin:/system/bin:/system/xbin:${'$'}PATH && export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd '$escapedParent' && chmod 777 '$escapedFile' && ( '$escapedFile' || sh '$escapedFile' )"

                process = ProcessBuilder("su", "-c", execCmd).redirectErrorStream(true).start()
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

    fun sendInput(text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                if (isTaskRunning && processWriter != null) {
                    withContext(Dispatchers.Main) {
                        appendOutputDirect(if (text.isEmpty()) "\n" else "$text\n")
                    }
                    processWriter?.write(text + "\n")
                    processWriter?.flush()
                } else if (text.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendOutputDirect("> $text\n")
                    }
                    val (exitCode, output) = runCommandSync(text)
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
                    val escapedTaskName = taskName.replace("'", "'\\''")
                    runCommandSync("pkill -9 -f '$escapedTaskName' 2>/dev/null")
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
