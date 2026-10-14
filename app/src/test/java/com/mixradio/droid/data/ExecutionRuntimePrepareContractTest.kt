// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本执行前「运行期基础设施」的三条硬约束（源码文本断言）：
 * 1. 建 Termux 证书软链：内置 curl 硬编码 CA 路径，指向内置证书后 HTTPS 不再报 cert 77。
 * 2. 建 `TMPDIR` 兜底目录：依赖 `$TMPDIR` 的自解压脚本不再因目录不存在而 127 退出。
 * 3. 幂等且执行前触发：并发/重复调用只跑一次，且执行与终端两条入口都调用。
 */
class ExecutionRuntimePrepareContractTest {

    private fun src(rel: String): String =
        java.io.File("src/main/java/com/mixradio/droid/$rel").readText()

    private val rootService = src("data/RootService.kt")
    private val embeddedEnv = src("data/EmbeddedEnv.kt")

    /** 证书软链：软链点名与内置证书名一致，且源存在才建（不盲建）。 */
    @Test
    fun certSymlinkMustPointToEmbeddedCert() {
        assertTrue("需声明 Termux 证书目录", rootService.contains("/data/data/com.termux/files/usr/etc/tls"))
        assertTrue("软链目标文件名需为 cert.pem", rootService.contains("/cert.pem"))
        assertTrue("需先建软链所在目录", rootService.contains("mkdir -p "))
        // 源存在才建：避免出现指向不存在的悬空软链
        assertTrue("源存在才建软链", rootService.contains("[ -e "))
    }

    /** 内置证书文件名须为 cert.pem，软链点与目标才对得上。 */
    @Test
    fun embeddedCertFileNameIsCertPem() {
        assertTrue("内置证书需为 etc/cert.pem", embeddedEnv.contains("etc/cert.pem"))
    }

    /** 临时目录兜底：root 侧 /data/local/tmp 须被创建。 */
    @Test
    fun tempDirFallbackMustBeCreated() {
        assertTrue("需声明根临时目录常量", rootService.contains("ROOT_TMP_DIR"))
        assertTrue("需创建根临时目录", rootService.contains("mkdir -p "))
    }

    /** 幂等：用 AtomicBoolean CAS 保证只执行一次。 */
    @Test
    fun prepareMustBeIdempotent() {
        assertTrue("需有 preparedRuntime 标志", rootService.contains("preparedRuntime"))
        assertTrue("需用 CAS 保证幂等", rootService.contains("compareAndSet(false, true)"))
    }

    /** 执行前触发：脚本执行入口与终端入口都须调用。 */
    @Test
    fun prepareMustBeCalledBeforeExecution() {
        val count = Regex("""prepareExecutionRuntime\(\)""").findAll(rootService).count()
        assertTrue("执行前须调用；实际调用 $count 次", count >= 2)
    }
}
