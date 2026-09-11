#!/system/bin/sh
###############################################################################
# uninstall.sh — 模块被移除时执行
#
# 注意：执行时模块文件可能已被部分删除，所有操作都要先判存在。
# 不要依赖 $0 定位模块目录（不可靠），改用显式路径。
#
# 清理策略（明确说明，便于审计）：
#   - 删除本模块自建的策略目录 /data/adb/shso_guard/（含 policy.conf）。
#     重装时由 service.sh 从模块自带 policy.conf 重新生成，无需保留。
#   - 【保留】审计日志 /data/adb/shso/audit.log：
#     取证价值 > 清理价值，卸载不触碰；需要彻底清除时用户手动删除该目录即可。
#   - 守卫脚本本身由模块管理器随模块目录一并移除，此处不处理。
###############################################################################

MODDIR=${0%/*}
[ ! -d "$MODDIR" ] && MODDIR="/data/adb/modules/shso_guard"

# 仅移除本模块专属的策略目录（固定路径，避免误删）。
SHSO_POLICY_DIR="/data/adb/shso_guard"
SHSO_AUDIT_DIR="/data/adb/shso"

if [ -d "$SHSO_POLICY_DIR" ]; then
    rm -rf "$SHSO_POLICY_DIR" 2>/dev/null \
        && echo "[shso_guard] 已移除策略目录: $SHSO_POLICY_DIR" \
        || echo "[shso_guard] 警告：移除策略目录失败: $SHSO_POLICY_DIR" >&2
else
    echo "[shso_guard] 策略目录不存在，跳过: $SHSO_POLICY_DIR"
fi

# 审计日志【保留】用于取证，不在此删除。
if [ -d "$SHSO_AUDIT_DIR" ]; then
    echo "[shso_guard] 保留审计目录（取证）: $SHSO_AUDIT_DIR"
fi

echo "[shso_guard] 卸载完成。"
exit 0
