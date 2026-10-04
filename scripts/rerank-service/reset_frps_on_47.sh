#!/bin/bash
# 2026-10-04 Steve 批准: 重设 47 上 frps 的 token。
# 背景: /opt/frp/frps.toml 被安装包默认版 (只有 bindPort) 覆盖, 运行中的 frps 靠内存里的旧 token 拒扫描器;
#       一旦重启就会无认证。最近一周 frps 日志里只有扫描器登录 (全部被拒), 没有正常客户端。
# 做法: 备份 → 写新配置 (新 token + 日志 + 管理面板只在本机) → 重启 → 自检。密钥只落文件, 不打印。
set -euo pipefail
cd /opt/frp
TS=$(date +%Y%m%d-%H%M%S)
cp -p frps.toml "frps.toml.bak-$TS"
umask 077
NEWTOKEN=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
DASHPW=$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')
cat > frps.toml.new <<EOF
bindPort = 7000
auth.method = "token"
auth.token = "$NEWTOKEN"

# 与运行中旧进程一致: 日志按天滚动, 保留 7 天
log.to = "/opt/frp/frps.log"
log.level = "info"
log.maxDays = 7

# 管理面板只开在本机 (旧进程也是 127.0.0.1:7500)
webServer.addr = "127.0.0.1"
webServer.port = 7500
webServer.user = "admin"
webServer.password = "$DASHPW"
EOF
/opt/frp/frps verify -c frps.toml.new >/dev/null
mv frps.toml.new frps.toml
chmod 600 frps.toml
systemctl restart frps
sleep 2
echo "frps: $(systemctl is-active frps)"
ss -lntp | grep frps | awk '{print "listen", $4}'
echo "backup: frps.toml.bak-$TS"
