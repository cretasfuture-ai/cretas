#!/usr/bin/env bash
# GUARDS=.github/workflows/java-gate-pr.yml,backend/java/cretas-api/src/test/java/**,scripts/ci/pr-test-selection.py,scripts/ci/pr-unselected-tests-baseline.txt,scripts/ci/pr-test-selection-allowlist.txt
# 测试类要么被 PR 作业选中, 要么在冻结基线里 (只许变少) —— 见 scripts/ci/pr-test-selection.py 头注释。
#
# 两段:
#   ① 真仓库: 检查必须通过 (新测试类没被 PR 选中 ⇒ 红)。
#   ② 阴性对照: 在临时目录里造一个最小仓库, 证明这道闸**能红**、--write 不会让基线变大、
#      收缩会被要求并被打印、靠标签的作业不算选中、!排除生效。⛔ 没有这段, 「通过」可能只是仪器坏了。
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PY="$(command -v python3 || command -v python)"
SCRIPT="$ROOT/scripts/ci/pr-test-selection.py"
fail=0
ok()  { echo "  ok - $1"; }
bad() { echo "  not ok - $1"; fail=1; }

echo "① 真仓库"
out="$("$PY" "$SCRIPT" --repo "$ROOT" 2>&1)"; rc=$?
echo "$out" | grep -E "^READING:" || true
if [ "$rc" -eq 0 ]; then ok "每个测试类都被 PR 作业选中或在基线里"; else echo "$out" | head -40; bad "rc=$rc"; fi

echo "② 阴性对照 (临时最小仓库)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/.github/workflows" "$T/backend/java/cretas-api/src/test/java/x" "$T/scripts/ci"
cat > "$T/.github/workflows/java-gate-pr.yml" <<'YAML'
on: pull_request
jobs:
  fast:
    runs-on: ubuntu-latest
    steps:
      - run: mvn test -Dtest='*ContractTest,!SkipMeContractTest'
  full:
    if: ${{ contains(github.event.pull_request.labels.*.name, 'java-full-ready') }}
    runs-on: ubuntu-latest
    steps:
      - run: mvn test -Dtest='*Test'
YAML
mk() { printf 'package x;\nclass %s { @org.junit.jupiter.api.Test void t() {} }\n' "$1" > "$T/backend/java/cretas-api/src/test/java/x/$1.java"; }
P="backend/java/cretas-api/src/test/java/x"
mk AContractTest; mk BPlainTest; mk SkipMeContractTest
args=(--repo "$T" --baseline "$T/scripts/ci/base.txt" --allowlist "$T/scripts/ci/allow.txt")

o="$("$PY" "$SCRIPT" "${args[@]}" 2>&1)"; rc=$?
{ [ "$rc" -eq 1 ] && echo "$o" | grep -q "+ $P/BPlainTest.java"; } \
  && ok "未选中的新类 ⇒ 红, 点名 BPlainTest" || { echo "$o"; bad "新类没被拦 rc=$rc"; }
echo "$o" | grep -q "+ $P/SkipMeContractTest.java" && ok "!排除生效: SkipMeContractTest 算未选中" || bad "!排除没生效"
echo "$o" | grep -q "+ $P/AContractTest.java" && bad "AContractTest 被当成未选中" || ok "命中模式的类算选中"
echo "$o" | grep -q "jobs=\['fast'\]" && ok "只靠标签才跑的作业不算选中" || { echo "$o" | grep READING; bad "标签作业被算进来了"; }

o="$("$PY" "$SCRIPT" "${args[@]}" --write 2>&1)"; rc=$?
{ [ "$rc" -eq 1 ] && echo "$o" | grep -q "REFUSED"; } && ok "--write 拒绝让基线变大" || { echo "$o"; bad "--write 扩张了基线 rc=$rc"; }
[ ! -s "$T/scripts/ci/base.txt" ] && ok "拒绝时没有写文件" || bad "拒绝时仍写了基线"

printf '# h\n%s/BPlainTest.java\n%s/SkipMeContractTest.java\n' "$P" "$P" > "$T/scripts/ci/base.txt"
"$PY" "$SCRIPT" "${args[@]}" >/dev/null 2>&1 && ok "在基线里 ⇒ 通过" || bad "基线内的类仍被拦"

rm "$T/$P/BPlainTest.java"; mk BPlainContractTest   # 改名命中模式
o="$("$PY" "$SCRIPT" "${args[@]}" 2>&1)"; rc=$?
{ [ "$rc" -eq 1 ] && echo "$o" | grep -q "\- $P/BPlainTest.java"; } && ok "基线条目失效 ⇒ 红, 要求 --write 收缩" || { echo "$o"; bad "失效条目没被要求收缩 rc=$rc"; }
o="$("$PY" "$SCRIPT" "${args[@]}" --write 2>&1)"; rc=$?
{ [ "$rc" -eq 0 ] && echo "$o" | grep -q "removed 1" && echo "$o" | grep -q "\- $P/BPlainTest.java"; } \
  && ok "--write 收缩并打印删掉的条目" || { echo "$o"; bad "--write 收缩 rc=$rc"; }
"$PY" "$SCRIPT" "${args[@]}" >/dev/null 2>&1 && ok "收缩后通过" || bad "收缩后仍红"

mk CNightlyTest; printf 'CNightlyTest\n' > "$T/scripts/ci/allow.txt"
o="$("$PY" "$SCRIPT" "${args[@]}" 2>&1)"; rc=$?
{ [ "$rc" -eq 1 ] && echo "$o" | grep -q "必须写理由"; } && ok "allowlist 不写理由 ⇒ 红" || { echo "$o"; bad "无理由 allowlist 被接受"; }
printf 'CNightlyTest 只跑真实 PG 的长耗时用例, nightly 覆盖\n' > "$T/scripts/ci/allow.txt"
"$PY" "$SCRIPT" "${args[@]}" >/dev/null 2>&1 && ok "allowlist 写了理由 ⇒ 通过" || bad "带理由的 allowlist 仍红"

rm -rf "$T/backend"
"$PY" "$SCRIPT" "${args[@]}" >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "测试树读不到 ⇒ rc=2 (没量到), 不是通过" || bad "仪器失明时 rc=$rc"

[ "$fail" -eq 0 ] && echo "PASS" || echo "FAIL"
exit "$fail"
