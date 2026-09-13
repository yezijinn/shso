// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0
package com.mixradio.droid.ui.components

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.mixradio.droid.data.syntax.SyntaxPackStore
import com.mixradio.droid.ui.theme.AuroraTokens
import io.github.rosemoe.sora.langs.monarch.MonarchColorScheme
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage
import io.github.rosemoe.sora.langs.monarch.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.monarch.registry.MonarchGrammarRegistry
import io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.monarch.registry.dsl.monarchLanguages
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeSource
import io.github.rosemoe.sora.langs.monarch.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Monarch 语法高亮：扩展名 → 语法定义。
 *
 * **APK 不内置任何语法**（体积优先）：语法全部来自用户导入的语法包
 * （`filesDir/syntax/grammars/<id>.json`，见 [SyntaxPackStore]），未导入前编辑器为无高亮的纯文本。
 * 语法包可从本地文件或 https 地址整体导入（zip：`index.json` + 语法 JSON）。
 *
 * Sora 的 [MonarchLanguage.create] 接收的是**语法注册名（scope）**而非 JSON 文本，
 * 故首次使用时经 [MonarchGrammarRegistry] 注册全部语法，再按 scope 取语言实例。
 * 单个语法解析失败不影响其它语言（该扩展名退化为无高亮的纯文本，不影响编辑）。
 */
internal object SoraMonarchGrammars {

    private const val THEME_ASSET = "sora-themes/shso-dark.json"
    private const val THEME_NAME = "shso-dark"

    private val lock = Any()
    private val cache = HashMap<String, MonarchLanguage?>()

    /** 已在本进程内注册过的语法 id（注册表是全局的，同一 id 重复注册会抛异常）。 */
    private val registeredIds = HashSet<String>()

    /** 进程级初始化标志（解析器 + 主题）：只允许执行一次，`invalidate` 不得重置。 */
    private var providersReady = false
    private var monarchScheme: MonarchColorScheme? = null

    /** 匹配键（扩展名 / 无扩展名文件名）→ 语言 id。仅读清单，不解析语法。 */
    private var keyToPack: Map<String, String> = emptyMap()

    /** 主题就绪标志：主题加载失败时**不得**启用语法——否则令牌色为 0，正文变黑/不可见。 */
    private var themeReady = false

    /**
     * Monarch 主题配色。
     *
     * Monarch 的令牌色来自主题（无主题时令牌色为 0，渲染为不可见），故启用语法时必须同时套用本方案。
     * 主题不可用时返回 null，调用方退回基础配色（无高亮但文本可读）。
     */
    fun schemeFor(context: Context): MonarchColorScheme? = synchronized(lock) {
        ensureProviders(context.applicationContext)
        if (!themeReady) return null
        if (monarchScheme == null) {
            monarchScheme = runCatching { MonarchColorScheme(ThemeRegistry.currentTheme).applyReadableGuards() }
                .getOrNull()
        }
        monarchScheme
    }

    /**
     * 按**文件名**取语法（大小写不敏感）。
     *
     * 先按完整文件名匹配（覆盖 `Dockerfile`、`CMakeLists.txt` 这类无扩展名或扩展名有歧义的文件），
     * 再退回扩展名匹配。未导入对应语法包时返回 null —— 编辑器为无高亮的纯文本（可读、可编辑）。
     *
     * **按需注册**：只为命中的那一个语法做注册与解析，避免首次进入编辑器就解析全部语法包
     * （62 个语法 JSON 的解析与正则编译是百毫秒级开销，不应压在主线程上）。
     */
    fun languageFor(context: Context, fileName: String?): MonarchLanguage? {
        val name = fileName?.substringAfterLast('/')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        synchronized(lock) {
            if (cache.containsKey(name)) return cache[name]
            ensureProviders(context.applicationContext)
            // 主题不可用时不装语法：宁可无高亮，也不能出现底色与文字同色的不可读文本。
            val language = if (!themeReady) null else {
                val ext = name.substringAfterLast('.', "")
                val id = keyToPack[name] ?: ext.takeIf { it.isNotEmpty() }?.let { keyToPack[it] }
                id?.let { loadLanguage(context.applicationContext, it) }
            }
            cache[name] = language
            return language
        }
    }

    /**
     * 语法包增删后调用：**只失效缓存**（O(1)，不解析任何语法），下次取用时按需重建。
     * 解析器与主题是进程级一次性初始化，**不得在此重复注册**——`FileProviderRegistry` 的
     * provider 列表只增不减，重复添加会让解析链随每次导入/启停线性变长。
     */
    fun invalidate(context: Context) {
        synchronized(lock) {
            cache.clear()
            keyToPack = loadKeyMap(context.applicationContext)
            monarchScheme = null
        }
    }

    /**
     * 兜底保证：把文本类槽位钉在 Aurora 的浅色上，避免任何来源（主题缺项 / 引擎默认值）
     * 把前景色落回黑色。背景类槽位不动，仍由主题提供。
     */
    private fun MonarchColorScheme.applyReadableGuards() = apply {
        setColor(EditorColorScheme.TEXT_NORMAL, AuroraTokens.Text.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER, AuroraTokens.TextDisabled.toArgb())
        setColor(EditorColorScheme.KEYWORD, AuroraTokens.Accent.toArgb())
        setColor(EditorColorScheme.COMMENT, AuroraTokens.TextSecondary.toArgb())
        setColor(EditorColorScheme.LITERAL, AuroraTokens.Success.toArgb())
        setColor(EditorColorScheme.FUNCTION_NAME, AuroraTokens.Accent.toArgb())
        setColor(EditorColorScheme.IDENTIFIER_NAME, AuroraTokens.Text.toArgb())
        setColor(EditorColorScheme.IDENTIFIER_VAR, AuroraTokens.Text.toArgb())
        setColor(EditorColorScheme.OPERATOR, AuroraTokens.TextSecondary.toArgb())
        setColor(EditorColorScheme.ANNOTATION, AuroraTokens.Warning.toArgb())
        setColor(EditorColorScheme.PROBLEM_ERROR, AuroraTokens.Error.toArgb())
        setColor(EditorColorScheme.PROBLEM_WARNING, AuroraTokens.Warning.toArgb())
    }

    private fun ensureProviders(appContext: Context) {
        if (providersReady) return
        providersReady = true
        // 解析器顺序**不可颠倒**（FileProviderRegistry.resolve = firstNotNullOfOrNull，取首个非 null）：
        //  1) 应用私有目录（语法包都在这里，路径相对 filesDir）；
        //  2) assets（**仅供主题** shso-dark.json 使用）。
        // 反过来会出问题：AssetsFileResolver.resolve 直接 assetManager.open(path) 不做捕获，
        // 语法包路径不在 assets 时抛 FileNotFoundException，异常会沿 firstNotNullOfOrNull 冒泡，
        // 导致后面的解析器永远不被调用（表现为全部语法加载失败）。
        runCatching { FileProviderRegistry.addProvider(AppFilesFileResolver(appContext.filesDir)) }
            .onFailure { logGrammarFailure("addProvider:appfiles", it) }
        runCatching { FileProviderRegistry.addProvider(AssetsFileResolver(appContext.assets)) }
            .onFailure { logGrammarFailure("addProvider:assets", it) }
        // 先装主题：Monarch 的令牌色由主题提供，缺主题会导致令牌色为 0（正文变黑/不可见）。
        themeReady = runCatching {
            val model = ThemeModel(ThemeSource(THEME_ASSET, THEME_NAME, null)).apply { isDark = true }
            ThemeRegistry.loadTheme(model, false)
            ThemeRegistry.setTheme(model)
            ThemeRegistry.currentTheme.isLoaded
        }.getOrDefault(false)
        keyToPack = loadKeyMap(appContext)
    }

    /** 只读清单（不解析语法 JSON），成本与语法包数量线性但极小（一次读几百字节的 TSV）。 */
    private fun loadKeyMap(appContext: Context): Map<String, String> =
        runCatching { SyntaxPackStore.keyOverrides(appContext) }.getOrDefault(emptyMap())

    /** 注册并创建单个语法；已注册过则直接创建。失败返回 null 并记日志（该键退化为无高亮）。 */
    private fun loadLanguage(appContext: Context, id: String): MonarchLanguage? {
        val path = "${SyntaxPackStore.GRAMMAR_PATH_PREFIX}/$id.json"
        val started = System.currentTimeMillis()
        val created = runCatching {
            if (registeredIds.add(id)) register(id, path)
            MonarchLanguage.create("source.$id", false)
        }.getOrElse {
            registeredIds.remove(id)
            logGrammarFailure(id, it)
            null
        }
        if (created != null) {
            android.util.Log.i(
                "shso-perf",
                "语法就绪: $id cost=${System.currentTimeMillis() - started}ms（进程内已注册 ${registeredIds.size} 个）"
            )
        }
        return created
    }

    private fun logGrammarFailure(id: String, error: Throwable) {
        android.util.Log.w("shso-perf", "语法注册失败: $id → ${error.javaClass.simpleName}: ${error.message}")
    }

    private fun register(languageId: String, grammarPath: String) {
        MonarchGrammarRegistry.INSTANCE.loadGrammars(
            monarchLanguages {
                language(languageId) {
                    grammar = grammarPath
                    defaultScopeName()
                }
            }
        )
    }
}

/** 解析应用私有目录（`filesDir`）下的语法/主题文件，路径按 filesDir 相对解析。 */
private class AppFilesFileResolver(private val filesDir: java.io.File) :
    io.github.rosemoe.sora.langs.monarch.registry.provider.FileResolver {
    override fun resolve(path: String): java.io.InputStream? {
        val file = java.io.File(filesDir, path)
        val stream = runCatching { file.inputStream() }.getOrNull()
        android.util.Log.d(
            "shso-perf",
            "appResolver $path → ${if (stream != null) "命中" else "未命中"} (${file.absolutePath})"
        )
        return stream
    }

    override fun dispose() = Unit
}
