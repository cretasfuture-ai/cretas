#!/usr/bin/env bash
# GUARDS=scripts/ci/route-mini-or-hosted.sh
# 场景测试: Java Gate 分片路由 (mini 优先, 满了溢出托管)。用假 gh 驱动, 不碰网络。
#   少于阈值 -> mini / 达到阈值 -> hosted / API 出错 -> hosted / 返回结构不对 -> hosted /
#   fork -> hosted (且不调 gh) / ci-hosted 标签 -> hosted (且不调 gh) / merge_group -> 照常计数。
# 计数口径: 只数 status=queued 且带 cretas-mini-pg 标签的作业, 排除本 run。
# 末尾自带变异对照: 把比较方向改反, 本套场景必须变红 (变异没生效就判红, 硬约束 C)。
set -uo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRIPT="${ROUTE_SCRIPT:-$ROOT_DIR/scripts/ci/route-mini-or-hosted.sh}"
command -v jq >/dev/null 2>&1 || { echo "SKIPPED:jq 不在 PATH"; exit 77; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FIX="$WORK/fix"
mkdir -p "$FIX"

# ── 假 gh: `gh api <path>` -> 按路径取 fixture; FAKE_GH_FAIL=1 时一律失败; 每次调用记一行。
cat > "$WORK/gh" <<'SH'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_GH_LOG"
[ "${FAKE_GH_FAIL:-0}" = "1" ] && { echo "HTTP 502: Bad Gateway" >&2; exit 1; }
[ "$1" = "api" ] || exit 2
path="$2"
case "$path" in
  */actions/workflows/*/runs\?status=queued*)      cat "$FAKE_FIX/runs-queued.json" ;;
  */actions/workflows/*/runs\?status=in_progress*) cat "$FAKE_FIX/runs-in_progress.json" ;;
  */actions/runs/*/jobs*)
    id=${path#*/actions/runs/}; id=${id%%/*}
    cat "$FAKE_FIX/jobs-$id.json" ;;
  *) echo "unexpected path $path" >&2; exit 3 ;;
esac
SH
chmod +x "$WORK/gh"

write_fixtures() {
  # 本 run 999 也在 in_progress 里, 它的 5 个排队作业必须被排除。
  printf '%s' '{"workflow_runs":[{"id":101}]}' > "$FIX/runs-queued.json"
  printf '%s' '{"workflow_runs":[{"id":102},{"id":999}]}' > "$FIX/runs-in_progress.json"
  # 101: 1 个排队的 mini 作业 (计), 1 个排队的托管作业 (不计), 1 个在跑的 mini 作业 (不计)
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["ubuntu-latest"]},{"status":"in_progress","labels":["cretas-mini-pg"]}]}' > "$FIX/jobs-101.json"
  # 102: 1 个排队的 mini 作业 (计), 1 个还没定 labels 的作业 (不计)
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued"}]}' > "$FIX/jobs-102.json"
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]}]}' > "$FIX/jobs-999.json"
}

FAILS=0
PASSES=0
# run_case <名字> <期望 ROUTE 行> <期望 runner> <期望 gh 调用: any|none> [env 赋值...]
run_case() {
  local name="$1" want_line="$2" want_runner="$3" want_calls="$4"; shift 4
  local out="$WORK/out.$PASSES.$FAILS" ghout="$WORK/ghout" log="$WORK/ghlog"
  : > "$ghout"; : > "$log"
  env GH="$WORK/gh" FAKE_FIX="$FIX" FAKE_GH_LOG="$log" GITHUB_OUTPUT="$ghout" \
      GITHUB_REPOSITORY=cretasfuture-ai/cretas EVENT_NAME=pull_request \
      HEAD_REPO=cretasfuture-ai/cretas PR_LABELS='["java"]' GITHUB_RUN_ID=999 \
      MINI_QUEUE_THRESHOLD=4 "$@" bash "$SCRIPT" > "$out" 2>&1
  local rc=$? ok=1 why=""
  [ "$rc" = 0 ] || { ok=0; why="rc=$rc"; }
  grep -qxF "$want_line" "$out" || { ok=0; why="$why 缺行「$want_line」"; }
  [ "$(grep -c '^ROUTE=' "$out")" = 1 ] || { ok=0; why="$why ROUTE 行不是恰好 1 条"; }
  grep -qxF "runner=$want_runner" "$ghout" || { ok=0; why="$why GITHUB_OUTPUT 不是 runner=$want_runner"; }
  case "$want_calls" in
    none) [ ! -s "$log" ] || { ok=0; why="$why 不该调 gh 却调了"; } ;;
    any)  [ -s "$log" ] || { ok=0; why="$why 该调 gh 却没调"; } ;;
  esac
  if [ "$ok" = 1 ]; then
    PASSES=$((PASSES + 1)); echo "  ok - $name"
  else
    FAILS=$((FAILS + 1)); echo "  FAIL - $name:$why"; sed 's/^/      | /' "$out"
  fi
}

run_all() {
  write_fixtures
  run_case "少于阈值 -> mini (排队 2 < 4)" \
    "ROUTE=mini queued=2 threshold=4 reason=below-threshold" cretas-mini-pg any
  run_case "达到阈值 -> hosted (排队 2 >= 2)" \
    "ROUTE=hosted queued=2 threshold=2 reason=mini-full" ubuntu-latest any MINI_QUEUE_THRESHOLD=2
  run_case "超过阈值 -> hosted (排队 2 >= 1)" \
    "ROUTE=hosted queued=2 threshold=1 reason=mini-full" ubuntu-latest any MINI_QUEUE_THRESHOLD=1
  run_case "API 出错 -> hosted" \
    "ROUTE=hosted queued=na threshold=4 reason=api-error" ubuntu-latest any FAKE_GH_FAIL=1
  run_case "fork PR -> hosted 且不调 gh" \
    "ROUTE=hosted queued=na threshold=4 reason=fork" ubuntu-latest none HEAD_REPO=stranger/cretas
  run_case "ci-hosted 标签 -> hosted 且不调 gh" \
    "ROUTE=hosted queued=na threshold=4 reason=ci-hosted-label" ubuntu-latest none PR_LABELS='["java","ci-hosted"]'
  run_case "merge_group (无 head 仓库) -> 照常计数 -> mini" \
    "ROUTE=mini queued=2 threshold=4 reason=below-threshold" cretas-mini-pg any EVENT_NAME=merge_group HEAD_REPO=
  run_case "阈值不是整数 -> hosted" \
    "ROUTE=hosted queued=na threshold=abc reason=bad-threshold" ubuntu-latest none MINI_QUEUE_THRESHOLD=abc
  # 结构不对 (令牌失效时 API 回 {"message":...}) 不许当成 0 排队。
  printf '%s' '{"message":"Bad credentials"}' > "$FIX/runs-queued.json"
  run_case "返回结构不对 -> hosted (不当成 0)" \
    "ROUTE=hosted queued=na threshold=4 reason=api-error" ubuntu-latest any
  # 都没有运行 -> 0 排队 -> mini
  printf '%s' '{"workflow_runs":[]}' > "$FIX/runs-queued.json"
  printf '%s' '{"workflow_runs":[]}' > "$FIX/runs-in_progress.json"
  run_case "没有运行 -> queued=0 -> mini" \
    "ROUTE=mini queued=0 threshold=4 reason=below-threshold" cretas-mini-pg any
}

echo "== 被测: $SCRIPT"
run_all
echo "READING: pass=$PASSES fail=$FAILS"
REAL_FAILS=$FAILS
[ "$PASSES" -ge 10 ] || { echo "FAIL: 阳性对照: 只跑通 $PASSES 个场景 (预期 >= 10)"; REAL_FAILS=$((REAL_FAILS + 1)); }

# ── 变异对照: 比较方向改反 (-lt -> -ge), 场景必须变红。仅在测真脚本时做 (测变异体时不递归)。
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  MUT="$WORK/route-mutant.sh"
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
