#!/usr/bin/env bash
# GUARDS=scripts/deploy/release-java-preflight.sh,scripts/tests/lib/release-jdk-probe.sh
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/jdk/bin" "$fixture/backend/java/cretas-api/src/test/java"
printf '%s\n' 'class ValidTest {}' > "$fixture/backend/java/cretas-api/src/test/java/ValidTest.java"
printf '%s\n' '#!/usr/bin/env bash' \
    'echo "Picked up JAVA_TOOL_OPTIONS: -XX:ActiveProcessorCount=6" >&2' \
    'printf "openjdk version \"%s.0.1\"\n" "$FAKE_JAVA_MAJOR" >&2' > "$fixture/jdk/bin/java"
chmod +x "$fixture/jdk/bin/java"
source "$root/scripts/tests/lib/release-jdk-probe.sh"
for major in 17 21 25; do
    export FAKE_JAVA_MAJOR="$major"
    parsed=$(_rjp_major "$fixture/jdk/bin/java")
    [ "$parsed" = "$major" ] || { echo "FAIL: helper parsed $parsed instead of $major"; exit 1; }
    rc=0
    JAVA_HOME="$fixture/jdk" bash "$root/scripts/deploy/release-java-preflight.sh" \
        --repo-root "$fixture" --tests ValidTest > "$fixture/result.log" 2>&1 || rc=$?
    if [ "$major" -lt 21 ]; then
        [ "$rc" -ne 0 ] && grep -Fq 'requires JDK 21 or newer, found 17' "$fixture/result.log"
    else
        [ "$rc" -eq 0 ] || { cat "$fixture/result.log"; exit 1; }
    fi
done
echo 'PASS: JVM preambles preserve version parsing and reject JDK 17 while accepting 21/25'
