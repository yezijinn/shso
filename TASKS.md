# shso 任务看板 (TASKS.md)

> 当前版本：9.0.2/283  
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 🎯 当前主目标
优化整个软件的性能，降低处理器负担，提高整体运行效率与流畅度。所有优化必须以实际热点、可验证收益和行为不回退为前提，效率优先、性能优先。
---

## 📋 任务流水线

### 1. 性能基线与热点定位
- [x] 建立全局性能基线并定位最高成本运行路径
  - 检查项：审计冷启动、文件浏览、终端输出和编辑器大文本操作的主线程 I/O、重复计算、高频重组及未受控协程；按用户可感知影响、风险和修复成本排序。
  - 验证要求：只记录有源码证据的热点；未验证前不得引入分页、缓存或后台常驻等推测性架构。
  - 审计结论：文件浏览与终端输出已通过 IO 调度/批量刷新和有界日志窗口降低主线程压力；编辑器大文本批量转换已移至 `Dispatchers.Default`。当前确认的后续高收益方向是基于实测追踪启动与高频 Compose 重组，暂不引入无证据的缓存或分页架构。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` 与 `./gradlew.bat :app:assembleDebug` 均 `BUILD SUCCESSFUL`；Kotlin LSP 不可用，未操作设备。

### 2. 启动与状态初始化
- [x] 优化冷启动关键路径与首屏状态初始化
  - 前置条件：完成性能基线审计并确认启动链路存在可测量阻塞。
  - 审计结论：`MainActivity.onCreate()` 仅执行 `enableEdgeToEdge`、`AppSettings.getInstance`、`RootService.initSettings` 和 `setContent`；ROOT 探测在首屏建立后由 `LaunchedEffect` 协程触发，未发现主线程 I/O 或同步环境检测阻塞。当前无足够源码证据支持进一步修改，保持无代码变更。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` 与 `./gradlew.bat :app:assembleDebug` 均 `BUILD SUCCESSFUL`；Kotlin LSP 不可用，未操作设备。

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

### 4. 终端输出与长任务界面
- [ ] 优化终端日志吞吐、批量刷新与长任务 UI 更新频率
  - 前置条件：完成性能基线审计并保持输出完整性、取消语义和前台服务契约。

### 5. 编辑器与文本处理
- [ ] 优化编辑器大文本加载、搜索和转换的 CPU 与内存开销
  - 前置条件：完成性能基线审计并保持文本编辑、保存与撤销行为一致。

### 6. 回归验证
- [ ] 运行完整性能回归与构建验证
  - 验证命令：`./gradlew.bat :app:testDebugUnitTest`、`./gradlew.bat :app:assembleDebug`；在用户人工设备验证时记录可感知流畅度结果。

### 已完成：文本编辑器批量文本处理
- [x] 优化文本编辑器批量文本处理的线程调度
  - 检查项：删除空行、整体缩进两格、删除所有换行不得在主线程同步处理大文本；处理期间保留编辑状态一致性，完成后关闭设置窗口。
  - `TextEditorDialog.kt`: 三个批量文本操作通过 `Dispatchers.Default` 计算，回到 Compose 主线程后更新文本、保持未保存状态并关闭设置；处理期间显示“处理中…”并禁用重复点击。
  - 验证：`./gradlew.bat :app:testDebugUnitTest` 与 `./gradlew.bat :app:assembleDebug` 均 `BUILD SUCCESSFUL`；Kotlin LSP 未配置；未执行设备操作。
