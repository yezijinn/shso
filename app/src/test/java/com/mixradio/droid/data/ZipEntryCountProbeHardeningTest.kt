// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 中央目录预算探针的**口径护栏**。
 *
 * 三个必须锁住的契约：
 * 1. 外层归档里原样（STORE）内嵌一个条目数更多的 ZIP/APK 时，内层 EOCD 不得否决外层；
 * 2. 探针的候选集合必须覆盖 zip4j 真正会采用的那个（窗口内最后一处签名），
 *    否则 `probe` 返回 null 会让预算检查被静默绕过；
 * 3. 探针的尾部窗口不得窄于 zip4j 的反查窗口（差 1 字节就有整条绕过的夹缝）。
 */
class ZipEntryCountProbeHardeningTest {

    private val limit = ArchiveExtractor.MAX_EXTRACT_ENTRIES

    private fun tempFile(suffix: String): File =
        File.createTempFile("shso-probe-", suffix).apply { deleteOnExit() }

    private fun zipBytes(entries: List<Pair<String, ByteArray>>, store: Boolean): ByteArray {
        val f = tempFile(".zip")
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (store) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return f.readBytes()
    }

    /** 模拟包内 base.apk：entries 条目。 */
    private fun innerApkLike(entries: Int = 200): ByteArray =
        zipBytes((0 until entries).map { "inner/f$it.txt" to "x".toByteArray() }, store = true)

    private fun buildRealZip(entries: Int): File {
        val f = tempFile(".zip")
        ZipOutputStream(f.outputStream().buffered()).use { zos ->
            repeat(entries) { i ->
                zos.putNextEntry(ZipEntry("res/drawable/img_${i}_" + "n".repeat(20)))
                zos.closeEntry()
            }
        }
        return f
    }

    private fun eocdAt(bytes: ByteArray): Int {
        for (i in bytes.size - 22 downTo 0) if (readIntLe(bytes, i) == 0x06054b50) return i
        throw IllegalStateException("未找到 EOCD")
    }

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLe(buf: ByteArray, offset: Int, value: Int) {
        for (k in 0 until 4) buf[offset + k] = ((value shr (8 * k)) and 0xFF).toByte()
    }

    private fun writeShortLe(buf: ByteArray, offset: Int, value: Int) {
        for (k in 0 until 2) buf[offset + k] = ((value shr (8 * k)) and 0xFF).toByte()
    }

    private fun writeLongLe(buf: ByteArray, offset: Int, value: Long) {
        for (k in 0 until 8) buf[offset + k] = ((value shr (8 * k)) and 0xFF).toByte()
    }

    private fun zip4jHeaderCount(f: File): Int = Zip4jFile(f).use { it.fileHeaders.size }

    // ── 1. 内嵌 STORE 归档不得放大外层条目数 ──────────────────────────────

    @Test fun `内层 STORE 归档是最后一个条目时外层条目数不被放大`() {
        val outer = tempFile(".zip").apply {
            writeBytes(
                zipBytes(
                    listOf("readme.txt" to "hello".toByteArray(), "bundle/base.apk" to innerApkLike()),
                    store = true
                )
            )
        }
        assertEquals(
            "外层真实条目数只有 2，不得被内层 200 条放大",
            2, ZipEntryCountProbe.probe(outer, limit)
        )
    }

    @Test fun `内层 STORE 归档后面还有小文件时同样不被放大`() {
        // .xapk / .apkm 的典型顺序：base.apk 之后还有 info.json / icon.png
        val outer = tempFile(".zip").apply {
            writeBytes(
                zipBytes(
                    listOf(
                        "readme.txt" to "hello".toByteArray(),
                        "base.apk" to innerApkLike(),
                        "info.json" to "{}".toByteArray(),
                        "icon.png" to ByteArray(64) { 0x7F }
                    ),
                    store = true
                )
            )
        }
        assertEquals(4, ZipEntryCountProbe.probe(outer, limit))
    }

    @Test fun `内嵌归档的中央目录被破坏时外层仍可浏览`() {
        // 内层 EOCD 还在、cdOffset 指到文件外：生产实现曾因此把 4 条目的外层判成超限。
        val broken = innerApkLike().copyOf().also { b ->
            writeIntLe(b, eocdAt(b) + 16, 0xFFFFFF)
        }
        val outer = tempFile(".zip").apply {
            writeBytes(
                zipBytes(
                    listOf("readme.txt" to "hello".toByteArray(), "b/base.apk" to broken),
                    store = true
                )
            )
        }
        assertEquals(2, ZipEntryCountProbe.probe(outer, limit))
    }

    @Test fun `内嵌归档不影响浏览与自动解压`() = runBlocking {
        val outer = tempFile(".zip").apply {
            writeBytes(
                zipBytes(
                    listOf("readme.txt" to "hello".toByteArray(), "bundle/base.apk" to innerApkLike()),
                    store = true
                )
            )
        }
        val browse = ArchiveBrowser.list(outer.path, null, rootGranted = false)
        assertTrue("必须能浏览，实际=$browse", browse is ArchiveBrowser.BrowseResult.Success)
        assertEquals(2, (browse as ArchiveBrowser.BrowseResult.Success).info.entries.size)
        val outDir = tempFile("-out").apply { delete(); mkdirs() }
        val ex = ArchiveExtractor.extract(outer.path, outDir.path, null)
        assertTrue("自动解压必须成功，实际=$ex", ex is ArchiveExtractor.ExtractResult.Success)
    }

    @Test fun `内层归档被 DEFLATE 时条目数仍然正确`() {
        val outer = tempFile(".zip").apply {
            writeBytes(
                zipBytes(
                    listOf("a.txt" to "hi".toByteArray(), "b.zip" to innerApkLike()),
                    store = false
                )
            )
        }
        assertEquals(2, ZipEntryCountProbe.probe(outer, limit))
    }

    // ── 2. 候选集合必须覆盖 zip4j 采用的候选 ────────────────────────────

    @Test fun `EOCD 注释长度字段撒谎不得让预算检查被绕过`() {
        // zip4j 的 readZipComment 把越界注释的 IOException 吞成 null，EOCD 照常采用。
        // 探针若按注释长度筛掉这个候选，就会返回 null —— 调用方 probeZipEntryCountOrThrow
        // 见到 null 直接放行，2 万条归档整包进内存。
        val zip = buildRealZip(21_000)
        val bytes = zip.readBytes()
        writeShortLe(bytes, eocdAt(bytes) + 20, 0xFFFF)   // 注释长度撒谎，不补字节
        zip.writeBytes(bytes)
        assertEquals(21_000, zip4jHeaderCount(zip))
        val probed = ZipEntryCountProbe.probe(zip, limit)
        assertTrue("注释撒谎的 EOCD 仍会被 zip4j 采用，探针不得返回 null", probed != null)
        assertTrue("必须按超限拒绝，实际=$probed", probed!! >= limit)
        try {
            ArchiveExtractor.probeZipEntryCountOrThrow(zip.path)
            fail("预算前置检查不得放行 21000 条归档")
        } catch (_: ArchiveExtractor.ExtractionLimitException) {
            // 期望
        }
    }

    @Test fun `最后一处 EOCD 是 ZIP64 哨兵时必须 fail_closed`() {
        // 合法 ZIP64 形态：计数不溢出、只有偏移/体积是 0xFFFFFFFF。
        // zip4j 会读 locator + zip64 记录里的 totalEntries（无界），并按其分配 FileHeader。
        // 探针不得因为「这个候选走不动」就跳过它、改信包里那条小归档。
        val zip = buildZip64Container(eocdCount = 3, declaredEntries = 21_000, realCdHeaders = 21_000)
        assertEquals(21_000, zip4jHeaderCount(zip))
        val probed = ZipEntryCountProbe.probe(zip, limit)
        assertTrue("ZIP64 候选不可跳过，实际=$probed", probed != null && probed >= limit)
    }

    @Test fun `候选走不动但声明计数超限时仍然拒绝`() {
        // 内层候选的 CD 走不动，但它自己声明了 65535 条：不能因为外层走得通就放行。
        val inner = innerApkLike().copyOf().also { b ->
            writeShortLe(b, eocdAt(b) + 8, 0xFFFF)
            writeShortLe(b, eocdAt(b) + 10, 0xFFFF)
            writeIntLe(b, eocdAt(b) + 16, 0xFFFFFF)
        }
        val outer = tempFile(".zip").apply {
            writeBytes(
                zipBytes(
                    listOf("readme.txt" to "hello".toByteArray(), "b/base.apk" to inner),
                    store = true
                )
            )
        }
        val probed = ZipEntryCountProbe.probe(outer, limit)
        assertTrue("自报 65535 条的候选必须按超限处理，实际=$probed", probed != null && probed >= limit)
    }

    // ── 3. 尾部窗口不得窄于 zip4j 的反查窗口 ────────────────────────────

    @Test fun `尾随垃圾正好落在 zip4j 反查窗口边界时不得绕过预算`() {
        // zip4j 从 len-23 起最多反查 65536 次 → 最低覆盖 len-65558。
        // 窗口取 65557 时，这 1 字节的夹缝里探针返回 null、zip4j 照读全部条目。
        assertTrue("探针窗口必须覆盖 len-65558", ZipEntryCountProbe.TAIL_WINDOW >= 65_558)
        assertTrue("同时仍只读固定窗口", ZipEntryCountProbe.TAIL_WINDOW < 70 * 1024)

        val zip = buildRealZip(21_000)
        zip.appendBytes(ByteArray(65_536))
        assertEquals(21_000, zip4jHeaderCount(zip))
        val probed = ZipEntryCountProbe.probe(zip, limit)
        assertTrue("65536 字节尾随垃圾不得关掉预算检查，实际=$probed", probed != null && probed >= limit)
    }

    @Test fun `尾随垃圾超出反查窗口时与 zip4j 同口径返回 null`() {
        val zip = tempFile(".zip").apply {
            writeBytes(
                zipBytes(
                    listOf("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray(), "c.txt" to "c".toByteArray()),
                    store = false
                ) + ByteArray(70_000)
            )
        }
        assertNull(ZipEntryCountProbe.probe(zip, limit))
    }

    @Test fun `放宽窗口后合法大归档仍给出精确条目数`() {
        val zip = buildRealZip(15_000)
        assertEquals(15_000, ZipEntryCountProbe.probe(zip, limit))
        assertNull("非 zip 仍返回 null", ZipEntryCountProbe.probe(tempFile(".bin").apply {
            writeBytes(ByteArray(4096) { (it % 127).toByte() })
        }, limit))
        assertFalse(zip4jHeaderCount(zip) > 15_000)
    }

    /** [3 条小 zip][rawCD][zip64 记录][locator][EOCD(计数=eocdCount, 偏移/体积=0xFFFFFFFF)] */
    private fun buildZip64Container(
        eocdCount: Int,
        declaredEntries: Int,
        realCdHeaders: Int
    ): File {
        val cd = ByteArrayOutputStream().apply {
            repeat(realCdHeaders) {
                val h = ByteArray(46)
                writeIntLe(h, 0, 0x02014b50)
                writeShortLe(h, 4, 20)
                writeShortLe(h, 6, 20)
                writeShortLe(h, 28, 1)
                write(h)
                write('a'.code)
            }
        }.toByteArray()
        val rawSmall = zipBytes(
            listOf("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray(), "c.txt" to "c".toByteArray()),
            store = false
        )
        val small = rawSmall.copyOf().also { b ->
            writeIntLe(b, eocdAt(b) + 16, readIntLe(b, eocdAt(b) + 16) + cd.size)
        }
        val z64At = (cd.size + small.size).toLong()
        val record = ByteArray(56).also {
            writeIntLe(it, 0, 0x06064b50)
            writeLongLe(it, 4, 44L)
            writeShortLe(it, 12, 45)
            writeShortLe(it, 14, 45)
            writeLongLe(it, 24, declaredEntries.toLong())
            writeLongLe(it, 32, declaredEntries.toLong())
            writeLongLe(it, 40, cd.size.toLong())
            writeLongLe(it, 48, 0L)
        }
        val locator = ByteArray(20).also {
            writeIntLe(it, 0, 0x07064b50)
            writeLongLe(it, 8, z64At)
            writeIntLe(it, 16, 1)
        }
        val eocd = ByteArray(22).also {
            writeIntLe(it, 0, 0x06054b50)
            writeShortLe(it, 8, eocdCount)
            writeShortLe(it, 10, eocdCount)
            writeIntLe(it, 12, -1)
            writeIntLe(it, 16, -1)
        }
        return tempFile(".zip").apply { writeBytes(cd + small + record + locator + eocd) }
    }

    private fun File.appendBytes(bytes: ByteArray) {
        RandomAccessFile(this, "rw").use { it.seek(it.length()); it.write(bytes) }
    }
}
