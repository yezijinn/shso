# PROJECT.md — shso

面向开发者：技术栈、目录结构、架构与构建约束、执行模型、已知注意点。

- 功能与用法：[`README.md`](../README.md)
- 变更记录：[`更新日志.md`](../更新日志.md)
- 编写规范：[`docs/文档规范.md`](文档规范.md)
- 命名规范：[`docs/命名规范.md`](命名规范.md)
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
| UI | AndroidX Compose Material 3（compose-bom 2026.09.00）+ 自研极光玻璃主题（`ui/theme/Aurora*`） |
| 并发 | kotlinx-coroutines 1.10.1（全局 object 单例 + Compose `mutableStateOf` 驱动 UI） |
| 序列化 | kotlinx-serialization-core |
| 构建 | AGP 9.2.1，Version Catalog，开启 configuration-cache |

## 目录结构

```
shso-main/
├── CONTRIBUTING.md                     # 开发约定与场景导航
├── README.md                     # 用户向文档
├── 更新日志.md                   # 变更清单（README 不内嵌）
├── docs/PROJECT.md               # 本文档
├── docs/在线编译.md               # GitHub Actions 自定义包名编译
├── settings.gradle.kts           # 自包含工程：仅 include(":app")
├── gradle/libs.versions.toml     # 唯一版本管理入口
├── gradle.properties             # 8G JVM、R8 gradual、Dokka V2 实验开关
└── app/src/main/
    ├── AndroidManifest.xml       # MANAGE_EXTERNAL_STORAGE、QUERY_ALL_PACKAGES、allowBackup=false
    └── java/com/mixradio/droid/
        ├── data/                 # 核心逻辑层
        │   ├── RootService.kt        # ROOT 执行引擎（单例，进程组回收 + 终端命令通道）
        │   ├── RootFileManager.kt    # 全盘文件操作（危险操作统一门禁）
        │   ├── ApkInstaller.kt       # APK / XAPK 安装（单文件 + 分包会话 + OBB 事务锁）
        │   ├── ApkExtractor.kt       # 提取已安装应用安装包（纯函数可测）
        │   ├── ArchiveExtractor.kt   # 压缩包解压（防 Zip Slip + 条目预算前置）
        │   ├── ZipEntryCountProbe.kt # ZIP 中央目录条目数前置探针（读尾部 EOCD，零分配）
        │   ├── ChunkedFileReader.kt  # 大文件分段读取（128KB 载入阈值 / 32MB 只读上限）
        │   ├── ExternalOpen.kt       # 外部唤起（打开方式/分享）URI 解析、收件箱拷贝、投递 Hub
        │   ├── AnsiParser.kt         # ANSI/OSC 增量解析（私有模式 / 退格 / 行内擦除）
        │   ├── HyperCore.kt          # banner / 日志批处理（发布节流）/ 滑动窗口 / 环境信息
        │   ├── AppSettings.kt        # 设置状态（shso_settings）
        │   ├── FileItem.kt           # 文件条目模型
        │   └── security/             # shell 转义工具（ShellEscapes）
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

### 外部唤起（open-with / share）

其他应用的「打开方式 / 分享」入口由同一 `MainActivity` 的两个 `activity-alias` 承担
（`ExternalOpenActivity` / `ExternalLocateActivity`，`MainActivity` 为 `singleTask`）。
系统选择器按 component 逐行列项，故显示为两行；命中哪个 alias 由 `intent.component` 判定模式。

数据流：`Intent` → `MainActivity.parseExternalRequest`（ACTION_VIEW 取 `data`、
ACTION_SEND 取 `EXTRA_STREAM`，两者都接受 Uri 与 String）→ `ExternalOpen.resolve`
（`data/ExternalOpen.kt`，阻塞 IO 走 `Dispatchers.IO`）→ `ExternalOpenHub.pending` → 切文件页 →
`FilePage` 跳目录 + 高亮 + OPEN 模式分派。

URI 解析分三层：`file://` 直取；`com.android.externalstorage.documents` 解 `documentId`
（`primary:` → `/storage/emulated/0`）、`com.android.providers.downloads.documents` 解 `raw:`、
其余查 `_data` 列；都拿不到（不透明 FileProvider，如 QQ）则流式拷贝到
`Download/shso/` 收件箱后按副本路径处理。

动作分派**复用文件页 `FileItem` 谓词**（`isInstallable` / `isSupportedExecutable` /
`isViewableImage` / `isEditableText` / `isArchive`），不引入第二套类型分类；
已知后缀（包括 `.数字` 下载器尾缀和点文件）按文件名判定，真正无扩展名文件才以发送方 MIME 兜底
（相册分享的临时图片常无扩展名，否则会被「无扩展名 = 文本」接管而显示乱码）。
执行类直接执行，安装类弹 `InstallConfirmDialog` 确认后安装。

外部唤起压缩包不自动写盘，只定位并弹动作菜单；用户明确点击解压后才执行。解压有资源预算：
总输出 ≤1GB、单条目 ≤512MB、条目数 ≤20000；超限或失败时清理本次目标目录。
外部请求取消必须透传 `CancellationException`；`ExternalOpenHub` 以请求令牌消费，防止取消中的旧 effect 清空后来者。
隐藏文件与刚落盘的收件箱副本不依赖目录列表可见性，使用 `RootFileManager.statFilePath()` 直接取单文件属性；
stat 失败或路径非法时禁止动作分派。
安装确认阶段先把源文件复制到应用私有缓存副本并计算 SHA-256；实际安装只使用该副本，并在安装前再次校验哈希。
副本缺失、哈希不一致或哈希不可计算时拒绝安装；确认时同时锁定 ROOT 静默安装/系统安装器模式，避免共享存储文件替换或权限状态变化影响确认结果。
安装开始、成功或失败会写入 `SecurityAuditLog`，包含来源路径、确认哈希、安装模式与结果；外部 URI 收件箱使用原子文件名预占，并限制最多 256 个文件、总容量 2GB。

配置变更守卫：`MainActivity` 用 `savedInstanceState` 记录外部 intent 是否已消费，
重建时不重解析（否则重放 intent 会重复拷贝、重开编辑器）；`onNewIntent` 清除该标记，
真实再次唤起不受影响。

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

### 工程约束（改动必守）

- `su -c` 参数路径一律单引号转义。**拼进命令的每个可变片段**（含从文件名派生的
  目录名/基础名/后缀）都要各自过一次 `escapeShellArg`，不能只转义整条路径。
- 文件操作过滤 `..`、`\`、`\0`。
- ROOT 鉴权带协程超时，防止授权管理器卡死导致 ANR。
- 解压落盘先词法剥离、再 `canonicalFile` 二次校验（防 Zip Slip）；符号链接替换竞态仍需更底层的无跟随写入方案，不能仅依赖该检查。
- 中断与结束进程按进程组发信号，发信号前校验目标确为组长且不等于本应用所在组。
- **跨进程互斥必须用 `O_EXCL` 类原语，不能用「mv 到目标位置」**。POSIX 的
  `mv dir1 dir2` 在 dir2 已存在且是目录时会把 dir1 移进其内部并返回 0，
  两边都会认为成功（真机已实测）。当前唯一可用的原语是 `set -C` + `>` 重定向
  （`O_CREAT|O_EXCL`），它在 emulated/FUSE 上同样生效，而 `ln` 硬链接不行
  （跨挂载点报 `Cross-device link`）。
- **shell 里的整数比较前必须先校验操作数是纯数字**。`cut -d'|' -fN` 对
  「无分隔符」的行会整行返回，垃圾输入会让 mksh 的 `[ a -ge b ]` 报
  `unexpected operator` 并返回非零，把判定链静默导向错误分支。

### OBB 事务锁（`ApkInstaller`，XAPK 安装）

OBB 落位要跨「拷贝 → 原子改名 → APK 安装 → 失败回滚」多个步骤，需要一把跨进程的锁。

| 项 | 实现 |
|---|---|
| 锁载体 | 单个**文件** `/sdcard/Android/obb/<pkg>/.shso_install.lock`，内容单行 `token\|pid\|进程启动ticks\|created` |
| 获取 | `( set -C; printf '%s' META > LOCK )` —— `O_EXCL` 原子创建，创建即 CAS |
| 占用判定 | pid **与** 进程启动时间双校验（pid 会被系统复用），退出码 17 = 忙（可退避重试） |
| 状态不明 | 字段缺失或非纯数字 → 退出码 21，fail-closed 立即拒绝，不抢占 |
| 陈旧回收 | pid 已死 **且** 超 15 分钟 TTL → `mv` 到唯一 quarantine 名摘掉锁名，再核对隔离出的内容确实是刚判定的那把；不一致则不删并返回 21 |
| 释放 | 只在内容首字段仍是自己 token 时删 |
| 目标落位 | 同目录临时文件 `cp` → 原子 `mv`；目标已存在或为软链一律拒绝，不覆盖用户原有 OBB |
| 回滚身份 | `stat -c '%i:%s:%y'`（**纳秒 mtime**）；身份取不到时该条目不进回滚表，宁可残留也不误删 |
| 脚本可测性 | `buildObbLockAcquireScript` 为 internal 纯函数，单测对**生成的脚本文本**断言 fail-closed 分支与 CAS 收尾，避免删掉失败分支却无人察觉 |

真机验证（PACM00 / Android 10 / Magisk）：8 进程并发抢锁恰好 1 个成功、
垃圾元数据返回 21、活锁返回 17、陈旧锁被回收、外部替换目标后回滚正确放弃删除。

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

## 许可证

本项目以 **GPL-3.0-or-later**（强 Copyleft）开源，`LICENSE` 为 GPL-3.0 全文。
所有自有源文件带 `// SPDX-License-Identifier: GPL-3.0-or-later`。

第三方依赖保留各自许可，随 APK 分发；均为 GPL-3.0 兼容（Apache-2.0 单向兼容，
LGPL-2.1 允许以 GPL-3.0 组合）：

| 组件 | 许可 |
|---|---|
| Sora Editor `editor` / `language-monarch` | LGPL-2.1 |
| `io.github.dingyi222666.monarch` / `regex-lib` / moshi / okio | Apache-2.0 |
| zip4j / commons-compress / tukaani xz | Apache-2.0 / Apache-2.0 / 公有领域 |
| AndroidX / Compose / Material Color Utilities | Apache-2.0 |

## 页面与模块映射

功能清单见 `README.md`，此处只列页面与实现的对应关系。

| 页面 | 入口 | 主要依赖 |
|---|---|---|
| 主页 | `ui/pages/HomePage.kt` | `RootService`、`RootFileManager` |
| 终端 | `ui/pages/TerminalPage.kt` | `RootService`、`AnsiParser`、`HyperCore` |
| 文件 | `ui/pages/FilePage.kt` | `RootFileManager`、`ApkInstaller`、`ApkExtractor`、`ArchiveExtractor`、`ExternalOpenHub` |
| 设置 | `ui/pages/SettingsPage.kt`（+ `SettingsPagePartials.kt`） | `AppSettings` |
| 外部唤起 | `MainActivity`（两个 alias）+ `data/ExternalOpen.kt` | `ContentResolver`、`ExternalOpenHub` |

## 执行模型

命令与脚本一律直通执行：终端输入直接派发，文件执行仅校验扩展名。
原有的静态策略审查、运行时守卫、安全档位与审计日志均已移除，设置页不再有对应入口。

- **命令派发**：交互态写入常驻 shell；一次性命令走 `ProcessBuilder("su", "-c", ...)`，
  带流式回吐与可中断能力。
- **扩展名校验**：`executeFile` 只接受 `.sh` 与 `.so`，其余直接提示。
- **执行身份**：未显式指定时以 Root 执行。
- **检查更新**（`SettingsPage`）：Gitee 优先、GitHub 备选，国内网络访问 GitHub 常不可达。
  Gitee 走 `/api/v5/repos/{owner}/{repo}/tags`（JSON；网页版 `/tags` 是 405），
  GitHub 走 `/tags`（HTML）。两源归一化规则一致：只保留 6..8 位纯数字标签，
  且不剥离 `v` 前缀 —— 兼容违规写法会让发布侧的问题一直藏着，而 Gitee 的「去更新」链接
  按纯数字拼，带 `v` 必然 404。GitHub 页面链接里的仓库名是全小写，正则要忽略大小写，
  否则「有标签」会被判成「无标签」，用户看到的是假的网络异常。
  状态机五态（Idle / Checking / UpToDate / Available / NetworkError），
  成功源只用于「去更新」跳转（Gitee → `releases/tag/<最新>`，GitHub → `releases`）与日志。
- **编辑器文件阈值**：`LARGE_FILE_THRESHOLD = 128KB` 是 `loadAll` **内部**的读法分界
  （≤128KB 直读 / >128KB 分块），不再决定「可编辑 vs 只读」；唯一分界是
  `MAX_LOAD_BYTES = 32MB`，超过无法全文入 Sora 内存（OOM），退回稀疏只读浏览。
- **编码探测**（`CharsetDetector`）：BOM → **无 BOM 的 UTF-16 嗅探** → UTF-8 严格校验 →
  GB18030 → ISO-8859-1。UTF-16 嗅探需两个信号合取：① 偶/奇位 NUL 分布显著不对称
  （ASCII 文本每两字节一个 `0x00`；纯中文 UTF-16 只有换行处有 NUL，占比约 0.2，
  故阈值取 0.18 而非 0.3）② 按该编码解码后不含异常 C0 控制字符与孤立代理项。
  只用信号 ① 会被「大量 NUL 的二进制」骗过，只用 ② 会在巧合分布下误判 GB18030/UTF-8 中文。
- **外部 intent 面**：只有两个 `activity-alias`、只接受 `ACTION_VIEW` / `ACTION_SEND`。
  拷贝收件箱时文件名经净化（滤 `..`、`\`、NUL 与控制字符）、体积上限 `COPY_LIMIT_BYTES`（512MB），
  超限拒绝而非读入内存。`content://` 的读权限只在接收 intent 后的短窗口有效，故拷贝必须在解析时立即完成。

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
- 编辑历史按文件分 key 存储（`history:<绝对路径>`），旧的 `edit_history` 首次访问时
  自动迁移。
- 编辑器载入时把 CRLF / CR 归一为 LF（Compose 只按 `\n` 断行），
  保存时按 `currentLineEnding` 还原；改动 `LineEnding.apply` 需同步该契约。
- 分包安装必须走会话流，且分片先拷到 `/data/local/tmp`：`pm install-write` 直接读
  `/storage/emulated/0` 会被 SELinux 拒绝（system_server 无权读 emulated 存储）。
- **`pm install-write` 的 SPLIT_NAME 语义**（已按 AOSP `PackageInstallerSession`
  源码与真机双向核实，勿按字面理解）：参数形式是
  `install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH|-]`，但 `SPLIT_NAME`
  **不是** manifest 里的 split 名。写入期只做 `FileUtils.isValidExtFilename`
  校验（仅禁 `NUL` 与 `/`），随后按该名字落盘，**完全不解析 APK**；真实 split 名
  在 `commit` 时才由 `PackageParser.parseApkLite` 逐个读出并统一改名为
  `base.apk` / `split_<manifestSplitName>.apk`。因此：
  - 名字不能是绝对路径（含 `/` 会报 `IllegalArgumentException: Invalid name`）；
  - base 与 split 的写入**顺序无关**（一致性断言与顺序无关）；
  - 但草稿名必须**两两不同** —— `doWriteInternal` 用
    `Os.open(..., O_CREAT|O_WRONLY, 0644)` 落盘、**没有 O_EXCL**，同名两次写入会
    静默覆盖成一个文件，表现为「装上了但少一个分片」；
  - 基础包必须存在（`MODE_FULL_INSTALL` 漏写会在 commit 报
    `INSTALL_FAILED_INVALID_APK: Full install must include a base package`）。
  - `pm install-commit` 的失败会**回传到 stdout 并返回非 0**（形如
    `Failure [INSTALL_PARSE_FAILED_NOT_APK: ...]`，rc=4），可直接据此判定。
- 套件聚合（`ApkInstaller.collectApkSet`）：优先命名约定，其次 manifest 的
  「同包名 + 同版本号」；`bases.size != 1` 时退回单文件，不做猜测。
  改动需同步 `ApkInstallerSetTest`。
- 「提取 APK」依赖 `QUERY_ALL_PACKAGES`，移除会导致应用列表残缺；
  读取 `/data/app/...` 需要 ROOT。命名规则由 `ApkExtractor` 纯函数决定，
  改动需同步 `ApkExtractorTest`。
- `Process.pid()` 在 Android 上不存在，取子进程 pid 只能反射；
  中断正确性由进程组回收保证。
- **已授权 ROOT 时的存在性判定不要用 `java.io.File.exists()`**：它以应用 uid 判定，
  对 `/data/adb/` 一类受保护路径恒为 false，会让「装了 ROOT 也装不上」。
  见 `ApkInstaller.pathReadable`。
- **KDoc 里不要出现 `/**` 序列**（例如写 `` `/data/adb/**` ``）：Kotlin 块注释**可嵌套**，
  内层的 `/**` 会让外层注释永不闭合，编译报 `Unclosed comment`。
- **反引号函数名里不能出现 `.` `;` `[` `]` `/` `<` `>` `:`**（JVM 限制）。
  单测命名要避开，如 `Os.open` 要写成「写入端」。

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
| Gradle 测试报 `CreateProcess error=740` | 陈旧 daemon 的安全上下文问题，`./gradlew --stop` 后重跑 |

## 术语

| 术语 | 含义 |
|---|---|
| shso | 本项目；设备端工作目录 `/data/adb/shso` |
| Aurora | 自研极光玻璃暗色主题（`ui/theme/Aurora*`） |
| HyperCore | 执行引擎的横幅与日志批处理模块 |

## 文档维护约定

各文档职责边界以 `docs/文档规范.md` § 文档职责边界 为准，本文件不重复。统一遵循该规范：结论先行、表格优先、无 emoji 与过程叙事、数据标注出处。

- 改动功能后的同步顺序：代码 → 单测 → `更新日志.md` → `README.md` / 本文件
  （仅当影响用法或约束时）→ 提交。
- 任务看板 `TASKS*.md` 属过程记录，不参与对外文档同步。
  bash 的 `-n` 查不出 mksh 的两类陷阱：跨行模式会报 `no closing quote`，让整个文件解析失败、
  脚本转去读 stdin 而挂住；参数展开里未加引号的 `|` 会被当成模式交替符，替换不收敛直接死循环。
- 发布标签必须为纯数字 `YYYYMMDD`（禁止 `v` 前缀）；非发布包用语义前缀 + 序号。
