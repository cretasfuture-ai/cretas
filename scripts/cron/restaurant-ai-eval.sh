#!/usr/bin/env bash
# 每日 AI 回归评测 —— 现只跑 F006 的工厂读电池与工厂写路径电池; 失败追加 alerts 日志。
#
# 2026-10-29 MOCK_REST AI writes stopped, owner-approved: 原先的餐饮电池
# (smartbi.scripts.restaurant_ai_eval, R6→R22 52 案例, 写死 MOCK_REST) 与餐饮飞轮日报
# (scripts/restaurant-intent-promote.py --list/--misses, --factory 默认 MOCK_REST) 已停并删除 ——
# 餐饮线已废弃, MOCK_REST 撤回训练授权、AI 副本已清理。原文见 git 历史。
# 文件名保留不改: 47 上 root crontab `40 4 * * *` 直接按这个路径调用。
#
# 本文件由 deploy-smartbi-python 同步到服务器；运行时通过 venv-current
# 原子链接切换，避免定时任务与主服务使用不同 Python。
set -uo pipefail

PYDIR=/www/wwwroot/cretas/code/backend/python
LOG=/www/wwwroot/cretas/logs/restaurant-ai-eval.log
ALERTS=/www/wwwroot/cretas/logs/restaurant-ai-eval-alerts.log

{
  echo "=== $(date '+%F %T') AI eval (餐饮电池 / 餐饮飞轮日报已停: 2026-10-29 MOCK_REST AI writes stopped, owner-approved) ==="
  cd "$PYDIR"
  # shellcheck disable=SC1091
  source "$PYDIR/venv-current/bin/activate"
  # 评测凭证只从 .env.prod 注入, 绝不落进仓库。
  set -a
  # shellcheck disable=SC1091
  source /www/wwwroot/cretas/.env.prod 2>/dev/null
  set +a
  # 工厂评测凭证 FACTORY_EVAL_USERNAME/PASSWORD (F006 账号) 同样只在 .env.prod, 缺了电池退 2 = 没量到
  rc=0

  # ── 工厂读电池 (2026-07-24 验收轮建立) ──
  echo "=== $(date '+%F %T') factory READ eval ==="
  PYTHONIOENCODING=utf-8 python -X utf8 -m smartbi.scripts.factory_ai_eval \
    --base https://admin.cretaceousfuture.com
  frc=$?
  echo "=== factory read done (rc=$frc) ==="
  if [ "$frc" -ne 0 ]; then
    # rc=1 断言失败 / rc=2 没量到 (登录、凭证) —— 措辞不同, 别把「没量到」读成「模型退化」
    echo "FACTORY READ EVAL $([ "$frc" -eq 1 ] && echo FAILED || echo NOT-MEASURED) rc=$frc $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
    rc=$((rc || frc))
  fi

  # ── 工厂写路径电池 (读写分块契约: 意图命中/READ拦截/TCC/demo闸) ──
  echo "=== $(date '+%F %T') factory WRITE eval ==="
  PYTHONIOENCODING=utf-8 python -X utf8 -m smartbi.scripts.factory_write_eval \
    --base https://admin.cretaceousfuture.com
  wrc=$?
  echo "=== factory write done (rc=$wrc) ==="
  if [ "$wrc" -ne 0 ]; then
    echo "FACTORY WRITE EVAL $([ "$wrc" -eq 1 ] && echo FAILED || echo NOT-MEASURED) rc=$wrc $(date '+%F %T') — 见 $LOG" >> "$ALERTS"
    rc=$((rc || wrc))
  fi

  exit "$rc"
} >> "$LOG" 2>&1
