#!/usr/bin/env bash
# pr-watch-merge.sh 的场景测试: 用假 gh (PR_WATCH_GH) 驱动, 不碰 GitHub。
# 本地入口: bash .claude/tools/__tests__/test-pr-watch-merge.sh
# 每个场景断言退出码、RESULT 行, 以及「该调/不该调 merge / update-branch」。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/../pr-watch-merge.sh"
T=$(mktemp -d)
FAKE="$T/gh"
cat > "$FAKE" <<'EOF'
#!/usr/bin/env bash
S="$FAKE_GH_STATE"; echo "$*" >> "$S/calls"
h=$(cat "$S/head")
case "$1 $2" in
  "pr view")
    if [ -f "$S/neterr" ] && [ "$(cat "$S/neterr")" -gt 0 ]; then echo $(( $(cat "$S/neterr") - 1 )) > "$S/neterr"; echo "unexpected EOF" >&2; exit 1; fi
    case "$*" in
      *mergeCommit*) if [ "$(cat "$S/state")" = MERGED ]; then echo "MERGED sha-$h"; else echo "OPEN "; fi;;
      *"--jq .headRefOid"*) echo "$h";;
      *"--jq .id"*) echo "PR_node_99";;
      *) echo "$(cat "$S/state") $h $(cat "$S/mergeable") main";;
    esac;;
  "pr checks")
    c=$(( $(cat "$S/cc.$h" 2>/dev/null || echo 0) + 1 )); echo $c > "$S/cc.$h"
    f="$S/checks.$h.$c"; while [ ! -f "$f" ] && [ $c -gt 1 ]; do c=$((c-1)); f="$S/checks.$h.$c"; done
    [ -f "$f" ] && cat "$f"; [ -f "$f" ] && grep -q pending "$f" && exit 8; exit 0;;
  "pr update-branch") cat "$S/next_head" > "$S/head"; echo "updated";;
  "api repos/{owner}/{repo}/rules/branches/main") echo rules-page;;
  "api graphql")
    case "$*" in *enqueuePullRequest*) case "$*" in *"h=$h"*) echo '{"data":{"enqueuePullRequest":{"mergeQueueEntry":{"state":"QUEUED"}}}}'; exit 0;; *) echo "head mismatch" >&2; exit 1;; esac;; esac
    # 队列读数: "<qon> <state> <inq> <sha>"; qseq 每行用一次, 用完沿用最后一行; 无 qseq 则无队列
    q=$(cat "$S/queue" 2>/dev/null || echo false)
    # qerr: 前 N 次读模拟 GraphQL 出错 (jq 在 data=null 时打出 "false null null ")
    if [ -f "$S/qerr" ] && [ "$(cat "$S/qerr")" -gt 0 ]; then echo $(( $(cat "$S/qerr") - 1 )) > "$S/qerr"; echo "false null null "; exit 0; fi
    if [ -f "$S/qseq" ]; then
      n=$(( $(cat "$S/qn" 2>/dev/null || echo 0) + 1 )); echo $n > "$S/qn"
      l=$(sed -n "${n}p" "$S/qseq"); [ -z "$l" ] && l=$(tail -1 "$S/qseq"); echo "$q $l"
    else echo "$q $(cat "$S/state") false "; fi;;
  "pr merge")
    [ "$(cat "$S/queue" 2>/dev/null)" = true ] && case "$*" in *--squash*) echo "queue sets strategy" >&2; exit 1;; *) echo "queued"; exit 0;; esac
    case "$*" in *"--match-head-commit $h"*) echo MERGED > "$S/state"; echo "merged";; *) echo "head mismatch" >&2; exit 1;; esac;;
  *)
    if [ "$1" = api ]; then cat "$S/behind.$h" 2>/dev/null || echo 0; else echo "fake gh: unhandled $*" >&2; exit 1; fi;;
esac
EOF
chmod +x "$FAKE"

pass=0; failn=0
ok() { pass=$((pass+1)); echo "PASS $1"; }
bad() { failn=$((failn+1)); echo "FAIL $1"; }
setup() { S="$T/s$1"; rm -rf "$S"; mkdir -p "$S"; echo OPEN > "$S/state"; echo MERGEABLE > "$S/mergeable"; echo h1 > "$S/head"; : > "$S/calls"; }
G=$'\t'
run() { FAKE_GH_STATE="$S" PR_WATCH_GH="$FAKE" PR_WATCH_INTERVAL=0 bash "$SCRIPT" 99 --timeout-sec 90 "$@" > "$S/out" 2>&1; echo $? > "$S/rc"; }
rc() { cat "$S/rc"; }
called() { grep -q -- "$1" "$S/calls"; }

# S1 全绿且不落后 -> 合并, 用当前头
setup 1; printf "pass${G}build\nskipping${G}e2e\n" > "$S/checks.h1.1"; run
[ "$(rc)" = 0 ] && grep -q 'RESULT=MERGED.*sha=sha-h1' "$S/out" && called "pr merge 99 --squash --match-head-commit h1" && ok S1-merge || bad S1-merge

# S2 有失败 -> RED, 不合并
setup 2; printf "pass${G}build\nfail${G}script-tests\n" > "$S/checks.h1.1"; run
[ "$(rc)" = 1 ] && grep -q 'RESULT=RED.*failed=script-tests' "$S/out" && ! called "pr merge" && ok S2-red || bad S2-red

# S3 绿但落后 -> update-branch, 等新头, 新头绿了合并新头
setup 3; printf "pass${G}build\n" > "$S/checks.h1.1"; echo 2 > "$S/behind.h1"; echo h2 > "$S/next_head"
printf "pending${G}build\n" > "$S/checks.h2.1"; printf "pass${G}build\n" > "$S/checks.h2.2"; run
[ "$(rc)" = 0 ] && called "pr update-branch 99" && called "match-head-commit h2" && ! called "match-head-commit h1" && grep -q 'updates=1' "$S/out" && ok S3-update-then-merge || bad S3-update-then-merge

# S4 先 pending 后绿 -> 合并 (pending 不算绿)
setup 4; printf "pending${G}build\n" > "$S/checks.h1.1"; printf "pass${G}build\n" > "$S/checks.h1.3"; run
[ "$(rc)" = 0 ] && [ "$(cat "$S/cc.h1")" -ge 3 ] && ok S4-pending-then-green || bad S4-pending-then-green

# S5 一直没有检查 -> 超时 rc=2, 不合并 (空不算绿)
setup 5; run --timeout-sec 2
[ "$(rc)" = 2 ] && grep -q 'RESULT=UNMEASURED.*timeout' "$S/out" && ! called "pr merge" && ok S5-empty-is-not-green || bad S5-empty-is-not-green

# S6 冲突 -> rc=3
setup 6; echo CONFLICTING > "$S/mergeable"; run --timeout-sec 3
[ "$(rc)" = 3 ] && grep -q 'RESULT=CONFLICT' "$S/out" && ! called "pr merge" && ok S6-conflict || bad S6-conflict

# S7 网络错误两次后恢复 -> 照常合并 (网络错误不当结论)
setup 7; echo 2 > "$S/neterr"; printf "pass${G}build\n" > "$S/checks.h1.1"; run
[ "$(rc)" = 0 ] && grep -q 'retry #2' "$S/out" && ok S7-neterr-retry || bad S7-neterr-retry

# S8 --no-merge -> GREEN, 不合并
setup 8; printf "pass${G}build\n" > "$S/checks.h1.1"; run --no-merge
[ "$(rc)" = 0 ] && grep -q 'RESULT=GREEN' "$S/out" && ! called "pr merge" && ok S8-no-merge || bad S8-no-merge

# S9 只有 skipping 没有 pass -> 不算绿, 超时
setup 9; printf "skipping${G}e2e\n" > "$S/checks.h1.1"; run --timeout-sec 2
[ "$(rc)" = 2 ] && ! called "pr merge" && ok S9-all-skipped-not-green || bad S9-all-skipped-not-green

# S10 update 次数用完 -> rc=2 不合并
setup 10; printf "pass${G}build\n" > "$S/checks.h1.1"; echo 1 > "$S/behind.h1"; echo h1 > "$S/next_head"; run --max-updates 0
[ "$(rc)" = 2 ] && grep -q 'max-updates' "$S/out" && ! called "pr merge" && ok S10-max-updates || bad S10-max-updates

# S11 已合并 -> rc=0 直接退出
setup 11; echo MERGED > "$S/state"; run
[ "$(rc)" = 0 ] && grep -q 'already-merged' "$S/out" && ok S11-already || bad S11-already

# S12 队列开: 绿了即使落后也不 update-branch, 入队 (不带 --squash), 在队里两轮后 MERGED
setup 12; echo true > "$S/queue"; printf "pass${G}build\n" > "$S/checks.h1.1"; echo 3 > "$S/behind.h1"; echo h9 > "$S/next_head"
printf "OPEN false \nOPEN true \nOPEN true \nMERGED false shaQ\n" > "$S/qseq"; run
[ "$(rc)" = 0 ] && grep -q 'RESULT=MERGED.*sha=shaQ.*queue=on' "$S/out" && ! called "pr update-branch" && called "enqueuePullRequest" && called "h=h1" && ! called "pr merge" && ! called "squash" && ok S12-queue-merge || bad S12-queue-merge

# S13 队列开: 入队后被踢出 (队列里检查没过) -> RED
setup 13; echo true > "$S/queue"; printf "pass${G}build\n" > "$S/checks.h1.1"
printf "OPEN false \nOPEN true \nOPEN false \n" > "$S/qseq"; run
[ "$(rc)" = 1 ] && [ "$(cat "$S/qn")" = 4 ] && grep -q 'RESULT=RED.*dequeued' "$S/out" && ok S13-queue-dequeued-red || bad S13-queue-dequeued-red

# S14 队列开: 入队命令后一直不在队列 -> UNMEASURED, 不当成功
setup 14; echo true > "$S/queue"; printf "pass${G}build\n" > "$S/checks.h1.1"; printf "OPEN false \n" > "$S/qseq"; run
[ "$(rc)" = 2 ] && grep -q 'RESULT=UNMEASURED.*not-enqueued' "$S/out" && ok S14-queue-not-enqueued || bad S14-queue-not-enqueued

# S15 队列开 + --no-merge -> GREEN, 不入队
setup 15; echo true > "$S/queue"; printf "pass${G}build\n" > "$S/checks.h1.1"; run --no-merge
[ "$(rc)" = 0 ] && grep -q 'RESULT=GREEN.*queue=on' "$S/out" && ! called "pr merge" && ok S15-queue-no-merge || bad S15-queue-no-merge

# S16 队列开但有检查失败 -> RED, 不入队 (红不进队)
setup 16; echo true > "$S/queue"; printf "fail${G}build\n" > "$S/checks.h1.1"; run
[ "$(rc)" = 1 ] && ! called "pr merge" && ok S16-queue-red-not-enqueued || bad S16-queue-red-not-enqueued

# S17 开了队列但有一次队列读数出错 -> 不能当无队列走 --squash, 重读后入队
setup 17; echo true > "$S/queue"; echo 1 > "$S/qerr"; printf "pass${G}build
" > "$S/checks.h1.1"; printf "OPEN false 
OPEN true 
MERGED false shaQ
" > "$S/qseq"; run
[ "$(rc)" = 0 ] && grep -q 'RESULT=MERGED.*queue=on' "$S/out" && ! called "squash" && ok S17-queue-read-error-not-no-queue || bad S17-queue-read-error-not-no-queue

# S18 出队时状态短暂为 OPEN, 下一轮确认 MERGED。
setup 18; echo true > "$S/queue"; printf "pass${G}build\n" > "$S/checks.h1.1"
printf "OPEN false \nOPEN true \nOPEN false \nMERGED false shaQ\n" > "$S/qseq"; run
if [ "$(rc)" = 0 ] && grep -q 'RESULT=MERGED.*sha=shaQ.*queue=on' "$S/out"; then
  ok S18-queue-dequeue-then-merged
else
  cat "$S/out"
  bad S18-queue-dequeue-then-merged
fi

# S19 出队后确认 PR 被关闭。
setup 19; echo true > "$S/queue"; printf "pass${G}build\n" > "$S/checks.h1.1"
printf "OPEN false \nOPEN true \nOPEN false \nCLOSED false\n" > "$S/qseq"; run
[ "$(rc)" = 4 ] && grep -q 'RESULT=CLOSED.*queue=on' "$S/out" && ok S19-queue-dequeue-then-closed || bad S19-queue-dequeue-then-closed

rm -rf "$T"
echo "TOTAL=$((pass+failn)) PASS=$pass FAIL=$failn"
[ "$failn" = 0 ]
