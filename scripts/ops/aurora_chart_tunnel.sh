#!/usr/bin/env bash
set -euo pipefail
[[ "$(hostname)" == Auroras-Mac-mini.local && "$(whoami)" == aurora && "$HOME" == /Users/aurora ]] || exit 78

config_dir="$HOME/.config/cretas/aurora-chart"
/usr/bin/curl --noproxy '*' --fail --silent --show-error --max-time 3 http://127.0.0.1:11501/health >/dev/null
exec /usr/bin/ssh -F /dev/null -NT \
  -i "$config_dir/tunnel_ed25519" \
  -o IdentitiesOnly=yes -o IdentityAgent=none -o BatchMode=yes \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$config_dir/known_hosts" \
  -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
  -R 127.0.0.1:11440:127.0.0.1:11501 cretas-aurora-chart@47.100.235.168
