#!/usr/bin/env bash
# 从 prod 采集 schema 基线（bootstrap 用），并在落盘之前**证明它是对的**。
#
# 票: OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907
# 文档: docs/ops/fresh-db-baseline-bootstrap-2026-09-07.md
#
# ═══════════════════════════════════════════════════════════════════════════
# ⛔ 两条禁令（写在这里，因为这是产出那个文件的地方）
# ═══════════════════════════════════════════════════════════════════════════
# 1. **基线不是一条 Flyway 迁移。** 产出文件名**刻意不带** `V…__` 前缀，
#    ⛔ 谁也不许把它改成 `V…__baseline.sql` 放进 `db/flyway/`：
#    prod 的 `flyway_schema_history` 里没有它 ⇒ **下次部署会把它当新迁移执行**，
#    在一个已有 502 张表的库上重跑整份建表脚本。
#    ⚠️ 失败形态很坏：dump 满是 `CREATE TABLE IF NOT EXISTS` ⇒ 多半是
#    **大部分语句静默 no-op、少数报错**，读起来像「一次有点吵但成功的部署」。
# 2. 输出目录 `db/baseline/` **⛔ 不许进 `spring.flyway.locations`**。
#
# ⚠️ 这两条**注释挡不住**（本仓 2026-09-07 一天用过三次这句）⇒ 由
#    scripts/tests/test-fresh-db-baseline-contract.sh 写成闸。
#
# ═══════════════════════════════════════════════════════════════════════════
# 三条判据（默认全跑；⛔ 不满足就不落盘）
# ═══════════════════════════════════════════════════════════════════════════
# ① 可复现：连采两次，**归一化后**逐字相同。
#    🔴 ⚠️ 归一化是必需的，⛔ 不是「除时间戳外」：实测两次 pg_dump 差异只有 2 行，
#       且不是时间戳，是 PG17 每次生成的 `\restrict` / `\unrestrict` **随机 token**
#       （其余 42959 行相同）。不归一化 ⇒ 这条判据**恒假** ⇒ 会把一个其实完全
#       可复现的做法误判成不可复现。
# ② 可验证：把它灌进一个空 PG，数**五个**形状数并与**当时的 prod** 逐位比对。
#    ⛔ 不是「跑完没报错」——实测删掉 1 条 CREATE INDEX 之后，重放**依然 rc=0 / 零 ERROR**，
#       只有索引数掉了 1。「没报错」与「形状对」是两回事。
#    ⚠️ 五个数而不是两个：只比表和索引，唯一约束/FK/列数不对得上时那个「对」是假的。
# ③ 带出处：文件头记 prod 的 `flyway_schema_history` 最大版本 + 采集时刻 + 五个数。
#    ⇒ 基线的**新鲜度必须是一个可读的数**，⛔ 不能靠 git 提交时间猜。
#
# ═══════════════════════════════════════════════════════════════════════════
# 🔴 这个库里任何按名字数的东西，都必须带 schema 过滤
# ═══════════════════════════════════════════════════════════════════════════
# prod 上有 **7 个 schema**（public + 6 个 purge/备份残留：tenant_purge_68 /
# f006_clear_71 / legacy_retired / bak_trf_* ×3）。
# ⇒ `pg_dump` 必须带 `-n public`；所有 `count(*)` 必须带 `table_schema='public'`
#   或 `schemaname='public'`。⚠️ 同源坑已咬过一次：某次 census 的 count(*) 没带过滤，
#   把 tenant_purge_68 的同名列数进去读出 1，按 public 过滤是 0。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
OUT_DIR="$REPO_ROOT/backend/java/cretas-api/src/main/resources/db/baseline"
OUT_FILE="$OUT_DIR/prod-schema-baseline.sql"      # ⛔ 刻意不是 V…__ 开头
RES_FLYWAY_DIR="$REPO_ROOT/backend/java/cretas-api/src/main/resources/db/flyway"
PROD_HOST="${CRETAS_PROD_SSH:-root@47.100.235.168}"
PROD_DB="${CRETAS_PROD_DB:-cretas_prod_db}"
VERIFY_IMAGE="${CRETAS_BASELINE_PG_IMAGE:-pgvector/pgvector:pg17}"
VERIFY_CONTAINER="cretas-baseline-verify-$$"
WRITE=0
KEEP=0
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; docker rm -f "$VERIFY_CONTAINER" >/dev/null 2>&1 || true' EXIT

log()  { printf '[baseline] %s\n' "$*"; }
fail() { printf '[baseline] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
    cat >&2 <<'USAGE'
usage: bash scripts/ops/capture-fresh-db-baseline.sh [--write] [--keep]

  (默认)   只采集 + 跑三条判据, **⛔ 不落盘** —— 用来审这套流程本身。
  --write  三条判据全过之后才写 db/baseline/prod-schema-baseline.sql。
  --keep   保留验证用的临时容器(排障用)。

⛔ 本脚本只读 prod（pg_dump --schema-only + 若干 count），一个字都不写。
USAGE
    exit 2
}
while (($#)); do
    case "$1" in
        --write) WRITE=1; shift ;;
        --keep)  KEEP=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

command -v ssh >/dev/null 2>&1 || fail "需要 ssh"
command -v docker >/dev/null 2>&1 || fail "需要 docker（判据 ② 要在空库上重放）"

# ── 归一化：抹掉 PG17 每次随机生成的 \restrict / \unrestrict token ───────────
# ⛔ 不要删整行 —— 那两行是 psql 元命令，灌库时需要它们；只把 token 换成占位符用于**比较**。
normalize() { sed -E 's/^\\(restrict|unrestrict) .*/\\\1 <NORMALIZED>/' "$1"; }

dump_once() {   # $1 = 落到哪个本地文件
    local remote="/tmp/cretas-baseline-$$-$RANDOM.sql"
    ssh -o BatchMode=yes -o ConnectTimeout=25 "$PROD_HOST" \
        "sudo -u postgres pg_dump --schema-only --no-owner --no-privileges -n public $PROD_DB > $remote 2>/dev/null; md5sum $remote | cut -d' ' -f1" \
        > "$WORK/remote.md5" 2>/dev/null || { ssh -o BatchMode=yes "$PROD_HOST" "rm -f $remote" >/dev/null 2>&1; fail "pg_dump 失败"; }
    scp -q -o ConnectTimeout=25 "$PROD_HOST:$remote" "$1" || fail "取回 dump 失败"
    ssh -o BatchMode=yes "$PROD_HOST" "rm -f $remote" >/dev/null 2>&1 || true
    # ⚠️ 两侧 md5 必须一致才继续 —— 2026-09-07 实测过一次「同名旧文件静默替换」，
    #    而那次 scp 与 psql 都报成功。判据是**内容的身份**，⛔ 不是「路径对不对」。
    local local_md5 remote_md5
    local_md5=$(md5sum "$1" | cut -d' ' -f1)
    remote_md5=$(tr -d '\r\n' < "$WORK/remote.md5")
    [ "$local_md5" = "$remote_md5" ] \
        || fail "传输后 md5 不一致（远端 $remote_md5 / 本地 $local_md5）—— ⛔ 不拿一个来源不明的文件当基线"
}

# ── 判据 ①：可复现 ─────────────────────────────────────────────────────────
log "判据 ①: 连采两次, 归一化后必须逐字相同"
dump_once "$WORK/d1.sql"
sleep 2
dump_once "$WORK/d2.sql"
normalize "$WORK/d1.sql" > "$WORK/n1.sql"
normalize "$WORK/d2.sql" > "$WORK/n2.sql"
RAW_DIFF=$(diff "$WORK/d1.sql" "$WORK/d2.sql" | grep -cE '^[<>]' || true)
NORM_DIFF=$(diff "$WORK/n1.sql" "$WORK/n2.sql" | grep -cE '^[<>]' || true)
echo "READING: BASELINE_REPRODUCIBLE raw_diff_lines=$RAW_DIFF normalized_diff_lines=$NORM_DIFF bytes=$(wc -c < "$WORK/d1.sql")"
[ "$NORM_DIFF" -eq 0 ] \
    || { diff "$WORK/n1.sql" "$WORK/n2.sql" | head -20 >&2; fail "判据 ①: 归一化之后仍有 $NORM_DIFF 行不同 —— 这个 dump 不可复现, ⛔ 不能当基线"; }
# 阳性对照: 归一化必须**真的**改动了东西(否则 NORM_DIFF=0 可能只是两次碰巧相同)
[ "$RAW_DIFF" -gt 0 ] \
    && log "判据 ① 通过 (归一化前 $RAW_DIFF 行不同 —— 全是 \\restrict/\\unrestrict 的随机 token)" \
    || log "⚠️ 判据 ① 通过, 但归一化前也没有差异 —— 这次没有验到归一化本身是否必要"

# ── 采集出处（判据 ③ 的原料）───────────────────────────────────────────────
log "采集 prod 出处与五个形状数"
PROD_META=$(ssh -o BatchMode=yes -o ConnectTimeout=25 "$PROD_HOST" \
    "sudo -u postgres psql $PROD_DB -Atc \"
     SELECT (SELECT max(version) FROM flyway_schema_history WHERE success=true)
       ||'|'||(SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE')
       ||'|'||(SELECT count(*) FROM pg_indexes WHERE schemaname='public')
       ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='u')
       ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='f')
       ||'|'||(SELECT count(*) FROM information_schema.columns WHERE table_schema='public')\"" 2>/dev/null | tr -d '\r')
IFS='|' read -r P_VER P_TAB P_IDX P_UQ P_FK P_COL <<< "$PROD_META"
for v in "$P_VER" "$P_TAB" "$P_IDX" "$P_UQ" "$P_FK" "$P_COL"; do
    [ -n "$v" ] || fail "prod 出处/形状数读不全（实得 '$PROD_META'）—— **没量到**, ⛔ 不落盘"
done
echo "READING: PROD_SHAPE max_version=$P_VER tables=$P_TAB indexes=$P_IDX unique=$P_UQ fk=$P_FK columns=$P_COL"

# ── 判据 ④: 基线**之上**的迁移, 文件必须都在 ──────────────────────────────────
#
# 🔴 为什么这条的写法与评审给的不同 —— 评审版本**恒真**:
#   评审说「prod history 里所有 version > baseline_max 的行, 其文件必须存在」。
#   但 baseline_max **就是** prod history 的最大版本 ⇒ 那个集合**按构造永远为空**,
#   ⛔ 不只是「今天为空」。一条恒真的判据会以「一直绿」的方式活着。
#
# ⇒ 有意义的形态是**拿已经 checked-in 的那份基线**去比**现在的 prod**:
#   基线是在 prod@X 那一刻采的; 之后 prod 又跑了 X+1…Y。
#   若其中某条的**文件已不存在**(本仓实测有 5 个 version 是这样),
#   那么「灌这份基线 + 跑基线之上的迁移」得到的库会**静默漏掉**那条的效果。
#   ⇒ 这才是「基线之上的缺文件是致命的」那句话真正指的东西。
#
# ⚠️ 首次运行(还没有 checked-in 基线)时这一条**没有输入** ⇒ 报「没量到」, ⛔ 不报「通过」。
BASE_MAX="${CRETAS_BASELINE_ASSUME_MAX:-}"   # ← 仅供阴性对照用, 见 test-fresh-db-baseline-contract.sh
if [ -z "$BASE_MAX" ] && [ -f "$OUT_FILE" ]; then
    BASE_MAX=$(grep -oE '^-- flyway_schema_history 最大版本: [0-9.]+' "$OUT_FILE" | head -1 | sed 's/.*: //')
fi
# ⚠️ 用 `V<数字>_<数字>__` 这个**完整**锚点取版本号 —— 只写 `^V[0-9_]+` 会把文件名里
#    做分隔用的 `__` 也吃进去, 读数变成 `20261101.39..`(尾部两个点)。
#    ▎一个带尾巴的读数在比较时会静默走错分支, 而它看起来「差不多对」。
REPO_MAX=$(ls "$RES_FLYWAY_DIR" 2>/dev/null | sed -nE 's/^V([0-9]+)_([0-9]+)__.*/\1.\2/p' \
    | sort -t. -k1,1n -k2,2n | tail -1)
if [ -z "$BASE_MAX" ]; then
    echo "READING: BASELINE_LAG baseline_max=<none> prod_max=$P_VER repo_max=${REPO_MAX:-?} —— **没量到**(还没有 checked-in 基线), ⛔ 不算通过"
else
    ABOVE=$(ssh -o BatchMode=yes -o ConnectTimeout=25 "$PROD_HOST" \
        "sudo -u postgres psql $PROD_DB -Atc \"SELECT script FROM flyway_schema_history WHERE success=true AND script LIKE '%.sql' AND version::text > '$BASE_MAX' ORDER BY installed_rank\"" 2>/dev/null | tr -d '\r')
    n_above=0; missing_above=""
    while IFS= read -r sc; do
        [ -n "$sc" ] || continue
        n_above=$((n_above + 1))
        [ -f "$RES_FLYWAY_DIR/$sc" ] || missing_above="$missing_above $sc"
    done <<< "$ABOVE"
    n_missing=$(printf '%s' "$missing_above" | wc -w)
    echo "READING: BASELINE_LAG baseline_max=$BASE_MAX prod_max=$P_VER repo_max=${REPO_MAX:-?} above_baseline_on_prod=$n_above missing_files_above=$n_missing"
    [ "$n_missing" -eq 0 ] \
        || fail "判据 ④: 基线(@$BASE_MAX)**之上**有 $n_missing 条已在 prod 跑过的迁移, 但仓库里文件不存在:$missing_above
  ⇒ 「灌这份基线 + 跑基线之上的迁移」会**静默漏掉**它们的效果。
  ⛔ 这不是「基线旧了」可以忽略的那种旧 —— 缺的那几条**永远补不回来**(文件已被删)。"
    log "判据 ④ 通过 (基线之上 $n_above 条, 文件全在)"
fi
# ⚠️ prod 落后于 main 是**常态** ⇒ ⛔ 不要试图把 baseline_max 与 repo_max 的差消成 0,
#    要的是让它**可读**。

# ── 判据 ②：可验证 ─────────────────────────────────────────────────────────
log "判据 ②: 灌进一个空 PG, 五个数必须与 prod 逐位相同"
docker rm -f "$VERIFY_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$VERIFY_CONTAINER" -e POSTGRES_PASSWORD=verify_local_only \
    -e POSTGRES_USER=cretas_user -e POSTGRES_DB=cretas_db "$VERIFY_IMAGE" >/dev/null 2>&1 \
    || fail "起不了验证容器"
ready=0
for _ in $(seq 1 40); do
    docker exec "$VERIFY_CONTAINER" pg_isready -U cretas_user -d cretas_db >/dev/null 2>&1 && { ready=1; break; }
    sleep 2
done
[ "$ready" -eq 1 ] || fail "验证容器 40 次探测仍未就绪 —— **没量到**"
docker exec "$VERIFY_CONTAINER" psql -U cretas_user -d cretas_db -Atc 'CREATE EXTENSION IF NOT EXISTS vector' >/dev/null 2>&1
docker cp "$WORK/d1.sql" "$VERIFY_CONTAINER:/baseline.sql" >/dev/null || fail "拷不进容器"
docker exec "$VERIFY_CONTAINER" bash -c 'psql -U cretas_user -d cretas_db -f /baseline.sql >/dev/null 2>/tmp/e.log; grep -c "^ERROR:" /tmp/e.log' > "$WORK/errs" 2>/dev/null || true
N_ERR=$(tr -d '\r\n' < "$WORK/errs"); [ -n "$N_ERR" ] || N_ERR=unknown
V_SHAPE=$(docker exec "$VERIFY_CONTAINER" psql -U cretas_user -d cretas_db -Atc "
     SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE')
       ||'|'||(SELECT count(*) FROM pg_indexes WHERE schemaname='public')
       ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='u')
       ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='f')
       ||'|'||(SELECT count(*) FROM information_schema.columns WHERE table_schema='public')" 2>/dev/null | tr -d '\r')
IFS='|' read -r V_TAB V_IDX V_UQ V_FK V_COL <<< "$V_SHAPE"
echo "READING: REPLAYED_SHAPE errors=$N_ERR tables=$V_TAB indexes=$V_IDX unique=$V_UQ fk=$V_FK columns=$V_COL"
mismatch=""
[ "$V_TAB" = "$P_TAB" ] || mismatch="$mismatch tables($V_TAB≠$P_TAB)"
[ "$V_IDX" = "$P_IDX" ] || mismatch="$mismatch indexes($V_IDX≠$P_IDX)"
[ "$V_UQ"  = "$P_UQ"  ] || mismatch="$mismatch unique($V_UQ≠$P_UQ)"
[ "$V_FK"  = "$P_FK"  ] || mismatch="$mismatch fk($V_FK≠$P_FK)"
[ "$V_COL" = "$P_COL" ] || mismatch="$mismatch columns($V_COL≠$P_COL)"
[ -z "$mismatch" ] \
    || fail "判据 ②: 重放出的形状与 prod 不同 ——$mismatch。⛔ 拒绝落盘（注意: errors=$N_ERR, 「没报错」不是判据）"
log "判据 ② 通过 (五维逐位相同; errors=$N_ERR)"
[ "$KEEP" -eq 1 ] || docker rm -f "$VERIFY_CONTAINER" >/dev/null 2>&1 || true

# ── 判据 ③：带出处 + 落盘 ──────────────────────────────────────────────────
if [ "$WRITE" -ne 1 ]; then
    log "三条判据全部通过。⛔ 未落盘（要落盘请加 --write）"
    exit 0
fi
mkdir -p "$OUT_DIR"
{
    printf -- '-- ╔══════════════════════════════════════════════════════════════════╗\n'
    printf -- '-- ║  prod schema 基线 —— ⛔ 这不是一条 Flyway 迁移                    ║\n'
    printf -- '-- ╚══════════════════════════════════════════════════════════════════╝\n'
    printf -- '-- ⛔ 不许改名成 V…__*.sql, ⛔ 不许放进 db/flyway/, ⛔ 不许进 spring.flyway.locations。\n'
    printf -- '--    理由: prod 的 flyway_schema_history 里没有它 ⇒ 下次部署会把它当新迁移,\n'
    printf -- '--    在一个已有 %s 张表的库上重跑整份建表脚本(且多半静默 no-op, 读起来像成功)。\n' "$P_TAB"
    printf -- '-- 用法: 新库 = psql 灌本文件 → flyway baseline -baselineVersion=%s → flyway migrate\n' "$P_VER"
    printf -- '--\n'
    printf -- '-- 出处(判据 ③): 采自 %s 的 %s, 采集时刻 %s\n' "$PROD_HOST" "$PROD_DB" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf -- '-- flyway_schema_history 最大版本: %s\n' "$P_VER"
    printf -- '-- 形状(与重放结果逐位相同): tables=%s indexes=%s unique=%s fk=%s columns=%s\n' "$P_TAB" "$P_IDX" "$P_UQ" "$P_FK" "$P_COL"
    printf -- '-- 只含 schema `public`(prod 另有 6 个 purge/备份 schema, 刻意不采)\n'
    printf -- '--\n'
    cat "$WORK/d1.sql"
} > "$OUT_FILE"
echo "READING: BASELINE_WRITTEN path=${OUT_FILE#$REPO_ROOT/} bytes=$(wc -c < "$OUT_FILE") max_version=$P_VER"
log "已落盘。⚠️ 下一步: 把 baselineVersion=$P_VER 接进建库流程, 并重置 fresh-db 进度棘轮的基线"
