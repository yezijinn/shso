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
 * 旧实现 `ByteArrayOutputStream(total.toInt().coerceAtMost(Int.MAX_VALUE))`：
 * - >2GB：`total.toInt()` 溢出为**负数** → `IllegalArgumentException: Negative initial size`；
 * - 1–2GB：直接尝试申请等量内存 → OOM。
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
     * 旧实现用 `File(filePath).readBytes()` 把整个文件读进堆后再截断；而该路径正是给
     * 「>128KB 走分段」的大文件做编码探测的入口，文件可达数百 MB → 必然 OOM。
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
}
