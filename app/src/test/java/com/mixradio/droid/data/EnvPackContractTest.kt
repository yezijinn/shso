// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可选扩展环境包的契约（源码文本断言）。
 *
 * 下载 / 解压需真机网络与 Context，无法在 JVM 单测跑完整路径；
 * 这里锁定「改错就静默退化或违反发布约定」的关键点。
 */
class EnvPackContractTest {

    private val src: String = java.io.File("src/main/java/com/mixradio/droid/data/EnvPack.kt").readText()

    /**
     * 非发布资产必须挂在**非纯数字**标签下。
     *
     * 发布安装包的标签是纯数字 `YYYYMMDD`；环境包属附件，套用纯数字会与发布版本
     * 混淆、也会被更新检查误读成发布日期。
     */
    @Test
    fun tagMustNotBeNumeric() {
        assertTrue("应使用带语义前缀的标签 envpack-1", src.contains("envpack-1"))
        assertFalse(
            "下载地址不得使用纯数字标签",
            Regex("""releases/download/\d{6,8}/""").containsMatchIn(src)
        )
    }

    /** 下载源必须 Gitee 优先、GitHub 备选。 */
    @Test
    fun sourcesMustPreferGitee() {
        val gitee = src.indexOf("gitee.com")
        val github = src.indexOf("github.com")
        assertTrue("必须含 Gitee 源", gitee >= 0)
        assertTrue("必须含 GitHub 备选源", github >= 0)
        assertTrue("Gitee 应排在 GitHub 之前（国内可直连）", gitee < github)
    }

    /** 必须校验下载内容，避免半截包被解压成残缺环境。 */
    @Test
    fun mustVerifyChecksum() {
        assertTrue("应对下载内容做 SHA-256 校验", src.contains("SHA-256"))
        assertTrue("校验失败应拒绝安装", src.contains("校验失败"))
    }

    /** 安装后必须把包目录接入 PATH 与库路径，并提供 Termux 系脚本依赖的前缀变量。 */
    @Test
    fun mustWireIntoExecutionEnv() {
        assertTrue("应接入 PATH", src.contains("export PATH="))
        assertTrue("应接入动态库路径", src.contains("LD_LIBRARY_PATH"))
        assertTrue("应设置 PREFIX（Termux 系脚本依赖）", src.contains("export PREFIX="))
        assertTrue("应设置 PYTHONHOME（python 找标准库）", src.contains("PYTHONHOME"))
        assertTrue("应设置 GIT_EXEC_PATH", src.contains("GIT_EXEC_PATH"))
    }

    /** 解压须处理符号链接（Termux 包大量使用，漏掉会让工具整片不可用）。 */
    @Test
    fun mustHandleSymlinks() {
        assertTrue("解压需处理符号链接", src.contains("isSymbolicLink") && src.contains("createSymbolicLink"))
    }
}
