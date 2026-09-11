# PROJECT.md — shso

面向开发者与 AI 协作者：技术栈、目录结构、架构与安全约束、安全子系统、已知注意点。

- 功能与用法：[`README.md`](../README.md)
- 变更记录：[`更新日志.md`](../更新日志.md)
- 守卫模块：[`module/shso_guard/README.md`](../module/shso_guard/README.md)
- 在线编译：`docs/在线编译.md`

任务看板 `TASKS*.md` 属过程记录，不在本文档维护范围。

## 项目定位

Android ROOT 环境下的图形化执行工具：运行 `.sh` 脚本与 `.so` / ELF 原生程序，
带 ANSI 高亮终端、stdin 交互与全盘 ROOT 文件管理。
包名 `com.mixradio.droid`，`versionName = Jinn`，
`versionCode` = 构建当日日期（如 `20260911`），默认工作目录 `/data/adb/shso`。

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 / 运行时 | Kotlin 2.4.0，JVM 21 |
| UI | AndroidX Compose Material 3（compose-bom 2026.08.00）+ 自研极光玻璃主题（`ui/theme/Aurora*`） |
| 并发 | kotlinx-coroutines 1.10.1（全局 object 单例 + Compose `mutableStateOf` 驱动 UI） |
| 序列化 | kotlinx-serialization-core |
| 构建 | AGP 9.2.1，Version Catalog，开启 configuration-cache |

## 目录结构

```
shso-main/
├── AGENTS.md                     # AI 协作准则与场景导航
├── README.md                     # 用户向文档
├── 更新日志.md                   # 变更清单（README 不内嵌）
├── docs/PROJECT.md               # 本文档
├── docs/在线编译.md               # GitHub Actions 自定义包名编译
├── module/shso_guard/            # 运行时守卫模块源码（与 assets/shso_guard.zip 一致）
│   ├── guard/common.sh           # 策略加载 / 路径归一化 / 判定 / 审计（所有守卫共用）
│   ├── guard/<cmd>               # 命令包装器（由 gen_wrappers.py 从 guard-template.sh 生成）
│   ├── policy.conf               # 默认策略（protect= / allow= / mode=）
│   └── gen_wrappers.py           # 包装器生成器：改守卫需重新生成并重打包 zip
├── settings.gradle.kts           # 自包含工程：仅 include(":app")
├── gradle/libs.versions.toml     # 唯一版本管理入口
├── gradle.properties             # 8G JVM、R8 gradual、Dokka V2 实验开关
└── app/src/main/
    ├── AndroidManifest.xml       # MANAGE_EXTERNAL_STORAGE、QUERY_ALL_PACKAGES、allowBackup=false
    └── java/com/mixradio/droid/
        ├── data/                 # 核心逻辑层
        │   ├── RootService.kt        # ROOT 执行引擎（单例，进程组回收）
        │   ├── RootFileManager.kt    # 全盘文件操作
        │   ├── ApkInstaller.kt       # APK / XAPK 安装（单文件 + 分包会话安装）
        │   ├── ApkExtractor.kt       # 提取已安装应用安装包（纯函数可测）
        │   ├── ArchiveExtractor.kt   # 压缩包解压（防 Zip Slip）
        │   ├── ChunkedFileReader.kt  # 大文件分段读取（128KB 阈值）
        │   ├── AnsiParser.kt         # ANSI 转义序列解析
        │   ├── HyperCore.kt          # banner / 日志批处理 / 环境信息
        │   ├── AppSettings.kt        # 设置状态（shso_settings）
        │   ├── FileItem.kt           # 文件条目模型
        │   └── security/             # 安全子系统，见「安全子系统」
        └── ui/
            ├── theme/        # AuroraTokens / AuroraGlass / AuroraComponents / AuroraBackground
            ├── components/   # DockBar、BuiltInFilePicker、ApkExtractDialog、TextEditorDialog 等
            └── pages/        # Home / Terminal / File / Settings（四 Tab，无启动页）
```

## 架构模式

`data/` 为全局 object 单例 + Compose State，`ui/pages/` 直接订阅，无框架分层。

- 冷启动由 `MainActivity.AppRootContent` 直接渲染 `MainContainer`（无启动页与权限门状态机）。
  `MainContainer` 为 `HorizontalPager` 四页 + 底部 `DockBar`，翻页用 `animateScrollToPage`。
- `rootGranted`（ON_RESUME 经 `PermissionChecker.hasRootAccess()` 重查）不拦截任何页面，
  仅影响 DockBar「终端」tab 着色。
- 页面间跳转（主页 / 文件「执行」→ 终端）通过回调切页实现。

### RootService（执行引擎）

单协程域（`SupervisorJob + Dispatchers.IO`）驱动的进程管理器：

1. `su -c` 启动子进程并注入环境（PATH / `TERM=xterm-256color` / LANG）；
   `.so` 与二进制执行前 `chmod 755`，`.sh` 一律经 `sh` 运行且不改动用户文件权限。
   启动后立即关闭子进程 stdin，避免读 stdin 的命令阻塞到超时。
2. 中断（SIGINT）与「结束进程」按进程组发信号（`kill -<sig> -- -<pgid>`），
   回收 `su` 之下的子孙进程；发信号前校验目标确为进程组组长且不是本应用所在组。
3. stdout / stderr 由独立协程按 16ms 微批次聚合（`HyperCore.startBatchFlushLoop`），
   防止重组风暴。
4. 日志超过 250,000 字符触发滑动窗口截断；退出码与 PID 暴露为 Compose State。

### UI 形态（改动必守）

- 全工程零圆角：`AuroraShapes` 五槽位全为 `RoundedCornerShape(0.dp)`，
  显式 clip / shape / border / shadow 同样如此。foundation 1.12.0 缓存制品无
  `RectangleShape` / `CircleShape` 符号。
- 无外层 Card / Container：列表项（图标 + 文本 Row）直接平铺在页面 Column，
  行间用细分割线或零间距分隔；设置页单列无分组，间距归零、行高 `heightIn(min = 48.dp)`。
- 状态点与强调条一律矩形。

### 安全约束（改动必守）

- `su -c` 参数路径一律单引号转义。
- 文件操作过滤 `..`、`\`、`\0`。
- ROOT 鉴权带协程超时，防止授权管理器卡死导致 ANR。
- 解压落盘先词法剥离、再 `canonicalFile` 二次校验（防 Zip Slip，含符号链接逃逸）。
- 中断与结束进程按进程组发信号，发信号前校验目标确为组长且不等于本应用所在组。

### 权限位约定

| 位置 | 权限 | 说明 |
|---|---|---|
| `/data/adb/shso` 工作区（`RootFileManager.ensureShsoDir`） | 777 | 硬性要求。需让文件管理器 / MT 管理器等第三方应用自由读写，降权会破坏「放进 shso 目录再用其他工具处理」的场景 |
| 「添加到 shso」建目录 / 拷贝 | 777 | 同上，便于其他应用读取 |
| `.so` / 二进制执行前 | 755 | `.sh` 经 `sh` 运行，不改动用户文件权限 |

判断可写性不能只看权限位：应用 uid 对 `/data/adb/` 一类路径还受 SELinux 限制，
即便 `chmod 777` 仍可能 `Permission denied`。以应用 uid 落盘的操作（如解压）
会实测目标可写性，不可写时禁用入口并标注原因。

## 外部依赖

UI 层全部使用 `androidx.compose.material3` + `material-icons-extended`，
无仓库外组合构建依赖，clone 后可直接构建。插件能力由官方 AGP / Compose 编译器插件与
`org.gradle.toolchains.foojay-resolver-convention` 提供。

## 构建与产物

```bash
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease  # V2+V3 签名，app/build/outputs/apk/release/
python build_apk.py             # Windows 一键脚本（含 --skip-check）
```

- 只打包 `arm64-v8a`（`defaultConfig.ndk.abiFilters`）。不要用 `splits.abi`：
  产物名会变成 `app-arm64-v8a-release.apk`，`build_apk.py` 按 `app-release*.apk`
  定位产物会失败。
- 不使用 zstd（`.zst` / `.tar.zst` 已移除）：zstd-jni 的 AAR 为 4 个 ABI 各带一份
  原生库，约 1.9MB。当前支持 12 种格式，见 `ArchiveExtractor`。
- Release 开启 R8（`isMinifyEnabled` + `shrinkResources`）：资源会被重命名为随机短名，
  不要按 APK 内资源名反查源码资源。
- 签名：仓库外 keystore（V2+V3，alias `com.mixradio.droid`），debug 复用 release 签名。
- packaging excludes：清理 META-INF / kotlin / assets 冗余，
  并排除 `org/apache/commons/codec/language/bm/**`（约 96KB 语音词典，本应用不使用）。
- 产物体积参考（20260911，1.84MB）：dex 1.59MB（88%）/ `resources.arsc` 98KB /
  `res/` 79KB / `assets/` 34KB / `lib/` 10KB。继续瘦身只能从 dex 入手。
  `build_apk.py` 每次构建后打印该构成，并校验 ABI 白名单与 zstd 残留。

## 页面与模块映射

功能清单见 `README.md`，此处只列页面与实现的对应关系。

| 页面 | 入口 | 主要依赖 |
|---|---|---|
| 主页 | `ui/pages/HomePage.kt` | `RootService`、`RootFileManager` |
| 终端 | `ui/pages/TerminalPage.kt` | `RootService`、`AnsiParser`、`HyperCore` |
| 文件 | `ui/pages/FilePage.kt` | `RootFileManager`、`ApkInstaller`、`ApkExtractor`、`ArchiveExtractor` |
| 设置 | `ui/pages/SettingsPage.kt`（+ `SettingsPagePartials.kt`） | `AppSettings`、安全子系统 |

## 安全子系统

分层模型：App 侧静态审查（提示层 / 自动执行第一道门）+ `shso_guard` 运行时守卫
（执行层）+ 审计日志。设计要点与能力边界见 `module/shso_guard/README.md`。

- **安全档位**：`0 关 / 1 审计 / 2 标准 / 3 最高`，由 `AppSettings.securityLevel`
  持久化，默认 2。档位 ≤1 时 `RootCommandGateway` 一律放行，
  验证拦截效果必须用档位 ≥2。切换档位会失效「守卫就绪」缓存、按需安装守卫并同步
  `policy.conf` 的 `mode`；冷启动也会同步一次（`policy.conf` 跨重装保留，
  残留 `off` / `log` 会让守卫静默不拦截）。
- **命令解析**（`CommandParser`）：词法切分 → 拆原子 → 展开 `$(...)` / 反引号 /
  `sh -c` / `eval`；剥离 `busybox` / `toybox` / `magisk` / `nohup` / `timeout` /
  `stdbuf` / `sudo` / `env` / `xargs` 前缀（含带值选项）；提取重定向目标；
  标记 `programUnresolved`（程序名含变量）与 `programAmbiguous`。
  超限（>32KB / >400 token / >128 原子 / 深度 >6）→ `truncated`。
- **策略**（`PolicyEngine`）：删除类、写入类、权限类、格机类、混淆类五类规则。
  风险等级按来源区分：`SCRIPT_FILE` 的混淆类为 `CRITICAL`（自动执行直接拒绝），
  `USER_TERMINAL` 为 `DANGEROUS`（可确认）。`CRITICAL` → `Verdict.Block`，
  `DANGEROUS` / `WARNING` → `Verdict.Confirm`。
- **路径分级**（`PathClassifier`）：词法归一化后分四级；`/data/adb/shso`、
  `/data/local/tmp`、`/sdcard` 为 SAFE，系统与设备为 CRITICAL，
  `/data/*` 与 `/data/adb/modules|magisk|ksu|ap` 为 DANGEROUS。
- **脚本审查**（`ScriptAuditor`）：合并续行后逐逻辑行解析，产出带行号的风险项；
  `looksEncrypted` 识别超长 base64 单行与 NUL / 二进制内容。
  门控为纯函数 `blocksUnattendedExecution(report) = report.truncated || 存在 CRITICAL`，
  超过 2MB 返回 `SCRIPT_UNREADABLE`。展示用风险项取 `blockingFindingsFor(report)`
  （可能为空，调用方不得 `first()`）。
- **守卫安装**（`GuardModuleInstaller`）：解压 APK 内 zip 并校验必需条目后，
  root 原子替换（构建 `.new` → 校验 → 旧目录挪 `.old` → `mv` → 清理），
  失败保留或回滚旧版。升级判定比对 `module.prop` 的 `version`。
- **审计**（`SecurityAuditLog`）：`/data/adb/shso/audit.log`（无 ROOT 回退应用私有目录），
  512KB 环形滚动；写入前清除软链与非普通文件（目录 0777，防止软链导致任意 root 写入）。
- **执行前确认**：弹风险确认框，展示文件名、路径、类型、大小、修改时间、
  SHA-256、是否以 Root 执行与脚本风险扫描结果。
- **编辑器只读阈值**：`ChunkedFileReader.LARGE_FILE_THRESHOLD = 128KB`，
  超过走只读懒加载（低端机实测：256KB 约 30s，2MB 数分钟无响应）。

## 已知注意点

- 版本集中在 `gradle/libs.versions.toml`；`app/build.gradle.kts` 中
  `core-ktx` / `appcompat` / `coroutines-android` 三处直引坐标为历史遗留，
  新增依赖走 catalog。
- `gradle.properties` 开启 configuration-cache，自定义 Task 配置需兼容。
- compileSdk 37 超出 AGP 默认支持，靠 `android.suppressUnsupportedCompileSdk=37.0` 压警告。
- 设置持久化文件名为 `shso_settings`（SharedPreferences）。
- 不要在 `LaunchedEffect` 中调用可挂起的滚动（如 `listState.scrollToItem(0)`）：
  列表未组合时会一直挂起，导致其后逻辑永不执行。需要归顶请
  `remember(key) { LazyListState() }` 重建状态。
- 守卫是 PATH 前置型：脚本内绝对路径调用（`/system/bin/rm`）或自行重置 `PATH`
  可绕过，彻底封堵需 seccomp / LSM 级 hook，属独立议题。
- 编辑历史按文件分 key 存储（`history:<绝对路径>`），旧的 `edit_history` 首次访问时
  自动迁移。
- 编辑器载入时把 CRLF / CR 归一为 LF（Compose 只按 `\n` 断行），
  保存时按 `currentLineEnding` 还原；改动 `LineEnding.apply` 需同步该契约。
- 分包安装必须走会话流，且分片先拷到 `/data/local/tmp`：`pm install-write` 直接读
  `/storage/emulated/0` 会被 SELinux 拒绝（system_server 无权读 emulated 存储）。
- 套件聚合（`ApkInstaller.collectApkSet`）：优先命名约定，其次 manifest 的
  「同包名 + 同版本号」；`bases.size != 1` 时退回单文件，不做猜测。
  改动需同步 `ApkInstallerSetTest`。
- 「提取 APK」依赖 `QUERY_ALL_PACKAGES`，移除会导致应用列表残缺；
  读取 `/data/app/...` 需要 ROOT。命名规则由 `ApkExtractor` 纯函数决定，
  改动需同步 `ApkExtractorTest`。
- `Process.pid()` 在 Android 上不存在，取子进程 pid 只能反射；
  中断正确性由进程组回收保证。

## 文档维护约定

| 文档 | 职责 | 不写 |
|---|---|---|
| `README.md` | 功能、运行环境、构建、安全模型、版本规则 | 更新日志内容 |
| `更新日志.md` | 变更清单，一行一条，日期倒序 | 实现细节 |
| `docs/PROJECT.md`（本文件） | 技术栈、架构、安全子系统、构建约束、已知注意点 | 重复 README 的功能清单 |
| `module/shso_guard/README.md` | 守卫模块原理、策略、覆盖范围、能力边界 | App 侧静态审查细节 |
| `docs/在线编译.md` | GitHub Actions 自定义包名编译步骤与 FAQ | 本地构建 |

- 改动功能后的同步顺序：代码 → 单测 → `更新日志.md` → `README.md` / 本文件
  （仅当影响用法或约束时）→ 提交。
- 任务看板 `TASKS*.md` 属过程记录，不参与对外文档同步。
- 新增守卫包装器需三处同步：`gen_wrappers.py` 的 specs、`assets/shso_guard.zip`、
  `GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES`，并升 `module.prop` 的 `version=`。
