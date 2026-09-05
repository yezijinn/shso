// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * ============================================================================
 * 两个玻璃修饰符的分工（务必分清，混用会让白度加倍）：
 *
 *   Modifier.auroraGlass(...)  = 填充 + 描边
 *                                用于**自己画**的 Box / Row / Column
 *                                （终端输出盒、取色器预览框、底部导航）
 *
 *   Modifier.auroraStroke(...) = **只加描边**
 *                                用于 Material 3 Card / Button —— 它们内部
 *                                已填充，再重复填充会加倍白
 *
 * 全部为静态绘制（drawBehind / border），无 animateXxxAsState、无逐帧重绘；
 * elevation 恒为 0（Material 3 组件默认无阴影）。
 *
 * 形态铁律：禁止任何大圆角，全部直角矩形（RoundedCornerShape(0.dp)）。
 * ============================================================================
 */

/**
 * 全局形状：所有尺寸槽位均为直角矩形。
 * 注入 `MaterialTheme(shapes = AuroraShapes)`，让 Card / Button / Surface /
 * TextField 等 M3 组件默认形状彻底矩形化。
 */
val AuroraShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

/**
 * 玻璃面板（实色填充）：半透明填充 + 1dp 发丝描边。
 *
 * @param fill 玻璃填充色，默认 5% 白。
 * @param stroke 描边颜色，默认 12% 白。
 * @param strokeWidth 描边宽度，默认 1dp。传 `0.dp` 可只留填充。
 */
fun Modifier.auroraGlass(
    fill: Color = AuroraTokens.Surface,
    stroke: Color = AuroraTokens.Stroke,
    strokeWidth: Dp = 1.dp,
): Modifier = this
    .background(color = fill)
    .then(if (strokeWidth > 0.dp) Modifier.border(strokeWidth, stroke) else Modifier)

/**
 * 玻璃面板（渐变填充 + 描边）：用于底部导航这类需要竖向渐变的容器。
 *
 * @param fill 渐变填充（例如 10% → 4% 白的竖向渐变）。
 * @param stroke 描边颜色。
 * @param strokeWidth 描边宽度。
 */
fun Modifier.auroraGlass(
    fill: Brush,
    stroke: Color = AuroraTokens.StrokeLight,
    strokeWidth: Dp = 1.dp,
): Modifier = this
    .background(brush = fill)
    .then(if (strokeWidth > 0.dp) Modifier.border(strokeWidth, stroke) else Modifier)

/**
 * 只加发丝描边：用于 Material 3 `Card` / `Button`（内部已填充，不可重复填充）。
 *
 * @param color 描边颜色。
 * @param width 描边宽度。
 */
fun Modifier.auroraStroke(
    color: Color = AuroraTokens.Stroke,
    width: Dp = 1.dp,
): Modifier = this.border(width, color)

/**
 * 标题下方的强调条：44×3dp，青 → 蓝 → 紫横向渐变，直角矩形。
 *
 * @param modifier 外部 modifier（通常用来加 `padding(top = 6.dp)` 或对齐）。
 * @param width 条宽，默认 44dp。
 * @param height 条高，默认 3dp。
 */
@Composable
fun AuroraAccentBar(
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    height: Dp = 3.dp,
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .background(
                brush = Brush.horizontalGradient(
                    listOf(
                        AuroraTokens.GlowCyan,
                        AuroraTokens.GlowBlue,
                        AuroraTokens.AccentViolet,
                    )
                )
            )
    )
}

/* ── 组件配色（覆盖 Material 3 默认映射中与规范冲突的字段） ────────────────────── */

/**
 * 卡片配色：5% 白玻璃底 + 主文字色。
 */
@Composable
fun auroraCardColors(
    color: Color = AuroraTokens.Surface,
    contentColor: Color = AuroraTokens.Text,
): CardColors = CardDefaults.cardColors(
    containerColor = color,
    contentColor = contentColor,
)

/**
 * 主操作按钮配色：青底 + 深青字。
 */
@Composable
fun auroraPrimaryButtonColors(
    color: Color = AuroraTokens.Accent,
    disabledColor: Color = AuroraTokens.Accent30,
    contentColor: Color = AuroraTokens.OnAccent,
    disabledContentColor: Color = Color(0xB3F2F6FA),
): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = color,
    disabledContainerColor = disabledColor,
    contentColor = contentColor,
    disabledContentColor = disabledContentColor,
)

/**
 * 开关配色。
 *
 * 选中态：拇指 = [AuroraTokens.Accent]、轨道 = [AuroraTokens.AccentBright]。
 * 未选中态：白拇指 + [AuroraTokens.ControlOff] 轨道。
 */
@Composable
fun auroraSwitchColors(
    checkedThumbColor: Color = AuroraTokens.Accent,
    uncheckedThumbColor: Color = Color.White,
    checkedTrackColor: Color = AuroraTokens.AccentBright,
    uncheckedTrackColor: Color = AuroraTokens.ControlOff,
    disabledCheckedThumbColor: Color = AuroraTokens.Accent30,
    disabledUncheckedThumbColor: Color = Color(0x80FFFFFF),
    disabledCheckedTrackColor: Color = AuroraTokens.Accent30,
    disabledUncheckedTrackColor: Color = Color(0x0DFFFFFF),
): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = checkedThumbColor,
    uncheckedThumbColor = uncheckedThumbColor,
    checkedTrackColor = checkedTrackColor,
    uncheckedTrackColor = uncheckedTrackColor,
    disabledCheckedThumbColor = disabledCheckedThumbColor,
    disabledUncheckedThumbColor = disabledUncheckedThumbColor,
    disabledCheckedTrackColor = disabledCheckedTrackColor,
    disabledUncheckedTrackColor = disabledUncheckedTrackColor,
)

/* ── 按钮形态：直角矩形（禁止任何大圆角） ───────────────────────────────── */

/**
 * 填充按钮外形：直角矩形。用于主操作按钮 / 填充按钮（Button 本身无圆角参数，
 * 统一在这里收敛为矩形，替代散落的圆角写法）。
 */
fun Modifier.auroraFilledButton(): Modifier =
    this.clip(RoundedCornerShape(0.dp))

/**
 * 描边按钮外形：直角矩形 + 1dp 20% 白发丝描边。
 * 与 12% 白填充（[AuroraTokens.SurfaceHover]）组合为「玻璃描边按钮」。
 */
fun Modifier.auroraOutlinedButton(): Modifier =
    this.clip(RoundedCornerShape(0.dp))
        .then(
            Modifier.border(
                width = 1.dp,
                color = AuroraTokens.StrokeStrong,
                shape = RoundedCornerShape(0.dp)
            )
        )

/**
 * 次要按钮配色：12% 白玻璃底 + 主文字（描边按钮配合 [auroraOutlinedButton]）。
 */
@Composable
fun auroraSecondaryButtonColors(
    containerColor: Color = AuroraTokens.SurfaceHover,
    contentColor: Color = AuroraTokens.Text,
    disabledContainerColor: Color = AuroraTokens.Surface,
    disabledContentColor: Color = AuroraTokens.TextDisabled,
): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = containerColor,
    contentColor = contentColor,
    disabledContainerColor = disabledContainerColor,
    disabledContentColor = disabledContentColor,
)
