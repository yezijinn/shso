// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 回归守卫：解压条目名的 Zip Slip 防护（`ArchiveExtractor.safeDest`）。
 *
 * 只做词法剥离是不够的 —— 词法层看不见符号链接，`<target>/link -> 外部目录` 仍可逃逸，
 * 因此增加了 `canonicalFile` 的真实路径二次校验。
 *
 * 越界一律**拒绝**（返回 null）：旧实现是「改名压回 target 根」，那确实落在 target 内，
 * 但会让磁盘结构与归档声明不一致，且与另一条归档条目映射到同一路径时触发重复判定，
 * 导致整次解压失败并删掉全部已解压内容。拒绝比静默改写更早失败、也更易归因。
 */
class ArchiveExtractorZipSlipTest {

    private fun tempBase(): File = java.nio.file.Files.createTempDirectory("shso-zipslip").toFile()

    private fun assertInside(target: File, dest: File, entry: String) {
        val basePath = target.canonicalFile.path
        val destPath = dest.canonicalFile.path
        assertTrue(
            "条目 $entry 逃出了目标目录: $destPath（base=$basePath）",
            destPath == basePath || destPath.startsWith(basePath + File.separator)
        )
    }

    @Test
    fun `穿越型条目名不得逃出目标目录`() {
        val base = tempBase()
        try {
            val target = File(base, "out").apply { mkdirs() }
            val entries = listOf(
                "../evil.txt",
                "../../evil.txt",
                "a/../../evil.txt",
                "a/b/../../../evil.txt",
                "/abs/evil.txt",
                "..\\..\\evil.txt",
                "./../evil.txt"
            )
            for (e in entries) {
                // 两条允许的出路，**任一**都不可接受之外的结果：
                //  1. 词法层把 `../` 归一化掉 → 条目名落在 target 内（如 `../evil.txt`
                //     → `<target>/evil.txt`）。这是安全且语义合理的：用户看到的是
                //     自己解压出的 evil.txt 在目标目录里。
                //  2. 判定为越界 → 拒绝（返回 null）。
                //
                // 不可接受的是**逃出 target**：那才是 Zip Slip。
                // 旧实现是「改名压回 target 根」，对 `../evil.txt` 恰好等价于 (1)，
                // 但对 `../../x.apk` 与 `sub/x.apk` 会映射到同一路径，
                // 触发 writtenPaths 重复判定 → 整次解压失败并删掉全部已解压内容。
                val dest = ArchiveExtractor.safeDest(target.path, e)
                if (dest == null) continue
                assertInside(target, dest, e)
                assertFalse("归一化后不得命中目标目录本身：" + e, dest.canonicalPath == target.canonicalPath)
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `纯点号条目绝不能解析成文件系统根`() {
        // 真实 Zip Slip 样例：
        // 归一化里 `n.replace(Regex("(^|/)\\.\\.(/|$)"), "/")` 把 `..` 替成 `/`，
        // 而紧随其后的 `trim()` **不去斜杠** —— 于是 `..` 变成 `/`，
        // 既不满足 isEmpty 也不等于 "." / ".."，**绕过空名分支**，
        // 直接 `File(target, "/")`。`File` 见到以 `/` 开头的子路径会当作绝对路径，
        // 结果是条目被写到**文件系统根**，逃出 target 之外。
        //
        // 危害：归档里名为 `..` 的条目 → 解压时在 `/` 下创建文件；
        // 名称可控时可覆盖根目录下的同名文件。
        val base = tempBase()
        try {
            val target = File(base, "out").apply { mkdirs() }
            for (name in listOf("..", "../..", "a/..", "./..", "a/b/../..")) {
                val dest = ArchiveExtractor.safeDest(target.path, name, fallbackName = "unnamed")
                    ?: continue
                // 归一化后为空 → 走占位名，占位文件必须落在 target **之内**。
                assertTrue(
                    "条目 '$name' 的占位文件必须落在 target 内：${dest.canonicalPath}",
                    dest.canonicalPath.startsWith(target.canonicalPath + File.separator)
                )
                // 关键回归点：修复前 `..` 会变成 File(target, "/") → 解析到 target 的**父目录**。
                assertFalse(
                    "条目 '$name' 不得逃到 target 之外：${dest.canonicalPath}",
                    dest.canonicalPath.startsWith(base.canonicalPath) &&
                        !dest.canonicalPath.startsWith(target.canonicalPath + File.separator)
                )
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `正常条目保留子目录结构且落在目标目录内`() {
        val base = tempBase()
        try {
            val target = File(base, "out").apply { mkdirs() }
            val dest = ArchiveExtractor.safeDest(target.path, "sub/dir/ok.txt")
                ?: throw AssertionError("正常条目不得被拒绝")
            assertEquals(File(target, "sub/dir/ok.txt").canonicalFile, dest.canonicalFile)
            assertInside(target, dest, "sub/dir/ok.txt")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `经符号链接逃逸的条目被拒绝`() {
        val base = tempBase()
        try {
            val target = File(base, "out").apply { mkdirs() }
            val outside = File(base, "outside").apply { mkdirs() }
            val link = File(target, "link")
            val linked = try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
                true
            } catch (_: Throwable) {
                false
            }
            // Windows 上创建符号链接通常需要特权；不支持时跳过该用例（其余用例仍覆盖词法层）。
            assumeTrue("当前环境无法创建符号链接，跳过", linked)

            // `link -> outside`，条目 `link/pwned.txt` 的 canonical 落在 target 之外
            // → 必须拒绝。若改名压回 target 根，写出的文件会静默落在用户没预期的位置。
            val dest = ArchiveExtractor.safeDest(target.path, "link/pwned.txt")
            assertNull("经符号链接逃逸的条目必须被拒绝，实际=$dest", dest)
            assertFalse("不得在 target 外产生任何文件", File(outside, "pwned.txt").exists())
        } finally {
            base.deleteRecursively()
        }
    }
}
