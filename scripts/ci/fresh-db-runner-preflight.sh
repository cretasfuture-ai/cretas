#!/usr/bin/env bash
# Fail-closed preflight for the Aurora-only Fresh DB progress job.
#
# This script deliberately owns only the runner contract and run-scoped
# isolation.  The Fresh DB gate remains the owner of PostgreSQL, Flyway,
# Spring, shape and exit-code semantics.
set -euo pipefail

fail() {
    printf '[fresh-db-preflight] ERROR: %s\n' "$*" >&2
    exit 1
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER_TEMP_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

version_major() {
    printf '%s\n' "$1" | sed -nE 's/[^0-9]*([0-9]+)(\..*)?/\1/p' | head -1
}

require_major_at_least() {
    local label="$1" raw="$2" minimum="$3" major
    major="$(version_major "$raw")"
    [[ "$major" =~ ^[0-9]+$ ]] || fail "could not read $label version from: $raw"
    [ "$major" -ge "$minimum" ] || fail "$label major version $major is below required $minimum"
}

maven_major_from_banner() {
    awk '
        /^Apache Maven / {
            seen=1
            if ($0 !~ /^Apache Maven [0-9]+(\.[0-9]+)*([[:space:]]|$)/) {
                bad=1
                next
            }
            split($3, parts, ".")
            if (parts[1] !~ /^[0-9]+$/) {
                bad=1
                next
            }
            major=parts[1]
        }
        END {
            if (!seen || bad || major == "") exit 1
            print major
        }
    '
}

require_maven_major_at_least() {
    local raw="$1" minimum="$2" major
    major="$(printf '%s\n' "$raw" | maven_major_from_banner)" \
        || fail "could not read Maven version from an anchored Apache Maven banner"
    [ "$major" -ge "$minimum" ] || fail "Maven major version $major is below required $minimum"
}

require_route() {
    [ "$(uname -s)" = Linux ] || fail "runner OS must be Linux"
    [ "$(uname -m)" = aarch64 ] || fail "runner machine must be aarch64/ARM64"
    [ "${RUNNER_OS:-}" = Linux ] || fail "RUNNER_OS must be Linux"
    [ "${RUNNER_ARCH:-}" = ARM64 ] || fail "RUNNER_ARCH must be ARM64"
    case "${RUNNER_NAME:-}" in
        aurora-cretas) ;;
        *) fail "runner must be aurora-cretas, got ${RUNNER_NAME:-<unset>}" ;;
    esac
}

require_resources() {
    local cpu mem_kib disk_kib min_mem_kib min_disk_kib
    require_command nproc
    require_command df
    require_command awk
    cpu="$(nproc)"
    [[ "$cpu" =~ ^[0-9]+$ ]] && [ "$cpu" -ge 4 ] \
        || fail "at least 4 CPUs are required, got ${cpu:-<unknown>}"

    mem_kib="$(awk '/^MemAvailable:[[:space:]]+[0-9]+/ {print $2; exit}' /proc/meminfo)"
    [[ "$mem_kib" =~ ^[0-9]+$ ]] || fail "MemAvailable could not be read from /proc/meminfo"
    min_mem_kib=$((5 * 1024 * 1024))
    [ "$mem_kib" -ge "$min_mem_kib" ] \
        || fail "MemAvailable ${mem_kib} KiB is below ${min_mem_kib} KiB (5 GiB)"

    disk_kib="$(df -Pk "$ROOT_DIR" | awk 'NR == 2 {print $4; exit}')"
    [[ "$disk_kib" =~ ^[0-9]+$ ]] || fail "free disk could not be read for $ROOT_DIR"
    min_disk_kib=$((10 * 1024 * 1024))
    [ "$disk_kib" -ge "$min_disk_kib" ] \
        || fail "free disk ${disk_kib} KiB is below ${min_disk_kib} KiB (10 GiB)"
    printf '[fresh-db-preflight] resources: cpu=%s mem_available_kib=%s disk_available_kib=%s\n' \
        "$cpu" "$mem_kib" "$disk_kib"
}

require_docker() {
    local docker_version compose_version context daemon_info daemon_name daemon_os daemon_arch daemon_mem
    require_command docker
    docker_version="$(docker --version 2>&1)" || fail "Docker CLI is unavailable"
    require_major_at_least Docker "$docker_version" 29
    context="$(docker context show 2>&1)" || fail "Docker context could not be read"
    [ "$context" = default ] || fail "Docker context must be default, got $context"
    daemon_info="$(docker info --format '{{.Name}}|{{.OSType}}|{{.Architecture}}|{{.MemTotal}}' 2>&1)" \
        || fail "Docker daemon is unavailable: $daemon_info"
    IFS='|' read -r daemon_name daemon_os daemon_arch daemon_mem <<<"$daemon_info"
    [ "$daemon_name" = colima-ci ] || fail "Docker daemon must be colima-ci, got ${daemon_name:-<empty>}"
    [ "$daemon_os" = linux ] || fail "Docker daemon OS must be linux, got ${daemon_os:-<empty>}"
    [ "$daemon_arch" = aarch64 ] || fail "Docker daemon architecture must be aarch64, got ${daemon_arch:-<empty>}"
    [[ "$daemon_mem" =~ ^[0-9]+$ ]] || fail "Docker daemon memory is unreadable: ${daemon_mem:-<empty>}"
    [ "$daemon_mem" -ge $((7 * 1024 * 1024 * 1024)) ] \
        || fail "Docker daemon memory ${daemon_mem} is below 7 GiB"
    compose_version="$(docker compose version 2>&1)" || fail "Docker Compose v2 is unavailable"
    require_major_at_least 'Docker Compose' "$compose_version" 2
    printf '[fresh-db-preflight] docker: version=%s compose=%s context=%s daemon=%s/%s/%s\n' \
        "$docker_version" "$compose_version" "$context" "$daemon_name" "$daemon_os" "$daemon_arch"
}

require_java_maven() {
    local java_version javac_version maven_version java_major maven_banner
    require_command java
    require_command javac
    require_command mvn
    java_version="$(java -version 2>&1 | head -1)" || fail "JDK is unavailable"
    java_major="$(version_major "$java_version")"
    [[ "$java_major" =~ ^[0-9]+$ ]] && [ "$java_major" -ge 21 ] \
        || fail "JDK 21+ is required, got ${java_version:-<unknown>}"
    javac_version="$(javac -version 2>&1 | head -1)" || fail "javac is unavailable"
    maven_version="$(mvn -version 2>&1)" || fail "Maven is unavailable"
    require_maven_major_at_least "$maven_version" 3
    maven_banner="$(printf '%s\n' "$maven_version" | awk '/^Apache Maven / {print; exit}')"
    printf '[fresh-db-preflight] toolchain: %s; %s; %s\n' \
        "$java_version" "$javac_version" "$maven_banner"
}

port_is_free() {
    local port="$1" listeners="" rc=0
    require_command ss
    listeners="$(ss -H -ltn "sport = :$port" 2>&1)" || rc=$?
    [ "$rc" -eq 0 ] || fail "cannot verify port $port availability (ss rc=$rc): $listeners"
    [ -z "$listeners" ]
}

choose_ports() {
    local seed base offset db app
    if [[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ ]]; then
        seed=$((10#${GITHUB_RUN_ID} + 10#${GITHUB_RUN_ATTEMPT:-1}))
    else
        seed=$$
    fi
    base=$((55432 + (seed % 400)))
    for offset in $(seq 0 99); do
        db=$((base + offset * 2))
        app=$((db + 1))
        if port_is_free "$db" && port_is_free "$app"; then
            FRESH_DB_PORT="$db"
            FRESH_DB_APP_PORT="$app"
            return 0
        fi
    done
    fail 'could not find two free ports in the isolated Fresh DB range'
}

emit_env() {
    local line
    for line in \
        "FRESH_DB_PROJECT=$FRESH_DB_PROJECT" \
        "FRESH_DB_PORT=$FRESH_DB_PORT" \
        "FRESH_DB_APP_PORT=$FRESH_DB_APP_PORT" \
        "FRESH_DB_OUTPUT=$FRESH_DB_OUTPUT" \
        "FRESH_DB_KEEP=0"; do
        printf '%s\n' "$line"
        if [ -n "${GITHUB_ENV:-}" ]; then
            printf '%s\n' "$line" >> "$GITHUB_ENV"
        fi
    done
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        printf 'project=%s\nport=%s\napp_port=%s\noutput=%s\n' \
            "$FRESH_DB_PROJECT" "$FRESH_DB_PORT" "$FRESH_DB_APP_PORT" "$FRESH_DB_OUTPUT" >> "$GITHUB_OUTPUT"
    fi
}

require_route
require_resources
require_docker
require_java_maven

run_token="${GITHUB_RUN_ID:-local-$$}-${GITHUB_RUN_ATTEMPT:-1}"
run_token="$(printf '%s' "$run_token" | tr '[:upper:]_' '[:lower:]-' | sed -E 's/[^a-z0-9-]+/-/g; s/^-+//; s/-+$//')"
[ -n "$run_token" ] || fail 'run token is empty'
FRESH_DB_PROJECT="cretas-fresh-db-${run_token}"
[[ "$FRESH_DB_PROJECT" =~ ^cretas-fresh-db-[a-z0-9-]+$ ]] || fail "unsafe generated Compose project: $FRESH_DB_PROJECT"
choose_ports

FRESH_DB_OUTPUT_DIR="$RUNNER_TEMP_DIR/cretas-fresh-db/$FRESH_DB_PROJECT"
mkdir -p "$FRESH_DB_OUTPUT_DIR"
FRESH_DB_OUTPUT="$FRESH_DB_OUTPUT_DIR/progress.log"
printf '[fresh-db-preflight] isolated project=%s db_port=%s app_port=%s output=%s\n' \
    "$FRESH_DB_PROJECT" "$FRESH_DB_PORT" "$FRESH_DB_APP_PORT" "$FRESH_DB_OUTPUT"
emit_env
