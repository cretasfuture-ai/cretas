#!/usr/bin/env bash
# GUARDS=scripts/testing/fresh-db-startup-outcome.sh,scripts/testing/fresh-db-gate.sh,.github/workflows/fresh-db-progress.yml
#
# `fresh-db-gate.sh --progress` 的**启动结局分类**闸 —— 用合成 backend 日志逐态喂分类器,
# ⛔ 不跑 Docker / Maven。
#
# 立它的成因(2026-10-01 ~ 10-03, hosted ubuntu-latest, 四趟):
#   READING: FRESH_DB_SHAPE migrations_above_baseline=14 tables=506 indexes=2067
#   READING: FRESH_DB_FAILURE file=<none> sqlstate=<none> process_died=0
#   ERROR: 既没有失败点读数、也没有健康 —— 这趟没跑到 Flyway
# 迁移已经跑完、进程还活着、Spring 在 300s 截断时还在初始化 bean —— 是**启动超时**,
# 却被报成「没跑到 Flyway」, 与真失败同一个 rc=1。重跑同一 commit 就绿。
#
# 被守的行为(每一条都**真跑**分类函数, ⛔ 不只 grep 措辞):
#   ① 五个结局各自命中: HEALTHY / FLYWAY_FAILED / DIED_BEFORE_HEALTHY /
#      TIMEOUT_AFTER_FLYWAY / TIMEOUT_BEFORE_FLYWAY
#   ② 退出码三态: 0 / 1(真失败) / 2(没量到); 认不出的结局 ⇒ 2, ⛔ 不落进 0
#   ③ 超时的措辞说「启动超时」并带出迁移条数, ⛔ 不再说「没跑到 Flyway」
#   ④ 失败点优先于健康(自相矛盾的读数 ⛔ 不放绿)
#   ⑤ 在 `set -euo pipefail` 下, 日志里没有命中 / 日志不存在都不会把调用方炸掉
#      (本仓栽过: grep 无命中 rc=1 + pipefail ⇒ 三态检查永远走不到)
#   ⑥ 接线(形态 B): 闸真的 source 并调用了这些函数; unmeasured 真的 exit 2;
#      工作流把 rc=2 与 rc=1 分开报
#   ⑦ 编译提前: 主代码编译在 spring-boot:run 与预算计时之前单独一步, 两处都跳过测试编译
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
LIB="$ROOT_DIR/scripts/testing/fresh-db-startup-outcome.sh"
GATE="$ROOT_DIR/scripts/testing/fresh-db-gate.sh"
WF="$ROOT_DIR/.github/workflows/fresh-db-progress.yml"
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }

[ -s "$LIB" ] || fail "找不到 $LIB"
bash -n "$LIB" || fail "$LIB 语法错误"
bash -n "$GATE" || fail "$GATE 语法错误"
# shellcheck source=../testing/fresh-db-startup-outcome.sh
. "$LIB"

FIX=$(mktemp -d)
trap 'rm -rf -- "$FIX"' EXIT

# ── 合成日志(行形取自真实 Spring Boot / Flyway 输出) ─────────────────────────
MVN_ONLY='[INFO] Scanning for projects...
[INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) @ cretas-api ---
[INFO] Compiling 3127 source files with javac [debug release 21] to target/classes'
SPRING_START='2026-10-03 10:01:52.101  INFO [main] c.cretas.aims.CretasBackendApplication - Starting CretasBackendApplication using Java 21.0.8 with PID 4242'
FLYWAY_APPLIED='2026-10-03 10:02:40.512  INFO [main] o.f.core.internal.command.DbMigrate - Successfully applied 13 migrations to schema "public", now at version v20261103.05 (execution time 00:04.118s)'
FLYWAY_UPTODATE='2026-10-03 10:02:40.512  INFO [main] o.f.core.internal.command.DbMigrate - Schema "public" is up to date. No migration necessary.'
BEAN_INIT='2026-10-03 10:05:53.755  INFO [main] c.cretas.aims.service.skill.impl.SkillRegistryImpl - Skill Registry initialized with 39 skills
2026-10-03 10:05:57.861  INFO [main] c.c.a.s.i.s.IndicatorComputationStrategyRegistry - IndicatorComputationStrategyRegistry: registered 11 strategies'
STARTED='2026-10-03 10:06:30.002  INFO [main] c.cretas.aims.CretasBackendApplication - Started CretasBackendApplication in 278.4 seconds (process running for 281.0)'
FLYWAY_FAIL='2026-10-03 10:02:39.001 ERROR [main] o.s.boot.SpringApplication - Application run failed
org.flywaydb.core.internal.command.DbMigrate$FlywayMigrateException: Migration V20261103_05__add_probe_column.sql failed
-------------------------------------------------------------
SQL State  : 42P01
Error Code : 0
Message    : ERROR: relation "no_such_table" does not exist'
BEAN_CRASH='2026-10-03 10:04:11.000 ERROR [main] o.s.boot.SpringApplication - Application run failed
org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name '"'"'skillRegistryImpl'"'"'

***************************
APPLICATION FAILED TO START
***************************'

mk() { local name="$1"; shift; printf '%s\n' "$@" > "$FIX/$name.log"; }
mk healthy          "$MVN_ONLY" "$SPRING_START" "$FLYWAY_APPLIED" "$BEAN_INIT" "$STARTED"
mk flyway_failed    "$MVN_ONLY" "$SPRING_START" "$FLYWAY_FAIL"
mk died             "$MVN_ONLY" "$SPRING_START" "$FLYWAY_APPLIED" "$BEAN_CRASH"
mk timeout_after    "$MVN_ONLY" "$SPRING_START" "$FLYWAY_APPLIED" "$BEAN_INIT"
mk timeout_uptodate "$MVN_ONLY" "$SPRING_START" "$FLYWAY_UPTODATE" "$BEAN_INIT"
mk timeout_before   "$MVN_ONLY"
: > "$FIX/empty.log"

# ── ① + ② 逐态真跑 ───────────────────────────────────────────────────────
# 每一行: fixture healthy died ⇒ 期望结局 期望 rc
expect() {
    local fx="$1" healthy="$2" died="$3" want_state="$4" want_rc="$5" got got_rc
    got=$(classify_startup_outcome "$FIX/$fx.log" "$healthy" "$died")
    got_rc=$(startup_outcome_rc "$got")
    [ "$got" = "$want_state" ] || fail "①: $fx (healthy=$healthy died=$died) 期望 $want_state, 实得 [$got]"
    [ "$got_rc" = "$want_rc" ] || fail "②: $got 期望 rc=$want_rc, 实得 rc=$got_rc"
    echo "READING: CASE fixture=$fx healthy=$healthy died=$died state=$got rc=$got_rc"
}
expect healthy          1 0 HEALTHY               0
expect flyway_failed    0 1 FLYWAY_FAILED         1
expect died             0 1 DIED_BEFORE_HEALTHY   1
expect timeout_after    0 0 TIMEOUT_AFTER_FLYWAY  2
expect timeout_uptodate 0 0 TIMEOUT_AFTER_FLYWAY  2
expect timeout_before   0 0 TIMEOUT_BEFORE_FLYWAY 2
# 迁移失败但进程还没退(日志刚写出失败点就到了截断时刻)⇒ 仍是迁移失败, ⛔ 不是超时
expect flyway_failed    0 0 FLYWAY_FAILED         1
# ④ 失败点 + 健康 = 自相矛盾的读数 ⇒ ⛔ 不放绿
expect flyway_failed    1 0 FLYWAY_FAILED         1
# 日志为空 / 不存在 ⇒ 只能是「没看到 Flyway 完成」, rc=2
expect empty            0 0 TIMEOUT_BEFORE_FLYWAY 2
got=$(classify_startup_outcome "$FIX/does-not-exist.log" 0 0)
[ "$got" = TIMEOUT_BEFORE_FLYWAY ] || fail "①: 日志文件不存在时期望 TIMEOUT_BEFORE_FLYWAY, 实得 [$got]"
ok "五个结局逐态命中, 退出码 0/1/2 各就各位"

# ② 开放集合: 认不出的结局名 ⛔ 不落进 0
for bogus in '' HEALTHY_ish TIMEOUT unknown; do
    r=$(startup_outcome_rc "$bogus")
    [ "$r" = 2 ] || fail "②: 认不出的结局 [$bogus] 期望 rc=2, 实得 [$r] —— 开放集合里的新值会静默落进绿"
done
ok "认不出的结局一律 rc=2(⛔ 不枚举, 不落进绿)"

# ── 失败点读数 ───────────────────────────────────────────────────────────
[ "$(fresh_db_failure_file "$FIX/flyway_failed.log")" = "V20261103_05__add_probe_column.sql" ] \
    || fail "失败点文件名读错: [$(fresh_db_failure_file "$FIX/flyway_failed.log")]"
[ "$(fresh_db_failure_sqlstate "$FIX/flyway_failed.log")" = "42P01" ] \
    || fail "SQLSTATE 读错: [$(fresh_db_failure_sqlstate "$FIX/flyway_failed.log")]"
[ -z "$(fresh_db_failure_file "$FIX/timeout_after.log")" ] || fail "没有失败点的日志读出了失败点"
ok "失败点文件名 / SQLSTATE 读得出, 没有时为空"

# ── ③ 措辞: 超时说超时, 带出迁移条数; ⛔ 不再说「没跑到 Flyway」 ───────────────
msg=$(startup_outcome_message TIMEOUT_AFTER_FLYWAY 14 306 300 "" "")
echo "READING: TIMEOUT_AFTER_MSG=$msg"
case "$msg" in *启动超时*) ;; *) fail "③: 超时措辞里没有「启动超时」: $msg" ;; esac
case "$msg" in *"迁移已执行 14 个"*) ;; *) fail "③: 超时措辞没带出迁移条数: $msg" ;; esac
case "$msg" in *进程仍在启动*) ;; *) fail "③: 超时措辞没说进程还活着: $msg" ;; esac
case "$msg" in *300s*) ;; *) fail "③: 超时措辞没带出预算: $msg" ;; esac
msg_b=$(startup_outcome_message TIMEOUT_BEFORE_FLYWAY "" 600 600 "" "")
case "$msg_b" in *启动超时*) ;; *) fail "③: Flyway 前超时的措辞里没有「启动超时」: $msg_b" ;; esac
# 🔴 反向: 那句把成因说反的话, 在五个结局的措辞里**一个都不许出现**
for st in HEALTHY FLYWAY_FAILED DIED_BEFORE_HEALTHY TIMEOUT_AFTER_FLYWAY TIMEOUT_BEFORE_FLYWAY; do
    m=$(startup_outcome_message "$st" 14 306 300 V1__x.sql 42P01)
    case "$m" in *没跑到*) fail "③: $st 的措辞里又出现了「没跑到」: $m" ;; esac
done
# 闸本身也不许在**可执行行**里把那句话留着(防有人在调用方另写一支)。
# ⚠️ 只看非注释行: 闸的注释里刻意记着这句话的来历, 那不是行为。
grep -vE '^[[:space:]]*#' "$GATE" | grep -q '没跑到' \
    && fail "③: 闸的可执行行里还有「没跑到」—— 那正是被这道闸替换掉的误报"
# 失败与超时的措辞必须可区分
m_fail=$(startup_outcome_message FLYWAY_FAILED 14 120 600 V20261103_05__add_probe_column.sql 42P01)
case "$m_fail" in *V20261103_05__add_probe_column.sql*42P01*) ;; *) fail "③: 迁移失败的措辞没点名文件 + SQLSTATE: $m_fail" ;; esac
case "$m_fail" in *启动超时*) fail "③: 迁移失败被说成了超时: $m_fail" ;; esac
ok "超时说「启动超时 + 迁移已执行 N 个 + 进程仍在启动」; 失败点名文件; 「没跑到」不再出现"

# ── ⑤ set -euo pipefail 下无命中不炸调用方 ───────────────────────────────────
# ⚠️ 上面的用例跑在 `set -uo pipefail`(没有 -e)下; 闸是 `set -euo pipefail` ——
#    grep 无命中 rc=1 在那里会**当场中止**, 而这里看不出来。⇒ 在子 shell 里按闸的模式再跑一遍。
strict=$(bash -c 'set -euo pipefail; . "$1"
    a=$(fresh_db_failure_file "$2"); b=$(fresh_db_failure_sqlstate "$2")
    c=$(classify_startup_outcome "$2" 0 0); d=$(classify_startup_outcome "$3" 0 0)
    echo "survived file=[$a] state=[$b] c=$c d=$d"' _ "$LIB" "$FIX/timeout_before.log" "$FIX/does-not-exist.log" 2>&1)
case "$strict" in
    "survived file=[] state=[] c=TIMEOUT_BEFORE_FLYWAY d=TIMEOUT_BEFORE_FLYWAY") ;;
    *) fail "⑤: 在 set -euo pipefail 下无命中把调用方炸掉了(或读数不对): [$strict]" ;;
esac
ok "set -euo pipefail 下, 日志无命中 / 不存在都不会中止调用方"

# ── ⑥ 接线(形态 B: 机制在, 接上没有) ──────────────────────────────────────
grep -qE '^\. "\$SCRIPT_DIR/fresh-db-startup-outcome\.sh"' "$GATE" || fail "⑥: 闸没有 source 分类库"
grep -qF 'startup_state=$(classify_startup_outcome "$BACKEND_LOG" "$healthy" "$died")' "$GATE" \
    || fail "⑥: 闸没有用 backend 日志 + healthy + died 调分类器"
grep -qF 'READING: FRESH_DB_STARTUP state=$startup_state' "$GATE" || fail "⑥: 闸没有打 FRESH_DB_STARTUP 读数"
# 闸的超时判定用墙钟, ⛔ 不是循环次数
grep -qF 'boot_deadline=$((SECONDS + STARTUP_TIMEOUT))' "$GATE" || fail "⑥: 启动预算不是墙钟秒"
# ⑦ 编译提前: 编译单独一步, 在启动预算开始计时**之前**; 两处都跳过测试编译。
#    ⚠️ 守的是**顺序**: 编译挪进预算之后(或 spring-boot:run 又去编 1693 个测试源文件),
#    #868 那趟 298s 里 203s 是编译的局面就回来了, 而预算读数看不出来。
ln_of() { grep -nF -- "$1" "$GATE" | grep -v '^[0-9]*:[[:space:]]*#' | head -1 | cut -d: -f1; }
l_compile=$(ln_of 'run_maven -B compile -Dmaven.test.skip=true')
l_compile_reading=$(ln_of 'READING: FRESH_DB_COMPILE rc=$compile_rc')
l_run=$(ln_of 'run_maven -B spring-boot:run')
l_t0=$(ln_of 'boot_t0=$SECONDS')
for v in l_compile l_compile_reading l_run l_t0; do
    [ -n "${!v}" ] || fail "⑦: 闸里找不到 $v 对应的那一行 —— 编译提前这一步不在了?"
done
[ "$l_compile" -lt "$l_run" ] && [ "$l_run" -lt "$l_t0" ] \
    || fail "⑦: 顺序不对 (compile=$l_compile spring-boot:run=$l_run boot_t0=$l_t0) —— 编译必须在启动、在预算计时之前"
sed -n "$l_run,$((l_run + 3))p" "$GATE" | grep -qF -- '-Dmaven.test.skip=true' \
    || fail "⑦: spring-boot:run 没带 -Dmaven.test.skip=true —— 它的 fork 会在预算里重新编测试源文件"
# 编译失败是 rc=1 的真失败, 且措辞说「编译」, ⛔ 不像启动超时
sed -n "$l_compile_reading,$((l_compile_reading + 4))p" "$GATE" | grep -qE 'fail "compile: .*编译失败' \
    || fail "⑦: 编译失败没有以「编译失败」当场红"
echo "READING: PRECOMPILE_ORDER compile=$l_compile spring_boot_run=$l_run budget_t0=$l_t0"
ok "编译在启动与预算计时之前单独一步, 两处都跳过测试编译, 编译失败当场红"

# unmeasured 真的 exit 2(行为, ⛔ 不是 grep 「exit 2」字样)
um=$(awk '/^unmeasured\(\) \{/{c=1} c{print} c&&/^}$/{exit}' "$GATE")
[ -n "$um" ] || fail "⑥: 闸里找不到 unmeasured()"
bash -c "$um"'
unmeasured probe' >/dev/null 2>&1
um_rc=$?
[ "$um_rc" = 2 ] || fail "⑥: unmeasured 的退出码是 $um_rc, 期望 2"
# 闸里对非 HEALTHY 结局的分派: rc=1 走 fail, 其余走 unmeasured
grep -qF '[ "$startup_rc" = "1" ] && fail "progress: $startup_msg"' "$GATE" || fail "⑥: rc=1 的结局没有走 fail"
grep -qF 'unmeasured "progress: $startup_msg"' "$GATE" || fail "⑥: rc=2 的结局没有走 unmeasured"
# TIMEOUT_AFTER_FLYWAY 判完形状后必须以 rc=2 收尾, ⛔ 不报绿
grep -qF '[ "$startup_state" = "HEALTHY" ] || unmeasured "progress: 形状无倒退, 但 $startup_msg"' "$GATE" \
    || fail "⑥: 超时那趟判完形状之后没有以 rc=2 收尾 —— 慢启动会被报成绿"
echo "READING: UNMEASURED_RC=$um_rc"
ok "闸 source 并调用分类器; unmeasured 真 exit 2; 超时不会落进绿"

# 工作流: 执行真实 progress run block, 证明 pipefail、rc 传播和输出目的地都有效。
[ -s "$WF" ] || fail "⑥: 找不到 $WF"
PROGRESS_RUN="$FIX/progress-run.sh"
awk '
    /FRESH_DB_PROGRESS_RUN_BEGIN/ { inside=1; next }
    /FRESH_DB_PROGRESS_RUN_END/ { inside=0 }
    inside { sub(/^          /, ""); print }
' "$WF" > "$PROGRESS_RUN"
[ -s "$PROGRESS_RUN" ] || fail "⑥: 没有抽出真实 progress run block"
bash -n "$PROGRESS_RUN" || fail "⑥: 抽出的 progress run block 语法错误"
NONCOMMENT_PROGRESS="$FIX/noncomment-progress.sh"
sed '/^[[:space:]]*#/d' "$PROGRESS_RUN" > "$NONCOMMENT_PROGRESS"
grep -qF 'set -euo pipefail' "$NONCOMMENT_PROGRESS" || fail "⑥: progress run 的非注释代码没有 pipefail"
grep -qF 'bash scripts/testing/fresh-db-gate.sh --progress 2>&1 | tee -a "$FRESH_DB_OUTPUT" || rc=$?' "$NONCOMMENT_PROGRESS" \
    || fail "⑥: progress run 的非注释代码没有在真实输出变量上捕获 gate rc"
grep -qF '[ -n "${FRESH_DB_OUTPUT:-}" ]' "$NONCOMMENT_PROGRESS" || fail "⑥: progress run 的非注释代码没有校验真实输出目的地"
grep -qE '^\s+2\) echo "::error title=Fresh DB 没量到\(rc=2\)' "$NONCOMMENT_PROGRESS" || fail "⑥: 工作流没有单独的 rc=2 分支"
grep -qF 'exit "$rc"' "$NONCOMMENT_PROGRESS" || fail "⑥: 工作流没把脚本 rc 原样退出 —— rc=2 会被吞成绿"
grep -qF "scripts/testing/fresh-db-startup-outcome.sh" "$WF" || fail "⑥: 改分类库的 PR 触发不到 fresh-db-progress"

mkdir -p "$FIX/runner/scripts/testing"
cat > "$FIX/runner/scripts/testing/fresh-db-gate.sh" <<'SH'
#!/usr/bin/env bash
printf 'MOCK_FRESH_DB_GATE rc=%s\n' "$FRESH_DB_MOCK_RC"
exit "$FRESH_DB_MOCK_RC"
SH
chmod +x "$FIX/runner/scripts/testing/fresh-db-gate.sh"

run_progress_fixture() {
    local label="$1" expected="$2" output="$FIX/output-$1.log" console="$FIX/console-$1.log" actual
    (
        cd "$FIX/runner"
        FRESH_DB_OUTPUT="$output" \
        FRESH_DB_MOCK_RC="$expected" \
        FRESH_DB_WORKFLOW_SOURCE_SHA='1111111111111111111111111111111111111111' \
        FRESH_DB_CANDIDATE_SOURCE_SHA='2222222222222222222222222222222222222222' \
        FRESH_DB_BACKEND_TREE='3333333333333333333333333333333333333333' \
        bash "$PROGRESS_RUN"
    ) > "$console" 2>&1
    actual=$?
    [ "$actual" -eq "$expected" ] || fail "⑥: $label fixture rc=$actual, expected $expected"
    grep -qF "MOCK_FRESH_DB_GATE rc=$expected" "$output" || fail "⑥: $label fixture 没写入真实 FRESH_DB_OUTPUT"
    grep -qF 'SOURCE workflow=1111111111111111111111111111111111111111 candidate=2222222222222222222222222222222222222222 backend_tree=3333333333333333333333333333333333333333' "$output" \
        || fail "⑥: $label fixture 没写入 provenance"
    case "$expected" in
        0) ! grep -qF '::error title=Fresh DB 闸红' "$console" || fail "⑥: positive fixture 被报成失败" ;;
        1) grep -qF '::error title=Fresh DB 闸红(rc=1)' "$console" || fail "⑥: rc=1 fixture 没有失败分类" ;;
        2) grep -qF '::error title=Fresh DB 没量到(rc=2)' "$console" || fail "⑥: rc=2 fixture 没有未量到分类" ;;
    esac
}

run_progress_fixture positive 0
run_progress_fixture gate_failure 1
run_progress_fixture unmeasured 2
ok "工作流真实 progress run block 通过 0/1/2 可执行夹具, rc 传播与 FRESH_DB_OUTPUT 均受守"

echo "PASS: $pass 项断言全部通过"
