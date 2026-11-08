// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import android.content.Context
import com.mixradio.droid.data.security.ShellEscapes
import java.io.File

/**
 * 让内置动态二进制（bash / curl / zip 等工具）在**不导出 `LD_LIBRARY_PATH`** 的前提下找到依赖库。
 *
 * ## 为什么不能导出 LD_LIBRARY_PATH
 *
 * 内置工具（Termux 构建）的 `RUNPATH` 指向 `/data/data/com.termux/files/usr/lib`（本机通常不存在），
 * 因此需要库搜索路径。但把它塞进 `LD_LIBRARY_PATH` 会**污染所有子进程**：
 * 动态加载器会优先用内置的 `libz` / `libbz2` 等去满足**系统**库（如 `libunwindstack.so`）的依赖，
 * 版本/符号不匹配时直接 `CANNOT LINK EXECUTABLE`，被测脚本里的原生程序（自解压载荷等）根本起不来。
 *
 * ## 做法
 *
 * 用 root 在 Termux 前缀下建立 `lib/` 与 `bin/` 目录，把内置各库**符号链接**进去：
 * 内置二进制的 `RUNPATH` 便能自然解析，**无需环境变量**，也就不再影响其它程序。
 *
 * 只链接 `lib/` 不够：归档内脚本的 shebang 是绝对路径
 * `#!/data/data/com.termux/files/usr/bin/python3.14`（也有 `/usr/bin/env`、`/usr/bin/sh`），
 * 内核按字面路径执行，**根本不看 `PATH`**。因此 `bin/` 必须补齐整套入口，
 * 与 `lib/` 同样只补不覆盖 —— 真实 Termux 安装的文件永远优先。
 *
 * 运行时资源目录（内置 python 的 `lib/python3.x`、git 的 `libexec/git-core` 与
 * `share/git-core`、openssl 的 `etc/tls/openssl.cnf`）同样按**编译期前缀**定位，
 * 这里只补缺失项，于是内置工具无需 `PYTHONHOME` / `PYTHONPATH` / `GIT_EXEC_PATH` /
 * `GIT_TEMPLATE_DIR` / `OPENSSL_CONF` 也能工作，装了真实 Termux 的同名工具也不被劫持。
 *
 * `env` 内置归档不含 `bin/sh`，内置 busybox 的 shell applet 直接执行会崩（SIGSEGV），
 * 因此 applet 链接也不再生成 `sh` / `ash` / `hush` / `mash`；前缀缺这些入口时用
 * `/system/bin/sh`（mksh，静态可用）兜底。
 *
 * ## 旧版残留迁移
 *
 * 旧版可下载扩展包（`envpack`）与旧 env 版本目录会被删除，但前缀里留下的软链仍在：
 * `[ -e ]` 为假、`[ -L ]` 为真，若只判「存在则跳过」就会**永久保留**指向已删除目录的
 * 悬空链，脚本继续命中不存在的库/工具，表现为「装了却跑不起来」。
 * 这里只清理**悬空且目标明确落在本应用自有目录**（当前 env / `env.old` / `env.tmp` /
 * 旧 `envpack`，含 `/data/data` 与 `/data/user` 两种写法）的自有软链，再补建当前 env 目标；
 * 真实 Termux 文件、真实 Termux 自建软链与外部目标一律不动。
 *
 * 无 root 时无法创建该前缀，调用方需退回导出 `LD_LIBRARY_PATH` 的旧方式（见 [ready]）。
 */
internal object TermuxPrefix {

    private const val PREFIX = "/data/data/com.termux/files/usr"

    /** 旧版「扩展环境包」目录名：其内容已并入内置资产。 */
    private const val LEGACY_ENV_DIR = "envpack"

    /** 系统 shell：仅在前缀缺 shell 入口时补位，供 `#!/.../bin/sh` 脚本兜底。 */
    private const val SYSTEM_SH = "/system/bin/sh"

    /**
     * 需要兜底的 shell 入口名。
     *
     * 内置 busybox 的这些 applet 直接执行即崩，绝不能把前缀同名入口链到 env；
     * 缺位时统一指向 [SYSTEM_SH]。
     */
    private const val SHELL_NAMES = "sh ash hush mash"

    /** 建链超时：首跑要建几十个软链并实测 bash/sh，慢存储上 20s 会把成功误判为失败。 */
    private const val BUILD_TIMEOUT_MS = 60_000L

    /**
     * 单段建链命令的字节上限（留足余量，只用 3000）。
     *
     * 本机 `su -c` 对单条命令约 4096 字节封顶，超限时**整段被丢弃**：
     * 实测 3629 字节正常、5429 字节即 rc=255 且零输出。因此建链脚本必须按语义分段执行，
     * 不能拼成一条 6KB 命令。
     */
    private const val SEGMENT_MAX_BYTES = 3000

    /** root 状态未确认（冷启动授权未回）或探测失败时的单次预算：够 su 回话又不拖死命令。 */
    private const val UNCONFIRMED_ROOT_BUDGET_MS = 15_000L

    /** 状态未定（null）时两次尝试的最小间隔。 */
    private const val UNKNOWN_ROOT_RETRY_MS = 20_000L

    /** 探测返回未授权（false）时的重试间隔：可能是超时/授权窗未回，不能当终局。 */
    private const val DENIED_ROOT_RETRY_MS = 60_000L

    /** 就绪标记：只在关键入口实测通过时由脚本打印，不靠最后一条 if 的退出码。 */
    private const val READY_MARKER = "SHSO_TERMUX_PREFIX_OK"

    /**
     * 分段执行完成标记：每段末尾无条件打印。
     *
     * 只凭 rc 判断会误伤 —— 段内最后一条 `ln -s` 在「链已存在」时返回非 0，
     * 而整段其实已经跑完；反过来 `su` 因命令超长整段丢弃时 rc 也是非 0 且零输出。
     * 用「是否打印完成标记」区分「段跑完了」与「段根本没执行」。
     */
    private const val SEGMENT_DONE_MARKER = "SHSO_PREFIX_SEG_DONE"

    /** 前缀库是否已就绪（就绪后调用方**不要**再导出 LD_LIBRARY_PATH）。 */
    @Volatile
    var ready: Boolean = false
        private set

    /** 上次尝试建前缀的时间（所有 root 状态共用），用于限流重试。 */
    @Volatile
    private var lastAttemptAt: Long = 0L

    /**
     * 建立/补齐前缀符号链接。幂等；root 状态未定时限流重试，不在调用线程长等授权。
     */
    fun ensure(context: Context): Boolean {
        if (ready) return true
        // `false` **不是终局**：冷启动/授权窗未回时探测超时也返回 false，若照此永久放弃，
        // 用户授权后（或换一条命令时）前缀永远建不出来，只剩 LD_LIBRARY_PATH 兜底。
        // false 与 null 一律限流重试：null 用短间隔，false 用长间隔，单次预算统一 15s；
        // 只有已确认 root 才用满 60s。就绪与否仍由真实 su + READY_MARKER 判定。
        val rootState = RootService.isRootGranted
        if (rootState != true) {
            val now = System.currentTimeMillis()
            val retryWindow = if (rootState == false) DENIED_ROOT_RETRY_MS else UNKNOWN_ROOT_RETRY_MS
            if (now - lastAttemptAt < retryWindow) return false
            lastAttemptAt = now
        }
        val timeoutMs = if (rootState == true) BUILD_TIMEOUT_MS else UNCONFIRMED_ROOT_BUDGET_MS
        synchronized(this) {
            if (ready) return true
            val segments = buildScriptSegments(context)
            if (segments.isEmpty()) return false
            ready = runSegments(segments, timeoutMs)
            return ready
        }
    }

    /**
     * 按语义切分建链脚本，顺序固定：库链接 → bin 链接 → shell 入口清理与系统 sh 兜底 →
     * 运行时资源 → 就绪探测。
     *
     * 每段单独走一次 `su -c`，因此每段都自带 `P=` 定义（不同进程不共享变量），
     * 且单段不超过 [SEGMENT_MAX_BYTES] 字节。
     */
    private fun buildScriptSegments(context: Context): List<String> {
        val libDirs = buildList {
            EmbeddedEnv.libDir?.let { add(it) }
        }
        if (libDirs.isEmpty()) return emptyList()
        val bash = EmbeddedEnv.bashPath
        val binDir = EmbeddedEnv.binDir
        // 可清理/重链的自有目标范围（当前 env、升级残留、旧 envpack）
        val owned = ownedCasePatterns(context)
        val segments = ArrayList<String>()

        val libPieces = ArrayList<String>()
        libPieces += "mkdir -p \"\$P/lib\" \"\$P/bin\" 2>/dev/null; "
        for (dir in libDirs) {
            val sb = StringBuilder()
            // 只链普通文件与指向非目录的软链：lib 下的目录（含 terminfo 这类目录软链）
            // 链进前缀会顶掉真实 Termux 目录；悬空软链仍按原策略处理。
            sb.append("for f in ").append(ShellEscapes.escapeShellArg(dir)).append("/*; do ")
            sb.append("[ -f \"\$f\" ] || { [ -L \"\$f\" ] && [ ! -d \"\$f\" ]; } || continue; n=\${f##*/}; ")
            sb.append(ensureLink("\"\$P/lib/\$n\"", "\"\$f\"", owned))
            sb.append("done; ")
            libPieces += sb.toString()
        }
        segments += packSegments(libPieces)

        // 完整命令入口：绝对 shebang 按字面路径执行，只有 $PREFIX/bin 有同名文件才找得到。
        // shell 入口（sh/ash/hush/mash）必须跳过：内置 busybox 的 shell 直接执行会崩，
        // 若先按 bin 全量补链补回 env/bin/ash，后面的清理循环只能再删一次，白折腾。
        val binPieces = ArrayList<String>()
        if (binDir != null) {
            val sb = StringBuilder()
            sb.append("for f in ").append(ShellEscapes.escapeShellArg(binDir)).append("/*; do ")
            sb.append("[ -e \"\$f\" ] || continue; n=\${f##*/}; ")
            sb.append("case \" ").append(SHELL_NAMES).append(" \" in *\" \$n \"*) continue ;; esac; ")
            sb.append(ensureLink("\"\$P/bin/\$n\"", "\"\$f\"", owned))
            sb.append("done; ")
            binPieces += sb.toString()
        }
        if (bash != null) {
            binPieces += ensureLink("\"\$P/bin/bash\"", ShellEscapes.escapeShellArg(bash), owned)
        }
        segments += packSegments(binPieces)

        // 内置环境不提供任何 shell 入口：前缀里指向本应用的 sh/ash/hush/mash 软链
        // （旧版 applet 链、旧 envpack 链、以及 env 版本重建后的链）**不论是否悬空**都先清掉：
        // env/bin/ash 这类目标在升级后仍然存在，只判悬空会漏掉，脚本照样命中崩溃 applet。
        // 目标落在本应用自有目录内才清，真实 Termux 文件/软链与外部目标一律不动。
        val shellPieces = ArrayList<String>()
        val purge = StringBuilder()
        purge.append("for n in ").append(SHELL_NAMES).append("; do ")
        purge.append("if [ -L \"\$P/bin/\$n\" ]; then t=\$(readlink \"\$P/bin/\$n\" 2>/dev/null); ")
        purge.append("case \"\$t\" in ").append(owned).append(") rm -f \"\$P/bin/\$n\" ;; esac; ")
        purge.append("fi; done; ")
        shellPieces += purge.toString()
        val fallback = StringBuilder()
        fallback.append("for n in ").append(SHELL_NAMES).append("; do ")
        fallback.append("if [ ! -e \"\$P/bin/\$n\" ] && [ ! -L \"\$P/bin/\$n\" ] && [ -x ")
            .append(SYSTEM_SH).append(" ]; then ln -s ").append(SYSTEM_SH)
            .append(" \"\$P/bin/\$n\" 2>/dev/null; fi; done; ")
        shellPieces += fallback.toString()
        segments += packSegments(shellPieces)

        // 运行时资源目录：内置 python / git / openssl 按编译期前缀找资源，不需要环境变量。
        // 只补缺失项，真实 Termux 的 python3.x、git-core、openssl.cnf 永远优先。
        val runtimePieces = ArrayList<String>()
        for ((rel, target) in runtimeLinks()) {
            val sb = StringBuilder()
            val parent = rel.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                sb.append("mkdir -p \"\$P/").append(parent).append("\" 2>/dev/null; ")
            }
            sb.append(ensureLink("\"\$P/$rel\"", ShellEscapes.escapeShellArg(target), owned))
            runtimePieces += sb.toString()
        }
        segments += packSegments(runtimePieces)

        // 就绪段必须最后：只有它打印就绪标记。
        segments += withHeader(readySegment())
        return segments
    }

    /**
     * 顺序执行各段：任一段 rc != 0 立即停止并返回 false，后面的段不再执行。
     *
     * 预算按剩余量均分，因此总耗时不会超过调用方给的 [timeoutMs]。
     */
    private fun runSegments(segments: List<String>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        for ((index, segment) in segments.withIndex()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return false
            val budget = (remaining / (segments.size - index)).coerceAtLeast(1L)
            val (_, out) = RootService.runCommandSync(segment, timeoutMs = budget)
            // 没打完成标记 = 这一段根本没跑完（su 拒绝 / 命令超限被丢弃），后续段没有意义。
            if (!out.contains(SEGMENT_DONE_MARKER)) return false
            if (out.contains(READY_MARKER)) return true
        }
        return false
    }

    /** 每段都是独立的 `su -c` 进程：变量不跨段，必须各自定义前缀。 */
    private fun segmentHeader(): String = "P=" + ShellEscapes.escapeShellArg(PREFIX) + "; "

    private fun withHeader(script: String): String = segmentHeader() + script + segmentDoneSuffix()

    /** 每段末尾无条件打印完成标记，见 [SEGMENT_DONE_MARKER]。用换行而非 `;`，避免出现空命令。 */
    private fun segmentDoneSuffix(): String = "\necho " + SEGMENT_DONE_MARKER + "\n"

    /**
     * 把同一语义阶段的片段按顺序装箱，单段不超过 [SEGMENT_MAX_BYTES] 字节。
     *
     * `su -c` 对单条命令约 4096 字节封顶，超限时整段被丢弃（rc=255、零输出），
     * 所以宁可多跑一次 su，也不能拼出超长命令。
     */
    private fun packSegments(pieces: List<String>): List<String> {
        val header = segmentHeader()
        val suffix = segmentDoneSuffix()
        val limit = SEGMENT_MAX_BYTES - header.toByteArray(Charsets.UTF_8).size -
            suffix.toByteArray(Charsets.UTF_8).size
        val out = ArrayList<String>()
        val current = StringBuilder()
        var bytes = 0
        for (piece in pieces) {
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (current.isNotEmpty() && bytes + pieceBytes > limit) {
                out += withHeader(current.toString())
                current.setLength(0)
                bytes = 0
            }
            current.append(piece)
            bytes += pieceBytes
        }
        if (current.isNotEmpty()) out += withHeader(current.toString())
        return out
    }

    /**
     * 就绪探测段：实测 bash 与 sh 都能起来才打标记。
     *
     * 就绪不由「最后一条命令返回 0」推断（各段都带 2>/dev/null，失败被吞），
     * 否则半套前缀会被当成可用。
     */
    private fun readySegment(): String =
        "if [ -x \"\$P/bin/bash\" ] && [ -e \"\$P/bin/sh\" ] && " +
            "\"\$P/bin/bash\" -c 'exit 0' >/dev/null 2>&1 && " +
            "\"\$P/bin/sh\" -c 'exit 0' >/dev/null 2>&1; then echo " +
            READY_MARKER + "; fi"

    /**
     * 需要补链的运行时资源（前缀内相对路径 to 内置绝对路径）。
     *
     * 按「内置存在才补」生成，调用方只在前缀缺该项时建链。
     */
    private fun runtimeLinks(): List<Pair<String, String>> {
        val root = EmbeddedEnv.libDir?.let { File(it).parentFile } ?: return emptyList()
        val links = ArrayList<Pair<String, String>>()
        // python 标准库：CPython 按 `lib/python3.x/os.py` 这个 landmark 找 prefix
        File(root, "lib").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("python3.") }
            ?.sortedBy { it.name }
            ?.forEach { links += "lib/${it.name}" to it.absolutePath }
        // git 的 exec-path 与模板目录在编译期固定为 $PREFIX/libexec、$PREFIX/share
        listOf("libexec/git-core", "share/git-core").forEach { rel ->
            val dir = File(root, rel)
            if (dir.isDirectory) links += rel to dir.absolutePath
        }
        val cnf = File(root, "etc/tls/openssl.cnf")
        if (cnf.isFile) links += "etc/tls/openssl.cnf" to cnf.absolutePath
        return links
    }

    /**
     * 「不存在才建链」片段：`ln -s` 不带 `-f`，且先判存在（含悬空软链），
     * 因此真实 Termux 安装的文件绝不会被覆盖。幂等，可重复执行。
     */
    private fun linkIfAbsent(link: String, target: String): String =
        "[ -e $link ] || [ -L $link ] || ln -s $target $link 2>/dev/null; "

    /**
     * 「自有悬空链先清理，再按需建链」片段。
     *
     * `linkIfAbsent` 单独用会把**悬空软链**当成「已存在」永久保留：旧版扩展包（`envpack`）
     * 或旧 env 版本目录被删除后，前缀里指向它们的链全部悬空，脚本继续拿到 127 / 缺库。
     * 这里只在 `[ -L ] && [ ! -e ]`（悬空）**且**目标命中 [ownedRoots] 前缀时删除，
     * 然后重建指向当前 env 的链。真实 Termux 文件、真实 Termux 自建链（目标在
     * Termux 前缀内）与任何外部目标都不满足清理条件，保持原样。
     */
    private fun ensureLink(link: String, target: String, owned: String): String =
        "if [ -L $link ] && [ ! -e $link ]; then t=\$(readlink $link 2>/dev/null); " +
            "case \"\$t\" in $owned) rm -f $link ;; esac; fi; " +
            linkIfAbsent(link, target)

    /** 自有目录的 `case` 匹配模式串（每个前缀各带通配符，逐个走 ShellEscapes）。 */
    private fun ownedCasePatterns(context: Context): String =
        ownedRoots(context).joinToString("|") { ShellEscapes.escapeShellArg(it) + "*" }

    /**
     * 本应用自有的环境目录（尾部带 `/`）及其路径别名。
     *
     * 覆盖当前 env 根、升级备份/暂存目录（`env.old` / `env.tmp`）与旧扩展包目录
     * （`envpack`）：这些都是本应用写入后可能被删除的目录，指向它们的软链属自有残留。
     * 同一个目录有 `/data/data/<pkg>` 与 `/data/user/<uid>/<pkg>` 两种写法，
     * 旧版建链两种都可能留下，故一并列举。
     */
    private fun ownedRoots(context: Context): List<String> {
        val files = context.filesDir.absolutePath.trimEnd('/')
        val envDir = EmbeddedEnv.binDir?.let { File(it).parentFile?.absolutePath }
            ?: "$files/env"
        val envName = File(envDir).name
        val aliasFiles = when {
            files.startsWith("/data/data/") ->
                "/data/user/0/" + files.removePrefix("/data/data/")
            files.startsWith("/data/user/") -> {
                val rest = files.removePrefix("/data/user/")
                "/data/data/" + rest.substringAfter('/', rest)
            }
            else -> null
        }
        val roots = LinkedHashSet<String>()
        // 当前 env：沿用实际安装目录（可能与默认名不同）
        roots += envDir.trimEnd('/') + "/"
        aliasFiles?.let { roots += File(it, envName).absolutePath.trimEnd('/') + "/" }
        // 升级残留与旧扩展包：目录名固定由 env 名派生
        for (name in listOf("$envName.old", "$envName.tmp", LEGACY_ENV_DIR)) {
            roots += "$files/$name/"
            aliasFiles?.let { roots += File(it, name).absolutePath.trimEnd('/') + "/" }
        }
        return roots.toList()
    }
}
