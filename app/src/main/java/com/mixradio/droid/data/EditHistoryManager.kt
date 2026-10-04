// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
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
/**
 * 历史条目合并规则（纯函数，便于 JVM 单测）。
 *
 * 语义与旧实现保持一致，仅增加「来源升级」：
 *  - 最新条目内容相同：来源更强则原地升级（不新增条目），否则无需变更；
 *  - 内容不同：丢弃同内容的旧条目后插到最前，条数上限由调用方的 [EditHistoryManager.trimToBudget] 收口。
 */
internal object HistoryMerge {

    /** 返回合并后的完整列表；返回 null 表示**无需写入**（内容重复且来源不更强）。 */
    fun apply(
        existing: List<EditHistoryManager.HistoryEntry>,
        newEntry: EditHistoryManager.HistoryEntry
    ): List<EditHistoryManager.HistoryEntry>? {
        val newest = existing.firstOrNull()
        if (newest != null && newest.content == newEntry.content) {
            if (newest.source.priority >= newEntry.source.priority) return null
            // 来源升级：保留原时间（该内容首次出现的时刻），只换来源标记
            return listOf(newest.copy(source = newEntry.source)) + existing.drop(1)
        }
        // 全量按内容去重：**每个内容只保留最新一条**（[distinctBy] 保留首次出现 = 最新的那条）。
        // 旧实现只过滤掉「等于新内容」的条目，其它内容的重复项会持续累积
        // （A→B→A→B 交替编辑会把 20 个槽位塞满两份内容，其它版本被挤掉）。
        val kept = existing.asSequence()
            .filter { it.content != newEntry.content }
            .distinctBy { it.content }
            .take(EditHistoryManager.maxHistoryPerFile() - 1)
            .toList()
        return listOf(newEntry) + kept
    }
}

object EditHistoryManager {
    /** 旧版：所有文件共用一个大 JSON 数组。仅用于一次性迁移。 */
    private const val KEY_LEGACY = "edit_history"

/** 迁移失败时保留 legacy 原文的键名（改名而非删除，便于事后人工恢复）。 */
private const val KEY_LEGACY_UNMIGRATED = "edit_history.unmigrated"
    private const val KEY_PREFIX = "history:"
    private const val MAX_HISTORY_PER_FILE = 20

    /** 供 [HistoryMerge] 读取单文件条数上限（常量保持私有，避免外部误用其它数值）。 */
    internal fun maxHistoryPerFile(): Int = MAX_HISTORY_PER_FILE

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

    /**
     * 历史条目的来源。用途：历史面板里区分「我手动保存过」与「系统自动记的快照」，
     * 恢复前的判断也更明确（手动保存点通常才是用户认为的"已确认版本"）。
     *
     * [priority] 用于**相同内容**合并时的取舍：手动保存 > 停顿快照 > 定时草稿，
     * 即同一份内容先被自动记过、之后被手动保存，条目应升级为「手动保存」而不是再插一条。
     */
    enum class HistorySource(val label: String) {
        DRAFT("定时草稿"),
        AUTO("停顿快照"),
        SAVE("手动保存");

        val priority: Int get() = ordinal
    }

    data class HistoryEntry(
        val content: String,
        val timestamp: Long,
        val source: HistorySource = HistorySource.AUTO,
        /**
         * 内容是否因超限被截断。为 true 时 [content] 只是**尾部片段**，
         * 恢复它会丢掉文件开头，见 [addHistory]。
         */
        val truncated: Boolean = false
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
     *  - 内容超过 [MAX_CONTENT_CHARS] 时截断（取末尾）并置 [HistoryEntry.truncated]
     *  - 与最新一条相同则跳过（类 git：无变更不入库）
     *  - 文件历史超过 [MAX_HISTORY_PER_FILE] 时淘汰最旧条目
     *
     * 截断取「末尾」是为了让 SharedPreferences 单条体积可控，但代价是**恢复时丢掉文件开头**：
     * 编辑器对 `entry.content` 无条件 `setEditorContent(..., markDirty = true)`，
     * 用户点一次恢复、随手一存，前 (N - 200000) 字就被覆盖消失且无任何提示。
     * 故此处必须打标，由 UI 决定是否允许恢复。
     */
    @Synchronized
    fun addHistory(filePath: String, content: String, source: HistorySource = HistorySource.AUTO) {
        ensureMigrated()
        val truncated = content.length > MAX_CONTENT_CHARS
        val safeContent = if (truncated) content.substring(content.length - MAX_CONTENT_CHARS) else content

        // 读取失败时**直接放弃本次写入**，绝不能拿「空列表」去覆写。
        // 此前 readFile 把解析异常（含 OOM）降级成 emptyList()，这里随即
        // HistoryMerge.apply(emptyList(), new) → writeFile(单条) 覆写同一 key：
        // 一次 OOM 就把该文件 20 条历史全部抹掉，且不可恢复。
        val entries = readFileOrNull(filePath) ?: return
        val merged = HistoryMerge.apply(
            entries, HistoryEntry(safeContent, System.currentTimeMillis(), source, truncated)
        ) ?: return
        writeFile(filePath, trimToBudget(merged))
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

    /**
     * 读取某文件的历史；**解析失败返回 null**，与「本来就没有历史（空列表）」区分开。
     *
     * 调用方必须区分两者：把失败当成空列表就等于「用一条新记录覆盖掉全部旧历史」。
     * 失败时同时把损坏原文另存一份，供事后人工恢复。
     */
    private fun readFileOrNull(filePath: String): List<HistoryEntry>? {
        val key = keyFor(filePath)
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    obj.optString("content", ""),
                    obj.optLong("timestamp", 0L),
                    // 旧数据无 source 字段：按「停顿快照」处理（历史默认来源）
                    runCatching { HistorySource.valueOf(obj.optString("source", HistorySource.AUTO.name)) }
                        .getOrDefault(HistorySource.AUTO),
                    obj.optBoolean("truncated", false)
                )
            }.sortedByDescending { it.timestamp }
        } catch (_: Throwable) {
            // 备份损坏原文再放弃本次写入：直接覆盖等于把用户的编辑历史清零。
            //
            // 备份键**必须固定**（"$key.corrupt"）且只在不存在时写：
            // 此前用带时间戳的新键（"$key.corrupt.<millis>"），而主键仍是那份损坏数据，
            // 于是此后每次停顿快照（2.5s 后）/ 草稿快照再失败一次就**再写一份完整原文**
            // （单键上限 100 万字符，最坏 ~3MB），20 次即 60MB 灌进 shso_editor.xml，
            // 且永不清理 —— 每次启动都要全量解析该 XML，越大越慢、越慢越易失败，正反馈。
            runCatching {
                if (!prefs.contains("$key.corrupt")) {
                    prefs.edit {
                        putString("$key.corrupt", raw)
                        remove(key)
                    }
                }
            }
            null
        }
    }

    private fun readFile(filePath: String): List<HistoryEntry> = readFileOrNull(filePath).orEmpty()

    private fun writeFile(filePath: String, entries: List<HistoryEntry>) {
        prefs.edit { putString(keyFor(filePath), entriesToJson(entries).toString()) }
    }

    private fun entriesToJson(entries: List<HistoryEntry>): JSONArray {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("content", e.content)
                put("timestamp", e.timestamp)
                put("source", e.source.name)
                // 旧记录没有该字段，optBoolean 默认 false，天然按「未截断」处理
                put("truncated", e.truncated)
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
        if (legacy.isNullOrBlank()) {
            prefs.edit { remove(KEY_LEGACY) }
            return
        }
        // 先在**事务外**解析并构造结果：解析失败时 legacy 绝不能被删。
        // 此前 remove(KEY_LEGACY) 写在 catch 之外的同一个 edit 块里，
        // 于是「解析抛异常 → 注释说直接丢弃 → 实际照删」，用户升级后全部旧历史当场蒸发。
        val byFile = LinkedHashMap<String, MutableList<HistoryEntry>>()
        try {
            val arr = JSONArray(legacy)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("filePath")
                if (path.isEmpty()) continue
                val raw = obj.optString("content", "")
                // 单条也套内容预算：旧版无此上限，20 条 × 数十万字会落成数 MB 的单 key，
                // 下一次 addHistory 解析它时极大推高 OOM 概率（进而触发上面的清空路径）。
                val over = raw.length > MAX_CONTENT_CHARS
                byFile.getOrPut(path) { mutableListOf() }
                    .add(
                        HistoryEntry(
                            if (over) raw.substring(raw.length - MAX_CONTENT_CHARS) else raw,
                            obj.optLong("timestamp", 0L),
                            runCatching { HistorySource.valueOf(obj.optString("source", HistorySource.AUTO.name)) }
                                .getOrDefault(HistorySource.AUTO),
                            over
                        )
                    )
            }
        } catch (_: Throwable) {
            // 迁移失败：把 legacy 原样改名为未迁移键，**保留数据**以便人工恢复。
            runCatching {
                prefs.edit {
                    putString(KEY_LEGACY_UNMIGRATED, legacy)
                    remove(KEY_LEGACY)
                }
            }
            return
        }
        prefs.edit {
            for ((path, list) in byFile) {
                // 迁移同样走体积预算，避免一次迁移写出超预算的单 key
                val trimmed = trimToBudget(list.sortedByDescending { it.timestamp }
                    .take(MAX_HISTORY_PER_FILE))
                putString(keyFor(path), entriesToJson(trimmed).toString())
            }
            remove(KEY_LEGACY)
        }
    }
}
