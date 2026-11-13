// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7Z / TAR / TGZ 的浏览与单条目抽取（纯 JVM）。
 * 写回：三者都必须被判为只读 —— 这条断言锁住「不做假支持」。
 */
class ArchiveBrowserFormatsTest {

    private fun tempFile(suffix: String): File =
        File.createTempFile("shso-arc-", suffix).apply { deleteOnExit() }

    private fun cacheDir(): File =
        Files.createTempDirectory("shso-arc-fmt-cache").toFile().apply { deleteOnExit() }

    private fun sevenZOf(entries: List<Pair<String, String>>): File {
        val f = tempFile(".7z")
        SevenZOutputFile(f).use { out ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                val e = SevenZArchiveEntry()
                e.name = name
                e.size = bytes.size.toLong()
                e.setHasStream(true)
                out.putArchiveEntry(e)
                out.write(bytes)
                out.closeArchiveEntry()
            }
            out.finish()
        }
        return f
    }

    private fun tarOf(entries: List<Pair<String, String>>): File {
        val f = tempFile(".tar")
        TarArchiveOutputStream(f.outputStream()).use { out ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                val e = TarArchiveEntry(name)
                e.size = bytes.size.toLong()
                out.putArchiveEntry(e)
                out.write(bytes)
                out.closeArchiveEntry()
            }
            out.finish()
        }
        return f
    }

    /** 压缩 tar（.tgz）：走 `openDecompress` 的 gzip 分支，与真机上的常见形态一致。 */
    private fun tgzOf(entries: List<Pair<String, String>>): File {
        val f = tempFile(".tgz")
        GzipCompressorOutputStream(BufferedOutputStream(f.outputStream())).use { gz ->
            TarArchiveOutputStream(gz).use { out ->
                for ((name, content) in entries) {
                    val bytes = content.toByteArray()
                    val e = TarArchiveEntry(name)
                    e.size = bytes.size.toLong()
                    out.putArchiveEntry(e)
                    out.write(bytes)
                    out.closeArchiveEntry()
                }
                out.finish()
            }
        }
        return f
    }

    @Test
    fun `7z 可列可读且判为只读`() = runBlocking {
        val z = sevenZOf(listOf("a.txt" to "SEVEN", "sub/b.txt" to "SUB"))
        val r = ArchiveBrowser.list(z.path, null, rootGranted = false)
        assertTrue("列举应成功，实际=$r", r is ArchiveBrowser.BrowseResult.Success)
        val info = (r as ArchiveBrowser.BrowseResult.Success).info
        assertEquals(2, info.entries.size)
        assertEquals(ArchiveBrowser.WriteSupport.NONE, info.writeSupport)
        assertTrue("必须给出可读的禁用原因", !info.writeBlockedReason.isNullOrEmpty())

        val open = ArchiveBrowser.openEntry(z.path, "sub/b.txt", null, cacheDir())
        assertTrue("抽取应成功，实际=$open", open is ArchiveBrowser.OpenResult.Success)
        assertEquals("SUB", File((open as ArchiveBrowser.OpenResult.Success).cachePath).readText())
    }

    @Test
    fun `tar 可列可读且判为只读`() = runBlocking {
        val t = tarOf(listOf("a.txt" to "TARRED", "sub/c.txt" to "DEEP"))
        val r = ArchiveBrowser.list(t.path, null, rootGranted = false)
        assertTrue("列举应成功，实际=$r", r is ArchiveBrowser.BrowseResult.Success)
        val info = (r as ArchiveBrowser.BrowseResult.Success).info
        assertEquals(2, info.entries.size)
        assertEquals(ArchiveBrowser.WriteSupport.NONE, info.writeSupport)

        val open = ArchiveBrowser.openEntry(t.path, "sub/c.txt", null, cacheDir())
        assertTrue("抽取应成功，实际=$open", open is ArchiveBrowser.OpenResult.Success)
        assertEquals("DEEP", File((open as ArchiveBrowser.OpenResult.Success).cachePath).readText())
    }

    @Test
    fun `压缩 tar 包可列可读且判为只读`() = runBlocking {
        val g = tgzOf(listOf("a.txt" to "TGZ", "sub/d.txt" to "TGZDEEP"))
        val r = ArchiveBrowser.list(g.path, null, rootGranted = false)
        assertTrue("列举应成功，实际=$r", r is ArchiveBrowser.BrowseResult.Success)
        val info = (r as ArchiveBrowser.BrowseResult.Success).info
        assertEquals(ArchiveExtractor.ContainerKind.TAR, info.kind)
        assertEquals(2, info.entries.size)
        assertEquals(ArchiveBrowser.WriteSupport.NONE, info.writeSupport)

        val open = ArchiveBrowser.openEntry(g.path, "sub/d.txt", null, cacheDir())
        assertTrue("抽取应成功，实际=$open", open is ArchiveBrowser.OpenResult.Success)
        assertEquals("TGZDEEP", File((open as ArchiveBrowser.OpenResult.Success).cachePath).readText())
    }
}
