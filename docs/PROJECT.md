# PROJECT.md — KernelEX

## 项目定位

Android ROOT 环境下的图形化执行工具：一键运行 `.sh` 脚本与 `.so`/ELF 原生程序，带 ANSI 高亮终端、stdin 交互、ROOT 全盘文件管理。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言/运行时 | Kotlin 2.4.0，JVM 21 |
| UI | JetBrains Compose Multiplatform 1.11.1 + MIUIX 组件库（HyperOS 质感） |
| 并发 | kotlinx-coroutines 1.10.1（全局 object 单例 + Compose `mutableStateOf` 驱动 UI） |
| 序列化 | kotlinx-serialization-core |
| 构建 | AGP 9.2.1，Version Catalog，configuration-cache 开启 |

## 目录结构

```
shso-main/
├── AGENTS.md                     # AI 行为准则与导航（先读这个）
├── docs/PROJECT.md               # 本文档
├── settings.gradle.kts           # 关键：以 ../../MIUIX 相对路径引入外部模块
├── gradle/libs.versions.toml     # 唯一版本管理入口
├── gradle.properties             # 8G JVM、R8 gradual、Dokka V2 实验开关
└── app/
    └── src/main/
        ├── AndroidManifest.xml   # MANAGE_EXTERNAL_STORAGE、allowBackup=false
        └── java/Kernel/Extend/
            ├── KernelEXApplication.kt
            ├── MainActivity.kt
            ├── data/             # 核心逻辑层
            │   ├── RootService.kt        # ROOT 执行引擎（单例）
            │   ├── RootFileManager.kt    # 全盘文件操作
            │   ├── HyperCore.kt          # 常量/banner/环境变量注入
            │   ├── AnsiParser.kt         # ANSI 转义序列解析
            │   ├── AppSettings.kt        # 设置状态
            │   └── FileItem.kt           # 文件条目模型
            └── ui/
                ├── components/   # DockBar、BuiltInFilePicker、ColorWheelDialog
                └── pages/        # Splash → PermissionGate → Home / File / Terminal / Settings
```

## 架构模式

**无框架分层**：`data/` 为全局 object 单例 + Compose State，`ui/pages/` 直接订阅。页面导航由 MIUIX Navigation3 承载，流程为 SplashPage（权限引导）→ PermissionGatePage（ROOT/存储授权门）→ 主功能三页。

### 核心模块：RootService（执行引擎）

单协程域（`SupervisorJob + Dispatchers.IO`）驱动的进程管理器：

1. `su -c` 启动子进程，注入环境变量（PATH/TERM=xterm-256color/LANG），`chmod 777` 补权限
2. stdout/stderr 由独立协程读入 **16ms 微批次队列**（`ConcurrentLinkedQueue` 聚合）防 Compose 重组风暴
3. 日志超 250,000 字符触发滑动窗口截断（防 OOM）
4. 支持 stdin 写入、SIGINT（Ctrl+C）、`kill -9` 强杀；退出码/实时 PID 暴露为 Compose State

### 安全约束（改动必守）

- `su -c` 参数路径一律单引号转义
- 文件操作过滤 `..`、`\`、`\0`（防路径穿越）
- ROOT 鉴权带协程超时（防授权管理器卡死 ANR）

## 外部模块（MIUIX）

`settings.gradle.kts` 将 `:miuix-*` 系列映射到仓库外 `D:\WorkBuddy_Project\MIUIX\`（相对路径 `../../MIUIX`），同时 includeBuild 其 `build-plugins`（提供 `module.kotlin-jvm-toolchain` 约定插件）。注意：不要把 MIUIX 的 `baselineprofile` include 进本项目构建树——它依赖 MIUIX 自己的 `:example:android` 子项目，在本项目中不可解析。

影响：
- 本仓库**不是自包含工程**，clone 后必须保证同级存在 MIUIX 仓库
- 升级 MIUIX API 可能破坏本项目编译，属跨仓库变更

## 构建与产物

```bash
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease  # 签名 V2+V3，输出 app/build/outputs/apk/release/
```

- Release 签名 `KernelEX.jks`（仓库外），debug buildType 复用 release 签名
- `isMinifyEnabled=false`（当前未混淆）
- packaging excludes 清理了 META-INF/kotlin/assets 冗余；ArtProfile 与 mergeAssets 任务被禁用

## 已知注意点

- 版本号集中在 `gradle/libs.versions.toml`；`app/build.gradle.kts` 里 `core-ktx`/`appcompat`/`coroutines-android` 三处直引坐标是历史遗留，新增依赖走 catalog
- `gradle.properties` 开启 configuration-cache，自定义 Task 配置需兼容
- compileSdk 37 超出 AGP 默认支持，靠 `android.suppressUnsupportedCompileSdk=37.0` 压警告
