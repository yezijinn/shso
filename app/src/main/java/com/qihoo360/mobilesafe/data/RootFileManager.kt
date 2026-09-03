// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object RootFileManager {

    const val DEFAULT_SHSO_DIR = "/data/adb/shso"

    // 目录只需建立一次，进程内去重，避免每次刷新/切目录都重复发起一次 su 调用
    @Volatile
    private var shsoDirEnsured = false

    suspend fun ensureShsoDir(): Boolean = withContext(Dispatchers.IO) {
        if (shsoDirEnsured) return@withContext true
        val cmd = "mkdir -p '$DEFAULT_SHSO_DIR' && chmod 777 '$DEFAULT_SHSO_DIR'"
        val (code, _) = RootService.runCommandSync(cmd)
        if (code == 0) shsoDirEnsured = true
        code == 0
    }

    suspend fun listFiles(dirPath: String): List<FileItem> = withContext(Dispatchers.IO) {
        val targetPath = if (dirPath.isEmpty()) "/" else dirPath
        val items = mutableListOf<FileItem>()

        val escapedPath = targetPath.replace("'", "'\\''")
        // 批量取全部条目：1~2 次 fork/exec 替代原先「每文件 2 次 stat」（2N 次进程创建）。
        // find -exec + 由 find 自行分批，不受 ARG_MAX 限制；stat -L 跟随符号链接，行为同旧版 [ -d ] 判断。
        // 输出格式：权限串|字节数|mtime|文件名，权限串首字符 'd' 即目录。
        val primaryCmd =
            "cd '$escapedPath' 2>/dev/null && find . -maxdepth 1 -mindepth 1 -exec stat -L -c \"%A|%s|%Y|%n\" {} + 2>/dev/null"
        // 个别系统 find 不支持 -exec + 时退化为通配批量（仅超大目录存在 ARG_MAX 风险）
        val fallbackCmd =
            "cd '$escapedPath' 2>/dev/null && stat -L -c \"%A|%s|%Y|%n\" .* * 2>/dev/null"

        // 注意：不可用 exitCode 判断成败。只要目录内有任一条目 stat 失败（典型如
        // /adb_keys 这类断链符号链接，stat -L 跟随不存在的目标即报错），find/stat
        // 便返回非 0，但其余条目的输出完全有效。旧实现退出码取自循环末尾的 echo
        // （恒为 0），故从未暴露该问题。这里只以「有无输出、能否解析出条目」为准。
        for (cmd in listOf(primaryCmd, fallbackCmd)) {
            val (_, output) = RootService.runCommandSync(cmd)
            if (output.isNotBlank()) {
                val before = items.size
                parseStatOutput(output, targetPath, items)
                if (items.size > before) break
            }
        }

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

        val escapedTargetDir = targetDir.replace("'", "'\\''")
        val createDirCmd = "mkdir -p '$escapedTargetDir' && chmod 777 '$escapedTargetDir'"
        RootService.runCommandSync(createDirCmd)

        val destinationPath = "$targetDir/$sourceName"
        val escapedSource = sourcePath.replace("'", "'\\''")
        val escapedDest = destinationPath.replace("'", "'\\''")

        val copyCmd = "cp -r '$escapedSource' '$escapedDest' && chmod 777 '$escapedDest'"
        val (copyCode, copyOut) = RootService.runCommandSync(copyCmd)

        if (copyCode != 0) {
            return@withContext Pair(false, "复制文件失败: $copyOut")
        }

        if (autoDeleteSource) {
            val deleteCmd = "rm -rf '$escapedSource'"
            RootService.runCommandSync(deleteCmd)
        }

        Pair(true, destinationPath)
    }

    suspend fun rename(oldPath: String, newName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val sanitized = newName.trim()
        if (sanitized.isEmpty()) {
            return@withContext Pair(false, "文件名不能为空")
        }
        if (sanitized.contains("/") || sanitized.contains("\\") || sanitized.contains("..") || sanitized.contains("\u0000")) {
            return@withContext Pair(false, "文件名不能包含路径分隔符或非法字符")
        }

        val parent = File(oldPath).parent ?: "/"
        val newPath = if (parent.endsWith("/")) "$parent$sanitized" else "$parent/$sanitized"
        val escapedOld = oldPath.replace("'", "'\\''")
        val escapedNew = newPath.replace("'", "'\\''")

        val cmd = "mv '$escapedOld' '$escapedNew'"
        val (code, out) = RootService.runCommandSync(cmd)
        Pair(code == 0, if (code == 0) "重命名成功" else out)
    }

    suspend fun delete(path: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val escaped = path.replace("'", "'\\''")
        val cmd = "rm -rf '$escaped'"
        val (code, out) = RootService.runCommandSync(cmd)
        Pair(code == 0, out)
    }
}
