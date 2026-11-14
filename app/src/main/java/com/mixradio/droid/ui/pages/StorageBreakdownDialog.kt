// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mixradio.droid.data.StorageBreakdown
import com.mixradio.droid.ui.theme.AuroraTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 人类可读的字节数。 */
private fun humanBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}

/**
 * 存储组成全屏页。
 *
 * 用全屏 Dialog 承载（与文本编辑器同法）：usePlatformDefaultWidth=false + fillMaxSize。
 * 进入即异步测量，测量在 IO 线程，结果按「运行期数据 / APK 组成」两栏列出。
 */
@Composable
fun StorageBreakdownDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<StorageBreakdown.Report?>(null) }
    var measuring by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        measuring = true
        report = runCatching { StorageBreakdown.measure(context) }.getOrNull()
        measuring = false
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AuroraTokens.DialogBg,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AuroraTokens.Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("存储组成", color = AuroraTokens.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "本应用在本机占用的全部空间",
                            color = AuroraTokens.TextSecondary, fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onDismissRequest,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraTokens.SurfaceHover,
                            contentColor = AuroraTokens.Text
                        )
                    ) { Text("返回") }
                }

                if (measuring && report == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AuroraTokens.Accent)
                            Spacer(Modifier.height(12.dp))
                            Text("正在统计各目录…", color = AuroraTokens.TextSecondary, fontSize = 13.sp)
                        }
                    }
                    return@Column
                }

                val data = report
                if (data == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("统计失败", color = AuroraTokens.Error, fontSize = 14.sp)
                    }
                    return@Column
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    val stamp = remember(data.generatedAtMillis) {
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(data.generatedAtMillis))
                    }
                    Text(
                        "统计时间 $stamp · 数字为磁盘字节数 · 软链不跟随计数 · " +
                            "共享存储（Download）不计入",
                        color = AuroraTokens.TextSecondary, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(4.dp))

                    // 总览
                    SummaryCard(
                        "运行期数据合计", data.runtimeTotal,
                        "APK 安装包", data.apkFileBytes
                    )
                    Spacer(Modifier.height(10.dp))

                    SectionTitle("运行期数据（本机实际占用）")
                    data.runtime.groupBy { it.group }.forEach { (group, items) ->
                        // 组小计让「内置环境总共占多少」仍然一眼可见，
                        // 又不必在列表里塞一条与各组件重复相加的总数条目。
                        GroupHeader(group, items.sumOf { if (it.unavailable) 0L else it.bytes })
                        items.forEach { ItemRow(it) }
                        Spacer(Modifier.height(6.dp))
                    }

                    Spacer(Modifier.height(6.dp))
                    SectionTitle("APK 安装包组成（静态）")
                    data.apk.forEach { ItemRow(it) }

                    Text(
                        "APK 组成是安装包内部构成，与运行期数据是两套账，不重复相加。",
                        color = AuroraTokens.TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(runtimeLabel: String, runtime: Long, apkLabel: String, apk: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .background(AuroraTokens.SurfaceVariant)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        SummaryCell(runtimeLabel, runtime, AuroraTokens.Accent)
        Box(Modifier.width(1.dp).height(40.dp).background(AuroraTokens.Stroke))
        SummaryCell(apkLabel, apk, AuroraTokens.AccentViolet)
    }
}

@Composable
private fun SummaryCell(label: String, bytes: Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AuroraTokens.TextSecondary, fontSize = 11.sp)
        Text(humanBytes(bytes), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            Modifier
                .size(3.dp, 16.dp)
                .background(AuroraTokens.Accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = AuroraTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = AuroraTokens.Stroke)
}

@Composable
private fun GroupHeader(group: String, totalBytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = group,
            color = AuroraTokens.AccentBright,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(text = humanBytes(totalBytes), color = AuroraTokens.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun ItemRow(item: StorageBreakdown.Item) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, color = AuroraTokens.Text, fontSize = 13.sp)
            Text(item.path, color = AuroraTokens.TextSecondary, fontSize = 10.sp)
            if (item.note.isNotEmpty()) {
                Text(item.note, color = AuroraTokens.TextDisabled, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (item.unavailable) "—" else humanBytes(item.bytes),
            color = if (item.unavailable) AuroraTokens.TextDisabled else AuroraTokens.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
