// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

/**
 * RootCommandGateway（方案 §3）：所有用户可见指令的统一判定入口。
 *
 * 档位门控：
 * - 0 无防护 / 1 仅审计 → 一律 Allow（1 档的留痕由执行侧记录）；
 * - 2 标准防护 / 3 最强防护 → PolicyEngine 完整判定。
 *
 * fail-closed：策略层任何异常按 CRITICAL Confirm 处理，绝不静默放行。
 */
object RootCommandGateway {

    /** 按档位评估命令。内部命令（INTERNAL_APP）恒放行。 */
    fun check(command: String, source: CommandSource): Verdict {
        val level = PolicyEngine.currentLevel()
        if (source == CommandSource.INTERNAL_APP) return Verdict.Allow
        if (level <= SecurityLevels.AUDIT_ONLY) return Verdict.Allow
        return try {
            PolicyEngine.evaluate(command, source)
        } catch (_: Exception) {
            // fail-closed：异常 → CRITICAL 人工确认
            Verdict.Confirm(
                listOf(
                    Finding(
                        ruleId = "POLICY_ERROR",
                        level = RiskLevel.CRITICAL,
                        message = "策略引擎内部异常，需人工确认（fail-closed）",
                        snippet = command.take(200)
                    )
                ),
                RiskLevel.CRITICAL
            )
        }
    }

    /**
     * 终端交互态（任务运行中，输入直写常驻 shell）的硬规则检查：
     * 只拦 CRITICAL 级硬规则（rm 系统 / dd 块设备 / mkfs / wipe / fastboot erase / chmod -R 系统），
     * Confirm 级不弹窗（交互程序在等待输入，弹窗会破坏交互）。
     */
    fun checkInteractiveHardRules(text: String): Verdict.Block? {
        val level = PolicyEngine.currentLevel()
        if (level <= SecurityLevels.AUDIT_ONLY) return null
        return checkInteractiveHardRulesWith(text, PolicyEngine::evaluate)
    }

    fun checkInteractiveHardRulesWith(
        text: String,
        evaluate: (String, CommandSource) -> Verdict
    ): Verdict.Block? = try {
        when (val verdict = evaluate(text, CommandSource.USER_TERMINAL)) {
            is Verdict.Block -> verdict
            else -> null
        }
    } catch (_: Exception) {
        Verdict.Block(
            listOf(
                Finding(
                    ruleId = "POLICY_ERROR",
                    level = RiskLevel.CRITICAL,
                    message = "策略引擎内部异常，已阻止交互输入（fail-closed）",
                    snippet = text.take(200)
                )
            )
        )
    }
}
