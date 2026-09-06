#!/usr/bin/env bash
# scripts/cron/cron-common.sh 的测试 —— 三条公共纪律各自能不能到达 rc=2, 以及阳性对照。
#
# 第三态平时永远不会自然发生(硬约束 4): 「缺命令」在开发机上构造不出来, 因为开发机
# 什么都有。唯一的办法是把 CRON_BIN_PATH 指到一个我们完全控制的目录 —— PATH 里加不回来
# 的东西才是真的没有。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
LIB="$ROOT_DIR/scripts/cron/cron-common.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$LIB" ] || fail "$LIB 不存在"
bash -n "$LIB" || fail "语法错误: $LIB"

# 受限 PATH: 只放被测函数**自己**要用的工具(date), 外加断言要用的。
FAKEBIN="$TMP/bin"; mkdir -p "$FAKEBIN"
for t in date cat grep head tr; do
    abs=$(command -v "$t") || fail "本机缺 $t"
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$t"; chmod +x "$FAKEBIN/$t"
done
PATH="$FAKEBIN" command -v ss >/dev/null 2>&1 && fail "受限 PATH 下仍能找到 ss, 构造失败"
PATH="$FAKEBIN" command -v python >/dev/null 2>&1 && fail "受限 PATH 下仍能找到 python, 构造失败"

ALERTS="$TMP/alerts.log"
RC=0; ERR=""
run_lib() {  # $1 = 要在 source 之后执行的 bash 片段
    rm -f "$ALERTS"
    ERR=$(CRON_BIN_PATH="$FAKEBIN" bash -c ". '$LIB'; cron_pin_path; $1" 2>&1 >/dev/null); RC=$?
    echo "    rc=$RC alerts: $( [ -f "$ALERTS" ] && tr '\n' '|' < "$ALERTS" || echo '<no file>')"
}
assert_alert() { grep -Fq -- "$1" "$ALERTS" || fail "$2: 告警文件里没有 [$1]"; }

# ── 1. 缺一个命令 ⇒ rc=2 + reason=cmd-not-found:<名> ────────────────────
run_lib "cron_require_cmds '$ALERTS' 'XXX' date ss"
[ "$RC" = 2 ] || fail "[1] 期望 rc=2 实得 $RC"
assert_alert "XXX INSTRUMENT DEAD" "[1]"; assert_alert "reason=cmd-not-found:ss" "[1]"
assert_alert "PATH=$FAKEBIN" "[1] 要把当时的 PATH 贴出来"
case "$ERR" in *"INSTRUMENT DEAD reason=cmd-not-found:ss"*) ;; *) fail "[1] stderr 里没有 reason 行: $ERR" ;; esac
ok "缺 ss ⇒ rc=2, 告警行 + stderr 都带 reason=cmd-not-found:ss"

# ── 2. 缺两个 ⇒ 都列出来, ⛔ 不只报第一个 ────────────────────────────────
run_lib "cron_require_cmds '$ALERTS' 'XXX' ss psql"
[ "$RC" = 2 ] || fail "[2] 期望 rc=2 实得 $RC"
assert_alert "reason=cmd-not-found:ss,psql" "[2]"
ok "缺 ss+psql ⇒ reason=cmd-not-found:ss,psql"

# ── 3. 阳性对照: 都在 ⇒ rc=0, 告警文件不被创建 ───────────────────────────
run_lib "cron_require_cmds '$ALERTS' 'XXX' date cat"
[ "$RC" = 0 ] || fail "[3] 期望 rc=0 实得 $RC: $ERR"
[ ! -f "$ALERTS" ] || fail "[3] 都在的时候不该写告警"
ok "命令都在 ⇒ rc=0, 不写告警"

# ── 4. venv 不在 ⇒ rc=2 reason=venv-missing ──────────────────────────────
run_lib "cron_activate_venv '$TMP/nope/bin/activate' '$ALERTS' 'LLM X'"
[ "$RC" = 2 ] || fail "[4] 期望 rc=2 实得 $RC"
assert_alert "LLM X INSTRUMENT DEAD" "[4]"; assert_alert "reason=venv-missing" "[4]"
ok "venv 不在 ⇒ rc=2 reason=venv-missing"

# ── 5. venv 在但 activate 之后仍没有 python ⇒ rc=2 (⛔ 不许掉回系统 python) ──
mkdir -p "$TMP/venv-nopy/bin"; printf ': nothing\n' > "$TMP/venv-nopy/bin/activate"
run_lib "cron_activate_venv '$TMP/venv-nopy/bin/activate' '$ALERTS' 'LLM X'"
[ "$RC" = 2 ] || fail "[5] 期望 rc=2 实得 $RC"
assert_alert "reason=python-missing-after-activate" "[5]"
ok "activate 后无 python ⇒ rc=2 reason=python-missing-after-activate"

# ── 6. 阳性对照: venv 带 python ⇒ rc=0 ───────────────────────────────────
mkdir -p "$TMP/venv-ok/bin"; printf '#!/bin/sh\nexit 0\n' > "$TMP/venv-ok/bin/python"; chmod +x "$TMP/venv-ok/bin/python"
printf 'export PATH="%s:$PATH"\n' "$TMP/venv-ok/bin" > "$TMP/venv-ok/bin/activate"
run_lib "cron_activate_venv '$TMP/venv-ok/bin/activate' '$ALERTS' 'LLM X' && command -v python"
[ "$RC" = 0 ] || fail "[6] 期望 rc=0 实得 $RC: $ERR"
[ ! -f "$ALERTS" ] || fail "[6] 不该写告警"
ok "venv 带 python ⇒ rc=0"

# ── 7. 告警目录不存在 ⇒ 仍 rc=2, 且 stderr 有 reason (写不进文件也不许静默) ──
run_lib "cron_require_cmds '$TMP/no-such-dir/alerts.log' 'XXX' ss"
[ "$RC" = 2 ] || fail "[7] 期望 rc=2 实得 $RC"
case "$ERR" in *"reason=cmd-not-found:ss"*) ;; *) fail "[7] stderr 里没有 reason: $ERR" ;; esac
case "$ERR" in *"no-such-dir"*) ;; *) fail "[7] 写不进告警文件的错误被吞了: $ERR" ;; esac
ok "告警目录不在 ⇒ rc=2, stderr 同时有 reason 与写文件错误"

echo "READING: RC2_REASONS_CONSTRUCTED=3 [cmd-not-found venv-missing python-missing-after-activate]"
echo "PASS: test-cron-common.sh ($PASSED 组断言)"
