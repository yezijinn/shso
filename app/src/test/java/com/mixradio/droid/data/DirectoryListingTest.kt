// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import com.mixradio.droid.data.RootFileManager.DirectoryListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「当前目录为空」误报的回归防线。
 *
 * 缺陷成因：目录列举返回裸 [List]，上层用 `isEmpty()` 判断，于是
 * 「读取失败」（su 被拒、并发 fork 失败、输出截断、stat 全解析失败）
 * 与「目录确实为空」被压成同一信号，UI 便把一次故障说成目录事实。
 * 修复引入 [DirectoryListing] 三态，本测试锁定该语义不被回退。
 */
class DirectoryListingTest {

    private val source = File("src/main/java/com/mixradio/droid/data/RootFileManager.kt")

    /**
     * 截取 [rootFileManagerObject] 中某个挂起函数到下一个挂起函数之间的完整源码。
     *
     * 固定字符窗口会随实现增长而失效（函数体一旦超出窗口，关键判定就被切掉，
     * 测试会给出误导性的失败）。按声明边界截取，函数变长不影响断言范围。
     */
    private fun bodyOf(file: File, signature: String): String {
        val text = file.readText()
        val start = text.indexOf(signature)
        assertTrue("应能找到 $signature", start > 0)
        val next = text.indexOf("\n    suspend fun ", start + 1)
            .let { if (it > 0) it else text.indexOf("\n    private suspend fun ", start + 1) }
            .let { if (it > 0) it else text.length }
        return text.substring(start, next)
    }

    private fun listDirectoryBody(): String =
        bodyOf(source, "suspend fun listDirectory(")

    @Test fun `三态结果彼此不可混淆`() {
        // 失败与空目录必须是不同类型：这是整个修复的立足点。
        // 经 Any 装箱后再判定，避免 sealed 的静态类型让 is 检查退化为恒真断言。
        val emptyDir: Any = DirectoryListing.Success(emptyList())
        val failed: Any = DirectoryListing.Failed("ROOT 调用失败")
        val missing: Any = DirectoryListing.Missing

        assertTrue(emptyDir is DirectoryListing.Success)
        assertTrue(failed is DirectoryListing.Failed)
        assertTrue(missing is DirectoryListing.Missing)
        // 空目录是 Success 的一种，失败与不存在绝不是 Success。
        assertTrue(failed !is DirectoryListing.Success)
        assertTrue(missing !is DirectoryListing.Success)
    }

    @Test fun `失败必须携带可展示的原因`() {
        val failed = DirectoryListing.Failed("无权限读取该目录")
        assertTrue("原因不能为空，否则 UI 只能显示空文案", failed.reason.isNotBlank())
    }

    @Test fun `listDirectory 必须同时判定存在性与条目`() {
        // 旧实现是 pathExists() + listFiles() 两次独立 su 调用：
        // 两次都要 fork su，中间存在目录被删/权限变化的竞态窗口，
        // 且任一次静默失败都退化为空列表。必须合并为一次列举。
        val text = source.readText()
        val body = listDirectoryBody()
        assertTrue(body.isNotEmpty())

        assertTrue(
            "listDirectory 不得再调用 pathExists（应一次调用同时判定）",
            !body.contains("pathExists(")
        )
        assertTrue("必须区分目录不存在", body.contains("DirectoryListing.Missing"))
        assertTrue("必须区分读取失败", body.contains("DirectoryListing.Failed"))
        assertTrue("成功才返回条目", body.contains("DirectoryListing.Success"))
    }

    @Test fun `root 调用失败必须上报而非当作空目录`() {
        // runCommandSync 以 code == -1 表示 su 失败/超时。
        // 该分支必须被识别为「这次调用没跑成」，转交本地兜底并如实报 Failed；
        // 若被忽略，输出为空就会走成「目录为空」。
        //
        // 三处 su 调用都要有该判定：存在性探测、名称通道、元数据通道。
        // 这里逐处锁定，避免新增通道时漏掉。
        val body = listDirectoryBody()

        // 存在性、名称、元数据已合并为**单次** su（见 listDirectory 注释）：
        // 三次串行调用既有延迟，也存在「test -d 通过后目录被删」的 TOCTOU 窗口。
        val guard = body.indexOf("code == -1")
        assertTrue("必须识别 su 失败（-1）", guard > 0)
        assertTrue(
            "su 失败必须转本地兜底并标注 root 不可用",
            body.substring(guard, guard + 260).contains("listDirectoryLocal") &&
                body.substring(guard, guard + 260).contains("rootUnavailable = true")
        )

        // 关键：su 失败绝不能落到「目录为空」。
        // 单次调用下，空目录由「exit 0 且零条目」唯一确定，
        // 不再需要 Success(emptyList()) 这个显式分支。
        assertTrue(
            "su 失败不得被当成空目录",
            !body.substring(guard, guard + 260).contains("Success")
        )

        // 输出被截断（标记缺失）同样不能当成空目录。
        assertTrue("必须校验分隔标记是否完整", body.contains("indexOf(META_SEP)"))
        assertTrue(
            "标记缺失必须报 Failed 而非空目录",
            body.contains("目录读取结果不完整")
        )
    }

    @Test fun `空目录必须靠存在性证据判定而非输出有无`() {
        // 「目录确实为空」与「目录不存在」时 find 都是零输出、退出码非 -1，
        // 二者只能靠 ROOT 自己给出的存在性证据（test -d）区分。
        // 若退回「有无输出」判据，必有一个分支被误判：
        //   旧实现把「空目录」判成 Failed（真机：/data/adb/shso 下空目录
        //   显示「读取失败：无权限读取该目录」，重试永远失败）；
        //   反向则把「不存在」判成空目录，FilePage 的回退逻辑永久失效。
        val body = listDirectoryBody()

        assertTrue(
            "必须用 test -d 取得存在性证据",
            body.contains("test -d")
        )
        // 存在性由**退出码**承担（3=进不去 / 4=不是目录 / 0=成功），
        // 不再依赖「rootEntered」这类内存标记，也不依赖输出是否为空。
        assertTrue(
            "存在性必须由退出码区分，不得依赖内存标记",
            body.contains("exit 3") && body.contains("exit 4")
        )
        assertTrue(
            "ROOT 确认不是目录时不得判为空目录",
            body.contains("listDirectoryLocal(targetPath)")
        )
        assertTrue(
            "不得再用「有输出」作为目录存在的依据",
            !body.contains("rootProducedOutput")
        )
    }

    @Test fun `存在性与列举必须是两个问题且同处一条命令`() {
        // 「目录在不在」与「里面有什么」必须分别取证，但可以同处一条 shell 命令：
        // 存在性靠退出码，条目靠输出。合并成一条 su 消除了三次 fork 的延迟，
        // 以及 test -d 通过后目录被删的 TOCTOU 窗口。
        val body = listDirectoryBody()
        assertTrue(
            "存在性判定必须先于列举",
            body.indexOf("test -d") < body.indexOf("find . -maxdepth 1")
        )
        assertTrue(
            "退出码必须承载存在性语义",
            body.contains("exit 3") && body.contains("exit 4")
        )
        // 名称与元数据仍在同一条命令内用标记分隔，而不是两次 find 按序配对：
        // 两次遍历之间目录变化会整体错位。
        assertTrue("必须用固定标记分隔两个通道", body.contains("META_SEP"))
        assertTrue(
            "名称通道必须仍是 NUL 分隔",
            body.contains("-print0")
        )
    }

    @Test fun `本地兜底负责区分无权限与不存在`() {
        // root 失败后本地 listFiles() 返回 null（无权限）时，
        // 旧代码 catch 掉异常继续返回空列表 → 显示「当前目录为空」。
        // Missing/Failed 的区别只取决于本地读不到时的存在性复核，与 root 无关，
        // 故该判定独立成 listDirectoryLocal。
        val text = source.readText()
        val local = text.indexOf("private suspend fun listDirectoryLocal(")
        assertTrue("应能找到 listDirectoryLocal", local > 0)
        val body = text.substring(local, local + 2200)

        assertTrue("本地读不到必须报失败", body.contains("无权限读取该目录"))
        assertTrue("本地读不到必须能判定 Missing", body.contains("DirectoryListing.Missing"))
        assertTrue(
            "必须先复核存在性再决定 Missing/Failed",
            body.contains("File(targetPath).isDirectory")
        )
        assertTrue(
            "root 不可用且路径不存在时不得断言不存在",
            body.contains("ROOT 调用失败，请检查授权")
        )
    }

    @Test fun `文件页不得用 pathExists 加 listFiles 两步判定`() {
        // 两步判定存在竞态：第一次探测通过、第二次列举失败时，
        // exists 为真而列表为空，正是「有目录却显示为空」的来源。
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val fn = page.indexOf("fun refreshPane(paneIndex: Int, showToast: Boolean = false)")
        assertTrue("应能找到 refresh", fn > 0)
        val body = page.substring(fn, fn + 4000)

        assertTrue("refresh 必须改用三态结果", body.contains("RootFileManager.listDirectory("))
        assertTrue("必须处理目录不存在", body.contains("is DirectoryListing.Missing"))
        assertTrue("必须处理读取失败", body.contains("is DirectoryListing.Failed"))
        assertTrue("必须处理列举成功", body.contains("is DirectoryListing.Success"))
    }

    @Test fun `文件页读取失败时不得清空已有列表`() {
        // 失败时清空 = 把故障说成空目录。必须保留旧列表并记录原因。
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val fn = page.indexOf("is DirectoryListing.Failed -> {")
        assertTrue("应能找到 Failed 分支", fn > 0)
        val branch = page.substring(fn, fn + 400)

        assertTrue("失败分支必须记录原因", branch.contains("directoryLoadError = listing.reason"))
        assertTrue(
            "失败分支绝不能清空列表",
            !branch.contains("displayFileList = emptyList()")
        )
    }

    @Test fun `文件页空态文案必须区分失败与真空`() {
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        assertTrue("必须有独立的失败态文案", page.contains("读取失败："))
        assertTrue("必须保留真正的空目录文案", page.contains("当前目录为空"))
        assertTrue("失败态必须有可执行提示", page.contains("点右上角刷新重试"))
    }

    @Test fun `文件选择器不得用两步判定且不得谎称空目录`() {
        val picker = File("src/main/java/com/mixradio/droid/ui/components/BuiltInFilePicker.kt").readText()
        val fn = picker.indexOf("fun loadDirectory(path: String)")
        assertTrue("应能找到 loadDirectory", fn > 0)
        val body = picker.substring(fn, fn + 3000)

        assertTrue("选择器必须改用三态结果", body.contains("RootFileManager.listDirectory("))
        val failAt = body.indexOf("is DirectoryListing.Failed ->")
        assertTrue("选择器必须处理失败分支", failAt > 0)
        assertTrue(
            "选择器失败分支不得把列表清空成空目录",
            !body.substring(failAt, failAt + 300).contains("fileList = emptyList()")
        )
    }

    @Test fun `旧接口仍不得被理解为目录为空`() {
        // listFiles 保留给只需条目的调用方，其契约必须写明失败同样给空列表，
        // 且**不得**被上层当作「目录为空」的证据。
        val text = source.readText()
        val fn = text.indexOf("suspend fun listFiles(")
        assertTrue("应能找到 listFiles", fn > 0)
        val body = text.substring(fn, fn + 600)

        assertTrue(
            "必须注明失败与不存在同样返回空列表",
            body.contains("失败与不存在同样给空列表")
        )
        assertTrue(
            "必须禁止上层据此断言目录为空",
            body.contains("不得") && body.contains("目录为空")
        )
    }

    @Test fun `空目录在有 root 时仍应正确判为成功`() {
        // 防止修复过度：合法空目录必须归入 Success，
        // 否则会把「目录确实是空的」也报成故障，反而制造新的误报。
        val tmp = File(System.getProperty("java.io.tmpdir"), "shso_empty_${System.nanoTime()}")
        assertTrue(tmp.mkdirs())
        try {
            assertTrue("空目录本地读取应返回空数组而非 null", tmp.listFiles()?.isEmpty() == true)
            assertTrue("空目录本地判定应为目录", tmp.isDirectory)
        } finally {
            tmp.delete()
        }
    }

    @Test fun `空目录与读不到必须走不同判定`() {
        // 关键语义：本地读不到时，先按 isDirectory 复核——
        // 是目录 → Failed（无权限），不是目录 → Missing（不存在）。
        // 若把「读不到」也判成 Missing，上层会静默回退到内部存储，
        // 用户在原目录看到的内容被替换，且没有任何错误提示。
        //
        // 该判定在 listDirectoryLocal：listDirectory 在 ROOT 已确认可进入时
        // 直接以 root 结果为准（零条目即空目录），不再回落本地。
        val text = source.readText()
        val local = text.indexOf("private suspend fun listDirectoryLocal(")
        assertTrue("应能找到 listDirectoryLocal", local > 0)
        val body = text.substring(local, local + 2200)

        assertTrue("应存在 Missing 判定", body.contains("DirectoryListing.Missing"))
        assertTrue(
            "「读得到但是空」与「读不到」必须走不同分支",
            body.contains("DirectoryListing.Failed(\"无权限读取该目录\")")
        )
        // 未走 ROOT 且 listFiles() 为 null 时，用 isDirectory 复核存在性。
        assertTrue(
            "必须用 isDirectory 复核后才能判 Missing",
            body.contains("File(targetPath).isDirectory")
        )
    }

    @Test fun `列表作废期间不得让旧列表保持可操作`() {
        // 数据一致性 + 安全：读取失败/切目录期间，fileList 仍是**上一目录**的内容，
        // 而路径栏已是新目录。此时长按删除作用的是旧目录里的文件 ——
        // 路径栏显示 B、用户点的是 A，无回收站、不可撤销。
        // 因此发起 refresh 就必须让旧列表立即失效。
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val start = page.indexOf("fun refreshPane(paneIndex: Int, showToast: Boolean = false)")
        assertTrue("应能找到 refresh", start > 0)
        val end = page.indexOf("fun runMoveWithConflict", start)
        val body = page.substring(start, if (end > 0) end else page.length)
        val launchAt = body.indexOf("pane.refreshJobRef[0] = scope.launch")

        val staleAt = body.indexOf("listIsStale = pane.displayFileList.isNotEmpty()")
        assertTrue("必须存在列表作废标记", page.contains("listIsStale"))
        assertTrue("发起刷新时必须标记列表已作废", staleAt in 0 until launchAt)

        // 关键：本条守护的目标是「旧列表不得可操作」，而**不是**「必须清空」。
        // 清空列表会让每次刷新都进入一段空白窗口（骨架屏或「当前目录为空」占位），
        // 列举要过一次 su 往返，用户观感就是「刷新后长时间看不见文件」+ 偶发空目录误报。
        // 因此改为：保留列表（无空白窗口），同时由 listIsStale 让它不可操作。
        assertTrue(
            "发起刷新时不得清空列表（会造成刷新期空白与空目录误报）",
            body.indexOf("fileList = emptyList()") !in 0 until launchAt
        )
        // 「不可操作」必须真的落到 UI 交互上，而不只是置个状态位。
        val clickAt = page.indexOf("enabled = !pane.listIsStale")
        assertTrue(
            "列表项必须用 enabled = !pane.listIsStale 真正禁用点击/长按",
            clickAt > 0
        )
        val clickableAt = page.indexOf("combinedClickable(")
        assertTrue(
            "门禁必须作用于列表项本身",
            clickableAt in 0 until clickAt
        )
        // 双列布局下，作废标记必须读**聚焦列**的那一份。若读页面级共享值，
        // 会出现「左列正在加载，把右列的列表也误判为已作废而点不动」。
        assertTrue(
            "listIsStale 必须是聚焦列的委托别名，不能是页面级共享状态",
            page.contains("var listIsStale by PaneProp({ activePane.listIsStale }")
        )
        assertTrue(
            "两份列表状态也必须各自独立，否则两列会互相覆盖",
            page.contains("val leftPane = remember { FilePaneState(") &&
                page.contains("val rightPane = remember {")
        )
    }

    @Test fun `读取失败不得保留可操作的旧列表`() {
        // 失败分支不得把**旧内容**当成新结果写回列表。
        // 列表改为「刷新期间保留」后，这一点更要守住：失败时若写回旧内容，
        // 用户会以为这就是当前目录的真实内容。
        // 旧列表此时由 listIsStale 保持不可操作，不会造成误删。
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val fn = page.indexOf("is DirectoryListing.Failed -> {")
        assertTrue("应能找到 Failed 分支", fn > 0)
        val branch = page.substring(fn, fn + 400)

        assertTrue("失败分支必须记录原因", branch.contains("directoryLoadError = listing.reason"))
        assertTrue(
            "失败分支绝不能写回列表",
            !branch.contains("displayFileList =")
        )
    }

    @Test fun `重算协程不得在列表作废期间落盘`() {
        // 否则会把「读取失败」状态覆盖回「目录为空」——
        // 正是本次要修的那类误报的另一种形式。
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val fn = page.indexOf("val computed = withContext(Dispatchers.Default)")
        assertTrue("应能找到重算逻辑", fn > 0)
        // 窗口要够宽：守卫在 computed 落盘之后不远处，但中间隔着代次复核。
    val tail = page.substring(fn, fn + 900)

        assertTrue(
            "重算落盘前必须检查 listIsStale",
            tail.contains("if (listIsStale) return@LaunchedEffect")
        )
    }

    @Test fun `stat 秒转毫秒换算仍被目录解析使用`() {
        // 回归护栏：修复未触碰换算逻辑，但目录解析依赖它。
        assertEquals(1789044590000L, RootFileManager.statSecondsToMillis(1789044590L))
    }

    // ========================================================================
    // 名称通道：NUL 分隔，杜绝幻影条目
    // ========================================================================

    @Test fun `文件名必须走 NUL 分隔通道`() {
        // 真实数据丢失路径：ext4/f2fs 允许文件名含 \n。若把裸文件名放进
        // 以 \n 分隔的 stat 记录里，一个名为
        //   x\ndrwxr-xr-x|0|0|y
        // 的文件会被解析成两条：一条指向不存在的 <dir>/x（幻影），
        // 一条是伪造的「目录 y」。长按删除幻影 x → rm -rf 删掉真实的 x。
        // 尾随空格同样被 trim() 抹掉后指向同名但不同的文件。
        // NUL 不可能出现在文件名里，是唯一可靠的记录分隔符。
        val body = listDirectoryBody()

        assertTrue(
            "名称必须用 find -print0 取",
            body.contains("-print0")
        )
        assertTrue(
            "必须按 NUL 切分",
            body.contains("split('\\u0000')")
        )
        // 判据针对**可执行代码**：注释里为了说明问题会引用旧的 %n 格式，
        // 因此这里检查 stat 调用本身，而不是全文是否出现过该字符串。
        assertTrue(
            "旧的行分隔解析器必须已移除",
            !source.readText().contains("private fun parseStatOutput(")
        )
        assertTrue(
            "stat 元数据通道不得携带文件名（%n）",
            !Regex("-exec stat[^\\n]*%n").containsMatchIn(body)
        )
        assertTrue(
            "源码不得含裸 NUL 控制字符",
            !source.readText().contains('\u0000')
        )
    }

    @Test fun `元数据通道不得包含文件名`() {
        // 元数据通道按 \n 切分，只有不含文件名才安全（文件名里可能有 \n）。
        // 元数据通道已随单次 su 合并内联进 listDirectory（不再有独立的 runStatMeta）。
        val body = listDirectoryBody()
        // 只看代码行：注释里会引用旧格式 %A|%s|%Y|%n 作为反例说明，那不是实际执行的命令。
        val cmdText = body.lineSequence()
            .filter { !it.trimStart().startsWith("//") }
            .joinToString("\n")

        assertTrue(
            "stat 格式只能是 %A|%s|%Y，不得含 %n",
            cmdText.contains("%A|%s|%Y") && !cmdText.contains("%Y|%n")
        )
    }

    @Test fun `元数据解析不得对整行 trim 后再切`() {
        // 名称已由独立通道提供，这里只解析 %A|%s|%Y；
        // 对整行 trim 是旧实现的残留（会连带吃掉名称的尾随空格）。
        val text = source.readText()
        val fn = text.indexOf("private fun parseStatMeta(")
        assertTrue("应能找到 parseStatMeta", fn > 0)
        val body = text.substring(fn, fn + 800)

        assertTrue("必须跳过空行", body.contains("isEmpty") || body.contains("isBlank"))
        assertTrue(
            "必须按 | 切分元数据三段",
            body.contains("split('|')")
        )
        assertTrue(
            "秒转毫秒必须在此处生效",
            body.contains("statSecondsToMillis")
        )
    }

    @Test fun `名称与元数据按序配对且缺失项不得越界`() {
        // 两个通道是两次 find：数量可能不等（断链符号链接 stat -L 失败、
        // 或两次调用之间目录被改动）。必须按索引安全取值，不能抛异常。
        val body = listDirectoryBody()
        assertTrue(
            "必须用 getOrNull 安全配对",
            body.contains("metas.getOrNull(index)")
        )
        assertTrue(
            "配对缺失时按非目录处理",
            body.contains("isDirectory = meta?.isDirectory == true")
        )
    }

    @Test fun `空目录必须在名称通道为空时直接判成功`() {
        // 核心回归：旧实现在 ROOT 已进入却零条目时
        // 回落本地判定，而 /data/adb/shso 被 SELinux 拦 → listFiles() 返回 null
        // → 报「读取失败：无权限读取该目录」，重试永远失败。
        val body = listDirectoryBody()
        // 单次 su 合并后已无独立的 Success(emptyList()) 分支：
        // 「目录确实为空」由 exit 0 且零条目唯一确定，不靠任何显式空分支。
        assertTrue(
            "零条目必须直接判成功，不能回落本地兜底",
            !body.contains("Success(emptyList())")
        )
        // 「ROOT 已进入后不回落本地」指的是**成功解析出条目之后**不再回落。
        // 函数末尾那句 listDirectoryLocal 属无 ROOT 分支的兜底（ROOT 从未进入），
        // 是必需路径，不能一并禁掉。
        val afterItems = body.substring(body.indexOf("items = names.mapIndexed"))
        val successReturn = afterItems.indexOf("return@withContext DirectoryListing.Success")
        assertTrue("应存在成功返回", successReturn > 0)
        assertTrue(
            "ROOT 已进入且解析成功后，返回之前不得再回落本地判定",
            !afterItems.substring(0, successReturn).contains("listDirectoryLocal")
        )
    }

    // ========================================================================
    // root 探测时序：null 不得被当作「无 ROOT」
    // ========================================================================

    @Test fun `root 未探测时必须等待而非直接降级`() {
        // 真机复现：Debug 首启即进文件页，isRootGranted 仍是 null，
        // preferRoot() 返回 false → 跳过 su 直接走本地 File API →
        // /storage/emulated/0 列出 0 项 → UI 显示「当前目录为空」，
        // 而该目录实际有 150 个文件。
        // null 的语义是「尚未探测」，不是「无 ROOT」，必须先等探测落定。
        val text = source.readText()
        val fn = text.indexOf("private suspend fun preferRoot(")
        assertTrue("应能找到 preferRoot", fn > 0)
        val body = text.substring(fn, fn + 900)

        assertTrue(
            "null 状态必须走等待逻辑",
            body.contains("awaitRootState()")
        )
        assertTrue(
            "必须显式判断 null 而非直接读 == true",
            body.contains("RootService.isRootGranted != null")
        )
        assertTrue(
            "等待必须有超时上限，不能无限阻塞文件页",
            text.contains("ROOT_STATE_WAIT_MS")
        )
    }

    @Test fun `等待逻辑必须有超时与轮询间隔`() {
        // 授权弹窗可能长期挂起（用户没看见）。无限等待会让文件页永远停在骨架屏，
        // 把一个偶发空列表换成更糟的永久无响应。
        val text = source.readText()
        assertTrue("必须定义等待上限", text.contains("private const val ROOT_STATE_WAIT_MS"))
        assertTrue("必须定义轮询间隔", text.contains("private const val ROOT_STATE_POLL_MS"))
        assertTrue("必须用 elapsedRealtime 抗系统时间跳变", text.contains("SystemClock.elapsedRealtime()"))
    }

    // ========================================================================
    // 展示列表重算竞态
    // ========================================================================

    @Test fun `重算协程必须等 refresh 落盘后才能覆盖列表`() {
        // 真机复现：refresh() 在 IO 线程读目录期间，
        // LaunchedEffect(showHiddenFiles/sortMode/nameQuery) 用**上一份** fileList
        // （切目录时为空列表）算完并写入 displayFileList。
        // refreshGenRef 只在 refresh 启动时递增，故此刻代次相同、守卫放行，
        // 空列表就此落盘且此后无人重算 —— 用户看到「当前目录为空」。
        // 必须额外用 loadingGenRef 表示「本代是否已落盘」。
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

        assertTrue("必须存在落盘标记", page.contains("loadingGenRef"))
        assertTrue(
            "重算前必须检查本代是否仍在加载",
            page.contains("if (activePane.loadingGenRef[0] == gen) return@LaunchedEffect")
        )
        assertTrue(
            "重算后必须再次检查（等待期间可能又启动了新一代）",
            page.indexOf("if (activePane.loadingGenRef[0] == gen) return@LaunchedEffect",
                page.indexOf("val computed = withContext(Dispatchers.Default)")) > 0
        )
    }

    @Test fun `refresh 必须在启动与收尾维护落盘标记`() {
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val start = page.indexOf("fun refreshPane(paneIndex: Int, showToast: Boolean = false)")
        assertTrue("应能找到 refresh", start > 0)
        // 按下一个函数声明截断，避免固定窗口随实现增长而失效
        val end = page.indexOf("fun runMoveWithConflict", start)
        val body = page.substring(start, if (end > 0) end else page.length)

        val setAt = body.indexOf("pane.loadingGenRef[0] = gen")
        val launchAt = body.indexOf("pane.refreshJobRef[0] = scope.launch")
        assertTrue("启动时必须标记本代未落盘", setAt > 0)
        assertTrue("标记必须早于协程启动", setAt < launchAt)

        val clearAt = body.indexOf("pane.loadingGenRef[0] = -1")
        assertTrue("收尾时必须清除标记", clearAt > 0)
        assertTrue(
            "清除必须限定在最新一代，否则被取消的旧刷新会误清",
            body.substring(clearAt - 200, clearAt).contains("gen == pane.refreshGenRef[0]")
        )
    }

    @Test fun `切换目录不得重置落盘标记造成误判`() {
        // 落盘标记只在 refresh 的 finally 里清除。切目录本身不碰它，
        // 否则会在 refresh 尚未启动时短暂呈现「已落盘」，重算协程得以用旧 fileList 落盘。
        val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val start = page.indexOf("fun navigateTo(dir: String)")
        assertTrue("应能找到 navigateTo", start > 0)
        // 按下一个函数声明截断，避免固定窗口越界到 refresh 而误判
        val end = page.indexOf("fun refreshPane(paneIndex: Int, showToast: Boolean = false)", start)
        val body = page.substring(start, if (end > 0) end else page.length)

        assertTrue(
            "navigateTo 不得写 loadingGenRef",
            !body.contains("pane.loadingGenRef")  // 已改为 pane.loadingGenRef，语义等价
        )
    }
}
