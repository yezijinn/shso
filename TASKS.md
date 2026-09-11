# shso 任务看板 (TASKS.md)

> 当前版本：9.0.2/283  
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认  
> 上一版任务全量归档：`TASKS-old-20260911-v17final.md`（17 项全部 [x]，含 R8 / 守卫 / 终端 / 编辑器优化 + 本轮 4 P1 4 P2 BUG 闭环）

---

## 🏁 项目现状（2026-09-11）

- 性能优化主线已收敛到边际收益 0（真机帧 50th 10-50ms；冷启动 547ms 释放；APK 5.0MB）。
- 安全链路（守卫模块 / 挡位 / 审计）已全链路打通；App 侧集成任务 8/9/10 [x]。
- 4 P1 + 4 P2 BUG 已闭环（commit `e7b3816`）；**134 tests / 0 failures**（基线 124 → 任务 22 +3 → 任务 26/27 +7）。
- 任务 19 真机补测进行中：守卫绕过回归 + 0–3 档位端到端已全绿；期间新发现并闭环任务 22（P1 崩溃）、任务 26（mtime 1970）、任务 27（`file -b` 误判）；任务 25（中断后孤儿进程）待修，是当前唯一的 P1。
- 推送状态：分支 `fix/github-tag-version-check` 已推送至 `72e22ac`；PR **#1** 已建（draft）。任务 26/27 的代码修复**尚未提交**。
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
- [ ] 后续：**BIYLBAFQQSS8DA69**（已连接、已确认 ROOT）跑通任务 19 端到端冒烟 → 转 ready for review。
  - 注：原写「5a91ac60 连接」为旧认知残留，按任务 19 修正为准。

### 19. ROOT 链路真机补测（BIYLBAFQQSS8DA69）—— 进行中，已完成 2/4 项
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
- [ ] **3. RootFileManager 真实执行（部分完成）**
  - [x] 终端执行链正常：`echo AAAABBBB_TAIL` → 输出正确（无截断）；错误路径 → `sh: ...: No such file or directory` + `[退出码: 127]`。
  - [x] 洪流可运行并正确解析：`sh /data/adb/shso/flood.sh`（8×8000 = 64000 行），实测 **24–60 行/秒**，应用单核 CPU 96.5%，全量约需 18 分钟。
  - [ ] 文件 chmod 改属（`FilePermissionDialog`）未测。
- [x] **4. 终端 `kill -2` 中断 + `\n` 写入响应（已测，`\n` 通过；中断未通过 → 任务 25）**
  - [x] `\n` 写入响应：`echo AAAABBBB_TAIL` / `echo PING_ONE` 均正确回显与输出（两条路径：终端一次性命令、脚本执行流）。
  - [x] 关键认知：终端「一次性命令」走 `runCommandSync`（输出**全量缓冲**、不设 `taskRunning`）→ 该路径下 `中断` 按钮**始终禁用**，不可能被中断（这本身是设计边界，非缺陷）。
  - [x] 可中断路径 = 脚本执行（主页「立即执行」）→ 设置 `taskRunning`/`processWriter`/`processPid`、输出流式、`中断` 启用，且自动跳转终端页。
  - [ ] **中断实测未生效 → 详见任务 25（P1）。**
- [ ] 第 3 项（文件 chmod 改属）未测；其余已跑完。
- [x] **5. 无 crash 基线**：冷启动（`Status: ok` / COLD）、档位切换、命令执行、洪流连续 4.25 分钟 —— 除任务 22 已修复的崩溃外无其他 crash。
- [ ] 第 3、4 项补完后 → 任务 19 [x]，PR 改 ready for review。

### 20. 审计剩余 14 项 P2 BUG（按用户节奏分批）
- [!] 批次 6: 健壮性微调（3-4 项）
  - `FilePage.kt` 列表 `itemsIndexed` 缺 stable key → 改为 `key = { idx, _ -> idx }`（与任务 17 P1-2 同手法）。
  - `ChunkedFileReader.kt:117` `ByteArrayOutputStream(total.toInt().coerceAtMost(Int.MAX_VALUE))` 在 >2GB 文件上分配 2GB → OOM；改为分段读 / 限制上限 512MB。
  - `RootService.kt:433-441` `Process.javaClass.getDeclaredField("pid")` 反射在新 Android 可能 throw NoSuchFieldException → 改用 `Process.pid()`（API 26+，本工程 minSdk 已满足）。
  - `Bitmap.recycle()` 漏调用 → 找具体位点。
- [ ] 批次 7: 路径安全（2-3 项）
  - `ArchiveExtractor.kt:403-409` `safeDest` 缺 canonical path 校验 → 改为 `if (realDest.startsWith(realBaseDir + File.separator))` 双层防御。
  - `RootFileManager.kt:238` `ensureShsoDir` 用 `chmod 777` → 改为 `chmod 755` + 显式 selinux 上下文（如 root 可写）。
  - `RootFileManager.kt:581` `delete` 全部走 `rm -rf` 单文件 → 改 `rm -f` 路径优先、`rm -rf` 仅当目标是目录。
  - `RootService.kt:174-206` `runCommandSync` 缺 `outputStream.close()` 显式 → 加 try-finally。
- [ ] 批次 8: 终态收口（剩余 5-6 项）
  - `RootService.sendInterrupt` 的 `\n` flush 行为再审视（删掉 ETX 之后是否还需要）。
  - `RootService.activeProcess = process` 后异常路径无 destroy。
  - `TextCompare.kt` mmap 错误回退路径 tmp 清理。
  - `ShsoApplication.appContext` `@Volatile lateinit var` 启动前访问的兜底。
  - 各类 `catch (_: Exception) {}` 静默吞异常的最小审计。
  - 每批完成时同步更新：单测新增 + 真机冒烟 + TASKS 文档 + commit。

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

### 25. [P1·待修] 高输出任务「中断」后孤儿进程存活，且 UI 无任何手段回收
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
- **建议修法方向**：`su -c` 前置 `setsid`/`exec` 以便对进程组发信号；或 `kill -2 -<pgid>` 终止整组；并在 `killCurrentProcess` 中放宽 `isTaskRunning` 前置条件（保留进程句柄兜底）；`Process.pid()` 替换反射。

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

### 21. 新主目标（占位 — 视用户输入）
- [ ] 用户指定后填充
  - 可能方向：新功能 / 体验改进 / 用户报告的具体 BUG / 第三方集成 / 国际化 / 设计语言 v2。

---

## ⚠️ 待决事项（需用户确认或外部依赖）

1. **主线选择**：任务 18 已完成；任务 19 已完成 2/4（守卫绕过 + 档位），剩 RootFileManager chmod / 终端 `kill -2`；期间新发现并闭环任务 22（P1 崩溃）。当前仍处 18 → **19** → 20 路径。
2. **5a91ac60 历史身份**：2026-09-11 修正——该 ID 自 2026-09-06 后已不在 adb 设备列表,长期被记忆误标为"ROOT 主力机",实际上 BIYLBAFQQSS8DA69 才是 ROOT 真机。历史 daily log 里的 5a91ac60 引用是当时真实接入的设备（与今日不同),保留原状不再回填;但新生成的看板、commit、PR 描述一律以 BIYLBAFQQSS8DA69 为准。
3. **审计余项处理节奏**：任务 20 是「全做」版（3 批 ~10 项），用户可指派「只做高风险（Zip Slip / overflow / chmod）」或「暂缓」。注意：任务 22/23 说明**原审计清单不完整**——「迁移导致测试脚本失效」与「终端内容 key 崩溃」都不在那 22 项里。
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
