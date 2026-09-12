// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mixradio.droid.ShsoApplication
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文本编辑器编辑历史记录管理器。
 *
 * **存储布局**：每个文件一个 SharedPreferences key（`history:<绝对路径>`），值为该文件的 JSON 数组。
 *  - 每文件最多 20 条、单条上限 20 万字、单文件总体积上限 100 万字
 *  - 每条记录：content + timestamp，按时间戳降序（最新在前）
 *
 * 按文件分 key 存储：若所有文件共用单个 key，每次读写都要解析并重写整份历史
 * （多个文件各 20 条 × 50 万字），开销随历史总量线性放大。
 * [ensureMigrated] 负责把旧的单 key 数据无损拆分。
 */
object EditHistoryManager {
    /** 旧版：所有文件共用一个大 JSON 数组。仅用于一次性迁移。 */
    private const val KEY_LEGACY = "edit_history"
    private const val KEY_PREFIX = "history:"
    private const val MAX_HISTORY_PER_FILE = 20

    /**
     * 单条历史上限 20 万字。
     * 超过此长度的内容取末尾保留（编辑器已允许编辑任意大小文件，故仍需截断保护）。
     * 取更小值可显著降低每次保存时的 JSON 序列化开销。
     */
    private const val MAX_CONTENT_CHARS = 200_000

    /**
     * 单文件历史**总体积**上限 100 万字。
     *
     * 存储载体是 SharedPreferences：应用首次访问该 prefs 时必须把整个 XML 全量解析进内存，
     * 且每次写入都要重新序列化该 key 的全部条目。若只按「条数 × 单条上限」约束，
     * 理论上限达 20 × 50 万字，会把 prefs 撑到数十 MB，直接拖慢启动并造成内存膨胀。
     * 超出预算时从最旧开始丢弃（最新一条永不丢）。
     */
    private const val MAX_TOTAL_CHARS_PER_FILE = 1_000_000

    private val prefs: SharedPreferences by lazy {
        ShsoApplication.appContext.getSharedPreferences("shso_editor", Context.MODE_PRIVATE)
    }

    data class HistoryEntry(
        val content: String,
        val timestamp: Long
    )

    /**
     * 获取指定文件的全部历史记录（按时间降序）。
     */
    fun getHistory(filePath: String): List<HistoryEntry> {
        ensureMigrated()
        return readFile(filePath)
    }

    /**
     * 添加一条历史记录（追加到最前）。
     *  - 内容超过 [MAX_CONTENT_CHARS] 时截断（取末尾）
     *  - 与最新一条相同则跳过（类 git：无变更不入库）
     *  - 文件历史超过 [MAX_HISTORY_PER_FILE] 时淘汰最旧条目
     */
    @Synchronized
    fun addHistory(filePath: String, content: String) {
        ensureMigrated()
        val safeContent = if (content.length > MAX_CONTENT_CHARS) {
            content.substring(content.length - MAX_CONTENT_CHARS)
        } else {
            content
        }

        val entries = readFile(filePath)
        if (entries.firstOrNull()?.content == safeContent) return

        // 内容级去重：相同内容的历史只保留最新一条；留 1 个位置给新条目
        val kept = entries.filter { it.content != safeContent }.take(MAX_HISTORY_PER_FILE - 1)
        writeFile(
            filePath,
            trimToBudget(listOf(HistoryEntry(safeContent, System.currentTimeMillis())) + kept)
        )
    }

    /**
     * 按总体积预算裁剪历史（输入需已按时间降序）。
     * 从最旧开始丢弃，最新一条无条件保留 —— 否则历史功能本身失去意义。
     */
    private fun trimToBudget(entries: List<HistoryEntry>): List<HistoryEntry> {
        if (entries.size <= 1) return entries
        if (entries.sumOf { it.content.length } <= MAX_TOTAL_CHARS_PER_FILE) return entries
        val kept = ArrayList<HistoryEntry>(entries.size)
        var total = 0
        entries.forEachIndexed { index, entry ->
            val next = total + entry.content.length
            if (index == 0 || next <= MAX_TOTAL_CHARS_PER_FILE) {
                kept += entry
                total = next
            }
        }
        return kept
    }

    /**
     * 清除指定文件的全部历史。
     */
    @Synchronized
    fun clearHistory(filePath: String) {
        ensureMigrated()
        prefs.edit { remove(keyFor(filePath)) }
    }

    private fun keyFor(filePath: String) = KEY_PREFIX + filePath

    private fun readFile(filePath: String): List<HistoryEntry> {
        val raw = prefs.getString(keyFor(filePath), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(obj.optString("content", ""), obj.optLong("timestamp", 0L))
            }.sortedByDescending { it.timestamp }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeFile(filePath: String, entries: List<HistoryEntry>) {
        prefs.edit { putString(keyFor(filePath), entriesToJson(entries).toString()) }
    }

    private fun entriesToJson(entries: List<HistoryEntry>): JSONArray {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("content", e.content)
                put("timestamp", e.timestamp)
            })
        }
        return arr
    }

    /**
     * 一次性迁移：把旧的单 key 大数组按 filePath 拆分到各自的 key，然后删除旧 key。
     * 幂等：迁移后 `KEY_LEGACY` 不存在，后续调用立即返回。
     */
    @Synchronized
    private fun ensureMigrated() {
        if (!prefs.contains(KEY_LEGACY)) return
        val legacy = prefs.getString(KEY_LEGACY, null)
        prefs.edit {
            if (!legacy.isNullOrBlank()) {
                try {
                    val arr = JSONArray(legacy)
                    val byFile = LinkedHashMap<String, MutableList<HistoryEntry>>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val path = obj.optString("filePath")
                        if (path.isEmpty()) continue
                        byFile.getOrPut(path) { mutableListOf() }
                            .add(HistoryEntry(obj.optString("content", ""), obj.optLong("timestamp", 0L)))
                    }
                    for ((path, list) in byFile) {
                        val trimmed = list.sortedByDescending { it.timestamp }.take(MAX_HISTORY_PER_FILE)
                        putString(keyFor(path), entriesToJson(trimmed).toString())
                    }
                } catch (_: Throwable) {
                    // 旧数据损坏：直接丢弃，不阻断使用
                }
            }
            remove(KEY_LEGACY)
        }
    }
}
