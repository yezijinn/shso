// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * 极光背景：1 层 135° 线性渐变 + 4 层静态径向光晕（规范 §2）。
 *
 * 约束：纯 drawBehind，无动画、无实时模糊（RenderEffect/BlurView）、无粒子、无逐帧重绘。
 * 尺寸/密度不变时只绘制一次并缓进 display list；滚动与翻页都不会触发重绘。
 */
fun Modifier.auroraBackground(): Modifier = this.drawBehind {
    // ① 135° 线性渐变（Compose linearGradient 左上→右下，与 CSS 135deg 同向）
    drawRect(
        brush = Brush.linearGradient(
            0.00f to AuroraTokens.AuroraTop,     // #0C1626
            0.55f to AuroraTokens.AuroraMid,     // #06080F
            1.00f to AuroraTokens.AuroraBottom,  // #0B0A17
        )
    )

    val w = size.width
    val h = size.height

    // ② 左上 青
    drawRadialGlow(AuroraTokens.GlowCyan, 0.16f, Offset(w * 0.08f, h * 0.00f), 720.dp.toPx())
    // ③ 右上 蓝
    drawRadialGlow(AuroraTokens.GlowBlue, 0.12f, Offset(w * 0.92f, h * 0.18f), 560.dp.toPx())
    // ④ 左下 紫
    drawRadialGlow(AuroraTokens.GlowViolet, 0.15f, Offset(w * 0.05f, h * 0.95f), 780.dp.toPx())
    // ⑤ 右下 青
    drawRadialGlow(AuroraTokens.GlowCyan, 0.08f, Offset(w * 0.98f, h * 0.72f), 520.dp.toPx())
}

private fun DrawScope.drawRadialGlow(color: Color, alpha: Float, center: Offset, radiusPx: Float) {
    drawRect(
        brush = Brush.radialGradient(
            0.0f to color.copy(alpha = alpha),   // 中心：规范给定的峰值透明度（≤25%）
            0.7f to Color.Transparent,           // 对齐 CSS 的 `transparent 70%`
            center = center,
            radius = radiusPx,
        )
    )
}
