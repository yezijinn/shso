// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装包（APK / XAPK / APKS / ASPK / APKM）只读浏览护栏。
 *
 * 安装包本质是 ZIP，但：
 *  1. 不属于 [ArchiveExtractor.isKnownArchive] —— 不进「自动解压」，仍有专门的安装入口；
 *  2. 经 [ArchiveExtractor.browseContainerKindOf] 归为 ZIP，可免解压浏览；
 *  3. [ArchiveBrowser] 强制 [ArchiveBrowser.WriteSupport.NONE]，且 [ArchiveBrowser.replaceEntry]
 *     在数据层硬拒绝 —— 改任一字节都会让签名失效、包再也装不上。
 */
class InstallerBrowseTest {

    private fun tempFile(suffix: String): File =
        File.createTempFile("shso-inst-", suffix).apply { deleteOnExit() }

    private fun cacheDir(): File =
        Files.createTempDirectory("shso-inst-cache").toFile().apply { deleteOnExit() }

    /** 造一个真正含条目的 ZIP，但文件名伪装成安装包后缀。 */
    private fun installerZip(ext: String): File {
        val f = tempFile(ext)
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/drawable/icon.png"))
            zos.write(ByteArray(16) { 0x42 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write("dex-placeholder".toByteArray())
            zos.closeEntry()
        }
        return f
    }

    @Test
    fun `五种安装包都被识别且浏览类型归为 ZIP`() {
        for (ext in listOf("apk", "xapk", "apks", "aspk", "apkm")) {
            val name = "app.$ext"
            assertTrue("$ext 应被识别为安装包", ArchiveExtractor.isInstallerPackage(name))
            assertEquals(
                "$ext 浏览类型必须是 ZIP",
                ArchiveExtractor.ContainerKind.ZIP,
                ArchiveExtractor.browseContainerKindOf(name)
            )
        }
    }

    @Test
    fun `大小写与数字尾缀同口径`() {
        assertTrue(ArchiveExtractor.isInstallerPackage("APP.APK"))
        assertTrue(ArchiveExtractor.isInstallerPackage("app.apk.1"))
        assertTrue(ArchiveExtractor.isInstallerPackage("split_config.xapk.2"))
        assertEquals(
            ArchiveExtractor.ContainerKind.ZIP,
            ArchiveExtractor.browseContainerKindOf("APP.XAPK.1")
        )
    }

    @Test
    fun `安装包不进自动解压判定也不改 containerKindOf`() {
        for (ext in listOf("apk", "xapk", "apks", "aspk", "apkm")) {
            val name = "app.$ext"
            // isKnownArchive 管的是「自动解压」：安装包不能混进去。
            assertFalse("$ext 不得被判成可解压压缩包", ArchiveExtractor.isKnownArchive(name))
            // containerKindOf 是写回/解压的判定表，必须保持不变（null）。
            assertEquals(
                "$ext 的 containerKindOf 必须为 null",
                null,
                ArchiveExtractor.containerKindOf(name)
            )
        }
        // 普通 zip 仍是已知压缩包，不受影响。
        assertTrue(ArchiveExtractor.isKnownArchive("a.zip"))
        assertFalse(ArchiveExtractor.isInstallerPackage("a.zip"))
    }

    @Test
    fun `安装包可只读浏览且列出包内条目`() = runBlocking {
        val apk = installerZip(".apk")
        val r = ArchiveBrowser.list(apk.path, null, rootGranted = false)
        assertTrue("列举应成功，实际=$r", r is ArchiveBrowser.BrowseResult.Success)
        val info = (r as ArchiveBrowser.BrowseResult.Success).info
        assertEquals(ArchiveExtractor.ContainerKind.ZIP, info.kind)
        assertEquals(3, info.entries.size)
        assertEquals("强制只读", ArchiveBrowser.WriteSupport.NONE, info.writeSupport)
        assertNotNull("必须给出只读原因", info.writeBlockedReason)
        assertTrue(
            "只读原因须点明签名失效",
            info.writeBlockedReason!!.contains("签名")
        )
    }

    @Test
    fun `即便授权 ROOT 或目录可写 安装包仍是只读`() = runBlocking {
        val apks = installerZip(".apks")
        val r = ArchiveBrowser.list(apks.path, null, rootGranted = true)
        val info = (r as ArchiveBrowser.BrowseResult.Success).info
        assertEquals(
            "ROOT 也不得解锁安装包写回",
            ArchiveBrowser.WriteSupport.NONE,
            info.writeSupport
        )
    }

    @Test
    fun `安装包内条目可抽取查看但不落盘整体解压`() = runBlocking {
        val apk = installerZip(".apk")
        val cache = cacheDir()
        val r = ArchiveBrowser.openEntry(apk.path, "classes.dex", null, cache)
        assertTrue("抽取应成功，实际=$r", r is ArchiveBrowser.OpenResult.Success)
        val out = File((r as ArchiveBrowser.OpenResult.Success).cachePath)
        assertEquals("dex-placeholder", out.readText())
    }

    @Test
    fun `写回对安装包在数据层硬拒绝且原包字节不变`() = runBlocking {
        val xapk = installerZip(".xapk")
        val before = xapk.readBytes()
        val newContent = tempFile(".txt").apply { writeText("tampered") }

        val wr = ArchiveBrowser.replaceEntry(
            archivePath = xapk.path,
            entryPath = "classes.dex",
            newContent = newContent,
            cacheDir = cacheDir(),
            rootGranted = true // 连 ROOT 也要被拒
        )
        assertTrue("写回必须失败，实际=$wr", wr is ArchiveBrowser.WriteResult.Failure)
        assertTrue(
            "失败原因须说明只读",
            (wr as ArchiveBrowser.WriteResult.Failure).message.contains("只读")
        )
        assertTrue("原安装包字节不得有任何改动", before.contentEquals(xapk.readBytes()))
        // 工作副本不得残留
        assertTrue(
            "不得留下工作副本",
            xapk.parentFile!!.listFiles()!!.none { it.name.contains(".shso_arc_") }
        )
    }

    @Test
    fun `普通 zip 仍可写回 未被只读规则误伤`() = runBlocking {
        val zip = tempFile(".zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("OLD".toByteArray())
            zos.closeEntry()
        }
        val r = ArchiveBrowser.list(zip.path, null, rootGranted = false)
        val info = (r as ArchiveBrowser.BrowseResult.Success).info
        assertEquals(ArchiveBrowser.WriteSupport.ZIP_REWRITE, info.writeSupport)
        assertEquals(null, info.writeBlockedReason)
    }
}
