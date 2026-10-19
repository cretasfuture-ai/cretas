#!/usr/bin/env bash
set -euo pipefail
[[ "$(hostname)" == Auroras-Mac-mini.local && "$(whoami)" == aurora && "$HOME" == /Users/aurora ]] || exit 78
/usr/bin/curl --fail --silent --show-error --max-time 3 http://127.0.0.1:11438/health >/dev/null
exec /usr/bin/ssh -F "$HOME/.config/cretas/f006/ssh_config" -NT cretas-f006-production
