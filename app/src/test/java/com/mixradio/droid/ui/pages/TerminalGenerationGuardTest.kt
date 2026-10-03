// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端解析代次守卫的语义锁定。
 *
 * 代次用于判别「本次解析结果是否已被新一轮组合作废」。它必须满足：
 * 当前组合的代次与计数器**恒相等**（否则每次解析都判定自己过期，结果永不落地），
 * 且每次重新组合后严格递增（否则旧协程的结果会污染新组合）。
 */
class TerminalGenerationGuardTest {

    @Test
    fun `后置自增会让守卫恒为假`() {
        // 反例：`p[0]++` 的值是自增前的旧值，写进 p[0] 的是新值，
        // 于是 myGen 恒等于 p[0] - 1，`p[0] == myGen` 永不成立。
        val p = intArrayOf(0)
        val myGen = p[0]++
        assertNotEquals("后置自增导致 myGen 与计数器不相等", p[0], myGen)
    }

    @Test
    fun `前置自增使守卫在当前组合内恒成立`() {
        val p = intArrayOf(0)
        val myGen = ++p[0]
        assertEquals("首次组合：计数器应等于本组合代次", myGen, p[0])
    }

    @Test
    fun `重新组合后代次严格递增且旧代次立即失效`() {
        val p = intArrayOf(0)
        val gen1 = ++p[0]
        assertEquals(gen1, p[0])

        // 新组合（换色 / 离进组合）重新求值，代次必须大于上一轮
        val gen2 = ++p[0]
        assertEquals(gen2, p[0])
        assertTrue("新代次必须严格递增", gen2 > gen1)
        assertNotEquals("旧协程持有的代次必须立即判为过期", gen1, p[0])
    }

    @Test
    fun `连续多次组合不会让任一组合的守卫失效`() {
        val p = intArrayOf(0)
        val gens = (1..5).map { ++p[0] }
        assertEquals("代次应连续无缺口", listOf(1, 2, 3, 4, 5), gens)
        gens.forEachIndexed { index, g ->
            // 只有最后一次组合的代次仍与计数器相等，先前的均已过期
            assertEquals(index == gens.lastIndex, g == p[0])
        }
    }
}
