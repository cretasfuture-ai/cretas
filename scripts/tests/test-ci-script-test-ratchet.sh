#!/usr/bin/env bash
# scripts/ci/run-script-tests.sh 的棘轮判定测试。
#
# 为什么单独测它: 这个 runner 是**闸的闸** —— 它判错一次, 后面 48 个用例的红绿
# 就全部不可信。而它最危险的失效模式是**假绿**(把红读成绿), 那恰恰是最难自己发现的。
#
# ⚠️ 在**临时目录**里造一个迷你 scripts/tests, 把 runner 复制进去跑。
#    ⛔ 不要拿真的 48 个用例来测这段逻辑: 那要几分钟, 而且真用例的红绿会变,
#    这个测试就跟着漂(形态 D)。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RUNNER="$ROOT_DIR/scripts/ci/run-script-tests.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

[ -s "$RUNNER" ] || fail "$RUNNER 不存在"
bash -n "$RUNNER" || fail "语法错误: $RUNNER"

mkdir -p "$TMP/scripts/ci" "$TMP/scripts/tests"
cp "$RUNNER" "$TMP/scripts/ci/run-script-tests.sh"

mk() {  # $1=名字 $2=退出码
    printf '#!/usr/bin/env bash\necho "fake %s"\nexit %s\n' "$1" "$2" > "$TMP/scripts/tests/$1"
    chmod +x "$TMP/scripts/tests/$1"
}
run() {
    ( cd "$TMP" && SCRIPT_TESTS_TIMEOUT=20 bash scripts/ci/run-script-tests.sh ) > "$TMP/out" 2>&1
    RC=$?
    # 形态 C″: 先把汇总行打出来再断言。
    echo "    $(grep -E '^TOTAL=' "$TMP/out" || echo '(没有汇总行!)')   rc=$RC"
}

# ── 基线: 2 绿 + 1 存量红(在名单里) ⇒ 整体绿 ──────────────────────────────
mk good-a.sh 0
mk good-b.sh 0
mk legacy-red.sh 1
printf 'legacy-red.sh  # 存量红, 另立任务\n' > "$TMP/scripts/tests/known-failing.txt"
run
[ "$RC" = 0 ] || { cat "$TMP/out"; fail "基线应为绿"; }
grep -q 'TOTAL=3 PASS=2 FAIL=0 KNOWN_FAILING=1 FIXED=0' "$TMP/out" || { cat "$TMP/out"; fail "基线汇总不对"; }
grep -q 'NO_NEW_FAILURES' "$TMP/out" || fail "缺少 NO_NEW_FAILURES"
ok "基线: 存量红记账后整体绿"

# ── 判定① 不在名单且 rc≠0 ⇒ 红。逐个退出码验, ⛔ 不枚举失败码 ─────────────
# 126(没有执行位) / 127(找不到命令) / 124(timeout) 都必须算红 —— 2026-08-27
# 那次假绿就是因为判定只认 1 和 2。
for code in 1 2 3 126 127; do
    mk newly-red.sh "$code"
    run
    [ "$RC" != 0 ] || { cat "$TMP/out"; fail "rc=$code 的新失败竟然被判绿"; }
    grep -q "NEW_FAILURE" "$TMP/out" || fail "rc=$code 没被标 NEW_FAILURE"
    rm -f "$TMP/scripts/tests/newly-red.sh"
done
ok "判定①: rc ∈ {1,2,3,126,127} 的新失败全部变红"

# timeout(124) 单独构造: 它不是脚本自己返回的, 是被打断的。
printf '#!/usr/bin/env bash\nsleep 60\n' > "$TMP/scripts/tests/hangs.sh"
chmod +x "$TMP/scripts/tests/hangs.sh"
( cd "$TMP" && SCRIPT_TESTS_TIMEOUT=2 bash scripts/ci/run-script-tests.sh ) > "$TMP/out" 2>&1
RC=$?
echo "    $(grep -E '^TOTAL=' "$TMP/out")   rc=$RC"
[ "$RC" != 0 ] || fail "挂死的用例被判绿"
grep -qE 'rc=124 +hangs\.sh +NEW_FAILURE' "$TMP/out" || { cat "$TMP/out"; fail "timeout 没被标 NEW_FAILURE"; }
ok "判定①: 挂死(rc=124)也算红, 且没有把整批拖死"
rm -f "$TMP/scripts/tests/hangs.sh"

# ── 判定② 在名单且 rc=0 ⇒ 红「已修好, 请删掉」 ⛔ 不许静默绿 ───────────────
# 【变异 A: 把名单里的用例改成 exit 0】
mk legacy-red.sh 0
echo "    变异到达确认: legacy-red.sh 现在 exit $(grep -o 'exit [0-9]*' "$TMP/scripts/tests/legacy-red.sh" | awk '{print $2}')"
run
[ "$RC" != 0 ] || { cat "$TMP/out"; fail "名单里的用例变绿了却没红 —— 棘轮退化成永久豁免"; }
grep -q 'FIXED' "$TMP/out" || fail "没标 FIXED"
grep -q '请从 known-failing.txt 删掉' "$TMP/out" || fail "措辞里没说清该怎么办"
ok "判定②: 名单里的用例修好后变红, 措辞是「请删掉」"

# ── 变异 B: 从名单里删掉一行(而用例还是红的) ⇒ 红 ─────────────────────────
mk legacy-red.sh 1
: > "$TMP/scripts/tests/known-failing.txt"
echo "    变异到达确认: 名单行数 = $(grep -c . "$TMP/scripts/tests/known-failing.txt")"
run
[ "$RC" != 0 ] || { cat "$TMP/out"; fail "名单删空后存量红竟然还是绿"; }
grep -q 'NEW_FAILURE' "$TMP/out" || fail "没标 NEW_FAILURE"
ok "变异 B: 名单删一行 ⇒ 那条红回来"

# ── 判定③ 名单里的文件不存在 ⇒ 红「测试被删了不是修好了」 ─────────────────
# 2026-08-27 实测: 棘轮把「删掉一个红测试」报成了「已修好」。
printf 'legacy-red.sh  # 存量红\nghost-test.sh  # 这个文件不存在\n' > "$TMP/scripts/tests/known-failing.txt"
echo "    变异到达确认: ghost-test.sh 存在吗 = $([ -f "$TMP/scripts/tests/ghost-test.sh" ] && echo yes || echo no)"
run
[ "$RC" != 0 ] || { cat "$TMP/out"; fail "名单指向不存在的文件竟然是绿的"; }
grep -q '测试被删了不是修好了' "$TMP/out" || { cat "$TMP/out"; fail "措辞不对"; }
ok "判定③: 名单指向已删文件 ⇒ 红"

# ── 名单条目必须带理由 ────────────────────────────────────────────────────
printf 'legacy-red.sh\n' > "$TMP/scripts/tests/known-failing.txt"
run
[ "$RC" != 0 ] || fail "没有理由的名单条目竟然放行"
grep -q 'known_failing_entry_without_reason' "$TMP/out" || { cat "$TMP/out"; fail "措辞不对"; }
ok "名单条目缺理由 ⇒ 红"

# ── 硬约束 9: 一个用例都没通过时不许报绿 ──────────────────────────────────
# 「FAIL=0」在没跑到任何东西时与「全绿」长得一模一样。
rm -f "$TMP/scripts/tests"/*.sh
printf '# 空名单\n' > "$TMP/scripts/tests/known-failing.txt"
run
[ "$RC" != 0 ] || fail "一个用例都没有时竟然报绿"
grep -q 'zero_tests_actually_passed' "$TMP/out" || { cat "$TMP/out"; fail "措辞不对"; }
ok "一个都没跑到 ⇒ rc≠0, ⛔ 不是绿"

echo
echo "PASS: test-ci-script-test-ratchet.sh"
