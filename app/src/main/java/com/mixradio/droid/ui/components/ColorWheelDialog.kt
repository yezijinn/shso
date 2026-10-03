// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items


import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import kotlin.math.roundToInt

private data class PresetColorItem(
    val name: String,
    val color: Color,
    /**
     * 预计算的 HEX 串。
     *
     * 此前每帧对每个预设格做一次 `String.format("#%06X", …)`：拖色相条时
     * `hexString` 每帧变化 → 16 个格子全部重组 → 每格 new 一个 `java.util.Formatter`
     * （60Hz 下约 1000 次/s）。且未指定 Locale，在使用非拉丁数字的 Locale
     * （`ar-SA`/`fa-IR` 的 `nu-arab`）下会输出本地化数字。
     * 改在构造期算一次并固定 `Locale.ROOT`，格内只做字符串等值比较。
     */
    val hex: String = String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and color.toArgb())
)

private val PRESET_COLOR_GROUPS = listOf(
    PresetColorItem("荧光绿", Color(0xFF00E676)),
    PresetColorItem("黑客绿", Color(0xFF00FF00)),
    PresetColorItem("薄荷绿", Color(0xFF69F0AE)),
    PresetColorItem("翠绿", Color(0xFF4CAF50)),
    PresetColorItem("赛博青", Color(0xFF00E5FF)),
    PresetColorItem("电光蓝", Color(0xFF448AFF)),
    PresetColorItem("深海蓝", Color(0xFF2979FF)),
    PresetColorItem("冰晶蓝", Color(0xFF80D8FF)),
    PresetColorItem("琥珀黄", Color(0xFFFFD54F)),
    PresetColorItem("荧光金", Color(0xFFFFEA00)),
    PresetColorItem("霓虹橙", Color(0xFFFF9100)),
    PresetColorItem("珊瑚橙", Color(0xFFFF6E40)),
    PresetColorItem("警示红", Color(0xFFFF5252)),
    PresetColorItem("极客粉", Color(0xFFFF4081)),
    PresetColorItem("霓虹紫", Color(0xFFE040FB)),
    PresetColorItem("极光白", Color(0xFFFFFFFF))
)

/**
 * 终端文字颜色选择器：色相条 + 明暗条 + 预设网格。
 *
 * 约束：本工程全局零圆角，所有显式 shape 一律 `RoundedCornerShape(0.dp)`，不随主题令牌变化。
 */
@Composable
fun ColorWheelDialog(
    show: Boolean,
    initialColor: Color,
    onDismissRequest: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    if (!show) return

    val density = LocalDensity.current

    val initialHsv = remember(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)
        hsv
    }

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    // 预览用颜色：给饱和/明度一个下限，否则 HSV 在 s=0 或 v=0 时退化成纯黑，
    // 预览区看不出当前选择。
    //
    // 但**提交值必须用未钳制的原值**：下限会把 s=0 的纯白算成 0.99 → #FCFCFC，
    // 于是「极光白」预设永远选不中（拿 #FCFCFC 与 #FFFFFF 比恒为 false），
    // 且点确定后落盘的是 #FCFCFC —— 用户要的纯白被静默改写。
    // 故预览与取值拆开：previewColor 只用于绘制与 hex 显示。
    val previewColor = remember(hue, saturation, value) {
        // 低饱和 / 低明度下预览做抬升，纯粹为了「拖色相时看得见变化」；
        // 提交取值 currentColor 保持原值不变（见 updateHue 注释）。
        Color.hsv(
            hue,
            saturation.coerceIn(0f, 1f).coerceAtLeast(0.7f),
            value.coerceIn(0f, 1f).coerceAtLeast(0.6f)
        )
    }
    // 实际取值：s/v 为 0 时 Color.hsv 能正确给出灰/黑，无需下限
    val currentColor = remember(hue, saturation, value) {
        Color.hsv(hue, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    }

    val hexString = remember(currentColor) {
        val argb = currentColor.toArgb()
        String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and argb)
    }

    // 色相条 thumb 直径：触摸映射与绘制映射都要用，故提到外层作用域
    val hueThumbDiameter = 28.dp
    val hueThumbDiameterPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        hueThumbDiameter.toPx()
    }

    val rainbowBrush = remember {
        Brush.horizontalGradient(
            colors = listOf(
                Color.Red, Color.Yellow, Color.Green,
                Color.Cyan, Color.Blue, Color.Magenta, Color.Red
            )
        )
    }

    AuroraWindowDialog(
        show = show,
        title = "终端文字颜色",
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.dp))
                    .background(AuroraTokens.BgDeep)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PREVIEW",
                        color = Color.White.copy(0.4f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = hexString,
                        color = previewColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "root@android:~# shso --status",
                    color = previewColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "[shso] 任务执行成功 [退出码: 0]",
                    color = previewColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "色相",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(rainbowBrush)
                        .pointerInput(Unit) {
                            // 触摸位置 → 色相：与 thumb 的绘制位置必须同一套映射。
                            // 此前这里是 x/width → [0,360]，而 thumb 画在
                            // hue/360*(width-28dp)，两套公式差半个 thumb 直径
                            // （≈300dp 宽条上约 17° 色相）—— 点哪不是哪。
                            fun updateHue(x: Float, maxWidthPx: Float) {
                                val usable = (maxWidthPx - hueThumbDiameterPx).coerceAtLeast(1f)
                                val clampedX = x.coerceIn(0f, usable)
                                hue = (clampedX / usable) * 360f
                                // 接近灰或黑时拖动色相在预览上看不出变化 —— 但这是**显示层**的补偿，
                                // 绝不能写回 saturation/value：它们是提交取值（onColorSelected 读
                                // currentColor ← saturation/value）。此前两处协程里直接
                                // `saturation = 1f; value = 1f`，于是终端色 #1A1A1A（s=0.10 v=0.10）
                                // 只要手指碰一下色相条就被强抬成满饱和满明度：预览跳变，且落盘的
                                // 是用户没选过的颜色。补偿已移到 [previewColor]。
                            }

                            detectTapGestures { offset ->
                                updateHue(offset.x, size.width.toFloat())
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val usable = (size.width - hueThumbDiameterPx).coerceAtLeast(1f)
                                val clampedX = change.position.x.coerceIn(0f, usable)
                                hue = (clampedX / usable) * 360f
                                // 同 updateHue：预览补偿只在显示层，不回写提交取值
                            }
                        }
                ) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    
                    // 与 updateHue 同一套映射：x = hue/360 * (width - thumbDiameter)
                    val thumbX = (hue / 360f * (widthPx - hueThumbDiameterPx)).coerceIn(0f, (widthPx - hueThumbDiameterPx).coerceAtLeast(0f))

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(thumbX.roundToInt(), with(density) { 3.dp.toPx().roundToInt() }) }
                            .size(hueThumbDiameter)
                            .shadow(4.dp, RoundedCornerShape(0.dp))
                            .clip(RoundedCornerShape(0.dp))
                            .background(Color.White)
                            .border(2.dp, AuroraTokens.StrokeLight, RoundedCornerShape(0.dp))
                            .padding(3.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(Color.hsv(hue, 1f, 1f))
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "明暗",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )

                val brightnessBrush = remember(hue, saturation) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black,
                            Color.hsv(hue, saturation.coerceIn(0.1f, 1f), 1f),
                            Color.White
                        )
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(brightnessBrush)
                        .pointerInput(Unit) {
                            fun updateBrightness(x: Float, maxWidthPx: Float) {
                                val clampedX = x.coerceIn(0f, maxWidthPx)
                                val ratio = clampedX / maxWidthPx
                                // 明度下限 0.2：终端文字在纯黑下不可读，故映射到 [0.2, 1]
                                value = (0.2f + ratio * 0.8f).coerceIn(0.2f, 1f)
                            }

                            detectTapGestures { offset ->
                                updateBrightness(offset.x, size.width.toFloat())
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val clampedX = change.position.x.coerceIn(0f, size.width.toFloat())
                                val ratio = clampedX / size.width.toFloat()
                                // 同 updateBrightness：明度区间为 [0.2, 1]
                                value = (0.2f + ratio * 0.8f).coerceIn(0.2f, 1f)
                            }
                        }
                ) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    
    val thumbDiameter = 28.dp
    val thumbDiameterPx = with(density) { thumbDiameter.toPx() }
                    val progress = ((value - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    // min > max 时 Float.coerceIn 抛 IllegalArgumentException：窄容器
                    // （分屏小窗 / 折叠屏外屏 / 极端显示缩放）下 widthPx 可能小于 thumb 直径。
                    // 色相条那处已有 coerceAtLeast 保护，这里补齐。
                    val thumbUsable = (widthPx - thumbDiameterPx).coerceAtLeast(0f)
                    val thumbX = (progress * thumbUsable).coerceIn(0f, thumbUsable)

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(thumbX.roundToInt(), with(density) { 3.dp.toPx().roundToInt() }) }
                            .size(thumbDiameter)
                            .shadow(4.dp, RoundedCornerShape(0.dp))
                            .clip(RoundedCornerShape(0.dp))
                            .background(Color.White)
                            .border(2.dp, AuroraTokens.StrokeLight, RoundedCornerShape(0.dp))
                            .padding(3.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(previewColor)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "预设",
                    style = AuroraTextStyles.footnote2,
                    color = AuroraTokens.TextSecondary
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                ) {
                    items(PRESET_COLOR_GROUPS) { item ->
                        // 按 HEX 字符串判定选中：Color 分量为浮点，直接等值比较会因转换误差漏判
                        val isSelected = hexString == item.hex

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .clip(RoundedCornerShape(0.dp))
                                .background(
                                    if (isSelected) item.color.copy(alpha = 0.25f)
                                    else AuroraTokens.SurfaceHover.copy(alpha = 0.6f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.dp,
                                    color = if (isSelected) item.color else Color.Transparent,
                                    shape = RoundedCornerShape(0.dp)
                                )
                                .clickable {
                                    val hsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(item.color.toArgb(), hsv)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(item.color)
                                    .border(
                                        width = 1.dp,
                                        color = if (item.color == Color.White) Color.Gray.copy(0.5f) else Color.Transparent,
                                        shape = RoundedCornerShape(0.dp)
                                    )
                            )
                            Text(
                                text = item.name,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) item.color else AuroraTokens.Text,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.SurfaceHover,
                        contentColor = AuroraTokens.Text
                    )
                ) {
                    Text("取消", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = {
                        onColorSelected(currentColor)
                        onDismissRequest()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuroraTokens.Accent,
                        contentColor = AuroraTokens.OnAccent
                    )
                ) {
                    Text("确定应用", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
