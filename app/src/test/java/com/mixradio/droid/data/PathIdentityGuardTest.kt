// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一物理路径」判等的护栏。
 *
 * 背景：`/sdcard/A` 与 `/storage/emulated/0/A`、`/sdcard/download` 与
 * `/sdcard/Download`（共享存储大小写不敏感）、软链与其目标、`x/./y` 与 `x/y`
 * 字面不同却是同一份数据。OVERWRITE 分支对同名**目录**目标是先 `rm -rf` 再搬，
 * 一旦「目标」其实是源自己，删的就是源：真机逐字复刻段脚本实测（两路径
 * `stat -c %d:%i` 同为 `27:22`）回 `N:0`，源目录内容被清空，界面只报「移动失败」。
 * 复制侧同理：目标落进源内部时 `copyRecursively` 会自我复制到存储耗尽。
 *
 * 故所有「目标即源」与「自身/子目录」判据必须走 [RootFileManager.samePhysicalPath]、
 * [RootFileManager.isPhysicallyInside] 与 `ExternalOpen.normalizeSdCardAlias`，
 * 不得比字符串。
 */
class PathIdentityGuardTest {

    private val rfm = File("src/main/java/com/mixradio/droid/data/RootFileManager.kt").readText()
    private val page = File("src/main/java/com/mixradio/droid/ui/pages/FilePage.kt").readText()

    /**
     * 只取单个函数体。右界必须是**紧随其后**的那个函数，否则会把后面的函数一起圈进来，
     * 断言会被相邻函数的实现「代为满足」—— 被保护的那处回退了也照样绿。
     */
    private fun rfmBody(start: String, end: String): String =
        rfm.substringAfter(start).substringBefore(end)

    private val moveFileBody: String get() = rfmBody("suspend fun moveFile(", "suspend fun moveBatch(")
    private val moveBatchBody: String get() = rfmBody("suspend fun moveBatch(", "suspend fun moveBatchConflicts(")
    private val copyBody: String get() = rfmBody("suspend fun copyToDirectory(", "suspend fun moveDestinationCollides(")

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    // ── 判等语义本身 ───────────────────────────────────────────────
    @Test
    fun `sdcard 别名与尾斜杠必须判为同一目录`() {
        assertTrue(
            RootFileManager.samePhysicalPath("/sdcard/Download", "$INTERNAL_STORAGE_PATH/Download")
        )
        assertTrue(
            RootFileManager.samePhysicalPath("/sdcard/Download/", "$INTERNAL_STORAGE_PATH/Download")
        )
        assertTrue(RootFileManager.samePhysicalPath("/sdcard", INTERNAL_STORAGE_PATH))
    }

    @Test
    fun `点号与父目录写法必须判为同一路径`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "shso_path_identity").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(dir, "x/y").mkdirs()
            assertTrue(RootFileManager.samePhysicalPath("${dir.path}/x/./y", "${dir.path}/x/y"))
            assertTrue(RootFileManager.samePhysicalPath("${dir.path}/x/../x/y", "${dir.path}/x/y"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `不同目录不得误判为同一路径`() {
        assertFalse(RootFileManager.samePhysicalPath("/sdcard/Download", "/sdcard/DCIM"))
        // 名字有共同前缀但不是父子：A 与 AB
        assertFalse(RootFileManager.samePhysicalPath("/sdcard/A", "/sdcard/AB"))
        assertFalse(
            "/sdcard/AB 不是 /sdcard/A 的子目录",
            ExternalOpen.normalizeSdCardAlias("/sdcard/AB")
                .startsWith(ExternalOpen.normalizeSdCardAlias("/sdcard/A") + "/")
        )
        assertTrue(
            "/sdcard/A/sub 是 /sdcard/A 的子目录",
            ExternalOpen.normalizeSdCardAlias("/sdcard/A/sub")
                .startsWith(ExternalOpen.normalizeSdCardAlias("/sdcard/A") + "/")
        )
        assertFalse(
            RootFileManager.samePhysicalPath("/data/adb/a", "$INTERNAL_STORAGE_PATH/a")
        )
    }

    /**
     * 共享存储大小写不敏感（真机实测 `/sdcard/dOwnLoad` 与 `/sdcard/Download` 同 inode），
     * 而 realpath / canonicalPath 不折叠大小写，只有 inode 能判等。
     * 宿主文件系统区分大小写时无法构造该场景，跳过断言（不引入 skip 计数）。
     */
    @Test
    fun `大小写变体必须判为同一路径`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "shso_path_case").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(dir, "casedir/inner").mkdirs()
            if (!File(dir, "CaseDir").isDirectory) return  // 宿主区分大小写，构造不出
            assertTrue(
                "大小写变体必须判为同一目录",
                RootFileManager.samePhysicalPath("${dir.path}/CaseDir", "${dir.path}/casedir")
            )
            assertTrue(
                "前缀比不出来的父子关系必须靠物理层级判出来",
                RootFileManager.isPhysicallyInside("${dir.path}/CASEDIR/inner", "${dir.path}/casedir")
            )
            assertFalse(
                "兄弟目录不得判成内部",
                RootFileManager.isPhysicallyInside("${dir.path}/casedir2", "${dir.path}/casedir")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 调用点契约：三处「目标即源」判据必须用同一实现 ────────────────
    @Test
    fun `moveFile 的两处判据按物理路径`() {
        val body = moveFileBody
        assertTrue(
            "moveFile 的目标目录即源守卫必须按物理路径：$body",
            body.contains("if (samePhysicalPath(sourceNormalized, destinationNormalized)) {")
        )
        assertTrue(
            "moveFile 的自身目标守卫必须按物理路径：$body",
            body.contains(
                "if (samePhysicalPath(destinationPath, sourceNormalized) " +
                    "&& onConflict != MoveDestinationConflict.RENAME)"
            )
        )
        assertEquals(
            "moveFile 的子目录前缀判据只应有一处、且归一后的写法：$body",
            1,
            occurrences(body, "ExternalOpen.normalizeSdCardAlias(destinationNormalized)")
        )
    }

    @Test
    fun `moveBatch 的两处判据按物理路径`() {
        val body = moveBatchBody
        assertTrue(
            "moveBatch 阶段 0 的自身目标守卫必须按物理路径：$body",
            body.contains("if (samePhysicalPath(destinationPath, sourceNormalized)) {")
        )
        assertTrue(
            "moveBatch 的目标目录即源守卫必须按物理路径：$body",
            body.contains("if (samePhysicalPath(sourceNormalized, destNormalized)) {")
        )
        assertEquals(
            "moveBatch 的子目录前缀判据只应有一处、且归一后的写法：$body",
            1,
            occurrences(body, "ExternalOpen.normalizeSdCardAlias(destNormalized)")
        )
    }

    @Test
    fun `copyToDirectory 三处判据按物理路径`() {
        val body = copyBody
        assertTrue(
            "复制到自身目录会先删源：$body",
            body.contains("if (samePhysicalPath(sourceNormalized, destinationNormalized)) {")
        )
        assertTrue(
            "复制进自身子目录会让 copyRecursively 自我复制：$body",
            body.contains(
                "if (samePhysicalPath(destinationPath, sourceNormalized) " +
                    "&& onConflict != MoveDestinationConflict.RENAME)"
            )
        )
        assertTrue(
            "复制进自身子目录还要有物理层级判定（大小写变体 / 软链祖先）：$body",
            body.contains("isPhysicallyInside(destinationNormalized, sourceNormalized)")
        )
        assertEquals(
            "copyToDirectory 的子目录前缀判据只应有一处、且归一后的写法：$body",
            1,
            occurrences(body, "ExternalOpen.normalizeSdCardAlias(destinationNormalized)")
        )
    }

    @Test
    fun `三处前缀判据都归一别名且不退回字面比较`() {
        assertFalse(
            "不得退回 moveFile 的字面前缀比较",
            rfm.contains("destinationNormalized.startsWith(\"\$sourceNormalized/\")")
        )
        assertFalse(
            "不得退回 moveBatch 的字面前缀比较",
            rfm.contains("destNormalized.startsWith(\"\$sourceNormalized/\")")
        )
    }

    @Test
    fun `两列同目录拦截按物理路径且不再有字面比较`() {
        val body = page.substringAfter("fun moveToOtherPane(item: FileItem)")
            .substringBefore("// 打开方式")
        assertTrue(
            "两列同目录拦截必须按物理路径判等：$body",
            body.contains("RootFileManager.samePhysicalPath(File(item.path).parent ?: \"/\", dest)")
        )
        assertFalse("不得退回 trimEnd 的字面比较", body.contains("fun normPath("))
    }
}
