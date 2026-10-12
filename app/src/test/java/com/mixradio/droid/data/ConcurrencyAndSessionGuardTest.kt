// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import com.mixradio.droid.ui.pages.MAX_SAVED_LIST_ENTRIES
import com.mixradio.droid.ui.pages.savedPathList
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
    fun `建立 shso 目录的并发调用必须只落一次 su`() {
        // 冷启动时主页与文件页两列同时调 ensureShsoDir，而 @Volatile 标记只保证可见性：
        // 「读到 false → 跑 su → 置位」可被另两个协程穿过，三次 mkdir 各发一次 su。
        // su 是 Magisk 侧的串行资源，多出来的几次会排在同时发起的目录列举前。
        val s = source("data/RootFileManager.kt")
        assertTrue("并发去重须有互斥", s.contains("private val shsoDirLock = Mutex()"))
        assertTrue("须在锁内二次检查标记", s.contains("if (shsoDirEnsured) return@withLock true"))
        assertTrue("su 须落在锁内", s.contains("shsoDirLock.withLock {"))
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

    @Test
    fun `任务输出读取不得依赖不可中断的阻塞读`() {
        // 阻塞读不响应协程取消：脚本把 stdout 交给逃逸到 init 下的后代后，read 永不返回
        // EOF，旧协程 + IO 线程 + Process 句柄一并常驻，任务每被 kill / 覆盖启动一次泄漏一份。
        // 必须改成「无数据即挂起等待」，让取消在挂起点立即生效。
        // 判空还须问 **reader** 而非底层 stream：InputStreamReader 会预读一大块进自己的
        // 字符缓冲，底层 available() 归零时 reader 里仍有数据，用 stream 判空会丢尾部输出。
        val s = source("data/RootService.kt")
        val start = s.indexOf("val buffer = CharArray(2048)")
        assertTrue("应能找到主执行读取循环", start > 0)
        val loop = s.substring(start, minOf(s.length, start + 1_400))
        assertTrue("无数据时必须挂起等待", loop.contains("if (!reader.ready()) {"))
        assertTrue("挂起等待须用 delay", loop.contains("delay(OUTPUT_POLL_MS)"))
        // 新任务接管后继续排空管道而不是停止读取：提前退出会让后代进程的写端填满而永久阻塞。
        assertTrue("新任务接管后须继续排空管道", loop.contains("if (executionJob !== myJob) continue"))
        assertFalse(
            "不得再用底层 stream 的 available() 判空（会丢掉 reader 缓冲里的尾部输出）",
            loop.contains("stream.available() <= 0")
        )
        assertFalse(
            "主执行读取循环不得保留无条件阻塞读",
            loop.contains("reader.read(buffer).also { count = it } != -1")
        )
    }

    @Test
    fun `无界路径列表不得整表写入 savedInstanceState`() {
        // savedInstanceState 是 Bundle，经 binder 传递约 1MB 上限（官方建议 < 50KB），
        // 超出即 TransactionTooLargeException、旋转当场崩。选中集合与图片列表都随
        // 目录条目数无界增长（listDirectory 对条目数无上限）。
        val pane = source("ui/pages/FilePaneState.kt")
        assertTrue("选中集合须经限量函数落盘", pane.contains("savedPathList(pane.selectedPaths)"))
        val page = source("ui/pages/FilePage.kt")
        assertTrue(
            "图片列表须用限量存档器",
            page.contains("rememberSaveable(stateSaver = savedPathListSaver)")
        )
    }

    @Test
    fun `路径列表存档器超限时整表丢弃而不截断`() {
        // 只保存前 N 条会让用户看到「选中了一部分」的假象，比丢失选择更危险，
        // 故超限一律整表丢弃；未超限则原样保留。
        val small = List(10) { "/sdcard/f$it" }
        assertEquals(small, savedPathList(small))
        assertEquals(
            MAX_SAVED_LIST_ENTRIES,
            savedPathList(List(MAX_SAVED_LIST_ENTRIES) { "p" }).size
        )
        assertTrue(
            "超限必须整表丢弃",
            savedPathList(List(MAX_SAVED_LIST_ENTRIES + 1) { "p" }).isEmpty()
        )
    }

    @Test
    fun `文件页浏览态必须跨配置变更保留且还原时不得被清理`() {
        // 目录 / 过滤词 / 多选集合 / 搜索栏此前一律 remember，旋转即清空。
        // 改为 rememberSaveable 后，切目录清理必须只在**目录真的变了**时执行，
        // 否则还原出来的多选与过滤词会被 LaunchedEffect 立刻抹掉。
        val page = source("ui/pages/FilePage.kt")
        assertTrue(
            "两列状态须跨重建保留",
            page.contains("val leftPane = rememberSaveable(saver = FilePaneState.saver(leftListState))") &&
                page.contains("val rightPane = rememberSaveable(saver = FilePaneState.saver(rightListState))")
        )
        assertTrue("聚焦列须跨重建保留", page.contains("var focusedIndex by rememberSaveable { mutableIntStateOf(0) }"))
        assertTrue("切目录清理须以已应用目录为闸", page.contains("val previousDir = pane.appliedDirRef[0]"))
        assertTrue("目录未变时不得清理", page.contains("if (previousDir != null && previousDir != pane.currentDirectory) {"))

        val pane = source("ui/pages/FilePaneState.kt")
        assertTrue("浏览态须有存档器", pane.contains("fun saver(listState: LazyListState): Saver<FilePaneState, Any>"))
        assertTrue("须记录已应用清理的目录", pane.contains("val appliedDirRef = arrayOfNulls<String>(1)"))
    }

    @Test
    fun `浏览器挑选不得使用子串启发式`() {
        // `!pkg.contains("github")` 会误伤包名恰好含该词的无关应用，也漏掉包名不含该词的
        // 链接拦截类应用。改为精确包名集合。
        val s = source("ui/pages/SettingsPage.kt")
        assertFalse("不得用子串启发式排除应用", s.contains("""!pkg.contains("github")"""))
        assertTrue("须按精确包名排除", s.contains("private val NON_BROWSER_HANDLERS = setOf(\"com.github.android\")"))
        assertTrue(s.contains(".filterNot { it in NON_BROWSER_HANDLERS }"))
    }

    @Test
    fun `多文件分享不得静默只取首项`() {
        // ACTION_SEND_MULTIPLE 的多项此前被压缩成首项，其余文件静默丢弃。
        // 采集侧须完整展开，并在只处理首个时明确告出数量。
        val activity = source("MainActivity.kt")
        assertTrue("须采集全部 URI", activity.contains("private fun resolveIncomingUris(intent: Intent): List<String>"))
        assertTrue("clipData 须逐条展开", activity.contains("for (i in 0 until clip.itemCount)"))
        assertTrue("须传递其余文件数量", activity.contains("additionalCount = additional"))

        val open = source("data/ExternalOpen.kt")
        assertTrue("解析请求须携带全部 URI", open.contains("val uris: List<String>"))
        assertTrue("须有计数告知", open.contains("val additionalCount: Int = 0"))
    }

    @Test
    fun `提取 APK 的同名冲突须按包名区分`() {
        // `<应用名>-<versionCode>` 无法区分标签与版本码同时相同的不同应用（分身 / 双开），
        // 第二次提取静默覆盖第一次的产物。
        val s = source("data/ApkExtractor.kt")
        assertTrue("须有归属判定", s.contains("private fun collisionSuffix(context: Context, baseTarget: File, packageName: String): String"))
        assertTrue("解析不出归属时保持覆盖语义", s.contains("}.getOrNull() ?: return \"\""))
        assertTrue("后缀须传进标签段而非拼到文件名末尾", s.contains("val planned = plannedTargets(app, suffix)"))
        assertTrue(
            "归属判定须基于 base 名（后缀尚未参与）",
            s.contains("val suffix = collisionSuffix(context, File(dir, baseName), app.packageName)")
        )
    }
}
