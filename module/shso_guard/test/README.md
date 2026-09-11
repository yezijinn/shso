# shso_guard 测试

守卫模块的外部黑盒测试，不依赖 Android 工具链，可在任意 POSIX shell 上运行；
与 `app/src/test/` 的 JVM 单测互补。

| 脚本 | 用途 |
|---|---|
| `harness.sh` | 对抗性冒烟：策略解析容错（CRLF / 空格 / 行内注释 / 未知 mode）、`toybox` 与 `busybox` 子命令派发、`mv` / `cp` / `find` / `sed` 覆盖、`fastboot -w`、`dd of =x`、`wipe` 无参、误杀检查、递归深度与缺失二进制、审计留痕 |
| `device-symlink.sh` | 真机用例：在 `/data/local/tmp` 构造伪真二进制与 `link -> /system`，验证符号链接绕过已被阻断（叶子不存在时 `realpath` 失败不再退回纯词法），以及 `sed` 脚本参数不再误判为路径。需在 root 上下文运行 |
| `count.sh` | 进程创建计数：为守卫可能调用的外部命令（`tr` / `realpath` / `date` / `dirname` / `wc` / `tail` 等）建记账桩，统计单次 `cp` / `find` / `sed` / `rm` / `ls` 创建的进程数，用于验证「每次守卫调用只起 0–1 个进程」 |

## 运行

Git Bash：

```bash
cd shso-main/module/shso_guard
ROOT="$(pwd -W)" bash test/harness.sh   # pwd -W 把 Windows 路径转为 POSIX
```

成功时输出末尾为 `全部通过`；失败输出 `存在失败` 并以非 0 退出。
