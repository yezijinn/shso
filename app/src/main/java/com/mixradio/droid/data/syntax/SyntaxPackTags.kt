// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.data.syntax

import android.content.Context

/**
 * 语言标识（供 UI 展示）。
 *
 * 数据来源**只有一处**：已导入语法包的匹配表（[SyntaxPackStore.keyOverridesCached]），
 * 因此「能否高亮」「列表上的语言标签」「编辑器顶栏的语言名」三者永远一致——
 * 不会出现「文件标着 Rust 却没有着色」或「能着色却标成 TXT」。
 * 未导入对应语法包时返回 null，调用方退回通用文案（`TXT`）。
 */
object SyntaxPackTags {

    /** 文件列表用的短标签（尽量 ≤6 字符，避免挤压行内空间）。 */
    fun tagFor(context: Context, fileName: String?): String? {
        val id = SyntaxPackStore.languageIdFor(context, fileName) ?: return null
        return SHORT[id] ?: id.take(7).uppercase()
    }

    /** 编辑器顶栏用的语言名（可读全称，保持官方大小写）。 */
    fun displayNameFor(context: Context, fileName: String?): String? {
        val id = SyntaxPackStore.languageIdFor(context, fileName) ?: return null
        return DISPLAY[id] ?: id.replaceFirstChar { it.uppercaseChar() }
    }

    private val SHORT = mapOf(
        "cpp" to "C++", "c" to "C", "csharp" to "C#", "objectivec" to "OBJC",
        "java" to "JAVA", "kotlin" to "KT", "scala" to "SCALA", "groovy" to "GROOVY",
        "python" to "PY", "ruby" to "RB", "perl" to "PL", "php" to "PHP",
        "javascript" to "JS", "typescript" to "TS", "dart" to "DART", "vb" to "VB",
        "rust" to "RS", "go" to "GO", "swift" to "SWIFT", "zig" to "ZIG", "nim" to "NIM",
        "shell" to "SH", "batch" to "BAT", "powershell" to "PS1", "lua" to "LUA",
        "json" to "JSON", "yaml" to "YAML", "toml" to "TOML", "ini" to "INI",
        "xml" to "XML", "html" to "HTML", "css" to "CSS", "scss" to "SCSS",
        "markdown" to "MD", "sql" to "SQL", "r" to "R", "asm" to "ASM",
        "dockerfile" to "DOCKER", "makefile" to "MAKE", "cmake" to "CMAKE", "meson" to "MESON",
        "gitconfig" to "GIT", "terraform" to "TF", "nginx" to "NGINX", "systemd" to "UNIT",
        "protobuf" to "PROTO", "glsl" to "GLSL", "hlsl" to "HLSL", "latex" to "TEX",
        "haskell" to "HS", "erlang" to "ERL", "elixir" to "EX", "julia" to "JL",
        "matlab" to "MATLAB", "sas" to "SAS", "solidity" to "SOL", "regex" to "RE",
        "log" to "LOG", "hosts" to "HOSTS", "crontab" to "CRON", "diff" to "DIFF",
        "csv" to "CSV"
    )

    private val DISPLAY = mapOf(
        "cpp" to "C++", "csharp" to "C#", "objectivec" to "Objective-C", "vb" to "Visual Basic",
        "javascript" to "JavaScript", "typescript" to "TypeScript", "powershell" to "PowerShell",
        "python" to "Python", "ruby" to "Ruby", "perl" to "Perl", "php" to "PHP",
        "rust" to "Rust", "go" to "Go", "swift" to "Swift", "kotlin" to "Kotlin", "java" to "Java",
        "shell" to "Shell", "batch" to "Batch", "lua" to "Lua", "scala" to "Scala", "groovy" to "Groovy",
        "haskell" to "Haskell", "erlang" to "Erlang", "elixir" to "Elixir", "julia" to "Julia",
        "matlab" to "MATLAB", "sas" to "SAS", "solidity" to "Solidity", "nim" to "Nim", "zig" to "Zig",
        "dart" to "Dart", "json" to "JSON", "yaml" to "YAML", "toml" to "TOML", "ini" to "INI",
        "xml" to "XML", "html" to "HTML", "css" to "CSS", "scss" to "SCSS", "sql" to "SQL",
        "markdown" to "Markdown", "dockerfile" to "Dockerfile", "makefile" to "Makefile",
        "cmake" to "CMake", "meson" to "Meson", "gitconfig" to "Git Config", "terraform" to "Terraform",
        "nginx" to "Nginx", "systemd" to "systemd unit", "protobuf" to "Protobuf", "glsl" to "GLSL",
        "hlsl" to "HLSL", "latex" to "LaTeX", "asm" to "Assembly", "regex" to "Regex",
        "log" to "Log", "hosts" to "Hosts", "crontab" to "Crontab", "diff" to "Diff", "csv" to "CSV",
        "r" to "R"
    )
}
