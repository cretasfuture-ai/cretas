#!/usr/bin/env bash
# worktree 体检 + 清理。上限 50 个（含主仓库；2026-11-11 由 9 调到 30，2026-11-20 Steve 定调到 50）。规则见 .claude/rules/worktree-and-main-only-deploy.md「铁律 4」。
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
#
# 占用探测 (--apply 与 --auto 删除前都做, 2026-11-11): 先把目录改名再改回。Windows 上目录或其任一子目录是某个进程的
#   当前目录时改名会失败 ⇒ 判为「占用中」, 一个文件都不删。原因: git worktree remove 是先删文件、最后删目录;
#   会话正在用的目录会被删光内容、只剩一个删不掉的空壳 (2026-11-11 organizer 会话与推送排查会话都是这样丢了工作目录)。
#   活动标记与时间窗口只是推断, 这一步问的是操作系统。Linux/macOS 上改名不受当前目录限制, 探测恒为「未占用」, 只靠前面的判据。
#
# 会话工作目录保护 (2026-11-15): 没归档的 Claude 桌面会话的 cwd / worktreePath (含其子目录) 一律保留, --apply 与 --auto 都管。
#   活动标记只看 6 小时窗口, 置顶但几天没说话的会话会被判成「不在用」: 11-15 11:18 --auto 删掉了置顶会话「产品亮点与BP叙事」的 worktree。
#   会话清单读自 Claude 应用的 claude-code-sessions 目录 (WH_SESSIONS_DIR 可覆盖)。目录不存在 = 本机没有桌面应用, 不按会话保护;
#   目录在但一个会话文件都读不到 = 没量到, --auto 本次不回收。
# 归属检查放行 (2026-11-15): 部分 worktree 的 .git 归属是 Administrators, git 报 dubious ownership 拒绝读, 本脚本拿不到分支和提交
#   (日志里 branch=DETACHED ahead=?), 已完成的也一直判成「未完成」。下面只对本脚本里的 git 放行。
set -u
_n=${GIT_CONFIG_COUNT:-0}; export "GIT_CONFIG_KEY_$_n=safe.directory" "GIT_CONFIG_VALUE_$_n=*" "GIT_CONFIG_COUNT=$((_n+1))"; unset _n
MAX=50
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
in_use() { # 0 = 有进程占用 (或无法确认), 1 = 可以删。见文件头「占用探测」。
  local p="${1%/}" probe="${1%/}.wh-probe-$$"
  [ -d "$p" ] || return 1
  mv "$p" "$probe" 2>/dev/null || return 0
  mv "$probe" "$p" 2>/dev/null || { echo "⚠️ 占用探测改名后还原失败, 请手工改回: $probe -> $p"; return 0; }
  return 1
}
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
  sweep_shells() { # 清「空壳」: git worktree remove 删完内容却因目录被占用删不掉时留下的空目录。只删【未登记 + 完全为空】的, 非空的不碰。
    local d reg n=0 held=0 seen_f="$common/worktree-hygiene.held-shells" seen="" now_held=""
    # 同一个空壳每轮都报会刷屏 (11-13 实测 3 个空壳各报约 90 次): 首次报一行, 之后只计数; 删掉后从清单里移除
    seen=$(cat "$seen_f" 2>/dev/null)
    reg=$(for x in "${paths[@]}"; do norm "$x"; done)
    for d in "$main_wt"/.claude/worktrees/*/ "${shell_candidates[@]:-}"; do
      d="${d%/}"; [ -n "$d" ] && [ -d "$d" ] || continue
      echo "$reg" | grep -Fxq "$(norm "$d")" && continue
      [ -z "$(ls -A "$d" 2>/dev/null)" ] || continue
      if rmdir "$d" 2>/dev/null; then echo "SWEPT 空壳 $d"; n=$((n+1)); continue; fi
      now_held+="$d"$'\n'; held=$((held+1))
      printf '%s\n' "$seen" | grep -Fxq "$d" || echo "空壳仍被占用(首次): $d"
    done
    printf '%s' "$now_held" > "$seen_f" 2>/dev/null
    [ "$held" -gt 0 ] && echo "空壳仍被占用 共 $held 个 (首次之外不逐条重复)"
  }
  shell_candidates=()
  sweep_shells
  merged_map=$(gh pr list --state merged --limit 500 --json headRefName,headRefOid --jq '.[]|.headRefName+" "+.headRefOid' 2>/dev/null)     || { echo "gh 不可用, 本次不回收"; exit 0; }
  [ -n "$merged_map" ] || { echo "gh 返回空(没量到), 本次不回收"; exit 0; }
  git -C "$main_wt" fetch -q origin main 2>/dev/null || echo "fetch 失败, 用本地 origin/main"
fi

recent() { # 120 分钟内 HEAD 动过(提交/切分支)。⛔ 不看 index: git status 本身会刷新 index mtime, 体检自己就会把全部判成「近期活动」
  [ "${WH_NO_RECENT:-0}" = 1 ] && return 1
  local g; g=$(git -C "$1" rev-parse --absolute-git-dir 2>/dev/null) || return 1
  local win=120
  if [ "$mode" = "--auto" ]; then
    # --auto 不看提交时间(已合并+干净的 worktree 提交时间必然是旧的, 用它判断「在不在用」会误杀正在收尾的会话, 2026-11-11 实测删掉了当前会话自己的目录)。
    # 看会话活动标记: worktree-touch.sh 在 SessionStart/每条消息/每轮结束时 touch <gitdir>/wh-active; 标记在 WH_ACTIVE_MIN(默认 360)分钟内 = 有会话在用。
    local w="${WH_ACTIVE_MIN:-360}"
    if [ -e "$g/wh-active" ]; then
      [ -n "$(find "$g/wh-active" -mmin -"$w" 2>/dev/null)" ]; return
    fi
    # 没有标记 = Codex 会话(不跑 Claude hook)或 hook 上线前的旧会话: 退而看 HEAD / index 的 mtime(任一在窗口内就当有人在用)。
    # ⚠️ 本脚本只对「已判完成」的 worktree 才跑 git status, 所以不会把全部 worktree 的 index 刷成近期。
    [ -n "$(find "$g/HEAD" "$g/index" -mmin -"$w" 2>/dev/null)" ]
    return
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

# 会话工作目录保护 (见文件头)。
sess_dir="${WH_SESSIONS_DIR:-}"
if [ -z "$sess_dir" ]; then
  if [ -n "${APPDATA:-}" ]; then sess_dir="$APPDATA/Claude/claude-code-sessions"; else sess_dir="$HOME/Library/Application Support/Claude/claude-code-sessions"; fi
fi
sess_paths=""
if [ -d "$sess_dir" ]; then
  sess_files=0
  sess_paths=$(while IFS= read -r -d '' f; do
      grep -q '"isArchived":true' "$f" 2>/dev/null && continue
      grep -o '"\(cwd\|worktreePath\)":"[^"]*"' "$f" 2>/dev/null
    done < <(find "$sess_dir" -name 'local_*.json' -print0 2>/dev/null) \
    | sed 's/^"[A-Za-z]*":"//; s/"$//' | while IFS= read -r x; do norm "$x" | tr -s '/' | sed 's#/$##'; done | sort -u)
  sess_files=$(find "$sess_dir" -name 'local_*.json' 2>/dev/null | wc -l | tr -d ' ')
  if [ "$sess_files" = 0 ]; then
    echo "会话清单没量到 ($sess_dir 下没有会话文件)"
    [ "$mode" = "--auto" ] && { echo "--auto: 会话清单没量到, 本次不回收"; exit 0; }
  fi
else
  echo "会话清单不存在, 不按会话保护: $sess_dir"
fi
sess_in_use() { # 0 = 是某个没归档会话的工作目录 (或其上级)
  [ -n "$sess_paths" ] || return 1
  local p; p=$(norm "$1" | tr -s '/'); p="${p%/}"
  printf '%s\n' "$sess_paths" | awk -v p="$p" '$0==p || index($0, p"/")==1 {f=1} END{exit !f}'
}

removable=()
shell_candidates=(${shell_candidates[@]:-})
for p in "${paths[@]}"; do
  if is_keep "$p"; then echo "KEEP(保护)   $p"; continue; fi
  if echo "$locked_set" | grep -Fxq "$p"; then echo "KEEP(locked) $p"; continue; fi
  if sess_in_use "$p"; then echo "KEEP(会话在用) $p"; continue; fi
  if recent "$p"; then echo "KEEP(近期活动) $p"; continue; fi
  dirty=0; [ "$mode" = "--auto" ] || dirty=$(git -C "$p" status --porcelain 2>/dev/null | wc -l | tr -d ' ')   # --auto 先判完成再查脏(git status 在大树上要几秒)
  br=$(git -C "$p" branch --show-current 2>/dev/null)
  ahead=$(git -C "$p" rev-list --count origin/main..HEAD 2>/dev/null || echo '?')
  if [ "$mode" = "--auto" ]; then
    head_sha=$(git -C "$p" rev-parse HEAD 2>/dev/null)
    done_=0
    [ "$ahead" = 0 ] && done_=1
    # 按 HEAD 提交号匹配已合并 PR 的头, 不要求分支名一致: 合并后 worktree 常被切成 detached (2026-11-15 infallible-matsumoto 因此一直判未完成)
    [ -n "$head_sha" ] && echo "$merged_map" | awk -v s="$head_sha" '$2==s{f=1} END{exit !f}' && done_=1
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
  if in_use "$p"; then echo "KEEP(占用中) $p"; continue; fi   # 先问操作系统, 再动任何东西 (含 WIP commit)
  if [ -n "$(git -C "$p" status --porcelain)" ]; then
    br=$(git -C "$p" branch --show-current)
    [ -z "$br" ] && { echo "SKIP(detached 有改动) $p"; continue; }
    git -C "$p" add -A >/dev/null 2>&1
    git -C "$p" commit -q -m "WIP: worktree hygiene snapshot $(date +%F)" >/dev/null 2>&1 \
      && echo "WIPCOMMIT $br $(git -C "$p" rev-parse --short HEAD)" \
      || { echo "SKIP(commit 被 hook 拦, 未动) $p"; continue; }
  fi
  command -v powershell >/dev/null 2>&1 && powershell -NoProfile -Command "Get-ChildItem -LiteralPath '$p' -Recurse -Depth 4 -Directory -Force -EA SilentlyContinue | ?{\$_.Attributes -band 'ReparsePoint'} | %{ [IO.Directory]::Delete(\$_.FullName,\$false); 'unlinked '+\$_.FullName }"
  git -C "$main_wt" worktree remove "$p" 2>&1 && echo "REMOVED $p" || { echo "FAILED(留给人工) $p"; shell_candidates+=("$p"); }
done
git -C "$main_wt" worktree prune
[ "$mode" = "--auto" ] && sweep_shells
echo "收缩后: $(git -C "$main_wt" worktree list | wc -l) 个"
