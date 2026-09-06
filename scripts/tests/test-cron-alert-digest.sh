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
# 🔴 python 桩要指向一个**真的解释器**, 不是垫片 (2026-09-06 CI 首跑就栽在这里): ubuntu runner 上
#    `command -v python` 给的是 workflow 装的 ~/.local/bin/python 垫片, 它 `exec python3` —— 而 python3
#    不在受限 PATH 里 ⇒ 垫片死 ⇒ payload 写不出 ⇒ 被测脚本读成 sink-failed, 与真投递失败同形 (形态 A:
#    错的是尺子)。Windows 上 python3 又是 Store 的假存根。⇒ 逐个真跑一次 `-c` 探测, 谁能跑用谁。
HAVE_PY=0
for c in python3 python; do
    abs=$(command -v "$c" 2>/dev/null) || continue
    "$abs" -c 'import sys, json; sys.exit(0)' >/dev/null 2>&1 || continue
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/python"; chmod +x "$FAKEBIN/python"
    # 桩自检: 在**受限 PATH** 下它真的能跑 (垫片 exec 别的名字时这里就会露馅)。
    if PATH="$FAKEBIN" python -c 'import json' >/dev/null 2>&1; then HAVE_PY=1; break; fi
    rm -f "$FAKEBIN/python"
done
echo "READING: PYTHON_STUB=$([ "$HAVE_PY" = 1 ] && cat "$FAKEBIN/python" | tail -1 || echo none)"
export STUB
# curl 桩: 按被测脚本的 `-w '\n%{http_code}'` 约定, 先打响应体再打一行 http code。
#   $STUB/curl.rc   curl 自己的退出码 (网络层失败)      默认 0
#   $STUB/curl.http HTTP 状态                            默认 200
#   $STUB/curl.body 响应体                               默认 {"errcode":0,"errmsg":"ok"}
#   ⚠️ 记调用要用 printf 不用 echo: 被测脚本传的 `-w '\n%{http_code}'` 含字面 \n, dash 的 echo 会把它
#      展开成换行, 记录被劈成两行, `grep 'curl .*example.invalid'` 就找不到了 (WSL 实测, Windows 的 sh 不会)。
cat > "$FAKEBIN/curl" <<'S'
#!/bin/sh
printf 'curl %s\n' "$*" >> "$STUB/calls.log"
for a in "$@"; do case "$a" in @*) cp "${a#@}" "$STUB/payload.json" ;; esac; done
[ -f "$STUB/curl.rc" ] && exit "$(cat "$STUB/curl.rc")"
if [ -f "$STUB/curl.body" ]; then cat "$STUB/curl.body"; else printf '{"errcode":0,"errmsg":"ok"}'; fi
printf '\n%s' "$( [ -f "$STUB/curl.http" ] && cat "$STUB/curl.http" || echo 200)"
exit 0
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
    [ "$RC" = 2 ] || fail "[5] 期望 rc=2 实得 $RC: $OUT"; assert_out "reason=webhook-curl-failed" "[5]"
    grep -q 'curl .*example.invalid' "$STUB/calls.log" || fail "[5] curl 没被调"
    grep -q '"msgtype": "text"' "$STUB/payload.json" || fail "[5] payload 不是机器人 text 形状"
else
    [ "$RC" = 2 ] || fail "[5] 期望 rc=2 实得 $RC"; assert_out "reason=payload-build-failed" "[5] (无 python, 走 payload 失败那条也该 rc=2, 且 reason 与 curl 失败分开)"
    echo "    (本机无 python, webhook curl 分支未量到)"
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

# ── 10. 🔴 「送到了」= HTTP 2xx 且 errcode=0: 404/500 或 200+errcode≠0 都不许前进游标 (#681 终审) ──
if [ "$HAVE_PY" = 1 ]; then
    printf 'H1 line\n' >> "$LOGS/llm-canary-alerts.log"
    printf '500' > "$STUB/curl.http"; printf 'boom' > "$STUB/curl.body"
    run_it ALERT_SINK=webhook ALERT_WEBHOOK_URL=https://example.invalid/hook
    [ "$RC" = 2 ] || fail "[10a] HTTP 500 应 rc=2, 实得 $RC: $OUT"; assert_out "reason=webhook-http-non-2xx" "[10a]"
    rm -f "$STUB/curl.http" "$STUB/curl.body"
    printf '{"errcode":93000,"errmsg":"invalid webhook url"}' > "$STUB/curl.body"
    run_it ALERT_SINK=webhook ALERT_WEBHOOK_URL=https://example.invalid/hook
    [ "$RC" = 2 ] || fail "[10b] errcode≠0 应 rc=2, 实得 $RC: $OUT"; assert_out "reason=webhook-errcode" "[10b]"
    grep -q 'errcode=93000' "$STATE/digest.alerts" || fail "[10b] 明细里应带 errcode=93000: $(tail -3 "$STATE/digest.alerts")"
    rm -f "$STUB/curl.body"
    run_it
    [ "$RC" = 1 ] || fail "[10c] 两次假投递之后游标应仍未前进, 实得 rc=$RC"; assert_out "H1 line" "[10c] 那一批不许丢"
    ok "🔴 HTTP 500 / 200+errcode≠0 都 rc=2 且游标不前进 (不丢批)"
    # payload 拼不出 (python 坏) ⇒ 独立 reason, ⛔ 不与 curl 失败撞码。先追一行, 否则上一步已把游标推到末尾, 这里会读成 empty。
    printf 'P1 line for broken python\n' >> "$LOGS/llm-canary-alerts.log"
    printf '#!/bin/sh\nexit 1\n' > "$FAKEBIN/python.broken"; mv "$FAKEBIN/python" "$FAKEBIN/python.ok"; mv "$FAKEBIN/python.broken" "$FAKEBIN/python"; chmod +x "$FAKEBIN/python"
    run_it ALERT_SINK=webhook ALERT_WEBHOOK_URL=https://example.invalid/hook
    mv -f "$FAKEBIN/python.ok" "$FAKEBIN/python"
    [ "$RC" = 2 ] || fail "[10d] 期望 rc=2 实得 $RC"; assert_out "reason=payload-build-failed" "[10d]"
    [ ! -f "$STUB/calls.log" ] || ! grep -q curl "$STUB/calls.log" || fail "[10d] payload 都没拼出来不该调 curl"
    ok "payload 拼不出 ⇒ rc=2 payload-build-failed (与 curl 失败分开), 不调 curl"
    run_it ALERT_SINK=webhook ALERT_WEBHOOK_URL=https://example.invalid/hook
    [ "$RC" = 1 ] || fail "[10e] 修好后应投递成功, 实得 $RC: $OUT"
else
    ok "HTTP/errcode/payload 三例: 本机无 python, 登记未量 (CI ubuntu 有)"
fi

# ── 11. 全部输入都读不了 ⇒ rc=2 no-readable-alert-files (⛔ 不是 DIGEST empty) ───────
run_it ALERT_DIGEST_FILES="/nonexistent/a-alerts.log /nonexistent/b.alert"
[ "$RC" = 2 ] || fail "[11] 期望 rc=2 实得 $RC: $OUT"; assert_out "reason=no-readable-alert-files" "[11]"
assert_no_out "DIGEST empty" "[11] 一个都读不了不许说 empty"
ok "全部输入读不了 ⇒ rc=2 no-readable-alert-files"

# ── 12. dead 路径台账也恰好一行判决 (明细走 digest.alerts) ───────────────────────
printf 'D1 line for dead path\n' >> "$LOGS/margin-parity-alerts.log"   # 没新增会在 sink 之前走 empty 路径, 要先有行
before=$(grep -c . "$STATE/digest.ledger"); run_it ALERT_SINK=pigeon; after=$(grep -c . "$STATE/digest.ledger")
[ "$((after - before))" = 1 ] || fail "[12] dead 路径台账应恰好长 1 行, 实得 $((after - before))"
grep -q 'INSTRUMENT_DEAD reason=unknown-sink:pigeon rc=2' <(ledger_last) || fail "[12] 台账判决行: $(ledger_last)"
grep -q 'reason=unknown-sink:pigeon' "$STATE/digest.alerts" || fail "[12] 明细应在 digest.alerts"
grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} ' <(ledger_last) || fail "[12] 台账时间戳应是本地 %F %T (与 cron_dead 同口径): $(ledger_last)"
ok "dead 路径台账恰好一行, 明细进 digest.alerts, 时间戳同口径"

echo "READING: RC2_REASONS_CONSTRUCTED=$([ "$HAVE_PY" = 1 ] && echo 9 || echo 6) [no-alert-files webhook-curl-failed webhook-url-missing unknown-sink state-dir-unwritable no-readable-alert-files$([ "$HAVE_PY" = 1 ] && echo ' webhook-http-non-2xx webhook-errcode payload-build-failed')]"
echo "READING: HAVE_PYTHON=$HAVE_PY"
echo "PASS: test-cron-alert-digest.sh ($PASSED 组断言)"
