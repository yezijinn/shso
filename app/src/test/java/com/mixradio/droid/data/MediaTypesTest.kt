// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypesTest {

    @Test
    fun `常见视频扩展名判定为视频`() {
        listOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "m2ts", "flv")
            .forEach { ext ->
                assertEquals("$ext 应为视频", MediaKind.VIDEO, mediaKindOfExtension(ext))
                assertTrue("$ext 应判 isVideo", FileItem("a.$ext", "/a.$ext", false).isVideo)
            }
    }

    @Test
    fun `常见音频扩展名判定为音频`() {
        listOf("mp3", "wav", "aac", "flac", "ogg", "opus", "m4a", "mid")
            .forEach { ext ->
                assertEquals("$ext 应为音频", MediaKind.AUDIO, mediaKindOfExtension(ext))
                assertTrue("$ext 应判 isAudio", FileItem("a.$ext", "/a.$ext", false).isAudio)
            }
    }

    @Test
    fun `大小写与下载器数字尾缀同口径`() {
        assertEquals(MediaKind.VIDEO, mediaKindOf("movie.MP4"))
        assertEquals(MediaKind.VIDEO, mediaKindOf("movie.mp4.1"))
        assertEquals(MediaKind.AUDIO, mediaKindOf("song.Mp3.2"))
        val item = FileItem("movie.MP4.1", "/movie.MP4.1", false)
        assertTrue(item.isVideo)
        assertTrue(item.isMedia)
        assertFalse(item.isAudio)
    }

    @Test
    fun `ts 与 mts 保留给文本，不得变成媒体`() {
        // 回归护栏：ts 是 TypeScript、mts 是 TypeScript 模块写法；
        // 收进视频集合会让 .ts 源码多出「播放」入口，把文本编辑挤到次要位置。
        assertEquals(MediaKind.NONE, mediaKindOf("app.ts"))
        assertEquals(MediaKind.NONE, mediaKindOf("stream.mts"))
        val ts = FileItem("app.ts", "/app.ts", false)
        assertTrue("ts 仍须可编辑", ts.isEditableText)
        assertFalse("ts 不得判为媒体", ts.isMedia)
    }

    @Test
    fun `媒体集合与文本图片安装包集合无交集`() {
        assertTrue((MEDIA_EXTENSIONS intersect TEXT_EXTENSIONS).isEmpty())
        assertTrue((MEDIA_EXTENSIONS intersect IMAGE_EXTENSIONS).isEmpty())
        assertTrue((MEDIA_EXTENSIONS intersect INSTALLABLE_EXTENSIONS).isEmpty())
    }

    @Test
    fun `目录与无扩展名不是媒体`() {
        assertFalse(FileItem("dir", "/dir", true).isMedia)
        assertFalse(FileItem("Dockerfile", "/Dockerfile", false).isMedia)
        assertEquals(MediaKind.NONE, mediaKindOfExtension(""))
    }

    @Test
    fun `点开头的媒体仍按媒体识别而不降级为文本`() {
        // 下载器会留下 .mp4 / .mp3 这类点开头文件；若按「隐藏点文件 = 文本」处理，
        // 图标变 TXT、菜单首位变「编辑文本」，播放被挤到后面。
        val hidden = FileItem(".mp4", "/.mp4", false)
        assertTrue(hidden.isMedia)
        assertFalse("点开头媒体不应判成无扩展名文本", hidden.isExtensionlessText)
        assertEquals("MP4", fileExtensionLabel(hidden))
        // 非媒体的点开头配置仍按文本
        assertTrue(FileItem(".gitignore", "/.gitignore", false).isExtensionlessText)
    }

    @Test
    fun `时间格式`() {
        assertEquals("00:00", formatMediaTime(0))
        assertEquals("00:00", formatMediaTime(-1))
        assertEquals("01:05", formatMediaTime(65_000))
        assertEquals("59:59", formatMediaTime(3_599_000))
        assertEquals("1:00:00", formatMediaTime(3_600_000))
        assertEquals("2:03:04", formatMediaTime(7_384_000))
    }

    @Test
    fun `外部唤起对媒体 MIME 直通播放`() {
        // 无扩展名分享目标：video/ audio/ 必须进播放器，而不是被当文本打开
        assertEquals(ExternalAction.PLAY_MEDIA, actionForMimeType("video/mp4"))
        assertEquals(ExternalAction.PLAY_MEDIA, actionForMimeType("audio/mpeg"))
        assertEquals(ExternalAction.PLAY_MEDIA, actionForMimeType("VIDEO/MP4"))
        // 有扩展名且命中媒体集合 → PLAY_MEDIA，且优先于 isEditableText
        assertEquals(
            ExternalAction.PLAY_MEDIA,
            decideExternalAction(
                isExtensionless = false, isInstallable = false, isSupportedExecutable = false,
                isViewableImage = false, isEditableText = false, isArchive = false,
                mimeType = null, isMedia = true
            )
        )
        // 默认参数保持既有语义：不传 isMedia 与传 false 等价
        assertEquals(
            ExternalAction.BROWSE,
            decideExternalAction(false, false, false, false, false, false, null)
        )
    }
}
