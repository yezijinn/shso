// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.prop` 纳入文本判定后的护栏。
 *
 * 只改 `TEXT_EXTENSIONS` 一处即可让四处（文件页动作菜单 / 外部唤起 / 文件视图分组 /
 * 压缩包内条目分派）同时生效 —— 它们都读同一个 `FileItem.isEditableText`。
 * 这里锁住该结论，防止有人后续为「更精确」再复制一份扩展名清单。
 */
class TextExtensionPropTest {

    @Test
    fun `prop 走文本编辑口径`() {
        val item = FileItem("build.prop", "/system/build.prop", false)
        assertTrue("build.prop 必须可编辑", item.isEditableText)
        assertFalse("prop 不是图片", item.isViewableImage)
        assertFalse("prop 不是压缩包", item.isArchive)
        assertFalse("有扩展名，不应落入无扩展名文本分支", item.isExtensionlessText)
        // 同族后缀仍然有效（防止有人把 properties 换掉）
        assertTrue(FileItem("x.properties", "/x/x.properties", false).isEditableText)
    }

    @Test
    fun `大小写与数字尾缀同样命中`() {
        assertTrue(FileItem("build.PROP", "/x/build.PROP", false).isEditableText)
        assertTrue(FileItem("default.prop.1", "/x/default.prop.1", false).isEditableText)
        // 未纳入集合的相邻后缀不受影响（防止把整段前缀误改）
        assertFalse(FileItem("x.pro", "/x/x.pro", false).isEditableText)
    }
}
