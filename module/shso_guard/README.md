# shso_guard —— shso 指令守卫

shso 的配套 root 模块。**运行时**拦截 `rm` / `dd` / `mkfs` 等高危命令，
保护系统分区，并把每一次调用落进审计日志。

## 它解决什么问题

shso App 侧的指令审查（`ScriptAuditor`）是**静态文本分析**：读脚本内容找危险模式。
这在原理上可被绕过：

```sh
python  -c 'import shutil; shutil.rmtree("/system")'   # 不含 rm 关键字
echo Ym0gLXJmIC8= | base64 -d | sh                     # 解码后才出现 rm -rf /
eval "$(printf '\162\155') -rf /"                      # 八进制转义
```

本模块换了一层：**不分析文本，而是在命令真正被调用的那一刻拦下来**。
无论脚本用 `python -c`、`eval`、`base64 | sh` 还是嵌套 `$()` 触发，
只要它最终去调用 `rm`，就会先经过守卫。

| 层 | 位置 | 能力 | 拦不住的情况 |
|---|---|---|---|
| L1 静态审查 | App（Kotlin） | 弹确认框、展示风险行号 | 混淆 / 等价改写 |
| **L2 运行时守卫** | **本模块** | 凡经 `PATH` 解析到被守卫命令的调用都会先过守卫（含 `toybox` / `busybox` 子命令派发） | 写绝对路径 `/system/bin/rm`；自行改写 `PATH`；不经外部命令的实现（见「能力边界」） |
| L3 审计留痕 | 两侧共用同一日志 | 事后追溯 | 有 root 即可篡改日志 |

## 安装

模块 ID：`shso_guard`（一经发布永不修改）。

### 方式 A：复制安装（shso App 集成推荐）

**安装模块 == 把文件放进 `/data/adb/modules/`**，不需要刷机、不需要重启：

```sh
cp -R  shso_guard  /data/adb/modules/shso_guard
chmod -R 0755      /data/adb/modules/shso_guard
chmod    0644      /data/adb/modules/shso_guard/module.prop
```

> **注意**：这种方式 `customize.sh` **不会执行**。因此本模块被设计成开箱即用：
> - `guard/` 下的守卫是**预生成**的（由 `gen_wrappers.py` 从 `guard-template.sh` 生成，
>   两者保持「除两处占位符外逐字节相同」的不变式），不依赖安装期逻辑；
> - 真实二进制路径由守卫在**运行时**按 PATH 查找（`exec_real()`），
>   既不硬编码路径，也不依赖 busybox 位置；
> - `policy.conf` 随模块自带，`service.sh` 开机时兜底部署到 `/data/adb/shso_guard/`。

**守卫装完立刻生效**（拦截靠 PATH，不靠开机脚本）。
重启只是为了让模块在 Magisk / KSU / APatch 管理器里可见、并让 `service.sh` 跑一遍善后。

### 方式 B：刷 zip

```bash
python scripts/build.py . -o ../shso_guard.zip
```

Magisk App / KernelSU / APatch 均可直接刷入。Recovery 仅 Magisk 支持。

## 生效原理

守卫脚本与真实命令**同名**，放在 `guard/` 目录；把该目录前置到 `PATH` 后，
`rm` 会先命中守卫，守卫判定放行时再 `exec` 真实二进制：

```text
脚本里写 rm -rf /system
        │
        ▼  PATH 查找：/data/adb/modules/shso_guard/guard/rm
   ┌─────────────────┐
   │ 提取操作数 /system │
   │ 归一化（realpath） │   ← 防 /system/../data、符号链接绕过
   │ 对照 policy.conf  │   ← allow 优先于 protect
   │ 审计落盘          │   ← 先留痕，后动作
   └────────┬────────┘
       DENY │        │ ALLOW
            ▼        ▼
       exit 1    exec /system/bin/rm …
```

关键实现细节：
- **防递归**：`exec_real()` 遍历 PATH 时跳过 guard 目录（判据是「该目录下有
  `common.sh`」，对尾斜杠 / `//` / `/./` / 符号链接等写法差异天然免疫）。
  另有导出计数器 `SHSO_GUARD_DEPTH`，深度超限即 fail-closed（防 fork 炸弹）。
- **符号链接防绕过**（关键，勿简化）：逐级扫描路径分量，命中**第一处符号链接**时
  只解析该链接本身，再把其后分量词法接回。
  **不能**对整条路径直接调 `realpath` —— 需求场景 `cp f <link>/new`（叶子尚不存在）
  下 `realpath` 会失败，若就此退回纯词法，`<link>/new` 会被判为 ALLOW，
  而内核实际写入的是链接目标（可能就是 `/system`）。这是一条**真实可复现的绕过**。
  无法解析时按 `PATH_UNRESOLVABLE` 拒绝。
- **fail-closed**：`common.sh` 缺失、策略无法加载、二进制找不到、路径无法解析时，
  守卫一律**拒绝执行**并退出非 0，绝不静默放行。
- **根路径特殊处理**：`protect=/` 只做精确匹配（只拦 `rm -rf /`），
  否则 `/` + `/*` 的模式会误伤所有绝对路径。
- **`dd` 看 `of=`**：真正造成破坏的是输出目标，不是输入（兼容 `of =x` 空格变体）。
- **`fastboot` 看任意位置的子命令**：`-w` / `--wipe` / `erase` / `format` /
  `wipe` / `flash` 一律高危。
- **`wipe` 无操作数**：等于「擦默认设备」，是最危险的形态，直接拒绝。
- **性能铁律**：`load_policy` 与路径判定位于**每次调用**的热路径上，
  其中**禁止**任何会创建子进程的写法。实测：旧实现每行 `printf|tr` + `$(trim_ws)`
  使单次 `rm` 创建约 150 个进程，真机上 60 次 `cp` 需 **159 秒**；
  改为纯 POSIX 参数展开 + 全局变量返回后降到 **3.6 秒（43.8×）**。

## shso App 集成点

App 侧只需把守卫目录前置到 PATH（**目前尚未改动，待确认**）：

1. **脚本执行** —— `RootService.executeFile()`（`RootService.kt:239`）构造命令处，
   在 `export PATH=...` 最前面插入守卫目录：

```kotlin
val guardDir = "/data/adb/modules/shso_guard/guard"
val guardPath = if (File("$guardDir/rm").exists()) "$guardDir:" else ""
val execCmd = "export PATH=$guardPath/sbin:/system/sbin:/system/bin:/system/xbin:\$PATH && ..."
```

2. **终端输入** —— 常驻 root shell 启动时同样前置该目录，
   使 `sendInput()` 送进去的命令也经过守卫。

守卫未安装时 `guardPath` 为空串，行为与现在完全一致（优雅降级）。

## 策略配置

文件：`/data/adb/shso_guard/policy.conf`（修改后即时生效，无需重启）

```conf
mode=enforce        # enforce=拦截并记录 | log=只记录 | off=放行（排障用）
protect=/system     # 受保护路径，命中即拦
allow=/sdcard       # 豁免路径，优先级高于 protect
```

内置默认值见 `policy.conf`。判定顺序：**allow → protect**，
因此 `/data/media` 可以豁免 `/data` 的保护。

守卫找不到策略文件时会退回内置兜底清单，保证「裸装也受保护」。

## 审计日志

文件：`/data/adb/shso/audit.log`（与 App 侧共用，统一视图）

```text
2026-09-05 23:41:07|GUARD|DENY|PROTECTED_PATH|cmd=rm|args=-rf /system|path=/system
2026-09-05 23:41:09|GUARD|ALLOW|NONE|cmd=rm|args=-f /sdcard/a.log|path=/sdcard/a.log
```

超过 `AUDIT_MAX_LINES + 400` 行时保留尾部 `AUDIT_MAX_LINES` 行（默认 2000）。
轮转使用**每进程唯一**的临时名（`audit.log.$$.tmp`），避免并发守卫调用互相覆盖丢行；
行数统计按 PID 抽样（每 16 次调用一次），把 `wc -l` 的整文件扫描成本均摊到可忽略。

## 覆盖范围

| 命令 | 判定依据 | 备注 |
|---|---|---|
| `rm` `rmdir` `shred` `truncate` `wipe` `mkfs.*` `mke2fs` `make_f2fs` | 所有非选项操作数 | `wipe` 无参数视为最危险 → 拒绝 |
| `dd` | `of=<路径>`（含 `of =x`、`of= x`） | 只关心输出目标 |
| `fastboot` | 任意位置的 `-w` / `--wipe` / `erase` / `format` / `wipe` / `flash` | — |
| `mv` | **源 + 目标**（把受保护路径移走同样是销毁） | — |
| `cp` | **目标**（可覆盖 `/system` 下的文件） | — |
| `find` | **仅当含 `-delete`**，或 `-exec`/`-execdir` 后接 `rm`/`rmdir`/`sh`/`bash` | 常规查找零干预 |
| `sed` | **仅当含 `-i`**；会正确跳过 sed 脚本参数 | 常规流式编辑零干预 |
| `toybox` `busybox` | 子命令命中上表任一即 shift 后按上表判定 | 否则原样透传，零干预 |

高频命令（`cp` / `find` / `sed` / `mv`）在**不需要介入**时只做最小判定就立即
`exec` 真实二进制，不产生额外行为变化。

## 能力边界（务必了解）

本模块**不是**安全沙箱。它是 **PATH 前置层**——只有「通过 `PATH` 查找该命令」的
调用才会命中守卫。以下情况**拦不住**：

1. **绝对路径调用**：`/system/bin/rm -rf /system`、`/system/bin/toybox rm -rf /`、
   `/apex/com.android.runtime/bin/rm` 均**绕过 PATH**，守卫不会介入。
2. **`command -p` / 显式指定搜索路径**：`command -p rm -rf /` 使用系统默认 PATH。
3. **自行改写 `PATH`**：脚本里 `export PATH=/system/bin:$PATH` 即可摘掉守卫
   （本模块的 `PATH` 前置是为「正常调用」设计的，不是不可摘除的钩子）。
4. **shell 重定向直接写设备/系统文件**：
   `: > /system/build.prop`、`echo x > /dev/block/by-name/boot` 都不 fork 任何
   被守卫命令，守卫**完全看不到**。
5. **同进程内完成破坏的实现**：`python -c 'shutil.rmtree("/system")'`、
   `busybox` 静态链接进其他程序等，只要不 fork 被守卫命令名，就不经过守卫。
6. **有 root 就能改配置/日志**：`policy.conf` 与 `audit.log` 都在 `/data/adb/` 下，
   决意的用户（或另一个 root 进程）可以改写 `mode=off` 或删日志。

所以定位很清楚：**拦误操作 + 高危留痕 + 提高恶意脚本的门槛**。
真正的主防线仍然是 **L1 静态审查 + 档位 3 的「脚本默认不以 Root 执行」**。

## 通用性

| Root 方案 | 支持 | 说明 |
|---|---|---|
| Magisk | 原生支持 | — |
| KernelSU | 原生支持 | 本模块**不使用 `system/`**，无需 metamodule |
| APatch | 原生支持 | — |

模块为**纯脚本档位**（B 档）：仅 `module.prop` + `customize.sh` + `service.sh` +
`uninstall.sh` + `guard/` + `policy.conf`，零 `system/` 依赖，三种方案行为一致。

```
shso_guard/
├── module.prop              # 模块元信息（id=shso_guard，永不改）
├── policy.conf              # 随模块自带的默认策略（LF 行尾）
├── service.sh               # 开机兜底：把 policy.conf 部署到 /data/adb/shso_guard/
├── customize.sh             # 刷 zip 安装时执行（复制安装不会执行）
├── uninstall.sh             # 卸载：删除 /data/adb/shso_guard/（审计日志保留）
├── guard-template.sh        # 守卫模板（__CMD_NAME__ / __OPERAND_MODE__ 两处占位符）
├── gen_wrappers.py          # 从模板生成 25 个守卫，保证模板与产物不漂移
└── guard/
    ├── common.sh            # 共用引擎：策略加载 / 路径归一化 / 判定 / 审计 / exec_real
    ├── rm rmdir shred truncate wipe dd fastboot          # 模板生成（删除 / 覆写）
    ├── mkfs.ext4 mkfs.f2fs mkfs.vfat mke2fs make_f2fs   # 模板生成（格式化）
    ├── mv cp find sed                                    # 模板生成（移动 / 拷贝 / 原地修改）
    ├── chmod chown chgrp mkfs mknod                      # 模板生成（v1.2.0：权限崩坏）
    ├── sgdisk parted fdisk flash_image                   # 模板生成（v1.2.0：分区表 / 刷机）
    └── toybox busybox       # 多二进制派发（结构特殊，不由模板生成）
```

> **新增包装器需三处同步**：`gen_wrappers.py` 的 specs、`app/src/main/assets/shso_guard.zip`
> （重打包）、`GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES`（必需条目清单）；并升
> `module.prop` 的 `version=`，否则已装用户不会触发升级。

改动公共行为后，**必须重新生成守卫**：

```bash
python gen_wrappers.py     # 校验：生成的产物应与 guard/ 下对应文件逐字节一致
```
