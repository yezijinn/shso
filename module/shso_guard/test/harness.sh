#!/usr/bin/env bash
# shso_guard 加固后的对抗性冒烟测试（lead 自建，独立于实现者）
#
# 环境注意：本机是 Git Bash on Windows。
#   - 必须使用 POSIX 形式路径（/c/...），否则 PATH 里的盘符冒号会被 IFS=':' 切碎；
#     这是 Windows 测试环境的特性，与 Android 无关（Android 路径无盘符）。
#   - PATH 采用「fakebin 前置 + 保留原 PATH」，否则 sh/tr/date/wc 都找不到。

set -u
ROOT="/c/AI_WORKSPACE/PROJECTS/com.mixradio.droid"
GUARD="$ROOT/shso_guard/guard"
WORK="$ROOT/.tmp-guard-test/work"
FAKE="$WORK/fakebin"
ORIG_PATH="$PATH"
EMPTY="$WORK/empty"

rm -rf "$WORK"; mkdir -p "$FAKE" "$WORK/policy" "$EMPTY"

for c in rm dd find sed mv cp toybox busybox truncate rmdir shred wipe; do
  printf '#!/bin/sh\necho "REAL:%s $*"\nexit 0\n' "$c" > "$FAKE/$c"
  chmod 755 "$FAKE/$c"
done

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  FAIL  %s\n' "$1"; }

# run_case <期望 BLOCK|ALLOW> <描述> <policy> <guard脚本> <参数...>
run_case() {
  local expect="$1" desc="$2" pf="$3" gscript="$4"; shift 4
  local out rc
  out=$(PATH="$FAKE:$ORIG_PATH" SHSO_POLICY="$pf" SHSO_AUDIT="$WORK/audit.log" \
        /bin/sh "$GUARD/$gscript" "$@" 2>&1); rc=$?
  if [ "$expect" = "BLOCK" ]; then
    if [ $rc -ne 0 ] && printf '%s' "$out" | grep -q "已拦截"; then ok "$desc"
    else bad "$desc  期望拦截，实际 rc=$rc out=[$out]"; fi
  else
    if [ $rc -eq 0 ] && printf '%s' "$out" | grep -q "^REAL:"; then ok "$desc -> $(printf '%s' "$out" | head -1)"
    else bad "$desc  期望放行，实际 rc=$rc out=[$out]"; fi
  fi
}

# ---------------------------------------------------------------- 策略文件变体
printf 'mode=enforce\nprotect=/ /system /system_ext /product /vendor /odm /apex /proc /sys /dev /data/system /data/misc /metadata\nallow=/sdcard /storage/emulated /data/media /data/local/tmp\n' > "$WORK/policy/lf.conf"
printf 'mode=enforce\r\nprotect=/ /system /data/system\r\nallow=/sdcard /data/local/tmp\r\n' > "$WORK/policy/crlf.conf"
printf 'mode = enforce\nprotect = /system /data/system\nallow = /sdcard\n' > "$WORK/policy/spaces.conf"
printf '# comment\n\nmode=enforce   # mode\nprotect=/system   # sys\nallow=/sdcard # user\n' > "$WORK/policy/comment.conf"
printf 'mode=garbage\nprotect=/system\nallow=/sdcard\n' > "$WORK/policy/badmode.conf"
printf 'mode=OFF\nprotect=/system\n' > "$WORK/policy/badmode2.conf"
printf 'mode=enforce\nprotect=/system\n' > "$WORK/policy/min.conf"
printf 'mode=enforce\nprotect=/data\nallow=/data/local/tmp\n' > "$WORK/policy/allowfirst.conf"

echo
echo "===== a/b/c/d  策略解析加固（P0 静默 fail-open 修复）====="
run_case BLOCK "a)  CRLF 行尾：rm -rf /system 仍被拦"      "$WORK/policy/crlf.conf"     rm      -rf /system
run_case BLOCK "b)  protect = /system（等号带空格）"        "$WORK/policy/spaces.conf"   rm      -rf /system
run_case BLOCK "c)  行尾内联注释不破坏规则"                 "$WORK/policy/comment.conf"  rm      -rf /system
run_case BLOCK "d)  mode=garbage 回退 enforce 且仍拦截"     "$WORK/policy/badmode.conf"  rm      -rf /system
run_case BLOCK "d2) mode=OFF（大写，非法）回退 enforce"     "$WORK/policy/badmode2.conf" rm      -rf /system
run_case ALLOW "c2) 同一注释策略下 /sdcard 正常放行"        "$WORK/policy/comment.conf"  rm      -rf /sdcard/x
run_case ALLOW "a2) CRLF 策略的 allow 清单同样生效(/sdcard)" "$WORK/policy/crlf.conf"    rm      -rf /sdcard/x
run_case BLOCK "a3) CRLF 策略的 protect 清单同样生效"        "$WORK/policy/crlf.conf"    rm      -rf /data/system/x

echo
echo "===== e/f  toybox / busybox 多二进制绕过（P0）====="
run_case BLOCK "e)   toybox rm -rf /system"                 "$WORK/policy/lf.conf" toybox  rm -rf /system
run_case BLOCK "f)   busybox rm -rf /system"                "$WORK/policy/lf.conf" busybox rm -rf /system
run_case BLOCK "e2)  toybox dd of=/dev/block/sda"           "$WORK/policy/lf.conf" toybox  dd if=/dev/zero of=/dev/block/sda
run_case BLOCK "e3)  toybox wipe（无参）"                   "$WORK/policy/lf.conf" toybox  wipe
run_case ALLOW "e4)  toybox ls（非守卫子命令零干预）"       "$WORK/policy/lf.conf" toybox  ls -l /sdcard

echo
echo "===== g/h/i  新覆盖面 mv / cp / find / sed ====="
run_case BLOCK "g)   mv /system/x /sdcard/（源受保护）"     "$WORK/policy/lf.conf" mv   /system/build.prop /sdcard/
run_case BLOCK "g2)  mv /sdcard/a /system/b（目标受保护）"  "$WORK/policy/lf.conf" mv   /sdcard/a /system/b
run_case BLOCK "g3)  cp /dev/zero /system/build.prop"       "$WORK/policy/lf.conf" cp   /dev/zero /system/build.prop
run_case BLOCK "h)   find /system -delete"                  "$WORK/policy/lf.conf" find /system -name '*.odex' -delete
run_case BLOCK "i)   sed -i 1d /system/build.prop"          "$WORK/policy/lf.conf" sed  -i 1d /system/build.prop
run_case BLOCK "h2)  find /system -exec rm {} ;"            "$WORK/policy/lf.conf" find /system -exec rm {} ";"

echo
echo "===== j/k  既有缺陷（fastboot -w / dd of 带空格 / wipe 无参）====="
run_case BLOCK "j)   fastboot -w"                           "$WORK/policy/lf.conf" fastboot -w
run_case BLOCK "j2)  fastboot flash boot boot.img"          "$WORK/policy/lf.conf" fastboot flash boot boot.img
run_case BLOCK "k)   wipe 无操作数 = 擦默认设备"            "$WORK/policy/min.conf" wipe

echo
echo "===== l  误杀检查（必须全部放行）====="
run_case ALLOW "l1) rm -rf /data/local/tmp/x（豁免）"       "$WORK/policy/lf.conf" rm   -rf /data/local/tmp/x
run_case ALLOW "l2) cp a b（相对路径）"                     "$WORK/policy/lf.conf" cp   a b
run_case ALLOW "l3) find /data/local/tmp -delete"           "$WORK/policy/lf.conf" find /data/local/tmp -delete
run_case ALLOW "l4) sed -i 1d /data/local/tmp/f"            "$WORK/policy/lf.conf" sed  -i 1d /data/local/tmp/f
run_case ALLOW "l5) rm -rf /sdcard/tmp"                     "$WORK/policy/lf.conf" rm   -rf /sdcard/tmp
run_case ALLOW "l6) mv /sdcard/a /sdcard/b"                 "$WORK/policy/lf.conf" mv   /sdcard/a /sdcard/b
run_case ALLOW "l7) find /sdcard -name x（无 -delete）"     "$WORK/policy/lf.conf" find /sdcard -name x
run_case ALLOW "l8) sed s/a/b/ /system/x（无 -i）"          "$WORK/policy/lf.conf" sed  "s/a/b/" /system/x
run_case ALLOW "l9) allow 优先于 protect"                   "$WORK/policy/allowfirst.conf" rm -rf /data/local/tmp/x

echo
echo "===== m  递归守卫 / PATH 写法差异 / 缺失二进制 ====="
out=$(PATH="$GUARD/:/system/bin:$FAKE:$ORIG_PATH" SHSO_POLICY="$WORK/policy/lf.conf" SHSO_AUDIT="$WORK/audit.log" \
      /bin/sh "$GUARD/rm" -rf /sdcard/ok 2>&1); rc=$?
if [ $rc -eq 0 ] && printf '%s' "$out" | grep -q "^REAL:rm"; then ok "m1) PATH 中守卫目录带尾斜杠：不递归"
else bad "m1) 带尾斜杠异常 rc=$rc out=[$out]"; fi

out=$(PATH="$GUARD//./:$FAKE:$ORIG_PATH" SHSO_POLICY="$WORK/policy/lf.conf" SHSO_AUDIT="$WORK/audit.log" \
      /bin/sh "$GUARD/rm" -rf /sdcard/ok 2>&1); rc=$?
if [ $rc -eq 0 ] && printf '%s' "$out" | grep -q "^REAL:rm"; then ok "m2) PATH 中双斜杠点写法：不递归"
else bad "m2) 异常 rc=$rc out=[$out]"; fi

out=$(SHSO_GUARD_DEPTH=9 PATH="$FAKE:$ORIG_PATH" SHSO_POLICY="$WORK/policy/lf.conf" SHSO_AUDIT="$WORK/audit.log" \
      /bin/sh "$GUARD/rm" -rf /sdcard/ok 2>&1); rc=$?
if [ $rc -ne 0 ] && printf '%s' "$out" | grep -q "递归深度超限"; then ok "m3) 深度超限 fail-closed（不挂死）"
else bad "m3) 深度守卫未生效 rc=$rc out=[$out]"; fi

# 缺失真实二进制：PATH 只留不会有 fastboot 的目录（本机 SDK platform-tools 里有 fastboot，必须排除）
out=$(PATH="$EMPTY:/bin" SHSO_POLICY="$WORK/policy/lf.conf" SHSO_AUDIT="$WORK/audit.log" \
      /bin/sh "$GUARD/fastboot" devices 2>&1); rc=$?
if [ $rc -ne 0 ] && printf '%s' "$out" | grep -q "找不到可执行的真实二进制"; then ok "m4) 真实二进制缺失：清晰报错 + fail-closed"
else bad "m4) 缺失二进制处理异常 rc=$rc out=[$out]"; fi

echo
echo "===== 审计日志 ====="
if [ -s "$WORK/audit.log" ] && grep -q "GUARD" "$WORK/audit.log"; then
  ok "审计日志已写入（含 GUARD 前缀 / $(grep -c . "$WORK/audit.log") 行）"
  echo "        尾行: $(tail -1 "$WORK/audit.log")"
else bad "审计日志未写入"; fi
if grep -q "DENY" "$WORK/audit.log" && grep -q "ALLOW" "$WORK/audit.log"; then ok "DENY 与 ALLOW 均已留痕"; else bad "审计未同时记录 DENY/ALLOW"; fi

echo
echo "===== 汇总 ====="
printf 'PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && echo "全部通过" || echo "存在失败"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
