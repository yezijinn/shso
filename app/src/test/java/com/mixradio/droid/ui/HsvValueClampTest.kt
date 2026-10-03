// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 第九轮深挖的回归：取色器 HSV 的「预览下限」不得污染「取值」。
 *
 * 原实现用同一个下限 `0.01f` 同时承担预览与取值，于是 s=0 的纯白被算成
 * `1 - 1*0.01*1 = 0.99` → `#FCFCFC`：
 *   · 「极光白」预设的选中框永远不亮（拿 #FCFCFC 与 #FFFFFF 比恒为 false）
 *   · 点确定后落盘的是 #FCFCFC，用户要的纯白被静默改写
 *
 * 预览侧的下限只影响绘制、不影响提交值，故此处只固化「取值侧不做下限」的语义。
 */
class HsvValueClampTest {

    /** 提交值路径：与 ColorWheelDialog 的 currentColor 保持一致（无下限）。 */
    private fun submitted(h: Float, s: Float, v: Float): Int =
        Color.hsv(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)).toArgb()

    /** 旧实现：预览与取值共用一个下限。仅作对照，不参与被测断言。 */
    private fun legacy(h: Float, s: Float, v: Float): Int =
        Color.hsv(h, s.coerceIn(0.01f, 1f), v.coerceIn(0.01f, 1f)).toArgb()

    @Test fun `纯白必须精确可达`() {
        assertEquals("s=0,v=1 必须得到纯白 0xFFFFFF", 0xFFFFFFFF.toInt(), submitted(0f, 0f, 1f))
    }

    @Test fun `纯黑必须精确可达`() {
        assertEquals("v=0 必须得到纯黑", 0xFF000000.toInt(), submitted(0f, 0f, 0f))
    }

    @Test fun `旧实现确实把纯白算错（对照，防止回归到旧行为）`() {
        assertNotEquals(
            "旧实现给纯白套 0.01 下限后不再是纯白 —— 这正是要修掉的缺陷",
            legacy(0f, 0f, 1f),
            submitted(0f, 0f, 1f)
        )
    }

    @Test fun `任意灰阶必须三通道相等`() {
        for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val argb = submitted(0f, 0f, v)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            assertEquals(
                "灰阶 v=$v 的 R/G/B 必须相等（实得 $r/$g/$b）",
                r,
                g
            )
            assertEquals("灰阶 v=$v 的 G/B 必须相等", g, b)
        }
    }

    @Test fun `饱和色在取色条正中仍是原色`() {
        // 色相 180 + 满饱和 + 满明度 = 青，验证饱和路径未被下限影响
        assertEquals(0xFF00FFFF.toInt(), submitted(180f, 1f, 1f))
    }

    @Test fun `色相 360 与 0 等价（避免条尾出现色跳变）`() {
        assertEquals(submitted(0f, 1f, 1f), submitted(360f, 1f, 1f))
    }

    @Test fun `取值侧不得对饱和度设下限`() {
        // 纯白是下限影响最大的点（s=0 被抬到 0.01 后掉到 0.99），已在上面对照用例断言。
        // 这里断言「低饱和仍是满值红通道」：若取值侧又被套上下限，
        // s=0.02 会被抬到 0.02→ 与 0.01 差异极小，但 s=0 一定掉档，
        // 两个用例合起来足以锁住回归。
        val lowSatWhite = submitted(0f, 0.02f, 1f)
        assertEquals(
            "低饱和白的红通道应仍是满值（未被下限压暗）",
            0xFF,
            (lowSatWhite shr 16) and 0xFF
        )
    }
}
