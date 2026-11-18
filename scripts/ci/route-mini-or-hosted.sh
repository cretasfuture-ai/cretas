#!/usr/bin/env bash
# Java Gate 分片路由: canada-mini 优先, mini 满了溢出到 GitHub 托管 (2026-11-18)。
#
# 为什么: canada-mini 自建 runner 固定 4 槽 (标签 cretas-mini-pg); 槽满后作业在 mini 上排队,
# 可能比托管还慢。本脚本在 ubuntu-latest 上的 `changes` 作业里跑 (⛔ 不能放 mini 上: mini 一满它
# 自己也会排队), 数 mini 标签上正在排队的作业, 决定本次 run 的分片落哪。
#
# 决定顺序 (先到先得):
#   1. fork PR (非 merge_group 且 head 仓库 != 本仓库)  -> hosted reason=fork      (陌生人代码不上我们的机器)
#   2. PR 带 ci-hosted 标签                               -> hosted reason=ci-hosted-label
#   3. 阈值不是非负整数                                   -> hosted reason=bad-threshold
#   4. 数 Java Gate queued/in_progress 运行里 status=queued 且带 mini 标签的作业 (排除本 run)
#        读数失败 (gh 出错 / JSON 解析不了)               -> hosted reason=api-error (打 ::warning::, 不静默)
#        queued <  阈值                                   -> mini   reason=below-threshold
#        queued >= 阈值                                   -> hosted reason=mini-full
#
# 每次都打一行 `ROUTE=<mini|hosted> queued=<n|na> threshold=<t> reason=<...>`, 并向 $GITHUB_OUTPUT
# 写 `runner=cretas-mini-pg|ubuntu-latest`。脚本本身恒 exit 0 (路由仪器坏了走托管, 不让判定作业红)。
# workflow 里各分片的 runs-on 仍带「本仓库分支或合并队列」守卫, 本脚本输出只能把作业从 mini 挪到托管,
# 不能反过来 (双保险, 闸 scripts/tests/test-public-repo-hosted-only.sh 逐字钉住表达式)。
#
# 输入 (env):
#   GITHUB_REPOSITORY  本仓库 owner/name (必需)
#   EVENT_NAME         github.event_name
#   HEAD_REPO          github.event.pull_request.head.repo.full_name (merge_group 下为空)
#   PR_LABELS          PR 标签 JSON 数组 (toJSON(github.event.pull_request.labels.*.name)), 可空
#   GITHUB_RUN_ID      本 run id (计数时排除)
#   MINI_QUEUE_THRESHOLD  默认 4
#   MINI_LABEL         默认 cretas-mini-pg
#   ROUTE_WORKFLOW     默认 java-gate-pr.yml
#   GH                 gh 可执行文件 (测试注入假 gh), 默认 gh
set -uo pipefail

THRESHOLD="${MINI_QUEUE_THRESHOLD:-4}"
LABEL="${MINI_LABEL:-cretas-mini-pg}"
WORKFLOW="${ROUTE_WORKFLOW:-java-gate-pr.yml}"
GH="${GH:-gh}"
REPO="${GITHUB_REPOSITORY:-}"
EVENT_NAME="${EVENT_NAME:-}"
HEAD_REPO="${HEAD_REPO:-}"
PR_LABELS="${PR_LABELS:-[]}"
RUN_ID="${GITHUB_RUN_ID:-}"

emit() {
  local route="$1" queued="$2" reason="$3" runner
  if [ "$route" = "mini" ]; then runner="$LABEL"; else runner="ubuntu-latest"; fi
  echo "ROUTE=$route queued=$queued threshold=$THRESHOLD reason=$reason"
  if [ "$reason" = "api-error" ] || [ "$reason" = "bad-threshold" ]; then
    echo "::warning::Java Gate 路由读不到 mini 排队数 ($reason), 本次走托管"
  fi
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "runner=$runner" >> "$GITHUB_OUTPUT"
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

# 4. 数 mini 上排队的作业。任何一步失败 -> api-error。
count_queued() {
  local status runs ids id jobs n total=0
  for status in queued in_progress; do
    runs=$("$GH" api "repos/$REPO/actions/workflows/$WORKFLOW/runs?status=$status&per_page=100") || return 1
    # 结构不对 (不是 {workflow_runs: [...]}) 就是没读到, ⛔ 不当成 0。
    printf '%s' "$runs" | jq -e '.workflow_runs | type == "array"' >/dev/null 2>&1 || return 1
    ids=$(printf '%s' "$runs" | jq -r '.workflow_runs[].id' | tr -d '\r') || return 1
    for id in $ids; do
      [ "$id" = "$RUN_ID" ] && continue
      jobs=$("$GH" api "repos/$REPO/actions/runs/$id/jobs?filter=latest&per_page=100") || return 1
      n=$(printf '%s' "$jobs" | jq -er --arg l "$LABEL" \
        '[.jobs[] | select(.status == "queued" and ((.labels // []) | index($l) != null))] | length' | tr -d '\r') || return 1
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
