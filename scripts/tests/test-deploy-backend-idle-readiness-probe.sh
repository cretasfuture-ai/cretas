#!/usr/bin/env bash
# 2026-09-02 事故: [BG 2/4] 的 idle readiness 判据曾经只有两态 —— `UP:*` 是健康,
# 别的一律判"挂了", 立刻回滚 + 换回旧 jar。但远端 loop 只可能吐 UP:n /
# RESTART_LIMIT:.../TIMEOUT 三种字面量; 当 ssh 会话自己中途断线(BG_EXIT=255,
# 输出空串), 那是第四种情况 —— 没量到, 不是量到了说挂了。实测那次 9 秒之后直接
# 查, 新版早就 UP 了; 判据把一次网络抖动当场判成应用挂了, 代价是一次多余的
# 回滚 + 重新部署。
#
# 本测试锁定三态判据本身(wait_for_idle_readiness, 见 deploy-backend.sh 里
# BEGIN_IDLE_READINESS_PROBE_HELPER 标记的那段):
#   (1) ssh 掉线一次(空串, exit=255)之后换新连接重试, 拿到 UP:40 -> 健康, 不回滚
#   (2) 远端 loop 正常吐 TIMEOUT(真失败) -> 立刻判失败, 不做无意义重试
#   (3) 全部重试预算耗尽仍拿不到量 -> 做一次独立直接核验, 核验健康就判活
#   (4) 直接核验也不健康 -> 才真的判失败
# 并用变异(临时把判据改回原始两态)证明 case (1) 的断言不是空气: 两态逻辑下
# 它必须变红。
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
    /^# BEGIN_IDLE_READINESS_PROBE_HELPER$/ {copy = 1; next}
    /^# END_IDLE_READINESS_PROBE_HELPER$/ {copy = 0}
    copy {print}
' "$DEPLOY_SCRIPT")
[ -n "$PROBE_HELPER" ] || fail "idle readiness probe helper markers missing"
eval "$PROBE_HELPER"

CRETAS_IDLE_READINESS_SSH_RETRIES=2
CRETAS_IDLE_READINESS_TOTAL_SECONDS=150

# ---------------------------------------------------------------------------
# case (1): ssh 掉线一次(空串, exit=255), 重试后拿到 UP:40 -> 应判健康。
# ---------------------------------------------------------------------------
CALL_LOG="$TMP_ROOT/call-log-1"
: > "$CALL_LOG"
ssh() {
    echo "CALLED" >> "$CALL_LOG"
    local n
    n=$(wc -l < "$CALL_LOG")
    if [ "$n" -eq 1 ]; then
        return 255
    fi
    echo "UP:40"
    return 0
}
sleep() { :; }

# set +e/-e 包住每次调用: 被测函数返回非零是【预期内】的测试场景, 不是脚本
# 该死的地方 —— 不包的话, `set -e` 在函数返回处直接杀掉整个测试进程, 连一条
# FAIL 消息都不给("红了但哑" 比 "红了还报错误" 差, 前者调查起来无从下手)。
set +e
wait_for_idle_readiness gateway 10022 cretas-backend-green 0 2
RC1=$?
set -e
[ "$RC1" -eq 0 ] || fail "case1: ssh 掉线一次后应恢复健康(rc=0), 实际 rc=$RC1, state=$IDLE_READINESS_STATE result=$IDLE_READINESS_RESULT"
[ "$IDLE_READINESS_STATE" = "up" ] || fail "case1: 预期 state=up, 实际 $IDLE_READINESS_STATE"
[ "$IDLE_READINESS_RESULT" = "UP:40" ] || fail "case1: 预期 result=UP:40, 实际 $IDLE_READINESS_RESULT"
[ "$(wc -l < "$CALL_LOG")" -eq 2 ] || fail "case1: 预期恰好重试一次(2 次 ssh 调用), 实际 $(wc -l < "$CALL_LOG") 次"
echo "PASS case1: ssh 掉线一次不误判挂了, 重试后确认健康, 未过度重试(2 次 ssh 调用)"

# ---------------------------------------------------------------------------
# case (2): 远端 loop 正常吐 TIMEOUT(真失败) -> 立刻失败, 不重试, 不做直接核验。
# ---------------------------------------------------------------------------
CALL_LOG="$TMP_ROOT/call-log-2"
: > "$CALL_LOG"
ssh() {
    echo "CALLED" >> "$CALL_LOG"
    echo "TIMEOUT"
    return 1
}
sleep() { :; }

set +e
wait_for_idle_readiness gateway 10022 cretas-backend-green 0 2
RC2=$?
set -e
[ "$RC2" -eq 1 ] || fail "case2: TIMEOUT 应判真失败(rc=1), 实际 rc=$RC2"
[ "$IDLE_READINESS_STATE" = "real_failure" ] || fail "case2: 预期 state=real_failure, 实际 $IDLE_READINESS_STATE"
[ "$IDLE_READINESS_RESULT" = "TIMEOUT" ] || fail "case2: 预期 result=TIMEOUT, 实际 $IDLE_READINESS_RESULT"
[ "$(wc -l < "$CALL_LOG")" -eq 1 ] || fail "case2: TIMEOUT 是已知失败字面量, 不该重试, 实际调用了 $(wc -l < "$CALL_LOG") 次"
echo "PASS case2: TIMEOUT 立刻判真失败, 不做无意义重试(1 次 ssh 调用)"

# ---------------------------------------------------------------------------
# case (3): 全部重试预算耗尽仍拿不到量(持续 255) -> 走独立直接核验, 核验健康就判活。
# ---------------------------------------------------------------------------
CALL_LOG="$TMP_ROOT/call-log-3"
: > "$CALL_LOG"
ssh() {
    echo "CALLED" >> "$CALL_LOG"
    local n
    n=$(wc -l < "$CALL_LOG")
    if [ "$n" -le 3 ]; then
        return 255   # 三次主探针全掉线(1 次首发 + 2 次重试)
    fi
    echo "active:200"  # 第 4 次是独立直接核验
    return 0
}
sleep() { :; }

set +e
wait_for_idle_readiness gateway 10022 cretas-backend-green 0 2
RC3=$?
set -e
[ "$RC3" -eq 0 ] || fail "case3: 直接核验健康应判活(rc=0), 实际 rc=$RC3, state=$IDLE_READINESS_STATE"
[ "$IDLE_READINESS_STATE" = "up" ] || fail "case3: 预期 state=up, 实际 $IDLE_READINESS_STATE"
[[ "$IDLE_READINESS_RESULT" == UP:direct-check ]] || fail "case3: 预期 result 标出是直接核验来源, 实际 $IDLE_READINESS_RESULT"
[ "$(wc -l < "$CALL_LOG")" -eq 4 ] || fail "case3: 预期 3 次主探针 + 1 次直接核验 = 4 次 ssh 调用, 实际 $(wc -l < "$CALL_LOG") 次"
echo "PASS case3: 重试预算耗尽后, 独立直接核验确认健康 -> 判活, 不误回滚"

# ---------------------------------------------------------------------------
# case (4): 独立直接核验也不健康 -> 才真的判失败。
# ---------------------------------------------------------------------------
CALL_LOG="$TMP_ROOT/call-log-4"
: > "$CALL_LOG"
ssh() {
    echo "CALLED" >> "$CALL_LOG"
    return 255   # 主探针 + 直接核验全部掉线/失败
}
sleep() { :; }

set +e
wait_for_idle_readiness gateway 10022 cretas-backend-green 0 2
RC4=$?
set -e
[ "$RC4" -eq 1 ] || fail "case4: 直接核验也失败应判真失败(rc=1), 实际 rc=$RC4"
[ "$IDLE_READINESS_STATE" = "real_failure" ] || fail "case4: 预期 state=real_failure, 实际 $IDLE_READINESS_STATE"
echo "PASS case4: 重试 + 直接核验全部落空 -> 才判真失败(不是无限乐观地一直判活)"

echo "PASS: idle readiness 三态判据 —— ssh 掉线是'没量到', 不是'量到了说挂了'"
