#!/usr/bin/env bash
#
# 每天扫一次软删孤儿, 有就写告警。装在 prod 服务器上, 由 crontab 调。
#
# 为什么要定时扫而不是「删之前记得跑」:
#   2026-08-21 实测 —— 我软删 26 个账号前查了「有没有在途任务」, **没查「有没有别的表
#   引用它」**, 结果客户列表整个 500。而这个仓库**没有统一的删数据入口**(每次都是临时
#   SQL), 挂钩无处可挂。
#   ▎靠记得不构成约束力。⇒ 让它自己会响。
#
# SQL 由 `scripts/check-soft-delete-orphans.sh --sql-only` 生成, **在仓库里有一份**
# (scripts/cron/soft-delete-orphans.sql), 装机时随本脚本一起推上来。
# **实体改了要重新生成并重推**, 见下面的出处检查。
#
# 退出码是三态(⛔ 不是两态):
#   0 没孤儿 / 1 有孤儿 / 2 **没量到**(仪器故障, 本次读数作废)
# rc=2 用**与 rc=1 不同的措辞**告警 —— 把「没量到」从「没问题」里拆出来。
#
# ═══════════════════════════════════════════════════════════════════════════
# 🔴 2026-09-06 修: 这个仪器从 2026-08-22 起**每天 rc=2, 连续 15 天一个读数都没有**
# ═══════════════════════════════════════════════════════════════════════════
# 成因不在判据上, 在**载体**上:
#
#   ① cron 的 PATH。本脚本挂在 root 的 user crontab(/var/spool/cron/root), 那里
#      **没有 PATH= 行** ⇒ cronie 用默认的 `/usr/bin:/bin`, 而 `ss` 只在
#      `/usr/sbin/ss`(实测: /usr/bin/ss 与 /bin/ss 都不存在)。
#   ② 而原来那行末尾写着 `2>/dev/null` —— 它把 `ss: command not found` 整句吞掉,
#      于是 PID 为空, 走到 `INSTRUMENT_DEAD no-backend-pid`。
#      **「找不到 ss」和「后端没在监听」被折叠成同一个读数**, 而两者的修法完全不同。
#
#   ▎实测对照(2026-09-06, 把台账/告警改指 /tmp 后在 prod 上跑同一份脚本):
#   ▎  PATH=/usr/bin:/bin            → rc=2 INSTRUMENT_DEAD no-backend-pid
#   ▎  PATH=/usr/sbin:/usr/bin:/bin  → rc=1 ORPHANS_FOUND (真读数, 3262 行孤儿)
#   ▎**只差一个 PATH。后端一直是活的, 孤儿一直在那儿, 没人看见。**
#
# ⇒ 三条对策, 缺一条都还会复发:
#   · PATH 在**脚本里**钉死, ⛔ 不依赖 crontab 里有没有 PATH= 行(那是别人的文件)。
#   · ⛔ 不再吞 stderr。找不到命令要能说出来。
#   · rc=2 的**原因必须可区分**(reason=…), 否则未来接告警通道时无法分派。
set -uo pipefail

# ── 测试注入点 ────────────────────────────────────────────────────────────
# 下面这几个变量的默认值就是生产值; 覆盖它们**只改变「去哪里找东西」**, 不改变任何
# 判定逻辑。存在的理由是硬约束 4: rc=2 那一态平时永远不会自然发生, 不构造就不知道
# 它到不到得了 —— scripts/tests/test-soft-delete-orphans-watch.sh 靠它把三种 rc=2
# 各构造一次。
SQL_FILE="${SDO_SQL_FILE:-/www/wwwroot/cretas/scripts/soft-delete-orphans.sql}"
ALERTS="${SDO_ALERTS:-/www/wwwroot/cretas/logs/soft-delete-orphans.alert}"
LEDGER="${SDO_LEDGER:-/www/wwwroot/cretas/logs/soft-delete-orphans.ledger}"
JAR="${SDO_JAR:-/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar}"
HASDEL="${SDO_HASDEL:-/www/wwwroot/cretas/scripts/soft-delete-orphans.hasdel.txt}"

# 🔴 成因 ① 的对策。`command -v ss` 之所以够用, 正是因为这里已经把 /usr/sbin 钉进来了
# (等价于 `command -v ss || /usr/sbin/ss`, 但只有一处真相)。
SDO_BIN_PATH="${SDO_BIN_PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
export PATH="$SDO_BIN_PATH"
PROC_ROOT="${SDO_PROC_ROOT:-/proc}"

STAMP="$(date '+%F %T')"
WORK="$(mktemp -d)"

# ⚠️ 每次都追加一行台账 —— 这是「必发生」的那个读数(硬约束 9)。
# 验证「没有告警」时要同时看它: 告警文件没涨可能是「没问题」, 也可能是
# **脚本崩在写告警之前**。两者在 [ -s file ] 里长得一模一样。
note() { echo "$STAMP $*" >> "$LEDGER"; }
alert() { echo "$STAMP SOFT-DELETE-ORPHANS $*" >> "$ALERTS"; }

# 「必发生」再加一层: 判决行没写就是崩在半路, 那也必须在台账上留一行。
# ⛔ 「台账没涨」不许长成「今天没问题」。
VERDICT_WRITTEN=0
verdict() { note "$@"; VERDICT_WRITTEN=1; }
on_exit() {
    rm -rf "$WORK"
    [ "$VERDICT_WRITTEN" = 1 ] || \
        echo "$STAMP INSTRUMENT_DEAD reason=aborted-before-verdict" >> "$LEDGER"
}
trap on_exit EXIT

# rc=2 的唯一出口。reason= 是**给未来的告警通道用的分派键** —— 现在没有消费者
# (见 OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER-20260906), 但通道建起来时不该再回头
# 从中文散文里猜「这次是哪种没量到」。
dead() {
    local reason="$1"; shift
    verdict "INSTRUMENT_DEAD reason=$reason"
    alert "仪器故障(reason=$reason) — 本次读数作废, ⛔ 不要读成「没有孤儿」"
    local line
    for line in "$@"; do
        [ -n "$line" ] && printf '        %s\n' "$line" >> "$ALERTS"
    done
    exit 2
}

# ── 1. SQL 在不在 ─────────────────────────────────────────────────────────
[ -s "$SQL_FILE" ] || dead sql-missing "SQL 文件不存在或为空: $SQL_FILE"

# ── 2. 扫描面的出处 ───────────────────────────────────────────────────────
# ⛔ 原来这里比的是 mtime(`[ "$JAR" -nt "$SQL_FILE" ]`)。**每次部署 jar 都会变新**,
#    于是它对「实体改没改」一无所知, 只是在报「最近部署过」—— 32 行台账里 16 行
#    是它, 全部无信息量。天天喊的闸最后会被人无视(形态 E), 那时它的覆盖率归零。
# ⇒ 换成**出处比对**: 生成器往 SQL 里盖 `-- generated-from <commit> <UTC>`,
#   这里拿它跟运行中制品的构建 commit 比。
#
# ⚠️ 实测(2026-09-06): 现在这个 jar 里**既没有 git.properties 也没有
#    build-info.properties** ⇒ 拿不到 jar 的 commit ⇒ 走 provenance-unknown。
#    那是 **INFO 不是 STALE** —— 「比不了」和「比过了不一致」是两回事,
#    ⛔ 不许把「没量到」染成「有问题」(反过来更不许, 见硬约束 4)。
jar_build_commit() {
    command -v unzip >/dev/null 2>&1 || return 0
    [ -f "$JAR" ] || return 0
    local p
    for p in BOOT-INF/classes/git.properties META-INF/build-info.properties; do
        unzip -p "$JAR" "$p" 2>/dev/null \
            | grep -oE '(commit\.id(\.abbrev)?|build\.commit)=[0-9a-f]{7,40}' \
            | head -1 | cut -d= -f2
    done | grep -m1 . || return 0
}

PROV_LINE=$(grep -m1 '^-- generated-from ' "$SQL_FILE" || true)
if [ -z "$PROV_LINE" ]; then
    # 没盖章 = 说不清出处(手改过? 老版本生成器?)。是 INFO, 不是「已知过期」。
    note "INFO provenance-missing"
else
    SQL_COMMIT=$(printf '%s\n' "$PROV_LINE" | awk '{print $3}')
    SQL_UTC=$(printf '%s\n' "$PROV_LINE" | awk '{print $4" "$5}')
    JAR_COMMIT=$(jar_build_commit)
    if [ -z "$JAR_COMMIT" ]; then
        note "INFO provenance-unknown sql-commit=$SQL_COMMIT sql-utc=$SQL_UTC"
    elif [ "$JAR_COMMIT" != "$SQL_COMMIT" ]; then
        note "STALE sql-commit=$SQL_COMMIT jar-commit=$JAR_COMMIT"
        alert "扫描面可能过期 — SQL 出自 $SQL_COMMIT, 运行中制品是 $JAR_COMMIT:"
        printf '        %s\n' "本地跑 ./scripts/check-soft-delete-orphans.sh --write 重新生成并重装" >> "$ALERTS"
        # ⛔ 不 exit —— 过期的扫描仍然比不扫强, 继续跑, 只是标记出来。
    else
        note "INFO provenance-match commit=$SQL_COMMIT"
    fi
fi

# ── 3. 找后端进程 ─────────────────────────────────────────────────────────
# 🔴 端口是服务的定义, 关键词不是(形态 A¹²: `pgrep -f` 会抓到探针自己)。
SS_BIN=$(command -v ss || true)
[ -n "$SS_BIN" ] || dead ss-not-found \
    "PATH=$PATH 下找不到 ss。cron 默认 PATH 只有 /usr/bin:/bin, 而 ss 在 /usr/sbin。" \
    "本脚本已自己钉 PATH; 还报这个说明这台机器上真的没装 iproute2。"

# ⛔ 不再 2>/dev/null: 吞掉的正是「为什么取不到 PID」。
LISTEN=$("$SS_BIN" -lntp 2> "$WORK/ss.err")
SS_RC=$?
PID=$(printf '%s\n' "$LISTEN" | grep -E ':(10010|10020)' | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -z "$PID" ]; then
    dead no-listener-on-10010-10020 \
        "ss rc=$SS_RC; 后端没在 10010/10020 上监听(或 ss 看不到 pid= —— 非 root?)" \
        "$(head -3 "$WORK/ss.err" | tr '\n' ' ')"
fi

# ── 4. 问活进程要 DB 凭证 ─────────────────────────────────────────────────
# ⛔ 不要用配置里的默认值: prod 是 cretas_prod_db, 而 properties 默认写的是
#    ${DB_NAME:cretas_db} —— 本仓已经因此误报过「迁移失败」。
ENVIRON="$PROC_ROOT/$PID/environ"
[ -r "$ENVIRON" ] || dead environ-unreadable "读不了 $ENVIRON (pid=$PID)"

# ⛔ 不用 `eval $(... | sed s/^/export /)`:
#    ① eval 整份 environ 会冲掉 PATH(形态 A¹²);
#    ② 密码里有 `/`、引号这类字符时 eval 会解释它们 —— 本仓已经栽过一次
#       (「DSN 里的 / 让每个手拼连接串都连不上」)。这里改成纯文本取值, 不过 shell。
ENV_LINES=$(tr '\0' '\n' < "$ENVIRON" | grep -E '^(DB_|POSTGRES_)[A-Za-z0-9_]*=' || true)
envget() { printf '%s\n' "$ENV_LINES" | grep -m1 -E "^$1=" | cut -d= -f2- ; }

DB_NAME_V=$(envget DB_NAME)
DB_USER_V=$(envget DB_USERNAME)
DB_PW=$(envget DB_PASSWORD)
PG_PW=$(envget POSTGRES_PASSWORD)

# 密码: DB_PASSWORD 优先, POSTGRES_PASSWORD 兜底。**记下用的是哪一个**(形态 A¹³) ——
# 两个变量在 prod 上是**不同的值**, 实测只有 DB_PASSWORD 能连上 cretas_user;
# psql 失败时不带这个标记就分不清是「密码错」还是「拿错了变量」。
PW=""; PW_VAR=""
if [ -n "$DB_PW" ]; then PW="$DB_PW"; PW_VAR=DB_PASSWORD
elif [ -n "$PG_PW" ]; then PW="$PG_PW"; PW_VAR=POSTGRES_PASSWORD
fi

# 用户名: 进程上**确实没有** DB_USERNAME(实测), 而应用自己就是靠
# application-pg-prod.properties 里的 `${DB_USERNAME:cretas_user}` 跑起来的。
# ⇒ 沿用**同一个**默认值不是「凭空编一个读数」: 用户名错只会让 psql 认证失败
#   (变成 psql-failed), **造不出一个假的「0 条孤儿」**。所以它可以有默认值,
#   但必须**在台账上标出来源**, ⛔ 不许是静默的。
USER_SRC=DB_USERNAME
if [ -z "$DB_USER_V" ]; then DB_USER_V=cretas_user; USER_SRC=app-default; fi

# 库名和密码则**不许兜底**(形态 A¹⁰): `-d ""` 会连到「与用户同名的那个库」——
# 一个合法但错误的库, 很可能干净地返回 (0 rows), 也就是一个**假的「没有孤儿」**。
MISSING=""
[ -n "$DB_NAME_V" ] || MISSING="$MISSING DB_NAME"
[ -n "$PW_VAR" ]    || MISSING="$MISSING DB_PASSWORD|POSTGRES_PASSWORD"
[ -z "$MISSING" ] || dead db-credentials-incomplete \
    "活进程 pid=$PID 的 environ 里缺:$MISSING" \
    "已读到的键: $(printf '%s\n' "$ENV_LINES" | cut -d= -f1 | tr '\n' ' ')"

CTX="db=$DB_NAME_V user=$DB_USER_V(src=$USER_SRC) pw=$PW_VAR"
PSQL() { PGPASSWORD="$PW" psql -h localhost -U "$DB_USER_V" -d "$DB_NAME_V" "$@"; }

# ── 5. 快照对账: 扫描面是不是还覆盖着 prod 的真实形状 ─────────────────────
# 🔴 出处戳只能证明「SQL 出自哪个 commit」, **证明不了扫描面还对**。
#    hasdel 快照(哪些表有 deleted_at)是生成器唯一的数据库输入; prod 上给某张表加了
#    deleted_at 之后, 快照就旧了 —— 而离线重放用的是同一份旧快照, 于是**重放结果与
#    仓库份逐字相同, CI 全绿**, 而真实集合已经不同了。
#    ▎两份都错成一样, 比对就变成了恒真式(形态 B′)。
# ⇒ 只有在**线上**、对着真库, 才能发现这件事。这里就是那个唯一的观测点。
#
# ⚠️ 口径必须与抓取时**逐字相同**: 这里执行的 SQL 是从快照头部 `-- capture-sql `
#    读出来的那一条, ⛔ 不在本文件里另写一条「意思一样」的。
#    (另写一条的后果是**第一次线上跑就 rc=2** —— 一个假故障。)
if [ ! -s "$HASDEL" ]; then
    dead hasdel-snapshot-missing "快照不在: $HASDEL (它随本脚本一起装, 见 server-script-inventory.conf)"
fi
CAPTURE_SQL=$(grep -m1 '^-- capture-sql ' "$HASDEL" | sed 's/^-- capture-sql //')
if [ -z "$CAPTURE_SQL" ]; then
    dead hasdel-snapshot-missing "快照头部没有 '-- capture-sql ' 行, 无法确定抓取口径" \
        "⛔ 不猜一条 —— 猜错的口径会把「快照是好的」报成 stale"
fi
# 防呆: 只允许一条 SELECT。快照是 root 装上来的, 但从数据文件里读出来的东西拿去
# 执行, 形状该被约束住。
case "$CAPTURE_SQL" in
    SELECT*) ;;
    *) dead hasdel-snapshot-missing "capture-sql 不是 SELECT 开头, 拒绝执行: $CAPTURE_SQL" ;;
esac
if printf '%s' "$CAPTURE_SQL" | grep -q ';'; then
    dead hasdel-snapshot-missing "capture-sql 里有分号(多语句), 拒绝执行"
fi

# 快照体与实测各自归一化后比对。⚠️ 排序口径要和生成器写快照时一致(LC_ALL=C sort -u)。
grep -vE '^--|^[[:space:]]*$' "$HASDEL" | tr -d '\r' | LC_ALL=C sort -u > "$WORK/snap.txt"
LIVE_RAW=$(PSQL -At -c "$CAPTURE_SQL" 2>"$WORK/hasdel.err")
LIVE_RC=$?
if [ "$LIVE_RC" -ne 0 ]; then
    dead "psql-failed" "rc=$LIVE_RC $CTX (对账查询失败)" \
        "$(head -3 "$WORK/hasdel.err" | tr '\n' ' ')"
fi
printf '%s\n' "$LIVE_RAW" | grep -vE '^[[:space:]]*$' | tr -d '\r' | LC_ALL=C sort -u > "$WORK/live.txt"

SNAP_N=$(grep -c . "$WORK/snap.txt"); LIVE_N=$(grep -c . "$WORK/live.txt")
# 阳性对照: 实测个位数 = 查询本身坏了, ⛔ 不许把它读成「prod 少了 380 张表」。
[ "$LIVE_N" -ge 50 ] || dead hasdel-snapshot-stale \
    "实测只有 $LIVE_N 张带 deleted_at 的表(预期数百张) —— 是查询坏了, 不是 prod 变了" \
    "$CTX"

ONLY_LIVE=$(LC_ALL=C comm -13 "$WORK/snap.txt" "$WORK/live.txt" | tr '\n' ' ')
ONLY_SNAP=$(LC_ALL=C comm -23 "$WORK/snap.txt" "$WORK/live.txt" | tr '\n' ' ')
if [ -n "${ONLY_LIVE// /}" ] || [ -n "${ONLY_SNAP// /}" ]; then
    # 差集**打进台账行**, 不只写告警 —— 台账是那个「必发生」的读数。
    note "HASDEL_DRIFT snap=$SNAP_N live=$LIVE_N only-live=[${ONLY_LIVE% }] only-snap=[${ONLY_SNAP% }]"
    dead hasdel-snapshot-stale \
        "扫描面已过期: 快照 $SNAP_N 张 / 实测 $LIVE_N 张" \
        "prod 有而快照没有(新加了 deleted_at, 扫不到它的孤儿): ${ONLY_LIVE:-无}" \
        "快照有而 prod 没有(表已删/列已去, SQL 可能报错): ${ONLY_SNAP:-无}" \
        "⇒ 本地跑 ./scripts/check-soft-delete-orphans.sh --write 重新抓, 提 PR, 再装。"
fi
note "INFO hasdel-in-sync tables=$SNAP_N"

# ── 6. 执行 ───────────────────────────────────────────────────────────────
OUT=$(PSQL -f "$SQL_FILE" 2>&1)
RC=$?
if [ "$RC" -ne 0 ]; then
    dead "psql-failed" "rc=$RC $CTX" "$(printf '%s\n' "$OUT" | head -5 | tr '\n' ' ')"
fi

if printf '%s\n' "$OUT" | grep -qE '^\(0 rows\)'; then
    verdict "OK no-orphans $CTX"
    exit 0
fi

verdict "ORPHANS_FOUND $CTX"
{
    echo "$STAMP SOFT-DELETE-ORPHANS 发现孤儿 — 下面每一条现在都可能让对应页面 500:"
    printf '%s\n' "$OUT" | sed -n '3,$p' | grep -v '^(' | sed 's/^/        /'
    echo "        修法看情况, ⛔ 不要一刀切:"
    echo "          · 子表指向父表 → 多半是软删父行时没级联, 应补级联"
    echo "          · 审计字段     → 加 @NotFound(IGNORE)"
    echo "          · 测试残留     → 清数据即可"
} >> "$ALERTS"
exit 1
