// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三轮深挖的安全回归：命令替换绕过、未设防的「拆防护体系」原语、pkill 正则注入。
 */
class SubstitutionAndTamperGuardTest {

    private fun verdictOf(command: String): Verdict =
        PolicyEngine.evaluateParsed(CommandParser.parse(command), CommandSource.USER_TERMINAL)

    private fun ruleIds(command: String): List<String> = when (val v = verdictOf(command)) {
        is Verdict.Block -> v.findings.map { it.ruleId }
        is Verdict.Confirm -> v.findings.map { it.ruleId }
        Verdict.Allow -> emptyList()
    }

    private fun levelOf(command: String): RiskLevel? = when (val v = verdictOf(command)) {
        is Verdict.Block -> v.findings.maxByOrNull { it.level.ordinal }?.level
        is Verdict.Confirm -> v.level
        Verdict.Allow -> null
    }

    // ---------- ① 命令替换必须参与外层操作数分级 ----------

    @Test fun `rsync 目标里的命令替换不能绕过系统分区判定`() {
        // 真实缺陷：$(...) 的产物被单独解析成独立原子，外层不留任何标记，
        // 于是 rsync 的操作数只剩 [/data/local/tmp/x, /bin/]，末位不受保护 → 放行，
        // 而内层 echo 的 /system 从不作为 rsync 的目标参与分级 → 静默写入系统分区。
        val plain = ruleIds("rsync -a /data/local/tmp/x /system/bin/")
        assertTrue("对照组：直写系统分区必须命中", plain.contains("COPY_SYSTEM"))

        val ids = ruleIds("rsync -a /data/local/tmp/x " + DOLLAR + "(echo /system)/bin/")
        assertTrue(
            "命令替换后的目标不可静态判定，必须升级判定：$ids",
            ids.contains("UNRESOLVED_DESTRUCTIVE_CONFIRM") || ids.contains("COPY_SYSTEM")
        )
    }

    @Test fun `反引号替换同样必须参与外层分级`() {
        val tick = "`"
        val ids = ruleIds("cp -a /data/local/tmp/x " + tick + "echo /system" + tick + "/bin/f")
        assertTrue(
            "反引号替换与命令替换同口径：$ids",
            ids.contains("UNRESOLVED_DESTRUCTIVE_CONFIRM") || ids.contains("COPY_SYSTEM")
        )
    }

    @Test fun `rm 目标里的命令替换不能绕过`() {
        val ids = ruleIds("/system/bin/rm -rf " + DOLLAR + "(echo /data)/misc")
        assertTrue("rm 的替换目标必须被判高危：$ids", ids.isNotEmpty())
    }

    @Test fun `合法的不含替换的 cp 仍按原口径判定`() {
        val ids = ruleIds("cp /sdcard/a /sdcard/b")
        assertTrue("普通 cp 到 sdcard 不应被误判：$ids", ids.none { it == "COPY_SYSTEM" })
    }

    // ---------- ② 拆掉防护体系本身的原语 ----------

    @Test fun `setenforce 被识别为 CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, levelOf("setenforce 0"))
    }

    @Test fun `resetprop 被识别为 CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, levelOf("resetprop ro.debuggable 1"))
    }

    @Test fun `magisk 删除模块被识别为 CRITICAL`() {
        val ids = ruleIds("magisk --remove-modules")
        assertTrue("卸载 Magisk 模块（含守卫本身）必须命中：$ids", ids.contains("MAGISK_TAMPER"))
        assertEquals(RiskLevel.CRITICAL, levelOf("magisk --remove-modules"))
    }

    @Test fun `magisk 只读查询不被误杀`() {
        val ids = ruleIds("magisk -v")
        assertTrue("magisk 版本查询属只读，不应产生拆防护发现：$ids", ids.none { it == "MAGISK_TAMPER" })
    }

    @Test fun `系统分区重挂为可写被识别为 CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, levelOf("mount -o remount,rw /system"))
    }

    @Test fun `bind 挂载覆盖系统目录被识别为 CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, levelOf("mount -o bind /data/local/tmp /system/app"))
    }

    @Test fun `普通挂载不产生 CRITICAL`() {
        assertFalse(levelOf("mount -t tmpfs tmpfs /mnt") == RiskLevel.CRITICAL)
    }

    // ---------- ③ pkill 的 ERE 字面量转义 ----------

    @Test fun `pkill 模式转义 ERE 元字符`() {
        // 路径里的 . 会被 pkill -f 当成「任意字符」：v1.2.sh 会一并命中 v1X2yzh，
        // 而这条兜底路径以 root 执行，误杀的是任意 uid 的进程。
        val out = ShellEscapes.escapeEreLiteral("/data/adb/shso/v1.2.sh")
        assertEquals("/data/adb/shso/v1" + BS + ".2" + BS + ".sh", out)
    }

    @Test fun `ERE 转义覆盖全部元字符`() {
        // 不含 / 与 -：两者在 ERE 括号表达式外本身即字面量，转义它们依赖未定义行为
        val meta = listOf(".", "*", "+", "?", "(", ")", "[", "]", "{", "}", "|", "^", DOLLAR, BS)
        val literal = "a" + meta.joinToString("") { it + "b" }
        val out = ShellEscapes.escapeEreLiteral(literal)
        for (ch in meta) {
            assertTrue("元字符 $ch 必须被转义：$out", out.contains(BS + ch))
        }
    }

    @Test fun `ERE 转义不处理斜杠与连字符`() {
        // 路径里 '-' 极常见；`\/` `\‑` 属未定义转义，部分实现会拒绝该模式。
        // 注意 '.' 仍需转义，故 sh-so/v1.sh → sh-so/v1\.sh
        assertEquals(
            "/data/adb/sh-so/v1" + BS + ".sh",
            ShellEscapes.escapeEreLiteral("/data/adb/sh-so/v1.sh")
        )
    }

    @Test fun `ERE 转义保留普通字符不变`() {
        assertEquals("abc123", ShellEscapes.escapeEreLiteral("abc123"))
    }

    private companion object {
        const val DOLLAR = "$"
        const val BS = "\\"
    }
}
