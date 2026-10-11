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

    @Test
    fun `双列落点记忆必须按列分流写入`() {
        // 落盘点原先无条件写左列变量：右列每加载一个目录，左列的「上次浏览目录」就被
        // 改写成右列的位置；而 rememberedRightDirectory 声明后从未被写过（死状态），
        // 右列记忆形同虚设。两处落盘点（列举成功、目录失效回退）都须按列分流。
        val s = source("ui/pages/FilePage.kt")
        assertTrue("右列成功列举须写右列记忆", s.contains("RootFileManager.rememberedRightDirectory = requestedDir"))
        assertTrue("右列回退也须写右列记忆", s.contains("RootFileManager.rememberedRightDirectory = fallback"))
        assertFalse(
            "列举成功处不得再无条件写左列记忆",
            Regex("""rememberDirectory && !skipPersist\) \{\s*\n\s*RootFileManager\.rememberedDirectory = requestedDir""")
                .containsMatchIn(s)
        )
        val at = s.indexOf("if (paneIndexOf(pane) == RIGHT_PANE_INDEX) {")
        assertTrue("成功落盘点须按列下标分流", at > 0)
        assertTrue("回退落盘点须按列下标分流", s.contains("if (paneIndex == RIGHT_PANE_INDEX) {"))
    }

    @Test
    fun `shso 目录被删除或移走时必须让建立标记失效`() {
        // 标记残留时 ensureShsoDir 直接返回成功而不再 mkdir：本进程内后续
        // 「添加文件到 shso」「解压到 shso」全部失败，且报错是「目录不存在」，
        // 与「已确保存在」的契约矛盾，只能靠重启进程恢复。
        val s = source("data/RootFileManager.kt")
        assertTrue(s.contains("private fun invalidateShsoDirIfAffected(path: String)"))
        assertTrue(s.contains("if (path == DEFAULT_SHSO_DIR) shsoDirEnsured = false"))
        assertEquals(
            "删除 / 改名 / 移走三个入口外加声明处，应恰好 4 处",
            4,
            Regex("invalidateShsoDirIfAffected\\(").findAll(s).count()
        )
    }

    @Test
    fun `分包安装的结果必须直接返回`() {
        // 分支尾部只写裸表达式 `outcome` 会被当成语句、值被编译器丢弃（Kotlin 报
        // 「Expression is unused」）：整套分片装完后继续往下走「单文件安装」，
        // 把刚装好的分包套件用仅含基础包的安装覆盖，返回值也换成后者的失败。
        val s = source("data/ApkInstaller.kt")
        val cleanupAt = s.indexOf("stagedHere.forEach { runCatching { File(it).delete() } }")
        val returnAt = s.indexOf("return@withContext outcome")
        assertTrue("分包分支必须显式返回安装结果", returnAt > 0)
        assertTrue("暂存副本清理须先于返回", cleanupAt in 0 until returnAt)
    }

    @Test
    fun `提取 APK 的执行作用域与进行中状态必须不随组合销毁`() {
        // 提取是逐个 cp 到 Download 的不可撤销副作用。弹窗开关若是普通 remember，旋屏即
        // 复位、弹窗离开组合、其页面级 scope 被取消 —— 已复制的分片留在 Download、未复制的
        // 没有，产出装不上的半套（base + 部分 split）；「提取中」标记若也是页面级，
        // 重建后入口重新可点，再点一次即对同一批目标文件名并发 cp -f。
        val dialog = source("ui/components/ApkExtractDialog.kt")
        assertFalse(
            "提取不得再引入页面级 scope",
            dialog.contains("import androidx.compose.runtime.rememberCoroutineScope")
        )
        assertTrue(
            "提取须用文件级进程作用域",
            dialog.contains("private val extractJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)")
        )
        assertTrue(
            "提取中状态须提到进程级",
            dialog.contains("private var extractingPkg by mutableStateOf<String?>(null)")
        )
        val page = source("ui/pages/FilePage.kt")
        assertTrue(
            "提取弹窗开关须跨重建保留",
            page.contains("var showExtractApkDialog by rememberSaveable { mutableStateOf(false) }")
        )
    }

    @Test
    fun `APK 套件聚合不得在过滤前截断候选名`() {
        // listFiles 的顺序是文件系统序，`take(200)` 等于随机砍掉一批候选名：
        // 同目录 APK 超过 200 个时目标套件可能整组落在截断之外，命名聚合静默失效，
        // 退到 manifest 分组；而应用不可读的目录（如 /data/adb）manifest 必然解析失败，
        // 最终降级为单文件安装并以 MISSING_SPLIT 失败。
        val s = source("data/ApkInstaller.kt")
        assertFalse("不得在过滤前按固定上限截断候选名", s.contains("take(MAX_SIBLING_SCAN)"))
        assertFalse("截断上限常量须一并移除", s.contains("MAX_SIBLING_SCAN"))
        assertTrue("候选名须全量传入聚合函数", s.contains("nameBasedSet(tapped.name, siblings.map { it.name })"))
    }
}
