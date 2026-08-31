#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

HELP_OUTPUT=$(bash "$DEPLOY_SCRIPT" --help)
grep -Fq 'prod (默认且唯一支持)' <<<"$HELP_OUTPUT" \
    || fail "help does not identify prod as the only supported environment"
grep -Fq 'test/all 已于 2026-07-13' <<<"$HELP_OUTPUT" \
    || fail "help does not explain the co-located test retirement"

TEST_OUTPUT=$(mktemp)
trap 'rm -f "$TEST_OUTPUT"' EXIT
for retired_env in test all; do
    if bash "$DEPLOY_SCRIPT" --env "$retired_env" --dry-run >"$TEST_OUTPUT" 2>&1; then
        fail "retired --env $retired_env unexpectedly continued"
    fi
    grep -Fq '同机 test/all 环境已于 2026-07-13 下线' "$TEST_OUTPUT" \
        || fail "retired $retired_env deployment did not fail closed with the expected message"
done

grep -Fq '[环境边界] test/10011 已下线，跳过跨环境健康检查' "$DEPLOY_SCRIPT" \
    || fail "prod deployment does not retain an explicit retired-test boundary"
if grep -F '恢复: ssh $SERVER' "$DEPLOY_SCRIPT" | grep -Fq 'restart.sh test'; then
    fail "deploy script still prints a recovery instruction for the retired test path"
fi
if grep -Fq 'test 10011 异常' "$DEPLOY_SCRIPT"; then
    fail "deploy script still emits the retired test false alarm"
fi

echo "PASS: retired co-located test environment fails closed and prod no longer emits the 10011 false alarm"
