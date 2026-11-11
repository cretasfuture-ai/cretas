#!/usr/bin/env bash
# PostToolUse(Bash) hook: 本会话刚执行了合并动作(gh pr merge / publish-main-fastlane.sh 实推)就立刻触发一次 worktree 回收,
# 不等下一个会话启动。hygiene --auto 自己在后台跑、自带互斥锁; 当前会话的 worktree 有新鲜活动标记, 不会被它收掉。
# 从 stdin 读 hook JSON; 匹配不上或任何错误一律静默 exit 0。
in=$(cat 2>/dev/null)
case "$in" in
  *"gh pr merge"*|*"publish-main-fastlane.sh"*) ;;
  *) exit 0;;
esac
case "$in" in *"--dry-run"*) exit 0;; esac
bash "$(dirname "$0")/worktree-touch.sh" >/dev/null 2>&1
bash "$(dirname "$0")/worktree-hygiene.sh" --auto >/dev/null 2>&1
exit 0
