// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package Kernel.Extend.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import Kernel.Extend.data.AppSettings

val TerminalIcon: ImageVector
    get() {
        return ImageVector.Builder(
            name = "Terminal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.5f
            ) {
                moveTo(4f, 7f)
                lineTo(10f, 12f)
                lineTo(4f, 17f)
            }
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.5f
            ) {
                moveTo(12f, 17f)
                lineTo(20f, 17f)
            }
        }.build()
    }

data class DockTabItem(
    val label: String,
    val icon: ImageVector
)

val DOCK_TABS = listOf(
    DockTabItem("主页", MiuixIcons.Home),
    DockTabItem("终端", TerminalIcon),
    DockTabItem("文件", MiuixIcons.Folder),
    DockTabItem("设置", MiuixIcons.Settings)
)

@Composable
fun DockBar(
    selectedPage: Int,
    appTheme: Int = AppSettings.THEME_MATERIAL,
    isFloating: Boolean = false,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (appTheme == AppSettings.THEME_MIUIX && isFloating) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(0.96f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DOCK_TABS.forEachIndexed { index, tab ->
                    val isSelected = selectedPage == index
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                        animationSpec = spring(),
                        label = "FloatingDockColor"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.15f else 1.0f,
                        animationSpec = spring(),
                        label = "FloatingDockScale"
                    )

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MiuixTheme.colorScheme.primary.copy(0.12f)
                                else Color.Transparent
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(index) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier
                                .scale(iconScale)
                                .size(24.dp)
                        )
                    }
                }
            }
        }
    } else if (appTheme == AppSettings.THEME_MIUIX) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DOCK_TABS.forEachIndexed { index, tab ->
                    val isSelected = selectedPage == index
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                        animationSpec = spring(),
                        label = "DockTextColor"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.12f else 1.0f,
                        animationSpec = spring(),
                        label = "DockIconScale"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(index) }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = contentColor,
                                modifier = Modifier
                                    .scale(iconScale)
                                    .size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DOCK_TABS.forEachIndexed { index, tab ->
                    val isSelected = selectedPage == index
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
                        animationSpec = spring(),
                        label = "MaterialDockColor"
                    )
                    val pillColor by animateColorAsState(
                        targetValue = if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                        animationSpec = spring(),
                        label = "MaterialPillColor"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onTabSelected(index) }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(pillColor)
                                    .padding(horizontal = 18.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = contentColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
