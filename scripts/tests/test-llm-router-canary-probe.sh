#!/usr/bin/env bash
# llm-router-billing-canary.sh **python heredoc 本体**的测试 —— 抽出来在假 common.llm_router / 假 httpx 下真跑。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (2026-09-06, #658 装 47 首跑)
# ═══════════════════════════════════════════════════════════════════════════
# 密钥源修好之后 (service-env exported=8), heredoc 第 29 行 `base, kenv = CFG[account]` 当场
# `KeyError: 'aistore'` —— 注册表 08 月已换成 aistore/deepseek/zhipu, 而 heredoc 里自己抄的 CFG 表
# 还是 7-01 的 5 个账号 (形态 D: 两份会漂)。python 默认 rc=1 ⇒ wrapper 写「FLAGGED rc=1」= 又一条
# 假账单告警。#658 的 PR body 明写「heredoc 本体无桩测试」—— 那一格就是这次漏的地方。
#
# 三处改法, 各一条用例钉着:
#   ① 端点/密钥只从 r._provider_config 取; 未知账号 (返回 ("","")) ⇒ UNKNOWN-PROVIDER 标记, ⛔ 不崩
#   ② sys.excepthook: 任何未捕获异常 ⇒ 打 `INSTRUMENT DEAD: unhandled …` + exit 2 (⛔ 不是 1)
#   ③ 全部 NO-KEY ⇒ exit 2 (#658 已有, 这里第一次真跑到)
#
# 抽取法: awk 取 `out=$(python - <<'PY'` 与 `^PY$` 之间的行。⛔ 不把 heredoc 抄一份进测试 —— 那又是形态 D。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CANARY="$ROOT_DIR/scripts/deploy/llm-router-billing-canary.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$CANARY" ] || fail "$CANARY 不存在"
PY=""
for c in python python3; do
    if "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then PY=$c; break; fi
done
if [ -z "$PY" ]; then echo "SKIPPED:本机没有可用的 python>=3.8, heredoc 本体量不了"; exit 77; fi
echo "READING: PYTHON=$($PY --version 2>&1)"

# ── 抽出 heredoc ──────────────────────────────────────────────────────────
PROBE="$TMP/probe.py"
awk '/^out=\$\(python - <<'"'"'PY'"'"'$/{f=1; next} /^PY$/{f=0} f' "$CANARY" > "$PROBE"
n=$(grep -c . "$PROBE"); [ "$n" -ge 40 ] || fail "抽出的 heredoc 只有 $n 行 —— awk 锚点没对上 (被测脚本的 heredoc 开头/结尾变了?)"
grep -q 'r._provider_config(account)' "$PROBE" || fail "heredoc 没走 r._provider_config"
# ⚠️ 只看非注释行 —— heredoc 注释里记着 `CFG[account]` 那段历史, 闸不能把自己的注释当被测对象。
grep -vE '^[[:space:]]*#' "$PROBE" | grep -q 'CFG\[account\]' && fail "heredoc 仍有自己的 CFG 表"
grep -q 'sys.excepthook' "$PROBE" || fail "heredoc 没装 excepthook"
$PY -c "import ast,sys; ast.parse(open(sys.argv[1],encoding='utf-8').read())" "$PROBE" || fail "抽出的 heredoc 不是合法 python"
echo "READING: HEREDOC_LINES=$n"

# ── 假模块: common.llm_router / httpx ─────────────────────────────────────
FAKE="$TMP/fake"; mkdir -p "$FAKE/common"; : > "$FAKE/common/__init__.py"
cat > "$FAKE/common/llm_router.py" <<'PYF'
import os, datetime
if os.environ.get("FAKE_CRASH"):
    raise RuntimeError("boom-at-import")
_pairs = [p.split("/") for p in os.environ.get("FAKE_MODELS", "").split(",") if p]
_SAFE_MODELS = {(a, m): None for a, m in _pairs}
_REGISTRY_AUDIT_DATE = datetime.date.today() - datetime.timedelta(days=int(os.environ.get("FAKE_AUDIT_DAYS_AGO", "1")))
_REGISTRY_MAX_AGE_DAYS = 21
_KNOWN = set(os.environ.get("FAKE_KNOWN", "aistore,deepseek,zhipu").split(","))
def _provider_config(account):
    # 与真 router 同形: 未知账号返回 ("", ""), ⛔ 不抛
    if account not in _KNOWN:
        return ("", "")
    return (f"http://fake/{account}", os.getenv(f"LLM_{account.upper()}_API_KEY", ""))
PYF
cat > "$FAKE/httpx.py" <<'PYF'
import os
class _Resp:
    def __init__(self): self.status_code = int(os.environ.get("FAKE_STATUS", "200")); self.text = os.environ.get("FAKE_BODY", "")
class Client:
    def __init__(self, timeout=None): pass
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def post(self, url, headers=None, json=None):
        if os.environ.get("FAKE_RAISE"): raise ConnectionError("fake-net-down")
        return _Resp()
PYF

RC=0; OUT=""
run_probe() {  # 参数 = KEY=VAL...
    OUT=$(env -i PATH="$PATH" PYTHONPATH="$FAKE" PYTHONIOENCODING=utf-8 "$@" "$PY" "$PROBE" 2>&1); RC=$?
    echo "    rc=$RC: $(printf '%s' "$OUT" | grep -E '^\[canary|INSTRUMENT DEAD|UNKNOWN-PROVIDER|NO-KEY|FLAGGED|PASS|STALE' | head -5 | tr '\n' '|')"
}
assert_out() { case "$OUT" in *"$1"*) ;; *) fail "$2: 输出里没有 [$1]: $OUT" ;; esac; }
assert_no_out() { case "$OUT" in *"$1"*) fail "$2: 输出里不该有 [$1]" ;; esac; }

# ── 1. 🔴 真注册表形状: aistore(有 key) + zhipu(无 key) + nope(未知账号) ⇒ 不崩, rc=1, 三种标记各归各 ──
run_probe FAKE_MODELS="aistore/m1,zhipu/m2,nope/m3" LLM_AISTORE_API_KEY=k
[ "$RC" = 1 ] || fail "[1] 期望 rc=1 实得 $RC: $OUT"
assert_out "[canary " "[1] 抬头"; assert_out "3 entries · 1 billing-safe" "[1]"
assert_out "nope/m3: UNKNOWN-PROVIDER" "[1]"; assert_out "zhipu/m2: NO-KEY(LLM_ZHIPU_API_KEY)" "[1]"
assert_no_out "Traceback" "[1] 不许崩"; assert_no_out "KeyError" "[1] 这就是首跑那个 KeyError"
ok "aistore/zhipu/未知账号混合 ⇒ 不崩, UNKNOWN-PROVIDER 与 NO-KEY 分开标, rc=1"

# ── 2. 全 billing-safe ⇒ rc=0 ─────────────────────────────────────────────
run_probe FAKE_MODELS="aistore/m1,deepseek/m2" LLM_AISTORE_API_KEY=k LLM_DEEPSEEK_API_KEY=k2
[ "$RC" = 0 ] || fail "[2] 期望 rc=0 实得 $RC: $OUT"; assert_out "canary PASS" "[2]"; assert_out "2 billing-safe" "[2]"
ok "全部 200 ⇒ rc=0 PASS"

# ── 3. 全部 NO-KEY ⇒ rc=2 INSTRUMENT DEAD (⛔ 不是 FLAGGED) ────────────────
run_probe FAKE_MODELS="aistore/m1,deepseek/m2"
[ "$RC" = 2 ] || fail "[3] 期望 rc=2 实得 $RC: $OUT"; assert_out "INSTRUMENT DEAD: 2/2 entries NO-KEY" "[3]"
assert_no_out "FLAGGED" "[3]"
ok "全部 NO-KEY ⇒ rc=2 INSTRUMENT DEAD (07-20 ~ 08-31 的真实状态)"

# ── 4. 🔴 未捕获异常 ⇒ rc=2 + INSTRUMENT DEAD: unhandled (⛔ 不是 python 默认的 1) ──
run_probe FAKE_MODELS="aistore/m1" LLM_AISTORE_API_KEY=k FAKE_CRASH=1
[ "$RC" = 2 ] || fail "[4] 期望 rc=2 实得 $RC: $OUT"; assert_out "INSTRUMENT DEAD: unhandled RuntimeError: boom-at-import" "[4]"
assert_out "Traceback" "[4] traceback 要保留给人看"
ok "import 时崩 ⇒ rc=2 INSTRUMENT DEAD: unhandled (首跑那条 KeyError 现在会长这样)"

# ── 5. HTTP 500 ⇒ FLAGGED rc=1; 网络异常两次 ⇒ ERR 标记 rc=1 ──────────────
run_probe FAKE_MODELS="aistore/m1" LLM_AISTORE_API_KEY=k FAKE_STATUS=500
[ "$RC" = 1 ] || fail "[5] 期望 rc=1 实得 $RC"; assert_out "HTTP 500" "[5]"
run_probe FAKE_MODELS="aistore/m1" LLM_AISTORE_API_KEY=k FAKE_RAISE=1
[ "$RC" = 1 ] || fail "[5b] 期望 rc=1 实得 $RC"; assert_out "ERR ConnectionError (x2)" "[5b] 要重试一次再标"
ok "HTTP 500 / 网络两次失败 ⇒ FLAGGED rc=1"

# ── 6. 403 FreeTierOnly / 402 exhausted 算 billing-safe ───────────────────
run_probe FAKE_MODELS="aistore/m1" LLM_AISTORE_API_KEY=k FAKE_STATUS=403 FAKE_BODY="FreeTierOnly"
[ "$RC" = 0 ] || fail "[6] 期望 rc=0 实得 $RC: $OUT"
ok "403 FreeTierOnly ⇒ billing-safe rc=0"

# ── 7. 注册表过期 ⇒ rc=3 ──────────────────────────────────────────────────
run_probe FAKE_MODELS="aistore/m1" LLM_AISTORE_API_KEY=k FAKE_AUDIT_DAYS_AGO=30
[ "$RC" = 3 ] || fail "[7] 期望 rc=3 实得 $RC"; assert_out "REGISTRY STALE" "[7]"
ok "审计快照 30d ⇒ rc=3 REGISTRY STALE"

echo "READING: RC2_REASONS_CONSTRUCTED=2 [all-no-key unhandled-exception]"
echo "PASS: test-llm-router-canary-probe.sh ($PASSED 组断言)"
