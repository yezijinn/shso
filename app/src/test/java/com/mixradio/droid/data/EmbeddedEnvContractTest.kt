// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 POSIX 环境的落地契约（源码文本断言）。
 *
 * 解压 / 执行需真机 Context 与 root，无法在 JVM 单测跑完整路径；
 * 这里锁定几处「改错就静默退化」的关键点，避免回归被无声吞掉。
 */
class EmbeddedEnvContractTest {

    private fun read(rel: String): String = run {
        val f = java.io.File("src/main/java/com/mixradio/droid/$rel")
        f.readText()
    }

    private val envSrc = read("data/EmbeddedEnv.kt")
    private val rootSrc = read("data/RootService.kt")

    /** 资产名不得带 `.gz`：AGP 会把 `.gz` 资产自动解压并改名，导致按名读取失败。 */
    @Test
    fun assetNameMustNotUseGzSuffix() {
        assertFalse(
            "资产名不得以 .gz 结尾（打包工具会改名，读取必失败）",
            envSrc.contains("\"env.tar.gz\"")
        )
        assertTrue("内置资产名应为 env.bin", envSrc.contains("\"env.bin\""))
    }

    /** 必须注入 LD_LIBRARY_PATH：内置 bash / curl 的 RUNPATH 指向不存在的 Termux 前缀。 */
    @Test
    fun mustInjectLdLibraryPath() {
        assertTrue(
            "缺少 LD_LIBRARY_PATH 会让内置 bash/curl 报 CANNOT LINK EXECUTABLE",
            envSrc.contains("LD_LIBRARY_PATH")
        )
        assertTrue("必须设置 SSL_CERT_FILE/CURL_CA_BUNDLE 才能做 HTTPS 校验",
            envSrc.contains("CURL_CA_BUNDLE"))
    }

    /** 必须为 busybox applet 建入口，否则脚本里的 wget/tar/xz 等按命令名找不到。 */
    @Test
    fun mustCreateApplets() {
        assertTrue(
            "必须为 busybox applet 建立 bin/<applet> 入口",
            envSrc.contains("createSymbolicLink") || envSrc.contains("createLink")
        )
    }

    /** 执行链路必须把内置环境接入 PATH。 */
    @Test
    fun rootServiceMustWireEnvIntoPath() {
        assertTrue(
            "执行前需接入内置环境的 PATH/LD_LIBRARY_PATH",
            rootSrc.contains("EmbeddedEnv.exportFragment()")
        )
        assertTrue(
            "必须先确保环境解压就绪",
            rootSrc.contains("EmbeddedEnv.ensure(")
        )
    }

    /** shebang 指向 bash 时必须改用内置 bash，否则 bash 脚本半途报错。 */
    @Test
    fun mustHonorBashShebang() {
        assertTrue("需按 shebang 判定 bash", rootSrc.contains("usesBashShebang"))
        assertTrue("需使用内置 bash 路径", rootSrc.contains("EmbeddedEnv.bashPath"))
    }
}
