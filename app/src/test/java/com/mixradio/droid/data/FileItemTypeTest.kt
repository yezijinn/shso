// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileItemTypeTest {
    @Test
    fun `dot files keep text fallback but known actions use suffix`() {
        assertTrue(FileItem(".env", "/.env", false).isExtensionlessText)
        assertFalse(FileItem(".apk", "/.apk", false).isExtensionlessText)
        assertTrue(FileItem(".apk", "/.apk", false).isInstallable)
        assertFalse(FileItem(".zip", "/.zip", false).isExtensionlessText)
        assertTrue(FileItem(".zip", "/.zip", false).isArchive)
        assertFalse(FileItem(".sh", "/.sh", false).isExtensionlessText)
        assertTrue(FileItem(".sh", "/.sh", false).isSupportedExecutable)
        assertTrue(FileItem("archive.zip.1", "/archive.zip.1", false).isArchive)
        assertTrue(FileItem("archive.zip.1", "/archive.zip.1", false).isExtractableArchive)
    }

    @Test
    fun `下载器追加数字尾缀的脚本仍判可执行`() {
        // 腾讯系下载会给重名文件加「.数字」尾缀（qq.sh.1）。realExtension 剥尾缀后再判格式，
        // 与 isInstallable / isArchive 同口径；否则文件页给出执行入口、点击却报「不支持格式」。
        assertTrue(FileItem("qq.sh.1", "/qq.sh.1", false).isExecutableScript)
        assertTrue(FileItem("libx.so.2", "/libx.so.2", false).isExecutableBinary)
        assertTrue(FileItem("qq.sh.1", "/qq.sh.1", false).isSupportedExecutable)
        // 非脚本尾缀不受影响
        assertFalse(FileItem("notes.txt.1", "/notes.txt.1", false).isExecutableScript)
    }

    @Test
    fun `执行入口与文件页谓词共用同一份尾缀口径`() {
        // 三处入口（文件页谓词 / RootService.executeFile / 首页路径输入）此前各写一份
        // 「剥 .数字 再判后缀」，任一处漂移都会让同一路径出现「列表里可执行、点了报不支持」。
        // 现统一走这两个顶层函数，此处锁住其行为与谓词一致。
        assertEquals("sh", realExtensionOf("qq.sh.1"))
        assertEquals("so", realExtensionOf("libx.so.2"))
        assertEquals("zip", realExtensionOf("archive.zip.1"))
        assertEquals("", realExtensionOf("noext"))
        assertEquals("apk", realExtensionOf("qq.apk"))
        assertEquals("qq.sh", stripNumericSuffix("qq.sh.1"))
        assertEquals("qq.apk", stripNumericSuffix("qq.apk"))
        assertEquals("ext", realExtensionOf(".ext.1"))
        listOf("qq.sh.1", "libx.so.2", "archive.zip.1", "qq.apk", "noext").forEach { name ->
            assertEquals(
                "$name 的真实扩展名必须与 FileItem.realExtension 一致",
                FileItem(name, "/$name", false).realExtension,
                realExtensionOf(name)
            )
        }
    }
}
