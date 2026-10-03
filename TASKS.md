# shso 任务看板 (TASKS.md)

> 过程记录，不参与对外文档同步；现行说明见 `README.md`、`docs/PROJECT.md`、`更新日志.md`。
> 建立时间：2026-09-11（上一版归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 版本规则

`versionCode` = 构建当日日期（`YYYYMMDD`），`versionName` = `Jinn`，Release Tag 与 `versionCode` 对齐；升级判定只认 `versionCode`。完整规则见 `README.md` § 版本规则。

---

## 当前状态速览（2026-10-03）

| 项 | 值 |
|---|---|
| 分支 | `main`，与 `origin/main` 同步 |
| 许可 | **GPL-3.0-or-later**（2026-10-02 由 Apache-2.0 切换，强 Copyleft） |
| 单元测试 | 340 tests / 0 failures / 1 skipped |
| lint | 0 errors / 31 warnings |
| release 体积 | 2.08 MB，`verifyReleasePayload` 红线通过 |
| 条目预算 | 中央目录**零分配结构遍历**取精确条目数（不信自报字段、不按体积折算） |
| 终端 | 增量 ANSI/OSC 解析、单行渲染上限 4000 字符、一次性命令可中断/流式/保活 |
| 编辑器内核 | Sora Editor 0.23.6（打开即可编辑；语法由外置语法包提供） |
| 语法包 | 62 语言 / 187 扩展名，`syntax-packs.zip`(37KB)，永固直链 tag `syntaxpacks-v2` |
| 执行模型 | 命令与脚本直通执行，无策略判定、无执行记录（守卫/档位/审计已移除） |
| OBB 事务锁 | 单文件 `set -C`(O_EXCL) 原子 CAS；真机 8 进程并发恰好 1 成功；**建目录早于取锁** |
| 真机 | `$DEVICE`（OnePlus PACM00 / Android 10 / 1080×2280 / Magisk root） |
| 已知环境坑 | `adb shell su -c "a; b"` 的 `;` 会让后半段以 shell 用户执行 → 必须 `su -c 'sh script.sh'` |
| 已知环境坑 | Android 的 mksh **不支持**算术展开里的位运算符（`$(( 0600 & 022 ))` 真机实测返回非 0）。判权限位只能用 `find -perm /022` 或 `stat -c %A` 逐位取，别写位与 |
| 已知环境坑 | 设备的 `shared_prefs` 读不到（SELinux 拦 su 直读），且 pager 把四个 Tab 装在同一个 Activity 里 → **无法从设备侧观测外部唤起被接受还是被拒**。这类判定只能靠 JVM 单测覆盖谓词本身；设备侧只能验证「不崩、前台稳定」 |
| `/data/adb/shso` 是 0777 但 **SELinux 拦住第三方**（目录标签 `adb_data_file`）。实测：`shell` 域与 app 域（`run-as` → `u:r:runas_app`，派生自 `untrusted_app`）对该目录的 list / unlink / symlink / write **全部 Permission denied**，dmesg 有对应 `avc: denied { getattr }`；只有 `su`(magisk 域) 能写 |
| 备份 | 守卫相关全部内容备份在 `C:\AI_WORKSPACE\PROJECTS\com.mixradio.droid\守卫模块备份`（基线 `d6c3b0f`） |
| 发版 | tag `20261002`（纯数字，与 `versionCode` 对齐）；双端同名 Release 覆盖旧 APK |

---

## 待办

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

- [x] **P1 · 并发：发布循环的归属令牌在内存模型上不成立**
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

- [!] **未修，如实登记**
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

- [x] **过程教训（已写入本文件）**
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
  - 教训：上一轮据静态阅读把这两条列为 P1，实测均不成立。**涉及守卫层的结论必须真机复现**，
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
- [x] **过程教训（已写入本文件，避免重犯）**
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
  - `[Medium][兼容性]` `ACTION_SEND_MULTIPLE` 与多项 `clipData` 仍只取首项，用户分享多文件时其余项目被静默丢弃；属于既有单文件模型限制，需先确定是否扩展 Hub/页面模型，`MainActivity.kt:147-155`
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
  - `[Medium][兼容性]` 多文件分享仍只取首项；扩展需同时调整 `ExternalOpenHub` 单槽位与 FilePage 消费模型，不能单点改 `MainActivity`。`MainActivity.kt:147-155`
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
  - `[High][安全]` ZIP/TAR/7Z 重复条目仍可覆盖相同输出；`safeDest()` canonical 检查与实际创建/打开之间仍有符号链接 TOCTOU，需原子、无跟随符号链接的写入模型，`ArchiveExtractor.kt:382-479`、`534-548`
  - `[Medium][性能]` ZIP `fileHeaders` 仍在条目上限检查前整体构造，极端 central directory 可能先产生内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` `ACTION_SEND_MULTIPLE` 仍只取首项；修复需扩展 Hub/页面数据模型，不宜单点修改，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通目录、多选、搜索状态旋转丢失，当前未见数据写盘破坏，`FilePage.kt:134-214`
  - 未发现新的终端解析、审计字段注入或守卫卸载路径注入证据；这些不重复计入
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
  - `[Medium][兼容性]` 多文件分享仍只消费首项，需扩展 `ExternalOpenHub`/FilePage 模型，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通导航、多选、搜索状态旋转丢失，当前未发现直接数据写盘破坏，`FilePage.kt:134-214`
  - A18 已修复确认：硬链接改为同目录临时文件 + `mv`；新增的是锁生命周期、事务回滚和真实 OBB 兼容性风险，不重复计入

### A20. 全面 BUG 挖掘（2026-09-30）

  - `[High][异常/可用性]` A18 的锁目录没有陈旧锁恢复；强杀/断电/shell 超时会永久阻塞同一 OBB 目标，且锁目录位于用户可见存储，可能被外部应用预创建造成拒绝服务，`ApkInstaller.kt:254-272`
  - `[High][安全/数据一致性]` OBB 外层安装失败回滚只按路径 `rm -f`，锁覆盖的 OBB 事务已结束后，其他进程仍可能替换目标，回滚会误删新文件；锁需覆盖 OBB 落位到 APK 安装完成，或回滚需验证唯一身份，`ApkInstaller.kt:239-245`
  - `[High][安全]` 解压仍未拒绝重复条目；ZIP/TAR/7Z 后出现同路径条目会覆盖前一条，且写入跟随符号链接，`ArchiveExtractor.kt:382-479`
  - `[Medium][性能]` ZIP `fileHeaders` 在预算检查前整体加载，极端 central directory 仍可能产生内存峰值，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，涉及单槽位 Hub 与 FilePage 模型，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通状态旋转丢失，未发现直接数据写盘破坏，`FilePage.kt:134-214`
  - 未发现新的终端、审计注入或守卫路径注入问题

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
  - `[Medium][兼容性]` 多文件分享仍只消费首项，需扩展 Hub/FilePage 模型，`MainActivity.kt:147-155`
  - 未发现新的终端、审计字段注入或守卫路径注入问题

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
  - `[Medium][兼容性]` 多文件分享只处理首项，需要扩展单槽位 Hub 与 FilePage 模型，`MainActivity.kt:147-155`
  - 未发现新的终端、审计或守卫注入问题

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
  - `[Medium][功能]` 多文件分享仍只处理首项，需扩展单槽位 Hub 与 FilePage 模型。`MainActivity.kt:147-155`
  - 未发现新的终端、审计、守卫路径注入问题

### A28. OBB 回滚身份修复（2026-09-30）

- [x] **修复陈旧锁回收与目标替换回滚**：完成
  - 不再自动 `rm -rf` 陈旧锁；无法确认锁所有权时 fail-closed，避免非原子回收误删他方锁
  - OBB 原子落位后记录目标身份，安装失败回滚前重新读取并比对；身份变化则放弃删除

### A29. 全面 BUG 挖掘（2026-09-30）

  - `[High][可用性/异常]` A28 取消陈旧锁自动回收后，进程强杀、断电或文件系统残留会永久阻塞同一 OBB 安装；安全拒绝误用为永久拒绝服务，`ApkInstaller.kt:296-305`
  - `[High][并发/数据一致性]` OBB 锁只覆盖当前 `installXapk` 协程；锁释放后外层异常/回滚路径与其他安装调用的资源身份仍可能交叉，当前 token 不能覆盖进程死亡后的完整事务，`ApkInstaller.kt:245-260`
  - `[High][安全]` 重复条目检测基于 canonical 路径，仍无法阻止 `safeDest` 检查后目录被替换为符号链接并实际跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍在预算前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享只取第一项，需扩展 Hub/FilePage 模型，`MainActivity.kt:147-155`
  - `[Low][生命周期]` FilePage 普通导航/多选/搜索状态旋转丢失，未见直接写盘破坏，`FilePage.kt:134-214`
  - 未发现新的终端、审计、守卫注入问题

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
  - 未发现新的终端、审计、守卫注入问题

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
  - 未发现新的终端、审计、守卫注入问题

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
  - 未发现新的终端、审计、守卫注入问题

### A38. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发/安全]` A35 原正式锁存在时，先读取 PID/start/created 后 `rm -rf`，再发布临时锁；这不是 compare-and-swap，任何外部替换都可能被删除或被覆盖，`ApkInstaller.kt:303-320`
  - `[High][可用性]` 活锁直接返回失败，没有等待/退避，合法并发 XAPK 安装表现为随机失败；`ApkInstaller.kt:303-305`
  - `[High][安全]` 解压输出继续跟随符号链接，canonical 检查与实际写入之间存在 TOCTOU；重复条目拒绝不改变该结论，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载，预算检查滞后，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`
  - 未发现新的终端、审计、守卫注入问题

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
  - 未发现新的终端、审计、守卫注入问题

### A41. 全面 BUG 挖掘（2026-09-30）

  - `[High][并发/安全]` A39 quarantine 路径由调用方随机生成但未先原子预占；`mv lock quarantine` 成功后直接 `rm -rf quarantine`，外部进程可抢占该名称，导致误删，`ApkInstaller.kt:310-327`
  - `[High][异常/可用性]` A39 `repeat` 重试逻辑使用 `return@repeat`，成功获取锁后仍继续后续迭代、delay 并再次 acquire，可能把自身刚持有的锁判为冲突，`ApkInstaller.kt:205-210`
  - `[High][安全]` 解压仍存在符号链接 TOCTOU，`safeDest` canonical 检查不能保证后续 `FileOutputStream` 不跟随链接，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍整体加载后才检查预算，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理首项，`MainActivity.kt:147-155`
  - 未发现新的终端、审计、守卫注入问题

### A44. 全面 BUG 挖掘（2026-09-30）

  - `[High][文档/功能一致性]` A39 仍标为 `[/]` 未完成，且 A35/A36/A40/A41 的历史描述与当前实现混杂，发布说明可能把已修复、待修复和旧路径混为一谈；需发布前清理任务状态，`TASKS.md:519-544`
  - `[High][并发]` A42 `quarantine` 虽预占目录，但接管后仍将旧锁移入并删除；在 `mv` 与清理之间仍可能被外部替换，当前实现未能真正保证所有权，`ApkInstaller.kt:313-327`
  - `[High][安全]` 解压符号链接 TOCTOU 仍未解决，`safeDest` canonical 校验不等于无跟随写入，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍一次性加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取第一项，`MainActivity.kt:147-155`
  - 未发现新的终端、审计或守卫注入问题

### A45. 全面 BUG 挖掘（2026-09-30）

  - `[High][文档/状态]` A39 仍标记为 `[/]`，而 A42 已标记完成；A31~A44 多处重复记录同一锁问题，当前 TASKS 不能准确表达实际完成状态，发布前容易误读。
  - `[High][并发/安全]` A42 quarantine 仍保留旧锁清理链路，A43/A44 已确认其非原子窗口；当前实现未形成可证明的 compare-and-swap。
  - `[High][安全]` 解压仍以 `FileOutputStream(dest)` 跟随路径写入，符号链接替换可逃逸 canonical 检查；需要无跟随链接的原子文件创建模型，不能靠继续增加路径判断解决。
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才检查条目上限。
  - `[Medium][兼容性]` 多文件分享仍只取第一项。
  - 未发现新的终端、审计字段注入、守卫路径注入

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
  - 未发现新的终端、审计或守卫注入问题

### A46. 全面 BUG 挖掘（2026-09-30）

  - `[High][文档/状态]` A39 仍标记为 `[/]`，A42 已完成；A31~A45 含大量同一锁问题的历史重复记录，当前任务状态无法直接作为发布依据。
  - `[High][安全/一致性]` A39 quarantine 接管链路仍存在外部替换窗口，当前 `mv` 后没有受所有权保护的清理策略，`ApkInstaller.kt:313-329`
  - `[High][安全]` 解压写入依旧跟随 `safeDest()` 路径，canonical 检查无法阻止检查后符号链接替换，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP central directory 仍在预算检查前整体加载，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只取首项，`MainActivity.kt:147-155`
  - 未发现新的终端、审计或守卫注入问题

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
  - 未发现新的终端、审计、守卫注入问题

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
  - 未发现新的终端、审计、守卫注入问题

### A31. 全面 BUG 挖掘（2026-09-30）

  - `[High][可用性]` A30 在锁元数据写入失败时 trap 会自动删除刚创建的锁，但在进程被强杀/断电时又无法安全回收；锁机制在“写入失败”和“崩溃残留”之间没有一致的恢复策略，可能表现为间歇性永久拒绝安装，`ApkInstaller.kt:296-315`
  - `[High][安全/边界]` OBB 回滚身份通过字符串 `path|inode:size:mtime` 传递；虽然当前生成路径不含 `|`，但该隐式格式契约无独立校验，后续路径来源变化可能导致截断或误判，`ApkInstaller.kt:253-337`
  - `[High][安全]` ZIP/TAR/7Z 写入仍跟随符号链接，canonical 检查不是原子安全写；重复条目拒绝不能消除该风险，`ArchiveExtractor.kt:382-494`、`546-561`
  - `[Medium][性能]` ZIP `fileHeaders` 仍整体加载后才做预算检查，`ArchiveExtractor.kt:383`
  - `[Medium][兼容性]` 多文件分享仍只处理第一项，`MainActivity.kt:147-155`
  - 未发现新的终端、审计、守卫注入问题

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

### 真机操作（$DEVICE，PACM00 / Android 10 / 1080×2280）

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
| `README.md` | 功能总览、执行模型、版本规则、在线编译入口 |
| `更新日志.md` | 变更清单，一行一条 |
| `docs/PROJECT.md` | 技术栈、目录结构、架构、执行模型与已知注意点 |
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
      `ExternalTrustAndTempFileTest` 无守卫引用，原样保留。**336 tests / 0 failures / 1 skipped**
- [x] **文档同步**：`README.md`（安全模型 → 执行模型）、`docs/PROJECT.md`（删除「安全子系统」章节）、
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
  **340 tests / 0 failures / 1 skipped**

- [x] **验证**：`:app:lintRelease` 0 errors / 31 warnings；Release 2.08 MB；
      真机 `BIYLBAFQQSS8DA69` 实测脚本执行、终端一次性命令、实时流式回显、
      中文与彩色 Emoji、退出码、`whoami`→`root`、`id -u`→`0`、PATH 无守卫目录，
      全程 `FATAL=0` / `ANR=0`

