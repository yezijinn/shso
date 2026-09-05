// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 极光玻璃主题的设计令牌（design-spec.md 附录 C）。
 *
 * 使用约定：
 * 1. 页面/组件里**禁止**再写 `Color(0xFF...)` 字面量，一律引用本对象的常量
 *    （取色器预设色板、终端用户自定义文字色除外——那是功能数据不是装饰色）。
 * 2. 全部是编译期常量（`val` + 字面量构造），Compose 重组零成本，且保证"静态"
 *    （无 animateXxxAsState，无逐帧重绘）。
 */
object AuroraTokens {

    /* ── 极光底色 ─────────────────────────────────────────── */
    /** 深空底，仅用于系统栏与 windowDimming 计算，不作页面背景。 */
    val BgDeep = Color(0xFF04060B)

    /** 135° 线性渐变三段：起 / 中 / 止。 */
    val AuroraTop = Color(0xFF0C1626)
    val AuroraMid = Color(0xFF06080F)
    val AuroraBottom = Color(0xFF0B0A17)

    /* ── 四角光晕基色（峰值透明度见 AuroraBackground） ──────── */
    val GlowCyan = Color(0xFF00C8FF)
    val GlowBlue = Color(0xFF4D9FFF)
    val GlowViolet = Color(0xFF9D8CFF)

    /* ── 玻璃填充（半透明白阶） ────────────────────────────── */
    /** surface，5% 白——卡片默认底。 */
    val Surface = Color(0x0DFFFFFF)

    /** surface_strong，8% 白——输入框底。 */
    val SurfaceStrong = Color(0x14FFFFFF)

    /** surface_hover，12% 白——描边按钮底 / 滑块轨道底。 */
    val SurfaceHover = Color(0x1FFFFFFF)

    /* ── 发丝描边 ─────────────────────────────────────────── */
    /** stroke，12% 白——卡片与常规描边。 */
    val Stroke = Color(0x1FFFFFFF)

    /** stroke_strong，20% 白——小控件强调描边。 */
    val StrokeStrong = Color(0x33FFFFFF)

    /** stroke_light，15% 白——底部导航与圆形控件描边。 */
    val StrokeLight = Color(0x26FFFFFF)

    /* ── 对话框 ───────────────────────────────────────────── */
    /** dialog_bg，95% 深空蓝。对话框与弹窗默认底。 */
    val DialogBg = Color(0xF2141B27)

    /* ── 文字 ─────────────────────────────────────────────── */
    val Text = Color(0xFFF2F6FA)
    val TextSecondary = Color(0xFF9AA7BA)
    val TextUnselected = Color(0xFF8B98A8)

    /** text @72%，说明性文字。 */
    val TextHint = Color(0xB8F2F6FA)

    /** text @35%，禁用态文字。 */
    val TextDisabled = Color(0x59F2F6FA)

    /* ── 强调色（青 / 蓝 / 紫族） ──────────────────────────── */
    val Accent = Color(0xFF00B8E6)
    val AccentDark = Color(0xFF007EA6)
    val AccentBright = Color(0xFF4DD8FF)
    val AccentViolet = Color(0xFF9D8CFF)
    val AccentVioletDark = Color(0xFF6C5CE7)
    val OnAccent = Color(0xFF04222E)
    val OnViolet = Color(0xFF0E0A1F)

    /* ── 控件与状态 ───────────────────────────────────────── */
    /** 开关/滑块未选中轨道。 */
    val ControlOff = Color(0xFF1D2530)

    /** 错误 / 危险。 */
    val Error = Color(0xFFF85149)

    /** 主操作按钮：激活 90% 青 / 禁用 30% 青。 */
    val Accent90 = Color(0xE600B8E6)
    val Accent30 = Color(0x4D00B8E6)

    /** 状态胶囊底，60% 深。 */
    val PillBg = Color(0x9905080D)

    /** 弹窗遮罩，bg_deep @62%，不用纯黑。 */
    val Dimming = Color(0x9E04060B)

    /** 表面变体（按钮非激活态，SurfaceHover 替代） */
    val SurfaceVariant = Color(0x14FFFFFF)

    /** 警告 / 提醒（橙黄） */
    val Warning = Color(0xFFE6B800)

    /** 成功 / 已完成（绿） */
    val Success = Color(0xFF00E676)
}

/**
 * 极光配色表：把 [AuroraTokens] 映射到 Material 3 的 [ColorScheme]（暗色）。
 *
 * 由 `MainActivity` 通过 `MaterialTheme(colorScheme = AuroraColorScheme, ...)` 注入。
 *
 * ⚠️ 两个必须知道的约束：
 * 1. `background` 设为 `Color.Transparent` —— 让 `Scaffold` / `TopAppBar` / 页面
 *    容器的默认背景透出极光（`Modifier.auroraBackground()` 提供屏幕背景）。
 *    对话框不依赖 `background`，显式用 [AuroraTokens.DialogBg]（见 [AuroraWindowDialog]）。
 * 2. `surface` 设为 [AuroraTokens.DialogBg] —— 作为默认 Surface 底（深空蓝玻璃），
 *    任何把 `surface` 当**文字颜色**用的地方都不会再变透明（这是迁移旧主题时的坑）。
 */
val AuroraColorScheme: ColorScheme = darkColorScheme().copy(
    primary = AuroraTokens.Accent,
    onPrimary = AuroraTokens.OnAccent,
    primaryContainer = Color(0x1A00B8E6),
    onPrimaryContainer = AuroraTokens.AccentBright,
    inversePrimary = AuroraTokens.AccentBright,

    secondary = AuroraTokens.ControlOff,
    onSecondary = Color.White,
    secondaryContainer = AuroraTokens.SurfaceStrong,
    onSecondaryContainer = AuroraTokens.TextSecondary,

    tertiary = AuroraTokens.AccentViolet,
    onTertiary = AuroraTokens.OnViolet,
    tertiaryContainer = Color(0x1A9D8CFF),
    onTertiaryContainer = AuroraTokens.AccentViolet,

    background = Color.Transparent,
    onBackground = AuroraTokens.Text,

    surface = AuroraTokens.DialogBg,
    onSurface = AuroraTokens.Text,
    surfaceVariant = AuroraTokens.Surface,
    onSurfaceVariant = AuroraTokens.TextSecondary,
    inverseSurface = AuroraTokens.Text,
    inverseOnSurface = AuroraTokens.BgDeep,

    error = AuroraTokens.Error,
    onError = Color.White,
    errorContainer = Color(0x1FF85149),
    onErrorContainer = Color(0xFFFFB4AF),

    outline = AuroraTokens.Stroke,
    outlineVariant = AuroraTokens.StrokeStrong,
    scrim = AuroraTokens.Dimming,

    surfaceBright = AuroraTokens.SurfaceHover,
    surfaceDim = AuroraTokens.BgDeep,
    surfaceContainer = AuroraTokens.Surface,
    surfaceContainerHigh = AuroraTokens.SurfaceStrong,
    surfaceContainerHighest = AuroraTokens.SurfaceHover,
    surfaceContainerLow = AuroraTokens.Surface,
    surfaceContainerLowest = AuroraTokens.Surface,
)

/**
 * 全局等宽字体（用户硬约束：APP 一律使用 Monospace）。
 */
val AuroraFontFamily: FontFamily = FontFamily.Monospace

/**
 * Material 3 排版：全部样式强制等宽字体，尺寸沿用 Material 默认值。
 */
val AuroraTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = AuroraFontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = AuroraFontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = AuroraFontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = AuroraFontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = AuroraFontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = AuroraFontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = AuroraFontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = AuroraFontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = AuroraFontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = AuroraFontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = AuroraFontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = AuroraFontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = AuroraFontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = AuroraFontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = AuroraFontFamily),
    )
}

/**
 * 等宽全局字体下的常用文本样式集合（与 Material 默认 Typography 互为补充），
 * 供页面直接引用（`AuroraTextStyles.xxx`），保证字号/字重全局统一。
 */
data class AuroraTextStyleSet(
    val main: TextStyle,
    val paragraph: TextStyle,
    val body1: TextStyle,
    val body2: TextStyle,
    val button: TextStyle,
    val footnote1: TextStyle,
    val footnote2: TextStyle,
    val headline1: TextStyle,
    val headline2: TextStyle,
    val subtitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val title4: TextStyle,
    val monospace: TextStyle
)

val AuroraTextStyles = AuroraTextStyleSet(
    main = TextStyle(fontFamily = AuroraFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Normal),
    paragraph = TextStyle(fontFamily = AuroraFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Normal),
    body1 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Normal),
    body2 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Normal),
    button = TextStyle(fontFamily = AuroraFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    footnote1 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Normal),
    footnote2 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Normal),
    headline1 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headline2 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 28.sp, fontWeight = FontWeight.Bold),
    subtitle = TextStyle(fontFamily = AuroraFontFamily, fontSize = 18.sp, fontWeight = FontWeight.Medium),
    title1 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold),
    title2 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 18.sp, fontWeight = FontWeight.Bold),
    title3 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 16.sp, fontWeight = FontWeight.Bold),
    title4 = TextStyle(fontFamily = AuroraFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold),
    monospace = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Normal),
)

/**
 * 字号整体缩小 [deltaSp]（px），用于设置页 -4px 需求。
 */
fun AuroraTextStyleSet.shrunk(deltaSp: Float): AuroraTextStyleSet = copy(
    main = main.copy(fontSize = (main.fontSize.value - deltaSp).sp),
    paragraph = paragraph.copy(fontSize = (paragraph.fontSize.value - deltaSp).sp),
    body1 = body1.copy(fontSize = (body1.fontSize.value - deltaSp).sp),
    body2 = body2.copy(fontSize = (body2.fontSize.value - deltaSp).sp),
    button = button.copy(fontSize = (button.fontSize.value - deltaSp).sp),
    footnote1 = footnote1.copy(fontSize = (footnote1.fontSize.value - deltaSp).sp),
    footnote2 = footnote2.copy(fontSize = (footnote2.fontSize.value - deltaSp).sp),
    headline1 = headline1.copy(fontSize = (headline1.fontSize.value - deltaSp).sp),
    headline2 = headline2.copy(fontSize = (headline2.fontSize.value - deltaSp).sp),
    subtitle = subtitle.copy(fontSize = (subtitle.fontSize.value - deltaSp).sp),
    title1 = title1.copy(fontSize = (title1.fontSize.value - deltaSp).sp),
    title2 = title2.copy(fontSize = (title2.fontSize.value - deltaSp).sp),
    title3 = title3.copy(fontSize = (title3.fontSize.value - deltaSp).sp),
    title4 = title4.copy(fontSize = (title4.fontSize.value - deltaSp).sp),
)
