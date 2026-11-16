// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复制 ->」「移动 ->」两条单键动作的冲突策略护栏。
 *
 * [RootFileManager.moveFile] / [RootFileManager.copyToDirectory] 的默认 onConflict 是
 * OVERWRITE，对同名**目录**目标是递归删除：另一列里那个文件夹连同内容会在「已复制」/
 * 「已移动」的提示下消失。两条入口都必须显式声明策略，且不得退回默认值。
 */
class PaneConflictWiringTest {

    private val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    @Test
    fun `复制到另一列必须显式声明冲突策略`() {
        val body = page.substringAfter("fun copyToOtherPane(item: FileItem)")
            .substringBefore("// 移动到另一列目录")
        assertTrue(
            "不得走默认 OVERWRITE（会静默替换/递归删除目标列同名项）：$body",
            body.contains("onConflict = MoveDestinationConflict.RENAME")
        )
        assertFalse("不得再调用不带策略的重载", body.contains("copyToDirectory(item.path, dest)\n"))
    }

    @Test
    fun `移动到另一列必须先探测冲突并复用同一套策略`() {
        val body = page.substringAfter("fun moveToOtherPane(item: FileItem)")
            .substringBefore("// 打开方式")
        assertTrue("必须先探测同名项：$body", body.contains("RootFileManager.moveDestinationCollides("))
        assertTrue("探测失败按有冲突处理", body.contains("}.getOrElse { true }"))
        assertTrue("冲突时弹既有策略框", body.contains("showConflictDialog = true"))
        assertTrue(
            "执行段必须走 SKIP，探测与执行之间被并发写入时也不覆盖",
            body.contains("onConflict = MoveDestinationConflict.SKIP")
        )
        assertTrue("isMoving 必须在 finally 复位，否则策略框选不动", body.contains("isMoving = false"))
    }
}
