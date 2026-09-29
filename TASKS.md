# shso 任务看板 (TASKS.md)

> 过程记录，不参与对外文档同步；现行说明见 `README.md`、`docs/PROJECT.md`、`更新日志.md`。
> 建立时间：2026-09-11（上一版归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 版本规则

`versionCode` = 构建当日日期（`YYYYMMDD`），`versionName` = `Jinn`，Release Tag 与 `versionCode` 对齐；升级判定只认 `versionCode`。完整规则见 `README.md` § 版本规则。

守卫模块独立版本：当前 v1.3.1（`module.prop` 的 `version=`）。新增或修改包装器、`common.sh` 后必须按序执行：重新生成包装器 → 重打包 `assets/shso_guard.zip` → 同步 `GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES` → 升 `module.prop` 版本，否则已装用户不会升级。

---

## 当前状态速览（2026-09-22）

| 项 | 值 |
|---|---|
| 分支 | `main`，与 `origin/main` 同步 |
| 单元测试 | 289 tests / 0 failures / 1 skipped |
| release 体积 | 2.09 MB，`verifyReleasePayload` 红线通过（≤2.2MB、无语法包、无 `tables/`） |
| 终端 | 增量 ANSI/OSC 解析、单行渲染上限 4000 字符、一次性命令可中断/流式/保活 |
| 编辑器内核 | Sora Editor 0.23.6（打开即可编辑；语法由外置语法包提供） |
| 语法包 | 62 语言 / 187 扩展名，`syntax-packs.zip`(37KB)，永固直链 tag `syntaxpacks-v2` |
| 守卫模块 | v1.3.1（真机已装并验证拦截） |
| 真机 | BIYLBAFQQSS8DA69（PACM00 / Android 10 / 1080×2280 / 底部导航 y=2156） |
| 当前安全档位 | 设备上为 0（验证拦截需切到 ≥2） |

---

## 待办

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
