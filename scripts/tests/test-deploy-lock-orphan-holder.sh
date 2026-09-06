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
#   ④ (D, 释放侧; #679 终审) 持有者**正常退出**、子进程还在 ⇒ 同 ①: 被拒 + 点名 + 可复制的 kill
#      命令 + 复核行; 锁文件**不**被 unlink。旧写法 (调用方 trap 里 `rm -f` 锁文件) 会 unlink
#      掉孤儿按着的 inode ⇒ 第二次获取 rc=0 —— 本测试先用旧写法把这个窗口量出来当阴性对照。
#   ⑤ (E, TOCTOU; #679 终审) **被拒**的那次退出不能删掉别人正持有的锁文件 —— 旧写法
#      `rm -f "${ACQUIRED_DEPLOY_LOCK_FILE:-/tmp/x.lock}"` 在没拿到锁时会 rm 默认路径。
#
# 只在有 flock + /proc 的机器上量 (Linux / WSL / CI ubuntu); Windows Git Bash 没有 flock,
# 走 PID 文件分支, 这里量不到 ⇒ rc=77 SKIPPED (⛔ 不是绿)。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
COMMON="$ROOT_DIR/scripts/lib/deploy-common.sh"
TMP=$(mktemp -d)
CHILD=""
LIVE=""
cleanup() {
    [ -n "$CHILD" ] && kill -9 "$CHILD" 2>/dev/null
    [ -n "$LIVE" ] && kill -9 "$LIVE" 2>/dev/null
    rm -rf "$TMP"; rm -f "/tmp/${LOCK_NAME:-none}.lock"
}
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
case "$out2" in *"kill $CHILD"*) ;; *) fail "拒绝信息必须给出可直接复制的 kill 命令 (kill $CHILD): $out2" ;; esac
case "$out2" in *"still alive"*) ;; *) fail "拒绝信息必须带一行复核命令 (kill -0 … still alive): $out2" ;; esac
case "$fd200_after" in *"(deleted)"*) fail "锁文件被 unlink 了 ($fd200_after) —— 那就是「rm 旧 inode」那条路, 孤儿按的锁与新锁互不可见" ;; esac
ok "持有者死 + 孤儿活 ⇒ 第二次获取被拒 (rc=$rc2), 点名 pid $CHILD, 带 kill 命令与复核行, 锁文件未被 unlink"

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

# ── D 释放侧 (#679 终审 D 场景): 持有者**正常退出**, 但它的子进程还按着 fd 200 ───────
# 三个调用方的 EXIT trap 原来都 `rm -f` 锁文件。持有者正常退出时若还有子进程 (后台 CI fetch /
# 没等完的 rsync) 按着 fd 200, rm 掉路径 = 下一次 acquire 建新 inode ⇒ 与孤儿互不可见。
# 先用旧写法把窗口量出来 (阴性对照: 没有它, 「release_deploy_lock 守住了」与「本来就没窗口」同形)。
make_exiting_holder() {   # $1 = 调用方 trap 里的那条释放语句 (原样写进脚本)
    cat > "$TMP/holder-exit.sh" <<EOF
#!/usr/bin/env bash
set -uo pipefail
source "$COMMON"
acquire_deploy_lock "$LOCK_NAME" || exit 9
sleep 120 &
echo \$! > "$TMP/child.pid"
on_exit() { $1; }
trap on_exit EXIT
exit 0
EOF
    bash "$TMP/holder-exit.sh" > "$TMP/holder-exit.log" 2>&1 \
        || fail "释放侧持有者没拿到锁 / 没正常退出: $(cat "$TMP/holder-exit.log")"
    CHILD=$(cat "$TMP/child.pid")
    kill -0 "$CHILD" 2>/dev/null || fail "释放侧孤儿 $CHILD 没活下来, 构造失败"
}

rm -f "$LOCK_FILE"
make_exiting_holder 'rm -f "$ACQUIRED_DEPLOY_LOCK_FILE" 2>/dev/null || true'
outD0=$(acquire); rcD0=$?
fdD0=$(readlink "/proc/$CHILD/fd/200" 2>/dev/null || echo none)
echo "READING: D_old_rm_release: second_acquire_rc=$rcD0 orphan_fd200=$fdD0"
[ "$rcD0" -eq 0 ] \
    || fail "阴性对照失效: 旧写法 (trap 里 rm -f 锁文件) 本应让第二次获取**成功** (互斥失效的形状), 实得 rc=$rcD0 —— 这台机器上 rm 已不造成窗口, 后面 D 的断言守不住东西: $outD0"
case "$fdD0" in *"(deleted)"*) ;; *) fail "阴性对照失效: 旧写法应把孤儿按着的 inode unlink 掉 (deleted), 实得 $fdD0" ;; esac
kill -9 "$CHILD"; wait "$CHILD" 2>/dev/null; CHILD=""
ok "D 阴性对照: 旧写法 rm 锁文件 ⇒ 孤儿按着 (deleted) inode, 第二次获取 rc=0 —— 释放侧的窗口量出来了"

rm -f "$LOCK_FILE"
make_exiting_holder 'release_deploy_lock'
outD=$(acquire); rcD=$?
fdD=$(readlink "/proc/$CHILD/fd/200" 2>/dev/null || echo none)
echo "READING: D_release_deploy_lock: second_acquire_rc=$rcD orphan_fd200=$fdD lockfile_bytes=$(stat -c %s "$LOCK_FILE" 2>/dev/null || echo missing)"
echo "    log: $(printf '%s' "$outD" | tr '\n' '|' | cut -c1-700)"
[ "$rcD" -ne 0 ] || fail "释放侧互斥失效: 持有者正常退出 (release_deploy_lock) 而子进程 $CHILD 仍按着 fd 200, 第二次获取却成功: $outD"
case "$fdD" in *"(deleted)"*) fail "release_deploy_lock 不该 unlink 锁文件 ($fdD)" ;; esac
case "$outD" in *"$CHILD"*) ;; *) fail "拒绝信息必须点名孤儿 pid $CHILD: $outD" ;; esac
case "$outD" in *"kill $CHILD"*) ;; *) fail "拒绝信息必须给出可复制的 kill 命令 (kill $CHILD): $outD" ;; esac
case "$outD" in *"still alive"*) ;; *) fail "拒绝信息必须带一行复核命令: $outD" ;; esac
case "$outD" in *"已正常释放"*) ;; *) fail "登记 PID 已被正常释放截掉, 措辞应是「已正常释放, 但锁仍被按着」, 不是「PID ? 已死」: $outD" ;; esac
ok "D: 正常退出 + 子进程还在 ⇒ 第二次获取被拒 (rc=$rcD), 点名 $CHILD, 带 kill 命令与复核行, 锁文件未 unlink"
kill -9 "$CHILD"; wait "$CHILD" 2>/dev/null; CHILD=""; sleep 0.2
outD2=$(acquire); rcD2=$?
echo "READING: D_after_orphan_exit: acquire_rc=$rcD2 reclaiming_lines=$(printf '%s\n' "$outD2" | grep -c reclaiming)"
[ "$rcD2" -eq 0 ] || fail "孤儿退出后获取应成功, 实得 rc=$rcD2: $outD2"
case "$outD2" in *reclaiming*) fail "正常释放过的锁不是 stale, 不该报 reclaiming: $outD2" ;; esac
ok "D: 孤儿退出后获取成功, 且不报 reclaiming (正常释放已把登记 PID 截掉)"

# ── E TOCTOU (#679 终审): 被拒的那次退出不能删掉别人正持有的锁 ─────────────────────
# 活持有者: 拿锁后 exec sleep (同一 pid 继续按着 fd 200, 登记 PID 活着 ⇒ 走「另一个 deploy」分支)。
start_live_holder() {
    rm -f "$LOCK_FILE" "$TMP/live.held"
    cat > "$TMP/live.sh" <<EOF
#!/usr/bin/env bash
set -uo pipefail
source "$COMMON"
acquire_deploy_lock "$LOCK_NAME" || exit 9
echo held > "$TMP/live.held"
exec sleep 120
EOF
    bash "$TMP/live.sh" > "$TMP/live.log" 2>&1 &
    LIVE=$!
    local i=0
    while [ ! -f "$TMP/live.held" ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
    [ -f "$TMP/live.held" ] || fail "活持有者没拿到锁: $(cat "$TMP/live.log")"
}
stop_live_holder() { kill -9 "$LIVE" 2>/dev/null; wait "$LIVE" 2>/dev/null; LIVE=""; }
refused_caller() {   # $1 = 被拒调用方 trap 里的释放语句
    cat > "$TMP/refused.sh" <<EOF
#!/usr/bin/env bash
set -uo pipefail
source "$COMMON"
on_exit() { $1; }
trap on_exit EXIT
acquire_deploy_lock "$LOCK_NAME"
exit \$?
EOF
    bash "$TMP/refused.sh" > "$TMP/refused.log" 2>&1
    echo $?
}

start_live_holder
rcE0=$(refused_caller 'rm -f "${ACQUIRED_DEPLOY_LOCK_FILE:-'"$LOCK_FILE"'}" 2>/dev/null || true')
fdE0=$(readlink "/proc/$LIVE/fd/200" 2>/dev/null || echo none)
outE0=$(acquire); rcE0b=$?
echo "READING: E_old_rm_release: refused_rc=$rcE0 live_fd200=$fdE0 lockfile=$([ -e "$LOCK_FILE" ] && echo present || echo missing) next_acquire_rc=$rcE0b"
[ "$rcE0" -ne 0 ] || fail "E 构造失败: 活持有者按着锁, 第二个调用方本该被拒: $(cat "$TMP/refused.log")"
case "$fdE0" in *"(deleted)"*) ;; *) fail "阴性对照失效: 旧写法在被拒退出时本应 rm 掉活持有者的锁文件 (deleted), 实得 $fdE0" ;; esac
[ "$rcE0b" -eq 0 ] || fail "阴性对照失效: 锁文件被删后第三个进程本应拿到新 inode 的锁 (互斥失效), 实得 rc=$rcE0b: $outE0"
stop_live_holder
ok "E 阴性对照: 被拒调用方旧写法 rm 掉了活持有者的锁文件 ⇒ 第三个进程 rc=0 —— TOCTOU 量出来了"

start_live_holder
rcE=$(refused_caller 'release_deploy_lock')
fdE=$(readlink "/proc/$LIVE/fd/200" 2>/dev/null || echo none)
outE=$(acquire); rcEb=$?
echo "READING: E_release_deploy_lock: refused_rc=$rcE live_fd200=$fdE lockfile=$([ -e "$LOCK_FILE" ] && echo present || echo missing) next_acquire_rc=$rcEb"
[ "$rcE" -ne 0 ] || fail "E 构造失败: 活持有者按着锁, 第二个调用方本该被拒: $(cat "$TMP/refused.log")"
case "$fdE" in *"(deleted)"*) fail "被拒调用方的 release_deploy_lock 删掉了活持有者的锁文件 ($fdE)" ;; esac
[ -e "$LOCK_FILE" ] || fail "被拒调用方退出后锁文件应仍在"
[ "$rcEb" -ne 0 ] || fail "活持有者仍在, 第三个进程本该被拒, 实得 rc=0: $outE"
case "$outE" in *"$LIVE"*|*"另一个 deploy"*) ;; *) fail "拒绝信息应点名活持有者 (PID $LIVE / 另一个 deploy): $outE" ;; esac
stop_live_holder
ok "E: 被拒调用方走 release_deploy_lock ⇒ 活持有者的锁文件仍在, 第三个进程照样被拒 (rc=$rcEb)"

# ── F 截断走自己的 fd, 不走路径 (#692 终审 follow-up a) ─────────────────────────
# release_deploy_lock 要把登记的 PID 清掉。若按【路径】截断, 而 acquire 与 release 之间那个
# 路径已经被换掉(灰度期的旧写法对端 / 有人手 rm 又重建), 截掉的就是**别人的 inode** ——
# 互斥不受影响(flock 挂在 OFD 上), 坏的是诊断: 下一次拒绝会说「已正常释放」而其实有活着的
# 持有者。⇒ 必须经 /dev/fd/200 截断我们自己的 inode。
rm -f "$LOCK_FILE" "$LOCK_FILE.moved"
cat > "$TMP/swap.sh" <<EOF
#!/usr/bin/env bash
set -uo pipefail
source "$COMMON"
acquire_deploy_lock "$LOCK_NAME" || exit 9
# 有人在我们持锁期间把路径换走, 并用一个新文件占住原路径
mv "$LOCK_FILE" "$LOCK_FILE.moved"
printf 'IMPOSTOR12\n' > "$LOCK_FILE"
release_deploy_lock
EOF
bash "$TMP/swap.sh" > "$TMP/swap.log" 2>&1 || fail "F 构造失败: $(cat "$TMP/swap.log")"
moved_bytes=$(stat -c %s "$LOCK_FILE.moved" 2>/dev/null || echo missing)
impostor_bytes=$(stat -c %s "$LOCK_FILE" 2>/dev/null || echo missing)
echo "READING: F_own_inode_bytes=$moved_bytes F_impostor_bytes=$impostor_bytes (期望 0 / 11)"
[ "$moved_bytes" = "0" ] \
    || fail "F: 我们自己的 inode 没被截断(实得 $moved_bytes) —— 登记 PID 还在, 下一次获取会误报 reclaiming"
[ "$impostor_bytes" = "11" ] \
    || fail "F: 占住原路径的**别人的**文件被动了(实得 $impostor_bytes, 期望 11) —— 按路径截断会抹掉别人的 PID 戳"
ok "释放时截断的是自己的 inode, 不是当时占住该路径的那个文件"
rm -f "$LOCK_FILE" "$LOCK_FILE.moved"

# ── G 拒绝信息: kill 之前必须先有一条确认命令 (#692 终审 follow-up b) ────────────
# pid 会被回收再分配; 运维几分钟后粘贴 kill 时那个数字可能已经是别的进程。
rm -f "$LOCK_FILE"
make_exiting_holder 'release_deploy_lock'
outG=$(acquire); rcG=$?
[ "$rcG" -ne 0 ] || fail "G 构造失败: 孤儿还按着锁, 第二次获取本该被拒"
printf '%s' "$outG" | grep -q "ps -o pid,args -p" \
    || fail "G: 拒绝信息里没有 kill 之前的确认命令(ps -o pid,args -p …): $outG"
printf '%s' "$outG" | grep -q "pid 会被回收再分配" \
    || fail "G: 没有 pid 复用的警告 —— 快照读数几分钟后就可能不成立了"
ps_line=$(printf '%s\n' "$outG" | grep -n 'ps -o pid,args -p' | head -1 | cut -d: -f1)
kill_line=$(printf '%s\n' "$outG" | grep -n '    kill ' | head -1 | cut -d: -f1)
echo "READING: G_ps_line=$ps_line G_kill_line=$kill_line (确认必须排在 kill 之前)"
[ -n "$ps_line" ] && [ -n "$kill_line" ] && [ "$ps_line" -lt "$kill_line" ] \
    || fail "G: 确认命令(行 ${ps_line:-?})必须排在 kill(行 ${kill_line:-?})之前, 否则是杀完才确认"
printf '%s' "$outG" | grep -q "ps -o pid,args -p $CHILD" \
    || fail "G: 确认命令里没有那个孤儿 pid $CHILD"
ok "拒绝信息把「先确认」排在 kill 之前, 并带 pid 复用警告"
kill -9 "$CHILD" 2>/dev/null; wait "$CHILD" 2>/dev/null; CHILD=""

echo "READING: SEMANTICS=block-while-orphan-holds"
echo "PASS: test-deploy-lock-orphan-holder.sh ($PASSED 组断言)"
