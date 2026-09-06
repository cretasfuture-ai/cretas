#!/usr/bin/env bash
# @Async 失败台账积压告警。
#
# 背景: @Async void 方法抛出的异常原先只被 Spring 默认处理器打一条 ERROR 就丢
#   —— 用户侧无感 (HTTP 仍然 200)、无记录、无补偿。prod error 流
#   2026-06-19 ~ 08-19 共 211 次逃逸, 三个监听器各自静默失败了两个月。
#   出口是 async_task_failures 这张表; 本脚本负责让它**被人看见**。
#
# 🔴 三态(硬约束 4): rc=0 没问题 / rc=1 有积压 / rc=2 **这次没量到**。
#    ⛔ 两态会把「没量到」折叠进「没问题」—— 一个连不上库的跑批会安静地
#    天天绿, 而它一个样本都没看过。
#
# 🔴 「至今 0 条」单独说, ⛔ 不与「无积压」共用一个分支:
#    台账一条都没有, 要么是真没失败过, 要么是**出口根本没接上**
#    (AsyncConfig 没返回处理器 / 登记表失效 / 表没建)。考虑到接这套东西之前
#    两个月里有 211 次逃逸, 部署后长期 total=0 更可能是后者。
#    两件事在告警文件里必须长得不一样。
#
# ⚙️ 路径/库名全部可覆盖: 生产默认值不变(cron 零配置照跑), 本地用环境变量
#    覆盖来**构造 rc=2** —— 那一态平时永远不会自然发生, 不构造就不知道
#    它到不到得了。⛔ 不要为了好测就改生产默认值。
set -uo pipefail

ALERTS="${ASYNC_FAILURE_ALERTS:-/www/wwwroot/cretas/logs/async-failure-backlog-alerts.log}"
# 端口反查 pid —— ⛔ 不要 pgrep -f <关键词>, 那会抓到你自己正在跑的探针。
APP_PORTS="${ASYNC_FAILURE_APP_PORTS:-:10010|:10020}"
PSQL_BIN="${ASYNC_FAILURE_PSQL:-psql}"

# 🔴 2026-09-06 审计: 与 soft-delete-orphans-watch.sh 同款隐患 —— root crontab 无 PATH=,
#    `ss` 只在 /usr/sbin, 而原来 `ss -lntp 2>/dev/null` 把 `command not found` 吞掉,
#    PID 为空 ⇒ 静默落到默认库名/空密码 ⇒ psql 失败 ⇒ 被记成「台账读不到」。
#    「找不到 ss」和「台账读不到」是两种修法, 不能长成同一行。
# ⛔ source 失败必须停: 无 -e 下 cron_* 逐个 command not found 后照跑, 空密码去连库 ⇒ 记成「台账读不到」。
# shellcheck source=scripts/cron/cron-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh" || {
    echo "XXX INSTRUMENT DEAD $(date '+%F %T') reason=cron-common-missing — scripts/cron/cron-common.sh 不在本脚本旁边 (3d 整目录同步应一起到), 本次读数作废" >> "$ALERTS"
    echo "XXX INSTRUMENT DEAD reason=cron-common-missing" >&2; exit 2; }
cron_pin_path
cron_require_cmds "$ALERTS" "XXX" ss "$PSQL_BIN"

# 从活着的 JVM 拿库连接 —— 配置文件里的默认值指向的是别的库。
# ⚠️ 只 export 需要的那几个前缀: eval 整份 environ 会冲掉 PATH。
# ⛔ 不再 2>/dev/null: ss 的 stderr 正是「为什么取不到 PID」。
SS_ERR=$(mktemp)
LISTEN=$(ss -lntp 2> "$SS_ERR")
SS_RC=$?
# ⚠️ 不用 grep -P(\K): BSD grep 没有 -P, 自建 CI runner 是 macOS(time-phrase 那次实测炸过)。
PID=$(printf '%s\n' "$LISTEN" | grep -E "$APP_PORTS" | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
if [ -z "$PID" ] && [ -z "${ASYNC_FAILURE_DB_PASS:-}" ]; then
    # 拿不到活进程, 又没有显式给密码 ⇒ 下面必然是空密码去连 —— 那不是「台账读不到」,
    # 是「没找到后端」。分开喊, rc=2 (没量到)。
    SS_MSG=$(head -2 "$SS_ERR" | tr '\n' ' '); rm -f "$SS_ERR"
    cron_dead "$ALERTS" "XXX" "no-listener-on-ports" \
        "ss rc=$SS_RC, 端口 $APP_PORTS 上没有 pid= (后端没在监听, 或 ss 看不到 pid —— 非 root?)" \
        "$SS_MSG"
fi
rm -f "$SS_ERR"
if [ -n "$PID" ] && [ -r "/proc/$PID/environ" ]; then
    eval "$(tr '\0' '\n' < "/proc/$PID/environ" \
            | grep -E '^FOOD_KB_POSTGRES_' | sed 's/^/export /')"
fi

DB_HOST="${ASYNC_FAILURE_DB_HOST:-${FOOD_KB_POSTGRES_HOST:-127.0.0.1}}"
DB_USER="${ASYNC_FAILURE_DB_USER:-${FOOD_KB_POSTGRES_USER:-cretas_user}}"
DB_NAME="${ASYNC_FAILURE_DB_NAME:-${FOOD_KB_POSTGRES_DB:-cretas_prod_db}}"
DB_PASS="${ASYNC_FAILURE_DB_PASS:-${FOOD_KB_POSTGRES_PASSWORD:-}}"

# 一次查两个数: 未处理条数 + 总条数。总数是用来分辨「没失败」和「没接上」的。
# ⚠️ 表名带 schema 限定 —— 本库有 7 个 schema, 不限定会读到别的 schema 的同名表。
SQL="SELECT count(*) FILTER (WHERE status = 'OPEN') AS open_rows, count(*) AS total_rows
     FROM public.async_task_failures;"

OUT=$(PGPASSWORD="$DB_PASS" "$PSQL_BIN" -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" \
      -At -F '|' -c "$SQL" 2>&1)
rc=$?

if [ "$rc" -ne 0 ]; then
    echo "XXX INSTRUMENT DEAD $(date '+%F %T') — 台账读不到, 本次读数作废; 「没有告警」这次不代表「没有失败」" >> "$ALERTS"
    echo "  $OUT" >> "$ALERTS"
    exit 2
fi

open_rows="${OUT%%|*}"
total_rows="${OUT##*|}"

# ⚠️ 解析不出数字也是「没量到」, ⛔ 不是 0。
# 用整体锚定的纯数字正则: OUT 合并了 stderr, 任何诊断文本都可能让 -z 判空失效,
# 而一个内嵌换行的「非空但非数字」的值会让后面的 -gt 报错并被 if 读成 false,
# 一路滑到 exit 0 (假绿)。
if ! [[ "$open_rows" =~ ^[0-9]+$ ]] || ! [[ "$total_rows" =~ ^[0-9]+$ ]]; then
    echo "XXX INSTRUMENT DEAD $(date '+%F %T') — 读数解析失败: $OUT" >> "$ALERTS"
    exit 2
fi

# 🔴 与「无积压」刻意不同的措辞。接这套东西之前的两个月里有 211 次逃逸,
#    所以部署之后长期一条都没有, 更可能是出口没接上, 而不是真的不再失败了。
if [ "$total_rows" -eq 0 ]; then
    echo "--- 台账至今 0 条 $(date '+%F %T') — 要么真没失败过, 要么出口没接上 (查 AsyncConfig.getAsyncUncaughtExceptionHandler / AsyncFailureEnrollment)" >> "$ALERTS"
    exit 0
fi

if [ "$open_rows" -gt 0 ]; then
    echo "XXX BACKLOG $(date '+%F %T') — $open_rows 条异步失败没人处理 (台账共 $total_rows 条)。这些失败在用户侧无感, 接口当时返回了 200。" >> "$ALERTS"
    DETAIL=$(PGPASSWORD="$DB_PASS" "$PSQL_BIN" -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -At -F '|' \
        -c "SELECT method_signature, factory_id, business_key, exception_class, occurrence_count, remediation_hint
            FROM public.async_task_failures WHERE status = 'OPEN'
            ORDER BY last_seen_at DESC LIMIT 20;" 2>&1)
    # 明细取不到不推翻「有积压」这个已经成立的结论, 但要说出来。
    if [ $? -ne 0 ]; then
        echo "  (明细查询失败, 只报总数)" >> "$ALERTS"
    else
        echo "$DETAIL" | sed 's/^/  /' >> "$ALERTS"
    fi
    exit 1
fi

exit 0
