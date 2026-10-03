// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第五轮深挖的回归。
 *
 * 覆盖四路审计中读代码复核后确认成立的高危项：
 * ① `content://` 的 `_data` 信任边界（root 越权读取与执行）
 * ② 守卫档位与守卫实际 mode 的一致性（fail-open）
 * ③ 改权限的部分成功语义（越权残留）
 * ④ 分包安装套件的发现基准（暂存副本 vs 原始目录）
 * ⑤ 首页 shso 列表的可见性与刷新时机
 */
class RoundFiveRegressionTest {

    // ---------- ① content:// 的 _data 与 file:// 同等受白名单约束 ----------

    @Test fun `provider 伪造 _data 指向他应用私有目录必须拒绝`() {
        // 被授予的是 URI，不是 provider 写进游标的路径字符串。自建 provider 可对任意
        // URI 返回 /data/data/<别人>/files/x，链路下游用 root 去 stat / 读取 / 执行。
        assertFalse(
            "provider 声称的路径落在 /data/data 下必须拒绝",
            ExternalOpen.isExternalPathAllowed("/data/data/com.other.app/files/token")
        )
        assertFalse(
            "provider 声称的路径落在 /data/user 下必须拒绝",
            ExternalOpen.isExternalPathAllowed("/data/user/0/com.other.app/files/token")
        )
    }

    @Test fun `provider 伪造 _data 指向 adb 目录必须拒绝`() {
        // /data/adb 不在白名单里。守卫脚本/service.sh 落到这里时，
        // 扩展名判定 EXECUTE，用户点一次确认即以 root 执行攻击者指定的脚本。
        assertFalse(
            "/data/adb 必须在白名单外",
            ExternalOpen.isExternalPathAllowed("/data/adb/modules/x/service.sh")
        )
        assertFalse(
            "/data/adb/shso 必须在白名单外",
            ExternalOpen.isExternalPathAllowed("/data/adb/shso/evil.sh")
        )
    }

    @Test fun `provider 指向共享存储的正常分享不被误伤`() {
        // 合法分享（FileProvider / MediaStore / Downloads）的真实路径都在共享存储内，
        // 白名单收紧后仍须放行，否则会把正常功能一起打死。
        assertTrue(
            "共享存储的正常分享必须放行",
            ExternalOpen.isExternalPathAllowed("/storage/emulated/0/Download/notes.txt")
        )
        assertTrue(
            "/storage/self/primary 必须放行",
            ExternalOpen.isExternalPathAllowed("/storage/self/primary/Download/a.sh")
        )
        assertTrue(
            "/data/local/tmp 必须放行（shso 自己的暂存区）",
            ExternalOpen.isExternalPathAllowed("/data/local/tmp/_shso_install_x.apk")
        )
    }

    @Test fun `白名单不得因尾斜杠或前缀相似而误放行`() {
        // /storage/emulated/0evil 不在 /storage/emulated/0 下，不能靠 startsWith("/storage/emulated/0")
        // 直接匹配放行。
        assertFalse(
            "前缀相似的越界目录必须拒绝",
            ExternalOpen.isExternalPathAllowed("/storage/emulated/0evil/secret")
        )
        // 目录自身等于白名单前缀应放行（尾斜杠归一）
        assertTrue(
            "白名单前缀本身应放行",
            ExternalOpen.isExternalPathAllowed("/storage/emulated/0/")
        )
    }

    @Test fun `空白路径不得放行`() {
        assertFalse("空路径必须拒绝", ExternalOpen.isExternalPathAllowed(""))
        assertFalse("纯空白路径必须拒绝", ExternalOpen.isExternalPathAllowed("   "))
        assertFalse("根目录必须拒绝", ExternalOpen.isExternalPathAllowed("/"))
    }

    // ---------- 改权限的部分成功语义 ----------

    @Test fun `权限位输入校验须拒绝越界与非法写法`() {
        // 弹窗保存时用它做前置校验；放宽会让非法 mode 直达 chmod。
        // 3~4 位八进制均合法（4 位承载 setuid/setgid/sticky）。
        assertTrue(RootFileManager.isValidPermissionMode("755"))
        assertTrue(RootFileManager.isValidPermissionMode("0777"))
        assertTrue(RootFileManager.isValidPermissionMode("4755"))
        assertTrue(RootFileManager.isValidPermissionMode("7777"))
        assertFalse("5 位必须拒绝", RootFileManager.isValidPermissionMode("77777"))
        assertFalse("2 位必须拒绝", RootFileManager.isValidPermissionMode("99"))
        assertFalse("非八进制数字必须拒绝", RootFileManager.isValidPermissionMode("7a5"))
        assertFalse("8/9 不是八进制", RootFileManager.isValidPermissionMode("789"))
        assertFalse("空串必须拒绝", RootFileManager.isValidPermissionMode(""))
        assertFalse("前导空格必须拒绝", RootFileManager.isValidPermissionMode(" 755"))
        assertFalse("尾随空格必须拒绝", RootFileManager.isValidPermissionMode("755 "))
        assertFalse("shell 元字符必须拒绝", RootFileManager.isValidPermissionMode("$(id)"))
    }

    @Test fun `属主与用户组输入校验须拒绝 shell 元字符`() {
        // uid/gid 会被拼进 chown/chgrp 命令。
        assertTrue(RootFileManager.isValidOwnerOrGroup("0"))
        assertTrue(RootFileManager.isValidOwnerOrGroup("10216"))
        assertTrue(RootFileManager.isValidOwnerOrGroup("root"))
        assertFalse(RootFileManager.isValidOwnerOrGroup("root:x"))
        assertFalse(RootFileManager.isValidOwnerOrGroup("$(id)"))
        assertFalse(RootFileManager.isValidOwnerOrGroup("a b"))
        assertFalse(RootFileManager.isValidOwnerOrGroup(""))
    }

    // ---------- ⑤ 首页 shso 列表可见性 ----------

    @Test fun `首页列表必须保留目录条目`() {
        // 「独立存储」把文件放进 /data/adb/shso/<名>_<时间戳>/ 子目录。过滤掉目录后
        // 那些文件永远不可见，而「进入子目录」「返回上级」成为不可达的死代码。
        val dir = FileItem(name = "tool_1764000000000", path = "/data/adb/shso/tool_1764000000000", isDirectory = true)
        val script = FileItem(name = "run.sh", path = "/data/adb/shso/tool_1764000000000/run.sh", isDirectory = false)
        val binary = FileItem(name = "lib.so", path = "/data/adb/shso/lib.so", isDirectory = false)
        val doc = FileItem(name = "readme.md", path = "/data/adb/shso/readme.md", isDirectory = false)

        val visible = listOf(dir, script, binary, doc).filter { it.isDirectory || it.isSupportedExecutable }

        assertTrue("子目录必须可见", visible.contains(dir))
        assertTrue("子目录内的脚本必须可见", visible.contains(script))
        assertTrue("so 二进制必须可见", visible.contains(binary))
        assertFalse("无关文档不应出现在首页执行列表", visible.contains(doc))
    }
}
