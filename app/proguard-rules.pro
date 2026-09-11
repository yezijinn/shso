# Copyright 2026, shso contributors
# SPDX-License-Identifier: Apache-2.0
#
# shso 自定义 ProGuard / R8 规则（启用 isMinifyEnabled = true 时生效）
#
# 原则：仅保留「会被反射 / SPI / native 调用」的类。其余应用代码与 Compose 依赖
# 均允许 R8 自由混淆与删未引用，以最大化 dex 体积与启动收益。

# ─── commons-compress（压缩解压核心）───────────────────────────────────────
# ArchiveStreamFactory / CompressorStreamFactory 通过 SPI + 反射加载格式识别器；
# XZCompressorInputStream / ZstdCompressorInputStream 等会穿透调用 tukaani / zstd-jni。
-keep class org.apache.commons.compress.** { *; }

# commons-compress 的可选 transitive 依赖（应用未调用，但 R8 严格模式会报 missing class）。
#  - brotli: 压缩格式识别器之一，应用只用 XZ / Zstd / Tar / 7z / BZip2 / GZip / LZ4 / zip4j，
#    不调 BrotliCompressorInputStream；R8 会引用到但运行时不执行。
#  - asm (objectweb): commons-compress 的 pack200 支持，与本应用解压场景无关。
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# ─── org.tukaani.xz（XZ 解压，被 commons-compress XZCompressorInputStream 引用）───
-keep class org.tukaani.xz.** { *; }

# ─── net.lingala.zip4j（ZIP 加密解密，ZipFile / AES / ZipCrypto Provider）────
# 内部有 Factory 模式 + Provider 注册，混淆会破坏 AES 提供者查找。
-keep class net.lingala.zip4j.** { *; }

# ─── com.github.luben.zstd（zstd-jni，native 加载 System.loadLibrary("zstd-jni-...")）─
# JNI 注册的类名与 native 代码强耦合，混淆会导致 UnsatisfiedLinkError。
-keep class com.github.luben.zstd.** { *; }

# ─── 不添加宽泛 Kotlin / Compose keep ─────────────────────────────────────
# 本工程源码未使用 kotlin.reflect、Kotlin 序列化或按名称反射应用类；
# Compose / Material3 / AndroidX 的反射入口由各 AAR 自带 consumer ProGuard 规则保留。
# 不要写 `-keep @kotlin.Metadata class * { *; }`：它会匹配几乎所有 Kotlin 类，
# 让应用代码失去混淆与进一步缩减收益（2026-09-11 已在 mapping.txt 实测确认）。

# ─── FileProvider 等 AndroidX 组件 ────────────────────────────────────────
# androidx.core 自带 keep 规则，无需手动写。此处留空。
