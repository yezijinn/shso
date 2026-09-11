import os, re

# 路径相对本脚本（位于 shso_guard/ 仓库根），与本机绝对路径解耦。
_HERE = os.path.dirname(os.path.abspath(__file__))
GUARD = os.path.join(_HERE, "guard")
tpl_path = os.path.join(_HERE, "guard-template.sh")
with open(tpl_path, "r", encoding="utf-8") as f:
    tpl = f.read()

# (filename, CMD_NAME, OPERAND_MODE)
specs = [
    ("rm", "rm", "ARGS"),
    ("rmdir", "rmdir", "ARGS"),
    ("shred", "shred", "ARGS"),
    ("truncate", "truncate", "ARGS"),
    ("wipe", "wipe", "ARGS"),
    ("dd", "dd", "DD"),
    ("fastboot", "fastboot", "FASTBOOT"),
    ("mkfs.ext4", "mkfs.ext4", "ARGS"),
    ("mkfs.f2fs", "mkfs.f2fs", "ARGS"),
    ("mkfs.vfat", "mkfs.vfat", "ARGS"),
    ("mke2fs", "mke2fs", "ARGS"),
    ("make_f2fs", "make_f2fs", "ARGS"),
    ("mv", "mv", "MVCP"),
    ("cp", "cp", "MVCP"),
    ("find", "find", "FIND"),
    ("sed", "sed", "SED"),
    # v1.2.0：补齐「权限崩坏 / 分区表 / 刷机」这一类格机原语。
    # 均为 ARGS 模式（非选项参数即路径），命中 protect=/system、/dev、/data 即拦截。
    ("chmod", "chmod", "ARGS"),
    ("chown", "chown", "ARGS"),
    ("chgrp", "chgrp", "ARGS"),
    ("mkfs", "mkfs", "ARGS"),
    ("mknod", "mknod", "ARGS"),
    ("sgdisk", "sgdisk", "ARGS"),
    ("parted", "parted", "ARGS"),
    ("fdisk", "fdisk", "ARGS"),
    ("flash_image", "flash_image", "ARGS"),
]

others = re.findall(r"__[A-Z_]+__", tpl)
assert set(others) <= {"__CMD_NAME__", "__OPERAND_MODE__"}, others

for fname, cmd, mode in specs:
    out = tpl.replace("__CMD_NAME__", cmd).replace("__OPERAND_MODE__", mode)
    with open(os.path.join(GUARD, fname), "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("wrote", fname)
print("done")
