// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

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

    var showHiddenFiles by mutableStateOf(prefs.getBoolean(KEY_SHOW_HIDDEN_FILES, false))
        private set

    var fileSortMode by mutableIntStateOf(prefs.getInt(KEY_FILE_SORT_MODE, FILE_SORT_NAME_ASC))
        private set

    fun setIndependentFolder(enable: Boolean) {
        useIndependentFolder = enable
        prefs.edit().putBoolean(KEY_USE_INDEPENDENT_FOLDER, enable).apply()
    }

    fun setAutoDelete(enable: Boolean) {
        autoDeleteAfterAdding = enable
        prefs.edit().putBoolean(KEY_AUTO_DELETE_AFTER_ADDING, enable).apply()
    }

    fun setAutoExecute(enable: Boolean) {
        autoExecuteAfterAdding = enable
        prefs.edit().putBoolean(KEY_AUTO_EXECUTE_AFTER_ADDING, enable).apply()
    }

    fun setTerminalColor(color: Color) {
        val argb = color.toArgb().toLong() and 0xFFFFFFFFL
        terminalTextColor = argb
        prefs.edit().putLong(KEY_TERMINAL_TEXT_COLOR, argb).apply()
    }

    fun setDarkMode(option: Int) {
        darkModeOption = option
        prefs.edit().putInt(KEY_DARK_MODE, option).apply()
    }

    fun setCustomFontEnabled(enable: Boolean) {
        useCustomFont = enable
        prefs.edit().putBoolean(KEY_CUSTOM_FONT_ENABLED, enable).apply()
    }

    fun setCustomFont(path: String, name: String) {
        customFontPath = path
        customFontName = name
        useCustomFont = true
        prefs.edit()
            .putString(KEY_CUSTOM_FONT_PATH, path)
            .putString(KEY_CUSTOM_FONT_NAME, name)
            .putBoolean(KEY_CUSTOM_FONT_ENABLED, true)
            .apply()
    }

    fun useBuiltInFont() {
        customFontPath = ""
        customFontName = "内置字体"
        useCustomFont = true
        prefs.edit()
            .remove(KEY_CUSTOM_FONT_PATH)
            .putString(KEY_CUSTOM_FONT_NAME, "内置字体")
            .putBoolean(KEY_CUSTOM_FONT_ENABLED, true)
            .apply()
    }

    fun resetCustomFont() {
        customFontPath = ""
        customFontName = ""
        useCustomFont = true
        prefs.edit()
            .remove(KEY_CUSTOM_FONT_PATH)
            .remove(KEY_CUSTOM_FONT_NAME)
            .putBoolean(KEY_CUSTOM_FONT_ENABLED, true)
            .apply()
    }

    fun setHyperCoreBanner(enable: Boolean) {
        showHyperCoreBanner = enable
        prefs.edit().putBoolean(KEY_SHOW_HYPERCORE_BANNER, enable).apply()
    }

    fun setShsoBanner(enable: Boolean) {
        showShsoBanner = enable
        prefs.edit().putBoolean(KEY_SHOW_SHSO_BANNER, enable).apply()
    }

    fun updateFileListFontSize(size: Float) {
        val clamped = size.coerceIn(MIN_FILE_LIST_FONT_SIZE, MAX_FILE_LIST_FONT_SIZE)
        fileListFontSize = clamped
        prefs.edit().putFloat(KEY_FILE_LIST_FONT_SIZE, clamped).apply()
    }

    fun updateShowHiddenFiles(enable: Boolean) {
        showHiddenFiles = enable
        prefs.edit().putBoolean(KEY_SHOW_HIDDEN_FILES, enable).apply()
    }

    fun updateFileSortMode(mode: Int) {
        fileSortMode = mode
        prefs.edit().putInt(KEY_FILE_SORT_MODE, mode).apply()
    }

    companion object {
        const val FILE_SORT_NAME_ASC = 0
        const val FILE_SORT_NAME_DESC = 1
        const val FILE_SORT_TIME_ASC = 2
        const val FILE_SORT_TIME_DESC = 3

        private const val MIN_FILE_LIST_FONT_SIZE = 12f
        private const val MAX_FILE_LIST_FONT_SIZE = 20f
        private const val DEFAULT_FILE_LIST_FONT_SIZE = 15f

        private const val PREF_NAME = "shso_settings"
        private const val KEY_USE_INDEPENDENT_FOLDER = "use_independent_folder"
        private const val KEY_AUTO_DELETE_AFTER_ADDING = "auto_delete_after_adding"
        private const val KEY_AUTO_EXECUTE_AFTER_ADDING = "auto_execute_after_adding"
        private const val KEY_TERMINAL_TEXT_COLOR = "terminal_text_color"
        private const val KEY_DARK_MODE = "dark_mode_option"
        private const val KEY_FILE_LIST_FONT_SIZE = "file_list_font_size"
        private const val KEY_SHOW_HIDDEN_FILES = "show_hidden_files"
        private const val KEY_FILE_SORT_MODE = "file_sort_mode"
        private const val KEY_CUSTOM_FONT_ENABLED = "custom_font_enabled"
        private const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
        private const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
        private const val KEY_SHOW_HYPERCORE_BANNER = "show_hypercore_banner"
        private const val KEY_SHOW_SHSO_BANNER = "show_shso_banner"

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
