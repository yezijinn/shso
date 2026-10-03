// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data.security

/**
 * 安全体系共享模型：调用者身份、风险等级、风险项、判定结果、安全档位。
 * 设计依据《docs/指令审查与拦截方案.md》§3。
 */

/** 指令来源：按调用者身份分流，内部命令白名单放行保功能，用户输入走严格策略。 */
enum class CommandSource {
    /** App 内部按模板构造的命令（escapeShellArg 拼接，注入安全）：直接放行 */
    INTERNAL_APP,

    /** 用户在终端手敲：完整走策略，CONFIRM / BLOCK 生效 */
    USER_TERMINAL,

    /** 被执行的脚本文件内容：逐行扫描 + 按档位决定 Root / 非 Root */
    SCRIPT_FILE,

    /**
     * 用户在文件页发起的破坏性操作（删除 / 改名 / 移动 / 改权 / 改属 / 添加到 shso 时删源）。
     *
     * 此前这些操作被记成 [USER_TERMINAL]，审计日志里「谁触发的」信息是错的：
     * 事后无法区分是终端敲的还是文件页点的，追溯破坏来源时缺少最关键的一维。
     */
    FILE_MANAGER
}

/** 风险等级：SAFE < WARNING < DANGEROUS < CRITICAL */
enum class RiskLevel {
    SAFE, WARNING, DANGEROUS, CRITICAL
}

/**
 * 审计判定结果。原先是裸字符串，语义在调用点漂移：
 * `BLOCK` 既被用于「已拒绝执行」也被用于「放行但降级告警」（守卫不可用），
 * 事后看日志无法区分「被拦下」与「执行了但没守卫」。收敛成枚举后由类型保证。
 */
enum class AuditVerdict {
    /** 策略判定为放行。 */
    ALLOW,

    /** 用户已确认（文件页弹窗放行 / 终端确认框放行）。 */
    CONFIRMED,

    /** 被策略拒绝，未执行。 */
    DENIED,

    /**
     * 放行，但**降级执行**：本次执行发生时运行时守卫不可用，只有静态审查保护。
     * 与 [DENIED] 严格区分 —— 这是事后判断「这台设备当时有没有完整防护」的唯一依据。
     */
    DEGRADED,

    /** 执行开始（长任务的起始留痕）。 */
    START,

    /** 执行结束，detail 附退出码。 */
    FINISHED,

    /** 失败，detail 附原因。 */
    FAILED
}

/** 单条风险项（规则命中）。 */
data class Finding(
    /** 规则 ID，例 "RM_SYSTEM"、"DD_BLOCK_DEV" */
    val ruleId: String,
    val level: RiskLevel,
    /** 人话描述，例「递归删除系统分区」 */
    val message: String,
    /** 命中的原始片段（用于弹窗展示） */
    val snippet: String,
    /** 脚本扫描时的行号（1 起）；终端命令为 null */
    val line: Int? = null
)

/** 策略判定结果。 */
sealed interface Verdict {
    /** 放行（仅审计） */
    data object Allow : Verdict

    /** 需手动确认：展示风险项列表；CRITICAL 档需输入 EXECUTE */
    data class Confirm(val findings: List<Finding>, val level: RiskLevel) : Verdict

    /** 拒绝执行：给出原因并落审计 */
    data class Block(val findings: List<Finding>) : Verdict
}

/**
 * 安全档位。0 = 完全无防护（面向开发 / 高端用户）。
 *
 * **默认 0**：新安装或无有效持久化值时按「无防护」起步，不擅自替用户开启守卫安装与
 * 命令拦截；用户可在设置页主动切到 1/2/3。已保存的档位不迁移、不覆盖。
 */
object SecurityLevels {
    /** 无防护：不拦截、不审计。 */
    const val OFF = 0

    /** 仅审计：不拦截，所有用户动作落审计日志 */
    const val AUDIT_ONLY = 1

    /** 标准防护：硬规则拦截 + 高危弹窗确认 + 脚本扫描 + 守卫 PATH 前置 */
    const val STANDARD = 2

    /** 最强防护：标准防护基础上，脚本默认非 Root 执行 + CRITICAL 需输入 EXECUTE */
    const val MAXIMUM = 3

    fun nameOf(level: Int): String = when (level) {
        OFF -> "0 无防护"
        AUDIT_ONLY -> "1 仅审计"
        STANDARD -> "2 标准防护"
        MAXIMUM -> "3 最强防护"
        else -> "未知($level)"
    }

    fun isValid(level: Int): Boolean = level in OFF..MAXIMUM
}
