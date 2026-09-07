#!/usr/bin/env bash
# GUARDS=backend/java/cretas-api/src/main/resources/db/baseline/**,scripts/ops/capture-fresh-db-baseline.sh
# SCRIPT_TEST_TIMEOUT=420
#
# 离线验证器：仓库里那份基线，今天还能重放成**它自己头里记的那五个数**吗。
#
# ═══════════════════════════════════════════════════════════════════════════
# 🔴 为什么它与采集器是**两件事**（票 OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907）
# ═══════════════════════════════════════════════════════════════════════════
#            | 采集(capture-fresh-db-baseline.sh) | 验证(本文件)
#   需 prod 凭证 |            ✅ 是                  |      ❌ 否
#   进 CI       |         ⛔ **不进**               |   ✅ **每次都跑**
#   谁跑        |   人, 有人看着, 带 review          |      CI
#
# ⛔ **CI 不持有 prod 凭证、不连 prod。这条没有例外。**
# ⚠️ 把两者混成一个脚本会逼你在 CI 里放 prod 凭证 ——
#    那是**为了自动化去换掉一条安全边界**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据
# ═══════════════════════════════════════════════════════════════════════════
# 基线文件头里记着采集当时的五个数（判据 ③「带出处」的产物）。本验证器把基线灌进一个
# **空 PG**，重新数那五个数，**逐位比对**。
#
# ⛔ **不是「跑完没报错」** —— 实测(2026-09-07): 从 dump 删掉 1 条 CREATE INDEX 之后,
#    重放**依然 rc=0 / 零 ERROR**, 只有索引数掉 1。「没报错」与「形状对」是两回事。
#
# ⚠️ 它守的是「**这份文件还是不是它声称的那个东西**」, ⛔ 不守「它与今天的 prod 一致」——
#    后者需要连 prod, 属于采集器那一侧(判据 ④ / BASELINE_LAG)。
#    ▎两句话的差别: 本验证器绿 ⇒ 基线**自洽**; ⛔ 不代表基线**不过期**。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
BASELINE_DIR="$ROOT_DIR/backend/java/cretas-api/src/main/resources/db/baseline"
IMAGE="${CRETAS_BASELINE_PG_IMAGE:-pgvector/pgvector:pg17}"
CONTAINER="cretas-baseline-replay-$$"
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }
cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# ---- 1. 找到那份基线 ----
# ⚠️ 基线还没产出是**合法状态**, 但必须报成「没量到」, ⛔ 不是「通过」——
#    两者在退出码上同形, 而前者意味着这一趟什么都没验。
if [ ! -d "$BASELINE_DIR" ]; then
    echo "READING: BASELINE_VERIFY=skipped reason=dir-absent (基线尚未产出 ⇒ **没量到**, ⛔ 不是「没问题」)"
    echo "PASS: 0 项断言(本趟没有可验的基线)"
    exit 0
fi
mapfile -t FILES < <(find "$BASELINE_DIR" -maxdepth 1 -name '*.sql' -size +0 | sort)
if [ "${#FILES[@]}" -eq 0 ]; then
    fail "1: db/baseline/ 存在但没有非空基线 —— 目录在而内容空, ⛔ 不许算绿"
fi
[ "${#FILES[@]}" -eq 1 ] \
    || fail "1: db/baseline/ 下有 ${#FILES[@]} 份基线, 期望恰好 1 份 —— 两份基线一定会漂(形态 D): ${FILES[*]}"
BASELINE="${FILES[0]}"
echo "READING: BASELINE_FILE=$(basename "$BASELINE") bytes=$(wc -c < "$BASELINE")"
ok "找到恰好一份非空基线"

# ---- 2. 从文件头解析出处与五个数 ----
# ⛔ 解析不出来就红, 不回退到「跳过」—— 一份没有出处的基线**不可验证**,
#    而「不可验证」与「验过了」必须长得不一样。
hdr=$(head -30 "$BASELINE")
H_VER=$(printf '%s' "$hdr" | sed -nE 's/^-- flyway_schema_history 最大版本: ([0-9.]+).*/\1/p' | head -1)
H_LINE=$(printf '%s' "$hdr" | grep -m1 'tables=' || true)
H_TAB=$(printf '%s' "$H_LINE" | sed -nE 's/.*tables=([0-9]+).*/\1/p')
H_IDX=$(printf '%s' "$H_LINE" | sed -nE 's/.*indexes=([0-9]+).*/\1/p')
H_UQ=$(printf  '%s' "$H_LINE" | sed -nE 's/.*unique=([0-9]+).*/\1/p')
H_FK=$(printf  '%s' "$H_LINE" | sed -nE 's/.*fk=([0-9]+).*/\1/p')
H_COL=$(printf '%s' "$H_LINE" | sed -nE 's/.*columns=([0-9]+).*/\1/p')
for v in "$H_VER" "$H_TAB" "$H_IDX" "$H_UQ" "$H_FK" "$H_COL"; do
    [ -n "$v" ] \
        || fail "2: 基线文件头里解析不出出处/五个数 (max_version='$H_VER' tables='$H_TAB' indexes='$H_IDX' unique='$H_UQ' fk='$H_FK' columns='$H_COL') —— 一份没有出处的基线**不可验证**, ⛔ 不许当成验过了"
done
echo "READING: BASELINE_HEADER max_version=$H_VER tables=$H_TAB indexes=$H_IDX unique=$H_UQ fk=$H_FK columns=$H_COL"
ok "文件头带出处与五个数"

# ---- 3. 灌进一个空 PG ----
command -v docker >/dev/null 2>&1 \
    || fail "3: 没有 docker —— 本验证器要一个空 PG。⛔ 不许把「跳过」当通过(那正是这道闸最容易失效的方式)"
cleanup
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=verify_local_only \
    -e POSTGRES_USER=cretas_user -e POSTGRES_DB=cretas_db "$IMAGE" >/dev/null 2>&1 \
    || fail "3: 起不了验证容器($IMAGE)"
ready=0
for _ in $(seq 1 40); do
    docker exec "$CONTAINER" pg_isready -U cretas_user -d cretas_db >/dev/null 2>&1 && { ready=1; break; }
    sleep 2
done
[ "$ready" -eq 1 ] || fail "3: 容器 40 次探测仍未就绪 —— **没量到**"
docker exec "$CONTAINER" psql -U cretas_user -d cretas_db -Atc 'CREATE EXTENSION IF NOT EXISTS vector' >/dev/null 2>&1
docker cp "$BASELINE" "$CONTAINER:/baseline.sql" >/dev/null || fail "3: 拷不进容器"
n_err=$(docker exec "$CONTAINER" bash -c \
    'psql -U cretas_user -d cretas_db -f /baseline.sql >/dev/null 2>/tmp/e.log; grep -c "^ERROR:" /tmp/e.log' \
    2>/dev/null | tr -d '\r\n')
[ -n "$n_err" ] || n_err=unknown
ok "基线已灌进空 PG (errors=$n_err)"

# ---- 4. 🔴 主判据: 重放出的五个数 == 文件头记的五个数 ----
# ⚠️ 这里刻意**带 schema 过滤** —— 这个库里任何按名字数的东西都必须带 schema 过滤
#    (prod 上有 7 个 schema; 同源坑咬过一次)。
shape=$(docker exec "$CONTAINER" psql -U cretas_user -d cretas_db -Atc "
   SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE')
     ||'|'||(SELECT count(*) FROM pg_indexes WHERE schemaname='public')
     ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='u')
     ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='f')
     ||'|'||(SELECT count(*) FROM information_schema.columns WHERE table_schema='public')" 2>/dev/null | tr -d '\r')
IFS='|' read -r R_TAB R_IDX R_UQ R_FK R_COL <<< "$shape"
for v in "$R_TAB" "$R_IDX" "$R_UQ" "$R_FK" "$R_COL"; do
    case "$v" in ''|*[!0-9]*) fail "4: 重放后数不出五个数(实得 '$shape') —— **没量到**, ⛔ 与「形状不符」是两回事" ;; esac
done
echo "READING: BASELINE_REPLAY errors=$n_err tables=$R_TAB indexes=$R_IDX unique=$R_UQ fk=$R_FK columns=$R_COL"
diffs=""
[ "$R_TAB" = "$H_TAB" ] || diffs="$diffs tables($R_TAB≠$H_TAB)"
[ "$R_IDX" = "$H_IDX" ] || diffs="$diffs indexes($R_IDX≠$H_IDX)"
[ "$R_UQ"  = "$H_UQ"  ] || diffs="$diffs unique($R_UQ≠$H_UQ)"
[ "$R_FK"  = "$H_FK"  ] || diffs="$diffs fk($R_FK≠$H_FK)"
[ "$R_COL" = "$H_COL" ] || diffs="$diffs columns($R_COL≠$H_COL)"
[ -z "$diffs" ] \
    || fail "4: 基线重放出的形状与它**自己头里记的**不同 ——$diffs
  ⇒ 这份文件已经不是它声称的那个东西了(被编辑过? 被截断过?)。
  ⚠️ 注意 errors=$n_err —— ⛔「没报错」不是判据: 删掉一条 CREATE INDEX 的基线照样零错误。"
ok "五维与文件头逐位相同 (tables=$R_TAB indexes=$R_IDX unique=$R_UQ fk=$R_FK columns=$R_COL)"

# ---- 5. 边界: 本验证器**不**回答「基线过期了没有」----
echo "READING: VERIFIER_SCOPE=self-consistency-only (⛔ 不连 prod ⇒ 守不住「基线过期」; 那由采集器的判据 ④ / BASELINE_LAG 负责)"
ok "边界已声明"

echo "PASS: $pass 项断言全部通过"
