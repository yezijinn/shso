// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
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
            // 保留 16ms tick 去 drain 队列（防止队列无界增长），但先累积到 pending，
            // 满足阈值（距上次发布 ≥ minIntervalMs，或累积 ≥ backlogChars）才发布一次，降低重组频率。
            val pending = StringBuilder()
            var lastFlushMs = System.currentTimeMillis()
            // 发布节流：每次发布都有固定主线程开销（组合 + 可见行布局 + 重绘失效），
            // 成本与发布次数成正比、与 item 数无关。250ms ≈ 4 次/秒，
            // 洪流输出下可显著降低占用，刷新延迟几乎无感。
            // backlogChars 是内存安全阀，防止极端积压时 pending 无界增长。
            val minIntervalMs = 250L
            val backlogChars = 400_000
            try {
                while (isActive && isTaskRunningProvider()) {
                    delay(16.milliseconds)
                    if (logBatchQueue.isNotEmpty()) {
                        while (true) {
                            val item = logBatchQueue.poll() ?: break
                            pending.append(item)
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (pending.isNotEmpty() && (now - lastFlushMs >= minIntervalMs || pending.length >= backlogChars)) {
                        onFlush(pending.toString())
                        pending.setLength(0)
                        lastFlushMs = now
                    }
                }
            } finally {
                // 循环退出前必须把残留 pending 文本 flush 一次，避免丢日志
                if (pending.isNotEmpty()) {
                    onFlush(pending.toString())
                    pending.setLength(0)
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

    /**
     * 停止批量发布循环，并等待它把本地积压（`pending`）刷出去。
     *
     * 顺序很关键：命令/任务结束时必须**先停循环、再写总结行**（退出码、已结束等）。
     * 循环持有最多 `minIntervalMs` 的未发布文本，若只在 finally 里自然退出，
     * 这段残留会落在总结行**之后**——日志读起来就是「退出码打在了最后几行输出前面」。
     */
    suspend fun stopBatchFlushLoop() {
        batchFlushJob?.cancelAndJoin()
        batchFlushJob = null
    }

    /**
     * 追加日志并按滑动窗口裁剪：超过 `MAX_LOG_LENGTH` 时保留尾部 `PRUNE_TARGET_LENGTH`，
     * 裁剪点对齐到换行（找不到换行则按长度硬截），避免半行 ANSI 序列残留。
     */
    fun appendWithSlidingWindow(currentLog: String, newText: String): String {
        val updated = currentLog + newText
        if (updated.length <= MAX_LOG_LENGTH) return updated
        val cutIndex = updated.indexOf('\n', updated.length - PRUNE_TARGET_LENGTH)
        if (cutIndex != -1 && cutIndex < updated.length) {
            // 对齐换行裁剪：新起点必然是一行的开头，不会切到转义序列或代理对
            return updated.substring(cutIndex + 1)
        }
        // 找不到换行（整段没有换行的超大输出）只能硬截：避免从代理对中间切开，
        // 否则头部会出现半个字符（渲染成替换符）。
        var hardCut = updated.length - PRUNE_TARGET_LENGTH
        if (hardCut > 0 && updated[hardCut].isLowSurrogate()) hardCut--
        return updated.substring(hardCut)
    }
}
