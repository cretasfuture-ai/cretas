#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-label-qc-model.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[ -f "$DEPLOY_SCRIPT" ] || fail "missing label-QC model deploy script"

health_line=$(grep -E '^health=\$\(curl ' "$DEPLOY_SCRIPT" || true)
[ -n "$health_line" ] || fail "missing production health probe after activation"

validate_health_retry() {
    local probe=$1
    [[ "$probe" == *"--retry 15"* ]] || return 1
    [[ "$probe" == *"--retry-connrefused"* ]] || return 1
    [[ "$probe" == *"--retry-delay 1"* ]] || return 1
    [[ "$probe" == *"--max-time 5"* ]] || return 1
}

validate_health_retry "$health_line" \
    || fail "health probe must retry refused connections while startup is bounded"

mutation=${health_line/--retry-connrefused/}
if validate_health_retry "$mutation"; then
    fail "mutation without connection-refused retry unexpectedly passed"
fi

grep -q '^trap rollback EXIT$' "$DEPLOY_SCRIPT" \
    || fail "activation rollback trap must remain active during health verification"

printf 'PASS: label-QC startup health retries are bounded and rollback remains armed\n'
