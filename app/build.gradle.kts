// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/**
 * 签名密码读取优先级：
 * 1. 环境变量：KEYSTORE_PASSWORD / KEY_ALIAS_PASSWORD（CI/CD / 生产构建推荐）
 * 2. local.properties：signing.storePassword / signing.keyPassword（本地开发兜底，该文件已 .gitignore）
 *
 * 两处均未配置时主动抛异常，禁止静默降级到硬编码默认值。
 */
fun getSigningPassword(envKey: String, propKey: String): String =
    System.getenv(envKey) ?: readLocalPropertiesProperty(propKey)
        ?: error("签名密码未配置：请设置环境变量 $envKey 或在 local.properties 中配置 $propKey")

fun readLocalPropertiesProperty(propKey: String): String? =
    try {
        val propFile = rootProject.file("local.properties")
        if (!propFile.exists()) null
        else propFile.readText().lines()
            .filter { it.startsWith("$propKey=") }
            .mapNotNull { it.substringAfter('=').trim() }
            .firstOrNull()
    } catch (_: Exception) { null }

fun getSigningFile(): File = rootProject.file(
        System.getenv("KEYSTORE_FILE")
            ?: readLocalPropertiesProperty("signing.storeFile")
            ?: "C:/AI_WORKSPACE/GLOBAL/credentials/JinnKeyStores/com.mixradio.droid/release.jks"
    )


kotlin {
    // 等价原 module.kotlin-jvm-toolchain 约定插件：统一 Kotlin JVM Toolchain 21
    jvmToolchain(21)
}

// 版本号自动取「构建当日日期」纯数字（YYYYMMDD，如 20260905），
// 既作为 versionCode（应用升级判定的唯一依据），也与 GitHub 发布标签的纯日期格式对齐。
//
// **必须固定按东八区计算**：GitHub runner 默认 UTC，北京时间 00:00–08:00 之间构建时
// `LocalDate.now()` 会退回前一天，产物 versionCode 与发布标签不一致 ——
// 用户装上后 versionCode 仍是旧日期，应用内「检查更新」会无限提示升级。
val buildDateVersionCode: Int = run {
    LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
}

android {
    namespace = "com.mixradio.droid"
    compileSdk = 37

    // —— 在线编译自定义包名支持 ——
    // 仅覆盖 applicationId（安装身份），namespace 与源码包名保持 com.mixradio.droid 不变，
    // 以保证 R / BuildConfig 引用与所有 import 有效；FileProvider authority、跳设置页 Uri
    // 均使用 ${applicationId} / context.packageName，自动跟随。
    val overridePackage: String? = project.findProperty("overridePackage")?.toString()
    // 仅显式传入 -PuseDebugSigning=true 时使用调试密钥；Release CI 默认使用外部 release 密钥。
    val useDebugSigning = project.findProperty("useDebugSigning")?.toString()?.toBoolean() == true

    defaultConfig {
        applicationId = overridePackage ?: "com.mixradio.droid"
        minSdk = 26
        targetSdk = 35
        versionCode = buildDateVersionCode
        // 版本名固定为 "Jinn"（新规则，不再使用 9.0.2 之类的数字版本名）。
        // 升级判定只看 versionCode（构建当日日期 YYYYMMDD），versionName 仅作展示。
        versionName = "Jinn"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包 arm64-v8a，其余 ABI 的原生库约 1.4MB。
        // 不要用 splits.abi：产物名会变成 app-arm64-v8a-release.apk，
        // build_apk.py 按 app-release*.apk 定位产物会失败。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // 本块在**配置期**求值，且不受 useDebugSigning 约束：无条件读凭据会让
            // 「缺外部密钥、回退调试签名」的构建在配置阶段就 error 中止
            // （Fork 未继承 Secrets 时 `-PuseDebugSigning=true` 也救不回来）。
            // 故仅在实际使用外部密钥时读取签名文件与密码。
            if (!useDebugSigning) {
                // 真实签名密钥位于全局凭据目录（与 GLOBAL/credentials/JinnKeyStores 保持一致）
                storeFile = getSigningFile()
                // 密码通过环境变量或 local.properties 读取，不再硬编码
                storePassword = getSigningPassword("KEYSTORE_PASSWORD", "signing.storePassword")
                keyAlias = "com.mixradio.droid"
                keyPassword = getSigningPassword("KEY_ALIAS_PASSWORD", "signing.keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8：混淆并删除未引用类以减小 dex，同时让 ART 走 AOT 而非 JIT。
            // 反射与 SPI 类（commons-compress / zip4j / material3）在 proguard-rules.pro 中保留。
            isMinifyEnabled = true
            // shrinkResources 随 R8 删除未引用资源，依赖 isMinifyEnabled = true。
            isShrinkResources = true
            signingConfig = if (useDebugSigning) signingConfigs.getByName("debug") else signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // AGP 默认写入 META-INF/version-control-info.textproto（构建时的 git revision 等），
            // 产物无需此元数据；构建信息由发布 tag 与 Release 记录承担。
            vcsInfo {
                include = false
            }
        }
        debug {
            applicationIdSuffix = ""
            // 有外部密钥时 debug 与 release 同签名（便于覆盖安装）；回退调试密钥时
            // release 配置未填凭据，不能再用，改用 AGP 自带的 debug 配置。
            signingConfig = if (useDebugSigning) signingConfigs.getByName("debug") else signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // 只打包 arm64-v8a 是既定取舍（其余 ABI 原生库合计约 1.4MB，目标设备为 arm 真机），
        // 不为 ChromeOS 增加 x86_64。
        disable += "ChromeOsAbiSupport"
        // targetSdk 升级牵涉前台服务类型、通知权限、存储分区等行为变更，需完整真机回归后再动。
        disable += "OldTargetApi"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/META-INF/**/LICENSE*",
                "/META-INF/**/NOTICE*",
                "/META-INF/**/license*",
                "/META-INF/**/notice*",
                "/META-INF/**/*.properties",
                "DebugProbesKt.bin",
                "kotlin/**",
                "kotlin-tooling-metadata.json",
                // commons-codec 的语音匹配资源（bm/ 词典 120 个 txt + 同目录的 dmrules.txt），
                // 本应用不使用语音匹配（无 Metaphone / Soundex 调用），整目录排除。
                "org/apache/commons/codec/language/**",
                // jcodings（joni → regex-lib-oniguruma 的传递依赖）的 648 个编码转换表（约 2.9MB），
                // 仅在转录遗留编码时需要；Monarch 语法匹配只用 UTF-8/ASCII-8BIT（内建编码，不查表）。
                "tables/**"
            )
        }
    }

    /**
     * 资产压缩策略：`assets/env.bin` 本身就是 gzip 流，aapt2 再 deflate 一遍只会让首次
     * 解压多做一次无谓的 inflate。`noCompress` 按**扩展名**生效，`.bin` 一律原样入库。
     *
     * 资产名与压缩开关无关：本工程没有「`.gz` 资产会被打包工具解压改名」这类配置，
     * 读取路径始终是 `assets/env.bin`（见 EmbeddedEnv.ASSET）。
     */
    androidResources {
        noCompress += "bin"
    }
}

dependencies {
    // 原生 Material 3（AndroidX Compose，Google Maven 可达；导入命名空间仍为 androidx.compose.*）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.navigationevent)
    // 无 @Serializable 与 kotlinx.serialization 引用，故不直接依赖 serialization-core；
    // androidx.savedstate 仍会传递引入。需要 JSON 序列化时再恢复该依赖。

    implementation(libs.androidx.core.ktx)
    // 无 AppCompatActivity / AppCompatDialog / Theme.AppCompat，主题继承 android:Theme.Material，
    // 不依赖 appcompat。
    implementation(libs.kotlinx.coroutines.android)

    // 压缩包解压（zip/tar/tgz/7z 解析；本地 Gradle 缓存已具备 1.27.1，离线可构建）
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)

    // ZIP 加密解密（zip4j 支持 ZipCrypto + WinZip AES，char[] 密码天然支持中文）
    implementation(libs.zip4j)

    // Zstandard 解压（commons-compress 的 ZstdCompressorInputStream 通过 JNI 调用）。
    // 必须取 aar 变体：同 GAV 的 jar 内是 win/ darwin/ linux/ 桌面原生库，
    // 打进 APK 后既没有 Android 可加载的 .so（运行时 UnsatisfiedLinkError），又白占约 1.5 MB。
    implementation(libs.zstd.jni) { artifact { type = "aar" } }

    // 文本编辑器引擎（MP-Manager 同款 Sora Editor）：自绘 View + 行索引增量 Content，只渲染可视区。
    implementation(platform(libs.sora.editor.bom))
    implementation(libs.sora.editor)
    // 语法高亮：Monarch 引擎（语法定义以本项目内置 JSON 提供，不依赖外部语法包）
    implementation(libs.sora.language.monarch)

    // JVM 单元测试（JUnit 4，不依赖设备，可在无 ROOT 真机环境下验证纯逻辑路径）
    testImplementation(libs.junit4)
}

/**
 * release 产物内容校验（编译期强制，不做静默降级）。
 *
 * 约定：**APK 不携带任何语法高亮包**——语法由用户在线下载后导入（见 ui/components/SyntaxPackDialog.kt），
 * 编译产物中只允许存在配色主题。同时禁止把无关的数据表打进包（jcodings 的 648 个编码转换表曾贡献 1.24MB）。
 * 体积不做上限限制（约定：编译 APK 不限制体积）。
 * 内置 POSIX 环境（assets/env.bin）另有存在性、必需条目、完整工具链、版本与体积下限校验，见下方常量。
 */
val releaseApkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")

/**
 * 定位约定的 release APK。
 *
 * 只认 Gradle 当前变体的标准产物路径，避免把目录中的历史 APK 当作本次结果。
 * 返回 null 表示产物不存在 —— 调用方必须中断构建，不得静默跳过校验。
 */
fun resolveReleaseApk(): File? = releaseApkFile.get().asFile.takeIf(File::isFile)

/**
 * 内置 POSIX 环境归档（assets/env.bin，gzip tar）的发布红线，解压侧见 EmbeddedEnv.kt。
 *
 * 拦的是「静默出包」：资产被删、被占位文件顶替、被 aapt 排除规则命中，
 * 或重新打包时漏了二进制/证书，都必须在编译期失败。
 *
 * envArchiveVersion 需与归档内 VERSION 条目一致（当前为 "6"）。它和
 * EmbeddedEnv.ENV_VERSION（当前 "6"，仅作设备解压标记，不读归档内 VERSION）相互独立：
 * 归档重新生成时改这里，解压标记变更时改那边。两者必须同步递增，
 * 否则旧设备不会重解压，遗留的 bin/sh -> busybox 链接继续把脚本带崩。
 */
val envAssetPath = "assets/env.bin"
val envArchiveVersion = "6"
/** APK 内体积下限（现值约 29.1 MB）：低于 20 MB 只可能是占位或误裁。 */
val envAssetMinBytes = 20_000_000L
/** 解包后正文总量下限（现值约 71.5 MB）：防止只剩空条目的归档。 */
val envContentMinBytes = 60_000_000L

/**
 * 完整内置工具链的必需条目名（缺一即编译失败）。
 *
 * 名称按归档内实际路径写全（均在 bin/ 下）。其中 bin/python3 之类是软链，
 * 正文长度恒为 0，因此这一组只校验名称存在，不套用普通文件的大小下限。
 */
val envRequiredToolchainNames = setOf(
    "bin/git", "bin/python3", "bin/openssl", "bin/ssh", "bin/rsync",
    "bin/sed", "bin/tar", "bin/wget", "bin/grep", "bin/find",
)

/** tar 条目：名称、正文字节数、正文前 64 字节（读 VERSION 这类小文件足够）、是否软链。 */
data class TarEntryHead(val name: String, val size: Long, val head: ByteArray, val symlink: Boolean = false)

/** 跳过指定字节数，数据不足即视为归档截断。 */
fun skipFully(input: InputStream, count: Long) {
    var left = count
    val buf = ByteArray(8192)
    while (left > 0) {
        val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
        if (n < 0) throw IOException("tar 数据块不足，归档被截断")
        left -= n
    }
}

/**
 * 流式读取 gzip tar 的条目头，不把 13 MB 正文整体载入内存。
 *
 * 只处理 ustar 与 GNU 长文件名（类型 'L'）：本归档条目名均为短路径，无 pax 头。
 * 软链（类型 '2'）也计入名称集合：其正文长度为 0，链接目标在头部 linkname 字段，
 * 这里只需名称即可判定工具链是否齐全。
 */
fun readTarEntries(raw: InputStream): List<TarEntryHead> {
    val out = ArrayList<TarEntryHead>()
    GZIPInputStream(BufferedInputStream(raw)).use { gz ->
        val header = ByteArray(512)
        var pendingName: String? = null
        while (true) {
            val read = gz.readNBytes(header, 0, 512)
            if (read == 0) break
            if (read < 512) throw IOException("tar 头部不足 512 字节，归档被截断")
            if (header.all { it == 0.toByte() }) break // 全零块 = 归档结束
            val rawName = header.copyOfRange(0, 100).toString(Charsets.UTF_8).substringBefore('\u0000')
            val size = header.copyOfRange(124, 136).toString(Charsets.US_ASCII)
                .trim('\u0000', ' ').toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            if (type == 'L') {
                val body = ByteArray(size.toInt())
                var filled = 0
                while (filled < body.size) {
                    val n = gz.read(body, filled, body.size - filled)
                    if (n < 0) throw IOException("tar 长文件名正文被截断")
                    filled += n
                }
                pendingName = body.toString(Charsets.UTF_8).substringBefore('\u0000')
                skipFully(gz, padded - size)
                continue
            }
            val name = (pendingName ?: rawName).removePrefix("./")
            pendingName = null
            val head = ByteArray(minOf(size, 64L).toInt())
            var filled = 0
            while (filled < head.size) {
                val n = gz.read(head, filled, head.size - filled)
                if (n < 0) throw IOException("tar 正文被截断：$name")
                filled += n
            }
            // 普通文件、目录与软链都纳入名称集合；软链正文长度为 0，
            // 体积统计只认普通文件，名称集合用于工具链存在性校验。
            val isSymlink = type == '2'
            if (type == '0' || type == '\u0000' || type == '5' || isSymlink) {
                out += TarEntryHead(name, size, head, isSymlink)
            }
            skipFully(gz, padded - head.size)
        }
    }
    return out
}

val verifyReleasePayload = tasks.register("verifyReleasePayload") {
    group = "verification"
    description = "校验 release APK 不携带语法包/无关数据表，且内置 POSIX 环境与工具链完整"
    dependsOn("packageRelease")
    // 校验逻辑在 doLast 里读取 APK 内容，闭包会引用脚本对象，故声明为配置缓存不兼容
    // （仅在执行 assembleRelease 时触发，代价是本次构建不复用配置缓存）。
    notCompatibleWithConfigurationCache("校验任务读取 APK 内容，闭包引用脚本对象")
    // APK 由 packageRelease 先生成；校验只认标准路径，避免读取目录中的历史产物。
    doLast {
        val apk = resolveReleaseApk() ?: throw GradleException(
            "verifyReleasePayload: 未找到 release APK（${releaseApkFile.get().asFile}）。" +
                "该校验只认真实产物，不会以「跳过」收尾；请先执行 :app:assembleRelease 再重试。"
        )
        val problems = mutableListOf<String>()
        // 运行时必需、不得被任何排除规则波及的 assets（Sora 主题由 SoraMonarchGrammars 常量引用）。
        val requiredAssets = setOf("assets/sora-themes/shso-dark.json")
        val seenAssets = mutableSetOf<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { e ->
                val name = e.name
                if (name in requiredAssets) seenAssets += name
                if (name.startsWith("assets/sora-grammars/") || name.contains("syntax-packs")) {
                    problems += "内嵌语法包文件：$name"
                }
                if (name.startsWith("tables/")) {
                    problems += "无关数据表：$name"
                }
                if (name.startsWith("org/apache/commons/codec/language/")) {
                    problems += "未被使用的语音匹配资源：$name"
                }
                if (name == "META-INF/version-control-info.textproto") {
                    problems += "构建信息元数据：$name"
                }
                if (e.size in 1L..2_000_000L && (name.endsWith(".json") || name.contains("grammar", true))) {
                    val text = zip.getInputStream(e).use { it.readBytes().decodeToString() }
                    if ("\"tokenizer\"" in text) problems += "内嵌语法定义（含 tokenizer 字段）：$name"
                }
            }
            // 内置 POSIX 环境：存在性、体积红线、必需条目、工具链、归档版本
            val envEntry = zip.getEntry(envAssetPath)
            if (envEntry == null) {
                problems += "缺少内置 POSIX 环境：$envAssetPath"
            } else if (envEntry.size < envAssetMinBytes) {
                problems += "内置环境体积异常：${envEntry.size} 字节 < 红线 $envAssetMinBytes（疑似占位或误裁）"
            } else {
                val envEntries = runCatching { readTarEntries(zip.getInputStream(envEntry)) }.getOrElse {
                    problems += "内置环境无法解析（gzip/tar 损坏）：${it.message}"
                    emptyList()
                }
                if (envEntries.isNotEmpty()) {
                    // 软链长度为 0，不参与体积与大小下限统计；名称集合另行保留全部条目。
                    val sizes = envEntries.filterNot { it.symlink }.associate { it.name to it.size }
                    val names = envEntries.mapTo(mutableSetOf<String>()) { it.name }
                    val total = envEntries.sumOf { it.size }
                    if (total < envContentMinBytes) {
                        problems += "内置环境解包体积异常：$total 字节 < 红线 $envContentMinBytes（疑似缺二进制）"
                    }
                    // 运行时被 EmbeddedEnv / 脚本直接引用的二进制与证书
                    mapOf(
                        "bin/bash" to 400_000L,
                        "bin/busybox" to 800_000L,
                        "bin/curl" to 100_000L,
                        "bin/zip" to 50_000L,
                        "etc/cert.pem" to 50_000L,
                        "share/applets.txt" to 500L,
                        "lib/libcrypto.so.3" to 1_000_000L,
                        "lib/libcurl.so" to 300_000L,
                        "lib/libssl.so.3" to 300_000L,
                    ).forEach { (name, min) ->
                        val size = sizes[name]
                        when {
                            size == null && name !in names -> problems += "内置环境缺少必需条目：$name"
                            // 名称在但只以软链/目录形态存在，体积下限无从满足，同样视为异常。
                            size == null -> problems += "内置环境条目不是普通文件：$name（无法满足体积下限 $min）"
                            size < min -> problems += "内置环境条目异常：$name 仅 $size 字节（期望 ≥ $min）"
                        }
                    }
                    // 完整工具链存在性：软链条目只校验名称（bin/python3 等），
                    // 普通文件的大小下限由上一节负责。
                    (envRequiredToolchainNames - names).sorted().forEach {
                        problems += "内置环境缺少工具链条目：$it"
                    }
                    val libCount = envEntries.count { it.name.startsWith("lib/") && ".so" in it.name }
                    if (libCount < 10) problems += "内置环境共享库过少：$libCount 个（期望 ≥ 10）"
                    val version = envEntries.firstOrNull { it.name == "VERSION" }?.head
                        ?.toString(Charsets.UTF_8)?.substringBefore('\n')?.trim()
                    when {
                        version.isNullOrEmpty() -> problems += "内置环境缺少 VERSION 条目或内容为空"
                        !version.matches(Regex("\\d+")) -> problems += "内置环境 VERSION 非法：$version"
                        version != envArchiveVersion ->
                            problems += "内置环境版本不符：归档 $version，期望 $envArchiveVersion（归档重打包后同步 envArchiveVersion）"
                    }
                    // sh 必须来自 TermuxPrefix 的 /system/bin/sh：归档里出现 bin/sh（无论实体还是
                    // 指向 busybox 的软链）都会让 #!/.../bin/sh 脚本直接 SIGSEGV。
                    if ("bin/sh" in names) {
                        problems += "内置环境不得包含 bin/sh（应交由 TermuxPrefix 链 /system/bin/sh）"
                    }
                }
            }
        }
        (requiredAssets - seenAssets).forEach { problems += "缺少必需资源：$it" }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "release 产物校验失败：\n" + problems.joinToString("\n") { "  - $it" } +
                    "\n语法高亮包必须由用户导入，不得随 APK 分发；内置环境必须完整随包。"
            )
        }
        println("verifyReleasePayload: 通过（无语法包、无数据表、内置环境完整）")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleasePayload)
}
