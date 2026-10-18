#!/bin/sh
set -eu
# Bound the parent Maven heap; its test fork defaults to 3 GiB instead of
# 1/4 of this smaller VM. Explicit workflow -Xmx options still take priority.
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xmx3g}"
# Serialize Maven across both repositories. Bound lock wait (exit 75) and the
# full lifecycle (90 min, exit 124), leaving report/ratchet time in the 120-min job.
mkdir -p "$HOME/.cache/cretas-ci"
exec flock --wait 300 --conflict-exit-code 75 --close "$HOME/.cache/cretas-ci/maven.lock" timeout --signal=TERM --kill-after=30s 90m /usr/local/bin/mvn -DreuseForks=false -Dmaven.compiler.fork=true -Dmaven.compiler.meminitial=256m -Dmaven.compiler.maxmem=2g "$@"
