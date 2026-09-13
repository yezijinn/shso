
@file:Suppress("UnstableApiUsage")

rootProject.name = "shso"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Google 官方 Maven Central 镜像：本环境下 repo1.maven.org 对相当一部分制品返回 404
        // （Sora 系、moshi/okio、kotlin-stdlib-jdk8、joni/re2j 等），该镜像可达。作为通用回退源，
        // 仅在 mavenCentral 解析失败时生效，内容与中央仓库一致。
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app")
