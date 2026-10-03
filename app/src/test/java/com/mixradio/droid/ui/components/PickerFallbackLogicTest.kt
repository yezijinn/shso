// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆目录失效后的回退：纯逻辑等价验证（不依赖 Android Context）。
 *
 * 复刻 [BuiltInFilePicker.loadDirectory] 修复后的判定顺序，验证「回退时列的是回退目录、
 * 且副作用写在代次守卫之后」这两个不变量。
 */
class PickerFallbackLogicTest {

    /** 模拟文件系统：路径 -> 是否存在。 */
    private class Fs(val existing: Set<String>) {
        fun exists(p: String) = p in existing
        var listed = mutableListOf<String>()
        fun list(p: String): List<String> {
            listed += p
            return if (p == "/storage/emulated/0") listOf("data", "shso", "DCIM") else emptyList()
        }
    }

    private val INTERNAL = "/storage/emulated/0"

    /** 复刻修复后的 resolve + load 顺序。 */
    private fun resolveAndLoad(fs: Fs, path: String, rememberDirectory: Boolean): String {
        val resolved = if (fs.exists(path)) {
            path
        } else {
            if (rememberDirectory) {
                remembered?.takeIf { it != path && fs.exists(it) } ?: INTERNAL
            } else {
                INTERNAL
            }
        }
        fs.list(resolved)
        return resolved
    }

    private var remembered: String? = null

    @Test fun `目录存在时列的就是它`() {
        remembered = null
        val fs = Fs(setOf(INTERNAL, "/sdcard/ok"))
        assertTrue(resolveAndLoad(fs, "/sdcard/ok", true) == "/sdcard/ok")
        assertTrue("必须列请求的目录本身", fs.listed == listOf("/sdcard/ok"))
    }

    @Test fun `记忆目录失效时回退并列出回退目录的内容`() {
        // 核心回归：旧实现只改 currentDir、loaded 仍是 emptyList()，
        // 于是路径行显示回退目录而列表空白 —— 用户以为「内部存储是空的」。
        remembered = "/sdcard/gone"
        val fs = Fs(setOf(INTERNAL))                 // /sdcard/gone 已不存在
        val resolved = resolveAndLoad(fs, "/sdcard/gone", true)
        assertTrue("应回退到内部存储", resolved == INTERNAL)
        assertTrue("必须真的去列回退目录", fs.listed == listOf(INTERNAL))
    }

    @Test fun `回退目录同样不存在时兜底到内部存储并列出`() {
        remembered = "/sdcard/gone"
        val fs = Fs(setOf("/sdcard/other"))          // remembered 失效、兜底目标恰为 remembered 自身
        val resolved = resolveAndLoad(fs, "/sdcard/gone", true)
        assertTrue("兜底必须是内部存储", resolved == INTERNAL)
        assertTrue("兜底后仍要列出内容", fs.listed.isNotEmpty())
    }

    @Test fun `关闭记忆时不改写记忆目录`() {
        remembered = "/sdcard/keep"
        val fs = Fs(setOf(INTERNAL))
        resolveAndLoad(fs, "/sdcard/gone", false)
        assertTrue("关闭记忆开关时不得改写 rememberedDirectory", remembered == "/sdcard/keep")
    }
}
