#!/usr/bin/env bash
set -euo pipefail

# The copied test doubles as gh. Every API response is local, including the rules filter input.
if [ "${0##*/}" = fake-gh ]; then
  case "$1 $2" in
    'pr view')
      if [ "$5" = headRefOid ]; then
        if [ "$SCENARIO" = changed-head ]; then printf 'new-head\n'; else printf 'test-head\n'; fi
      else
        printf 'OPEN test-head MERGEABLE main\n'
      fi
      ;;
    'api repos/{owner}/{repo}/rules/branches/main')
      [ "$3" = --paginate ] && [ "$4" = --jq ] || exit 98
      case "$SCENARIO" in
        api-error) printf 'gh: HTTP 503\n' >&2; exit 1;;
        invalid-rules) jq -br "$5" <<<'[{"type":"required_status_checks","parameters":{}}]';;
        *)
          # Simulate gh applying --jq separately to each page. One rule is on page two.
          jq -br "$5" <<<'[{"type":"merge_queue"},{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"fast-contracts"}]}}]'
          jq -br "$5" <<<'[{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"postgres-reversal-concurrency"}]}}]'
          ;;
      esac
      ;;
    'pr checks')
      if [ "$SCENARIO" = no-checks ]; then
        printf "no checks reported on the 'test-branch' branch\n" >&2
        exit 1
      fi
      if [ "$SCENARIO" = all-skipped ]; then
        printf 'skipping\tfast-contracts\nskipping\tpostgres-reversal-concurrency\n'
      else
        printf 'pass\tfast-contracts\n'
        case "$SCENARIO" in
          missing) printf 'pass\tpostgres-reversal-concurrency-extra\n';;
          pending) printf 'pending\tpostgres-reversal-concurrency\n'; exit 8;;
          *) printf 'skipping\tpostgres-reversal-concurrency\n';;
        esac
      fi
      ;;
    'api graphql')
      [ "$3" = -F ] || exit 98
      printf 'true OPEN false\n'
      ;;
    *) printf 'Unexpected fake gh command: %s\n' "$*" >&2; exit 98;;
  esac
  exit
fi

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
watch="$root/.claude/tools/pr-watch-merge.sh"
tmp=$(mktemp -d)
trap 'rm -f -- "$tmp/fake-gh" "$tmp/run.log" "$tmp/missing-guard-disabled.sh"; rmdir -- "$tmp"' EXIT
cp -- "$0" "$tmp/fake-gh"
chmod +x "$tmp/fake-gh"

pass=0
assert_case() {
  local scenario=$1 expected_rc=$2 result=$3 diagnostic=$4 script=${5:-$watch} rc=0
  SCENARIO="$scenario" PR_WATCH_GH="$tmp/fake-gh" PR_WATCH_INTERVAL=0.2 \
    bash "$script" 42 --no-merge --timeout-sec 3 >"$tmp/run.log" 2>&1 || rc=$?
  if [ "$rc" -ne "$expected_rc" ] || ! grep -Fq "RESULT=$result pr=42 " "$tmp/run.log" ||
     ! grep -Fq "$diagnostic" "$tmp/run.log" ||
     { [ "$expected_rc" -ne 0 ] && grep -q '^RESULT=GREEN ' "$tmp/run.log"; }; then
    printf 'FAIL %s: expected rc=%s and RESULT=%s, got rc=%s\n' "$scenario" "$expected_rc" "$result" "$rc"
    cat "$tmp/run.log"
    return 1
  fi
  printf 'ok - %s: rc=%s RESULT=%s (%s)\n' "$scenario" "$rc" "$result" "$diagnostic"
  pass=$((pass+1))
}

assert_case pass-and-skipping 0 GREEN queue=on
assert_case all-skipped 0 GREEN queue=on
assert_case missing 2 UNMEASURED missing=postgres-reversal-concurrency
assert_case no-checks 2 UNMEASURED missing=fast-contracts,postgres-reversal-concurrency
assert_case api-error 2 UNMEASURED 'gh: HTTP 503'
assert_case invalid-rules 2 UNMEASURED 'invalid required checks list'
assert_case pending 2 UNMEASURED 'pending=1 pass=1'
assert_case changed-head 2 UNMEASURED 'unable to confirm checks head: new-head'

# Disable the missing-context gate in a temporary copy and run the same assertion.
sed '/^  if \[ -n "\$missing" \]; then say /s/^/# /' "$watch" >"$tmp/missing-guard-disabled.sh"
if assert_case missing 2 UNMEASURED missing=postgres-reversal-concurrency "$tmp/missing-guard-disabled.sh"; then
  printf 'FAIL: missing-context assertion survived disabling the gate\n'
  exit 1
fi
grep -q '^RESULT=GREEN pr=42 ' "$tmp/run.log"
pass=$((pass+1))
printf 'READING: missing_guard_mutation=assertion_failed_on_false_GREEN\n'

printf 'PASS: %s cases\n' "$pass"
