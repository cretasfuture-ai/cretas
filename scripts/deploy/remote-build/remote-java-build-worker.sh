#!/usr/bin/env bash
# 在远程构建机 (canada-mini) 上跑【一次】Cretas Java 发布构建。
#
# 由 scripts/deploy/remote-java-build.sh 每次先上传到 mini 的 ~/cretas-build/worker-<sha256>.sh
# 再执行, 本文件不需要预先装在 mini 上 —— 每次跑的都是发起发布那棵树里的版本, 不会漂。
# ⛔ 不用 `ssh host bash -s < 本文件`: 脚本里的 ssh / git / mvn 会从 stdin 读走剩下的脚本正文。
#
# mini 是有人在用的桌面机, 所以这里有几条硬约束 (2026-11-18 Steve / 降本线 organizer):
#   - 只用自己装的 JDK 绝对路径。⛔ 不调用 /usr/bin/java: 那是 macOS stub, 没装 Java 时
#     会在桌面弹「需要安装 Java」对话框。PATH 里也把它挡掉。
#   - 不跑 open / osascript / 任何 GUI。
#   - 资源上限: nice 10、Maven -T 1C、Maven 自身 -Xmx4g、测试 fork 经 JAVA_TOOL_OPTIONS -Xmx3g
#     (pom 的 <argLine> 不动, 免得与 Windows 构建的测试 JVM 参数分叉)。
#   - 一次只跑一个构建 (mkdir 锁)。
#   - 北京时间 23:30–00:30 不跑 (黑湖夜间分析 ~23:55 加载 27B 模型, 内存余量最小)。
#   - memory_pressure 空闲 < 25% 不跑; 【读不到也不跑】—— 量不到不等于有余量。
#
# 输出: 成功时 stdout 末尾打一组 REMOTE_*=... 行, 由调用方解析。
# 退出码: 0 成功 / 1 构建或校验失败 / 3 前置条件拒绝 (时间窗 / 内存 / 锁 / 提交不在 origin)
set -uo pipefail

COMMIT=${1:-}
BACKEND_TREE=${2:-}
TARGET_TESTS=${3:-}
TEST_INCLUDES=${4:-}
STAGE_FROM_REMOTE=${5:-0}

ROOT=${CRETAS_RB_ROOT:-$HOME/cretas-build}
REPO="$ROOT/repo"
JDK=${CRETAS_RB_JDK:-$HOME/Tools/jdk21}
MVN=${CRETAS_RB_MVN:-$HOME/Tools/maven/bin/mvn}
SETTINGS=${CRETAS_RB_SETTINGS:-$ROOT/maven-settings.xml}
MIN_FREE_PCT=${CRETAS_RB_MIN_FREE_PCT:-25}
REPO_URL=${CRETAS_RB_REPO_URL:-https://github.com/cretasfuture-ai/cretas.git}
STAGE_KEY=${CRETAS_RB_STAGE_KEY:-$HOME/.ssh/cretas_remote_build_47_ed25519}
STAGE_KNOWN_HOSTS=${CRETAS_RB_STAGE_KNOWN_HOSTS:-$ROOT/known_hosts_47}
STAGE_SERVER=${CRETAS_RB_STAGE_SERVER:-root@47.100.235.168}
BACKEND_PATH=backend/java/cretas-api
JAR_NAME=cretas-backend-system-1.0.0.jar
SPARSE_DIRS=(backend/java/cretas-api backend/python/common)

# /usr/bin 在 PATH 里排在自己的 JDK 之后, 且 JAVA_HOME 必设; 任何 `java` 都先命中自己的 JDK。
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

refuse() {
    printf 'REMOTE_BUILD_REFUSED reason=%s\n' "$1"
    shift
    [ $# -eq 0 ] || printf '%s\n' "$*" >&2
    exit 3
}

fail() {
    printf 'REMOTE_BUILD_FAILED reason=%s\n' "$1"
    exit 1
}

is_hex() {
    [ "${#1}" = "$2" ] || return 1
    case "$1" in *[!0123456789abcdef]*) return 1 ;; esac
}

mem_free_pct() {
    # 只认 memory_pressure 的那一行; 解析不出来返回空串 (调用方按「没量到」拒绝)。
    /usr/bin/memory_pressure 2>/dev/null \
        | sed -nE 's/.*free percentage: ([0-9]+)%.*/\1/p' | head -1
}

is_hex "$COMMIT" 40 || refuse bad_args "commit must be 40 lowercase hex"
is_hex "$BACKEND_TREE" 40 || refuse bad_args "backend tree must be 40 lowercase hex"
[ -n "$TARGET_TESTS" ] && [ -n "$TEST_INCLUDES" ] || refuse bad_args "tests/includes required"
[ -x "$JDK/bin/java" ] || refuse jdk_missing "$JDK/bin/java not executable"
[ -x "$MVN" ] || refuse maven_missing "$MVN not executable"
[ -f "$SETTINGS" ] || refuse settings_missing "$SETTINGS"

# 时间窗: 北京时间 23:30–00:29 拒绝。
hm=$(TZ=Asia/Shanghai date +%H%M)
case "$hm" in ''|*[!0-9]*) refuse clock_unreadable ;; esac
if [ "$hm" -ge 2330 ] || [ "$hm" -lt 0030 ]; then
    refuse time_window "Asia/Shanghai $hm is inside 23:30-00:30 (27B model load window)"
fi

free_pct=$(mem_free_pct)
[ -n "$free_pct" ] || refuse memory_unreadable "memory_pressure gave no free percentage"
[ "$free_pct" -ge "$MIN_FREE_PCT" ] || refuse memory_low "free ${free_pct}% < ${MIN_FREE_PCT}%"

mkdir -p "$ROOT/logs" "$ROOT/out" || fail mkdir
LOCK="$ROOT/.build.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    holder=$(cat "$LOCK/pid" 2>/dev/null || true)
    if [ -n "$holder" ] && kill -0 "$holder" 2>/dev/null; then
        refuse busy "another remote build is running (pid $holder)"
    fi
    # 持锁进程已不在 (被 kill / ssh 断开): 回收孤儿锁。
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || refuse busy "lock race"
fi
echo $$ > "$LOCK/pid"
SAMPLER=
cleanup() {
    [ -z "$SAMPLER" ] || kill "$SAMPLER" 2>/dev/null
    rm -rf "$LOCK"
}
trap cleanup EXIT

# 源码: blobless + sparse 的一份长驻 clone, 每次按提交 SHA 精确取。提交必须已推到 origin
# (PR head 或 main), 否则拒绝 —— mini 只构建 GitHub 上可审计的那棵树。
if [ ! -d "$REPO/.git" ]; then
    git clone -q --filter=blob:none --no-checkout "$REPO_URL" "$REPO" || fail clone
fi
git -C "$REPO" sparse-checkout set --cone "${SPARSE_DIRS[@]}" || fail sparse_checkout
git -C "$REPO" fetch -q --filter=blob:none origin "$COMMIT" 2>/dev/null \
    || refuse commit_not_on_origin "git fetch origin $COMMIT failed (push the candidate first)"
git -C "$REPO" checkout -q --force --detach "$COMMIT" || fail checkout
[ "$(git -C "$REPO" rev-parse HEAD)" = "$COMMIT" ] || fail head_mismatch
remote_tree=$(git -C "$REPO" rev-parse "HEAD:$BACKEND_PATH") || fail tree_unreadable
[ "$remote_tree" = "$BACKEND_TREE" ] || fail "tree_mismatch remote=$remote_tree local=$BACKEND_TREE"
git -C "$REPO" clean -qfdx -- "$BACKEND_PATH" || fail clean
[ -z "$(git -C "$REPO" status --porcelain --untracked-files=normal)" ] || fail remote_worktree_dirty

LOGF="$ROOT/logs/build-$COMMIT.log"
( while :; do mem_free_pct; sleep 10; done ) > "$ROOT/logs/mem-$COMMIT.log" 2>/dev/null &
SAMPLER=$!

started=$(date +%s)
(
    cd "$REPO/$BACKEND_PATH" || exit 1
    MAVEN_OPTS="-Xmx4g" JAVA_TOOL_OPTIONS="-Xmx3g" nice -n 10 "$MVN" -B -T 1C -s "$SETTINGS" \
        clean package "-Dtest=$TARGET_TESTS" "-Dcretas.testIncludes=$TEST_INCLUDES"
) < /dev/null > "$LOGF" 2>&1
mvn_rc=$?
wall=$(( $(date +%s) - started ))
kill "$SAMPLER" 2>/dev/null; wait "$SAMPLER" 2>/dev/null; SAMPLER=
free_min=$(sort -n "$ROOT/logs/mem-$COMMIT.log" | head -1)

if [ "$mvn_rc" -ne 0 ]; then
    tail -60 "$LOGF" >&2
    fail "maven_exit_$mvn_rc log=$LOGF"
fi
# 构建期间工作区不许变 (与 release_manifest_build 的本地闸同义)。
[ -z "$(git -C "$REPO" status --porcelain --untracked-files=normal)" ] || fail remote_worktree_changed

jar="$REPO/$BACKEND_PATH/target/$JAR_NAME"
[ -f "$jar" ] || fail jar_missing
/usr/bin/unzip -tqq "$jar" >/dev/null 2>&1 || fail jar_corrupt
out="$ROOT/out/$COMMIT.jar"
cp -f "$jar" "$out.tmp" && mv -f "$out.tmp" "$out" || fail copy_out
# 只留最近 3 份产物。
ls -t "$ROOT"/out/*.jar 2>/dev/null | tail -n +4 | while read -r old; do rm -f "$old"; done
sha=$(/usr/bin/shasum -a 256 "$out" | awk '{print $1}')
size=$(wc -c < "$out" | tr -d '[:space:]')
props=$("$JDK/bin/java" -XshowSettings:properties -version 2>&1)
vendor=$(printf '%s\n' "$props" | sed -nE 's/^[[:space:]]*java\.vendor[[:space:]]*=[[:space:]]*//p' | head -1)
version=$(printf '%s\n' "$props" | sed -nE 's/^[[:space:]]*java\.version[[:space:]]*=[[:space:]]*//p' | head -1)

stage_status=not-requested
if [ "$STAGE_FROM_REMOTE" = 1 ]; then
    SO=(-o BatchMode=yes -o IdentitiesOnly=yes -o "IdentityFile=$STAGE_KEY"
        -o "UserKnownHostsFile=$STAGE_KNOWN_HOSTS" -o StrictHostKeyChecking=yes
        -o ConnectTimeout=10 -o Compression=no)
    if ssh "${SO[@]}" "$STAGE_SERVER" "has $sha" >/dev/null 2>&1; then
        stage_status=already
    else
        st=$(date +%s)
        if ssh "${SO[@]}" "$STAGE_SERVER" "stage $sha $size" < "$out" >&2; then
            stage_status="staged:$(( $(date +%s) - st ))s"
        else
            stage_status=failed
        fi
    fi
fi

printf 'REMOTE_JAR_PATH=%s\n' "$out"
printf 'REMOTE_JAR_SHA256=%s\n' "$sha"
printf 'REMOTE_JAR_SIZE=%s\n' "$size"
printf 'REMOTE_JDK_VENDOR=%s\n' "$vendor"
printf 'REMOTE_JDK_VERSION=%s\n' "$version"
printf 'REMOTE_MAVEN_WALL_SECONDS=%s\n' "$wall"
printf 'REMOTE_MEM_FREE_PCT_START=%s\n' "$free_pct"
printf 'REMOTE_MEM_FREE_PCT_MIN=%s\n' "${free_min:-unknown}"
printf 'REMOTE_STAGE=%s\n' "$stage_status"
printf 'REMOTE_BUILD_OK=1\n'
[ "$stage_status" != failed ] || exit 1
exit 0
