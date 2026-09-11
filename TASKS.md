# shso 任务看板 (TASKS.md)

> 建立时间：2026-09-11（上一版已归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 📌 版本规则（2026-09-11 起生效，不再用旧规则）

| 项 | 规则 | 生成位置 |
|---|---|---|
| `versionCode` | **构建当日日期**纯数字 `YYYYMMDD`（如 `20260911`） | `app/build.gradle.kts` → `buildDateVersionCode`（`BASIC_ISO_DATE`） |
| `versionName` | **`Jinn`**（固定展示名，不再使用 `9.0.2` 之类数字版本名） | `app/build.gradle.kts` 硬编码 |
| Release Tag | **纯日期**（如 `20260911`），与 `versionCode` 天然对齐 | `.github/workflows/publish-release.yml` |

- 升级判定只认 `versionCode`；应用内「检查更新」抓取 GitHub tags 的纯数字标签取最大值比对。
- `build_apk.py` 会用 `aapt2` 校验产物是否符合上述规则（仅告警不阻断，跨零点构建可能差一天）。

---

## 📊 当前状态速览

| 项 | 值 |
|---|---|
| 分支 | `main` @ `75a975b`（**本地与远端已同步**，无落后/领先） |
| PR #1 | **已合并**（merge commit `ce506e3`），29 个 conventional commit 进入 main |
| 最新 Release | **20260911**（已用最新源码**重新编译并覆盖上传**，`app-release.apk` 4,307,626 字节 / 4.11MB，SHA-256 `76ca2f59…cf65c`，含 777 + 全选文件） |
| 单元测试 | **150 tests / 0 failures** |
| APK 体积 | release 4.11MB（清理死资源后，原 5.00MB）；debug 34.18MB（被 DEX 主导，非资源问题） |
| ROOT 真机 | BIYLBAFQQSS8DA69（PacM00，Magisk v30.7，`su -c id` uid=0） |
| 文档 | README / docs/PROJECT.md 已同步至 2026-09-11 状态 |

---

## 📋 待办

### A. 收尾动作

- [x] **推送本地 main**（`2c971f3` build_apk 版本规则 + `76379c7` TASKS 重写 + `75a975b` 全选文件；已推送 `622fe4f..75a975b`）
- [x] **重发 Release**：`20260911` 资产已用最新源码重新编译并 `--clobber` 覆盖上传（含 777 权限、全选文件、主页底部修复），Release 说明同步重写

### B. 观察项 / 待确认

- [x] **主页「shso 目录文件」列表无显示上限** —— 2026-09-11 真机复核**排除**：代码侧 `HomePage.kt:366` 为 `forEachIndexed` 全量渲染、`RootFileManager.listFiles:276` 无 `take`/截断，`runCommandSync` 亦无输出上限。真机 `/data/adb/shso` 9 个条目，首屏可见 6 行（`flood.sh` 行下边界被裁于 y=1980 = 视口底），下滑后 **9 行全部出现**。先前「造 12 个文件列表不增加」系**排序错觉**：列表按「目录在前 + 名称升序」排序（`HomePage.kt:103`），新建的 `num_*` / `20…txt` 排在末尾、位于折叠线以下，不滚动即看不到。
- [x] **「添加到 shso」建目录/拷贝仍是 `chmod 777`**（`RootFileManager` 约 400/407 行）：已确认**刻意保留**（便于其他应用读取），不在改动范围。

### C. 后续可选（未安排）

- [ ] 未混淆 debug 包体积优化（DEX 主导，需从依赖/混淆入手，属独立议题）

---

## ✅ 本轮已完成（2026-09-11，共 16 项）

| # | 内容 | 提交锚点 |
|---|---|---|
| 18 | 推送 18 commits 并建立 PR #1 | `ce506e3`（merge） |
| 19 | **ROOT 真机补测 4/4 通过**（守卫绕过 / 0–3 档位 / RootFileManager 含 chmod 改属 / 终端 `\n` 与中断） | `be6db40` |
| 20 | 审计余项逐项核查：4 项真修 + 1 项按用户要求回退 + 4 项判不成立 + 2 项已覆盖 | `bc76a8c`、`1ec85fe` |
| 22 | **[P1]** 终端日志重复行导致 LazyColumn 崩溃（内容哈希 key → 行序号 key） | `c39d3ae` |
| 23 | 守卫测试脚本自定位（`harness.sh`/`count.sh` 因目录迁移**从未能运行**，已恢复 37/37） | `342c2ae` |
| 25 | **[P1]** 中断后孤儿进程占 CPU 且 UI 无法回收（改为**进程组**回收 + 放宽结束进程条件） | `25877f6` |
| 26 | Root 文件修改时间恒为 1970（`stat -c %Y` 秒被当毫秒） | `c391cd4` |
| 27 | `file -b` 在 toybox 不支持 → 普通脚本被误判「二进制 / 加密」 | `c391cd4` |
| 28 | 目标目录不可写时禁用解压入口并标注原因 | `01ff8c7` |
| 29 | **[P1]** 大文件编辑器空白（`EditorContentArea` 漏传 `chunkedLines`，参数有默认值故静默失败；已改为必填） | `427d11a` |
| 30 | 中等体积文本（0.5MB～2MB）长时间空白：阈值 2MB → **128KB**（按低端机实测）+ 字节数显示改一位小数 | `12c0626` |
| 31 | 清理死资源：`res/RJ.png` 源图 + 24.4MB 未引用字体 + 三张无用图 | `678c4d3`、`4d1a813`、`c208926` |
| — | `versionName` → `Jinn` | `3bf50b3` |
| — | `ensureShsoDir` 权限**必须 777**（曾改 755，按用户要求回退） | `fd334e0` |
| — | 主页底部对齐文件页关系（预留 DockBar 56.dp，导航栏不再透明穿透） | `622fe4f` |
| — | **文件设置弹窗：新增「全选文件 / 取消全选」（只选文件不含文件夹）、排序改纯文本、字号改一行、与新建文件同行** | `75a975b` |
| — | 文档与工具：README / PROJECT.md 全面更新、发布工作流自动生成 Release 说明、`build_apk.py` 版本规则校验 | `bf7a7ed`、`2c971f3` |

---

## 🔒 已定结论（避免反复推翻）

1. **`/data/adb/shso` 必须 777** —— 需让其他应用（文件管理器 / MT 管理器等）自由读写；曾改为 755，用户明确要求回退。代码注释与 `docs/PROJECT.md` 均已标注为硬性要求。
2. **大文件阈值 128KB** —— 按低端机实测定（32/64KB 秒开；256KB 约 30s；2MB 数分钟无响应）。宁可让大文件走只读懒加载，也不要假死的编辑器。
3. **release 已开启 R8 + shrinkResources** —— 资源会被重命名为随机短名（如 `res/RJ.png`），**不要按 APK 内资源名反查源码资源**。
4. **`Process.pid()` 在 Android 不存在** —— 早期审计建议「改用 `Process.pid()`（API 26+）」是错的，编译报 `Unresolved reference`。取子进程 pid 只能反射，且中断正确性已改由进程组回收保证。
5. **「添加到 shso」的 777 保留** —— 便于其他应用读取，勿收紧。

---

## ⚠️ 待决事项（需用户确认）

1. ~~主页「shso 目录文件」列表显示上限是否为刻意设计~~ → **已排除（无上限，非 bug，见 B 节）**。
2. `/data/adb/shso` 下真机测试残留是否清理：`test.number.sh`、`num_20260911_153703_818.txt`(2MB)、`num_20260911_162347_171.txt`(1MB)、`20260911183319412.txt`(0B)、`loadtest/` 目录、以及既有 `1.sh` / `flood.sh` / `editor.png`。**未动**，等用户确认。

（已解决：`2c971f3` 等本地提交已推送；Release `20260911` 已用最新源码重新编译并覆盖发布。）

---

## 🧪 真机与环境备忘

### 真机操作（BIYLBAFQQSS8DA69，**性能较弱**）

- **每次点击后等约 3 秒**再执行下一步，否则容易误操作（页面未切换完成）。
- 文件页到 `/data/adb/shso`：直接点顶栏**「shso」面包屑**（约 `626,174`），无需逐级点。
- 底部导航坐标：`主页 153 / 终端 411 / 文件 669 / 设置 927`，y = `2034`。
- 输入法已关闭（不会因聚焦输入框弹起），布局稳定。

### 环境坑（会导致误判，务必注意）

| 坑 | 表现 / 处理 |
|---|---|
| Git Bash MSYS 路径转换 | `adb push <local> /data/...` 会把 `/data/...` 改成 `C:/Program Files/Git/data/...`，表现为失败或挂起 → 必须 `export MSYS_NO_PATHCONV=1` |
| Android mksh 算术是 **32 位** | `date +%s%N`（19 位）参与 `$(( ))` 被截断产生垃圾值 → 真机计时用 POSIX `time` 内建 |
| toybox `file` **不支持 `-b`** | 传 `-b` 只输出错误文本；不要把它当文件内容 |
| 应用 uid 写 `/data/adb/` 受 **SELinux(MAC)** 限制 | 即便 `chmod 777`，`run-as` 建目录仍 `Permission denied` → 判断可写性必须实测 `File.canWrite()`，不能只看权限位 |
| uiautomator dump 在界面卡死时返回 `null root node` / 陈旧内容 | 结果不可信；此时用 `top -n 1 -b -q -p <pid>` 与 `/proc/<pid>/stat` 看主线程状态（R + 高 CPU = 卡死）

### 验证命令速查

```bash
export MSYS_NO_PATHCONV=1
adb -s BIYLBAFQQSS8DA69 shell "su -c 'ls -ld /data/adb/shso'"              # 权限应为 777
adb -s BIYLBAFQQSS8DA69 shell "dumpsys package com.mixradio.droid | grep -E 'versionCode|versionName'"
adb -s BIYLBAFQQSS8DA69 shell "top -n 1 -b -q -p $(adb shell pidof com.mixradio.droid | tr -d '\r')"   # 主线程是否卡死
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
python build_apk.py --variant Debug --skip-check                            # 含签名 + 版本规则校验
```

---

## 📂 旧看板索引

| 文件 | 涵盖范围 |
|---|---|
| `TASKS-old-20260911-v18final.md` | 任务 18–31 全量过程与证据（本版据此提炼） |
| `TASKS-old-20260911-v17final.md` | 更早一版（17 项，含 R8 / 守卫 / 终端 / 编辑器优化 + 4 P1 4 P2 BUG 闭环） |

---

## 🗂️ 相关文档

- `README.md` —— 功能总览、更新日志、版本号规则、在线编译（自定义包名）指引
- `docs/PROJECT.md` —— 技术栈、目录结构、架构模式、**安全约束与权限位约定**
- `.github/workflows/publish-release.yml` —— 纯日期标签发布（自动生成 Release 说明）
- `.github/workflows/build-apk.yml` —— 在线编译（自定义包名，禁止使用作者原包名）
