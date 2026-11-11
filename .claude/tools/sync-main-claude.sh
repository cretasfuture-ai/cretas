#!/usr/bin/env bash
# 把主目录 checkout 的 .claude/ 对齐到 origin/main（只动 .claude/，只动工作树，不动 HEAD/index/分支/其他文件）。
#
# 为什么需要：Skill 工具按【主目录】的 .claude/skills 加载（不管会话 cwd 在哪个 worktree）。
# 主目录按规矩没人在里面干活、也没人 pull，长期落后 main（2026-11-11 落后 800+ 提交，
# organizer 加载成 9-11 的旧版 9003 字节，现行版 6278 字节）。
#
# 保护：某个文件在主目录里既不等于主目录 HEAD 版本、也不等于 origin/main 版本（= 有人本地改过）→ 跳过并报告，不覆盖。
# 永远 exit 0（挂在 SessionStart hook 上，不能拖垮会话）。
#
# 用法：bash .claude/tools/sync-main-claude.sh [--dry-run]

DRY=0
[ "$1" = "--dry-run" ] && DRY=1

export MSYS_NO_PATHCONV=1
common="$(git rev-parse --git-common-dir 2>/dev/null)" || exit 0
main_root="$(cd "$(dirname "$common")" 2>/dev/null && { pwd -W 2>/dev/null || pwd; })" || exit 0
[ -d "$main_root/.claude" ] || exit 0

G() { git -C "$main_root" "$@"; }

# 取最新 origin/main（失败就用本地已有的，不报错）
if command -v timeout >/dev/null 2>&1; then
  timeout 25 git -C "$main_root" fetch -q origin main 2>/dev/null
else
  git -C "$main_root" fetch -q origin main 2>/dev/null
fi
G rev-parse -q --verify origin/main >/dev/null || { echo "sync-main-claude: no origin/main, skip"; exit 0; }

# 主目录是 detached HEAD 且没有已跟踪文件的改动 → 整体跟到 origin/main（未跟踪文件不碰）。
# 有已跟踪改动（别人在里面干活）→ 不动，退回下面只对齐 .claude/ 的保守路径。
if [ -z "$(G symbolic-ref -q HEAD)" ] && [ -z "$(G status --porcelain --untracked-files=no)" ]; then
  if [ "$DRY" = 1 ]; then echo "would fast-forward main checkout to origin/main"; else
    G checkout -q --detach origin/main 2>/dev/null && { echo "sync-main-claude: main checkout -> $(G rev-parse --short HEAD)"; exit 0; }
  fi
fi

changed=0; skipped=0
while IFS=$'\t' read -r status path; do
  [ -z "$path" ] && continue
  f="$main_root/$path"
  head_blob="$(G rev-parse -q --verify "HEAD:$path" 2>/dev/null)"
  new_blob="$(G rev-parse -q --verify "origin/main:$path" 2>/dev/null)"
  if [ -f "$f" ]; then wt_blob="$(G hash-object -- "$path" 2>/dev/null)"; else wt_blob=""; fi

  [ "$wt_blob" = "$new_blob" ] && continue                       # 已经是最新
  if [ -n "$wt_blob" ] && [ "$wt_blob" != "$head_blob" ]; then   # 本地改过
    echo "sync-main-claude: SKIP local edit: $path"; skipped=$((skipped+1)); continue
  fi
  changed=$((changed+1))
  [ "$DRY" = 1 ] && { echo "would sync: $status $path"; continue; }
  if [ -z "$new_blob" ]; then
    rm -f -- "$f"
  else
    G restore --source=origin/main --worktree -- "$path" 2>/dev/null || echo "sync-main-claude: restore failed: $path"
  fi
done < <(G diff --name-status --no-renames HEAD origin/main -- .claude 2>/dev/null)

# 上一轮同步留下的、HEAD 与 origin/main 都没有变化但工作树已被覆盖的文件无需处理；
# 这里只处理 HEAD→origin/main 有差异的路径，这就是全部会过期的集合。
echo "sync-main-claude: main_root=$main_root synced=$changed skipped_local_edits=$skipped dry=$DRY"
exit 0
