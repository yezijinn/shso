// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile as Zip4jFile
import net.lingala.zip4j.model.enums.CompressionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真 ZIP + zip4j 的端到端回归（纯 JVM，和 [ArchiveExtractorExtractTest] 同一路数）：
 * 列举 / 目录合成 / 单条目抽取 / 写回替换 + 其它条目字节不变。
 * 真机专属的部分（root 路径写回、图片解码）见 §6.2 验证清单。
 */
class ArchiveBrowserZipTest {

    private fun tempFile(suffix: String): File =
        File.createTempFile("shso-arc-", suffix).apply { deleteOnExit() }

    private fun zipOf(entries: List<Pair<String, String>>): File {
        val f = tempFile(".zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    private fun cacheDir(): File =
        Files.createTempDirectory("shso-arc-cache").toFile().apply { deleteOnExit() }

    @Test
    fun `列举并按层浏览`() = runBlocking {
        val zip = zipOf(listOf("a.txt" to "A", "res/values/strings.xml" to "<x/>"))
        val r = ArchiveBrowser.list(zip.path, null, rootGranted = false)
        assertTrue("列举应成功，实际=$r", r is ArchiveBrowser.BrowseResult.Success)
        val info = (r as ArchiveBrowser.BrowseResult.Success).info
        assertEquals(2, info.entries.size)
        assertEquals(ArchiveBrowser.WriteSupport.ZIP_REWRITE, info.writeSupport)
        val top = ArchiveBrowser.childrenOf(info.entries, "")
        assertEquals(listOf("res", "a.txt"), top.map { it.name })
    }

    @Test
    fun `单条目抽取到 cache 且不整包落盘`() = runBlocking {
        val zip = zipOf(listOf("a.txt" to "HELLO", "b.txt" to "B"))
        val cache = cacheDir()
        val r = ArchiveBrowser.openEntry(zip.path, "a.txt", null, cache)
        assertTrue("抽取应成功，实际=$r", r is ArchiveBrowser.OpenResult.Success)
        val out = File((r as ArchiveBrowser.OpenResult.Success).cachePath)
        assertTrue("临时文件必须落在传入的 cache 下", out.absolutePath.startsWith(cache.absolutePath))
        assertEquals("文件名保留条目名（编辑器标题显示真实名）", "a.txt", out.name)
        assertEquals("HELLO", out.readText())
        assertTrue("文本内容不得被判成二进制", !ArchiveBrowser.isLikelyBinary(out.path))
        // archive-open 下只有一个条目目录：没有整包解压
        assertEquals(1, File(cache, "archive-open").listFiles()?.size ?: 0)
    }

    @Test
    fun `同名不同目录的条目抽取到不同缓存`() = runBlocking {
        val zip = zipOf(listOf("a/x.txt" to "AAA", "b/x.txt" to "BBB"))
        val cache = cacheDir()
        val first = ArchiveBrowser.openEntry(zip.path, "a/x.txt", null, cache)
        val second = ArchiveBrowser.openEntry(zip.path, "b/x.txt", null, cache)
        assertTrue(first is ArchiveBrowser.OpenResult.Success)
        assertTrue(second is ArchiveBrowser.OpenResult.Success)
        val p1 = (first as ArchiveBrowser.OpenResult.Success).cachePath
        val p2 = (second as ArchiveBrowser.OpenResult.Success).cachePath
        assertTrue("两条必须落到不同路径", p1 != p2)
        assertEquals("AAA", File(p1).readText())
        assertEquals("BBB", File(p2).readText())
    }

    @Test
    fun `写回替换目标条目且不动其它条目`() = runBlocking {
        val zip = zipOf(listOf("a.txt" to "OLD", "res/values/strings.xml" to "<x/>"))
        val otherBefore = Zip4jFile(zip.path).use {
            it.getFileHeader("res/values/strings.xml")!!.compressedSize
        }
        val newContent = tempFile(".txt").apply { writeText("NEW CONTENT") }

        val wr = ArchiveBrowser.replaceEntry(
            archivePath = zip.path,
            entryPath = "a.txt",
            newContent = newContent,
            cacheDir = cacheDir(),
            rootGranted = false
        )
        assertTrue("写回应成功，实际=$wr", wr is ArchiveBrowser.WriteResult.Success)

        Zip4jFile(zip.path).use { z ->
            val headers = z.fileHeaders
            assertEquals("条目数必须不变", 2, headers.size)
            val a = z.getFileHeader("a.txt")!!
            assertEquals("NEW CONTENT", z.getInputStream(a).bufferedReader().readText())
            val s = z.getFileHeader("res/values/strings.xml")!!
            assertEquals("其它条目内容必须原样保留", "<x/>", z.getInputStream(s).bufferedReader().readText())
            assertEquals("其它条目的压缩字节不得被重压", otherBefore, s.compressedSize)
        }
        // 工作副本与 zip4j 内部临时文件都不得残留
        assertTrue(
            "临时副本应被清理",
            zip.parentFile!!.listFiles()!!.none { it.name.contains(".shso_arc_") }
        )
    }

    @Test
    fun `STORE 条目写回后仍为 STORE 且内容正确`() = runBlocking {
        val zip = tempFile(".zip")
        val stored = "STORED".toByteArray()
        ZipOutputStream(zip.outputStream()).use { zos ->
            val e = ZipEntry("s.txt").apply {
                method = ZipEntry.STORED
                size = stored.size.toLong()
                compressedSize = stored.size.toLong()
                crc = CRC32().apply { update(stored) }.value
            }
            zos.putNextEntry(e)
            zos.write(stored)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("d.txt"))
            zos.write("DEFLATED".toByteArray())
            zos.closeEntry()
        }
        val newContent = tempFile(".txt").apply { writeText("STORED-NEW") }
        val wr = ArchiveBrowser.replaceEntry(zip.path, "s.txt", newContent, cacheDir(), false)
        assertTrue("STORE 条目写回应成功，实际=$wr", wr is ArchiveBrowser.WriteResult.Success)
        Zip4jFile(zip.path).use { z ->
            val h = z.getFileHeader("s.txt")!!
            assertEquals("压缩方式必须保持 STORE", CompressionMethod.STORE, h.compressionMethod)
            assertEquals("STORED-NEW", z.getInputStream(h).bufferedReader().readText())
            val d = z.getFileHeader("d.txt")!!
            assertEquals("其它条目仍为 DEFLATE", CompressionMethod.DEFLATE, d.compressionMethod)
            assertEquals("DEFLATED", z.getInputStream(d).bufferedReader().readText())
        }
    }

    @Test
    fun `写回不存在条目时原包不变`() = runBlocking {
        val zip = zipOf(listOf("a.txt" to "AAA"))
        val before = zip.readBytes()
        val newContent = tempFile(".txt").apply { writeText("X") }
        val wr = ArchiveBrowser.replaceEntry(zip.path, "missing.txt", newContent, cacheDir(), false)
        assertTrue(wr is ArchiveBrowser.WriteResult.Failure)
        assertTrue("失败必须保留原包", before.contentEquals(zip.readBytes()))
    }

    @Test
    fun `目录条目与越界条目不可写回`() = runBlocking {
        val zip = zipOf(listOf("dir/" to "", "a.txt" to "A"))
        val before = zip.readBytes()
        val newContent = tempFile(".txt").apply { writeText("X") }
        assertTrue(
            ArchiveBrowser.replaceEntry(zip.path, "dir", newContent, cacheDir(), false)
                is ArchiveBrowser.WriteResult.Failure
        )
        assertTrue(
            "越界名必须直接拒绝",
            ArchiveBrowser.replaceEntry(zip.path, "../a.txt", newContent, cacheDir(), false)
                is ArchiveBrowser.WriteResult.Failure
        )
        assertTrue(before.contentEquals(zip.readBytes()))
    }
}
