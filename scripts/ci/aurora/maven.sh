#!/bin/bash
set -eu
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xmx3g}"
# Bound lock wait and the entire pipeline, including all Maven descendants.
if [[ "${1:-}" != --cretas-inside-lock ]]; then
  mkdir -p "$HOME/.cache/cretas-ci"
  exec flock --wait 300 --conflict-exit-code 75 --close "$HOME/.cache/cretas-ci/maven.lock" \
    timeout --signal=TERM --kill-after=30s 90m "$0" --cretas-inside-lock "$@"
fi
shift
# Keep selectors and explicit workflow options unchanged; retain all output.
set +e
/usr/local/bin/mvn -DreuseForks=false -Dmaven.compiler.fork=true -Dmaven.compiler.meminitial=256m -Dmaven.compiler.maxmem=2g "$@" 2>&1 \
  | /usr/bin/python3 "$(dirname "$0")/maven_output_guard.py"
statuses=("${PIPESTATUS[@]}")
if (( statuses[0] != 0 )); then exit "${statuses[0]}"; fi
exit "${statuses[1]}"
