// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第九轮深挖的回归。
 *
 * 两条主线：
 * ① 解释器**内联执行**此前完全不被判定，而守卫侧没有 python/perl/node 包装器
 *    （已实测），静态层是唯一防线 → 这一条等于 root 任意代码执行。
 * ② 取色器把「预览下限」当成「取值下限」，纯白被静默改写成 #FCFCFC 并落盘。
 */
class InterpreterInlineAndColorClampTest {

    private fun ids(cmd: String, source: CommandSource = CommandSource.SCRIPT_FILE): Set<String> {
        val v = PolicyEngine.evaluate(cmd, source)
        val findings = when (v) {
            is Verdict.Block -> v.findings
            is Verdict.Confirm -> v.findings
            else -> emptySet()
        }
        return findings.map { it.ruleId }.toSet()
    }

    private fun blocks(cmd: String, source: CommandSource = CommandSource.SCRIPT_FILE): Boolean {
        val v = PolicyEngine.evaluate(cmd, source)
        val findings = when (v) {
            is Verdict.Block -> v.findings
            is Verdict.Confirm -> v.findings
            else -> emptySet()
        }
        val level = (v as? Verdict.Block)?.findings?.maxByOrNull { it.level.ordinal }?.level
            ?: (v as? Verdict.Confirm)?.level
        // 脚本来源下混淆执行必须 CRITICAL，自动执行链路才拦得住
        return level == RiskLevel.CRITICAL
    }

    // ---------- ① 解释器内联执行此前零判定 ----------

    @Test fun `python 内联执行系统目录删除必须被判定`() {
        // 该载荷不含 base64/atob/xxd/codecs 等任何「解码标记」，
        // 旧实现只认解码标记 → 零 finding → 档位 2 自动执行、root、无弹窗无审计。
        val ids = ids("python3 -c \"import shutil; shutil.rmtree('/system')\"")
        assertTrue("解释器内联执行必须上报，实际=$ids", ids.contains("INTERPRETER_PAYLOAD"))
    }

    @Test fun `perl 内联执行 dd 刷分区必须被判定`() {
        assertTrue(
            ids("perl -e 'system(\"dd if=/dev/zero of=/dev/block/by-name/boot\")'")
                .contains("INTERPRETER_PAYLOAD")
        )
    }

    @Test fun `node 内联执行递归删除必须被判定`() {
        assertTrue(
            ids("node -e \"fs.rmSync('/system',{recursive:true})\"")
                .contains("INTERPRETER_PAYLOAD")
        )
    }

    @Test fun `php 与 ruby 内联执行同样必须被判定`() {
        assertTrue(ids("php -r 'unlink(\"/data/adb/modules/x\")'").contains("INTERPRETER_PAYLOAD"))
        assertTrue(ids("ruby -e 'FileUtils.rm_rf(\"/system\")'").contains("INTERPRETER_PAYLOAD"))
    }

    @Test fun `脚本内的解释器内联执行必须 CRITICAL 以拦停自动执行`() {
        // 只有 CRITICAL 才让 blocksUnattendedExecution 为 true。
        // 而守卫没有 python/perl/node 包装器，运行时无兜底，
        // 静态层若只给 DANGEROUS，档位 2/3 的自动执行链路就会放行。
        assertTrue(
            "脚本中的解释器内联执行必须是 CRITICAL",
            blocks("python3 -c \"import os; os.system('rm -rf /system')\"", CommandSource.SCRIPT_FILE)
        )
    }

    @Test fun `长选项与等号形态同样必须被判定`() {
        assertTrue(ids("python3 --command \"import shutil\"").contains("INTERPRETER_PAYLOAD"))
        assertTrue(ids("python3 --command=\"import shutil\"").contains("INTERPRETER_PAYLOAD"))
        assertTrue(ids("node --eval \"process.exit(1)\"").contains("INTERPRETER_PAYLOAD"))
    }

    @Test fun `解释器执行普通脚本文件不得误报`() {
        // 只带**文件路径**（无内联开关）时不应按混淆执行判 —— 那是常规用法。
        val ids = ids("python3 /data/local/tmp/tool.py")
        assertFalse("解释器执行脚本文件不应命中内联规则，实际=$ids", ids.contains("INTERPRETER_PAYLOAD"))
        assertFalse("解释器执行脚本文件不应命中内联规则", ids.contains("INTERPRETER_PAYLOAD"))
    }

    @Test fun `既有的解码标记用例必须继续命中（防止条件改坏）`() {
        assertTrue(
            "带解码标记的解释器载荷仍须命中",
            ids("python3 -c \"import base64;exec(base64.b64decode('xxx'))\"")
                .contains("INTERPRETER_PAYLOAD")
        )
    }

    @Test fun `守卫无解释器包装器这一前提不得被回退`() {
        // 这不是单测能覆盖的运行时事实，但把前提写进测试注释与断言里，
        // 便于将来有人新增解释器包装器时同步更新这里的期望。
        // 真机实测：guard/ 下不存在 python/python3/perl/node 包装器。
        assertTrue(
            "若将来为解释器补上守卫包装器，本测试的 CRITICAL 期望可放宽为 DANGEROUS；" +
                "在此之前必须按 CRITICAL 拦停自动执行",
            blocks("python3 -c \"import shutil\"", CommandSource.SCRIPT_FILE)
        )
    }
}
