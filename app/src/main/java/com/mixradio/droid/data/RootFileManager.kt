// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import com.mixradio.droid.data.security.CommandSource
import com.mixradio.droid.data.security.PolicyEngine
import com.mixradio.droid.data.security.RiskLevel
import com.mixradio.droid.data.security.RootCommandGateway
import com.mixradio.droid.data.security.AuditVerdict
import com.mixradio.droid.data.security.SecurityAuditLog
import com.mixradio.droid.data.security.SecurityLevels
import com.mixradio.droid.data.security.Verdict
import kotlinx.coroutines.Dispatchers
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

    // 目录只需建立一次，进程内去重，避免每次刷新/切目录都重复发起一次 su 调用
    @Volatile
    private var shsoDirEnsured = false

    /**
     * 是否「优先使用 ROOT」：仅当已确获 ROOT 授权时为 true。
     * 设计原则：普通用户本就能做的操作（浏览/读写/增删自己的存储）不强制依赖 ROOT；
     * 只有在授权了 ROOT 时才优先走 su 以获得更完整的路径访问能力，
     * 无 ROOT 或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
     */
    private fun preferRoot(): Boolean = RootService.isRootGranted == true

    /**
     * 文件管理危险操作（删除 / 改名 / 移动 / 改权 / 改属）的统一安全门禁。
     *
     * 这些操作统一走安全门禁，否则会绕过 L1 静态审查与审计，使安全档位对文件页失效。
     *
     * 档位语义与终端路径保持一致：
     * - 0 无防护：不拦截、不审计；
     * - 1 仅审计：判定为 Allow，但落一条 ALLOW 审计；
     * - 2/3：完整策略判定 —— Block 直接拒绝；Confirm 视为已放行（UI 侧已由用户弹窗确认），
     *   落 CONFIRM 审计；Allow 落 ALLOW 审计。
     *
     * @return null=放行；非 null=拒绝理由（调用方原样返回给 UI）
     */
    private fun guardDestructiveOp(command: String, label: String): String? {
        if (!shouldGuardFileOp(PolicyEngine.currentLevel())) return null

        // 来源必须是 FILE_MANAGER：这些命令由用户在文件页触发，不是终端输入。
        // 记成 USER_TERMINAL 会让「破坏来源追溯」缺少最关键的一维。
        return when (val verdict = RootCommandGateway.check(command, CommandSource.FILE_MANAGER)) {
            is Verdict.Block -> {
                val finding = verdict.findings.firstOrNull()
                SecurityAuditLog.log(
                    CommandSource.FILE_MANAGER, AuditVerdict.DENIED, finding?.ruleId, RiskLevel.CRITICAL, command
                )
                "$label 被安全策略拦截：${finding?.message ?: "高危操作"}"
            }
            is Verdict.Confirm -> {
                val finding = verdict.findings.firstOrNull()
                SecurityAuditLog.log(
                    CommandSource.FILE_MANAGER, AuditVerdict.CONFIRMED, finding?.ruleId, verdict.level, command
                )
                null
            }
            Verdict.Allow -> {
                SecurityAuditLog.log(CommandSource.FILE_MANAGER, AuditVerdict.ALLOW, null, RiskLevel.SAFE, command)
                null
            }
        }
    }

    /**
     * 文件管理危险操作是否进入安全门禁（纯函数，便于单测）。
     *
     * 档位 0（无防护）不拦截、不审计；档位 1 及以上才走判定
     * （档位 1 的判定恒为 Allow，但**要落审计**，故返回值必须包含 1）。
     */
    internal fun shouldGuardFileOp(securityLevel: Int): Boolean = securityLevel > SecurityLevels.OFF

    /**
     * Root 执行的守卫 PATH 前缀（档位 <2 时为空串；档位 ≥2 但守卫不可用时同样返回空串，
     * 由 RootService 侧统一落降级审计 + 首次告警，此处不重复告警、不阻断）。
     */
    private fun guardPrefix(): String = RootService.guardPathPrefix().orEmpty()

    /**
     * 路径级非法校验：拒绝空路径、反斜杠、NUL、换行、回车，以及含 `..` 的路径段（防路径穿越）。
     * 用于所有把路径拼进 shell 命令或 java.io.File 前的统一拦截。
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

    suspend fun changePermissions(path: String, mode: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isAllowedDataPath(path)) {
            return@withContext Pair(false, "仅允许修改 /data 下的路径")
        }
        if (!isValidPermissionMode(mode)) {
            return@withContext Pair(false, "权限模式必须是 3 或 4 位八进制数字")
        }
        if (!preferRoot()) return@withContext Pair(false, NO_ROOT_NEEDED_FOR_ABSOLUTE_PATH)

        val escapedPath = RootService.escapeShellArg(path)
        guardDestructiveOp("chmod $mode $path", "修改权限")?.let { return@withContext Pair(false, it) }
        val (code, output) = RootService.runCommandSync("${guardPrefix()}chmod $mode $escapedPath")
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

        val escapedOwner = RootService.escapeShellArg(owner)
        val escapedPath = RootService.escapeShellArg(path)
        guardDestructiveOp("chown $owner $path", "修改所有者")?.let { return@withContext Pair(false, it) }
        val (code, output) = RootService.runCommandSync("${guardPrefix()}chown $escapedOwner $escapedPath")
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

        val escapedGroup = RootService.escapeShellArg(group)
        val escapedPath = RootService.escapeShellArg(path)
        guardDestructiveOp("chown :$group $path", "修改用户组")?.let { return@withContext Pair(false, it) }
        val (code, output) = RootService.runCommandSync("${guardPrefix()}chown :$escapedGroup $escapedPath")
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

    suspend fun listFiles(dirPath: String): List<FileItem> = withContext(Dispatchers.IO) {
        val targetPath = if (dirPath.isEmpty()) "/" else dirPath
        if (isUnsafePath(targetPath)) return@withContext emptyList()
        val items = mutableListOf<FileItem>()

        // ROOT 已授权时优先走 su 批量取条目（1~2 次 fork/exec，可访问受保护/系统路径）；
        // 未授权 ROOT 时跳过 su 探测，直接本地读取，避免无谓的 su 调用与超时。
        if (preferRoot()) {
            val escapedPath = RootService.escapeShellArg(targetPath)
            // 批量取全部条目：1~2 次 fork/exec 即可，避免逐文件 stat 产生的 2N 次进程创建开销。
            // find -exec + 由 find 自行分批，不受 ARG_MAX 限制；stat -L 跟随符号链接，行为同旧版 [ -d ] 判断。
            // 输出格式：权限串|字节数|mtime|文件名，权限串首字符 'd' 即目录。
            val primaryCmd =
                "cd $escapedPath 2>/dev/null && find . -maxdepth 1 -mindepth 1 -exec stat -L -c \"%A|%s|%Y|%n\" {} + 2>/dev/null"
            // 个别系统 find 不支持 -exec + 时退化为通配批量（仅超大目录存在 ARG_MAX 风险）
            val fallbackCmd =
                "cd $escapedPath 2>/dev/null && stat -L -c \"%A|%s|%Y|%n\" .* * 2>/dev/null"

            // 约束：不可用 exitCode 判断成败。只要目录内有任一条目 stat 失败（典型如
            // /adb_keys 这类断链符号链接，stat -L 跟随不存在的目标即报错），find/stat
            // 便返回非 0，但其余条目的输出完全有效。这里只以「有无输出、能否解析出条目」为准。
            for (cmd in listOf(primaryCmd, fallbackCmd)) {
                val (_, output) = RootService.runCommandSync(cmd)
                if (output.isNotBlank()) {
                    val before = items.size
                    parseStatOutput(output, targetPath, items)
                    if (items.size > before) break
                }
            }
        }

        // 无 ROOT 或 ROOT 取空/失败：本地兜底（授予「所有文件访问」后可浏览 /sdcard）
        if (items.isEmpty()) {
            try {
                val localFiles = File(targetPath).listFiles()
                if (localFiles != null) {
                    for (f in localFiles) {
                        items.add(
                            FileItem(
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = f.isDirectory,
                                size = if (f.isDirectory) 0L else f.length(),
                                lastModified = f.lastModified()
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 仅去重后返回：展示顺序由 UI 层（applyFileViewSettings：目录恒在前 + 名称/时间升/降序）
        // 统一决定，此处不再二次排序——原地排序会被 UI 层立即覆盖，且其比较器内逐次
        // name.lowercase() 会带来 O(N log N) 次临时字符串分配。
        items.distinctBy { it.path }
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

    private fun parseStatOutput(output: String, targetPath: String, items: MutableList<FileItem>) {
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // limit=4：文件名自身可含 '|'，故只切前三段，余下整体作为文件名
            val parts = trimmed.split("|", limit = 4)
            if (parts.size != 4) continue

            val perms = parts[0]
            if (perms.length < 2) continue

            val isDir = perms[0] == 'd'
            val size = parts[1].toLongOrNull() ?: 0L
            // stat -c %Y 给的是「秒」；lastModified 契约是「毫秒」，此处必须换算（见 statSecondsToMillis）
            val modified = statSecondsToMillis(parts[2].toLongOrNull() ?: 0L)
            var name = parts[3]

            // find 输出带 "./" 前缀
            if (name.startsWith("./")) name = name.removePrefix("./")
            // 格式化串用 %n（仅文件名），不会附加 " -> 链接目标"（那是 stat -l / %N 的行为），
            // 因此不能按 " -> " 截断，否则名字里含该串的文件会被截出错误的 name / path。
            if (name.isEmpty() || name == "." || name == "..") continue

            val itemPath = if (targetPath.endsWith("/")) "$targetPath$name" else "$targetPath/$name"

            items.add(
                FileItem(
                    name = name,
                    path = itemPath,
                    isDirectory = isDir,
                    size = size,
                    lastModified = modified
                )
            )
        }
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

        val copyCmd = "cp -r $escapedSource $escapedDest && chmod 777 $escapedDest"
        val (copyCode, copyOut) = RootService.runCommandSync(copyCmd)

        if (copyCode != 0) {
            return@withContext Pair(false, "复制文件失败: $copyOut")
        }

        if (autoDeleteSource) {
            // 「自动删除」是一次递归删除，必须走 [guardDestructiveOp] 门禁并检查退出码：
            // 1. 该命令此前完全在门禁外 —— 与 [delete] 口径不一致，等于给文件页开了一条
            //    「无策略、无审计的 root 递归删」通道，且由 UI 开关驱动、一次误点即生效；
            // 2. 退出码此前被忽略，删除失败也会返回成功，用户以为已删（实际源还在）。
            val deleteCmd = "rm -rf $escapedSource"
            guardDestructiveOp(deleteCmd, "自动删除源文件")?.let { reason ->
                return@withContext Pair(false, reason)
            }
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

        // ROOT 已授权时优先用 su 移动（可操作受保护/系统路径）；
        // 未授权或 su 失败时回退标准 renameTo（授予「所有文件访问」后可操作 /sdcard）。
        if (preferRoot()) {
            val escapedOld = RootService.escapeShellArg(oldPath)
            val escapedNew = RootService.escapeShellArg(newPath)
            guardDestructiveOp("mv $oldPath $newPath", "重命名")?.let { return@withContext Pair(false, it) }
            val (code, _) = RootService.runCommandSync("${guardPrefix()}mv $escapedOld $escapedNew")
            if (code == 0) return@withContext Pair(true, "重命名成功")
        }
        try {
            if (File(oldPath).renameTo(File(newPath))) return@withContext Pair(true, "重命名成功")
        } catch (_: Exception) {
        }
        Pair(false, "重命名失败")
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
                    // （源在别的挂载点 / 无权限 / 被守卫拦时，mv 必然失败，
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
            if (source.renameTo(dest) &&
                localType(sourcePath) == 0 && localType(finalPath) == sourceType
            ) {
                return@withContext Pair(true, "移动成功")
            }
        } catch (_: Exception) {
        }

        if (RootService.isRootGranted == true) {
            val escapedSource = RootService.escapeShellArg(sourcePath)
            val escapedDestination = RootService.escapeShellArg(finalPath)
            guardDestructiveOp("mv $sourcePath $finalPath", "移动")?.let { return@withContext Pair(false, it) }
            val (code, output) = RootService.runCommandSync(
                "${guardPrefix()}mv $escapedSource $escapedDestination && test ! -e $escapedSource && " +
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
            guardDestructiveOp("rm -rf $path", "删除")?.let { return@withContext Pair(false, it) }
            val escaped = RootService.escapeShellArg(path)
            val (code, _) = RootService.runCommandSync("${guardPrefix()}rm -rf $escaped")
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

    /** 拷��时追加序号的重名查找上限；超过即视为异常目录（避免无限循环）。 */
    private const val COPY_NAME_PROBE_LIMIT = 1000

    /**
     * 不 fork su 的存在性探测（仅用于 Java 回退路径的重名查找）。
     *
     * ROOT 路径下仍可能被 Java 判定为「不存在」，但那种情况下 [copyFile] 的 shell 分支
     * 已经先跑过并成功了，走到这里说明确实没有 ROOT，用 [File.exists] 足够。
     */
    private fun pathExistsQuiet(path: String): Boolean = File(path).exists()

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
        val base = srcFile.nameWithoutExtension
        val ext = srcFile.extension
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""

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
        var n = 0
        var destPath: String
        do {
            destPath = copyCandidatePath(parent, base, suffix, n)
            n++
        } while (n < COPY_NAME_PROBE_LIMIT && (File(destPath).exists() || pathExistsQuiet(destPath)))
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
