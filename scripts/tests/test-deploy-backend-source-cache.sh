#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"
MANIFEST_HELPER="$ROOT_DIR/scripts/deploy/release-jar-manifest.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

source "$MANIFEST_HELPER"

HELPERS=$(awk '
    /^# BEGIN_BACKEND_SOURCE_CACHE_HELPERS$/ {copy = 1; next}
    /^# END_BACKEND_SOURCE_CACHE_HELPERS$/ {exit}
    copy {print}
' "$DEPLOY_SCRIPT")
eval "$HELPERS"

FIXTURE_REPO="$TMP_ROOT/repo"
SOURCE_JAR="$TMP_ROOT/source.jar"
JAR_NAME="cretas-backend-system-1.0.0.jar"
DEST_JAR="$TMP_ROOT/destination/$JAR_NAME"
PROJECT_ROOT="$FIXTURE_REPO"
LOCAL_JAR_CACHE_ROOT="$TMP_ROOT/cache"
mkdir -p "$FIXTURE_REPO/backend/java/cretas-api"
(
    cd "$FIXTURE_REPO"
    git init -q -b main
    git config user.name test
    git config user.email test@example.com
    printf '<project/>\n' > backend/java/cretas-api/pom.xml
    git add backend/java/cretas-api/pom.xml
    git commit -qm backend
    git update-ref refs/remotes/origin/main HEAD
)
printf 'verified jar bytes\n' > "$SOURCE_JAR"
JAR_CONTENT="$TMP_ROOT/jar-content"
mkdir -p "$JAR_CONTENT"
printf 'verified jar bytes\n' > "$JAR_CONTENT/payload.txt"
rm -f "$SOURCE_JAR"
(cd "$JAR_CONTENT" && jar --create --file "$SOURCE_JAR" payload.txt)

# Store a verified main artifact, advance main with docs only, and prove the
# unchanged backend tree reuses the same bytes across commits.
LOCAL_MAVEN_BUILD_COMPLETED=true store_local_source_artifact_cache "$SOURCE_JAR"
(
    cd "$FIXTURE_REPO"
    mkdir -p docs
    printf 'docs only\n' > docs/note.md
    git add docs/note.md
    git commit -qm docs
    git update-ref refs/remotes/origin/main HEAD
)
reuse_local_source_artifact_cache "$DEST_JAR"
cmp "$SOURCE_JAR" "$DEST_JAR" || fail "docs-only commit did not reuse identical JAR"

# Backend content changes must produce a different tree and fail closed.
(
    cd "$FIXTURE_REPO"
    printf '<project>changed</project>\n' > backend/java/cretas-api/pom.xml
    git add backend/java/cretas-api/pom.xml
    git commit -qm backend-change
    git update-ref refs/remotes/origin/main HEAD
)
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "changed backend tree reused stale JAR"
fi

# Restore the cached tree, then corrupt its checksum and prove rejection.
git -C "$FIXTURE_REPO" reset -q --hard HEAD~1
git -C "$FIXTURE_REPO" update-ref refs/remotes/origin/main HEAD
sed -i 's/^jar_sha256=.*/jar_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
    "$LOCAL_JAR_CACHE_ROOT/current/$RELEASE_MANIFEST_NAME"
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "corrupt cache checksum was accepted"
fi

# Explicit cache disable must fail closed.
LOCAL_MAVEN_BUILD_COMPLETED=true store_local_source_artifact_cache "$SOURCE_JAR"
if DISABLE_LOCAL_JAR_CACHE=1 reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "DISABLE_LOCAL_JAR_CACHE=1 did not disable reuse"
fi

# Dirty includes untracked files. A clean index/diff is not enough.
printf 'untracked\n' > "$FIXTURE_REPO/untracked.txt"
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "untracked release worktree was accepted"
fi
rm "$FIXTURE_REPO/untracked.txt"

# Recompute the checksum over deliberately non-ZIP bytes. SHA matching alone
# must not make a damaged JAR trustworthy.
printf 'not a jar\n' > "$LOCAL_JAR_CACHE_ROOT/current/$JAR_NAME"
DAMAGED_SHA=$(sha256sum "$LOCAL_JAR_CACHE_ROOT/current/$JAR_NAME" | awk '{print $1}')
sed -i "s/^jar_sha256=.*/jar_sha256=$DAMAGED_SHA/" \
    "$LOCAL_JAR_CACHE_ROOT/current/$RELEASE_MANIFEST_NAME"
if reuse_local_source_artifact_cache "$DEST_JAR"; then
    fail "damaged non-ZIP JAR was accepted with a matching SHA"
fi

# Mock the two SSH hosts: identical bytes + real parsed upstream + active unit
# + HTTP 200 allow no-op. Any byte mismatch or FORCE_REDEPLOY must reject it.
MODE=jar
DEPLOY_ENV=prod
DEPLOY_MODE=bluegreen
SERVER=root@backend
GATEWAY=root@gateway
REMOTE_JAR_DIR=/srv/cretas
RUNTIME_JAR_NAME=aims-0.0.1-SNAPSHOT.jar
NGINX_UPSTREAM_FILE=/etc/nginx/upstream.conf
BLUE_SERVICE=cretas-backend
GREEN_SERVICE=cretas-backend-green
LOCAL_MD5=0123456789abcdef0123456789abcdef
MOCK_UPSTREAM_PORT=10020
MOCK_SERVICE_STATE=active
MOCK_HTTP_STATUS=200
MOCK_MAIN_PID=4242
# 运行进程打开的运行 jar 句柄: 「<md5> live|deleted」每行一个。默认 = 正常已部署态。
OLD_MD5=fedcba9876543210fedcba9876543210
MOCK_OPEN_JARS="$LOCAL_MD5 live"
MOCK_REMOTE_FD_DIR=
MOCK_TRUNCATED=
ssh() {
    case "$*" in
        # ⚠️ 句柄这条也跑 md5sum, 必须排在 *md5sum* 之前, 否则被那条吃掉。
        *"/proc/$MOCK_MAIN_PID/fd/"*)
            [[ "$*" == *"$REMOTE_JAR_DIR/$RUNTIME_JAR_NAME (deleted)"* ]] \
                || fail "open-JAR check does not recognise deleted runtime JAR handles"
            if [ -n "$MOCK_REMOTE_FD_DIR" ]; then
                # 远端那段命令原样在本机跑, 只把 /proc/<pid>/fd 换成假目录。
                local remote_cmd="${*: -1}"
                bash -c "${remote_cmd//\/proc\/$MOCK_MAIN_PID\/fd/$MOCK_REMOTE_FD_DIR}"
            else
                [ -z "$MOCK_OPEN_JARS" ] || printf '%s\n' "$MOCK_OPEN_JARS"
                [ -n "$MOCK_TRUNCATED" ] || printf 'END\n'
            fi
            ;;
        *md5sum*)
            [[ "$*" == *"$REMOTE_JAR_DIR/$RUNTIME_JAR_NAME"* ]] \
                || fail "production no-op checked the upload artifact instead of the runtime JAR"
            printf '%s\n' "${MOCK_DISK_MD5:-$LOCAL_MD5}"
            ;;
        *"cat '$NGINX_UPSTREAM_FILE'"*) printf 'upstream x { server 47.100.235.168:%s; }\n' "$MOCK_UPSTREAM_PORT" ;;
        # ⚠️ MainPID 这条必须排在通用 *systemctl* 之前 —— 否则会被那条吃掉,
        #    返回 "active\n200" 当成 pid, 测试会以错误的理由通过/失败。
        *"MainPID"*) printf '%s\n' "$MOCK_MAIN_PID" ;;
        *systemctl*) printf '%s\n%s' "$MOCK_SERVICE_STATE" "$MOCK_HTTP_STATUS" ;;
        *) return 1 ;;
    esac
}
prod_already_runs_local_artifact "$LOCAL_MD5" || fail "healthy identical production artifact was not a no-op"
if prod_already_runs_local_artifact ffffffffffffffffffffffffffffffff; then
    fail "remote MD5 mismatch was accepted"
fi
if FORCE_REDEPLOY=1 prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "FORCE_REDEPLOY=1 did not bypass no-op"
fi

# 🔴 2026-08-20 事故用例: jar MD5 一致 + upstream 对 + systemd active + health 200
# 四条全绿, 而 JVM 仍跑着旧字节码 (上次部署装了 jar 但切流失败没重启)。
# 🔴 2026-10-28 同形复发: 当时靠「/proc/<pid> mtime >= jar mtime」拦, 但那个 mtime 是条目首次
# 被读的时间 —— 盘上 jar 14:40 是新的, 13:10 的进程握着已删除的旧 jar, 仍判成 no-op。
# 现在逐个读进程打开的 jar 句柄。实测当时的句柄形状: 两个已删除的旧 jar + 一个新 jar。
MOCK_OPEN_JARS=$(printf '%s deleted\n%s deleted\n%s live' "$OLD_MD5" "$OLD_MD5" "$LOCAL_MD5")
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "process still holding the deleted old JAR (new JAR on disk) was accepted as an already-deployed no-op"
fi
MOCK_OPEN_JARS="$OLD_MD5 deleted"
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "process running only a deleted old JAR was accepted as an already-deployed no-op"
fi
# 已删除但字节恰好相同也不放行: 判据是「进程打开的就是盘上这一份」。
MOCK_OPEN_JARS="$LOCAL_MD5 deleted"
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "deleted JAR handle was accepted as an already-deployed no-op"
fi
MOCK_OPEN_JARS="$OLD_MD5 live"
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "process holding different JAR bytes was accepted as an already-deployed no-op"
fi

# 读不到句柄要走正常部署, ⛔ 不能默认放行(「读不到」不等于「确认一致」)。
MOCK_OPEN_JARS=
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "unreadable open-JAR handles were treated as an already-deployed no-op"
fi
# ssh 中途断开: 只收到「<本地> live」, 后面的 deleted 行和 END 都没到。生产脚本没开 pipefail,
# 退出码看不出来, 只能靠 END 行。
MOCK_OPEN_JARS="$LOCAL_MD5 live"
MOCK_TRUNCATED=1
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "truncated handle list (no END line) was treated as an already-deployed no-op"
fi
MOCK_TRUNCATED=
MOCK_OPEN_JARS="$LOCAL_MD5 live"

# 句柄判定在远端执行, 上面的 mock 直接给成品行, 测不到「(deleted) 认成 deleted」和「按句柄读字节」。
# 这里把远端命令原样跑在假的 fd 目录上 (符号链接, 与 /proc/<pid>/fd 同形)。
# 已删除句柄的字节故意与新 jar 相同 —— 只有「它是 deleted」这一条能把它拦下来。
FAKE_ROOT=$(mktemp -d)
mkdir -p "$FAKE_ROOT/jar" "$FAKE_ROOT/fd"
printf 'new-jar-bytes' > "$FAKE_ROOT/jar/$RUNTIME_JAR_NAME"
printf 'new-jar-bytes' > "$FAKE_ROOT/jar/$RUNTIME_JAR_NAME (deleted)"
ln -s /dev/null "$FAKE_ROOT/fd/0"
ln -s "$FAKE_ROOT/jar/$RUNTIME_JAR_NAME (deleted)" "$FAKE_ROOT/fd/4"
ln -s "$FAKE_ROOT/jar/$RUNTIME_JAR_NAME" "$FAKE_ROOT/fd/25"
[ "$(readlink "$FAKE_ROOT/fd/4")" = "$FAKE_ROOT/jar/$RUNTIME_JAR_NAME (deleted)" ] \
    || fail "test setup: this shell cannot create real symlinks (on Git Bash set MSYS=winsymlinks:nativestrict)"
SAVED_REMOTE_JAR_DIR=$REMOTE_JAR_DIR
REMOTE_JAR_DIR="$FAKE_ROOT/jar"
MOCK_REMOTE_FD_DIR="$FAKE_ROOT/fd"
NEW_MD5=$(printf 'new-jar-bytes' | md5sum | awk '{print $1}')
MOCK_DISK_MD5=$NEW_MD5
if prod_already_runs_local_artifact "$NEW_MD5"; then
    fail "real handle scan: a deleted runtime JAR handle next to the new one was accepted as an already-deployed no-op"
fi
rm "$FAKE_ROOT/fd/4"
prod_already_runs_local_artifact "$NEW_MD5" \
    || fail "real handle scan positive control: only the live new JAR is open, should be a no-op"
printf 'old-jar-bytes' > "$FAKE_ROOT/jar/$RUNTIME_JAR_NAME"
if prod_already_runs_local_artifact "$NEW_MD5"; then
    fail "real handle scan: open JAR bytes differ from the local JAR but were accepted as a no-op"
fi
REMOTE_JAR_DIR=$SAVED_REMOTE_JAR_DIR
MOCK_REMOTE_FD_DIR=
MOCK_DISK_MD5=
rm -rf "$FAKE_ROOT"

MOCK_MAIN_PID=0
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "MainPID=0 (service not running) was treated as an already-deployed no-op"
fi

# 复位, 并确认阳性对照仍然成立 —— ⛔ 否则上面几条「不通过」可能只是因为我把 mock 弄坏了。
MOCK_MAIN_PID=4242
MOCK_OPEN_JARS="$LOCAL_MD5 live"
prod_already_runs_local_artifact "$LOCAL_MD5" \
    || fail "positive control broken: healthy identical artifact should still be a no-op"
MOCK_UPSTREAM_PORT=10030
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "unknown upstream port was accepted"
fi
MOCK_UPSTREAM_PORT=10020
MOCK_SERVICE_STATE=inactive
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "inactive systemd unit was accepted"
fi
MOCK_SERVICE_STATE=active
MOCK_HTTP_STATUS=503
if prod_already_runs_local_artifact "$LOCAL_MD5"; then
    fail "unhealthy active slot was accepted"
fi

grep -Fq 'if reuse_local_source_artifact_cache "$JAR_TARGET"; then' "$DEPLOY_SCRIPT" \
    || fail "source cache is not wired before Maven"
grep -Fq 'store_local_source_artifact_cache "$JAR_PATH"' "$DEPLOY_SCRIPT" \
    || fail "verified JAR is not persisted"
grep -Fq 'SKIP_BUILD=1 但可信 manifest 未命中；安全回退本地 clean package' "$DEPLOY_SCRIPT" \
    || fail "SKIP_BUILD manifest miss does not fall back to clean package"
grep -Fq '[ "${ENABLE_CI_ARTIFACT_REUSE:-0}" != "1" ]' "$DEPLOY_SCRIPT" \
    || fail "CI artifact fallback is not explicit opt-in"
grep -Fq 'prod_already_runs_local_artifact "$LOCAL_MD5"' "$DEPLOY_SCRIPT" \
    || fail "identical production no-op is not wired"

echo "PASS: backend tree cache and healthy identical-production no-op"
