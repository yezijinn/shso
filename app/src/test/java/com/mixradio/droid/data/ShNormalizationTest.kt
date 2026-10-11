// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShNormalizationTest {

    @Test fun `plain LF script runs directly`() {
        val result = ShNormalization.decideBytes("#!/system/bin/sh\necho ok\n".toByteArray())
        assertEquals(ShNormalization.Plan.DIRECT, result.plan)
    }

    @Test fun `plain CRLF script is normalized`() {
        val result = ShNormalization.decideBytes("#!/system/bin/sh\r\necho ok\r\n".toByteArray())
        assertEquals(ShNormalization.Plan.NORMALIZE, result.plan)
    }

    @Test fun `BOM text script is normalized`() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            "#!/system/bin/sh\necho ok\n".toByteArray()
        assertEquals(ShNormalization.Plan.NORMALIZE, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF self extractor with gzip payload preserves exact bytes`() {
        val text = "#!/system/bin/sh\r\ntail -c +120 \"${'$'}0\" | gzip -cd\r\nexit\r\n"
            .toByteArray()
        val bytes = text + byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00, 0x00)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF self extractor detected by own byte offset even without known magic`() {
        val bytes = "#!/system/bin/sh\r\ntail -c +120 \"${'$'}0\" | custom-decompress\r\n".toByteArray() +
            byteArrayOf(0x11, 0x22, 0x33, 0x44)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF script with embedded xz payload preserves exact bytes`() {
        val text = "#!/bin/sh\r\necho extracting\r\n".toByteArray()
        val bytes = text + byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00, 0x01)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF self extractor using tail -n offset is preserved`() {
        val bytes = "#!/bin/sh\r\ntail -n +12 \"${'$'}0\" | gzip -cd\r\n".toByteArray() +
            byteArrayOf(0x11, 0x22, 0x33, 0x44)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF self extractor using braced zero with head -c is preserved`() {
        val bytes = "#!/bin/sh\r\nhead -c 999 \"${'$'}{0}\" | custom-dec\r\n".toByteArray() +
            byteArrayOf(0x11, 0x22, 0x33, 0x44)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF self extractor using tail with variable offset is preserved`() {
        // 真机样本（clear.sh / 阿灵的腾讯游戏通用清理➕设备标识重置…​.sh）的实际写法：
        //   gztmp=$gztmpdir/$0
        //   tail $tail_n +$skip <"$0" | gzip -cd > "$gztmp"     # skip=50
        // 偏移不是字面量而是变量，早期正则只认 `+\s*\d+` 会漏判；该文件含 83500 个 CR 字节，
        // 一旦按普通文本去 CR，载荷整体前移且压缩流内的 CR 也被删掉 → 解压必然失败。
        val text = "#!/bin/sh\r\nskip=50\r\ngztmp=${'$'}gztmpdir/${'$'}0\r\n" +
            "tail ${'$'}tail_n +${'$'}skip <\"${'$'}0\" | gzip -cd > \"${'$'}gztmp\"\r\n"
        val bytes = text.toByteArray() + byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x0d)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF self extractor referencing unbraced BASH_SOURCE is preserved`() {
        // 无花括号的 $BASH_SOURCE 与 ${BASH_SOURCE}、$0 是同一语义的合法写法，
        // 早期判定只认带花括号形式，会把它当普通文本脚本去 CR、破坏载荷。
        val text = "#!/bin/sh\r\ntail -c +120 \"${'$'}BASH_SOURCE\" | gzip -cd\r\n"
        val bytes = text.toByteArray() + byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF plain text containing bare BZh is still normalized`() {
        // 裸 BZh 只有 3 字节纯 ASCII，纯文本里（注释、字符串）偶发命中；
        // 特征须连级别数字一起要求，否则本应归一化的 CRLF 脚本会被误判为 DIRECT。
        val bytes = "#!/bin/sh\r\n# 备份前先看 BZh 标记\r\necho ok\r\n".toByteArray()
        assertEquals(ShNormalization.Plan.NORMALIZE, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF script with embedded bzip2 payload preserves exact bytes`() {
        val text = "#!/bin/sh\r\ntail -c +120 \"${'$'}0\" | bzip2 -cd\r\n".toByteArray()
        val bytes = text + byteArrayOf(0x42, 0x5a, 0x68, 0x39, 0x31, 0x41)
        assertEquals(ShNormalization.Plan.DIRECT, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `CRLF plain text containing lzma-like bytes is still normalized`() {
        // 3 字节 LZMA 特征（5D 00 00）已从白名单移除：它在本应用不支持，却极易假命中，
        // 会把本应归一化的 CRLF 脚本误判为 DIRECT，使 A75 的修复失效。
        val bytes = "#!/bin/sh\r\necho ok\r\n".toByteArray() + byteArrayOf(0x5d, 0x00, 0x00)
        assertEquals(ShNormalization.Plan.NORMALIZE, ShNormalization.decideBytes(bytes).plan)
    }

    @Test fun `unreadable file reports app unreadable for root fallback`() {
        val missing = java.io.File("/definitely/not/here/shso-x.sh")
        val decision = ShNormalization.decide(missing)
        assertEquals(ShNormalization.Plan.DIRECT, decision.plan)
        assertEquals(false, decision.appReadable)
    }
}
