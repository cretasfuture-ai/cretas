#!/usr/bin/env bash
# GUARDS=.github/workflows/ops-script-tests.yml
#
# 每个用例**自己声明**它依赖哪些路径, 这些路径必须落在 ops-script-tests.yml 的触发面里。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (票 CI-GUARDED-PATHS-MUST-BE-DECLARED-NOT-INFERRED-20260907)
# ═══════════════════════════════════════════════════════════════════════════
# 「闸断言了某个文件, 而那个文件不在这道闸的触发面里」——
# 2026-09-07 一天之内出现**四次**:
#
#   #713  .github/workflows/java-gate-pr.yml          ← 只动它的 PR 触发不到闸,
#                                                        而那次的状态会让全仓 CI 停摆
#   #723  .github/workflows/web-dist.yml
#   #724  backend/java/cretas-api/src/test/java/**    ← 🔴 根本不是 workflow, 是源码树
#   本条  .github/workflows/ops-script-tests.yml      ← 自指那一格
#
# 四次全靠人手工往 paths 里补一行。**读数与「没问题」完全同形**: 闸没跑 = 闸绿。
#
# ═══════════════════════════════════════════════════════════════════════════
# ⛔ 为什么不反解, 而是要作者声明
# ═══════════════════════════════════════════════════════════════════════════
# 「从各测试脚本里反解它断言了哪些路径」这条路**判死**, 两个理由:
#
# ① 第三个实例(#724)根本不是 workflow, 是**源码树** ⇒ 反解「它断言了哪些 workflow」
#    接不住它。这一族的形状是「**闸依赖的任何输入**」, 不是「闸断言的 workflow」。
# ② 路径可能是拼出来的、变量里的、跨函数传的。正则收窄本仓已栽三次
#    (`@Builder.Default` 卡 `@\w+` / 全限定注解名 / Java 文本块), 规矩是**同形第三次换 AST**
#    —— 而这里连 AST 都救不了。
#
# ⇒ 用**声明**。本仓已有同形先例: `# SCRIPT_TEST_TIMEOUT=<秒>`
#    (scripts/ci/run-script-tests.sh:176, 读文件头 40 行)。同一形状再加一条:
#
#        # GUARDS=<glob>[,<glob>...]
#
# ⚠️ 这道闸的**边界**(⛔ 别把它当成更强的东西):
#    它只检查**已声明**的路径。一个什么都不声明的脚本, 它一个字都不会说 ——
#    漏声明的代价与今天**完全相同, 不多不少**。它消灭的是另一半:
#    **「声明了却没进触发面」从此不可能发生**。
#    ⛔ 不要为了「覆盖漏声明」去加反解 —— 那正是上面判死的那条路。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TESTS_DIR="$ROOT_DIR/scripts/tests"
WF="$ROOT_DIR/.github/workflows/ops-script-tests.yml"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }

[ -s "$WF" ] || fail "找不到 $WF"
[ -d "$TESTS_DIR" ] || fail "找不到 $TESTS_DIR"

# ---- 1. 取 ops-script-tests.yml 的 pull_request.paths ----
# ⚠️ 只取 pull_request 那一段。push 那段刻意**不带 paths**(见该文件注释), 混进来会让
#    这道闸恒绿 —— 「没有 paths」在语义上等于「全部都在触发面里」。
awk '/^  pull_request:/{inpr=1; next} inpr && /^  [a-z_]+:/{inpr=0} inpr' "$WF" \
    | grep -oE "^ *- '[^']+'" | sed "s/^ *- '//;s/'$//" > "$TMP/paths.txt"
N_PATHS=$(wc -l < "$TMP/paths.txt")
echo "READING: TRIGGER_PATHS=$N_PATHS"
[ "$N_PATHS" -ge 5 ] \
    || fail "只解析出 $N_PATHS 条 paths, 不像那份 workflow —— **没量到**, ⛔ 不当成没问题"

# ---- 2. 收集所有 `# GUARDS=` 声明 ----
# 与 SCRIPT_TEST_TIMEOUT 同一约定: 只读文件头 40 行, 行首必须是 `# GUARDS=`。
: > "$TMP/decl.txt"
n_files=0
for f in "$TESTS_DIR"/*.sh; do
    [ -f "$f" ] || continue
    line=$(head -40 "$f" | grep -oE '^# GUARDS=[^ ]+' | head -1 | cut -d= -f2-) || true
    [ -n "$line" ] || continue
    n_files=$((n_files + 1))
    printf '%s\n' "$line" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
        | grep -v '^$' | while read -r g; do printf '%s\t%s\n' "$(basename "$f")" "$g"; done \
        >> "$TMP/decl.txt"
done
N_DECL=$(wc -l < "$TMP/decl.txt")
echo "READING: DECLARING_FILES=$n_files DECLARED_PATHS=$N_DECL"
[ "$n_files" -ge 1 ] || fail "一个 GUARDS 声明都没找到 —— **没量到**(标记写法变了?), ⛔ 不报绿"

# ---- 3. 覆盖判定 ----
# 一条声明 D 被覆盖 = 存在某条 paths 条目 P, 把 P 当 glob 转成正则后能匹配 D 的字面串。
# (`**` -> `.*`, `*` -> `[^/]*`; `.*` 也匹配 D 里的 `**`, 所以 D 本身是 glob 也能判。)
glob_to_re() {
    local p=$1
    p=${p//./\\.}
    p=${p//\*\*/@@GLOBSTAR@@}
    p=${p//\*/[^\/]*}
    p=${p//@@GLOBSTAR@@/.*}
    printf '^%s$' "$p"
}
: > "$TMP/uncovered.txt"
while IFS=$'\t' read -r owner g; do
    [ -n "$g" ] || continue
    covered=no
    while read -r p; do
        [ -n "$p" ] || continue
        if printf '%s\n' "$g" | grep -qE "$(glob_to_re "$p")"; then covered=yes; break; fi
    done < "$TMP/paths.txt"
    [ "$covered" = yes ] || printf '%s\t%s\n' "$owner" "$g" >> "$TMP/uncovered.txt"
done < "$TMP/decl.txt"

# ---- 4. 🔴 主判据 ----
if [ -s "$TMP/uncovered.txt" ]; then
    echo "FAIL: 这些用例声明了它们依赖的路径, 但那些路径**不在** ops-script-tests.yml 的触发面里:" >&2
    while IFS=$'\t' read -r owner g; do echo "  · $owner  声明 GUARDS=$g" >&2; done < "$TMP/uncovered.txt"
    cat >&2 <<'EOF'
  ⇒ 只动那些路径的 PR **触发不到这道闸**, 而「没跑」与「跑了没问题」读数完全相同
    (2026-09-07 这个洞一天出现四次: #713 / #723 / #724 / 本闸自指)。
  修法: 往 .github/workflows/ops-script-tests.yml 的 pull_request.paths 里加上它;
       ⛔ 不要改小 GUARDS 声明来让这道闸变绿 —— 那是把仪器改坏, 不是把问题修好。
EOF
    exit 1
fi
ok "$n_files 个用例声明的 $N_DECL 条路径全部落在触发面里"

# ---- 5. 自指: 这道闸自己必须声明它依赖的那个文件 ----
# ⚠️ 没有这一条, 它就是下一个「断言了自己却触发不到自己」——
#    改 ops-script-tests.yml 的 PR 会绕过它, 而它恰恰是唯一看着那份 paths 的东西。
SELF=$(basename "${BASH_SOURCE[0]}")
grep -qE "^$SELF\s+\.github/workflows/ops-script-tests\.yml$" \
    <(sed 's/\t/ /' "$TMP/decl.txt" | tr -s ' ') \
    || fail "5: 本闸自己没有声明 \`# GUARDS=.github/workflows/ops-script-tests.yml\` —— 自指那一格必须显式占上"
ok "自指: 本闸声明了它依赖 ops-script-tests.yml"

# ---- 6. 阴性对照: 这道闸真的能红吗 ----
# 构造一条**必然不在**触发面里的声明, 确认覆盖判定会判它 uncovered。
# 没有这一条,「绿」可能只是覆盖判定恒真(例如 glob 转换写错让每条都匹配)。
probe='zzz/definitely/not/in/the/trigger/surface.txt'
probe_covered=no
while read -r p; do
    [ -n "$p" ] || continue
    if printf '%s\n' "$probe" | grep -qE "$(glob_to_re "$p")"; then probe_covered=yes; break; fi
done < "$TMP/paths.txt"
[ "$probe_covered" = no ] \
    || fail "6: 阴性对照失效 —— 构造的 $probe 居然被判为已覆盖, 说明 glob 转换过宽, 上面那条绿无意义"
echo "READING: NEGATIVE_CONTROL_PROBE=$probe covered=no"
ok "阴性对照: 构造的路径确实判为未覆盖(这道闸能红)"

# ---- 7. 阳性对照: 一条**已知在**触发面里的路径必须判为已覆盖 ----
# 与 6 成对: 6 防「恒真」, 7 防「恒假」。只有 6 会让人以为闸有效而其实全绿;
# 只有 7 会让人以为闸有效而其实全红 —— 两个方向都要钉。
pos='scripts/tests/test-guarded-paths-are-in-the-trigger-surface.sh'
pos_covered=no
while read -r p; do
    [ -n "$p" ] || continue
    if printf '%s\n' "$pos" | grep -qE "$(glob_to_re "$p")"; then pos_covered=yes; break; fi
done < "$TMP/paths.txt"
[ "$pos_covered" = yes ] \
    || fail "7: 阳性对照失效 —— $pos 明明被 'scripts/tests/**' 覆盖却判成未覆盖, glob 转换过窄"
ok "阳性对照: 已知被覆盖的路径判为已覆盖"

# ---- 8. 🔴 棘轮: 声明本身不许被删掉 ----
#
# 这道闸红了之后, **让它变绿最便宜的做法是删掉那行 `# GUARDS=`** —— 而删掉之后本闸
# 一个字都不会说(它只检查已声明的路径, 见文件头的边界)。
# ⇒ 声明式机制的通用规矩: **「作者写一行就能让闸不看某个东西」的机制, 那一行必须被钉住。**
# ⚠️ 光棘轮一个**计数**不够 —— 计数说不出是哪个文件没了, 而且加一个新声明就能把删掉的那个
#    补回数目上。⇒ 用**按文件名的双向登记表**。
REGISTRY="$TESTS_DIR/guards-declaring-files.txt"
[ -f "$REGISTRY" ] || fail "8: 缺登记表 $REGISTRY"
grep -vE '^[[:space:]]*(#|$)' "$REGISTRY" | tr -d '\r' | sed 's/[[:space:]]*$//' \
    | grep -v '^$' | sort -u > "$TMP/registry.txt"
cut -f1 "$TMP/decl.txt" | sort -u > "$TMP/declaring.txt"
N_REG=$(wc -l < "$TMP/registry.txt")
echo "READING: GUARDS_REGISTERED=$N_REG DECLARING_NOW=$(wc -l < "$TMP/declaring.txt")"
[ "$N_REG" -ge 1 ] || fail "8: 登记表是空的 —— **没量到**, ⛔ 不报绿"

# ⑧b 先判「文件还在不在」—— 必须排在 ⑧a **之前**:
#    文件都没了的时候, ⑧a 会报「登记过要声明, 现在却不声明了」, 而那句话指向一个
#    **做不到的动作**(去一个不存在的文件里加声明)。与端口探针那次同族: 报错要说出能做的下一步。
#    ⚠️ 这一条本身也守着一件事: 没有它,「测试被删了」会长成「它不需要声明」。
missing=""
while read -r f; do
    [ -n "$f" ] || continue
    [ -f "$TESTS_DIR/$f" ] || missing="$missing $f"
done < "$TMP/registry.txt"
[ -z "$missing" ] || fail "8: 登记表里这些文件**不存在**(测试被删了不是不需要声明了):$missing"

# ⑧a 登记了却不再声明 ⇒ 就是那个洞
lost=$(comm -23 "$TMP/registry.txt" "$TMP/declaring.txt" | tr '\n' ' ')
if [ -n "$(printf '%s' "$lost" | tr -d ' ')" ]; then
    echo "FAIL: 这些用例登记过要声明 \`# GUARDS=\`, 现在却不声明了:$lost" >&2
    cat >&2 <<'EOF'
  ⇒ 删掉声明是让本闸「变绿」最便宜的做法, 而删完之后它对那些路径**完全失明**。
  修法: 把 `# GUARDS=` 加回去(并把路径补进 ops-script-tests.yml 的 paths);
       真要删掉这个用例, 就在**同一个 commit** 里把它从 guards-declaring-files.txt 里删掉,
       ⛔ 不要先删声明再说。
EOF
    exit 1
fi

# ⑧c 在声明却没登记 ⇒ 请登记(一行的事), 否则新声明不受棘轮保护
unreg=$(comm -13 "$TMP/registry.txt" "$TMP/declaring.txt" | tr '\n' ' ')
[ -z "$(printf '%s' "$unreg" | tr -d ' ')" ] \
    || fail "8: 这些用例声明了 \`# GUARDS=\` 但没登记进 guards-declaring-files.txt:$unreg —— 不登记就不受棘轮保护, 下次谁把声明删了没人会知道"
ok "声明棘轮: $N_REG 个登记文件全部仍在声明(⛔ 删声明会红并被点名)"

# ---- 9. 阴性对照: 这道棘轮真的能红吗 ----
# 构造「登记了但不在声明集合里」的情形, 确认 comm 会把它算成 lost。
# ⚠️ 必须**排序后**再喂给 comm。第一版直接把探针行拼在已排序清单前面, comm 打出
#    `input is not in sorted order` —— 那趟结果碰巧是对的, 但**仪器在报警的读数不能用**。
probe_lost=$(comm -23 <({ printf 'zzz-not-declaring.sh\n'; cat "$TMP/registry.txt"; } | sort -u) <(sort -u "$TMP/declaring.txt") 2>&1 | tr '\n' ' ')
printf '%s' "$probe_lost" | grep -q 'not in sorted order' \
    && fail "9: comm 报「输入未排序」—— 仪器在报警的读数不能用"
printf '%s' "$probe_lost" | grep -q 'zzz-not-declaring.sh' \
    || fail "9: 阴性对照失效 —— 构造的「登记了却不声明」没有被算成 lost, 那么第 8 组的绿无意义"
echo "READING: RATCHET_NEGATIVE_CONTROL=zzz-not-declaring.sh->lost"
ok "阴性对照: 「登记了却不声明」确实会被判为 lost"

echo "PASS: $pass 项断言全部通过"
