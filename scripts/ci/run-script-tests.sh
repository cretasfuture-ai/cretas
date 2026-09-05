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
# ── 判定四条 (⛔ 退出码是开放集合, 不枚举失败码) ─────────────────────────
#   ① 不在 known-failing 且 rc≠0  ⇒ 红。**任何非 0**, 含 timeout 的 124、
#      找不到命令的 127、没有执行位的 126。
#      2026-08-27 那次 nightly 假绿正是因为判定只枚举了 rc==1 和 rc==2, 而实际
#      是 127 —— 开放集合里的第四种值两条都不命中, 静默落进「绿」(硬约束 4)。
#   ② 在 known-failing 且 rc=0    ⇒ 红,「已修好, 请从 known-failing.txt 删掉」。
#      ⛔ 不许静默绿 —— 那样名单只会变长, 棘轮就退化成了永久豁免。
#   ③ known-failing 里的文件不存在 ⇒ 红,「测试被删了不是修好了」。
#      2026-08-27 实测: 棘轮把「删掉一个红测试」报成了「已修好」。
#   ④ FLAKY 条目缺双向 run id / 两个 id 相同 / 登记超过 14 天 ⇒ 红。
#      FLAKY 不参与红绿判定, 所以它必须自带出口, 否则就是一张永久豁免。
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
declare -a LISTED=() FLAKY_LIST=()
missing_listed=""
bad_entry=""
stale_flaky=""
while IFS= read -r raw; do
    # ⚠️ 两种取法各有用途, ⛔ 别混:
    #   body_fields —— 去注释、**保留空格**, 用来切字段(FLAKY 行有 5 个字段);
    #   body        —— 再去掉所有空白, 用来判空 / 判前缀 / 当单个文件名。
    body_fields="${raw%%#*}"
    body="$(printf '%s' "$body_fields" | tr -d '[:space:]')"
    [ -n "$body" ] || continue
    reason="${raw#*#}"
    [ "$reason" != "$raw" ] && [ -n "$(printf '%s' "$reason" | tr -d '[:space:]')" ] \
        || bad_entry="$bad_entry $body"
    if [ "${body#FLAKY}" != "$body" ]; then
        # ── 第四态: 读数不可判定 ──────────────────────────────────────────
        # ⛔ 它是四态里**唯一不参与红绿判定**的, 所以必须自带出口, 否则就是一张
        #    永久豁免。known-failing 的出口是 FIXED 探测器; FLAKY 没有那个 ——
        #    时红时绿的东西, 任何单次读数都不能证明它已经稳定。
        # ⇒ 两道闸:
        #   ① **双向 run id**: 必须同时贴出一次 rc=0 和一次 rc≠0 的 run。
        #      只贴一个等于「我看它失败过」—— 那是**确定性失败**的证据, 不是 flaky 的。
        #   ② **日期过期**: 超过 14 天就红, 强制重新分类。
        #      时间会替你做判断: 它要么早修好了, 要么根本不是 flaky。
        # 格式: FLAKY <name> <YYYY-MM-DD> <run-id-rc0> <run-id-rc≠0>  # <理由>
        set -- $body_fields
        name="${2:-}"; fdate="${3:-}"; run_ok="${4:-}"; run_bad="${5:-}"
        if [ -z "$name" ] || [ -z "$fdate" ] || [ -z "$run_ok" ] || [ -z "$run_bad" ]; then
            bad_entry="$bad_entry ${name:-<无名>}(FLAKY-格式应为:FLAKY_name_日期_rc0run_rcNrun)"
        elif [ "$run_ok" = "$run_bad" ]; then
            # 同一个 run 不可能既 rc=0 又 rc≠0 —— 贴两遍同一个 id 是在凑格式。
            bad_entry="$bad_entry ${name}(FLAKY-两个run-id相同:$run_ok)"
        elif ! printf '%s' "$fdate" | grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'; then
            bad_entry="$bad_entry ${name}(FLAKY-日期应为YYYY-MM-DD:$fdate)"
        else
            entry_epoch=$(date -u -d "$fdate" +%s 2>/dev/null || echo "")
            if [ -z "$entry_epoch" ]; then
                bad_entry="$bad_entry ${name}(FLAKY-日期解析不了:$fdate)"
            else
                age_days=$(( ( $(date -u +%s) - entry_epoch ) / 86400 ))
                if [ "$age_days" -gt 14 ]; then
                    stale_flaky="$stale_flaky ${name}(登记于 $fdate, 已 ${age_days} 天)"
                fi
            fi
        fi
        [ -z "$name" ] || [ -f "$TESTS_DIR/$name" ] || missing_listed="$missing_listed $name"
        [ -z "$name" ] || FLAKY_LIST+=("$name")
    else
        [ -f "$TESTS_DIR/$body" ] || missing_listed="$missing_listed $body"
        LISTED+=("$body")
    fi
done < "$KNOWN"

[ -z "$bad_entry" ] || { echo "error=known_failing_entry_invalid$bad_entry (格式/理由/证据 有一项不合规)" >&2; exit 2; }
if [ -n "$stale_flaky" ]; then
    echo "FAIL: FLAKY 条目过期, 请重新分类:$stale_flaky" >&2
    echo "      FLAKY 是四态里唯一不参与红绿判定的, 所以它必须会过期 —— 否则就是永久豁免。" >&2
    echo "      ⇒ 要么它早就稳定了(移出 FLAKY), 要么它其实是确定性失败(移进 known-failing)," >&2
    echo "        要么它真还在抖(重新取两侧 run id 并把日期更新到今天)。" >&2
    exit 1
fi
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
is_flaky() {
    local x
    for x in ${FLAKY_LIST[@]+"${FLAKY_LIST[@]}"}; do [ "$x" = "$1" ] && return 0; done
    return 1
}

n_total=0 n_pass=0 n_fail=0 n_known=0 n_fixed=0 n_flaky=0
declare -a FAILED=() FIXED=()

for f in "$TESTS_DIR"/*.sh; do
    base=$(basename "$f")
    # test-helpers.sh 是被 source 的函数库, 不是用例。
    [ "$base" = "test-helpers.sh" ] && continue
    n_total=$((n_total + 1))

    timeout "$PER_TEST_TIMEOUT" bash "$f" > "$LOG_DIR/$base.log" 2>&1
    rc=$?

    if is_flaky "$base"; then
        # rc 照常打印(⛔ 不藏), 但不参与红绿判定 —— 它的读数在这台机器上不可判定。
        printf 'rc=%-5s %-45s FLAKY(读数不可判定, 不参与判定)\n' "$rc" "$base"
        n_flaky=$((n_flaky + 1))
    elif is_listed "$base"; then
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
echo "TOTAL=$n_total PASS=$n_pass FAIL=$n_fail KNOWN_FAILING=$n_known FIXED=$n_fixed FLAKY=$n_flaky"

# 硬约束 9: 「没有新失败」必须配一个「一定会发生」的读数。glob 没命中 / 目录空
# 在 FAIL=0 上与「全绿」长得一模一样。
[ "$((n_pass + n_fail + n_known + n_fixed + n_flaky))" -eq "$n_total" ] \
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

[ "$rc_final" -eq 0 ] && echo "NO_NEW_FAILURES (存量红 $n_known 条已记账, 读数不可判定 $n_flaky 条)"
exit "$rc_final"
