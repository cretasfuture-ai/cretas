#!/usr/bin/env bash
# scripts/cron/soft-delete-orphans-watch.sh 的测试。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这个东西需要测试(而它此前一个测试都没有)
# ═══════════════════════════════════════════════════════════════════════════
# 它从 2026-08-22 到 09-06 **连续 15 天 rc=2**, 每天写一行
# `INSTRUMENT_DEAD no-backend-pid`, 而后端一直是活的、孤儿一直在那儿(3262 行)。
# 成因是 cron 的 PATH 里没有 /usr/sbin ⇒ `ss: command not found` ⇒ 被 `2>/dev/null`
# 吞掉 ⇒ PID 为空 ⇒ 落进「后端没在监听」那个分支。
#
# ▎**「找不到 ss」和「后端没在监听」被折叠成了同一个读数。**
#
# ⇒ 这个测试的核心不是「跑通了没有」, 是**把每一种 rc=2 各构造一次**(硬约束 4:
#   三态的价值全在第三态, 而第三态平时永远不会自然发生 —— 不构造就不知道它到不到得了)。
#
# ⚠️ 每条断言之前先把台账那一行**打出来**(形态 C″): 变异不红有两种相反的成因,
#    「断言在守空气」和「变异没打进去」, 不看实际读数就分不清。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
WATCH="$ROOT_DIR/scripts/cron/soft-delete-orphans-watch.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { PASSED=$((PASSED + 1)); echo "  ok  $*"; }

[ -s "$WATCH" ] || fail "$WATCH 不存在"
bash -n "$WATCH" || fail "语法错误: $WATCH"

# ── 造一个自足的 bin 目录 ─────────────────────────────────────────────────
# 被测脚本自己钉 PATH(那正是修复的一部分), 所以想构造「找不到 ss」**只能**把
# SDO_BIN_PATH 指到一个我们完全控制的目录 —— PATH 里加不回来的东西才是真的没有。
FAKEBIN="$TMP_ROOT/bin"
mkdir -p "$FAKEBIN"
# ⚠️ `cat` 是**桩自己**要用的(heredoc), 不是被测脚本要用的。第一版漏了它, 于是 ss 桩
#    以 `cat: command not found` / rc=127 死掉, 输出为空 ⇒ 被测脚本落进
#    `no-listener-on-10010-10020` —— **一个坏掉的仪器长成了一个合理的读数**。
#    这正是被测脚本原来那个 bug 的同款形状(形态 A: 错的是尺子, 不是被测的东西)。
#    ⇒ 下面 make_ss 里加了自检, 让桩坏掉时当场喊出来。
for tool in date mktemp rm grep awk sed cut tr head cat sort comm; do
    abs=$(command -v "$tool") || fail "本机缺 $tool, 无法构造测试环境"
    printf '#!/bin/sh\nexec %s "$@"\n' "$abs" > "$FAKEBIN/$tool"
    chmod +x "$FAKEBIN/$tool"
done
# 🔴 同一个坑栽了两次: 第一次漏 `cat`(ss 桩用它), 第二次漏 `sort`/`comm`
#    (hasdel 对账用它们)。两次的症状都不是「报错」, 而是**一个看起来合理的错误结论**
#    ——「端口上没人」和「快照过期」。⇒ 逐个证明它们在受限 PATH 下真的能跑,
#    ⛔ 不靠「我列全了吧」。
for tool in date mktemp rm grep awk sed cut tr head cat sort comm; do
    PATH="$FAKEBIN" command -v "$tool" >/dev/null 2>&1 \
        || fail "受限 PATH 下找不到 $tool —— 被测脚本会拿它做判断, 缺了会得出假结论"
done
# 阳性对照: 这个 bin 目录必须真的**不含** ss —— 否则「ss-not-found」那条永远构造不出来,
# 而它长得跟「测试通过」一模一样。
[ -x "$FAKEBIN/ss" ] && fail "FAKEBIN 里不该有 ss"
PATH="$FAKEBIN" command -v ss >/dev/null 2>&1 && fail "SDO_BIN_PATH 下仍能找到 ss, 构造失败"

make_ss() {  # $1 = 输出内容
    printf '#!/bin/sh\ncat <<'"'"'SSEOF'"'"'\n%s\nSSEOF\n' "$1" > "$FAKEBIN/ss"
    chmod +x "$FAKEBIN/ss"
    # 🔴 桩的自检: 在被测脚本用它之前, 先证明它在**那个受限 PATH 下**真的能跑。
    #    桩死掉时输出为空, 而空输出与「端口上没人」在被测脚本眼里一模一样 ——
    #    不自检就会拿一个坏仪器的读数去下结论。
    local out rc
    out=$(PATH="$FAKEBIN" "$FAKEBIN/ss" -lntp 2>&1); rc=$?
    [ "$rc" = 0 ] || fail "ss 桩在受限 PATH 下跑不起来(rc=$rc): $out"
    [ -n "$out" ] || fail "ss 桩输出为空 —— 它会被误读成「端口上没人」"
}
# psql 桩要回答**两个**查询, ⛔ 不能一个输出应付到底:
#   · `-At -c <capture-sql>`  → hasdel 对账, 回 $TMP_ROOT/hasdel_live.txt
#   · `-f <scan.sql>`         → 孤儿扫描, 回调用方给的那份
# 混成一个的话, 对账那一步会拿到孤儿结果表格当表名, 于是**每个用例都 stale** ——
# 那看起来会像「新加的闸到处误报」, 而不是「我的桩写错了」(形态 A: 错的是尺子)。
make_psql() {  # $1 = 退出码, $2 = stdout
    {
        printf '#!/bin/sh\n'
        printf 'printf "%%s\\n" "PGPASSWORD=$PGPASSWORD" > "%s"\n' "$TMP_ROOT/psql.env"
        printf 'printf "%%s\\n" "$*" >> "%s"\n' "$TMP_ROOT/psql.env"
        printf 'case "$*" in\n  *-At*) cat "%s"; exit 0 ;;\nesac\n' "$TMP_ROOT/hasdel_live.txt"
        printf 'cat <<'"'"'PQEOF'"'"'\n%s\nPQEOF\n' "$2"
        printf 'exit %s\n' "$1"
    } > "$FAKEBIN/psql"
    chmod +x "$FAKEBIN/psql"
}

# 快照(装在服务器上那份)与「真库实测」两侧。默认两侧一致 ⇒ 对账通过。
CAPTURE_SQL="SELECT table_name FROM information_schema.columns WHERE table_schema = current_schema() AND column_name = 'deleted_at'"
make_hasdel() {  # $1 = 快照表名(换行分隔), $2 = 实测表名(不给就同 $1)
    {
        printf -- '-- 这是生成物。\n'
        printf -- '-- captured-at 2026-09-06 00:00:00Z\n'
        printf -- '-- capture-sql %s\n' "$CAPTURE_SQL"
        printf '%s\n' "$1"
    } > "$TMP_ROOT/hasdel.snap"
    printf '%s\n' "${2:-$1}" > "$TMP_ROOT/hasdel_live.txt"
}
# 阳性对照要求实测 >= 50 张, 所以造一份够长的名单。
gen_tables() {  # $1 = 张数
    local i=1
    while [ "$i" -le "$1" ]; do printf 't_%03d\n' "$i"; i=$((i + 1)); done
}
make_environ() {  # 参数 = KEY=VAL...; 写成 NUL 分隔, 和真 /proc/<pid>/environ 一样
    mkdir -p "$TMP_ROOT/proc/4242"
    : > "$TMP_ROOT/proc/4242/environ"
    local kv
    for kv in "$@"; do printf '%s\0' "$kv" >> "$TMP_ROOT/proc/4242/environ"; done
}

SQL="$TMP_ROOT/scan.sql"
LEDGER="$TMP_ROOT/ledger"
ALERTS="$TMP_ROOT/alert"
JAR="$TMP_ROOT/app.jar"

run_watch() {
    : > "$LEDGER"; : > "$ALERTS"; rm -f "$TMP_ROOT/psql.env"
    SDO_BIN_PATH="$FAKEBIN" \
    SDO_PROC_ROOT="$TMP_ROOT/proc" \
    SDO_SQL_FILE="$SQL" SDO_LEDGER="$LEDGER" SDO_ALERTS="$ALERTS" SDO_JAR="$JAR" \
    SDO_HASDEL="$TMP_ROOT/hasdel.snap" \
        bash "$WATCH" >/dev/null 2>&1
    RC=$?
    # 形态 C″ / 硬约束 9: 先把实际读数打出来, 再断言。
    echo "    ledger: $(tr '\n' ' ' < "$LEDGER")"
    echo "    rc=$RC"
}

assert_rc()     { [ "$RC" = "$1" ] || fail "$2: 期望 rc=$1, 实得 rc=$RC"; }
assert_ledger() { grep -Fq "$1" "$LEDGER" || fail "$2: 台账里没有 [$1]"; }
assert_no_ledger() { grep -Fq "$1" "$LEDGER" && fail "$2: 台账里不该有 [$1]"; return 0; }

# 一份**带出处戳**的 SQL, 与生成器产出的形状一致。
printf -- '-- generated-from deadbeefcafe 2026-09-06 00:00:00Z\nSELECT 1;\n' > "$SQL"
: > "$JAR"
GOOD_SS='State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process
LISTEN 0      100          *:10010            *:*    users:(("java",pid=4242,fd=30))'
GOOD_ENV=(DB_NAME=cretas_prod_db DB_PASSWORD=realpw POSTGRES_PASSWORD=otherpw)
BASE_TABLES=$(gen_tables 60)
make_hasdel "$BASE_TABLES"

# ═════════ 1. rc=2 三态: ss-not-found ═════════════════════════════════════
# ⛔ 这就是 15 天空转的那一条。修之前它长成 `no-backend-pid`(= 后端挂了),
#    于是每天的告警都指着一个**没有发生**的故障。
echo "[1] ss-not-found (PATH 里没有 ss)"
rm -f "$FAKEBIN/ss"
make_psql 0 '(0 rows)'
make_environ "${GOOD_ENV[@]}"
run_watch
assert_rc 2 "ss-not-found"
assert_ledger "INSTRUMENT_DEAD reason=ss-not-found" "ss-not-found"
assert_no_ledger "no-backend-pid" "ss-not-found 不许再冒充「后端没在监听」"
grep -q "仪器故障(reason=ss-not-found)" "$ALERTS" || fail "告警没带 reason"
ok "reason=ss-not-found, 且与 no-listener 可区分"

# ═════════ 2. rc=2: no-listener-on-10010-10020 ════════════════════════════
# 阴性对照 —— 证明上面那条不是「只要出错就报 ss-not-found」。
echo "[2] no-listener-on-10010-10020 (ss 在, 但端口上没人)"
make_ss 'LISTEN 0 100 *:9999 *:* users:(("nginx",pid=7,fd=3))'
run_watch
assert_rc 2 "no-listener"
assert_ledger "INSTRUMENT_DEAD reason=no-listener-on-10010-10020" "no-listener"
ok "reason=no-listener-on-10010-10020"

# ═════════ 3. rc=2: environ-unreadable ════════════════════════════════════
echo "[3] environ-unreadable (拿到 pid 但读不了 environ)"
make_ss "$GOOD_SS"
rm -rf "$TMP_ROOT/proc"
run_watch
assert_rc 2 "environ-unreadable"
assert_ledger "INSTRUMENT_DEAD reason=environ-unreadable" "environ-unreadable"
ok "reason=environ-unreadable"

# ═════════ 4. rc=2: db-credentials-incomplete (缺密码) ════════════════════
echo "[4] db-credentials-incomplete — 两个密码变量都没有"
make_environ DB_NAME=cretas_prod_db DB_USERNAME=cretas_user
run_watch
assert_rc 2 "缺密码"
assert_ledger "INSTRUMENT_DEAD reason=db-credentials-incomplete" "缺密码"
grep -q "DB_PASSWORD|POSTGRES_PASSWORD" "$ALERTS" || fail "告警没说清缺的是哪个"
ok "缺密码 → db-credentials-incomplete"

# ═════════ 5. rc=2: db-credentials-incomplete (缺库名) ════════════════════
# 🔴 这一条守的是形态 A¹⁰: 原来写的是 `-d "${DB_NAME:-}"`, 而空库名会让 psql 连到
#    「与用户同名的那个库」—— 一个合法但错误的库, 很可能干净地返回 (0 rows),
#    也就是一个**假的「没有孤儿」**。⛔ 「我不知道」不许被翻译成「是 0」。
echo "[5] db-credentials-incomplete — 缺 DB_NAME(假 0 的入口)"
make_environ DB_PASSWORD=realpw
run_watch
assert_rc 2 "缺库名"
assert_ledger "INSTRUMENT_DEAD reason=db-credentials-incomplete" "缺库名"
grep -q "DB_NAME" "$ALERTS" || fail "告警没说清缺 DB_NAME"
assert_no_ledger "OK no-orphans" "缺库名绝不许落进「没有孤儿」"
ok "缺库名 → rc=2, ⛔ 不是 rc=0"

# ═════════ 6. rc=2: psql-failed ═══════════════════════════════════════════
echo "[6] psql-failed (凭证齐了, 查询本身失败)"
make_environ "${GOOD_ENV[@]}"
make_psql 2 'psql: error: FATAL: database does not exist'
run_watch
assert_rc 2 "psql-failed"
assert_ledger "INSTRUMENT_DEAD reason=psql-failed" "psql-failed"
ok "reason=psql-failed"

# ═════════ 7. rc=0 与凭证来源标记 ═════════════════════════════════════════
# 形态 A¹³: 每条读数带来源标记。prod 上 DB_PASSWORD 与 POSTGRES_PASSWORD 是**两个
# 不同的值**(实测只有前者能连上), 不记 pw= 就分不清「密码错」和「拿错了变量」。
echo "[7] rc=0 no-orphans + 来源标记"
make_psql 0 ' assoc | bad
-------+-----
(0 rows)'
run_watch
assert_rc 0 "no-orphans"
assert_ledger "OK no-orphans db=cretas_prod_db user=cretas_user(src=app-default) pw=DB_PASSWORD" "来源标记"
grep -Fq "PGPASSWORD=realpw" "$TMP_ROOT/psql.env" || fail "用错了密码变量(应优先 DB_PASSWORD)"
[ ! -s "$ALERTS" ] || fail "没孤儿不该写告警"
ok "rc=0, 台账带 db/user(src)/pw 三个来源标记, 且真的用了 DB_PASSWORD"

# ═════════ 8. 来源标记的变异对照 ══════════════════════════════════════════
echo "[8] 来源标记随环境变 (变异对照: 换环境, 标记必须跟着换)"
make_environ DB_NAME=other_db DB_USERNAME=someone_else POSTGRES_PASSWORD=onlypg
run_watch
assert_rc 0 "标记变异"
assert_ledger "db=other_db user=someone_else(src=DB_USERNAME) pw=POSTGRES_PASSWORD" "标记变异"
grep -Fq "PGPASSWORD=onlypg" "$TMP_ROOT/psql.env" || fail "没回退到 POSTGRES_PASSWORD"
ok "三个标记全部随环境改变 —— 它们不是写死的"

# ═════════ 9. rc=1 有孤儿 ═════════════════════════════════════════════════
echo "[9] rc=1 orphans-found"
make_environ "${GOOD_ENV[@]}"
make_psql 0 '                 assoc                  | bad
----------------------------------------+------
 process_sheet_rows.plan_id -> production_plans | 56
(1 row)'
run_watch
assert_rc 1 "orphans"
assert_ledger "ORPHANS_FOUND" "orphans"
grep -Fq "process_sheet_rows.plan_id" "$ALERTS" || fail "告警里没列出孤儿行"
ok "rc=1 且孤儿明细进了告警"

# ═════════ 10. 出处检查取代 mtime ═════════════════════════════════════════
# 🔴 旧判据是 `[ "$JAR" -nt "$SQL_FILE" ]` —— **每次部署 jar 都变新**, 于是它
#    32 行台账里占了 16 行, 全部无信息量(形态 E: 天天喊的闸最后会被无视)。
#    这里是那条的**回归闸**: jar 比 SQL 新, 但 STALE 不许再出现。
echo "[10] jar 比 SQL 新 ⇒ ⛔ 不再报 STALE(旧 mtime 判据的回归闸)"
make_psql 0 '(0 rows)'
sleep 1
touch "$JAR"          # jar 明确比 SQL 新
[ "$JAR" -nt "$SQL" ] || fail "构造失败: jar 并不比 SQL 新, 这条对照无意义"
echo "    (构造已生效: jar -nt sql 为真)"
run_watch
assert_rc 0 "provenance"
assert_no_ledger "STALE" "mtime 不再驱动 STALE"
assert_ledger "INFO provenance-unknown sql-commit=deadbeefcafe" "provenance-unknown"
ok "只报 INFO provenance-unknown, ⛔ 不报 STALE"

# ═════════ 11. 没盖出处戳 ⇒ provenance-missing(INFO, 不是 STALE) ══════════
echo "[11] SQL 没有 -- generated-from ⇒ provenance-missing"
printf 'SELECT 1;\n' > "$SQL"
run_watch
assert_rc 0 "provenance-missing"
assert_ledger "INFO provenance-missing" "provenance-missing"
assert_no_ledger "STALE" "「说不清出处」不是「已知过期」"
ok "provenance-missing 是 INFO"

# ═════════ 11b. hasdel 快照对账 ═══════════════════════════════════════════
# 🔴 这一条守的是**离线闸看不见的那半边**。
#    CI 里的漂移闸拿快照离线重放, 再跟仓库份比 —— 快照旧了的时候, **两边用的是
#    同一份旧快照**, 于是逐字相同、全绿, 而 prod 的真实集合已经不一样了。
#    ▎两份都错成一样, 比对就是恒真式(形态 B′)。
#    只有在线上对着真库才量得到。⇒ 强制构造一次。
echo "[11b] prod 新加了一张带 deleted_at 的表 ⇒ hasdel-snapshot-stale"
printf -- '-- generated-from deadbeefcafe 2026-09-06 00:00:00Z\nSELECT 1;\n' > "$SQL"
make_psql 0 '(0 rows)'
make_environ "${GOOD_ENV[@]}"
# 实测比快照多一张 —— 这正是「新表加了 deleted_at, 而扫描面还不知道」。
NEW_TABLE_LIST=$(printf '%s\nbrand_new_soft_deleted_table\n' "$BASE_TABLES")
make_hasdel "$BASE_TABLES" "$NEW_TABLE_LIST"
echo "    构造已生效: 快照 $(printf '%s\n' "$BASE_TABLES" | grep -c .) 张 / 实测 $(grep -c . "$TMP_ROOT/hasdel_live.txt") 张"
run_watch
assert_rc 2 "hasdel-stale"
assert_ledger "INSTRUMENT_DEAD reason=hasdel-snapshot-stale" "hasdel-stale"
assert_ledger "only-live=[brand_new_soft_deleted_table]" "差集要打进台账"
assert_no_ledger "OK no-orphans" "扫描面过期时不许落进「没有孤儿」"
grep -Fq "brand_new_soft_deleted_table" "$ALERTS" || fail "告警里没列出多出来的表"
ok "多一张表 ⇒ rc=2 + 差集进台账"

# 反向: 快照有而 prod 没有(表被删了 / 列被去掉)—— SQL 会在那张表上报错。
echo "[11c] 快照比 prod 多一张 ⇒ 同样 stale, 但差集在另一边"
GONE_LIST=$(printf '%s\ntable_that_was_dropped\n' "$BASE_TABLES")
make_hasdel "$GONE_LIST" "$BASE_TABLES"
run_watch
assert_rc 2 "hasdel-stale 反向"
assert_ledger "only-snap=[table_that_was_dropped]" "反向差集"
ok "少一张表 ⇒ 差集落在 only-snap"

# 阴性对照: 两侧一致时**不许**报 stale —— 否则这道闸第一次线上跑就恒红。
echo "[11d] 阴性对照: 两侧一致 ⇒ 不报 stale"
make_hasdel "$BASE_TABLES"
run_watch
assert_rc 0 "两侧一致"
assert_no_ledger "hasdel-snapshot-stale" "一致时不许报 stale"
assert_ledger "INFO hasdel-in-sync tables=60" "一致时要留一行「量过了」的读数"
ok "一致 ⇒ rc=0 且台账有 hasdel-in-sync(⛔ 不是「没消息就是好消息」)"

# 口径必须来自快照头部, ⛔ 不许脚本自己另写一条。
echo "[11e] 快照头部没有 capture-sql ⇒ 拒绝猜, 报 missing"
grep -v '^-- capture-sql ' "$TMP_ROOT/hasdel.snap" > "$TMP_ROOT/h2" && mv "$TMP_ROOT/h2" "$TMP_ROOT/hasdel.snap"
echo "    构造已生效: capture-sql 行数 = $(grep -c '^-- capture-sql ' "$TMP_ROOT/hasdel.snap")"
run_watch
assert_rc 2 "缺 capture-sql"
assert_ledger "INSTRUMENT_DEAD reason=hasdel-snapshot-missing" "缺 capture-sql"
ok "缺口径 ⇒ rc=2, ⛔ 不自己编一条"
make_hasdel "$BASE_TABLES"

# ═════════ 12. sql-missing ════════════════════════════════════════════════
echo "[12] sql-missing"
: > "$SQL"
run_watch
assert_rc 2 "sql-missing"
assert_ledger "INSTRUMENT_DEAD reason=sql-missing" "sql-missing"
ok "reason=sql-missing"

# ═════════ 13. 「必发生」的台账行 ═════════════════════════════════════════
# 硬约束 9: 验证「没有告警」时必须同时有一个「一定会发生」的读数。这里断言的是
# **上面每一次运行台账都涨了** —— 12 个用例里没有一次是「静默什么都没写」。
echo "[13] 每一条路径都写了判决行"
printf -- '-- generated-from deadbeefcafe 2026-09-06 00:00:00Z\nSELECT 1;\n' > "$SQL"
make_psql 0 '(0 rows)'
run_watch
n=$(grep -cE 'INSTRUMENT_DEAD|OK no-orphans|ORPHANS_FOUND' "$LEDGER")
[ "$n" = "1" ] || fail "一次运行应恰好写 1 行判决, 实得 $n"
grep -Fq "aborted-before-verdict" "$WATCH" || fail "崩在判决之前的兜底行不见了"
ok "恰好 1 行判决 + 保留了 aborted-before-verdict 兜底"

echo
echo "PASS: test-soft-delete-orphans-watch.sh ($PASSED 项)"
