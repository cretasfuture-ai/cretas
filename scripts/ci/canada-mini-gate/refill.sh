#!/bin/bash
# canada-mini 一次性 (JIT/ephemeral) GitHub runner 补位循环, 每个槽位一个 launchd 进程 (以 rex 运行)。
# - GitHub 令牌只在 rex 的 gh 配置里; 这里用它换一次性 JIT 配置, 交给无密码、非管理员的 ghgate 账户跑。
# - 每个任务一个全新 runner 目录 (从干净的安装包 APFS 克隆), 跑完整个目录删除 (runner 身份/凭据/日志不留)。
# - 例外: 代码检出目录 ~ghgate/work-N 跨任务保留 (actions/checkout 每次 clean+reset)。2026-11-18 实测每单在全新
#   目录整仓克隆时 mini→GitHub 拉取超时 (curl 56 / early EOF), PG 第 0 片死在 checkout; 保留后只做增量拉取。
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
        -f name="$name" -F runner_group_id=1 -f "labels[]=$LABEL" -f work_folder="../work-$SLOT" \
        -q .encoded_jit_config 2>>"$LOG")
  if [ -z "$jit" ]; then log "jitconfig failed, retry in 30s"; sleep 30; continue; fi
  sudo -n -u ghgate -H /bin/bash -c '
    set -e
    rm -rf "$1"; cp -cR "$2" "$1"; cd "$1"
    echo "/opt/homebrew/opt/postgresql@17/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" > .path
    printf "CRETAS_RELEASE_LOCK=%s\n" "$4" > .env
    # 本机镜像 (2026-11-18): 任务开始前钩子把镜像增量更新, 任务里的 git 改走镜像 (见 job-started.sh)。
    if [ -d /Users/ghgate/mirror/cretas.git ] && [ -x /Users/ghgate/hooks/job-started.sh ]; then
      printf "%s\n" "ACTIONS_RUNNER_HOOK_JOB_STARTED=/Users/ghgate/hooks/job-started.sh" \
        "GIT_CONFIG_COUNT=1" \
        "GIT_CONFIG_KEY_0=url.file:///Users/ghgate/mirror/cretas.git.insteadOf" \
        "GIT_CONFIG_VALUE_0=https://github.com/cretasfuture-ai/cretas" >> .env
    fi
    ./run.sh --jitconfig "$3"
  ' _ "$WORK" "$PKG" "$jit" "$LOCK" >> "$LOG" 2>&1
  rc=$?
  sudo -n -u ghgate rm -rf "$WORK" /Users/ghgate/.m2/repository/com/cretas 2>/dev/null
  log "runner $name exited rc=$rc"
  sleep 3
  # 每单之后重新执行自己: 脚本更新 (原子替换) 后下一单就生效, 不用重启 launchd、不打断在跑的任务。
  exec /bin/bash "$0" "$SLOT"
done
