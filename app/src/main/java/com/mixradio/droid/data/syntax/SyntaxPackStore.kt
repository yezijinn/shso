// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mixradio.droid.data.syntax

import java.util.Locale
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 外置语法包：Monarch 语法 JSON（不含任何可执行代码）。
 *
 * - 语法文件：`filesDir/syntax/grammars/<id>.json`；元数据：`filesDir/syntax/index.tsv`。
 * - 导入来源：本地文件或 https URL（GitHub raw 属此类）。
 * - 强校验：体积上限、SHA-256（可选但建议）、JSON 结构（须含 `tokenizer`）。
 *   任一校验失败即整体拒绝，**不影响内置语法**（内置始终注册，外置解析失败自动回落）。
 *
 * 语法包以扩展名（= id）匹配文件；与内置同名时覆盖内置（用于修订内置语法）。
 */
data class SyntaxPack(
    val id: String,
    /** 适用的文件扩展名（小写，不含点）；为空时等同于 [id]。 */
    val exts: List<String>,
    /** 无扩展名的常见文件名（小写，如 `dockerfile`、`cmakelists.txt`）。 */
    val filenames: List<String>,
    val sha256: String,
    val source: String,
    val sizeBytes: Long,
    val addedAtMs: Long,
    val enabled: Boolean
) {
    /** Monarch 语言 id 即 scope 的尾段（`defaultScopeName()` → `source.<id>`）。 */
    val scope: String get() = "source.$id"
}

object SyntaxPackStore {

    /** 单文件体积上限：Monarch 语法远超此值即属异常。 */
    const val MAX_BYTES = 512 * 1024

    /** 压缩包（含全部语法）体积上限。 */
    const val MAX_ZIP_BYTES = 2 * 1024 * 1024

    /** 网络超时（连接与读取）。 */
    private const val TIMEOUT_MS = 15_000

    private const val DIR = "syntax"
    private const val INDEX = "index.tsv"

    /** zip 内的清单文件名（声明每个语法的适用扩展名）。 */
    private const val ZIP_INDEX = "index.json"

    /** 传给语法注册表的相对路径前缀（由 SoraMonarchGrammars 的应用目录解析器解析）。 */
    const val GRAMMAR_PATH_PREFIX = "syntax/grammars"

    private fun root(ctx: Context) = File(ctx.filesDir, DIR)

    fun grammarsDir(ctx: Context): File = File(root(ctx), "grammars")

    fun fileFor(ctx: Context, id: String): File = File(grammarsDir(ctx), "$id.json")

    private fun indexFile(ctx: Context) = File(root(ctx), INDEX)

    fun list(ctx: Context): List<SyntaxPack> {
        val f = indexFile(ctx)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val c = line.split('\t').map { unescapeField(it) }
                // 8 列 = 当前格式（含 filenames）；7 列 = 早期版本写入（无 filenames），按旧布局解析以兼容升级。
                // **必须精确等于** 8 或 7：原先写 `>= 8`，而 save() 未转义字段，
                // 一个含制表符的 exts/source 会把该行撑成 9 列并整行右移 ——
                // filenames 收到 exts 的第二段、sha256 收到 filenames、sizeBytes 变 0、
                // `enabled = c[7]=="1"` 而 c[7] 是 13 位时间戳 → 该语法包**静默变成停用**，
                // 界面毫无报错。多列整行丢弃，宁可少一个包也不要解析出字段全错的幽灵条目。
                when (c.size) {
                    8 -> SyntaxPack(
                        id = c[0], exts = splitKeys(c[1]).ifEmpty { listOf(c[0]) }, filenames = splitKeys(c[2]),
                        sha256 = c[3], source = c[4],
                        sizeBytes = c[5].toLongOrNull() ?: 0L,
                        addedAtMs = c[6].toLongOrNull() ?: 0L,
                        enabled = c[7] == "1"
                    )
                    7 -> SyntaxPack(
                        id = c[0], exts = splitKeys(c[1]).ifEmpty { listOf(c[0]) }, filenames = emptyList(),
                        sha256 = c[2], source = c[3],
                        sizeBytes = c[4].toLongOrNull() ?: 0L,
                        addedAtMs = c[5].toLongOrNull() ?: 0L,
                        enabled = c[6] == "1"
                    )
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * TSV 字段转义：制表符/换行/回车是列与行的分隔符，直接落盘即错列。
     * ext4 允许文件名含制表符（`source = "local:<path>"` 会带进来），
     * `index.json` 里的扩展名数组同样可能含制表符。
     * 纯函数，便于 JVM 单测。
     */
    internal fun escapeField(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** [escapeField] 的逆运算。 */
    internal fun unescapeField(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    't' -> { sb.append('\t'); i += 2; continue }
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun splitKeys(raw: String): List<String> =
        raw.split(',').map { unescapeField(it.trim()).lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }

    private fun save(ctx: Context, packs: List<SyntaxPack>) {
        val f = indexFile(ctx)
        atomicWrite(
            f,
            packs.joinToString("\n") { p ->
                listOf(
                    p.id,
                    p.exts.joinToString(","),
                    p.filenames.joinToString(","),
                    p.sha256,
                    p.source,
                    p.sizeBytes.toString(),
                    p.addedAtMs.toString(),
                    if (p.enabled) "1" else "0"
                ).joinToString("\t") { escapeField(it) }
            }
        )
    }

    /**
     * 原子写：同目录临时文件 + rename 覆盖，避免写入中途进程被杀导致 [indexFile] 损坏（丢失全部语法包元数据）。
     * rename 失败（极少见）时回退为非原子直写，至少保证落盘。
     */
    private fun atomicWrite(file: File, text: String) {
        val dir = file.parentFile
        if (dir == null) {
            file.writeText(text)
            return
        }
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 启用的语法包：**匹配键 → 语法 id**。
     * 键同时包含扩展名与无扩展名文件名（如 `dockerfile`），均由编辑器传入的小写键匹配。
     */
    fun keyOverrides(ctx: Context): Map<String, String> = buildMap {
        list(ctx).filter { it.enabled }.forEach { p ->
            p.exts.forEach { put(it, p.id) }
            p.filenames.forEach { put(it, p.id) }
        }
    }

    @Volatile
    private var cachedKeys: Map<String, String>? = null

    /**
     * 匹配表的内存缓存。列表渲染会**逐行**查询语言标签，不能每行都读清单文件。
     * 任何增删改（导入/删除/启停）都要调用 [invalidateCache]。
     */
    fun keyOverridesCached(ctx: Context): Map<String, String> =
        cachedKeys ?: synchronized(this) { cachedKeys ?: keyOverrides(ctx).also { cachedKeys = it } }

    fun invalidateCache() {
        cachedKeys = null
    }

    /** 文件名 → 语言 id（先全名匹配、再扩展名匹配）；未导入对应语法包时返回 null。 */
    fun languageIdFor(ctx: Context, fileName: String?): String? {
        val name = fileName?.substringAfterLast('/')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val map = keyOverridesCached(ctx)
        val ext = name.substringAfterLast('.', "")
        return map[name] ?: ext.takeIf { it.isNotEmpty() }?.let { map[it] }
    }

    /**
     * 从本地文件导入。支持两种形态：
     *  - `.zip` 语法包压缩档（内含 `index.json` + 语法 JSON，整体导入）；
     *  - 单个 Monarch 语法 JSON。
     */
    fun importFromFile(ctx: Context, path: String): Result<Int> = runCatching {
        val file = File(path)
        require(file.isFile) { "文件不存在：$path" }
        require(file.length() in 1..MAX_ZIP_BYTES) { "文件体积需在 1B ~ ${MAX_ZIP_BYTES / 1024}KB 之间" }
        val bytes = file.readBytes()
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            importZip(ctx, bytes, "local:$path")
        } else {
            val id = sanitize(file.nameWithoutExtension)
            require(id.isNotEmpty()) { "无法从文件名推断语法标识" }
            ingest(ctx, bytes, id, exts = null, source = "local:$path", expectedSha256 = null)
            1
        }
    }

    /** 从 https 地址导入：内容可为语法包 zip 或单个语法 JSON。 */
    fun importFromUrl(ctx: Context, url: String, expectedSha256: String?): Result<Int> = runCatching {
        val trimmed = url.trim()
        require(trimmed.startsWith("https://")) { "仅支持 https:// 地址" }
        val bytes = download(trimmed)
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            importZip(ctx, bytes, trimmed)
        } else {
            val fileName = trimmed.substringAfterLast('/').substringBefore('?')
            val id = sanitize(fileName.substringBeforeLast('.').ifEmpty { fileName })
            require(id.isNotEmpty()) { "无法从 URL 推断语法标识" }
            ingest(ctx, bytes, id, exts = null, source = trimmed, expectedSha256 = expectedSha256)
            1
        }
    }

    fun remove(ctx: Context, id: String) {
        fileFor(ctx, id).delete()
        save(ctx, list(ctx).filterNot { it.id == id })
        invalidateCache()
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        save(ctx, list(ctx).map { if (it.id == id) it.copy(enabled = enabled) else it })
        invalidateCache()
    }

    /** 批量启停：一次写入清单（逐个调用会写 N 次文件）。 */
    fun setAllEnabled(ctx: Context, enabled: Boolean) {
        val packs = list(ctx).map { if (it.enabled != enabled) it.copy(enabled = enabled) else it }
        save(ctx, packs)
        invalidateCache()
    }

    private fun download(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            require(conn.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${conn.responseCode}" }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    // 边读边限流：不能等下载完再判断体积。
                    // 上限取两者较大者（zip 整包可达 MAX_ZIP_BYTES），具体上限由 importZip/ingest 按内容类型复核。
                    require(total <= MAX_ZIP_BYTES) { "内容超过体积上限 ${MAX_ZIP_BYTES / 1024}KB" }
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 整体导入语法包 zip：`index.json`（可选，声明每个语法的扩展名）+ 语法 JSON。
     * 任一语法校验失败即整体拒绝（已写入的文件会回滚删除），保证不会出现"半套"语法。
     */
    /**
     * 读取当前 entry 的内容，累计超过 [remaining] 立即抛错。
     *
     * 与 [download] 的边读边限流同口径：先按 `remaining` 预分配一个**有界**缓冲，
     * 一旦读满即判定超限，绝不为了「读完再判」而无限增长。
     * 纯函数（不依赖 Context），便于 JVM 单测。
     */
    internal fun readEntryBounded(zin: java.io.InputStream, remaining: Int): ByteArray {
        require(remaining >= 0) { "压缩包内容超过体积上限 ${MAX_ZIP_BYTES / 1024}KB" }
        val buf = java.io.ByteArrayOutputStream(minOf(remaining.coerceAtLeast(0), 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = zin.read(chunk)
            if (n < 0) break
            require(buf.size() + n <= remaining) {
                "压缩包内容超过体积上限 ${MAX_ZIP_BYTES / 1024}KB"
            }
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun importZip(ctx: Context, bytes: ByteArray, source: String): Int {        val indexExts = HashMap<String, List<String>>()
        val indexNames = HashMap<String, List<String>>()
        val grammars = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            var total = 0
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    // 必须**边读边限流**，不能 `zin.readBytes()` 之后再判总量。
                    // 单个 entry 可以声明数 GB 的未压缩大小而压缩后仅数 MB（zip 炸弹），
                    // `readBytes()` 会先把整个 entry 物化进 ByteArrayOutputStream
                    // （含 2~3 倍扩容余量）才轮到第 275 行的 require ——
                    // 预算检查形同虚设，堆被打爆后进程不可恢复。
                    // 这里的 readEntryBounded 与 download() 的边读边限流同口径。
                    val data = readEntryBounded(zin, MAX_ZIP_BYTES - total)
                    total += data.size
                    when {
                        name.equals(ZIP_INDEX, ignoreCase = true) -> {
                            val arr = JSONArray(String(data, Charsets.UTF_8))
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val id = sanitize(o.optString("id"))
                                if (id.isEmpty()) continue
                                val exts = readStringArray(o.optJSONArray("exts"))
                                val names = readStringArray(o.optJSONArray("filenames"))
                                indexExts[id] = exts.ifEmpty { listOf(id) }
                                indexNames[id] = names
                            }
                        }
                        name.lowercase(Locale.ROOT).endsWith(".json") -> {
                            // 必须用 substringBeforeLast 而不是 removeSuffix：
                            // 上面的判定是大小写无关的，而 removeSuffix **大小写敏感**，
                            // `Kotlin.Json` 会原样返回 → sanitize 得 "kotlinjson"，
                            // 与 `kotlin.json` 写出的键不一致，语法包静默不高亮。
                            val id = sanitize(name.substringBeforeLast('.'))
                            if (id.isNotEmpty()) grammars[id] = data
                        }
                    }
                }
                entry = zin.nextEntry
            }
        }
        require(grammars.isNotEmpty()) { "压缩包内没有语法 JSON" }

        // 先全部校验，再落盘：失败即整体拒绝。
        val validated = grammars.map { (id, data) ->
            val text = String(data, Charsets.UTF_8)
            val json = runCatching { JSONObject(text) }
                .getOrElse { throw IllegalArgumentException("语法 $id 不是合法 JSON") }
            require(json.has("tokenizer")) { "语法 $id 缺少 tokenizer 字段" }
            Triple(id, data, sha256(data))
        }

        val packs = list(ctx).filterNot { p -> grammars.containsKey(p.id) }.toMutableList()
        val written = mutableListOf<File>()
        try {
            for ((id, data, digest) in validated) {
                val file = fileFor(ctx, id)
                file.parentFile?.mkdirs()
                file.writeBytes(data)
                written += file
                packs += SyntaxPack(
                    id = id, exts = indexExts[id] ?: listOf(id), filenames = indexNames[id] ?: emptyList(),
                    sha256 = digest, source = source,
                    sizeBytes = data.size.toLong(), addedAtMs = System.currentTimeMillis(), enabled = true
                )
            }
            save(ctx, packs)
            invalidateCache()
        } catch (e: Throwable) {
            // 落盘失败：回收已写入文件，避免留下"有文件无清单"的僵尸语法。
            written.forEach { runCatching { it.delete() } }
            throw e
        }
        return grammars.size
    }

    private fun readStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().lowercase(Locale.ROOT).takeIf { s -> s.isNotEmpty() } }
    }

    private fun ingest(
        ctx: Context, bytes: ByteArray, id: String, exts: List<String>?, source: String, expectedSha256: String?
    ): SyntaxPack {
        require(bytes.isNotEmpty()) { "内容为空" }
        require(bytes.size <= MAX_BYTES) { "内容超过体积上限 ${MAX_BYTES / 1024}KB" }
        val digest = sha256(bytes)
        if (!expectedSha256.isNullOrBlank()) {
            require(digest.equals(expectedSha256.trim(), ignoreCase = true)) { "SHA-256 不匹配（实际 $digest）" }
        }
        val text = String(bytes, Charsets.UTF_8)
        val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("不是合法 JSON") }
        require(json.has("tokenizer")) { "缺少 tokenizer 字段，不是 Monarch 语法" }
        // 语法 JSON 可用顶层 "extensions"/"filenames" 声明匹配键（缺省 = 语法 id 本身）。
        val resolved = runCatching { readStringArray(json.optJSONArray("extensions")) }.getOrDefault(emptyList())
        val names = runCatching { readStringArray(json.optJSONArray("filenames")) }.getOrDefault(emptyList())
        val file = fileFor(ctx, id)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val pack = SyntaxPack(
            id = id, exts = exts ?: resolved.ifEmpty { listOf(id) }, filenames = names,
            sha256 = digest, source = source,
            sizeBytes = bytes.size.toLong(), addedAtMs = System.currentTimeMillis(), enabled = true
        )
        save(ctx, list(ctx).filterNot { it.id == id } + pack)
        invalidateCache()
        return pack
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 语法 id 只允许字母数字与下划线：它同时是文件名与 scope 尾段。 */
    private fun sanitize(raw: String): String =
        raw.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '_' }
}
