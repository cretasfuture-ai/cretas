#!/usr/bin/env bash
# scripts/cron/async-failure-backlog-daily.sh 的测试 —— 假 PATH 下构造「缺 ss」。
#
# 它是 soft-delete-orphans-watch.sh 那次 15 天空转的**同款隐患**(inventory 里早就标了
# 「与 soft-delete 同款 ss/PATH 隐患, 另立任务」): root crontab 没有 PATH=, `ss` 只在
# /usr/sbin, 原来 `ss -lntp 2>/dev/null` 把 command not found 吞掉 ⇒ PID 空 ⇒ 空密码去
# 连库 ⇒ 记成「台账读不到」。三种事实(找不到 ss / 后端没监听 / 库连不上)必须三个 reason。
#
# 桩: ss(打印一行 LISTEN pid=4242) / psql(输出与退出码由文件控制, 记录调用)。
# /proc/4242/environ 在测试机上读不到 ⇒ 脚本跳过 eval, 靠 ASYNC_FAILURE_DB_* 走通。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRIPT="$ROOT_DIR/scripts/cron/async-failure-backlog-daily.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$SCRIPT" ] || fail "$SCRIPT 不存在"
bash -n "$SCRIPT" || fail "语法错误: $SCRIPT"
BASH_ABS=$(command -v bash) || fail "本机缺 bash"
grep -q 'cron-common.sh' "$SCRIPT" || fail "脚本没有 source cron-common.sh"
# ⚠️ 只看非注释行 —— 脚本注释里**引用**了原来那句坏写法, 闸不能把自己的注释当被测对象。
grep -vE '^[[:space:]]*#' "$SCRIPT" | grep -q 'ss -lntp 2>/dev/null' && fail "ss 的 stderr 仍被 2>/dev/null 吞掉"

FAKEBIN="$TMP/bin"; STUB="$TMP/stub"; mkdir -p "$FAKEBIN" "$STUB"
for t in date mktemp rm grep head cut tr sed printf cat dirname; do
    abs=$(command -v "$t") || fail "本机缺 $t"
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$t"; chmod +x "$FAKEBIN/$t"
done
export STUB
cat > "$FAKEBIN/ss" <<'S'
#!/bin/sh
echo "ss $*" >> "$STUB/calls.log"
[ -f "$STUB/ss.rc" ] && rc=$(cat "$STUB/ss.rc") || rc=0
[ -f "$STUB/ss.out" ] && cat "$STUB/ss.out"
[ -f "$STUB/ss.err" ] && cat "$STUB/ss.err" >&2
exit "$rc"
S
cat > "$FAKEBIN/psql" <<'S'
#!/bin/sh
echo "psql $*" >> "$STUB/calls.log"
[ -f "$STUB/psql.rc" ] && rc=$(cat "$STUB/psql.rc") || rc=0
[ -f "$STUB/psql.out" ] && cat "$STUB/psql.out"
exit "$rc"
S
chmod +x "$FAKEBIN/ss" "$FAKEBIN/psql"
GOOD_SS='LISTEN 0 100 *:10010 *:* users:(("java",pid=4242,fd=30))'
reset() { rm -f "$STUB"/*; printf '%s\n' "$GOOD_SS" > "$STUB/ss.out"; printf '0|10\n' > "$STUB/psql.out"; }
ALERTS="$TMP/alerts.log"
RC=0
run_it() {  # 额外 env 以 K=V 形式传入
    rm -f "$ALERTS" "$STUB/calls.log"
    # env -i 之后 PATH 只剩假目录, bash 自己要用绝对路径调。
    env -i PATH="$FAKEBIN" HOME="$TMP" STUB="$STUB" CRON_BIN_PATH="$FAKEBIN" \
        ASYNC_FAILURE_ALERTS="$ALERTS" "$@" "$BASH_ABS" "$SCRIPT" > "$TMP/out" 2>&1; RC=$?
    echo "    rc=$RC alerts: $( [ -f "$ALERTS" ] && head -c 240 "$ALERTS" | tr '\n' '|' || echo '<none>')"
}
calls() { [ -f "$STUB/calls.log" ] && cat "$STUB/calls.log" || true; }
assert_alert() { grep -Fq -- "$1" "$ALERTS" || fail "$2: 告警里没有 [$1]"; }

# ── 1. 阳性对照: ss 在、psql 说 0 条未处理 ⇒ rc=0 ─────────────────────────
reset
run_it ASYNC_FAILURE_DB_PASS=pw
[ "$RC" = 0 ] || fail "[1] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"
[ ! -f "$ALERTS" ] || fail "[1] 无积压不该写告警"
case "$(calls)" in *"psql"*) ;; *) fail "[1] psql 没被调用" ;; esac
ok "ss 在 + 0 条未处理 ⇒ rc=0"

# ── 2. 有积压 ⇒ rc=1 XXX BACKLOG ──────────────────────────────────────────
reset; printf '3|10\n' > "$STUB/psql.out"
run_it ASYNC_FAILURE_DB_PASS=pw
[ "$RC" = 1 ] || fail "[2] 期望 rc=1 实得 $RC"; assert_alert "XXX BACKLOG" "[2]"
ok "3 条未处理 ⇒ rc=1 BACKLOG"

# ── 3. 🔴 核心: 假 PATH 下没有 ss ⇒ rc=2 reason=cmd-not-found:ss, psql 根本不被调 ──
reset; rm -f "$FAKEBIN/ss"
run_it ASYNC_FAILURE_DB_PASS=pw
[ "$RC" = 2 ] || fail "[3] 期望 rc=2 实得 $RC"
assert_alert "XXX INSTRUMENT DEAD" "[3]"; assert_alert "reason=cmd-not-found:ss" "[3]"
case "$(calls)" in *psql*) fail "[3] 缺 ss 时不该去连库(那正是原来「台账读不到」的假读数来源)" ;; esac
ok "缺 ss ⇒ rc=2 reason=cmd-not-found:ss, 未连库"
# 复原 ss 桩
cat > "$FAKEBIN/ss" <<'S'
#!/bin/sh
echo "ss $*" >> "$STUB/calls.log"
[ -f "$STUB/ss.rc" ] && rc=$(cat "$STUB/ss.rc") || rc=0
[ -f "$STUB/ss.out" ] && cat "$STUB/ss.out"
[ -f "$STUB/ss.err" ] && cat "$STUB/ss.err" >&2
exit "$rc"
S
chmod +x "$FAKEBIN/ss"

# ── 4. ss 在但端口上没人 且 没显式密码 ⇒ rc=2 reason=no-listener-on-ports ──
reset; : > "$STUB/ss.out"; printf 'ss: some warning\n' > "$STUB/ss.err"
run_it
[ "$RC" = 2 ] || fail "[4] 期望 rc=2 实得 $RC"
assert_alert "reason=no-listener-on-ports" "[4]"; assert_alert "ss: some warning" "[4] ss 的 stderr 要贴出来"
ok "端口无 pid 且无显式密码 ⇒ rc=2 reason=no-listener-on-ports (ss stderr 不吞)"

# ── 4b. 端口无 pid 但显式给了密码 ⇒ 继续用显式凭证连库(测试/手工场景) ──
reset; : > "$STUB/ss.out"
run_it ASYNC_FAILURE_DB_PASS=pw
[ "$RC" = 0 ] || fail "[4b] 期望 rc=0 实得 $RC: $(cat "$TMP/out")"
ok "端口无 pid 但显式密码在 ⇒ 照常连库"

# ── 5. 缺 psql ⇒ rc=2 reason=cmd-not-found:psql ──────────────────────────
reset; rm -f "$FAKEBIN/psql"
run_it ASYNC_FAILURE_DB_PASS=pw
[ "$RC" = 2 ] || fail "[5] 期望 rc=2 实得 $RC"; assert_alert "reason=cmd-not-found:psql" "[5]"
cat > "$FAKEBIN/psql" <<'S'
#!/bin/sh
echo "psql $*" >> "$STUB/calls.log"
[ -f "$STUB/psql.rc" ] && rc=$(cat "$STUB/psql.rc") || rc=0
[ -f "$STUB/psql.out" ] && cat "$STUB/psql.out"
exit "$rc"
S
chmod +x "$FAKEBIN/psql"
ok "缺 psql ⇒ rc=2 reason=cmd-not-found:psql"

# ── 6. psql 连不上 ⇒ rc=2 (既有分支, 措辞「台账读不到」) ─────────────────
reset; printf '2' > "$STUB/psql.rc"; printf 'FATAL: password authentication failed\n' > "$STUB/psql.out"
run_it ASYNC_FAILURE_DB_PASS=pw
[ "$RC" = 2 ] || fail "[6] 期望 rc=2 实得 $RC"; assert_alert "台账读不到" "[6]"
ok "psql 失败 ⇒ rc=2 台账读不到 (与「缺 ss」措辞不同)"

echo "READING: RC2_REASONS_CONSTRUCTED=4 [cmd-not-found:ss no-listener-on-ports cmd-not-found:psql psql-failed]"
echo "PASS: test-cron-async-failure-backlog.sh ($PASSED 组断言)"
