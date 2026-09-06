#!/usr/bin/env bash
# 2026-09-02 凌晨事故: /tmp/*.lock 存了 PID 就拒绝, 不核实持有者是否还活着 ——
# 或者核实了却因为调用方自己后设的 EXIT trap 覆盖掉 acquire_deploy_lock 注册的
# 清锁 trap, 导致正常退出(rc=0)也会漏删锁文件, 靠下一次的 stale-PID precheck 兜底。
# 01:21 的 web 锁(已死 PID)拒了部署 9 的 web 半程; 部署 10 自己已退出(rc=0)的
# release 锁拒了部署 11 —— 两次都靠人工 `rm` 锁文件重发。
#
# 本测试锁定四件事:
#   A1/A2: acquire_deploy_lock 本身对死/活 PID 的判定, 且日志里带 mtime(死)/
#          cmdline(活), 不是只报个光秃秃的 PID。
#   A3:    release-cretas.sh 的 drift-retry 用 `exec bash "$0" ...` 重新跑自己
#          (PID 不变) 时, 不能把自己已经持有的锁当成"另一个进程"拒绝。
#   A4:    调用方(release-cretas.sh / deploy-web-admin.sh)自己的 EXIT trap 必须
#          用 $ACQUIRED_DEPLOY_LOCK_FILE 显式清锁 —— 这是 R43 fix 的核心, 缺了它
#          的调用方模式会在本测试里被复现成"锁文件在正常退出后仍然存在"。
#   A5:    (2026-09-02 跟进, PR #518 终审后的非阻塞发现) A3 那条自我重入判据
#          光比"锁文件里的 PID == 我的 PID"不够 —— PID 是操作系统回收再分配的,
#          一次崩溃的 release 留下死锁文件, 之后系统把那个 PID 派给一个全新、
#          无关的 release 进程纯属巧合命中同一个数字, 若判据只看 PID 相等就会
#          把它误判成"重入"而 return 0, 完全跳过 flock/PID 文件那层真正的互斥
#          (5/30 事故的形态: 两个 deploy 并发)。判据加了合取
#          $CRETAS_DEPLOY_REENTRY_PID == 我的 PID(只有 exec 重入路径会设它),
#          本节验证缺这个环境变量时不会走重入分支。
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
    rm -f /tmp/lockstale-a1.lock /tmp/lockstale-a2.lock /tmp/lockstale-a3.lock \
          /tmp/lockstale-a5.lock /tmp/lockstale-a6.lock
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
# A2: 「另一个进程正持锁」必须被拒绝, 且日志里要看到持有者的 PID + cmdline。
#
# 🔴 2026-09-06 实测: acquire_deploy_lock 有**两套互斥实现**, 判据不一样, 必须
#    按实际生效的那一套来量 —— 原版只写了 PID 文件那一套, 于是在 CI(ubuntu, 有
#    flock)上 rc=0 而报「活 PID 应该被拒绝」, 在 Windows Git Bash(没有 flock)上绿。
#
#    · **PID 文件模式**(Git Bash: 没有 flock): 文件里的 PID 活着 = 拒绝。
#    · **flock 模式**(Linux/macOS): 真正的互斥是 flock 本身; 文件里的 PID 只是
#      给人看的线索。「文件里有个活 PID 但没人持 flock」恰恰是 **PID 回收撞库**
#      那个假阳性 —— 一次崩掉的部署留下死锁文件, 系统把那个 PID 号派给了别的
#      无关进程。在这种形状下**放行才是对的**(2026-09-02 凌晨: 陈旧锁两次拒掉
#      健康部署, 靠人工 rm 才发出去)。
#
#    ⇒ 两边量的都是同一个性质「有人真持锁就必须被拒, 且日志点名持有者」,
#      只是持锁的表示不同。⛔ 不用 exit 77 跳过 —— 这条在两种环境里都量得了。
# ---------------------------------------------------------------------------
rm -f /tmp/lockstale-a2.lock
if command -v flock >/dev/null 2>&1; then
    echo "A2_MUTEX_MODE=flock"
    # 真持有者: 自己走一遍 acquire_deploy_lock(所以 PID 文件里是它的 PID),
    # 然后 `exec sleep` —— 用 exec 而不是 `sleep &`, 这样只有一个进程持着 fd 200,
    # kill 掉它 flock 就真的释放(⛔ 起子进程的话父进程死了子进程还按着锁)。
    A2_HOLDER="$ROOT_DIR/scripts/tests/.tmp-lock-a2-holder-$$.sh"
    A2_MARK="/tmp/lockstale-a2.held.$$"
    rm -f "$A2_MARK"
    cat > "$A2_HOLDER" <<HOLDERSH
#!/usr/bin/env bash
set -euo pipefail
source "$COMMON"
acquire_deploy_lock "lockstale-a2" >/dev/null 2>&1 || exit 1
touch "$A2_MARK"
exec sleep 300
HOLDERSH
    bash "$A2_HOLDER" &
    A2_HOLDER_PID=$!
    CLEANUP_PIDS+=("$A2_HOLDER_PID")
    for _ in $(seq 1 100); do
        [ -f "$A2_MARK" ] && break
        sleep 0.1
    done
    [ -f "$A2_MARK" ] || fail "A2 precondition: 持有者始终没拿到锁"
    set +e
    A2_LOG=$(acquire_deploy_lock "lockstale-a2" 2>&1)
    A2_RC=$?
    set -e
    [ "$A2_RC" -eq 1 ] || fail "A2: 有进程真持着 flock, 第二个必须被拒(rc=1), 实际 rc=$A2_RC, 日志: $A2_LOG"
    echo "$A2_LOG" | grep -q "cmdline:" || fail "A2: 拒绝日志缺少 cmdline 字段: $A2_LOG"
    # 🔴 这一条只有在 acquire_deploy_lock **不再于判定前截断锁文件**时才成立 ——
    #    旧写法 `exec 200>"$lock_file"` 会把持有者的 PID 抹掉, 日志退化成
    #    "PID , cmdline: unknown"。它同时也是 mutex-coverage 那个「锁永久卡住」
    #    的根因(空 PID ⇒ 下次 stale 回收整段被跳过)。
    echo "$A2_LOG" | grep -q "PID $A2_HOLDER_PID" \
        || fail "A2: 拒绝日志里没点名持有者 PID $A2_HOLDER_PID —— 锁文件在判定前被截断了? 日志: $A2_LOG"
    echo "PASS A2: 真持有者被拒, 日志带持有者 PID + cmdline"
    kill -9 "$A2_HOLDER_PID" 2>/dev/null || true
    wait "$A2_HOLDER_PID" 2>/dev/null || true
    rm -f "$A2_HOLDER" "$A2_MARK"
else
    echo "A2_MUTEX_MODE=pidfile"
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
fi
rm -f /tmp/lockstale-a2.lock

# ---------------------------------------------------------------------------
# A3: 自我重入(模拟 release-cretas.sh 的 drift-retry `exec bash "$0" ...`,
#     同一个 PID 在同一进程里再次调用 acquire_deploy_lock) 不能把自己拒绝掉。
#     🔴 修复后判据是合取, 第二次调用前必须像 recover_from_main_drift() 那样
#     把 CRETAS_DEPLOY_REENTRY_PID 设成自己的 PID(exec 保留环境, 这正是真实
#     重入时环境变量会存在且值匹配的原因) —— 不设的话这次调用会落进 A5 那种
#     "巧合撞见自己 PID 但没有重入凭证"的形状, 见 A5。
# ---------------------------------------------------------------------------
rm -f /tmp/lockstale-a3.lock
acquire_deploy_lock "lockstale-a3" >/dev/null 2>&1
FIRST_RC=$?
[ "$FIRST_RC" -eq 0 ] || fail "A3 precondition: 第一次获取锁应该成功, 实际 rc=$FIRST_RC"
export CRETAS_DEPLOY_REENTRY_PID=$$
A3_LOG=$(acquire_deploy_lock "lockstale-a3" 2>&1)
A3_RC=$?
unset CRETAS_DEPLOY_REENTRY_PID
[ "$A3_RC" -eq 0 ] \
    || fail "A3: exec 自我重入(同 PID 二次获取同名锁)必须放行(rc=0), 实际 rc=$A3_RC — 这正是把自己当'另一个进程'拒绝的那个 bug, 日志: $A3_LOG"
echo "$A3_LOG" | grep -qi "自我重入" || fail "A3: 未按预期走自我重入分支, 日志: $A3_LOG"
echo "PASS A3: exec 自我重入(带重入凭证)不自杀"
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
# A5 (安全闸, 2026-09-02 跟进): OS 把一个死进程的 PID 回收派给一个全新、无关的
#     release 进程时, "锁文件里的 PID == 我的 PID" 这个巧合不能被判成自我重入
#     直接放行 —— 那会跳过 flock/PID 文件那层真正的互斥, 第三个进程还能并发
#     拿到锁(5/30 事故的形态: 两个 deploy 上传同一份共享 jar/dist)。
#
#     真实的 OS PID 回收没法在测试里可靠制造, 用桩固定 deploy_lock_pid() 的
#     返回值来构造这个巧合 —— 锁文件里的"死 PID"和这次调用 deploy_lock_pid()
#     算出来的"我的 PID"恰好是同一个字符串, 但**刻意不设**
#     CRETAS_DEPLOY_REENTRY_PID, 模拟"这个新进程从没走过 exec 重入这条路"。
# ---------------------------------------------------------------------------
rm -f /tmp/lockstale-a5.lock
unset CRETAS_DEPLOY_REENTRY_PID 2>/dev/null || true
RECYCLED_PID="999999"
echo "$RECYCLED_PID" > /tmp/lockstale-a5.lock

# 桩替换真实函数(bash 允许在当前 shell 里重定义已 source 进来的函数)。
deploy_lock_pid() { echo "$RECYCLED_PID"; }
deploy_pid_alive() { [ "$1" != "$RECYCLED_PID" ]; }   # RECYCLED_PID 判死 -> 应走 stale 回收

A5_LOGFILE=$(mktemp)
acquire_deploy_lock "lockstale-a5" > "$A5_LOGFILE" 2>&1
A5_RC=$?
A5_LOG=$(cat "$A5_LOGFILE")
rm -f "$A5_LOGFILE"

# 用完立刻恢复真实函数, 不让桩污染后面的结构闸或任何提前 return/fail 路径。
unset -f deploy_lock_pid deploy_pid_alive
# shellcheck source=/dev/null
source "$COMMON"

[ "$A5_RC" -eq 0 ] \
    || fail "A5: 缺 CRETAS_DEPLOY_REENTRY_PID 时应该照常走 stale 回收成功(rc=0), 实际 rc=$A5_RC, 日志: $A5_LOG"
echo "$A5_LOG" | grep -qi "自我重入" \
    && fail "A5 SECURITY: 没有 CRETAS_DEPLOY_REENTRY_PID 却走了自我重入分支 —— PID 回收撞库漏洞未修复! 日志: $A5_LOG"
echo "$A5_LOG" | grep -q "reclaiming" \
    || fail "A5: 应该走真正的 stale 回收路径(日志里要有 'reclaiming'), 实际日志: $A5_LOG"
echo "PASS A5: PID 回收撞见自己巧合时不会误判自我重入, 照常走 stale 回收 + flock/PID 文件那层真正互斥"
rm -f /tmp/lockstale-a5.lock

# ---------------------------------------------------------------------------
# A6 (2026-09-06, flock 分支专有): 拿到锁之后必须**先截断再写 PID**。
#
# 上游那处 `exec 200>` 改成不截断的 `exec 200<>` 之后, 写 PID 是往 offset 0 写的 ——
# 旧内容比新 PID 长时会留下尾巴(旧 1234567 + 新 999 = "9994567"), 而**下一次**
# precheck 会拿那串垃圾去 `deploy_pid_alive`。垃圾一律判死 ⇒ 一把真正被持有的锁
# 会被当成 stale 回收掉 = 两个 deploy 并发(5/30 事故的形态)。
#
# 构造: 锁文件里放一串比任何真实 PID 都长的内容, 并把 deploy_pid_alive 打桩成
# 「它活着」—— 这样不会走 stale 回收那条 rm 路径, 才量得到截断这一步。
# ⚠️ 只在 flock 模式下成立: PID 文件分支用的是 `deploy_lock_pid > "$lock_file"`,
#    `>` 自带截断, 本来就没有这个问题。
# ---------------------------------------------------------------------------
if command -v flock >/dev/null 2>&1; then
    rm -f /tmp/lockstale-a6.lock
    A6_LONG_STALE='1234567890123456789'
    printf '%s\n' "$A6_LONG_STALE" > /tmp/lockstale-a6.lock
    deploy_pid_alive() { [ "$1" = "$A6_LONG_STALE" ]; }
    A6_LOGFILE=$(mktemp)
    acquire_deploy_lock "lockstale-a6" > "$A6_LOGFILE" 2>&1
    A6_RC=$?
    A6_LOG=$(cat "$A6_LOGFILE")
    rm -f "$A6_LOGFILE"
    unset -f deploy_pid_alive
    # shellcheck source=/dev/null
    source "$COMMON"
    [ "$A6_RC" -eq 0 ] \
        || fail "A6 precondition: 没有别的进程持 flock, 应该拿得到锁(rc=0), 实际 rc=$A6_RC, 日志: $A6_LOG"
    A6_CONTENT=$(cat /tmp/lockstale-a6.lock)
    A6_EXPECT=$(deploy_lock_pid)
    echo "A6_LOCKFILE_CONTENT=[$A6_CONTENT] EXPECT=[$A6_EXPECT]"
    [ "$A6_CONTENT" = "$A6_EXPECT" ] \
        || fail "A6: 锁文件里是 '$A6_CONTENT', 期望恰好 '$A6_EXPECT' —— 旧内容的尾巴没被截掉, 下次 precheck 会拿这串垃圾去问死活, 把一把真锁当 stale 回收"
    echo "PASS A6: 拿到锁后先截断再写 PID, 没有旧内容残留"
    rm -f /tmp/lockstale-a6.lock
else
    echo "A6_SKIPPED_BRANCH=pidfile (该分支用 \`>\` 写, 自带截断, 无此问题)"
fi

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

# A5 的真实布线闸: recover_from_main_drift() 必须真的在 exec 之前设了这个
# 环境变量 —— 光有 A5 那个原语级测试不够, 原语对了不代表真实调用点接上了
# (形态 B: 机制在, 没接上)。
grep -Pzo '(?s)exec bash "\$0".*?\n' "$RELEASE" >/dev/null 2>&1  # 仅确认还是这个 exec 形状, 没静默改名
grep -Fq 'CRETAS_DEPLOY_REENTRY_PID=$$' "$RELEASE" \
    || fail "结构闸: release-cretas.sh 里没找到 CRETAS_DEPLOY_REENTRY_PID=\$\$ —— drift-retry 的 exec 没有带上重入凭证"
echo "PASS 结构闸: release-cretas.sh 的 drift-retry exec 前设了 CRETAS_DEPLOY_REENTRY_PID"

echo "PASS: deploy lock 正确核实持有者存活状态, 自我重入(带凭证)不自杀, PID 回收撞库不会被误判成重入, 调用方清锁兜底就位"
