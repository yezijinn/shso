# shso

[![Release](https://img.shields.io/badge/Release-20260911-00e5ff.svg?style=flat-square)](https://github.com/yezijinn/shso/releases)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20%28API%2026%2B%29-3DDC84.svg?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![ROOT](https://img.shields.io/badge/ROOT-Magisk%20%7C%20KernelSU%20%7C%20APatch-orange.svg?style=flat-square)](https://github.com/topjohnwu/Magisk)

Android ROOT 环境下的图形化执行工具：运行 `.sh` 脚本与 `.so` / ELF 原生程序，
带 ANSI 高亮终端与 stdin 交互，并集成全盘 ROOT 文件管理、文本编辑与对比、
压缩包解压、APK 提取与安装。界面为等宽字体 + 直角矩形深色主题，
冷启动直接进入主页四 Tab（主页 / 终端 / 文件 / 设置）。

> **可自定义包名 · GitHub 在线编译**：默认包名 `com.mixradio.droid` 可改为你自己的包名，用 GitHub Actions 在线一键编译 APK，无需本地环境。步骤见 [`docs/在线编译.md`](docs/在线编译.md)。

包名 `com.mixradio.droid`，版本名 `Jinn`，版本号为构建当日日期（如 `20260911`），
默认工作目录 `/data/adb/shso`。

权限模型：普通权限可完成的操作不强制 ROOT；已授权时优先使用 ROOT，
否则回退 `java.io.File`。

## 自定义包名与在线编译

默认包名 `com.mixradio.droid` 可改为你自己的包名，用 GitHub Actions 在网页上一键编译 APK，无需本地安装 Android Studio / SDK。

步骤：Fork 本仓库 → 进入 `Actions` → 运行「在线编译 APK（自定义包名）」→ 填写包名 → 下载产物。完整说明见 [`docs/在线编译.md`](docs/在线编译.md)。

## 文档

| 文档 | 内容 |
|---|---|
| `README.md`（本文件） | 功能、运行环境、构建、安全模型、版本规则 |
| `docs/PROJECT.md` | 技术栈、目录结构、架构与安全约束、安全子系统、已知注意点 |
| [`更新日志.md`](更新日志.md) | 变更清单（新增 / 修复 / 优化 / 安全，一行一条） |
| `docs/在线编译.md` | 用 GitHub Actions 自定义包名编译 APK |
| `module/shso_guard/README.md` | 运行时守卫模块：原理、策略、覆盖范围、能力边界 |
| `AGENTS.md` | 仓库的 AI 协作准则与场景导航 |
| `docs/文档规范.md` | Markdown 写作准则与文档职责边界 |

本文件不内嵌更新日志，变更一律写入 `更新日志.md`。

## 截图

| 主页 | 文件管理 |
| :---: | :---: |
| ![主页](docs/screenshots/主页.png) | ![文件](docs/screenshots/文件.png) |

| 终端 | 设置 |
| :---: | :---: |
| ![终端](docs/screenshots/终端.png) | ![设置](docs/screenshots/设置.png) |

| 安装包 | 存储占用 |
| :---: | :---: |
| ![安装包](docs/screenshots/安装包.png) | ![存储占用](docs/screenshots/存储占用.png) |

## 运行环境

| 项 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| 架构 | 仅 `arm64-v8a`；32 位 ARM 与 x86 设备无法安装 |
| ROOT | Magisk / KernelSU / APatch（执行 ROOT 脚本、访问受保护路径需要） |
| 存储 | 「所有文件访问权限」（管理外部存储需要） |

无 ROOT 时仍可浏览文件、编辑文本、解压、安装普通 APK、使用书签；
仅受保护路径与静默安装需要 ROOT。

## 安装与上手

1. 下载 [Release](https://github.com/yezijinn/shso/releases) 中的 APK 并安装，或按
   [`docs/在线编译.md`](docs/在线编译.md) 自行编译。
2. 授予「所有文件访问权限」与 ROOT 授权（设置页可随时查看与补授权）。
3. 执行目标的三种方式：
   - 主页输入绝对路径，或点「从文件管理器选择」；
   - 文件页单击文件 → 动作菜单 → 「执行」（或先「添加到 shso」）；
   - 把脚本放到 `/data/adb/shso` 后从主页或文件页执行。

## 功能

### 主页

- 仅允许执行 `.sh` 与 `.so`，其他格式拒绝并提示。
- 校验通过后调用执行引擎并跳转终端页；运行中可再次启动覆盖当前任务。
- 展示 `/data/adb/shso` 目录文件，支持上级返回与刷新；字号跟随文件列表设置。

### 终端

- ANSI 转义序列解析，支持 16 色与加粗；文字颜色可自定义。
- 状态指示：空闲「待命中」，运行中为「运行中」并带循环点动画（每 400ms 递增）。
- 顶栏：`复制输出` / `结束进程` / `重启终端` / `设置`。
- 底部输入行：`中断`（SIGINT）/ `清屏` / `历史` / `Enter`（空行）/ `发送`（stdin）。
- 命令历史：列出本次会话执行过的命令（最多 50 条、降序、相邻去重），点击回填输入框，
  支持一键清空；仅存内存不落盘（命令常含密码/token）。
- 终端设置：文字颜色、字号（5–30sp）、`HyperCore 终端提示`、`shso 终端提示`。
- 仅在视图停在底部时自动跟随新日志；日志上限 25 万字符，超出按滑动窗口截断。

### 文件

**浏览与导航**

- 快捷入口：`/`、`/storage/emulated/0`、`/data/adb/shso`。
- 顶栏：`返回 | data | storage | shso | 书签 | 设置`。
- 记忆上次浏览目录（默认开；关闭则恒回 `/storage/emulated/0`）。

**列表设置**（右上角齿轮）

- 字号 5–30sp（默认 15sp），与主页 shso 目录列表共用。
- 隐藏文件开关、排序（名称 / 时间，升降序）。
- 功能入口两列：新建文件 / 全选文件 / 搜索文件 / 提取 APK；全选只选文件不含文件夹，无文件时提示。
- 提取 APK：见下。

**提取 APK**

- 从已安装应用导出安装包到内部存储 `Download/`。
- 命名：基础包 `<应用名>-<版本号>.APK`；分包追加 `-split1.APK`、`-split2.APK`…
  （只导出基础包通常装不上，故一并导出）。
- 应用名中的 `/ \ : * ? " < > |` 与控制字符替换为 `_`。
- 默认只列用户应用，可切换「含系统应用」。
- 读取 `/data/app/...` 需要 ROOT；无 ROOT 时仅可直读的应用能提取。
- 提取后通知媒体库刷新。

**单文件动作与多选**

- 文件单击弹动作菜单（文件夹为单击进入、长按弹菜单）：`添加到 shso`、
  `安装 APK/XAPK`、`浏览图片`、`编辑文本`、`重命名`、`拷贝`、`移动`、`删除`、
  `权限/属性`、`自动解压文件`（压缩包）。
- `权限/属性`：八进制权限矩阵 + 所有者/用户组。所有者与用户组提供**账户选择器**，
  列出系统账户与已安装应用（账户名 / uid / 应用名，可搜索）；选择应用时填入数字 uid
  —— 应用账户不在 passwd 中，`chown u0_a216` 会报 unknown user。
- 长按文件进入多选模式；批量操作为删除 / 拷贝（`_n` 副本）/ 重命名 / 移动。
- 重命名与拷贝自动过滤 `..`、`\`、`\0`；移动目标由内置目录选择器确认，
  同名时可选覆盖、改名（插入 `_new`）、跳过或中止。

**APK / XAPK 安装**

- ROOT 已授权：单 APK 先 `cp` 到 `/data/local/tmp` 再 `pm install -r -d -t`；
  分包与 XAPK 走 `pm install-create/write/commit` 会话流，OBB 落位
  `/sdcard/Android/obb/<包名>/`。
- 无 ROOT：回退系统安装器（FileProvider + `ACTION_VIEW`）。
- 分包套件聚合：优先按命名约定（`<名>-<版本>.APK` + `-splitN`，即「提取 APK」的输出），
  否则按 manifest 的「同包名 + 同版本号」；定位不到唯一基础包时退回单文件安装。
- 点基础包或点任意分包都安装整套；无 ROOT 且识别为分包时直接提示需要 ROOT。

**其他**

- 图片浏览：支持 jpg/jpeg/png/bmp/gif/webp/ico/tiff/tif，可缩放、拖动、旋转。
- 字体预览：`.ttf` / `.otf` 可预览并应用为全局等宽字体。
- 新建文件：文件列表设置内（与全选 / 搜索 / 提取 APK 同行），文件名预填当前时间戳，扩展名预填 `txt`。
- 悬浮导航：`⤒` 回顶 / `⤓` 到底 / `⟳` 刷新。
- 后台长任务：任务由 `RootService` 管理，`ExecutionForegroundService` 作为前台服务哨兵，
  通知栏显示任务名、耗时、PID 与 ROOT 状态，并提供「结束进程」。

### 文本编辑器

入口：文件页「编辑文本」。

- 打开 / 编辑 / 保存 / 另存为 / 新建；未保存时关闭前提醒，支持自动保存草稿。
- 打开即可编辑（单一 Sora 编辑器，无「只读 / 编辑」切换）；文件超过 32MB 时退回稀疏行索引只读浏览（全文入内存会 OOM，不可编辑、保存、查找或对比）。
- 行号开关（超长文本自动收起）、字号 8–32sp、查找替换（带独立查找游标）、右侧细滚动条。
- 编码：自动检测（BOM → UTF-8 → GB18030 → ISO-8859-1），可手动切换，
  保存时按需写 BOM（面板内可显式开关）。
- 换行：自动识别并在保存时还原 LF / CRLF / CR。
- 语法高亮：由 Monarch 引擎提供，语法**来自外置语法包**（APK 不内置，体积优先）。编辑器「设置 → 语法包」可导入（本地 zip / 单个 JSON / 仓库直链）、停用、删除、批量启停；当前覆盖 62 种语言 / 187 个扩展名。按需加载：只解析当前文件命中的那一个语法（实测 7–47ms）；超过 20 万字符不设语法，仅保留基础配色。未导入时为无高亮纯文本，功能不受影响。
- 编辑历史：每文件最多 20 条、单条 20 万字、单文件总体积 100 万字；仅编辑态产生快照。条目标注来源（手动保存 / 停顿快照 / 定时草稿）与相对时间（刚刚 / N 分钟前 / N 天前）；同内容被更强来源覆盖时原地升级，不再出现重复条目。
- 文本处理（删除所有空行 / 整体缩进两格 / 删除所有换行）执行前先展示「行数 X → Y」影响面。

### 文本对比

入口：编辑器「对比」→ 选择另一个文件 → 选择模式 → 执行。

- 逐行对比：按行号对应，输出内容相同的行，格式 `[行号]\t内容`。
- 重复对比：忽略行号，输出两文件共同行（去重，按最小行号排序）。
- 大文件用内存映射读取；ROOT 不可直读的文件先 `cat` 到临时文件。

### 压缩包解压

- 支持 12 种格式：归档型 `zip / 7z / tar / tgz / tar.gz / tar.xz / tar.bz2 / tar.lz4`，
  单文件压缩型 `gz / xz / bz2 / lz4`。RAR 为专有格式，不支持；
  `.zst` / `.tar.zst` 已移除（zstd-jni 需为 4 个 ABI 各带一份原生库）。
- 解压规则：根目录仅一个顶层文件夹时直接解压到当前目录（避免 `abc/abc` 嵌套），
  否则新建以压缩包名（去后缀）命名的文件夹。
- 加密包（zip ZipCrypto / WinZip AES、7z）弹窗收集密码后重试。
- 重名自动追加 `_1`、`_2`…；防 Zip Slip：先词法剥离再做 `canonicalFile` 二次校验。
- 目标目录不可写时（典型为受 SELinux 限制的 ROOT 目录）禁用入口并标注原因。

### 书签

- 存于 `SharedPreferences`（`bookmarks`），跨重启保留。
- 首次运行预置 `/data/adb` 与 `/data/adb/modules` 两条常用 ROOT 目录；用户删除后不再自动加回。
- 弹窗为标题 + 可滚动列表 + 底部「添加书签」；主页与文件页共用。

### 添加到 shso

文件页单击文件 → 「添加到 shso」：复制到 `/data/adb/shso`。行为受三个开关控制：
`独立存储`（存到带时间戳的独立文件夹）、`自动删除`（删除原文件）、
`自动执行`（跳转终端立即执行）。

### 设置

- 权限区（实时检测，点击跳系统授权页）：存储空间、省电策略、后台弹出、
  超级用户、安装应用（`REQUEST_INSTALL_PACKAGES`）。
- 行为开关：独立存储 / 自动删除 / 自动执行。
- 安全档位：见「安全模型」。
- 关于：图标、版本、GitHub 链接。

## 执行引擎

`RootService` 为单例执行引擎（`SupervisorJob + Dispatchers.IO`）：

- 以 `su -c` 启动子进程并注入环境（PATH / `TERM=xterm-256color` / LANG）；
  `.so` 与二进制执行前 `chmod 755`，`.sh` 一律经 `sh` 运行且不改动用户文件权限。
- stdout / stderr 由独立协程按 16ms 微批次聚合，避免重组风暴。
- 中断（SIGINT）与「结束进程」按**进程组**发信号，回收 `su` 之下的子孙进程；
  发信号前校验目标确为进程组组长且不是本应用所在组。
- 日志超过 25 万字符按滑动窗口截断；退出码与 PID 暴露为 Compose State。
- 长任务启动 `ExecutionForegroundService`（`dataSync` 类型）作为前台服务哨兵。

## 安全模型

三层：App 侧静态审查（提示层 / 自动执行第一道门）+ `shso_guard` 运行时守卫（执行层）
+ 审计日志。

**安全档位**（设置页点击循环，即时生效）

| 档位 | 行为 |
|---|---|
| `0` 关 | 不审查、不拦截、不审计 |
| `1` 审计 | 受保护命令放行，审计记为 `DENY` |
| `2` 标准（默认） | 硬规则拦截 + 高危确认 + 脚本扫描 + 安装运行时守卫 |
| `3` 最高 | 标准防护 + 脚本默认非 ROOT 执行 + CRITICAL 需输入 `EXECUTE` |

档位 ≥2 时在 `/data/adb/modules/shso_guard` 安装运行时守卫。
执行脚本 / 程序前弹风险确认框，展示文件名、路径、类型、大小、修改时间、
SHA-256 与是否以 Root 执行。

**静态审查**（App 侧）

- 解析：词法切分 → 拆原子 → 展开 `$(...)` / 反引号 / `sh -c` / `eval`；
  剥离 `busybox` / `toybox` / `timeout` / `sudo` / `stdbuf` / `env` / `xargs` 等前缀
  （含带值选项）；提取重定向目标。
- 拦截：加密与混淆脚本（解码管道、`eval` 接解码器、解释器内联解码载荷、
  超长 base64 单行、含 NUL 的二进制内容）、格机原语（分区表与刷机工具、`mkfs`、
  `wipe`、`dd` 写块设备、`truncate` / `tee` 写系统与设备、`cp` / `mv` / `install`
  写入系统路径、写 `/proc/sysrq-trigger`）。
- 分级按来源：脚本来源命中为 `CRITICAL`（自动执行直接拒绝），
  终端来源为 `DANGEROUS`（确认后可执行）。
- fail-closed：解析超限、脚本超过 2MB 无法完整扫描、疑似加密时，
  自动执行一律拒绝。

**运行时守卫**（`module/shso_guard`，当前 v1.2.0）

- 随 APK 以 `assets/shso_guard.zip` 分发，通过 PATH 前置拦截破坏性命令，
  审计日志写入 `/data/adb/shso/audit.log`。
- 策略文件 `/data/adb/shso_guard/policy.conf`（`protect=` / `allow=` /
  `mode=enforce|log|off`），修改即时生效，优先级高于模块自带策略。
- 安装与升级为原子替换，失败保留或回滚旧版本。
- 能力边界：PATH 前置型守卫拦不到脚本内的绝对路径调用（如 `/system/bin/rm`）
  与自行重置 `PATH` 的情况。

详见 [`module/shso_guard/README.md`](module/shso_guard/README.md)。

**通用防护**

- `su -c` 参数一律单引号转义；路径过滤 `..`、`\`、`\0`。
- ROOT 鉴权带超时，避免授权管理器卡死导致 ANR 或泄漏 `su` 进程。
- 解压落盘先词法剥离再 `canonicalFile` 校验（防 Zip Slip，含符号链接逃逸）。
- 关闭 `allowBackup`。

## 构建

```bash
./gradlew :app:assembleDebug     # Debug
./gradlew :app:assembleRelease   # Release（V2+V3 签名）
./gradlew :app:testDebugUnitTest # 单元测试
python build_apk.py              # 一键构建：签名 + 版本规则 + 产物内容与体积校验
```

- release 已开启 R8 与 `shrinkResources`，资源会被重命名为随机短名，
  不要按 APK 内资源名反查源码资源。
- 只打包 `arm64-v8a`；新增带原生库的依赖时注意不要引入多 ABI。
- Windows 一键脚本 `build_apk.py` 需要外部 keystore，密码从环境变量或
  `local.properties` 读取。
- 无需本地环境时用 GitHub Actions 自定义包名编译，步骤见
  [`docs/在线编译.md`](docs/在线编译.md)。

## 版本规则

- `versionCode` = 构建当日日期（如 `20260911`），是判断是否有新版的唯一依据。
- `versionName` = `Jinn`（固定展示名）。
- GitHub 发布标签同为纯日期，与 `versionCode` 对齐。
- 设置页「检查更新」抓取 GitHub tags，提取纯数字标签取最大值与本地 `versionCode`
  比较；网络异常时提示检查网络。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
