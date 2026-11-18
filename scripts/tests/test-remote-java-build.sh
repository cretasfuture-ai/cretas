#!/usr/bin/env bash
# 远程构建车道 (canada-mini) 的契约:
#   1. CRETAS_RELEASE_REMOTE_BUILD_HOST 非空时 release_manifest_build 走远程, 本机 Maven 一次都不跑;
#      manifest 照常由本机写, maven_command 带 remote:<host> 前缀、jdk_* 记远端 JDK、sha 与 JAR 一致
#   2. 远端拒绝 / 失败 / 报的 sha 与取回的 JAR 不符 ⇒ 构建失败、不写 manifest、⛔ 不静默回退本机 Maven
#   3. 47 上的受限接收端 cretas-stage-receive: 只认 has/stage/bench, 字节数 / sha256 / unzip 三道校验,
#      注入与路径穿越被拒, 失败不留临时文件
#   4. 跑在有人使用的 mini 桌面上的 worker 不调用 /usr/bin/java、open、osascript
#   5. 驱动脚本拒绝能逃出远端单引号的测试选择器
# GUARDS=scripts/deploy/release-jar-manifest.sh,scripts/deploy/remote-java-build.sh,scripts/deploy/remote-build/remote-java-build-worker.sh,scripts/deploy/ecs/cretas-stage-receive,scripts/deploy/release-cretas.sh
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
HELPER="$ROOT_DIR/scripts/deploy/release-jar-manifest.sh"
RECEIVER="$ROOT_DIR/scripts/deploy/ecs/cretas-stage-receive"
WORKER="$ROOT_DIR/scripts/deploy/remote-build/remote-java-build-worker.sh"
DRIVER="$ROOT_DIR/scripts/deploy/remote-java-build.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
checks=0
pass() { checks=$((checks + 1)); echo "ok - $*"; }

command -v jar >/dev/null 2>&1 || { echo "SKIPPED: jar (JDK) not on PATH; cannot build fixture JARs"; exit 77; }

source "$HELPER"

# ── fixture repo (同 test-release-jar-manifest.sh 的形状) ─────────────────────
FIXTURE_REPO="$TMP_ROOT/repo"
FIXTURE_HELPER="$FIXTURE_REPO/scripts/deploy/release-jar-manifest.sh"
mkdir -p "$FIXTURE_REPO/backend/java/cretas-api" "$(dirname "$FIXTURE_HELPER")"
cp "$HELPER" "$FIXTURE_HELPER"
(
    cd "$FIXTURE_REPO"
    git init -q -b main
    git config user.name test
    git config user.email test@example.com
    printf '<project/>\n' > backend/java/cretas-api/pom.xml
    printf 'backend/java/cretas-api/target/\n' > .gitignore
    git add .gitignore backend/java/cretas-api/pom.xml scripts/deploy/release-jar-manifest.sh
    git commit -qm backend
)
MANIFEST="$TMP_ROOT/cache/current/$RELEASE_MANIFEST_NAME"
MVN_LOG="$TMP_ROOT/mvn.log"
FAKE_MVN="$TMP_ROOT/fake-mvn"
cat > "$FAKE_MVN" <<'EOF'
#!/usr/bin/env bash
printf 'LOCAL MAVEN RAN %s\n' "$*" >> "$MVN_LOG"
exit 1
EOF
chmod +x "$FAKE_MVN"

# 假远端: 按 FAKE_MODE 模拟 remote-java-build.sh 的接口 (参数 / 输出行 / 退出码)。
FAKE_REMOTE="$TMP_ROOT/fake-remote"
cat > "$FAKE_REMOTE" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_REMOTE_LOG"
pull_to=
while [ "$#" -gt 0 ]; do
    case "$1" in --pull-to) pull_to=$2; shift 2 ;; *) shift ;; esac
done
case "$FAKE_MODE" in
    refused) echo "REMOTE_BUILD_REFUSED reason=memory_low"; exit 3 ;;
    failed) echo "REMOTE_BUILD_FAILED reason=maven_exit_1"; exit 1 ;;
esac
work=$(mktemp -d)
printf 'built on mini\n' > "$work/Main.class"
(cd "$work" && jar --create --file remote.jar Main.class)
cp "$work/remote.jar" "$pull_to"
sha=$(sha256sum "$pull_to" | awk '{print $1}')
[ "$FAKE_MODE" != badsha ] || sha=$(printf '%064d' 0)
echo "REMOTE_JAR_PATH=/Users/rex/cretas-build/out/x.jar"
echo "REMOTE_JAR_SHA256=$sha"
echo "REMOTE_JDK_VENDOR=Azul Systems, Inc."
echo "REMOTE_JDK_VERSION=21.0.10"
echo "REMOTE_MAVEN_WALL_SECONDS=69"
echo "REMOTE_BUILD_OK=1"
echo "PULL_SHA256_VERIFIED=$sha"
EOF
chmod +x "$FAKE_REMOTE"
export MVN_LOG FAKE_REMOTE_LOG="$TMP_ROOT/remote.log"

run_build() {
    CRETAS_RELEASE_REMOTE_BUILD_HOST=fake-mini CRETAS_REMOTE_JAVA_BUILD_CMD="$FAKE_REMOTE" \
        CRETAS_MAVEN_WRAPPER="$FAKE_MVN" CRETAS_RELEASE_FORCE_JAVA_BUILD=1 \
        "$FIXTURE_HELPER" build --tests ReleaseDecisionToolTest --manifest "$MANIFEST"
}

# 1. 成功路径
FAKE_MODE=ok run_build > "$TMP_ROOT/ok.log" 2>&1 || { cat "$TMP_ROOT/ok.log"; fail "remote build (ok) failed"; }
[ ! -s "$MVN_LOG" ] || fail "local Maven ran in remote mode: $(cat "$MVN_LOG")"
grep -q -- '--host fake-mini' "$FAKE_REMOTE_LOG" || fail "remote command not invoked with the host"
grep -q -- '--tests ReleaseDecisionToolTest' "$FAKE_REMOTE_LOG" || fail "remote command lost the test selector"
grep -qF -- "--includes $(release_manifest_test_includes ReleaseDecisionToolTest)" "$FAKE_REMOTE_LOG" \
    || fail "remote command lost the test-compile narrowing"
grep -Fxq "maven_command=remote:fake-mini mvn clean package -Dtest=ReleaseDecisionToolTest -Dcretas.testIncludes=$(release_manifest_test_includes ReleaseDecisionToolTest)" "$MANIFEST" \
    || fail "manifest maven_command does not record the remote build ($(grep maven_command "$MANIFEST"))"
grep -Fxq 'jdk_vendor=Azul Systems, Inc.' "$MANIFEST" || fail "manifest jdk_vendor is not the remote JDK"
grep -Fxq 'jdk_version=21.0.10' "$MANIFEST" || fail "manifest jdk_version is not the remote JDK"
grep -Fxq 'maven_wall_seconds=69' "$MANIFEST" || fail "manifest lost the remote wall time"
grep -Fxq "build_commit=$(git -C "$FIXTURE_REPO" rev-parse HEAD)" "$MANIFEST" || fail "manifest build_commit wrong"
cached="$(dirname "$MANIFEST")/$RELEASE_JAR_NAME"
[ "$(sha256sum "$cached" | awk '{print $1}')" = "$(release_manifest_field "$MANIFEST" jar_sha256)" ] \
    || fail "cached JAR sha does not match manifest"
unzip -p "$cached" Main.class | grep -q 'built on mini' || fail "cached JAR is not the remotely built one"
ls -A "$(dirname "$MANIFEST")" | grep -q '^\.remote-pull' && fail "pulled temp JAR left in cache"
# 同一条 manifest 必须能被 deploy 侧 validate 接受 (门禁不因远程而变)。
git -C "$FIXTURE_REPO" update-ref refs/remotes/origin/main HEAD
release_manifest_validate "$MANIFEST" "$FIXTURE_REPO" "$TMP_ROOT/dest/$RELEASE_JAR_NAME" \
    || fail "deploy-side validate rejected a remotely built manifest"
pass "remote build writes a validate-able manifest and never runs local Maven"

# 2. 拒绝 / 失败 / sha 不符: 都必须失败, 不写 manifest, 不回退本机 Maven
for mode in refused failed badsha; do
    rm -f "$MANIFEST" "$cached" "$MVN_LOG"
    if FAKE_MODE=$mode run_build > "$TMP_ROOT/$mode.log" 2>&1; then
        cat "$TMP_ROOT/$mode.log"; fail "remote build mode=$mode was reported as success"
    fi
    [ ! -f "$MANIFEST" ] || fail "mode=$mode still wrote a manifest"
    [ ! -s "$MVN_LOG" ] || fail "mode=$mode silently fell back to local Maven"
    ls -A "$(dirname "$MANIFEST")" 2>/dev/null | grep -q '^\.remote-pull' && fail "mode=$mode left a temp JAR"
    pass "remote mode=$mode fails closed without local fallback"
done
grep -q 'not falling back to local Maven' "$TMP_ROOT/refused.log" || fail "refusal message does not say there is no fallback"

# 3. 受限接收端
RC="$TMP_ROOT/rc"; mkdir -p "$RC/cache" "$RC/w"
printf 'hi\n' > "$RC/w/a.txt"; (cd "$RC/w" && jar --create --file t.jar a.txt)
J="$RC/w/t.jar"; S=$(sha256sum "$J" | awk '{print $1}'); N=$(wc -c < "$J" | tr -d '[:space:]')
printf 'notzip' > "$RC/w/nz"; NZS=$(sha256sum "$RC/w/nz" | awk '{print $1}')
recv() { CRETAS_REMOTE_JAR_CACHE_DIR="$RC/cache" CRETAS_STAGE_RECEIVE_LOG="$RC/log" SSH_ORIGINAL_COMMAND="$1" bash "$RECEIVER"; }
expect_rc() {
    local want=$1 label=$2 cmd=$3 input=${4:-/dev/null} got
    set +e; recv "$cmd" < "$input" > /dev/null 2>&1; got=$?; set -e
    [ "$got" = "$want" ] || fail "receiver $label: rc=$got want=$want"
}
expect_rc 1 "has (absent)" "has $S"
expect_rc 1 "wrong sha" "stage $(printf '%064d' 7) $N" "$J"
expect_rc 1 "wrong size" "stage $S $((N - 1))" "$J"
expect_rc 1 "not a zip" "stage $NZS 6" "$RC/w/nz"
[ -z "$(ls -A "$RC/cache")" ] || fail "receiver left files after rejected uploads: $(ls -A "$RC/cache")"
expect_rc 2 "shell command" "bash -i"
expect_rc 2 "command injection" "stage $S $N; rm -rf /" "$J"
expect_rc 2 "path traversal" "has ../../etc/passwd"
expect_rc 2 "empty command" ""
expect_rc 0 "stage" "stage $S $N" "$J"
[ -f "$RC/cache/$S.jar" ] || fail "receiver did not place the JAR at <sha>.jar"
[ "$(stat -c %a "$RC/cache/$S.jar")" = 444 ] || fail "staged JAR is not 0444"
expect_rc 0 "has (present)" "has $S"
expect_rc 0 "stage (idempotent)" "stage $S $N" "$J"
grep -q "STAGED sha=$S" "$RC/log" || fail "receiver did not log the staging"
pass "stage receiver accepts only verified JARs and refuses everything else"

# 4. 桌面零弹窗: worker 的可执行行里不许出现 GUI 入口 / macOS java stub
if grep -vE '^[[:space:]]*#' "$WORKER" | grep -nE '(^|[^A-Za-z0-9_/.-])(osascript|open)([[:space:]]|$)|/usr/bin/java'; then
    fail "worker invokes a GUI entry or the /usr/bin/java stub"
fi
grep -q 'export JAVA_HOME="$JDK"' "$WORKER" || fail "worker does not pin JAVA_HOME to its own JDK"
grep -q 'nice -n 10' "$WORKER" || fail "worker lost its nice priority"
grep -q 'MAVEN_OPTS="-Xmx4g"' "$WORKER" || fail "worker lost the Maven heap cap"
grep -q 'REMOTE_BUILD_REFUSED reason' "$WORKER" && grep -q 'time_window' "$WORKER" && grep -q 'memory_unreadable' "$WORKER" \
    || fail "worker lost the time-window / memory refusal"
pass "worker keeps the desktop-safety and resource limits"

# 5. 驱动脚本拒绝能逃出单引号的选择器 (在连 ssh 之前)
set +e
# 主机名故意不可解析: 即使守卫被改坏, 注入也到不了任何真机器 (2026-11-18 变异验证时它真的在 mini 上执行过)。
"$DRIVER" --host no-such-host.invalid --tests "Foo'; touch /tmp/pwned; '" --pull-to "$TMP_ROOT/x.jar" > /dev/null 2>&1
drc=$?
set -e
[ "$drc" = 2 ] || fail "driver accepted a quote-escaping selector (rc=$drc)"
pass "driver rejects selectors that escape remote quoting"

# 6. 统一入口导出开关
grep -q 'export CRETAS_RELEASE_REMOTE_BUILD_HOST=\$REMOTE_BUILD_HOST' "$ROOT_DIR/scripts/deploy/release-cretas.sh" \
    || fail "release-cretas.sh --remote-build is not exported to the Java build"
pass "release-cretas.sh exports --remote-build"

[ "$checks" = 8 ] || fail "expected 8 checks, ran $checks"
echo "PASS: remote Java build lane ($checks checks)"
