#!/usr/bin/env bash
# pr-watch-merge.sh — 一个 PR 一条监视: 等全绿 → 落后 base 就 update-branch 再等全绿 → squash 合并。
# 中间不叫醒 organizer, 只在最终结果时退出 (配合 Bash run_in_background, 结束时只通知一次)。
#
# 为什么 (2026-11-18 一周效果检查): organizer 花费里 33% 是被自己挂的后台命令通知叫醒 (901 次),
# 典型是「监视绿了 → 醒来 update-branch → 再挂监视 → 再醒来合并」。本脚本把这一串收成一次通知。
#
# 用法 (在仓库任意 worktree 里, 后台跑):
#   bash .claude/tools/pr-watch-merge.sh <PR号> [--timeout-min 60] [--no-merge] [--max-updates 5]
# 退出码 / 最后一行 RESULT=:
#   0 MERGED (或 --no-merge 时 GREEN: 全绿且不落后)
#   1 RED        有检查 fail/cancel (列出名字), 不合并
#   2 UNMEASURED 超时 / update 次数用完 / 合并命令失败 / 一直读不到检查 —— 不是结论, 要人看
#   3 CONFLICT   和 base 冲突
#   4 CLOSED     PR 被关了
# 规矩 (全局 CLAUDE.md「等 CI」): 检查为空不算绿; 网络错误重试不当结论; 头 SHA 变了按新头判断; ⛔ 不带红合并。
# 合并用 --match-head-commit, 合并前一刻头变了会被 GitHub 拒绝。
# 合并队列 (Steve 2026-11-18 拍板开): base 开了队列时, 全绿后不 update-branch (队列自己在最新 base 上叠合重跑),
#   `gh pr merge` 只是入队, PR 仍 OPEN; 之后查 isInMergeQueue 等到 MERGED 或出队。出队 = RED (队列里的检查没过),
#   入队命令后连续 3 轮都不在队列也没合并 = UNMEASURED (没入上队, 要人看)。RESULT 行带 queue=on。
set -u

PR="${1:-}"; shift || true
[[ "$PR" =~ ^[0-9]+$ ]] || { echo "usage: pr-watch-merge.sh <PR> [--timeout-min N] [--timeout-sec N] [--no-merge] [--max-updates N]" >&2; exit 2; }
TIMEOUT_SEC=3600; MERGE=1; MAX_UPDATES=5
while [ $# -gt 0 ]; do
  case "$1" in
    --timeout-min) TIMEOUT_SEC=$(( $2 * 60 )); shift 2;;
    --timeout-sec) TIMEOUT_SEC=$2; shift 2;;
    --no-merge) MERGE=0; shift;;
    --max-updates) MAX_UPDATES=$2; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done
INTERVAL="${PR_WATCH_INTERVAL:-45}"
GH="${PR_WATCH_GH:-gh}"
START=$(date +%s)
updates=0; neterr=0; last=""

say() { [ "$1" != "$last" ] && echo "$(date +%H:%M:%S) $1"; last="$1"; }
finish() { echo "RESULT=$1 pr=$PR ${2:-}"; exit "$3"; }
expired() { [ $(( $(date +%s) - START )) -ge "$TIMEOUT_SEC" ]; }
nap() { sleep "$INTERVAL"; }
# 一次 GraphQL 读: "<base 有队列 true|false> <state> <isInMergeQueue> <mergeCommit oid>"
qstate() {
  "$GH" api graphql -F owner='{owner}' -F name='{repo}' -F n="$PR" -F b="$1" \
    -f query='query($owner:String!,$name:String!,$n:Int!,$b:String!){repository(owner:$owner,name:$name){mergeQueue(branch:$b){url} pullRequest(number:$n){state isInMergeQueue mergeCommit{oid}}}}' \
    --jq '[(.data.repository.mergeQueue!=null), .data.repository.pullRequest.state, .data.repository.pullRequest.isInMergeQueue, (.data.repository.pullRequest.mergeCommit.oid // "")]|map(tostring)|join(" ")' 2>&1
}
# 入队并等结果 (不返回, 一定 finish)
enqueue_and_wait() {
  local out mrc q qon s inq sha seen=0 miss=0
  say "head=${head:0:10} green, base $base has merge queue -> enqueue"
  out=$("$GH" pr merge "$PR" --match-head-commit "$head" 2>&1); mrc=$?
  while :; do
    expired && finish UNMEASURED "reason=timeout-in-queue head=$head seen=$seen queue=on" 2
    nap
    q=$(qstate "$base")
    read -r qon s inq sha <<<"$q"
    case "$s" in
      MERGED) finish MERGED "head=$head sha=$sha queue=on" 0;;
      CLOSED) finish CLOSED "head=$head queue=on" 4;;
      OPEN) ;;
      *) neterr=$((neterr+1)); say "queue state unreadable (retry #$neterr): ${q:0:80}"; continue;;
    esac
    if [ "$inq" = true ]; then seen=1; say "head=${head:0:10} in merge queue"
    elif [ "$seen" = 1 ]; then finish RED "head=$head reason=dequeued-from-merge-queue queue=on" 1
    else miss=$((miss+1)); [ "$miss" -ge 3 ] && finish UNMEASURED "reason=not-enqueued rc=$mrc out=${out:0:120} queue=on" 2
    fi
  done
}

while :; do
  expired && finish UNMEASURED "reason=timeout updates=$updates" 2

  info=$("$GH" pr view "$PR" --json state,headRefOid,mergeable,baseRefName --jq '.state+" "+.headRefOid+" "+.mergeable+" "+.baseRefName' 2>&1)
  if [ $? -ne 0 ]; then neterr=$((neterr+1)); say "pr view failed (retry #$neterr): ${info:0:80}"; nap; continue; fi
  read -r state head mergeable base <<<"$info"
  case "$state" in
    MERGED) finish MERGED "head=$head note=already-merged" 0;;
    CLOSED) finish CLOSED "head=$head" 4;;
    OPEN) ;;
    *) neterr=$((neterr+1)); say "unexpected pr view output: ${info:0:80}"; nap; continue;;
  esac
  [ "$mergeable" = "CONFLICTING" ] && finish CONFLICT "head=$head base=$base" 3

  checks=$("$GH" pr checks "$PR" --json bucket,name --jq '.[]|.bucket+"\t"+.name' 2>&1)
  crc=$?
  # gh pr checks: 有 pending 时退出码 8, 有失败时 1; 以输出为准, 输出不像检查列表才当网络错误
  if [ -z "$checks" ]; then say "head=${head:0:10} no checks yet"; nap; continue; fi
  bad=0; fail=""; pend=0; pass=0
  while IFS=$'\t' read -r bucket name; do
    case "$bucket" in
      pass) pass=$((pass+1));;
      skipping) ;;
      pending) pend=$((pend+1));;
      fail|cancel) fail="$fail${fail:+,}$name";;
      *) bad=1;;
    esac
  done <<<"$checks"
  if [ "$bad" = 1 ]; then neterr=$((neterr+1)); say "checks unreadable (rc=$crc retry #$neterr): ${checks:0:80}"; nap; continue; fi
  [ -n "$fail" ] && finish RED "head=$head failed=$fail" 1
  if [ "$pend" -gt 0 ] || [ "$pass" -eq 0 ]; then say "head=${head:0:10} pending=$pend pass=$pass"; nap; continue; fi

  # 全绿: base 开了合并队列 -> 入队 (不 update-branch, 队列在最新 base 上重跑)
  # GraphQL 报错时 jq 会把 mergeQueue!=null 算成 false (data 为 null), 不能当「没开队列」:
  # 同一次读数里 PR 状态不是 OPEN/MERGED/CLOSED 就是没读到, 重试。
  # (2026-11-18 #1866: 一次读失败被当成无队列, 走了 --squash, 被队列拒绝。)
  q=$(qstate "$base"); read -r qon qs _ <<<"$q"
  case "$qs" in OPEN|MERGED|CLOSED) ;; *) qon=unreadable;; esac
  case "$qon" in
    true) [ "$MERGE" = 0 ] && finish GREEN "head=$head updates=$updates queue=on" 0; enqueue_and_wait;;
    false) ;;
    *) neterr=$((neterr+1)); say "queue check failed (retry #$neterr): ${q:0:80}"; nap; continue;;
  esac

  # 全绿且无队列: 看是否落后 base
  behind=$("$GH" api "repos/{owner}/{repo}/compare/$base...$head" --jq .behind_by 2>&1)
  if ! [[ "$behind" =~ ^[0-9]+$ ]]; then neterr=$((neterr+1)); say "compare failed (retry #$neterr): ${behind:0:80}"; nap; continue; fi
  if [ "$behind" -gt 0 ]; then
    [ "$updates" -ge "$MAX_UPDATES" ] && finish UNMEASURED "reason=max-updates head=$head behind=$behind" 2
    updates=$((updates+1))
    say "head=${head:0:10} green but behind $base by $behind -> update-branch #$updates"
    out=$("$GH" pr update-branch "$PR" 2>&1) || { say "update-branch failed: ${out:0:80}"; nap; continue; }
    # 等头真的换了再判, 免得拿旧头的绿检查又 update 一次
    while :; do
      expired && finish UNMEASURED "reason=timeout-after-update updates=$updates" 2
      nap
      nh=$("$GH" pr view "$PR" --json headRefOid --jq .headRefOid 2>/dev/null)
      [ -n "$nh" ] && [ "$nh" != "$head" ] && break
    done
    continue
  fi

  [ "$MERGE" = 0 ] && finish GREEN "head=$head updates=$updates" 0
  say "head=${head:0:10} green and current -> merge"
  out=$("$GH" pr merge "$PR" --squash --match-head-commit "$head" 2>&1)
  mrc=$?
  st=$("$GH" pr view "$PR" --json state,mergeCommit --jq '.state+" "+(.mergeCommit.oid // "")' 2>&1)
  read -r s2 sha <<<"$st"
  [ "$s2" = "MERGED" ] && finish MERGED "head=$head sha=$sha updates=$updates" 0
  # 合并前一刻有人推了新头 (--match-head-commit 拒绝) 等情况: 重新判一轮, 最多两次
  merge_tries=$(( ${merge_tries:-0} + 1 ))
  [ "$merge_tries" -ge 2 ] && finish UNMEASURED "reason=merge-failed rc=$mrc out=${out:0:120}" 2
  say "merge failed (rc=$mrc), re-checking: ${out:0:80}"; nap
done
