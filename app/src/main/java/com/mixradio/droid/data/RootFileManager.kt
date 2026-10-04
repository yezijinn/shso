// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

object RootFileManager {

    const val DEFAULT_SHSO_DIR = "/data/adb/shso"

    private val PERMISSION_MODE_PATTERN = Regex("^[0-7]{3,4}$")

    /** 同名副本探测的次数上限：防止异常目录里 `exists()` 无限循环。 */
    private const val COPY_NAME_PROBE_LIMIT = 1000
    private val OWNER_OR_GROUP_PATTERN = Regex("^[a-zA-Z0-9._-]+$")

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
     * 但更关键的是 [preferRoot] 现在**只在本地列举拿不到条目时**才会走到这里
     * （见 listDirectoryLocal 的快路径），因此这个等待绝大多数情况下不会发生。
     */
    private const val ROOT_STATE_WAIT_MS = 3_000L
    private const val ROOT_STATE_POLL_MS = 50L

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
     * 文件名级非法校验：拒绝路径分隔符（/ 与 \）、`..`、NUL、换行、回车，防止越目录写入或命令分隔。
     */
    internal fun isUnsafeFileName(name: String): Boolean =
        name.isEmpty() ||
            name.contains("/") ||
            name.contains("\\") ||
            name.contains("..") ||
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
        val (code, out) = RootService.runCommandSync(
            "readlink -f ${RootService.escapeShellArg(path)} 2>/dev/null", 10_000L
        )
        val resolved = if (code == 0 && out.isNotBlank()) out.trim() else path
        if (!isAllowedDataPath(resolved)) {
            return null to "该路径是符号链接，真实目标（$resolved）不在 /data 下，已拒绝"
        }
        // 真实目标也必须仍在用户点选的路径之内，防止链到 /data 内的其它敏感位置。
        //
        // 这一段此前只有判断和注释、函数体是空的，等于该检查从未存在：`isAllowedDataPath`
        // 只能证明「解析后仍在 /data 下」，于是 `/data/local/tmp/m → /data/adb/modules`
        // 这类链接会被放行，root 随后把 modules 目录 chmod/改属主，而确认弹窗显示的
        // 只是链接自身的路径。链接指向自己或子目录（`p`、`p/x`）是正常用法，必须放行。
        if (resolved != path) {
            val selfBase = path.trimEnd('/')
            val insideSelf = resolved == selfBase || resolved.startsWith("$selfBase/")
            if (!insideSelf) {
                return resolved to "路径不安全：真实目标（$resolved）不在你选择的路径（$path）之内"
            }
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
        val (code, output) = RootService.runCommandSync("chown $escapedOwner $escapedPath")
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
        val (code, output) = RootService.runCommandSync("chown :$escapedGroup $escapedPath")
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
        val cmd = "mkdir -p ${RootService.escapeShellArg(DEFAULT_SHSO_DIR)} && chmod 777 ${RootService.escapeShellArg(DEFAULT_SHSO_DIR)}"
        val (code, _) = RootService.runCommandSync(cmd)
        if (code == 0) shsoDirEnsured = true
        code == 0
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

    /** 解析 `权限串|字节数|mtime`，跳过畸形行。不做 trim：名称在另一条通道。 */
    private fun parseStatMeta(output: String): List<StatMeta> {
        val out = ArrayList<StatMeta>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split('|')
            if (parts.size < 3) continue
            val perms = parts[0]
            if (perms.length < 2) continue
            out.add(
                StatMeta(
                    isDirectory = perms[0] == 'd',
                    size = parts[1].toLongOrNull() ?: 0L,
                    modified = statSecondsToMillis(parts[2].toLongOrNull() ?: 0L)
                )
            )
        }
        return out
    }

    /**
     * 列举目录条目，并区分「成功（含空目录）」/「目录不存在」/「列举失败」。
     *
     * 与 [listFiles] 的差别：不再把失败压成空列表。这是「当前目录为空」
     * 误报的根因修复点 —— 调用方必须能区分二者才能给出正确文案。
     */
    suspend fun listDirectory(dirPath: String): DirectoryListing = withContext(Dispatchers.IO) {
        val targetPath = if (dirPath.isEmpty()) "/" else dirPath
        if (isUnsafePath(targetPath)) return@withContext DirectoryListing.Failed("路径非法")

        val escapedPath = RootService.escapeShellArg(targetPath)

        if (preferRoot()) {
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
            // 标记前全是文件名、标记后全是元数据行，两者互不污染 —— 文件名里的
            // \n、| 、前后空格都不会错切记录，也不会像旧 `%A|%s|%Y|%n` 那样把
            // 含 \n 的名字劈成两条记录（一条指向不存在的幻影文件，长按删除即误删真文件）。
            val (code, out) = RootService.runCommandSync(
                "cd $escapedPath 2>/dev/null || exit 3; test -d . || exit 4; " +
                    "find . -maxdepth 1 -mindepth 1 -print0; printf '\\0$META_SEP\\0'; " +
                    "find . -maxdepth 1 -mindepth 1 -exec stat -L -c \"%A|%s|%Y\" {} + 2>/dev/null"
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

            val cut = out.indexOf(META_SEP)
            if (cut < 0) {
                // 标记缺失说明命令在中途被杀或输出被截断。如实报失败，
                // 绝不能把「只拿到一半输出」当成「目录为空」。
                return@withContext DirectoryListing.Failed("目录读取结果不完整")
            }
            val names = out.substring(0, cut)
                .split('\u0000')
                .asSequence()
                .map { it.removePrefix("./") }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .toList()
            val metas = parseStatMeta(out.substring(cut + META_SEP.length))

            // ROOT 已确认可进入：条目列表就是权威结果，零条目即「目录确实为空」。
            // 绝不能回落到本地判定 —— /data/adb/shso 等目录应用侧被 SELinux 拦，
            // 本地 listFiles() 返回 null，据此报「无权限」就是把空目录说成故障。
            val items = names.mapIndexed { index, name ->
                val meta = metas.getOrNull(index)
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
        // 能走到这里说明 preferRoot() 为 false —— su 从未被调用过，
        // 因此不存在「ROOT 不可用」这一前提，不传该标记。
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
        ensureShsoDir()

        val sourceFile = File(sourcePath)
        val sourceName = sourceFile.name
        val nameWithoutExt = sourceFile.nameWithoutExtension.replace("'", "")

        val targetDir = if (useIndependentFolder) {
            val timestamp = (System.currentTimeMillis() % 100000).toString()
            "$DEFAULT_SHSO_DIR/${nameWithoutExt}_$timestamp"
        } else {
            DEFAULT_SHSO_DIR
        }

        val escapedTargetDir = RootService.escapeShellArg(targetDir)
        val createDirCmd = "mkdir -p $escapedTargetDir && chmod 777 $escapedTargetDir"
        RootService.runCommandSync(createDirCmd)

        val destinationPath = "$targetDir/$sourceName"
        val escapedSource = RootService.escapeShellArg(sourcePath)
        val escapedDest = RootService.escapeShellArg(destinationPath)

        // 目录必须递归 chmod：设计目的是「让其他应用也能自由读写其中的文件」，
        // 只改顶层的话子文件/子目录仍是源权限（如 0700），第三方文件管理器照样读不到。
        val chmodCmd = if (sourceFile.isDirectory) "chmod -R 777" else "chmod 777"
        val copyCmd = "cp -r $escapedSource $escapedDest && $chmodCmd $escapedDest"
        val (copyCode, copyOut) = RootService.runCommandSync(copyCmd)

        if (copyCode != 0) {
            return@withContext Pair(false, "复制文件失败: $copyOut")
        }

        if (autoDeleteSource) {
            // 「自动删除」是一次递归删除：必须检查退出码 —— 忽略它会让删除失败也返回成功，
            // 用户以为已删（实际源还在）。
            val deleteCmd = "rm -rf $escapedSource"
            val (deleteCode, deleteOut) = RootService.runCommandSync(deleteCmd)
            if (deleteCode != 0) {
                return@withContext Pair(false, "已复制到 shso，但删除源文件失败: ${deleteOut.trim()}")
            }
        }

        Pair(true, destinationPath)
    }

suspend fun rename(oldPath: String, newName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isUnsafePath(oldPath)) {
            return@withContext Pair(false, "路径包含非法字符")
        }
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
          // 原实现两条路径都是「无条件替换」：root 走 `mv`（GNU/BSD mv 默认覆盖），
          // 非 root 走 `File.renameTo`（JDK 明确「若目标已存在则结果依赖平台」，
          // Linux 上同样是 rename(2) 覆盖）。于是：
          //   · 批量重命名把 `notes.txt` 改成 `report_0.txt`，而同目录里已存在
          //     **不在选择集内**的 `report_0.txt` → 该文件被静默销毁；
          //   · 单文件重命名同理，用户看到「重命名成功」而另一个文件已消失。
          // 「覆盖」只应由用户显式选择（见 moveFile 的 MoveDestinationConflict），
          // 重命名这里没有该选项，故一律拒绝。
          if (destinationExists(newPath, oldPath)) {
              return@withContext Pair(false, "目标已存在：$sanitized")
          }

        // ROOT 已授权时优先用 su 移动（可操作受保护/系统路径）；
        // 未授权或 su 失败时回退标准 renameTo（授予「所有文件访问」后可操作 /sdcard）。
        if (preferRoot()) {
            val escapedOld = RootService.escapeShellArg(oldPath)
            val escapedNew = RootService.escapeShellArg(newPath)
            val (code, _) = RootService.runCommandSync("mv $escapedOld $escapedNew")
            if (code == 0) return@withContext Pair(true, "重命名成功")
        }
        try {
            if (File(oldPath).renameTo(File(newPath))) return@withContext Pair(true, "重命名成功")
        } catch (_: Exception) {
        }
        Pair(false, "重命名失败")
    }

    /**
     * 目标路径是否已被**另一个**条目占用。
     *
     * 应用 uid 看不到受保护路径（`File.exists` 恒 false），故已授权时改用 root 通道判定；
     * 名称不同即视为「另一个条目」，同名（改回原名）不算冲突。
     */
    private suspend fun destinationExists(newPath: String, oldPath: String): Boolean {
        if (newPath == oldPath) return false
        val local = runCatching { File(newPath).exists() }.getOrDefault(false)
        if (local) return true
        if (RootService.isRootGranted != true) return false
        val escaped = RootService.escapeShellArg(newPath)
        val (code, _) = RootService.runCommandSync("test -e $escaped")
        return code == 0
    }

    suspend fun moveFile(
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

        fun destExists(): Boolean =
            localType(destinationPath) != 0 ||
                (RootService.isRootGranted == true && rootType(destinationPath) != 0)

        // 自动改名：在扩展名前插入 _new（file.txt → file_new.txt，无扩展名 → name_new）
        fun renamedDestPath(): String {
            val dot = sourceName.lastIndexOf('.')
            val newName = if (dot > 0) "${sourceName.substring(0, dot)}_new${sourceName.substring(dot)}" else "${sourceName}_new"
            return if (destinationNormalized == "/") "/$newName" else "$destinationNormalized/$newName"
        }

        if (destExists()) {
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

        // 实际目标路径：OVERWRITE 用原名；RENAME 冲突时用 _new 名
        val finalPath = if (destExists() && onConflict == MoveDestinationConflict.RENAME) renamedDestPath() else destinationPath

        try {
            val dest = File(finalPath)
            // renameTo 返回 true 即视为成功。原先还要求 `localType(finalPath) == sourceType`，
            // 而目标常位于 app 无权 stat 的挂载点（FUSE/sdcardfs）或本身是 socket/FIFO，
            // 复核必然失败 → 落到 root 分支再跑一次 mv（源已不在，报「移动失败」）。
            // 移动其实已经完成，用户会重试，第二次就命中 OVERWRITE 分支，可能连带删掉目标同名文件。
            if (source.renameTo(dest)) {
                return@withContext Pair(true, "移动成功")
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
            if (code == 0) return@withContext Pair(true, "移动成功")
            if (output.isNotBlank()) return@withContext Pair(false, "移动失败: ${output.trim()}")
        }

        Pair(false, "移动失败")
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
        // ROOT 已授权时优先用 su 删除（可操作受保护/系统路径）；
        // 未授权或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
        if (preferRoot()) {
            val escaped = RootService.escapeShellArg(path)
            val (code, _) = RootService.runCommandSync("rm -rf $escaped")
            if (code == 0) return@withContext Pair(true, "删除成功")
        }
        try {
            val targetFile = File(path)
            // 符号链接必须只删链接本身，绝不能跟随。
            // `File.isDirectory` 走 stat，会跟随链接；`deleteRecursively()` 对目录链接
            // 同样会下潜，于是「删除一个指向别处的链接」会静默清空链接目标整棵树
            // （ROOT 不可用 + 用户删的是 /sdcard 下自己放的链接时必现，且不可撤销）。
            if (Files.isSymbolicLink(targetFile.toPath())) {
                val ok = targetFile.delete()   // 删链接本身，不动目标
                if (ok) return@withContext Pair(true, "删除成功")
                return@withContext Pair(false, "删除符号链接失败")
            }
            val ok = if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
            if (ok) return@withContext Pair(true, "删除成功")
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
                append("if cp -p $escapedSource \"\$d\" && chmod 644 \"\$d\"; then ")
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

                // 单次 File.exists() 即可：此前写成 `File(destPath).exists() || pathExistsQuiet(destPath)`，
        // 而后者函数体就是 File(path).exists() —— `||` 只在左为 true 时省下右，
        // 左为 false（正是需要继续探测下一个名字的情况）时必然**再做一次完整 stat**。
        // 同名副本 N 个 → 2N 次系统调用。
        var n = 0
        var destPath: String
        do {
            destPath = copyCandidatePath(parent, base, suffix, n)
            n++
        } while (n < COPY_NAME_PROBE_LIMIT && File(destPath).exists())
        if (n >= COPY_NAME_PROBE_LIMIT) {
            return@withContext Pair(false, "同级重名过多，未找到可用的目标名")
        }

        try {
            val destFile = File(destPath)
            srcFile.inputStream().use { ins ->
                destFile.outputStream().use { outs -> ins.copyTo(outs) }
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
}
