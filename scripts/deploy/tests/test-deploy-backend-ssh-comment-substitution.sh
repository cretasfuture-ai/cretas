#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
deploy_script="$repo_root/scripts/deploy/deploy-backend.sh"

# The remote install body is a local double-quoted string. Backticks inside a
# comment are still command substitutions before ssh receives the body, so a
# harmless-looking comment can execute a local command and inject its output.
if awk '
    /if ssh \$SERVER "/ { in_remote_install = 1 }
    in_remote_install && /^[[:space:]]*#/ && index($0, "`") {
        print "unsafe backtick in double-quoted ssh comment at line " NR ": " $0 > "/dev/stderr"
        unsafe = 1
    }
    in_remote_install && /"; then/ { in_remote_install = 0 }
    END { exit unsafe ? 1 : 0 }
' "$deploy_script"; then
    printf '%s\n' 'PASS: deploy-backend remote-install comments cannot trigger local backtick substitution'
else
    exit 1
fi
