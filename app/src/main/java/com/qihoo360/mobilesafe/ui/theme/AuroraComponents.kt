// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 原生对话框：深空蓝玻璃底 + 20dp 圆角 + 1dp 发丝描边，
 * 顶部显示标题（可选摘要），内容区可滚动。
 *
 * 调用处保持 `AuroraWindowDialog(show, title, summary?, onDismissRequest) { Column { ... } }` 形态。
 */
@Composable
fun AuroraWindowDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!show) return

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            color = AuroraTokens.DialogBg,
            shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, AuroraTokens.Stroke),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = AuroraTextStyles.title3,
                        color = AuroraTokens.Text
                    )
                }
                if (summary != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = summary,
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }
}

/**
 * 区段标题。
 *
 * @param fontSize 非空时覆盖默认 title3 字号（如设置页全局字号 -2 时传 14.sp）。
 */
@Composable
fun AuroraSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit? = null
) {
    Text(
        text = text,
        style = if (fontSize != null) AuroraTextStyles.title3.copy(fontSize = fontSize) else AuroraTextStyles.title3,
        color = AuroraTokens.Text,
        modifier = modifier
    )
}

/**
 * 箭头设置项：标题 + 可选摘要 + 右侧自定义内容 + 末尾箭头。
 */
@Composable
fun AuroraArrowPreference(
    title: String,
    summary: String? = null,
    endActions: @Composable (() -> Unit)? = null,
    /**
     * 右侧胶囊开关状态（权限类只读展示）。非 null 时优先渲染 M3 Switch（缩小 50%，
     * 与 AuroraSwitchPreference 视觉完全一致），取代 endActions 文本与末尾箭头；
     * 点击开关 / 整行均触发 onClick。
     */
    statusSwitch: Boolean? = null,
    statusSwitchEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 主标题：body2(14sp)=body1(16sp)-2，满足设置页「非注释文本字号 -2」
            Text(text = title, style = AuroraTextStyles.body2, color = AuroraTokens.Text)
            if (summary != null) {
                Text(text = summary, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
            }
        }
        if (statusSwitch != null) {
            // 胶囊开关：checked=权限状态（已获得=ON/未获得=OFF），点击跳转系统设置而非本地切换
            Switch(
                checked = statusSwitch,
                onCheckedChange = { onClick() },
                enabled = statusSwitchEnabled,
                colors = auroraSwitchColors(),
                modifier = Modifier.scale(0.5f)
            )
        } else {
            endActions?.invoke()
            IconChevron()
        }
    }
}

/**
 * 开关设置项。
 */
@Composable
fun AuroraSwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 主标题：body2(14sp)=body1(16sp)-2，满足设置页「非注释文本字号 -2」
            Text(text = title, style = AuroraTextStyles.body2, color = AuroraTokens.Text)
            if (summary != null) {
                Text(text = summary, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary)
            }
        }
        // M3 开关默认 52x32dp 过大，绘制整体缩小 50%（26x16dp 视觉）
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = auroraSwitchColors(),
            modifier = Modifier.scale(0.5f)
        )
    }
}

@Composable
private fun IconChevron() {
    androidx.compose.material3.Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = AuroraTokens.TextUnselected,
        modifier = Modifier.size(20.dp)
    )
}

/**
 * 输入框统一配色（规范 §4.4）：无描边、容器 8% 白玻璃底、hint 用主色、光标青。
 * 页面 TextField 统一 `colors = auroraTextFieldColors()` + `clip(RoundedCornerShape(0.dp))`。
 */
@Composable
fun auroraTextFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = AuroraTokens.Text,
    unfocusedTextColor = AuroraTokens.Text,
    disabledTextColor = AuroraTokens.TextDisabled,
    cursorColor = AuroraTokens.Accent,
    focusedContainerColor = AuroraTokens.SurfaceStrong,
    unfocusedContainerColor = AuroraTokens.SurfaceStrong,
    disabledContainerColor = AuroraTokens.Surface,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = AuroraTokens.Error,
    focusedLabelColor = AuroraTokens.Accent,
    unfocusedLabelColor = AuroraTokens.TextSecondary,
    disabledLabelColor = AuroraTokens.TextDisabled,
    errorLabelColor = AuroraTokens.Error,
    focusedPlaceholderColor = AuroraTokens.TextHint,
    unfocusedPlaceholderColor = AuroraTokens.TextHint,
    disabledPlaceholderColor = AuroraTokens.TextDisabled,
    focusedLeadingIconColor = AuroraTokens.Accent,
    unfocusedLeadingIconColor = AuroraTokens.TextSecondary,
    disabledLeadingIconColor = AuroraTokens.TextDisabled,
    focusedTrailingIconColor = AuroraTokens.Accent,
    unfocusedTrailingIconColor = AuroraTokens.TextUnselected,
    disabledTrailingIconColor = AuroraTokens.TextDisabled,
    errorTrailingIconColor = AuroraTokens.Error,
)

/**
 * 极简滑杆：一条 1.5dp 细刻度线 + `|` 极细光标（2dp 宽、10dp 高，为常规 20dp 拇指高度的一半）。
 *
 * 相对 M3 Slider 去除圆点拇指 / 立体轨道，只保留语义化的细线交互；
 * 点击任意位置即跳值，横向拖动连续改值，映射区两端各留 2dp 保证光标不裁切。
 */
@Composable
fun AuroraThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .pointerInput(valueRange) {
                val insetPx = 2.dp.toPx()
                fun pick(x: Float) {
                    val usable = (size.width - 2 * insetPx).coerceAtLeast(1f)
                    val fraction = ((x - insetPx) / usable).coerceIn(0f, 1f)
                    val span = valueRange.endInclusive - valueRange.start
                    onValueChange(valueRange.start + fraction * span)
                }
                detectTapGestures { offset -> pick(offset.x) }
            }
            .pointerInput(valueRange) {
                val insetPx = 2.dp.toPx()
                fun pick(x: Float) {
                    val usable = (size.width - 2 * insetPx).coerceAtLeast(1f)
                    val fraction = ((x - insetPx) / usable).coerceIn(0f, 1f)
                    val span = valueRange.endInclusive - valueRange.start
                    onValueChange(valueRange.start + fraction * span)
                }
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    pick(change.position.x)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val insetPx = 2.dp.toPx()
            val thickness = 1.5.dp.toPx()
            val centerY = size.height / 2f
            val usable = (size.width - 2 * insetPx).coerceAtLeast(0f)
            val startX = insetPx
            val endX = insetPx + usable
            val fraction =
                if (valueRange.endInclusive <= valueRange.start) 0f
                else ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val thumbX = insetPx + usable * fraction

            // 全段细刻度线（未选中段）
            drawLine(
                color = AuroraTokens.ControlOff,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = thickness
            )
            // 已选中段（青）
            if (fraction > 0f) {
                drawLine(
                    color = AuroraTokens.Accent,
                    start = Offset(startX, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = thickness
                )
            }
            // | 极细光标：2dp 宽 × 10dp 高，居中覆盖在刻度线上
            val cursorHalfHeight = 5.dp.toPx()
            drawLine(
                color = AuroraTokens.Accent,
                start = Offset(thumbX, centerY - cursorHalfHeight),
                end = Offset(thumbX, centerY + cursorHalfHeight),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
