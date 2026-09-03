// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.components

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
import androidx.compose.runtime.mutableStateOf
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
import com.qihoo360.mobilesafe.ui.theme.AuroraTextStyles
import com.qihoo360.mobilesafe.ui.theme.AuroraTokens
import com.qihoo360.mobilesafe.ui.theme.AuroraWindowDialog
import kotlin.math.roundToInt

private data class PresetColorItem(
    val name: String,
    val color: Color
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

    val currentColor = remember(hue, saturation, value) {
        Color.hsv(hue, saturation.coerceIn(0.01f, 1f), value.coerceIn(0.01f, 1f))
    }

    val hexString = remember(currentColor) {
        val argb = currentColor.toArgb()
        String.format("#%06X", 0xFFFFFF and argb)
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
                        color = currentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "root@android:~# shso --status",
                    color = currentColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "[shso] 任务执行成功 [退出码: 0]",
                    color = currentColor,
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
                            fun updateHue(x: Float, maxWidthPx: Float) {
                                val clampedX = x.coerceIn(0f, maxWidthPx)
                                hue = (clampedX / maxWidthPx) * 360f
                                if (saturation < 0.2f) saturation = 1.0f
                                if (value < 0.3f) value = 1.0f
                            }

                            detectTapGestures { offset ->
                                updateHue(offset.x, size.width.toFloat())
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val clampedX = change.position.x.coerceIn(0f, size.width.toFloat())
                                hue = (clampedX / size.width.toFloat()) * 360f
                                if (saturation < 0.2f) saturation = 1.0f
                                if (value < 0.3f) value = 1.0f
                            }
                        }
                ) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    val thumbDiameter = 28.dp
                    val thumbDiameterPx = with(density) { thumbDiameter.toPx() }
                    val thumbX = (hue / 360f * (widthPx - thumbDiameterPx)).coerceIn(0f, widthPx - thumbDiameterPx)

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
                                value = (0.2f + ratio * 0.8f).coerceIn(0.2f, 1f)
                            }
                        }
                ) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    val thumbDiameter = 28.dp
                    val thumbDiameterPx = with(density) { thumbDiameter.toPx() }
                    val progress = ((value - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    val thumbX = (progress * (widthPx - thumbDiameterPx)).coerceIn(0f, widthPx - thumbDiameterPx)

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
                            .background(currentColor)
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
                        val isSelected = hexString == String.format("#%06X", 0xFFFFFF and item.color.toArgb())

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
