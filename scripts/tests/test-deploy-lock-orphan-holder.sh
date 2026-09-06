#!/usr/bin/env bash
# acquire_deploy_lock (scripts/lib/deploy-common.sh) 在「持有者已死、但它的**子进程**还按着锁」时的行为。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (brief 卡 C 任务 3; #635 终审登记「遗留子进程持锁未消除」)
# ═══════════════════════════════════════════════════════════════════════════
# flock 挂在**打开文件描述**上: 父进程 `exec 200<>lock; flock 200` 之后 fork 出来的每一个子进程
# (前台的 deploy-backend.sh / ssh / rsync / mvn, 后台的 CI fetch / web build) 都继承 fd 200,
# 于是父进程被 SIGKILL 之后, 只要还有一个子进程活着, 锁就还被按着。
#
# 现状 (2026-09-06 之前的 deploy-common.sh): 下一次 acquire 看到锁文件里的 PID 已死, `rm -f` 掉
# 那个**路径**, 再 `exec 200<>` 建一个**新 inode** 并 flock 成功 —— 于是同一时刻两个持有者:
# 新部署按着新 inode, 遗留的 rsync 按着已 unlink 的旧 inode, 互不可见, **互斥失效**。
# 那正是 5/30 事故的形状 (两个 deploy 同时写共享 jar)。
# ⚠️ 现有 test-deploy-mutex-coverage.sh 量不到它: 它的「持有者死后第二次获取成功」走的正是这条
#    rm 旧 inode 的路, 对遗留子进程**不敏感**。本测试就是把那个窗口量出来。
#
# 判据 (本测试守的语义 = 「挡」, 见 PR 里的裁定):
#   ① 持有者已死 + 孤儿子进程仍按着 fd 200  ⇒ 第二次获取必须**被拒** (rc≠0), 且拒绝信息里
#      要**点名**那个孤儿 pid —— 操作者据此决定等它还是杀它, ⛔ 不是静默放行
#   ② 孤儿一死 (kill)                          ⇒ 第三次获取必须成功 —— 不是永久卡死
#   ③ 持有者已死、**没有**子进程                ⇒ 获取必须成功 (内核释放 flock; 与 mutex-coverage 一致)
#
# 只在有 flock + /proc 的机器上量 (Linux / WSL / CI ubuntu); Windows Git Bash 没有 flock,
# 走 PID 文件分支, 这里量不到 ⇒ rc=77 SKIPPED (⛔ 不是绿)。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
COMMON="$ROOT_DIR/scripts/lib/deploy-common.sh"
TMP=$(mktemp -d)
CHILD=""
cleanup() { [ -n "$CHILD" ] && kill -9 "$CHILD" 2>/dev/null; rm -rf "$TMP"; rm -f "/tmp/${LOCK_NAME:-none}.lock"; }
trap cleanup EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$COMMON" ] || fail "$COMMON 不存在"
if ! command -v flock >/dev/null 2>&1; then
    echo "SKIPPED:本机没有 flock (Windows Git Bash), acquire_deploy_lock 走 PID 文件分支, 遗留子进程持锁在这里量不到"
    exit 77
fi
if [ ! -d /proc/self/fd ]; then
    echo "SKIPPED:没有 /proc, 量不到「哪个进程按着 fd 200」"
    exit 77
fi

LOCK_NAME="cretas-orphan-selftest-$$"
LOCK_FILE="/tmp/$LOCK_NAME.lock"
rm -f "$LOCK_FILE"

# 持有者: 拿锁 → fork 一个会活很久的子进程 (继承 fd 200) → 自杀 (SIGKILL, 不走任何 trap)。
cat > "$TMP/holder.sh" <<EOF
#!/usr/bin/env bash
set -uo pipefail
source "$COMMON"
acquire_deploy_lock "$LOCK_NAME" || exit 9
sleep 120 &
echo \$! > "$TMP/child.pid"
echo held > "$TMP/held"
kill -9 \$\$
EOF
bash "$TMP/holder.sh" > "$TMP/holder.log" 2>&1
hrc=$?
[ -f "$TMP/held" ] || fail "持有者没拿到锁 (rc=$hrc): $(cat "$TMP/holder.log")"
CHILD=$(cat "$TMP/child.pid")
kill -0 "$CHILD" 2>/dev/null || fail "孤儿子进程 $CHILD 没活下来, 构造失败"
# 读数 ①: 孤儿真的按着锁 —— 它的 fd 200 指向锁文件 (阳性对照, 没有它后面的断言没意义)。
fd200=$(readlink "/proc/$CHILD/fd/200" 2>/dev/null || echo none)
case "$fd200" in "$LOCK_FILE"*) ;; *) fail "孤儿 $CHILD 的 fd 200 不是锁文件 ($fd200) —— 构造失败, 这条测试量不到东西" ;; esac
echo "READING: orphan_pid=$CHILD fd200=$fd200 holder_rc=$hrc"

# ── ① 持有者死、孤儿活 ⇒ 第二次获取必须被拒, 且点名孤儿 ─────────────────────
acquire() { bash -c "source '$COMMON'; acquire_deploy_lock '$LOCK_NAME'" 2>&1; }
out2=$(acquire); rc2=$?
fd200_after=$(readlink "/proc/$CHILD/fd/200" 2>/dev/null || echo none)
echo "READING: second_acquire_rc=$rc2 orphan_fd200_after=$fd200_after"
echo "    log: $(printf '%s' "$out2" | tail -3 | tr '\n' '|')"
[ "$rc2" -ne 0 ] || fail "互斥失效: 持有者已死、孤儿 $CHILD 仍按着 fd 200 ($fd200_after), 第二次获取却成功 (rc=0) —— 5/30 事故的形状 (两个 deploy 同时写共享 jar)"
case "$out2" in *"$CHILD"*) ;; *) fail "拒绝信息必须点名孤儿 pid $CHILD, 操作者才知道该等还是该杀: $out2" ;; esac
case "$fd200_after" in *"(deleted)"*) fail "锁文件被 unlink 了 ($fd200_after) —— 那就是「rm 旧 inode」那条路, 孤儿按的锁与新锁互不可见" ;; esac
ok "持有者死 + 孤儿活 ⇒ 第二次获取被拒 (rc=$rc2), 点名 pid $CHILD, 锁文件未被 unlink"

# ── ② 孤儿一死 ⇒ 第三次获取成功 (不是永久卡死) ─────────────────────────────
kill -9 "$CHILD"; wait "$CHILD" 2>/dev/null; CHILD=""
sleep 0.2
out3=$(acquire); rc3=$?
echo "READING: third_acquire_rc=$rc3"
[ "$rc3" -eq 0 ] || fail "孤儿已死, 第三次获取仍被拒 (rc=$rc3) —— 永久卡死: $out3"
ok "孤儿死后 ⇒ 获取成功 (内核释放 flock, 无永久卡死)"

# ── ③ 持有者死、无子进程 ⇒ 获取成功 (与 test-deploy-mutex-coverage 的既有判据一致) ──
rm -f "$LOCK_FILE"
bash -c "source '$COMMON'; acquire_deploy_lock '$LOCK_NAME' || exit 9; kill -9 \$\$" > /dev/null 2>&1
out4=$(acquire); rc4=$?
echo "READING: stale_no_children_acquire_rc=$rc4"
[ "$rc4" -eq 0 ] || fail "持有者死且无子进程, 获取应成功, 实得 rc=$rc4: $out4"
ok "持有者死、无子进程 ⇒ 获取成功"

echo "READING: SEMANTICS=block-while-orphan-holds"
echo "PASS: test-deploy-lock-orphan-holder.sh ($PASSED 组断言)"
