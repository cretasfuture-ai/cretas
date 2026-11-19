#!/bin/bash
# ⚠️ 要在 mini 的 /bin/bash 3.2 上跑: 不用关联数组 (declare -A)、不用 ${x,,} 之类 4.x 语法。
# 取消「已经不在合并队列里的合并组」上还在排队/在跑的 workflow run。
# 用法: reap-stale-merge-groups.sh [--dry-run]
# 环境: REPO (默认 cretasfuture-ai/cretas)  GH (默认 gh, 测试用假 gh)  MIN_AGE_SEC (默认 180)
#
# 为什么 (2026-11-19 实测): main 合并队列非 strict, 每合入/踢出一个 PR 就重排, 后面每个合并组换一个新头 SHA、
# 整套检查重新起一遍; 旧组的 run 不会被取消 (merge_group 的并发组按 gh-readonly-queue/... 分支名, 每组唯一)。
# 当天全仓 251 个 run 在排、只有 9 个在跑, 其中 139 个是失效组的 ⇒ 队首 #1938 等了 3 个多小时。
# 手动取消 133 个后排队降到 88。
#
# 判定: 合并队列 entries 的 headCommit.oid 是「活的」; event=merge_group 的 run 头 SHA 不在活集合里、
# 且创建超过 MIN_AGE_SEC 秒 ⇒ 取消。活集合在列 run 之前、之后各查一次取并集 (列 run 期间新排出的组不误杀),
# 创建时间门槛再挡一次「组刚建好、队列查询还没反映出来」。
# 退出码三态 (measurement-and-wiring 硬约束 4): 0 读到了且全部处理成功 / 1 有取消失败 / 2 没量到 (队列或 run 列表读不到)。
# ⛔ 读不到队列时不取消任何东西: 空集合会把所有合并组都当成失效。
set -uo pipefail
REPO=${REPO:-cretasfuture-ai/cretas}
GH=${GH:-gh}
MIN_AGE_SEC=${MIN_AGE_SEC:-180}
DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1
owner=${REPO%%/*}; name=${REPO#*/}
Q='query($o:String!,$n:String!){repository(owner:$o,name:$n){mergeQueue(branch:"main"){entries(first:100){nodes{headCommit{oid}}}}}}'

live_oids() {  # 打印活的头 SHA; 结构不对 (mergeQueue 为 null 等) 返回非 0
  local out
  out=$("$GH" api graphql -f query="$Q" -f o="$owner" -f n="$name" \
        --jq 'if .data.repository.mergeQueue == null then error("no mergeQueue") else .data.repository.mergeQueue.entries.nodes[].headCommit.oid // empty end') || return 1
  printf '%s\n' "$out"
}

live1=$(live_oids) || { echo "READING: UNMEASURED reason=queue-read-failed(before)"; exit 2; }
runs=
for st in queued in_progress waiting pending requested; do
  r=$("$GH" api --paginate "repos/$REPO/actions/runs?event=merge_group&status=$st&per_page=100" \
      --jq ".workflow_runs[] | \"\(.id) \(.head_sha) \((now - (.created_at|fromdateiso8601))|floor)\"") \
    || { echo "READING: UNMEASURED reason=runs-read-failed status=$st"; exit 2; }
  [ -n "$r" ] && runs+="$r"$'\n'
done
live2=$(live_oids) || { echo "READING: UNMEASURED reason=queue-read-failed(after)"; exit 2; }

NL=$'\n'
live=$NL nlive=0   # 换行包住的集合, 用 case *"$NL$x$NL"* 判成员 (bash 3.2 没有关联数组)
while IFS= read -r s; do
  [ -n "$s" ] || continue
  case "$live" in *"$NL$s$NL"*) ;; *) live="$live$s$NL"; nlive=$((nlive + 1));; esac
done <<< "$live1$NL$live2"
seen=$NL
total=0 stale=0 young=0 ok=0 bad=0
while read -r id sha age; do
  [ -n "${id:-}" ] || continue
  case "$seen" in *"$NL$id$NL"*) continue;; esac
  seen="$seen$id$NL"
  total=$((total + 1))
  case "$live" in *"$NL$sha$NL"*) continue;; esac
  if [ "${age:-0}" -lt "$MIN_AGE_SEC" ]; then young=$((young + 1)); continue; fi
  stale=$((stale + 1))
  if [ "$DRY" = 1 ]; then echo "DRY: would cancel run $id head=${sha:0:10} age=${age}s"; continue; fi
  if "$GH" api -X POST "repos/$REPO/actions/runs/$id/cancel" >/dev/null 2>&1; then ok=$((ok + 1)); continue; fi
  # 取消失败: 已经结束 (409) 不算失败, 其它记失败
  st=$("$GH" api "repos/$REPO/actions/runs/$id" --jq .status 2>/dev/null) || st=
  if [ "$st" = completed ]; then ok=$((ok + 1)); else bad=$((bad + 1)); echo "CANCEL_FAILED run $id status=${st:-?}"; fi
done <<< "$runs"

echo "READING: live_groups=$nlive merge_group_runs=$total stale=$stale young_skipped=$young cancelled=$ok failed=$bad dry=$DRY"
[ "$bad" -eq 0 ] || exit 1
exit 0
