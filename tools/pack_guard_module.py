#!/usr/bin/env python3
# Copyright 2026, shso contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""把 module/shso_guard/ 确定性地打包成 app/src/main/assets/shso_guard.zip。

为什么需要这个脚本（原先是手工重打包，属可复现性隐患）：
`SecurityCoreTest.required entries stay in sync with sources and bundled zip`
会逐条比对「源码目录 / zip / REQUIRED_ARCHIVE_ENTRIES」三者内容，
zip 的字节与源码不一致时测试直接失败。手工重打包很容易漏掉某个文件、
或把 CRLF 写进 zip（守卫脚本带 CRLF 会被 mksh 拒绝执行）。

打包契约（与历史产物保持一致，变了会导致已装设备与新包行为不一致）：
- 全部条目 deflate 压缩，时间戳固定为 1980-01-01（保证同内容同产物，可复现）
- 可执行文件（*.sh / guard/* / gen_wrappers.py）置 0755，其余 0644
- 条目顺序按 POSIX 路径字典序，保证产物稳定
- 排除 .tmp-guard-test/、.gitattributes 之外的点文件、__pycache__

用法：
    python tools/pack_guard_module.py            # 打包并校验
    python tools/pack_guard_module.py --check    # 只校验 zip 与源码是否一致
"""

import argparse
import os
import stat
import sys
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(_HERE)
SRC = os.path.join(REPO, "module", "shso_guard")
OUT = os.path.join(REPO, "app", "src", "main", "assets", "shso_guard.zip")

# 固定时间戳：1980-01-01 00:00:00（zip 格式的起点），保证同内容产出同字节
FIXED_DATE = (1980, 1, 1, 0, 0, 0)

# 不进包：本地测试残留与开发期辅助文件
EXCLUDE_DIRS = {".tmp-guard-test", "__pycache__", ".git"}
# .gitattributes 保留在包内：它声明「强制 LF」，属模块内容的一部分，
# 刷 zip 的场景下与源码目录保持一致更好排查。
EXCLUDE_FILES: set[str] = set()


def iter_entries():
    """产出 (绝对路径, zip 内相对路径)，按相对路径字典序保证产物稳定。"""
    collected = []
    for root, dirs, files in os.walk(SRC):
        dirs[:] = sorted(d for d in dirs if d not in EXCLUDE_DIRS)
        for name in sorted(files):
            if name in EXCLUDE_FILES or name.endswith(".orig") or name.endswith(".rej"):
                continue
            abs_path = os.path.join(root, name)
            rel = os.path.relpath(abs_path, SRC).replace(os.sep, "/")
            collected.append((abs_path, rel))
    collected.sort(key=lambda item: item[1])
    return collected


def mode_for(rel):
    """守卫脚本与打包工具需可执行；配置与文档不需要。"""
    base = os.path.basename(rel)
    executable = rel.endswith(".sh") or rel.startswith("guard/") or base == "gen_wrappers.py"
    return 0o100755 if executable else 0o100644


def build():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for abs_path, rel in iter_entries():
            # 以二进制读入后原样写入：杜绝任何换行转换（守卫脚本必须是 LF）
            with open(abs_path, "rb") as f:
                data = f.read()
            info = zipfile.ZipInfo(rel, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = mode_for(rel) << 16
            zf.writestr(info, data)
    return OUT


def check():
    """校验 zip 与源码逐字节一致；返回不一致条目列表。"""
    if not os.path.isfile(OUT):
        return ["assets/shso_guard.zip 缺失"]
    mismatched = []
    with zipfile.ZipFile(OUT) as zf:
        names = {n for n in zf.namelist() if not n.endswith("/")}
        on_disk = {rel for _, rel in iter_entries()}
        for extra in sorted(names - on_disk):
            mismatched.append(f"zip 多出条目: {extra}")
        for missing in sorted(on_disk - names):
            mismatched.append(f"zip 缺少条目: {missing}")
        for _, rel in iter_entries():
            if rel not in names:
                continue
            with open(os.path.join(SRC, rel.replace("/", os.sep)), "rb") as f:
                disk = f.read()
            if disk != zf.read(rel):
                mismatched.append(f"内容不一致: {rel}")
    return mismatched


def report_crlf():
    """守卫脚本带 CRLF 会被 Android mksh 直接拒绝执行，构建期就拦下。"""
    bad = []
    for abs_path, rel in iter_entries():
        if not (rel.endswith(".sh") or rel.startswith("guard/")):
            continue
        with open(abs_path, "rb") as f:
            if b"\r\n" in f.read():
                bad.append(rel)
    return bad


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验，不重新打包")
    args = parser.parse_args()

    crlf = report_crlf()
    if crlf:
        print("以下守卫脚本含 CRLF，mksh 会拒绝执行：")
        for name in crlf:
            print(f"  - {name}")
        return 1

    if not args.check:
        out = build()
        size = os.path.getsize(out)
        print(f"已打包 {os.path.relpath(out, REPO)}（{size} bytes）")

    mismatched = check()
    if mismatched:
        print("zip 与源码不一致：")
        for line in mismatched:
            print(f"  - {line}")
        return 1
    print("校验通过：assets/shso_guard.zip 与 module/shso_guard/ 逐字节一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
