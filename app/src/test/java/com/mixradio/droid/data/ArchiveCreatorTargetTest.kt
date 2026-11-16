// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打包目标名的**数据安全**护栏。
 *
 * [ArchiveCreator.buildCreateZipCommand] 的前缀是 `rm -f <target>`，因此：
 * 1. 目标名绝不能等于源路径（`foo.zip` → `foo.zip` 会把唯一的源删掉）；
 * 2. 目标名绝不能落在已存在的文件上（静默覆盖无关归档）；
 * 3. 分配不出来时必须返回 null，由调用方拒绝，而不是退回一个已存在的名字。
 */
class ArchiveCreatorTargetTest {

    private fun target(
        parent: String,
        name: String,
        taken: suspend (String) -> Boolean = { false }
    ): String? = runBlocking { ArchiveCreator.uniqueZipTarget(parent, name, taken) }

    @Test
    fun `zip 文件不得把自己当目标`() {
        assertEquals("/sdcard/t/foo.zip.zip", target("/sdcard/t", "foo.zip"))
        assertEquals("/sdcard/t/a.b.zip.zip", target("/sdcard/t", "a.b.zip"))
        assertEquals("/sdcard/t/.zip.zip", target("/sdcard/t", ".zip"))
    }

    @Test
    fun `目标永不等于源路径且按大小写不敏感比较`() {
        for (name in listOf("a.zip", "a.ZIP", "A.Zip", "a", "a.txt", ".zip", "a.b.zip", "a b.zip", "foo.")) {
            val t = target("/d", name)
            assertNotNull("必须能给出目标：$name", t)
            assertTrue(
                "目标不得与源同名（大小写不敏感）：$name -> $t",
                t!!.substringAfterLast('/').lowercase() != name.lowercase()
            )
        }
    }

    @Test
    fun `已存在的同名归档必须自动改名而不是覆盖`() {
        val taken = setOf("/sdcard/t/photo.zip", "/sdcard/t/photo(2).zip")
        assertEquals("/sdcard/t/photo(3).zip", target("/sdcard/t", "photo.jpg") { it in taken })
    }

    @Test
    fun `探测函数真的被用来避开已存在的目标`() {
        assertNull(target("/t", "x.txt") { true })
        assertEquals("/t/x(2).zip", target("/t", "x.txt") { it == "/t/x.zip" })
    }

    @Test
    fun `根目录拼接与普通名保持不变`() {
        assertEquals("/x.zip", target("/", "x.txt"))
        assertEquals("/sdcard/t/a.zip", target("/sdcard/t", "a.txt"))
    }

    @Test
    fun `目录名同样按名字派生且不与自身撞名`() {
        assertEquals("/t/dir.zip", target("/t", "dir"))
        assertEquals("/t/dir.zip.zip", target("/t", "dir.zip"))
    }

    @Test
    fun `数据层必须拒绝源与目标同名`() {
        assertNotNull(ArchiveCreator.sourceCollisionReason(listOf("/d/foo.zip"), "/d/foo.zip"))
        assertNotNull(
            "`.` 归一后同名也必须拒绝",
            ArchiveCreator.sourceCollisionReason(listOf("/d/a.txt", "/d/./foo.zip"), "/d/foo.zip")
        )
        assertNotNull(
            "`..` 归一后同名也必须拒绝",
            ArchiveCreator.sourceCollisionReason(listOf("/d/x/../foo.zip"), "/d/foo.zip")
        )
        assertNull(ArchiveCreator.sourceCollisionReason(listOf("/d/a.txt"), "/d/a.zip"))
        assertNull(ArchiveCreator.sourceCollisionReason(listOf("/d/a.zip"), "/d/a.zip.zip"))
    }

    @Test
    fun `数据层兜底必须排在命令拼接之前`() {
        val src = File("src/main/java/com/mixradio/droid/data/ArchiveCreator.kt").readText()
        val guard = src.indexOf("sourceCollisionReason(sourcePaths, targetZipPath)")
        val cmd = src.indexOf("buildCreateZipCommand(parent, zipBin, targetZipPath, libDir, names)")
        assertTrue("兜底必须存在且先于命令拼接：guard=$guard cmd=$cmd", guard in 0 until cmd)
    }

    @Test
    fun `页面入口必须用探测函数选目标并防重复点击`() {
        val src = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()
        val body = src.substringAfter("fun addToArchive(item: FileItem)")
            .substringBefore("// 复制到另一列目录")
        assertTrue("必须改用 uniqueZipTarget：$body", body.contains("ArchiveCreator.uniqueZipTarget("))
        assertTrue("必须传 ROOT 感知的存在性探测", body.contains("RootFileManager.pathExists(it)"))
        assertTrue("必须防重复点击", body.contains("if (isArchiving) return"))
        assertTrue("标志必须复位，否则一次失败后入口永久禁用", body.contains("isArchiving = false"))
    }
}
