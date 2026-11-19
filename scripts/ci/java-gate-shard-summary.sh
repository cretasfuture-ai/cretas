#!/usr/bin/env bash
# Java Gate 汇总作业 (fast-contracts / postgres-reversal-concurrency) 的判定。
# 用法: java-gate-shard-summary.sh <汇总名> <分片 needs.result>
#
# 判定:
#   success                         ⇒ 0
#   cancelled 且本 run 是 pull_request 事件, 且同一 workflow、同一 PR 头 SHA 上已有 id 更大的 run
#                                   ⇒ 0 (SUPERSEDED: 本 run 被并发组取消, 结论交给那条更新的 run)
#   其它 (failure / skipped / cancelled 但没有更新的 run / API 读不到) ⇒ 1
#
# 为什么 (2026-11-19 #1935 #1944): PR 开好后再加 ci-hosted 标签 ⇒ labeled 事件再起一轮,
# 第一轮被 concurrency cancel-in-progress 取消; 汇总作业是 always() 照跑, 读到 cancelled 报 failure,
# 这条 failure 留在同一个头 SHA 上, 合并队列拒「2 of 12 required status checks are failing」,
# 只能手动 rerun 被取消的那轮。
#
# ⛔ 不改成 `!cancelled()` 跳过: 跳过的必需检查算通过, 手动取消 / 没有后继的取消会被当成绿。
#    这里只在「能读到更新的 run」时放行, 读不到 (API 失败、merge_group、真取消) 一律红。
# 环境: GITHUB_API_URL GITHUB_REPOSITORY GITHUB_RUN_ID GITHUB_TOKEN EVENT_NAME HEAD_SHA
#       CURL (测试用, 默认 curl)
set -uo pipefail

name=${1:?汇总名}
result=${2-}
CURL=${CURL:-curl}
echo "READING: ${name}-shard result=${result:-<空>} event=${EVENT_NAME:-} run=${GITHUB_RUN_ID:-} head=${HEAD_SHA:-}"

[ "$result" = "success" ] && exit 0

red() { echo "::error::${name} 分片结论是 ${result:-<空>}, 不是 success${1:+ ($1)}"; exit 1; }

[ "$result" = "cancelled" ] || red
[ "${EVENT_NAME:-}" = "pull_request" ] || red "事件 ${EVENT_NAME:-<空>} 没有后继 run 可交接"
[ -n "${HEAD_SHA:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ] || red "缺 HEAD_SHA/GITHUB_RUN_ID/GITHUB_REPOSITORY"

api=${GITHUB_API_URL:-https://api.github.com}/repos/$GITHUB_REPOSITORY
get() { "$CURL" -fsS --max-time 20 -H "Authorization: Bearer ${GITHUB_TOKEN:-}" -H "Accept: application/vnd.github+json" "$1"; }

wf=$(get "$api/actions/runs/$GITHUB_RUN_ID" | jq -r '.workflow_id // empty') || wf=
[ -n "$wf" ] || red "读不到本 run 的 workflow_id"
newer=$(get "$api/actions/workflows/$wf/runs?head_sha=$HEAD_SHA&event=pull_request&per_page=100" \
  | jq -r --argjson me "$GITHUB_RUN_ID" '[.workflow_runs[]? | select(.id > $me) | .id] | sort | last // empty') || newer=
[ -n "$newer" ] || red "同一头 SHA 上没有更新的 run, 是真取消"
echo "SUPERSEDED: run $GITHUB_RUN_ID 被同一头 SHA 上更新的 run $newer 取代, ${name} 的结论以那条为准"
exit 0
