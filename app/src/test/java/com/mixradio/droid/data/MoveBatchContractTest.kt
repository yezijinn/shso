// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 批量移动与空目录刷新的护栏。 */
class MoveBatchContractTest {

    private fun page(): String =
        File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    private fun rfm(): String =
        File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()

    @Test
    fun `空目录与搜索过滤不得触发自愈重读`() {
        val s = page()
        // 判据看权威 fileList，不看派生的 displayFileList（搜索过滤会让它为空）
        assertTrue("判据必须看 fileList", s.contains("pane.fileList.isEmpty() && !pane.retriedForEmptyOnce && !listedViaRoot"))
        assertFalse("不得再单独用 displayFileList 判空", s.contains("val stillSilent = pane.displayFileList.isEmpty()"))
        assertTrue("su 权威空必须跳过重读", s.contains("!listedViaRoot"))
        assertTrue("延迟回调须复核代次", s.contains("if (gen != pane.refreshGenRef[0]) return@launch"))
    }

    @Test
    fun `批量移动命令是单条脚本带完成标记`() {
        val s = rfm()
        assertTrue(s.contains("internal const val MOVE_SEGMENT_DONE_MARKER"))
        assertTrue(s.contains("suspend fun moveBatch("))
        assertTrue(s.contains("suspend fun moveBatchConflicts("))
        // 完成标记无条件打印且是最后一句
        assertTrue(s.contains("sb.append(\"echo \").append(MOVE_SEGMENT_DONE_MARKER)"))
        // 段内不逐项拼 mv（那会每项 fork 一次）
        assertFalse("不得写成 for 循环逐项 mv", s.contains("for p in \"\\$@\"; do"))
    }

    @Test
    fun `moveBatch 分段按 UTF-8 字节且不超过上限`() {
        val items = (0 until 200).map {
            RootFileManager.MovePlanItem(
                "/storage/emulated/0/DCIM/Camera/IMG_20261113_${it.toString().padStart(4, '0')}.jpg",
                "/storage/emulated/0/Download/IMG_20261113_${it.toString().padStart(4, '0')}.jpg",
                "/storage/emulated/0/Download/IMG_20261113_${it.toString().padStart(4, '0')}_new.jpg"
            )
        }
        val segs = RootFileManager.planMoveSegments(items, MoveDestinationConflict.RENAME)
        assertTrue("200 项必须分段", segs.size >= 2)
        assertEquals("分段保序不丢项", items, segs.flatten())
        segs.forEach { seg ->
            val cmd = RootFileManager.buildMoveSegmentCommand(seg, MoveDestinationConflict.RENAME)
            assertTrue(
                "段命令 ${cmd.toByteArray(Charsets.UTF_8).size} 字节超上限",
                cmd.toByteArray(Charsets.UTF_8).size <= RootFileManager.MOVE_SEGMENT_MAX_BYTES
            )
        }
    }

    @Test
    fun `移动段输出按索引解析且完成标记整行比对`() {
        val out = "K:0:d\nS:1\nC:2\nX:3\nSHSO_MOVE_OK\n"
        val p = RootFileManager.parseMoveSegmentOutput(out, 4)
        assertTrue(p.done)
        assertEquals('K', p.codes[0])
        assertEquals('d', p.targets[0])
        assertEquals('S', p.codes[1])
        assertEquals('C', p.codes[2])
        assertEquals('X', p.codes[3])
        assertFalse(RootFileManager.parseMoveSegmentOutput("XSHSO_MOVE_OK\n", 1).done)
    }

    @Test
    fun `目标即源不得删除自身`() {
        val s = rfm()
        assertTrue("moveFile 须有自身目标守卫", s.contains("if (destinationPath == sourceNormalized && onConflict != MoveDestinationConflict.RENAME)"))
        assertTrue("目录须拒绝覆盖自身", s.contains("\"目标与源相同，不能覆盖自身\""))
    }

    @Test
    fun `rename 折叠为单条 su 且保留防覆盖`() {
        val all = rfm()
        val start = all.indexOf("suspend fun rename(")
        // 只取 rename 函数体（destinationExists 的定义紧随其后，不能算进来）
        val end = all.indexOf("private suspend fun destinationExists(", start)
        val body = all.substring(start, end)
        assertTrue("不覆盖判定须并入 su 同一条命令", body.contains("printf 'EXISTS") && body.contains("exit 2"))
        assertFalse("不得再单独调 destinationExists", body.contains("destinationExists("))
        assertEquals("成功路径仍两处 renamed()", 2, body.split("return@withContext renamed()").size - 1)
    }
}
