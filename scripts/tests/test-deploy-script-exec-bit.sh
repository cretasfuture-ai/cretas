#!/usr/bin/env bash
# 发布链上被【直接执行】的脚本, 在 git 索引里必须有执行位。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要它 (2026-09-06 实测)
# ═══════════════════════════════════════════════════════════════════════════
# `scripts/tests/` 第一次接进 CI 的那天, test-release-parallel-fastlane.sh 在
# ubuntu runner 上报 **rc=126 Permission denied** —— `scripts/deploy/release-java-
# preflight.sh` 在 git 里是 100644。而同一棵树在 Windows Git Bash 上是绿的:
# Windows 没有 POSIX 执行位, Git 把工作区里的每个文件都当成可执行的。
#
# 🔴 这不只是测试的事: `release-cretas.sh` 有 **3 个**调用点、
# `release-cretas-artifacts.sh` 有 1 个, 全都是
#     "$SCRIPT_DIR/release-java-preflight.sh" --repo-root ... --tests ...
# 这种直接执行的形状。在任何 POSIX 检出上(Linux 操作机、macOS、自建 runner),
# 整条发布在预检那一步就 126 死掉。它至今没咬人, 只是因为发布一直从 Windows 跑。
#
# ⇒ 这类缺陷在开发机上**永远看不见**, 判据只能问 git 索引, 不能问工作区。
#
# ── 判据的取材范围: 刻意窄 (形态 E: 宁可窄而可信, 不要宽而被关掉) ──────────
# 只认 `scripts/deploy/*.sh` 里处于**命令位**的 `"$SCRIPT_DIR/<x>.sh"` 这一种形状。
# 试过更宽的启发式(按 basename 反查全 scripts/), 实测 13 个命中里 **7 个是假阳性**:
#   · rsync 的**参数**            (deploy-smartbi-python.sh 同步 ops scripts, 且它
#                                   在服务器上另外 chmod +x, 本地执行位无关)
#   · `cat f1 f2 f3` 的参数        (cron wrapper 反查那段)
#   · `ssh host bash <路径>`       (显式 bash, 不需要执行位)
#   · 脚本自己 usage 文本里的 `./x.sh`
# 一个会误报的闸比没有更糟(形态 D′), 所以这里只留能一眼判定的那一种。
# ⛔ 代价登记在案: `source`/`. ` 进来的、`bash x.sh` 调的、以及文档里写
#    `./scripts/deploy/x.sh` 的操作者入口, 本闸**都不管**。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT_DIR"

fail() { echo "FAIL: $*" >&2; exit 1; }

# 前提: 判据问的是 **git 索引里的 mode**, 所以没有 git / 不在工作树里就是
# **这次量不了**, ⛔ 既不是「全都合规」也不是「有人少了执行位」(硬约束 4 的三态)。
# ⇒ rc=77 + `SKIPPED:` 由 scripts/ci/run-script-tests.sh 判成 SKIPPED(不计进 PASS)。
# 实测会走到这里的场景: 从 tar 解出来的树、或 vendored 副本。CI 的 actions/checkout
# 一定是工作树, 所以那边不会跳。
if ! command -v git >/dev/null 2>&1; then
    echo "SKIPPED:没有 git, 问不到索引里的 mode(判据只能问索引, ⛔ 不能问工作区)"
    exit 77
fi
if ! git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "SKIPPED:$ROOT_DIR 不在 git 工作树里(例如从 tar 解出来的副本), 索引 mode 无从谈起"
    exit 77
fi

# ── 采集: 命令位上的 "$SCRIPT_DIR/<x>.sh" ────────────────────────────────
# 前缀允许 `if ` / `! ` / `&& ` / `|| ` —— 这些之后仍然是命令位。
# ⛔ `source` / `.` / `[ -f` / `echo "..."` 都不以这个形状开头, 天然排除。
CALL_RE='^[[:space:]]*(if[[:space:]]+)?(![[:space:]]*)?((&&|\|\|)[[:space:]]+)?"\$SCRIPT_DIR/[A-Za-z0-9._-]+\.sh"'
callees=$(grep -hoE "$CALL_RE" scripts/deploy/*.sh 2>/dev/null \
    | sed -E 's#.*/##; s/"$//' | LC_ALL=C sort -u || true)
callee_count=$(printf '%s' "$callees" | grep -c . || true)

# 硬约束 9: 「没有缺执行位的」必须配一个「一定会发生」的读数 —— 采集为空时,
# 「全部合规」和「正则一个都没匹配上」长得一模一样。
[ "$callee_count" -ge 5 ] \
    || fail "只采到 $callee_count 个直接执行的被调脚本 —— 正则大概率失效了, 本次读数作废"
echo "DIRECTLY_EXECUTED_CALLEES=$callee_count"
printf '%s\n' "$callees" | sed 's/^/  · /'

offenders=""
checked=0
while IFS= read -r base; do
    [ -n "$base" ] || continue
    path="scripts/deploy/$base"
    entry=$(git -C "$ROOT_DIR" ls-files -s -- "$path" || true)
    [ -n "$entry" ] || fail "$path 被直接执行, 但它不在 git 索引里"
    mode=${entry%% *}
    checked=$((checked + 1))
    [ "$mode" = "100755" ] || offenders="$offenders$path(mode=$mode) "
done <<< "$callees"

echo "CHECKED=$checked"
[ "$checked" -eq "$callee_count" ] \
    || fail "census_mismatch: 采到 $callee_count 个却只查了 $checked 个, 本次读数作废"

if [ -n "$offenders" ]; then
    echo "FAIL: 下列脚本在发布链上被直接执行, 但 git 索引里没有执行位:" >&2
    for o in $offenders; do echo "  - $o" >&2; done
    echo "  ⇒ POSIX 检出上会 rc=126 Permission denied; Windows 检出**看不出来**。" >&2
    echo "  ⇒ 修法: git update-index --chmod=+x <path> && git commit" >&2
    exit 1
fi

echo "PASS: 发布链上 $checked 个被直接执行的脚本在 git 索引里都有执行位"
