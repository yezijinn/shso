// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import com.mixradio.droid.data.syntax.SyntaxPackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 第四轮深挖的回归：外部唤起的路径信任边界、临时文件可预测性、
 * 批量重命名的覆盖语义、TSV 分隔符注入、解压目标目录自指。
 */
class ExternalTrustAndTempFileTest {

    // ---------- ① 外部 Intent 的路径信任边界 ----------

    @Test fun `外部 file URI 不得指向本进程私有目录`() {
        // 导出 alias 无 android:permission，任意应用可构造
        // `file:///data/data/com.mixradio.droid/shared_prefs/xxx.xml`，
        // shso 随后以 root 身份 stat 并把文本直接渲染 → 跨应用私有数据越权读取。
        assertFalse(
            "本进程私有目录必须拒绝",
            ExternalOpen.isExternalPathAllowed("/data/data/com.mixradio.droid/shared_prefs/root.xml")
        )
        assertFalse(
            "user 0 形态同样拒绝",
            ExternalOpen.isExternalPathAllowed("/data/user/0/com.mixradio.droid/files/a.txt")
        )
    }

    @Test fun `外部 file URI 只允许共享存储与本地临时目录`() {
        assertTrue(ExternalOpen.isExternalPathAllowed("/storage/emulated/0/Download/a.txt"))
        assertTrue(ExternalOpen.isExternalPathAllowed("/storage/emulated/0"))
        assertTrue(ExternalOpen.isExternalPathAllowed("/data/local/tmp/a.sh"))
    }

    @Test fun `外部 file URI 不得越出白名单`() {
        assertFalse(ExternalOpen.isExternalPathAllowed("/system/etc/hosts"))
        assertFalse(ExternalOpen.isExternalPathAllowed("/data/adb/modules"))
        assertFalse(ExternalOpen.isExternalPathAllowed("/data/data/com.other.app/files/x"))
        assertFalse(ExternalOpen.isExternalPathAllowed("/"))
        assertFalse(ExternalOpen.isExternalPathAllowed(""))
    }

    @Test fun `前缀相似但不同的路径不被误放行`() {
        // `/storage/emulated/01` 不是 `/storage/emulated/0` 的子目录：
        // 判定必须按「完整一段」比较，不能用裸 startsWith
        assertFalse(ExternalOpen.isExternalPathAllowed("/storage/emulated/01/a.txt"))
        assertFalse(ExternalOpen.isExternalPathAllowed("/data/local/tmpX/a.txt"))
        // 白名单之外一律拒绝（相似前缀的应用私有目录也不例外）
        assertFalse(ExternalOpen.isExternalPathAllowed("/data/data/com.mixradio.droid.backup/a.txt"))
    }

    @Test fun `目录自身的尾斜杠不影响判定`() {
        assertTrue(ExternalOpen.isExternalPathAllowed("/storage/emulated/0/"))
        assertFalse(ExternalOpen.isExternalPathAllowed("/data/data/com.mixradio.droid/"))
    }

    // ---------- ② TSV 字段转义 ----------

    @Test fun `TSV 字段转义与还原互逆`() {
        val samples = listOf(
            "kotlin",
            "local:/data/local/tmp/a b.json",
            "local:/data/local/tmp/a\tb.json",   // ext4 允许文件名含制表符
            "local:/data/local/tmp/a\nb.json",
            "back\\slash",
            "a\tb\nc\rd\\e"
        )
        for (s in samples) {
            val escaped = SyntaxPackStore.escapeField(s)
            assertFalse("转义后不得残留裸制表符：$escaped", escaped.contains('\t'))
            assertFalse("转义后不得残留裸换行：$escaped", escaped.contains('\n'))
            assertFalse("转义后不得残留裸回车：$escaped", escaped.contains('\r'))
            assertEquals("往返必须还原：$s", s, SyntaxPackStore.unescapeField(escaped))
        }
    }

    @Test fun `含制表符的字段不会撑破 TSV 列数`() {
        val row = listOf(
            "kotlin", "kt,kts,\tjava", "Kotlin.kt", "abc123",
            "local:/data/local/tmp/x\ty.json", "1024", "1700000000000", "1"
        ).joinToString("\t") { SyntaxPackStore.escapeField(it) }
        assertEquals("转义后仍必须是 8 列", 8, row.split('\t').size)
        val parsed = row.split('\t').map { SyntaxPackStore.unescapeField(it) }
        assertEquals("source 字段应完整还原", "local:/data/local/tmp/x\ty.json", parsed[4])
        assertEquals("启用位必须落在第 8 列", "1", parsed[7])
    }

    @Test fun `无转义序列的普通字段原样通过`() {
        assertEquals("kotlin", SyntaxPackStore.unescapeField("kotlin"))
        assertEquals("a,b", SyntaxPackStore.unescapeField("a,b"))
    }

    // ---------- ③ 解压目标目录不得被条目本身命中 ----------

    @Test fun `条目名为空或点号时不得解析成目标目录本身`() {
        val target = System.getProperty("java.io.tmpdir") + "/shso_sd_" + System.nanoTime()
        val base = java.io.File(target)
        base.mkdirs()
        try {
            for (name in listOf("", ".", "..", "./", "/")) {
                // 归一化后条目名为空：不带 fallbackName 时直接拒绝（返回 null）。
                // 关键点无论拒绝还是给占位名，都**不得**命中目标目录本身 ——
                // 那会让随后的 FileOutputStream(target) 抛异常并被兜底成
                // dest.delete()，把用户预期的目标目录删掉。
                val dest = ArchiveExtractor.safeDest(target, name)
                assertNotEquals(
                    "条目名 '$name' 不得命中目标目录本身",
                    base.canonicalPath,
                    dest?.canonicalPath ?: ""
                )
                // 显式给占位名时必须落在目标目录内。
                // 占位名必须自身合法（不含分隔符、不为 . / ..）——
                // 直接把 entryName 当占位名会在 entryName == ".." 时再次落空。
                val placeholder = "unnamed_" + name.hashCode().toString(16)
                val withFallback = ArchiveExtractor.safeDest(target, name, fallbackName = placeholder)
                    ?: throw AssertionError("带合法占位名时不得拒绝：$name")
                assertNotEquals("条目名 '$name' 不得命中目标目录本身", base.canonicalPath, withFallback.canonicalPath)
                assertTrue("条目名 '$name' 必须落在目标目录内：" + withFallback.path,
                    withFallback.canonicalPath.startsWith(base.canonicalPath + java.io.File.separator))
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test fun `正常条目名仍按原规则解析`() {
        val target = System.getProperty("java.io.tmpdir") + "/shso_sd2_" + System.nanoTime()
        val base = java.io.File(target)
        base.mkdirs()
        try {
            val d = ArchiveExtractor.safeDest(target, "abc/def.txt")
                ?: throw AssertionError("正常条目名不得被拒绝")
            assertEquals(
                "abc/def.txt",
                d.canonicalPath.removePrefix(base.canonicalPath + java.io.File.separator)
                    .replace('\\', '/')
            )
        } finally {
            base.deleteRecursively()
        }
    }

    // ---------- ④ zip 炸弹：预算检查必须早于无界分配 ----------

    @Test fun `entry 内容超过剩余预算时立即抛错而非先物化`() {
        // 原实现 `zin.readBytes()` 先把整个 entry 物化（单 entry 可声明数 GB），
        // 再判总量 —— 预算检查形同虚设，必然 OOM。
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zos ->
            zos.putNextEntry(ZipEntry("big.bin"))
            // 高度可压缩：1MB 随机度低的填充压缩后极小
            val filler = ByteArray(1024 * 1024) { (it % 7).toByte() }
            repeat(3) { zos.write(filler) }
            zos.closeEntry()
        }
        val zipBytes = bomb.toByteArray()
        assertTrue("构造的炸弹压缩后应远小于未压缩体积", zipBytes.size < 3 * 1024 * 1024)

        var thrown = false
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
                zin.nextEntry
                // 剩余预算给 100KB，远小于 3MB 的声明体积
                SyntaxPackStore.readEntryBounded(zin, 100 * 1024)
            }
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("超预算必须抛 IllegalArgumentException（体积上限）", thrown)
    }

    @Test fun `预算充足时正常读完整个 entry`() {
        val src = ByteArray(50 * 1024) { (it % 251).toByte() }
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zos ->
            zos.putNextEntry(ZipEntry("ok.bin"))
            zos.write(src)
            zos.closeEntry()
        }
        ZipInputStream(ByteArrayInputStream(bomb.toByteArray())).use { zin ->
            zin.nextEntry
            val got = SyntaxPackStore.readEntryBounded(zin, 1024 * 1024)
            assertEquals("内容应完整读出", src.size, got.size)
            assertTrue("内容应逐字节一致", got.contentEquals(src))
        }
    }

    @Test fun `剩余预算为 0 时空 entry 合法、非空 entry 抛错`() {
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zos ->
            zos.putNextEntry(ZipEntry("empty.bin"))
            zos.closeEntry()
        }
        ZipInputStream(ByteArrayInputStream(bomb.toByteArray())).use { zin ->
            zin.nextEntry
            assertEquals(0, SyntaxPackStore.readEntryBounded(zin, 0).size)
        }
        var thrown = false
        try {
            SyntaxPackStore.readEntryBounded(ByteArrayInputStream(ByteArray(1)), 0)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("剩余预算 0 且有内容时必须抛错", thrown)
    }
}
