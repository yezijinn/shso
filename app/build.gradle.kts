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
        versionName = "9.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            isMinifyEnabled = false
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
                "assets/dexopt/**"
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
    implementation(libs.kotlinx.serialization.core)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // 压缩包解压（zip/tar/tgz/7z 解析；本地 Gradle 缓存已具备 1.27.1，离线可构建）
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")

    // ZIP 加密解密（zip4j 支持 ZipCrypto + WinZip AES，char[] 密码天然支持中文）
    implementation("net.lingala.zip4j:zip4j:2.11.1")

    // Zstd 解压（zstd-jni Android AAR 含 arm64-v8a/armeabi-v7a/x86/x86_64 原生库）
    implementation("com.github.luben:zstd-jni:1.5.7-16@aar")

    // JVM 单元测试（JUnit 4，验证 CommandParser / PathClassifier / PolicyEngine / SecurityModels 纯逻辑拦截路径,
    // 不依赖设备,可在无 ROOT 真机环境下覆盖 ROOT 链路清单 #7-10 项拦截规则）
    testImplementation("junit:junit:4.13.2")
}

// 启动守卫模块 zip 需要打包进 APK
tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("Assets")
}.configureEach {
    enabled = true
}
