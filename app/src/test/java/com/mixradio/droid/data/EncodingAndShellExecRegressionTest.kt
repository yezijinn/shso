// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data

import com.mixradio.droid.data.security.CommandParser
import com.mixradio.droid.data.security.CommandSource
import com.mixradio.droid.data.security.PolicyEngine
import com.mixradio.droid.data.security.ScriptAuditor
import com.mixradio.droid.data.security.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 第十轮深挖的回归。
 *
 * 三条主线：
 * ① 编码探测：非严格 UTF-8 时**无条件**落 GB18030 → 单个非法字节即让整个文件被重解释，
 *    保存后原始字节不可逆损坏。
 * ② 终端 `sh <文件>` 此前不触发任何规则，可绕开脚本内容审查；
 *    `su`/`nice`/`setsid`/`time` 不在 wrapper 名单、`su -c` 内层不展开。
 * ③ 脚本审计把 U+FFFD 当二进制特征 → GBK 中文脚本被误判为加密载荷；
 *    base64 单行判据只看第一行，而脚本首行是 shebang → 该规则对脚本永久失效。
 */
class EncodingAndShellExecRegressionTest {

    private fun gb(name: String): Charset = Charset.forName(name)

    // ---------- ① 编码探测 ----------

    @Test fun `非法字节夹在 UTF-8 中间时不得整体重解释为 GB18030`() {
        // 「基本是 ASCII + 中间夹 1 个非法 UTF-8 字节」：日志里混进一行 GB18030 中文、
        // 或日志被截断在多字节字符中间，都会形成这种文件。
        val good = "hello world\n".toByteArray(Charsets.UTF_8)
        val bad = ByteArray(good.size + 1)
        good.copyInto(bad)
        bad[good.size] = 0xFF.toByte()   // 非法起始字节

        val d = CharsetDetector.detect(bad)
        assertNotEquals(
            "含非法字节的 UTF-8 不得被判成 GB18030（否则保存即不可逆改写整个文件）",
            gb("GB18030"), d.charset
        )
    }

    @Test fun `真正的 GB18030 中文仍须被正确识别`() {
        val bytes = "中文测试内容".toByteArray(gb("GB18030"))
        val d = CharsetDetector.detect(bytes)
        assertEquals("真正的 GB18030 不得被误伤", gb("GB18030"), d.charset)
        assertEquals("解码文本必须正确", "中文测试内容", d.text)
    }

    @Test fun `纯 UTF-8 中文不得被误判`() {
        val bytes = "中文测试内容".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, CharsetDetector.detect(bytes).charset)
    }

    @Test fun `无回退路径时按 ISO-8859-1 兜底且可无损往返`() {
        // ISO-8859-1 对任意字节序列一一映射，往返无损 —— 这是「不破坏用户数据」的最后保证
        val bad = byteArrayOf(0x41, 0xFF.toByte(), 0x42, 0x80.toByte())
        val d = CharsetDetector.detect(bad)
        val roundTrip = d.text.toByteArray(d.charset)
        assertTrue(
            "ISO-8859-1 路径必须字节级无损往返",
            roundTrip.contentEquals(bad)
        )
    }

    // ---------- ② 终端 sh <文件> 与 su 展开 ----------

    private fun ids(cmd: String, source: CommandSource = CommandSource.USER_TERMINAL): Set<String> {
        val v = PolicyEngine.evaluate(cmd, source)
        val findings = when (v) {
            is Verdict.Block -> v.findings
            is Verdict.Confirm -> v.findings
            else -> emptySet()
        }
        return findings.map { it.ruleId }.toSet()
    }

    @Test fun `终端执行任意后缀文件必须被判定`() {
        // 脚本内容审查只在「执行文件」链路且 `isSh` 为真时调用。
        // 命名成 .dat 再 `sh /sdcard/p.dat` 就完全绕过 —— 同一份内容走文件页会被拦。
        val found = ids("sh /sdcard/payload.dat")
        assertTrue("sh <文件> 必须上报，实际=$found", found.contains("SHELL_FILE_EXECUTION"))
    }

    @Test fun `bash 与其它 shell 同样适用`() {
        for (sh in listOf("sh", "bash", "ash", "dash", "mksh")) {
            val found = ids("$sh /data/local/tmp/x.bin")
            assertTrue("$sh <文件> 未被判定：$found", found.contains("SHELL_FILE_EXECUTION"))
        }
    }

    @Test fun `交互式读 stdin 的用法不得误报`() {
        // `sh` / `sh -x` 没有文件操作数，是正常的交互式 shell
        assertFalse("裸 sh 不应命中", ids("sh").contains("SHELL_FILE_EXECUTION"))
        assertFalse("sh -x 不应命中", ids("sh -x").contains("SHELL_FILE_EXECUTION"))
    }

    @Test fun `su -c 必须展开内层命令`() {
        // su 会重建环境（PATH 重置），守卫 PATH 兜底不成立 → 静态层是唯一防线
        val found = ids("su -c \"rm -rf /system\"")
        assertTrue(
            "su -c 的内层命令必须被评估，实际=$found",
            found.any { it.startsWith("UNRESOLVED") || it == "PROTECTED_PATH" || it == "DESTRUCTIVE" || it.contains("SYSTEM") }
        )
    }

    @Test fun `nice 与 setsid 前缀必须被剥离`() {
        // 此前不在 WRAPPER_PREFIXES → program 是它们本身 → when 无分支 → 零规则
        val atoms = CommandParser.parse("nice rm -rf /system").atoms
        assertTrue("nice 必须被剥掉，解析结果=${atoms.map { it.program }}", atoms.any { it.program == "rm" })
        val atoms2 = CommandParser.parse("setsid sh -c 'rm -rf /system'").atoms
        assertTrue(
            "setsid 必须被剥掉，解析结果=${atoms2.map { it.program }}",
            atoms2.any { it.program == "rm" }
        )
    }

    // ---------- ③ 脚本审计的加密误判与 base64 盲区 ----------

    @Test fun `GBK 中文脚本不得被判为加密载荷`() {
        // 按 UTF-8 读 GBK 脚本会产生大量 U+FFFD；此前与 NUL 同权计入 → CRITICAL 混淆载荷
        val gbkBytes = "#!/system/bin/sh\n# 中文注释：这是一个测试脚本\nrm -rf /tmp/x\n"
            .toByteArray(gb("GB18030"))
        val asUtf8 = String(gbkBytes, Charsets.UTF_8)
        assertFalse(
            "GBK 脚本不得被判为加密载荷",
            ScriptAuditor.looksEncrypted(asUtf8)
        )
    }

    @Test fun `真二进制（含 NUL）仍须被判为加密载荷`() {
        val bin = String(CharArray(200) { '\u0000' })
        assertTrue("含 NUL 的二进制必须被判为加密载荷", ScriptAuditor.looksEncrypted(bin))
    }

    @Test fun `shebang 之后的 base64 载荷行必须被识别`() {
        // 此前只看第一行，而脚本第一行永远是 shebang（长度 <2048）→ 该规则对脚本永久失效
        val payload = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=" + "A".repeat(2100)
        val script = "#!/system/bin/sh\n$payload\necho done\n"
        assertTrue(
            "第 2 行的 base64 载荷必须被识别（fail-open 方向）",
            ScriptAuditor.looksEncrypted(script)
        )
    }

    @Test fun `普通脚本不得误判为加密载荷`() {
        val script = """
            #!/system/bin/sh
            # 这是一个普通的脚本
            for f in /data/adb/*; do
                echo "${'$'}f"
            done
        """.trimIndent()
        assertFalse("普通脚本不得误判", ScriptAuditor.looksEncrypted(script))
    }
}
