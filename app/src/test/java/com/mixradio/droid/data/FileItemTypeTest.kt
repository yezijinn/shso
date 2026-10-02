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
}
