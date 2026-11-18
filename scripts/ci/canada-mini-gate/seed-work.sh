#!/bin/bash
# 以 ghgate 身份给每个槽位预置代码检出目录 (~/work-N/cretas/cretas), 之后 actions/checkout 只做增量拉取。
# 起因 (2026-11-18): mini→GitHub 小请求正常 (ls-remote 1s), 大批量传输频繁 reset (curl 56 / early EOF),
# PG 分片两次死在 checkout 的整仓拉取上。这里带重试一次拉全, 断了从已有对象续拉。
# 用法 (rex): sudo -n -u ghgate -H bash seed-work.sh
set -u
URL=https://github.com/cretasfuture-ai/cretas.git
SEED=$HOME/seed/cretas
mkdir -p "$HOME/seed"
[ -d "$SEED/.git" ] || git init -q "$SEED"
git -C "$SEED" remote get-url origin >/dev/null 2>&1 || git -C "$SEED" remote add origin "$URL"
git -C "$SEED" config remote.origin.promisor true
git -C "$SEED" config remote.origin.partialclonefilter blob:none
ok=0
for i in $(seq 1 15); do
  if git -C "$SEED" -c http.postBuffer=524288000 -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 \
       fetch -q --filter=blob:none origin '+refs/heads/main:refs/remotes/origin/main'; then
    if git -C "$SEED" checkout -q --force --detach origin/main; then ok=1; break; fi
  fi
  echo "attempt $i failed, retry in 20s"; sleep 20
done
[ "$ok" = 1 ] || { echo "SEED_FAILED"; exit 1; }
echo "seed ok head=$(git -C "$SEED" rev-parse --short HEAD) size=$(du -sh "$SEED/.git" | cut -f1)"
for n in 1 2; do
  dst=$HOME/work-$n/cretas/cretas
  if [ ! -d "$dst/.git" ]; then mkdir -p "$(dirname "$dst")"; cp -cR "$SEED" "$dst"; echo "seeded work-$n"; fi
done
