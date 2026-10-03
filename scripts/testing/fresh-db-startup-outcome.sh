# shellcheck shell=bash
# fresh-db-gate.sh --progress 的「启动结局」分类 —— 纯函数, 只读 backend 日志 + 两个标志。
# 被 scripts/testing/fresh-db-gate.sh source; scripts/tests/test-fresh-db-startup-outcome.sh
# 用合成日志逐态喂它(⛔ 不起 Docker)。
#
# 🔴 为什么单独成文件: 2026-10-01 ~ 10-03 四趟 CI(hosted ubuntu-latest)在 300s 预算处被截断,
#    当时 Flyway 早已跑完(migrations_above_baseline=14, process_died=0, Spring 还在初始化 bean),
#    却被报成「这趟没跑到 Flyway」—— 判定只有「有失败点 / 健康 / 其它」三支,
#    「其它」把「超时」和「进程死了」和「真没跑到」全折成同一句话、同一个 rc=1。
#    ▎同一个红, 成因不同, 而只看红绿分不出。⇒ 结局逐个命名, 措辞与退出码逐个不同。
#
# 五个结局(⛔ 不许再折回三个):
#   HEALTHY               健康                                    rc=0 → 继续判形状
#   FLYWAY_FAILED         日志里有 `Migration V….sql failed`       rc=1 真失败
#   DIED_BEFORE_HEALTHY   进程退出、没健康、日志里没有失败点       rc=1 真失败(迁移之外)
#   TIMEOUT_AFTER_FLYWAY  进程还活着、Flyway 已完成、没到健康      rc=2 没量到(启动那一半)
#   TIMEOUT_BEFORE_FLYWAY 进程还活着、日志里没看到 Flyway 完成     rc=2 没量到(三个数也不作数)
#
# ⚠️ FLYWAY_FAILED 必须**自己**判红, ⛔ 不能交给 flyway_schema_history 的 success=false:
#    PostgreSQL 支持事务性 DDL, 失败的迁移整体回滚, history 里**不留** success=false 那一行
#    ⇒ 只靠 failed=0 判, 一条坏迁移会读成「失败 0 条」, 再被形状棘轮放过。

# Flyway 失败点: `Migration V20261103_01__x.sql failed`
FRESH_DB_FLYWAY_FAIL_RE='Migration V[0-9A-Za-z_.]+\.sql failed'
# Flyway 完成: 有迁移要跑 / 没有迁移要跑, 两种措辞都算「Flyway 这一步结束了」
FRESH_DB_FLYWAY_DONE_RE='Successfully applied [0-9]+ migrations? to schema|is up to date\. No migration necessary'
# Maven 编译完、把控制交给 Spring 的那一刻(用来把预算拆成「编译」与「启动」两段)
FRESH_DB_SPRING_START_RE='Starting CretasBackendApplication'

# 失败点文件名(没有则空串)。⚠️ grep 无命中返回 1, 调用方是 set -euo pipefail ⇒ 必须 || true。
fresh_db_failure_file() {   # $1=backend log
    { grep -oE "$FRESH_DB_FLYWAY_FAIL_RE" "$1" 2>/dev/null || true; } \
        | head -1 | sed 's/^Migration //;s/ failed$//'
}

fresh_db_failure_sqlstate() {   # $1=backend log
    { grep -oE 'SQL State  *: *[0-9A-Za-z]+' "$1" 2>/dev/null || true; } \
        | head -1 | sed 's/.*: *//'
}

# $1=backend log  $2=healthy(0/1)  $3=died(0/1)  ⇒ 打印结局名
classify_startup_outcome() {
    local log="$1" healthy="$2" died="$3"
    # 失败点优先于健康: 「日志说某条迁移失败了」而进程又健康, 是自相矛盾的读数, ⛔ 不放绿。
    if [ -n "$(fresh_db_failure_file "$log")" ]; then
        echo FLYWAY_FAILED
    elif [ "$healthy" = "1" ]; then
        echo HEALTHY
    elif [ "$died" = "1" ]; then
        echo DIED_BEFORE_HEALTHY
    elif grep -qE "$FRESH_DB_FLYWAY_DONE_RE" "$log" 2>/dev/null; then
        echo TIMEOUT_AFTER_FLYWAY
    else
        echo TIMEOUT_BEFORE_FLYWAY
    fi
}

# 退出码是开放集合的反面: 只有 HEALTHY 是 0; 认不出的结局名 ⇒ 2(没量到), ⛔ 不落进绿。
startup_outcome_rc() {   # $1=结局名
    case "$1" in
        HEALTHY) echo 0 ;;
        FLYWAY_FAILED|DIED_BEFORE_HEALTHY) echo 1 ;;
        *) echo 2 ;;
    esac
}

# $1=结局 $2=applied(history 成功行数) $3=用时s $4=预算s $5=失败文件 $6=SQLSTATE
startup_outcome_message() {
    local state="$1" applied="${2:-}" elapsed="$3" budget="$4" ffile="${5:-}" fstate="${6:-}"
    case "$state" in
        HEALTHY)
            echo "启动健康 (用时 ${elapsed}s / 预算 ${budget}s)" ;;
        FLYWAY_FAILED)
            echo "Flyway 迁移失败: ${ffile} (SQLSTATE ${fstate:-<none>}) —— 新库建不出来。⛔ 不是超时, 也 ⛔ 不交给 failed=0 判(PG 回滚后 history 不留失败行)" ;;
        DIED_BEFORE_HEALTHY)
            echo "进程在健康前退出 (用时 ${elapsed}s), 日志里没有 Flyway 失败点 ⇒ 启动失败, 成因在迁移之外, 看下面的 backend tail" ;;
        TIMEOUT_AFTER_FLYWAY)
            echo "启动超时: 迁移已执行 ${applied:-?} 个 (Flyway 已完成), 进程仍在启动 —— ${budget}s 内没到健康。⛔ 不是迁移失败; Spring/JPA 启动这一半本趟没量到。反复出现就调大 FRESH_DB_STARTUP_TIMEOUT" ;;
        TIMEOUT_BEFORE_FLYWAY)
            echo "启动超时: ${budget}s 内日志里没看到 Flyway 完成 (history 成功行 ${applied:-读不出}), 进程仍在启动 —— 多半还在编译或迁移半途, 三个数不作数" ;;
        *)
            echo "认不出的启动结局 [$state] —— 分类器与调用方不一致, 本趟没量到" ;;
    esac
}
