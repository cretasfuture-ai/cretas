#!/usr/bin/env bash
#
# 临时备份 / 删前副本的 30 天保留期 (Steve 2026-10-26 拍板 Q6 = A; 手册
# docs/security/factory-data-export-and-erasure-runbook.md §8)。装在 47 上, 由 root crontab 每天调。
#
# 为什么要有它: 每次修数据前留的整库 dump、删前备份 schema、副本表都**没有过期机制**——
# 2026-10-26 实测, 7 月的 8 个 dump、08-07 的 tenant_purge_68 (34.7 万行) 还都在。
# 「彻底删除」承诺写的是「备份按保留期自然过期」, 对这些副本原本不成立。
#
# ── 管什么 (只管这些, 其余一概不碰) ──────────────────────────────────────
#   FILE      /www/wwwroot/cretas/backups 顶层的每个文件 / 目录 (目录按其中最新文件的时间算)
#             /www/backup 顶层名字形如 pre-*-YYYYMMDD / prod-clean-YYYYMMDD / <x>-backup-YYYYMMDD 的目录
#             ⛔ 不管: 每日备份 *_YYYYMMDD_HHMMSS.sql.gz (backup-db.sh 自己保留 7 天)、backup.log、
#                *.md、*逐表行数*.csv (只有说明和计数, Steve 选择保留)
#   SCHEMA    两个生产库里名字像副本的 schema; 日期取名字里的 YYYYMMDD, 没有日期的查下面的登记表,
#             登记表里也没有 ⇒ 记 undated, ⛔ 不删, rc=1 让人来补登记
#   TABLE     两个生产库 public 里的 backup_* / repair_backup_* / bak_* / mock_rest_bak_* 且名字带日期
#   DATABASE  只删登记表里点名的临时库
#
# ── 用法 ───────────────────────────────────────────────────────────────
#   --plan                       只列清单 (每项: 类别/位置/名字/日期/表数/行数/字节/是否到期), 不删
#   --apply --expect <清单文件>  只删清单里标 due 的项; 当场重算, 任何一项对不上(没了/行数或字节变了)
#                                ⇒ 一项都不删, rc=1。清单外新到期的项只报告不删。
#   --apply --auto               每日定时: 先把清单落盘, 再删全部到期项
#   删完重算一次: 删掉的必须都不在了; 没到期的每一项 (表数/行数/字节) 必须一个不差 (阴性对照)。
#
# ── 退出码 ─────────────────────────────────────────────────────────────
#   0 正常 / 1 有问题 (对不上、阴性对照不过、有没登记日期的副本) / 2 没量到 (缺命令、库连不上)
#
# 输出: $TEMP_BACKUP_RETENTION_DIR/<时间>-{plan,receipt}.txt, 最后一行恰好一条判决行。
set -uo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/cron-common.sh"
cron_pin_path

RETENTION_DAYS=${RETENTION_DAYS:-30}
OUT_DIR=${TEMP_BACKUP_RETENTION_DIR:-/www/wwwroot/cretas/logs/backup-copy-retention}
BACKUPS_ROOT=${TEMP_BACKUP_BACKUPS_ROOT:-/www/wwwroot/cretas/backups}
WWW_BACKUP_ROOT=${TEMP_BACKUP_WWW_BACKUP_ROOT:-/www/backup}
DBS=${TEMP_BACKUP_DBS:-"cretas_prod_db smartbi_prod_db"}
# 测试里换成直连临时库的 psql
PSQL_CMD=${TEMP_BACKUP_PSQL_CMD:-"sudo -u postgres psql"}
NOW_EPOCH=${TEMP_BACKUP_NOW_EPOCH:-$(date +%s)}
ALERTS=${TEMP_BACKUP_ALERTS:-$OUT_DIR/alerts.log}
PREFIX="TEMP-BACKUP-RETENTION"

# 没有日期后缀的副本 schema 的生成日期 (flyway installed_on 实测), 名字 -> YYYYMMDD
SCHEMA_DATES="tenant_purge_68=20260807 f006_clear_71=20260807 legacy_retired=20260809"
# 允许删除的临时整库, 名字 -> 生成日期 (base/<oid>/PG_VERSION mtime 实测)
TEMP_DATABASES=${TEMP_BACKUP_TEMP_DATABASES:-"cretas_f006_clone_verify=20260715"}
# 这些库名无论如何都不删
PROTECTED_DATABASES="postgres template0 template1 cretas_prod_db smartbi_prod_db cretas_db smartbi_db mall_center"

# 名字只允许 [a-z0-9_] —— 它们会被拼进 DROP 语句
COPY_SCHEMA_RE='^(tenant_purge_[0-9]+|f006_clear_[0-9]+|f006_clean_[0-9]{8}|legacy_retired|backup_[a-z0-9_]+|bak_[a-z0-9_]+|[a-z0-9_]+_bak_[0-9]{8})$'
COPY_TABLE_RE='^(backup_|repair_backup_|bak_|mock_rest_bak_)[a-z0-9_]*[0-9]{8}[a-z0-9_]*$'
DAILY_BACKUP_RE='^(cretas_prod_db|smartbi_prod_db|cretas_db_test)_[0-9]{8}_[0-9]{6}\.sql\.gz$'
WWW_BACKUP_DIR_RE='^(pre-[a-z0-9-]+|prod-clean|[a-z0-9]+-backup)-[0-9]{8}$'

mode="" expect="" auto=0
while [ $# -gt 0 ]; do
    case "$1" in
        --plan) mode=plan ;;
        --apply) mode=apply ;;
        --expect) expect=${2:-}; shift ;;
        --auto) auto=1 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done
[ -n "$mode" ] || { echo "usage: $0 --plan | --apply --expect FILE | --apply --auto" >&2; exit 2; }
if [ "$mode" = apply ] && [ "$auto" = 0 ] && [ -z "$expect" ]; then
    echo "--apply 需要 --expect <清单> 或 --auto" >&2; exit 2
fi
if [ -n "$expect" ] && [ ! -f "$expect" ]; then echo "清单不存在: $expect" >&2; exit 2; fi

mkdir -p "$OUT_DIR" || exit 2
cron_require_cmds "$ALERTS" "$PREFIX" date find du stat awk sort ${PSQL_CMD%% *}
cd / || exit 2

CUTOFF_EPOCH=$((NOW_EPOCH - RETENTION_DAYS * 86400))
CUTOFF_DAY=$(date -d "@$CUTOFF_EPOCH" +%Y%m%d)
STAMP=$(date -d "@$NOW_EPOCH" +%Y%m%dT%H%M%S)

psql_at() {   # $1 db, $2 sql
    $PSQL_CMD -X -q -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"
}

registry_date() {  # $1 name, $2 registry
    local pair
    for pair in $2; do
        [ "${pair%%=*}" = "$1" ] && { echo "${pair#*=}"; return 0; }
    done
    return 1
}

due_flag() {  # $1 YYYYMMDD or '?'
    if [ "$1" = "?" ]; then echo undated
    elif [ "$1" -lt "$CUTOFF_DAY" ]; then echo due
    else echo keep
    fi
}

# 一行: KIND|LOCATION|NAME|DATE|TABLES|ROWS|BYTES|FLAG
plan_files() {
    local root=$1 kind_re=$2 entry name epoch day bytes
    [ -d "$root" ] || return 0
    for entry in "$root"/* "$root"/.[!.]*; do
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        name=${entry##*/}
        if [ "$kind_re" = backups ]; then
            [ "$name" = backup.log ] && continue
            [[ "$name" =~ $DAILY_BACKUP_RE ]] && continue
            [[ "$name" == *.md ]] && continue
            [[ "$name" == *逐表行数*.csv ]] && continue
        else
            [[ "$name" =~ $WWW_BACKUP_DIR_RE ]] || continue
        fi
        if [ -L "$entry" ]; then
            echo "FILE|$root|$name|?|-|-|0|undated"
            continue
        fi
        if [ -d "$entry" ]; then
            # 目录按其中最新文件的时间算; find 读不全 ⇒ undated, ⛔ 不能把读不到的新文件当成没有
            if ! times=$(find "$entry" -type f -printf '%T@\n'); then
                echo "FILE|$root|$name|?|-|-|0|undated"
                continue
            fi
            epoch=$(awk 'BEGIN { m = 0 } { if ($1 + 0 > m) m = $1 + 0 } END { printf "%d", m }' <<<"$times")
            [ "$epoch" -gt 0 ] || epoch=$(stat -c %Y "$entry")
        else
            epoch=$(stat -c %Y "$entry")
        fi
        day=$(date -d "@$epoch" +%Y%m%d)
        bytes=$(du -sb "$entry") || bytes=0
        bytes=${bytes%%[[:space:]]*}
        echo "FILE|$root|$name|$day|-|-|$bytes|$(due_flag "$day")"
    done
}

plan_db() {
    local db=$1 out name day
    out=$(psql_at "$db" "
        SELECT 'S', n.nspname,
               (SELECT count(*) FROM pg_tables t WHERE t.schemaname = n.nspname),
               COALESCE((SELECT sum((xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', t.schemaname, t.tablename), false, true, '')))[1]::text::bigint)
                           FROM pg_tables t WHERE t.schemaname = n.nspname), 0),
               COALESCE((SELECT sum(pg_total_relation_size(c.oid)) FROM pg_class c WHERE c.relnamespace = n.oid AND c.relkind IN ('r','m','t')), 0)
          FROM pg_namespace n
         WHERE n.nspname ~ '$COPY_SCHEMA_RE'
        UNION ALL
        SELECT 'T', t.tablename, 1,
               (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM public.%I', t.tablename), false, true, '')))[1]::text::bigint,
               pg_total_relation_size(format('public.%I', t.tablename)::regclass)
          FROM pg_tables t WHERE t.schemaname = 'public' AND t.tablename ~ '$COPY_TABLE_RE'
         ORDER BY 1, 2") || return 1
    out=${out//$'\r'/}   # Windows 上的 psql (测试机) 行尾带 \r; 不去掉, 字节列带着它, 阴性对照误报
    while IFS='|' read -r k name tables rows bytes; do
        [ -n "$k" ] || continue
        day="?"
        [[ "$name" =~ ([0-9]{8}) ]] && day=${BASH_REMATCH[1]}
        if [ "$k" = S ]; then
            [[ "$name" =~ $COPY_SCHEMA_RE ]] || continue
            [ "$day" != "?" ] || day=$(registry_date "$name" "$SCHEMA_DATES") || day="?"
            echo "SCHEMA|$db|$name|$day|$tables|$rows|$bytes|$(due_flag "$day")"
        else
            [[ "$name" =~ $COPY_TABLE_RE ]] || continue
            echo "TABLE|$db|$name|$day|$tables|$rows|$bytes|$(due_flag "$day")"
        fi
    done <<<"$out"
}

plan_databases() {
    local pair name day bytes
    for pair in $TEMP_DATABASES; do
        name=${pair%%=*}; day=${pair#*=}
        bytes=$(psql_at postgres "SELECT pg_database_size(datname) FROM pg_database WHERE datname = '$name'") || return 1
        [ -n "$bytes" ] || continue
        echo "DATABASE|-|$name|$day|-|-|$bytes|$(due_flag "$day")"
    done
}

make_plan() {
    local db
    plan_files "$BACKUPS_ROOT" backups
    plan_files "$WWW_BACKUP_ROOT" www
    for db in $DBS; do plan_db "$db" || return 1; done
    plan_databases || return 1
}

key_of() { cut -d'|' -f1-3; }

verdict() {  # $1 rc, $2 text
    echo "$(date '+%F %T') $2 rc=$1"
    exit "$1"
}

plan_file="$OUT_DIR/$STAMP-plan.txt"
if ! make_plan > "$plan_file"; then
    cron_dead "$ALERTS" "$PREFIX" "plan-failed" "计算清单失败 (库连不上?)"
fi
undated=$(awk -F'|' '$8 == "undated" { n++ } END { print n + 0 }' "$plan_file")
due_count=$(awk -F'|' '$8 == "due" { n++ } END { print n + 0 }' "$plan_file")
echo "PLAN $plan_file cutoff=$CUTOFF_DAY due=$due_count undated=$undated"
cat "$plan_file"

if [ "$mode" = plan ]; then
    [ "$undated" -eq 0 ] || verdict 1 "PLAN_ONLY due=$due_count undated=$undated (补登记 SCHEMA_DATES)"
    verdict 0 "PLAN_ONLY due=$due_count"
fi

# ── apply ────────────────────────────────────────────────────────────────
if [ "$auto" = 1 ]; then
    approved=$(awk -F'|' '$8=="due"' "$plan_file")
else
    approved=$(awk -F'|' '$8=="due"' "$expect")
    mismatch=""
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        key=$(key_of <<<"$line")
        now=$(awk -F'|' -v k="$key" '($1"|"$2"|"$3)==k' "$plan_file")
        if [ -z "$now" ]; then mismatch="$mismatch\n  不在了: $key"
        elif [ "$(cut -d'|' -f5-8 <<<"$now")" != "$(cut -d'|' -f5-8 <<<"$line")" ]; then
            mismatch="$mismatch\n  对不上: $key 清单=$(cut -d'|' -f5-8 <<<"$line") 当场=$(cut -d'|' -f5-8 <<<"$now")"
        fi
    done <<<"$approved"
    if [ -n "$mismatch" ]; then
        printf 'MISMATCH (一项都没删):%b\n' "$mismatch"
        verdict 1 "MISMATCH nothing-deleted"
    fi
    extra=$(awk -F'|' 'NR == FNR { if (NF) a[$1 "|" $2 "|" $3] = 1; next }
                       $8 == "due" && !(($1 "|" $2 "|" $3) in a) { print $1 "|" $2 "|" $3 }' \
                <(printf '%s\n' "$approved") "$plan_file")
    [ -z "$extra" ] || printf '清单外新到期 (未批准, 不删):\n%s\n' "$extra"
fi

failures=0
while IFS='|' read -r kind loc name day tables rows bytes flag; do
    [ -n "$kind" ] || continue
    case "$kind" in
        FILE)
            case "$loc" in "$BACKUPS_ROOT"|"$WWW_BACKUP_ROOT") ;; *) echo "拒绝: 位置不在白名单 $loc"; failures=$((failures+1)); continue ;; esac
            case "$name" in ""|.|..|*/*) echo "拒绝: 名字不合法 $name"; failures=$((failures+1)); continue ;; esac
            if [ -L "$loc/$name" ]; then echo "拒绝: 符号链接 $loc/$name"; failures=$((failures+1)); continue; fi
            rm -rf -- "${loc:?}/${name:?}" || failures=$((failures+1)) ;;
        SCHEMA)
            [[ "$name" =~ $COPY_SCHEMA_RE ]] || { echo "拒绝: 不像副本 schema $name"; failures=$((failures+1)); continue; }
            psql_at "$loc" "DROP SCHEMA \"$name\" CASCADE" >/dev/null || failures=$((failures+1)) ;;
        TABLE)
            [[ "$name" =~ $COPY_TABLE_RE ]] || { echo "拒绝: 不像副本表 $name"; failures=$((failures+1)); continue; }
            psql_at "$loc" "DROP TABLE public.\"$name\"" >/dev/null || failures=$((failures+1)) ;;
        DATABASE)
            if [[ " $PROTECTED_DATABASES " == *" $name "* ]] || ! registry_date "$name" "$TEMP_DATABASES" >/dev/null; then
                echo "拒绝: 不在临时库登记表 $name"; failures=$((failures+1)); continue
            fi
            psql_at postgres "DROP DATABASE \"$name\"" >/dev/null || failures=$((failures+1)) ;;
    esac
    echo "DELETED $kind|$loc|$name tables=$tables rows=$rows bytes=$bytes"
done <<<"$approved"

after_file="$OUT_DIR/$STAMP-after.txt"
if ! make_plan > "$after_file"; then
    cron_dead "$ALERTS" "$PREFIX" "after-plan-failed" "删后重算清单失败"
fi
approved_keys="$OUT_DIR/$STAMP-approved-keys.txt"
awk -F'|' 'NF { print $1 "|" $2 "|" $3 }' <<<"$approved" > "$approved_keys"
still=$(awk -F'|' 'NR == FNR { d[$0] = 1; next } (($1 "|" $2 "|" $3) in d) { print $1 "|" $2 "|" $3 }' \
            "$approved_keys" "$after_file")
# 阴性对照: 删前清单里每个「不是本次删除对象」的项, 删后表数/行数/字节一个不差
changed=$(awk -F'|' '
    FILENAME == ARGV[1] { del[$0] = 1; next }
    FILENAME == ARGV[2] { after[$1 "|" $2 "|" $3] = $5 "|" $6 "|" $7; next }
    {
        k = $1 "|" $2 "|" $3
        if (k in del) next
        if (!(k in after)) print "  消失: " k
        else if (after[k] != $5 "|" $6 "|" $7) print "  变了: " k " " $5 "|" $6 "|" $7 " -> " after[k]
    }' "$approved_keys" "$after_file" "$plan_file")
{
    echo "RECEIPT $STAMP cutoff=$CUTOFF_DAY"
    echo "-- 删前 (本次删除对象)"; echo "$approved"
    echo "-- 删后仍在 (应为空)"; echo "${still:-（无）}"
    echo "-- 阴性对照: 非删除对象的变化 (应为空)"; echo "${changed:-（无）}"
} > "$OUT_DIR/$STAMP-receipt.txt"
cat "$OUT_DIR/$STAMP-receipt.txt"

deleted=$(grep -c . <<<"$approved" || true)
[ "$failures" -eq 0 ] && [ -z "$still" ] && [ -z "$changed" ] \
    || verdict 1 "APPLY_FAILED deleted=$deleted failures=$failures still=$(grep -c . <<<"$still") changed=$(grep -c . <<<"$changed")"
[ "$undated" -eq 0 ] || verdict 1 "APPLIED deleted=$deleted undated=$undated (补登记 SCHEMA_DATES)"
verdict 0 "APPLIED deleted=$deleted"
