#!/usr/bin/env bash
# 软删孤儿扫描的**扫描根**必须盖住所有「会被它管」的实体 —— 盖不住的必须**登记且自证无害**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (票 OPS-SOFT-DELETE-SCAN-ROOT-MISSES-LOGISTICS-ENTITIES-20260907)
# ═══════════════════════════════════════════════════════════════════════════
# `check-soft-delete-orphans.sh` 只扫两个写死的根:
#     ENTITY_ROOT     = com/cretas/aims/entity
#     REPOSITORY_ROOT = com/cretas/aims/repository
# 而 2026-09-07 实测: `com/cretas/aims/logistics/entity/` 下另有 **11 个**带 @Table 的实体,
# 它们的表 **11/11 都在 hasdel 快照里**(= 库里真有 deleted_at 列)。⇒ 扫描器一个都没看过。
#
# 🔴 但**今天没有在漏**, 这个区分决定了处置方式:
#     · 那 11 个里 @ManyToOne / @OneToMany / @JoinColumn / @JsonIgnore **全是 0**
#     · 扫描根内的实体里, @ManyToOne 指向 Logistics* 的也是 **0**
#     · 把它们并进扫描根重新生成: 实体数 431→442, 而 **UNION ALL 块 61→61(零新增)**
#   ⇒ 扫描器针对的失败模式(Hibernate 解析 @ManyToOne 代理时目标已软删 ⇒ EntityNotFoundException)
#     在这 11 张表上**两个方向都不可能发生**。盲区是**潜伏的**, 不是正在漏的。
#
# ⛔ 因此**不扩根**(organizer 2026-09-07 裁定): 收益今天是 0 个新块, 代价是动生成物 + 与
#    ops-script-tests 的 main 车道互相影响。⚠️ 而且**只扩 ENTITY_ROOT 会把另一条既有判据搞坏**:
#    logistics 有**自己的** repository 根, 只扩实体根会让升级判据①(「这个实体有没有自己的
#    Repository」)对这 11 个全部判错 —— 实测「没有 Repository 的实体」3 → 14, 多出的 11 个全是假的。
#    两个根是一对, 动一个不动另一个就漂(形态 D 的变种)。
#
# ⇒ 本闸要的是**让盲区下次别再无声**: 出现第 12 棵子树、或登记在案的那些哪天长出第一个关联,
#   就红。⛔ 不是「文件改没改」的代理判据 —— 每一条都从**实体本身**算出来。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GEN="$ROOT_DIR/scripts/check-soft-delete-orphans.sh"
HASDEL="$ROOT_DIR/scripts/cron/soft-delete-orphans.hasdel.txt"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$GEN" ] || fail "生成器不存在: $GEN"
[ -s "$HASDEL" ] || fail "hasdel 快照不存在: $HASDEL"

# 扫描根从**生成器里读出来**, ⛔ 不在这里另抄一份常量(那就是第二份会漂的副本)。
ENTITY_REL=$(grep -E '^ENTITY_ROOT=' "$GEN" | sed -E 's|.*JAVA_SRC/||; s|"||g')
REPO_REL=$(grep -E '^REPOSITORY_ROOT=' "$GEN" | sed -E 's|.*JAVA_SRC/||; s|"||g')
[ -n "$ENTITY_REL" ] && [ -n "$REPO_REL" ] \
    || fail "从生成器里读不出 ENTITY_ROOT / REPOSITORY_ROOT(改写法了?) —— 读不出就不能假装钉住了"
echo "READING: SCAN_ROOTS=com/cretas/aims/{$ENTITY_REL,$REPO_REL}"

# ── 登记在案的「扫不到但今天无害」——每一条都必须自证无害, ⛔ 不是白名单 ─────────
# 无害的**理由**是「没有任何 JPA 关联注解」, 所以下面第 2 组会**重新验证这个理由**:
# 哪天它们长出第一个 @ManyToOne, 盲区就从潜伏变成正在漏, 这道闸当场红。
REGISTERED=$(cat <<'EOF'
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsDailyAvailability.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsDeliveryOrder.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsDistanceEdge.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsDriver.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsOrderBatch.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsPlan.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsStop.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsStoreMaster.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsTrip.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsVehicleDriver.java
backend/java/cretas-api/src/main/java/com/cretas/aims/logistics/entity/LogisticsVehicleProfile.java
EOF
)
REG_COUNT=$(printf '%s\n' "$REGISTERED" | grep -c . || true)

NEXT_STEPS='  下一步(⛔ 别只扩一个根):
    1) 两个根**一起**扩 —— ENTITY_ROOT 与 REPOSITORY_ROOT 是一对。只扩实体根会让升级判据①
       (「这个实体有没有自己的 Repository」)对新子树全部判错(logistics 那次实测 3 → 14, 11 条假读数)。
    2) 重新生成并提交: ./scripts/check-soft-delete-orphans.sh --write
    3) 核一次「没有 Repository 的实体」这个数 —— 它不该因为扩根而暴涨; 涨了就是判据被搞坏了。
    4) 若这个新子树今天没有任何 JPA 关联注解, 也可以先登记进本文件的 REGISTERED 名单
       (但要连同「无害的理由」一起, 本闸会重新验证那个理由)。'

# ── 核心: 找出「表在 hasdel 快照里、但文件不在扫描根下」的实体 ────────────────
# ⛔ 不靠 grep 文件里的 deleted_at 判软删: 多数实体的 deleted_at 是从 BaseEntity(@MappedSuperclass)
#    **继承**的, 文件里根本没有那个字符串。权威源是 hasdel 快照(库里哪些表真有这一列)。
scan_violations() {   # $1 = 仓库根; 输出: 每行一个 <相对路径>\t<表名>
    local root="$1" f table rel
    # ⚡ 先用**一次**递归 grep 把候选缩到带 @Table 的那几百个, ⛔ 不要 find 全树再逐文件 grep:
    #    第一版那么写, Windows 上每个 .java 两次进程 spawn, 一次扫描要几分钟(实测跑不完)。
    while IFS= read -r f; do
        table=$(grep -oE '@Table\s*\(\s*name\s*=\s*"[^"]+"' "$f" | grep -oE '"[^"]+"' | tr -d '"' | head -1)
        [ -n "$table" ] || continue
        grep -qx "$table" "$HASDEL" || continue          # 表没有 deleted_at ⇒ 与本扫描无关
        rel=${f#"$root"/}
        case "$rel" in
            */com/cretas/aims/"$ENTITY_REL"/*|*/com/cretas/aims/"$REPO_REL"/*) continue ;;
        esac
        printf '%s\t%s\n' "$rel" "$table"
    done < <(grep -rl --include='*.java' '@Table' "$root/backend/java" 2>/dev/null | grep '/src/main/java/' || true)
}

VIOL=$(scan_violations "$ROOT_DIR" || true)
VIOL_COUNT=$(printf '%s\n' "$VIOL" | grep -c . || true)
echo "READING: UNSCANNED_SOFTDELETE_ENTITIES=$VIOL_COUNT REGISTERED=$REG_COUNT"

# ── 1. 每一条扫不到的实体都必须已登记, 否则红并给出下一步 ────────────────────
UNREG=""
while IFS=$'\t' read -r rel table; do
    [ -n "$rel" ] || continue
    printf '%s\n' "$REGISTERED" | grep -qxF "$rel" || UNREG="$UNREG
  $rel  (表 $table 在 hasdel 快照里)"
done <<< "$VIOL"
if [ -n "$UNREG" ]; then
    fail "出现了**没登记**的扫不到实体 —— 软删孤儿扫描盖不到它们:${UNREG}
$NEXT_STEPS"
fi
ok "所有扫不到的软删实体都已登记 ($VIOL_COUNT 个)"

# ── 2. 登记条目必须**仍然自证无害**: 没有任何 JPA 关联注解 ────────────────────
# 这是登记的**理由**, 不是登记本身。理由不成立了就必须重新裁定 ⇒ 红。
ACTIVE=""
TOTAL_ASSOC=0
while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    [ -f "$ROOT_DIR/$rel" ] || fail "登记名单里的文件不存在了: $rel —— 名单有陈旧项, 清掉它(⛔ 别让名单自己漂)"
    # ⚠️ 必须容忍**全限定**写法: `@jakarta.persistence.ManyToOne` 里 `ManyToOne` 前面是 `.` 不是 `@`,
    #    只写 `@ManyToOne` 会漏(2026-09-07 我自己的变异就是这么"没红"的 —— 变异没到达, 而不是断言没用;
    #    同一天 Line B 也栽在「源码闸正则被点号注解名打瞎」上, 同款)。
    n=$(grep -cE '@([A-Za-z_][A-Za-z0-9_]*\.)*(ManyToOne|OneToMany|ManyToMany|JoinColumn)\b' "$ROOT_DIR/$rel" || true)
    TOTAL_ASSOC=$((TOTAL_ASSOC + n))
    [ "$n" -eq 0 ] || ACTIVE="$ACTIVE
  $rel  ($n 个关联注解)"
done <<< "$REGISTERED"
echo "READING: REGISTERED_ASSOCIATION_ANNOTATIONS=$TOTAL_ASSOC (必须为 0 —— 这是它们被登记的理由)"
if [ -n "$ACTIVE" ]; then
    fail "登记在案的实体长出了 JPA 关联 ⇒ 盲区从**潜伏**变成**正在漏**(扫描器看不到这些关联):${ACTIVE}
$NEXT_STEPS"
fi
ok "登记条目仍自证无害: 关联注解总数 $TOTAL_ASSOC"

# ── 3. 名单不许有陈旧项(已经被扫描根盖住了却还登记着)─────────────────────────
STALE=""
while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    printf '%s\n' "$VIOL" | cut -f1 | grep -qxF "$rel" || STALE="$STALE $rel"
done <<< "$REGISTERED"
[ -z "$STALE" ] || fail "名单里这些已经不再是「扫不到」了(扩过根?), 清掉:$STALE"
ok "名单无陈旧项 (登记数 == 扫不到数 == $VIOL_COUNT)"

# ── 4. 阴性对照: 真造一个「不被扫描的子树里的软删实体」, 闸必须红并点名 ────────
# ⛔ 不是「测闸能跑」—— 没有这一条, 上面三组对一棵**永远只有 logistics**的树恒真。
# ⚠️ 快照头部有注释行 —— `head -1` 会取到那句说明而不是表名(第一版就这么写的, 阴性对照当场
#    以 `table=-- 这是生成物…` 失败)。取第一行**裸表名**: 跳过注释与空行。
KNOWN_TABLE=$(grep -vE '^[[:space:]]*(--|#|$)' "$HASDEL" | head -1 | tr -d '[:space:]')
[ -n "$KNOWN_TABLE" ] || fail "hasdel 快照里找不到裸表名, 阴性对照造不出来"
case "$KNOWN_TABLE" in
    *[!a-z0-9_]*) fail "取到的不像表名: '$KNOWN_TABLE' —— 快照格式变了, 本次阴性对照作废" ;;
esac
FAKE_ROOT="$TMP/fake"
mkdir -p "$FAKE_ROOT/backend/java/cretas-api/src/main/java/com/cretas/aims/probeunscanned/entity"
FAKE_REL="backend/java/cretas-api/src/main/java/com/cretas/aims/probeunscanned/entity/ProbeUnscannedEntity.java"
cat > "$FAKE_ROOT/$FAKE_REL" <<JAVA
package com.cretas.aims.probeunscanned.entity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
@Entity
@Table(name = "$KNOWN_TABLE")
public class ProbeUnscannedEntity { }
JAVA
FAKE_VIOL=$(scan_violations "$FAKE_ROOT" || true)
echo "READING: NEGATIVE_CONTROL_hits=$(printf '%s\n' "$FAKE_VIOL" | grep -c . || true) table=$KNOWN_TABLE"
printf '%s\n' "$FAKE_VIOL" | grep -qF "probeunscanned/entity/ProbeUnscannedEntity.java" \
    || fail "阴性对照失效: 在**不被扫描的**子树里放了一个表在快照里的实体, 闸却没报出来 —— 那上面三组断言对新子树是瞎的"
ok "阴性对照: 不被扫描的子树里出现软删实体 ⇒ 被点名"

# 撤回那个文件 ⇒ 必须回到干净, 证明上面那条命中确实来自它(而不是别的东西)
rm -f "$FAKE_ROOT/$FAKE_REL"
FAKE_AFTER=$(scan_violations "$FAKE_ROOT" || true)
[ -z "$(printf '%s\n' "$FAKE_AFTER" | grep -c . | grep -v '^0$')" ] \
    || fail "撤回探针文件后仍有命中 —— 上面那条命中不能归因到它"
ok "撤回探针 ⇒ 回到干净 (命中可归因)"

# ── 5. 错误信息必须写清下一步(否则下一个人红了还是只扩一个根)────────────────
case "$NEXT_STEPS" in
    *"一起"*) ;; *) fail "下一步说明里没写「两个根一起扩」" ;;
esac
case "$NEXT_STEPS" in
    *"--write"*) ;; *) fail "下一步说明里没给重新生成的命令" ;;
esac
case "$NEXT_STEPS" in
    *"Repository"*) ;; *) fail "下一步说明里没提醒核 Repository 判据的数" ;;
esac
ok "错误信息带可照做的下一步(两个根一起扩 / --write 重新生成 / 核 Repository 判据)"

echo "PASS: test-soft-delete-scan-root-covers-entities.sh ($PASSED 组断言)"
