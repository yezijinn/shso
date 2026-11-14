// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileIconLabelTest {

    private fun item(name: String) = FileItem(name, "/dir/$name", false)

    @Test
    fun `没有专门图标的格式用扩展名文本`() {
        assertEquals("ZIP", fileExtensionLabel(item("a.zip")))
        assertEquals("RAR", fileExtensionLabel(item("a.rar")))
        assertEquals("MP4", fileExtensionLabel(item("m.mp4")))
        assertEquals("WAV", fileExtensionLabel(item("s.wav")))
        assertEquals("MKV", fileExtensionLabel(item("v.mkv")))
        assertEquals("7Z", fileExtensionLabel(item("a.7z")))
        assertEquals("GZ", fileExtensionLabel(item("a.tar.gz")))
        assertEquals("DOCX", fileExtensionLabel(item("a.docx")))
        assertEquals("TORRE", fileExtensionLabel(item("a.torrent")))
    }

    @Test
    fun `数字尾缀与大小写走同一口径`() {
        assertEquals("MP4", fileExtensionLabel(item("m.mp4.1")))
        assertEquals("WAV", fileExtensionLabel(item("s.WAV")))
    }

    @Test
    fun `已知类型不得回退为扩展名`() {
        assertNull("目录", fileExtensionLabel(FileItem("dir", "/dir", true)))
        assertNull("安装包", fileExtensionLabel(item("a.apk")))
        assertNull("安装包", fileExtensionLabel(item("a.apk.1")))
        assertNull("图片", fileExtensionLabel(item("a.png")))
        assertNull("文本", fileExtensionLabel(item("a.kt")))
        assertNull("文本", fileExtensionLabel(item("a.txt")))
        assertNull("文本", fileExtensionLabel(item("a.prop")))
        assertNull("字体", fileExtensionLabel(item("a.ttf")))
        assertNull("字体", fileExtensionLabel(item("a.otf")))
        assertNull("脚本", fileExtensionLabel(item("a.sh")))
        assertNull("二进制", fileExtensionLabel(item("a.so")))
    }

    @Test
    fun `无扩展名与隐藏点文件降级为文本而非扩展名`() {
        assertNull(fileExtensionLabel(item("Dockerfile")))
        assertNull(fileExtensionLabel(item(".gitignore")))
        assertNull(fileExtensionLabel(item(".env")))
        assertEquals("?", extensionIconText("") ?: "?")
    }

    @Test
    fun `超长扩展名截断且不含省略号`() {
        assertEquals("ABCDE", fileExtensionLabel(item("x.abcdefg")))
        assertEquals("ABCDE", extensionIconText("abcdefg", maxChars = 5))
        assertEquals("AB", extensionIconText("abcdefg", maxChars = 2))
        assertNull(extensionIconText("abcdefg", maxChars = 0))
        // 非字母数字扩展名（如全空格）清洗后为空 → 交调用方降级
        assertNull(extensionIconText("   "))
    }
}
