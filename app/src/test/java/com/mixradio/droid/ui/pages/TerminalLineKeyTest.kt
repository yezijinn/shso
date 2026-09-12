// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.pages

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归守卫：终端 LazyColumn 的行 key 必须**只用行序号**。
 *
 * 背景：行 key 必须只用行序号派生，不能依赖行内容。
 * 终端中重复行极常见（空行 / 重复提示符 / 回显 / `\r` 原地覆盖产生的同文本行），
 * 若以行内容（hashCode）派生 key，相同内容会 key 重复，导致 LazyColumn 抛
 * `IllegalArgumentException("Key ... was already used")` 直接崩溃。
 */
class TerminalLineKeyTest {

    @Test
    fun `重复内容的行必须得到互不相同的 key`() {
        val dup = AnnotatedString("same")
        val lines = listOf(dup, dup, AnnotatedString("same"), AnnotatedString(""))
        val keys = lines.mapIndexed { i, line -> terminalLineKey(i, line) }
        assertEquals(lines.size, keys.toSet().size)
    }

    @Test
    fun `空行场景（内容 hashCode 相同）不得产生重复 key`() {
        // 同内容空行若以内容派生 key 会重复，触发崩溃；此处验证各行得到不同 key。
        val empty = AnnotatedString("")
        val keys = listOf(empty, empty, empty).mapIndexed { i, line -> terminalLineKey(i, line) }
        assertEquals(listOf(0, 1, 2), keys)
    }

    @Test
    fun `key 只依赖行序号，与行内容无关`() {
        assertEquals(
            terminalLineKey(3, AnnotatedString("alpha")),
            terminalLineKey(3, AnnotatedString("beta"))
        )
        assertEquals(
            terminalLineKey(0, AnnotatedString("")),
            terminalLineKey(0, AnnotatedString("\u001b[31mred\u001b[0m"))
        )
    }
}
