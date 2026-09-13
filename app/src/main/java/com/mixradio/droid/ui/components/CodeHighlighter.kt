// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

/**
 * 文件类型识别：顶栏「语言」标签与该文件的语法高亮选择共用同一扩展名。
 *
 * 注：着色由编辑器引擎（Sora + Monarch）负责，本对象只做扩展名判定 ——
 * 旧 Compose 渲染通道的关键字表与 AnnotatedString 着色实现已移除。
 */
object CodeHighlighter {

    /** 语言枚举（顶栏标签用）。 */
    enum class Language(val ext: String, val displayName: String) {
        PY("py", "Python"), JAVA("java", "Java"), KT("kt", "Kotlin"),
        C("c", "C"), CPP("cpp", "C++"), JS("js", "JavaScript"),
        SH("sh", "Shell"), BASH("bash", "Bash"), BAT("bat", "Batch"),
        CMD("cmd", "CMD"), PS1("ps1", "PowerShell"), SQL("sql", "SQL"),
        LUA("lua", "Lua")
    }

    /** 给定文件名推断语言（无扩展名或不在支持列表返回 null）。 */
    fun languageOf(fileName: String): Language? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return Language.entries.firstOrNull { it.ext == ext }
    }
}
