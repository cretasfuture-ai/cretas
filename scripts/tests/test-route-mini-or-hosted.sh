#!/usr/bin/env bash
# GUARDS=scripts/ci/route-mini-or-hosted.sh,scripts/ci/canada-mini-gate/refill.sh,scripts/ci/canada-mini-gate/queue-snapshot.sh,scripts/ci/canada-mini-gate/setup.sh,scripts/ci/canada-mini-gate/rollout.md,.github/workflows/java-gate-pr.yml,.github/workflows/rn-gate.yml,.github/workflows/web-admin-gate.yml
# 场景测试: mini 共用槽池路由 (mini 优先, 满了溢出托管)。用假 gh 驱动, 不碰网络。
#   少于阈值 -> mini / 达到阈值 -> hosted / API 出错 -> hosted / 返回结构不对 -> hosted /
#   fork、ci-hosted、来源未确认的合并组、过期心跳、零容量和完整重跑都走托管。
#   阈值按时段 (day 2 / full 3) / 排队数把所有 mini 标签 (公共 + 旧 pg/fast)、所有上 mini 的 workflow 一起算。
# 计数口径: 只数 $MINI_WORKFLOWS 的 run 里 status=queued 且带任一 mini 标签的作业, 排除本 run。
# 2026-11-19 21:5x Steve「不跑5个了……重点是这4个槽不要只给pg用」+「其他检查也上 mini」: 槽 1-4 共用,
# refill.sh 和路由跑同一份多页 fixture，另核对容量、空闲名次、清理计划和缓存隔离。
# 末尾自带变异对照: 比较方向改反 / 只数公共标签 / 删掉槽数上限 / 删掉心跳年龄等, 都必须变红 (变异没生效就判红, 硬约束 C)。
set -uo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRIPT="${ROUTE_SCRIPT:-$ROOT_DIR/scripts/ci/route-mini-or-hosted.sh}"
command -v jq >/dev/null 2>&1 || { echo "SKIPPED:jq 不在 PATH"; exit 77; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FIX="$WORK/fix"
mkdir -p "$FIX"
# Keep the same runtime dependencies next to mutated routers or refill scripts.
GATE="$ROOT_DIR/scripts/ci/canada-mini-gate"
mutant_dir() {
  mkdir -p "$WORK/mut/canada-mini-gate"
  cp "$GATE/refill.sh" "$GATE/queue-snapshot.sh" "$WORK/mut/canada-mini-gate/"
}

# ── 假 gh: `gh api <path>` -> 按路径取 fixture; FAKE_GH_FAIL=1 时一律失败; 每次调用记一行。
cat > "$WORK/gh" <<'SH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_GH_LOG"
[ "${FAKE_GH_FAIL:-0}" = 1 ] && exit 1
[ "$1" = api ] || exit 2
shift
paginate=0
if [ "$1" = --paginate ]; then paginate=1; shift; fi
path="$1"; shift
filter=
if [ "${1:-}" = -q ]; then filter="$2"; fi
case "$path" in
  graphql) cat "$FAKE_FIX/group.json"; exit $? ;;
  */actions/runs\?status=queued*) file=runs-queued ;;
  */actions/runs\?status=in_progress*) file=runs-in_progress ;;
  */actions/runs/*/jobs*) id=${path#*/actions/runs/}; file=jobs-${id%%/*} ;;
  *) echo "unexpected path $path" >&2; exit 3 ;;
esac
page() {
  if [ -n "$filter" ]; then jq -r "$filter" "$1"; else cat "$1"; fi
}
page "$FAKE_FIX/$file.json" || exit 1
if [ "$paginate" = 1 ] && [ -f "$FAKE_FIX/$file-2.json" ]; then
  [ "${FAKE_GH_PAGE_FAIL:-}" != "$file" ] || exit 1
  [ "${FAKE_GH_TRUNCATED:-}" != "$file" ] || exit 0
  page "$FAKE_FIX/$file-2.json" || exit 1
fi
SH
chmod +x "$WORK/gh"

write_fixtures() {
  # 本 run 999 也在 in_progress 里, 它的 5 个排队作业必须被排除。
  # 103 是 ops-script-tests.yml (没有 mini 作业的 workflow): 默认清单不取它, 也不去拉它的作业。
  printf '%s' '{"workflow_runs":[{"id":101,"path":".github/workflows/java-gate-pr.yml"},{"id":103,"path":".github/workflows/ops-script-tests.yml"}]}' > "$FIX/runs-queued.json"
  printf '%s' '{"workflow_runs":[{"id":102,"path":".github/workflows/web-admin-gate.yml"},{"id":999,"path":".github/workflows/rn-gate.yml"}]}' > "$FIX/runs-in_progress.json"
  # 101: 1 个排队的旧 PG 标签作业 (计), 1 个排队的托管作业 (不计), 1 个在跑的 mini 作业 (不计),
  #      1 个排队的旧 fast 标签作业 (计: 共用槽池, 旧标签也是 mini 的活)
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["ubuntu-latest"]},{"status":"in_progress","labels":["cretas-mini"]},{"status":"queued","labels":["cretas-mini-fast"]}]}' > "$FIX/jobs-101.json"
  # 102: 1 个排队的公共标签作业 (计), 1 个还没定 labels 的作业 (不计)
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini"]},{"status":"queued"}]}' > "$FIX/jobs-102.json"
  # 103: 2 个排队的 mini 作业 —— 只有把 ops-script-tests.yml 放进清单时才计
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini"]},{"status":"queued","labels":["cretas-mini"]}]}' > "$FIX/jobs-103.json"
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini"]},{"status":"queued","labels":["cretas-mini"]},{"status":"queued","labels":["cretas-mini"]},{"status":"queued","labels":["cretas-mini"]},{"status":"queued","labels":["cretas-mini-pg"]}]}' > "$FIX/jobs-999.json"
  local file
  for file in "$FIX"/runs-*.json; do
    jq '.total_count = (.workflow_runs | length)' "$file" > "$file.tmp" && mv "$file.tmp" "$file" || exit 2
  done
  for file in "$FIX"/jobs-*.json; do
    jq '.jobs |= (to_entries | map(.value + {id: (.key + 1)})) | .total_count = (.jobs | length)' "$file" > "$file.tmp" && mv "$file.tmp" "$file" || exit 2
  done
}

write_paged_fixtures() {
  # Page 1 has exactly 100 irrelevant runs/jobs, page 2 has the only work.
  jq -n '{total_count:101, workflow_runs:[range(200;300) | {id:.,path:".github/workflows/ops-script-tests.yml"}]}' > "$FIX/runs-queued.json" || exit 2
  jq -n '{total_count:101, workflow_runs:[{id:102,path:".github/workflows/web-admin-gate.yml"}]}' > "$FIX/runs-queued-2.json" || exit 2
  printf '%s\n' '{"total_count":0,"workflow_runs":[]}' > "$FIX/runs-in_progress.json"
  jq -n '{total_count:102, jobs:[range(300;400) | {id:.,status:"queued",labels:["ubuntu-latest"]}]}' > "$FIX/jobs-102.json" || exit 2
  jq -n '{total_count:102,jobs:[{id:400,status:"queued",labels:["cretas-mini"]},{id:401,status:"queued",labels:["cretas-mini-fast"]}]}' > "$FIX/jobs-102-2.json" || exit 2
}

FAILS=0
PASSES=0
# run_case <名字> <期望 ROUTE 行> <期望 GITHUB_OUTPUT 行 key=value> <期望 gh 调用: any|none> [env 赋值...]
# 默认钉住 MINI_WINDOW=day + MINI_QUEUE_THRESHOLD=4 (场景各自覆盖), 结论不随 CI 跑的时间变。
run_case() {
  local name="$1" want_line="$2" want_runner="$3" want_calls="$4"; shift 4
  local out="$WORK/out.$PASSES.$FAILS" ghout="$WORK/ghout" log="$WORK/ghlog"
  : > "$ghout"; : > "$log"
  env GH="$WORK/gh" FAKE_FIX="$FIX" FAKE_GH_LOG="$log" GITHUB_OUTPUT="$ghout" \
      GITHUB_REPOSITORY=cretasfuture-ai/cretas EVENT_NAME=pull_request \
      HEAD_REPO=cretasfuture-ai/cretas PR_LABELS='["java"]' GITHUB_RUN_ID=999 \
      MINI_WINDOW=day MINI_QUEUE_THRESHOLD=4 ROUTE_NOW=1000000 MINI_POOL_V2_ENABLED=1 MINI_POOL_CAPACITY=999990:3:2 "$@" bash "$SCRIPT" > "$out" 2>&1
  local rc=$? ok=1 why=""
  [ "$rc" = 0 ] || { ok=0; why="rc=$rc"; }
  grep -qxF "$want_line" "$out" || { ok=0; why="$why 缺行「$want_line」"; }
  [ "$(grep -c '^ROUTE=' "$out")" = 1 ] || { ok=0; why="$why ROUTE 行不是恰好 1 条"; }
  grep -qxF "$want_runner" "$ghout" || { ok=0; why="$why GITHUB_OUTPUT 没有 $want_runner"; }
  grep -qE '^mini_window=(day|full)$' "$ghout" || { ok=0; why="$why missing mini_window"; }
  grep -qxF route_policy=v2 "$ghout" || { ok=0; why="$why missing route_policy"; }
  if [ -n "${WANT_GROUP:-}" ]; then
    grep -qxF "mini_group_verified=$WANT_GROUP" "$ghout" || { ok=0; why="$why wrong merge group verification output"; }
  fi
  case "$want_calls" in
    none) [ ! -s "$log" ] || { ok=0; why="$why 不该调 gh 却调了"; } ;;
    any)  [ -s "$log" ] || { ok=0; why="$why 该调 gh 却没调"; } ;;
  esac
  if grep -q 'runs/103/jobs' "$log" && [ "${WANT_103:-0}" != 1 ]; then ok=0; why="$why 不在清单里的 run 103 也被拉了作业"; fi
  if [ "$ok" = 1 ]; then
    PASSES=$((PASSES + 1)); echo "  ok - $name"
  else
    FAILS=$((FAILS + 1)); echo "  FAIL - $name:$why"; sed 's/^/      | /' "$out"
  fi
}

run_all() {
  write_fixtures
  run_case "少于阈值 -> mini (排队 3 < 4: 公共 + 旧 pg + 旧 fast 标签都算)" \
    "ROUTE=mini queued=3 threshold=4 reason=below-threshold label=cretas-mini window=day" runner=cretas-mini any
  run_case "达到阈值 -> hosted (排队 3 >= 3)" \
    "ROUTE=hosted queued=3 threshold=3 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD=3
  run_case "超过阈值 -> hosted (排队 3 >= 1)" \
    "ROUTE=hosted queued=3 threshold=1 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD=1
  # 时段阈值 (不给 MINI_QUEUE_THRESHOLD): day 默认 2、full 默认 3; 不给 MINI_WINDOW 时调 refill.sh --window。
  run_case "白天阈值 2: 排队 3 -> hosted" \
    "ROUTE=hosted queued=3 threshold=2 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD= MINI_WINDOW=day
  run_case "夜间阈值 3 (时段取自 refill.sh, 23 点): 排队 3 -> hosted" \
    "ROUTE=hosted queued=3 threshold=3 reason=mini-full label=cretas-mini window=full" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD= MINI_WINDOW= GH_GATE_HOUR=23 GH_GATE_DAY_FULL_FILE="$WORK/no-such-file"
  run_case "白天时段取自 refill.sh (12 点) -> 阈值 2" \
    "ROUTE=hosted queued=3 threshold=2 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD= MINI_WINDOW= GH_GATE_HOUR=12 GH_GATE_DAY_FULL_FILE="$WORK/no-such-file"
  run_case "只数公共标签时 -> 排队 1 (旧标签口径确实有作用)" \
    "ROUTE=mini queued=1 threshold=4 reason=below-threshold label=cretas-mini window=day" runner=cretas-mini any MINI_COUNT_LABELS=cretas-mini
  WANT_103=1 run_case "清单加上 ops-script-tests.yml -> 排队 5 (workflow 过滤确实有作用)" \
    "ROUTE=hosted queued=5 threshold=4 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest any "MINI_WORKFLOWS=java-gate-pr.yml rn-gate.yml web-admin-gate.yml ops-script-tests.yml"
  run_case "API 出错 -> hosted" \
    "ROUTE=hosted queued=na threshold=4 reason=api-error label=cretas-mini window=day" runner=ubuntu-latest any FAKE_GH_FAIL=1
  run_case "fork PR -> hosted 且不调 gh" \
    "ROUTE=hosted queued=na threshold=4 reason=fork label=cretas-mini window=day" runner=ubuntu-latest none HEAD_REPO=stranger/cretas
  run_case "ci-hosted 标签 -> hosted 且不调 gh" \
    "ROUTE=hosted queued=na threshold=4 reason=ci-hosted-label label=cretas-mini window=day" runner=ubuntu-latest none PR_LABELS='["java","ci-hosted"]'
  local group_git="$WORK/group-git" base first head
  mkdir -p "$group_git"
  git -C "$group_git" init -q || exit 2
  git -C "$group_git" -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m base || exit 2
  base=$(git -C "$group_git" rev-parse HEAD) || exit 2
  git -C "$group_git" -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m first || exit 2
  first=$(git -C "$group_git" rev-parse HEAD) || exit 2
  git -C "$group_git" -c user.name=Fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m second || exit 2
  head=$(git -C "$group_git" rev-parse HEAD) || exit 2
  local group_env=(EVENT_NAME=merge_group HEAD_REPO= "GIT_DIR=$group_git/.git" "GROUP_BASE_SHA=$base" "GROUP_HEAD_SHA=$head" GROUP_BASE_REF=refs/heads/main)
  jq -n --arg first "$first" --arg head "$head" '
    def row($pos; $oid; $pr): {position:$pos, headCommit:{oid:$oid}, pullRequest:{number:$pr,
      headRepository:{nameWithOwner:"cretasfuture-ai/cretas"}, labels:{nodes:[],pageInfo:{hasNextPage:false}}}};
    {data:{repository:{mergeQueue:{entries:{nodes:[row(1;$first;1),row(2;$head;2)]}}}}}' > "$FIX/group-original.json" || exit 2
  cp "$FIX/group-original.json" "$FIX/group.json"
  WANT_GROUP=true run_case "合并组 2 个 PR 均确认同仓 -> mini" \
    "ROUTE=mini queued=3 threshold=4 reason=below-threshold label=cretas-mini window=day" runner=cretas-mini any "${group_env[@]}"
  local why expr
  for why in fork missing-repo missing-member wrong-commit; do
    case "$why" in
      fork) expr='.[0].pullRequest.headRepository.nameWithOwner = "fork/cretas"' ;;
      missing-repo) expr='.[0].pullRequest.headRepository = null' ;;
      missing-member) expr='del(.[0])' ;;
      wrong-commit) expr='.[0].headCommit.oid = "other"' ;;
    esac
    jq ".data.repository.mergeQueue.entries.nodes |= ($expr)" "$FIX/group-original.json" > "$FIX/group.json" || exit 2
    WANT_GROUP=false run_case "合并组前序 $why -> 整组托管" \
      "ROUTE=hosted queued=na threshold=4 reason=group-unverified label=cretas-mini window=day" runner=ubuntu-latest any "${group_env[@]}"
  done
  WANT_GROUP=false run_case "合并组队列查询失败 -> 整组托管" \
    "ROUTE=hosted queued=na threshold=4 reason=group-unverified label=cretas-mini window=day" runner=ubuntu-latest any "${group_env[@]}" FAKE_GH_FAIL=1
  jq '.data.repository.mergeQueue.entries.nodes[0].pullRequest.labels.nodes = [{name:"ci-hosted"}]' "$FIX/group-original.json" > "$FIX/group.json" || exit 2
  WANT_GROUP=false run_case "合并组前序 PR 有 ci-hosted -> 整组托管" \
    "ROUTE=hosted queued=na threshold=4 reason=ci-hosted-label label=cretas-mini window=day" runner=ubuntu-latest any "${group_env[@]}"
  for event in pull_request merge_group; do
    run_case "$event 完整重跑 -> 托管" \
      "ROUTE=hosted queued=na threshold=4 reason=retry-hosted label=cretas-mini window=day" runner=ubuntu-latest none EVENT_NAME="$event" GITHUB_RUN_ATTEMPT=2
  done
  run_case "新标签未开放 -> 托管" \
    "ROUTE=hosted queued=na threshold=4 reason=rollout-disabled label=cretas-mini window=day" runner=ubuntu-latest none MINI_POOL_V2_ENABLED=
  run_case "阈值不是整数 -> hosted" \
    "ROUTE=hosted queued=na threshold=abc reason=bad-threshold label=cretas-mini window=day" runner=ubuntu-latest none MINI_QUEUE_THRESHOLD=abc
  # 结构不对 (令牌失效时 API 回 {"message":...}) 不许当成 0 排队。
  printf '%s' '{"message":"Bad credentials"}' > "$FIX/runs-queued.json"
  run_case "返回结构不对 -> hosted (不当成 0)" \
    "ROUTE=hosted queued=na threshold=4 reason=api-error label=cretas-mini window=day" runner=ubuntu-latest any
  printf '%s' '{"total_count":0,"workflow_runs":[]}' > "$FIX/runs-queued.json"
  printf '%s' '{"total_count":0,"workflow_runs":[]}' > "$FIX/runs-in_progress.json"
  run_case "没有运行 -> queued=0 -> mini" \
    "ROUTE=mini queued=0 threshold=4 reason=below-threshold label=cretas-mini window=day" runner=cretas-mini any
  run_case "同一心跳在 run 启动时仍新鲜 -> mini" \
    "ROUTE=mini queued=0 threshold=4 reason=below-threshold label=cretas-mini window=day" runner=cretas-mini any ROUTE_NOW=999830 MINI_POOL_CAPACITY=999800:3:2
  run_case "派发时心跳过期 (即使 run 启动时正常) -> 托管" \
    "ROUTE=hosted queued=na threshold=4 reason=mini-offline label=cretas-mini window=day" runner=ubuntu-latest none MINI_POOL_CAPACITY=999800:3:2
  run_case "心跳缺失 -> hosted mini-offline" \
    "ROUTE=hosted queued=na threshold=4 reason=mini-offline label=cretas-mini window=day" runner=ubuntu-latest none MINI_POOL_CAPACITY=
  run_case "旧格式心跳 -> hosted mini-offline" \
    "ROUTE=hosted queued=na threshold=4 reason=mini-offline label=cretas-mini window=day" runner=ubuntu-latest none MINI_POOL_CAPACITY=999990
  run_case "心跳正好 180s -> mini" \
    "ROUTE=mini queued=0 threshold=4 reason=below-threshold label=cretas-mini window=day" runner=cretas-mini any MINI_POOL_CAPACITY=999820:3:2
  run_case "发布锁或暂停令空闲容量为零 -> 托管" \
    "ROUTE=hosted queued=na threshold=4 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest none MINI_POOL_CAPACITY=999990:0:0
  jq -n '{total_count:1,workflow_runs:[{id:101,path:".github/workflows/java-gate-pr.yml"}]}' > "$FIX/runs-in_progress.json"
  jq -n '{total_count:3,jobs:[range(1;4) | {id:.,status:"in_progress",labels:["cretas-mini"]}]}' > "$FIX/jobs-101.json"
  run_case "实时槽满且排队为零 -> 托管" \
    "ROUTE=hosted queued=0 threshold=4 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest any
  write_paged_fixtures
  run_case "目标 run 和 mini job 都只在第 2 页 -> 阈值命中" \
    "ROUTE=hosted queued=2 threshold=2 reason=mini-full label=cretas-mini window=day" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD=2
  for failure in runs-queued jobs-102; do
    run_case "第 2 页失败 ($failure) -> 未量到" \
      "ROUTE=hosted queued=na threshold=4 reason=api-error label=cretas-mini window=day" runner=ubuntu-latest any FAKE_GH_PAGE_FAIL="$failure"
  done
  run_case "分页少于 API 总数 -> 未量到" \
    "ROUTE=hosted queued=na threshold=4 reason=api-error label=cretas-mini window=day" runner=ubuntu-latest any FAKE_GH_TRUNCATED=runs-queued
  rm -f "$FIX/runs-queued-2.json" "$FIX/jobs-102-2.json"
}

echo "== 被测: $SCRIPT"
run_all
echo "READING: pass=$PASSES fail=$FAILS"
REAL_FAILS=$FAILS

# 被测脚本是变异体时只跑场景 (变异对照递归调本测试), 下面的核对只对真脚本做。
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  REFILL="$ROOT_DIR/scripts/ci/canada-mini-gate/refill.sh"
  WFDIR="$ROOT_DIR/.github/workflows"
  WFY="$WFDIR/java-gate-pr.yml"
  # ── 共用槽池: 每个槽注册同一组标签, 且 = 路由脚本排队口径 = refill 排队口径 (同一个东西多份, 形态 D)。
  want_labels="cretas-mini cretas-mini-pg cretas-mini-fast"
  got=""
  for i in 1 2 3 4; do got="$got $i=[$(bash "$REFILL" --labels-of "$i" 2>&1)]"; done
  echo "READING: refill slot labels:$got"
  route_labels=$(sed -nE 's/^COUNT_LABELS="\$\{MINI_COUNT_LABELS:-(.*)\}"$/\1/p' "$SCRIPT")
  route_wfs=$(sed -nE 's/^MINI_WORKFLOWS="\$\{MINI_WORKFLOWS:-(.*)\}"$/\1/p' "$SCRIPT")
  refill_wfs=$(bash "$REFILL" --workflows)
  echo "READING: route count labels=[$route_labels] refill queue labels=[$(bash "$REFILL" --queue-labels)] route workflows=[$route_wfs] refill workflows=[$refill_wfs]"
  if [ "$got" != " 1=[$want_labels] 2=[$want_labels] 3=[$want_labels] 4=[$want_labels]" ] \
     || [ "$route_labels" != "$want_labels" ] || [ "$(bash "$REFILL" --queue-labels)" != "$want_labels" ]; then
    echo "FAIL: 槽 1-4 都应注册「$want_labels」, 且路由脚本 / refill 的排队标签与之一致"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - 4 个槽同一组标签, 路由与 refill 排队口径一致"
  fi
  # workflow 清单: 路由 == refill == 实际 runs-on 写着 'cretas-mini' 的 workflow 集合 (新搬一个上 mini 漏登记就红)。
  sorted() { printf '%s\n' $1 | sort | tr '\n' ' ' | sed 's/ $//'; }
  # 先落变量再整理, 不走管道 (闸 test-pipeline-tail-swallows-rc.sh); grep 一个都没有 (rc=1) 时为空, 下面判红。
  wf_hits=$(cd "$WFDIR" && grep -l "&& 'cretas-mini' || 'ubuntu-latest' }}" ./*.yml) || wf_hits=""
  actual_wfs=$(sorted "${wf_hits//.\//}")
  echo "READING: workflows with mini runs-on=[$actual_wfs]"
  if [ -z "$actual_wfs" ] || [ "$(sorted "$route_wfs")" != "$actual_wfs" ] || [ "$(sorted "$refill_wfs")" != "$actual_wfs" ]; then
    echo "FAIL: 路由脚本 MINI_WORKFLOWS / refill MINI_WORKFLOWS 必须等于 runs-on 里有 cretas-mini 的 workflow 集合"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - mini workflow 清单三处一致"
  fi
  # 旧标签不许再出现在任何 runs-on 里 (只在 refill 注册 / 排队口径里留作过渡)。
  if grep -nE "runs-on:.*cretas-mini-(pg|fast)" "$WFDIR"/*.yml; then
    echo "FAIL: workflow 的 runs-on 里还有旧标签 cretas-mini-pg / cretas-mini-fast"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - runs-on 里没有旧标签"; fi

  refill_count() {
    env GH="$WORK/gh" FAKE_FIX="$FIX" FAKE_GH_LOG="$WORK/refill-gh.log" \
      GITHUB_REPOSITORY=cretasfuture-ai/cretas "$@"
  }
  write_fixtures
  got=$(refill_count bash "$REFILL" --queue-snapshot)
  if [ "$got" = "8 1" ]; then echo "  ok - refill counts all three labels ($got)"; else
    echo "FAIL: refill expected 8 queued, 1 busy, got [$got]"; REAL_FAILS=$((REAL_FAILS + 1)); fi
  write_paged_fixtures
  got=$(refill_count bash "$REFILL" --queue-snapshot)
  incomplete=$(refill_count env FAKE_GH_PAGE_FAIL=jobs-102 bash "$REFILL" --queue-snapshot)
  incomplete_rc=$?
  if [ "$got" = "2 0" ] && [ "$incomplete_rc" = 1 ] && [ -z "$incomplete" ]; then
    echo "  ok - refill counted 2 from page 2; failed job page yielded rc=1 and no partial value"
  else
    echo "FAIL: refill pagination got=[$got] incomplete=[$incomplete] rc=$incomplete_rc"; REAL_FAILS=$((REAL_FAILS + 1))
  fi
  mutant_dir
  sed 's/api --paginate/api/' "$GATE/queue-snapshot.sh" > "$WORK/mut/canada-mini-gate/queue-snapshot.sh"
  missing_page=$(refill_count bash "$WORK/mut/canada-mini-gate/refill.sh" --queue-snapshot)
  missing_page_rc=$?
  if [ "$missing_page_rc" = 1 ] && [ -z "$missing_page" ]; then
    echo "  ok - pagination mutation (old single-page API) is red: refill outputs no snapshot rc=1"
  else
    echo "FAIL: pagination mutation returned [$missing_page] rc=$missing_page_rc"; REAL_FAILS=$((REAL_FAILS + 1))
  fi
  rm -f "$FIX/runs-queued-2.json" "$FIX/jobs-102-2.json"

  mkdir -p "$WORK/bin" "$WORK/agents"
  cat > "$WORK/bin/launchctl" <<'SH'
#!/usr/bin/env bash
[ "$1" = list ] || exit 2
printf '%s\n' "100 0 com.cretas.gh-gate-1" "101 0 com.cretas.gh-gate-2" "102 0 com.cretas.gh-gate-3" \
  "- 0 com.cretas.gh-gate-4" "103 0 com.cretas.gh-gate-5" "104 0 com.cretas.gh-gate-12"
SH
  cat > "$WORK/bin/pgrep" <<'SH'
#!/usr/bin/env bash
[[ "$*" == *"slot-${TEST_BUSY_SLOT:-none}/"* ]]
SH
  chmod +x "$WORK/bin/launchctl" "$WORK/bin/pgrep"
  touch "$WORK/agents/com.cretas.gh-gate-4.plist" "$WORK/agents/com.cretas.gh-gate-14.plist"
  setup_plan() {
    PATH="$WORK/bin:$PATH" GH_GATE_AGENTS_DIR="$WORK/agents" bash "$1" --plan-cleanup 2
  }
  planned=$(setup_plan "$GATE/setup.sh")
  expected=$(printf '%s\n' com.cretas.gh-gate-{3,4,5,12,14})
  sed 's/if (slot + 0 > limit)/if (slot + 0 > limit \&\& slot + 0 >= 5)/' "$GATE/setup.sh" > "$WORK/setup-mutant.sh"
  old_plan=$(setup_plan "$WORK/setup-mutant.sh")
  old_expected=$(printf '%s\n' com.cretas.gh-gate-{5,12,14})
  if [ "$planned" = "$expected" ] && [ "$old_plan" = "$old_expected" ]; then
    echo "  ok - setup 2 cleans 3,4,5,12,14; old lower bound mutation misses 3,4"
  else
    echo "FAIL: setup cleanup plan=[$planned] old=[$old_plan]"; REAL_FAILS=$((REAL_FAILS + 1))
  fi
  busy_plan=$(TEST_BUSY_SLOT=3 setup_plan "$GATE/setup.sh" 2>&1)
  busy_rc=$?
  if [ "$busy_rc" = 1 ] && [[ "$busy_plan" == *'槽 3 仍有 Runner.Listener 或 Runner.Worker'* ]]; then
    echo "  ok - setup 2 aborts before cleanup when slot 3 is still active"
  else
    echo "FAIL: setup busy cleanup rc=$busy_rc output=[$busy_plan]"; REAL_FAILS=$((REAL_FAILS + 1))
  fi
  capacity() {
    PATH="$WORK/bin:$PATH" GH_GATE_HOUR=23 GH_GATE_DAY_FULL_FILE="$WORK/no-such-file" \
      GH_GATE_ENABLED_FILE="$WORK/no-such-file" GH_GATE_RELEASE_LOCK="$WORK/no-such-lock" \
      GH_GATE_PRESSURE_FILE="$1" TEST_BUSY_SLOT=2 bash "$REFILL" --capacity
  }
  printf '%s\n' Heavy > "$WORK/heavy"
  if [ "$(capacity "$WORK/no-such-file")" = 3:2 ] && [ "$(capacity "$WORK/heavy")" = 0:0 ]; then
    echo "  ok - loaded+enabled capacity 3:2; thermal pause 0:0"
  else
    echo "FAIL: mini capacity normal/paused values unexpected"; REAL_FAILS=$((REAL_FAILS + 1))
  fi
  # Missing, disabled and busy lower-numbered slots do not count towards rank.
  printf '2\n4\n' > "$WORK/enabled-slots"
  rank=$(PATH="$WORK/bin:$PATH" GH_GATE_ENABLED_FILE="$WORK/enabled-slots" bash "$REFILL" --idle-rank-of 3)
  busy_rank=$(PATH="$WORK/bin:$PATH" GH_GATE_ENABLED_FILE="$WORK/enabled-slots" TEST_BUSY_SLOT=2 bash "$REFILL" --idle-rank-of 3)
  if [ "$rank" = 2 ] && [ "$busy_rank" = 1 ]; then
    echo "  ok - only loaded, enabled and idle slots count towards rank"
  else
    echo "FAIL: slot 3 idle/busy ranks: $rank / $busy_rank"; REAL_FAILS=$((REAL_FAILS + 1))
  fi
  # 时段 (2026-11-19 Steve「22:00 后跑满, 8 点后 1 pg 1 fast」, 北京时间): [22,8) full, [8,22) day; 边界 7/8/21/22。
  win_all() { local h out=""; for h in 0 7 8 12 21 22 23 07 08; do out="$out $h=$(GH_GATE_DAY_FULL_FILE="$WORK/no-such-file" bash "$1" --window "$h")"; done; echo "$out"; }
  want_win=" 0=full 7=full 8=day 12=day 21=day 22=full 23=full 07=full 08=day"
  got=$(win_all "$REFILL")
  echo "READING: refill window:$got"
  if [ "$got" != "$want_win" ]; then echo "FAIL: 时段应为$want_win"; REAL_FAILS=$((REAL_FAILS + 1)); else echo "  ok - 时段边界正确"; fi
  # 启用 (槽 1-5, 5 用来证明上限): 白天 1-3, 夜间 1-4, 覆盖文件优先但槽 5 写进去也不启用。
  printf '2\n5\n' > "$WORK/enabled-2-5"
  en_all() { local i out=""; for i in 1 2 3 4 5; do out="$out$(GH_GATE_HOUR="$2" GH_GATE_ENABLED_FILE="$3" GH_GATE_DAY_FULL_FILE="$WORK/no-such-file" bash "$1" --enabled-of "$i" | cut -c1)"; done; echo "$out"; }
  want_en="day=yyynn night=yyyyn override=nynnn"
  got="day=$(en_all "$REFILL" 10 "$WORK/no-such-file") night=$(en_all "$REFILL" 23 "$WORK/no-such-file") override=$(en_all "$REFILL" 23 "$WORK/enabled-2-5")"
  echo "READING: refill enabled (y/n per slot 1-5): $got"
  if [ "$got" != "$want_en" ]; then
    echo "FAIL: 启用应为 $want_en"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - 按时段启用 (白天 3 槽 / 夜间 4 槽) + 覆盖文件优先 + 槽 5 永不启用"; fi
  # 变异对照: 删掉槽数上限, 夜间 / 覆盖文件里的槽 5 必须被查出启用。
  CMUT="$WORK/refill-cap-mutant.sh"
  grep -v 'MAX-SLOTS-CAP' "$REFILL" > "$CMUT"
  mgot="day=$(en_all "$CMUT" 10 "$WORK/no-such-file") night=$(en_all "$CMUT" 23 "$WORK/no-such-file") override=$(en_all "$CMUT" 23 "$WORK/enabled-2-5")"
  echo "READING: mutant(no max-slots cap) enabled: $mgot"
  if ! grep -q 'MAX-SLOTS-CAP' "$REFILL" || [ "$mgot" = "$want_en" ]; then
    echo "FAIL: 槽数上限变异没生效或没被查出"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - 槽数上限变异对照: 删掉上限后槽 5 被启用"; fi
  # 白天也全开开关 (2026-11-19 Steve「放开白天派片」): workflow 端环境变量 / mini 端文件, 任一成立白天也 full。
  touch "$WORK/day-full"
  got="env=$(GH_GATE_DAY_FULL=1 bash "$REFILL" --window 12) file=$(GH_GATE_DAY_FULL_FILE="$WORK/day-full" bash "$REFILL" --window 12) off=$(GH_GATE_DAY_FULL=0 GH_GATE_DAY_FULL_FILE="$WORK/no-such-file" bash "$REFILL" --window 12)"
  got="$got slots=$(GH_GATE_DAY_FULL_FILE="$WORK/day-full" bash -c 'for i in 1 2 3 4 5; do GH_GATE_HOUR=10 GH_GATE_ENABLED_FILE=/no-such-file bash "$0" --enabled-of $i | cut -c1; done | tr -d "\n"' "$REFILL")"
  echo "READING: day-full switch: $got"
  if [ "$got" != "env=full file=full off=day slots=yyyyn" ]; then
    echo "FAIL: 白天全开开关应为 env=full file=full off=day slots=yyyyn"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - 白天全开开关 (变量/文件) 生效, 关掉回到按时段"; fi
  # 变异对照: 去掉「< 8 点也算 full」, 凌晨必须被查出变成 day。
  WMUT="$WORK/refill-window-mutant.sh"
  sed 's/ || \[ "\$h" -lt "\$FULL_UNTIL" \]//' "$REFILL" > "$WMUT"
  mgot=$(win_all "$WMUT")
  echo "READING: mutant(no early-morning full) window:$mgot"
  if [ "$mgot" = "$want_win" ]; then echo "FAIL: 时段变异对照: 变异后时段仍正确 (变异没生效或测试没守住)"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - 时段变异对照: 去掉凌晨段后查出"; fi
  # 时段只有 refill.sh 一份 (形态 D): 路由脚本调它, workflow 只读 changes 的 mini_window 输出, 不另写小时判断。
  wf_ok=1
  grep -q 'refill.sh' "$SCRIPT" && grep -q -- '--window' "$SCRIPT" || wf_ok=0
  for wf in $actual_wfs; do
    f="$WFDIR/$wf"
    grep -q 'timeout 45s bash .*scripts/ci/route-mini-or-hosted.sh' "$f" && grep -q 'GH_GATE_DAY_FULL: \${{ vars.MINI_DAY_FULL }}' "$f" \
      && [ "$(grep -c 'MINI_POOL_CAPACITY: \${{ vars.MINI_POOL_CAPACITY }}' "$f")" = 1 ] \
      && grep -q 'runner: \${{ steps.route.outputs.runner }}' "$f" && ! grep -qE 'TZ=|date \+%H|date -u \+%H' "$f" || { wf_ok=0; echo "  ✗ $wf 的 changes 路由没接全"; }
  done
  grep -q "mini_window: \${{ steps.route.outputs.mini_window }}" "$WFY" && grep -q "needs.changes.outputs.mini_window == 'full'" "$WFY" || wf_ok=0
  if [ "$wf_ok" = 1 ]; then
    echo "  ok - 各 mini workflow 的 changes 都调路由脚本 (传心跳 / MINI_DAY_FULL, 输出 runner), 时段取自 refill.sh"
  else
    echo "FAIL: mini workflow 的 changes 路由没接全, 或 workflow 里另写了小时判断"; REAL_FAILS=$((REAL_FAILS + 1))
  fi
  # ── 缓存恢复不许在自建 runner 上跑 (2026-11-19: 各槽共用 ~ghgate/.m2, 恢复原地解压让别的槽读到半截 jar)。
  #    Java Gate 每个 actions/cache/restore 步骤块必须带 self-hosted 排除守卫。
  restore_guard_report() {  # $1 = workflow; 输出「total=<n> guarded=<m>」
    awk '
      /^      - name:/ { if (blk != "") flush(); blk = $0 "\n"; next }
      blk != "" { blk = blk $0 "\n" }
      function flush() {
        if (blk ~ /uses: actions\/cache\/restore@/) {
          total++
          if (blk ~ /if: \$\{\{ runner\.environment != .self-hosted. \}\}/) guarded++
        }
        blk = ""
      }
      END { if (blk != "") flush(); printf "total=%d guarded=%d\n", total, guarded }
    ' "$1"
  }
  rg_got=$(restore_guard_report "$WFY")
  echo "READING: maven cache restore steps: $rg_got"
  rg_total=${rg_got#total=}; rg_total=${rg_total%% *}
  if [ "${rg_total:-0}" -lt 1 ] || [ "$rg_got" != "total=$rg_total guarded=$rg_total" ]; then
    echo "FAIL: java-gate-pr.yml 的 actions/cache/restore 步骤必须都带 if: \${{ runner.environment != 'self-hosted' }} (且至少 1 个)"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - Maven 缓存恢复全部排除自建 runner"
  fi
  # 变异对照: 删掉第一处守卫, 必须查出来。
  GMUT="$WORK/wf-mutant.yml"
  awk 'done != 1 && /if: \$\{\{ runner\.environment != .self-hosted. \}\}/ && prev ~ /Restore Maven repository/ { done = 1; prev = $0; next } { print; prev = $0 }' "$WFY" > "$GMUT"
  mg=$(restore_guard_report "$GMUT")
  echo "READING: mutant(drop guard) restore steps: $mg"
  if [ "$mg" != "total=$rg_total guarded=$((rg_total - 1))" ]; then
    echo "FAIL: 守卫变异对照: 删掉一处守卫后应读到 total=$rg_total guarded=$((rg_total - 1)), 实际 $mg"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - 守卫变异对照: 删掉一处守卫后查出"
  fi
  # 同理: 上 mini 的 node 作业 (setup-node cache: 'npm') 只在托管机上用 actions/cache 恢复 npm 缓存。
  npm_bad=0; npm_n=0
  for wf in $actual_wfs; do
    f="$WFDIR/$wf"
    n=$(grep -c "cache: \${{ runner.environment == 'github-hosted' && 'npm' || '' }}" "$f"); npm_n=$((npm_n + n))
    grep -nE "^ +cache: 'npm'" "$f" && npm_bad=1
  done
  echo "READING: guarded npm cache inputs=$npm_n bare=$npm_bad"
  if [ "$npm_bad" != 0 ] || [ "$npm_n" -lt 2 ]; then
    echo "FAIL: mini workflow 的 setup-node 不许无条件 cache: 'npm' (须按 runner.environment 只在托管机恢复)"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - npm 缓存恢复只在托管机"; fi
fi
[ "$PASSES" -ge 21 ] || { echo "FAIL: 阳性对照: 只跑通 $PASSES 个场景 (预期 >= 21)"; REAL_FAILS=$((REAL_FAILS + 1)); }

# ── 心跳: 写端 (refill.sh) 与读端 (每个 mini workflow 的路由步骤传 vars.MINI_POOL_CAPACITY, 上面已核) 必须都在 (形态 D/B)。
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  hb_w=$(grep -c 'gh variable set MINI_POOL_CAPACITY' "$ROOT_DIR/scripts/ci/canada-mini-gate/refill.sh")
  hb_c=$(grep -c '^  beat$\|^    beat$' "$ROOT_DIR/scripts/ci/canada-mini-gate/refill.sh")
  echo "READING: heartbeat writer=$hb_w beat_calls=$hb_c"
  if [ "$hb_w" != 1 ] || [ "$hb_c" != 2 ]; then
    echo "FAIL: 心跳没接全: refill.sh 要写 1 处并在空闲/忙碌两个循环里各调 1 次 beat"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - 心跳写端接上"
  fi
  # 睡眠/过热 (2026-11-19 半醒里跑满 -> Dark Wake Thermal Emergency): 领活前叫醒、作业期间禁止睡眠、有温度告警不领活。
  mkdir -p "$WORK/pmsetbin"
  printf '#!/usr/bin/env bash
echo "$FAKE_THERM"
' > "$WORK/pmsetbin/pmset"; chmod +x "$WORK/pmsetbin/pmset"
  th() { PATH="$WORK/pmsetbin:$PATH" FAKE_THERM="$2" GH_GATE_PRESSURE_FILE="$WORK/no-pressure" bash "$1" --thermal-hot; }
  # 温度压力文件: Nominal/Moderate 不算热, Heavy 算热
  tp() { echo "$2" > "$WORK/pressure"; PATH="$WORK/pmsetbin:$PATH" FAKE_THERM="Note: No thermal warning level has been recorded" GH_GATE_PRESSURE_FILE="$WORK/pressure" bash "$1" --thermal-hot; }
  dw() { PATH="$WORK/pmsetbin:$PATH" FAKE_THERM="$2" bash "$1" --darkwake; }
  REFILL_F="$ROOT_DIR/scripts/ci/canada-mini-gate/refill.sh"
  tgot="ok=$(th "$REFILL_F" "$(printf 'Note: No thermal warning level has been recorded
Note: No performance warning level has been recorded')") hot=$(th "$REFILL_F" 'Thermal Warning Level set to 2.')"
  echo "READING: refill thermal: $tgot"
  if [ "$tgot" != "ok=no hot=yes" ]; then echo "FAIL: 温度判断应为 ok=no hot=yes"; REAL_FAILS=$((REAL_FAILS + 1)); else echo "  ok - 温度告警判断"; fi
  pgot="nominal=$(tp "$REFILL_F" Nominal) moderate=$(tp "$REFILL_F" Moderate) heavy=$(tp "$REFILL_F" Heavy) trapping=$(tp "$REFILL_F" Trapping)"
  echo "READING: refill pressure: $pgot"
  if [ "$pgot" != "nominal=no moderate=no heavy=yes trapping=yes" ]; then echo "FAIL: 压力判断应为 nominal=no moderate=no heavy=yes trapping=yes"; REAL_FAILS=$((REAL_FAILS + 1)); else echo "  ok - 温度压力判断"; fi
  # 半醒: 能力里没有 Graphics 才算; 读不到 (空输出) 不算 (不把「不知道」当半醒)。
  dgot="full=$(dw "$REFILL_F" 'Current System Capabilities are: CPU Graphics Audio Network') dark=$(dw "$REFILL_F" 'Current System Capabilities are: CPU Network') unknown=$(dw "$REFILL_F" '')"
  echo "READING: refill darkwake: $dgot"
  if [ "$dgot" != "full=no dark=yes unknown=no" ]; then echo "FAIL: 半醒判断应为 full=no dark=yes unknown=no"; REAL_FAILS=$((REAL_FAILS + 1)); else echo "  ok - 半醒判断"; fi
  w_guard=$(grep -c '^  awake_guard$\|^    awake_guard$' "$REFILL_F")
  echo "READING: awake_guard calls=$w_guard (空闲循环 + 忙碌循环)"
  if [ "$w_guard" != 2 ]; then echo "FAIL: awake_guard 要在空闲、忙碌两个循环里各调 1 次"; REAL_FAILS=$((REAL_FAILS + 1)); else echo "  ok - 半醒守护两个循环都接上"; fi
  w_full=$(grep -c '^  full_wake$' "$REFILL_F"); w_caf=$(grep -c 'caffeinate -i -s -w "\$pid"' "$REFILL_F"); w_hot=$(grep -c '^  if thermal_hot; then$' "$REFILL_F")
  echo "READING: sleep guards full_wake=$w_full caffeinate_job=$w_caf thermal_gate=$w_hot"
  # full_wake 两处: 领活前 (jitconfig 之后) + awake_guard 里。
  if [ "$w_full" != 2 ] || [ "$w_caf" != 1 ] || [ "$w_hot" != 1 ] || ! grep -A1 'jitconfig failed, retry in 30s' "$REFILL_F" | grep -q '^  full_wake$'; then
    echo "FAIL: refill.sh 要在领活前 (jitconfig 之后) 和 awake_guard 里调 full_wake、作业期间 caffeinate -i -s -w \$pid、循环里有 thermal_hot 闸"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - 叫醒/防睡/温度闸都接上"; fi
  # 注册 runner 时把全部 mini 标签都带上 (每个 labels[] 一个参数), 而不是单个标签。
  if grep -q 'for l in \$LABEL; do lab_args+=(-f "labels\[\]=\$l"); done' "$REFILL_F" && grep -q '"\${lab_args\[@\]}"' "$REFILL_F" \
     && ! grep -q '"labels\[\]=\$LABEL"' "$REFILL_F"; then echo "  ok - jitconfig 注册全部 mini 标签"
  else echo "FAIL: refill.sh 的 generate-jitconfig 必须按 \$LABEL (全部 mini 标签) 逐个传 labels[]"; REAL_FAILS=$((REAL_FAILS + 1)); fi
  # 变异对照: 删掉年龄判断, 过期心跳必须被查出来 (变异体只跑场景, 不递归)。
  mutant_dir
  HMUT="$WORK/mut/route-hb-mutant.sh"
  grep -v 'HEARTBEAT-AGE-CHECK' "$SCRIPT" > "$HMUT"
  if grep -q 'HEARTBEAT-AGE-CHECK' "$HMUT" || ! grep -q 'HEARTBEAT-AGE-CHECK' "$SCRIPT"; then
    echo "FAIL: 心跳变异没生效 (标记行找不到)"; REAL_FAILS=$((REAL_FAILS + 1))
  else
    ROUTE_SCRIPT="$HMUT" bash "${BASH_SOURCE[0]}" > "$WORK/hb-mutant.log" 2>&1
    hrc=$?
    echo "READING: mutant(no heartbeat age) rc=$hrc $(grep '^READING: pass=' "$WORK/hb-mutant.log") $(grep -c 'FAIL - 派发时心跳过期' "$WORK/hb-mutant.log") 个过期场景变红"
    if [ "$hrc" = 0 ] || ! grep -q 'FAIL - 派发时心跳过期' "$WORK/hb-mutant.log"; then
      echo "FAIL: 心跳变异对照: 删掉年龄判断后过期场景仍绿"; REAL_FAILS=$((REAL_FAILS + 1))
    else
      echo "  ok - 心跳变异对照: 删掉年龄判断后过期场景变红"
    fi
  fi
fi

# Each variant must fail the named decision, with a readable red assertion.
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  for mutation in busy retry merge_group pagination; do
    mutant_dir
    target="$WORK/mut/route-$mutation.sh"
    case "$mutation" in
      busy) sed '/BUSY-SLOT-CHECK/d' "$SCRIPT" > "$target"; expected_red="实时槽满且排队为零" ;;
      retry) sed '/GITHUB_RUN_ATTEMPT/d' "$SCRIPT" > "$target"; expected_red="pull_request 完整重跑" ;;
      merge_group)
        sed 's/group=$(verify_merge_group 2>\/dev\/null) || emit hosted na group-unverified/group=unchecked/' "$SCRIPT" > "$target"
        expected_red="合并组前序 fork" ;;
      pagination)
        cp "$SCRIPT" "$target"
        sed 's/api --paginate/api/' "$GATE/queue-snapshot.sh" > "$WORK/mut/canada-mini-gate/queue-snapshot.sh"
        expected_red="目标 run 和 mini job 都只在第 2 页" ;;
    esac
    ROUTE_SCRIPT="$target" bash "${BASH_SOURCE[0]}" > "$WORK/$mutation.log" 2>&1
    rc=$?
    if [ "$rc" = 1 ] && grep -Fq "FAIL - $expected_red" "$WORK/$mutation.log"; then
      red=$(grep -F "FAIL - $expected_red" "$WORK/$mutation.log")
      echo "  ok - $mutation mutation rc=$rc: $red"
    else
      echo "FAIL: $mutation mutant did not fail the target case (rc=$rc)"; REAL_FAILS=$((REAL_FAILS + 1))
    fi
  done
fi

# ── 变异对照: 路由排队口径只数公共标签 (旧 pg/fast 标签的排队作业不算进共用槽池), 「少于阈值」等场景必须变红。
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  mutant_dir
  LMUT="$WORK/mut/route-label-mutant.sh"
  sed 's/^COUNT_LABELS="\${MINI_COUNT_LABELS:-cretas-mini cretas-mini-pg cretas-mini-fast}"$/COUNT_LABELS="${MINI_COUNT_LABELS:-cretas-mini}"/' "$SCRIPT" > "$LMUT"
  if ! grep -q '^COUNT_LABELS="\${MINI_COUNT_LABELS:-cretas-mini}"$' "$LMUT"; then
    echo "FAIL: 排队标签变异没生效 (COUNT_LABELS 行找不到, 同步本测试的 sed)"; REAL_FAILS=$((REAL_FAILS + 1))
  else
    ROUTE_SCRIPT="$LMUT" bash "${BASH_SOURCE[0]}" > "$WORK/label-mutant.log" 2>&1
    lrc=$?
    echo "READING: mutant(count public label only) rc=$lrc $(grep '^READING: pass=' "$WORK/label-mutant.log") fails=$(grep -c '^  FAIL - ' "$WORK/label-mutant.log")"
    if [ "$lrc" = 0 ] || ! grep -q 'FAIL - 少于阈值' "$WORK/label-mutant.log"; then
      echo "FAIL: 排队标签变异对照: 只数公共标签后场景仍绿"; REAL_FAILS=$((REAL_FAILS + 1))
    else
      echo "  ok - 排队标签变异对照: 只数公共标签后场景变红"
    fi
  fi
fi

# ── 变异对照: 比较方向改反 (-lt -> -ge), 场景必须变红。仅在测真脚本时做 (测变异体时不递归)。
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  mutant_dir
  MUT="$WORK/mut/route-mutant.sh"
  sed 's/if \[ "\$QUEUED" -lt "\$THRESHOLD" \]/if [ "$QUEUED" -ge "$THRESHOLD" ]/' "$SCRIPT" > "$MUT"
  if ! grep -q '"\$QUEUED" -ge "\$THRESHOLD"' "$MUT"; then
    echo "FAIL: 变异没生效 (比较行找不到, 脚本改过了? 同步本测试的 sed)"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    ROUTE_SCRIPT="$MUT" bash "${BASH_SOURCE[0]}" > "$WORK/mutant.log" 2>&1
    mrc=$?
    echo "READING: mutant(-lt->-ge) rc=$mrc $(grep '^READING: pass=' "$WORK/mutant.log")"
    if [ "$mrc" = 0 ]; then
      echo "FAIL: 变异对照: 比较方向改反后场景仍全绿 —— 场景没守住阈值比较"
      REAL_FAILS=$((REAL_FAILS + 1))
    else
      echo "  ok - 变异对照: 比较方向改反后场景变红"
    fi
  fi
fi

if [ "$REAL_FAILS" = 0 ]; then echo "PASS"; exit 0; fi
echo "FAIL ($REAL_FAILS)"; exit 1
