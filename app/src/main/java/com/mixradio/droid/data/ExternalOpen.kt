// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * 列表取首个元素（与当前单文件限制一致）。
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
     * 外部 Intent 落地路径的白名单。
     *
     * `file://` URI **不携带任何授权** —— 发送方只是断言了一个路径字符串。
     * 而 shso 是 ROOT 工具：拿到路径后 `FilePage` 会用 `stat -L` / `find`（root 通道）
     * 打开它，文本类还会直接进编辑器渲染。于是任意应用可以构造
     * `file:///data/data/com.mixradio.droid/shared_prefs/xxx.xml` 让 shso
     * **以 root 身份读出并显示本进程乃至其它应用的私有数据**（令牌常在 prefs 里），
     * 全程无需用户真正选中过那个文件。
     *
     * 为什么 `file://` 与 `content://` 走同一份白名单：`content://` 并不因为「有读权限授予」
     * 就更可信 —— 被授予的是 URI 本身，而解析出的落盘路径是**对方的 provider 写进游标的字符串**，
     * 任何应用都能自建 provider 声称自己的文件在 `/data` 下任意位置。下游会用 root 去 stat /
     * 读取 / 执行该路径，所以只能按路径本身判定。现代文件管理器都用 FileProvider 分享，
     * 它们的真实路径都在共享存储内，卡掉这些路径不影响正常分享。
     */
    @Suppress("SdCardPath")
    private val EXTERNAL_FILE_URI_ALLOWED_PREFIXES = listOf(
        INTERNAL_STORAGE_PATH,   // /storage/emulated/0（normalizeSdCardAlias 已把 /sdcard 归一到这里）
        "/storage/self/primary",
        "/data/local/tmp"
    )

    /**
     * 本进程私有目录：无论 URI 来自哪里都不得由外部 Intent 打开（无任何合法分享场景）。
     *
     * 这里是**故意的绝对路径字面量**（`SdCardPath` lint 命中同款）：安全白名单必须写死
     * 真实路径前缀，用 `Environment.getExternalStorageDirectory()` 之类的相对推导反而会
     * 让白名单随环境漂移。与本文件既有的 `/sdcard` 归一常量同性质。
     */
    @Suppress("SdCardPath")
    private val APP_PRIVATE_PREFIXES = listOf(
        "/data/data/com.mixradio.droid",
        "/data/user/0/com.mixradio.droid",
        "/data/user_de/0/com.mixradio.droid"
    )

    /**
     * 判断一个**外部来源**解析出的路径是否允许被 shso 直接使用。
     * 纯函数，便于 JVM 单测。
     *
     * 必须先做词法归一化再比对前缀，否则白名单形同虚设：`Uri` 不会归一化 `..`，
     * 任意应用都能构造 `file:///storage/emulated/0/../../data/adb/modules/x/service.sh`，
     * 它以 `/storage/emulated/0/` 开头 → 直接命中允许前缀 → 返回 true，而实际指向
     * `/data/adb/modules`。同理本应用私有目录也能用 `..` 绕开前缀黑名单。
     *
     * 本函数的契约是「这条路径已经可以放心使用」，因此任何无法判定的情况都必须
     * 返回 false，不能指望下游再兜一道 —— 下游是否过滤 `..` 属于实现细节，
     * 换个调用方（安装/执行分派，或不经 `isUnsafePath` 的读取）就不再成立。
     */
    fun isExternalPathAllowed(
        path: String,
        allowedPrefixes: List<String> = EXTERNAL_FILE_URI_ALLOWED_PREFIXES,
        appPrivatePrefixes: List<String> = APP_PRIVATE_PREFIXES
    ): Boolean {
        val p = normalizeForContainment(path) ?: return false
        if (p.isEmpty()) return false
        // 两组前缀都是模块级常量，归一化是纯词法函数、无外部依赖，
        // 因此每次调用都在重复计算恒定的结果：一次 isExternalPathAllowed 至少跑 6 次
        // normalizeForContainment，而 resolveToRealPath 一次唤起最多调它 4 次
        // → 单次唤起 24 次重复的 split/join。这里按列表身份缓存归一化结果。
        val normalizedPrivate = normalizedPrefixes(appPrivatePrefixes)
        for (base in normalizedPrivate) {
            if (p == base || p.startsWith("$base/")) return false
        }
        val normalizedAllowed = normalizedPrefixes(allowedPrefixes)
        for (base in normalizedAllowed) {
            if (p == base || p.startsWith("$base/")) return true
        }
        return false
    }

    /**
     * 归一化后的前缀列表，按**列表相等性**缓存。
     *
     * 调用方两次传入的都是同一份模块级常量（`==` 成立），
     * 因此命中率接近 100%；万一传入动态列表也只是退化为重新归一化，语义不变。
     */
    private val normalizedPrefixCache = HashMap<List<String>, List<String>>()

    private fun normalizedPrefixes(prefixes: List<String>): List<String> =
        synchronized(normalizedPrefixCache) {
            normalizedPrefixCache.getOrPut(prefixes) {
                prefixes.mapNotNull { normalizeForContainment(it) }
            }
        }

    /**
     * 词法归一化：解析 `.` 与 `..`、折叠重复斜杠、去尾部斜杠。
     *
     * 只做词法、不做 `canonicalPath`（后者会 stat 每个路径，交互式唤起无法承受），
     * 但足以消除 `..` 穿越与前缀混淆。解析不出合法绝对路径时返回 null（fail-closed）。
     */
    internal fun normalizeForContainment(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null
        // 出现 NUL 或换行直接判否：这两者无法出现在合法路径里，只可能来自构造
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return null
        val out = ArrayList<String>()
        for (seg in trimmed.split('/')) {
            when (seg) {
                "", "." -> {}
                // 根之上的 `..`（如 `/../x`）按 POSIX 语义仍落在根，保留 x；
                // 但为了让「路径里有无意义穿越」也被拦下，这里选择整体判否。
                ".." -> return null
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    /**
     * 尝试把 URI 解析成真实文件路径；解析不出返回 null（调用方改用拷贝）。
     * 纯读取，无副作用。
     *
     * 信任边界：`_data` 由**对方的 provider** 提供，与系统授予的读权限无关，
     * 不可全信。因此 `file://` 与 `content://` 一律过 [isExternalPathAllowed]。
     */
    fun resolveToRealPath(context: Context, uri: Uri): String? {
        val isFileScheme = uri.scheme?.lowercase(Locale.ROOT) == "file"
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> {
                val path = uri.path?.let { normalizeSdCardAlias(it) } ?: return null
                return path.takeIf { isExternalPathAllowed(it) }
            }
            "content" -> Unit
            else -> return null
        }
        // 1) 标准 DocumentProvider：直接解 documentId，无需查询
        val authority = uri.authority.orEmpty()
        val documentId = uri.lastPathSegment
        if (authority == "com.android.externalstorage.documents") {
            decodeExternalStorageDocumentId(documentId)?.let { candidate ->
                if (isExternalPathAllowed(candidate)) return candidate
            }
        }
        if (authority == "com.android.providers.downloads.documents") {
            decodeRawDownloadDocumentId(documentId)?.let { candidate ->
                if (isExternalPathAllowed(candidate)) return candidate
            }
        }
        // 2) 其余（media / downloads 数字 ID / 带 _data 的 provider）：查询 DATA 列
        val fromProvider = queryDataColumn(context, uri) ?: return null
        // provider 来源同样只认白名单。
        //
        // `_data` 是**对方的 provider 写进游标的字符串**，不是系统代为解析的事实：
        // 读权限授予的是那个 URI（authority+path），与 provider 声称的落盘位置毫无关系。
        // 自建 provider 完全可以对任意 URI 返回 `/data/data/<别人>/files/x` 或
        // `/data/adb/modules/x/service.sh`，且这条链路下游会用 root 去 stat / 读取 / 执行
        // （EXECUTE 只需用户在弹窗点一次）。因此这里必须与 `file://` 同等对待，
        // 只放行共享存储与本地临时目录 —— 一切 `/data/data`、`/data/user`、
        // `/data/adb` 下的路径自然落在白名单之外。合法分享不受影响：FileProvider /
        // MediaStore / Downloads 的真实路径本就在共享存储内。
        return fromProvider.takeIf { isExternalPathAllowed(it) }
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
            // 显式判空而不是用 `?.use{} ?: return null`：
            // `return null` 是**非局部返回**，会直接跳出函数、绕过下面两个 catch，
            // 于是 reserveUniqueFile 预占出来的 0 字节占位文件永远留在收件箱里。
            // 反复分享同一个打不开的 URI 就能把收件箱槽位（MAX_INBOX_FILES）全部占满，
            // 之后任何分享都被 `existing.size >= MAX_INBOX_FILES` 拒绝。
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开传入的文件")
            input.use { stream ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var checkedBytes = existingBytes
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > COPY_LIMIT_BYTES) throw IllegalStateException("文件超过 ${COPY_LIMIT_BYTES / 1024 / 1024}MB 上限")
                        // 总容量必须**边写边判**：`declaredSize` 来自 Provider，可能是 -1（未知）
                        // 或故意偏小。只在拷贝前检查一次的话，声明为「未知/很小」的大文件
                        // 就能把收件箱撑到远超 MAX_INBOX_BYTES。
                        checkedBytes += read
                        if (checkedBytes > MAX_INBOX_BYTES) {
                            throw IllegalStateException("收件箱总容量超过 ${MAX_INBOX_BYTES / 1024 / 1024 / 1024}GB 上限")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            target
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { target.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { target.delete() }
            null
        }
    }

    /**
     * 原子预占目标文件名（`createNewFile`），避免并发分享同名文件互相覆盖。
     *
     * 重名重试上界独立于 [MAX_INBOX_FILES]（那是「文件总数」上限），
     * 两者语义不同；此前复用同一个常量，在极端情况下会把「重名重试耗尽」
     * 误报成「收件箱已满」。
     */
    private fun reserveUniqueFile(dir: File, name: String): File? {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 0
        while (index < MAX_NAME_PROBE) {
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

    /** 单个收件箱内同名文件的重名探测上限。 */
    private const val MAX_NAME_PROBE = 1000

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
