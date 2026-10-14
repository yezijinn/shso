// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import android.content.Context
import java.io.File

/**
 * 让内置动态二进制（bash / curl / zip / 扩展包工具）在**不导出 `LD_LIBRARY_PATH`** 的前提下找到依赖库。
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
 * 只创建不存在的条目（不覆盖真实 Termux 安装的文件）。
 *
 * 无 root 时无法创建该前缀，调用方需退回导出 `LD_LIBRARY_PATH` 的旧方式（见 [ready]）。
 */
internal object TermuxPrefix {

    private const val PREFIX = "/data/data/com.termux/files/usr"

    /** 前缀库是否已就绪（就绪后调用方**不要**再导出 LD_LIBRARY_PATH）。 */
    @Volatile
    var ready: Boolean = false
        private set

    /**
     * 建立/补齐前缀符号链接。幂等；无 root 或库目录缺失时返回 false。
     */
    fun ensure(context: Context): Boolean {
        if (ready) return true
        // 仅在**明确无 root** 时放弃；root 状态尚未探测完（null，如冷启动首次执行）仍尝试建立，
        // 否则首跑会退回导出 LD_LIBRARY_PATH 的旧路径，脚本里的原生程序会 CANNOT LINK。
        if (RootService.isRootGranted == false) return false
        synchronized(this) {
            if (ready) return true
            val libDirs = buildList {
                EmbeddedEnv.libDir?.let { add(it) }
                EnvPack.libDir?.let { add(it) }
            }
            if (libDirs.isEmpty()) return false
            val bash = EmbeddedEnv.bashPath

            val sb = StringBuilder()
            sb.append("P=").append(escape(PREFIX)).append("; ")
            sb.append("mkdir -p \"\$P/lib\" \"\$P/bin\" 2>/dev/null; ")
            for (dir in libDirs) {
                sb.append("for f in ").append(escape(dir)).append("/*; do ")
                sb.append("[ -e \"\$f\" ] || continue; n=\${f##*/}; ")
                sb.append("[ -e \"\$P/lib/\$n\" ] || ln -s \"\$f\" \"\$P/lib/\$n\" 2>/dev/null; done; ")
            }
            if (bash != null) {
                sb.append("[ -e \"\$P/bin/bash\" ] || ln -s ").append(escape(bash))
                    .append(" \"\$P/bin/bash\" 2>/dev/null; ")
            }
            val (code, _) = RootService.runCommandSync(sb.toString(), timeoutMs = 20_000L)
            ready = code == 0
            return ready
        }
    }

    private fun escape(s: String): String =
        if (s.matches(Regex("^[a-zA-Z0-9_./\\-:]+$"))) s else "'" + s.replace("'", "'\\''") + "'"
}
