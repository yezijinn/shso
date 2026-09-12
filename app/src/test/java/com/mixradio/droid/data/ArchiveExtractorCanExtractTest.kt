// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 守卫测试：解压入口的可写性判定（`ArchiveExtractor.canExtractTo`）。
 *
 * 背景：解压以应用自身 uid 落盘，`/data/adb/` 受 SELinux 限制，
 * 即使 `chmod 777` 也写不进去，因此解压入口不得指向该路径。
 */
class ArchiveExtractorCanExtractTest {

    @Test
    fun `普通可写目录可解压`() {
        val d = java.nio.file.Files.createTempDirectory("shso-w").toFile()
        try {
            assertTrue(ArchiveExtractor.canExtractTo(d.path))
        } finally {
            d.deleteRecursively()
        }
    }

    @Test
    fun `不存在的目录不可解压`() {
        assertFalse(ArchiveExtractor.canExtractTo("/_shso_definitely_not_exist_/x"))
    }

    @Test
    fun `文件而非目录不可解压`() {
        val f = File.createTempFile("shso-f", ".txt")
        try {
            assertFalse(ArchiveExtractor.canExtractTo(f.path))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `只读目录不可解压`() {
        val d = java.nio.file.Files.createTempDirectory("shso-ro").toFile()
        try {
            val madeReadOnly = d.setWritable(false)
            // 部分平台/文件系统不支持去掉目录写权限，不支持则跳过
            assumeTrue("当前平台无法将目录设为只读，跳过", madeReadOnly)
            assertFalse(ArchiveExtractor.canExtractTo(d.path))
        } finally {
            d.setWritable(true)
            d.deleteRecursively()
        }
    }
}
