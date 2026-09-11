// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 回归守卫：解压条目名的 Zip Slip 防护（`ArchiveExtractor.safeDest`）。
 *
 * 只做词法剥离是不够的 —— 词法层看不见符号链接，`<target>/link -> 外部目录` 仍可逃逸，
 * 因此增加了 `canonicalFile` 的真实路径二次校验。
 */
class ArchiveExtractorZipSlipTest {

    private fun tempBase(): File = java.nio.file.Files.createTempDirectory("shso-zipslip").toFile()

    private fun assertInside(target: File, dest: File, entry: String) {
        val basePath = target.canonicalFile.path
        val destPath = dest.canonicalFile.path
        assertTrue(
            "条目 $entry 逃出了目标目录: $destPath（base=$basePath）",
            destPath == basePath || destPath.startsWith(basePath + File.separator)
        )
    }

    @Test
    fun `穿越型条目名不得逃出目标目录`() {
        val base = tempBase()
        try {
            val target = File(base, "out").apply { mkdirs() }
            val entries = listOf(
                "../evil.txt",
                "../../evil.txt",
                "a/../../evil.txt",
                "a/b/../../../evil.txt",
                "/abs/evil.txt",
                "..\\..\\evil.txt",
                "./../evil.txt"
            )
            for (e in entries) {
                assertInside(target, ArchiveExtractor.safeDest(target.path, e), e)
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `正常条目保留子目录结构且落在目标目录内`() {
        val base = tempBase()
        try {
            val target = File(base, "out").apply { mkdirs() }
            val dest = ArchiveExtractor.safeDest(target.path, "sub/dir/ok.txt")
            assertEquals(File(target, "sub/dir/ok.txt").canonicalFile, dest.canonicalFile)
            assertInside(target, dest, "sub/dir/ok.txt")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `经符号链接逃逸的条目被压回目标目录内`() {
        val base = tempBase()
        try {
            val target = File(base, "out").apply { mkdirs() }
            val outside = File(base, "outside").apply { mkdirs() }
            val link = File(target, "link")
            val linked = try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
                true
            } catch (_: Throwable) {
                false
            }
            // Windows 上创建符号链接通常需要特权；不支持时跳过该用例（其余用例仍覆盖词法层）。
            assumeTrue("当前环境无法创建符号链接，跳过", linked)

            assertInside(target, ArchiveExtractor.safeDest(target.path, "link/pwned.txt"), "link/pwned.txt")
        } finally {
            base.deleteRecursively()
        }
    }
}
