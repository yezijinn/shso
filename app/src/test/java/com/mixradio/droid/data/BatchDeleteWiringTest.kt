// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 接线护栏：防「批量入口退回逐项 delete」，防「shso 标记被逐段重复失效」，
 * 防「兜底退化成一句 deleteRecursively（对不存在的路径返回 true）」。
 */
class BatchDeleteWiringTest {

    private val rootFileManager =
        File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
    private val filePage =
        File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    /** `deleteBatch(` → `buildDeleteSegmentCommand(` 之间就是批量入口的全部实现。 */
    private val batchBody: String
        get() = rootFileManager.substringAfter("suspend fun deleteBatch(")
            .substringBefore("internal fun buildDeleteSegmentCommand(")

    @Test fun `批量删除必须一次调用而不是逐项 delete`() {
        assertTrue(filePage.contains("RootFileManager.deleteBatch(targets)"))
        assertFalse("逐项 delete 等于每项一个 su 进程", filePage.contains("RootFileManager.delete(p)"))
        assertTrue(rootFileManager.contains("suspend fun deleteBatch("))
        assertTrue("必须是单条 rm 带整段路径", rootFileManager.contains("rm -rf -- "))
        assertTrue("残留回捞走 shell 内建", rootFileManager.contains("printf 'F:%s"))
        assertFalse("su 内逐项 rm 仍每项 fork", rootFileManager.contains("do rm -rf"))
        assertTrue("缺标记必须判未执行", batchBody.contains("if (!parsed.done)"))
    }

    @Test fun `shso 标记失效与历史回收各只有一个出口`() {
        // 标记失效必须逐路径调 —— 批入口只调一次会在 shso 目录不在首位时漏。
        assertEquals(
            "标记失效不得在循环外或按段重复",
            1,
            Regex("invalidateShsoDirIfAffected\\(").findAll(batchBody).count()
        )
        // 计成功只有一个出口，保证「成功必回收历史」不被新增分支绕过。
        assertEquals(
            "历史回收必须收敛到 markDeleted 一处",
            1,
            Regex("EditHistoryManager\\.forget\\(").findAll(batchBody).count()
        )
        assertTrue(batchBody.contains("fun markDeleted(p: String)"))
    }

    @Test fun `Java 兜底必须显式分支而不是裸递归`() {
        val start = rootFileManager.indexOf("private fun deleteLocalRecursively(")
        assertTrue("必须能定位到兜底实现", start > 0)
        val body = rootFileManager.substring(start, minOf(rootFileManager.length, start + 900))
        val linkAt = body.indexOf("Files.isSymbolicLink(")
        val dirAt = body.indexOf("f.isDirectory")
        val recurseAt = body.indexOf("deleteRecursively()")
        assertTrue("先判符号链接", linkAt > 0)
        assertTrue("再判目录", dirAt > linkAt)
        assertTrue("只对真实目录递归", recurseAt > dirAt)
        assertFalse(
            "不得直接对任意路径 deleteRecursively：它对不存在的路径返回 true，" +
                "受保护路径会被记成删除成功",
            body.contains("File(path).deleteRecursively()")
        )
    }
}
