# shso 任务看板 (TASKS.md)

> 当前版本：9.0.2/283  
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认  
> 上一版任务全量归档：`TASKS-old-20260911-v17final.md`（17 项全部 [x]，含 R8 / 守卫 / 终端 / 编辑器优化 + 本轮 4 P1 4 P2 BUG 闭环）

---

## 🏁 项目现状（2026-09-11）

- 性能优化主线已收敛到边际收益 0（真机帧 50th 10-50ms；冷启动 547ms 释放；APK 5.0MB）。
- 安全链路（守卫模块 / 挡位 / 审计）已全链路打通；App 侧集成任务 8/9/10 [x]。
- 4 P1 + 4 P2 BUG 已闭环（`e7b3816`）；**150 tests / 0 failures**（124 → +3 任务22 → +7 任务26/27 → +3 任务25 → +7 任务20 → +2 解压端到端 → +4 任务28）。
- 任务 19 真机补测 **✅ 全部通过（4/4）**；期间新发现并闭环 **任务 22/25/26/27**。
- **任务 20 审计余项 ✅ 已逐项核查完毕**：4 项真修（Zip Slip canonical 校验 / `chmod 777`→755 / `runCommandSync` 关闭 stdin / `TextCompare` tmp 泄漏，外加 `ChunkedFileReader` 上限加固）、4 项判定不成立（含「`Process.pid()` 在 Android 不存在」）、2 项已覆盖。真机验证 2 项（目录权限、`cat` 不再阻塞）。
- **任务 28 ✅ 已修**：解压到不可写目录（`/data/adb/` 受 SELinux 拦截）时禁用入口并说明原因（方案 B）。
- ⚠️ **任务 29（新·待查）**：大文件（>2MB）编辑器显示「行数 0」且无内容，冷启动可复现；与本轮改动无关，根因未定位。
- **任务 20 审计余项 ✅ 已逐项核查完毕**：4 项真修（Zip Slip canonical 校验 / `chmod 777`→755 / `runCommandSync` 关闭 stdin / `TextCompare` tmp 泄漏，外加 `ChunkedFileReader` 上限加固）、4 项判定不成立（含「`Process.pid()` 在 Android 不存在」）、2 项已覆盖。真机验证 2 项（目录权限、`cat` 不再阻塞）。
- **任务 28（新）**：解压到 `/data/adb/` 下静默失败已定位（SELinux 拦截应用 uid，与我方改动无关），修法待定。
- **任务 20 审计余项 ✅ 已逐项核查完毕**：4 项真修（Zip Slip canonical 校验 / `chmod 777`→755 / `runCommandSync` 关闭 stdin / `TextCompare` tmp 泄漏，外加 `ChunkedFileReader` 上限加固）、4 项判定不成立（含「`Process.pid()` 在 Android 不存在」）、2 项已覆盖。真机验证 2 项（目录权限、`cat` 不再阻塞）。
- 推送状态：分支 `fix/github-tag-version-check` 已推送至 `be6db40`；PR **#1 已 ready for review**。任务 20 的修复**尚未提交**。
- ROOT 链路：BIYLBAFQQSS8DA69 是**已连接、已确认 ROOT 的真机**（Magisk v30.7、`su -c id` uid=0、守卫模块已装、PATH 注入验证通过）——**此前记忆里 5a91ac60 当 ROOT 机是错的,本机无 ROOT 的说法也是错的**。任务 19 ROOT 链路补测可立即执行。
- 审计余项：22 项 BUG 排查已闭环 8 项；剩余 14 项 P2（FilePage key / ChunkedFileReader >2GB overflow / RootService pid reflection / ArchiveExtractor Zip Slip canonical path / chmod 777 / runCommandSync stream close / Bitmap recycle / 等）属次优先级，按用户节奏分批处理。

---

## 🎯 新主目标

**待用户确认**。从已知状态衍生的三条候选主线（独立、可任意切换）：

1. **「推送 + 真机补测」主线**：把当前 16 个未推送提交推到 `origin/fix/github-tag-version-check` → 创建 PR → 5a91ac60 设备连接后补 ROOT 链路验收 → 合并回 `main`。这是最低风险、最高确定性的推进路径。
2. **「清空审计余项」主线**：按依赖关系分 2-3 批把审计剩余 14 项 P2 BUG 全部闭环，每批附测试与真机冒烟。无新功能，纯健壮性 / 边界场景。
3. **「下一轮功能 / 体验」主线**：新需求 / 新场景 / 用户报告的具体 BUG / 设计改进（由用户指派）。

> 默认动作：从主线 1 开始（推送是 16 commits 已就绪的最自然收口）。主线 2 在主线 1 之后做，主线 3 视用户输入开启。

---

## 📋 任务流水线

### 18. 推送当前 fix/github-tag-version-check 分支
- [x] 把本地 18 commits 推到 origin
  - 已执行 `git push origin fix/github-tag-version-check` → `3140003..ee26e03`（fast-forward，无 force）。
  - 验证：远端 `git log --oneline -3` → `ee26e03 / b42f37a / e7b3816` ✓。
  - 说明：看板原记载 16 commits 已过时，实际 18（新增 `ee26e03` ROOT 身份修正、`b42f37a` 看板归档）。
- [x] 创建 PR：`fix/github-tag-version-check` → `main`
  - PR **#1**：https://github.com/yezijinn/shso/pull/1 ，状态 **draft**（68 files, +5756/-607）。
  - 标题：「feat: 守卫模块 / 终端洪流进化 / 编辑器优化 / R8 / BUG 闭环」。
  - 描述取自新生成的 `artifacts/推送前检查报告-20260911.md`（**该文件此前并不存在，任务 18 首轮核验时补生成**；`artifacts/` 已 gitignore，不入库）。
  - CI 说明：仓库两个 workflow 均为 `workflow_dispatch` 手动触发，推送/PR **不会自动跑 CI**，故 `gh pr checks` 显示 "no checks reported" 属预期，非失败。
- [x] 后续：**BIYLBAFQQSS8DA69** 跑通任务 19 端到端冒烟 → **已于 2026-09-11 完成**，PR #1 具备转 ready for review 的条件（是否转由用户决定）。
  - 注：原写「5a91ac60 连接」为旧认知残留，按任务 19 修正为准。

### 19. ROOT 链路真机补测（BIYLBAFQQSS8DA69）—— ✅ 全部通过（4/4）
- [x] 真机能力已确认（2026-09-11 重新核验）：`su -c id` → `uid=0(root) context=u:r:magisk:s0`（Magisk v30.7 / 30750）；`/data/adb/modules/shso_guard/` 已装（20 wrapper）；守卫 PATH 注入 `which rm` → `guard/rm`；`/data/adb/shso/` 落盘。
- [x] **1. 守卫符号链接绕过回归（真机全绿）**
  - 复测手法：当前 `module/shso_guard/guard` 重新推送至 `/data/local/tmp/gt/guard`，`su -c` 运行 `test/device-symlink.sh`。
  - 符号链接绕过 **s1–s8 全 PASS**：`rm -rf <link->/system>/x`、`<link->/data/system>/x`、`/system/./x` 与 `/system/../system/x` 词法归一、`sed -i` 经链接、`cp` 到链接目标、`find <link> -delete` —— 全部拦截。
  - 误杀检查 **n1–n5 + p1–p3 全 PASS**（正常路径放行）；**p4–p5 PASS**（`sed -i` 真受保护路径仍拦）。
  - 审计留痕 18 行，含 `GUARD|DENY|PROTECTED_PATH`。
- [x] **2. RootCommandGateway 0–3 档位真机端到端（全通过）**
  - 档位行点击即循环 0→1→2→3→0；每次切换都 `invalidateReadyCache()` + `syncPolicyMode()`，写 `/data/adb/shso_guard/policy.conf`，**即时生效、无需重启**。
  - 档位 0（off）：受保护命令 `rm -rf /system/__tier_probe__` **真实执行**（放行）；不审计属设计（`SecurityAuditLog.kt:62`：level ≤ OFF 直接 return）。
  - 档位 1（log）：受保护命令**真实执行**（放行），审计 `GUARD|DENY|PROTECTED_PATH` + `INTERNAL_APP|ALLOW|GUARD_POLICY_MODE mode=log (档位=1)`。
  - 档位 2/3（enforce）：命令**被拦截**（`shso_guard: 已拦截 [rm]`），审计 `mode=enforce (档位=2/3)`。
  - 补充事实：策略优先级 `/data/adb/shso_guard/policy.conf`（应用写） > 模块自带（`mode=enforce`）；`which rm` 恒为 `guard/rm`（守卫 PATH 优先于 `/system/bin`）。
- [x] **3. RootFileManager 真实执行**
  - [x] 终端执行链正常：`echo AAAABBBB_TAIL` → 输出正确（无截断）；错误路径 → `sh: ...: No such file or directory` + `[退出码: 127]`。
  - [x] 洪流可运行并正确解析：`sh /data/adb/shso/flood.sh`（8×8000 = 64000 行），实测 **24–60 行/秒**，应用单核 CPU 96.5%，全量约需 18 分钟。
  - [x] **文件 chmod 改属（`FilePermissionDialog`）实测通过**：文件页 → 长按/短按行打开动作菜单（长按进多选、短按弹动作表）→ 「权限/属性」→ 对话框正确回显 `八进制权限 644` + 所有者/用户组 `root`（与设备 `stat` 一致）→ 直接输入 `755` 并选 `system:system` → 保存。
    设备侧核对：`-rwxr-xr-x 1 system system /data/adb/shso/permtest.txt`，`stat` → `mode=755 owner=system:system` —— 与界面设定完全一致 ✓
    （注：档位 0 不审计，故审计日志无对应条目，属设计行为。）
- [x] **4. 终端 `kill -2` 中断 + `\n` 写入响应（已测，`\n` 通过；中断未通过 → 任务 25）**
  - [x] `\n` 写入响应：`echo AAAABBBB_TAIL` / `echo PING_ONE` 均正确回显与输出（两条路径：终端一次性命令、脚本执行流）。
  - [x] 关键认知：终端「一次性命令」走 `runCommandSync`（输出**全量缓冲**、不设 `taskRunning`）→ 该路径下 `中断` 按钮**始终禁用**，不可能被中断（这本身是设计边界，非缺陷）。
  - [x] 可中断路径 = 脚本执行（主页「立即执行」）→ 设置 `taskRunning`/`processWriter`/`processPid`、输出流式、`中断` 启用，且自动跳转终端页。
  - [x] **中断实测未生效 → 已定位并修复，详见任务 25（P1·已修复）。**
- [x] **5. 无 crash 基线**：冷启动（`Status: ok` / COLD）、档位切换、命令执行、洪流连续 4.25 分钟、中断/结束进程、chmod 改属 —— 除任务 22 已修复的崩溃外无其他 crash。
- [x] **任务 19 全部通过（4/4）**：守卫绕过回归 × 0–3 档位 × RootFileManager 执行（含 chmod 改属）× 终端 `\n` 与中断。**PR #1 可转 ready for review**（是否转由用户决定）。

### 20. ✅ 审计余项核查（2026-09-11 逐项核对代码现状；4 项真修 + 4 项判定不成立）
> 说明：清单是早期写的，本轮**逐项回到代码核对**，而不是照单全改。**已修** 4 项、**判定不成立/不需改** 4 项、**已覆盖** 2 项。
> 单测：新增 `ChunkedFileReaderTest`(4) + `ArchiveExtractorZipSlipTest`(3)。全量 **144 tests / 0 failures**。

#### 批次 6 · 健壮性
- **`ChunkedFileReader` >2GB 分配 → 已修（加固）**
  - 旧：`ByteArrayOutputStream(total.toInt().coerceAtMost(Int.MAX_VALUE))`。>2GB 时 `toInt()` 溢出为**负数** → `IllegalArgumentException: Negative initial size`；1–2GB 则直接申请等量内存 → OOM。
  - 新：新增 `MAX_LOAD_BYTES = 32MB` 与纯函数 `cappedLoadBytes(total)`，按上限分块读取；初始容量取 `minOf(cap, CHUNK_BYTES)`。
  - **可达性更正**：唯一调用点（`TextEditorDialog.kt:214`）有 `total <= LARGE_FILE_THRESHOLD`(2MB) 守卫，故该分支**当前不可达**，属潜在缺陷而非现网问题 —— 仍加固，因 `loadAll` 是公开 API。
- **`RootService` pid 反射 → 判定「按原建议不可修」**
  - 原建议「改用 `Process.pid()`（API 26+）」**不成立**：Android 的 `java.lang.Process` 没有 `pid()`，实测编译报 `Unresolved reference 'pid'`。反射保留并容错（失败退回 0）。
  - 且中断/回收的正确性**已不再依赖该 pid**（任务 25 改为进程组回收）。
- **`FilePage.kt` 列表缺 stable key → 已修（本轮之前）**
  - 现为 `itemsIndexed(displayFileList, key = { index, item -> "${item.path}_$index" })`，index 保证唯一。
- **`Bitmap.recycle()` → 判定「不建议改」**
  - 唯一 Bitmap 位点 `ContentViewerDialogs.kt:123`。API 26+ 起 Bitmap 像素内存由 Java 堆统一管理，不显式 `recycle()` 不构成泄漏；
  - 反而在换图/组合切换时显式回收，有「Canvas 绘制已回收位图」崩溃风险。收益为负，未改。

#### 批次 7 · 路径安全
- **`ArchiveExtractor.safeDest` Zip Slip → 已修**（真实文件系统回归测试已加）
  - 旧：仅词法剥离绝对路径与 `../`。词法层**看不到符号链接**，`<target>/link -> 外部目录` 仍可逃逸。
  - 新：双层防御 —— 保留词法层，再加 `canonicalFile` 真实路径校验（解析 `..` 与符号链接），越界即丢弃目录只留文件名；**异常路径同样退化为文件名**（不留 fail-open）。
  - 测试：`ArchiveExtractorZipSlipTest` 3 例（7 种穿越型条目名 / 正常条目保留结构 / **符号链接逃逸**）。旧代码在符号链接用例下会失败。
  - ✅ **端到端验证（已补齐）**：新增 `ArchiveExtractorExtractTest` 2 例，在 JVM + 真实文件系统上直接调 `ArchiveExtractor.extract()`：
    - 正常 zip → 解压成功并保留 `ok.txt` / `sub/normal.txt` 结构（**证明解压代码本身没问题**）；
    - 含穿越条目的 zip → 目标目录外的 `../evil.txt`、`sub/../../deep.txt` **均未出现**，被压回目标目录内，正常条目不受影响（**Zip Slip 端到端拦截成立**）。
- **`ensureShsoDir` chmod 777 → 已修**（真机验证）
  - 改为 **755**：该目录是 root 侧写审计日志与守卫产物的位置，0777 让任意应用可往里塞/改文件（例如伪造审计内容）；目录内写入均由 `su` 以 root 进行，owner 可写已足够。
  - 真机验证：App 启动后 `/data/adb/shso` 由 `drwxrwxrwx`(777) → **`drwxr-xr-x`(755)** ✓
  - 注：SELinux 上下文未额外处理 —— Magisk root 域写入正常，加显式上下文无观测收益。
- **`delete` 全走 `rm -rf` → 判定「不成立」**
  - 路径已过 `isUnsafePath` 校验 + `escapeShellArg`（无通配展开），对**文件**而言 `rm -rf` 与 `rm -f` 行为等价；递归只会在目标是目录时发生，而那正是「删除文件夹」的预期语义。
  - 改成「先 `rm -f` 失败再 `rm -rf`」反而多一次 fork 且引入中间态，未改。
- **`runCommandSync` 未关 stdin → 已修**（真机验证）
  - 该函数只读输出、从不喂输入，但不关 stdin：任何读 stdin 的命令（`cat`/`read`/等 EOF 的交互式命令）会**阻塞到 timeoutMs（默认 120s）**才返回。
  - 新：`start()` 后立即 `runCatching { process.outputStream.close() }`。
  - 真机验证：终端发 `cat`，**+8s 时状态已回到「待命中」且无超时提示**（修复前会一直 RUNNING 到 120s）✓

#### 批次 8 · 终态收口
- **`sendInterrupt` 的 `\n` flush → 保留（不需要改）**：它让行缓冲的读取端及时拿到最后一行，是中断可观测性的必要动作。
- **`activeProcess = process` 后异常路径无 destroy → 判定「已覆盖」**：执行协程 `finally` 中已有 `process?.destroy()`（`RootService` 执行块 finally），异常/取消路径都会走到。
- **`TextCompare` mmap 回退路径 tmp 清理 → 已修**
  - `MappedFile.close()` 会删 tmp（成功路径 OK），但 ROOT 分支里 `len <= 0` / `len > Int.MAX_VALUE` 两处 `throw` **都不删 tmp**，`FileInputStream`/`mmap` 抛异常同样漏 —— 大文件对比本就吃空间，残留 `_shso_cmp_*.tmp` 不易察觉。
  - 新：进入「已产出 tmp」阶段后统一 `try { … } catch (e: Throwable) { runCatching { File(tmp).delete() }; throw e }`；成功路径 `return` 不经过 catch，tmp 仍交给 `MappedFile.close()`。
- **`ShsoApplication.appContext` 启动前访问兜底 → 判定「理论风险、不可达」**
  - `appContext` 在 `Application.onCreate()` 赋值。清单中唯一 `<provider>` 是 androidx 的 `FileProvider`（其 `onCreate` 不触碰本应用上下文），**本应用无自定义 provider/receiver**，不存在早于 `onCreate` 的入口。
  - 本轮新增的 `RootService.runPgidFile` 已用 `runCatching` 包裹。其余 3 个调用点（`SecurityAuditLog:41` / `EditHistoryManager:26` / `RootService:412`）都在 UI 之后触发，未加防御性改写（避免为不可达路径增加噪音）。
- **静默吞异常审计 → 已核查，无需改动**
  - 全项目仅 **6 处** `catch (_: Exception) {}`：`RootService.forceCloseProcess` 关三流（717/718/719）、`RootService:535/538`、`ApkInstaller:167` —— 全部位于**资源关闭/清理**路径，吞掉异常是正确做法。

### 22. [P1·已修复] 终端 LazyColumn 重复 key 崩溃（真机复现 → 修复 → 验证）
- [x] **现象**：终端跑洪流时崩溃
  `FATAL EXCEPTION: java.lang.IllegalArgumentException: Key "..." was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.`
  栈落 `TerminalPageKt$TerminalPage$3$1.invokeSuspend(TerminalPage.kt:159)`（`:159` 是洪流下的自动滚底 `scrollToItem`，只是触发时机）。
- [x] **根因**：`TerminalPage.kt:345` 原为 `items(parsedOutput.lines, key = { line -> line.hashCode() })` —— 用**行内容哈希**当 key。终端里重复行极常见（空行 / 重复提示符 / 回显 / `\r` 原地覆盖产生的同文本行），内容相同即 key 重复 → LazyColumn 直接抛异常。
- [x] **确定性最小复现**（旧 APK，11:03 版）：终端输入 `echo ZZZ_DUP; echo ZZZ_DUP`（产生两行相同输出）→ 立即 FATAL，`Key "2124049908" was already used`，进程死亡。
- [x] **修复**：回归 index key ——
  `itemsIndexed(parsedOutput.lines, key = { idx, line -> terminalLineKey(idx, line) })`，
  新增顶层 `internal fun terminalLineKey(index: Int, line: AnnotatedString) = index` 并注释「禁止用内容做 key」的理由。
  说明：LazyColumn **不传 key 时默认就是 index**，故此次是回退到安全基线而非新增机制。
- [x] **回归守卫测试**：`app/src/test/java/com/mixradio/droid/ui/pages/TerminalLineKeyTest.kt`（3 例：重复内容 key 必须互异 / 空行场景 / key 与内容无关）。全量 **127 tests / 0 failures**（基线 124 + 3）。
- [x] **真机验证**（新 APK，12:15 版）：
  - 同一输入 `echo ZZZ_DUP; echo ZZZ_DUP` → 正常渲染两行 `ZZZ_DUP`，pid 存活，崩溃 0。
  - 原始触发场景 `sh /data/adb/shso/flood.sh` 连续 **4.25 分钟**（旧实现约 119s 即崩），pid 稳定、**崩溃 0**。
- [x] **横向扫描**：全项目仅 `TerminalPage.kt:345` 用内容做 key；`FilePage.kt:540` 与 `BuiltInFilePicker.kt:352`（`"${path}_$index"`）、`TextEditorDialog.kt:855`（idx）、`:984`（index）均安全。

### 23. [已修复] 守卫测试脚本因目录迁移而失效（harness.sh / count.sh）
- [x] **根因**：commit `9cd74ed` 把 `shso_guard/` 迁移到 `module/shso_guard/`，但 `test/harness.sh:10` 与 `test/count.sh:4` 仍**硬编码迁移前路径** `/c/AI_WORKSPACE/PROJECTS/com.mixradio.droid`，并且**忽略传入的 `ROOT` 环境变量**（与 `test/README.md` 的用法自相矛盾）→ 两个脚本自迁移起**从未能运行**，守卫对抗测试实际处于失效状态，却未被发现。
- [x] **修复**：改为脚本自定位
  `SELF="$(cd "$(dirname "$0")" && pwd)"; ROOT="${ROOT:-$(dirname "$SELF")}"; GUARD="$ROOT/guard"`。
- [x] **验证**：`bash test/harness.sh` → **通过 37/37**（策略解析容错 / toybox·busybox 派发 / mv·cp·find·sed / fastboot -w / dd of=x / wipe 无参 / 误杀检查 / 递归深度 / 缺失二进制 fail-closed / 审计留痕）。
- [x] `.gitignore` 补 `.tmp-guard-test/`（临时目录现落在仓库内，否则跑测试会污染工作区）。
- [ ] 遗留小项：`count.sh` 无断言且不设 `SHSO_POLICY`/`SHSO_AUDIT`，结果失真（Git Bash 报 3–4 子进程）；`test/README.md` 的 `ROOT="$(pwd -W)"`（Windows 形态）与脚本注释要求的 POSIX 形态矛盾 —— 自动定位后已不影响使用，README 待更正。

### 24. 守卫单次调用开销（真机实测；非缺陷，但需知悉）
- **方法学坑**：Android mksh 算术为 **32 位**，`date +%s%N`（19 位纳秒纪元）会被截断产生垃圾值（曾得到 712/64/−908 等无意义结果）。真机计时必须用 POSIX `time` 内建。
- **实测**（BIYLBAFQQSS8DA69，各 300 次 `cp`）：裸 `cp` ≈ **16.8 ms/次**；守卫 `cp` ≈ **58.0 ms/次**（+41 ms）。
- **归因**：mksh 解释器启动 + 每次调用一次 `date` fork（`common.sh:503` 的 ALLOW 路径**无条件**调用 `audit()`，`audit()` 内 `common.sh:252` 跑 `date`）。
- **结论**：README 宣称的「每次守卫调用 0–1 个进程」指**守卫逻辑自身**的 fork，与本测量不矛盾，但表述易被误读；且档位 ≤1 是「不装守卫」语义，该开销只在 ≥2 档出现。是否优化另开任务，不在任务 19 验收范围。

### 25. [P1·已修复] 高输出任务「中断」后孤儿进程存活，且 UI 无任何手段回收
- **复现步骤**（BIYLBAFQQSS8DA69，12:34 版 APK）：主页输入脚本路径 → 立即执行 → 确认框「确认执行」→ 自动跳转终端页，状态「运行中..」、输出流式 → 点「中断」。
- **对照实验（两次实测，结论相反，正是问题所在）**：
  | 被中断的任务 | `^C` 回显 | SIGINT 兜底提示 | UI 状态 | 实际进程 |
  |---|---|---|---|---|
  | `slow.sh`（每秒 1 行，低输出） | **有** | 无（正常，已响应） | 待命中 | **全部终止** ✓ |
  | `flood.sh`（8×8000 行，高输出） | **有** | 无 | 待命中 | **`sh -c …` 与 `sh flood.sh` 继续存活** ✗ |
- **关键结论**：
  1. `中断`（`kill -2`）**链路本身可用**：低输出场景下能真正终止进程，`^C` 回显、无兜底提示，行为符合任务 17 P2-19 的设计。**此前「kill -2 完全不工作」的判断不成立，已更正。**
  2. **高输出场景下 `kill -2` 只作用于直接子进程**（`su -c …`），孙进程 `sh -c …`(PPID=465) 与 `sh flood.sh` 逃逸成孤儿并**继续运行**：实测 `sh flood.sh` 占用 **10.0% CPU**，`/proc/<pid>/stat` 的 utime/stime 在 3 秒内 318→323、1609→1634（持续增长）。
  3. **UI 无法回收**：`sendInterrupt` 已把 `isTaskRunning` 置 false → `中断` 按钮变禁用；而 `RootService.killCurrentProcess`（`结束进程`）首行就是 `if (!isTaskRunning) return`，**点击无效**。实测中断后再点「结束进程」，孤儿进程依旧存活。→ 用户只能靠系统「强行停止」或外部 `kill -9` 收尾。
  4. 若在窗口期内较早点击（协程已结束但进程仍在），则连 `^C` 都不会出现（`sendInterrupt` 首行 `if (isTaskRunning)` 即返回），表现为「点中断毫无反应」。
- **定位线索**：
  - `sendInterrupt` 只 `kill -2 $targetPid`，`targetPid` 来自 `RootService.kt:437-439` 的反射 `process.javaClass.getDeclaredField("pid")`（失败则 `=0` → 直接跳过 kill）。该反射即任务 20 批次 6 的待改项（建议 `Process.pid()`，minSdk 26 可用）。
  - 执行协程 `finally` 只对直接子进程做 `process?.destroy()`（SIGTERM），未按进程组/子孙树回收 → 孙进程被孤悬。
  - 低输出为何能全灭、高输出为何逃逸，尚未定论（疑与管道/SIGPIPE 时机有关），修复时应统一按**进程组**回收。
- **已实施修复（进程组回收，2026-09-11）**：
  - **根因**：`su -c` 会把命令放进**新的会话/进程组**，组长随即被重挂到 init（ppid=465），
    应用手上的 `Process` 句柄只对应 `su` 自身 —— 对它发信号既杀不到 `sh -c …`，也杀不到真正的脚本进程。
    真机验证：`su -c` 下 `$$` 即组长（`pid == pgrp == sid`），子进程留在同组，`kill -2 -- -<pgid>` 可一次回收整组
    （toybox `kill` 支持负 pid，实测 rc=0 且目标进程消失）。
  - **执行侧**：`execCmd` 前置 `echo $$ > <应用私有 filesDir>/.run.pgid;`，开跑前先 `delete()` 旧文件并把内存值清零；
    随后 `scope.launch { runPgid = awaitRunPgid() }` 异步取回（不阻塞输出读取）。**刻意不放在外部可写路径** ——
    `/data/adb/shso` 实测 0777，若把 pgid 放那里，任何应用都能伪造值让本应用以 root 执行 `kill -9`。
  - **中断侧**：`sendInterrupt` 优先 `killProcessGroup(2, pgid)`（SIGINT 整组），再对直接子进程兜底 kill。
  - **结束进程侧**：`killCurrentProcess` 先 `killProcessGroup(9, pgid)`；并**放宽前置条件** ——
    旧实现首行 `if (!isTaskRunning) return` 使「UI 已显示待命中但子孙仍在跑」时该按钮完全失效，
    现改为「仍有执行句柄 / 进程组记录 / 任务名」即可兜底回收。
  - **安全校验（fail-closed，抽成顶层纯函数 `buildProcessGroupKillCommand` 以便单测）**：
    ① `pgid > 1`；② `/proc/<pgid>/stat` 第 5 字段 == pgid（确为组长）；③ 本应用自身 pgrp != 该 pgid
    （防止某些 `su` 实现不新建会话时误杀应用自己所在的组）；三者以 `&&` 串联，任一失败即不 kill。
- **测试**：新增 `RootServiceProcessGroupTest`（3 例，精确等值锁定命令串与三重校验）。
  说明：`RootService` 是 object 且初始化依赖 Android/Compose，JVM 测试里无法加载（实测 `ExceptionInInitializerError`），
  故纯函数必须是**文件顶层**声明 —— 这也是本项目既有约定（见 `FileExecutionAnalyzer` / `TextEditorDialog`）。
- **真机验证**（13:00 版 APK，BIYLBAFQQSS8DA69）：
  - 高输出 `flood.sh` 运行中点「中断」→ `^C` 回显、状态回「待命中」、**进程全部消失**（修复前：孤儿继续占 10% CPU）。
  - 高输出 `flood.sh` 运行中点「结束进程」→ **进程全部消失**。
  - 低输出 `slow.sh` 中断 → 进程全部消失（回归保持）。
  - 两次均 `logcat -b crash` 无本应用崩溃。
- **附带更正**：任务 20 批次 6 里「`Process.pid()`（API 26+）替换反射」的建议**不成立** ——
  Android 的 `java.lang.Process` **没有** `pid()`（实测编译报 `Unresolved reference 'pid'`），
  反射只能保留并容错；好在中断/回收的正确性已不再依赖该 pid。
- **遗留小观察**：高输出洪流下 `^C` 回显会被随后 flush 的缓冲输出顶出可见窗口（低输出场景正常显示），
  属显示顺序问题，不影响进程回收。

### 26. [BUG·已修复] Root 文件「最后修改时间」恒为 1970（秒当毫秒）
- **现象**：执行确认框显示 `最后修改时间 = 1970-01-22 00:57`，而设备实际 mtime 为 `2026-09-10 20:49:50 (+0800)`（`stat -c %Y` = `1789044590`）。
- **换算验证**：`1789044590` 当作**毫秒** → `/1000 = 1789044 s` → `1970-01-21 16:57:24 UTC` → **+0800 恰为 `1970-01-22 00:57`**。完全吻合，证实单位错误。
- **根因链**：
  - `RootFileManager.kt:278/281` 执行 `stat -c "%A|%s|%Y|%n"` —— **`%Y` 是「秒」**。
  - `RootFileManager.kt:337` `val modified = parts[2].toLongOrNull() ?: 0L` → 直接赋给 `FileItem.lastModified`。
  - `FileItem.kt:106` `sdf.format(Date(lastModified))` —— `Date(long)` 要求**毫秒**。
  - 而本地路径 `RootFileManager.kt:308` 用 `f.lastModified()`（**毫秒**）→ **同一字段两种单位混用**。
- **影响**：Root 路径（含执行确认框、文件页时间列）时间显示错误；若同一列表混入本地项（毫秒）与 Root 项（秒），按时间排序（`FileListViewSettings.kt:70` `compareBy { it.lastModified }`）会错乱。
- **已实施修复**：`RootFileManager` 新增 `internal fun statSecondsToMillis(seconds: Long) = if (seconds <= 0L) 0L else seconds * 1000L`，`parseStatOutput` 改为 `statSecondsToMillis(parts[2].toLongOrNull() ?: 0L)`；非正值回退 0（保持 `FileItem.formattedDate` 的 `<=0 → ""` 语义）。`FileItem.lastModified` 契约统一为**毫秒**。
- **测试**：`RootFileManagerEscapingTest` 新增 2 例（真机秒值 `1789044590` → `1789044590000` 且年份为 2026；非正值回退 0）。
- **真机验证**（12:34 版 APK）：同一确认框显示 `最后修改时间 = 2026-09-10 20:49`，与设备 `stat` 完全一致（修复前为 `1970-01-22 00:57`）。

### 27. [BUG·已修复] `file -b` 在 Android toybox 不存在 → 内容判定失真（普通脚本被判「二进制 / 加密」）
- **现象**：`/data/adb/shso/flood.sh`（纯 shell 脚本）在执行确认框里显示 **文件类型 = `Shell Script`**、**文件内容 = `二进制 / 加密`**，两者自相矛盾。
- **根因**：`FileExecutionAnalyzer.detectContentViaRoot` 执行
  `RootService.runCommandSync("file -b " + escapeShellArg(path))`，
  但真机实测 **toybox 的 `file` 只支持 `-hL`，不支持 `-b`**：
  ```
  $ su -c 'file -b /data/adb/shso/flood.sh'
  file: Unknown option b (see "file --help")     # 退出码非 0
  $ su -c 'file /data/adb/shso/flood.sh'
  /data/adb/shso/flood.sh: /system/bin/sh script
  ```
  而旧实现用 `val (_, out) = ...` **丢弃退出码**，把这段错误文本当成文件内容去匹配关键词：
  - 错误文本里没有 `elf/shell script/script/text/data` → 落入 `else` → `extTypeLabel(".sh", binary = !contains("text"))` = `"Shell Script"`；
  - `contentLabel` 条件 `!contains("text") && !contains("script")` 为真 → **「二进制 / 加密」**。
- **影响**：所有需要 Root 才能读的文件（`/data/adb/**` 等）内容判定失真，可能误导用户放弃执行正常脚本。
- **已实施修复**：
  1. 命令去掉 `-b`（`file <path>`），**并检查退出码**，非 0 直接回退扩展名（不再把错误文本当内容）；
  2. 抽出**纯函数** `internal fun classifyFileTypeLine(line, ext)` 承载判定逻辑，并**剥掉 `<path>: ` 前缀**——否则路径里的 `data` 会命中 `contains("data")`，把 `/data/adb/...` 误判成「未知二进制」。
- **测试**：新增 `FileExecutionAnalyzerTest`（5 例），含真机原始输出 `/data/adb/shso/flood.sh: /system/bin/sh script` → `("文本 / 脚本", "明文代码")`，以及「路径含 data 不得污染判定」的回归。
- **真机验证**（12:34 版 APK）：同一确认框显示 `文件类型 = 文本 / 脚本`、`文件内容 = 明文代码`（修复前为 `Shell Script` + `二进制 / 加密`），SHA-256 不变。

### 28. ✅ 解压到 `/data/adb/` 下会静默失败 —— 已按方案 B 修复（非本轮引入）
> 来源：任务 20 回归冒烟时发现「点解压无任何产出」，已定位并修。

- **现象**：文件页对 `/data/adb/shso/` 下的 zip 点「自动解压文件」→ 不产生任何目录/文件，只有一闪而过的 Toast（uiautomator 读不到）。**正常 zip 与恶意 zip 表现一致**。
- **根因（已证实，非代码逻辑 bug）**：解压是以**应用自身 uid** 落盘的（`extract()` 里 `File(finalTarget).mkdirs()`，以及 `copyStream` 的 `FileOutputStream`）。而 `/data/adb/` 对应用域有 **SELinux(MAC)** 限制。
  - 决定性证据：`adb shell run-as com.mixradio.droid sh -c 'mkdir -p /data/adb/shso/zz'` 在目录为 **0755 时失败**，临时改为 **0777 时同样失败**（均 `Permission denied`）→ 说明与 DAC 权限位无关，是 MAC 拦截。
  - 因此：**本轮 `chmod 777 → 755` 不是回归**（777 下同样失败）。
- **代码侧佐证**：`ArchiveExtractorExtractTest` 在 JVM 真实文件系统上直接调 `extract()`，正常 zip 解压成功 → 解压逻辑本身正确。
- **为什么是问题**：UI 没有前置判断，仍向用户暴露「自动解压文件」入口；失败只通过 Toast 反馈，用户难以察觉原因。
- **已实施修复（用户选定方案 B：禁用入口并说明原因）**：
  - `ArchiveExtractor.canExtractTo(dirPath)`（纯函数，便于单测）：实测目标父目录可写性 —— `File.canWrite()` 底层走 `access(W_OK)` 系统调用，**能同时反映 SELinux 限制**，所以不是只看权限位。
  - `FilePage` 动作菜单：不可写时标签改为 **「自动解压文件（当前目录不可写）」** 且 `enabled = false`，不再给用户一个「点了没反应、只闪 Toast」的入口。
- **真机验证**（`/data/adb/shso/zstest.zip`）：标签正确变为「自动解压文件（当前目录不可写）」；点击后**动作菜单停留在原地**（确认点击真被拦截），且**未产生任何解压目录** ✓
- **测试**：`ArchiveExtractorCanExtractTest` 4 例（可写目录可解压 / 不存在目录 / 文件而非目录 / 只读目录）。全量 **150 tests / 0 failures**（只读目录用例在 Windows 上按权限跳过 1 例）。
- **未做**：方案 C（改用 root 落盘以支持在 `/data/adb/` 下真正解压）—— 改动面大、需充分回归，如后续需要可单开。

### 29. [待查·疑似既有 BUG] 大文件（>2MB）编辑器显示「行数 0」且无内容
> 来源：任务 20 回归冒烟（补齐「大文件读取」一项时发现）。

- **复现**（BIYLBAFQQSS8DA69，已**冷启动**直接打开排除状态串扰）：新建 `big.txt` = 2,928,890 B（60000 行）→ 文件页 `/data/adb/shso/big.txt` → 动作菜单「编辑文本」。
- **现象**：编辑器打开，标题 `big.txt`，底部显示
  `分段模式: 1 MB / 2 MB` ／ `加载更多 →` ／ **行数 0** ／ `字节数 1 MB / 2 MB`；
  **但正文区域没有任何内容行**（dump 中无 `big line …`，也无任何长度 >50 的文本节点）。**无崩溃**。
- **已确认**：确实用了大文件分支（`isLargeFile = total > LARGE_FILE_THRESHOLD` 成立，走 `readHead` + 分块，不是 `loadAll`）。
- **与本轮改动的關係**：**无关**。本轮只动了 `ChunkedFileReader.loadAll` 的「>2MB 分支」（而该分支的唯一调用点有 ≤2MB 守卫，当前不可达）；`readHead` / `CharsetDetector` / `TextEditorDialog` 均未改动。
- **未定位**：根因未查明。相关代码 `TextEditorDialog.kt:223-231`：`readHead(...)` → `CharsetDetector.detect(raw)` → `chunkedLines = det.text.split('\n')`，理论上 `split` 至少返回 1 个元素，因此「行数 0」可能显示的是别的值，或 `det.text` 为空而 `raw` 非空（例如读到的是未trim的全零缓冲）。
- **下一步建议**：① 确认「行数」的实际取值来源；② 在 `/sdcard`（应用可直接读、不经过 root `dd` 路径）复测同一文件，以区分是「root 读取路径问题」还是「分块渲染逻辑问题」。本次 `/sdcard` 对照因列表排序（文件夹全在前）导航成本过高未完成。

### 21. 新主目标（占位 — 视用户输入）
- [ ] 用户指定后填充
  - 可能方向：新功能 / 体验改进 / 用户报告的具体 BUG / 第三方集成 / 国际化 / 设计语言 v2。

---

## ⚠️ 待决事项（需用户确认或外部依赖）

1. **主线选择**：任务 18 与 **19 均已完成**（19 真机补测 4/4 通过）。期间闭环任务 22/25/26/27。当前处 **19 → 20** 节点：可选「转 PR #1 ready」或「开任务 20 清理审计余项」。
2. **5a91ac60 历史身份**：2026-09-11 修正——该 ID 自 2026-09-06 后已不在 adb 设备列表,长期被记忆误标为"ROOT 主力机",实际上 BIYLBAFQQSS8DA69 才是 ROOT 真机。历史 daily log 里的 5a91ac60 引用是当时真实接入的设备（与今日不同),保留原状不再回填;但新生成的看板、commit、PR 描述一律以 BIYLBAFQQSS8DA69 为准。
3. **审计余项处理节奏**：任务 20 **已逐项核查完毕**（4 修 / 4 判不成立 / 2 已覆盖），不再需要分批。注意：任务 22/25/26/27 与本轮核查共同说明**原审计清单本身不够准确**（有过时项、有建议本身错误、有重复项），后续引用清单前应回代码核对。
4. **PR 标题 / 描述模板**：已用于 PR #1（标题「feat: 守卫模块 / 终端洪流进化 / 编辑器优化 / R8 / BUG 闭环」，描述存 `artifacts/pr-body-fix-github-tag-version-check.md`）；用户可随时改。
5. **TASKS-old-20260911-v17final.md 保留期**：默认永久保留（git history 仍可查），用户可指派删除。
6. **设备侧状态残留（本轮测试副作用，均无害）**：
   - `/data/adb/shso/audit.log` 因计时压测写入约 2000 行测试噪声（设备侧日志，有轮转上限）。
   - 新增 `/data/adb/shso_guard/policy.conf`（应用同步产生，现 `mode=off`，对应档位 0）。原先只有模块自带那份 `mode=enforce`；两份共存时**应用写的那份优先**。
   - `/data/local/tmp/gt/` 有守卫副本与探针脚本，`/data/local/tmp/gtw*` 为计时临时目录（测试用，可清理）。
   - 设备档位已还原为初始的 **0（无防护）**。
   - 输入法：`com.jinn.inputmethod/.JinnIme` 由用户在测试期间手动关闭（如需恢复：`adb shell ime enable com.jinn.inputmethod/.JinnIme`）。

---

## 📂 旧看板索引

| 文件 | 涵盖范围 | 提交锚点 |
|---|---|---|
| `TASKS-old-20260911-v17final.md` | 任务 1-17 全部 [x]，含 R8 / 守卫 / 终端洪流 / 编辑器 / SettingsPage / 性能 / BUG 闭环 | `e7b3816` 起向前 16 个 commit |
| `TASKS-old-20260910113039.md` | 早期版本（已被 9fdc1f2 清理） | 历史快照 |
| `TASKS-old-20260910172800.md` | 早期版本（已被 9fdc1f2 清理） | 历史快照 |
