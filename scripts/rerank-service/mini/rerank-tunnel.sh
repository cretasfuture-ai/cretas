#!/bin/bash
# 2026-11-15 重排服务 mini → 47 反向隧道: 47 的 127.0.0.1:18191 → mini 的 127.0.0.1:18190 (app_mlx.py)。
# 照抄 ~/cretas-llm/tunnel.sh / egress-tunnel.sh 的做法: 同一个受限账户 cretas-llm-canada + 同一把专用密钥,
# 47 上 sshd Match 块 PermitListen 与 authorized_keys 的 permitlisten 都放行了 127.0.0.1:18191 (公网不可见)。
# 单独一个进程 (不并进 tunnel.sh): ExitOnForwardFailure 时 18191 被拒不会连带掐掉 11437 本地模型隧道。
# 18190 留给 Windows FRP 那条备份通道, 不冲突。
exec /usr/bin/ssh -N -T -F /dev/null -i /Users/rex/cretas-llm/tunnel_ed25519 -o IdentitiesOnly=yes -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/Users/rex/cretas-llm/known_hosts -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ConnectTimeout=10 -R 127.0.0.1:18191:127.0.0.1:18190 cretas-llm-canada@47.100.235.168
