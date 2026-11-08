// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 POSIX 工具链的落地契约（源码文本断言）。
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
    private val prefixSrc = read("data/TermuxPrefix.kt")
    private val rootSrc = read("data/RootService.kt")

    /** 从源码读出 shell applet 黑名单（[EmbeddedEnv] 里唯一的定义点）。 */
    private val shellBlacklist: Set<String> =
        Regex("SHELL_APPLETS = setOf\\(([^)]*)\\)")
            .find(envSrc)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }?.toSet()
            .orEmpty()

    /** 资产名固定为 `env.bin`：压缩开关按扩展名配置，与资产名无关，改名会直接读不到资产。 */
    @Test
    fun assetNameMustNotUseGzSuffix() {
        assertFalse(
            "资产名不得以 .gz 结尾（改名会导致按名读取失败）",
            envSrc.contains("\"env.tar.gz\"")
        )
        assertTrue("内置资产名应为 env.bin", envSrc.contains("\"env.bin\""))
    }

    /** 资产必须真实存在且包含完整工具链（不是旧的 4 个二进制）。 */
    @Test
    fun assetMustCarryFullToolchain() {
        val asset = java.io.File("src/main/assets/env.bin")
        assertTrue("缺少内置资产 env.bin", asset.isFile)
        assertTrue(
            "内置资产应含完整工具链（当前不足 20 MiB 视为未合并）",
            asset.length() > 20L * 1024 * 1024
        )
        listOf("bin/python3", "bin/git", "bin/openssl", "bin/ssh", "bin/rsync").forEach {
            assertTrue("必需文件清单缺少 $it", envSrc.contains("\"$it\""))
        }
    }

    /** VERSION 与 applets 一次读全：单测直接看归档内容，避免只断言源码文本。 */
    private class AssetMeta(val version: String, val applets: Set<String>)

    private fun assetMeta(): AssetMeta {
        var version = ""
        var applets = emptySet<String>()
        java.util.zip.GZIPInputStream(
            java.io.File("src/main/assets/env.bin").inputStream()
        ).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "VERSION" -> version = tar.readBytes().toString(Charsets.UTF_8).trim()
                        "share/applets.txt" ->
                            applets = tar.readBytes().toString(Charsets.UTF_8)
                                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return AssetMeta(version, applets)
    }

    /** 版本号与资产同步：内置内容变化（如不再生成 bin/sh）后版本递增。 */
    @Test
    fun versionMustMatchAsset() {
        assertTrue("环境版本应为 5", envSrc.contains("ENV_VERSION = \"5\""))
        assertEquals("归档内 VERSION 必须与 ENV_VERSION 同步", "5", assetMeta().version)
        val script = java.io.File("../tools/build_env_bin.py").readText()
        assertTrue(
            "合并工具的默认版本需同步为 5",
            script.contains("--version\", default=\"5\"")
        )
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

    /**
     * 前缀变量必须齐备，但**不得**全局导出语言/工具链变量。
     *
     * `PYTHONHOME` / `PYTHONPATH` / `GIT_EXEC_PATH` / `GIT_TEMPLATE_DIR` / `OPENSSL_CONF`
     * 一旦全局导出，会劫持**真实 Termux** 的同名工具（解释器被迫用内置 stdlib、
     * git 用内置 exec-path、openssl 用内置配置），装了 Termux 的设备反而环境错乱。
     * 内置工具的资源定位改由 [TermuxPrefix] 按编译期前缀补缺失目录/入口实现。
     */
    @Test
    fun mustExportPrefixButNotHijackRuntimeVars() {
        listOf("PREFIX", "TERMUX_PREFIX").forEach { name ->
            assertTrue("缺少环境变量 $name", envSrc.contains("export $name="))
        }
        listOf("PYTHONHOME", "PYTHONPATH", "GIT_EXEC_PATH", "GIT_TEMPLATE_DIR", "OPENSSL_CONF")
            .forEach { name ->
                assertFalse(
                    "不得全局导出 $name：会污染真实 Termux 的同名工具",
                    envSrc.contains("export $name=")
                )
            }
        listOf("libexec/git-core", "share/git-core", "etc/tls/openssl.cnf", "startsWith(\"python3.\")")
            .forEach {
                assertTrue("内置资源需由前缀补链（缺 $it）", prefixSrc.contains(it))
            }
    }

    /** 必须为 busybox applet 建入口，否则脚本里的 wget/tar/xz 等按命令名找不到。 */
    @Test
    fun mustCreateApplets() {
        assertTrue(
            "必须为 busybox applet 建立 bin/<applet> 入口",
            envSrc.contains("createSymbolicLink") || envSrc.contains("createLink")
        )
    }

    /**
     * shell applet 不得链到 busybox：sh / ash / hush / mash 在本机直接执行即 SIGSEGV。
     *
     * `#!/.../bin/sh`、`#!/.../bin/ash` 类脚本按字面路径命中崩溃入口，
     * 归档清单（含 ash / hush 等名字）与运行时生成两处都必须挡住，
     * 缺位入口交由 [TermuxPrefix] 链 `/system/bin/sh` 兜底。
     */
    @Test
    fun shellAppletsMustNeverLinkToBusybox() {
        val required = setOf("sh", "ash", "hush", "mash")
        assertTrue("源码黑名单需覆盖 $required，实为 $shellBlacklist", shellBlacklist.containsAll(required))
        assertTrue(
            "createAppletLinks 需按黑名单跳过，避免清单/旧环境生成指向 busybox 的 shell 入口",
            envSrc.contains("if (name in SHELL_APPLETS) continue")
        )
        // 归档清单里出现的 shell 名字必须全部被黑名单挡住
        val listed = assetMeta().applets
        required.forEach { name ->
            assertTrue(
                "清单含 $name 但源码未挡（会生成指向 busybox 的入口）",
                name in shellBlacklist || name !in listed
            )
        }
        // 已安装环境（版本号未变、不会重建）里的旧入口同样要清掉
        assertTrue("旧环境需清理 shell applet 链", envSrc.contains("sanitizeShellApplets"))
        assertTrue(
            "只能删指向 busybox 的软链，真实文件不动",
            envSrc.contains("Files.isSymbolicLink") && envSrc.contains("!= \"busybox\"")
        )
    }

    /** 打包含内置 zip 前必须先 ensure，否则冷启动静默退回系统 zip。 */
    @Test
    fun archiveCreatorMustEnsureEnvFirst() {
        val src = read("data/ArchiveCreator.kt")
        val ensure = src.indexOf("EmbeddedEnv.ensure(")
        val binDir = src.indexOf("EmbeddedEnv.binDir")
        assertTrue("读取内置 zip 路径前需 ensure 内置环境", ensure >= 0)
        assertTrue("需先 ensure 再读 binDir", binDir >= 0 && ensure < binDir)
        assertTrue("ensure 失败需退回系统 zip", src.contains("?: \"zip\""))
    }

    /** 软链目标按父目录 + linkName 解析，允许合法 ../，越界拒绝。 */
    @Test
    fun symlinkTargetsMustStayInsideRoot() {
        assertTrue("软链需按父目录 + linkName 解析", envSrc.contains("File(parent, linkName)"))
        assertTrue("需 canonical 校验目标", envSrc.contains(".canonicalFile"))
        assertTrue("需校验目标仍在环境根内", envSrc.contains("isInside(root, resolved)"))
    }

    /** 安装失败不固化：可重试，且清理旧版扩展包目录。 */
    @Test
    fun installMustBeRetryableAndCleanLegacy() {
        assertTrue("需有重试次数常量", envSrc.contains("INSTALL_ATTEMPTS"))
        assertTrue("旧扩展包目录需清理", envSrc.contains("\"envpack\""))
        assertTrue("解压需落到暂存目录", envSrc.contains("STAGING_NAME"))
        assertTrue("替换前需备份旧目录以便回滚", envSrc.contains("\$DIR_NAME.old"))
    }

    /**
     * 整目录替换只走同文件系统 rename，失败就保守回滚。
     *
     * `copyRecursively` 会把归档里的软链展开成实体文件（`lib/` 下几十个多版本 .so），
     * 存储峰值翻数倍；且改名失败还删掉旧目录会把可用环境一并丢掉。
     */
    @Test
    fun failedReplaceMustRollBackWithoutCopying() {
        assertFalse(
            "rename 失败不得复制：copyRecursively 会跟随软链造成存储放大",
            envSrc.contains("staging.copyRecursively")
        )
        assertTrue(
            "旧环境改名失败需保留现有可用环境",
            envSrc.contains("if (!hadOld)")
        )
        assertTrue("失败需回滚备份", envSrc.contains("backup.renameTo(root)"))
        assertTrue(
            "环境就绪后需清掉遗留 env.old（升级峰值不保留）",
            envSrc.contains("\"\$DIR_NAME.old\").deleteRecursively()")
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
