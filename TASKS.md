# shso 任务看板 (TASKS.md)

> 当前版本：9.0.2/283  
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 🎯 当前主目标

**性能优化**：优化整个软件的性能，降低处理器负担，提高整体运行效率与流畅度。所有优化必须以实际热点、可验证收益和行为不回退为前提，效率优先、性能优先。（任务 1–7 已完成。）

**安全链路进化**：对「终端洪流 / 守卫链路 / 守卫模块 / 安全挡位」四块做优化、完善与进化。（任务 8 守卫模块本体已完成；9 / 10 / 11 待开工。）

---

## 📋 任务流水线

### 1. 性能基线与热点定位
- [x] 建立全局性能基线并定位最高成本运行路径
  - 检查项：审计冷启动、文件浏览、终端输出和编辑器大文本操作的主线程 I/O、重复计算、高频重组及未受控协程；按用户可感知影响、风险和修复成本排序。
  - 验证要求：只记录有源码证据的热点；未验证前不得引入分页、缓存或后台常驻等推测性架构。
  - 审计结论：文件浏览与终端输出已通过 IO 调度/批量刷新和有界日志窗口降低主线程压力；编辑器大文本批量转换已移至 `Dispatchers.Default`。当前确认的后续高收益方向是基于实测追踪启动与高频 Compose 重组，暂不引入无证据的缓存或分页架构。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` 与 `./gradlew.bat :app:assembleDebug` 均 `BUILD SUCCESSFUL`。

### 2. 启动与状态初始化
- [x] 优化冷启动关键路径与首屏状态初始化
  - 前置条件：完成性能基线审计并确认启动链路存在可测量阻塞。
  - 审计结论：`MainActivity.onCreate()` 仅执行 `enableEdgeToEdge`、`AppSettings.getInstance`、`RootService.initSettings` 和 `setContent`；ROOT 探测在首屏建立后由 `LaunchedEffect` 协程触发，未发现主线程 I/O 或同步环境检测阻塞。保持无代码变更。
  - 补充实测（2026-09-10，真机可用后补测）：`am start -W` 冷启动三次 = **4422 / 4424 / 4410 ms**，且 `TotalTime == ActivityTaskManager: Displayed`（首帧即全部耗时，非应用内阻塞）。逐点核查 `onCreate` 路径确认无同步磁盘/进程 I/O：`AppSettings` 构造仅读一次 `SharedPreferences`（键值均为小对象）；`RootService.initSettings → refreshPristineBanner → HyperCore.generateEngineBanner` 内的 `detectEnvironmentInfo()` / `detectKernelInfo()` 为**纯内存**（`Build.SUPPORTED_ABIS` / `Build.VERSION.*` / `System.getProperty("os.version")`），不执行任何 shell 或文件读取。
  - 结论：该耗时是 **debug 包固有开销**——`debuggable=true` 使 ART 走 JIT/解释执行而非 AOT，叠加 `isMinifyEnabled=false`（release 亦未开 R8）在 ColorOS 上的类加载/校验成本，**非应用源码热点**，故不做源码改造。若要显著改善冷启动，正确杠杆在**构建配置**（release 开启 R8/minify + profile 引导 AOT），而非应用代码。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` 与 `./gradlew.bat :app:assembleDebug` 均 `BUILD SUCCESSFUL`。

### 3. 文件浏览与列表渲染
- [x] 优化目录加载、排序过滤与文件列表渲染成本
  - 前置条件：完成性能基线审计并只处理确认的 I/O、分配或重组热点。
  - 变更（均为已确认热点，未引入分页/缓存等推测性架构）：
    - `FileListViewSettings.kt`：`applyFileViewSettings` 的名称排序键改为排序前一次性预计算（O(N) 次 `lowercase`），替代比较器内逐次求值（原实现每次比较都新建临时字符串，总分配量 O(N log N)）；目录/文件分组排序抽为私有助手 `sortedForView`。
    - `FilePage.kt`：`displayFileList` 从 `remember` 组合期同步计算改为状态 + `Dispatchers.Default` 后台计算，组合期不再做 O(N log N) 排序；`refresh()` 改为先算完再连续写 `fileList`/`displayFileList`（两次写入之间无挂起点，不产生「新目录列表 + 旧排序结果」的中间帧）；新增视图偏好（隐藏文件/排序）变更时的后台重算 effect。
    - `RootFileManager.kt`：`listFiles` 移除「目录在前 + 名称升序」的重复排序——其结果必被 UI 层 `applyFileViewSettings` 覆盖，且比较器内同样存在逐次 `lowercase`；仅保留 `distinctBy` 去重，并删除因此不再使用的 `Locale` 导入。
  - 新增测试：`app/src/test/java/com/mixradio/droid/ui/components/FileListViewSettingsTest.kt`（6 例）锁定「目录恒在前、名称/时间升/降序、隐藏文件过滤、空输入」契约，防止本次重构改变行为。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` → **90 tests / 0 failures**；`./gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`；Debug APK 已安装至 BIYLBAFQQSS8DA69，uiautomator 核验文件页列表正常渲染（目录在前、名称不区分大小写升序）、进入 `Download` 子目录刷新正常、进程存活无异常。启动未受影响（`HorizontalPager` 无 `beyondViewportPageCount`，启动不组合文件页）。
  - 契约说明：`RootFileManager.listFiles` 现返回「去重、无序」结果，展示顺序一律由 UI 层决定；4 处调用方或自行排序、或仅取名称集合，行为不变。

> 并行说明：2026-09-10 用户要求「并行推进、同时修复多个功能」，故 4 / 5 / 7 三条**文件集互不重叠**的改造线同时开工（终端链路 / 编辑器链路 / 主页与全局），6 为统一回归收口。**三条线均已完成，并由独立验证代理（fresh eyes）对抗性复核通过。**

### 4. 终端输出与长任务界面
- [x] 优化终端日志吞吐、批量刷新与长任务 UI 更新频率
  - 前置条件：完成性能基线审计并保持输出完整性、取消语义和前台服务契约。
  - 已确证热点（并行审计，均带源码证据）：
    - `TerminalPage.kt:98` `remember(outputLog) { AnsiParser.parseAnsi(整段 250k) }` 每次 flush 全量正则扫描并构建整段 AnnotatedString；
    - `TerminalPage.kt:294` 用单个 `Text` 渲染整段 250k 字符 → 每帧全量文本 layout（最大开销，直接掉帧）；
    - `HyperCore.kt:117` `appendWithSlidingWindow` 的 `currentLog + newText` 每次 flush 全量拷贝 250k；
    - `HyperCore.kt:86` flush 循环固定 16ms 且跑在 `Dispatchers.Main`；
    - `TerminalPage.kt:112` 自动滚动 effect 以 `outputLog.length` 为 key，每次 flush 取消并重启协程。
  - 改造（A 线，最终落地）：
    - `AnsiParser.kt`：`ParsedAnsiResult` 由「整段 `AnnotatedString` + `plainText`」改为**按行** `List<AnnotatedString>`；新增局部 `emit(segment, style)` 助手做单遍 O(N+S) 构建（维护 `lineTexts` / `lineStyles` 每行相对偏移），跨行 SGR 状态机（颜色/加粗/256 色/truecolor）完整保留。`plainText` 改为 `by lazy`——它只在「复制输出」时被读取，避免每次 flush 都拼接 250k。CR 归一化改为**仅当 `raw.indexOf('\r') >= 0` 时才执行**（原为无条件两次全量 `replace`），逻辑逐字等价、**无行为变化**。
    - `TerminalPage.kt`：解析移出主线程——`snapshotFlow { RootService.outputLog }.conflate().collect { withContext(Dispatchers.Default) { parseAnsi(...) } }`（`conflate` 保证同一时刻只有一个解析在跑，中间值直接丢弃）；首帧仍同步解析一次，保证进入终端页即有内容、无空白闪烁。渲染由「`Column(verticalScroll)` + 单个 `Text` 渲染整段文本」改为 `LazyColumn { items(parsedOutput.lines) }` 按行懒布局。自动滚动由 `derivedStateOf { !canScrollForward }`（有缺陷：新内容一追加 `canScrollForward` 立刻变 true，会被误判「用户已向上回看」而**永久停滚**）改为 `followTail` 意图位——只在滚动进行中采样用户真实落点，并在回到底部时重新激活；key 改用 `parsedOutput.lines.size` 而非字符串长度，避免每次 flush 取消并重启协程；滚动方式改 `scrollToItem`（不再 `animateScrollTo` 造成每帧动画 churn）。
    - `HyperCore.kt`：flush 循环保留 16ms tick 排空队列（防止队列无界增长），但改为累积到 `pending` StringBuilder，**仅当距上次发布 ≥250ms 或累积 ≥400_000 字符**才发布一次；`finally` 中兜底 flush 残留文本（防任务退出丢日志）。
  - **实测收益（真机 BIYLBAFQQSS8DA69，固定洪流负载 64k 行彩色输出）**：

    | 指标 | 改造前 | 改造后 | 变化 |
    |---|---|---|---|
    | 整机 CPU 峰值 | 170% | **67.2%** | **−60%** |
    | 主线程占用 | ~76% | **48.0%** | −37% |
    | 渲染线程 | — | 2.7% | — |
    | 帧耗时 50th | 350ms | **69ms** | **−80%** |

  - 过程性关键结论（均为**实测**，非推测）：① 「每行一个 AnnotatedString + 48ms 节流」首版**反而比基线更差**（CPU 231%、RSS 1.8G vs 505M）——按行建 `AnnotatedString` 的对象分配量高于单个大 `AnnotatedString`；② 把解析移出主线程是流畅度最大功臣（帧 50th 350ms → 38ms，约 9.2×）；③ 逐线程归因（`/proc/<pid>/task/*/stat`）证明主线程成本**与「发布次数」成正比、与「item 数」几乎无关**——把 item 从 3570 降到 60 几乎不改变占用，故最终杠杆是**降低发布频率**而非减少行数；④ 隔离实验：终端页不可见时整机 CPU 仅 17.8%（主线程 10.7%），即**终端页约占全部负载 82%**。以上结论推翻了「按行懒渲染就能解决」的初始假设，最终以「发布节流 + 解析移出主线程」收敛。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` → **100 tests / 0 failures**；`./gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`；真机安装运行无崩溃（`logcat -b crash` 为空），终端页正常渲染 HyperCore 横幅与命令输出。

### 5. 编辑器与文本处理
- [x] 优化编辑器大文本加载、搜索和转换的 CPU 与内存开销
  - 前置条件：完成性能基线审计并保持文本编辑、保存与撤销行为一致。
  - 已确证热点：`TextEditorDialog.kt:255` 语法高亮在 `VisualTransformation` 内每帧对全文重算；`:167` 统计 `derivedStateOf` 每键在主线程全量 3 遍扫描；`:846` 行号列非惰性且随每次按键重建全部行号节点；`:1042` 查找匹配数用 `split` 全量分配；`TextStatistics.kt:30` 最长行 `split('\n')` 全量分配。
  - 改造（B 线，最终落地）：
    - `TextEditorDialog.kt` 统计：由 `derivedStateOf`（组合期读时同步全量扫描）改为 `LaunchedEffect(contentValue.text)` + `withContext(Dispatchers.Default)` 异步计算，主线程不再承担统计扫描。
    - `TextEditorDialog.kt` 语法高亮：由「`VisualTransformation` lambda 每次渲染内部全量重跑 `CodeHighlighter`」改为组合层 `remember(contentValue.text, language)` 记忆化，仅在文本或语言变化时重算；超长文本（>100k 字符）降级纯文本的既有策略保持不变。**陈旧文本风险已排查排除**：`BasicTextField(value = value)` 的 `value` 直接来自 `contentValue`（`TextEditorDialog.kt:375 → :874`），无本地草稿态，故 Compose 契约保证 `filter()` 收到的文本恒等于 `contentValue.text`。
    - `TextEditorDialog.kt` 行号列：抽为私有独立 Composable `EditorLineNumbers(lineCount, fontSize, scrollState)`，参数稳定故仅真正增删行时才重组，不再随每次按键重建全部行号节点；与编辑区共享同一 `scrollState`，纵向同步滚动行为不变。
    - `TextEditorDialog.kt` 查找匹配数：由 `text.split(findText).size - 1`（全量 List/子串分配）改为 `indexOf` 循环无分配计数，步进 `idx + findText.length`，与原 `split` 的非重叠计数语义一致（含尾随分隔符情形）。
    - `TextStatistics.kt` 最长行：由 `text.split('\n').maxOfOrNull { it.length }`（全量分配整行列表）改为并入既有单遍 `while` 扫描（`currentLen` / `maxLine` 结算，循环后补算尾部段）。
  - 新增测试：`app/src/test/java/com/mixradio/droid/data/AnsiParserLinesTest.kt`（5 例：尾随空行、SGR 重置、跨行颜色保持、CRLF 归一化、空输入）、`app/src/test/java/com/mixradio/droid/data/TextStatisticsColumnsTest.kt`（5 例：多行列宽、尾随换行、空输入、中英混排、CRLF）。其中 `TextStatistics` 用例**当场抓出一个真实缺陷**：首版单遍扫描在 `'\n'` 分支 `continue` 前漏掉 `symbols++`，导致符号数少算 1/行，已修复并由用例锁定——这正是补回归用例的价值所在。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` → **100 tests / 0 failures**；`./gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`；真机可打开文件、行号与统计正常、无崩溃。

### 6. 回归验证
- [x] 运行完整性能回归与构建验证
  - 验证命令与结果（2026-09-10，真机 BIYLBAFQQSS8DA69，Android 10 / ColorOS）：
    - `./gradlew.bat --stop && ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`；且 `compileDebugKotlin` 与 `testDebugUnitTest` 均为**真实执行（非 UP-TO-DATE）**——先 `--stop` 规避陈旧 daemon 的 `CreateProcess error=740` 假通过（UP-TO-DATE 的测试任务会掩盖该故障）。
    - 单测 **100 tests / 0 failures / 0 errors / 0 skipped**（基线 90，本次新增 10）。逐类：`SecurityCoreTest` 63、`RootFileManagerEscapingTest` 12、`FileListViewSettingsTest` 6、`FilePermissionDialogTest` 6、`AnsiParserLinesTest` 5、`TextStatisticsColumnsTest` 5、`TextEditorTextTransformTest` 3。
    - **独立验证代理（fresh eyes，未参与实现）对抗性复核**，逐一给出结论与代码证据：B1 任务退出丢日志（`finally` 兜底 `pending`，无任何循环退出路径可跳过 `onFlush`）→ SAFE；B2 CR 语义 → SAFE（无字符丢失，但见下方已知行为）；B3 `plainText` 尾随换行（`"a\nb\n"` → 3 行、`plainText == "a\nb\n"`）→ SAFE；B4 `'\n'` 计入 `symbols` → SAFE；B5 自动滚动回到底部能否重新激活 → SAFE（`scrolling == true` 且 `canScrollForward == false` 时复位 `followTail`）。
    - 真机功能冒烟：冷启动直进主页（无加载闪屏）；主页 `立即执行` / `shso 目录文件` / `ElapsedRunningTimeText` 正常；终端页渲染 HyperCore 横幅与命令输出；文件页列表与滚动正常；编辑器动作对话框「编辑文本」可达；`logcat -b crash` 无堆栈；进程存活。
  - 已知既有行为（**非本次引入**，改造前后完全一致）：`AnsiParser` 把孤立 `\r` 归一化为**换行**，与真实终端的「回车原地覆盖」语义不同——进度条型输出（如 `10%\r20%\r30%`）会堆叠成 3 行而非原地刷新。原代码即是**无条件** `replace('\r','\n')`，本次仅加了「含 `\r` 才处理」的快速路径，故行为逐字等价，且已被 `AnsiParserLinesTest.crlfAndLoneCrNormalized` 锁定。改为原地覆盖可同时提升终端保真度并显著削减进度条场景的日志体积（间接降 CPU），但会改变已测试的展示契约，**属推测性收益（尚无实测证据表明其为现实热点），按本项目「只做有源码证据的热点」铁律暂缓，待用户确认后再做**。

### 7. 主页与全局重组
- [x] 降低主页每秒整页重组与全局列表重绘成本
  - 已确证热点：`HomePage.kt:116` 每秒写 `elapsedSeconds` 触发**整页重组**，且 `:370` 文件列表为内联 `forEachIndexed`（非惰性、无 key），随重组全量重绘所有行；`:99` 排序比较器内逐次 `lowercase`（比较器内 `it.name.lowercase()` 共 O(N log N) 次临时分配）。
  - 改造（C 线，最终落地）：
    - `elapsedSeconds` 计时抽为私有独立 Composable `ElapsedRunningTimeText()`：每秒变化只重组该节点，不再波及主页文件列表；计时逻辑**逐字迁移**（`while (isTaskRunning)` + `if (start > 0)` 更新，未加归零、未设 `else` 分支），行为严格不变——该节点仅在有任务进行中时才出现在组合树内，故唯一收益就是重组范围收敛。
    - 文件行抽为私有独立 Composable `ShsoFileRow(fileItem, isSelected, listFontSize, listSecondaryFontSize, onSelect)`，提升跳过性；行内点击语义（目录→进入、文件→回填路径并清错）与按钮「选择/已选择」完全等价迁移。
    - 排序键改为排序前一次性预计算 `FileItemSortKey(item, item.name.lowercase())`，替代比较器内逐次 `lowercase`（原为 O(N log N) 次临时字符串分配）；排序结果（目录恒在前 + 名称升序）不变。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` → **100 tests / 0 failures**；`./gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`；真机主页渲染正常（`立即执行`、`shso 目录文件`、文件行名称+大小）。

### 8. 守卫模块 shso_guard（运行时 L2 守卫）
- [x] 加固 shso_guard：修复静默失效与绕过路径、扩大覆盖面、消除调用开销
  - 源码位置：仓库外 `../shso_guard/`（**当前不在任何 git 仓库内**，仅构建产物 zip 随仓库提交——见文末待决事项）。提交：`2a43034`。
  - **P0 静默 fail-open**：`policy.conf` 若为 CRLF 行尾（Windows 记事本编辑，极常见）、或写成 `protect = /system`（等号带空格）、或行尾带 `# 注释`，`protect` 规则会**整条静默失效**——守卫在跑、审计照记 ALLOW，却一条都拦不住。已改为纯 POSIX 参数展开解析（`[:space:]` 天然含 `\r`），`mode` 取非法值一律回退 `enforce`。
  - **P0 符号链接绕过（真机复现）**：原实现对**整条路径**调 `realpath`。当叶子尚不存在时（`cp f <link>/new`、`rm -rf <link>/x`）`realpath` 失败返回空，代码退回纯词法判定，于是 `<link>/x` 被判 ALLOW，而内核实际操作的是**链接目标**（实测 `link -> /system` 时 `cp`/`rm`/`sed` 均未被拦截）。修法：逐级扫描分量定位**第一处符号链接**，只解析该链接自身，再词法接回其余分量；无法解析时按 `PATH_UNRESOLVABLE` 拒绝。逐级 `[ -L ]` 全为 shell 内建，零子进程。
  - **P1 sed 误杀**：`sed` 的脚本参数曾与路径一并送审，`sed -i '/system/d' f` 会因脚本串形似绝对路径被误拦。现按 POSIX sed 规则跳过 `-e`/`-f` 取值与首个非选项参数。
  - 覆盖面扩展：新增 `mv`（判源+目标）/ `cp`（判目标）/ `find`（仅 `-delete` 或 `-exec rm` 时介入）/ `sed`（仅 `-i` 时介入）守卫；新增 `toybox` / `busybox` **多二进制派发**（堵住 `toybox rm -rf /` 这类绕过）；`fastboot` 支持 `-w`/`--wipe` 及任意位置子命令；`dd` 支持 `of =x` 空格变体；`wipe` 无操作数视为最危险形态直接拒绝；`find_real` → `exec_real`（直接 exec，省一个子 shell）；递归深度守卫 + 缺失二进制 fail-closed + 审计轮转改每进程唯一临时名。
  - **性能：修复一处严重回归（本次最大发现）**。`load_policy` 与路径判定位于**每次调用**的热路径，而旧实现逐行 `printf | tr -d '\r'` + `$(trim_ws ...)`，单次 `rm`/`cp` 调用创建约 **150 个外部进程**。真机同设备交替 A/B（各 60 次 `cp`）：

    | 场景 | 单次耗时 |
    |---|---|
    | 裸 `cp`（无守卫） | 17.7 ms |
    | 优化前守卫 `cp` | **2656 ms** |
    | 优化后守卫 `cp` | **60.7 ms** |

    **43.8× 提速**。手法：① 策略解析改纯参数展开；② `judge` / `normalize_path` / `guard_operand_mode` 由 `$( )` 返回值改为**全局变量返回**（命令替换本身即 fork 子 shell）；③ 无符号链接分量时**跳过 realpath**。重构后单次调用只剩 2 个外部进程（审计 `date` + 真实二进制本身）。
  - 长期不变式：新增 `gen_wrappers.py`，由 `guard-template.sh` 生成 16 个守卫，保证「除 `__CMD_NAME__` / `__OPERAND_MODE__` 两处占位符外逐字节一致」，已加入循环校验。
  - README「能力边界」改为**诚实表述**：明确列出拦不住的情形——绝对路径调用（`/system/bin/rm`）、`command -p`、脚本自行改写 `PATH`、shell 重定向（`: > /system/build.prop`）、同进程内完成破坏的实现（`python -c 'shutil.rmtree(...)'`）、有 root 即可改配置与日志。删去原「任何调用路径都拦」的误导性表述。
  - 验证：`sh -n` 全通过；**对抗性冒烟 37/37**（策略解析变体 / toybox-busybox / 新覆盖面 / fastboot-dd-wipe / **误杀检查** / 递归与缺失二进制 / 审计留痕）；真机（BIYLBAFQQSS8DA69, Android 10, Magisk）**符号链接绕过 8/8 + 正常路径不误杀 5/5 + sed 误杀回归 5/5**；重打包 zip 后解包内容与源码逐字节一致，且用解包出的守卫**复跑 37/37**。

### 9. 守卫链路（App 侧集成）
- [x] 把守卫接入 App 的全部 root 执行路径，并消除审计盲区
  - **守卫 PATH 注入已核实生效（真机证据）**：把探针脚本 `/data/adb/shso/probe.sh`（内含 `echo "APP_PATH=$PATH"` 与 `which rm`）经主页「立即执行」跑起来，终端输出为
    `APP_PATH=/data/adb/modules/shso_guard/guard:/sbin:/system/sbin:/system/bin:/system/xbin`
    `which rm` → `/data/adb/modules/shso_guard/guard/rm`
    即守卫目录在 PATH **首位**、`rm` 解析到守卫包装器而非真实二进制。`RootService` 三处构造 root 命令的位置（脚本执行 `executeFile`、终端一次性命令 `sendInput` 非交互分支、常驻任务 shell 启动）均已前置守卫目录，交互态输入因写入同一常驻 shell 而天然继承守卫 PATH。
  - **守卫不可用不再硬阻断**（原实现档位 ≥2 且守卫缺失时**拒绝一切 root 执行**，连 `ls` 都不行，导致默认档位 2 实际不可用）。改为：落 `GUARD_UNAVAILABLE_DEGRADED` 审计 + 终端首次醒目告警后**放行**（仅静态审查保护）。
  - **`RootFileManager` 危险操作接入门禁**（此前删除/改名/移动/改权/改属在**全部四个档位**下既不过策略也不落审计）：
    新增 `guardDestructiveOp()`，档位 0 不判定不审计（与旧版逐字节一致）、档位 1 落 ALLOW 审计、档位 2/3 走 `RootCommandGateway` 完整判定（Block → 拒绝并落 BLOCK 审计；Confirm → 记 CONFIRM 后放行），并在 `rm`/`mv`/`chmod`/`chown` 命令前注入守卫 PATH 前缀。
  - **守卫自动安装 + 版本升级**：档位 ≥2 时用 APK 内置 `shso_guard.zip` 静默安装。
    - **修复「永不升级」缺口**：原实现只要 `guard/rm` 存在就认为「已就绪」直接返回，已装过旧版的用户**永远拿不到 APK 内置的新版**（本轮新增的 toybox/busybox/mv/cp/find/sed 与 P0 修复全部拿不到）。真机确认：升级前设备上是 v1.0.0 的 13 个旧包装器，改后自动重装为 v1.1.0 的 18 个包装器。现比对 APK 内置 `module.prop` 的 `version=` 与已装版本，不一致即重装。
    - **修复安装并发竞态**：一次档位变更会被 `MainActivity` 与 `SettingsPage` **同时**触发 `ensureInstalled`，两个 `install()` 并发 `rm -rf $MODULE_DIR` + `cp -R` 互相破坏 —— 真机审计日志实锤 `GUARD_AUTO_INSTALL_FAILED | 安装校验失败（guard/rm 不可执行）` 后第二次才成功。已用 `Mutex` 串行化 + 持锁后双重检查；修复后清空模块重启，审计日志只有 1 条 `GUARD_INSTALL`，**0 条 FAILED**。
  - 验证：真机（BIYLBAFQQSS8DA69, Android 10, Magisk v30.7）档位 0→1→2 全程无崩溃，进程存活。

### 10. 安全挡位 0-3 落实度
- [x] 打通档位 3 的专属能力并消除「声称但未实现」
  - **`runAsRoot` / `riskApproved` 死代码已打通**：`ExecuteConfirmDialog.onConfirm` 的签名本就带 `runAsRoot`，但 `FilePage` 与 `HomePage` 的回调把它丢掉了。现两处均改为 `(path, runAsRoot, riskApproved)` 并透传到 `RootService.executeFile`，档位 3 的「脚本默认非 Root + 用户可勾选以 Root」真正生效（档位 <3 保持 Root 默认，行为不变）。自动执行链路（添加到 shso 后自动执行）显式传 `riskApproved=false`，仍会扫描脚本内容。
  - **EXECUTE 闸门收归档位 3**：原条件 `scanEnabled && hasCritical`（档位 ≥2）使**默认档位 2 也强制打字**，与文档语义不符。现抽为纯函数 `needTypedExecuteConfirm(level, hasCritical)`，仅档位 3 要求输入 EXECUTE，档位 2 普通确认即可。真机确认：档位 2 执行脚本时确认框无打字框、`Root 权限` 行显示「是（将以 Root 权限执行）」。
  - **档位变更即时生效**（原需重启/重装）：设置页切档位时 ① `invalidateReadyCache()` 失效 60s TTL 的「守卫就绪」缓存；② 档位 ≥2 时触发 `ensureInstalled`；③ `syncPolicyMode()` 把档位写成 `policy.conf` 的 `mode`。真机实测档位 0→1 → `mode=log`，1→2 → `mode=enforce`，**无需重启**。
  - **新增 `SecurityTierSemanticsTest`（12 用例）**锁定：守卫 PATH 注入的档位口径、自动安装的档位门槛、档位→mode 映射、EXECUTE 闸门档位、文件操作门禁档位。其中一条用例当场抓出真 bug——`policyModeFor` 原写 `securityLevel <= OFF → "off"`，越界脏值会被映射成**完全放行**（fail-open），已改为仅精确匹配 0/1，其余一律 `enforce`。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` → **112 tests / 0 failures**（基线 100，+12）。

### 11. 终端洪流进化
- [ ] `\r` 原地覆盖语义 + 增量解析
  - 现状（既有行为，非本轮引入）：`AnsiParser` 把孤立 `\r` 归一化为**换行**，进度条型输出（`10%\r20%\r30%`）堆叠成多行而非原地刷新；解析为**全量重扫描**（每次 flush 重扫整个 250k 窗口）。
  - 目标：① 实现真实终端的「回车原地覆盖」；② 解析改为**增量**（只解析新增尾部，跨边界携带 SGR 状态；整体替换时回落全量）；③ `appendWithSlidingWindow` 改为**先裁剪后拼接**，避免每次发布两次大拷贝（该函数运行在主线程）；④ 补「增量与全量解析等价」的回归用例。
  - 状态：待开工（`\r` 语义会改变已锁定的展示契约，用户已确认要做）。

---

### 已完成：文本编辑器批量文本处理
- [x] 优化文本编辑器批量文本处理的线程调度
  - 检查项：删除空行、整体缩进两格、删除所有换行不得在主线程同步处理大文本；处理期间保留编辑状态一致性，完成后关闭设置窗口。
  - `TextEditorDialog.kt`: 三个批量文本操作通过 `Dispatchers.Default` 计算，回到 Compose 主线程后更新文本、保持未保存状态并关闭设置；处理期间显示“处理中…”并禁用重复点击。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` 与 `./gradlew.bat :app:assembleDebug` 均 `BUILD SUCCESSFUL`。

---

## ⚠️ 待决事项（需人工确认）

1. **`shso_guard/` 源码没有任何版本控制**。当前 git 仓库只有 `shso-main/`，而守卫模块源码（`common.sh` 约 500 行安全关键脚本）+ `docs/` 都在仓库外的 `../` 下。也就是说：**加固后的守卫源码目前只存在于本机磁盘**，一旦丢失无法从仓库恢复，只有构建产物 `shso_guard.zip` 在版本控制内。建议把模块源码纳入仓库（例如 `shso-main/module/shso_guard/` 作为唯一源，构建脚本从那里读取），或单独给工作区根目录建一个仓库。
2. **仓库根目录堆积 53 张 QA 截图 + 2 个 UI dump**（`home.png` / `terminal_after_enter.png` / `jump_*.png` / `editor*.png` / `ui_*.xml` 等），均为历次 adb 测试产物。本轮已通过 `.gitignore` 的 `/*.png` 与 `/ui_*.xml` 阻止其入库，但**文件本身仍在磁盘上**。是否需要清理或归档到 `build/screenshots/`，请确认。
3. **`TASKS-old-20260910113039.md` / `TASKS-old-20260910172800.md`** 为历史看板快照，已被跟踪入库。若无保留价值可一并清理。
