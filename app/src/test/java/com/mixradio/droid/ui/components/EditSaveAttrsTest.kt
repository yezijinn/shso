// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编辑器 root 保存的「权限/属主还原」命令构造。
 *
 * 背景：root 保存走 `/data/local/tmp` 中转 + `mv` 覆盖，新文件会沿用临时文件的 mode/owner，
 * 丢掉原文件的权限与属主。写入前 `stat -c '%a %u %g'` 记录、写入后据此还原。
 */
class EditSaveAttrsTest {

    @Test
    fun `正常输出生成 chmod 与 chown`() {
        assertEquals(
            "chmod 644 /a/b.txt; chown 0:0 /a/b.txt",
            buildRestoreAttrsCommand("644 0 0", "/a/b.txt")
        )
        assertEquals(
            "chmod 0755 /a/b.txt; chown 1000:1000 /a/b.txt",
            buildRestoreAttrsCommand("0755 1000 1000", "/a/b.txt")
        )
    }

    @Test
    fun `容忍首尾空白与多空格分隔`() {
        assertEquals(
            "chmod 600 /x; chown 0:0 /x",
            buildRestoreAttrsCommand("  600   0   0 \n", "/x")
        )
    }

    @Test
    fun `字段不足返回 null`() {
        assertNull(buildRestoreAttrsCommand("644 0", "/x"))
        assertNull(buildRestoreAttrsCommand("", "/x"))
        assertNull(buildRestoreAttrsCommand("   ", "/x"))
    }

    @Test
    fun `非数字字段返回 null 以防注入`() {
        // mode 非八进制
        assertNull(buildRestoreAttrsCommand("abc 0 0", "/x"))
        // 试图把命令拼进 gid：`0;` 不满足 \d+
        assertNull(buildRestoreAttrsCommand("644 0 0; rm -rf /", "/x"))
        // uid 非数字
        assertNull(buildRestoreAttrsCommand("644 root 0", "/x"))
    }
}
