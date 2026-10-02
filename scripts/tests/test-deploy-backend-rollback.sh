#!/usr/bin/env bash
# SCRIPT_TEST_TIMEOUT=60
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT=${DEPLOY_SCRIPT:-"$ROOT_DIR/scripts/deploy/deploy-backend.sh"}
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

# Load the real rollback functions, never the executable deployment entrypoint.
eval "$(awk '/^# BEGIN_BOUND_ROLLBACK_HELPERS$/ {copy=1;next} /^# END_BOUND_ROLLBACK_HELPERS$/ {copy=0} copy {print}' "$DEPLOY_SCRIPT")"
eval "$(awk '/^deploy_rollback\(\) \{/ {copy=1} copy {print} copy && /^}$/ {exit}' "$DEPLOY_SCRIPT")"

export SHA256_BIN
SHA256_BIN=$(type -P sha256sum || true)
sha256sum() {
    if [ -n "$SHA256_BIN" ]; then "$SHA256_BIN" "$@"; else shasum -a 256 "$@"; fi
}
file_sha256() {
    local checksum
    checksum=$(sha256sum "$1") || return 1
    printf '%s\n' "${checksum%% *}"
}
event_line() {
    local event
    event=$(grep -n -m1 "$1" "$EVENTS") || return 1
    printf '%s\n' "${event%%:*}"
}
cp() {
    printf 'copy %s\n' "$*" >> "$EVENTS"
    local src=$1
    [ "$src" != -p ] || src=$2
    if [ "$FAULT" = copy ] && [[ "$src" == *.bak.* ]]; then return 41; fi
    command cp "$@"
}
sed() {
    if [[ "$OSTYPE" == darwin* ]] && [ "${1:-}" = -i ]; then
        shift
        command sed -i '' "$@"
    else
        command sed "$@"
    fi
}
systemctl() {
    printf 'systemctl %s\n' "$*" >> "$EVENTS"
    if [ "$1" = restart ] && [ "$FAULT" = restart ]; then return 42; fi
    if [ "$1" = is-active ]; then printf 'active\n'; fi
}
nginx() {
    printf 'nginx %s\n' "$*" >> "$EVENTS"
    [ "$FAULT" != gateway ]
}
ssh() {
    while [[ "${1:-}" == -* ]]; do shift 2; done
    case "$1" in stub-server|stub-gateway) ;; *) echo 'NETWORK FORBIDDEN' >&2; return 99 ;; esac
    printf 'ssh %s\n' "$1" >> "$EVENTS"
    shift
    bash -c "$*"
}
wait_for_health_via_ssh() {
    printf 'health %s\n' "$2" >> "$EVENTS"
    [ "$2" = "$EXPECTED_PORT" ] && [ "$FAULT" != health ]
}
post_switch_probe() {
    printf 'gateway-health %s\n' "$3" >> "$EVENTS"
    [ "$3" = "$EXPECTED_SERVICE" ] && [ "$FAULT" != gateway ]
}
log() { printf '%s %s\n' "$1" "$2"; }
export -f sha256sum cp sed systemctl nginx

SERVER=stub-server GATEWAY=stub-gateway
BLUE_PORT=10010 GREEN_PORT=10020
BLUE_SERVICE=cretas-backend GREEN_SERVICE=cretas-backend-green
RUNTIME_JAR_NAME=aims-0.0.1-SNAPSHOT.jar
DEPLOY_ENV=prod
failures=0
checks=0

run_case() (
    local port=$1 entry=$2 fault=$3
    export FAULT=$fault EXPECTED_PORT=$port
    EXPECTED_SERVICE=$BLUE_SERVICE
    [ "$port" != 10020 ] || EXPECTED_SERVICE=$GREEN_SERVICE
    export EXPECTED_SERVICE
    local case_root="$TMP_ROOT/$port-$entry-$fault"
    mkdir -p "$case_root"
    REMOTE_JAR_DIR=$case_root
    NGINX_UPSTREAM_FILE="$case_root/upstream.conf"
    export EVENTS="$case_root/events"
    : > "$EVENTS"
    printf 'old artifact\n' > "$case_root/$RUNTIME_JAR_NAME.bak.bound"
    printf 'new artifact\n' > "$case_root/$RUNTIME_JAR_NAME"
    printf 'unrelated newer backup\n' > "$case_root/$RUNTIME_JAR_NAME.bak.decoy"
    touch -t 209901010000 "$case_root/$RUNTIME_JAR_NAME.bak.decoy"
    local old_sha new_sha
    old_sha=$(file_sha256 "$case_root/$RUNTIME_JAR_NAME.bak.bound") || return 1
    new_sha=$(file_sha256 "$case_root/$RUNTIME_JAR_NAME") || return 1
    printf 'CRETAS_ROLLBACK_V1\n%s\n%s\n%s\n%s\n' "$RUNTIME_JAR_NAME.bak.bound" "$old_sha" "$port" "$new_sha" > "$case_root/.last-deploy-rollback"
    local binding
    binding=$(cat "$case_root/.last-deploy-rollback")
    local current_port=$BLUE_PORT
    [ "$port" != "$BLUE_PORT" ] || current_port=$GREEN_PORT
    printf 'upstream cretas_backend {\n server 47.100.235.168:%s; # ACTIVE=%s\n}\n' "$current_port" "$current_port" > "$NGINX_UPSTREAM_FILE"
    # Legacy baseline also runs entirely inside the stub filesystem.
    printf '#!/usr/bin/env bash\nsystemctl restart cretas-backend\n' > "$case_root/restart.sh"
    case "$fault" in
        hash) printf 'tampered\n' > "$case_root/$RUNTIME_JAR_NAME.bak.bound" ;;
        missing) rm "$case_root/.last-deploy-rollback" ;;
        stale) printf 'foreign deployment\n' > "$case_root/$RUNTIME_JAR_NAME" ;;
        record) printf '\nextra\n' >> "$case_root/.last-deploy-rollback" ;;
    esac
    local rc=0
    if [ "$entry" = manual ]; then
        (deploy_rollback) > "$case_root/output" 2>&1 || rc=$?
    else
        local auto_block
        auto_block=$(awk '/^[[:space:]]*# BEGIN_POST_SWITCH_BOUND_ROLLBACK$/ {copy=1;next} /^[[:space:]]*# END_POST_SWITCH_BOUND_ROLLBACK$/ {copy=0} copy {print}' "$DEPLOY_SCRIPT")
        [ -n "$auto_block" ] || { echo 'FAIL: automatic rollback block missing'; return 1; }
        POST_SWITCH_HEALTHY=false ACTIVE_COLOR=previous ACTIVE_PORT=$port
        IDLE_SERVICE=$BLUE_SERVICE
        [ "$port" != "$BLUE_PORT" ] || IDLE_SERVICE=$GREEN_SERVICE
        DEPLOY_ROLLBACK_BINDING=$binding
        (eval "$auto_block") > "$case_root/output" 2>&1 || rc=$?
    fi
    [ -s "$EVENTS" ] || { echo 'FAIL: no transport observation'; return 1; }
    if [ "$fault" = none ]; then
        local expected_rc=0
        [ "$entry" != automatic ] || expected_rc=1 # Original deployment still failed.
        [ "$rc" = "$expected_rc" ] || { cat "$case_root/output"; echo 'FAIL: unexpected deployment result'; return 1; }
        [ "$(file_sha256 "$case_root/$RUNTIME_JAR_NAME")" = "$old_sha" ] || { echo 'FAIL: restored an unbound backup'; return 1; }
        grep -Fxq "systemctl restart $EXPECTED_SERVICE" "$EVENTS" || return 1
        grep -Fxq "health $port" "$EVENTS" || return 1
        grep -Fq "47.100.235.168:$port;" "$NGINX_UPSTREAM_FILE" || return 1
        grep -Fq 'ROLLBACK_STATUS=restored' "$case_root/output" || return 1
        # Restart only after the verified copy; switch traffic only after health.
        local copied restarted healthy switched
        copied=$(event_line '^copy .*bak.bound') || return 1
        restarted=$(event_line '^systemctl restart ') || return 1
        healthy=$(event_line '^health ') || return 1
        switched=$(event_line '^nginx -s reload') || return 1
        [ "$copied" -lt "$restarted" ] && [ "$restarted" -lt "$healthy" ] && [ "$healthy" -lt "$switched" ] || return 1
    else
        [ "$rc" != 0 ] || { echo 'FAIL: failure returned success'; return 1; }
        ! grep -Fq 'ROLLBACK_STATUS=restored' "$case_root/output" || return 1
        case "$fault" in copy|hash|missing|stale|record)
            ! grep -q '^systemctl restart' "$EVENTS" || { echo 'FAIL: restarted without a verified copy'; return 1; } ;;
        esac
        case "$fault" in restart|health)
            ! grep -q '^nginx -s reload' "$EVENTS" || { echo 'FAIL: routed to an unhealthy rollback slot'; return 1; } ;;
        esac
    fi
    printf 'PASS: %s %s %s (rc=%s)\n' "$port" "$entry" "$fault" "$rc"
)

for port in 10010 10020; do
    for entry in manual automatic; do
        for fault in none copy restart health hash missing stale gateway record; do
            checks=$((checks + 1))
            if ! run_case "$port" "$entry" "$fault"; then
                failures=$((failures + 1))
                printf 'FAIL: case %s %s %s\n' "$port" "$entry" "$fault"
            fi
        done
    done
done

# Exercise the production preparation function, not a hand-authored manifest.
for port in 10010 10020; do
    checks=$((checks + 1))
    if ! (
        case_root="$TMP_ROOT/prepare-$port"
        mkdir -p "$case_root"
        REMOTE_JAR_DIR=$case_root NGINX_UPSTREAM_FILE="$case_root/upstream.conf"
        export EVENTS="$case_root/events" FAULT=none
        : > "$EVENTS"
        printf 'old prepare artifact\n' > "$case_root/$RUNTIME_JAR_NAME"
        printf 'candidate\n' > "$case_root/candidate"
        LOCAL_SHA256=$(file_sha256 "$case_root/candidate") || exit 1
        expected_old=$(file_sha256 "$case_root/$RUNTIME_JAR_NAME") || exit 1
        printf ' server 47.100.235.168:%s;\n' "$port" > "$NGINX_UPSTREAM_FILE"
        prepare_bound_rollback || exit 1
        [ "$ROLLBACK_BOUND_PORT" = "$port" ] || exit 1
        [ "$(sed -n '3p' "$case_root/.last-deploy-rollback")" = "$expected_old" ] || exit 1
        [ "$(sed -n '4p' "$case_root/.last-deploy-rollback")" = "$port" ] || exit 1
        [ "$(sed -n '5p' "$case_root/.last-deploy-rollback")" = "$LOCAL_SHA256" ] || exit 1
        command cp "$case_root/candidate" "$case_root/$RUNTIME_JAR_NAME"
        restore_bound_rollback disk "$DEPLOY_ROLLBACK_BINDING" || exit 1
        [ "$(file_sha256 "$case_root/$RUNTIME_JAR_NAME")" = "$expected_old" ] || exit 1
        ! grep -q '^systemctl restart' "$EVENTS" || exit 1
        # The live automatic caller must reject a record replaced by another release.
        printf '\nchanged\n' >> "$case_root/.last-deploy-rollback"
        if restore_bound_rollback disk "$DEPLOY_ROLLBACK_BINDING"; then exit 1; fi
        # Ambiguous upstream must fail before a backup/install operation.
        printf ' server 47.100.235.168:10010;\n server 47.100.235.168:10020;\n' > "$NGINX_UPSTREAM_FILE"
        if prepare_bound_rollback; then exit 1; fi
        printf 'PASS: %s prepare/disk/changed-binding/ambiguous-upstream\n' "$port"
    ); then
        failures=$((failures + 1))
        printf 'FAIL: preparation %s\n' "$port"
    fi
done
printf 'ROLLBACK_STUB_MATRIX checks=%s failures=%s\n' "$checks" "$failures"
[ "$failures" = 0 ]
