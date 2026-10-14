# shso 主执行体长期任务规范

> 本文件给拥有项目全部权限（增删查改）的主执行体使用。
> 负责维护、完善、修复、开发、找缺陷、审查误判、防读错漏读、多角度核查。
> 两名只读副执行体：副1号落点 `AI-1-BUG.md`、副2号落点 `AI-2-BUG.md`（均根目录，副体唯一可写）。
>
> 项目根：`C:\AI_WORKSPACE\PROJECTS\com.mixradio.droid\shso-main`
> 包名：`com.mixradio.droid`　版本名：`Jinn`　构建日即 `versionCode`（YYYYMMDD）
> 执行引擎核心：`app/src/main/java/com/mixradio/droid/data/RootService.kt`
> 环境体系：`EmbeddedEnv.kt`（内置 env.bin）/ `EnvPack.kt`（可下载扩展包）/ `TermuxPrefix.kt`
> 既有机器台账：`BUG.md`（不入库、不发布）；过程看板：`TASKS.md`；对外变更：`更新日志.md`

---

## 总纲

1. **终端脚本执行完整性是最高优先级，不可妥协**。
   必须保证 `.sh`（自解压、加密、联网、对暗号、read/select 交互、trap、后台、大输出、bashisms）
   与 `.so`/ELF（动态链接依赖、非 ELF 误报 126/127）**全部可运行、可交互、不中断、不缺环境**。
   任何改动若使脚本执行不了、无法交互、交互不完整、代码中断、环境缺失，视为 P0 回归，必须回退或修复。
2. **自行修改文件，不停下来提问**。信息不足时按既有约定推断并落 `BUG.md`，不阻塞流程。
3. **改动作必先读后改**：改前 `Read` 目标文件至少一次；`edit` 必须基于最新内容。
4. **多角度核查**：每处判断至少取「静态代码 / 设备真机 / 单测 / 复现脚本」四视角之一取证。
5. **防看错读错漏读**：跨文件改动先 `grep` 全仓确认调用点；行号失效重新定位；
   长文件分段续读，禁基于半截内容下结论。
6. **防误判**：对 `BUG.md` 既有结论先验证再采信；DISPROVEN/BY_DESIGN 项改动前须复现推翻证据。
7. **对外文本先净化再推送**：同步在线仓库前依次调用 output-sanitize 与 changelog 两个技能。
8. **发版纪律**：Release APK 须缩减体积；说明 ≤10 行且 ≤200 字；详细写入 `更新日志.md`。
   双端（GitHub/Gitee）主分支推送；Tag 须纯数字 `YYYYMMDD`（禁 `v` 前缀）。
   发布新文件须覆盖旧 APK 并删除旧版 Release 附件。

---

## 规范 1 — 代码维护与日常完善

**角色**：长期值守的维护者，负责整洁、依赖更新、死代码清理、约定一致。

**职责**
- 周期扫描 `app/src/main/java/com/mixradio/droid/` 下重复逻辑、死代码、过期 TODO。
- 统一命名、日志口径、异常分类，与 `BUG.md` 已 FIXED 条目一致，防台账—代码漂移。
- 依赖与安全组件更新先确认签名链路不受影响。

**入场检查（每次启动必做）**
1. `git status` 确认工作区干净或与 `TASKS.md` 当前条目吻合。
2. 读 `BUG.md` 索引表，列 `OPEN`/`BLOCKED_HUMAN` 项为潜在清理对象。
3. 读 `TASKS.md` 状态速览，对齐当前在做的事项。

**执行约束**
- 删/改名符号前先 `grep -rn "<symbol>"` 全仓确认调用点，更新所有引用再删。
- 清死代码须留痕：在 `BUG.md` 追 BY_DESIGN/DISPROVEN 并注"代码已删"。
- 不擅自改变执行引擎对 `.sh`/`.so` 的运行路径、PTY 包装、环境变量注入方式。

**完成判定**：`lint` + `assembleRelease` 通过；`BUG.md`/`TASKS.md` 同步；无新增 OPEN 缺陷。

---

## 规范 2 — 缺陷修复与回归防护

**角色**：专职修缺陷，并把修过的坑固化为防护，杜绝二次踩雷。

**职责**
- 认领 `BUG.md` 中 `OPEN`/`P1`/`P0` 条目，按 `ev(file:line#symbol)` 定位根因。
- 每修一类缺陷，补 `guard_test`（复现脚本或单测）写入对应条目。
- 合并两名副执行体在 `AI-1-BUG.md` / `AI-2-BUG.md` 的产出，去重后并入 `BUG.md` 并调度修复。

**执行流程**
1. 读目标条目 → `Read` 到具体行 → `grep` 同模式其他位置（防同类漏修）。
2. 设备真机用 `adb` 复现（交互脚本经 `busybox script -q -c '...' /dev/null` PTY 通道，
   同构于 `RootService.wrapWithPty`）；非交互用复现脚本落 `RESULT=PASS/FAIL`。
3. 修复后重跑复现；再跑相邻类型脚本确认无回归（见规范 8 矩阵）。
4. 条目 `OPEN→FIXED`，补 `fix` 与 `guard_test`，禁删历史条目。

**铁律（脚本执行）**
- 涉及 `RootService.startExecution` / `execCmd` / `wrapWithPty` / `sendInput` /
  `EmbeddedEnv.exportFragment` / `EnvPack` 的改动，必过"终端对 .sh/.so 完整执行"验收。
- 环境变量（TMPDIR、LD_LIBRARY_PATH、SSL_CERT_FILE、CURL_CA_BUNDLE、PATH）注入变更，
  须真机验证 curl HTTPS、openssl、python3、git、ssh 五类仍可用。

**完成判定**：复现脚本 FAIL 转 PASS；`BUILD SUCCESSFUL`；`BUG.md` 状态迁移完整。

---

## 规范 3 — 新功能开发与集成

**角色**：在保持终端脚本能力完整前提下交付新功能。

**职责**
- 实现 `TASKS.md` 规划增量；遵循既有 Compose、协程、RootService 调用范式。
- 新入口若触发脚本执行，须复用 `RootService.executeFile` 既有链路，不自造执行通道。

**入场检查**
1. 读 `TASKS.md` 目标条目与 `README.md` 架构段，确认边界。
2. 检索 `RootService.kt` 公开方法，确认复用点（执行、中断、输入、前台保活）。
3. 评估对 `.sh`/`.so` 执行路径耦合度，列为"必须回归"清单。

**执行约束**
- 新增权限、文件落点须进 `res/xml/file_provider_paths.xml` 白名单，复核 `BUG-SEC-121` 收口。
- 配置敏感 UI 状态须文件级持有（参考 `BUG-CONSIST-*` 旋转丢失教训）。
- 新脚本执行相关代码不得破坏 PTY 交互与证书软链（`prepareExecutionRuntime`）。

**完成判定**：`assembleRelease` 通过；`TASKS.md` 标记完成；真机用至少一类交互脚本验证。

---

## 规范 4 — 专职找缺陷 / 挖掘

**角色**：主执行体亲自下场深挖，并统筹两名副体的审查产出。

**职责**
- 按维度 `LOGIC/EDGE/EXC/PERF/SEC/COMPAT/CONSIST/CONC` 扫描近期改动与历史热点。
- 重点靶区：`RootService.kt`（执行/进程组回收/PTY/输入）、`ShNormalization.kt`（归一化）、
  `ArchiveExtractor.kt`（解压）、`RootFileManager.kt`（列举/权限）、文本编辑器大文件链路。
- 周期性读取 `AI-1-BUG.md` / `AI-2-BUG.md`，将其 OPEN 条目去重并入 `BUG.md` 并排期。

**挖掘方法**
1. `git log -p -<N>` 取近期 diff，逐行追问异常分支、空值、越界、竞态、口径不一致。
2. 可疑点写最小复现（静态推导先行，必要时 `adb` 真机取证）。
3. 先查 `BUG.md` 索引去重；新发现按 `BUG-<DIM>-<NNN>` 追加，含 `ev` 三要素。

**脚本执行完整性专项巡检**
- 归一化是否误破自解压/加密脚本（参考 `BUG-CONSIST-040`/`BUG-LOGIC-040`）。
- PTY 下 read 交互、退出码透传、输入归属代次是否仍正确（参考 `BUG-CONSIST-166`/`BUG-LOGIC-164`）。
- `.so` ELF 魔数预检、chmod、非 ELF 报 126 是否仍生效（参考 `AI2-LOGIC-007`/`BUG-LOGIC-161`）。

**完成判定**：产出 `BUG.md` 新条目或 DISPROVEN 复核；副体产出已并入调度。

---

## 规范 5 — 误判审查与结论复核

**角色**：专审"看似修好实则误判、看错读错漏读导致的错误结论"。

**职责**
- 复核 `BUG.md` 中 `FIXED`/`DISPROVEN`/`BY_DESIGN` 条目证据链是否扎实。
- 对"前提不成立"类结论，重跑证据命令确认（如 `git log -S`、`grep` 全仓、真机复现）再采信。
- 复核副体 `AI-1-BUG.md`/`AI-2-BUG.md` 的 CONFIRMED 条目证据是否充分，避免误信。

**审查清单**
1. 行号是否仍有效（`Read` 重新定位 `symbol`，防代码移动后误指）。
2. 证据是否来自真实运行而非臆测；`DISPROVEN` 须有"实测无该符号/路径"硬证据。
3. 静态推导是否与 `RootService` 实际运行态（协程线程、PTY、su 往返）一致。
4. 跨文件一致性：台账结论、代码、文档（`README`/`TASKS`/`更新日志`）三者是否漂移。

**防误判铁律**
- 凡"某缺陷不成立"，必用 `grep -rn` + 真机命令双证；单凭注释推断无效。
- 推翻既有 `FIXED` 须先复现其失效，再改状态，禁静默翻转。

**完成判定**：输出复核报告（哪些可靠、哪些需重验），相应更新 `BUG.md` 置信度字段。

---

## 规范 6 — 防看错 / 读错 / 漏读 核查

**角色**：任意改动落地前做"信息完整性"把关，专治截断、错位、假设性阅读。

**职责**
- 长文件改动前强制分段 `Read`（offset/limit 续读），禁基于首屏下结论。
- 跨文件联改前列出全部受影响符号与调用点清单，逐项打勾消项。

**核查动作**
1. **看错**：`edit` 的 `oldString` 必先 `Read` 取真实缩进/编码；杜绝凭记忆拼字符串。
2. **读错**：正则/字节特征改动（如 `ShNormalization.magics`）须核对注释与实现量纲一致
   （参考 `BUG-LOGIC-052` gzip 2→3 字节、`BUG-LOGIC-158` 分子分母量纲）。
3. **漏读**：`/proc/<pid>/stat`、`ps` 输出、`awk` 列号在 `comm` 含空格时整体错位
   （参考 `BUG-LOGIC-131`/`BUG-LOGIC-132`）——凡解析进程/文本字段，先剥 `)` 再取列。
4. **漏改**：同模式多出现点（如三处 `export TMPDIR`、三处 `realExtension`）须全量 `grep` 覆盖。

**完成判定**：改动前提供"已读文件清单 + 调用点清单"；无半截阅读导致的假设性修改。

---

## 规范 7 — 多角度交叉验证

**角色**：对关键改动做四视角交叉取证，确保结论可重复、可审计。

**验证矩阵（每类脚本执行改动必做）**

| 视角 | 手段 | 取证物 |
|---|---|---|
| 静态 | `grep` 调用点 + `Read` 上下文 | 影响面清单 |
| 设备 | `adb` + `su -c` 跑真机脚本 | 退出码、输出片段 |
| 单测 | `app/src/test` 现有用例 + 新增 | JUnit 结果 |
| 复现 | 最小复现脚本（非交互 `RESULT=PASS`） | 回归前/后对照 |

**脚本执行专项四视角**
- 静态：确认 `wrapWithPty`/`sendInput`/`prepareExecutionRuntime` 未退化。
- 设备：跑 `TwTLoaderv1.52.sh` 类自解压交互安装器，确认可达提示并接收输入。
- 单测：执行引擎相关 `Test` 全绿。
- 复现：openssl 加密、curl HTTPS、python3、git、ssh、ELF `.so` 五类复现脚本全 PASS。

**完成判定**：四视角均有取证记录；任一视角 FAIL 则回退并交规范 2。

---

## 规范 8 — 终端 .sh/.so 执行完整性守护（最高优先级）

**角色**：专职保证"终端对所有类型 .sh / .so 文件功能完整无缺"。这是八份规范的核心目标。

**不可接受现象（出现即 P0）**
- 执行不了：环境缺失、解释器找不到、魔数误判。
- 无法交互：PTY 未建、输入不送达、提示卡死。
- 交互不完整：输入只收一半、退出码丢失、旧任务输出混入。
- 代码执行中断：前台服务被冻结、进程组回收误杀、读取截断丢尾。
- 环境缺失：无 TMPDIR、证书链断、LD_LIBRARY_PATH 未注入、curl cert 77。

**守护清单（每次发版前全量过）**
1. **基础类型**：普通 `.sh`、带 BOM/CRLF、自引用自解压（base64/heredoc 载荷）归一化不破坏。
2. **加密**：openssl 加解密脚本（参考 s07/s10 复现）。
3. **联网**：`curl` HTTPS 不报 cert 77（依赖 `prepareExecutionRuntime` 证书软链 +
   `EmbeddedEnv.exportFragment` 的 `SSL_CERT_FILE`/`CURL_CA_BUNDLE`）。
4. **对暗号**：python3 + openssl 挑战应答类（依赖 `EnvPack` 的 python3）。
5. **交互 read**：read 提示式菜单/密码（TwTLoader 范式）可达可输入。
6. **交互 select**：`select` 菜单（`#!/bin/bash` 或 `#!/system/bin/sh` 均须尽力）——
   已知 `busybox script` PTY 下 `select`/`while-read` 循环存在 busybox 局限，
   本规范须在真机持续尝试更稳健 PTY 后端（如可用 python `pty` 中继），
   并在 `BUG.md` 如实记录局限与进展，不以"读提示能用"掩盖 select 缺陷。
7. **ELF/.so**：ELF 魔数预检 + `chmod a+x` 直接执行；非 ELF 报 126 不回退 shell 乱码
   （参考 `AI2-LOGIC-007`/`BUG-LOGIC-161`）。
8. **环境兜底**：TMPDIR、`/data/local/tmp`、LD_LIBRARY_PATH、PATH（env/bin + EnvPack/bin）齐备。

**执行引擎关键不变量（改动须保全）**
- `wrapWithPty` 用 `busybox script -q -c '<cmd>' /dev/null`（注意 `-q -c` 顺序，非 `-qec`）。
- `sendInput` 经 `processWriter`（`process.outputStream`）写 `text + "\n"`，须带代次/槽位归属。
- `prepareExecutionRuntime` 幂等建证书软链与临时目录，启动/执行前触发。
- 退出码经 `EXIT_RELAY` 透传，禁被 PTY 回声或读取截断吞掉（参考 `BUG-EXC-154`/`AI2-CONC-001`）。

**验收脚本（真机矩阵）**
- 非交互 14 类（自解压/联网/加密/组合/网解密/git/python/ssh/trap/大输出/语法/后台/环境变量/bashisms/ELF）。
- 交互 read 类（菜单、密码、自解压安装器）。
- 任一 FAIL 回退或修复至全 PASS，再走规范 7 四视角。

**完成判定**：真机矩阵全 PASS；`BUG.md` 局限条目状态与证据最新；对外文档经规范 1/5 复核。

---

## 自动任务编排建议

- 每晚/每迭代：规范 4（找缺陷，含并入副体产出）→ 规范 2（修 OPEN）→ 规范 7（验证）→ 规范 8（执行守护）。
- 发版前：规范 6（防读错）→ 规范 5（复核误判）→ 规范 8（全量矩阵）→ 规范 3 增量回归。
- 所有对外提交前：output-sanitize → changelog → 双端主分支推送 + 纯数字 Tag + 覆盖 Release APK。
- 两名副体分别产出 `AI-1-BUG.md` / `AI-2-BUG.md` 后，主执行体据其条目并入 `BUG.md` 并调度上述规范闭环。
