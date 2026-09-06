#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# LLM router billing-safety CANARY  (spec 2026-07-01-smart-llm-router-spec.md)
#
# The router only calls `已开启`(ON) models, which 403 on exhaustion and never bill.
# But the ON toggle lives in the Aliyun console and can be flipped OFF by anyone, or a
# model can be retired (404 NOSKU) — the static _SAFE_MODELS registry can't see either.
# This canary re-probes EVERY registry entry with the real keys and FLAGS anything that
# is NOT a clean billing-safe response (200 has-quota / 403 FreeTierOnly / 402 free-
# exhausted). It also warns when the registry snapshot is stale (>21d → re-audit console).
#
# Runs ON server 47 (has the keys + venv + deployed module). Exit 1 on any flag so a
# cron wrapper can alert. Intended cadence: weekly  (crontab example at bottom).
#
# Manual run:  bash /www/wwwroot/cretas/code/scripts/deploy/llm-router-billing-canary.sh
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
PYDIR="${LLM_ROUTER_PYDIR:-/www/wwwroot/cretas/code/backend/python}"
ALERTS="${LLM_ROUTER_ALERTS:-/www/wwwroot/cretas/logs/llm-canary-alerts.log}"
# 🔴 2026-09-06 审计: 原来 `source venv38/bin/activate` —— venv38 早已不在(现在只有
#    venv311 与 venv-current 链接), `set -u` 无 `-e` 下 source 失败**不中止**, 掉回系统
#    python 3.6.8, `from __future__ import annotations` SyntaxError。实测从 2026-08-03 起
#    每周崩一次(日志里 5 个 SyntaxError), 而 crontab 那行 `|| echo "LLM canary FLAGGED"`
#    把崩溃写成了账单告警。⇒ venv 用 venv-current, 不在就 rc=2 说清楚; 三态措辞由本脚本
#    自己写进 $ALERTS, crontab 那个 `|| echo` 应当去掉(见审计文档)。
# shellcheck source=scripts/cron/cron-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/../cron/cron-common.sh"
cron_pin_path
cron_require_cmds "$ALERTS" "LLM CANARY" systemctl sed tr grep date
cd "$PYDIR" || cron_dead "$ALERTS" "LLM CANARY" "pydir-missing" "no $PYDIR"
cron_activate_venv "$PYDIR/venv-current/bin/activate" "$ALERTS" "LLM CANARY"
# llm_router.py 用 `from __future__ import annotations`(3.7+); 系统 python 是 3.6.8。
python -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 7) else 1)' \
    || cron_dead "$ALERTS" "LLM CANARY" "python-too-old" "$(command -v python): $(python --version 2>&1)"
# Load the running service's LLM keys (never hardcode — they rotate).
eval "$(systemctl show cretas-python -p Environment | sed 's/^Environment=//' \
        | tr ' ' '\n' | grep -E '^LLM_(ALIYUN|TENCENT|ZHIPU)' \
        | sed -E "s/^([^=]+)=(.*)$/export \1='\2'/")"

python - <<'PY'
import sys, types, os, datetime, httpx
# stub the http-client import so we can load the module standalone
_s = types.ModuleType("common.llm_client"); _s.get_llm_http_client = lambda: None
sys.modules["common.llm_client"] = _s
import common.llm_router as r

pairs = list(r._SAFE_MODELS.keys())
CFG = {
    "aliyun_a": ("https://dashscope.aliyuncs.com/compatible-mode/v1", "LLM_ALIYUN_A_API_KEY"),
    "aliyun_b": ("https://dashscope.aliyuncs.com/compatible-mode/v1", "LLM_ALIYUN_B_API_KEY"),
    "aliyun_c": ("https://dashscope.aliyuncs.com/compatible-mode/v1", "LLM_ALIYUN_C_API_KEY"),
    "tencent":  ("https://tokenhub.tencentmaas.com/v1", "LLM_TENCENT_API_KEY"),
    "zhipu":    ("https://open.bigmodel.cn/api/paas/v4", "LLM_ZHIPU_API_KEY"),
}
def _classify(sc, body):
    """None = billing-safe (200 has-quota / 403 FreeTierOnly / 402 free-exhausted);
    else a flag string. 404 NOSKU = retired; other = toggle flip / possible bill."""
    if 200 <= sc < 300:
        return None
    if sc == 403 and ("FreeTierOnly" in body or "AllocationQuota" in body):
        return None
    if sc == 402 and ("FREE_QUOTA_EXHAUSTED" in body or "Insufficient Balance" in body):
        return None
    return f"HTTP {sc} :: {body[:90]}"

flags, safe = [], 0
with httpx.Client(timeout=30.0) as cli:
    for account, model in pairs:
        base, kenv = CFG[account]
        key = os.environ.get(kenv, "")
        if not key:
            flags.append(f"{account}/{model}: NO-KEY({kenv})"); continue
        payload = {"model": model, "messages": [{"role": "user", "content": "hi"}], "max_tokens": 1}
        hdr = {"Authorization": f"Bearer {key}"}
        result = "ERR unknown"
        # Retry once on transient timeout/network error so a blip doesn't cry wolf —
        # only a PERSISTENT bad status (real toggle flip / retired model) should flag.
        for attempt in (1, 2):
            try:
                resp = cli.post(f"{base}/chat/completions", headers=hdr, json=payload)
                result = _classify(resp.status_code, resp.text)
                break                                      # got a real HTTP status → trust it
            except Exception as e:
                result = f"ERR {type(e).__name__} (x{attempt})"
                # transient (timeout/connection) → retry once; a 2nd failure flags
        if result is None:
            safe += 1
        else:
            flags.append(f"{account}/{model}: {result}")

age = (datetime.date.today() - r._REGISTRY_AUDIT_DATE).days
print(f"[canary {datetime.date.today()}] {len(pairs)} entries · {safe} billing-safe · "
      f"registry age {age}d (audit {r._REGISTRY_AUDIT_DATE})")
rc = 0
if age > r._REGISTRY_MAX_AGE_DAYS:
    print(f"⚠️  REGISTRY STALE ({age}d > {r._REGISTRY_MAX_AGE_DAYS}d) — re-scrape console + refresh _SAFE_MODELS")
    rc = 3
if flags:
    print(f"⚠️  {len(flags)} FLAGGED (toggle flip / retired / possible bill — investigate):")
    for f in flags:
        print("   " + f)
    rc = 1
if rc == 0:
    print("✅ canary PASS — every registry entry still billing-safe, registry fresh")
print("👉 also eyeball the Aliyun 账单 (DashScope/百炼) — client-side logic can't see a "
      "console-level paid charge; that bill = the true backstop.")
sys.exit(rc)
PY
rc=$?
# 三态措辞写在这里, ⛔ 不在 crontab 里: 0 全部 billing-safe / 1 有 FLAGGED / 3 注册表过期 /
# 2 仪器故障(上面 cron_dead 已写过告警行并退出, 到不了这里)。
if [ "$rc" -ne 0 ]; then
    echo "LLM CANARY FLAGGED rc=$rc $(date '+%F %T') — $([ "$rc" -eq 3 ] && echo '注册表快照过期(>21d), 重扫控制台' || echo '有条目非 billing-safe, 看日志逐条核对')" >> "$ALERTS"
fi
exit "$rc"
# ── crontab (install on server 47, weekly Mon 09:00) ──
#   0 9 * * 1 bash /www/wwwroot/cretas/code/scripts/deploy/llm-router-billing-canary.sh \
#     >> /www/wwwroot/cretas/logs/llm-canary.log 2>&1
#   ⛔ 不要再接 `|| echo "LLM canary FLAGGED"`: 那会把 rc=2(崩了/venv 不在)和 rc=1(真 FLAGGED)
#      写成同一句话 —— 2026-08-03 ~ 08-31 就是这么把 5 次崩溃记成 5 次账单告警的。
