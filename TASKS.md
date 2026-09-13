# shso 任务看板 (TASKS.md)

> 过程记录，不参与对外文档同步；现行说明见 `README.md`、`docs/PROJECT.md`、`更新日志.md`。
> 建立时间：2026-09-11（上一版归档为 `TASKS-old-20260911-v18final.md`）
> 状态规范：`[ ]` 待办 | `[/]` 执行中 | `[x]` 完成 | `[!]` 阻塞/需人工确认

---

## 版本规则

`versionCode` = 构建当日日期（`YYYYMMDD`），`versionName` = `Jinn`，Release Tag 与 `versionCode` 对齐；升级判定只认 `versionCode`。完整规则见 `README.md` § 版本规则。

守卫模块独立版本：当前 v1.2.0（`module.prop` 的 `version=`）。新增或修改包装器、`common.sh` 后必须按序执行：重新生成包装器 → 重打包 `assets/shso_guard.zip` → 同步 `GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES` → 升 `module.prop` 版本，否则已装用户不会升级。

---

## 当前状态速览（2026-09-13）

| 项 | 值 |
|---|---|
| 分支 | `main`，与 `origin/main` 同步，HEAD `3de5623` |
| 单元测试 | 242 tests / 0 failures |
| release 体积 | 2.09 MB，`verifyReleasePayload` 红线通过（≤2.2MB、无语法包、无 `tables/`） |
| 编辑器内核 | Sora Editor 0.23.6（打开即可编辑；语法由外置语法包提供） |
| 语法包 | 62 语言 / 187 扩展名，`syntax-packs.zip`(37KB)，永固直链 tag `syntaxpacks-v2` |
| 守卫模块 | v1.2.0（真机已装并验证拦截） |
| 真机 | BIYLBAFQQSS8DA69（PACM00 / Android 10 / 1080×2280 / 底部导航 y=2034） |
| 当前安全档位 | 设备上为 0（验证拦截需切到 ≥2） |

---

## 待办

### A. 本轮计划（编辑器与文件页体验）

- [x] **文件页搜索 / 过滤**（`a4e7355` 之后一次提交）——已实现
  - 搜索入口收进「文件列表设置」弹窗（`content-description="搜索文件"`），点击展开名称过滤栏，按名称子串过滤当前目录（大小写不敏感）
  - 过滤与排序合并为同一次后台遍历（`applyFileViewSettings(list, showHidden, sortMode, nameQuery)`），大目录不额外多一趟分配
  - 过滤时显示命中数（`N 项`，无命中转为警示色）；空列表区分「无匹配项：<关键字>」与「当前目录为空」
  - 切目录自动清空过滤词（`LaunchedEffect(currentDirectory)`），避免「新目录打不开」（实为空结果）
  - 真机验证：`xml`→`a1.xml/a2.xml`+「2 项」；`zzz`→「无匹配项：zzz」；清空→恢复全文；切到 `/` 后过滤词为空
- [x] **历史条目来源标记**（`EditHistoryManager.HistorySource` + `data/RelativeTime.kt`）——已实现
  - 三种来源：`SAVE` 手动保存 / `AUTO` 停顿快照（编辑停顿 2.5s）/ `DRAFT` 定时草稿；条目写入 JSON 的 `source` 字段，旧数据缺字段按 `AUTO` 兼容
  - 同内容来源升级：内容相同但来源更强（草稿→快照→保存）时原地升级来源并保留首次时间，不再新增重复条目（`HistoryMerge` 纯函数 + 5 例单测）
  - 修一个真 BUG：旧去重只过滤「等于新内容」的条目，其它内容的重复项会累积（A→B 交替编辑把 20 槽塞满两份内容）；现按内容全量去重
  - 历史面板显示来源标签（手动保存=绿 / 停顿快照=次要色 / 定时草稿=浅色）与相对时间（刚刚 / N 分钟前 / N 小时前 / N 天前 / 超 7 天给日期）；`data/RelativeTime.kt` + 4 例单测
  - 真机验证：输入两次并保存后，历史为「手动保存（C2）+ 停顿快照（C1）」两条，来源与相对时间均正确
- [ ] **语法包更新检测**（中）——直链固定到 tag，用户无法得知有新版本
  - 目标：「检查更新」比对远端版本文件与本机导入版本，提示可更新
- [ ] **长耗时批量操作进度反馈**（中）——多选复制/移动/删除无进度，界面表现为「无反应」
  - 目标：操作中显示「处理中 N/M」并可取消；失败项汇总提示
- [ ] 检查更新改用 GitHub API（低）——现用 `yezijinn/shso/tags` 页面 HTML 正则，页面结构变动即失效
- [ ] 图标按钮补 `contentDescription`（低）——文字按钮已自带语义，仅图标按钮受影响

### B. 安全后续（未安排）

- [ ] **安全第三轮（可选）**：`GuardModuleInstaller` 卸载残留（`/data/adb/shso_guard/policy.conf` 与审计日志）；`ScriptAuditor` 跨行变量追踪；`$IFS` 之外的 shell 展开（`${x:-…}`、算术展开）
- [ ] **内核级守卫（独立议题）**：PATH 前置型守卫无法拦绝对路径调用与 `PATH` 重置，彻底封堵需 seccomp/LSM hook

### C. 待决事项（需用户确认）

1. 设备上的安全档位现为 0（测试后还原）。若要实际启用防护，请在设置中切到 2/3。
2. `/data/adb/shso` 下用户自带的测试文件（`test.number.sh`、`num_*.txt`）是否清理 —— 未动，等确认。
3. 语法包更新需重打 tag（`syntaxpacks-v3`…）并同步 `SyntaxPackUrls.TAG` 常量。

---

## 本轮已完成（2026-09-12 ~ 09-13：编辑器引擎与语法高亮系列）

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

1. **`/data/adb/shso` 必须 777** —— 需让其他应用自由读写；曾改 755，用户明确要求回退。
2. **release 已开启 R8 + shrinkResources** —— 资源会被重命名为随机短名，不要按 APK 内资源名反查源码资源。
3. **`Process.pid()` 在 Android 不存在** —— 取子进程 pid 只能反射；中断正确性由进程组回收保证。
4. **编辑器载入归一为 LF、保存按 `currentLineEnding` 还原** —— 改 `LineEnding.apply` 需同步该契约。
5. **风险等级一律按来源分级** —— 混淆/未解析类在 `SCRIPT_FILE` 为 `CRITICAL`（自动执行拒），`USER_TERMINAL` 为 `DANGEROUS`（可确认）。
6. **守卫是 PATH 前置型，能力有边界** —— 绝对路径调用与 `PATH` 重置可绕过；不要据此认为「装了守卫就万无一失」。
7. **新增守卫包装器必须三处同步** —— `gen_wrappers.py` specs、`assets/shso_guard.zip`、`REQUIRED_ARCHIVE_ENTRIES`，并升 `module.prop` 版本。
8. **不要在 `LaunchedEffect` 里直接 `scrollToItem`** —— 列表未组合时会挂起并阻塞后续逻辑。
9. **档位 ≤1 时策略层一律放行** —— 验证拦截必须用档位 ≥2。
10. **分包安装必须走 `pm install-create/-write/-commit` 且分片先拷到 `/data/local/tmp`**。
11. **套件聚合宁少勿错** —— `collectApkSet` 定位不到唯一基础包时退回单文件安装，绝不猜测。
12. **APK 不得内置语法包** —— 语法由用户导入（本地 zip / 仓库直链）；`verifyReleasePayload` 为强制红线，确需上调体积上限须连同理由一起改常量。
13. **保存必须原子** —— 临时文件与目标同目录、`renameTo`/`mv` 覆盖、还原 `mode/uid/gid`、失败清理；禁止直接 `writeBytes` 到目标。
14. **编码必须严格** —— 用 `CharsetEncoder` + `REPORT`；禁止 `String.toByteArray(charset)` 的静默 `?` 替换。
15. **Sora 的检索与替换都是异步的** —— `replaceAll/replaceCurrentMatch` 在检索未结束（`isResultValid()==false`）时只弹 Toast 后返回；任何「搜索后立即读结果/替换」都必须先等结果集写入。
16. **`packaging.resources.excludes` 必须保留 `"tables/**"` —— jcodings 的 648 个编码表会打进 APK 根目录（1.24MB）；正则只用 UTF-8/ASCII-8BIT 内建编码，不查表。
17. **语法解析器顺序不可颠倒** —— `FileProviderRegistry.addProvider` 先应用私有目录、后 assets；`AssetsFileResolver` 对缺失路径不捕获异常，排在前面会中断整条解析链。
18. **Monarch 主题必须覆盖语法用到的全部令牌作用域**（含 `identifier`、`attribute`）—— 未匹配令牌落回黑色；主题加载失败时不启用语法。

---

## 校验与验证命令

```bash
export MSYS_NO_PATHCONV=1
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'

./gradlew :app:testDebugUnitTest :app:assembleDebug          # 基线 233 tests / 0 failures
./gradlew :app:assembleRelease                               # 含 verifyReleasePayload 红线校验
python tools/gen_syntax_packs.py                             # 重新生成语法包（syntax-packs/ + syntax-packs.zip）

adb -s BIYLBAFQQSS8DA69 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s BIYLBAFQQSS8DA69 shell "logcat -d -s shso-perf"       # 语法就绪/注册日志
adb -s BIYLBAFQQSS8DA69 shell "su -c 'ls -ld /data/adb/shso'"        # 权限应为 777
adb -s BIYLBAFQQSS8DA69 shell "su -c 'grep ^version= /data/adb/modules/shso_guard/module.prop'"
```

---

## 真机与环境备忘

### 真机操作（BIYLBAFQQSS8DA69，PACM00 / Android 10 / 1080×2280）

- 底部导航坐标：`主页 153 / 终端 411 / 文件 669 / 设置 927`，y = `2034`；点击后等 3–5 秒。
- 文件页默认「内部存储」；目录恒排在文件之前。
- 文件单击=动作菜单（含「编辑文本」），长按=多选模式。
- 截图前先 `input keyevent KEYCODE_WAKEUP`，否则可能得到黑屏。
- 清理 `/sdcard` 测试文件需 `su -c`（应用 push 的文件属 root，adb shell 直接删会失败）。

### 环境坑（会导致误判，务必注意）

| 坑 | 表现 / 处理 |
|---|---|
| Git Bash MSYS 路径转换 | `adb push <local> /data/...` 会被改成 `C:/Program Files/Git/data/...` → 必须 `export MSYS_NO_PATHCONV=1` |
| `adb install` 相对路径 | 需要绝对路径（shell 不保留上一条命令的 `cd`），否则 `failed to stat` |
| Android mksh 算术是 32 位 | `date +%s%N` 参与 `$(( ))` 被截断 → 真机计时用 POSIX `time` |
| toybox `file` 不支持 `-b`，`cat` 不支持 `-A` | 传了只输出错误文本 |
| 应用 uid 写 `/data/adb/` 受 SELinux 限制 | 即便 `chmod 777` 仍可能 `Permission denied` → 判可写性必须实测 |
| uiautomator dump 不含视口外内容 | 长列表超出视口的内容不会出现在 dump 里，据此判断「列表被截断」是误报 |
| uiautomator bounds 对 Compose 文本按钮纵向偏上（实测约 70px） | 报 `[x1,521][x2,624]`，实际命中区在 590–610；按 bounds 中心点击会「点了没反应」，先做 y 方向小范围扫描再判定 |
| 用 `su -c` 传含 `$`/`\` 的脚本内容 | 多层引号会被吃掉 → 改用「本地写文件 → push → `cp`」 |
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
| `README.md` | 功能总览、安全模型、版本规则、在线编译入口 |
| `更新日志.md` | 变更清单，一行一条 |
| `docs/PROJECT.md` | 技术栈、目录结构、架构、安全子系统与已知注意点 |
| `module/shso_guard/README.md` | 守卫模块：策略语法、包装器生成、测试脚本 |
| `.github/workflows/publish-release.yml` | 纯日期标签发布 |
| `.github/workflows/build-apk.yml` | 在线编译（自定义包名） |
