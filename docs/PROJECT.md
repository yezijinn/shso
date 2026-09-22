# PROJECT.md — shso

面向开发者与 AI 协作者：技术栈、目录结构、架构与安全约束、安全子系统、已知注意点。

- 功能与用法：[`README.md`](../README.md)
- 变更记录：[`更新日志.md`](../更新日志.md)
- 编写规范：[`docs/文档规范.md`](文档规范.md)
- 命名规范：[`docs/命名规范.md`](命名规范.md)
- 守卫模块：[`module/shso_guard/README.md`](../module/shso_guard/README.md)
- 在线编译：`docs/在线编译.md`

任务看板 `TASKS*.md` 属过程记录，不在本文档维护范围。

## 项目定位

Android ROOT 环境下的图形化执行工具：运行 `.sh` 脚本与 `.so` / ELF 原生程序，
带 ANSI 高亮终端、stdin 交互与全盘 ROOT 文件管理。
包名 `com.mixradio.droid`，`versionName = Jinn`，
`versionCode` = 构建当日日期（如 `20260922`），默认工作目录 `/data/adb/shso`。

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
        │   ├── RootService.kt        # ROOT 执行引擎（单例，进程组回收 + 终端命令通道）
        │   ├── RootFileManager.kt    # 全盘文件操作
        │   ├── ApkInstaller.kt       # APK / XAPK 安装（单文件 + 分包会话安装）
        │   ├── ApkExtractor.kt       # 提取已安装应用安装包（纯函数可测）
        │   ├── ArchiveExtractor.kt   # 压缩包解压（防 Zip Slip）
        │   ├── ChunkedFileReader.kt  # 大文件分段读取（128KB 载入阈值 / 32MB 只读上限）
        │   ├── AnsiParser.kt         # ANSI/OSC 增量解析（私有模式 / 退格 / 行内擦除）
        │   ├── HyperCore.kt          # banner / 日志批处理（发布节流）/ 滑动窗口 / 环境信息
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
   脚本任务**保持 stdin 打开**（支持交互输入）；`runCommandSync` 与终端「一次性命令」启动后立即关闭 stdin，
   避免读 stdin 的命令一直阻塞到超时。
2. 中断（SIGINT）与「结束进程」按进程组发信号（`kill -<sig> -- -<pgid>`），
   回收 `su` 之下的子孙进程；发信号前校验目标确为进程组组长且不是本应用所在组。
   「重启终端」走同一条整组回收路径——只杀 `su` 会把 `sh -c …` 与脚本进程留成 init 名下的孤儿。
3. 终端「一次性命令」（`runTerminalCommand`）与脚本任务**共用同一套「当前活动进程」状态**
   （`isTaskRunning` / `activeProcess` / `processPid` / `runPgid`）：命令期间顶栏显示「运行中」、
   「中断 / 结束进程」可用，输出经批量队列**边跑边回显**（不再全量缓冲到结束才刷出）。
   同一时刻只受理一条命令——并发会让先启动的那条失去回收句柄，故被拒绝时提示用户先中断。
   状态清理按**代际计数**判定（`terminalCommandGeneration`，新命令与新任务启动都自增），
   避免旧命令退出时把后来者误清成「待命中」；命令超 5s 自动拉起前台保活服务（秒回命令不闪通知）。
4. stdout / stderr 由独立协程按 16ms tick 收集、**≥250ms 发布节流**（`HyperCore.startBatchFlushLoop`），
   防止重组风暴；命令/任务结束时先停发布循环并等积压刷完（`stopBatchFlushLoop`）再写
   「退出码 / 已结束」文案，保证日志顺序不倒挂。
5. 日志超过 250,000 字符触发滑动窗口截断（优先**对齐换行**；整段无换行时硬截且不从代理对中间切开）；
   退出码与 PID 暴露为 Compose State。

### 终端显示约束（改动必守）

- 解析：`IncrementalAnsiParser` 把输出当字符流增量解析，跨块维护未完成行 / SGR 状态 / `\r` 光标列 /
  被块边界截断的转义序列；支持 16 色、256 色、真彩色与加粗，并消化 `\r` 原地覆盖、`\b` 退格、
  `ESC[K` 行内擦除、私有模式 CSI（`ESC[?25l/h` 等）、OSC（窗口标题 / OSC 8 超链接）与其余 C0 控制字符；
  转义序列的跨块缓冲**有 1KB 上限**（防把二进制 `cat` 到终端时待补序列无界增长）。
- **单行渲染上限 `MAX_RENDER_CHARS_PER_LINE = 4000`**：`LazyColumn` 只做**项级**虚拟化、单个 item 内部不切分，
  而 Compose `Text` 的排版成本与该行字符数成正比——实测单行 10 万字符会让主线程排版约 20 秒并触发 ANR
  （`Skipped 1210 frames` / `Davey! 20182ms`）。所有行渲染前过 `renderableLine()` 投影（截断 + 标注省略量），
  **模型层保持全文**（`plainText` / 「复制输出」不受影响）。
- 日志跟随：`followTail` 只在滚动进行中采样用户真实落点（**不可**用 `!canScrollForward` 判定，
  新内容一追加它立刻变 true，会永久停跟）；发命令与清屏时把它置回 `true`，
  否则用户上翻读日志时发出的命令，其回显与结果都落在屏外、界面看起来「点了没反应」。

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

### 文本编辑器（Sora Editor）

编辑器内核为 **Sora Editor 0.23.6**（`io.github.Rosemoe.sora-editor:editor`，LGPL-2.1；MP-Manager 同款）：
自绘 `CodeEditor` View + 行索引增量 `Content`，只渲染可视区。**打开即可编辑，不区分「只读 / 编辑」**。
文本驻留在编辑器内部，仅在保存 / 查找 / 对比 / 统计 / 历史时按需快照到 `contentValue`，
避免逐键把整篇文本折回 Compose State（那是 O(n) 拷贝）。
承载见 `ui/components/SoraTextEditor.kt`，编排见 `ui/components/TextEditorDialog.kt`。
实测（22127RK46C / Android 16，debug）：4.0 MB / 50002 行打开 841ms。

语法高亮由 **Monarch** 提供，且**语法包不入 APK**（体积优先）：编辑器只内置配色主题
`assets/sora-themes/shso-dark.json`，语法定义全部来自用户导入的语法包，存于
`filesDir/syntax/grammars/<id>.json`，元数据在 `filesDir/syntax/index.tsv`。

- 生成：`tools/gen_syntax_packs.py` 产出 **62 种语言 / 187 个扩展名**（纯 Monarch JSON），
  同时写入仓库根 `syntax-packs/` 与整包 `syntax-packs.zip`（内含 `index.json`，声明各语法的适用扩展名）。
- 导入：`ui/components/SyntaxPackDialog.kt`（编辑器「设置 → 语法包」）支持本地 zip/单个 JSON 与 https 直链；
  直链为仓库 tag 永固地址（`SyntaxPackUrls.PACK_ZIP`）。
- 校验：单文件 ≤512KB、整包 ≤2MB、SHA-256 可选、必须为含 `tokenizer` 的合法 JSON；整包任一失败即整体拒绝。
- 注册：`ui/components/SoraMonarchGrammars.kt`。**解析器顺序不可颠倒**：应用私有目录在前、assets 在后；
  `AssetsFileResolver.resolve` 对缺失路径不捕获异常，排在前面会让其抛错中断 provider 链、导致全部语法加载失败。
- 约束：Monarch 的令牌色来自主题，**主题必须覆盖语法用到的全部作用域**（含 `identifier`、`attribute`），
  未匹配的令牌会落回黑色（深底不可读）；主题不可用时降级为「不启用语法」。
- 文本超过 `HIGHLIGHT_MAX_CHARS`（20 万字符）时不设语法，仅保留基础配色。
- 未导入语法包时编辑器为无高亮的纯文本，功能不受影响。
- **编译期红线**：`app/build.gradle.kts` 的 `verifyReleasePayload` 任务在 `assembleRelease` 后强制校验——
  APK 内不得出现 `assets/sora-grammars/**`、`syntax-packs*`、`tables/**`（jcodings 编码表）以及任何含
  `"tokenizer"` 的 JSON，且体积不得超过 2.2MB；违反即中断构建。语法高亮包只能由用户导入，不得随 APK 分发。

### 巨型文件只读（稀疏索引 + 虚拟滚动）

超过 `ChunkedFileReader.MAX_LOAD_BYTES`（32MB）的文件无法全文入内存，退回只读浏览，
由稀疏行索引接管渲染，内存为 O(行数 / 1024) + O(窗口)，与文件体积无关。
关键类型：`data/SparseLineIndex.kt`（`SparseLineIndex` / `ChunkedDocument` / `IndexedLineProvider`）、
`data/FileSizeClass.kt`。方案与阈值见 [`docs/大文件只读方案.md`](大文件只读方案.md)。

## 外部依赖

UI 层全部使用 `androidx.compose.material3` + `material-icons-extended`，
无仓库外组合构建依赖，clone 后可直接构建。插件能力由官方 AGP / Compose 编译器插件与
`org.gradle.toolchains.foojay-resolver-convention` 提供。

- 文本编辑器引擎：`io.github.Rosemoe.sora-editor:editor` + `language-monarch`（**LGPL-2.1**），
  仅作库依赖使用、不改其源码；分发需随附 LGPL-2.1 许可声明。
  `language-monarch` 传递引入 `io.github.dingyi222666.monarch` / `regex-lib`（Apache-2.0）、
  `com.squareup.moshi`、`okio`，并含 oniguruma 原生库（仅打包 arm64-v8a）。
- 仓库回退源：本项目环境下 `repo1.maven.org` 对相当一部分制品返回 404（Sora 系、moshi/okio、
  `kotlin-stdlib-jdk8` 等），`settings.gradle.kts` 因此把 Google 官方 Maven Central 镜像
  （`maven-central.storage-download.googleapis.com`）作为通用回退源（内容与中央仓库一致）。
- 产物体积：编辑器引擎使 release APK 由约 1.84MB 增至约 3.40MB（2026-09-12 实测，含 oniguruma 编码表；剔除 `tables/**` 后回落至 2.09MB）；**语法包不入 APK**。

## 构建与产物

```bash
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease  # V2+V3 签名，app/build/outputs/apk/release/
python build_apk.py             # Windows 脚本（含 --skip-check）
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
- 产物体积参考（20260922，2.14MB）：dex 1.84MB（87%）/ `resources.arsc` 109KB /
  `res/` 86KB / `assets/` 72KB / `lib/` 10KB。继续瘦身只能从 dex 入手。
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

- **安全档位**：`0 无防护 / 1 仅审计 / 2 标准防护 / 3 最强防护`，由 `AppSettings.securityLevel`
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
  512KB 环形滚动；写入前清除软链与非普通文件（目录 0777，防止软链导致任意 root 写入），
  清除失败即放弃本次写入；字段经 `sanitizeField` 转义（`|` → `\u007C`、换行 → `\n`、
  剥离控制字符）以维持「一行一条记录」；轮转使用带 PID 的唯一临时名；
  配置类事件（守卫安装 / 卸载 / 改档 / 降级 / 审计清空）在档位 0 下仍留痕。
- **执行前确认**：弹风险确认框，展示文件名、路径、类型、大小、修改时间、
  SHA-256、是否以 Root 执行与脚本风险扫描结果。
- **检查更新**（`SettingsPage`）：Gitee 优先、GitHub 备选，国内网络访问 GitHub 常不可达。
  Gitee 走 `/api/v5/repos/{owner}/{repo}/tags`（JSON；网页版 `/tags` 是 405），
  GitHub 走 `/tags`（HTML）。两源归一化规则一致：只保留 6..8 位纯数字标签，
  且不剥离 `v` 前缀 —— 兼容违规写法会让发布侧的问题一直藏着，而 Gitee 的「去更新」链接
  按纯数字拼，带 `v` 必然 404。GitHub 页面链接里的仓库名是全小写，正则要忽略大小写，
  否则「有标签」会被判成「无标签」，用户看到的是假的网络异常。
  状态机五态（Idle / Checking / UpToDate / Available / NetworkError），
  成功源只用于「去更新」跳转（Gitee → `releases/tag/<最新>`，GitHub → `releases`）与日志。
- **编辑器文件阈值**：`LARGE_FILE_THRESHOLD = 128KB` 是整体载入边界（≤128KB 走 `loadAll`，超过走分块读取以支撑编辑）；`MAX_LOAD_BYTES = 32MB` 是可编辑上限，超过无法全文入 Sora 内存（OOM），退回稀疏只读浏览。

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

## 排错速查

| 现象 | 原因与处理 |
|---|---|
| 构建报 `RectangleShape` 未解析 | foundation 缓存制品无该符号，改用 `RoundedCornerShape(0.dp)` |
| Release 构建报 keystore 找不到 | keystore 在仓库外，路径由环境变量 `KEYSTORE_FILE` 或 `local.properties` 提供；新环境可用 debug 签名兜底 |
| 列表底部出现约 48dp 空白带 | 列表 Box 上多加了 `navigationBarsPadding()`，与 Scaffold inset 重复计算 |
| 文件行透过 DockBar 穿帮 | 列表 Box 少了 `padding(bottom = 56.dp)` |
| 桌面图标边缘被裁 | 前景图未缩进中心安全区（直径 ≤72dp / 108dp 画布） |
| 文件列表属性异常（链接误判为文件） | `stat` 漏 `-L`，未跟随符号链接 |
| 文件列表加载慢 | 在 shell 循环里逐条 `stat`，改为 `find ... -exec stat -L -c ... {} +` |
| 安全策略未拦截 | 检查档位是否 ≥2；`wipe` 必须走 `PolicyEngine` 独立分支，不能并入 `RM_LIKE` |
| 改了守卫源码但行为没变 | 未重新生成包装器并重打 `assets/shso_guard.zip`，或未升 `module.prop` 版本号 |
| Gradle 测试报 `CreateProcess error=740` | 陈旧 daemon 的安全上下文问题，`./gradlew --stop` 后重跑 |

## 术语

| 术语 | 含义 |
|---|---|
| shso | 本项目；设备端工作目录 `/data/adb/shso` |
| Aurora | 自研极光玻璃暗色主题（`ui/theme/Aurora*`） |
| 档位 / SecurityLevel | 安全防护强度 0–3，设置页切换，即时生效 |
| 守卫 / shso_guard | 配套 ROOT 模块，PATH 前置，运行时拦截高危命令 |
| Verdict | 安全判定结果：`Allow` / `Confirm` / `Block` |
| CommandSource | 调用者身份：`INTERNAL_APP` / `USER_TERMINAL` / `SCRIPT_FILE` |
| fail-closed | 策略异常时按最高风险处理，绝不静默放行 |
| HyperCore | 执行引擎的横幅与日志批处理模块 |

## 文档维护约定

各文档职责边界以 `docs/文档规范.md` § 文档职责边界 为准，本文件不重复。统一遵循该规范：结论先行、表格优先、无 emoji 与过程叙事、数据标注出处。

- 改动功能后的同步顺序：代码 → 单测 → `更新日志.md` → `README.md` / 本文件
  （仅当影响用法或约束时）→ 提交。
- 任务看板 `TASKS*.md` 属过程记录，不参与对外文档同步。
- 新增守卫包装器要同步四处：`gen_wrappers.py` 的 specs、`common.sh` 的 `guard_operand_mode()`
  派发表、`assets/shso_guard.zip`、`GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES`，
  同时升 `module.prop` 的 `version=`（版本不变，已装设备不会自动升级）。单测
  `required entries stay in sync with sources and bundled zip` 会逐条比对
  「源码目录 / zip / 必需清单」的内容，漏登记或忘记重新打包都会失败。
- 守卫脚本改动后先做两步验证：设备上 `sh -n common.sh`，再跑一次含 `|` 与换行的审计用例。
  bash 的 `-n` 查不出 mksh 的两类陷阱：跨行模式会报 `no closing quote`，让整个文件解析失败、
  脚本转去读 stdin 而挂住；参数展开里未加引号的 `|` 会被当成模式交替符，替换不收敛直接死循环。
- 发布标签必须为纯数字 `YYYYMMDD`（禁止 `v` 前缀）；非发布包用语义前缀 + 序号。
