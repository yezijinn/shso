# shso_guard 对抗性测试

本目录是守卫模块的**外部黑盒测试**（不依赖 Android 工具链，可在任意 POSIX shell 上跑），与 `app/src/test/` 的 JVM 单测互为补充。

| 脚本 | 作用 |
|---|---|
| `harness.sh` | **37 项对抗性冒烟**：策略解析容错（CRLF/空格/行内注释/未知 mode）/ toybox·busybox 子命令派发 / 新覆盖面 mv·cp·find·sed / fastboot `-w` / dd `of =x` / wipe 无参 / 误杀检查 / 递归深度与缺失二进制 / 审计留痕。在 Git Bash 上用 `ROOT=<module dir> bash harness.sh` 跑。 |
| `device-symlink.sh` | 真机专用：在 `/data/local/tmp` 搭伪造真二进制与 `link -> /system`，验证**符号链接绕过**已被阻断（叶子不存在时 `realpath` 失败不再退回纯词法）+ `sed` 脚本参数不再误判为路径。需要在真机 root 上下文跑。 |
| `count.sh` | **进程创建计数器**：给守卫可能调用的每个外部命令（`tr`/`realpath`/`date`/`dirname`/`wc`/`tail` 等）建一个记账桩，统计单次 `cp` / `find` / `sed` / `rm` / `ls` 调用的外部进程数。这是「每次守卫调用必须只起 0–1 个进程」不变式的验证手段。 |

## 在 Git Bash 跑 harness.sh

```bash
cd shso-main/module/shso_guard
ROOT="$(pwd -W)" bash test/harness.sh   # 把 Windows 路径转 POSIX（Git Bash 必须）
```

期望输出末尾出现 `全部通过`（失败则输出 `存在失败` 并以非 0 退出）。
