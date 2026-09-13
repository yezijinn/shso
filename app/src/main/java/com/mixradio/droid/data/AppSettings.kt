// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit

/**
 * 全部 var 都是 mutableStateOf（Compose 读取会自动订阅其变化），
 * 声明 @Stable 让 Compose 把整个实例视为「引用不变即未变」——父 Composable 因其它原因
 * 重组时，接收 AppSettings 的子 Composable 可正确跳过（否则保守认为不稳定而被迫重组）。
 * 类内字段读写都已通过 mutableStateOf 自动订阅，外层只需持有同一引用即可。
 */
@Stable
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var useIndependentFolder by mutableStateOf(prefs.getBoolean(KEY_USE_INDEPENDENT_FOLDER, false))
        private set

    var autoDeleteAfterAdding by mutableStateOf(prefs.getBoolean(KEY_AUTO_DELETE_AFTER_ADDING, false))
        private set

    var autoExecuteAfterAdding by mutableStateOf(prefs.getBoolean(KEY_AUTO_EXECUTE_AFTER_ADDING, false))
        private set

    var terminalTextColor by mutableLongStateOf(prefs.getLong(KEY_TERMINAL_TEXT_COLOR, DEFAULT_TERMINAL_COLOR))
        private set

    var darkModeOption by mutableIntStateOf(prefs.getInt(KEY_DARK_MODE, 0))
        private set

    var useCustomFont by mutableStateOf(prefs.getBoolean(KEY_CUSTOM_FONT_ENABLED, true))
        private set

    var customFontPath by mutableStateOf(prefs.getString(KEY_CUSTOM_FONT_PATH, "") ?: "")
        private set

    var customFontName by mutableStateOf(prefs.getString(KEY_CUSTOM_FONT_NAME, "") ?: "")
        private set

    var showHyperCoreBanner by mutableStateOf(prefs.getBoolean(KEY_SHOW_HYPERCORE_BANNER, true))
        private set

    var showShsoBanner by mutableStateOf(prefs.getBoolean(KEY_SHOW_SHSO_BANNER, true))
        private set

    var fileListFontSize by mutableFloatStateOf(prefs.getFloat(KEY_FILE_LIST_FONT_SIZE, DEFAULT_FILE_LIST_FONT_SIZE))
        private set

    var terminalFontSize by mutableFloatStateOf(prefs.getFloat(KEY_TERMINAL_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE))
        private set

    var showHiddenFiles by mutableStateOf(prefs.getBoolean(KEY_SHOW_HIDDEN_FILES, false))
        private set

    var fileSortMode by mutableIntStateOf(prefs.getInt(KEY_FILE_SORT_MODE, FILE_SORT_NAME_ASC))
        private set

    var rememberDirectory by mutableStateOf(prefs.getBoolean(KEY_REMEMBER_DIRECTORY, true))
        private set

    /** 编辑器自动保存草稿间隔（秒；0 = 关闭自动保存） */
    var editorAutoSaveInterval by mutableIntStateOf(prefs.getInt(KEY_EDITOR_AUTOSAVE_INTERVAL, DEFAULT_EDITOR_AUTOSAVE_INTERVAL))
        private set

    /** 编辑器是否显示行号 */
    var editorShowLineNumber by mutableStateOf(prefs.getBoolean(KEY_EDITOR_SHOW_LINE_NUMBER, true))
        private set

    /** 编辑器字号（sp） */
    var editorFontSize by mutableFloatStateOf(prefs.getFloat(KEY_EDITOR_FONT_SIZE, DEFAULT_EDITOR_FONT_SIZE))
        private set

    /**
     * 安全防护档位：0 无防护 / 1 仅审计 / 2 标准防护（默认）/ 3 最强防护。
     * 见 data/security/SecurityLevels.kt 与 docs/指令审查与拦截方案.md。
     */
    var securityLevel by mutableIntStateOf(prefs.getInt(KEY_SECURITY_LEVEL, SECURITY_STANDARD))
        private set

    // 书签（永久存储，按添加顺序）
    private val bookmarkPaths: MutableSet<String> = LinkedHashSet(loadBookmarks())

    var bookmarks by mutableStateOf(bookmarkPaths.toList())
        private set

    init {
        seedDefaultBookmarks()
    }

    /**
     * 读取书签。
     *
     * 用「换行分隔字符串」而不是 `StringSet` 持久化：`SharedPreferences` 的 Set 无序，
     * 重启后顺序会漂移，与「按添加顺序」的语义不符。首次读取时从旧的 Set 键迁移。
     */
    private fun loadBookmarks(): List<String> {
        prefs.getString(KEY_BOOKMARKS_ORDERED, null)?.let { ordered ->
            return ordered.split('\n').filter { it.isNotEmpty() }
        }
        return (prefs.getStringSet(KEY_BOOKMARKS, emptySet()) ?: emptySet()).toList()
    }

    private fun persistBookmarks() {
        prefs.edit {
            putString(KEY_BOOKMARKS_ORDERED, bookmarkPaths.joinToString("\n"))
            remove(KEY_BOOKMARKS)
        }
    }

    /** 首次运行预置常用 ROOT 目录；用户删除后不再自动加回（由 seeded 标志保证只执行一次）。 */
    private fun seedDefaultBookmarks() {
        if (prefs.getBoolean(KEY_BOOKMARKS_SEEDED, false)) return
        bookmarkPaths.addAll(DEFAULT_BOOKMARKS)
        bookmarks = bookmarkPaths.toList()
        prefs.edit {
            putString(KEY_BOOKMARKS_ORDERED, bookmarkPaths.joinToString("\n"))
            remove(KEY_BOOKMARKS)
            putBoolean(KEY_BOOKMARKS_SEEDED, true)
        }
    }

    fun setIndependentFolder(enable: Boolean) {
        useIndependentFolder = enable
        prefs.edit { putBoolean(KEY_USE_INDEPENDENT_FOLDER, enable) }
    }

    fun setAutoDelete(enable: Boolean) {
        autoDeleteAfterAdding = enable
        prefs.edit { putBoolean(KEY_AUTO_DELETE_AFTER_ADDING, enable) }
    }

    fun setAutoExecute(enable: Boolean) {
        autoExecuteAfterAdding = enable
        prefs.edit { putBoolean(KEY_AUTO_EXECUTE_AFTER_ADDING, enable) }
    }

    fun setTerminalColor(color: Color) {
        val argb = color.toArgb().toLong() and 0xFFFFFFFFL
        terminalTextColor = argb
        prefs.edit { putLong(KEY_TERMINAL_TEXT_COLOR, argb) }
    }

    fun setDarkMode(option: Int) {
        darkModeOption = option
        prefs.edit { putInt(KEY_DARK_MODE, option) }
    }

    fun setCustomFontEnabled(enable: Boolean) {
        useCustomFont = enable
        prefs.edit { putBoolean(KEY_CUSTOM_FONT_ENABLED, enable) }
    }

    fun setCustomFont(path: String, name: String) {
        customFontPath = path
        customFontName = name
        useCustomFont = true
        prefs.edit {
            putString(KEY_CUSTOM_FONT_PATH, path)
            putString(KEY_CUSTOM_FONT_NAME, name)
            putBoolean(KEY_CUSTOM_FONT_ENABLED, true)
        }
    }

    fun useBuiltInFont() {
        customFontPath = ""
        customFontName = "内置字体"
        useCustomFont = true
        prefs.edit {
            remove(KEY_CUSTOM_FONT_PATH)
            putString(KEY_CUSTOM_FONT_NAME, "内置字体")
            putBoolean(KEY_CUSTOM_FONT_ENABLED, true)
        }
    }

    fun resetCustomFont() {
        customFontPath = ""
        customFontName = ""
        useCustomFont = true
        prefs.edit {
            remove(KEY_CUSTOM_FONT_PATH)
            remove(KEY_CUSTOM_FONT_NAME)
            putBoolean(KEY_CUSTOM_FONT_ENABLED, true)
        }
    }

    fun setHyperCoreBanner(enable: Boolean) {
        showHyperCoreBanner = enable
        prefs.edit { putBoolean(KEY_SHOW_HYPERCORE_BANNER, enable) }
    }

    fun setShsoBanner(enable: Boolean) {
        showShsoBanner = enable
        prefs.edit { putBoolean(KEY_SHOW_SHSO_BANNER, enable) }
    }

    fun updateFileListFontSize(size: Float) {
        val clamped = size.coerceIn(MIN_FILE_LIST_FONT_SIZE, MAX_FILE_LIST_FONT_SIZE)
        fileListFontSize = clamped
        prefs.edit { putFloat(KEY_FILE_LIST_FONT_SIZE, clamped) }
    }

    fun updateTerminalFontSize(size: Float) {
        val clamped = size.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE)
        terminalFontSize = clamped
        prefs.edit { putFloat(KEY_TERMINAL_FONT_SIZE, clamped) }
    }

    fun updateRememberDirectory(enable: Boolean) {
        rememberDirectory = enable
        prefs.edit { putBoolean(KEY_REMEMBER_DIRECTORY, enable) }
    }

    fun updateEditorAutoSaveInterval(seconds: Int) {
        val clamped = seconds.coerceIn(0, 600)
        editorAutoSaveInterval = clamped
        prefs.edit { putInt(KEY_EDITOR_AUTOSAVE_INTERVAL, clamped) }
    }

    fun updateEditorShowLineNumber(show: Boolean) {
        editorShowLineNumber = show
        prefs.edit { putBoolean(KEY_EDITOR_SHOW_LINE_NUMBER, show) }
    }

    fun updateEditorFontSize(size: Float) {
        val clamped = size.coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
        editorFontSize = clamped
        prefs.edit { putFloat(KEY_EDITOR_FONT_SIZE, clamped) }
    }

    fun updateShowHiddenFiles(enable: Boolean) {
        showHiddenFiles = enable
        prefs.edit { putBoolean(KEY_SHOW_HIDDEN_FILES, enable) }
    }

    fun updateFileSortMode(mode: Int) {
        fileSortMode = mode
        prefs.edit { putInt(KEY_FILE_SORT_MODE, mode) }
    }

    fun updateSecurityLevel(level: Int) {
        val clamped = level.coerceIn(SECURITY_OFF, SECURITY_MAXIMUM)
        securityLevel = clamped
        prefs.edit { putInt(KEY_SECURITY_LEVEL, clamped) }
    }

    fun addBookmark(path: String) {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        bookmarkPaths.add(normalized)
        bookmarks = bookmarkPaths.toList()
        persistBookmarks()
    }

    fun removeBookmark(path: String) {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        bookmarkPaths.remove(normalized)
        bookmarkPaths.remove(path.trim()) // 兼容旧数据或未归一化路径
        bookmarks = bookmarkPaths.toList()
        persistBookmarks()
    }

    fun isBookmarked(path: String): Boolean {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        return bookmarks.contains(normalized) || bookmarks.contains(path.trim())
    }

    companion object {
        const val FILE_SORT_NAME_ASC = 0
        const val FILE_SORT_NAME_DESC = 1
        const val FILE_SORT_TIME_ASC = 2
        const val FILE_SORT_TIME_DESC = 3

        /** 安全档位常量（与 data/security/SecurityLevels 对齐） */
        const val SECURITY_OFF = 0
        const val SECURITY_AUDIT_ONLY = 1
        const val SECURITY_STANDARD = 2
        const val SECURITY_MAXIMUM = 3

        private const val MIN_FILE_LIST_FONT_SIZE = 5f
        private const val MAX_FILE_LIST_FONT_SIZE = 30f
        private const val DEFAULT_FILE_LIST_FONT_SIZE = 15f

        private const val MIN_TERMINAL_FONT_SIZE = 5f
        private const val MAX_TERMINAL_FONT_SIZE = 30f
        private const val DEFAULT_TERMINAL_FONT_SIZE = 12f
        private const val MIN_EDITOR_FONT_SIZE = 8f
        private const val MAX_EDITOR_FONT_SIZE = 32f
        private const val DEFAULT_EDITOR_FONT_SIZE = 14f
        private const val DEFAULT_EDITOR_AUTOSAVE_INTERVAL = 0

        private const val PREF_NAME = "shso_settings"
        private const val KEY_USE_INDEPENDENT_FOLDER = "use_independent_folder"
        private const val KEY_AUTO_DELETE_AFTER_ADDING = "auto_delete_after_adding"
        private const val KEY_AUTO_EXECUTE_AFTER_ADDING = "auto_execute_after_adding"
        private const val KEY_TERMINAL_TEXT_COLOR = "terminal_text_color"
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
        private const val KEY_DARK_MODE = "dark_mode_option"
        private const val KEY_FILE_LIST_FONT_SIZE = "file_list_font_size"
        private const val KEY_SHOW_HIDDEN_FILES = "show_hidden_files"
        private const val KEY_FILE_SORT_MODE = "file_sort_mode"
        private const val KEY_CUSTOM_FONT_ENABLED = "custom_font_enabled"
        private const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
        private const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
        private const val KEY_SHOW_HYPERCORE_BANNER = "show_hypercore_banner"
        private const val KEY_SHOW_SHSO_BANNER = "show_shso_banner"
        private const val KEY_REMEMBER_DIRECTORY = "remember_directory"
        /** 旧键（无序 StringSet）：仅用于首次迁移，迁移后删除。 */
        private const val KEY_BOOKMARKS = "bookmarks"

        /** 书签（有序，换行分隔）。 */
        private const val KEY_BOOKMARKS_ORDERED = "bookmarks_ordered"

        /** 预置书签是否已写入（保证只执行一次，用户删除后不再自动加回）。 */
        private const val KEY_BOOKMARKS_SEEDED = "bookmarks_seeded"

        /** 首次运行预置的常用 ROOT 目录。 */
        private val DEFAULT_BOOKMARKS = listOf("/data/adb", "/data/adb/modules")
        private const val KEY_EDITOR_AUTOSAVE_INTERVAL = "editor_autosave_interval"
        private const val KEY_EDITOR_SHOW_LINE_NUMBER = "editor_show_line_number"
        private const val KEY_EDITOR_FONT_SIZE = "editor_font_size"
        private const val KEY_SECURITY_LEVEL = "security_level"

        private const val DEFAULT_TERMINAL_COLOR = 0xFF00E676L

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }
}
