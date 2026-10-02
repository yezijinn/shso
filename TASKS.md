# shso 任务看板 (TASKS.md)

> 过程记录，不参与对外文档同步；现行说明见 `README.md`、`docs/PROJECT.md`、`更新日志.md`。
> 建立时间：2026-09-11（上一版归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 版本规则

`versionCode` = 构建当日日期（`YYYYMMDD`），`versionName` = `Jinn`，Release Tag 与 `versionCode` 对齐；升级判定只认 `versionCode`。完整规则见 `README.md` § 版本规则。

守卫模块独立版本：当前 v1.3.1（`module.prop` 的 `version=`）。新增或修改包装器、`common.sh` 后必须按序执行：重新生成包装器 → 重打包 `assets/shso_guard.zip` → 同步 `GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES` → 升 `module.prop` 版本，否则已装用户不会升级。

---

## 当前状态速览（2026-10-02）

| 项 | 值 |
|---|---|
| 分支 | `main`，与 `origin/main` 同步 |
| 许可 | **GPL-3.0-or-later**（2026-10-02 由 Apache-2.0 切换，强 Copyleft） |
| 单元测试 | 371 tests / 0 failures / 1 skipped |
| lint | 0 errors / 25 warnings / 4 hints |
| release 体积 | 2.17 MB，`verifyReleasePayload` 红线通过（≤2.2MB、无语法包、无 `tables/`） |
| 终端 | 增量 ANSI/OSC 解析、单行渲染上限 4000 字符、一次性命令可中断/流式/保活 |
| 编辑器内核 | Sora Editor 0.23.6（打开即可编辑；语法由外置语法包提供） |
| 语法包 | 62 语言 / 187 扩展名，`syntax-packs.zip`(37KB)，永固直链 tag `syntaxpacks-v2` |
| 守卫模块 | **v1.4.1**（真机已装并验证拦截）；重打包走 `tools/pack_guard_module.py` |
| OBB 事务锁 | 单文件 `set -C`(O_EXCL) 原子 CAS；真机 8 进程并发恰好 1 成功 |
| 审计判定 | `AuditVerdict` 枚举（`DENIED` 与 `DEGRADED` 分离）；来源含 `FILE_MANAGER` |
| 真机 | BIYLBAFQQSS8DA69，PACM00 / Android 10 / 1080×2280 / Magisk root |
| 当前安全档位 | 设备上为 **3**（验证后如需还原请手动切回 0） |
| 已知环境坑 | `adb shell su -c "a; b"` 的 `;` 会让后半段以 shell 用户执行 → 必须 `su -c 'sh script.sh'` |

---

## 待办

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

> 全部结论均以真机（PACM00 / Android 10 / Magisk）或 AOSP 源码为准，不采信推测。
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

### B. 安全后续（未安排）

### A. 本轮计划（编辑器与文件页体验）

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
    目录、多选、搜索词、各弹窗旋转即丢。本轮只修高危确认框（其余为既有行为，未扩大范围）
  - 已证伪（非缺陷）：`documentId` 含 `../` 穿越（被 `isUnsafePath` 兜住）、`file://` 不存在路径
    （回退内部存储）、BROWSABLE 误列 http 链接（不误列）、前台连续唤起不同文件（正确切换）、
    进程存活时后台恢复（不重复拷贝）
  - 存疑未证实：拷贝进行中旋转（`onExternalRequestConsumed` 在拷贝完成后才调用，理论窗口真实存在，
    但 300MB/900MB 文件均无法抢占，未复现也未证伪）
  - 真机复验：外部唤起 APK 弹「安装确认」且标注「ROOT 静默安装」；旋转后执行/安装确认框均保留；
    点「取消」不安装；点「确认安装」真正安装（`com.jinn.inputmethod` 覆盖安装成功）
- [x] **第三轮对抗性审查与修复**（同日）：首次走**真实隐式启动链路**（前两轮用 `-n` 绕过了系统选择器），
  并复查上一轮修复自身
  - `[Medium]` **无扩展名 + image MIME 被当文本打开**（上轮我清理死字段时删掉 `mimeType` 引入的回归）：
    相册分享图片常用无扩展名临时文件，`FileItem` 谓词只看扩展名 → `isViewableImage` 为假、
    `isEditableText`（无扩展名恒真）接管 → 编辑器显示乱码。修法：恢复 `mimeType` 并新增纯函数
    `decideExternalAction`（有扩展名按扩展名、无扩展名按 MIME 兜底）；顺带修 `openImageViewer`
    的相册列表按扩展名收集、无扩展名目标被漏掉导致查看器不显示
  - 真机结论（首次）：不带 `-n` 的 `VIEW` / `SEND` 均弹出系统选择器并**正确列出两个 alias**，
    选择后链路正常（前两轮结论未错但证据不足，本轮补实）
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

- [!] **第五轮全面 BUG 审查**（静态审查，本轮未操作手机）：发现以下待处理项，未擅自修改
  - `[High]` `RootFileManager.statFilePath()` 对 ROOT `stat` 的 `%n` 完整路径按文件名解析，
    拼出错误 path，导致 ROOT-only 文件的 size/mtime/权限元信息退回为 0/空；应单独解析单文件 stat
  - `[High]` 外部 OPEN 压缩包直接调用 `startExtract()`，绕过动作菜单的 `canExtractTo()` 检查，
    `ArchiveExtractor` 无总输出/条目/单文件/压缩比限制，存在 Zip Bomb 耗尽存储风险
  - `[High]` `MainContainer` 用 `catch (Exception)` 包住可取消的 `ExternalOpen.resolve()`，
    取消旧请求时可能继续调用 `onExternalRequestConsumed()` 清空新请求；`copyToInbox()` 的
    `runCatching` 同样吞 `CancellationException`
  - `[High]` 安装确认 SHA-256 与实际安装路径之间存在 TOCTOU：确认后共享存储文件可被替换，
    实际安装内容可能与用户确认的哈希不一致
  - `[Medium]` `statFilePath()` 非法路径 / stat 失败回退伪造 `FileItem(isDirectory=false)`，
    仍会进入 APK/脚本/压缩包分派；应返回 null 并禁止动作
  - `[Medium]` `FilePage` 直接分派前未用 try/finally 消费 `ExternalOpenHub`，stat/分派异常会留下旧 pending
  - `[Medium]` 收件箱无去重、过期清理与总容量上限；重复外部唤起会无限生成 `_1`、`_2` 副本
  - `[Medium]` 安装动作仍不写 `SecurityAuditLog`，无法追踪外部来源、确认哈希、静默安装结果
  - `[Low]` `FileItemSaver` 对 saved state 字段数量/类型强制转换，损坏或未来格式变化可能导致恢复崩溃
  - `[Low]` `ACTION_SEND_MULTIPLE`/多项 clipData 只取首项；纯文本分享无文件 URI 时静默无动作
  - 验证基线：本轮未执行 ADB、安装、旋转或清理；本地 `testDebugUnitTest` / `lintDebug` 通过
  - 本次复核补充：`statFilePath` 的 ROOT 单文件输出必须与目录列表解析分离；外部压缩包分派必须复用
    `canExtractTo` 并增加解压资源上限；安装确认需锁定安装模式并在确认前后校验文件一致性；
    `CancellationException` 不得被普通异常捕获；上述项当前仍未修复，保持 `[!]`，待单独执行

### B. 安全后续（未安排）

### A3. 第五轮优先修复（2026-09-28 起）

- [x] **外部唤起高优先级缺陷修复**：完成 stat 安全、取消语义与解压预算加固；本轮不操作手机
  - [x] `statFilePath`：单文件 ROOT stat 独立解析完整路径；非法/不存在/失败返回 null，禁止继续动作分派
  - [x] 外部请求取消：`CancellationException` 透传；Hub 使用请求 token 消费，防旧 effect 清空后来者
  - [x] 外部压缩包：总输出 1GB、单条目 512MB、条目数 20000 上限；外部路径复用 `canExtractTo`；失败清理目标目录
  - [!] 安装确认 TOCTOU、安装审计、收件箱去重/清理、文件页全面状态保存列入下一轮，未在本轮扩大范围
  - 验证：309 tests / 0 failures、lint 0 error、Release 载荷红线通过；新增单文件 stat / Hub 令牌 / 解压预算回归测试

- [!] **第六轮全面 BUG 审查**（静态审查，本轮未操作手机）：当前代码未修改，发现以下待处理项
  - `[High]` `statFilePath` 的单文件 ROOT stat 已修正，但 `parseSingleStatOutput` 与 fallback 仍需补充异常/权限回归；
    外部路径分派必须保证 stat 失败不会进入动作路径
  - `[High]` 外部压缩预算已加固，但 ZIP central directory 在 `peekRoot()` 阶段仍由 zip4j 一次性构造 header 列表，
    极端百万条目归档可能在预算检查前消耗内存
  - `[High]` 安装确认 SHA-256 与实际安装路径存在 TOCTOU；安装动作仍无审计记录
  - `[Medium]` 外部压缩失败清理与 `resolveTargetPath()` 存在并发竞态：目标目录被其他进程抢先创建时，失败清理可能误删他方目录
  - `[Medium]` `InstallConfirmDialog` 的 ROOT 状态/文件元数据在确认前后可能变化，确认文案与实际安装路径可能不一致
  - `[Medium]` FilePage 的 `rememberSaveable` 仅覆盖高危确认，目录/多选/编辑器等状态重建仍丢失
  - `[Low]` 收件箱无去重/过期清理/总容量上限；多文件分享只取首项；纯文本分享无文件 URI 时静默无动作
  - 本轮验证：未执行 ADB、安装、旋转或清理；本地 309 tests / 0 failures、lint 通过
  - 本次复核补充：外部压缩/安装函数仍有多处 `catch (Exception)`，需单独透传 `CancellationException`；
    `statFilePath` 的 ROOT stat 解析和 fallback 需继续补边界测试；安装确认 TOCTOU 与 APK 安装审计仍未处理

### A4. 安装确认一致性与审计（进行中）

- [x] **安装确认 TOCTOU 修复**：确认框展示的 APK 信息必须与实际安装字节一致
  - 目标：确认阶段生成应用私有不可变副本，安装只使用副本；取消/失败清理副本
  - 目标：ROOT/非 ROOT 安装模式在确认时锁定，确认文案与实际路径一致
- 实现：确认与安装前均执行完整 SHA-256；哈希不可计算或发生变化即拒绝安装；安装模式由确认时锁定
- [ ] **APK 安装审计**：记录来源、确认哈希、安装模式、开始/结果/失败原因
- 验证：310 tests / 0 failures、lint 0 error、Release 载荷红线通过；新增完整 SHA-256 回归测试

### A6. 外部动作与取消语义修复（已完成）

- [x] **外部压缩包不再自动写盘**：OPEN 模式只定位并弹文件动作菜单，用户明确点击解压后才执行，
  保留资源预算与可写性检查
- [x] **取消语义收口**：ArchiveExtractor / ApkInstaller / FilePage 安装任务的 `CancellationException`
  必须透传，不转成普通失败、不继续写 Compose 状态
- 验证：310 tests / 0 failures、lint 0 error、Release 载荷红线通过；不操作手机

### A5. 第七轮全面 BUG 审查（静态审查，历史记录）

- [x] 新发现待处理项：后续已拆分至 A8，以下内容保留为审查原始记录
  - `[High]` `MainActivity` 仍用 `catch (Exception)` 捕获 `ExternalOpen.resolve()`，取消旧请求时可能继续清空后来者；
    `ArchiveExtractor` / `ApkInstaller` / `FilePage.startInstall` 也有同类取消吞异常路径
  - `[High]` `RootFileManager.statFilePath()` 修复后仍需补 ROOT-only 失败、目录、符号链接边界测试；
    stat 失败应保持不可分派，不得回退默认元数据
  - `[High]` ZIP central directory 在 `peekRoot()` 中仍可能先由 zip4j 一次性构造，条目上限检查存在前置内存峰值
  - `[Medium]` 外部压缩失败清理与目标目录并发创建存在误删他方目录风险
  - `[Medium]` 外部压缩/安装任务绑定 FilePage 生命周期，页面重建或取消时底层同步任务与 UI 状态可能脱节
  - `[Medium]` 外部 OPEN 压缩包仍自动写盘，虽有预算限制但没有用户确认；APK 已有确认，压缩包语义不一致
  - `[Medium]` 安装确认后仍安装共享存储原文件，确认哈希与实际安装内容存在 TOCTOU；安装审计仍缺失
  - `[Low]` LOCATE 隐藏文件仍无法在过滤列表中显示高亮；收件箱无去重/清理；多文件分享只处理首项
  - 本地基线：310 tests / 0 failures、lint 通过；本轮未执行 ADB、安装或清理

### A7. 全面 BUG 挖掘（2026-09-29，已完成）

- [x] **八维度静态审查**：功能逻辑、边界条件、异常处理、性能、安全、兼容性、数据一致性、并发；本轮不操作手机，避免干扰其他操作者
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
  - 静态复核范围已完成：现有测试曾缺少 XAPK 恶意 manifest/超大条目、安装替换竞态、固定临时文件并发、隐藏扩展名外部分派和取消清理覆盖；本轮已补充关键边界测试
  - 完成记录：静态审查验证通过 `:app:testDebugUnitTest`、`:app:lintDebug`；修复项与产物验证统一记录于 A8

### A8. 高优先级缺陷修复（2026-09-29）

- [x] **修复 stat / XAPK / 安装并发边界**：完成
  - `RootFileManager.statFilePath`：ROOT stat 非零退出或解析失败直接返回 null，不再降级为可能误分派的普通文件元数据
  - `ApkInstaller`：XAPK 增加压缩包/条目/单条目/manifest/总解压预算，校验 Android 包名与版本号；单 APK、分包临时文件改为 UUID 隔离，失败会话统一 abandon
  - 安装确认：确认阶段生成应用私有缓存副本，实际安装只使用副本并复核哈希；确认时锁定 ROOT/系统安装器模式
  - 文件页：修复解压取消后 `isExtracting` 不复位；兼容 `.zip.1` 等压缩包尾缀
  - 验证：`testDebugUnitTest`、`lintDebug`、`assembleRelease` 均通过；Release 载荷红线通过；Release APK 已安装至 `BIYLBAFQQSS8DA69` 并启动验证成功

### A9. 已知缺陷修复（2026-09-29）

- [x] **安装审计与收件箱并发安全**：完成
  - APK / XAPK 安装记录来源路径、确认哈希、安装模式、开始与结果
  - 收件箱使用 `createNewFile()` 原子预占文件名，避免并发分享同名文件互相覆盖；增加 256 文件 / 2GB 总容量边界
  - 暂不修改 ZIP central directory、解压目录竞态等需更大重构的项目，避免误判
  - 验证：311 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线均通过
  - 发布产物：`app-release.apk`，2,266,625 bytes，SHA-256 `E83E3F3912241DFCABD80F778890B9419E76715022A02811EE2D491E4A0C7ED7`，V2/V3 签名通过

### A10. 高置信度缺陷修复（2026-09-29）

- [x] **隐藏文件、解压竞态与状态恢复**：完成
  - 点文件按真实后缀分派：`.apk`、`.zip`、`.sh`、图片不再被无扩展名文本兜底抢先处理；`.env` 等未知点文件仍按文本处理
  - 解压目标文件/目录使用原子预占，失败清理只针对本次拥有的目标，降低并发解压误删他方目录风险
  - `FileItemSaver` 对字段数量、类型和数值类型做安全校验，损坏状态恢复为 null，不再因旋转恢复崩溃
  - 追加修复：压缩包类型识别统一剥离下载器 `.数字` 尾缀，`FileItem` 与实际解压内核保持一致
  - 验证：312 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线均通过；后续 A11 重新构建并安装验证

### A11. 解压取消清理（2026-09-29）

- [x] **取消解压残留目标清理**：完成
  - 取消单文件解压时删除本次原子预占的目标文件
  - 取消归档解压时删除本次原子预占的目标目录
  - 仅清理当前任务成功预占的目标，不触碰并发任务或用户原有文件
  - 验证：312 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线均通过；Release APK 已安装至 `BIYLBAFQQSS8DA69` 并启动成功，进程 `13527`
  - 发布产物校验：2,266,625 bytes，SHA-256 `166A330D86B3F0DBC4CB8B0DE3130FD156407A7602024B34AE874E4298991430`，V2/V3 签名通过

### A12. 全面 BUG 挖掘（2026-09-29）

- [x] **八维度复审**：功能逻辑、边界条件、异常处理、性能、安全、兼容性、数据一致性、并发；本轮先静态审查，不操作手机
  - 范围：XAPK/分包安装、压缩包解压、外部唤起收件箱、ROOT 执行/审计、终端与编辑器生命周期
  - 要求：区分已修复项、真实当前缺陷和仅有理论风险；每项记录触发条件、影响、证据位置和测试缺口

  - `[High][功能/数据一致性]` XAPK 的 OBB `mkdir` 与 `cp` 只调用 `runCommandSync` 而忽略退出码；目标目录不可写、复制失败或空间不足时仍继续 APK 安装并可能返回成功，导致应用安装后缺少资源，`ApkInstaller.kt:192-205`。缺少 OBB 失败回滚与回归测试
  - `[High][安全/数据一致性]` ZIP/TAR/7Z 解压对重复条目没有拒绝或去重；同一路径的后出现条目会覆盖先前输出，攻击者可利用目录顺序制造“预览/扫描内容”和最终落盘内容不一致，`ArchiveExtractor.kt:369-421`、`432-465`。现有 Zip Slip/预算测试未覆盖 duplicate entry
  - `[High][安全/并发]` `safeDest()` 只在写入前做 canonical 检查；检查后目标父路径或已有目录可被替换为符号链接，随后 `mkdirs`/`FileOutputStream` 仍可能逃逸，`ArchiveExtractor.kt:530-545`。当前防护是静态路径检查，未形成写入时原子安全保证
  - `[Medium][性能]` ZIP `zip.fileHeaders` 在条目数上限检查前一次性构造 central directory；极端高条目归档可能先造成内存峰值，`ArchiveExtractor.kt:131-145`、`355-371`。需确认 zip4j 可用的流式枚举 API 后再改
  - `[Medium][异常处理]` `extract7z` 对条目声明 `entry.size < 0` 或实际读取提前 EOF 只退出循环，仍可能返回成功并保留不完整文件；应将短读视为失败并清理，`ArchiveExtractor.kt:441-465`
  - `[Medium][兼容性]` `ACTION_SEND_MULTIPLE` 与多项 `clipData` 仍只取首项，用户分享多文件时其余项目被静默丢弃；属于既有单文件模型限制，需先确定是否扩展 Hub/页面模型，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 除高危确认外的目录、多选、搜索与编辑器弹窗状态仍不全面保存；旋转/分屏会丢失用户上下文，但未发现数据写盘破坏，`FilePage.kt:134-214`
  - `[High][安全/数据一致性]` XAPK OBB 复制到真实 OBB 目录前没有确认目标是否为本次任务新建；安装失败回滚会直接 `rm -f` 已记录目标，可能删除用户原有同名 OBB，`ApkInstaller.kt:203-244`
  - `[High][安全/一致性]` ZIP/TAR/7Z 解压仍直接向 `safeDest()` 返回路径写入，重复条目覆盖和符号链接 TOCTOU 风险尚未修复；本轮确认 A13 只覆盖 OBB 失败与 7Z 短读，不应把这两项标为已完成，`ArchiveExtractor.kt:382-476`
  - `[Medium][异常处理]` XAPK staging 目录 `mkdirs()` 失败未立即返回，后续可能以误导性的 ZIP 解压/复制错误结束；`ApkInstaller.kt:120-125`
  - `[Medium][边界]` 7Z 条目声明大小为负值时当前循环不读取内容却仍可能返回成功；A13 仅修复了正数声明下的提前 EOF，负值需单独拒绝，`ArchiveExtractor.kt:455-475`
  - 已证伪/暂缓：守卫卸载命令路径为固定内部常量，不构成当前 shell 注入；ZIP central-directory 内存问题尚未完成库 API 复核；上述项目本轮未操作手机
  - 本轮验证基线：`312 tests / 0 failures / 1 skipped`、`lintDebug` 通过；本轮未操作手机；新增发现保持 `[!]`，待后续专项修复

### A13. 安装与解压一致性修复（2026-09-29）

- [x] **修复 XAPK OBB 失败静默继续与 7Z 短读成功**：完成
  - OBB 目录创建/复制命令必须检查退出码，失败时停止安装并清理本次已落位 OBB
  - 7Z 非目录条目必须读满声明大小，提前 EOF 作为失败并由上层清理目标
  - 验证：`testDebugUnitTest`、`lintDebug`、`assembleRelease`、Release 载荷红线均通过；Release APK 已安装至 `BIYLBAFQQSS8DA69` 并启动成功，进程 `23764`

### A14. XAPK 边界与回滚安全（2026-09-29）

- [x] **修复 OBB 原文件保护、临时目录失败与 7Z 未知大小**：完成
  - XAPK staging 目录创建失败立即返回
  - OBB 目标已存在或为软链时拒绝覆盖，回滚只清理本次成功复制的目标
  - 7Z 非目录条目声明大小为负时拒绝解压
  - 验证：313 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线均通过；Release APK 已安装至 `BIYLBAFQQSS8DA69` 并启动成功，进程 `2030`

### A15. 全面 BUG 挖掘（2026-09-29）

- [x] **八维度复审**：功能逻辑、边界条件、异常处理、性能、安全、兼容性、数据一致性、并发；本轮未操作手机
  - `[High][安全/数据一致性]` OBB `cp` 返回失败时，当前条目尚未加入回滚列表；若 `cp` 已创建部分目标后失败，`finally` 只清理此前成功条目，当前半文件可能残留。`ApkInstaller.kt:203-239`
  - `[High][并发]` OBB “不存在检查”与后续 `cp` 非原子；检查后其他进程可抢先创建同名目标，仍存在覆盖/回滚误删竞态。`ApkInstaller.kt:216-231`
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍可覆盖同一路径输出；`safeDest` canonical 检查与实际 `mkdirs`/打开文件之间仍有符号链接 TOCTOU。`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP central directory 仍由 `zip.fileHeaders` 一次性构造后才检查条目上限；极端高条目归档可能在预算检查前产生内存峰值。`ArchiveExtractor.kt:137`、`383`
  - `[Medium][兼容性]` 多文件分享仍只取首项；扩展需同时调整 `ExternalOpenHub` 单槽位与 FilePage 消费模型，不能单点改 `MainActivity`。`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通浏览/多选/搜索状态旋转丢失，属于上下文体验问题，暂未发现直接数据损坏。`FilePage.kt:134-214`
  - 已修复确认：staging 创建失败、OBB 已有目标拒绝覆盖、7Z 负数大小拒绝、OBB 失败回滚、7Z 提前 EOF；本轮不重复计入
  - 现有测试覆盖：313 tests / 0 failures / 1 skipped；新增发现缺少 OBB 部分复制失败、竞争创建、重复归档条目、符号链接并发替换测试

### A16. OBB 原子落位修复（2026-09-29）

- [x] **修复 OBB 部分复制残留与检查后覆盖竞态**：完成
  - 不再直接复制到最终 OBB 路径；先复制到随机临时文件，再以原子 `mv` 落位
  - 最终目标已存在或变为软链时拒绝落位，失败只清理本次临时文件
  - 取消/安装失败不删除用户原有 OBB
  - 验证：313 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；设备已安装并启动，版本 `20260930`，进程 `10247`

### A17. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：功能逻辑、边界条件、异常处理、性能、安全、兼容性、数据一致性、并发；本轮未操作手机
  - `[High][兼容性/功能]` A16 使用 ROOT `ln` 把临时 OBB 硬链接到 `/sdcard/Android/obb`；Android emulated/FUSE 存储及部分 ROM 可能不支持跨目录/外部存储硬链接，导致合法 XAPK 的 OBB 安装全部失败。`ApkInstaller.kt:254-268`；当前无真机 OBB 回归
  - `[High][安全/并发]` 即使 `ln` 可用，临时文件复制、目标检查和最终链接依赖 shell 文件系统语义；外部进程可抢占目标或替换父目录，当前实现只保证同一实现的“目标不存在即链接”，不构成对抗性目录锁。`ApkInstaller.kt:254-268`
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍可覆盖相同输出；`safeDest()` canonical 检查与实际创建/打开之间仍有符号链接 TOCTOU，需原子、无跟随符号链接的写入模型，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP `fileHeaders` 仍在条目上限检查前整体构造，极端 central directory 可能先产生内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` `ACTION_SEND_MULTIPLE` 仍只取首项；修复需扩展 Hub/页面数据模型，不宜单点修改，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通目录、多选、搜索状态旋转丢失，当前未见数据写盘破坏，`FilePage.kt:134-214`
  - 本轮未发现新的终端解析、审计字段注入或守卫卸载路径注入证据；这些不重复计入
  - 验证基线沿用：`313 tests / 0 failures / 1 skipped`、`lintDebug` 通过；本轮未操作手机；高风险项保持待专项修复

### A18. OBB 落位兼容性修复（2026-09-30）

- [x] **移除外部存储硬链接依赖**：完成
  - OBB 临时文件与最终目标保持同目录，使用 `mv` 原子落位，兼容不支持跨目录硬链接的 emulated/FUSE 存储
  - 使用独占锁文件串行化同一目标的检查、复制和移动；目标已存在或变为软链时拒绝覆盖
  - 失败只清理本次临时文件，安装失败只清理本次已原子落位的目标
  - 验证：313 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装至 `BIYLBAFQQSS8DA69` 并启动成功，进程 `28353`

### A19. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：功能逻辑、边界条件、异常处理、性能、安全、兼容性、数据一致性、并发；本轮未操作手机
  - `[High][并发/可用性]` A18 的 OBB 锁是目标旁目录；进程被强杀、设备断电或 shell 超时后锁目录可能永久残留，后续合法安装会持续返回“锁已存在”，`ApkInstaller.kt:254-272`。当前无陈旧锁恢复/超时机制
  - `[High][安全/数据一致性]` OBB 成功落位后释放锁，待 APK 安装失败再由外层 `rm -f` 回滚；期间其他进程可替换目标，失败回滚可能删除他方文件，`ApkInstaller.kt:239-245`。需要把锁覆盖整个 OBB+APK 事务或回滚前验证文件身份
  - `[High][兼容性]` `mv` 原子落位依赖同目录 rename 语义，目标目录 `/sdcard/Android/obb` 在不同 Android/FUSE/厂商 ROM 上行为不同；当前仅验证应用启动，未验证真实 OBB XAPK 安装
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍可覆盖同一路径；`safeDest` canonical 检查与实际写入仍有符号链接 TOCTOU，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP central directory 仍在条目上限检查前由 `fileHeaders` 一次性构造，极端归档可能先造成内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，需扩展 `ExternalOpenHub`/FilePage 模型，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通导航、多选、搜索状态旋转丢失，当前未发现直接数据写盘破坏，`FilePage.kt:134-214`
  - A18 已修复确认：硬链接改为同目录临时文件 + `mv`；本轮新增的是锁生命周期、事务回滚和真实 OBB 兼容性风险，不重复计入
  - 验证基线：`313 tests / 0 failures / 1 skipped`、`lintDebug` 通过；本轮未操作手机

### A20. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][异常/可用性]` A18 的锁目录没有陈旧锁恢复；强杀/断电/shell 超时会永久阻塞同一 OBB 目标，且锁目录位于用户可见存储，可能被外部应用预创建造成拒绝服务，`ApkInstaller.kt:254-272`
  - `[High][安全/数据一致性]` OBB 外层安装失败回滚只按路径 `rm -f`，锁覆盖的 OBB 事务已结束后，其他进程仍可能替换目标，回滚会误删新文件；锁需覆盖 OBB 落位到 APK 安装完成，或回滚需验证唯一身份，`ApkInstaller.kt:239-245`
  - `[High][安全]` 解压仍未拒绝重复条目；ZIP/TAR/7Z 后出现同路径条目会覆盖前一条，且写入跟随符号链接，`ArchiveExtractor.kt:382-479`
  - `[Medium][性能]` ZIP `fileHeaders` 在预算检查前整体加载，极端 central directory 仍可能产生内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，涉及单槽位 Hub 与 FilePage 模型，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通状态旋转丢失，未发现直接数据写盘破坏，`FilePage.kt:134-214`
  - 本轮未发现新的终端、审计注入或守卫路径注入问题；验证基线沿用 `313 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A21. 安全档位默认值调整（2026-09-30）

- [x] **默认档位改为 0 无防护**：完成
  - 新安装或无有效持久化值时使用档位 0；不做策略审查、不拦截、不安装运行时守卫
  - 已保存的用户档位不迁移、不覆盖；用户可在设置中主动切换到 1/2/3
  - 同步 AppSettings、RootService/PolicyEngine fallback、执行确认默认值和相关文档
  - 验证：314 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `23151`

### A22. OBB 事务锁生命周期修复（2026-09-30）

- [x] **修复陈旧 OBB 锁与回滚身份风险**：完成
  - 锁目录写入随机 token 和时间戳；发现旧锁只在超过 TTL 时回收，避免永久拒绝服务
  - OBB 锁保持到 APK 安装事务结束，回滚前验证目标仍属于本次 token
  - 不修改 ZIP 重复条目/符号链接 TOCTOU 等独立专项
  - 验证：314 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `3218`

### A23. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][安全/并发]` A22 的 finally 无条件 `rm -rf lockPath`；若锁目录被外部应用删除后重新创建，或锁被替换为含内容目录，安装结束可能删除非本次创建的目录，`ApkInstaller.kt:248-254`
  - `[High][异常/可用性]` 陈旧锁回收使用 `find -mmin`，若目标文件系统不支持可靠 mtime 或权限不足，可能永久拒绝安装；当前失败信息未区分“锁占用”和“锁无法检查”，`ApkInstaller.kt:279-282`
  - `[High][数据一致性]` OBB 回滚仍按路径删除；即使事务锁持续到 APK 结束，锁被强杀后回收再被新任务获得时，旧进程无法继续安全回滚，需 token/所有权校验，`ApkInstaller.kt:241-254`
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍覆盖输出；`safeDest` canonical 检查与实际写入间仍有符号链接 TOCTOU，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP central directory 仍一次性构造后才检查条目上限，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，需扩展 Hub/FilePage 模型，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计字段注入或守卫路径注入问题；验证基线 `314 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A24. OBB 锁所有权修复（2026-09-30）

- [x] **修复锁替换、陈旧回收和回滚误删**：完成
  - 锁目录内写入随机 token；释放/回收必须匹配 token，不再无条件 `rm -rf`
  - 回滚前验证目标内容仍带本次 token 标记；不匹配则放弃删除
  - 保留 15 分钟陈旧锁回收，但无法读取/校验锁时 fail-closed
  - 验证：314 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `7007`

### A25. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][安全/并发]` `acquireObbLock()` 的陈旧锁回收按 `find -mmin` 判断后直接 `rm -rf lock`，未再次验证 token；锁可在检查后被外部替换，回收会删除非本次任务目录，`ApkInstaller.kt:294-305`
  - `[High][数据一致性]` A24 的 OBB 回滚 token 只保护锁文件，不保护目标文件；外部进程若在事务期间删除并替换目标，旧任务仍可能在 token 有效时 `rm -f` 新目标，`ApkInstaller.kt:245-256`、`315-321`
  - `[High][兼容性]` 目标旁 `.shso.lock`、`.shso.tmp.*` 文件位于用户可见 OBB 目录，Android 媒体扫描、厂商文件管理器或权限策略可能暴露/处理这些中间项；当前未验证真实 OBB XAPK 流程
  - `[High][安全]` ZIP/TAR/7Z 重复条目覆盖和符号链接 TOCTOU 仍未修复，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP `fileHeaders` 一次性构造后才检查条目上限，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享只处理首项，需要扩展单槽位 Hub 与 FilePage 模型，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计或守卫注入问题；验证基线 `314 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A26. 归档重复条目修复（2026-09-30）

- [x] **拒绝 ZIP/TAR/7Z 重复输出路径**：完成
  - 同一归档内经规范化后的重复文件路径直接失败，不允许后出现条目覆盖前一条
  - 目录/文件路径冲突同样失败，避免预览、扫描和最终落盘内容不一致
  - 验证：315 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `5404`

### A27. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][安全/一致性]` 重复条目检测使用 `dest.canonicalPath`，但 `safeDest()` 可能因已有符号链接把路径 canonical 化到链接目标；不同归档条目可能被错误合并或仍绕过实际写入竞态，重复检测不能替代无跟随符号链接写入。`ArchiveExtractor.kt:382-479`、`546-573`
  - `[High][并发/可用性]` 陈旧锁回收仍是“检查 mtime → rm -rf”非原子操作；锁在检查后被替换仍可能误删，A24 token 只在释放/复制时校验，回收路径未校验 token。`ApkInstaller.kt:294-305`
  - `[High][数据一致性]` OBB 回滚验证 token 只验证锁，不验证目标文件仍由本次 `mv` 创建；外部进程可删除后创建同名目标，回滚仍可能删除新目标。`ApkInstaller.kt:250-260`、`315-321`
  - `[Medium][性能]` ZIP central directory 仍由 `fileHeaders` 一次性构造后才检查预算。`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` OBB 锁、临时文件位于用户可见目录，真实 XAPK OBB 安装尚未在设备上验证；厂商媒体扫描/文件管理器可能看到中间文件。`ApkInstaller.kt:254-305`
  - `[Medium][功能]` 多文件分享仍只处理首项，需扩展单槽位 Hub 与 FilePage 模型。`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫路径注入问题；验证基线：`315 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A28. OBB 回滚身份修复（2026-09-30）

- [x] **修复陈旧锁回收与目标替换回滚**：完成
  - 不再自动 `rm -rf` 陈旧锁；无法确认锁所有权时 fail-closed，避免非原子回收误删他方锁
  - OBB 原子落位后记录目标身份，安装失败回滚前重新读取并比对；身份变化则放弃删除
  - 验证：316 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `20980`

### A29. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][可用性/异常]` A28 取消陈旧锁自动回收后，进程强杀、断电或文件系统残留会永久阻塞同一 OBB 安装；安全拒绝误用为永久拒绝服务，`ApkInstaller.kt:296-305`
  - `[High][并发/数据一致性]` OBB 锁只覆盖当前 `installXapk` 协程；锁释放后外层异常/回滚路径与其他安装调用的资源身份仍可能交叉，当前 token 不能覆盖进程死亡后的完整事务，`ApkInstaller.kt:245-260`
  - `[High][安全]` 重复条目检测基于 canonical 路径，仍无法阻止 `safeDest` 检查后目录被替换为符号链接并实际跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍在预算前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享只取第一项，需扩展 Hub/FilePage 模型，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通导航/多选/搜索状态旋转丢失，未见直接写盘破坏，`FilePage.kt:134-214`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A30. OBB 锁陈旧恢复修复（2026-09-30）

- [x] **修复 OBB 锁永久阻塞**：完成
  - 锁元数据写入 token、创建时间和持有进程标识
  - 只有锁元数据完整、超过 TTL 且持有进程已不存在时才允许回收
  - 无法确认锁状态时继续 fail-closed，避免把安全不确定性变成覆盖风险
  - 验证：316 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `19486`

### A32. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][异常/可用性]` A30 的锁在进程死亡后只有当 `pid` 不存在且年龄超过 TTL 才可回收；PID 可能被系统快速复用，旧锁会被误判为活锁而永久阻塞，`ApkInstaller.kt:297-315`
  - `[High][安全/并发]` 陈旧锁回收仍是检查后 `rm -rf`，未用 token 原子夺锁；外部替换锁目录的窗口仍存在，`ApkInstaller.kt:297-315`
  - `[High][安全]` 解压输出仍直接打开 `safeDest()` 路径，重复条目拒绝不能解决符号链接 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才执行条目预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，`MainActivity.kt:147-155`
  - `[Low][文档一致性]` A24/A30 任务记录仍描述“超过 TTL 自动回收/不再自动删除”两种不同语义，实际代码已改为元数据+PID校验；需在发布前统一文档措辞
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A33. OBB 锁 PID 复用修复（2026-09-30）

- [x] **修复 PID 复用导致陈旧锁误判**：完成
  - 锁元数据增加进程启动时间标识，不再只依赖 PID 存活
  - 回收前同时校验 PID 与启动时间；无法读取时 fail-closed
  - 同步更新锁文档与验证记录
  - 验证：316 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `22683`

### A34. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][可用性]` A33 仍只在“锁目录存在”时读取 PID/启动时间；若锁目录元数据未完整写入后进程退出，后续永远 fail-closed，无法恢复；`ApkInstaller.kt:297-315`
  - `[High][并发/安全]` 锁回收仍是读取旧元数据后 `rm -rf`，没有 compare-and-swap；锁被替换窗口内可能删除新任务锁，`ApkInstaller.kt:303-315`
  - `[High][安全]` 解压实际写入仍跟随 `safeDest()` 解析出的符号链接，重复条目检测不构成无跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍一次性加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A35. OBB 锁元数据原子发布（2026-09-30）

- [x] **修复锁元数据半成品永久阻塞**：完成
  - 锁元数据写入随机临时目录，token/pid/start/created 全部成功后再原子移动为正式锁目录
  - 正式锁目录只接受完整元数据；临时目录异常由 trap 清理
  - 保持无法确认所有权时 fail-closed，不自动删除不完整正式锁
  - 验证：316 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `28197`

### A37. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][安全/并发]` A35 临时锁完整写入后使用 `mv tempLock lock`，但锁正式目录已在此前检查阶段被删除；其他进程可在检查与发布之间抢先创建 lock，`mv` 在部分语义下可能覆盖/替换对方锁，未形成原子 compare-and-swap，`ApkInstaller.kt:303-320`
  - `[High][可用性]` A35/A33 对存活锁直接失败且没有等待/退避；两个合法并发 XAPK 安装会随机失败，用户无法区分真实占用与异常锁，`ApkInstaller.kt:303-315`
  - `[High][安全]` 解压写入仍跟随 `safeDest()` 路径，canonical 检查与 `FileOutputStream` 之间仍有符号链接 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A38. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][并发/安全]` A35 原正式锁存在时，先读取 PID/start/created 后 `rm -rf`，再发布临时锁；这不是 compare-and-swap，任何外部替换都可能被删除或被覆盖，`ApkInstaller.kt:303-320`
  - `[High][可用性]` 活锁直接返回失败，没有等待/退避，合法并发 XAPK 安装表现为随机失败；`ApkInstaller.kt:303-305`
  - `[High][安全]` 解压输出继续跟随符号链接，canonical 检查与实际写入之间存在 TOCTOU；重复条目拒绝不改变该结论，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载，预算检查滞后，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A39. OBB 锁原子接管修复（2026-09-30，历史记录）

- [x] **修复锁回收与并发重试**：后续已由 A42 及后续条目收口，以下保留原始记录
  - 陈旧锁先改名隔离，接管失败不删除未知路径
  - 活锁按有限次数短退避重试，降低合法并发安装的随机失败

### A40. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][并发/安全]` A39 的陈旧锁接管仍是 `mv lock quarantine` 后再 `rm -rf quarantine`；若 quarantine 被外部替换或预先存在，清理路径可能误删非本次接管对象，`ApkInstaller.kt:313-327`
  - `[High][功能]` A39 的 `repeat(3) { if (...) return@repeat }` 只结束当前迭代，不结束重试流程；成功后仍会继续 delay 并重复 acquire，可能把刚获得的锁视为已有锁而最终失败，`ApkInstaller.kt:205-210`
  - `[High][安全]` 解压仍直接向 `safeDest()` 路径打开，canonical 检查与实际写入之间存在符号链接 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A41. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][并发/安全]` A39 quarantine 路径由调用方随机生成但未先原子预占；`mv lock quarantine` 成功后直接 `rm -rf quarantine`，外部进程可抢占该名称，导致误删，`ApkInstaller.kt:310-327`
  - `[High][异常/可用性]` A39 `repeat` 重试逻辑使用 `return@repeat`，成功获取锁后仍继续后续迭代、delay 并再次 acquire，可能把自身刚持有的锁判为冲突，`ApkInstaller.kt:205-210`
  - `[High][安全]` 解压仍存在符号链接 TOCTOU，`safeDest` canonical 检查不能保证后续 `FileOutputStream` 不跟随链接，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A44. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][文档/功能一致性]` A39 仍标为 `[/]` 未完成，且 A35/A36/A40/A41 的历史描述与当前实现混杂，发布说明可能把已修复、待修复和旧路径混为一谈；需发布前清理任务状态，`TASKS.md:519-544`
  - `[High][并发]` A42 `quarantine` 虽预占目录，但接管后仍将旧锁移入并删除；在 `mv` 与清理之间仍可能被外部替换，当前实现未能真正保证所有权，`ApkInstaller.kt:313-327`
  - `[High][安全]` 解压符号链接 TOCTOU 仍未解决，`safeDest` canonical 校验不等于无跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍一次性加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取第一项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计或守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A45. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][文档/状态]` A39 仍标记为 `[/]`，而 A42 已标记完成；A31~A44 多处重复记录同一锁问题，当前 TASKS 不能准确表达实际完成状态，发布前容易误读。
  - `[High][并发/安全]` A42 quarantine 仍保留旧锁清理链路，A43/A44 已确认其非原子窗口；当前实现未形成可证明的 compare-and-swap。
  - `[High][安全]` 解压仍以 `FileOutputStream(dest)` 跟随路径写入，符号链接替换可逃逸 canonical 检查；需要无跟随链接的原子文件创建模型，不能靠继续增加路径判断解决。
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才检查条目上限。
  - `[Medium][兼容性]` 多文件分享仍只取第一项。
  - 本轮未发现新的终端、审计字段注入、守卫路径注入；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A42. OBB 重试与 quarantine 竞态修复（2026-09-30，历史记录）

- [x] **修复重试控制流和 quarantine 误删**：完成
  - 成功获取锁后立即退出重试，不再重复 acquire 自己持有的锁
  - quarantine 目录名先用原子 `mkdir` 预占；锁接管边界仍见 A43/A44 遗留风险
  - 验证：316 tests / 0 failures / 1 skipped、lintDebug、assembleRelease、Release 载荷红线通过；APK 已安装并启动，进程 `26524`；SHA-256 `77B9C9F2CD384AEBD4E06265DF7D80D0EA89A7C0E2008588E99E912D5A88EAB5`

### A43. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][文档/安全一致性]` 文档与实现不一致：A35/更新日志声称“不自动删除不完整正式锁”“quarantine 只改名不删除”，但 `acquireObbLock()` 仍会 `rm -rf` 正式锁和 quarantine；发布说明会误导运维判断，且实现仍保留误删风险，`ApkInstaller.kt:313-327`
  - `[High][并发]` A42 重试修复仍未真正保证 CAS：旧锁接管、quarantine 释放和新锁发布由多个 shell 步骤组成，外部替换可插入中间窗口，`ApkInstaller.kt:303-327`
  - `[High][安全]` 解压仍存在符号链接 TOCTOU；canonical 路径检查不等于无跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只消费首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计或守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A46. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][文档/状态]` A39 仍标记为 `[/]`，A42 已完成；A31~A45 含大量同一锁问题的历史重复记录，当前任务状态无法直接作为发布依据。
  - `[High][安全/一致性]` A39 quarantine 接管链路仍存在外部替换窗口，当前 `mv` 后没有受所有权保护的清理策略，`ApkInstaller.kt:313-329`
  - `[High][安全]` 解压写入依旧跟随 `safeDest()` 路径，canonical 检查无法阻止检查后符号链接替换，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍在预算检查前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计或守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A47. 文档状态整理（2026-09-30）

- [x] **统一审查记录与发布事实**
  - A39、A42 改为历史记录，避免与后续 A43~A46 的遗留风险重复作为当前进行中任务。
  - 当前遗留风险集中记录在最近一次 A46：解压符号链接 TOCTOU、ZIP central directory 内存峰值、多文件分享单槽位。
  - 已完成修复集中记录在 A21~A35；历史审查条目保留证据但不再作为当前状态依据。
  - 文档检查：`git diff --check` 通过；未修改业务代码。

### A48. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][并发]` A42/A39 重试控制流与 quarantine 接管仍依赖多条 shell 命令；正式锁删除、quarantine 移动、临时锁发布之间无法形成真正 CAS，外部进程可插入窗口，`ApkInstaller.kt:303-329`
  - `[High][异常/可用性]` 活锁重试次数固定且没有区分持有者存活、状态读取失败和文件系统错误，合法并发安装可能误报“正在使用”，`ApkInstaller.kt:205-212`
  - `[High][安全]` 解压文件写入仍跟随 `safeDest()` 路径；canonical 检查后符号链接替换可导致越界写，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A49. OBB 锁状态分类修复（2026-09-30 → 2026-10-02 收口）

- [x] **区分锁占用、锁损坏与文件系统错误**：已由 A50 彻底重做
  - acquire 结果不再只用退出码 17 表示所有失败
  - 持有者存活返回 17「忙」，元数据缺失/不可解析返回 21「锁状态不明」，只对「忙」退避重试
  - 2026-10-02 复核发现：原实现把「非数字元数据」也滑进了「陈旧锁可抢占」分支，
    且锁发布本身不是原子的；已在 A50 中以 `set -C`(O_EXCL) 重做锁协议并加数字校验
  - 退出码契约抽成 `ApkInstaller.ObbLockExit`，脚本构造抽成
    `buildObbLockAcquireScript` 以便单测断言 fail-closed 分支

### A36. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][并发/安全]` A35 临时锁目录发布前虽完整写入，但正式锁陈旧回收仍先读取 PID/start/created 再 `rm -rf`，未做 token compare-and-swap；其他进程可在窗口中替换锁，回收误删新锁，`ApkInstaller.kt:303-320`
  - `[High][异常/可用性]` A35 正式锁若已存在且元数据完整但持有进程存活，安装直接失败；没有等待/退避策略，两个合法并发安装会立即失败而非排队，`ApkInstaller.kt:303-305`
  - `[High][安全]` 解压仍直接向 `safeDest` 路径打开文件，符号链接可在 canonical 检查后被跟随；重复条目拒绝不能消除 TOCTOU，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP `fileHeaders` 仍在预算前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取第一项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

### A31. 全面 BUG 挖掘（2026-09-30）

- [x] **八维度复审**：本轮未操作手机
  - `[High][可用性]` A30 在锁元数据写入失败时 trap 会自动删除刚创建的锁，但在进程被强杀/断电时又无法安全回收；锁机制在“写入失败”和“崩溃残留”之间没有一致的恢复策略，可能表现为间歇性永久拒绝安装，`ApkInstaller.kt:296-315`
  - `[High][安全/边界]` OBB 回滚身份通过字符串 `path|inode:size:mtime` 传递；虽然当前生成路径不含 `|`，但该隐式格式契约无独立校验，后续路径来源变化可能导致截断或误判，`ApkInstaller.kt:253-337`
  - `[High][安全]` ZIP/TAR/7Z 写入仍跟随符号链接，canonical 检查不是原子安全写；重复条目拒绝不能消除该风险，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才做预算检查，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理第一项，`MainActivity.kt:147-155`
  - 本轮未发现新的终端、审计、守卫注入问题；验证基线 `316 tests / 0 failures / 1 skipped`、`lintDebug` 通过

- [ ] **安全第三轮（可选）**：`GuardModuleInstaller` 卸载残留（`/data/adb/shso_guard/policy.conf` 与审计日志）；`ScriptAuditor` 跨行变量追踪；`$IFS` 之外的 shell 展开（`${x:-…}`、算术展开）
- [ ] **内核级守卫（独立议题）**：PATH 前置型守卫无法拦绝对路径调用与 `PATH` 重置，彻底封堵需 seccomp/LSM hook

### C. 待决事项（需用户确认）

1. 设备上的安全档位现为 0（测试后还原）。若要实际启用防护，请在设置中切到 2/3。
2. `/data/adb/shso` 下用户自带的测试文件（`test.number.sh`、`num_*.txt`）是否清理 —— 未动，等确认。
3. 语法包更新需重打 tag（`syntaxpacks-v3`…）并同步 `SyntaxPackUrls.TAG` 常量。

---

## 本轮已完成（2026-09-14：终端专项审查与修复，4 轮）

- [x] **第一轮：终端功能审查（6 项）**：覆盖启动同名脚本被 `pkill -9 -f <文件名>` 误杀、「重启终端」不回收进程组、
      拦截高危命令不落审计（`reportBlockedInput` 成死代码）、「Enter」清空输入却不发送、私有模式 CSI 泄漏为文本、
      一次性命令无运行状态 / 不可中断 / 无流式输出。逐项真机复验。
- [x] **第二轮：控制序列补齐 + 状态健壮性（7 项）**：OSC（窗口标题 / 超链接）、两字符 ESC 序列、`` 退格、
      `ESC[K` 行内擦除、C0 控制字符过滤、转义缓冲 1KB 上限；旋转屏幕保留输入 / 历史 / 待确认高危命令；
      输出顺序（先停发布循环再写退出码）；终端命令超时 120s → 30min；>5s 命令前台保活；进程组 pid 按代际回填。
- [x] **第三轮：渲染成本与解析进度（3 项）**：**单行超长输出 ANR**（单行 10 万字符 → 主线程排版 20s、
      `Skipped 1210 frames`、`Davey! 20182ms`）以「渲染投影 4000 字符上限」修复，模型保持全文；
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
  模型层保持全文；新增任何「整行渲染」入口都要过 `renderableLine()`。
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
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'

./gradlew :app:testDebugUnitTest :app:assembleDebug          # 基线 277 tests / 0 failures
./gradlew :app:assembleRelease                               # 含 verifyReleasePayload 红线校验
python tools/gen_syntax_packs.py                             # 重新生成语法包（syntax-packs/ + syntax-packs.zip）

adb -s BIYLBAFQQSS8DA69 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s BIYLBAFQQSS8DA69 shell "logcat -d -s shso-perf"       # 语法就绪/注册日志
adb -s BIYLBAFQQSS8DA69 shell "su -c 'ls -ld /data/adb/shso'"        # 权限应为 777
adb -s BIYLBAFQQSS8DA69 shell "su -c 'grep ^version= /data/adb/modules/shso_guard/module.prop'"
```

---

## 真机与环境备忘

### 真机操作（BIYLBAFQQSS8DA69，PACM00 / Android 10 / 1080×2280）

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
| `README.md` | 功能总览、安全模型、版本规则、在线编译入口 |
| `更新日志.md` | 变更清单，一行一条 |
| `docs/PROJECT.md` | 技术栈、目录结构、架构、安全子系统与已知注意点 |
| `module/shso_guard/README.md` | 守卫模块：策略语法、包装器生成、测试脚本 |
| `.github/workflows/publish-release.yml` | 纯日期标签发布 |
| `.github/workflows/build-apk.yml` | 在线编译（自定义包名） |
