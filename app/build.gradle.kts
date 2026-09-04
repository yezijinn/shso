// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // 等价原 module.kotlin-jvm-toolchain 约定插件：统一 Kotlin JVM Toolchain 21
    jvmToolchain(21)
}

android {
    namespace = "com.qihoo360.mobilesafe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qihoo360.mobilesafe"
        minSdk = 26
        targetSdk = 35
        versionCode = 283
        versionName = "9.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 真实签名密钥位于本机 E 盘（与 build_apk.py 的 SIGNING 配置保持一致）
            storeFile = file("E:/JinnKeyStores/Kernel.Extend/release.jks")
            storePassword = "WE1A1xus0n9."
            keyAlias = "kernel.extend"
            keyPassword = "WE1A1xus0n9."
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
}

tasks.matching {
    it.name.contains("ArtProfile") || (it.name.startsWith("merge") && it.name.endsWith("Assets"))
}.configureEach {
    enabled = false
}
