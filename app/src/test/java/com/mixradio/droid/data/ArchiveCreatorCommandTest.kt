// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打包命令的**参数区边界**护栏。
 *
 * 实机 Zip 3.0 已确认：`zip -r -q out.zip -m a.txt` 退出 0 且删掉 `a.txt`。
 * 因此命令里必须存在 `--`，且它必须排在**所有**条目名之前。
 */
class ArchiveCreatorCommandTest {

    private fun cmd(vararg names: String): String = ArchiveCreator.buildCreateZipCommand(
        parent = "/sdcard/x",
        zipBin = "/data/x/env/bin/zip",
        targetZipPath = "/sdcard/x/out.zip",
        libDir = "/data/x/env/lib",
        names = names.toList()
    )

    @Test
    fun `破折号文件名必须落在选项终止符之后`() {
        val c = cmd("-m", "a.txt")
        assertTrue("缺少 -- 会让 zip 把 -m 当成「打包后删除源文件」：$c", c.contains(" -- "))
        assertEquals(
            "条目名必须整体位于 -- 之后",
            " -r -q /sdcard/x/out.zip -- -m a.txt && ",
            c.substringAfter("/data/x/env/bin/zip")
                .substringBefore("test -s")
        )
    }

    @Test
    fun `选项终止符不得被文件名挤到后面`() {
        val c = cmd("-P", "b.txt")
        val dashDash = c.indexOf(" -- ")
        val firstOption = c.indexOf(" -r -q ")
        assertTrue("-- 必须存在", dashDash > 0)
        assertTrue("-r -q 必须在 -- 之前", firstOption in 0 until dashDash)
        assertTrue("两个名字都必须整体落在 -- 之后", c.contains(" -- -P b.txt && "))
    }

    @Test
    fun `普通名字与含空格名字都不受影响`() {
        val c = cmd("a.txt", "b c.txt")
        assertTrue("普通名字原样保留", c.contains(" -- a.txt 'b c.txt' && "))
    }

    @Test
    fun `目录名递归与内置环境注入保持原样`() {
        val c = cmd("dir")
        assertTrue("必须注入内置 lib", c.contains("LD_LIBRARY_PATH=/data/x/env/lib:\$LD_LIBRARY_PATH "))
        assertTrue("必须仍在父目录内执行", c.startsWith("cd /sdcard/x && rm -f /sdcard/x/out.zip && "))
        assertTrue("递归开关不得丢", c.contains(" -r -q "))
        assertTrue("结果非空校验不得丢", c.contains("&& test -s /sdcard/x/out.zip"))
    }
}
