// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

import android.content.Context
import com.mixradio.droid.data.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * shso_guard 守卫模块安装器（方案 §10）。
 *
 * 安装方式 = 复制安装：APK 内置 shso_guard.zip → 解压到缓存 → root 复制到
 * /data/adb/modules/shso_guard → chmod 0755。无需刷机、无需重启，装完立即生效
 * （拦截靠 PATH 前置，不靠开机脚本）。
 *
 * 卸载用绝对路径 /system/bin/rm 绕过守卫自身（守卫 protect=/data 会拦截对模块目录的
 * 常规 rm -rf——这正是守卫的自保护设计，App 卸载走受信路径绕行）。
 */
object GuardModuleInstaller {

    const val MODULE_ID = "shso_guard"
    const val MODULE_DIR = "/data/adb/modules/shso_guard"
    const val GUARD_BIN_DIR = "$MODULE_DIR/guard"
    private const val ASSET_ZIP = "shso_guard.zip"
    private val REQUIRED_ARCHIVE_ENTRIES = setOf(
        "module.prop",
        "policy.conf",
        "guard/common.sh",
        "guard/rm",
        "guard/rmdir",
        "guard/wipe",
        "guard/dd",
        "guard/fastboot",
        "guard/truncate",
        "guard/shred",
        "guard/make_f2fs",
        "guard/mke2fs",
        "guard/mkfs.ext4",
        "guard/mkfs.f2fs",
        "guard/mkfs.vfat",
        // 多二进制派发 + 高频破坏原语：缺任何一个即说明打包异常，安装前会被直接拒绝。
        "guard/toybox",
        "guard/busybox",
        "guard/mv",
        "guard/cp",
        "guard/find",
        "guard/sed",
        // 权限崩坏 / 分区表 / 刷机 这类「格机」原语
        "guard/chmod",
        "guard/chown",
        "guard/chgrp",
        "guard/mkfs",
        "guard/mknod",
        "guard/sgdisk",
        "guard/parted",
        "guard/fdisk",
        "guard/flash_image"
    )

    fun validateArchiveEntry(stagingDir: File, entryName: String): File? {
        if (entryName.isEmpty() || entryName.contains('\\') || entryName.contains('\u0000') ||
            entryName.startsWith('/') || entryName.matches(Regex("^[A-Za-z]:.*"))
        ) return null
        val components = entryName.split('/')
        if (components.any { it.isEmpty() || it == "." || it == ".." }) return null
        val staging = stagingDir.canonicalFile
        val target = File(staging, entryName).canonicalFile
        val stagingPath = staging.path + File.separator
        return if (target.path.startsWith(stagingPath)) target else null
    }

    fun hasRequiredArchiveEntries(entryNames: Collection<String>): Boolean =
        entryNames.containsAll(REQUIRED_ARCHIVE_ENTRIES)

    sealed class GuardStatus {
        data object NotInstalled : GuardStatus()
        data class Installed(val version: String, val disabled: Boolean) : GuardStatus()
        data class Unknown(val reason: String) : GuardStatus()
    }

    /** 查询守卫模块状态（一次 su 探测）。 */
    suspend fun status(): GuardStatus = withContext(Dispatchers.IO) {
        try {
            val (code, out) = RootService.runCommandSync(
                "if [ -f $MODULE_DIR/module.prop ]; then echo INSTALLED; " +
                    "grep '^version=' $MODULE_DIR/module.prop | head -1; " +
                    "if [ -e $MODULE_DIR/disable ]; then echo DISABLED; fi; " +
                    "else echo MISSING; fi",
                8_000L
            )
            if (code != 0) return@withContext GuardStatus.Unknown("su 返回 $code")
            val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
            when {
                lines.isEmpty() || lines.first() != "INSTALLED" -> GuardStatus.NotInstalled
                else -> GuardStatus.Installed(
                    version = lines.getOrNull(1)?.removePrefix("version=") ?: "?",
                    disabled = lines.getOrNull(2) == "DISABLED"
                )
            }
        } catch (e: Exception) {
            GuardStatus.Unknown(e.message ?: "异常")
        }
    }

    /** 守卫 bin 目录是否就绪（executeFile PATH 前置判定用，结果缓存 60s）。 */
    @Volatile
    private var guardReadyCache: Boolean? = null
    @Volatile
    private var guardReadyAt: Long = 0

    /** 后台刷新作用域：缓存过期时异步重探，避免占用调用线程。 */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var refreshing = false

    /**
     * 探测守卫 bin 目录是否就绪（结果缓存 60s）。
     *
     * 本函数会被 `RootService.guardPathPrefix()` 在 **UI 线程**调用（executeFile 由点击触发），
     * 而探测需要起 su 进程（最长 5s）——同步执行会直接造成 ANR。
     * 因此缓存过期时不再同步重探，而是「立即返回上次结果 + 后台刷新」；
     * 只有首次（无缓存）或显式 [forceRefresh] 才同步探测，而后者的调用方都在 IO 线程。
     */
    fun guardBinDirReady(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val cached = guardReadyCache
        if (!forceRefresh) {
            if (cached != null && now - guardReadyAt < 60_000) return cached
            if (cached != null) {
                scheduleRefresh()
                return cached
            }
        }
        return probeGuardBinDir()
    }

    /** 同步探测一次并写回缓存。 */
    private fun probeGuardBinDir(): Boolean {
        val ready = try {
            RootService.runCommandSync("test -x $GUARD_BIN_DIR/rm", 5_000L).first == 0
        } catch (_: Exception) {
            false
        }
        guardReadyCache = ready
        guardReadyAt = System.currentTimeMillis()
        return ready
    }

    private fun scheduleRefresh() {
        if (refreshing) return
        refreshing = true
        refreshScope.launch {
            try {
                probeGuardBinDir()
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * 安装/覆盖安装：assets/shso_guard.zip → cacheDir 解压 → root 复制。
     * @return (成功, 消息)
     */
    suspend fun install(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 1) 从 APK assets 解压到应用缓存目录
            val staging = File(context.cacheDir, "guard_install").apply { mkdirs() }
            val moduleDir = File(staging, MODULE_ID)
            moduleDir.deleteRecursively()
            moduleDir.mkdirs()

            context.assets.open(ASSET_ZIP).use { asset ->
                ZipInputStream(asset.buffered()).use { zis ->
                    val archiveEntries = mutableSetOf<String>()
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val target = validateArchiveEntry(moduleDir, entry.name)
                            ?: return@withContext Pair(false, "安装包含非法路径: ${entry.name.take(120)}")
                        val name = target.relativeTo(moduleDir.canonicalFile).path.replace(File.separatorChar, '/')
                        archiveEntries += name
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                        zis.closeEntry()
                    }
                    if (!hasRequiredArchiveEntries(archiveEntries)) {
                        return@withContext Pair(false, "安装包缺少守卫模块必需文件（打包异常）")
                    }
                }
            }

            // 2) root 原子替换安装
            //    不能「先 rm -rf 旧模块再 cp -R 新模块」：cp 失败或空间不足时旧守卫已删除、
            //    新守卫不完整，运行时防护会静默失效。
            //    改为同文件系统内构建 .new → 校验 → 旧目录挪 .old → mv 原子替换 → 删 .old，
            //    任一步失败都保留或回滚旧版本。
            //    放在 /data/adb 下（而非 /data/adb/modules 内）是为了不让 Magisk 把临时目录当成模块。
            val stagingPath = RootService.escapeShellArg(moduleDir.absolutePath)
            val newDir = "/data/adb/.shso_guard.new"
            val oldDir = "/data/adb/.shso_guard.old"
            val installScript = buildString {
                append("mkdir -p /data/adb && ")
                append("/system/bin/rm -rf $newDir 2>/dev/null; ")
                // 任一步失败都要清掉半成品 .new，不能把垃圾留在 /data/adb
                append("cp -R $stagingPath $newDir && chmod -R 0755 $newDir || { /system/bin/rm -rf $newDir; exit 2; }; ")
                append("if ! (test -f $newDir/module.prop && test -x $newDir/guard/rm); then /system/bin/rm -rf $newDir; exit 3; fi; ")
                append("/system/bin/rm -rf $oldDir 2>/dev/null; ")
                append("if [ -d $MODULE_DIR ]; then mv $MODULE_DIR $oldDir || exit 4; fi; ")
                append("if ! mv $newDir $MODULE_DIR; then /system/bin/rm -rf $newDir; ")
                append("[ -d $oldDir ] && mv $oldDir $MODULE_DIR; exit 5; fi; ")
                append("/system/bin/rm -rf $oldDir 2>/dev/null; true")
            }
            RootService.runCommandSync(installScript, 60_000L).let { (code, out) ->
                if (code != 0) {
                    return@withContext Pair(false, "root 复制失败(code=$code): ${out.trim().take(200)}")
                }
            }

            // 3) 校验
            val ok = RootService.runCommandSync(
                "test -f $MODULE_DIR/module.prop -a -x $GUARD_BIN_DIR/rm", 8_000L
            ).first == 0
            if (!ok) return@withContext Pair(false, "安装校验失败（guard/rm 不可执行）")

            guardBinDirReady(forceRefresh = true)
            SecurityAuditLog.log(
                CommandSource.INTERNAL_APP, "ALLOW", "GUARD_INSTALL", RiskLevel.SAFE,
                "安装守卫模块 shso_guard → $MODULE_DIR"
            )
            Pair(true, "守卫模块已安装，立即生效（管理器中可见需重启）")
        } catch (e: Exception) {
            Pair(false, "安装失败: ${e.message}")
        }
    }

    /** 失效「守卫就绪」缓存，使档位变更 / 安装卸载后立即重新探测（无需等 60s TTL）。 */
    fun invalidateReadyCache() {
        guardReadyCache = null
        guardReadyAt = 0
    }

    /** 就绪缓存是否已确定（未探测或已过期返回 false）。 */
    fun hasFreshReadyCache(): Boolean {
        val cache = guardReadyCache ?: return false
        return cache && System.currentTimeMillis() - guardReadyAt < 60_000
    }

    /**
     * 该档位是否需要运行时守卫（纯函数，便于单测）。
     *
     * 档位 0/1 明确表示「不拦截」，安装守卫既无意义也违反档位语义，故只在 ≥标准防护时安装。
     */
    fun requiresRuntimeGuard(securityLevel: Int): Boolean = securityLevel >= SecurityLevels.STANDARD

    /**
     * 档位 → 守卫 `policy.conf` 的 `mode` 取值（纯函数，便于单测）。
     *
     * 0(关) → `off`（完全放行）；1(仅审计) → `log`（只记录不拦截）；2/3(标准/最强) → `enforce`。
     * 越界或未知档位一律按最严格处理（`enforce`），与守卫自身的 fail-closed 取向一致。
     */
    fun policyModeFor(securityLevel: Int): String = when (securityLevel) {
        SecurityLevels.OFF -> "off"
        SecurityLevels.AUDIT_ONLY -> "log"
        // 2/3 以及一切越界/未知取值一律取最严格档，避免脏数据把守卫降级成完全放行
        // （`mode=off` 是完全不拦截，属 fail-open，与项目「误断网 > 意外放行」取向相反）
        else -> "enforce"
    }

    /**
     * 按安全档位同步守卫 `policy.conf` 的 `mode=`。
     *
     * 守卫未就绪时静默返回 false（策略文件本身不存在时，守卫会退回内置兜底清单）。
     * 只改写 `mode=` 行，其余 `protect=` / `allow=` 用户自定义内容原样保留。
     */
    suspend fun syncPolicyMode(securityLevel: Int): Boolean = withContext(Dispatchers.IO) {
        if (!guardBinDirReady()) return@withContext false
        val mode = policyModeFor(securityLevel)
        val policyDir = "/data/adb/shso_guard"
        val script = buildString {
            append("d=$policyDir; mkdir -p \$d; f=\$d/policy.conf; ")
            append("[ -f \"\$f\" ] || cp $MODULE_DIR/policy.conf \"\$f\" 2>/dev/null; ")
            append("[ -f \"\$f\" ] || : > \"\$f\"; ")
            // 删掉既有的 mode= 行（容忍 `mode = x` 写法），再追加一行标准写法
            append("grep -v '^[[:space:]]*mode[[:space:]]*=' \"\$f\" > \"\$f.shso.tmp\" 2>/dev/null; ")
            append("[ -s \"\$f.shso.tmp\" ] || : > \"\$f.shso.tmp\"; ")
            append("mv \"\$f.shso.tmp\" \"\$f\" 2>/dev/null; ")
            append("echo 'mode=$mode' >> \"\$f\"")
        }
        val code = RootService.runCommandSync(script, 10_000L).first
        if (code == 0) {
            SecurityAuditLog.log(
                CommandSource.INTERNAL_APP, "ALLOW", "GUARD_POLICY_MODE", RiskLevel.SAFE,
                "$policyDir/policy.conf mode=$mode (档位=$securityLevel)"
            )
            true
        } else {
            false
        }
    }

    /**
     * 确保守卫可用：需要时安装/升级，已就绪且版本一致时直接返回 true。
     *
     * 调用时机：档位 ≥2（进入 App / 切换到受保护档位）。
     *
     * **升级判定**：不能仅凭 `guard/rm` 存在就判定已就绪，否则版本过旧时不会更新到
     * APK 内置的新版守卫（新增的 toybox/busybox/mv/cp/find/sed 包装器将失效）。
     * 故比对 APK 内置 `module.prop` 的 `version=` 与已装模块版本，不一致即重装。
     *
     * 并发：一次档位变更会被 MainActivity 与 SettingsPage 同时触发本函数，
     * 两个 `install()` 并发 `rm -rf $MODULE_DIR` + `cp -R` 会互相破坏
     * （表现为安装校验失败、`guard/rm` 不可执行）。故用互斥锁串行化，
     * 并在持锁后重新判定（第二个调用方直接复用结果）。
     *
     * 失败返回 false —— 调用方应降级为醒目告警后继续放行，而不是阻断全部执行
     * （硬阻断会让未装守卫的设备连 `ls` 都无法执行）。
     */
    private val installMutex = Mutex()

    suspend fun ensureInstalled(context: Context): Boolean = installMutex.withLock {
        if (guardBinDirReady(forceRefresh = true) && !needsUpgrade(context)) return@withLock true
        return@withLock try {
            val (ok, msg) = install(context)
            if (!ok) {
                SecurityAuditLog.log(
                    CommandSource.INTERNAL_APP, "BLOCK", "GUARD_AUTO_INSTALL_FAILED", RiskLevel.DANGEROUS,
                    msg
                )
            }
            ok && guardBinDirReady(forceRefresh = true)
        } catch (e: Exception) {
            SecurityAuditLog.log(
                CommandSource.INTERNAL_APP, "BLOCK", "GUARD_AUTO_INSTALL_FAILED", RiskLevel.DANGEROUS,
                e.message ?: "异常"
            )
            false
        }
    }

    /** 已装模块与 APK 内置版本不一致（或已装模块无版本号）时判定需要重装。 */
    private suspend fun needsUpgrade(context: Context): Boolean {
        val bundled = bundledModuleVersion(context) ?: return false
        val installed = (status() as? GuardStatus.Installed)?.version ?: return true
        return bundled != installed || installed == "?"
    }

    /**
     * 读取 APK 内置守卫模块声明的 `version=`（升级判定用）。
     * 读取失败返回 null —— 此时跳过升级判定，按「已就绪」处理，不阻塞启动。
     */
    private fun bundledModuleVersion(context: Context): String? = try {
        context.assets.open(ASSET_ZIP).use { asset ->
            ZipInputStream(asset.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "module.prop") {
                        return zis.readBytes().toString(Charsets.UTF_8)
                            .lineSequence()
                            .firstOrNull { it.startsWith("version=") }
                            ?.removePrefix("version=")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    }
                    entry = zis.nextEntry
                }
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** 卸载守卫模块。 */
    suspend fun uninstall(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 绝对路径绕过守卫自保护（protect=/data）
            val (code, out) = RootService.runCommandSync(
                "/system/bin/rm -rf $MODULE_DIR", 30_000L
            )
            if (code != 0) return@withContext Pair(false, "卸载失败: ${out.trim().take(200)}")
            guardReadyCache = false
            guardReadyAt = System.currentTimeMillis()
            SecurityAuditLog.log(
                CommandSource.INTERNAL_APP, "ALLOW", "GUARD_UNINSTALL", RiskLevel.SAFE,
                "卸载守卫模块 $MODULE_DIR"
            )
            Pair(true, "守卫模块已卸载")
        } catch (e: Exception) {
            Pair(false, "卸载失败: ${e.message}")
        }
    }
}
