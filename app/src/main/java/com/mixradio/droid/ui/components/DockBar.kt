// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.auroraGlass

data class DockTabItem(
    val label: String,
    val icon: ImageVector
)

val DOCK_TABS = listOf(
    DockTabItem("主页", Icons.Filled.Home),
    DockTabItem("终端", Icons.Filled.Terminal),
    DockTabItem("文件", Icons.Filled.Folder),
    DockTabItem("设置", Icons.Filled.Settings)
)

@Composable
fun DockBar(
    selectedPage: Int,
    onTabSelected: (Int) -> Unit,
    terminalLocked: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .auroraGlass(
                fill = Brush.verticalGradient(
                    listOf(Color(0x1AFFFFFF), Color(0x0AFFFFFF))
                ),
                stroke = AuroraTokens.StrokeLight,
                strokeWidth = 1.dp
            )
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DOCK_TABS.forEachIndexed { index, tab ->
                val isSelected = selectedPage == index
                val isTerminalLocked = index == 1 && terminalLocked
                val contentColor = when {
                    isTerminalLocked -> AuroraTokens.Error
                    isSelected -> AuroraTokens.Accent
                    else -> AuroraTokens.TextUnselected
                }
                val pillColor = when {
                    isTerminalLocked -> Color.Transparent
                    isSelected -> AuroraTokens.Accent.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // 无 ROOT 也允许点开终端页（仅保留红色字体提示）
                            onTabSelected(index)
                        }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .background(pillColor)
                                .padding(horizontal = 18.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = contentColor,
                                modifier = Modifier.size(22.dp)
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
