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
GATE="$ROOT_DIR/.github/workflows/java-gate-pr.yml"
NIGHTLY="$ROOT_DIR/.github/workflows/java-full-nightly.yml"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$GATE" ] || fail "$GATE 不存在"

# ---------------------------------------------------------------------------
# A. 行为: 抽出 action 里的 run: 脚本, 真跑
# ---------------------------------------------------------------------------
SCRIPT="$TMP/merge-step.sh"
python - "$GATE" "$SCRIPT" <<'PY'
import sys, textwrap
src, dst = sys.argv[1], sys.argv[2]
lines = open(src, encoding="utf-8").read().splitlines()
# 定位到「Merge PR base into head」那一步的 run 块 —— 文件里还有别的 run: |,
# ⛔ 不能取第一个(那会抽到别的步骤, 而且抽错了照样"能跑", 属于最难查的那种)。
step = next(k for k, l in enumerate(lines) if "name: Merge PR base into head" in l)
i = next(k for k in range(step, len(lines)) if lines[k].strip() == "run: |")
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
[ -s "$SCRIPT" ] || fail "没能从 workflow 里抽出那段内联 merge 脚本"

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
# ⚠️ 必须**锚到结构行**: 内联之后, `pull_request.head.sha` 还会出现在 env 的 HEAD_SHA 行和
#    注释里(实测把 3 数成 9) —— 又是「闸把自己的文本数进去」。只数 checkout 的 `ref:` 那一行。
PINS=$(grep -cE '^[[:space:]]*ref:.*pull_request\.head\.sha' "$GATE" "$NIGHTLY" | awk -F: '{s+=$2} END {print s+0}' || true)
CALLS=$(grep -c 'name: Merge PR base into head' "$GATE" "$NIGHTLY" | awk -F: '{s+=$2} END {print s+0}' || true)
DEPTH=$(grep -cE '^[[:space:]]*fetch-depth: 0[[:space:]]*$' "$GATE" "$NIGHTLY" | awk -F: '{s+=$2} END {print s+0}' || true)
echo "READING: HEAD_SHA_PINS=$PINS INLINE_MERGE_STEPS=$CALLS FETCH_DEPTH0=$DEPTH (三者必须相等且为 3)"

# 🔴 回归闸(2026-09-07 P0): ⛔ 这一步不许再写成本地 composite action。
#    pull_request 下 workflow 定义从 base 读, 而 `uses: ./…` 的 action 从**工作区**读, 而工作区
#    checkout 的是 PR **head** ⇒ head 不含那个目录的 PR(旧分支)这一步必死 9 秒红, 且长得像
#    「CI 没测出问题」。实测炸掉了当时所有在飞 PR 的 Java 闸。
# ⚠️ 先剥整行注释再查 —— 本文件与 workflow 的说明文字里都写着这个字符串(闸把自己的注释数进去, 又一次)。
LOCAL_ACTION_REFS=$(sed -E 's/^[[:space:]]*#.*$//' "$GATE" "$NIGHTLY" \
    | grep -cE 'uses:[[:space:]]*\./\.github/actions/' || true)
echo "READING: LOCAL_COMPOSITE_ACTION_REFS=$LOCAL_ACTION_REFS (必须 0)"
[ "$LOCAL_ACTION_REFS" -eq 0 ] \
    || fail "B: 又出现了 \`uses: ./.github/actions/…\` —— head 不含该目录的 PR 会 9 秒红(P0 回归)"
[ "$PINS" -eq 3 ] || fail "B: 预期 3 处 pin head.sha, 实得 $PINS —— 新增的那处也要配 merge action"
[ "$CALLS" -eq "$PINS" ] \
    || fail "B: pin 了 $PINS 处但只有 $CALLS 处内联了 merge 步骤 ⇒ 有闸仍然跑在 PR 分支自己上"
[ "$DEPTH" -eq "$PINS" ] \
    || fail "B: pin 了 $PINS 处但只有 $DEPTH 处 fetch-depth: 0 ⇒ 浅克隆合不了 base"
ok "三处 pin 各自配齐内联 merge 步骤与 fetch-depth: 0"

# 内联的代价是**三份会漂**(形态 D)。这条闸是那个代价的对价: 三段 run 体必须逐字相同。
python - "$GATE" "$NIGHTLY" <<'PY' || fail "B: 三处内联的 run 体不完全相同 —— 它们会各自漂走"
import sys, textwrap
bodies = []
for path in sys.argv[1:]:
    lines = open(path, encoding="utf-8").read().splitlines()
    for k, l in enumerate(lines):
        if "name: Merge PR base into head" not in l:
            continue
        i = next(j for j in range(k, len(lines)) if lines[j].strip() == "run: |")
        ind = len(lines[i]) - len(lines[i].lstrip())
        body = []
        for m in lines[i + 1:]:
            if m.strip() and (len(m) - len(m.lstrip())) <= ind:
                break
            body.append(m)
        bodies.append(textwrap.dedent("\n".join(body)).rstrip())
print(f"READING: INLINE_BODIES={len(bodies)} identical={len(set(bodies)) == 1}")
sys.exit(0 if len(bodies) == 3 and len(set(bodies)) == 1 else 1)
PY
ok "三处内联的 run 体逐字相同"

# ⛔ 不许写死 main: base 不是 main 的 PR 会被合错树。
grep -qE "base\.sha" "$GATE" || fail "B: 内联步骤没有用 pull_request.base.sha"
# ⚠️ 先剥注释再 grep: 注释里本来就会提到 main。原先写的是 `grep -n … | grep -v '^\s*#'`,
#    而 `grep -n` 的输出带 "12:" 前缀 ⇒ `^\s*#` 永远匹配不上, 那个过滤器等于没有
#    (本仓反复踩的「闸把自己的注释数进去」同款, 只是方向相反)。
if sed -n '/name: Merge PR base into head/,/^      - /p' "$GATE" | sed 's/#.*//' \
     | grep -qE "origin/main|refs/heads/main|'main'|\"main\""; then
    fail "B: action 里出现写死的 main —— base 不是 main 的 PR 会合错树"
fi
ok "合的是 pull_request.base.sha, 没有写死 main"

# 非 pull_request 事件必须整步跳过, 否则 schedule 跑批会去合一个不存在的 base。
grep -q "github.event_name == 'pull_request'" "$GATE" \
    || fail "B: action 缺 pull_request 守卫 —— schedule / dispatch 会合一个不存在的 base"
ok "非 pull_request 事件整步跳过"

# ── C. 这道闸自己**触发得到吗** (2026-09-07 P0 的直接教训) ──────────────────
# 本测试断言的是 java-gate-pr.yml / java-full-nightly.yml 的结构, 但它自己是由
# ops-script-tests.yml 的 glob(`scripts/tests/*.sh`)跑的 —— 如果那个 workflow 的 paths
# 不含被断言的文件, 那么**只动那些文件**的 PR 根本不会触发它。
# 实测代价: 一个只动 `.github/actions/` 的 commit(删了 action 却留着三处 `uses:`)在 PR 上全绿,
# 而它会让**每一个** PR 的 Java 闸 9 秒红。⇒ 闸断言谁的结构, 谁就必须在触发面里。
OPS_WF="$ROOT_DIR/.github/workflows/ops-script-tests.yml"
[ -s "$OPS_WF" ] || fail "C: 找不到 $OPS_WF"
OPS_PATHS=$(awk '
    /^[[:space:]]*paths:[[:space:]]*$/ { inb = 1; next }
    inb && /^[[:space:]]*-[[:space:]]/ { sub(/#.*$/, ""); print; next }
    inb && /^[[:space:]]*#/            { next }
    inb && NF                          { inb = 0 }
' "$OPS_WF")
echo "READING: OPS_SCRIPT_TESTS_PATHS=$(printf '%s
' "$OPS_PATHS" | grep -c . || true)"
for guarded in .github/workflows/java-gate-pr.yml .github/workflows/java-full-nightly.yml; do
    printf '%s
' "$OPS_PATHS" | grep -qF "$guarded"         || fail "C: 本测试断言 $guarded 的结构, 但 ops-script-tests.yml 的 paths 不含它 ⇒ 只动它的 PR 触发不到这道闸(那正是 P0 的形状)"
done
ok "被本测试断言的两个 workflow 都在 ops-script-tests 的触发面里"

echo "PASS: test-ci-gate-runs-merge-result.sh ($PASSED 组断言)"
