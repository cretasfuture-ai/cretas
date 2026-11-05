#!/usr/bin/env bash
set -eu
target=/www/server/panel/vhost/nginx/test.cretaceousfuture.com.conf
backup=/www/server/panel/vhost/nginx/test.cretaceousfuture.com.conf.blacklake-preview-backup
exec 9>/tmp/cretas-blacklake-preview-publish.lock
flock -n 9 || exit 2
verify_closed() {
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        status=$(curl -sS --resolve test.cretaceousfuture.com:443:127.0.0.1 -o /dev/null -w '%{http_code}' https://test.cretaceousfuture.com/)
        if [ "$status" = 410 ]; then return 0; fi
        sleep 1
    done
    return 1
}
if grep -q 'Standalone test preview' "$target"; then
    test -f "$backup"
    grep -q 'return 410' "$backup"
    cp -p "$backup" "$target"
    /www/server/nginx/sbin/nginx -t
    /www/server/nginx/sbin/nginx -s reload
fi
if [ "${1:-}" = --close-only ]; then
    verify_closed
    echo BLACKLAKE_PREVIEW_ENTRY=closed410
    exit 0
fi
for attempt in 1 2 3 4 5; do
    if ! ss -H -ltn 'sport = :19378' | grep -q .; then break; fi
    sleep 1
done
if ss -H -ltn 'sport = :19378' | grep -q .; then echo 'Remote preview port still listening'; exit 6; fi
verify_closed
echo BLACKLAKE_PREVIEW_DISABLED=verified410
