#!/usr/bin/env bash
# sync-main-claude.sh 回归：上一轮同步留下的旧 origin/main 版本要继续同步，真正的本地改动要跳过。
# 用法: bash .claude/tools/__tests__/test-sync-main-claude.sh   (退出码 0 = 全过)
set -u
SCRIPT="$(cd "$(dirname "$0")/.." && pwd)/sync-main-claude.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0; pass=0
ok()  { pass=$((pass+1)); echo "PASS $1"; }
bad() { fail=$((fail+1)); echo "FAIL $1"; }
g() { git -c user.email=t@t -c user.name=t "$@"; }

g init -q --bare "$T/origin.git"
g clone -q "$T/origin.git" "$T/main" 2>/dev/null
cd "$T/main" || exit 2
mkdir -p .claude/skills
printf 'v1\n' > .claude/skills/stale.md
printf 'v1\n' > .claude/skills/local.md
printf 'v1\n' > .claude/skills/clean.md
g add -f .claude && g commit -qm A && g branch -M main && g push -q origin main
# 主目录 HEAD 停在 A；origin/main 往前走两版
g clone -q -b main "$T/origin.git" "$T/up" 2>/dev/null
( cd "$T/up" && for v in v2 v3; do for f in stale local clean; do printf '%s\n' "$v" > .claude/skills/$f.md; done; g commit -qam "$v"; done; g push -q origin main )
g fetch -q origin main
printf 'v2\n' > .claude/skills/stale.md        # 上一轮同步写进来的 v2（旧一版 origin/main）
printf 'mine\n' > .claude/skills/local.md      # 真正的本地改动
# clean.md 保持 HEAD 版 v1

out="$(bash "$SCRIPT" 2>&1)"
[ "$(cat .claude/skills/stale.md)" = v3 ] && ok "上轮同步留下的旧 main 版 -> 同步到最新" || bad "stale.md=$(cat .claude/skills/stale.md) (want v3)"
[ "$(cat .claude/skills/local.md)" = mine ] && ok "本地改动 -> 不覆盖" || bad "local.md 被覆盖"
echo "$out" | grep -q "SKIP local edit: .claude/skills/local.md" && ok "本地改动 -> 报 SKIP" || bad "没报 SKIP local.md"
[ "$(cat .claude/skills/clean.md)" = v3 ] && ok "HEAD 版 -> 同步到最新" || bad "clean.md=$(cat .claude/skills/clean.md)"
echo "$out" | grep -q "synced=2 skipped_local_edits=1" && ok "计数 synced=2 skipped=1" || bad "计数不对: $out"
[ "$(g rev-parse HEAD)" = "$(g rev-parse origin/main~2)" ] && ok "HEAD 没动" || bad "HEAD 被改"

echo "pass=$pass fail=$fail"
[ "$fail" = 0 ]
