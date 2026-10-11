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

    @Test fun `解析代次计数器必须是文件级且跨线程可见`() {
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
            s.contains("TerminalParseGeneration.ref.get()") ||
                s.contains("TerminalParseGeneration.ref.incrementAndGet()")
        )
        // 读取发生在 withContext(Dispatchers.Default) 的解析块里，写发生在主线程；
        // 跨 Activity 重建时两者无 happens-before，普通数组容器会读到过期值使守卫恒成立。
        assertTrue(
            "代次计数器必须是原子类型",
            s.contains("val ref = AtomicInteger(0)")
        )
        assertTrue(
            "不得再用无可见性保证的数组容器",
            !s.contains("val ref = intArrayOf")
        )
    }

    @Test fun `代次递增仍必须是前置自增`() {
        // 后置自增会使 myGen 恒等于 ref 的旧值，两处守卫恒为假，
        // 每次 collect 都在解析完的结果上直接 return，终端输出区永远空白。
        val s = page.readText()
        assertTrue(
            "必须前置自增",
            Regex("TerminalParseGeneration\\.ref\\.incrementAndGet\\(\\)").containsMatchIn(s)
        )
        assertTrue(
            "不得回退为后置自增",
            !Regex("TerminalParseGeneration\\.ref\\.getAndIncrement\\(\\)").containsMatchIn(s)
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

/**
 * 第十六轮审查修复项的源码契约护栏：把「改回旧写法就回归」的四处钉住。
 */
class RoundSixteenGuardTest {

    private val dialog = File("src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt")
    private val sora = File("src/main/java/com/mixradio/droid/ui/components/SoraTextEditor.kt")
    private val viewer = File("src/main/java/com/mixradio/droid/ui/components/ContentViewerDialogs.kt")

    @Test fun `历史列表的 LazyColumn key 不得用毫秒时间戳`() {
        // 同一毫秒的两条历史会撞 key，LazyColumn 抛「Key ... was already used」直接崩。
        val s = dialog.readText()
        assertTrue("不得再以 timestamp 作 key", !s.contains("key = { _, entry -> entry.timestamp }"))
        assertTrue("应以稳定下标作 key", s.contains("itemsIndexed(history, key = { index, _ -> index })"))
    }

    @Test fun `编辑器重载必须取当前路径而非初始路径`() {
        // 另存为后旋转：按 initialFilePath 重载会把旧文件内容读回编辑器，而保存仍写向
        // currentFilePath —— 用旧文件内容整体覆盖新文件。
        val s = dialog.readText()
        assertTrue("加载源应为 currentFilePath", s.contains("val path = currentFilePath ?: return@LaunchedEffect"))
        assertTrue("不得再按 initialFilePath 加载", !s.contains("val path = initialFilePath ?: return@LaunchedEffect"))
    }

    @Test fun `Sora 字号守卫不得混比 sp 与 px`() {
        // fontSize 是 sp、textSizePx 是 px，density≠1 时恒不相等，守卫失效 → 每键全量重排。
        val s = sora.readText()
        assertTrue("不得再用 px 与 sp 值直接比较", !s.contains("if (ed.textSizePx != targetPx)"))
        assertTrue("应以已下发字号判等", s.contains("appliedTextSize.value != fontSize.value"))
    }

    @Test fun `图片查看器初始索引对空列表安全`() {
        // 空列表时 images.lastIndex == -1，coerceIn(0, -1) 在组合期抛异常。
        val s = viewer.readText()
        assertTrue(
            "空列表必须兜底下限",
            s.contains("coerceIn(0, maxOf(0, images.lastIndex))")
        )
    }
}

/**
 * 取消传播、批量守卫作用域、临时名不可预测的源码契约护栏。
 *
 * 这几处都是「改成旧写法即回归」的写法级约束，行为测试覆盖不到组合/私有函数内部，
 * 用源码契约钉住。
 */
class CancellationAndScopeContractTest {

    private val dialog = File("src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt")
    private val filePage = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt")
    private val textCompare = File("src/main/java/com/mixradio/droid/data/TextCompare.kt")
    private val sparse = File("src/main/java/com/mixradio/droid/data/SparseLineIndex.kt")

    @Test fun `挂起体不得用宽 catch 吞掉取消信号`() {
        // CancellationException 是取消信号，不是「读取失败」。宽 catch 会把它吞成正常返回，
        // 使取消在编辑器加载 / 加载更多这两条路径上失效。
        val s = dialog.readText()
        assertTrue("编辑器加载的 catch 必须先重抛取消", s.contains("} catch (e: CancellationException) { throw e"))
        assertTrue("加载更多的 catch 必须先重抛取消", s.contains("} catch (e: CancellationException) {"))
    }

    @Test fun `稀疏索引 build 必须可取消`() {
        // 原实现 runCatching 包住整个扫描体且循环无检查点：大文件建索引在用户离开后仍跑完整个文件。
        val s = sparse.readText()
        assertTrue("build 不得再用 runCatching 兜底", !Regex("withContext\\(Dispatchers\\.IO\\) \\{\\s*runCatching").containsMatchIn(s))
        assertTrue("build 必须重抛取消", s.contains("} catch (e: CancellationException) {"))
        assertTrue("扫描循环必须有取消检查点", s.contains("currentCoroutineContext().ensureActive()"))
    }

    @Test fun `批量守卫标记必须与执行作用域同生命周期`() {
        // 批量循环跑在文件级 batchJobScope（旋屏不取消），守卫标记若是页面级 remember，
        // 旋转归零后目录切换不再被拦截，删除落在旧目录而收尾刷新新目录。
        val s = filePage.readText()
        assertTrue("守卫标记必须定义在文件级", s.contains("private var batchInFlight by mutableStateOf(false)"))
        assertTrue("不得再用页面级 remember 持有守卫", !s.contains("var isBatchRunning by remember"))
        assertTrue("守卫标记不得残留旧名", !s.contains("isBatchRunning"))
    }

    @Test fun `对比结果非 root 落盘临时名必须不可预测`() {
        // 结果文件名可预测，固定临时名会被在可写目录预置的软链劫持写入。
        val s = textCompare.readText()
        assertTrue(
            "非 root 临时名必须含 UUID",
            Regex("\\.\\$\\{target\\.name\\}\\.shso_\\$\\{java\\.util\\.UUID\\.randomUUID\\(\\)\\}\\.tmp")
                .containsMatchIn(s)
        )
        assertTrue("不得再用固定临时名", !s.contains(".shso_tmp\""))
    }

    @Test fun `只读浏览提示必须反映只读能力而非体积档位`() {
        // 只读判定用 MAX_LOAD_BYTES（32MB），LARGE 档从 50MB 起，32–50MB 落在 MEDIUM 档，
        // 原按档位分派会误标「全文可浏览」。提示条只在只读态渲染，一律标注只读预览。
        val s = dialog.readText()
        assertTrue(
            "MEDIUM 档不得再落到「全文可浏览」",
            !s.contains("FileSizeClass.LARGE -> \"全文可浏览 · 只读预览\"")
        )
        assertTrue("只读态必须标注只读预览", s.contains("\"全文可浏览 · 只读预览\""))
    }

    @Test fun `只读浏览态下行尾与 BOM 设置项必须禁用`() {
        // 两项切换都会置 dirty，而只读态 doSave 被 isLargeFile 守卫拦死：
        // 可切等于把用户引进「改了必被拦、关必弹未保存、只能丢弃」的死路。
        val s = dialog.readText()
        assertTrue(
            "换行设置项必须按只读态禁用",
            s.contains("CompactSettingRow(\"换行\", lineEnding.displayName(), enabled = !chunkedBrowsing)")
        )
        assertTrue(
            "BOM 设置项必须按只读态禁用",
            s.contains("enabled = !chunkedBrowsing && (charset == Charsets.UTF_8 ||")
        )
    }
}

/**
 * 前台保活服务的「结束进程」收摊契约。
 *
 * ACTION_KILL 经 PendingIntent.getService 拉起，START_NOT_STICKY 只表示进程被杀后不重启，
 * 不等于停掉服务自身 —— 不 stopSelf 会留下 started 状态的空服务。
 */
class ExecutionForegroundServiceLifecycleTest {

    private val service =
        File("src/main/java/com/mixradio/droid/data/ExecutionForegroundService.kt")

    @Test fun `结束进程分支必须回收服务自身`() {
        val s = service.readText()
        val idx = s.indexOf("if (intent?.action == ACTION_KILL) {")
        assertTrue("应能找到 ACTION_KILL 分支", idx > 0)
        val branch = s.substring(idx, idx + 400)
        assertTrue("ACTION_KILL 分支必须 stopSelf", branch.contains("stopSelf()"))
    }
}
