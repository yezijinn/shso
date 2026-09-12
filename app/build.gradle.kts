// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
val buildDateVersionCode: Int = run {
    LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
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
        }
        debug {
            applicationIdSuffix = ""
            signingConfig = signingConfigs.getByName("release")
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
                "assets/**",
                "assets/dexopt/**",
                // commons-codec 的语音匹配词典（120 个 txt，约 96KB），本应用不使用。
                "org/apache/commons/codec/language/bm/**"
            )
        }
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

    // 不使用 zstd：zstd-jni 的 AAR 为 4 个 ABI 各带一份原生库，合计约 1.9MB。
    // 受影响的格式只有 .zst / .tar.zst，其余 12 种不受影响。

    // JVM 单元测试（JUnit 4，验证 CommandParser / PathClassifier / PolicyEngine / SecurityModels 纯逻辑拦截路径,
    // 不依赖设备,可在无 ROOT 真机环境下覆盖 ROOT 链路清单 #7-10 项拦截规则）
    testImplementation(libs.junit4)
}

// 启动守卫模块 zip 需要打包进 APK
tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("Assets")
}.configureEach {
    enabled = true
}
