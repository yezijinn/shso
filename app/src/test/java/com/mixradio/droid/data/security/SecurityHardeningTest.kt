// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全加固回归（第 7 项）：wrapper 绕过、重定向写入、格机原语、未解析变量、加密/混淆载荷。
 * 全部为纯逻辑（无 Context/ROOT），刻意不做任何真机依赖。
 */
class SecurityHardeningTest {

    private fun v(cmd: String, source: CommandSource = CommandSource.USER_TERMINAL) =
        PolicyEngine.evaluate(cmd, source)

    private fun assertBlocked(cmd: String, ruleId: String? = null, source: CommandSource = CommandSource.USER_TERMINAL) {
        val r = v(cmd, source)
        assertTrue("期望 Block，实际 $r（cmd=$cmd）", r is Verdict.Block)
        if (ruleId != null) {
            assertTrue(
                "期望命中规则 $ruleId，实际 ${(r as Verdict.Block).findings.map { it.ruleId }}",
                r.findings.any { it.ruleId == ruleId }
            )
        }
    }

    // ========================================================================
    // 1) wrapper 选项绕过（旧实现把 `5` / `root` 当程序名，rm 规则完全不评估）
    // ========================================================================

    @Test fun `timeout 前缀不再绕过高危命令`() {
        assertBlocked("timeout 5 rm -rf /system", "RM_SYSTEM")
        assertBlocked("timeout -s KILL 5 rm -rf /system", "RM_SYSTEM")
    }

    @Test fun `sudo 带值选项不再绕过`() {
        assertBlocked("sudo -u root rm -rf /system", "RM_SYSTEM")
    }

    @Test fun `stdbuf 与 env 选项不再绕过`() {
        assertBlocked("stdbuf -o0 rm -rf /system", "RM_SYSTEM")
        assertBlocked("env -i rm -rf /system", "RM_SYSTEM")
    }

    // ========================================================================
    // 2) 重定向写入（旧实现完全漏检：不经 dd 的块设备/系统写入）
    // ========================================================================

    @Test fun `重定向写块设备被硬拦`() {
        assertBlocked("echo x > /dev/block/by-name/boot", "REDIRECT_SYSTEM")
        assertBlocked("cat boot.img > /system/build.prop", "REDIRECT_SYSTEM")
    }

    @Test fun `重定向写 sysrq-trigger 被硬拦`() {
        assertBlocked("echo c > /proc/sysrq-trigger", "REDIRECT_SYSRQ")
    }

    @Test fun `重定向经 dotdot 穿越写系统路径仍被硬拦`() {
        // /data/../system/build.prop 归一化后是 /system/build.prop
        assertBlocked("echo x >> /data/../system/build.prop", "REDIRECT_SYSTEM")
    }

    @Test fun `重定向写数据分区为需确认`() {
        val r = v("echo x >> /metadata/foo")
        assertTrue("期望 Confirm，实际 $r", r is Verdict.Confirm)
        assertTrue((r as Verdict.Confirm).findings.any { it.ruleId == "REDIRECT_DATA" })
    }

    @Test fun `常规重定向不误报`() {
        assertEquals(Verdict.Allow, v("ls -la /sdcard > /dev/null"))
        assertEquals(Verdict.Allow, v("echo hi > out.txt"))
        assertEquals(Verdict.Allow, v("cat /sdcard/a.txt 2>&1"))
    }

    // ========================================================================
    // 3) 格机原语：分区表 / 刷机工具 / truncate / tee
    // ========================================================================

    @Test fun `分区表与刷机工具被硬拦`() {
        assertBlocked("parted /dev/block/mmcblk0", "BRICK_TOOL")
        assertBlocked("sgdisk --zap-all /dev/block/sda", "BRICK_TOOL")
        assertBlocked("flash_image boot /sdcard/boot.img", "BRICK_TOOL")
    }

    @Test fun `truncate 系统设备文件被硬拦`() {
        assertBlocked("truncate -s 0 /dev/block/sda", "TRUNCATE_SYSTEM")
        assertEquals(Verdict.Allow, v("truncate -s 0 /sdcard/x"))
    }

    @Test fun `tee 写设备文件被硬拦`() {
        assertBlocked("echo x | tee /dev/block/sda", "TEE_SYSTEM")
    }

    // ========================================================================
    // 4) 未解析变量：破坏性命令目标不可静态判定
    // ========================================================================

    @Test fun `未解析变量的 dd 目标按最高危`() {
        assertBlocked("dd if=/dev/zero of=\$TARGET", "UNRESOLVED_DESTRUCTIVE")
    }

    @Test fun `未解析变量的 rm 目标为需确认`() {
        val r = v("rm -rf \$TARGET")
        assertTrue("期望 Confirm，实际 $r", r is Verdict.Confirm)
        assertTrue((r as Verdict.Confirm).findings.any { it.ruleId == "UNRESOLVED_DESTRUCTIVE_CONFIRM" })
    }

    // ========================================================================
    // 5) 加密 / 混淆载荷
    // ========================================================================

    @Test fun `脚本中的解码管道被硬拦`() {
        assertBlocked("echo aGVsbG8= | base64 -d | sh", "ENCODED_PIPE_SHELL", CommandSource.SCRIPT_FILE)
    }

    @Test fun `终端中的解码管道可确认`() {
        val r = v("echo aGVsbG8= | base64 -d | sh")
        assertTrue("期望 Confirm，实际 $r", r is Verdict.Confirm)
        assertTrue((r as Verdict.Confirm).findings.any { it.ruleId == "ENCODED_PIPE_SHELL" })
    }

    @Test fun `脚本中的 eval 解码载荷被硬拦`() {
        assertBlocked("eval \"\$(base64 -d <<< aGVsbG8=)\"", "EVAL_DYNAMIC", CommandSource.SCRIPT_FILE)
    }

    @Test fun `脚本中的解释器解码载荷被硬拦`() {
        assertBlocked(
            "python -c \"import base64;exec(base64.b64decode('YQ=='))\"",
            "INTERPRETER_PAYLOAD", CommandSource.SCRIPT_FILE
        )
    }

    @Test fun `普通 eval 仅需确认`() {
        val r = v("eval echo hi", CommandSource.SCRIPT_FILE)
        assertTrue("期望 Confirm，实际 $r", r is Verdict.Confirm)
        assertTrue((r as Verdict.Confirm).findings.any { it.ruleId == "EVAL" })
    }

    // ========================================================================
    // 6) ScriptAuditor：加密内容识别 + 解析超限 fail-closed
    // ========================================================================

    @Test fun `超长 base64 单行被识别为混淆载荷`() {
        val payload = "QUJD".repeat(1500)      // 6000 字符、纯 base64 字符集、无空格
        assertTrue(ScriptAuditor.looksEncrypted(payload))
        val report = ScriptAuditor.audit(payload)
        assertTrue(report.findings.any { it.ruleId == "OBFUSCATED_PAYLOAD" && it.level == RiskLevel.CRITICAL })
    }

    @Test fun `含 NUL 的二进制内容被识别为不明文`() {
        val bin = "\u0000\u0001\u0002".repeat(2000)
        assertTrue(ScriptAuditor.looksEncrypted(bin))
    }

    @Test fun `普通脚本不被误判为加密`() {
        val script = "#!/system/bin/sh\nrm -rf /sdcard/x\nbase64 -d a.b64 > out.txt\n"
        assertFalse(ScriptAuditor.looksEncrypted(script))
    }

    @Test fun `解析超限升级为 CRITICAL 以便自动执行链拦截`() {
        // 单行 200 个原子 → 超过 MAX_ATOMS，parse truncated
        val longLine = (1..200).joinToString("; ") { "ls /a$it" }
        val report = ScriptAuditor.audit(longLine)
        assertTrue("期望 truncated=true", report.truncated)
        assertTrue(
            "超限必须产出 CRITICAL（否则 auto-exec 只拦 CRITICAL 会放行）",
            report.findings.any { it.ruleId == "LINE_TOO_COMPLEX" && it.level == RiskLevel.CRITICAL }
        )
    }

    // ========================================================================
    // 7) 第二轮：变量伪装程序名（$IFS 拼命令）
    // ========================================================================

    @Test fun `脚本里变量拼出的程序名按最高危拒绝`() {
        // r$IFSm → 真实是 rm。旧实现 basename 得到 "rm$IFS-rf$IFS/system"→"system"，完全绕过规则
        assertBlocked("r\$IFSm -rf /system", "UNRESOLVED_PROGRAM", CommandSource.SCRIPT_FILE)
        assertBlocked("rm\$IFS-rf\$IFS/system", "UNRESOLVED_PROGRAM", CommandSource.SCRIPT_FILE)
    }

    @Test fun `终端里变量程序名需确认`() {
        val r = v("r\$IFSm -rf /system")
        assertTrue("期望 Confirm，实际 $r", r is Verdict.Confirm)
        assertTrue((r as Verdict.Confirm).findings.any { it.ruleId == "UNRESOLVED_PROGRAM" })
    }

    // ========================================================================
    // 8) 第二轮：eval 载荷与 xargs 派发
    // ========================================================================

    @Test fun `eval 内的破坏命令会被展开并拦截`() {
        assertBlocked("eval \"rm -rf /system\"", "RM_SYSTEM", CommandSource.SCRIPT_FILE)
    }

    @Test fun `xargs 派发的破坏命令会被拦截`() {
        assertBlocked("xargs rm -rf /system", "RM_SYSTEM")
        assertBlocked("xargs -n1 rm -rf /vendor", "RM_SYSTEM")
    }

    // ========================================================================
    // 9) 第二轮：cp / mv 的写入与搬走
    // ========================================================================

    @Test fun `拷贝到系统路径被硬拦`() {
        assertBlocked("cp evil.bin /system/bin/x", "COPY_SYSTEM")
        assertBlocked("install -m 755 evil /system/xbin/y", "COPY_SYSTEM")
    }

    @Test fun `移动到系统路径被硬拦`() {
        assertBlocked("mv /sdcard/a /system/bin/", "MOVE_SYSTEM")
    }

    @Test fun `从系统路径移走文件为需确认`() {
        val r = v("mv /system/build.prop /sdcard/")
        assertTrue("期望 Confirm，实际 $r", r is Verdict.Confirm)
        assertTrue((r as Verdict.Confirm).findings.any { it.ruleId == "MOVE_SYSTEM_SRC" })
    }

    @Test fun `普通拷贝不误报`() {
        assertEquals(Verdict.Allow, v("cp /sdcard/a /sdcard/b"))
        assertEquals(Verdict.Allow, v("mv /sdcard/a /sdcard/b"))
    }

    // ========================================================================
    // 10) 路径分级：root 环境数据
    // ========================================================================

    @Test fun `删除 magisk 模块目录为需确认而非硬拦`() {
        assertEquals(PathClassifier.PathClass.DANGEROUS, PathClassifier.classify("/data/adb/modules/x"))
        // 应用工作区仍是 SAFE，不受影响
        assertEquals(PathClassifier.PathClass.SAFE, PathClassifier.classify("/data/adb/shso/x"))
    }
}
