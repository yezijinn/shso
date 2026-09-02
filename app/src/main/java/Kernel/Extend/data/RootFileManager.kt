// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

package Kernel.Extend.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object RootFileManager {

    const val DEFAULT_KERNEL_EX_DIR = "/data/adb/KernelEX"

    suspend fun ensureKernelEXDir(): Boolean = withContext(Dispatchers.IO) {
        val cmd = "mkdir -p '$DEFAULT_KERNEL_EX_DIR' && chmod 777 '$DEFAULT_KERNEL_EX_DIR'"
        val (code, _) = RootService.runCommandSync(cmd)
        code == 0
    }

    suspend fun listFiles(dirPath: String): List<FileItem> = withContext(Dispatchers.IO) {
        val targetPath = if (dirPath.isEmpty()) "/" else dirPath
        val items = mutableListOf<FileItem>()

        val escapedPath = targetPath.replace("'", "'\\''")
        val cmd = "cd '$escapedPath' 2>/dev/null && for f in .* *; do [ -e \"\$f\" ] || continue; [ \"\$f\" = \".\" ] && continue; [ \"\$f\" = \"..\" ] && continue; [ -d \"\$f\" ] && d=1 || d=0; s=\$(stat -c %s \"\$f\" 2>/dev/null || echo 0); echo \"\$d|\$s|\$f\"; done"
        val (exitCode, output) = RootService.runCommandSync(cmd)

        if (exitCode == 0 && output.isNotBlank()) {
            val lines = output.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val parts = trimmed.split("|", limit = 3)
                if (parts.size == 3) {
                    val isDir = parts[0] == "1"
                    val size = parts[1].toLongOrNull() ?: 0L
                    var name = parts[2]

                    if (name.contains(" -> ")) {
                        name = name.substringBefore(" -> ").trim()
                    }

                    if (name == "." || name == "..") continue

                    val itemPath = if (targetPath.endsWith("/")) "$targetPath$name" else "$targetPath/$name"

                    items.add(
                        FileItem(
                            name = name,
                            path = itemPath,
                            isDirectory = isDir,
                            size = size,
                            lastModified = System.currentTimeMillis()
                        )
                    )
                }
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

    suspend fun addFileToKernelEX(
        sourcePath: String,
        useIndependentFolder: Boolean,
        autoDeleteSource: Boolean
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        ensureKernelEXDir()

        val sourceFile = File(sourcePath)
        val sourceName = sourceFile.name
        val nameWithoutExt = sourceFile.nameWithoutExtension.replace("'", "")

        val targetDir = if (useIndependentFolder) {
            val timestamp = (System.currentTimeMillis() % 100000).toString()
            "$DEFAULT_KERNEL_EX_DIR/${nameWithoutExt}_$timestamp"
        } else {
            DEFAULT_KERNEL_EX_DIR
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
