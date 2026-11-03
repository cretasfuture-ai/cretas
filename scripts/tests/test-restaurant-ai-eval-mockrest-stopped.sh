#!/usr/bin/env bash
# 2026-10-29 MOCK_REST AI writes stopped, owner-approved.
#
# scripts/cron/restaurant-ai-eval.sh 停掉两段 MOCK_REST (餐饮电池 restaurant_ai_eval、
# 餐饮飞轮日报 restaurant-intent-promote.py, 其 --factory 默认 MOCK_REST),
# 保留 DEMO_FACTORY2 的工厂读 / 写电池。
#
# 真跑一遍脚本 (只把 /www/wwwroot/cretas 前缀挪进沙箱, 其余一个字不改),
# 假 python 记下每次调用:
#   ① 两段 MOCK_REST 一次都没调到
#   ② 阴性对照: 工厂读 / 写电池仍各调一次, rc=0
#   ③ 工厂电池失败时 rc 仍然非 0 (停掉餐饮段之后 rc 初值还在, set -u 不炸)
# 沙箱里放好 restaurant-intent-promote.py, 所以飞轮段一旦被恢复, -f 判断会过、调用会被记下。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SRC="$ROOT_DIR/scripts/cron/restaurant-ai-eval.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$SRC" ] || fail "$SRC 不存在"
bash -n "$SRC" || fail "语法错误: $SRC"

SANDBOX="$TMP/root"
SCRIPT="$TMP/restaurant-ai-eval.sh"
sed -e "s|/www/wwwroot/cretas|$SANDBOX|g" "$SRC" > "$SCRIPT"
grep -q '/www/wwwroot/cretas' "$SCRIPT" && fail "沙箱化后仍有生产路径"
grep -q "$SANDBOX" "$SCRIPT" || fail "沙箱化没生效"
diff <(sed -e "s|$SANDBOX|/www/wwwroot/cretas|g" "$SCRIPT") "$SRC" >/dev/null \
    || fail "沙箱化改动超出了路径前缀"

PYDIR="$SANDBOX/code/backend/python"
FAKEBIN="$TMP/fakebin"
mkdir -p "$PYDIR/venv-current/bin" "$SANDBOX/logs" "$SANDBOX/code/scripts" "$FAKEBIN"
printf 'export PATH="%s:$PATH"\n' "$FAKEBIN" > "$PYDIR/venv-current/bin/activate"
printf 'X=1\n' > "$SANDBOX/.env.prod"
printf '# placeholder\n' > "$SANDBOX/code/scripts/restaurant-intent-promote.py"
CALLS="$TMP/calls.log"
cat > "$FAKEBIN/python" <<'S'
#!/bin/sh
echo "python $*" >> "$CALLS"
case "$*" in *"$FAIL_MATCH"*) [ -n "$FAIL_MATCH" ] && exit 1 ;; esac
exit 0
S
chmod +x "$FAKEBIN/python"

run() {  # $1 = 让哪个调用失败 (空 = 全成功)
    rm -f "$CALLS" "$SANDBOX/logs/"*
    CALLS="$CALLS" FAIL_MATCH="$1" bash "$SCRIPT" > "$TMP/out" 2>&1; RC=$?
}

run ""
[ "$RC" = 0 ] || fail "[全成功] 期望 rc=0 实得 $RC: $(tail -5 "$SANDBOX/logs/restaurant-ai-eval.log" 2>/dev/null; cat "$TMP/out")"
[ -s "$CALLS" ] || fail "[全成功] python 一次都没被调用 —— 仪器没接上"
grep -q 'smartbi.scripts.restaurant_ai_eval' "$CALLS" && fail "餐饮电池 (MOCK_REST) 仍在跑: $(cat "$CALLS")"
ok "餐饮电池 restaurant_ai_eval (MOCK_REST) 未调用"
grep -q 'restaurant-intent-promote.py' "$CALLS" && fail "餐饮飞轮日报 (MOCK_REST) 仍在跑: $(cat "$CALLS")"
ok "餐饮飞轮日报 restaurant-intent-promote.py (MOCK_REST) 未调用"
[ "$(grep -c 'smartbi.scripts.factory_ai_eval' "$CALLS")" = 1 ] || fail "工厂读电池没跑 (阴性对照): $(cat "$CALLS")"
[ "$(grep -c 'smartbi.scripts.factory_write_eval' "$CALLS")" = 1 ] || fail "工厂写电池没跑 (阴性对照): $(cat "$CALLS")"
ok "阴性对照: 工厂读 / 写电池 (DEMO_FACTORY2) 各跑一次, rc=0"
grep -q '2026-10-29 MOCK_REST AI writes stopped, owner-approved' "$SANDBOX/logs/restaurant-ai-eval.log" \
    || fail "日志里没有停用说明"
ok "日志记了停用说明"

run "factory_write_eval"
[ "$RC" = 1 ] || fail "[写电池失败] 期望 rc=1 实得 $RC"
grep -q 'FACTORY WRITE EVAL FAILED' "$SANDBOX/logs/restaurant-ai-eval-alerts.log" 2>/dev/null \
    || fail "[写电池失败] 没有告警行"
ok "工厂写电池失败 ⇒ rc=1 + 告警 (rc 初值仍在)"

echo "PASS: test-restaurant-ai-eval-mockrest-stopped.sh ($PASSED 组断言)"
