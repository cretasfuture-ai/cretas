#!/usr/bin/env bash
# scripts/check-soft-delete-orphans.sh 里 MANUAL_PAIRS 那一段的契约测试。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么手工登记需要一道闸
# ═══════════════════════════════════════════════════════════════════════════
# 注解扫描器只认 `@ManyToOne`。**标量外键列(`@Column private String planId`)对它
# 结构上不存在** —— prod 上 process_sheet_rows.plan_id 有 56 行指向已硬删的计划,
# 它一条都看不见。⇒ 只能手工登记。
#
# 而手工清单会烂: 列改名 / 表改名 / 实体删掉之后, 清单还在, 生成的 SQL 当场报错,
# 那时 watch 脚本报的是 `psql-failed` —— **一个把「清单写错了」说成「数据库出问题了」
# 的读数**。这道闸把它挪到源码层, 在 PR 上就红。
#
# ⚠️ 它是**源码级**核对(实体 + 生成物), ⛔ 不连库 —— CI 里没有 Docker、也不该有
#    生产库凭证。连库那一层由生成器自己的 hasdel 过滤守着(手工对落不进 SQL 直接 exit 2)。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GEN="$ROOT_DIR/scripts/check-soft-delete-orphans.sh"
SQL="$ROOT_DIR/scripts/cron/soft-delete-orphans.sql"
ENTITY_ROOT="$ROOT_DIR/backend/java/cretas-api/src/main/java/com/cretas/aims/entity"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

[ -s "$GEN" ] || fail "$GEN 不存在"
[ -s "$SQL" ] || fail "$SQL 不存在 —— 生成物必须在仓库里(它是装到服务器那份的源)"
[ -d "$ENTITY_ROOT" ] || fail "找不到实体目录"
bash -n "$GEN" || fail "语法错误: $GEN"

# ── 生成物必须带出处戳 ────────────────────────────────────────────────────
# watch 脚本靠它判扫描面的出处。没有它就退回「说不清」, 而以前那个 mtime 判据
# (jar 每次部署都变新)是恒真的, 16/32 行台账全是它。
head -1 "$SQL" | grep -Eq '^-- generated-from [0-9a-f]{7,40}(-dirty)? [0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+Z$' \
    || fail "生成物第一行不是出处戳, 实得: $(head -1 "$SQL")"
ok "生成物带 -- generated-from 出处戳"

# ── 取出手工登记的对 ──────────────────────────────────────────────────────
# 🔴 第一版这里写的是 `| grep -E '^[a-z_]+\|'` —— 一个**筛子**。变异实测: 把表名改成
#    `process_sheet_rows_TYPO` 之后, 那一行**不匹配就被静默丢掉**, 清单从 2 条变 1 条,
#    测试照样绿。⛔ 一条格式不对的登记必须让闸红, 而不是从人口普查里消失(形态 A⁴:
#    「我看到的是全部吗」—— 筛子会把「写错了」变成「我没看见」)。
# ⇒ 先原样取出**所有**非空非注释行, 再逐行校验格式。
RAW=$(awk '/^# BEGIN_MANUAL_PAIRS$/{f=1;next} /^# END_MANUAL_PAIRS$/{f=0} f' "$GEN" \
      | grep -vE "^\s*#|^\s*$|^MANUAL_PAIRS='?\s*$|^'\s*$")
[ -n "$RAW" ] || fail "BEGIN_MANUAL_PAIRS/END_MANUAL_PAIRS 之间一条都没解析到 —— 仪器坏了, 本次读数作废"

N=$(printf '%s\n' "$RAW" | grep -c .)
while IFS= read -r line; do
    [ -n "$line" ] || continue
    printf '%s\n' "$line" | grep -qE '^[a-z_][a-z0-9_]*\|[a-z_][a-z0-9_]*\|[a-z_][a-z0-9_]*\|.+$' \
        || fail "登记行格式不对(应为 子表|外键列|父表|理由, 全小写): $line"
done <<< "$RAW"
PAIRS="$RAW"
echo "解析到 $N 条手工登记:"

# 实体表名 → 文件。@Table(name = "x") 可能后面跟逗号换行(indexes=...), 所以只锚定前半段。
entity_file_for_table() {
    grep -rl --include='*.java' -E "@Table\(name = \"$1\"" "$ENTITY_ROOT" | head -1
}
is_soft_delete() {
    grep -qE 'extends BaseEntity|@Where\(clause = "[^"]*deleted_at' "$1"
}

while IFS='|' read -r child col parent reason; do
    [ -n "$child" ] || continue
    tag="$child.$col -> $parent"

    # ① 理由不能为空 —— 登记是留痕, 没有理由的登记等于静默豁免。
    [ -n "${reason// /}" ] || fail "$tag: 没写理由"

    # ② 三个表名都要解析到真实体
    cf=$(entity_file_for_table "$child")
    pf=$(entity_file_for_table "$parent")
    [ -n "$cf" ] || fail "$tag: 找不到 @Table(name = \"$child\") 的实体"
    [ -n "$pf" ] || fail "$tag: 找不到 @Table(name = \"$parent\") 的实体"

    # ③ 外键列要在子实体上真实存在
    grep -qE "@Column\(name = \"$col\"" "$cf" \
        || fail "$tag: $(basename "$cf") 里没有 @Column(name = \"$col\")"

    # ④ 两端都必须是软删实体 —— 生成的 SQL 两边都写了 deleted_at IS NULL,
    #    有一端没有这列就是运行期 psql 报错(而那会被读成「数据库出问题了」)。
    is_soft_delete "$cf" || fail "$tag: $(basename "$cf") 不是软删实体"
    is_soft_delete "$pf" || fail "$tag: $(basename "$pf") 不是软删实体"

    # ⑤ 它必须**确实是扫描器看不见的那一类**。
    #    🔴 判据是 `@JoinColumn(name = "<col>")` 在不在, ⛔ 不是「@Column 上面几行有没有
    #       @ManyToOne」。变异实测: BatchEquipmentUsage 对 batch_id **同时**有一个标量
    #       `@Column(name="batch_id")`(第 32 行) 和一个 `@ManyToOne @JoinColumn(name=
    #       "batch_id")`(第 53 行) —— 按「@Column 上方 4 行」问, 答案是「没有 @ManyToOne」,
    #       于是一条**扫描器本来就收**的对被放行了。问错了问题, 不是断言没用(形态 C″)。
    #    有 @JoinColumn 就说明关联形态存在: 要么扫描器已经收了(重复登记 = 形态 D),
    #    要么它被 @JsonIgnore/@NotFound **有意**排除了(手工登记等于把那个决定推翻)。
    #    两种都该停下来说清楚, ⛔ 不许静默通过。
    if grep -qE "@JoinColumn\(name = \"$col\"" "$cf"; then
        fail "$tag: $(basename "$cf") 上 $col 已有 @JoinColumn 关联形态 —— 归扫描器管, ⛔ 不该手工登记"
    fi

    # ⑥ 「必发生」的读数(硬约束 9): 登记了就必须**真的出现在生成物里**。
    #    只断言「登记条目格式对」是不够的 —— 它可能一路被过滤掉而清单看起来很健康。
    grep -Fq "'$child.$col -> $parent'" "$SQL" \
        || fail "$tag: 登记了, 但生成物 $SQL 里没有它 —— 重跑 --write"

    ok "$tag  ($(basename "$cf") → $(basename "$pf"))"
done <<< "$PAIRS"

# ── 阴性对照: 明确不登记的那两张日志表, 不许偷偷混进生成物 ────────────────
# 它们指向已删计划是**正常状态**(审计/日志), 收进来等于每天报一次不需要行动的孤儿。
for t in scheduling_alerts process_sheet_row_change_log; do
    grep -Fq "'$t.plan_id ->" "$SQL" \
        && fail "$t 被登记进来了 —— 那是日志表, 需要单独的理由(见 conf 里的说明)"
done
ok "两张日志表确实不在生成物里(阴性对照)"

echo
echo "PASS: test-soft-delete-orphans-manual-pairs.sh ($N 条手工登记全部解析到真实列)"
