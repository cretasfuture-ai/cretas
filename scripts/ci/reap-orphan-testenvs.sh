#!/usr/bin/env bash
# Retired Canada macOS orphan reaper. Linux CI has a separate lifecycle.
# Historical implementation remains in Git; this entrypoint never inspects or kills processes.
set -euo pipefail
if [[ $# -eq 1 && ( "$1" == --help || "$1" == -h || "$1" == --status ) ]]; then
  echo 'REAP_STATUS=retired'
  echo 'Canada macOS reaper is retired; no orphan process inspection performed.'
  echo 'No automatic Linux cleanup is enabled by this entrypoint.'
  exit 0
fi
echo 'REAP_STATUS=retired; legacy invocation refused without connecting or changing processes.' >&2
echo 'usage: reap-orphan-testenvs.sh --status | --help' >&2
exit 2
