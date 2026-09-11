# Copyright 2026, shso contributors
# SPDX-License-Identifier: Apache-2.0
#
# shso 自定义 ProGuard / R8 规则（启用 isMinifyEnabled = true 时生效）
#
# 原则：仅保留「会被反射 / SPI / native 调用」的类。其余应用代码与 Compose 依赖
# 均允许 R8 自由混淆与删未引用，以最大化 dex 体积与启动收益。

# ─── commons-compress（压缩解压核心）───────────────────────────────────────
# ArchiveStreamFactory / CompressorStreamFactory 通过 SPI + 反射加载格式识别器；
# XZCompressorInputStream 等会穿透调用 tukaani。zstd 支持已于 2026-09-11 移除，
# 故不再保留/依赖 com.github.luben.zstd。
# 按需 keep（2026-09-11 收窄）：原本的全量 keep 会保留 584 个类（含 arj / cpio / dump / jar /
# brotli / pack200 / zstandard 等本应用不用的格式），是 dex 里最大的一块。现只保留实际用到的
# 归档/压缩实现；commons-compress 的 SPI 工厂（ArchiveStreamFactory）本应用并未使用，
# 均为直接构造，故可以收窄。改动后必须真机验证 zip / 7z / tar.* 各格式解压。
-keep class org.apache.commons.compress.archivers.tar.** { *; }
-keep class org.apache.commons.compress.archivers.sevenz.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class org.apache.commons.compress.compressors.gzip.** { *; }
-keep class org.apache.commons.compress.compressors.xz.** { *; }
-keep class org.apache.commons.compress.compressors.bzip2.** { *; }
-keep class org.apache.commons.compress.compressors.lz4.** { *; }
-keep class org.apache.commons.compress.utils.** { *; }

# commons-compress 的可选 transitive 依赖（应用未调用，但 R8 严格模式会报 missing class）。
#  - brotli: 压缩格式识别器之一，应用只用 XZ / Tar / 7z / BZip2 / GZip / LZ4 / zip4j，
#    不调 BrotliCompressorInputStream；R8 会引用到但运行时不执行。
#  - asm (objectweb): commons-compress 的 pack200 支持，与本应用解压场景无关。
#  - zstd (com.github.luben): 依赖已移除（2026-09-11），但上面的 commons-compress 全量 keep
#    仍会保留 ZstdCompressorInputStream，R8 因而报缺类；该分支运行时不会走到。
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn com.github.luben.zstd.**

# ─── org.tukaani.xz（XZ 解压，被 commons-compress XZCompressorInputStream 引用）───
-keep class org.tukaani.xz.** { *; }

# ─── net.lingala.zip4j（ZIP 加密解密，ZipFile / AES / ZipCrypto Provider）────
# 内部有 Factory 模式 + Provider 注册，混淆会破坏 AES 提供者查找。
-keep class net.lingala.zip4j.** { *; }

# ─── 不添加宽泛 Kotlin / Compose keep ─────────────────────────────────────
# 本工程源码未使用 kotlin.reflect、Kotlin 序列化或按名称反射应用类；
# Compose / Material3 / AndroidX 的反射入口由各 AAR 自带 consumer ProGuard 规则保留。
# 不要写 `-keep @kotlin.Metadata class * { *; }`：它会匹配几乎所有 Kotlin 类，
# 让应用代码失去混淆与进一步缩减收益（2026-09-11 已在 mapping.txt 实测确认）。

# ─── FileProvider 等 AndroidX 组件 ────────────────────────────────────────
# androidx.core 自带 keep 规则，无需手动写。此处留空。
