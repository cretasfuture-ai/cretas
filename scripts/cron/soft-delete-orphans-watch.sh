#!/usr/bin/env bash
#
# 每天扫一次软删孤儿, 有就写告警。装在 prod 服务器上, 由 crontab 调。
#
# 为什么要定时扫而不是「删之前记得跑」:
#   2026-08-21 实测 —— 我软删 26 个账号前查了「有没有在途任务」, **没查「有没有别的表
#   引用它」**, 结果客户列表整个 500。而这个仓库**没有统一的删数据入口**(每次都是临时
#   SQL), 挂钩无处可挂。
#   ▎靠记得不构成约束力。⇒ 让它自己会响。
#
# SQL 由本地 `scripts/check-soft-delete-orphans.sh --sql-only` 生成后推上来
# (服务器上没有 Java 源码, 扫不出关联)。**实体改了要重新推一次**, 见下面的过期检查。
#
# 退出码是三态(⛔ 不是两态):
#   0 没孤儿 / 1 有孤儿 / 2 **没量到**(SQL 不在/连不上库/阳性对照失败)
# rc=2 用**与 rc=1 不同的措辞**告警 —— 把「没量到」从「没问题」里拆出来。
set -uo pipefail

SQL_FILE=/www/wwwroot/cretas/scripts/soft-delete-orphans.sql
ALERTS=/www/wwwroot/cretas/logs/soft-delete-orphans.alert
LEDGER=/www/wwwroot/cretas/logs/soft-delete-orphans.ledger
STAMP="$(date '+%F %T')"

# ⚠️ 每次都追加一行台账 —— 这是「必发生」的那个读数。
# 验证「没有告警」时要同时看它: 告警文件没涨可能是「没问题」, 也可能是
# **脚本崩在写告警之前**。两者在 [ -s file ] 里长得一模一样。
note() { echo "$STAMP $*" >> "$LEDGER"; }

if [ ! -s "$SQL_FILE" ]; then
  note "INSTRUMENT_DEAD sql-missing"
  echo "$STAMP SOFT-DELETE-ORPHANS 仪器故障 — SQL 文件不存在或为空: $SQL_FILE" >> "$ALERTS"
  exit 2
fi

# SQL 比后端制品还老 ⇒ 实体可能改过而 SQL 没重推 ⇒ 扫描面过期
JAR=/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar
if [ -f "$JAR" ] && [ "$JAR" -nt "$SQL_FILE" ]; then
  note "STALE sql-older-than-jar"
  echo "$STAMP SOFT-DELETE-ORPHANS 扫描面可能过期 — 后端制品比 SQL 新, 实体改过就该重推:" >> "$ALERTS"
  echo "        本地跑 ./scripts/check-soft-delete-orphans.sh --sql-only 重新生成并 scp" >> "$ALERTS"
  # ⛔ 不 exit —— 过期的扫描仍然比不扫强, 继续跑, 只是标记出来。
fi

PID=$(ss -lntp 2>/dev/null | grep -E ":(10010|10020)" | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -z "$PID" ]; then
  note "INSTRUMENT_DEAD no-backend-pid"
  echo "$STAMP SOFT-DELETE-ORPHANS 仪器故障 — 找不到后端进程, 取不到 DB 凭证" >> "$ALERTS"
  exit 2
fi
# ⛔ 只 export DB_ 前缀: eval 整份 environ 会冲掉 PATH
eval "$(tr '\0' '\n' < "/proc/$PID/environ" | grep -E '^DB_' | sed 's/^/export /')"
export PGPASSWORD="${DB_PASSWORD:-}"

OUT=$(psql -h localhost -U "${DB_USERNAME:-cretas_user}" -d "${DB_NAME:-}" -f "$SQL_FILE" 2>&1)
RC=$?
if [ "$RC" -ne 0 ]; then
  note "INSTRUMENT_DEAD psql-rc=$RC"
  echo "$STAMP SOFT-DELETE-ORPHANS 仪器故障 — 查询失败, 本次读数作废:" >> "$ALERTS"
  echo "$OUT" | head -5 >> "$ALERTS"
  exit 2
fi

if echo "$OUT" | grep -qE '^\(0 rows\)'; then
  note "OK no-orphans"
  exit 0
fi

note "ORPHANS_FOUND"
{
  echo "$STAMP SOFT-DELETE-ORPHANS 发现孤儿 — 下面每一条现在都可能让对应页面 500:"
  echo "$OUT" | sed -n '3,$p' | grep -v '^(' | sed 's/^/        /'
  echo "        修法看情况, ⛔ 不要一刀切:"
  echo "          · 子表指向父表 → 多半是软删父行时没级联, 应补级联"
  echo "          · 审计字段     → 加 @NotFound(IGNORE)"
  echo "          · 测试残留     → 清数据即可"
} >> "$ALERTS"
exit 1
