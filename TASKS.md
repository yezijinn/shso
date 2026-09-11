# shso 任务看板 (TASKS.md)

> 当前版本：9.0.2/283  
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认  
> 上一版任务全量归档：`TASKS-old-20260911-v17final.md`（17 项全部 [x]，含 R8 / 守卫 / 终端 / 编辑器优化 + 本轮 4 P1 4 P2 BUG 闭环）

---

## 🏁 项目现状（2026-09-11）

- 性能优化主线已收敛到边际收益 0（真机帧 50th 10-50ms；冷启动 547ms 释放；APK 5.0MB）。
- 安全链路（守卫模块 / 挡位 / 审计）已全链路打通；App 侧集成任务 8/9/10 [x]。
- 4 P1 + 4 P2 BUG 已闭环（commit `e7b3816`）；124 tests / 0 failures 基线守住。
- 推送状态：分支 `fix/github-tag-version-check` 本地领先 origin 17 个提交，**未推送**。
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
- [ ] 把本地 16 commits 推到 origin
  - 前置：`git status` 干净、`git log origin..HEAD` 已确认（e7b3816..b034fe4..adbf6c1..9fdc1f2..7fd5a6e..9cd74ed..66e4f44 + 之前 9 个）。
  - 推送目标：`origin/fix/github-tag-version-check`（**不** `--force`，**不** `git push --set-upstream` 除非首次）。
  - 验证：远端 `git log --oneline | head -5` 出现 e7b3816。
  - 已知信息：`artifacts/推送前检查报告-20260911.md` 已含 PR 标题 / 描述模板。
- [ ] 创建 PR：`fix/github-tag-version-check` → `main`
  - 标题：「feat: 守卫模块 / 终端洪流进化 / 编辑器优化 / R8 / BUG 闭环 」
  - 描述贴自 `artifacts/推送前检查报告-20260911.md` + 任务 17 BUG 闭环简述。
  - 状态：draft（ROOT 链路未补测 → 不能 merge）。
- [ ] 后续：5a91ac60 连接 + ROOT 验收通过 → 转 ready for review。

### 19. ROOT 链路真机补测（BIYLBAFQQSS8DA69,设备已连接;此前误标"待 5a91ac60"是认知错误,已修正）
- [ ] 真机能力已确认（2026-09-11 重新核验）:
  - `su -c id` → `uid=0(root) gid=0(root) context=u:r:magisk:s0`,Magisk v30.7（versionCode 30750）。
  - `/sbin/su` 存在;`/data/adb/modules/shso_guard/` 已装（2026-09-10 23:57 写入,20 个 wrapper: rm/mv/cp/dd/fastboot/find/sed/shred/wipe/busybox/toybox/common.sh 等）。
  - **守卫 PATH 注入验证通过**:`su 0 sh -c "export PATH=/data/adb/modules/shso_guard/guard:\$PATH; which rm"` → `/data/adb/modules/shso_guard/guard/rm`（非 `/system/bin/rm`）;调 `rm` 真触发守卫并审计。
  - `/data/adb/shso/audit.log` 与 `editor.png` / `flood.sh` / `loadtest/` 都已落盘。
- [ ] 端到端真机冒烟（建议逐项跑,确认现状不崩;若发现新 BUG 单独列任务）:
  1. 守卫符号链接绕过回归（任务 8 已有 8/8 冒烟脚本,在 BIYLBAFQQSS8DA69 复测）。
  2. `RootCommandGateway` 0-3 档位真机端到端:档位 0 全放行,档位 1 落 ALLOW 审计,档位 2/3 走完整判定,挡 critical 命令弹确认框。
  3. `RootFileManager` 真实执行:文件 chmod 改属,执行可执行文件（真跑 `sh /data/adb/shso/flood.sh` 看洪流 → 终端解析路径）。
  4. 终端常驻 shell 的 `kill -2` 中断（任务 17 P2-19 改后是否生效）+ `\n` 写入响应。
  5. `RootService.runCommandSync` 流关闭 / `ChunkedFileReader.loadAll` 大文件 / `ArchiveExtractor` Zip Slip canonical path——这些 BUG 改不改取决于任务 20 节奏,验收只确认当前真机无 crash。
- [ ] 全部冒烟通过后 → 任务 19 [x],PR 改 ready for review。

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

### 21. 新主目标（占位 — 视用户输入）
- [ ] 用户指定后填充
  - 可能方向：新功能 / 体验改进 / 用户报告的具体 BUG / 第三方集成 / 国际化 / 设计语言 v2。

---

## ⚠️ 待决事项（需用户确认或外部依赖）

1. **主线选择**：任务 18 → 19 → 20 是默认推荐路径；用户若有其他优先级可重新排序。
2. **5a91ac60 历史身份**：2026-09-11 修正——该 ID 自 2026-09-06 后已不在 adb 设备列表,长期被记忆误标为"ROOT 主力机",实际上 BIYLBAFQQSS8DA69 才是 ROOT 真机。历史 daily log 里的 5a91ac60 引用是当时真实接入的设备（与今日不同),保留原状不再回填;但新生成的看板、commit、PR 描述一律以 BIYLBAFQQSS8DA69 为准。
3. **审计余项处理节奏**：任务 20 是「全做」版（3 批 ~10 项），用户可指派「只做高风险（Zip Slip / overflow / chmod）」或「暂缓」。
4. **PR 标题 / 描述模板**：已用 `artifacts/推送前检查报告-20260911.md` 里的草案；用户可改。
5. **TASKS-old-20260911-v17final.md 保留期**：默认永久保留（git history 仍可查），用户可指派删除。

---

## 📂 旧看板索引

| 文件 | 涵盖范围 | 提交锚点 |
|---|---|---|
| `TASKS-old-20260911-v17final.md` | 任务 1-17 全部 [x]，含 R8 / 守卫 / 终端洪流 / 编辑器 / SettingsPage / 性能 / BUG 闭环 | `e7b3816` 起向前 16 个 commit |
| `TASKS-old-20260910113039.md` | 早期版本（已被 9fdc1f2 清理） | 历史快照 |
| `TASKS-old-20260910172800.md` | 早期版本（已被 9fdc1f2 清理） | 历史快照 |
