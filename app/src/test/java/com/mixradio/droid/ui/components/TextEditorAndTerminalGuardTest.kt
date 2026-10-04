// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本编辑器「不可逆数据破坏」与「必崩」路径的回归护栏。
 *
 * 本组锁定的都是**真实可触发**且后果不可撤销的缺陷，
 * 且每条都指向「三处保存入口各自抄一遍守卫」这一结构性成因。
 */
class TextEditorSaveGuardTest {

    private val dialog = File("src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt")
    private val sora = File("src/main/java/com/mixradio/droid/ui/components/SoraTextEditor.kt")

    @Test fun `未保存弹窗不得漏掉只读大文件守卫`() {
        // 失效链（每一环都已核实）：
        //  1. 打开 > 32MB（MAX_LOAD_BYTES）文件 → 稀疏行索引只读浏览态，isLargeFile = true；
        //  2. 设置里改「换行」或「写入 BOM」→ 无条件 dirty = true → 顶栏出现「● 未保存」；
        //  3. 点「✕ 关闭」→「未保存的更改」→「保存并关闭」；
        //  4. UnsavedChangesDialog.onSave 只判 isLoading / loadError / isSaving，**漏掉 isLargeFile**；
        //  5. syncSnapshot() 因 `!soraEditor.isAttached` 直接返回（只读态未进组合），
        //     contentValue.text 仍是初始空串；
        //  6. writeTextFile(path, "", ...) 以「临时文件 + 原子替换」把 32MB+ 原文件写成 0 字节，
        //     且该路径不写历史、不抛异常 → 内容彻底消失。
        //
        // doSave 与 SaveAsDialog 都有该守卫，唯独这条漏了 —— 说明必须三处同时校验。
        val s = dialog.readText()
        val idx = s.indexOf("onSave = {\n            showUnsavedDialog = false")
        assertTrue("应能找到未保存弹窗的保存分支", idx > 0)
        val branch = s.substring(idx, idx + 1600)

        assertTrue(
            "未保存弹窗必须拦下只读大文件",
            branch.contains("isLargeFile")
        )
        assertTrue(
            "必须给出可读原因",
            branch.contains("仅支持只读浏览")
        )
    }

    @Test fun `三条保存入口都要有只读大文件守卫`() {
        val s = dialog.readText()
        // doSave
        assertTrue("doSave 必须拦只读大文件", s.contains("文件超过可编辑上限，仅支持只读浏览，无法保存"))
        // SaveAsDialog
        assertTrue("另存为必须拦只读大文件", s.contains("文件超过可编辑上限，仅支持只读浏览，无法另存为"))
        // 三处计数：确保新增保存入口时不会又漏一处
        val guards = Regex("isLargeFile").findAll(s).count()
        assertTrue("isLargeFile 守卫点应覆盖多处，实际 $guards", guards >= 8)
    }

    @Test fun `只读态不得依赖编辑器取全文来写盘`() {
        // 根因是 syncSnapshot 在未挂载时静默返回空串，而调用方把空串当「当前内容」。
        // 该约束写在 SoraEditorController.isAttached 的 KDoc 里（不是 dialog），
        // 这里锁定 syncSnapshot 确实提前返回、以及那条约束仍在文档中。
        val s = dialog.readText()
        assertTrue(
            "syncSnapshot 必须在未挂载时提前返回",
            s.contains("if (!soraEditor.isAttached) return")
        )
        val controller = sora.readText()
        assertTrue(
            "isAttached 的注释必须保留「取全文并写回前先判这个」的约束说明",
            controller.contains("凡是要")
        )
    }
}

/**
 * Sora 检索 API 的「会抛异常」防护。
 *
 * `EditorSearcher` 把「有无检索词」记在 `currentPattern` 上，而 `gotoNext()` /
 * `matchedPositionCount()` / `replaceCurrentMatch()` 的**第一条指令就是 `checkState()`**，
 * 在 `currentPattern == null` 时无条件抛 `IllegalStateException`。
 *
 * 真实触发：查找弹窗关闭 → `stopSearch()` 清 Sora 侧；再次打开并输入**相同**检索词 →
 * UI 侧判定「无需重新检索」直接 `gotoNextMatch()` → 必崩（调用点在 clickable 回调里，无 try/catch）。
 */
class SoraSearchGuardTest {

    private val sora = File("src/main/java/com/mixradio/droid/ui/components/SoraTextEditor.kt")

    @Test fun `检索有效性必须被显式记录`() {
        val s = sora.readText()
        assertTrue(
            "必须记录本控制器是否持有有效检索",
            s.contains("queryActive")
        )
        assertTrue(
            "search() 成功后必须置为有效",
            Regex("queryActive\\s*=\\s*true").containsMatchIn(s)
        )
        assertTrue(
            "stopSearch() 必须同时清掉该标记",
            Regex("fun stopSearch\\(\\)[\\s\\S]{0,200}queryActive\\s*=\\s*false").containsMatchIn(s)
        )
    }

    @Test fun `会抛 checkState 的方法必须先判有效性`() {
        val s = sora.readText()
        for (fn in listOf("gotoNextMatch", "replaceAll", "replaceCurrentMatch", "searcherMatchCount")) {
            val idx = s.indexOf("fun $fn(")
            assertTrue("应能找到 $fn", idx > 0)
            val body = s.substring(idx, idx + 400)
            assertTrue(
                "$fn 必须先判 queryActive，否则 currentPattern 为 null 时 checkState 抛异常",
                body.contains("queryActive")
            )
        }
    }

    @Test fun `检索方法必须有兜底以防库行为变化`() {
        // 即便 queryActive 判断将来被绕过，也不能让异常直达崩溃：
        // 这些调用点分布在 Modifier.clickable 回调与 LaunchedEffect 协程里。
        val s = sora.readText()
        assertTrue(
            "gotoNextMatch 必须 runCatching 兜底",
            Regex("fun gotoNextMatch\\(\\)[\\s\\S]{0,200}runCatching").containsMatchIn(s)
        )
        assertTrue(
            "searcherMatchCount 必须 runCatching 兜底",
            Regex("fun searcherMatchCount\\(\\)[\\s\\S]{0,400}runCatching").containsMatchIn(s)
        )
    }
}

/**
 * 终端解析代次守卫的跨 Activity 有效性。
 *
 * `parseGenRef` 曾是 `remember`：旋转屏幕后新旧 Activity 各持一份全新数组，
 * `myGen` 都等于 1，`parseGenRef[0] == myGen` 两侧同时成立 → 守卫形同虚设。
 * 旧 Activity 那个 in-flight 解析块（纯 CPU、无挂起点，取消打不断）会与新 Activity
 * 的首个 collect 串行进入 `synchronized(ansiParser)`，各自持有自己捕获的 `prev`，
 * 后进入者按旧 prev 把同一段再喂一次 → 终端出现重复行。
 */
class TerminalParseGenerationScopeTest {

    private val page = File("src/main/java/com/mixradio/droid/ui/pages/TerminalPage.kt")

    @Test fun `解析代次计数器必须是文件级而非组合级`() {
        val s = page.readText()
        assertTrue(
            "代次计数器必须定义在文件级对象上",
            s.contains("internal object TerminalParseGeneration")
        )
        assertTrue(
            "组合内不得再用 remember 持有代次计数器",
            !s.contains("val parseGenRef = remember")
        )
        assertTrue(
            "取代次必须走文件级对象",
            s.contains("TerminalParseGeneration.ref[0]")
        )
    }

    @Test fun `代次递增仍必须是前置自增`() {
        // 后置自增会使 myGen 恒等于 ref[0] - 1，两处守卫恒为假，
        // 每次 collect 都在解析完的结果上直接 return，终端输出区永远空白。
        val s = page.readText()
        assertTrue(
            "必须前置自增",
            Regex("\\+\\+TerminalParseGeneration\\.ref\\[0\\]").containsMatchIn(s)
        )
        assertTrue(
            "不得回退为后置自增",
            !Regex("TerminalParseGeneration\\.ref\\[0\\]\\+\\+").containsMatchIn(s)
        )
    }

    @Test fun `超长行输出时自动滚动必须重新触发并钉到底`() {
        // 输出不含换行时行数恒定 → 只以行数为 key 的 effect 根本不重跑，
        // 视口停在原处，新增内容全落在软换行折叠线以下。
        // 且 scrollToItem 只对齐 item 顶端，对超过视口高度的行，尾部永远在屏外。
        val s = page.readText()
        assertTrue(
            "滚动 key 必须包含末行长度",
            Regex("LaunchedEffect\\(parsedOutput\\.lines\\.size,\\s*lastLineLength,").containsMatchIn(s)
        )
        assertTrue(
            "必须显式钉到底部",
            s.contains("scrollToItem(last, Int.MAX_VALUE / 2)")
        )
    }
}
