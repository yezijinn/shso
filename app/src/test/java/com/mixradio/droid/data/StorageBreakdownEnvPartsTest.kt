// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「env.bin 组成部分必须分别解释」的护栏。 */
class StorageBreakdownEnvPartsTest {

    @Test
    fun `env 拆成六个组成部分且每项都有说明`() {
        val parts = StorageBreakdown.ENV_COMPONENTS
        assertEquals(6, parts.size)
        assertEquals(
            listOf("bin", "lib", "libexec", "share", "etc", null),
            parts.map { it.top }
        )
        val labels = parts.map { it.label }
        assertEquals("标签不得重复（否则 UI 上两条同名条目无从区分）", labels.size, labels.toSet().size)
        parts.forEach { part ->
            assertTrue("${part.label} 缺名称", part.label.isNotBlank())
            assertTrue("${part.label} 缺用途说明", part.note.length >= 8)
        }
        // 说明必须真的是「分别是什么」，不能是「内置工具链」这种复读标签
        assertTrue(parts.first { it.top == "lib" }.note.contains("python"))
        assertTrue(parts.first { it.top == "etc" }.note.contains("证书"))
        assertTrue(parts.first { it.top == "libexec" }.note.contains("git"))
        assertTrue(parts.first { it.top == "bin" }.note.contains("busybox"))
        assertTrue(parts.first { it.top == null }.note.contains("VERSION"))
    }

    @Test
    fun `求和跳过不存在目录返回 null`() {
        val missing = File("build/tmp/definitely-not-exist-" + System.nanoTime())
        assertNull(StorageBreakdown.sumRegularFileBytes(missing))
        assertNull(StorageBreakdown.topLevelFileBytes(missing.absolutePath))
    }

    @Test
    fun `递归求和只算常规文件且顶层文件不重复计入子目录`() {
        val root = Files.createTempDirectory("shso-env-parts").toFile()
        try {
            File(root, "bin").mkdirs()
            File(root, "lib/python3.14").mkdirs()
            File(root, "bin/busybox").writeBytes(ByteArray(1024))
            File(root, "lib/libcrypto.so").writeBytes(ByteArray(2048))
            File(root, "lib/python3.14/os.py").writeBytes(ByteArray(512))
            File(root, "VERSION").writeText("6\n")

            // 「?: -1L」避免 JUnit assertEquals(long, Long?) 的重载歧义
            assertEquals(1024L + 2048L + 512L + 2L, StorageBreakdown.sumRegularFileBytes(root) ?: -1L)
            // 顶层文件只算 VERSION，不把 bin/lib 递归算进来
            assertEquals(2L, StorageBreakdown.topLevelFileBytes(root.absolutePath) ?: -1L)
        } finally {
            root.deleteRecursively()
        }
    }
}
