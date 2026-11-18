#!/bin/bash
# canada-mini 一次性 (JIT/ephemeral) GitHub runner 补位循环, 每个槽位一个 launchd 进程 (以 rex 运行)。
# - GitHub 令牌只在 rex 的 gh 配置里; 这里用它换一次性 JIT 配置, 交给无密码、非管理员的 ghgate 账户跑。
# - 每个任务一个全新目录 (从干净的安装包 APFS 克隆), 跑完整个目录删除: 任务之间不留任何东西。
# - 发布优先: 发布构建持有 ~/cretas-build/.build.lock 时不领新任务。
# 用法: refill.sh <槽位号>
set -u
SLOT="${1:?slot}"
export PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
REPO=cretasfuture-ai/cretas
LABEL=cretas-mini-pg
PKG=/Users/ghgate/runner-pkg
WORK=/Users/ghgate/slot-$SLOT
LOCK=/Users/rex/cretas-build/.build.lock
LOG=/Users/rex/gh-gate/slot-$SLOT.log

log() { echo "$(date '+%F %T') slot=$SLOT $*" >> "$LOG"; }
lock_held() { [ -d "$LOCK" ] && ps -p "$(cat "$LOCK/pid" 2>/dev/null || echo 0)" >/dev/null 2>&1; }

log "start"
while :; do
  if lock_held; then log "release build holds lock, wait"; while lock_held; do sleep 15; done; fi
  name="canada-mini-gate-$SLOT-$(date +%m%d%H%M%S)"
  jit=$(gh api -X POST "repos/$REPO/actions/runners/generate-jitconfig" \
        -f name="$name" -F runner_group_id=1 -f "labels[]=$LABEL" -f work_folder=_work \
        -q .encoded_jit_config 2>>"$LOG")
  if [ -z "$jit" ]; then log "jitconfig failed, retry in 30s"; sleep 30; continue; fi
  sudo -n -u ghgate -H /bin/bash -c '
    set -e
    rm -rf "$1"; cp -cR "$2" "$1"; cd "$1"
    echo "/opt/homebrew/opt/postgresql@17/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" > .path
    printf "CRETAS_RELEASE_LOCK=%s\n" "$4" > .env
    ./run.sh --jitconfig "$3"
  ' _ "$WORK" "$PKG" "$jit" "$LOCK" >> "$LOG" 2>&1
  rc=$?
  sudo -n -u ghgate rm -rf "$WORK" /Users/ghgate/.m2/repository/com/cretas 2>/dev/null
  log "runner $name exited rc=$rc"
  sleep 3
done
