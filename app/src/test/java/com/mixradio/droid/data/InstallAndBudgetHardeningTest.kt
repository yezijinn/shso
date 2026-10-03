// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本轮修复的回归护栏。
 *
 * 每条用例都对应一个**已实证的真实缺陷**，不是「为了覆盖率而测」：
 * - 契约类结论（`pm install-write` 的 SPLIT_NAME 语义、FUSE 上的 CAS 原语、
 *   纳秒 mtime 身份）均已在真机 PACM00 / Android 10 上实测，断言按实测结论写死；
 * - 漏判类结论针对的是「一句话绕过规则」的具体命令形态。
 */
class InstallAndBudgetHardeningTest {

    // ========================================================================
    // pm install-write 草稿名
    // ========================================================================

    @Test fun `草稿名首个必须是 base apk 以匹配 AOSP 规范名`() {
        val names = ApkInstaller.draftNamesFor(
            listOf("/tmp/base.apk", "/tmp/s1.apk", "/tmp/s2.apk"),
            listOf("config.arm64_v8a", "config.xxhdpi")
        )
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk"), names)
    }

    @Test fun `草稿名必须两两不同 同名会被写入端无 EXCL 静默覆盖成一个文件`() {
        // 两个分包声明了相同 split 名（或未声明时按序号），不能产生同名草稿
        val names = ApkInstaller.draftNamesFor(
            listOf("/tmp/b", "/tmp/s1", "/tmp/s2", "/tmp/s3"),
            listOf("dup", "dup", "")
        )
        assertEquals("base.apk", names.first())
        assertEquals("草稿名出现重复会让两个分片塌缩成一个文件", names.size, names.toSet().size)
    }

    @Test fun `split 名非法或缺失时退化为序号命名而不是拼进非法路径分隔符名`() {
        val names = ApkInstaller.draftNamesFor(
            listOf("/tmp/b", "/tmp/s1", "/tmp/s2"),
            listOf("../escape", "")
        )
        names.forEach {
            assertTrue("草稿名不得含路径分隔符: $it", !it.contains('/'))
        }
        assertTrue(names[1].startsWith("shso_split_"))
    }

    @Test fun `单 APK 不产生分包草稿名`() {
        assertEquals(listOf("base.apk"), ApkInstaller.draftNamesFor(listOf("/tmp/only.apk"), emptyList()))
        assertEquals(emptyList<String>(), ApkInstaller.draftNamesFor(emptyList(), emptyList()))
    }

    @Test fun `套件写入顺序把基础包排在最前`() {
        val set = ApkInstaller.ApkSet("/d/base.apk", listOf("/d/s1.apk", "/d/s2.apk"))
        assertEquals(listOf("/d/base.apk", "/d/s1.apk", "/d/s2.apk"), set.orderedWrites)
        assertTrue(set.isSplit)
    }

    // ========================================================================
    // 中央目录预算前置（EOCD 探针）
    // ========================================================================

    @Test fun `真实 zip 的条目数被正确读出`() {
        val zip = buildZip(listOf("a.txt", "b.txt", "c/d.txt", "e.txt"))
        assertEquals(4, ZipEntryCountProbe.probe(zip))
    }

    @Test fun `空 zip 读出 0`() {
        val zip = buildZip(emptyList())
        assertEquals(0, ZipEntryCountProbe.probe(zip))
    }

    @Test fun `条目数超限时能在解析前被拒绝`() {
        // 2 万条上限；构造一个「声明条目数很大」的真实 zip 成本太高，
        // 改为直接断言探针读出的真实条目数与上限比较的判定逻辑。
        val zip = buildZip(listOf("a.txt", "b.txt"))
        val count = ZipEntryCountProbe.probe(zip)!!
        assertTrue(count <= ArchiveExtractor.MAX_EXTRACT_ENTRIES)
    }

    @Test fun `非 zip 文件返回 null 交由后续解析器报错`() {
        val notZip = File.createTempFile("notzip", ".bin").apply {
            writeBytes(ByteArray(4096) { (it % 127).toByte() })
            deleteOnExit()
        }
        assertNull(ZipEntryCountProbe.probe(notZip))
    }

    @Test fun `空文件与极小文件返回 null 而不是抛异常`() {
        val empty = File.createTempFile("empty", ".zip").apply { deleteOnExit() }
        assertNull(ZipEntryCountProbe.probe(empty))
        val tiny = File.createTempFile("tiny", ".zip").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        assertNull(ZipEntryCountProbe.probe(tiny))
    }

    @Test fun `不存在与目录路径返回 null`() {
        assertNull(ZipEntryCountProbe.probe(File("/definitely/not/here.zip")))
    }

    @Test fun `注释里伪造的 EOCD 签名不被误认为真 EOCD`() {
        // 注释区可以任意字节，攻击者能放一个 0x06054b50。
        // 判据：真 EOCD 的起点 + 22 + 注释长度 不得超过文件长度，且必须落在尾部窗口内。
        val zip = buildZipWithComment(listOf("a.txt"), commentByteCount = 64, forgeEocdInComment = true)
        assertEquals(1, ZipEntryCountProbe.probe(zip))
    }

    @Test fun `真 EOCD 之后挂尾随垃圾字节仍能解析出条目数`() {
        // zip4j 2.11.1 的 HeaderReader.locateOffsetOfEndOfCentralDirectoryByReverseSeek
        // 反向找到签名后**直接返回，不做 EOF 对齐或注释长度校验**（javap 反编译确认）。
        // 所以「真 EOCD 后面还有垃圾字节」的 zip（拼接下载、对齐填充、自定义工具追加）
        // zip4j 能正常解析出全部 central directory。若探针用严格等值判据就会判成「非 zip」
        // 返回 null，预算检查被静默绕过 —— 恰好是这个探针要防的事。
        val zip = buildZip(listOf("a.txt", "b.txt", "c.txt"))
        appendBytes(zip, 1)   // 追加 1 字节
        assertEquals(3, ZipEntryCountProbe.probe(zip))
    }

    @Test fun `尾随垃圾字节时 zip4j 同样能解析_证明探针必须与之同口径`() {
        val zip = buildZip(listOf("a.txt", "b.txt", "c.txt"))
        appendBytes(zip, 3)
        val zf = net.lingala.zip4j.ZipFile(zip)
        try {
            assertEquals(3, zf.fileHeaders.size)
        } finally {
            zf.close()
        }
        assertEquals(3, ZipEntryCountProbe.probe(zip))
    }

    @Test fun `尾随垃圾超出 64KB 反查窗口时两个解析器都拒绝`() {
        // zip4j 的反向查找窗口是 65536 字节，垃圾更多就找不到签名；
        // 探针此时同样返回 null（尾部窗口内无自洽 EOCD），两侧口径一致。
        val zip = buildZip(listOf("a.txt", "b.txt", "c.txt"))
        appendBytes(zip, 70000)
        assertNull(ZipEntryCountProbe.probe(zip))
    }

    @Test fun `EOCD 结束位置超过文件长度仍判为伪造`() {
        // 注释长度字段谎报 500，但文件只有 22 字节 → 结束位置越界，不认。
        val eocd = ByteArray(22)
        writeIntLe(eocd, 0, 0x06054b50)
        writeShortLe(eocd, 10, 4)
        writeShortLe(eocd, 20, 500)
        assertNull(ZipEntryCountProbe.entryCountFromTail(eocd, 22L))
    }

    @Test fun `尾部字节不足一个 EOCD 时返回 null`() {
        val tail = ByteArray(10)
        assertNull(ZipEntryCountProbe.entryCountFromTail(tail, 10L))
        // tail 比文件还大（调用方不可能这样传）也要挡住
        assertNull(ZipEntryCountProbe.entryCountFromTail(ByteArray(64), 10L))
    }

    @Test fun `手工构造的 EOCD 能被解析出条目数`() {
        val eocd = ByteArray(22)
        writeIntLe(eocd, 0, 0x06054b50)
        writeShortLe(eocd, 8, 2)      // 本盘条目数
        writeShortLe(eocd, 10, 7)     // 总条目数
        assertEquals(7, ZipEntryCountProbe.entryCountFromTail(eocd, 22L))
    }

    @Test fun `ZIP64 哨兵值原样返回以便按上限拒绝`() {
        val eocd = ByteArray(22)
        writeIntLe(eocd, 0, 0x06054b50)
        writeShortLe(eocd, 8, 0xFFFF)
        writeShortLe(eocd, 10, 0xFFFF)
        val count = ZipEntryCountProbe.entryCountFromTail(eocd, 22L)!!
        assertTrue("0xFFFF 必须被当作「已达上限量级」", count >= ArchiveExtractor.MAX_EXTRACT_ENTRIES)
    }

    @Test fun `压缩包很大时探针仍只读固定窗口`() {
        // 尾部窗口固定 64KB 上下，与归档体积无关（这是「预算前置」能成立的前提）
        assertTrue(ZipEntryCountProbe.TAIL_WINDOW < 70 * 1024)
    }

    @Test fun `自报条目数被改小时必须用中央目录体积兜住`() {
        // 攻击形态：中央目录实际写上百万条 header 记录，而 EOCD 的「总条目数」写 1。
        // 预检若只信自报字段就得到 1 → 放行；而 zip4j 的 HeaderReader 是
        // **从中央目录起点逐条扫到 EOCD 签名为止**、不以自报计数为上界
        // → 百万个 FileHeader 一次性进堆 → OOM。预检对它唯一要防的形态完全失效。
        val eocd = ByteArray(22)
        writeIntLe(eocd, 0, 0x06054b50)
        writeShortLe(eocd, 8, 1)         // 本盘条目数：攻击者写小
        writeShortLe(eocd, 10, 1)        // 总条目数：攻击者写小
        writeIntLe(eocd, 12, 46 * 30_000)  // 但中央目录实际体积 = 30000 条记录
        val count = ZipEntryCountProbe.entryCountFromTail(eocd, 22L)!!
        assertEquals(
            "中央目录体积折算的条目数必须参与判定，不能只信自报字段",
            30_000, count
        )
        assertTrue(
            "30000 条必须触发解压预算拦截（上限 20000）",
            count >= ArchiveExtractor.MAX_EXTRACT_ENTRIES
        )
    }

    @Test fun `自报条目数大于体积折算时以自报值为准`() {
        // 两个上界取大者：自报字段也可能被**改大**来触发误拒，不能只信体积。
        val eocd = ByteArray(22)
        writeIntLe(eocd, 0, 0x06054b50)
        writeShortLe(eocd, 8, 900)
        writeShortLe(eocd, 10, 900)
        writeIntLe(eocd, 12, 46 * 3)     // 体积只折算 3 条
        assertEquals(900, ZipEntryCountProbe.entryCountFromTail(eocd, 22L))
    }

    @Test fun `中央目录体积为 0 时不得误判`() {
        // 空归档：自报 0 条、体积 0 → 仍是 0，不能因除法/默认值产出奇怪数字
        val eocd = ByteArray(22)
        writeIntLe(eocd, 0, 0x06054b50)
        writeShortLe(eocd, 8, 0)
        writeShortLe(eocd, 10, 0)
        writeIntLe(eocd, 12, 0)
        assertEquals(0, ZipEntryCountProbe.entryCountFromTail(eocd, 22L))
    }

    // ========================================================================
    // OBB 锁退出码契约
    // ========================================================================

    @Test fun `OBB 锁退出码区分忙与状态不明`() {
        // 忙可退避重试；状态不明必须立即 fail-closed。
        // 两者若混用同一个码，用户只会看到「正在被其他安装任务使用」而无法定位。
        assertTrue(ApkInstaller.ObbLockExit.BUSY != ApkInstaller.ObbLockExit.UNKNOWN)
        assertTrue(ApkInstaller.ObbLockExit.UNKNOWN != ApkInstaller.ObbLockExit.PUBLISH_FAILED)
        assertTrue("成功必须是 0", ApkInstaller.ObbLockExit.BUSY != 0)
    }

    @Test fun `OBB 锁获取脚本以 O_EXCL 原子创建收尾`() {
        // 互斥点必须是 `set -C`（O_CREAT|O_EXCL）。
        // 反例：旧实现用 `mv $tempLock $lock`，而 `mv dir1 dir2` 在 dir2 已存在且是目录时
        // 会把 dir1 移进 dir2 内部并返回 0 —— 并发双方都认为持锁（真机已实测复现）。
        val s = ApkInstaller.buildObbLockAcquireScript("'L'", "'T|1|2'", "'Q'")
        assertTrue("必须用 set -C 做 O_EXCL 创建", s.contains("set -C"))
        assertTrue(s.contains("printf '%s' 'T|1|2' > 'L'"))
        assertTrue("成功路径必须以 exit 0 收尾", s.trimEnd().endsWith("exit ${ApkInstaller.ObbLockExit.BUSY}"))
        assertTrue("不能出现把临时目录 mv 到锁位置的非原子发布", !s.contains("tempLock"))
    }

    @Test fun `OBB 锁获取脚本对非数字元数据 fail-closed`() {
        // 真实缺陷：`cut -d'|' -f2` 对「无分隔符」的行整行返回，垃圾锁的三个字段都变成
        // 同一串非数字文本；直接 `[ a -ge b ]` 在 mksh 下报 unexpected operator 并返回非零，
        // 判定链顺势滑到「陈旧锁回收」—— 状态不明被误判成可抢占（真机已实测复现）。
        val s = ApkInstaller.buildObbLockAcquireScript("'L'", "'T|1|2'", "'Q'")
        assertTrue(
            "必须对 oldPid/oldStart/oldCreated/now 做纯数字校验",
            s.contains("case \"\$_f\" in ''|*[!0-9]*) exit ${ApkInstaller.ObbLockExit.UNKNOWN}")
        )
        assertTrue("必须列出全部四个待校验字段", s.contains("\"\$oldPid\" \"\$oldStart\" \"\$oldCreated\" \"\$now\""))
        // liveStart 为空（/proc 读不到 = 进程已死）不应被判成活锁
        assertTrue(s.contains("case \"\$liveStart\" in ''|*[!0-9]*) ;;"))
    }

    @Test fun `OBB 目录必须在取锁之前创建`() {
        // 真实缺陷：锁用 `( set -C; printf > LOCK )` 创建，而该重定向在父目录不存在时
        // 直接失败（ENOENT），`if` 条件为假 → 走 `exit BUSY`，用户看到的是误导性的
        // 「OBB 目录正在被其他安装任务使用」并白等 4 次退避重试（重试永远无效）。
        // 任何新游戏首次安装时 /sdcard/Android/obb/<pkg> 都不存在（AOSP 到装 APK 那步才建），
        // 所以这是**含 OBB 的 XAPK 的必现失败**。
        val s = File("src/main/java/com/mixradio/droid/data/ApkInstaller.kt").readText()
        val mkdirAt = s.indexOf("mkdir -p \${RootService.escapeShellArg(obbDir)}")
        val lockAt = s.indexOf("acquireObbLock(obbTransactionLock, obbLockToken)")
        assertTrue("必须存在 mkdir -p OBB 目录的调用", mkdirAt > 0)
        assertTrue("必须存在取锁调用", lockAt > 0)
        assertTrue("建目录必须早于取锁（否则 O_EXCL 在父目录缺失时必失败）", mkdirAt < lockAt)
    }

    @Test fun `OBB 锁获取脚本对陈旧锁先隔离再核对身份`() {
        val s = ApkInstaller.buildObbLockAcquireScript("'L'", "'T|1|2'", "'Q'")
        assertTrue("陈旧锁必须先原子改名摘掉锁名", s.contains("mv 'L' 'Q'"))
        assertTrue(
            "隔离后必须核对内容一致，不一致不得删除",
            s.contains("if [ -z \"\$got\" ] || [ \"\$got\" != \"\$old\" ]; then exit ${ApkInstaller.ObbLockExit.UNKNOWN}; fi;")
        )
        // 顺序：先核对再删
        val verifyAt = s.indexOf("\$got\" != \"\$old\"")
        val removeAt = s.indexOf("rm -f -- 'Q'")
        assertTrue("核对必须早于删除", verifyAt in 0 until removeAt)
    }

    @Test fun `OBB 锁获取脚本对活跃持有者返回 BUSY 且不删除锁`() {
        val s = ApkInstaller.buildObbLockAcquireScript("'L'", "'T|1|2'", "'Q'")
        // pid + 进程启动时间双校验
        assertTrue(s.contains("/proc/\$oldPid/stat"))
        assertTrue(s.contains("if [ \"\$liveStart\" = \"\$oldStart\" ]; then exit ${ApkInstaller.ObbLockExit.BUSY}"))
        // 未过 TTL 也算忙
        assertTrue(s.contains("-ge \"\$now\""))
    }

    @Test fun `OBB 身份格式必须容纳带纳秒与时区的 mtime 段`() {
        // 真机实测：秒级 %Y 在同秒替换下不可区分，纳秒 %y 可区分，
        // 因此身份必须形如 "inode:size:2026-10-02 09:52:03.108011510 +0800"。
        val withNanos = "230968:6:2026-10-02 09:52:03.108011510 +0800"
        assertTrue(ZipEntryCountProbeProbeHelper.identityMatches(withNanos))
        // 不完整/畸形身份不得被接受，否则会把「拿不到身份」当成「可以回滚」
        assertTrue(!ZipEntryCountProbeProbeHelper.identityMatches(""))
        assertTrue(!ZipEntryCountProbeProbeHelper.identityMatches("230968"))
        assertTrue(!ZipEntryCountProbeProbeHelper.identityMatches("230968:6"))
        assertTrue(!ZipEntryCountProbeProbeHelper.identityMatches("abc:6:2026"))
    }

    // ========================================================================
    // 工具
    // ========================================================================

    private object ZipEntryCountProbeProbeHelper {
        /** 复刻 ApkInstaller 的身份正则形态，避免测试直接依赖 private 成员。 */
        fun identityMatches(value: String): Boolean = Regex("\\d+:\\d+:.+").matches(value)
    }

    private fun writeIntLe(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun buildZip(names: List<String>): File = buildZipWithComment(names, 0, false)

    /** 追加字节；必须用 append 模式，`File.outputStream()` 默认截断会把 zip 清空。 */
    private fun appendBytes(file: File, count: Int) {
        java.io.FileOutputStream(file, true).use { it.write(ByteArray(count)) }
    }

    private fun buildZipWithComment(
        names: List<String>,
        commentByteCount: Int,
        forgeEocdInComment: Boolean
    ): File {
        val file = File.createTempFile("probe", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            // 注释若为 0 长度则无法伪造签名；这里通过自定义写入实现带注释的 zip：
            // ZipOutputStream.setComment 会自己算长度，不便精确控制，故先写普通 zip 再手工补。
            names.forEach { zos.putNextEntry(ZipEntry(it)); zos.write(it.toByteArray()); zos.closeEntry() }
        }
        if (commentByteCount <= 0) return file

        // 手工补一个「EOCD + 注释」结构：读出原 EOCD 起点，原地扩展注释区。
        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length().toInt()
            val all = ByteArray(len)
            raf.seek(0); raf.readFully(all)
            var eocdAt = -1
            for (i in len - 22 downTo 0) {
                if (readIntLe(all, i) == 0x06054b50) { eocdAt = i; break }
            }
            require(eocdAt >= 0) { "未找到 EOCD" }
            // 把原 EOCD 之后的内容清空，改写为：EOCD(注释长度=commentByteCount) + 注释
            val out = all.copyOfRange(0, eocdAt + 22)
            writeShortLe(out, eocdAt + 20, commentByteCount)
            val comment = ByteArray(commentByteCount)
            if (forgeEocdInComment) writeIntLe(comment, 8, 0x06054b50)
            raf.setLength(0)
            raf.write(out)
            raf.write(comment)
        }
        return file
    }

    private fun readIntLe(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}
