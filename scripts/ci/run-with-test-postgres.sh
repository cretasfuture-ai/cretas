#!/usr/bin/env bash
# Run a command against an isolated, disposable local PostgreSQL cluster.
set -euo pipefail
[ "$#" -gt 0 ] || { echo 'Usage: run-with-test-postgres.sh command [args...]' >&2; exit 2; }
[ "$(id -u)" -ne 0 ] || { echo 'Refusing to run the test database as root' >&2; exit 2; }
for candidate in "${CRETAS_CI_PG_BIN:-}" /usr/lib/postgresql/17/bin /opt/homebrew/opt/postgresql@17/bin; do
    if [ -n "$candidate" ] && [ -x "$candidate/initdb" ]; then export PATH="$candidate:$PATH"; break; fi
done
for tool in initdb pg_ctl createdb psql python3; do
    command -v "$tool" >/dev/null || { echo "Missing CI database tool: $tool" >&2; exit 2; }
done
cluster_root=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cretas-ci-pg.XXXXXX")
cleanup() {
    local result=$?
    trap - EXIT
    if [ -f "$cluster_root/data/postmaster.pid" ]; then
        if ! pg_ctl -D "$cluster_root/data" -m fast -w stop >>"$cluster_root/server.log" 2>&1; then
            echo "Failed to stop disposable database; evidence retained: $cluster_root" >&2
            exit 1
        fi
    fi
    if [ "$result" -ne 0 ]; then tail -30 "$cluster_root/server.log" 2>/dev/null || true; fi
    case "$cluster_root" in /*/cretas-ci-pg.*) rm -rf -- "$cluster_root" ;; *) exit 1 ;; esac
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
initdb -D "$cluster_root/data" --username=cretas_ci --auth-local=trust --auth-host=trust --encoding=UTF8 --no-locale >"$cluster_root/init.log"
pg_ctl -D "$cluster_root/data" -l "$cluster_root/server.log" -o "-h 127.0.0.1 -p $port -k $cluster_root -c shared_buffers=32MB -c max_connections=30" -w start
database=cretas_workflow_verify_test
createdb -h 127.0.0.1 -p "$port" -U cretas_ci "$database"
export SMARTBI_PG_DSN="postgresql://cretas_ci@127.0.0.1:$port/$database"
export CRETAS_WORKFLOW_PG_VERIFY=true
export CRETAS_WORKFLOW_PG_URL="jdbc:postgresql://127.0.0.1:$port/$database"
export CRETAS_WORKFLOW_PG_USER=cretas_ci CRETAS_WORKFLOW_PG_PASSWORD=''
echo "CI_POSTGRES=ready host=127.0.0.1 port=$port database=$database"
"$@"
