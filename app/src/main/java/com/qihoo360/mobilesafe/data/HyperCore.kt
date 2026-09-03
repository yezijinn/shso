// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

object HyperCore {

    private val logBatchQueue = ConcurrentLinkedQueue<String>()
    private var batchFlushJob: Job? = null

    private const val MAX_LOG_LENGTH = 250_000
    private const val PRUNE_TARGET_LENGTH = 180_000

    fun detectEnvironmentInfo(): String {
        val arch = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "arm64-v8a"
        val androidVer = Build.VERSION.RELEASE
        val sdkInt = Build.VERSION.SDK_INT
        return "Android $androidVer (API $sdkInt) / $arch"
    }

    fun detectKernelInfo(): String {
        val osVer = System.getProperty("os.version") ?: "Linux"
        return "Linux $osVer"
    }

    /**
     * 生成引擎启动横幅。
     *
     * @param rootGranted ROOT 探测结果：true=已获得 / false=未获得 / null=尚未探测，
     *   权限行据此输出对应文案（不再写死 ROOT）。
     */
    fun generateEngineBanner(statusText: String = "工作中", rootGranted: Boolean? = null): String {
        val env = detectEnvironmentInfo()
        val kernel = detectKernelInfo()
        val rootLine = when (rootGranted) {
            true -> "[HyperCore Engine] 当前权限：ROOT"
            false -> "[HyperCore Engine] 无ROOT 请先授予ROOT权限再使用"
            null -> "[HyperCore Engine] 当前权限：检测中…"
        }
        return """[HyperCore Engine] 引擎初始化成功
$rootLine
[HyperCore Engine] 运行环境：$env
[HyperCore Engine] 系统内核：$kernel
[HyperCore Engine] 运行状态：$statusText
========================================
"""
    }

    fun generateTaskHeader(fileName: String, filePath: String, parentDir: String, showHyperCore: Boolean): String {
        val sb = StringBuilder()
        if (!showHyperCore) {
            sb.append("========================================\n")
        }
        sb.append("[shso Engine] 启动任务: $fileName\n")
        sb.append("[shso Engine] 路径: $filePath\n")
        sb.append("[shso Engine] 工作目录: $parentDir\n")
        sb.append("========================================\n")
        return sb.toString()
    }

    fun queueLogChunk(chunk: String) {
        logBatchQueue.offer(chunk)
    }

    fun clearBatchQueue() {
        logBatchQueue.clear()
    }

    fun startBatchFlushLoop(
        scope: CoroutineScope,
        isTaskRunningProvider: () -> Boolean,
        onFlush: (String) -> Unit
    ) {
        batchFlushJob?.cancel()
        batchFlushJob = scope.launch(Dispatchers.Main) {
            while (isActive && isTaskRunningProvider()) {
                delay(16)
                if (logBatchQueue.isNotEmpty()) {
                    val sb = StringBuilder()
                    while (true) {
                        val item = logBatchQueue.poll() ?: break
                        sb.append(item)
                    }
                    if (sb.isNotEmpty()) {
                        onFlush(sb.toString())
                    }
                }
            }
        }
    }

    suspend fun flushBatchQueueImmediate(onFlush: (String) -> Unit) = withContext(Dispatchers.Main) {
        if (logBatchQueue.isNotEmpty()) {
            val sb = StringBuilder()
            while (true) {
                val item = logBatchQueue.poll() ?: break
                sb.append(item)
            }
            if (sb.isNotEmpty()) {
                onFlush(sb.toString())
            }
        }
    }

    fun appendWithSlidingWindow(currentLog: String, newText: String): String {
        val updated = currentLog + newText
        return if (updated.length > MAX_LOG_LENGTH) {
            val cutIndex = updated.indexOf('\n', updated.length - PRUNE_TARGET_LENGTH)
            if (cutIndex != -1 && cutIndex < updated.length) {
                updated.substring(cutIndex + 1)
            } else {
                updated.substring(updated.length - PRUNE_TARGET_LENGTH)
            }
        } else {
            updated
        }
    }
}
