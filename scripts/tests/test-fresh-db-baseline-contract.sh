#!/usr/bin/env bash
# GUARDS=scripts/ops/capture-fresh-db-baseline.sh,backend/java/cretas-api/src/main/resources/application-pg.properties,backend/java/cretas-api/src/main/resources/application-pg-prod.properties
#
# baseline bootstrap 的两条禁令 —— ⛔ 写成闸, 因为注释挡不住。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (票 OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907)
# ═══════════════════════════════════════════════════════════════════════════
# 新库改成「灌 schema 基线 + flyway baseline + flyway migrate」之后, 有一颗雷:
#
#   **最自然的做法恰好是错的** —— 把那份 schema dump 命名成 `V…__baseline.sql`
#   放进 `db/flyway/`。prod 的 flyway_schema_history 里没有它 ⇒ **下次部署会把它当成
#   一条新迁移执行**, 在一个已有 502 张表的库上重跑整份建表脚本。
#
# ⚠️ 而且失败形态很坏: dump 满是 `CREATE TABLE IF NOT EXISTS` ⇒ 多半是
#    **大部分语句静默 no-op、少数报错**, 读起来像「一次有点吵但成功的部署」。
#    ⇒ 与本仓这一族同形: **失败长成正常**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 边界 (⛔ 别把这道闸当成更强的东西)
# ═══════════════════════════════════════════════════════════════════════════
# 它守的是**文件名与配置**这两件可机械判定的事。它 ⛔ 守不住「有人手工把 dump 内容
# 粘进一条真的迁移里」—— 那种要靠 review。⇒ 这条边界打成读数, 不假装守住了。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
RES="$ROOT_DIR/backend/java/cretas-api/src/main/resources"
BASELINE_DIR="$RES/db/baseline"
FLYWAY_DIR="$RES/db/flyway"
CAPTURE="$ROOT_DIR/scripts/ops/capture-fresh-db-baseline.sh"
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }

[ -s "$CAPTURE" ] || fail "找不到采集脚本 $CAPTURE"
bash -n "$CAPTURE" || fail "$CAPTURE 语法错误"

# ---- 1. 禁令一: db/baseline/ 下 ⛔ 不许有 V…__ 开头的文件 ----
# ⚠️ 目录还不存在是**合法状态**(基线尚未产出), 但必须**说出来**, ⛔ 不静默跳过 ——
#    「目录不存在」与「目录里没有违规文件」在退出码上同形, 而前者意味着这一条**没量到**。
if [ -d "$BASELINE_DIR" ]; then
    offenders=$(find "$BASELINE_DIR" -maxdepth 1 -name 'V*__*.sql' -printf '%f\n' 2>/dev/null | tr '\n' ' ')
    n_sql=$(find "$BASELINE_DIR" -maxdepth 1 -name '*.sql' | wc -l)
    n_nonempty=$(find "$BASELINE_DIR" -maxdepth 1 -name '*.sql' -size +0 | wc -l)
    echo "READING: BASELINE_DIR=present files=$n_sql non_empty=$n_nonempty"
    [ -z "$(printf '%s' "$offenders" | tr -d ' ')" ] \
        || fail "1: db/baseline/ 下出现了 Flyway 迁移命名的文件:$offenders —— ⛔ 它会在 prod 上被当成新迁移执行"
    # 🔴 目录在但**空着**时, 上面那条 offenders 恒为空 ⇒ 闸绿, 而它什么都没守到。
    #    ⚠️ 「闸绿」与「禁令生效」在这一步读数相同 —— 这正是「每加一道闸先答:
    #    **它自己坏掉时读数长什么样**」。实测: 建一个空的 db/baseline/ ⇒ files=0 而闸照样 PASS。
    #    ⇒ 目录一旦存在, 就必须真的有一个非空基线。
    [ "$n_nonempty" -ge 1 ] \
        || fail "1: db/baseline/ 存在但没有非空的 .sql (files=$n_sql non_empty=$n_nonempty) —— 本条**没守到东西**, ⛔ 不许算绿"
    ok "db/baseline/ 下没有 V…__ 命名的文件, 且有 $n_nonempty 个非空基线"
else
    echo "READING: BASELINE_DIR=absent (基线尚未产出 ⇒ 本条**没量到**, 不是「没问题」)"
    ok "db/baseline/ 尚不存在(允许; 但本条这次没有量到东西)"
fi

# ---- 2. 禁令一的另一半: db/flyway/ 下 ⛔ 不许出现 baseline dump ----
# 判据取内容特征而不是文件名: 一份 pg_dump 会带 `-- Dumped from database version`。
# ⚠️ 只判文件名挡不住「改个名字放进去」, 而那正是这颗雷的形状。
dump_in_flyway=$(grep -rl -- '-- Dumped from database version' "$FLYWAY_DIR" 2>/dev/null | sed 's#.*/##' | tr '\n' ' ')
[ -z "$(printf '%s' "$dump_in_flyway" | tr -d ' ')" ] \
    || fail "2: db/flyway/ 下混进了 pg_dump 产物:$dump_in_flyway —— ⛔ 那会在 prod 上重跑整份建表脚本"
ok "db/flyway/ 下没有 pg_dump 产物(按内容特征判, ⛔ 不只看文件名)"

# ---- 3. 禁令二: spring.flyway.locations ⛔ 不许含 baseline ----
loc_files=$(grep -rl 'spring.flyway.locations' "$RES" 2>/dev/null)
[ -n "$loc_files" ] || fail "3: 一处 spring.flyway.locations 都找不到 —— **没量到**(配置改名了?), ⛔ 不报绿"
n_loc=0
for f in $loc_files; do
    n_loc=$((n_loc + 1))
    line=$(grep 'spring.flyway.locations' "$f" | head -1)
    printf '%s' "$line" | grep -qi 'baseline' \
        && fail "3: $(basename "$f") 的 locations 含 baseline —— ⛔ 基线一旦进搜索路径就会被当迁移跑: $line"
done
echo "READING: FLYWAY_LOCATIONS_FILES=$n_loc"
ok "$n_loc 处 spring.flyway.locations 都不含 baseline"

# ---- 4. 采集脚本默认 ⛔ 不落盘 ----
# ⚠️ 守的是**行为**: 必须存在「WRITE 不为 1 就提前退出」这条路径。
grep -q 'if \[ "$WRITE" -ne 1 \]' "$CAPTURE" \
    || fail "4: 采集脚本没有「默认不落盘」的分支 —— 一个默认就写文件的采集脚本会让人不敢跑它"
grep -q '⛔ 未落盘' "$CAPTURE" || fail "4: 默认路径没有把「未落盘」打出来"
ok "采集脚本默认不落盘, 且会明说"

# ---- 5. 三条判据都必须在脚本里, 且**落盘前**执行 ----
# ⚠️ 判据写了但排在写文件之后 = 没有判据。⇒ 比行号。
line_repro=$(grep -n 'READING: BASELINE_REPRODUCIBLE' "$CAPTURE" | head -1 | cut -d: -f1)
line_shape=$(grep -n 'READING: REPLAYED_SHAPE'        "$CAPTURE" | head -1 | cut -d: -f1)
line_write=$(grep -n 'READING: BASELINE_WRITTEN'      "$CAPTURE" | head -1 | cut -d: -f1)
for v in "$line_repro" "$line_shape" "$line_write"; do
    [ -n "$v" ] || fail "5: 三条判据的读数不全(repro=$line_repro shape=$line_shape write=$line_write)"
done
[ "$line_repro" -lt "$line_write" ] && [ "$line_shape" -lt "$line_write" ] \
    || fail "5: 判据排在落盘之后(repro=$line_repro shape=$line_shape write=$line_write) —— 那等于没有判据"
echo "READING: CRITERIA_ORDER repro=$line_repro shape=$line_shape write=$line_write"
ok "三条判据都在落盘之前执行"

# ---- 6. 边界: 这道闸守不住什么(⛔ 打成读数, 不假装) ----
echo "READING: GATE_SCOPE=filename+config-only (⛔ 守不住「有人把 dump 内容手工粘进一条真迁移里」—— 那要靠 review)"
ok "边界已声明"

echo "PASS: $pass 项断言全部通过"
