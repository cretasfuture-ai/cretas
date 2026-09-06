#!/usr/bin/env bash
# 审计批 2 (docs/ops/cron-scripts-path-audit-2026-09-06.md) 的 7 个 venv 定时脚本, 每个各一条最小测试:
#   ① 假 PATH 缺 systemctl ⇒ rc=2 + reason=cmd-not-found:systemctl   (forecast 不用 systemctl, 跳过此项)
#   ② venv 不在            ⇒ rc=2 + reason=venv-missing, python 零调用 (⛔ 不掉回系统 python)
#   ③ .env.prod 不在       ⇒ rc=2 + reason=env-file-missing:env.prod
#   ④ 阳性对照: 三样都在 ⇒ rc=0, 告警里没有上面三个 reason, python 被调到
#
# 为什么值得每个都测一遍而不是只测 cron-common.sh: 这 7 个脚本共用同一个未守卫的
# `source venv-current/bin/activate`, venv-current 链接坏掉那天会**同时**掉回 3.6.8; 其中两态的
# 会把崩溃写成业务失败。测的是「每个脚本真的接上了守卫」(形态 B), 不是守卫本身。
#
# 假 python 只做两件事: 记调用、消费 stdin (ledger 那段是 `python - <<PY`)、exit 0。它不产出
# 各脚本的 /tmp 产出文件, 所以阳性对照里可能出现脚本**自己**的 INSTRUMENT DEAD(产出没写) ——
# 那是脚本原有的三态, 不是本批的三个 reason; 断言只看本批的三个键。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }
BASH_ABS=$(command -v bash) || fail "本机缺 bash"

# 脚本 | 环境变量前缀 | 告警措辞前缀 | 是否 require systemctl
CASES=(
  "daily-close-push.sh|DAILY_CLOSE|DAILY CLOSE|1"
  "flywheel-promotion-staleness-daily.sh|FLYWHEEL_STALE|FLYWHEEL PROMOTION STALENESS|1"
  "forecast-backtest-watch.sh|FORECAST_BT|FORECAST BACKTEST|0"
  "llm-provider-health-daily.sh|LLM_HEALTH|LLM HEALTH|1"
  "margin-parity-daily.sh|MARGIN_PARITY|MARGIN PARITY|1"
  "replay-equivalence-daily.sh|REPLAY_EQ|REPLAY EQUIV|1"
  "restaurant-unfreeze-watch.sh|UNFREEZE|UNFREEZE WATCH|1"
)

FAKEBIN="$TMP/bin"; STUB="$TMP/stub"; PYBIN="$TMP/pyvenv/bin"
mkdir -p "$FAKEBIN" "$STUB" "$PYBIN"
for t in date mktemp rm grep head cut tr sed cat dirname awk mkdir wc tail basename; do
    abs=$(command -v "$t") || fail "本机缺 $t"
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$t"; chmod +x "$FAKEBIN/$t"
done
export STUB
printf '#!/bin/sh\necho "systemctl $*" >> "$STUB/calls.log"; echo 0\n' > "$FAKEBIN/systemctl"
cat > "$PYBIN/python" <<'S'
#!/bin/sh
echo "python $*" >> "$STUB/calls.log"
case "$1" in -) cat > /dev/null ;; esac
exit 0
S
chmod +x "$FAKEBIN/systemctl" "$PYBIN/python"
PATH="$FAKEBIN" command -v python >/dev/null 2>&1 && fail "受限 PATH 不该直接有 python"

mk_pydir() {  # 假 venv: activate 只把假 python 放进 PATH
    rm -rf "$TMP/pydir"; mkdir -p "$TMP/pydir/venv-current/bin"
    printf 'export PATH="%s:$PATH"\n' "$PYBIN" > "$TMP/pydir/venv-current/bin/activate"
}
RC=0; ALERTS="$TMP/alerts.log"
run_script() {  # $1 script, $2 env-prefix
    local s=$1 p=$2
    rm -f "$ALERTS" "$STUB/calls.log" "$TMP/log" "$TMP/ledger"
    env -i PATH="$FAKEBIN" HOME="$TMP" STUB="$STUB" CRON_BIN_PATH="$FAKEBIN" \
        "${p}_PYDIR=$TMP/pydir" "${p}_ALERTS=$ALERTS" "${p}_LOG=$TMP/log" "${p}_LEDGER=$TMP/ledger" \
        "${p}_ENV_PROD=$TMP/env.prod" "${p}_ENV_EXTRA=$TMP/env.extra" \
        "$BASH_ABS" "$ROOT_DIR/scripts/cron/$s" > "$TMP/out" 2>&1; RC=$?
    echo "    $s rc=$RC alerts: $( [ -f "$ALERTS" ] && head -c 160 "$ALERTS" | tr '\n' '|' || echo '<none>')"
}
calls() { [ -f "$STUB/calls.log" ] && cat "$STUB/calls.log" || true; }
assert_alert() { grep -Fq -- "$1" "$ALERTS" || fail "$2: 告警里没有 [$1]"; }
assert_no_reason() { [ -f "$ALERTS" ] && grep -Eq 'reason=(cmd-not-found|venv-missing|env-file-missing|pydir-missing)' "$ALERTS" && fail "$1: 阳性对照里出现了本批的 reason: $(cat "$ALERTS")"; return 0; }

N_RC2=0
for entry in "${CASES[@]}"; do
    IFS='|' read -r script prefix wording needs_systemctl <<< "$entry"
    S="$ROOT_DIR/scripts/cron/$script"
    [ -s "$S" ] || fail "$S 不存在"; bash -n "$S" || fail "语法错误: $S"
    grep -vE '^[[:space:]]*#' "$S" | grep -q 'cron-common.sh' || fail "$script 没有 source cron-common.sh"
    grep -vE '^[[:space:]]*#' "$S" | grep -q 'source .*venv-current/bin/activate\|source "\$VENV_ACTIVATE"' && fail "$script 仍在裸 source venv activate"
    grep -vE '^[[:space:]]*#' "$S" | grep -q '\.env\.prod 2>/dev/null' && fail "$script 仍在吞 .env.prod 的缺失"
    grep -vE '^[[:space:]]*#' "$S" | grep -q 'systemctl show .* 2>/dev/null' && fail "$script 仍在吞 systemctl 的 stderr"
    echo "== $script"

    # ④ 阳性对照
    mk_pydir; printf 'X=1\n' > "$TMP/env.prod"; rm -f "$TMP/env.extra"
    run_script "$script" "$prefix"
    [ "$RC" = 0 ] || fail "[$script ④] 期望 rc=0 实得 $RC: $(tail -5 "$TMP/log" 2>/dev/null; tail -3 "$TMP/out")"
    assert_no_reason "[$script ④]"
    case "$(calls)" in *python*) ;; *) fail "[$script ④] python 没被调用: $(calls)" ;; esac
    ok "$script 阳性对照 ⇒ rc=0, python 被调"

    # ① 缺 systemctl
    if [ "$needs_systemctl" = 1 ]; then
        mv "$FAKEBIN/systemctl" "$TMP/systemctl.bak"
        run_script "$script" "$prefix"
        mv "$TMP/systemctl.bak" "$FAKEBIN/systemctl"
        [ "$RC" = 2 ] || fail "[$script ①] 期望 rc=2 实得 $RC"
        assert_alert "$wording INSTRUMENT DEAD" "[$script ①]"; assert_alert "reason=cmd-not-found:systemctl" "[$script ①]"
        case "$(calls)" in *python*) fail "[$script ①] 缺命令时不该跑到 python" ;; esac
        N_RC2=$((N_RC2 + 1))
        ok "$script 假 PATH 缺 systemctl ⇒ rc=2 reason=cmd-not-found:systemctl"
    fi

    # ② venv 不在
    rm -f "$TMP/pydir/venv-current/bin/activate"
    run_script "$script" "$prefix"
    [ "$RC" = 2 ] || fail "[$script ②] 期望 rc=2 实得 $RC"
    assert_alert "reason=venv-missing" "[$script ②]"
    case "$(calls)" in *python*) fail "[$script ②] venv 不在时不许调到任何 python (那就是掉回 3.6.8 的路)" ;; esac
    N_RC2=$((N_RC2 + 1))
    ok "$script venv 不在 ⇒ rc=2 reason=venv-missing, python 零调用"

    # ③ .env.prod 不在
    mk_pydir; rm -f "$TMP/env.prod"
    run_script "$script" "$prefix"
    [ "$RC" = 2 ] || fail "[$script ③] 期望 rc=2 实得 $RC"
    assert_alert "reason=env-file-missing:env.prod" "[$script ③]"
    case "$(calls)" in *python*) fail "[$script ③] env 不在时不该跑到 python" ;; esac
    N_RC2=$((N_RC2 + 1))
    ok "$script .env.prod 不在 ⇒ rc=2 reason=env-file-missing:env.prod"
done

# 可选 env 文件: 不在只记一句, ⛔ 不 rc=2 (daily-close / margin-parity 两个有 ENV_EXTRA)
for entry in "daily-close-push.sh|DAILY_CLOSE" "margin-parity-daily.sh|MARGIN_PARITY"; do
    IFS='|' read -r script prefix <<< "$entry"
    mk_pydir; printf 'X=1\n' > "$TMP/env.prod"; rm -f "$TMP/env.extra"
    run_script "$script" "$prefix"
    [ "$RC" = 0 ] || fail "[$script 可选] 期望 rc=0 实得 $RC"
    grep -q "INFO optional env file absent: $TMP/env.extra" "$TMP/log" || fail "[$script 可选] 日志里没有「可选文件不在」那一句"
    ok "$script 可选 env 不在 ⇒ 记一句, rc=0"
done

echo "READING: RC2_CONSTRUCTED=$N_RC2 (7 脚本 × {venv-missing, env-file-missing} + 6 × cmd-not-found:systemctl)"
[ "$N_RC2" -eq 20 ] || fail "rc=2 构造次数应为 20, 实得 $N_RC2"
echo "PASS: test-cron-venv-scripts-batch2.sh ($PASSED 组断言)"
