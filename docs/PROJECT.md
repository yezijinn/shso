# PROJECT.md — shso

## 项目定位

Android ROOT 环境下的图形化执行工具：一键运行 `.sh` 脚本与 `.so`/ELF 原生程序，带 ANSI 高亮终端、stdin 交互、ROOT 全盘文件管理。包名 `com.mixradio.droid`，`versionName = Jinn`，`versionCode` = 构建当日日期（如 `20260911`），默认工作目录 `/data/adb/shso`。

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
        └── java/com/mixradio/droid/
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

1. `su -c` 启动子进程，注入环境变量（PATH/TERM=xterm-256color/LANG）；`.so` / 二进制执行前 `chmod 755`，`.sh` 一律经 `sh` 运行**不改动用户文件权限**
   - 进程启动后立即关闭子进程 stdin，避免读 stdin 的命令阻塞到超时才返回
   - 中断（SIGINT）与「结束进程」按**进程组**（`kill -<sig> -- -<pgid>`）发信号，回收 `su` 之下的子孙进程；发信号前校验目标确为进程组组长、且不是本应用所在组（fail-closed）
2. stdout/stderr 由独立协程读入 **16ms 微批次队列**（`ConcurrentLinkedQueue` 聚合）防 Compose 重组风暴（`HyperCore.startBatchFlushLoop`）
3. 日志超 250,000 字符触发滑动窗口截断（防 OOM，`appendWithSlidingWindow`）
4. 支持 stdin 写入、SIGINT（Ctrl+C）、`kill -9` 强杀；退出码/实时 PID 暴露为 Compose State

### UI 形态铁律（改动必守）

- **全工程零圆角**：`AuroraShapes` 五槽位全 `RoundedCornerShape(0.dp)` 注入 MaterialTheme；显式 clip/shape/border/shadow 一律 `RoundedCornerShape(0.dp)`。注意 foundation 1.12.0 缓存制品无 `RectangleShape`/`CircleShape` 符号。
- **无外层 Card/Container 容器**：列表项（图标+文本 Row）直接平铺在页面 Column，行间用细分割线或零间距分隔；设置页为单列无分组（权限 5 项 + 行为 3 项），间距全部归零、行高统一 `heightIn(min = 48.dp)`。
- **行距/字号约定**：preference 主标题 body2、summary 注释色不动；section 标题与页面主文本按要求内联 `fontSize`（非注释文本遵循 -2sp 惯例时以最近指令为准）。
- 列表项状态点/强调条等一律矩形。

### 安全约束（改动必守）

- `su -c` 参数路径一律单引号转义
- 文件操作过滤 `..`、`\`、`\0`（防路径穿越）
- ROOT 鉴权带协程超时（防授权管理器卡死 ANR）
- 解压落盘先词法剥离、再 `canonicalFile` 二次校验（防 Zip Slip，含符号链接逃逸）
- 中断 / 结束进程按**进程组**发信号，且发信号前校验目标确为组长、不等于本应用所在组（fail-closed）

### 权限位约定（勿随意"收紧"）

| 位置 | 权限 | 状态 |
|---|---|---|
| `/data/adb/shso` 工作区（`RootFileManager.ensureShsoDir`） | **777** | **硬性要求，禁止改为 755**。需让其他应用（文件管理器 / MT 管理器等）自由读写其中文件；降权会直接破坏「放进 shso 目录再用别的工具处理」的使用场景 |
| 「添加到 shso」建目录 / 拷贝（`RootFileManager` 约 400 / 407 行） | **777** | **刻意保留，勿改**。同上，便于其他应用读取 |
| `.so` / 二进制执行前（`RootService`） | **755** | 保持。`.sh` 一律经 `sh` 运行，不改动用户文件权限 |

> 提示：判断能否写入不能只看权限位 —— 应用 uid 对 `/data/adb/` 一类路径还受 **SELinux(MAC)** 限制，实测即便 `chmod 777`，应用自身仍 `Permission denied`。因此「解压」等以应用 uid 落盘的操作会先实测目标可写性，不可写时禁用入口并标注「当前目录不可写」。

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
 - Release 签名：本地 keystore（仓库外，V2+V3，alias=com.mixradio.droid），debug buildType 复用 release 签名
- **Release 已开启 R8**：`isMinifyEnabled = true` + `shrinkResources = true`（2026-09-11 起；此前为 `false`）。开启后资源会被重命名为随机短名（如 `res/RJ.png`）并剔除未引用资源，因此**不要按 APK 内的资源名反查源码资源**，应以源码 `res/` 与构建产物的映射为准。
- packaging excludes 清理了 META-INF/kotlin/assets 冗余；ArtProfile 与 mergeAssets 任务被禁用

## 页面功能清单（Jinn / 20260911）

| 页面 | 内容 |
|---|---|
| 主页 | 执行目标输入框 + 居中「立即执行」「从文件管理器选择」（无框/自适应宽度）+ 当前任务状态区 + `/data/adb/shso` 目录文件列表 |
| 终端 | 顶栏（左 IDLE/RUNNING 状态灯，右 复制输出/结束进程/重启终端/设置）；内容区为 ANSI 着色滚动日志；底部输入行 + 中断/清屏/Enter/发送；「设置」弹窗含 终端文字颜色/HyperCore 终端提示/shso 终端提示 |
| 文件 | ROOT 全文盘浏览（/、/storage/emulated/0、/data/adb/shso 快捷入口）、排序（名称/时间升降序，纯文本选项）、隐藏文件开关、列表字号滑块（5–30sp，默认 15sp，一行布局）、**「全选文件 / 取消全选」（仅文件，不含文件夹；与「新建文件」同行）**、记忆路径、书签、长按单文件动作（添加到shso/安装APK·XAPK/浏览图片/编辑文本/重命名/拷贝/删除）、多选批量删除·拷贝·重命名、三悬浮导航按钮（回顶/到底/刷新）、APK·XAPK 安装（ROOT 静默 / 无 ROOT 系统安装器）、图片浏览、.ttf/.otf 字体预览并应用、设置菜单内「新建文件」、刷新 |
| 设置 | 单列扁平列表：存储空间/省电策略/后台弹出/超级用户/安装应用（权限状态 + 授权跳转）+ **安全档位 0–3（点击循环，同步守卫策略）** + 独立存储/自动删除/自动执行开关 + 查看审计日志；右上角「关于」按钮弹窗（图标/版本 Jinn/Github） |

## 安全子系统

- **安全档位**：`0 关 / 1 审计 / 2 标准 / 3 最高`，由 `AppSettings.securityLevel` 持久化；切换时失效「守卫就绪」缓存、按需安装守卫并同步 `/data/adb/shso_guard/policy.conf` 的 `mode`（`off` / `log` / `enforce`）。
- **运行时守卫**：仓库内 `module/shso_guard/` 为守卫模块源码（随 APK 以 `assets/shso_guard.zip` 分发），档位 ≥2 时安装到 `/data/adb/modules/shso_guard`，拦截破坏性写操作并落审计日志 `/data/adb/shso/audit.log`。
- **执行前确认**：脚本 / 程序执行前弹风险确认框（文件名 / 路径 / 类型 / 大小 / 修改时间 / SHA-256 / 是否 Root）。
- **编辑器只读阈值**：`ChunkedFileReader.LARGE_FILE_THRESHOLD = 128KB`，超过即走只读懒加载（低端机实测：编辑框对整段文本全量排版，256KB 约 30s、2MB 数分钟无响应）。

## 已知注意点

- 版本号集中在 `gradle/libs.versions.toml`；`app/build.gradle.kts` 里 `core-ktx`/`appcompat`/`coroutines-android` 三处直引坐标是历史遗留，新增依赖走 catalog
- `gradle.properties` 开启 configuration-cache，自定义 Task 配置需兼容
- compileSdk 37 超出 AGP 默认支持，靠 `android.suppressUnsupportedCompileSdk=37.0` 压警告
- 设置持久化文件名为 `shso_settings`（SharedPreferences）
