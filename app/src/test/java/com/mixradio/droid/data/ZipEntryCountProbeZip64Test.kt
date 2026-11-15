// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import net.lingala.zip4j.ZipFile as Zip4jFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZIP64 扩展记录的预算护栏。
 *
 * 锁住四条契约：
 * 1. 记录里的 totalEntries 不得让「预算前置」失效（EOCD 自报 1 条也要按记录判）；
 * 2. 合法 ZIP64 必须给出精确条目数，不得一律判超限；
 * 3. 尾部窗口必须同时覆盖 zip4j 的最远候选（`len-65558`）与它前面的 locator（再 20 字节）；
 * 4. 有 locator 但记录不可核验时必须 fail-closed。
 */
class ZipEntryCountProbeZip64Test {

    private val limit = ArchiveExtractor.MAX_EXTRACT_ENTRIES

    // ── 构造器 ────────────────────────────────────────────────────────────

    private fun writeIntLe(b: ByteArray, o: Int, v: Int) {
        for (k in 0 until 4) b[o + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    private fun writeShortLe(b: ByteArray, o: Int, v: Int) {
        for (k in 0 until 2) b[o + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    private fun writeLongLe(b: ByteArray, o: Int, v: Long) {
        for (k in 0 until 8) b[o + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    private fun readIntLe(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun tempZip(): File = File.createTempFile("shso-z64-", ".zip").apply { deleteOnExit() }

    /** n 条合法的中央目录 header（46 定长 + 1 字节名 = 47 字节）。 */
    private fun cdRegion(n: Int): ByteArray {
        val rec = 47
        val b = ByteArray(n * rec)
        for (i in 0 until n) {
            val o = i * rec
            writeIntLe(b, o, 0x02014b50)
            writeShortLe(b, o + 4, 20)
            writeShortLe(b, o + 6, 20)
            writeShortLe(b, o + 28, 1)
            b[o + 46] = 'a'.code.toByte()
        }
        return b
    }

    private fun zip64Record(total: Long, cdSize: Long, cdOffset: Long, sig: Int = 0x06064b50) =
        ByteArray(56).also {
            writeIntLe(it, 0, sig)
            writeLongLe(it, 4, 44L)
            writeShortLe(it, 12, 45)
            writeShortLe(it, 14, 45)
            writeLongLe(it, 24, total)
            writeLongLe(it, 32, total)
            writeLongLe(it, 40, cdSize)
            writeLongLe(it, 48, cdOffset)
        }

    private fun zip64Locator(recordOffset: Long) = ByteArray(20).also {
        writeIntLe(it, 0, 0x07064b50)
        writeLongLe(it, 8, recordOffset)
        writeIntLe(it, 16, 1)
    }

    private fun eocd(total: Int, cdSize: Int, cdOffset: Int) = ByteArray(22).also {
        writeIntLe(it, 0, 0x06054b50)
        writeShortLe(it, 8, total)
        writeShortLe(it, 10, total)
        writeIntLe(it, 12, cdSize)
        writeIntLe(it, 16, cdOffset)
    }

    /** `[n 条 CD @0][记录][locator][EOCD(自报 1, 指向首条)][junk 个 0]` */
    private fun bypass(n: Int, junk: Int = 0): File {
        val cd = cdRegion(n)
        return tempZip().apply {
            writeBytes(
                cd + zip64Record(n.toLong(), cd.size.toLong(), 0L) +
                    zip64Locator(cd.size.toLong()) + eocd(1, 47, 0) + ByteArray(junk)
            )
        }
    }

    /** 真实 zip + 记录 + locator + 哨兵 EOCD。 */
    private fun legit(n: Int): File {
        val f = tempZip()
        java.util.zip.ZipOutputStream(f.outputStream()).use { zos ->
            repeat(n) { i ->
                zos.putNextEntry(java.util.zip.ZipEntry("d/f$i"))
                zos.closeEntry()
            }
        }
        val bytes = f.readBytes()
        var at = -1
        for (i in bytes.size - 22 downTo 0) if (readIntLe(bytes, i) == 0x06054b50) { at = i; break }
        require(at >= 0)
        val cdSize = readIntLe(bytes, at + 12).toLong()
        val cdOffset = readIntLe(bytes, at + 16).toLong()
        f.writeBytes(
            bytes + zip64Record(n.toLong(), cdSize, cdOffset) +
                zip64Locator(bytes.size.toLong()) + eocd(0xFFFF, -1, -1)
        )
        return f
    }

    private fun zip4jCount(f: File): Int = Zip4jFile(f).use { it.fileHeaders.size }

    // ── 1. 预算不得被记录绕过 ─────────────────────────────────────────────

    @Test
    fun `ZIP64 记录声明的条目数不得让预算前置失效`() {
        // EOCD 自报 1 条、cdSize 只覆盖 1 条；zip4j 却因 locator 改用记录里的 total。
        val f = bypass(60_000)
        assertEquals("构造必须能被 zip4j 读满 6 万条", 60_000, zip4jCount(f))
        val probed = ZipEntryCountProbe.probe(f, limit)
        assertTrue("记录 6 万条必须按超限拒绝，实际=$probed", probed != null && probed >= limit)
    }

    // ── 2. 合法 ZIP64 必须给出精确条目数 ────────────────────────────────

    @Test
    fun `合法 ZIP64 归档必须给出精确条目数`() {
        for (n in listOf(0, 1, 2, 5, 1_000)) {
            val f = legit(n)
            assertEquals("zip4j 应读出 $n 条", n, zip4jCount(f))
            assertEquals("合法 ZIP64（$n 条）必须精确计数", n, ZipEntryCountProbe.probe(f, limit))
        }
    }

    // ── 3. 窗口必须同时覆盖候选与它的 locator ───────────────────────────

    @Test
    fun `locator 落在旧窗口外时仍必须按记录判定`() {
        // EOCD 落在 zip4j 反查最远端 len-65558，locator 在 len-65578。
        // 窗口 65558 时 locator 恰在窗口外 20 字节，探针会把「看不到」当成「没有」。
        assertTrue("窗口必须覆盖 len-65578", ZipEntryCountProbe.TAIL_WINDOW >= 65_578)
        for (junk in listOf(65_535, 65_536)) {
            val f = bypass(60_000, junk)
            assertEquals("junk=$junk 时 zip4j 仍读满 6 万条", 60_000, zip4jCount(f))
            val probed = ZipEntryCountProbe.probe(f, limit)
            assertTrue("junk=$junk 不得绕过预算，实际=$probed", probed != null && probed >= limit)
        }
    }

    @Test
    fun `尾部窗口必须刚好够而不浪费`() {
        // 65578 = 22 + 65535 + 1 + 20：少 1 字节重现 locator 出窗，多则白读。
        assertEquals(65_578, ZipEntryCountProbe.TAIL_WINDOW)
        assertTrue("仍需固定窗口", ZipEntryCountProbe.TAIL_WINDOW < 70 * 1024)
        // 再多 1 字节尾随垃圾即超出 zip4j 反查范围，zip4j 自己就打不开，探针返回多少都不构成放行。
        val f = bypass(60_000, 65_537)
        val probed = ZipEntryCountProbe.probe(f, limit)
        assertTrue("超出反查范围时不得再声称超限以外的结论破坏口径", probed == null || probed < limit)
    }

    // ── 4. 有 locator 但记录不可核验 → fail-closed ───────────────────────

    @Test
    fun `ZIP64 locator 指向文件外时必须 fail_closed`() {
        val cd = cdRegion(1_000)
        val f = tempZip().apply {
            writeBytes(
                cd + zip64Record(1_000, cd.size.toLong(), 0L) +
                    zip64Locator(999_999_999L) + eocd(1, 47, 0)
            )
        }
        val probed = ZipEntryCountProbe.probe(f, limit)
        assertTrue("locator 偏移越界必须拒绝，实际=$probed", probed != null && probed >= limit)
    }

    @Test
    fun `ZIP64 记录签名不符时必须 fail_closed`() {
        val cd = cdRegion(1_000)
        val f = tempZip().apply {
            writeBytes(
                cd + zip64Record(1_000, cd.size.toLong(), 0L, sig = 0x06064b51) +
                    zip64Locator(cd.size.toLong()) + eocd(1, 47, 0)
            )
        }
        val probed = ZipEntryCountProbe.probe(f, limit)
        assertTrue("记录签名不符必须拒绝，实际=$probed", probed != null && probed >= limit)
    }

    @Test
    fun `无 locator 的哨兵 EOCD 仍按超限处理`() {
        // 只有 EOCD 哨兵、没有 locator：自相矛盾，无真值可核验，最后一处候选必须 fail-closed。
        val f = tempZip().apply {
            writeBytes(ByteArray(600) { 0x41 } + eocd(1, 400, -1))
        }
        assertEquals(limit, ZipEntryCountProbe.probe(f, limit))
        // 非 zip 仍返回 null（保持既有契约）
        assertNull(ZipEntryCountProbe.probe(File.createTempFile("shso-nozip-", ".bin").apply {
            deleteOnExit(); writeBytes(ByteArray(4096) { (it % 127).toByte() })
        }, limit))
    }
}
