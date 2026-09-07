#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/docker-compose.fresh-db.yml"
BACKEND_DIR="$REPO_ROOT/backend/java/cretas-api"

FRESH_DB_PORT="${FRESH_DB_PORT:-55432}"
FRESH_DB_PROJECT="${FRESH_DB_PROJECT:-cretas-fresh-db}"
FRESH_DB_APP_PORT="${FRESH_DB_APP_PORT:-10019}"
FRESH_DB_KEEP="${FRESH_DB_KEEP:-0}"
STARTUP_TIMEOUT="${FRESH_DB_STARTUP_TIMEOUT:-300}"
DRY_RUN=0
PROGRESS=0
SHAPE_FLOOR="$SCRIPT_DIR/fresh-db-above-baseline-floor.txt"
BACKEND_PID=""
WORK_DIR="${TMPDIR:-/tmp}/cretas-fresh-db-${$}"
BACKEND_LOG="$WORK_DIR/backend.log"

usage() {
    cat <<'EOF'
Usage: bash scripts/testing/fresh-db-gate.sh [--keep] [--dry-run]

Starts a dedicated PostgreSQL 17 + pgvector container on localhost:55432,
runs the same Repository query startup gate as CI, then boots the real `pg`
profile against the empty database to exercise Flyway and full Spring/JPA
initialization. Containers are removed after the run unless --keep is used.

Options:
  --keep       Keep the database container after success or failure.
  --dry-run    Print the exact plan without invoking Docker or Maven.
  --progress   进度棘轮模式: 跳过 Repository 闸, 允许 Flyway 中途失败, 然后照样读出
               migrations_applied / tables / indexes 三个数并与基线比较 —— 三个只许涨。
               ⛔ 不是 pass/fail 闸: 今天这条链必然跑不完(见
               docs/ops/repo-cannot-build-a-fresh-database-2026-09-07.md),
               而开局就红的阻塞闸活不过一周。
  -h, --help   Show this help.

Local-only overrides:
  FRESH_DB_PORT=55432
  FRESH_DB_APP_PORT=10019
  FRESH_DB_PROJECT=cretas-fresh-db[-suffix]
  FRESH_DB_STARTUP_TIMEOUT=300
EOF
}

log() {
    printf '[fresh-db] %s\n' "$*"
}

fail() {
    printf '[fresh-db] ERROR: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --keep) FRESH_DB_KEEP=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --progress) PROGRESS=1 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
    shift
done

validate_port_range() {
    local name="$1"
    local raw_value="$2"
    [[ "$raw_value" =~ ^[0-9]{1,5}$ ]] \
        || fail "$name must be an integer between 1024 and 65535"
    local value=$((10#$raw_value))
    [ "$value" -ge 1024 ] && [ "$value" -le 65535 ] \
        || fail "$name must be between 1024 and 65535"
}

port_is_available() {
    local port="$1"
    if command -v powershell.exe >/dev/null 2>&1; then
        # ⚠️ 这一支排在 python3 前面, 所以**它**才是 Windows 上真正跑的那个 ——
        #    只在下面那支里分三态, 上面这条路径就永远走不到(形态 B: 机制在, 没接上)。
        #    ⇒ 两支都要分: 0 可用 / 1 被占用 / 2 被系统保留(SocketError AccessDenied)。
        powershell.exe -NoProfile -NonInteractive -Command \
            "\$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port); try { \$listener.Start(); \$listener.Stop(); exit 0 } catch [System.Net.Sockets.SocketException] { if (\$_.Exception.SocketErrorCode -eq 'AccessDenied') { exit 2 } else { exit 1 } } catch { exit 1 }" \
            >/dev/null 2>&1
        return $?
    fi
    if command -v python3 >/dev/null 2>&1; then
        # 🔴 退出码分三态, ⛔ 不是「能不能 bind」两态:
        #   0 = 可用   1 = 被别的进程占用(EADDRINUSE)   2 = **被系统保留**(WSAEACCES 10013)
        # 为什么必须分开: Windows(Hyper-V) 会动态保留成片端口, 且**每次重启都会漂**。
        # 2026-09-07 实测本机 55432(本脚本默认值) / 55440 / 55480 全部落进保留区,
        # 而 54000 可用 —— 同一天早些时候 55440 还是能用的。
        # ⚠️ 此前两种成因共用一句 "already in use; stop the existing process":
        #    对保留区间那种, **那个动作根本不存在可执行对象**(没有进程可停),
        #    读的人会去翻进程表然后一无所获。失败必须说出**能做的下一步**。
        python3 - "$port" <<'PY'
import errno
import socket
import sys

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    sock.bind(("127.0.0.1", int(sys.argv[1])))
except OSError as exc:
    sys.exit(2 if exc.errno == errno.EACCES else 1)
finally:
    sock.close()
PY
        return $?
    fi
    fail "cannot verify local port availability (PowerShell or python3 is required)"
}

require_port_available() {
    local name="$1"
    local port="$2"
    local rc=0
    port_is_available "$port" || rc=$?
    case "$rc" in
        0) return 0 ;;
        2) fail "$name $port 被**操作系统保留**(WSAEACCES), ⛔ 没有进程可停 —— Windows/Hyper-V 会动态
   保留成片端口且每次重启都会漂。用 \`netsh int ipv4 show excludedportrange protocol=tcp\` 看当前
   保留区间, 然后换一个区间外的端口(实测 54000 可用)。⚠️ 本脚本的默认值 55432 也可能落在里面。" ;;
        *) fail "$name $port is already in use on 127.0.0.1; stop the existing process or choose another port" ;;
    esac
}

validate_port_range "FRESH_DB_PORT" "$FRESH_DB_PORT"
validate_port_range "FRESH_DB_APP_PORT" "$FRESH_DB_APP_PORT"
[[ "$STARTUP_TIMEOUT" =~ ^[0-9]+$ ]] || fail "FRESH_DB_STARTUP_TIMEOUT must be numeric"
[ "$FRESH_DB_PORT" -ne 5432 ] || fail "port 5432 is intentionally forbidden; use the isolated default 55432"
case "$FRESH_DB_APP_PORT" in
    10010|10011|10020)
        fail "FRESH_DB_APP_PORT $FRESH_DB_APP_PORT is reserved; use an isolated local application port"
        ;;
esac
[ "$FRESH_DB_PORT" -ne "$FRESH_DB_APP_PORT" ] \
    || fail "FRESH_DB_PORT and FRESH_DB_APP_PORT must be different"
[[ "$FRESH_DB_PROJECT" =~ ^cretas-fresh-db(-[a-z0-9-]+)?$ ]] \
    || fail "FRESH_DB_PROJECT must be cretas-fresh-db or a safe cretas-fresh-db-* suffix"

COMPOSE=(docker compose -p "$FRESH_DB_PROJECT" -f "$COMPOSE_FILE")

run_maven() {
    if command -v mvn >/dev/null 2>&1; then
        mvn "$@"
    elif [ -x "$BACKEND_DIR/mvnw" ]; then
        "$BACKEND_DIR/mvnw" "$@"
    else
        fail "Maven is unavailable (install mvn or keep the repository Maven wrapper executable)"
    fi
}

terminate_process_tree() {
    local pid="$1"
    local child_pids=""
    local child_pid

    case "$pid" in
        ''|*[!0-9]*) return 0 ;;
    esac
    kill -0 "$pid" 2>/dev/null || return 0

    # `$!` is an MSYS PID in Git Bash. Walk the MSYS process table instead of
    # passing it to native taskkill, which can miss the Maven/Java descendants
    # and leave both the application port and Compose cleanup blocked.
    if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* || "${OSTYPE:-}" == win32* ]]; then
        child_pids=$(ps -e 2>/dev/null | awk -v parent="$pid" 'NR > 1 && $2 == parent {print $1}')
    elif command -v pgrep >/dev/null 2>&1; then
        child_pids=$(pgrep -P "$pid" 2>/dev/null || true)
    elif ps -eo pid=,ppid= >/dev/null 2>&1; then
        child_pids=$(ps -eo pid=,ppid= 2>/dev/null | awk -v parent="$pid" '$2 == parent {print $1}')
    fi
    for child_pid in $child_pids; do
        terminate_process_tree "$child_pid"
    done
    kill -TERM "$pid" 2>/dev/null || true
    sleep 0.05
    kill -KILL "$pid" 2>/dev/null || true
}

cleanup() {
    local rc=$?
    if [ -n "$BACKEND_PID" ]; then
        terminate_process_tree "$BACKEND_PID"
        wait "$BACKEND_PID" >/dev/null 2>&1 || true
        BACKEND_PID=""
    fi
    if [ "$DRY_RUN" -eq 0 ] && command -v docker >/dev/null 2>&1; then
        if [ "$FRESH_DB_KEEP" = "1" ]; then
            log "container kept: project=$FRESH_DB_PROJECT port=$FRESH_DB_PORT"
        else
            "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
            log "isolated database removed"
        fi
    fi
    if [ "$rc" -ne 0 ] && [ -f "$BACKEND_LOG" ]; then
        printf '\n[fresh-db] backend startup tail (%s):\n' "$BACKEND_LOG" >&2
        tail -120 "$BACKEND_LOG" >&2 || true
    fi
    if [ -d "$WORK_DIR" ]; then
        if [ "$FRESH_DB_KEEP" = "1" ]; then
            log "work directory kept: $WORK_DIR"
        else
            rm -rf -- "$WORK_DIR"
        fi
    fi
    return "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$DRY_RUN" -eq 1 ]; then
    cat <<EOF
[fresh-db] DRY RUN
compose project : $FRESH_DB_PROJECT
database        : pgvector/pgvector:pg17 on 127.0.0.1:$FRESH_DB_PORT (never 5432)
application     : http://127.0.0.1:$FRESH_DB_APP_PORT
cleanup         : $([ "$FRESH_DB_KEEP" = "1" ] && echo keep || echo remove)

1. docker compose down -v, then up a genuinely empty database
2. create vector extensions and the local-only smartbi database
3. mvn -B test -Dspring.profiles.active=test -Dtest='*RepositoryQueryValidationTest' -Dsurefire.failIfNoSpecifiedTests=false
4. mvn -B spring-boot:run -Dspring-boot.run.profiles=pg (database URL overridden to port $FRESH_DB_PORT)
5. require /api/mobile/health and a non-empty, successful flyway_schema_history
EOF
    exit 0
fi

command -v docker >/dev/null 2>&1 || fail "Docker CLI not found; install/start Docker Desktop and reopen Git Bash"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required (docker compose)"
command -v curl >/dev/null 2>&1 || fail "curl is required for the Spring health gate"
[ -f "$COMPOSE_FILE" ] || fail "missing $COMPOSE_FILE"
require_port_available "FRESH_DB_PORT" "$FRESH_DB_PORT"
require_port_available "FRESH_DB_APP_PORT" "$FRESH_DB_APP_PORT"
mkdir -p "$WORK_DIR"

export FRESH_DB_PORT
log "resetting dedicated Compose project $FRESH_DB_PROJECT (host port $FRESH_DB_PORT)"
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --force-recreate

log "waiting for PostgreSQL readiness"
ready=0
for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T postgres pg_isready -U cretas_user -d cretas_db >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" -eq 1 ] || fail "PostgreSQL did not become ready within 60 seconds"

log "bootstrapping pgvector and SmartBI database (local placeholder credentials only)"
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U cretas_user -d cretas_db <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE USER smartbi_user WITH PASSWORD 'smartbi_local_only';
CREATE DATABASE smartbi_db OWNER smartbi_user;
SQL
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U cretas_user -d smartbi_db \
    -c 'CREATE EXTENSION IF NOT EXISTS vector;'

# ── 🔴 baseline bootstrap: 新库 = 灌基线 → flyway baseline → 只跑基线之上的 ──────
#    票 OPS-FRESH-DB-BASELINE-BOOTSTRAP-20260907
#    文档 docs/ops/fresh-db-baseline-bootstrap-2026-09-07.md
#
# ⛔ 基线缺席**不许静默回退到「从史前史全量重放」**: 那条路今天死在第 267 条,
#    三个数会**整体掉下来** —— 与「棘轮抓到一次真倒退」读数完全相同。⇒ 缺席就红。
BASELINE_SQL="$REPO_ROOT/ops/db-baseline/prod-schema-baseline.sql"
[ -s "$BASELINE_SQL" ] \
    || fail "baseline bootstrap: 找不到非空基线 $BASELINE_SQL
  ⛔ 不回退到全量重放 —— 那会让三个数整体下跌, 与「真倒退」同形。
  ⇒ 先跑 bash scripts/ops/capture-fresh-db-baseline.sh --write (需要 prod 只读凭证)。"
# ⚠️ 版本号从**基线文件头**读, ⛔ 不写死。写死 = 基线换了而这里没换 ⇒ 两份副本漂(形态 D),
#    而漂的表现是「跑了一堆本该跳过的迁移」—— 它不报错, 只是慢慢做错事。
BASELINE_VERSION=$(sed -nE 's/^-- flyway_schema_history 最大版本: ([0-9.]+).*/\1/p' "$BASELINE_SQL" | head -1)
[ -n "$BASELINE_VERSION" ] \
    || fail "baseline bootstrap: 基线文件头里读不出 flyway 最大版本 —— **没量到**, ⛔ 不猜一个"

log "baseline bootstrap: 灌 $(basename "$BASELINE_SQL") (baselineVersion=$BASELINE_VERSION)"
# 🔴 dump 里有一行 `CREATE SCHEMA public;`, 而任何新库上 public 天然存在 ⇒ 它必然报错。
#    ⇒ 本步用 ON_ERROR_STOP=1(**任何**错误当场停), 所以要先把这一行摘掉。
# ⛔ 不许改成「先 DROP SCHEMA public 再灌」—— 实测那会 **CASCADE 掉 vector 扩展**:
#    错误 1 → 56 条, 表 502 → 498。▎消掉一条已知错误的办法, 可能比那条错误糟得多。
# ⚠️ 摘除必须**可证**: 断言恰好少 1 行, ⛔ 不接受 0 行(锚点漂了)或 ≥2 行(误删)。
sed '/^CREATE SCHEMA public;$/d' "$BASELINE_SQL" > "$WORK_DIR/baseline-loadable.sql"
raw_lines=$(wc -l < "$BASELINE_SQL"); cut_lines=$(wc -l < "$WORK_DIR/baseline-loadable.sql")
[ "$((raw_lines - cut_lines))" -eq 1 ] \
    || fail "baseline bootstrap: 摘 'CREATE SCHEMA public;' 摘掉了 $((raw_lines - cut_lines)) 行(期望 1) —— ⛔ 不拿一份不知道被改成什么样的基线去灌"
if ! "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U cretas_user -d cretas_db \
        < "$WORK_DIR/baseline-loadable.sql" > "$WORK_DIR/baseline-load.log" 2>&1; then
    grep -i "error" "$WORK_DIR/baseline-load.log" | head -10 >&2 || true
    fail "baseline bootstrap: 灌基线失败(ON_ERROR_STOP=1 ⇒ 任何一条错误都会停), 全文见 $WORK_DIR/baseline-load.log"
fi

# 🔴 dump 里**带着** flyway_schema_history 这张表(prod 上有它), 但 schema-only ⇒ **没有行**。
#    ⚠️ 「表在、里面空」是最坏的一种形态: Flyway 不会认为需要 baseline,
#       而会认为一条都还没 applied ⇒ **从头跑全部 697 条**, 打在一个已经建好的库上。
#       ⇒ 必须先删掉它, 让 baseline-on-migrate 看到它唯一认识的形态:
#         「schema 非空 + 没有 history 表」。
# ⚠️ 先断言它**确实在** —— 否则 DROP IF EXISTS 是个静默 no-op, 而将来基线若改成
#    「不含这张表」, 这里会一声不响地什么都不做。
bq() { "${COMPOSE[@]}" exec -T postgres psql -tAc "$1" -U cretas_user -d cretas_db 2>/dev/null | tr -d '\r' | head -1; }
hist_before=$(bq "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='flyway_schema_history';" || true)
[ "$hist_before" = "1" ] \
    || fail "baseline bootstrap: 灌完基线后 flyway_schema_history 不在(实得 '$hist_before') —— 基线形态与预期不符, ⛔ 不继续。
  ⇒ 若是刻意改成「基线不含这张表」, 请同时删掉本段断言并说明。"
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U cretas_user -d cretas_db \
    -c 'DROP TABLE IF EXISTS public.flyway_schema_history;' >/dev/null
hist_after=$(bq "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='flyway_schema_history';" || true)
[ "$hist_after" = "0" ] || fail "baseline bootstrap: history 表没删掉(实得 '$hist_after')"

bs_tables=$(bq "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';" || true)
bs_indexes=$(bq "SELECT count(*) FROM pg_indexes WHERE schemaname='public';" || true)
echo "READING: BASELINE_BOOTSTRAP version=$BASELINE_VERSION tables_after_load=$bs_tables indexes_after_load=$bs_indexes history_table_dropped=yes"
# 阳性对照: 灌完必须**真的**有几百张表。一次「psql 成功但什么都没建」会让下面每个数
# 都从 0 开始涨, 而那看起来只是「进度不太好」, ⛔ 不像故障。
case "$bs_tables" in ''|*[!0-9]*) fail "baseline bootstrap: 数不出表数(实得 '$bs_tables') —— **没量到**" ;; esac
[ "$bs_tables" -ge 400 ] \
    || fail "baseline bootstrap: 灌完只有 $bs_tables 张表 —— 基线没真正生效, ⛔ 不许当成「跑起来了」"

# 🔴 灌完的形状必须与基线**文件头记的**逐位相同。
# ⚠️ 上面那条 `>= 400` 是个下界, 它挡不住「少了 4 张表」这种 —— 而实测里「先 DROP SCHEMA
#    public」正好就是少 4 张(498)。▎下界挡得住灾难, ⛔ 挡不住偏差, 而偏差才是这里的失效形态。
h_tab=$(sed -nE 's/^-- 形状.*tables=([0-9]+).*/\1/p' "$BASELINE_SQL" | head -1)
h_idx=$(sed -nE 's/^-- 形状.*indexes=([0-9]+).*/\1/p' "$BASELINE_SQL" | head -1)
if [ -n "$h_tab" ] && [ -n "$h_idx" ]; then
    # ⚠️ 比的是**灌完、Flyway 还没跑**的那一刻 —— 此刻应当恰好等于基线,
    #    只差我们刚删掉的 flyway_schema_history 那一张表和它的索引。
    exp_tab=$((h_tab - 1))
    [ "$bs_tables" = "$exp_tab" ] \
        || fail "baseline bootstrap: 灌完 $bs_tables 张表, 基线头记 $h_tab(减去刚删的 flyway_schema_history 应为 $exp_tab) —— ⛔ 形状不符, 不继续"
    echo "READING: BASELINE_LOAD_SHAPE tables=$bs_tables expected=$exp_tab header_tables=$h_tab header_indexes=$h_idx"
else
    echo "READING: BASELINE_LOAD_SHAPE header_unparsed=yes —— **没量到**(基线头格式变了?), ⛔ 不当成对上了"
fi

if [ "$PROGRESS" -eq 1 ]; then
    # ⛔ 进度模式跳过这一步: 它会编译 1641 个测试类, 是这条链 6 分钟里的大头,
    #    而棘轮要的三个数一个都不来自它。
    # ⚠️ 代价要说清楚: 跳过之后本趟**不再验** Repository 查询闸, 所以 `--progress` 的绿
    #    ⛔ 不能当成 fresh-db-gate 的绿 —— 它们量的不是同一件事。
    log "progress mode: skipping the Repository query gate (not part of the three ratcheted numbers)"
else
log "running CI-equivalent Repository query startup gate"
(
    cd "$BACKEND_DIR"
    run_maven -B test \
        -Dspring.profiles.active=test \
        "-Dtest=*RepositoryQueryValidationTest" \
        -Dsurefire.failIfNoSpecifiedTests=false
)
fi

DB_URL="jdbc:postgresql://127.0.0.1:${FRESH_DB_PORT}/cretas_db?currentSchema=public&stringtype=unspecified"
SMARTBI_URL="jdbc:postgresql://127.0.0.1:${FRESH_DB_PORT}/smartbi_db"
RUN_ARGS="--server.address=127.0.0.1 --server.port=${FRESH_DB_APP_PORT} --spring.datasource.url=${DB_URL} --spring.datasource.username=cretas_user --spring.datasource.password=cretas_local_only --smartbi.postgres.url=${SMARTBI_URL} --smartbi.postgres.username=smartbi_user --smartbi.postgres.password=smartbi_local_only --spring.jpa.show-sql=false --python-smartbi.enabled=false --python-error-analysis.enabled=false --python-classifier.enabled=false --spring.flyway.baseline-version=${BASELINE_VERSION} --spring.flyway.baseline-description=cretas-prod-schema-baseline --spring.jpa.hibernate.ddl-auto=none"
export DB_PASSWORD=cretas_local_only
export POSTGRES_SMARTBI_PASSWORD=smartbi_local_only
export JWT_SECRET=fresh_db_local_jwt_secret_not_for_remote_use

# 🔴 `ddl-auto=none` 是**刻意覆盖**的, ⛔ 不是抄配置抄漏了。
#    `pg` profile 写的是 ddl-auto=update, 而 **prod 跑的是 pg-prod, 那里是 none**。
#    实测(2026-09-07): 带着 update 跑完 ⇒ indexes=2119, 而基线+两条迁移应当是 2048
#    ⇒ **Hibernate 自己补了 71 个索引**。
#    ⚠️ 后果不是「多了几个索引」: 本闸问的是「**迁移**能不能把库建出来」,
#      而 Hibernate 会在迁移之后**替它把缺的补上** ⇒ 一条缺失的迁移会被悄悄盖住,
#      读数照样漂亮。▎让被测对象自己修补自己, 这个测量就不再回答原来的问题。
# ⚠️ 把**配置本身**打成读数, ⛔ 不要只改行为不留痕 ——
#    否则那三个形状数**不可归因**: 今天这一版和上一版数字不同(2119 vs 2044),
#    而日志里看不出是「迁移变了」还是「配置变了」。
echo "READING: FRESH_DB_ENV profile=pg ddl_auto=none(覆盖, 与 prod 的 pg-prod 对齐) flyway_baseline_version=$BASELINE_VERSION flyway_locations=classpath:db/flyway"
log "starting real pg profile for Flyway + full Spring/JPA gate"
require_port_available "FRESH_DB_APP_PORT" "$FRESH_DB_APP_PORT"
(
    cd "$BACKEND_DIR"
    run_maven -B spring-boot:run \
        -Dspring-boot.run.profiles=pg \
        "-Dspring-boot.run.arguments=$RUN_ARGS"
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

healthy=0
died=0
for _ in $(seq 1 "$STARTUP_TIMEOUT"); do
    if ! kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
        wait "$BACKEND_PID" || true
        BACKEND_PID=""
        # ⚠️ 进度模式下「进程退出」是**预期**的 —— 今天 Flyway 必然在半路死掉。
        #    ⛔ 但不能因此静默继续: 记下来, 后面的读数要带上这个事实。
        if [ "$PROGRESS" -eq 1 ]; then died=1; break; fi
        fail "Spring process exited before becoming healthy"
    fi
    if curl --silent --show-error --fail --max-time 2 \
        "http://127.0.0.1:${FRESH_DB_APP_PORT}/api/mobile/health" >/dev/null 2>&1 \
        && kill -0 "$BACKEND_PID" >/dev/null 2>&1 \
        && grep -q 'Started CretasBackendApplication' "$BACKEND_LOG"; then
        healthy=1
        break
    fi
    sleep 1
done
if [ "$PROGRESS" -eq 1 ]; then
    # ── 进度棘轮 ────────────────────────────────────────────────────────────
    # 三个数 + 失败点。⛔ 不判 pass/fail, 只判「有没有倒退」。
    q() { "${COMPOSE[@]}" exec -T postgres psql -tAc "$1" -U cretas_user -d cretas_db 2>/dev/null | tr -d '\r' | head -1; }
    # ⚠️ 每个都要 `|| true`: 本脚本是 `set -euo pipefail`, 而 `x=$(cmd)` 里 cmd 失败会**当场**
    #    中止脚本 —— 于是下面那个「读不出来 ⇒ 没量到」的三态检查**永远走不到**,
    #    而退出码 2 与「有倒退」在调用方看来是同一件事。
    #    🔴 实测踩过: 第一趟真跑 PROG_RC=2、一行读数都没有 —— shell 替我做了那个判定。
    applied=$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true;" || true)
    tables=$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';" || true)
    indexes=$(q "SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public';" || true)
    for v in "$applied" "$tables" "$indexes"; do
        case "$v" in
            ''|*[!0-9]*)
                # 读不出来 ⇒ **没量到**, 与「倒退」是两回事, 措辞必须不同。
                fail "progress: 读不出三个数之一 (applied='$applied' tables='$tables' indexes='$indexes') —— 本趟读数作废, ⛔ 不当成倒退" ;;
        esac
    done

    # 🔴 硬约束 9 的对照: 失败点是**必然存在**的读数(今天这条链一定会死在某处)。
    #    它不出现, 说明这趟根本没跑到 Flyway —— 那时三个数即使没倒退也毫无意义。
    # 同理: grep 无命中返回 1, pipefail 会让整条管道非零 ⇒ 又一次在检查之前中止。
    fail_file=$(grep -oE 'Migration V[0-9A-Za-z_.]+\.sql failed' "$BACKEND_LOG" 2>/dev/null | head -1 | sed 's/^Migration //;s/ failed$//' || true)
    fail_state=$(grep -oE 'SQL State  *: *[0-9A-Za-z]+' "$BACKEND_LOG" 2>/dev/null | head -1 | sed 's/.*: *//' || true)
    echo "READING: FRESH_DB_SHAPE migrations_above_baseline=$applied tables=$tables indexes=$indexes"
    echo "READING: FRESH_DB_FAILURE file=${fail_file:-<none>} sqlstate=${fail_state:-<none>} process_died=$died"
    if [ -z "$fail_file" ] && [ "$healthy" -ne 1 ]; then
        fail "progress: 既没有失败点读数、也没有健康 —— 这趟没跑到 Flyway, ⛔ 三个数不作数"
    fi
    [ -n "$fail_file" ] || log "progress: 没有失败点 —— 🎉 这条链已经跑完了, 请把 --progress 换成正常模式并考虑接成 pass/fail 闸"

    # ── 失败迁移: 这一条是 pass/fail, ⛔ 不是棘轮 ────────────────────────────
    # 基线 bootstrap 落地之后, 「跑完」不再是遥远目标而是常态 ⇒ 一条失败的迁移
    # 必须当场红。⚠️ 它与下面的形状下界是两件事: 形状可以因为新迁移而涨,
    # 但 success=false 的行数**任何时候都该是 0**。
    failed=$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false;" || true)
    case "$failed" in ''|*[!0-9]*) fail "shape: 数不出失败迁移条数(实得 '$failed') —— **没量到**" ;; esac
    [ "$failed" -eq 0 ] || fail "shape: flyway_schema_history 里有 $failed 条失败迁移 —— ⛔ 直接红, 这不是「进度」问题"

    if [ ! -f "$SHAPE_FLOOR" ]; then
        log "shape: 下界文件不存在, 写入首个下界 $SHAPE_FLOOR"
        printf 'tables=%s\nindexes=%s\n' "$tables" "$indexes" > "$SHAPE_FLOOR"
        exit 0
    fi
    b_tables=$(grep -oE '^tables=[0-9]+' "$SHAPE_FLOOR" | cut -d= -f2)
    b_indexes=$(grep -oE '^indexes=[0-9]+' "$SHAPE_FLOOR" | cut -d= -f2)
    for v in "$b_tables" "$b_indexes"; do
        case "$v" in ''|*[!0-9]*) fail "shape: 下界文件解析不出 tables/indexes —— ⛔ 不拿一个读不懂的下界去判倒退" ;; esac
    done
    echo "READING: FRESH_DB_SHAPE_FLOOR tables=$b_tables indexes=$b_indexes"
    # 🔴 ⛔ migrations_above_baseline **刻意不设下界**。
    #    它 = baseline 行 + 基线之上已跑的迁移条数, 而**每次重新采集基线, 它都会掉回 1**
    #    (基线往前挪 ⇒ 上面的迁移被吸收进去)。⇒ 给它设下界 = 每次刷新基线都要红一次,
    #    而那次红与「真的少跑了迁移」逐字同形。▎**会被正常操作触发的闸, 迟早被关掉。**
    #    ⇒ 它只报数; 真正承重的是上面那条 failed=0 和下面的形状下界。
    regressed=""
    [ "$tables"  -lt "$b_tables"  ] && regressed="$regressed tables($tables<$b_tables)"
    [ "$indexes" -lt "$b_indexes" ] && regressed="$regressed indexes($indexes<$b_indexes)"
    if [ -n "$regressed" ]; then
        fail "shape: 有倒退 ——$regressed。⛔ 形状只许涨: 倒退意味着新库比昨天更建不出来"
    fi
    if [ "$tables" -gt "$b_tables" ] || [ "$indexes" -gt "$b_indexes" ]; then
        log "shape: 形状变大了。把下面两行**整体替换** $SHAPE_FLOOR 里的对应两行, 即可锁住:"
        printf '    tables=%s\n    indexes=%s\n' "$tables" "$indexes"
        log "shape: (旧值 tables=$b_tables indexes=$b_indexes)"
    fi
    log "shape: 无倒退 (tables $tables/$b_tables, indexes $indexes/$b_indexes; 失败迁移 $failed; 基线之上已跑 $applied)"
    exit 0
fi

[ "$healthy" -eq 1 ] || fail "Spring health gate timed out after ${STARTUP_TIMEOUT}s"
kill -0 "$BACKEND_PID" >/dev/null 2>&1 \
    || fail "Spring process exited immediately after the health response"

log "verifying Flyway history on the fresh database"
MIGRATION_COUNT=$("${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 \
    -U cretas_user -d cretas_db -tAc 'SELECT COUNT(*) FROM flyway_schema_history;')
FAILED_COUNT=$("${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 \
    -U cretas_user -d cretas_db -tAc 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = false;')
[[ "$MIGRATION_COUNT" =~ ^[0-9]+$ ]] && [ "$MIGRATION_COUNT" -gt 0 ] \
    || fail "Flyway history is missing or empty"
[ "$FAILED_COUNT" = "0" ] || fail "Flyway history contains $FAILED_COUNT failed migration(s)"

log "PASS: Repository gate + Flyway + full Spring/JPA startup (${MIGRATION_COUNT} migrations)"
