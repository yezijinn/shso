// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
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
            "[ -r /proc/\$P/stat ] && " +
            "[ \"\$(cut -d' ' -f5 /proc/\$P/stat)\" = \"\$P\" ] && " +
            "[ \"\$(cut -d' ' -f5 /proc/\$M/stat)\" != \"\$P\" ] && " +
            "kill -2 -- -\$P"
        // 精确等值：既锁定三重校验（组长校验 / 不误伤自身组 / pgid>1），也锁定必须用负 pid 打整组
        assertEquals(expected, cmd)
    }

    @Test
    fun `信号量按入参生成`() {
        assertTrue(buildProcessGroupKillCommand(9, 555, 4242)!!.contains("kill -9 -- -"))
    }
}
