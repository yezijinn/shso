#!/bin/sh
###############################################################################
# shso_guard - 安装脚本 (customize.sh)
#
# 两种安装方式：
#   A. 刷 zip   —— 本脚本由安装器 source 执行（Magisk / KernelSU / APatch 均支持）
#   B. 复制安装 —— 把整个目录拷到 /data/adb/modules/<模块ID> 即可，
#                  本脚本【不会】被执行。因此模块必须开箱可用：
#                    · guard/ 下的守卫是预生成的，不依赖任何安装期逻辑
#                    · policy.conf 随模块自带，service.sh 开机时兜底部署
#
# 约束（通用模块铁律）：
#   - 末尾禁止 exit（会跳过安装器收尾）    → 用 abort
#   - 输出禁止 echo                        → 用 ui_print
#   - 禁止 set -e                          → 手动判错
#   - 必须 LF 换行
###############################################################################

SKIPUNZIP=0

MIN_API=0
SUPPORT_ARCH=""

###############################################################################
# 环境检测（顺序不可调换：APATCH -> KSU -> Magisk）
###############################################################################
if [ "$APATCH" = "true" ] || [ -n "$KERNELPATCH" ]; then
    ROOT_SOLUTION="APatch"
elif [ "$KSU_NEXT" = "true" ]; then
    ROOT_SOLUTION="KernelSU-Next"
elif [ "$KSU" = "true" ]; then
    ROOT_SOLUTION="KernelSU"
else
    ROOT_SOLUTION="Magisk"
fi

ui_print "***************************************"
ui_print " shso_guard —— shso 指令守卫"
ui_print " Root : $ROOT_SOLUTION"
ui_print " API  : $API   ARCH: $ARCH"
ui_print "***************************************"

if [ "$MIN_API" -gt 0 ] && [ "$API" -lt "$MIN_API" ]; then
    abort "! 需要 Android API $MIN_API 及以上，当前 $API"
fi

# 本模块不使用 system/ 目录（纯脚本 + 运行期 PATH 拦截），
# 因此不依赖 KernelSU 的 metamodule，三种方案零额外依赖。
# 若有 system/ 目录才需要提示：
# if [ -d "$MODPATH/system" ] && [ "$KSU" = "true" ] && [ "$APATCH" != "true" ]; then
#     ui_print "! KernelSU 需安装 metamodule 才会挂载 system/"
# fi

###############################################################################
# 完整性校验：common.sh 缺失会让守卫 fail-closed 拒绝执行，安装期就拦下
###############################################################################
if [ ! -f "$MODPATH/guard/common.sh" ]; then
    abort "! 守卫公共库缺失: guard/common.sh"
fi

###############################################################################
# 权限设置
###############################################################################
set_perm_recursive "$MODPATH" 0 0 0755 0644

for _s in service.sh post-fs-data.sh post-mount.sh boot-completed.sh action.sh uninstall.sh; do
    [ -f "$MODPATH/$_s" ] && set_perm "$MODPATH/$_s" 0 0 0755
done

# 守卫脚本必须全部可执行（复制安装最容易丢 +x，service.sh 也会兜底修复）
if [ -d "$MODPATH/guard" ]; then
    set_perm_recursive "$MODPATH/guard" 0 0 0755 0755
fi

###############################################################################
# 目录与策略：刷 zip 时提前部署，安装完即可用
###############################################################################
mkdir -p /data/adb/shso 2>/dev/null
mkdir -p /data/adb/shso_guard 2>/dev/null
chmod 0755 /data/adb/shso /data/adb/shso_guard 2>/dev/null

if [ ! -f /data/adb/shso_guard/policy.conf ] && [ -f "$MODPATH/policy.conf" ]; then
    cp "$MODPATH/policy.conf" /data/adb/shso_guard/policy.conf
    chmod 0644 /data/adb/shso_guard/policy.conf
fi

ui_print "- 守卫目录: $MODPATH/guard"
ui_print "- 策略文件: /data/adb/shso_guard/policy.conf"
ui_print "- 审计日志: /data/adb/shso/audit.log"
ui_print "- 生效方式: 守卫目录前置到 PATH（由 shso App 自动完成）"
ui_print "- 安装完成，重启后模块在管理器中可见"
# 不要写 exit
