#!/usr/bin/env bash
# 晋升表失效积压守望 —— 每天量一次「人审过的晋升还有几条真的在生效」。
#
# 2026-08-20 实测：`_load_promoted_routes` 里那句
#   「N 条晋升因路由规则指纹不符被跳过」
# 在 prod 上喊了 **755 次、12 天**，而逐层查完 **零消费方**
# （cron 0 命中 / 无告警文件 / API 只报 route_count=40 而生效是 0 /
#   web-admin Overview.vue 根本不渲染这个数）。
# ▎「喊在日志里」不构成消费方。这个跑批就是那个消费方。
#
# ⚠️ 它守的是**积压**，⛔ 不是缺陷 —— 那 40 条失效是有台账依据的设计
#    （见 restaurant_intent.plan_semantics_segment）。只报不改，⛔ 绝不自动复活。
#
# 🔴 三态（硬约束 4）: rc=0 无积压 / rc=1 有积压待人审 / rc=2 这次没量到。
#    rc=2 用**与 rc=1 不同的措辞**单独告警 —— ⛔ 两态会把「没量到」折叠进
#    「没问题」，一个连不上库的跑批会安静地天天绿。
#
# 形状抄 scripts/cron/restaurant-unfreeze-watch.sh（同一套三态 + 台账 +
# 从活服务进程取 environ），⛔ 不新造一套。
#
# ⚙️ 路径全部可覆盖：生产默认值不变（cron 零配置照跑），本地/验收用环境变量
#    覆盖来**主动构造三态**（第三态平时永远不会自然发生，不构造就不知道它到得了）：
#      FLYWHEEL_STALE_TABLE=ai_promoted_routes_does_not_exist  ⇒ rc=2
set -uo pipefail

PYDIR="${FLYWHEEL_STALE_PYDIR:-/www/wwwroot/cretas/code/backend/python}"
# ⚙️ venv 单独可覆盖（同 restaurant-flywheel-promotion-backlog-daily.sh 的裁定）:
#    验收时要把 PYDIR 指到一棵**没有 venv 的**副本树上跑三态。
VENV_ACTIVATE="${FLYWHEEL_STALE_VENV_ACTIVATE:-$PYDIR/venv-current/bin/activate}"
LOG="${FLYWHEEL_STALE_LOG:-/www/wwwroot/cretas/logs/flywheel-promotion-staleness.log}"
ALERTS="${FLYWHEEL_STALE_ALERTS:-/www/wwwroot/cretas/logs/flywheel-promotion-staleness-alerts.log}"
LEDGER="${FLYWHEEL_STALE_LEDGER:-/www/wwwroot/cretas/logs/flywheel-promotion-staleness-ledger.jsonl}"
ENV_PROD="${FLYWHEEL_STALE_ENV_PROD:-/www/wwwroot/cretas/.env.prod}"
# 2026-09-06 审计批 2: 钉 PATH; venv 不在 ⇒ rc=2 (⛔ 不掉回系统 python 3.6.8); .env.prod 不在 ⇒ rc=2。
# shellcheck source=scripts/cron/cron-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh"
cron_pin_path
cron_require_cmds "$ALERTS" "FLYWHEEL PROMOTION STALENESS" systemctl

{
  echo "=== $(date '+%F %T') flywheel promotion staleness watch ==="
  cd "$PYDIR" || cron_dead "$ALERTS" "FLYWHEEL PROMOTION STALENESS" "pydir-missing" "no $PYDIR"
  cron_activate_venv "$VENV_ACTIVATE" "$ALERTS" "FLYWHEEL PROMOTION STALENESS"

  set -a
  cron_source_env_required "$ENV_PROD" "$ALERTS" "FLYWHEEL PROMOTION STALENESS"
  set +a

  # ⚠️ `.env.prod` **不含 POSTGRES_DB** —— 只在 systemd unit 里。
  #    不补会落到默认值(测试库)，而那会让读数量在错的库上。
  # ⛔ 只 export 需要的前缀: eval 整份 environ 会冲掉 PATH，python 掉回系统解释器。
  svc_pid=$(systemctl show -p MainPID --value cretas-python.service)
  if [ -n "${svc_pid:-}" ] && [ "$svc_pid" != "0" ] && [ -r "/proc/$svc_pid/environ" ]; then
    while IFS= read -r -d '' kv; do
      case "$kv" in
        POSTGRES_*|FOOD_KB_POSTGRES_*) export "${kv?}" ;;
      esac
    done < "/proc/$svc_pid/environ"
  else
    echo "WARN: 拿不到活服务进程 environ (pid=${svc_pid:-none}) —— 库名可能落到测试库"
  fi
  echo "POSTGRES_DB=${POSTGRES_DB:-<unset>}"

  export PYTHONPATH="$PYDIR:$PYDIR/smartbi"
  export FLYWHEEL_STALE_LEDGER="$LEDGER"

  # ⛔ 不写成 `wc -l < "$LEDGER" 2>/dev/null || echo 0` —— 台账**第一次**跑时
  #    文件还不存在，那个写法会把 bash 的重定向错误刷进告警日志，而这条路径
  #    正是判「今天有没有跑完」的那条，不该有噪声。
  _ledger_lines() { if [ -f "$LEDGER" ]; then wc -l < "$LEDGER"; else echo 0; fi; }

  before=$(_ledger_lines)

  PYTHONIOENCODING=utf-8 python -X utf8 -u -m smartbi.scripts.flywheel_promotion_staleness
  rc=$?
  echo "=== done (rc=$rc) ==="

  after=$(_ledger_lines)

  # 🔴 硬约束 9：验证「今天没喊」必须同时有一个**设计上必然会变**的读数。
  #    台账每次必追加一行 —— 它没涨就说明根本没跑完，那么「没告警」没有意义。
  #    ⛔ 「文件不存在」和「决定不喊」在 `[ -s ... ]` 里长得一模一样。
  if [ "$after" -le "$before" ]; then
    echo "FLYWHEEL PROMOTION STALENESS INSTRUMENT DEAD $(date '+%F %T') — 台账没有新增行($before -> $after)，本次根本没跑完，见 $LOG" >> "$ALERTS"
  elif [ "$rc" -eq 2 ]; then
    # ⛔ 与 rc=1 **不同的措辞**: 「没量到」不是「没问题」，也不是「有问题」。
    echo "FLYWHEEL PROMOTION STALENESS INSTRUMENT DEAD $(date '+%F %T') — 表不存在/连不上库/指纹判定的对照没过，本次读数作废，见 $LOG" >> "$ALERTS"
  elif [ "$rc" -eq 1 ]; then
    # ⛔ 不从人看的正文里 grep 数字（文案一改判据就静默失效）——
    #    从台账最后一行的结构化字段取。
    stale=$(tail -1 "$LEDGER" | grep -oP '"stale_count":\s*\K[0-9]+')
    echo "FLYWHEEL PROMOTION BACKLOG $(date '+%F %T') — ${stale:-?} 条人审过的晋升当前不生效(回放回落 planner，慢且费 token)。⛔ 不要靠改判定复活它们，人按台账逐条复审后重新盖章，逐条「生效/已失效」看: python scripts/restaurant-intent-promote.py --list-routes (⚠️ 不是 --list，那是候选队列)。见 $LOG" >> "$ALERTS"
  elif [ "$rc" -ne 0 ]; then
    echo "FLYWHEEL PROMOTION STALENESS UNEXPECTED RC=$rc $(date '+%F %T') — 三态之外的退出码，当成没量到处理，见 $LOG" >> "$ALERTS"
  fi

  exit "$rc"
} >> "$LOG" 2>&1
