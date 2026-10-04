// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import android.os.Process
import java.util.Locale

/**
 * APK / XAPK 安装器（root 静默）。
 *
 * 方案参考 MP-Manager RootManager：
 * - 单 APK：先 `cp` 到 /data/local/tmp 再 `pm install -r`，装完清理——从原路径直接
 *   `pm install` 在部分 ROM 上会因 SELinux/存储权限失败，拷 tmp 是最稳路径。
 * - split/XAPK：`pm install-create -r -S <总大小>` → 逐个 `pm install-write -S <size>
 *   <session> split<i> <path>` → `pm install-commit`；session id 从输出 `[<id>]` 解析。
 * - XAPK 本质是 zip：解出 base.apk + split_config.*.apk 落位 /data/local/tmp，
 *   OBB 数据拷到 /sdcard/Android/obb/<包名>/。
 * - 全程使用 [RootService.escapeShellArg] 防注入；每条命令带超时。
 */
object ApkInstaller {

    /** 安装结果：成功 / 失败(消息) / 需要系统确认(无 root 兜底)。 */
    sealed class InstallResult {
        data class Success(val message: String) : InstallResult()
        data class Failure(val message: String) : InstallResult()
    }

    private const val TMP_DIR = "/data/local/tmp"

    /** 安装大包允许的更长超时（拷贝 + pm 会话流可能超过默认 120s）。 */
    private const val INSTALL_TIMEOUT_MS = 300_000L
    private const val OBB_LOCK_TTL_SECONDS = 15 * 60L
    /** 锁被占用时的退避重试次数（配合 acquireObbLock 的 250ms 递增退避）。 */
    private const val OBB_LOCK_RETRY = 4
    internal const val MAX_XAPK_BYTES = 1L * 1024 * 1024 * 1024
    internal const val MAX_XAPK_ENTRY_BYTES = 512L * 1024 * 1024
    internal const val MAX_XAPK_ENTRIES = 20_000
    private const val MAX_XAPK_MANIFEST_BYTES = 1L * 1024 * 1024
    private val ANDROID_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /**
     * OBB 事务锁的退出码契约（与 [acquireObbLock] 的 shell 脚本一一对应）。
     *
     * 区分「忙」与「状态不明」是必需的：忙可以退避重试，状态不明必须 fail-closed
     * 立即失败，否则一个被外部创建/损坏的锁会把安装入口变成不可用且无从诊断。
     */
    internal object ObbLockExit {
        /** 持锁者存活，或未过 TTL：可退避重试。 */
        const val BUSY = 17

        /** 锁存在但元数据缺失/不可解析，或本次隔离到的不是刚才判定的那把锁：拒绝。 */
        const val UNKNOWN = 21

        /** 写元数据 / 原子创建失败：文件系统错误。 */
        const val PUBLISH_FAILED = 22
    }

    internal fun isValidAndroidPackageName(value: String): Boolean =
        ANDROID_PACKAGE_NAME.matches(value)

    internal fun isValidVersionCode(value: String): Boolean = value.matches(Regex("\\d+"))

    /**
     * 判定安装源是否可读。
     *
     * `java.io.File.exists()` 以应用 uid 判定，对 `/data/adb/` 一类 ROOT 专属路径
     * 恒为 false——直接用它做前置检查会让「已授权 ROOT 也装不上 /data/adb 里的 APK」。
     * 已授权 ROOT 时改用 root `test -e -f`，未授权才退回 Java 判定。
     */
    private suspend fun pathReadable(path: String): Boolean {
        if (RootService.isRootGranted == true) {
            val (code, _) = RootService.runCommandSync(
                "test -f ${RootService.escapeShellArg(path)}", 20_000L
            )
            if (code == 0) return true
            // ROOT 探测结果可能是陈旧的（刚被撤销/刚授权），回退 Java 判定再确认一次，
            // 避免把「其实应用自己就能读」的常见路径误杀。
        }
        val f = File(path)
        return f.isFile && f.canRead()
    }

    /**
     * 安装 APK（root 静默）。
     * 支持伪装名：qq.apk.1（腾讯下载追加 .1）、APK/Apk 等大小写变体——
     * 统一先拷到 /data/local/tmp 的规范名 _shso_install.apk 再安装。
     *
     * **注意存在性检查**：`File.exists()` 走应用 uid，对 `/data/adb/` 一类
     * ROOT 专属路径必然返回 false。已授权 ROOT 时改用 root `test -e` 判定，
     * 否则「装了 ROOT 也装不上 /data/adb 里的 APK」。
     *
     * **分包自动识别**：若同目录存在同一套件的分包（shso 自己提取出的
     * `<名>-<版本>-splitN.APK`，或 SAI/MT 解包的 `split_config.*.apk`），
     * 则自动改装整套（单文件 `pm install` 对分包应用必然失败：
     * INSTALL_FAILED_MISSING_SPLIT）。点基础包或点任意分包都能装整套。
     *
     * @param sourcePath 用户实际点中的**原始**文件路径。安装确认弹窗会把待装包暂存成
     *   `<cacheDir>/install-confirm/<uuid>.apk`（暂存是为了校验后不被替换），而兄弟分片
     *   的发现必须以原始目录为基准 —— 暂存目录里只有那一个 uuid 文件，按它列举永远
     *   只得到 1 个兄弟，分包套件会被静默降级成单文件安装。传 null 表示没有暂存副本。
     */
    suspend fun installApk(context: Context, apkPath: String, sourcePath: String? = null): InstallResult =
        withContext(Dispatchers.IO) {
        if (!pathReadable(apkPath)) return@withContext InstallResult.Failure("APK 文件不存在或不可读: $apkPath")

        // 同目录聚合出「安装套件」；只有基础包时退回单文件安装
        val set = collectApkSet(context, sourcePath ?: apkPath)
        if (set.isSplit) {
            // 整套分片**全部**用已校验的暂存副本安装。
            //
            // 此前只暂存被点中的那一个，兄弟分片仍按原始路径取 ——
            // 而分片才是真正的代码载体（base 只含清单与入口）。
            // 确认弹窗停留期间（用户阅读提示、切换应用），共享存储上的兄弟分片
            // 可被替换：下载器续传重写、另一应用写入 /sdcard（该分区任何应用可写）、
            // 或用户自己用本应用编辑器覆盖该文件。随后点「确认安装」，
            // 用户看到的那个 SHA-256 完全正确，实际被 root 安装的却是替换后的字节
            // → 任意代码以 root 静默装入（pm install-commit 无系统确认），且无从察觉。
            //
            //
            // 本次新增的暂存副本必须在安装结束后清理，否则 cache/install-confirm
            // 会随每次安装永久堆积（单个 APK 数 MB，整套可达数十 MB）。
            // 全量暂存把「确认后不被替换」的保证从 1 个文件扩展到整套套件。
            // 分片数上限 MAX_SPLITS 有界（64），暂存代价可接受。
            val stagedHere = ArrayList<String>()
            val stagedPaths = set.orderedWrites.map { path ->
                if (path == sourcePath) {
                    apkPath
                } else {
                    val staged = stageApkForInstall(context, path)?.first
                    if (staged == null) {
                        // 暂存失败就不能装：宁可不装，也不能装一份可能被替换过的字节。
                        return@withContext InstallResult.Failure(
                            "无法校验分包 ${File(path).name}，已中止安装以防装入被替换的文件"
                        )
                    }
                    staged.also { stagedHere.add(it) }
                }
            }
            val outcome = when (val r = installSplitApks(context, stagedPaths)) {
                is InstallResult.Success -> InstallResult.Success("${r.message}（含 ${set.splits.size} 个分包）")
                is InstallResult.Failure -> r
            }
            stagedHere.forEach { runCatching { File(it).delete() } }
            outcome
        }

        // 统一用规范名（.apk）落到 /data/local/tmp：pm install 对 .1 等非规范后缀可能拒绝
        val tmpApk = "$TMP_DIR/_shso_install_${UUID.randomUUID()}.apk"
        val cleanCmd = "rm -f ${RootService.escapeShellArg(tmpApk)}"
        // -1 = runCommandSync 超时/异常。此时 `pm install` 可能**仍在运行**
        // （su 超时只代表没等到，不保证子进程已死），无条件删除会让它读到被删的
        // 文件 → 后续偶发 INSTALL_FAILED_* 或半安装态，且用户完全无法归因。
        // 故超时场景保留临时文件并如实告知，由用户稍后自行清理。
        var installTimedOut = false
        try {
            RootService.runCommandSync(cleanCmd, INSTALL_TIMEOUT_MS)

            val copyCmd = "cp ${RootService.escapeShellArg(apkPath)} ${RootService.escapeShellArg(tmpApk)}"
            val (copyCode, copyOut) = RootService.runCommandSync(copyCmd, INSTALL_TIMEOUT_MS)
            if (copyCode != 0) {
                return@withContext InstallResult.Failure("复制 APK 到临时目录失败: $copyOut")
            }

            // 安装（-r 覆盖安装 -d 允许降级 -t 测试包）
            val installCmd = "pm install -r -d -t ${RootService.escapeShellArg(tmpApk)}"
            val (installCode, installOut) = RootService.runCommandSync(installCmd, INSTALL_TIMEOUT_MS)

            if (installCode == 0 && (installOut.contains("Success") || installOut.contains("success"))) {
                InstallResult.Success("安装成功")
            } else {
                if (installCode == -1) installTimedOut = true
                val tail = if (installTimedOut) {
                    "（安装超时，临时文件已保留以免打断仍在进行的安装：$tmpApk）"
                } else {
                    ""
                }
                InstallResult.Failure(
                    "安装失败: ${installOut.trim().ifEmpty { "未知错误" }}$tail"
                )
            }
        } finally {
            if (!installTimedOut) {
                RootService.runCommandSync(cleanCmd, INSTALL_TIMEOUT_MS)
            }
        }
    }

    /**
     * 安装 XAPK（含 split 分片与 OBB 数据）。
     *
     * @param xapkPath  .xapk/.apks/.aspk/.apkm 文件路径
     */
    @SuppressLint("SdCardPath")
    suspend fun installXapk(context: Context, xapkPath: String): InstallResult = withContext(Dispatchers.IO) {
        val file = File(xapkPath)
        if (!file.isFile) return@withContext InstallResult.Failure("文件不存在: $xapkPath")

        // 1. 解压 XAPK（zip4j，zip 条目名可能含中文，需 UTF-8）
        val stagingDir = File(File(xapkPath).parentFile ?: File(TMP_DIR), ".shso_xapk_${UUID.randomUUID()}")
        val installedObbTargets = mutableListOf<String>()
        // 安装前就存在、走幂等分支而**未被本次改动**的目标 OBB。
        // 必须与 installedObbTargets 严格分开：后者是「失败该清理」清单。
        // 声明在 try 外，outer finally 的回滚要用它做减法。
        val preexistingObbTargets = mutableSetOf<String>()
        var obbTransactionLock: String? = null
        var obbLockToken: String? = null
        var installSucceeded = false
        try {
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                return@withContext InstallResult.Failure("无法创建 XAPK 临时目录")
            }

            if (file.length() > MAX_XAPK_BYTES) {
                return@withContext InstallResult.Failure("XAPK 文件超过 ${MAX_XAPK_BYTES / 1024 / 1024}MB 上限")
            }
            val apkFiles = mutableListOf<File>()
            val obbFiles = mutableListOf<File>()
            val extractedNames = mutableSetOf<String>()
            var extractedBytes = 0L
            var manifestJson: JSONObject? = null

            try {
                // 条目数预算前置：zip4j 的 `fileHeaders` 会一次性把整个 central directory
                // 构造成对象列表，预算检查放在其后等于没有。详见 [ZipEntryCountProbe]。
                val probed = ZipEntryCountProbe.probe(file, MAX_XAPK_ENTRIES)
                if (probed != null && probed >= MAX_XAPK_ENTRIES) {
                    return@withContext InstallResult.Failure("XAPK 条目数超过 $MAX_XAPK_ENTRIES")
                }
                ZipFile(xapkPath).use { zip ->
                    val headers = zip.fileHeaders
                    if (headers.size > MAX_XAPK_ENTRIES) {
                        return@withContext InstallResult.Failure("XAPK 条目数超过 $MAX_XAPK_ENTRIES")
                    }
                    for (header in headers) {
                        val entryName = header.fileName
                        // 跳过目录条目与 manifest 之外的元数据
                        if (header.isDirectory) continue

                        val lower = entryName.lowercase(Locale.ROOT)
                        when {
                            entryName == "manifest.json" -> {
                                if (header.uncompressedSize > MAX_XAPK_MANIFEST_BYTES) {
                                    return@withContext InstallResult.Failure("XAPK manifest.json 过大")
                                }
                                val content = zip.getInputStream(header).use {
                                    readBoundedUtf8(it, MAX_XAPK_MANIFEST_BYTES)
                                }
                                manifestJson = try { JSONObject(content) } catch (_: Exception) { null }
                            }
                            lower.endsWith(".apk") -> {
                                val name = File(entryName).name
                                if (!extractedNames.add(name)) return@withContext InstallResult.Failure("XAPK 包含重复文件名: $name")
                                val dest = File(stagingDir, name)
                                extractedBytes = copyXapkEntry(zip, header, dest, extractedBytes)
                                apkFiles.add(dest)
                            }
                            lower.endsWith(".obb") -> {
                                val name = File(entryName).name
                                if (!extractedNames.add(name)) return@withContext InstallResult.Failure("XAPK 包含重复文件名: $name")
                                val dest = File(stagingDir, name)
                                extractedBytes = copyXapkEntry(zip, header, dest, extractedBytes)
                                obbFiles.add(dest)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext InstallResult.Failure("XAPK 解压失败: ${e.message}")
            }

            if (apkFiles.isEmpty()) {
                return@withContext InstallResult.Failure("XAPK 中未找到任何 APK 文件")
            }

            // 2. OBB 数据落位 /sdcard/Android/obb/<包名>/
            // 该路径由 ROOT shell 侧使用（mkdir/cp），非 Java 文件 API，故不能用 Environment 构造。
            // Android 规范：OBB 文件名必须为 main.<versionCode>.<packageName>.obb
            val packageName = manifestJson?.optString("package_name")
                ?.takeIf(::isValidAndroidPackageName)
            val versionCode = manifestJson?.optString("version_code")
                ?.takeIf(::isValidVersionCode) ?: "1"
            if (obbFiles.isNotEmpty() && packageName == null) {
                return@withContext InstallResult.Failure("XAPK 含 OBB 但 manifest 缺少有效包名")
            }
            if (obbFiles.isNotEmpty()) {
                val obbDir = "/sdcard/Android/obb/$packageName"
                obbTransactionLock = "$obbDir/.shso_install.lock"
                obbLockToken = UUID.randomUUID().toString()

                // **必须先建目录再取锁**：锁用 `( set -C; printf > LOCK )` 创建，
                // 而该重定向在父目录不存在时直接失败（ENOENT），`if` 条件为假 →
                // 走 `exit BUSY`，用户看到的是误导性的「OBB 目录正在被其他安装任务使用」，
                // 并白等 4 次退避重试（重试永远无效，因为目录依然不存在）。
                // 任何新游戏首次安装时 `/sdcard/Android/obb/<pkg>` 都不存在
                // （AOSP 要到 APK 安装那一步才建），所以这是**含 OBB 的 XAPK 的必现失败**。
                val mkdirCmd = "mkdir -p ${RootService.escapeShellArg(obbDir)}"
                val (mkdirCode, mkdirOutput) = RootService.runCommandSync(mkdirCmd, INSTALL_TIMEOUT_MS)
                if (mkdirCode != 0) {
                    return@withContext InstallResult.Failure("创建 OBB 目录失败: ${mkdirOutput.trim()}")
                }

                var lockResult = acquireObbLock(obbTransactionLock, obbLockToken)
                // 只对「确实被占用」退避重试；状态不明/文件系统错误必须立即失败，
                // 否则用户会看到「正在被其他安装任务使用」这种无法定位的提示。
                for (attempt in 0 until OBB_LOCK_RETRY) {
                    if (lockResult.first != ObbLockExit.BUSY) break
                    delay((attempt + 1) * 250L)
                    lockResult = acquireObbLock(obbTransactionLock, obbLockToken)
                }
                if (lockResult.first != 0) {
                    val reason = when (lockResult.first) {
                        ObbLockExit.BUSY -> "OBB 目录正在被其他安装任务使用，请稍后重试"
                        ObbLockExit.UNKNOWN -> "无法确认 OBB 锁状态（锁文件可能已损坏或被外部占用），已拒绝安装"
                        else -> "OBB 锁操作失败: ${lockResult.second.trim()}"
                    }
                    return@withContext InstallResult.Failure(reason)
                }
                val copiedObbTargets = mutableListOf<String>()
                try {
                    for (obb in obbFiles) {
                        // 已符合 main.<vc>.<pkg>.obb 命名则原样拷贝，否则按规范重命名
                        val targetName = if (obb.name.matches(Regex("^(main|patch)\\.\\d+\\..+\\.obb$"))) {
                            obb.name
                        } else {
                            "main.$versionCode.$packageName.obb"
                        }
                        val targetPath = "$obbDir/$targetName"
                        if (targetPath in copiedObbTargets) {
                            return@withContext InstallResult.Failure("XAPK 包含重复 OBB 目标: $targetName")
                        }
                        val (copyCode, copyOutput) = copyObbAtomically(
                            obb.absolutePath, targetPath, obbTransactionLock, obbLockToken
                        )
                        if (copyCode == OBB_EXIT_ALREADY_EXISTS) {
                            // 目标已存在：同身份说明期望的最终状态已经成立（重复安装同一 XAPK），
                            // 视为成功而不是失败。此前这里直接失败且脚本一个字节都没输出，
                            // 界面只显示「复制 OBB 失败: 」，用户既看不出原因也没有自愈路径，
                            // 该 XAPK 从此再也装不上。
                            val existing = readObbIdentity(targetPath)
                            if (existing != null && existing == readObbIdentity(obb.absolutePath)) {
                                // 关键：**不得**登记进 installedObbTargets。
                                // 那张表是「本次安装亲手落位、失败时应清理」的清单，
                                // 而这个文件是**安装前就存在**的用户数据（copyObbAtomically
                                // 全程刻意不覆盖它）。登记后 outer finally 的回滚会
                                // removeOwnedObb 把它删掉：两道校验此时都通过（锁仍是本次的，
                                // 文件从未被本次改动），rm 命中 —— APK 安装失败时
                                // 用户的游戏数据包被静默删除，且无处恢复。
                                // 幂等分支只登记「无需清理」，不登记「需要清理」。
                                preexistingObbTargets += targetPath
                                continue
                            }
                            return@withContext InstallResult.Failure(
                                "目标 OBB 已存在且内容不同：$targetName。" +
                                    "为避免覆盖游戏正在使用的数据包，shso 不会替换它；" +
                                    "请先删除该文件再重新安装：$targetPath"
                            )
                        }
                        if (copyCode != 0) {
                            return@withContext InstallResult.Failure(
                                "复制 OBB 失败: ${copyOutput.trim().ifEmpty { "未知错误（exit=$copyCode）" }}"
                            )
                        }
                        copiedObbTargets += targetPath
                        readObbIdentity(targetPath)?.let { identity ->
                            installedObbTargets += "$targetPath|$identity"
                        }
                    }
                } finally {
                    // Do not leave a partial OBB set when any copy fails or is cancelled.
                    if (copiedObbTargets.size != obbFiles.size) {
                        // 走 removeOwnedObb（锁校验 + inode:size:mtime 校验），不用裸 rm -f：
                        // 从落位到此刻已过去一个最长 300s 的 cp 窗口，期间游戏自身/文件
                        // 管理器/旧版 shso 若按同规范名重建了该文件，无校验的 rm 会把它删掉。
                        copiedObbTargets.forEach { target ->
                            val identity = installedObbTargets.firstOrNull { it.startsWith("$target|") }
                            if (identity != null) {
                                removeOwnedObb(identity, obbTransactionLock, obbLockToken ?: return@forEach)
                            }
                        }
                    }
                }
            }

            // 3. 单 APK 直接装；多 APK（split）走会话流
            val installResult = if (apkFiles.size == 1) {
                installApk(context, apkFiles[0].absolutePath)
            } else {
                installSplitApks(context, orderBaseFirst(context, apkFiles).map { it.absolutePath })
            }
            installSucceeded = installResult is InstallResult.Success
            return@withContext installResult
        } finally {
            if (!installSucceeded && installedObbTargets.isNotEmpty()) {
                installedObbTargets.forEach { target ->
                    // 双重保险：即便幂等条目被误登记，也绝不删除用户原有 OBB。
                    val path = target.substringBefore('|')
                    if (path in preexistingObbTargets) return@forEach
                    obbLockToken?.let { removeOwnedObb(target, obbTransactionLock, it) }
                }
            }
            if (obbTransactionLock != null && obbLockToken != null) {
                releaseObbLock(obbTransactionLock, obbLockToken)
            }
            // 清理解压的临时目录（保留 OBB 已拷走的副本）
            try {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    /**
     * 把 OBB 拷到目标旁的临时文件，再原子 `mv` 落到目标路径。
     *
     * 三道校验：
     * 1. 复制前后各确认一次锁仍属本任务（事务可能被别的任务接管）；
     * 2. 目标已存在或为软链一律拒绝，绝不覆盖用户原有 OBB；
     * 3. `mv` 只在同目录内进行（同目录 rename 才是原子的；跨挂载点的 `mv` 会退化为
     *    复制+删除，原子性丧失——这也是临时文件必须与目标同目录的原因）。
     */
    private fun copyObbAtomically(
        sourcePath: String,
        targetPath: String,
        lockPath: String?,
        token: String?
    ): Pair<Int, String> {
        val tempPath = "$targetPath.shso.tmp.${UUID.randomUUID()}"
        val source = RootService.escapeShellArg(sourcePath)
        val target = RootService.escapeShellArg(targetPath)
        val temp = RootService.escapeShellArg(tempPath)
        val lock = RootService.escapeShellArg(lockPath ?: return ObbLockExit.UNKNOWN to "OBB lock missing")
        val tokenArg = RootService.escapeShellArg(token ?: return ObbLockExit.UNKNOWN to "OBB lock token missing")
        val lockOwned = "if [ \"\$(cat $lock 2>/dev/null | cut -d'|' -f1)\" != $tokenArg ]; then exit ${ObbLockExit.UNKNOWN}; fi; "
        val script = buildString {
            append("trap 'rm -f $temp' EXIT; ")
            append(lockOwned)
            // 复制前先探一次：已存在就不必浪费一次几百 MB 的 cp。
            // 必须 echo 出原因 —— 此前这里静默 exit，界面只显示「复制 OBB 失败: 」，
            // 空白错误无法自愈。
            append("if [ -e $target ] || [ -L $target ]; then echo \"目标已存在: $target\"; exit $OBB_EXIT_ALREADY_EXISTS; fi; ")
            append("cp $source $temp || exit 19; ")
            append(lockOwned)
            // mv 之前再探一次：cp 一个几百 MB 的 OBB 最长 300s，这段时间里游戏自身/
            // 文件管理器/未走锁的旧版可能已创建该文件，而 mv 是覆盖语义且返回 0。
            append("if [ -e $target ] || [ -L $target ]; then echo \"复制期间目标被创建: $target\"; exit $OBB_EXIT_ALREADY_EXISTS; fi; ")
            append("mv $temp $target || exit 20; ")
            append("trap - EXIT")
        }
        return RootService.runCommandSync(script, INSTALL_TIMEOUT_MS)
    }

    /**
     * OBB 落位的「文件身份」：`inode:size:mtime`，mtime 取 **纳秒**。
     *
     * 为什么必须是纳秒（真机 PACM00 / Android 10 实测）：`stat -c %Y` 只有秒级精度，
     * 而 OBB 落位 + APK 安装 + 失败回滚都发生在同一秒内。实测在同一秒内连续替换同一
     * 文件两次，`%i:%s:%Y` 三元组**完全相同**（inode 复用 + 同秒 mtime + 同样大小），
     * 意味着「回滚前校验身份」这条防线在真实时间窗内形同虚设，外部进程可以在事务
     * 期间把目标换成自己的文件而回滚仍会删掉它。`%y` 带纳秒，实测可区分。
     *
     * 身份不可解析时返回 null，调用方必须 fail-closed（不删除），不得当作「无需回滚」。
     */
    private fun readObbIdentity(path: String): String? {
        val escaped = RootService.escapeShellArg(path)
        val (code, output) = RootService.runCommandSync("stat -c '%i:%s:%y' $escaped 2>/dev/null", INSTALL_TIMEOUT_MS)
        val value = output.trim()
        // %i/%s 为纯数字，%y 形如 "2026-10-02 09:52:03.108011510 +0800"（含空格与 +，故整体匹配）
        return value.takeIf { code == 0 && OBB_IDENTITY_REGEX.matches(value) }
    }

    /** 身份格式：`<inode>:<size>:<mtime>`，mtime 段允许空格、点、冒号、时区偏移与正负号。 */
    private val OBB_IDENTITY_REGEX = Regex("\\d+:\\d+:.+")

    /**
     * 以「单文件 + O_EXCL 原子创建」实现 OBB 事务锁。
     *
     * ## 为什么换成文件锁而不是目录锁
     *
     * 原实现是「建临时锁目录 → 写元数据 → `mv $tempLock $lock`」。POSIX 的 `mv dir1 dir2`
     * 在 dir2 已存在且是目录时，会把 dir1 **移进** dir2 内部并返回 0（真机实测确认）。
     * 于是并发两方都会拿到「发布成功」，同时认为自己持锁 → OBB 落位与回滚互相踩。
     * 这不是理论窗口，是**每次陈旧锁回收竞争都会稳定复现**的漏洞。
     *
     * 改用文件锁的三个理由：
     * 1. `set -C`（noclobber）在 `>` 重定向时使用 `O_CREAT|O_EXCL`，**创建本身即 CAS**，
     *    不存在「发布」步骤，也就没有「发布到已存在目标」的窗口。真机已验证在
     *    emulated/FUSE 存储上同样生效（这是选它而非 `ln` 硬链接的原因：FUSE 不支持
     *    跨设备硬链接，实测 `ln` 报 `Cross-device link`）。
     * 2. `mv file file` 在目标已存在时是**覆盖**而非嵌套（与目录行为相反），
     *    语义可预期。
     * 3. 元数据可以一次性 `printf` 写进同一个文件，不存在「目录已建、元数据未写完」
     *    的半成品锁（那正是 A35 引入临时目录要解决的问题，现在结构上消失了）。
     *
     * ## 陈旧锁回收
     *
     * 判定为陈旧后先 `mv` 到唯一 quarantine 名（原子改名，摘掉 `lock` 这个名字），
     * 再核对隔离出来的那把锁内容是否就是我们判定为陈旧的那一把：
     * - 一致 → 才是别人的陈旧残留，`rm` 后继续竞争；
     * - 不一致 → 说明在我们读与改名之间持有者释放并有新的持有者落锁，
     *   此时**不删除**（不能删他方活锁），返回 [ObbLockExit.UNKNOWN]，
     *   残留的 quarantine 文件不影响后续获取（只有 `lock` 这个名字参与竞争）。
     *
     * 无法读取元数据时同样返回 UNKNOWN（fail-closed）：宁可拒绝一次安装，
     * 也不把「状态不明」当成「可以抢占」。
     *
     * 锁内容为单行：`token|pid|startTicks|createdEpoch`
     */
    private fun acquireObbLock(lockPath: String, token: String): Pair<Int, String> {
        val startTicks = processStartTicks() ?: return ObbLockExit.UNKNOWN to "无法读取当前进程启动时间"
        val meta = RootService.escapeShellArg("$token|${Process.myPid()}|$startTicks")
        val quarantine = RootService.escapeShellArg("$lockPath.shso.quarantine.${UUID.randomUUID()}")
        val script = buildObbLockAcquireScript(
            lock = RootService.escapeShellArg(lockPath),
            meta = meta,
            quarantine = quarantine
        )
        return RootService.runCommandSync(script, INSTALL_TIMEOUT_MS)
    }

    /**
     * 生成 OBB 事务锁的获取脚本。抽成独立函数是为了能在 JVM 单测里对**生成的脚本文本**
     * 做契约断言 —— 这段判定链全在 shell 里，一旦少了某个 fail-closed 分支，
     * 单元测试无法察觉，只能等真机复现。
     *
     * 参数必须已完成 [RootService.escapeShellArg] 转义。
     */
    internal fun buildObbLockAcquireScript(lock: String, meta: String, quarantine: String): String = buildString {
        // 持锁者存活：拒绝（可退避重试）
        append("if [ -e $lock ] || [ -L $lock ]; then ")
        append("old=\$(cat $lock 2>/dev/null || true); ")
        append("now=\$(date +%s 2>/dev/null || true); ")
        append("oldPid=\$(echo \"\$old\" | cut -d'|' -f2); ")
        append("oldStart=\$(echo \"\$old\" | cut -d'|' -f3); ")
        append("oldCreated=\$(echo \"\$old\" | cut -d'|' -f4); ")
        // 元数据不完整或**非纯数字** = 状态不明：fail-closed。
        // 数字校验是必需的，不只是防御性写法：`cut` 对「无分隔符」的行会整行返回，
        // 于是垃圾锁文件的三个字段都变成同一串非数字文本；直接拿去和 now 做
        // `[ a -ge b ]` 整数比较，mksh 会报 "unexpected operator" 并返回非零，
        // 整条判定链就顺势滑到「陈旧锁回收」分支 —— 状态不明被误判成可抢占。
        // 真机（PACM00 / Android 10，mksh）已实测复现该路径。
        append("for _f in \"\$oldPid\" \"\$oldStart\" \"\$oldCreated\" \"\$now\"; do ")
        append("case \"\$_f\" in ''|*[!0-9]*) exit ${ObbLockExit.UNKNOWN};; esac; done; ")
        // pid + 进程启动时间双校验：pid 会被系统复用，只比存活会误判为活锁。
        // liveStart 为空/非数字表示进程已不存在（/proc 读不到），此时按「非活锁」继续走陈旧判定。
        append("liveStart=\$(cat /proc/\$oldPid/stat 2>/dev/null | awk '{print \$22}'); ")
        append("case \"\$liveStart\" in ''|*[!0-9]*) ;; *) if [ \"\$liveStart\" = \"\$oldStart\" ]; then exit ${ObbLockExit.BUSY}; fi;; esac; ")
        append("if [ \"\$oldCreated\" -ge \"\$now\" ] || [ \"\$now\" - \"\$oldCreated\" -lt $OBB_LOCK_TTL_SECONDS ]; then exit ${ObbLockExit.BUSY}; fi; ")
        // 陈旧：原子改名摘掉锁名，再核对内容确实是刚判定的那把
        append("if mv $lock $quarantine 2>/dev/null; then ")
        append("got=\$(cat $quarantine 2>/dev/null || true); ")
        append("if [ -z \"\$got\" ] || [ \"\$got\" != \"\$old\" ]; then exit ${ObbLockExit.UNKNOWN}; fi; ")
        append("rm -f -- $quarantine; ")
        append("else exit ${ObbLockExit.BUSY}; fi; ")
        // 真 CAS：O_EXCL 创建，只有一方能成功。这是整个协议的互斥点，
        // 绝不能替换成「先删后建」或「mv 临时文件到位」—— 后者在目标已存在时
        // 会把临时目录移进目标内部并返回 0，导致并发双方都认为持锁。
        append("if ( set -C; printf '%s' $meta > $lock ) 2>/dev/null; then exit 0; fi; ")
        // 走到这里说明 lock 又被别人占了（我们刚隔离掉的窗口内有人抢先）
        append("exit ${ObbLockExit.BUSY}")
    }

    private fun processStartTicks(): String? = runCatching {
        val stat = File("/proc/${Process.myPid()}/stat").readText()
        val endComm = stat.lastIndexOf(")")
        if (endComm < 0) return null
        stat.substring(endComm + 1).trim().split(Regex("\\s+"))[19]
    }.getOrNull()?.takeIf { it.all(Char::isDigit) }

    /**
     * 释放锁：只有内容仍是自己那把 token 才删。
     *
     * 校验的是**整行内容**而非某个字段：整行相等意味着持有者仍是本任务，
     * 不会出现「token 字段被复用/串行写」导致的误删。
     */
    private fun releaseObbLock(lockPath: String?, token: String?) {
        if (lockPath == null || token == null) return
        val lock = RootService.escapeShellArg(lockPath)
        val tokenArg = RootService.escapeShellArg(token)
        RootService.runCommandSync(
            "case \"\$(cat $lock 2>/dev/null)\" in $tokenArg\\|*) rm -f -- $lock;; esac",
            INSTALL_TIMEOUT_MS
        )
    }

    /**
     * 回滚本次落位的 OBB：锁仍属本任务 **且** 目标文件身份与落位时记录的一致才删。
     *
     * 双重校验缺一不可：锁校验防「事务已交给别人」，身份校验防「目标已被外部替换」。
     * 身份不可解析（readObbIdentity 返回 null）时该条目根本不会进入回滚表，
     * 从源头上避免「拿不到身份就照删」。
     */
    private fun removeOwnedObb(targetPath: String, lockPath: String?, token: String) {
        if (lockPath == null) return
        val separator = targetPath.indexOf('|')
        if (separator <= 0) return
        val path = targetPath.substring(0, separator)
        val identity = targetPath.substring(separator + 1)
        val target = RootService.escapeShellArg(path)
        val lock = RootService.escapeShellArg(lockPath)
        val tokenArg = RootService.escapeShellArg(token)
        val expected = RootService.escapeShellArg(identity)
        RootService.runCommandSync(
            "if [ \"\$(cat $lock 2>/dev/null | cut -d'|' -f1)\" = $tokenArg ] && " +
                "[ \"\$(stat -c '%i:%s:%y' $target 2>/dev/null)\" = $expected ]; " +
                "then rm -f -- $target; fi",
            INSTALL_TIMEOUT_MS
        )
    }

    //  安装套件识别（基础包 + 分包）
    //
    //  为什么需要：单文件 `pm install` 对分包应用必定失败（INSTALL_FAILED_MISSING_SPLIT）。
    //  分包安装必须走 `pm install-create/-write/-commit` 会话流，且**分片要先拷到
    //  /data/local/tmp** —— `install-write` 直接读 /storage 会被 SELinux 拒绝
    //  （avc denied sdcardfs，system_server 无权读 emulated 存储）。

    /** 一个「安装套件」：基础包 + 其分包（单包应用 splits 为空）。 */
    internal data class ApkSet(val base: String, val splits: List<String>) {
        val all: List<String> get() = listOf(base) + splits
        val isSplit: Boolean get() = splits.isNotEmpty()

        /**
         * 写入会话的顺序：**基础包必须排在第一位**。
         *
         * AOSP 侧并不要求顺序（`PackageInstallerSession.validateApkInstallLocked` 对
         * 包名/版本/签名做的是与顺序无关的一致性断言），但基础包先写有两个实际好处：
         * 一是 `pm install-create -p` 需要包名，基础包的 manifest 才是权威来源；
         * 二是真机实测（PACM00 / Android 10）基础包写入后才认得出 session 的包名，
         * 先写分包会让错误信息里缺少包名线索。
         */
        val orderedWrites: List<String> get() = all
    }

    /** 同目录扫描上限：避免在塞满 APK 的目录里付出无谓的 manifest 解析开销。 */
    private const val MAX_SIBLING_SCAN = 200
    private const val MAX_SPLITS = 64

    private val SPLIT_SUFFIX_REGEX = Regex("-split(\\d+)$", RegexOption.IGNORE_CASE)
    private val SPLIT_NAME_REGEX = Regex("^split[_.-].*", RegexOption.IGNORE_CASE)

    /**
     * 由文件名推导「套件前缀」：`X-123.APK` → `X-123`；`X-123-split2.APK` → `X-123`。
     * 非 `.apk` 后缀返回 null。纯函数，便于单测。
     */
    internal fun apkSetStem(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "")
        if (!ext.equals("apk", ignoreCase = true)) return null
        return SPLIT_SUFFIX_REGEX.replace(fileName.substringBeforeLast('.'), "")
    }

    /** 文件名是否形如分包：`...-split<数字>.apk`（shso 提取命名）。纯函数。 */
    internal fun isSplitName(fileName: String): Boolean =
        SPLIT_SUFFIX_REGEX.containsMatchIn(fileName.substringBeforeLast('.', fileName))

    /**
     * 按命名约定从目录内挑出一个安装套件。纯函数（只吃文件名），便于单测。
     *
     * 约定：基础包 `<名>-<版本>.APK`、分包 `<名>-<版本>-split<序号>.APK`。
     * **点基础包或点任意分包都能得到同一套件**。
     *
     * @return (基础包文件名, 分包文件名列表)；不构成套件时返回 null
     */
    internal fun nameBasedSet(tappedName: String, siblingNames: List<String>): Pair<String, List<String>>? {
        val stem = apkSetStem(tappedName)?.takeIf { it.isNotEmpty() } ?: return null
        fun isBase(n: String) = apkSetStem(n) == stem && !isSplitName(n)
        fun isSplit(n: String) = apkSetStem(n) == stem && isSplitName(n)

        val base = if (isBase(tappedName)) tappedName else siblingNames.firstOrNull { isBase(it) } ?: return null
        val splits = (siblingNames.filter { isSplit(it) } + if (isSplit(tappedName)) listOf(tappedName) else emptyList())
            .distinct()
            .filter { it != base }
            .sorted()
        return if (splits.isEmpty()) null else base to splits
    }

    /**
     * 收集 [apkPath] 所属的安装套件。
     *
     * 先按命名约定（覆盖 shso 自己提取出来的产物，**不需要读 manifest**，因此对
     * 应用不可读的目录同样有效）；命名不规范时再按 manifest 的「同包名 + 同版本号」
     * 聚合（覆盖 SAI / MT / xapk 解包出来的 `split_config.*.apk`）。
     * 任何不确定的情形都退回「单文件」，绝不猜测。
     */
    internal fun collectApkSet(context: Context, apkPath: String): ApkSet {
        val tapped = File(apkPath)
        val fallback = ApkSet(apkPath, emptyList())
        val dir = tapped.parentFile ?: return fallback
        val siblings = runCatching {
            dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
        }.getOrNull()?.toList() ?: return fallback
        if (siblings.size < 2) return fallback

        val byName = siblings.associateBy { it.name }

        // A) 命名约定
        nameBasedSet(tapped.name, siblings.map { it.name }.take(MAX_SIBLING_SCAN))?.let { (baseName, splitNames) ->
            val base = byName[baseName]
            // 同样受 MAX_SPLITS 约束：命名前缀匹配会把同目录下所有 `<名>-<版本>-splitN`
            // 收进来，而每个都会 cp 一份到 /data/local/tmp，不设上限时一个几百个 APK 的
            // 目录能把 /data 写满。
            val splits = splitNames.take(MAX_SPLITS).mapNotNull { byName[it] }
            if (base != null && splits.isNotEmpty()) {
                return ApkSet(base.absolutePath, splits.map { it.absolutePath })
            }
        }

        // B) manifest 分组
        return manifestBasedSet(context, tapped, siblings) ?: fallback
    }

    private fun manifestBasedSet(context: Context, tapped: File, siblings: List<File>): ApkSet? {
        val pm = context.packageManager
        val tappedInfo = archiveInfo(pm, tapped) ?: return null
        val pkg = tappedInfo.packageName.takeIf { it.isNotBlank() } ?: return null
        val version = longVersionCode(tappedInfo)

        val group = ArrayList<Pair<File, android.content.pm.PackageInfo>>()
        group += tapped to tappedInfo
        for (f in siblings) {
            if (f.absolutePath == tapped.absolutePath) continue
            if (group.size > MAX_SPLITS) break
            val info = archiveInfo(pm, f) ?: continue
            if (info.packageName != pkg || longVersionCode(info) != version) continue
            group += f to info
        }
        if (group.size < 2) return null

        // 基础包必须能唯一定位：manifest 的 splitNames 为空且名字不像分包
        val bases = group.filter { (f, info) -> !isSplitArchive(f, info) }
        if (bases.size != 1) return null
        val base = bases.single().first
        val splits = group.filter { it.first.absolutePath != base.absolutePath }
            .map { it.first.absolutePath }
            .sorted()
        return if (splits.isEmpty()) null else ApkSet(base.absolutePath, splits)
    }

    /**
     * 把基础包排到首位，其余保持原顺序。
     *
     * XAPK 的条目顺序由打包者决定，`base.apk` 未必在最前。会话流需要基础包在首位：
     * 一是 `pm install-create -p` 的包名取自基础包 manifest；
     * 二是 `MODE_FULL_INSTALL` 漏写基础包会在 commit 时报
     * `INSTALL_FAILED_INVALID_APK: Full install must include a base package`。
     *
     * 判不出基础包时（多个候选或全都不是）保持原顺序交由 AOSP 的一致性断言裁决，
     * 不做猜测。
     */
    internal fun orderBaseFirst(context: Context, apks: List<File>): List<File> {
        if (apks.size <= 1) return apks
        val bases = apks.filter { apk ->
            val info = archiveInfo(context.packageManager, apk) ?: return@filter false
            !isSplitArchive(apk, info)
        }
        if (bases.size != 1) return apks
        val base = bases.single()
        return listOf(base) + apks.filter { it.absolutePath != base.absolutePath }
    }

    private fun isSplitArchive(f: File, info: android.content.pm.PackageInfo): Boolean {
        if (!info.applicationInfo?.splitNames.isNullOrEmpty()) return true
        return isSplitName(f.name) || SPLIT_NAME_REGEX.containsMatchIn(f.name)
    }

    /** 解析 APK 归档的 manifest；不可读 / 非 APK 时返回 null。 */
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: android.content.pm.PackageManager, f: File): android.content.pm.PackageInfo? =
        runCatching { pm.getPackageArchiveInfo(f.absolutePath, 0) }.getOrNull()

    private fun longVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    /**
     * 安装 split 分片 APK（pm 会话流）。
     *
     * ## `pm install-write` 的 SPLIT_NAME 契约（已按 AOSP 源码与真机双向核实）
     *
     * 参数形式是 `install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH|-]`，但
     * **SPLIT_NAME 不是 manifest 里的 split 名**。AOSP `PackageInstallerSession`：
     * - 写入期只做 `FileUtils.isValidExtFilename(name)` 校验（仅禁 `NUL` 与 `/`），
     *   随后 `new File(stageDir, name)` 原样落盘，**不解析 APK、不校验 split 名**；
     * - 真实 split 名在 `commit` 时才由 `PackageParser.parseApkLite` 逐个读出，
     *   据此把草稿文件统一改名为 `base.apk` / `split_<manifestSplitName>.apk`。
     *
     * 真机（PACM00 / Android 10）实测与之一致：名称传 `split0`、`base.apk`、包名
     * 均可成功写入；传绝对路径 `/data/local/tmp/x.apk` 报
     * `IllegalArgumentException: Invalid name`（因为含 `/`）。
     *
     * 因此这里的命名策略是：**基础包用 `base.apk`、分包用与清单 split 名一致的
     * `<splitName>.apk`**，让草稿名直接等于 commit 后的规范名，改名步骤可省，
     * 也顺带规避「草稿名与规范名撞车导致两个文件塌缩成一个」的坑。
     *
     * ## 基础包必须存在
     *
     * `pm install-create` 建的是 `MODE_FULL_INSTALL` 会话，漏写基础包在 commit 时报
     * `INSTALL_FAILED_INVALID_APK: Full install must include a base package`。
     * 因此 [apkPaths] 必须首元素为基础包（见 [ApkSet.orderedWrites]）。
     */
    private suspend fun installSplitApks(context: Context, apkPaths: List<String>): InstallResult =
        withContext(Dispatchers.IO) {
            if (apkPaths.isEmpty()) return@withContext InstallResult.Failure("没有可安装的 APK")

            val basePath = apkPaths[0]
            val baseInfo = archiveInfo(context.packageManager, File(basePath))
            val basePkg = baseInfo?.packageName?.takeIf { it.isNotBlank() }
            if (apkPaths.size > 1 && basePkg == null) {
                // 基础包读不出包名：不带 -p 继续（commit 阶段仍会做一致性断言），
                // 但要在日志与文档口径上明确「未锁定包名」。
                Log.w(TAG, "install-create 未锁定包名：基础包 manifest 解析失败 ${basePath}")
            }
            installSplitApksInternal(context, apkPaths, basePkg)
        }

    private suspend fun installSplitApksInternal(
        context: Context,
        apkPaths: List<String>,
        expectedPackageName: String?
    ): InstallResult = withContext(Dispatchers.IO) {
        // 1. 拷贝所有分片到 /data/local/tmp（sdcard 直读可能受限）
        val token = UUID.randomUUID().toString()
        val tmpFiles = mutableListOf<String>()
        var sessionId: String? = null
        var committed = false
        try {
            for ((i, path) in apkPaths.withIndex()) {
                val tmp = "$TMP_DIR/_shso_split_${token}_$i.apk"
                val copyCmd = "cp ${RootService.escapeShellArg(path)} ${RootService.escapeShellArg(tmp)}"
                val (code, out) = RootService.runCommandSync(copyCmd, INSTALL_TIMEOUT_MS)
                if (code != 0) {
                    return@withContext InstallResult.Failure("复制分片 $i 失败: $out")
                }
                tmpFiles.add(tmp)
            }

            // 2. 计算总大小并创建会话。带 -p 锁包名：可以在 commit 之前就把
            // 「装错套件」这类错误暴露出来，错误信息里也带包名。
            val totalSize = apkPaths.sumOf { File(it).length() }
            val pkgArg = expectedPackageName?.let { " -p ${RootService.escapeShellArg(it)}" } ?: ""
            val createCmd = "pm install-create -r -d -S $totalSize$pkgArg"
            val (createCode, createOut) = RootService.runCommandSync(createCmd, INSTALL_TIMEOUT_MS)
            if (createCode != 0) {
                return@withContext InstallResult.Failure("创建安装会话失败: ${createOut.trim()}")
            }

            // 会话 id 形如 "Success: created install session [123456789]"
            val session = SESSION_ID_REGEX.find(createOut)?.groupValues?.get(1)
                ?: return@withContext InstallResult.Failure("无法解析安装会话 ID: ${createOut.trim()}")
            sessionId = session

            // 3. 写入分片。草稿名 = commit 后的规范名：基础包 base.apk、分包 split_<splitName>.apk。
            val draftNames = draftNamesFor(apkPaths, splitNamesOf(context, apkPaths))
            for ((i, tmp) in tmpFiles.withIndex()) {
                val size = File(tmp).length()
                val draft = draftNames[i]
                val writeCmd = "pm install-write -S $size $session ${RootService.escapeShellArg(draft)} ${RootService.escapeShellArg(tmp)}"
                val (writeCode, writeOut) = RootService.runCommandSync(writeCmd, INSTALL_TIMEOUT_MS)
                if (writeCode != 0) {
                    return@withContext InstallResult.Failure("写入分片 $i（$draft）失败: ${writeOut.trim()}")
                }
            }

            // 4. 提交
            val commitCmd = "pm install-commit $session"
            val (commitCode, commitOut) = RootService.runCommandSync(commitCmd, INSTALL_TIMEOUT_MS)
            if (commitCode != 0 || (!commitOut.contains("Success") && !commitOut.contains("success"))) {
                return@withContext InstallResult.Failure("提交安装失败: ${commitOut.trim().ifEmpty { "未知错误" }}")
            }

            committed = true
            InstallResult.Success("安装成功")
        } finally {
            if (!committed) sessionId?.let { RootService.runCommandSync("pm install-abandon $it", INSTALL_TIMEOUT_MS) }
            // 清理临时分片
            val cleanupCmd = tmpFiles.joinToString(";") { "rm -f ${RootService.escapeShellArg(it)}" }
            if (cleanupCmd.isNotBlank()) {
                RootService.runCommandSync(cleanupCmd, INSTALL_TIMEOUT_MS)
            }
        }
    }

    /**
     * 为会话内的每个 APK 生成**草稿名**（写入期用的临时名）。
     *
     * 基础包固定 `base.apk`，分包用 `split_<manifest split 名>.apk` —— 与 AOSP
     * `validateApkInstallLocked` 里的规范名一致，commit 时无需改名。
     *
     * 硬约束：**草稿名必须两两不同**。`PackageInstallerSession.doWriteInternal` 用
     * `Os.open(..., O_CREAT|O_WRONLY, 0644)` 落盘，**没有 O_EXCL**，同名两次写入会
     * 静默覆盖成同一个文件，表现为「装上了但少一个分片」或直接解析失败。
     * split 名取不到或重复时按序号退化命名。
     */
    internal fun draftNamesFor(apkPaths: List<String>, splitNames: List<String>): List<String> {
        if (apkPaths.isEmpty()) return emptyList()
        val result = ArrayList<String>(apkPaths.size)
        result += BASE_DRAFT_NAME
        for (i in 1 until apkPaths.size) {
            val declared = splitNames.getOrNull(i - 1)
            val name = if (!declared.isNullOrBlank() && DRAFT_SAFE_REGEX.matches(declared)) {
                "split_$declared.apk"
            } else {
                "shso_split_$i.apk"
            }
            var candidate = name
            var suffix = 2
            while (result.contains(candidate)) {
                candidate = "shso_split_${i}_$suffix.apk"
                suffix++
            }
            result += candidate
        }
        return result
    }

    /**
     * 解析各分包的 manifest split 名。
     *
     * `getPackageArchiveInfo` 会填充 `applicationInfo.splitNames`（基础包为 null/空）。
     * 解析失败或集合大小与分片数不符时返回空列表，交由 [draftNamesFor] 退化为序号命名
     * ——写入期本就不校验该名字，序号命名同样正确。
     */
    private fun splitNamesOf(context: Context, apkPaths: List<String>): List<String> {
        if (apkPaths.size <= 1) return emptyList()
        val names = apkPaths.drop(1).map { apk ->
            runCatching {
                archiveInfo(context.packageManager, File(apk))?.applicationInfo?.splitNames
            }.getOrNull()?.firstOrNull().orEmpty()
        }
        return if (names.all { it.isNotBlank() }) names else emptyList()
    }

    private const val BASE_DRAFT_NAME = "base.apk"

    /** [copyObbAtomically] 的「目标已存在」退出码，调用方据此走幂等/冲突分支。 */
    private const val OBB_EXIT_ALREADY_EXISTS = 18

    /** split 名必须是合法文件名（无 `/`、无 NUL），否则不能用作草稿名。 */
    private val DRAFT_SAFE_REGEX = Regex("[A-Za-z0-9._+\\-]{1,200}")

    private val SESSION_ID_REGEX = Regex("\\[(\\d+)]")

    private fun readBoundedUtf8(input: java.io.InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalStateException("XAPK manifest.json 过大")
            out.write(buffer, 0, count)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun copyXapkEntry(
        zip: ZipFile,
        header: net.lingala.zip4j.model.FileHeader,
        dest: File,
        currentTotal: Long
    ): Long {
        val declared = header.uncompressedSize
        if (declared > MAX_XAPK_ENTRY_BYTES) throw IllegalStateException("XAPK 单个条目过大")
        var total = currentTotal
        zip.getInputStream(header).use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_XAPK_BYTES) throw IllegalStateException("XAPK 解压总大小超限")
                    output.write(buffer, 0, count)
                }
            }
        }
        return total
    }

    /**
     * 非 ROOT 安装：调用系统包安装器（ACTION_VIEW + FileProvider 内容 URI）。
     *
     * 这是「普通用户本就能安装 APK」的标准路径，不再强制依赖 ROOT：
     * 仅当授权 ROOT 时才由 [installApk] 走静默安装；无 ROOT 时回退到本方法，
     * 由系统安装器完成交互式安装。适用于普通用户可读取的 APK（如 /sdcard 下）；
     * 受保护路径（/data/adb 等）非 ROOT 不可读时会失败并给出明确提示。
     */
    fun installApkViaSystem(context: Context, apkPath: String): InstallResult {
        val file = File(apkPath)
        if (!file.exists()) {
            Log.e(TAG, "APK 文件不存在: $apkPath")
            return InstallResult.Failure("APK 文件不存在: $apkPath")
        }

        // Android 8+ 要求声明并动态授权 REQUEST_INSTALL_PACKAGES。
        // 未授权时系统安装器会直接 finish，表现为“点了安装但没有任何反应”。
        // minSdk 26 即 Android 8，该判断恒真，无需保留版本分支。
        val canInstall = context.packageManager.canRequestPackageInstalls()
        Log.i(TAG, "canRequestPackageInstalls=$canInstall, path=$apkPath")
        if (!canInstall) {
            return try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:${context.packageName}".toUri()
                    addFlags(FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "已跳转设置页请求 REQUEST_INSTALL_PACKAGES")
                InstallResult.Failure("需要允许 shso 安装未知来源应用，请先在设置中开启后再试")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "跳转设置页失败", e)
                InstallResult.Failure("需要允许 shso 安装未知来源应用: ${e.message}")
            }
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "已启动系统安装器, uri=$uri")
            InstallResult.Success("已调用系统安装器，请在弹出的界面完成安装")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "启动系统安装器失败", e)
            InstallResult.Failure("启动系统安装器失败: ${e.message}")
        }
    }

    private const val TAG = "ApkInstaller"
}
