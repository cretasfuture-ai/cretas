#!/usr/bin/env bash
# scripts/cron/cron-common.sh —— 定时脚本的三条公共纪律, **source 进来, 不直接执行**。
#
#   . "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh"
#   cron_pin_path                                   # ① PATH 在脚本里钉死
#   cron_require_cmds "$ALERTS" "XXX" ss psql       # ② 缺命令 ⇒ 告警行 + rc=2 + reason=
#   cron_activate_venv "$ACT" "$ALERTS" "XXX"       # ③ venv 不在 ⇒ rc=2, ⛔ 不掉回系统 python
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (2026-09-06 审计, docs/ops/cron-scripts-path-audit-2026-09-06.md)
# ═══════════════════════════════════════════════════════════════════════════
# ① root 的 crontab **没有 PATH= 行** ⇒ cronie 用 `/usr/bin:/bin`, 而 `ss` 只在
#    /usr/sbin。soft-delete-orphans-watch.sh 因此**连续 15 天 rc=2**, 把「找不到 ss」
#    读成「后端没在监听」。PATH 必须在**脚本里**钉, ⛔ 不依赖 crontab(那是别人的文件)。
# ② 找不到命令时 `2>/dev/null` 会把 `command not found` 整句吞掉, 于是变量为空、
#    走到某个看起来合理的分支。要让它**说出缺的是哪个**, 并且用 rc=2(没量到)退出,
#    ⛔ 不是 rc=1(有问题), 更不是 rc=0。
# ③ `source venv38/bin/activate` 在 `set -u`(无 -e)下失败**不中止** ⇒ 掉回系统
#    python 3.6.8 ⇒ `from __future__ import annotations` SyntaxError。
#    llm-router-billing-canary.sh 因此从 2026-08-03 起每周崩一次, 而 crontab 的
#    `|| echo FLAGGED` 把崩溃写成了「账单告警」—— 5 周没人分得清。
#
# 这个文件放在 scripts/cron/ 而不是 scripts/lib/: deploy-smartbi-python.sh 3d 只把
# `scripts/cron/*.sh` 整目录 rsync 到服务器, 放别处就是「机制在、没接上」(形态 B)。
#
# reason= 是分派键(与 soft-delete-orphans-watch.sh / refresh-testenv-backend.sh 同一约定):
# 将来告警通道接上时按它分派, ⛔ 不从中文散文里猜。

# 生产 PATH。测试用 CRON_BIN_PATH 指到一个自己控制的目录来构造「缺命令」——
# PATH 里加不回来的东西才是真的没有。
cron_pin_path() {
    export PATH="${CRON_BIN_PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
}

# rc=2 的唯一出口。$1 告警文件, $2 措辞前缀(各脚本自己的, 如 XXX / LLM CANARY),
# $3 reason 键, $4.. 给人看的明细行。
cron_dead() {
    local alerts=$1 prefix=$2 reason=$3
    shift 3
    local stamp line
    stamp=$(date '+%F %T')
    # 告警文件的目录不在, 就让下面的 >> 自己报错到 stderr(cron 日志能看到), ⛔ 不吞。
    {
        echo "$prefix INSTRUMENT DEAD $stamp reason=$reason — 本次读数作废, ⛔ 不要读成「没问题」"
        for line in "$@"; do [ -n "$line" ] && echo "  $line"; done
    } >> "$alerts"
    echo "$prefix INSTRUMENT DEAD reason=$reason" >&2
    exit 2
}

# 逐个 command -v。缺任何一个 ⇒ cron_dead reason=cmd-not-found:<a,b>。
# ⚠️ 只查「在不在」, 不查「能不能跑」—— 后者由脚本自己的调用去证明。
cron_require_cmds() {
    local alerts=$1 prefix=$2
    shift 2
    local missing="" c
    for c in "$@"; do
        command -v "$c" >/dev/null || missing="$missing,$c"
    done
    [ -z "$missing" ] || cron_dead "$alerts" "$prefix" "cmd-not-found:${missing#,}" \
        "PATH=$PATH" \
        "cron 默认 PATH 只有 /usr/bin:/bin; 本脚本已自己钉 PATH, 还报这个说明机器上真的没装"
}

# 从**活服务进程**的 environ 取变量 (形态 A′: 问活进程要真相)。
# ⛔ 不用 `systemctl show <svc> -p Environment`: 它只列 unit 文件里 inline 的 `Environment=`,
#    **看不见 `EnvironmentFile=` 里的东西**。47 上 2026-07-20 起 POSTGRES_PASSWORD 与全部
#    LLM_*_API_KEY 都搬进了 EnvironmentFile=.env.prod, inline 只剩 5 个非密钥变量 ⇒ 三个
#    llm-router 周报从那天起一直读到**空密钥**: canary 每行 NO-KEY(81 entries · 0 billing-safe),
#    heatmap/rerank psql `password authentication failed` —— 7 周, 而它们自己都没喊过「没量到」。
# 只 export 键名匹配 $4(ERE)的那几个: ⛔ 不 eval 整份 environ (会冲掉 PATH; 密码里的 / 引号会被
# shell 解释, 本仓栽过)。
cron_export_service_env() {  # $1 service, $2 alerts, $3 prefix, $4 键名 ERE (如 '^(LLM_|POSTGRES_)')
    local svc=$1 alerts=$2 prefix=$3 re=$4 pid environ kv n=0
    pid=$(systemctl show -p MainPID --value "$svc" 2>&1) || pid=""
    case "$pid" in ''|0|*[!0-9]*)
        cron_dead "$alerts" "$prefix" "service-not-running:$svc" \
            "systemctl show -p MainPID --value $svc 给了 '${pid:-}' —— 服务没在跑, 拿不到它的 environ" ;;
    esac
    environ="${CRON_PROC_ROOT:-/proc}/$pid/environ"
    [ -r "$environ" ] || cron_dead "$alerts" "$prefix" "service-environ-unreadable:$svc" \
        "$environ (pid=$pid) 读不了 —— 非 root? /proc 没挂?"
    while IFS= read -r -d '' kv; do
        case "$kv" in *=*) ;; *) continue ;; esac
        if printf '%s\n' "${kv%%=*}" | grep -Eq "$re"; then
            export "${kv?}"; n=$((n + 1))
        fi
    done < "$environ"
    echo "INFO service-env exported=$n match=$re pid=$pid"
}

# venv 不在就停, ⛔ 不许静默掉回系统 python(3.6.8, 连 from __future__ annotations 都不认)。
cron_activate_venv() {
    local activate=$1 alerts=$2 prefix=$3
    [ -r "$activate" ] || cron_dead "$alerts" "$prefix" "venv-missing" \
        "$activate 不存在或不可读" \
        "⛔ 不掉回系统 python —— 那会以 SyntaxError 崩, 看起来像业务失败"
    # shellcheck disable=SC1090
    . "$activate" || cron_dead "$alerts" "$prefix" "venv-activate-failed" "$activate"
    command -v python >/dev/null || cron_dead "$alerts" "$prefix" "python-missing-after-activate" \
        "source $activate 之后 PATH=$PATH 下仍找不到 python"
}
