#!/usr/bin/env bash
# Java 闸必须跑在【PR 合进 base 之后】的树上, 不是 PR 分支自己。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (票 CI-JAVA-GATE-CHECKS-PR-HEAD-NOT-MERGE-RESULT-20260907)
# ═══════════════════════════════════════════════════════════════════════════
# 三处 checkout 都写死 `ref: ${{ github.event.pull_request.head.sha }}`, 覆盖了 GitHub
# 默认的 merge ref。实测(#693): full-suite 的 run_head 恰好等于 PR head; #682 在该 run
# 触发前 31 秒合进 main, 而那次日志里 #682 新增的类出现 0 次。
#
# 本仓大量契约闸是【全仓扫描型】(软删棘轮 / 源码闸 / 模块清单闸 / NUL 字节闸), 作用域是
# 整棵树 —— 树给错了, 闸就是瞎的, 而且「瞎着的绿」与「真的没问题」读数完全相同。
#
# 本测试两部分:
#   A. 行为: 把 action 里那段 merge 脚本【真的抽出来跑】在临时 git 仓库上(正例 + 冲突 +
#      base 缺失三种), ⛔ 不是 grep 一下就算数。
#   B. 结构: 三处 pin 都必须配 merge action + fetch-depth: 0; action 不许写死 main。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ACTION="$ROOT_DIR/.github/actions/merge-pr-base/action.yml"
GATE="$ROOT_DIR/.github/workflows/java-gate-pr.yml"
NIGHTLY="$ROOT_DIR/.github/workflows/java-full-nightly.yml"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$ACTION" ] || fail "$ACTION 不存在"

# ---------------------------------------------------------------------------
# A. 行为: 抽出 action 里的 run: 脚本, 真跑
# ---------------------------------------------------------------------------
SCRIPT="$TMP/merge-step.sh"
python - "$ACTION" "$SCRIPT" <<'PY'
import sys, textwrap
src, dst = sys.argv[1], sys.argv[2]
lines = open(src, encoding="utf-8").read().splitlines()
i = next(k for k, l in enumerate(lines) if l.strip() == "run: |")
indent = len(lines[i]) - len(lines[i].lstrip())
body = []
for l in lines[i + 1:]:
    if l.strip() and (len(l) - len(l.lstrip())) <= indent:
        break
    body.append(l)
text = textwrap.dedent("\n".join(body)).rstrip() + "\n"
assert "git merge" in text, "抽出来的不是那段 merge 脚本"
open(dst, "w", encoding="utf-8", newline="\n").write(text)
print(f"READING: EXTRACTED_LINES={len(body)}")
PY
[ -s "$SCRIPT" ] || fail "没能从 action.yml 抽出 run 脚本"

# 临时仓库: base 上有一个「违规」文件, head 上没有 —— 这正是「分支自己绿 / 合起来红」的形状。
FIX="$TMP/repo"
mkdir -p "$FIX" && cd "$FIX"
git init -q . && git config user.email t@e.com && git config user.name t
echo shared > shared.txt && git add -A && git commit -qm root
ROOT_SHA=$(git rev-parse HEAD)
# base: 加一个只有合并后才看得见的文件
echo offender > census-offender.txt && git add -A && git commit -qm base-adds-offender
BASE_SHA=$(git rev-parse HEAD)
# head: 从 root 分叉, 不含那个文件
git checkout -q -b pr-head "$ROOT_SHA"
echo rule > census-rule.txt && git add -A && git commit -qm head-adds-rule
HEAD_SHA=$(git rev-parse HEAD)

[ ! -f census-offender.txt ] || fail "构造失败: head 分支上不该有 base 的文件"

# A1 正例: 合并后, base 的文件必须出现在树里 —— 这就是闸本来应该看到的东西。
out=$(BASE_SHA="$BASE_SHA" HEAD_SHA="$HEAD_SHA" bash "$SCRIPT" 2>&1); rc=$?
echo "    $(printf '%s' "$out" | tr '\n' '|' | cut -c1-190)"
[ "$rc" -eq 0 ] || fail "A1: 合并应成功, 实得 rc=$rc: $out"
[ -f census-offender.txt ] \
    || fail "A1: 合并后树里仍然没有 base 的文件 —— 闸看到的还是 PR 分支自己"
printf '%s' "$out" | grep -q 'merged_ok=yes' || fail "A1: 没打 merged_ok=yes 读数"
printf '%s' "$out" | grep -q "base=$BASE_SHA" || fail "A1: 读数里没有 base sha"
# 硬约束 9: merged_ok=yes 有两种成因(真合进来 / base 已是祖先所以 no-op), 读数相同。
# 必须再有一个【设计上必然会变】的读数把它们分开。
printf '%s' "$out" | grep -qE 'merged_in_commits=[1-9]'     || fail "A1: 缺 merged_in_commits(>0) —— 分不出「真合进来」与「base 已是祖先的 no-op」: $out"
printf '%s' "$out" | grep -q 'tree_changed=yes'     || fail "A1: 缺 tree_changed=yes —— 树没变就说明这一趟什么都没证明: $out"
ok "合并 base 之后, 只存在于 base 的文件出现在树里 (闸看的是合并结果), 且 merged_in_commits>0 / tree_changed=yes"

# A1b: base 已经是 head 的祖先(PR 刚 rebase 过) ⇒ merge 是 no-op。
# 它照样 merged_ok=yes, 但必须**自己说出来**这一趟没证明任何事, ⛔ 不许与 A1 长得一样。
git checkout -q -B rebased "$BASE_SHA"
echo extra > after-base.txt && git add -A && git commit -qm head-on-top-of-base
outNoop=$(BASE_SHA="$BASE_SHA" HEAD_SHA="$(git rev-parse HEAD)" bash "$SCRIPT" 2>&1); rcNoop=$?
[ "$rcNoop" -eq 0 ] || fail "A1b: base 已是祖先时合并应成功, 实得 rc=$rcNoop: $outNoop"
printf '%s' "$outNoop" | grep -q 'merged_in_commits=0'     || fail "A1b: base 已是祖先时 merged_in_commits 应为 0: $outNoop"
printf '%s' "$outNoop" | grep -q 'tree_changed=no'     || fail "A1b: no-op 合并不该改变树: $outNoop"
printf '%s' "$outNoop" | grep -q 'base_already_ancestor'     || fail "A1b: no-op 的那一趟必须自己说明「证明不了任何事」, 否则与 A1 读数同形: $outNoop"
echo "    $(printf '%s' "$outNoop" | grep -o 'merged_in_commits=[0-9]* tree_changed=[a-z]*' | head -1)"
ok "base 已是祖先 ⇒ merged_ok=yes 但 merged_in_commits=0 / tree_changed=no 且点名 no-op (两种成因分得开)"
git checkout -q pr-head

# A1 阴性对照: 不跑这段脚本, 树里就没有那个文件 —— 证明 A1 不是恒真。
git checkout -q -B pr-head2 "$HEAD_SHA"
[ ! -f census-offender.txt ] \
    || fail "A1 阴性对照失效: 没合并却已经有 base 的文件, 那 A1 什么都没证明"
ok "阴性对照: 不合并时树里没有那个文件 (A1 不是恒真式)"

# A2 冲突: 必须红, ⛔ 不许静默跳过合并继续跑闸。
git checkout -q -b conflict-base "$ROOT_SHA"
echo base-side > clash.txt && git add -A && git commit -qm base-clash
CBASE=$(git rev-parse HEAD)
git checkout -q -b conflict-head "$ROOT_SHA"
echo head-side > clash.txt && git add -A && git commit -qm head-clash
out2=$(BASE_SHA="$CBASE" HEAD_SHA="$(git rev-parse HEAD)" bash "$SCRIPT" 2>&1); rc2=$?
git merge --abort 2>/dev/null || true
echo "    rc=$rc2 $(printf '%s' "$out2" | grep -o 'merged_ok=no' | head -1)"
[ "$rc2" -ne 0 ] || fail "A2: 冲突时必须红, 实得 rc=0 —— 那会让闸跑在一棵没合成的树上"
printf '%s' "$out2" | grep -q 'merged_ok=no' || fail "A2: 冲突时没打 merged_ok=no"
ok "与 base 冲突 ⇒ 红 (不静默跑在没合成的树上)"

# A3 base 对象取不到时必须红, ⛔ 不许「取不到就跳过合并」——那等于悄悄退回 PR 分支。
git checkout -q pr-head 2>/dev/null || git checkout -q pr-head2
MISSING=$(printf '0%.0s' {1..40})
out3=$(BASE_SHA="$MISSING" HEAD_SHA="$HEAD_SHA" bash "$SCRIPT" 2>&1); rc3=$?
[ "$rc3" -ne 0 ] || fail "A3: base 取不到时必须红, 实得 rc=0: $out3"
# ⚠️ 只断言「红」是不够的: 去掉那道显式守卫之后, 合并**照样**会失败(base 对象不在),
#    于是 rc 仍然非 0 —— 变异打不红, 而我会误以为断言在守东西(形态 C 的第三种: 断言守的
#    行为压根没被变异改变)。守卫真正的价值是**说清楚是什么坏了**, 所以断言措辞:
#    有它 ⇒「取不到 base 提交 … 需要 fetch-depth: 0」(可直接动手);
#    没它 ⇒「not something we can merge」(得自己猜)。
printf '%s' "$out3" | grep -q '取不到 base 提交'     || fail "A3: 缺少可动手的报错(应指出取不到 base 提交、调用方需要 fetch-depth: 0), 实得: $out3"
ok "base 对象取不到 ⇒ 红, 且报错指到 fetch-depth (不是一句 not something we can merge)"
cd "$ROOT_DIR"

# ---------------------------------------------------------------------------
# B. 结构: 三处 pin 都要配上 merge action + fetch-depth: 0
# ---------------------------------------------------------------------------
PINS=$(grep -c 'pull_request\.head\.sha' "$GATE" "$NIGHTLY" | awk -F: '{s+=$2} END {print s+0}' || true)
CALLS=$(grep -c 'merge-pr-base' "$GATE" "$NIGHTLY" | awk -F: '{s+=$2} END {print s+0}' || true)
DEPTH=$(grep -c 'fetch-depth: 0' "$GATE" "$NIGHTLY" | awk -F: '{s+=$2} END {print s+0}' || true)
echo "READING: HEAD_SHA_PINS=$PINS MERGE_ACTION_CALLS=$CALLS FETCH_DEPTH0=$DEPTH (三者必须相等且为 3)"
[ "$PINS" -eq 3 ] || fail "B: 预期 3 处 pin head.sha, 实得 $PINS —— 新增的那处也要配 merge action"
[ "$CALLS" -eq "$PINS" ] \
    || fail "B: pin 了 $PINS 处但只有 $CALLS 处调 merge-pr-base ⇒ 有闸仍然跑在 PR 分支自己上"
[ "$DEPTH" -eq "$PINS" ] \
    || fail "B: pin 了 $PINS 处但只有 $DEPTH 处 fetch-depth: 0 ⇒ 浅克隆合不了 base"
ok "三处 pin 各自配齐 merge action 与 fetch-depth: 0"

# ⛔ 不许写死 main: base 不是 main 的 PR 会被合错树。
grep -qE "base\.sha" "$ACTION" || fail "B: action 没有用 pull_request.base.sha"
# ⚠️ 先剥注释再 grep: 注释里本来就会提到 main。原先写的是 `grep -n … | grep -v '^\s*#'`,
#    而 `grep -n` 的输出带 "12:" 前缀 ⇒ `^\s*#` 永远匹配不上, 那个过滤器等于没有
#    (本仓反复踩的「闸把自己的注释数进去」同款, 只是方向相反)。
if sed 's/#.*//' "$ACTION" | grep -qE "origin/main|refs/heads/main|'main'|\"main\""; then
    fail "B: action 里出现写死的 main —— base 不是 main 的 PR 会合错树"
fi
ok "合的是 pull_request.base.sha, 没有写死 main"

# 非 pull_request 事件必须整步跳过, 否则 schedule 跑批会去合一个不存在的 base。
grep -q "github.event_name == 'pull_request'" "$ACTION" \
    || fail "B: action 缺 pull_request 守卫 —— schedule / dispatch 会合一个不存在的 base"
ok "非 pull_request 事件整步跳过"

echo "PASS: test-ci-gate-runs-merge-result.sh ($PASSED 组断言)"
