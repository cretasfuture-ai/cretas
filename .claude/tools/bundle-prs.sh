#!/usr/bin/env bash
# bundle-prs.sh — 把同批低风险小改动拼成一个集成 PR (CI 只跑一次), 合并后批量关闭原 PR。
# Steve 2026-11-18 拍板「同批低风险小改动由 organizer 拼一个 PR, 红线不拼」; 规则见 organizer SKILL「拼包」。
#
# 用法 (organizer 在任意 worktree 里跑; 拼包在临时 worktree 里做, 不动当前目录, 不打断在跑的监视):
#   bash .claude/tools/bundle-prs.sh create --name <短名> --title "<标题>" [--dry-run] <PR|分支>...
#       参数可以是 PR 号 (取它的 head) 或 branch:<远端分支名> (执行线没开 PR、只推了分支)。
#       依次在 origin/main 上 merge --no-ff; 推到 bundle/<短名>; 开集成 PR, 描述里列出每项与 head (机读标记)。
#   bash .claude/tools/bundle-prs.sh close <集成PR号>
#       集成 PR 已 MERGED 后: 原 PR 的 head 仍等于拼进去的那个 → 关闭并留言指向集成 PR;
#       head 变了 (拼完又推了新提交) → 不关, 报 DRIFT, 新提交要另行处理。
# 拒绝 (rc 4, 一项都不拼): 原 PR 不是 OPEN / 来自 fork / 有失败或取消的检查 / 碰了红线路径。
#   红线路径: 迁移、entity、repository、security、workflows、scripts/deploy、AGENTS.md、.agents/、.codex/
#   (与 worktree-and-main-only-deploy「强制 PR」同一口径) —— 这些各自单独 PR、单独终审。
# 退出码 / 最后一行 RESULT=:
#   0 BUNDLED / DRY_RUN_OK / CLOSED      3 CONFLICT (列出与谁冲突, 不推送)
#   1 DRIFT (close 时有原 PR 没关)        4 REFUSED (列出原因)      2 用法或环境错误
set -u
GH="${BUNDLE_GH:-gh}"
REMOTE="${BUNDLE_REMOTE:-origin}"
BASE="${BUNDLE_BASE:-main}"
REDLINE='(^|/)db/migration[^/]*/|/entity/|/repository/|/security/|^\.github/workflows/|^scripts/deploy/|^AGENTS\.md$|^\.agents/|^\.codex/'

die() { echo "RESULT=$1 ${2:-}"; exit "$3"; }

cmd="${1:-}"; shift || true
case "$cmd" in
create)
  NAME=""; TITLE=""; DRY=0; ITEMS=()
  while [ $# -gt 0 ]; do
    case "$1" in
      --name) NAME="$2"; shift 2;;
      --title) TITLE="$2"; shift 2;;
      --dry-run) DRY=1; shift;;
      -*) die USAGE "unknown arg $1" 2;;
      *) ITEMS+=("$1"); shift;;
    esac
  done
  [[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || die USAGE "--name 只能用字母数字._-" 2
  [ -n "$TITLE" ] || die USAGE "--title 必填" 2
  [ "${#ITEMS[@]}" -ge 2 ] || die USAGE "至少两项才值得拼" 2

  git fetch -q "$REMOTE" "$BASE" || die USAGE "fetch $REMOTE/$BASE failed" 2
  base_sha=$(git rev-parse "$REMOTE/$BASE")

  # 1) 逐项取 head、做门禁 (全部过了才开始 merge)
  LABELS=(); SHAS=(); TITLES=(); refuse=""
  for it in "${ITEMS[@]}"; do
    if [[ "$it" =~ ^[0-9]+$ ]]; then
      info=$("$GH" pr view "$it" --json state,headRefOid,isCrossRepository,title --jq '.state+"\t"+.headRefOid+"\t"+(.isCrossRepository|tostring)+"\t"+.title' 2>&1) \
        || die USAGE "pr view #$it failed: ${info:0:80}" 2
      IFS=$'\t' read -r st sha cross title <<<"$info"
      [ "$st" = OPEN ] || refuse="$refuse #$it:state=$st"
      [ "$cross" = false ] || refuse="$refuse #$it:fork"
      bad=$("$GH" pr checks "$it" --json bucket,name --jq '.[]|select(.bucket=="fail" or .bucket=="cancel")|.name' 2>/dev/null | paste -sd, -)
      [ -n "$bad" ] && refuse="$refuse #$it:red($bad)"
      git fetch -q "$REMOTE" "pull/$it/head" || die USAGE "fetch pull/$it/head failed" 2
      [ "$(git rev-parse FETCH_HEAD)" = "$sha" ] || refuse="$refuse #$it:head-moved-while-fetching"
      label="#$it"
    elif [[ "$it" =~ ^branch:([A-Za-z0-9._/-]+)$ ]]; then
      br="${BASH_REMATCH[1]}"
      git fetch -q "$REMOTE" "$br" || die USAGE "fetch branch $br failed" 2
      sha=$(git rev-parse FETCH_HEAD); title=$(git log -1 --format=%s "$sha"); label="branch:$br"
    else
      die USAGE "不认识的项: $it (要 PR 号或 branch:<名>)" 2
    fi
    red=$(git diff --name-only "$base_sha...$sha" | grep -E "$REDLINE" | head -3 | paste -sd, -)
    [ -n "$red" ] && refuse="$refuse $label:redline($red)"
    LABELS+=("$label"); SHAS+=("$sha"); TITLES+=("$title")
  done
  [ -n "$refuse" ] && die REFUSED "$refuse" 4

  # 2) 临时 worktree 里依次 merge
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/bundle-$NAME.XXXXXX")
  wt="$tmp/wt"
  git worktree add -q --detach "$wt" "$base_sha" || die USAGE "worktree add failed" 2
  cleanup() { git worktree remove --force "$wt" >/dev/null 2>&1; rm -rf "$tmp"; }
  trap cleanup EXIT
  merged=()
  for k in "${!SHAS[@]}"; do
    if ! git -C "$wt" merge -q --no-ff --no-edit -m "bundle($NAME): ${LABELS[$k]} ${TITLES[$k]}" "${SHAS[$k]}" >"$tmp/merge.log" 2>&1; then
      files=$(git -C "$wt" diff --name-only --diff-filter=U | head -5 | paste -sd, -)
      git -C "$wt" merge --abort >/dev/null 2>&1
      die CONFLICT "item=${LABELS[$k]} files=${files:-?} after=[${merged[*]:-base}]" 3
    fi
    merged+=("${LABELS[$k]}")
  done
  head=$(git -C "$wt" rev-parse HEAD)

  # 3) 描述 (机读标记供 close 用)
  body="$tmp/body.md"
  {
    echo "同批低风险小改动集成 PR（organizer 用 bundle-prs.sh 拼包；CI 只跑这一次）。合并后执行 \`bash .claude/tools/bundle-prs.sh close <本PR号>\` 关闭原 PR。"
    echo
    echo "基于 $BASE \`${base_sha:0:10}\`，依次 merge --no-ff（不改各自提交）："
    for k in "${!SHAS[@]}"; do echo "- ${LABELS[$k]} ${TITLES[$k]}（head \`${SHAS[$k]:0:10}\`）"; done
    echo
    echo "红线路径检查：无（迁移/entity/repository/security/workflows/scripts/deploy 不进拼包）。"
    echo "测试：各项自己的目标检查见原 PR / 执行线回执；组合结果以本 PR CI 为准。红了：按失败检查对应的改动剔除那一项重拼。"
    echo
    for k in "${!SHAS[@]}"; do echo "<!-- bundle-item: ${LABELS[$k]} ${SHAS[$k]} -->"; done
    echo
    echo "🤖 Generated with [Claude Code](https://claude.com/claude-code)"
  } > "$body"

  if [ "$DRY" = 1 ]; then
    cat "$body"; die DRY_RUN_OK "head=$head items=${#SHAS[@]}" 0
  fi
  git -C "$wt" push -q "$REMOTE" "HEAD:refs/heads/bundle/$NAME" || die USAGE "push bundle/$NAME failed (已存在? 换 --name)" 2
  url=$("$GH" pr create --base "$BASE" --head "bundle/$NAME" --title "$TITLE" --body-file "$body" 2>&1) || die USAGE "pr create failed: ${url:0:120}" 2
  die BUNDLED "pr=$url head=$head items=${#SHAS[@]}" 0
  ;;

close)
  B="${1:-}"; [[ "$B" =~ ^[0-9]+$ ]] || die USAGE "close <集成PR号>" 2
  info=$("$GH" pr view "$B" --json state,body --jq '.state+"\n"+.body' 2>&1) || die USAGE "pr view #$B failed" 2
  st=$(head -1 <<<"$info")
  [ "$st" = MERGED ] || die USAGE "集成 PR #$B 还没合并 (state=$st), 不关原 PR" 2
  drift=""; closed=""
  while read -r label sha; do
    [[ "$label" =~ ^#([0-9]+)$ ]] || continue   # branch: 项没有 PR 可关
    n="${BASH_REMATCH[1]}"
    cur=$("$GH" pr view "$n" --json state,headRefOid --jq '.state+" "+.headRefOid' 2>&1)
    read -r s2 h2 <<<"$cur"
    if [ "$s2" != OPEN ]; then continue; fi
    if [ "$h2" != "$sha" ]; then drift="$drift #$n"; continue; fi
    "$GH" pr close "$n" --comment "已并入集成 PR #$B 合并（拼入 head ${sha:0:10}）。" >/dev/null 2>&1 && closed="$closed #$n" || drift="$drift #$n(close-failed)"
  done < <(sed -nE 's/^<!-- bundle-item: (\S+) ([0-9a-f]{40}) -->$/\1 \2/p' <<<"$info")
  [ -n "$drift" ] && die DRIFT "closed=[${closed# }] not-closed=[${drift# }] (拼包后又推了提交, 新提交另开 PR 或下一包)" 1
  die CLOSED "bundle=#$B closed=[${closed# }]" 0
  ;;
*)
  echo "usage: bundle-prs.sh create --name <短名> --title <标题> [--dry-run] <PR|branch:名>... | close <集成PR号>" >&2; exit 2;;
esac
