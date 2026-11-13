// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二轮 su 浪费治理的源码与纯函数护栏。
 *
 * 一次 `RootService.runCommandSync` = 一次 `su -c` 进程（真机 145~173ms，任务书附录）。
 * 本轮把「每项一条 su」的五个入口压成「整批一条 / 一次操作一条」。这里钉三件事：
 *  1. 命令形态 —— 单条 / 分段不超预算 / 完成标记必须是最后一句；
 *  2. 解析语义 —— 缺标记绝不判成功（这条比性能重要）；
 *  3. 安全判据不得被顺手删掉 —— 不覆盖、O_EXCL 原子占位、只删目录不删文件目标。
 *
 * JVM 测试只能钉源码结构与纯函数，**行为**验证（真实 su、真实 /data/adb）见真机记录。
 */
class SuWasteRound2Test {

    private fun root(rel: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("找不到文件: $rel")
    }

    /** 取 `from` 之后、`until` 之前的源码切片（`until` 找不到时取到文件尾）。 */
    private fun slice(source: String, from: String, until: String): String {
        val start = source.indexOf(from)
        assertTrue("应能定位 $from", start >= 0)
        val end = source.indexOf(until, start)
        return source.substring(start, if (end > start) end else source.length)
    }

    private val rfm = root("app/src/main/java/com/mixradio/droid/data/RootFileManager.kt")
    private val apk = root("app/src/main/java/com/mixradio/droid/data/ApkInstaller.kt")
    private val compare = root("app/src/main/java/com/mixradio/droid/data/TextCompare.kt")
    private val filePage = root("app/src/main/java/com/mixradio/droid/ui/pages/FilePage.kt")

    // ── #5 添加到 shso ────────────────────────────────────────────────

    @Test
    fun `添加到 shso 单条命令即完成判重与拷贝`() {
        val cmd = RootFileManager.buildAddToShsoCommand(
            sourcePath = "/sdcard/Download/run.sh",
            targetDir = "/data/adb/shso/run_1789045123456",
            destinationPath = "/data/adb/shso/run_1789045123456/run.sh",
            independentFolder = true,
            autoDeleteSource = true
        )
        val bytes = cmd.toByteArray(Charsets.UTF_8).size
        assertTrue("单条命令 $bytes 字节超上限", bytes <= RootFileManager.ADD_TO_SHSO_MAX_BYTES)
        assertTrue("建目标目录", cmd.contains("mkdir -p \"\$2\""))
        assertTrue("基目录也要放开权限", cmd.contains("chmod 777 /data/adb/shso"))
        assertTrue(
            "不覆盖判据必须在拷贝之前，且认断链软链",
            cmd.indexOf("[ -e \"\$3\" ] || [ -L \"\$3\" ]") in 0 until cmd.indexOf("cp -r \"\$1\"")
        )
        assertTrue("自动删源", cmd.contains("rm -rf -- \"\$1\""))
        assertTrue(
            "完成标记必须是最后一句",
            cmd.trimEnd().endsWith(RootFileManager.ADD_TO_SHSO_DONE_MARKER)
        )
        assertTrue("RC 行必须先于标记", cmd.indexOf("RC:\$rc") in 0 until cmd.indexOf(RootFileManager.ADD_TO_SHSO_DONE_MARKER))
        assertTrue("路径只出现一次（set -- 打包）", cmd.countSubstring("/sdcard/Download/run.sh") == 1)
        assertFalse("任何一步都不得吞 stderr（失败文案要带原因）", cmd.contains("2>/dev/null"))
    }

    @Test
    fun `添加到 shso 关掉自动删源时不生成 rm`() {
        val cmd = RootFileManager.buildAddToShsoCommand(
            sourcePath = "/sdcard/Download/run.sh",
            targetDir = "/data/adb/shso",
            destinationPath = "/data/adb/shso/run.sh",
            independentFolder = false,
            autoDeleteSource = false
        )
        assertFalse("不得出现 rm -rf", cmd.contains("rm -rf"))
        assertFalse("固定目录模式已由 chmod 777 \"$2\" 覆盖基目录", cmd.contains("chmod 777 /data/adb/shso"))
    }

    @Test
    fun `添加到 shso 目录源递归放权文件源不递归`() {
        val cmd = RootFileManager.buildAddToShsoCommand(
            sourcePath = "/sdcard/Download/sub",
            targetDir = "/data/adb/shso",
            destinationPath = "/data/adb/shso/sub",
            independentFolder = false,
            autoDeleteSource = false
        )
        assertTrue("目录分支必须递归放权", cmd.contains("${RootFileManager.CHMOD_RECURSIVE_777} \"\$3\""))
        assertTrue("文件分支不得递归", cmd.contains("chmod 777 \"\$3\""))
        assertTrue(
            "递归 chmod 必须在目录分支里（目录判定由 root 视角做）",
            cmd.indexOf("if [ -d \"\$1\" ]") in 0 until cmd.indexOf(RootFileManager.CHMOD_RECURSIVE_777)
        )
    }

    @Test
    fun `添加到 shso 缺标记缺 RC 都不算完成`() {
        assertFalse("缺标记不得判完成", RootFileManager.parseAddToShsoOutput("RC:0\n").done)
        assertNull("无可用 RC 行时退出码未知", RootFileManager.parseAddToShsoOutput("RC:x\n").code)
        val parsed = RootFileManager.parseAddToShsoOutput(
            "RC:2\n${RootFileManager.ADD_TO_SHSO_DONE_MARKER}\n"
        )
        assertTrue(parsed.done)
        // 断言 Int? 时先落成非空 Int：assertEquals(int, Int?) 会解析成装箱重载
        assertEquals(2, parsed.code ?: -1)
    }

    @Test
    fun `同名判定不再单独起一条 su`() {
        val body = slice(rfm, "private suspend fun destinationExists(", "suspend fun moveFile(")
        assertFalse("本地快路径不得再起 su", body.contains("runCommandSync"))
        assertTrue(
            "保留「与源同路径不算冲突」的例外，且两侧都要归一尾斜杠",
            body.contains("if (newPath.trimEnd('/') == oldPath.trimEnd('/')) return false")
        )
    }

    @Test
    fun `递归 chmod 常量必须真的被命令生成器引用`() {
        // 既有断言只钉「源码里有 "chmod -R 777" 这个字面量」。
        // 抽成常量后若没人引用，那条断言仍绿而功能已丢 —— 这里补上引用检查。
        assertTrue("字面量必须仍在源码里（既有护栏的锚点）", rfm.contains("\"chmod -R 777\""))
        assertTrue(
            "常量不得成为孤儿：目录分支必须用它拼命令",
            rfm.contains(".append(CHMOD_RECURSIVE_777)")
        )
    }

    // ── #7 多选原地拷贝 ───────────────────────────────────────────────

    @Test
    fun `原地拷贝批量后每项仍用 O_EXCL 原子占位取唯一名`() {
        val items = (0 until 120).map {
            RootFileManager.CopyPlanItem(
                "/storage/emulated/0/Download/file$it.txt", "file$it", ".txt"
            )
        }
        val segments = RootFileManager.planCopySegments(items)
        assertTrue("必须分段而不是一项一段", segments.size < items.size / 10)
        var total = 0
        for (segment in segments) {
            val cmd = RootFileManager.buildCopySegmentCommand(segment)
            val bytes = cmd.toByteArray(Charsets.UTF_8).size
            assertTrue("段命令 $bytes 字节超上限", bytes <= RootFileManager.COPY_SEGMENT_MAX_BYTES)
            assertTrue("必须用 set -C 做 O_EXCL 占位", cmd.contains("set -C"))
            assertTrue("权限口径不得回退", cmd.contains("chmod a-s,go-w"))
            assertTrue(
                "每项必须从 n=0 起探测（唯一名语义）",
                cmd.contains("p=\${s%/*}; n=0; ok=0")
            )
            assertTrue(
                "完成标记必须是最后一句",
                cmd.trimEnd().endsWith(RootFileManager.COPY_SEGMENT_DONE_MARKER)
            )
            total += segment.size
        }
        assertEquals("不得丢项", items.size, total)
    }

    @Test
    fun `原地拷贝缺标记或未知码一律不算成功`() {
        assertFalse(
            "缺标记不得判完成",
            RootFileManager.parseCopySegmentOutput("OK:0:/x\n", 1).done
        )
        val parsed = RootFileManager.parseCopySegmentOutput(
            "OK:0:/x_0.txt\nNG:1\nNM:2\n${RootFileManager.COPY_SEGMENT_DONE_MARKER}\n", 3
        )
        assertTrue(parsed.done)
        assertEquals('K', parsed.codes[0] ?: ' ')
        assertEquals("/x_0.txt", parsed.paths[0] ?: "")
        assertEquals('N', parsed.codes[1] ?: ' ')
        assertEquals('M', parsed.codes[2] ?: ' ')
        assertNull("越界索引必须忽略", parsed.codes.getOrNull(5))
        assertNull(
            "段跑完但本项无回执：码必须为 null（调用方据此判「未完成」而不是成功）",
            RootFileManager.parseCopySegmentOutput(
                "${RootFileManager.COPY_SEGMENT_DONE_MARKER}\n", 1
            ).codes[0]
        )
    }

    @Test
    fun `原地拷贝结果必须把失败与未完成分档`() {
        // 「su 没送达」与「cp 真失败」是两种事实，混成一个计数会让用户在授权弹窗卡住时
        // 看到成百条假失败。字段层面钉住分档，防止后续被合并回去。
        val empty = RootFileManager.BatchCopyResult()
        assertEquals(0, empty.failedCount)
        assertEquals(0, empty.unprocessedCount)
        val mixed = RootFileManager.BatchCopyResult(
            copied = listOf("/d/a_0.txt"),
            failed = listOf("/d/b.txt"),
            unprocessed = listOf("/d/c.txt"),
            reasons = mapOf("/d/b.txt" to "拷贝文件失败")
        )
        assertEquals(1, mixed.failedCount)
        assertEquals(1, mixed.unprocessedCount)
    }

    @Test
    fun `多选原地拷贝走批量入口且收尾不随组合销毁`() {
        val start = filePage.indexOf("ActionTextRow(\"原地拷贝\",")
        assertTrue("应能找到多选菜单的原地拷贝入口", start > 0)
        val end = filePage.indexOf("ActionTextRow(\"重命名\",", start)
        assertTrue("应能找到下一个菜单项", end > start)
        val block = filePage.substring(start, end)
        assertTrue("必须走批量入口", block.contains("RootFileManager.copyBatch("))
        assertFalse("不得再逐项调 copyFile", block.contains("files.forEach { p ->"))
        assertTrue("失败项仍要计数", block.contains("batch.failedCount"))
        assertTrue("未完成项要单独计数", block.contains("batch.unprocessedCount"))
        assertTrue("选中集必须清理", block.contains("selectedPaths.clear()"))
        assertTrue("必须刷新", block.contains("refresh()"))
        assertTrue("必须用进程级作用域", block.contains("actionJobScope.launch"))
        // 注意：actionJobScope.launch 里的 `Scope` 是大写，不含小写 `scope.launch`
        assertFalse("不得再用页面级 scope", block.contains("scope.launch"))
        assertTrue("收尾必须不可取消", block.contains("NonCancellable"))
    }

    // ── #9 对比结果命名 ──────────────────────────────────────────────

    @Test
    fun `对比结果命名最坏只发一条 su`() {
        val script = TextCompare.buildOutputProbeScript(
            "/storage/emulated/0/Documents/", "文本对比_20261113_", ".txt"
        )
        val bytes = script.toByteArray(Charsets.UTF_8).size
        assertTrue("脚本 $bytes 字节超出单条上限", bytes <= 2900)
        assertTrue(
            "上限必须与 MAX_OUTPUT_PROBE 一致",
            script.contains("while [ \$n -lt ${TextCompare.MAX_OUTPUT_PROBE} ]")
        )
        assertTrue("断链软链也算占用", script.contains("[ ! -L "))
        assertTrue("必须有完成标记", script.contains(TextCompare.OUTPUT_PROBE_DONE_MARKER))
        assertTrue("无空位必须走非零退出", script.trimEnd().endsWith("exit 2"))
    }

    @Test
    fun `对比结果命名只在未授权 root 时走本地探测`() {
        val body = slice(compare, "suspend fun nextOutputPath(", "private fun localOutputPath(")
        val rootAt = body.indexOf("probeOutputIndexAsRoot(base, prefix, ext)")
        val localAt = body.indexOf("return localOutputPath(base, prefix, ext)")
        assertTrue("必须能定位 root 探测", rootAt >= 0)
        assertTrue("必须能定位本地探测", localAt >= 0)
        assertTrue(
            "root 已授权时必须先问 root：目录可 stat 不等于子项可枚举，" +
                "本地判定会选中已占用的名字，末尾 mv 静默覆盖上次结果",
            rootAt < localAt
        )
        assertFalse("不得再逐条 test -e", body.contains("test -e "))
        assertFalse("不得回到 listFiles 判空的老路", body.contains("listFiles"))
    }

    @Test
    fun `对比结果探测三态必须分开`() {
        // 命中
        val hit = TextCompare.parseOutputProbeOutput(
            "N:37\n${TextCompare.OUTPUT_PROBE_DONE_MARKER}\n"
        )
        assertTrue(hit.done)
        assertEquals(37, hit.index ?: -1)
        // 名字全被占用：有标记、无 N 行
        val full = TextCompare.parseOutputProbeOutput(
            "NOMATCH\n${TextCompare.OUTPUT_PROBE_DONE_MARKER}\n"
        )
        assertTrue(full.done)
        assertNull("全占用时 index 必须为 null", full.index)
        // su 未完成：无标记
        assertFalse(
            "缺标记不得判完成（超时/被拒不等于「名字太多」）",
            TextCompare.parseOutputProbeOutput("N:0\n").done
        )
    }

    // ── #10 / #11 分片安装 ───────────────────────────────────────────

    @Test
    fun `分片拷贝压成一条 su 且回传真实大小`() {
        val items = (0 until 40).map {
            ApkInstaller.InstallCopyItem(
                it, "/sdcard/Download/x$it.apk", "/data/local/tmp/_shso_split_t$it.apk"
            )
        }
        val segments = ApkInstaller.planInstallCopySegments(items)
        assertTrue("必须分段而不是一片一段", segments.size < items.size)
        var total = 0
        for (segment in segments) {
            val cmd = ApkInstaller.buildInstallCopySegmentCommand(segment)
            val bytes = cmd.toByteArray(Charsets.UTF_8).size
            assertTrue("段命令 $bytes 字节超上限", bytes <= ApkInstaller.INSTALL_SEGMENT_MAX_BYTES)
            assertTrue(
                "必须回取分片大小：应用 uid stat 不到 /data/local/tmp",
                cmd.contains("stat -c %s")
            )
            assertFalse("stat 失败不得退回 0（fail-open）", cmd.contains("|| s=0"))
            assertTrue("0 字节同样是失败（cp 被截断时会这样）", cmd.contains("[ \"\$s\" -le 0 ]"))
            assertTrue(
                "完成标记必须是最后一句",
                cmd.trimEnd().endsWith(ApkInstaller.INSTALL_COPY_DONE_MARKER)
            )
            total += segment.size
        }
        assertEquals("不得丢项", items.size, total)
    }

    @Test
    fun `分片写入仍逐片 pm 调用但只发一条 su`() {
        val items = (0 until 40).map {
            ApkInstaller.InstallWriteItem(
                it, "split_$it.apk", "/data/local/tmp/_shso_split_t$it.apk", 1024L * (it + 1)
            )
        }
        val segments = ApkInstaller.planInstallWriteSegments(items)
        assertTrue("必须分段", segments.size >= 1)
        var total = 0
        for (segment in segments) {
            val cmd = ApkInstaller.buildInstallWriteSegmentCommand("1234567890", segment)
            val bytes = cmd.toByteArray(Charsets.UTF_8).size
            assertTrue("段命令 $bytes 字节超上限", bytes <= ApkInstaller.INSTALL_SEGMENT_MAX_BYTES)
            assertEquals("每条命令里只应有一个 install-write", 1, cmd.countSubstring("pm install-write"))
            assertTrue("会话 id 必须内联为纯数字", cmd.contains(" -S \"\$3\" 1234567890 "))
            assertTrue(cmd.trimEnd().endsWith(ApkInstaller.INSTALL_WRITE_DONE_MARKER))
            total += segment.size
        }
        assertEquals("不得丢项", items.size, total)
    }

    @Test
    fun `分片暂存与写入各自只发一次 su`() {
        val stageBody = slice(apk, "private suspend fun stageSplits(", "private suspend fun writeSplits(")
        assertEquals("暂存只能有一条 su", 1, stageBody.countSubstring("RootService.runCommandSync"))
        val writeBody = slice(apk, "private suspend fun writeSplits(", "private suspend fun installSplitApksInternal(")
        assertEquals("写入只能有一条 su", 1, writeBody.countSubstring("RootService.runCommandSync"))
        // 逐片 pm 的契约在**命令建造器**里，不在搬运外壳里（外壳只发不拼）。
        val builder = slice(apk, "internal fun buildInstallWriteSegmentCommand(", "internal fun installCopyCostBytes(")
        assertTrue(
            "写入仍必须逐片一次 pm（协议契约），不得改成一次多写",
            builder.contains("pm install-write -S") && builder.contains("shift 3")
        )
    }

    @Test
    fun `分片输出缺标记不得判成功`() {
        assertFalse(ApkInstaller.parseInstallCopySegmentOutput("SZ:0:123\n", 1).done)
        assertFalse(ApkInstaller.parseInstallWriteSegmentOutput("", 1).done)
        val parsed = ApkInstaller.parseInstallCopySegmentOutput(
            "SZ:0:123\nCPF:1\n${ApkInstaller.INSTALL_COPY_DONE_MARKER}\n", 2
        )
        assertTrue(parsed.done)
        assertEquals("cp 失败必须回指到具体下标", 1, parsed.failedIndex ?: -1)
        assertEquals('C', parsed.failedKind)
        assertEquals(123L, parsed.sizes[0])
        assertEquals("没回传的项必须是 -1，绝不能当 0 用", -1L, parsed.sizes[1])
        val stat = ApkInstaller.parseInstallCopySegmentOutput(
            "CPS:0\n${ApkInstaller.INSTALL_COPY_DONE_MARKER}\n", 1
        )
        assertEquals("stat 失败必须与 cp 失败区分", 'S', stat.failedKind)
        val zero = ApkInstaller.parseInstallCopySegmentOutput(
            "SZ:0:0\n${ApkInstaller.INSTALL_COPY_DONE_MARKER}\n", 1
        )
        assertEquals("0 字节不得被当成有效大小", -1L, zero.sizes[0])
    }

    @Test
    fun `分片清理用本轮 token 通配且只发一条 su`() {
        val body = slice(apk, "private suspend fun installSplitApksInternal(", "internal fun draftNamesFor(")
        assertFalse(
            "不得再用 joinToString(\";\") 拼 64 条 rm（约 3200 字节，越过 su 上限）",
            body.contains("joinToString(\";\")")
        )
        assertTrue("清理必须按本轮 token 通配", body.contains("/_shso_split_\").append(token)"))
        assertEquals("finally 只能有一条 su", 1, body.countSubstring("RootService.runCommandSync(cleanup"))
    }

    @Test
    fun `单包安装压成一条 su`() {
        val start = apk.indexOf("// 统一用规范名")
        assertTrue("必须保留该锚点注释（既有测试用它作切片右界）", start > 0)
        val end = apk.indexOf("\n    /**", start)
        val body = apk.substring(start, if (end > start) end else apk.length)
        assertEquals("单包安装（暂存 + 安装 + 清理）只能有一条 su", 1, body.countSubstring("RootService.runCommandSync"))
        assertTrue("暂存失败必须能单独识别", body.contains("SHSO_STAGE_FAIL"))
        assertTrue("清理必须在脚本末尾（脚本被杀 = 不清理）", body.contains("val installScript = buildString {"))
        assertFalse("UUID 临时名不可能有残留，预清理是纯浪费的一条 su", body.contains("val cleanCmd"))
    }

    // ── #8 复制到另一列 ──────────────────────────────────────────────

    @Test
    fun `复制到另一列一条 su 且三种冲突策略都在命令里`() {
        fun script(policy: MoveDestinationConflict) = RootFileManager.buildCopyToDirectoryCommand(
            sourcePath = "/sdcard/Download/a.txt",
            destinationDirectory = "/data/adb/shso",
            destinationPath = "/data/adb/shso/a.txt",
            renamedPath = "/data/adb/shso/a_new.txt",
            onConflict = policy
        )

        val overwrite = script(MoveDestinationConflict.OVERWRITE)
        val bytes = overwrite.toByteArray(Charsets.UTF_8).size
        assertTrue("单条命令 $bytes 字节超出预算", bytes <= RootFileManager.COPY_DIR_MAX_BYTES)
        assertTrue("目录目标才允许先删", overwrite.contains("if [ -d \"\$f\" ]"))
        assertTrue("删目录失败必须停下", overwrite.contains("else rc=6; fi"))
        assertEquals("同名文件不得预删", 1, overwrite.countSubstring("rm -rf"))
        assertTrue("目标必须是目录", overwrite.contains("[ ! -d \"\$2\" ]"))
        assertTrue("回捞", overwrite.contains("[ ! -e \"\$f\" ]"))
        assertTrue("落点必须回传", overwrite.contains("echo \"FP:\$f\""))
        assertTrue("源类型只判一次", overwrite.countSubstring("if [ -d \"\$1\" ]") == 1)
        assertTrue("完成标记必须是最后一句", overwrite.trimEnd().endsWith(RootFileManager.COPY_DIR_DONE_MARKER))

        assertTrue("跳过策略", script(MoveDestinationConflict.SKIP).contains("rc=4"))
        val renamed = script(MoveDestinationConflict.RENAME)
        assertTrue("改名策略要判 _new 是否也占用", renamed.contains("then rc=5; else f=\$4; fi"))
        assertFalse("改名策略不得删除任何目标", renamed.contains("rm -rf"))
    }

    @Test
    fun `复制到另一列缺标记不得判成功且要识别覆盖目录`() {
        assertFalse(RootFileManager.parseCopyToDirectoryOutput("RC:0\nFP:/x\n").done)
        val parsed = RootFileManager.parseCopyToDirectoryOutput(
            "OV:D\nRC:0\nFP:/x\n${RootFileManager.COPY_DIR_DONE_MARKER}\n"
        )
        assertTrue(parsed.done)
        assertTrue("覆盖同名目录必须被识别（历史要回收）", parsed.removedDirectory)
        assertEquals(0, parsed.code ?: -1)
        assertEquals("/x", parsed.path ?: "")
    }

    @Test
    fun `复制到另一列保护路径只发一条 su`() {
        val body = slice(rfm, "suspend fun copyToDirectory(", "suspend fun moveDestinationCollides(")
        assertEquals("保护路径只应发一次 su", 1, body.countSubstring("RootService.runCommandSync"))
        assertTrue("本地快路径必须先试", body.contains("copyRecursively"))
        assertTrue("本地删掉同名目录后要回收历史", body.contains("EditHistoryManager.forget(destinationPath)"))
        assertTrue("删目录后必须让 shso 标记失效", body.contains("invalidateShsoDirIfAffected(destinationPath)"))
        assertFalse("不得再把目标类型探测交给独立 su", body.contains("fun rootType("))
        assertTrue("目标必须验为目录", body.contains("\"目标路径不是文件夹或不可访问\""))
    }

    /** 统计子串出现次数（Kotlin 没有内置）。 */
    private fun String.countSubstring(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}