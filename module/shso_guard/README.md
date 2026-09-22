# shso_guard

shso 的运行时守卫模块：在命令被调用的时刻拦截 `rm` / `dd` / `mkfs` 等高危操作，
保护系统分区并记录审计日志。

- 模块 ID：`shso_guard`（一经发布不再修改），当前版本 v1.3.0（`module.prop` 的 `version=`）。
- 相关文档：[`README.md`](../../README.md)、[`docs/PROJECT.md`](../../docs/PROJECT.md)。

## 分层定位

| 层 | 位置 | 能力 | 拦不住 |
|---|---|---|---|
| L1 静态审查 | App（`security/ScriptAuditor`） | 弹确认框、展示风险行号 | 混淆与等价改写 |
| L2 运行时守卫 | 本模块 | 凡经 PATH 解析到被守卫命令的调用都会先过守卫（含 `toybox` / `busybox` 子命令派发） | 绝对路径调用、自行改写 `PATH`、不经外部命令的实现 |
| L3 审计 | 两侧共用同一日志 | 事后追溯 | 有 root 即可篡改日志 |

App 侧静态分析可被绕过，本模块不分析文本，而是在命令执行时拦截：

```sh
python  -c 'import shutil; shutil.rmtree("/system")'   # 不含 rm 关键字
echo Ym0gLXJmIC8= | base64 -d | sh                     # 解码后才出现 rm -rf /
eval "$(printf '\162\155') -rf /"                      # 八进制转义
```

只要最终调用被守卫的命令，就会先经过守卫。

## 安装

**方式 A：复制安装（App 集成采用）**

把文件放进 `/data/adb/modules/` 即可，无需刷机或重启：

```sh
cp -R  shso_guard  /data/adb/modules/shso_guard
chmod -R 0755      /data/adb/modules/shso_guard
chmod    0644      /data/adb/modules/shso_guard/module.prop
```

这种方式 `customize.sh` 不会执行，因此模块被设计为开箱即用：

- `guard/` 下的守卫预生成（由 `gen_wrappers.py` 从 `guard-template.sh` 生成，
  两者除两处占位符外逐字节相同），不依赖安装期逻辑。
- 真实二进制路径由守卫在运行时按 PATH 查找（`exec_real()`），不硬编码。
- `policy.conf` 随模块自带，`service.sh` 开机时兜底部署到 `/data/adb/shso_guard/`。

装完立即生效（拦截靠 PATH，不靠开机脚本）；重启只是让模块在 Magisk / KernelSU /
APatch 管理器中可见，并让 `service.sh` 跑一遍善后。

App 内的安装为原子替换（v1.2.0 起，`GuardModuleInstaller`）：解压 APK 内
`assets/shso_guard.zip` 到缓存并校验必需条目 → 在同文件系统构建
`/data/adb/.shso_guard.new` → 校验 → 旧目录挪 `.old` → `mv` → 清理；
任一步失败都保留或回滚旧版本。升级判定比对 `module.prop` 的 `version=`，
因此新增包装器必须升版本号。手动复制安装没有回滚，建议先备份旧目录。

**方式 B：刷 zip**

```bash
python scripts/build.py . -o ../shso_guard.zip
```

Magisk / KernelSU / APatch 均可刷入；Recovery 仅 Magisk 支持。

## 生效原理

守卫脚本与真实命令同名，位于 `guard/`；该目录前置到 PATH 后，
`rm` 先命中守卫，判定放行时再 `exec` 真实二进制：

```text
脚本里写 rm -rf /system
        │
        ▼  PATH 查找：/data/adb/modules/shso_guard/guard/rm
   ┌─────────────────┐
   │ 提取操作数 /system │
   │ 归一化（含符号链接）│   防 /system/../data 与符号链接绕过
   │ 对照 policy.conf  │   allow 优先于 protect
   │ 审计落盘          │   先留痕，后动作
   └────────┬────────┘
       DENY │        │ ALLOW
            ▼        ▼
       exit 1    exec /system/bin/rm …
```

实现要点：

- 防递归：`exec_real()` 遍历 PATH 时跳过守卫目录（判据为「该目录下有 `common.sh`」，
  对尾斜杠、`//`、`/./`、符号链接等写法免疫）；另有深度计数器
  `SHSO_GUARD_DEPTH` 超限即 fail-closed。该计数器定位为**健壮性**防线
  （防误配置触发 fork 炸弹），不是安全边界 —— 它来自可被调用者重置的环境变量，
  真正的防递归是 `exec_real()` 的目录跳过。v1.3.0 起校验其必须为十进制并收紧上限到 3。
- 符号链接处理：逐级扫描路径分量，命中第一处符号链接时只解析该链接本身，
  再把其后分量词法接回。不能对整条路径直接 `realpath` ——
  `cp f <link>/new` 场景下叶子不存在，`realpath` 会失败，若退回纯词法则
  `<link>/new` 会被判为 ALLOW，而内核实际写入的是链接目标（可能是 `/system`）。
  无法解析时按 `PATH_UNRESOLVABLE` 拒绝。
- fail-closed：`common.sh` 缺失、策略无法加载、二进制找不到、路径无法解析时一律拒绝。
- `protect=/` 只做精确匹配（仅拦 `rm -rf /`），避免 `/` + `/*` 误伤所有绝对路径。
- `dd` 看 `of=`（兼容 `of =x` 空格变体）；`fastboot` 看任意位置的
  `-w` / `--wipe` / `erase` / `format` / `wipe` / `flash`；`wipe` 无操作数直接拒绝。
- 性能：`load_policy` 与路径判定位于每次调用的热路径，禁止创建子进程。
  旧实现每行 `printf | tr` + `$(trim_ws)` 使单次 `rm` 创建约 150 个进程，
  真机 60 次 `cp` 需 159 秒；改为纯 POSIX 参数展开后为 3.6 秒。

## App 集成点

App 侧已完成集成，守卫目录由 `GuardModuleInstaller.GUARD_BIN_DIR`
（`/data/adb/modules/shso_guard/guard`）统一提供：

1. **受保护的 root 执行**：`GuardPathPolicy.prefixOrNull()` 生成前缀
   `export PATH=<GUARD_BIN_DIR>:/sbin:/system/sbin:/system/bin:/system/xbin && …`。
   - 档位 < 2：返回空串，不前置守卫。
   - 档位 ≥ 2 且守卫就绪：前置守卫目录。
   - 档位 ≥ 2 但守卫未安装：返回 `null`，上层落审计 + 首次告警后**放行**（不阻断 ——
     档位 2 是默认档位，硬阻断会让未装守卫的设备连 `ls` 都跑不了）。
2. **安装与升级**：`GuardModuleInstaller` 按原子替换流程执行；
   「守卫 bin 目录是否就绪」探测结果缓存 60 秒，切换档位会失效该缓存。
3. **策略同步**：切换档位与冷启动时同步 `policy.conf` 的 `mode`
   为当前档位对应值（`off` / `log` / `enforce`）。
4. **终端输入**：常驻 root shell 使用同一前缀，`sendInput()` 送入的命令同样经过守卫。

守卫未安装或档位不足时行为与未集成时一致。

## 策略配置

文件：`/data/adb/shso_guard/policy.conf`，修改后即时生效。

```conf
mode=enforce        # enforce=拦截并记录 | log=只记录 | off=放行（排障用）
protect=/system     # 受保护路径，命中即拦
allow=/sdcard       # 豁免路径，优先级高于 protect
```

判定顺序为 allow → protect，因此 `/data/media` 可豁免 `/data` 的保护。
找不到策略文件时退回内置兜底清单。

### 环境变量（v1.3.0 收紧）

安全关键路径**不接受任意环境覆盖**：守卫的防护对象就是"将被执行的命令"，
若允许它把策略文件指向自己可写的路径，守卫等于自解除。

| 变量 | v1.3.0 行为 |
|---|---|
| `SHSO_POLICY` | 仅接受 `/data/adb/shso_guard/` 前缀（root 0755），其余回落默认策略文件。原实现对任意路径生效，实测 `SHSO_POLICY=<mode=off 文件> rm -rf /system/x` 可整体关闭守卫 |
| `SHSO_AUDIT` | 仅接受 `/data/adb/shso/` 前缀，其余回落默认日志 |
| `SHSO_AUDIT_MAX` | 不再生效（固定 2000 行；仅影响日志长度，无排障价值，却可被用来撑爆分区） |
| `SHSO_GUARD_DEPTH` | 仅作辅助计数，必须为十进制，上限 3 |

排障请改用策略文件里的 `mode=log`。

## 审计日志

文件：`/data/adb/shso/audit.log`（与 App 侧共用）。

```text
2026-09-05 23:41:07|GUARD|DENY|PROTECTED_PATH|cmd=rm|args=-rf /system|path=/system
2026-09-05 23:41:09|GUARD|ALLOW|NONE|cmd=rm|args=-f /sdcard/a.log|path=/sdcard/a.log
```

超过 `AUDIT_MAX_LINES + 400` 行时保留尾部 `AUDIT_MAX_LINES` 行（固定 2000，
v1.3.0 起不再接受 `SHSO_AUDIT_MAX` 覆盖）。轮转使用每进程唯一的临时名（`audit.log.$$.tmp`），
避免并发调用互相覆盖；行数统计按 PID 抽样（每 16 次调用一次），
把 `wc -l` 的整文件扫描成本均摊。

写入前做两件事（v1.3.0）：

1. **字段清洗**：把字段内的 `|` 转义为 `\u007C`、换行转义为 `\n`、剥离控制字符
   ——审计行以 `|` 分隔，未清洗时字段可错位、并可用换行注入完整伪造行。
2. **目标校验**：`audit.log` 若为软链或非常规文件，先用 `/system/bin/rm -f --` 清除；
   清除失败则**放弃本次写入**（不退化为继续追加）。审计目录为 0777（刻意），
   第三方可在其中放置软链让 `>>` 跟随写入任意文件。

App 侧 `SecurityAuditLog` 另有字节级环形滚动（超过 512KB 裁剪保留约 256KB），
两侧写同一文件，行数上限与字节上限同时生效。

## 覆盖范围

| 命令 | 判定依据 | 备注 |
|---|---|---|
| `rm` `rmdir` `shred` `truncate` `wipe` `mkfs.*` `mke2fs` `make_f2fs` | 所有非选项操作数 | `wipe` 无参数视为最危险，拒绝 |
| `dd` | `of=<路径>`（含 `of =x`、`of= x`） | 只关心输出目标 |
| `fastboot` | 任意位置的 `-w` / `--wipe` / `erase` / `format` / `wipe` / `flash` | — |
| `mv` | 源 + 目标 | 把受保护路径移走同样是销毁 |
| `cp` | 目标 | 可覆盖 `/system` 下的文件 |
| `ln` | 目标（末操作数） | v1.3.0 新增，可把块设备指向 `/dev/null` |
| `install` | 目标（末操作数） | v1.3.0 新增，带属性的 `cp` |
| `tee` | 所有非选项操作数 | v1.3.0 新增，管道内容直接写目标文件 |
| `find` | 仅当含 `-delete`，或 `-exec` / `-execdir` 后接 `rm` / `rmdir` / `sh` / `bash` / 解释器（按 basename 判定） | v1.3.0 起 `-exec /system/bin/rm`、`-exec toybox rm` 也介入；常规查找零干预 |
| `sed` | 仅当含 `-i` / `--in-place`，会跳过 sed 脚本参数 | v1.3.0 起认长选项 |
| `chmod` `chown` `chgrp` | 所有非选项操作数 | v1.2.0 新增，防权限崩坏 |
| `mknod` | 所有非选项操作数 | v1.2.0 新增 |
| `mkfs` | 所有非选项操作数 | v1.2.0 新增，通用入口 |
| `sgdisk` `parted` `fdisk` | 所有非选项操作数 | v1.2.0 新增，分区表改动 |
| `flash_image` | 所有非选项操作数 | v1.2.0 新增，刷写分区镜像 |
| `wipefs` `chattr` | 所有非选项操作数 | v1.3.0 新增，与 `toybox` 派发表对齐 |
| `toybox` `busybox` | 子命令命中上表任一即 shift 后按上表判定 | 否则原样透传。**派发表与上表同集合**：漏一项即该命令在 `toybox <cmd>` 形态下完全不受守卫（v1.3.0 修复前 `toybox chmod -R 777 /system/…` 实测未拦截且无审计） |

高频命令（`cp` / `find` / `sed` / `mv`）在不需要介入时只做最小判定即 `exec`
真实二进制。

## 能力边界

本模块是 PATH 前置层，不是安全沙箱。以下情况拦不住：

1. 绝对路径调用：`/system/bin/rm -rf /system`、
   `/apex/com.android.runtime/bin/rm` 绕过 PATH。
2. `command -p` 或显式指定搜索路径：使用系统默认 PATH。
3. 自行改写 `PATH`：脚本内 `export PATH=/system/bin:$PATH` 即可摘掉守卫。
4. shell 重定向直接写设备或系统文件：`: > /system/build.prop`、
   `echo x > /dev/block/by-name/boot` 不 fork 任何被守卫命令。
5. 同进程内完成破坏的实现：`python -c 'shutil.rmtree("/system")'`、
   静态链接 busybox 的程序。
6. 有 root 即可直接改写 `policy.conf` 与 `audit.log`（设置 `mode=off` 或删日志）。
   注意：**不以 root 运行的被守卫命令无法再自行降级守卫**（v1.3.0 起环境变量覆盖
   已被策略白名单收口，见「环境变量」节）。
7. 包装器清单以外的破坏原语（例如 `mount`、`setprop`、`bootctl`）不在运行时覆盖范围；
   静态层（L1）对部分命令有等价规则，但不构成运行时兜底。

定位是拦误操作 + 高危留痕 + 提高恶意脚本门槛；主防线仍是 L1 静态审查与
档位 3 的「脚本默认不以 Root 执行」。

## 兼容性

| Root 方案 | 支持 | 说明 |
|---|---|---|
| Magisk | 是 | — |
| KernelSU | 是 | 模块不使用 `system/`，无需 metamodule |
| APatch | 是 | — |

模块为纯脚本模块：仅 `module.prop` + `customize.sh` + `service.sh` +
`uninstall.sh` + `guard/` + `policy.conf`，零 `system/` 依赖，三种方案行为一致。

## 目录结构

```
shso_guard/
├── module.prop              # 模块元信息（id=shso_guard，永不改）
├── policy.conf              # 随模块自带的默认策略（LF 行尾）
├── service.sh               # 开机兜底：把 policy.conf 部署到 /data/adb/shso_guard/
├── customize.sh             # 刷 zip 安装时执行（复制安装不执行）
├── uninstall.sh             # 卸载：删除 /data/adb/shso_guard/（审计日志保留）
├── guard-template.sh        # 守卫模板（__CMD_NAME__ / __OPERAND_MODE__ 两处占位符）
├── gen_wrappers.py          # 从模板生成 30 个守卫，另有 toybox / busybox 两个手写
│                            # 派发器，合计 32 个包装器
└── guard/
    ├── common.sh            # 共用引擎：策略加载 / 路径归一化 / 判定 / 审计 / exec_real
    ├── rm rmdir shred truncate wipe dd fastboot          # 模板生成（删除 / 覆写）
    ├── mkfs.ext4 mkfs.f2fs mkfs.vfat mke2fs make_f2fs   # 模板生成（格式化）
    ├── mv cp find sed                                    # 模板生成（移动 / 拷贝 / 原地修改）
    ├── chmod chown chgrp mkfs mknod                      # 模板生成（v1.2.0：权限）
    ├── sgdisk parted fdisk flash_image                   # 模板生成（v1.2.0：分区表 / 刷机）
    ├── ln install tee wipefs chattr                      # 模板生成（v1.3.0：写入原语 / 派发对齐）
    └── toybox busybox       # 多二进制派发（结构特殊，不由模板生成）
```

新增包装器需三处同步：`gen_wrappers.py` 的 specs、`guard_operand_mode()` 的派发表、
`app/src/main/assets/shso_guard.zip`（重打包）、
`GuardModuleInstaller.REQUIRED_ARCHIVE_ENTRIES`，并升 `module.prop` 的 `version=`。
其中「源码目录 / zip / REQUIRED 三者一致」由单测
`required entries stay in sync with sources and bundled zip` 强制校验，漏登记即失败。

改动公共行为后必须重新生成守卫：

```bash
python gen_wrappers.py     # 生成的产物应与 guard/ 下对应文件逐字节一致
```
