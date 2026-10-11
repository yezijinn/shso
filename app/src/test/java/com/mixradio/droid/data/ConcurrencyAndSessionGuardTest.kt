// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨线程状态与跨重建状态的形态守护。
 *
 * 这几处缺陷的共同点是「改完照常编译、单测也全绿」——它们只在真机的并发时序或
 * 旋转重建后暴露，因此用源码断言锁住结构，防止回退。
 */
class ConcurrencyAndSessionGuardTest {

    private fun source(relative: String): String =
        File("src/main/java/com/mixradio/droid/$relative").readText()

    @Test
    fun `发布循环代次必须原子自增且与取消同锁`() {
        // 两个调用点分处 Main（startExecution）与 IO（runTerminalCommand）。原先的 `++` 是
        // 读-改-写，两线程并发时可能拿到同一代次，旧循环 finally 的判据恒成立，
        // 把旧任务尾部输出灌进新循环会话。
        val s = source("data/HyperCore.kt")
        assertTrue(
            "代次必须是 AtomicInteger",
            s.contains("private val batchFlushGeneration = AtomicInteger(0)")
        )
        assertFalse("不得残留非原子自增", s.contains("++batchFlushGeneration"))
        assertTrue("代次须用 incrementAndGet 取得", s.contains("batchFlushGeneration.incrementAndGet()"))
        assertTrue("finally 判据须读原子值", s.contains("batchFlushGeneration.get() == myGeneration"))
        assertTrue(
            "cancel 与代次自增、Job 赋值必须同处一个临界区",
            s.contains("): Job = synchronized(this) {")
        )
        assertEquals(
            "启动函数只应有一处临界区",
            1,
            Regex("synchronized\\(this\\)").findAll(s).count()
        )
    }

    @Test
    fun `选中集裁剪必须与列表重算同处一个 effect`() {
        // 裁剪若另挂一个只读 nameQuery 的 effect：本 effect 因搜索防抖在 delay 处挂起让出，
        // 那个 effect 无延迟先跑、读到的仍是过滤前的旧列表，dropped 恒为 0，
        // 于是列表已显示「无匹配项」，批量删除仍报「选中的 N 个项目」并删掉屏幕外的文件。
        val s = source("ui/pages/FilePage.kt")
        assertFalse(
            "不得另挂只读 nameQuery 的裁剪 effect",
            s.contains("LaunchedEffect(pane.nameQuery)")
        )
        val trimAt = s.indexOf("val visible = computed.mapTo(HashSet()) { it.path }")
        assertTrue("裁剪必须基于刚算出的 computed", trimAt > 0)
        val writeAt = s.indexOf("pane.displayFileList = computed")
        assertTrue("应能找到重算落盘点", writeAt > 0)
        assertTrue("裁剪应紧跟在重算落盘之后", writeAt < trimAt)
    }

    @Test
    fun `终端输入与命令历史不得进入 savedInstanceState`() {
        // rememberSaveable 的内容会写进 Activity 的 savedInstanceState，系统在进程被回收前
        // 可能落盘；而命令常含密码、token，与「仅本次会话内存保留」的承诺冲突。
        val s = source("ui/pages/TerminalPage.kt")
        assertFalse("终端页不得再引用 rememberSaveable 及其存档器", s.contains("runtime.saveable"))
        assertFalse("不得再对终端状态用 rememberSaveable 持有", s.contains("by rememberSaveable"))
        assertTrue("改用进程级持有者", s.contains("private object TerminalSession"))
        assertTrue(s.contains("var inputText by TerminalSession.inputText"))
        assertTrue(s.contains("var cmdHistory by TerminalSession.cmdHistory"))
    }

    @Test
    fun `执行入口共用同一份尾缀口径`() {
        // 三处入口各写一份「剥 .数字 再判后缀」，任一处漂移都会让同一路径出现
        // 「列表里显示可执行、点击却报不支持格式」。
        val svc = source("data/RootService.kt")
        assertFalse("executeFile 不得自建尾缀正则", svc.contains("private val NUMERIC_SUFFIX"))
        assertTrue(svc.contains("val realExtension = realExtensionOf(fileName)"))

        val home = source("ui/pages/HomePage.kt")
        assertTrue(home.contains("import com.mixradio.droid.data.realExtensionOf"))
        assertTrue(home.contains("val realExtension = realExtensionOf(trimmed)"))

        val fileItem = source("data/FileItem.kt")
        assertTrue(fileItem.contains("internal fun realExtensionOf(name: String): String"))
        assertTrue(fileItem.contains("internal fun stripNumericSuffix(name: String): String"))
    }

    @Test
    fun `首页不得残留未接线的执行确认槽位`() {
        // pendingExecutePath 曾经声明后从未读写，注释却称「弹窗确认后再真正执行」——
        // 死状态 + 注释与实现不符。按钮文案是「立即执行」，用户手工输入路径即明确意图，
        // 故删除该槽位而非补弹窗（身份选择在文件页的 ExecuteConfirmDialog 里）。
        val home = source("ui/pages/HomePage.kt")
        assertFalse("不得残留未接线的执行确认槽位", home.contains("pendingExecutePath"))
    }
}
