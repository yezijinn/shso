# PROJECT.md — shso

> **本文档面向开发者 / AI 协作者**：技术栈、目录结构、架构与安全约束、安全子系统设计、已知注意点。
> 功能用法与面向用户的说明见仓库根目录 [`README.md`](../README.md)；
> 变更记录见 [`更新日志.md`](../更新日志.md)；
> 运行时守卫模块见 [`module/shso_guard/README.md`](../module/shso_guard/README.md)。
> 任务看板（`TASKS*.md`）属过程记录，不在本文档维护范围内。

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
├── README.md                     # 面向用户的总览（功能 / 构建 / 安全说明）
├── 更新日志.md                   # 独立更新日志（README 不再内嵌，改动请同步此文件）
├── docs/PROJECT.md               # 本文档
├── module/shso_guard/            # 运行时守卫模块源码（与 assets/shso_guard.zip 保持一致）
│   ├── guard/common.sh           # 策略加载 / 路径归一化 / 判定 / 审计（所有守卫共用）
│   ├── guard/<cmd>               # 各命令包装器（由 gen_wrappers.py 从 guard-template.sh 生成）
│   ├── policy.conf               # 默认策略（protect= / allow= / mode=）
│   └── gen_wrappers.py           # 包装器生成器：改守卫需重新生成并重打包 zip
├── settings.gradle.kts           # 自包含工程：仅 include(":app")
├── gradle/libs.versions.toml     # 唯一版本管理入口
├── gradle.properties             # 8G JVM、R8 gradual、Dokka V2 实验开关
└── app/
    └── src/main/
        ├── AndroidManifest.xml   # MANAGE_EXTERNAL_STORAGE、QUERY_ALL_PACKAGES、allowBackup=false
        └── java/com/mixradio/droid/
            ├── ShsoApplication.kt
            ├── MainActivity.kt
            ├── data/             # 核心逻辑层
            │   ├── RootService.kt        # ROOT 执行引擎（单例，进程组回收）
            │   ├── RootFileManager.kt    # 全盘文件操作
            │   ├── ApkInstaller.kt      # APK/XAPK 安装（单文件 + 分包会话安装）
            │   ├── ApkExtractor.kt      # 提取已安装应用的安装包（纯函数可测）
            │   ├── ChunkedFileReader.kt # 大文件分段读取（128KB 阈值）
            │   ├── ArchiveExtractor.kt  # 压缩包解压（防 Zip Slip）
            │   ├── AnsiParser.kt        # ANSI 转义序列解析
            │   ├── HyperCore.kt         # banner/日志批处理/环境信息
            │   ├── AppSettings.kt       # 设置状态（shso_settings）
            │   ├── FileItem.kt          # 文件条目模型
            │   └── security/            # 安全子系统（见「安全子系统」章节）
            │       ├── CommandParser.kt         # 命令词法/原子/展开
            │       ├── PolicyEngine.kt          # 五类拦截规则 + 来源分级
            │       ├── PathClassifier.kt        # 路径四级分类
            │       ├── ScriptAuditor.kt         # 逐行脚本审查 + 自动执行门控
            │       ├── RootCommandGateway.kt    # 档位 ≤1 放行 / ≥2 评估
            │       ├── GuardModuleInstaller.kt  # 守卫原子安装与回滚
            │       ├── GuardPathPolicy.kt       # 守卫策略同步
            │       ├── SecurityAuditLog.kt      # 环形审计日志
            │       └── SecurityModels.kt        # 风险等级 / 判定模型
            └── ui/
                ├── theme/        # AuroraTokens/AuroraGlass/AuroraComponents/AuroraBackground（极光玻璃令牌与组件）
                ├── components/   # DockBar、BuiltInFilePicker、ApkExtractDialog、TextEditorDialog、CodeHighlighter 等
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
- **只打包 `arm64-v8a`**（`defaultConfig.ndk.abiFilters`，2026-09-11 起）：ROOT 玩机设备基本都是 arm64，
  去掉其余 ABI 的原生库可省约 1.4MB。**不要用 `splits.abi`** —— 产物名会变成 `app-arm64-v8a-release.apk`，
  `build_apk.py` 按 `app-release*.apk` 定位产物会失败。
- **未使用 zstd**（`.zst` / `.tar.zst`，2026-09-11 移除）：zstd-jni 的 AAR 为 4 个 ABI 各带一份原生库（约 1.9MB）。
  当前支持的 12 种格式见 `ArchiveExtractor`。
 - Release 签名：本地 keystore（仓库外，V2+V3，alias=com.mixradio.droid），debug buildType 复用 release 签名
- **产物体积参考（2026-09-11 版本，1.84MB）**：dex 约 1.59MB（88%）/ `resources.arsc` 98KB / `res/` 79KB /
  `assets/` 34KB / `lib/` 10KB。想继续瘦身只能从 dex 入手（Compose 与 `material-icons-extended`），
  `python build_apk.py` 会在每次构建后打印该构成并校验 ABI 白名单与 zstd 残留。
- **Release 已开启 R8**：`isMinifyEnabled = true` + `shrinkResources = true`（2026-09-11 起；此前为 `false`）。开启后资源会被重命名为随机短名（如 `res/RJ.png`）并剔除未引用资源，因此**不要按 APK 内的资源名反查源码资源**，应以源码 `res/` 与构建产物的映射为准。
- packaging excludes 清理了 META-INF/kotlin/assets 冗余；ArtProfile 与 mergeAssets 任务被禁用
- packaging excludes 同时排除 `org/apache/commons/codec/language/bm/**`（commons-codec 的语音匹配词典，
  约 96KB，本应用不涉及语音编码）

## 页面功能清单（Jinn / 20260911）

| 页面 | 内容 |
|---|---|
| 主页 | 执行目标输入框 + 居中「立即执行」「从文件管理器选择」（无框/自适应宽度）+ 当前任务状态区 + `/data/adb/shso` 目录文件列表 |
| 终端 | 顶栏（左 IDLE/RUNNING 状态灯，右 复制输出/结束进程/重启终端/设置）；内容区为 ANSI 着色滚动日志；底部输入行 + 中断/清屏/Enter/发送；「设置」弹窗含 终端文字颜色/HyperCore 终端提示/shso 终端提示 |
| 文件 | ROOT 全文盘浏览（/、/storage/emulated/0、/data/adb/shso 快捷入口）、排序（名称/时间升降序，纯文本选项）、隐藏文件开关、列表字号滑块（5–30sp，默认 15sp，一行布局）、**「全选文件 / 取消全选」（仅文件，不含文件夹；与「新建文件」同行）**、记忆路径、书签、**单击文件弹动作菜单**（添加到shso/安装APK·XAPK/浏览图片/编辑文本/重命名/拷贝/删除；文件夹为单击进入、长按弹菜单）、多选批量删除·拷贝·重命名（长按文件进入多选）、三悬浮导航按钮（回顶/到底/刷新）、APK·XAPK 安装（ROOT 静默 / 无 ROOT 系统安装器；**同目录存在 `-splitN` 分包时自动整套会话安装**）、图片浏览、.ttf/.otf 字体预览并应用、设置菜单内「新建文件」与**「提取APK」**（列出已安装应用，导出安装包到 `Download/`，命名 `<应用名>-<版本号>.APK`，分包追加 `-splitN.APK`；默认仅用户应用，可切换含系统应用）、刷新 |
| 设置 | 单列扁平列表：存储空间/省电策略/后台弹出/超级用户/安装应用（权限状态 + 授权跳转）+ **安全档位 0–3（点击循环，同步守卫策略）** + 独立存储/自动删除/自动执行开关 + 查看审计日志；右上角「关于」按钮弹窗（图标/版本 Jinn/Github） |

## 安全子系统

**分层模型**：App 侧静态审查（提示层 / 自动执行链路的第一道门） + `shso_guard` 运行时守卫（执行层） + 审计日志。

- **安全档位**：`0 关 / 1 审计 / 2 标准 / 3 最高`，由 `AppSettings.securityLevel` 持久化；档位 ≤1 时 `RootCommandGateway` 一律放行（验证拦截效果必须用档位 ≥2）。切换档位会失效「守卫就绪」缓存、按需安装守卫并同步 `policy.conf` 的 `mode`（`off`/`log`/`enforce`）；**冷启动时也会同步一次**（`policy.conf` 跨重装保留，残留 `off/log` 会让守卫静默不拦截）。
- **命令解析（`security/CommandParser`）**：词法切分 → 拆原子 → 展开 `$(...)`/反引号/`sh -c`/`eval`；剥离 `busybox/toybox/magisk/nohup/timeout/stdbuf/sudo/env/xargs` 前缀（含带值选项，如 `timeout 5`、`sudo -u root`）；提取重定向目标；标记 `programUnresolved`（程序名含变量，如 `r$IFSm`）与 `programAmbiguous`。超限（>32KB / >400 token / >128 原子 / 深度 >6）→ `truncated`。
- **策略（`security/PolicyEngine`）**：五类规则 —— 删除类（`rm` 族）、写入类（`dd`/`truncate`/`tee`/`cp`/`mv`/`install`/重定向）、权限类（`chmod`/`chown`）、格机类（`mkfs`/`wipe`/分区表/刷机工具/`fastboot`）、混淆类（解码器管道 / `eval` / 解释器内联解码载荷）。**风险等级按来源区分**：`SCRIPT_FILE` 的混淆类一律 `CRITICAL`（自动执行直接拒），`USER_TERMINAL` 为 `DANGEROUS`（可确认）。`CRITICAL` → `Verdict.Block`，`DANGEROUS/WARNING` → `Verdict.Confirm`。
- **路径分级（`security/PathClassifier`）**：词法归一化（解析 `.`/`..`/`//`/通配符基路径）后分四级；`/data/adb/shso`、`/data/local/tmp`、`/sdcard` 等为 SAFE，系统/设备为 CRITICAL，`/data/*` 与 `/data/adb/modules|magisk|ksu|ap` 为 DANGEROUS。
- **脚本审查（`security/ScriptAuditor`）**：逐逻辑行合并续行后解析，产出带行号的风险项；`looksEncrypted` 识别超长纯 base64 单行与 NUL/二进制内容。**门控为纯函数** `blocksUnattendedExecution(report) = report.truncated || 存在 CRITICAL`；`>2MB` 返回不可读（`SCRIPT_UNREADABLE`）。展示用风险项取 `blockingFindingsFor(report)`（可能为空，调用方不得 `first()`）。
- **运行时守卫**：`module/shso_guard/` 源码随 APK 以 `assets/shso_guard.zip` 分发，档位 ≥2 时安装到 `/data/adb/modules/shso_guard`。`guard/common.sh` 统一做策略加载 / 路径归一化（含符号链接解析）/ 判定 / 审计；各 `guard/<cmd>` 只是薄包装（决策代码禁止复制）。覆盖 `rm/rmdir/shred/truncate/wipe/dd/fastboot/mkfs*/mke2fs/make_f2fs/mv/cp/find/sed/toybox/busybox` + v1.2.0 的 `chmod/chown/chgrp/mkfs/mknod/sgdisk/parted/fdisk/flash_image`。
- **守卫安装（`security/GuardModuleInstaller`）**：先解压 APK 内 zip 到应用缓存并校验必需条目，再 root **原子替换**（同文件系统构建 `/data/adb/.shso_guard.new` → 校验 → 旧目录挪 `.old` → `mv` → 清理）；失败保留/回滚旧版。升级判定比对 `module.prop` 的 `version`，故**新增包装器必须同步 `REQUIRED_ARCHIVE_ENTRIES` 并重打包 zip**。
- **审计（`security/SecurityAuditLog`）**：`/data/adb/shso/audit.log`（无 ROOT 回退应用私有目录），512KB 环形滚动；写入前清除软链/非普通文件（该目录 0777，防止软链导致任意 root 写入）。
- **执行前确认**：脚本 / 程序执行前弹风险确认框（文件名 / 路径 / 类型 / 大小 / 修改时间 / SHA-256 / 是否 Root + 脚本风险扫描逐行结果）。
- **编辑器只读阈值**：`ChunkedFileReader.LARGE_FILE_THRESHOLD = 128KB`，超过即走只读懒加载（低端机实测：编辑框对整段文本全量排版，256KB 约 30s、2MB 数分钟无响应）。

## 已知注意点

- 版本号集中在 `gradle/libs.versions.toml`；`app/build.gradle.kts` 里 `core-ktx`/`appcompat`/`coroutines-android` 三处直引坐标是历史遗留，新增依赖走 catalog
- `gradle.properties` 开启 configuration-cache，自定义 Task 配置需兼容
- compileSdk 37 超出 AGP 默认支持，靠 `android.suppressUnsupportedCompileSdk=37.0` 压警告
- 设置持久化文件名为 `shso_settings`（SharedPreferences）
- **不要在 `LaunchedEffect` 里直接调用可挂起的滚动**（如 `listState.scrollToItem(0)`）：列表尚未组合时会一直挂起，导致同一 effect 中其后的逻辑永不执行（曾造成文件页「进入目录后空白、需手动刷新」）。需要归顶请用 `remember(key) { LazyListState() }` 重建状态。
- **守卫是 PATH 前置型**：脚本内部用绝对路径（`/system/bin/rm`）或自行重置 `PATH` 可绕过运行时守卫；这部分只由 App 侧静态审查覆盖（App 解析执行的命令），脚本内部自行拼装的调用不在内。彻底封堵需 seccomp/LSM 级 hook，属独立议题。
- **编辑历史按文件分 key 存储**（`history:<绝对路径>`），旧的单键 `edit_history` 会在首次访问时自动迁移；不要按「一个大 JSON」的假设去读 `shso_editor`。
- **文本编辑器载入时把 CRLF/CR 归一为 LF**（Compose 只按 `\n` 断行），保存时按 `currentLineEnding` 还原；改动 `LineEnding.apply` 需同步该契约。
- **分包安装必须走会话流且分片要先拷到 `/data/local/tmp`**：真机实测 `pm install-write` 直接读 `/storage/emulated/0` 会被 SELinux 拒绝（`avc denied … sdcardfs`，system_server 无权读 emulated 存储）。`ApkInstaller.installSplitApks()` 因此统一先 `cp` 到 `/data/local/tmp/_shso_split_N.apk` 再写入会话。
- **「安装套件」聚合规则**（`ApkInstaller.collectApkSet`）：主判定走命名约定（`<名>-<版本>.APK` + `-splitN`，纯函数 `nameBasedSet` 有单测覆盖），次判定走 manifest 的「同包名 + 同版本号」；`bases.size != 1` 时退回单文件，绝不猜测。改动这里需同步 `ApkInstallerSetTest`。
- **「提取 APK」依赖 `QUERY_ALL_PACKAGES`**：targetSdk 30+ 的包可见性过滤会让 `getInstalledPackages()` 只返回可见包；移除该权限会导致应用列表残缺。读取 `/data/app/...` 的安装包需 ROOT，无 ROOT 仅系统分区可直读的应用可用。产物命名规则（大写 `.APK`、分包 `-splitN`）由 `ApkExtractor` 的纯函数决定，改动需同步 `ApkExtractorTest`。
- `Process.pid()` 在 Android 上不存在，取子进程 pid 只能反射；中断正确性由**进程组回收**保证。

## 文档维护约定

| 文档 | 职责 | 不要写什么 |
|---|---|---|
| `README.md` | 面向用户的功能、构建、安全与版本说明 | **不写更新日志内容**（只保留指向 `更新日志.md` 的一行） |
| `更新日志.md` | 所有变更：新增 / 修复 / 优化 / 安全，一行一条，按日期倒序 | 不复述实现细节（细节在提交信息或本文档） |
| `docs/PROJECT.md`（本文件） | 技术栈、架构、安全子系统、构建约束、已知注意点 | 不重复 README 的功能清单 |
| `module/shso_guard/README.md` | 守卫模块的原理、策略、覆盖范围、能力边界 | 不写 App 侧静态审查细节 |

- 改动功能后同步顺序：代码 → 单测 → `更新日志.md` → `README.md` / 本文件（仅当影响用法或约束时）→ 提交。
- 任务看板 `TASKS*.md` 属过程记录，不参与对外文档同步。
- 新增守卫包装器必须三处同步：`gen_wrappers.py` 的 specs、`assets/shso_guard.zip`、
  `GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES`，并升 `module.prop` 的 `version=`。
