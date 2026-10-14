// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二十二轮护栏：安全（分片暂存）+ 资源（取消检查、损坏键膨胀）+ 执行归一化。
 *
 * 这些缺陷的性质决定了单测只能做**源码结构断言**：
 * 分片替换要真实共享存储与安装窗口，CRLF 要真实 mksh，权限要真实 chmod，
 * 因此对应的行为验证全部在真机完成（见 TASKS.md A75 的实测记录）。
 * 本类的作用是把当时的判断固化成回归网，避免后续重构悄悄改回去。
 */
class SecondRoundSecurityAndResourceTest {

    /** Kotlin 字符串里的字面量 `$`。 */
    private val D = "\$"

    /**
     * 定位主源码。
     *
     * 不写死 `src/main/...`：单测工作目录在不同 Gradle 版本或直接跑 IDE 时
     * 可能是工程根、也可能是 `app/`，写死会让整类用例因 FileNotFoundException 全灭。
     * 这里从当前目录逐级上溯，找到含该文件的第一个位置。
     */
    private fun source(rel: String): String {
        val target = "src/main/java/com/mixradio/droid/" + rel
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, target)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("find no main source: " + target)
    }

    private val rootService = source("data/RootService.kt")
    private val shNormalization = source("data/ShNormalization.kt")
    private val apkInstaller = source("data/ApkInstaller.kt")
    private val sparseLineIndex = source("data/SparseLineIndex.kt")
    private val editHistory = source("data/EditHistoryManager.kt")
    private val archiveExtractor = source("data/ArchiveExtractor.kt")
    private val foregroundService = source("data/ExecutionForegroundService.kt")

    // ---------- 安全：解压落盘不得跟随软链 ----------

    @Test fun `解压落盘不得跟随符号链接`() {
        assertTrue(
            "FileOutputStream 会顺着已存在的软链写到链接目标，canonical 校验与打开之间存在 TOCTOU 窗口",
            !archiveExtractor.contains("FileOutputStream(dest)")
        )
        assertTrue(
            "落盘必须用 NOFOLLOW_LINKS 打开，末段为软链时直接失败而非跟随",
            archiveExtractor.contains("LinkOption.NOFOLLOW_LINKS")
        )
        val callSites = Regex("openOutputNoFollow\\(dest\\)").findAll(archiveExtractor).count()
        assertTrue(
            "ZIP/TAR 与 7z 两条落盘路径都要走同一个不跟随软链的打开方式，实际调用点=$callSites",
            callSites >= 2
        )
    }

    // ---------- 资源：ZIP 预读的预算前置 ----------

    @Test fun `ZIP 预读必须先过条目数探针再构造 central directory`() {
        val peek = archiveExtractor.substringAfter("private fun peekRoot")
            .substringAfter("Kind.ZIP ->")
            .substringBefore("Kind.SEVENZ ->")
        val probeIdx = peek.indexOf("probeZipEntryCountOrThrow(path)")
        val headersIdx = peek.indexOf("val headers = zip.fileHeaders")
        assertTrue(
            "fileHeaders 一行就把 N 万个 FileHeader 分配进堆，预算判定必须排在它之前；" +
                "probe=$probeIdx headers=$headersIdx",
            probeIdx in 0 until headersIdx
        )
    }

    // ---------- 资源：前台服务提升失败必须退出 ----------

    @Test fun `前台服务提升失败必须立即退出`() {
        assertTrue(
            "startForegroundService 之后未在数秒内进入前台，系统会以 RemoteServiceException 崩溃整个进程",
            foregroundService.contains("if (!startAsForeground())")
        )
        val guard = foregroundService.substringAfter("if (!startAsForeground())")
            .substringBefore("pollJob?.cancel()")
        assertTrue("失败分支必须 stopSelf 退出，不得继续跑保活轮询", guard.contains("stopSelf()"))
        assertTrue(
            "startForeground 抛出的异常必须被捕获并转成失败返回值",
            foregroundService.substringAfter("private fun startAsForeground(): Boolean")
                .contains("catch (_: Exception)")
        )
    }

    // ---------- 安全：分包套件必须整套暂存 ----------

    @Test fun `分包套件必须逐个暂存而非只暂存被点中的那个`() {
        val staging = apkInstaller.substringAfter("if (set.isSplit) {").substringBefore("val outcome")
        assertTrue(
            "分片分支必须调用 stageApkForInstall，否则兄弟分片仍走原始路径",
            staging.contains("stageApkForInstall")
        )
        assertTrue(
            "任一分片暂存失败必须中止安装，不能装可能已被替换的字节",
            staging.contains("已中止安装")
        )
    }

    @Test fun `暂存副本必须在安装结束后清理`() {
        val splitBranch = apkInstaller.substringAfter("if (set.isSplit) {").substringBefore("// 统一用规范名")
        assertTrue(
            "install-confirm 目录会随每次安装永久堆积，必须清理",
            splitBranch.contains("stagedHere") && splitBranch.contains("delete()")
        )
    }

    // ---------- 资源：取消检查 ----------

    @Test fun `SparseLineIndex 的读取循环必须有取消检查`() {
        val body = sparseLineIndex.substringAfter("suspend fun load").substringBefore("private fun")
        assertTrue(
            "行滑出窗口后协程已取消，但 IO 调度是协作式的，循环必须 ensureActive",
            body.contains("ensureActive()")
        )
        val loopIdx = body.indexOf("while (newlines < need)")
        val guardIdx = body.indexOf("ensureActive()")
        assertTrue(
            "ensureActive 必须在 while 循环体内，循环之后无效",
            guardIdx > loopIdx
        )
    }

    // ---------- 资源：损坏键无限膨胀 ----------

    @Test fun `损坏原文备份键必须固定且不得带时间戳`() {
        val block = editHistory.substringAfter("private fun readFileOrNull")
            .substringBefore("private fun readFile(")
        assertTrue(
            "备份键带时间戳会让每次快照失败都再写一份完整原文，prefs 无界膨胀",
            !block.contains("corrupt.\${System.currentTimeMillis()}")
        )
        assertTrue(
            "备份键必须是固定的 "+D+"key.corrupt",
            block.contains("\"\$key.corrupt\"")
        )
        assertTrue(
            "只在首次损坏时备份，且要清掉主键，避免此后每次都再写一份",
            block.contains("contains(\"\$key.corrupt\")") && block.contains("remove(key)")
        )
    }

    // ---------- 执行：CRLF / BOM 归一化 ----------

    @Test fun `sh 归一化前必须经过字节偏移安全判定`() {
        assertTrue(
            "含自解压载荷时必须保留原始字节，避免 tail 偏移错位破坏压缩流",
            rootService.contains("ShNormalization.decide(File(filePath))")
        )
        assertTrue(
            "超出检测能力或判定失败必须直跑原文件，不得冒险改写",
            rootService.contains("ShNormalization.Plan.DIRECT")
        )
        assertTrue(
            "普通 CRLF/BOM 文本脚本仍须保留临时副本归一化路径",
            shNormalization.contains("Plan.NORMALIZE") && rootService.contains("tr -d '\\r'")
        )
    }

    @Test fun `归一化不得用管道喂 sh`() {
        val runSh = rootService.substringAfter("val runShCmd: String")
            .substringBefore("val execCmd = if (useRoot)")
        assertTrue(
            "cat x.sh | sh 会让脚本里的 read 吞掉后续脚本文本（实测无输出），必须走临时文件",
            !runSh.contains("| sh")
        )
        assertTrue("必须写 app 私有临时文件后再 sh 执行", runSh.contains("> "+D+"t"))
    }

    @Test fun `非 Root 身份同样必须走归一化路径`() {
        val nonRoot = rootService.substringAfter("// 非 Root：普通 sh 执行")
            .substringBefore("if (useRoot) {")
        assertTrue(
            "非 Root 分支必须复用 runShCmd（归一化后的执行体）",
            nonRoot.contains("nonRootPrefix + runShCmd")
        )
        assertTrue(
            "非 Root 分支硬编码 `sh \$escapedFile` 会让 CRLF/BOM 脚本只在这条路径上复现 A75 缺陷",
            !nonRoot.contains("sh " + D + "escapedFile")
        )
    }

    @Test fun `非 Root 执行 ELF 不得喂给 sh`() {
        // 非 Root 下 isSo 必须走 ELF 魔数预检 + 直接执行分支，
        // 不能落到 `sh $escapedFile`：mksh 会把 ELF 字节按脚本解释，产出满屏乱码并覆盖退出码。
        val nonRoot = rootService.substringAfter("// 非 Root：普通 sh 执行")
            .substringBefore("if (useRoot) {")
        assertTrue(
            "非 Root 分支同样必须按魔数拦截非 ELF",
            nonRoot.contains("!= 7f454c46")
        )
        assertTrue(
            "非 Root 分支的 .so 必须直接执行而非交给 sh",
            nonRoot.contains("chmod u+x " + D + "escapedFile")
        )
    }

    @Test fun `非 ELF 文件必须在执行前按魔数拦截`() {
        assertTrue(
            "mksh 对 execve 返回 ENOEXEC 的文件会隐式回退为 shell 解释，" +
                "只移除显式 ( \$f || sh \$f ) 回退并不够，必须按魔数提前拦住",
            rootService.contains("!= 7f454c46")
        )
    }

    // ---------- 执行：临时目录环境变量 ----------

    /**
     * 缺陷链条：Android 应用进程不提供 `TMPDIR`，`su -c` 也不补。Termux 打包的自解压
     * 脚本（`skip=50` + gzip 载荷）在 `TMPDIR` 为空时按 makeself 逻辑回退到硬编码的
     * `/data/data/com.termux/files/usr/tmp`；设备未装 Termux 时该目录不存在，
     * `mktemp -d "${TMPDIR}gztmpXXXXXXXXX"` 失败 → `(exit 127); exit 127`，
     * 终端只留一行 `mktemp: Failed to create directory …`，退出码 127。
     * `adb shell` 自带 `TMPDIR=/data/local/tmp`，因此该差异只在应用内复现。
     */
    @Test fun `脚本执行环境必须补齐 TMPDIR`() {
        val injected = rootService.split("export TMPDIR=" + D + "tmpDir").size - 1
        assertTrue(
            "root 的 .sh / ELF 与非 root 三条执行分支都要注入 TMPDIR，实际=$injected",
            injected >= 3
        )
        assertTrue(
            "root 侧临时目录必须落在各机型恒在且 root 可写的 /data/local/tmp",
            rootService.contains("private const val ROOT_TMP_DIR = \"/data/local/tmp\"")
        )
        assertTrue(
            "非 root 侧必须落在应用私有缓存目录，与 root 侧互不影响",
            rootService.contains("ShsoApplication.appContext.cacheDir.absolutePath")
        )
    }

    @Test fun `终端命令同样必须补齐 TMPDIR`() {
        val terminal = rootService.substringAfter("private suspend fun runTerminalCommand(")
            .substringBefore("fun writeBytesAsRoot(")
        assertTrue(
            "终端里直接跑 Termux 系脚本会走到同一条空值回退分支，必须一并注入",
            terminal.contains("export TMPDIR=") && terminal.contains("ROOT_TMP_DIR")
        )
    }

    @Test fun `迟到输出必须按代际自检`() {
        assertTrue(
            "迟到的孤儿输出会绕过 clearBatchQueue 混进新任务日志；必须 continue 而非 break，" +
                "否则管道写端填满会让子孙进程永久阻塞",
            rootService.contains("if (executionJob !== myJob) continue")
        )
    }

    @Test fun `未实装的执行放行参数不得留在入口签名上`() {
        assertTrue(
            "riskApproved 从未被读取，留着会让调用方以为已放行，形成虚假契约",
            !rootService.contains("riskApproved")
        )
    }

    @Test fun `归一化产出为空但源非空时必须回落原路径`() {
        val runSh = rootService.substringAfter("val runShCmd: String")
            .substringBefore("val execCmd = if (useRoot)")
        assertTrue(
            "读取失败时不能拿空脚本假装执行成功，必须让真实报错浮现",
            runSh.contains("[ -s "+D+"t ] || [ ! -s "+D+"escapedFile ]")
        )
    }

    @Test fun `归一化临时文件必须在收尾时清理`() {
        assertTrue(
            "每次执行 .sh 都会在 filesDir 留一份，必须清理",
            rootService.contains("normalizedShFile?.delete()")
        )
    }

    // ---------- 执行：.so 权限还原时序 ----------

    @Test fun `权限还原必须排在执行之后`() {
        val soBranch = rootService.substringAfter("// .so / ELF：直接执行需要 +x。")
            .substringBefore("// 非 Root：普通 sh 执行")
        val execIdx = soBranch.indexOf("chmod a+x " + D + "escapedFile && " + D + "escapedFile")
        val restoreIdx = soBranch.indexOf("restoreAttrCmd +")
        val detail = "exec=" + execIdx + " restore=" + restoreIdx
        assertTrue(
            "restoreAttrCmd 排在 chmod a+x 之前时是 600→600 的空操作，文件会永久停在 711；" + detail,
            restoreIdx > execIdx
        )
    }

    @Test fun `原权限必须在 chmod 之前被记录`() {
        val soBranch = rootService.substringAfter("// .so / ELF：直接执行需要 +x。")
            .substringBefore("// 非 Root：普通 sh 执行")
        val saveIdx = soBranch.indexOf("saveAttrCmd +")
        val chmodIdx = soBranch.indexOf("chmod a+x "+D+"escapedFile")
        assertTrue("saveAttrCmd 必须先于 chmod a+x 执行", saveIdx in 0 until chmodIdx)
    }

    @Test fun `权限记录与还原文件必须落在 app 私有目录`() {
        assertTrue(
            "属性文件写在共享存储上会被其他应用改写，还原结果不可信",
            rootService.contains("appContext.filesDir, \".exec_attr_")
        )
    }

    // ---------- 值守：不得回退已删除的守卫 ----------

    @Test fun `不得重新引入命令守卫或档位`() {
        listOf("guardMode", "requiresConfirm", "auditLog", "riskLevel").forEach { banned ->
            assertTrue(
                "执行链路保持 root 直通，不得回退 $banned",
                !rootService.contains(banned)
            )
        }
    }

    @Test fun `暂存目录必须使用不可预测的名字`() {
        // 暂存名由 FileExecutionAnalyzer.stageApkForInstall 生成。原先的断言查的是
        // ApkInstaller / RootService 是否含 UUID.randomUUID()，而这两处另有 UUID 用法，
        // 或运算左操作数恒真 —— 把暂存名改回可预测路径，用例照样通过。
        val analyzer = source("data/FileExecutionAnalyzer.kt")
        val at = analyzer.indexOf("install-confirm")
        assertTrue("应能找到 install-confirm 暂存目录", at > 0)
        val window = analyzer.substring(at, (at + 900).coerceAtMost(analyzer.length))
        assertTrue(
            "install-confirm 下的文件名必须是 UUID，避免可预测路径",
            window.contains("UUID.randomUUID()")
        )
        assertFalse("暂存名不得用可预测的时间戳", window.contains("currentTimeMillis"))
        assertFalse("暂存名不得用可预测的纳秒值", window.contains("nanoTime"))
    }
}
