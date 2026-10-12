// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import com.mixradio.droid.data.syntax.SyntaxPackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 语法包导入的事务性回归。
 *
 * 这些用例不需要 Android Context：断言的是「导入失败时不得破坏已有语法文件」这条
 * 不变式所依赖的落盘/回滚结构，以及 zip 路径必须真的比对 SHA-256。
 */
class SyntaxPackTransactionTest {

    private fun grammar(id: String, marker: String): ByteArray =
        """{"tokenizer":{"root":[{"regex":"$marker","token":"keyword"}]}}""".toByteArray()

    private fun buildZip(target: File, vararg ids: String): File {
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            for (id in ids) {
                zos.putNextEntry(ZipEntry("grammars/$id.json"))
                zos.write(grammar(id, "A$id"))
                zos.closeEntry()
            }
        }
        return target
    }

    @Test fun `回滚必须还原被覆盖的旧内容而不是删除`() {
        // 回归护栏：更新语法包时目标文件本就存在，原实现把它塞进 `written`，
        // 失败后统一 `delete()` —— 原本能正常高亮的语法被删光，而清单仍指向它，
        // 用户侧表现是「某些语言突然没有高亮」且无任何提示。
        // 调用的是生产代码的 writeGrammarFile / rollback，不是用例里复刻的同名逻辑。
        val dir = File.createTempFile("grammars", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            val target = File(dir, "kotlin.json")
            val original = grammar("kotlin", "ORIGINAL")
            target.writeBytes(original)

            val written = mutableListOf<SyntaxPackStore.FileWrite>()
            val replacement = grammar("kotlin", "REPLACEMENT")
            SyntaxPackStore.writeGrammarFile(target, replacement, written)
            assertEquals("写入后应是被覆盖的新内容", replacement.toList(), target.readBytes().toList())

            SyntaxPackStore.rollback(written)

            assertTrue("回滚后文件必须仍存在", target.isFile)
            assertEquals("回滚后必须还原为导入前的内容", original.toList(), target.readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `回滚只删除本次新建的文件`() {
        val dir = File.createTempFile("grammars_new", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            val fresh = File(dir, "python.json")
            assertFalse("导入前不存在", fresh.exists())

            val written = mutableListOf<SyntaxPackStore.FileWrite>()
            SyntaxPackStore.writeGrammarFile(fresh, grammar("python", "NEW"), written)
            assertTrue("写入后应存在", fresh.isFile)

            SyntaxPackStore.rollback(written)

            assertFalse("新建的文件在回滚后应被删除", fresh.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `整包上限约束回滚内存占用`() {
        // writeGrammarFile 会把被覆盖文件读进内存备份，量级必须受整包上限约束，
        // 否则一个恶意大包能把堆吃光。
        val s = File("src/main/java/com/mixradio/droid/data/syntax/SyntaxPackStore.kt").readText()
        assertTrue("必须存在整包体积上限常量", s.contains("MAX_ZIP_BYTES"))
        assertTrue("下载侧必须按该上限收口", s.contains("MAX_ZIP_BYTES"))
    }

    @Test fun `zip 单条目必须与单文件同受体积上限约束`() {
        // zip 分支若只受整包上限（2MB）约束，单条目上限就被放宽到 4 倍，
        // 用一个压缩包即可绕开单文件导入的 512KB 校验。
        val s = File("src/main/java/com/mixradio/droid/data/syntax/SyntaxPackStore.kt").readText()
        val fn = s.indexOf("private fun importZip(")
        assertTrue("应能找到 importZip", fn > 0)
        val body = s.substring(fn, fn + 3000)
        assertTrue(
            "逐条目读取后必须复核单条目上限",
            body.contains("require(data.size <= MAX_BYTES)")
        )
    }

    @Test fun `zip 导入路径必须比对 sha256`() {
        // 回归护栏：importFromUrl 的 zip 分支曾直接把 expectedSha256 丢掉，
        // 而「从仓库下载」预填的正是 zip 直链、弹窗文案写着「填写则校验」。
        val s = File("src/main/java/com/mixradio/droid/data/syntax/SyntaxPackStore.kt").readText()
        val fn = s.indexOf("fun importFromUrl")
        assertTrue("应能找到 importFromUrl", fn > 0)
        val body = s.substring(fn, fn + 1600)
        val zipBranch = body.indexOf("importZip(ctx, bytes, trimmed)")
        assertTrue("应存在 zip 分支", zipBranch > 0)
        val beforeZip = body.substring(0, zipBranch)
        assertTrue(
            "zip 分支之前必须先比对摘要",
            beforeZip.contains("sha256(bytes)") && beforeZip.contains("expectedSha256")
        )
    }

    @Test fun `语法包 zip 结构可被正确解析`() {
        val zip = File.createTempFile("packs", ".zip")
        try {
            buildZip(zip, "kotlin", "python")
            val names = mutableListOf<String>()
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var e = zin_entry(zis)
                while (e != null) {
                    names += e.name
                    zis.closeEntry()
                    e = zin_entry(zis)
                }
            }
            assertEquals(listOf("grammars/kotlin.json", "grammars/python.json"), names)
            assertTrue("语法 JSON 必须含 tokenizer", grammar("kotlin", "A").toString(Charsets.UTF_8).contains("tokenizer"))
        } finally {
            zip.delete()
        }
    }

    private fun zin_entry(zis: java.util.zip.ZipInputStream): ZipEntry? = zis.nextEntry
}
