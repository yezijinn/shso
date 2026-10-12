// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端解压回归：`ArchiveExtractor.extract` 在 JVM + 真实文件系统上直接跑，
 * 不依赖真机与 UI 自动化（真机上应用 uid 写 `/data/adb/` 下的路径会被 SELinux 拒绝，无法用于验证）。
 */
class ArchiveExtractorExtractTest {

    private fun zipOf(entries: List<Pair<String, String>>): File {
        val f = File.createTempFile("shso-zip-", ".zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `正常 zip 可解压并保留目录结构`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("shso-x-clean").toFile()
        try {
            val zip = zipOf(listOf("ok.txt" to "LEGIT OK", "sub/normal.txt" to "LEGIT SUB"))
            val parent = File(base, "out").apply { mkdirs() }
            val r = ArchiveExtractor.extract(zip.path, parent.path)
            assertTrue("解压应成功，实际=$r", r is ArchiveExtractor.ExtractResult.Success)
            val dir = (r as ArchiveExtractor.ExtractResult.Success).targetDir
            assertTrue(File(dir, "ok.txt").isFile)
            assertTrue(File(dir, "sub/normal.txt").isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `含穿越条目的 zip 不得写出目标目录`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("shso-x-slip").toFile()
        try {
            val zip = zipOf(
                listOf(
                    "ok.txt" to "LEGIT",
                    "../shso_zip_slip_evil.txt" to "PWNED",
                    "sub/../../shso_zip_slip_deep.txt" to "PWNED DEEP"
                )
            )
            val parent = File(base, "out").apply { mkdirs() }
            val r = ArchiveExtractor.extract(zip.path, parent.path)
            assertTrue("解压应成功，实际=$r", r is ArchiveExtractor.ExtractResult.Success)
            val dir = (r as ArchiveExtractor.ExtractResult.Success).targetDir

            // ① 逃逸到 targetParent 之外的文件不得存在
            assertFalse(File(parent, "shso_zip_slip_evil.txt").exists())
            assertFalse(File(parent, "shso_zip_slip_deep.txt").exists())
            assertFalse(File(base, "shso_zip_slip_evil.txt").exists())
            // ② 应被压回目标目录内（保留文件名）
            assertTrue(File(dir, "shso_zip_slip_evil.txt").isFile)
            assertTrue(File(dir, "shso_zip_slip_deep.txt").isFile)
            // ③ 正常条目不受影响
            assertTrue(File(dir, "ok.txt").isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `指向同一目标的重复条目必须被拒`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("shso-x-dup").toFile()
        try {
            // 两条条目前后写同一个目标：最终内容取决于包内顺序，用户拿到的是「后写的那个」，
            // 且没有任何提示。原先的用例只断言 HashSet.add 的返回值，测的是 JDK 而非这条防线。
            // ZipOutputStream 拒绝写入同名字面量，故用路径别名 —— 归一化后指向同一文件，
            // 正是生产代码按 canonicalPath 判重所覆盖的情形。
            val zip = zipOf(listOf("dup.txt" to "FIRST", "./dup.txt" to "SECOND"))
            val parent = File(base, "out").apply { mkdirs() }
            val r = ArchiveExtractor.extract(zip.path, parent.path)
            assertTrue("重复条目必须被拒，实际=$r", r is ArchiveExtractor.ExtractResult.Failure)
            val fail = r as ArchiveExtractor.ExtractResult.Failure
            assertTrue("失败原因应指明重复条目，实际=${fail.message}", fail.message.contains("重复条目"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `含显式顶层目录条目的归档不得解出 unnamed 占位目录`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("shso-x-topdir").toFile()
        try {
            // 条件 A：根目录仅一个顶层目录 -> 剥离前缀，直接解到以该目录为名的目标里。
            // 顶层目录条目自身（"abc/"）剥离后是空串，若落进占位名分支就会多建一个
            // `unnamed_<hash>_/` 空目录。
            val zip = zipOf(listOf("abc/" to "", "abc/f.txt" to "TOP LEVEL"))
            val parent = File(base, "out").apply { mkdirs() }
            val r = ArchiveExtractor.extract(zip.path, parent.path)
            assertTrue("解压应成功，实际=$r", r is ArchiveExtractor.ExtractResult.Success)
            val dir = File((r as ArchiveExtractor.ExtractResult.Success).targetDir)
            assertEquals("目标目录应为顶层目录名本身", "abc", dir.name)
            assertTrue(File(dir, "f.txt").isFile)
            val stray = dir.listFiles()?.map { it.name }?.filter { it.startsWith("unnamed_") } ?: emptyList()
            assertTrue("目标内不得出现占位空目录，实际残留=$stray", stray.isEmpty())
            assertEquals("目标内应只有剥离后的条目", listOf("f.txt"), dir.listFiles()?.map { it.name }?.sorted())
        } finally {
            base.deleteRecursively()
        }
    }
}
