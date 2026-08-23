#!/usr/bin/env bash
# 离线期恢复清单 —— GitHub 账号恢复那天现算, ⛔ 不要读手写台账
#
# ## 为什么要现算
# 手写台账记的是「写它那一刻」的状态。2026-08-18 晚那份列了 4 条本地分支,
# 到 08-19 上午实际已经有 27 条合进本地 main —— 台账不会自己更新, 照它做会漏掉 23 条。
#
# ## 为什么不能用 `git branch --format='%(ahead-behind:origin/main)'` 筛
# 那个数是「不在 origin/main 里的 commit 数」。**squash 合并让老分支的原始 commit
# 永远不是 origin/main 的祖先**, 所以几周前早就合过的僵尸分支同样显示为 ahead。
# 实测：那样筛出 869 条, 而真正在离线期动过的只有 28 条。
# ⇒ 判据必须是**提交时间晚于 origin/main 冻结时刻**。
#
# 用法:  bash offline-recovery-manifest.sh            # 只看清单
#        bash offline-recovery-manifest.sh --commands # 顺带打出 push/PR 命令

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

FREEZE_SHA=$(git rev-parse --short origin/main)
FREEZE_AT=$(git log -1 --format=%cI origin/main)
AHEAD=$(git rev-list --count origin/main..main)

echo "════════ 离线期恢复清单 ════════"
echo "origin/main 冻结于 : $FREEZE_SHA  ($FREEZE_AT)"
echo "本地 main 领先     : $AHEAD 个 commit"
echo "涉及文件           : $(git diff --name-only origin/main..main | wc -l) 个"
echo "  碰运行路径       : $(git diff --name-only origin/main..main | grep -cE '^(backend|web-admin|frontend)/' || true) 个 (按双轨规则必须走 PR)"
echo ""

MERGED=$(git branch --merged main --format='%(refname:short)' | sort)
FRESH=$(git for-each-ref refs/heads/ --format='%(committerdate:iso8601-strict)|%(refname:short)' \
        | awk -F'|' -v f="$FREEZE_AT" '$1 > f {print $2}' | grep -vx main | sort)

echo "──── A. 已合进本地 main, 恢复后逐条 push + 开 PR ────"
A_LIST=""
while read -r b; do
  [ -z "$b" ] && continue
  if echo "$MERGED" | grep -qx "$b"; then
    n=$(git rev-list --count "origin/main..$b")
    printf "  %-46s %4s commits\n" "$b" "$n"
    A_LIST="$A_LIST $b"
  fi
done <<< "$FRESH"
echo "  小计: $(echo $A_LIST | wc -w) 条"
echo ""

echo "──── B. 仍在飞(未合入 main), 先让它自己完成 ────"
while read -r b; do
  [ -z "$b" ] && continue
  if ! echo "$MERGED" | grep -qx "$b"; then
    printf "  %-46s  最后提交 %s\n" "$b" \
      "$(git log -1 --format='%cd' --date=format:'%m-%d %H:%M' "$b")"
  fi
done <<< "$FRESH"
echo ""

echo "──── C. 离线期部署过什么(台账文件, 恢复后逐条核销) ────"
git log origin/main..main --name-only --format='' | sort -u \
  | grep -iE 'offline|deploy.*ledger|ledger.*deploy' | sed 's/^/  /' || echo "  (无)"
echo ""

if [ "${1:-}" = "--commands" ]; then
  echo "──── 恢复日的命令(逐条执行, ⛔ 不要 git push origin main) ────"
  for b in $A_LIST; do
    echo "git push -u origin $b && gh pr create --fill --head $b"
  done
  echo ""
  echo "# 全部合完之后, 再让本地 main 对齐:"
  echo "git checkout main && git reset --hard origin/main"
fi

echo "⛔ 不要 git push origin main —— 那会把 $AHEAD 个 commit 绕过 CI 一次性推上去,"
echo "   而其中 $(git diff --name-only origin/main..main | grep -cE '^(backend|web-admin|frontend)/' || true) 个文件在强制 PR 的路径上。"
