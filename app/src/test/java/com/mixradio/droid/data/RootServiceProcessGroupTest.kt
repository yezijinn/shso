// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：进程组 kill 命令的**安全校验**必须保留。
 *
 * 背景：`su -c` 把命令放进新会话，组长被重挂到 init，
 * 只杀直接子进程会留下孤儿继续占 CPU。改为对整进程组发信号后，
 * 一旦校验被删掉，伪造/陈旧的 pgid 就可能以 root 身份误杀无关进程组（甚至包含本应用的组）。
 */
class RootServiceProcessGroupTest {

    @Test
    fun `pgid 非法时拒绝构造 kill 命令`() {
        assertNull(buildProcessGroupKillCommand(9, 0, 1234))
        assertNull(buildProcessGroupKillCommand(9, 1, 1234))
        assertNull(buildProcessGroupKillCommand(2, -5, 1234))
    }

    @Test
    fun `合法 pgid 生成带三重校验的负 pid kill`() {
        val cmd = buildProcessGroupKillCommand(2, 10700, 1234)
        val expected = "P=10700; M=1234; " +
            "G=; for d in /proc/[0-9]*; do read -r L < \"\$d/stat\" 2>/dev/null; " +
            "L=\${L##*) }; set -- \$L; [ \"\$3\" = \"\$P\" ] && G=1; [ -n \"\$G\" ] && break; done; " +
            "[ -n \"\$G\" ] && " +
            "[ \"\$(sed 's/.*) //' /proc/\$M/stat | cut -d' ' -f3)\" != \"\$P\" ] && " +
            "kill -2 -- -\$P"
        // 精确等值：锁定三重校验（组成员存活 / 不误伤自身组 / pgid>1），也锁定必须用负 pid 打整组
        assertEquals(expected, cmd)
        // comm 含空格/右括号时不能按固定字段号取 pgrp（否则字段错位，把 state 当 pgrp）
        assertFalse("不得按空格固定取第 5 字段", cmd?.contains("cut -d' ' -f5") == true)
        // 不得依赖 ps 的 PGID 字段：Android toybox 该字段恒为 0（真机实测），据此判定会全部 fail-closed
        assertFalse("不得依赖 ps -o PGID", cmd?.contains("ps -") == true)
        // 不得依赖 awk：Android 默认不带 awk
        assertFalse("不得依赖 awk", cmd?.contains("awk") == true)
    }

    @Test
    fun `组成员存活校验覆盖组长已死的场景`() {
        val cmd = buildProcessGroupKillCommand(9, 4242, 1234)!!
        // 组长先退出（SIGINT 先打组长、`… & exit 0` 令组长立即退出）是常态：
        // 若仍以「/proc/<pgid>/stat 存在」为判据，整组回收会被短路成 no-op，留下 root 孤儿。
        assertFalse("不得退回「组长存活」判据", cmd.contains("/proc/\$P/stat"))
        assertTrue("必须以扫描 pgrp == P 的进程为判据", cmd.contains("[ \"\$3\" = \"\$P\" ]"))
    }

    @Test
    fun `信号量按入参生成`() {
        assertTrue(buildProcessGroupKillCommand(9, 555, 4242)!!.contains("kill -9 -- -"))
    }
}
