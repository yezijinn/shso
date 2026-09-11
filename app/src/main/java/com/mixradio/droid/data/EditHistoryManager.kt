// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import android.content.Context
import android.content.SharedPreferences
import com.mixradio.droid.ShsoApplication
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文本编辑器编辑历史记录管理器。
 *
 * **存储布局**：每个文件一个 SharedPreferences key（`history:<绝对路径>`），值为该文件的 JSON 数组。
 *  - 每文件最多 20 条历史记录，单条 content 上限 50 万字
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
    private const val MAX_CONTENT_CHARS = 500_000  // 单条历史上限 50 万字

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
        writeFile(filePath, listOf(HistoryEntry(safeContent, System.currentTimeMillis())) + kept)
    }

    /**
     * 清除指定文件的全部历史。
     */
    @Synchronized
    fun clearHistory(filePath: String) {
        ensureMigrated()
        prefs.edit().remove(keyFor(filePath)).apply()
    }

    private fun keyFor(filePath: String) = KEY_PREFIX + filePath

    private fun readFile(filePath: String): List<HistoryEntry> {
        val raw = prefs.getString(keyFor(filePath), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(o.optString("content", ""), o.optLong("timestamp", 0L))
            }.sortedByDescending { it.timestamp }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeFile(filePath: String, entries: List<HistoryEntry>) {
        prefs.edit().putString(keyFor(filePath), entriesToJson(entries).toString()).apply()
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
        val edit = prefs.edit()
        val legacy = prefs.getString(KEY_LEGACY, null)
        if (!legacy.isNullOrBlank()) {
            try {
                val arr = JSONArray(legacy)
                val byFile = LinkedHashMap<String, MutableList<HistoryEntry>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("filePath")
                    if (path.isEmpty()) continue
                    byFile.getOrPut(path) { mutableListOf() }
                        .add(HistoryEntry(o.optString("content", ""), o.optLong("timestamp", 0L)))
                }
                for ((path, list) in byFile) {
                    val trimmed = list.sortedByDescending { it.timestamp }.take(MAX_HISTORY_PER_FILE)
                    edit.putString(keyFor(path), entriesToJson(trimmed).toString())
                }
            } catch (_: Throwable) {
                // 旧数据损坏：直接丢弃，不阻断使用
            }
        }
        edit.remove(KEY_LEGACY).apply()
    }
}
