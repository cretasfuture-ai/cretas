#!/usr/bin/env bash
# Isolated executable contract: never reads credentials or calls a real evaluator.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TASK_TMP="$(mktemp -d)"
trap 'rm -rf -- "$TASK_TMP"' EXIT
mkdir -p "$TASK_TMP/py/venv-current/bin"
cat > "$TASK_TMP/py/venv-current/bin/python" <<'FAKE'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$FACTORY_TEST_CALLS"
case "$*" in
    *smartbi.scripts.factory_ai_eval*) exit "${FACTORY_TEST_READ_RC:-0}" ;;
    *smartbi.scripts.factory_write_eval*) exit "${FACTORY_TEST_WRITE_RC:-0}" ;;
    *) exit 91 ;;
esac
FAKE
chmod +x "$TASK_TMP/py/venv-current/bin/python"
printf 'export FACTORY_EVAL_USERNAME=fixture_only\n' > "$TASK_TMP/production.env"
export FACTORY_AI_EVAL_PYDIR="$TASK_TMP/py"
export FACTORY_AI_EVAL_ENV_PROD="$TASK_TMP/production.env"
export FACTORY_AI_EVAL_LOG="$TASK_TMP/eval.log"
export FACTORY_AI_EVAL_ALERTS="$TASK_TMP/alerts.log"
export FACTORY_AI_EVAL_BASE=https://factory-eval.invalid
export FACTORY_TEST_CALLS="$TASK_TMP/calls.log"

run_case() {
    local read_rc=$1 write_rc=$2 expected=$3 actual
    : > "$FACTORY_TEST_CALLS"
    : > "$FACTORY_AI_EVAL_ALERTS"
    export FACTORY_TEST_READ_RC=$read_rc FACTORY_TEST_WRITE_RC=$write_rc
    if bash "$ROOT/scripts/cron/factory-ai-eval.sh"; then actual=0; else actual=$?; fi
    [ "$actual" -eq "$expected" ] || { echo "wrong aggregate status: $actual" >&2; exit 1; }
    [ "$(wc -l < "$FACTORY_TEST_CALLS" | tr -d ' ')" -eq 2 ]
    grep -q -- '-m smartbi.scripts.factory_ai_eval --base https://factory-eval.invalid' "$FACTORY_TEST_CALLS"
    grep -q -- '-m smartbi.scripts.factory_write_eval --base https://factory-eval.invalid' "$FACTORY_TEST_CALLS"
}

run_case 0 0 0
run_case 1 0 1
grep -q 'READ EVAL FAILED' "$FACTORY_AI_EVAL_ALERTS"
run_case 0 2 2
grep -q 'WRITE EVAL NOT-MEASURED' "$FACTORY_AI_EVAL_ALERTS"
run_case 2 1 2
grep -q 'READ EVAL NOT-MEASURED' "$FACTORY_AI_EVAL_ALERTS"
grep -q 'WRITE EVAL FAILED' "$FACTORY_AI_EVAL_ALERTS"
run_case 7 0 2
grep -q 'READ EVAL NOT-MEASURED rc=7' "$FACTORY_AI_EVAL_ALERTS"

: > "$FACTORY_TEST_CALLS"
export FACTORY_AI_EVAL_ENV_PROD="$TASK_TMP/missing.env"
if bash "$ROOT/scripts/cron/factory-ai-eval.sh"; then exit 1; else [ "$?" -eq 2 ]; fi
[ ! -s "$FACTORY_TEST_CALLS" ]
export FACTORY_AI_EVAL_ENV_PROD="$TASK_TMP/production.env"
mkdir -p "$TASK_TMP/no-venv"
export FACTORY_AI_EVAL_PYDIR="$TASK_TMP/no-venv"
if bash "$ROOT/scripts/cron/factory-ai-eval.sh"; then exit 1; else [ "$?" -eq 2 ]; fi
[ ! -s "$FACTORY_TEST_CALLS" ]
export FACTORY_AI_EVAL_PYDIR="$TASK_TMP/missing-python"
if bash "$ROOT/scripts/cron/factory-ai-eval.sh"; then exit 1; else [ "$?" -eq 2 ]; fi
[ ! -s "$FACTORY_TEST_CALLS" ]
echo 'Factory evaluation cron: 8 executable cases passed; production/API/credentials untouched'
