#!/usr/bin/env bash
# 挂账项状态守望 —— 每条「⛔ 本轮不做」的解冻条件，每天量一次。
#
# 2026-08-19 盘点：设计卡里 6 条解冻条件, **6 条都没有触发通道** ——
# 判据都写好了, 但没有一个会自己跑。按「解冻条件必须有触发通道, 否则等于不存在」,
# 那 6 条当时**等于不存在**。这个跑批就是那个通道。
#
# 形状抄 margin-parity-daily.sh（同一天量一次, 比登记状态, 三态 rc）。
# ⛔ 不阻断任何东西 —— 产出是台账 + 告警行, 给人看。
set -uo pipefail

# ⚙️ 路径可覆盖(生产默认值不变, 测试用环境变量构造三态)。
PYDIR="${UNFREEZE_PYDIR:-/www/wwwroot/cretas/code/backend/python}"
LOG="${UNFREEZE_LOG:-/www/wwwroot/cretas/logs/unfreeze-watch.log}"
ALERTS="${UNFREEZE_ALERTS:-/www/wwwroot/cretas/logs/unfreeze-watch-alerts.log}"
LEDGER="${UNFREEZE_LEDGER:-/www/wwwroot/cretas/logs/unfreeze-watch-ledger.jsonl}"
ENV_PROD="${UNFREEZE_ENV_PROD:-/www/wwwroot/cretas/.env.prod}"
# 2026-09-06 审计批 2: 钉 PATH; venv 不在 ⇒ rc=2 (⛔ 不掉回系统 python 3.6.8); .env.prod 不在 ⇒ rc=2。
# shellcheck source=scripts/cron/cron-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh"
cron_pin_path
cron_require_cmds "$ALERTS" "UNFREEZE WATCH" systemctl

{
  echo "=== $(date '+%F %T') unfreeze watch ==="
  cd "$PYDIR" || cron_dead "$ALERTS" "UNFREEZE WATCH" "pydir-missing" "no $PYDIR"
  cron_activate_venv "$PYDIR/venv-current/bin/activate" "$ALERTS" "UNFREEZE WATCH"

  set -a
  cron_source_env_required "$ENV_PROD" "$ALERTS" "UNFREEZE WATCH"
  set +a

  # ⚠️ `.env.prod` **不含 POSTGRES_DB** —— 只在 systemd unit 里。
  #    不补会落到默认值(测试库), 而那会让每一条判据都量在错的库上。
  # ⛔ 只 export 需要的前缀: eval 整份 environ 会冲掉 PATH, python 掉回系统解释器。
  svc_pid=$(systemctl show -p MainPID --value cretas-python.service)
  if [ -n "${svc_pid:-}" ] && [ "$svc_pid" != "0" ] && [ -r "/proc/$svc_pid/environ" ]; then
    while IFS= read -r -d '' kv; do
      case "$kv" in
        POSTGRES_*|FOOD_KB_POSTGRES_*|DASHSCOPE_*|LLM_*|AISTORE_*|DEEPSEEK_*)
          export "${kv?}" ;;
      esac
    done < "/proc/$svc_pid/environ"
  else
    echo "WARN: 拿不到活服务进程 environ (pid=${svc_pid:-none}) —— 库名可能落到测试库"
  fi
  echo "POSTGRES_DB=${POSTGRES_DB:-<unset>}"

  export PYTHONPATH="$PYDIR:$PYDIR/smartbi"
  export UNFREEZE_LEDGER="$LEDGER"

  # 与 flywheel-promotion-staleness 同形: 台账第一次跑时文件还不存在, ⛔ 不用 `2>/dev/null || echo 0`
  # 去吞重定向错误 —— 那会把别的读错(权限/路径)也吞成 0。
  _ledger_lines() { if [ -f "$LEDGER" ]; then wc -l < "$LEDGER"; else echo 0; fi; }
  before=$(_ledger_lines)

  PYTHONIOENCODING=utf-8 python -X utf8 -u -m smartbi.scripts.unfreeze_watch
  rc=$?
  echo "=== done (rc=$rc) ==="

  after=$(_ledger_lines)

  # 🔴 硬约束 9：验证「今天没喊」时，必须同时有一个「一定会发生」的读数。
  #    台账每次必追加一行 —— 它没涨就说明根本没跑完, 那么「没告警」没有意义。
  #    ⛔ 「文件不存在」和「决定不喊」在 `[ -s ... ]` 里长得一模一样。
  if [ "$after" -le "$before" ]; then
    echo "UNFREEZE WATCH INSTRUMENT DEAD $(date '+%F %T') — 台账没有新增行($before -> $after), 本次根本没跑完, 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -eq 2 ]; then
    # ⛔ 与 rc=1 **不同的措辞**: 「没量到」不是「没问题」, 也不是「有问题」。
    # ⚠️ rc=2 只留给**仪器坏**(阳性对照没过/判据抛异常)。
    #    「这轮没样本」不进 rc=2 —— 它逐天记台账并自己累计, 连续 N 天才升级成 rc=1。
    #    第一次真跑就撞上了: store-absent 那条要「模型提名了那家店」, 而模型
    #    两轮全给 store=null。让它天天喊 = 做一道会被关掉的闸(形态 E)。
    echo "UNFREEZE WATCH INSTRUMENT DEAD $(date '+%F %T') — 有挂账项的阳性对照没过, 本次那几条读数作废, 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -ne 0 ]; then
    echo "UNFREEZE WATCH FLIPPED $(date '+%F %T') — 有挂账项状态翻面, 对应设计卡该重新看了, 见 $LOG" >> "$ALERTS"
  fi

  exit "$rc"
} >> "$LOG" 2>&1
