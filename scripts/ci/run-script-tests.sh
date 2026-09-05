#!/usr/bin/env bash
# 跑 scripts/tests/ 下全部 bash 用例, **逐行打出每个脚本的 rc**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要它
# ═══════════════════════════════════════════════════════════════════════════
# 2026-09-06 实测: 全仓 22 个 workflow **没有一个**引用 scripts/tests/ ——
# 这里 50 个用例从来只在有人想起来的时候手跑。「机制在、没接上」(形态 B)。
#
# ── 三条纪律 ──────────────────────────────────────────────────────────────
#  ① ⛔ 不 `|| true`、⛔ 不把测试管道进 tail。`cmd | tail` 的退出码是 **tail 的**,
#     失败会被读成成功且无声(硬约束 1)。诊断输出一律落文件, 事后再读。
#  ② rc 判定**不枚举失败码**: 只有 0 是通过, 其余(含 timeout 的 124、
#     command-not-found 的 127)一律算失败。2026-08-27 那次 nightly 假绿正是
#     因为判定只枚举了 rc==1 和 rc==2, 而实际是 127 —— 开放集合里的第四种值
#     两条都不命中, 静默落进「绿」。
#  ③ 跳过必须是**显式清单 + 理由**, 并打成 `SKIPPED:<原因>` —— ⛔ 不许长成绿。
#     清单在 scripts/ci/script-tests-skip.conf。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TESTS_DIR="$ROOT_DIR/scripts/tests"
SKIP_CONF="$ROOT_DIR/scripts/ci/script-tests-skip.conf"
PER_TEST_TIMEOUT="${SCRIPT_TESTS_TIMEOUT:-300}"
LOG_DIR="${SCRIPT_TESTS_LOGDIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"

[ -d "$TESTS_DIR" ] || { echo "error=tests_dir_missing $TESTS_DIR" >&2; exit 2; }
[ -f "$SKIP_CONF" ] || { echo "error=skip_conf_missing $SKIP_CONF" >&2; exit 2; }

# 跳过清单里的条目必须指向真实存在的用例 —— 否则它「保护」的是空气, 而将来出现
# 同名文件时会替那个新文件跳过(与 inventory 里 NOT_INSTALLED 同一条纪律)。
while IFS='|' read -r base reason; do
    case "$base" in ''|\#*) continue ;; esac
    [ -f "$TESTS_DIR/$base" ] || { echo "error=skip_entry_points_at_nothing $base" >&2; exit 2; }
    [ -n "${reason// /}" ] || { echo "error=skip_entry_without_reason $base" >&2; exit 2; }
done < "$SKIP_CONF"

n_total=0 n_pass=0 n_fail=0 n_skip=0
declare -a FAILED=()

for f in "$TESTS_DIR"/*.sh; do
    base=$(basename "$f")
    # test-helpers.sh 是被 source 的函数库, 不是用例。
    [ "$base" = "test-helpers.sh" ] && continue
    n_total=$((n_total + 1))

    reason=$(grep -E "^$base\|" "$SKIP_CONF" 2>/dev/null | head -1 | cut -d'|' -f2-)
    if [ -n "$reason" ]; then
        printf 'SKIPPED:%s  %s\n' "$base" "$reason"
        n_skip=$((n_skip + 1))
        continue
    fi

    timeout "$PER_TEST_TIMEOUT" bash "$f" > "$LOG_DIR/$base.log" 2>&1
    rc=$?
    printf 'rc=%-5s %s\n' "$rc" "$base"
    if [ "$rc" -eq 0 ]; then
        n_pass=$((n_pass + 1))
    else
        n_fail=$((n_fail + 1))
        FAILED+=("$base rc=$rc")
    fi
done

echo
echo "TOTAL=$n_total PASS=$n_pass FAIL=$n_fail SKIP=$n_skip"

# 硬约束 9: 「没有失败」必须配一个「一定会发生」的读数。全 skip / 一个都没跑到
# (glob 没命中、目录空)在 FAIL=0 上与「全绿」长得一模一样。
[ "$((n_pass + n_fail + n_skip))" -eq "$n_total" ] \
    || { echo "error=census_mismatch —— 计数器和实际循环对不上, 本次读数作废" >&2; exit 2; }
[ "$n_pass" -gt 0 ] \
    || { echo "error=zero_tests_actually_executed —— FAIL=0 在这里没有意义" >&2; exit 2; }

if [ "$n_fail" -gt 0 ]; then
    echo
    echo "===== 失败用例的日志尾部 ====="
    for entry in "${FAILED[@]}"; do
        b=${entry%% *}
        echo "----- $entry -----"
        tail -25 "$LOG_DIR/$b.log"
        echo
    done
    exit 1
fi

echo "ALL_GREEN"
exit 0
