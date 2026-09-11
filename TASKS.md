# shso 任务看板 (TASKS.md)

> 建立时间：2026-09-11（上一版归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 📌 版本规则（2026-09-11 起生效，不再用旧规则）

| 项 | 规则 | 生成位置 |
|---|---|---|
| `versionCode` | **构建当日日期**纯数字 `YYYYMMDD`（如 `20260911`） | `app/build.gradle.kts` → `buildDateVersionCode`（`BASIC_ISO_DATE`） |
| `versionName` | **`Jinn`**（固定展示名） | `app/build.gradle.kts` 硬编码 |
| Release Tag | **纯日期**（如 `20260911`），与 `versionCode` 对齐 | `.github/workflows/publish-release.yml` |

- 升级判定只认 `versionCode`；应用内「检查更新」抓取 GitHub tags 的纯数字标签取最大值比对。
- `build_apk.py` 用 `aapt2` 校验产物是否符合上述规则（仅告警不阻断，跨零点构建可能差一天）。

> 守卫模块 `module/shso_guard` 有独立版本：当前 **v1.2.0**（`module.prop` 的 `version=`）。
> 新增/修改包装器或 `common.sh` 后必须：重新生成包装器 → 重打包 `assets/shso_guard.zip` → 同步
> `GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES` → 升 `module.prop` 版本（否则已装用户不会升级）。

---

## 📊 当前状态速览

| 项 | 值 |
|---|---|
| 分支 | `main`（本次提交后待推送；上一批 `0f2cbf9` 已同步） |
| 单元测试 | **220 tests / 0 failures**（本轮新增 70 条，起点 150） |
| 守卫模块 | **v1.2.0**（真机已装并验证拦截） |
| 真机 | BIYLBAFQQSS8DA69（PacM00，Magisk，`su -c id` uid=0）|
| 当前安全档位 | 设备上为 **0**（测试后已还原；验证拦截需切到 ≥2） |
| 文档 | README / PROJECT.md / 本看板已同步至本轮结束状态 |

---

## 📋 待办

### A. 立即动作

- [x] **推送 main 至 origin** —— 已推送 `bc9690e..73c8f0f`（9 个提交，含本次文档更新）
  - `fef258c` 看板：排除「主页列表上限」误报
  - `65c4f8f` 审查修复 11 项（正确性/崩溃/竞态/性能/UX）
  - `32ca866` 编辑器批次（高亮移出主线程 / 分段按行对齐 / CR 归一 / 文件页首屏回归）
  - `1a0a309` root 保存保权限与软链 / Pager 保留页 / 编辑历史按文件分区
  - `42e725e` 安全第一轮（wrapper 绕过 / 加密混淆 / 格机原语 / 重定向 / 守卫 v1.2.0）
  - `7301745` 安全第二轮（变量伪装程序名 / eval 载荷 / xargs / cp·mv / 守卫原子安装）
  - `27c1b18` 原子安装失败清理 `.new`
  - `f34eecb` 自动执行门控纯函数化 + truncated 兜底崩溃修复

### B. 观察项 / 待确认

- [x] 主页「shso 目录文件」列表无显示上限 —— 已排除（真机下滑后 9 项齐全；先前是排序把新文件挤到折叠线以下）
- [x] 「添加到 shso」的 `chmod 777` —— 刻意保留（便于其他应用读取）

### C. 后续可选（未安排）

- [ ] **安全第三轮（可选）**：`GuardModuleInstaller` 卸载残留（`/data/adb/shso_guard/policy.conf` 与审计日志）；`ScriptAuditor` 跨行变量追踪；`$IFS` 之外的 shell 展开（`${x:-…}`、算术展开）
- [ ] **内核级守卫（独立议题）**：PATH 前置型守卫无法拦绝对路径调用与 `PATH` 重置，彻底封堵需 seccomp/LSM hook
- [ ] 未混淆 debug 包体积优化（DEX 主导，属独立议题）

---

## ✅ 本轮已完成（2026-09-11 代码审查 + 安全加固）

### 批次一：全量代码审查后修复（`65c4f8f`，11 项）

| # | 内容 | 位置 |
|---|---|---|
| 1 | 编辑器切编码静默丢编辑；读取失败仍可保存 → 文件截断为 0 字节 | `TextEditorDialog` |
| 2 | `stat -c %n` 被误剥离 `" -> "`，含该串的真实文件名被截断（真机验证） | `RootFileManager:365` |
| 3 | `\e[0;32m` 组合序列丢色 | `AnsiParser:342` |
| 4 | 编码探测整读文件 → 超大文件 OOM | `ChunkedFileReader:64` |
| 5 | `checkRoot` 超时无效 + 泄漏 `su` 进程 | `RootService:164` |
| 6 | 切目录被旧加载结果覆盖 → 可能对错误路径操作（代次校验 + 取消旧任务） | `FilePage:183` |
| 7 | 切目录不清多选 / 不归顶 | `FilePage:258` |
| 8 | 终端首帧主线程全量解析 ANSI（≈200ms 卡帧） | `TerminalPage:124` |
| 9 | LazyColumn key 含下标 → 列表重建 | `FilePage` / `BuiltInFilePicker` |
| 10 | 每项每帧 `new SimpleDateFormat` | `FileItem:102` |
| 11 | 扫描中/失败与空目录不可区分 | `HomePage:344` |

### 批次二：编辑器（`32ca866`）

- 语法高亮移出主线程（停顿 120ms 后台计算）+ `VisualTransformation` 陈旧回退保护
- 大文件分段读取按**完整行**对齐（新增纯函数 `ChunkedFileReader.lastCompleteLineEnd`），不再切断行/多字节字符
- 载入时 CRLF/CR 归一为 LF（Compose 只按 `\n` 断行），保存时按原风格还原
- **修复自身引入的回归**：`LaunchedEffect` 内 `scrollToItem` 挂起导致文件页首屏空白 → 改为按目录重建 `LazyListState`

### 批次三：生命周期与存储（`1a0a309`）

- root 保存：解析软链真身、还原 `mode/uid/gid`、尽力 `restorecon`（旧行为会把 640 改成 644 并替换软链）
- `HorizontalPager` 保留 4 页：切标签不再丢终端输入 / 文件页多选·滚动 / 风险确认弹窗
- 编辑历史改为**每文件一个 key** + 旧数据自动迁移

### 批次六：分包 APK 可直接安装（修复「提取出来却装不上」）

- **根因**：点 `.APK` 走单文件 `pm install`，分包应用必失败（`INSTALL_FAILED_MISSING_SPLIT`）；
  会话安装 `installSplitApks()` 早已存在，但只有 `.xapk` 路径调用
- **做法**：安装前 `collectApkSet()` 聚合同目录同一套件 —— 主判定命名约定
  （`<名>-<版本>.APK` + `-splitN`，即本 App 提取产物的命名），次判定 manifest 的
  「同包名 + 同版本号」（覆盖 SAI/MT 的 `split_config.*.apk`）；`bases.size != 1` 退回单文件
- **点 base 或点任意 split 都装整套**；无 ROOT 时识别出分包给出明确提示而非 `MISSING_SPLIT`
- 新增 `ApkInstallerSetTest` 8 例；单测 212 → 220
- 真机验证：`WhatsApp-263507522.APK` + 3 个 `-splitN.APK`（92MB）经 shso 安装成功，
  `pm path com.whatsapp` 返回 base + `split_config.armeabi_v7a` + `split_config.xhdpi` + `split_i18n_ko`
- 验证后已 `pm uninstall` 还原设备（测试前该应用本就未安装）

### 批次五：新增「提取 APK」（文件页设置菜单）

- 文件页「设置」弹窗内新增 **「提取APK」**：列出已安装应用 → 导出安装包到内部存储 `Download/`
- 命名（用户指定，**后缀大写**）：基础包 `<应用名>-<版本号>.APK`；分包应用追加 `-split1.APK`、`-split2.APK`…
- 默认只列用户应用，可切「含系统应用」；应用名非法字符净化；读取 `/data/app/...` 需 ROOT
- 清单新增 `QUERY_ALL_PACKAGES`（否则 targetSdk 30+ 下 `getInstalledPackages()` 列表残缺）
- 新增 `ApkExtractorTest` 7 例（命名 / 净化 / 单包与分包产物规划）
- 真机验证：提取 `com.reveny.vbmetafix.service` → `Download/com.reveny.vbmetafix.service-1.APK`（3.15MB，魔数 `PK\x03\x04` 有效）
- **未覆盖**：本机全量扫描无任何分包应用，分包分支由纯函数单测覆盖

### 批次四：安全加固（第 7 项，`42e725e` + `7301745` + `27c1b18` + `f34eecb`）

- **解析层**：wrapper 选项绕过（`timeout 5`/`sudo -u root`/`stdbuf -o0`）、重定向目标提取、`programUnresolved`（`$IFS` 拼命令）、`eval` 载荷展开、`xargs` 派发
- **拦截规则**：加密/混淆（解码管道、`eval`+解码器、解释器内联解码、超长 base64、NUL 内容）、格机原语（分区表/刷机/mkfs/truncate/tee/sysrq-trigger）、`cp/mv/install` 写系统路径、重定向写块设备
- **分级按来源**：脚本 `CRITICAL`（自动执行直接拒）/ 终端 `DANGEROUS`（可确认）
- **fail-closed**：解析超限、>2MB 不可完整扫描、疑似加密 → 自动执行一律拒绝
- **守卫 v1.2.0**：新增 9 个包装器；安装改**原子替换 + 失败回滚**；冷启动同步 `policy.conf` 的 `mode`；审计改用线程安全 formatter 并在写入前清除软链

### 验证（真机闭环）

| 链路 | 证据 |
|---|---|
| 运行时守卫拦截 | `enforce` 下 `rm/chmod/chown/sgdisk/mkfs/mknod` 对 `/system`、`/dev/block`、`/data/adb/modules` 全部 `DENY(RC=1)`；`/sdcard` 放行 |
| 守卫安装/升级 | v1.1.0 → v1.2.0 自动升级；删模块后走全新原子安装，无 `.new/.old` 残留；失败路径 A/B/C 均保留旧版 |
| App 静态层（终端） | 输入 `r$IFSm -rf /system` → 弹窗 `[DANGEROUS] 命令名含未解析变量…` |
| App 静态层（脚本） | 同内容 `.sh` 点「执行」→ 扫描显示 `第 2 行 [CRITICAL] …` |
| 自动执行拒绝 | 「添加到 shso」触发 → 终端输出拦截原因、脚本未执行（`/system/bin/x` 不存在）、审计 `CRITICAL\|SCRIPT_FILE\|BLOCK\|UNRESOLVED_PROGRAM` |
| 兜底分支 | 40KB 单行 → `LINE_TOO_COMPLEX` 拒绝；2.1MB → `SCRIPT_UNREADABLE` 拒绝；两者均落审计 |

---

## 🔒 已定结论（避免反复推翻）

1. **`/data/adb/shso` 必须 777** —— 需让其他应用自由读写；曾改 755，用户明确要求回退。
2. **大文件阈值 128KB** —— 按低端机实测定（32/64KB 秒开；256KB 约 30s；2MB 数分钟无响应）。
3. **release 已开启 R8 + shrinkResources** —— 资源会被重命名为随机短名，**不要按 APK 内资源名反查源码资源**。
4. **`Process.pid()` 在 Android 不存在** —— 取子进程 pid 只能反射；中断正确性由**进程组回收**保证。
5. **编辑器载入归一为 LF、保存按 `currentLineEnding` 还原** —— 改 `LineEnding.apply` 需同步该契约。
6. **风险等级一律按来源分级** —— 混淆/未解析类在 `SCRIPT_FILE` 为 `CRITICAL`（自动执行拒），`USER_TERMINAL` 为 `DANGEROUS`（可确认）；不要为「统一」而抹平。
7. **守卫是 PATH 前置型，能力有边界** —— 绝对路径调用与 `PATH` 重置可绕过；不要据此认为「装了守卫就万无一失」。
8. **新增守卫包装器必须三处同步** —— `gen_wrappers.py` specs、`assets/shso_guard.zip`、`REQUIRED_ARCHIVE_ENTRIES`，并升 `module.prop` 版本。
9. **不要在 `LaunchedEffect` 里直接 `scrollToItem`** —— 列表未组合时会挂起并阻塞后续逻辑。
10. **档位 ≤1 时策略层一律放行** —— 验证拦截必须用档位 ≥2。
11. **分包安装必须走 `pm install-create/-write/-commit` 且分片先拷到 `/data/local/tmp`** —— 真机实测 `install-write` 直接读 `/storage` 会被 SELinux 拒绝（system_server 无权读 emulated 存储）；单文件 `pm install` 对分包应用必定 `INSTALL_FAILED_MISSING_SPLIT`。
12. **套件聚合宁少勿错** —— `collectApkSet` 定位不到唯一基础包时必须退回单文件安装，绝不猜测（否则会把两份同包同版本的基础包塞进一个会话）。

---

## ⚠️ 待决事项（需用户确认）

1. 设备上的安全档位现为 **0**（测试后还原）。若要实际启用防护，请在设置中切到 **2/3**。
2. `/data/adb/shso` 下用户自带的测试文件（`test.number.sh`、`num_*.txt` 2MB/1MB）是否清理 —— **未动**，等确认。

---

## 🧪 真机与环境备忘

### 真机操作（BIYLBAFQQSS8DA69，**性能较弱**）

- **每次点击后等 3–5 秒**再执行下一步，否则容易误操作（页面未切换完成）。
- 底部导航坐标：`主页 153 / 终端 411 / 文件 669 / 设置 927`，y = `2034`。
- 文件页到 `/data/adb/shso`：直接点顶栏「shso」面包屑（约 `625,174`）。
- 文件页默认目录是「内部存储」（`/storage/emulated/0`）；要找文件需先下滑（目录恒在文件之前）。
- 文件的**单击**=动作菜单（含「添加到shso」「执行」等在行内/菜单内），**长按**=多选模式弹窗。
- 输入法已关闭（不会因聚焦输入框弹起），布局稳定。

### 环境坑（会导致误判，务必注意）

| 坑 | 表现 / 处理 |
|---|---|
| Git Bash MSYS 路径转换 | `adb push <local> /data/...` 会被改成 `C:/Program Files/Git/data/...` → 必须 `export MSYS_NO_PATHCONV=1` |
| `adb install` 相对路径 | 需要绝对路径（shell 不保留上一条命令的 `cd`），否则 `failed to stat` |
| Android mksh 算术是 **32 位** | `date +%s%N` 参与 `$(( ))` 被截断 → 真机计时用 POSIX `time` |
| toybox `file` **不支持 `-b`** | 传 `-b` 只输出错误文本；`cat` 也不支持 `-A` |
| 应用 uid 写 `/data/adb/` 受 **SELinux** 限制 | 即便 `chmod 777`，`run-as` 仍 `Permission denied` → 判可写性必须实测 |
| uiautomator dump 不含视口外内容 | 长列表超出视口（含普通 `Column + verticalScroll` 的项）**不会**出现在 dump 里；据此判断「列表被截断」是误报 |
| 用 `su -c` 传含 `$`/`\` 的脚本内容 | 多层引号会被吃掉（曾把 `r$IFSm` 写成 `r\`）→ 改用「本地写文件 → push → `cp`」 |
| `addFileToShso` 源与目标同目录 | `cp` 同文件失败 → 触发自动执行的测试样本必须放在 shso 目录**之外** |

### 验证命令速查

```bash
export MSYS_NO_PATHCONV=1
adb -s BIYLBAFQQSS8DA69 shell "su -c 'ls -ld /data/adb/shso'"                       # 权限应为 777
adb -s BIYLBAFQQSS8DA69 shell "su -c 'grep ^version= /data/adb/modules/shso_guard/module.prop'"
adb -s BIYLBAFQQSS8DA69 shell "su -c 'grep -E \"^[[:space:]]*mode\" /data/adb/shso_guard/policy.conf'"
# 守卫拦截检查（dangerous 命令应 RC=1）
adb -s BIYLBAFQQSS8DA69 shell "su -c 'export PATH=/data/adb/modules/shso_guard/guard:/sbin:/system/bin; rm -rf /system/__probe__; echo RC=\$?'"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
python build_apk.py --variant Debug --skip-check                                    # 含签名 + 版本规则校验
```

---

## 📂 旧看板索引

| 文件 | 涵盖范围 |
|---|---|
| `TASKS-old-20260911-v18final.md` | 任务 18–31 全量过程与证据 |
| `TASKS-old-20260911-v17final.md` | 更早一版（17 项，含 R8 / 守卫 / 终端 / 编辑器优化） |

---

## 🗂️ 相关文档

- `README.md` —— 功能总览、安全与防护说明、更新日志、版本号规则、在线编译指引
- `docs/PROJECT.md` —— 技术栈、目录结构、架构模式、**安全子系统与已知注意点**
- `module/shso_guard/README.md` —— 守卫模块说明（策略语法、包装器生成、测试脚本）
- `.github/workflows/publish-release.yml` —— 纯日期标签发布
- `.github/workflows/build-apk.yml` —— 在线编译（自定义包名）
