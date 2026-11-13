#!/usr/bin/env bash
# 闸: worktree-hygiene.sh --auto 遇到删不掉的空壳 (目录被进程占用) 只在首次逐条报, 之后只报总数; 释放后能删掉并从清单移除。
# 2026-11-13 8 小时检查: 3 个空壳各被逐条报约 90 次, 日志里「空壳仍被占用」1811 行。
# 判据: 第 1 轮 首次行=1; 第 2 轮 首次行=0 且总数行=1 (Y 一定会发生: 总数行证明第 2 轮真的跑到了 sweep); 释放后第 3 轮 SWEPT=1 且清单为空。
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/../../.claude/tools/worktree-hygiene.sh"
[ -f "$script" ] || { echo "not ok - 找不到 $script"; exit 2; }
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *) echo "SKIP: 目录占用挡 rmdir 只在 Windows 上成立 (uname=$(uname -s))"; exit 0;;
esac

tmp=$(mktemp -d) || exit 2
holder_pid=""
cleanup() { [ -n "$holder_pid" ] && kill "$holder_pid" 2>/dev/null; wait 2>/dev/null; rm -rf "$tmp" 2>/dev/null; }
trap cleanup EXIT

repo="$tmp/main"
git init -q -b main "$repo" || exit 2
git -C "$repo" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m init || exit 2
mkdir -p "$repo/.claude/tools" && cp "$script" "$repo/.claude/tools/worktree-hygiene.sh"
shell="$repo/.claude/worktrees/held-shell"
mkdir -p "$shell"
# 必须是 Windows 原生进程: MSYS 的 sleep 只模拟当前目录, 不持有目录句柄, 挡不住 rmdir (实测)
wshell=$(cd "$shell" && pwd -W)
powershell.exe -NoProfile -Command "[Environment]::CurrentDirectory='$wshell'; Set-Location -LiteralPath '$wshell'; Start-Sleep 60" & holder_pid=$!
sleep 2

run() { ( cd "$repo" && WH_BG=1 bash .claude/tools/worktree-hygiene.sh --auto ) >"$tmp/$1.log" 2>&1; }
cnt() { grep -c "$2" "$tmp/$1.log"; }
fail=0
run r1; run r2
[ "$(cnt r1 '空壳仍被占用(首次)')" = 1 ] && echo "ok - 第 1 轮首次逐条报" || { echo "not ok - 第 1 轮应有 1 行首次报"; fail=1; }
[ "$(cnt r2 '空壳仍被占用(首次)')" = 0 ] && echo "ok - 第 2 轮不再逐条报" || { echo "not ok - 第 2 轮重复逐条报"; fail=1; }
[ "$(cnt r2 '空壳仍被占用 共 1 个')" = 1 ] && echo "ok - 第 2 轮只报总数 (证明跑到了 sweep)" || { echo "not ok - 第 2 轮没有总数行 (sweep 可能没跑)"; fail=1; }
kill "$holder_pid" 2>/dev/null; wait "$holder_pid" 2>/dev/null; holder_pid=""; sleep 1
run r3
[ "$(cnt r3 'SWEPT 空壳')" = 1 ] && [ ! -d "$shell" ] && echo "ok - 释放后删掉了" || { echo "not ok - 释放后没删"; fail=1; }
common=$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir)
[ ! -s "$common/worktree-hygiene.held-shells" ] && echo "ok - 清单已清空" || { echo "not ok - 清单没清"; fail=1; }
if [ "$fail" != 0 ]; then for f in r1 r2 r3; do echo "--- $f ---"; cat "$tmp/$f.log"; done; echo FAIL; exit 1; fi
echo PASS
