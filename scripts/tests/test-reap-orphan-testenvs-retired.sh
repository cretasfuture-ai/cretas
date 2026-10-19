#!/usr/bin/env bash
# GUARDS=scripts/ci/reap-orphan-testenvs.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
for name in ssh ps psql kill pkill; do
  printf '#!/bin/sh\nprintf "called\n" >> "$SENTINEL"\nexit 99\n' > "$tmp/bin/$name"
  chmod +x "$tmp/bin/$name"
done
export PATH="$tmp/bin:$PATH" SENTINEL="$tmp/called"
for SSH_TARGET in local canada-mini arbitrary-host; do
  export SSH_TARGET
  for option in --help -h --status; do
    output=$(bash "$root/scripts/ci/reap-orphan-testenvs.sh" "$option")
    [[ "$output" == *REAP_STATUS=retired* && "$output" != *ORPHANS=0* ]]
  done
  for option in '' --kill '--min-age-hours 6' --unknown '--status --kill' '--help extra'; do
    set +e
    # Intentional splitting exercises the legacy argument matrix (all values are fixed test literals).
    bash "$root/scripts/ci/reap-orphan-testenvs.sh" $option > "$tmp/output" 2>&1
    rc=$?
    set -e
    [[ $rc -eq 2 ]]
    [[ ! -e "$SENTINEL" ]]
  done
done
echo 'retired reaper argument/sentinel checks passed'
