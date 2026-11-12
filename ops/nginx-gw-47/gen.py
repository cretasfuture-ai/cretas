"""Generate 47 gateway nginx configs from the repo copies of the 139 vhosts.

phase=http  : port 80 serves content (no TLS yet) + ACME webroot
phase=https : port 80 -> 301 (ACME kept), 443 with the Let's Encrypt cert
"""
import re
import sys
from pathlib import Path

G = Path(__file__).parent
phase = sys.argv[1]
assert phase in ("http", "https")
OUT = G / "out"
OUT.mkdir(exist_ok=True)
ACME_ROOT = "/www/wwwroot/acme"
CERT = "/etc/letsencrypt/live/cretas-gw/fullchain.pem"
KEY = "/etc/letsencrypt/live/cretas-gw/privkey.pem"


def read(name):
    return (G / name).read_text(encoding="utf-8")


def local(s):
    # same machine now: talk to Java / Python over loopback
    return s.replace("http://47.100.235.168:8083", "http://127.0.0.1:8083")


def server_443_body(conf):
    """Return the inner text of the `listen 443` server block."""
    i = conf.index("listen 443")
    start = conf.rindex("{", 0, i) + 1
    depth, j = 1, start
    while depth:
        c = conf[j]
        depth += c == "{"
        depth -= c == "}"
        j += 1
    body = conf[start:j - 1]
    body = re.sub(r"^\s*listen 443[^\n]*\n", "", body, flags=re.M)
    body = re.sub(r"^\s*http2 on;\n", "", body, flags=re.M)
    body = re.sub(r"^\s*server_name[^\n]*\n", "", body, flags=re.M)
    body = re.sub(r"^\s*ssl_[^\n]*\n", "", body, flags=re.M)
    body = re.sub(r"^\s*#\s*SSL[^\n]*\n", "", body, flags=re.M)
    return local(body)


TLS = f"""    ssl_certificate {CERT};
    ssl_certificate_key {KEY};
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
"""
ACME = f"    location ^~ /.well-known/acme-challenge/ {{ root {ACME_ROOT}; default_type text/plain; }}\n"


def vhost(names, body):
    names = " ".join(names)
    if phase == "http":
        return f"server {{\n    listen 80;\n    server_name {names};\n{ACME}{body}}}\n"
    return (f"server {{\n    listen 80;\n    server_name {names};\n{ACME}"
            f"    location / {{ return 301 https://$host$request_uri; }}\n}}\n\n"
            f"server {{\n    listen 443 ssl http2;\n    server_name {names};\n{TLS}{body}}}\n")


admin_body = server_443_body(read("admin.cretaceousfuture.com.conf"))
assert "root /www/wwwroot/web-admin;" in admin_body

# www = official site + the same API routes as admin (the App's API base is https://www.cretaceousfuture.com)
www_body = admin_body.replace("root /www/wwwroot/web-admin;", "root /www/wwwroot/showcase/cretaceousfuture;")
www_body = www_body.replace("location / { try_files $uri $uri/ /index.html; }", "location / { try_files $uri $uri/ =404; }")
assert "try_files $uri $uri/ =404;" in www_body

api_body = server_443_body(read("api.cretaceousfuture.com.conf"))
ota_body = server_443_body(read("ota.cretaceousfuture.com.conf"))
gone_body = "    location / { return 410; }\n"

files = {
    "gw-10-admin.conf": vhost(["admin.cretaceousfuture.com"], admin_body),
    "gw-11-www.conf": vhost(["www.cretaceousfuture.com", "cretaceousfuture.com"], www_body),
    "gw-12-api.conf": vhost(["api.cretaceousfuture.com"], api_body),
    "gw-13-ota.conf": vhost(["ota.cretaceousfuture.com"], ota_body),
    "gw-14-centerapi.conf": vhost(["centerapi.cretaceousfuture.com"], gone_body),
    # APK download page (deploy-apk.sh publishes download-page/ here; APKs themselves live on the dl CDN)
    "gw-15-download.conf": vhost(["download.cretaceousfuture.com"],
        "    root /www/wwwroot/download;\n    index index.html;\n"
        "    location / { try_files $uri $uri/ =404; }\n"
        "    location ~* \\.html$ { add_header Cache-Control \"no-cache\"; }\n"),
    # factory operation / SOP assistant: aiassist.html from the web-admin build + food-kb chat API
    "gw-16-aiassist.conf": vhost(["aiassist.cretaceousfuture.com"],
        "    root /www/wwwroot/web-admin;\n"
        "    location = / { try_files /aiassist.html =404; add_header Cache-Control \"no-cache\"; }\n"
        "    location / { try_files $uri =404; }\n"
        "    location /api/food-kb/ {\n"
        "        proxy_pass http://cretas_python;\n"
        "        proxy_set_header Host $host;\n"
        "        proxy_set_header X-Real-IP $remote_addr;\n"
        "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
        "        proxy_http_version 1.1;\n"
        "        proxy_set_header Connection \"\";\n"
        "        proxy_buffering off;\n"
        "        proxy_read_timeout 300s;\n"
        "    }\n"),
}
files["gw-00-upstream.conf"] = (
    "# 2026-11-12 gateway moved from 139 to 47.\n"
    "# cretas_backend lives in the file deploy-backend.sh rewrites on every blue-green switch.\n"
    "include /www/server/panel/vhost/nginx/_upstream_cretas.conf;\n"
    "upstream cretas_python { server 127.0.0.1:8083; keepalive 16; }\n")
# local-only vhosts the release scripts check over ssh (8086 prod web-admin, 8097 test web-admin)
files["gw-20-local-8086-8097.conf"] = (
    "server {\n    listen 127.0.0.1:8086;\n    server_name _;\n" + admin_body + "}\n\n"
    "server {\n    listen 127.0.0.1:8097;\n    server_name _;\n"
    + admin_body.replace("root /www/wwwroot/web-admin;", "root /www/wwwroot/web-admin-test;") + "}\n")
files["gw-01-ambiguous-map.conf"] = read("_cretas_ambiguous_path_map.conf")
files["gw-02-word-gate-map.conf"] = read("_cretas_word_gate_map.conf")
for name, text in files.items():
    (OUT / name).write_text(text, encoding="utf-8", newline="\n")
snip = OUT / "snippets"
snip.mkdir(exist_ok=True)
(snip / "smart-bi-routing.conf").write_text(local(read("smart-bi-routing.conf")), encoding="utf-8", newline="\n")
(snip / "block-ambiguous-path.conf").write_text(read("block-ambiguous-path.conf"), encoding="utf-8", newline="\n")
print("generated", phase, sorted(files))
