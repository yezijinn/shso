// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object RootFileManager {

    const val DEFAULT_SHSO_DIR = "/data/adb/shso"

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

        items.distinctBy { it.path }
            .sortedWith(
                compareByDescending<FileItem> { it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
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
            val modified = parts[2].toLongOrNull() ?: 0L
            var name = parts[3]

            // find 输出带 "./" 前缀
            if (name.startsWith("./")) name = name.removePrefix("./")
            if (name.contains(" -> ")) name = name.substringBefore(" -> ").trim()
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
        val sanitized = newName.trim()
        if (sanitized.isEmpty()) {
            return@withContext Pair(false, "文件名不能为空")
        }
        // 换行/回车可被 shell 解释为命令分隔，必须一并拒绝（与 /、\、..、\0 同级）
        if (sanitized.contains("/") || sanitized.contains("\\") || sanitized.contains("..") ||
            sanitized.contains("\n") || sanitized.contains("\r") || sanitized.contains("\u0000")
        ) {
            return@withContext Pair(false, "文件名不能包含路径分隔符或非法字符")
        }

        val parent = File(oldPath).parent ?: "/"
        val newPath = if (parent.endsWith("/")) "$parent$sanitized" else "$parent/$sanitized"

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

    suspend fun delete(path: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // ROOT 已授权时优先用 su 删除（可操作受保护/系统路径）；
        // 未授权或 su 失败时回退标准 File API（授予「所有文件访问」后可操作 /sdcard）。
        if (preferRoot()) {
            val escaped = RootService.escapeShellArg(path)
            val (code, _) = RootService.runCommandSync("rm -rf $escaped")
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
        val name = fileName.trim()
        if (name.isEmpty()) {
            return@withContext Pair(false, "文件名不能为空")
        }
        // 拒绝路径分隔符与非法字符（与 rename 同级校验，防止越目录写入）
        if (name.contains("/") || name.contains("\\") || name.contains("..") ||
            name.contains("\n") || name.contains("\r") || name.contains("\u0000")
        ) {
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
