// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「把非普通文件当成空文件编辑并保存」的回归防线。
 *
 * 缺陷链条：procfs / sysfs 节点与 FIFO 的 `stat %s` 恒为 0 但**有内容**。
 * `loadAll` 见 `total <= 0` 即返回空文本，而 `LoadResult.isComplete` 写作
 * `totalBytes <= 0L || loadedBytes >= totalBytes`，于是 0 被判为「读完了」。
 * 编辑器把这当作「完整的空文件」，`dirty = false`，用户看不出任何异常；
 * 一次无关编辑后 `writeTextFile` 用这份空内容整文件覆盖 ——
 * 对 root 可写的节点就是真实数据销毁，且不可撤销。
 */
class ChunkedFileReaderSpecialFileTest {

    private val source = File("src/main/java/com/mixradio/droid/data/ChunkedFileReader.kt")

    @Test fun `大小探测失败必须与零字节区分`() {
        // 旧实现两条路径都返回 0，调用方无法分辨「空文件」与「探测失败」。
        val text = source.readText()
        val fn = text.indexOf("fun fileSize(")
        assertTrue("应能找到 fileSize", fn > 0)
        val body = text.substring(fn, fn + 1200)

        assertTrue(
            "本地通道必须用 exists() 区分不存在与取不到长度",
            body.contains("f.exists()")
        )
        assertTrue(
            "探测失败必须返回 -1",
            body.contains("-1L")
        )
        assertTrue(
            "stat 输出不可解析时不得回落成 0",
            body.contains("out.trim().toLongOrNull()")
        )
    }

    @Test fun `零字节必须实读复核而非只看类型`() {
        // 这是本缺陷的核心拦截点。
        //
        // 真机实测推翻了一个看似可行的方案：`test -f` 对 `/proc/cpuinfo` 返回**真**
        // （procfs 项的类型就是普通文件），所以「零字节时用 isRegularFile 复核」拦不住它。
        // 唯一可靠的判据是「读得出字节」：
        //   stat -L -c %s /proc/cpuinfo  -> 0，但 dd 实读得到内容
        //   stat -L -c %s <真空文件>     -> 0，dd 实读 0 字节
        // 三者的 stat %s 完全相同。
        val text = source.readText()
        val fn = text.indexOf("if (total == 0L) {")
        assertTrue("必须显式处理 total == 0", fn > 0)
        val body = text.substring(fn, fn + 1200)

        assertTrue(
            "零字节时必须实读探测",
            body.contains("probeReadableBytes(filePath)")
        )
        assertTrue(
            "实读到内容必须标记 readFailed",
            body.contains("readFailed = true")
        )
        assertTrue(
            "实读失败（-1）也不得当成空文件",
            body.contains("readable < 0")
        )
        assertTrue(
            "只有实读确实为 0 才是真空文件",
            body.contains("return LoadResult(\"\", Charsets.UTF_8, false, 0L, 0, 0L)")
        )
    }

    @Test fun `实读探测必须以读不出为失败而非零`() {
        // 探测函数自身不能把失败返回 0 —— 那正是缺陷源头。
        val text = source.readText()
        val start = text.indexOf("fun probeReadableBytes(")
        assertTrue("应能找到 probeReadableBytes", start > 0)
        // 按下一个声明截断，避免固定窗口随实现增长而失效
        val end = text.indexOf("fun fileSize(", start)
        val body = text.substring(start, if (end > 0) end else text.length)

        assertTrue(
            "必须用 dd|wc -c 实读",
            body.contains("wc -c")
        )
        assertTrue(
            "读失败必须返回 -1",
            body.contains("-1")
        )
    }

    @Test fun `必须留下真机实测依据避免改回类型判定`() {
        // 记录判据来源：`test -f` 对 `/proc/cpuinfo` 返回**真**，所以「零字节时
        // 查文件类型」这条看似可行的方案已被真机推翻，只有实读可用。
        // 少了这条注释，后人很容易"优化"回 isRegularFile 而重新引入缺陷。
        val text = source.readText()
        val fn = text.indexOf("if (total == 0L) {")
        assertTrue("应能找到零字节分支", fn > 0)
        val body = text.substring(fn, fn + 900)

        assertTrue(
            "必须说明 test -f 拦不住 procfs",
            body.contains("test -f")
        )
        assertTrue(
            "必须说明实读是唯一可靠判据",
            body.contains("读得出字节")
        )
        assertTrue(
            "不得残留基于类型的复核",
            !body.contains("isRegularFile(")
        )
    }

    @Test fun `非普通文件必须被标记为读取失败`() {
        // 判据：只有「真的是普通空文件」才允许 isComplete 为 true。
        val text = source.readText()
        val fn = text.indexOf("val isComplete: Boolean")
        assertTrue("应能找到 isComplete", fn > 0)
        val body = text.substring(fn, fn + 400)

        assertTrue(
            "readFailed 时一律不得判为完整",
            body.contains("!readFailed")
        )
        assertFalse(
            "不得保留 totalBytes <= 0 即视为完整的旧写法",
            body.contains("totalBytes <= 0L ||")
        )
    }

    @Test fun `编辑器必须区别两类失败并阻止保存`() {
        // 文案要能区分「内容残缺」与「目标不是普通文件」，
        // 且两条都必须走 loadError 通道阻止保存。
        val editor = File("src/main/java/com/mixradio/droid/ui/components/TextEditorDialog.kt").readText()
        val fn = editor.indexOf("if (!load.isComplete) {")
        assertTrue("应能找到 isComplete 判定", fn > 0)
        val branch = editor.substring(fn, fn + 1200)

        assertTrue(
            "必须按 readFailed 区分文案",
            branch.contains("load.readFailed")
        )
        assertTrue(
            "必须提示目标可能不是普通文件",
            branch.contains("不是普通文件")
        )
        assertTrue(
            "必须置 loadError 以阻止保存",
            branch.contains("loadError =")
        )
        assertTrue(
            "必须清脏标记",
            branch.contains("dirty = false")
        )
    }

    @Test fun `区间读取必须拒绝负偏移并夹住超长请求`() {
        // 负 offset 会让 readRangeRoot 的 copyOfRange 抛越界异常，
        // 而该调用在旧实现的 try 之外，会逃出 readRange 跳过本地兜底。
        // count > 2GB 时 toInt() 溢出为负 → NegativeArraySizeException。
        val text = source.readText()
        val fn = text.indexOf("fun readRange(")
        assertTrue("应能找到 readRange", fn > 0)
        val end = text.indexOf("private fun readRangeRoot(", fn)
        val body = text.substring(fn, if (end > 0) end else text.length)

        assertTrue("必须拒绝负 offset", body.contains("offset < 0L"))
        assertTrue(
            "count 必须夹到上限，避免 toInt() 溢出",
            body.contains("coerceAtMost(Int.MAX_VALUE.toLong())")
        )
        assertTrue(
            "不得再无保护地使用原始 count",
            !body.contains("ByteArray(count.toInt())")
        )
    }

    @Test fun `root 区间读取必须自行收口异常`() {
        // readRange 的 root 分支不能把异常抛给调用方：
        // 抛出会跳过紧随其后的本地兜底，把「root 通道失败」变成崩溃。
        val text = source.readText()
        val fn = text.indexOf("private fun readRangeRoot(")
        assertTrue("应能找到 readRangeRoot", fn > 0)
        val body = text.substring(fn, fn + 1200)

        assertTrue(
            "readRangeRoot 必须整体包在 try 内",
            body.contains("= try {")
        )
        assertTrue(
            "必须收口为返回空数组",
            body.contains("ByteArray(0)")
        )
    }

    @Test fun `源码注释不得含未转义的块注释终止符`() {
        // 真实踩坑：注释里写 `/proc/*`，其中的 `*/` 被当作块注释结束，
        // 导致其后所有代码被注释掉、文件解析失败。此类缺陷编译期才暴露，
        // 但一旦出现整个文件无法编译，故作为护栏固定下来。
        val offenders = ArrayList<String>()
        for (f in File("app/src/main/java/com/mixradio/droid").walkTopDown()) {
            if (!f.isFile || !f.name.endsWith(".kt")) continue
            var depth = 0
            for (line in f.readLines()) {
                depth += Regex("/\\*").findAll(line).count()
                depth -= Regex("\\*/").findAll(line).count()
            }
            if (depth != 0) offenders.add("${f.path} (depth=$depth)")
        }
        assertTrue("块注释必须成对闭合: $offenders", offenders.isEmpty())
    }
}
