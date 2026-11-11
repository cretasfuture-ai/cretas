#!/usr/bin/env bash
# worktree 体检 + 清理。上限 30 个（含主仓库；2026-11-11 由 9 调整）。规则见 .claude/rules/worktree-and-main-only-deploy.md「铁律 4」。
#
#   .claude/tools/worktree-hygiene.sh --check                 只报数与分类, 不动任何东西 (退出码: 0=未超限, 3=超限)
#   .claude/tools/worktree-hygiene.sh --apply [--keep <路径>]...   收缩到上限以内
#   .claude/tools/worktree-hygiene.sh --auto [--keep <路径>]...    按「活干完了」自动回收(挂在 SessionStart hook 上, 2026-11-11)
#
# --auto: 只回收【干净 + 已完成】的 worktree, 与总数无关。已完成 = 分支的 PR 已 MERGED 且 PR 头 == 该 worktree HEAD
#   (squash 合并后 git 看不出已合并, 所以问 gh; 合并后又加了提交的不算完成), 或 HEAD 已是 origin/main 的祖先。
#   .codex/worktrees 不再整体豁免, 同样按此判据; 有任何改动(含未跟踪)一律不动; 不 WIP commit; 分支保留。
#   前台只打一行数量/超限提示就返回, 回收在后台跑, 日志 <git-common-dir>/worktree-hygiene.log; gh 不可用则不回收。
#
# --apply 的处理 (每个 worktree 独立判断, 分支一律保留, 不删本地分支, 不 push):
#   保留: 主仓库 / 当前所在 worktree / .codex/worktrees 下的(Codex 的) / --keep 指定的(调用方先用 list_sessions 把 isRunning 的 cwd 传进来)
#         / locked 的 / 近 120 分钟内 HEAD 动过的(提交/切分支 = 多半有人在用; WH_NO_RECENT=1 可关闭)
#   其余: 有未提交改动 -> 先在它自己的分支上 WIP commit (detached 且有改动 -> 跳过并报告, 不替它建分支)
#         -> 摘 junction (只摘链接) -> git worktree remove (⛔ 绝不加 --force) -> 删不掉就报告, 不强删
set -u
MAX=30
mode="${1:-}"; shift || true
keeps=()
while [ $# -gt 0 ]; do
  case "$1" in --keep) keeps+=("$(cd "$2" 2>/dev/null && pwd -W 2>/dev/null || echo "$2")"); shift 2;; *) shift;; esac
done
[ "$mode" = "--check" ] || [ "$mode" = "--apply" ] || [ "$mode" = "--auto" ] || { sed -n 2,14p "$0"; exit 2; }

common=$(git rev-parse --path-format=absolute --git-common-dir) || exit 2
main_wt=$(dirname "$common")
here=$(git rev-parse --show-toplevel)
norm() { echo "$1" | tr -d '\r' | sed 's#\\#/#g' | tr 'A-Z' 'a-z'; }
git -C "$main_wt" worktree prune
mapfile -t paths < <(git -C "$main_wt" worktree list --porcelain | awk '/^worktree /{sub(/^worktree /,""); print}')
count=${#paths[@]}
echo "worktree 总数: $count (上限 $MAX)"
if [ "$mode" = "--auto" ]; then
  [ "$count" -gt "$MAX" ] && echo "⚠️ worktree 数 $count 超过 $MAX: 后台正在回收已完成的; 仍超限请先收掉自己已合并的 worktree 再开新的"
  if [ "${WH_BG:-0}" != 1 ]; then   # 前台: 起后台任务就返回, 不拖会话启动
    logf="$common/worktree-hygiene.log"
    fwd=(); for k in "${keeps[@]:-}"; do [ -n "$k" ] && fwd+=(--keep "$k"); done
    ( WH_BG=1 nohup bash "$0" --auto "${fwd[@]:-}" >>"$logf" 2>&1 & ) >/dev/null 2>&1
    exit 0
  fi
  lock="$common/worktree-hygiene.lock"
  find "$lock" -maxdepth 0 -mmin +90 -exec rmdir {} \; 2>/dev/null   # 陈旧锁
  mkdir "$lock" 2>/dev/null || { echo "另一个回收在跑, 退出"; exit 0; }
  trap 'rmdir "$lock" 2>/dev/null' EXIT
  echo "=== auto $(date '+%F %T') ==="
  merged_map=$(gh pr list --state merged --limit 500 --json headRefName,headRefOid --jq '.[]|.headRefName+" "+.headRefOid' 2>/dev/null)     || { echo "gh 不可用, 本次不回收"; exit 0; }
  [ -n "$merged_map" ] || { echo "gh 返回空(没量到), 本次不回收"; exit 0; }
  git -C "$main_wt" fetch -q origin main 2>/dev/null || echo "fetch 失败, 用本地 origin/main"
fi

recent() { # 120 分钟内 HEAD 动过(提交/切分支)。⛔ 不看 index: git status 本身会刷新 index mtime, 体检自己就会把全部判成「近期活动」
  [ "${WH_NO_RECENT:-0}" = 1 ] && return 1
  local g; g=$(git -C "$1" rev-parse --absolute-git-dir 2>/dev/null) || return 1
  local win=120
  if [ "$mode" = "--auto" ]; then   # --auto 已要求「PR 已合并 + 干净」, 不再叠时间窗(否则刚合并的会白占名额); 想加保护用 WH_RECENT_MIN=<分钟>
    [ -n "${WH_RECENT_MIN:-}" ] || return 1
    win="$WH_RECENT_MIN"
  fi
  [ -n "$(find "$g/HEAD" -mmin -"$win" 2>/dev/null)" ]
}
is_keep() {
  local p; p=$(norm "$1")
  [ "$p" = "$(norm "$main_wt")" ] && return 0
  [ "$p" = "$(norm "$here")" ] && return 0
  [ "$mode" != "--auto" ] && case "$p" in */.codex/worktrees/*) return 0;; esac   # Codex 自己的 worktree: --check/--apply 不管; --auto 按完成判据管
  for k in "${keeps[@]:-}"; do [ -n "$k" ] && [ "$p" = "$(norm "$k")" ] && return 0; done
  return 1
}
locked_set=$(git -C "$main_wt" worktree list --porcelain | awk '/^worktree /{p=$2} /^locked/{print p}')

removable=()
for p in "${paths[@]}"; do
  if is_keep "$p"; then echo "KEEP(保护)   $p"; continue; fi
  if echo "$locked_set" | grep -Fxq "$p"; then echo "KEEP(locked) $p"; continue; fi
  if recent "$p"; then echo "KEEP(近期活动) $p"; continue; fi
  dirty=0; [ "$mode" = "--auto" ] || dirty=$(git -C "$p" status --porcelain 2>/dev/null | wc -l | tr -d ' ')   # --auto 先判完成再查脏(git status 在大树上要几秒)
  br=$(git -C "$p" branch --show-current 2>/dev/null)
  ahead=$(git -C "$p" rev-list --count origin/main..HEAD 2>/dev/null || echo '?')
  if [ "$mode" = "--auto" ]; then
    head_sha=$(git -C "$p" rev-parse HEAD 2>/dev/null)
    done_=0
    [ "$ahead" = 0 ] && done_=1
    [ -n "$br" ] && echo "$merged_map" | grep -Fxq "$br $head_sha" && done_=1
    [ "$done_" = 1 ] || { echo "KEEP(未完成) $p branch=${br:-DETACHED} ahead=$ahead"; continue; }
    dirty=$(git -C "$p" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
    [ "$dirty" = 0 ] || { echo "KEEP(有改动) $p"; continue; }
  fi
  echo "REMOVABLE    $p  branch=${br:-DETACHED} ahead=$ahead dirty=$dirty"
  removable+=("$p")
done

if [ "$mode" = "--check" ]; then
  echo "可收缩: ${#removable[@]} 个"
  [ "$count" -gt "$MAX" ] && { echo "⚠️ 超限: 先 --apply 再开新 worktree"; exit 3; }
  exit 0
fi

if [ "${WH_DRY:-0}" = 1 ]; then echo "WH_DRY=1: 只列出, 不删 (${#removable[@]} 个可回收)"; exit 0; fi
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
