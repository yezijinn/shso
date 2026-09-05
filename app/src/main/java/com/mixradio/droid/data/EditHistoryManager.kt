// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data

import android.content.Context
import android.content.SharedPreferences
import com.mixradio.droid.ShsoApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文本编辑器编辑历史记录管理器。
 *  - 存储在 SharedPreferences（KEY `edit_history`，JSON 数组）
 *  - 每文件最多 20 条历史记录
 *  - 每条记录：content + timestamp
 *  - 按时间戳降序排列（最新在前）
 */
object EditHistoryManager {
    private const val KEY_HISTORY = "edit_history"
    private const val MAX_HISTORY_PER_FILE = 20
    private const val MAX_CONTENT_CHARS = 500_000  // 单条历史上限 50 万字，防 SharedPreferences 膨胀

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
        val all = readAll()
        return all.filter { it.optString("filePath") == filePath }
            .map { entry ->
                HistoryEntry(
                    content = entry.optString("content", ""),
                    timestamp = entry.optLong("timestamp", 0L)
                )
            }
            .sortedByDescending { it.timestamp }
    }

    /**
     * 添加一条历史记录（追加到最前）。
     *  - 内容超过 MAX_CONTENT_CHARS 时截断（取末尾）
     *  - 文件历史超过 MAX_HISTORY_PER_FILE 时淘汰最旧条目
     */
    fun addHistory(filePath: String, content: String) {
        val safeContent = if (content.length > MAX_CONTENT_CHARS) {
            content.substring(content.length - MAX_CONTENT_CHARS)
        } else {
            content
        }

        val all = readAll()
        // 去重：与最新版本内容相同则跳过（类 git：无变更不入库）
        val latestForFile = all.firstOrNull { it.optString("filePath") == filePath }
        if (latestForFile != null && latestForFile.optString("content", "") == safeContent) return

        // 内容级去重：相同内容的历史只保留最新一条（用户要求）
        val thisFileEntries = all.filter { it.optString("filePath") == filePath }
            .filter { it.optString("content", "") != safeContent }  // 剔除内容相同的旧条目
            .sortedByDescending { it.optLong("timestamp", 0L) }
            .take(MAX_HISTORY_PER_FILE - 1)  // 留 1 个位置给新条目
            .toMutableList()
        val otherFileEntries = all.filter { it.optString("filePath") != filePath }

        // 新记录插到最前
        val newEntry = JSONObject().apply {
            put("filePath", filePath)
            put("content", safeContent)
            put("timestamp", System.currentTimeMillis())
        }
        thisFileEntries.add(0, newEntry)

        writeAll(otherFileEntries + thisFileEntries)
    }

    /**
     * 清除指定文件的全部历史。
     */
    fun clearHistory(filePath: String) {
        val all = readAll()
        val filtered = all.filter { it.optString("filePath") != filePath }
        writeAll(filtered)
    }

    /** 读取全部 JSON 数组 */
    private fun readAll(): List<JSONObject> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 写回全部 JSON 数组 */
    private fun writeAll(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}
