// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

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
}
