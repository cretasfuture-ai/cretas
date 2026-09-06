#!/usr/bin/env bash
# 打烊触发 —— 每天营业结束后跑一次日结那一屏, 推给店长。
#
# ## 为什么是 cron 而不是「打烊按钮」
#
# 打烊时间各店不同, 而「谁来点那个按钮」本身就是个没人负责的活。
# 固定时刻跑 = 不依赖任何人记得。⚠️ 时刻取 23:40: 晚市基本收摊, 又还没跨日 ——
# `date.today()` 必须落在**营业当天**, 跨过 0 点就成了推「昨天」。
#
# ## 幂等
#
# 防重键是 `(factory_id, YYYY-MM-DD, role)`, 存在
# `restaurant_value_notifications_log`(迁移 V20261101_13 把那一列加宽到 16)。
# 所以 cron 重试 / 手工补跑都只推一次。
#
# ⛔ 推送失败**不写**防重日志 —— 下次可重试。这是 value_notifier 原有的行为,
#    日结沿用, 没有另写一套。
#
# ## 租户名单
#
# `DAILY_CLOSE_FACTORIES` 在 `/www/wwwroot/cretas/.env.daily-close` 里配。
# ⛔ 脚本**不给默认租户** —— 名单空就 rc=2 告警, 而不是猜一个。
#    猜错的方向是「把别人家的经营数字推给另一家店长」。
set -uo pipefail

# ⚙️ 路径可覆盖(生产默认值不变, 测试用环境变量构造三态, 与 flywheel-promotion-staleness 同一裁定)。
PYDIR="${DAILY_CLOSE_PYDIR:-/www/wwwroot/cretas/code/backend/python}"
LOG="${DAILY_CLOSE_LOG:-/www/wwwroot/cretas/logs/daily-close-push.log}"
ALERTS="${DAILY_CLOSE_ALERTS:-/www/wwwroot/cretas/logs/daily-close-alerts.log}"
LEDGER="${DAILY_CLOSE_LEDGER:-/www/wwwroot/cretas/logs/daily-close-ledger.jsonl}"
ENV_PROD="${DAILY_CLOSE_ENV_PROD:-/www/wwwroot/cretas/.env.prod}"
ENV_EXTRA="${DAILY_CLOSE_ENV_EXTRA:-/www/wwwroot/cretas/.env.daily-close}"
# 2026-09-06 审计批 2 (docs/ops/cron-scripts-path-audit-2026-09-06.md): 钉 PATH; venv 不在 ⇒ rc=2
# (⛔ 不掉回系统 python 3.6.8); .env.prod 不在 ⇒ rc=2 (原来 2>/dev/null 吞掉, 到 python 报凭证缺才知道);
# .env.daily-close 是**可选**文件, 不在只记一句。
# shellcheck source=scripts/cron/cron-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh"
cron_pin_path
cron_require_cmds "$ALERTS" "DAILY CLOSE" systemctl

{
  echo "=== $(date '+%F %T') daily-close push ==="
  cd "$PYDIR" || cron_dead "$ALERTS" "DAILY CLOSE" "pydir-missing" "no $PYDIR"
  cron_activate_venv "$PYDIR/venv-current/bin/activate" "$ALERTS" "DAILY CLOSE"

  set -a
  cron_source_env_required "$ENV_PROD" "$ALERTS" "DAILY CLOSE"
  cron_source_env_optional "$ENV_EXTRA"
  set +a

  # ⚠️ `.env.prod` **不含** POSTGRES_DB —— 它只在 systemd unit 里。
  #    不补这一步会静默落到默认值(测试库), 于是「推了」但推的是测试库的数。
  # (systemctl 的 stderr 进 $LOG, ⛔ 不再 2>/dev/null)
  svc_pid=$(systemctl show -p MainPID --value cretas-python.service)
  if [ -n "${svc_pid:-}" ] && [ "$svc_pid" != "0" ] && [ -r "/proc/$svc_pid/environ" ]; then
    while IFS= read -r -d '' kv; do
      case "$kv" in POSTGRES_*|INTERNAL_API_SECRET=*) export "${kv?}" ;; esac
    done < "/proc/$svc_pid/environ"
  else
    echo "WARN: 拿不到活服务进程 environ (pid=${svc_pid:-none}) —— 库名可能落到默认值(测试库)"
  fi

  # ⛔ 先删上一次的产出。python 侧现在**总会**写它, 但两道都要有 ——
  #    只要有一条路径没写文件, 台账就会把**上一次**的计数配上**这一次**的 rc,
  #    而那种行看起来完全正常。2026-08-13 实测出现过一次:
  #    {"rc": 2, "factories": 1, "sections_computed": 1} —— rc 是这次的, 计数是上次的。
  rm -f /tmp/daily_close_push.json

  out=$(DAILY_CLOSE_OUT=/tmp/daily_close_push.json PYTHONIOENCODING=utf-8 \
        python -X utf8 -u -m smartbi.scripts.daily_close_push 2>&1)
  rc=$?
  echo "$out"
  echo "=== done (rc=$rc) ==="

  # rc: 0 = 推成功/已推过; 1 = 有租户失败; 2 = 仪器问题(没轮到租户/一段都没算出来)
  # ⛔ rc=2 单独喊 —— 「一个都没推」和「都推过了」计数上都是 notified=0,
  #    混在一起报的话, 静默失效看起来跟正常一模一样。
  if [ "$rc" -eq 2 ]; then
    echo "DAILY CLOSE INSTRUMENT DEAD $(date '+%F %T') — 没轮到租户或一段都没算出来, 见 $LOG" >> "$ALERTS"
  elif [ "$rc" -ne 0 ]; then
    echo "DAILY CLOSE PUSH FAILED $(date '+%F %T') — 有租户推送失败(下次会重试), 见 $LOG" >> "$ALERTS"
  fi

  # 台账: 每天一行。⛔ 只记计数不记正文 —— 台账不该把经营数字抄进日志文件。
  if [ -r /tmp/daily_close_push.json ]; then
    DAILY_CLOSE_RC="$rc" python -X utf8 -u -m smartbi.scripts.daily_close_ledger >> "$LEDGER"
  fi
} >> "$LOG" 2>&1
