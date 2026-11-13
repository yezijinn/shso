// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.mixradio.droid.data.security.ShellEscapes
import java.io.File
import java.nio.file.Files

/** 移动文件时遇到目标同名项目时的处理策略 */
enum class MoveDestinationConflict {
    /** 覆盖替换：删除目标同名项后移动 */
    OVERWRITE,
    /** 自动改名：保留原目标，被移动文件改名为 name_new（保留扩展名前的原名） */
    RENAME,
    /** 同名不动：跳过该源，不移动此源（源留在原地） */
    SKIP
}

data class FilePermissionMetadata(
    val mode: String,
    val owner: String,
    val group: String,
)

/**
 * 判定 `readlink -f` 的完整解析结果 [resolved] 是否仍落在用户点选的 [path] 之内。
 *
 * 「之内」有两种正当形态，只看 [path] 会把第二种误判为越界：
 *  1. 最终分量本身是链接，指向自身子树（`p`、`p/x`），解析结果以 [path] 为前缀；
 *  2. 祖先目录含链接，例如 `/data/local/tmp/lnk/foo.sh` 中 `lnk → /data/adb/shso`，
 *     解析结果是 `/data/adb/shso/foo.sh`。用户选的仍是 /data 下的一个文件，属正常操作。
 *
 * [parentResolved] 为 [path] 父目录的解析结果，用于重建第 2 种形态的期望路径；
 * 不能改用「文件名相同」来放行——`/data/local/tmp/modules → /data/adb/modules`
 * 这类链接的文件名恰好相同，正是要拦的越界写法。
 */
internal fun isResolvedInsideSelected(resolved: String, path: String, parentResolved: String): Boolean {
    val selfBase = path.trimEnd('/')
    if (resolved == selfBase || resolved.startsWith("$selfBase/")) return true
    val baseName = selfBase.substringAfterLast('/')
    val parentBase = parentResolved.trimEnd('/')
    if (baseName.isEmpty()) return resolved == parentBase
    val expected = "$parentBase/$baseName"
    return resolved == expected || resolved.startsWith("$expected/")
}

object RootFileManager {

    const val DEFAULT_SHSO_DIR = "/data/adb/shso"

    private val PERMISSION_MODE_PATTERN = Regex("^[0-7]{3,4}$")

    /** 同名副本探测的次数上限：防止异常目录里 `exists()` 无限循环。 */
    private const val COPY_NAME_PROBE_LIMIT = 1000

    /**
     * 拷贝落盘后的权限掩码（八进制 0755）：保留 rwx（含执行位），
     * 清掉 group/other 写位与 setuid/setgid。与 shell 侧的 `chmod a-s,go-w` 同口径。
     */
    private const val COPY_MODE_MASK = 0b111101101
    // 首字符不得为连字符：owner/group 会原样进入 `chown` 的操作数位，
    // 前导 `-` 会被当选项（`-R` 递归、`--help` 提前退出），属 argument injection。
    private val OWNER_OR_GROUP_PATTERN = Regex("^[a-zA-Z0-9._][a-zA-Z0-9._-]*$")

    /**
     * 改权限 / 改属主 / 改用户组在无 ROOT 时的统一提示。
     *
     * 这三个操作只允许 `/data` 下的路径，应用 uid 对该树既无权限也受 SELinux 限制，
     * 无 ROOT 时执行必然失败。与其 fork 一个注定失败的 `su`（可能还弹出授权框、
     * 拿到的是「Permission denied」这种无信息量的文案），不如直接说清原因。
     */
    private const val NO_ROOT_NEEDED_FOR_ABSOLUTE_PATH = "修改 /data 下文件的权限/属主需要 ROOT 授权"

    /**
     * 进程内记忆的上次浏览目录（仅 AppSettings.rememberDirectory 开启时读写）。
     * 「文件」页与主页「从文件管理器选择」共用，APP 存活期间切页/重开选择器均保留该值；
     * 进程被杀后自动重置，符合「临时缓存」语义。
     */
    @Volatile
    var rememberedDirectory: String? = null

    /**
     * 「文件」页右列记忆的上次浏览目录。
     *
     * 双列布局下两列各自独立导航，各记各的落点。与 [rememberedDirectory]
     * 一样是**进程内**记忆（AppSettings.rememberDirectory 开启时读写），
     * 进程被杀后重置，符合「临时缓存」语义。
     */
    @Volatile
    var rememberedRightDirectory: String? = null

    // 目录只需建立一次，进程内去重，避免每次刷新/切目录都重复发起一次 su 调用
    @Volatile
    private var shsoDirEnsured = false

    // 冷启动时主页与文件页两列会同时调 [ensureShsoDir]：上面那个标记只保证可见性，
    // 「读到 false → 跑 su → 置位」三步之间可被另外两个协程穿过，各自再发一次 su。
    // su 在 Magisk 侧是串行资源，多出来的几次会排在同时发起的目录列举前面。
    // 这里把并发调用串行化，后到者拿到锁时标记通常已置位，直接返回。
    private val shsoDirLock = Mutex()

    /**
     * 让「shso 目录已建立」标记失效。
     *
     * 删除 / 改名 / 移走该目录后必须调用：标记一旦残留，[ensureShsoDir] 会直接返回成功
     * 而不再 mkdir，本进程内后续「添加文件到 shso」「解压到 shso」等入口全部失败，
     * 报错却是「目录不存在」，与「已确保存在」的契约自相矛盾。
     * 操作失败也同样清标记：代价只是下一次多跑一次幂等的 `mkdir -p && chmod`。
     */
    private fun invalidateShsoDirIfAffected(path: String) {
        if (path == DEFAULT_SHSO_DIR) shsoDirEnsured = false
    }

    /**
     * 是否「优先使用 ROOT」：仅当已确获 ROOT 授权时为 true。
     * 设计原则：普通用户本就能做的操作（浏览/读写/增删自己的存储）不强制依赖 ROOT；
     * 只有在授权了 ROOT 时才优先走 su 以获得更完整的路径访问能力，
     * 无 ROOT 或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
     */
    /**
     * 是否「优先使用 ROOT」：已确获 ROOT 授权时为 true。
     *
     * 设计原则：普通用户本就能做的操作（浏览/读写/增删自己的存储）不强制依赖 ROOT；
     * 只有在授权了 ROOT 时才优先走 su 以获得更完整的路径访问能力，
     * 无 ROOT 或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
     *
     * `null`（尚未探测）**不算**「无 ROOT」：探测在 MainActivity 里异步进行，
     * 与首帧列目录并发。若把 null 当 false，首帧就会跳过 su 直接走本地 File API，
     * 而此时「所有文件访问」可能刚授权尚未生效、或路径在应用无权访问的位置，
     * 于是拿到空列表/无权限 —— 用户看到「当前目录为空」，实际目录里有大量文件。
     * 真机实测：Debug 首启即进文件页，root=null 时 /storage/emulated/0 列出 0 项。
     * 因此 null 时先等待探测落定，由 [awaitRootState] 负责。
     *
     * 只读的 [listDirectory] 不适用这条：它拿不到条目时才等（本地能列出内容就直接返回），
     * 等探测会白白占住首帧。写操作没有「先用弱口径做一遍」的退路，必须等状态落定。
     */
    private suspend fun preferRoot(): Boolean {
        // 只需「等探测落定」，返回值本身不参与判定：
        // 等待超时（返回 false）时按当前已知状态处理，可能仍是 null → false。
        // 写成 if (awaitRootState()) return X / return X 两条相同分支是纯装饰。
        awaitRootState()
        return RootService.isRootGranted == true
    }

    /**
     * ROOT 状态尚未确定时最多等待 [ROOT_STATE_WAIT_MS]，让异步探测落定。
     *
     * 不无限等待：授权弹窗可能长时间挂起（用户没看见/没点），此时必须继续走本地兜底，
     * 否则文件页会一直停在骨架屏。超时后按当前已知状态（可能是 null）处理。
     */
    private suspend fun awaitRootState(): Boolean {
        if (RootService.isRootGranted != null) return true
        val deadline = SystemClock.elapsedRealtime() + ROOT_STATE_WAIT_MS
        while (RootService.isRootGranted == null) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return false
            delay(minOf(ROOT_STATE_POLL_MS, remaining))
        }
        return true
    }

    /**
     * ROOT 状态未落定时的最长等待。
     *
     * 3 秒是在「授权弹窗要用户点」与「页面不能一直卡在骨架屏」之间的折中。
     * [listDirectory] 只在本地拿不到条目时才走到这里（本地能列出内容就直接返回），
     * 因此读路径上的这个等待只在「目录确实读不出来」时才发生。
     */
    private const val ROOT_STATE_WAIT_MS = 3_000L
    private const val ROOT_STATE_POLL_MS = 50L

    /**
     * 目录列举单次 su 调用的超时。
     *
     * 列举正常在 1s 内返回（真机 188 条目 0.11s），15s 已极宽松；
     * 取更短会把「目录很大」「设备正忙」误判成 ROOT 失败。
     * 关键在于**必须显式给**：不传就走 [RootService.runCommandSync] 的 120s 默认值，
     * su 停在授权弹窗上时列表要卡满两分钟才回到本地兜底。
     */
    private const val LIST_TIMEOUT_MS = 15_000L

    /**
     * 路径级非法校验：拒绝空路径、反斜杠、NUL、换行、回车，以及含 `..` 的路径段（防路径穿越）。
     * 用于所有把路径拼进 shell 命令或 java.io.File 前的统一校验。
     */
    internal fun isUnsafePath(path: String): Boolean =
        path.isEmpty() ||
            path.contains("\\") ||
            path.contains("\n") ||
            path.contains("\r") ||
            path.contains("\u0000") ||
            path.split('/').any { it == ".." }

    /**
     * 文件名级非法校验：拒绝空名、路径分隔符（/ 与 \）、`..`、NUL、换行、回车，防止越目录写入或命令分隔。
     *
     * `..` 只在**整名等于**它时才非法：文件名不是路径，`a..b`、`v1..2` 都是合法名，
     * 旧写法 `contains("..")` 会把它们一并拒掉。
     */
    internal fun isUnsafeFileName(name: String): Boolean =
        name.isEmpty() ||
            name.contains("/") ||
            name.contains("\\") ||
            name == ".." ||
            name.contains("\n") ||
            name.contains("\r") ||
            name.contains("\u0000")

    /** 仅允许修改 /data 本身或其后代，避免权限操作越权到其他文件系统路径。 */
    internal fun isAllowedDataPath(path: String): Boolean =
        !isUnsafePath(path) && (path == "/data" || path.startsWith("/data/"))

    internal fun isValidPermissionMode(mode: String): Boolean =
        PERMISSION_MODE_PATTERN.matches(mode)

    internal fun isValidOwnerOrGroup(value: String): Boolean =
        OWNER_OR_GROUP_PATTERN.matches(value)

    internal fun permissionStringToOctal(permissionString: String): String? {
        if (permissionString.length != 10) return null
        if (permissionString[0] !in "-bcdlps") return null
        val expected = listOf(
            setOf('r', '-'), setOf('w', '-'), setOf('x', 's', 'S', '-'),
            setOf('r', '-'), setOf('w', '-'), setOf('x', 's', 'S', '-'),
            setOf('r', '-'), setOf('w', '-'), setOf('x', 't', 'T', '-')
        )
        if (permissionString.drop(1).toList().zip(expected).any { (value, allowed) -> value !in allowed }) return null
        val bits = permissionString.substring(1).mapIndexed { index, value ->
            when (index % 3) {
                0 -> if (value == 'r') 4 else 0
                1 -> if (value == 'w') 2 else 0
                else -> if (value == 'x' || value in "st") 1 else 0
            }
        }
        if (bits.size != 9) return null
        val specialBits = (if (permissionString[3] in "sS") 4 else 0) +
            (if (permissionString[6] in "sS") 2 else 0) +
            (if (permissionString[9] in "tT") 1 else 0)
        return (if (specialBits == 0) "" else specialBits.toString()) +
            bits.chunked(3).joinToString("") { it.sum().toString() }
    }

    suspend fun readPermissionMetadata(path: String): Pair<FilePermissionMetadata?, String> = withContext(Dispatchers.IO) {
        if (!isAllowedDataPath(path)) {
            return@withContext Pair(null, "仅允许读取 /data 下的路径")
        }
        if (!preferRoot()) {
            return@withContext Pair(null, "读取 /data 权限需要 ROOT")
        }

        val escapedPath = RootService.escapeShellArg(path)
        val (code, output) = RootService.runCommandSync("stat -c \"%A|%U|%G\" $escapedPath")
        if (code != 0) return@withContext Pair(null, "读取文件属性失败")

        val parts = output.trim().lineSequence().firstOrNull()?.split('|', limit = 3).orEmpty()
        if (parts.size != 3) return@withContext Pair(null, "读取文件属性失败")
        val mode = permissionStringToOctal(parts[0])
        if (mode == null || !isValidOwnerOrGroup(parts[1]) || !isValidOwnerOrGroup(parts[2])) {
            return@withContext Pair(null, "读取文件属性失败")
        }
        Pair(FilePermissionMetadata(mode, parts[1], parts[2]), "")
    }

    /**
     * 校验「可改权限/属主的真实目标」。
     *
     * `chmod` / `chown` / `chgrp` **默认跟随符号链接**，而 [isAllowedDataPath]
     * 校验的是链接自身路径 —— 于是 `/data/local/tmp/m`（→ `/data/adb/modules`）能通过校验，
     * 落盘却是 `chmod 777 /data/adb/modules`：把受保护目录改成 0777 或把属主改成
     * 普通应用 uid，而弹窗显示与修改的都是链接自身，用户全程以为只改了一个链接的元数据。
     * `listFiles` 用 `stat -L`（跟随），`FileItem` 也没有链接标记，UI 无任何提示。
     *
     * 策略：解析真实路径后**对解析结果重跑** [isAllowedDataPath]，越界即拒绝。
     * 读不到真实路径（非链接时 readlink -f 仍返回同一路径）时按原路径处理。
     */
    private suspend fun resolveWritableTarget(path: String): Pair<String?, String> {
        val escapedPath = RootService.escapeShellArg(path)
        val parentDir = File(path).parent.orEmpty()
        val escapedParent = RootService.escapeShellArg(parentDir.ifEmpty { "/" })
        // 一次 su 调用同时取「完整解析」与「父目录解析」：后者用于辨认祖先目录为链接的正常路径，
        // 两项分开取会把 root 进程调用翻倍。
        val (_, out) = RootService.runCommandSync(
            "readlink -f $escapedPath 2>/dev/null; echo ---; readlink -f $escapedParent 2>/dev/null",
            10_000L
        )
        val segments = out.split("---", limit = 2)
        val resolved = segments.getOrNull(0)?.trim().orEmpty().ifEmpty { path }
        val parentResolved = segments.getOrNull(1)?.trim().orEmpty().ifEmpty { parentDir }
        if (!isAllowedDataPath(resolved)) {
            return null to "该路径是符号链接，真实目标（$resolved）不在 /data 下，已拒绝"
        }
        // 真实目标也必须仍在用户点选的路径之内，防止链到 /data 内的其它敏感位置。
        //
        // 这一段此前只有判断和注释、函数体是空的，等于该检查从未存在：`isAllowedDataPath`
        // 只能证明「解析后仍在 /data 下」，于是 `/data/local/tmp/m → /data/adb/modules`
        // 这类链接会被放行，root 随后把 modules 目录 chmod/改属主，而确认弹窗显示的
        // 只是链接自身的路径。链接指向自己或子目录（`p`、`p/x`）是正常用法，必须放行。
        if (resolved != path && !isResolvedInsideSelected(resolved, path, parentResolved)) {
            return resolved to "路径不安全：真实目标（$resolved）不在你选择的路径（$path）之内"
        }
        return resolved to ""
    }

    suspend fun changePermissions(path: String, mode: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isAllowedDataPath(path)) {
            return@withContext Pair(false, "仅允许修改 /data 下的路径")
        }
        if (!isValidPermissionMode(mode)) {
            return@withContext Pair(false, "权限模式必须是 3 或 4 位八进制数字")
        }
        if (!preferRoot()) return@withContext Pair(false, NO_ROOT_NEEDED_FOR_ABSOLUTE_PATH)

        val (target, problem) = resolveWritableTarget(path)
        if (target == null) return@withContext Pair(false, problem)
        val escapedPath = RootService.escapeShellArg(target)
        val (code, output) = RootService.runCommandSync("chmod $mode $escapedPath")
        if (code == 0) Pair(true, "权限修改成功") else Pair(false, "权限修改失败: $output")
    }

    suspend fun changeOwner(path: String, owner: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isAllowedDataPath(path)) {
            return@withContext Pair(false, "仅允许修改 /data 下的路径")
        }
        if (!isValidOwnerOrGroup(owner)) {
            return@withContext Pair(false, "所有者包含非法字符")
        }
        if (!preferRoot()) return@withContext Pair(false, NO_ROOT_NEEDED_FOR_ABSOLUTE_PATH)

        val (target, problem) = resolveWritableTarget(path)
        if (target == null) return@withContext Pair(false, problem)
        val escapedOwner = RootService.escapeShellArg(owner)
        val escapedPath = RootService.escapeShellArg(target)
        // `--` 选项终止符：owner 可含连字符，escapeShellArg 不为其加引号，
        // 没有终止符时 `chown -R path` 会被当作递归选项而非所有者。
        val (code, output) = RootService.runCommandSync("chown -- $escapedOwner $escapedPath")
        if (code == 0) Pair(true, "所有者修改成功") else Pair(false, "所有者修改失败: $output")
    }

    suspend fun changeGroup(path: String, group: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isAllowedDataPath(path)) {
            return@withContext Pair(false, "仅允许修改 /data 下的路径")
        }
        if (!isValidOwnerOrGroup(group)) {
            return@withContext Pair(false, "用户组包含非法字符")
        }
        if (!preferRoot()) return@withContext Pair(false, NO_ROOT_NEEDED_FOR_ABSOLUTE_PATH)

        val (target, problem) = resolveWritableTarget(path)
        if (target == null) return@withContext Pair(false, problem)
        val escapedGroup = RootService.escapeShellArg(group)
        val escapedPath = RootService.escapeShellArg(target)
        val (code, output) = RootService.runCommandSync("chown -- :$escapedGroup $escapedPath")
        if (code == 0) Pair(true, "用户组修改成功") else Pair(false, "用户组修改失败: $output")
    }

    /**
     * 确保 shso 工作目录存在，权限固定为 **0777**。
     *
     * 这是**硬性要求，不要收紧为 755**：`/data/adb/shso` 需要让其他应用（文件管理器、MT 管理器等）
     * 也能自由读写其中的文件；降权后第三方应用将无法访问，会直接破坏用户「把文件放进 shso 目录
     * 再用别的工具处理」的使用场景。
     *
     * 即便目录为 0777，应用自身对 `/data/adb/` 的写入仍受 SELinux 限制。
     * 凡以应用 uid 落盘的操作（如解压）必须先检测目标可写性，不可写时禁用入口。
     */
    suspend fun ensureShsoDir(): Boolean = withContext(Dispatchers.IO) {
        if (shsoDirEnsured) return@withContext true
        shsoDirLock.withLock {
            // 双重检查：并发调用里只有第一个真正跑 su，其余在此处直接命中已置位的标记。
            // 建立失败时不置位，后到者拿到锁会重试一次（与原有「失败即下次重来」语义一致）。
            if (shsoDirEnsured) return@withLock true
            val cmd = "mkdir -p ${RootService.escapeShellArg(DEFAULT_SHSO_DIR)} && chmod 777 ${RootService.escapeShellArg(DEFAULT_SHSO_DIR)}"
            val (code, _) = RootService.runCommandSync(cmd)
            if (code == 0) shsoDirEnsured = true
            code == 0
        }
    }

    /**
     * 轻量探测路径是否存在，供记忆目录失效回退使用。
     * 优先走 su（`[ -e ]`），su 不可用（无 ROOT）时退回 Java 本地 [File.exists] 判断，
     * 保证无 ROOT 设备上文件页也能正常浏览共享存储。
     */
    suspend fun pathExists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsafePath(path)) return@withContext false
        // ROOT 已授权时优先用 su 探测（可访问受保护/系统路径）；
        // 未授权则跳过 su，直接本地判断，保证无 ROOT 设备正常浏览共享存储。
        if (preferRoot()) {
            val escaped = RootService.escapeShellArg(path)
            val (code, output) = RootService.runCommandSync("test -e $escaped && echo yes")
            if (code == 0 && output.contains("yes")) return@withContext true
        }
        try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 目录列举结果：把「目录确实是空的」与「列举失败」彻底分开。
     *
     * 旧接口返回裸 [List]，上层只能靠 `isEmpty()` 判断，于是「root 调用失败」
     * 与「空目录」被压成同一个信号，UI 显示「当前目录为空」——用户看到的是
     * 一个断言，而真实情况是这次读取根本没成功（su 被拒、输出被截断、
     * stat 全部解析失败等）。空目录是事实陈述，不能承载失败。
     */
    sealed interface DirectoryListing {
        /** 列举成功。items 为空即目录确实为空。 */
        data class Success(val items: List<FileItem>) : DirectoryListing

        /** 目录不存在 / 不可达。 */
        data object Missing : DirectoryListing

        /** 列举失败。reason 面向用户可读。 */
        data class Failed(val reason: String) : DirectoryListing
    }

    /**
     * 单次 su 内「名称通道」与「元数据通道」的分隔标记。
     *
     * 用一个正常文件名不会出现的组合，两侧由 printf 各补一个 NUL，
     * 使 split('\0') 后边界绝对清晰。旧实现靠两次 find 的**输出顺序**配对，
     * 两次遍历之间目录若发生变化就会整体错位（某条目拿到别人的大小与时间）。
     */
    private const val META_SEP = "__SHSO_META_5A1C__"

    /** 单条目的元数据（不含名称）。 */
    private data class StatMeta(val isDirectory: Boolean, val size: Long, val modified: Long)

    /** 拼接目录与条目名；目录以 / 结尾时不重复加斜杠。 */
    private fun joinPath(directory: String, name: String): String =
        if (directory.endsWith("/")) "$directory$name" else "$directory/$name"

    /**
     * 标记是否被 NUL 包裹（printf 输出为 `\0标记\0`）。
     *
     * 文件名不可能含 NUL，因此「两侧均为 NUL」只可能是 printf 打出的那个真标记，
     * 可据此把「恰有文件叫同名」的假命中排除掉。
     */
    private fun isNulDelimited(text: String, markerIndex: Int): Boolean {
        // 名称通道恒在标记之前，故标记不可能落在输出首位。真为 0 只能说明输出被截断，
        // 此时若用哨兵值把「无前驱」当成 NUL，会把截断结果误判成合法通道 →
        // 名称通道读成空 → 把「读取不完整」报成「目录为空」。
        if (markerIndex <= 0) return false
        val before = text[markerIndex - 1]
        val afterIndex = markerIndex + META_SEP.length
        val after = if (afterIndex < text.length) text[afterIndex] else '\u0000'
        return before == '\u0000' && after == '\u0000'
    }

    /** 元数据通道的解析结果：名称 → 元数据，以及通道里实际出现过的名称集合。 */
    private data class StatChannel(val metas: Map<String, StatMeta>, val names: Set<String>)

    /**
     * 解析元数据通道，返回「条目名 → 元数据」映射与通道内的名称集合。
     *
     * 通道由 `find -exec sh -c 'printf "%s\0" "$f"; stat -L -c "%A|%s|%Y" "$f"; printf "\0"'`
     * 产出，形如 `名称\0权限串|字节数|mtime\0名称\0…`，通道本体前面还带着标记的收尾 NUL。
     *
     * 按**名称**配对，而不是按下标：条目数不等（目录变动、断链符号链接被 `stat -L` 拒绝）
     * 时，按下标会让从错位点起的每个条目都拿到别人的大小与时间。`stat` 失败（无输出）的
     * 条目其元数据段为空，直接跳过 —— 该条目自然退化为「非目录 / 0 字节」。
     *
     * 名称集合单独收集且不因 `stat` 失败而缺失：调用方用它判断两趟 find 是否看到同一份
     * 目录内容，从而把「软链天然缺元数据」与「目录已被改动」区分开。
     */
    private fun parseStatMeta(channel: String): StatChannel {
        // 通道以标记的收尾 NUL 开头，首段恒为空，先剥掉再两两配对。
        val raw = channel.substringAfter('\u0000').split('\u0000')
        val metas = HashMap<String, StatMeta>()
        val names = HashSet<String>()
        var index = 0
        while (index + 1 < raw.size) {
            val name = raw[index].removePrefix("./")
            val line = raw[index + 1].trim()
            if (name.isNotEmpty()) names.add(name)
            if (name.isEmpty() || line.isEmpty()) {
                index += 2
                continue
            }
            val parts = line.split('|')
            if (parts.size >= 3 && parts[0].length >= 2) {
                metas[name] = StatMeta(
                    isDirectory = parts[0][0] == 'd',
                    size = parts[1].toLongOrNull() ?: 0L,
                    modified = statSecondsToMillis(parts[2].toLongOrNull() ?: 0L)
                )
            }
            index += 2
        }
        return StatChannel(metas, names)
    }

    /**
     * 列举目录条目，并区分「成功（含空目录）」/「目录不存在」/「列举失败」。
     *
     * 与 [listFiles] 的差别：不再把失败压成空列表。这是「当前目录为空」
     * 误报的根因修复点 —— 调用方必须能区分二者才能给出正确文案。
     */
    suspend fun listDirectory(dirPath: String, attempt: Int = 0): DirectoryListing = withContext(Dispatchers.IO) {
        val targetPath = if (dirPath.isEmpty()) "/" else dirPath
        if (isUnsafePath(targetPath)) return@withContext DirectoryListing.Failed("路径非法")

        val escapedPath = RootService.escapeShellArg(targetPath)

        // ROOT 状态未落定（探测还在跑）时**不阻塞首帧**：先本地列举，本地能拿出条目就直接用。
        // 列举是只读操作，本地读得出来就没必要等 ROOT —— 而等探测最长要 ROOT_STATE_WAIT_MS，
        // 这段时间界面是空的，正是「进目录后半天不出文件」的来源之一。
        //
        // 本地拿不到条目才必须等：那种情形本地给的是「空」，而无权访问时 FUSE 会把无权目录
        // 也呈现成空目录（真机实测：未获「所有文件访问」时 /storage/emulated/0 本地列出 0 项，
        // 实际有 150 项），与真空目录无法区分 —— 只有 ROOT 的结论能定夺走 su 还是如实报空。
        //
        // 探测落定后界面会重读一次（FilePage 订阅 RootService.isRootGranted），
        // 因此这里「先本地」不会把受保护目录的失败结果永久留在屏幕上。
        if (RootService.isRootGranted == null) {
            val local = listDirectoryLocal(targetPath)
            if (local is DirectoryListing.Success && local.items.isNotEmpty()) {
                return@withContext local
            }
            awaitRootState()
        }

        if (RootService.isRootGranted == true) {
            // ── 单次 su 完成「存在性 + 名称 + 元数据」────────────────────────
            //
            // 旧实现是三次串行 su：test -d → find -print0 → find -exec stat。
            // 三次各自 fork su、各自解析一遍命令，除去延迟（实测每轮 su 往返数十毫秒，
            // 冷启动 / Magisk 首次授权时可达数百毫秒，用户观感就是「进目录后长时间不出文件」），
            // 更本质的问题是**三次之间存在 TOCTOU 窗口**：test -d 成功后目录可能被删除，
            // 此时 find 零输出而旧逻辑据此返回「空目录」——把「目录刚被删掉」报成「当前目录为空」。
            //
            // 合并后由退出码区分三种情况，语义不再依赖输出是否为空：
            //   3 → cd 失败（目录不存在或不可进入）；4 → 不是目录；0 → 成功。
            //
            // 两个 find 之间用固定标记分隔：名称通道整体以 NUL 结尾（NUL 不可能出现在
            // 文件名里，是唯一可靠记录边界），标记两侧再补 NUL，于是 split('\0') 后
            // 标记前全是文件名、标记后全是「名称 + 元数据」成对记录，两者互不污染 ——
            // 文件名里的 \n、| 、前后空格都不会错切记录，也不会像旧 `%A|%s|%Y|%n` 那样把
            // 含 \n 的名字劈成两条记录（一条指向不存在的幻影文件，长按删除即误删真文件）。
            //
            // 元数据通道内**自带名称**（`名称\0权限串|字节数|mtime\0`）：两趟 find 之间
            // 目录若变动、或有断链符号链接被 `stat -L` 拒绝，条目数就会不等 —— 按下标配对
            // 会让从该点起的**每个**条目拿到别人的大小与时间。按名称配对则天然免疫，
            // 缺元数据的条目退化为「非目录 / 0 字节」，不再错位到别的文件上。
            //
            // 元数据通道必须**批量**取：`-exec stat … {} +` 由 find 把整批路径交给一个
            // stat 进程；旧写法在 `sh -c` 里对每个条目各 fork 一次 stat，条目数一多就撞上
            // 进程创建开销 —— 真机（PACM00 / Android 10）实测 200 条目目录：逐文件 fork
            // 1.1~10s，批量后 0.05s 上下；名称通道只要 30ms。列表「加载慢」的根因即此，
            // 与 su 往返无关（单次 su 约 20ms）。
            //
            // 批量 stat 用 `%n` 自带名称，由 sed 按**最后**三个字段切开（文件名里的 `|`
            // 会被正确留在名称一侧，见下方 filterKeys 的兜底），再由 tr 把行分隔换成 NUL。
            // 输出与旧实现逐字节同构：`名称\0权限串|字节数|mtime\0` 成对记录，分隔一律 NUL
            // —— 文件名可含换行，按行切分会错位。
            //
            // 超时必须显式给：runCommandSync 的默认值是 120s，那是为长任务准备的。
            // 列举正常在 1s 内返回，若 su 停在 Magisk 授权弹窗上（用户没点），
            // 默认值会让这一趟挂满两分钟，界面停在骨架屏或过期列表上 —— 观感即卡死。
            val (code, out) = RootService.runCommandSync(
                "cd $escapedPath 2>/dev/null || exit 3; test -d . || exit 4; " +
                    "find . -maxdepth 1 -mindepth 1 -print0; printf '\\0$META_SEP\\0'; " +
                    "find . -maxdepth 1 -mindepth 1 -exec stat -L -c '%n|%A|%s|%Y' {} + 2>/dev/null " +
                    "| sed 's/|\\([^|]*\\)|\\([^|]*\\)|\\([^|]*\\)$/\\n\\1|\\2|\\3/' | tr '\\n' '\\0'",
                timeoutMs = LIST_TIMEOUT_MS
            )
            if (code == 3 || code == 4) {
                // ROOT 明确「进不去」或「不是目录」。仍给本地一次机会：
                // 无 ROOT 场景或应用恰好有权限时可能读得到。
                return@withContext listDirectoryLocal(targetPath)
            }
            if (code == -1) {
                // su 被拒 / 超时 / fork 失败：这次调用根本没跑成，不能断言目录为空。
                return@withContext listDirectoryLocal(targetPath, rootUnavailable = true)
            }

            // 标记由 printf 以 `\0` 包裹。文件名理论上可以是同名字符串，所以不能取第一个
            // 命中，必须取「两侧均为 NUL」的那一处（NUL 不可能出现在文件名内）。
            // 找不到这样的位置，说明命令中途被杀或输出被截断。如实报失败，
            // 绝不能把「只拿到一半输出」当成「目录为空」。
            var cut = out.indexOf(META_SEP)
            while (cut >= 0 && !isNulDelimited(out, cut)) {
                cut = out.indexOf(META_SEP, cut + 1)
            }
            if (cut < 0) {
                return@withContext DirectoryListing.Failed("目录读取结果不完整")
            }
            val names = out.substring(0, cut)
                .split('\u0000')
                .asSequence()
                .map { it.removePrefix("./") }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .toList()
            val nameSet = names.toSet()
            val parsed = parseStatMeta(out.substring(cut + META_SEP.length))

            // 名称与元数据取自**两趟独立的 find**。重试判据：只认「元数据通道看见了名称通道
            // 没有的名字」—— 那只可能是两次遍历之间目录被**新增**（被删只会让某一条缺元数据）。
            // 不做全等比较：断链符号链接被 `stat -L` 拒绝、天然没有记录，全等判据会让含断链
            // 软链的目录每次白跑一整轮列举。只重试一次 —— 持续变化的目录（日志、下载中）
            // 否则会无限递归。即便重试后仍不一致，按名称配对也只会让缺元数据的那一条退化为
            //「非目录 / 0 字节」，不会把其后每个条目错配到别的文件。
            if (attempt == 0 && parsed.names.any { it !in nameSet }) {
                return@withContext listDirectory(targetPath, attempt + 1)
            }
            // 只接受名称通道认可的记录。批量 `%n` 是「行分隔再转 NUL」的，文件名里若含换行，
            // 该条会被劈成两段，后半段形如「名字后缀|元数据」—— 按名字配对时它只可能命中一个
            // 恰好同名的兄弟项；滤掉即可保证不会把别条目的属性安到它头上。
            val metas = parsed.metas.filterKeys { it in nameSet }

            // ROOT 已确认可进入：条目列表就是权威结果，零条目即「目录确实为空」。
            // 绝不能回落到本地判定 —— /data/adb/shso 等目录应用侧被 SELinux 拦，
            // 本地 listFiles() 返回 null，据此报「无权限」就是把空目录说成故障。
            val items = names.mapIndexed { _, name ->
                val meta = metas[name]
                FileItem(
                    name = name,
                    path = joinPath(targetPath, name),
                    isDirectory = meta?.isDirectory == true,
                    size = meta?.size ?: 0L,
                    lastModified = meta?.modified ?: 0L
                )
            }
            return@withContext DirectoryListing.Success(items.distinctBy { it.path })
        }

        // 无 ROOT，或 ROOT 调用失败：交给本地兜底判定。
        // 能走到这里说明本次列举没有走 su：或是 ROOT 未授权，或是探测仍无结论。
        // 两种情形 su 都没有被调用过，因此不存在「ROOT 不可用」这一前提，不传该标记。
        // （su 被拒/超时的情形已在上面 code == -1 分支显式以 rootUnavailable=true 兜底。）
        listDirectoryLocal(targetPath)
    }

    /**
     * 本地 [File] API 兜底列举（应用自身权限内）。
     *
     * @param rootUnavailable ROOT 不可用（无授权或 su 调用失败）。用于区分失败语义：
     *   true  → 读不到是**权限/环境**问题，报 Failed；false → 读不到即不存在，报 Missing。
     */
    private suspend fun listDirectoryLocal(
        targetPath: String,
        rootUnavailable: Boolean = false
    ): DirectoryListing = withContext(Dispatchers.IO) {
        val localFiles = try {
            File(targetPath).listFiles()
        } catch (_: Exception) {
            null
        }
        if (localFiles == null) {
            // 读不到。先确认路径本身是否存在：不存在是 Missing（可回退），
            // 存在却读不到才是 Failed（真实故障，需提示重试）。
            val exists = try {
                File(targetPath).isDirectory
            } catch (_: Exception) {
                false
            }
            return@withContext if (exists) {
                DirectoryListing.Failed("无权限读取该目录")
            } else if (rootUnavailable) {
                // ROOT 不可用且路径不存在：无法进一步区分「本来就不存在」与
                // 「被 ROOT 授权问题掩盖」，如实报失败而不是断言不存在。
                DirectoryListing.Failed("ROOT 调用失败，请检查授权")
            } else {
                DirectoryListing.Missing
            }
        }
        val items = localFiles.map { f ->
            FileItem(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                lastModified = f.lastModified()
            )
        }
        DirectoryListing.Success(items.distinctBy { it.path })
    }

    suspend fun listFiles(dirPath: String): List<FileItem> = withContext(Dispatchers.IO) {
        when (val result = listDirectory(dirPath)) {
            is DirectoryListing.Success -> result.items
            // 旧接口无法表达失败：调用方（选择器等）只关心条目，
            // 失败与不存在同样给空列表，但**不得**据此断言目录为空。
            is DirectoryListing.Missing -> emptyList()
            is DirectoryListing.Failed -> emptyList()
        }
    }

    /**
     * `stat -c %Y` 输出的是「秒」，而 [FileItem.lastModified] 的契约是「毫秒」
     * （本地路径走 `File.lastModified()`，本身就是毫秒）。
     *
     * 不换算会把秒当成毫秒，修改时间显示为 1970 年，且按时间排序错乱。
     */
    internal fun statSecondsToMillis(seconds: Long): Long = if (seconds <= 0L) 0L else seconds * 1000L

    /**
     * 对单个路径做 stat，构造 [FileItem]（不依赖所在目录的列表）。
     *
     * 用途：外部唤起时目标可能是**隐藏文件**（默认被列表过滤）或**刚落盘的收件箱副本**，
     * 若等它出现在目录列表里再处理，就会永远等不到。此处直接取属性，使分派与列表可见性解耦。
     * 失败（路径非法/不存在/无法 stat）返回 null；上层不得对不确定的路径继续分派动作。
     */
    suspend fun statFilePath(path: String): FileItem? = withContext(Dispatchers.IO) {
        val name = path.substringAfterLast('/').ifEmpty { path }
        if (isUnsafePath(path) || name.isEmpty() || name == "." || name == "..") return@withContext null

        if (preferRoot()) {
            val escaped = RootService.escapeShellArg(path)
            val (code, output) = RootService.runCommandSync("stat -L -c \"%A|%s|%Y|%n\" $escaped 2>/dev/null")
            // ROOT is authoritative for protected paths. Do not downgrade a failed
            // stat to best-effort metadata and accidentally dispatch an unknown file.
            if (code != 0) return@withContext null
            return@withContext parseSingleStatOutput(output, path)
        }
        val file = runCatching { File(path) }.getOrNull() ?: return@withContext null
        if (!runCatching { file.exists() }.getOrDefault(false)) return@withContext null
        FileItem(
            name = name,
            path = path,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            lastModified = file.lastModified()
        )
    }

    /** 解析单文件 stat 的完整路径输出；不复用目录列表的相对路径解析。 */
    internal fun parseSingleStatOutput(output: String, path: String): FileItem? {
        val parts = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.split("|", limit = 4)
            ?: return null
        if (parts.size != 4 || parts[0].length < 2) return null
        val name = File(path).name
        if (name.isEmpty() || name == "." || name == "..") return null
        val permissions = parts[0]
        val isDirectory = permissions[0] == 'd'
        return FileItem(
            name = name,
            path = path,
            isDirectory = isDirectory,
            size = parts[1].toLongOrNull() ?: return null,
            lastModified = statSecondsToMillis(parts[2].toLongOrNull() ?: return null),
            permissions = permissions
        )
    }


    suspend fun addFileToShso(
        sourcePath: String,
        useIndependentFolder: Boolean,
        autoDeleteSource: Boolean
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(sourcePath)) {
            return@withContext Pair(false, "源路径包含非法字符")
        }

        val sourceFile = File(sourcePath)
        val sourceName = sourceFile.name
        // 独立目录名要落进 shell 并作为路径段：控制字符、路径分隔符、".." 会改变命令语义
        // 或越目录，统一替换为下划线；首尾的点与空格（`..`、`.`）会与目录穿越混淆，一并裁掉。
        val nameWithoutExt = sourceFile.nameWithoutExtension
            .replace("'", "")
            .map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }
            .joinToString("")
            .trim('.', ' ')
            .ifEmpty { "item" }

        val targetDir = if (useIndependentFolder) {
            // 用完整 epoch 毫秒：取模 100000 只有 100 秒周期，同一分钟内多次「添加」
            // 就会撞进同一目录，后一次的 cp 落在已存在的目录上变成嵌套副本（X/X）。
            val timestamp = System.currentTimeMillis().toString()
            "$DEFAULT_SHSO_DIR/${nameWithoutExt}_$timestamp"
        } else {
            DEFAULT_SHSO_DIR
        }

        val destinationPath = "$targetDir/$sourceName"
        // 本地可见即拒（0 次 fork）。受保护目标本地恒 false，权威判定在下面那条 su 里
        // （命中即 rc=2）—— 两层都保留，任何一层都不构成唯一防线。
        if (destinationExists(destinationPath, sourcePath)) {
            return@withContext Pair(false, "目标已存在同名文件或目录：$sourceName")
        }

        val merged = buildAddToShsoCommand(
            sourcePath = sourcePath,
            targetDir = targetDir,
            destinationPath = destinationPath,
            independentFolder = useIndependentFolder,
            autoDeleteSource = autoDeleteSource
        )

        val parsed: AddToShsoParse
        val raw: String
        if (merged.toByteArray(Charsets.UTF_8).size <= ADD_TO_SHSO_MAX_BYTES) {
            val (_, out) = RootService.runCommandSync(merged)
            raw = out
            parsed = parseAddToShsoOutput(out)
        } else {
            // 极端超长路径（单条命令 >2900 字节）无法整段送达：退回「建目录」「拷贝」两条。
            // 仍比旧实现少一条 —— 不覆盖判据并进了拷贝那条。
            val (_, dirOut) = RootService.runCommandSync(
                buildAddToShsoCommand(
                    sourcePath = sourcePath,
                    targetDir = targetDir,
                    destinationPath = destinationPath,
                    independentFolder = useIndependentFolder,
                    autoDeleteSource = false,
                    copy = false
                )
            )
            val dirParsed = parseAddToShsoOutput(dirOut)
            if (dirParsed.done && dirParsed.code == 0) {
                val (_, out) = RootService.runCommandSync(
                    buildAddToShsoCommand(
                        sourcePath = sourcePath,
                        targetDir = targetDir,
                        destinationPath = destinationPath,
                        independentFolder = false,
                        autoDeleteSource = autoDeleteSource,
                        ensureDir = false
                    )
                )
                raw = out
                parsed = parseAddToShsoOutput(out)
            } else {
                raw = dirOut
                parsed = dirParsed
            }
        }

        if (!parsed.done) {
            // 缺完成标记 = su 停在授权窗 / 被拒 / 超时被杀 / 命令整段丢弃。状态未知，绝不报成功。
            return@withContext Pair(false, "添加失败：ROOT 命令未完成")
        }
        // 建目录成功后立刻置位：旧实现靠 ensureShsoDir() 顺带置位，现在由这里承担，
        // 否则每次添加之后其他入口还会各跑一次幂等的 mkdir + chmod。
        if (parsed.code != null && parsed.code != ADD_SHSO_MKDIR_FAILED) shsoDirEnsured = true

        // 协议行（RC: / 完成标记）不得进用户文案；其余是 cp / chmod / mkdir 的真实诊断。
        val detail = raw.lineSequence()
            .filterNot { it.startsWith("RC:") || it == ADD_TO_SHSO_DONE_MARKER }
            .joinToString("\n")
            .trim()
        when (parsed.code) {
            0 -> return@withContext Pair(true, destinationPath)
            ADD_SHSO_MKDIR_FAILED -> return@withContext Pair(
                false,
                if (detail.isEmpty()) "无法创建目标文件夹" else "无法创建目标文件夹: $detail"
            )
            ADD_SHSO_DEST_EXISTS ->
                return@withContext Pair(false, "目标已存在同名文件或目录：$sourceName")
            ADD_SHSO_DELETE_SOURCE_FAILED -> return@withContext Pair(
                false,
                if (detail.isEmpty()) "已复制到 shso，但删除源文件失败"
                else "已复制到 shso，但删除源文件失败: $detail"
            )
            else -> return@withContext Pair(
                false,
                if (detail.isEmpty()) "复制文件失败" else "复制文件失败: $detail"
            )
        }
    }

suspend fun rename(oldPath: String, newName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(oldPath)) {
            return@withContext Pair(false, "路径包含非法字符")
        }
        invalidateShsoDirIfAffected(oldPath)
        val sanitized = newName.trim()
        if (sanitized.isEmpty()) {
            return@withContext Pair(false, "文件名不能为空")
        }
        // 换行/回车可被 shell 解释为命令分隔，必须一并拒绝（与 /、\、..、\0 同级）
        if (isUnsafeFileName(sanitized)) {
            return@withContext Pair(false, "文件名不能包含路径分隔符或非法字符")
        }

          val parent = File(oldPath).parent ?: "/"
          val newPath = if (parent.endsWith("/")) "$parent$sanitized" else "$parent/$sanitized"

          // 目标已存在时必须拒绝，不能直接覆盖。
          // 旧实现两条路径都是「无条件替换」：root 走 mv（默认覆盖），
          // 非 root 走 File.renameTo（Linux 同为 rename(2) 覆盖）。
          // 覆盖只应由用户显式选择，重命名没有该选项，故一律拒绝。
          // 本地看得见就先拒：0 次 su 解决绝大多数冲突。
          val occupiedLocally = newPath != oldPath &&
              runCatching { File(newPath).exists() }.getOrDefault(false)
          if (occupiedLocally) {
              return@withContext Pair(false, "目标已存在：$sanitized")
          }

        // 改名不是删除：编辑历史跟着文件搬到新路径（目录则整棵子树一起搬）。
        fun renamed(): Pair<Boolean, String> {
            EditHistoryManager.moveHistory(oldPath, newPath)
            return true to "重命名成功"
        }
        // ROOT 已授权时优先用 su 移动（可操作受保护/系统路径）；
        // 未授权或 su 失败时回退标准 renameTo（授予「所有文件访问」后可操作 /sdcard）。
        if (preferRoot()) {
            val escapedOld = RootService.escapeShellArg(oldPath)
            val escapedNew = RootService.escapeShellArg(newPath)
            // 一条 su 完成「不覆盖判定 + 改名」。旧实现是
            // 存在性单独一趟 su + mv 另一趟 su，同目标探测两趟。
            // 本地看不见（受保护路径）时，这里与 mv 同进程内补判，消除 TOCTOU。
            val guard = if (newPath == oldPath) {
                ""
            } else {
                "if [ -e $escapedNew ] || [ -L $escapedNew ]; then printf 'EXISTS\\n'; exit 2; fi\n"
            }
            val (code, out) = RootService.runCommandSync(
                guard + "mv $escapedOld $escapedNew 2>/dev/null && printf 'MOVED\\n'"
            )
            if (code == 2 && out.contains("EXISTS")) {
                return@withContext Pair(false, "目标已存在：$sanitized")
            }
            if (code == 0 && out.contains("MOVED")) return@withContext renamed()
        }
        try {
            if (File(oldPath).renameTo(File(newPath))) return@withContext renamed()
        } catch (_: Exception) {
        }
        Pair(false, "重命名失败")
    }

    /**
     * 目标路径是否已被**另一个条目**占用 —— **仅应用可见范围的快路径**（零 fork）。
     *
     * 应用 uid 看不到受保护路径（`File.exists()` 对 /data/adb 下的路径恒 false），
     * 所以这个函数只回答「本地可见的同名项」，**不构成最终判定**；
     * 权威判定并进了 [addFileToShso] 那条唯一的 su（`[ -e "$3" ] || [ -L "$3" ]` → rc=2）。
     *
     * 保留函数而不内联，是因为 `newPath == oldPath` 这个例外需要一处显式表达，
     * 且必须对两侧都 `trimEnd('/')` —— 旧实现只做字面比较，
     * `/x/y/` 与 `/x/y` 会被误判成两个条目。
     *
     * `suspend` 修饰必须保留：`MoveBatchContractTest` 用它作 rename 函数体的切片右界，
     * 去掉 `suspend` 会让 `indexOf` 返回 -1 并抛 `StringIndexOutOfBoundsException`。
     */
    private suspend fun destinationExists(newPath: String, oldPath: String): Boolean {
        if (newPath.trimEnd('/') == oldPath.trimEnd('/')) return false
        return runCatching { File(newPath).exists() }.getOrDefault(false)
    }

    suspend fun moveFile(
        sourcePath: String,
        destinationDirectory: String,
        onConflict: MoveDestinationConflict = MoveDestinationConflict.OVERWRITE
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(sourcePath) || isUnsafePath(destinationDirectory)) {
            return@withContext Pair(false, "路径包含非法字符")
        }
        invalidateShsoDirIfAffected(sourcePath)

        val source = File(sourcePath)
        val sourceName = source.name
        if (sourceName.isEmpty() || sourceName == "." || sourceName == "..") {
            return@withContext Pair(false, "源文件名无效")
        }

        val sourceNormalized = sourcePath.trimEnd('/').ifEmpty { "/" }
        val destinationNormalized = destinationDirectory.trimEnd('/').ifEmpty { "/" }
        if (sourceNormalized == destinationNormalized) {
            return@withContext Pair(false, "目标目录不能与源路径相同")
        }
        fun localType(path: String): Int = try {
            val file = File(path)
            when {
                file.isDirectory -> 2
                file.isFile -> 1
                file.exists() -> 3
                else -> 0
            }
        } catch (_: Exception) {
            0
        }

        fun rootType(path: String): Int {
            val escaped = RootService.escapeShellArg(path)
            val (code, output) = RootService.runCommandSync(
                "if [ -d $escaped ]; then echo dir; elif [ -f $escaped ]; then echo file; elif [ -e $escaped ]; then echo other; fi"
            )
            if (code != 0) return 0
            return when (output.trim()) {
                "dir" -> 2
                "file" -> 1
                "other" -> 3
                else -> 0
            }
        }

        val sourceType = localType(sourcePath).let { local ->
            if (local != 0) local else if (RootService.isRootGranted == true) rootType(sourcePath) else 0
        }
        if (sourceType == 0) return@withContext Pair(false, "源文件不存在或不可访问")
        if (sourceType == 3) return@withContext Pair(false, "源路径不是普通文件或文件夹")
        if (sourceType == 2 && destinationNormalized.startsWith("$sourceNormalized/")) {
            return@withContext Pair(false, "不能将文件夹移动到自身或其子目录")
        }

        val destinationType = localType(destinationDirectory).let { local ->
            if (local != 0) local else if (RootService.isRootGranted == true) rootType(destinationDirectory) else 0
        }
        if (destinationType != 2) return@withContext Pair(false, "目标路径不是文件夹或不可访问")

        val destinationPath = if (destinationNormalized == "/") "/$sourceName" else "$destinationNormalized/$sourceName"

        // 目标就是源本身（把条目移进它自己所在的目录）。
        // 下面 OVERWRITE 分支对目录会先 delete(destinationPath) —— 那正是源本身，
        // 会删掉源后再也搬不回来。RENAME 是真改名，不在此拦。
        if (destinationPath == sourceNormalized && onConflict != MoveDestinationConflict.RENAME) {
            return@withContext if (sourceType == 2) {
                Pair(false, "目标与源相同，不能覆盖自身")
            } else {
                // 文件 rename(2) 到自身是成功空操作；moveHistory(from==to) 亦为空操作。
                Pair(true, "移动成功")
            }
        }

        fun destExists(): Boolean =
            localType(destinationPath) != 0 ||
                (RootService.isRootGranted == true && rootType(destinationPath) != 0)

        // 自动改名：在扩展名前插入 _new（file.txt → file_new.txt，无扩展名 → name_new）
        fun renamedDestPath(): String {
            val dot = sourceName.lastIndexOf('.')
            val newName = if (dot > 0) "${sourceName.substring(0, dot)}_new${sourceName.substring(dot)}" else "${sourceName}_new"
            return if (destinationNormalized == "/") "/$newName" else "$destinationNormalized/$newName"
        }

        val destOccupied = destExists()
        if (destOccupied) {
            when (onConflict) {
                MoveDestinationConflict.OVERWRITE -> {
                    // 仅当目标是**目录**时才需要先删：`rename(2)` 与 `mv` 本身就会原子覆盖
                    // 同类型文件目标，先删等于凭空造出一个「移动失败则目标已丢」的窗口
                    // （源在别的挂载点 / 无权限时，mv 必然失败，
                    //   而目标已被删掉，用户数据不可恢复）。
                    // 目录目标无法被 rename 覆盖（非空目录会 ENOTEMPTY），只能先删。
                    val destType = localType(destinationPath).let {
                        if (it == 0 && RootService.isRootGranted == true) rootType(destinationPath) else it
                    }
                    if (destType == 2) {
                        if (!delete(destinationPath).first) {
                            return@withContext Pair(false, "无法覆盖目标同名目录")
                        }
                    }
                }
                MoveDestinationConflict.RENAME -> {
                    // 原目标保留；换成 _new 路径。若 _new 也重名则失败。
                    val renamed = renamedDestPath()
                    val renamedExists = localType(renamed) != 0 ||
                        (RootService.isRootGranted == true && rootType(renamed) != 0)
                    if (renamedExists) {
                        return@withContext Pair(false, "自动改名后的目标也已存在")
                    }
                }
                MoveDestinationConflict.SKIP -> {
                    return@withContext Pair(false, "目标文件夹中已存在同名项目（跳过）")
                }
            }
        }

        // 实际目标路径：OVERWRITE 用原名；RENAME 冲突时用 _new 名。
        // 复用上面的探测结果：该探测在 root 场景每次要 fork 两次 su
        // （local + root 各一次），重复调用等于白白多跑两趟 su 往返。
        val finalPath = if (destOccupied && onConflict == MoveDestinationConflict.RENAME) renamedDestPath() else destinationPath

        // 移动同样不是删除：历史跟着搬到实际落点（冲突改名后是 finalPath，不是 destinationPath）。
        fun moved(): Pair<Boolean, String> {
            EditHistoryManager.moveHistory(sourcePath, finalPath)
            return true to "移动成功"
        }

        try {
            val dest = File(finalPath)
            // renameTo 返回 true 即视为成功。原先还要求 `localType(finalPath) == sourceType`，
            // 而目标常位于 app 无权 stat 的挂载点（FUSE/sdcardfs）或本身是 socket/FIFO，
            // 复核必然失败 → 落到 root 分支再跑一次 mv（源已不在，报「移动失败」）。
            // 移动其实已经完成，用户会重试，第二次就命中 OVERWRITE 分支，可能连带删掉目标同名文件。
            if (source.renameTo(dest)) {
                return@withContext moved()
            }
        } catch (_: Exception) {
        }

        if (RootService.isRootGranted == true) {
            val escapedSource = RootService.escapeShellArg(sourcePath)
            val escapedDestination = RootService.escapeShellArg(finalPath)
            val (code, output) = RootService.runCommandSync(
                "mv $escapedSource $escapedDestination && test ! -e $escapedSource && " +
                    if (sourceType == 2) "test -d $escapedDestination" else "test -f $escapedDestination"
            )
            if (code == 0) return@withContext moved()
            if (output.isNotBlank()) return@withContext Pair(false, "移动失败: ${output.trim()}")
        }

        Pair(false, "移动失败")
    }

    // ────────────────────────────────────────────────────────────────────
    // 批量移动
    //
    // 旧路径：FilePage 逐项 moveFile()。`/sdcard` 下每项至少一次 su
    // （destExists 的 root 兜底），受保护路径 3~5 次；picker 入口还要先逐项
    // moveDestinationCollides（又 +1 su/项）。400 项 = 400~800 su ≈ 60~120s，
    // 而真正的 rename/copy 占比不到 5%，其余全是 su 往返。
    //
    // 这里把整批压进少数几条 su：一条 `set --` 打包，一个 while 循环逐项
    // 判定冲突 + mv + 回捞，su 次数从 O(N) 降到 O(命令字节 / 2900)。
    //
    // 与 moveFile 的语义逐条对齐见施工文档 §3.6。moveFile 只加了一处
    // 「目标即源」守卫（数据丢失修复），其余一字未改：
    // PerfAndNestingRegressionTest:253-262 锁死了它的结构（destExists 恰好一次）。
    // ────────────────────────────────────────────────────────────────────

    /** 单段移动命令的 UTF-8 字节上限（与 [DELETE_SEGMENT_MAX_BYTES] 同口径与理由）。 */
    internal const val MOVE_SEGMENT_MAX_BYTES = 2900

    /** 段完成标记：**整行**精确比对。缺标记 = 该段没执行（su 被拒 / 超时 / 命令被丢弃）。 */
    internal const val MOVE_SEGMENT_DONE_MARKER = "SHSO_MOVE_OK"

    /** 冲突探测段的完成标记。 */
    internal const val MOVE_PROBE_DONE_MARKER = "SHSO_PROBE_OK"

    /** 冲突探测段超时：探测只做 test，正常在 1s 内返回。 */
    private const val MOVE_PROBE_TIMEOUT_MS = 15_000L

    private const val MOVE_SEGMENT_TIMEOUT_BASE_MS = 20_000L
    private const val MOVE_SEGMENT_TIMEOUT_PER_ITEM_MS = 500L
    private const val MOVE_SEGMENT_TIMEOUT_MAX_MS = 120_000L

    /** 连续失败 2 段就停发后续段：一段超时往往只是段内有跨挂载点大目录，su 本身没坏。 */
    private const val MOVE_SEGMENT_MAX_CONSECUTIVE_FAILURES = 2

    /** 一个待移动项：源、计划目标、冲突改名后的备选目标。 */
    internal data class MovePlanItem(
        val source: String,
        val destination: String,
        val renamed: String
    )

    /**
     * 批量移动结果：与 [BatchDeleteResult] 同一套分档语义。
     *
     * - [moved]       已确认移动（源已不在、目标已出现）；
     * - [failed]      明确失败（冲突跳过 / 改名目标已存在 / 源不在 / mv 失败）；
     * - [unprocessed] 状态未知（段命令没跑完、单项超长、或段跑完但该项没回执），**绝不**计成功；
     * - [rejected]    上游 [isUnsafePath] 或文件名校验拒掉的路径，连尝试都没有。
     *
     * 失败原因的表达能力会真实退化：没有逐项 errno 了，只剩固定文案。
     * 取舍与 [deleteBatch] 一致。
     */
    data class BatchMoveResult(
        val moved: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val unprocessed: List<String> = emptyList(),
        val rejected: List<String> = emptyList(),
        val reasons: Map<String, String> = emptyMap()
    ) {
        val failedCount: Int get() = failed.size + unprocessed.size + rejected.size

        /** 逐项失败明细，沿用 FilePage 旧文案格式「名称（原因）」。 */
        fun failureLines(): List<String> {
            val out = ArrayList<String>(failedCount)
            failed.forEach { out += "${File(it).name}（${reasons[it] ?: "移动失败"}）" }
            unprocessed.forEach { out += "${File(it).name}（未完成）" }
            rejected.forEach { out += "${File(it).name}（${reasons[it] ?: "路径包含非法字符"}）" }
            return out
        }
    }

    /** 冲突改名目标：在扩展名前插入 `_new`（file.txt → file_new.txt）。与 [moveFile] 同口径。 */
    private fun renamedInDestination(sourceName: String, destinationNormalized: String): String {
        val dot = sourceName.lastIndexOf('.')
        val newName = if (dot > 0) {
            "${sourceName.substring(0, dot)}_new${sourceName.substring(dot)}"
        } else {
            "${sourceName}_new"
        }
        return joinPath(destinationNormalized, newName)
    }

    /** 本地类型判定（2=目录 1=文件 3=其它 0=看不到）。与 [moveFile] 内的同义实现一致。 */
    private fun moveLocalType(path: String): Int = try {
        val file = File(path)
        when {
            file.isDirectory -> 2
            file.isFile -> 1
            file.exists() -> 3
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    /** ROOT 下的类型判定。整批只判一次（整批只有一个目标目录）。 */
    private suspend fun moveRootType(path: String): Int {
        val escaped = ShellEscapes.escapeShellArg(path)
        val (code, output) = RootService.runCommandSync(
            "if [ -d $escaped ]; then echo dir; elif [ -f $escaped ]; then echo file; " +
                "elif [ -e $escaped ]; then echo other; fi"
        )
        if (code != 0) return 0
        return when (output.trim()) {
            "dir" -> 2
            "file" -> 1
            "other" -> 3
            else -> 0
        }
    }

    private fun moveLocalExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Exception) {
        false
    }

    /**
     * 批量移动：整批一次调用，内部按 UTF-8 字节分段，每段一条 su。
     *
     * 每段内一个 while 循环逐项处理：判源是否还在 → 判目标占用 →
     * 按 [onConflict] 走改名或覆盖 → `mv` → 立即回捞（源是否消失、目标是否出现）。
     *
     * 与逐项 [moveFile] 的差别只有两处：
     *  · `mv` 的 stderr 被丢弃，失败原因固定为「移动失败」（批量下逐项 errno 无法稳定收集）；
     *  · FIFO/socket 不做拒绝（`moveFile` 的 sourceType==3 分支），直接交给 `mv`。
     */
    suspend fun moveBatch(
        sources: List<String>,
        destinationDirectory: String,
        onConflict: MoveDestinationConflict = MoveDestinationConflict.SKIP
    ): BatchMoveResult = withContext(Dispatchers.IO) {
        val unique = sources.distinct()
        if (unique.isEmpty()) return@withContext BatchMoveResult()

        val moved = ArrayList<String>(unique.size)
        val failed = ArrayList<String>()
        val unprocessed = ArrayList<String>()
        val rejected = ArrayList<String>()
        val reasons = HashMap<String, String>()

        // 唯一的「计失败」出口：明细与原因必须同时落地。
        fun fail(source: String, reason: String) {
            failed += source
            reasons[source] = reason
        }

        // shell 路径下唯一的「计成功」出口：历史迁移与计数必须同时发生。
        // 任何新增的成功分支绕过它，都会留下指向旧路径的孤儿历史键。
        fun markMoved(source: String, finalPath: String, overwroteDirectory: Boolean) {
            // OVERWRITE 删掉同名目录时，那棵子树的历史必须一并回收（与 delete() 同口径）；
            // 顺序不能反：先回收目标侧旧历史，再把源历史搬过去。
            if (overwroteDirectory) EditHistoryManager.forget(finalPath)
            EditHistoryManager.moveHistory(source, finalPath)
            moved += source
        }

        fun result(): BatchMoveResult {
            val order = unique.withIndex().associate { (index, path) -> path to index }
            fun sorted(items: List<String>): List<String> =
                items.sortedBy { order[it] ?: Int.MAX_VALUE }
            return BatchMoveResult(
                moved = sorted(moved),
                failed = sorted(failed),
                unprocessed = sorted(unprocessed),
                rejected = sorted(rejected),
                reasons = reasons
            )
        }

        val destNormalized = destinationDirectory.trimEnd('/').ifEmpty { "/" }

        // ── 阶段 0：纯词法预检（零 su）────────────────────────────────
        val pending = ArrayList<MovePlanItem>(unique.size)
        for (source in unique) {
            // 与 moveFile 一致：标记失效不看移动结果，且必须**逐路径**调
            // （函数体是 `if (path == DEFAULT_SHSO_DIR)` 条件判定，批入口只调一次会漏）。
            invalidateShsoDirIfAffected(source)
            if (isUnsafePath(source)) {
                rejected += source
                reasons[source] = "路径包含非法字符"
                continue
            }
            if (isUnsafePath(destNormalized)) {
                rejected += source
                reasons[source] = "目标路径包含非法字符"
                continue
            }
            val name = File(source).name
            if (name.isEmpty() || name == "." || name == "..") {
                rejected += source
                reasons[source] = "源文件名无效"
                continue
            }
            val sourceNormalized = source.trimEnd('/').ifEmpty { "/" }
            if (sourceNormalized == destNormalized) {
                fail(source, "目标目录不能与源路径相同")
                continue
            }
            // 对文件不可能误中（文件不能包含子路径），故无条件判，比 moveFile 更保守。
            if (destNormalized.startsWith("$sourceNormalized/")) {
                fail(source, "不能将文件夹移动到自身或其子目录")
                continue
            }
            val destinationPath = joinPath(destNormalized, name)
            if (destinationPath == sourceNormalized) {
                // 移进自己所在的目录：moveFile 的 renameTo(同路径) 是成功空操作；
                // RENAME 策略下则是真改名（a.txt → a_new.txt），必须交给 shell。
                // 这一支同时挡住 OVERWRITE 对自身的预删（见 moveFile 的同名守卫）。
                when (onConflict) {
                    MoveDestinationConflict.OVERWRITE -> markMoved(source, sourceNormalized, false)
                    MoveDestinationConflict.SKIP ->
                        fail(source, "目标文件夹中已存在同名项目（跳过）")
                    MoveDestinationConflict.RENAME ->
                        pending += MovePlanItem(source, destinationPath, renamedInDestination(name, destNormalized))
                }
                continue
            }
            pending += MovePlanItem(source, destinationPath, renamedInDestination(name, destNormalized))
        }
        if (pending.isEmpty()) return@withContext result()

        // 一次取定的 ROOT 结论：目标目录判定与「走不走 su」必须用同一个值，
        // 否则两次 preferRoot() 各等一轮探测，且可能前后不一致。
        val useRoot = preferRoot()

        // 目标目录必须是文件夹：整批判一次（moveFile 是每项判一次，同一目标纯属重复）。
        val destinationType = moveLocalType(destNormalized).let { local ->
            if (local != 0) local else if (useRoot) moveRootType(destNormalized) else 0
        }
        if (destinationType != 2) {
            pending.forEach { fail(it.source, "目标路径不是文件夹或不可访问") }
            return@withContext result()
        }

        // 无 ROOT：逐项走 moveFile（其内部只有本地 stat 与 renameTo，零 su），行为不变。
        if (!useRoot) {
            for (item in pending) {
                val (ok, message) = moveFile(item.source, destNormalized, onConflict)
                if (ok) moved += item.source else fail(item.source, message)
            }
            return@withContext result()
        }

        // ── 阶段 1：分段批量 su ───────────────────────────────────────
        val sendable = ArrayList<MovePlanItem>(pending.size)
        for (item in pending) {
            // 单项本身装不进一段：发出去只会被 su 整段丢弃，直接判未完成
            // （与 deleteBatch 的 deletePathFitsOneSegment 同一道预判）。
            if (moveItemFitsOneSegment(item, onConflict)) sendable += item else unprocessed += item.source
        }
        var consecutiveFailures = 0
        for (segment in planMoveSegments(sendable, onConflict)) {
            if (consecutiveFailures >= MOVE_SEGMENT_MAX_CONSECUTIVE_FAILURES) {
                segment.forEach { unprocessed += it.source }
                continue
            }
            val (_, out) = RootService.runCommandSync(
                buildMoveSegmentCommand(segment, onConflict),
                timeoutMs = moveSegmentTimeoutMs(segment.size)
            )
            val parsed = parseMoveSegmentOutput(out, segment.size)
            if (!parsed.done) {
                // 缺完成标记 = 这一段根本没执行：su 停在授权窗、被拒、超时被杀，
                // 或命令超长被整段丢弃。判「未完成」，**绝不**计成功。
                segment.forEach { unprocessed += it.source }
                consecutiveFailures++
                continue
            }
            consecutiveFailures = 0
            segment.forEachIndexed { index, item ->
                val code = parsed.codes[index]
                when (code) {
                    'K', 'D' -> markMoved(
                        item.source,
                        // 历史必须跟到**实际**落点：RENAME 冲突时是 _new 名。
                        if (parsed.targets[index] == 'r') item.renamed else item.destination,
                        code == 'D'
                    )
                    'S' -> fail(item.source, "目标文件夹中已存在同名项目（跳过）")
                    'C' -> fail(item.source, "自动改名后的目标也已存在")
                    'X' -> fail(item.source, "源文件不存在或不可访问")
                    // 段跑完了但这一项没有回执：状态未知（可能已搬走），
                    // 断言「移动失败」是错的，归「未完成」。
                    null -> unprocessed += item.source
                    else -> fail(item.source, "移动失败")
                }
            }
        }
        result()
    }

    /**
     * 供 UI 决定是否弹冲突框：整批一次探测（分段），返回「目标已存在」的源路径。
     *
     * 旧写法 `targets.any { moveDestinationCollides(it, dest) }` 是 **M 次 su**，
     * 只为回答一个是/否。这里同样走 `set --` 打包 + shell 内建 test。
     *
     * 探测没跑完时**按有冲突处理**（宁可多弹一次框），最终由 [moveBatch] 内的
     * 逐项判定兜底 —— 绝不在「不知道」的情况下直接覆盖。
     */
    suspend fun moveBatchConflicts(
        sources: List<String>,
        destinationDirectory: String
    ): List<String> = withContext(Dispatchers.IO) {
        val destNormalized = destinationDirectory.trimEnd('/').ifEmpty { "/" }
        if (isUnsafePath(destNormalized)) return@withContext sources

        val unique = sources.distinct()
        val hits = ArrayList<String>()
        val unresolved = ArrayList<Pair<String, String>>(unique.size)
        for (source in unique) {
            if (isUnsafePath(source)) continue
            val name = File(source).name
            if (name.isEmpty() || name == "." || name == "..") continue
            val destPath = joinPath(destNormalized, name)
            if (moveLocalExists(destPath)) hits += source else unresolved += source to destPath
        }
        if (unresolved.isNotEmpty() && preferRoot()) {
            // 分段只按**目标路径**打包，命中项直接取自段内的 pair —— 不再用段内索引
            // 去索引全局表（那会在多段时错位甚至越界）。
            for (segment in planMoveProbeSegments(unresolved)) {
                val destinations = segment.map { it.second }
                val (_, out) = RootService.runCommandSync(
                    buildDestProbeCommand(destinations),
                    timeoutMs = MOVE_PROBE_TIMEOUT_MS
                )
                val parsed = parseDestProbeOutput(out, destinations.size)
                if (!parsed.done) {
                    // 探测没跑完：按「有冲突」处理，宁可多弹一次框。
                    segment.forEach { hits += it.first }
                    continue
                }
                parsed.hitIndices.forEach { hits += segment[it].first }
            }
        }
        val order = unique.withIndex().associate { (index, path) -> path to index }
        hits.sortedBy { order[it] ?: Int.MAX_VALUE }
    }

    /** 单项在段命令里的字节成本：转义后长度 + 字段分隔空格。 */
    internal fun moveItemCostBytes(item: MovePlanItem, onConflict: MoveDestinationConflict): Int {
        val source = ShellEscapes.escapeShellArg(item.source).toByteArray(Charsets.UTF_8).size
        val destination = ShellEscapes.escapeShellArg(item.destination).toByteArray(Charsets.UTF_8).size
        return if (onConflict == MoveDestinationConflict.RENAME) {
            source + destination +
                ShellEscapes.escapeShellArg(item.renamed).toByteArray(Charsets.UTF_8).size + 3
        } else {
            source + destination + 2
        }
    }

    /**
     * 段命令的固定开销：由构造函数反测，脚本改动后不会与常量漂移。
     *
     * 取**三种策略的最大值**（RENAME 打包三字段、OVERWRITE 脚本最长）：
     * 装箱一律按最大值预算，对任何策略都不会超限，也不依赖「谁更大」的静态假设。
     */
    internal val moveSegmentOverheadBytes: Int by lazy {
        val probe = MovePlanItem("/", "/", "/")
        listOf(
            MoveDestinationConflict.SKIP,
            MoveDestinationConflict.OVERWRITE,
            MoveDestinationConflict.RENAME
        ).maxOf { policy ->
            buildMoveSegmentCommand(listOf(probe), policy).toByteArray(Charsets.UTF_8).size -
                moveItemCostBytes(probe, policy)
        }
    }

    /** 单项单独成段时是否装得下。超长路径无法经 `su -c` 送达，调用方直接判未完成。 */
    internal fun moveItemFitsOneSegment(item: MovePlanItem, onConflict: MoveDestinationConflict): Boolean =
        moveSegmentOverheadBytes + moveItemCostBytes(item, onConflict) <= MOVE_SEGMENT_MAX_BYTES

    /**
     * 按预算装箱，保序不丢项。单项超上限时仍单独成段（是否可发出由调用方判）。
     *
     * @param limit 段字节上限。默认值保持 [MOVE_SEGMENT_MAX_BYTES]，
     *   既有两个调用点（[planMoveSegments] / [planMoveProbeSegments]）用的是尾随 lambda，
     *   会绑定到最后一个形参 `cost`，`limit` 取默认值，零改动。
     *
     * 与 [planDeleteSegments] 的内联装箱同构；删除侧是禁改区，故不强行合并。
     */
    private fun <T> chunkByBytes(
        items: List<T>,
        overhead: Int,
        limit: Int = MOVE_SEGMENT_MAX_BYTES,
        cost: (T) -> Int
    ): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val out = ArrayList<List<T>>()
        var current = ArrayList<T>()
        var bytes = overhead
        for (item in items) {
            val itemCost = cost(item)
            if (current.isNotEmpty() && bytes + itemCost > limit) {
                out += current
                current = ArrayList()
                bytes = overhead
            }
            current += item
            bytes += itemCost
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    internal fun planMoveSegments(
        items: List<MovePlanItem>,
        onConflict: MoveDestinationConflict
    ): List<List<MovePlanItem>> =
        chunkByBytes(items, moveSegmentOverheadBytes) { moveItemCostBytes(it, onConflict) }

    internal fun planMoveProbeSegments(
        targets: List<Pair<String, String>>
    ): List<List<Pair<String, String>>> =
        chunkByBytes(targets, moveSegmentOverheadBytes) {
            ShellEscapes.escapeShellArg(it.second).toByteArray(Charsets.UTF_8).size + 1
        }

    /** 段超时：真正耗时的是跨挂载点的大目录 mv（复制 + 删除），不是命令解析。 */
    internal fun moveSegmentTimeoutMs(itemCount: Int): Long =
        (MOVE_SEGMENT_TIMEOUT_BASE_MS + MOVE_SEGMENT_TIMEOUT_PER_ITEM_MS * itemCount.coerceAtLeast(1))
            .coerceAtMost(MOVE_SEGMENT_TIMEOUT_MAX_MS)

    /**
     * 单段移动命令：一条 `set --` 打包全部字段，一个 while 循环逐项处理。
     *
     * 逐项展开成 `if` 时每项约 300 字节，2900 字节只装 9 项；打包后每项约 92 字节。
     * 每条都有依据：
     * - 全程只用 shell 内建（test / printf / echo）与 mv，su 内每项只 fork 一个 mv；
     * - 目标存在判定放在 `mv` **之前**（SKIP），窗口缩到同一进程内的微秒级；
     * - 回捞用 `[ ! -e ] && [ ! -L ]` + `[ -e ] || [ -L ]`：断链软链也算搬到了；
     * - OVERWRITE 删除同名目录失败时**直接判失败**，绝不下探 mv ——
     *   否则 `mv src dst` 会落进残留目录内部，产生 X/X 嵌套（moveFile 是拒绝的）；
     * - 完成标记**无条件**打印且是最后一句：命令被整段丢弃时不会有它；
     * - 路径一律 [ShellEscapes.escapeShellArg]。
     */
    internal fun buildMoveSegmentCommand(
        items: List<MovePlanItem>,
        onConflict: MoveDestinationConflict
    ): String {
        require(items.isNotEmpty()) { "空段无意义" }
        val sb = StringBuilder(300 + items.size * 96)
        sb.append("set --")
        for (item in items) {
            sb.append(' ').append(ShellEscapes.escapeShellArg(item.source))
            sb.append(' ').append(ShellEscapes.escapeShellArg(item.destination))
            if (onConflict == MoveDestinationConflict.RENAME) {
                sb.append(' ').append(ShellEscapes.escapeShellArg(item.renamed))
            }
        }
        sb.append('\n')
        sb.append("i=0\n")
        when (onConflict) {
            MoveDestinationConflict.SKIP -> {
                sb.append("while [ \$# -ge 2 ]; do\n")
                sb.append("s=\$1; d=\$2; shift 2\n")
                sb.append("if [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ]; then printf 'X:%d\\n' \$i\n")
                sb.append("elif [ -e \"\$d\" ] || [ -L \"\$d\" ]; then printf 'S:%d\\n' \$i\n")
                sb.append("elif mv \"\$s\" \"\$d\" 2>/dev/null && [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ] " +
                    "&& { [ -e \"\$d\" ] || [ -L \"\$d\" ]; }; then printf 'K:%d:d\\n' \$i\n")
                sb.append("else printf 'N:%d\\n' \$i; fi\n")
                sb.append("i=\$((i+1))\n")
                sb.append("done\n")
            }
            MoveDestinationConflict.OVERWRITE -> {
                sb.append("while [ \$# -ge 2 ]; do\n")
                sb.append("s=\$1; d=\$2; shift 2\n")
                sb.append("if [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ]; then printf 'X:%d\\n' \$i\n")
                sb.append("elif [ -d \"\$d\" ]; then\n")
                // 同名文件交给 mv 原子覆盖；同名目录只能先删（rename(2) 不能覆盖非空目录）。
                // 删除失败必须拒绝：残余目录会让 mv 把源搬进它内部（X/X 嵌套）。
                sb.append("if ! rm -rf -- \"\$d\" 2>/dev/null; then printf 'N:%d\\n' \$i\n")
                sb.append("elif mv \"\$s\" \"\$d\" 2>/dev/null && [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ] " +
                    "&& { [ -e \"\$d\" ] || [ -L \"\$d\" ]; }; then printf 'D:%d:d\\n' \$i\n")
                sb.append("else printf 'N:%d\\n' \$i; fi\n")
                sb.append("elif mv \"\$s\" \"\$d\" 2>/dev/null && [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ] " +
                    "&& { [ -e \"\$d\" ] || [ -L \"\$d\" ]; }; then printf 'K:%d:d\\n' \$i\n")
                sb.append("else printf 'N:%d\\n' \$i; fi\n")
                sb.append("i=\$((i+1))\n")
                sb.append("done\n")
            }
            MoveDestinationConflict.RENAME -> {
                sb.append("while [ \$# -ge 3 ]; do\n")
                sb.append("s=\$1; d=\$2; r=\$3; shift 3\n")
                sb.append("if [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ]; then printf 'X:%d\\n' \$i\n")
                sb.append("elif [ -e \"\$d\" ] || [ -L \"\$d\" ]; then\n")
                sb.append("if [ -e \"\$r\" ] || [ -L \"\$r\" ]; then printf 'C:%d\\n' \$i\n")
                sb.append("elif mv \"\$s\" \"\$r\" 2>/dev/null && [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ] " +
                    "&& { [ -e \"\$r\" ] || [ -L \"\$r\" ]; }; then printf 'K:%d:r\\n' \$i\n")
                sb.append("else printf 'N:%d\\n' \$i; fi\n")
                sb.append("elif mv \"\$s\" \"\$d\" 2>/dev/null && [ ! -e \"\$s\" ] && [ ! -L \"\$s\" ] " +
                    "&& { [ -e \"\$d\" ] || [ -L \"\$d\" ]; }; then printf 'K:%d:d\\n' \$i\n")
                sb.append("else printf 'N:%d\\n' \$i; fi\n")
                sb.append("i=\$((i+1))\n")
                sb.append("done\n")
            }
        }
        sb.append("echo ").append(MOVE_SEGMENT_DONE_MARKER).append('\n')
        return sb.toString()
    }

    /** 单段冲突探测命令：内建 test 逐个判，输出命中索引。 */
    internal fun buildDestProbeCommand(destinations: List<String>): String {
        require(destinations.isNotEmpty()) { "空段无意义" }
        val sb = StringBuilder(96 + destinations.size * 48)
        sb.append("set --")
        for (destination in destinations) {
            sb.append(' ').append(ShellEscapes.escapeShellArg(destination))
        }
        sb.append('\n')
        sb.append("i=0\n")
        sb.append("while [ \$# -ge 1 ]; do\n")
        sb.append("if [ -e \"\$1\" ] || [ -L \"\$1\" ]; then printf 'E:%d\\n' \$i; fi\n")
        sb.append("shift 1; i=\$((i+1))\n")
        sb.append("done\n")
        sb.append("echo ").append(MOVE_PROBE_DONE_MARKER).append('\n')
        return sb.toString()
    }

    /** 单段移动输出解析结果：[codes] 每项状态码，[targets] 成功项落点（d=原名 r=改名）。 */
    internal data class MoveSegmentParse(
        val done: Boolean,
        val codes: Array<Char?>,
        val targets: Array<Char?>
    )

    /**
     * 解析单段输出。完成标记按**整行**比对；
     * 状态行格式 `<码>:<段内索引>[:<落点>]`，索引越界 / 未知码一律忽略。
     *
     * 用索引而不是回显路径：路径里可能有 `:`、换行、`OK:` 前缀，
     * 回显会造成解析歧义与字节膨胀，索引天然免疫。
     */
    internal fun parseMoveSegmentOutput(output: String, count: Int): MoveSegmentParse {
        val codes = arrayOfNulls<Char>(count)
        val targets = arrayOfNulls<Char>(count)
        var done = false
        for (line in output.lineSequence()) {
            if (line == MOVE_SEGMENT_DONE_MARKER) {
                done = true
                continue
            }
            if (line.length < 3 || line[1] != ':') continue
            val code = line[0]
            if (code != 'K' && code != 'D' && code != 'X' && code != 'S' && code != 'C' && code != 'N') continue
            val rest = line.substring(2)
            val digits = rest.takeWhile { it.isDigit() }
            val index = digits.toIntOrNull() ?: continue
            if (index !in 0 until count) continue
            codes[index] = code
            val tail = rest.substring(digits.length)
            if (tail.startsWith(":r")) targets[index] = 'r'
            if (tail.startsWith(":d")) targets[index] = 'd'
        }
        return MoveSegmentParse(done, codes, targets)
    }

    /** 探测段解析结果：[hitIndices] 为「目标已存在」的段内索引。 */
    internal data class MoveProbeParse(val done: Boolean, val hitIndices: Set<Int>)

    internal fun parseDestProbeOutput(output: String, count: Int): MoveProbeParse {
        var done = false
        val hits = LinkedHashSet<Int>()
        for (line in output.lineSequence()) {
            if (line == MOVE_PROBE_DONE_MARKER) {
                done = true
                continue
            }
            if (!line.startsWith("E:")) continue
            val index = line.substring(2).trim().toIntOrNull() ?: continue
            if (index in 0 until count) hits += index
        }
        return MoveProbeParse(done, hits)
    }

    /**
     * 把源文件/文件夹**复制**到目标目录（与 [moveFile] 同口径，但保留源）。
     *
     * 供文件页「复制 ->」使用。目标同名冲突按 [onConflict] 处理（默认覆盖）。
     *
     * 旧实现在保护路径上要 4~6 条 su：源/目标/目标同名项各一条类型探测、
     * 覆盖目录时 `delete()` 又一条 `rm -rf`、Java 直拷失败后再一条 `cp` + `test -e`。
     * 现在本地可见零 fork，不可见时那条 su 一次判完「类型 + 冲突 + 拷贝 + 回捞」。
     */
    suspend fun copyToDirectory(
        sourcePath: String,
        destinationDirectory: String,
        onConflict: MoveDestinationConflict = MoveDestinationConflict.OVERWRITE
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(sourcePath) || isUnsafePath(destinationDirectory)) {
            return@withContext Pair(false, "路径包含非法字符")
        }
        val source = File(sourcePath)
        val sourceName = source.name
        if (sourceName.isEmpty() || sourceName == "." || sourceName == "..") {
            return@withContext Pair(false, "源文件名无效")
        }
        val sourceNormalized = sourcePath.trimEnd('/').ifEmpty { "/" }
        val destinationNormalized = destinationDirectory.trimEnd('/').ifEmpty { "/" }
        if (sourceNormalized == destinationNormalized) {
            return@withContext Pair(false, "目标目录不能与源路径相同")
        }
        if (destinationNormalized.startsWith("$sourceNormalized/")) {
            return@withContext Pair(false, "不能复制到自身或子目录")
        }

        val destinationPath = joinPath(destinationNormalized, sourceName)
        // 目标就是源本身：OVERWRITE 会先删同名目录（= 删源）再从一条已不存在的路径复制。
        // RENAME 落到 name_new，语义正确，不拦。
        if (destinationPath == sourceNormalized && onConflict != MoveDestinationConflict.RENAME) {
            return@withContext Pair(false, "目标与源相同，不能覆盖自身")
        }
        val renamedPath = renamedInDestination(sourceName, destinationNormalized)

        // ── 应用域可见性：只回答「本地能看到什么」，看不到的一律交给下面那条 su ──
        val sourceType = moveLocalType(sourcePath)
        val destDirType = moveLocalType(destinationNormalized)
        val destType = moveLocalType(destinationPath)
        if (sourceType == 3) return@withContext Pair(false, "源路径不是普通文件或文件夹")
        if (destDirType != 0 && destDirType != 2) {
            return@withContext Pair(false, "目标路径不是文件夹或不可访问")
        }
        if (RootService.isRootGranted != true) {
            // 无 ROOT 的文案与旧实现逐字一致（旧实现靠 rootType 返回 0 落到同一分支）
            if (sourceType == 0) return@withContext Pair(false, "源文件不存在或不可访问")
            if (destDirType == 0) return@withContext Pair(false, "目标路径不是文件夹或不可访问")
        }

        var finalPath = destinationPath
        var localReady = sourceType != 0 && destDirType == 2
        if (localReady && destType != 0) {
            when (onConflict) {
                MoveDestinationConflict.SKIP ->
                    return@withContext Pair(false, "目标文件夹中已存在同名项目（跳过）")
                MoveDestinationConflict.RENAME -> {
                    finalPath = renamedPath
                    if (moveLocalType(finalPath) != 0) {
                        return@withContext Pair(false, "自动改名后的目标也已存在")
                    }
                }
                MoveDestinationConflict.OVERWRITE -> {
                    // 只有目录目标需要先删：文件目标交给 cp 直接替换（不造「已删未写」的窗口）。
                    if (destType == 2) {
                        if (deleteLocalRecursively(destinationPath)) {
                            // 与被删目录走同一套回收：旧实现由 delete() 间接触发这两件事。
                            // 必须在这里就记账 —— 本地删成功后可能回落 su 拷贝，
                            // 那时脚本看到的 `$f` 已不存在，不会再打覆盖标记。
                            invalidateShsoDirIfAffected(destinationPath)
                            EditHistoryManager.forget(destinationPath)
                        } else {
                            // 应用域删不掉（SELinux / 权限）：交给下面那条 su 的 rm -rf
                            localReady = false
                        }
                    }
                }
            }
        }
        if (localReady) {
            val copiedLocally = runCatching {
                if (sourceType == 2) {
                    source.copyRecursively(File(finalPath), overwrite = true)
                } else {
                    source.copyTo(File(finalPath), overwrite = true)
                }
                // copyRecursively / copyTo 对「合并进已存在目录」都不保证，
                // 仍以「落点现在可见」为判据。
                moveLocalType(finalPath) != 0
            }.getOrDefault(false)
            if (copiedLocally) return@withContext true to "已复制到 $finalPath"
        }

        // ── 一条 su 完成「类型复核 + 冲突策略 + 拷贝 + 回捞」──────────────
        if (RootService.isRootGranted != true) return@withContext Pair(false, "复制失败")
        val (_, out) = RootService.runCommandSync(
            buildCopyToDirectoryCommand(
                sourcePath = sourcePath,
                destinationDirectory = destinationNormalized,
                destinationPath = destinationPath,
                renamedPath = renamedPath,
                onConflict = onConflict
            ),
            timeoutMs = COPY_TO_DIRECTORY_TIMEOUT_MS
        )
        val parsed = parseCopyToDirectoryOutput(out)
        if (!parsed.done) {
            // 缺完成标记 = 这条 su 没送达（被拒 / 超时 / 命令整段丢弃）：状态未知
            return@withContext Pair(false, "复制失败：ROOT 命令未完成")
        }
        if (parsed.removedDirectory) {
            // 脚本删掉了同名目录：标记失效 + 历史回收，两件事都必须发生。
            invalidateShsoDirIfAffected(destinationPath)
            EditHistoryManager.forget(destinationPath)
        }
        val detail = out.lineSequence()
            .filterNot {
                it.startsWith("RC:") || it.startsWith("FP:") ||
                    it == COPY_DIR_DONE_MARKER || it == COPY_DIR_OVERWROTE_MARKER
            }
            .joinToString("\n")
            .trim()
        when (parsed.code) {
            0 -> return@withContext true to "已复制到 ${parsed.path ?: destinationPath}"
            1 -> return@withContext Pair(false, "源文件不存在或不可访问")
            2 -> return@withContext Pair(false, "源路径不是普通文件或文件夹")
            3 -> return@withContext Pair(false, "目标路径不是文件夹或不可访问")
            4 -> return@withContext Pair(false, "目标文件夹中已存在同名项目（跳过）")
            5 -> return@withContext Pair(false, "自动改名后的目标也已存在")
            6 -> return@withContext Pair(false, "无法覆盖目标同名目录")
            else -> return@withContext Pair(
                false,
                if (detail.isEmpty()) "复制失败" else "复制失败: $detail"
            )
        }
    }

    /**
     * 探测目标目录中是否已存在与源同名的项目（供 UI 在移动前弹出冲突决策）。
     */
    suspend fun moveDestinationCollides(sourcePath: String, destinationDirectory: String): Boolean = withContext(Dispatchers.IO) {
        if (isUnsafePath(sourcePath)) return@withContext false

        fun localExists(path: String): Boolean = try { File(path).exists() } catch (_: Exception) { false }
        fun rootExists(path: String): Boolean {
            val escaped = RootService.escapeShellArg(path)
            val (code, output) = RootService.runCommandSync("test -e $escaped && echo yes")
            return code == 0 && output.contains("yes")
        }

        val sourceName = File(sourcePath).name
        if (sourceName.isEmpty() || sourceName == "." || sourceName == "..") return@withContext false
        val destNorm = destinationDirectory.trimEnd('/').ifEmpty { "/" }
        if (isUnsafePath(destNorm)) return@withContext false
        val destPath = if (destNorm == "/") "/$sourceName" else "$destNorm/$sourceName"
        localExists(destPath) || (RootService.isRootGranted == true && rootExists(destPath))
    }

suspend fun delete(path: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(path)) {
            return@withContext Pair(false, "路径包含非法字符")
        }
        invalidateShsoDirIfAffected(path)
        // 三条删除成功路径共用：文件没了，其编辑历史也不该再留在 SharedPreferences 里
        // （删目录时连带其下所有文件，见 EditHistoryManager.forget 的前缀匹配）。
        fun removed(): Pair<Boolean, String> {
            EditHistoryManager.forget(path)
            return true to "删除成功"
        }
        // 本地能删就先删：unlink/rmdir 只有一次系统调用，而每次 su 是 145~173ms。
        // 语义不变 —— File.delete() 不递归、不跟随软链；非空目录/受保护路径立即失败，
        // 下面照旧走 su 与 Java 兜底。与 deleteBatch 阶段 1 同一条判据。
        if (deleteLocalOnce(path)) return@withContext removed()
        // ROOT 已授权时优先用 su 删除（可操作受保护/系统路径）；
        // 未授权或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
        if (preferRoot()) {
            val escaped = RootService.escapeShellArg(path)
            val (code, _) = RootService.runCommandSync("rm -rf $escaped")
            if (code == 0) return@withContext removed()
        }
        try {
            val targetFile = File(path)
            // 符号链接必须只删链接本身，绝不能跟随。
            // `File.isDirectory` 走 stat，会跟随链接；`deleteRecursively()` 对目录链接
            // 同样会下潜，于是「删除一个指向别处的链接」会静默清空链接目标整棵树
            // （ROOT 不可用 + 用户删的是 /sdcard 下自己放的链接时必现，且不可撤销）。
            if (Files.isSymbolicLink(targetFile.toPath())) {
                val ok = targetFile.delete()   // 删链接本身，不动目标
                if (ok) return@withContext removed()
                return@withContext Pair(false, "删除符号链接失败")
            }
            val ok = if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
            if (ok) return@withContext removed()
        } catch (_: Exception) {
        }
        Pair(false, "删除失败")
    }


    /**
     * 在同级目录里找首个不存在的 `<base>_<n><ext>` 目标名。
     *
     * 纯函数，便于单测。**只做词法构造，不碰文件系统** —— 实际占用检查在
     * [copyFile] 里用单条 shell 完成（见该函数注释）。

     */
    internal fun copyCandidatePath(parent: String, base: String, suffix: String, n: Int): String {
        val name = "${base}_$n$suffix"
        return if (parent.endsWith("/")) "$parent$name" else "$parent/$name"
    }

    /**
     * 把文件名拆成「基名 / 后缀」两段，供拷贝重命名使用。
     *
     * 点开头的文件名（`.env` / `.gitignore` / `.bashrc`）必须整体作为基名、后缀留空：
     * `File.nameWithoutExtension` 对它们返回**空串**（`lastIndexOf('.') == 0` →
     * `substring(0, 0)`），直接用会产出 `_0.env`，前导点丢失。同文件
     * [moveFile] 的 `renamedDestPath` 与 `ExternalOpen.reserveUniqueFile` 都用
     * `dot > 0` 规避过，这里是遗漏。
     */
    internal fun splitCopyName(rawName: String): Pair<String, String> {
        val dot = rawName.lastIndexOf('.')
        return if (dot <= 0) {
            rawName to ""
        } else {
            rawName.substring(0, dot) to rawName.substring(dot)
        }
    }

    /**
     * 拷贝文件到同级目录，自动追加递增序号后缀（如 file.txt → file_0.txt → file_1.txt）。
     * 序号插入在扩展名之前（无扩展名则直接加在末尾）。仅用于文件（文件夹不调用）。
     *
     * ## 重名查找收敛为单次 su
     *
     * 原实现是 `do { ... } while (pathExists(destPath))`，而 [pathExists] 每次都
     * fork 一个 `su -c test -e`（含独立读线程 + waitFor）。目录里已有 N 个同名副本时
     * 就是 N+1 次 su fork：Magisk/KernelSU 每次都要做一次 IPC 与授权检查，
     * 连续多选拷贝时叠加成明显的「点了没反应」。
     *
     * 改为：把「找一个空位 + 拷贝」放进**同一条 shell**（`cp -n` 依次尝试），
     * 无论重名多少次都只 fork 一次。`cp -n` 在目标已存在时返回 0 且不写入，
     * 因此用「先 `rm -f` 探测再 `cp`」的写法要小心；这里改用
     * `test -e` 判空 + `set -C`（O_EXCL）占位的组合保证不覆盖已有文件。
     */
    suspend fun copyFile(sourcePath: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(sourcePath)) {
            return@withContext Pair(false, "源路径包含非法字符")
        }
        val srcFile = File(sourcePath)
        val parent = srcFile.parent ?: "/"
        val (base, suffix) = splitCopyName(srcFile.name)

        if (preferRoot()) {
            // 单条 shell 完成「找空位 + 拷贝」：i 从 0 起，先用 `set -C`（O_EXCL）原子占位，
            // 占位成功即说明该名字此前不存在，随后 `cp` 覆盖这个空文件（我们自己占的）。
            // 全程最多一次 su fork，不再出现「重名 N 次就 fork N+1 次」。
            //
            // 注意：`base` / `suffix` 来自文件名，**必须**各自过 escapeShellArg 后再拼进
            // 命令；shell 里 `'名字'_$n'.txt'` 这种「引号片段 + 变量」是合法拼接，
            // 转义后的引号不会破坏脚本结构。
            val escapedSource = RootService.escapeShellArg(sourcePath)
            val escapedBase = RootService.escapeShellArg(base)
            val escapedSuffix = RootService.escapeShellArg(suffix)
            val escapedParent = RootService.escapeShellArg(if (parent.endsWith("/")) parent.dropLast(1) else parent)
            val script = buildString {
                append("n=0; ")
                append("while [ \$n -lt $COPY_NAME_PROBE_LIMIT ]; do ")
                append("d=$escapedParent/$escapedBase" + '"' + "_" + '"' + "\$n$escapedSuffix; ")
                append("if ( set -C; : > \"\$d\" ) 2>/dev/null; then ")
                append("if cp -p $escapedSource \"\$d\" && chmod a-s,go-w \"\$d\"; then ")
                append("echo \"OK \$d\"; exit 0; fi; rm -f -- \"\$d\"; exit 1; fi; ")
                append("n=\$((n+1)); ")
                append("done; echo NOMATCH; exit 2")
            }
            val (code, out) = RootService.runCommandSync(script)
            val line = out.lineSequence().firstOrNull { it.startsWith("OK ") }?.trim()
            if (code == 0 && line != null) {
                return@withContext Pair(true, line.removePrefix("OK ").trim())
            }
            // shell 路径失败（含 NOMATCH）时回退到 Java 拷贝，行为与原先一致
        }

        // Java 路径：同样需要重名查找，但走 File.exists() 不 fork su
        //
        // 单次 File.exists() 即可：此前写成 `File(destPath).exists() || pathExistsQuiet(destPath)`，
        // 而后者函数体就是 File(path).exists() —— `||` 只在左为 true 时省下右，
        // 左为 false（正是需要继续探测下一个名字的情况）时必然**再做一次完整 stat**。
        // 同名副本 N 个 → 2N 次系统调用。
        //
        // 探测口径必须与下面的 shell 路径一致（都试 n=0..COPY_NAME_PROBE_LIMIT-1）：
        // 用 `n++ 后判 n>=LIMIT` 会在候选名恰为空闲时也报「重名过多」，比 shell 少试一个名。
        var n = 0
        var destPath = copyCandidatePath(parent, base, suffix, n)
        while (File(destPath).exists()) {
            n++
            if (n >= COPY_NAME_PROBE_LIMIT) {
                return@withContext Pair(false, "同级重名过多，未找到可用的目标名")
            }
            destPath = copyCandidatePath(parent, base, suffix, n)
        }

        try {
            val destFile = File(destPath)
            srcFile.inputStream().use { ins ->
                destFile.outputStream().use { outs -> ins.copyTo(outs) }
            }
            // 与 ROOT 路径的 chmod 口径对齐：按来源 mode「只减不增」——
            // 保留 rwx（含执行位），只清 setuid/setgid 与 group/other 写位。
            // cp -p 在 Java 路径不可用，权限要靠这里补；否则拷出的 .sh/.so 一律丢失执行位。
            runCatching {
                Os.chmod(destPath, Os.stat(sourcePath).st_mode and COPY_MODE_MASK)
            }
            return@withContext Pair(true, destPath)
        } catch (_: Exception) {
        }
        Pair(false, "拷贝文件失败")
    }

    /**
     * 在指定目录新建空文件。ROOT 已授权时优先用 su 创建（可操作受保护/系统路径）；
     * 未授权或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
     * @return Pair(成功, 消息/最终路径)
     */
    suspend fun createEmptyFile(dirPath: String, fileName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(dirPath)) {
            return@withContext Pair(false, "路径包含非法字符")
        }
        val name = fileName.trim()
        if (name.isEmpty()) {
            return@withContext Pair(false, "文件名不能为空")
        }
        // 拒绝路径分隔符与非法字符（与 rename 同级校验，防止越目录写入）
        if (isUnsafeFileName(name)) {
            return@withContext Pair(false, "文件名不能包含路径分隔符或非法字符")
        }

        val base = if (dirPath.endsWith("/")) dirPath else "$dirPath/"
        val fullPath = "$base$name"

        // 已存在则中止，避免覆盖既有文件
        if (pathExists(fullPath)) {
            return@withContext Pair(false, "文件已存在: $name")
        }

        if (preferRoot()) {
            val escaped = RootService.escapeShellArg(fullPath)
            val (code, _) = RootService.runCommandSync("touch $escaped && chmod 644 $escaped")
            if (code == 0) return@withContext Pair(true, fullPath)
        }
        try {
            val targetFile = File(fullPath)
            if (targetFile.createNewFile()) return@withContext Pair(true, fullPath)
        } catch (_: Exception) {
        }
        Pair(false, "创建文件失败")
    }

    /**
     * 在指定目录新建文件夹。ROOT 已授权时优先用 su 创建（可操作受保护/系统路径）；
     * 未授权或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
     * @return Pair(成功, 消息/最终路径)
     */
    suspend fun createFolder(dirPath: String, folderName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(dirPath)) {
            return@withContext Pair(false, "路径包含非法字符")
        }
        val name = folderName.trim()
        if (name.isEmpty()) {
            return@withContext Pair(false, "文件夹名不能为空")
        }
        // 文件夹名同样拒绝路径分隔符与非法字符（防止越目录写入）
        if (isUnsafeFileName(name)) {
            return@withContext Pair(false, "文件夹名不能包含路径分隔符或非法字符")
        }

        val base = if (dirPath.endsWith("/")) dirPath else "$dirPath/"
        val fullPath = "$base$name"

        // 已存在则中止，避免覆盖既有文件/目录
        if (pathExists(fullPath)) {
            return@withContext Pair(false, "已存在: $name")
        }

        if (preferRoot()) {
            val escaped = RootService.escapeShellArg(fullPath)
            val (code, _) = RootService.runCommandSync("mkdir -p $escaped && chmod 777 $escaped")
            if (code == 0) return@withContext Pair(true, fullPath)
        }
        try {
            val targetDir = File(fullPath)
            if (targetDir.mkdirs()) return@withContext Pair(true, fullPath)
        } catch (_: Exception) {
        }
        Pair(false, "创建文件夹失败")
    }

    // ────────────────────────────────────────────────────────────────────
    // 批量删除
    //
    // 旧调用路径：FilePage 逐项 RootFileManager.delete()，每项一次
    // ProcessBuilder("su","-c")，145~173ms/项，400 项约 1 分钟；
    // 而真正的 unlink 成本不到 1ms —— 99% 的时间花在反复启动 su 上。
    // 这里把「应用自己能删的项」全部本地摘掉，剩余项按 UTF-8 字节分段，
    // 每段一条 rm 带整段路径，su 次数从 N 降到段数。
    // ────────────────────────────────────────────────────────────────────

    /**
     * 单段命令的 **UTF-8 字节**上限（量整条命令，不是路径之和）。
     *
     * `su -c` 对单条命令在 4090 字节附近会整段丢弃（`TermuxPrefix` 同源结论，
     * 见其打包处注释；那边取 3000，本命令无段头，取 2900 留 1KB 以上余量）。
     * 中文 1 字 3 字节，用 `String.length` 会低估到三分之一，必须按 UTF-8 字节累加。
     *
     * **本实现不看 rc**：脚本最后一句是 echo，rc 恒 0；命令被丢弃时 rc 可能是 0 或 255。
     * 只认段末完成标记，两种表现都落到「未完成」，口径统一。
     */
    internal const val DELETE_SEGMENT_MAX_BYTES = 2900

    /** 段完成标记：**整行**精确比对。缺标记 = 整段没执行（su 拒绝 / 超时 / 命令被丢弃）。 */
    internal const val DELETE_SEGMENT_DONE_MARKER = "SHSO_BATCH_OK"

    /** 残留回捞前缀。传入路径都是绝对路径，`F:` 不会与路径本身混淆（见 [parseDeleteSegmentOutput]）。 */
    private const val DELETE_SEGMENT_REMAIN_PREFIX = "F:"

    /** 段超时基线 + 每项增量，封顶与 [RootService.runCommandSync] 默认值齐平。 */
    private const val DELETE_SEGMENT_TIMEOUT_BASE_MS = 15_000L
    private const val DELETE_SEGMENT_TIMEOUT_PER_PATH_MS = 300L
    private const val DELETE_SEGMENT_TIMEOUT_MAX_MS = 120_000L

    /**
     * 连续失败多少段就停发后续段。
     *
     * 取 2 而不是 1：一段超时往往只是段内有大目录，su 本身没坏，
     * 一失败就停会把后面几百个普通文件误判为「未完成」。
     * 取 2 也就把「su 挂死」的最坏等待封在 2 个段超时内。
     */
    private const val DELETE_SEGMENT_MAX_CONSECUTIVE_FAILURES = 2

    /** 段命令的固定开销（不含路径）。由构造函数反测，脚本改动后不会与常量漂移。 */
    internal val deleteSegmentOverheadBytes: Int by lazy {
        // 占位路径 "/" 与其前导空格共 2 字节
        buildDeleteSegmentCommand(listOf("/")).toByteArray(Charsets.UTF_8).size - 2
    }

    /** 段超时：真正耗时的不是命令本身，而是段内可能有多个大目录要递归。 */
    internal fun deleteSegmentTimeoutMs(pathCount: Int): Long =
        (DELETE_SEGMENT_TIMEOUT_BASE_MS + DELETE_SEGMENT_TIMEOUT_PER_PATH_MS * pathCount.coerceAtLeast(1))
            .coerceAtMost(DELETE_SEGMENT_TIMEOUT_MAX_MS)

    /**
     * 批量删除结果：**三档失败必须分开**，不能压成「成功/失败」。
     *
     * - [deleted]     已确认不存在（本地试删 / su 回捞未见 / 兜底成功，任一路径）；
     * - [remaining]   su 明确回捞到「仍在」，且兜底也没删掉；
     * - [unprocessed] 段命令没跑完（su 被拒 / 超时 / 命令被整段丢弃）或单项超长，
     *                 **状态未知**：不许计成功，也不该当成用户的错；
     * - [rejected]    上游 [isUnsafePath] 拒掉的路径，连尝试都没有。
     *
     * 「删除失败 / 路径包含非法字符」与 [delete] 逐字一致，
     * 避免同一操作在两个入口叫不同名字。
     */
    data class BatchDeleteResult(
        val deleted: List<String> = emptyList(),
        val remaining: List<String> = emptyList(),
        val unprocessed: List<String> = emptyList(),
        val rejected: List<String> = emptyList(),
    ) {
        val failedCount: Int get() = remaining.size + unprocessed.size + rejected.size

        /** 逐项失败明细，沿用 FilePage 旧文案格式「名称（原因）」。 */
        fun failureLines(): List<String> {
            val out = ArrayList<String>(failedCount)
            remaining.forEach { out += "${File(it).name}（删除失败）" }
            unprocessed.forEach { out += "${File(it).name}（未完成）" }
            rejected.forEach { out += "${File(it).name}（路径包含非法字符）" }
            return out
        }
    }

    /** 单段输出解析结果：[done] 是否见到完成标记，[remaining] 为本段仍存在的路径。 */
    internal data class DeleteSegmentParse(val done: Boolean, val remaining: Set<String>)

    /**
     * 批量删除：先整批本地试删，只剩本地删不掉的项才进 su，且**分段批量**执行。
     *
     * 三个阶段，语义与逐项调用 [delete] **等价**：
     * 1. 本地 `File.delete()`：unlink / rmdir，**不递归、不跟随软链**；
     * 2. 剩余项分段进 su，一条 `rm -rf -- "$@"` 带走整段全部路径；
     * 3. su 之后仍未确认删除的项落回 Java 兜底（软链守卫 + 递归），
     *    与 [delete] 尾段同一条路径 —— 非 ROOT 设备删非空目录必须靠它。
     *
     * 失败原因的**表达能力会真实退化**：没有逐项 errno 了，
     * 只剩「删除失败 / 未完成 / 路径包含非法字符」三档，取舍见施工文档 §8.1。
     */
    suspend fun deleteBatch(paths: List<String>): BatchDeleteResult = withContext(Dispatchers.IO) {
        val unique = paths.distinct()
        if (unique.isEmpty()) return@withContext BatchDeleteResult()

        val deleted = ArrayList<String>(unique.size)
        val remaining = ArrayList<String>()
        val unprocessed = ArrayList<String>()
        val rejected = ArrayList<String>()

        // 唯一的「计成功」出口：历史回收与计数必须同时发生。
        // 任何新增的成功路径只要绕过它，就会留下永不回收的历史键（键名即绝对路径）。
        fun markDeleted(p: String) {
            EditHistoryManager.forget(p)
            deleted += p
        }

        // ── 阶段 1：整批本地试删（零 fork）─────────────────────────────
        // 旧实现只要已授权 ROOT 就无条件先 su：连 /sdcard 上应用自己就能 unlink
        // 的文件也各起一个 su 进程。这一段把「本来就能做」的项全部摘出去。
        val pending = ArrayList<String>(unique.size)
        for (p in unique) {
            if (isUnsafePath(p)) {
                rejected += p
                continue
            }
            // 与 delete() 一致：标记失效不看删除结果 —— 失败也只多跑一次幂等 mkdir。
            // 必须**逐路径**调：函数体是 `if (path == DEFAULT_SHSO_DIR)` 条件判定，
            // 批入口只调一次、传第一个路径，会在 shso 目录不在首位时漏。
            invalidateShsoDirIfAffected(p)
            if (deleteLocalOnce(p)) markDeleted(p) else pending += p
        }

        // ── 阶段 2：分段批量 su ───────────────────────────────────────
        // 空批连首次 ROOT 状态探测都省掉。
        val stillThere = ArrayList<String>()
        if (pending.isNotEmpty() && preferRoot()) {
            val sendable = ArrayList<String>(pending.size)
            for (p in pending) {
                // 单项本身装不进一段：发出去只会被 su 整段丢弃，直接判未完成。
                if (deletePathFitsOneSegment(p)) sendable += p else unprocessed += p
            }
            var consecutiveFailures = 0
            for (segment in planDeleteSegments(sendable)) {
                if (consecutiveFailures >= DELETE_SEGMENT_MAX_CONSECUTIVE_FAILURES) {
                    unprocessed += segment
                    continue
                }
                val (_, out) = RootService.runCommandSync(
                    buildDeleteSegmentCommand(segment),
                    timeoutMs = deleteSegmentTimeoutMs(segment.size)
                )
                val parsed = parseDeleteSegmentOutput(out, segment)
                if (!parsed.done) {
                    // 缺完成标记 = 这一段根本没执行：su 停在授权窗、被拒、超时被杀，
                    // 或命令超长被整段丢弃。判「未完成」，**绝不**计成功。
                    // 退出码在这里没有信息量：脚本最后一句是 echo，恒为 0。
                    unprocessed += segment
                    consecutiveFailures++
                    continue
                }
                consecutiveFailures = 0
                for (p in segment) {
                    if (p in parsed.remaining) stillThere += p else markDeleted(p)
                }
            }
        } else {
            // 未授权 / 探测超时：与 delete() 一样直接落 Java 兜底。
            stillThere += pending
        }

        // ── 阶段 3：Java 兜底（与 delete() 尾段同一语义）────────────────
        for (p in stillThere) {
            if (deleteLocalRecursively(p)) markDeleted(p) else remaining += p
        }
        // 「未完成」状态未知：兜底能删掉算成功；删不掉**仍归未完成**。
        // 绝不因为应用侧 exists() 为 false 就断言已删 —— 受保护路径本地恒 false，
        // 而 deleteRecursively() 对不存在的路径恰好返回 true（见 deleteLocalRecursively
        // 的分支写法：不存在时走 f.delete()，不会走到递归）。
        val stillUnprocessed = ArrayList<String>()
        for (p in unprocessed) {
            if (deleteLocalRecursively(p)) markDeleted(p) else stillUnprocessed += p
        }

        // 结果按入参顺序输出，UI 明细与测试断言都稳定
        val order = unique.withIndex().associate { (i, p) -> p to i }
        fun sorted(xs: List<String>): List<String> = xs.sortedBy { order[it] ?: Int.MAX_VALUE }
        BatchDeleteResult(
            deleted = sorted(deleted),
            remaining = sorted(remaining),
            unprocessed = sorted(stillUnprocessed),
            rejected = sorted(rejected),
        )
    }

    /**
     * 单段删除命令：一条 `rm -rf` 带走整批路径，回捞与标记全用 shell 内建（零 fork）。
     *
     * 每条都有依据：
     * - 命令体只用一条 rm 带整批路径；若写成逐项循环的 rm，su 内仍每项 fork 一个 rm。
     * - `--` 终止选项解析：路径可能以 `-` 开头。
     * - `2>/dev/null` 只吞 rm 的诊断：`runCommandSync` 是 `redirectErrorStream(true)`
     *   （`RootService.kt:671`），不吞的话
     *   `rm: /proc/version: Operation not permitted` 会混进输出。
     * - **顺序即正确性**：全部 `F:` 行都打印在标记**之前**，故「标记在 ⇒ 回捞完整」。
     *   输出被 4M 上限截断、或读线程未退出时，缺的是标记，不会缺 `F:` 行。
     * - 完成标记**无条件**打印且是最后一句：命令被整段丢弃时不会有它。
     * - 路径侧一律 [ShellEscapes.escapeShellArg]，与 `RootService.escapeShellArg` 同一实现。
     */
    internal fun buildDeleteSegmentCommand(paths: List<String>): String {
        require(paths.isNotEmpty()) { "空段无意义" }
        val sb = StringBuilder(64 + paths.size * 32)
        sb.append("set --")
        for (p in paths) sb.append(' ').append(ShellEscapes.escapeShellArg(p))
        sb.append('\n')
        sb.append("rm -rf -- \"\$@\" 2>/dev/null\n")
        sb.append("for p in \"\$@\"; do\n")
        sb.append("if [ -e \"\$p\" ] || [ -L \"\$p\" ]; then printf 'F:%s\\n' \"\$p\"; fi\n")
        sb.append("done\n")
        sb.append("echo ").append(DELETE_SEGMENT_DONE_MARKER).append('\n')
        return sb.toString()
    }

    /** 单项在命令里的字节成本：转义后长度 + 1 个前导空格。 */
    private fun deletePathCostBytes(path: String): Int =
        ShellEscapes.escapeShellArg(path).toByteArray(Charsets.UTF_8).size + 1

    /**
     * 按 UTF-8 字节装箱：每段命令总字节 ≤ [DELETE_SEGMENT_MAX_BYTES]，**顺序不变**。
     *
     * 纯词法函数，不碰文件系统，便于 JVM 单测（中文路径 / 超长单项 / 空集）。
     * 单项超上限时仍单独成段返回 —— 这里绝不静默丢路径，是否可发送由调用方判。
     */
    internal fun planDeleteSegments(paths: List<String>): List<List<String>> {
        if (paths.isEmpty()) return emptyList()
        val overhead = deleteSegmentOverheadBytes
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var bytes = overhead
        for (p in paths) {
            val cost = deletePathCostBytes(p)
            if (current.isNotEmpty() && bytes + cost > DELETE_SEGMENT_MAX_BYTES) {
                out += current
                current = ArrayList()
                bytes = overhead
            }
            current += p
            bytes += cost
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** 单项单独成段时是否装得下。超长路径无法经 `su -c` 送达，调用方直接判未完成。 */
    internal fun deletePathFitsOneSegment(path: String): Boolean =
        deleteSegmentOverheadBytes + deletePathCostBytes(path) <= DELETE_SEGMENT_MAX_BYTES

    /**
     * 解析单段输出。完成标记按**整行**比对，避免 `XSHSO_BATCH_OK` 之类子串误判成功；
     * 残留只认本段真正发过的路径，别的段的 `F:` 行不会污染本段结果。
     *
     * 路径含 `F:` 不会混淆：只剥一层前缀，剩余部分必须精确等于发过的路径。
     * 路径以 `SHSO_BATCH_OK` 结尾也不会：它只能以 `F:` 行出现，不会是裸标记行。
     */
    internal fun parseDeleteSegmentOutput(output: String, sent: List<String>): DeleteSegmentParse {
        val sentSet = sent.toHashSet()
        var done = false
        val remaining = LinkedHashSet<String>()
        for (line in output.lineSequence()) {
            if (line == DELETE_SEGMENT_DONE_MARKER) {
                done = true
                continue
            }
            if (!line.startsWith(DELETE_SEGMENT_REMAIN_PREFIX)) continue
            val path = line.substring(DELETE_SEGMENT_REMAIN_PREFIX.length)
            if (path in sentSet) remaining += path
        }
        return DeleteSegmentParse(done, remaining)
    }

    /**
     * 本地单次删除：`unlink(2)` / `rmdir(2)`。
     *
     * **不递归、不跟随软链**：`File.delete()` 对软链删的是链接本身；对非空目录
     * 立即 ENOTEMPTY 失败（交给 su 或递归兜底）。因此这里不可能像
     * `deleteRecursively()` 那样顺着目录链接清空别人的树。
     */
    private fun deleteLocalOnce(path: String): Boolean = try {
        File(path).delete()
    } catch (_: Exception) {
        false
    }

    /**
     * Java 兜底：**分支顺序即安全性**，与 [delete] 尾段同义。
     *
     * 不能简化成 `File(path).deleteRecursively()`：
     * 标准库实现是 `(it.delete() || !it.exists()) && …`，
     * 对**不存在**的路径返回 **true**。受保护路径（应用 stat 不到）恰好命中这一条，
     * 于是「根本没删」会被记成成功，「缺标记绝不许多成功」的判据当场失效。
     * 先判链接、再判目录、其余单次 delete，即可让「返回 true」只出现在
     * 「应用确实完成了删除」这一种情形。
     */
    private fun deleteLocalRecursively(path: String): Boolean = try {
        val f = File(path)
        if (Files.isSymbolicLink(f.toPath())) {
            f.delete()                 // 删链接本身，不动目标
        } else if (f.isDirectory) {
            f.deleteRecursively()      // 真实目录才递归
        } else {
            f.delete()                 // 其余（含不存在）：单次 delete，不存在返回 false
        }
    } catch (_: Exception) {
        false
    }
    // ────────────────────────────────────────────────────────────────────
    // 添加到 shso：一条 su 完成「建目录 + 不覆盖判定 + 拷贝 + chmod + 可选删源」
    //
    // 旧路径 3~4 条 su，最贵的一条是 `destinationExists` 的 root `test -e`：
    // 目标在 /data/adb 下应用 uid 看不到，本地判定恒 false，
    // 于是「不覆盖」这条判据每次都要单独 fork 一个 su 才能回答一个 yes/no。
    // 判据并入拷贝脚本后，窗口从「两条 su 之间」缩到「同一进程内」。
    // ────────────────────────────────────────────────────────────────────

    /** 单条「添加到 shso」命令的 UTF-8 字节上限（与 [MOVE_SEGMENT_MAX_BYTES] 同口径）。 */
    internal const val ADD_TO_SHSO_MAX_BYTES = 2900

    /** 完成标记：**整行**精确比对。缺它 = 这条 su 没送达（被拒 / 超时 / 命令整段丢弃）。 */
    internal const val ADD_TO_SHSO_DONE_MARKER = "SHSO_ADD_OK"

    /**
     * 目录递归放权命令。
     *
     * 单列为常量的两个原因：
     *  ① 目录分支用递归版、文件分支用非递归版，两处只差一个引用，
     *     不会再有人改一侧时漏掉 `-R`；
     *  ② `RootFileManagerEscapingTest.复制目录必须递归改权限` 钉的是源码里的
     *     字面量 `"chmod -R 777"`。旧实现靠 addFileToShso 里那行三元表达式承载，
     *     合并进 shell 之后必须仍有一处 Kotlin 字面量，否则该断言失去锚点，
     *     只能被改成恒真的空断言。
     */
    internal const val CHMOD_RECURSIVE_777 = "chmod -R 777"

    private const val ADD_SHSO_MKDIR_FAILED = 1
    private const val ADD_SHSO_DEST_EXISTS = 2
    private const val ADD_SHSO_COPY_FAILED = 3
    private const val ADD_SHSO_DELETE_SOURCE_FAILED = 9

    /** 解析结果：[code] 为 null 表示脚本没给出 `RC:` 行（按未完成处理）。 */
    internal data class AddToShsoParse(val done: Boolean, val code: Int?)

    /**
     * 生成「添加到 shso」的命令。
     *
     * 全脚本只有**一个终止点**（末尾打印 `RC:` 与完成标记），不用 early exit：
     * 任何一处提前 `exit` 只要漏打标记，调用方就会把已成功的项判成未完成。
     *
     * 每条都有依据：
     * - 三条路径各走一次 `set --`，脚本内用 `$1/$2/$3` —— 深路径重复四次会直接撞上 su 的长度上限；
     * - `[ -e "$3" ] || [ -L "$3" ]`：断链软链也算「已存在」（旧的 root `test -e` 会漏）；
     * - 目录判定用 shell 的 `[ -d "$1" ]`（root 视角），
     *   不用应用侧 `File.isDirectory` —— 后者对受保护源恒 false，
     *   旧实现因此给目录用了**非递归** chmod（隐藏缺陷）；
     * - 全脚本**不吞 stderr**：`runCommandSync` 合并了 stderr，失败文案要带真实原因；
     * - `mkdir` 失败才判致命（rc=1），`chmod` 失败照旧忽略 —— 与旧实现「返回值丢弃」同口径，
     *   避免「目录建成了却报『无法创建目标文件夹』」；
     * - `rm -rf -- "$1"` 只在 `autoDeleteSource` 时**生成**（不是运行时分支），省字节也不会误删。
     */
    internal fun buildAddToShsoCommand(
        sourcePath: String,
        targetDir: String,
        destinationPath: String,
        independentFolder: Boolean,
        autoDeleteSource: Boolean,
        ensureDir: Boolean = true,
        copy: Boolean = true
    ): String {
        val sb = StringBuilder(520)
        sb.append("set --")
            .append(' ').append(ShellEscapes.escapeShellArg(sourcePath))
            .append(' ').append(ShellEscapes.escapeShellArg(targetDir))
            .append(' ').append(ShellEscapes.escapeShellArg(destinationPath))
            .append('\n')
        sb.append("rc=0\n")
        if (ensureDir) {
            sb.append("mkdir -p \"\$2\" || rc=").append(ADD_SHSO_MKDIR_FAILED).append('\n')
            sb.append("if [ \$rc = 0 ]; then chmod 777 \"\$2\"; fi\n")
            if (independentFolder) {
                // 基目录也要放开：只把子目录设成 777 时，
                // 其他应用仍穿不过 0755 的 /data/adb/shso。
                sb.append("if [ \$rc = 0 ]; then chmod 777 ")
                    .append(ShellEscapes.escapeShellArg(DEFAULT_SHSO_DIR)).append("; fi\n")
            }
        }
        if (copy) {
            // 「把已在 shso 里的文件再加一次」与旧 destinationExists 的 newPath==oldPath
            // 例外同义：不判占用，让 cp 自己报错（旧行为就是 cp 失败）。
            if (destinationPath.trimEnd('/') != sourcePath.trimEnd('/')) {
                sb.append("if [ \$rc = 0 ] && { [ -e \"\$3\" ] || [ -L \"\$3\" ]; }; then rc=")
                    .append(ADD_SHSO_DEST_EXISTS).append("; fi\n")
            }
            sb.append("if [ \$rc = 0 ]; then\n")
            sb.append("if [ -d \"\$1\" ]; then\n")
            sb.append("cp -r \"\$1\" \"\$3\" || rc=").append(ADD_SHSO_COPY_FAILED).append('\n')
            sb.append("if [ \$rc = 0 ]; then ").append(CHMOD_RECURSIVE_777)
                .append(" \"\$3\" || rc=").append(ADD_SHSO_COPY_FAILED).append("; fi\n")
            sb.append("else\n")
            sb.append("cp \"\$1\" \"\$3\" || rc=").append(ADD_SHSO_COPY_FAILED).append('\n')
            sb.append("if [ \$rc = 0 ]; then chmod 777 \"\$3\" || rc=")
                .append(ADD_SHSO_COPY_FAILED).append("; fi\n")
            sb.append("fi\nfi\n")
            if (autoDeleteSource) {
                sb.append("if [ \$rc = 0 ]; then rm -rf -- \"\$1\" || rc=")
                    .append(ADD_SHSO_DELETE_SOURCE_FAILED).append("; fi\n")
            }
        }
        sb.append("echo \"RC:\$rc\"\n")
        sb.append("echo ").append(ADD_TO_SHSO_DONE_MARKER).append('\n')
        return sb.toString()
    }

    /**
     * 解析输出。完成标记整行比对；`RC:` 取最后一条（脚本只有一个终止点，
     * 出现多条说明输出被截断，取最后一条更保守 —— `done` 才是唯一闸门）。
     */
    internal fun parseAddToShsoOutput(output: String): AddToShsoParse {
        var done = false
        var code: Int? = null
        for (line in output.lineSequence()) {
            if (line == ADD_TO_SHSO_DONE_MARKER) {
                done = true
                continue
            }
            if (!line.startsWith("RC:")) continue
            code = line.substring(3).trim().toIntOrNull() ?: code
        }
        return AddToShsoParse(done, code)
    }
    // ────────────────────────────────────────────────────────────────────
    // 批量原地拷贝（多选菜单「原地拷贝」）
    //
    // 旧路径：FilePage 逐项 copyFile()，每项一条 su。M 个文件 = M 条 su，
    // 而真正耗时的只有 cp 本身；其余全是 ProcessBuilder 与授权检查的往返。
    // ────────────────────────────────────────────────────────────────────

    /** 单段拷贝命令的 UTF-8 字节上限（与 [MOVE_SEGMENT_MAX_BYTES] 同口径）。 */
    internal const val COPY_SEGMENT_MAX_BYTES = 2900

    /** 段完成标记：整行比对。缺标记 = 该段没执行。 */
    internal const val COPY_SEGMENT_DONE_MARKER = "SHSO_COPY_OK"

    /**
     * 连续失败多少段就停发后续段（与 [MOVE_SEGMENT_MAX_CONSECUTIVE_FAILURES] 同口径）。
     *
     * 取 2 而不是 1：一段超时往往只是段内有大文件，su 本身没坏。
     * 取 2 也把「su 挂死」的最坏等待封在 2 段超时内 ——
     * 否则 1000 个文件会依次发出 20 段注定失败的命令，最后还要逐项回捞 1000 次。
     */
    private const val COPY_SEGMENT_MAX_CONSECUTIVE_FAILURES = 2

    /** 段超时：基线 120s 与旧逐项 copyFile 一致（它不传 timeout，走 120s 默认值）。 */
    private const val COPY_SEGMENT_TIMEOUT_BASE_MS = 120_000L
    private const val COPY_SEGMENT_TIMEOUT_PER_ITEM_MS = 20_000L
    private const val COPY_SEGMENT_TIMEOUT_MAX_MS = 600_000L

    /** 一个待拷贝项：源 + 已拆好的基名/后缀（拆分必须在 Kotlin 侧做，见 [splitCopyName]）。 */
    internal data class CopyPlanItem(val source: String, val base: String, val suffix: String)

    /**
     * 批量拷贝结果：与 [BatchMoveResult] 同一套分档语义。
     *
     * [copied] 是**落盘路径**（`dir/name_n.ext`），按完成顺序累积 —— 段是顺序处理的，
     * 因此这个顺序稳定可复现。不做「新路径反查源路径」的排序：
     * 调用方只取条数，反查是 O(n²) 且在同目录近似名（`a.txt` 与 `a_27.txt`）下不可靠。
     *
     * [unprocessed] 与 [failed] 必须分开：「su 没送达」不等于「拷贝失败」，
     * 混为一谈会让用户在授权弹窗卡住时看到 1000 条假失败
     * （同 [BatchMoveResult] 的 failed / unprocessed 分档）。
     */
    data class BatchCopyResult(
        /** 实际落盘的新路径，按完成顺序。 */
        val copied: List<String> = emptyList(),
        /** 明确失败的源路径（`cp` 失败 / 同级重名过多）。 */
        val failed: List<String> = emptyList(),
        /** 未能确认结果的源路径（段缺失、无回执、断路器跳闸后未发送）。 */
        val unprocessed: List<String> = emptyList(),
        /** 源路径 → 失败原因。 */
        val reasons: Map<String, String> = emptyMap()
    ) {
        val failedCount: Int get() = failed.size
        val unprocessedCount: Int get() = unprocessed.size
    }

    internal data class CopySegmentParse(
        val done: Boolean,
        val codes: Array<Char?>,
        val paths: Array<String?>
    )

    /**
     * 单段拷贝命令：`set --` 三字段打包，一个 while 顺序处理全部项。
     *
     * 每条都有依据：
     * - 父目录用参数展开「去掉最后一段」现算，不作为字段传（省一条路径的字节），
     *   与 `File(path).parent` 对绝对路径结论一致；
     * - 每项开头重置 `n=0`：唯一名**仍然**从 0 起探测，前项已占用的名字会让它自动递增；
     * - `( set -C; : > "$d" )` 是 O_EXCL 原子占位，**这是唯一性来源**，与 copyFile 同源；
     * - `cp -p` + `chmod a-s,go-w` 保留源权限且只减不增（与 copyFile 同口径）；
     * - 占位成功后立即 `break`，失败则同步递增 —— 不存在「探测到空位再另起一步占用」的窗口；
     * - `cp` / `chmod` 失败必须 `rm -f` 释放占位名，否则残留 0 字节文件；
     * - `NM:`（候选名全占用）与 `NG:`（cp/chmod 失败）分开，两者文案不同，合并会骗人；
     * - 单项失败**不终止整段**：外层 while 继续处理其余项（与逐项 copyFile 语义一致）；
     * - 完成标记恒为最后一行。
     */
    internal fun buildCopySegmentCommand(items: List<CopyPlanItem>): String {
        require(items.isNotEmpty()) { "空段无意义" }
        val sb = StringBuilder(340 + items.size * 56)
        sb.append("set --")
        for (item in items) {
            sb.append(' ').append(ShellEscapes.escapeShellArg(item.source))
            sb.append(' ').append(ShellEscapes.escapeShellArg(item.base))
            sb.append(' ').append(ShellEscapes.escapeShellArg(item.suffix))
        }
        sb.append('\n')
        sb.append("i=0\n")
        sb.append("while [ \$# -ge 3 ]; do\n")
        sb.append("s=\$1; b=\$2; x=\$3; shift 3\n")
        sb.append("p=\${s%/*}; n=0; ok=0\n")
        sb.append("while [ \$n -lt $COPY_NAME_PROBE_LIMIT ]; do\n")
        sb.append("d=\"\$p/\${b}_\$n\$x\"\n")
        sb.append("if ( set -C; : > \"\$d\" ) 2>/dev/null; then\n")
        sb.append("if cp -p \"\$s\" \"\$d\" 2>/dev/null && chmod a-s,go-w \"\$d\" 2>/dev/null; then ")
        sb.append("printf 'OK:%d:%s\\n' \$i \"\$d\"; ok=1; else rm -f -- \"\$d\"; fi\n")
        sb.append("break\n")
        sb.append("fi\n")
        sb.append("n=\$((n+1))\n")
        sb.append("done\n")
        sb.append("if [ \$ok = 0 ]; then if [ \$n -ge $COPY_NAME_PROBE_LIMIT ]; then ")
        sb.append("printf 'NM:%d\\n' \$i; else printf 'NG:%d\\n' \$i; fi; fi\n")
        sb.append("i=\$((i+1))\n")
        sb.append("done\n")
        sb.append("echo ").append(COPY_SEGMENT_DONE_MARKER).append('\n')
        return sb.toString()
    }

    /** 单项字节成本：转义后的源 + 基名 + 后缀 + 3 个分隔空格。 */
    internal fun copyItemCostBytes(item: CopyPlanItem): Int =
        ShellEscapes.escapeShellArg(item.source).toByteArray(Charsets.UTF_8).size +
            ShellEscapes.escapeShellArg(item.base).toByteArray(Charsets.UTF_8).size +
            ShellEscapes.escapeShellArg(item.suffix).toByteArray(Charsets.UTF_8).size + 3

    /** 段固定开销：由构造函数反测，脚本改动后不会与常量漂移。 */
    internal val copySegmentOverheadBytes: Int by lazy {
        val probe = CopyPlanItem("/", "", "")
        buildCopySegmentCommand(listOf(probe)).toByteArray(Charsets.UTF_8).size - copyItemCostBytes(probe)
    }

    internal fun planCopySegments(items: List<CopyPlanItem>): List<List<CopyPlanItem>> =
        chunkByBytes(items, copySegmentOverheadBytes, COPY_SEGMENT_MAX_BYTES) { copyItemCostBytes(it) }

    internal fun copyItemFitsOneSegment(item: CopyPlanItem): Boolean =
        copySegmentOverheadBytes + copyItemCostBytes(item) <= COPY_SEGMENT_MAX_BYTES

    internal fun copySegmentTimeoutMs(itemCount: Int): Long =
        (COPY_SEGMENT_TIMEOUT_BASE_MS + COPY_SEGMENT_TIMEOUT_PER_ITEM_MS * itemCount.coerceAtLeast(1))
            .coerceAtMost(COPY_SEGMENT_TIMEOUT_MAX_MS)

    /** 解析单段输出。状态行 `OK:<i>:<path>` / `NG:<i>` / `NM:<i>`，索引越界一律忽略。 */
    internal fun parseCopySegmentOutput(output: String, count: Int): CopySegmentParse {
        val codes = arrayOfNulls<Char>(count)
        val paths = arrayOfNulls<String>(count)
        var done = false
        for (line in output.lineSequence()) {
            if (line == COPY_SEGMENT_DONE_MARKER) {
                done = true
                continue
            }
            if (line.length < 4 || line[2] != ':') continue
            val code = when {
                line.startsWith("OK:") -> 'K'
                line.startsWith("NG:") -> 'N'
                line.startsWith("NM:") -> 'M'
                else -> continue
            }
            val rest = line.substring(3)
            val digits = rest.takeWhile { it.isDigit() }
            val index = digits.toIntOrNull() ?: continue
            if (index !in 0 until count) continue
            codes[index] = code
            if (code == 'K') {
                val tail = rest.substring(digits.length)
                if (tail.startsWith(":")) paths[index] = tail.substring(1)
            }
        }
        return CopySegmentParse(done, codes, paths)
    }

    /**
     * 批量原地拷贝：整批压进少数几条 su，每项仍各自原子取名。
     *
     * 段内顺序执行 ⇒ 唯一名语义与逐项 [copyFile] 一致（见施工文档 §4.2）。
     * 与 [copyFile] 的两处差异：
     *  · 失败原因只剩固定文案（`cp` 的 stderr 被丢弃，与 [moveBatch] 同一取舍）；
     *  · 段缺完成标记 / 某项无回执时逐项回捞 [copyFile]（它有 Java 兜底），
     *    **绝不**把「不知道」当成功；但连续失败达上限后不再逐项回捞 ——
     *    那等于把断路器绕过去，su 挂死时仍会白跑 M 次。
     */
    suspend fun copyBatch(sources: List<String>): BatchCopyResult = withContext(Dispatchers.IO) {
        // 尾斜杠会让 shell 的 `${s%/*}` 与 `File.parent` 得出不同父目录，先归一。
        val unique = sources.map { it.trimEnd('/').ifEmpty { "/" } }.distinct()
        if (unique.isEmpty()) return@withContext BatchCopyResult()

        val copied = ArrayList<String>(unique.size)
        val failed = ArrayList<String>()
        val unprocessed = ArrayList<String>()
        val reasons = HashMap<String, String>()

        // ── 阶段 0：纯词法预检（零 fork）─────────────────────────────
        val pending = ArrayList<CopyPlanItem>(unique.size)
        for (source in unique) {
            if (isUnsafePath(source)) {
                failed += source
                reasons[source] = "源路径包含非法字符"
                continue
            }
            val name = File(source).name
            if (name.isEmpty() || name == "." || name == "..") {
                failed += source
                reasons[source] = "源文件名无效"
                continue
            }
            val (base, suffix) = splitCopyName(name)
            pending += CopyPlanItem(source, base, suffix)
        }
        if (pending.isEmpty()) return@withContext BatchCopyResult(copied, failed, unprocessed, reasons)

        // 无 ROOT：copyFile 全程不 fork（本地 stat + 文件流），逐项即可。
        if (!preferRoot()) {
            for (item in pending) {
                val (ok, payload) = copyFile(item.source)
                if (ok) copied += payload else {
                    failed += item.source
                    reasons[item.source] = payload
                }
            }
            return@withContext BatchCopyResult(copied, failed, unprocessed, reasons)
        }

        // ── 阶段 1：分段批量 su ─────────────────────────────────────
        val sendable = ArrayList<CopyPlanItem>(pending.size)
        val tooBig = ArrayList<CopyPlanItem>()
        for (item in pending) {
            // 单项装不进一段：发出去只会被 su 整段丢弃，交给逐项回捞。
            if (copyItemFitsOneSegment(item)) sendable += item else tooBig += item
        }
        var consecutiveFailures = 0
        var suBroken = false
        for (segment in planCopySegments(sendable)) {
            if (consecutiveFailures >= COPY_SEGMENT_MAX_CONSECUTIVE_FAILURES) {
                segment.forEach { unprocessed += it.source }
                suBroken = true
                continue
            }
            val (_, out) = RootService.runCommandSync(
                buildCopySegmentCommand(segment),
                timeoutMs = copySegmentTimeoutMs(segment.size)
            )
            val parsed = parseCopySegmentOutput(out, segment.size)
            if (!parsed.done) {
                // 缺完成标记 = 这一段没送达：状态未知，归「未完成」，绝不计成功。
                segment.forEach { unprocessed += it.source }
                consecutiveFailures++
                continue
            }
            consecutiveFailures = 0
            segment.forEachIndexed { index, item ->
                when (parsed.codes[index]) {
                    'K' -> {
                        val landed = parsed.paths[index]
                        if (landed.isNullOrEmpty()) unprocessed += item.source else copied += landed
                    }
                    'N' -> {
                        failed += item.source
                        reasons[item.source] = "拷贝文件失败"
                    }
                    'M' -> {
                        failed += item.source
                        reasons[item.source] = "同级重名过多，未找到可用的目标名"
                    }
                    // 段跑完但本项没有回执：可能已经拷好，不能断言失败。
                    else -> unprocessed += item.source
                }
            }
        }
        tooBig.forEach { unprocessed += it.source }

        // ── 阶段 2：逐项回捞（copyFile 自带 Java 兜底）──────────────
        for (source in unprocessed.toList()) {
            if (suBroken) {
                reasons[source] = "ROOT 命令未完成"
                continue
            }
            unprocessed.remove(source)
            val (ok, payload) = copyFile(source)
            if (ok) copied += payload else {
                failed += source
                reasons[source] = payload
            }
        }

        val order = unique.withIndex().associate { (index, path) -> path to index }
        BatchCopyResult(
            copied = copied,
            failed = failed.sortedBy { order[it] ?: Int.MAX_VALUE },
            unprocessed = unprocessed.sortedBy { order[it] ?: Int.MAX_VALUE },
            reasons = reasons
        )
    }
    // ────────────────────────────────────────────────────────────────────
    // 复制到指定目录：一条 su 完成「类型复核 + 冲突策略 + 拷贝 + 回捞」
    // ────────────────────────────────────────────────────────────────────

    /**
     * 单条复制命令的 UTF-8 预算（2900，与其它批量命令同口径）。
     *
     * **只用于单测预算断言，代码里不做门禁**：本命令是单次操作、无法分段；
     * 若真的超过 4085 字节说明路径已到 PATH_MAX 量级，`su -c` 会整段丢弃 →
     * 缺完成标记 → 报「ROOT 命令未完成」，不会误报成功。
     * 在 2900 处拒绝反而会误伤 2900~4085 之间「本来能跑」的深路径。
     */
    internal const val COPY_DIR_MAX_BYTES = 2900

    internal const val COPY_DIR_DONE_MARKER = "SHSO_COPYDIR_OK"

    /** 覆盖同名**目录**成功（用于回收旧目标子树的历史）。 */
    internal const val COPY_DIR_OVERWROTE_MARKER = "OV:D"

    /** 目录复制可能搬几百 MB；旧实现走 120s 默认值，这里显式给宽。 */
    private const val COPY_TO_DIRECTORY_TIMEOUT_MS = 300_000L

    internal data class CopyToDirectoryParse(
        val done: Boolean,
        val code: Int?,
        val path: String?,
        val removedDirectory: Boolean
    )

    /**
     * 生成「复制到目录」的单条命令。
     *
     * 每条都有依据：
     * - 源类型只判一次（`st`），既做「0/其它」拒绝，也决定 `cp -a` 还是 `cp -p`；
     * - 目标必须是目录（`[ ! -d "$2" ] → rc=3`），把旧的三次类型探测压成一次判定；
     * - **同名文件绝不先删**：`cp` 覆盖同类型文件就是替换，先删等于造出
     *   「拷贝失败但目标已丢」的窗口（与 moveFile 的 OVERWRITE 注释同一理由）；
     * - 同名**目录**才 `rm -rf`，且删除失败**直接判失败**（rc=6），
     *   绝不下探 `cp` —— 否则 `cp src dst` 会落进残留目录内部，产生 X/X 嵌套；
     * - RENAME 策略只换 `f`（`$4`），`$3` 保持原目标；
     * - 回捞用 `[ ! -e "$f" ] && [ ! -L "$f" ]`：断链软链也算「没写出来」；
     * - 完成标记恒为最后一行；`FP:` 回传**实际**落点（RENAME 时与入参不同）。
     */
    internal fun buildCopyToDirectoryCommand(
        sourcePath: String,
        destinationDirectory: String,
        destinationPath: String,
        renamedPath: String,
        onConflict: MoveDestinationConflict
    ): String {
        val needsRenamed = onConflict == MoveDestinationConflict.RENAME
        val sb = StringBuilder(600)
        sb.append("set --")
            .append(' ').append(ShellEscapes.escapeShellArg(sourcePath))
            .append(' ').append(ShellEscapes.escapeShellArg(destinationDirectory))
            .append(' ').append(ShellEscapes.escapeShellArg(destinationPath))
        // 只有 RENAME 需要第四条路径：其余策略不传，省一条路径的字节。
        if (needsRenamed) sb.append(' ').append(ShellEscapes.escapeShellArg(renamedPath))
        sb.append('\n')
        sb.append("st=x; f=\$3; rc=0\n")
        sb.append("if [ -d \"\$1\" ]; then st=d\n")
        sb.append("elif [ -f \"\$1\" ]; then st=f\n")
        sb.append("elif [ -e \"\$1\" ] || [ -L \"\$1\" ]; then rc=2\n")
        sb.append("else rc=1; fi\n")
        sb.append("if [ \$rc = 0 ] && [ ! -d \"\$2\" ]; then rc=3; fi\n")
        sb.append("if [ \$rc = 0 ] && { [ -e \"\$f\" ] || [ -L \"\$f\" ]; }; then\n")
        when (onConflict) {
            MoveDestinationConflict.SKIP -> sb.append("rc=4\n")
            MoveDestinationConflict.RENAME -> sb.append(
                "if [ -e \"\$4\" ] || [ -L \"\$4\" ]; then rc=5; else f=\$4; fi\n"
            )
            MoveDestinationConflict.OVERWRITE -> {
                sb.append("if [ -d \"\$f\" ]; then\n")
                sb.append("if rm -rf -- \"\$f\" 2>/dev/null; then echo ")
                    .append(COPY_DIR_OVERWROTE_MARKER).append("; else rc=6; fi\n")
                sb.append("fi\n")
            }
        }
        sb.append("fi\n")
        sb.append("if [ \$rc = 0 ]; then\n")
        sb.append("if [ \$st = d ]; then cp -a \"\$1\" \"\$f\" || rc=7\n")
        sb.append("else cp -p \"\$1\" \"\$f\" || rc=7; fi\n")
        sb.append("if [ \$rc = 0 ] && { [ ! -e \"\$f\" ] && [ ! -L \"\$f\" ]; }; then rc=7; fi\n")
        sb.append("fi\n")
        sb.append("echo \"RC:\$rc\"\n")
        sb.append("echo \"FP:\$f\"\n")
        sb.append("echo ").append(COPY_DIR_DONE_MARKER).append('\n')
        return sb.toString()
    }

    internal fun parseCopyToDirectoryOutput(output: String): CopyToDirectoryParse {
        var done = false
        var code: Int? = null
        var path: String? = null
        var removed = false
        for (line in output.lineSequence()) {
            when {
                line == COPY_DIR_DONE_MARKER -> done = true
                line == COPY_DIR_OVERWROTE_MARKER -> removed = true
                line.startsWith("RC:") -> code = line.substring(3).trim().toIntOrNull() ?: code
                line.startsWith("FP:") -> path = line.substring(3)
            }
        }
        return CopyToDirectoryParse(done, code, path, removed)
    }
}
