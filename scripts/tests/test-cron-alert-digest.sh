#!/usr/bin/env bash
# scripts/cron/alert-digest.sh 的测试 —— 游标、空 digest 的必发生读数、投递失败不前进游标、三态。
#
# 卡 C 任务 4 的两条硬判据:
#   · 游标不前进 ⇒ 重复发 ⇒ 红   (投递成功后第二次跑必须 DIGEST empty; 只发**新增**行)
#   · 无新增 ⇒ 必有一行 `DIGEST empty` (硬约束 9: 「没喊」要与「没跑」长得不一样)
# 另: 投递失败 ⇒ rc=2 且游标**不**前进 (下次重发, 不丢); 轮转/截断 ⇒ 从头读并注明; 0 个文件 ⇒ rc=2。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRIPT="$ROOT_DIR/scripts/cron/alert-digest.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }
BASH_ABS=$(command -v bash) || fail "本机缺 bash"
[ -s "$SCRIPT" ] || fail "$SCRIPT 不存在"; bash -n "$SCRIPT" || fail "语法错误: $SCRIPT"

FAKEBIN="$TMP/bin"; STUB="$TMP/stub"; LOGS="$TMP/logs"; STATE="$TMP/state"
mkdir -p "$FAKEBIN" "$STUB" "$LOGS"
# ⚠️ cp 是 curl **桩自己**要用的 (拷 payload), 不是被测脚本要的 —— 第一版漏了它, 桩以 `cp: command not found`
#    死掉, 而那在被测脚本眼里就是「sink 失败 rc=7」, 与真投递失败长得一样 (形态 A: 错的是尺子)。
for t in date mktemp rm grep head tail cut tr sed cat cp dirname basename awk mkdir wc stat hostname; do
    abs=$(command -v "$t") || { [ "$t" = hostname ] && continue; fail "本机缺 $t"; }
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$t"; chmod +x "$FAKEBIN/$t"
done
if abs=$(command -v python); then printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/python"; chmod +x "$FAKEBIN/python"; HAVE_PY=1; else HAVE_PY=0; fi
export STUB
cat > "$FAKEBIN/curl" <<'S'
#!/bin/sh
echo "curl $*" >> "$STUB/calls.log"
for a in "$@"; do case "$a" in @*) cp "${a#@}" "$STUB/payload.json" ;; esac; done
[ -f "$STUB/curl.rc" ] && exit "$(cat "$STUB/curl.rc")"; exit 0
S
chmod +x "$FAKEBIN/curl"

RC=0; OUT=""
run_it() {  # 额外 env K=V…
    rm -f "$STUB/calls.log"
    OUT=$(env -i PATH="$FAKEBIN" HOME="$TMP" STUB="$STUB" CRON_BIN_PATH="$FAKEBIN" \
        ALERT_DIGEST_LOGS_DIR="$LOGS" ALERT_DIGEST_STATE_DIR="$STATE" "$@" "$BASH_ABS" "$SCRIPT" 2>&1); RC=$?
    echo "    rc=$RC | $(printf '%s' "$OUT" | grep -aE '^(ALERT DIGEST|DIGEST empty|== )' | tr '\n' '|' | cut -c1-200)"
}
ledger_last() { tail -1 "$STATE/digest.ledger" 2>/dev/null; }
assert_out() { case "$OUT" in *"$1"*) ;; *) fail "$2: 输出里没有 [$1]: $OUT" ;; esac; }
assert_no_out() { case "$OUT" in *"$1"*) fail "$2: 输出里不该有 [$1]" ;; esac; }

# ── 0. 一个 alert 文件都没有 ⇒ rc=2 no-alert-files (⛔ 不是「今天没告警」) ─────
run_it
[ "$RC" = 2 ] || fail "[0] 期望 rc=2 实得 $RC"; assert_out "reason=no-alert-files" "[0]"
ok "0 个 alert 文件 ⇒ rc=2 no-alert-files"

# ── 1. 首跑: 三个文件的存量行全进 digest, rc=1, 游标建立 ─────────────────
printf 'A1 soft-delete orphans found\nA2 second line\n' > "$LOGS/soft-delete-orphans.alert"
printf 'B1 LLM CANARY FLAGGED rc=1\n' > "$LOGS/llm-canary-alerts.log"
: > "$LOGS/margin-parity-alerts.log"
printf 'should be ignored\n' > "$LOGS/soft-delete-orphans.cron.log"
run_it
[ "$RC" = 1 ] || fail "[1] 期望 rc=1 实得 $RC: $OUT"
assert_out "files=3 new_lines=3" "[1]"; assert_out "== soft-delete-orphans.alert  (+2)" "[1]"; assert_out "== llm-canary-alerts.log  (+1)" "[1]"
assert_out "A1 soft-delete" "[1]"; assert_out "B1 LLM CANARY" "[1]"; assert_no_out "margin-parity" "[1] 空文件不该出现在 digest 里"
assert_no_out "should be ignored" "[1] *.cron.log 不是 alert 文件"
[ -f "$STATE/soft-delete-orphans.alert.cursor" ] || fail "[1] 游标没建"
case "$(ledger_last)" in *"DIGEST files=3 new_lines=3 sink=stdout delivered=yes rc=1"*) ;; *) fail "[1] 台账: $(ledger_last)" ;; esac
ok "首跑: 3 文件 3 行进 digest, rc=1, 游标建立, *.cron.log 被排除"

# ── 2. 🔴 立刻再跑: 没有新增 ⇒ 必有 `DIGEST empty`, rc=0, 一行都不重发 ──────
run_it
[ "$RC" = 0 ] || fail "[2] 期望 rc=0 实得 $RC"; assert_out "DIGEST empty" "[2] 硬约束 9: 空也要打出来"
assert_no_out "A1 soft-delete" "[2] 游标没前进 —— 重发了上次的行"
case "$(ledger_last)" in *"new_lines=0 sink=stdout delivered=empty rc=0"*) ;; *) fail "[2] 台账: $(ledger_last)" ;; esac
ok "🔴 无新增 ⇒ DIGEST empty rc=0, 不重发 (游标前进了)"

# ── 3. 追加一行 ⇒ 只发新增的那一行 ──────────────────────────────────────
printf 'A3 third line\n' >> "$LOGS/soft-delete-orphans.alert"
run_it
[ "$RC" = 1 ] || fail "[3] 期望 rc=1 实得 $RC"; assert_out "(+1)" "[3]"; assert_out "A3 third line" "[3]"
assert_no_out "A1 soft-delete" "[3] 旧行不该再发"; assert_no_out "A2 second" "[3] 旧行不该再发"
ok "追加一行 ⇒ 只发新增的那一行"

# ── 4. 轮转/截断 ⇒ 从头读并注明 rotated ─────────────────────────────────
printf 'R1 after rotate\n' > "$LOGS/soft-delete-orphans.alert"   # 比游标短 ⇒ 截断
run_it
[ "$RC" = 1 ] || fail "[4] 期望 rc=1 实得 $RC"; assert_out "rotated/truncated" "[4]"; assert_out "R1 after rotate" "[4]"
ok "文件变短 ⇒ 从头读, 注明 rotated/truncated"

# ── 5. 🔴 投递失败 ⇒ rc=2, 游标**不**前进, 下次 stdout 重发同一批 ──────────
printf 'W1 line for webhook\n' >> "$LOGS/llm-canary-alerts.log"
printf '7' > "$STUB/curl.rc"
run_it ALERT_SINK=webhook ALERT_WEBHOOK_URL=https://example.invalid/hook
if [ "$HAVE_PY" = 1 ]; then
    [ "$RC" = 2 ] || fail "[5] 期望 rc=2 实得 $RC: $OUT"; assert_out "reason=sink-failed:webhook" "[5]"
    grep -q 'curl .*example.invalid' "$STUB/calls.log" || fail "[5] curl 没被调"
    grep -q '"msgtype": "text"' "$STUB/payload.json" || fail "[5] payload 不是机器人 text 形状"
else
    [ "$RC" = 2 ] || fail "[5] 期望 rc=2 实得 $RC"; assert_out "reason=sink-failed:webhook" "[5] (无 python, 走 payload 失败那条也该 rc=2)"
    echo "    (本机无 python, webhook payload 分支未量到)"
fi
rm -f "$STUB/curl.rc"
run_it
[ "$RC" = 1 ] || fail "[5b] 投递失败后再跑 stdout 应重发, 实得 rc=$RC"; assert_out "W1 line for webhook" "[5b] 上次没送出去的行必须还在"
ok "🔴 投递失败 ⇒ rc=2 且游标不前进; 下次重发同一批 (不丢)"

# ── 6. webhook 成功 ⇒ rc=1, 游标前进 ─────────────────────────────────────
if [ "$HAVE_PY" = 1 ]; then
    printf 'W2 ok line\n' >> "$LOGS/llm-canary-alerts.log"
    run_it ALERT_SINK=webhook ALERT_WEBHOOK_URL=https://example.invalid/hook
    [ "$RC" = 1 ] || fail "[6] 期望 rc=1 实得 $RC: $OUT"
    grep -q 'W2 ok line' "$STUB/payload.json" || fail "[6] payload 里没有新行"
    run_it; [ "$RC" = 0 ] || fail "[6b] webhook 成功后应 empty"; assert_out "DIGEST empty" "[6b]"
    ok "webhook 成功 ⇒ rc=1, payload 含新行, 游标前进"
else
    echo "    (本机无 python, 跳过 webhook 成功路径)"; ok "webhook 成功路径: 本机无 python, 登记未量"
fi

# ── 7. sink 配置错 ⇒ rc=2 ────────────────────────────────────────────────
printf 'X1\n' >> "$LOGS/margin-parity-alerts.log"
run_it ALERT_SINK=webhook; [ "$RC" = 2 ] || fail "[7] 期望 rc=2 实得 $RC"; assert_out "reason=webhook-url-missing" "[7]"
run_it ALERT_SINK=pigeon; [ "$RC" = 2 ] || fail "[7b] 期望 rc=2 实得 $RC"; assert_out "reason=unknown-sink:pigeon" "[7b]"
run_it; [ "$RC" = 1 ] || fail "[7c] 配置错之后游标应未前进, 实得 rc=$RC"; assert_out "X1" "[7c]"
ok "webhook 无 URL / 未知 sink ⇒ rc=2, 游标不前进"

# ── 8. 状态目录不可写 ⇒ rc=2 (cron_dead 往台账追加会报错到 stderr, 不吞) ─────
: > "$TMP/state-is-a-file"
run_it ALERT_DIGEST_STATE_DIR="$TMP/state-is-a-file"
[ "$RC" = 2 ] || fail "[8] 期望 rc=2 实得 $RC"; assert_out "state-dir-unwritable" "[8]"
ok "状态目录不可写 ⇒ rc=2 state-dir-unwritable"

# ── 9. 每次跑台账恰好一行判决 (硬约束 9) ─────────────────────────────────
n_led=$(grep -c . "$STATE/digest.ledger")
before=$n_led; run_it; after=$(grep -c . "$STATE/digest.ledger")
[ "$((after - before))" = 1 ] || fail "[9] 一次运行台账应恰好长 1 行, 实得 $((after - before))"
ok "台账每次恰好一行"

echo "READING: RC2_REASONS_CONSTRUCTED=5 [no-alert-files sink-failed webhook-url-missing unknown-sink state-dir-unwritable]"
echo "READING: HAVE_PYTHON=$HAVE_PY"
echo "PASS: test-cron-alert-digest.sh ($PASSED 组断言)"
