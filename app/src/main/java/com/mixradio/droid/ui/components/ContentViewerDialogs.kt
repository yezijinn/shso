// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片浏览弹窗（jpg/jpeg/png/bmp/gif/webp/ico/tiff/tif）：
 * - 伪全屏设计：四周 1px 黑色边距，填满屏幕 98%
 * - 右上角：关闭按钮
 * - 右下角：← 上一张 / → 下一张 / 顺时针旋转 90°
 * - 双指缩放 / 拖动平移（transformable）
 * - 大图降采样（≤2048px）防 OOM
 * - 矩形化铁律：零圆角
 *
 * @param images     当前目录下所有可浏览图片的有序列表
 * @param initialIndex  当前点击图片在列表中的索引
 * @param onDismiss 关闭回调
 */
@Composable
fun ImageViewerDialog(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    // 状态
    var currentIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, images.lastIndex)) }
    var bitmap by remember(images.getOrNull(currentIndex) ?: "") { mutableStateOf<android.graphics.Bitmap?>(null) }
    var decodeError by remember(images.getOrNull(currentIndex) ?: "") { mutableStateOf<String?>(null) }
    var rotation by remember { mutableFloatStateOf(0f) }           // 累计旋转角度
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val currentPath = images.getOrNull(currentIndex) ?: ""

    // 加载图片（路径或索引变化时触发）
    LaunchedEffect(currentPath) {
        bitmap = null
        decodeError = null
        if (currentPath.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(currentPath, opts)
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                var sample = 1
                while (maxDim / (sample * 2) >= 2048) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                bitmap = BitmapFactory.decodeFile(currentPath, decodeOpts)
                if (bitmap == null) decodeError = "无法解码该图片"
            } catch (e: Exception) {
                decodeError = "读取失败: ${e.message}"
            }
        }
    }

    // 重置变换（换图时）
    LaunchedEffect(currentPath) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        // rotation 保留，用户手动旋转的状态跨图片保持
    }

    // 首参 centroid 为变换中心点；本视图以图片中心为锚点，忽略该值以保持原有缩放/平移手感。
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 8f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    val total = images.size

    // 图标样式：体积约为 title3 的 3 倍（title3=16sp → 48sp）+ 加粗（Black）
    // 颜色：极光渐变（与 AuroraAccentBar 一致：青 → 蓝 → 紫），与 APP 整体主题统一
    val auroraIconBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
        colors = listOf(
            AuroraTokens.GlowCyan,
            AuroraTokens.GlowBlue,
            AuroraTokens.AccentViolet
        )
    )
    val iconStyle = AuroraTextStyles.title3.copy(
        fontSize = 48.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
        brush = auroraIconBrush
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, Color.Black),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                // 图片区域（撑满）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp),          // 四周 1px 边距（伪全屏留黑边）
                    contentAlignment = Alignment.Center
                ) {
                    val currentBitmap = bitmap
                    when {
                        currentBitmap != null -> {
                            Image(
                                bitmap = currentBitmap.asImageBitmap(),
                                contentDescription = File(currentPath).name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        rotationZ = rotation,
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY
                                    )
                                    .transformable(transformState)
                            )
                        }
                        decodeError != null -> {
                            Text(
                                text = decodeError!!,
                                style = AuroraTextStyles.body1,
                                color = Color.Red
                            )
                        }
                        else -> {
                            Text(
                                text = "正在加载…",
                                style = AuroraTextStyles.body2,
                                color = Color.Gray
                            )
                        }
                    }
                }

                // 关闭入口统一在右下角按钮行，右上角不另设关闭按钮

                // 右下角操作行：上一张 / 下一张 / 顺时针旋转 90° / 关闭 + 计数（透明背景，极光渐变文字）
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ← 上一张
                    Text(
                        text = "←",
                        style = if (currentIndex > 0) iconStyle
                        else iconStyle.copy(brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(
                                AuroraTokens.GlowCyan.copy(alpha = 0.35f),
                                AuroraTokens.GlowBlue.copy(alpha = 0.35f),
                                AuroraTokens.AccentViolet.copy(alpha = 0.35f)
                            )
                        )),
                        modifier = Modifier
                            .clickable(enabled = currentIndex > 0) {
                                currentIndex--
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )

                    // 中间计数（沿用极光色但用小号文字样式）
                    Text(
                        text = "${currentIndex + 1}/$total",
                        style = AuroraTextStyles.footnote2.copy(brush = auroraIconBrush)
                    )

                    // → 下一张
                    Text(
                        text = "→",
                        style = if (currentIndex < total - 1) iconStyle
                        else iconStyle.copy(brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(
                                AuroraTokens.GlowCyan.copy(alpha = 0.35f),
                                AuroraTokens.GlowBlue.copy(alpha = 0.35f),
                                AuroraTokens.AccentViolet.copy(alpha = 0.35f)
                            )
                        )),
                        modifier = Modifier
                            .clickable(enabled = currentIndex < total - 1) {
                                currentIndex++
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )

                    // 顺时针旋转 90°
                    Text(
                        text = "↻",
                        style = iconStyle,
                        modifier = Modifier
                            .clickable {
                                rotation = (rotation + 90f) % 360f
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )

                    // 右下角关闭（快捷关闭）
                    Text(
                        text = "✕",
                        style = iconStyle,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
