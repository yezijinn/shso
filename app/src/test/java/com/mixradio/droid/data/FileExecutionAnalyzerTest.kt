// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归守卫：`file` 输出行 → 类型/内容判定的纯函数。
 *
 * 背景（真机 BIYLBAFQQSS8DA69 实测，2026-09-11）：
 * - toybox 的 `file` 不支持 `-b`，旧实现把 `file: Unknown option b` 的错误文本当内容分析，
 *   导致 `/data/adb/shso/flood.sh` 被判成「二进制 / 加密」。
 * - 输出带 `<path>: ` 前缀，且路径里的 `data` 会污染关键词匹配，必须先剥前缀。
 */
class FileExecutionAnalyzerTest {

    @Test
    fun `真实 toybox 输出的 shell 脚本判为文本脚本而非二进制`() {
        // 这是本次崩溃/误判的真机原始输出（路径含 "data"，是剥前缀的关键回归点）
        val (type, content) = classifyFileTypeLine(
            "/data/adb/shso/flood.sh: /system/bin/sh script", "sh"
        )
        assertEquals("文本 / 脚本", type)
        assertEquals("明文代码", content)
    }

    @Test
    fun `路径里的 data 不得污染判定`() {
        // 若未剥掉 "<path>: " 前缀，lower 会命中 contains("data") → 误判「未知二进制」
        val (type, _) = classifyFileTypeLine("/data/system/app.txt: ASCII text", "txt")
        assertEquals("文本 / 脚本", type)
    }

    @Test
    fun `PNG 判为二进制`() {
        val (type, content) = classifyFileTypeLine(
            "/data/adb/shso/editor.png: PNG image data, 1080 x 2280, 8-bit/color RGBA, non-interlaced",
            "png"
        )
        assertEquals("未知二进制", type)
        assertEquals("二进制 / 加密", content)
    }

    @Test
    fun `ELF 判为 ELF 二进制`() {
        val (type, content) = classifyFileTypeLine(
            "/system/lib64/libfoo.so: ELF 64-bit LSB shared object, ARM aarch64", "so"
        )
        assertEquals("ELF 二进制 (.so / 可执行)", type)
        assertEquals("二进制 / 加密", content)
    }

    @Test
    fun `无冒号前缀时按整行匹配`() {
        val (type, content) = classifyFileTypeLine("ASCII text", "txt")
        assertEquals("文本 / 脚本", type)
        assertEquals("明文代码", content)
    }
}
