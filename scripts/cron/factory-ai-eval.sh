#!/usr/bin/env bash
# Daily Factory READ/WRITE contract evaluation. No tenant or training scope is added.
set -euo pipefail

PYDIR="${FACTORY_AI_EVAL_PYDIR:-/www/wwwroot/cretas/code/backend/python}"
ENV_PROD="${FACTORY_AI_EVAL_ENV_PROD:-/www/wwwroot/cretas/.env.prod}"
LOG="${FACTORY_AI_EVAL_LOG:-/www/wwwroot/cretas/logs/factory-ai-eval.log}"
ALERTS="${FACTORY_AI_EVAL_ALERTS:-/www/wwwroot/cretas/logs/factory-ai-eval-alerts.log}"
BASE="${FACTORY_AI_EVAL_BASE:-https://admin.cretaceousfuture.com}"
PYTHON="$PYDIR/venv-current/bin/python"

not_measured() {
    printf 'FACTORY EVAL NOT-MEASURED: %s\n' "$1" >&2
    exit 2
}

[ -d "$PYDIR" ] || not_measured 'Python service directory is unavailable'
[ -x "$PYTHON" ] || not_measured 'service venv-current interpreter is unavailable'
[ -r "$ENV_PROD" ] || not_measured 'production evaluation environment is unavailable'
cd "$PYDIR" || not_measured 'cannot enter Python service directory'

# Credentials stay in the existing private environment, never in cron arguments/logs.
set -a
source "$ENV_PROD" >/dev/null 2>&1 || not_measured 'cannot load production evaluation environment'
set +a

{
    overall=0
    for battery in READ WRITE; do
        case "$battery" in
            READ) module=smartbi.scripts.factory_ai_eval ;;
            WRITE) module=smartbi.scripts.factory_write_eval ;;
        esac
        printf '=== %s factory %s eval ===\n' "$(date '+%F %T')" "$battery"
        if PYTHONIOENCODING=utf-8 "$PYTHON" -X utf8 -m "$module" --base "$BASE"; then
            result=0
        else
            result=$?
        fi
        printf '=== factory %s done (rc=%s) ===\n' "$battery" "$result"
        case "$result" in
            0) ;;
            1)
                printf 'FACTORY %s EVAL FAILED rc=1\n' "$battery" >> "$ALERTS"
                [ "$overall" -ne 0 ] || overall=1
                ;;
            *)
                printf 'FACTORY %s EVAL NOT-MEASURED rc=%s\n' "$battery" "$result" >> "$ALERTS"
                overall=2
                ;;
        esac
    done
    exit "$overall"
} >> "$LOG" 2>&1
