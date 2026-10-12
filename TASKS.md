# shso 任务看板 (TASKS.md)

> 过程记录，不参与对外文档同步；现行说明见 `README.md`、`docs/PROJECT.md`、`更新日志.md`。
> 建立时间：2026-09-11（上一版归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 版本规则

`versionCode` = 构建当日日期（`YYYYMMDD`），`versionName` = `Jinn`，Release Tag 与 `versionCode` 对齐；升级判定只认 `versionCode`。完整规则见 `README.md` § 版本规则。

---

## 当前状态速览（2026-10-12）

| 项 | 值 |
|---|---|
| 分支 | `main`，与 GitHub / Gitee 双端 `main` 同步（tag `20261012` → 见 § A141） |
| 许可 | **GPL-3.0-or-later**（2026-10-02 由 Apache-2.0 切换，强 Copyleft） |
| 单元测试 | 652 tests / 0 failures / 1 skipped |
| lint | 0 errors |
| release 体积 | 2.76 MB（zstd 原生库 475 KB 压缩后占比回归 + commons-compress 全量 keep），`verifyReleasePayload` 红线通过 |
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
| 列表性能 | `FileItem` 类型判定构造期一次算好（不再每帧重算正则）；选中判定走 HashSet；搜索 250ms 防抖；主页行用 derivedStateOf 隔离；目录元数据批量 stat（不逐条目 fork） |
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
| `/data/adb/shso` 是 0777 但 **SELinux 拦住第三方**（目录标签 `adb_data_file`）。实测：`shell` 域与 app 域（`run-as` → `u:r:runas_app`，派生自 `untrusted_app`）对该目录的 list / unlink / symlink / write **全部 Permission denied**，dmesg 有对应 `avc: denied { getattr }`；只有 `su`(magisk 域) 能写 |
| `/data/local/tmp` 子目录默认 `root:root 755`，adb（shell 身份）**无法写入** → 需 `su -c 'chmod 777'` 后再 push |
| 已知环境坑 | 应用进程的 environ **不含 `TMPDIR`**（`su -c` 也不补），而 `adb shell` 自带 `TMPDIR=/data/local/tmp` → 依赖 `$TMPDIR` 的脚本「adb 下正常、应用内失败」。执行环境须显式 `export TMPDIR` |
| 已知环境坑 | 本机 toybox `mktemp` 创建失败时把模板前缀打成 `(null)/`（如 `Failed to create directory (null)//data/…`）——`(null)` 不是路径的一部分，真实模板要减去该前缀再看 |
| 已知环境坑 | 本机 toybox 0.8.0 的 `chmod` 符号模式**忽略按类别给出的 `-s`**：`u-s` / `g-s` 为空操作，清 setuid/setgid 只有 `a-s` 生效。实测 `4777` 经 `u-s,g-s,go-w` 得 `4755`（setuid 残留），经 `a-s,go-w` 得 `755` |
| 备份 | 守卫相关全部内容备份在 `守卫模块备份`（工作区内）（基线 `d6c3b0f`） |
| 收件箱提示 | 复制到收件箱失败的提示按实际原因区分（超单文件上限 / 文件数 256 / 总容量 2GB / 读不到文件），容量与数量类提示写明 `/storage/emulated/0/Download/shso` |
| 发版 | tag `20261012`（纯数字，与 `versionCode` 对齐，**指向当次发版代码**）；双端同名 Release，资产 `app-release.apk` 2908305 字节，三端 sha256 一致（`CA6825CA…`）；A141 产物与 A140 逐字节相同，故资产未替换、正文未改，双端 tag 与 Release 目标提交已对齐 A141 发版代码；旧版发行程序已从双端移除（Release 与 tag 保留）。语法包侧：`syntaxpacks-v2` 指向 A114 提交（APK 不内置语法） |
| 在线编译 | 实跑 `workflow_dispatch` 通过（包名 `com.sina.news`，5m19s，产物 2908313 字节，`package: name='com.sina.news'`）；签名开关 `use_debug_signing` 由签名步单点产出，单元测试步与编译步以 `-PuseDebugSigning` 同口径消费（见 § A142） |

---

## 待办

### A144. 发版完整性核验与选中态反馈复核（2026-10-12）

`docs/AI-1-BUG.md` 无新增（止于第 349 轮），`docs/AI-2-BUG.md` 止于 §8.5、无变更。
第 349 轮列出的三条（218/219/220）已由 A141 的 `8e6718a` 处置，逐条复核确认闭环；
台账 §2.43 登记的 BUG-CONSIST-249（双列选中态不可见）经真机复现判为不成立。

- [x] **A141 改名复核**：`RootService` / `ExecuteConfirmDialog` / `FilePage` 为纯标识符改名，
      调用点与默认值等价、无行为变化；全库 `asRoot` 零命中（`hasRootAccess` 是子串误命中）。
- [x] **BUG-CONSIST-249（P3 CONSIST）改判 DISPROVEN**：登记内容称选中反馈仅靠「行尾 ✓」表达、
      窄列中难以察觉，建议改为选中行加容器底色。实读 `FilePage.kt:1204-1210`：行级 `.background`
      已按 `isSelected` 给整行铺 `Accent.copy(alpha = 0.16f)`（另有 `isHighlighted` 的更亮一档），
      该实现自 `e8ca598`（2026-09-28）即在位，根因与修法建议均与代码不符；真机复现见下。
- [x] **构建与回归**：`testDebugUnitTest` 652 tests / 0 failures / 1 skipped；`assembleRelease` +
      `verifyReleasePayload` 通过；产物 2908305 字节、sha256 `CA6825CA…`，与双端发布资产逐字节相同。
- [x] **依赖解析核验**：`releaseRuntimeClasspath` 实测 zip4j 2.11.6（CVE-2023-22899 修复版确在产物内）、
      commons-compress 1.27.1、zstd-jni 1.5.7-16、xz 1.9、sora-editor 0.23.6。
- [x] **发布完整性**：GitHub 与 Gitee 的 `20261012` Release 目标提交均为 `8e6718a`（= 发版代码提交），
      资产 `app-release.apk` 各一份且 sha256 与本地重建一致；更早的 tag 与 Release 保留、无残留发行程序。
- [x] **真机（root）**：`su -c 'pm install -r -t'` 覆盖安装成功，`am start` 后
      `mResumedActivity = com.mixradio.droid/.MainActivity`，crash buffer 无该包记录；
      文件页 → 设置 → 「全选文件」选中 Download 的 183 个文件，选中行整行底色与同页未选中的
      文件夹行对比明显（截图可辨），退出多选后状态复原；设备上装的是正式版，不卸载、不动用户数据。
- [x] **发布影响**：无代码改动，tag `20261012`、Release 资产与正文均不动。

### A143. 双列布局下「全选 / 多选」选中反馈复核（2026-10-12）

登记于台账 §2.43；复核结论见 § A144（判 DISPROVEN）。

### A142. 在线编译工作流签名凭据修复（2026-10-12）

实跑 GitHub Actions「在线编译 APK（自定义包名）」（`workflow_dispatch`，包名 `com.sina.news`）时发现：
工作流的「运行单元测试」步在 Gradle **配置期**中止——该步由 `8aa7301`（2026-10-11）新增，只跑
`./gradlew testDebugUnitTest`，未注入签名相关 env，而 `app/build.gradle.kts` 的 `signingConfigs`
在配置期无条件解析凭据。上游仓库挂了 Secret 却没接到这一步，Fork 侧根本没有 Secret 更无从取得，
两条路径都必然失败，在线编译入口整体不可用。

- [x] **BUG-BUILD-248（P1）**：签名步改为产出一处布尔输出 `use_debug_signing`（缺
      `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` 时为 `true` 并打 warning），不再产出 `sign_args`；
      单元测试步与编译步都改为 `-PuseDebugSigning="$USE_DEBUG_SIGNING"` 显式传入，测试步另补
      `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS_PASSWORD` 三个 env。开关只在一处产出。
      同一遗漏也存在于 `publish-release.yml` 的单元测试步（那里 Secret 是必需项，故只补同样的三个
      env，不引入开关），否则发布路径一样倒在配置期。
- [x] **本地对照**：`local.properties` 临时替换为只含 `sdk.dir` 后
      `:app:testDebugUnitTest -PuseDebugSigning=true` 通过；不带该属性即复现线上那条
      `签名密码未配置` 报错。
- [x] **实跑回归**：run `38248296938`（提交 `4a6d4289`）5m19s 成功；产物
      `app-release-com.sina.news` → `app-release.apk` 2908313 字节，`aapt2` 读得
      `package: name='com.sina.news'` / `versionCode=20261012`；`apksigner` 验签通过、证书
      `CN=Jinn`（上游 Secret 齐备，走 Release JKS 分支而非调试密钥）；37 条目，无语法包与数据表，
      `lib` 仅 arm64-v8a。
- [x] **真机（root）**：`su -c 'pm install -r -t'` 安装后 `am start` 正常，
      `mResumedActivity = com.sina.news/com.mixradio.droid.MainActivity`，crash buffer 无输出；
      验证后 `pm uninstall`，设备不留测试包。
- [x] **发布影响**：只改 CI 工作流，不进 APK 产物；发版资产与 tag `20261012` 均不动。
      `docs/在线编译.md` 不改——其中「未配 Secret 即用调试密钥签名」的描述本就是应有行为，
      是工作流没实现它。

### A141. 命名与文档一致性收口（2026-10-12）

`docs/AI-1-BUG.md` 推进到第 349 轮；「文档维度 100% 覆盖」后新开 3 条（218~220），
均为 CONSIST / P3。逐条读实码与 workflow 复核后一并修复；改名为标识符级，产物字节不变。

- [x] **AI-1-BUG-218（P3）命名规范禁用 `asRoot`，代码内仍在用**：规范规定「是否以 ROOT 执行」
      统一叫 `runAsRoot`（跨层参数）/ `useRoot`（局部开关），而执行引擎参数仍叫 `asRoot`。
      采取改代码而非改文档：`runCommandSync` / `forceKillProcessTree` / `killProcessGroup` /
      `runKillSync` 的声明与命名实参、`ExecuteConfirmDialog.onPick` 回调形参、`FilePage`
      调用点全部改名 `runAsRoot`；两处 source-contract 单测的源码文本断言同步。
      `forceKillProcessTree` 的超时回收调用保留两参形式——该分支已由
      `reclaimOnTimeout && runAsRoot` 守卫，默认值与显式传参等价。
- [x] **AI-1-BUG-219（P3）`大文件只读方案.md` 结论句与差异表不符**：结论称「差异仅在三处」，
      同文 §3 实为 6 行（编码、块缓存对象、块缓存实现、只读阈值、渲染容器、索引粒度）
      → 改为「差异共六处」并列明项。
- [x] **AI-1-BUG-220（P3）`在线编译.md` 的包名字符集与产物名不准**：workflow 正则
      `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$` 还允许数字与下划线；artifact 名实为
      `app-release-` 前缀 + 用户完整输入。两处订正并给出示例。
- [x] **回归**：`testDebugUnitTest` 652 tests / 0 failures / 1 skipped；`assembleRelease`
      与 `verifyReleasePayload` 通过。产物 2908305 字节，sha256 与 A140 发布资产相同
      （标识符改名不进字节码常量池）。
- [x] **外部建议复核**：`docs/AI-2-BUG.md` 无新增；`docs/AI-1-BUG.md` 第 290~346 轮的
      200~217 已由 49076c6 / b0e9c64 / 9f54fbe / db64931 闭环，第 349 轮仅剩 218/219/220，
      本轮全部处置；长期 OPEN（PERF-030 / PERF-182 / CONSIST-118 / COMPAT-093 / CONC-148）维持原判。
- [x] **发布**：产物字节与 A140 一致，Release 资产不替换、正文不改；tag `20261012` 重指到本轮提交。

### A140. 依赖供应链 / CI 加固 / 主题与工具链保鲜（2026-10-12）

`docs/AI-1-BUG.md` 推进到第 343 轮，新开 7 条（211~217）：主题字段完整性、悬空文档引用、
依赖 CVE、CI 注入面、混淆映射归档、工具链可重现性、行尾一致性。逐条读实码复核后全部处置。

- [x] **AI-1-BUG-213（P2 SEC）zip4j 2.11.1 落在 CVE-2023-22899 影响范围内**：
      该漏洞语义是「解密 WinZip AES 加密 ZIP 时不总是校验 MAC」——被篡改或损坏的密文
      可能被静默解出错误内容而非报错拒绝，而项目确有 AES 加密 ZIP + 中文密码的解密路径。
      升到 2.11.6（修复版为 2.11.3，最新 2.11.6）。同轮核验 commons-compress 1.27.1 /
      zstd-jni 1.5.7-16 / tukaani-xz 1.9 均无已知 CVE。
- [x] **AI-1-BUG-214（P3 SEC）`publish-release.yml` 的 tag 输入直接插值进 `run:`**：
      GitHub Actions 在 shell 执行前做文本替换，表单值含 `$(...)` / 反引号会被展开执行，
      而该 job 持有 Release 签名密钥；与已修复的 `build-apk.yml` 属同类不一致。
      改为经 `env: INPUT_TAG` 注入，脚本内只引用变量。
- [x] **AI-1-BUG-215（P3）release 经 R8 混淆而 mapping 未归档**：混淆映射既不随 Release
      上传也未作 artifact 保存，线上堆栈无法还原，且 R8 每次构建的映射不同、事后无法重建。
      两个 workflow 各加一步 `upload-artifact` 归档
      `app/build/outputs/mapping/release/mapping.txt`（不进 Release Assets，避免降低逆向门槛）。
- [x] **AI-1-BUG-216（P3）`gen_syntax_packs.py` 产出的 zip 不可重现**：
      `writestr(名字, 数据)` 取当前本地时间作条目时间戳，同一份输入每次产出字节不同的归档。
      改为构造固定时间（1980-01-01）的 `ZipInfo`。实测连续两次生成 sha256 一致，
      并与仓库原包逐成员比对内容完全相同（63 项，仅时间戳字段不同）。
- [x] **AI-1-BUG-217（P3）仓库无 `.gitattributes`，行尾取决于各机 `core.autocrlf`**：
      生成器显式写 LF 而 checkout 端可能转 CRLF，同一仓库在不同机器上字节不一致，
      也使 `syntax-packs/*.json` 长期呈现 62 项假 modified。新增 `.gitattributes`
      固定文本类型为 LF、批处理为 CRLF、二进制免转换。
- [x] **AI-1-BUG-211（P3）`AuroraTextStyleSet.shrunk()` 漏掉 `monospace` 字段**：
      `copy` 手写 14 个字段而数据类有 15 个，漏传不报错——一旦按 KDoc 用于全局缩字号，
      编辑器正文 / 行号 / 统计行不会随之缩小。补上该字段。
- [x] **AI-1-BUG-212（P3）注释引用不存在的 `design-spec.md` 附录 C**：该文件从未进入仓库，
      引用落空。改为指向本文件各常量的行内注释。
- [x] **回归**：`testDebugUnitTest` 652 tests / 0 failures / 1 skipped；
      `assembleRelease` 与 `verifyReleasePayload` 通过。产物 2908305 字节。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280）**：构造 WinZip AES-256 加密 ZIP
      推到 `/sdcard/Download/`，经文件页「自动解压文件」正确弹出密码框，输入密码后解出
      `内层文件.txt`（33 字节），内容逐字节与源一致——升级后的解密与完整性校验路径正常。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 第 332~343 轮的 7 条 OPEN 全部处置；
      `docs/AI-2-BUG.md` 止于 §8.5，其 13 条 OPEN 经比对已在 A6x~A12x 闭环，无新增。

### A139. 多用户路径 / 编辑历史回收 / CI 与构建脚本输入（2026-10-12）

`docs/AI-1-BUG.md` 推进到第 324 轮，新开 4 条 CONFIRMED OPEN：路径适配、数据回收、
CI 输入校验、构建脚本产物定位各一条。逐条读实码复核后全部处置。

- [x] **BUG-COMPAT-234（P2）多用户路径适配不一致**：共享存储根在文件页 / 首页 /
      文件选择器 / APK 提取 / 收件箱 / 外部唤起白名单六处被写死为 `/storage/emulated/0`，
      而该常量只在主用户成立（次用户为 `/storage/emulated/<userId>`）。
      报告推荐的两条路线中，方案 A（manifest 声明 `android:supportsMultipleUsers="false"`）
      被 AAPT 直接拒绝——该属性不在公有 SDK 属性表内，第三方应用不可用，
      故改采方案 B：`PathDisplay` 新增 `INTERNAL_STORAGE_PATH`，
      由 `Environment.getExternalStorageDirectory()` 运行时推导，经
      `^/storage/emulated/[0-9]+$` 形状校验（个别 ROM 返回异常路径时不得进白名单）
      后采用，推导失败回落到 `PRIMARY_STORAGE_PATH`（`/storage/emulated/0`）；
      `ApkExtractor.DOWNLOAD_DIR`、`ExternalOpen.INBOX_DIR`、
      `EXTERNAL_FILE_URI_ALLOWED_PREFIXES`、`FilePage` 默认右列、`HomePage` 目录初值
      一并改引该值。白名单语义复核：`/storage/emulated/01` 与 `/storage/self/*`
      仍被拒（`isRemovableVolumePath` 排除 emulated/self）；
      `APP_PRIVATE_PREFIXES` 保持主用户字面量不构成漏洞——次用户私有路径
      `/data/user/<uid>/…` 命中不了任何允许前缀，fail-closed。
- [x] **BUG-CONSIST-235（P2）编辑历史没有回收路径**：历史以 `history:<绝对路径>`
      存于 SharedPreferences，而删除 / 重命名 / 移动文件时不触碰这些键——
      删掉一个装满脚本的目录会留下一整批永不回收的键。修法：`RootFileManager.delete`
      的三条成功路径（`rm -rf` / 符号链接 / Java 回退）统一经局部函数 `removed()`
      调 `EditHistoryManager.forget(path)`（前缀匹配，命中的是「该路径及其整棵子树」，
      无需先列举目录内容）；`rename` / `moveFile` 的两条成功路径经 `renamed()` / `moved()`
      调 `moveHistory(old, new)`（目录整棵子树一起搬；目标键已存在则不覆盖，
      但源键一律清除，避免留下孤儿键）。
- [x] **同域补护栏**：把键的定位规则抽成纯函数 `HistoryKeys`（`keysUnder` / `movePlan`）
      并让 `forget` / `moveHistory` 委托之，JVM 单测覆盖「自身 + 整棵子树命中」、
      「`/sdcard/di` 不得命中 `/sdcard/dir/...` 与 `/sdcard/dirx`」、
      「目标键已存在时新键为空（不覆盖）」四种语义。另补两条源码契约测试
      （删除/重命名/移动三条路径必须接线、共享存储根必须运行时取值且四份调用点
      不得再出现写死的 `/storage/emulated/0`）。
- [x] **BUG-BUILD-236（P3）CI 的 `artifact_retention_days` 未校验**：该表单值直接
      `fromJSON(...)` 交给 `upload-artifact`，只接受 1~90 的整数，非法值要到最后一步
      才报错，前面几十步编译全白跑。改为在校验步骤内前置校验（经 `env:` 注入而非
      `run:` 内直接插值，顺带消除 `$()` / 反引号被 shell 执行的注入面），
      留空回退 30，结果经 `steps.validate.outputs.retention` 供上传步骤引用。
- [x] **BUG-BUILD-237（P3）`build_apk.py` 的 `locate_apk` 会取到旧产物**：
      函数只在「第一个存在的候选目录」里查找，而 `app/build/outputs/apk/...` 与
      `.tmp/build_out/...` 可能同时存在，另一目录的旧 APK 会被当成本次产物。
      改为跨两个候选目录收集全部候选，再按 mtime 取最新。
- [x] **回归**：`testDebugUnitTest` 652 tests / 0 failures / 1 skipped（A138 为 645）；
      `assembleRelease` 与 `verifyReleasePayload` 通过。产物 2908305 字节，
      sha256 `2f9e0a9b…`（与 A138 的 `6297e1ec…` 不同，确为重新构建）。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280）**：删除端到端——为
      `/storage/emulated/0/a139_probe.txt` 生成历史后经文件页多选删除，prefs 中该键
      随之消失、其余文件历史不受影响；重命名端到端——把带历史的
      `/storage/emulated/0/_精灵输入法/历史1.txt` 批量重命名为 `h1renamed_0.txt`，
      prefs 中历史键同步改名且内容完整、旧键消失（验证后已复原文件名）。
      新包 `pm install -r` 返回 Success，启动与文件页正常。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 第 324 轮的 4 条 OPEN 全部处置；
      第 329 轮记录的 A138 产物级核验（204/205/206）与双端 tag / 资产 sha256 一致
      经复核无出入；`docs/AI-2-BUG.md` 止于 §8.5，无新增。

### A138. 打包配置与产物元数据（2026-10-12）

`docs/AI-1-BUG.md` 推进到第 318 轮，新开「产物级验证」维度——不再只读构建脚本，
而是解包 release APK 把「配置声明」与「真实产物」逐项对表。三条 CONFIRMED OPEN
全部落于 `app/build.gradle.kts`。先独立复现（对未修复的 APK 解包），三条均属实。

- [x] **BUG-BUILD-231（P3）packaging 的 assets 排除是空操作**：`assets/**` 与
      `assets/dexopt/**` 两条排除从未命中任何条目——AGP 的 assets 合并发生在资源
      打包之前，packaging 排除只作用于 JAR/AAR 的 `resources`，对 `assets/` 无效；
      而 `baseline.prof` / `baseline.profm` 由 AGP 的 profile installer 单独注入，
      根本不在可排除的集合里。两条规则删掉，改为在 `verifyReleasePayload` 里正向断言
      「必需 assets 必须在产物中」（`assets/sora-themes/shso-dark.json` 由
      `SoraMonarchGrammars` 常量引用，被任何排除规则波及即构建失败）。
- [x] **BUG-BUILD-232（P3）语音匹配资源排除不彻底**：只排除 `…/language/bm/**`，
      同目录的 `dmrules.txt`（3545 字节）仍在包内。`commons-codec` 的
      `bm/` + `dmrules.txt` 只服务 Metaphone / Soundex 一族，源码侧零引用
      （`grep` `Metaphone|Soundex|ColognePhonetic|Nysiis|MatchRating|Caverphone`
      在 `app/src/main/java` 无命中）。规则扩为整目录
      `org/apache/commons/codec/language/**`，并在校验任务里加负向断言。
- [x] **BUG-SEC-233（P3）产物携带构建期 git revision**：AGP 默认写入
      `META-INF/version-control-info.textproto`（120 字节，含构建时的 git revision），
      release 构建类型内加 `vcsInfo { include = false }` 关闭。量小、无直接实害，
      但私有 fork 场景下会把源码头暴露进产物，与项目既有的 META-INF 激进裁剪口径一致。
- [x] **复核（未改动）**：ABI 集合（仅 arm64-v8a）、ProGuard 保留、zstd 取 aar 变体、
      `app-metadata.properties`、`META-INF/services/**`（ChromeOS 与 CharsetProvider
      的 SPI 注册）逐项解包核对，均与配置声明一致。
- [x] **回归**：`testDebugUnitTest` 645 tests / 0 failures / 1 skipped；
      `assembleRelease` 与 `verifyReleasePayload` 通过（无语法包、无数据表）。
      产物由 2909817 降至 2908305 字节（−1512）。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：新包
      `pm install -r -t` 返回 Success，启动进入终端页无崩溃，`logcat` 无应用侧 FATAL；
      滑屏切文件页 → `Download` → 打开 `c0.xml` 进编辑器，语法配色与工具栏正常
      ——反证 `assets/sora-themes/shso-dark.json` 运行时可用（该资产正是校验断言的正向目标）。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 第 318 轮 3 条 OPEN 全部处置，剩余
      PERF-182、CONSIST-118、COMPAT-093（BY_DESIGN）、CONC-148（WONTFIX）维持；
      `docs/AI-2-BUG.md` 止于 §8.5，其列出的 12 条历史项经逐条回溯均已在 A62~A135
      各轮闭环或裁定，无新增。

### A137. 提取失败窗口补漏 + 三条假绿护栏（2026-10-12）

`docs/AI-1-BUG.md` 已推进到第 301 轮，积压 4 条 CONFIRMED OPEN：1 条逻辑 + 3 条测试
假绿。逐条复现后全部处置。

- [x] **BUG-LOGIC-227（P2）失败分支漏清「本次已落盘但未登记」的目标**：A136 的
      `created` 只在 `copyOne` 返回成功后追加，而 `copyOne` 报失败时目标仍可能已经落盘——
      shell 侧 cp 与 mv 都成功、随后整条命令超时/被杀（退出码 -1）。此类目标不在
      `created` 里 → 不被回滚，残留 Download。失败分支补一条按「循环开始前是否存在」
      gating 的清理：`if (existed) 0 else rollbackNewlyCreated(listOf(dst.absolutePath))`，
      即「那时不存在、现在出现了，只能是本次写的」。同名覆盖刷新（`existed=true`）仍不触碰。
- [x] **BUG-TEST-230（P3）语法包回滚用例复刻实现→假绿**：原用例在测试里重写了一遍
      `backup != null` 的分支逻辑，生产代码改回「失败统一 delete()」测试照样通过。
      把 `FileWrite` / `writeGrammarFile` / `rollback` 由 `private` 放开为 `internal`，
      用例改为调用真实实现，断言「回滚后文件仍在且内容还原为导入前」。
- [x] **BUG-TEST-229（P3）未知条目大小用例恒真**：原 `assertThrows` 首行是
      `if (-1L < 0L) throw`，恒真。改为按生产判据
      (`declaredSize != null && >= 0 && > 上限`) 断言「未知大小放行且不计入已消费字节」。
- [x] **BUG-TEST-228（P3）重复归档路径用例只测 HashSet**：删掉只断言集合行为的假用例，
      在 `ArchiveExtractorExtractTest` 新增端到端用例——`ZipOutputStream` 拒写同名字面量，
      故用路径别名 `dup.txt` / `./dup.txt`（归一化后同一 canonicalPath），断言返回
      `Failure` 且消息含「重复条目」。
- [x] **回归**：`testDebugUnitTest` 645 tests / 0 failures / 1 skipped；
      `assembleRelease` 与 `verifyReleasePayload` 通过（无语法包、无数据表）。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：常规路径提取
      ARunit v30 → `/sdcard/Download/ARunit-30.APK` 38865615 字节，无 `.part-` 残留；
      失败路径预置同名目录 `ARunit-30.APK/`（内含 `keep.txt`）后提取，弹「目标路径已是
      目录，未提取：/storage/emulated/0/Download/ARunit-30.APK」，目录与 `keep.txt`
      原样保留、未生成嵌套 `.part-` 文件——即 `existed=true` 分支确实不触碰既有目标。
      本轮窗口（判失败而目标已落盘）需 su 命令中途超时或被强杀，设备侧不可控构造；
      本机无分包应用，mid-way 失败同样不可构造，由纯函数 + 源码契约单测覆盖。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 第 301 轮 4 条 OPEN 全部处置；剩余
      PERF-182、CONSIST-118、COMPAT-093（BY_DESIGN）、CONC-148（WONTFIX）维持；
      `docs/AI-2-BUG.md` 止于 §8.5 无新增；`BUG.md` 中 `BUG-PERF-030` 维持 A109 裁定。

### A136. APK 提取的失败态与目标占用（2026-10-12）

`docs/AI-1-BUG.md` 已推进到第 263 轮，新增 OPEN `AI-1-BUG-LOGIC-199`。按其
fix_hint 落实时，在真机上把「拷贝中途失败」的相邻分支一并走查，发现第二条同族缺陷。

- [x] **BUG-LOGIC-225（P2）分包提取中途失败不回滚**：`extract()` 逐个复制 base + 各 split，
      任一失败即 `return`，此前已复制成功的目标留在 Download 成「装不上的半套」——文件在、
      名字对，装的时候才报缺分片。改为 `created` 记录「本次新建」并在失败时
      `rollbackNewlyCreated(created)` 只删这些（同名覆盖刷新时目标本已存在，误删会销毁用户
      上一次的可用产物），失败消息回传「已清除 N 个 / 先前已有的 M 个保持原样」。
      同时 `copyOne` 两条分支统一「先写同目录 `.part-<nanoTime>`、成功后改名」，杜绝
      cp / 流拷贝中途失败留下的 size>0 半截 APK。
- [x] **BUG-LOGIC-226（P2）目标被同名目录占住时报假成功（真机复现）**：
      在 Download 建同名目录 `ARunit-30.APK/` 后提取，`mv -f tmp dst` 的语义是「移入该目录」
      且返回 0，`test -s <目录>`（size 4096）与 Kotlin 的 `dst.length()` 双重判据同时通过 →
      弹「已提取到 Download：1 个文件」，产物实际躺在 `<目录>/<名字>.part-<nano>`。
      `copyOne` 入口判 `dst.isDirectory` 提前失败并带出目标路径。
- [x] **护栏自身的可维护性**：`PerfAndNestingRegressionTest` 中「root cp 退出码不得被后续命令
      覆盖」原以 `substring(start, start + 1200)` 取样，在 `copyOne` 入口插入守卫后
      `exit $rc` 被挤出窗口而误报失败。区间改为取到 `// 无 ROOT`（root 分支终点），
      不再受函数体内注释增删影响。
- [x] **回归**：`testDebugUnitTest` 645 tests / 0 failures / 1 skipped（新增
      `ApkExtractorTest` 两条：回滚纯函数 + 源码契约），`assembleRelease` 与
      `verifyReleasePayload` 通过。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：目录占用场景
      改前复现「报成功 + 目录内出现 .part- 嵌套文件」，改后提示「目标路径已是目录，未提取：
      /storage/emulated/0/Download/ARunit-30.APK」且目录保持空；删除目录后重试成功落盘
      38865615 字节，Download 无任何 `.part-` 残留。成功/失败两条 shell 链路另以等价脚本验证
      （成功 rc=0、失败 rc=1 且无目标无残留）。本机无分包应用（`split_config*.apk` 扫描为空），
      mid-way 失败无法端到端构造，回滚分支由单测覆盖。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 第 263 轮的 `AI-1-BUG-LOGIC-199` 已处置；
      剩余 PERF-182（BY_DESIGN）、CONSIST-118 / COMPAT-093（BY_DESIGN）、CONC-148（WONTFIX）
      维持；`docs/AI-2-BUG.md` 止于 §8.5 无新增；`BUG.md` 中 `BUG-PERF-030` 维持 A109 裁定。

### A135. 终端页底栏预留的第二份真值（2026-10-12）

继续复审被搁置的 `AI-1-BUG-CONSIST-184`，判定同为**误置**：此前只看开关为真那一半（确实归零），
开关为假时该页并未走统一换算。立案 1 条（P3），修复并加护栏。

- [x] **BUG-CONSIST-224（P3）终端页底部预留写死 58dp**：`DockBar.kt` 约定「高度固定 56.dp，
      页面侧预留必须与之相等，不得各处写死」，`HomePage` / `FilePage` / `SettingsPage` 均经
      `dockBarBottomPadding()`，仅 `TerminalPage` 按开关自行判断并写死 58.dp。偏差 2dp 肉眼难辨，
      风险在底栏改高度时只有这一页不跟随。改为 `if (isImeVisible) 0.dp else dockBarBottomPadding(...)`。
- [x] **回归**：`testDebugUnitTest` 643 tests / 0 failures / 1 skipped。原先的护栏只断言「终端页须
      按开关归零」，反把写死值锁成了期望；已改为断言四个页面统一走换算函数、且不得出现
      `hideDockBar … else <数字>.dp`。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：设备 `shared_prefs`
      中 `hide_dock_bar` 为 true（A129 验证残留），以 root 改写为 false 并重启进程 → 底栏四 Tab
      出现；终端页按钮行（中断/清屏/历史/Enter/发送）下沿 y≈1959px，底栏玻璃层上沿 y≈1986px，
      无重叠无缝隙；验证后该键改回 true，恢复设备原状态。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 止于第 259 轮 TERMINAL、`docs/AI-2-BUG.md` 止于 §8.5，
      均无新增；`BUG.md` 唯一 OPEN 仍为 `BUG-PERF-030`（A109 裁定维持）。

### A134. 缺密钥回退签名的配置期失效（2026-10-12）

此前把 `docs/AI-1-BUG.md` 第 245 轮的 `AI-1-BUG-CONSIST-185` 判为「已有裁定（BUG-CONSIST-200）维持」，
复审后判定为**误置**：BUG-CONSIST-200 只补了工作流一侧，构建脚本一侧从未改过，
回退分支实际从未生效。立案 1 条（P2），修复并加护栏。

- [x] **BUG-CONSIST-223（P2）配置期强制读签名凭据**：`signingConfigs.create("release")` 在配置期
      整体求值且不受 `useDebugSigning` 约束，无条件调用 `getSigningFile()` 与两次
      `getSigningPassword()`；后者在既无环境变量也无 `local.properties` 时 `error()`。Fork 未继承
      Secrets 时工作流虽传 `-PuseDebugSigning=true`，构建仍在配置阶段中止（`:app:help` 都跑不起来）。
      改为把凭据读取放进 `if (!useDebugSigning)`；`debug` 构建类型的签名配置按同一开关分流，
      回退路径取 AGP 自带 debug 配置。
- [x] **复现（修复前）**：临时摘掉 `local.properties` 的 `signing.*` 三项、清空
      `KEYSTORE_PASSWORD` / `KEY_ALIAS_PASSWORD`、`KEYSTORE_FILE` 指向不存在文件 →
      `:app:help -PuseDebugSigning=true` 在配置阶段 2 秒内 FAILURE「签名密码未配置」。
      本地长期测不出，是 `local.properties` 的兜底密码掩盖了这条无凭据路径。
- [x] **验证（修复后）**：同前复现环境 `assembleRelease -PuseDebugSigning=true` → BUILD SUCCESSFUL，
      产物 `apksigner --print-certs` 为 `CN=Android Debug`；恢复 `local.properties` 后常规
      `assembleRelease verifyReleasePayload` → BUILD SUCCESSFUL，产物签名 `CN=Jinn`（release 密钥未受影响）。
- [x] **回归**：`testDebugUnitTest` 643 tests / 0 failures / 1 skipped（新增护栏 1 条：
      契约断言 release 签名块内的 `getSigningFile()` / `getSigningPassword()` 必须在
      `if (!useDebugSigning)` 之内，且 `debug` 构建类型有回退分支）。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：新构建 release APK
      （2909817 字节）`su -c 'pm install -r -t'` 覆盖安装 Success；`dumpsys package` 报
      `versionCode=20261012 / versionName=Jinn`；冷启动后 `mResumedActivity` 为本应用
      `MainActivity`，`logcat -b crash` 无本应用记录。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 止于第 259 轮 TERMINAL，无新增；`docs/AI-2-BUG.md`
      止于 §8.5，无新增；`BUG.md` 唯一 OPEN 仍为 `BUG-PERF-030`（A109 裁定维持）。

### A133. 解压顶层剥离与语法包直链导入（2026-10-12）

复核 `docs/AI-1-BUG.md` 已推进至第 259 轮（第 242~259 轮的发现均未进入上一轮处置范围），
新增 OPEN 仅 2 条，逐条对源码复核后属实，立案 2 条（P3×2），修复并加护栏。

- [x] **BUG-LOGIC-221（P3）条件 A 剥离顶层前缀后多出 `unnamed_` 空目录**：根目录仅 1 个顶层目录时
      剥离前缀直接解压到当前目录，而顶层目录条目自身（`abc/` 与 `abc`）剥离后为空串，落进
      `safeDest` 为 `./`、`..` 准备的 `fallbackName` 分支，被赋占位名 `unnamed_<hash>_` 并由
      `dest.mkdirs()` 建成实体空目录；zip / tar / 7z 三条链路同病。三处目录条目在剥离后为空串时
      直接 `continue`（该条目就是 `reserveTargetDirectory` 已预占的 target 本身）。
- [x] **BUG-LOGIC-222（P3）语法包直链推断 id 先取末段再剥 query**：原写法
      `substringAfterLast('/').substringBefore('?')` 顺序相反，query 自身含斜杠的直链
      （带签名的下载链、带路径参数的网关）取到的是 query 里的片段 —— 语法以该随机片段为 id
      落盘、`exts` 回退成它，编辑器不会用它匹配 `.kt` 一类文件，表现为「导入成功却不高亮」。
      抽出纯函数 `idFromUrl`：先剥 query/fragment 再取末段，`#` 一并处理。
- [x] **回归**：`testDebugUnitTest` 642 tests / 0 failures / 1 skipped（新增 2 条护栏）；
      `assembleRelease verifyReleasePayload` 通过（无语法包、无数据表）。
      `ArchiveExtractorExtractTest` 新增「含显式顶层目录条目的归档不得解出 unnamed 占位目录」
      端到端用例；`SyntaxPackSourceTest` 新增 `idFromUrl` 六种 URL 形态断言。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：push 自造
      `topdir_test.zip`（`abc/` + `abc/f.txt`，211 B）到 `/sdcard/Download`，文件页长按 →
      「自动解压文件」→ 提示「已解压到: /storage/emulated/0/Download/abc」；
      `ls -a .../Download/abc` 仅 `f.txt`，`ls /sdcard/Download | grep -c unnamed_` 为 0，
      `logcat -b crash` 无本应用记录。222 为纯函数，由单测覆盖。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 第 259 轮 2 条 OPEN 全部处置；第 259 轮残留的唯一
      待确认点依赖第三方 Monarch 库内部实现，静态无法确证，不立案；`docs/AI-2-BUG.md`（§8.5）
      无新增；`BUG.md` 唯一 OPEN 仍为 `BUG-PERF-030`（A109 裁定维持）。
      经复核不改动：`AI-1-BUG-CONSIST-184`（`TerminalPage` 底距 58dp，`hideDockBar` 时已归零）、
      `185`（维持 `BUG-CONSIST-200` 裁定）、`PERF-182`（维持 BY_DESIGN / `BUG-PERF-203`）。

### A132. 安装链路与文件操作的边缘缺陷复查（2026-10-12）

复核 `docs/AI-1-BUG.md` 第 253 轮新增的 11 条 OPEN 项（该文件已从第 241 轮推进到 253 轮），
逐条对源码复核后全部属实，立案 11 条（P2×1 / P3×10），全部修复并加护栏。

- [x] **BUG-LOGIC-210（P2）OBB 幂等判据用 inode 比较恒假**：`readObbIdentity` 取
      `stat -c %i:%s:%y`，标识文件本身而非内容；目标与本次解压出的 OBB 是同时存在的两个文件，
      inode 必然不同 → 幂等分支不可达，重复安装同一 XAPK 稳定落进「内容不同」失败分支。
      新增 `readObbContentSignature`（`stat -c %s` + `sha256sum` → `size:sha256`，失败返回 null
      按「内容不同」处理，fail-closed），判据改比内容签名。
- [x] **BUG-LOGIC-211（P3）多 OBB 部分幂等时 finally 误删已落位 OBB**：内层 finally 只比
      `copiedObbTargets.size`，漏掉走幂等分支的既有目标 → 完整成功路径被判成未全部成功，删掉
      本次刚落位的 OBB；该 finally 在 APK 安装之前执行，结果是「APK 装好、OBB 没了」。判据改为
      `copiedObbTargets.size + preexistingObbTargets.size != obbFiles.size`。
- [x] **BUG-LOGIC-212（P3）XAPK 解压暂存建在用户可见目录**：`.shso_xapk_<uuid>` 建在 XAPK 同目录
      （通常 `/sdcard/Download`），进程被强杀时 finally 不执行，残留整份 base + 分片，且不在启动
      清扫覆盖内。迁到 `cacheDir`；`sweepExecutionTempFiles` 增加同类目录清扫（只删 mtime 超
      1 小时者，避免误删进行中的解压）。
- [x] **BUG-LOGIC-213（P3）非规范 OBB 一律兜底成 main**：XAPK 内条目常写作 `main.obb` /
      `patch.obb`，兜底命名都拼成 `main.<vc>.<pkg>.obb`，第二个撞「重复 OBB 目标」使整包安装
      失败。兜底按名字前缀区分 main / patch。
- [x] **BUG-SEC-214（P3）FileProvider 暴露整棵外部存储**：`file_provider_paths.xml` 残留
      `<external-path name="external" path="." />`。全库 `getUriForFile` 仅 1 处
      （`installApkViaSystem`），入参是 `cacheDir/install-confirm` 下的校验副本，该配置已成死项
      → 删除，只留 `<cache-path name="cache" path="install-confirm/" />`。
- [x] **BUG-CONSIST-215（P3）文档仍写 Toast 统一 LONG**：`README.md` 与
      `docs/B站专栏-shso分享.md` 各订正 1 行。后者属其它工作线迁移中的未提交文件，只改内容不
      纳入提交。
- [x] **BUG-LOGIC-216（P3）`raw:` 路径未过 `normalizeSdCardAlias`**：`file://` 分支过了、
      `raw:` 分支没过，Downloads provider 的 `/sdcard/...` 形态被白名单拒掉，降级为一次无谓的
      全量拷贝。两条分支统一口径。
- [x] **BUG-LOGIC-217（P3）共享文件名不限长度**：超长 `DISPLAY_NAME` 让 `createNewFile` 抛
      `ENAMETOOLONG` 返回 null，调用方却报「同名文件过多」，把用户引去清理收件箱而无效。新增
      `truncateUtf8Bytes`，按 UTF-8 字节截断到 200 字节（保留扩展名、不在字符中间切断）。
- [x] **BUG-LOGIC-218（P3）Java 拷贝重名探测少试一个名**：`n++` 后判上限的 do-while 只试
      `0..998`，shell 路径试 `0..999`；且 `File.exists() || pathExistsQuiet()` 对同一路径重复
      探测。统一为先判存在再自增。
- [x] **BUG-LOGIC-219（P3）对比输出扩展名取自整条路径**：无扩展名文件配上含点的父目录时，
      `substringAfterLast('.')` 取出的「扩展名」带多层子目录，输出路径落盘失败。改为先取文件名
      再取扩展名。
- [x] **BUG-LOGIC-220（P3）新文件历史落成空串 key**：`currentFilePath` 为 null 时
      `addHistory("", …)` 写进所有新文件共享的 `history:`，既取不回又会被顶掉。无归属不写。
- [x] **回归**：`testDebugUnitTest` 640 tests / 0 failures / 1 skipped；`assembleRelease
      verifyReleasePayload` 通过（无语法包、无数据表）。新增护栏 10 条；另订正
      `PerfAndNestingRegressionTest` 中钉住旧循环形态的断言（旧断言与被修复的实现冲突）。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：冷启动前在应用 cache
      预置带旧 mtime（2020-01-01）的 `.shso_xapk_sweeptest`，启动后被清扫；`/sdcard/Download`
      无 `.shso_xapk_*`；`logcat` 无本应用 FATAL；界面渲染正常；设备侧 `stat -c %s` +
      `sha256sum` 组合可用。OBB 幂等与含 `patch.obb` 的 XAPK 需现成素材，以单测与静态复核为准。
- [x] **外部建议复核**：`docs/AI-1-BUG.md` 第 253 轮 11 条 OPEN 全部处置；
      `docs/AI-2-BUG.md`（§8.5）无新增；`BUG.md` 唯一 OPEN 仍为 `BUG-PERF-030`（A109 裁定维持）。

### A131. Toast 时长统一 + APK 安装链路审查（2026-10-12）

需求：所有 Toast 统一为短时长；审查 APK 安装逻辑是否有缺陷。共立案 5 条，全部修复并加护栏。

- [x] **BUG-CONSIST-209（P3）Toast 统一 LENGTH_SHORT**：全库 22 处 `Toast.makeText` 一律 `LENGTH_LONG`
      （3.5s），改为 `Toast.LENGTH_SHORT`（2s）；`grep LENGTH_LONG app/src` 零命中。
      涉及 MainActivity(2) / SettingsPagePartials(10) / TerminalPage(7) / TextEditorDialog(1) /
      FilePage(1) / SettingsPage(1)。
- [x] **BUG-LOGIC-205（P1）`pm install-create` 不得带 `-p`**：`-p` 是 INHERIT_PACKAGE（会话置
      `MODE_INHERIT_EXISTING`，基础包从已装包继承），不是校验包名。真机 A/B（Android 10 / ColorOS）：
      WhatsApp 整套分包（base + split1..3，92567061 B）带 `-p` 时 4/4 streamed 成功、commit 报
      `Missing existing base package`；去掉 `-p` 后 commit `Success`，`pm path` 返回 base + 3 split。
      改为 `pm install-create -r -d -t -S $totalSize`，并删除无用的 basePkg 推导与相关 KDoc 表述。
- [x] **BUG-EDGE-206（P2）暂存副本清理时机**：`startInstall` 尾部无条件删除暂存副本，而系统安装器经
      `content://` **异步**读取（`startActivity` 返回只代表已拉起）→ 无 ROOT 安装读不到文件。
      改为 `if (installAsRoot) stagedPath?.let { File(it).delete() }`，非 ROOT 路交启动清扫回收。
- [x] **BUG-EDGE-207（P3）分包暂存失败漏清理**：提前 `return` 绕过统一清理，`cache/install-confirm`
      留一整份套件；补删除已暂存兄弟分片。
- [x] **BUG-CONSIST-208（P2）ROOT 探测时序**：弹窗 `lockedInstallAsRoot` 在首次组合即锁定，承载 effect
      的 key 不含 `willInstallAsRoot`，探测结果回来不重跑 → 冷启动外部唤起安装时锁成 false（显示
      「系统安装器」，并把 false 交给 `startInstall` 使分包安装必然失败）。补
      `LaunchedEffect(willInstallAsRoot, show, fileItem?.path)` 在交出所有权前同步锁定值。
- [x] **真机验证（root，PACM00 / Android 10 / 1080×2280 / density 480）**：`am force-stop` 后经 VIEW
      intent 唤起，修复前弹窗显示「系统安装器」、修复后显示「ROOT 静默安装（无系统安装器确认）」；
      点「确认安装」后 `pm path com.whatsapp` 返回 base + 3 split；`pm uninstall com.whatsapp` 复原，
      临时文件已清。Magisk 策略 uid=10263 policy=2 已核实。
- [x] **回归**：`testDebugUnitTest` 630 tests / 0 failures / 1 skipped；`assembleRelease
      verifyReleasePayload` 通过（无语法包、无数据表）。新增护栏 3 条
      （`分包会话创建不得带 -p`、`暂存副本不得在系统安装器拉起后立即删除`、`ROOT 探测结果到达后必须同步安装方式显示`）。
- [x] **外部建议复核**：`docs/AI-1-BUG.md`（止于第 241 轮 TERMINAL）与 `docs/AI-2-BUG.md`（§8.5）无新增；
      `BUG.md` 唯一 OPEN 仍为 `BUG-PERF-030`（A109 裁定维持）。

### A130. A129 改动面全量回读与台账收口（2026-10-12）

换视角重查 A129：不查「功能是否实现」（A129 已验），只查「是否还有别处默认底栏恒占位 56dp」、
「两个开关是否只有一处来源」、「提示的冷启动语义在 Activity 重建下是否成立」。

- [x] **预留高度残留扫描**：全库 `56.dp` / `58.dp` / `dockBarBottomPadding` 实读，预留点仅
  `HomePage`(210/474)、`FilePage`(1765)、`SettingsPage`(375)、`TerminalPage`(490) 五处，
  均已随 `hideDockBar` 换算，无第六处硬编码残留。
- [x] **开关来源唯一性**：`hideDockBar` / `showSwipeHint` 的读写仅 `AppSettings`(77/81/197/202)、
  门控 `MainActivity`(369)、开关 `SettingsPagePartials`(164-175)，无第二处直读 SharedPreferences。
- [x] **提示重建语义**：转屏 / 深色模式切换重建 Activity 时 `MainContainer` 重组，`LaunchedEffect(Unit)`
  重跑但进程级标记已置位 → 不重弹；进程被系统回收后重进属冷启动，弹一次，均与「每次冷启动一次」相符。
- [x] **组合取舍**：同时开启「隐藏底栏」并关闭「左右提示」后，页间导航只剩左右滑屏且无任何视觉提示，
  属两个独立开关的必然组合；加兜底提示会与「不勾选则永久不再提示」直接冲突，不加。
- [x] **外部建议复核**：`docs/AI-1-BUG.md`（第 241 轮 TERMINAL）与 `docs/AI-2-BUG.md`（§8.5）均无新增；
  `BUG.md` 唯一 OPEN 仍为 `BUG-PERF-030`（A109 裁定维持）。
- 无代码改动，不发版。

### A129. 设置页新增「隐藏底栏」与「左右提示」（2026-10-12）

需求：左右滑屏切换导航页面的能力保留；设置页新增两个开关 —— 「隐藏底栏」隐藏底部的主页 / 终端 / 文件 / 设置，
让每一页占满屏幕高度（默认关闭）；「左右提示」勾选则每次冷启动提示「左右滑屏可切换菜单」，
不勾选则永久不再提示（默认开启）。

- [x] 设置存储：`AppSettings` 新增 `hideDockBar`（键 `hide_dock_bar`，默认 false）、
  `showSwipeHint`（键 `show_swipe_hint`，默认 true）。setter 命名 `updateHideDockBar` /
  `updateShowSwipeHint` —— 与属性同名会被 JVM 视为属性 setter 的同签名重复定义（`Platform
  declaration clash`），且改名前的报错会连带一批虚假的 `Unresolved reference` 干扰定位。
- [x] 底栏门控：`MainContainer` 的 DockBar 绘制条件由 `!WindowInsets.isImeVisible` 改为
  `!WindowInsets.isImeVisible && !appSettings.hideDockBar`。
- [x] 预留高度换算：`DockBar.kt` 新增顶层 `dockBarBottomPadding(hideDockBar)` → `0.dp` / `56.dp`，
  主页、文件页、设置页原先写死的 `padding(bottom = 56.dp)` 改为调用；终端页底部留白改为
  `if (isImeVisible || appSettings.hideDockBar) 0.dp else 58.dp`。漏掉这一步的表现是「底栏没了、
  原位仍留一条空白带」，与需求相反。
- [x] 冷启动提示：文件级 `@Volatile swipeHintShown` + `LaunchedEffect(Unit)` 弹一次。
  键取 `Unit` 而非 `showSwipeHint` —— 后者的表现是在设置页拨动开关时就地弹一条，且同一进程内
  「关掉再打开」时而弹、时而不弹（取决于本进程是否已弹过）；提示按需求只在冷启动出现。
  用进程级标记而非 `remember`/`rememberSaveable`，转屏、深色模式切换等配置变更导致的
  Activity 重建不重弹。
- [x] 设置入口：`SettingsPagePartials.kt` 新增 `SettingsInterfaceGroup`（两个 `AuroraSwitchPreference`），
  挂在设置页「文件行为」组之后。
- [x] 护栏测试：`ConsistencyCleanupRegressionTest` 新增「隐藏底栏与左右提示两个开关必须真正生效」，
  断言两个键名与默认值、底栏门控表达式、提示文案、进程级标记、提示 effect 不得以开关为 key、
  `dockBarBottomPadding` 定义与三页
  调用（且三页不得再写死 `56.dp`）、终端页留白表达式。两个开关都跨「存储 → Activity → 各页布局」
  三层，任一层漏接都表现为「开关点得动、界面没变化」。
- [x] 单测 626 通过 / 0 失败 / 1 跳过；`assembleRelease` + `verifyReleasePayload` 通过（APK 2909869 字节）。
- [x] 真机（root）：开启「隐藏底栏」后逐页截图，主页列表、终端按钮行（中断 / 清屏 / 历史 / Enter / 发送）、
  文件页工具条（顶部 / 底部 / 新建 / 同步 / 刷新）均抵到系统导航条上沿，原底栏位置无空白带；
  反复冷启动后 `uiautomator` 层级中底栏四页签始终缺席，关闭后四页签回归。
- [x] 提示两态实测：默认（开）冷启动探针 60 次采样 `toast_hits=36`；关闭后同一探针 `toast_hits=0`；
  在设置页把开关关掉再打开，两次拨动期间 `dumpsys window` 均无 Toast 窗口（提示不再随开关就地弹）。
- [x] 左右滑屏切页全程可用（底栏隐藏时三连滑由主页抵设置页）。

### A128. 冷启动列表被 ROOT 探测阻塞、列举无超时与构建配置残留（2026-10-12）

需求：冷启动进入目录后列表要等 ROOT 探测落定才出内容；同时复核外部建议末尾的 OPEN 项。

- [x] **BUG-PERF-197**：`listDirectory` 在 `RootService.isRootGranted == null` 时无条件
  `awaitRootState()`（50ms 轮询、上限 3s），而探测本身要跑一次 `su -c id`（Magisk 首次授权或
  未放行时最长 1.5s 超时）。列举是只读操作，本地读得出来就没必要等 ROOT —— 改为先本地列举、
  能拿到条目就直接返回；本地拿不到条目（受保护的 `/data/adb/…`、未授存储权限时 FUSE 把有权目录
  也呈现成空目录）才等探测。写入路径保留原口径，`preferRoot()` 不再被列举调用。
- [x] **BUG-PERF-198**：`FilePage` 缺「ROOT 落定后重读」effect，而 `HomePage` 有。首帧列举与探测
  并发时受保护目录读到的是空或失败，探测落定后两列不会自动更新（用户看到「有 ROOT 却一直空」，
  只能手动刷新）。新增 effect，只在「上一次已知未授权 → 本次已授权」这一次跃迁上刷新两列，
  避免冷启动时与各列首屏列举重复跑一轮 su。
- [x] **BUG-PERF-199**：列举的 `runCommandSync` 未传 `timeoutMs`，沿用默认 120s；su 停在 Magisk
  授权弹窗上时列表挂满两分钟。新增 `LIST_TIMEOUT_MS = 15_000L` 并显式传入。
- [x] **BUG-CONSIST-200**：`docs/在线编译.md` 的主流程称「CI 使用调试密钥签名」，而
  `build-apk.yml` 强制要求 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD`（GitHub 不把上游 Secrets 复制
  到 Fork），外部用户按其文档操作会在「写入 Release 签名密钥」步失败。改为缺密钥时回退
  `-PuseDebugSigning=true`，编译步按上一步的输出决定是否传该参数；文档订正签名描述，并补
  「安装时提示签名冲突」一条说明两个 Secret 与调试签名的差别。
- [x] **BUG-CONSIST-201**：`gradle/libs.versions.toml` 保留 20 条未被任何 `*.gradle.kts` 引用的
  声明（jetbrains-compose-*、jetbrains-lifecycle-*、navigation3、materialKolor、aboutlibraries，
  以及 dokka / baselineprofile / composeHotReload 等插件，与 BUG-CONSIST-195 同源的模板残留），
  `gradle.properties` 另留着两条只对 Dokka 插件生效的属性。删除未引用项，
  `androidx-lifecycle-runtime-compose` 的内联版本改为 `version.ref`；新增护栏断言「每条
  library / plugin 都在构建脚本中被引用、每个 version 都有 version.ref」。
- [x] **BUG-CONSIST-202**：`CONTRIBUTING.md` / `CONTRIBUTING.en.md` 的技术栈表仍写 compose-bom
  2026.08.00，实际为 2026.09.00。改齐，并加护栏断言三份文档的 compose-bom 版本与版本目录一致。
- [x] **BUG-PERF-203 记 BY_DESIGN**：双列各挂 `LaunchedEffect(pane.currentDirectory)`，冷启动并发
  两次 su 列举。批量 stat 后单次列举 0.1s 量级、su 往返约 20ms，右列改为聚焦才加载会先呈现空列，
  代价大于收益；两列内容同屏可见，并发只改变完成顺序、不减少总工作量。维持现状。
- [x] **BUG-CONC-204**（原 AI-1-BUG-PERF-183）：`shsoDirEnsured` 是 `@Volatile`
  布尔，「读标记 → 跑 su → 置位」非原子，冷启动时主页与文件页两列同时调 `ensureShsoDir`
  会各发一次 `mkdir` su（`mkdir -p` 幂等，正确性无损，但 su 在 Magisk 侧串行，挤占同时发起的列举）。
  加 `Mutex` + 锁内二次检查，失败时不置位以保留「失败即下次重来」的语义。
- [x] 单测 625 通过 / 0 失败 / 1 跳过；`assembleRelease` + `verifyReleasePayload` 通过。
- [x] 真机（root，PACM00 / Android 10）：同一条 su 命令对 `/data/adb/shso`（10 项）、
  `/sdcard/Download`（188 项）、`/system/etc`（98 项）三目录逐一返回，退出码均 0，三段合计 612ms
  （含 3 次 adb 往返）；安装本版后冷启动切「文件」页，两列 400ms 内已出内容，`uiautomator`
  导出的层级里 `/storage/emulated/0` 的目录项完整渲染、无读取失败提示，`logcat` 无本应用 FATAL。
  并发 su 采样（设备侧 `ps` 轮询，150 次采样、间隔约 70ms）冷启动窗口内峰值并发 4 个 su，
  其中 3 个来自应用（两列列举 + 一次建立目录），未见建立目录并发多次；采样间隔大于单次 su
  存活时间，该数据只作辅助。
- [x] 不带密钥的分支实测：`-PuseDebugSigning=true` + 指向不存在文件的 `KEYSTORE_FILE` 可正常
  编译，产物签名者为 `CN=Android Debug`，即 CI 缺 Secret 时的回退路径可用。
- [x] 提交 `740b72a`（代码）、`3ea9b0c`（构建配置与文档）。

### A127. 目录列表加载慢：元数据通道逐文件 fork stat（BUG-PERF-196）（2026-10-12）

需求：进入目录后文件列表加载过慢。逐段计时排除 su 往返（≈20ms）、名称通道（`find -print0`，30ms 量级）、
排序过滤（纯内存无 I/O）之后，瓶颈落在 `listDirectory` 的元数据通道。

- [x] **根因**：元数据通道用 `find … -exec sh -c 'for f ; do … stat …; done' sh {} +`，
  `{} +` 只把路径批量交给**一个** `sh`，循环体内的 `stat` 仍是每文件一次 fork。
  真机单次进程创建 21~27ms（最坏 50ms 量级），188 条目的 `/sdcard/Download` 即 188 次 fork，
  实测该趟 9.34s（`time` user 3.84 + sys 5.52，时间几乎全在进程管理）。
- [x] **修法**：改 `find … -exec stat -L -c '%n|%A|%s|%Y' {} + 2>/dev/null | sed 's/|\([^|]*\)|\([^|]*\)|\([^|]*\)$/\n\1|\2|\3/' | tr '\n' '\0'`
  —— find 一次调用批量取，`%n` 自带名称、`sed` 按**最后**三字段切开（文件名含 `|` 亦正确）、
  `tr` 把行分隔换成 NUL。输出与旧实现逐字节同构，下游 `parseStatMeta` / UI 层零改动；
  同一 188 条目目录 9.34s → 0.11s，200 条目 1.1~10s → 0.05~0.07s。
- [x] **连带处置 1**：重试判据由「两趟 find 名称集合全等」改为「元数据通道出现名称通道没有的名字」。
  断链软链被 `stat -L` 拒绝、天然无元数据记录，是正常态；全等判据会让含断链软链的目录
  每次进入都白跑一整轮列举。
- [x] **连带处置 2**：新增 `filterKeys { it in nameSet }` —— `%n` 通道是「行分隔再转 NUL」，
  文件名含换行时该条被劈成两段、后半段形如「名字后缀|元数据」，按名配对可能命中恰好同名的
  兄弟项，滤掉即免疫。
- [x] **不变项**：`-L` 语义、退出码 3/4/0/-1、`META_SEP` 两侧 NUL 判定、断链软链降级为
  「非目录 / 0 字节」、`listDirectory(dirPath, attempt = 0)` 签名（`DualPaneStateRegressionTest`
  锁定 JVM 三参签名）全部不变。
- [x] **验证**：`od -c` 逐字节核对输出成对记录，NUL token 数恰为条目数 2 倍（188→376、10→20），
  含 `|` 与含空格的文件名拆分正确；批量 `stat` 遇断链软链跳过该条并 rc=1、其余照常输出
  （夹具建在 `/data/local/tmp`，`/sdcard` 是 FUSE 不允许 `ln -s`）。
- [x] 单测 619 通过 / 0 失败 / 1 跳过；`assembleRelease` + `verifyReleasePayload` 通过。
- [x] root 真机安装本版：冷启动后 `stat` 进程残留 0；`command -v stat` → `/system/bin/stat`；
  「主页」`/data/adb/shso` 8 项、「文件」`/sdcard/Download` 188 项均正常渲染，类型与大小正确，
  无空态与读取失败误报，`logcat` 无本应用 FATAL。
- [x] 提交 `c487208`，tag `20261012` 重指该提交，双端 Release 资产替换并核验三端 sha256 一致。

### A126. 构建配置残留与权限文档漂移（BUG-CONSIST-194 / 195）（2026-10-12）

复核 `docs/AI-1-BUG.md`（已到第 233 轮）末尾剩余 OPEN，处置两条 P3。

- [x] **BUG-CONSIST-194（原 AI-1-BUG-CONSIST-174）**：`docs/PROJECT.md` 第 116、199 行称
  「`.so` / 二进制执行前 `chmod 755`」，与实现（root `a+x`、非 root `u+x`，只补执行位并在执行后
  按记录的原权限还原；`.sh` 经 `sh` 运行不改权限）不符。按实现改写两处。
- [x] **BUG-CONSIST-195（原 AI-1-BUG-CONSIST-175）**：`gradle.properties` 保留 KMP/iOS/Native
  模板项（`xcodeproj`、`kotlin.mpp.*`、`kotlin.native.*`、`kotlin.incremental.{multiplatform,js,js.klib,native,wasm}`、
  `compose.experimental.macos`、`kotlin.apple.xcodeCompatibility.nowarn`），而工程为纯 Android
  （`settings.gradle.kts` 仅 `include(":app")`）。删除上述无用项，保留 `org.gradle.*`、`android.*`、
  `kotlin.code.style`、`kotlin.incremental`/`.jvm.fir`。
- [x] **AI-1-BUG-CONSIST-118 维持 BY_DESIGN**（同上轮）；093 BY_DESIGN、148 WONTFIX 不变。
- [x] 新增回归护栏：`ReleaseGateRegressionTest.构建配置不得残留多平台模板项`。
- [x] 单测 619 通过 / 0 失败 / 1 跳过；`assembleRelease` + `verifyReleasePayload` 通过。
- [x] APK 与上版逐条目比对 39/39 内容哈希一致（仅容器时间戳不同）；root 真机安装启动正常，`logcat` 无 FATAL。
- [x] 提交，tag `20261012` 重指该提交，双端 Release 资产替换并核验三端 sha256 一致。

### A125. 静默失败面清扫 + AI-1 残留项落地（BUG-CONSIST-191 / 192 / BUG-SEC-193）（2026-10-12）

换维度查「失败被吞掉、界面却当作成功」的路径（空 catch、`runCatching` 结果被丢弃、
`getOrDefault` 把失败折成默认值、写入失败无提示）。主干已闭环，另把 `docs/AI-1-BUG.md`
末尾剩余的四条 OPEN 逐条实读后处置三条。

- [x] **静默失败面实读**：`data/` 与 `ui/` 全域逐处读上下文。结论：清理型吞异常属预期；
  `EditHistoryManager.readFileOrNull` 失败返回 `null`（损坏原文另存固定键）而非空列表；
  `SoraMonarchGrammars` 失败留日志且主题不可用即拒绝装语法；`TextEditorDialog` 保存链路
  逐条回传失败原因；`RootService` 命令超时有可见说明。无新增缺陷。
- [x] **BUG-CONSIST-191（原 AI-1-BUG-CONSIST-171）**：`formatBytes` 的 `"%.1f MB".format`
  走默认区域，逗号小数点的区域显示「34,0 MB」，与文件列表（固定 `Locale.ROOT`）写法不一致。
  改 `String.format(Locale.ROOT, …)`，函数提为 `internal` 并加 `Locale.GERMANY` 行为断言。
- [x] **BUG-CONSIST-192（原 AI-1-BUG-CONSIST-172）**：`ExternalOpen.resolveToRealPath` 里
  `val isFileScheme = … == "file"` 与紧随其后的 `when` 判定重复且无消费点，删除；加缺席断言。
- [x] **BUG-SEC-193（原 AI-1-BUG-SEC-173）**：`gradle-wrapper.properties` 只校验 URL 形态、
  不校验发行包内容，CI 每次在全新 runner 上按该包编译发版。补 `distributionSha256Sum`，
  取值以 `gradle.org/release-checksums` 与 `services.gradle.org` 的 `.sha256` 两源一致为准。
- [x] **AI-1-BUG-CONSIST-118 维持 BY_DESIGN**：收件箱拒绝原因已可辨识（A113），
  自动 TTL 删除用户可见目录的真实文件不可接受。
- [x] 单测 618 通过 / 0 失败 / 1 跳过；`assembleRelease` + `verifyReleasePayload` 通过。
- [x] root 真机：34MB 大文件经 `VIEW` 意图打开，走入巨型只读分段模式，状态条「字节数 34.0 MB / 34.0 MB」正常，
  `logcat` 无本应用 FATAL。
- [x] 提交 `00780ff`，tag `20261012` 重指该提交，双端 Release 资产替换并以 sha256 核验三端一致。

### A124. UI 布局健壮性清扫：长文本挤压相邻固定元素（2026-10-12）

AI-1 第 146 轮自述第二个 UI 盲区：此前只查「命中区尺寸」，未查「用户可控长文本把相邻固定元素挤出
视野」（编辑器顶栏文件名压掉 ✕ 即此类）。按该维度全库实读，无残留，无代码改动。

- [x] **固定关闭/删除入口的行**：`BookmarksDialog`、`SyntaxPackDialog#PackRow`、`FilePage#查找栏`、
  `TextEditorDialog` 顶栏（已修）与设置面板标题行（枚举字面量）逐处核对，用户可控文本侧均带
  `weight(1f)` + `maxLines` + 省略号，或本行不含用户输入。
- [x] **`ContentViewerDialogs` 图片操作行排除**：`iconStyle` 为 48sp 字形 + `padding(8)`，命中区约 74dp，
  原就不低于触控约定，故不出现在 AI-1 的 168 清单中。
- [x] **非加权文本的测量顺序**：`FilePermissionDialog` 账户候选行的 `accountName`（无 `weight`）曾疑为
  挤压点，按 Compose Row 规则排除——`label` 位于末位且带省略号，只会自身截断；`accountName` 取值域有界。
- [x] **滚动容器内嵌可滚动子项**：`AuroraWindowDialog` 的 30 个调用点逐处核对，需固定头部的窗口均自建头部，
  其余标题为固定文案；`FilePermissionDialog` 的 `LazyColumn` 有 `heightIn(max = 300.dp)` 兜底。
- 无代码改动，不发版。

### A123. 同类风险模式扫描与外部建议复核（2026-10-12）

A122 落地后台账唯一 OPEN 仍为 `BUG-PERF-030`（A109 裁定维持架构取舍）。无新增改动项，转做
两项复核，避免只从「已修条目」单一视角看问题。

- [x] **A122 两类缺陷的全库模式扫描**：
  - 「`;` 串接命令后整行退出码取自末条命令」——扫全库 cp/chmod/chown 命令串，仅 `ApkExtractor#copyOne`
    一处构成假成功（已修）；`RootFileManager.kt:740` 的 `cp -r && chmod` 在 cp 失败时短路、退出码即 cp 的
    失败码，只可能「假失败」；`ApkInstaller` 的两处 cp 为单命令或带显式 `exit`；`TextEditorDialog#permRestoreCmd`
    取 chown 退出码，chmod 失败而 chown 成功不可达。
  - 「按外部输入规模无界预分配」——带容量实参的集合构造点全库仅 `TextCompare.kt` 三处，已统一收敛。
- [x] **`docs/AI-1-BUG.md` 第 145/146 轮残留候选逐条实读**：`TextEditorDialog` 顶栏 ✕、
  `FileListViewSettings` ActionCell、`FilePermissionDialog` 账户候选行、`SettingsPage`「关于」入口
  四处均已按「clickable 在外、`minimumInteractiveComponentSize` 在内」补齐；
  `BuiltInFilePicker` 顶栏星标/设置（36dp 等分紧凑工具栏）与 `FilePage` 列头聚焦行（`fillMaxWidth` 整行可点）
  属刻意设计或本已达标，维持。AI-1-BUG-COMPAT-168 闭环成立。
- [x] **`docs/AI-2-BUG.md` 复核**：报告止于 §8.5，13 条 OPEN 清单已全部在 A6x~A12x 各轮落地，无新增项。
- [x] **台账头部刷新**：`BUG.md` 的 `repo_head` / `source_dirty_worktree` 由 A119 更新为 A122（`1778aaf`）。
- 无代码改动，不发版。

### A122. `docs/AI-1-BUG.md` 长期残留项落地：提取退出码 + 对比行数上限（2026-10-12）

`docs/AI-1-BUG.md` 已推进到第 180 轮（连续多轮标注 FINAL、内容为「复核无缺陷」留档），其「剩余项」表
却长期停在同两条 P3 —— `AI-1-BUG-EDGE-169` 与 `AI-1-BUG-LOGIC-170`。两条在此前多轮复核中均被判
真实缺陷（非误判、非 BY_DESIGN），只是优先级低于已修项而一直挂着，A122 一并落地。
`docs/AI-2-BUG.md` 无新增（止于 §8.5）。

- [x] **P3 · cp 失败被行尾 `test -s` 掩盖（BUG-LOGIC-189 / 原 AI-1-BUG-LOGIC-170）**：
  提取命令 `cp -f $src $dst && chmod 0644 $dst; chown …; restorecon …; test -s $dst` 用 `;` 串接，
  整行退出码取自最后的 `test -s`，`cp` 的失败被完全覆盖。首次提取（目标不存在）时 `test -s` 才如实报错，
  所以缺陷只在「重复提取刷新」这个正常场景暴露：`collisionSuffix` 对同包返回空串、目标复用同名文件，
  `cp` 失败后旧文件仍在（`size > 0`），`code == 0` 且 `dst.length() > 0` 两处校验同时通过 → 报「已提取」，
  用户拿到旧版本或磁盘写满时的半截文件。修法：`rc=$?` 先记录 `cp && chmod` 的成败，属主/上下文等
  尽力而为的命令与判据排在其后，末行 `exit $rc` 让 `cp` 的失败直达调用方；空文件校验仍由 Kotlin 侧
  `dst.length() <= 0L` 兜底。
- [x] **P3 · 共同行对比行数无上限（BUG-EDGE-190 / 原 AI-1-BUG-EDGE-169）**：COMMON_LINES 需把基准文件
  每一行同时放进 `HashSet` 与 `HashMap`，链路上却没有行数或容量上限。`hashCapacity` 用 Float 除法，
  `expected ≥ 约 1.61e9` 时钳到 `Int.MAX_VALUE`，容器首次 `put` 即按 2^30 槽分配（约 8GB）→
  `OutOfMemoryError`（`Error` 不被 `catch (e: Exception)` 接住，直接崩进程）；`countLines()` 以 Int
  累加，约 21 亿行处溢出为负、连带选反基准文件。修法：`countLines()` 返回 `Long`；基准行数
  （两文件取小，即真正构建集合的那个）超 100 万即抛 `CompareException` 并提示改用逐行对比；
  `hashCapacity` 结果 `coerceIn(16, 1 shl 22)`。守卫排在集合分配之前，超限文件不会先吃一遍内存。
- [x] **回归测试**：`PerfAndNestingRegressionTest` 新增 3 例 ——
  `提取 APK 的 root cp 退出码不得被后续命令覆盖`（源码断言 rc → restorecon → 判据 → `exit $rc` 的顺序）、
  `共同行对比超行数上限必须拒绝而不是 OOM`（两文件各 100 万零 1 行）、
  `行集合预分配容量必须有界`（`hashCapacity(Int.MAX_VALUE) <= 1 shl 22`）。
- [x] **验证**：`testDebugUnitTest` 616 tests / 0 failures / 1 skipped；`assembleRelease` +
  `verifyReleasePayload` 通过。root 真机（PACM00 / Android 10）：
  - 退出码语义：源不存在且目标预先存在时，旧形态退出码 `0`、新形态 `1`；成功路径（源为
    `/system/framework/framework-res.apk`）退出码 `0`、目标 23958375 字节。
  - 完整链路：文件页设置弹窗 → 提取 APK → 提取 `com.standardar.service` →「已提取到 Download：1 个文件」，
    产物 `ARunit-30.APK` 38865615 字节；对同一应用再提取一次（覆盖刷新路径）仍报成功、字节数不变，
    `logcat` 无异常。
  - 行数上限一条无真机证据：编辑器无法正常装载百万行文本，验证落在单测。

### A119. 编辑器设置面板滚动范围收窄（BUG-COMPAT-188）（2026-10-12）

`docs/AI-1-BUG.md` 报告 #156~#159（末轮标注 FINAL）逐处核验 38 处
`minimumInteractiveComponentSize` 与 `clickable` 的相对顺序，全部正确；确认 COMPAT-187 的 9 处补齐与
COMPAT-168 完全闭环，并自行纠正对 `BookmarksDialog` 书签导航行的误判（漏看行内已补 48dp 的 `✕`
子节点会撑高整行）。报告剩余 3 条（收件箱去重 / TTL、tif/tiff 预览、OBB 锁窄窗口）此前已记为
DISPROVEN / BY_DESIGN / WONTFIX。`docs/AI-2-BUG.md` 无新增（止于 §8.5 轮次总结）。台账唯一 OPEN 项
仍为 BUG-PERF-030（A109 已裁定维持不修）。

按「同一现象被多处同报」的清单复核，发现 A118 在补齐过程中自己引入的残留（BUG-COMPAT-188）：

- [x] **P3 · 面板滚动容器套住标题行（BUG-COMPAT-188）**：A118 把 `verticalScroll` 挂在面板**根**
  `Column` 上，标题行（含 `✕` 关闭入口）与 `HorizontalDivider` 同处滚动区内。正常屏幕下面板内容约
  556dp、不溢出，看不出问题；一旦溢出（小屏、分屏、系统大字号），滚动即把标题与关闭入口一并卷走，
  关闭按钮既看不见也点不到，只能退回系统返回键。改为把滚动范围收窄到内容区：外层 `Column` 去掉
  `verticalScroll`（只留 `padding(horizontal = 14.dp, vertical = 10.dp)`），标题 `Row` 与
  `HorizontalDivider` 直接挂在外层 `Column` 下，原 `when (page) { ... }` 整块（含尾部 `Spacer(6.dp)`）
  包进新的内层 `Column`，内层只带 `fillMaxWidth()` + `verticalScroll(rememberScrollState())`。
- [x] 单测：`testDebugUnitTest` **613 tests / 0 failures / 1 skipped**（纯布局结构调整，JVM 单测无法
  覆盖布局滚动行为，不新增用例）。`assembleRelease` + `verifyReleasePayload` 通过。
- [x] 真机（root，PACM00 / Android 10 / density 480）：正常屏幕下面板不溢出、压不出滚动，故
  `wm size 1080x1400`（467dp 高）制造溢出后再进入编辑器设置面板（文件页 Download → `aa_test.txt` →
  动作菜单「编辑文本」→ 顶栏「设置」；屏幕尺寸变更会重建 Activity，面板状态不保留）。滚动前 `✕`
  `[829,206][973,350]`（144×144px = 48dp）、标题 `设置` `[107,250][203,307]`、`编码` 行 y=436~485、
  `写入 BOM` 行被面板下沿裁到 2px 高；
  `input swipe 540 1000 540 400` 后 `✕` 与标题 bounds **完全不变**，`写入 BOM` 行由 y≈1156 上移至
  y=402，`编码`/`换行`/`语法包`/`另存为`/`显示行号` 滚出可见区，`草稿快照` 等被裁切行进入可见区；
  在滚动后位置点击 `✕`（`(901,278)`）→ 面板关闭并回到编辑器。滚动区内部行另测（`✕` 在滚动区之外，
  不能代表内部）：点 `显示行号` 行 `开`→`关`，缩屏并 `input swipe 540 1000 540 500` 后该行由 y=1012 上移至
  415，再点同一行（`(300,437)`）`关`→`开`，命中判定随滚动偏移正确跟随。随后 `wm size reset` 复原，
  清理设备与本地临时文件。
- [x] 同类形态全量复核 14 处 `Dialog`/`AlertDialog` 中带滚动容器的形态：`BookmarksDialog`（标题/底栏在
  滚动区外）、`ApkExtractDialog`（`Surface.height(maxContentHeight)` 定高，仅列表滚动）、
  `BuiltInFilePicker`（标题在滚动区外）、`ImageViewerDialog`（无滚动容器）均正确；`SyntaxPackDialog` 行内
  `✕` 是列表项删除动作（列表定高 200dp），非弹窗关闭入口。唯一形似的是共享组件 `AuroraWindowDialog`
  （标题与摘要同在 `verticalScroll` 内）——该组件不带 `✕`，关闭走外部点击/返回键，标题滚走只影响观感、
  不阻断操作，判 BY_DESIGN 不改（波及全部 10 余个调用点，收益与风险不成比例）。
- [x] 构建与发布：APK 2909869 字节，sha256 `eb0145e9…`；tag `20261012` 重指至本次发版提交，
  双端 Release 资产替换并核验三方一致。语法包侧 `syntaxpacks-v2` 未变（APK 不内置语法）。

### A118. 文本级可点目标触控尺寸兜底（BUG-COMPAT-187）（2026-10-12）

`docs/AI-1-BUG.md` 第一百四十六 / 一百四十七轮（#154 / #155）复核上一提交的 COMPAT-168 修复，确认闭环，
同时补报两处「修复后精确残留」：设置页右上角「关于」入口（≈40dp）与书签弹窗导航行（估高约 24dp）。
前者属实并已纳入；后者为静态估高的误判 —— 该行内含已补齐 48dp 的「✕」子节点，行高 ≥56dp，无需改动。
`docs/AI-2-BUG.md` 无新增（止于 §8）。台账唯一 OPEN 项仍为 BUG-PERF-030（A109 已裁定维持不修）。

A117 修的是「补尺寸这个动作**写错了顺序**」，覆盖范围只到**已经加过** `minimumInteractiveComponentSize`
但顺序颠倒的站点。顺着 AI-1 的原话（「行内次要操作触控尺寸**系统性**不足」）复核全库 `.clickable`
（含分行链式写法）后确认：另有一批站点**自始没加过任何补偿**，是同类缺陷的残留，需一并补齐：

- [x] **P3 · 文本级可点目标补齐至 48dp（BUG-COMPAT-187）**：9 处 / 7 文件 —— 首页「从文件管理器选择」
  （裸文字入口 ≈30dp）、文件页搜索栏 `✕`（无 padding，≈14×21dp）与长按动作菜单项（43dp）、
  列表设置弹窗 `ActionCell`（43dp）、权限弹窗账户候选行（43dp，误点会改错属主/组）、终端命令历史行
  与「清空历史」（≈36dp）、编辑器设置面板 `CompactSettingRow`/`CompactOptionRow`/`CompactBackRow`（35dp）、
  设置页右上角「关于」（≈40dp）。一律按 `clickable` 在外、`minimumInteractiveComponentSize` 在内的顺序补齐。
- [x] **判定边界（不改）**：有可见底/固定高度的容器属**设计尺寸**，不改 —— 36dp 工具行按钮
  （`SurfaceHover` 底色）、42dp 路径行（`heightIn(min = 42.dp)`）、色轮预设色块（底色+边框）。
  近似项复核后排除：应用列表行（两行文本+8dp 内边距 ≈52dp）、书签导航行（内含 48dp 子节点）、
  复选框行与语法包备用直链入口（均已有 `heightIn(min = 48.dp)`）。
- [x] **连带风险处置**：编辑器设置面板行高 35 → 48dp 后根页内容约 500 → 630dp，小屏/系统大字号下会
  超出对话框高度，而该面板原先**无滚动容器**（超出部分既画不出也点不到）→ 同步补
  `Modifier.verticalScroll(rememberScrollState())` 兜底。滚动范围后由 A119 收窄到内容区（标题行
  与 `✕` 留在滚动区外）。
- [x] 单测：`testDebugUnitTest` **613 tests / 0 failures / 1 skipped**（纯 UI 尺寸与修饰符，
  JVM 单测无法覆盖指针命中区，不新增用例）。`assembleRelease` + `verifyReleasePayload` 通过。
- [x] 真机（root，PACM00 / Android 10 / 1080×2280 / density 480）：首页「从文件管理器选择」
  bounds `[400,465][681,609]`（144px=48dp），在字形上缘外 `(540,480)` 点击 → 弹出「选择执行文件」；
  搜索栏 `✕` bounds `[900,270][1044,414]`（144×144px），在 `(972,282)` 点击 → 搜索栏关闭；
  列表设置弹窗 `搜索文件` `[180,1573][528,1717]`、`全选文件` `[552,1387][900,1531]` 均 144px（原 ≈123px）；
  动作菜单 6 行、编辑器设置面板 12 行、编码二级页 10 行全部 144px；面板出现 `scrollable="true"`
  节点 `[65,176][1015,2068]`（竖向滚动生效）；设置页「关于」可点节点 `[895,120][1068,264]`（144px），
  在字形 `[937,156][1026,228]` 之外、框内的 `(905,132)` 点击 → 弹出关于对话框。
  验证后清理设备与本地临时文件。
- [x] 构建与发布：APK 2909869 字节，sha256 `fa3ccc51…`；tag `20261012` 重指至本次发版提交，
  双端 Release 资产替换并核验三方一致。语法包侧 `syntaxpacks-v2` 未变（APK 不内置语法）。

### A117. 行内次要操作触控命中区与编辑器顶栏（BUG-COMPAT-185 / BUG-EDGE-186）（2026-10-12）

`docs/AI-1-BUG.md` 第一百三十九 / 一百四十一 / 一百四十四 / 一百四十五轮连续指向同一件事：行内次要操作
（查找栏动作、终端工具条、弹窗次级按钮）触控尺寸不足（AI-1 内部编号 COMPAT-168，此前长期未登记进 BUG.md）。
第一百四十八 / 一百五十 / 一百五十一 / 一百五十三轮分别为无障碍 `contentDescription`、RTL、
`LaunchedEffect` key 审计，均无新发现。`docs/AI-2-BUG.md` 无新增（止于 §8）。台账唯一 OPEN 项仍为
BUG-PERF-030（A109 已裁定维持不修）。

落地 AI-1 的 COMPAT-168 时，发现「补尺寸」这个动作本身写错了顺序，且顺带暴露出编辑器顶栏一个缺陷：

- [x] **P3 · 修饰符顺序颠倒致命中区未扩（BUG-COMPAT-185）**：`Modifier.minimumInteractiveComponentSize().clickable{}`
  只把**自身报出的**尺寸抬到 48dp（语义尺寸与 `uiautomator` bounds 随之为 144px），内层 `clickable` 的
  pointer coordinator 仍按文字自身度量，真实命中区只有文字。改为 `clickable` 在外、
  `minimumInteractiveComponentSize` 在内，共 7 个文件：`TextEditorDialog.kt`（顶栏 ✕ / 查找栏四项 /
  设置面板 ✕ / 比对取消 / Diff 取消 / 加载更多）、`TerminalPage.kt`（9 处）、`SyntaxPackDialog.kt`（4 处）、
  `BookmarksDialog.kt`、`BuiltInFilePicker.kt`、`FileListViewSettings.kt#SortModeTextOption`、
  `FilePermissionDialog.kt#OwnerLabelRow`。查找栏四项补齐后总宽增大，间距 18dp → 8dp。
  裸文字不照搬 `heightIn(min = 48.dp)`（会让文字顶对齐在超高节点内，视觉上移）。
- [x] **P3 · 顶栏超长文件名挤压关闭入口（BUG-EDGE-186）**：`EditorTopBar` 标题 `Text(fileName)` 无
  `maxLines/overflow`、内层标题 `Row` 无 `weight`，而外层是 `SpaceBetween` → 数十字符的文件名撑满整行，
  「✕」被压成零宽，关闭入口不可见也不可点。标题行加 `Modifier.weight(1f)`，标题加 `maxLines = 1` +
  `TextOverflow.Ellipsis` 且 `weight(1f, fill = false)`，✕ 同步补齐 48dp 命中区。
- [x] 单测：`testDebugUnitTest` **613 tests / 0 failures / 1 skipped**（纯 UI 修饰符与布局约束，
  JVM 单测无法覆盖指针命中区，不新增用例）。`assembleRelease` + `verifyReleasePayload` 通过。
- [x] 真机（root，PACM00 / Android 10 / 1080×2280 / density 480）：
  修复前 `✕ 关闭` 语义 bounds 已 144px，但在框内偏离文字中心 45px 的 `(875,1237)` 点击**不关闭**，
  点文字中心 `(883,1282)` 才关闭 → 反证命中区未扩；修复后在 `(883,1236)`（偏离中心 45px）点击关闭成功，
  终端页「设置」`[894,120][1038,264]` 在 `(966,147)` 点击弹出「终端设置」。
  顶栏项：Download 下造 76 字符名 `.txt`，文件页 → 长按 → 编辑文本，顶栏以省略号截断，✕ 完整可见
  （bounds `[889,228][1033,372]` = 48dp×48dp），在 `(900,240)`（偏离中心 61px ≈ 20dp）点击关闭成功。
  验证后清理测试文件与设备/本地临时文件。
- [x] 构建与发布：APK 2909869 字节，sha256 `1244c6e4…`；tag `20261012` 重指至本次发版提交，
  双端 Release 资产替换并核验三方一致。语法包侧 `syntaxpacks-v2` 未变（APK 不内置语法）。

### A116. 语法包弹窗文案与触控复核（BUG-CONSIST-183 / BUG-COMPAT-184）（2026-10-12）

`docs/AI-1-BUG.md` 第一百三十七轮为非缺陷留档：AI-1 实测 Gitee raw 走 302 → `raw.giteeusercontent.com` → 200，
并核 `SyntaxPackStore.download()` 的 `instanceFollowRedirects = true` 能跟随重定向，A115 的 Gitee 首选源成立。
`docs/AI-2-BUG.md` 无新增（止于 §8）。台账唯一 OPEN 项仍为 BUG-PERF-030（A109 已裁定维持不修）。

顺着 A115 改过的那一屏再看一遍，发现两个此前没登记的问题，一并处理：

- [x] **P3 · 空态文案悬空（BUG-CONSIST-183）**：空态写「可从下方直链或本地 zip 导入」，而弹窗下方从来
  没有过直链。早先确有一组「仓库语法包（点击预填直链）」条目 + `SyntaxPackUrls.PRESETS`，在
  `9c21b99 调整语法包弹窗` 里整组删除、收成「从仓库下载」按钮预填，文案未同步。改为
  「可用下方按钮导入本地 zip 或直接从仓库下载。」。
- [x] **P3 · 备用源入口触控面积不足（BUG-COMPAT-184）**：备用源入口是裸 `Text` + `padding(top = 6.dp)`，
  `clickable` 挂在 `Text` 上，命中区仅文字本身（行高约 16dp），低于项目 48dp 的触控约定
  （范式见 `AuroraComponents.kt` 的 `fillMaxWidth().heightIn(min = 48.dp).clickable`）。该行在首选源
  不可达时是唯一换源出口，改 `Box(fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy))`
  整行可点，文案与行为不变。
- [x] 单测：`testDebugUnitTest` **613 tests / 0 failures / 1 skipped**（两条均为纯文案与修饰符改动，
  不新增用例；既有 `SyntaxPackSourceTest` 覆盖的 URL 常量与 `sourceLabel` 未变）。
- [x] 真机（root，PACM00 / Android 10）：空态确认新文案与三个入口；点备用源行右侧空白区（远离文字）
  成功拉起 URL 输入框且预填 `raw.githubusercontent.com`，证明整行命中；再导入 62 个包后确认弹窗
  （列表 200dp + 汇总 + 提示 + 按钮 + 备用源行）在 2280px 屏内不溢出，XML 文件已挂 `XML` 徽标。
  验证后清理设备侧 `files/syntax` 与 `/sdcard/Download` 一次性文件。
- [x] 构建与发布：`assembleRelease` + `verifyReleasePayload` 通过（无语法包、无数据表）；APK 2909869 字节，
  sha256 `b18b2991…`；tag `20261012` 重指至本次发版提交，双端 Release 资产替换并核验三方一致。
  语法包侧 `syntaxpacks-v2` 未变（APK 不内置语法，本次无语法包重发）。

### A115. 语法包下载源双源化（BUG-COMPAT-182）（2026-10-12）

`docs/AI-1-BUG.md` 第 135 / 136 轮均为非缺陷留档：135 轮核验 `syntax-packs.zip` 内 63 个 json 与仓库一致、
`git status` 里 52 个 `syntax-packs/*.json` 的 `M` 仅为行尾差异（`git diff --numstat` 为空）；136 轮核验
FilePage 单文件删除用页面级 scope、批量删除用进程级 scope + `NonCancellable` 的不对称判定合理。
`docs/AI-2-BUG.md` 无新增（止于 §8）。台账唯一 OPEN 项仍为 BUG-PERF-030（A109 已裁定维持）。

复核发现的缺陷不在上述两份记录里：语法包弹窗的「从仓库下载」只给 `raw.githubusercontent.com` 一条直链，
而本项目在「检查更新」处已按 Gitee 优先进处理同类问题 —— 国内网络访问 GitHub 常超时或被重置，该按钮
直接失败且没有替代入口；语法包又是取得语法着色的唯一途径（APK 不内置），等于该功能在目标网络环境下不可用。

- [x] **P2 · 下载源单一（BUG-COMPAT-182）**：`SyntaxPackDialog.kt` 的 `SyntaxPackUrls` 由单一直链改为
  `SOURCES` 列表（gitee 优先、github 备用，两源为同一 tag `syntaxpacks-v2` 下的同一份文件），`PACK_ZIP`
  取首选、`PACK_ZIP_ALT` 取备用，新增 `sourceLabel()` 按直链归一来源名（本地文件路径原样返回）；
  按钮下方补「Gitee 不可达？改用 GitHub 直链」入口，已导入条目的来源标签据此显示 `gitee` / `github`。
  不引入网络探测自动切换：一次探测的成本高于让用户点一下。
- [x] 单测：`testDebugUnitTest` **613 tests / 0 failures / 1 skipped**（新增 `SyntaxPackSourceTest` 3 条：
  首选源必须是 Gitee 且被预填；两条直链去参、去尾斜杠后须同以 `/syntax-packs.zip` 结尾且 tag 去重后只有 1 个；
  备用直链为 GitHub、`sourceLabel` 对两条直链分别归一为 `gitee` / `github` 且对本地路径原样返回）。
- [x] 真机（root）：装新 APK，`file://` 唤起 `.kt` → 设置 → 语法包 → 「从仓库下载」预填框显示 Gitee 直链 →
  导入成功「已导入 62 个 · 启用 62 个」，条目来源标签 `gitee`，设备侧 `files/syntax/index.tsv` 的 source 列为
  `https://gitee.com/yezijinn/shso/raw/syntaxpacks-v2/syntax-packs.zip`；点备用入口后预填框切为
  `raw.githubusercontent.com`。验证后清理设备侧语法目录与一次性测试文件。
- [x] 构建与发布：`assembleRelease` + `verifyReleasePayload` 通过（无语法包、无数据表）；APK 2909869 字节，
  sha256 `48e5fc51…`；tag `20261012` 重指至本次发版提交，双端 Release 资产替换并核验三方一致。
  语法包侧 `syntaxpacks-v2` 未变（APK 不内置语法，故本次无语法包重发）。

### A114. 语法包关键字正则边界（BUG-LOGIC-181 / 原 AI-1-BUG-LOGIC-167）（2026-10-12）

`docs/AI-1-BUG.md` 已收敛为终局（§141 第一百三十三轮，文件末 `TERMINAL` 标记）。其 §133.2 剩余项表里
`AI-1-BUG-LOGIC-167`（语法包生成器关键字未转义）此前从未落入本台账 —— 167 在本台账中是另一条已修复的
`BUG-EXC-167`，编号撞号把它盖住了；读码确认成立，登记为 BUG-LOGIC-181。其余三条维持原结论
（CONSIST-118 / COMPAT-093 / CONC-148），`docs/AI-2-BUG.md` 无新增轮次（止于 §8）。

全量扫描 62 个语法包，只有 4 个关键字带正则元字符或首尾为非词字符：Kotlin `as?`（`?` 被当量词，
退化成 `as` 或 `a`，于是 `val a = 1` 的 `a` 被染成关键字色）、Kotlin `!in` / `!is`（前置 `\b` 要求
前一字符是词字符，实际写法前是空格 → 从未高亮）、Ruby `defined?`（同 `as?`）。

- [x] **P3 · 关键字正则未转义与边界错误（BUG-LOGIC-181）**：`tools/gen_syntax_packs.py` 的 `words()`
  原先一律拼 `\b(kw1|kw2)\b`。现改为：全为纯词字符的列表仍走原路径（产物字节不变），含元字符或
  首尾非词字符的逐项 `re.escape`、边界 `\b` 只加在词字符一侧、长关键字优先（`as` 与 `as?` 并存时
  须先匹配 `as?`，否则 `?` 前也是边界，`as?` 只会被高亮到 `as`）。不采用 `(?<!\w)…(?!\w)`：
  Sora 的 RegexLib 可能是 re2j，re2j 无 lookaround。
- [x] 产物：62 个语法文件仅 `kotlin.json` / `ruby.json` 有内容差异（`git diff --numstat` 各 1 行），
  `syntax-packs.zip` 重发，`syntaxpacks-v2` 指向该提交；APK 不内置语法包（`verifyReleasePayload`
  拒收）且 `SyntaxPackUrls.TAG` 未改 → **无需重编 APK**。
- [x] 单测：`testDebugUnitTest` **610 tests / 0 failures / 1 skipped**（新增 `SyntaxPackGrammarContractTest`
  3 条，直读仓库语法产物断言边界：`val a = 1; a + a` 只命中 `val`；`as?` / `!in` / `!is` 整体命中而
  `asAny` / `inx` 不命中；`defined?` 整体命中而裸 `defined` 不算关键字）。
- [x] 真机（root）：把新语法包预置进 `filesDir/syntax/grammars`（`chown 10263:10263`、`chmod 644`、
  目录归应用 uid），`file://` 唤起 `.kt` / `.rb` 各截图核对 —— `val a = 1`、`b = a + a` 的 `a` 为正文色，
  `as?` / `!in` / `!is` / `defined?` 整体为关键字色；日志 `语法就绪: kotlin cost=18ms` 证明新语法在
  真实引擎中可编译。验证后已删除预置目录（原本不存在）与 `/sdcard/Download` 下的一次性测试文件。

### A113. 收件箱拒绝原因可辨识化（BUG-CONSIST-180）（2026-10-12）

`docs/AI-1-BUG.md` 第 134~136 轮无新增缺陷：第 126 轮提出的 BUG-COMPAT-166（浅色父主题与应用恒暗冲突）
已由其自行撤回（漏看 `values-v27` / `values-night` / `values-night-v27` 四套主题目录），第 128 轮对 res
全量核验亦未发现缺陷。余下 3 条均为 P3 且已有结论：CONSIST-118（收件箱去重 / TTL，BY_DESIGN）、
COMPAT-093（tif 预览，BY_DESIGN）、CONC-148（OBB 锁 quarantine，WONTFIX）。
`docs/AI-2-BUG.md` 无新增轮次（仍止于 §8，已由 A110/A111 处理）。

复核中另发现一处用户可见缺口：`copyToInbox` 的所有失败路径折叠成同一个 `File?` 空值，调用方只能给出
同一句「无法读取该文件（可能未授予读取权限或复制失败）」，而其中文件数达 256、总占用达 2GB、
同名探测耗尽三类都需要用户去清理 `/storage/emulated/0/Download/shso`。

- [x] **P3 · 收件箱拒绝原因不可辨识（BUG-CONSIST-180）**：判定提为顶层纯函数
  `inboxRejectReason(fileCount, existingBytes, declaredBytes)`，统一产出可执行原因
  （超单文件上限 / 文件数达 256 / 总容量达 2GB，后两者带目录路径）；`copyToInbox` 返回类型由
  `File?` 改为 `InboxCopy`（`Ok` / `Fail`），原因逐条透传至 Toast；`resolve` 的声明大小预筛
  复用同一函数，不再另写一份文案。
- 不实现收件箱 TTL 自动清理：该目录内容对用户可见，自动删除真实文件的风险高于收益，
  CONSIST-118 维持 BY_DESIGN。
- [x] 单测：`testDebugUnitTest` **607 tests / 0 failures / 1 skipped**（新增 1 条覆盖文件数 /
  总容量 / 声明大小 / 未知大小四类分支，并断言提示含目录路径）。
- [x] 真机（root）：预置 256 个占位文件后以不可解析的 `content://` 唤起，Toast 为「收件箱文件数
  已达上限（256 个），请清理 /storage/emulated/0/Download/shso 后重试」；清理后同一 URI 回落为
  「无法读取该文件（可能未授予读取权限，或存储空间不足）」，目录内无 0 字节残留。
- [x] 发版：APK 2909869 字节（sha256 `1dae2b07…`）、双端 `main` 与 tag `20261012` 推送
  （重指至 `05914d1`）、Release 目标提交与资产同步，下载 sha256 三方一致。

### A112. `docs/AI-1-BUG.md` 第 124~129 轮移交项（2026-10-12）

逐条读码核实四条发现（AI-1 编号 162~165，与既有条目撞号，重编为 176~179）：
两条确认（另存为残留、路径控制字符），一条一致性（进程组守卫与注释相悖），一条疑似项经复核不成立（setsid 前提）。

- [x] **P3 · 另存为临时文件残留无清扫通道（BUG-EDGE-176）**：临时文件必须建在**目标同目录**
  （跨文件系统 `mv` 非原子），而目录完全由用户指定；启动清扫只覆盖 filesDir / `/data/local/tmp` /
  cacheDir 三处固定位置，用户目录里的 `.shso_edit_*.tmp` / `.*.shso_*.tmp` 扫不到 ——
  写完临时文件与替换目标之间被强杀即永久残留隐藏文件。新增登记表 `filesDir/.shso_save_pending`：
  `writeTextFile` 落盘前 `recordSaveTemp`，`mv`/`rename` 成功或就地删除后 `clearSaveTemp`；
  启动清扫新增 `sweepRegisteredSaveTemps()`，按 `isSaveTempPath` 白名单删除，不依赖 mtime、
  不遍历用户目录，删不掉的行保留至下次启动（root 路径以 `rm` 退出码为准）。
- [x] **P3 · 另存为路径不拒换行/回车（BUG-LOGIC-177）**：`validateSaveAsPath` 拒 NUL、拒反斜杠，
  却不拒 `\n`/`\r`，而路径原样进入 `cat > <path>` / `readlink` / `stat` 的参数位，内嵌换行等价
  命令分隔；并入 NUL 的控制字符判定，与项目既有口径对齐。
- [x] **P3 · 进程组守卫与注释相悖（BUG-CONSIST-178）**：字段 KDoc 与三处注释称 `runPgid`
  「刻意保留，供回收被中断后逃逸到 init 下的子孙」，而守卫只认存活实体，该路径不可达；
  AI-1 建议放宽守卫以兑现注释，复核否决 —— 校验②（组内仍有存活成员）会被内核复用后的
  无关进程组满足，放宽即等于以 root 整组误杀。**保留守卫，订正 4 处注释**（含字段 KDoc）。
- [x] **P3 · setsid 不 fork 的前提（BUG-LOGIC-179，不成立）**：非 Root 分支无条件
  `runPgid = childPid` 被疑依赖 toybox `setsid` 不 fork。该依赖成立：子进程继承应用进程组，其 pid
  为本次新分配、必不等于该组 id → 子进程非组长 → `setsid(2)` 必成功不 fork；A111 真机
  `EXIT_RELAY=7` 已证退出码原样透传。订正注释表述（「子进程不是组长」）。
- [x] 单测：`testDebugUnitTest` **606 tests / 0 failures / 1 skipped**（新增 3 条护栏：
  换行/回车拒绝、另存为临时文件名识别白名单、登记与撤销通道）。
- [x] 真机（root）端到端：预置残留 `/sdcard/Download/.shso_edit_A112TEST.tmp` 与登记表
  （chown 应用 uid），`am force-stop` 后启动 MainActivity → 两者均被清除，logcat 无 FATAL。
- [x] 发版：APK 构建（2909869 字节，sha256 `972abfa7…`）、root 真机安装冒烟；双端 `main` 与
  tag `20261012` 推送、Release 目标提交改指 A112 代码提交并替换资产，下载 sha256 双端一致。

### A111. 非 ROOT 任务进程组隔离专项：`setsid` + 身份感知 kill 通道（BUG-LOGIC-173 / 原 AI2-LOGIC-023）（2026-10-12）

A110 暂缓项的专项落地。此前非 Root 任务沿用应用自身进程组，按进程组终止会连带杀掉本应用，
故 `runPgid` 恒 0、组杀整条跳过，中断/结束实际只杀了外层 `sh`，内层脚本真身以应用 uid
继续运行（不写 stdout 的 `sleep` 没有 SIGPIPE 自愈路径，永远留在后台），而界面显示已结束。
A110 用 A110 版 APK 复现：脚本内 `sleep 600` 与应用同 `PGRP=1167`，点「结束进程」后
被重挂到 PID 1 继续存活。属执行模型改造 + 真机端到端取证。

- [x] **P3 · 非 Root 任务经 `setsid` 自成会话/进程组（BUG-LOGIC-173）**：非 Root 分支改经
  `/system/bin/setsid sh -c <cmd>` 启动。toybox `setsid(2)` 只在调用者自身是进程组组长时
  才 fork，而 ProcessBuilder 起的子进程 `pid != pgid` 恒成立 → 必不 fork、退出码原样透传
  （uid 2000 侧实测 `EXIT_RELAY=7`）；`sh` 自任组长，`pgid == 组长 pid == 直接子进程 pid`，
  在 `withContext(Main)` 同步段随 `processPid` 一并回填，非 Root 侧不再需要探 `pgid`；
  `setsid` 不可执行时回退普通 `sh -c`（此时 `runPgid` 保持 0，组杀按原语义不触发）。
- [x] **P3 · kill 通道按执行身份分流（同条）**：新增 `execUsesRoot` 记录当前任务身份
  （`startExecution` 同步段、`runTerminalCommand`、`restartTerminal` 收尾均复位为 `true`）；
  `runCommandSync` 增 `asRoot` 形参，spawn 与超时分支同样按身份选择 `su -c` /
  `sh -c`；`killProcessGroup` 与新增的 `runKillSync` 按身份下发 kill。非 Root 任务本就
  允许同 uid 互发信号，走 `su` 只会平白弹授权框，且授权框无人回应时 kill 停在超时窗口里
  （表现为「点了结束进程但脚本还在跑」）。`runKillSync` 一律 `reclaimOnTimeout = false`，
  与 `forceKillProcessTree` 的互递归斩断保持一致。
- [x] 单测：`testDebugUnitTest` **603 tests / 0 failures / 1 skipped**（新增 4 条护栏：
  非 Root 必须经 setsid 自成进程组、非 Root 的进程组由其自身 pid 直接确定且 `awaitRunPgid`
  只在 Root 分支、非 Root 的 kill 不得经 `su`、终端命令必须复位执行身份）。
- [x] 真机（root）端到端：同一脚本（`echo marker; sleep 600`）置于应用可读的
  `/data/data/com.mixradio.droid/cache/`，应用内「执行 → 无 ROOT」两条路径对照 ——
  修复前三者 `PGRP=1167`（与应用同组），结束进程后 `sleep` 仍活（重挂 PID 1）；
  修复后三者 `PGRP=14379`（= 组长 pid），结束进程后 `/proc` 扫描零残留，全程无 `su` 弹窗，
  终端输出 `执行身份: 非 Root` + `A111 non-root marker` + `用户已手动结束进程`。
- [x] 发版：APK 构建（2893485 字节，sha256 `9e64e87f…`）、root 真机安装冒烟；双端 `main` 与
  tag `20261012` 推送、Release 目标提交改指 A111 代码提交并替换资产，下载 sha256 双端一致。

### A110. 执行链路与终端回收专项：`docs/AI-1-BUG.md` 第 111/113 轮 + `docs/AI-2-BUG.md` 第 8 节（2026-10-12）

逐条读码核实两份外部审查文档的发现项。AI-1 第 111/113 轮 2 条（死代码、过时注释）与
AI-2 §8 的 13 条均**复现成立**，且此前 BUG.md / TASKS.md 零登记。共 15 条：
A110 修 14 条，余 1 条（023）经 A111 专项落地。

- [x] **P2 · 非 ROOT 执行 `.so` 退化为 `sh <ELF>`（BUG-LOGIC-161）**：Root 侧早已按魔数
  `7f454c46` 预检 + 126/127 提示，非 Root 分支只拼 `export/cd` 后交给 `runShCmd`
  （`.so` → `Plan.DIRECT` → 恒为 `sh $escapedFile`）。mksh 对 `execve` 返回 ENOEXEC 的文件
  隐式回退为 shell 解释 → 满屏 U+FFFD、真实退出码被覆盖，且文档宣称的「给出明确提示」只对
  Root 成立（`更新日志.md` 失实已同步修正）。现非 Root 分支比照 Root：先 `head -c 4` 取魔数，
  非 ELF 直接 126 并提示，是 ELF 则 `chmod u+x` 后直接执行（仅应用可写文件可行，`/sdcard`
  等 noexec 挂载点必然失败，如实报 126/127，不再静默退化），并配套记录/还原执行位。
- [x] **P2 · 组长存活校验使「组长已死、成员仍活」的整组回收静默失效（BUG-LOGIC-164）**：
  `buildProcessGroupKillCommand` 的校验②要求 `/proc/<pgid>/stat` 存在且 pgrp==pgid，即
  **组长存活**；而组杀的用途正是回收「中断后逃逸的子孙」，SIGINT 恰先杀组长
  （`sleep 600 & exit 0` 即为组长立即退出的常态）→ 校验②恒假 → 整组回收短路成 no-op，
  root 的 `sleep` 继续跑、UI 报「已结束」。现校验②改为**组成员存活扫描**（遍历
  `/proc/<pid>/stat` 从最后一个 `)` 之后取 pgrp 比对，命中即置标记），fail-closed 语义与
  校验③（不误伤本应用所在组）保持不变。经真机实测两个坑：toybox 的 `ps -o PGID` **恒为 0**
  （不可作判据）、Android 无 `awk`，故采用纯 shell 内建 `/proc` 扫描。
- [x] **P2 · 终端保活前台服务延迟拉起被系统拒绝且被静默吞掉（BUG-EXC-165）**：
  `runTerminalCommand` 在 `delay(5_000)` 后才 `runCatching { ExecutionForegroundService.start() }`，
  而 `targetSdk=35` 下应用退到后台后 `startForegroundService` 抛
  `ForegroundServiceStartNotAllowedException`，被 `runCatching` 静默吞掉 → 无通知、无保活，
  进程随即被 LMK 杀（`su` 子进程收 SIGPIPE 暴亡）或被 freezer 冻结（输出协程停调度 →
  子进程写满 64KB 管道阻塞）。**该功能声称保护的场景恰是它失效的场景**（脚本路径无此问题：
  `startExecution` 在应用必前台时同步拉起）。现删除延迟常量，命令启动时立即拉起前台服务，
  通知显眼度后续再升级。
- [x] **P2 · `executeFile` finally 的权限还原在主线程同步跑 `su` 往返（BUG-EXC-167）**：
  `runCommandSync` 是非挂起同步函数（ProcessBuilder + waitFor），却写在
  `withContext(NonCancellable + Dispatchers.Main)` 里，每次 `.so` 收尾（含强杀路径）都阻塞
  主线程，`su` 停在授权弹窗时最长 10s（ANR 级），点「结束进程」后 UI 反而冻住。现该段改
  `Dispatchers.IO`（函数本就非挂起，换 dispatcher 零语义损失）。
- [x] **P3 · 强杀窗口的权限还原依赖 app 可读属性文件，`su` 掩码 077 时静默跳过（BUG-EDGE-171）**：
  属性文件由 root shell 重定向创建，`su`（Magisk/KernelSU）默认 `umask 077` → 600 root:root，
  app 侧 `readText()` 抛异常 → `mode=null` → chmod 跳过，被放宽的执行位永久保留。现
  `saveAttrCmd` 末尾追加 `chmod 644 <attrFile>`（app 私有目录无泄密面）。真机实测：
  属主可写掩码下写出 `-rw-------`，追加后 `-rw-r--r--`，应用侧可读（755）。
- [x] **P3 · `killCurrentProcess` 守卫缺存活实体项，stale pgid 可致 PID 复用误杀（BUG-CONC-163）**：
  守卫为 `!isTaskRunning && targetProcess==null && targetPgid<=1 && targetName==null`，
  不含 `targetPid`/存活实体项；而 finally 跑完后组长必已回收、组杀校验②恒失败，这条 stale
  组杀本就是 no-op —— 它唯一真正发出信号的情形恰是 pgid 被复用为无关组组长。现对齐
  `restartTerminal` 口径：`hasLiveEntity = isTaskRunning || targetProcess != null || targetPid > 0`，
  保留 `targetName` 通道。
- [x] **P3 · `startExecution` 作废旧实体漏清 `processWriter` 与 `executionJob`（BUG-CONC-168 / BUG-CONC-170）**：
  同步段作废清单只含 `runPgid/activeProcess/processPid`，漏 `processWriter` → 归一化窗口内
  终端输入被受理并写向已关闭的 writer（IOException →「发送失败: Stream closed」，输入被吞）；
  `executionJob` 的作废完全依赖时序 → 极端停顿下旧收尾按仍为真的归属判定把新任务的
  `isTaskRunning/currentTaskName` 清掉（脚本在跑、UI「待命中」、终止入口禁用）。现同步段补
  `processWriter = null` 与 `executionJob?.cancel(); executionJob = null`。
- [x] **P3 · `sendInput` 退出码行缺归属判定（BUG-CONSIST-166）**：`runTerminalCommand` 自身
  收尾与读线程投递都有代际/槽位守卫，唯独 `sendInput` 这条出口裸奔 —— 终端命令 A 被脚本 B
  覆盖启动（A 被杀，退出码 137）时，该行落入 B 刚开始的日志。现写入前加
  `terminalSlotOwner.get() == slotToken` 比对。
- [x] **P3 · `.nobom` 后缀错配致 finally 清理恒为空操作（BUG-CONSIST-162）**：创建的是
  `.sh_norm_*.nobom`，finally 删的是 `it.absolutePath + ".bom"`（从未被创建）。正常路径由
  prepare 子命令自清，泄漏窗口为「prepare 中途被强杀」；现改为删 `.nobom`。
- [x] **P3 · 读线程泄漏双现场（BUG-EXC-169）**：① `runTerminalCommand` 整个函数无 finally，
  正常结束路径（`finished=true`）对流零处理；② `runCommandSync` 的 `readerThread.join(5000)`
  仍存活时直接 return，不关流不销毁。两处每次触发泄漏一个常驻读线程 + 管道 FD + Process
  句柄。现两处均在 join 超时后 `forceCloseProcess`（关流迫使 `read` 返回 -1，线程自尽）。
- [x] **P3 · `runCommandSync` ↔ `forceKillProcessTree` 互递归无深度上限（BUG-EXC-172）**：
  超时分支调 `forceKillProcessTree`，后者又以 5s 超时调 `runCommandSync(killCmd)`，killCmd
  经**新的** `su -c` 执行，`su` 停在授权弹窗时再次超时 → 再入 → 每 5s 泄漏一个挂弹窗的 `su`，
  链长只由用户何时拒绝授权决定。现 `runCommandSync` 增加 `reclaimOnTimeout` 形参，超时分支
  在回收通道内只 `destroyForcibly`，斩断互递归。
- [x] **P3 · 解压冲突消解死函数与两条过时注释（BUG-CONSIST-174 / BUG-CONSIST-175）**：
  `ArchiveExtractor.resolveTargetPath` 全项目无调用者（职责已由原子预占的
  `reserveTargetDirectory`/`reserveTargetFile` 取代，且后者修掉了前者的 TOCTOU 窗口），
  连带 L407 注释仍指向该死函数；`AuroraComponents` 的 `AuroraWindowDialog` KDoc 写
  「20dp 圆角」而实现为 `RoundedCornerShape(0.dp)`，与零圆角形态铁律矛盾。现删除死函数、
  订正两处注释。
- [x] 单测：`testDebugUnitTest` **599 tests / 0 failures / 1 skipped**（新增/改写 8 条护栏：
  组杀命令精确等值 + 组成员存活扫描 + 不得依赖 `ps -o PGID`/`awk`、非 Root 段须含
  `7f454c46` 预检与直接执行、非 Root 必须复用归一化执行体、`reclaimOnTimeout` 互递归斩断、
  权限还原须离开 `Dispatchers.Main`）。
- [x] 真机（root）：013 组杀复现验证 —— 构造「组长（27543）已退、成员（27544）仍活」的孤儿组，
  新判据 `G=1` 命中并整组回收（`AFTER_REMAIN=0`）；021 属性文件权限实测
  `-rw-------` → `chmod 644` → `-rw-r--r--` 且应用侧可读；010 魔数预检表达式在 mksh 下实测
  ELF 得 `7f454c46`、文本得 `3132372e`。
- [x] **P3 · 非 ROOT 任务的中断/结束只达直接子进程（BUG-LOGIC-173，原 AI2-LOGIC-023）**：
  A110 当时判定为执行模型重构而暂缓，已由 A111 专项落地（`setsid` 自成进程组 +
  kill 通道按执行身份分流），详见 § A111。
- [x] 发版：APK 构建（2909869 字节，sha256 `ab1729c2…`）、root 真机冒烟（`pm install -r -t` 成功、
  进程存活、crash 缓冲无本包记录）、终端跑 `sleep 600` 后按 Home 转后台，`dumpsys activity services`
  显示 `ExecutionForegroundService isForeground=true` 且 `sleep` 仍在运行（保活生效），
  点「结束进程」后服务停止、`sleep` 无残留；双端 `main` 与 tag `20261012` 推送、双端 Release 目标
  提交改指 `89cde49` 并替换资产，下载 sha256 双端一致。见速览。

### A109. 编码判据阈值回归：短 GB18030 样本被误判 UTF-8（2026-10-12）

复核 A108 的判据实现时，用 JVM 对 GB2312 常用字穷举采样（3000 样本/档），发现阈值 1/2
对短样本过宽，是 A108 自身引入的回归。

- [x] **P2 · 两字 GB18030 误判率 48%（BUG-LOGIC-160 阈值追加）**：分母改非 ASCII 字节后
  长样本的稀释问题已解决，但两字样本只有 4 个非 ASCII 字节，替换符 ≤2 即判 UTF-8 ——
  实测 48.03% 被误判（A107 版判据在该形态判定正确）。修正是把
  `UTF8_REPLACEMENT_RATIO_LIMIT` 由 2 改为 4（阈值 1/2 → 1/4）：两字误判率降到 4.57%、
  三字 1.20%、八字 0.57%、二十字 0%；UTF-8 侧「单字 + 1 坏字节」的真实截断仍判 UTF-8，
  仅「单字 + 3 个杂散字节」的极端样本退化。单字 GB18030 的 18.30% 误判在所有阈值下不变
  （该批样本通过严格 UTF-8，属不可判定的固有歧义）。
- [x] 单测：`testDebugUnitTest` 596 tests / 0 failures / 1 skipped（新增 1 条短样本护栏
  `两字与三字 GB18030 短样本仍判 GB18030`）。
- [x] 发版：见速览。

### A108. 复核编码判据残留：ASCII 主导的 GB18030 被误判 UTF-8（2026-10-12）

复核 `docs/AI-1-BUG.md` 第 109 节（§117）对 158 残余边界的定级。原文判「P3、仅显示层、字节无损」，
建议只记作已知限制。核对保存链路后不采信。

- [x] **P2 · ASCII 主导的 GB18030 文件被误判 UTF-8，保存即损坏（BUG-LOGIC-160）**：158 的判据是
  「UTF-8 宽松解码的替换符 ÷ 已解码字符数 ≤ 0.20」。分母含 ASCII，而 ASCII 在 UTF-8 与
  GB18030 下同形、不产生替换符 —— ASCII 占比越高，比例被稀释得越低。一份 60 行 ASCII 加一行
  中文注释的 GB18030 文件密度仅 0.004，被判 UTF-8；编辑器保存按检测出的编码写回
  （`resolveCharset()` → `TextEncoder.encode`），误判后中文以 U+FFFD 呈现，保存即按 UTF-8
  全量重写，原 GB18030 字节被 `EF BF BD` 永久替换（临时文件 + 原子替换，无备份）——
  不可逆数据损坏，不是显示降级。该形态在修复前的旧判据下判 GB18030，属 158 引入的回归。
  - 修复：判据分母改**非 ASCII 字节数**（`utf8Replacements * 2 <= nonAsciiBytes` 即判 UTF-8），
    与 ASCII 体量无关；常量 `GB_FALLBACK_MAX_UTF8_REPL_DENSITY` 由
    `UTF8_REPLACEMENT_RATIO_LIMIT = 2` 取代；GB18030 候选分支（`gbDensity ≤ 0.01`）不变。
    顺带消掉「两汉字 + 1 坏字节」短样本降到 ISO-8859-1 的乱码边界。
- [x] 单测与契约测试：`testDebugUnitTest` 595 tests / 0 failures / 1 skipped（新增 2 条，
  均在修复前源码上实测失败，对照有效）。
- [x] 发版：APK 构建（2893485 字节，sha256 `412c7a39…`）、root 真机冒烟（安装成功、进程存活、
  crash 缓冲无本包记录）、双端 `main` 与 tag `20261012` 推送、双端 Release 目标提交改指该代码
  提交并替换资产。

### A107. 审查发现项：读取截断、套件配对、编码降级与可见性竞态（2026-10-11）

清理 `docs/AI-1-BUG.md` 第 105~108 节新增的 6 项。全部是 **6201599 / 1bff0cf 两笔改动自身
引入的回归或窄窗口** —— 「改完照常编译、单测也全绿」，只在真机时序或大输入下暴露。

- [x] **P1 · 终端输出末尾截断（BUG-EXC-154，6201599 回归）**：执行输出读取循环的判空问底层
  `stream.available()`、读取用带缓冲的 `InputStreamReader`。首次 read 即把约 8KB 预读进 reader
  自己的字符缓冲，底层 available() 立刻归零 → 循环误判「无数据」，进程退出后宽限计数到顶即
  break，缓冲里剩的尾部输出被整段丢弃（AI-1 实证：20000 字节实收 18432，真实子进程丢 5664）。
  - 修复：判空改 `if (!reader.ready())`（ready 同时反映字符缓冲与底层），补 `count == 0` 的
    挂起保护（解码器在等多字节后续字节时会 ready 为真却读不出），宽限复位移到成功读取之后。
- [x] **P3 · 提取去重后缀破坏分包套件配对（BUG-CONSIST-155，6201599 回归）**：后缀被
  `withSuffix` 加在扩展名前（文件名末尾），而套件 stem 由「去 `-split\d+$` 尾缀」导出 ——
  split 的 `-split1` 不再处于末尾，`isSplitName` 为假、两 stem 不等，命名聚合失效并退化为
  单文件安装（受保护目录下 manifest 也读不到 → MISSING_SPLIT）。
  - 修复：`plannedTargets` 增 `labelSuffix` 参数，后缀拼进**标签段**
    （`<label><suffix>-<version>[-splitN].APK`）；`extract()` 先用不含后缀的 base 名做归属判定，
    再把 suffix 交给 plannedTargets；删除 `withSuffix`。
- [x] **P3 · 无界路径列表撑爆 Bundle（BUG-EDGE-156）**：选中集合与图片列表都是随目录条目数
  无界增长的路径列表，整表进 `savedInstanceState`（约 1MB binder 上限）后旋转/分屏抛
  TransactionTooLargeException。
  - 修复：新增 `MAX_SAVED_LIST_ENTRIES = 400` 与 `savedPathList()`，超限**整表丢弃**
    （只存前 N 条会让用户误以为「选中了一部分」，比丢失选择更危险）；选中集合与图片列表
    （`savedPathListSaver`）都经该限量函数。
- [x] **P2 · GB18030 降级判据量纲错误（BUG-LOGIC-158）**：原判据 `replacements * 100 <= bytes.size`
  分子是替换符**字符数**、分母是**字节数**，文件越长阈值越松 → 长 UTF-8 文件混入个别坏字节
  即被判成 GB18030，中文全乱码，保存一次即按 GB18030 全量重写、原始字节不可逆损坏。
  - 修复：分母改已解码字符数（`utf8Text.length`），阈值定 0.20。实测替换符密度：纯 UTF-8
    中文混 1 坏字节 = 长 0.001 / 短 0.10；纯 GB18030 = 0.72、ASCII 与 GB18030 混排 = 0.26。
    0.20 落在两侧之间且留裕度，短样本不再需要「绝对数量」兜底（常量与文档一并删除）；
    GB18030 候选判据的分母同步改 `gbText.length`。
- [x] **P3 · 明暗条触摸映射未同步 thumb 扣除（BUG-LOGIC-159）**：色相条早已修「触摸映射与
  thumb 绘制同源」，明暗条漏改 —— 触摸用整宽、thumb 画在 `progress*(width-thumb)`，
  右侧点选偏移最多一个 thumb 直径（实测 x=300 偏差 28px）。
  - 修复：thumb 直径提到外层作用域共用，明暗条 tap 与 drag 两处触摸映射改为
    `usable = (maxWidthPx - thumbDiameterPx).coerceAtLeast(1f)`；删除条内重复声明。
- [x] **P3 · 切页重启解析的窄窗口双喂（BUG-CONC-157，1bff0cf 回归）**：把 `isVisible` 加进
  effect key 后，切页即取消并重启 effect；而 `withContext(Dispatchers.Default)` 里的解析块是
  纯 CPU、无挂起点、取消打不断 —— 旧块继续推进解析器，新 collect 读到的 prev 仍是旧值，
  同一段被重复喂入（重复行）。触发频率由「换色才偶发」扩大到「切页即可能」。
  - 修复：`isVisible` 移出 effect key，改 `rememberUpdatedState` 门控 +
    `snapshotFlow { RootService.outputLog to visibleState.value }`，collect 内 `if (!visible) return@collect`。
    离屏仍零解析，回到本页由可见性变化触发重新发射、增量追赶，结构上消除重启竞态。
- [x] 单测与契约测试：`testDebugUnitTest` 593 tests / 0 failures / 1 skipped。
- [x] 发版：APK 构建（2909869 字节，sha256 `54fc3b56…`）、root 真机冒烟（安装成功、进程存活、
  logcat 无 FATAL/ANR）、双端 `main` 与 tag `20261011` 推送、双端 Release 目标提交改指该代码
  提交并替换资产，双端下载哈希与本地一致。

### A106. 离屏终端页停解析（2026-10-11）

清理 AI-1 百轮报告 `final_state` 里唯一剩下的 `BUG-PERF-030`（P2）。该条两条子项：解析随页面
可见性启停、裁剪分支短路。本次做前者，后者改动面大仍待办。

- [x] **P2 · 终端页离屏仍在全额解析**：`MainActivity` 的 `HorizontalPager` 用
  `beyondViewportPageCount = 3` 常驻全部 4 页（否则切页会丢终端输入框、文件页多选等状态），
  终端页离屏时仍在组合树里；解析 effect 只以 `terminalDefaultColor` 为 key，与可见性无关。
  命令持续输出时，用户看不见的那一页会连同滑窗裁剪反复付全量重解析的代价（单次 10~25MB
  分配量级，见 A74）。A74 当时判为架构取舍未改。
  - 修复：`TerminalPage` 增加 `isVisible: Boolean = true`，解析 effect 的 key 改为
    `LaunchedEffect(terminalDefaultColor, isVisible)`，不可见时直接返回；`MainActivity`
    传入 `isVisible = pagerState.currentPage == 1`。离屏期间的输出合并为回到本页时的一次
    追赶解析（增量则只喂增量，窗口被裁过一次全量）。顺带订正 `TerminalPage` 中
    「HorizontalPager 离屏即销毁本页」的过时注释。
  - 真机实证：`id` 输出正常（uid=0(root)）；`ping -c 10 127.0.0.1` 运行期间切到文件页
    9 秒再切回，输出区完整显示 10 条回显与统计，无重复行、无丢行。
- [ ] **待办 · 裁剪后仍走全量重解析分支**（`BUG-PERF-030` 另一子项）：滑窗裁掉头部时
  `TerminalPage` 回落 `reset() + feed(整段)`，每 flush 一次全量。根治需给解析器加
  「静默快进」模式（只推进 SGR / 光标状态、不构造行对象），改动面较大。
- [x] 单测与契约测试：`TerminalGenerationGuardTest` 新增 2 条源码契约；`testDebugUnitTest`
  585 tests / 0 failures / 1 skipped。
- [x] 发版：APK 构建（2893485 字节，sha256 `c045f70e…`）、root 真机冒烟、双端 `main` 与
  tag `20261011` 推送、双端 Release 目标提交改指该代码提交并替换资产，双端下载哈希与本地一致。

### A105. 权限保存生命周期、字体虚假入口与死设置清理（2026-10-11）

- [x] `BUG-CONSIST-150`（P3，越权面）：`FilePermissionDialog` 的保存协程挂页面级
  `rememberCoroutineScope()`，弹窗开关与元数据为普通 `remember`。提交要连跑 chmod / chown / chgrp
  （含软链解析，Magisk 慢时数秒），旋转 / 分屏重建即取消，`catch (CancellationException) { throw e }`
  又不回滚 —— 取消点落在三步之间时文件停在「权限已改、属主未改」的半套且无任何提示。
  新增文件级 `permissionSubmitScope`（`SupervisorJob` + `Main.immediate`，与安装 / 批量同法），保存改走它。
- [x] `BUG-CONSIST-151`（P2）：字体预览弹窗「应用为软件字体」复制字体到 filesDir、写
  `appSettings.setCustomFont(...)` 并弹「已成功应用」，而渲染层从不读该设置
  （`AuroraFontFamily` 硬编码 `FontFamily.Monospace`，222 处 `AuroraTextStyles` 引用）。写入侧齐全、
  读取侧缺失，属虚假成功。动态化字体需大范围重构且与「APP 一律 Monospace」的硬约束冲突，
  故移除该入口、字体复制与成功 Toast，保留真实可用的预览本体。
- [x] `BUG-CONSIST-152`（P3）：`darkModeOption` / `setDarkMode` / `KEY_DARK_MODE` 无 UI 入口、
  无消费（主题固定暗色），随字体死设置一并删除（共 4 字段 / 5 方法 / 4 常量）。
- [x] `BUG-CONSIST-153`（P3）：`doSave` 的编码 / 行尾 / BOM 原在 `withContext(Dispatchers.IO)` 块内读，
  与同文件另两条保存路径口径不一；改为 `scope.launch` 前（主线程）取快照。
- [x] 单测与契约测试：新增 `ConsistencyCleanupRegressionTest` 4 条源码契约；
  `testDebugUnitTest` 583 tests / 0 failures / 1 skipped。
- [x] 文档：`BUG.md`（§1 索引、§3 A105、§2.6 排除项勘误）、`README.md`（字体预览措辞）、
  `更新日志.md`、`TASKS.md`。
- [x] 发版：APK 构建（2893485 字节，sha256 `5dc39735…`）、root 真机冒烟（`pm install -r -t`
  成功后进程存活、logcat 无 FATAL/ANR）、双端 `main` 与 tag `20261011` 推送、双端 Release
  目标提交改指该代码提交并替换资产，双端下载哈希与本地一致。

### A104. 分享多目标、文件页浏览态与输出读取取消（2026-10-11）

- [x] `BUG-LOGIC-012`（P2）：多文件分享只处理首项，其余静默丢弃。`ExternalRequestParser.build`
  改收 `uriStrings: List<String>`（空串过滤 + distinct 保序）；`streamExtraToUris` 覆盖
  Uri / CharSequence / List / Array 四种 `EXTRA_STREAM` 形态；`resolveIncomingUris` 归并
  data、`EXTRA_STREAM`、`clipData`，`getParcelableExtra` 与 `getParcelableArrayListExtra`
  各自 `runCatching`（避免互相 ClassCastException）；新增 `PendingExternalOpen.additionalCount`，
  UI 提示「本次分享共 N 个文件，仅打开第一个」。
- [x] `BUG-CONSIST-013`（P2）：文件页浏览态（当前目录 / 搜索词 / 多选集合 / 选中集 / 聚焦列）
  用 `remember`，旋转即丢。`FilePaneState` 增 `Saver`（listSaver），两列与 `focusedIndex` 改
  `rememberSaveable`；新增 `appliedDirRef` 区分「还原」与「真实切目录」，否则
  `LaunchedEffect(pane.currentDirectory)` 会在首帧把刚还原的状态抹掉。
- [x] `BUG-CONC-052`（P3，AI2-CONC-004 residual）：主执行输出读取用无条件阻塞读 —— 脚本把
  stdout 交给逃逸到 init 下的后代后 `read` 永不返回 EOF，旧协程 + IO 线程 + Process 句柄一并常驻，
  任务每被 kill / 覆盖启动一次泄漏一份。改为 `available()` 轮询 + `delay(OUTPUT_POLL_MS)` 的
  可取消挂起读；代际不符仍 `continue`（继续排空管道，**不提前 break**，否则后代写端填满会永久阻塞）；
  新增退出后 6 轮（约 300ms）宽限，覆盖 `cmd &` 后代稍后补输出。
- [x] `BUG-EDGE-097`（P3）：`openInBrowserOnly` 用 `!pkg.contains("github")` 子串启发式排除浏览器，
  误伤/漏判并存。改为精确包名集合 `NON_BROWSER_HANDLERS`。
- [x] `BUG-CONSIST-128`（P3，原 `BLOCKED_HUMAN` 解除）：提取产物名 `<label>-<versionCode>`，
  标签与版本码同时相同的分身/双开应用二次提取静默覆盖。采用「保留现状命名 + 跨应用同名时追加
  `-<packageName>`」：新增 `collisionSuffix`（目标存在且 `getPackageArchiveInfo` 确认属另一包才追加）
  与 `withSuffix`；整组 base + split 共用同一后缀，避免配对破坏被安装器判 `MISSING_SPLIT`。
- [x] `AI2-UI-002` / `BUG-CONSIST-115` 判 **WONTFIX**：正文驻留 Sora 原生控件、刻意不回折 Compose
  State；镜像进 Bundle 会重新引入逐键 O(n) 拷贝，大文本还会触发 `TransactionTooLargeException`；
  正确修复需磁盘草稿（属功能，且有未保存内容落盘的隐私影响）。缓解：`dirty` / `currentFilePath`
  跨重建保留（关闭仍提示未保存）、草稿快照功能可留存部分内容。
- [x] 单测与契约测试：`ConcurrencyAndSessionGuardTest` +5、`ApkExtractorTest` +1、
  `ExternalOpenTest` 3 例改造（List 形态 + 保序去重）；`testDebugUnitTest`
  579 tests / 0 failures / 1 skipped。
- [x] 文档：`BUG.md`（§1 索引、§2 明细、§3 A104、§4 WONTFIX、§5 矩阵）、`更新日志.md`、`TASKS.md`。
- [x] 发版：APK 构建（2909869 字节，sha256 `819fb081…`）、root 真机冒烟（`pm install -r -t`
  成功后进程存活、logcat 无 FATAL/ANR）、双端 `main` 与 tag `20261011` 推送、双端 Release
  目标提交改指 `6201599` 并替换资产，双端下载哈希与本地一致。

### A103. AI-1 移交项核实与分包安装结果丢弃（2026-10-11）

先按 `docs/AI-1-BUG.md` 第 87/88 轮的交叉核对清单逐条读码核实，再顺带扫编译器警告。

- [x] `BUG-LOGIC-150`（P1，编译警告发现）：`installApk` 分包分支尾部只写裸表达式 `outcome`
  而**没有 return**。Kotlin 里 `if` 作语句时末尾裸表达式的值被丢弃（编译器给
  `ApkInstaller.kt:164:13 Expression is unused`），块结束后函数继续执行下面的「单文件安装」——
  刚用 `installSplitApks` 装好的整套分片，被随后一条仅含 base 的 `pm install -r -d -t` 覆盖
  （分包应用即 `INSTALL_FAILED_MISSING_SPLIT`），返回值也从成功换成这条失败。改 `return@withContext outcome`。
- [x] `BUG-CONSIST-144`：两处落盘点无条件写 `rememberedDirectory`，右列每列举一个目录就把左列
  「上次浏览目录」改写为右列位置；`rememberedRightDirectory` 全库只有默认初值一个读点、无写点。
  新增 `RIGHT_PANE_INDEX = 1`，两处按 `paneIndexOf(pane)` 分流写对应字段。
- [x] `BUG-CONSIST-145`：`ensureShsoDir` 以 `if (shsoDirEnsured) return true` 永久短路，
  `/data/adb/shso` 被删/改名/移走后不再重建。新增 `invalidateShsoDirIfAffected(path)`，
  在 `delete`/`rename`/`moveFile` 的 `isUnsafePath` 守卫后各调用一次。
- [x] `BUG-EDGE-147`：`collectApkSet` 在按 stem 过滤前 `take(MAX_SIBLING_SCAN=200)`，
  `listFiles` 为文件系统序，同目录 >200 个 APK 时目标套件可能整组被截掉。删除该常量与截断，
  候选名全量传入 `nameBasedSet`（条目上限由下游 `take(MAX_SPLITS)` 兜住）。
- [x] `BUG-CONSIST-149`：提取 APK 用页面级 `rememberCoroutineScope()`、弹窗开关为普通 `remember`，
  旋转重建即取消提取，Download 里留下 base + 部分 split 的**半套**。新增文件级 `extractJobScope`
  与进程级 `extractingPkg`/`extractStatusMessage`，弹窗开关改 `rememberSaveable`。
  安装 / 批量 / 解压·拷贝·移动都已用文件级作用域防这一类取消，提取是唯一遗漏。
- [x] `BUG-CONC-148` 判 **WONTFIX**：被位移的持有者自身对 `$lock` 有前后两次 `lockOwned` 校验，
  锁名一旦被摘走即 fail-closed 中止；D 抢到空出的锁名后目标存在性预检以 ALREADY_EXISTS 退出。
  「mv 回退」在回退瞬间可能覆盖新持有者的锁，反而放大窗口。根治需 flock/write-lease，收益不抵风险。

验证：单测 573 项通过（1 skip），`ConcurrencyAndSessionGuardTest` 新增 5 条源码契约。
台账同步：`BUG.md` §1 索引、新增 §2.9 移交项块、§3 新增 A103 记录、§4 新增 148 裁决。

### A102. 独立复核轮（无代码改动）（2026-10-11）

不沿用既往结论，对「此前各轮未覆盖或仅粗看过」的区域重新逐行读码，逐条给可证伪的判断。
结论是无可证实且值得改动的缺陷 —— 不产生代码变更，故不重打 APK、不动 `20261011` 标签与
Release 资产（沿用 `df00088` 构建，APK sha256 `f8e34739…` 不变），只做台账裁决与文档更新。

复核范围与结果：

- [x] 外部入站（不可信输入）`ExternalOpen.kt`：净化、白名单、`createNewFile` 原子预占、
  边写边判容量、失败清理逐条核对；**无发现**，`BUG-CONSIST-014` 判 **DISPROVEN**
  （上限与去重均已实现，TTL/内容去重属产品特性，并入 `BUG-CONSIST-118`）。
- [x] 检查更新 `SettingsPage.kt:91-174`：双源回退、只认 6..8 位纯数字 tag、不剥 `v` 前缀、
  超时与 HTTP 状态分支穷举；**无发现**（「网络/更新 × EXC」盲区已覆盖）。
- [x] 语法包 `syntax/SyntaxPackStore.kt` 全文：单文件 512KB / 整包 2MB / 单条目 512KB /
  边读边限流 / 写入回滚 / TSV 转义 / 大小写无关 `substringBeforeLast`；**无发现**。
- [x] 设置持久化 `AppSettings.kt` 全文：读侧未钳制只在外改 prefs/降级安装场景可达（不可能场景，
  不加载荷）；`darkModeOption`/`setDarkMode` 无读取方（死设置，无行为影响，A105 已随字体死设置删除）；**均不作缺陷**。
- [x] 文本编辑器检索与只读态：`AI2-UI-004` 判 **BY_DESIGN**（缩短超时会把「较慢但有命中」
  误报为未找到，属正确性回退）、`AI2-UI-005` 判 **DISPROVEN**（`ensureActive()` 在循环体首部，
  取消后最多再读 1 块即抛；`getChunk` 为 `@Synchronized`，并发 load 串行化）。
- [x] 无障碍 `BUG-COMPAT-032` 判 **WONTFIX**：动作按钮以文字字符承载语义，全库仅 1 处
  `contentDescription = null` 且属装饰节点；整体无障碍改造为全局 UX 议题，非缺陷修复范围。
- [x] 归档解压 `ArchiveExtractor.safeDest`（canonical 二次校验）与 `ApkExtractor`：无新发现；
  唯一残余仍为待人工裁决的 `BUG-CONSIST-128`。

台账与覆盖矩阵同步：`BUG.md` §1 索引、§2.1 明细（014/032）、§2.6 表（AI2-UI-004/005）、
新增 §2.8 复核轮记录、§5 覆盖矩阵（文本编辑器 PERF、收件箱、无障碍三格归零）。

### A101. 时序竞态、执行入口口径与会话状态复核（2026-10-11）

对只读复核提出的 8 条逐条对照当前代码：全部成立并修复。

- [x] **文件页选中集裁剪的时序竞态**（`BUG-CONSIST-139`，P2）：裁剪挂在独立的 `LaunchedEffect(pane.nameQuery)`，
  而重算 effect 因搜索防抖会在 delay 处挂起让出，裁剪 effect 无延迟先跑、读到的是**过滤前**的列表，
  全部选中项都「可见」→ 裁剪直接返回；250ms 后重算才落盘，其 key 不再变化也不会重跑。结果是列表已显示
  「无匹配项」，长按批量删除的确认框仍报「选中的 N 个项目」，删的是屏幕上不存在的文件且不可撤销。
  现删除独立 effect，裁剪并入重算 effect 末尾、基于刚算出的 `computed` 求可见集，按列收敛并如实反馈被裁数量。
- [x] **发布循环代次非原子自增**（`BUG-CONC-140`，P3）：`++batchFlushGeneration` 是读-改-写，而调用点分处
  Main（`startExecution`）与 IO（`runTerminalCommand`）；并发启动时两条循环可能拿到同一代次，旧循环 finally
  的判据恒成立，把旧任务尾部输出灌进新会话。现改 `AtomicInteger` + `incrementAndGet()`，并把 cancel、
  代次自增与 Job 赋值收进同一 `synchronized(this)` 临界区。
- [x] **首页执行入口未剥 `.数字` 尾缀**（`BUG-CONSIST-141`，P3）：首页按字面后缀判定，而 `FileItem` 与
  `RootService.executeFile` 都先剥尾缀，同一路径出现「文件页可执行、首页报格式不支持」。现 `FileItem.kt`
  提供顶层 `realExtensionOf` / `stripNumericSuffix`，三处统一复用，删掉 RootService 私有的尾缀正则。
- [x] **祖先目录为符号链接时越界检查误拒**（`BUG-CONSIST-138`，P3）：`resolveWritableTarget` 只拿 `path`
  自身做前缀比对，祖先含链接时解析结果必然不等于 path，正常路径被拒绝。现一次 su 同时取「完整解析」与
  「父目录解析」，新增可单测的 `isResolvedInsideSelected`：放行「链接自身或子目录」与「祖先链接 +
  父目录解析后同名」；文件名恰好相同的越界链接（`/data/local/tmp/modules → /data/adb/modules`）仍拦下。
- [x] **终端命令输入与历史会进 savedInstanceState**（`BUG-SEC-142`，P3）：两项用 `rememberSaveable`，
  内容进入 savedInstanceState 并可能被系统落盘，而命令常含密码 / token，与「仅本次会话内存保留」矛盾。
  现改用文件级 `TerminalSession`（进程级 Compose State）：旋转仍保留，但不进 Bundle，进程回收即消失。
- [x] **首页未接线的执行确认槽位**（`BUG-CONSIST-143`，P3）：`pendingExecutePath` 声明后从未读写，注释却称
  「弹窗确认后再真正执行」。按钮文案是「立即执行」、用户手工输入路径即明确意图，身份选择在文件页的
  ExecuteConfirmDialog，故删除该死状态而不是补弹窗。
- [x] **残留违规 tag**（`BUG-COMPAT-136`，P3）：`v20260904` 非纯数字日期，违反统一更新检查约定；本地与双远端标签均已删除。
- [x] **chown/chmod 缺选项终止符**（`BUG-SEC-137`，P2）：与 A100 的 `BUG-SEC-135` 同源，两轮独立发现同一处，已修。
- [x] 单测：新增 `ConcurrencyAndSessionGuardTest` 5 项源码契约（代次原子性与同锁、裁剪不得独立挂 effect、
  终端页不得引用 saveable、三处入口共用 `realExtensionOf`、首页不得残留死状态），`FileItemTypeTest` 补尾缀口径
  1 项，`RootFileManagerEscapingTest` 补越界比对 2 项；全量 `testDebugUnitTest` → 568 tests / 0 failures / 1 skipped。

### A100. 编码统计、进程字段解析与安装锁复核（2026-10-11）

对近期只读复核提出的 8 条逐条对照当前代码：7 条成立并修复，1 条涉及既有命名约定，需人工确认后再改。

- [x] **文本统计「字节数」固定按 UTF-8 计**（`BUG-CONSIST-129`，P2）：编辑器支持 GBK / UTF-16 / ISO-8859-1 等编码保存，
  统计面板却一律按 UTF-8 折算，非 UTF-8 文件的显示值与保存后的真实大小对不上（GBK 偏大、UTF-16 偏小）。
  现 `TextStatistics.compute` 增加编码参数，统计面板与设置面板均传入当前写盘编码，切换编码即重算。
- [x] **进程组终止前的组长校验字段错位**（`BUG-LOGIC-131`，P2）：`/proc/<pid>/stat` 不能按空格取第 5 字段，
  `comm` 含空格或右括号（`prctl` 改名，如 `sh -c x`）时字段整体右移，把 `state` 当成 `pgrp`，组长校验与
  「非本应用所在组」校验双双失真。现先剥到**最后一个**右括号之后，再取 `state ppid pgrp` 的第 3 字段，
  与 Kotlin 侧 `substringAfterLast(')')` 同口径。
- [x] **OBB 安装锁的进程启动时间解析错位**（`BUG-LOGIC-132`，P2）：锁脚本用 `awk '{print $22}'` 取 starttime，
  同样受 `comm` 含空格影响而取错字段，与锁内记录的启动时间永不相等 → 存活锁被误判为陈旧并抢占，两个安装
  并发改写同一份 OBB。现改 `sed 's/.*) //' | cut -d' ' -f20`，与 Kotlin 侧 `processStartTicks` 对齐。
- [x] **安装确认弹窗的暂存副本缺启动清扫**（`BUG-CONSIST-130`，P3）：确认弹窗打开期间进程被强杀时清理代码不执行，
  残留只在缓存目录累积；现随启动清扫一次（仅清超过 1 小时未变动的），与安装临时包同口径。
- [x] **`chown` / `chgrp` 缺选项终止符**（`BUG-SEC-135`，P2）：属主 / 组名可含连字符，`escapeShellArg` 不为
  无特殊字符的参数加引号，`chown -R <path>` 会被当作递归选项而非所有者执行。现属主 / 组名首字符禁止连字符，
  并统一加 `--` 终止符；`chmod` 的 mode 已有 `[0-7]{3,4}` 白名单、路径为绝对路径且转义，无注入面，不动。
- [x] **Release 发布路径跳过单元测试**（`BUG-COMPAT-134`，P2）：两条发布工作流在编译前不跑单测，编译通过但破坏
  源码契约（权限矩阵、fail-closed 校验等）的改动会直接进 Release；现均在编译前执行 `testDebugUnitTest`，失败即中止。
- [x] **构建脚本残留未使用变量**（`BUG-COMPAT-133`，P3）：`gen_syntax_packs.py` 的 `app_assets` 已无任何引用，删除。
- [!] **提取 APK 命名未纳入包名**（`BUG-CONSIST-128`，P3）：标签与 `versionCode` 均相同的两个应用（应用分身、
  同名双开）会提取到同一文件名而静默覆盖。命名规则是既有约定，改动属用户可见契约，需确认后再改，暂挂（见 `BUG.md`）。
- [x] 单测：`TextStatisticsColumnsTest` 补编码口径断言，`ReleaseGateRegressionTest` 新增发布护栏 3 项；
  全量 `testDebugUnitTest` → 560 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：`adb install -r -g` 成功，`su -c id` 为 `uid=0`，进程存活，logcat 无 `FATAL` / `ANR`。

### A99. 添加文件嵌套、执行标志与主题背景复核（2026-10-11）

对只读审查近期提出的 14 条逐条对照当前代码复核：10 条成立并修复，2 条证据证伪，1 条归为设计取舍，1 条架构性挂起。

- [x] **「添加文件」产生 `X/X` 嵌套副本**（`BUG-LOGIC-116`，P2）：`addFileToShso` 用 `cp -r 源 目标` 落盘前未探测
  目标是否已存在，目标为**目录**时 cp 把源拷进其内部，等同于名二次添加生成嵌套副本，目标是同名文件时被静默覆盖；
  独立目录名取时间戳后 `% 100000` 只有 100 秒周期，同一分钟内多次添加即撞进同一目录。现先探测目标、命中即拒绝，
  独立目录名改完整 epoch 毫秒。
- [x] **独立目录名未过滤控制字符与路径分隔符**（`BUG-EDGE-117`，P3）：目录名直接取自源文件名，控制字符、`/`、`\`
  会改变 shell 命令语义或越目录，`..`、首尾点与空格与目录穿越混淆。现统一归一为 `_` 并 `trim('.', ' ')`，空值回落 `item`。
- [x] **解压/拷贝/移动的状态标志与作业挂在页面级**（`BUG-CONSIST-119`，P3）：旋转先取消页面级 scope，但
  `withContext(Dispatchers.IO)` 块体无挂起点会整段跑完（副作用已落地），收尾复位被取消吞掉、标志又被新页面
  重新初始化为 false → 可重入交叠。现改为文件级 `actionJobScope` + 文件级标志，并补幂等早退守卫。
- [x] **超大文本高亮判定不随内容长度重算**（`BUG-COMPAT-120`，P3）：阈值在组合期按 `controller.charCount()` 判定，
  控件未挂载时恒为 0；改用初值长度后必须把内容修订号纳入 `remember` 键，否则改写缩短到阈值内也不启用高亮。
- [x] **FileProvider 暴露面过宽**（`BUG-SEC-121`，P3）：`file_provider_paths.xml` 同时暴露 `files-path path="."`
  与整棵 `cache-path path="."`，而安装入口的唯一入参只是 `cache/install-confirm` 下的副本。现移除 files-path，
  cache 收窄为 `install-confirm/`。
- [x] **ROOT 探测超时后无界等待回收**（`BUG-EXC-122`，P3）：`destroyForcibly` 之后调无参 `process.waitFor()`，
  `su` 若停在不可中断状态会把 IO 线程永久占住。现改为有界回收。
- [x] **窗口背景缺失导致冷启动白闪**（`BUG-COMPAT-123`，P3）：四个主题变体均未设 `android:windowBackground`；
  且 `v27` 变体的 `Theme.shso` 未复列基础条目 —— style 资源按配置**整体择一**、不跨配置项合并，导致 `v27`
  设备上 `statusBarColor` 等一并丢失。现四变体统一补深色窗口背景，`v27` 变体复列全部条目（`aapt2 dump resources` 实证）。
- [x] **无冲突批量移动静默覆盖**（`BUG-CONSIST-125`，P3）：无冲突分支沿用默认 `OVERWRITE`，选中目录与执行之间存在
  TOCTOU。现与冲突弹窗一致显式传 `SKIP`。
- [x] **书签路径含换行污染列表**（`BUG-CONSIST-126`，P3）：书签以换行分隔的字符串持久化，路径含 `\n`/`\r`/NUL
  会让读回时 split 成多条。现与 `isUnsafePath` 同口径拒绝。
- [x] **构建告警全静默**（`BUG-COMPAT-127`，P3）：`org.gradle.warning.mode=none` 连新增的弃用告警一并静默；
  改 `summary` 保留计数与标题。
- [x] 判为不成立 2 条：`BUG-COMPAT-114`（`ServiceCompat.startForeground` 在 API<29 走两参重载，类型位被忽略，
  原判定正确）、`BUG-COMPAT-124`（`packaging.resources.excludes` 不作用于 assets，解包实证 `assets/sora-themes/shso-dark.json` 仍在）。
- [x] 设计取舍 1 条：`BUG-CONSIST-118`（收件箱文件数与容量上限已存在，去重与 TTL 属产品特性）。
- [x] 架构性挂起 1 条：`BUG-CONSIST-115`（编辑中旋转丢未保存内容，与 `AI2-UI-002` 同源，需内容持久化改造）。
- [x] 单测：新增 `PathAndActionHardeningTest` 11 项源码契约；全量 `testDebugUnitTest` → 555 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：`adb install -r -g` 成功，进程存活，logcat 无 `FATAL`/`ANR`；主页列出 `/data/adb/shso`，文件页列出 `/storage/emulated/0`。

### A98. 语法包上限与终端 SGR 复核（2026-10-11）

对早前几批遗留的 8 条逐条对照当前代码复核：4 条成立并修复，4 条证据载体消失或前提不成立。

- [x] **语法包 zip 单条目绕过体积上限**（`BUG-LOGIC-022`，P2）：压缩包分支逐条目读取只受整包上限
  `MAX_ZIP_BYTES`（2MB）约束，单文件上限 `MAX_BYTES`（512KB）未复核，同一个对象经 zip 导入可放宽 4 倍，
  等于用压缩包绕过单文件校验。现于逐条目读取后补 `require(data.size <= MAX_BYTES)`，与单文件导入同口径
  （仓库内官方包最大条目 8.8KB，不受影响）。
- [x] **冒号式 SGR 被当成重置**（`BUG-LOGIC-024`，P2）：`applySgr` 只按 `;` 切分，冒号式参数
  （`ESC[4:3` 波浪下划线、`ESC[38:2::R:G:B` 真彩色）整串 `toIntOrNull` 失败被滤空 → 走 reset 分支，
  该行其后文本连同已设前景色一并丢失。现入口把 `:` 归一化为 `;` 后再切分，未知码仍忽略（不再当 reset）。
- [x] **移动文件时目标存在性重复探测**（`BUG-LOGIC-103`，P3）：`moveFile` 在冲突分支对目标存在性探测两次，
  root 身份下每次探测要往返两次 `su`。现单次探测存 `destOccupied` 复用。
- [x] **安装超时残留的临时包无兜底清扫**（`BUG-EXC-084`，P3）：安装超时（`INSTALL_TIMEOUT_MS` = 5 分钟）
  刻意保留的临时 APK 此前没有任何清理入口。启动清扫扩展为同时清 `/data/local/tmp` 下的
  `_shso_install_*.apk` / `_shso_split_*.apk`，以 `-mmin +60` 兜底（大于安装超时），每进程一次。
- [x] 判为不成立 4 条：`BUG-LOGIC-020`（升级判定——全库已无「本地版本 ↔ 远端版本」比较代码）、
  `BUG-PERF-023`（渲染器已改增量解析 + 4000 行硬上限）、`BUG-EDGE-028`（权限编辑全文件无 `pointerInput`，
  主按钮触摸目标 `min 48dp`）、`BUG-LOGIC-029`（权限编辑无路径选择器，软链只按链接自身操作）。
- [x] 单测：`PerfAndNestingRegressionTest` 新增源码契约 2 项、`SyntaxPackTransactionTest` 新增 1 项、
  `AnsiParserControlSequenceTest` 新增 ANSI 行为 2 项。
- [x] 全量单测通过：`testDebugUnitTest` → 544 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：`adb install -r -g` 成功，进程存活，logcat 无 `FATAL`。

### A97. 更新检查状态与终端渲染取消复核（2026-10-11）

继续对同类条目做资产面复核：一条真缺陷、一条失效。

- [x] **「检查更新」进行态与结果不跨配置变更**（`BUG-CONSIST-096`，P3，CONFIRMED）：
  `updateState`/`updateChecking` 是页面级 `remember`、检查作业跑在 `rememberCoroutineScope` 上，
  旋转（或深色模式 / 字号变化）重建整个组合 → 作业被取消、状态归零，「检查中」熄灭、结果弹窗永不出现；
  且 `catch (Exception)` 把取消折成 `NetworkError`。状态与作业作用域提为文件级
  （`updateJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`），
  try/catch 首行补 `CancellationException` 重抛。
- [x] **终端渲染 `runCatching` 吞取消**（`BUG-EXC-025`，P2）判 `DISPROVEN`：
  唯一渲染器文件 `TerminalPage.kt` 全文件已无 `runCatching`，输出 collect 内 `withContext` 之后
  不再套宽 catch，取消经 `snapshotFlow` 自然传播（另有代次守卫）；原证据来自更早的渲染器实现。
- [x] 单测：新增 `SettingsUpdateStateGuardTest` 2 项源码契约。
- [x] 全量单测通过：`testDebugUnitTest` → 539 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：`adb install -r -g` 成功，进程存活，logcat 无 `FATAL`。

### A96. 协程取消传播与批量守卫作用域复核（2026-10-11）

对协程取消传播、批量操作状态、临时文件命名与只读态设置项的后续审查条目逐条对照代码复核：
8 条成立并修复，1 条维持 `OPEN`（架构性），其余经核实判为设计取舍或不可达状态。

- [x] **大文件稀疏行索引静默吞掉取消**（`BUG-EXC-109`，P2，CONFIRMED）：
  `SparseLineIndex.build` 用 `runCatching { … }.getOrNull()` 包住整个扫描体，
  取消时 `CancellationException` 被折成 `null`，调用方按「读取失败」处理 —— 取消语义丢失。
  改为 `try/catch`，首行 `catch (e: CancellationException) { throw e }` 原样重抛；
  块内 `withContext(Dispatchers.IO)` 扫描循环补 `currentCoroutineContext().ensureActive()`。
- [x] **编辑器加载/续读吞掉取消**（`BUG-EXC-106`，P2，CONFIRMED）：
  加载 `LaunchedEffect` 的 `catch (e: Exception)` 与 `loadNextChunk` 的
  `catch (_: Throwable)` 都会把取消当失败路径。各自补 `catch (e: CancellationException) { throw e }` 置前。
- [x] **批量任务守卫随旋转归零**（`BUG-CONSIST-107` / `BUG-CONSIST-112`，P2，CONFIRMED）：
  `isBatchRunning` 与 `batchDirGuard` 均为页面级 `remember`，旋转后归零，
  旧 Activity 的收尾会与新 Activity 的启动互踩；且 `batchDirGuard` 只写不读（死状态）。
  删死状态，守卫提为文件级 `private var batchInFlight by mutableStateOf(false)`（与 `installInFlight` 同模式）。
- [x] **非 Root 拷贝临时名可预测**（`BUG-SEC-108`，P2，CONFIRMED）：
  `TextCompare` 非 Root 分支临时名固定为 `.${target.name}.shso_tmp`，1777 目录可被预置同名软链，
  `cat >` 跟随软链写穿到目标外。改为 `.${name}.shso_<UUID>.tmp`，不可预测。
- [x] **只读浏览提示条口径不符**（`BUG-COMPAT-111`，P3，CONFIRMED）：
  该提示条只在只读浏览态渲染，却对「大文件但可编辑」分支保留「可编辑」措辞。
  统一为 `全文可浏览 · 只读预览`，HUGE 档附 `HUGE` 标识。
- [x] **只读浏览态可切行尾/BOM 却永不可存**（P3，CONFIRMED）：
  两项切换都会置 `dirty`，而只读态 `doSave` 被 `isLargeFile` 守卫拦死，用户被引进
  「改了必被拦、关必弹未保存、只能丢弃」的死路（且先切行尾会让编码切换也被 `dirty` 前置挡住）。
  只读态下两项按 `enabled = !chunkedBrowsing` 禁用。
- [x] **前台服务「结束进程」未回收服务自身**（`BUG-EXC-113`，P3，LIKELY）：
  `ACTION_KILL` 分支只 `killCurrentProcess()` 后 `return START_NOT_STICKY`，而该分支经
  `PendingIntent.getService` 拉起时会把服务带入 started 态，`START_NOT_STICKY` 不等于停服务 →
  留下一个非前台、无工作的空服务。补 `stopSelf()`。
- [x] **其余四条的处理**：`BUG-CONSIST-110`（删除确认显示层兜底）属防御性显示，
  非缺陷级，判 `BY_DESIGN`；`BUG-COMPAT-093`（tif/tiff 声明与解码能力不符）维持 `BY_DESIGN`
  （走 `BitmapFactory` 优雅失败，移出列表反而更差）；`BUG-EXC-033` / `BUG-EXC-054`
  触发前提为应用私有 `filesDir` 不可写（应用已不可用），不为不可达路径在热路径加告警管路，判 `BY_DESIGN`。
  旋转丢未保存编辑属需引入内容持久化的架构性改动，Bundle 直存大文本有 `TransactionTooLarge` 风险、
  进程级持有者又与「关闭/保存后清理」耦合，暂不动，维持 `OPEN` 并如实登记。
- [x] 单测：`SparseLineIndexTest` +1（扫描途中取消必须抛 `CancellationException`）；
  `TextEditorAndTerminalGuardTest` 新增 `CancellationAndScopeContractTest` 5 项源码契约，
  另加只读态设置项门控与前台服务收摊 2 项契约。
- [x] 全量单测通过：`testDebugUnitTest` → 537 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：`adb install -r -g` 成功，进程存活，logcat 无 `FATAL`，`su -c 'id'` 返回 `uid=0(magisk)`。

### A95. 拷贝权限与选择器末批复核（2026-10-11）

对文件管理写操作与内置选择器的后续审查条目逐条对照代码复核：3 条成立并修复，
1 条经核实不成立，3 条暂不修改。

- [x] **拷贝文件抹掉来源可执行位（台账与代码漂移）**（`BUG-LOGIC-102` / `BUG-CONSIST-105`，P2，CONFIRMED）：
  ROOT 路径先 `cp -p` 保留来源权限（如 755），紧接着 `chmod 644` 改成 `rw-r--r--`，
  连普通 0755 的执行位一并抹掉 —— 拷出的 `.sh` / `.so` 立刻失去执行权限；Java 回退路径
  用普通流拷贝后根本没设权限，与台账声称的「两条路径产物一致」不符。
  且本文件 A 段早已把该项记为 P1 并标 `[x]`，`go-w` 在 git 历史中零出现 —— 属「台账已修、
  代码未落地」的真实漂移。现两条路径统一「只减不增」：ROOT 侧 `chmod a-s,go-w`
  （`cp -p` 已保留权限，只清 setuid/setgid 与组/其他写位），Java 侧按来源 mode 掩 0755。
- [x] **`chmod u-s,g-s` 在设备上不生效**（实现期实测发现）：toybox 0.8.0 的符号模式解析
  忽略按类别给的 `-s`（`u-s` / `g-s` 均为空操作），只有 `a-s` 真正清除 setuid/setgid。
  真机逐项实测：`4777` 经 `u-s,g-s,go-w` 得 `4755`（setuid 残留，属越权面），
  改 `a-s,go-w` 得 `755`。据此定稿 `a-s,go-w`。
- [x] **内置选择器行内重复调用 fileFilter**（`BUG-PERF-100`，P3，CONFIRMED）：
  `displayFileList` 生成时已用 fileFilter 剔除不匹配项，行渲染又调一次判可选项；
  对比入口的过滤器含 `File.canonicalPath`，每个可见行每次重组都多跑一次 realpath。
  改为「未注入过滤器时才按扩展名判」。
- [x] **新建文件名重复追加扩展名**（`BUG-EDGE-101`，P3，LIKELY）：
  用户在「文件名」里已写扩展名且扩展名框非空时，无条件拼接得到 `note.txt.txt`。
  改为名字已以 `.<ext>` 结尾则不再追加。
- [x] **`entryCountFromTail` 死代码，不成立**（`BUG-COMPAT-104`，P3）：
  审查称「全项目无引用」，实际被 `InstallAndBudgetHardeningTest` 8 处调用（共 9 处引用）。
  与 A91 对 `BUG-COMPAT-066` 的裁决一致，判 DISPROVEN。
- [x] **暂不修改的三条**：设置页检查更新旋转丢结果需为 sealed class 状态自写 `Saver`；
  图片查看器声明支持 tif/tiff 与解码能力不符属设计取舍；编辑器检索 0 命中与检索中
  不可区分（每次未命中等满 3s 超时）属 Sora 检索无完成回调的取舍残留，
  降档超时会在慢检索下误报「未找到」，不动。`writeBytesAsRoot` 无界写阻塞维持 P3。
- [x] 单测：`RootFileManagerEscapingTest` +1（拷贝权限口径）、
  `PickerAndPermissionRegressionTest` +2（行内过滤、扩展名拼接）。
- [x] 全量单测通过：`testDebugUnitTest` → 529 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：`chmod a-s,go-w` 在 4777/6777/777/755/700/644/600 上的输出逐项核对，
  安装冒烟无崩溃。

### A94. 编辑器与文件列表末批复核（2026-10-11）

对编辑器与文件列表的近期审查条目逐条对照代码复核：7 条成立并修复，6 条判为设计取舍、不成立或待架构调整。

- [x] **另存为后旋转会把旧文件内容覆盖新文件**（P1，CONFIRMED）：
  加载源取构造参数 `initialFilePath`，而保存目标 `currentFilePath` 是 `rememberSaveable`
  且另存为后已切到新文件。旋转重建后 effect 按旧路径重载，把「另存为前的文件内容」读回
  编辑器，而保存仍写向 `currentFilePath` —— 用旧内容整体覆盖新文件，新文件原内容不可恢复。
  改为按 `currentFilePath` 加载（与保存目标同源）；effect key 不变。
- [x] **历史列表 LazyColumn 撞 key 崩溃**（`BUG-LOGIC-090`，P2，CONFIRMED）：
  key 取 `entry.timestamp`（`System.currentTimeMillis()` 毫秒），同一毫秒内的两条记录
  （连续保存、迁移的旧数据）会撞 key，LazyColumn 抛 `Key ... was already used` 直接崩。
  改 `itemsIndexed(history, key = { index, _ -> index })`，顺序稳定即下标稳定。
- [x] **图片查看器空列表组合期崩溃**（`BUG-EDGE-092`，P2，CONFIRMED）：
  `initialIndex.coerceIn(0, images.lastIndex)` 在空列表时 `coerceIn(0, -1)`，min > max 抛
  `IllegalArgumentException`，组合期即崩。下限兜底 `maxOf(0, images.lastIndex)`，空列表由后续
  `getOrNull` 兜。
- [x] **Sora 字号守卫混比 sp 与 px 致每键全量重排**（`BUG-LOGIC-098`，P2，CONFIRMED）：
  `ed.textSizePx`（px）与 `fontSize.value`（sp）直接判等，density≠1 时恒不相等，守卫失效 ——
  每次敲字都触发 `setTextSize` 内部无条件重建 Layout（不换行分支还分配 `float[65536]`）。
  改为与「上次下发的字号」`appliedTextSize` 判等，绕开单位换算，保持幂等。
- [x] **文件列表搜索/排序走系统区域**（`BUG-COMPAT-094`，P3，CONFIRMED）：
  `lowercase(Locale.getDefault())` 在土耳其语下把 `'I'` 折成 `'ı'`，搜索「i」命中不了
  `Ixxx` 文件、排序也错位。改 `Locale.ROOT`（与 `FileItem.extension`、`ArchiveExtractor` 同口径）。
- [x] **内置文件选择器 fileFilter 身份逐帧失效**（`BUG-PERF-095`，P3，CONFIRMED）：
  `BuiltInFilePicker` 把 `fileFilter` 当 `remember` 的 key，内联 lambda 每次重组都是新实例，
  缓存逐帧失效并重跑过滤 + 排序。提到顶层做常量 `SYNTAX_PACK_FILE_FILTER`，身份稳定。
- [x] **历史截断条目恢复会丢文件开头**（`BUG-CONSIST-091`，P3，CONFIRMED）：
  截断条目只存末尾 `MAX_CONTENT_CHARS` 字符，恢复即丢开头，界面无任何提示。
  列表项补一行「已截断：只保留末尾内容，恢复会丢失文件开头」。
- [x] **其余六条的处理**：`BUG-COMPAT-093`（tif/tiff 声明与解码能力不符）属设计取舍；
  `BUG-COMPAT-099`（CodeHighlighter 无参 `lowercase()` 即 ROOT）经核实不成立——无参折叠本就用区域无关规则；
  `BUG-CONSIST-096`（设置页检查更新旋转丢结果）因 `updateState` 是 sealed class，跨重建保留需自写
  `Saver`，改动面大且非正确性问题，暂不动；`BUG-EDGE-097`（浏览器包名启发式）无法可靠判定；
  编辑器内容不跨重建保留一项，大文本塞 Bundle 有 `TransactionTooLarge` 风险，需另设计草稿落盘；
  巨型只读模式的 BOM / 行尾可切换却不可保存，同样留待架构调整。
- [x] 单测：`RoundSixteenGuardTest` +4（历史 key、加载源、字号守卫、空列表索引）、
  `FileListViewSettingsTest` +1（tr-TR 区域下的搜索折叠）。
- [x] 全量单测通过：`testDebugUnitTest` → 526 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证。

### A93. 权限矩阵位序复核（2026-10-11）

补齐只读审查第九轮新增条目（17:24 追加，此前未入台账）。

- [x] **3×3 权限矩阵索引与 bits 布局互为转置**（`BUG-LOGIC-089`，P2，CONFIRMED）：
  `octalToPermissionBits` / `permissionBitsToOctal` 是**组优先**布局（bits[0..2]=用户、
  [3..5]=用户组、[6..8]=其他，index = group*3 + perm），而 `PermissionMatrix` 用
  `row*3 + column`（row=读/写/执行，column=用户/组/其他）—— 两者互为转置，
  且行注释误称「位序一致」。以 750 为例，「读·其他」会取到 bits[2]（用户执行位）显示为勾选，
  点任一开关写的是另一个组的位：界面看着对，实际设成另一组权限。
  755 这类各组合取值巧合相同，看不出异常；750/744/705 等非对称分布才暴露。
  改权限是唯一带越权后果的操作，故按 P2 处理。
- [x] **修复**：抽出纯函数 `permissionMatrixIndex(row, column) = column * 3 + row`，
  矩阵改用它；修正注释。不动两个转换函数（其 group-major 位序与文件系统一致，改动面更大）。
- [x] 单测：`FilePermissionDialogTest` +3（9 个下标逐点、750 逐格断言、点「写·用户组」得 770）。
- [x] 全量单测通过：`testDebugUnitTest` → 521 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证。

### A92. 终端解析代次与安装生命周期复核（2026-10-11）

补齐 A91 遗漏：只读审查末批新增的 4 条从未进入台账（`BUG.md` 全域零命中）。
逐条对照当前代码复核，3 条成立并修复，1 条判设计取舍。

- [x] **终端解析代次计数器跨线程不可见**（087，P2）：`parseGenRef` 是 `remember` 的
  `intArrayOf`，自增在主线程、读取在 `withContext(Dispatchers.Default)` 的解析块里。
  旋转屏幕重建 Activity 后新旧各持一份全新数组，`myGen` 都等于 1，`ref[0] == myGen`
  两侧同时成立 → 守卫形同虚设，旧 Activity 的 in-flight 解析块（纯 CPU、无挂起点、取消打不断）
  与新 Activity 首个 collect 串行进入 `synchronized(ansiParser)`，后进入者按旧 `prev`
  把同一段再喂一次 → 终端出现重复行。改为文件级 `internal object TerminalParseGeneration`
  持有 `AtomicInteger`，承担跨线程可见性；既有前置自增语义保留。
- [x] **安装确认弹窗暂存副本随旋转泄漏**（085，P3）：旋转让 `stagedPath` 这个普通 `remember`
  归零，旧副本路径丢失，`LaunchedEffect` 只在 show / 目标路径变化时清理 → 每旋转一次多一份
  孤儿副本（XAPK 整套分片可达数十 MB，落在 `cacheDir` 只能等系统回收）。
  新增 `DisposableEffect(fileItem?.path) { onDispose { if (!stagedConsumed) …delete() } }`
  清理未交出的副本；`stagedConsumed` 改 `rememberSaveable`，已交给 `startInstall` 的副本不受影响。
- [x] **安装中状态与执行作用域跨配置变更重置**（086，P3）：`isInstalling` 是页面级 `remember`，
  旋转后归零 → 顶栏安装入口重新可用，安装尚未结束时即可二次发起，两次 `pm install` 交叠。
  且 `startInstall` 的清理不在 `finally` 内（取消从 try 内重抛会跳过尾部），
  直接对 `isInstalling` 用 `rememberSaveable` 会造成「取消后入口永久禁用」。
  改为文件级进程状态 `installInFlight`（`mutableStateOf`）+ 文件级 `installJobScope`
  （`SupervisorJob() + Dispatchers.Main.immediate`），沿用既有 `batchJobScope` 模式：
  重建不重置、取消仍能收尾。
- [x] **判为设计取舍的一条**：`BUG-CONSIST-088`（首页以布尔 key 驱动重列举）——授权状态翻转
  由用户操作封顶，非自激；列举结果有 `shsoGenRef` 代次守卫，无非正确性问题；
  改「仅 false→true 边沿」会让用户主动刷新失效，加节流又致授权后列表延迟出现。判 BY_DESIGN。
- [x] 单测：改写 `TextEditorAndTerminalGuardTest` 的两条代次断言（原子化）、
  新增 `InstallLifecycleGuardTest` 2 例（暂存清理 + 安装状态跨配置变更）。
- [x] 全量单测通过：`testDebugUnitTest` → 518 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证。

### A91. 只读审查余下条目的代码级复核（2026-10-11）

此前只读审查留下、尚未裁决的一批条目逐条对照当前代码复核：12 条成立并已修复，
3 条判为设计取舍或不成立。

- [x] **归一化 prepare 的 TOCTOU**（055）：`prepare` 脚本先 `head -c 3 原文件` 探 BOM、
  再整读原文件去 CR，两次读的是两份可能不同的数据；od 读空时还把「探测失败」当成「无 BOM」。
  改为只读一次原文件、去 CR 写出副本，再在**副本**上探 BOM 并 `tail -c +4` 剥除。
- [x] **prefix 物化的无谓拷贝**（056）：`decideBytes` 对每个含 CR/BOM 的脚本都做
  `copyOfRange(0, 1MB).toString(ISO_8859_1)`。加窗口内 `$` 字节预筛，无 `$` 时自引用不可能，
  直接跳过物化（判定语义不变）。
- [x] **构建脚本桌面原生库检测的位置**（060）：检测嵌在「未识别前缀」的 `else` 分支里，
  落在 `lib/` 等已识别前缀下的同名平台目录被漏检，与注释声明的意图相反。移到 `if/elif` 链之前，
  对每个条目独立判定。
- [x] **zstd 缺失被误报为文件损坏**（061）：`openZstd` 在设备缺 native 时抛 `LinkageError`，
  被 `peekRoot` 的宽 catch 吞成 `parseFailed` → TAR+zstd 报「压缩包已损坏」，
  与 SINGLE 分支给出的「组件不可用」自相矛盾。新增 `ZstdUnavailableException` 并在 `peekRoot` 透传。
- [x] **SD 卡路径被白名单拒**（062）：`isExternalPathAllowed` 末尾无条件 `return false`，
  `/storage/<卷名>/…` 一律不接纳。新增 `isRemovableVolumePath` 放行合法可移除卷；
  排除 `emulated` / `self` 别名，保住「`/storage/emulated/01` 必须为 false」的既有断言。
- [x] **`.数字` 尾缀脚本判为不可执行**（063）：`isExecutableScript/Binary` 用 `extension`（不剥尾缀），
  而同类的 `isInstallable` / `isArchive` 用 `realExtension`。改用 `realExtension`；
  `RootService.executeFile` 的字面 `endsWith(".sh")` 同步改为剥尾缀后判定，否则会出现
  「文件页有执行入口、点击却报不支持格式」。
- [x] **大小格式化的 locale 口径**（064）：`formattedSize` 用 `Locale.getDefault()`，
  部分区域把小数点渲染成本地化数字形状，与 `formattedDate` 已在用的 `Locale.ROOT` 不一致。三处改 ROOT。
- [x] **CRLF 行尾 CR 混入行文本**（076）：`IndexedLineProvider.load` 只按 `'\n'` 切分，
  CRLF 的 CR 留在行文本里。加 `removeSuffix("\r")`。
- [x] **对比收尾的 `System.gc()`**（077）：全局 STW 提示，对比跑在 `Dispatchers.IO` 却停顿整个进程。删除。
- [x] **flush 循环替换时的残留**（078）：旧循环的 `finally` 只比 `epoch`，而 `epoch` 不随「循环替换」
  变化，旧循环局部 `pending` 的残留会被刷进新会话。新增 `@Volatile batchFlushGeneration` 代次令牌，
  `finally` 同时校验 `epoch` 与 `generation`。
- [x] **CRLF 文件最长行偏大 1**（080）：`columns` 按 `'\n'` 结算时把行尾 CR 计入长度。LF 处剔除紧邻 CR。
- [x] **`chars` 与四类之和不等**（081）：`chars` 取码元数，四类分类按码点累加，含 emoji /
  扩展 B 区汉字时「四类之和 ≠ 总字符数」。改 `codePointCount(0, n)`。
- [x] **判为取舍或不成立的三条**：`BUG-LOGIC-065`（1MB 探测窗口）——窗口未命中即保守 DIRECT
  会让所有 >1MB 普通脚本退回不归一化，等于撤销 A75 修复，保留窗口；
  `BUG-COMPAT-066`（`entryCountFromTail` 死代码）——实测被 `InstallAndBudgetHardeningTest`
  8 处引用，原判前提不成立；`BUG-LOGIC-075`（无 BOM UTF-16 嗅探）——去掉 NUL 信号会让
  纯 ASCII 的 UTF-8 倒向误判，两难，保留现状。
- [x] 单测：`TextStatisticsColumnsTest` +2（CRLF 行宽、emoji 码点）、`FileItemTypeTest` +1、
  `ExternalOpenTest` +1、`SparseLineIndexTest` +2。
- [x] 全量单测通过：`testDebugUnitTest` → 516 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证。

### A90. 归一化判定与文本对比行切分（2026-10-11）

本批四条取自此前只读审查留下、尚未复核的条目清单，逐条实测后确认为真。

- [x] **gzip 特征只写两字节**：`ShNormalization.magics` 里 gzip 只取 `1F 8B`，与紧邻注释
  「只收 >=4 字节特征」自相矛盾。两字节特征在二进制噪声里更易命中，一旦命中就把本应去 CR
  的 CRLF 脚本判为 DIRECT（跳过归一化）。改为 `1F 8B 08`（CM 位在 gzip 规范里恒为 deflate），
  并把注释改成准确表述：收录标准是「该特征在合法文本中不可能出现」，而非固定字节数。
- [x] **判定与读取之间的长度窗口**：`decide` 先按 `file.length()` 分配再 `readFully`，
  读取期间文件被追加时尾部载荷落在本次扫描之外，「未发现内嵌载荷」的结论不可信 → 去 CR 破坏载荷。
  root 侧 `decideAsRoot` 已有同类复查，app 侧缺对称保护。改为：长度以本次打开后的 `raf.length()`
  为准，读完再比对一次，不等即判 DIRECT「读取期间文件发生变化」。
- [x] **UTF-16 文本对比错位**：`TextCompare` 行扫描按单字节 `0x0A` 定位边界、只剔 1 字节 `\r`。
  UTF-16 的换行是 2 字节码元，从第二行起码元对齐丢失：行文本错位成乱码、行尾 `\r` 判错。
  `MappedFile.bomOffset` 也只跳 UTF-8 BOM，UTF-16 BOM 被当成数据。改为：`LineScanner` 按码元宽度
  推进并按 `0A 00`/`00 0A`、`0D 00`/`00 0D` 匹配；`countLines` 同步宽度感知；`bomOffset` 识别
  `FF FE`/`FE FF` 并跳过 2 字节；`detectedCharset` 探测改从文件头取样，避免 `dataStart` 漏掉
  UTF-16 BOM 这条最强判据。UTF-8 路径保持逐字节等价。
- [x] **无换行的单行文件被判 CRLF**：`LineEnding.detect` 在 lf/crlf/cr 三计数同为 0 时，
  `max == crlf` 成立 → 单行文件判成 CRLF，与空文本分支（LF）不一致，编辑出换行后会按 CRLF 落盘。
  改为 `max == 0` 直接返回 LF。
- [x] **同批复核**：`BUG-EXC-054` 与既有 `BUG-EXC-033` 同因（归一化失败静默回退），合并；
  `BUG-EXC-058` 证伪——`readFileHeadAsRoot` 超时分支已是 `destroyForcibly()` + `join(1000)`，
  无 su 子进程泄漏。
- [x] 单测与契约测试：`ShNormalizationTest` +3、`LineEndingTest` +1、新增
  `TextCompareUnicodeBoundaryTest` 4 例（UTF-16LE/BE 带 BOM、无 BOM 逐行命中；UTF-8 回归）。
- [x] 全量单测通过：`testDebugUnitTest` → 511 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：安装 release APK 后启动无崩溃，进程常驻、logcat 无 E 级异常。

### A89. OBB 锁元数据字段数与 TTL 判定（2026-10-11）

OBB 事务锁的判定链全在 shell 里跑，JVM 单测只能断言生成的脚本文本，
所以这两条只能靠真机（root，Android 10 / mksh）复现。

- [x] **锁元数据少写第四段，锁一旦残留就永久拒绝安装**：锁内容契约为
  `token|pid|startTicks|createdEpoch`，脚本按 `cut -d'|' -f4` 读创建时刻，
  而写入侧只写了三段。三段行取 `f4` 得空串，随即被脚本的数字校验判为「状态不明」，
  于是并发退避（期望 `17` BUSY）与陈旧锁 TTL 回收两条路径全部不可达：
  前者让后到的并发安装看到误导性的「锁文件可能已损坏」（`21` UNKNOWN，调用方只对 `17` 退避），
  后者让一次崩溃留下的锁把该游戏的 OBB 安装永久锁死且无自愈路径。
- [x] **抽出 `buildObbLockMeta` 写满四段**，并让调用点直接走该构造器；
  对历史残留的三段锁用锁文件 `mtime` 兜底（`stat -c %Y`）：锁只在创建时写一次，
  `mtime` 即创建时刻，语义等价。无分隔符的垃圾锁仍被 `oldPid` / `oldStart` 挡下，fail-closed 性质不变。
- [x] **TTL 判定写法非法致该保护恒定失效**：`[ "$now" - "$oldCreated" -lt 900 ]`
  不是 `test` 的合法形式（`test` 内建没有算术运算），mksh 报
  `[: -: unexpected operator/operand` 并以非零返回，于是「未过 TTL 也算忙」整条失效 ——
  只要持有者被判定为不存活就立刻回收，连刚写下 1 秒的锁也不例外。
  改为 `[ $((now - oldCreated)) -lt $OBB_LOCK_TTL_SECONDS ]`。
- [x] 单测与契约测试：`InstallAndBudgetHardeningTest` 增 4 例（四段元数据构造、
  取锁必须走构造器、三段锁 `mtime` 兜底位置先于数字校验、TTL 必须用 `$(( ))` 算术展开）。
- [x] 全量单测通过：`testDebugUnitTest` → 503 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：脚本语义 7/7 通过（空闲 / 活锁 / 新锁未过 TTL / 陈旧锁已过 TTL /
  三段旧锁新老 `mtime` 各一 / 无分隔符垃圾锁）；陈旧锁回收后锁已换新且无 quarantine 残留；
  8 进程并发恰好 1 成功、其余 7 个均为 `17`。设备端 `base.apk` 的 sha256 与本地一致（`635fc748…`）。
- [x] **不改项**：审查建议「`exit 21` 前删掉 quarantine」经复核确认有害——mismatch 分支里
  隔离出的可能是他方活锁的元数据（读与 `mv` 之间被抢锁），删掉会诱发双持锁；改判 BY_DESIGN。
- [x] **台账补录**：此前只读审查的 21 条建议完全未进台账（含一条 P1），已汇总清单登记，
  避免单一视角漏判。
- [x] **发版一致性订正**：Release 资产替换为新构建后，核对发现 tag `20261011` 仍停在 `85e320d`
  —— 早于 A87/A88/A89 三次修复，意味着按 tag 取源码构建出的包与已发布 APK 不是同一份代码。
  已把双端 tag 同步到当次发版代码，使 tag / 资产 / 源码三者一致。

### A88. 权限弹窗崩溃与保存死锁（2026-10-11）

台账里权限编辑区的两条 P2 同源，都在 `FilePermissionDialog` 的输入与提交链路上。

- [x] **非法四位串点开关即崩溃**：回写八进制串时把当前 `mode` 的首位当 setuid/setgid/sticky 前缀，
  判据只有 `length == 4 && isDigit`。输入框只挡非数字字符，`9999` / `1238` 这类四位串能停在框里，
  首位原样传给 `permissionBitsToOctal` 会命中它的 `require(specialMode[0] in '0'..'7')`，
  点任一权限开关就抛 `IllegalArgumentException`。八进制串与 owner / group 此前也没有任何即时校验。
- [x] **前缀提取收敛为 `specialModePrefix(mode)`**：仅当串本身是合法四位模式时才保留首位，
  否则返回空串。既挡住 `require` 崩溃，也避免从 `1238` 这类非法串里凭空带出一个用户没设过的特殊位。
  三个 `TextField` 补 `isError`（`!modeValid` / `!ownerValid` / `!groupValid`），
  保存分支复用同一组判定，不再各写一遍正则。
- [x] **保存协程无兜底致死锁**：`submitting = true` 之后没有任何 try/catch。
  `onSubmit` 要 fork 多次 su（含失败回滚路径），任一步抛异常协程直接结束，`submitting` 停在 true；
  而 `dismissOnBackPress` / `dismissOnClickOutside` 都被 `!submitting` 挡住 ——
  弹窗既不能保存也不能取消，只能杀进程。
- [x] **改为 try/catch/finally**：`catch CancellationException` 原样重抛（保持取消传播），
  其余 `Exception` 落到弹窗 message，`finally` 复位 `submitting`。
- [x] 单测与契约测试：`FilePermissionDialogTest` 增 2 例（前缀取值、非法四位串回写不抛），
  `PickerAndPermissionRegressionTest` 增 2 例（保存协程 try-finally-取消重抛、三输入框 `isError`）。
- [x] 全量单测通过：`testDebugUnitTest` → 499 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：release 包安装成功、冷启动无 `AndroidRuntime` 记录；
  设备端 `base.apk` 的 sha256 与本地构建一致（`e7a8f49b…`）。
  弹窗内的非法输入路径需要多级目录导航 + 长按动作菜单才能触达，改由纯函数单测覆盖。
- [x] **台账订正**：`BUG-LOGIC-017`、`BUG-CONC-018`、`BUG-SEC-015`、`BUG-SEC-034` 置 DISPROVEN
  —— 它们的取证对象（`PathClassifier` / `AuditVerdict` / `ScriptAuditor` / `SecurityAuditLog` /
  `GuardModuleInstaller`）已随 A67 的策略与审计子系统整体删除，`git grep` 全域零命中；
  `BUG-COMPAT-031` 置 BY_DESIGN —— 检查更新早已是「Gitee 优先、GitHub 备选」形态，
  原条目基于已删除的 `UpdateChecker` 类取证。
- [x] 发版：APK 2893369 字节，双端同步与 Release 资产更新。

### A87. 脚本执行环境补齐 TMPDIR（2026-10-11）

`/data/adb/shso/Keybox.sh` 在应用内执行失败：横幅为「执行身份: Root」，随后只有一行
`mktemp: Failed to create directory (null)//data/data/com.termux/files/usr/tmp/gztmpQrUgGA3tA: No such file or directory`，
退出码 127，用时 116 毫秒。

- [x] **定位为脚本头部的空值回退**：该文件是 makeself 风格自解压包（`skip=50` 头部 + gzip 载荷，
  载荷为 arm64 ELF）。头部把 `$TMPDIR` 归一化后，空值回退到硬编码的
  `/data/data/com.termux/files/usr/tmp/`，随后 `mktemp -d "${TMPDIR}gztmpXXXXXXXXX"` 失败并
  走 `(exit 127); exit 127`。报错里的 `(null)/` 是本机 toybox `mktemp` 失败时的固定前缀，
  不参与路径拼接，真实模板就是 Termux 那个不存在的目录。
- [x] **根因是执行环境没有 TMPDIR**：Android 应用进程的 environ 不含 `TMPDIR`（真机读
  `/proc/<pid>/environ` 确认），`su -c` 也不补；`adb shell` 自带 `TMPDIR=/data/local/tmp`，
  所以同一个脚本在 adb 下正常、在应用内必失败。设备未装 Termux，`/data/data/com.termux`
  不存在，回退目录无从创建。
- [x] **三条执行分支注入 TMPDIR**：root 的 `.sh` 分支、root 的 ELF 分支、非 Root 分支统一在
  `TERM` / `LANG` 之后补 `export TMPDIR=$tmpDir`。root 侧取 `ROOT_TMP_DIR=/data/local/tmp`
  （各机型恒在且 root 可写），非 Root 侧取应用私有 `cacheDir`（只有应用 uid 可写，与 root 侧互不影响）。
- [x] **终端命令一并注入**：`runTerminalCommand` 同样在 `su -c` 前补 `export TMPDIR`，
  否则在终端里直接敲脚本路径仍会落到同一条回退分支。
- [x] 契约测试同步：`SecondRoundSecurityAndResourceTest` 增 2 例
  （三条分支的注入计数与 root 侧常量、终端注入）。
- [x] 全量单测通过：`testDebugUnitTest` → 495 tests / 0 failures / 1 skipped。
- [x] 真机（root）复验：以脚本头部的 `TMPDIR` 归一化 + `mktemp` 逻辑做成探针，
  `su -c 'unset TMPDIR; sh 探针'` 原样复现该错误与退出码 127；
  加 `export TMPDIR=/data/local/tmp` 后 `MKTEMP_OK`、退出码 0。
  未整脚本执行——载荷会改写设备 keybox 状态。
- [x] 发版：APK 2893369 字节，双端同步与 Release 资产更新。

### A86. 长期挂起缺陷收敛：解压写盘 / 前台服务 / 文件名（2026-10-11）

长期挂起的 OPEN 缺陷里挑出 3 条 P1 与 1 条误杀类 P2 先修，其余留待各自专项。

- [x] **解压写盘不再跟随符号链接**：ZIP/TAR 与 7z 两条落盘路径原各自用 `FileOutputStream`。
  `safeDest` 的 `canonicalFile` 校验只解析到父目录，末段若本就是软链，检查与打开之间留有 TOCTOU 窗口，
  写入会落到链接目标上。统一改为 `Files.newOutputStream` + `LinkOption.NOFOLLOW_LINKS`
  （CREATE/WRITE/TRUNCATE_EXISTING）；末段为软链时内核实测抛 ELOOP，由既有异常分支转成解压失败。
- [x] **ZIP 预读的预算判定前置**：`peekRoot` 原先 `zip.fileHeaders` 全量构造再比条目数，
  「只靠条目数爆炸」的归档在判定之前就已经把对象分配完了。改为在 `fileHeaders` 之前调用条目数探针；
  该探针是零分配的结构遍历，ZIP64 哨兵、中央目录越界、体积超 32MB、结构损坏一律按超限拒绝。
  残余仅「探针返回 null」（非 ZIP／空／短于 22 字节）一条放行路径，此情形 zip4j 同样解不出条目。
- [x] **前台服务提升失败即退出**：`startAsForeground()` 改为返回布尔并捕获异常，
  失败时 `stopSelf()` 并返回 `START_NOT_STICKY`。只把异常吞掉不够——
  `startForegroundService()` 之后未在数秒内进入前台，系统会以 `RemoteServiceException` 崩掉整个进程，
  所以失败路径必须立即回收，且不启动保活轮询。
- [x] **文件名不再误杀内嵌两点**：`isUnsafeFileName` 由子串 `contains("..")` 收敛为整名等于 `..`。
  名字不是路径，`/` 与 `\` 已被拒，单个名字段拼不出 `..` 段；`a..b.txt`、`v1..2` 恢复合法。
- [x] **执行读取循环的残余**：代际自检只解决了日志污染，读取循环保留「继续 drain 到 EOF」
  的语义；写端永不关闭时旧协程无法退出，与 drain 目标互斥。此项尚未修复，方案需先确认真机出现频率。
- [x] 契约测试同步：`ArchiveExtractorZipSlipTest` 增 2 例（含真实软链用例）、
  `SecondRoundSecurityAndResourceTest` 增 3 例、`RootFileManagerEscapingTest` 把 `a..b` 一族移入合法名。
- [x] 全量单测通过：`testDebugUnitTest` → 493 tests / 0 failures / 1 skipped。
- [x] 真机（root）复验：`pm install -r -t` 装 release 包（versionCode 20261011 / versionName Jinn），
  Activity Resumed 且 logcat 无 FATAL；以 `app_process` 在 `/data` 上直跑
  `Files.newOutputStream(…, NOFOLLOW_LINKS)` 探针，末段软链返回
  `IOException: Too many symbolic links encountered (NOFOLLOW_LINKS specified)`，链接目标内容未被改写；
  R8 映射确认新方法已进发行包。
- [x] 发版：APK 2893369 字节，双端同步与 Release 资产更新。

### A85. 执行引擎回收与归一化对称性（2026-10-11）

对执行引擎（终端命令、脚本/二进制执行、root 文件写入）做静态审查，9 条发现全部确认并修复；
另有 3 条建议不予采纳——`hasLiveEntity` 加 `runPgid > 1`（陈旧 pid 会被内核复用，正是下述误杀路径）、
`appendWithSlidingWindow` 改环形缓冲（无实测卡顿，非缺陷）、终止路径补诊断输出（保持执行输出干净）。
改动全部落在 `RootService.kt`。

- [x] **终端命令超时不再留 ROOT 孤儿**：30 分钟超时分支原只 `destroyForcibly()` 杀 `su`，
  `sh -c …` 的后代被重挂到 init 名下继续以 ROOT 运行。改为起进程时记下 `suPid`，
  超时从 `/proc/<suPid>/stat` 反查实时 pgrp 整组回收（复用 `forceKillProcessTree`，
  不依赖可能被 pid 复用污染的 `runPgid`）。
- [x] **「结束进程」在进程组未回填时不再只杀 su**：终端命令的 `currentTaskPath` 恒为 null，
  `pkill` 兜底整段跳过；命令刚发出（回填窗口最长 1.5s）时 pgid 仍为 0，唯一手段只剩杀 `su`。
  改为按 `su` 的 pid 反查进程组整组回收，三重校验 fail-closed。
- [x] **非 ROOT 身份同样走归一化**：非 Root 分支原硬编码 `sh $escapedFile`，
  绕过 Root 分支才有的归一化链路 —— 同一 CRLF/BOM 脚本换身份即复现 A75。改用 `$runShCmd`。
- [x] **启动同步段复位执行实体**：`startExecution` 同步段补 `runPgid=0` / `activeProcess=null` /
  `processPid=0`。归一化判定在协程体内跑，跨两次 su 往返（最长 120s + 5s），
  窗口内这三个字段仍是上一轮的旧值，点结束会拿旧 pgid 组杀。
- [x] **心跳与迟到输出按代际自检**：旧任务因子孙进程持有管道而读不到 EOF 时，协程与 finally 都不执行，
  心跳就挂在下方的全局 `isTaskRunning` 上继续给新任务追加计时行。心跳补代际比对、
  输出投递补 `executionJob !== myJob` 守卫（必须 `continue` 而非 `break`，否则管道写满会阻塞子孙）。
- [x] **写 root 文件不再无界阻塞**：`writeBytesAsRoot` 原在调用线程直写管道，
  超过管道缓冲（约 64KB）即卡死，`waitFor` 超时形同虚设；且错误流并入无人读的 stdout。
  改为独立写线程 + 有界等待 + 单独 drain 错误通道。
- [x] **非 ELF 文件不再被当脚本解释**：mksh 对 `execve` 返回 ENOEXEC 的文件会隐式回退为 shell 解释，
  伪 `.so` 满屏乱码且 126/127 分支不可达。改为执行前按魔数 `7f454c46` 预检，非 ELF 直接提示并退出 126。
- [x] **执行期临时文件启动即清**：`.exec_attr_*` / `.sh_norm_*` 只在 finally 删除，
  强杀/OOM/协程卡在 native 读上时不执行。新增本进程首次初始化时的清扫（`AtomicBoolean` 保证只扫一次）。
- [x] **删除死参数**：`executeFile` 入口签名上悬挂从未被读取的放行参数，形成虚假契约，已删除。
- [x] 契约测试同步：`SecondRoundSecurityAndResourceTest` 增 4 例、`SyncCommandReclaimTest` 增 4 例。
- [x] 全量单测通过：`testDebugUnitTest` → 488 tests / 0 failures / 1 skipped。
- [x] 真机（root）复验：文本伪 `.so` → 明确提示、退出码 126、无乱码；toybox 真 ELF 副本 → 正常执行；
  非 ROOT 身份 CRLF/BOM 脚本输出长度与 ROOT 身份逐一一致；预置临时文件后冷启 App 复查已清空。
- [x] 发版：APK 2.76MB，双端同步与 Release 资产更新。

### A84. 变更复核与边界收敛（2026-10-11）

对 A83 引入的字节级判定与根侧兜底做独立复核，逐条裁决后实施下列修正。

- [x] **su 无响应不再永久阻塞**：`readFileHeadAsRoot` 原为裸 `waitFor()` + 阻塞 `readBytes()`，
  su 停在授权弹窗即永久挂起（UI 停在「运行中」）。改为独立线程读流 + 5s 等待上界，
  超时 `destroyForcibly()` 并放弃本次 root 判定，转交保守分支。
- [x] **读取上界与并发变更校验**：`decideAsRoot` 改读 `size + 1` 并要求读取长度严格等于
  `stat -L -c %s` 的大小；文件在 stat 与读取之间被改写时不再按过期头部判定。
- [x] **zstd 组件缺失不再闪退**：`openZstd` 捕获 `LinkageError` 并归一为 `IllegalStateException`，
  原生库在个别 ABI 缺失时由解压失败提示承担，不再整个进程崩溃。
- [x] **无花括号 `$BASH_SOURCE` 纳入自引用识别**：原只认 `${BASH_SOURCE`，漏掉 `$BASH_SOURCE`，
  该写法的自解压脚本会被误判为普通文本脚本去 CR。
- [x] **bzip2 块头按「魔数 + 级别」判定**：`BZh` 仅 3 字节，单列会与普通文本假命中
  （实测文字里出现 `BZh` 即触发直跑）；改为要求紧随级别数字 `1`~`9`，与其余 ≥4 字节特征一致。
  移除多余的三字节 bzip2 魔数项。
- [x] **列举重试判据改为名称集合**：原按 `metas.size != names.size` 判「两趟不一致」，
  断链符号链接 stat 失败会缺元数据但名称仍在，被误判为不一致而反复重试；
  改为比较名称集合 `parsed.names != names.toSet()`。
- [x] **`isNulDelimited` 收紧**：标记位于输出首尾时原用哨兵补边，改为 `markerIndex <= 0`
  直接判否，避免把「标记恰在起点」误判为合法分隔。
- [x] 契约测试同步：`DualPaneStateRegressionTest` 重试判据断言、`DirectoryListingTest`
  固定窗口改按函数边界截取、`ShNormalizationTest` 增 3 例（共 14）。
- [x] 全量单测通过：`testDebugUnitTest` → 480 tests / 0 failures / 1 skipped。
- [x] 真机（root）复验：CRLF 脚本经 root 判定执行 `len=3`、退出码 0；`.zst` / `.tar.zst`
  解压内容逐字节正确；含断链符号链接的目录完整列举且大小/类型无串位。
- [x] 发版：tag `20261011` 推双远端；双端同名 Release 上传 `app-release.apk`（2893369 字节），
  双端下载 sha256 与本地一致；旧版 APK 已从双端 `20261004` 移除，Release 与 tag 保留。

### A83. 恢复 zstd + 脚本归一化保守化（2026-10-11）

- [x] **恢复 zstd 解压**：`ArchiveExtractor` 接入 `ZstdCompressorInputStream`（`.zst` 单文件 /
  `.tar.zst` / `.tzst`），`TAR_EXTENSIONS` 增补 `.tzst`；依赖 `zstd-jni 1.5.7-16` 已在
  `libs.versions.toml` 与 `app/build.gradle.kts` 就位，abiFilters 裁到 arm64-v8a。
  - [x] 构建脚本与文档同步：`build_apk.py` 移除 zstd 残留检查与返回字段；`README.md`
    格式数 12→14；`docs/PROJECT.md` 目录树补 `ShNormalization.kt` 并改述为「使用 zstd」。
  - [x] **原生库变体纠正**：`com.github.luben:zstd-jni` 同 GAV 下有 jar 与 aar 两份制品，
    jar 内是 `win/ darwin/ linux/ freebsd/ aix/` 桌面原生库，打进 APK 后设备侧没有可加载的
    `.so`（运行期 `UnsatisfiedLinkError`），还白占约 1.5 MB。改用 aar 变体
    （`implementation(libs.zstd.jni) { artifact { type = "aar" } }`），APK 由 3.88 MB 回落 2.76 MB，
    并引入 `lib/arm64-v8a/libzstd-jni-1.5.7-16.so`（475 KB）。
- [x] **修复内置压缩自解压脚本退出码 127**
  - [x] 根因：执行前无条件 `tr -d '\r'` 重写整个文件，把自解压脚本尾部内嵌载荷整体移位，
    压缩流损坏 → `gzip: gzread: invalid distance too far back`、退出码 127。
  - [x] 新增 `ShNormalization`（`internal object`）：按**字节**判定 `DIRECT` / `NORMALIZE`。
    只要出现字节偏移自引用（`$0` / `${0}` / `${BASH_SOURCE`，以及 `tail…+N` / `head…-c` /
    `dd…skip=` / `sed -n 'N,'`）或内嵌压缩魔数（gzip/bzip2/xz/zstd/lz4/zip），一律直跑原字节；
    仅确认是普通文本脚本才去 BOM 与 CR。扫描上限 16 MiB，超限保守直跑。
  - [x] 移除 3 字节 LZMA 弱特征 `5d 00 00`（独立 lzma 流本就不支持，且极易与普通文本假命中，
    会把该归一化的 CRLF 脚本误判成载荷）。
  - [x] 偏移正则放宽到变量形式：真机样本 clear.sh 写作 `tail $tail_n +$skip <"$0"`（skip=50），
    原 `\+\s*\d+` 不匹配 `+$skip` → 漏判；改为 `\+\s*[0-9$]`，并补单测。
- [x] **app 侧不可读时的 root 兜底**：`Decision.appReadable=false` 时改判 root 侧结果；
  `/data/adb/shso` 这类 0700 父目录下的脚本不再因 `File.isFile` 恒 false 而误走归一化。
  - [x] 兜底入口 `decideAsRoot`：先 `stat -L -c %s` 取实际大小，超 16 MiB 上限直接判 DIRECT 且**不读文件**
    （clear.sh 21 MB，原先无条件读 16 MiB 纯属浪费），否则按实际大小读头部。
- [x] **目录元数据按名称配对**：元数据通道内自带名称（`printf "%s\0" "$f"` + `stat`），
  `parseStatMeta` 返回名称集合与元数据映射；列举期间增删或断链不再让后续条目串位。
  `META_SEP` 判定要求两侧均为 NUL（`isNulDelimited`），名字里含该串的文件不再被切开。
- [x] **外部分享 MIME 补齐 zstd**：两个 alias 的 intent-filter 增 `application/zstd` / `application/x-zstd`，
  声明官方 zstd 类型的来源不再找不到 shso。
- [x] 守护单测：`ShNormalizationTest` 14 项（新增自解压 `tail -n`/`head -c`/变量偏移、lzma 假特征、
  app 不可读标志、无花括号 `$BASH_SOURCE`、`BZh` 字面量、内嵌 bzip2 载荷）；
  `DirectoryListingTest` 断言改为 `metas[name]`。
- [x] 全量单测通过：`testDebugUnitTest` → 480 tests / 0 failures / 1 skipped。
- [x] 真机（root）验证：
  - [x] CRLF 脚本归一化：`/data/adb/shso` 下探针脚本输出 `shso-crlf ok len=3`、退出码 0（root 侧兜底判定生效）。
  - [x] 自解压脚本字节偏移：含 0x0D 压缩流的 `tail -n +$skip "$0" | gzip -cd` 夹具经应用执行输出
    `payload-content-12345`、退出码 0；同文件去 CR 后直接执行报 `gzip: gzread: <fd:0>: incorrect data check`（反证）。
  - [x] 断链符号链接元数据：`dangling_link` 与撞名 `__SHSO_META_5A1C__fake` 均按自身名称配对到正确大小。
  - [x] `.zst` / `.tar.zst` / `.tzst` 解压：设备无 zstd 命令，经 UI 解压全部成功、内容逐字节正确
    （证明 `lib/arm64-v8a/libzstd-jni-1.5.7-16.so` 在设备侧可加载）。

### A82. 执行入口改为菜单 + 身份选择（2026-10-04）

- [x] **移除列表行内「执行」按钮**
  - [x] 该按钮 gated 在 `!compact && isExecutable`，而双列布局下每列约 205dp，
    恒小于 220dp 的紧凑阈值 → 手机上永远不渲染。占着行内空间却点不到。
  - [x] 同处一个 `if/else if` 链的「预览」保留（改结构时勿连带删掉）。
- [x] **`pendingExecuteItem` 是声明后从未使用的死状态**
  - [x] 槽位与注释（「点击「执行」先暂存待执行文件，弹窗确认后再真正执行」）都在，
    但没有任何代码写入或消费它。编译与测试都通过，用户点了没有任何反应。
  - [x] 动作菜单新增 `执行` 项（限 `isExecutableScript || isExecutableBinary`）
    写入该槽位；新增 `ExecuteConfirmDialog` 消费它。
- [x] **执行身份三选：取消 / 无ROOT / 有ROOT**
  - [x] 此前只有 `executeFile(path, runAsRoot = null)` 一条路，`null` 落成 `true`
    即一律 ROOT —— 以应用自身身份跑一次没有入口。
  - [x] `有ROOT` → `onExecuteFileAndNavigate(path, true)`；`无ROOT` → 传 `false`。
  - [x] ROOT 状态先 probe 再落本地 state（不在组合期读全局 `isRootGranted`，
    否则牵动整页重组）；未授权时 `有ROOT` 置灰并说明只能以普通用户身份执行 ——
    留着可点的话用户拿到的是一串 su 失败输出，而不是一句「没授权」。
- [x] **弹窗两处布局/取值缺陷（真机首版暴露后修）**
  - [x] 三枚 Material3 按钮最小宽度 + 24dp 内边距本就超出行宽，不定宽时
    `有ROOT` 被压到最窄、文字逐字竖排（真机实测「有 / R / O / O / T」）。
    改为 `weight(1f)` 等分 + `maxLines = 1, softWrap = false`。
  - [x] 原先列出 `fileItem.permissions`：目录列举得到的 `FileItem` 不带该字段
    （默认空串），界面上是一行空值。已删除该行；权限信息在同菜单的「权限/属性」里。
- [x] 守护：`ExecuteEntryRegressionTest`（9 项），其中「待执行槽位不得只声明不接线」
  盯的正是上面那条死状态。
- [x] 真机验证：`无ROOT` → 横幅「执行身份: 非 Root」，`/data/adb/shso/smoke.sh`
  如实返回 `Permission denied`、退出码 127；`有ROOT` → 横幅「执行身份: Root」，
  输出 `SMOKE_OK`、退出码 0。两种身份确实走了不同路径。

### A81. 双列状态隔离审查（2026-10-04）

页面里绝大多数状态读写走 `PaneProp` 委托，语义是「聚焦列」。这条委托在单列时代
恰好等价于页面状态，拆成两列后，**凡是本该按列的地方误用了委托，另一列就永远拿不到
自己的状态**。这类缺陷不崩不报错，只是安静地显示错的文案、不重排、不清词。

- [x] **排序 / 隐藏文件开关只重排聚焦列**
  - [x] 两者是全局偏好，改一次应当两列同时生效；原实现只有一个
    `LaunchedEffect(..., nameQuery)`，经委托只看得见聚焦列，另一列保持旧顺序
    且此后再无重算时机，两列看上去「排序规则不一样」。
  - [x] 重算特化改为 `panes.forEach { pane -> LaunchedEffect(..., pane.nameQuery) }`，
    基准、代次守卫、落盘全部走 `pane.*`。
- [x] **非聚焦列切目录后残留上一个目录的搜索词**
  - [x] 清词原本是页面级 `LaunchedEffect(currentDirectory)` + 委托，只清聚焦列。
    右列带着旧 keyword 进新目录，列表按旧词过滤 → 显示「无匹配项」。
  - [x] 并入按列的 `LaunchedEffect(pane.currentDirectory)`。
- [x] **非聚焦列读取失败被显示成「当前目录为空」**
  - [x] `PaneBody` 空态取的是委托的 `directoryLoadError` / `nameQuery`：
    右列失败时借到左列的 null，于是故障又被说成目录是空的 —— 正是这次要消除的误报，
    只是搬到了右列。改读 `pane.*`。
- [x] **保留旧列表时失败原因完全不可见**
  - [x] 空态区只在 `displayFileList` 为空时渲染；失败时旧列表被保留（置灰），
    路径栏也无标记，用户只看到一列变灰的列表。`PaneHeader` 补警告标记。
- [x] **`retriedForEmptyOnce` 永不重置 → 自愈重试全进程只生效一次**
  - [x] 「每列一次」本意是每个目录一次，实际只在进程启动时置位；冷启动那次用掉之后，
    后续任何目录的静默空列表都不再重试。切目录时按列复位。
- [x] **`remember(pane.selectedPaths)` 落在列表项作用域内**
  - [x] 放进 `itemsIndexed` 的 item 里会得到「每个可见行各一份」：既没起缓存作用，
    又把一次 O(N) 换成 O(可见行数 × N)。上提到 `PaneBody` 列级。
- [x] **恒假死分支**：`pane.currentDirectory != requestedDir` —— `requestedDir` 刚从
  `pane.currentDirectory` 取来，两者恒等，该条件永不命中，且下一行即被覆盖。
- [x] **元数据按下标配对，目录中途变化会整体错位**
  - [x] 名称通道 `find -print0` 与元数据通道 `find -exec stat` 是**两趟独立遍历**，
    元数据不带文件名（带 `%n` 会被文件名里的 `|` 与 `\n` 劈开），只能按下标配对。
    遍历之间若有文件增删，从第一条起全部错位：类型错会把文件显示成目录、大小错更具误导性。
  - [x] 设备实测 toybox `stat -c` 不支持 `\0`（原样输出反斜杠零），无法改成 NUL 分隔的
    四字段记录；改为检测数量不一致时**重取一次**（`attempt` 参数，只重试一次，
    否则持续变化的目录上会无限递归）。路径本身由名称派生，不受影响。
- [x] 守护：`DualPaneStateRegressionTest`（8 项）。`DirectoryListingTest` /
  `PerfAndNestingRegressionTest` / `FileListingRefreshRegressionTest` 中锁定旧
  聚焦列写法的断言改为按列语义，并补「不得出现 `activePane.loadingGenRef`」。
- [x] 457 tests / 0 failures / 1 skipped；lint 0 errors。

### A80. 冷启动双列一列不加载（2026-10-04）

需求：冷启动时双列不应出现某一列不刷新、看不到文件。

#### 已修为防守护（A79 同一处定位）

- [x] **代次守卫误写为 `activePane.*` → 左列永远卡在骨架屏**
  - [x] 两列共用同一份代次：左列 gen=1，右列紧接着 gen=2 自增同一计数器
    → 左列结果被全部丢弃，永远停在骨架屏。已改为全部 `pane.*`。

#### 新增修复

- [x] **P0 · 目录失效回退只触发聚焦列 → 右列卡在无效目录里拿不到回退**
  - [x] `LaunchedEffect(directoryLoadFailed)` 走委托，只能看见聚焦列的标记。
  - [x] 非聚焦列的目录失效时回退永远不触发。
  - [x] 已改为每列各挂 `LaunchedEffect(pane.directoryLoadFailed)`；回退目标只取内部存储，
    已在回退目录上时再降一级到根，避免两列被逐一进入无效目录。

- [x] **P1 · 冷启动「静默空列表」自愈守护**
  - [x] 冷启动时两列的 `listDirectory` 可能因 ROOT 授权尚未落定、`su` 首次启动开销较慢
    而返回零条目。那时两列都显示「当前目录为空」，而目录里实际有文件
    ——用户看到的是一个**谓言**，且无任何提示可操作。
  - [x] 在每列列举收尾后做一次自愈检查：**无内容且也无错误原因**时延迟 400ms 重试一次。
  - [x] 三条守卡：有错误原因不重试（已有可读文案，重试反而遮蔽真故障）；
    真空目录不重试（下次仍返零条目）；每列只重试一次（`retriedForEmptyOnce`），避免无限循环。

#### 验证与未复现项

- [x] 449 tests / 0 failures / 1 skipped；lint 0 errors；Debug APK 已装真机
- [x] **冷启动压测 8 轮全部通过**：每轮截图 464~465KB，与「双列均正常」的基线一致（骨架屏时为 337KB）。
- [ ] **未能复现该现象**。以上压测未出现任一列为空。
  推测所见属于另一条路径（如特定目录、或从内部 Intent 进入时 `pendingExternalDirectory` 的分支）。
  已把「静默空列表」变为自动恢复，且补上了非聚焦列的失效回退，
  两条路径都已覆盖。

### A79. 文件页全局底栏（2026-10-04）

把到顶部 / 到底部 / 刷新从「列内」提到**全局底栏**，并新增「新建」「同步」。

#### 参考实现

- MPManager `activity_main.xml:116-179` 的 `bottomBar`：`layout_alignParentBottom=true`，
  5 个按钮均为 `layout_weight=1` 等分占满宽度，每个带 `selectableItemBackground`。
- MPManager `MainActivity.java:1643` 的 `syncPaneButton`：取聚焦列路径写入另一列并加载；
  `:2213` 在非文件视图时 `setEnabled(false)` 置灰。

#### 实施

- [x] **新增 `PaneBottomBar`**：顶部 / 底部 / 新建 / 同步 / 刷新，五个按钮等分，
      位于页面底部、全局 DockBar（56dp）之上。面板区取消了原来的 `padding(bottom=56.dp)`，
      该让位改由底栏外层 Box 承担。
- [x] **为何收全局而非各列一份**：两列各只分到一半宽，容不下五个按钮；
      五个操作本身就是「当前列」的操作，各列一套会让用户误以为影响「所有列」；
      底栏只有一份才能命中。
- [x] **新建**：抽出 `openNewFileDialog()`，预填时间戳 + `txt`；
      “文件列表设置弹窗」的旧入口与底栏共用此函数，避免两条路径各自演化后漂移。
- [x] **同步**：取聚焦列当前目录赋给另一列。不显式调 `refreshPane` ——
      目标列目录变化会由每列各自的 `LaunchedEffect` 触发，显式调会与令牌并发、多起一次列举。
- [x] **禁用态**：两列同目录时「同步」降透明度（参考 `setEnabled(false)`）；
      列表为空时「顶部/底部」降透明度。
- [x] **右列默认落点改为 `/storage/emulated/0/Android`**：两列都停在同目录时「同步」一打开就是禁用态，
      用户会以为功能坏了。参考 MPManager 两列各自记忆 home1 / home2。

#### 修复的两个真实缺陷

- [x] **P0 · 变更目录只传闻聚焦列 → 另一列永远不加载**
  - [x] `LaunchedEffect(currentDirectory)` 走委托只监听聚焦列；“同步」把左列路径赋给右列后，
        右列目录变了而没任何触发 —— 路径栏变了而列表仍是旧目录内容。
  - [x] 改为 `panes.forEach { pane -> LaunchedEffect(pane.currentDirectory) { ... } }`，每列各自触发。
- [x] **P0 · 代次守卫误写为 `activePane.*` → 左列永远卡在骨架屏**
  - [x] 批量替换时把 `refreshPane` 内的代次守卫与节流窗口一并写成了 `activePane.*`（聚焦列），
    而状态读写是 `pane.*`。
  - [x] 后果：两列共用同一份代次。左列启动 gen=1，右列紧接着启动 gen=2 并自增同一计数器
    → 左列 `gen != refreshGenRef[0]` 恒成立 → **左列结果被全部丢弃，永远停在骨架屏**。
  - [x] 真机截图发现（左列怎样卡住、右列正常），定位后全部改回 `pane.*`。
  - [x] 同期发现 4 条测试守护揫反并变绿 —— 它们早以来被我的错误实现带偏了。
    已一并回正为正确写法（页面级重算协程读 `activePane` 仍是正确的，不应改）。

#### 验证

- [x] lint 0 errors
- [x] 真机实测：双列均正常渲染（左列 158 项、右列 Android 目录）；
      底栏五按钮正常可点；**「同步」实测生效** —— 右列从 `.../Android` 跳到 `/storage/emulated/0`，
      随后「同步」自动置灰（同目录无需同步）。logcat 无 FATAL。

### A78. 文件页双列布局（2026-10-04）

按需求把「文件」页改为**左右平分双列**，交互语义对齐参考项目 MPManager 的 dual pane。

#### 实施

- [x] **状态一分为二**：新增 `ui/pages/FilePaneState.kt`，把原先页面级的 15 个状态
      （目录 / 列表 / 加载态 / 作废标记 / 过滤 / 多选 / 滚动位置 / 5 个代次守卫）
      收进一个 `@Stable` 类，左右两列各持一份。
- [x] **读写委托 `PaneProp`**：页面里上百处 `currentDirectory` / `nameQuery` / `isLoading`
      的读写**语义与重构前逐字一致**，diff 里能清楚看出真正的逻辑改动。
- [x] **双列渲染**：`Row { PaneColumn(左,1f) │ 1dp │ PaneColumn(右,1f) }`，
      每列 = 细条（焦点 + 短路径 + 计数/加载）+ 骨架/空态/列表 + 该列的悬浮导航。
- [x] **聚焦路由**：点列内条目即聚焦该列；细条可点以切换聚焦。
      顶栏快捷键 / 返回上级 / 长按菜单 / 批量操作 / 跳转路径一律作用于聚焦列
      —— 与 MPManager 的 `lastPaneSelected` 一致。
- [x] **逐列独立刷新**：`refreshPane(paneIndex)`，代次守卫与节流窗口都落在各自 pane 上；
      右列的刷新不会被左列的代次挡下。
- [x] **紧凑行**（窄列 < 220dp）：行内边距 16→6dp、去掉权限串、去掉内联「执行 / 预览」按钮
      （长按菜单里都仍可触达，非功能丢失）。竖屏 360dp 平分后每列 180dp。

#### 过程中的一次事故（已修正）

第 2 步实施中我执行了 `git checkout -- FilePage.kt` 想回到干净基准重做，
**连带回退掉了此前 A76 / A77 已验证并测试通过的修复**（三态空态文案、刷新不清空列表、
交互门禁、落盘标记、节流、防抖）。已按备份逐项核对恢复 ——
被回退的 10 项测试守护重新变绿即是证据。对承载多次改动的大文件不用 `git checkout --` 回退：它会一并回退既有成果。

#### 验证

- [x] lint 0 errors；Debug APK 已装真机，Activity 正常 Resumed、logcat 无 FATAL
- [ ] **未完成**：双列布局的真机视觉确认。该设备 UI 自动化一贯失效
      （`input tap` 点 DockBar「文件」未触发换页），未能截图取证。
      需手动打开文件页确认左右两列渲染与紧凑行效果。

### A77. 第二十四轮：性能（反应慢）与无用嵌套清理（2026-10-04）：性能（反应慢）与无用嵌套清理（2026-10-04）

前一次修的是「刷新清空列表」。横向排查同类问题：**不报错、不崩，只让用户觉得卡**，
以及**该扁平化却层层包裹**的代码。修复 11 处，测试 432 → **449**。

#### 反应慢

- [x] **P0 · `FileItem` 全部类型判定是 `get()` 计算属性，每行每帧重算**
  - [x] `extension / realExtension / realArchiveName / isInstallable / isViewableImage /
        isEditableText / isArchive / formattedSize` 全无 backing field，
        每次访问都重新 `Regex(...)` 构造 + 多次 `lowercase` + `String.format`。
  - [x] 而文件列表的行内容对同一项**连续求值三轮**（图标文案 / 字号 / 颜色各判一次类型），
        单个可见行每次重组约 7 次 `realExtension` → 7 次正则构造 + 14 个临时字符串。
  - [x] 20 个可见行 ≈ 单次重组 150+ 次正则构造；仅「选中态变化」这种轻量重组即触发。
  - [x] 该文件 149–150 行的注释本就自认「每项每帧都会读取本属性」，
        但当时只修了 `formattedDate`（复用预编译 formatter），其余全部漏掉。
  - [x] 修复：全部改为**构造期求值的 `val`**（只依赖构造入参，语义等价）；
        正则提到顶层 `NUMERIC_SUFFIX` 只编译一次。
        保留 `formattedDate` 的 `get()` —— 它已复用 `FILE_DATE_FORMATTER`，无收益且改动无益。

- [x] **P0 · `fileFilter` 每次重组都是新 lambda → 缓存每帧失效**
  - [x] `TextEditorDialog` 里 `fileFilter = currentFilePath?.let { TextCompare.sameExtensionFilter(it) }`
        **每次调用返回一个新 lambda 实例**，而 `BuiltInFilePicker` 把它用作 `remember` 的 key。
  - [x] 键永不相等 → 缓存每次重组都失效 → 文本对比选择器打开时，
        **编辑器每敲一个键**都重跑「过滤 + 排序 + 每个文件项一次 `File.canonicalPath`
        （realpath 系统调用）」。
  - [x] 修复：`remember(currentFilePath)` 固定 lambda 身份。

- [x] **P1 · 主页行在父组合域读 `filePathInput` → 每击键全表重组**
  - [x] `shsoFiles.forEachIndexed` 是 inline 函数（无 LazyColumn 窗口化），
        行内 `val isSelected = filePathInput == fileItem.path` 在**父组合域**求值，
        于是输入框每敲一个字符 → **N 行全部重组**（每行还带一次 `String.format`）。
  - [x] 该处注释声称「独立成 Composable 以提升跳过性」，但这个收益拿不到。
  - [x] 修复：`derivedStateOf` 把判定推迟到行内部，只有命中行因选中态变化而重组。

- [x] **P1 · 选中集合线性扫描 + 全选判定白算**
  - [x] `selectedPaths` 是 `SnapshotStateList`，`contains`/`containsAll` 为线性扫描；
        全选判定每次重组还要额外分配 2 个 N 长度列表。
  - [x] `allFilesSelected` 写在 `if (showFileSettingsDialog)` **之外**，
        而唯一消费者就是这个弹窗 —— 弹窗关着时纯属白算。
  - [x] 修复：`selectedPathSet = remember(selectedPaths) { toHashSet() }`；
        `allFilePaths` 用 `mapNotNull` 一趟完成（替代 `filter{}.map{}` 两趟）；
        全选判定移进弹窗分支内。

- [x] **P1 · 搜索缺防抖**
  - [x] `nameQuery` 每敲一个字符重启重算协程，而 `LaunchedEffect` 只能取消**协程**，
        已排进 `Dispatchers.Default` 队列的计算不会中断 ——
        1 秒内敲 10 个字符 = 10 次全量「过滤 + 2 次分组 + N 次 lowercase + 排序」并发跑完再依次丢弃。
  - [x] 修复：`delay(250ms)` 放在**协程内部、计算之前**。
        只在「本次确由搜索词变化触发」时延迟（与上次重算的搜索词比较），
        排序方式 / 隐藏文件开关是离散选择，仍即时生效。
  - [x] 踩过的坑：另起一个 `LaunchedEffect(nameQuery)` 去 delay 是**并行**的，拦不住原协程。

- [x] **P2 · 历史面板每行在组合期全文扫描**
  - [x] `entry.content` 上限 20 万字符，每行 `lineSequence().count()` 数换行，
        6 个可见行 ≈ 单次重组 120 万次字符比较，且每次重组重跑。
  - [x] 修复：`remember(entry)` 固定行数与预览串；补稳定 key `key = { _, e -> e.timestamp }`。

- [x] **P2 · 前缀常量每次调用重复归一化**
  - [x] `isExternalPathAllowed` 每次至少跑 6 次 `normalizeForContainment`，
        而两组前缀都是模块级常量、归一化是纯词法函数无副作用；
        `resolveToRealPath` 一次唤起最多调它 4 次 → 单次唤起 24 次重复 split/join。
  - [x] 修复：按列表相等性缓存归一化结果。

#### 无用嵌套

- [x] **`preferRoot()` 两条分支返回逐字相同的表达式**（纯装饰）
- [x] **`rootFailed` 死变量**：从未被置 true，恒为 false。
      注：语义本身**正确**（能走到该行说明 su 从未被调用；su 失败已在 `code == -1` 处
      以 `rootUnavailable = true` 兜底），属死代码而非缺陷 —— 已删除。
- [x] **`pathExistsQuiet` 无用中间层**：函数体就是 `File(path).exists()`，
      与调用处左操作数逐字相同。`||` 只在左为 true 时省下右，
      而左为 false（正是需继续探测下一个名字时）必然**再做一次完整 stat** → 同名副本 N 个 = 2N 次系统调用。
- [x] **`isExtractable` 与 `isKnownArchive` 函数体逐字相同**，且注释停留在
      「rar 暂不支持解压」的旧状态（rar 已移除）→ 合并为一个。
- [x] **`uniqueFile` 无生产调用点且 `while (true)` 无上界**
      （兄弟函数 `reserveUniqueFile` 有 1000 次上限）→ 连同其测试一并删除。
- [x] **`runTextTransform` 里必被覆盖的死写**：try 体内的 `isTransforming = false`
      必被紧随的 `finally` 覆盖（正常与异常两条路径效果完全相同）。

#### 验证

- [x] 新增 `PerfAndNestingRegressionTest` 17 项，覆盖上述全部判断
- [x] 真机（Android 10 / Magisk）安装 Debug APK 并启动：
      Activity 已 Resumed、进程存活、logcat 无 FATAL、无 ANR；
      列表通道实测 `/storage/emulated/0` 154 项、`Android` 7 项、`/data/adb/shso` 5 项
- [x] lint 0 errors；release 2.033 MB

### A76. 第二十三轮：文件列表「空白刷新 / 误报空目录」根治（2026-10-04）：文件列表「空白刷新 / 误报空目录」根治（2026-10-04）

需求指出三个症状：**目录经常显示「当前目录为空」**、**加载慢**、**刷新期长时间看不见文件**。
三者同源，根因在 `refresh()` 与 `listDirectory()`。修复 5 条，测试 424 → **432**。

- [x] **P0 · 每次刷新都清空列表 → 必然经过一段空白窗口（症状的直接成因）**
  - [x] `refresh()` 开头无条件 `fileList = emptyList()` + `displayFileList = emptyList()`，
        于是**每一次**刷新（切目录、删除后、返回上级、手动刷新）都会进入列表为空的时段。
  - [x] 该时段内 UI 命中 `isLoading && displayFileList.isEmpty()` 走骨架屏；
        但**加载完成的瞬间必然先经过一次空态分支**，而空态的兜底文案正是
        「当前目录为空」—— 这就是「目录为空」的误报来源：不是数据为空，
        而是**数据还没回来**。
  - [x] 用户观感即「刷新后长时间看不见文件」+「偶发当前目录为空」。
  - [x] 修复：**不再清空列表**。旧列表保留可见，空白窗口消失。

- [x] **P0 · 保留列表后必须禁用交互，否则引入误删（数据一致性）**
  - [x] 原本「清空列表」同时承担了两个职责：避免空白窗口 + 阻止用户操作上一目录的文件。
        只去掉前者的做法会让后者失守：路径栏显示 B、列表是 A，
        用户长按删除作用的是 **A 里的文件**，无回收站、不可撤销。
  - [x] 查证 `listIsStale` **此前只在 693 行被读取**，从未真正禁用过点击——
        也就是说旧实现里「不可操作」这件事其实**只由清空列表在兜**，
        状态位本身是空的。这需补齐的缺口。
  - [x] 修复：列表项 `combinedClickable(enabled = !listIsStale)` 真正禁用点击与长按；
        同时降透明度（0.45）表达「这批数据已过期」，避免用户点了没反应。
  - [x] `listIsStale = displayFileList.isNotEmpty()`：首屏无历史列表时不置位，
        否则首屏列表将恒不可点。

- [x] **P0 · 三次串行 `su` → 合并为单次，且消除 TOCTOU 窗口**
  - [x] 旧实现是 `test -d` → `find -print0` → `find -exec stat` 三次独立 su，
        每次各自 fork su、各自解析命令（`runStatMeta` 还有第四次重试）。
  - [x] 真机实测：单轮 su 往返约 21ms，三步合计 138ms（800 项）；
        合并后 93ms（50 项）、87ms（300 项）。慢设备 / 冷启动 / Magisk 首次授权时更甚。
  - [x] 更本质的问题是**三次之间存在 TOCTOU 窗口**：`test -d` 通过后目录可能被删除，
        此时 find 零输出而退出码非 -1，旧逻辑据此判 Success(emptyList())
        → 把「目录刚被删掉」报成「当前目录为空」。**这正是该误报的成因**。
  - [x] 修复：单次 su，退出码承载存在性语义
        （`3` = cd 失败 / `4` = 不是目录 / `0` = 成功），语义不再依赖输出是否为空。
  - [x] 真机实测：不存在目录返回 **exit 3**，不再落到空目录。

- [x] **P1 · 两个通道由两次 find 按输出顺序配对 → 改用固定标记分隔**
  - [x] 两次 find 之间目录若发生变化，条目会**整体错位**（拿到别人的大小与时间），
        断链符号链接等 stat 失败场景更会直接少一截。
  - [x] 修复：同一条命令内用固定标记 `META_SEP` 分隔，标记两侧由 `printf` 各补一个 NUL。
        名称通道整体以 NUL 结尾（NUL 不可能出现在文件名里），
        于是 `split('\0')` 后边界绝对清晰。
  - [x] 真机实测：含换行的 `./x\ny`、含竖线的 `./p|q` 均完整解析；
        标记出现恰好 1 次；空目录输出为 `@META@`（零条目）→ 正确判为真空目录。

- [x] **P1 · 同目录连续刷新无节流**
  - [x] 删除 / 重命名 / 移动等操作常在**同一回调里连着调多次 `refresh()`**，
        每次都走完整列举（fork su）。密集触发时列表反复 loading。
  - [x] 修复：同目录且刚成功加载过则合并（窗口 120ms）；
        **跨目录切换、切回上级、手动点刷新一律不受节流影响**（`showToast` 绕开）。

#### 附带修复

- [x] `Missing` 分支补 `directoryLoadError`：
      原先只置 `directoryLoadFailed` 而错误文案为 null，空态会落到
      「当前目录为空」——把「读不到」断言成「没有文件」。
- [x] 删除已无调用者的 `parseStatOutput`（旧单通道解析函数，37 行死代码）。

#### 验证

- [x] 真机（Android 10 / Magisk）端到端实测：
      目录标记识别正确（3 个目录）、空目录零条目、
      **不存在目录 exit 3（不再误报空目录）**、单次 su 87~93ms
- [x] 新增 `FileListingRefreshRegressionTest` 8 项：
      刷新不清空列表、保留列表必须禁用交互、作废标记仅在有旧列表时置位、
      骨架屏优先于空态、空目录文案只在无搜索词且无错误时出现、
      Missing 必须给出原因、同目录刷新必须节流、单次 su 与通道分隔
- [x] 更新 `DirectoryListingTest` 断言以匹配新结构（语义目标不变，取证方式改变）
- [x] lint 0 errors；release 2.033 MB

### A75. 第二十二轮：安全与资源类残留清理（2026-10-04）：安全与资源类残留清理（2026-10-04）

前四轮按「层」推进（文件 / 执行解压 / 编辑器终端 / 累积高危），
本次专挑**安全**与**资源泄漏**两类残留。修复 5 条，测试 411 → **424**。

- [x] **P0 · 分包套件只暂存了被点中的那个 → 兄弟分片可被换包顶替（root 静默装入任意代码）**
  - [x] 根因：`installApk` 里 `stagedPaths = set.orderedWrites.map { if (it == sourcePath) apkPath else it }`
        —— 被点中的走已校验的 app 私有暂存副本，**其余分片仍按原始路径取**。
  - [x] 而分片才是真正的代码载体（base 只含清单与入口）。
        确认弹窗停留期间（阅读提示、切应用），共享存储上的兄弟分片可被替换：
        下载器续传重写、另一应用写 `/sdcard`（该分区任何应用可写）、
        或用户自己用本应用的编辑器覆盖该文件。
  - [x] 后果：用户看到的那个 SHA-256 完全正确，实际被 `pm install` 装入的却是替换后的字节
        → 任意代码以 root 静默装入（install-commit 阶段无系统确认），且**无从察觉**。
  - [x] 代码注释此前把「确认后未被替换」当成已有保证，是**承诺与实现不符**。
  - [x] 修复：整套分片逐个 `stageApkForInstall`；任一暂存失败即中止安装
        （宁可不装，也不能装可能被替换的字节）；安装结束清理本次暂存副本。
  - [x] 分片数上限 `MAX_SPLITS` 有界（64），暂存代价可接受。

- [x] **P1 · `.so` 权限还原排在执行之前 → 空操作，文件永久停在 711**
  - [x] `execCmd` 拼成 `pgidRecorder + restoreAttrCmd + "…chmod a+x …" + exec`，
        还原在 `chmod a+x` **之前**执行：此时文件还是 600，`chmod 600` 是 600→600 的空操作；
        真正放宽权限的 `chmod a+x` 在其后，之后再无还原。
  - [x] 更糟的是 `saveAttrCmd`（记录原权限那一步）**从未被拼进命令**，
        属性文件恒为空，Kotlin `finally` 读到的 `mode` 为 null，**兜底还原也被静默跳过**。
  - [x] 双重失守下，用户 600 的 .so 执行一次就永久停在 711（world-readable + 可执行）。
  - [x] 修复：`saveAttrCmd` 前置于 `chmod a+x`，`restoreAttrCmd` 后置到执行之后、`exit $C` 之前。
  - [x] 放在 shell 内还原而非只靠 `finally`，才能覆盖「进程被强杀 / OOM / 用户划掉」
        这类 `finally` 根本不执行的路径。

- [x] **P1 · CRLF / BOM 脚本无法执行 → 应用能产出自己执行不了的脚本**
  - [x] 三条真机实证：
        1. CRLF 的 shebang 直接执行必失败 —— 内核把 `#!/system/bin/sh\r` 当解释器路径，
           实测 `No such file or directory`；
        2. CRLF 污染变量值 —— 实测 `export V=abc\r` 后 `${#V}` 为 **4**（应为 3），
           后续比较、路径拼接、字符串匹配全错；
        3. 应用编辑器**本身就能写出 CRLF**（行尾风格可选）并可勾选写入 BOM。
  - [x] 修复：执行前归一化（去首 3 字节 BOM + 去全部 CR）到 app 私有临时文件，原文件不动。
  - [x] 两个实现细节均由真机试错确定，不是猜的：
        - **不能管道喂 sh**：`cat x.sh | sh` 会让脚本里的 `read` 吞掉后续脚本文本
          （实测整个脚本无输出），必须 `sh 临时文件` 才能继承 stdin；
        - **不能用 `sed '1s|^\xEF\xBB\xBF||'`**：Android 自带 toybox sed 不支持 `\xNN`
          （实测原样输出、不生效），改用 `od -An -tx1` 比对首 3 字节。
  - [x] 归一化产出为空但源文件非空 → 回落原路径让真实报错浮现，
        不能拿空脚本假装执行成功（否则空脚本从「空操作」变成 `exit=127`）。

- [x] **P2 · `SparseLineIndex.load` 的读取循环无取消检查**
  - [x] 行滑出可视窗口时协程已取消，但 IO 调度器的取消是**协作式**的：
        不会中断阻塞中的 `getChunk`，循环会一路读到凑够换行数或 EOF。
  - [x] 快速拖动滑块时几十个已取消的 load 同时跑满 IO，各自持有最多 4MB 的
        `ByteArrayOutputStream` → GC 抖动、正常行排队，极端情况 OOM。
  - [x] 与上一轮 `ArchiveExtractor.copyStream` 属同一类遗漏（补一处漏一处，故本次全量排查）。

- [x] **P2 · 历史损坏后每次快照都再写一份完整原文 → prefs 无界膨胀**
  - [x] 备份键带时间戳（`$key.corrupt.<millis>`），而主键仍是那份损坏数据
        → 此后每次停顿快照（2.5s 后）/ 草稿快照再失败一次就**再写一份完整原文**
        （单键上限 100 万字符，最坏约 3MB）。
  - [x] 20 次即约 60MB 灌进 `shso_editor.xml`，且永不清理；
        每次启动都要全量解析该 XML，越大越慢、越慢越易失败 —— 正反馈。
  - [x] 修复：备份键固定为 `$key.corrupt`，只在不存在时写，并同时清掉主键。

#### 验证

- [x] 真机（Android 10 / Magisk）逐条实测，见上文各条「真机实证」
- [x] `.so` 权限回退实测：`600 → 执行 → 600`、`640 → 执行 → 640`，退出码保持
- [x] 归一化实测 6 项：CRLF 变量污染 `len=3`（旧行为 4）、BOM+CRLF 正常、
      `read` 继承 stdin 正常、退出码透传 42、空脚本 0、原脚本字节未被改写
- [x] 真机装 Debug APK 后确认 `filesDir` 无 `.sh_norm` / `.exec_attr` 残留（仅 12K）
- [x] 新增 `SecondRoundSecurityAndResourceTest` 13 项（分片整套暂存、暂存清理、
      取消检查位置、损坏键固定、CRLF/BOM 归一化、不得管道喂 sh、
      权限还原时序、不得回退守卫）
- [x] lint 0 errors；release 2.033 MB

### A74. 第二十一轮：累积高危项清理（2026-10-04）：累积高危项清理（2026-10-04）

前四轮（A71~A73）分块覆盖了文件管理、执行解压、文本编辑、终端渲染。
本次不再开新战场，专门清理**前几轮已定位但未修**的高危项。
修复 4 条，测试 401 → **411**（新增 10 项，含一组真实压测）。

- [x] **P0 · 另存为后旋转屏幕 → 保存回原文件并覆盖它（两个文件同时受损）**
  - [x] 根因是**状态生命周期不一致**：`dirty` / `currentCharset` / `currentLineEnding` /
        `hasBom` 用 `rememberSaveable`，而 `currentFilePath`（保存目标）用 `remember`。
  - [x] 触发：打开 a.txt → 另存为 b.txt（`currentFilePath` 改为 b.txt）→ 在 b.txt 上继续编辑
        → 旋转 → 再编辑并保存。重建后 `currentFilePath` 回到 `initialFilePath`（a.txt），
        写的是 a.txt → **b.txt 停在旧内容、a.txt 被覆盖**。
  - [x] 该文件原有注释本就写着「编码 / 换行 / BOM 与**文件路径**、未保存标记一起用
        rememberSaveable」—— 实现与承诺不符，属注释已描述意图但代码没跟上。
  - [x] 修复：`currentFilePath` 改为 `rememberSaveable`（String 标量，Bundle 成本可忽略）。

- [x] **P0 · 后台线程读 Sora `Content` 与主线程编辑竞争 → 闪退或写入损坏的历史**
  - [x] 两处历史快照（停顿快照 `LaunchedEffect(textRevision, …)` 与
        草稿快照循环）在 `withContext(Dispatchers.IO)` 内调用 `soraEditor.text()`。
  - [x] Sora 的 `Content` 只有 `new Content(seq, threadSafe)` 才线程安全，
        而 `CodeEditor` 用的是**单参构造**（`lock = null`、`lines` 为普通 `ArrayList`）。
        IO 线程遍历 `lines` 的同时主线程 `insert`/`delete` 正在 `ArrayList.add/removeRange`，
        可读到**扩容中的空洞（null → NPE）**、错位下标（AIOOBE）或半截内容；
        所在协程无 try/catch，`LaunchedEffect` scope 无 `CoroutineExceptionHandler`
        → 异常直达 `Thread.uncaughtExceptionHandler` → 闪退。
  - [x] 即便不崩，撕裂的内容会被写进 `EditHistoryManager`，成为**内容损坏的历史**，
        用户日后「恢复」它就把编辑器换成乱码。
  - [x] 修复：三处 `soraEditor.text()` 全部移回主线程读取（`runCatching` 收口），
        IO 块内只做纯文件操作（`EditHistoryManager` 读写）。

- [x] **P1 · 解析器 `completed` 无界 → 滑窗满后每次 flush 全量重解析，分配 80MB/s**
  - [x] `HyperCore` 的滑窗只约束**字符数**（`MAX_LOG_LENGTH = 250_000`），
        裁剪点按 `\n` 对齐 —— 对「全空行」输出等价于「一个字符 = 一行」，
        于是 250k 字符的窗口就是 **250k 行**。
  - [x] 每行还要新建 `AnnotatedString` + 空 `ArrayList` + `RangeList`
        （`buildCurrentLine`，约 4 对象/行）；且滑窗裁掉头部后
        `TerminalPage` 的增量判据 `log.startsWith(prev)` **必然不成立**，
        于是每次 flush 都回落 `reset()` + `feed(整段)` 全量重解析 ——
        单次 10~25MB、每秒 4 次，新旧两份快照叠加峰值 40~50MB。
  - [x] 后果：终端持续掉帧、GC 频繁 Major，极端情况 `OutOfMemoryError`；
        且用户不在终端页时（pager 保留全部 4 页）解析协程仍在全额付费。
  - [x] 修复：`IncrementalAnsiParser` 增加 `MAX_COMPLETED_LINES = 4000` 硬上限，
        超限时用 `subList(0, excess).clear()` **一次性**从头部丢弃
        （逐个 `removeAt(0)` 是 O(n) 搬移，每行触发会退化成 O(n²)），
        并累计 `droppedLines` 供 UI 如实告知「已省略 N 行」。
  - [x] 这样 `snapshot()` 的规模恒定有界，全量重解析的每帧分配从 10~25MB 降到常数级。
  - [x] 尚未处理（记录待办）：裁剪后仍走全量分支。根治需给解析器加
        「静默快进」模式（只推进 SGR/光标状态、不构造行对象），
        改动面较大，本次只做上界止损。

- [x] **P1 · 上述解析开销在用户不在终端页时仍然全额发生**
  - [x] `MainActivity` 用 `beyondViewportPageCount = 3` 保留全部 4 页，
        与 `TerminalPage` 注释里「离屏即销毁本页」的**设计前提不一致** ——
        那套「离屏即销毁、重进重建状态」的论证在当前工程并不成立。
  - [x] 本次未改（属架构取舍：常驻省重建开销 vs 持续付解析成本），
        已连同上一条记入待办，待有实测数据再定。

#### 验证

- [x] 新增 `AnsiParserStressTest`：10 万行输入后行数被上界约束（≤5000）、
      省略计数正确、`reset()` 归零 —— **不依赖 UI 的真实压测**
- [x] 新增 `EditorStateAcrossRebuildTest`：保存目标必须 `rememberSaveable`；
      静态扫描确认**没有任何 IO 块内直接读编辑器内容**的调用点
- [x] 新增 `AnsiParserLineCapTest`：行数上限存在、一次性移除、计数归零、上界取值合理
- [x] lint 0 errors；release 2.033 MB

### A73. 第二十轮全面 BUG 深挖：文本编辑层 + 终端渲染层（2026-10-04）

前三轮覆盖文件管理层（A71）与执行解压层（A72）。本次转向**从未审过的两大块**：
文本编辑层（`TextEditorDialog` 2500+ 行是全项目最大文件、`SoraTextEditor`、
`TextCompare`、`SparseLineIndex`、`EditHistoryManager`）与终端渲染层
（`TerminalPage`、`AnsiParser`、`HyperCore`、`SyntaxPackStore`、`ExternalOpen`）。
报出 33 条候选，核实后**修复 5 条**（含 1 条必崩、1 条不可逆数据破坏）。
测试 392 → **401**，全部通过。

#### 不可逆数据破坏

- [x] **P0 · 只读大文件被「保存并关闭」写成 0 字节，且无历史可恢复**
  - [x] 完整失效链（逐环核实）：
        打开 > 32MB（`MAX_LOAD_BYTES`）文件 → 稀疏行索引**只读浏览态**（`isLargeFile = true`）
        → 设置里改「换行」或「写入 BOM」，二者都无条件 `dirty = true`，顶栏出现「● 未保存」
        → 点「✕ 关闭」→「未保存的更改」→「保存并关闭」
        → `UnsavedChangesDialog.onSave` 只判 `isLoading` / `loadError` / `isSaving`，**漏掉 `isLargeFile`**
        → 只读态下 `SoraTextEditor` 未进入组合，`syncSnapshot()` 因 `!isAttached` 直接返回，
        `contentValue.text` 仍是初始空串（该分支从不调 `setEditorContent`）
        → `writeTextFile(path, "", …)` 以「临时文件 + 原子替换」把 32MB+ 原文件**写成 0 字节**，
        界面弹「已保存」。
  - [x] 该路径**不写 `addHistory`**，草稿快照循环也因 `isLargeFile` 跳过
        → 历史里没有任何可恢复版本，文件内容彻底消失。
  - [x] `doSave` 与 `SaveAsDialog` **都有**该守卫（`isLargeFile` 分支），
        唯独未保存弹窗漏了 —— 三处各自抄一遍守卫的结构性缺陷。
  - [x] 修复：补齐 `isLargeFile` 守卫，并给出可读原因。
        （另有一版把三条入口收敛为单一 `saveBlockedReason()` 的改法，
        但实测编辑过程中改动过大、需回退重做；已改为最小补丁 + 护栏测试锁定三处覆盖。）

#### 必崩

- [x] **P0 · 关闭查找弹窗后用同一检索词点「查找下一个」→ `IllegalStateException` 闪退**
  - [x] Sora 的 `EditorSearcher` 把「有无检索词」记在 `currentPattern`，
        而 `gotoNext()` / `matchedPositionCount()` / `replaceCurrentMatch()`
        的**第一条指令就是 `checkState()`**，`currentPattern == null` 时无条件抛。
  - [x] 触发：查找 → 输入 `abc` → 查找下一个 → 「✕ 关闭」（`stopSearch()` 清 Sora 侧）
        → 再打开、输入**相同**的 `abc` → UI 侧判定 `findText == findQuery`「无需重新检索」
        → 直接 `gotoNextMatch()` → 抛异常。该调用在 `Modifier.clickable` 回调里，
        **无 try/catch** → 直接闪退。
  - [x] 成因：控制器侧检索状态与 UI 侧 `findQuery` 是**两份独立状态**，
        `stopSearch()` 只清了其中一份。
  - [x] 修复：`SoraEditorController` 增加 `queryActive` 记录有效性，
        `search()` 置真、`stopSearch()` 清零；`gotoNextMatch` / `replaceAll` /
        `replaceCurrentMatch` / `searcherMatchCount` 一律先判有效性，
        并额外用 `runCatching` 兜底（防库行为变化）。`searcherMatchCount` 无检索时返回 0 而非抛。

- [x] **P1 · 「替换/全部替换」进行中关闭查找弹窗 → `searcherMatchCount()` 抛异常闪退**
  - [x] 与上条同源：`awaitSearchDone` 的轮询与替换流程都把它当取值 API 使用，
        且调用点在协程里（异常直达崩溃）。已由同一处收口一并修复。

#### 状态机失效

- [x] **P1 · 解析代次守卫跨 Activity 重建完全失效 → 旋转屏幕出现重复行**
  - [x] `parseGenRef` 是 `remember`：旋转后新旧 Activity 各持一份**全新数组**，
        `myGen` 都等于 1，`parseGenRef[0] == myGen` 两侧同时成立 → 守卫等于不存在。
  - [x] 旧 Activity 那个 in-flight 解析块（纯 CPU、无挂起点，取消打不断）
        会与新 Activity 的首个 collect 串行进入 `synchronized(ansiParser)`，
        各自持有自己捕获的 `prev`，后进入者按旧 prev 把同一段再喂一次
        → 终端出现最多一批（≤ 一个 flush 间隔）的重复行。
  - [x] 修复：代次计数器提到**文件级** `TerminalParseGeneration`（与进程级解析器缓存同生命周期）。
        前置自增的约束保留（后置自增会使守卫恒假、输出区永远空白），并加护栏锁定。

#### 功能逻辑

- [x] **P1 · 输出不含换行时自动滚动停在行首，新输出永不可见**
  - [x] `LaunchedEffect(parsedOutput.lines.size, isImeVisible)` 只以**行数**为 key。
        命令输出不含换行时（`cat` 单行大 JSON、`curl` 长响应、二进制 `cat`）
        行数恒定 → effect 根本不重跑 → 视口停在原处，
        新增内容全落在软换行折叠线以下。观感是「命令跑着跑着终端不刷新了」，且无任何提示。
  - [x] 即便重跑也不够：`scrollToItem` 只把该 item 的**顶端**对齐视口顶端，
        对超过视口高度的行（软换行后多个视觉行），尾部永远在屏外。
  - [x] 修复：key 加入**末行长度**，滚动后再 `scrollToItem(last, Int.MAX_VALUE / 2)` 钉到底。
        原注释「key 用行数而非字符串长度，避免每次 flush 都取消并重启协程」
        只对多行输出成立，对单行持续输出恰好是缺陷来源，已一并更正。

#### 真机验证（Debug APK + root）

- [x] 终端链路正常：`重启终端` 正确输出反馈并自动滚到底
- [x] `FATAL` / `ANR` / `Exception` 全程 0

#### 本次观察到的工具限制

- [x] `adb shell input text` 对含空格/长串的命令在本机**常静默失败**
      （输入框被清空但命令未执行、输出区空白）→ 终端命令类验证不能用它作为唯一手段，
      需改用「按钮触发型」操作（如重启终端）或脚本下发

### A72. 第十九轮全面 BUG 深挖：执行解压层（2026-10-04）

A71 修完文件管理层后，本次转向**执行与解压链路**
（`RootService.runCommandSync` / `ArchiveExtractor` / `ApkInstaller`）。
核实后**修复 6 条**，其中 **1 条是本次新发现的真实 Zip Slip**（由新加的护栏当场抓到）。
测试 385 → **392**，全部通过；lint 0 errors；release 2.033 MB。

#### 资源回收：超时留下 root 孤儿（本次最高危）

- [x] **P0 · `runCommandSync` 超时只杀 `su`，子孙进程继续以 root 运行**
  - [x] 真机实测（Android 10）：
        `su -c 'sleep 300 & echo $!'` → `CHILD=5361 SHELLPID=5360`；
        `ps -o pid,pgid,args` 显示 `5361 5360 sleep 300`，即 **su 的子 shell 自任组长**
        （pgid == pid），`sleep` 继承该组。`destroyForcibly()` 只对 `su` 的 pid 发信号，
        被重挂到 init 的 `sh -c …` / `pm` / `cp` **继续以 root 运行**。
  - [x] 放大路径：本函数在全项目有 60+ 调用点（安装、拷贝、解压、列目录、权限）。
        上层拿到 -1 后立刻跑 `finally` 清理 —— `installApk` 会删掉临时 APK，
        而那个 root 进程还在读它 → 后续偶发 `INSTALL_FAILED_*` 或半安装态，
        用户完全无法归因（界面只显示「命令执行超时」）。
  - [x] 修复：新增 `forceKillProcessTree` —— 先从 `/proc/<suPid>/stat` 取 pgrp，
        复用既有的 `buildProcessGroupKillCommand`（三重校验：pgid > 1、
        确认是组长、不是本应用所在进程组）整组 SIGKILL，再 `destroyForcibly` + 补杀 su。
  - [x] `comm` 可能含空格与右括号，故从**最后一个** `')'` 之后切分 `/proc/pid/stat`。
  - [x] 真机验证按 pgid 整组回收有效：`before=1` → `after=0`。

- [x] **P1 · 输出累积用非线程安全 `StringBuilder`，异常被吞后输出全丢**
  - [x] 读线程 `append` 与主线程 `toString` 并发：超时路径下读线程尚未退出，
        `AbstractStringBuilder` 撕裂或越界；该异常被外层 `catch (e: Exception)`
        吞掉并替换为 `Pair(-1, e.message)` —— **已收集的输出全部丢失**。
  - [x] 改为 `CommandOutputCollector`（`@Synchronized` 的 append 与 snapshot）。
  - [x] 顺带加**输出上限** `MAX_SYNC_OUTPUT_CHARS = 4M`：无上限时一条 `find /`
        或 `logcat -d` 就能让 StringBuilder 扩容峰值达 2× 加上条目对象直接 OOM。
        超限后**继续 drain**（否则写端填满管道会让子进程永久阻塞）并如实标注「已截断」。
  - [x] `join(5000)` 超时后不再静默返回，如实标注「结果可能不完整」——
        该情形说明有后代进程仍持着管道写端，尾部输出会丢。

- [x] **P1 · 安装超时时删掉了仍在被读取的 APK**
  - [x] `installApk` 的 `finally` 无条件 `rm -f` 临时 APK。
        `runCommandSync` 超时只代表「没等到」，**不保证子进程已死**。
  - [x] 修复：`-1` 时保留临时文件，并在失败文案里如实告知路径，由用户稍后自行清理。

#### 解压安全

- [x] **P0 · 归档名为 `..` 的条目解析到文件系统根（新发现，本次护栏当场抓到）**
  - [x] 归一化里 `n.replace(Regex("(^|/)\.\.(/|$)"), "/")` 把 `..` 替成 `/`，
        而紧随其后的 `trim()` **不去斜杠** —— `..` 变成 `/`，既不满足 `isEmpty`
        也不等于 `.` / `..`，**绕过空名分支**，直接 `File(target, "/")`。
        `File` 见到以 `/` 开头的子路径会当作**绝对路径**，条目被写到文件系统根。
  - [x] 危害：归档里名为 `..` 的条目即可在 `/` 下创建文件；名称可控时覆盖根目录同名文件。
  - [x] 修复：归一化后统一 `trim('/')` 再判空。
  - [x] 该缺陷是本次**先加护栏、护栏立即失败**才发现的 —— 说明 A71 修 `safeDest`
        时只改了返回类型，没重新推演归一化链上的每一环。

- [x] **P1 · 越界条目被静默改名落盘，而非拒绝**
  - [x] 旧实现在越界时把条目改名写到 target 根（`../evil.sh` → `./evil.sh`）。
        除了「磁盘结构与归档声明不一致」，更实际的后果是：恶意归档里
        `../../x.apk` 与 `sub/x.apk` 映射到同一 canonicalPath →
        触发 `writtenPaths` 重复判定 → **整次解压失败并删掉全部已解压内容**。
        也就是说静默改写避免不了失败，只是把失败点挪到更晚、更难归因的位置。
  - [x] 修复：`safeDest` 越界返回 `null`，调用方明确报「归档包含越界路径」。
        与既有的「重复条目」处理一致：立刻失败、明确报错、清理已解压内容。
  - [x] 同步加固：`fallbackName` 必须**自身合法**（不含分隔符、不为 `.`/`..`），
        否则「合法条目走占位名」这条路在 `entryName == ".."` 时会再次落空。
        新增 `placeholderNameFor` 生成安全占位名。

- [x] **P1 · 解压取消完全无效，清理分支是死代码**
  - [x] `extract` 整体在 `withContext(Dispatchers.IO)` 里，而 IO 调度器的取消是
        **协作式**的：不会中断阻塞中的 `input.read()`，块体一路跑到自然结束，
        `withContext` 只在块体返回时才抛 `CancellationException` ——
        而三处 `catch (e: CancellationException)` 的清理分支写在块体**内部**，永远看不到它。
  - [x] 后果：`targetFile?.delete()` / `finalTarget.deleteRecursively()` 永不执行；
        用户取消后磁盘 IO 继续写满整个 ExtractionBudget（最大 1GB）；
        同时 `isExtracting` 已复位，用户可再次发起解压 → **两个解压并发写同一父目录**。
  - [x] 修复：`copyStream` 每个 64KB 块边界、7z 每个条目边界调 `ensureActive()`。
        为此把 `extractZip` / `extractTar` / `extract7z` / `extractSingle` 改为 `suspend`。

#### 直接执行路径：权限被永久放宽、退出码被吞

- [x] **P1 · 执行 `.so` 永久把用户文件从 600 放宽成 world-readable+可执行**
  - [x] 旧实现 `chmod 755 $file && ( $file || sh $file )`，且**执行完不还原**。
        用户放在 `/data/adb/shso` 的私有文件被应用单方面改成 755，
        同设备其他 root 环境（Recovery、其他工具链）随之可读。
  - [x] 修复：执行前 `stat -L -c %a` 把原权限记到 app 私有目录，
        只补 `chmod a+x`（不再整体改 755），收尾在 `NonCancellable` 块内
        按原权限 `chmod` 还原并删除记录文件。
        放在 NonCancellable 内是必须的：协程被取消时 `finally` 仍执行，
        否则权限永远还原不了。

- [x] **P1 · `( $f || sh $f )` 把 ELF 字节当 shell 脚本喂进终端，退出码被覆盖**
  - [x] `||` 语义是「前一条失败才执行后一条」。ELF **正常返回非 0 退出码**时，
        会回落执行 `sh <二进制>` → `InputStreamReader(UTF_8)` 产出大量 U+FFFD
        与控制字符灌进 AnsiParser，用户看到满屏乱码，
        而真实退出码被 `sh` 的失败码（127）覆盖 —— 排障时被彻底误导。
  - [x] 修复：如实保留退出码 `exit $C`，仅在 126/127（无法执行 / 格式不对）时
        补一句明确提示，不再用 `sh` 兜底二进制。

#### 真机验证（Debug APK + root）

- [x] 权限还原链路：`600 → 711（执行中）→ 600（还原后）`
- [x] 退出码保留：脚本 `exit 42` 经执行路径后仍为 `42`，未被 `sh` 兜底覆盖
- [x] 按 pgid 整组回收：`before=1 → after=0`
- [x] 文件页 150 项正常列出（新 NUL 双通道）
- [x] `runCommandSync` 改动后 lint 0 errors / release 2.033 MB

#### 本次新增设备事实

- [x] `su -c` 的子 shell **自任进程组组长**（pgid == pid），子孙继承该组
      → 超时回收必须按 pgid 整组杀，只杀 `su` 会留下 root 孤儿

### A71. 第十八轮全面 BUG 深挖（2026-10-04）

前一轮（A70）修完「当前目录为空」，本次用 **Debug APK + root 真机** 做全项目深挖：
并行审查文件管理层（`RootFileManager` / `FileItem` / `ZipEntryCountProbe` / `ChunkedFileReader`）
与执行解压层（`RootService` / `ArchiveExtractor` / `ApkInstaller` / `ApkExtractor` /
`HyperCore` / `ExecutionForegroundService`），报出 30 条候选。

**其中 10 条经代码核对确认成立并已修复**，其中 2 条是 A70 修复引入的**反向回归**
（真机复现后立即修正），4 条属不可撤销的数据破坏，2 条属安全，2 条属状态机失效。
测试 367 → **385**（新增 18 项护栏），全部通过。

#### A70 修复引入的反向回归（本次最先发现并修正）

- [x] **P0 · 合法空目录被误判为「读取失败：无权限读取该目录」，重试永远失败**
  - [x] A70 用 `rootProducedOutput`（find 是否有输出）区分「空」与「不存在」，
        但这两者 find 输出**同为空白、退出码同为非 -1**，判据本身不成立。
  - [x] 真机复现：`/data/adb/shso/emptydir`（空目录，应用侧被 SELinux 拦）
        在文件页显示「读取失败」；`runCommandSync` 实测 root 侧 `find` 退出码 0、零输出。
  - [x] 改为先用 `test -d && echo <标记>` 取**存在性证据**，与「里面有什么」分开提问：
        ROOT 确认可进入 + 零条目 = 权威的「真空」；ROOT 确认不是目录 = `Missing`。
  - [x] `Missing`/`Failed` 的最终区分下沉到新的 `listDirectoryLocal`
        （只取决于本地读不到时的存在性复核，与 ROOT 无关）。

- [x] **P0 · 有 ROOT 时 `DirectoryListing.Missing` 永远不可达，回退逻辑全面失效**
  - [x] 分支顺序错误：本地 `listFiles()` 返回 null 被 `rootAttempted` 抢在前面
        判成 `Failed`，永远走不到 `Missing`。
  - [x] 后果：`FilePage` 的「目录失效回退内部存储」与 `BuiltInFilePicker` 的
        回退重列在**有 ROOT 的设备上完全失效** —— 记忆目录被删后用户被卡在旧路径，
        且配合下一条可在旧列表上执行删除。
  - [x] 由存在性证据重排后修复：先问「在不在」，再问「有什么」。

#### 不可撤销的数据破坏

- [x] **P0 · 列表陈旧期间可对「上一目录」的文件执行删除**
  - [x] A70 的 `Failed` 分支刻意「保留旧列表」，但 `currentDirectory` 已是新目录。
        渲染层只在列表为空时显示空态，否则照常可长按/多选/删除。
  - [x] 触发：切目录或读取失败期间（配合上面两条，空目录必然进入该状态），
        路径栏显示 B、列表是 A 的文件，长按删除 → **删掉 A 里的文件**，无回收站。
  - [x] 修复：`refresh()` 发起即 `listIsStale = true` 并清空 `fileList`/`displayFileList`，
        让旧列表立即不可操作；重算协程在 `listIsStale` 期间不得落盘
        （否则会把「读取失败」覆盖回「目录为空」）。

- [x] **P0 · 文件名含换行会产出幻影条目，长按删除即误删真实文件**
  - [x] 旧格式 `stat -L -c '%A|%s|%Y|%n'` 把裸文件名放进**以 `\n` 分隔**的记录里。
        ext4/f2fs 允许文件名含 `\n`，故名为 `x\ndrwxr-xr-x|0|0|y` 的文件被解析成两条：
        一条指向不存在的 `<dir>/x`（幻影），一条是伪造的「目录 y」。
        长按删除幻影 `x` → `rm -rf <dir>/x` → **删掉真实的 x**。
        尾随空格同样被 `trim()` 抹掉后指向同名但不同的文件。
  - [x] 真机能力探测（Android 10 toybox）：
        `find -print0` **可用**，`find -printf` **不可用**（`bad arg '-printf'`）。
  - [x] 改为双通道：**名称走 `find -print0`（NUL 分隔，NUL 不可能出现在文件名里）**，
        **元数据走不含文件名的 `%A|%s|%Y`**（只含权限串与两个整数，按 `\n` 切分永远安全），
        按索引配对；数量不等时缺失项按非目录处理（几乎只发生在断链符号链接 `stat -L` 失败，
        而断链本来就不是目录）。删除了已无引用的 `parseStatOutput`。
  - [x] 真机实测：造名含真实换行的文件，`find -print0` 输出 3 条记录 = 3 个真实条目，
        名字完整未截断，无幻影条目。

- [x] **P0 · procfs / sysfs 节点被当成「完整的空文件」，编辑器一保存即清空**
  - [x] 缺陷链条：这类文件 `stat %s` 恒为 0 但**有内容**；
        `loadAll` 见 `total <= 0` 返回空文本，而 `isComplete` 写作
        `totalBytes <= 0L || loadedBytes >= totalBytes` → 0 被判为「读完了」。
        编辑器标 `dirty = false`，用户毫无察觉，一次无关编辑后
        `writeTextFile` 用空内容整文件覆盖 —— 对 root 可写的节点就是数据销毁。
  - [x] 真机实测**推翻了看似可行的修法**：`test -f /proc/cpuinfo` 返回**真**
        （procfs 项的类型就是普通文件），所以「零字节时查文件类型」拦不住。
        三者的 `stat %s` 完全相同（都是 0），只有实读能区分：
        `dd if=/proc/cpuinfo | wc -c` = 1、真空文件 = 0。
  - [x] 改为 `probeReadableBytes()`（`dd … | wc -c`）作为唯一可靠判据；
        `fileSize` 探测失败改返回 **-1**，与真空区分；
        `LoadResult` 新增 `readFailed`，`isComplete` 改为 `!readFailed && loadedBytes >= totalBytes`。
  - [x] 编辑器按 `readFailed` 区分文案（内容残缺 vs 目标非普通文件），两条都走
        `loadError` 通道阻止保存。
  - [x] 顺带修 `readRange` 的负 offset 越界与 `count > 2GB` 的 `toInt()` 溢出，
        并把 `readRangeRoot` 整体包进 `try`（原先 `copyOfRange` 在 try 外，
        异常会逃出并跳过本地兜底，把「root 通道失败」变成崩溃）。

- [x] **P1 · `moveFile` 的冲突策略可被绕过，静默覆盖用户文件**
  - [x] `destExists()` 被调用两次（冲突分支一次、算 `finalPath` 又一次），
        每次都 fork su，两次结果可以不同（su 超时即返回 false）：
        第一次 false → 完全跳过冲突校验；第二次 true 且 RENAME → `finalPath` 取
        `_new` 名而该名**从未校验过** → `mv` 静默覆盖已存在的 `xxx_new`。
  - [x] 改为单次探测 `destCollides`，两处共用同一结论。
  - [x] 顺带修 OVERWRITE 覆盖目录「先 `rm -rf` 再 `mv`」无回滚：
        改为先把旧目录**改名让位**，移动成功后才清理，失败则改回；
        回滚也失败时如实告知占位路径，不再让目标目录静默消失。
  - [x] 顺带修符号链接移动被误报失败：验证由 `test -f/-d`（跟随链接，必然为假）
        改为「源已消失 + 目标已存在」。

- [x] **P1 · `copyFile` 的 `chmod 644` 抹掉可执行位**
  - [x] `cp -p` 刚保留完权限，下一句 `chmod 644` 把它改成 rw-r--r--，
        连普通 0755 的执行位也一并抹掉 —— 拷出的 `.sh` / `.so` 立刻失去执行权限。
  - [x] 改为「只减不增」：`chmod u-s,g-s,go-w`（清 setuid/setgid 与 g/o 写位，保留 x）。
  - [x] Java 回退路径同样按来源权限设置可执行位，两条路径产物一致。

- [x] **P0 · XAPK 安装失败会删掉「安装前就存在」的 OBB**
  - [x] 幂等分支（目标 OBB 已存在且内容一致，刻意不碰该文件）却把它写进了
        `installedObbTargets` 回滚表；`outer finally` 在锁释放前遍历该表，
        `removeOwnedObb` 的两道校验此时都通过 → `rm -f` 命中。
  - [x] 与 `copyObbAtomically`「绝不覆盖用户原有 OBB」的设计直接矛盾。
  - [x] 修复：幂等条目改记入 `preexistingObbTargets`，回滚时做减集；
        并在删除循环里再加一道 `path in preexistingObbTargets` 的兜底。

#### 状态机失效

- [x] **P0 · 新启动的脚本失去唯一的终止出口，进程成为 root 孤儿**
  - [x] `stillOwnsExecution` 的判据是「`targetJob == null && terminalSlotOwner != 0`」，
        只说明**存在某条**终端命令，不说明是本次要收尾的那条。
  - [x] 而 `startExecution` 只 `terminalCommandGeneration.incrementAndGet()`，
        **从不**清 `terminalSlotOwner` —— 上一条注释承诺的契约代码里根本没实现。
  - [x] 时序：`startExecution` 因 `isTaskRunning` 先调 `killCurrentProcess`，
        kill 的 `finally` 早于新任务的 `waitFor` 返回，令牌仍非 0 → 判真 →
        把**新任务**的 `isTaskRunning`/`currentTaskName`/`currentTaskPath` 清空、
        停掉它的批量发布循环。脚本真在跑，UI 显示「待命中」，
        「结束进程」被禁用，pgid 与路径记录也被清 → 用户失去唯一出口。
  - [x] 修复：`startExecution` 兑现契约（`terminalSlotOwner.set(0L)`）；
        `stillOwnsExecution` 改为比对**精确令牌**（用掉此前捕获却从未使用的
        `targetSlot` 死变量），`restartTerminal` / `sendInterrupt` 同样补上捕获。

- [x] **P2 · `sendInterrupt` 未走交互写锁，与 `sendInput` 并发写同一 encoder**
  - [x] `sendInput` 显式加了 `interactiveWriteLock`，`sendInterrupt` 直接写
        `targetWriter`。`OutputStreamWriter` 的 `StreamEncoder` 非线程安全
        （byteBuffer/charBuffer 与 leftover 状态），并发写互相覆盖残留字节。
  - [x] 修复：纳入同一把锁。

#### 本次新增的环境事实（写入状态速览）

- [x] `find -print0` 可用、`find -printf` **不可用**（toybox `bad arg`）；
      `stat -L -c %s` 正常返回数字，但 `test -f` 对 procfs 节点返回真
      → 判定「真空 vs 有内容」只能用实读（`dd | wc -c`）

### A70. 「当前目录为空」误报专项（2026-10-04）

用户报文件页「经常出现『当前目录为空』」。这一轮用 **Debug APK + root 真机** 定位，
报出**三个叠加缺陷**：前两个是真机可复现的数据错误，第三个是掩盖前两者的一层失真表达。
三者共同构成一条完整的失效链：探测时序 → 竞态覆盖 → 文案失真。

#### 已修（三处，367 tests 全绿 + 真机验证）

- [x] **P0 · 目录列举无法区分「真空」与「失败」，失败被显示成「当前目录为空」**
  - [x] `RootFileManager.listFiles` 返回裸 `List`，上层只能 `isEmpty()`。
        su 被拒 / 并发 fork 失败 / 输出截断 / stat 全解析失败，四类故障全被压成
        「空目录」这一个信号 —— 而空目录是**事实陈述**，不该承载失败。
  - [x] 改为 `sealed interface DirectoryListing`：`Success(items)` / `Missing` / `Failed(reason)`。
  - [x] 判定细化：`code == -1`（su 失败/超时）→ `Failed`；有输出但零条目 → `Failed`；
        本地兜底返回 null 且路径非目录 → `Missing`；是目录但读不到 → `Failed`。
  - [x] UI 相应改为三态文案：`读取失败：<原因>` + 「点右上角刷新重试」，
        保留旧列表不覆盖；「无匹配项」「当前目录为空」两种真空态文案不变。
  - [x] `BuiltInFilePicker.loadDirectory` 同病同治：`catch` 分支原会
        `fileList = emptyList()`（谎称空目录），现保留旧列表并记 `loadError`。

- [x] **P0 · root 探测未完成即列目录：`null` 被当成「无 ROOT」而误降级**
  - [x] 真机复现（Debug 首启 + 已授权 Magisk）：
        `refresh gen=1 dir=/storage/emulated/0 root=null loaded=0 display=0`，
        而该目录实际有 **150 项**。授权完成后同一路径 `root=true loaded=1`（`/storage/emulated` 只有 1 项，正确）。
  - [x] 成因：`isRootGranted` 是 `Boolean?`，初值 `null`；`preferRoot()` 写作 `== true`，
        首帧探测尚未落定 → 直接跳过 su 走本地 `File` API，而「所有文件访问」
        可能刚授权未生效、或路径在应用无权访问处 → 空列表。
  - [x] `null` 的语义是**尚未探测**而非**无 ROOT**，改为 `awaitRootState()`：
        轮询等探测落定，上限 `ROOT_STATE_WAIT_MS = 3_000`，`ROOT_STATE_POLL_MS = 50`。
        超时后按已知状态继续，**不无限阻塞**文件页（授权弹窗可能长期挂起）。
  - [x] 计时用 `SystemClock.elapsedRealtime()`，抗系统时间跳变。
  - [x] `preferRoot` 随之改为 `suspend`，12 处调用点全部已在 suspend 上下文（逐个核对）。

- [x] **P0 · 展示列表重算协程用陈旧 `fileList` 覆盖新目录列表（竞态）**
  - [x] 真机复现（同一日志相邻两行，顺序即缺陷）：
        `recompute gen=1 src=0 out=0` **先于** `refresh gen=1 ... loaded=0` 落盘。
  - [x] 成因：`LaunchedEffect(showHiddenFiles, fileSortMode, nameQuery)` 与 `refresh()` 并发。
        它抓 `refreshGenRef[0]` 作守卫，但该代次只在 `refresh()` **启动**时递增；
        在其 IO 落盘前进入本协程时代次**恰好相同**、守卫放行，
        于是用**上一份** `fileList`（切目录时即空列表）算出结果并写入 `displayFileList`，
        此后 key 不再变化、**永远无人重算** → 用户看到「当前目录为空」。
  - [x] 代次相同 ≠ 基准有效。新增 `loadingGenRef`（`-1` = 无加载进行中）表示
        「本代是否已把 `fileList` 落盘」：`refresh` 启动时置 `gen`，
        `finally` 中**仅限最新一代**清为 `-1`；重算协程在计算前后各判一次。
  - [x] `navigateTo` 刻意不碰该标记 —— 否则会在 refresh 尚未启动时短暂呈现
        「已落盘」，让重算协程得以用旧 `fileList` 落盘，缺陷复现。

#### 真机验证（Debug APK + root）

- [x] 撤销 Magisk 授权策略模拟冷启动 → 启动即进文件页：
      `/storage/emulated/0` **150 项全部列出**（修前同一场景显示「当前目录为空」）
- [x] `/storage/emulated` → 1 项（`0`），路径栏与列表一致
- [x] 点刷新后列表稳定保持，不再被重算协程清空
- [x] 主页 `/data/adb/shso` 4 项正常（`archtest` / `shso.hidden` / `d.sh` / `smoke.sh`）
- [x] 全程 `FATAL` / `ANR` / `NoClassDefFoundError` = 0
- [x] 临时探针（`shsoPROBE`）已从源码移除，哈希与索引一致

#### 回归护栏

- [x] 新增 `DirectoryListingTest`（14 项），覆盖三态互不混淆、su 失败上报、
      有输出零条目判失败、本地兜底失败不谎称空、`navigateTo` 不碰标记、
      等待逻辑有超时与轮询等
- [x] 更新 `PickerAndPermissionRegressionTest.回退目录必须真的去列内容`：
      原断言钉死 `listFiles(resolved)` 这一实现细节，三态改造后必然失效。
      改为锁定**语义**（回退分支必须对回退目录重新发生一次列举），不绑定 API 名称。
- [x] `compileDebugKotlin` 无警告

### A66. 第十六轮全面 BUG 深挖（2026-10-03）

前十五轮覆盖了策略引擎、执行链路、文件管理层、守卫安装、文本处理链路，这轮转向
**UI 弹窗层**（`ColorWheelDialog` 439 / `FilePermissionDialog` 405 / `BuiltInFilePicker` 634）。
三个文件报出 26 条候选，核对后**确认成立并已修 6 条**；其中**一条被证伪**，
一条因**真机环境与报告前提不符**而重新判定。

- [x] **产品范围**：三份文件全量重读，交叉核对 `RootFileManager.statFilePath`、
      `OwnerCandidates`、`AuroraComponents`、`HomePage` 与 5 处调用方

- [x] **P0 · 取色器：拖色相条会篡改用户没选过的颜色并落盘**
  - [x] 两个 `pointerInput` 协程里直接 `saturation = 1f; value = 1f`。注释写的是
        「低饱和/低明度下拖色相看不出变化」——那是**显示层**补偿需求，
        但实现写回了 `saturation`/`value` **状态本体**，而提交读的
        `currentColor`（127-129 行）正是由它们派生、426 行 `onColorSelected(currentColor)`。
  - [x] 触发：终端色为暗色或低饱和（如 `#1A1A1A`，s≈0.10 v≈0.10）时打开取色器，
        只想微调色相，手指一碰色相条 → s/v 被强抬到 1.0 → 预览从深灰跳成亮色，
        且**落盘的是用户没选过的颜色**。这是本文件唯一一处「所见即非所得」的写数据路径。
  - [x] 该文件 116-122 行的注释早已写明「提交取值保持**未抬高的原值**……预览补偿只放在
        previewColor」—— 实现与自身声明的设计意图正好相反。
  - [x] 修法：两处赋值删除；补偿移入 `previewColor`（`coerceAtLeast(0.7f/0.6f)`）

- [x] **P0 · 文件选择器：记忆目录失效后「路径行显示 A、列表却是空的」**
  - [x] `loadDirectory` 的 `loaded` 在 `!exists` 分支恒为 `emptyList()`，而回退分支只改了
        `currentDir`，**从未对回退目录调 `listFiles`**。于是拔过 SD 卡 / 删过记忆目录后，
        路径行显示「内部存储」、列表空白并提示「当前目录为空」——
        用户会得出「内部存储是空的」这一完全错误的结论。
  - [x] 修法：先定 `resolved`，再对 `resolved` 列目录

- [x] **P1 · 数据一致性：记忆目录被陈旧请求覆盖**
  - [x] 两处 `rememberedDirectory = …` 都排在代次守卫**之前**，而那行注释原文写着
        「丢弃本次结果（**含副作用**）」—— 副作用已经发生，守卫只挡得住返回值。
  - [x] 触发：快速连点目录 A 再点 B，A 的 root 调用更慢（100~500ms 抖动常见）
        → 记忆目录被覆盖回 A，而用户最后实际浏览的是 B，下次打开落在 A。
  - [x] 修法：写入移到守卫之后

- [x] **P1 · 功能：选择器内改设置会把已浏览目录重置**
  - [x] `LaunchedEffect(show, initialDirectory)` —— `initialDirectory` 依赖
        `appSettings.rememberDirectory` 这个**可观察状态**。在选择器内关掉「记忆操作路径」
        开关 → key 变化 → 协程重启 → `currentDir` 重置回内部存储根并重新 fork su 列目录，
        刚浏览到的目录与滚动位置全部丢失。
  - [x] 修法：key 只用 `Unit`，`initialDirectory` 仅作首帧初值

- [x] **P1 · 崩溃：窄容器下明暗条 thumb 计算抛异常**
  - [x] `thumbX = (…).coerceIn(0f, widthPx - thumbDiameterPx)`：`Float.coerceIn` 在
        `min > max` 时抛 `IllegalArgumentException`。容器宽度小于 thumb 直径（分屏小窗 /
        折叠屏外屏 / 极端显示缩放）即触发。同文件色相条那处早有 `coerceAtLeast` 保护，
        明暗条这处没有。
  - [x] 修法：先 `coerceAtLeast(0f)` 得出可用宽度再钳位

- [x] **P1 · 可用性：权限开关触摸目标低于工程硬约束**
  - [x] 9 个权限开关固定 `size(40.dp)`，而工程约定（`CONTRIBUTING`「UI 形态」）要求行高
        统一 `heightIn(min = 48.dp)`，同工程 `AuroraArrowPreference` 正为此。
        3×3 密集排布下 40dp 误触代价偏高 —— 把文件从 644 误点成 777 是不可逆的越权。
  - [x] 修法：视觉方块保持 40dp，外层 `heightIn/widthIn(min = 48.dp)` 撑开命中区

- [x] **P1 · 数据一致性：保存中预设按钮仍可点**
  - [x] 保存要 fork 多次 su、耗时数秒，期间三个输入框都传了 `enabled = !submitting`，
        唯独「root:root」「system:system」两个预设没传。用户看到输入框是旧值（禁用态），
        owner/group 却已被改成 root —— **落盘的和屏幕上显示的不是同一组值**。
  - [x] 修法：两个预设跟随 `submitting`

- [x] **被证伪的一条（如实记录，避免以后重犯）**
  - [x] 报告称「点『选择所有者/用户组』必崩」，因果链起点是
        `RootFileManager` 用 `stat -c '%A|%U|%G'` 取属主、而「GNU stat 的 `%U` 是数字 uid」。
  - [x] **真机实测推翻该前提**：Android toybox `stat 0.8.0` 的 `%U|%G` 返回**名字**
        （`root`、`u0_a216`），数字才是 `%u|%g`。于是 `current` 形如 `u0_a216`，
        `OwnerCandidates.withCurrent` 的 `value.toIntOrNull()` 为 null → **直接返回原列表、
        不追加合成项**，不产生重复 key。
  - [x] 补查：即便 `current` 是数字，合成项也只在「该 uid 不在列表里」时追加，
        而已安装应用条目的 `name` 本身就是 `uid.toString()`（标签另存字段），
        因此仍不会与合成项重 key。**结论：该崩溃在本机与常规环境下均不成立，不修。**
  - [x] 附带确认的真问题（影响小，未修）：`current` 是 `u0_a216` 这类名字而候选列表里
        应用条目的 `name` 是数字串，导致「当前项」高亮判定恒不成立 —— 用户看不出当前值对应哪一条。

- [x] **回归**：单元测试与 lint 全绿，Release 红线通过，APK 安装启动正常、`FATAL=0` / `ANR=0`；
      真机打开选择器确认路径行与列表内容一致（内部存储 + 实际目录项）
- [x] **回退验证**：把文件选择器的两条修复逐条回退，确认对应用例**确实变红**
      （回退必须列回退目录 / 记忆目录写入必须在守卫之后），随后恢复复跑全绿
- [x] **新增用例 12 条**：预览补偿不得写回提交取值 / 窄容器 thumb 不抛异常 /
      回退目录必须真的列内容 / 回退目录亦失效时的兜底 / 关闭记忆时不改写记忆目录 /
      回退分支必须列 resolved / 记忆写入在守卫之后 / 不得以可变初值为 key /
      预设按钮跟随提交中禁用 / 权限开关 ≥48dp

- [!] **核对后判定为误报、不可达或需改契约，未动**
  - 「特殊位前缀保留条件依赖 `mode.length == 4`」：确认成立（把八进制框改成单个数字
    「4」时点矩阵开关会丢掉前缀），但需要用户先输入一个非法中间态，且后果是权限位少设
    而非越权，留待与输入校验一起改
  - 「八进制与 owner 输入零即时校验」：确认成立 —— 粘贴带尾随空格或全角数字时整串被跳过、
    无 `isError` 提示，用户只觉「输入框卡住」；owner 无长度上限会把格式错误推迟成一次
    数秒的 root 往返。属可用性问题，安全侧仍由 `isValidPermissionMode` /
    `isValidOwnerOrGroup` + `escapeShellArg` 兜住
  - 「保存协程无 try/catch，异常时 `submitting` 永久为 true」：确认成立。
    但当前宿主 `FilePage` 用 `remember` 承载弹窗，旋转后整体重建会把该状态一起清掉，
    用户看到的是「弹窗消失、权限已落盘」而非「卡死」；补 `runCatching` 需同时决定
    取消语义如何回报，留待后续
  - 「`pointerInput(Unit)` 捕获组合期的 thumb 直径」「`remember {}` 未以 initialColor 为 key」：
    均确认成立但当前调用方不可达（宿主用 `remember`，配置变更直接关窗）
  - 「列表行高约 36dp 低于 48dp」「加载中无指示」「新建文件弹窗无 submitting 守卫」
    「返回键未禁用」「`fileFilter` 作为 remember key 不稳定」：均确认为真但影响有限，
    留待后续
  - 「符号链接在选择器中指向目标内容却显示链接路径、断链软链不可见」：确认成立，
    但属产品需求（是否要在选择器暴露软链），需先定 `FileItem` 是否加字段

### A65. 第十五轮全面 BUG 深挖（2026-10-03）

前十四轮的重心在安全策略、守卫安装、文件管理层，终端**文本处理链路**
（`AnsiParser` / `HyperCore` / `SparseLineIndex`，合计 815 行）此前只在早期零散修过。
三个文件报出 19 条候选，核对后**确认成立并已修 4 条**。

这一轮有个明显特征：**四条全是「静默出错」而非崩溃**。崩溃用户能看见、能上报；
这些缺陷用户只会看到内容不对 —— 错位的行、空白的行、裂开的 emoji、闪回的旧输出。
因此除了常规核验，这轮额外做了**回退验证**（见下）。

- [x] **产品范围**：`AnsiParser.kt`(447) / `HyperCore.kt`(160) / `SparseLineIndex.kt`(208)
      全量重读，交叉核对 `RootService` 的分块与收尾、`ChunkedFileReader`、
      `TerminalPage` 的滑动窗口与快照

- [x] **P0 · 数据一致性：中途短读会让稀疏索引静默跳过字节、行内容错位**
  - [x] `IndexedLineProvider.load` 的循环无条件 `ci++`，即假定每次 `getChunk(ci)`
        都覆盖完整的 `[ci*256KB, (ci+1)*256KB)`。而 `ChunkedDocument.getChunk`
        **只缓存满块** —— 非满块必然来自 reader 的短读。
  - [x] 短读可达路径已核实：`ChunkedFileReader.readRangeRoot` 用
        `minOf(startInBlock + count, raw.size)` 截断，root 路径 `dd` 读到被并发截断的
        文件即返回不足 `count` 的数组；非 root 路径 `RandomAccessFile.read` 契约本身
        允许短读。
  - [x] 后果：该块剩余字节**永久跳过** → 换行计数少计 → 后续行整体前移，
        而 `i - floor` 取到的是文件里更靠后的另一行，**行号仍然「看起来正确」**。
        用户复制该行拿到的就是错的数据。这是 A57「索引不完整不得返回伪有效结果」
        同一类故障的另一入口 —— A57 当时只给首块加了守卫，循环内没有。
  - [x] 修法：循环内补等价守卫 —— 非满块且未到文件末尾即判索引失效，返回空交上层重建

- [x] **P0 · 功能：超长行被截断时已读到的 4MB 全部丢弃**
  - [x] `truncated = true` 的前提就是 `newlines < need`（跳出循环的条件），
        而 `split('\n')` 最多产出 `newlines + 1` 段，`getOrNull(i - floor)` **必然为 null**，
        `?: ""` 于是把辛苦读到的内容全扔了。
  - [x] 用户侧表现：一行纯标注「…（本行超过 4MB，已截断显示）」，**一个字内容都没有**。
        A57 加截断标记时只想到「不能冒充完整行」，没意识到前缀也没保住。
  - [x] 修法：回退到 `parts.last()`，让已读到的真实内容显示出来

- [x] **P1 · 边界条件：代理对跨输出块被劈开**
  - [x] `feed()` 只跨块续接 `pendingEscape`，**没有任何字段承载孤立高代理**。
        而 `RootService` 用 `CharArray(1024)` 读 stdout 后整块 `String(buffer, 0, count)`
        入队，分块边界与码点无关 —— emoji、部分增补平面汉字的高低代理完全可能分处两次
        `feed()`。已核实 `RootService.kt:415-418`。
  - [x] 后果：孤立高代理落进 `curText` → 渲染成 U+FFFD 豆腐块且多占一列；
        快照进 `parsedOutput.lines` 后「复制输出」还会把残缺代理写进剪贴板。
        慢速输出（慢挂载的 `ls`、spinner）时必然发生。
  - [x] 修法：`feed` 入口续接 —— 下一块以低代理开头则拼回，否则按 Unicode 替换字符落地
        （与真实终端一致）；末尾高代理留到下一块；`finish()` 与 `reset()` 各自收尾

- [x] **P1 · 并发：发布循环的归属令牌在内存语义上不成立**
  - [x] `batchFlushJob` 是裸 `var`，而同文件的 `batchFlushEpoch` 明确加了 `@Volatile`
        并注明「跨线程可见」—— 同文件同用法，一有一无，是明确疏漏。
  - [x] 线程事实：`startBatchFlushLoop` 由 IO worker 调用（写），`stopBatchFlushLoop`
        在主线程调用（读）。缺 happens-before 边时主线程可能读到旧值（上一条命令的
        已结束 Job）或 null → `job !== owner` 判定成立 → **静默 return，循环根本没停** ——
        恰好是这个「归属令牌」设计要防的情况。旧循环继续 drain 队列并
        `appendOutputDirect`，与命令收尾的 `flushBatchQueueImmediate` 争抢同一队列。
  - [x] 同时给 `finally` 补上代次守卫：循环体用 `batchFlushEpoch` 丢弃清屏前积压，
        `finally` 却无守卫 —— 用户点「清屏」后循环恰好退出时，会把最多 250ms/400k 字符的
        清屏前内容整段回灌，屏幕闪回一批旧输出。与循环体注释描述的是同一类故障，
        只是漏了这条路径。
  - [x] 修法：`@Volatile` + `finally` 内比对 `batchFlushEpoch == seenEpoch`

- [x] **回归**：单元测试与 lint 全绿，Release 红线通过，APK 安装启动正常、
      `FATAL=0` / `ANR=0`，终端页渲染正常（待命中态与四个动作按钮齐全）
- [x] **回退验证（这轮的关键动作）**：三条行为类修复逐条临时回退，确认对应用例**确实变红**
      （代理对 2 条、短读 1 条、超长行 1 条），随后恢复并复跑全绿。
      源码断言类（`@Volatile`、代次守卫）无法用行为断言覆盖，改为直接读源码校验。
      —— 只跑「修复后全绿」无法区分「用例真的在守护」和「用例根本没用例」，
      这条纪律从 A61 沿用至今。
- [x] **新增用例 8 条**：代理对切开必须续接 / 孤立高代理收尾 / 单块不受影响 /
      连续多块反复切开 / 中途短读不得返回错位行 / 超长行必须保留已读内容 /
      `batchFlushJob` 必须 volatile / `finally` 必须带代次守卫

- [!] **核对后判定为误报、不可达或需改契约，未动**
  - 「`snapshot()` 每帧 O(已完成行数) 重建整个列表」：现象成立，但属既有设计取舍
    （返回不可变快照），改成增量视图需动 `TerminalPage` 的 diff 策略，收益与风险不匹配
  - 「窗口裁剪引发 SGR 状态丢失 + 3.5× 重解析放大」：确认成立，但根治要新增
    `dropPrefix()` 并重写裁剪路径，属结构性改动；当前表现是「日志顶部若干行褪成默认色」，
    不丢内容
  - 「`writeSegment` 绕过 C0 过滤，ESC 本体进屏」：确认成立，但触发需要输出里出现
    `ESC` + C0 的组合（`cat` 二进制），且后果是多一个不可见占位列，非数据错误
  - 「冒号式 SGR（`38:2::r:g:b`）被当成 reset」：确认成立，但需目标 shell 工具链输出
    ECMA-48 子参数扩展色；修法涉及子参数拆分，改动面大于当前收益
  - 「`completed` 无上界，只靠外部 reset 收敛」：确认是隐式契约，但已核实当前全部
    写入 `outputLog` 的入口都走滑动窗口，暂不可达
  - 「陈旧索引返回 `""` 而上层无重建路径」：确认成立（`peek` 恒 null、`loadError` 不置位），
    但需要「索引建成后文件被外部改写」这一外部条件，且修法要动 `TextEditorDialog` 的
    索引生命周期，留待后续
  - 「`runCatching` 吞掉 `CancellationException`」：确认成立（取消 32MB 扫描会退到
    旧的分段累积路径），属既有通用写法，全仓多处同款，单点修不一致，留待统一

### A64. 第十四轮全面 BUG 深挖（2026-10-03）

前十三轮把策略引擎、执行链路、文件管理层都过了一遍，这轮转向**守卫安装器与外置资源层**
（`GuardModuleInstaller` 376 行 / `SyntaxPackStore` 360 行）——这两处此前只核对过打包一致性，
没有按维度通读。两个文件报出 19 条候选，逐条回代码核对后**确认成立并已修 4 条**，
其余为误报、不可达或需改契约。

- [x] **产品范围**：`GuardModuleInstaller.kt`(376) / `SyntaxPackStore.kt`(360) 全量重读，
      连带核对 `RootService.runCommandSync`、`GuardPathPolicy`、`SyntaxPackDialog`、
      守卫 `customize.sh` / `common.sh`、assets zip 条目清单

- [x] **P0 · 数据一致性：语法包更新失败会把原有语法删光**
  - [x] `importZip` 的回滚是 `written.forEach { it.delete() }`，而 `written` 里装的是
        **本次已写入的文件** —— 更新语法包时这些文件本就存在（旧条目已在
        `filterNot { grammars.containsKey(p.id) }` 处被摘掉）。于是清单保存失败时，
        原本能正常高亮的语法文件被删除，而 `index.tsv` 仍指向它们。
  - [x] 用户侧表现：界面只提示「导入失败：…」，但**某些语言突然没有高亮**且再无提示
        （`AppFilesFileResolver.resolve` 返回 null → `loadLanguage` 只落一行 logcat）。
        数据不可逆丢失。
  - [x] 修法：写入前把被覆盖文件的原内容读入内存作为备份，回滚时**还原**而不是删除；
        只有本次新建的文件才删。内存量级受整包上限约束，不会无界增长。
        单文件导入 `ingest()` 的同类问题（文件已换新、清单 sha256 仍是旧的）一并修掉。

- [x] **P0 · 安全：zip 导入路径完全丢弃用户填的 SHA-256**
  - [x] `importFromUrl` 收到 `expectedSha256`，但内容是 zip 时直接
        `importZip(ctx, bytes, trimmed)` —— **形参根本没传下去**，一次都不比对。
        而「从仓库下载」按钮预填的正是 zip 直链。
  - [x] 比报告所述更严重：弹窗 summary 明写「支持 https…**可填 SHA-256 做完整性校验**」，
        且存在标签为「SHA-256（可选，填写则校验）」的输入框。用户填了、界面承诺校验、
        实际一次都没比 —— 这是一条**对用户的失效承诺**。
  - [x] 修法：zip 分支在 `importZip` 之前先对整包字节做一次摘要比对

- [x] **P0 · 安全：档位同步可能把守卫策略文件清空，且报告成功**
  - [x] 原脚本：`grep -v … > "$t" 2>/dev/null;`（**丢掉 grep 退出码**）
        接 `[ -s "$t" ] || : > "$t"`（主动保证临时文件为空）再 `cp "$t" "$f"`。
        `$f` 不可读 / `/data` 写满 / grep 缺失时，policy.conf 被**清空**，
        用户自定义的 `protect=` / `allow=` 全部丢失，脚本却仍以 0 退出并提示「同步成功」。
  - [x] 与该函数 KDoc 承诺的「只改写 `mode=` 行、其余用户自定义内容原样保留」正好相反。
  - [x] **真机验证时推翻了自己的第一版判据**：先写成 `g != 0` 即报错，实测发现
        `grep -v` 在**所有行都被过滤**时退出码是 **1**（输出为空），而这正是
        「全新安装后 policy.conf 里只有 mode 行」的正常状态 —— 那样判会把正常配置
        判成失败，档位从此同步不进去。只有 **≥2** 才是真错误（文件读不了/grep 缺失）。
        真机逐例实测：`only_mode` exit=1、`mixed` exit=0、`no_mode` exit=0。
  - [x] 修法：判据改为 `g >= 2` 并中止；覆盖前先 `cp "$f" "$f.bak"` 留备份
  - [x] 真机复验：完整配置（mode + 自定义 protect/allow）→ 自定义行全部保留、mode 正确更新；
        只有 mode 行 → exit 0 且 mode 变 enforce；无 mode 行 → exit 0

- [x] **P1 · 功能：守卫安装校验失败时给出不实提示**
  - [x] 安装脚本成功路径末尾已 `rm -rf $oldDir`，随后才做事后校验；校验不过时的
        「回滚」脚本只能把**本次装的**新模块挪走删除 —— 旧版本此时并不存在，
        首次安装更是从来没有旧版本。文案却无条件写「已回滚到旧版本」。
  - [x] 危害：用户以为防护还在，实际守卫模块已被整体移除，之后所有 root 执行走
        「无守卫降级」分支（仅在终端打一行告警），直到下次冷启动才补装。
  - [x] 修法：按实际分支给文案 —— 覆盖安装说「已移除本次安装的版本」，
        首次安装说「守卫已移除，将在下次启动重装」

- [x] **回归**：单元测试与 lint 全绿，Release 红线通过，APK 安装启动正常且
      `FATAL=0` / `ANR=0`
- [x] **真机复核**：`policy.conf` 1747 字节 / 14 条 protect / 5 条 allow / `mode=enforce`
      完整无缺；守卫模块 v1.4.3；`GUARD_POLICY_MODE` 审计条目证明新代码的同步路径已实际执行
- [x] **新增用例 7 条**：回滚还原被覆盖内容 / 回滚只删本次新建 / 整包上限约束备份内存 /
      zip 路径必须比对 sha256 / zip 结构解析 / 策略同步不得清空（含 `g>=2` 判据）/
      安装文案不得声称恢复旧版本

- [!] **核对后判定为误报、不可达或需改契约，未动**
  - 「升级判定只判字符串相等会降级用户手装的高版本」：确认现象成立（无任何语义化比较），
    但改成「仅当 installed < bundled 才重装」属于行为变更，且用户手装新版后被 APK 回退
    是否算缺陷需产品决策，不适合在 bug 修复里单方面改
  - 「超时只杀 su、脚本继续改模块目录」：`runCommandSync` 的 `destroyForcibly()` 确实
    只作用于 su 本身，这是既有已知限制（影响所有 root 调用，不止守卫安装）；
    按进程组回收是跨模块改动，未纳入这轮
  - `uninstall()` 不持 `installMutex`：确认成立，但全仓 0 调用方，接上 UI 才成立
  - `status()` 把 `DISABLED` 当版本号：确认成立，需 `module.prop` 缺 `version=` 行
    且模块被停用同时发生，属窄路径
  - 「KDoc 称语法 JSON 不含可执行代码不成立」：结论正确（Monarch 的 tokenizer 正则会
    被引擎编译执行），但这是**文档表述问题**，改它需同步 `docs/安全相关逻辑清单.md`，
    留待后续
  - 「只校验输入 URL 的 scheme、重定向后不校验」：确认成立，但 Android `HttpURLConnection`
    默认不跨协议跟随重定向，实际不可达；且属加固项而非缺陷
  - `importZip` 内单条目上限比单文件导入宽 4 倍、zip 内同名文件静默覆盖、
    `index.json` 缺失仍报成功：均确认为真，但影响有限（整包 2MB 上限已兜底），
    留待后续

### A63. 第十三轮全面 BUG 深挖（2026-10-03）

前十二轮的重心在策略引擎与执行链路，文件管理层（`RootFileManager` 914 行 / `ExternalOpen`
517 行）只被零星提及。这一轮整块重读，两个文件报出 19 + 9 条候选；**逐条到代码里核对后，
确认成立并已修 6 条**，其余为误报或需改测试契约。确认成立率不到三分之一，所以下面只记
核实过的结论。

- [x] **产品范围**：`RootFileManager.kt`(914) / `ExternalOpen.kt`(517) 全量重读，
      连带核对 `RootService.escapeShellArg`、`PolicyEngine` 词法、`ArchiveExtractor` 预算闸门

- [x] **P0 · 安全：外部路径白名单不归一化，`..` 穿越直接过闸**
  - [x] `isExternalPathAllowed` 此前只做前缀比对，**从不解析 `..`、从不 canonicalize**。
        而 `Uri` 不会归一化 `..`，任意应用构造
        `file:///storage/emulated/0/../../data/adb/modules/x/service.sh` 就能以
        「`/storage/emulated/0/` 开头」命中允许前缀 → 放行，实际指向 `/data/adb/modules`；
        本应用私有目录的前缀黑名单同样能被 `..` 绕开。
  - [x] **今天靠下游偶然挡住**：`FilePage` 随后调 `statFilePath`，其 `isUnsafePath` 拒绝
        任何 `..` 段 → 用户只看到「路径不合法」。但本函数的契约是「这条路径已可信」，
        下游是否过滤属于实现细节 —— 换个调用方（安装/执行分派，或不经 `isUnsafePath`
        的读取）就不再成立。
  - [x] **既有测试完全没覆盖**：外部信任相关用例覆盖了 `/data/data/…`、`/data/adb/…`、
        前缀混淆 `/storage/emulated/01` 与 `/storage/emulated/0evil`，**唯独没有一条 `..` 用例**。
  - [x] **修法**：新增 `normalizeForContainment()` 做词法归一化（解析 `.`/`..`、折叠重复
        斜杠、去尾斜杠），前缀比对前先归一；无法判定（非绝对路径、含控制字符、含 `..`）
        一律 fail-closed。只做词法不做 `canonicalPath` —— 后者要 stat 每个路径，
        交互式唤起无法承受。
  - [x] **修复有效性直接对照**（同一 JVM 内跑新旧两版实现）：

        | 路径 | 旧 | 新 |
        |---|---|---|
        | `/storage/emulated/0/../../data/adb/modules/x/service.sh` | 放行 | **拒绝** |
        | `/storage/emulated/0/../data/data/com.other.app/files/token` | 放行 | **拒绝** |
        | `/storage/emulated/0/../../data/data/com.mixradio.droid/shared_prefs/a.xml` | 放行 | **拒绝** |
        | `/storage/emulated/0/Download/a.apk` | 放行 | 放行 |

- [x] **P0 · 安全：`resolveWritableTarget` 的越界检查是空实现**
  - [x] 注释写着「真实目标也必须仍在用户点选的路径之内，防止链到 /data 内的其它敏感位置」，
        `if` 条件也在，但**函数体只有两行注释** —— 该检查从未存在过。
        `isAllowedDataPath` 只能证明「解析后仍在 `/data` 下」，于是
        `/data/local/tmp/m → /data/adb/modules` 被放行，root 随后把 modules 目录
        chmod / 改属主，而确认弹窗显示的只是链接自身的路径。
  - [x] 补上实现：解析结果必须等于自身或落在自身之下（指向自己或子目录是正常用法，放行）

- [x] **P0 · 安全：同文件系统内的移动完全绕过策略与审计**
  - [x] `moveFile` 的 Java 兜底分支 `source.renameTo(dest)` 排在 `guardDestructiveOp`
        **之前** —— 同一文件系统内移动成功就直接返回，判定与审计都被跳过。
        同文件 `addFileToShso` 的 `autoDeleteSource` 已修过同一类问题，`moveFile` 漏了。
  - [x] 门禁上提到所有执行分支之前

- [x] **P1 · 数据一致性：移动已成功却报「移动失败」**
  - [x] `renameTo` 返回 true 后又要求 `localType(finalPath) == sourceType`，而目标常位于
        app 无权 stat 的挂载点（FUSE/sdcardfs）或本身是 socket/FIFO → 复核必然失败 →
        落到 root 分支再跑一次 `mv`（源已不在，报错）。**移动其实已经完成**。
  - [x] 危害链：用户看到失败会重试 → 第二次命中 OVERWRITE 分支 → 可能连带删掉目标同名文件。
  - [x] `renameTo` 返回 true 即视为成功

- [x] **P1 · 功能：点开头的文件名被改名**
  - [x] `File.nameWithoutExtension` 对 `.env` 返回**空串**（`lastIndexOf('.') == 0` →
        `substring(0, 0)`），`File.extension` 则返回 `env`。于是 `base + "_" + n + suffix`
        产出 `_0.env`，前导点丢失；「加入 shso」的独立目录名更退化成 `/data/adb/shso/_12345`。
  - [x] 同文件 `moveFile.renamedDestPath` 与 `ExternalOpen.reserveUniqueFile` 都用
        `dot > 0` 正确规避过 —— 是遗漏而非设计。
  - [x] 真机验证：`.env` 加入 shso 后目录名为 `.env_<戳>`（修复前是 `_<戳>`）

- [x] **P2 · 功能：目录拷贝只改顶层权限**
  - [x] `cp -r … && chmod 777 <顶层>` 缺 `-R`，子文件/子目录仍是源权限（如 0700），
        与「让其他应用也能自由读写其中的文件」的设计目的相悖 —— 第三方文件管理器照样读不到。
  - [x] 真机验证：源 `sub/` 与 `deep.txt` 均为 700，拷贝后 `deep.txt` 为 `rwxrwxrwx`

- [x] **回归**：单元测试与 lint 全绿，Release 红线通过，APK 安装启动正常且 `FATAL=0`
- [x] **新增用例 7 条**：`..` 穿越三类拒绝 / 归一化后合法路径仍放行 / 无法判定 fail-closed /
      点开头命名拆分 / 门禁时序（源码断言 `guardDestructiveOp` 早于 `renameTo`）/
      越界检查不得是空实现 / 目录必须用 `chmod -R`

- [!] **核对后判定为误报或需改测试契约，未动**
  - 「送审串未转义会导致漏拦」：动词在送审串与执行串里都是字面量，倾向偏严而非偏松，
    未找到可证实的漏拦路径
  - `renameTo`/`delete` 的空 catch：均最终返回 `false` + 文案，不构成「假成功」
  - `listFiles` 无条目上限、`moveFile` 最多 fork 8 次 su：性能问题，非这轮重点
  - `ExternalOpenHub.sequence` 非原子自增：当前不可达（唯一两个调用点都在主线程
    `LaunchedEffect` 内），记为隐患不记为缺陷
  - `uniqueFile` 非原子且生产无调用方：确认是死代码，但删除属清理动作，未纳入这轮
  - `isUnsafeFileName` 用子串判 `..`（会误杀 `a..b.txt`）：确认是真问题，但
    `RootFileManagerEscapingTest` 有一条用例把现状锁死，改语义需一并调整该契约，
    留待后续

### A62. 第十二轮全面 BUG 深挖（2026-10-03）

A61 修掉审计写入的软链 TOCTOU 后，这里回头复核它改过的每一处，外加上一次遗留的三条。
结论先说：**A61 自己引入了一个功能回归**，比它修掉的那个洞更影响日常使用 —— 已定位、
已修复、已用真实归档复现与验证。共修 4 项，其中 2 项在真机复现过。

- [x] **产品范围**：`ZipEntryCountProbe`（148 行）/ `SecurityAuditLog`（392 行）/
      `ArchiveExtractor` 预算闸门 / `ApkInstaller` XAPK 闸门 + 两处调用点 + 既有测试 480 行

- [x] **P0 · 功能回归：条目数预检把合法大归档误判为超限（A61 引入）**
  - [x] **根因是把上界当下界用**。中央目录每条 header 记录**至少** 46 字节，故
        `cdSize / 46` 满足 `N ≤ cdSize / 46` —— 它是条目数的**上界**。A61 为堵
        「自报字段被改小」的漏判，把 `max(自报值, cdSize / 46)` 当判定值，注释两处
        都写成「下界」（`ZipEntryCountProbe` 类注释与 `cdSize` 折算处各一处）。
  - [x] **误拒是必然的，不是边缘情况**。条目名几十字节时每条记录远大于 46 字节，
        折算值成倍放大。真机 JVM 实测（条目名 65 字节）：

        | 真实条目数 | 探针返回 | 上限 20000 |
        |---|---|---|
        | 5000 | 12367 | 放行 |
        | 9000 | 22280 | **误拒** |
        | 11000 | 27258 | **误拒** |
        | 15000 | 37258 | **误拒** |

        报错文案还写着「压缩包条目数超过 20000」，与事实相反 —— 用户看到的是一个
        只有 9000 个文件的合法包被告知条目数超限。1~3 万文件的大型 APK 很常见，
        等效真实上限被压到 8000 条上下。
  - [x] **改为零分配的结构遍历，精确计数**：逐条走中央目录，每条记录的下一条位置由
        其定长头里的三个长度字段（名长/extra 长/注释长）唯一确定，可从 `cdOffset`
        顺序跳到 `cdOffset + cdSize` 数出真实条数。**只读字节、不构造任何对象**，
        「预算前置」依然成立。既不漏判（完全不信自报字段），也不误判（给精确值）。
  - [x] **配套边界**：`cdOffset + cdSize` 越界、`cdSize > 32MB`、遍历中遇到非
        `0x02014b50` 签名、长度字段溢出 `cdEnd` —— 一律 fail-closed 按超限返回。
        ZIP64 的 `0xFFFFFFFF` 哨兵（偏移/体积）同样 fail-closed：真实值在扩展记录里，
        而自报值已被攻击者控制，此时回退它等于把预算交给对方。
  - [x] **解压侧二次闸门仍在**（`ArchiveExtractor` 的 `headers.size > 上限`），
        探针返回 null 时仍由它兜底，防线没有被削弱成单点
  - [x] **新增 5 条用例**：真实 ZIP 5000/9000/12000/15000 条目必须给出精确值且不超限；
        自报字段改写成 1 时仍数出真实 12000；上限提前返回；结构性损坏 fail-closed；
        ZIP64 哨兵 fail-closed

- [x] **P0 · 安全：清空审计可被软链劫持成 root 截断任意文件（A61 漏改的同一条链路）**
  - [x] **A61 只改了追加路径，截断路径原样保留**。`clear()` 仍是三次**独立** su：
        `prepareRootTarget()` 校验 → `sh -c '> path'` 截断 → `writeBytesAsRoot(append=true)`
        写标记。校验与「跟随软链的截断」分属两个进程，中间是几十毫秒窗口，而目标目录
        `/data/adb/shso` 是 0777 且**无 sticky 位**。
  - [x] **真机复现（步骤与 app 代码逐条对应）**：三次调用**全部返回 0**，受害文件
        `/data/local/tmp/victim.txt` 的 `ORIGINAL-CONTENT-LONG-ENOUGH` 被**完全抹掉**，
        换成审计标记行。截断比追加更危险 —— 追加只多一行，截断毁掉目标全部内容；
        落点若是 Magisk 模块的 `post-fs-data.sh` / `service.sh` 即等于下次开机的 root 代码执行。
  - [x] **利用前提如实记录**：本机 SELinux 拒绝第三方应用访问该目录（实测见下），
        因此**这不是「任意应用可利用」**。但代码注释本身写着 0777 是为了「供第三方文件
        文件管理器互访」—— 一旦某个 ROM / Magisk 策略真的放开了这条路径，攻击即成立。
        真正的边界是 SELinux 而不是代码，这本身就说明代码不该留这个窗口。
  - [x] **修法**：`clear()` 改用与 `log()` 同一个 `appendRootLogLine(truncate = true)` ——
        校验、`exec 3>` 打开、写入全在同一个 root shell 内，打开紧跟校验，中间无可插入窗口。
        `prepareRootTarget()` 随之失去唯一调用者，已删除（它正是这个 TOCTOU 的来源）
  - [x] **真机复验**：同一攻击序列下受害文件内容保持不变；正常路径 5 行 → 1 行标记，
        权限保持 `-rw-------`

- [x] **P1 · 数据一致性：审计日志里的中文全部损坏**
  - [x] **现象**：真机打开审计弹窗，`(档位=3)` 显示为 `(╗╗µı╱ⅡΛ=3)`。字节级确认
        落盘为 `EF BF A6 EF BE A1 EF BE A3 EF BF A4 EF BE BD EF BE 8D` —— 每个字节
        被映射成 `U+FF00 + byte`。
  - [x] **根因**：`appendRootLogLine` 先把行编成 UTF-8 字节，再逐字节
        `b.toInt().toChar()` 拼回字符串（等价于按 ISO-8859-1 重解释每个字节），
        随后 `ProcessBuilder` 把这个字符串按 UTF-8 **二次编码**。本地路径用
        `appendText(line, UTF_8)` 不受影响，所以只有 root 路径的中文会坏。
  - [x] **危害不止观感**：审计日志是事后追溯依据，字段损坏等于让证据不可用；本项目
        又恰好大量涉及中文路径与中文档位名（`GUARD_POLICY_MODE` 的摘要里就带「档位」）
  - [x] **修法**：只转义单引号，字符串原样交给 `ProcessBuilder` 按 UTF-8 编码
  - [x] **真机复验**：同一事件重跑，落盘字节为 `E6 A1 A3 E4 BD 8D`（= 「档位」的
        正确 UTF-8），弹窗显示「档位=3」
  - [x] **顺带补回换行**：改为字符串拼接后原先 payload 里的 `\n` 没了，
        `printf '%s'` 改成 `printf '%s\n'`，否则整份日志会挤成一行

- [x] **P2 · 异常处理：轮转失败对用户完全不可见**
  - [x] `trimRootLog()` 与 `trimLocalLog()` 的 `catch (_: Exception) {}` 是空吞。
        这与 A61 P0-3 建立的「审计降级必须可见」不变式直接冲突：日志在无上限增长的
        同时对用户看起来一切正常。补 `recordFailure`，并把 `wc -c` 的 exit 与
        「大小不可解析」也各自记一次

- [x] **一处代码卫生问题**：`SecurityAuditLog` 里有两段相邻的 KDoc，第一段
      （原 `prepareRootTarget` 的说明）被第二段顶掉成了**悬空注释**，谁也不解释；
      `prepareRootTarget` 自己反而没有文档。删除该函数后一并消失

- [x] **真机验证**：APK 安装启动正常，`FATAL=0`；审计弹窗显示真实日志且中文正常；
      守卫 v1.4.3 仍安装；档位 3。共修 4 项，其中 2 项在真机复现过
- [x] **回归**：单元测试与 lint 全绿，Release 2.20 MB 且 `verifyReleasePayload` 红线通过

- [!] **未修**
  - `PathClassifier` 对 `$'/system'` 这类 ANSI-C 引用只判 WARNING（`startsWith("$")`
    兜住了，但没按未解析目标升级）。脚本来源下需守卫兜底才能拦，而守卫目录实测
    **没有 `sh` 包装器** → 这条路径实际是敞开的
  - `AuditVerdict.CONFIRMED` 在终端链路仍未被消费；`PARSER_OVERFLOW` 交互态仍可确认
  - `ExecutionForegroundService.startAsForeground()` 无 try/catch，
    `ForegroundServiceStartNotAllowedException` 会在 `onStartCommand` 里抛（调用点
    的 try 只包住 `startForegroundService()` 本身），后台启动受限场景仍是崩溃路径
  - 审计并发：多条 `log()` 的 `printf` 各自一次 write，O_APPEND 下大概率不交错，
    但超过 PIPE_BUF 的行理论上可被拆成多次 write；轮转与追加之间会丢在途记录，
    已在 `trimRootLog` 的注释里写明取舍（每 24 条才轮转一次，窗口仅一次重定向）
  - `readTail` 本地路径 `readLines()` 全量载入（root 路径走 `tail -n`）；
    文件被本地裁剪限制在 512KB，暂可接受

### A61. 第十一轮全面 BUG 深挖（2026-10-03）

前十一轮已把所有源文件读过一遍。A61 攻**安全审计与网关层**（此前仅零散提及、从未整体审过）
并继续落地 backlog。

- [x] **审计范围**：`SecurityAuditLog`（345 行）/ `PathClassifier`（110）/
      `RootCommandGateway`（71）/ `ExecutionForegroundService`（175）/ `ZipEntryCountProbe`（123），
      共约 824 行 + 全部调用点

- [x] **安全性（2 项 P0）**
  - [x] **审计写入存在 TOCTOU → root 任意文件追加写**。「校验目标是不是普通文件」与
        「实际写入」此前是**两个独立的 su 进程**：第 1 次判软链/属主/权限，第 2 次执行
        `cat >> <path>`。而目标目录 `/data/adb/shso` 是 0777 且**无 sticky 位**
        （可写性是硬性要求，`RootFileManager` 里显式 `chmod 777`），两次 su 之间有几十毫秒
        窗口：任意第三方应用在此期间 `unlink(audit.log)` +
        `symlink(audit.log, <某模块>/post-fs-data.sh)`，第 2 个 su 的 `>>` 跟随软链 →
        **以 root 把审计行追加进引导期脚本** = 下次开机的 root 代码执行。
        `clear()` 的 `sh -c '> …'` 同理，且是**截断**。
        现合并为单个 su 脚本：判软链/非常规（失败 `exit 9`）→ 建档 → **打开前最后一刻再判一次**
        → 同进程内 `exec 3>>file` 打开并 `printf` 写入。打开动作紧跟校验，中间无可插入窗口。
        真机实测：把 `audit.log` 换成指向任意 root 文件的软链后跑该脚本，
        **退出码 9、受害者文件内容保持原样未被写入**，fail-closed 生效
  - [x] **条目数预算探针只信 EOCD 的自报字段 → 对它唯一要防的攻击形态完全失效**。
        自报「总条目数」是**攻击者可控**的：中央目录实际写 100 万条 header 记录、该字段写 1，
        预检得到 1 → 放行；而 zip4j 的 `HeaderReader` 是**从中央目录起点逐条扫到 EOCD 签名为止**、
        不以自报计数为上界 → 100 万个 `FileHeader` 一次性进堆 → OOM。
        注释声称的「取所有自洽候选取最大」只在「候选数」维度保守，对「自报字段本身」零防御。
        现同时解析 EOCD 的 `cdSize`（偏移 12），按每条中央目录记录最小 46 字节折算出
        条目数下界，与自报值**取大者**。只信体积也不对（自报值可被改大触发误拒），故两者取大

- [x] **审计失效的可见性（1 项 P0）**
  - [x] **`failureSummary()` 定义了但全仓零消费方 → 审计链可无声降级为「无审计运行」**。
        全仓 grep 确认它只在自身文件里出现 4 次（定义 + 自增），设置页从未调用。
        四类失败逐一对齐：①日志不可写 → `appendText` 抛异常 → 计数；
        ②root 侧目标被换成目录 → 计数后放弃写入；③磁盘满 → 写一半抛异常 → 计数；
        ④轮转失败 → 计数，且异常分支是**空 catch 连计数都没有**。
        四种情况下越权放行事件照常发生，终端无提示、设置页无红字、日志里也没有任何记录 ——
        事后完全无法回答「这台设备当时有没有记过」。
        现两处常驻显示：安全组里挂一行红色警示（不必点进弹窗也能看到），
        审计弹窗顶部再重复一次并说明「越权放行事件也可能未被记录」

- [x] **已核实为不成立 / A61 未修（如实记录，避免下轮重复排查）**
  - [x] **日志注入（伪造独立审计记录）：已闭合**。`sanitizeField` 转义 `|`/`\n`、
        丢弃 `\r`、转义全部 `<0x20` 控制字符；`formatLine` 中唯一自由文本字段
        `command` 与 `ruleId` 都过该函数，其余是枚举/整数/哈希 → 命令里的换行
        无法造出第二条记录
  - [x] **轮转与追加互踩**：真实存在（锁只护计数器不护写，本地侧 `writeBytes` 截断会抹掉
        期间追加的记录，root 侧 `mv` 让在途记录写进已 unlink 的旧 inode），
        但需累积 512KB 才触发且修复涉及写入路径重构，风险高于 A61 其它项，**未修**，
        已记入下方待办
  - [x] `PARSER_OVERFLOW` 在交互态被降级为可确认而非硬拦、`AuditVerdict.CONFIRMED`
        在终端链路零消费、`ForegroundServiceStartNotAllowedException` 被空 catch 吞、
        `PathClassifier` 对 `$'…'` ANSI-C 引用降级为 WARNING、`recordFailure` 把
        「su 被拒」误记成「疑似软链」、每条审计 fork 5~6 次进程、`readTail` 全量加载 512KB
        —— 均**确认成立但A61 未修**，已记入下方待办

  - 第一次验证 P0-1 时，我用**裸 `su -c "printf ... >> audit.log"`** 去复现，
    结果自然显示「受害者文件被写入」—— 但那只证明了**内核的 `>>` 会跟随软链**
    （任何程序都拦不住），并没有验证应用的写入路径，属于**用错了验证方式**。
    第二次改为把 `appendRootLogLine` 的脚本**原样**抽到设备上执行，才拿到可信的
    「退出码 9、受害者文件未被写入」。**验证修复必须复现被修的那条代码路径**，
    不能用一个「行为相近」的替代路径代替

      `build_apk.py` 红线通过。新增 3 项 EOCD `cdSize` 用例（自报改小/自报改大/体积为 0）
- [x] **真机验证**：APK 安装冷启动 `FATAL=0`；TOCTOU 修复按上述脚本实测（exit 9、
      受害者文件未动、日志行数恢复）；审计日志已还原；探针已清理

### A60. 第十轮全面 BUG 深挖（2026-10-03）

前十轮已把所有源文件读过一遍。A60 不再重复扫描，改为**集中落地累积 backlog 里危害最高的项**，
并对上一轮列为「待办」的两条争议项做真机复核。

- [x] **安全性（3 项）**
  - [x] **终端 `sh <任意文件>` 完全绕过脚本内容审查**。`SHELL_PROGRAMS` 此前只用在
        「管道终点」检测，`evaluateAtom` 的 when 无对应分支 → `sh /sdcard/p.dat` 零 finding。
        而脚本内容审查只在「执行文件」链路且后缀为 `.sh` 时才调用：把含
        `rm -rf /system`、`curl …|sh`、`python3 -c …` 的文件命名成 `.dat`，再在终端手输
        `sh <该文件>`，同一份内容走文件页会被拦（高档位直接拒绝自动执行）、走终端一路放行。
        现新增 `evaluateShellFileExecution`，脚本来源下按 `obfuscationLevel` 收敛到 CRITICAL；
        `sh` / `sh -x`（交互式读 stdin）不误报
  - [x] **`su`/`nice`/`setsid`/`time` 等不在 wrapper 名单 → 整条命令零规则**。它们都是
        「包一层再执行」的前缀，此前 program 就是它们本身，when 无分支 → Allow。
        `su`/`setsid` 还会重建环境（PATH 重置），守卫 PATH 兜底不成立
  - [x] **`su -c` 内层不展开**：即使把 `su` 加进 `WRAPPER_PREFIXES`，`-c` 未登记为取值选项，
        内层选项循环会把它当普通 flag 跳过，随后走「剥完没东西了」分支直接返回 `su`，
        内层永远不展开。现为 `su` 登记 `-c`/`--command`/`-g`/`-p`/`-s` 等取值选项，
        并为新增的 `nice`/`setsid`/`time`/`chrt`/`taskset`/`ionice`/`unbuffer`/`watch`
        补上各自的取值选项表

- [x] **数据丢失（1 项 P0）**
  - [x] **单个非法字节即让整个文件被重解释为 GB18030 → 保存后原始字节不可逆损坏**。
        `isStrictUtf8` 是「解码再编码与原字节一致」的严格往返，一个非法字节就让它失败，
        而失败后**无条件**落 GB18030。于是「基本是 ASCII、中间夹 1 个非法字节」的文件
        （日志里混进一行 GB18030 中文、日志被截断在多字节字符中间）整个被按 GB18030 解读：
        原本合法的 UTF-8 中文全变乱码、非法字节变 U+FFFD，用户看不出异常（只是满屏乱码），
        改一个字点保存就按 GB18030 全量重写 → 原始字节彻底替换、不可撤销。
        现先判「这到底是不是 GB18030」：GB18030 覆盖全部 Unicode 码位，真正的 GB18030 文本
        不该含 U+FFFD，密度超 1% 即不认它 → 落 ISO-8859-1（对任意字节序列一一映射，
        往返无损，至少不破坏用户数据）。真 GB18030 中文仍被正确识别

- [x] **误判方向修正（1 项）**
  - [x] **GBK/GB18030 中文脚本被误判为加密载荷而拒绝自动执行**。`looksEncrypted` 把
        U+FFFD 与 NUL 同权计入「二进制字符」，≥5% 即判加密；而两条读取链路都按 UTF-8 解码，
        GBK 脚本的中文注释会产出**大量** U+FFFD → CRITICAL `OBFUSCATED_PAYLOAD`，
        提示写「疑似加密/编码混淆」与真实原因（编码不符）不符，且少写几行注释又不命中
        → 判定随内容长度跳变。现 NUL 仍是二进制的硬特征（≥5%），
        U+FFFD 单列且阈值提到 40%（真二进制接近 100%，GB18030 中文注释约 30% 上下）

- [x] **同处修掉的 fail-open 盲区**
  - [x] **base64 单行判据只看第一行 → 对脚本永久失效**。任何可执行脚本第一行是
        `#!/system/bin/sh`（长度 <2048），该判据恒不成立；把 base64 载荷放第 2 行即完全绕过
        —— 与注释宣称的「混淆载荷第一道拦截」覆盖面不符。现跳过 shebang 后判定载荷行

- [x] **复核上一轮列为「待办」的两条争议项：均为误报，不予改动**
  - [x] **「`mv -t` 跳过源判定」不成立**。真机实测（真实包装器 + 真实 PATH）：
        `mv -t /system/bin src.txt` 与 `mv /system/bin/x -t <dir>` **均 exit=1 已拦截**，
        正常 `mv src.txt <dir>/` 放行。源码 540–553 行也确实把源收集进 `_paths`
  - [x] **「`ln` 硬链接搬运受保护 inode」不成立**。实测 `ln <src> /system/bin/x` 被守卫拦截
        （exit=1，命中受保护路径）；另一条 `ln /system/bin/ls <dir>/hl` 的 exit=1 来自内核
        `Cross-device link`（`/system` 与 `/data` 不同文件系统），**不是守卫判定**。
        且 `cp /system/bin/ls <dir>/` 放行是**正确**行为 —— 读受保护文件并非破坏操作
  - 仅靠静态阅读把这两条列为 P1，实测均不成立。**涉及守卫层的结论必须真机复现**，
    且要分清「守卫拦截」与「内核/工具自身报错」—— 只看 exit code 会误判

      `build_apk.py` 红线通过。新增 `EncodingAndShellExecRegressionTest`（13 项）
- [x] **真机验证**：APK 安装冷启动 `FATAL=0`；探针已清理。编码探测的核心断言由 JVM 单测
      覆盖（设备上 `iconv` 不可用、`/tmp` 在 Android 10 不存在，无法在设备侧复算，
      不把「设备侧已验证」写进结论）

### A59. 第九轮全面 BUG 深挖（2026-10-03）

前八轮已把业务 Kotlin 与守卫 shell 走完。A59 攻**UI 基建层**（此前从未审过），
并把第七轮确认存在但未修的最高危项落地。

- [x] **审计范围**：`AuroraComponents` / `DockBar` / `ColorWheelDialog` / `AuroraGlass` /
      `SoraMonarchGrammars` / `SoraTextEditor`（合计约 1490 行）+ 全部调用点
      （`TextEditorDialog` / `TerminalPage` / `SyntaxPackDialog` / `MainActivity`）。
      该轮反编译了 Sora/Monarch 的 jar 与 Compose 源码来核实断言，未凭印象下结论

- [x] **安全性：解释器内联执行此前完全不被判定（1 项 P0）**
  - [x] `python3 -c "import shutil; shutil.rmtree('/system')"`、
        `perl -e 'system("dd if=/dev/zero of=/dev/block/by-name/boot")'`、
        `node -e "fs.rmSync('/system',{recursive:true})"` 全部**零 finding**。
        `detectInterpreterExecution` 只在 `containsDecoderMarker`（base64 / atob /
        xxd -r / openssl enc / `\x` / codecs.decode）命中时才报，而解码标记只是混淆执行的
        **一种**形态，最直白的载荷一个标记都不含。`evaluateAtom` 的 when 无解释器分支
        → `Verdict.Allow` → 档位 2 的自动执行链路放行，root 身份、无弹窗、无审计记录。
        而守卫 `guard/` 下**没有** python/perl/node 包装器（第八轮已实测，该轮再确认
        设备上 `python3` 本身就不存在）→ 静态层是唯一防线。
        改为：命中内联开关（`-c`/`-e`/`-r`/`-m`/`--command`/`--eval`/`--execute`
        及其 `=` 形态）即出 finding，规则 ID **沿用** `INTERPRETER_PAYLOAD`
        （不新造 ID，避免同一语义被拆成两个规则导致下游漏判）；
        脚本来源下按 `obfuscationLevel` 收敛到 CRITICAL 以拦停自动执行

- [x] **性能瓶颈：编辑器每敲一个字重建一次 Layout（1 项 P0）**
  - [x] `SoraTextEditor` 的 `update` 无条件调 `ed.setTextSize(fontSize.value)`，
        而 Sora 的 `setTextSize` → `setTextSizePx` 是**无条件**
        `requestLayoutIfNeeded(); createLayout(); invalidate()`（javap 核实）。
        本组件所在重组域会读 `textRevision`（统计行用它），于是每按键重跑 update：
        · 不换行：`LineBreakLayout` 重建时 `new SingleCharacterWidths(tabWidth)`，
          其构造器分配 `new float[65536]`（**256KB**）+ SparseArray
        · 换行：`WordwrapLayout` 全量重排所有可见行
        同一段代码里 `setWordwrap` 早已加了门闩，字号漏了。现按 `ed.textSizePx` 比对后再设

- [x] **静默错误结果（3 项）**
  - [x] **取色器把纯白压成 `#FCFCFC` 并落盘**。预览用的饱和/明度下限 `0.01f` 同时被当成取值
        下限：s=0 的纯白被算成 `1-1*0.01*1=0.99` → `#FCFCFC`。后果是「极光白」预设的
        选中框永远不亮（拿 `#FCFCFC` 与 `#FFFFFF` 比恒为 false），且点确定后落盘的是
        `#FCFCFC` —— 用户要的纯白被静默改写。真机 awk 复算对照：
        纯白 新 `#FFFFFF` / 旧 `#FFFFFC`，纯黑 新 `#000000` / 旧 `#030303`。
        现拆成 `previewColor`（绘制/hex 显示，带下限）与 `currentColor`（提交，无下限）
  - [x] **语法无法被清除**：`if (ed.editorLanguage !== editorLanguage && editorLanguage != null)`
        的 `!= null` 守卫使「`.py` 另存为 `.txt`」与「删除语法包」都因 null 而短路，
        同一个 `CodeEditor` 继续用旧语法与 Monarch 配色，界面却已是 `.txt` / 提示已删除。
        现去掉该守卫，null 时回落 `EmptyLanguage()` 并把基础配色无条件应用一次
  - [x] **取色映射与 thumb 绘制用了两套公式**：触摸是 `x/width → [0,360]`，
        thumb 画在 `hue/360*(width-28dp)`，两套差**半个 thumb 直径**
        （≈300dp 宽条约 17° 色相）—— 点哪不是哪。现共用同一套映射

- [x] **已核实为不成立 / 无需改动**（避免下轮重复排查）
  - 语法注册表的**应用层失效链路是通的**：导入/删除/单项启停/全部启停四条路径都
    `refresh()` → `onChanged()` → `invalidate()` + `syntaxRevision++`。
    真正漏的是**进程级注册表**：`GrammarRegistry`/`LanguageRegistry` 无移除 API，
    同 id 重新导入仍返回首次解析的旧 `Language`（清单 sha256 已更新、界面提示已导入，
    着色仍是旧的，只能重启进程）—— 真实存在但需改动 Monarch scope 命名策略，
    风险高于A59其他项，A59 未修，已记入下方待办
  - `controller.editor` 挂载/卸载**无可利用竞争**：`applyChanges()` 先跑
    `applier.applyChanges()`（factory 置 editor）再 `dispatchRememberObservers()`
    （onDispose 置 null），同帧内 onDispose 不可能覆盖新建的 view
  - RGB↔HSV 往返**无精度漂移**（16 个预设逐档验算字节级精确）；色相 360 与 0 等价
  - `AuroraComponents` / `AuroraGlass` / `DockBar` / `AuroraTokens` **无组合期 IO、
    无 Typeface 创建**（只用 `FontFamily.Monospace`，走 Compose 自带字体缓存）
  - `DockBar` **无手势冲突、无实质防抖缺口**（Pager 消费位移后 clickable 自动取消，
    `scroll{}` 互斥量天然串行化）
  - `String.format` 未指定 Locale 的本地化数字问题已在预设项加 `Locale.ROOT` 时一并修掉

      `build_apk.py` 红线通过（2.2 MB）。新增 `InterpreterInlineAndColorClampTest`（9 项）
      与 `HsvValueClampTest`（7 项）
- [x] **真机验证**：APK 安装冷启动 `FATAL=0`；用设备 awk 独立复算 HSV 转换，
      确认「取值不设下限」后纯白/纯黑精确、旧实现确实掉档；
      顺带确认设备上不存在 `python3`（呼应守卫无解释器包装器这一前提）

### A58. 第八轮全面 BUG 深挖（2026-10-03）

前七轮已把 Kotlin 侧走完。A58 首次进入**守卫模块的 shell 实现**（真正的运行时拦截层），
并把前七轮确认存在但未修的最高危项落地。

- [x] **审计范围**
  - `module/shso_guard/guard/common.sh`（682 行，核心判定 + 审计日志）、`guard-template.sh`、
    `service.sh`、`customize.sh`、`uninstall.sh`、`gen_wrappers.py`、`guard/` 下 33 个包装器（只看模式）
  - 前提已实测：`guard/` 下**没有** `curl`/`sh`/`cat`/`python` 包装器（调用返回 exit=127），
    故「下载后直接执行」「解释器内联执行」这类形态**完全依赖 Kotlin 静态判定**，
    守卫层不是兜底 → 静态层与守卫层任何一处 fail-open 都直接等于越权

- [x] **守卫层 fail-open（2 项 P0，均已实证闭合）**
  - [x] **`find` 只收集绝对路径 → 相对起始路径的销毁命令零判定放行**。
        `for _a in "$@"; case "$_a" in /*) _paths="$_paths $_a"` 只收绝对路径，
        于是 `cd /data/adb/shso && find . -delete`、`find .. -delete`、
        `find /sdcard /data -delete` 中受保护的那个、`find -delete`（POSIX find 无路径时默认 `.`）
        的 `_paths` 全为空 → 末尾 `for _p in $_paths` 一次都不进 → `_verdict` 停在 `ALLOW`。
        `cd / && find . -delete` 是能删掉 `/system` 内容的形态。
        修法：销毁型 find 额外收集「取值型选项之外的相对操作数」→ `FIND_RELATIVE_OPERAND`；
        完全没有起始路径 → `FIND_NO_OPERAND`。取值型选项（`-name`/`-type`/`-maxdepth` 等 30 个）
        的下一个 token 必须跳过，否则 `find -name keepme /sdcard -delete` 会被误伤。
        新增 `_find_destruct` 把「是否含破坏性动作」带到函数末尾的判定处
  - [x] **`normalize_path` 只解析第一处符号链接 → 尾段里的后续软链不解析**。
        逐级扫描定位**第一处**软链后 `realpath "$_np_acc"`，再把剩余分量原样拼回；
        而尾段是在解析之后才被拼上去的，其中的软链不再解析。
        绕过形态：`/data/local/tmp/a` → `/system`，`/system/x` 又是软链指向 `/data/adb/modules`，
        则 `rm /data/local/tmp/a/x/y` 归一化成 `/system/x/y`（不在保护清单）
        而真实目标 `/data/adb/modules/y` 在清单里。
        修法：词法收尾后**复查**结果里是否仍有软链，有则再 `realpath` 一次；
        无 `realpath` 且仍存在软链则按不可判定拒绝（fail-closed）。

- [x] **已核实为 fail-closed、不予改动的点**（避免下轮重复排查）
  - `load_policy` 三处回退方向正确（文件缺失→内置兜底+`enforce`；`mode` 非法→`enforce`；
    `common.sh` 缺失→直接拒绝）
  - 策略文件并发截断只会变严不会变松（内置兜底清单恒定在前）
  - `--`、`-t DIR`/`-tDIR`/`--target-directory=`、`of=` 三种空格变体、
    `sed -ni`/`-i.bak`/`--in-place`、`eraseInLine`、递归深度超限 → 均正确 DENY
  - 含空格/换行/制表符的路径经分词后只可能多出 token，方向偏 fail-closed
  - `realpath` 缺失或软链悬空 → `_NORM_PATH` 置空 → `PATH_UNRESOLVABLE` DENY
  - `mv -t` 只判目标目录不判源、`ln` 硬链接搬运受保护 inode：真实存在但 A58 未修，
    已记入下方待办

- [x] **真机验证（全部用真实包装器 + 真实 PATH，非 source 内部函数）**
  - 隔离两级软链（策略只 `protect=/data/adb/modules`，排除 `protect=/data` 的干扰）：
    **修复前 `exit=0` 放行**（归一化停在 `/data/local/tmp/fb/inner/deep`），
    **修复后 `exit=1` 拦截**（归一化正确为 `/data/adb/modules`）；`/data/adb/modules` 目录仍在
  - `find` 矩阵：`.`/`..`/无起始路径/`-name a.txt -delete`/`/data -delete`/`/system -delete`
    全部 `exit=1` 已拦截；`/sdcard -name a.txt`、`. -name a.txt`、
    `/sdcard -maxdepth 2 -name x`、`/data -name a.txt` 全部 `exit=0` 正常放行（无误伤）
  - 由**新 APK 自动重装**的 v1.4.3（`GUARD_INSTALL` 审计日志佐证，非手工替换）复测同一矩阵，
    结果一致
  - 打包产物核对：`assets/shso_guard.zip` 内 `guard/common.sh` 含两处修复标记、
    `module.prop` 为 `v1.4.3`、`sh -n` 通过
  - APK 冷启动 `FATAL=0`；探针文件已清理
  - 插桩副本用 PowerShell `WriteAllLines` 生成会得到 **CRLF**，mksh 在 `case ... in` 处直接
    语法报错（`unexpected 'in'`），一度误判为源码被改坏；改用 `WriteAllText` + 显式 `\n`
  - `[IO.File]::ReadAllLines` **不继承** PowerShell 的 `Set-Location`，相对路径会解析到错误目录；
    `git show > file` 会被 PowerShell 重新编码破坏字节，必须走 `cmd /c` 才字节级保真
  - 自建 harness（`source common.sh` 后直接调 `run_guard`）**不可信**：`enforce` 模式下 DENY 会
    `exit 1` 把脚本后续全部终止，看起来像「全部 ALLOW」。最终改用真实包装器 + 真实 PATH 才拿到
    可信结论

      `build_apk.py` 红线通过（2.2 MB）

### A57. 第七轮全面 BUG 深挖（2026-10-02）

前六轮已把 UI 层与执行引擎逐文件走完。A57 转攻**从未成组审过的决策层与数据层**
（静态策略/脚本审计/权限探测，以及编辑器历史/文本统计/稀疏行索引/分块读取），
并把前六轮明确推迟的最高危项落地。

- [x] **两路审计**
  - 数据层：`EditHistoryManager` / `TextStatistics` / `SparseLineIndex` / `ChunkedFileReader`
  - 决策层：`ScriptAuditor` / `PolicyEngine` / `SecurityModels` / `FileItem` / `PermissionChecker`
  - 两条链路的「前几轮报过但一直没修」的追问全部给出当前代码的确切结论
    （历史迁移是否无条件删 legacy、字节数是否整份拷贝、索引失效面、
    U+FFFD 是否当加密判据、管道是否只比相邻段、扩展名能否被双后缀绕过、root 探测有无去重）
  - **结论：扩展名无法被绕过** —— `extension`/`realExtension` 都取最后一段，
    `x.sh.txt`→不可执行、`x.apk.`→空、`X.APK`→仍可装，方向一律 fail-closed。
    真实缺陷只是**三套口径分裂**（`x.sh.1` 在安装/预览可识别、在执行/字体不可识别），
    A57 未定级为缺陷，留作后续口径收敛

- [x] **安全性：静态层是远程执行的唯一防线（3 项 P0 + 1 项 fail-open）**
  - [x] **重定向目标含变量时整条被丢弃 → root 覆写系统文件零 finding**。
        `cat /dev/urandom > $T/build.prop`（`T=/system`）：walk 把 `>` 切成独立 token，
        目标取下一个 token `$T/build.prop`，既非绝对路径**不被记录**、
        又被从 `words` 里**消费掉** → `hasUnresolvedVar`（只扫 words）也看不到那个 `$`
        → 整条 `Verdict.Allow`。真机实测守卫**没有 `cat`/`curl`/`sh` 包装器**，
        两层防护同时失明。现新增 `Atom.unresolvedRedirects` 通道 + `REDIRECT_UNRESOLVED` 规则，
        并把这些目标计入 `hasUnresolvedVar`
  - [x] **管道只比较相邻段 → 中间插一个 `cat` 即绕过**。
        `curl -fsSL url | cat | sh` 的两对相邻关系是 (curl→cat)、(cat→sh)：
        第一对因 `next` 不是 shell 而跳过，第二对的上游只有 `cat`（既非下载器也非解码器）
        → 零 finding，远程载荷以 root 执行。`tee`/`grep`/`nl`/`sed` 同理。
        现回溯到**管道起点**取全部上游原子
  - [x] **`REMOTE_PIPE_SHELL` 硬编码 DANGEROUS，与同函数 KDoc 承诺的 CRITICAL 相反**。
        KDoc 明写「脚本文件里出现则说明作者刻意隐藏载荷 → CRITICAL，自动执行链路直接拦截」，
        而 `ScriptAuditor` 只拦 CRITICAL → 档位 2/3 的「添加到 shso 后自动执行」
        实测放行远程 root 代码。现统一走 `obfuscationLevel(source)`，与 `ENCODED_PIPE_SHELL` 对齐
  - [x] 附带核实并**驳回**一条猜测：`bash <(curl …)` 的进程替换确实不可见，
        但它与「重定向目标含变量」同源，已由上述 `unresolvedRedirects` 一并覆盖为 `DANGEROUS`；
        未按 P0 定级（静态层不可能对该形态做 fail-closed 之外的更强判定，
        且守卫仍无 `sh` 包装器可依赖，故如实记为 DANGEROUS 而非夸大）

- [x] **数据丢失：编辑器历史（2 项 P0）**
  - [x] **一次解析失败就抹掉该文件的全部历史**。`readFile` 把解析异常（含 `OutOfMemoryError`）
        降级成 `emptyList()`，而 `addHistory` 随即 `HistoryMerge.apply(emptyList(), new)`
        → `writeFile(单条)` **覆写同一 key**。触发现实：单 key 上限 100 万字，
        `JSONArray(raw)` 需 3~5 倍瞬时内存，而编辑器已把 ≤32MB 文件载入内存；
        空闲自动快照（默认常开、无开关）一次 OOM 即把 20 条历史全部抹掉且不可恢复。
        现 `readFileOrNull` 区分「空」与「失败」，失败时**放弃本次写入**并把损坏原文另存备份
  - [x] **迁移失败仍删 legacy → 升级即丢全部旧历史**。`remove(KEY_LEGACY)` 写在
        `catch` **之外**的同一个 edit 块里：注释说「旧数据损坏直接丢弃」，实际照删不误。
        现改为事务外先解析，失败则把 legacy 改名存为 `edit_history.unmigrated` 保留；
        成功路径也补上单条 `MAX_CONTENT_CHARS` 截断与 `trimToBudget` 体积预算
        （旧版无上限，20 条 × 数十万字会落成数 MB 单 key，反过来推高下一次 OOM 概率）

- [x] **静默错误结果：大文件只读浏览（2 项 P0）**
  - [x] **索引扫描失败仍返回「1 行的有效索引」→ 整个 >32MB 文件空白**。
        root 通道单次读取失败（su 被拒/授权未确认/60s 超时且丢弃已读部分）让 `raw.isEmpty()`
        直接 `break`，`count` 停在半路甚至为 0，而 `getOrNull()` 不抛异常 → 照样返回
        `lines=1` 的索引；调用方随即 `chunkedLines = emptyList()` 把本来正确的首块行丢掉，
        UI 变成 totalLines=1 的单行空白、`loadError` 为空、无任何提示 ——
        用户以为文件是空的。现扫不全即返回 `null`，由上层用首块行兜底
  - [x] **首块短读时整块被跳过 → 1024 行窗口整体错位**。
        文件被外部截断后 `startOffset` 落在首块之外，`first.size <= skip` 时
        `out` 为空、`newlines=0`，而游标仍从 `firstChunk+1` 起步 → 首个稀疏点覆盖的
        1024 行累积区间整体前移，取到的是文件里更靠后的另一行，**行号看起来仍然正确**；
        短读还会被当成完整块缓存，错位在整个 LRU 生命周期内不自愈。
        现判为索引失效并返回空（由上层重建），且 `getChunk` 只缓存**满块**

      `build_apk.py` 红线通过（2.2 MB）。新增 `PolicyBypassPathTest`（11 项）
      锁死三条绕过路径，并**显式断言相对路径重定向与 fd 复制不得误报**
      —— 第一版把 `> out.txt` 也判成不可知写点，被既有测试当场抓到后收紧为
      「仅含变量/命令替换/进程替换才算不可知」
  - 安装冷启动无崩溃；`FATAL=0`
  - 实测守卫目录**无 `curl`/`sh`/`cat` 包装器**（调用返回 exit=127）
- [x] **八维度复审**：A22–A40 这批复审未操作手机，逐次只做静态走查与只读比对，无代码改动。
  其中两条结论有留存价值：外部路径排序缺失导致跨目录顺序错乱；`SafeArgsPathPolicy`
  在 `/data` 前缀下对 `..` 的判定与文档口径不一致 → 印证「静态层是远程管道唯一防线」。
  - 审计日志中的 `GUARD_POLICY_MODE` 间隔疑似热循环，**核实为误报**：
    那 4 条对应刚做的四次横竖屏旋转（`remember` 随 Activity 重建重置），
    停歇后无新增，不作为缺陷
  - 探针文件已清理

### A56. 第六轮全面 BUG 深挖（2026-10-02）

前五轮已把「逐文件重读」这条路走完。A56 专攻**两个体量最大却始终只做定点修补的文件**
（`TextEditorDialog` 2435 行、`FilePage` 2349 行），并对终端渲染链路整体复核。

- [x] **三路审计**
  - `TextEditorDialog.kt` 全文（2435 行）＋编辑器引擎、编码探测、行尾、编码器、
    历史管理器等全部依赖
  - `FilePage.kt` 全文（2349 行）＋ `RootFileManager` / `ExternalOpen` / `ApkInstaller` /
    Manifest（确认**未声明 `android:configChanges`**）
  - `AnsiParser.kt` + `TerminalPage.kt` 渲染与滚动链路
  - 第四路（推迟项复核）被工具中止，未执行；相关候选项未予定级

- [x] **数据丢失（编辑器，5 项）**
  - [x] **「另存为」在加载中把原文件覆盖成 0 字节**。`controller.text()` 在控件未挂载时
        返回空串，而空串与「真的空文件」不可区分；大文件走 root 通道要数秒，此间编辑器
        尚未进入组合、其 `DisposableEffect` 已把 `editor` 置 null → `syncSnapshot()`
        把已载入的缓冲内容整体替换成空串 → 此时点「另存为」并填**相同**路径，
        覆盖确认因 `newPath == currentFilePath` 被跳过 → 原子替换成 0 字节，提示「已保存」。
        现 `syncSnapshot` 未挂载即返回（并给 controller 加 `isAttached`），
        `performSaveAs` 补齐 `doSave` 同款的加载中/读取失败/`isSaving` 守卫
  - [x] **「保存并关闭」无修订号守卫 → 写入期间的输入静默蒸发**。关闭未保存弹窗后编辑器
        立刻恢复可编辑（`isSaving` 不禁任何输入），root 写 30MB 要数秒，期间敲的字
        `onChanged` 置脏，但收尾无条件 `dirty = false` 并关闭编辑器 —— 这批字符既没写盘
        也不留在编辑器，无任何提示。同路径还把 `contentValue.text` 放进 IO 块读，
        与主线程 `syncSnapshot()` 直接竞争。现补修订号守卫（有新修改则不关闭并提示），
        且文本/修订号/编码/行尾/BOM 全部在主线程取后作参数传入
  - [x] **换行风格与 BOM 开关不置脏 → 设置被「无改动」吞掉，之后整文件被改写**。
        设置面板已显示 LF，点保存却命中「无改动」；之后随便改一个字再保存，
        `LineEnding.apply` 就把**整个文件**行尾重写。现两项变更均置脏
  - [x] **目标填成已存在目录时报告「已保存」但一个字节没写**。`mv <tmp> <已存在目录>`
        是「移进该目录」语义，返回 0；`validateSaveAsPath` 只查 `..`、反斜杠与私有目录，
        不判「目标是目录」→ 用户填的路径没被写入，目录里多出隐藏临时文件。现先判目录
  - [x] `performSaveAs` 此前既不置位也不检查 `isSaving`，可与「保存」并发写同一路径，
        两者的清脏判定只看修订号，会双双清掉。现共用保存门

- [x] **批量操作不可撤销却可被旋屏截断（3 项）**
  - [x] **`batchScope` 就是页面级 scope**。注释自己写明「旋屏会取消正在跑的循环、
        提示与刷新永不执行」，代码却正是 `rememberCoroutineScope()`。全选 80 个文件批量删除，
        删到第 k 个时旋屏 → `finally` 里的 `withContext(Main)` 在已取消的 Job 上
        **根本不会执行块体**（以 CancellationException 恢复）→ 提示不弹、刷新不跑、
        标志永不复位，列表仍显示已删文件。现改用进程级 `SupervisorJob` scope，
        收尾 `finally` 加 `NonCancellable`，并逐项入口检查取消、如实报「被中断」
  - [x] **「批量进行中禁止切换目录」只覆盖 2/5 入口**。上级目录按钮、书签跳转、跳转路径
        三处直接写 `currentDirectory` 绕过守卫 → 删除继续落在用户看不见的旧目录。
        现三处全部收敛到 `navigateTo`
  - [x] **选中集不随名称筛选收敛 → 不可见文件被不可撤销删除**。多选 5 个 → 搜索把它们
        全筛掉（可见 0 个、「无匹配项」）→ 长按批量删除，确认框只报「选中的 5 个项目」，
        删的却是屏幕上不存在的 5 个文件。另一侧「全选」取筛选后子集，清掉筛选词后按钮
        文案又变回「全选」，再点一次不是取消而是并入全量。现筛选变化时把选中集裁剪到
        可见范围**并如实告知裁掉几个**（静默裁剪选择比静默删除文件可接受，但两者都不静默）
  - [x] 附带：批量入口未重置 `deleteTargetItem`，而取消路径只清 `batchDeletePaths`
        → 对文件 A 取消删除后，从批量入口弹出的却是**单文件**确认框、目标是陈旧的 A。
        现取消与批量入口双向清理

- [x] **终端渲染正确性（1 项 P0 + 3 项）**
  - [x] **回车覆盖把 emoji 劈成半个字符**。列号是 UTF-16 码元下标，而 emoji 占两个码元；
        `printf '😀\rX\n'` → 覆盖路径按单码元 `setCharAt` → 残留孤立低代理，
        显示成替换符，且 `plainText` 会把它带进剪贴板。现实触发是进度条/spinner
        收尾缩窄、`pv`/`pip` 末帧、docker 拉层回显。现落笔时按码点整对写入，
        跨边界的单码元写入会把所在代理对整对清成空格（不动列号，避免与退格/光标
        移动的真实终端语义冲突）；`ESC[K` 截断点也对齐到码元边界
  - [x] **旋转屏幕时两个 worker 并发写同一个解析器**。解析器是**进程级缓存**，
        而解析块是纯 CPU 无挂起点、取消打不断 → 旧 Activity 的 in-flight 块与新组合的
        首个 collect 在两个 Default worker 上同时 `append`/`add`，越界异常穿出
        `LaunchedEffect` 直接崩进程；即使不崩，旧协程把消费进度回写成较旧值
        → 重复行或整段丢行。现整块 `synchronized` + 代次守卫禁止过期协程写回
  - [x] **「清屏」清不掉的输出会回灌**。`clearOutput` 只清共享队列，而运行中的发布循环
        攒了最多 250ms/400k 字符在**协程局部** `StringBuilder` 里，下一个 ≤16ms 的 tick
        就把这批刚被清掉的旧输出整段刷回屏幕。现引入发布代次，「清屏」传导进循环即丢弃积压
  - [x] **中断/结束进程的兜底提示会写进下一条命令的日志**。3 秒后的提示与异常分支仍用裸
        `executionJob === targetJob`，而终端一次性命令这条路径上两侧恒为 null、判据恒真 →
        A 的提示落进 B 的日志，用户据此去点「结束进程」把 B 杀掉。现统一走 `stillOwnsExecution`

      `build_apk.py` 红线通过（2.2 MB）
  - 首页列出探针脚本；终端实跑含 emoji 的覆盖脚本，应用稳定
  - **有输出期间连续四次横竖屏切换**：`FATAL=0`、`ANR=0`、无 `IndexOutOfBounds`
    → 命中并发解析窗口的路径已闭合
  - 代理对修复用**真机 shell 实测字节**（`printf '\360\237\230\200 building...\rOK  \r'`）
    做单测，而非手写字面量 —— 见下方「方法论修正」
  - 探针文件与临时目录已全部清理
- [x] **方法论修正**
      代理对修复的首版**引入了新 bug 且被既有测试当场抓到**：
      `Character.highSurrogate(cp)` 对 BMP 字符返回 `D800 + (cp >> 10)`，
      把 ASCII 与中文全写成代理字符（10 个既有测试同时变红）——这说明
      「既有测试就是防线」这条底线必须守住，不能因为「改动很小」就跳过全量回归。
      改对之后又漏了「这一轮消费了几个码元」：写完整码点后下标只前进 1，
      下一轮把低代理当成独立码元再读一次，反手把高代理抹成空格，
      结果是「空格 + 孤立低代理」。**手写 `"😀\rX"` 字面量测不出后者**——
      编译器已把它合并成整码点，喂给解析器时根本不会出现「码元被拆开重读」的路径。
      只有喂**真机 `printf` 的原始字节**才暴露。故新增 `TerminalEmojiOverwriteDeviceByteTest`，
      以十六进制串还原真机输出。**结论：不要用手写字面量冒充「真机输入」**，
      涉及编码/字节边界的回归必须用真实采集的字节。

### A55. 第五轮全面 BUG 深挖（2026-10-02）

前四轮已把执行引擎、安全子系统、UI 状态层、数据服务层与安装解压逐文件读过。
A55 转攻**此前从未深审的区块** + 把前几轮明确推迟的 P1 重新定级。

- [x] **四路审计**（报 70+ 条，读代码复核后修 12 项，驳回 1 条）
  - `SettingsPage` / `SettingsPagePartials` / `AppSettings`（此前从未审）
  - `ApkInstaller` 已覆盖部分之外的安装链路（此前从未审）
  - `MainActivity` / `HomePage` / `ShsoApplication`（此前从未审）
  - `BuiltInFilePicker` / `FilePermissionDialog` / `SyntaxPackDialog` /
    `FileListViewSettings` / `InstallConfirmDialog`（此前从未审）
  - **驳回**：`OwnerPickerDialog` 重复 LazyColumn key 崩溃。合成项 name 为「当前所有者」、
    应用项 name 为应用名，key 分别是 `uid|当前所有者` 与 `uid|<应用名>`，不重复；
    读代码时误按 `Entry(value, uid, …)` 的实参顺序当成 name，未定级

- [x] **安全性（2 项）**
  - [x] **`content://` 的路径列可伪造 → root 越权读取与任意代码执行**。
        上一轮只对 `file://` 施加白名单，`content://` 采信 provider 返回的 `_data` 列，
        仅额外拒绝本进程私有目录。**但被授予的是 URI 本身，不是 provider 写进游标的字符串**
        —— 任意应用自建 provider 即可对任意 URI 返回 `/data/data/<别人>/files/x` 或
        `/data/adb/modules/x/service.sh`，而下游用 root 去 stat / 读取 / 执行（扩展名判为
        EXECUTE 时用户点一次确认即执行）。现 `content://` 与 `file://` 同受白名单约束，
        `/data/data/*`、`/data/user/*`、`/data/adb/*` 自然落在白名单外；
        合法分享（FileProvider / MediaStore / Downloads）真实路径都在共享存储内，不受影响
  - [x] **改权限三步无回滚 → 越权残留且界面报失败**。`chmod` → `chown` → `chgrp`
        依次执行，返回值只带最后一步的错误：文件当前 750，用户填了设备上不存在的用户组，
        则 chmod 已落盘、chgrp 失败，弹窗提示「保存失败」而磁盘上已是新权限。
        反向更危险：用户想把 777 收紧为 700，chgrp 失败后文件**仍是 777** 而用户以为已锁死。
        现 chown/chgrp 任一失败即回滚 chmod 到进弹窗时读到的原始 mode，并说明已执行/未执行部分

- [x] **fail-open（2 项，本项目最高危的一类）**
  - [x] **守卫部署失败后 UI 仍宣称「已开启防护」**。切档顺序是「先落盘 → 再确保守卫已安装 →
        再同步策略」，而 `ensureInstalled` 的 false 与 `syncPolicyMode` 的返回值**都被丢弃**，
        随后无条件弹出含「守卫 PATH」字样的提示。Magisk 未授权、`/data` 写满、策略同步超时，
        都会让档位停在 2/3 而守卫缺失。唯一全局降级信号 `reportGuardDegraded`
        只写终端输出、全局只报一次、且**完全不覆盖文件页**（`guardPrefix()` 用 `orEmpty()`
        把 null 吞成空串，文件页的 chmod/chown/mv/rm -rf 零审计裸跑）。
        现改为「落盘 → 同步 → 失败回滚到上一档并按上一档重同步」，且必须 Toast 原因；
        另加 `levelSyncInFlight` 挡连点
  - [x] **`syncPolicyMode` 无互斥 + 固定临时名 → `policy.conf` 留下两条 `mode=`**。
        切档行是唯一入口且无防抖，两个 `syncPolicyMode`（外加冷启动那次）会并发跑在
        同一个文件、同一个固定临时名 `policy.conf.shso.tmp` 上：A 的 `mv` 移走 tmp 后
        B 的 `mv` 因文件已不存在而**静默失败**（`2>/dev/null`），但两次 `echo mode=` 都成功，
        文件里留下两条 `mode=` 行；守卫只认最后一条，于是实际模式由 su 完成顺序决定，
        与 App 档位无关。现与 `install` 共用 `installMutex`，临时文件改 `mktemp` 独占

- [x] **功能逻辑（5 项）**
  - [x] **分包应用必然装不上**。安装确认弹窗把待装包暂存成
        `<cacheDir>/install-confirm/<uuid>.apk`（暂存是为了校验后不被替换），
        而兄弟分片的发现以**待装文件所在目录**为基准 —— 暂存目录里只有那一个 uuid 文件，
        列举结果恒为 1 个兄弟，套件被静默降级成单文件 `pm install`，
        分包应用 100% 报「缺少分包」。同文件非 ROOT 分支用的是 `item.path`（原始路径），
        说明这个基准差异本就是个笔误。现 `installApk` 增加原始路径参数：
        套件从原始目录发现，被点中的那一个仍用已校验的暂存副本安装，两者兼顾；
        命名约定的分片数也补上 `MAX_SPLITS` 上限（此前只有 manifest 分组有限制，
        一个几百个 APK 的目录能把 /data/local/tmp 写满）
  - [x] **含 OBB 的 XAPK 重复安装必然失败且错误信息为空白**。目标已存在时脚本
        `exit 18` 且**一个字节都没往 stdout/stderr 写**，界面渲染成「复制 OBB 失败: 」；
        该 XAPK 从此永久装不上，界面既不说明原因也没有自愈路径。现同身份视为幂等成功
        （期望的最终状态已成立），异身份给出点名道姓的可操作提示；脚本本身也 echo 原因
  - [x] **`copyObbAtomically` 的「目标已存在」检查与 `mv` 之间隔着整个 `cp`**。
        拷几百 MB 最长 300s，这段时间游戏自身/文件管理器可能已创建该文件，
        而 `mv` 是覆盖语义且返回 0 —— 静默覆盖他人数据，与注释承诺的「绝不覆盖」不符。
        现 `mv` 之前再探一次（两次检查之间只留一次 rename）
  - [x] **首页 shso 列表整段会话不可用**。冷启动时 `isRootGranted` 尚未确定，
        列举走非 root 口径去读 `/data/adb/shso`（应用 uid 无权遍历，返回空），
        而**没有任何机制让它在 root 转正后重读** → 首页一直显示「暂无可执行文件」，
        文案还把用户往「去文件页添加」的方向引导。现以 `rootGranted == true` 为
        `LaunchedEffect` 的 key，转正即重读
  - [x] **「独立存储」添加的文件在首页永远看不到**。`isSupportedExecutable` 两个分支都带
        `!isDirectory`，把目录一并滤掉 → `addFileToShso` 建出的 `<名>_<时间戳>/` 子目录不可见，
        子目录里的脚本也就不可见，而「进入子目录」「返回上级」全部成为不可达的死代码。
        现过滤条件放行目录
  - [x] 附带：`ensureShsoDir()` 的返回值此前被丢弃，创建失败与「目录为空」不可区分；
        现失败/读取失败给出真实原因而不是一律显示「暂无可执行文件」

- [x] **数据一致性（2 项）**
  - [x] **OBB 部分成功的回滚会删掉别人的文件**。`obbFiles=[A,B]`，A 落位成功、
        B 因空间不足失败 → 内层 `finally` 对 A 直接 `rm -f`。而从落位到此刻已过去一个
        最长 300s 的 `cp` 窗口，期间游戏自身/文件管理器/未走锁的旧版 shso 若按同规范名
        重建了该文件，这条无校验的 `rm` 就把它删了；外层带锁 + inode:size:mtime 校验的
        `removeOwnedObb` 随后 stat 失败成为空操作，保护形同虚设。现复用同一校验删除路径
  - [x] **执行确认弹窗对真实文件显示「0 B」「—」**。不在 shso 列表里的目标
        （从「文件」页选择器进来的 `/storage/...` 路径）直接构造一个 size=0/lastModified=0 的
        `FileItem`，而弹窗的大小与时间正取自它 —— 高风险确认弹窗的元数据说谎，
        档位 3 的风险判断依据失真。现对不在列表里的目标做真实 stat（`statFilePath` 是挂起函数，
        故改为状态 + 协程，不能塞进 `remember`）
  - [x] **内置文件选择器丢弃 `createEmptyFile` 返回值**。同名已存在、文件名含 `/`、
        目录不可写（`/system/app`、`/data/adb/shso`）三条失败路径全部丢弃，
        对话框已关闭、列表刷出原样内容、**零提示**。同一交互在文件页是有提示的，
        两处口径不一致。现失败就地把原因显示在新建对话框内并保留对话框

- [x] **并发 / 生命周期（3 项）**
  - [x] **首页刷新无世代守卫**。连点刷新并发 fork su，先发起的那次若后落地且以异常收尾，
        会把已成功的新结果清空；旧协程的 `finally` 还会提前把「正在读取目录…」擦掉。
        现加 `refreshGenRef` 世代守卫（与文件页同款），并于扫描中禁用刷新按钮
  - [x] **首页输入与确认流程用普通 `remember` 承载**。旋转 / 分屏 / 系统改字号都会重建
        Activity → 已输入的长路径被清空、执行确认弹窗无声消失、选择器浏览位置退回存储根目录。
        现改 `rememberSaveable`（文件页此前已就同类状态明确记录过「这就是数据丢失」）
  - [x] **「安装守卫模块」行无防重入**。`installingGuard` 此前是只写状态、从不参与渲染，
        连点两次会排进 `installMutex` 串行跑完整链路（每次一轮 `rm -rf` → `mv`），
        而行文案不变，用户以为没点上而继续点。现部署期间显示「正在部署…」并吞掉点击

      `build_apk.py` 红线通过（2.2 MB）
  - 首页同时列出子目录（📁 文件夹）与顶层脚本（26 B）→ 目录过滤已解除、root 转正后重读生效
  - `policy.conf` 的 `mode=` 行**恰好 1 条**、`protect=` 14 条完整、无残留临时文件
    → mktemp 改动未破坏策略文件；`guard/rm` 在 enforce 档下真实拦截生效
  - 探针文件已全部清理；`FATAL=0`
  - **一项未做到**：守卫部署失败回滚与改权限回滚需要「先制造失败」才能观测，
        设备侧无法稳定构造（需要断开 Magisk 授权或填入不存在的用户组后走 UI），
        该两条由代码路径复核 + 单测覆盖映射判定（`policyModeFor` / `requiresRuntimeGuard`）

### A54. 第四轮全面 BUG 深挖（2026-10-02）

前三轮已覆盖守卫、安装解压、编辑器、文件操作、外部唤起、执行引擎、安全子系统、UI 状态层、数据服务层。
A54 换视角，不再逐文件重读，改用**跨维度模式横扫** + 补齐前三轮明确推迟的项 + 首次深审生命周期层。

- [x] **四路审计**（共报 70+ 条，逐条读代码复核后修 18 项）
  - 跨维度模式横扫：`runCommandSync` 全量调用点 / shell 拼接 / `File` 与路径切分 /
    Compose 状态 / `catch` 与 `runCatching` / IO 边界 / JSON-TSV-偏好存储 / 原子与可见性
  - 生命周期与意图层：`MainActivity` / `ShsoApplication` / `HomePage` / `ExternalOpen` / Manifest
  - 终端渲染与执行收尾：`AnsiParser` / `TerminalPage` / `kill` / `restart` / `awaitRunPgid`
  - 批量操作与守卫安装窗口 / 权限弹窗 / 语法包读写
  - 对「此前已报未修」的 A~H / A~F 逐条给判定，结论多为**部分成立**（例如 `sendInterrupt`
    的静默失效被「中断」按钮的 `enabled` 挡住、`runCommandSync` 超时分支的
    `StringBuilder` 单写者不会抛异常），据实收窄而非照单全收

- [x] **安全性（6 项）**
  - [x] **导出 alias 无路径约束 → 跨应用私有数据越权读取**。`ExternalOpenActivity` exported
        且无 `android:permission`，而 `resolveToRealPath` 对 `file://` 直接取 path、
        对 `content://` 采信对方 provider 的 `_data` 列，全程无白名单。任意应用构造
        `file:///data/data/com.mixradio.droid/shared_prefs/xxx.xml` 即可让 shso **以 root 身份**
        stat 并把文本渲染上屏。现：外部 `file://` 须过白名单（共享存储 + `/data/local/tmp`），
        任何来源一律拒绝本进程私有目录
  - [x] 外部 Intent 不得改写持久化的「上次浏览目录」（`refresh()` 按 `pendingExternalDirectory` 跳过落盘，
        用后即清，不影响用户后续主动导航）
  - [x] `intent.extras` 裸 `get(EXTRA_STREAM)` 会先 unparcel 整个 Bundle，发送方塞入只有它自己
        APK 才有定义的自定义 Parcelable 即抛 `BadParcelableException`（singleTask 下每次分享都崩）。
        改为按类型取值 + 整段 `runCatching`
  - [x] **文本对比结果临时名可预测且落在 1777 目录** → root 跟随软链覆盖任意文件。
        `System.currentTimeMillis()` 可由 `/proc/uptime` 推算窗口，`writeBytesAsRoot` 是
        `cat > `（跟随软链）。改 UUID 命名 + 写后复核非软链
  - [x] 编辑器覆盖写临时名同样是 `nanoTime + Random(9000)`（ART 上即 CLOCK_MONOTONIC，
        9000 种取值），目标目录可能是 1777/777 → 改 UUID + 复核
  - [x] 改权限/属主/用户组**跟随符号链接**：`/data/local/tmp/m → /data/adb/modules` 能过
        `isAllowedDataPath`（校验的是链接自身），落盘却是 `chmod 777 /data/adb/modules`。
        现 `readlink -f` 解析后对真实目标重跑白名单

- [x] **数据丢失（5 项）**
  - [x] **批量重命名用裸 `mv` 覆盖未选中文件**：root 走 `mv`、非 root 走 `renameTo`（Linux 上同为
        rename(2) 覆盖），而返回值被丢弃 → 提示恒为「已批量重命名 N 个文件」。
        `rename` 增加目标存在性判定（已授权时走 root 通道），批量层统计真实成功/失败并列出失败项
  - [x] **批量删除无二次确认**（单文件有「此操作无法撤销」确认框），且跑在页面级 scope 上，
        旋屏即被取消 → 静默半完成、提示与刷新永不执行。改为复用确认框 + 不随组合销毁的 scope
        + `finally` 里无条件刷新
  - [x] 批量执行期间可切目录/被外部唤起改写目录，删除继续落在用户看不见的旧目录。统一
        `navigateTo` 入口，批量进行中拒绝切换
  - [x] 文本对比非 root 分支「就地截断直写」→ 目标只剩前缀，且软链被跟随改写真身。改原子写
  - [x] 对比结果重名探测依赖 `listFiles`，而它在 root 通道下对 stat 失败不作成败判断
        （su 被拒即返回空列表）→ 回落 `_0` 覆盖上一次结果。改为逐个 `test -e` 递增 + 探测上限

- [x] **并发（4 项）**
  - [x] **`executionJob === targetJob` 对终端命令恒真**（终端命令从不写 executionJob），
        于是「结束进程 / 重启终端」一条命令的收尾会清掉**期间新启动那条**的状态、停掉它的
        发布循环、覆盖它的横幅 —— 进程还在跑，顶栏却显示「待命中」，用户失去唯一出口。
        改为统一判据 `stillOwnsExecution`（脚本看 Job、终端看槽位令牌）
  - [x] `restartTerminal` 对**上一轮残留的 `runPgid`** 发 root `kill -9 -- -pgid`，而该 pid
        早已被内核回收、`buildProcessGroupKillCommand` 只校验「是组长且非本应用组」，
        pid 复用后照样通过 → 以 root 整组误杀无关进程组。现无活动实体时只复位不发信号，收尾清 `runPgid`
  - [x] `executeFile` 的 pgid 轮询挂在页面级 scope（不是 executionJob 子任务，下一轮 cancel 不到），
        迟到结果（尤其超时归零）会冲掉新任务的进程组 → 改按该轮 Job 自检
  - [x] 交互态 stdin 两次独立读全局 `processWriter`（收尾置 null 落在 write/flush 之间即丢 flush），
        且无互斥（连点两次「发送」并发写同一 StreamEncoder）。改为局部快照 + 互斥锁

- [x] **边界条件 / 异常处理（3 项）**
  - [x] **语法包 zip 炸弹**：单 entry 可声明数 GB 未压缩体积而压缩后仅数 MB，
        `zin.readBytes()` 先整体物化才判总量 → 预算检查形同虚设、必 OOM。改边读边限流
  - [x] 损坏/截断的 7z：结构解析失败与「空归档」折叠成同一空结构 → 走到加密预检分支，
        被反复索要密码，真实原因永久隐藏。`RootPeek` 增加 `parseFailed` 区分
  - [x] `safeDest` 对条目名 `.`/`..`/`./`/空串归一后解析成**解压目标目录本身**，
        写失败后调用方的 `dest.delete()` 会删掉本次原子预留的目标目录，后续条目写到目录外被丢弃。
        显式拒绝并给唯一占位名

- [x] **数据一致性（1 项）**
  - [x] `index.tsv` 字段未转义：含制表符的 `exts`（`readStringArray` 只 trim）或文件名
        （ext4 允许制表符，`source="local:<path>"`）会把行撑成 9 列，读回时整行右移 →
        `enabled` 取到 13 位时间戳 → 该语法包**静默变停用**且不高亮；含换行则整包从列表消失
        而导入提示报成功。写侧转义、读侧反转义，并把列数判据从 `>= 8` 收紧为 `== 8 || == 7`
        （多列整行丢弃，宁可少一个包也不要解析出字段全错的幽灵条目）

      `build_apk.py` 红线通过（2.18 MB）
- [x] **真机验证**：安装并冷启动无崩溃；私有目录 / `/data/adb` / 恶意 `content://` /
      畸形 SEND Intent 四类输入后 `MainActivity` 均稳定前台、`FATAL=0`
      （「接受还是拒绝」无法从设备侧观测，判定由 8 条 JVM 断言覆盖谓词本身）

### A53. 第三轮全面 BUG 深挖（2026-10-02）

- [x] **四路并行审计**（前两轮已覆盖守卫、解压安装、编辑器、文件操作、外部唤起）：
  - 终端与执行引擎（`RootService` / `AnsiParser` / `TerminalPage`）
  - 安全子系统（`data/security/` 全目录）
  - UI 状态层（`FilePage` / `HomePage` / `SettingsPage` / `MainActivity` / 各对话框）
  - 数据服务层（`AppSettings` / `EditHistoryManager` / `SyntaxPackStore` / `TextCompare` /
    `ChunkedFileReader` / `SparseLineIndex` / `HyperCore` / `TextStatistics`）
  - 共报 60+ 条；**逐条读代码复核后**修 20 项，剔除误报（`StatsArchive` 等备份类在本仓不存在；
    `selectedItem` 的实际机制是「弹窗被隐藏后以新目标重现」而非直接换目标）

- [x] **数据一致性 / 数据丢失（6 项）**
  - [x] `ChunkedDocument.cache` 是无同步的 access-order `LinkedHashMap`，而
    `IndexedLineProvider.load` 挂在 `Dispatchers.IO`、每个可见行各自 launch 一次 ——
    并发下链表自环（IO 线程永久卡死）或返回错位块（虚拟列表静默显示错行）。加 `@Synchronized`
  - [x] `ChunkedFileReader.loadAll` 把「读不满/短读」当读完：调用方只看 `text`，
    残缺内容被标成「未修改」，用户随手一存就整文件覆盖原文件。新增 `LoadResult.isComplete`，
    编辑器读不满时走既有 `loadError` 通道拒绝编辑
  - [x] `IndexedLineProvider.load` 命中 4MB 上限后仍把**前缀**当完整行返回（尾部静默丢弃），
    且循环条件里对已累积字节 `toByteArray()` 再全量数换行（单次 load 约 8MB 复制+扫描）。
    改增量计数 + 显式截断标记
  - [x] 稀疏索引无条件 `count + 1` 算行数，与分段回退路径（`split('\n')` + 末尾空串则 dropLast）
    及 `TextCompare` 口径相反：以换行结尾的文件行号数多 1，且随「索引是否建成」跳变。
    改为按是否以换行结尾取值
  - [x] `EditHistoryManager` 超出 20 万字时**只存尾部**，恢复即丢文件开头且无提示。
    `HistoryEntry` 增加 `truncated`（并落盘），编辑器对截断条目拒绝恢复并提示改用「另存为」
  - [x] `InstallConfirmDialog` 在 `onConfirm` 同一帧删除 staged 副本，而 `startInstall`
    的协程还要用它算 sha256、再喂给 `ApkInstaller` —— 两条 Main 派发谁先跑取决于帧时序，
    安装表现为「时好时坏」。引入 `stagedConsumed` 所有权标记

- [x] **安全性（6 项）**
  - [x] `$(...)` 与反引号替换的产物被单独解析成独立原子、**外层完全看不到该片段**：
    `rsync -a /x $(echo /system)/bin/` 的外层操作数只剩 `[/x, /bin/]`，末位不受保护 → 放行，
    内层 `/system` 从不参与分级。改为在外层 `current` 留 `$` 标记使 `hasUnresolvedVar` 生效，
    并把 `cp/install/ln/rsync/mv` 加入 `DANGEROUS_UNRESOLVED`
  - [x] `setenforce` / `resetprop` / `mount` / `umount` / `magisk --remove-modules`
    在 `evaluateAtom` 里**无任何分支**，落到末尾即 Allow；守卫也没有对应包装器。
    新增 `SELINUX_TOGGLE` / `PROP_OVERRIDE` / `MOUNT_MODIFY` / `MAGISK_TAMPER` 规则
  - [x] `resolveProgram` 把 `magisk` 当 wrapper 剥到词尾时 `break`，**参数被一并丢弃**
    （`magisk --remove-modules` 的 args 变空列表），导致新规则也看不到子命令。
    改为剥到词尾时只消费程序名本身
  - [x] 收尾兜底 `pkill -9 -f <路径>` 以 root 执行，`escapeShellArg` 只防 shell 解释、
    对 `pkill -f` 的 ERE 无效：路径里的 `.` 匹配任意字符（`v1.2.sh` 会命中 `v1X2yzh`）。
    新增 `ShellEscapes.escapeEreLiteral`（不转义 `/` 与 `-`：括号外本即字面量，
    `\/` 属未定义行为）。**该函数不挂在 `RootService` 上** —— 后者静态初始化依赖 Android，
    JVM 单测加载不了
  - [x] 审计轮转的临时名 `audit.log.tmp.<pid>` 可被预置**悬空软链**劫持：
    `[ -e $tmp ]` 对悬空链为 false → 不删除 → `> $tmp` 由 root 跟随软链在攻击者指定路径建文件
    （落点是 Magisk 模块目录即等于下次开机的 root 代码执行）。改用 `mktemp`（O_EXCL + 不可预测名）
    并显式拒绝软链。**真机反证**：旧写法确实被劫持（受害文件被创建），新写法未创建
  - [x] 审计日志所在目录 `/data/adb/shso` 按产品要求必须是 0777（第三方文件管理器互访），
    因此任意应用都能替换/改权限审计文件。`prepareRootTarget` 增加**属主 + 权限位**校验
    （`[ -O ]` + `find -perm /022`），不合规则删除重建并收紧为 0600。
    **真机验证**：App 启动后日志已自动变为 `600 root` 且写入正常
  - [x] 执行确认框把「扫描不可用/未完成」当 fail-open：`.sh` 读不出或超 2MB 时
    `report == null` → `hasCritical=false` → 确认按钮仍 enabled → 一键以 root 执行。
    改为 `scanUnresolved` 时禁用确认按钮

- [x] **并发（4 项）**
  - [x] 终端「当前活动进程」槽位只有 `isTaskRunning` 一个判据，而它到 `runTerminalCommand`
    内部才置 true，其间要跨过策略判定 + 守卫探测（缓存冷时最长 5s）→ 判定与置位之间存在数秒窗口，
    可并发跑两条命令或命令与脚本互相覆盖全局句柄并 cancel 掉对方的发布循环。
    新增同步 CAS 占位标记 `terminalSlotOwner` + 归属令牌自检
  - [x] `HyperCore.stopBatchFlushLoop()` 无归属令牌，旧命令收尾会停掉**新命令**仍在跑的发布循环
    （其输出退化为结束后一次性喷出）。改为返回 Job 令牌、按令牌停
  - [x] `BuiltInFilePicker.loadDirectory` 无代次守卫，连点两个文件夹时 `currentDir` 与 `fileList` 会错位
  - [x] `FilePage` 的「搜索/排序变化」特效对 `displayFileList` 的写入**没有任何守卫**，
    且基于旧 `fileList` 起算，可让路径栏是新目录而列表是旧目录内容

- [x] **功能逻辑 / 状态正确性（1 项）**
  - [x] 动作/重命名/删除/权限/多选模式五个弹窗共享同一个 `selectedItem`，
    外部唤起会改写它而 `show*Dialog` 标志不清 → 弹窗先被隐藏、用户下次点任意文件
    又以新目标重现（标题与预填值仍是旧文件），确认时作用在另一个文件上。改为各弹窗独立目标快照

- [x] **性能 / ANR（2 项）**
  - [x] `executeFile` 的前置段（守卫探测最长 5s + 脚本扫描两次 `su` 共 25s 超时 + 2MB 逐行解析）
    跑在 UI 线程 → 确定性 ANR。拆为 `executeFilePreflight`（IO）+ `startExecution`（Main）
  - [x] `GuardModuleInstaller.ensureInstalled` 整体缺 `withContext(Dispatchers.IO)`，
    冷启动与改档后由 `MainActivity` / `SettingsPage` 在主线程调用 → 最长 5s 阻塞

- [x] **兼容性（4 项）**
  - [x] 16 处无参 `lowercase()/uppercase()` 未指定 `Locale.ROOT`：`"INI"` 在土耳其语下折成 `ını`，
    含大写 `I` 的扩展名整批打不开、对比选文件器互过滤、语法包键匹配不上
    （`ArchiveExtractor` 此前只有注释、并未真改）
  - [x] 4 处日期格式化随区域取默认历法：`th-TH` 佛历 / `ja-JP-u-ca-japanese` 日本历下
    `yyyy` 取该历法 YEAR，文件时间显示成 2569 年。统一 `Locale.ROOT`，
    `DateTimeFormatter` 另加 `IsoChronology`
  - [x] `TextStatistics.countLines` 与自身 KDoc 契约相反（`"abc\n"` 返回 2），
    且与 `TextCompare` 口径不一致。改为末尾换行时减 1。
    **注意**：原单测把「多算一行」当成期望值固化了，已同步改为统一口径并在用例里写明理由
  - [x] `SyntaxPackStore.importZip` 用 `removeSuffix(".json")`（大小写敏感）配
    大小写无关的 `endsWith` 判定：`Kotlin.Json` 得到 id `kotlinjson`，与 `kotlin.json`
    写出的键不一致 → 语法包静默不高亮。改用 `substringBeforeLast('.')`

  `build_apk.py` 红线通过（2.18 MB）；真机安装并冷启动无崩溃；
  守卫 `common.sh` 语法自检通过、`device-symlink.sh` 语义未受影响
- [x] **真机专项验证**
  - [x] `find -perm /022` 权限判定 9/9 通过（600/644/640/755/4755 保留；666/620/777/466 清除）
  - [x] 审计轮转软链劫持：旧写法被劫持（受害文件被创建），`mktemp` 版未创建
  - [x] App 启动后审计日志自动收紧为 `600 root`、写入正常（当天 1290 条）、无临时文件残留
  - [x] `/data/adb/shso_guard/policy.conf` 与模块自带仅 `mode=` 行序不同，条目无缺失

### A52. 发版 20261002（2026-10-02）

- [x] 产物与校验
  - [x] `build_apk.py` 通过：`verifyReleasePayload` 红线、体积 2.18 MB、ABI 仅 arm64-v8a、V2+V3 签名
  - [x] 真机安装并冷启动，无崩溃
  - [x] 双端各下载一次，`sha256` 与本地一致
- [x] 文档
  - [x] 清除入库的设备序列号，改用 `$DEVICE` 运行时推导（`TASKS.md` 9 处）
  - [x] `校验与验证命令` 段的 `JAVA_HOME` 由 jdk-17 更正为 jdk-21（工程实际用 21）
- [x] 提交并推 `origin main` 与 `gitee main`，`git ls-remote` 复核远端 tip
- [x] 打纯数字 tag `20261002` 推双远端；两端建同名 Release
- [x] Gitee 同名附件先删再传

产物：`app-release.apk`，2 290 093 字节，`sha256=01701399adddb6e56cfb36417d2bd9f5464280193000dd24ed08ac13fc4908da`

发版踩坑（下次直接绕开）：

- **Gitee API v5 无单附件删除端点**。`assets` 数组不返回附件 id（只有上传响应里给 `id`），
  `DELETE /releases/{id}/attach_files/{name}` 返回 404。清除旧版 APK 只能
  「删整个 Release → 用同 tag/name/body 重建 → 不带附件」，源码 zip/tar.gz 由 Gitee 自动重生成。
- **PowerShell 5.1 会毁掉 CJK 正文**。`Invoke-RestMethod` 按 ANSI 码页解码响应，
  读回来的中文已是乱码；再拿这份乱码回写就等于二次损坏。
  Gitee API v5 的表单与 JSON 两种提交实测都会被按 latin-1 落库，**只有用 Python
  显式 UTF-8 收发才正确**。同理 `Out-File -Encoding utf8` 会写 BOM，
  `gh release create --notes-file` 会把 BOM 带进 GitHub 正文（表现为两端正文差 1 字符）。
- 上面的乱码可逆：`true = utf8_decode(latin1_bytes(stored))`，逐层还原即可取回真身。
  `20260922` 正文就是这样从 2609 字符还原成 1359 字的完整中文。
- 核验正文可读性必须**抓网页**而不是读 API 响应：API 响应在 PS 里解码即坏，
  网页是真值的呈现面。判据用正则 `[\u4e00-\u9fff]` 找中文、用 `[ÃÅÆåæ\u0080-\u009f]` 找乱码。

### A51. 第二轮全面 BUG 深挖（2026-10-02）

- [x] **四路并行审计 + 真机/字节码核验**：完成
  - 分工：守卫 `common.sh` / 解压与安装落盘 / 编辑器与终端 / 文件操作与外部唤起
  - 纪律：子代理结论一律复核。已剔除 2 条误报（`pm install-write` 的 splitName、
    编辑器「读取失败可保存」——代码里 `loadError != null` 已先行拦截）
  - 守卫侧不采信设备探针结论，改用**读代码 + harness 回归**定案（原因见「已知环境坑」末条：
    首轮探针把测试目录放在 `allow=/data/local/tmp` 下，`allow` 优先于 `protect`，全部误判为放行）

- [x] **守卫 v1.4.2：补齐 5 处可绕过判定**（真机 mksh + toybox 复验由放行转拦截，合法用法无误杀）
  - [x] `find` 起始路径：删掉「取第一个非选项参数就 break」，改为收集全部绝对路径操作数
    （`find -name x /system -delete`、`find /sdcard /system -delete` 原先整体放行）
  - [x] `find -exec` 名单与顶层 ARGS 对齐（`truncate`/`dd`/`cp`/`mv`/`ln`/`tee`/`sed`/`wipefs`/
    `chmod`/`chown`/`mkfs.*`/`parted` 等原缺）
  - [x] `sed` 短选项捆绑：`-ni` / `-in` / `-Ei` 原先整体放行
  - [x] `fastboot`：`flashall` / `flashing` 原先被 `flash` 的整参数匹配漏掉；
    `"oem unlock"` 写在 case 里永不命中（参数已按空白拆开）
  - [x] `cp` / `mv` 的 `-t<目录>` 紧贴写法（必选参数可与短选项连写）
  - [x] `harness.sh` 新增 22 条用例（56 → 78，全通过）；桩列表补 `fastboot`

- [x] **Kotlin 侧 8 项修复**
  - [x] OBB 目录先建再取锁（否则含 OBB 的 XAPK **首次安装必失败**，且误报「目录正被占用」）
  - [x] `ZipEntryCountProbe` 改宽松：zip4j 2.11.1 EOCD 反查**不做 EOF 对齐**（javap 核实），
    严格等值判据会被 1 字节尾随垃圾绕过；改为取全部自洽候选的条目数上界
  - [x] `delete` Java 回退先 `lstat` 判符号链接（原先跟随链接递归删空目标树）
  - [x] `moveFile` OVERWRITE 仅目录目标才预删（原先文件目标也先删，失败即丢数据）
  - [x] `ExternalOpen.copyToInbox` 去掉非局部 `return`，占位文件必清理
  - [x] 文件页编辑器宿主开关/目标项改 `rememberSaveable`（旋转不再丢未保存内容）
  - [x] 保存期间继续输入不再被清脏标记（按 `textRevision` 比对），文本与状态读取移回主线程
  - [x] 新增 `validateSaveAsPath`：拒绝相对路径、`..`/`.`、空目录段、反斜杠、NUL，
    并按运行时实际私有目录（`dataDir` / `deviceProtectedDataDir`）拒绝自毁数据

  `build_apk.py` 红线通过（2.18 MB）；真机安装并冷启动无崩溃；
  守卫 harness 78/78、`device-symlink.sh` 全通过
- [x] **文档**：`更新日志.md` 增补守卫 5 项缺口与 8 项修复

### B1. GPL-3.0 协议切换（2026-10-02）

- [x] **协议切换 Apache-2.0 → GPL-3.0-or-later**：完成
  - `LICENSE` 换为 GPL-3.0 全文（UTF-8 / LF / 无 BOM，取自 SPDX license-list-data）
  - 98 个源文件 SPDX 头改为 `GPL-3.0-or-later`（含 `proguard-rules.pro`、`build.gradle.kts`）
  - 守卫模块 43 个脚本补 SPDX 头并经 `gen_wrappers.py` 重新生成；`module.prop` 升 v1.4.0
  - README 新增第三方许可表；`docs/PROJECT.md` 新增「许可证」小节
  - 兼容性核对：Sora Editor 为 LGPL-2.1（允许以 GPL-3.0 组合分发），
    其余依赖 Apache-2.0 / 公有领域，均与 GPL-3.0 兼容
  - 顺带修正 `app/build.gradle.kts` 首行署名误写为 `KernelEX contributors`

### B2. 守卫打包工具化（2026-10-02）

- [x] **新增 `tools/pack_guard_module.py`**：完成
  - 动机：原先靠手工重打包 zip，漏文件或混入 CRLF 只能靠单测事后发现
  - 固定时间戳（1980-01-01）+ 字典序条目 + 0755/0644 权限位，保证同内容同产物
  - 打包前后校验「zip 与源码逐字节一致」，并拦下守卫脚本里的 CRLF（会被 mksh 拒绝执行）
  - 验证：首次运行即复现出既有 zip 的内容基线（`--check` 通过）

### A50. 缺陷修复（2026-10-02）

> 全部结论均以真机（Android 10 / Magisk）或 AOSP 源码为准，不采信推测。
> 单测 371 / 0 failures / 1 skipped，lint 0 error，Release 载荷红线通过。

#### 先证伪一条（避免按错误前提改代码）

- [x] **`pm install-write` 的 SPLIT_NAME 契约**：**原判「分包名写错导致安装失败」为误判**
  - AOSP `PackageInstallerSession.doWriteInternal` 写入期只做
    `FileUtils.isValidExtFilename`（仅禁 `NUL` 与 `/`），**不解析 APK**
  - 真机实测：名称传 `split0` / `base.apk` / 包名均 `Success: streamed`；
    传绝对路径报 `Invalid name`（因含 `/`）
  - 真实 split 名在 commit 时从 manifest 读出并改名
  - commit 失败会回传 stdout 且 rc=4（`Failure [CODE: ...]`），判定逻辑本就正确
  - 保留的真实改进：草稿名两两不同（`Os.open` 无 O_EXCL，同名会静默覆盖）、
    基础包排首位、`install-create -p` 提前锁包名、XAPK 自动识别基础包

#### 高危（安全 / 数据一致性）

- [x] **OBB 事务锁双持锁**：`mv 临时锁目录 锁目录` 在目标已存在且是目录时把临时目录
  移进其内部并**返回 0**（真机实测复现），并发双方都认为持锁
  - 改为单文件 + `set -C`（`O_EXCL`）原子创建，创建即 CAS，无发布窗口
  - 选它而非 `ln`：FUSE 不支持跨挂载点硬链接（实测 `Cross-device link`）
- [x] **OBB 锁元数据未做数字校验**：`cut -d'|' -fN` 对无分隔符行整行返回，
  垃圾锁文件让 mksh 的 `[ a -ge b ]` 报 `unexpected operator` 并返回非零，
  判定链静默滑进「陈旧锁可抢占」—— 状态不明被误判为可抢占（真机实测复现）
  - 加纯数字校验，非数字一律 21 fail-closed
- [x] **OBB 回滚身份用秒级 mtime**：真机实测同秒替换后 `%i:%s:%Y` 三元组完全相同，
  回滚校验形同虚设 → 改 `%i:%s:%y`（纳秒，实测可区分）
- [x] **`addFileToShso` 的 `rm -rf` 绕过门禁与审计**，且忽略退出码
- [x] **`readScriptContent` fail-open**：`stat` 失败时跳过 2MB 上限继续截断扫描
- [x] **守卫安装事后校验不回滚**，故障态模块留在设备上；设置页手动安装绕过互斥锁
- [x] **策略漏判**：`cp/mv/install -t <系统路径>`、`find -exec /system/bin/rm`、
  `find -exec busybox rm`、管道段内第二个原子（`true && curl x | sh`）
- [x] **ZIP 条目数预算滞后**：上限在解析器已构造完整 central directory 之后才判断
  - 新增 `ZipEntryCountProbe`：读尾部 64KB 解析 EOCD，超限立即拒绝（解压 + XAPK 两路）
  - ZIP64 哨兵 `0xFFFF` 按「已达上限量级」拒绝，不解析 ZIP64 记录
- [x] **审计 verdict 语义漂移**：`BLOCK` 既表「被拒绝」也表「放行但降级」
  - 收敛为 `AuditVerdict` 枚举；`DENIED` 与 `DEGRADED` 严格分离
  - 新增 `CommandSource.FILE_MANAGER`（文件页破坏操作此前被记成终端输入）
  - `DEGRADED` 在档位 0 下也留痕

#### 功能 / 健壮性

- [x] **7Z 零读死循环**：`read()==0 → continue` 使 `while (total < size)` 永不退出
- [x] **压缩包后缀区域依赖**：`lowercase(Locale.getDefault())` 在 tr locale 下 `.ZIP` 失效
- [x] **无 BOM 的 UTF-16 误判**：NUL 字节让 UTF-8 严格校验碰巧通过 → 满屏问号
  - 双信号嗅探：NUL 分布不对称（阈值 0.18）+ 解码无控制字符噪声
  - 补 GB18030 / UTF-8 混排 / 二进制 / 短输入等负向用例
- [x] **已授权 ROOT 仍装不上 `/data/adb` 的 APK**：前置 `File.exists()` 走应用 uid
  - 新增 `pathReadable`：已授权时用 root `test -f`，失败再回退 Java 判定
- [x] **收件箱总容量可绕过**：声明大小未知时只在拷贝前检查一次 → 改为边写边判
- [x] **重名探测上界语义错配**：`MAX_INBOX_FILES`（文件总数）被当重名重试上界
- [x] **注释与实现相反**：档位 fallback 注释写「保守取 STANDARD」而实现返回 `OFF`；
  守卫降级审计注释称由 `RootFileManager` 落，实际不落

#### 性能 / 体验

- [x] **`copyFile` 的 N+1 次 su fork**：重名 N 次就 fork N+1 次
  - 改为单条 shell 内用 `set -C` 依次 O_EXCL 占位；
    **文件名派生的 base/suffix/parent 各自 escapeShellArg**（此前内联未转义 = 注入）
  - 真机验证：中文+空格名、序号递增、644 权限、注入载荷被完整引号包裹
- [x] **编辑器历史 IO 在主线程**：另存为与历史回退两处
  - `doSave` 写法正确，`performSaveAs` / `onRestore` 漏了 `withContext(IO)`
- [x] **编辑器零 `rememberSaveable`**：`dirty` 丢失会绕过未保存守卫；
  编码复位为 UTF-8 会把 GB18030 文件写坏
- [x] **组合期阻塞 I/O**：`canExtractTo`（切目录即重跑）、`Typeface.createFromFile`
- [x] **FilePage 整页重组**：组合期直读 `RootService.isRootGranted`，ROOT 状态一变
  触发 2000+ 行重组 → 改为 `LaunchedEffect` 落到本地 state
- [x] **无 ROOT 时改权限仍 fork su**：改为提前返回并说明原因

#### 验证

- 单测新增 2 个类 / 56 例：`InstallAndBudgetHardeningTest`（22）、
  `PolicyCoverageGapTest`（17）、`CharsetDetectorTest`（17）
- 守卫测试套件 56 例全通过（新增 4 例 `-t` 回归）
- 真机：守卫 v1.4.0 → v1.4.1 升级、`mode=enforce`、
  `rm /system`·`rm /data/adb/modules`·`toybox rm`·`find -exec 绝对路径`·`cp -t` 全部 DENY
- 真机 OBB 锁：8 进程并发恰好 1 成功、垃圾元数据 21、活锁 17、陈旧锁回收、
  外部替换目标后回滚正确放弃删除
- 真机端到端：终端 `cp -t /system/bin …` 被 App 侧静态层硬拦（`COPY_SYSTEM`），
  审计落 `DENIED`；文件页拷贝中文+空格名成功
- Release：`app-release.apk` 2,271,609 bytes，lint 0 error / 25 warnings / 4 hints

### A. 计划：编辑器与文件页体验

- [x] **文件页搜索 / 过滤**（`a4e7355` 之后一次提交）：已实现
  - 搜索入口收进「文件列表设置」弹窗（`content-description="搜索文件"`），点击展开名称过滤栏，按名称子串过滤当前目录（大小写不敏感）
  - 过滤与排序合并为同一次后台遍历（`applyFileViewSettings(list, showHidden, sortMode, nameQuery)`），大目录不额外多一趟分配
  - 过滤时显示命中数（`N 项`，无命中转为警示色）；空列表区分「无匹配项：<关键字>」与「当前目录为空」
  - 切目录自动清空过滤词（`LaunchedEffect(currentDirectory)`），避免「新目录打不开」（实为空结果）
  - 真机验证：`xml`→`a1.xml/a2.xml`+「2 项」；`zzz`→「无匹配项：zzz」；清空→恢复全文；切到 `/` 后过滤词为空
- [x] **历史条目来源标记**（`EditHistoryManager.HistorySource` + `data/RelativeTime.kt`）：已实现
  - 三种来源：`SAVE` 手动保存 / `AUTO` 停顿快照（编辑停顿 2.5s）/ `DRAFT` 定时草稿；条目写入 JSON 的 `source` 字段，旧数据缺字段按 `AUTO` 兼容
  - 同内容来源升级：内容相同但来源更强（草稿→快照→保存）时原地升级来源并保留首次时间，不再新增重复条目（`HistoryMerge` 纯函数 + 5 例单测）
  - 修一个真 BUG：旧去重只过滤「等于新内容」的条目，其它内容的重复项会累积（A→B 交替编辑把 20 槽塞满两份内容）；现按内容全量去重
  - 历史面板显示来源标签（手动保存=绿 / 停顿快照=次要色 / 定时草稿=浅色）与相对时间（刚刚 / N 分钟前 / N 小时前 / N 天前 / 超 7 天给日期）；`data/RelativeTime.kt` + 4 例单测
  - 真机验证：输入两次并保存后，历史为「手动保存（C2）+ 停顿快照（C1）」两条，来源与相对时间均正确
- [ ] **语法包更新检测**（中）：直链固定到 tag，用户无法得知有新版本
  - 目标：「检查更新」比对远端版本文件与本机导入版本，提示可更新
- [ ] **长耗时批量操作进度反馈**（中）：多选复制/移动/删除无进度，界面表现为「无反应」
  - 目标：操作中显示「处理中 N/M」并可取消；失败项汇总提示
- [ ] 检查更新改用 GitHub API（低）：现用 `yezijinn/shso/tags` 页面 HTML 正则，页面结构变动即失效
- [ ] 图标按钮补 `contentDescription`（低）：文字按钮已自带语义，仅图标按钮受影响

### A2. 外部唤起（2026-09-28 完成）

- [x] **从其他 APP 唤起 shso 处理文件**（高）：其他应用「打开方式 / 分享」菜单出现 shso，
  点击后启动并定位到文件所在目录，OPEN 模式按类型自动执行
  - 入口：`MainActivity`（`singleTask`）+ 两个 `activity-alias`：`ExternalOpenActivity`（label「shso 打开」）、
    `ExternalLocateActivity`（label「shso 定位所在位置」），各自注册 `ACTION_VIEW` + `ACTION_SEND` +
    `ACTION_SEND_MULTIPLE`；MIME 取「精选 + `application/octet-stream`」（QQ 下载常标 octet-stream）
  - URI 采集：`data` → `EXTRA_STREAM`（覆盖 `Uri` / `CharSequence` / `List` / 数组）→ `clipData`
  - URI 解析（`data/ExternalOpen.kt`）：`file://` 直取；`externalstorage` 解 `documentId`（`primary:` →
    `/storage/emulated/0`）、`downloads` 解 `raw:`；其余查 `_data` 列；不透明 FileProvider
    流式拷贝到 `/sdcard/Download/shso/` 收件箱后按副本路径处理
  - 动作分派复用文件页 `FileItem` 谓词（不引入第二套分类）：安装 / 执行（弹确认框）/ 看图 / 编辑 / 解压 /
    退回动作菜单；文件页内动作体抽为 `startInstall` / `startExtract` / `openImageViewer` /
    `openTextEditor` 局部函数，动作菜单与外部唤起共用
  - 文件页新增 `highlightPath` 定位高亮 + 滚动，命中行用 `Accent.copy(0.22f)` 底
  - 安全：不因外部传入跳过确认框与档位门控；文件名净化滤 `..`/`\`/NUL/控制字符；拷贝上限 512MB
  - 验证：单测 293 / 0 failures（`ExternalOpenTest` 16 例）；`lintDebug` 0 error；真机实测见下
- [x] **外部唤起对抗性审查与修复**（同日）：对上述改动做 7 维度 BUG 挖掘，真机复现并修复 5 项
  - `[Critical]` **重建重复处理**：配置变更重放 intent → 收件箱重复拷贝（旋转两次得 3 份）、
    编辑器被原始内容重开且未保存编辑被丢弃。修法：`savedInstanceState` 记录「已消费」，
    重建时不重解析；`onNewIntent` 清除标记，真实再次唤起不受影响
  - `[Major]` `EXTRA_STREAM` 为 `ArrayList` / 数组时 `toString()` 出方括号 → 解析失败静默退回主页。
    修法：`streamExtraToUri` 顶层纯函数，覆盖 `Uri`/`List`/数组/`CharSequence`
  - `[Major]` 未读 `clipData`（Google Photos / Chrome 只设 clipData）→ 已补
  - `[Minor]` 未注册 `ACTION_SEND_MULTIPLE` → 已补（多文件取首个）
  - 清理死字段：`PendingExternalOpen.token` / `displayName`、`ExternalRequest.mimeType`（均无消费方）
  - 已证伪（非缺陷）：`file://` 路径穿越（被 `isUnsafePath` 兜住）、intent-filter 匹配
    （content:// + 各 MIME 均正确列出）、并发唤起竞争（后到者胜）
  - 真机复验：旋转两次收件箱仍 1 份；旋转后编辑器正常关闭（不再回退内容）；数组 stream 正确打开；
    `onNewIntent` 正常；`SEND_MULTIPLE` 被系统列出
- [x] **第二轮对抗性审查与修复**（同日）：审查范围含上一轮修复本身，真机复现并修复 3 项
  - `[High/安全]` **外部唤起 APK 静默安装**：`dispatchExternalOpen` 对 `isInstallable` 直接调
    `startInstall`，ROOT 下走 `pm install` 静默完成、无确认无审计（`ApkInstaller` 对
    `PolicyEngine`/`RootCommandGateway`/`SecurityAuditLog` 引用全为 False）。文件页手动点击是显式意图，
    但外部唤起是被动触发 —— 诱导打开即可静默装。修法：新增 `InstallConfirmDialog`，
    展示来源/文件信息/安装方式，两条路径（外部唤起与动作菜单）统一经此确认
  - `[Medium]` **旋转丢失确认框**：`pendingExecuteItem` 用 `remember`，旋转重建后静默丢弃；
    终端页同类状态早已用 `rememberSaveable`（`TASKS.md` 已记「旋转保留待确认高危命令」），文件页遗漏。
    修法：新增 `FileItemSaver`，执行确认与新增的安装确认都用 `rememberSaveable`
  - `[Low]` `FilePage` 完全不用 `rememberSaveable`（56 `remember` / 0 `rememberSaveable`）：
    目录、多选、搜索词、各弹窗旋转即丢。只修高危确认框（其余为既有行为，未扩大范围）
  - 已证伪（非缺陷）：`documentId` 含 `../` 穿越（被 `isUnsafePath` 兜住）、`file://` 不存在路径
    （回退内部存储）、BROWSABLE 误列 http 链接（不误列）、前台连续唤起不同文件（正确切换）、
    进程存活时后台恢复（不重复拷贝）
  - 存疑未证实：拷贝进行中旋转（`onExternalRequestConsumed` 在拷贝完成后才调用，理论窗口真实存在，
    但 300MB/900MB 文件均无法抢占，未复现也未证伪）
  - 真机复验：外部唤起 APK 弹「安装确认」且标注「ROOT 静默安装」；旋转后执行/安装确认框均保留；
    点「取消」不安装；点「确认安装」真正安装（`com.jinn.inputmethod` 覆盖安装成功）
- [x] **第三轮对抗性审查与修复**（同日）：首次走**真实隐式启动链路**（前两轮用 `-n` 绕过了系统选择器），
  并复查上一轮修复自身
  - `[Medium]` **无扩展名 + image MIME 被当文本打开**（清理死字段时删掉 `mimeType` 引入的回归）：
    相册分享图片常用无扩展名临时文件，`FileItem` 谓词只看扩展名 → `isViewableImage` 为假、
    `isEditableText`（无扩展名恒真）接管 → 编辑器显示乱码。修法：恢复 `mimeType` 并新增纯函数
    `decideExternalAction`（有扩展名按扩展名、无扩展名按 MIME 兜底）；顺带修 `openImageViewer`
    的相册列表按扩展名收集、无扩展名目标被漏掉导致查看器不显示
  - 真机结论（首次）：不带 `-n` 的 `VIEW` / `SEND` 均弹出系统选择器并**正确列出两个 alias**，
    选择后链路正常（前两轮结论未错但证据不足，该批次补实）
  - 已证伪（非缺陷）：无读权限 provider 优雅降级、收件箱权限 0660、无扩展名 `.sh` 不误执行、
    **拷贝中旋转的重复拷贝窗口（30/50ms 多次抢占均未复现，本地 I/O 太快，判定实践中不可达）**
  - 观察（记录，未改）：收件箱无去重/清理（同文件唤起 5 次得 5 份副本）；分享菜单里同时出现
    「打开」与「定位」语义略怪；安装动作仍零审计（`ApkInstaller` 对 `SecurityAuditLog` 引用为 0）；
    大文件拷贝期间无进度反馈（主线程未阻塞，`resolve` 在 IO）
  - 验证：单测 303 / 0 failures（`ExternalOpenTest` 21 例）、lint 0 error、release 载荷红线通过；
    真机复验无扩展名图片→查看器、无扩展名文本→编辑器、有扩展名图片→查看器、有扩展名文本→编辑器
- [x] **第四轮对抗性审查与修复**（同日）：重点复查上一轮 `decideExternalAction` 修复自身
  - `[Medium]` **外部唤起隐藏文件静默失效**（上一轮为防抖把分派挂在「目标出现在目录列表」上引入）：
    点开头文件默认被 `applyFileViewSettings` 过滤 → `pendingOpenDispatch` 的 effect 永远等不到 →
    OPEN 模式不执行任何动作（不打开/不安装/不弹框/无提示），LOCATE 模式跳目录但不高亮。
    真实场景：QQ/编辑器分享 `.gitignore` / `.env` / `.bashrc`。修法：新增
    `RootFileManager.statFilePath` 单文件取属性，在外部唤起 effect 内**立即分派**，
    与列表可见性解耦（同时覆盖「副本刚落盘尚未列出」）
  - 已证伪（非缺陷）：**协程取消吞 `CancellationException`** —— `input.read()` 是阻塞 IO、
    不响应取消，真机 200MB 拷贝中途旋转，副本完整、无残留（209715200 字节）；
    点开头文件「扩展名矛盾」（`.png` 的 `isExtensionlessText=true` 但 `realExtension='png'`）
    在 D1 修复后复验正确（隐藏图片按 MIME 进查看器）；TOCTOU（源文件消失）优雅定位不崩溃；
    zip 解压目标目录时序正确
  - 真机复验：`.gitignore`（默认不显示隐藏）→ 编辑器；`.png` 隐藏图片 → 查看器；
    普通 txt → 编辑器；不透明 URI 副本 → 编辑器；LOCATE → 停在文件页；全程无崩溃

- [!] **第五轮全面 BUG 审查**（静态审查，未操作手机）：发现以下待处理项，未擅自修改
  - `[High]` `RootFileManager.statFilePath()` 对 ROOT `stat` 的 `%n` 完整路径按文件名解析，
    拼出错误 path，导致 ROOT-only 文件的 size/mtime/权限元信息退回为 0/空；应单独解析单文件 stat
  - `[High]` 外部 OPEN 压缩包直接调用 `startExtract()`，绕过动作菜单的 `canExtractTo()` 检查，
    `ArchiveExtractor` 无总输出/条目/单文件/压缩比限制，存在 Zip Bomb 耗尽存储风险
  - `[High]` `MainContainer` 用 `catch (Exception)` 包住可取消的 `ExternalOpen.resolve()`，
    取消旧请求时可能继续调用 `onExternalRequestConsumed()` 清空新请求；`copyToInbox()` 的
    `runCatching` 同样吞 `CancellationException`
  - `[High]` 安装确认 SHA-256 与实际安装路径之间存在 TOCTOU：确认后共享存储文件可被替换，
    实际安装内容可能与确认弹窗显示的哈希不一致
  - `[Medium]` `statFilePath()` 非法路径 / stat 失败回退伪造 `FileItem(isDirectory=false)`，
    仍会进入 APK/脚本/压缩包分派；应返回 null 并禁止动作
  - `[Medium]` `FilePage` 直接分派前未用 try/finally 消费 `ExternalOpenHub`，stat/分派异常会留下旧 pending
  - `[Medium]` 收件箱无去重、过期清理与总容量上限；重复外部唤起会无限生成 `_1`、`_2` 副本
  - `[Medium]` 安装动作仍不写 `SecurityAuditLog`，无法追踪外部来源、确认哈希、静默安装结果
  - `[Low]` `FileItemSaver` 对 saved state 字段数量/类型强制转换，损坏或未来格式变化可能导致恢复崩溃
  - `[Low]` `ACTION_SEND_MULTIPLE`/多项 clipData 只取首项；纯文本分享无文件 URI 时静默无动作
  - 验证基线：未执行 ADB、安装、旋转或清理；本地 `testDebugUnitTest` / `lintDebug` 通过
  - 复核补充：`statFilePath` 的 ROOT 单文件输出必须与目录列表解析分离；外部压缩包分派必须复用
    `canExtractTo` 并增加解压资源上限；安装确认需锁定安装模式并在确认前后校验文件一致性；
    `CancellationException` 不得被普通异常捕获；上述项当前仍未修复，保持 `[!]`，待单独执行

### A3. 第五轮优先修复（2026-09-28 起）

- [x] **外部唤起高优先级缺陷修复**：完成 stat 安全、取消语义与解压预算加固；未操作手机
  - [x] `statFilePath`：单文件 ROOT stat 独立解析完整路径；非法/不存在/失败返回 null，禁止继续动作分派
  - [x] 外部请求取消：`CancellationException` 透传；Hub 使用请求 token 消费，防旧 effect 清空后来者
  - [x] 外部压缩包：总输出 1GB、单条目 512MB、条目数 20000 上限；外部路径复用 `canExtractTo`；失败清理目标目录
  - [!] 安装确认 TOCTOU、安装审计、收件箱去重/清理、文件页全面状态保存列入后续，未扩大范围

- [!] **第六轮全面 BUG 审查**（静态审查，未操作手机）：当前代码未修改，发现以下待处理项
  - `[High]` `statFilePath` 的单文件 ROOT stat 已修正，但 `parseSingleStatOutput` 与 fallback 仍需补充异常/权限回归；
    外部路径分派必须保证 stat 失败不会进入动作路径
  - `[High]` 外部压缩预算已加固，但 ZIP central directory 在 `peekRoot()` 阶段仍由 zip4j 一次性构造 header 列表，
    极端百万条目归档可能在预算检查前消耗内存
  - `[High]` 安装确认 SHA-256 与实际安装路径存在 TOCTOU；安装动作仍无审计记录
  - `[Medium]` 外部压缩失败清理与 `resolveTargetPath()` 存在并发竞态：目标目录被其他进程抢先创建时，失败清理可能误删他方目录
  - `[Medium]` `InstallConfirmDialog` 的 ROOT 状态/文件元数据在确认前后可能变化，确认文案与实际安装路径可能不一致
  - `[Medium]` FilePage 的 `rememberSaveable` 仅覆盖高危确认，目录/多选/编辑器等状态重建仍丢失
  - `[Low]` 收件箱无去重/过期清理/总容量上限；多文件分享只取首项；纯文本分享无文件 URI 时静默无动作
  - 验证：未执行 ADB、安装、旋转或清理
  - 复核补充：外部压缩/安装函数仍有多处 `catch (Exception)`，需单独透传 `CancellationException`；
    `statFilePath` 的 ROOT stat 解析和 fallback 需继续补边界测试；安装确认 TOCTOU 与 APK 安装审计仍未处理

### A4. 安装确认一致性与审计（进行中）

- [x] **安装确认 TOCTOU 修复**：确认框展示的 APK 信息必须与实际安装字节一致
  - 目标：确认阶段生成应用私有不可变副本，安装只使用副本；取消/失败清理副本
  - 目标：ROOT/非 ROOT 安装模式在确认时锁定，确认文案与实际路径一致
- 实现：确认与安装前均执行完整 SHA-256；哈希不可计算或发生变化即拒绝安装；安装模式由确认时锁定
- [ ] **APK 安装审计**：记录来源、确认哈希、安装模式、开始/结果/失败原因

### A6. 外部动作与取消语义修复（已完成）

- [x] **外部压缩包不再自动写盘**：OPEN 模式只定位并弹文件动作菜单，用户明确点击解压后才执行，
  保留资源预算与可写性检查
- [x] **取消语义收口**：ArchiveExtractor / ApkInstaller / FilePage 安装任务的 `CancellationException`
  必须透传，不转成普通失败、不继续写 Compose 状态

### A5. 第七轮全面 BUG 审查（静态审查，历史记录）

- [x] 新发现待处理项：后续已拆分至 A8，以下内容保留为审查原始记录
  - `[High]` `MainActivity` 仍用 `catch (Exception)` 捕获 `ExternalOpen.resolve()`，取消旧请求时可能继续清空后来者；
    `ArchiveExtractor` / `ApkInstaller` / `FilePage.startInstall` 也有同类取消吞异常路径
  - `[High]` `RootFileManager.statFilePath()` 修复后仍需补 ROOT-only 失败、目录、符号链接边界测试；
    stat 失败应保持不可分派，不得回退默认元数据
  - `[High]` ZIP central directory 在 `peekRoot()` 中仍可能先由 zip4j 一次性构造，条目上限检查存在前置内存峰值
  - `[Medium]` 外部压缩失败清理与目标目录并发创建存在误删他方目录风险
  - `[Medium]` 外部压缩/安装任务绑定 FilePage 生命周期，页面重建或取消时底层同步任务与 UI 状态可能脱节
  - `[Medium]` 外部 OPEN 压缩包仍自动写盘，虽有预算限制但未经确认；APK 路径已有确认，压缩包语义不一致
  - `[Medium]` 安装确认后仍安装共享存储原文件，确认哈希与实际安装内容存在 TOCTOU；安装审计仍缺失
  - `[Low]` LOCATE 隐藏文件仍无法在过滤列表中显示高亮；收件箱无去重/清理；多文件分享只处理首项
  - 本地基线：、lint 通过；未执行 ADB、安装或清理

### A7. 全面 BUG 挖掘（2026-09-29，已完成）

- [x] **八维度静态审查**：功能逻辑、边界条件、异常处理、性能、安全、兼容性、数据一致性、并发；未操作手机，避免干扰其他操作者
  - 范围：外部唤起、安装/解压、ROOT 文件操作、FilePage/编辑器生命周期、终端任务、守卫与审计
  - 规则：先区分已修复历史项与当前代码风险；每项记录严重级别、文件/行号、触发条件、影响与测试缺口
  - `[High][安全/一致性]` 安装确认哈希后仍直接从共享存储原路径 `cp`/系统安装器读取；确认哈希与实际安装字节之间仍有 TOCTOU 窗口，`FilePage.kt:331-363`、`ApkInstaller.kt:58-94`。需确认阶段生成应用私有不可变副本，安装只读副本；当前仅有哈希回归测试，无替换竞态测试
  - `[High][安全/功能]` `installXapk()` 对外部 XAPK 无总大小、单条目、条目数或 manifest 大小限制，`fileHeaders`/`readBytes()` 可造成内存或磁盘耗尽；manifest 的 `package_name` 未按 Android 包名校验，拼入 `/sdcard/Android/obb/$packageName` 后可逃出 OBB 根目录，`ApkInstaller.kt:102-181`
  - `[High][并发/一致性]` 单 APK 与 split 安装使用固定 `/data/local/tmp/_shso_install.apk`、`_shso_split_N.apk`，并发安装会互相覆盖或清理临时文件；提交失败也未统一 `pm install-abandon`，`ApkInstaller.kt:58-94`、`321-374`
  - `[High][安全]` `ArchiveExtractor.resolveTargetPath()` 的“检查后创建”与失败 `deleteRecursively()` 存在竞态；并发解压同名归档或外部进程抢先创建目标时，失败任务可能删除他方目录。`safeDest()` 的 canonical 检查同样不能阻止检查后被替换为软链，`ArchiveExtractor.kt:268-320`、`485-509`
  - `[High][边界/性能]` ZIP `peekRoot()` 在条目数上限判断前先取 `zip.fileHeaders`，zip4j 可能先一次性构造百万级 central directory；XAPK 也一次性遍历完整 headers，预算检查无法阻止前置内存峰值，`ArchiveExtractor.kt:128-145`、`ApkInstaller.kt:116-143`
  - `[High][安全/失败关闭]` `statFilePath()` ROOT `stat` 失败或输出解析失败后仍回退 `java.io.File` 元数据；共享存储可读但 ROOT stat 异常时仍可能进入安装/执行/解压分派，违背“stat 失败禁止动作”的契约，`RootFileManager.kt:347-364`
  - `[Medium][功能]` 点开头的隐藏文件统一被 `isExtensionlessText` 判为无扩展名；外部唤起 `.apk`/`.zip`/`.sh`/`.png` 时优先走 MIME，发送方常给 `application/octet-stream` 会把可安装/可执行/图片目标误退回文本或动作菜单，`FileItem.kt:105-110`、`ExternalOpen.kt:174-185`
  - `[Medium][并发/兼容]` 收件箱 `uniqueFile()` 是非原子“查存在→返回”，并发分享同名 URI 可能同时选择同一路径并互相覆盖；无去重、清理和容量上限，重复唤起可无限增长，`ExternalOpen.kt:328-369`
  - `[Medium][异常处理]` XAPK 安装取消/异常时虽清 staging，但固定临时 APK、分片安装会话与 OBB 已落盘数据没有统一回滚；`installSplitApks()` 仅写分片失败时 abandon，create/commit 失败与取消可能泄露 session/文件，`ApkInstaller.kt:155-185`、`321-374`
  - `[Medium][数据一致性]` 安装确认窗口的大小来自旧 `FileItem`，哈希却异步重新读取；文件被替换或大小变化时界面展示的大小与确认对象不一致，且 ROOT 状态变化会动态改变“安装方式”，确认时未锁定初始模式，`InstallConfirmDialog.kt:61-73`、`FilePage.kt:2031-2040`
  - `[Medium][生命周期]` `startExtract()` 在 `ArchiveExtractor.extract()` 抛取消异常时没有 `finally` 复位 `isExtracting`；页面仍存活时可能永久显示“正在解压”，`FilePage.kt:311-328`
  - `[Low][健壮性]` `FileItemSaver.restore` 对 saved state 字段数量/类型直接强转，损坏或未来格式变化会在旋转恢复阶段崩溃；`FileItem.kt:160-175`
  - `[Low][审计]` `SecurityAuditLog.log()`、轮转与读取仍用 `catch (Exception)`，可能吞取消异常或把取消记录为普通写入失败；安装流程亦完全没有来源、确认哈希、模式、结果审计，`SecurityAuditLog.kt:157-186`、`ApkInstaller.kt:58-94`
  - `[Low][兼容]` `isArchive` 未使用 `realExtension`，QQ 等产生的 `archive.zip.1` 能识别 APK `.apk.1` 却不能识别压缩包；`FileItem.kt:116-122`
  - 复核结论：外部解压自动写盘已由 A6 修复；安装确认一致性、stat 回退、XAPK 边界、安装并发与取消清理已由 A8 修复
  - 静态复核范围已完成：现有测试曾缺少 XAPK 恶意 manifest/超大条目、安装替换竞态、固定临时文件并发、隐藏扩展名外部分派和取消清理覆盖；该批次已补充关键边界测试
  - 完成记录：静态审查验证通过 `:app:testDebugUnitTest`、`:app:lintDebug`；修复项与产物验证统一记录于 A8

### A8. 高优先级缺陷修复（2026-09-29）

- [x] **修复 stat / XAPK / 安装并发边界**：完成
  - `RootFileManager.statFilePath`：ROOT stat 非零退出或解析失败直接返回 null，不再降级为可能误分派的普通文件元数据
  - `ApkInstaller`：XAPK 增加压缩包/条目/单条目/manifest/总解压预算，校验 Android 包名与版本号；单 APK、分包临时文件改为 UUID 隔离，失败会话统一 abandon
  - 安装确认：确认阶段生成应用私有缓存副本，实际安装只使用副本并复核哈希；确认时锁定 ROOT/系统安装器模式
  - 文件页：修复解压取消后 `isExtracting` 不复位；兼容 `.zip.1` 等压缩包尾缀
  - 验证：`testDebugUnitTest`、`lintDebug`、`assembleRelease` 均通过；Release 载荷红线通过；Release APK 已安装至 `$DEVICE` 并启动验证成功

### A9. 已知缺陷修复（2026-09-29）

- [x] **安装审计与收件箱并发安全**：完成
  - APK / XAPK 安装记录来源路径、确认哈希、安装模式、开始与结果
  - 收件箱使用 `createNewFile()` 原子预占文件名，避免并发分享同名文件互相覆盖；增加 256 文件 / 2GB 总容量边界
  - 暂不修改 ZIP central directory、解压目录竞态等需更大重构的项目，避免误判
  - 发布产物：`app-release.apk`，2,266,625 bytes，SHA-256 `E83E3F3912241DFCABD80F778890B9419E76715022A02811EE2D491E4A0C7ED7`，V2/V3 签名通过

### A10. 高置信度缺陷修复（2026-09-29）

- [x] **隐藏文件、解压竞态与状态恢复**：完成
  - 点文件按真实后缀分派：`.apk`、`.zip`、`.sh`、图片不再被无扩展名文本兜底抢先处理；`.env` 等未知点文件仍按文本处理
  - 解压目标文件/目录使用原子预占，失败清理只针对本次拥有的目标，降低并发解压误删他方目录风险
  - `FileItemSaver` 对字段数量、类型和数值类型做安全校验，损坏状态恢复为 null，不再因旋转恢复崩溃
  - 追加修复：压缩包类型识别统一剥离下载器 `.数字` 尾缀，`FileItem` 与实际解压内核保持一致

### A11. 解压取消清理（2026-09-29）

- [x] **取消解压残留目标清理**：完成
  - 取消单文件解压时删除本次原子预占的目标文件
  - 取消归档解压时删除本次原子预占的目标目录
  - 仅清理当前任务成功预占的目标，不触碰并发任务或用户原有文件
  - 发布产物校验：2,266,625 bytes，SHA-256 `166A330D86B3F0DBC4CB8B0DE3130FD156407A7602024B34AE874E4298991430`，V2/V3 签名通过

### A12. 全面 BUG 挖掘（2026-09-29）

- [x] **八维度复审**：功能逻辑、边界条件、异常处理、性能、安全、兼容性、数据一致性、并发；先静态审查，不操作手机
  - 范围：XAPK/分包安装、压缩包解压、外部唤起收件箱、ROOT 执行/审计、终端与编辑器生命周期
  - 要求：区分已修复项、真实当前缺陷和仅有理论风险；每项记录触发条件、影响、证据位置和测试缺口

  - `[High][功能/数据一致性]` XAPK 的 OBB `mkdir` 与 `cp` 只调用 `runCommandSync` 而忽略退出码；目标目录不可写、复制失败或空间不足时仍继续 APK 安装并可能返回成功，导致应用安装后缺少资源，`ApkInstaller.kt:192-205`。缺少 OBB 失败回滚与回归测试
  - `[High][安全/数据一致性]` ZIP/TAR/7Z 解压对重复条目没有拒绝或去重；同一路径的后出现条目会覆盖先前输出，攻击者可利用目录顺序制造“预览/扫描内容”和最终落盘内容不一致，`ArchiveExtractor.kt:369-421`、`432-465`。现有 Zip Slip/预算测试未覆盖 duplicate entry
  - `[High][安全/并发]` `safeDest()` 只在写入前做 canonical 检查；检查后目标父路径或已有目录可被替换为符号链接，随后 `mkdirs`/`FileOutputStream` 仍可能逃逸，`ArchiveExtractor.kt:530-545`。当前防护是静态路径检查，未形成写入时原子安全保证
  - `[Medium][性能]` ZIP `zip.fileHeaders` 在条目数上限检查前一次性构造 central directory；极端高条目归档可能先造成内存峰值，`ArchiveExtractor.kt:131-145`、`355-371`。需确认 zip4j 可用的流式枚举 API 后再改
  - `[Medium][异常处理]` `extract7z` 对条目声明 `entry.size < 0` 或实际读取提前 EOF 只退出循环，仍可能返回成功并保留不完整文件；应将短读视为失败并清理，`ArchiveExtractor.kt:441-465`
  - `[Medium][兼容性]` `ACTION_SEND_MULTIPLE` 与多项 `clipData` 仍只取首项，用户分享多文件时其余项目被静默丢弃；属于既有单文件限制，需先确定是否扩展 Hub/页面结构，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 除高危确认外的目录、多选、搜索与编辑器弹窗状态仍不全面保存；旋转/分屏会丢失用户上下文，但未发现数据写盘破坏，`FilePage.kt:134-214`
  - `[High][安全/数据一致性]` XAPK OBB 复制到真实 OBB 目录前没有确认目标是否为本次任务新建；安装失败回滚会直接 `rm -f` 已记录目标，可能删除用户原有同名 OBB，`ApkInstaller.kt:203-244`
  - `[High][安全/一致性]` ZIP/TAR/7Z 解压仍直接向 `safeDest()` 返回路径写入，重复条目覆盖和符号链接 TOCTOU 风险尚未修复；确认 A13 只覆盖 OBB 失败与 7Z 短读，不应把这两项标为已完成，`ArchiveExtractor.kt:382-476`
  - `[Medium][异常处理]` XAPK staging 目录 `mkdirs()` 失败未立即返回，后续可能以误导性的 ZIP 解压/复制错误结束；`ApkInstaller.kt:120-125`
  - `[Medium][边界]` 7Z 条目声明大小为负值时当前循环不读取内容却仍可能返回成功；A13 仅修复了正数声明下的提前 EOF，负值需单独拒绝，`ArchiveExtractor.kt:455-475`
  - 已证伪/暂缓：守卫卸载命令路径为固定内部常量，不构成当前 shell 注入；ZIP central-directory 内存问题尚未完成库 API 复核；上述项目未操作手机
  - 验证基线：、`lintDebug` 通过；未操作手机；新增发现保持 `[!]`，待后续专项修复

### A13. 安装与解压一致性修复（2026-09-29）

- [x] **修复 XAPK OBB 失败静默继续与 7Z 短读成功**：完成
  - OBB 目录创建/复制命令必须检查退出码，失败时停止安装并清理本次已落位 OBB
  - 7Z 非目录条目必须读满声明大小，提前 EOF 作为失败并由上层清理目标
  - 验证：`testDebugUnitTest`、`lintDebug`、`assembleRelease`、Release 载荷红线均通过；Release APK 已安装至 `$DEVICE` 并启动成功，进程 `23764`

### A14. XAPK 边界与回滚安全（2026-09-29）

- [x] **修复 OBB 原文件保护、临时目录失败与 7Z 未知大小**：完成
  - XAPK staging 目录创建失败立即返回
  - OBB 目标已存在或为软链时拒绝覆盖，回滚只清理本次成功复制的目标
  - 7Z 非目录条目声明大小为负时拒绝解压

### A15. 全面 BUG 挖掘（2026-09-29）

  - `[High][安全/数据一致性]` OBB `cp` 返回失败时，当前条目尚未加入回滚列表；若 `cp` 已创建部分目标后失败，`finally` 只清理此前成功条目，当前半文件可能残留。`ApkInstaller.kt:203-239`
  - `[High][并发]` OBB “不存在检查”与后续 `cp` 非原子；检查后其他进程可抢先创建同名目标，仍存在覆盖/回滚误删竞态。`ApkInstaller.kt:216-231`
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍可覆盖同一路径输出；`safeDest` canonical 检查与实际 `mkdirs`/打开文件之间仍有符号链接 TOCTOU。`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP central directory 仍由 `zip.fileHeaders` 一次性构造后才检查条目上限；极端高条目归档可能在预算检查前产生内存峰值。`ArchiveExtractor.kt:137`、`383`
  - `[Medium][兼容性]` 多文件分享仍只取首项；扩展需同时调整 `ExternalOpenHub` 单槽位与 FilePage 消费方式，不能单点改 `MainActivity`。`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通浏览/多选/搜索状态旋转丢失，属于上下文体验问题，暂未发现直接数据损坏。`FilePage.kt:134-214`
  - 已修复确认：staging 创建失败、OBB 已有目标拒绝覆盖、7Z 负数大小拒绝、OBB 失败回滚、7Z 提前 EOF；不重复计入
  - 现有测试覆盖：；新增发现缺少 OBB 部分复制失败、竞争创建、重复归档条目、符号链接并发替换测试

### A16. OBB 原子落位修复（2026-09-29）

- [x] **修复 OBB 部分复制残留与检查后覆盖竞态**：完成
  - 不再直接复制到最终 OBB 路径；先复制到随机临时文件，再以原子 `mv` 落位
  - 最终目标已存在或变为软链时拒绝落位，失败只清理本次临时文件
  - 取消/安装失败不删除用户原有 OBB

### A17. 全面 BUG 挖掘（2026-09-30）

  - `[High][兼容性/功能]` A16 使用 ROOT `ln` 把临时 OBB 硬链接到 `/sdcard/Android/obb`；Android emulated/FUSE 存储及部分 ROM 可能不支持跨目录/外部存储硬链接，导致合法 XAPK 的 OBB 安装全部失败。`ApkInstaller.kt:254-268`；当前无真机 OBB 回归
  - `[High][安全/并发]` 即使 `ln` 可用，临时文件复制、目标检查和最终链接依赖 shell 文件系统语义；外部进程可抢占目标或替换父目录，当前实现只保证同一实现的“目标不存在即链接”，不构成对抗性目录锁。`ApkInstaller.kt:254-268`
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍可覆盖相同输出；`safeDest()` canonical 检查与实际创建/打开之间仍有符号链接 TOCTOU，需原子、无跟随符号链接的写入方式，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP `fileHeaders` 仍在条目上限检查前整体构造，极端 central directory 可能先产生内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` `ACTION_SEND_MULTIPLE` 仍只取首项；修复需扩展 Hub/页面数据结构，不宜单点修改，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通目录、多选、搜索状态旋转丢失，当前未见数据写盘破坏，`FilePage.kt:134-214`
  - 验证基线沿用：、`lintDebug` 通过；未操作手机；高风险项保持待专项修复

### A18. OBB 落位兼容性修复（2026-09-30）

- [x] **移除外部存储硬链接依赖**：完成
  - OBB 临时文件与最终目标保持同目录，使用 `mv` 原子落位，兼容不支持跨目录硬链接的 emulated/FUSE 存储
  - 使用独占锁文件串行化同一目标的检查、复制和移动；目标已存在或变为软链时拒绝覆盖
  - 失败只清理本次临时文件，安装失败只清理本次已原子落位的目标

### A19. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发/可用性]` A18 的 OBB 锁是目标旁目录；进程被强杀、设备断电或 shell 超时后锁目录可能永久残留，后续合法安装会持续返回“锁已存在”，`ApkInstaller.kt:254-272`。当前无陈旧锁恢复/超时机制
  - `[High][安全/数据一致性]` OBB 成功落位后释放锁，待 APK 安装失败再由外层 `rm -f` 回滚；期间其他进程可替换目标，失败回滚可能删除他方文件，`ApkInstaller.kt:239-245`。需要把锁覆盖整个 OBB+APK 事务或回滚前验证文件身份
  - `[High][兼容性]` `mv` 原子落位依赖同目录 rename 语义，目标目录 `/sdcard/Android/obb` 在不同 Android/FUSE/厂商 ROM 上行为不同；当前仅验证应用启动，未验证真实 OBB XAPK 安装
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍可覆盖同一路径；`safeDest` canonical 检查与实际写入仍有符号链接 TOCTOU，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP central directory 仍在条目上限检查前由 `fileHeaders` 一次性构造，极端归档可能先造成内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，需扩展 `ExternalOpenHub`/FilePage 数据结构，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通导航、多选、搜索状态旋转丢失，当前未发现直接数据写盘破坏，`FilePage.kt:134-214`
  - A18 已修复确认：硬链接改为同目录临时文件 + `mv`；新增的是锁生命周期、事务回滚和真实 OBB 兼容性风险，不重复计入

### A20. 全面 BUG 挖掘（2026-09-30）

  - `[High][异常/可用性]` A18 的锁目录没有陈旧锁恢复；强杀/断电/shell 超时会永久阻塞同一 OBB 目标，且锁目录位于用户可见存储，可能被外部应用预创建造成拒绝服务，`ApkInstaller.kt:254-272`
  - `[High][安全/数据一致性]` OBB 外层安装失败回滚只按路径 `rm -f`，锁覆盖的 OBB 事务已结束后，其他进程仍可能替换目标，回滚会误删新文件；锁需覆盖 OBB 落位到 APK 安装完成，或回滚需验证唯一身份，`ApkInstaller.kt:239-245`
  - `[High][安全]` 解压仍未拒绝重复条目；ZIP/TAR/7Z 后出现同路径条目会覆盖前一条，且写入跟随符号链接，`ArchiveExtractor.kt:382-479`
  - `[Medium][性能]` ZIP `fileHeaders` 在预算检查前整体加载，极端 central directory 仍可能产生内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，涉及单槽位 Hub 与 FilePage 数据结构，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通状态旋转丢失，未发现直接数据写盘破坏，`FilePage.kt:134-214`

### A21. 安全档位默认值调整（2026-09-30）

- [x] **默认档位改为 0 无防护**：完成
  - 新安装或无有效持久化值时使用档位 0；不做策略审查、不拦截、不安装运行时守卫
  - 已保存的用户档位不迁移、不覆盖；用户可在设置中主动切换到 1/2/3
  - 同步 AppSettings、RootService/PolicyEngine fallback、执行确认默认值和相关文档

### A22. OBB 事务锁生命周期修复（2026-09-30）

- [x] **修复陈旧 OBB 锁与回滚身份风险**：完成
  - 锁目录写入随机 token 和时间戳；发现旧锁只在超过 TTL 时回收，避免永久拒绝服务
  - OBB 锁保持到 APK 安装事务结束，回滚前验证目标仍属于本次 token
  - 不修改 ZIP 重复条目/符号链接 TOCTOU 等独立专项

### A23. 全面 BUG 挖掘（2026-09-30）

  - `[High][安全/并发]` A22 的 finally 无条件 `rm -rf lockPath`；若锁目录被外部应用删除后重新创建，或锁被替换为含内容目录，安装结束可能删除非本次创建的目录，`ApkInstaller.kt:248-254`
  - `[High][异常/可用性]` 陈旧锁回收使用 `find -mmin`，若目标文件系统不支持可靠 mtime 或权限不足，可能永久拒绝安装；当前失败信息未区分“锁占用”和“锁无法检查”，`ApkInstaller.kt:279-282`
  - `[High][数据一致性]` OBB 回滚仍按路径删除；即使事务锁持续到 APK 结束，锁被强杀后回收再被新任务获得时，旧进程无法继续安全回滚，需 token/所有权校验，`ApkInstaller.kt:241-254`
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍覆盖输出；`safeDest` canonical 检查与实际写入间仍有符号链接 TOCTOU，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP central directory 仍一次性构造后才检查条目上限，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，需扩展 Hub/FilePage 数据结构，`MainActivity.kt:147-155`

### A24. OBB 锁所有权修复（2026-09-30）

- [x] **修复锁替换、陈旧回收和回滚误删**：完成
  - 锁目录内写入随机 token；释放/回收必须匹配 token，不再无条件 `rm -rf`
  - 回滚前验证目标内容仍带本次 token 标记；不匹配则放弃删除
  - 保留 15 分钟陈旧锁回收，但无法读取/校验锁时 fail-closed

### A25. 全面 BUG 挖掘（2026-09-30）

  - `[High][安全/并发]` `acquireObbLock()` 的陈旧锁回收按 `find -mmin` 判断后直接 `rm -rf lock`，未再次验证 token；锁可在检查后被外部替换，回收会删除非本次任务目录，`ApkInstaller.kt:294-305`
  - `[High][数据一致性]` A24 的 OBB 回滚 token 只保护锁文件，不保护目标文件；外部进程若在事务期间删除并替换目标，旧任务仍可能在 token 有效时 `rm -f` 新目标，`ApkInstaller.kt:245-256`、`315-321`
  - `[High][兼容性]` 目标旁 `.shso.lock`、`.shso.tmp.*` 文件位于用户可见 OBB 目录，Android 媒体扫描、厂商文件管理器或权限策略可能暴露/处理这些中间项；当前未验证真实 OBB XAPK 流程
  - `[High][安全]` ZIP/TAR/7Z 重复条目覆盖和符号链接 TOCTOU 仍未修复，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP `fileHeaders` 一次性构造后才检查条目上限，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享只处理首项，需要扩展单槽位 Hub 与 FilePage 数据结构，`MainActivity.kt:147-155`

### A26. 归档重复条目修复（2026-09-30）

- [x] **拒绝 ZIP/TAR/7Z 重复输出路径**：完成
  - 同一归档内经规范化后的重复文件路径直接失败，不允许后出现条目覆盖前一条
  - 目录/文件路径冲突同样失败，避免预览、扫描和最终落盘内容不一致

### A27. 全面 BUG 挖掘（2026-09-30）

  - `[High][安全/一致性]` 重复条目检测使用 `dest.canonicalPath`，但 `safeDest()` 可能因已有符号链接把路径 canonical 化到链接目标；不同归档条目可能被错误合并或仍绕过实际写入竞态，重复检测不能替代无跟随符号链接写入。`ArchiveExtractor.kt:382-479`、`546-573`
  - `[High][并发/可用性]` 陈旧锁回收仍是“检查 mtime → rm -rf”非原子操作；锁在检查后被替换仍可能误删，A24 token 只在释放/复制时校验，回收路径未校验 token。`ApkInstaller.kt:294-305`
  - `[High][数据一致性]` OBB 回滚验证 token 只验证锁，不验证目标文件仍由本次 `mv` 创建；外部进程可删除后创建同名目标，回滚仍可能删除新目标。`ApkInstaller.kt:250-260`、`315-321`
  - `[Medium][性能]` ZIP central directory 仍由 `fileHeaders` 一次性构造后才检查预算。`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` OBB 锁、临时文件位于用户可见目录，真实 XAPK OBB 安装尚未在设备上验证；厂商媒体扫描/文件管理器可能看到中间文件。`ApkInstaller.kt:254-305`
  - `[Medium][功能]` 多文件分享仍只处理首项，需扩展单槽位 Hub 与 FilePage 数据结构。`MainActivity.kt:147-155`

### A28. OBB 回滚身份修复（2026-09-30）

- [x] **修复陈旧锁回收与目标替换回滚**：完成
  - 不再自动 `rm -rf` 陈旧锁；无法确认锁所有权时 fail-closed，避免非原子回收误删他方锁
  - OBB 原子落位后记录目标身份，安装失败回滚前重新读取并比对；身份变化则放弃删除

### A29. 全面 BUG 挖掘（2026-09-30）

  - `[High][可用性/异常]` A28 取消陈旧锁自动回收后，进程强杀、断电或文件系统残留会永久阻塞同一 OBB 安装；安全拒绝误用为永久拒绝服务，`ApkInstaller.kt:296-305`
  - `[High][并发/数据一致性]` OBB 锁只覆盖当前 `installXapk` 协程；锁释放后外层异常/回滚路径与其他安装调用的资源身份仍可能交叉，当前 token 不能覆盖进程死亡后的完整事务，`ApkInstaller.kt:245-260`
  - `[High][安全]` 重复条目检测基于 canonical 路径，仍无法阻止 `safeDest` 检查后目录被替换为符号链接并实际跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍在预算前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享只取第一项，需扩展 Hub/FilePage 数据结构，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通导航/多选/搜索状态旋转丢失，未见直接写盘破坏，`FilePage.kt:134-214`

### A30. OBB 锁陈旧恢复修复（2026-09-30）

- [x] **修复 OBB 锁永久阻塞**：完成
  - 锁元数据写入 token、创建时间和持有进程标识
  - 只有锁元数据完整、超过 TTL 且持有进程已不存在时才允许回收
  - 无法确认锁状态时继续 fail-closed，避免把安全不确定性变成覆盖风险

### A32. 全面 BUG 挖掘（2026-09-30）

  - `[High][异常/可用性]` A30 的锁在进程死亡后只有当 `pid` 不存在且年龄超过 TTL 才可回收；PID 可能被系统快速复用，旧锁会被误判为活锁而永久阻塞，`ApkInstaller.kt:297-315`
  - `[High][安全/并发]` 陈旧锁回收仍是检查后 `rm -rf`，未用 token 原子夺锁；外部替换锁目录的窗口仍存在，`ApkInstaller.kt:297-315`
  - `[High][安全]` 解压输出仍直接打开 `safeDest()` 路径，重复条目拒绝不能解决符号链接 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才执行条目预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，`MainActivity.kt:147-155`
  - `[Low][文档一致性]` A24/A30 任务记录仍描述“超过 TTL 自动回收/不再自动删除”两种不同语义，实际代码已改为元数据+PID校验；需在发布前统一文档措辞

### A33. OBB 锁 PID 复用修复（2026-09-30）

- [x] **修复 PID 复用导致陈旧锁误判**：完成
  - 锁元数据增加进程启动时间标识，不再只依赖 PID 存活
  - 回收前同时校验 PID 与启动时间；无法读取时 fail-closed
  - 同步更新锁文档与验证记录

### A34. 全面 BUG 挖掘（2026-09-30）

  - `[High][可用性]` A33 仍只在“锁目录存在”时读取 PID/启动时间；若锁目录元数据未完整写入后进程退出，后续永远 fail-closed，无法恢复；`ApkInstaller.kt:297-315`
  - `[High][并发/安全]` 锁回收仍是读取旧元数据后 `rm -rf`，没有 compare-and-swap；锁被替换窗口内可能删除新任务锁，`ApkInstaller.kt:303-315`
  - `[High][安全]` 解压实际写入仍跟随 `safeDest()` 解析出的符号链接，重复条目检测不构成无跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍一次性加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`

### A35. OBB 锁元数据原子发布（2026-09-30）

- [x] **修复锁元数据半成品永久阻塞**：完成
  - 锁元数据写入随机临时目录，token/pid/start/created 全部成功后再原子移动为正式锁目录
  - 正式锁目录只接受完整元数据；临时目录异常由 trap 清理
  - 保持无法确认所有权时 fail-closed，不自动删除不完整正式锁

### A37. 全面 BUG 挖掘（2026-09-30）

  - `[High][安全/并发]` A35 临时锁完整写入后使用 `mv tempLock lock`，但锁正式目录已在此前检查阶段被删除；其他进程可在检查与发布之间抢先创建 lock，`mv` 在部分语义下可能覆盖/替换对方锁，未形成原子 compare-and-swap，`ApkInstaller.kt:303-320`
  - `[High][可用性]` A35/A33 对存活锁直接失败且没有等待/退避；两个合法并发 XAPK 安装会随机失败，用户无法区分真实占用与异常锁，`ApkInstaller.kt:303-315`
  - `[High][安全]` 解压写入仍跟随 `safeDest()` 路径，canonical 检查与 `FileOutputStream` 之间仍有符号链接 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`

### A38. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发/安全]` A35 原正式锁存在时，先读取 PID/start/created 后 `rm -rf`，再发布临时锁；这不是 compare-and-swap，任何外部替换都可能被删除或被覆盖，`ApkInstaller.kt:303-320`
  - `[High][可用性]` 活锁直接返回失败，没有等待/退避，合法并发 XAPK 安装表现为随机失败；`ApkInstaller.kt:303-305`
  - `[High][安全]` 解压输出继续跟随符号链接，canonical 检查与实际写入之间存在 TOCTOU；重复条目拒绝不改变该结论，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载，预算检查滞后，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`

### A39. OBB 锁原子接管修复（2026-09-30，历史记录）

- [x] **修复锁回收与并发重试**：后续已由 A42 及后续条目收口，以下保留原始记录
  - 陈旧锁先改名隔离，接管失败不删除未知路径
  - 活锁按有限次数短退避重试，降低合法并发安装的随机失败

### A40. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发/安全]` A39 的陈旧锁接管仍是 `mv lock quarantine` 后再 `rm -rf quarantine`；若 quarantine 被外部替换或预先存在，清理路径可能误删非本次接管对象，`ApkInstaller.kt:313-327`
  - `[High][功能]` A39 的 `repeat(3) { if (...) return@repeat }` 只结束当前迭代，不结束重试流程；成功后仍会继续 delay 并重复 acquire，可能把刚获得的锁视为已有锁而最终失败，`ApkInstaller.kt:205-210`
  - `[High][安全]` 解压仍直接向 `safeDest()` 路径打开，canonical 检查与实际写入之间存在符号链接 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`

### A41. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发/安全]` A39 quarantine 路径由调用方随机生成但未先原子预占；`mv lock quarantine` 成功后直接 `rm -rf quarantine`，外部进程可抢占该名称，导致误删，`ApkInstaller.kt:310-327`
  - `[High][异常/可用性]` A39 `repeat` 重试逻辑使用 `return@repeat`，成功获取锁后仍继续后续迭代、delay 并再次 acquire，可能把自身刚持有的锁判为冲突，`ApkInstaller.kt:205-210`
  - `[High][安全]` 解压仍存在符号链接 TOCTOU，`safeDest` canonical 检查不能保证后续 `FileOutputStream` 不跟随链接，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`

### A44. 全面 BUG 挖掘（2026-09-30）

  - `[High][文档/功能一致性]` A39 仍标为 `[/]` 未完成，且 A35/A36/A40/A41 的历史描述与当前实现混杂，发布说明可能把已修复、待修复和旧路径混为一谈；需发布前清理任务状态，`TASKS.md:519-544`
  - `[High][并发]` A42 `quarantine` 虽预占目录，但接管后仍将旧锁移入并删除；在 `mv` 与清理之间仍可能被外部替换，当前实现未能真正保证所有权，`ApkInstaller.kt:313-327`
  - `[High][安全]` 解压符号链接 TOCTOU 仍未解决，`safeDest` canonical 校验不等于无跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍一次性加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取第一项，`MainActivity.kt:147-155`

### A45. 全面 BUG 挖掘（2026-09-30）

  - `[High][文档/状态]` A39 仍标记为 `[/]`，而 A42 已标记完成；A31~A44 多处重复记录同一锁问题，当前 TASKS 不能准确表达实际完成状态，发布前容易误读。
  - `[High][并发/安全]` A42 quarantine 仍保留旧锁清理链路，A43/A44 已确认其非原子窗口；当前实现未形成可证明的 compare-and-swap。
  - `[High][安全]` 解压仍以 `FileOutputStream(dest)` 跟随路径写入，符号链接替换可逃逸 canonical 检查；需要无跟随链接的原子文件创建方式，不能靠继续增加路径判断解决。
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才检查条目上限。
  - `[Medium][兼容性]` 多文件分享仍只取第一项。

### A42. OBB 重试与 quarantine 竞态修复（2026-09-30，历史记录）

- [x] **修复重试控制流和 quarantine 误删**：完成
  - 成功获取锁后立即退出重试，不再重复 acquire 自己持有的锁
  - quarantine 目录名先用原子 `mkdir` 预占；锁接管边界仍见 A43/A44 遗留风险

### A43. 全面 BUG 挖掘（2026-09-30）

  - `[High][文档/安全一致性]` 文档与实现不一致：A35/更新日志声称“不自动删除不完整正式锁”“quarantine 只改名不删除”，但 `acquireObbLock()` 仍会 `rm -rf` 正式锁和 quarantine；发布说明会误导运维判断，且实现仍保留误删风险，`ApkInstaller.kt:313-327`
  - `[High][并发]` A42 重试修复仍未真正保证 CAS：旧锁接管、quarantine 释放和新锁发布由多个 shell 步骤组成，外部替换可插入中间窗口，`ApkInstaller.kt:303-327`
  - `[High][安全]` 解压仍存在符号链接 TOCTOU；canonical 路径检查不等于无跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，`MainActivity.kt:147-155`

### A46. 全面 BUG 挖掘（2026-09-30）

  - `[High][文档/状态]` A39 仍标记为 `[/]`，A42 已完成；A31~A45 含大量同一锁问题的历史重复记录，当前任务状态无法直接作为发布依据。
  - `[High][安全/一致性]` A39 quarantine 接管链路仍存在外部替换窗口，当前 `mv` 后没有受所有权保护的清理策略，`ApkInstaller.kt:313-329`
  - `[High][安全]` 解压写入依旧跟随 `safeDest()` 路径，canonical 检查无法阻止检查后符号链接替换，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍在预算检查前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`

### A47. 文档状态整理（2026-09-30）

- [x] **统一审查记录与发布事实**
  - A39、A42 改为历史记录，避免与后续 A43~A46 的遗留风险重复作为当前进行中任务。
  - 当前遗留风险集中记录在最近一次 A46：解压符号链接 TOCTOU、ZIP central directory 内存峰值、多文件分享单槽位。
  - 已完成修复集中记录在 A21~A35；历史审查条目保留证据但不再作为当前状态依据。
  - 文档检查：`git diff --check` 通过；未修改业务代码。

### A48. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发]` A42/A39 重试控制流与 quarantine 接管仍依赖多条 shell 命令；正式锁删除、quarantine 移动、临时锁发布之间无法形成真正 CAS，外部进程可插入窗口，`ApkInstaller.kt:303-329`
  - `[High][异常/可用性]` 活锁重试次数固定且没有区分持有者存活、状态读取失败和文件系统错误，合法并发安装可能误报“正在使用”，`ApkInstaller.kt:205-212`
  - `[High][安全]` 解压文件写入仍跟随 `safeDest()` 路径；canonical 检查后符号链接替换可导致越界写，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`

### A49. OBB 锁状态分类修复（2026-09-30 → 2026-10-02 收口）

- [x] **区分锁占用、锁损坏与文件系统错误**：已由 A50 彻底重做
  - acquire 结果不再只用退出码 17 表示所有失败
  - 持有者存活返回 17「忙」，元数据缺失/不可解析返回 21「锁状态不明」，只对「忙」退避重试
  - 2026-10-02 复核发现：原实现把「非数字元数据」也滑进了「陈旧锁可抢占」分支，
    且锁发布本身不是原子的；已在 A50 中以 `set -C`(O_EXCL) 重做锁协议并加数字校验
  - 退出码契约抽成 `ApkInstaller.ObbLockExit`，脚本构造抽成
    `buildObbLockAcquireScript` 以便单测断言 fail-closed 分支

### A36. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发/安全]` A35 临时锁目录发布前虽完整写入，但正式锁陈旧回收仍先读取 PID/start/created 再 `rm -rf`，未做 token compare-and-swap；其他进程可在窗口中替换锁，回收误删新锁，`ApkInstaller.kt:303-320`
  - `[High][异常/可用性]` A35 正式锁若已存在且元数据完整但持有进程存活，安装直接失败；没有等待/退避策略，两个合法并发安装会立即失败而非排队，`ApkInstaller.kt:303-305`
  - `[High][安全]` 解压仍直接向 `safeDest` 路径打开文件，符号链接可在 canonical 检查后被跟随；重复条目拒绝不能消除 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP `fileHeaders` 仍在预算前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取第一项，`MainActivity.kt:147-155`

### A31. 全面 BUG 挖掘（2026-09-30）

  - `[High][可用性]` A30 在锁元数据写入失败时 trap 会自动删除刚创建的锁，但在进程被强杀/断电时又无法安全回收；锁机制在“写入失败”和“崩溃残留”之间没有一致的恢复策略，可能表现为间歇性永久拒绝安装，`ApkInstaller.kt:296-315`
  - `[High][安全/边界]` OBB 回滚身份通过字符串 `path|inode:size:mtime` 传递；虽然当前生成路径不含 `|`，但该隐式格式契约无独立校验，后续路径来源变化可能导致截断或误判，`ApkInstaller.kt:253-337`
  - `[High][安全]` ZIP/TAR/7Z 写入仍跟随符号链接，canonical 检查不是原子安全写；重复条目拒绝不能消除该风险，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才做预算检查，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理第一项，`MainActivity.kt:147-155`

- [ ] **安全第三轮（可选）**：`GuardModuleInstaller` 卸载残留（`/data/adb/shso_guard/policy.conf` 与审计日志）；`ScriptAuditor` 跨行变量追踪；`$IFS` 之外的 shell 展开（`${x:-…}`、算术展开）
- [ ] **内核级守卫（独立议题）**：PATH 前置型守卫无法拦绝对路径调用与 `PATH` 重置，彻底封堵需 seccomp/LSM hook

### C. 待决事项（需人工确认）

1. 设备上的安全档位现为 0（测试后还原）。若要实际启用防护，请在设置中切到 2/3。
2. `/data/adb/shso` 下用户自带的测试文件（`test.number.sh`、`num_*.txt`）是否清理 —— 未动，等确认。
3. 语法包更新需重打 tag（`syntaxpacks-v3`…）并同步 `SyntaxPackUrls.TAG` 常量。

---

## 已完成（2026-09-14：终端专项审查与修复，4 轮）

- [x] **第一轮：终端功能审查（6 项）**：覆盖启动同名脚本被 `pkill -9 -f <文件名>` 误杀、「重启终端」不回收进程组、
      拦截高危命令不落审计（`reportBlockedInput` 成死代码）、「Enter」清空输入却不发送、私有模式 CSI 泄漏为文本、
      一次性命令无运行状态 / 不可中断 / 无流式输出。逐项真机复验。
- [x] **第二轮：控制序列补齐 + 状态健壮性（7 项）**：OSC（窗口标题 / 超链接）、两字符 ESC 序列、`` 退格、
      `ESC[K` 行内擦除、C0 控制字符过滤、转义缓冲 1KB 上限；旋转屏幕保留输入 / 历史 / 待确认高危命令；
      输出顺序（先停发布循环再写退出码）；终端命令超时 120s → 30min；>5s 命令前台保活；进程组 pid 按代际回填。
- [x] **第三轮：渲染成本与解析进度（3 项）**：**单行超长输出 ANR**（单行 10 万字符 → 主线程排版 20s、
      `Skipped 1210 frames`、`Davey! 20182ms`）以「渲染投影 4000 字符上限」修复，数据层保持全文；
      解析进度随 feed 原子落定（修重复行）；滑动窗口硬截不切代理对。
- [x] **第四轮：交互跟随（1 项）+ 3 项负结果**：上翻读日志时发命令「看起来没反应」改为发命令 / 清屏自动回尾部；
      负结果：横屏按钮未被挤掉（uiautomator 零 bounds 是假象）、四个对话框均可滚动可达、发送后输入框保持焦点。

## 上一轮已完成（2026-09-12 ~ 09-13：编辑器引擎与语法高亮系列）

### 批次 A：编辑器内核替换（Sora Editor）

- 以 Sora Editor 0.23.6 为唯一编辑面（`ui/components/SoraTextEditor.kt`）：打开即可编辑，移除「只读/编辑」切换
- 文本驻留引擎内部（行索引增量 `Content`），禁止把整串折回 Compose State（旧卡顿根因）
- 超过 `HIGHLIGHT_MAX_CHARS`（20 万字符）不设语法；巨型文件（>32MB）仍走只读稀疏索引浏览
- 实测 4.0MB / 50002 行打开 841ms（旧实现卡顿甚至闪退）

### 批次 B：语法高亮外置化（APK 零内置语法包）

- APK 不内置任何语法包（体积优先）：`app/src/main/assets/sora-grammars/` 已删除，只保留配色主题
- `tools/gen_syntax_packs.py` 生成 62 语言 / 187 扩展名（纯 Monarch JSON）→ 仓库根 `syntax-packs/` + `syntax-packs.zip`（含 `index.json` 声明扩展名与无扩展名文件名）
- `data/syntax/SyntaxPackStore.kt`：zip 整包导入、SHA-256、上限（单文件 512KB / 整包 2MB）、先全量校验再落盘、清单格式向后兼容（7 列 / 8 列）
- `ui/components/SyntaxPackDialog.kt`：导入 / 停用 / 删除 / 批量启停 / 汇总行 / 删除二次确认；预设仓库直链（tag 永固地址）
- 按需注册：只为命中的语法做解析（实测 7–47ms/个），不再首屏解析全部 62 个

### 批次 C：语言标识统一 + 文件页可编辑范围

- `data/syntax/SyntaxPackTags.kt`：语言名/短标签单一来源（已导入语法包），保证「能否高亮 / 列表标签 / 顶栏语言名」一致
- 文件列表标签由一律 `TXT` 改为具体语言（`.rs`→`RS`、`Dockerfile`→`DOCKER`）；顶栏显示 `Rust` 等
- 编辑器可编辑扩展名白名单并入语法包覆盖的 135 个扩展名；无扩展名与点开头文件（`Dockerfile`/`.gitignore`）按文本处理

### 批次 D：数据安全（保存 / 编码 / 替换）

- 保存原子化：临时文件放目标同目录 + `renameTo`/`mv` 原子替换（跨文件系统 `mv` 非原子）；失败清理临时文件；root 记录并还原 `mode/uid/gid` + `restorecon`；软链先 `readlink -f`
- 编码严格：`data/TextEncoder.kt` 用 `CharsetEncoder`+`REPORT`，目标字符集无法表示的字符拒绝保存（原先静默变 `?` 损坏文件）
- 替换/全部替换修复：Sora 的 `replaceAll`/`replaceCurrentMatch` 在检索未结束时静默返回 → 改为「先检索 → 等结果集写入 → 再替换」，全部替换走完成回调并刷新快照
- 另存为覆盖确认：目标已存在时弹确认，取消不落盘
- 草稿快照（原「自动保存」）补守卫并正名：只写编辑历史、不写原文件；加载中/失败/只读时不抓取

### 批次 E：release 体积纠正

- 排除 `jcodings`（`joni` ← `regex-lib-oniguruma`）的 648 个编码转换表 `tables/`（2.9MB 原始 / 1.24MB 压缩）→ release 3.41MB → 2.09MB
- 新增编译期红线 `verifyReleasePayload`：禁语法包 / `tables/` / 含 `"tokenizer"` 的 JSON，体积 ≤2.2MB，违反即中断构建（已反向验证）

---

## 历史批次（2026-09-11：安全加固 + 审查修复）

> 详细过程与证据见 `docs/archive/TASKS-old-20260911-v18final.md`。

- 代码审查修复 11 项（`65c4f8f`）：切编码丢编辑、`stat -c %n` 误剥离、编码探测 OOM、`checkRoot` 超时、切目录竞态、LazyColumn key、`SimpleDateFormat` 每帧新建等
- 编辑器批次（`32ca866`）：高亮移出主线程、分段按完整行对齐、CRLF 归一、修 `scrollToItem` 首屏空白回归
- 生命周期与存储（`1a0a309`）：root 保存保权限与软链、`HorizontalPager` 保留 4 页、编辑历史按文件分区
- 安全加固（`42e725e`/`7301745`/`27c1b18`/`f34eecb`）：wrapper 绕过、加密混淆、格机原语、重定向写块设备、按来源分级、fail-closed、守卫 v1.2.0 原子安装
- 提取 APK / 分包安装：`ApkInstaller.collectApkSet` 套件聚合 + `pm install-create/-write/-commit`

---

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

| 文件 | 涵盖范围 |
|---|---|
| `docs/archive/TASKS-old-20260911-v18final.md` | 任务 18–31 全量过程与证据 |
| `docs/archive/TASKS-old-20260911-v17final.md` | 更早一版（17 项，含 R8 / 守卫 / 终端 / 编辑器优化） |

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

### A67. 移除守卫与执行门禁（2026-10-03）

- [x] **备份**：守卫模块、随 APK 分发的 zip、策略与审计源码、相关测试、含守卫描述的文档、
      10 个调用方改动前快照，全部复制到 `守卫模块备份/`（基线 `d6c3b0f`），清单见该目录 `README.md`
- [x] **删除 Magisk 模块与打包资产**：`module/shso_guard/`、`assets/shso_guard.zip`、
      `tools/pack_guard_module.py`，以及 `app/build.gradle.kts` 里为该 zip 打开的 mergeAssets 任务
- [x] **删除策略与审计子系统**：`CommandParser`、`PolicyEngine`、`PathClassifier`、
      `RootCommandGateway`、`ScriptAuditor`、`SecurityModels`、`GuardModuleInstaller`、
      `GuardPathPolicy`、`SecurityAuditLog`；`ShellEscapes` 属纯转义工具，保留
- [x] **执行链路直通**：`RootService` 删除 `currentSecurityLevel` / `guardPathPrefix` /
      `reportBlockedInput` / `reportGuardDegraded` / `guardPrefixOrDegrade` 与整个
      `Preflight` 机制（含脚本内容审查）；`executeFile` 只校验扩展名，未指定时以 root 执行；
      命令拼接去掉守卫 PATH 前缀；`sendInput` 去掉 `confirmed` 参数与两处策略判定
- [x] **文件管理层去门禁**：`RootFileManager.guardDestructiveOp` 及其 6 处调用点删除，
      自动删除源文件仍检查退出码
- [x] **设置项与弹窗**：`AppSettings.securityLevel` / `updateSecurityLevel` /
      `KEY_SECURITY_LEVEL` 与 4 个 `SECURITY_*` 常量删除；`SettingsSecurityGroup` 与审计日志弹窗删除；
      `CommandRiskDialog`、`ExecuteConfirmDialog` 删除；`MainActivity` 的守卫自动安装删除
- [x] **测试**：`data/security/` 整目录删除；`RoundFiveRegressionTest` 剥离档位用例、
      `EncodingAndShellExecRegressionTest` 剥离策略与脚本审查用例（编码探测部分保留）；
      `RootFileManagerEscapingTest` 移除依赖 `guardDestructiveOp` 源码文本的护栏用例。
`ExternalTrustAndTempFileTest` 无守卫引用，厚保留。
- [x] **文档同步**：`README.md`（安全策略 → 执行方式）、`docs/PROJECT.md`（删除「安全子系统」章节）、
      `docs/命名规范.md`、`docs/文档规范.md`、`CONTRIBUTING.md` / `.en.md`、
      `B站专栏-shso分享.md`、`app/build.gradle.kts` 注释；`更新日志.md` 与 `docs/archive/` 属历史记录，不改
- [x] **验证**：`:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest --rerun-tasks` 全绿；
      `:app:lintRelease` 0 errors / 31 warnings；`:app:assembleRelease` 成功，APK 2.08 MB
- [x] **设备侧清理**：`/data/adb/modules/shso_guard`、`/data/adb/modules_update/shso_guard`、
      `/data/adb/shso_guard` 已删除；`audit.log` 与 `/data/local/tmp` 下的守卫副本、探针目录已清除；
      复验 `which rm` → `/system/bin/rm`、`$PATH` 无守卫目录

---

### A68. 修终端输出不显示与退出码缺失（2026-10-03）

移除守卫后真机复测发现两个**先前已存在**的缺陷（`d6c3b0f` 即有，非本次移除引入），
两者叠加导致「脚本确实执行、但终端输出区完全空白」。

- [x] **根因 1 · 解析代次守卫恒为假**（`TerminalPage`）
    - `val myGen = remember(terminalDefaultColor) { parseGenRef[0]++ }` 用的是**后置**自增：
      表达式值是自增前的旧值，写进 `parseGenRef[0]` 的是新值，于是 `myGen` 恒等于
      `parseGenRef[0] - 1`，两处 `parseGenRef[0] == myGen` 守卫**永不成立**。
    - 后果：`snapshotFlow` 每次 collect 都在解析完的结果上直接 `return`，`parsedOutput`
      永不更新 → 输出区空白（横幅、脚本输出、退出码全无）。真机日志实测 `gen=1/0` 佐证。
    - 修法：改为前置自增 `++parseGenRef[0]`，使当前组合的代次与计数器恒相等、
      重新组合后仍严格递增（旧协程照旧被判过期）。

- [x] **根因 2 · Job 代际比较用错对象**（`RootService` 三处）
    - `if (executionJob === coroutineContext[Job])` 在 `withContext(Dispatchers.Main)`
      与 `withContext(NonCancellable + Dispatchers.Main)` 块内，取到的是 kotlinx 为该次
      上下文创建的协程对象，与 `launch` 返回并赋给 `executionJob` 的那个并非同一实例，
      **恒不相等**。真机日志实测 `kz{Active}@942d6b0` vs `ly1{Active}@aff3929`。
    - 后果：任务结束块整体被跳过 —— 退出码永不显示、发布循环不停止、
      `isTaskRunning` / `lastExitCode` 不复位（进程早已退出，顶栏仍显示「运行中」）。
    - 修法：三处一律改与协程入口处已捕获的 `myJob` 比较（该处本就是为代际判定而取）。

- [x] **回归测试**：新增 `TerminalGenerationGuardTest`（4 例）锁定代次语义 ——
  后置自增必失效、前置自增恒成立、重新组合严格递增、连续多次组合仅最后一次有效。


- [x] **验证**：`:app:lintRelease` 0 errors / 31 warnings；Release 2.08 MB；
      真机 `$DEVICE` 实测脚本执行、终端一次性命令、实时流式回显、
      中文与彩色 Emoji、退出码、`whoami`→`root`、`id -u`→`0`、PATH 无守卫目录，
      全程 `FATAL=0` / `ANR=0`
---

### A69. 执行反馈增强：静默脚本可感知（2026-10-03）

真机反馈：`/data/adb/shso/d.sh`（只做 `rm` + `touch`、无任何 `echo`）执行后
终端除横幅与退出码外一片空白，用户误以为软件没执行。实测确认脚本本身正常
（删档 → App 执行 → 5 个目标文件全部重建），问题在反馈而非功能。
沿「执行生命周期」单点补三处反馈，正常脚本零额外噪音。

- [x] **A 静默提示**：读取循环累计「是否产出过非空白内容」，收尾时若全程无输出则
      补一行「脚本执行完成，未产生任何输出」。纯空白（空行 / 缩进 / 裸换行）不计入，
      否则静默脚本会被误判为有输出、又不给提示。
- [x] **B 耗时显示**：收尾行补 `用时`，按量级切换格式（毫秒 / 秒一位小数 / 分秒 / 时分秒），
      强制 `Locale.ROOT` 保证小数点稳定为 `.`（中文环境下 `String.format` 会输出逗号）。
- [x] **C 运行中心跳**：执行期间每 10s 追加一行已运行时长；脚本一旦产出内容即停止播报
      （满屏心跳会淹没真实日志）。心跳挂在 `scope` 上而非 `executionJob` 下，
      故在 `finally` 里显式 `cancel()` —— 否则任务结束后仍会继续插话。
      跨线程标志用 `AtomicBoolean`（局部变量不能加 `@Volatile`）。
- [x] **纯逻辑抽出便于单测**：`formatElapsed`（顶层）与 `ExecutionFeedback`
      （`hasVisibleOutput` / `anyChunkHasContent`）—— `RootService` 是 object，
      单测无法加载。新增 `ExecutionFeedbackTest` 8 例覆盖格式切换、负值兜底、
      小数点稳定性、全空白判定、分块累积判定。
- [x] **验证**：lint 0 errors / 31 warnings
      Release 2.08 MB。真机实测：
      - `d.sh`（静默）→「脚本执行完成，未产生任何输出」+「退出码: 0，用时 176毫秒」
      - `slow.sh`（静默 sleep 25s）→ 第 10 秒出现「仍在执行，已运行 10.0秒…」，顶栏「运行中...」
      - `selftest.sh`（有输出）→ 无心跳干扰、无静默误报，正常「退出码: 0，用时 94毫秒」