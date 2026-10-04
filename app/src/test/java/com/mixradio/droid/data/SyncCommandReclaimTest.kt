// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步命令执行的资源回收护栏。
 *
 * 缺陷链条：`su -c` 会把命令放进**新会话**，`su` 只是其直接子进程。
 * 真机实测（PACM00 / Android 10）：
 * ```
 * $ su -c 'sleep 300 & echo $!'
 * CHILD=5361 SHELLPID=5360
 * $ ps -o pid,pgid,comm -p 5360
 *   5360 5360 sh          ← su 的子 shell 自任组长（pgid == pid）
 * $ ps -o pid,pgid,args | grep 'sleep 300'
 *   5361 5360 sleep 300   ← 继承该组
 * ```
 * 故 `process.destroyForcibly()` 只对 `su` 的 pid 发信号，
 * 被重挂到 init 的 `sh -c …`、`pm`、`cp` 会**继续以 root 运行**。
 * 本函数在全项目有 60+ 调用点，超时即在系统里留下无人回收的 root 孤儿。
 */
class SyncCommandReclaimTest {

    private val source = File("src/main/java/com/mixradio/droid/data/RootService.kt")

    @Test fun `超时必须整组回收而非只杀 su`() {
        val text = source.readText()
        val start = text.indexOf("fun runCommandSync(")
        assertTrue("应能找到 runCommandSync", start > 0)
        val end = text.indexOf("private fun forceKillProcessTree(", start)
        val body = text.substring(start, if (end > 0) end else text.length)

        val timeoutAt = body.indexOf("if (!finished)")
        assertTrue("必须存在超时分支", timeoutAt > 0)
        val timeoutBranch = body.substring(timeoutAt, timeoutAt + 400)

        assertTrue(
            "超时必须调用进程树回收",
            timeoutBranch.contains("forceKillProcessTree")
        )
        assertTrue(
            "不得再只靠 destroyForcibly 兜底",
            !body.contains("process.destroyForcibly()")
        )
    }

    @Test fun `进程树回收必须走已有的组校验命令`() {
        // 复用 buildProcessGroupKillCommand 的三重校验（组长确认、
        // 不误杀本应用所在进程组）——不能自己拼 `kill -9 -<pgid>`：
        // pid 会被内核回收复用，校验缺失时可能整组杀掉无关进程。
        val text = source.readText()
        val start = text.indexOf("private fun forceKillProcessTree(")
        assertTrue("应能找到 forceKillProcessTree", start > 0)
        val end = text.indexOf("private class CommandOutputCollector", start)
        val body = text.substring(start, if (end > 0) end else text.length)

        assertTrue(
            "必须复用 buildProcessGroupKillCommand",
            body.contains("buildProcessGroupKillCommand")
        )
        assertTrue(
            "必须校验不是本应用自身进程组",
            body.contains("android.os.Process.myPid()")
        )
        assertTrue(
            "必须从 /proc/<pid>/stat 取 pgrp",
            body.contains("/proc/\$suPid/stat")
        )
        assertTrue(
            "comm 可能含空格与右括号，必须从最后一个 ')' 之后切分",
            body.contains("substringAfterLast(')')")
        )
    }

    @Test fun `输出累积必须线程安全`() {
        // 原实现用非线程安全的 StringBuilder：读线程 append 与主线程 toString 并发，
        // 超时路径下读线程尚未退出 → 撕裂字符串或越界异常，
        // 而该异常被外层 `catch (e: Exception)` 吞掉并替换成
        // `Pair(-1, e.message)` —— **已收集的输出全部丢失**。
        val text = source.readText()
        assertTrue(
            "必须使用线程安全的输出收集器",
            text.contains("CommandOutputCollector")
        )
        val body = collectorBody()
        // 追加与快照都要同步：只同步其中一个仍会与主线程的 snapshot 竞态。
        assertTrue(
            "收集器的追加必须同步",
            body.contains("@Synchronized")
        )
        assertTrue(
            "快照也必须同步",
            Regex("@Synchronized\\s+fun snapshot\\(\\): String").containsMatchIn(body)
        )
    }

    @Test fun `输出必须有上限以防 OOM`() {
        // 无上限时一条 `find /` 或 `logcat -d` 就能让 StringBuilder
        // 扩容峰值达 2×，加上条目对象直接 OOM。
        val text = source.readText()
        assertTrue("必须定义输出上限", text.contains("MAX_SYNC_OUTPUT_CHARS"))
        val body = collectorBody()
        assertTrue(
            "超限必须如实告知而非静默丢弃",
            body.contains("已截断")
        )
        assertTrue(
            "超限后仍必须继续 drain，否则写端填满管道会让子进程永久阻塞",
            body.contains("if (truncated) return")
        )
    }

    /** 按声明边界截取收集器类，避免固定窗口随实现增长而失效。 */
    private fun collectorBody(): String {
        val text = source.readText()
        val start = text.indexOf("private class CommandOutputCollector")
        assertTrue("应能找到输出收集器", start > 0)
        val end = text.indexOf("\n    /**", start)
        return text.substring(start, if (end > 0) end else text.length)
    }

    @Test fun `读线程未结束时必须如实标记结果不完整`() {
        // join 超时说明有后代进程仍持着管道写端，尾部输出会丢。
        // 旧实现直接 `Pair(exitValue(), output.toString())` 静默返回，
        // 用户与调用方都以为拿到了完整输出。
        val text = source.readText()
        assertTrue(
            "必须检测读线程是否仍在运行",
            text.contains("readerThread.isAlive")
        )
        assertTrue(
            "必须如实告知结果可能不完整",
            text.contains("结果可能不完整")
        )
    }

    @Test fun `组杀命令的三重校验不得被绕过`() {
        // 这条护栏保护的是「不误杀无关进程组」这一不可逆风险。
        assertTrue(
            "pgid 必须大于 1",
            RootServiceProcessGroupRules.rejectsNonPositivePgid
        )
        assertTrue(
            "必须校验 /proc/<pgid>/stat 第 5 字段等于 pgid",
            RootServiceProcessGroupRules.checksGroupLeader
        )
        assertTrue(
            "必须校验本应用 pgrp 不等于目标 pgid",
            RootServiceProcessGroupRules.checksNotSelfGroup
        )
    }
}

/**
 * 把 [buildProcessGroupKillCommand] 的校验项抽成可断言的纯逻辑标记。
 *
 * 该函数返回 shell 字符串，单测只能对字符串做断言；这里用三个常量把
 * 「必须校验什么」显式记下来，避免校验被删掉时测试仍然通过。
 */
internal object RootServiceProcessGroupRules {
    val rejectsNonPositivePgid: Boolean
        get() = buildProcessGroupKillCommand(9, 0, 1234) == null &&
            buildProcessGroupKillCommand(9, 1, 1234) == null

    val checksGroupLeader: Boolean
        get() = buildProcessGroupKillCommand(9, 4242, 1234)
            ?.contains("/proc/\$P/stat") == true

    val checksNotSelfGroup: Boolean
        get() = buildProcessGroupKillCommand(9, 4242, 1234)
            ?.contains("-f5 /proc/\$M/stat") == true
}
