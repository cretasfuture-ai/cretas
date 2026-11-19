#!/usr/bin/env bash
# PR 检查路由: canada-mini 优先, mini 满了溢出到 GitHub 托管 (2026-11-18 Java Gate; 2026-11-19 扩到别的检查)。
#
# 为什么: canada-mini 自建 runner 最多 4 个共用槽 (2026-11-19 21:5x Steve「不跑5个了……重点是这4个槽不要只给pg用」),
# 每个槽什么作业都领 (公共标签 cretas-mini); 槽满后作业在 mini 上排队, 可能比托管还慢。本脚本在各 workflow
# ubuntu-latest 上的 `changes` 作业里跑 (⛔ 不能放 mini 上: mini 一满它自己也会排队), 数 mini 上正在排队的作业
# (所有上 mini 的 workflow、所有 mini 标签一起算: 共用一个槽池), 决定本次 run 的 mini 作业落哪。
#
# 决定顺序 (先到先得):
#   1. fork PR (非 merge_group 且 head 仓库 != 本仓库)  -> hosted reason=fork      (陌生人代码不上我们的机器)
#   2. PR 带 ci-hosted 标签                               -> hosted reason=ci-hosted-label
#   3. 阈值不是非负整数                                   -> hosted reason=bad-threshold
#   3b. mini 心跳 MINI_HEARTBEAT 缺失/过期 (> MINI_HEARTBEAT_MAX_AGE 秒) -> hosted reason=mini-offline
#   4. 数 $MINI_WORKFLOWS 的 queued/in_progress 运行里 status=queued 且带任一 $MINI_COUNT_LABELS 的作业 (排除本 run)
#        读数失败 (gh 出错 / JSON 解析不了)               -> hosted reason=api-error (打 ::warning::, 不静默)
#        queued <  阈值                                   -> mini   reason=below-threshold
#        queued >= 阈值                                   -> hosted reason=mini-full
#
# 阈值按时段 (时段唯一定义在 canada-mini-gate/refill.sh --window; 本脚本调它, 读不到按 day):
#   day  (08-22, 启用槽 1-3)  MINI_QUEUE_THRESHOLD_DAY  默认 2 = 槽数 - 1
#   full (22-08, 启用槽 1-4)  MINI_QUEUE_THRESHOLD_FULL 默认 3 = 槽数 - 1
#   含义: 别的 run 已经排着 >= 「槽数 - 1」个作业 = 前面压着差不多一整轮, 本 run 再加 3-6 个作业要多等一轮以上
#   (mini 上一片 Java 5-7 分钟), 而托管排队近期均值 2-5 分钟 (Java 分片 17-20 分钟) ⇒ 走托管。
#   MINI_QUEUE_THRESHOLD 给了就用它 (测试/临时覆盖), 不再按时段。
#
# 每次都打一行 `ROUTE=<mini|hosted> queued=<n|na> threshold=<t> reason=<...> label=<标签> window=<day|full>`,
# 并向 $GITHUB_OUTPUT 写 `<ROUTE_OUTPUT_KEY>=<标签>|ubuntu-latest` 与 `mini_window=<day|full>`。
# 脚本本身恒 exit 0 (路由仪器坏了走托管, 不让判定作业红)。
# workflow 里各作业的 runs-on 仍带「本仓库分支或合并队列」守卫, 本脚本输出只能把作业从 mini 挪到托管,
# 不能反过来 (双保险, 闸 scripts/tests/test-public-repo-hosted-only.sh 逐字钉住表达式)。
#
# 输入 (env):
#   GITHUB_REPOSITORY  本仓库 owner/name (必需)
#   EVENT_NAME         github.event_name
#   HEAD_REPO          github.event.pull_request.head.repo.full_name (merge_group 下为空)
#   PR_LABELS          PR 标签 JSON 数组 (toJSON(github.event.pull_request.labels.*.name)), 可空
#   GITHUB_RUN_ID      本 run id (计数时排除)
#   MINI_QUEUE_THRESHOLD  给了就固定用它; 不给按时段取 _DAY / _FULL
#   MINI_WINDOW        day|full, 不给就调 refill.sh --window (GH_GATE_DAY_FULL 照常透传)
#   MINI_LABEL         默认 cretas-mini (mini 路由时输出它)
#   MINI_COUNT_LABELS  空格分隔, 默认 refill.sh 的 MINI_LABELS (带其中任一标签的排队作业都算)
#   MINI_WORKFLOWS     空格分隔的 workflow 文件名, 默认见下 (只数这些 workflow 的 run)
#   ROUTE_OUTPUT_KEY   写进 $GITHUB_OUTPUT 的键名, 默认 runner
#   GH                 gh 可执行文件 (测试注入假 gh), 默认 gh
#   MINI_HEARTBEAT     mini 最近一次心跳的 Unix 秒 (vars.MINI_HEARTBEAT, refill.sh 每分钟写)
#   MINI_HEARTBEAT_MAX_AGE 心跳最大年龄秒数, 默认 180
#   ROUTE_NOW          心跳年龄的参照 Unix 秒 (测试注入); 不给则取本 run 的 run_started_at, 读不到退回 date +%s
set -uo pipefail

REFILL_SH="$(dirname "${BASH_SOURCE[0]}")/canada-mini-gate/refill.sh"
WINDOW="${MINI_WINDOW:-}"
[ -n "$WINDOW" ] || WINDOW=$(bash "$REFILL_SH" --window 2>/dev/null) || WINDOW=""
[ "$WINDOW" = full ] || WINDOW=day   # 读不到按 day: 阈值更低, 只会少派
if [ -n "${MINI_QUEUE_THRESHOLD:-}" ]; then THRESHOLD="$MINI_QUEUE_THRESHOLD"
elif [ "$WINDOW" = full ]; then THRESHOLD="${MINI_QUEUE_THRESHOLD_FULL:-3}"
else THRESHOLD="${MINI_QUEUE_THRESHOLD_DAY:-2}"; fi
LABEL="${MINI_LABEL:-cretas-mini}"
# ⚠️ 两份口径 (排队标签 / workflow 清单) 与 refill.sh 的 MINI_LABELS / MINI_WORKFLOWS 逐字一致, 闸 test-route-mini-or-hosted.sh 核对。
COUNT_LABELS="${MINI_COUNT_LABELS:-cretas-mini cretas-mini-pg cretas-mini-fast}"
MINI_WORKFLOWS="${MINI_WORKFLOWS:-java-gate-pr.yml rn-gate.yml web-admin-gate.yml}"
GH="${GH:-gh}"
OUTPUT_KEY="${ROUTE_OUTPUT_KEY:-runner}"
REPO="${GITHUB_REPOSITORY:-}"
EVENT_NAME="${EVENT_NAME:-}"
HEAD_REPO="${HEAD_REPO:-}"
PR_LABELS="${PR_LABELS:-[]}"
RUN_ID="${GITHUB_RUN_ID:-}"

emit() {
  local route="$1" queued="$2" reason="$3" runner
  if [ "$route" = "mini" ]; then runner="$LABEL"; else runner="ubuntu-latest"; fi
  echo "ROUTE=$route queued=$queued threshold=$THRESHOLD reason=$reason label=$LABEL window=$WINDOW"
  if [ "$reason" = "api-error" ] || [ "$reason" = "bad-threshold" ]; then
    echo "::warning::mini 路由读不到排队数 ($reason), 本次走托管"
  fi
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "$OUTPUT_KEY=$runner" >> "$GITHUB_OUTPUT"
    echo "mini_window=$WINDOW" >> "$GITHUB_OUTPUT"
  fi
  exit 0
}

# 1. fork PR: merge_group 只来自本仓库; 其它事件比对 head 仓库。
if [ "$EVENT_NAME" != "merge_group" ]; then
  if [ -z "$REPO" ] || [ "$HEAD_REPO" != "$REPO" ]; then
    emit hosted na fork
  fi
fi

# 2. ci-hosted 标签 (逃生口)。
if printf '%s' "$PR_LABELS" | jq -e 'index("ci-hosted") != null' >/dev/null 2>&1; then
  emit hosted na ci-hosted-label
fi

# 3. 阈值。
case "$THRESHOLD" in
  ''|*[!0-9]*) emit hosted na bad-threshold ;;
esac

# 3b. mini 在线心跳 (2026-11-19: mini 一天失联三次, 失联时排队数是 0, 路由照样派过去, 作业卡死不回落)。
#     mini 的补位脚本每分钟把当前 Unix 秒写进仓库变量 MINI_HEARTBEAT (refill.sh beat), workflow 经 vars 传入。
#     没有 / 不是数字 / 超过 MINI_HEARTBEAT_MAX_AGE 秒 (默认 180 = 漏 3 次) -> hosted reason=mini-offline, 不再数排队。
HEARTBEAT="${MINI_HEARTBEAT:-}"
HB_MAX_AGE="${MINI_HEARTBEAT_MAX_AGE:-180}"
# ⚠️ 年龄的参照时间是「本次 run 开始」, 不是「现在」(2026-11-19 实测): vars.MINI_HEARTBEAT 在 run 创建时就定格,
#    changes 作业要等托管机排到才跑。19:44 合并组排了 4 分钟 -> 判 240s; 20:19 #1959 排了 27 分钟 -> 判 1642s,
#    两次 mini 都在线 (心跳都正好在 run 创建前几十秒)。托管越堵 -> 越判离线 -> 越往托管派, 自我加强。
#    参照 = GET runs/$GITHUB_RUN_ID 的 run_started_at (重跑时是本次重跑的开始); 读不到退回「现在」(偏向托管, 安全)。
#    代价: run 排队期间 mini 才掉线的情况看不见 —— 与「路由只在 run 开始看一次」本来就一样。
hb_ref_time() {
  local s=""
  if [ -n "${ROUTE_NOW:-}" ]; then echo "$ROUTE_NOW now"; return; fi
  if [ -n "$REPO" ] && [ -n "$RUN_ID" ]; then
    local j
    # 先落变量再解析, 不走管道 (闸 test-pipeline-tail-swallows-rc.sh)。
    if j=$("$GH" api "repos/$REPO/actions/runs/$RUN_ID" 2>/dev/null); then
      s=$(printf '%s' "$j" | jq -er '.run_started_at | fromdateiso8601' 2>/dev/null) || s=""
      s=${s%$'\r'}
    fi
  fi
  case "$s" in ''|*[!0-9]*) echo "$(date +%s) now-fallback" ;; *) echo "$s run_started_at" ;; esac
}
read -r HB_NOW HB_REF_SRC <<EOF_REF
$(hb_ref_time)
EOF_REF
echo "READING: heartbeat_ref=$HB_REF_SRC ref=$HB_NOW heartbeat=${HEARTBEAT:-<empty>}"
mini_offline() {
  echo "::warning::mini 心跳 ${1} (上限 ${HB_MAX_AGE}s), 本次 $LABEL 走托管"
  emit hosted na mini-offline
}
case "$HEARTBEAT" in ''|*[!0-9]*) mini_offline "缺失或不是数字: '${HEARTBEAT}'" ;; esac
HB_AGE=$((HB_NOW - HEARTBEAT))
[ "$HB_AGE" -gt "$HB_MAX_AGE" ] && mini_offline "${HB_AGE}s 前"   # HEARTBEAT-AGE-CHECK

# 4. 数 mini 上排队的作业 (共用槽池: 全仓 run 里只看 $MINI_WORKFLOWS, 任一 mini 标签都算)。任何一步失败 -> api-error。
count_queued() {
  local status runs ids id jobs n total=0 wfs labels
  wfs=$(printf '%s\n' $MINI_WORKFLOWS | jq -Rsc 'split("\n") | map(select(length > 0))') || return 1
  labels=$(printf '%s\n' $COUNT_LABELS | jq -Rsc 'split("\n") | map(select(length > 0))') || return 1
  for status in queued in_progress; do
    runs=$("$GH" api "repos/$REPO/actions/runs?status=$status&per_page=100") || return 1
    # 结构不对 (不是 {workflow_runs: [...]}) 就是没读到, ⛔ 不当成 0。
    printf '%s' "$runs" | jq -e '.workflow_runs | type == "array"' >/dev/null 2>&1 || return 1
    # 只取 path 末段在清单里的 run (别的 workflow 没有 mini 作业, 不为它们花 API 额度)。
    ids=$(printf '%s' "$runs" | jq -r --argjson w "$wfs" \
      '.workflow_runs[] | select(((.path // "") | split("/") | last) as $p | $w | index($p) != null) | .id' | tr -d '\r') || return 1
    for id in $ids; do
      [ "$id" = "$RUN_ID" ] && continue
      jobs=$("$GH" api "repos/$REPO/actions/runs/$id/jobs?filter=latest&per_page=100") || return 1
      n=$(printf '%s' "$jobs" | jq -er --argjson l "$labels" \
        '[.jobs[] | select(.status == "queued" and ((.labels // []) as $j | any($l[]; . as $x | $j | index($x) != null)))] | length' | tr -d '\r') || return 1
      total=$((total + n))
    done
  done
  echo "$total"
}

if [ -z "$REPO" ]; then
  emit hosted na api-error
fi
if ! QUEUED=$(count_queued 2>/dev/null) || [ -z "$QUEUED" ]; then
  emit hosted na api-error
fi

if [ "$QUEUED" -lt "$THRESHOLD" ]; then
  emit mini "$QUEUED" below-threshold
else
  emit hosted "$QUEUED" mini-full
fi
