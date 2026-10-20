#!/usr/bin/env bash
# Runner contract and isolation guard for the Aurora-only Fresh DB workflow.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PREFLIGHT="$ROOT_DIR/scripts/ci/fresh-db-runner-preflight.sh"
WORKFLOW="$ROOT_DIR/.github/workflows/fresh-db-progress.yml"
# GUARDS=scripts/ci/fresh-db-runner-preflight.sh,.github/workflows/fresh-db-progress.yml
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

EVIDENCE_DIR="${FRESH_DB_LOCAL_EVIDENCE_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/fresh-db-local-runner-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}}"
mkdir -p "$EVIDENCE_DIR"
LOG="$EVIDENCE_DIR/fresh-db-local-runner.log"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

[ -f "$PREFLIGHT" ] || fail "missing preflight: $PREFLIGHT"
[ -f "$WORKFLOW" ] || fail "missing Fresh DB workflow: $WORKFLOW"
bash -n "$PREFLIGHT" || fail "preflight shell syntax is invalid"
grep -qF 'runs-on: [self-hosted, Linux, ARM64, java, cretas, aurora]' "$WORKFLOW" \
    || fail 'Fresh DB workflow is not pinned to the verified Aurora labels'
grep -qF 'timeout-minutes: 30' "$WORKFLOW" \
    || fail 'Fresh DB workflow timeout changed from 30 minutes'
grep -qF -- "- 'scripts/ci/fresh-db-runner-preflight.sh'" "$WORKFLOW" \
    || fail 'preflight is missing from the Fresh DB trigger paths'
grep -qF -- "- 'scripts/tests/test-fresh-db-local-runner.sh'" "$WORKFLOW" \
    || fail 'local runner helper is missing from the Fresh DB trigger paths'

# Execute the workflow's own candidate selector snippet. A malformed SHA must
# fail before the conditional candidate checkout can run.
checkout_count="$(grep -cF 'uses: actions/checkout@v4' "$WORKFLOW")"
[ "$checkout_count" -ge 2 ] || fail 'workflow does not have separate workflow/candidate checkouts'
grep -qF 'backend_java_cretas_api_tree=' "$WORKFLOW" \
    || fail 'workflow provenance does not record the backend tree'
grep -qF 'candidate_actual=$(git -C "$candidate_dir" rev-parse HEAD)' "$WORKFLOW" \
    || fail 'workflow does not verify candidate checkout HEAD'
resolve_snippet="$(awk '
    /FRESH_DB_RESOLVE_BEGIN/ { capture=1; next }
    /FRESH_DB_RESOLVE_END/ { exit }
    capture { sub(/^          /, ""); print }
' "$WORKFLOW")"
[ -n "$resolve_snippet" ] || fail 'candidate resolver snippet was not found'
resolve_script="$TMP/candidate-resolve.sh"
{
    echo '#!/usr/bin/env bash'
    echo 'set -euo pipefail'
    echo 'fail() { echo "FAIL: $*" >&2; exit 1; }'
    echo 'workflow_source_sha=1111111111111111111111111111111111111111'
    echo 'candidate_path=candidate-source-fixture'
    echo 'candidate_requested=false'
    printf '%s\n' "$resolve_snippet"
} > "$resolve_script"
chmod +x "$resolve_script"
set +e
bad_candidate_output="$(CANDIDATE_SHA=bad-ref GITHUB_RUN_ID=424242 GITHUB_RUN_ATTEMPT=11 GITHUB_OUTPUT="$TMP/bad-candidate-output" bash "$resolve_script" 2>&1)"
bad_candidate_rc=$?
set -e
[ "$bad_candidate_rc" -ne 0 ] || fail 'malformed candidate SHA was accepted before checkout'
[[ "$bad_candidate_output" == *'candidate_sha must be exactly 40 hexadecimal characters'* ]] \
    || fail "malformed candidate SHA failed without exact validation reason: $bad_candidate_output"
valid_candidate_output="$TMP/valid-candidate-output"
valid_resolve_output="$(CANDIDATE_SHA=f6a675ec4511ae5f885225d266fd472e55b40695 GITHUB_RUN_ID=424242 GITHUB_RUN_ATTEMPT=12 GITHUB_OUTPUT="$valid_candidate_output" bash "$resolve_script")" \
    || fail 'valid candidate SHA was rejected by the resolver'
grep -qx 'candidate_requested=true' "$valid_candidate_output" \
    || fail 'valid candidate resolver did not emit candidate_requested=true'
grep -qx 'candidate_source_sha=f6a675ec4511ae5f885225d266fd472e55b40695' "$valid_candidate_output" \
    || fail 'valid candidate resolver did not preserve the exact candidate SHA'
ok 'workflow candidate resolver rejects malformed SHA before checkout and accepts exact 40-hex input'

# Execute the workflow's own readback snippet with a temporary Git candidate
# whose HEAD differs from the requested SHA. The gate directory must not be
# emitted until the actual candidate checkout HEAD has matched.
readback_snippet="$(awk '
    /FRESH_DB_READBACK_BEGIN/ { capture=1; next }
    /FRESH_DB_READBACK_END/ { exit }
    capture { sub(/^          /, ""); print }
' "$WORKFLOW")"
[ -n "$readback_snippet" ] || fail 'candidate readback snippet was not found'
readback_script="$TMP/source-readback.sh"
{
    echo '#!/usr/bin/env bash'
    echo 'set -euo pipefail'
    echo 'fail() { echo "FAIL: $*" >&2; exit 1; }'
    printf '%s\n' "$readback_snippet"
} > "$readback_script"
chmod +x "$readback_script"
fixture_repo="$TMP/source-repo"
mkdir -p "$fixture_repo/workflow-source/backend/java/cretas-api" "$fixture_repo/candidate-source-424242-13/backend/java/cretas-api" "$fixture_repo/runner"
git -C "$fixture_repo/workflow-source" init -q
git -C "$fixture_repo/workflow-source" config user.email fixture@example.invalid
git -C "$fixture_repo/workflow-source" config user.name fixture
touch "$fixture_repo/workflow-source/backend/java/cretas-api/pom.xml"
git -C "$fixture_repo/workflow-source" add .
git -C "$fixture_repo/workflow-source" commit -qm fixture
workflow_fixture_sha="$(git -C "$fixture_repo/workflow-source" rev-parse HEAD)"
git -C "$fixture_repo/candidate-source-424242-13" init -q
git -C "$fixture_repo/candidate-source-424242-13" config user.email fixture@example.invalid
git -C "$fixture_repo/candidate-source-424242-13" config user.name fixture
touch "$fixture_repo/candidate-source-424242-13/backend/java/cretas-api/pom.xml"
git -C "$fixture_repo/candidate-source-424242-13" add .
git -C "$fixture_repo/candidate-source-424242-13" commit -qm fixture
set +e
mismatch_output="$(GITHUB_WORKSPACE="$fixture_repo" RUNNER_TEMP="$fixture_repo/runner" GITHUB_STEP_SUMMARY="$TMP/readback-summary" GITHUB_OUTPUT="$TMP/readback-output" WORKFLOW_SOURCE_SHA="$workflow_fixture_sha" CANDIDATE_SHA=0000000000000000000000000000000000000000 CANDIDATE_REQUESTED=true CANDIDATE_PATH=candidate-source-424242-13 bash "$readback_script" 2>&1)"
mismatch_rc=$?
set -e
[ "$mismatch_rc" -ne 0 ] || fail 'candidate HEAD mismatch fixture was accepted'
[[ "$mismatch_output" == *'candidate checkout HEAD'* ]] || fail "candidate HEAD mismatch lacked actionable reason: $mismatch_output"
if [ -s "$TMP/readback-output" ] && grep -q '^gate_dir=' "$TMP/readback-output"; then
    fail 'gate_dir was emitted before candidate HEAD verification'
fi
ok 'candidate readback rejects a mismatched actual HEAD before exposing gate_dir'

# Execute the real preflight against command fixtures that model Aurora.
FIXTURE_BIN="$TMP/fixture-bin"
REAL_PATH="$PATH"
mkdir -p "$FIXTURE_BIN"
REAL_AWK="$(command -v awk || true)"
[ -n "$REAL_AWK" ] || fail "awk is required to build the runner fixture"

fixture_script() {
    local name="$1"
    shift
    printf '%s\n' '#!/usr/bin/env bash' "$@" > "$FIXTURE_BIN/$name"
    chmod +x "$FIXTURE_BIN/$name"
}

fixture_script uname \
    'case "${1:-}" in' \
    '  -s) echo Linux ;;' \
    '  -m) echo aarch64 ;;' \
    '  *) exit 1 ;;' \
    'esac'
fixture_script nproc 'echo 4'
fixture_script df \
    "printf '%s\\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on' 'fixture 100000000 11400000 88600000 12% /'"
printf '%s\n' '#!/usr/bin/env bash' \
    'if [ "${2:-}" = /proc/meminfo ]; then' \
    '  echo 7467008' \
    'else' \
    "  exec \"$REAL_AWK\" \"\$@\"" \
    'fi' > "$FIXTURE_BIN/awk"
chmod +x "$FIXTURE_BIN/awk"
fixture_script docker \
    'case "${1:-} ${2:-}" in' \
    "  '--version ') echo 'Docker version 29.5.2, build fixture' ;;" \
    "  'context show') echo default ;;" \
    "  'info --format') printf '%s\\n' \"\${FIXTURE_DOCKER_NAME:-colima-ci}|linux|aarch64|8307175424\" ;;" \
    "  'compose version') echo 'Docker Compose version v5.1.4' ;;" \
    '  *) exit 0 ;;' \
    'esac'
fixture_script java 'echo '\''openjdk version "21.0.8" fixture'\'' >&2'
fixture_script javac 'echo '\''javac 21.0.8'\'''
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'case "${FIXTURE_MVN_MODE:-positive}" in' \
    '  positive) printf "%s\\n" "Picked up JAVA_TOOL_OPTIONS: -Xmx3g" "Apache Maven 3.9.9 (fixture)" "Java version: 21.0.8, vendor: Eclipse Adoptium" ;;' \
    '  missing) printf "%s\\n" "Picked up JAVA_TOOL_OPTIONS: -Xmx3g" "Maven launcher output without a banner" ;;' \
    '  old) printf "%s\\n" "Picked up JAVA_TOOL_OPTIONS: -Xmx3g" "Apache Maven 2.9.9 (fixture)" ;;' \
    '  unreadable) printf "%s\\n" "Picked up JAVA_TOOL_OPTIONS: -Xmx3g" "Apache Maven unknown (fixture)" ;;' \
    '  *) exit 1 ;;' \
    'esac' > "$FIXTURE_BIN/mvn"
chmod +x "$FIXTURE_BIN/mvn"
fixture_script ss \
    'if [ "${FIXTURE_SS_FAILURE:-0}" = 1 ]; then' \
    '  echo fixture-ss-failure >&2' \
    '  exit 2' \
    'fi' \
    'exit 0'

run_fixture() (
    local attempt="$1"
    local mode="${2:-positive}"
    local env_file="$TMP/env-$attempt"
    mkdir -p "$TMP/runner-$attempt"
    export PATH="$FIXTURE_BIN:$REAL_PATH"
    export RUNNER_OS=Linux RUNNER_ARCH=ARM64 RUNNER_NAME=aurora-cretas
    export GITHUB_RUN_ID=424242 GITHUB_RUN_ATTEMPT="$attempt"
    export RUNNER_TEMP="$TMP/runner-$attempt" GITHUB_ENV="$env_file" GITHUB_OUTPUT="$TMP/output-$attempt"
    if [ "$mode" = route-negative ]; then
        export RUNNER_OS=Windows
    elif [ "$mode" = daemon-negative ]; then
        export FIXTURE_DOCKER_NAME=wrong-daemon
    elif [ "$mode" = ports-negative ]; then
        export FIXTURE_SS_FAILURE=1
    elif [ "$mode" = maven-missing-negative ]; then
        export FIXTURE_MVN_MODE=missing
    elif [ "$mode" = maven-old-negative ]; then
        export FIXTURE_MVN_MODE=old
    elif [ "$mode" = maven-unreadable-negative ]; then
        export FIXTURE_MVN_MODE=unreadable
    fi
    bash "$PREFLIGHT"
)

positive_output="$(run_fixture 7)" || fail "Aurora positive preflight fixture failed: $positive_output"
positive_env="$TMP/env-7"
[ -s "$positive_env" ] || fail 'positive preflight did not emit GITHUB_ENV values'
grep -qx 'FRESH_DB_PROJECT=cretas-fresh-db-424242-7' "$positive_env" \
    || fail 'positive fixture did not create the run-scoped Compose project'
db_port="$(sed -n 's/^FRESH_DB_PORT=//p' "$positive_env")"
app_port="$(sed -n 's/^FRESH_DB_APP_PORT=//p' "$positive_env")"
output_path="$(sed -n 's/^FRESH_DB_OUTPUT=//p' "$positive_env")"
[[ "$db_port" =~ ^[0-9]+$ && "$app_port" =~ ^[0-9]+$ ]] || fail 'positive fixture did not emit numeric ports'
[ "$app_port" -eq $((db_port + 1)) ] || fail 'Fresh DB ports are not a unique adjacent pair'
[[ "$output_path" == "$TMP/runner-7/cretas-fresh-db/cretas-fresh-db-424242-7/progress.log" ]] \
    || fail "Fresh DB output is not run-scoped: $output_path"
[[ "$positive_output" == *'Apache Maven 3.9.9 (fixture)'* ]] \
    || fail "positive Maven fixture did not parse the anchored banner after noise: $positive_output"
ok 'positive Aurora route fixture passes and emits unique project/ports/output'

set +e
route_output="$(run_fixture 8 route-negative 2>&1)"
route_rc=$?
daemon_output="$(run_fixture 9 daemon-negative 2>&1)"
daemon_rc=$?
ports_output="$(run_fixture 10 ports-negative 2>&1)"
ports_rc=$?
maven_missing_output="$(run_fixture 11 maven-missing-negative 2>&1)"
maven_missing_rc=$?
maven_old_output="$(run_fixture 12 maven-old-negative 2>&1)"
maven_old_rc=$?
maven_unreadable_output="$(run_fixture 13 maven-unreadable-negative 2>&1)"
maven_unreadable_rc=$?
set -e
[ "$route_rc" -ne 0 ] || fail 'wrong OS fixture was accepted'
[[ "$route_output" == *'RUNNER_OS must be Linux'* ]] || fail "wrong OS fixture failed without route reason: $route_output"
[ "$daemon_rc" -ne 0 ] || fail 'wrong Docker daemon fixture was accepted'
[[ "$daemon_output" == *'Docker daemon must be colima-ci'* ]] || fail "wrong daemon fixture failed without daemon reason: $daemon_output"
[ "$ports_rc" -ne 0 ] || fail 'ss failure fixture was accepted as an available port'
[[ "$ports_output" == *'cannot verify port'* ]] || fail "ss failure fixture failed without port reason: $ports_output"
[ "$maven_missing_rc" -ne 0 ] || fail 'missing Maven banner fixture was accepted'
[[ "$maven_missing_output" == *'anchored Apache Maven banner'* ]] \
    || fail "missing Maven banner fixture failed without banner reason: $maven_missing_output"
[ "$maven_old_rc" -ne 0 ] || fail 'old Maven fixture was accepted'
[[ "$maven_old_output" == *'Maven major version 2 is below required 3'* ]] \
    || fail "old Maven fixture failed without version reason: $maven_old_output"
[ "$maven_unreadable_rc" -ne 0 ] || fail 'unreadable Maven fixture was accepted'
[[ "$maven_unreadable_output" == *'anchored Apache Maven banner'* ]] \
    || fail "unreadable Maven fixture failed without banner reason: $maven_unreadable_output"
ok 'negative route, daemon, port, and Maven banner fixtures fail closed with actionable reasons'
echo "PASS: Aurora Fresh DB local runner contract"
echo "Evidence: $LOG"
