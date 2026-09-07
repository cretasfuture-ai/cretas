#!/usr/bin/env bash
# PR 上的 Java 闸靠**类名后缀**决定跑谁, 而那是一个没人守的命名约定。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (票 CI-NEW-JAVA-GATES-MAY-NEVER-RUN, 2026-09-07 实测)
# ═══════════════════════════════════════════════════════════════════════════
# java-gate-pr.yml 用 `-Dtest='*ContractTest,*RoleGateTest,*SecurityTest,…'` 选测试。
# 写新闸的人按**它守什么**给它起名, 而它到底拦不拦 PR, 取决于一个没写在任何地方的后缀。
# 差一个词就整类逃掉:
#
#   RawMaterialTypeRoleGateMvcTest   ← `*RoleGateTest` 要求以 RoleGateTest 结尾, Mvc 卡在中间
#   ToolExecutionContractsTest       ← 复数 Contracts 不匹配 `*ContractTest`
#   SecurityVulnerabilityTest        ← Security 在**前面**, 不是后缀
#   IntentPermissionGateTest         ← PermissionGateTest ≠ PermissionTest
#
# 实测读数(改动前, PR 闸 run 34076718241 的 43MB 日志):
#   · 阳性对照 FlywayVersionUniquenessTest 出现 3 次、…ContractTest 出现 5 次 ⇒ 日志是全的
#   · 上面这些 + ToolExecutionArchitectureTest 出现 **0 次**
#   · 而它们在 nightly 全量里**都跑**(run 34052569577, Tests run: 13180, Failures: 0)
#   ⇒ 性质不是「从不运行」, 是「**PR 上不拦, 晚一天才知道**」。ToolExecutionArchitectureTest
#     还是一道**棘轮**(冻结绕过 ToolExecutionGateway 的直调基线) —— 棘轮不拦 PR,
#     等于允许被守的那个面在合并当时长大。
#
# ⚠️ `-Dsurefire.failIfNoSpecifiedTests=false` 让「某个模式一个都没匹配上」**完全无声**,
#    所以整族被改名/删光也不会红。这道闸就是补那一处。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据
# ═══════════════════════════════════════════════════════════════════════════
# 关键词**从选择器自己推**(⛔ 不另写一份, 否则就是形态 D 的第二份副本):
# 每个带 `*` 的模式去掉通配与结尾的 Test, 剩下的就是「这一族的名字」。
# 名字里带某个族关键词、却不被任何模式匹配的类 = **逃掉的**, 必须在例外清单里登记理由。
#
# 例外清单是**双向**的(与 known-failing 同一约定):
#   · 逃掉但没登记      ⇒ 红「新闸可能不拦 PR」
#   · 登记了但其实没逃  ⇒ 红「已经进选择器了, 请从清单里删掉」(⛔ 不许留永久豁免)
#   · 登记的类文件不存在 ⇒ 红「测试被删了不是被覆盖了」
#
# ⚠️ 这道闸**发现不了全新的族**, 这是它的边界, 不是 bug。关键词来自选择器自己 ⇒ 选择器里
#    没有的族, 它一个字都不会说。实测: 加 `*ArchitectureTest` 之前, ToolExecutionArchitectureTest
#    (一道棘轮)对本闸完全不可见 —— KEYWORD_CARRYING 从 364 变成 365 就是这一个。
#    ⇒ 它守的是「已声明的族不要漂」, ⛔ 不是「所有闸都进 PR 闸了」。后者需要另一个判据
#      (从测试内容判断它是不是闸), 那个判据我量过两版都不可信, 所以没做(形态 E: 宁可窄而可信)。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GATE="$ROOT_DIR/.github/workflows/java-gate-pr.yml"
TEST_ROOT="$ROOT_DIR/backend/java/cretas-api/src/test/java"
EXCEPTIONS="$ROOT_DIR/scripts/tests/java-gate-selector-exceptions.txt"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }

[ -s "$GATE" ] || fail "找不到 $GATE"
[ -d "$TEST_ROOT" ] || fail "找不到测试根 $TEST_ROOT"

# ---- 1. 从 workflow 里抽出选择器本身 ----
# ⛔ 不 grep 单个模式名 —— 那等于把清单抄第二份。抽整行, 恰好 1 处。
SELECTOR_LINES=$(grep -c -- "-Dtest='" "$GATE" || true)
[ "$SELECTOR_LINES" -eq 1 ] \
    || fail "java-gate-pr.yml 里 \`-Dtest='…'\` 有 $SELECTOR_LINES 处(要恰好 1) —— 抽错了照样能跑, 属于最难查的那种"
SELECTOR=$(sed -n "s/.*-Dtest='\([^']*\)'.*/\1/p" "$GATE")
[ -n "$SELECTOR" ] || fail "抽不出选择器内容 —— 没量到, ⛔ 不当成没问题"
printf '%s\n' "$SELECTOR" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
    | grep -v '^$' > "$TMP/patterns.txt"
N_PAT=$(wc -l < "$TMP/patterns.txt")
[ "$N_PAT" -ge 5 ] || fail "只解析出 $N_PAT 个模式, 不像是那条选择器 —— 没量到"
echo "READING: SELECTOR_PATTERNS=$N_PAT"

# ---- 2. 全部测试类 ----
# 所有模式都以 Test 结尾 ⇒ 只有 *Test.java 可能被匹配到, 其余(*IT.java)本就不在这条闸的
# 作用域里, ⛔ 不混进来制造假的"逃掉"。
find "$TEST_ROOT" -name '*Test.java' -printf '%f\n' | sed 's/\.java$//' | sort -u > "$TMP/classes.txt"
N_CLS=$(wc -l < "$TMP/classes.txt")
[ "$N_CLS" -gt 100 ] || fail "只找到 $N_CLS 个测试类, 不像是这个仓 —— 没量到"

# ---- 3. 匹配 ----
# ⚠️ 用 bash 参数展开, ⛔ 不过 sed —— 转义符要经过 shell 一层、sed 一层, 第一版就是在那儿
#    被吃掉的, 症状不是报错而是 SELECTOR_MATCHED=0(被上面那条"仪器坏了"的断言当场抓住)。
#    Java 类名只可能含字母数字下划线与 `*`, 所以只需处理 `.` 与 `*` 两个元字符。
glob_to_regex() {
    local p=$1
    p=${p//./\\.}
    p=${p//\*/.*}
    printf '^%s$\n' "$p"
}
: > "$TMP/matched.txt"
: > "$TMP/keywords.txt"
while read -r p; do
    glob_to_regex "$p" >> "$TMP/regexes.txt"
    case "$p" in
        *'*'*)
            # 族关键词 = 去掉通配与结尾 Test 之后剩下的部分。太短的(<6)不要 ——
            # 它们会把整个仓库都算成"带关键词", 让这道闸宽到没法信(形态 E)。
            kw=$(printf '%s' "$p" | tr -d '*' | sed 's/Test$//')
            [ "${#kw}" -ge 6 ] && printf '%s\n' "$kw" >> "$TMP/keywords.txt"
            ;;
        *) ;;   # 无通配 = 点名某一个类, 不构成"一族"
    esac
done < "$TMP/patterns.txt"
sort -u "$TMP/keywords.txt" -o "$TMP/keywords.txt"
grep -f "$TMP/regexes.txt" "$TMP/classes.txt" > "$TMP/matched.txt" || true
N_MATCH=$(wc -l < "$TMP/matched.txt")
N_KW=$(wc -l < "$TMP/keywords.txt")
echo "READING: TEST_CLASSES=$N_CLS SELECTOR_MATCHED=$N_MATCH FAMILY_KEYWORDS=$N_KW"
[ "$N_MATCH" -gt 0 ] || fail "选择器一个类都没匹配上 —— 仪器坏了, ⛔ 不报绿"
[ "$N_KW" -ge 3 ] || fail "只推出 $N_KW 个族关键词 —— 没量到"

# ---- 4. 逃掉的 = 带族关键词 且 不被任何模式匹配 ----
grep -F -f "$TMP/keywords.txt" "$TMP/classes.txt" 2>/dev/null | sort -u > "$TMP/kw_carrying.txt" || true
comm -23 "$TMP/kw_carrying.txt" <(sort -u "$TMP/matched.txt") > "$TMP/escaped.txt"
N_CARRY=$(wc -l < "$TMP/kw_carrying.txt")
N_ESC=$(wc -l < "$TMP/escaped.txt")
echo "READING: KEYWORD_CARRYING=$N_CARRY ESCAPED=$N_ESC"

# ---- 5. 例外清单(双向) ----
[ -f "$EXCEPTIONS" ] || fail "缺例外清单 $EXCEPTIONS"
grep -vE '^[[:space:]]*(#|$)' "$EXCEPTIONS" | awk -F'\t' '{print $1}' \
    | sed 's/[[:space:]]*$//' | sort -u > "$TMP/registered.txt"
N_REG=$(wc -l < "$TMP/registered.txt")
echo "READING: REGISTERED_EXCEPTIONS=$N_REG"

# 每条登记都要带理由 —— ⛔ 不允许光写个类名就豁免。
while IFS= read -r line; do
    case "$line" in ''|\#*) continue ;; esac
    reason=$(printf '%s' "$line" | awk -F'\t' '{print $2}')
    [ -n "$reason" ] || fail "例外清单里这一行没有理由(要 类名<TAB>理由): $line"
done < "$EXCEPTIONS"
ok "例外清单每条都带理由"

# ⑤a 登记的类必须真的存在 —— 否则「测试被删了」会长成「已登记豁免」。
missing=""
while read -r c; do
    [ -n "$c" ] || continue
    find "$TEST_ROOT" -name "$c.java" | head -1 | grep -q . || missing="$missing $c"
done < "$TMP/registered.txt"
[ -z "$missing" ] || fail "例外清单里这些类**文件不存在**(测试被删了不是被覆盖了):$missing"
ok "例外清单里的类都还在"

# ⑤b 登记了却其实没逃 ⇒ 已经进选择器了, 请删掉(与 known-failing 的 FIXED 探测同一约定)。
stale=$(comm -23 "$TMP/registered.txt" "$TMP/escaped.txt" | tr '\n' ' ')
[ -z "$(printf '%s' "$stale" | tr -d ' ')" ] \
    || fail "这些类已经被选择器覆盖了, 请从例外清单删掉(⛔ 不留永久豁免): $stale"
ok "例外清单里没有过期条目"

# ---- 6. 🔴 本闸的主判据 ----
unregistered=$(comm -23 "$TMP/escaped.txt" "$TMP/registered.txt")
if [ -n "$unregistered" ]; then
    echo "FAIL: 这些测试类的名字带着选择器的族关键词, 却**逃出了** java-gate-pr 的 -Dtest:" >&2
    printf '  · %s\n' $unregistered >&2
    cat >&2 <<'EOF'
  ⇒ 它们在 PR 上**不会跑**(只在 java-full-nightly 的全量里跑, 晚一天且不拦合并)。
  两条出路, 二选一:
    ① 让它进闸: 改类名对上某个模式, 或在 java-gate-pr.yml 的 -Dtest 里加一个模式;
    ② 它本来就不是闸(只是名字撞了业务词, 如 UnitContractControllerTest 的 "Contract"):
       写进 scripts/tests/java-gate-selector-exceptions.txt, 一行「类名<TAB>理由」。
  ⛔ 不要为了让这道闸变绿而去放宽关键词 —— 那会把它自己关掉。
EOF
    exit 1
fi
ok "带族关键词的 $N_CARRY 个类里, 逃掉的 $N_ESC 个全部已登记($N_REG 条)"

# ---- 7. 阴性对照: 这道闸真的能红吗 ----
# 造一个必然逃掉的名字, 确认它会被算进 escaped —— 否则「绿」可能只是关键词一个都没命中。
probe_kw=$(head -1 "$TMP/keywords.txt")
probe="ZzzProbe${probe_kw}MvcNotMatchedTest"
if printf '%s\n' "$probe" | grep -F -f "$TMP/keywords.txt" >/dev/null 2>&1; then
    if printf '%s\n' "$probe" | grep -f "$TMP/regexes.txt" >/dev/null 2>&1; then
        fail "阴性对照失效: 构造的 $probe 居然被选择器匹配上了, 说明模式比想象的宽"
    fi
    echo "READING: NEGATIVE_CONTROL_PROBE=$probe (带关键词 $probe_kw 且不被任何模式匹配 ⇒ 会被算作逃掉)"
else
    fail "阴性对照失效: 构造的 $probe 都不带关键词, 说明关键词提取坏了 —— 那么上面那条绿无意义"
fi
ok "阴性对照: 构造的逃逸名确实会被判为逃掉(这道闸能红)"

echo "PASS: $pass 项断言全部通过"
