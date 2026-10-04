#!/bin/bash
# 在 47 上生成「重排服务」的 FRP STCP 两端配置 (2026-10-04)。密钥只落文件, 不打印。
#   win-frpc.toml  —— 给本机 Windows 的 frpc (服务端: 把本机 127.0.0.1:18190 注册成私密通道)
#   visitor.toml   —— 47 上的访问端: 只在 47 的 127.0.0.1:18190 上开口, 公网看不到
# 已存在就不重新生成 (幂等, 不换密钥)。
set -euo pipefail
D=/opt/frp/rerank
mkdir -p "$D" && chmod 700 "$D"
# frps 用 auth.token 认证 (2026-10-04 由 reset_frps_on_47.sh 重设; 之前磁盘上的配置曾被安装包默认版覆盖,
# 运行中的进程靠内存里的旧 token 拒扫描器)。本通道另有 STCP secretKey: 不知道 sk 的人连不上这个服务端。
# 逐行读 + bash 正则, 不经管道 (管道尾部会吞掉前面命令的退出码, 仓库闸 test-pipeline-tail-swallows-rc 拦新增)
TOKEN=""
while IFS= read -r line; do
  if [[ $line =~ ^[[:space:]]*auth\.token[[:space:]]*=[[:space:]]*\"?([^\"]*)\"?[[:space:]]*$ ]]; then
    TOKEN="${BASH_REMATCH[1]}"
    break
  fi
done < /opt/frp/frps.toml
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
