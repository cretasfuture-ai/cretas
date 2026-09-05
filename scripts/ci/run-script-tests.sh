#!/usr/bin/env bash
# 跑 scripts/tests/ 下全部 bash 用例, **逐行打出每个脚本的 rc**, 按棘轮判定。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要它
# ═══════════════════════════════════════════════════════════════════════════
# 2026-09-06 实测: 全仓 22 个 workflow **没有一个**引用 scripts/tests/ ——
# 这里 48 个用例从来只在有人想起来的时候手跑。「机制在、没接上」(形态 B)。
# 接上的第一天就照出 11 条存量红。
#
# ── 判定三条 (⛔ 退出码是开放集合, 不枚举失败码) ─────────────────────────
#   ① 不在 known-failing 且 rc≠0  ⇒ 红。**任何非 0**, 含 timeout 的 124、
#      找不到命令的 127、没有执行位的 126。
#      2026-08-27 那次 nightly 假绿正是因为判定只枚举了 rc==1 和 rc==2, 而实际
#      是 127 —— 开放集合里的第四种值两条都不命中, 静默落进「绿」(硬约束 4)。
#   ② 在 known-failing 且 rc=0    ⇒ 红,「已修好, 请从 known-failing.txt 删掉」。
#      ⛔ 不许静默绿 —— 那样名单只会变长, 棘轮就退化成了永久豁免。
#   ③ known-failing 里的文件不存在 ⇒ 红,「测试被删了不是修好了」。
#      2026-08-27 实测: 棘轮把「删掉一个红测试」报成了「已修好」。
#
# ── 另外两条纪律 ──────────────────────────────────────────────────────────
#   ⛔ 不 `|| true`、⛔ 不把测试管道进 tail。`cmd | tail` 的退出码是 **tail 的**,
#      失败会被读成成功且无声(硬约束 1)。诊断输出一律落文件, 事后再读。
#   ⛔ 名单里的用例**照常执行**, 不是 skip。skip 掉就永远发现不了它已经修好了。
set -uo pipefail
# glob 没命中时展开成字面量 `*.sh`, 于是会去"跑"一个不存在的文件并记成一条失败 ——
# 一个**假的读数**。nullglob 让它展开成空, 由下面的 zero_tests 闸如实报「没量到」。
shopt -s nullglob

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TESTS_DIR="$ROOT_DIR/scripts/tests"
KNOWN="$TESTS_DIR/known-failing.txt"
PER_TEST_TIMEOUT="${SCRIPT_TESTS_TIMEOUT:-300}"
LOG_DIR="${SCRIPT_TESTS_LOGDIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"

[ -d "$TESTS_DIR" ] || { echo "error=tests_dir_missing $TESTS_DIR" >&2; exit 2; }
[ -f "$KNOWN" ] || { echo "error=known_failing_missing $KNOWN" >&2; exit 2; }

# ── 解析棘轮名单 ──────────────────────────────────────────────────────────
# ⛔ 不用「筛掉不匹配的行」那种写法 —— 一条写错的条目会静默从名单里消失,
#    而它守的那个用例就变成了「不在名单」⇒ 一红就红, 或者更糟, 反过来。
#    (本 PR 里另一个闸刚栽过这个: 筛子把「写错了」变成了「我没看见」, 形态 A⁴)
declare -a LISTED=()
missing_listed=""
bad_entry=""
while IFS= read -r raw; do
    line="${raw%%#*}"
    line="$(printf '%s' "$line" | tr -d '[:space:]')"
    [ -n "$line" ] || continue
    reason="${raw#*#}"
    [ "$reason" != "$raw" ] && [ -n "$(printf '%s' "$reason" | tr -d '[:space:]')" ] \
        || bad_entry="$bad_entry $line"
    [ -f "$TESTS_DIR/$line" ] || missing_listed="$missing_listed $line"
    LISTED+=("$line")
done < "$KNOWN"

[ -z "$bad_entry" ] || { echo "error=known_failing_entry_without_reason$bad_entry" >&2; exit 2; }
if [ -n "$missing_listed" ]; then
    echo "FAIL: known-failing.txt 里这些用例**文件不存在**:$missing_listed" >&2
    echo "      ⛔ 测试被删了不是修好了。要么把文件找回来, 要么在同一个 commit 里" >&2
    echo "      说清为什么它该消失 —— ⛔ 不要只从名单里划掉。" >&2
    exit 1
fi

is_listed() {
    local x
    for x in ${LISTED[@]+"${LISTED[@]}"}; do [ "$x" = "$1" ] && return 0; done
    return 1
}

n_total=0 n_pass=0 n_fail=0 n_known=0 n_fixed=0
declare -a FAILED=() FIXED=()

for f in "$TESTS_DIR"/*.sh; do
    base=$(basename "$f")
    # test-helpers.sh 是被 source 的函数库, 不是用例。
    [ "$base" = "test-helpers.sh" ] && continue
    n_total=$((n_total + 1))

    timeout "$PER_TEST_TIMEOUT" bash "$f" > "$LOG_DIR/$base.log" 2>&1
    rc=$?

    if is_listed "$base"; then
        if [ "$rc" -eq 0 ]; then
            printf 'rc=%-5s %-45s FIXED — 请从 known-failing.txt 删掉\n' "$rc" "$base"
            n_fixed=$((n_fixed + 1)); FIXED+=("$base")
        else
            printf 'rc=%-5s %-45s KNOWN_FAILING\n' "$rc" "$base"
            n_known=$((n_known + 1))
        fi
    elif [ "$rc" -eq 0 ]; then
        printf 'rc=%-5s %s\n' "$rc" "$base"
        n_pass=$((n_pass + 1))
    else
        printf 'rc=%-5s %-45s NEW_FAILURE\n' "$rc" "$base"
        n_fail=$((n_fail + 1)); FAILED+=("$base rc=$rc")
    fi
done

echo
echo "TOTAL=$n_total PASS=$n_pass FAIL=$n_fail KNOWN_FAILING=$n_known FIXED=$n_fixed"

# 硬约束 9: 「没有新失败」必须配一个「一定会发生」的读数。glob 没命中 / 目录空
# 在 FAIL=0 上与「全绿」长得一模一样。
[ "$((n_pass + n_fail + n_known + n_fixed))" -eq "$n_total" ] \
    || { echo "error=census_mismatch —— 计数器和实际循环对不上, 本次读数作废" >&2; exit 2; }
[ "$n_pass" -gt 0 ] \
    || { echo "error=zero_tests_actually_passed —— FAIL=0 在这里没有意义" >&2; exit 2; }

rc_final=0
if [ "$n_fail" -gt 0 ]; then
    echo
    echo "===== 新增失败的日志尾部 ====="
    for entry in ${FAILED[@]+"${FAILED[@]}"}; do
        b=${entry%% *}
        echo "----- $entry -----"
        tail -25 "$LOG_DIR/$b.log"
        echo
    done
    rc_final=1
fi

if [ "$n_fixed" -gt 0 ]; then
    echo
    echo "===== 已修好, 但还挂在 known-failing.txt 上 ====="
    for b in ${FIXED[@]+"${FIXED[@]}"}; do echo "  $b"; done
    echo "⇒ 从 scripts/tests/known-failing.txt 删掉这些行。棘轮只许变短。"
    rc_final=1
fi

[ "$rc_final" -eq 0 ] && echo "NO_NEW_FAILURES (存量红 $n_known 条已记账)"
exit "$rc_final"
