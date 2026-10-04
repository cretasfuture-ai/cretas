#!/bin/bash
# 在 47 上生成「重排服务」的 FRP STCP 两端配置 (2026-10-04)。密钥只落文件, 不打印。
#   win-frpc.toml  —— 给本机 Windows 的 frpc (服务端: 把本机 127.0.0.1:18190 注册成私密通道)
#   visitor.toml   —— 47 上的访问端: 只在 47 的 127.0.0.1:18190 上开口, 公网看不到
# 已存在就不重新生成 (幂等, 不换密钥)。
set -euo pipefail
D=/opt/frp/rerank
mkdir -p "$D" && chmod 700 "$D"
# 2026-10-04 实测: frps.toml 没有设 auth.token (7000 对公网开放, 无认证 —— 既有隐患, 已报 Steve)。
# 本通道靠 STCP secretKey 保护: 不知道 sk 的人连不上这个 visitor 对应的服务端。有 token 就带上。
TOKEN=$(grep -E '^\s*auth\.token\s*=' /opt/frp/frps.toml | head -1 | sed -E 's/^[^=]+=\s*"?([^"]*)"?\s*$/\1/' || true)
TOKEN_LINE=""
[ -n "$TOKEN" ] && TOKEN_LINE="auth.token = \"$TOKEN\""
if [ ! -f "$D/sk" ]; then
  head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$D/sk"
  chmod 600 "$D/sk"
fi
SK=$(cat "$D/sk")
cat > "$D/win-frpc.toml" <<EOF
serverAddr = "47.100.235.168"
serverPort = 7000
$TOKEN_LINE
loginFailExit = false

[[proxies]]
name = "cretas-rerank"
type = "stcp"
secretKey = "$SK"
localIP = "127.0.0.1"
localPort = 18190
EOF
cat > "$D/visitor.toml" <<EOF
serverAddr = "127.0.0.1"
serverPort = 7000
$TOKEN_LINE
loginFailExit = false

[[visitors]]
name = "cretas-rerank-visitor"
type = "stcp"
serverName = "cretas-rerank"
secretKey = "$SK"
bindAddr = "127.0.0.1"
bindPort = 18190
EOF
chmod 600 "$D/win-frpc.toml" "$D/visitor.toml"
cat > /etc/systemd/system/frpc-rerank-visitor.service <<EOF
[Unit]
Description=FRP STCP visitor for the intent rerank service (127.0.0.1:18190 only)
After=network-online.target frps.service

[Service]
ExecStart=/opt/frp/frpc -c $D/visitor.toml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now frpc-rerank-visitor.service >/dev/null 2>&1
sleep 2
echo "visitor: $(systemctl is-active frpc-rerank-visitor.service) / $(systemctl is-enabled frpc-rerank-visitor.service)"
ss -lntp | grep ':18190 ' | awk '{print "listen", $4}'
