#!/bin/bash
# 2026-11-12 LLM-PROVIDER-SWITCH: SOCKS exit for the opt-in OpenAI providers (Luna intent decision), listening
# only on 47's 127.0.0.1:11438. prod sets LLM_EGRESS_PROXY=socks5h://127.0.0.1:11438; only openai/anthropic use it.
#
# Why a static forward to 127.0.0.1:7897 and not a dynamic `-R 127.0.0.1:11438`: this mini's own IP is blocked too
# (2026-11-12: direct curl -> api.openai.com timeout, api.anthropic.com 403 "Request not allowed"); the mini reaches
# the internet through its Clash Verge mixed port 127.0.0.1:7897 (system proxy). A dynamic SSH exit dials out directly
# and gets the same block, so the forward lands on Clash's SOCKS/HTTP mixed port instead (measured via Clash: 401 / 401
# in 0.43 s). ⇒ This exit depends on Clash Verge running on the mini.
#
# Separate process from tunnel.sh on purpose: ExitOnForwardFailure there would let a refused 11438 kill the 11437
# local-model tunnel too. Same dedicated user/key; 47 allows it via PermitListen + permitlisten (2026-11-12).
exec /usr/bin/ssh -N -T -F /dev/null -i /Users/rex/cretas-llm/tunnel_ed25519 -o IdentitiesOnly=yes -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/Users/rex/cretas-llm/known_hosts -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o ConnectTimeout=10 -R 127.0.0.1:11438:127.0.0.1:7897 cretas-llm-canada@47.100.235.168
