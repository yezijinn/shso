// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import com.mixradio.droid.data.RootFileManager
import com.mixradio.droid.ui.components.needTypedExecuteConfirm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全档位（0 关 / 1 仅审计 / 2 标准 / 3 最强）各处**语义**的回归锁。
 *
 * 这些断言锁定的都是曾经真实出错的点：
 * - 档位 3 的「输入 EXECUTE」曾被错误地套用到档位 2（`scanEnabled` 判定），使默认档位强制打字；
 * - 档位 2 在守卫缺失时曾**拒绝一切 root 执行**（连 `ls` 都不行），使默认档位不可用；
 * - `RootFileManager` 的删除/移动/改权曾**完全不过策略、不落审计**，四档位行为一致；
 * - 档位切换后守卫 `policy.conf` 的 `mode` 与 App 档位可能长期不一致。
 */
class SecurityTierSemanticsTest {

    // ───────────────────────── 守卫 PATH 注入 ─────────────────────────

    @Test
    fun `档位 0 与 1 不注入守卫 PATH（返回空串而非 null）`() {
        // 档位 <2 明确表示「不拦截」，返回空串即「行为与旧版一致」；
        // 返回 null 会被语义化为「守卫不可用」，触发无谓的降级告警。
        assertEquals("", GuardPathPolicy.prefixOrNull(SecurityLevels.OFF, guardReady = false))
        assertEquals("", GuardPathPolicy.prefixOrNull(SecurityLevels.OFF, guardReady = true))
        assertEquals("", GuardPathPolicy.prefixOrNull(SecurityLevels.AUDIT_ONLY, guardReady = false))
        assertEquals("", GuardPathPolicy.prefixOrNull(SecurityLevels.AUDIT_ONLY, guardReady = true))
    }

    @Test
    fun `档位 2 及以上守卫未就绪时返回 null（表示不可用，供调用方降级告警）`() {
        assertNull(GuardPathPolicy.prefixOrNull(SecurityLevels.STANDARD, guardReady = false))
        assertNull(GuardPathPolicy.prefixOrNull(SecurityLevels.MAXIMUM, guardReady = false))
    }

    @Test
    fun `档位 2 及以上守卫就绪时注入守卫目录到 PATH 最前`() {
        for (level in listOf(SecurityLevels.STANDARD, SecurityLevels.MAXIMUM)) {
            val prefix = GuardPathPolicy.prefixOrNull(level, guardReady = true)
            assertNotNull("档位 $level 就绪时应注入前缀", prefix)
            // 必须是 export PATH=<守卫目录>:<系统目录> && 的形式，且守卫目录在最前
            assertTrue(prefix!!.startsWith("export PATH=${GuardModuleInstaller.GUARD_BIN_DIR}:"))
            assertTrue(prefix.contains("/system/bin"))
            assertTrue(prefix.endsWith(" && "))
        }
    }

    @Test
    fun `守卫目录位于模块安装路径下`() {
        assertEquals(
            "/data/adb/modules/shso_guard/guard",
            GuardModuleInstaller.GUARD_BIN_DIR
        )
    }

    // ───────────────────────── 守卫自动安装时机 ─────────────────────────

    @Test
    fun `仅在档位 2 及以上才需要安装运行时守卫`() {
        // 档位 0/1 明确表示「不拦截」，安装守卫违反档位语义
        assertFalse(GuardModuleInstaller.requiresRuntimeGuard(SecurityLevels.OFF))
        assertFalse(GuardModuleInstaller.requiresRuntimeGuard(SecurityLevels.AUDIT_ONLY))
        assertTrue(GuardModuleInstaller.requiresRuntimeGuard(SecurityLevels.STANDARD))
        assertTrue(GuardModuleInstaller.requiresRuntimeGuard(SecurityLevels.MAXIMUM))
    }

    // ───────────────────────── 档位 → policy.conf mode ─────────────────────────

    @Test
    fun `档位映射到守卫 mode`() {
        assertEquals("off", GuardModuleInstaller.policyModeFor(SecurityLevels.OFF))
        assertEquals("log", GuardModuleInstaller.policyModeFor(SecurityLevels.AUDIT_ONLY))
        assertEquals("enforce", GuardModuleInstaller.policyModeFor(SecurityLevels.STANDARD))
        assertEquals("enforce", GuardModuleInstaller.policyModeFor(SecurityLevels.MAXIMUM))
    }

    @Test
    fun `越界或未知档位按最严格处理（fail-closed）`() {
        assertEquals("enforce", GuardModuleInstaller.policyModeFor(-1))
        assertEquals("enforce", GuardModuleInstaller.policyModeFor(4))
        assertEquals("enforce", GuardModuleInstaller.policyModeFor(Int.MAX_VALUE))
    }

    // ───────────────────────── EXECUTE 闸门档位 ─────────────────────────

    @Test
    fun `输入 EXECUTE 属档位 3 专属能力`() {
        // 档位 3 + CRITICAL → 必须打字
        assertTrue(needTypedExecuteConfirm(SecurityLevels.MAXIMUM, hasCritical = true))
        // 档位 2 + CRITICAL → 普通确认即可（回归锁：曾被 scanEnabled 误判为需打字）
        assertFalse(needTypedExecuteConfirm(SecurityLevels.STANDARD, hasCritical = true))
        assertFalse(needTypedExecuteConfirm(SecurityLevels.AUDIT_ONLY, hasCritical = true))
        assertFalse(needTypedExecuteConfirm(SecurityLevels.OFF, hasCritical = true))
    }

    @Test
    fun `无 CRITICAL 项时任何档位都不需要打字`() {
        for (level in 0..3) {
            assertFalse("档位 $level 无 CRITICAL 时不应要求打字", needTypedExecuteConfirm(level, false))
        }
    }

    // ───────────────────────── 文件管理危险操作门禁 ─────────────────────────

    @Test
    fun `文件管理危险操作在档位 0 不判定不审计`() {
        // 档位 0 = 无防护，须与改造前逐字节一致（不判定、不落审计）
        assertFalse(RootFileManager.shouldGuardFileOp(SecurityLevels.OFF))
    }

    @Test
    fun `文件管理危险操作在档位 1 及以上进入门禁`() {
        // 档位 1 的判定恒为 Allow，但**必须落审计**，故 1 也要进门禁
        assertTrue(RootFileManager.shouldGuardFileOp(SecurityLevels.AUDIT_ONLY))
        assertTrue(RootFileManager.shouldGuardFileOp(SecurityLevels.STANDARD))
        assertTrue(RootFileManager.shouldGuardFileOp(SecurityLevels.MAXIMUM))
    }

    // ───────────────────────── 档位常量自身一致性 ─────────────────────────

    @Test
    fun `档位常量取值与文档一致且可循环`() {
        assertEquals(0, SecurityLevels.OFF)
        assertEquals(1, SecurityLevels.AUDIT_ONLY)
        assertEquals(2, SecurityLevels.STANDARD)
        assertEquals(3, SecurityLevels.MAXIMUM)
        // 设置页用 (level + 1) % 4 循环，全部档位必须落在 0..3
        for (level in 0..3) assertTrue(((level + 1) % 4) in 0..3)
    }
}
