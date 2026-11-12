#!/bin/bash
# 2026-11-12 INTENT-DISTILL-20261111: 第二条出境出口 (备用), 跑在美国 Mac mini aurora 上。
# 47 的 127.0.0.1:11439 -> aurora 127.0.0.1:17897 (egress-socks-allowlist.py, 只放行 api.openai.com:443)
# -> 直连 OpenAI。aurora 在美国, 不需要 Clash; 与主出口 (11438 -> 加拿大 mini Clash 7897) 不共用机器/代理。
# 47 上专用用户 cretas-llm-aurora: AllowTcpForwarding remote + PermitListen 127.0.0.1:11439 (sshd Match),
# authorized_keys 带 restrict,port-forwarding,permitlisten。
exec /usr/bin/ssh -N -T -F /dev/null -i /Users/aurora/cretas-llm-egress/tunnel_ed25519 -o IdentitiesOnly=yes   -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes   -o UserKnownHostsFile=/Users/aurora/cretas-llm-egress/known_hosts -o ExitOnForwardFailure=yes   -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ConnectTimeout=10   -R 127.0.0.1:11439:127.0.0.1:17897 cretas-llm-aurora@47.100.235.168
