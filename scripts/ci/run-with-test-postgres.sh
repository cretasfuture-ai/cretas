#!/usr/bin/env bash
# Run a command against an isolated, disposable local PostgreSQL cluster.
set -euo pipefail
[ "$#" -gt 0 ] || { echo 'Usage: run-with-test-postgres.sh command [args...]' >&2; exit 2; }
[ "$(id -u)" -ne 0 ] || { echo 'Refusing to run the test database as root' >&2; exit 2; }
# GitHub-hosted ubuntu-latest ships PostgreSQL 16 only; install 17 (prod major) from PGDG.
# The package auto-starts a 5432 cluster; this helper uses its own cluster on a free port.
if [ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] && [ ! -x /usr/lib/postgresql/17/bin/initdb ]; then
    sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y >/dev/null
    if ! sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q postgresql-17 postgresql-17-pgvector >/dev/null; then
        # 2026-11-12 实测: PGDG 先发了要求 postgresql-17 (> 17.12) 的 pgvector, 服务端包还没跟上,
        # apt 依赖无解 (exit 100), 全部 PG 闸在装库这步就红。服务端照装, pgvector 改从官方源码按已装版本编译。
        echo "::warning::PGDG 的 postgresql-17-pgvector 与 postgresql-17 暂时不匹配, 改为源码编译 pgvector"
        sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -q postgresql-17 postgresql-server-dev-17 build-essential >/dev/null
        pgv_src=$(mktemp -d "${RUNNER_TEMP:-/tmp}/pgvector.XXXXXX")
        git clone -q --depth 1 --branch v0.8.0 https://github.com/pgvector/pgvector.git "$pgv_src"
        make -s -C "$pgv_src" PG_CONFIG=/usr/lib/postgresql/17/bin/pg_config >/dev/null
        sudo make -s -C "$pgv_src" PG_CONFIG=/usr/lib/postgresql/17/bin/pg_config install >/dev/null
        rm -rf -- "$pgv_src"
    fi
fi
for candidate in "${CRETAS_CI_PG_BIN:-}" /usr/lib/postgresql/17/bin /opt/homebrew/opt/postgresql@17/bin; do
    if [ -n "$candidate" ] && [ -x "$candidate/initdb" ]; then export PATH="$candidate:$PATH"; break; fi
done
for tool in initdb pg_ctl createdb psql python3; do
    command -v "$tool" >/dev/null || { echo "Missing CI database tool: $tool" >&2; exit 2; }
done
cluster_root=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cretas-ci-pg.XXXXXX")
cleanup() {
    local result=$?
    trap - EXIT
    if [ -f "$cluster_root/data/postmaster.pid" ]; then
        if ! pg_ctl -D "$cluster_root/data" -m fast -w stop >>"$cluster_root/server.log" 2>&1; then
            echo "Failed to stop disposable database; evidence retained: $cluster_root" >&2
            exit 1
        fi
    fi
    if [ "$result" -ne 0 ]; then tail -30 "$cluster_root/server.log" 2>/dev/null || true; fi
    case "$cluster_root" in /*/cretas-ci-pg.*) rm -rf -- "$cluster_root" ;; *) exit 1 ;; esac
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
initdb -D "$cluster_root/data" --username=cretas_ci --auth-local=trust --auth-host=trust --encoding=UTF8 --no-locale >"$cluster_root/init.log"
# max_connections: 每个被缓存的 Spring 测试上下文各持一个 Hikari 池(默认 10 个、空闲也保留),
# 同时活着几个用 PG 的上下文由 surefire 的执行顺序决定 —— 本机顺序下不超过 3 个, 加拿大
# canada-mini-cretas 的顺序下超过 3 个, 30 就会 `too many clients`。2026-10-03 在那台 VM 上
# 同一组 7 个类对照: 30 -> 20 个里 3 个错误(16 次 too many clients), 200 -> 20/20。
# 这是随跑随删的临时实例, 提高上限不掩盖泄漏(真泄漏会持续增长, 200 也会撞上)。
pg_ctl -D "$cluster_root/data" -l "$cluster_root/server.log" -o "-h 127.0.0.1 -p $port -k $cluster_root -c shared_buffers=32MB -c max_connections=200" -w start
database=cretas_workflow_verify_test
createdb -h 127.0.0.1 -p "$port" -U cretas_ci "$database"
export SMARTBI_PG_DSN="postgresql://cretas_ci@127.0.0.1:$port/$database"
export CRETAS_WORKFLOW_PG_VERIFY=true
export CRETAS_WORKFLOW_PG_URL="jdbc:postgresql://127.0.0.1:$port/$database"
export CRETAS_WORKFLOW_PG_USER=cretas_ci CRETAS_WORKFLOW_PG_PASSWORD=''
echo "CI_POSTGRES=ready host=127.0.0.1 port=$port database=$database"
"$@"
