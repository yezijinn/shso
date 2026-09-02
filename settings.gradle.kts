// Copyright 2026, KernelEX contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("UnstableApiUsage")

rootProject.name = "KernelEX"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("../../MIUIX/build-plugins")
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
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app")
include(":miuix-core")
project(":miuix-core").projectDir = file("../../MIUIX/miuix-core")
include(":miuix-ui")
project(":miuix-ui").projectDir = file("../../MIUIX/miuix-ui")
include(":miuix-preference")
project(":miuix-preference").projectDir = file("../../MIUIX/miuix-preference")
include(":miuix-icons")
project(":miuix-icons").projectDir = file("../../MIUIX/miuix-icons")
include(":miuix-blur")
project(":miuix-blur").projectDir = file("../../MIUIX/miuix-blur")
include(":miuix-squircle")
project(":miuix-squircle").projectDir = file("../../MIUIX/miuix-squircle")
include(":miuix-shader")
project(":miuix-shader").projectDir = file("../../MIUIX/miuix-shader")
