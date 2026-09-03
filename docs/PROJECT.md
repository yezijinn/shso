# PROJECT.md — shso

## 项目定位

Android ROOT 环境下的图形化执行工具：一键运行 `.sh` 脚本与 `.so`/ELF 原生程序，带 ANSI 高亮终端、stdin 交互、ROOT 全盘文件管理。包名 `com.qihoo360.mobilesafe`，版本 9.0.2/283，默认工作目录 `/data/adb/shso`。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言/运行时 | Kotlin 2.4.0，JVM 21 |
| UI | AndroidX Compose Material 3 原生控件（compose-bom 2026.08.00，命名空间 `androidx.compose.*`）+ 自研极光玻璃主题（`ui/theme/Aurora*`，liquid-glass-aurora-ui 规范） |
| 并发 | kotlinx-coroutines 1.10.1（全局 object 单例 + Compose `mutableStateOf` 驱动 UI） |
| 序列化 | kotlinx-serialization-core |
| 构建 | AGP 9.2.1，Version Catalog，configuration-cache 开启 |

## 目录结构

```
shso-main/
├── AGENTS.md                     # AI 行为准则与导航（先读这个）
├── docs/PROJECT.md               # 本文档
├── settings.gradle.kts           # 自包含工程：仅 include(":app")
├── gradle/libs.versions.toml     # 唯一版本管理入口
├── gradle.properties             # 8G JVM、R8 gradual、Dokka V2 实验开关
└── app/
    └── src/main/
        ├── AndroidManifest.xml   # MANAGE_EXTERNAL_STORAGE、allowBackup=false
        └── java/com/qihoo360/mobilesafe/
            ├── ShsoApplication.kt
            ├── MainActivity.kt
            ├── data/             # 核心逻辑层
            │   ├── RootService.kt        # ROOT 执行引擎（单例）
            │   ├── RootFileManager.kt    # 全盘文件操作
            │   ├── PermissionChecker.kt  # ROOT 可用性探测（带超时）
            │   ├── HyperCore.kt          # banner/日志批处理/环境信息
            │   ├── AnsiParser.kt         # ANSI 转义序列解析
            │   ├── AppSettings.kt        # 设置状态（shso_settings）
            │   └── FileItem.kt           # 文件条目模型
            └── ui/
                ├── theme/        # AuroraTokens/AuroraGlass/AuroraComponents/AuroraBackground（极光玻璃令牌与组件）
                ├── components/   # DockBar、BuiltInFilePicker、ColorWheelDialog
                └── pages/        # Home / Terminal / File / Settings（四 Tab，无启动页）
```

## 架构模式

**无框架分层**：`data/` 为全局 object 单例 + Compose State，`ui/pages/` 直接订阅。

**页面承载**：`MainActivity.AppRootContent` 冷启动**直接渲染 MainContainer**（无 SPLASH/权限门状态机，无加载动画与检测文字）。`MainContainer` 内为 `HorizontalPager` 四页（主页/终端/文件/设置）+ 底部 `DockBar`；翻页用 `pagerState.animateScrollToPage`。页面间跳转（主页/文件「执行」→ 终端）通过回调切 page 1 实现。

**ROOT 门禁策略**：`rootGranted`（null/false/true，ON_RESUME 经 `PermissionChecker.hasRootAccess()` 重查）不再拦截任何页面打开；仅影响 DockBar「终端」tab 着色（未获得 ROOT 时红色提示）。终端页对无 ROOT 用户同样开放。

### 核心模块：RootService（执行引擎）

单协程域（`SupervisorJob + Dispatchers.IO`）驱动的进程管理器：

1. `su -c` 启动子进程，注入环境变量（PATH/TERM=xterm-256color/LANG），`chmod 777` 补权限
2. stdout/stderr 由独立协程读入 **16ms 微批次队列**（`ConcurrentLinkedQueue` 聚合）防 Compose 重组风暴（`HyperCore.startBatchFlushLoop`）
3. 日志超 250,000 字符触发滑动窗口截断（防 OOM，`appendWithSlidingWindow`）
4. 支持 stdin 写入、SIGINT（Ctrl+C）、`kill -9` 强杀；退出码/实时 PID 暴露为 Compose State

### UI 形态铁律（改动必守）

- **全工程零圆角**：`AuroraShapes` 五槽位全 `RoundedCornerShape(0.dp)` 注入 MaterialTheme；显式 clip/shape/border/shadow 一律 `RoundedCornerShape(0.dp)`。注意 foundation 1.12.0 缓存制品无 `RectangleShape`/`CircleShape` 符号。
- **无外层 Card/Container 容器**：列表项（图标+文本 Row）直接平铺在页面 Column，行间用细分割线或零间距分隔；设置页为单列无分组（权限 4 项 + 行为 3 项），间距全部归零、行高统一 `heightIn(min = 48.dp)`。
- **行距/字号约定**：preference 主标题 body2、summary 注释色不动；section 标题与页面主文本按要求内联 `fontSize`（非注释文本遵循 -2sp 惯例时以最近指令为准）。
- 列表项状态点/强调条等一律矩形。

### 安全约束（改动必守）

- `su -c` 参数路径一律单引号转义
- 文件操作过滤 `..`、`\`、`\0`（防路径穿越）
- ROOT 鉴权带协程超时（防授权管理器卡死 ANR）

## 外部模块

UI 层 100% 采用 AndroidX Compose Material 3 原生控件（`androidx.compose.material3`）+ `material-icons-extended`，无仓库外组合构建依赖。本项目为**自包含工程**，clone 后可直接独立构建。

影响：
- 本仓库**是自包含工程**，clone 后无需任何同级外部 UI 库即可构建
- 约定插件能力由官方 AGP/Compose 编译器插件与 `org.gradle.toolchains.foojay-resolver-convention`（`settings.gradle.kts` 的 `plugins` 块）提供

## 构建与产物

```bash
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease  # 签名 V2+V3，输出 app/build/outputs/apk/release/
```

- Windows 下推荐仓库内一键脚本：`python build_apk.py --skip-check`
- Release 签名 `E:/JinnKeyStores/Kernel.Extend/release.jks`（仓库外，V2+V3，alias=kernel.extend），debug buildType 复用 release 签名
- `isMinifyEnabled=false`（当前未混淆）
- packaging excludes 清理了 META-INF/kotlin/assets 冗余；ArtProfile 与 mergeAssets 任务被禁用

## 页面功能清单（v9.0.2）

| 页面 | 内容 |
|---|---|
| 主页 | 执行目标输入框 + 居中「立即执行」「从文件管理器选择」（无框/自适应宽度）+ 当前任务状态区 + `/data/adb/shso` 目录文件列表 |
| 终端 | 顶栏（左 IDLE/RUNNING 状态灯，右 复制输出/结束进程/重启终端/设置）；内容区为 ANSI 着色滚动日志；底部输入行 + 中断/清屏/Enter/发送；「设置」弹窗含 终端文字颜色/HyperCore 终端提示/shso 终端提示 |
| 文件 | ROOT 全盘浏览（/、/storage/emulated/0 快捷入口）、排序（名称/时间升降序）、隐藏文件开关、列表字号滑块（默认 15sp）、长按「添加到shso」、.ttf/.otf 预览并应用为软件字体、刷新/删除 |
| 设置 | 单列扁平列表：存储空间/省电策略/后台弹出/超级用户（权限状态 + 授权跳转）+ 独立存储/自动删除/自动执行开关；右上角「关于」按钮弹窗（图标/版本/Github） |

## 已知注意点

- 版本号集中在 `gradle/libs.versions.toml`；`app/build.gradle.kts` 里 `core-ktx`/`appcompat`/`coroutines-android` 三处直引坐标是历史遗留，新增依赖走 catalog
- `gradle.properties` 开启 configuration-cache，自定义 Task 配置需兼容
- compileSdk 37 超出 AGP 默认支持，靠 `android.suppressUnsupportedCompileSdk=37.0` 压警告
- 设置持久化文件名为 `shso_settings`（SharedPreferences）
