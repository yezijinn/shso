// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * 外置语法包的关键字边界契约。
 *
 * `tools/gen_syntax_packs.py` 曾统一按 `\b(kw1|kw2)\b` 生成关键字正则，两处会出错：
 * ① 关键字自带的元字符被当正则解释 —— Kotlin 的 `as?` 等价于 `as` 或 `a`，
 *    于是代码里任意单独的 `a` 都被高亮成关键字；
 * ② 首尾非词字符的关键字永远匹配不上 —— `!in` 的 `\b` 要求前一字符是词字符，
 *    而实际写法 `x !in y` 前面是空格，`!in` 从未高亮。
 *
 * 断言直接读仓库产物，改坏生成逻辑或产物立刻失败。
 */
class SyntaxPackGrammarContractTest {

    private fun packsDir(): File = File("../syntax-packs")

    /** 取某个令牌（如 `keyword`）对应的规则正则：产物为缩进 JSON，规则字符串在令牌行的上一行。 */
    private fun rulePattern(id: String, token: String): String {
        val file = File(packsDir(), "$id.json")
        assertTrue("找不到语法文件 ${file.path}（cwd=${File(".").absolutePath}）", file.isFile)
        val lines = file.readLines()
        val idx = lines.indexOfFirst { it.trim() == "\"$token\"" }
        assertTrue("$id.json 缺少 $token 规则", idx >= 1)
        val raw = lines[idx - 1].trim().removeSuffix(",").trim()
        assertTrue("$id.json 的 $token 规则不是字符串字面量：$raw", raw.startsWith("\"") && raw.endsWith("\""))
        // 规则串里唯一的 JSON 转义就是反斜杠
        return raw.substring(1, raw.length - 1).replace("\\\\", "\\")
    }

    private fun hits(pattern: Pattern, text: String): List<String> {
        val out = ArrayList<String>()
        val m = pattern.matcher(text)
        while (m.find()) out.add(m.group())
        return out
    }

    @Test
    fun `关键字生成器必须转义正则元字符`() {
        val src = File("../tools/gen_syntax_packs.py")
        assertTrue("找不到语法包生成器（cwd=${File(".").absolutePath}）", src.isFile)
        val text = src.readText()
        val start = text.indexOf("def words(")
        assertTrue("生成器必须保留 words() 入口", start > 0)
        val body = text.substring(start, minOf(text.length, start + 1200))
        assertTrue("关键字必须逐项 re.escape，否则 as? 会被当正则退化成单字母 a", body.contains("re.escape"))
    }

    @Test
    fun `Kotlin 关键字不得把单字母标识符当成关键字`() {
        val p = Pattern.compile(rulePattern("kotlin", "keyword"))
        assertEquals("单独字母 a 不是关键字", listOf("val"), hits(p, "val a = 1; a + a"))
        assertEquals("as? 必须整体命中", listOf("val", "as?"), hits(p, "val x = y as? Int"))
        assertEquals("!in 必须整体命中", listOf("if", "!in"), hits(p, "if (x !in list) {}"))
        assertEquals("!is 必须整体命中", listOf("if", "!is"), hits(p, "if (x !is Foo) {}"))
        assertEquals("前缀相同的标识符不得命中", emptyList<String>(), hits(p, "asAny + inx"))
    }

    @Test
    fun `Ruby 的 defined? 必须整体命中而 defined 不算关键字`() {
        val p = Pattern.compile(rulePattern("ruby", "keyword"))
        assertEquals(listOf("defined?"), hits(p, "puts defined?(x)"))
        assertEquals("裸 defined 不是关键字", emptyList<String>(), hits(p, "defined = 1; putd defined"))
    }
}
