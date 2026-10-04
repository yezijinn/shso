// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid.data

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 可安装的 APK 系扩展名（小写；比较前先 lowercase）。 */
val INSTALLABLE_EXTENSIONS = setOf("apk", "xapk", "apks", "aspk", "apkm")

/** 可编辑的常见文本扩展名（小写；比较前先 lowercase）。
 *  来源 docs/2026-09-05_00-43-02.txt：纯文本/代码/前端标记/Shell/系统配置。
 */
val TEXT_EXTENSIONS = setOf(

    // 纯文本
    "txt", "log", "text", "csv", "ini", "cfg", "conf", "properties", "env",
    // 代码
    "py", "java", "kt", "c", "cpp", "h", "hpp", "cc", "cs", "go", "rs",
    "swift", "rb", "php", "js", "ts", "jsx", "tsx",
    // 前端/标记
    "html", "htm", "xml", "css", "scss", "less", "json", "yaml", "yml",
    "toml", "md", "markdown", "rst",
    // Shell/脚本
    "sh", "bash", "zsh", "bat", "cmd", "ps1", "sql", "lua", "pl", "r",
    // 系统/配置
    "rc", "gradle", "cmake", "mk", "makefile",
    // 已有保留
    "tsv", "kts", "smali", "gitignore",

    // 语法包覆盖的其余扩展名（与仓库 syntax-packs 保持一致，避免「能高亮却打不开」）
    "asm", "aux", "babelrc", "bas", "bib", "c++", "cginc", "cjs",
    "cls", "comp", "containerfile", "cron", "crontab", "csx", "cts", "cxx",
    "dart", "ddl", "desktop", "diff", "dml", "dockerfile", "erl", "err",
    "eslintrc", "ex", "exs", "fish", "frag", "fx", "fxh", "gemspec",
    "geom", "gitattributes", "gitconfig", "gitmodules", "glsl", "groovy", "gvy", "h++",
    "hcl", "hh", "hlsl", "hlslinc", "hosts", "hrl", "hs", "hxx",
    "iml", "ino", "jl", "json5", "jsonc", "jsp", "ksh", "latex",
    "lhs", "lock", "m", "mak", "manifest", "masm", "matlab", "mdown",
    "meson", "mjs", "mm", "mount", "mts", "nasm", "nginx", "nginxconf",
    "nim", "nimble", "nims", "out", "patch", "path", "php5", "phtml",
    "plist", "pm", "pod", "proto", "psd1", "psm1", "pyi", "pyw",
    "pyx", "rake", "reg", "regex", "regexp", "rmd", "ru", "s",
    "sas", "sass", "sbt", "sc", "scala", "scope", "service", "shader",
    "slice", "socket", "sol", "sty", "svelte", "svg", "swap", "t",
    "target", "tcc", "tesc", "tese", "tex", "tf", "tfstate", "tfvars",
    "timer", "unit", "usf", "ush", "vb", "vbs", "vert", "vue",
    "wrap", "xhtml", "xsd", "xsl", "xslt", "zig", "zon",
)

/** 可浏览的常见图片扩展名（小写；比较前先 lowercase）。 */
val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp", "ico", "tiff", "tif")

/**
 * 「.数字」尾缀（腾讯产品下载后追加的 .1，如 qq.apk.1）。
 *
 * 提到顶层并只编译一次：此前它在 [FileItem.realExtension] 与 [realArchiveName]
 * 里每次访问都 `Regex(...)` 现场构造，而这两个属性在文件列表的**每一行、
 * 每一次重组**里被求值多次（图标、字号、颜色三处各判一次类型）。
 */
private val NUMERIC_SUFFIX = Regex("\\.\\d+$")

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val permissions: String = ""
) {
    // ── 以下属性一律在**构造期算一次**，不再每次访问重算 ──────────────────
    //
    // 它们此前全是 `get()` 计算属性，而文件列表的行内容会对同一项连续求值三轮
    // （图标文案 / 字号 / 颜色各判一次 isInstallable、isViewableImage、isEditableText），
    // 于是单个可见行每次重组要构造 7 次 Regex、7 次匹配、14 个临时字符串、
    // 6 次 extension，外加一次 String.format。仅「选中态变化」这种轻量重组
    // 就会在 20 个可见行上放大成 150+ 次正则构造。
    //
    // 全部只依赖构造入参，构造期求值语义等价且无副作用。
    // 声明顺序有意义：isExtensionlessText 依赖下面几个，必须排在它们之后。

    val extension: String =
        if (isDirectory) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** 剥除「.数字」尾缀后的真实扩展名（兼容腾讯产品下载后追加 .1 的情况，如 qq.apk.1）。 */
    val realExtension: String = run {
        val stripped = NUMERIC_SUFFIX.find(name)?.let { name.substring(0, it.range.first) } ?: name
        if (isDirectory) "" else stripped.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    /** 剥除「.数字」尾缀后的文件名（供压缩包类型判定用）。 */
    private val realArchiveName: String =
        if (NUMERIC_SUFFIX.matches(name)) name.substringBeforeLast('.') else name

    val isExecutableScript: Boolean = !isDirectory && extension == "sh"

    val isExecutableBinary: Boolean = !isDirectory && extension == "so"

    val isSupportedExecutable: Boolean = isExecutableScript || isExecutableBinary

    /** 是否可安装的 APK 系文件（apk/xapk/apks/aspk/apkm），大小写不敏感，兼容 .1 尾缀。 */
    val isInstallable: Boolean = !isDirectory && realExtension in INSTALLABLE_EXTENSIONS

    /** 是否常见图片（可浏览）。 */
    val isViewableImage: Boolean = !isDirectory && realExtension in IMAGE_EXTENSIONS

    /** 是否为已知压缩包（zip/tar/tgz/7z/gz/xz/bz2/lz4 等）——长按菜单据此显示「自动解压文件」。 */
    val isArchive: Boolean = !isDirectory && ArchiveExtractor.isKnownArchive(realArchiveName)

    /**
     * 无扩展名（`Dockerfile`、`Makefile`、`hosts`、`crontab`）或点开头的隐藏配置（`.gitignore`）
     * 按文本处理——这类文件在扩展名白名单里查不到，但绝大多数是纯文本。
     *
     * 对外可见：外部唤起需要据此判断「谓词无法区分类型、应以 MIME 为准」。
     */
    val isExtensionlessText: Boolean = run {
        if (isDirectory) return@run false
        val dots = name.count { it == '.' }
        if (dots == 0) return@run true
        // Keep dot-prefixed configuration files text-like, but preserve
        // known action types such as .apk, .zip, .sh and .png.
        name.startsWith(".") && dots == 1 &&
            !isInstallable && !isSupportedExecutable && !isViewableImage && !isArchive
    }

    /** 是否常见文本文档（可编辑保存）。 */
    val isEditableText: Boolean =
        !isDirectory && (realExtension in TEXT_EXTENSIONS || isExtensionlessText)

    /**
     * 是否实际可解压。
     *
     * 所有已知格式均可解压（rar 已彻底移除），因此与 [isArchive] 同源。
     * 保留独立属性是为了不改动既有调用点。
     */
    val isExtractableArchive: Boolean = isArchive

    val formattedSize: String = run {
        if (isDirectory) return@run "目录"
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "$size B"
        }
    }

    val formattedDate: String
        get() {
            if (lastModified <= 0) return ""
            // 使用不可变、线程安全的 DateTimeFormatter：每项每帧都会读取本属性，
            // 每次新建 formatter 会持续产生临时对象。
            return Instant.ofEpochMilli(lastModified)
                .atZone(ZoneId.systemDefault())
                .format(FILE_DATE_FORMATTER)
        }

    private companion object {
        /**
         * 文件修改时间格式化。
         *
         * 两个「区域无关」都要显式指定，缺一即在部分区域下错得离谱：
         *  - `Locale.ROOT`：避免土耳其语等 locale 的大小写折叠规则；
         *  - `IsoChronology`：`DateTimeFormatter.ofPattern` 默认取 locale 的**默认历法**，
         *    CLDR 规定 `th-TH` 是佛历、`ja-JP-u-ca-japanese` 是日本历，
         *    此时 `yyyy` 取的是该历法的 YEAR 字段 —— 文件列表所有时间会显示成 2569 年。
         */
        private val FILE_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                .withChronology(IsoChronology.INSTANCE)
                .withZone(ZoneId.systemDefault())
    }
}

/**
 * [FileItem] 的 `rememberSaveable` Saver：把各字段摊平成可放入 Bundle 的列表。
 *
 * 用于「待执行 / 待安装」这类必须在配置变更（旋转 / 分屏）后存活的状态 ——
 * 它们是文件页自身产生的确认流程，属用户显式意图，旋转不应静默丢弃。
 * 只存已解析出的字段，重建后不会再去读目录，避免依赖列表是否已加载。
 */
val FileItemSaver: Saver<FileItem?, Any> = listSaver(
    save = { item ->
        if (item == null) emptyList() else listOf(
            item.name, item.path, item.isDirectory, item.size, item.lastModified, item.permissions
        )
    },
    restore = { values ->
        if (values.size != 6) null else {
            val name = values[0] as? String
            val path = values[1] as? String
            val isDirectory = values[2] as? Boolean
            val size = (values[3] as? Number)?.toLong()
            val lastModified = (values[4] as? Number)?.toLong()
            val permissions = values[5] as? String
            if (name == null || path == null || isDirectory == null || size == null ||
                lastModified == null || permissions == null
            ) null else FileItem(name, path, isDirectory, size, lastModified, permissions)
        }
    }
)
