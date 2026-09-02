// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package Kernel.Extend.data

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    var outputLog by mutableStateOf(HyperCore.generateEngineBanner("工作中"))
        private set

    var lastExitCode by mutableStateOf<Int?>(null)
        private set

    var processPid by mutableIntStateOf(0)
        private set

    private var activeProcess: Process? = null
    private var processWriter: OutputStreamWriter? = null
    private var executionJob: Job? = null

    fun initSettings(settings: AppSettings) {
        appSettings = settings
        outputLog = if (settings.showHyperCoreBanner) HyperCore.generateEngineBanner("工作中") else ""
    }

    fun detectEnvironmentInfo(): String = HyperCore.detectEnvironmentInfo()
    fun detectKernelInfo(): String = HyperCore.detectKernelInfo()
    fun generateEngineBanner(statusText: String = "工作中"): String = HyperCore.generateEngineBanner(statusText)

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
        val parentDir = file.parent ?: "/data/adb/KernelEX"
        val isSh = fileName.endsWith(".sh", ignoreCase = true)
        val isSo = fileName.endsWith(".so", ignoreCase = true)

        if (!isSh && !isSo) {
            appendOutputDirect("\n[!] 错误: 不支持的文件格式，仅支持执行 .sh 和 .so 文件\n")
            return
        }

        lastExecutedPath = filePath

        HyperCore.clearBatchQueue()
        val showHyperCore = appSettings?.showHyperCoreBanner ?: true
        val showKernelEX = appSettings?.showKernelEXBanner ?: true

        outputLog = if (showHyperCore) HyperCore.generateEngineBanner("工作中") else ""
        isTaskRunning = true
        currentTaskName = fileName
        currentTaskPath = filePath
        taskStartTime = System.currentTimeMillis()
        lastExitCode = null

        if (showKernelEX) {
            appendOutputDirect(HyperCore.generateTaskHeader(fileName, filePath, parentDir, showHyperCore))
        }

        HyperCore.startBatchFlushLoop(scope, { isTaskRunning }) { flushedText ->
            appendOutputDirect(flushedText)
        }

        executionJob?.cancel()
        executionJob = scope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                val escapedParent = parentDir.replace("'", "'\\''")
                val escapedFile = filePath.replace("'", "'\\''")

                val execCmd = "export PATH=/sbin:/system/sbin:/system/bin:/system/xbin:${'$'}PATH && export TERM=xterm-256color && export LANG=en_US.UTF-8 && cd '$escapedParent' && chmod 777 '$escapedFile' && ( '$escapedFile' || sh '$escapedFile' )"

                process = ProcessBuilder("su", "-c", execCmd).redirectErrorStream(true).start()
                activeProcess = process
                processWriter = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

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
                withContext(Dispatchers.Main) {
                    lastExitCode = exitCode
                    if (appSettings?.showKernelEXBanner != false) {
                        appendOutputDirect("\n[KernelEX Engine] 任务已退出，退出码: $exitCode\n")
                    }
                }
            } catch (e: Exception) {
                HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
                withContext(Dispatchers.Main) {
                    if (appSettings?.showKernelEXBanner != false) {
                        appendOutputDirect("\n[KernelEX Engine] 异常终止: ${e.message}\n")
                    }
                    lastExitCode = -1
                }
            } finally {
                try {
                    processWriter?.close()
                } catch (_: Exception) {}
                try {
                    process?.destroy()
                } catch (_: Exception) {}
                HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
                withContext(Dispatchers.Main) {
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
        scope.launch(Dispatchers.IO) {
            try {
                if (processPid > 0) {
                    runCommandSync("kill -9 $processPid 2>/dev/null")
                }
                currentTaskName?.let { taskName ->
                    val escapedTaskName = taskName.replace("'", "'\\''")
                    runCommandSync("pkill -9 -f '$escapedTaskName' 2>/dev/null")
                }
                activeProcess?.destroyForcibly()
                activeProcess = null
                processWriter = null
                executionJob?.cancel()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (appSettings?.showKernelEXBanner != false) {
                        appendOutputDirect("\n[KernelEX Engine] 结束进程失败: ${e.message}\n")
                    }
                }
            } finally {
                HyperCore.flushBatchQueueImmediate { appendOutputDirect(it) }
                withContext(Dispatchers.Main) {
                    isTaskRunning = false
                    currentTaskName = null
                    currentTaskPath = null
                    lastExitCode = 137
                    processPid = 0
                    if (appSettings?.showKernelEXBanner != false) {
                        appendOutputDirect("\n[KernelEX Engine] 用户已手动结束进程\n")
                    }
                }
            }
        }
    }

    fun sendInterrupt() {
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
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutputDirect("[中断失败: ${e.message}]\n")
                }
            }
        }
    }

    fun restartTerminal() {
        scope.launch(Dispatchers.IO) {
            try {
                executionJob?.cancel()
                if (processPid > 0) {
                    runCommandSync("kill -9 $processPid 2>/dev/null")
                }
                activeProcess?.destroyForcibly()
                activeProcess = null
                processWriter = null
                HyperCore.clearBatchQueue()
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    isTaskRunning = false
                    currentTaskName = null
                    currentTaskPath = null
                    taskStartTime = 0L
                    lastExitCode = null
                    processPid = 0
                    val showHyperCore = appSettings?.showHyperCoreBanner ?: true
                    outputLog = if (showHyperCore) HyperCore.generateEngineBanner("工作中") else ""
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
