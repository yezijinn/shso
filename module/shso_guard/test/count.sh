#!/bin/sh
# 统计「守卫包装器每次调用创建的外部进程数」——fork 数是守卫开销的唯一决定因素
# （实测本机 fork+exec ≈ 15–18ms，故进程数直接换算为延迟）。
ROOT="/c/AI_WORKSPACE/PROJECTS/com.mixradio.droid"
W="$ROOT/.tmp-guard-test/work"
GUARD="$ROOT/shso_guard/guard"
CNT="$W/countbin"

rm -rf "$CNT"; mkdir -p "$CNT"
# 桩：凡是守卫可能调用的外部命令都记一笔，再转发给真实命令。
# 注意：必须解析出**绝对路径**再转发。Git Bash 里 `command -v rm` 可能返回裸名
# `rm`（存在同名 alias/function），若原样 exec 会重新命中本桩 → 无限递归。
for t in tr realpath date dirname basename mkdir wc tail head cat ls cp mv find sed rm sh; do
    real=$(type -P "$t" 2>/dev/null || command -v "$t" 2>/dev/null)
    case "$real" in
        /*) ;;
        *)  echo "  [跳过] 无法解析 $t 的绝对路径（得到 '${real:-空}'）"; continue ;;
    esac
    printf '#!/bin/sh\necho %s >> "$COUNTER_FILE"\nexec "%s" "$@"\n' "$t" "$real" > "$CNT/$t"
    chmod 755 "$CNT/$t"
done
echo "计数桩: $(ls "$CNT" | tr '\n' ' ')"
echo

measure() {
    label="$1"; script="$2"; shift 2
    : > "$W/c"
    COUNTER_FILE="$W/c" PATH="$CNT:$W/fakebin:$PATH" sh "$GUARD/$script" "$@" >/dev/null 2>&1
    n=$(grep -c . "$W/c" 2>/dev/null || echo 0)
    printf '  %-32s 子进程 = %-3s  (总进程 ≈ %s)\n' "$label" "$n" "$((n+1))"
    if [ "$n" -gt 0 ]; then
        sort "$W/c" | uniq -c | sort -rn | sed 's/^/       /'
    fi
}

echo "=== 守卫包装器（本轮优化后）==="
measure "cp a b"                 cp       a b
measure "rm -rf /sdcard/ok"      rm       -rf /sdcard/ok
measure "find /sdcard -name x"   find     /sdcard -name x
measure "sed s/a/b/ /system/x"   sed      s/a/b/ /system/x
measure "mv /sdcard/a /sdcard/b" mv       /sdcard/a /sdcard/b
measure "rm -rf /system (DENY)"  rm       -rf /system

echo
echo "=== 对照：裸奔（无守卫）==="
: > "$W/c0"
COUNTER_FILE="$W/c0" PATH="$CNT:$W/fakebin:$PATH" sh -c 'cp a b' >/dev/null 2>&1
n0=$(grep -c . "$W/c0" 2>/dev/null || echo 0)
printf '  %-32s 子进程 = %s\n' "cp a b（无守卫）" "$n0"
