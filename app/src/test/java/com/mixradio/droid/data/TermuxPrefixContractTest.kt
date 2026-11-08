// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Termux 前缀补链契约（源码文本断言）。
 *
 * 归档内脚本的 shebang 是绝对路径（`/data/data/com.termux/files/usr/bin/python3.14`、
 * `.../bin/env`、`.../bin/sh`），内核不看 `PATH`。只链 `lib/` 与 `bin/bash` 时
 * 这些入口全部落空，脚本按 127 退出。此处锁定硬约束：
 * 1. `bin/` 必须**整套**补齐（含 python3.14 / python3 / python / env）；
 * 2. 只补不覆盖，真实 Termux 安装的文件永远优先；
 * 3. 就绪以实测标记为准，旧的自有悬空 sh 可自愈，其余目标不动；
 * 4. 旧 `envpack` / 旧 env 版本被删除后，指向它们的自有悬空软链要重建，
 *    否则会被 `[ -L ]` 永久保留，脚本继续命中已删除的库与工具。
 */
class TermuxPrefixContractTest {

    private fun src(rel: String): String =
        java.io.File("src/main/java/com/mixradio/droid/$rel").readText()

    private val prefixSrc = src("data/TermuxPrefix.kt")

    /** 必须遍历内置 bin 目录补齐入口，而不是只建 bin/bash。 */
    @Test
    fun mustLinkWholeBinDirectory() {
        assertTrue("需取内置 bin 目录", prefixSrc.contains("EmbeddedEnv.binDir"))
        assertTrue("需遍历内置 bin 建链到前缀 bin", prefixSrc.contains("P/bin/"))
        assertTrue("bash 入口仍需保留", prefixSrc.contains("P/bin/bash"))
    }

    /** 补链前先判存在（含悬空软链），且 ln 不得带 -f。 */
    @Test
    fun linksMustNeverOverwriteExistingFiles() {
        assertTrue("建链前需判目标已存在", prefixSrc.contains("[ -e \$link ]"))
        assertTrue("需识别悬空软链，避免重复建链", prefixSrc.contains("|| [ -L "))
        assertFalse("禁止强制覆盖（-f）真实 Termux 文件", prefixSrc.contains("ln -sf"))
    }

    /**
     * 旧版残留迁移：envpack / 旧 env 版本被删除后，前缀里指向它们的软链必须重建。
     *
     * `linkIfAbsent` 把悬空链视同「已存在」，只判存在不清理就会**永久保留**，
     * 脚本继续命中已删除的库/工具（表现为「装了却跑不起来」）。
     * 规则：先清「悬空 + 目标命中本应用自有目录」的自有链，再补建当前 env 目标。
     */
    @Test
    fun ownedDanglingLinksAreReplacedByCurrentEnv() {
        assertTrue("需识别悬空软链", prefixSrc.contains("if [ -L \$link ] && [ ! -e \$link ]"))
        assertTrue("需读取悬空链目标", prefixSrc.contains("readlink \$link"))
        assertTrue(
            "清理必须限定在自有目录模式内",
            prefixSrc.contains("case \\\"\\\$t\\\" in \$owned) rm -f \$link")
        )
        assertTrue("旧扩展包目录需纳入清理范围", prefixSrc.contains("LEGACY_ENV_DIR = \"envpack\""))
        assertTrue(
            "升级残留目录（env.old / env.tmp）也要覆盖",
            prefixSrc.contains(".old\"") && prefixSrc.contains(".tmp\"")
        )
        assertTrue("清理后需补建当前 env 目标", prefixSrc.contains("linkIfAbsent(link, target)"))
        val purge = prefixSrc.indexOf("rm -f \$link")
        val relink = prefixSrc.indexOf("linkIfAbsent(link, target)")
        assertTrue("清理必须先于补建", purge in 0 until relink)
    }

    /** bin 全量补链必须跳过 shell 入口，否则会先把 env/bin/ash、hush 补回前缀。 */
    @Test
    fun binLoopMustSkipShellEntries() {
        val skipSnippet =
            ".append(\"case \\\" \").append(SHELL_NAMES).append(\" \\\" in *\\\" \\\$n \\\"*) continue ;; esac; \")"
        assertTrue("bin 循环需按 SHELL_NAMES 跳过 shell 入口", prefixSrc.contains(skipSnippet))
        val skip = prefixSrc.indexOf(skipSnippet)
        val link = prefixSrc.indexOf("ensureLink(", skip)
        assertTrue("跳过必须在 bin 补链之前", skip > 0 && link > skip)
        assertTrue(
            "跳过需发生在 bin 循环内",
            prefixSrc.lastIndexOf("EmbeddedEnv.binDir", skip) in 0 until skip
        )
    }

    /**
     * 自有 shell 软链不论是否悬空都要清理。
     *
     * 真机回归：`$PREFIX/bin/ash`、`hush` 指向 `.../files/env/bin/ash`（目标存在、链不悬空），
     * 只判悬空的迁移会全部漏掉，脚本继续命中会 SIGSEGV 的 busybox shell applet。
     */
    @Test
    fun nonDanglingAppOwnedShellLinksAreReplaced() {
        assertTrue(
            "只要是指向自有目录的软链就清理",
            prefixSrc.contains("if [ -L \\\"\\\$P/bin/\\\$n\\\" ]; then")
        )
        assertFalse(
            "清理条件不得要求悬空",
            prefixSrc.contains("if [ -L \\\"\\\$P/bin/\\\$n\\\" ] && [ ! -e")
        )
        assertTrue(
            "清理后补系统 sh",
            prefixSrc.contains(
                "if [ ! -e \\\"\\\$P/bin/\\\$n\\\" ] && [ ! -L \\\"\\\$P/bin/\\\$n\\\" ] && [ -x "
            )
        )
    }

    /** 自有目录范围必须含路径别名，且每个匹配模式都经 ShellEscapes 转义。 */
    @Test
    fun ownedRootsCoverAliasesAndEscapeArgs() {
        assertTrue("需按实际 env 目录派生范围", prefixSrc.contains("ownedRoots(context)"))
        assertTrue(
            "每个前缀都要转义并追加通配",
            prefixSrc.contains("ShellEscapes.escapeShellArg(it) + \"*\"")
        )
        assertTrue("多前缀需连成 case 多模式", prefixSrc.contains("joinToString(\"|\")"))
        assertTrue(
            "同一目录的 /data/data 与 /data/user 写法都要覆盖",
            prefixSrc.contains("\"/data/data/\"") && prefixSrc.contains("\"/data/user/\"")
        )
        assertTrue("shell 入口兜底需复用同一范围", prefixSrc.contains(".append(owned)"))
    }

    /** 归档无 shell 入口：前缀缺 sh/ash/hush/mash 时补系统 shell。 */
    @Test
    fun missingShellEntriesFallBackToSystemShell() {
        assertTrue("需识别 /system/bin/sh", prefixSrc.contains("/system/bin/sh"))
        assertTrue("需判前缀是否已有入口", prefixSrc.contains("if [ ! -e "))
        assertTrue("需判前缀已有悬空软链", prefixSrc.contains("&& [ ! -L "))
        assertTrue("系统 sh 需存在才建链", prefixSrc.contains("[ -x "))
        assertTrue(
            "需覆盖全部 shell 入口名（sh/ash/hush/mash）",
            prefixSrc.contains("sh ash hush mash")
        )
    }

    /**
     * 旧设备升级：前缀 shell 入口曾链到本应用 env（applet 链或版本重建后的悬空链）。
     *
     * 必须清掉这类本应用自有链再重链系统 sh；真实 Termux 文件与外部目标受保护。
     */
    @Test
    fun appOwnedShellLinksAreReplacedBySystemShell() {
        assertTrue("需读取入口软链的目标", prefixSrc.contains("readlink"))
        assertTrue("需按软链判定", prefixSrc.contains("if [ -L \\\"\\\$P/bin/\\\$n\\\" ]"))
        assertTrue("需清除旧链", prefixSrc.contains("rm -f \\\"\\\$P/bin/\\\$n\\\""))
        assertTrue("清理范围需按前缀匹配限定", prefixSrc.contains("case \\\"\\\$t\\\" in "))
        assertTrue("需限定在本应用 env 目录内", prefixSrc.contains("ownedRoots(context)"))
        assertTrue(
            "同一目录的 /data/data 与 /data/user 写法都要覆盖",
            prefixSrc.contains("\"/data/data/\"") && prefixSrc.contains("\"/data/user/\"")
        )
        assertTrue("清理后仍需系统 sh 兜底", prefixSrc.contains(".append(SYSTEM_SH)"))
    }

    /**
     * 就绪不能取脚本最后一条命令的退出码：前面每条都吞了 stderr，
     * 半套前缀也会返回 0。必须显式标记 + 实测 bash/sh 能起来。
     */
    @Test
    fun readinessNeedsMarkerAndRealExecution() {
        assertTrue("需显式就绪标记", prefixSrc.contains("READY_MARKER"))
        assertTrue(
            "需实测内置 bash 可执行",
            prefixSrc.contains("\\\"\\\$P/bin/bash\\\" -c 'exit 0'")
        )
        assertTrue(
            "需实测兜底 sh 可执行",
            prefixSrc.contains("\\\"\\\$P/bin/sh\\\" -c 'exit 0'")
        )
        assertTrue("按标记判定就绪", prefixSrc.contains("out.contains(READY_MARKER)"))
    }

    /** lib 只能链文件与指向非目录的软链：把目录（含 terminfo 等目录软链）链进前缀会顶掉真实 Termux 目录。 */
    @Test
    fun libraryLinksSkipDirectories() {
        assertTrue(
            "lib 循环需跳过目录与指向目录的软链",
            prefixSrc.contains(
                "[ -f \\\"\\\$f\\\" ] || { [ -L \\\"\\\$f\\\" ] && [ ! -d \\\"\\\$f\\\" ]; } || continue"
            )
        )
        assertTrue("需显式排除目录（含目录软链）", prefixSrc.contains("[ ! -d \\\"\\\$f\\\" ]"))
    }

    /**
     * root 状态陈旧（false/null）不得终止建链：限流重试，就绪仍由真实 su 判定。
     *
     * `hasRootAccess()` 超时/授权窗未回都返回 false，若把 false 当终局，
     * 用户授权后前缀永远建不出来（本 lane 的线上根因）。
     */
    @Test
    fun staleRootStateStillRetriesWithThrottle() {
        assertFalse(
            "false 不得作为终局直接返回",
            prefixSrc.contains("if (rootState == false) return false")
        )
        assertTrue("false 与 null 统一走限流", prefixSrc.contains("if (rootState != true)"))
        assertTrue(
            "未授权重试间隔 60s",
            prefixSrc.contains("DENIED_ROOT_RETRY_MS = 60_000L")
        )
        assertTrue(
            "未确认/失败单次预算 15s",
            prefixSrc.contains("UNCONFIRMED_ROOT_BUDGET_MS = 15_000L")
        )
        assertTrue(
            "true 才用满 60s 建链预算",
            prefixSrc.contains("if (rootState == true) BUILD_TIMEOUT_MS else UNCONFIRMED_ROOT_BUDGET_MS")
        )
        assertTrue("统一 lastAttemptAt 记录尝试时间", prefixSrc.contains("lastAttemptAt"))
        val gate = prefixSrc.indexOf("if (now - lastAttemptAt < retryWindow) return false")
        val sync = prefixSrc.indexOf("runCommandSync")
        assertTrue("限流判定必须在同步执行之前", gate in 0 until sync)
        assertFalse("不得在此处自行异步补建", prefixSrc.contains("Thread {"))
    }

    /**
     * 内置 python / git / openssl 的资源目录按编译期前缀定位，由前缀补缺失项，
     * 因此**无需**导出 PYTHONHOME / PYTHONPATH / GIT_EXEC_PATH / GIT_TEMPLATE_DIR /
     * OPENSSL_CONF（导出会劫持真实 Termux 的同名工具）。
     */
    @Test
    fun runtimeDirsAreLinkedInsteadOfExported() {
        listOf("libexec/git-core", "share/git-core", "etc/tls/openssl.cnf").forEach {
            assertTrue("需补链 $it", prefixSrc.contains(it))
        }
        assertTrue("python 标准库目录需按版本补链", prefixSrc.contains("startsWith(\"python3.\")"))
        assertTrue("补链需只补缺失（真实 Termux 优先）", prefixSrc.contains("linkIfAbsent"))
        assertFalse("不应导出运行时变量", prefixSrc.contains("export PYTHON"))
        assertFalse("不应导出 git 变量", prefixSrc.contains("export GIT_"))
        assertFalse("不应导出 openssl 变量", prefixSrc.contains("export OPENSSL"))
    }

    /**
     * 单段命令必须有硬字节上限。
     *
     * 真机实测：本机 `su -c` 约 4096 字节封顶，3629 字节正常、5429 字节即 rc=255 且零输出，
     * 整段被丢弃。拼成一条 6KB 命令时建链恒失败，`ready` 永远为 false。
     */
    @Test
    fun segmentByteCapIsDeclaredAndBounded() {
        val cap = Regex("SEGMENT_MAX_BYTES\\s*=\\s*([0-9_]+)")
            .find(prefixSrc)?.groupValues?.get(1)?.replace("_", "")?.toIntOrNull()
        assertTrue("需声明单段字节上限常量", cap != null)
        assertTrue("单段上限必须 ≤3000 字节，给 su -c 的 4096 留余量", cap!! <= 3000)
        assertTrue("装箱必须按该常量限长", prefixSrc.contains("SEGMENT_MAX_BYTES - header"))
        assertFalse("不得再拼一条超长命令交给 su", prefixSrc.contains("sb.append(READY_MARKER)"))
    }

    /** 建链脚本必须是分段列表顺序执行，而不是一条长命令。 */
    @Test
    fun scriptIsSplitIntoSegmentsRunInOrder() {
        assertTrue(
            "需返回分段列表",
            prefixSrc.contains("private fun buildScriptSegments(context: Context): List<String>")
        )
        assertTrue("需按顺序逐段执行", prefixSrc.contains("for ((index, segment) in segments.withIndex())"))
        assertTrue("每段单独走 runCommandSync", prefixSrc.contains("runCommandSync(segment"))
        assertEquals(
            "只允许一个执行点：单条长命令正是 rc=255 的根因",
            1,
            Regex("runCommandSync\\(").findAll(prefixSrc).count()
        )
    }

    /** 段没跑完（su 拒绝 / 命令超限被丢弃）必须立即停止，后面的段不再执行。 */
    @Test
    fun segmentFailureStopsFollowingSegments() {
        val loop = prefixSrc.indexOf("for ((index, segment) in segments.withIndex())")
        val exec = prefixSrc.indexOf("runCommandSync(segment")
        val fail = prefixSrc.indexOf("if (!out.contains(SEGMENT_DONE_MARKER)) return false")
        assertTrue("执行需在顺序循环内", loop in 0 until exec)
        assertTrue("需在执行点之后判完成标记", exec in 0 until fail)
        assertTrue("失败判定需先于就绪判定", fail in 0 until prefixSrc.indexOf("out.contains(READY_MARKER)"))
        assertFalse("失败不得被吞掉后继续下一段", prefixSrc.contains("catch (e: Exception) {"))
        assertTrue("每段末尾必须无条件打印完成标记", prefixSrc.contains("segmentDoneSuffix()"))
    }

    /** READY_MARKER 只能由最后一段打印，且该段最后追加。 */
    @Test
    fun readyMarkerOnlyAppearsInLastSegment() {
        val marker = "READY_MARKER"
        val body = prefixSrc.substringAfter("private fun buildScriptSegments")
        val lastStage = body.indexOf("packSegments(runtimePieces)")
        val readyStage = body.indexOf("withHeader(readySegment())")
        assertTrue("运行时资源段需先于就绪段追加", lastStage in 0 until readyStage)
        assertTrue("需追加就绪段", readyStage > 0)
        assertTrue(
            "就绪段必须是最后追加的那一段",
            prefixSrc.contains("segments += withHeader(readySegment())")
        )
        assertTrue("READY_MARKER 只能出现在就绪段", body.indexOf(marker) > readyStage)
        assertFalse(
            "就绪段之前的片段不得打印标记",
            body.substring(0, readyStage).contains(marker)
        )
        assertTrue(
            "就绪段需实测内置 bash 可执行",
            prefixSrc.contains("\\\"\\\$P/bin/bash\\\" -c 'exit 0'")
        )
    }
}
