// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

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

    // 与 [batchFlushEpoch] 同为跨线程字段：IO worker 写入、Main 线程在
    // [stopBatchFlushLoop] 读取。缺 @Volatile 时主线程可能读到旧值（上一条命令的已结束 Job）
    // 或 null，于是 `job !== owner` 判定成立 → **静默 return，循环根本没停** ——
    // 恰好是本字段的「归属令牌」设计要防的情况，在内存模型上却不成立。
    // 旧循环继续 drain 队列并 appendOutputDirect，与命令收尾的 flushBatchQueueImmediate
    // 争抢同一队列，输出顺序错乱。
    @Volatile
    private var batchFlushJob: Job? = null

    private const val MAX_LOG_LENGTH = 250_000
    private const val PRUNE_TARGET_LENGTH = 180_000

    /**
     * 启动横幅与任务头已移除：终端不再输出引擎信息、运行环境、内核与任务路径，
     * 也不再提供对应开关。环境与内核探测随之失去唯一消费者，一并删除。
     * 此处只保留日志批处理与滑窗所需的纯工具函数。
     */

    fun queueLogChunk(chunk: String) {
        logBatchQueue.offer(chunk)
    }

    /**
 * 批量发布代次。`clearBatchQueue()` 递增它，发布循环发现变化即丢弃自己已积累
 * 但尚未发布的 [StringBuilder] 积压 —— 否则「清屏」清掉的输出会在下一个 tick
 * 被回灌回屏幕。跨线程可见，故 @Volatile。
 */
@Volatile
private var batchFlushEpoch: Int = 0

    fun clearBatchQueue() {
        logBatchQueue.clear()
        batchFlushEpoch++
    }

    /**
     * 启动批量发布循环，返回**归属令牌**（本次循环的 Job）。
     *
     * 必须把令牌交给调用方：[stopBatchFlushLoop] 无条件 `cancelAndJoin()` 当前循环，
     * 而终端命令允许重叠（`RootService.terminalCommandGeneration` 就是为此存在）。
     * 命令 A 结束时调 stop 会把属于命令 B 的循环一起取消掉 —— B 全程无实时输出，
     * 日志只在队列里堆积、结束后一次性喷出，用户观感是「终端卡住不刷新」。
     */
    fun startBatchFlushLoop(
        scope: CoroutineScope,
        isTaskRunningProvider: () -> Boolean,
        onFlush: (String) -> Unit
    ): Job {
        batchFlushJob?.cancel()
        val job = scope.launch(Dispatchers.Main) {
            // 保留 16ms tick 去 drain 队列（防止队列无界增长），但先累积到 pending，
            // 满足阈值（距上次发布 ≥ minIntervalMs，或累积 ≥ backlogChars）才发布一次，降低重组频率。
            val pending = StringBuilder()
            var lastFlushMs = System.currentTimeMillis()
            // 清屏代次：clearBatchQueue() 只清共享队列，清不掉本循环**局部**的 pending
            // （最多 250ms / 400k 字符）。用户点「清屏」后 outputLog 已清空，
            // 而下一个 ≤16ms 的 tick 就把这批刚被清掉的旧输出整段 flush 回去，
            // 屏幕上闪回一批旧内容且新旧无法区分。
            // 故用代次把「清屏」传导进循环：代次一变即丢弃本循环的积压。
            var seenEpoch = batchFlushEpoch
            // 发布节流：每次发布都有固定主线程开销（组合 + 可见行布局 + 重绘失效），
            // 成本与发布次数成正比、与 item 数无关。250ms ≈ 4 次/秒，
            // 洪流输出下可显著降低占用，刷新延迟几乎无感。
            // backlogChars 是内存安全阀，防止极端积压时 pending 无界增长。
            val minIntervalMs = 250L
            val backlogChars = 400_000
            try {
                while (isActive && isTaskRunningProvider()) {
                    delay(16.milliseconds)
                    if (batchFlushEpoch != seenEpoch) {
                        // 「清屏」发生：丢弃本循环已积累但尚未发布的旧输出。
                        pending.setLength(0)
                        seenEpoch = batchFlushEpoch
                        lastFlushMs = System.currentTimeMillis()
                        continue
                    }
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
                // 循环退出前把残留 pending 文本 flush 一次，避免丢日志。
                // 但必须与循环体用**同一把**代次尺子：清屏只递增 epoch、清不掉本循环的
                // 局部 pending，用户点「清屏」后若循环恰好在此刻退出，无守卫的 finally
                // 会把最多 250ms / 400k 字符的清屏前内容整段回灌 —— 屏幕闪回一批旧输出
                // 且与新内容无法区分，与循环体 L124 注释描述的是同一类故障，只是漏了这条路径。
                if (pending.isNotEmpty() && batchFlushEpoch == seenEpoch) {
                    onFlush(pending.toString())
                    pending.setLength(0)
                }
            }
        }
        batchFlushJob = job
        return job
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
    suspend fun stopBatchFlushLoop(owner: Job? = null) {
        val job = batchFlushJob
        // 归属校验：只停属于自己的循环。命令可重叠，旧命令收尾时若无条件停，
        // 会把新命令仍在跑的发布循环一起取消（其输出退化为结束后一次性喷出）。
        if (owner != null && job !== owner) return
        job?.cancelAndJoin()
        if (batchFlushJob === job) batchFlushJob = null
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
