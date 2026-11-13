// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 包内路径 / 目录合成 / 缓存命名的纯函数护栏（不碰磁盘、不碰 Android）。 */
class ArchiveBrowserPathTest {

    private fun entry(path: String, isDir: Boolean = false, size: Long = 1L) =
        ArchiveBrowser.ArchiveEntry(path, isDir, size, size, false)

    @Test
    fun `条目路径归一化`() {
        assertEquals("sub/a.txt", ArchiveBrowser.normalizeEntryPath("\\sub\\a.txt"))
        assertEquals("a/b", ArchiveBrowser.normalizeEntryPath("/a/b/"))
        assertEquals("a", ArchiveBrowser.normalizeEntryPath("./a"))
        assertEquals("a/b", ArchiveBrowser.normalizeEntryPath("a//b"))
        assertEquals("", ArchiveBrowser.normalizeEntryPath("/"))
    }

    @Test
    fun `越界路径被识别`() {
        assertTrue(ArchiveBrowser.isUnsafeEntryPath("../evil"))
        assertTrue(ArchiveBrowser.isUnsafeEntryPath("a/../../b"))
        assertFalse(ArchiveBrowser.isUnsafeEntryPath("b"))
        assertFalse(ArchiveBrowser.isUnsafeEntryPath("a..b/c"))
        assertFalse(ArchiveBrowser.isUnsafeEntryPath("sub/a.txt"))
    }

    @Test
    fun `父目录推导`() {
        assertEquals("", ArchiveBrowser.parentOf("a.txt"))
        assertEquals("res", ArchiveBrowser.parentOf("res/values"))
        assertEquals("res/values", ArchiveBrowser.parentOf("res/values/strings.xml"))
    }

    @Test
    fun `缓存文件名被净化成目录内安全的单段`() {
        assertEquals("build.prop", ArchiveBrowser.safeCacheName("system/build.prop"))
        assertEquals("a_b", ArchiveBrowser.safeCacheName("a:b"))      // 非法字符换 _
        assertEquals("passwd", ArchiveBrowser.safeCacheName("/etc/passwd"))
        assertFalse(ArchiveBrowser.safeCacheName("../../x").contains('/'))
        assertFalse(ArchiveBrowser.safeCacheName("../../x").contains(".."))
        assertTrue(ArchiveBrowser.safeCacheName("///").isNotEmpty())
        assertTrue("截断到 48 字符以内", ArchiveBrowser.safeCacheName("a".repeat(200)).length <= 48)
    }

    @Test
    fun `缓存子目录按 包+条目 唯一，同名条目不会互相覆盖`() {
        val a = ArchiveBrowser.cacheSubdirName("/sdcard/a.zip", "x/f.txt")
        val b = ArchiveBrowser.cacheSubdirName("/sdcard/b.zip", "x/f.txt")
        val c = ArchiveBrowser.cacheSubdirName("/sdcard/a.zip", "y/f.txt")
        assertEquals("同一对输入必须稳定", a, ArchiveBrowser.cacheSubdirName("/sdcard/a.zip", "x/f.txt"))
        assertEquals(16, a.length)
        assertNotEquals("不同压缩包同名条目必须分开", a, b)
        assertNotEquals("同包不同目录同名条目必须分开", a, c)
        assertNotEquals(b, c)
    }

    @Test
    fun `二进制嗅探`() {
        val text = "ro.x=1\n".toByteArray()
        assertFalse(ArchiveBrowser.looksBinary(text, text.size))
        val bin = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)   // zip 头，含 NUL
        assertTrue(ArchiveBrowser.looksBinary(bin, bin.size))
        // UTF-16 LE BOM 的文件后面必然有 0 字节，不能判成二进制
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0x00)
        assertFalse(ArchiveBrowser.looksBinary(utf16, utf16.size))
        assertFalse(ArchiveBrowser.looksBinary(ByteArray(0), 0))
    }

    @Test
    fun `按目录列直接子项并合成隐式目录`() {
        val entries = listOf(
            entry("a.txt"),
            entry("res/values/strings.xml"),
            entry("res/layout/main.xml"),
            entry("res/", isDir = true, size = 0L)
        )
        val top = ArchiveBrowser.childrenOf(entries, "")
        assertEquals(listOf("res", "a.txt"), top.map { it.name })
        assertEquals(listOf("res", "a.txt"), top.map { it.path })
        assertTrue("目录必须排在文件前面", top.first().isDirectory)

        val res = ArchiveBrowser.childrenOf(entries, "res")
        assertEquals(listOf("layout", "values"), res.map { it.name })
        assertEquals(listOf("res/layout", "res/values"), res.map { it.path })
        assertTrue(res.all { it.isDirectory })

        val values = ArchiveBrowser.childrenOf(entries, "res/values")
        assertEquals(listOf("strings.xml"), values.map { it.name })
        assertEquals(listOf("res/values/strings.xml"), values.map { it.path })
        assertFalse(values.first().isDirectory)

        assertTrue(ArchiveBrowser.childrenOf(entries, "nope").isEmpty())
    }
}
