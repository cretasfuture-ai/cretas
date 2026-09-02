#!/usr/bin/env bash
# 2026-09-02 凌晨事故: /tmp/*.lock 存了 PID 就拒绝, 不核实持有者是否还活着 ——
# 或者核实了却因为调用方自己后设的 EXIT trap 覆盖掉 acquire_deploy_lock 注册的
# 清锁 trap, 导致正常退出(rc=0)也会漏删锁文件, 靠下一次的 stale-PID precheck 兜底。
# 01:21 的 web 锁(已死 PID)拒了部署 9 的 web 半程; 部署 10 自己已退出(rc=0)的
# release 锁拒了部署 11 —— 两次都靠人工 `rm` 锁文件重发。
#
# 本测试锁定三件事:
#   A1/A2: acquire_deploy_lock 本身对死/活 PID 的判定, 且日志里带 mtime(死)/
#          cmdline(活), 不是只报个光秃秃的 PID。
#   A3:    release-cretas.sh 的 drift-retry 用 `exec bash "$0" ...` 重新跑自己
#          (PID 不变) 时, 不能把自己已经持有的锁当成"另一个进程"拒绝。
#   A4:    调用方(release-cretas.sh / deploy-web-admin.sh)自己的 EXIT trap 必须
#          用 $ACQUIRED_DEPLOY_LOCK_FILE 显式清锁 —— 这是 R43 fix 的核心, 缺了它
#          的调用方模式会在本测试里被复现成"锁文件在正常退出后仍然存在"。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
COMMON="$ROOT_DIR/scripts/lib/deploy-common.sh"
RELEASE="$ROOT_DIR/scripts/deploy/release-cretas.sh"
WEB="$ROOT_DIR/scripts/deploy/deploy-web-admin.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }

CLEANUP_PIDS=()
on_test_exit() {
    local p
    for p in "${CLEANUP_PIDS[@]:-}"; do
        [ -n "$p" ] && kill -9 "$p" 2>/dev/null || true
    done
    rm -f /tmp/lockstale-a1.lock /tmp/lockstale-a2.lock /tmp/lockstale-a3.lock
}
trap on_test_exit EXIT

# shellcheck source=/dev/null
source "$COMMON"
# 自我重入那条日志是 DEBUG 级别(与既有"获取 flock/PID 锁"日志同级 —— 正常路径不
# 该吵), 默认 _LOG_LEVEL=INFO 会把它吃掉。测试要盯这条日志, 显式调到 DEBUG。
_LOG_LEVEL=DEBUG

# ---------------------------------------------------------------------------
# A1: 死 PID -> 自动回收, 日志里要看到 "reclaiming" 和一个 mtime。
# ---------------------------------------------------------------------------
rm -f /tmp/lockstale-a1.lock
sleep 300 &
DEAD_MSYS_PID=$!
CLEANUP_PIDS+=("$DEAD_MSYS_PID")
DEAD_WINPID=$(cat "/proc/$DEAD_MSYS_PID/winpid" 2>/dev/null || echo "$DEAD_MSYS_PID")
echo "$DEAD_WINPID" > /tmp/lockstale-a1.lock
kill -9 "$DEAD_MSYS_PID" 2>/dev/null || true
# 给 Windows 一点时间把进程表更新掉, 否则偶发仍能查到刚被杀的 PID。
for _ in $(seq 1 20); do
    deploy_pid_alive "$DEAD_WINPID" || break
    sleep 0.2
done
deploy_pid_alive "$DEAD_WINPID" && fail "A1 precondition: PID $DEAD_WINPID 应该已死, 但 deploy_pid_alive 仍报活"

A1_LOGFILE=$(mktemp)
# 🔴 不能用 A1_LOG=$(acquire_deploy_lock ...) —— command substitution 会 fork
# 一个子 shell, acquire_deploy_lock 里对全局变量 ACQUIRED_DEPLOY_LOCK_FILE 的赋值
# 只在那个子 shell 里可见, 回到父 shell 就没了。日志改成落文件, 函数本身直接跑
# 在当前 shell 里, 这样才量得到它真正设置的全局状态。
acquire_deploy_lock "lockstale-a1" > "$A1_LOGFILE" 2>&1
A1_RC=$?
A1_LOG=$(cat "$A1_LOGFILE")
rm -f "$A1_LOGFILE"
[ "$A1_RC" -eq 0 ] || fail "A1: 死 PID 应该被自动回收(rc=0), 实际 rc=$A1_RC, 日志: $A1_LOG"
echo "$A1_LOG" | grep -q "reclaiming" || fail "A1: 回收日志缺少 'reclaiming' 关键字: $A1_LOG"
echo "$A1_LOG" | grep -Eq "mtime [0-9]{4}-[0-9]{2}-[0-9]{2}" || fail "A1: 回收日志缺少 mtime: $A1_LOG"
[ "$ACQUIRED_DEPLOY_LOCK_FILE" = "/tmp/lockstale-a1.lock" ] \
    || fail "A1: ACQUIRED_DEPLOY_LOCK_FILE 未按预期设置, 实际='$ACQUIRED_DEPLOY_LOCK_FILE'"
echo "PASS A1: 死 PID 自动回收, 日志带 mtime"
rm -f /tmp/lockstale-a1.lock

# ---------------------------------------------------------------------------
# A2: 活 PID -> 照常拒绝, 且日志里要看到 cmdline。
# ---------------------------------------------------------------------------
rm -f /tmp/lockstale-a2.lock
sleep 300 &
ALIVE_MSYS_PID=$!
CLEANUP_PIDS+=("$ALIVE_MSYS_PID")
ALIVE_WINPID=$(cat "/proc/$ALIVE_MSYS_PID/winpid" 2>/dev/null || echo "$ALIVE_MSYS_PID")
echo "$ALIVE_WINPID" > /tmp/lockstale-a2.lock

# 🔴 acquire_deploy_lock 这里预期返回 1(拒绝) —— 在 `set -e` 下,
# `VAR=$(cmd)` 形式的赋值语句一旦 cmd 非零会立刻杀掉整个测试脚本(不会走到下面
# 的 fail(), 只会带着一个 EXIT=1 悄悄退出, 看起来像"脚本跑丢了"而不是"断言红了")。
# 必须先 set +e 拿到返回码, 再 set -e 回来。
set +e
A2_LOG=$(acquire_deploy_lock "lockstale-a2" 2>&1)
A2_RC=$?
set -e
[ "$A2_RC" -eq 1 ] || fail "A2: 活 PID 应该被拒绝(rc=1), 实际 rc=$A2_RC"
echo "$A2_LOG" | grep -q "cmdline:" || fail "A2: 拒绝日志缺少 cmdline 字段: $A2_LOG"
echo "PASS A2: 活 PID 照常拒绝, 日志带 cmdline"
kill -9 "$ALIVE_MSYS_PID" 2>/dev/null || true
rm -f /tmp/lockstale-a2.lock

# ---------------------------------------------------------------------------
# A3: 自我重入(模拟 release-cretas.sh 的 drift-retry `exec bash "$0" ...`,
#     同一个 PID 在同一进程里再次调用 acquire_deploy_lock) 不能把自己拒绝掉。
# ---------------------------------------------------------------------------
rm -f /tmp/lockstale-a3.lock
acquire_deploy_lock "lockstale-a3" >/dev/null 2>&1
FIRST_RC=$?
[ "$FIRST_RC" -eq 0 ] || fail "A3 precondition: 第一次获取锁应该成功, 实际 rc=$FIRST_RC"
A3_LOG=$(acquire_deploy_lock "lockstale-a3" 2>&1)
A3_RC=$?
[ "$A3_RC" -eq 0 ] \
    || fail "A3: exec 自我重入(同 PID 二次获取同名锁)必须放行(rc=0), 实际 rc=$A3_RC — 这正是把自己当'另一个进程'拒绝的那个 bug, 日志: $A3_LOG"
echo "$A3_LOG" | grep -qi "自我重入" || fail "A3: 未按预期走自我重入分支, 日志: $A3_LOG"
echo "PASS A3: exec 自我重入不自杀"
rm -f /tmp/lockstale-a3.lock

# ---------------------------------------------------------------------------
# A4: 调用方在 acquire_deploy_lock 之后再设一次 EXIT trap 时(release-cretas.sh /
#     deploy-web-admin.sh 都是这个形状), 必须用 $ACQUIRED_DEPLOY_LOCK_FILE 自己
#     清锁, 否则正常退出后锁文件会留在 /tmp 里。
# ---------------------------------------------------------------------------
CALLER_SCRIPT="$ROOT_DIR/scripts/tests/.tmp-lock-caller-$$.sh"
cat > "$CALLER_SCRIPT" <<CALLERSH
#!/usr/bin/env bash
set -euo pipefail
source "$COMMON"
acquire_deploy_lock "lockstale-a4" || exit 1
my_exit() {
    local rc=\$?
    rm -f "\${ACQUIRED_DEPLOY_LOCK_FILE:-/tmp/lockstale-a4.lock}" 2>/dev/null || true
}
trap my_exit EXIT
exit 0
CALLERSH
chmod +x "$CALLER_SCRIPT"
rm -f /tmp/lockstale-a4.lock
bash "$CALLER_SCRIPT"
[ ! -f /tmp/lockstale-a4.lock ] \
    || fail "A4: 调用方自己的 EXIT trap 清了锁, 但锁文件仍在 —— 测试自身有问题"
rm -f "$CALLER_SCRIPT"
echo "PASS A4 (正例): 调用方自己清锁 -> 锁文件按预期消失"

# 反例(证明 A4 的断言真的在守东西, 不是空气): 调用方后设的 EXIT trap
# 【不】清锁 —— 复现 release-cretas.sh / deploy-web-admin.sh 修复前的原始形状,
# 锁文件必须留下来。这一段本身不是"新增断言", 是用来证明上面那条断言并非
# 对任何调用方模式都恒为 true。
BUGGY_CALLER_SCRIPT="$ROOT_DIR/scripts/tests/.tmp-lock-caller-buggy-$$.sh"
cat > "$BUGGY_CALLER_SCRIPT" <<CALLERSH
#!/usr/bin/env bash
set -euo pipefail
source "$COMMON"
acquire_deploy_lock "lockstale-a4b" || exit 1
my_exit() {
    :  # 复现修复前的 on_exit: 只写报告, 不清锁 —— bash 的 trap 不叠加,
       # 这一条会整个替换掉 acquire_deploy_lock 注册的清锁 trap。
}
trap my_exit EXIT
exit 0
CALLERSH
chmod +x "$BUGGY_CALLER_SCRIPT"
rm -f /tmp/lockstale-a4b.lock
bash "$BUGGY_CALLER_SCRIPT"
[ -f /tmp/lockstale-a4b.lock ] \
    || fail "A4 反例: 预期复现'调用方后设 trap 覆盖清锁 trap'这个原始 bug, 但锁文件却被清掉了 —— 说明上面 A4 的断言没有守住任何东西(它对任何调用方形状都会通过)"
echo "PASS A4 (反例): 不清锁的调用方形状确实会漏留锁文件 -- 证明正例的断言不是空气"
rm -f "$BUGGY_CALLER_SCRIPT" /tmp/lockstale-a4b.lock

# ---------------------------------------------------------------------------
# 结构闸: 真实的 release-cretas.sh / deploy-web-admin.sh 必须落在 A4 的"正例"
# 那个形状里, 不是"反例"那个形状 —— 光测试模式对不代表真文件真的改了。
# ---------------------------------------------------------------------------
grep -Pzo '(?s)on_exit\(\).*?\n\}' "$RELEASE" | tr -d '\0' \
    | grep -q 'rm -f "\${ACQUIRED_DEPLOY_LOCK_FILE' \
    || fail "结构闸: release-cretas.sh 的 on_exit() 里没找到用 \$ACQUIRED_DEPLOY_LOCK_FILE 清锁的那一行"
echo "PASS 结构闸: release-cretas.sh on_exit() 含清锁兜底"

grep -Pzo '(?s)web_deploy_on_exit\(\).*?\n\}' "$WEB" | tr -d '\0' \
    | grep -q 'rm -f "\${ACQUIRED_DEPLOY_LOCK_FILE' \
    || fail "结构闸: deploy-web-admin.sh 的 web_deploy_on_exit() 里没找到用 \$ACQUIRED_DEPLOY_LOCK_FILE 清锁的那一行"
echo "PASS 结构闸: deploy-web-admin.sh web_deploy_on_exit() 含清锁兜底"

echo "PASS: deploy lock 正确核实持有者存活状态, 自我重入不自杀, 调用方清锁兜底就位"
