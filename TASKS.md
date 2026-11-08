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
| 单元测试 | 725 tests / 0 failures / 0 errors / 1 skipped（A151 `testDebugUnitTest`，XML 汇总与源码 `@Test` 计数一致） |
| lint | 0 errors（A151 `lintRelease` BUILD SUCCESSFUL） |
| release 体积 | **39,849,241 字节**（约 39.8 MB，十进制），sha256 `9E591DE61A95AF19E3BB48B41459DB4FB7571B0C81C2DF5E741D4AC858F52293`；`verifyReleasePayload` BUILD SUCCESSFUL（输出「内置环境完整」）；`assets/env.bin` 36,924,277 字节（VERSION 5 / 1648 条目 / 373 软链，已剔除 magic.mgc 与 scalar 等运行期无用内容） |
| 执行环境 | **全量内置**（A151）：`assets/env.bin` VERSION 5 携带完整 aarch64 工具链 —— bash / busybox（358 applet）/ curl / GNU 与 coreutils / git / python3 / openssl / ssh / rsync，首次启动解压到私有目录；归档内 `bin/sh` 不由 busybox 提供（busybox sh applet 直跑会 SIGSEGV，applets 亦不含 sh），前缀缺 sh 时由 `TermuxPrefix` 建链指向 `/system/bin/sh`，真机已验证；Termux 前缀符号链接避免 `LD_LIBRARY_PATH` 污染；`GIT_PAGER=cat` 规避未内置分页器导致的 `fatal: unable to execute pager`；可下载扩展包已删除，不再有「按设计缺失」的工具 |
| 执行矩阵 | A151 真机（127.0.0.1:16384 / Redmi K70 23113RKC6C）安装成功、MainActivity 前台稳定；首解后 `VERSION=5`，`env.tmp`/`env.old`/`envpack` 均不存在，旧 `env/bin/sh` 缺失，前缀 `sh`/`ash`/`hush`/`mash` 全部链到 `/system/bin/sh`；应用执行绝对 shebang `#!/data/data/com.termux/files/usr/bin/sh` 输出 `ABS_SH_OK`；绝对 `python3.14`/`python3`/`python`/`env`/`sh`/`bash` 与 `libcrypto`/`libz` 链接在位，`libexec/git-core`、`share/git-core`、`etc/tls/openssl.cnf`、`lib/python3.14` 补链到位；执行链不再导出 `LD_LIBRARY_PATH`；Python 3.14.6（含 sqlite3/ssl）、git 2.56.0（含 `git init/add/commit/log`）、OpenSSL 3.6.5、OpenSSH 10.6p1、rsync 3.5.1、GNU sed/tar/wget/grep/find、zstd 1.5.7、xz 5.8.4 均执行成功；日志无 FATAL EXCEPTION / CANNOT LINK / SIGSEGV。A150 的 35/36 为非交互套件历史值（`tests/suite.sh` 不在仓库，未重跑） |
| 目录列举 | 三态（成功 / 不存在 / 失败）+ **存在性证据**（`test -d`）先于列举，「真空」不再与「失败」混淆 |
| 文件名通道 | `find -print0`（NUL 分隔）；元数据通道**批量**一次调用取（`find -exec stat -L -c '%n\|%A\|%s\|%Y' {} +`，`%n` 自带名称、`sed` 按末三字段切开、`tr` 换 NUL）→ 进程创建 O(n)→O(1)，188 条目目录由 9.34s 降至 0.11s；按名称配对 → 列举期间增删/断链不再让大小与类型串位；标记须两侧 NUL 包裹才命中 |
| 列表一致性 | `listIsStale` + `loadingGenRef` 双护栏：陈旧列表不可操作，重算不越代落盘 |
| 移动/复制 | 冲突探测单次化；覆盖目录先改名让位、失败回滚；拷贝只清 setuid/setgid 与 g/o 写位，保留执行位 |
| root 探测时序 | `isRootGranted == null`（未探测）先等待探测落定，上限 3s，超时按已知状态处理 |
| 执行归属 | `stillOwnsExecution` 比对精确槽位令牌；`startExecution` 清槽位兑现契约 → 新脚本不会被旧收尾误清 |
| 进程回收 | 整组 kill 的校验②由「组长存活」改为**组成员存活扫描**（纯 shell 内建 `/proc` 遍历，不依赖 `ps -o PGID`／`awk`）→ 组长已退的孤儿组仍能整组回收 |
| 非 Root 回收 | 经 `/system/bin/setsid` 自成会话/进程组（`pgid == 组长 pid == 直接子进程 pid`，同步段直接回填）；kill 通道按 `execUsesRoot` 分流：非 Root 走应用自身 uid 的 `sh`，不弹 `su` |
| 终端保活 | 命令启动即拉起前台服务（不再延迟 5s），避开 `targetSdk 35` 后台禁止启动前台服务的限制 |
| 超时回收 | `runCommandSync` 新增 `reclaimOnTimeout` 形参：回收通道只 `destroyForcibly`，斩断「超时 → 组杀 → 超时」互递归 |
| 执行收尾 | `.so` 权限还原改在 `Dispatchers.IO`（不再阻塞主线程）；属性文件写出后 `chmod 644`（`su` 掩码 077 时 app 读不到会导致还原被静默跳过） |
| 非 Root 执行 | 与 ROOT 同口径的 ELF 魔数预检；非 ELF 报 126 并提示，是 ELF 则 `chmod u+x` 后直接执行（不再交给 `sh` 隐式解释） |
| 文本读取 | `probeReadableBytes`（`dd \| wc -c`）区分「真空文件」与「procfs 有内容」；`readFailed` 阻止编辑保存 |
| 条目预算 | 中央目录**零分配结构遍历**取精确条目数（不信自报字段、不按体积折算） |
| 终端 | 增量 ANSI/OSC 解析、单行渲染上限 4000 字符、一次性命令可中断/流式/保活 |
| 编辑器内核 | Sora Editor 0.23.6（打开即可编辑；语法由外置语法包提供）；检索 API 全部经 `queryActive` 守卫 |
| 保存守卫 | 三条保存入口（doSave / 另存为 / 未保存弹窗）均拦「加载中 / 读取失败 / 只读大文件」，后者曾漏检致 0 字节覆盖 |
| 另存为残留 | 临时文件建在目标同目录，目录由用户指定：写入前登记 `filesDir/.shso_save_pending`、替换或就地删除后撤销；启动清扫按名白名单（绝对路径 + 隐藏名 + 含 `.shso_` + `.tmp`）逐条清理，删不掉的留待下次重试 |
| 另存为路径校验 | 控制字符（NUL / `\n` / `\r`）一并拒绝，与 `RootFileManager.isUnsafePath` / `isUnsafeFileName` 同口径 |
| 终端渲染 | 解析代次计数器为**文件级**（跨 Activity 有效）；自动滚动 key 含末行长度并钉到底 |
| 解析规模 | `completed` 行数硬上限 4000（超限从头部一次性丢弃，`droppedLines` 如实计数）→ snapshot 分配恒定有界 |
| 编辑器状态 | 保存目标 `currentFilePath` 用 `rememberSaveable`（与 dirty/编码/行尾同组）；编辑器内容一律主线程读取 |
| 安装安全 | 分包套件**整套**暂存校验（兄弟分片不再走原始路径）；任一分片暂存失败即中止 |
| 目录列举 | 单次 su 完成存在性+名称+元数据；存在性由**退出码**承担（3/4/0），不再依赖输出是否为空；两通道用 NUL + 固定标记分隔 |
| 文件页布局 | 双列常驻（`Row` + 1dp 分隔），窄列自动切紧凑行、隐藏权限串与内联按钮 |
| 全局底栏 | `顶部 / 底部 / 新建 / 同步 / 刷新` 置于 DockBar 上方；作用对象为聚焦列，`同步` 把聚焦列目录赋给另一列 |
| 逐列状态 | 左右列各自持有目录、列表、过滤、多选、刷新代次（`FilePaneState`）；目录失效回退与静默空列表自愈均按列独立触发 |
| 列表刷新 | **不再清空列表**（消除刷新期空白）；保留期间由 `enabled = !listIsStale` 真正禁用交互并降透明度；同目录刷新 120ms 节流 |
| 列表性能 | `FileItem` 类型判定构造期一次算好（不再每帧重算正则）；选中判定走 HashSet（`derivedStateOf` 缓存、随选择实时重算，见 § A145）；搜索 250ms 防抖；主页行用 derivedStateOf 隔离；目录元数据批量 stat（不逐条目 fork） |
| 列表设置入口 | 「全选文件 / 取消全选」双态由**单点判据** `multiSelectMode && selectedPaths.isNotEmpty()` 驱动（文案与点击分支同源，无错位窗口）；范围四类改为 全部 / 仅文件 / 仅图片 / 仅文本（见 § A148） |
| 代码结构 | 无重复分支/无用中间层：`preferRoot` 已扁平、`pathExistsQuiet`/`uniqueFile`/`isExtractable` 已删 |
| 脚本执行 | `.sh` 归一化改为**字节级保守判定**（`ShNormalization`）：含字节偏移自引用（`$0`/`${0}`/`${BASH_SOURCE`，配 `tail…+N`／含变量形式 `+$skip`、`head -c`、`dd skip=`、`sed -n 'N,'`）或内嵌压缩魔数即直跑原字节，仅普通文本脚本去 BOM/CR（上限 16 MiB，超限直跑）；app 侧不可读时由 `decideAsRoot` 先 `stat` 取大小再按实际长度以 root 读头部判定；`.so` 权限先记录后还原，还原排在执行之后；执行环境统一补 `TMPDIR`（root 侧 `/data/local/tmp`，非 Root 侧应用私有 `cacheDir`），终端命令同样注入 |
| 归档格式 | 14 种：tar 系（`.tar.gz/.tar.xz/.tar.bz2/.tar.lz4/.tar.zst/.tgz/.tzst/.tar`）+ 单文件（`.gz/.xz/.bz2/.lz4/.zst/.zip`）；`.tzst` 为 tar+zstd 别名 |
| 语法包 | 62 语言 / 187 扩展名，`syntax-packs.zip`，永固直链 tag `syntaxpacks-v2`；下载**双源（Gitee 优先、GitHub 备用，同 tag 同文件）**，弹窗内可切备用直链；关键字正则逐项转义、边界只加在词字符一侧、长关键字优先（`as?`/`!in`/`!is`/`defined?`） |
| 执行方式 | 命令与脚本直通执行，无策略判定、无执行记录（守卫/档位/审计已移除） |
| OBB 事务锁 | 单文件 `set -C`(O_EXCL) 原子 CAS；真机 8 进程并发恰好 1 成功；**建目录早于取锁**；幂等条目不进回滚表；锁内容四段 `token\|pid\|startTicks\|createdEpoch`，陈旧判定用 `$(( ))` 算术展开（`- ` 写法非 test 合法形式会恒假） |
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
| 发版 | tag `20261013`（纯数字，与 `versionCode` 对齐，**指向当次发版代码** `b3d7739`）；双端同名 Release（GitHub / Gitee），资产 `app-release.apk` 2908309 字节，三端 sha256 一致（`E17346CE…`，全量重编译后覆盖上传并回下载核算，见 § A149），正文 167 字符（为 `更新日志.md` 20261013 段落的精简版，符合发版说明 ≤200 字），双端 Release 目标提交均已对齐 `b3d7739`（见 § A148）；上一版（`20261012`）的发行程序已从双端移除（Release 与 tag 保留）。语法包侧：`syntaxpacks-v2` 指向 A114 提交（APK 不内置语法） |
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
- [x] **旧编号记录清理**：两份旧编号台账删除，剩余引用改指 `BUG.md` / `TASKS.md` 的现存结论。
- [x] **258 结论订正**：原判「旧 ID 全仓无定义」不成立 —— 旧编号在历史台账中均有已修记录；
      真实问题是同一缺陷两套编号并行、无迁移注记，即「引用未统一」。相关改动（只引现行
      `BUG-*` ID）保留不动。
- [x] **BUG-CONSIST-259（P2）构建红线版本对齐**：登记时 `envArchiveVersion` 仍为 "1"、
      归档 VERSION 已升 `3`，`verifyReleasePayload` 等值比较必报「内置环境版本不符」；
      现常量为 `"5"`、体积红线 20 MB / 60 MB、注释现值 35.2 MB，
      与 `EmbeddedEnv.ENV_VERSION = "5"` 三处一致（构建实跑见下项，已通过）。
- [x] **构建与真机复测**：`testDebugUnitTest` / `lintRelease` / `assembleRelease` /
      `verifyReleasePayload` 四项 BUILD SUCCESSFUL（725 tests / 0 failures / 0 errors /
      1 skipped；lint 0 errors；verify 输出「内置环境完整」），APK 39,849,241 字节（约 39.8 MB）/ sha256
      `9E591DE61A95AF19E3BB48B41459DB4FB7571B0C81C2DF5E741D4AC858F52293`；
      仅授权设备 127.0.0.1:16384（Redmi K70 / 23113RKC6C）`adb install` 成功，MainActivity 前台稳定；首解后
      真机 `VERSION=5`，旧 `env/bin/sh` 缺失，前缀 `sh` / `ash` / `hush` / `mash` 全部链到 `/system/bin/sh`，
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

- [x] **文档收敛**：写作与命名规范合并为 `docs/规范.md`；在线编译并入 `docs/PROJECT.md`；
      `大文件只读方案`、`B站专栏`、两份 archive 归档删除；`BUG.md` 只留开放项与判定口径，
      `TASKS.md` 只留现行状态与最近三轮，合计由约 1.06 MB 降至约 40 KB。

### A150. 死状态清理、章程引据收口与执行矩阵真机复核（2026-10-15）

历史台账登记 5 条（254~258），其中 254（主线程冷启动同步 su）与 256（弹窗路径对原件算
SHA-256 被丢弃）已由 `41b78e3` 当日修复关闭。剩余两条纯清理项处置如下，并对「脚本执行
完整性」这一最高优先级目标做端到端复测。

- [x] **BUG-LOGIC-257（P3）`lastExitCode` 零读取死状态**：全树 grep 仅 1 处声明 + 8 处赋值
      + 1 处注释引用，UI 从不展示退出码；`RootService` 发布循环前的注释（`lastExitCode` 旁，
      现行 `RootService.kt:1374-1375`）仍以「退出码永不显示」
      描述影响、暗示存在消费点。删除符号本体与全部赋值，注释订正为「发布循环不停 /
      isTaskRunning 不复位」；catch 分支顺带补 `stopBatchFlushLoop` + flush（原先只写死状态，
      行为等价且更完整）。`TASKS.md` 旧段落同步订正。
- [x] **BUG-CONSIST-258（P3）章程幽灵引据**：三份章程成对引用旧/新 ID
      （旧编号；章程原文见历史审计记录），旧 ID 在历史台账中确有已修条目、与现行 ID
      记录同一批修复（A151 复核订正：原「全仓无定义」判断有误，实为编号未收口）；且 `EXIT_RELAY` 系
      A111 真机测试脚本的探针变量（uid 2000 侧退出码 7 透传 setsid 链），非生产符号，章程却
      把它当生产不变量写。三份统一改为只引现行 `BUG-*` ID，退出码中继改述为
      `process.exitValue` / `waitFor` 链，与 `RootService.kt:384` KDoc 一致。
      章程侧三处引用经复核为正确，不改。
- [x] **P0 疑云证伪（执行引擎无回归）**：终端输入的命令一度全部报
      `/system/bin/sh: <cmd>: inaccessible or not found`（rc=127），按 P0 立案排查。
      三步取证后判定为**取证工具缺陷**：① 落盘诊断显示应用下发的命令串与预期逐字节一致；
      ② 同串经 `execv`/`su -c` 在设备上 rc=0；③ `adb input text 'echo\ X'` 的反斜杠被 mksh
      解释为词内转义，`echo\ X` 成为单个词，127 是 shell 的正确行为。键入不含反斜杠的
      `curl --version` 后 curl 真实执行并输出诊断。执行引擎无回归，未立案。
- [x] **真机执行矩阵验收（root，PACM00 / Android 10）**：`tests/suite.sh` 经应用「立即执行」
      入口跑完 **pass=35 fail=1**（shell 直跑同脚本 pass=24 fail=12 —— 差 11 项全由应用侧
      PATH / 证书 / TMPDIR 注入补齐：bash/curl 在场、PATH 含 env/bin、bash 数组/关联数组/
      进程替换/heredoc、zip 回环、busybox sha256、bash shebang 两种写法）。唯一失败
      `xz_roundtrip`：内置 busybox 的 xz applet 仅支持解压（`Usage: xz -d [-cfk]`，无压缩入口），
      而应用自身 `.xz` 解压走 Java tukaani-xz 库不经 shell，压缩型脚本属扩展环境包范畴 ——
      判 COMPAT/BY_DESIGN 观察项（A151 注：扩展包已下线、工具链全量内置，本判据仍成立，
      仅「扩展包范畴」一说失效）。另验 read 交互（smoke.sh 输出 SMOKE_OK + 退出码行 +
      状态回「待命中」）、终端命令链（curl 实跑输出诊断）、`HAVE` 工具 7 项。
- [x] **构建与装机**：`testDebugUnitTest` 690 tests / 0 failures / 1 skipped；
      `assembleRelease` + `verifyReleasePayload` 通过；`lintRelease` 0 errors；
      产物 9407253 字节 / sha256 `DC3DD465295F3DDC0DEC9E861A43C1935BE1628C20EDD41875DFABD31804DFA9`；
      `adb install -r` 成功，`versionCode=20261015` / `versionName=Jinn`，冷启动
      `mResumedActivity` 为本应用 MainActivity，crash buffer 无记录。

### A149. 更新日志分类归一、文档净化与全量重编译覆盖发版（2026-10-13）

背景：按「日期版本号 + 六类分组（新增 / 变更 / 弃用 / 移除 / 修复 / 安全，同日固定顺序）」的
更新日志约定复查全仓对外文档，并按净化口径清理过程性措辞；随后对发布产物做一次全量
重编译与覆盖式发版核对（无代码变更）。

- [x] **更新日志.md**：头部格式约定补入「安全」；6 个日期节的分类顺序归一
      （20261011 / 20261003 / 20261002 / 20260928 / 20260922 / 20260913，其中 20261003 两段
      「修复」合并，写后复检零违例）；一处指向已废弃协作文件的引用改为实际挂链对象
      「CONTRIBUTING」。
- [x] **README.md**：Release 徽章日期 `20260929` → `20261013`；文档表「变更清单」括注补齐
      「新增 / 变更 / 修复 / 优化 / 安全」；能力行安装包体积 2.76MB → 2.77MB。对外文档禁用词
      全量扫描，命中均为子串误报（如 `fillMaxWidth` 内含 `llM`），无需改写。
- [x] **单测门禁与全量编译**：`gradlew testDebugUnitTest --rerun`（659 tests / 0 failures /
      1 skipped）→ `python build_apk.py --clean` 通过（V2+V3 签名、arm64-v8a、
      `verifyReleasePayload` 通过），产物 2908309 字节 /
      sha256 `E17346CEE4832977E0081B75689CCD2788BB2F18C8D3A09AD8339A766CFABC98`。
- [x] **真机冒烟（root，Android 10）**：`adb install -r` 成功；`am start` 后 `MainActivity`
      前台挂起，`versionCode=20261013` / `versionName=Jinn`，logcat 无 FATAL。
- [x] **发版（覆盖式，无代码变更）**：tag `20261013` 仍指 `b3d7739`；双端 Release 正文收紧为
      167 字符（20261013 段落精简版，符合发版说明 ≤200 字），资产覆盖上传
      （GitHub `--clobber` / Gitee 删附件 3376305 后新传 3377634），回下载核算双端 sha256
      与本地一致（`E17346CE…`）；双端 Release 目标提交仍为 `b3d7739`。


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

## 相关文档

| 文件 | 用途 |
|---|---|
| `README.md` | 功能总览、执行方式、版本规则、在线编译入口 |
| `更新日志.md` | 变更清单，一行一条 |
| `docs/PROJECT.md` | 技术栈、目录结构、架构、执行方式与已知注意点 |
| `.github/workflows/publish-release.yml` | 纯日期标签发布 |
| `.github/workflows/build-apk.yml` | 在线编译（自定义包名） |

---
