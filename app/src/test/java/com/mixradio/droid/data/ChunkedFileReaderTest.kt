// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归守卫：`loadAll` 的读取上限。
 *
 * 读取上限不得超过 Int 范围：否则按文件总字节数申请初始容量会整数溢出
 * （>2GB 时 `total.toInt()` 变为负数 → `IllegalArgumentException`），或在 1–2GB 区间直接 OOM。
 */
class ChunkedFileReaderTest {

    @Test
    fun `超过上限的文件按上限读取`() {
        assertEquals(ChunkedFileReader.MAX_LOAD_BYTES, ChunkedFileReader.cappedLoadBytes(3L * 1024 * 1024 * 1024))
        assertEquals(ChunkedFileReader.MAX_LOAD_BYTES, ChunkedFileReader.cappedLoadBytes(Long.MAX_VALUE))
    }

    @Test
    fun `未超上限时按原始大小`() {
        assertEquals(1024L, ChunkedFileReader.cappedLoadBytes(1024L))
        assertEquals(
            ChunkedFileReader.LARGE_FILE_THRESHOLD,
            ChunkedFileReader.cappedLoadBytes(ChunkedFileReader.LARGE_FILE_THRESHOLD)
        )
    }

    @Test
    fun `非正数回退为 0`() {
        assertEquals(0L, ChunkedFileReader.cappedLoadBytes(0L))
        assertEquals(0L, ChunkedFileReader.cappedLoadBytes(-1L))
    }

    @Test
    fun `上限本身不超过 Int 范围，保证初始容量不溢出`() {
        assertTrue(ChunkedFileReader.MAX_LOAD_BYTES <= Int.MAX_VALUE.toLong())
        assertTrue(ChunkedFileReader.MAX_LOAD_BYTES > 0L)
    }

    /**
     * 回归守卫：编码探测读取必须**只读前 N 字节**。
     *
     * 编码探测只需读取前 N 字节：该路径用于「>128KB 走分段」的大文件，
     * 文件可达数百 MB，若整文件读入堆后再截断必然 OOM。
     * 这里直接测无 Android 依赖的 [ChunkedFileReader.readHeadLocal]。
     */
    @Test
    fun `有界读取只返回前 N 字节`() {
        val f = File.createTempFile("chunked-head", ".bin")
        try {
            val payload = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
            f.writeBytes(payload)

            val head = ChunkedFileReader.readHeadLocal(f.absolutePath, 4096)
            assertEquals(4096, head.size)
            assertArrayEquals(payload.copyOf(4096), head)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `有界读取对小于上限的文件返回全部内容`() {
        val f = File.createTempFile("chunked-small", ".bin")
        try {
            val payload = "hello 世界".toByteArray(Charsets.UTF_8)
            f.writeBytes(payload)

            val head = ChunkedFileReader.readHeadLocal(f.absolutePath, 4096)
            assertArrayEquals(payload, head)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `有界读取对不存在的文件返回空数组`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "chunked-does-not-exist-${System.nanoTime()}")
        assertTrue(ChunkedFileReader.readHeadLocal(missing.absolutePath, 4096).isEmpty())
    }

    // 分段加载的行边界对齐（避免在行/多字节字符中间切断）

    @Test
    fun `行对齐返回最后一个换行的结束下标`() {
        val bytes = "abc\ndef\nxyz".toByteArray(Charsets.UTF_8)
        // "abc\ndef\n" 共 8 字节，下一个块的起点应为 8
        assertEquals(8, ChunkedFileReader.lastCompleteLineEnd(bytes))
    }

    @Test
    fun `行对齐在末尾换行时返回全长`() {
        val bytes = "abc\n".toByteArray(Charsets.UTF_8)
        assertEquals(4, ChunkedFileReader.lastCompleteLineEnd(bytes))
    }

    @Test
    fun `行对齐在无换行时返回负一`() {
        assertEquals(-1, ChunkedFileReader.lastCompleteLineEnd("abc".toByteArray(Charsets.UTF_8)))
        assertEquals(-1, ChunkedFileReader.lastCompleteLineEnd(ByteArray(0)))
    }

    @Test
    fun `行对齐落在多字节字符之后而非其中间`() {
        // "中\n" = E4 B8 AD 0A → 换行在第 4 字节，切割点必须是 4（不会切在汉字中间）
        val bytes = "中\n".toByteArray(Charsets.UTF_8)
        assertEquals(4, ChunkedFileReader.lastCompleteLineEnd(bytes))
    }
}
