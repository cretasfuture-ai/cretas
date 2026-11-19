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
      MINI_QUEUE_THRESHOLD=4 ROUTE_NOW=1000000 MINI_HEARTBEAT=999990 "$@" bash "$SCRIPT" > "$out" 2>&1
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
  # mini 在线心跳 (2026-11-19): 排队数是 0 也不派 mini, 而且不去数排队 (不调 gh)。
  run_case "心跳过期 (200s > 180s) -> hosted mini-offline, 不调 gh"     "ROUTE=hosted queued=na threshold=4 reason=mini-offline label=cretas-mini-pg" runner=ubuntu-latest none     MINI_HEARTBEAT=999800
  run_case "心跳缺失 -> hosted mini-offline"     "ROUTE=hosted queued=na threshold=4 reason=mini-offline label=cretas-mini-pg" runner=ubuntu-latest none     MINI_HEARTBEAT=
  run_case "心跳不是数字 -> hosted mini-offline"     "ROUTE=hosted queued=na threshold=4 reason=mini-offline label=cretas-mini-pg" runner=ubuntu-latest none     MINI_HEARTBEAT=abc
  run_case "心跳正好 180s (边界, 不算过期) -> mini"     "ROUTE=mini queued=0 threshold=4 reason=below-threshold label=cretas-mini-pg" runner=cretas-mini-pg any     MINI_HEARTBEAT=999820
  run_case "fast 标签心跳过期 -> hosted mini-offline"     "ROUTE=hosted queued=na threshold=1 reason=mini-offline label=cretas-mini-fast" runner_fast=ubuntu-latest none     MINI_LABEL=cretas-mini-fast MINI_QUEUE_THRESHOLD=1 ROUTE_OUTPUT_KEY=runner_fast MINI_HEARTBEAT=999000
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
    # 钉住夜间 + 无覆盖文件 (全部槽启用), 否则结论随 CI 跑的时间变。
    for i in 1 2 3 4 5; do out="$out $i=$(PATH="$WORK/bsdbin:$PATH" GH_GATE_HOUR=23 GH_GATE_ENABLED_FILE="$WORK/no-such-file" bash "$1" --idle-rank-of "$i" 2>&1)"; done
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
  # 启用名单 (2026-11-19 Steve「最多 1 个 PG 1 个 fast」): 只启用 2 和 5 时, 槽 1 停用不算进槽 2 的名次 ⇒ 2=1 5=1;
  # 变异对照: 删掉名次里的 slot_enabled 跳过, 槽 2 名次必须变成 2。
  printf '2\n5\n' > "$WORK/enabled-slots"
  en_rank() { echo " 2=$(GH_GATE_ENABLED_FILE="$WORK/enabled-slots" bash "$1" --idle-rank-of 2) 5=$(GH_GATE_ENABLED_FILE="$WORK/enabled-slots" bash "$1" --idle-rank-of 5)"; }
  got=$(en_rank "$REFILL")
  echo "READING: refill idle ranks (enabled 2 5):$got"
  if [ "$got" != " 2=1 5=1" ]; then
    echo "FAIL: 只启用 2 5 时名次应为 2=1 5=1"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - 启用名单: 停用的槽不算进名次"
  fi
  EMUT="$WORK/refill-enabled-mutant.sh"
  grep -v '^    slot_enabled "\$k" || continue$' "$REFILL" > "$EMUT"
  mgot=$(en_rank "$EMUT")
  echo "READING: mutant(no enabled skip) idle ranks:$mgot"
  if [ "$mgot" != " 2=2 5=1" ]; then
    echo "FAIL: 启用名单变异对照: 删掉跳过后应读到 2=2 5=1, 实际$mgot (变异没生效或测试没守住)"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - 启用名单变异对照: 删掉跳过后名次变错"
  fi
  # 时段 (2026-11-19 Steve「22:00 后跑满, 8 点后 1 pg 1 fast」, 北京时间): [22,8) full, [8,22) day; 边界 7/8/21/22。
  win_all() { local h out=""; for h in 0 7 8 12 21 22 23 07 08; do out="$out $h=$(bash "$1" --window "$h")"; done; echo "$out"; }
  want_win=" 0=full 7=full 8=day 12=day 21=day 22=full 23=full 07=full 08=day"
  got=$(win_all "$REFILL")
  echo "READING: refill window:$got"
  if [ "$got" != "$want_win" ]; then echo "FAIL: 时段应为$want_win"; REAL_FAILS=$((REAL_FAILS + 1)); else echo "  ok - 时段边界正确"; fi
  # 无覆盖文件时按时段启用: 白天只有 1 5, 夜间全部; 有覆盖文件时以文件为准 (夜间也只开文件里的)。
  en_all() { local i out=""; for i in 1 2 3 4 5; do out="$out$(GH_GATE_HOUR="$2" GH_GATE_ENABLED_FILE="$3" bash "$1" --enabled-of "$i" | cut -c1)"; done; echo "$out"; }
  got="day=$(en_all "$REFILL" 10 "$WORK/no-such-file") night=$(en_all "$REFILL" 23 "$WORK/no-such-file") override=$(en_all "$REFILL" 23 "$WORK/enabled-slots")"
  echo "READING: refill enabled (y/n per slot 1-5): $got"
  if [ "$got" != "day=yynny night=yyyyy override=nynny" ]; then
    echo "FAIL: 启用应为 day=yynny night=yyyyy override=nynny"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - 按时段启用 + 覆盖文件优先"; fi
  # 变异对照: 去掉「< 8 点也算 full」, 凌晨必须被查出变成 day。
  WMUT="$WORK/refill-window-mutant.sh"
  sed 's/ || \[ "\$h" -lt "\$FULL_UNTIL" \]//' "$REFILL" > "$WMUT"
  mgot=$(win_all "$WMUT")
  echo "READING: mutant(no early-morning full) window:$mgot"
  if [ "$mgot" = "$want_win" ]; then echo "FAIL: 时段变异对照: 变异后时段仍正确 (变异没生效或测试没守住)"; REAL_FAILS=$((REAL_FAILS + 1))
  else echo "  ok - 时段变异对照: 去掉凌晨段后查出"; fi
  # workflow 必须用这一份时段 (形态 D: 不许在 workflow 里另写一份小时判断)。
  if grep -q 'refill.sh --window' "$WFY" && grep -q "needs.changes.outputs.mini_window == 'full'" "$WFY"      && grep -q "steps.window.outputs.mini_window == 'full' && env.MINI_PG_QUEUE_THRESHOLD_FULL" "$WFY"      && ! grep -qE 'TZ=|date \+%H|date -u \+%H' "$WFY"; then
    echo "  ok - workflow 时段取自 refill.sh --window (分片 + 阈值)"
  else
    echo "FAIL: java-gate-pr.yml 必须调 refill.sh --window 决定 PG 分片与阈值, 且不另写小时判断"; REAL_FAILS=$((REAL_FAILS + 1))
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
[ "$PASSES" -ge 18 ] || { echo "FAIL: 阳性对照: 只跑通 $PASSES 个场景 (预期 >= 18)"; REAL_FAILS=$((REAL_FAILS + 1)); }

# ── 心跳: 写端 (refill.sh) 与读端 (workflow 两条路由都传 vars.MINI_HEARTBEAT) 必须都在 (形态 D/B: 接上没有)。
if [ -z "${ROUTE_SCRIPT:-}" ]; then
  hb_w=$(grep -c 'gh variable set MINI_HEARTBEAT' "$ROOT_DIR/scripts/ci/canada-mini-gate/refill.sh")
  hb_r=$(grep -c 'MINI_HEARTBEAT: ${{ vars.MINI_HEARTBEAT }}' "$ROOT_DIR/.github/workflows/java-gate-pr.yml")
  hb_c=$(grep -c '^  beat$\|^    beat$' "$ROOT_DIR/scripts/ci/canada-mini-gate/refill.sh")
  echo "READING: heartbeat writer=$hb_w readers=$hb_r beat_calls=$hb_c"
  if [ "$hb_w" != 1 ] || [ "$hb_r" != 2 ] || [ "$hb_c" != 2 ]; then
    echo "FAIL: 心跳没接全: refill.sh 要写 1 处并在空闲/忙碌两个循环里各调 1 次 beat, workflow 两条路由都要传 vars.MINI_HEARTBEAT"
    REAL_FAILS=$((REAL_FAILS + 1))
  else
    echo "  ok - 心跳写端/读端都接上"
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
  # 变异对照: 删掉年龄判断, 过期心跳必须被查出来 (变异体只跑场景, 不递归)。
  HMUT="$WORK/route-hb-mutant.sh"
  grep -v 'HEARTBEAT-AGE-CHECK' "$SCRIPT" > "$HMUT"
  if grep -q 'HEARTBEAT-AGE-CHECK' "$HMUT" || ! grep -q 'HEARTBEAT-AGE-CHECK' "$SCRIPT"; then
    echo "FAIL: 心跳变异没生效 (标记行找不到)"; REAL_FAILS=$((REAL_FAILS + 1))
  else
    ROUTE_SCRIPT="$HMUT" bash "${BASH_SOURCE[0]}" > "$WORK/hb-mutant.log" 2>&1
    hrc=$?
    echo "READING: mutant(no heartbeat age) rc=$hrc $(grep '^READING: pass=' "$WORK/hb-mutant.log") $(grep -c 'FAIL - 心跳过期\|FAIL - fast 标签心跳过期' "$WORK/hb-mutant.log") 个过期场景变红"
    if [ "$hrc" = 0 ] || ! grep -q 'FAIL - 心跳过期' "$WORK/hb-mutant.log"; then
      echo "FAIL: 心跳变异对照: 删掉年龄判断后过期场景仍绿"; REAL_FAILS=$((REAL_FAILS + 1))
    else
      echo "  ok - 心跳变异对照: 删掉年龄判断后过期场景变红"
    fi
  fi
fi

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
