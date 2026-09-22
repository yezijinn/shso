#!/system/bin/sh
###############################################################################
# shso_guard 公共库 —— 策略判定 / 路径归一化 / 审计落盘 / 决策分发
#
# 由 guard/<cmd> 脚本 source 调用，不可单独执行。
# 兼容性：POSIX 语法为主，不依赖 bash 专有设施；唯一例外是 sanitize_field 用于匹配换行的
# ANSI-C quoting（美元符 + 单引号包裹的反斜杠 n）—— Android /system/bin/sh 即 mksh、busybox ash
# 均支持，但 dash 不支持，故本模块的适用面为 mksh / busybox ash，不含 dash。
# 注意：注释里不得直接书写该字面量，写错会闭合引号/断行，直接破坏整个文件的解析。
#
# 设计要点
#   1. 策略文件缺失时，使用内置兜底清单，保证「裸装也受保护」。
#   2. allow 先于 protect 判定，因此 /data/media 可以豁免 /data 的保护。
#   3. 路径判定前必须归一化，否则 /system/../data 可绕过。
#   4. 匹配 protect="/" 时只判精确相等，否则 `/*` 会误伤全部路径。
#   5. 决策逻辑集中在 run_guard()，所有守卫（含 toybox/busybox 派发）共用，
#      禁止在各包装脚本里复制决策代码。
#   6. 任何无法判定之处一律 DENY（fail-closed）。
###############################################################################

# 守卫脚本 source 本文件前会显式设置 GUARD_DIR；此处仅为兜底推导，
# 不硬编码模块路径（复制安装时模块目录与 ID 绑定，写死会失效）。
# 被 source 时 $0 即调用方（guard/<cmd>）的路径，取其目录即 guard 目录。
GUARD_DIR="${GUARD_DIR:-${MODDIR:-${0%/*}}}"

# 安全关键路径**不接受任意环境覆盖**：守卫的防护对象就是"将被执行的命令"，
# 若允许其把策略文件指向自己可写的路径（实测 `SHSO_POLICY=<mode=off 文件>` 可整体
# 关闭守卫），守卫就等于自解除。排障请改用策略文件里的 mode=log。
# 覆盖仅允许指向受信目录内（词法判定，零子进程；/data/adb/shso_guard 为 root 0755）。
_OV_POLICY="${SHSO_POLICY:-}"
case "$_OV_POLICY" in
    /data/adb/shso_guard/*)
        # 前缀匹配挡不住 `…/../../data/local/tmp/x.conf` 这类回退：含 `..` 段一律拒绝。
        case "$_OV_POLICY" in
            */../*|*/..) POLICY_FILE="/data/adb/shso_guard/policy.conf" ;;
            *)           POLICY_FILE="$_OV_POLICY" ;;
        esac ;;
    *) POLICY_FILE="/data/adb/shso_guard/policy.conf" ;;
esac
_OV_AUDIT="${SHSO_AUDIT:-}"
case "$_OV_AUDIT" in
    /data/adb/shso/*)
        case "$_OV_AUDIT" in
            */../*|*/..) AUDIT_LOG="/data/adb/shso/audit.log" ;;
            *)           AUDIT_LOG="$_OV_AUDIT" ;;
        esac ;;
    *) AUDIT_LOG="/data/adb/shso/audit.log" ;;
esac
# 轮转阈值不接受覆盖：它只影响日志长度，无排障价值，却可被用来撑爆分区。
AUDIT_MAX_LINES=2000

# 内置兜底：受保护路径（策略文件缺失时生效）
DEFAULT_PROTECT="/ /system /system_ext /product /vendor /odm /apex /proc /sys /dev /data/system /data/misc /metadata"
# 内置兜底：豁免路径（优先级高于 protect）
DEFAULT_ALLOW="/sdcard /storage/emulated /data/media /data/adb/shso /data/local/tmp"

_POLICY_PROTECT=""
_POLICY_ALLOW=""
SHSO_MODE="enforce"

#------------------------------------------------------------------------------
# 工具函数（纯 POSIX；不依赖 bash 专属语法，无局部变量声明）
#------------------------------------------------------------------------------

# 去掉结尾斜杠（保留根 "/"）。结果写入全局 _SLASH_STRIPPED，不 fork 子 shell。
strip_slash() {
    case "$1" in
        "/") _SLASH_STRIPPED="/" ;;
        */)  _SLASH_STRIPPED="${1%/}" ;;
        *)   _SLASH_STRIPPED="$1" ;;
    esac
}

# 路径归一化。结果写入全局 _NORM_PATH，正常情况下**不 fork 子 shell**。
#
# 算法（安全关键，勿简化）：
#   1. 绝对化：相对路径按 $PWD 展开。
#   2. 逐级扫描分量，定位**第一处符号链接**（全部用 shell 内建 [ -L ]，零子进程）。
#   3. 命中符号链接时：**只对链接自身那一级**调用一次 realpath，再把其后的分量
#      按词法接回去，最后统一做词法收尾（. / .. / 重复斜杠）。
#   4. 无符号链接：纯词法处理 —— 与内核解析结果一致。
#
# 为什么不能「对整条路径直接调 realpath」：
#   叶子尚不存在时（典型：`cp file <link>/newfile`、`rm -rf <link>/x`），
#   realpath 会失败返回空；若此时退回纯词法，就会把 `<link>/newfile` 判为 ALLOW，
#   而内核实际写入的是**链接目标**（可能是 /system）——
#   这是一条真实的绕过路径，已在真机复现（link -> /system 时 cp 未被拦截）。
#   改为「只解析链接前缀」后，叶子是否存在都不影响判定。
#
# fail-closed：检测到符号链接却无法解析（realpath 不可用 / 链接悬空）时，
#   不返回词法结果，而是置空 _NORM_PATH 并返回 1，由调用方按拒绝处理。
#
# 说明：不改动调用者的位置参数（$@）；内部变量一律 _np_ 前缀。
normalize_path() {
    _np_in="$1"
    _NORM_PATH=""
    [ -z "$_np_in" ] && return 1

    case "$_np_in" in
        /*) _np_abs="$_np_in" ;;
        *)  _np_abs="$PWD/$_np_in" ;;
    esac

    # --- 逐级扫描，定位第一处符号链接，并记录其后分量 ---
    _np_acc=""; _np_rest=""; _np_seen=0
    _np_oifs="$IFS"
    IFS='/'
    for _np_seg in $_np_abs; do
        [ -z "$_np_seg" ] && continue
        if [ $_np_seen -eq 1 ]; then
            _np_rest="$_np_rest/$_np_seg"
            continue
        fi
        _np_acc="$_np_acc/$_np_seg"
        if [ -L "$_np_acc" ]; then
            _np_seen=1
        fi
    done
    IFS="$_np_oifs"

    # --- 命中符号链接：解析链接自身，再接回剩余分量 ---
    if [ $_np_seen -eq 1 ]; then
        _np_rp=""
        if command -v realpath >/dev/null 2>&1; then
            _np_rp=$(realpath "$_np_acc" 2>/dev/null)
        fi
        if [ -n "$_np_rp" ]; then
            _np_abs="${_np_rp}${_np_rest}"
        else
            _NORM_PATH=""
            return 1
        fi
    fi

    # --- 词法收尾 ---
    _np_out=""
    _np_oifs="$IFS"
    IFS='/'
    for _np_seg in $_np_abs; do
        case "$_np_seg" in
            ""|".") ;;
            "..") _np_out="${_np_out%/*}" ;;
            *)    _np_out="$_np_out/$_np_seg" ;;
        esac
    done
    IFS="$_np_oifs"

    [ -z "$_np_out" ] && _np_out="/"
    strip_slash "$_np_out"
    _NORM_PATH="$_SLASH_STRIPPED"
    return 0
}

# 递归深度守卫：每个守卫进程启动时调用一次。
# 若 PATH 里 guard 目录无法被归一化跳过（如符号链接），exec_real 可能反复返回
# 守卫自身，造成 exec 递归 / fork 炸弹。
#
# 定位（勿扩大）：这是**健壮性**防线，防误配置触发 fork 炸弹，**不是安全边界** ——
# 计数器来自可被调用者重置的环境变量，无法做到不可伪造；真正的防递归是
# exec_real() 跳过含 common.sh 的目录。因此这里只保证：非数字不得进入算术
# （旧实现 `[ "$_d" -ge 8 ] 2>/dev/null` 在非数字时静默失败且把非数字当 0），
# 上限收紧到 3（正常链路深度恒为 1，>1 即异常）。
guard_enter() {
    _d="${SHSO_GUARD_DEPTH:-0}"
    case "$_d" in
        ''|*[!0-9]*) _d=0 ;;
    esac
    if [ "$_d" -ge 3 ]; then
        echo "shso_guard: 递归深度超限（${_d}），拒绝执行以防 fork 炸弹（fail-closed）" >&2
        exit 1
    fi
    _d=$((_d + 1))
    export SHSO_GUARD_DEPTH="$_d"
}

# 加载策略文件；三处都找不到则用内置兜底清单（绝不因此放行）
#
# 查找顺序（必须覆盖「复制安装」场景——该场景 customize.sh 不会执行）：
#   1) $SHSO_POLICY        —— 环境变量覆盖，便于临时排障
#   2) /data/adb/shso_guard/policy.conf —— 用户可编辑，重装模块不丢失
#   3) $GUARD_DIR/../policy.conf        —— 模块自带默认配置（复制安装即开箱可用）
#
# 健壮性（P0）：
#   - 逐行剥除 \r（CRLF 文件常见），避免 protect=/system\r 永不命中。
#   - 去除 key/value 两侧空白，支持 "protect = /system" 这类写法。
#   - 跳过整行 # 注释与空行；仅当 # 前有空白时剥除行尾内联注释。
#   - 畸形行（无 =）直接忽略，不中止加载。
#   - mode 取值非 enforce|log|off 时一律 fail-closed 回退到 enforce。
#
# 性能约束（重要，勿回退）：
#   本函数在**每一次**守卫调用中执行，而 mv/cp/find/sed 可能是脚本里的高频命令。
#   因此循环体内**禁止**任何会创建子进程的写法（命令替换 $()、管道、tr/sed/cut…）。
#   实测教训：早期版本用 `_line=$(printf|tr -d '\r')` + `$(trim_ws ...)`，
#   单次 rm 调用会创建 50+ 个外部进程，100 次 cp 的脚本从 3 秒劣化到 3 分钟以上。
#   现全部改用 POSIX 参数展开（[:space:] 天然包含 \r，故 CR 无需单独处理）。
load_policy() {
    _POLICY_PROTECT="$DEFAULT_PROTECT"
    _POLICY_ALLOW="$DEFAULT_ALLOW"
    SHSO_MODE="enforce"

    _pf="$POLICY_FILE"
    [ -f "$_pf" ] || _pf="/data/adb/shso_guard/policy.conf"
    [ -f "$_pf" ] || _pf="$GUARD_DIR/../policy.conf"
    [ -f "$_pf" ] || return 0
    POLICY_FILE="$_pf"

    while IFS= read -r _line || [ -n "$_line" ]; do
        # 1) 去整行首尾空白。POSIX 的 [:space:] 已包含 \r，
        #    故 CRLF 的行尾 CR 在此一并剥除，**无需再单独跑 tr**。
        _line="${_line#"${_line%%[![:space:]]*}"}"
        _line="${_line%"${_line##*[![:space:]]}"}"
        # 2) 跳空行 / 整行注释
        case "$_line" in
            ''|'#'*) continue ;;
        esac
        # 3) 行尾内联注释：仅 # 前有空白才当作注释剥除
        case "$_line" in
            *' #'*) _line="${_line%% #*}" ;;
        esac
        # 4) 必须有 =，否则视为畸形行忽略
        case "$_line" in
            *=*) ;;
            *) continue ;;
        esac
        # 5) 拆分并去两侧空白（纯参数展开，与上同）
        _k="${_line%%=*}"
        _k="${_k#"${_k%%[![:space:]]*}"}"
        _k="${_k%"${_k##*[![:space:]]}"}"
        _v="${_line#*=}"
        _v="${_v#"${_v%%[![:space:]]*}"}"
        _v="${_v%"${_v##*[![:space:]]}"}"
        [ -z "$_k" ] && continue
        case "$_k" in
            protect) _POLICY_PROTECT="$_POLICY_PROTECT $_v" ;;
            allow)   _POLICY_ALLOW="$_POLICY_ALLOW $_v" ;;
            mode)
                case "$_v" in
                    enforce|log|off) SHSO_MODE="$_v" ;;
                    *) SHSO_MODE="enforce" ;;   # 未知值 fail-closed
                esac
                ;;
        esac
    done < "$_pf"
}

# 判定单个已归一化路径，结果写入全局 _JUDGE_RESULT（ALLOW|DENY）。
# 注意：刻意**不用 $(judge ...)** —— 命令替换会 fork 一个子 shell，而本函数在
#   「每个操作数」上都会被调用。改为全局变量返回，零子进程。
# 内部变量一律 _j_ 前缀，避免与调用方（run_guard）的变量互相污染。
judge() {
    _j_path="$1"
    _JUDGE_RESULT="ALLOW"

    # 1) 豁免优先
    for _j_a in $_POLICY_ALLOW; do
        [ -z "$_j_a" ] && continue
        case "$_j_path" in
            "$_j_a"|"$_j_a"/*) return 0 ;;
        esac
    done

    # 2) 受保护判定
    for _j_p in $_POLICY_PROTECT; do
        [ -z "$_j_p" ] && continue
        if [ "$_j_p" = "/" ]; then
            # 根路径只做精确匹配，避免 "/*" 误伤所有绝对路径
            [ "$_j_path" = "/" ] && { _JUDGE_RESULT="DENY"; return 0; }
            continue
        fi
        case "$_j_path" in
            "$_j_p"|"$_j_p"/*) _JUDGE_RESULT="DENY"; return 0 ;;
        esac
    done

    return 0
}

# 审计字段清洗：维持「一行一条记录」的不变式。
# 不变量：审计行以 | 分隔字段，字段内出现 | 会让字段错位，出现换行则可注入
# 一整行伪造记录（真机已复现：args 携带换行后日志里出现完整假行）。
# 结果写入全局 _SANITIZED。
#
# 性能：仅当字段含分隔符时才进入循环，正常路径零迭代、零子进程。
# 写法约束（两处踩过坑，勿"简化"）：
#   1. 模式里的分隔符必须**带引号**：mksh 的参数展开沿用 ksh 扩展模式，未引号的
#      `|` 会被当作模式交替符 —— `${_s%%|*}` 于是变成"删除任意后缀"，替换结果
#      每轮只增长不收敛，直接死循环（真机复现：守卫进程挂死，需 kill -9）。
#   2. 换行必须写成 $'\n'：在 case 模式/参数展开里嵌入跨行字面量会让 mksh 报
#      `no closing quote`，整个 common.sh 解析失败（函数缺失 + 脚本转为读 stdin
#      而挂起）。bash 的 `-n` 检查发现不了这两种问题，**推送前必须用设备上的
#      `sh -n common.sh` 校验，并在真机上跑一次带 `|` 与换行的审计用例**。
sanitize_field() {
    _s="$1"
    while :; do
        case "$_s" in
            *"|"*)   _s="${_s%%"|"*}_${_s#*"|"}" ;;
            *$'\n'*) _s="${_s%%$'\n'*} ${_s#*$'\n'}" ;;
            *) break ;;
        esac
    done
    _SANITIZED="$_s"
}

# 审计落盘（追加写；超过上限时保留尾部）。
# 用法: audit <verdict> <rule> <cmd> <args> <path>
# 注意：轮转使用每进程唯一的临时名（audit.log.$$.tmp），
#       避免并发守卫调用共用固定临时名互相覆盖丢行。
# 性能：dirname 用参数展开替代；wc -l 需扫描整个日志，故用 PID 抽样
#       （每 16 次调用才检查一次）——每次调用只追加 1 行，因此轮转最多
#       迟滞 16 行，仍能有效约束增长，而均摊成本降到可忽略。
audit() {
    _vd="$1"; _rule="$2"; _cmd="$3"; _args="$4"; _path="$5"
    sanitize_field "$_cmd";  _cmd="$_SANITIZED"
    sanitize_field "$_args"; _args="$_SANITIZED"
    sanitize_field "$_path"; _path="$_SANITIZED"

    _ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
    _dir="${AUDIT_LOG%/*}"
    [ "$_dir" = "$AUDIT_LOG" ] && _dir=""
    [ -n "$_dir" ] && [ ! -d "$_dir" ] && mkdir -p "$_dir" 2>/dev/null

    # 目标是软链 / 非常规文件时先清除：审计目录为 0777（刻意，供第三方文件管理器
    # 访问），第三方可在其中放置软链，`>>` 会跟随软链等于以 root 追加任意文件。
    # 内建 [ -L ] 零成本；仅异常时才 fork 一次 rm。
    if [ -L "$AUDIT_LOG" ] || { [ -e "$AUDIT_LOG" ] && [ ! -f "$AUDIT_LOG" ]; }; then
        /system/bin/rm -f -- "$AUDIT_LOG" 2>/dev/null
        # 清除后仍非常规文件 → 放弃本次写入：`>>` 会跟随软链，等于以 root 写任意文件。
        # 这里 return 而不是 exit —— 调用方 run_guard 随后还要输出拦截提示并决定退出码。
        if [ -L "$AUDIT_LOG" ] || { [ -e "$AUDIT_LOG" ] && [ ! -f "$AUDIT_LOG" ]; }; then
            return 0
        fi
    fi

    echo "${_ts}|GUARD|${_vd}|${_rule}|cmd=${_cmd}|args=${_args}|path=${_path}" >> "$AUDIT_LOG" 2>/dev/null

    [ $(( $$ % 16 )) -eq 0 ] || return 0
    # set -- 借助分词去掉 wc 的前导空白，避免再 fork 一个 tr
    set -- $(wc -l < "$AUDIT_LOG" 2>/dev/null)
    _n="${1:-}"
    case "$_n" in
        ''|*[!0-9]*) return 0 ;;
    esac
    if [ "$_n" -gt $((AUDIT_MAX_LINES + 400)) ] 2>/dev/null; then
        _tmp="${AUDIT_LOG}.$$.tmp"
        tail -n "$AUDIT_MAX_LINES" "$AUDIT_LOG" > "$_tmp" 2>/dev/null \
            && mv "$_tmp" "$AUDIT_LOG" 2>/dev/null
    fi
}

# 在 PATH 中定位真实二进制并立即 exec 它（**不经 $( ) 返回路径**，省一个子 shell）。
# 跳过守卫目录——以「该目录是否含 common.sh」判定身份，对写法差异（尾斜杠 / // /
# /./ / 符号链接）天然免疫，且 [ -f ] 是 shell 内建、不创建子进程。
# 找不到可执行的真实二进制时向 stderr 报错并以 127 退出（fail-closed，
# 绝不 exec 一个不存在/未知的路径）。
# 用法: exec_real <cmd> [args...]
exec_real() {
    _c="$1"; shift
    _OIFS="$IFS"
    IFS=':'
    for _d in $PATH; do
        [ -z "$_d" ] && continue
        [ -f "$_d/common.sh" ] && continue
        if [ -x "$_d/$_c" ]; then
            IFS="$_OIFS"
            exec "$_d/$_c" "$@"
        fi
    done
    IFS="$_OIFS"
    # 兜底：PATH 未列 /system/bin 但二进制确实存在
    if [ -x "/system/bin/$_c" ]; then
        exec "/system/bin/$_c" "$@"
    fi
    echo "shso_guard: 找不到可执行的真实二进制 ${_c}（fail-closed）" >&2
    exit 127
}

# 把子命令名映射到操作数提取模式，供 toybox/busybox 派发复用。
# 结果写入全局 _GUARD_OPERAND_MODE（空串 = 该子命令不受守卫，直接透传真实二进制）。
# 刻意不用 $( ) 返回——命令替换会 fork 子 shell。
#
# 不变量：本清单必须与 guard/ 下的包装器清单**同集合**（见 gen_wrappers.py specs）。
# 漏一个就等于该命令在 `toybox <cmd>` / `busybox <cmd>` 形态下完全不受守卫
# ——真机已复现：`toybox chmod -R 777 /system/...` 未被拦截且不留审计，
# 而直接 `chmod` 被拦。新增包装器时必须同步扩充本表。
guard_operand_mode() {
    case "$1" in
        rm|rmdir|shred|truncate|wipe) _GUARD_OPERAND_MODE="ARGS" ;;
        mkfs*|mke2fs|make_f2fs)       _GUARD_OPERAND_MODE="ARGS" ;;
        chmod|chown|chgrp|mknod|sgdisk|parted|fdisk|flash_image|wipefs|chattr)
                                      _GUARD_OPERAND_MODE="ARGS" ;;
        dd)                           _GUARD_OPERAND_MODE="DD" ;;
        fastboot)                     _GUARD_OPERAND_MODE="FASTBOOT" ;;
        mv|cp|ln|install)             _GUARD_OPERAND_MODE="MVCP" ;;
        find)                         _GUARD_OPERAND_MODE="FIND" ;;
        sed)                          _GUARD_OPERAND_MODE="SED" ;;
        tee)                          _GUARD_OPERAND_MODE="TEE" ;;
        *)                            _GUARD_OPERAND_MODE="" ;;
    esac
}

#------------------------------------------------------------------------------
# 决策核心：提取操作数 → 归一化 → 对照策略 → ALLOW/DENY → 审计
#   - 返回 0 表示「放行」，调用方随后 exec 真实二进制；
#   - enforce 下命中则直接 exit 1（失败关闭），绝不退化成放行；
#   - log 模式下只记录不拦截（返回 0）。
#
# 操作数模式：
#   ARGS     —— 非 - 开头的参数视为路径（rm / rmdir / shred / mkfs.* / truncate / wipe）
#   DD       —— 额外解析 of=<path>（及 of =<path>、of= <path> 空格变体）
#   FASTBOOT —— 子命令判定（-w/--wipe/erase/format/wipe/flash/oem unlock 一律高危）
#   MVCP     —— mv/cp：判定目标（末操作数）；mv 还需判定源（除末操作数外全部）
#   FIND     —— 仅当含 -delete，或 -exec/-execdir 后接 rm/rmdir/sh/bash 时介入，
#              并判定搜索起始路径（首个非选项操作数）
#   SED      —— 仅当含 -i 时介入，并判定文件操作数
#
# 调用：run_guard <cmd> <operand_mode> <args...>
#------------------------------------------------------------------------------
run_guard() {
    _g_cmd="$1"; _g_mode="$2"; shift 2
    _paths=""

    case "$_g_mode" in
        DD)
            _i=1; _n=$#
            while [ $_i -le $_n ]; do
                eval "_a=\"\${$_i}\""
                case "$_a" in
                    of=*)
                        _v="${_a#of=}"
                        if [ -z "$_v" ]; then
                            # 形式 of= /path：of= 后一个参数才是路径
                            _j=$((_i + 1))
                            if [ $_j -le $_n ]; then
                                eval "_v=\"\${$_j}\""
                                _i=$((_i + 1))
                            fi
                        fi
                        [ -n "$_v" ] && _paths="$_paths $_v"
                        ;;
                    "of ="*)
                        _v="${_a#of =}"
                        [ -n "$_v" ] && _paths="$_paths $_v"
                        ;;
                esac
                _i=$((_i + 1))
            done
            ;;
        FASTBOOT)
            for _a in "$@"; do
                case "$_a" in
                    -w|--wipe|erase|format|wipe|flash|"oem unlock")
                        _paths="$_paths __FASTBOOT_DESTRUCTIVE__" ;;
                esac
            done
            ;;
        MVCP)
            _n=$#
            _last=""
            if [ $_n -gt 0 ]; then eval "_last=\"\${$_n}\""; fi
            # mv：源（除末操作数外）也要判定——移动受保护路径即销毁
            if [ "$_g_cmd" = "mv" ]; then
                _i=1
                while [ $_i -lt $_n ]; do
                    eval "_a=\"\${$_i}\""
                    case "$_a" in
                        -*) ;;
                        *)  _paths="$_paths $_a" ;;
                    esac
                    _i=$((_i + 1))
                done
            fi
            # 目标（末操作数）始终判定
            if [ -n "$_last" ]; then
                case "$_last" in
                    -*) ;;
                    *)  _paths="$_paths $_last" ;;
                esac
            fi
            ;;
        FIND)
            _i=1; _n=$#; _destruct=0
            while [ $_i -le $_n ]; do
                eval "_a=\"\${$_i}\""
                case "$_a" in
                    -delete) _destruct=1 ;;
                    -exec|-execdir)
                        _j=$((_i + 1))
                        if [ $_j -le $_n ]; then
                            eval "_b=\"\${$_j}\""
                            # 取 basename 判定：`-exec /system/bin/rm`、`-exec toybox rm`
                            # 与解释器内联执行同样不可审计，一律介入。
                            # （只匹配字面 rm 会让带路径/带前缀的形态整体漏判。）
                            case "${_b##*/}" in
                                rm|rmdir|sh|bash|ash|mksh|python|python3|perl|node|php|toybox|busybox)
                                    _destruct=1 ;;
                            esac
                        fi
                        ;;
                esac
                _i=$((_i + 1))
            done
            if [ $_destruct -eq 1 ]; then
                for _a in "$@"; do
                    case "$_a" in
                        -*) ;;
                        *)  _paths="$_paths $_a"; break ;;
                    esac
                done
            fi
            ;;
        SED)
            # 仅当出现 -i（原地修改）才介入；否则 sed 不落盘，零干预。
            # 操作数提取必须**排除 sed 脚本本身**，否则形如 `sed -i '/system/d' f`
            # 的脚本串会被当作绝对路径 → 误杀（false positive）。
            # 规则（POSIX sed）：有 -e/-f 时脚本来自选项值；否则第一个非选项参数
            # 就是脚本。故：-e/-f 后的那个值跳过；无 -e/-f 时跳过第一个非选项参数。
            _has_ef=0
            for _a in "$@"; do
                case "$_a" in
                    -e|-e?*|-f|-f?*|--expression|--expression=*|--file|--file=*)
                        _has_ef=1 ;;
                esac
            done
            _has_i=0; _skip=0
            _take_script=0
            [ $_has_ef -eq 0 ] && _take_script=1
            for _a in "$@"; do
                if [ $_skip -eq 1 ]; then _skip=0; continue; fi
                case "$_a" in
                    -e|-f|--expression|--file)  _skip=1; continue ;;
                    -e?*|-f?*|--expression=*|--file=*)  continue ;;
                    # 原地修改：短选项与长选项都要认，否则 `sed --in-place … /system/x`
                    # 会被当作"无 -i"整体放行（busybox/GNU sed 环境可绕过）。
                    -i|-i*|--in-place|--in-place=*)     _has_i=1; continue ;;
                    -*)         continue ;;
                esac
                if [ $_take_script -eq 1 ]; then
                    _take_script=0      # 第一个非选项参数 = sed 脚本，不是路径
                    continue
                fi
                _paths="$_paths $_a"
            done
            [ $_has_i -eq 1 ] || _paths=""
            ;;
        TEE)
            # tee <file>...：把管道内容写入列出的每个文件（`… | tee /system/x`）。
            # 目标是系统/设备文件即等高危写入；-a/--append 为追加标志，不参与判定。
            for _a in "$@"; do
                case "$_a" in
                    -a|--append|-i|--ignore-interrupts) ;;
                    -*) ;;
                    *)  _paths="$_paths $_a" ;;
                esac
            done
            ;;
        *)
            # ARGS：非选项参数视为路径；-- 之后一律视为操作数
            _dash=0
            for _a in "$@"; do
                if [ $_dash -eq 1 ]; then
                    _paths="$_paths $_a"; continue
                fi
                case "$_a" in
                    --) _dash=1 ;;
                    -*) ;;
                    *)  _paths="$_paths $_a" ;;
                esac
            done
            ;;
    esac

    # 操作数含未解析变量（未定义变量被原样传入，如 `T=; rm -rf "$T"`）→ 目标不可静态
    # 判定，与静态层 UNRESOLVED_DESTRUCTIVE 对齐，一律 fail-closed。
    case "$_paths" in
        *'$'*) _verdict="DENY"; _rule="UNRESOLVED_TARGET"; _hit="$_paths" ;;
        *)
            # 特殊：wipe 无操作数 = 擦除默认设备，enforce 下最危险 → 拒绝
            if [ "$_g_cmd" = "wipe" ] && [ -z "$_paths" ]; then
                _verdict="DENY"; _rule="WIPE_NO_OPERAND"; _hit="(no operand)"
            else
                _verdict="ALLOW"; _rule="NONE"; _hit=""
                for _p in $_paths; do
                    if [ "$_p" = "__FASTBOOT_DESTRUCTIVE__" ]; then
                        _verdict="DENY"; _rule="FASTBOOT_DESTRUCTIVE"; _hit="${_g_cmd} $*"
                        break
                    fi
                    [ -z "$_p" ] && continue
                    normalize_path "$_p"
                    if [ -z "$_NORM_PATH" ]; then
                        # 可见符号链接却无法解析（realpath 不可用 / 链接悬空）：
                        # 不猜测解析结果，直接按 fail-closed 拒绝。
                        _verdict="DENY"; _rule="PATH_UNRESOLVABLE"; _hit="$_p"
                        break
                    fi
                    judge "$_NORM_PATH"
                    if [ "$_JUDGE_RESULT" = "DENY" ]; then
                        _verdict="DENY"; _rule="PROTECTED_PATH"; _hit="$_NORM_PATH"
                        break
                    fi
                done
            fi
            ;;
    esac

    if [ "$_verdict" = "DENY" ]; then
        audit DENY "$_rule" "$_g_cmd" "$*" "$_hit"
        if [ "$SHSO_MODE" = "enforce" ]; then
            echo "shso_guard: 已拦截 [${_g_cmd}] —— 命中受保护路径/危险操作: ${_hit}" >&2
            echo "shso_guard: 确需执行，请在 ${POLICY_FILE} 增加 allow= 或将 mode 改为 log" >&2
            exit 1
        fi
    else
        audit ALLOW "$_rule" "$_g_cmd" "$*" "${_paths:-}"
    fi
    return 0
}
