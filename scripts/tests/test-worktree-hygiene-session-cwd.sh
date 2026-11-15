#!/usr/bin/env bash
# 闸: worktree-hygiene.sh 不得删除「没归档会话」的工作目录。
# 2026-11-15: --auto 只看 6 小时活动标记, 删掉了置顶会话「产品亮点与BP叙事」的 worktree (会话几天没说话, 但没归档)。
# 修复: 读 Claude 应用的会话清单 (WH_SESSIONS_DIR 可覆盖), 没归档会话的 cwd / worktreePath (含子目录) 一律 KEEP(会话在用)。
#
# 判据 (临时仓库里三个都「已完成 + 干净」的 worktree):
#   wt-live: 有一个没归档会话, cwd 是它的子目录          → KEEP(会话在用), 目录还在
#   wt-arch: 只有一个已归档会话指着它                    → REMOVED (阳性对照: 已归档的不保护, 脚本确实会删)
#   wt-free: 没有会话指着它                              → REMOVED (阳性对照)
# 另验三态: 会话目录在但没有会话文件 → --auto 打印「没量到」且不删任何东西。
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/../../.claude/tools/worktree-hygiene.sh"
[ -f "$script" ] || { echo "not ok - 找不到 $script"; exit 2; }

tmp=$(mktemp -d) || exit 2
trap 'rm -rf "$tmp" 2>/dev/null' EXIT
win() { (cd "$1" 2>/dev/null && pwd -W 2>/dev/null) || echo "$1"; }   # Windows 上 git 报的是 C:/... 形式

repo="$tmp/main"
git init -q -b main "$repo" || exit 2
git -C "$repo" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m init || exit 2
mkdir -p "$repo/.claude/tools" && cp "$script" "$repo/.claude/tools/worktree-hygiene.sh"
git -C "$repo" add -A && git -C "$repo" -c user.name=t -c user.email=t@example.invalid commit -q -m tools || exit 2
git -C "$repo" update-ref refs/remotes/origin/main HEAD   # 三个 worktree 的 HEAD 都是它 ⇒ 已完成

for w in wt-live wt-arch wt-free; do git -C "$repo" worktree add -q --detach "$tmp/$w" HEAD || exit 2; done
mkdir -p "$tmp/wt-live/sub"

sess="$tmp/sessions/acct/ws"; mkdir -p "$sess"
bs='\\'   # JSON 里路径分隔符是转义的反斜杠 (两个字符), 与真实会话文件同形; 不走管道, 免得吞掉退出码
live_cwd=$(win "$tmp/wt-live/sub") || exit 2; live_cwd=${live_cwd//\//$bs}
arch_cwd=$(win "$tmp/wt-arch") || exit 2; arch_cwd=${arch_cwd//\//$bs}
printf '{"sessionId":"local_live","cwd":"%s","isArchived":false}\n' "$live_cwd" > "$sess/local_live.json"
printf '{"sessionId":"local_arch","cwd":"%s","isArchived":true}\n' "$arch_cwd" > "$sess/local_arch.json"

out="$tmp/out.log"
( cd "$repo" && WH_NO_RECENT=1 WH_SESSIONS_DIR="$tmp/sessions" bash .claude/tools/worktree-hygiene.sh --apply ) >"$out" 2>&1
rc=$?
fail=0
grep -Fq "KEEP(会话在用)" "$out" && grep "KEEP(会话在用)" "$out" | grep -q "wt-live" \
  || { echo "not ok - 没归档会话的 worktree 没有判为会话在用"; fail=1; }
[ -d "$tmp/wt-live/sub" ] && echo "ok - 没归档会话的 worktree 还在" || { echo "not ok - 没归档会话的 worktree 被删了"; fail=1; }
[ -d "$tmp/wt-arch" ] && { echo "not ok - 阳性对照: 只有已归档会话指着的 worktree 没被删"; fail=1; } || echo "ok - 阳性对照: 已归档会话的 worktree 被删了"
[ -d "$tmp/wt-free" ] && { echo "not ok - 阳性对照: 没有会话的 worktree 没被删"; fail=1; } || echo "ok - 阳性对照: 没有会话的 worktree 被删了"
[ "$rc" = 0 ] || { echo "not ok - --apply rc=$rc"; fail=1; }

# 三态: 会话目录存在但为空 ⇒ --auto 没量到, 不回收。--auto 先问 gh 要已合并列表, 这里放一个假 gh 让它走到会话清单这一步
git -C "$repo" worktree add -q --detach "$tmp/wt-empty" HEAD || exit 2
mkdir -p "$tmp/empty-sessions" "$tmp/bin"
printf '#!/usr/bin/env bash\necho "fake-branch 0000000000000000000000000000000000000000"\n' > "$tmp/bin/gh" && chmod +x "$tmp/bin/gh"
out2="$tmp/out2.log"
( cd "$repo" && PATH="$tmp/bin:$PATH" WH_BG=1 WH_SESSIONS_DIR="$tmp/empty-sessions" bash .claude/tools/worktree-hygiene.sh --auto ) >"$out2" 2>&1
grep -Fq "会话清单没量到, 本次不回收" "$out2" && [ -d "$tmp/wt-empty" ] \
  && echo "ok - 会话清单没量到时 --auto 不回收" || { echo "not ok - 会话清单没量到时 --auto 仍可能回收"; fail=1; }

if [ "$fail" != 0 ]; then echo "--- --apply 输出 ---"; cat "$out"; echo "--- --auto 输出 ---"; cat "$out2"; echo FAIL; exit 1; fi
echo PASS
