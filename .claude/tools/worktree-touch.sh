#!/usr/bin/env bash
# 给当前 worktree 打「有会话在用」标记(mtime = 最后活动)。挂在 SessionStart / UserPromptSubmit / Stop hook 上。
# worktree-hygiene.sh --auto 只保护标记在 WH_ACTIVE_MIN(默认 360)分钟内的 worktree, 其余已完成的立即回收。
# 标记放在该 worktree 的 git 管理目录里(不在工作树, 不会弄脏, 也不会被提交)。永远 exit 0。
g=$(git rev-parse --absolute-git-dir 2>/dev/null) || exit 0
touch "$g/wh-active" 2>/dev/null
exit 0
