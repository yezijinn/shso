// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 批量删除的分段 / 命令构造 / 输出解析：纯词法，JVM 全覆盖。
 *
 * 本类同时还是一道**加载护栏**：能跑到任何一条用例，就说明 RootFileManager
 * 的静态初始化没有引入 Android 依赖（否则会 ExceptionInInitializerError）。
 */
class BatchDeleteSegmentTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun cmdBytes(seg: List<String>) = bytes(RootFileManager.buildDeleteSegmentCommand(seg))

    @Test fun `空集分成零段`() {
        assertEquals(emptyList<List<String>>(), RootFileManager.planDeleteSegments(emptyList()))
    }

    /** 反测开销必须与真实构造成本自洽，否则分段上限会被悄悄突破。 */
    @Test fun `段开销反测与真实构造成本一致`() {
        val costA = bytes(RootFileManager.buildDeleteSegmentCommand(listOf("/a"))) -
            RootFileManager.deleteSegmentOverheadBytes
        val costB = bytes(RootFileManager.buildDeleteSegmentCommand(listOf("/a", "/b"))) -
            RootFileManager.deleteSegmentOverheadBytes
        assertEquals("单项成本 = 转义长 + 1 个前导空格", "/a".length + 1, costA)
        assertEquals(costA + "/b".length + 1, costB)
    }

    @Test fun `每段命令都不超过字节上限且路径不丢不改序`() {
        val paths = (0 until 400).map {
            "/storage/emulated/0/DCIM/Camera/IMG_20261112_${it.toString().padStart(4, '0')}.jpg"
        }
        val segs = RootFileManager.planDeleteSegments(paths)
        assertTrue("400 项必须分成多段", segs.size >= 2)
        segs.forEach { seg ->
            assertTrue(seg.isNotEmpty())
            assertTrue(
                "段命令 ${cmdBytes(seg)} 字节超过上限",
                cmdBytes(seg) <= RootFileManager.DELETE_SEGMENT_MAX_BYTES
            )
        }
        assertEquals("分段必须保序且不丢项", paths, segs.flatten())
    }

    @Test fun `中文路径按 UTF-8 字节计长`() {
        // 40 个汉字 = 120 字节；按 String.length 只有 40，会低估三倍
        val name = "中".repeat(40) + ".txt"
        assertEquals(44, name.length)
        assertEquals(124, bytes(name))
        val paths = List(40) { "/storage/emulated/0/下载/$it-$name" }
        val segs = RootFileManager.planDeleteSegments(paths)
        segs.forEach { assertTrue(cmdBytes(it) <= RootFileManager.DELETE_SEGMENT_MAX_BYTES) }
        assertTrue("按字节装箱必然多段", segs.size >= 2)
    }

    @Test fun `超长单项单独成段且不被静默丢弃`() {
        val long = "/data/local/tmp/" + "a".repeat(3000)
        assertFalse(RootFileManager.deletePathFitsOneSegment(long))
        val segs = RootFileManager.planDeleteSegments(listOf("/a", long, "/b"))
        assertEquals("/a", segs.first().single())
        assertEquals("/b", segs.last().single())
        assertTrue("超长项仍在结果里", segs.flatten().contains(long))
    }

    @Test fun `命令是一条 rm 带整批而不是逐项 rm`() {
        val cmd = RootFileManager.buildDeleteSegmentCommand(listOf("/a b", "/c"))
        assertTrue(cmd.startsWith("set -- '/a b' /c\n"))
        assertTrue("必须一条 rm 带整批", cmd.contains("rm -rf -- \"\$@\" 2>/dev/null"))
        assertFalse("逐项 rm 会退化成每项一个进程", cmd.contains("do rm -rf"))
        assertEquals("只允许一条 rm", 1, Regex("rm -rf").findAll(cmd).count())
    }

    /** 顺序即正确性：所有 F: 行都必须先于标记，否则「标记在」不能推出「回捞完整」。 */
    @Test fun `完成标记必须是命令最后一句`() {
        val cmd = RootFileManager.buildDeleteSegmentCommand(listOf("/a"))
        val lines = cmd.trimEnd().lines()
        // 命令末行是 `echo SHSO_BATCH_OK`，不是标记本身 —— 断言裸标记会红
        assertEquals("echo " + RootFileManager.DELETE_SEGMENT_DONE_MARKER, lines.last())
        assertTrue(
            "回捞行必须先于标记",
            lines.indexOfFirst { it.contains("printf") } in 0 until lines.lastIndex
        )
    }

    @Test fun `单引号与空格路径被正确转义`() {
        val cmd = RootFileManager.buildDeleteSegmentCommand(listOf("/sdcard/it's a.txt"))
        assertTrue(cmd.contains("'/sdcard/it'\\''s a.txt'"))
    }

    @Test fun `有完成标记且无残留行即全部成功`() {
        val p = RootFileManager.parseDeleteSegmentOutput("SHSO_BATCH_OK\n", listOf("/a", "/b"))
        assertTrue(p.done)
        assertTrue(p.remaining.isEmpty())
        // 行尾 CR 不影响整行比对：lineSequence 会把 \r\n 当分隔符
        assertTrue(
            RootFileManager.parseDeleteSegmentOutput("SHSO_BATCH_OK\r\n", listOf("/a")).done
        )
    }

    @Test fun `缺完成标记判整段未执行`() {
        assertFalse(RootFileManager.parseDeleteSegmentOutput("", listOf("/a")).done)
        assertFalse(
            "超时提示行里没有标记",
            RootFileManager.parseDeleteSegmentOutput(
                "\n[shso] 命令执行超时（>30000ms），已强制终止", listOf("/a")
            ).done
        )
        assertFalse(
            "输出被截断时也没有标记",
            RootFileManager.parseDeleteSegmentOutput(
                "F:/a\n[shso] 输出超过 4194304 字符上限，已截断", listOf("/a")
            ).done
        )
    }

    @Test fun `相似子串不得当作完成标记`() {
        assertFalse(
            RootFileManager.parseDeleteSegmentOutput(
                "XSHSO_BATCH_OK\nSHSO_BATCH_OK_2\nSHSO_BATCH_OK \n", listOf("/a")
            ).done
        )
    }

    @Test fun `残留行按整行回捞且只认本段路径`() {
        val out = "F:/a\nF:/other-seg\nF:/b\nSHSO_BATCH_OK\n"
        val p = RootFileManager.parseDeleteSegmentOutput(out, listOf("/a", "/b"))
        assertEquals(setOf("/a", "/b"), p.remaining)
    }

    @Test fun `F 前缀但非残留行不产生假失败`() {
        val p = RootFileManager.parseDeleteSegmentOutput("FOO\nF:\nSHSO_BATCH_OK\n", listOf("/a"))
        assertTrue(p.remaining.isEmpty())
    }

    @Test fun `段超时随条数放宽且封顶`() {
        assertEquals(15_300L, RootFileManager.deleteSegmentTimeoutMs(1))
        assertTrue(
            RootFileManager.deleteSegmentTimeoutMs(100) > RootFileManager.deleteSegmentTimeoutMs(10)
        )
        assertEquals(120_000L, RootFileManager.deleteSegmentTimeoutMs(10_000))
    }
}
