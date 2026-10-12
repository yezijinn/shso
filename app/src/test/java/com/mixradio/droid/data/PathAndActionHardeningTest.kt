// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 添加文件入口（同名目标）、ROOT 探测回收、编辑器高亮阈值、FileProvider 暴露面、
 * 主题冷启动底色、文件动作作用域与移动冲突策略、编辑历史随文件动作回收与迁移、
 * 共享存储根的按用户取值等源码契约护栏。
 *
 * 这些缺陷的共同点是「不报错、不崩溃，只在特定时序/特定路径下静默出错」，
 * 行为验证要真实共享存储、真实 su 与真实旋屏，单测只能把源码结构钉住，
 * 避免后续重构悄悄改回去。行为验证见真机记录。
 */
class PathAndActionHardeningTest {

    private fun root(rel: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到文件: $rel")
    }

    private val rootFileManager = root("app/src/main/java/com/mixradio/droid/data/RootFileManager.kt")
    private val permissionChecker = root("app/src/main/java/com/mixradio/droid/data/PermissionChecker.kt")
    private val appSettings = root("app/src/main/java/com/mixradio/droid/data/AppSettings.kt")
    private val soraEditor = root("app/src/main/java/com/mixradio/droid/ui/components/SoraTextEditor.kt")
    private val textEditor = root("app/src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt")
    private val filePage = root("app/src/main/java/com/mixradio/droid/ui/pages/FilePage.kt")
    private val providerPaths = root("app/src/main/res/xml/file_provider_paths.xml")
    private val gradleProperties = root("gradle.properties")

    private val themes = listOf(
        "app/src/main/res/values/themes.xml",
        "app/src/main/res/values-v27/themes.xml",
        "app/src/main/res/values-night/themes.xml",
        "app/src/main/res/values-night-v27/themes.xml"
    )

    // ---------- 添加文件到 shso：同名目标 ----------

    @Test fun `添加文件到 shso 前必须探测同名目标`() {
        val body = rootFileManager.substringAfter("suspend fun addFileToShso(").substringBefore("suspend fun rename(")
        assertTrue(
            "cp -r 落到已存在的同名目录上会把源拷进其内部（X/X 嵌套），目标是同名文件时则被静默覆盖",
            body.contains("destinationExists(destinationPath, sourcePath)")
        )
        assertTrue(
            "独立目录名必须用完整 epoch 毫秒：取模 100000 只有 100 秒周期，同帧内多次添加会撞进同一目录",
            !body.contains("% 100000")
        )
    }

    @Test fun `添加文件到 shso 的目录名必须过滤控制字符`() {
        val body = rootFileManager.substringAfter("suspend fun addFileToShso(").substringBefore("suspend fun rename(")
        assertTrue(
            "目录名会拼进 shell 与路径段，控制字符/分隔符必须替换，首尾点与空格会与目录穿越混淆",
            body.contains("isISOControl()")
        )
    }

    // ---------- ROOT 探测：超时后不得无界等待 ----------

    @Test fun `ROOT 探测超时后不得无界等待`() {
        assertTrue(
            "su 卡在不可中断状态时，无参 waitFor() 会把 IO 线程永久占住，销毁后也必须按超时有界回收",
            !permissionChecker.contains("process.waitFor()")
        )
        assertTrue(
            "销毁后的回收必须有界",
            permissionChecker.contains("process.waitFor(ROOT_CHECK_TIMEOUT_MS")
        )
    }

    // ---------- 编辑器高亮阈值 ----------

    @Test fun `编辑器高亮阈值必须按当前文本长度判定`() {
        assertTrue(
            "只用打开时的 initialText.length 判定：超大文本经查找替换缩短到阈值内仍不启用高亮，且 initialText 不变无法恢复",
            soraEditor.contains("if (controller.isAttached) controller.charCount() else initialText.length")
        )
        assertTrue(
            "remember 的键必须包含内容修订号，否则文本变化不会重估阈值",
            soraEditor.contains("remember(fileName, contentRevision, syntaxRevision)")
        )
        assertTrue(
            "调用方必须把文本修订号传给编辑器",
            textEditor.contains("contentRevision = textRevision")
        )
    }

    // ---------- FileProvider 暴露面 ----------

    @Test fun `FileProvider 不得暴露整棵私有目录`() {
        assertFalse(
            "files 目录没有任何 getUriForFile 调用方，暴露整棵等于扩大读取面",
            providerPaths.contains("files-path")
        )
        assertTrue(
            "cache 只应暴露安装暂存目录，而非整棵 cache",
            providerPaths.contains("""<cache-path name="cache" path="install-confirm/" />""")
        )
    }

    // ---------- 主题冷启动底色 ----------

    @Test fun `四个主题变体都必须声明冷启动窗口底色`() {
        themes.forEach { rel ->
            val xml = root(rel)
            assertTrue(
                "$rel 缺 android:windowBackground：应用恒为暗色，系统浅色模式下冷启动先铺白再切暗，出现白闪",
                xml.contains("android:windowBackground")
            )
            assertTrue("$rel 的窗口底色必须是 Aurora 渐变起始色", xml.contains("#FF0C1626"))
        }
    }

    @Test fun `v27 主题必须复列基础条目`() {
        // style 资源按配置整体择一，-v27 的 Theme.shso 会完全取代 values/ 下的同名 style，
        // 只写 windowLightNavigationBar 会让底色与系统栏设置在该配置下全部丢失。
        listOf("app/src/main/res/values-v27/themes.xml", "app/src/main/res/values-night-v27/themes.xml")
            .forEach { rel ->
                val xml = root(rel)
                assertTrue("$rel 缺 statusBarColor", xml.contains("android:statusBarColor"))
                assertTrue("$rel 缺 windowLightStatusBar", xml.contains("android:windowLightStatusBar"))
            }
    }

    @Test fun `Gradle 弃用警告不得完全静默`() {
        assertTrue(
            "warning.mode=none 会让新增弃用一并静默；summary 仍保留计数与标题",
            gradleProperties.contains("org.gradle.warning.mode=summary")
        )
    }

    // ---------- 文件动作作用域与移动冲突策略 ----------

    @Test fun `文件动作必须使用不随组合销毁的作用域与进程级标志`() {
        assertTrue(
            "解压/拷贝/移动底层是不可撤销的 su 副作用，页面级 scope 在旋屏后取消只会让收尾丢失、入口重新可点",
            filePage.contains("private val actionJobScope = CoroutineScope")
        )
        listOf("isExtracting", "isCopying", "isMoving").forEach { flag ->
            assertFalse(
                "$flag 若仍是页面级 remember，旋屏归零后入口重新可点，会在同一目标目录叠加一次操作",
                filePage.contains("var $flag by remember")
            )
        }
    }

    @Test fun `无冲突批量移动必须显式声明 SKIP`() {
        assertTrue(
            "探测与执行之间目标可能被并发写入，默认 OVERWRITE 会静默覆盖别人的文件",
            filePage.contains("onConflict = MoveDestinationConflict.SKIP")
        )
    }

    // ---------- 编辑历史随文件动作回收与迁移 ----------

    @Test fun `删除成功必须回收该路径的编辑历史`() {
        // 历史键以绝对路径命名，删除后不回收就留下永不释放的 prefs 条目；
        // 删目录时其下每个文件的历史也各占一个键。
        val body = rootFileManager.substringAfter("suspend fun delete(")
            .substringBefore("suspend fun copyFile(")
        assertTrue(
            "删除成功后必须回收该路径的编辑历史",
            body.contains("EditHistoryManager.forget(path)")
        )
        val hooks = body.split("return@withContext removed()").size - 1
        assertTrue(
            "rm -rf / 符号链接 / Java 回退三条成功路径都要经过 removed()，实际=$hooks",
            hooks >= 3
        )
        assertFalse(
            "仍有未接回收的裸成功返回",
            body.contains("return@withContext Pair(true, \"删除成功\")")
        )
    }

    @Test fun `重命名与移动必须把编辑历史迁到新路径`() {
        val renameBody = rootFileManager.substringAfter("suspend fun rename(oldPath")
            .substringBefore("suspend fun moveFile(")
        assertTrue(
            "历史键仍指向旧路径时，重命名等于让该文件的历史凭空消失",
            renameBody.contains("EditHistoryManager.moveHistory(oldPath, newPath)")
        )
        assertEquals(
            "mv 与 File.renameTo 两条成功路径都要迁移",
            2, renameBody.split("return@withContext renamed()").size - 1
        )

        val moveBody = rootFileManager.substringAfter("suspend fun moveFile(")
            .substringBefore("suspend fun moveDestinationCollides(")
        assertTrue(
            "冲突改名后实际落点是 finalPath，历史必须跟到 finalPath 而非计划中的目标目录",
            moveBody.contains("EditHistoryManager.moveHistory(sourcePath, finalPath)")
        )
        assertEquals(
            "renameTo 与 mv 两条成功路径都要迁移",
            2, moveBody.split("return@withContext moved()").size - 1
        )
    }

    // ---------- 多用户：共享存储根 ----------

    @Test fun `共享存储根必须运行时取值而非写死 user 0`() {
        // 系统按 userId 挂载共享存储：主用户 /storage/emulated/0，次用户 /storage/emulated/10。
        // 写死 0 会让次用户下的提取落点、收件箱、文件页起点与「内部存储」显示指向别人的存储。
        val pathDisplay = root("app/src/main/java/com/mixradio/droid/data/PathDisplay.kt")
        assertTrue(
            "共享存储根须按当前用户推导",
            pathDisplay.contains("Environment.getExternalStorageDirectory()")
        )
        assertTrue(
            "推导结果须校验形状，个别 ROM 的异常路径不得进白名单",
            pathDisplay.contains("^/storage/emulated/[0-9]+$")
        )
        assertTrue("推导失败须回落到主用户路径", pathDisplay.contains("PRIMARY_STORAGE_PATH"))

        listOf(
            "app/src/main/java/com/mixradio/droid/data/ApkExtractor.kt",
            "app/src/main/java/com/mixradio/droid/data/ExternalOpen.kt",
            "app/src/main/java/com/mixradio/droid/ui/pages/FilePage.kt",
            "app/src/main/java/com/mixradio/droid/ui/pages/HomePage.kt",
            "app/src/main/java/com/mixradio/droid/ui/components/BuiltInFilePicker.kt"
        ).forEach { rel ->
            assertFalse(
                "$rel 不得再写死 /storage/emulated/0",
                codeOnly(root(rel)).contains("/storage/emulated/0")
            )
        }
    }

    /** 去掉整行注释：注释里提到该路径是说明，不是硬编码。 */
    private fun codeOnly(text: String): String = text.lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }
        .joinToString("\n")

    // ---------- 书签持久化 ----------

    @Test fun `书签必须拒绝换行与控制字符`() {
        val body = appSettings.substringAfter("fun addBookmark(").substringBefore("fun removeBookmark(")
        assertTrue(
            "书签以换行分隔字符串持久化，路径里带换行会让读回时被 split 成多条，一个目录名就能污染整份列表",
            body.contains("'\\u0000'") && body.contains("'\\n'")
        )
    }
}
