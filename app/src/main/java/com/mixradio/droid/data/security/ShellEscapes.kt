// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data.security

/**
 * 纯字符串转义工具。**不得依赖任何 Android 类型** ——
 * [com.mixradio.droid.data.RootService] 的静态初始化会碰 Android，故此类功能放这里，
 * JVM 单测可直接加载。
 */
internal object ShellEscapes {

    /**
     * Shell 参数白名单：仅字母/数字/下划线/点/斜杠/连字符/冒号可原样拼接。
     *
     * 这些字符在 shell 中都是字面量，含其一的路径（`/data/data/<pkg>/files/env/bin`）
     * 不必加引号；一旦出现空格、`$`、`*`、`;` 等字符就必须走单引号分支，
     * 否则会被拆词或解释成通配、变量、命令分隔符 —— 命令注入的入口。
     */
    private val SAFE_ARG = Regex("^[a-zA-Z0-9_./\\-:]+$")

    /**
     * 生成可直接拼入 shell 命令的参数。
     *
     * 白名单命中即原样返回；否则整体用单引号包裹，内部单引号按 `'\''` 收尾重开，
     * 这是 POSIX shell 中唯一无歧义的转义方式（反斜杠在单引号内不生效）。
     * 空串一律返回 `''`：不引用的空参数会**消失**，导致后续参数整体左移。
     */
    fun escapeShellArg(arg: String): String {
        if (arg.isEmpty()) return "''"
        return if (SAFE_ARG.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * 生成 `pkill -f` 可用的**字面量**匹配串。
     *
     * `escapeShellArg` 只防 shell 解释，对 `pkill -f` 无效 —— 后者把参数当**扩展正则**（ERE）
     * 匹配整条 cmdline。于是路径里的正则元字符会被解释：`/data/adb/shso/v1.2.sh` 的 `.`
     * 匹配任意字符，能一并命中 `v1X2yzh`；`( ) [ ] * + ? | ^ $ \` 同理。
     *
     * 收尾回收的兜底路径以 root 身份执行（`su -c pkill -9 …`），误杀的是任意 uid 的进程：
     * 被杀的是别的应用或系统服务时表现为第三方崩溃 / 系统组件重启，而非本应用的任务回收；
     * `2>/dev/null` 又把 pkill 的诊断输出吞掉，日志与用户都看不到误杀。
     *
     * 转义集**不含** `/` 与 `-`：两者在 ERE 括号表达式之外本身就是字面量，
     * 转义成 `\/` `-` 属于「转义非特殊字符」，POSIX 未定义其行为（部分实现会拒绝该模式），
     * 不如保持原样。路径里出现 `-` 极常见，误伤面比收益大得多。
     */
    fun escapeEreLiteral(literal: String): String {
        val sb = StringBuilder(literal.length + 8)
        for (ch in literal) {
            if ("\\^$.|?*+()[]{}".indexOf(ch) >= 0) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }
}
