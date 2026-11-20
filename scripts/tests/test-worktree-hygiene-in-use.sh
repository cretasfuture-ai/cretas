#!/usr/bin/env bash
# 闸: worktree-hygiene.sh 删除前必须先问操作系统「这个目录有没有进程在用」。
# 2026-11-11: 回收把正在运行的会话目录当成「已完成」删掉 —— git worktree remove 先删文件、最后删目录,
# 结果文件全没了、只剩一个被占用删不掉的空壳。修复是删除前的改名探测 (Windows 上被占用的目录不能改名)。
#
# 判据 (临时仓库里两个都「已完成 + 干净」的 worktree, 其中一个有进程把当前目录放在它的子目录里):
#   被占用的 → KEEP(占用中), 文件一个不少;  空闲的 → REMOVED (阳性对照: 证明脚本确实会删, 不是什么都不删)。
# 改名探测只在 Windows 上有意义; 其他平台上「进程当前目录」不挡改名, 这里如实报 SKIP (rc=0) 而不是假装通过。
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/../../.claude/tools/worktree-hygiene.sh"
[ -f "$script" ] || { echo "not ok - 找不到 $script"; exit 2; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *) echo "SKIP: 改名占用探测只在 Windows 上生效 (uname=$(uname -s)); 本闸不在此平台判定"; exit 0;;
esac

tmp=$(mktemp -d) || exit 2
holder_pid=""
cleanup() { [ -n "$holder_pid" ] && kill "$holder_pid" 2>/dev/null; wait 2>/dev/null; rm -rf "$tmp" 2>/dev/null; }
trap cleanup EXIT

repo="$tmp/main"
git init -q -b main "$repo" || exit 2
git -C "$repo" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m init || exit 2
mkdir -p "$repo/.claude/tools" && cp "$script" "$repo/.claude/tools/worktree-hygiene.sh"
git -C "$repo" add -A && git -C "$repo" -c user.name=t -c user.email=t@example.invalid commit -q -m tools || exit 2
# origin/main 指向本地 main: 两个 worktree 的 HEAD 都是它的祖先 ⇒ 判为「已完成」
git -C "$repo" update-ref refs/remotes/origin/main HEAD

busy="$tmp/wt-busy"; free="$tmp/wt-free"
git -C "$repo" worktree add -q --detach "$busy" HEAD || exit 2
git -C "$repo" worktree add -q --detach "$free" HEAD || exit 2
mkdir -p "$busy/sub" && echo keep-me > "$busy/sub/marker.txt"
git -C "$busy" -c user.name=t -c user.email=t@example.invalid add -A >/dev/null
# marker 是未跟踪还是已提交都行; 这里提交掉让工作树「干净」, 只剩占用这一个保护理由
git -C "$busy" -c user.name=t -c user.email=t@example.invalid commit -q -m marker && git -C "$repo" update-ref refs/remotes/origin/main "$(git -C "$busy" rev-parse HEAD)"

# 进程把当前目录放在被占用 worktree 的子目录里 (会话常见的样子)
( cd "$busy/sub" && exec sleep 120 ) & holder_pid=$!
sleep 2

out="$tmp/out.log"
( cd "$repo" && WH_NO_RECENT=1 bash .claude/tools/worktree-hygiene.sh --apply ) >"$out" 2>&1
rc=$?
fail=0
grep -Fq "KEEP(占用中) $busy" "$out" || grep -Fq "KEEP(占用中) $(cd "$busy" 2>/dev/null && pwd -W)" "$out" \
  || { echo "not ok - 被占用的 worktree 没有判为占用中"; fail=1; }
[ -f "$busy/sub/marker.txt" ] && echo "ok - 被占用的 worktree 文件一个不少" || { echo "not ok - 被占用的 worktree 文件被删了"; fail=1; }
[ -d "$free" ] && { echo "not ok - 阳性对照: 空闲的 worktree 没被删 (脚本可能什么都不删)"; fail=1; } || echo "ok - 阳性对照: 空闲的 worktree 被删了"
[ "$rc" = 0 ] || { echo "not ok - 脚本 rc=$rc"; fail=1; }
if [ "$fail" != 0 ]; then echo "--- 脚本输出 ---"; cat "$out"; echo FAIL; exit 1; fi
echo PASS
