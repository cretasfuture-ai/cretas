#!/bin/bash
# canada-mini 一次性 (JIT/ephemeral) GitHub runner 补位循环, 每个槽位一个 launchd 进程 (以 rex 运行)。
# - GitHub 令牌只在 rex 的 gh 配置里; 这里用它换一次性 JIT 配置, 交给无密码、非管理员的 ghgate 账户跑。
# - 每个任务一个全新 runner 目录 (从干净的安装包 APFS 克隆), 跑完整个目录删除 (runner 身份/凭据/日志不留)。
# - 例外: 代码检出目录 ~ghgate/work-N 跨任务保留 (actions/checkout 每次 clean+reset)。2026-11-18 实测每单在全新
#   目录整仓克隆时 mini→GitHub 拉取超时 (curl 56 / early EOF), PG 第 0 片死在 checkout; 保留后只做增量拉取。
# - 发布优先: 发布构建持有 ~/cretas-build/.build.lock 时不领新任务。
# 用法: refill.sh <槽位号>      (--label-of <槽位号> 打印该槽标签, --idle-rank-of <槽位号> 打印空闲名次, 供仓库测试核对)
set -u
# 槽号 -> 标签 (2026-11-19 Steve: 第 5 槽专给 fast-contracts): GH_GATE_FAST_SLOTS 里的槽注册 cretas-mini-fast,
# 其余槽 (1-4) 注册 cretas-mini-pg。⚠️ 标签与 java-gate-pr.yml 里 route-pg / route-fast 的 MINI_LABEL 一致,
# 闸 scripts/tests/test-route-mini-or-hosted.sh 核对两边。
FAST_SLOTS="${GH_GATE_FAST_SLOTS:-5}"
slot_label() { case " $FAST_SLOTS " in *" $1 "*) echo cretas-mini-fast ;; *) echo cretas-mini-pg ;; esac; }
slot_busy() { pgrep -f "/Users/ghgate/slot-$1/bin/Runner.Listener" >/dev/null 2>&1; }
# 时段 (2026-11-19 Steve「晚上 22:00 之后恢复跑满 runner, 8 点之后变成 1 pg 1 fast」), 北京时间:
#   full = [22:00, 08:00) 全部槽; day = [08:00, 22:00) 只开 $DAY_SLOTS (槽 1 = PG, 槽 5 = fast)。
# ⚠️ 唯一一份时段定义: java-gate-pr.yml 的 changes 作业也调 `refill.sh --window` 决定 PG 派几片上 mini。
FULL_FROM=22
FULL_UNTIL=8
DAY_SLOTS="${GH_GATE_DAY_SLOTS:-1 5}"
mini_window() {  # [小时 0-23]; 不给就取北京时间当前小时 (GH_GATE_HOUR 可覆盖, 供测试)
  # ⛔ 不用 TZ=Asia/Shanghai: 没有时区数据的机器 (Git Bash 实测) 静默按 UTC 算, 时段整体错 8 小时。
  #    北京时间 = UTC + 8, 无夏令时。
  local h="${1:-${GH_GATE_HOUR:-$(( (10#$(date -u +%H) + 8) % 24 ))}}"
  h=$((10#$h))
  if [ "$h" -ge "$FULL_FROM" ] || [ "$h" -lt "$FULL_UNTIL" ]; then echo full; else echo day; fi
}
# 启用哪些槽: 手动覆盖文件 $ENABLED_FILE 存在时以它为准 (列出槽号, 空格/换行分隔); 不存在就按时段。
# 不启用的槽空转不领活、也不算进别的槽的名次; 改文件/到点即生效, 不用动 launchd。
ENABLED_FILE="${GH_GATE_ENABLED_FILE:-/Users/rex/gh-gate/enabled-slots}"
slot_enabled() {
  local list
  if [ -f "$ENABLED_FILE" ]; then list=$(tr '\n' ' ' < "$ENABLED_FILE")
  elif [ "$(mini_window)" = full ]; then return 0
  else list="$DAY_SLOTS"; fi
  case " $list " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}
# 名次只在同标签的槽里排 (fast 槽不和 PG 槽抢同一份排队数): 编号比 $1 小、同标签、空闲的槽数 + 1。
# ⛔ 不用 `seq 1 $((n-1))`: macOS (BSD) 的 `seq 1 0` 倒数输出「1 0」, 第 1 槽把自己和不存在的 0 号槽都算成空闲,
#    名次变 3; 只剩 1 个排队作业时 4 个 PG 槽全不起 (2026-11-19 run 44403277567 汇总作业卡 1.5 小时, 队列超时踢出)。
idle_rank() {
  local s="$1" lbl k r=1
  lbl=$(slot_label "$s")
  for ((k = 1; k < s; k++)); do
    [ "$(slot_label "$k")" = "$lbl" ] || continue
    slot_enabled "$k" || continue
    slot_busy "$k" || r=$((r + 1))
  done
  echo "$r"
}
if [ "${1:-}" = "--label-of" ]; then slot_label "${2:?slot}"; exit 0; fi
if [ "${1:-}" = "--idle-rank-of" ]; then idle_rank "${2:?slot}"; exit 0; fi
if [ "${1:-}" = "--enabled-of" ]; then if slot_enabled "${2:?slot}"; then echo yes; else echo no; fi; exit 0; fi
if [ "${1:-}" = "--window" ]; then mini_window "${2:-}"; exit 0; fi
SLOT="${1:?slot}"
export PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
REPO=cretasfuture-ai/cretas
LABEL=$(slot_label "$SLOT")
PKG=/Users/ghgate/runner-pkg
WORK=/Users/ghgate/slot-$SLOT
LOCK=/Users/rex/cretas-build/.build.lock
LOG=/Users/rex/gh-gate/slot-$SLOT.log

log() { echo "$(date '+%F %T') slot=$SLOT $*" >> "$LOG"; }
lock_held() { [ -d "$LOCK" ] && ps -p "$(cat "$LOCK/pid" 2>/dev/null || echo 0)" >/dev/null 2>&1; }

# 按需起 runner (Steve 2026-11-18「没跑的时候不占内存, 用的时候才占」): 空闲时只有这个循环 (~10MB),
# 不常驻 Runner.Listener (~120MB/槽)。GitHub 上排队等 $LABEL 的 job 数 >= 本槽在同标签空闲槽里的名次才起。
# 排队数按标签缓存在 $QCACHE, 20 秒内同标签各槽共用一次查询 (省 API 额度)。
QCACHE=/Users/rex/gh-gate/queued-$LABEL.cache
IDLE_KILL_SEC=180
mini_queued() {
  local age=999 n=0 r c
  [ -f "$QCACHE" ] && age=$(( $(date +%s) - $(stat -f %m "$QCACHE") ))
  if [ "$age" -ge 20 ] && mkdir "$QCACHE.lock" 2>/dev/null; then
    for r in $( { gh api "repos/$REPO/actions/workflows/java-gate-pr.yml/runs?status=queued&per_page=30" -q '.workflow_runs[].id'
                  gh api "repos/$REPO/actions/workflows/java-gate-pr.yml/runs?status=in_progress&per_page=30" -q '.workflow_runs[].id'; } 2>>"$LOG"); do
      c=$(gh api "repos/$REPO/actions/runs/$r/jobs?per_page=100" \
            -q "[.jobs[]|select(.status==\"queued\" and (.labels|index(\"$LABEL\")))]|length" 2>>"$LOG") || continue
      n=$((n + ${c:-0}))
    done
    echo "$n" > "$QCACHE.tmp" && mv "$QCACHE.tmp" "$QCACHE"
    rmdir "$QCACHE.lock"
  fi
  cat "$QCACHE" 2>/dev/null || echo 0
}

log "start label=$LABEL"
paused=0
while :; do
  if ! slot_enabled "$SLOT"; then
    [ "$paused" = 1 ] || log "disabled (not in $ENABLED_FILE), idle"
    paused=1; sleep 30; continue
  fi
  [ "$paused" = 1 ] && log "enabled again"
  paused=0
  if lock_held; then log "release build holds lock, wait"; while lock_held; do sleep 15; done; fi
  # 残留的陈旧锁目录 (持锁进程已死) 超过 2 分钟就清掉
  [ -d "$QCACHE.lock" ] && [ -n "$(find "$QCACHE.lock" -maxdepth 0 -mmin +2 2>/dev/null)" ] && rmdir "$QCACHE.lock" 2>/dev/null
  q=$(mini_queued); rank=$(idle_rank "$SLOT")
  if [ "${q:-0}" -lt "$rank" ]; then sleep 20; continue; fi
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
    # macOS 上 launchd 起的进程没有 locale, PostgreSQL 17 启动报
    # 「postmaster became multithreaded during startup / Set the LC_ALL」(2026-11-18 run 44294244372)。
    printf "%s\n" "LC_ALL=en_US.UTF-8" "LANG=en_US.UTF-8" >> .env
    # 本机镜像 (2026-11-18): 任务开始前钩子把镜像增量更新, 任务里的 git 改走镜像 (见 job-started.sh)。
    if [ -d /Users/ghgate/mirror/cretas.git ] && [ -x /Users/ghgate/hooks/job-started.sh ]; then
      printf "%s\n" "ACTIONS_RUNNER_HOOK_JOB_STARTED=/Users/ghgate/hooks/job-started.sh" \
        "GIT_CONFIG_COUNT=1" \
        "GIT_CONFIG_KEY_0=url.file:///Users/ghgate/mirror/cretas.git.insteadOf" \
        "GIT_CONFIG_VALUE_0=https://github.com/cretasfuture-ai/cretas" >> .env
    fi
    ./run.sh --jitconfig "$3"
  ' _ "$WORK" "$PKG" "$jit" "$LOCK" >> "$LOG" 2>&1 &
  pid=$!
  # 起了但 $IDLE_KILL_SEC 秒内没领到活 (别的槽先接走了) 就收掉, 并删掉这条 runner 注册。
  waited=0
  while ps -p "$pid" >/dev/null 2>&1; do  # ⛔ 不用 kill -0: pid 是 root 的 sudo, rex 发信号会 EPERM
    if [ "$waited" -ge "$IDLE_KILL_SEC" ] && ! pgrep -f "/Users/ghgate/slot-$SLOT/bin/Runner.Worker" >/dev/null 2>&1; then
      log "idle ${waited}s without a job, stop $name"
      sudo -n -u ghgate pkill -f "/Users/ghgate/slot-$SLOT/bin/Runner.Listener" 2>/dev/null
      sleep 5
      rid=$(gh api "repos/$REPO/actions/runners?per_page=100" -q ".runners[]|select(.name==\"$name\")|.id" 2>>"$LOG")
      [ -n "$rid" ] && gh api -X DELETE "repos/$REPO/actions/runners/$rid" 2>>"$LOG"
      break
    fi
    pgrep -f "/Users/ghgate/slot-$SLOT/bin/Runner.Worker" >/dev/null 2>&1 && waited=-100000
    sleep 5; waited=$((waited + 5))
  done
  wait "$pid"; rc=$?
  # 任务结束后不留进程: 本槽检出目录下起的 java / postgres 等一律结束, 空闲时不占内存。
  sudo -n -u ghgate pkill -f "/Users/ghgate/work-$SLOT/" 2>/dev/null
  sudo -n -u ghgate rm -rf "$WORK" /Users/ghgate/.m2/repository/com/cretas 2>/dev/null
  log "runner $name exited rc=$rc"
  sleep 3
  # 每单之后重新执行自己: 脚本更新 (原子替换) 后下一单就生效, 不用重启 launchd、不打断在跑的任务。
  exec /bin/bash "$0" "$SLOT"
done
