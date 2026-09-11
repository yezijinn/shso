#!/system/bin/sh
# 设备侧：验证 needs_realpath 快速路径的正确性（符号链接绕过）+ 交错计时
G=/data/local/tmp/gt/guard
W=/data/local/tmp/gtw
rm -rf "$W"; mkdir -p "$W/real" "$W/bin"
echo data > "$W/real/f.txt"
echo data > "$W/real/f2.txt"

# 符号链接：指向受保护目录
ln -sfn /system      "$W/real/link-sys"
ln -sfn /data/system "$W/real/link-dsys"

# 真实二进制桩（记录调用 + 转发），用于确认是否走到 exec
for c in rm cp mv find sed mkfs.ext4; do
  printf '#!/system/bin/sh\necho "REAL:%s $*"\n' "$c" > "$W/bin/$c"
  chmod 755 "$W/bin/$c"
done

POL="$W/policy.conf"
printf 'mode=enforce\nprotect=/ /system /data/system /data\nallow=/data/local/tmp /sdcard\n' > "$POL"
PF="$W/audit.log"; : > "$PF"

run() {  # run <期望 BLOCK|ALLOW> <描述> <脚本> <参数...>
  exp="$1"; desc="$2"; sc="$3"; shift 3
  out=$(PATH="$W/bin:$PATH" SHSO_POLICY="$POL" SHSO_AUDIT="$PF" sh "$G/$sc" "$@" 2>&1); rc=$?
  if [ "$exp" = "BLOCK" ]; then
    case "$out" in
      *已拦截*) echo "  PASS  $desc" ;;
      *) echo "  FAIL  $desc  (期望拦截, rc=$rc, out=$out)" ;;
    esac
  else
    case "$out" in
      REAL:*) echo "  PASS  $desc  -> $(printf '%s' "$out" | head -1)" ;;
      *) echo "  FAIL  $desc  (期望放行, rc=$rc, out=$out)" ;;
    esac
  fi
}

echo "===== 符号链接绕过（needs_realpath 新分支）====="
run BLOCK "s1) rm -rf <link->/system>/x（只探测不存在文件）" rm -rf "$W/real/link-sys/__probe_nonexistent__"
run BLOCK "s2) rm -rf <link->/data/system>/x"               rm -rf "$W/real/link-dsys/__probe_nonexistent__"
run BLOCK "s3) 纯路径 /system/x（无链接，走词法）"          rm -rf /system/__probe_nonexistent__
run BLOCK "s4) /system/./x 词法归一后仍拦"                   rm -rf /system/./__probe_nonexistent__
run BLOCK "s5) /system/../system/x 词法归一后仍拦"           rm -rf /system/../system/__probe_nonexistent__
run BLOCK "s6) sed -i 经符号链接指向 /system"                sed -i 1d "$W/real/link-sys/__probe_nonexistent__"
run BLOCK "s7) cp 到符号链接指向的 /system 目标"             cp /dev/zero "$W/real/link-sys/__probe_nonexistent__"
run BLOCK "s8) find 符号链接目录 -delete"                    find "$W/real/link-sys" -name x -delete
echo
echo "===== 正常路径不得误杀 ====="
run ALLOW "n1) rm -rf /data/local/tmp/x"                      rm -rf /data/local/tmp/x
run ALLOW "n2) rm -rf 真实目录内文件"                         rm -rf "$W/real/f2.txt"
run ALLOW "n3) cp 真实文件"                                   cp "$W/real/f.txt" "$W/real/g.txt"
run ALLOW "n4) sed -i 非链接文件"                             sed -i s/a/b/ "$W/real/f.txt"
run ALLOW "n5) find 非链接目录 -delete"                       find "$W/real" -name f.txt -delete
echo
echo "===== sed 脚本参数不得被当作路径（误杀回归）====="
run ALLOW "p1) sed -i '/system/d' <允许文件>（脚本像绝对路径）" sed -i "/system/d" "$W/real/f.txt"
run ALLOW "p2) sed -i -e 's#/system#x#' <允许文件>"            sed -i -e "s#/system#x#" "$W/real/f.txt"
run ALLOW "p3) sed -i s/a/b/ <允许文件>（脚本无斜杠）"          sed -i s/a/b/ "$W/real/f.txt"
run BLOCK "p4) sed -i '/x/d' /system/__probe_nonexistent__"    sed -i "/x/d" /system/__probe_nonexistent__
run BLOCK "p5) sed -i -e s/a/b/ /system/__probe_nonexistent__" sed -i -e s/a/b/ /system/__probe_nonexistent__
echo
echo "===== 审计留痕 ====="
echo "  日志行数 = $(wc -l < "$PF")"
tail -2 "$PF" | sed 's/^/    /'
echo
echo "===== 交错计时（各 300 次）====="
echo x > "$W/src"
r=1
while [ $r -le 2 ]; do
  echo "  第${r}轮 裸 cp:"
  time sh -c "i=0; while [ \$i -lt 300 ]; do cp $W/src $W/x 2>/dev/null; i=\$((i+1)); done"
  echo "  第${r}轮 守卫 cp:"
  time sh -c "i=0; while [ \$i -lt 300 ]; do sh $G/cp $W/src $W/x 2>/dev/null; i=\$((i+1)); done"
  r=$((r+1))
done
