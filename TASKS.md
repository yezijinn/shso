# shso 任务看板 (TASKS.md)

> 过程记录，不参与对外文档同步；现行说明见 `README.md`、`docs/PROJECT.md`、`更新日志.md`。
> 建立时间：2026-09-11（上一版归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 版本规则

`versionCode` = 构建当日日期（`YYYYMMDD`），`versionName` = `Jinn`，Release Tag 与 `versionCode` 对齐；升级判定只认 `versionCode`。完整规则见 `README.md` § 版本规则。

---

## 当前状态速览（2026-11-07）

| 项 | 值 |
|---|---|
| 分支 | `main`，与 GitHub / Gitee 双端 `main` 同步；最新 tag `20261014` → `796de4c`（2026-10-14 发版提交），其后 HEAD `41b78e3`（A150）只在 `main`；`20261013` 与 `b3d7739` 见 § A149 |
| 许可 | **GPL-3.0-or-later**（2026-10-02 由 Apache-2.0 切换，强 Copyleft） |
| 单元测试 | 717 tests / 0 failures / 0 errors / 1 skipped（A151 `testDebugUnitTest`，XML 汇总与源码 `@Test` 计数一致） |
| lint | 0 errors（A151 `lintRelease` BUILD SUCCESSFUL） |
| release 体积 | **33,438,105 字节**（约 33.4 MB，十进制），sha256 `418FBF2E6DD49651CA613F5D3CF5E050A03C266A0A0059A991D84FF8DBE362C1`；`verifyReleasePayload` BUILD SUCCESSFUL（输出「内置环境完整」）；`assets/env.bin` 30,513,138 字节（VERSION 6 / 1635 条目 / 373 软链，已剔除 magic.mgc、scalar、git 服务端辅助程序与 pydoc_data） |
| 执行环境 | **全量内置**（A151）：`assets/env.bin` VERSION 6 携带完整 aarch64 工具链 —— bash / busybox（358 applet）/ curl / GNU 与 coreutils / git（客户端）/ python3 / openssl / ssh / rsync，首次启动解压到私有目录；归档内 `bin/sh` 不由 busybox 提供（busybox sh applet 直跑会 SIGSEGV，applets 亦不含 sh），前缀缺 sh 时由 `TermuxPrefix` 建链指向 `/system/bin/sh`，真机已验证；Termux 前缀符号链接避免 `LD_LIBRARY_PATH` 污染；`GIT_PAGER=cat` 规避未内置分页器导致的 `fatal: unable to execute pager`；可下载扩展包已删除，不再有「按设计缺失」的工具 |
| 执行矩阵 | A151 真机（127.0.0.1:16384 / Redmi K70 23113RKC6C）安装成功、MainActivity 前台稳定；首解后 `VERSION=6`，`env.tmp`/`env.old`/`envpack` 均不存在，旧 `env/bin/sh` 缺失，前缀 `sh`/`ash`/`hush`/`mash` 全部链到 `/system/bin/sh`；应用执行绝对 shebang `#!/data/data/com.termux/files/usr/bin/sh` 输出 `ABS_SH_OK`；绝对 `python3.14`/`python3`/`python`/`env`/`sh`/`bash` 与 `libcrypto`/`libz` 链接在位，`libexec/git-core`、`share/git-core`、`etc/tls/openssl.cnf`、`lib/python3.14` 补链到位；执行链不再导出 `LD_LIBRARY_PATH`；Python 3.14.6（含 sqlite3/ssl）、git 2.56.0（含 `git init/add/commit/log`）、OpenSSL 3.6.5、OpenSSH 10.6p1、rsync 3.5.1、GNU sed/tar/wget/grep/find、zstd 1.5.7、xz 5.8.4 均执行成功；日志无 FATAL EXCEPTION / CANNOT LINK / SIGSEGV。A150 的 35/36 为非交互套件历史值（`tests/suite.sh` 不在仓库，未重跑） |
| 真机 | `$DEVICE`（Android 10 / 1080×2280 / Magisk root） |
| 已知环境坑 | `adb shell su -c "a; b"` 的 `;` 会让后半段以 shell 用户执行 → 必须 `su -c 'sh script.sh'` |
| 已知环境坑 | Android 的 mksh **不支持**算术展开里的位运算符（`$(( 0600 & 022 ))` 真机实测返回非 0）。判权限位只能用 `find -perm /022` 或 `stat -c %A` 逐位取，别写位与 |
| 已知环境坑 | mksh 的 `test` 内建**没有算术运算**：`[ "$now" - "$oldCreated" -lt 900 ]` 报 `[: -: unexpected operator/operand` 并非零返回（即恒假），差值必须写成 `[ $((now - oldCreated)) -lt 900 ]` |
| 已知环境坑 | 本机 toybox `find -print0` 可用，但 **`find -printf` 不可用**（`bad arg '-printf'`）；`stat -L -c %s` 正常 |
| 已知环境坑 | `test -f /proc/cpuinfo` 返回**真**（procfs 项类型就是普通文件）→ 判断「真空 vs 有内容」不能用文件类型，只能实读（`dd \| wc -c`） |
| 已知环境坑 | `stat -L -c %s` 对 `/proc/*`、`/sys/*`、FIFO **恒为 0** 但内容非空（三者与真空文件的 `stat %s` 完全相同） |
| 已知环境坑 | 内联 SQL 经 mksh 传参会被引号规则拆坏；`stat -c "%A\|%s"` 必须用脚本文件（`su -c 'sh x.sh'`）下发，直接在 `adb shell` 里会被 PowerShell 抢先解析 |
| 已知环境坑 | 设备的 `shared_prefs` 读不到（SELinux 拦 su 直读），且 pager 把四个 Tab 装在同一个 Activity 里 → **无法从设备侧观测外部唤起被接受还是被拒**。这类判定只能靠 JVM 单测覆盖谓词本身；设备侧只能验证「不崩、前台稳定」 |
| 已知环境坑 | Debug APK 与 Release 签名不同，装 Debug 需先 `pm uninstall`；`adb install` 在本机报 `Failure [-99]`，改用 `adb push` 到 `/data/local/tmp` 后 `su -c 'pm install -r -t'` 可成功 |
| 已知环境坑 | Magisk 授权策略可用 `magisk --sqlite <文件>` 批量写入（`policies` 表 `policy=2` 为允许），避免弹窗阻塞自动化；直接内联 SQL 会被 mksh 的引号规则拆坏，必须落文件 |
| 已知环境坑 | Compose 页面在本机 `uiautomator dump` 里 `content-desc` 与 `text` 常为空或为**上一个页面**的缓存，坐标易失效 → 复杂交互改用截图确认，或在 shell 层复刻判定链验证 |
| 已知环境坑 | 屏幕会自行熄灭导致截图全黑；长时间真机操作前先 `svc power stayon true` + `input keyevent KEYCODE_WAKEUP` |
| 已知环境坑 | 真机回显截图 480×1013（设备 1080×2280），PIL 读到的是 1080×2280 原图；`input tap` 坐标**双轴均按 ×2.25 换算**（此前把 y 轴记成 ×2.1308 属误记，换算结果系统性偏上、越靠近屏幕底部偏得越多）。设备底部 144px 为系统导航条（480dpi 下 48dp），y≥2136 的点击落入导航条——实测按旧系数点底栏「同步」（算得 y=2148）把应用点回桌面，底栏按钮实际中心约 y=2077。软键盘弹出后对话框上移，须重新截图定位 |
| `/data/adb/shso` 是 0777 但 **SELinux 拦住第三方**（目录标签 `adb_data_file`）。实测：`shell` 域与 app 域（`run-as` → `u:r:runas_app`，派生自 `untrusted_app`）对该目录的 list / unlink / symlink / write **全部 Permission denied**，dmesg 有对应 `avc: denied { getattr }`；只有 `su`(magisk 域) 能写 |
| `/data/local/tmp` 子目录默认 `root:root 755`，adb（shell 身份）**无法写入** → 需 `su -c 'chmod 777'` 后再 push |
| 已知环境坑 | 应用进程的 environ **不含 `TMPDIR`**（`su -c` 也不补），而 `adb shell` 自带 `TMPDIR=/data/local/tmp` → 依赖 `$TMPDIR` 的脚本「adb 下正常、应用内失败」。执行环境须显式 `export TMPDIR` |
| 已知环境坑 | 本机 toybox `mktemp` 创建失败时把模板前缀打成 `(null)/`（如 `Failed to create directory (null)//data/…`）——`(null)` 不是路径的一部分，真实模板要减去该前缀再看 |
| 已知环境坑 | 本机 toybox 0.8.0 的 `chmod` 符号模式**忽略按类别给出的 `-s`**：`u-s` / `g-s` 为空操作，清 setuid/setgid 只有 `a-s` 生效。实测 `4777` 经 `u-s,g-s,go-w` 得 `4755`（setuid 残留），经 `a-s,go-w` 得 `755` |
| 备份 | 守卫相关全部内容备份在 `守卫模块备份`（工作区内）（基线 `d6c3b0f`） |
| 收件箱提示 | 复制到收件箱失败的提示按实际原因区分（超单文件上限 / 文件数 256 / 总容量 2GB / 读不到文件），容量与数量类提示写明 `/storage/emulated/0/Download/shso` |
| 发版 | tag `20261108`（纯数字，与 `versionCode` 对齐，指向当次发版代码 `aea11b3`）；双端同名 Release（GitHub / Gitee），资产 `app-release.apk` 33,438,105 字节，三端 sha256 一致（`53CB069B…`，回下载核算），正文为 `更新日志.md` 20261108 段落的精简版（≤200 字）；上一版（`20261014`）的发行程序已从双端移除（Release 与 tag 保留），已下线的扩展包 Release 一并删除。语法包侧：`syntaxpacks-v2` 指向 A114 提交（APK 不内置语法） |
| 在线编译 | 实跑 `workflow_dispatch` 通过（包名 `com.sina.news`，5m19s，产物 2908313 字节，`package: name='com.sina.news'`）；签名开关 `use_debug_signing` 由签名步单点产出，单元测试步与编译步以 `-PuseDebugSigning` 同口径消费（见 § A142） |

---

## 待办

### A151. 扩展包下线、工具链全量内置（2026-11-07）

可下载扩展包（EnvPack，约 35MB 按需下载）下线，完整工具链并入内置资产，执行环境
收敛为单一来源；同时收口三项随删除失效的历史缺陷与一处构建红线漂移。

- [x] **删除可下载扩展包**：`EnvPack.kt` 与 `EnvPackContractTest` 删除；`TerminalPage`
      的设置/终端下载、删除入口（65 行）移除；`ShsoApplication` 去掉 `ensureLoaded`；
      `RootService` / `TermuxPrefix` 摘除 envpack 库目录分支 → 执行链路只剩内置 env 一条。
- [x] **工具链并入 env.bin**：新增 `tools/build_env_bin.py`，把原扩展包归档与内置环境
      合并为单资产（首轮 VERSION 3，现行 VERSION 4 / 1725 条目 / 38495218 字节）；生成期拒绝同路径冲突与
      软链越界，mtime/uid/gid 固定以保证可复现。`EmbeddedEnv` 改为 12 项必需文件校验、
      `env.tmp` 暂存整体替换、2 次尝试（失败不缓存，即自动重试一次），并导出 PREFIX /
      TERMUX_PREFIX；Python、Git、OpenSSL 资源改由前缀补链，避免污染真实 Termux；首装后清理旧
      `envpack` 目录。
- [x] **失效项归档**：SEC-003（linkName 越界）、EDGE-004（冷启动
      抢跑 su）、CONSIST-040（ready 短路致库不进前缀）三条随 EnvPack 删除全部不适用，
      相关引用已从代码与文档清理。
- [x] **旧编号记录清理**：两份旧编号台账删除，剩余引用改指 `TASKS.md` 的现存结论。
- [x] **258 结论订正**：原判「旧 ID 全仓无定义」不成立 —— 旧编号在历史台账中均有已修记录；
      真实问题是同一缺陷两套编号并行、无迁移注记，即「引用未统一」。相关改动（只引现行
      `BUG-*` ID）保留不动。
- [x] **BUG-CONSIST-259（P2）构建红线版本对齐**：登记时 `envArchiveVersion` 仍为 "1"、
      归档 VERSION 已升 `3`，`verifyReleasePayload` 等值比较必报「内置环境版本不符」；
      现常量为 `"6"`、体积红线 20 MB / 60 MB、注释现值 29.1 MB，
      与 `EmbeddedEnv.ENV_VERSION = "6"` 三处一致（构建实跑见下项，已通过）。
- [x] **构建与真机复测**：`testDebugUnitTest` / `lintRelease` / `assembleRelease` /
      `verifyReleasePayload` 四项 BUILD SUCCESSFUL（717 tests / 0 failures / 0 errors /
      1 skipped；lint 0 errors；verify 输出「内置环境完整」），APK 33,438,105 字节（约 33.4 MB）/ sha256
      `418FBF2E6DD49651CA613F5D3CF5E050A03C266A0A0059A991D84FF8DBE362C1`；
      仅授权设备 127.0.0.1:16384（Redmi K70 / 23113RKC6C）`adb install` 成功，MainActivity 前台稳定；首解后
      真机 `VERSION=6`，旧 `env/bin/sh` 缺失，前缀 `sh` / `ash` / `hush` / `mash` 全部链到 `/system/bin/sh`，
      `env.tmp` / `env.old` / `envpack` 均不存在；应用执行绝对 shebang
      `#!/data/data/com.termux/files/usr/bin/sh` 输出 `ABS_SH_OK`；绝对前缀入口
      `python3.14` / `python3` / `python` / `env` / `sh` / `bash` 与 `libcrypto` / `libz`
      链接在位，`libexec/git-core`、`share/git-core`、`etc/tls/openssl.cnf`、`lib/python3.14` 补链到位；
      执行链不再导出 `LD_LIBRARY_PATH`；Python 3.14.6、git 2.56.0、OpenSSL 3.6.5、OpenSSH 10.6p1、rsync 3.5.1、GNU
      sed/tar/wget/grep/find、zstd 1.5.7、xz 5.8.4 均执行成功；日志无 FATAL EXCEPTION / CANNOT LINK / SIGSEGV
       （`tests/suite.sh` 不在仓库，未重跑）。

- [x] **执行链路审计修复**：busybox `sh` / `ash` / `hush` / `mash` 入口全部禁建；旧 envpack、
      env.old、env.tmp 的自有悬空前缀链可迁移；真实 Termux 文件、软链与外部目标不覆盖；
      Python、Git、OpenSSL 资源改由前缀补链；冷启动 root 未定时短预算限流；运行时准备不丢重试；
      `verifyReleasePayload` 先生成当前 APK，env.bin 关闭二次压缩。

- [x] **前缀建链分段（su 4KB 上限）**：真机探针确认 `su -c` 单条命令约 4096 字节封顶，
      3629 字节正常、5429 字节即 rc=255 且零输出；原 5998 字节建链命令被整段丢弃，
      `TermuxPrefix.ready` 恒为 false、`LD_LIBRARY_PATH` 长期兜底、旧 ash/hush 链不清。
      改为按语义分段顺序执行、单段 ≤3000 字节，并以段完成标记判定是否真跑完；复测前缀
      sh/ash/hush/mash 全部指向 `/system/bin/sh`，libexec/git-core、share/git-core、
      etc/tls/openssl.cnf、lib/python3.14 补链到位，执行链不再导出 `LD_LIBRARY_PATH`。

- [x] **内置环境瘦身（VERSION 5）**：剔除 `share/misc/magic.mgc`（10.3MB，归档不含 `file` 命令）、
      `bin/scalar`、Python 构建配置与 pkgconfig/aclocal，剔除清单固化在 `tools/build_env_bin.py`
      并可复现；`env.bin` 38,495,218 → 36,924,277 字节，APK 41,420,185 → 39,849,241 字节。
      同轮固定 `GIT_PAGER=cat`，修复未内置分页器时 `git log` 报 `fatal: unable to execute pager`。

- [x] **二次瘦身（VERSION 6）**：再剔除 git 服务端与 dumb-http 辅助程序（`git-daemon`、
      `git-http-backend`、`git-http-fetch`、`git-http-push`、`git-imap-send`、`git-sh-i18n--envsubst`，
      每个约 2 MB）与 `pydoc_data`；`git-remote-http` 保留，http/https 传输不受影响。
      `env.bin` 36,924,277 → 30,513,138 字节，APK 39,849,241 → 33,438,105 字节。
      真机复验：`VERSION=6`、前缀四个 shell 入口指向 `/system/bin/sh`、`git init/add/commit/log/status/fetch/branch`
      全通、Python 3.14.6（sqlite3/ssl/email/asyncio）正常、解压后 77 MB 且无 `env.old` 残留，
      日志无 FATAL EXCEPTION / CANNOT LINK / SIGSEGV。

- [x] **死代码清理**：删除执行反馈遗留的 `ExecutionFeedback` / `formatElapsed` 与其单测（功能已移除，
      仅测试引用）、`FilePage` 中未被调用的 `ListScrollBar` 组件；单测数 725 → 717，
      构建与真机执行复验通过。

- [x] **文档收敛**：写作与命名规范合并为 `docs/规范.md`；在线编译并入 `docs/PROJECT.md`；
      `大文件只读方案`、`B站专栏`、两份 archive 归档删除；缺陷与任务记录合并为一份，
      只留现行状态与最近三轮，合计由约 1.06 MB 降至约 40 KB。

### A150. 死状态清理、引用收口与执行矩阵复核（2026-10-15）

- 删除 `lastExitCode` 死状态（UI 从不展示），发布循环前注释改为描述实际影响。
- 缺陷编号曾新旧两套并行且无迁移记注，统一为只引现行 `BUG-*`；测试探针变量 `EXIT_RELAY`
  不再当作生产不变量。
- 真机执行矩阵（root / Android 10）：`tests/suite.sh` 经应用入口 pass=35 fail=1，
  失败项 `xz_roundtrip` 因 busybox 的 xz 仅支持解压，判为兼容性观察项；`read` 交互、
  终端命令链、工具在场 7 项均通过。
- 构建与装机：690 tests / 0 failures / 1 skipped；lint 0 errors；产物 9,407,253 字节；
  装机后 `versionCode=20261015`，崩溃缓冲为空。

### A149. 更新日志分类归一、措辞整理与覆盖式发版（2026-10-13）

- `更新日志.md` 分类顺序归一（新增 / 变更 / 弃用 / 移除 / 修复 / 安全），悬空引用改指真实文件。
- `README.md` 徽章、体积与能力措辞同步；对外文档禁用词扫描无实命中。
- 全量重编译 659 tests / 0 failures / 1 skipped，`build_apk.py --clean` 通过，产物 2,908,309 字节。
- 覆盖式发版：tag `20261013` 仍指 `b3d7739`，双端正文 167 字符、资产覆盖上传，回下载 sha256 一致。

> 更早轮次（A1~A148）的逐条过程与证据见 git 历史，本文只保留现行状态与最近三轮。
## 已定结论（避免反复推翻）

- **终端「当前活动进程」只有一个槽位**：一次性命令与脚本任务共用 `isTaskRunning` / `activeProcess` / `processPid` / `runPgid`，
  状态清理一律按**代际计数**判断（`terminalCommandGeneration`）；同时刻只受理一条命令（并发会让先启动那条失去回收句柄）。
- **停止任务必须整组回收**：`kill -<sig> -- -<pgid>`，且每条停止入口（中断 / 结束进程 / 重启终端 / 覆盖启动前的清理）都要走同一套；
  兜底 `pkill` 只在进程组不可用时使用，且按**完整路径**匹配（按文件名会误杀同名重跑的新任务）。
- **日志类 UI 的渲染成本受单行长度支配**：LazyColumn 只做项级虚拟化，必须给单行加渲染上限（当前 4000 字符），
  数据层保持全文；新增任何「整行渲染」入口都要过 `renderableLine()`。
- **终端显示必须消化非 SGR 序列**：私有模式 CSI / OSC / `` / `ESC[K` 都要吞掉，未识别即会变成可见乱码。

1. **`/data/adb/shso` 必须 777**：需让其他应用自由读写；曾改 755，用户明确要求回退。
2. **release 已开启 R8 + shrinkResources**：资源会被重命名为随机短名，不要按 APK 内资源名反查源码资源。
3. **`Process.pid()` 在 Android 不存在**：取子进程 pid 只能反射；中断正确性由进程组回收保证。
4. **编辑器载入归一为 LF、保存按 `currentLineEnding` 还原**：改 `LineEnding.apply` 需同步该契约。
5. **风险等级一律按来源分级**：混淆/未解析类在 `SCRIPT_FILE` 为 `CRITICAL`（自动执行拒），`USER_TERMINAL` 为 `DANGEROUS`（可确认）。
6. **守卫是 PATH 前置型，能力有边界**：绝对路径调用与 `PATH` 重置可绕过；不要据此认为「装了守卫就万无一失」。
7. **新增守卫包装器必须三处同步**：`gen_wrappers.py` specs、`assets/shso_guard.zip`、`REQUIRED_ARCHIVE_ENTRIES`，并升 `module.prop` 版本。
8. **不要在 `LaunchedEffect` 里直接 `scrollToItem`**：列表未组合时会挂起并阻塞后续逻辑。
9. **档位 ≤1 时策略层一律放行**：验证拦截必须用档位 ≥2。
10. **分包安装必须走 `pm install-create/-write/-commit` 且分片先拷到 `/data/local/tmp`**。
11. **套件聚合宁少勿错**：`collectApkSet` 定位不到唯一基础包时退回单文件安装，绝不猜测。
12. **APK 不得内置语法包**：语法由用户导入（本地 zip / 仓库直链）；`verifyReleasePayload` 为强制红线，确需上调体积上限须连同理由一起改常量。
13. **保存必须原子**：临时文件与目标同目录、`renameTo`/`mv` 覆盖、还原 `mode/uid/gid`、失败清理；禁止直接 `writeBytes` 到目标。
14. **编码必须严格**：用 `CharsetEncoder` + `REPORT`；禁止 `String.toByteArray(charset)` 的静默 `?` 替换。
15. **Sora 的检索与替换都是异步的**：`replaceAll/replaceCurrentMatch` 在检索未结束（`isResultValid()==false`）时只弹 Toast 后返回；任何「搜索后立即读结果/替换」都必须先等结果集写入。
16. **`packaging.resources.excludes` 必须保留 `"tables/**"` —— jcodings 的 648 个编码表会打进 APK 根目录（1.24MB）；正则只用 UTF-8/ASCII-8BIT 内建编码，不查表。
17. **语法解析器顺序不可颠倒**：`FileProviderRegistry.addProvider` 先应用私有目录、后 assets；`AssetsFileResolver` 对缺失路径不捕获异常，排在前面会中断整条解析链。
18. **Monarch 主题必须覆盖语法用到的全部令牌作用域**（含 `identifier`、`attribute`）： 未匹配令牌落回黑色；主题加载失败时不启用语法。

---

## 校验与验证命令

```bash
export MSYS_NO_PATHCONV=1
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
export DEVICE=$(adb devices | awk 'NR==2{print $1}')   # 目标机序列号不入库

./gradlew :app:testDebugUnitTest :app:assembleDebug          # 基线
./gradlew :app:assembleRelease                               # 含 verifyReleasePayload 红线校验
python tools/gen_syntax_packs.py                             # 重新生成语法包（syntax-packs/ + syntax-packs.zip）

adb -s $DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $DEVICE shell "logcat -d -s shso-perf"       # 语法就绪/注册日志
adb -s $DEVICE shell "su -c 'ls -ld /data/adb/shso'"        # 权限应为 777
adb -s $DEVICE shell "su -c 'grep ^version= /data/adb/modules/shso_guard/module.prop'"
```

---

## 真机与环境备忘

### 真机操作（$DEVICE，Android 10 / 1080×2280）

- 底部导航坐标：`主页 153 / 终端 411 / 文件 669 / 设置 927`，y = `2156`；点击后等 3–5 秒。
- 文件页默认「内部存储」；目录恒排在文件之前。
- 文件单击=动作菜单（含「编辑文本」），长按=多选模式。
- 截图前先 `input keyevent KEYCODE_WAKEUP`，否则可能得到黑屏。
- 清理 `/sdcard` 测试文件需 `su -c`（应用 push 的文件属 root，adb shell 直接删会失败）。

### 环境坑（易致误判）

| 坑 | 表现 / 处理 |
|---|---|
| Git Bash MSYS 路径转换 | `adb push <local> /data/...` 会被改成 `C:/Program Files/Git/data/...` → 必须 `export MSYS_NO_PATHCONV=1` |
| `adb install` 相对路径 | 需要绝对路径（shell 不保留上一条命令的 `cd`），否则 `failed to stat` |
| Android mksh 算术是 32 位 | `date +%s%N` 参与 `$(( ))` 被截断 → 真机计时用 POSIX `time` |
| toybox `file` 不支持 `-b`，`cat` 不支持 `-A` | 传了只输出错误文本 |
| 应用 uid 写 `/data/adb/` 受 SELinux 限制 | 即便 `chmod 777` 仍可能 `Permission denied` → 判可写性必须实测 |
| uiautomator dump 不含视口外内容 | 长列表超出视口的内容不会出现在 dump 里，据此判断「列表被截断」是误报 |
| uiautomator bounds 对 Compose 文本按钮纵向偏上（实测约 70px） | 报 `[x1,521][x2,624]`，实际命中区在 590–610；按 bounds 中心点击会「点了没反应」，先做 y 方向小范围扫描再判定 |
| **`adb shell input text` 在终端输入框完全不生效**（2026-10-03 复现） | 输入后 placeholder「请输入命令…」仍在，命令未发出、`input keyevent ENTER` 也无反应。同一坐标点击动作栏按钮有效，故非坐标问题。**终端交互不要依赖 `input text`**，改用真实代码路径验证（对应测试或 `su` 脚本），别把「命令没跑」误判成功能缺陷 |
| 用 `su -c` 传含 `$`/`\` 的脚本内容 | 多层引号会被吃掉 → 改用「本地写文件 → push → `cp`」 |
| `su -c "wc -l < /data/adb/…"` | `<` 重定向由**外层 shell**（shell 用户）执行 → `Permission denied`；写 `adb shell "su -c 'wc -l /路径'"` |
| uiautomator 把每行**最后一个**控件报成 `bounds="[0,0][0,0]"` | 顶栏「设置」/ 动作行「发送」在横屏下被误判成「被挤没了」——实际正常渲染，**零 bounds 不能作为不可见证据**，用截图复核 |
| `settings put system user_rotation` 强制横屏 | ColorOS 上会**静默回弹**（脚本里"设横屏→截图"可能拿到竖屏）→ 每步用 `dumpsys window \| grep mCurrentRotation` 校验；模拟矮视口改用 `wm size` |
| 横屏下点击屏幕右边缘（2280 宽屏 x≥2150） | 落进系统返回手势区 → **把 App 直接退出**（无崩溃日志），因此该轮坐标扫描全部失效；扫描前先 `ps` 确认 App 仍在台前 |
| **CI runner 是 UTC，版本号/标签取「当日日期」必须固定东八区** | 北京时间 00:00–08:00 构建时 `LocalDate.now()` 退回前一天 → 产物 versionCode 与发布标签不一致（曾出现 tag `20260914` 的 APK 实为 `20260913`，用户装上仍被判为旧版本、更新提示无限循环）。`app/build.gradle.kts` 已固定 `ZoneId.of("Asia/Shanghai")`，发布工作流新增 aapt2 校验：产物 versionCode ≠ 标签即失败 |
| Windows 控制台显示 Gradle 中文输出为乱码 | 仅显示问题，日志文件本身是 UTF-8；用 Python 读日志核对 |

---

## 旧看板

| 范围 | 位置 |
|---|---|
| 任务 1~A148 的全量过程与证据 | git 历史（`TASKS.md` 旧版本） |

---

## 缺陷台账

只记现行开放项与已判定口径；已修条目的证据由 git 历史与 `更新日志.md` 承担。

﻿### 开放项

| ID | 级别 | 维度 | 位置 | 症状 | 现状与原因 |
|---|---|---|---|---|---|
| BUG-PERF-030 | P2 | 性能 | `ui/pages/TerminalPage.kt`、`AnsiParser` | 语法解析在非终端页仍全额发生；裁剪后仍走全量重解析 | 离屏终端页已停止解析（A106）。剩余分支为架构取舍：终端输出需完整保留供「复制输出」，改为增量重解析会引入跨块状态机，收益不抵复杂度，A109 裁定维持 |
| BUG-COMPAT-184 | P2 | 兼容 | `RootService.wrapWithPty` | busybox `script` 伪终端下，`select` 菜单与 `while-read` 循环构造会卡住 | 2026-10-14 真机复核：非交互 13 类（自解压 / 联网 curl / openssl / git / python / ssh / trap / 大输出 / bash shebang / 后台 wait / 环境变量 / ELF .so）全 PASS，`read` 密码范式 PASS，纯管道正常。仅 `select` / `while-read` 构造受限，属 busybox PTY 行规局限，不自研 PTY |

### 已判定口径

| 现象 | 判定 | 依据 |
|---|---|---|
| 双列各挂一个列举 effect，冷启动并发两次 su | 设计如此 | 单次列举已降至 0.1s 量级，su 往返约 20ms；右列懒加载会先呈现空列，代价更大 |
| 无 root 时功能退化（写系统目录、静默安装等） | 设计如此 | 无 root 时回退 `java.io.File` 与系统安装器，入口按实测可写性禁用 |
| 动作按钮无 `contentDescription` | 不修 | 按钮为文字字符而非 Icon，补标签属全局 UX 改造 |
| 更新检查两源行为不一致（Gitee 优先 / GitHub 备选） | 设计如此 | 按统一约定实现；Gitee 取 `/api/v5/.../tags`，GitHub 取 `/tags` HTML |
| 超 32MB 文本禁编辑、只读态禁搜索 | 设计如此 | 全文入 Sora 内存会 OOM；只读态未载入全文，查找入口已提示先「编辑」 |
| 语法包不入 APK、需用户导入 | 设计如此 | 体积优先；`verifyReleasePayload` 强制拦截随包语法定义 |
| 解压符号链接 TOCTOU | 已知残留 | 已做词法剥离 + `canonicalFile` 二次校验；彻底消除需无跟随写入原语，超出当前实现 |
| ZIP 中央目录整体加载 | 已知可接受 | 条目数在加载前用尾部 EOCD 零分配探针取得，内存峰值受条目预算约束 |
| 多文件分享只消费首项 | 已知限制 | 系统 `ACTION_SEND` 单 `EXTRA_STREAM` 语义；批量走 `ACTION_SEND_MULTIPLE` 未开放 |
| 执行链路不写横幅 / 审计日志 | 设计如此 | 守卫与审计子系统已整体移除，命令直通执行 |

新增条目按一行表格登记：`BUG-<维度>-<序号>` / P0~P3 / 维度 / 文件与符号 / 用户可感知现象 / 一句话结论。
新增前先查重，重复即合并；修复后移出开放项，明细写 `更新日志.md`。

## 相关文档

| 文件 | 用途 |
|---|---|
| `README.md` | 功能总览、执行方式、版本规则、在线编译入口 |
| `更新日志.md` | 变更清单，一行一条 |
| `docs/PROJECT.md` | 技术栈、目录结构、架构、执行方式与已知注意点 |
| `.github/workflows/publish-release.yml` | 纯日期标签发布 |
| `.github/workflows/build-apk.yml` | 在线编译（自定义包名） |

---
