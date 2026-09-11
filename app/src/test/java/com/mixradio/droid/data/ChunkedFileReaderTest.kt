// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
