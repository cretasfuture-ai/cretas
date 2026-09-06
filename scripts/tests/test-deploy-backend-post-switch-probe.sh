#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

PROBE_HELPER=$(awk '
    /^# BEGIN_POST_SWITCH_PROBE_HELPER$/ {copy = 1; next}
    /^# END_POST_SWITCH_PROBE_HELPER$/ {copy = 0}
    copy {print}
' "$DEPLOY_SCRIPT")
[ -n "$PROBE_HELPER" ] || fail "post-switch probe helper markers missing"
eval "$PROBE_HELPER"

# ═══════════════════════════════════════════════════════════════════════════
# 桩的计数必须是**每种探针一个文件 + 追加**, ⛔ 不能是一个共享计数器
# ═══════════════════════════════════════════════════════════════════════════
# 2026-09-05/06: 这个用例在同一棵树上 Windows rc=0 / ubuntu rc=1, 被记成 FLAKY。
# 真因不在被测的 post_switch_probe 里, 在**桩**里:
#
#   post_switch_probe 把 HTTP 与 systemd 两个探针**并行**丢进后台子 shell
#   (`ssh ... & ssh ... & wait`)。原来的桩做的是「读文件 → 加一 → 写回」——
#   两个子 shell 同时读到同一个旧值, 各自写回一次, **丢更新**。于是 3 轮下来
#   计数是 3 而不是 6, 断言 `-eq 4` 当场红, 报的却是「probe did not stop after
#   the successful retry」—— 一个完全指错方向的措辞。
#   Windows/MSYS 上进程创建慢, 两个子 shell 很少真正重叠 ⇒ 那边"稳定绿"。
#
# ⇒ 每种探针各写各的文件, 且用 `>>` 追加一行(O_APPEND 下小于 PIPE_BUF 的写是
#   原子的)。这样无论两边怎么交错, 「第 n 次调用」对每种探针都是确定的。
PROBE_DIR="$TMP_ROOT/probe-calls"
PROBE_MODE=retry_then_pass

reset_probe_counters() { rm -rf "$PROBE_DIR"; mkdir -p "$PROBE_DIR"; }
probe_calls() { cat "$PROBE_DIR"/* 2>/dev/null | wc -l | tr -d '[:space:]'; }

ssh() {
    local kind
    case "$*" in
        *curl*)      kind=http ;;
        *systemctl*) kind=systemd ;;
        *)           return 2 ;;
    esac
    printf 'x\n' >> "$PROBE_DIR/$kind"
    local n
    n=$(wc -l < "$PROBE_DIR/$kind" | tr -d '[:space:]')
    # 每种探针**各自**的第 1 次失败、第 2 次起成功 —— 与原来「前两次调用失败」
    # 等价(串行时那两次正好是第一轮的 http 和 systemd), 但不再依赖交错顺序。
    if [ "$PROBE_MODE" = "always_fail" ] || [ "$n" -le 1 ]; then
        return 255
    fi
    case "$kind" in
        http)    printf '200\n' ;;
        systemd) printf 'active\n' ;;
    esac
}

sleep() { :; }

CRETAS_POST_SWITCH_PROBE_ATTEMPTS=3
CRETAS_POST_SWITCH_PROBE_RETRY_SECONDS=0
reset_probe_counters
post_switch_probe gateway server cretas-backend \
    || fail "probe did not recover after one transient SSH round"
echo "READING: PROBE_CALLS_after_retry_then_pass=$(probe_calls)"
[ "$(probe_calls)" -eq 4 ] \
    || fail "probe did not stop after the successful retry (期望 4 次调用 = 2 种探针 x 2 轮, 实际 $(probe_calls))"
[ "$POST_SWITCH_HTTP" = "200" ] || fail "successful HTTP status was not retained"
[ "$POST_SWITCH_SYSTEMD" = "active" ] || fail "successful systemd status was not retained"

PROBE_MODE=always_fail
reset_probe_counters
if post_switch_probe gateway server cretas-backend; then
    fail "sustained probe failure unexpectedly passed"
fi
echo "READING: PROBE_CALLS_after_always_fail=$(probe_calls)"
[ "$(probe_calls)" -eq 6 ] \
    || fail "sustained failure did not consume exactly three bounded attempts (期望 6, 实际 $(probe_calls))"

echo "PASS: post-switch probe retries transient SSH failures and preserves sustained-failure rollback signaling"
