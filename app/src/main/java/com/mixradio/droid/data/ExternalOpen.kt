// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 从其他应用唤起 shso（「打开方式」/「分享」）后的目标处理。
 *
 * 设计要点：
 * - 收到的是 `content://`（API 24+ 跨应用传 `file://` 会抛 `FileUriExposedException`），
 *   而 shso 的安装 / 编辑 / 解压 / 执行全部要求真实文件路径，故先把 URI 解析成路径。
 * - 解析分层：能解出真实路径就地使用；不透明 FileProvider（如 QQ）流式拷贝到收件箱后按真实路径处理。
 * - 具体 app 名（QQ/微信）不可判断，唯一可依赖的事实是接收到的 `Uri`。
 */
enum class ExternalMode { OPEN, LOCATE }

/**
 * 唤起后待处理的目标，经 [ExternalOpenHub] 从 MainActivity 投递到 FilePage。
 *
 * 动作由 FilePage 依 [FileItem] 现有的 `isInstallable` / `isSupportedExecutable` /
 * `isViewableImage` / `isEditableText` / `isArchive` 判定，与文件页动作菜单同源；
 * 这些谓词**只看扩展名**，对无扩展名文件无法区分类型（如相册分享的临时图片），
 * 故额外携带 [mimeType] 作兜底。
 */
data class PendingExternalOpen(
    val path: String,
    val mode: ExternalMode,
    /** 发送方声明的 MIME（可空）；扩展名无法判定时用它兜底。 */
    val mimeType: String?,
    /** 是否来自不透明 URI 的收件箱副本（用于提示用户实际位置与来源不同）。 */
    val copiedFromExternal: Boolean,
    /** 请求代数：旧 effect 取消收尾时不得清掉后来者。 */
    val token: Long
)

/** Activity 层解析出的原始请求；只含字符串，便于 JVM 单测（不触碰 Uri / Context）。 */
data class ExternalRequest(
    val uri: String,
    val mode: ExternalMode,
    val mimeType: String?
)

/**
 * 从 Intent 的原始字段提取外部唤起请求。仅字符串判定，不依赖 Android 运行时，可纯 JVM 单测。
 * URI 的采集（data / EXTRA_STREAM / clipData）在 Activity 层完成，这里只做动作与组件的判定。
 */
object ExternalRequestParser {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    private val SUPPORTED_ACTIONS = setOf(ACTION_VIEW, ACTION_SEND, ACTION_SEND_MULTIPLE)

    /** 是否命中两个 external alias（用于与普通 LAUNCHER 启动区分）。 */
    fun isExternalComponent(className: String?): Boolean =
        className?.endsWith("ExternalOpenActivity") == true ||
            className?.endsWith("ExternalLocateActivity") == true

    fun modeForComponent(className: String?): ExternalMode =
        if (className?.endsWith("ExternalLocateActivity") == true) ExternalMode.LOCATE else ExternalMode.OPEN

    /**
     * @param uriString 已由 Activity 层从 data / EXTRA_STREAM / clipData 采集到的目标 URI
     * @param mimeType  Intent 声明的 MIME（`Intent.type`），用于扩展名无法判定时兜底
     * @return 非受支持动作、非外部组件或缺少 URI 时返回 null
     */
    fun build(
        action: String?,
        componentClassName: String?,
        uriString: String?,
        mimeType: String? = null
    ): ExternalRequest? {
        if (action !in SUPPORTED_ACTIONS) return null
        val uri = uriString?.takeIf { it.isNotEmpty() } ?: return null
        return ExternalRequest(uri, modeForComponent(componentClassName), mimeType)
    }
}

/**
 * 把 `Intent.EXTRA_STREAM` 的值归一为 URI 字符串。
 *
 * 类型不统一：真实分享应用多传 `Uri`（Parcelable），`am start --eu` 等传 `String`，
 * 而 `SEND_MULTIPLE` 与部分实现传 `ArrayList<Uri>` —— 早期实现直接 `value.toString()`，
 * 对列表会得到 `[uri1, uri2]`（带方括号）导致解析失败、静默降级为主页。
 * 列表取首个元素（与当前单文件模型一致）。
 *
 * 顶层纯函数：不依赖 Activity，可 JVM 单测。
 */
internal fun streamExtraToUri(value: Any?): String? = when (value) {
    null -> null
    is Uri -> value.toString()
    // SEND_MULTIPLE 常见形态是 ArrayList（ParcelableArrayList），取首个
    is List<*> -> value.firstNotNullOfOrNull { streamExtraToUri(it) }
    // 也有实现传数组（如 putExtra(String, String[])），Kotlin 里数组不是 List，需单独处理
    is Array<*> -> value.firstNotNullOfOrNull { streamExtraToUri(it) }
    is CharSequence -> value.toString().takeIf { it.isNotEmpty() }
    else -> null
}

/** 跨层投递单槽位：MainActivity 写入，FilePage 消费后清空。 */
object ExternalOpenHub {
    /** 唯一 Compose 状态：进程内单槽位；FilePage 依 path 定位，无额外令牌。 */
    var pending by mutableStateOf<PendingExternalOpen?>(null)
        private set

    private var sequence = 0L

    fun post(path: String, mode: ExternalMode, mimeType: String?, copied: Boolean) {
        sequence += 1L
        pending = PendingExternalOpen(path, mode, mimeType, copied, sequence)
    }

    fun consume() {
        pending = null
    }

    /** 仅消费仍是本请求的槽位，防止取消中的旧 effect 清掉后来者。 */
    fun consume(request: PendingExternalOpen) {
        if (pending?.token == request.token) pending = null
    }
}

/**
 * 外部唤起（OPEN 模式）的动作。`BROWSE` 表示无法判定，退回定位 + 动作菜单。
 */
enum class ExternalAction { INSTALL, EXECUTE, VIEW_IMAGE, EDIT_TEXT, EXTRACT, BROWSE }

/** MIME → 动作；无法判定返回 null。发送方给的 MIME 可能不准，故只作兜底。 */
fun actionForMimeType(mimeType: String?): ExternalAction? = when {
    mimeType.isNullOrBlank() -> null
    mimeType.startsWith("image/", ignoreCase = true) -> ExternalAction.VIEW_IMAGE
    mimeType.startsWith("text/", ignoreCase = true) -> ExternalAction.EDIT_TEXT
    mimeType.equals("application/json", ignoreCase = true) || mimeType.endsWith("+json", ignoreCase = true) ->
        ExternalAction.EDIT_TEXT
    mimeType.equals("application/xml", ignoreCase = true) -> ExternalAction.EDIT_TEXT
    mimeType.equals("application/vnd.android.package-archive", ignoreCase = true) -> ExternalAction.INSTALL
    mimeType.contains("zip", ignoreCase = true) ||
        mimeType.contains("tar", ignoreCase = true) ||
        mimeType.contains("compress", ignoreCase = true) -> ExternalAction.EXTRACT
    else -> null
}

/**
 * 决定外部唤起后的动作（纯函数，便于单测）。
 *
 * **有扩展名**：完全按扩展名谓词（与文件页动作菜单同源，发送方的 MIME 可能不准）。
 * **无扩展名**：谓词无法区分类型（`isEditableText` 对无扩展名恒真），此时以 MIME 为准；
 * MIME 也无结论才退回「按文本」——沿用无扩展名 = 文本的既有语义。
 *
 * 存在的理由：相册 / 分享应用常给出**无扩展名**的临时文件。不像 MIME 分流的话，
 * 一张 `image/png` 无扩展名图片会走 `isEditableText` 被当文本打开（用户看到一堆乱码）。
 */
fun decideExternalAction(
    isExtensionless: Boolean,
    isInstallable: Boolean,
    isSupportedExecutable: Boolean,
    isViewableImage: Boolean,
    isEditableText: Boolean,
    isArchive: Boolean,
    mimeType: String?
): ExternalAction {
    if (isExtensionless) {
        // 无扩展名：安装 / 执行不受影响（谓词本就依赖扩展名，此处必为假），以 MIME 为准
        return actionForMimeType(mimeType) ?: ExternalAction.EDIT_TEXT
    }
    return when {
        isInstallable -> ExternalAction.INSTALL
        isSupportedExecutable -> ExternalAction.EXECUTE
        isViewableImage -> ExternalAction.VIEW_IMAGE
        isEditableText -> ExternalAction.EDIT_TEXT
        isArchive -> ExternalAction.EXTRACT
        // 扩展名未知：尽力用 MIME，仍无结论退回定位 + 动作菜单
        else -> actionForMimeType(mimeType) ?: ExternalAction.BROWSE
    }
}

object ExternalOpen {

    /** 不透明 URI 拷贝落盘的位置：用户可见、无 ROOT / SELinux 限制，文件页可直接浏览。 */
    const val INBOX_DIR = "$INTERNAL_STORAGE_PATH/Download/shso"

    /** 拷贝体积上限：共享型 FileProvider 无法预知大小，超限直接拒绝而不是 OOM。 */
    const val COPY_LIMIT_BYTES = 512L * 1024 * 1024
    private const val MAX_INBOX_FILES = 256
    private const val MAX_INBOX_BYTES = 2L * 1024 * 1024 * 1024

    sealed class Resolved {
        /** 直接拿到真实文件路径。 */
        data class Real(val path: String) : Resolved()

        /** 不透明 URI，已拷贝到收件箱，path 即副本路径。 */
        data class Copied(val path: String) : Resolved()

        data class Failed(val reason: String) : Resolved()
    }

    /**
     * 剥离目录分隔与控制字符，只保留文件名；`.1` 尾缀保留（腾讯系下载会追加，如 `qq.apk.1`）。
     * 与 `RootFileManager` 的路径过滤口径一致（`..`、`\`、NUL）。
     */
    fun sanitizeSharedName(raw: String?): String {
        val fallback = "shared_file"
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return fallback
        // 只取最后一段：QQ 等可能给出完整路径或带目录的 display name
        val base = input.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (ch in base) {
                when {
                    ch == '\u0000' -> {}
                    ch.code < 0x20 -> {}
                    ch == '\\' || ch == ':' || ch == '*' || ch == '?' -> append('_')
                    ch == '"' || ch == '<' || ch == '>' || ch == '|' -> append('_')
                    else -> append(ch)
                }
            }
        }.trim()
        // 纯 `.` / `..` 或净化后为空一律回落，避免把名字用成路径
        if (cleaned.isEmpty() || cleaned.all { it == '.' }) return fallback
        return cleaned
    }

    /**
     * 解 `com.android.externalstorage.documents` 的 documentId。
     * 形如 `primary:Download/x.apk` → `/storage/emulated/0/Download/x.apk`；
     * 非 primary 卷（SD 卡）返回 `/storage/<卷名>/…`。
     */
    fun decodeExternalStorageDocumentId(documentId: String?): String? {
        val id = documentId.orEmpty()
        if (id.isEmpty()) return null
        val sep = id.indexOf(':')
        if (sep < 0) return null
        val volume = id.substring(0, sep)
        val relative = id.substring(sep + 1)
        if (relative.isEmpty()) return null
        val root = if (volume.equals("primary", ignoreCase = true)) {
            INTERNAL_STORAGE_PATH
        } else {
            "/storage/$volume"
        }
        return "$root/$relative"
    }

    /**
     * 解 `com.android.providers.downloads.documents` 的 documentId：
     * `raw:/sdcard/Download/x.apk` 直接可用；纯数字 ID 需要 `_data` 查询（在 [resolveToRealPath] 内完成）。
     */
    fun decodeRawDownloadDocumentId(documentId: String?): String? {
        val id = documentId.orEmpty()
        return if (id.startsWith("raw:")) id.removePrefix("raw:").takeIf { it.isNotEmpty() } else null
    }

    /** 规范化 `file://` 路径中的 `/sdcard` 别名，统一到 `/storage/emulated/0`。 */
    fun normalizeSdCardAlias(path: String): String = when {
        path == "/sdcard" -> INTERNAL_STORAGE_PATH
        path.startsWith("/sdcard/") -> INTERNAL_STORAGE_PATH + path.removePrefix("/sdcard")
        else -> path
    }

    /**
     * 尝试把 URI 解析成真实文件路径；解析不出返回 null（调用方改用拷贝）。
     * 纯读取，无副作用。
     */
    fun resolveToRealPath(context: Context, uri: Uri): String? {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> return uri.path?.let { normalizeSdCardAlias(it) }
            "content" -> Unit
            else -> return null
        }
        // 1) 标准 DocumentProvider：直接解 documentId，无需查询
        val authority = uri.authority.orEmpty()
        val documentId = uri.lastPathSegment
        if (authority == "com.android.externalstorage.documents") {
            decodeExternalStorageDocumentId(documentId)?.let { return it }
        }
        if (authority == "com.android.providers.downloads.documents") {
            decodeRawDownloadDocumentId(documentId)?.let { return it }
        }
        // 2) 其余（media / downloads 数字 ID / 带 _data 的 provider）：查询 DATA 列
        return queryDataColumn(context, uri)
    }

    private fun queryDataColumn(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    }.getOrNull()

    /**
     * 读取 URI 展示名（优先 ContentResolver 的 DISPLAY_NAME，回退 URI 末段），并净化。
     */
    fun displayNameOf(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return sanitizeSharedName(fromProvider ?: uri.lastPathSegment)
    }

    /** URI 报告的大小；不可知时返回 -1。 */
    fun sizeOf(context: Context, uri: Uri): Long {
        val queried: Long? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
        }.getOrNull()
        return queried ?: -1L
    }

    /**
     * 流式拷贝到收件箱。读权限仅在接收 intent 后的短窗口有效，故必须在唤起时立即执行，
     * 不能延后到用户点确认时再读。同名自动追加 `_1`、`_2`…
     */
    private fun copyToInbox(context: Context, uri: Uri, displayName: String, declaredSize: Long): File? {
        if (declaredSize > COPY_LIMIT_BYTES) return null
        val dir = File(INBOX_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val existing = dir.listFiles() ?: emptyArray()
        if (existing.size >= MAX_INBOX_FILES) return null
        val existingBytes = existing.sumOf { if (it.isFile) it.length() else 0L }
        if (existingBytes >= MAX_INBOX_BYTES || (declaredSize > 0L && existingBytes + declaredSize > MAX_INBOX_BYTES)) return null
        val target = reserveUniqueFile(dir, displayName) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > COPY_LIMIT_BYTES) throw IllegalStateException("文件超过 ${COPY_LIMIT_BYTES / 1024 / 1024}MB 上限")
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return null
            target
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { target.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { target.delete() }
            null
        }
    }

    /** Atomically reserve a destination before opening the provider stream. */
    private fun reserveUniqueFile(dir: File, name: String): File? {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 0
        while (index < MAX_INBOX_FILES) {
            val candidate = File(dir, if (index == 0) name else "${stem}_$index$ext")
            try {
                if (candidate.createNewFile()) return candidate
            } catch (_: Exception) {
                return null
            }
            index++
        }
        return null
    }

    /** 在目录内构造不冲突的文件名：`x.apk` → `x_1.apk` → `x_2.apk`… */
    fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val next = File(dir, "${stem}_$index$ext")
            if (!next.exists()) return next
            index += 1
        }
    }

    /**
     * 解析外部 intent 的目标为可直接使用的真实路径。
     * 应在 IO 线程调用（拷贝是阻塞 IO）。
     */
    suspend fun resolve(context: Context, uri: Uri): Resolved = withContext(Dispatchers.IO) {
        resolveToRealPath(context, uri)?.let { path ->
            return@withContext Resolved.Real(path)
        }
        val displayName = displayNameOf(context, uri)
        val size = sizeOf(context, uri)
        if (size > COPY_LIMIT_BYTES) {
            return@withContext Resolved.Failed(
                "文件过大（${size / 1024 / 1024}MB），无法复制到 shso 收件箱"
            )
        }
        val copied = copyToInbox(context, uri, displayName, size)
            ?: return@withContext Resolved.Failed("无法读取该文件（可能未授予读取权限或复制失败）")
        Resolved.Copied(copied.absolutePath)
    }
}
