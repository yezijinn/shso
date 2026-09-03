#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
shso 一键编译脚本
====================

功能：自动完成环境预检 → 依赖校正 → Gradle 构建 → 产物签名校验 → 结果汇总，
      一步产出可直接安装的 Release APK。

用法：
    python build_apk.py                 # 编译 Release 包（默认）
    python build_apk.py --variant Debug # 编译 Debug 包
    python build_apk.py --clean         # 先 clean 再编译
    python build_apk.py --skip-check    # 跳过环境预检直接构建

依赖（本机环境，见 C:\\ENVIRONMENT.md）：
    - JDK 17      : C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20+8   （运行 Gradle）
    - Android SDK : C:\\Android\\sdk                                     （platforms;android-37.0 / build-tools;37.0.0）
    - 原生控件    : 100% 采用 AndroidX Compose Material 3 原生控件（自包含工程，无仓库外 UI 组件库）
    - 签名密钥    : E:\\JinnKeyStores\\Kernel.Extend\\release.jks          （alias: kernel.extend）

作者：Jinn / 小梦
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

# --------------------------------------------------------------------------- #
# 控制台编码：Windows 下强制 UTF-8，保证中文与 ANSI 输出不乱码
# --------------------------------------------------------------------------- #
os.environ.setdefault("PYTHONIOENCODING", "utf-8")
os.environ.setdefault("PYTHONUTF8", "1")
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass


# --------------------------------------------------------------------------- #
# 常量配置
# --------------------------------------------------------------------------- #
PROJECT_DIR: Path = Path(__file__).resolve().parent
PROJECT_NAME: str = "shso"

JAVA_HOME = Path(r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20+8")
ANDROID_SDK = Path(r"C:\Android\sdk")

# 签名配置（与 app/build.gradle.kts 保持一致）
SIGNING = {
    "keystore": Path(r"E:\JinnKeyStores\Kernel.Extend\release.jks"),
    "alias": "kernel.extend",
    "storepass": "WE1A1xus0n9.",
    "keypass": "WE1A1xus0n9.",
}

BUILD_TOOLS_VERSION = "37.0.0"
GRADLEW_BAT = PROJECT_DIR / "gradlew.bat"
LOG_DIR = PROJECT_DIR / ".tmp" / "build_logs"

# Gradle wrapper 客户端 JVM 参数（GRADLE_OPTS 仅作用于 wrapper 客户端进程，不影响 daemon；
# daemon 的内存参数由 gradle-daemon-jvm.properties / org.gradle.jvmargs 决定）。
# 关键：本机中间网络对 TLS1.3 执行 RST 重置，仅支持 TLS1.2，且 IPv6 不可达，
#       故强制 JVM 使用 TLS1.2 + IPv4，否则 Gradle 无法从 Maven / 插件门户 / foojay 下载。
GRADLE_JVM_ARGS = (
    "-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 "
    "-Dhttps.protocols=TLSv1.2 "
    "-Djdk.tls.client.protocols=TLSv1.2 "
    "-Djava.net.preferIPv4Stack=true"
)

OK = "[ OK ]"
FAIL = "[FAIL]"
WARN = "[WARN]"
INFO = "[INFO]"


# --------------------------------------------------------------------------- #
# 工具函数
# --------------------------------------------------------------------------- #
def log(tag: str, message: str) -> None:
    """统一日志输出。"""
    print(f"{tag} {message}", flush=True)


def section(title: str) -> None:
    """打印分节标题。"""
    print()
    print("=" * 68)
    print(f"  {title}")
    print("=" * 68, flush=True)


def run_cmd(
    cmd: Sequence[str],
    cwd: Optional[Path] = None,
    env: Optional[dict] = None,
    timeout: Optional[int] = None,
) -> subprocess.CompletedProcess:
    """执行命令并实时回显输出（UTF-8 解码，替换非法字节）。"""
    return subprocess.run(
        list(cmd),
        cwd=str(cwd) if cwd else None,
        env=env or os.environ.copy(),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        shell=False,
    )


def run_stream(
    cmd: Sequence[str],
    cwd: Optional[Path] = None,
    env: Optional[dict] = None,
    log_file: Optional[Path] = None,
    timeout: Optional[int] = None,
) -> int:
    """流式执行命令：实时打印到控制台，同时写入日志文件，返回退出码。

    timeout 为 None 时不限时；超时会终止整个进程树并返回非 0 退出码。
    """
    handle = None
    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handle = log_file.open("a", encoding="utf-8", errors="replace")

    proc = None
    reader: Optional[threading.Thread] = None
    try:
        proc = subprocess.Popen(
            list(cmd),
            cwd=str(cwd) if cwd else None,
            env=env or os.environ.copy(),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            shell=False,
        )
        assert proc.stdout is not None

        def _pump() -> None:
            # 后台线程持续回显并写日志；超时 kill 后读到 EOF 自动退出
            for line in proc.stdout:
                line = line.rstrip("\r\n")
                if not line:
                    continue
                print(line, flush=True)
                if handle:
                    # 日志句柄可能在读线程仍在写入时被主线程关闭，吞掉关闭后写入的异常
                    try:
                        handle.write(line + "\n")
                        handle.flush()
                    except (ValueError, OSError):
                        pass

        reader = threading.Thread(target=_pump, daemon=True)
        reader.start()
        try:
            return proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # 超时需终止整个进程树：gradlew.bat 只是包装进程，java daemon 可能仍存活。
            # Windows 优先 taskkill /F /T 连子进程一起杀；taskkill 不可用或失败时
            # 回退到对包装进程本身的 terminate / kill
            killed = False
            if os.name == "nt":
                result = subprocess.run(
                    ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                    capture_output=True,
                )
                killed = result.returncode == 0
            if not killed:
                proc.terminate()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
            proc.wait()
            log(FAIL, f"命令超时（>{timeout}s），已终止：{' '.join(cmd[:3])} ...")
            return 124  # 约定俗成的超时退出码（参考 timeout 命令惯例）
    finally:
        # 先等读线程退出（上限 5s），再关日志句柄，避免关闭后写入的竞态
        if reader is not None:
            reader.join(timeout=5)
        if handle:
            handle.close()


def human_size(num_bytes: int) -> str:
    """字节数转人类可读单位。"""
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024.0 or unit == "GB":
            return f"{size:.2f} {unit}" if unit != "B" else f"{int(size)} B"
        size /= 1024.0
    return f"{size:.2f} GB"


# --------------------------------------------------------------------------- #
# 阶段 1：环境预检
# --------------------------------------------------------------------------- #
def precheck() -> bool:
    """校验编译所需的所有外部依赖，返回是否全部通过。"""
    section("阶段 1 / 5：环境预检")
    passed = True

    # 1. JDK 17（用于运行 Gradle）
    java_exe = JAVA_HOME / "bin" / "java.exe"
    if java_exe.is_file():
        result = run_cmd([str(java_exe), "-version"], timeout=60)
        version_line = (result.stdout or "").splitlines()[0] if result.stdout else "unknown"
        log(OK, f"JDK 17 就绪：{version_line}")
    else:
        log(FAIL, f"JDK 17 缺失：{java_exe}")
        passed = False

    # 2. Android SDK 根目录
    if ANDROID_SDK.is_dir():
        log(OK, f"Android SDK 就绪：{ANDROID_SDK}")
    else:
        log(FAIL, f"Android SDK 缺失：{ANDROID_SDK}")
        passed = False

    # 3. 编译平台（compileSdk = 37 → platforms;android-37.0）
    platform_dir = ANDROID_SDK / "platforms" / "android-37.0"
    if platform_dir.is_dir():
        log(OK, f"编译平台就绪：{platform_dir.name}")
    else:
        log(FAIL, f"编译平台缺失：{platform_dir}（执行 sdkmanager \"platforms;android-37.0\" 安装）")
        passed = False

    # 4. 构建工具（apksigner / zipalign）
    build_tools = ANDROID_SDK / "build-tools" / BUILD_TOOLS_VERSION
    apksigner = build_tools / "apksigner.bat"
    if apksigner.is_file():
        log(OK, f"构建工具就绪：build-tools;{BUILD_TOOLS_VERSION}")
    else:
        log(FAIL, f"构建工具缺失：{apksigner}")
        passed = False

    # 5. 原生 Material 3 校验（自包含工程，禁止回退到仓库外 UI 组件库）
    settings_file = PROJECT_DIR / "settings.gradle.kts"
    app_build = PROJECT_DIR / "app" / "build.gradle.kts"
    settings_txt = settings_file.read_text(encoding="utf-8") if settings_file.is_file() else ""
    app_txt = app_build.read_text(encoding="utf-8") if app_build.is_file() else ""
    if any(t in settings_txt.lower() for t in ("includebuild", "../", "..\\")):
        log(FAIL, "检测到 settings.gradle.kts 引用仓库外工程/模块，自包含约束被破坏")
        passed = False
    elif "material3" not in app_txt:
        log(FAIL, "app/build.gradle.kts 未声明 Material 3 依赖，原生控件迁移未完成")
        passed = False
    else:
        log(OK, "原生 Material 3 校验通过：自包含工程，无仓库外 UI 组件库")

    # 6. 签名密钥
    ks = SIGNING["keystore"]
    if ks.is_file():
        log(OK, f"签名密钥就绪：{ks.name}（alias={SIGNING['alias']}）")
    else:
        log(FAIL, f"签名密钥缺失：{ks}")
        passed = False

    # 7. Gradle Wrapper
    if GRADLEW_BAT.is_file():
        log(OK, f"Gradle Wrapper 就绪：{GRADLEW_BAT.name}")
    else:
        log(FAIL, f"Gradle Wrapper 缺失：{GRADLEW_BAT}")
        passed = False

    return passed


# --------------------------------------------------------------------------- #
# 阶段 2：本地配置校正
# --------------------------------------------------------------------------- #
def sync_local_properties() -> None:
    """校正 local.properties 中的 sdk.dir，指向本机真实 SDK 路径（保留文件其余行）。"""
    section("阶段 2 / 5：本地配置校正")
    lp = PROJECT_DIR / "local.properties"
    desired = "sdk.dir=" + str(ANDROID_SDK).replace("\\", "\\\\").replace(":", "\\:")

    lines = lp.read_text(encoding="utf-8").splitlines() if lp.is_file() else []
    sdk_pattern = re.compile(r"^\s*#?\s*sdk\.dir\s*=")
    replaced = False
    new_lines: List[str] = []
    for line in lines:
        if sdk_pattern.match(line):
            if not replaced:
                new_lines.append(desired)  # 原位替换第一处 sdk.dir
                replaced = True
            # 其余 sdk.dir 行（含注释）一并丢弃，避免多行冲突
            continue
        new_lines.append(line)

    if replaced:
        if desired in new_lines and lines == new_lines:
            log(OK, f"local.properties 已指向 {ANDROID_SDK}，其余内容保留")
            return
        lp.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
        log(OK, f"已原位替换 sdk.dir → {desired}（其余行保留）")
    else:
        # 文件末尾追加，保留原有内容
        content = "\n".join(new_lines)
        if content and not content.endswith("\n"):
            content += "\n"
        lp.write_text(content + desired + "\n", encoding="utf-8")
        log(OK, f"已追加 sdk.dir → {desired}（原有内容保留）")


# --------------------------------------------------------------------------- #
# 阶段 3：Gradle 构建
# --------------------------------------------------------------------------- #
def build_env() -> dict:
    """构造 Gradle 子进程环境变量。"""
    env = os.environ.copy()
    env["JAVA_HOME"] = str(JAVA_HOME)
    env["ANDROID_HOME"] = str(ANDROID_SDK)
    env["ANDROID_SDK_ROOT"] = str(ANDROID_SDK)
    # GRADLE_OPTS 仅作用于 Gradle wrapper 客户端 JVM；
    # daemon 的内存与系统属性由 gradle-daemon-jvm.properties / org.gradle.jvmargs 决定
    env["GRADLE_OPTS"] = GRADLE_JVM_ARGS
    # 保证 Gradle 与 Kotlin 编译器按 UTF-8 读取中文路径/资源
    env["LANG"] = "C.UTF-8"
    env["LC_ALL"] = "C.UTF-8"
    env["PATH"] = os.pathsep.join(
        [str(JAVA_HOME / "bin"), str(ANDROID_SDK / "platform-tools"), env.get("PATH", "")]
    )
    return env


def gradle_build(variant: str, clean: bool, extra_args: Sequence[str]) -> int:
    """执行 Gradle 构建任务，返回退出码（assemble 上限 1 小时）。"""
    section(f"阶段 3 / 5：Gradle 构建（assemble{variant}）")

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    log_file = LOG_DIR / f"gradle-{variant.lower()}-{stamp}.log"
    log(INFO, f"构建日志：{log_file}")

    env = build_env()

    if clean:
        log(INFO, "执行 clean ...")
        code = run_stream([str(GRADLEW_BAT), ":app:clean", "--console=plain"], cwd=PROJECT_DIR, env=env, log_file=log_file, timeout=600)
        if code != 0:
            log(FAIL, f"clean 失败（exit={code}）")
            return code
        log(OK, "clean 完成")

    cmd: List[str] = [
        str(GRADLEW_BAT),
        f":app:assemble{variant}",
        "--console=plain",
        "--stacktrace",
        "--no-configuration-cache",
        *extra_args,
    ]
    log(INFO, "执行命令：" + " ".join(cmd))
    started = time.time()
    code = run_stream(cmd, cwd=PROJECT_DIR, env=env, log_file=log_file, timeout=3600)
    elapsed = time.time() - started

    if code == 0:
        log(OK, f"Gradle 构建成功，耗时 {elapsed:.1f}s")
    else:
        log(FAIL, f"Gradle 构建失败（exit={code}，耗时 {elapsed:.1f}s），详见日志：{log_file}")
    return code


# --------------------------------------------------------------------------- #
# 阶段 4：产物定位与签名校验
# --------------------------------------------------------------------------- #
def locate_apk(variant: str) -> Optional[Path]:
    """定位构建产物 APK。"""
    section("阶段 4 / 5：产物定位与签名校验")
    apk_dir = PROJECT_DIR / "app" / "build" / "outputs" / "apk" / variant.lower()
    if not apk_dir.is_dir():
        log(FAIL, f"产物目录不存在：{apk_dir}")
        return None

    candidates = sorted(apk_dir.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    candidates = [p for p in candidates if "unsigned" not in p.name.lower()]
    candidates = [p for p in candidates if p.name.lower().startswith(f"app-{variant.lower()}")]
    if not candidates:
        log(FAIL, f"未找到 APK 产物：{apk_dir}")
        return None

    apk = candidates[0]
    log(OK, f"产物：{apk}")
    log(INFO, f"大小：{human_size(apk.stat().st_size)}")
    return apk


def verify_signature(apk: Path) -> Tuple[bool, Optional[dict]]:
    """使用 apksigner 校验 APK 签名方案（V1 / V2 / V3）。

    返回 (是否通过, 各方案启用状态字典)；apksigner 不可用时返回 (False, None) 表示未校验。
    """
    apksigner = ANDROID_SDK / "build-tools" / BUILD_TOOLS_VERSION / "apksigner.bat"
    if not apksigner.is_file():
        log(WARN, "apksigner 不可用，跳过签名校验")
        return False, None

    result = run_cmd(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        timeout=180,
    )
    output = result.stdout or ""

    schemes = {
        "V1": "Verified using v1 scheme (JAR signing): true" in output,
        "V2": "Verified using v2 scheme (APK Signature Scheme v2): true" in output,
        "V3": "Verified using v3 scheme (APK Signature Scheme v3): true" in output,
    }
    for name, ok in schemes.items():
        log(OK if ok else WARN, f"{name} 签名：{'已启用' if ok else '未启用'}")

    # 证书主体
    for line in output.splitlines():
        if "DN:" in line:
            log(INFO, f"证书：{line.strip()}")
            break

    if result.returncode != 0:
        log(FAIL, "apksigner 验证未通过")
        print(output)
        return False, schemes
    log(OK, "签名校验通过")
    return True, schemes


# --------------------------------------------------------------------------- #
# 阶段 5：结果汇总
# --------------------------------------------------------------------------- #
def summarize(apk: Optional[Path], variant: str, code: int, schemes: Optional[dict] = None) -> int:
    section("阶段 5 / 5：结果汇总")
    if code == 0 and apk is not None:
        # 动态拼接实际校验到的签名方案；schemes 为 None 表示未执行校验
        if schemes is None:
            scheme_text = "未校验"
        else:
            enabled = [name for name in ("V1", "V2", "V3") if schemes.get(name)]
            scheme_text = " + ".join(enabled) if enabled else "未校验到任何签名方案"
        log(OK, f"🎉 {PROJECT_NAME} {variant} 构建完成")
        print()
        print(f"    APK 路径 : {apk}")
        print(f"    文件大小 : {human_size(apk.stat().st_size)}")
        print(f"    签名方案 : {scheme_text}（alias={SIGNING['alias']}）")
        print(f"    安装命令 : adb install -r \"{apk}\"")
        print()
        return 0
    log(FAIL, "构建未完成，请检查上方错误输出")
    return 1


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="shso 一键编译脚本：环境预检 → 依赖校正 → Gradle 构建 → 签名校验",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--variant",
        choices=["Release", "Debug"],
        default="Release",
        help="构建变体（默认 Release）",
    )
    parser.add_argument("--clean", action="store_true", help="构建前先执行 clean")
    parser.add_argument("--skip-check", action="store_true", help="跳过环境预检")
    parser.add_argument(
        "--gradle-arg",
        action="append",
        default=[],
        metavar="ARG",
        help="附加传递给 Gradle 的参数（可重复）",
    )
    args = parser.parse_args(argv)

    print()
    print("╔" + "═" * 66 + "╗")
    print(f"║  {PROJECT_NAME} 一键编译脚本".ljust(66) + " ║")
    print(f"║  项目目录: {str(PROJECT_DIR)}".ljust(66) + " ║")
    print("╚" + "═" * 66 + "╝")

    if not args.skip_check and not precheck():
        log(FAIL, "环境预检未通过，已终止。修复上述项后重试（或加 --skip-check 跳过）。")
        return 2

    sync_local_properties()

    code = gradle_build(args.variant, args.clean, args.gradle_arg)
    apk = locate_apk(args.variant) if code == 0 else None
    sig_ok = True
    schemes: Optional[dict] = None
    if apk is not None:
        sig_ok, schemes = verify_signature(apk)

    # 构建成功但签名校验失败时，同样以非 0 退出码上报
    return summarize(apk, args.variant, code if sig_ok else 1, schemes)


if __name__ == "__main__":
    sys.exit(main())
