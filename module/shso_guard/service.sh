#!/system/bin/sh
###############################################################################
# shso_guard - late_start service 脚本
#
# 只做「善后保障」，不承担拦截逻辑：
#   拦截完全由 guard/ 下的守卫脚本在运行时完成（依赖 PATH 前置），
#   因此守卫在复制安装后立刻可用，无需等到本脚本执行。
#
# 职责：
#   - 保障审计目录 / 策略目录存在
#   - 复制安装（customize.sh 不执行）时，从模块自带默认配置生成 policy.conf
#   - 修复守卫脚本权限（复制安装最容易丢失可执行位）
#   - 开机时做一次审计日志轮转
#
# 规则：MODDIR=${0%/*}；等待必须带超时；全程幂等，可重复执行。
###############################################################################

MODDIR=${0%/*}
POLICY_DIR="/data/adb/shso_guard"
POLICY_FILE="$POLICY_DIR/policy.conf"
AUDIT_DIR="/data/adb/shso"
AUDIT_LOG="$AUDIT_DIR/audit.log"

log() { echo "[shso_guard] $1"; }

# 等待开机完成（Magisk 没有 boot-completed.sh，这里轮询兜底，带超时）
wait_for_boot() {
    local i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 120 ]; do
        sleep 1
        i=$((i + 1))
    done
    [ "$i" -ge 120 ] && return 1
    return 0
}

log "service.sh 启动 (API=$(getprop ro.build.version.sdk))"

# 1) 目录就绪
mkdir -p "$AUDIT_DIR" 2>/dev/null
mkdir -p "$POLICY_DIR" 2>/dev/null
chmod 0755 "$AUDIT_DIR" "$POLICY_DIR" 2>/dev/null

# 2) 策略文件兜底：复制安装时 customize.sh 不会执行，由这里生成
if [ ! -f "$POLICY_FILE" ] && [ -f "$MODDIR/policy.conf" ]; then
    cp "$MODDIR/policy.conf" "$POLICY_FILE" 2>/dev/null
    chmod 0644 "$POLICY_FILE" 2>/dev/null
    log "已生成默认策略: $POLICY_FILE"
fi

# 3) 守卫脚本权限修复
if [ -d "$MODDIR/guard" ]; then
    chmod 0755 "$MODDIR/guard" 2>/dev/null
    for _f in "$MODDIR/guard"/*; do
        [ -f "$_f" ] && chmod 0755 "$_f" 2>/dev/null
    done
fi

# 4) 审计日志轮转（开机做一次即可，避免每次调用都付出 O(n) 成本）
if [ -f "$AUDIT_LOG" ]; then
    _n=$(wc -l < "$AUDIT_LOG" 2>/dev/null | tr -d ' ')
    if [ -n "$_n" ] && [ "$_n" -gt 2400 ]; then
        tail -n 2000 "$AUDIT_LOG" > "$AUDIT_LOG.$$.tmp" 2>/dev/null \
            && mv "$AUDIT_LOG.$$.tmp" "$AUDIT_LOG" 2>/dev/null \
            && log "审计日志已轮转"
    fi
fi

# 5) 需要完整系统环境的后续逻辑放这里
if wait_for_boot; then
    log "开机完成，守卫就绪: $MODDIR/guard"
else
    log "等待开机超时，跳过后续（守卫本身不依赖开机完成）"
fi

log "service.sh 结束"
