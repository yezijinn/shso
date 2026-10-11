// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
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
        assertTrue(
            "install-confirm 下的文件名必须是 UUID，避免可预测路径",
            apkInstaller.contains("UUID.randomUUID()") || rootService.contains("UUID.randomUUID()")
        )
        assertEquals(1, 1)
    }
}
