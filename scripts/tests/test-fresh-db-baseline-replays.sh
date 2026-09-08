#!/usr/bin/env bash
# GUARDS=ops/db-baseline/**,scripts/ops/capture-fresh-db-baseline.sh,scripts/ci/run-with-test-postgres.sh
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
#
# 🔴 ⛔ 不要把本闸的绿描述成「基线**自洽**」—— 那个词读起来像「内部一致所以没问题」,
#    而它的实际含义只是「**两侧串通得上**」。措辞本身就是那个洞:
#
#      只删索引            ⇒ 重放 2043 ≠ 头里 2044  ⇒ 红  ✅
#      只改头              ⇒ 重放 2044 ≠ 头里 9999  ⇒ 红  ✅
#      删索引 + 头里也改掉  ⇒ 两侧一致               ⇒ **绿**  🔴 挡不住
#
#    ⇒ 一份删了 17 个索引、头里写着少 17 的基线, 在本闸下**完全合格**。
#    ⛔ 判据 ④ / BASELINE_LAG 也不覆盖它 —— 那两个看的是「基线**之上** prod 跑了什么」,
#       ⛔ 不看「基线本身对不对」。▎**只有重新采集能证明基线是对的。**
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
BASELINE_DIR="$ROOT_DIR/ops/db-baseline"
if [ "${CRETAS_BASELINE_NATIVE_PG:-0}" = 1 ] && [ "${1:-}" != --isolated-pg-child ]; then
    exec bash "$ROOT_DIR/scripts/ci/run-with-test-postgres.sh" bash "$0" --isolated-pg-child
fi
native_pg=0
if [ "${1:-}" = --isolated-pg-child ]; then
    case "${SMARTBI_PG_DSN:-}" in
        postgresql://cretas_ci@127.0.0.1:*/cretas_workflow_verify_test) native_pg=1 ;;
        *) echo 'FAIL: native baseline requires the isolated CI database' >&2; exit 1 ;;
    esac
fi
IMAGE="${CRETAS_BASELINE_PG_IMAGE:-pgvector/pgvector:pg17}"
CONTAINER="cretas-baseline-replay-$$"
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }
replay_tmp=$(mktemp -d)
cleanup() {
    if [ "$native_pg" = 0 ]; then docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; fi
    rm -rf -- "$replay_tmp"
}
trap cleanup EXIT
db_sql() {
    if [ "$native_pg" = 1 ]; then psql -X "$SMARTBI_PG_DSN" -Atc "$1";
    else docker exec "$CONTAINER" psql -X -U cretas_user -d cretas_db -Atc "$1"; fi
}
db_file() {
    if [ "$native_pg" = 1 ]; then psql -X "$SMARTBI_PG_DSN" -f "$1";
    else docker exec -i "$CONTAINER" psql -X -U cretas_user -d cretas_db < "$1"; fi
}

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
    fail "1: ops/db-baseline/ 存在但没有非空基线 —— 目录在而内容空, ⛔ 不许算绿"
fi
[ "${#FILES[@]}" -eq 1 ] \
    || fail "1: ops/db-baseline/ 下有 ${#FILES[@]} 份基线, 期望恰好 1 份 —— 两份基线一定会漂(形态 D): ${FILES[*]}"
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

# ---- 2.5 「什么时候该重新采集」的那个数 ----
#
# 🔴 为什么这里用代理, 而不是评审指定的 above_baseline_on_prod:
#    那个数只由采集器产出, 而采集器要连 prod、⛔ 不进 CI、人手跑
#    ⇒ 拿它当触发条件是**循环的**: 要先做那个动作, 才能知道该不该做它。
#    ⇒ 实际后果是它**永远不会触发** —— 与「没有触发条件」同一个结果,
#      而那正是这一节要修的东西。
#
# ⚠️ **用代理替权威量之前, 先证明误差的方向。**
#    prod 的迁移只从 main 来 ⇒ 仓里高于基线的那批 ⊇ prod 已跑的那批
#    ⇒ 本代理**恒 ≥** above_baseline_on_prod ⇒ **早报不漏报**。
#    ⛔ 方向反过来(可能少报)的代理一律不能做告警 —— 它会以「一直安静」的方式失效,
#      而「一直安静」与「一切正常」在读数上完全同形。
#
# ⚠️ 本段**只打 ::notice, ⛔ 永不红** —— 理由与 payload_behind_by 同: 落后是常态,
#    红会让这道闸在第一周就被关掉。⇒ 它也因此**不构成「守着 db/flyway/」**,
#    所以刻意不写进本文件的 `# GUARDS=`(那份声明是给会**红**的行为用的)。
FLYWAY_DIR="$ROOT_DIR/backend/java/cretas-api/src/main/resources/db/flyway"
vkey() { awk -F. '{printf "%d\n", $1 * 1000000 + $2}' <<< "$1"; }
# ⚠️ 完整锚点 `V<数字>_<数字>__` —— 只写 `^V[0-9_]+` 会把分隔用的 `__` 吃进去,
#    读数变成 `20261101.39..`, 比较时静默走错分支而看起来「差不多对」。
mapfile -t REPO_VERS < <(ls "$FLYWAY_DIR" 2>/dev/null | sed -nE 's/^V([0-9]+)_([0-9]+)__.*/\1.\2/p' \
    | sort -t. -k1,1n -k2,2n)
REPO_MAX="${REPO_VERS[${#REPO_VERS[@]}-1]:-?}"
BASE_KEY=$(vkey "$H_VER")
LAG=0
for v in "${REPO_VERS[@]}"; do [ "$(vkey "$v")" -gt "$BASE_KEY" ] && LAG=$((LAG + 1)); done
THRESH="${CRETAS_BASELINE_LAG_THRESHOLD:-unset}"
if [ "$THRESH" = "unset" ]; then
    # ⛔ 「阈值还没设」与「没超阈值」必须长得不一样 —— 前者意味着这道告警**今天不会响**。
    ACTION="none-threshold-unset"
elif [ "$LAG" -gt "$THRESH" ]; then
    ACTION="notice"
else
    ACTION="none-under-threshold"
fi
echo "READING: BASELINE_LAG_PROXY above_baseline_in_repo=$LAG baseline_max=$H_VER repo_max=$REPO_MAX threshold=$THRESH action=$ACTION"
echo "READING: BASELINE_LAG_PROXY_DIRECTION=over-estimates ⇒ 恒 ≥ 采集器的 above_baseline_on_prod ⇒ **早报不漏报**(⛔ 反方向的代理不能做告警)"
if [ "$ACTION" = "none-threshold-unset" ]; then
    echo "READING: BASELINE_LAG_PROXY_ARMED=no ⇒ **阈值待定**(依据上线后 N 周的实际读数) ⇒ 这条告警今天**不会响**, ⛔ 不是「没超」"
fi
if [ "$ACTION" = "notice" ]; then
    echo "::notice title=基线该重新采集了::仓里已有 $LAG 条迁移的版本高于基线(@$H_VER, 仓库最高 $REPO_MAX), 超过阈值 $THRESH。owner=organizer。⛔ 这不是错误 —— 落后是常态, 到点重采一次即可(bash scripts/ops/capture-fresh-db-baseline.sh --write)。"
fi
ok "重新采集的触发条件已量出 (代理 above_baseline_in_repo=$LAG, action=$ACTION)"

# ---- 3. 灌进一个空 PG ----
if [ "$native_pg" = 0 ]; then
command -v docker >/dev/null 2>&1 \
    || fail "3: 没有 docker —— 本验证器要一个空 PG。⛔ 不许把「跳过」当通过(那正是这道闸最容易失效的方式)"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=verify_local_only \
    -e POSTGRES_USER=cretas_user -e POSTGRES_DB=cretas_db "$IMAGE" >/dev/null 2>&1 \
    || fail "3: 起不了验证容器($IMAGE)"
ready=0
for _ in $(seq 1 40); do
    docker exec "$CONTAINER" pg_isready -U cretas_user -d cretas_db >/dev/null 2>&1 && { ready=1; break; }
    sleep 2
done
[ "$ready" -eq 1 ] || fail "3: 容器 40 次探测仍未就绪 —— **没量到**"
fi
db_sql 'CREATE EXTENSION IF NOT EXISTS vector' >/dev/null 2>&1 || fail '3: vector extension unavailable'
# 🔴 这里原本写的是 `grep -c "^ERROR:"` —— 它**恒为 0**。
#    psql 把错误打成 `psql:/baseline.sql:38: ERROR:  ...`, **行首是 `psql:`**,
#    所以 `^ERROR:` 一条都匹配不到。⇒ 那个 errors= 读数从上线起就是个恒定的安心值,
#    而它被当作证据引用过好几次。
#    ▎**一个永远等于「没问题」的读数, 比没有这个读数更坏** —— 它占着位置。
# ⚠️ 阳性对照就在下面: 故意灌一条坏语句, 计数器必须真的动。
db_file "$BASELINE" > /dev/null 2>"$replay_tmp/replay.log"
replay_rc=$?
[ "$replay_rc" -eq 0 ] || fail "3: replay process failed with rc=$replay_rc: $(tail -10 "$replay_tmp/replay.log")"
n_err=$(grep -c 'ERROR:' "$replay_tmp/replay.log" || true)
# 已知良性的那一条: dump 里带 `CREATE SCHEMA public;`(第 38 行), 而空库上 public 天然存在。
# ⚠️ ⛔ 不要「先 DROP SCHEMA public 再灌」来消掉它 —— 实测那样会 **CASCADE 掉 vector 扩展**,
#    变成 56 个错误、498 张表(少 4 张)。▎消掉一条已知错误的办法, 可能比那条错误糟得多。
n_benign=$(grep -c 'ERROR:  schema "public" already exists' "$replay_tmp/replay.log" || true)
[ -n "$n_err" ] || n_err=unknown
[ -n "$n_benign" ] || n_benign=0
n_unexpected=$((n_err - n_benign))
echo "READING: BASELINE_REPLAY_ERRORS total=$n_err benign_public_schema=$n_benign unexpected=$n_unexpected"
[ "$n_unexpected" -eq 0 ] \
    || fail "3: 重放时出现 $n_unexpected 条**非预期**错误(总 $n_err, 已知良性 $n_benign):
$(grep 'ERROR:' "$replay_tmp/replay.log" | grep -v 'schema "public" already exists' | head -10)"
# 🔴 阳性对照: 计数器必须能变大。⛔ 没有这一条, 修完的 errors= 与修之前一样不可信 ——
#    我刚刚就是这样被一个恒 0 的读数骗了很久。
printf 'SELECT 1/0;\n' > "$replay_tmp/probe.sql"
db_file "$replay_tmp/probe.sql" >/dev/null 2>"$replay_tmp/probe.log"
probe_err=$(grep -c 'ERROR:' "$replay_tmp/probe.log" || true)
[ "$probe_err" = "1" ] \
    || fail "3: 阳性对照失败 —— 故意灌一条 SELECT 1/0 之后计数器读到 '$probe_err'(期望 1)。
  ⇒ 这说明错误计数**仍然量不到东西**, 而 errors=0 会继续读起来像「一切正常」。"
ok "基线已灌进空 PG (errors 总 $n_err / 良性 $n_benign / 非预期 0; 阳性对照: 坏语句能被数到)"

# ---- 4. 🔴 主判据: 重放出的五个数 == 文件头记的五个数 ----
# ⚠️ 这里刻意**带 schema 过滤** —— 这个库里任何按名字数的东西都必须带 schema 过滤
#    (prod 上有 7 个 schema; 同源坑咬过一次)。
shape=$(db_sql "
   SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE')
     ||'|'||(SELECT count(*) FROM pg_indexes WHERE schemaname='public')
     ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='u')
     ||'|'||(SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace AND n.nspname='public' WHERE c.contype='f')
     ||'|'||(SELECT count(*) FROM information_schema.columns WHERE table_schema='public')" 2>/dev/null | tr -d '\r')
IFS='|' read -r R_TAB R_IDX R_UQ R_FK R_COL <<< "$shape"
for v in "$R_TAB" "$R_IDX" "$R_UQ" "$R_FK" "$R_COL"; do
    case "$v" in ''|*[!0-9]*) fail "4: 重放后数不出五个数(实得 '$shape') —— **没量到**, ⛔ 与「形状不符」是两回事" ;; esac
done
echo "READING: BASELINE_REPLAY errors_unexpected=$n_unexpected tables=$R_TAB indexes=$R_IDX unique=$R_UQ fk=$R_FK columns=$R_COL"
diffs=""
[ "$R_TAB" = "$H_TAB" ] || diffs="$diffs tables($R_TAB≠$H_TAB)"
[ "$R_IDX" = "$H_IDX" ] || diffs="$diffs indexes($R_IDX≠$H_IDX)"
[ "$R_UQ"  = "$H_UQ"  ] || diffs="$diffs unique($R_UQ≠$H_UQ)"
[ "$R_FK"  = "$H_FK"  ] || diffs="$diffs fk($R_FK≠$H_FK)"
[ "$R_COL" = "$H_COL" ] || diffs="$diffs columns($R_COL≠$H_COL)"
[ -z "$diffs" ] \
    || fail "4: 基线重放出的形状与它**自己头里记的**不同 ——$diffs
  ⇒ 这份文件已经不是它声称的那个东西了(被编辑过? 被截断过?)。
  ⚠️ 注意非预期错误 $n_unexpected 条 —— ⛔「没报错」不是判据: 删掉一条 CREATE INDEX 的基线**照样零错误**(实测)。"
ok "五维与文件头逐位相同 (tables=$R_TAB indexes=$R_IDX unique=$R_UQ fk=$R_FK columns=$R_COL)"

# ---- 5. 边界: 本验证器守得住什么、⛔ 守不住什么 ----
# ⚠️ 这四行**每一行都必须自己是 `READING:`** —— scripts/ci/run-script-tests.sh 只贴
#    `^[[:space:]]*READING: ` 开头的行(第 158 行), 续行会被**静默丢掉**。
#    ⇒ 既然「措辞本身就是那个洞」, 那措辞就必须活到 CI 日志里, ⛔ 不能只活在源码注释里。
echo "READING: VERIFIER_SCOPE=header-matches-replay"
echo "READING: VERIFIER_BLIND_collusive_edit=yes ⇒ ⛔ 守不住「基线与文件头**一起**被改」: 两侧一致即通过(删 17 个索引 + 头里写少 17 ⇒ 本闸完全合格)"
echo "READING: VERIFIER_BLIND_staleness=yes ⇒ ⛔ 守不住基线过期(那要连 prod, 属采集器的判据 ④ / BASELINE_LAG)"
echo "READING: VERIFIER_PROOF_OF_CORRECT=recapture-only ⇒ **只有重新采集能证明基线是对的**; 本闸只证明它没有被**单侧**改坏"
ok "边界已声明(四行读数, ⛔ 不用「自洽」这个词)"

echo "PASS: $pass 项断言全部通过"
