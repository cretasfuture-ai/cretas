#!/usr/bin/env bash
# scripts/check-soft-delete-orphans.sh 里 escalate_soft_deleted 分类器的契约测试。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要**合成实体树**, ⛔ 不能只在真实语料上量
# ═══════════════════════════════════════════════════════════════════════════
# 分类判据是 `escalate = (子实体有自己的 *Repository) AND NOT (该 FK 有 @NotFound)`。
# 在真实语料上实测:
#   · repo:     带 @Table 的实体 431 个, 没有 Repository 的只有 3 个, 且这 3 个
#               一个都不在那 62 对里 ⇒ **escalate=0 这一侧一次都不会被走到**。
#   · notfound: 0/62 —— 带 @NotFound 的关联在收集阶段就被排除了, 走不到分类器。
#
# ▎两项依据在真实语料上都只取到一个值 ⇒ 只在真实语料上跑, 断言全是恒真式(形态 B′),
# ▎而「变异不红」会同时被读成「断言在守空气」和「变异没生效」(形态 C″), 分不开。
#
# ⇒ 这里搭一棵**自己控制两侧取值**的合成树: 有 Repository / 没有 Repository /
#   带 @NotFound / 父表主键不是 id, 各构造一次。
#   注入点只有 SDO_REPO_ROOT 一个, 它只改变「去哪里找东西」, ⛔ 不改判定逻辑。
#
# ⚠️ 每条断言之前先把**分类表那几行**打出来(形态 C″), 再断言。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GEN="$ROOT_DIR/scripts/check-soft-delete-orphans.sh"
REAL_SQL="$ROOT_DIR/scripts/cron/soft-delete-orphans.sql"
REAL_ENTITY="$ROOT_DIR/backend/java/cretas-api/src/main/java/com/cretas/aims/entity"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "  ok  $*"; }

[ -s "$GEN" ] || fail "$GEN 不存在"
bash -n "$GEN" || fail "语法错误: $GEN"

# ═══════════════════════════════════════════════════════════════════════════
# 第一部分: 真实语料上的阳性/阴性对照 (⛔ 不是合成的, 贴 grep 读数)
# ═══════════════════════════════════════════════════════════════════════════
# 全仓**唯一**带 @NotFound(action = NotFoundAction.IGNORE) 的实体是 Customer,
# 两个字段: created_by / assigned_sales_user_id, 都指向 users(软删实体)。
# 同一个 Customer 上的 factory_id 没有 @NotFound ⇒ 天然的同实体对照组,
# 「实体不同」这个混杂因素被排除掉了。
echo "[1] 真实语料对照: 同一个 Customer 上, 带 @NotFound 的被排除 / 不带的被收"
CUST="$REAL_ENTITY/Customer.java"
[ -s "$CUST" ] || fail "找不到 Customer.java —— 对照组没了, 本次读数作废"
NF_N=$(grep -c "NotFoundAction.IGNORE" "$CUST")
echo "    grep 读数: Customer.java 里 NotFoundAction.IGNORE 出现 $NF_N 次"
[ "$NF_N" -ge 2 ] || fail "Customer.java 上的 @NotFound 不见了 —— 这个对照组的前提没了"
for col in created_by assigned_sales_user_id; do
    grep -qE "@JoinColumn\(name = \"$col\"" "$CUST" \
        || fail "Customer.java 上找不到 @JoinColumn(name=\"$col\") —— 对照组前提变了"
done
# 阴性: 带 @NotFound 的两对**不在**生成物里(它们不会炸, 收进来就是天天误报)
for col in created_by assigned_sales_user_id; do
    grep -Fq "'customers.$col -> users'" "$REAL_SQL" \
        && fail "customers.$col 带 @NotFound 却进了生成物 —— 它不会抛异常, ⛔ 不该收"
done
# 阳性: 同一个实体上不带 @NotFound 的那一对**在**生成物里
grep -Fq "'customers.factory_id -> factories'" "$REAL_SQL" \
    || fail "customers.factory_id 不在生成物里 —— 那这个阴性对照证明不了任何事"
echo "    grep 读数: 生成物里 customers.* 的对 = $(grep -o "'customers\.[a-z_]* -> [a-z_]*'" "$REAL_SQL" | tr '\n' ' ')"
ok "同实体对照: 带 @NotFound 的 2 对被排除, 不带的 factory_id 被收"

# ═══════════════════════════════════════════════════════════════════════════
# 合成树
# ═══════════════════════════════════════════════════════════════════════════
SYN="$TMP/synrepo"
ENT="$SYN/backend/java/cretas-api/src/main/java/com/cretas/aims/entity"
REP="$SYN/backend/java/cretas-api/src/main/java/com/cretas/aims/repository"
mkdir -p "$ENT" "$REP" "$SYN/scripts/cron"

# 父实体: @Id 落在 id 列上(同义性前提成立)
mk_parent() {  # $1 = 类名, $2 = 表名, $3 = @Id 对应的列名(默认 id)
    cat > "$ENT/$1.java" <<JAVA
package com.cretas.aims.entity;
import jakarta.persistence.*;
@Entity
@Table(name = "$2")
public class $1 extends BaseEntity {
    @Id
    @Column(name = "${3:-id}", nullable = false)
    private String ${3:-id};
}
JAVA
}
# 子实体: 一个 @ManyToOne 指向父实体; $4=1 时在关联上加 @NotFound
mk_child() {  # $1 = 类名, $2 = 表名, $3 = 父类名, $4 = 加 @NotFound?, $5 = 外键列
    {
        printf 'package com.cretas.aims.entity;\n'
        printf 'import jakarta.persistence.*;\n@Entity\n@Table(name = "%s")\n' "$2"
        printf 'public class %s extends BaseEntity {\n' "$1"
        printf '    @Id\n    @Column(name = "id", nullable = false)\n    private String id;\n\n'
        printf '    @ManyToOne(fetch = FetchType.LAZY)\n'
        [ "$4" = "1" ] && printf '    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)\n'
        printf '    @JoinColumn(name = "%s", referencedColumnName = "id")\n' "$5"
        printf '    private %s parent;\n}\n' "$3"
    } > "$ENT/$1.java"
}
mk_repo() {  # $1 = 实体类名
    cat > "$REP/${1}Repository.java" <<JAVA
package com.cretas.aims.repository;
public interface ${1}Repository extends org.springframework.data.jpa.repository.JpaRepository<$1, String> {
}
JAVA
}

# 四种取值各一个:
mk_parent SynParent syn_parents
mk_child SynWithRepo syn_with_repo SynParent 0 parent_id      # 有 repo, 无 NotFound -> escalate=1
mk_child SynNoRepo   syn_no_repo   SynParent 0 parent_id      # 无 repo             -> escalate=0
mk_child SynNotFound syn_notfound  SynParent 1 parent_id      # 有 NotFound         -> 压根不收
mk_repo SynWithRepo
mk_repo SynNotFound
# ⛔ 故意**不给** SynNoRepo 建 Repository —— 那正是这条阴性对照的构造。

# MANUAL_PAIRS 是生成器里写死的两对, 合成树也得能解析到它们, 否则生成期就 exit 2。
mk_parent ProductionPlan production_plans
mk_child  ProcessSheetRow   process_sheet_rows   ProductionPlan 0 unused_fk_a
mk_child  ReportReversalLog report_reversal_logs ProductionPlan 0 unused_fk_b
mk_repo ProcessSheetRow
mk_repo ReportReversalLog

# hasdel 快照: 阳性对照要求 >= 50 行, 所以补足噪声表名。
{
    printf -- '-- 合成快照(测试用)\n'
    printf -- '-- capture-sql SELECT table_name FROM information_schema.columns\n'
    for t in syn_parents syn_with_repo syn_no_repo syn_notfound \
             production_plans process_sheet_rows report_reversal_logs; do echo "$t"; done
    i=1; while [ "$i" -le 60 ]; do printf 'noise_%03d\n' "$i"; i=$((i + 1)); done
} > "$SYN/scripts/cron/soft-delete-orphans.hasdel.txt"

regen() {  # 在合成树上重放一次; 产出落 $TMP/out.sql, stderr 落 $TMP/out.err
    SDO_REPO_ROOT="$SYN" bash "$GEN" --regen-offline > "$TMP/out.sql" 2> "$TMP/out.err"
    RC=$?
    # 形态 C″: 先把分类表打出来, 再断言。
    echo "    stderr: $(tr '\n' ' ' < "$TMP/out.err")"
    echo "    分类表:"; grep '^-- class syn' "$TMP/out.sql" | sed 's/^/      /' || true
    echo "    rc=$RC"
}

echo "[2] 合成树: 三种取值各一次"
regen
[ "$RC" = 0 ] || fail "合成树上生成失败 (rc=$RC) —— 仪器坏了, 本次读数作废: $(cat "$TMP/out.err")"
# 阳性: 有 Repository 且无 @NotFound -> escalate=1
grep -Fq -- "-- class syn_with_repo.parent_id -> syn_parents escalate=1 repo=SynWithRepoRepository notfound=no" "$TMP/out.sql" \
    || fail "有 Repository 的那对没被标 escalate=1"
# 🔴 阴性: 没有 Repository -> escalate=0。真实语料上**永远走不到**这一侧。
grep -Fq -- "-- class syn_no_repo.parent_id -> syn_parents escalate=0 repo=none notfound=no" "$TMP/out.sql" \
    || fail "没有 Repository 的那对没被标 escalate=0 —— 这一侧是恒真的?"
# @NotFound 那对压根不该出现(它不会抛异常)
grep -Fq "syn_notfound" "$TMP/out.sql" \
    && fail "带 @NotFound 的那对进了生成物 —— 它不会炸, ⛔ 不该收"
# SQL 体里 escalate 列真的跟着变(⛔ 不是只有注释在变)
grep -Fq "'syn_with_repo.parent_id -> syn_parents' AS assoc, count(*) FILTER (WHERE y.id IS NULL) AS absent, count(*) FILTER (WHERE y.id IS NOT NULL) AS soft_deleted, 1 AS escalate_soft_deleted" "$TMP/out.sql" \
    || fail "SQL 体里 escalate 列不是 1"
grep -Fq "'syn_no_repo.parent_id -> syn_parents' AS assoc, count(*) FILTER (WHERE y.id IS NULL) AS absent, count(*) FILTER (WHERE y.id IS NOT NULL) AS soft_deleted, 0 AS escalate_soft_deleted" "$TMP/out.sql" \
    || fail "SQL 体里 escalate 列不是 0"
ok "escalate 两侧都能取到, 且注释与 SQL 体一致"

# ═════════ 3. 两次变异, 打在 @NotFound 这条判据的两个位置上 ═══════════════
# 🔴 终审点名的那条变异。⛔ 先证明变异**真的进了代码**(打印命中行 + 打印分类表),
#    再看断言红不红 —— 「变异不红」有两种相反的成因, 不看读数分不开(形态 C″)。
BASE_SCANNED=$(grep -oE 'SCANNED=[0-9]+' "$TMP/out.err" | head -1)

mutate() {  # $1 = 变异名, $2 = 原文, $3 = 替换文; 产出 $TMP/mut.sql / $TMP/mut.err
    MUT="$TMP/gen-mutant.sh"
    MUT_NAME="$1" MUT_OLD="$2" MUT_NEW="$3" python - "$GEN" "$MUT" <<'PY'
import io, os, sys
src, dst = sys.argv[1], sys.argv[2]
s = io.open(src, "r", encoding="utf-8", newline="").read()
old, new = os.environ["MUT_OLD"], os.environ["MUT_NEW"]
n = s.count(old)
if n != 1:
    raise SystemExit("REFUSE: anchor occurs %d times, expected 1" % n)
io.open(dst, "w", encoding="utf-8", newline="").write(s.replace(old, new))
print("MUTANT_WRITTEN " + os.environ["MUT_NAME"])
PY
    [ -s "$MUT" ] || fail "$1: 变异体没写出来"
    echo "    变异确已进代码: $(grep -c "MUTANT_$1" "$MUT") 处命中"
    SDO_REPO_ROOT="$SYN" bash "$MUT" --regen-offline > "$TMP/mut.sql" 2> "$TMP/mut.err"
    MRC=$?
    echo "    变异体 stderr: $(tr '\n' ' ' < "$TMP/mut.err")"
    echo "    变异体分类表:"; grep '^-- class syn' "$TMP/mut.sql" | sed 's/^/      /' || true
    [ "$MRC" = 0 ] || fail "$1: 变异体自己跑挂了 (rc=$MRC) —— 那这次变异什么都证明不了"
}

echo "[3a] 变异 A: 收集阶段忽略 @NotFound(分类器仍然算)"
mutate IGNORE_NOTFOUND \
    '        if has_notfound(cls, col.group(1)):
            continue
' \
    '        if False:  # MUTANT_IGNORE_NOTFOUND
            continue
'
# 变异生效的可观测量: 那一对**出现**了; 而分类器仍然算得出 notfound=yes ⇒ escalate=0。
grep -Fq -- "-- class syn_notfound.parent_id -> syn_parents escalate=0 repo=SynNotFoundRepository notfound=yes" "$TMP/mut.sql" \
    || fail "变异 A 没产生可观测差异 —— 分不清「断言在守空气」还是「变异没生效」"
grep -Fq "syn_notfound" "$TMP/out.sql" && fail "基线里就有 syn_notfound —— [2] 那条断言本来就是恒真的"
ok "变异 A ⇒ 被排除的对出现了(⇒ [2] 那条阴性断言变红); 分类器仍算出 notfound=yes"
MA_SCANNED=$(grep -oE 'SCANNED=[0-9]+' "$TMP/mut.err" | head -1)
[ "$BASE_SCANNED" != "$MA_SCANNED" ] || fail "变异 A 没改变扫到的对数"
echo "    扫到的对数: 基线 $BASE_SCANNED -> 变异 A $MA_SCANNED"

echo "[3b] 变异 B: has_notfound 恒返回 False(⇒ 本应非 escalate 的对被**升级**)"
mutate NOTFOUND_ALWAYS_FALSE \
    'def has_notfound(child_cls, col):
    src = ents.get(child_cls)
' \
    'def has_notfound(child_cls, col):
    return False  # MUTANT_NOTFOUND_ALWAYS_FALSE
    src = ents.get(child_cls)
'
# 🔴 这就是终审要的那个形状: 一个**本该 escalate=0** 的对被标成了 escalate=1。
grep -Fq -- "-- class syn_notfound.parent_id -> syn_parents escalate=1 repo=SynNotFoundRepository notfound=no" "$TMP/mut.sql" \
    || fail "变异 B 没把那对升级成 escalate=1 —— 断言守的不是 @NotFound 这一项"
MB_NF=$(grep -oE 'NOTFOUND=[0-9]+' "$TMP/mut.err" | head -1)
BASE_NF=$(grep -oE 'NOTFOUND=[0-9]+' "$TMP/out.err" | head -1)
echo "    notfound 计数: 基线 $BASE_NF -> 变异 B $MB_NF"
ok "变异 B ⇒ escalate 由 0 变 1(假阴方向), [2] 与 [3a] 的断言都变红"

# ═════════ 4. 同义性前提: 父表主键不是 id ⇒ 生成期报错 ════════════════════
# 🔴 LEFT JOIN 与 NOT EXISTS 等价的前提是「y.id 至多命中一行」。前提不成立时
#    两列都会虚高**而不报错** —— ⛔ 不许静默跳过, 必须生成期就停。
echo "[4] 父实体 @Id 不在 id 列上 ⇒ 生成期 rc=2 (⛔ 不是静默产出)"
mk_parent SynParent syn_parents plan_code     # 把 @Id 挪到 plan_code 列
echo "    构造已生效: $(grep -c 'plan_code' "$ENT/SynParent.java") 处 plan_code"
regen
[ "$RC" = 2 ] || fail "父表主键不是 id 时应 rc=2, 实得 rc=$RC"
grep -q "PK_ASSUMPTION_VIOLATED" "$TMP/out.err" || fail "没打出 PK_ASSUMPTION_VIOLATED"
grep -q "syn_parents" "$TMP/out.err" || fail "没说清是哪个父表"
ok "rc=2 + PK_ASSUMPTION_VIOLATED, 且点名了父表"

# 阴性对照: 把它改回 id, 必须回到 rc=0 —— 否则上面那条红是别的原因造成的。
echo "[4b] 阴性对照: @Id 改回 id ⇒ 回到 rc=0"
mk_parent SynParent syn_parents
regen
[ "$RC" = 0 ] || fail "改回 id 之后仍然 rc=$RC —— [4] 的红不是主键那条判据造成的"
ok "改回 id ⇒ rc=0, [4] 的红确实来自主键断言"

echo
echo "PASS: test-soft-delete-orphans-escalation-classify.sh ($PASSED 项)"
