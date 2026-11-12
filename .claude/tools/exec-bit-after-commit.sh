#!/usr/bin/env bash
# PostToolUse(Bash) hook: 刚执行 git commit 后, 检查 HEAD 提交是否把文件 100755 -> 100644 (丢 +x)。
# core.fileMode=false 下 `git commit -- <paths>` 会静默丢掉暂存的 +x; 本机绿、CI 才红。
# 命中: stderr 打印并 exit 2 (让模型看到, 去 `git update-index --chmod=+x` 补回再 amend/追加提交)。
# 其余情况(不是 commit / 没问题 / 任何错误)一律静默 exit 0。⛔ 不要改 core.fileMode(Windows 上会让大量文件显示为已修改)。
in=$(cat 2>/dev/null)
case "$in" in *"git commit"*|*"safe-commit.sh"*) ;; *) exit 0;; esac
case "$in" in *"--dry-run"*) exit 0;; esac
dir="${CLAUDE_PROJECT_DIR:-.}"
out=$(git -C "$dir" -c core.quotepath=false diff-tree --raw -r --no-renames --no-commit-id HEAD 2>/dev/null) || exit 0
bad=$(printf '%s\n' "$out" | awk '$1==":100755" && $2=="100644" {print $NF}')
[ -z "$bad" ] && exit 0
echo "⚠️ 刚提交的 HEAD 丢了可执行位 (100755 -> 100644):" >&2
printf '%s\n' "$bad" >&2
echo "修复: git update-index --chmod=+x <path> 后新增提交; 用 git ls-tree HEAD <path> 确认 100755。有意去掉 +x 则登记 scripts/ci/exec-bit-allowlist.txt。" >&2
exit 2
