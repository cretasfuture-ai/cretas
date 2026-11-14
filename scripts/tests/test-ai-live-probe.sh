#!/usr/bin/env bash
# GUARDS=scripts/ai-live-probe/ai_live_probe.py,scripts/ai-live-probe/post-deploy-hook.sh,scripts/ai-live-probe/cases.json,scripts/deploy/release-cretas.sh,scripts/deploy/deploy-smartbi-python.sh
# 上线后自动实测关 (scripts/ai-live-probe) 的离线测试 —— ⛔ 不连生产, 全部打本机桩服务器
# (scripts/tests/lib/ai-live-probe-stub.py)。守三件事:
#   A. 三态退出码: 全过 0 / 有失败 1 / 没量到 2 (服务不可达、登录失败、租户不对、0 条执行)。
#      每个红的读数都配一个「一定会发生」的读数 (硬约束 9): 结果文件必有 summary 行。
#   B. 写入真跑的阴性对照会红: confirm 说成功却没落库 / 伪造令牌被接受 / 说话那轮就落库。
#   C. 发布钩子: report 不改退出码、enforce 失败返回非 0、off 不跑; 两个发布脚本真的接上了,
#      且 release-cretas.sh 的调用在 RELEASE_FINAL_STATUS 之后、只在 Java 真部署时跑。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
PROBE="$ROOT_DIR/scripts/ai-live-probe/ai_live_probe.py"
HOOK="$ROOT_DIR/scripts/ai-live-probe/post-deploy-hook.sh"
STUB="$ROOT_DIR/scripts/tests/lib/ai-live-probe-stub.py"
TMP=$(mktemp -d)
STUB_PID=""
cleanup() { [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

PY=""
for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
        PY=$candidate; break
    fi
done
[ -n "$PY" ] || { echo "SKIPPED: 没有 Python 3.8+"; exit 77; }

# 只用一条只读 case, 让桩保持小; 真套件的 cases.json 另做结构校验 (见 D)。
cat >"$TMP/cases.json" <<'EOF'
{"cases": [{"id": "inventory_both_entries", "entries": ["execute", "stream"], "same_intent_across_entries": true,
  "turns": [{"say": "查库存", "expect": {"status_in": ["SUCCESS"], "intent": "REPORT_INVENTORY", "message_contains": ["库存"]}}]}]}
EOF

start_stub() {  # $1 = STUB_MODE
    [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null
    rm -f "$TMP/port" "$TMP/calls.log"
    STUB_MODE="$1" "$PY" "$STUB" "$TMP/port" "$TMP/calls.log" &
    STUB_PID=$!
    for _ in $(seq 1 50); do [ -s "$TMP/port" ] && break; sleep 0.1; done
    [ -s "$TMP/port" ] || fail "桩服务器没起来 (mode=$1)"
    BASE="http://127.0.0.1:$(cat "$TMP/port")"
}

run_probe() {  # 其余参数透传; 设 RC / OUT / RESULT
    OUT="$TMP/out.log"
    AI_PROBE_READ_USERNAME=stub_reader AI_PROBE_READ_PASSWORD=stub-pass \
    AI_PROBE_WRITE_USERNAME=stub_writer AI_PROBE_WRITE_PASSWORD=stub-pass \
        "$PY" -X utf8 "$PROBE" --base "$BASE" --server "" --cases "$TMP/cases.json" \
        --out-dir "$TMP/results" --env-file "$TMP/none.env" "$@" >"$OUT" 2>&1
    RC=$?
    RESULT=$(sed -n 's/^AI_LIVE_PROBE_RESULT=//p' "$OUT")
    echo "    $(grep -E '^\[summary\]' "$OUT" || echo '(没有 summary 行!)')   rc=$RC"
    [ -n "$RESULT" ] && [ -s "$RESULT" ] || { cat "$OUT"; fail "没有结果文件 (一定会发生的读数缺失)"; }
    grep -q '"type": "summary"' "$RESULT" || fail "结果文件缺 summary 行"
}

expect_rc() {  # $1=期望 rc  $2=说明
    [ "$RC" = "$1" ] || { cat "$OUT"; fail "$2: 期望 rc=$1, 实为 $RC"; }
    grep -q "^AI_LIVE_PROBE_RC=$1\$" "$OUT" || fail "$2: 回执行 AI_LIVE_PROBE_RC=$1 缺失"
    ok "$2 (rc=$1)"
}

echo "A. 三态退出码"
start_stub pass
run_probe
expect_rc 0 "全过"
grep -q 'write_wechat_create_confirm_cleanup' "$OUT" || fail "全过时写入真跑必须真的跑了"
grep -q 'count before=0 after_confirm=1 after_cleanup=0' "$OUT" || { cat "$OUT"; fail "写入真跑计数不是 0→1→0"; }
grep -c '^POST /api/mobile/F006/ai-intents/confirm' "$TMP/calls.log" | grep -qx 3 \
    || fail "确认调用应恰好 3 次 (伪造 / 正规 / 重放)"
ok "写入真跑 0→1→0, 确认 3 次 (伪造 / 正规 / 重放)"

start_stub stream_drift
run_probe --no-write
expect_rc 1 "流式入口意图漂移"
grep -q 'FAIL.*inventory_both_entries (stream)' "$OUT" || fail "漂移应记在 stream 那条上"
grep -q 'FAIL.*#same_intent' "$OUT" || fail "两入口意图不一致应单独记一条 FAIL"

start_stub bad_login
run_probe
expect_rc 2 "登录 401 = 没量到"

start_stub wrong_tenant
run_probe
expect_rc 2 "登录到别的租户 = 没量到并停"
grep -q 'LIUSHANMEN' "$OUT" || fail "要说出登到了哪个租户"
! grep -q 'ai-intents' "$TMP/calls.log" || fail "租户不对之后一次 AI 调用都不许发生"
ok "租户不对之后 0 次 AI 调用"

kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; STUB_PID=""
run_probe   # BASE 仍指向刚关掉的端口
expect_rc 2 "服务不可达 = 没量到"

start_stub pass
run_probe --no-write --only __no_such_case__
expect_rc 2 "0 条执行 = 没量到"

echo "B. 写入真跑的阴性对照会红"
start_stub write_no_persist
run_probe --only __no_such_case__
expect_rc 1 "confirm 说成功但没落库"
grep -q 'BAD 回查: 计数 +1' "$OUT" || fail "应由回查那一步打红"

start_stub forged_accepted
run_probe --only __no_such_case__
expect_rc 1 "伪造令牌被接受"
grep -q 'BAD 阴性对照③' "$OUT" || fail "应由阴性对照③打红"
grep -q 'after_cleanup=0' "$OUT" || fail "失败时也必须清理干净"

start_stub forged_says_ok
run_probe --only __no_such_case__
expect_rc 1 "伪造令牌回成功 (哪怕没落库)"
grep -q 'BAD 阴性对照③' "$OUT" || fail "伪造令牌拿到成功答复本身就该打红"

start_stub write_on_say
run_probe --only __no_such_case__
expect_rc 1 "说话那轮就落库"
grep -q 'BAD 阴性对照①' "$OUT" || fail "应由阴性对照①打红"
! grep -q '^POST /api/mobile/F006/ai-intents/confirm' "$TMP/calls.log" || fail "阴性对照①失败后不许再去确认"
grep -q 'after_cleanup=0' "$OUT" || fail "提前失败也必须把 QA-PROBE 行清掉"
ok "提前失败: 0 次确认 + 清理干净"

echo "C. 发布钩子"
mkdir -p "$TMP/hook"
cp "$HOOK" "$TMP/hook/post-deploy-hook.sh"
printf 'import sys\nprint("FAKE_PROBE", " ".join(sys.argv[1:]))\nsys.exit(int(__import__("os").environ.get("FAKE_RC", "0")))\n' >"$TMP/hook/ai_live_probe.py"
hook_run() {  # $1=AI_LIVE_PROBE  $2=FAKE_RC ; 设 HRC / HOUT
    HOUT="$TMP/hook.log"
    ( set -euo pipefail; AI_LIVE_PROBE="$1" FAKE_RC="$2"; export AI_LIVE_PROBE FAKE_RC
      # shellcheck source=/dev/null
      source "$TMP/hook/post-deploy-hook.sh"; ai_live_probe_after_deploy python abc123 ) >"$HOUT" 2>&1
    HRC=$?
}
hook_run report 1
[ "$HRC" = 0 ] || { cat "$HOUT"; fail "report 模式失败也不改退出码"; }
grep -q 'AI_LIVE_PROBE_GATE=failed' "$HOUT" || fail "report 模式也要打 GATE=failed"
grep -q -- '--expect-python-commit abc123' "$HOUT" || fail "Python 发布提交没透传给套件"
ok "report: 失败只报告 (rc=0, GATE=failed, 透传发布提交)"
hook_run enforce 1
[ "$HRC" != 0 ] || { cat "$HOUT"; fail "enforce 模式失败必须返回非 0"; }
grep -q '回滚 Python' "$HOUT" || fail "enforce 失败要给回滚提示"
ok "enforce: 失败返回 $HRC + 回滚提示"
hook_run enforce 2
[ "$HRC" != 0 ] && grep -q 'AI_LIVE_PROBE_GATE=not-measured' "$HOUT" || fail "没量到在 enforce 下也是非 0 + not-measured"
ok "enforce: 没量到同样拦下"
hook_run enforce 0
[ "$HRC" = 0 ] && grep -q 'AI_LIVE_PROBE_GATE=passed' "$HOUT" || fail "全过应 rc=0 + passed"
ok "enforce: 全过放行"
hook_run off 1
[ "$HRC" = 0 ] && grep -q 'AI_LIVE_PROBE_GATE=skipped' "$HOUT" && ! grep -q FAKE_PROBE "$HOUT" || fail "off 不跑且留痕"
ok "off: 不跑, 留痕"

echo "C2. 两个发布脚本接上了"
REL="$ROOT_DIR/scripts/deploy/release-cretas.sh"
PYD="$ROOT_DIR/scripts/deploy/deploy-smartbi-python.sh"
# 不经管道 (test-pipeline-tail-swallows-rc): 先落变量, 再用 bash 取最后一行 / 行号
final_hits=$(grep -n "^printf 'RELEASE_FINAL_STATUS=%s\\\\n' \"\$FINAL_STATUS\"$" "$REL") || fail "release-cretas.sh 找不到 RELEASE_FINAL_STATUS 行"
final_hits=${final_hits##*$'\n'}
final_line=${final_hits%%:*}
call_hits=$(grep -n 'ai_live_probe_after_deploy java' "$REL") || fail "release-cretas.sh 没调用钩子"
call_line=${call_hits%%:*}
[ -n "$final_line" ] && [ -n "$call_line" ] || fail "release-cretas.sh 找不到 RELEASE_FINAL_STATUS 行或钩子调用 (final=$final_line call=$call_line)"
[ "$call_line" -gt "$final_line" ] || fail "钩子必须在最后一次 RELEASE_FINAL_STATUS 之后 (那一行仍恰好 1 次)"
grep -q 'if \[ "\$JAVA_DEPLOY_OUTCOME" = deployed \]' "$REL" || fail "release-cretas.sh 只在 Java 真部署时跑实测关"
grep -q 'ai_live_probe_after_deploy python "\$RELEASE_COMMIT"' "$PYD" || fail "deploy-smartbi-python.sh 没接上 (或没传 RELEASE_COMMIT)"
grep -c 'scripts/ai-live-probe/post-deploy-hook.sh' "$REL" "$PYD" | grep -q ':0$' && fail "有发布脚本没 source 钩子"
ok "release-cretas.sh: 在 RELEASE_FINAL_STATUS(第 $final_line 行) 之后第 $call_line 行, 只在 Java deployed 时; deploy-smartbi-python.sh: 传 RELEASE_COMMIT"
bash -n "$REL" && bash -n "$PYD" && bash -n "$HOOK" || fail "语法错误"

echo "D. 真套件 cases.json 结构"
"$PY" - "$ROOT_DIR/scripts/ai-live-probe/cases.json" <<'EOF' || fail "cases.json 结构不对"
import json, sys
cases = json.load(open(sys.argv[1], encoding="utf-8"))["cases"]
ids = [c["id"] for c in cases]
assert len(ids) == len(set(ids)), "case id 重复"
need = {"inventory_both_entries", "clarify_then_pick_1", "report_work_suspend_resume",
        "report_work_lists_processes_first", "report_work_stats_is_query"}
assert need <= set(ids), "缺必备话术: %s" % (need - set(ids))
for c in cases:
    assert set(c["entries"]) <= {"execute", "stream"} and c["entries"], c["id"]
    assert c["turns"] and all(t.get("say") and isinstance(t.get("expect"), dict) for t in c["turns"]), c["id"]
    for key in (c.get("pending") or {}):
        assert key in c["entries"] or key == "*", (c["id"], key)
inv = next(c for c in cases if c["id"] == "inventory_both_entries")
assert inv["entries"] == ["execute", "stream"] and inv["same_intent_across_entries"], "查库存必须两入口同意图"
print("cases=%d ok" % len(cases))
EOF
ok "cases.json 含 5 条必备话术, pending 只挂在存在的入口上"

echo "PASS: test-ai-live-probe"
