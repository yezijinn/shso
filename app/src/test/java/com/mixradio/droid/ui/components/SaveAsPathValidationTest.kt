// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「另存为」目标路径校验回归。
 *
 * 真实缺陷：`performSaveAs` 此前把用户输入/覆盖确认弹窗里的路径原样交给 `writeTextFile`，
 * 而后者在 ROOT 通道下等价于 `cat > <path>`，是**无条件覆盖**。于是另存为可以：
 *  - 指向目录（写入必失败，只是报错难看）；
 *  - 用 `a/../../..` 穿越出预期根，违反 AGENTS.md「路径必须过滤 `..`」；
 *  - 覆盖应用自身私有文件（`/data/data/com.mixradio.droid/…`），毁掉数据库/偏好设置。
 */
class SaveAsPathValidationTest {

    // 与真机一致：应用私有目录由调用方按当前 userId 注入
    private val priv = listOf(
        "/data/user/0/com.mixradio.droid",
        "/data/user_de/0/com.mixradio.droid",
        "/data/user_ce/0/com.mixradio.droid"
    )

    @Test fun `正常绝对路径放行`() {
        assertNull(validateSaveAsPath("/sdcard/Download/a.txt", priv))
        assertNull(validateSaveAsPath("/storage/emulated/0/Documents/笔记.md", priv))
        assertNull(validateSaveAsPath("/data/adb/shso/logs/out.log", priv))
    }

    @Test fun `空串与纯空白被拒`() {
        assertNotNull(validateSaveAsPath("", priv))
        assertNotNull(validateSaveAsPath("   ", priv))
    }

    @Test fun `相对路径被拒`() {
        assertNotNull(validateSaveAsPath("a.txt", priv))
        assertNotNull(validateSaveAsPath("./a.txt", priv))
        assertNotNull(validateSaveAsPath("../a.txt", priv))
    }

    @Test fun `路径穿越被拒`() {
        assertNotNull(validateSaveAsPath("/sdcard/../data/a.txt", priv))
        assertNotNull(validateSaveAsPath("/sdcard/a/../../b.txt", priv))
        assertNotNull(validateSaveAsPath("/sdcard/./a.txt", priv))
    }

    @Test fun `根目录与空目录段被拒`() {
        assertNotNull(validateSaveAsPath("/", priv))
        assertNotNull(validateSaveAsPath("//", priv))
        assertNotNull(validateSaveAsPath("/sdcard//a.txt", priv))
    }

    @Test fun `反斜杠与 NUL 被拒`() {
        assertNotNull(validateSaveAsPath("/sdcard\\a.txt", priv))
        assertNotNull(validateSaveAsPath("/sdcard/a\u0000.txt", priv))
    }

    @Test fun `应用私有目录被拒_防止自毁数据`() {
        // 覆盖 /data/user/0/<pkg>/… 会直接毁掉数据库与 SharedPreferences，且不可撤销
        assertNotNull(validateSaveAsPath("/data/user/0/com.mixradio.droid/databases/app.db", priv))
        assertNotNull(validateSaveAsPath("/data/user/0/com.mixradio.droid/shared_prefs/root.xml", priv))
        assertNotNull(validateSaveAsPath("/data/user_de/0/com.mixradio.droid/files/a.txt", priv))
        assertNotNull(validateSaveAsPath("/data/user/0/com.mixradio.droid", priv))
    }

    @Test fun `未注入私有目录时不做该判定`() {
        // 纯函数默认不注入，用于非编辑场景；此时只做通用路径校验
        assertNull(validateSaveAsPath("/data/user/0/com.mixradio.droid/x.txt"))
    }

    @Test fun `相似前缀的合法路径不被误杀`() {
        // 前缀相同但不是应用私有目录，必须放行
        assertNull(validateSaveAsPath("/data/user/0/com.mixradio.droid.backup/a.txt", priv))
        assertNull(validateSaveAsPath("/data/user/0/com.mixradio.dri/oa.txt", priv))
        assertNull(validateSaveAsPath("/data/media/0/a.txt", priv))
    }
}
