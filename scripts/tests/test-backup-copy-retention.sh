#!/usr/bin/env bash
# GUARDS=scripts/cron/backup-copy-retention.sh,scripts/cron/cron-common.sh,scripts/ci/run-with-test-postgres.sh
#
# backup-copy-retention.sh (30 天保留期) 的真跑测试: 临时目录 + 一次性 PostgreSQL。
#
# 守的行为:
#   1. 只删到期的副本 (文件 / 目录 / 副本 schema / 副本表 / 登记过的临时库);
#      每日备份、说明文件、普通表、普通 schema 一个不碰 (阴性对照)
#   2. --expect 清单与当场计数对不上 ⇒ 一项都不删
#   3. 没有日期、也没登记的副本 schema ⇒ 不删, rc=1
#   4. 受保护的库名即使被登记成临时库也不删
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRIPT="$ROOT_DIR/scripts/cron/backup-copy-retention.sh"
if [ -z "${CRETAS_WORKFLOW_PG_URL:-}" ]; then
    exec bash "$ROOT_DIR/scripts/ci/run-with-test-postgres.sh" bash "$0"
fi

port=$(sed -E 's#.*://[^:/]+:([0-9]+)/.*#\1#' <<<"$CRETAS_WORKFLOW_PG_URL")
user=${CRETAS_WORKFLOW_PG_USER:-cretas_ci}
PSQL="psql -h 127.0.0.1 -p $port -U $user"
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }
q() { $PSQL -X -q -At -v ON_ERROR_STOP=1 -d "$1" -c "$2"; }

tmp=$(mktemp -d)
MAIN=ret_main_$$ SB=ret_sb_$$ CLONE=ret_clone_$$
cleanup() {
    for db in "$MAIN" "$SB" "$CLONE"; do q postgres "DROP DATABASE IF EXISTS $db" >/dev/null 2>&1; done
    rm -rf "$tmp"
}
trap cleanup EXIT

OLD=$(date -d '60 days ago' +%Y%m%d)
NEW=$(date -d '5 days ago' +%Y%m%d)
B=$tmp/backups W=$tmp/www OUT=$tmp/out
mkdir -p "$B" "$W" "$OUT"

old_file() { mkdir -p "$(dirname "$1")"; printf '%s' "${2:-x}" > "$1"; touch -d '60 days ago' "$1"; }
new_file() { mkdir -p "$(dirname "$1")"; printf '%s' "${2:-x}" > "$1"; touch -d '5 days ago' "$1"; }

old_file "$B/cretas_prod_db_pre_fix_20200101.dump" dump-old              # due
new_file "$B/cretas_prod_db_pre_fix_recent.dump" dump-new                # keep
old_file "$B/cretas_prod_db_${OLD}_030001.sql.gz" daily                  # ignored: 每日备份
old_file "$B/backup.log" log                                             # ignored
old_file "$B/tenant_purge_68-还原手册-20200101.md" doc                   # ignored
old_file "$B/tenant_purge_68-逐表行数-20200101.csv" counts               # ignored
old_file "$B/pt01/old.dump" a; old_file "$B/pt01/old2.dump" b            # due (目录)
old_file "$B/task4-x/a.json" a; new_file "$B/task4-x/b.json" b          # keep (目录里有新文件)
old_file "$W/pre-tenant-purge-$OLD/cretas.dump" w                        # due
new_file "$W/f006-backup-$NEW/f.dump" w                                  # keep
old_file "$W/postgresql/cretas_db_${OLD}_030001.sql.gz" p                # ignored
old_file "$W/panel/p.zip" p                                              # ignored
old_file "$B/release-signing.pem" key                                    # unknown-type: 允许名单外的文件不删

for db in "$MAIN" "$SB" "$CLONE"; do q postgres "CREATE DATABASE $db" >/dev/null || fail "createdb $db"; done
q "$MAIN" "
  CREATE SCHEMA tenant_purge_68; CREATE TABLE tenant_purge_68.users AS SELECT g AS id FROM generate_series(1,5) g;
  CREATE SCHEMA bak_trf_${OLD}_1; CREATE TABLE bak_trf_${OLD}_1.t AS SELECT 1 AS id;
  CREATE SCHEMA f006_clean_${NEW}; CREATE TABLE f006_clean_${NEW}.t AS SELECT g AS id FROM generate_series(1,3) g;
  CREATE SCHEMA analytics; CREATE TABLE analytics.t AS SELECT 1 AS id;
  CREATE TABLE public.backup_sku_units_${OLD} AS SELECT g AS id FROM generate_series(1,4) g;
  CREATE TABLE public.backup_sku_units_${NEW} AS SELECT g AS id FROM generate_series(1,2) g;
  CREATE TABLE public.users AS SELECT g AS id FROM generate_series(1,7) g;" >/dev/null || fail "seed main"
q "$SB" "CREATE SCHEMA mock_rest_bak_${OLD}; CREATE TABLE mock_rest_bak_${OLD}.t AS SELECT g AS id FROM generate_series(1,6) g;" >/dev/null \
    || fail "seed smartbi"

run() {
    TEMP_BACKUP_RETENTION_DIR=$OUT TEMP_BACKUP_BACKUPS_ROOT=$B TEMP_BACKUP_WWW_BACKUP_ROOT=$W \
    TEMP_BACKUP_DBS="$MAIN $SB" TEMP_BACKUP_PSQL_CMD="$PSQL" \
    TEMP_BACKUP_TEMP_DATABASES="${TEMP_DBS:-$CLONE=20200101}" \
    CRON_BIN_PATH="$PATH" bash "$SCRIPT" "$@"
}
exists_schema() { q "$1" "SELECT count(*) FROM pg_namespace WHERE nspname = '$2'"; }
exists_table() { q "$1" "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = '$2'"; }
exists_db() { q postgres "SELECT count(*) FROM pg_database WHERE datname = '$1'"; }
# 按名字排序后的最后一个 (文件名以时间戳开头)
latest() { local f=""; for f in "$@"; do :; done; printf '%s' "$f"; }
# 到期项「类别|名字」, 排序后空格连接
due_names() { awk -F'|' '$8 == "due" { print $1 "|" $3 }' "$1" | sort | paste -sd' ' -; }

echo "1) --plan 只列清单, 不删"
out=$(run --plan); rc=$?
[ "$rc" -eq 0 ] || fail "plan rc=$rc: $out"
plan=$(latest "$OUT"/*-plan.txt)
due=$(due_names "$plan")
want="DATABASE|$CLONE FILE|cretas_prod_db_pre_fix_20200101.dump FILE|pre-tenant-purge-$OLD FILE|pt01 SCHEMA|bak_trf_${OLD}_1 SCHEMA|mock_rest_bak_${OLD} SCHEMA|tenant_purge_68 TABLE|backup_sku_units_${OLD}"
[ "$due" = "$want" ] || fail "due set: [$due] want [$want]"
grep -q "tenant_purge_68|20200807\|tenant_purge_68|20260807" "$plan" || fail "tenant_purge_68 没用登记日期"
grep -q "SCHEMA|$MAIN|tenant_purge_68|20260807|1|5|" "$plan" || fail "tenant_purge_68 表数/行数不对: $(grep tenant_purge_68 "$plan")"
! grep -qE "\|(backup.log|analytics|users|postgresql|panel)\||_030001|还原手册|逐表行数" "$plan" || fail "清单里出现了不该管的项"
[ -f "$B/cretas_prod_db_pre_fix_20200101.dump" ] && [ "$(exists_schema "$MAIN" tenant_purge_68)" = 1 ] || fail "--plan 删了东西"
grep -q "FILE|$B|release-signing.pem|?|-|-|0|unknown-type" "$plan" || fail "允许名单外的文件没被标成 unknown-type"
ok "plan 恰好列出 8 个到期项, 每日备份 / 说明文件 / 普通表 / 普通 schema 不在其中"

echo "2) --expect 清单与当场对不上 ⇒ 一项都不删"
q "$MAIN" "INSERT INTO public.backup_sku_units_${OLD} VALUES (99)" >/dev/null
out=$(run --apply --expect "$plan"); rc=$?
[ "$rc" -eq 1 ] || fail "mismatch rc=$rc"
grep -q "对不上: TABLE|$MAIN|backup_sku_units_${OLD}" <<<"$out" || fail "没说哪项对不上: $out"
[ -f "$B/cretas_prod_db_pre_fix_20200101.dump" ] && [ "$(exists_schema "$MAIN" tenant_purge_68)" = 1 ] \
    && [ "$(exists_db "$CLONE")" = 1 ] || fail "对不上时仍删了东西"
ok "对不上 rc=1, 一项都没删"

echo "3) 重新列清单后 --apply --expect: 到期项全删, 其余一个不差"
sleep 1
run --plan >/dev/null || fail "replan"
plan=$(latest "$OUT"/*-plan.txt)
before_keep=$(awk -F'|' '$8!="due"' "$plan")
out=$(run --apply --expect "$plan"); rc=$?
[ "$rc" -eq 0 ] || fail "apply rc=$rc: $out"
tail -1 <<<"$out" | grep -q "APPLIED deleted=8 rc=0" || fail "判决行: $(tail -1 <<<"$out")"
[ ! -e "$B/cretas_prod_db_pre_fix_20200101.dump" ] && [ ! -e "$B/pt01" ] && [ ! -e "$W/pre-tenant-purge-$OLD" ] || fail "到期文件还在"
[ "$(exists_schema "$MAIN" tenant_purge_68)" = 0 ] && [ "$(exists_schema "$MAIN" "bak_trf_${OLD}_1")" = 0 ] \
    && [ "$(exists_schema "$SB" "mock_rest_bak_${OLD}")" = 0 ] && [ "$(exists_table "$MAIN" "backup_sku_units_${OLD}")" = 0 ] \
    && [ "$(exists_db "$CLONE")" = 0 ] || fail "到期库对象还在"
# 阴性对照
for f in "$B/cretas_prod_db_pre_fix_recent.dump" "$B/cretas_prod_db_${OLD}_030001.sql.gz" "$B/backup.log" \
         "$B/tenant_purge_68-还原手册-20200101.md" "$B/tenant_purge_68-逐表行数-20200101.csv" "$B/task4-x/a.json" \
         "$W/f006-backup-$NEW/f.dump" "$W/postgresql/cretas_db_${OLD}_030001.sql.gz" "$W/panel/p.zip"          "$B/release-signing.pem"; do
    [ -f "$f" ] || fail "阴性对照: $f 被删了"
done
[ "$(q "$MAIN" "SELECT count(*) FROM public.users")" = 7 ] && [ "$(q "$MAIN" "SELECT count(*) FROM analytics.t")" = 1 ] \
    && [ "$(q "$MAIN" "SELECT count(*) FROM f006_clean_${NEW}.t")" = 3 ] \
    && [ "$(q "$MAIN" "SELECT count(*) FROM public.backup_sku_units_${NEW}")" = 2 ] || fail "阴性对照: 库里不该动的东西变了"
grep -q "阴性对照: 非删除对象的变化 (应为空)" "$(latest "$OUT"/*-receipt.txt)" || fail "回执缺阴性对照段"
after=$(latest "$OUT"/*-after.txt)
[ "$(awk -F'|' '$8!="due"' "$after")" = "$before_keep" ] || fail "删后的未到期项与删前不一致"
ok "8 项已删; 每日备份、说明、新副本、普通表、普通 schema 一个不差"

echo "4) 没有日期也没登记的副本 schema ⇒ 不删, rc=1"
q "$MAIN" "CREATE SCHEMA backup_nodate; CREATE TABLE backup_nodate.t AS SELECT 1 AS id;" >/dev/null
out=$(run --apply --auto); rc=$?
[ "$rc" -eq 1 ] || fail "undated rc=$rc"
tail -1 <<<"$out" | grep -q "undated=1" || fail "判决行没报 undated: $(tail -1 <<<"$out")"
[ "$(exists_schema "$MAIN" backup_nodate)" = 1 ] || fail "没日期的 schema 被删了"
ok "undated 留着, rc=1"

echo "5) 受保护库名即使登记成临时库也不删"
TEMP_DBS="postgres=20200101" run --apply --auto > "$tmp/o5" 2>&1; rc=$?
[ "$rc" -eq 1 ] || fail "protected rc=$rc"
grep -q "拒绝: 不在临时库登记表 postgres" "$tmp/o5" || fail "没拒绝 postgres: $(cat "$tmp/o5")"
[ "$(exists_db postgres)" = 1 ] || fail "postgres 库被删了"
ok "postgres 被拒绝"

echo "6) --auto 每日模式: 新到期的副本表被删, 先落清单"
q "$MAIN" "CREATE TABLE public.bak_bom_recipes_${OLD} AS SELECT 1 AS id; DROP SCHEMA backup_nodate CASCADE;" >/dev/null
out=$(run --apply --auto); rc=$?
[ "$rc" -eq 0 ] || fail "auto rc=$rc: $out"
[ "$(exists_table "$MAIN" "bak_bom_recipes_${OLD}")" = 0 ] || fail "auto 没删新到期表"
grep -q "TABLE|$MAIN|bak_bom_recipes_${OLD}|$OLD|1|1|" "$(latest "$OUT"/*-plan.txt)" || fail "auto 没先落清单"
ok "auto 删了新到期表, 清单先落盘"

echo "7) public 里有视图依赖副本 schema ⇒ 这一项不删 (不 CASCADE), rc=1, 副本与视图都还在"
q "$MAIN" "CREATE SCHEMA bak_dep_${OLD}; CREATE TABLE bak_dep_${OLD}.t AS SELECT 1 AS id;
           CREATE VIEW public.v_uses_copy AS SELECT id FROM bak_dep_${OLD}.t;" >/dev/null
out=$(run --apply --auto); rc=$?
[ "$rc" -eq 1 ] || fail "dependent rc=$rc: $out"
[ "$(exists_schema "$MAIN" "bak_dep_${OLD}")" = 1 ] && [ "$(q "$MAIN" "SELECT count(*) FROM bak_dep_${OLD}.t")" = 1 ]     && [ "$(q "$MAIN" "SELECT count(*) FROM public.v_uses_copy")" = 1 ] || fail "依赖它的视图或副本被删了"
ok "有外部依赖的副本不删, 视图完好"

echo "PASS backup-copy-retention ($pass checks)"
