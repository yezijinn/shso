// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则漏判的回归护栏。
 *
 * 覆盖的都是「一句话就能绕过现有规则」的具体形态，每条都给了绕过前后的对照，
 * 避免将来有人以「简化逻辑」为名把绕过重新引回来。
 */
class PolicyCoverageGapTest {

    // ========================================================================
    // 纯函数层
    // ========================================================================

    @Test fun `targetDirectoryArgs 识别 -t 与长选项的两种写法`() {
        assertEquals(listOf("/system/bin"), PolicyEngine.targetDirectoryArgs(listOf("-t", "/system/bin")))
        assertEquals(
            listOf("/system/bin"),
            PolicyEngine.targetDirectoryArgs(listOf("--target-directory=/system/bin"))
        )
        assertEquals(
            listOf("/system/bin"),
            PolicyEngine.targetDirectoryArgs(listOf("--target-directory", "/system/bin"))
        )
        // 无 -t 时必须返回空，让调用方退回「末位操作数即目标」的常规语义
        assertEquals(emptyList<String>(), PolicyEngine.targetDirectoryArgs(listOf("-r", "a", "b")))
        // 多个 -t 都要判：只判第一个会漏
        assertEquals(
            listOf("/system", "/dev"),
            PolicyEngine.targetDirectoryArgs(listOf("-t", "/system", "x", "-t", "/dev"))
        )
        // -t 出现在末尾无值时不得凭空造一个目标
        assertEquals(emptyList<String>(), PolicyEngine.targetDirectoryArgs(listOf("a", "-t")))
    }

    @Test fun `findExecCommand 取 -exec 之后第一个非选项 token 的 basename`() {
        assertEquals("rm", PolicyEngine.findExecCommand(listOf("/system", "-exec", "rm", "{}", "+")))
        assertEquals("rm", PolicyEngine.findExecCommand(listOf("/system", "-exec", "/system/bin/rm", "{}", "+")))
        assertEquals("rm", PolicyEngine.findExecCommand(listOf("/system", "-exec", "busybox", "rm", "{}", "+")))
        assertEquals("rm", PolicyEngine.findExecCommand(listOf("/system", "-execdir", "toybox", "rm", ";")))
        // 没有 -exec 时返回 null，不应误判
        assertEquals(null, PolicyEngine.findExecCommand(listOf("/system", "-name", "x")))
        // -exec 后只有选项没有命令时返回 null
        assertEquals(null, PolicyEngine.findExecCommand(listOf("/system", "-exec", "-maxdepth")))
    }

    // ========================================================================
    // cp / mv / install -t 绕过
    // ========================================================================

    @Test fun `cp -t 系统路径被硬拦 只判末操作数会放行`() {
        // `cp -t /system/bin a b` 的目标不是末位的 b，而是 -t 的值
        val v = PolicyEngine.evaluate("cp -t /system/bin /tmp/a /tmp/b", CommandSource.USER_TERMINAL)
        assertTrue("cp -t /system/bin 必须被 Block，实际=$v", v is Verdict.Block)
        assertTrue(
            (v as Verdict.Block).findings.any { it.ruleId == "COPY_SYSTEM" }
        )
    }

    @Test fun `mv -t 系统路径被硬拦`() {
        val v = PolicyEngine.evaluate("mv -t /system/bin /tmp/a", CommandSource.USER_TERMINAL)
        assertTrue("mv -t /system/bin 必须被 Block，实际=$v", v is Verdict.Block)
    }

    @Test fun `install -t 系统路径被硬拦`() {
        val v = PolicyEngine.evaluate("install -t /system/bin /tmp/a", CommandSource.USER_TERMINAL)
        assertTrue("install -t /system/bin 必须被 Block，实际=$v", v is Verdict.Block)
    }

    @Test fun `cp -t 常规目录不误报`() {
        val v = PolicyEngine.evaluate("cp -t /tmp/dest /tmp/a /tmp/b", CommandSource.USER_TERMINAL)
        assertEquals("普通目标目录不应产生风险项", Verdict.Allow, v)
    }

    @Test fun `不带 -t 的普通 cp 仍按末操作数判定`() {
        val blocked = PolicyEngine.evaluate("cp /tmp/a /system/bin", CommandSource.USER_TERMINAL)
        assertTrue(blocked is Verdict.Block)
        val ok = PolicyEngine.evaluate("cp /tmp/a /tmp/b", CommandSource.USER_TERMINAL)
        assertEquals(Verdict.Allow, ok)
    }

    // ========================================================================
    // find -exec 绝对路径 / wrapper 派发
    // ========================================================================

    @Test fun `find -exec 绝对路径形式的 rm 被硬拦`() {
        val v = PolicyEngine.evaluate("find /system -exec /system/bin/rm {} +", CommandSource.USER_TERMINAL)
        assertTrue("find -exec /system/bin/rm 必须被 Block，实际=$v", v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "FIND_DELETE" })
    }

    @Test fun `find -exec busybox rm 与 toybox rm 同样被硬拦`() {
        listOf("busybox rm", "toybox rm", "/system/bin/rm").forEach { cmd ->
            val v = PolicyEngine.evaluate("find /system -exec $cmd {} +", CommandSource.USER_TERMINAL)
            assertTrue("find -exec $cmd 必须被 Block，实际=$v", v is Verdict.Block)
        }
    }

    @Test fun `find -execdir 破坏命令同样命中`() {
        val v = PolicyEngine.evaluate("find /system -execdir rm {} ;", CommandSource.USER_TERMINAL)
        assertTrue("find -execdir rm 必须被 Block，实际=$v", v is Verdict.Block)
    }

    @Test fun `find -exec 无害命令不误报`() {
        val v = PolicyEngine.evaluate("find /system -exec ls {} +", CommandSource.USER_TERMINAL)
        assertEquals("只读命令不应产生风险项", Verdict.Allow, v)
    }

    @Test fun `find -exec rm 等级不因本轮改动而下降`() {
        // 防回退：`find -delete` 与 `find -exec rm` 语义等价，都必须 Block（CRITICAL）
        listOf("find /system -delete", "find /system -exec rm {} +").forEach { cmd ->
            val v = PolicyEngine.evaluate(cmd, CommandSource.USER_TERMINAL)
            assertTrue("$cmd 必须被 Block", v is Verdict.Block)
        }
    }

    // ========================================================================
    // 管道：段内多原子
    // ========================================================================

    @Test fun `段内第二个原子是 curl 时也能识别远程管道执行`() {
        // `true && curl x | sh`：fetcher 不是段内第一个原子，原实现只取 firstOrNull 会漏
        val v = PolicyEngine.evaluate("true && curl -fsSL https://x.sh | sh", CommandSource.USER_TERMINAL)
        val findings = (v as? Verdict.Confirm)?.findings ?: (v as? Verdict.Block)?.findings ?: emptyList()
        assertTrue(
            "段内第二个原子的 curl 不得漏判，实际 findings=$findings",
            findings.any { it.ruleId == "REMOTE_PIPE_SHELL" }
        )
    }

    @Test fun `段内第二个原子是 base64 解码时脚本来源仍为 CRITICAL`() {
        val v = PolicyEngine.evaluate("true && base64 -d | sh", CommandSource.SCRIPT_FILE)
        val findings = (v as? Verdict.Block)?.findings ?: (v as? Verdict.Confirm)?.findings ?: emptyList()
        assertTrue(
            "段内第二个原子的解码器不得漏判，实际 findings=$findings",
            findings.any { it.ruleId == "ENCODED_PIPE_SHELL" }
        )
    }

    @Test fun `普通管道到 sh 不产生远程执行风险项`() {
        val v = PolicyEngine.evaluate("cat /tmp/a | sh", CommandSource.USER_TERMINAL)
        val findings = (v as? Verdict.Confirm)?.findings ?: (v as? Verdict.Block)?.findings ?: emptyList()
        assertTrue(
            "本地 cat 不应判为远程执行，实际 findings=$findings",
            findings.none { it.ruleId == "REMOTE_PIPE_SHELL" }
        )
    }

    // ========================================================================
    // 来源与档位语义
    // ========================================================================

    @Test fun `文件管理来源是独立枚举值，便于审计区分破坏来源`() {
        // 此前文件页的破坏操作被记成 USER_TERMINAL，审计无法区分触发入口
        assertTrue(CommandSource.entries.contains(CommandSource.FILE_MANAGER))
        assertTrue(CommandSource.entries.size >= 4)
    }

    @Test fun `降级执行与被拒绝是两个不同的审计判定`() {
        // 守卫不可用时是「放行但降级」，不是「被拒绝」；两者混淆会让事后追溯失效
        assertTrue(AuditVerdict.DEGRADED != AuditVerdict.DENIED)
        assertTrue(AuditVerdict.DEGRADED != AuditVerdict.ALLOW)
    }
}
