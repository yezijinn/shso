> 语言 / Language: [English](AGENTS.en.md)

# shso — AI 协作准则

Android ROOT 环境下的图形化脚本与原生程序执行工具。Kotlin + AndroidX Compose
Material 3 原生控件，极光玻璃暗色主题，无外部 UI 组件库。
包名 `com.mixradio.droid`，版本名 `Jinn`，版本号为构建当日日期。
冷启动直接进入主页四 Tab（主页 / 终端 / 文件 / 设置），无启动检测流程。

## 行为准则

### 1. 任务驱动闭环

以 `TASKS.md` 状态机为准，不跨步骤无序修改。

- `TASKS.md` 是任务进度的唯一依据：执行前先读，只推进当前任务，不自行扩大范围。
- 状态标记：`[ ]` 待处理、`[/]` 进行中、`[x]` 已完成并自测通过、`[!]` 受阻或需人工确认。
- 执行生命周期：
  1. Read & Lock：优先恢复 `[/]` 任务；否则取第一个 `[ ]` 并就地改为 `[/]` 锁定。
  2. Execute：只改动该任务关联的文件。涉及 3 个以上核心文件时，先在任务下追加
     二级子任务拆细再动手。
  3. Verify：执行针对性验证（编译、单元测试或语法检查）。
  4. Write Back：验证通过后改为 `[x]`，缩进 2 格简要记录变更文件与产物。
     禁止全量重写 `TASKS.md`，只做局部增量修改。
- 两轮熔断：同一报错连续修复 2 次未果即置为 `[!]`，在任务下记录核心异常与排查推论，
  停机等待指示。

### 2. 先思考后编码

不假设、不隐藏困惑、展示权衡。

- 开发前先读项目文档，按场景导航表按需定位，只读与任务相关的部分。
- 先按文档执行；文档未覆盖时再搜索代码或查阅资料。
- 不确定时先提问，不猜测；存在多种理解时列出所有可能。
- 有更简单的方案就提出来；搞不清楚就停下并说明卡点。

### 3. 简单优先

用最小代码解决问题，不做推测性设计。

- 不做需求范围外的功能；单次使用的代码不做抽象。
- 不加未要求的灵活性与可配置性；不为不可能的场景写错误处理。
- 相同逻辑出现 2 次以上时考虑抽象，并说明取舍。

### 4. 最小改动

只改必须改的部分，清理自己引入的问题。

- 不顺手优化无关代码，不重构没坏的东西。
- 遵循现有风格，即使不认同。
- 发现无关死代码时提出，不自行删除。

### 5. 目标驱动

先定义成功标准，再循环验证。

- 加验证 → 先想清楚什么叫通过，再实现。
- 修 bug → 先找到最小复现路径，再修复。
- 重构 → 确保行为不变，用 diff 辅助验证。
- 多步任务先列计划：`1. [步骤] → verify: [检查]`。

## 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.4.0（JVM Toolchain 21） |
| UI | AndroidX Compose Material 3（compose-bom 2026.08.00，`ui/theme/Aurora*`） |
| 构建 | Gradle KTS + AGP 9.2.1 + Version Catalog（`gradle/libs.versions.toml`） |
| 目标 | minSdk 26 / targetSdk 35 / compileSdk 37，applicationId `com.mixradio.droid` |
| 依赖注入 | 无框架，全局 `object` 单例（`RootService`、`AppSettings` 等） |

**自包含工程**：不依赖仓库外源码或模块，clone 后可直接构建。
`settings.gradle.kts` 仅 `include(":app")`。UI 样式统一走 `ui/theme/` 的
Aurora 令牌（`AuroraTokens` / `AuroraGlass` / `AuroraComponents`），
页面禁止写装饰性 `Color(0x...)` 字面量。

**UI 形态（改动必守）**

- 全工程零圆角：Card / Button / TextField / 弹窗 / 面板 / 状态点 / DockBar 一律直角矩形。
  实现上由 `AuroraShapes`（M3 Shapes 五槽位全 `RoundedCornerShape(0.dp)`）注入
  `MaterialTheme`，显式 `clip` / `shape` / `shadow` / `border` 同样使用
  `RoundedCornerShape(0.dp)`。foundation 1.12.0 缓存制品无
  `RectangleShape` / `CircleShape` 符号，不要 import。
- 列表项禁止外层 Card / Container 包裹：设置页 / 文件页 / 主页均为无容器列表，
  行内容（Row：图标 + 文本）直接置于页面 Column。
- 页面文案按需内联字号（section 标题 14sp、preference 主标题 body2、summary 用注释色）；
  行高统一 `heightIn(min = 48.dp)`；分隔线用 0.7dp 细线（`SurfaceHover.copy(0.6f)`）
  或零间距。
- 自适应图标：`AndroidManifest.xml` 的 `icon` / `roundIcon` 指向 `@mipmap/ic_launcher`
  （background + foreground 两层）；背景透明，前景为去白去黑后的彩色图
  （当前为无损 WebP，`mipmap-*/ic_launcher_foreground.webp`），
  缩进中心安全区（≤72dp）以适配各 OEM mask。

**后台长任务约束**：执行脚本、下载或编译等长任务时，`RootService.executeFile()`
启动 `ExecutionForegroundService`（`foregroundServiceType="dataSync"`）作为前台服务哨兵。
实际的 Process、stdin、日志、取消与状态仍由 `RootService` 管理，
服务只负责持续通知、结束进程入口与任务结束后的自动停止。
修改该链路时需保持 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` 权限、
非导出 Service 声明与低重要性通知渠道的一致性。

**DockBar 布局约束**：FilePage 与 SettingsPage 的 `Scaffold` 内容由 `innerPadding`
处理系统 inset，页面内容额外预留 `56.dp` 给底部 DockBar。不要为这个目的再加
`navigationBarsPadding()`，也不要删除 `FilePage` 列表外层
`weight(1f).fillMaxWidth().padding(bottom = 56.dp)` 的预留。

## 场景导航

| 场景 | 文档 |
|---|---|
| 查看 / 更新开发计划与状态 | `TASKS.md`（按自治协议推进与写回） |
| 功能用法 / 对外说明 | `README.md`（不写更新日志，变更进 `更新日志.md`） |
| 记录变更 | `更新日志.md`（一行一条：新增 / 修复 / 优化 / 安全） |
| 技术栈、目录结构、架构 | `docs/PROJECT.md` |
| 改执行引擎 / ROOT 逻辑 | `docs/PROJECT.md` § RootService（执行引擎） |
| 改 UI 页面 / 组件 | `docs/PROJECT.md` § UI 形态（改动必守） |
| 改安全相关逻辑 | `docs/PROJECT.md` § 安全子系统 |
| 改守卫模块 / 新增包装器 | `module/shso_guard/README.md` |
| 文档该改哪一份 | `docs/PROJECT.md` § 文档维护约定 |
| 文档写作规范与格式 | `docs/文档规范.md` |
| 命名与代码风格 | `docs/命名规范.md` |
| 在线编译 / 自定义包名问题 | `docs/在线编译.md` |
| 加依赖 / 改版本 | `gradle/libs.versions.toml`（禁止在模块内硬编码版本；例外：`app/build.gradle.kts` 中已有的 3 个直引坐标） |

## 常用命令

```bash
./gradlew :app:assembleDebug     # Debug 构建
./gradlew :app:assembleRelease   # Release 构建，输出 app/build/outputs/apk/
./gradlew :app:testDebugUnitTest # 单元测试
python build_apk.py              # 一键构建：签名 + 版本规则 + 产物内容与体积校验
```

产物只打包 `arm64-v8a`（`app/build.gradle.kts` 的 `ndk.abiFilters`）；
新增带原生库的依赖时注意不要引入多 ABI。

## 注意事项

- 代码文件头统一：`// Copyright 2026, shso contributors` +
  `// SPDX-License-Identifier: Apache-2.0`；提交信息按既有惯例书写。
- 签名配置在 `app/build.gradle.kts`（V2+V3，debug 复用 release 签名）；
  `release.jks` 不在仓库内。
- 所有 `su -c` 路径必须单引号转义（`replace("'", "'\\''")`）；
  路径处理必须过滤 `..`、`\`、`\0`。改动 `RootFileManager` / `RootService` 时保持。
- `allowBackup=false`，不要开启。
- Windows 下构建路径过长时使用 `\\?\` 前缀。
