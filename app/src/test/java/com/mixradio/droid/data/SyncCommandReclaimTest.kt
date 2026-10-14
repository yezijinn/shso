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
 * 真机实测（Android 10）：
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
        val timeoutBranch = body.substring(timeoutAt, timeoutAt + 500)

        assertTrue(
            "超时必须调用进程树回收",
            timeoutBranch.contains("forceKillProcessTree(process, suPid)")
        )
        // 回收通道自身发起的 kill 命令必须能关闭「超时→回收」再入，否则与 forceKillProcessTree 互递归无界。
        assertTrue(
            "超时回收必须可关闭（斩断互递归）",
            body.contains("reclaimOnTimeout")
        )
    }

    @Test fun `进程树回收必须走已有的组校验命令`() {
        // 复用 buildProcessGroupKillCommand 的三重校验（组成员存活、
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

    @Test fun `终端命令超时同样必须整组回收`() {
        // 四条终止路径中唯独 runTerminalCommand 的超时分支只 destroyForcibly：
        // 30 分钟长跑命令超时后，`sh -c …` 会以 root 重挂 init 继续跑，且此后
        // isTaskRunning 已置 false，「结束进程」被禁用、重启终端又会抹掉 runPgid。
        val text = source.readText()
        val start = text.indexOf("private suspend fun runTerminalCommand(")
        assertTrue("应能找到 runTerminalCommand", start > 0)
        val end = text.indexOf("fun writeBytesAsRoot(", start)
        val body = text.substring(start, if (end > 0) end else text.length)
        assertTrue(
            "超时分支必须复用 forceKillProcessTree 整组回收",
            body.contains("forceKillProcessTree(process, suPid)")
        )
        assertTrue("必须在起进程后捕获 suPid 供反查", body.contains("val suPid = pidOfProcess(process)"))
    }

    @Test fun `结束进程在 pgid 未回填时必须按 pid 反查进程组`() {
        // 终端命令 currentTaskPath 恒为 null，pkill 兜底整段跳过；pgid 未落盘时
        // （命令刚发出，回填窗口最长 1.5s）就只剩「杀 su」一条路，子孙逃逸为 root 孤儿。
        val text = source.readText()
        val start = text.indexOf("fun killCurrentProcess()")
        assertTrue("应能找到 killCurrentProcess", start > 0)
        val end = text.indexOf("private fun forceCloseProcess(", start)
        val body = text.substring(start, if (end > 0) end else text.length)
        assertTrue(
            "pgid 不可用且无路径可匹配时，必须用 su 的 pid 反查进程组",
            body.contains("forceKillProcessTree(targetProcess, targetPid, targetUsesRoot)")
        )
    }

    @Test fun `执行期临时文件必须在启动时清扫`() {
        val text = source.readText()
        val start = text.indexOf("private fun sweepExecutionTempFiles()")
        assertTrue("必须存在启动清扫入口", start > 0)
        val body = text.substring(start, minOf(text.length, start + 2600))
        assertTrue("两类临时文件都要清理", body.contains(".exec_attr_") && body.contains(".sh_norm_"))
        assertTrue("扫一次即可，避免误删运行中的临时文件", body.contains("compareAndSet(false, true)"))
        assertTrue("另存为残留无固定目录，必须靠登记表兜底", body.contains("sweepRegisteredSaveTemps()"))
    }

    @Test fun `另存为临时文件名识别不得漏收也不误收`() {
        // root: <目标目录>/.shso_edit_<uuid>.tmp；非 root: <目标目录>/.<目标名>.shso_<uuid>.tmp
        assertTrue(isSaveTempPath("/sdcard/Download/.shso_edit_abc.tmp"))
        assertTrue(isSaveTempPath("/sdcard/Download/.notes.txt.shso_abc.tmp"))
        assertTrue(!isSaveTempPath("/sdcard/Download/notes.txt"))
        assertTrue(!isSaveTempPath("/sdcard/Download/.hidden.shso_x"))
        assertTrue(!isSaveTempPath("/sdcard/Download/shsso_a.tmp"))
        assertTrue(!isSaveTempPath("relative/.shso_edit_a.tmp"))
        // 含换行会撕裂按行存储的登记表，必须排除
        assertTrue(!isSaveTempPath("/sdcard/a\n.shso_edit_x.tmp"))
    }

    @Test fun `另存为临时文件必须先登记后撤销`() {
        val text = source.readText()
        assertTrue("必须登记待清理路径", text.contains("fun recordSaveTemp("))
        assertTrue("替换/删除后必须撤销登记", text.contains("fun clearSaveTemp("))
        val dialog = File("src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt").readText()
        assertTrue("写入临时文件前必须登记", dialog.contains("RootService.recordSaveTemp("))
        // root 与非 root 两条通道、成功与失败各路径都必须撤销登记
        assertTrue(
            "撤销点应覆盖两条通道的全部出口，实际 ${Regex("RootService\\.clearSaveTemp\\(").findAll(dialog).count()}",
            Regex("RootService\\.clearSaveTemp\\(").findAll(dialog).count() >= 5
        )
    }

    @Test fun `写入 root 文件不得无界阻塞且必须 drain 错误通道`() {
        val text = source.readText()
        val start = text.indexOf("fun writeBytesAsRoot(")
        assertTrue("应能找到 writeBytesAsRoot", start > 0)
        val end = text.indexOf("fun executeFile(", start)
        val body = text.substring(start, if (end > 0) end else text.length)
        assertTrue(
            "stderr 并入从不读取的 stdout 后，cat 报错会写满管道并把子进程阻塞住",
            !body.contains("redirectErrorStream(true)")
        )
        assertTrue("必须单独 drain 错误通道", body.contains("errorStream"))
        assertTrue(
            "写入必须放独立线程并有界等待，否则 su 卡授权弹窗时 waitFor 的超时形同虚设",
            body.contains("writer.join(")
        )
    }

    @Test fun `组杀命令的三重校验不得被绕过`() {
        // 这条护栏保护的是「不误杀无关进程组」与「不漏杀成组孤儿」两个不可逆风险。
        assertTrue(
            "pgid 必须大于 1",
            RootServiceProcessGroupRules.rejectsNonPositivePgid
        )
        assertTrue(
            "必须校验该进程组仍有存活成员（而非组长存活）",
            RootServiceProcessGroupRules.checksLiveGroupMember
        )
        assertTrue(
            "必须校验本应用 pgrp 不等于目标 pgid",
            RootServiceProcessGroupRules.checksNotSelfGroup
        )
    }

    @Test fun `exec 收尾的权限还原不得阻塞主线程`() {
        // runCommandSync 是**同步阻塞**函数（ProcessBuilder su + waitFor），放在
        // Dispatchers.Main 上会让每次 .so 收尾都阻塞主线程，su 停在授权弹窗时最长卡满 10s（ANR 级）。
        val text = source.readText()
        val start = text.indexOf("val isCurrentJob = executionJob === myJob")
        assertTrue("应能找到 executeFile 的 finally", start > 0)
        val body = text.substring(start, minOf(text.length, start + 1400))
        val restoreAt = body.indexOf("chmod \$mode")
        assertTrue("finally 里必须有权限还原", restoreAt > 0)
        val guard = body.substring(0, restoreAt)
        assertTrue(
            "还原必须离开 Main（不能写在 Dispatchers.Main 的 withContext 内）",
            guard.contains("Dispatchers.IO")
        )
    }

    @Test fun `非 Root 任务必须经 setsid 自成进程组`() {
        // 不隔离时子进程沿用本应用自身进程组：组杀会连应用一起杀掉，于是「中断/结束」只能
        // 杀到外层 sh，内层脚本以应用 uid 继续跑（`sleep 600` 无尽的典型形态）。
        val text = source.readText()
        assertTrue(
            "非 Root 分支必须经 setsid 起进程",
            text.contains("ProcessBuilder(setsid.absolutePath, \"sh\", \"-c\", ptyCmd)")
        )
        assertTrue(
            "setsid 不可用（极老机型）时必须退回原路径而不是拒绝执行",
            text.contains("setsid.canExecute()")
        )
    }

    @Test fun `非 Root 任务的进程组由其自身 pid 直接确定`() {
        // setsid 后 sh 自任组长，pgid == 其 pid，无需等落盘回填；若仍去等文件回填，
        // 非 Root 从不写该文件，回填只会把刚设好的 pgid 冲成 0，组回收当场失效。
        val text = source.readText()
        assertTrue(
            "隔离成功时必须直接用直接子进程 pid 充当 pgid",
            text.contains("if (isolatedGroup) runPgid = childPid")
        )
        val backfill = text.substringAfter("仅 Root 需要回填")
        assertTrue(
            "回填必须限定在 Root 路径（非 Root 由 setsid 同步确定）",
            backfill.contains("if (useRoot) {") && backfill.indexOf("awaitRunPgid()") > backfill.indexOf("if (useRoot) {")
        )
    }

    @Test fun `非 Root 任务的 kill 不得经 su`() {
        // 同 uid 进程本就允许互发信号；走 su 只会平白弹授权框，弹框无人回应时 kill 停在
        // 超时窗口里，用户看到的是「点了结束进程但脚本还在跑」。
        val text = source.readText()
        assertTrue("runCommandSync 必须能按身份选择 su 或 sh", text.contains("runAsRoot: Boolean = true"))
        val kill = text.substringAfter("private fun runKillSync(").substringBefore("fun sendInterrupt()")
        assertTrue("kill 命令一律不得在超时时回跳整组回收", kill.contains("reclaimOnTimeout = false"))
        val end = text.indexOf("private fun forceCloseProcess(")
        val kills = text.substring(text.indexOf("fun killCurrentProcess()"), if (end > 0) end else text.length)
        assertTrue(
            "结束进程必须按捕获到的执行身份发信号",
            kills.contains("killProcessGroup(9, targetPgid, targetUsesRoot)") &&
                kills.contains("runKillSync(\"kill -9 \$targetPid 2>/dev/null\", targetUsesRoot)")
        )
        val interrupt = text.substringAfter("fun sendInterrupt()").substringBefore("fun restartTerminal()")
        assertTrue(
            "中断同样必须按执行身份发信号",
            interrupt.contains("killProcessGroup(2, targetPgid, targetUsesRoot)") &&
                interrupt.contains("runKillSync(\"kill -2 \$targetPid 2>/dev/null\", targetUsesRoot)")
        )
    }

    @Test fun `终端命令必须复位执行身份`() {
        // 终端恒经 su 执行；若沿用上一次「非 Root 执行」遗留的 false，
        // 中断/结束会走应用自身 uid 的 kill 通道，杀不到 root 名下的进程组。
        val text = source.readText()
        val start = text.indexOf("private suspend fun runTerminalCommand(")
        val end = text.indexOf("fun writeBytesAsRoot(", start)
        val body = text.substring(start, if (end > 0) end else text.length)
        assertTrue("终端起进程前必须把执行身份复位为 root", body.contains("execUsesRoot = true"))
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

    /**
     * ② 必须以「该组仍有存活成员」为判据：组长死后组 id 仍有效（成员保留 pgid），
     * 而组长先退出是常态。不得退回「组长存活」（`/proc/<pgid>/stat` 存在）。
     */
    val checksLiveGroupMember: Boolean
        get() {
            val cmd = buildProcessGroupKillCommand(9, 4242, 1234) ?: return false
            return cmd.contains("for d in /proc/[0-9]*") &&
                cmd.contains("[ \"\$3\" = \"\$P\" ]") &&
                cmd.contains("[ -n \"\$G\" ]") &&
                !cmd.contains("/proc/\$P/stat")
        }

    val checksNotSelfGroup: Boolean
        get() = buildProcessGroupKillCommand(9, 4242, 1234)
            // 读本应用 /proc/$M/stat 的 pgrp（先剥离 comm），并断言与目标 pgid 不等
            ?.contains("sed 's/.*) //' /proc/\$M/stat | cut -d' ' -f3)\" != \"\$P\"") == true
}
