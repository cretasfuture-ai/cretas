#!/bin/bash
# canada-mini runner 的任务开始钩子 (ACTIONS_RUNNER_HOOK_JOB_STARTED, 以 ghgate 运行, 在任务第一步之前)。
# 把本机完整镜像 ~ghgate/mirror/cretas.git 从 GitHub 增量更新到这次任务要的提交。
# 任务里的 git (actions/checkout、合并 base) 经 .env 里的 url.<镜像>.insteadOf 改走本机镜像。
#
# 为什么 (2026-11-18 实测): actions/checkout 用 fetch.negotiationAlgorithm=noop 拉取, 不报本地已有对象,
# GitHub 每次整份发全部提交历史。mini 下行约 1.8MB/s, changes 作业 5 分钟拉不完被取消,
# PG 分片光 checkout 就 4 分钟以上。镜像这里用正常协商, 只取新增对象 (秒级); 任务从本机拉, 不受网速影响。
#
# 失败策略: 镜像更新失败不让任务失败 (exit 0), 任务照常从镜像拉; 镜像缺这次的提交时 checkout 会报
# 「not our ref / couldn't find remote ref」, 那是真失败, 不会静默用错的树。
set -u
M=/Users/ghgate/mirror/cretas.git
URL=https://github.com/cretasfuture-ai/cretas.git
LOCK=/Users/ghgate/mirror/.fetch.lock
# 本钩子自己要直连 GitHub: 去掉 .env 注入的 insteadOf, 否则镜像会向自己拉取。
unset GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
[ -d "$M" ] || { echo "READING: MIRROR missing=$M"; exit 0; }

# 两个槽位同时开工时串行更新 (macOS 没有 flock, 用 mkdir 原子锁; 超过 5 分钟的锁视为残留)。
for i in $(seq 1 150); do
  mkdir "$LOCK" 2>/dev/null && break
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +5 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null; continue; fi
  sleep 2
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

refs=('+refs/heads/*:refs/heads/*')
# pull_request: GITHUB_REF=refs/pull/<N>/merge
if [[ "${GITHUB_REF:-}" =~ ^refs/pull/([0-9]+)/ ]]; then
  n=${BASH_REMATCH[1]}
  refs+=("+refs/pull/$n/head:refs/pull/$n/head" "+refs/pull/$n/merge:refs/pull/$n/merge")
fi
s=$(date +%s)
ok=no
for a in 1 2 3; do
  if git -C "$M" -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60 fetch -q --prune --no-tags "$URL" "${refs[@]}"; then ok=yes; break; fi
  sleep 5
done
have=no
[ -n "${GITHUB_SHA:-}" ] && git -C "$M" cat-file -e "${GITHUB_SHA}^{commit}" 2>/dev/null && have=yes
echo "READING: MIRROR fetch_ok=$ok seconds=$(( $(date +%s) - s )) ref=${GITHUB_REF:-} sha=${GITHUB_SHA:-} have_sha=$have"
exit 0
