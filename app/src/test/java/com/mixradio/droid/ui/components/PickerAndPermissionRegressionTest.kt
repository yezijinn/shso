// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文件选择器与权限弹窗的回归护栏。
 *
 * 这一组都是**界面与实际行为不一致**型缺陷：不崩溃，但用户看到的和真正生效的不是一回事 ——
 * 路径行显示 A 而列表是空的、输入框禁用而值已被改掉、点保存落盘的不是屏幕上那组值。
 */
class PickerAndPermissionRegressionTest {

    private fun src(name: String): String =
        File("src/main/java/com/mixradio/droid/ui/components/$name").readText()

    // ========================================================================
    // 记忆目录失效后的回退
    // ========================================================================

    @Test fun `回退目录必须真的去列内容`() {
        // 回归护栏：原实现在 !exists 分支只改了 currentDir，`loaded` 仍是 emptyList()，
        // 于是路径行显示「内部存储」而列表空白 —— 用户会得出「内部存储是空的」的错误结论。
        // 拔过 SD 卡 / 删过记忆目录后必现。
        val s = src("BuiltInFilePicker.kt")
        val fn = s.indexOf("fun loadDirectory(path: String)")
        assertTrue("应能找到 loadDirectory", fn > 0)
        val body = s.substring(fn, fn + 2500)
        assertTrue(
            "回退分支必须对 resolved 调 listFiles，而不是固定用 path",
            body.contains("RootFileManager.listFiles(resolved)")
        )
        assertFalse(
            "不得再用 emptyList() 顶替回退目录的内容",
            body.contains("else emptyList()")
        )
    }

    @Test fun `记忆目录的写入必须在代次守卫之后`() {
        // 该守卫的注释原文写着「丢弃本次结果（含副作用）」，但 rememberedDirectory 的两次
        // 写入此前都排在守卫**之前** —— 副作用已经发生，守卫只挡得住返回值。
        // 于是快速连点目录 A 再点 B 时，更慢的陈旧请求会把「上次浏览目录」覆盖回 A，
        // 而用户最后实际浏览的是 B。
        val s = src("BuiltInFilePicker.kt")
        val fn = s.indexOf("fun loadDirectory(path: String)")
        val body = s.substring(fn, fn + 2500)
        val guardAt = body.indexOf("if (gen != loadGen[0]) return@launch")
        val writeAt = body.indexOf("RootFileManager.rememberedDirectory = resolved")
        assertTrue("应存在代次守卫", guardAt > 0)
        assertTrue("应存在记忆目录写入", writeAt > 0)
        assertTrue(
            "记忆目录写入必须排在代次守卫之后，否则陈旧请求会覆盖它",
            writeAt > guardAt
        )
    }

    @Test fun `选择器不得以可变的初始目录作为 LaunchedEffect 的 key`() {
        // initialDirectory 依赖 appSettings.rememberDirectory 这个可观察状态：在选择器内
        // 关掉「记忆操作路径」开关会让 key 变化 → 协程重启 → currentDir 被重置回内部存储根，
        // 用户刚浏览到的目录与滚动位置全部丢失。
        val s = src("BuiltInFilePicker.kt")
        assertFalse(
            "LaunchedEffect 不得以 initialDirectory 为 key",
            s.contains("LaunchedEffect(show, initialDirectory)")
        )
    }

    // ========================================================================
    // 权限弹窗
    // ========================================================================

    @Test fun `预设按钮必须跟随提交中状态禁用`() {
        // 保存要 fork 多次 su、耗时数秒，期间三个输入框都传了 enabled = !submitting，
        // 唯独两个预设按钮没传：用户看到输入框是旧值（禁用态），owner/group 却已被改成 root，
        // 落盘的与屏幕上显示的不是同一组值。改权限是本项目唯一带越权后果的操作。
        val s = src("FilePermissionDialog.kt")
        assertTrue(
            "root:root 预设必须禁用",
            s.contains("PresetButton(\"root:root\", enabled = !submitting)")
        )
        assertTrue(
            "system:system 预设必须禁用",
            s.contains("PresetButton(\"system:system\", enabled = !submitting)")
        )
    }

    @Test fun `权限开关的触摸目标不得低于 48dp`() {
        // 工程约定（CONTRIBUTING「UI 形态」）：行高统一 heightIn(min = 48.dp)，
        // 同工程的 AuroraArrowPreference 正是为此。3×3 密集排布下 40dp 的误触代价偏高 ——
        // 把文件从 644 误点成 777 是不可逆的越权。
        val s = src("FilePermissionDialog.kt")
        val fn = s.indexOf("private fun PermissionToggle")
        assertTrue("应能找到 PermissionToggle", fn > 0)
        val body = s.substring(fn, fn + 1200)
        assertTrue("触摸目标必须用 heightIn(min = 48.dp)", body.contains("heightIn(min = 48.dp)"))
        assertTrue(
            "视觉方块仍保持 40dp，命中区由外层撑开",
            body.contains("size(40.dp)")
        )
    }
}
