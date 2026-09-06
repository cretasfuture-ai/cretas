#!/usr/bin/env bash
# probe-llm-registry.sh — 每日全量探针, 把 llm_router._SAFE_MODELS 与现实对账。
#
# WHY: llm_router 的三层拆分(事实表 _SAFE_MODELS / 资格层 _SLOT_POOLS / 排序
# _build_chain)只有在"注册表跟得上现实"时才成立 —— 而发现"现实漂移了"的唯一
# 机制是 backend/python/scripts/probe_llm_registry.py。2026-08-09 之前这套机制
# 只是一个可以手动跑的脚本, 没有 cron 包装、没有 crontab 条目、没有告警通道,
# 相当于"这个分支存在的理由"从没真正跑起来过。本文件补上这三样, 抄
# restaurant-ai-eval.sh 的形状(venv-current 原子链接、日志、alerts 文件)。
#
# 2026-08-10 补记: 本文件首次真跑就在生产凭证下抓到 3 条 08-09 当天还 OK 的
# 条目 24 小时内变成 403 —— 机制第一次跑就抓到了真实漂移, 见
# llm_router.py `_SAFE_MODELS` 后面的 "2026-08-10 探针复审剔除" 段落。
#
# 本文件由 deploy-smartbi-python.sh 的 3d 步同步到服务器; 运行时通过
# venv-current 原子链接切换, 避免定时任务与主服务使用不同 Python(与
# restaurant-ai-eval.sh / refresh-demo-rest.sh 同一约定)。
#
# 退出码语义(见 probe_llm_registry.main() 的实现与注释):
#   0 = 无「注册表说活、实测不可用」的条目(已过期的条目算「已过期, 待清理」,
#       不算 dead, 不翻转退出码 —— 否则每次批量到期后会连续多天必炸)。
#   1 = 有 dead 条目, 需要人核对控制台余量后更新 _SAFE_MODELS。
#
# Install (on server 47, as root):
#   crontab -e
# 建议在餐饮日报评测(见 restaurant-ai-eval.sh, 无固定 crontab 时间戳可查, 按
# 惯例排在业务低峰)之外找一个不冲突的时段, 例如凌晨:
#   17 3 * * *  /www/wwwroot/cretas/code/scripts/cron/probe-llm-registry.sh
#
set -uo pipefail

# ⚙️ 路径可覆盖(生产默认值不变, 测试用环境变量构造三态)。
PYDIR="${PROBE_LLM_PYDIR:-/www/wwwroot/cretas/code/backend/python}"
ENV_FILE="${PROBE_LLM_ENV_FILE:-/www/wwwroot/cretas/.env.prod}"
LOG="${PROBE_LLM_LOG:-/www/wwwroot/cretas/logs/probe-llm-registry.log}"
ALERTS="${PROBE_LLM_ALERTS:-/www/wwwroot/cretas/logs/probe-llm-registry-alerts.log}"
PFX="PROBE LLM REGISTRY"

# 2026-09-06 审计批 3 (docs/ops/cron-scripts-path-audit-2026-09-06.md 表 #7): 两态 → 三态。
# 原来 `rc!=0 就是真实漂移` —— 但 python 未捕获异常 (import 崩 / 连不上 / 密钥没配) 的默认 rc
# 也是 1, 会被写成「FOUND DEAD ENTRIES」; 而 canary 首跑的 KeyError 就是这么变成假账单告警的。
# 判据: python 只在跑到判定那一步才打 `[probe] <date> 共探 N 个` 抬头 —— 抬头没打就是崩在
# 判定之前 (rc=2), 共探 0 个也是没量到 (rc=2), 只有「抬头在 + rc=1」才是真的 dead 条目。
# shellcheck source=scripts/cron/cron-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh" || {
    echo "$PFX INSTRUMENT DEAD $(date '+%F %T') reason=cron-common-missing — scripts/cron/cron-common.sh 不在本脚本旁边 (3d 整目录同步应一起到), 本次读数作废" >> "$ALERTS"
    echo "$PFX INSTRUMENT DEAD reason=cron-common-missing" >&2; exit 2; }
cron_pin_path

mkdir -p "$(dirname "$LOG")"

{
  echo "=== $(date '+%F %T') probe_llm_registry ==="
  cd "$PYDIR" || cron_dead "$ALERTS" "$PFX" "pydir-missing" "no $PYDIR"
  cron_activate_venv "$PYDIR/venv-current/bin/activate" "$ALERTS" "$PFX"

  # 凭证只从 .env.prod 注入, 绝不落进仓库(与 refresh-demo-rest.sh 同一规范)。
  # 文件不在 ⇒ rc=2 (原来 exit 1, 与「有 dead 条目」同码)。
  [ -r "$ENV_FILE" ] || cron_dead "$ALERTS" "$PFX" "env-file-missing:$(basename "$ENV_FILE")" "$ENV_FILE 不存在或不可读"
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a

  out=$(PYTHONIOENCODING=utf-8 python -X utf8 -m scripts.probe_llm_registry 2>&1)
  rc=$?
  printf '%s\n' "$out"
  echo "=== done (rc=$rc) ==="

  # ── 三态 ──────────────────────────────────────────────────────────────
  case "$out" in
    *"[probe] "*) ;;
    *) cron_dead "$ALERTS" "$PFX" "probe-crashed" \
         "python rc=$rc, 没打出 [probe] 抬头 —— 崩在判定之前 (import / 密钥 / 网络), 见 $LOG" ;;
  esac
  probed=$(printf '%s\n' "$out" | grep -oE '共探 [0-9]+ 个' | head -1 | grep -oE '[0-9]+')
  [ "${probed:-0}" -gt 0 ] || cron_dead "$ALERTS" "$PFX" "no-samples" \
      "共探 ${probed:-?} 个 —— 注册表空或探针一个请求都没发, 「0 个 dead」在这里没有意义"

  case "$rc" in
    0) exit 0 ;;
    1)
      # 只有「dead」(注册表说活、实测不可用)才翻转 rc(见 probe_llm_registry.main()
      # 内的判据), 所以抬头在 + rc=1 就是真实漂移, 值得每天都告警一次 —— 告警文件
      # 只在真失败时追加, 不会因为"7 天内到期"或"已过期待清理"这类预期内的信号
      # 而天天写, 不会重蹈飞轮日报"天天炸=没人看"的覆辙。
      echo "$PFX FOUND DEAD ENTRIES rc=1 $(date '+%F %T') — 共探 $probed 个, 见 $LOG" >> "$ALERTS"
      exit 1 ;;
    *) cron_dead "$ALERTS" "$PFX" "unexpected-rc:$rc" "probe_llm_registry 只应退 0/1, 实得 $rc, 见 $LOG" ;;
  esac
} >> "$LOG" 2>&1
