#!/usr/bin/env bash
# Registration token comes only from stdin, is never echoed or written to a file.
# Custom label is ONLY `cretas-quick` (plus the runner defaults self-hosted/Linux/X64, which every
# workflow route combines with `cretas` or `java`): no `cretas` (that would pull vitest/pytest into
# 1 GiB) and no `java`.
set -euo pipefail
[[ $(id -u) == 0 ]] || exit 2
[[ ! -e /opt/cretas-ci-quick/.runner ]] || { echo 'Already registered; refuse replacement'; exit 1; }
! systemctl is-active --quiet cretas-ci-quick.service || { echo 'Active listener; refuse registration'; exit 1; }
IFS= read -r registration_token
registration_token=${registration_token%$'\r'}
[[ -n "$registration_token" && ${#registration_token} -lt 512 ]] || exit 2
cd /opt/cretas-ci-quick
runuser -u cretas-ci-quick -- env HOME=/var/lib/cretas-ci-quick \
  PATH=/opt/cretas-ci-quick-tools/bin:/usr/local/bin:/usr/bin:/bin \
  RUNNER_TOOL_CACHE=/var/lib/cretas-ci-quick/toolcache \
  ./config.sh --unattended --url https://github.com/cretasfuture-ai/cretas \
  --token "$registration_token" --name windows-quick-cretas \
  --labels cretas-quick --work /var/lib/cretas-ci-quick/work
unset registration_token
echo 'QUICK_REGISTER=done NOT_STARTED'
