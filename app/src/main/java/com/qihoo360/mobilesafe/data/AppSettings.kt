// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
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

    var appThemeOption by mutableIntStateOf(prefs.getInt(KEY_APP_THEME, THEME_MATERIAL))
        private set

    var enableFloatingDock by mutableStateOf(prefs.getBoolean(KEY_FLOATING_DOCK, false))
        private set

    var useCustomFont by mutableStateOf(prefs.getBoolean(KEY_CUSTOM_FONT_ENABLED, true))
        private set

    var customFontPath by mutableStateOf(prefs.getString(KEY_CUSTOM_FONT_PATH, "") ?: "")
        private set

    var customFontName by mutableStateOf(prefs.getString(KEY_CUSTOM_FONT_NAME, "") ?: "")
        private set

    var showHyperCoreBanner by mutableStateOf(prefs.getBoolean(KEY_SHOW_HYPERCORE_BANNER, true))
        private set

    var showKernelEXBanner by mutableStateOf(prefs.getBoolean(KEY_SHOW_KERNELEX_BANNER, true))
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

    fun setAppTheme(theme: Int) {
        appThemeOption = theme
        prefs.edit().putInt(KEY_APP_THEME, theme).apply()
    }

    fun setFloatingDock(enable: Boolean) {
        enableFloatingDock = enable
        prefs.edit().putBoolean(KEY_FLOATING_DOCK, enable).apply()
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

    fun setKernelEXBanner(enable: Boolean) {
        showKernelEXBanner = enable
        prefs.edit().putBoolean(KEY_SHOW_KERNELEX_BANNER, enable).apply()
    }

    companion object {
        const val THEME_MATERIAL = 0
        const val THEME_MIUIX = 1

        private const val PREF_NAME = "KernelEX_Settings"
        private const val KEY_USE_INDEPENDENT_FOLDER = "use_independent_folder"
        private const val KEY_AUTO_DELETE_AFTER_ADDING = "auto_delete_after_adding"
        private const val KEY_AUTO_EXECUTE_AFTER_ADDING = "auto_execute_after_adding"
        private const val KEY_TERMINAL_TEXT_COLOR = "terminal_text_color"
        private const val KEY_DARK_MODE = "dark_mode_option"
        private const val KEY_APP_THEME = "app_theme_option"
        private const val KEY_FLOATING_DOCK = "floating_dock"
        private const val KEY_CUSTOM_FONT_ENABLED = "custom_font_enabled"
        private const val KEY_CUSTOM_FONT_PATH = "custom_font_path"
        private const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
        private const val KEY_SHOW_HYPERCORE_BANNER = "show_hypercore_banner"
        private const val KEY_SHOW_KERNELEX_BANNER = "show_kernelex_banner"

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
