// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
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
}
