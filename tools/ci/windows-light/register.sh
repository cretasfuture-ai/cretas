#!/usr/bin/env bash
# Registration token comes only from stdin, is never echoed or written to a file.
set -euo pipefail
[[ $(id -u) == 0 ]] || exit 2
[[ ! -e /opt/cretas-ci-light/.runner ]] || { echo 'Already registered; refuse replacement'; exit 1; }
IFS= read -r registration_token
registration_token=${registration_token%$'\r'}
[[ -n "$registration_token" && ${#registration_token} -lt 512 ]] || exit 2
cd /opt/cretas-ci-light
runuser -u cretas-ci-light -- env HOME=/var/lib/cretas-ci-light \
  PATH=/opt/cretas-ci-light-tools/node/bin:/opt/cretas-ci-light-tools/bin:/usr/lib/postgresql/17/bin:/usr/local/bin:/usr/bin:/bin \
  RUNNER_TOOL_CACHE=/var/lib/cretas-ci-light/toolcache \
  ./config.sh --unattended --url https://github.com/cretasfuture-ai/cretas \
  --token "$registration_token" --name windows-light-cretas \
  --labels cretas,windows-light --work /var/lib/cretas-ci-light/work
unset registration_token
echo 'LIGHT_REGISTER=done NOT_STARTED'
