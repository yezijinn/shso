// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.mixradio.droid.data.FileItem
import kotlin.reflect.KProperty
import kotlinx.coroutines.Job

/**
 * 把局部属性读写**转发到聚焦列**的委托。
 *
 * 页面里有上百处 `currentDirectory` / `nameQuery` / `isLoading` 之类的读写，
 * 它们都该作用于聚焦的那一列。若逐处改成 `activePane.xxx`，diff 会淹没在
 * 机械替换里，反而看不出真正的逻辑改动；用委托则读写语义与重构前**逐字一致**，
 * 审阅时仍按「操作聚焦列」理解。
 *
 * 为什么不用 `remember { derivedStateOf { activePane.x } }`：
 * `remember` 只在**首次组合**执行，其计算 lambda 会永久捕获那一次的
 * `activePane`。切换聚焦列后别名仍指向旧列 —— 表现为「点了右列，
 * 路径栏和搜索却还在操作左列」。委托的 getter 每次调用现取 `activePane`，
 * 且 lambda 捕获的是变量本身而非值，因此始终指向当前聚焦列。
 *
 * get/set 都是真实的快照读写，组合期读它照样会被 Compose 正确追踪。
 */
class PaneProp<T>(
    private val reader: () -> T,
    private val writer: (T) -> Unit
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = reader()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = writer(value)
}

/**
 * 「文件」页单列的完整浏览状态。
 *
 * 双列布局把原先页面级的 15 个状态一分为二。这里刻意把「可观察状态」与
 * 「协程内记账」分开：
 *
 * - **可观察状态**（`mutableStateOf` / `mutableStateListOf`）：参与组合，
 *   变化会驱动界面更新。
 * - **记账用的引用**（`refreshGenRef` 等普通数组）：只在挂起协程内部读写，
 *   从不参与组合。沿用重构前 `remember { intArrayOf(...) }` 的写法 ——
 *   若改成 `mutableStateOf`，每次代次自增都会触发一次无意义的重组，
 *   而这些代次在组合期根本读不到。
 *
 * 这些守卫的正确性依赖「同一列内自洽」：右列的刷新绝不能被左列的代次挡住，
 * 反之亦然。因此它们必须每列各持一份，不能提到页面级共享。
 */
@Stable
class FilePaneState(
    initialDirectory: String,
    /** 本列的滚动位置。由 `rememberLazyListState()` 提供（构造本类时不可调用组合 API）。 */
    val listState: LazyListState
) {
    // ── 目录与列表 ────────────────────────────────────────────────

    var currentDirectory by mutableStateOf(initialDirectory)
    var fileList by mutableStateOf<List<FileItem>>(emptyList())
    var displayFileList by mutableStateOf<List<FileItem>>(emptyList())

    // ── 加载与作废态 ──────────────────────────────────────────────

    var isLoading by mutableStateOf(false)

    /**
     * 列表可见但属于上一目录，期间**禁止任何条目操作**。
     *
     * 刷新期间保留旧列表是为了消除空白窗口（骨架屏 / 「当前目录为空」误报），
     * 代价是那一瞬间列表内容与路径栏不一致 —— 由本标记兜住安全性。
     */
    var listIsStale by mutableStateOf(false)

    var directoryLoadError by mutableStateOf<String?>(null)
    var directoryLoadFailed by mutableStateOf(false)

    // ── 过滤与多选 ────────────────────────────────────────────────

    var showSearch by mutableStateOf(false)
    var nameQuery by mutableStateOf("")
    var multiSelectMode by mutableStateOf(false)
    val selectedPaths: SnapshotStateList<String> = mutableStateListOf()

    /**
     * 本列是否已因「静默空列表」自愈重试过一次。
     *
     * 仅用于冷启动异常：列举结束后既无内容也无错误原因时，
     * 往往是冷启动瞬间 ROOT 授权尚未落定。只允许一次，避免真空目录被无限重试。
     */
    var retriedForEmptyOnce by mutableStateOf(false)

    /** 外部唤起定位用；仅视觉高亮，用户点击或切目录后清空。 */
    var highlightPath by mutableStateOf<String?>(null)

    // ── 协程内记账（不可观察，见类注释）────────────────────────────

    /** 刷新代次。切目录 / 重复刷新时自增，用于丢弃过期的异步结果。 */
    val refreshGenRef = intArrayOf(0)

    /** 本代是否尚未落盘；-1 表示已收尾。依赖 fileList 的重算协程必须等它清除。 */
    val loadingGenRef = intArrayOf(-1)

    /** 本列正在进行的刷新作业，切目录时取消。 */
    val refreshJobRef = arrayOfNulls<Job>(1)

    /** 上次成功加载的目录与时刻，用于同目录连续刷新的合并窗口。 */
    val lastLoadedDirRef = arrayOfNulls<String>(1)
    val lastLoadedAtRef = longArrayOf(0L)

    /** 上次重算所依据的搜索词，用于判断本次重算是否由搜索词变化触发（决定要不要防抖）。 */
    val lastRecomputedQueryRef = arrayOfNulls<String>(1)
}
