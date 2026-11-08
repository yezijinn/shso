#!/usr/bin/env python3
# Copyright 2026, shso contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""合并内置环境资产 env.bin。

把既有内置环境（bash / busybox / curl / zip 及其依赖库）与完整工具链归档
（coreutils / git / python / openssl / ssh / rsync / GNU 工具）合并成单个
tar.gz 资产，供 EmbeddedEnv 首次启动解压。

规则：
* 同路径内容不一致 → 立即中止（返回码 2），不做静默覆盖；
  符号链接按「父目录 + linkName」解析后比较真实内容。
* 符号链接只允许指向归档内路径；越界（绝对路径或解析到根外）→ 中止。
* 文件模式归一：有执行位 → 0755，否则 0644；目录 0755；软链 0777。
* VERSION 由本脚本写入，取 --version（当前为 4）。
* 输出可复现：mtime / uid / gid / uname / gname 固定，gzip 不写时间戳。

用法：
    python tools/build_env_bin.py --base app/src/main/assets/env.bin \
        --overlay path/to/toolchain.tar.gz --out app/src/main/assets/env.bin
"""

import argparse
import gzip
import hashlib
import io
import os
import posixpath
import sys
import tarfile
import tempfile

DIR_MODE = 0o755
FILE_MODE = 0o644
EXEC_MODE = 0o755
LINK_MODE = 0o777

REQUIRED = (
    "bin/bash",
    "bin/busybox",
    "bin/python3",
    "bin/git",
    "bin/openssl",
    "bin/ssh",
    "bin/rsync",
    "bin/sed",
    "bin/tar",
    "bin/wget",
    "bin/grep",
    "bin/find",
)

# 运行期用不到的随包内容：体积优先，且删掉不影响任何已声明的能力。
#
# * share/misc/magic.mgc   `file` 命令的魔数库（10.3 MB）。归档不含 bin/file，
#                          应用的类型判定走 toybox `file` 与自研魔数扫描，用不到该库。
# * bin/scalar             git 的附加组件（2.0 MB），日常 git 操作不经过它。
# * lib/python3.14/config-* 编译 C 扩展用的构建配置，头文件本就未随包分发。
# * share/aclocal、*/pkgconfig  autotools 开发期元数据，运行期不读取。
EXCLUDE_NAMES = frozenset({
    "share/misc/magic.mgc",
    "bin/scalar",
    # git 服务端 / dumb-http 辅助程序：本应用只做客户端，且每个约 2 MB。
    # 保留 git-remote-http（http/https 传输实际走它）。
    "libexec/git-core/git-daemon",
    "libexec/git-core/git-http-backend",
    "libexec/git-core/git-http-fetch",
    "libexec/git-core/git-http-push",
    "libexec/git-core/git-imap-send",
    # 仅用于本地化消息替换；环境固定 LANG=en_US.UTF-8，缺它不影响输出。
    "libexec/git-core/git-sh-i18n--envsubst",
    # 无内置 pip wheel，ensurepip 本就无法引导；pydoc_data 仅 `pydoc` 使用。
    "lib/python3.14/ensurepip/__init__.py",
    "lib/python3.14/ensurepip/__main__.py",
    "lib/python3.14/ensurepip/_uninstall.py",
})
EXCLUDE_PREFIXES = (
    "lib/python3.14/config-",
    "lib/python3.14/pydoc_data/",
    "lib/python3.14/ensurepip/",
    "share/aclocal/",
    "lib/pkgconfig/",
    "share/pkgconfig/",
)


def excluded(name):
    """是否为刻意剔除的运行期无用内容。"""
    if name in EXCLUDE_NAMES:
        return True
    return any(name.startswith(prefix) for prefix in EXCLUDE_PREFIXES)


class BuildError(Exception):
    pass


def normalize(name):
    """归一条目名；非法（绝对路径 / 含 .. 段 / 为空）返回 None。"""
    name = name.replace("\\", "/")
    while name.startswith("./"):
        name = name[2:]
    name = name.rstrip("/")
    if not name or name.startswith("/"):
        return None
    if any(seg in ("", ".", "..") for seg in name.split("/")):
        return None
    return name


def resolve_link(name, linkname, members):
    """按「父目录 + linkName」在归档内解析，返回规范化路径；越界返回 None。"""
    if linkname.startswith("/"):
        return None
    target = posixpath.normpath(posixpath.join(posixpath.dirname(name), linkname))
    if target == ".." or target.startswith("../"):
        return None
    return target


def signature(tf, entry, members):
    """内容签名：普通文件取 SHA-256；软链解析到普通文件后取 SHA-256。"""
    seen = 0
    cur = entry
    while cur.issym():
        target = resolve_link(cur.name, cur.linkname, members)
        if target is None:
            raise BuildError("软链越界或无法解析：%s -> %s" % (cur.name, cur.linkname))
        cur = members.get(target)
        if cur is None:
            raise BuildError("软链目标缺失：%s -> %s" % (entry.name, entry.linkname))
        seen += 1
        if seen > 20:
            raise BuildError("软链层数过多：%s" % entry.name)
    if cur.isdir():
        # 指向目录的软链（如 lib/terminfo -> ../share/terminfo）：按目标路径比对
        return "dir:" + cur.name
    if not cur.isfile():
        raise BuildError("不支持的条目类型：%s" % cur.name)
    digest = hashlib.sha256()
    f = tf.extractfile(cur)
    if f is None:
        raise BuildError("无法读取：%s" % cur.name)
    for chunk in iter(lambda: f.read(1 << 20), b""):
        digest.update(chunk)
    return digest.hexdigest()


def load(path):
    """读取归档为 name -> 条目；并校验软链不越界。"""
    tf = tarfile.open(path, "r:gz")
    members = {}
    for m in tf.getmembers():
        name = normalize(m.name)
        if name is None:
            continue
        if m.issym():
            target = resolve_link(name, m.linkname, members)
            if target is None:
                raise BuildError("软链越界：%s -> %s（%s）" % (name, m.linkname, path))
        if m.isdir() or m.isfile() or m.issym():
            if excluded(name):
                continue
            members[name] = m
    # 指向被剔除条目的软链一并丢弃，否则产物里会留下悬空链
    for name in list(members):
        entry = members[name]
        if not entry.issym():
            continue
        target = resolve_link(name, entry.linkname, members)
        if target is not None and excluded(target):
            del members[name]
    return tf, members


def merge(base_path, overlay_path, version):
    base_tf, base = load(base_path)
    if overlay_path is None:
        # 仅裁剪模式：不引入新内容，只按 EXCLUDE 重建产物（VERSION 由 build 统一写入）
        pruned = {n: e for n, e in base.items() if n != "VERSION"}
        return base_tf, base, None, {}, pruned, {n: (base_tf, base) for n in pruned}
    pack_tf, pack = load(overlay_path)

    merged = {}
    sources = {}
    for name, entry in base.items():
        if name == "VERSION":
            continue
        merged[name] = entry
        sources[name] = (base_tf, base)

    conflicts = []
    for name, entry in pack.items():
        if name == "VERSION":
            continue
        if name in merged:
            old = merged[name]
            if old.isdir() and entry.isdir():
                # 目录本身无内容，元数据差异（模式）由产物归一
                merged[name] = entry
                sources[name] = (pack_tf, pack)
                continue
            if old.isdir() != entry.isdir():
                conflicts.append((name, "dir" if old.isdir() else "file", "dir" if entry.isdir() else "file"))
                continue
            old_tf, old_members = sources[name]
            old_sig = signature(old_tf, old, old_members)
            new_sig = signature(pack_tf, entry, pack)
            if old_sig != new_sig:
                conflicts.append((name, old_sig, new_sig))
                continue
        merged[name] = entry
        sources[name] = (pack_tf, pack)

    if conflicts:
        detail = "\n".join("- %s\n  base=%s\n  overlay=%s" % c for c in conflicts)
        raise BuildError("同路径内容不一致，已中止：\n" + detail)

    return base_tf, base, pack_tf, pack, merged, sources


def canonical_mode(name, entry):
    if entry.issym():
        return LINK_MODE
    if entry.isdir():
        return DIR_MODE
    # bin/ 与 libexec/ 是命令入口，必须可执行；其余按源执行位判定
    if name.startswith("bin/") or name.startswith("libexec/"):
        return EXEC_MODE
    return EXEC_MODE if entry.mode & 0o111 else FILE_MODE


def build(merged, sources, version, out_path):
    # 补齐缺失的父目录条目
    dirs = set()
    for name in merged:
        parent = posixpath.dirname(name)
        while parent:
            dirs.add(parent)
            parent = posixpath.dirname(parent)

    entries = sorted(set(merged) | dirs)
    total_bytes = 0
    counts = {"file": 0, "dir": 0, "link": 0}

    fd, tmp_path = tempfile.mkstemp(prefix="env-bin-", suffix=".tmp", dir=os.path.dirname(os.path.abspath(out_path)))
    os.close(fd)
    try:
        with open(tmp_path, "wb") as raw:
            with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz:
                with tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as out:
                    def add(info):
                        info.mtime = 0
                        info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        out.addfile(info)

                    # VERSION
                    payload = (version + "\n").encode()
                    info = tarfile.TarInfo("VERSION")
                    info.size = len(payload)
                    info.mode = FILE_MODE
                    info.mtime = 0
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    out.addfile(info, io.BytesIO(payload))
                    counts["file"] += 1
                    total_bytes += len(payload)

                    for name in entries:
                        if name in dirs and name not in merged:
                            info = tarfile.TarInfo(name)
                            info.type = tarfile.DIRTYPE
                            info.mode = DIR_MODE
                            add(info)
                            counts["dir"] += 1
                            continue
                        entry = merged[name]
                        if name in dirs and entry.isdir():
                            info = tarfile.TarInfo(name)
                            info.type = tarfile.DIRTYPE
                            info.mode = DIR_MODE
                            add(info)
                            counts["dir"] += 1
                            continue
                        if entry.issym():
                            info = tarfile.TarInfo(name)
                            info.type = tarfile.SYMTYPE
                            info.linkname = entry.linkname
                            info.mode = LINK_MODE
                            add(info)
                            counts["link"] += 1
                            continue
                        if entry.isdir():
                            info = tarfile.TarInfo(name)
                            info.type = tarfile.DIRTYPE
                            info.mode = DIR_MODE
                            add(info)
                            counts["dir"] += 1
                            continue
                        tf, members = sources[name]
                        src = tf.extractfile(entry)
                        if src is None:
                            raise BuildError("无法读取条目：%s" % name)
                        data = src.read()
                        info = tarfile.TarInfo(name)
                        info.size = len(data)
                        info.mode = canonical_mode(name, entry)
                        info.mtime = 0
                        info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        out.addfile(info, io.BytesIO(data))
                        counts["file"] += 1
                        total_bytes += len(data)
    except Exception:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        raise
    return counts, total_bytes, tmp_path


def verify(path, version):
    """重新打开产物做结构与必需文件校验。"""
    members = {}
    with tarfile.open(path, "r:gz") as tf:
        for m in tf.getmembers():
            name = normalize(m.name)
            if name is None:
                raise BuildError("非法条目名：%r" % m.name)
            members[name] = m
        for name, m in members.items():
            if m.issym():
                target = resolve_link(name, m.linkname, members)
                if target is None:
                    raise BuildError("产物软链越界：%s -> %s" % (name, m.linkname))
                cur = members.get(target)
                hops = 0
                while cur is not None and cur.issym():
                    nxt = resolve_link(cur.name, cur.linkname, members)
                    cur = members.get(nxt) if nxt else None
                    hops += 1
                    if hops > 20:
                        raise BuildError("产物软链层数过多：%s" % name)
                if cur is None:
                    raise BuildError("产物软链目标缺失：%s -> %s" % (name, m.linkname))
        for name in members:
            if excluded(name):
                raise BuildError("产物仍含应剔除条目：%s" % name)
        for req in REQUIRED:
            if req not in members:
                raise BuildError("产物缺少必需文件：%s" % req)
            cur = members[req]
            hops = 0
            while cur.issym():
                nxt = resolve_link(cur.name, cur.linkname, members)
                cur = members.get(nxt) if nxt else None
                hops += 1
                if cur is None or hops > 20 or not cur.isfile():
                    raise BuildError("必需文件软链无效：%s" % req)
        version_member = members.get("VERSION")
        if version_member is None:
            raise BuildError("产物缺少 VERSION")
        got = tf.extractfile(version_member).read().decode().strip()
        if got != version:
            raise BuildError("VERSION 不符：期望 %s，实际 %s" % (version, got))
    return len(members)


def replace_with_retry(src, dst, tries=10):
    """Windows 上杀软/句柄可能短暂占用目标：带退避重试。"""
    import time

    for i in range(tries):
        try:
            os.replace(os.path.abspath(src), os.path.abspath(dst))
            return
        except PermissionError:
            if i == tries - 1:
                raise
            time.sleep(0.3 * (i + 1))


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description="合并生成内置环境资产 env.bin")
    parser.add_argument("--base", default="app/src/main/assets/env.bin")
    parser.add_argument("--overlay", help="要并入的工具链归档；省略则仅按剔除清单重建（裁剪模式）")
    parser.add_argument("--out", default="app/src/main/assets/env.bin")
    parser.add_argument("--version", default="6")
    args = parser.parse_args(argv)

    if not os.path.isfile(args.base):
        raise BuildError("基础资产不存在：%s" % args.base)
    if args.overlay is not None and not os.path.isfile(args.overlay):
        raise BuildError("工具链归档不存在：%s" % args.overlay)
    if not os.path.isfile(args.out):
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    base_tf, base, pack_tf, pack, merged, sources = merge(args.base, args.overlay, args.version)
    try:
        counts, total, tmp_path = build(merged, sources, args.version, args.out)
    finally:
        base_tf.close()
        if pack_tf is not None:
            pack_tf.close()
    # 归档句柄释放后再替换目标（Windows 上目标被打开时无法 rename）
    replace_with_retry(tmp_path, args.out)

    verified = verify(args.out, args.version)
    size = os.path.getsize(args.out)
    print("base entries     : %d" % len(base))
    print("overlay entries  : %d" % len(pack))
    print("merged entries   : %d" % len(merged))
    print("removed          : %d (剔除清单：magic.mgc / scalar / python config / pkgconfig)"
          % (len(base) + len(pack) - len(merged)))
    print("written entries  : %d (file=%d dir=%d link=%d)" % (verified, counts["file"], counts["dir"], counts["link"]))
    print("uncompressed     : %d bytes (%.2f MiB)" % (total, total / 1048576.0))
    print("output           : %s" % args.out)
    print("output size      : %d bytes (%.2f MiB)" % (size, size / 1048576.0))
    print("sha256           : %s" % sha256(args.out))
    print("VERSION          : %s" % args.version)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BuildError as e:
        print("错误：%s" % e, file=sys.stderr)
        sys.exit(2)
