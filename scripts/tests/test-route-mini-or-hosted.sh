#!/usr/bin/env bash
# GUARDS=scripts/ci/route-mini-or-hosted.sh,scripts/ci/canada-mini-gate/refill.sh,.github/workflows/java-gate-pr.yml
# 场景测试: Java Gate 分片路由 (mini 优先, 满了溢出托管)。用假 gh 驱动, 不碰网络。
#   少于阈值 -> mini / 达到阈值 -> hosted / API 出错 -> hosted / 返回结构不对 -> hosted /
#   fork -> hosted (且不调 gh) / ci-hosted 标签 -> hosted (且不调 gh) / merge_group -> 照常计数 /
#   fast 标签 (槽 5, 阈值 1) 只数自己标签、输出键 runner_fast。
# 计数口径: 只数 status=queued 且带被路由标签 (MINI_LABEL) 的作业, 排除本 run。
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
  # 101: 1 个排队的 PG mini 作业 (PG 计), 1 个排队的托管作业 (不计), 1 个在跑的 mini 作业 (不计),
  #      1 个排队的 fast mini 作业 (只有 fast 标签计)
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["ubuntu-latest"]},{"status":"in_progress","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-fast"]}]}' > "$FIX/jobs-101.json"
  # 102: 1 个排队的 mini 作业 (计), 1 个还没定 labels 的作业 (不计)
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued"}]}' > "$FIX/jobs-102.json"
  printf '%s' '{"jobs":[{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]},{"status":"queued","labels":["cretas-mini-pg"]}]}' > "$FIX/jobs-999.json"
}

FAILS=0
PASSES=0
# run_case <名字> <期望 ROUTE 行> <期望 GITHUB_OUTPUT 行 key=value> <期望 gh 调用: any|none> [env 赋值...]
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
  grep -qxF "$want_runner" "$ghout" || { ok=0; why="$why GITHUB_OUTPUT 没有 $want_runner"; }
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
    "ROUTE=mini queued=2 threshold=4 reason=below-threshold label=cretas-mini-pg" runner=cretas-mini-pg any
  run_case "达到阈值 -> hosted (排队 2 >= 2)" \
    "ROUTE=hosted queued=2 threshold=2 reason=mini-full label=cretas-mini-pg" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD=2
  run_case "超过阈值 -> hosted (排队 2 >= 1)" \
    "ROUTE=hosted queued=2 threshold=1 reason=mini-full label=cretas-mini-pg" runner=ubuntu-latest any MINI_QUEUE_THRESHOLD=1
  run_case "API 出错 -> hosted" \
    "ROUTE=hosted queued=na threshold=4 reason=api-error label=cretas-mini-pg" runner=ubuntu-latest any FAKE_GH_FAIL=1
  run_case "fork PR -> hosted 且不调 gh" \
    "ROUTE=hosted queued=na threshold=4 reason=fork label=cretas-mini-pg" runner=ubuntu-latest none HEAD_REPO=stranger/cretas
  run_case "ci-hosted 标签 -> hosted 且不调 gh" \
    "ROUTE=hosted queued=na threshold=4 reason=ci-hosted-label label=cretas-mini-pg" runner=ubuntu-latest none PR_LABELS='["java","ci-hosted"]'
  run_case "merge_group (无 head 仓库) -> 照常计数 -> mini" \
    "ROUTE=mini queued=2 threshold=4 reason=below-threshold label=cretas-mini-pg" runner=cretas-mini-pg any EVENT_NAME=merge_group HEAD_REPO=
  # fast 线 (槽 5, 阈值 1): 只数自己的标签, PG 的排队不算进来。
  run_case "fast 标签: 排队 1 >= 阈值 1 -> hosted" \
    "ROUTE=hosted queued=1 threshold=1 reason=mini-full label=cretas-mini-fast" runner_fast=ubuntu-latest any \
    MINI_LABEL=cretas-mini-fast MINI_QUEUE_THRESHOLD=1 ROUTE_OUTPUT_KEY=runner_fast
  run_case "fast 标签: 排队 1 < 阈值 2 -> mini (输出 fast 标签)" \
    "ROUTE=mini queued=1 threshold=2 reason=below-threshold label=cretas-mini-fast" runner_fast=cretas-mini-fast any \
    MINI_LABEL=cretas-mini-fast MINI_QUEUE_THRESHOLD=2 ROUTE_OUTPUT_KEY=runner_fast
  run_case "阈值不是整数 -> hosted" \
    "ROUTE=hosted queued=na threshold=abc reason=bad-threshold label=cretas-mini-pg" runner=ubuntu-latest none MINI_QUEUE_THRESHOLD=abc
  # 结构不对 (令牌失效时 API 回 {"message":...}) 不许当成 0 排队。
  printf '%s' '{"message":"Bad credentials"}' > "$FIX/runs-queued.json"
  run_case "返回结构不对 -> hosted (不当成 0)" \
    "ROUTE=hosted queued=na threshold=4 reason=api-error label=cretas-mini-pg" runner=ubuntu-latest any
  # 都没有运行 -> 0 排队 -> mini
  printf '%s' '{"workflow_runs":[]}' > "$FIX/runs-queued.json"
  printf '%s' '{"workflow_runs":[]}' > "$FIX/runs-in_progress.json"
  run_case "没有运行 -> queued=0 -> mini" \
    "ROUTE=mini queued=0 threshold=4 reason=below-threshold label=cretas-mini-pg" runner=cretas-mini-pg any
  run_case "fast 标签: 没有排队 -> mini (阈值 1)" \
    "ROUTE=mini queued=0 threshold=1 reason=below-threshold label=cretas-mini-fast" runner_fast=cretas-mini-fast any \
    MINI_LABEL=cretas-mini-fast MINI_QUEUE_THRESHOLD=1 ROUTE_OUTPUT_KEY=runner_fast
}

echo "== 被测: $SCRIPT"
run_all
echo "READING: pass=$PASSES fail=$FAILS"
REAL_FAILS=$FAILS

# ── mini 补位脚本的槽 -> 标签 与 workflow 路由的标签必须一致 (同一个东西两份, 形态 D)。
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  REFILL="$ROOT_DIR/scripts/ci/canada-mini-gate/refill.sh"
  WFY="$ROOT_DIR/.github/workflows/java-gate-pr.yml"
  got=""
  for i in 1 2 3 4 5; do got="$got $i=$(bash "$REFILL" --label-of "$i" 2>&1)"; done
  echo "READING: refill slot labels:$got"
  if [ "$got" != " 1=cretas-mini-pg 2=cretas-mini-pg 3=cretas-mini-pg 4=cretas-mini-pg 5=cretas-mini-fast" ]; then
    echo "FAIL: refill.sh 槽 1-4 应为 cretas-mini-pg、槽 5 应为 cretas-mini-fast"
    REAL_FAILS=$((REAL_FAILS + 1))
  fi
  for l in cretas-mini-pg cretas-mini-fast; do
    if ! grep -q "MINI_LABEL: $l\$" "$WFY" || ! grep -q "&& '$l' || 'ubuntu-latest' }}" "$WFY"; then
      echo "FAIL: java-gate-pr.yml 缺 $l 的路由 (MINI_LABEL 或 runs-on)"
      REAL_FAILS=$((REAL_FAILS + 1))
    else
      echo "  ok - refill 标签 $l 在 workflow 里有路由与 runs-on"
    fi
  done

  # ── 空闲名次 (2026-11-19 run 44403277567: macOS `seq 1 0` 倒数输出「1 0」, 槽 1 名次成 3, 只剩 1 个排队作业时
  #    PG 槽全不起)。PATH 前面放一个按 BSD 行为倒数的假 seq, 让 Linux 上也能复现; 全部槽空闲时名次必须是
  #    同标签里的序号: 1=1 2=2 3=3 4=4 5=1 (槽 5 独占 fast 标签)。
  mkdir -p "$WORK/bsdbin"
  cat > "$WORK/bsdbin/seq" <<'SH'
#!/usr/bin/env bash
a=$1; b=$2
if [ "$a" -le "$b" ]; then i=$a; while [ "$i" -le "$b" ]; do echo "$i"; i=$((i + 1)); done
else i=$a; while [ "$i" -ge "$b" ]; do echo "$i"; i=$((i - 1)); done; fi
SH
  chmod +x "$WORK/bsdbin/seq"
  rank_of_all() {  # $1 = 被测脚本
    local i out=""
    for i in 1 2 3 4 5; do out="$out $i=$(PATH="$WORK/bsdbin:$PATH" bash "$1" --idle-rank-of "$i" 2>&1)"; done
    echo "$out"
  }
  want_rank=" 1=1 2=2 3=3 4=4 5=1"
  got=$(rank_of_all "$REFILL")
  echo "READING: refill idle ranks (BSD seq):$got"
  if [ "$got" != "$want_rank" ]; then
    echo "FAIL: refill.sh 空闲名次应为$want_rank"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - refill 空闲名次在 BSD seq 下正确"
  fi
  # 变异对照: 把循环改回 seq 写法, 名次必须变错。
  RMUT="$WORK/refill-mutant.sh"
  sed 's/for ((k = 1; k < s; k++)); do/for k in $(seq 1 $((s - 1))); do/' "$REFILL" > "$RMUT"
  if ! grep -q 'for k in \$(seq 1' "$RMUT"; then
    echo "FAIL: 名次变异没生效 (循环行找不到, 同步本测试的 sed)"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    mgot=$(rank_of_all "$RMUT")
    echo "READING: mutant(seq) idle ranks:$mgot"
    if [ "$mgot" = "$want_rank" ]; then
      echo "FAIL: 名次变异对照: 改回 seq 后名次仍正确 —— 测试没复现 BSD 行为"
      REAL_FAILS=$((REAL_FAILS + 1))
    else
      echo "  ok - 名次变异对照: 改回 seq 后名次变错"
    fi
  fi

  # ── Maven 缓存恢复不许在自建 runner 上跑 (2026-11-19: 5 槽共用 ~ghgate/.m2, 恢复原地解压让别的槽读到半截 jar)。
  #    每个 actions/cache/restore 步骤块 (从它所在的「- name:」到下一个「- name:」) 必须带 self-hosted 排除守卫。
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
  # 变异体里删了一处守卫: 必须正好查出少一处 (guarded = total - 1)。没少 = 变异没生效或检查查不出, 都判红。
  if [ "$mg" != "total=$rg_total guarded=$((rg_total - 1))" ]; then
    echo "FAIL: 守卫变异对照: 删掉一处守卫后应读到 total=$rg_total guarded=$((rg_total - 1)), 实际 $mg"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - 守卫变异对照: 删掉一处守卫后查出"
  fi
fi
[ "$PASSES" -ge 13 ] || { echo "FAIL: 阳性对照: 只跑通 $PASSES 个场景 (预期 >= 13)"; REAL_FAILS=$((REAL_FAILS + 1)); }

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
