#!/usr/bin/env bash
# GUARDS=scripts/ci/canada-mini-gate/job-started.sh
#
# canada-mini 任务开始钩子: 工作区留在 sparse 状态时整个删掉 (2026-11-19 #1956 合并组 PG 两片
# 「working directory .../backend/java/cretas-api: No such file or directory」)。
# 场景:
#   1. sparse 工作区 (在 work-*/ 下) ⇒ 删掉, 打 SPARSE_RESET removed=
#   2. 非 sparse 工作区 ⇒ 保留, 打 not_sparse=
#   3. 不在 work-*/ 下的 sparse 目录 ⇒ 不删, 打 skip
#   4. GITHUB_WORKSPACE 为空 ⇒ skip, 钩子照常跑到镜像检查 (MIRROR missing 一行, 证明跑完了)
#   变异: 判定改成永不为真 ⇒ 场景 1 必须红 (证明断言守着删除行为)。
# 用 SUT_BASH=/bin/bash 可在 mini 上验 bash 3.2。
set -u
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SUT=$ROOT/scripts/ci/canada-mini-gate/job-started.sh
B=${SUT_BASH:-bash}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0

mkrepo() {  # $1 = 目录, $2 = sparse|full
  mkdir -p "$1"
  git -C "$1" init -q
  mkdir -p "$1/backend/java" "$1/scripts/ci"
  echo a > "$1/backend/java/a.txt"; echo s > "$1/scripts/ci/s.sh"
  git -C "$1" add -A
  git -C "$1" -c user.email=t@t -c user.name=t -c commit.gpgsign=false commit -q -m init
  if [ "$2" = sparse ]; then
    git -C "$1" sparse-checkout set --no-cone scripts/ci/s.sh >/dev/null 2>&1 \
      || { git -C "$1" config core.sparseCheckout true; }
  fi
}

run_hook() {  # $1 = sut, $2 = workspace
  GH_GATE_WORK_ROOT="$T/gh" GITHUB_WORKSPACE="$2" GITHUB_REF= GITHUB_SHA= "$B" "$1" 2>&1
}

check() {  # $1 label, $2 cond-result(0/1)
  if [ "$2" = 0 ]; then echo "  ok - $1"; else echo "  FAIL - $1"; fail=1; fi
}

scenarios() {  # $1 = sut; 返回失败数
  local sut=$1 bad=0 out
  rm -rf "$T/gh" "$T/other"
  mkrepo "$T/gh/work-1/cretas/cretas" sparse
  [ "$(git -C "$T/gh/work-1/cretas/cretas" config --get core.sparseCheckout)" = true ] || { echo "  前提不成立: 构造的仓库不是 sparse"; return 99; }
  out=$(run_hook "$sut" "$T/gh/work-1/cretas/cretas")
  echo "    READING s1: $(echo "$out" | grep SPARSE_RESET)"
  { [ ! -e "$T/gh/work-1/cretas/cretas" ] && echo "$out" | grep -q "SPARSE_RESET removed="; } || bad=$((bad+1))

  mkrepo "$T/gh/work-2/cretas/cretas" full
  out=$(run_hook "$sut" "$T/gh/work-2/cretas/cretas")
  echo "    READING s2: $(echo "$out" | grep SPARSE_RESET)"
  { [ -f "$T/gh/work-2/cretas/cretas/backend/java/a.txt" ] && echo "$out" | grep -q "not_sparse="; } || bad=$((bad+1))

  mkrepo "$T/other/cretas" sparse
  out=$(run_hook "$sut" "$T/other/cretas")
  echo "    READING s3: $(echo "$out" | grep SPARSE_RESET)"
  { [ -d "$T/other/cretas/.git" ] && echo "$out" | grep -q "SPARSE_RESET skip"; } || bad=$((bad+1))

  out=$(run_hook "$sut" "")
  echo "    READING s4: $(echo "$out" | grep -E 'SPARSE_RESET|MIRROR' | tr '\n' ' ')"
  { echo "$out" | grep -q "SPARSE_RESET skip workspace=<empty>" && echo "$out" | grep -q "READING: MIRROR missing="; } || bad=$((bad+1))
  return $bad
}

echo "== 原脚本"
scenarios "$SUT"; rc=$?
check "4 个场景全过 (失败数=$rc)" "$([ $rc = 0 ] && echo 0 || echo 1)"

echo "== 变异: sparse 判定永不为真"
M="$T/mutant.sh"
sed 's/if \[ "\$sp" = true \]; then/if [ "$sp" = never ]; then  # MUTANT/' "$SUT" > "$M"
grep -q MUTANT "$M" || { echo "  FAIL - 变异没打上"; fail=1; }
scenarios "$M"; mrc=$?
check "变异后场景 1 判红 (失败数=$mrc, 应 >=1)" "$([ $mrc -ge 1 ] && [ $mrc != 99 ] && echo 0 || echo 1)"

[ $fail = 0 ] && echo "PASS: job-started sparse 重置" || echo "FAIL: job-started sparse 重置"
exit $fail
