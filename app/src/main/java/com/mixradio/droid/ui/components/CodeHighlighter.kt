// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * 极简代码语法高亮（不引外部库）：
 *  仅按"关键字 / 字符串 / 注释"三色着色，足够代码阅读辅助。
 *  支持语言：py java kt c cpp js sh bash bat cmd ps1 sql lua
 */
object CodeHighlighter {

    /** 关键字 + 注释风格按语言差异化 */
    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "py"  to setOf("def","class","import","from","as","if","elif","else","for","while","return","try","except","finally","with","in","is","not","and","or","pass","break","continue","lambda","global","nonlocal","yield","raise","None","True","False"),
        "java" to setOf("public","private","protected","static","final","class","interface","extends","implements","new","return","if","else","for","while","do","switch","case","break","continue","try","catch","finally","throw","throws","void","int","long","short","byte","double","float","char","boolean","this","super","null","true","false","package","import"),
        "kt"  to setOf("fun","val","var","class","object","interface","if","else","when","for","while","return","in","is","!is","as","try","catch","finally","throw","do","break","continue","package","import","true","false","null","this","super","private","public","internal","protected"),
        "c"   to setOf("if","else","for","while","do","switch","case","break","continue","return","int","long","short","char","float","double","void","signed","unsigned","const","static","extern","struct","union","enum","typedef","sizeof","goto"),
        "cpp" to setOf("if","else","for","while","do","switch","case","break","continue","return","int","long","short","char","float","double","void","bool","true","false","const","static","extern","class","struct","public","private","protected","virtual","override","new","delete","namespace","using","template","typename","sizeof"),
        "js"  to setOf("var","let","const","function","return","if","else","for","while","do","switch","case","break","continue","try","catch","finally","throw","new","class","extends","this","super","import","export","from","of","in","typeof","instanceof","true","false","null","undefined","async","await","yield"),
        "sh"  to setOf("if","then","else","elif","fi","case","esac","for","do","done","while","function","return","exit","echo","export","local","read","set","unset","shift","source","in"),
        "bash" to setOf("if","then","else","elif","fi","case","esac","for","do","done","while","function","return","exit","echo","export","local","read","set","unset","shift","source","in","select"),
        "bat" to setOf("if","else","for","in","do","call","goto","set","setlocal","endlocal","exit"),
        "cmd" to setOf("if","else","for","in","do","call","goto","set","exit"),
        "ps1" to setOf("function","param","if","else","elseif","for","foreach","while","do","until","switch","break","continue","return","try","catch","finally","throw","begin","process","end","exit","param"),
        "sql" to setOf("SELECT","FROM","WHERE","INSERT","INTO","VALUES","UPDATE","SET","DELETE","CREATE","TABLE","DROP","ALTER","ADD","COLUMN","INDEX","VIEW","JOIN","LEFT","RIGHT","INNER","OUTER","FULL","ON","AS","AND","OR","NOT","NULL","IS","LIKE","IN","BETWEEN","EXISTS","ORDER","BY","GROUP","HAVING","LIMIT","OFFSET","UNION","ALL","DISTINCT","CASE","WHEN","THEN","ELSE","END","BEGIN","COMMIT","ROLLBACK","TRANSACTION"),
        "lua" to setOf("and","break","do","else","elseif","end","false","for","function","goto","if","in","local","nil","not","or","repeat","return","then","true","until","while")
    )

    private val COMMENT_LINE: Map<String, Pair<String, String?>> = mapOf(
        "py" to ("#" to null),
        "sh" to ("#" to null),
        "bash" to ("#" to null),
        "lua" to ("--" to null),
        "sql" to ("--" to "/*"),          // /* */ 多行注释
        "java" to ("//" to "/*"),
        "kt" to ("//" to "/*"),
        "c" to ("//" to "/*"),
        "cpp" to ("//" to "/*"),
        "js" to ("//" to "/*"),
        "bat" to ("REM " to null),
        "cmd" to ("REM " to null),
        "ps1" to ("#" to "<#")           // <# #> 多行注释
    )

    private val STRING_DELIMS: Map<String, CharArray> = mapOf(
        "py" to charArrayOf('"','\''),
        "java" to charArrayOf('"','\''),
        "kt" to charArrayOf('"','\''),
        "c" to charArrayOf('"','\''),
        "cpp" to charArrayOf('"','\''),
        "js" to charArrayOf('"','\'','`'),
        "sh" to charArrayOf('"','\''),
        "bash" to charArrayOf('"','\''),
        "bat" to charArrayOf('"'),
        "cmd" to charArrayOf('"'),
        "ps1" to charArrayOf('"','\''),
        "sql" to charArrayOf('\'','"'),
        "lua" to charArrayOf('"','\'')
    )

    /** 语言枚举（用于顶栏显示与设置） */
    enum class Language(val ext: String, val displayName: String) {
        PY("py", "Python"), JAVA("java", "Java"), KT("kt", "Kotlin"),
        C("c", "C"), CPP("cpp", "C++"), JS("js", "JavaScript"),
        SH("sh", "Shell"), BASH("bash", "Bash"), BAT("bat", "Batch"),
        CMD("cmd", "CMD"), PS1("ps1", "PowerShell"), SQL("sql", "SQL"),
        LUA("lua", "Lua")
    }

    val ColorKeyword = Color(0xFFB58CFF)    // 紫
    val ColorString = Color(0xFF7FE3A2)     // 绿
    val ColorComment = Color(0xFF7B8AA1)    // 灰
    val ColorNumber = Color(0xFFFFB770)     // 橙

    fun highlight(text: String, language: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
        val lang = language.lowercase()
        val keywords = KEYWORDS[lang] ?: emptySet()
        val (lineComment, blockCommentStart) = COMMENT_LINE[lang] ?: (null to null)
        val strDelims = STRING_DELIMS[lang] ?: charArrayOf('"','\'')

        withStyle(SpanStyle(color = baseColor)) {
            append(text)
        }
        if (keywords.isEmpty() && lineComment == null) return@buildAnnotatedString

        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]

            // 行注释
            if (lineComment != null && text.startsWith(lineComment, i)) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                addStyle(SpanStyle(color = ColorComment), i, end)
                i = end
                continue
            }
            // 块注释
            if (blockCommentStart != null && text.startsWith(blockCommentStart, i)) {
                val endTag = when (blockCommentStart) {
                    "/*" -> "*/"
                    "<#" -> "#>"
                    else -> null
                }
                val end = if (endTag != null) {
                    val pos = text.indexOf(endTag, i + blockCommentStart.length)
                    if (pos < 0) n else pos + endTag.length
                } else n
                addStyle(SpanStyle(color = ColorComment), i, end)
                i = end
                continue
            }
            // 字符串
            if (c in strDelims) {
                val quote = c
                var j = i + 1
                while (j < n) {
                    val cj = text[j]
                    if (cj == '\\' && j + 1 < n) { j += 2; continue }
                    if (cj == quote) { j++; break }
                    j++
                }
                addStyle(SpanStyle(color = ColorString), i, j)
                i = j
                continue
            }
            // 数字
            if (c.isDigit()) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                addStyle(SpanStyle(color = ColorNumber), i, j)
                i = j
                continue
            }
            // 标识符（关键字）
            if (c.isLetter() || c == '_') {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                if (word in keywords || word.uppercase() in keywords) {
                    addStyle(SpanStyle(color = ColorKeyword), i, j)
                }
                i = j
                continue
            }
            i++
        }
    }

    /** 给定文件名推断语言（无扩展名或不在支持列表返回 null） */
    fun languageOf(fileName: String): Language? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return Language.entries.firstOrNull { it.ext == ext }
    }
}
