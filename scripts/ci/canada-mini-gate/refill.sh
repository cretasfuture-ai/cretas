#!/bin/bash
# canada-mini 一次性 (JIT/ephemeral) GitHub runner 补位循环, 每个槽位一个 launchd 进程 (以 rex 运行)。
# - GitHub 令牌只在 rex 的 gh 配置里; 这里用它换一次性 JIT 配置, 交给无密码、非管理员的 ghgate 账户跑。
# - 每个任务一个全新 runner 目录 (从干净的安装包 APFS 克隆), 跑完整个目录删除 (runner 身份/凭据/日志不留)。
# - 例外: 代码检出目录 ~ghgate/work-N 跨任务保留 (actions/checkout 每次 clean+reset)。2026-11-18 实测每单在全新
#   目录整仓克隆时 mini→GitHub 拉取超时 (curl 56 / early EOF), PG 第 0 片死在 checkout; 保留后只做增量拉取。
# - 发布优先: 发布构建持有 ~/cretas-build/.build.lock 时不领新任务。
# 用法: refill.sh <槽位号>      (--labels-of <槽位号> 打印该槽注册的标签, --idle-rank-of <槽位号> 打印空闲名次,
#        --queue-labels / --workflows 打印排队计数口径, 供仓库测试核对)
set -u
# 共用槽池 (2026-11-19 21:5x Steve「不跑5个了 反正留出来4个就够了 5个怕出问题……重点是这4个槽不要只给pg用」):
# 最多 $MAX_SLOTS 个槽 (1-4, 没有槽 5: 4 槽同跑实测负载峰值 14.8 / 14 核, 温度到过 Moderate), 每个槽什么都领 ——
# 所有上 mini 的作业 (Java Gate 的 PG / fast 分片与汇总, 以及别的检查) 一律 runs-on 公共标签 cretas-mini。
# 槽同时注册旧标签 cretas-mini-pg / cretas-mini-fast: 合并本改动之前创建、还在排队的 run 仍写着旧标签,
# 没有它们就永远没人接。⚠️ 标签与 route-mini-or-hosted.sh 的 MINI_LABEL / MINI_COUNT_LABELS、各 workflow 的
# runs-on 一致, 闸 scripts/tests/test-route-mini-or-hosted.sh 核对。
MAX_SLOTS=4
MINI_LABELS="cretas-mini cretas-mini-pg cretas-mini-fast"
# 有作业会上 mini 的 workflow (排队只数这些 run, 省 API 额度)。⚠️ 与 route-mini-or-hosted.sh 的 MINI_WORKFLOWS 逐字一致 (闸核对)。
MINI_WORKFLOWS="java-gate-pr.yml rn-gate.yml web-admin-gate.yml"
slot_labels() { echo "$MINI_LABELS"; }
slot_busy() { pgrep -f "/Users/ghgate/slot-$1/bin/Runner.Listener" >/dev/null 2>&1; }
# 时段 (2026-11-19 Steve「晚上 22:00 之后恢复跑满 runner, 8 点之后变成 1 pg 1 fast」), 北京时间:
#   full = [22:00, 08:00) 槽 1-4 全开; day = [08:00, 22:00) 只开 $DAY_SLOTS (槽 1-3; 2026-11-19 Steve「白天最多总计 3 个」)。
# ⚠️ 唯一一份时段定义: 各 workflow changes 作业里的 route-mini-or-hosted.sh 也调 `refill.sh --window` 决定阈值与派几片上 mini。
FULL_FROM=22
FULL_UNTIL=8
DAY_SLOTS="${GH_GATE_DAY_SLOTS:-1 2 3}"
DAY_FULL_FILE="${GH_GATE_DAY_FULL_FILE:-/Users/rex/gh-gate/day-full}"
mini_window() {  # [小时 0-23]; 不给就取北京时间当前小时 (GH_GATE_HOUR 可覆盖, 供测试)
  # ⛔ 不用 TZ=Asia/Shanghai: 没有时区数据的机器 (Git Bash 实测) 静默按 UTC 算, 时段整体错 8 小时。
  #    北京时间 = UTC + 8, 无夏令时。
  # 白天也全开开关 (2026-11-19 Steve「放开白天派片」): 两端各一个, 要一起开关 ——
  #   workflow (托管机上调 --window): 仓库变量 MINI_DAY_FULL=1 -> 环境变量 GH_GATE_DAY_FULL=1;
  #   mini (槽启用): 文件 $DAY_FULL_FILE 存在。任一成立就整天按 full (多派分片、4 槽全开), 删掉即恢复按时段。
  if [ "${GH_GATE_DAY_FULL:-}" = 1 ] || [ -f "$DAY_FULL_FILE" ]; then echo full; return 0; fi
  local h="${1:-${GH_GATE_HOUR:-$(( (10#$(date -u +%H) + 8) % 24 ))}}"
  h=$((10#$h))
  if [ "$h" -ge "$FULL_FROM" ] || [ "$h" -lt "$FULL_UNTIL" ]; then echo full; else echo day; fi
}
# 启用哪些槽: 手动覆盖文件 $ENABLED_FILE 存在时以它为准 (列出槽号, 空格/换行分隔); 不存在就按时段。
# 不启用的槽空转不领活、也不算进别的槽的名次; 改文件/到点即生效, 不用动 launchd。
# 槽号 > $MAX_SLOTS 一律不启用 (覆盖文件里写了也不行): 旧的槽 5 launchd 服务没卸掉时也只是空转。
ENABLED_FILE="${GH_GATE_ENABLED_FILE:-/Users/rex/gh-gate/enabled-slots}"
slot_enabled() {
  local list
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ "$1" -ge 1 ] && [ "$1" -le "$MAX_SLOTS" ] || return 1   # MAX-SLOTS-CAP
  if [ -f "$ENABLED_FILE" ]; then list=$(tr '\n' ' ' < "$ENABLED_FILE")
  elif [ "$(mini_window)" = full ]; then return 0
  else list="$DAY_SLOTS"; fi
  case " $list " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}
# 名次在全部启用的槽里排 (共用槽池, 一份排队数): 编号比 $1 小、启用、空闲的槽数 + 1。
# ⛔ 不用 `seq 1 $((n-1))`: macOS (BSD) 的 `seq 1 0` 倒数输出「1 0」, 第 1 槽把自己和不存在的 0 号槽都算成空闲,
#    名次变 3; 只剩 1 个排队作业时 4 个 PG 槽全不起 (2026-11-19 run 44403277567 汇总作业卡 1.5 小时, 队列超时踢出)。
idle_rank() {
  local s="$1" k slots r=1
  slots=$(pool_slots) || return 1
  for k in $slots; do
    [ "$k" -lt "$s" ] || continue
    slot_busy "$k" || r=$((r + 1))
  done
  echo "$r"
}
# 睡眠/过热 (2026-11-19 实测): 10:14 loginwindow 软件睡眠后 mini 只被定时器叫到「半醒」(DarkWake), 一直没有
# 完全醒; 半醒里跑满 CI -> 12:40「Sleep Service Back to Sleep」、13:35「Dark Wake Thermal Emergency」, 每次失联十几
# 分钟。所以领活前先声明一次用户活动 (caffeinate -u = 动一下鼠标, 半醒 -> 完全醒), 作业期间持有「禁止系统睡眠」
# (caffeinate -i -s -w <pid>, 作业结束自动释放; 本机 pmset sleep 本来就是 0); 系统记了温度/性能告警就先不领新活。
full_wake() { command -v caffeinate >/dev/null 2>&1 && caffeinate -u -t 2 >/dev/null 2>&1; }
# 半醒检测 (Steve 2026-11-19「确保以后不会让他处于半醒状态」): pmset -g systemstate 的能力里没有 Graphics = 半醒
# (日志里半醒是 [CDNPB], 完全醒是 [CDNVA])。补位循环半醒时也在跑, 每圈查一次, 半醒就 full_wake (= 动一下鼠标),
# 一分钟内拉回完全醒; 不改任何电源设置。读不到 systemstate 就不动 (不把「不知道」当半醒)。
in_darkwake() {
  command -v pmset >/dev/null 2>&1 || return 1
  local caps
  caps=$(pmset -g systemstate 2>/dev/null | grep -i "capabilities") || return 1
  [ -n "$caps" ] || return 1
  ! printf '%s' "$caps" | grep -qi "graphics"
}
awake_guard() {
  in_darkwake || return 0
  log "darkwake detected ($(pmset -g systemstate 2>/dev/null | grep -i capabilities | tr -s ' ')), promoting to full wake"
  full_wake
}
# 温度压力 (Steve 2026-11-19 问「是不是 java 长时间负载导致」): 完全醒时 2 个作业 CPU 9.2W、压力 Nominal;
# 但晚上 4 槽长时间满载要有更早的刹车。beat 每分钟用 powermetrics 读一次压力写进 $PRESSURE_FILE
# (Nominal/Moderate/Heavy/Trapping/Sleeping), 到 Heavy 及以上就不领新活, 手上的不打断。
PRESSURE_FILE="${GH_GATE_PRESSURE_FILE:-/Users/rex/gh-gate/thermal-pressure}"
thermal_hot() {  # 压力 Heavy 及以上, 或 pmset -g therm 里有告警级别 (不是「No ... recorded」) 就算热
  grep -qiE "^(heavy|trapping|sleeping)" "$PRESSURE_FILE" 2>/dev/null && return 0
  command -v pmset >/dev/null 2>&1 || return 1
  pmset -g therm 2>/dev/null | grep -i "warning level" | grep -viq "no .*warning level has been recorded"
}

LOCK="${GH_GATE_RELEASE_LOCK:-/Users/rex/cretas-build/.build.lock}"
lock_held() { [ -d "$LOCK" ] && ps -p "$(cat "$LOCK/pid" 2>/dev/null || echo 0)" >/dev/null 2>&1; }

pool_slots() {
  local agents pid status label slot
  agents=$(launchctl list) || return 1
  while read -r pid status label; do
    case "$label" in com.cretas.gh-gate-*) slot=${label##*-} ;; *) continue ;; esac
    case "$pid" in ''|*[!0-9]*) continue ;; esac
    slot_enabled "$slot" && echo "$slot"
  done <<< "$agents"
  return 0
}

pool_capacity() {
  local slots slot eligible=0 idle=0
  if lock_held || thermal_hot || in_darkwake; then echo '0:0'; return; fi
  slots=$(pool_slots) || return 1
  for slot in $slots; do
    eligible=$((eligible + 1))
    slot_busy "$slot" || idle=$((idle + 1))
  done
  echo "$eligible:$idle"
}

if [ "${1:-}" = "--labels-of" ]; then slot_labels "${2:?slot}"; exit 0; fi
if [ "${1:-}" = "--queue-labels" ]; then echo "$MINI_LABELS"; exit 0; fi
if [ "${1:-}" = "--workflows" ]; then echo "$MINI_WORKFLOWS"; exit 0; fi
if [ "${1:-}" = "--max-slots" ]; then echo "$MAX_SLOTS"; exit 0; fi
if [ "${1:-}" = "--idle-rank-of" ]; then idle_rank "${2:?slot}"; exit $?; fi
if [ "${1:-}" = "--enabled-of" ]; then if slot_enabled "${2:?slot}"; then echo yes; else echo no; fi; exit 0; fi
if [ "${1:-}" = "--window" ]; then mini_window "${2:-}"; exit 0; fi
if [ "${1:-}" = "--darkwake" ]; then if in_darkwake; then echo yes; else echo no; fi; exit 0; fi
if [ "${1:-}" = "--thermal-hot" ]; then if thermal_hot; then echo yes; else echo no; fi; exit 0; fi
if [ "${1:-}" = "--capacity" ]; then pool_capacity; exit $?; fi
source "$(dirname "${BASH_SOURCE[0]}")/queue-snapshot.sh" || exit 1
if [ "${1:-}" = "--queue-snapshot" ]; then
  mini_queue_snapshot "${GITHUB_REPOSITORY:?repository}" "" "$MINI_WORKFLOWS" "$MINI_LABELS"
  exit $?
fi
SLOT="${1:?slot}"
export PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
REPO=cretasfuture-ai/cretas
LABEL=$(slot_labels "$SLOT")
PKG=/Users/ghgate/runner-pkg
WORK=/Users/ghgate/slot-$SLOT
LOG=/Users/rex/gh-gate/slot-$SLOT.log

log() { echo "$(date '+%F %T') slot=$SLOT $*" >> "$LOG"; }

# 按需起 runner (Steve 2026-11-18「没跑的时候不占内存, 用的时候才占」): 空闲时只有这个循环 (~10MB),
# 不常驻 Runner.Listener (~120MB/槽)。GitHub 上排队等任一 mini 标签 ($MINI_LABELS) 的 job 数 >= 本槽在全部启用槽里的
# 空闲名次才起。排队数缓存在 $QCACHE, 20 秒内各槽共用一次查询 (省 API 额度)。
QCACHE=/Users/rex/gh-gate/queue-snapshot.cache
IDLE_KILL_SEC=180
fresh_queue_cache() {
  local touched
  touched=$(stat -f %m "$QCACHE" 2>/dev/null) || return 1
  [ "$(( $(date +%s) - touched ))" -lt 20 ]
}
mini_queued() {
  local snapshot
  if ! fresh_queue_cache && mkdir "$QCACHE.lock" 2>/dev/null; then
    if ! snapshot=$(mini_queue_snapshot "$REPO" "" "$MINI_WORKFLOWS" "$MINI_LABELS" 2>>"$LOG"); then
      log "queue snapshot unavailable"
      snapshot=unavailable
    fi
    printf '%s\n' "$snapshot" > "$QCACHE.tmp" && mv "$QCACHE.tmp" "$QCACHE"
    rmdir "$QCACHE.lock"
  fi
  fresh_queue_cache && IFS= read -r snapshot < "$QCACHE" || return 1
  [[ "$snapshot" =~ ^[0-9]+\ [0-9]+$ ]] || return 1
  echo "${snapshot% *}"
}

# Publish dispatchable capacity, including zero while paused or full.
HB_STAMP=/Users/rex/gh-gate/heartbeat.stamp
HB_EVERY=60
beat() {
  local age=999
  [ -f "$HB_STAMP" ] && age=$(( $(date +%s) - $(stat -f %m "$HB_STAMP") ))
  [ "$age" -ge "$HB_EVERY" ] || return 0
  touch "$HB_STAMP"  # 先占位: 同一分钟别的槽不再写
  local pm p= line capacity
  # 先落变量再解析, 不走管道 (闸 test-pipeline-tail-swallows-rc.sh): powermetrics 失败 (sudo 不放行) 要记一行, 不是静默空值。
  if pm=$(sudo -n powermetrics --samplers thermal -i 1000 -n 1 2>/dev/null); then
    while IFS= read -r line; do
      case "$line" in *"Current pressure level:"*) p=${line##*Current pressure level:}; p=${p# }; p=${p# }; break;; esac
    done <<< "$pm"
  else
    log "powermetrics failed rc=$?"
  fi
  if [ -n "$p" ]; then
    [ "$p" = "$(cat "$PRESSURE_FILE" 2>/dev/null)" ] || log "thermal pressure: $p"
    echo "$p" > "$PRESSURE_FILE"
  fi
  if capacity=$(pool_capacity 2>>"$LOG"); then
    gh variable set MINI_POOL_CAPACITY --repo "$REPO" --body "$(date +%s):$capacity" >/dev/null 2>>"$LOG" || log "heartbeat write failed"
  else
    log "capacity snapshot unavailable"
  fi
  reap_stale_merge_groups
}

# 失效合并组清理 (2026-11-19 Steve「加」): 合并队列每次重排都给后面的组重起整套检查, 旧组的 run 不会被取消,
# 当天 139 个失效组 run 堵住托管机、队首等了 3 个多小时。任一槽每 $REAP_EVERY 秒在后台跑一次
# reap-stale-merge-groups.sh (mkdir 锁防并发, 结果一行追加到 $REAP_LOG)。mini 离线时没人清, 只是回到原状。
REAP_STAMP=/Users/rex/gh-gate/reap.stamp
REAP_EVERY=300
REAP_LOG=/Users/rex/gh-gate/reap.log
reap_stale_merge_groups() {
  local age=999
  [ -f "$REAP_STAMP" ] && age=$(( $(date +%s) - $(stat -f %m "$REAP_STAMP") ))
  [ "$age" -ge "$REAP_EVERY" ] || return 0
  mkdir "$REAP_STAMP.lock" 2>/dev/null || {
    # 锁目录超过 15 分钟算持锁进程已死
    [ -n "$(find "$REAP_STAMP.lock" -maxdepth 0 -mmin +15 2>/dev/null)" ] && rmdir "$REAP_STAMP.lock" 2>/dev/null
    return 0; }
  touch "$REAP_STAMP"
  ( out=$(/bin/bash /Users/rex/gh-gate/reap-stale-merge-groups.sh 2>&1); rc=$?
    printf '%s rc=%s %s\n' "$(date '+%F %T')" "$rc" "${out##*$'\n'}" >> "$REAP_LOG"
    rmdir "$REAP_STAMP.lock" ) </dev/null >/dev/null 2>&1 &
}

log "start label=$LABEL"
paused=0
while :; do
  awake_guard
  beat
  if ! slot_enabled "$SLOT"; then
    [ "$paused" = 1 ] || log "disabled (not in $ENABLED_FILE), idle"
    paused=1; sleep 30; continue
  fi
  [ "$paused" = 1 ] && log "enabled again"
  paused=0
  if lock_held; then log "release build holds lock, wait"; sleep 15; continue; fi
  # 残留的陈旧锁目录 (持锁进程已死) 超过 2 分钟就清掉
  [ -d "$QCACHE.lock" ] && [ -n "$(find "$QCACHE.lock" -maxdepth 0 -mmin +2 2>/dev/null)" ] && rmdir "$QCACHE.lock" 2>/dev/null
  if thermal_hot; then
    [ "${hot:-0}" = 1 ] || log "thermal warning recorded, not taking new jobs: $(pmset -g therm 2>/dev/null | grep -i 'warning level' | tr '
' ' ')"
    hot=1; sleep 60; continue
  fi
  [ "${hot:-0}" = 1 ] && log "thermal warning cleared"
  hot=0
  q=$(mini_queued) || { sleep 20; continue; }
  rank=$(idle_rank "$SLOT") || { sleep 20; continue; }
  if [ "$q" -lt "$rank" ] || ! slot_enabled "$SLOT" || lock_held || thermal_hot; then sleep 20; continue; fi
  name="canada-mini-gate-$SLOT-$(date +%m%d%H%M%S)"
  # 每个槽注册全部 mini 标签 ($MINI_LABELS: 公共 cretas-mini + 旧 cretas-mini-pg / cretas-mini-fast), 什么作业都能领。
  lab_args=(); for l in $LABEL; do lab_args+=(-f "labels[]=$l"); done
  jit=$(gh api -X POST "repos/$REPO/actions/runners/generate-jitconfig" \
        -f name="$name" -F runner_group_id=1 "${lab_args[@]}" -f work_folder="../work-$SLOT" \
        -q .encoded_jit_config 2>>"$LOG")
  if [ -z "$jit" ]; then log "jitconfig failed, retry in 30s"; sleep 30; continue; fi
  full_wake
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
  # 作业期间禁止系统睡眠 (随 runner 进程退出自动释放)。
  command -v caffeinate >/dev/null 2>&1 && caffeinate -i -s -w "$pid" >/dev/null 2>&1 &
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
    awake_guard
    beat
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
