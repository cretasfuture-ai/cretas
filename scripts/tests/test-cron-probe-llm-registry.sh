#!/usr/bin/env bash
# scripts/cron/probe-llm-registry.sh 的测试 —— 审计批 3: 两态 → 三态。
#
# 原来 `rc!=0 ⇒ FOUND DEAD ENTRIES`: python 崩 (import / 密钥 / 网络) 的默认 rc 也是 1, 于是崩溃被
# 写成「注册表有 dead 条目」—— 与 canary 首跑 KeyError 被写成 FLAGGED 同形。
# 判据: python 只在跑到判定时打 `[probe] <date> 共探 N 个` 抬头 (见 probe_llm_registry.main),
# 抬头没打 = 崩在判定前 ⇒ rc=2; 共探 0 个 ⇒ rc=2; 抬头在 + rc=1 才是真 dead。
#
# 桩 python: 打不打抬头、共探几个、退几 由 $STUB 文件控制。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRIPT="$ROOT_DIR/scripts/cron/probe-llm-registry.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }
BASH_ABS=$(command -v bash) || fail "本机缺 bash"

[ -s "$SCRIPT" ] || fail "$SCRIPT 不存在"; bash -n "$SCRIPT" || fail "语法错误: $SCRIPT"
grep -vE '^[[:space:]]*#' "$SCRIPT" | grep -q 'cron-common.sh' || fail "没有 source cron-common.sh"
grep -vE '^[[:space:]]*#' "$SCRIPT" | grep -q 'source .*venv-current/bin/activate' && fail "仍在裸 source venv activate"

FAKEBIN="$TMP/bin"; STUB="$TMP/stub"; PYDIR="$TMP/pydir"; PYBIN="$TMP/pyvenv/bin"
mkdir -p "$FAKEBIN" "$STUB" "$PYDIR/venv-current/bin" "$PYBIN"
for t in date mktemp rm grep head cut tr sed cat dirname awk mkdir wc tail basename; do
    abs=$(command -v "$t") || fail "本机缺 $t"
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$t"; chmod +x "$FAKEBIN/$t"
done
export STUB
printf 'export PATH="%s:$PATH"\n' "$PYBIN" > "$PYDIR/venv-current/bin/activate"
cat > "$PYBIN/python" <<'S'
#!/bin/sh
echo "python $*" >> "$STUB/calls.log"
n=3; [ -f "$STUB/probed" ] && n=$(cat "$STUB/probed")
[ -f "$STUB/noheader" ] || echo "[probe] 2026-09-06 共探 $n 个 (账号,模型)"
[ -f "$STUB/noheader" ] && echo "Traceback (most recent call last):" && echo "KeyError: 'aistore'"
rc=0; [ -f "$STUB/rc" ] && rc=$(cat "$STUB/rc")
exit "$rc"
S
chmod +x "$PYBIN/python"
printf 'LLM_X=1\n' > "$TMP/env.prod"

ALERTS="$TMP/alerts"; LOG="$TMP/log"; RC=0
run_it() {
    rm -f "$ALERTS" "$LOG" "$STUB/calls.log"
    env -i PATH="$FAKEBIN" HOME="$TMP" STUB="$STUB" CRON_BIN_PATH="$FAKEBIN" \
        PROBE_LLM_PYDIR="$PYDIR" PROBE_LLM_ENV_FILE="$TMP/env.prod" PROBE_LLM_LOG="$LOG" PROBE_LLM_ALERTS="$ALERTS" \
        "$BASH_ABS" "$1" > "$TMP/out" 2>&1; RC=$?
    echo "    rc=$RC alerts: $( [ -f "$ALERTS" ] && head -c 160 "$ALERTS" | tr '\n' '|' || echo '<none>')"
}
reset() { rm -f "$STUB"/*; }
assert_alert() { grep -Fq -- "$1" "$ALERTS" || fail "$2: 告警里没有 [$1]"; }
RC2=""; note_rc2() { RC2="$RC2 $1"; }

# ── 1. 阳性对照: 抬头在, rc=0 ⇒ rc=0, 无告警 ─────────────────────────────
reset; run_it "$SCRIPT"
[ "$RC" = 0 ] || fail "[1] 期望 rc=0 实得 $RC: $(cat "$LOG" 2>/dev/null; cat "$TMP/out")"
[ ! -f "$ALERTS" ] || fail "[1] 无 dead 不该写告警"
grep -q '\[probe\] 2026-09-06 共探 3 个' "$LOG" || fail "[1] python 输出没进日志"
ok "阳性对照 ⇒ rc=0, 无告警, 探针输出进日志"

# ── 2. 抬头在 + rc=1 ⇒ 真 dead 条目, rc=1 FOUND DEAD ENTRIES ──────────────
reset; printf '1' > "$STUB/rc"; run_it "$SCRIPT"
[ "$RC" = 1 ] || fail "[2] 期望 rc=1 实得 $RC"; assert_alert "PROBE LLM REGISTRY FOUND DEAD ENTRIES rc=1" "[2]"; assert_alert "共探 3 个" "[2]"
grep -q 'INSTRUMENT DEAD' "$ALERTS" && fail "[2] 真 dead 不许写成 INSTRUMENT DEAD"
ok "抬头在 + rc=1 ⇒ rc=1 FOUND DEAD ENTRIES"

# ── 3. 🔴 崩在判定前 (没抬头, rc=1) ⇒ rc=2 probe-crashed, ⛔ 不是 FOUND DEAD ──
reset; : > "$STUB/noheader"; printf '1' > "$STUB/rc"; run_it "$SCRIPT"
[ "$RC" = 2 ] || fail "[3] 期望 rc=2 实得 $RC"; assert_alert "INSTRUMENT DEAD" "[3]"; assert_alert "reason=probe-crashed" "[3]"
grep -q 'FOUND DEAD' "$ALERTS" && fail "[3] 崩溃不许写成 FOUND DEAD ENTRIES (原两态的假读数)"
grep -q "KeyError: 'aistore'" "$LOG" || fail "[3] traceback 要留在日志里"
note_rc2 probe-crashed
ok "🔴 python 崩 (无抬头, rc=1) ⇒ rc=2 probe-crashed, 不写 FOUND DEAD"

# ── 4. 共探 0 个 ⇒ rc=2 no-samples ───────────────────────────────────────
reset; printf '0' > "$STUB/probed"; run_it "$SCRIPT"
[ "$RC" = 2 ] || fail "[4] 期望 rc=2 实得 $RC"; assert_alert "reason=no-samples" "[4]"
note_rc2 no-samples
ok "共探 0 个 ⇒ rc=2 no-samples (0 个 dead 在这里没意义)"

# ── 5. 抬头在但 rc=3 ⇒ rc=2 unexpected-rc ────────────────────────────────
reset; printf '3' > "$STUB/rc"; run_it "$SCRIPT"
[ "$RC" = 2 ] || fail "[5] 期望 rc=2 实得 $RC"; assert_alert "reason=unexpected-rc:3" "[5]"
note_rc2 unexpected-rc
ok "rc=3 ⇒ rc=2 unexpected-rc:3 (退出码开放集合, 不认识的不当成 dead)"

# ── 6. venv 不在 ⇒ rc=2, python 零调用 ───────────────────────────────────
reset; mv "$PYDIR/venv-current/bin/activate" "$TMP/act.bak"; run_it "$SCRIPT"
mv "$TMP/act.bak" "$PYDIR/venv-current/bin/activate"
[ "$RC" = 2 ] || fail "[6] 期望 rc=2 实得 $RC"; assert_alert "reason=venv-missing" "[6]"
[ ! -f "$STUB/calls.log" ] || fail "[6] venv 不在不该调到 python"
note_rc2 venv-missing
ok "venv 不在 ⇒ rc=2 venv-missing, python 零调用"

# ── 7. .env.prod 不在 ⇒ rc=2 (原来 exit 1, 与 dead 同码) ───────────────────
reset; mv "$TMP/env.prod" "$TMP/env.bak"; run_it "$SCRIPT"; mv "$TMP/env.bak" "$TMP/env.prod"
[ "$RC" = 2 ] || fail "[7] 期望 rc=2 实得 $RC"; assert_alert "reason=env-file-missing:env.prod" "[7]"
note_rc2 env-file-missing
ok ".env.prod 不在 ⇒ rc=2 env-file-missing (原两态里是 exit 1)"

# ── 8. cron-common.sh 不在旁边 ⇒ rc=2 ────────────────────────────────────
NOCOMMON="$TMP/nocommon/cron"; mkdir -p "$NOCOMMON"; cp "$SCRIPT" "$NOCOMMON/"
reset; run_it "$NOCOMMON/$(basename "$SCRIPT")"
[ "$RC" = 2 ] || fail "[8] 期望 rc=2 实得 $RC"; assert_alert "reason=cron-common-missing" "[8]"
note_rc2 cron-common-missing
ok "cron-common.sh 不在 ⇒ rc=2 cron-common-missing"

n=$(printf '%s' "$RC2" | wc -w | tr -d ' ')
echo "READING: RC2_REASONS_CONSTRUCTED=$n [${RC2# }]"
[ "$n" -ge 6 ] || fail "rc=2 原因只构造到 $n 种"
echo "PASS: test-cron-probe-llm-registry.sh ($PASSED 组断言)"
