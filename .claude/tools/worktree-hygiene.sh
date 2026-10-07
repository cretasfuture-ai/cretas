#!/usr/bin/env bash
# worktree 体检 + 清理。上限 9 个（含主仓库）。规则见 .claude/rules/worktree-and-main-only-deploy.md「铁律 4」。
#
#   .claude/tools/worktree-hygiene.sh --check                 只报数与分类, 不动任何东西 (退出码: 0=未超限, 3=超限)
#   .claude/tools/worktree-hygiene.sh --apply [--keep <路径>]...   收缩到上限以内
#
# --apply 的处理 (每个 worktree 独立判断, 分支一律保留, 不删本地分支, 不 push):
#   保留: 主仓库 / 当前所在 worktree / .codex/worktrees 下的(Codex 的) / --keep 指定的(调用方先用 list_sessions 把 isRunning 的 cwd 传进来)
#         / locked 的 / 近 120 分钟内有过动静的(index 或 HEAD 被碰过 = 多半有人在用)
#   其余: 有未提交改动 -> 先在它自己的分支上 WIP commit (detached 且有改动 -> 跳过并报告, 不替它建分支)
#         -> 摘 junction (只摘链接) -> git worktree remove (⛔ 绝不加 --force) -> 删不掉就报告, 不强删
set -u
MAX=9
mode="${1:-}"; shift || true
keeps=()
while [ $# -gt 0 ]; do
  case "$1" in --keep) keeps+=("$(cd "$2" 2>/dev/null && pwd -W 2>/dev/null || echo "$2")"); shift 2;; *) shift;; esac
done
[ "$mode" = "--check" ] || [ "$mode" = "--apply" ] || { sed -n 2,14p "$0"; exit 2; }

common=$(git rev-parse --path-format=absolute --git-common-dir) || exit 2
main_wt=$(dirname "$common")
here=$(git rev-parse --show-toplevel)
norm() { echo "$1" | tr -d '\r' | sed 's#\\#/#g' | tr 'A-Z' 'a-z'; }
git -C "$main_wt" worktree prune
mapfile -t paths < <(git -C "$main_wt" worktree list --porcelain | awk '/^worktree /{sub(/^worktree /,""); print}')
count=${#paths[@]}
echo "worktree 总数: $count (上限 $MAX)"

recent() { # 120 分钟内 index/HEAD 被碰过
  local g; g=$(git -C "$1" rev-parse --absolute-git-dir 2>/dev/null) || return 1
  [ -n "$(find "$g/index" "$g/HEAD" -mmin -120 2>/dev/null)" ]
}
is_keep() {
  local p; p=$(norm "$1")
  [ "$p" = "$(norm "$main_wt")" ] && return 0
  [ "$p" = "$(norm "$here")" ] && return 0
  case "$p" in */.codex/worktrees/*) return 0;; esac   # Codex 自己的 worktree, 不归这里管
  for k in "${keeps[@]:-}"; do [ -n "$k" ] && [ "$p" = "$(norm "$k")" ] && return 0; done
  return 1
}
locked_set=$(git -C "$main_wt" worktree list --porcelain | awk '/^worktree /{p=$2} /^locked/{print p}')

removable=()
for p in "${paths[@]}"; do
  if is_keep "$p"; then echo "KEEP(保护)   $p"; continue; fi
  if echo "$locked_set" | grep -Fxq "$p"; then echo "KEEP(locked) $p"; continue; fi
  if recent "$p"; then echo "KEEP(近期活动) $p"; continue; fi
  dirty=$(git -C "$p" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
  br=$(git -C "$p" branch --show-current 2>/dev/null)
  ahead=$(git -C "$p" rev-list --count origin/main..HEAD 2>/dev/null || echo '?')
  echo "REMOVABLE    $p  branch=${br:-DETACHED} ahead=$ahead dirty=$dirty"
  removable+=("$p")
done

if [ "$mode" = "--check" ]; then
  echo "可收缩: ${#removable[@]} 个"
  [ "$count" -gt "$MAX" ] && { echo "⚠️ 超限: 先 --apply 再开新 worktree"; exit 3; }
  exit 0
fi

for p in "${removable[@]}"; do
  if [ -n "$(git -C "$p" status --porcelain)" ]; then
    br=$(git -C "$p" branch --show-current)
    [ -z "$br" ] && { echo "SKIP(detached 有改动) $p"; continue; }
    git -C "$p" add -A >/dev/null 2>&1
    git -C "$p" commit -q -m "WIP: worktree hygiene snapshot $(date +%F)" >/dev/null 2>&1 \
      && echo "WIPCOMMIT $br $(git -C "$p" rev-parse --short HEAD)" \
      || { echo "SKIP(commit 被 hook 拦, 未动) $p"; continue; }
  fi
  command -v powershell >/dev/null 2>&1 && powershell -NoProfile -Command "Get-ChildItem -LiteralPath '$p' -Recurse -Depth 4 -Directory -Force -EA SilentlyContinue | ?{\$_.Attributes -band 'ReparsePoint'} | %{ [IO.Directory]::Delete(\$_.FullName,\$false); 'unlinked '+\$_.FullName }"
  git -C "$main_wt" worktree remove "$p" 2>&1 && echo "REMOVED $p" || echo "FAILED(留给人工) $p"
done
git -C "$main_wt" worktree prune
echo "收缩后: $(git -C "$main_wt" worktree list | wc -l) 个"
