// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mixradio.droid.data.RootFileManager
import com.mixradio.droid.ui.theme.AuroraTextStyles
import com.mixradio.droid.ui.theme.AuroraTokens
import com.mixradio.droid.ui.theme.AuroraWindowDialog
import com.mixradio.droid.ui.theme.auroraFilledButton
import com.mixradio.droid.ui.theme.auroraTextFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PERMISSION_BIT_COUNT = 9

internal fun octalToPermissionBits(mode: String): List<Boolean> {
    if (!RootFileManager.isValidPermissionMode(mode)) return List(PERMISSION_BIT_COUNT) { false }
    val digits = mode.takeLast(3)
    return digits.flatMap { digit ->
        val value = digit - '0'
        listOf(value and 4 != 0, value and 2 != 0, value and 1 != 0)
    }
}

internal fun permissionBitsToOctal(bits: List<Boolean>, specialMode: String = ""): String {
    require(bits.size == PERMISSION_BIT_COUNT)
    require(specialMode.isEmpty() || (specialMode.length == 1 && specialMode[0] in '0'..'7'))
    val mode = bits.chunked(3).joinToString("") { group ->
        group.mapIndexed { index, enabled -> if (enabled) (4 shr index) else 0 }.sum().toString()
    }
    return specialMode + mode
}

@Composable
fun FilePermissionDialog(
    show: Boolean,
    path: String,
    initialMode: String,
    initialOwner: String,
    initialGroup: String,
    onDismiss: () -> Unit,
    onSubmitSuccess: () -> Unit = {},
    onSubmit: suspend (mode: String, owner: String, group: String) -> Pair<Boolean, String>,
) {
    if (!show) return

    val scope = rememberCoroutineScope()
    var mode by remember(path, initialMode) { mutableStateOf(initialMode) }
    var owner by remember(path, initialOwner) { mutableStateOf(initialOwner) }
    var group by remember(path, initialGroup) { mutableStateOf(initialGroup) }
    var bits by remember(path, initialMode) { mutableStateOf(octalToPermissionBits(initialMode)) }
    var message by remember(path) { mutableStateOf<String?>(null) }
    var submitting by remember(path) { mutableStateOf(false) }

    fun updateBits(index: Int, checked: Boolean) {
        bits = bits.toMutableList().also { it[index] = checked }
        val prefix = if (mode.length == 4 && mode.first().isDigit()) mode.take(1) else ""
        mode = permissionBitsToOctal(bits, prefix)
        message = null
    }

    AuroraWindowDialog(
        show = true,
        title = "文件权限与所有者",
        summary = path,
        onDismissRequest = onDismiss
    ) {
        Text("权限", style = AuroraTextStyles.body2, color = AuroraTokens.Text)
        Spacer(modifier = Modifier.height(6.dp))
        PermissionMatrix(bits = bits, onToggle = ::updateBits)
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = mode,
            onValueChange = { value ->
                if (value.length <= 4 && value.all { it in '0'..'9' }) {
                    mode = value
                    if (RootFileManager.isValidPermissionMode(value)) bits = octalToPermissionBits(value)
                    message = null
                }
            },
            label = { Text("八进制权限") },
            singleLine = true,
            enabled = !submitting,
            colors = auroraTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("所有者", style = AuroraTextStyles.body2, color = AuroraTokens.Text)
        Spacer(modifier = Modifier.height(4.dp))
        TextField(
            value = owner,
            onValueChange = { owner = it; message = null },
            label = { Text("Owner") },
            singleLine = true,
            enabled = !submitting,
            colors = auroraTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text("用户组", style = AuroraTextStyles.body2, color = AuroraTokens.Text)
        Spacer(modifier = Modifier.height(4.dp))
        TextField(
            value = group,
            onValueChange = { group = it; message = null },
            label = { Text("Group") },
            singleLine = true,
            enabled = !submitting,
            colors = auroraTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetButton("root:root") { owner = "root"; group = "root"; message = null }
            PresetButton("system:system") { owner = "system"; group = "system"; message = null }
        }
        if (message != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(message.orEmpty(), style = AuroraTextStyles.footnote2, color = AuroraTokens.Error)
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            PresetButton("取消", enabled = !submitting, onClick = onDismiss)
            PresetButton("保存", enabled = !submitting) {
                if (!RootFileManager.isValidPermissionMode(mode) ||
                    !RootFileManager.isValidOwnerOrGroup(owner) ||
                    !RootFileManager.isValidOwnerOrGroup(group)
                ) {
                    message = "权限、所有者或用户组格式无效"
                    return@PresetButton
                }
                submitting = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { onSubmit(mode, owner, group) }
                    submitting = false
                    if (result.first) {
                        onSubmitSuccess()
                        onDismiss()
                    } else {
                        message = result.second
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionMatrix(bits: List<Boolean>, onToggle: (Int, Boolean) -> Unit) {
    val labels = listOf("用户", "用户组", "其他")
    val permissions = listOf("读", "写", "执行")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            labels.forEach { label ->
                Text(label, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary, modifier = Modifier.weight(1f))
            }
        }
        permissions.forEachIndexed { row, permission ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(permission, style = AuroraTextStyles.footnote2, color = AuroraTokens.TextSecondary, modifier = Modifier.weight(1f))
                repeat(3) { column ->
                    val index = row * 3 + column
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        PermissionToggle(bits[index], { onToggle(index, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .semantics { role = Role.Checkbox }
            .clip(RoundedCornerShape(0.dp))
            .border(1.dp, if (checked) AuroraTokens.Accent else AuroraTokens.Stroke, RoundedCornerShape(0.dp))
            .clickable(onClick = { onCheckedChange(!checked) }),
        contentAlignment = Alignment.Center
    ) {
        if (checked) Text("✓", color = AuroraTokens.Accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PresetButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = AuroraTokens.SurfaceHover,
            contentColor = AuroraTokens.Text,
            disabledContainerColor = AuroraTokens.Surface,
            disabledContentColor = AuroraTokens.TextDisabled
        ),
        modifier = Modifier.auroraFilledButton()
    ) {
        Text(text)
    }
}
