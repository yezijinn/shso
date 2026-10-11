# Copyright 2026, shso contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# shso 自定义 ProGuard / R8 规则（isMinifyEnabled = true 时生效）
#
# 原则：只保留会被反射 / SPI / native 调用的类，其余交由 R8 混淆与删除。

# ─── commons-compress：保留完整归档与压缩实现 ─────────────────────────────
# 脚本和用户归档可能使用本应用当前未直接调用的格式；不要按现有 UI 入口裁剪解压实现。
# SPI/反射注册、ZIP extra fields 与 deflate64 等路径统一保留，优先保证格式兼容。
-keep class org.apache.commons.compress.** { *; }

# commons-compress 的可选传递依赖（未调用，但 R8 严格模式会报 missing class）。
#  - brotli：格式识别器之一，不调 BrotliCompressorInputStream。
#  - asm(objectweb)：pack200 支持，与解压场景无关。
# zstd-jni 已恢复，供 .zst 与 .tar.zst 解压使用。
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# ─── org.tukaani.xz（XZ 解压，被 XZCompressorInputStream 引用）────────────
-keep class org.tukaani.xz.** { *; }

# ─── net.lingala.zip4j（ZIP 加密解密）────────────────────────────────────
# 内部为 Factory + Provider 注册，混淆会破坏 AES 提供者查找。
-keep class net.lingala.zip4j.** { *; }

# ─── zstd-jni：JNI 名称与 native 注册绑定，禁止混淆 ────────────────────────
-keep class com.github.luben.zstd.** { *; }

# ─── 不添加宽泛 Kotlin / Compose keep ─────────────────────────────────────
# 源码未使用 kotlin.reflect、Kotlin 序列化或按名称反射应用类；
# Compose / Material3 / AndroidX 的反射入口由各 AAR 的 consumer 规则保留。
# 不要写 `-keep @kotlin.Metadata class * { *; }`：它会匹配几乎所有 Kotlin 类，
# 使应用代码失去混淆与缩减收益。

# ─── Sora Editor 语法高亮（monarch / moshi）──────────────────────────────
# 依赖自带 consumer 规则并自动生效，**不要再写宽泛 keep**：
#   editor.aar / language-monarch.aar 的 proguard.txt、moshi 的 META-INF/proguard/moshi.pro。
# 例如 -keep class io.github.rosemoe.sora.** { *; } 会让整个引擎失去收缩与混淆，dex 明显变大。
# 这里只补它们未覆盖的部分：Monarch 的语法/主题定义（无自带规则，经 Moshi 解析，
# 被混淆或删除会让语法 JSON 解析静默失败 → 无高亮且不崩溃）。
-keep class io.github.dingyi222666.monarch.** { *; }

# monarch 的 toKotlinDSL 工具链（把语法导出为 Kotlin 代码）依赖 kotlinpoet，本应用不调用。
-dontwarn com.squareup.kotlinpoet.**
# Sora 引擎内部对旧版 Kotlin 编译器生成的 DefaultImpls 的引用（Kotlin 2.x 已无此类）。
-dontwarn kotlin.Cloneable$DefaultImpls
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# ─── FileProvider 等 AndroidX 组件 ────────────────────────────────────────
# androidx.core 自带 keep 规则，无需手动写。
