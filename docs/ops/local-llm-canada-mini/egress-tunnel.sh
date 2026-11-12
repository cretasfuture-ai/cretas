#!/bin/bash
# 2026-11-12 LLM-PROVIDER-SWITCH: reverse *dynamic* forward = a SOCKS exit on this mini, listening only on
# 47's 127.0.0.1:11438. prod sets LLM_EGRESS_PROXY=socks5h://127.0.0.1:11438 so only the openai /
# anthropic providers leave through here (47 is in mainland China: api.anthropic.com 403, api.openai.com times out).
# Separate process from tunnel.sh on purpose: ExitOnForwardFailure there would let a refused 11438 kill the 11437
# local-model tunnel too. Same dedicated user/key; 47 allows it via PermitListen + permitlisten (2026-11-12).
exec /usr/bin/ssh -N -T -F /dev/null -i /Users/rex/cretas-llm/tunnel_ed25519 -o IdentitiesOnly=yes -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/Users/rex/cretas-llm/known_hosts -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ConnectTimeout=10 -R 127.0.0.1:11438 cretas-llm-canada@47.100.235.168
