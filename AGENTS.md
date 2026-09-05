> 语言 / Language: [English](AGENTS.en.md)

# shso — AI 开发文档

Android ROOT 环境下的图形化脚本/原生二进制执行工具（Kotlin + Jetpack Compose Material 3 原生控件，极光玻璃暗色主题，无外部 UI 组件库）。包名 `com.mixradio.droid`，版本 9.0.2/283。冷启动直进主页四 Tab（主页/终端/文件/设置），无启动检测流程。

## 行为准则

### 1. Think Before Coding
**不假设、不隐藏困惑、展示权衡。**
- 开发前先读项目文档，了解结构、规范和约束
- 按场景导航表按需定位文档，只读任务相关的，不一次性读完
- 先按文档执行，未覆盖时再搜索代码/查阅资料
- 不确定时先问，不要猜测
- 存在多种理解时，列出所有可能
- 有更简单的方案就提出来
- 搞不明白就停下来，说清楚哪里不明白

### 2. Simplicity First
**最小代码解决问题，不做推测性设计。**
- 不做需求范围外的功能
- 单次使用的代码不做抽象
- 没要求的"灵活性"和"可配置性"不加
- 不为不可能的场景写错误处理
- 避免重复：相同逻辑出现 2+ 次时考虑抽象，说明取舍

### 3. Surgical Changes
**只改必须改的，清理自己制造的混乱。**
- 不"顺手优化"周边无关代码
- 不重构没坏的东西
- 遵循现有风格，即使你不认同
- 发现无关死代码提出来，不删

### 4. Goal-Driven Execution
**定义成功标准，循环验证。**
- "加验证" → "先想清楚什么叫通过，再实现"
- "修bug" → "先找到最小复现路径，再修复"
- "重构X" → "确保行为不变，可用 diff 辅助验证"
- 多步任务列出计划：1. [步骤] → verify: [检查]

## 技术栈概览

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.4.0（JVM Toolchain 21） |
| UI | AndroidX Compose Material 3 原生控件（compose-bom 2026.08.00，导入命名空间 `androidx.compose.*`，`ui/theme/Aurora*` 极光玻璃主题） |
| 构建 | Gradle (KTS) + AGP 9.2.1 + Version Catalog（`gradle/libs.versions.toml`） |
| 目标 | minSdk 26 / targetSdk 35 / compileSdk 37，applicationId `com.mixradio.droid` |
| 依赖注入 | 无框架，全局 `object` 单例（`RootService`、`AppSettings` 等） |

**自包含工程（关键约束）**：不依赖任何仓库外源码/模块，clone 后可直接独立构建。`settings.gradle.kts` 仅 `include(":app")`；一键构建脚本 `build_apk.py` 内含原生 Material 3 校验（禁止回退到外部 UI 组件库）。UI 样式统一走 `ui/theme/` 下的 Aurora 令牌（`AuroraTokens`/`AuroraGlass`/`AuroraComponents`），页面禁止写装饰性 `Color(0x...)` 字面量。

**UI 形态铁律（改动必守）**：
- 全工程零圆角：所有 Card/Button/TextField/弹窗/面板/状态点/DockBar 一律直角矩形。实现：① `AuroraShapes`（M3 Shapes 五槽位 `RoundedCornerShape(0.dp)`）注入 `MaterialTheme`；② 显式 `clip/shape/shadow/border` 全用 `RoundedCornerShape(0.dp)`。`foundation 1.12.0` 缓存制品 shape 包**无 `RectangleShape`/`CircleShape` 符号**，勿 import。
- 禁止外层 Card/Container 容器包裹列表项：设置页/文件页/主页均为无容器列表，行内容（Row 图标+文本）直接置于页面 Column。
- 页面文案按需求用字号内联（如 section 标题 14sp、preference 主标题 body2、summary 用注释色）；行高统一 `heightIn(min = 48.dp)`；分隔线用 0.7dp 细线（`SurfaceHover.copy(0.6f)`）或纯零间距。
- **自适应图标（Adaptive Icon）**：`AndroidManifest.xml` 的 `icon`/`roundIcon` 指向 `@mipmap/ic_launcher`（background + foreground 两层）；背景透明、前景为去白去黑后的彩色 PNG，缩进中心安全区（≤72dp）确保圆形/圆角矩形/水滴等 OEM mask 下完整显示，无需为不同形状单独出图。

## 场景导航

| 场景 | 阅读文档 |
|---|---|
| 了解技术栈、目录结构、架构 | `docs/PROJECT.md` |
| 改执行引擎/ROOT 逻辑 | `docs/PROJECT.md` § 核心模块 |
| 改 UI 页面/组件 | `docs/PROJECT.md` § UI 层 |
| 加依赖/改版本 | `gradle/libs.versions.toml`（禁止在模块内硬编码版本，例外：`app/build.gradle.kts` 中已有的 3 个直引坐标） |

## 常用命令

```bash
./gradlew :app:assembleDebug     # Debug APK
./gradlew :app:assembleRelease   # Release APK（输出在 app/build/outputs/apk/）
```

## 注意事项

- 代码注释/提交已有惯例：文件头带 `// Copyright 2026, shso contributors` + `SPDX-License-Identifier: Apache-2.0`
- 签名配置在 `app/build.gradle.kts`（V2+V3，debug 复用 release 签名）；`release.jks` 不在仓库内
- 所有 `su -c` 路径必须单引号转义（`replace("'", "'\\''")`）；路径处理必须过滤 `..`、`\`、`\0`（防注入/穿越）——改动 RootFileManager / RootService 时强制保持
- `allowBackup=false`，勿开启
- Windows 下构建路径过长时使用 `\\?\` 前缀
