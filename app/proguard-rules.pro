# Copyright 2026, shso contributors
# SPDX-License-Identifier: Apache-2.0
#
# shso 自定义 ProGuard / R8 规则（isMinifyEnabled = true 时生效）
#
# 原则：只保留会被反射 / SPI / native 调用的类，其余交由 R8 混淆与删除。

# ─── commons-compress ────────────────────────────────────────────────────
# 只保留实际使用的归档与压缩实现：全量 keep 会保留 584 个类
# （arj / cpio / dump / jar / brotli / pack200 / zstandard 等均未使用）。
# SPI 工厂（ArchiveStreamFactory / CompressorStreamFactory）未使用，均为直接构造。
# 收窄后需验证 zip / 7z / tar.* 各格式解压。
-keep class org.apache.commons.compress.archivers.tar.** { *; }
-keep class org.apache.commons.compress.archivers.sevenz.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class org.apache.commons.compress.compressors.gzip.** { *; }
-keep class org.apache.commons.compress.compressors.xz.** { *; }
-keep class org.apache.commons.compress.compressors.bzip2.** { *; }
-keep class org.apache.commons.compress.compressors.lz4.** { *; }
-keep class org.apache.commons.compress.utils.** { *; }

# commons-compress 的可选传递依赖（未调用，但 R8 严格模式会报 missing class）。
#  - brotli：格式识别器之一，不调 BrotliCompressorInputStream。
#  - asm(objectweb)：pack200 支持，与解压场景无关。
#  - zstd(com.github.luben)：依赖已移除，上面的 keep 仍会保留 ZstdCompressorInputStream。
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn com.github.luben.zstd.**

# ─── org.tukaani.xz（XZ 解压，被 XZCompressorInputStream 引用）────────────
-keep class org.tukaani.xz.** { *; }

# ─── net.lingala.zip4j（ZIP 加密解密）────────────────────────────────────
# 内部为 Factory + Provider 注册，混淆会破坏 AES 提供者查找。
-keep class net.lingala.zip4j.** { *; }

# ─── 不添加宽泛 Kotlin / Compose keep ─────────────────────────────────────
# 源码未使用 kotlin.reflect、Kotlin 序列化或按名称反射应用类；
# Compose / Material3 / AndroidX 的反射入口由各 AAR 的 consumer 规则保留。
# 不要写 `-keep @kotlin.Metadata class * { *; }`：它会匹配几乎所有 Kotlin 类，
# 使应用代码失去混淆与缩减收益。

# ─── FileProvider 等 AndroidX 组件 ────────────────────────────────────────
# androidx.core 自带 keep 规则，无需手动写。
