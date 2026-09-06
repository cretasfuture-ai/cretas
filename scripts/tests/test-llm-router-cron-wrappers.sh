#!/usr/bin/env bash
# scripts/deploy/llm-router-{billing-canary,rerank-advisor,rules-gap-heatmap}.sh 的测试。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (2026-09-06 审计实测, prod 47)
# ═══════════════════════════════════════════════════════════════════════════
# canary 与 rerank 原来 `source venv38/bin/activate` —— venv38 早已不在。`set -u` 无 `-e`
# 下 source 失败**不中止**, 掉回系统 python 3.6.8, `from __future__ import annotations`
# SyntaxError。日志里各 5 个 SyntaxError(2026-08-03 ~ 08-31, 每周一一次), 而 crontab 那行
# `|| echo "LLM canary FLAGGED"` 把 5 次崩溃写成了 5 次账单告警。heatmap 只用 psql, 是三个里
# 唯一还活着的。
#
# 这里测的是 shell wrapper 的三态: 假 venv(activate 只把假 python 放进 PATH) + 假
# systemctl/psql, ⛔ 不跑真 httpx/真库。每种 rc=2 各构造一次。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CANARY="$ROOT_DIR/scripts/deploy/llm-router-billing-canary.sh"
RERANK="$ROOT_DIR/scripts/deploy/llm-router-rerank-advisor.sh"
HEATMAP="$ROOT_DIR/scripts/deploy/llm-router-rules-gap-heatmap.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

BASH_ABS=$(command -v bash) || fail "本机缺 bash"
for s in "$CANARY" "$RERANK" "$HEATMAP"; do
    [ -s "$s" ] || fail "$s 不存在"; bash -n "$s" || fail "语法错误: $s"
    grep -q 'cron-common.sh' "$s" || fail "$(basename "$s") 没有 source cron-common.sh"
    # ⚠️ 只看非注释行 —— 脚本注释里**记着** venv38 这段历史, 闸不能把自己的注释当被测对象。
    grep -vE '^[[:space:]]*#' "$s" | grep -q 'venv38' && fail "$(basename "$s") 仍引用 venv38 (它不存在)"
done

# ── 桩 ────────────────────────────────────────────────────────────────────
FAKEBIN="$TMP/bin"; STUB="$TMP/stub"; PYDIR="$TMP/pydir"; PYBIN="$TMP/pyvenv/bin"
mkdir -p "$FAKEBIN" "$STUB" "$PYDIR/venv-current/bin" "$PYBIN"
for t in date mktemp rm grep head cut tr sed cat dirname awk mkdir; do
    abs=$(command -v "$t") || fail "本机缺 $t"
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$t"; chmod +x "$FAKEBIN/$t"
done
export STUB
cat > "$FAKEBIN/systemctl" <<'S'
#!/bin/sh
echo "systemctl $*" >> "$STUB/calls.log"
echo "Environment=LLM_ALIYUN_A_API_KEY=k POSTGRES_PASSWORD=p POSTGRES_USER=u POSTGRES_HOST=h"
S
cat > "$FAKEBIN/psql" <<'S'
#!/bin/sh
echo "psql $*" >> "$STUB/calls.log"
cat > /dev/null
[ -f "$STUB/psql.rc" ] && exit "$(cat "$STUB/psql.rc")"; exit 0
S
chmod +x "$FAKEBIN/systemctl" "$FAKEBIN/psql"
# 假 venv: activate 只把假 python 放进 PATH(真 activate 也就是干这个)。
printf 'export PATH="%s:$PATH"\n' "$PYBIN" > "$PYDIR/venv-current/bin/activate"
cat > "$PYBIN/python" <<'S'
#!/bin/sh
echo "python $*" >> "$STUB/calls.log"
case "$1" in
  -c) [ -f "$STUB/python-old" ] && exit 1; exit 0 ;;      # 版本守卫
  --version) echo "Python 3.11.9"; exit 0 ;;
  -) cat > /dev/null; [ -f "$STUB/python.rc" ] && exit "$(cat "$STUB/python.rc")"; exit 0 ;;
esac
exit 0
S
chmod +x "$PYBIN/python"
# 桩自检: 在受限 PATH 下 python 找不到(只有 activate 之后才有), systemctl 找得到。
PATH="$FAKEBIN" command -v python >/dev/null 2>&1 && fail "受限 PATH 不该直接有 python"
PATH="$FAKEBIN" command -v systemctl >/dev/null 2>&1 || fail "受限 PATH 下应有 systemctl 桩"

ALERTS="$TMP/alerts.log"; RC=0
run_it() {  # $1 = 脚本
    rm -f "$ALERTS" "$STUB/calls.log"
    # env -i 之后 PATH 只剩假目录, bash 自己要用绝对路径调。
    env -i PATH="$FAKEBIN" HOME="$TMP" STUB="$STUB" CRON_BIN_PATH="$FAKEBIN" \
        LLM_ROUTER_PYDIR="$PYDIR" LLM_ROUTER_ALERTS="$ALERTS" "$BASH_ABS" "$1" > "$TMP/out" 2>&1; RC=$?
    echo "    $(basename "$1") rc=$RC alerts: $( [ -f "$ALERTS" ] && head -c 200 "$ALERTS" | tr '\n' '|' || echo '<none>')"
}
reset() { rm -f "$STUB"/*; }
calls() { [ -f "$STUB/calls.log" ] && cat "$STUB/calls.log" || true; }
assert_alert() { grep -Fq -- "$1" "$ALERTS" || fail "$2: 告警里没有 [$1]"; }
RC2=""
note_rc2() { RC2="$RC2 $1"; }

# ═════════ canary ═════════════════════════════════════════════════════════
reset; run_it "$CANARY"
[ "$RC" = 0 ] || fail "[c1] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"
[ ! -f "$ALERTS" ] || fail "[c1] PASS 不该写告警"
case "$(calls)" in *"python -"*) ;; *) fail "[c1] python 探针没被调用: $(calls)" ;; esac
ok "canary 阳性对照: venv 在 + python 0 ⇒ rc=0 无告警"

reset; printf '1' > "$STUB/python.rc"; run_it "$CANARY"
[ "$RC" = 1 ] || fail "[c2] 期望 rc=1 实得 $RC"; assert_alert "LLM CANARY FLAGGED rc=1" "[c2]"
grep -q "INSTRUMENT DEAD" "$ALERTS" && fail "[c2] 真 FLAGGED 不许写成 INSTRUMENT DEAD"
ok "canary python rc=1 ⇒ rc=1 FLAGGED (与崩溃措辞不同)"

reset; printf '3' > "$STUB/python.rc"; run_it "$CANARY"
[ "$RC" = 3 ] || fail "[c3] 期望 rc=3 实得 $RC"; assert_alert "注册表快照过期" "[c3]"
ok "canary python rc=3 ⇒ rc=3 注册表过期"

reset; mv "$PYDIR/venv-current/bin/activate" "$TMP/activate.bak"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[c4] 期望 rc=2 实得 $RC"; assert_alert "LLM CANARY INSTRUMENT DEAD" "[c4]"; assert_alert "reason=venv-missing" "[c4]"
case "$(calls)" in *python*) fail "[c4] venv 不在时不许调到任何 python(那就是掉回系统 python 的路)" ;; esac
mv "$TMP/activate.bak" "$PYDIR/venv-current/bin/activate"; note_rc2 venv-missing
ok "🔴 canary venv 不在 ⇒ rc=2 reason=venv-missing, python 零调用 (这就是 8-03 起每周崩的那条)"

reset; : > "$STUB/python-old"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[c5] 期望 rc=2 实得 $RC"; assert_alert "reason=python-too-old" "[c5]"
# ⚠️ 整行匹配 `python -`: 子串匹配会撞上 cron_dead 明细里那次 `python --version`。
grep -qx 'python -' "$STUB/calls.log" && fail "[c5] 版本太旧不该再跑探针"
note_rc2 python-too-old
ok "canary python 版本守卫不过 ⇒ rc=2 reason=python-too-old"

reset; mv "$FAKEBIN/systemctl" "$TMP/systemctl.bak"; run_it "$CANARY"
[ "$RC" = 2 ] || fail "[c6] 期望 rc=2 实得 $RC"; assert_alert "reason=cmd-not-found:systemctl" "[c6]"
mv "$TMP/systemctl.bak" "$FAKEBIN/systemctl"; note_rc2 cmd-not-found:systemctl
ok "canary 假 PATH 缺 systemctl ⇒ rc=2 reason=cmd-not-found:systemctl"

# ═════════ rerank ═════════════════════════════════════════════════════════
reset; run_it "$RERANK"
[ "$RC" = 0 ] || fail "[r1] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"; [ ! -f "$ALERTS" ] || fail "[r1] 不该写告警"
ok "rerank 阳性对照 ⇒ rc=0"
reset; printf '1' > "$STUB/python.rc"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[r2] 期望 rc=2 实得 $RC"; assert_alert "LLM RERANK INSTRUMENT DEAD" "[r2]"; assert_alert "reason=python-failed" "[r2]"
note_rc2 python-failed
ok "rerank python 崩 ⇒ rc=2 reason=python-failed (它是只读建议, 非 0 只可能是崩)"
reset; mv "$PYDIR/venv-current/bin/activate" "$TMP/activate.bak"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[r3] 期望 rc=2 实得 $RC"; assert_alert "reason=venv-missing" "[r3]"
mv "$TMP/activate.bak" "$PYDIR/venv-current/bin/activate"
ok "rerank venv 不在 ⇒ rc=2 reason=venv-missing"
reset; mv "$FAKEBIN/psql" "$TMP/psql.bak"; run_it "$RERANK"
[ "$RC" = 2 ] || fail "[r4] 期望 rc=2 实得 $RC"; assert_alert "reason=cmd-not-found:psql" "[r4]"
mv "$TMP/psql.bak" "$FAKEBIN/psql"; note_rc2 cmd-not-found:psql
ok "rerank 假 PATH 缺 psql ⇒ rc=2 reason=cmd-not-found:psql"

# ═════════ heatmap ════════════════════════════════════════════════════════
reset; run_it "$HEATMAP"
[ "$RC" = 0 ] || fail "[h1] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"; [ ! -f "$ALERTS" ] || fail "[h1] 不该写告警"
grep -q '规则缺口热力图' "$TMP/out" || fail "[h1] 报告抬头没打出来"
ok "heatmap 阳性对照 ⇒ rc=0"
reset; printf '2' > "$STUB/psql.rc"; run_it "$HEATMAP"
[ "$RC" = 2 ] || fail "[h2] 期望 rc=2 实得 $RC"; assert_alert "LLM RULES-GAP INSTRUMENT DEAD" "[h2]"; assert_alert "reason=psql-failed" "[h2]"
note_rc2 psql-failed
ok "heatmap psql 失败 ⇒ rc=2 reason=psql-failed"
reset; mv "$FAKEBIN/psql" "$TMP/psql.bak"; run_it "$HEATMAP"
[ "$RC" = 2 ] || fail "[h3] 期望 rc=2 实得 $RC"; assert_alert "reason=cmd-not-found:psql" "[h3]"
mv "$TMP/psql.bak" "$FAKEBIN/psql"
ok "heatmap 假 PATH 缺 psql ⇒ rc=2"

n=$(printf '%s' "$RC2" | wc -w | tr -d ' ')
echo "READING: RC2_REASONS_CONSTRUCTED=$n [${RC2# }]"
echo "PASS: test-llm-router-cron-wrappers.sh ($PASSED 组断言)"
