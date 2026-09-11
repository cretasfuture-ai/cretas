#!/usr/bin/env bash
# Rebuild-free restart of the local eval backend against the eval DB (see metrics.md for the
# environment). Waits for "Started" or a startup failure and prints which. Usage:
#   scripts/eval/intent_routing/restart_java.sh [--build]
set -uo pipefail
HERE="$(cd "$(dirname "$0")/../../.." && pwd)"
API="$HERE/backend/java/cretas-api"
LOG="${EVAL_JAVA_LOG:-/tmp/java-local.log}"
PORT="${EVAL_JAVA_PORT:-10010}"

PID=$(netstat -ano 2>/dev/null | grep -E ":$PORT .*LISTENING" | awk '{print $NF}' | head -1)
if [ -n "$PID" ]; then taskkill //PID "$PID" //F >/dev/null 2>&1; sleep 3; fi

if [ "${1:-}" = "--build" ]; then
  (cd "$API" && mvn -q clean package -DskipTests > /tmp/mvn-eval-build.log 2>&1) || { echo "BUILD_FAILED see /tmp/mvn-eval-build.log"; exit 1; }
fi

export DB_PASSWORD=cretas_local_only POSTGRES_SMARTBI_PASSWORD=cretas_local_only
(cd "$API" && java -jar target/cretas-backend-system-1.0.0.jar --spring.profiles.active=pg \
  "--spring.datasource.url=jdbc:postgresql://localhost:15450/cretas_db?currentSchema=public&stringtype=unspecified" \
  "--smartbi.postgres.url=jdbc:postgresql://localhost:15450/smartbi_db" \
  --embedding.mode=grpc > "$LOG" 2>&1 &)

for _ in $(seq 1 120); do
  if grep -q -E 'Started .*Application in' "$LOG" 2>/dev/null; then echo "JAVA_STARTED"; exit 0; fi
  if grep -q -E 'APPLICATION FAILED TO START' "$LOG" 2>/dev/null; then echo "JAVA_FAILED"; grep -m3 'Caused by' "$LOG"; exit 1; fi
  sleep 5
done
echo "JAVA_TIMEOUT"; exit 1
