// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 文本对比的行切分契约。
 *
 * UTF-16 的换行是两字节码元，只按单字节 `0x0A` 定位行边界会从第二行起丢掉码元对齐，
 * 行文本解成乱码、行尾 `\r` 判错。本用例锁定「按码元宽度切行」这一契约，
 * 同时保证 UTF-8 路径与逐字节切分等价。
 */
class TextCompareUnicodeBoundaryTest {

    private fun utf16(bom: Boolean, be: Boolean, text: String): ByteArray {
        val cs = if (be) StandardCharsets.UTF_16BE else StandardCharsets.UTF_16LE
        val body = text.toByteArray(cs)
        if (!bom) return body
        return if (be) byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body
        else byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
    }

    private fun compareLineByLine(name: String, a: ByteArray, b: ByteArray): Pair<String, TextCompare.Result> {
        val dir = Files.createTempDirectory("shso_cmp_$name").toFile()
        val fa = File(dir, "a.txt").apply { writeBytes(a) }
        val fb = File(dir, "b.txt").apply { writeBytes(b) }
        val out = File(dir, "out.txt")
        val result = TextCompare.run(
            dir.absolutePath, fa.absolutePath, fb.absolutePath, out.absolutePath,
            StandardCharsets.UTF_8, TextCompare.Mode.LINE_BY_LINE, {}, { false }
        )
        return out.readText(Charsets.UTF_8) to result
    }

    @Test
    fun `UTF-16LE BOM CRLF 多行逐行对比逐行命中`() {
        val text = "alpha\r\nbeta\r\ngamma\r\n"
        val bytes = utf16(bom = true, be = false, text)
        val (produced, result) = compareLineByLine("le_bom", bytes, bytes)
        assertTrue(produced, produced.contains("[1]\talpha"))
        assertTrue(produced, produced.contains("[2]\tbeta"))
        assertTrue(produced, produced.contains("[3]\tgamma"))
        assertEquals(3, result.hitLines)
    }

    @Test
    fun `UTF-16BE BOM LF 多行逐行对比逐行命中`() {
        val text = "one\ntwo\nthree\n"
        val bytes = utf16(bom = true, be = true, text)
        val (produced, result) = compareLineByLine("be_bom", bytes, bytes)
        assertTrue(produced, produced.contains("[1]\tone"))
        assertTrue(produced, produced.contains("[2]\ttwo"))
        assertTrue(produced, produced.contains("[3]\tthree"))
        assertEquals(3, result.hitLines)
    }

    @Test
    fun `UTF-16LE 无 BOM 多行逐行对比逐行命中`() {
        val text = "x\ny\nz\n"
        val bytes = utf16(bom = false, be = false, text)
        val (produced, result) = compareLineByLine("le_nobom", bytes, bytes)
        assertTrue(produced, produced.contains("[1]\tx"))
        assertTrue(produced, produced.contains("[2]\ty"))
        assertTrue(produced, produced.contains("[3]\tz"))
        assertEquals(3, result.hitLines)
    }

    @Test
    fun `UTF-8 多行逐行对比保持原有行为`() {
        val text = "p\r\nq\r\nr\r\n".toByteArray(Charset.forName("UTF-8"))
        val (produced, result) = compareLineByLine("utf8", text, text)
        assertTrue(produced, produced.contains("[1]\tp"))
        assertTrue(produced, produced.contains("[2]\tq"))
        assertTrue(produced, produced.contains("[3]\tr"))
        assertEquals(3, result.hitLines)
    }

    @Test
    fun `对比输出扩展名只能取自文件名`() {
        // 回归护栏：对整条路径 substringAfterLast('.')，无扩展名文件（Makefile / hosts 等）
        // 配上含点的父目录时，会取出 "foo/files/Makefile" 这类带子目录的「扩展名」，
        // 拼出的输出名带多层子目录，落盘因父目录不存在而失败。
        val s = File("src/main/java/com/mixradio/droid/data/TextCompare.kt").readText()
        assertTrue(
            "扩展名须先取文件名",
            s.contains("val sourceName = currentFilePath.substringAfterLast('/')")
        )
        assertFalse(
            "不得对整条路径取扩展名",
            s.contains("currentFilePath.substringAfterLast('.')")
        )
    }
}
