#!/usr/bin/env bash
# Read-only production check for Cretas. Never restarts, writes or deploys.
#
# Usage:
#   prod-check.sh [status]            # services, health, DBs, Redis, web-admin
#   prod-check.sh logs <java|python|web> [N]
#   prod-check.sh errors <java|python> [N]
#
# Exit codes (status): 0 all checks passed / 1 at least one check failed /
# 2 not measured (upstream or SSH unreadable). Treat 2 as "unknown", not "fine".
#
# Java runs blue-green on 10010 (cretas-backend, cretas-prod.log) and
# 10020 (cretas-backend-green, cretas-prod-green.log). The active slot is read
# from the gateway upstream every time; never assume 10010.

set -uo pipefail

PRIMARY="root@47.100.235.168"
GATEWAY="root@139.196.165.140"
UPSTREAM_CONF="/www/server/panel/vhost/nginx/_upstream_cretas.conf"
WEB_URL="http://139.196.165.140:8086/"
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10)

ACTIVE_PORT=""
ACTIVE_UNIT=""
ACTIVE_LOG=""

resolve_active_slot() {
    local line
    line=$("${SSH[@]}" "$GATEWAY" "grep -E '^[[:space:]]*server[[:space:]]+47\.100\.235\.168:' $UPSTREAM_CONF" 2>/dev/null) || {
        echo "UNMEASURED: cannot read $UPSTREAM_CONF on $GATEWAY" >&2
        return 2
    }
    if [ "$(printf '%s\n' "$line" | grep -c .)" != "1" ]; then
        echo "UNMEASURED: expected exactly 1 active server line in upstream, got:" >&2
        printf '%s\n' "$line" >&2
        return 2
    fi
    ACTIVE_PORT=$(printf '%s' "$line" | sed -E 's/.*47\.100\.235\.168:([0-9]+).*/\1/')
    case "$ACTIVE_PORT" in
        10010) ACTIVE_UNIT="cretas-backend";       ACTIVE_LOG="/www/wwwroot/cretas/cretas-prod.log" ;;
        10020) ACTIVE_UNIT="cretas-backend-green"; ACTIVE_LOG="/www/wwwroot/cretas/cretas-prod-green.log" ;;
        *) echo "UNMEASURED: unknown active port '$ACTIVE_PORT'" >&2; return 2 ;;
    esac
    echo "active slot: $ACTIVE_PORT ($ACTIVE_UNIT)"
}

log_path() {
    case "$1" in
        java)   echo "$ACTIVE_LOG" ;;
        python) echo "/www/wwwroot/cretas/python-prod.log" ;;
        *) return 1 ;;
    esac
}

cmd_status() {
    resolve_active_slot || return 2

    # One SSH round trip; each line is "<check> <OK|FAIL> <detail>".
    local remote
    remote=$("${SSH[@]}" "$PRIMARY" bash -s -- "$ACTIVE_PORT" "$ACTIVE_UNIT" <<'REMOTE'
port=$1; unit=$2
for u in "$unit" cretas-python cretas-embedding postgresql redis; do
    s=$(systemctl is-active "$u" 2>/dev/null)
    [ "$s" = active ] && echo "unit:$u OK $s" || echo "unit:$u FAIL $s"
done
c=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://localhost:$port/api/mobile/health")
[ "$c" = 200 ] && echo "java-health:$port OK $c" || echo "java-health:$port FAIL $c"
c=$(curl -s -m 10 -o /dev/null -w '%{http_code}' http://localhost:8083/health)
[ "$c" = 200 ] && echo "python-health OK $c" || echo "python-health FAIL $c"
for db in cretas_prod_db smartbi_prod_db; do
    r=$(sudo -u postgres psql -tAc 'select 1' "$db" 2>&1 | head -1)
    [ "$r" = 1 ] && echo "pg:$db OK" || echo "pg:$db FAIL $r"
done
r=$(redis-cli ping 2>&1 | head -1)
[ "$r" = PONG ] && echo "redis OK" || echo "redis FAIL $r"
echo "remote-done OK"
REMOTE
    ) || true

    # "remote-done" must appear; without it the per-check lines prove nothing.
    if ! printf '%s\n' "$remote" | grep -q '^remote-done OK'; then
        echo "UNMEASURED: SSH to $PRIMARY did not complete" >&2
        printf '%s\n' "$remote" >&2
        return 2
    fi

    local web
    web=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$WEB_URL")
    if [ "$web" = 200 ]; then remote+=$'\n'"web-admin OK $web"; else remote+=$'\n'"web-admin FAIL $web"; fi

    printf '%s\n' "$remote" | grep -v '^remote-done'
    local failed
    failed=$(printf '%s\n' "$remote" | grep -c ' FAIL' || true)
    echo "failed=$failed"
    [ "$failed" = 0 ] && return 0 || return 1
}

cmd_logs() {
    local svc=${1:-} n=${2:-100}
    case "$svc" in
        java)   resolve_active_slot || return 2; "${SSH[@]}" "$PRIMARY" "tail -n $n $(log_path java)" ;;
        python) "${SSH[@]}" "$PRIMARY" "tail -n $n $(log_path python)" ;;
        web)    "${SSH[@]}" "$GATEWAY" "tail -n $n /www/wwwlogs/web-admin.error.log" ;;
        *) usage; return 1 ;;
    esac
}

cmd_errors() {
    local svc=${1:-} n=${2:-50}
    case "$svc" in
        java)   resolve_active_slot || return 2 ;;
        python) ;;
        *) usage; return 1 ;;
    esac
    "${SSH[@]}" "$PRIMARY" "grep -E 'ERROR|Exception|Traceback' $(log_path "$svc") | tail -n $n"
}

usage() {
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
}

case "${1:-status}" in
    status) cmd_status ;;
    logs)   shift; cmd_logs "$@" ;;
    errors) shift; cmd_errors "$@" ;;
    -h|--help|help) usage ;;
    *) usage; exit 1 ;;
esac
