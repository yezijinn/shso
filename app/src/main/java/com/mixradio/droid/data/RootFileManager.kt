// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import com.mixradio.droid.data.security.CommandSource
import com.mixradio.droid.data.security.PolicyEngine
import com.mixradio.droid.data.security.RiskLevel
import com.mixradio.droid.data.security.RootCommandGateway
import com.mixradio.droid.data.security.SecurityAuditLog
import com.mixradio.droid.data.security.SecurityLevels
import com.mixradio.droid.data.security.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
     * - 0 无防护：不判定、不审计，行为与旧版逐字节一致；
     * - 1 仅审计：判定为 Allow，但落一条 ALLOW 审计；
     * - 2/3：完整策略判定 —— Block 直接拒绝；Confirm 视为已放行（UI 侧已由用户弹窗确认），
     *   落 CONFIRM 审计；Allow 落 ALLOW 审计。
     *
     * @return null=放行；非 null=拒绝理由（调用方原样返回给 UI）
     */
    private fun guardDestructiveOp(command: String, label: String): String? {
        if (!shouldGuardFileOp(PolicyEngine.currentLevel())) return null

        return when (val verdict = RootCommandGateway.check(command, CommandSource.USER_TERMINAL)) {
            is Verdict.Block -> {
                val finding = verdict.findings.firstOrNull()
                SecurityAuditLog.log(
                    CommandSource.USER_TERMINAL, "BLOCK", finding?.ruleId, RiskLevel.CRITICAL, command
                )
                "$label 被安全策略拦截：${finding?.message ?: "高危操作"}"
            }
            is Verdict.Confirm -> {
                val finding = verdict.findings.firstOrNull()
                SecurityAuditLog.log(
                    CommandSource.USER_TERMINAL, "CONFIRM", finding?.ruleId, verdict.level, command
                )
                null
            }
            Verdict.Allow -> {
                SecurityAuditLog.log(CommandSource.USER_TERMINAL, "ALLOW", null, RiskLevel.SAFE, command)
                null
            }
        }
    }

    /**
     * 文件管理危险操作是否进入安全门禁（纯函数，便于单测）。
     *
     * 档位 0（无防护）不判定、不审计，与旧版行为逐字节一致；档位 1 及以上才走判定
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
            // 批量取全部条目：1~2 次 fork/exec 替代原先「每文件 2 次 stat」（2N 次进程创建）。
            // find -exec + 由 find 自行分批，不受 ARG_MAX 限制；stat -L 跟随符号链接，行为同旧版 [ -d ] 判断。
            // 输出格式：权限串|字节数|mtime|文件名，权限串首字符 'd' 即目录。
            val primaryCmd =
                "cd $escapedPath 2>/dev/null && find . -maxdepth 1 -mindepth 1 -exec stat -L -c \"%A|%s|%Y|%n\" {} + 2>/dev/null"
            // 个别系统 find 不支持 -exec + 时退化为通配批量（仅超大目录存在 ARG_MAX 风险）
            val fallbackCmd =
                "cd $escapedPath 2>/dev/null && stat -L -c \"%A|%s|%Y|%n\" .* * 2>/dev/null"

            // 注意：不可用 exitCode 判断成败。只要目录内有任一条目 stat 失败（典型如
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

        // 仅去重后返回：展示顺序统一由 UI 层按用户偏好决定（applyFileViewSettings：
        // 目录恒在前 + 名称/时间升/降序）。此处原先额外做一次「目录在前 + 名称升序」排序，
        // 结果会被 UI 层立即覆盖，而其比较器内逐次 name.lowercase() 会带来
        // O(N log N) 次临时字符串分配，属可安全移除的重复计算。
        items.distinctBy { it.path }
    }

    /**
     * `stat -c %Y` 输出的是「秒」，而 [FileItem.lastModified] 的契约是「毫秒」
     * （本地路径走 `File.lastModified()`，本身就是毫秒）。
     *
     * 不换算会把秒当成毫秒，修改时间显示为 1970 年，且按时间排序错乱。
     */
    internal fun statSecondsToMillis(seconds: Long): Long = if (seconds <= 0L) 0L else seconds * 1000L

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
            val deleteCmd = "rm -rf $escapedSource"
            RootService.runCommandSync(deleteCmd)
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
                    // 先删除目标同名项（文件或目录树），再移动
                    if (!delete(destinationPath).first) {
                        return@withContext Pair(false, "无法覆盖目标同名项")
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
            val f = File(path)
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            if (ok) return@withContext Pair(true, "删除成功")
        } catch (_: Exception) {
        }
        Pair(false, "删除失败")
    }

    /**
     * 拷贝文件到同级目录，自动追加递增序号后缀（如 file.txt → file_0.txt → file_1.txt）。
     * 序号插入在扩展名之前（无扩展名则直接加在末尾）。仅用于文件（文件夹不调用）。
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

        // 从 0 递增找到首个不存在的目标名
        var n = 0
        var destPath: String
        do {
            destPath = if (parent.endsWith("/")) {
                "${parent}${base}_$n$suffix"
            } else {
                "$parent/${base}_$n$suffix"
            }
            n++
        } while (pathExists(destPath))

        // ROOT 已授权时优先用 su 拷贝（可操作受保护/系统路径）；
        // 未授权或 su 失败时回退标准 IO 拷贝（授予「所有文件访问」后可操作 /sdcard）。
        if (preferRoot()) {
            val escapedSource = RootService.escapeShellArg(sourcePath)
            val escapedDest = RootService.escapeShellArg(destPath)
            val copyCmd = "cp -p $escapedSource $escapedDest && chmod 644 $escapedDest"
            val (code, _) = RootService.runCommandSync(copyCmd)
            if (code == 0) return@withContext Pair(true, destPath)
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
            val f = File(fullPath)
            if (f.createNewFile()) return@withContext Pair(true, fullPath)
        } catch (_: Exception) {
        }
        Pair(false, "创建文件失败")
    }
}
