// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mixradio.droid.data.OwnerCandidates
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

/**
 * 八进制权限串转 9 位开关：每组按 rwx 顺序对应 4 / 2 / 1 位。
 *
 * 只取末三位：首位为特殊位（setuid / setgid / sticky），不在开关矩阵内，回写时由调用方保留。
 * 非法输入返回全 false，交由上层校验提示。
 */
internal fun octalToPermissionBits(mode: String): List<Boolean> {
    if (!RootFileManager.isValidPermissionMode(mode)) return List(PERMISSION_BIT_COUNT) { false }
    val digits = mode.takeLast(3)
    return digits.flatMap { digit ->
        val value = digit - '0'
        listOf(value and 4 != 0, value and 2 != 0, value and 1 != 0)
    }
}

/** 9 位开关转八进制串。`specialMode` 为特殊位前缀（单字符 0–7），为空时输出三位。 */
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
    // 账户选择器目标：null=关闭，"owner"=选所有者，"group"=选用户组
    var pickerTarget by remember(path) { mutableStateOf<String?>(null) }
    var submitting by remember(path) { mutableStateOf(false) }

    fun updateBits(index: Int, checked: Boolean) {
        bits = bits.toMutableList().also { it[index] = checked }
        // 回写时保留特殊位：四位模式的首位是 setuid / setgid / sticky，不来自开关矩阵
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
        OwnerLabelRow(
            label = "所有者",
            enabled = !submitting,
            onPick = { pickerTarget = "owner" }
        )
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
        OwnerLabelRow(
            label = "用户组",
            enabled = !submitting,
            onPick = { pickerTarget = "group" }
        )
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

    // 账户选择器：系统账户 + 已安装应用（应用回填数字 uid，因其不在 passwd 中）
    OwnerPickerDialog(
        show = pickerTarget != null,
        title = if (pickerTarget == "group") "选择用户组" else "选择所有者",
        current = if (pickerTarget == "group") group else owner,
        onPick = { picked ->
            if (pickerTarget == "group") group = picked else owner = picked
            message = null
            pickerTarget = null
        },
        onDismiss = { pickerTarget = null }
    )
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
                    // 行 = 读 / 写 / 执行，列 = 用户 / 用户组 / 其他，与 octalToPermissionBits 的位序一致
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


/**
 * 「所有者 / 用户组」标题行：右侧给出账户列表入口。
 * 只保留手输时用户必须自己记住 `u0_a216` 这类账户名或 uid，实际几乎不可用。
 */
@Composable
private fun OwnerLabelRow(label: String, enabled: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = AuroraTextStyles.body2, color = AuroraTokens.Text)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "选择",
            style = AuroraTextStyles.footnote2,
            color = if (enabled) AuroraTokens.Accent else AuroraTokens.TextDisabled,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onPick)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

/**
 * 账户选择器：系统账户 + 已安装应用，支持按账户名 / uid / 应用名搜索。
 *
 * 选中应用时回填**数字 uid** —— 应用账户不在 `/system/etc/passwd` 中，`chown u0_a216` 会报
 * unknown user，而 `chown 10216` 一定可用。列表仍展示约定账户名与 uid 便于识别。
 */
@Composable
private fun OwnerPickerDialog(
    show: Boolean,
    title: String,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<OwnerCandidates.Entry>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    // PackageManager 枚举需在 IO 线程执行（load 内部已 withContext(Dispatchers.IO)）
    LaunchedEffect(title, current) {
        entries = OwnerCandidates.load(context, current)
    }
    val filtered = remember(entries, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) entries
        else entries.filter {
            it.accountName.contains(keyword, ignoreCase = true) ||
                it.uid.toString().contains(keyword) ||
                it.label.contains(keyword, ignoreCase = true)
        }
    }

    AuroraWindowDialog(
        show = true,
        title = title,
        summary = "共 ${entries.size} 个账户；应用将以数字 uid 填入",
        onDismissRequest = onDismiss
    ) {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索账户名 / uid / 应用名") },
            singleLine = true,
            colors = auroraTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
        ) {
            items(filtered, key = { it.uid.toString() + "|" + it.name }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry.name) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.accountName,
                        style = AuroraTextStyles.footnote2,
                        color = if (entry.name == current.trim()) AuroraTokens.Accent else AuroraTokens.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${entry.uid})",
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = entry.label,
                        style = AuroraTextStyles.footnote2,
                        color = AuroraTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
