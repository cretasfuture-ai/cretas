# Cretas Nginx Vhost Reference

The nginx vhosts on `139.196.165.140` are managed by Baota and **NOT committed
to git**. This document records the **correct** upstream pattern all Cretas
vhosts MUST follow so blue-green deploys work.

## The rule

**All vhosts that proxy to the Cretas Java backend MUST use `http://cretas_backend/...`**
(the named upstream defined in `_upstream_cretas.conf`). NEVER hardcode
`http://47.100.235.168:10010/` or `:10020/` in a vhost — that breaks during
blue-green swaps.

Why: `deploy-backend.sh v5.0` blue-green orchestration rewrites the `server`
line inside `_upstream_cretas.conf` during cutovers. Any vhost that reads
from that upstream follows the swap automatically. Vhosts that hardcode the
port stay pointed at whichever port was "winning" when they were written, and
break the first time blue-green cuts over.

## Correct pattern

```nginx
# 1. _upstream_cretas.conf (auto-managed — DO NOT edit directly)
upstream cretas_backend {
    server 47.100.235.168:10010;  # or 10020; rewritten during BG swaps
    keepalive 32;
}

# 2. Any vhost proxying Cretas API:
location /api/mobile/ {
    proxy_pass http://cretas_backend/api/mobile/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
}

location /api/public/ {
    proxy_pass http://cretas_backend/api/public/;
    # (same headers)
}
```

## Anti-pattern (DO NOT do this)

```nginx
location /api/mobile/ {
    proxy_pass http://47.100.235.168:10010/api/mobile/;  # HARDCODED — breaks during BG swaps
}
```

## Current status (as of 2026-04-11)

| Vhost | Config file on 139 | Status |
|---|---|---|
| `api.cretaceousfuture.com` | `api.cretaceousfuture.com.conf` | ✅ Uses `http://cretas_backend/` |
| `www.cretaceousfuture.com` | `www.cretaceousfuture.com.conf` | ✅ Fixed 2026-04-11 (this doc) |
| `web-admin` | `web-admin.conf` | N/A — proxies to Python not Cretas Java |
| `centerapi.cretaceousfuture.com` | `java_logistics-admin.conf` | N/A — proxies to Mall admin not Cretas |

## Ambiguous path rule (live since 2026-11-04)

Java gates judge the raw URI while Spring routes on the decoded path, so a request path whose raw
and routed forms can differ is refused at the gateway as well as in Java
(`AmbiguousRequestPathFilter`). Repo copies: `ops/nginx-vhosts-139/_cretas_ambiguous_path_map.conf`
and `ops/nginx-vhosts-139/block-ambiguous-path.conf`.

```nginx
# /www/server/panel/vhost/nginx/_cretas_ambiguous_path_map.conf
# Loaded by the main config's vhost glob: no vhost names it, its location is its wiring.
map $request_uri $cretas_ambiguous_path {
    default 0;
    "~*^[^?]*(;|%(3[0-9]|4[1-9a-f]|5[0-9a]|6[1-9a-f]|7[0-9a]|2[def]|5[cf]|7e))" 1;
}

# /www/server/nginx/conf/snippets/block-ambiguous-path.conf
if ($cretas_ambiguous_path) { return 400; }
```

Every live server block that reaches Cretas Java or Python includes the snippet on the line after `server_name`. `ops/nginx-vhosts-139/web-admin-test.conf` (8097) is not loaded on 139 today: add the include if it comes back.

```nginx
include /www/server/nginx/conf/snippets/block-ambiguous-path.conf;
```

| Vhost file on 139 | Serving block | Mirrored in `ops/nginx-vhosts-139/` |
|---|---|---|
| `admin.cretaceousfuture.com.conf` | 443 | yes |
| `api.cretaceousfuture.com.conf` | 443 | yes |
| `web-admin.conf` | 8086 | yes |
| `test.cretaceousfuture.com.conf` | 443 | no, include is on 139 only |
| `www.cretaceousfuture.com.conf` | 443 | no, include is on 139 only |
| `aiassist.cretaceousfuture.com.conf` | 443 | no, include is on 139 only |
| `ota.cretaceousfuture.com.conf` | 443 | no, include is on 139 only |

The port 80 blocks only redirect and carry no include. A new vhost that proxies Cretas needs the
include; a Baota regenerate can drop it, so re-check after one.

The rule judges only the path part of `$request_uri`, so a query string is never refused. It covers
escapes of letters, digits and `-._~\`, which no client needs. Encoded control bytes are not in it;
Java refuses those.

`%3B` passes this rule but does not reach Java as `%3B` everywhere. A URI-form `proxy_pass`
(`proxy_pass http://cretas_backend/api/mobile/;`, used on the admin, web-admin and www vhosts) sends the
normalised URI, which turns it into a real `;`, and Java then refuses it. `proxy_pass` without a URI
(the api vhost) passes the raw form. So a value holding `;` cannot travel in a path through those three
vhosts; put it in the query string or the body.

## Re-applying after a Baota regenerate

If Baota regenerates `www.cretaceousfuture.com.conf` or a server rebuild
restores an old version, the 2 `proxy_pass` lines will revert to hardcoded
port. Fix:

```bash
ssh root@139.196.165.140
# Backup first
cp /www/server/panel/vhost/nginx/www.cretaceousfuture.com.conf \
   /www/server/panel/vhost/nginx/www.cretaceousfuture.com.conf.bak.$(date +%Y%m%d_%H%M%S)

# Rewrite both hardcoded lines
sed -i \
  -e 's|proxy_pass http://47.100.235.168:10010/api/mobile/|proxy_pass http://cretas_backend/api/mobile/|' \
  -e 's|proxy_pass http://47.100.235.168:10010/api/public/|proxy_pass http://cretas_backend/api/public/|' \
  /www/server/panel/vhost/nginx/www.cretaceousfuture.com.conf

# Verify
nginx -t && nginx -s reload
curl -s -o /dev/null -w "%{http_code}\n" https://www.cretaceousfuture.com/api/mobile/health
# Expected: 200
```

## Related

- `scripts/deploy/nginx-scanner-blocklist.conf` — scanner blocklist snapshot (Phase 2 of error-log-hygiene)
- `scripts/deploy/deploy-backend.sh` — blue-green orchestration that manages `_upstream_cretas.conf`
- `docs/superpowers/plans/2026-04-11-nginx-upstream-migration-audit.md` — parent plan

## Word gate (added 2026-11-04, review around 2026-11-11)

A stop-gap in front of the Java gate. `/api/mobile/{word}/...` needs a credential at the gateway unless the path is a known anonymous route; `/api/mobile/public/` is closed except `share`. Repo copy: `ops/nginx-vhosts-139/_cretas_word_gate_map.conf`; the second line of `block-ambiguous-path.conf` applies it, so every server block that includes the snippet gets it.

nginx cannot verify a token. This stops requests with no credential, not one with a forged header: the Java gate is what refuses those. Removing this rule does not open anything the Java gate covers.

Remove it: delete the map file and the second line of the snippet, then `nginx -t` and `nginx -s reload`.
