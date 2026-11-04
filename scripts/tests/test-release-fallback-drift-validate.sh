#!/usr/bin/env bash
# SCRIPT_TEST_TIMEOUT=600
# 回退构建期间 origin/main 前进时, 构建后的 manifest validate 不许无声退出。
#
# 2026-11-04 发 #1417: 构建 8 分钟、211 个测试全过后 DEPLOY_EXIT=1, 日志没有 ERROR,
# 没有 RELEASE_FINAL_STATUS, 回执 main_guard=passed。真因是 *_fallback_build 末尾的
# validate 要求 HEAD == origin/main, 不等就 return 1, 走不到 recover_from_main_drift。
# 本机所有 worktree 共用一个 .git, 所以别的会话一次 git fetch 就足以让 origin/main
# 在构建中途变掉。
#
# test-release-cretas.sh 的 fixture 把 validate 整个换成了桩(只按次数返回 rc), 所以它
# 永远看不到这条路径 —— 这里的 fixture 只桩 build, validate 跑的是【真脚本】。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    [ ! -f "${CASE_OUTPUT:-}" ] || sed 's/^/  | /' "$CASE_OUTPUT" >&2
    exit 1
}

assert_contains() {
    local path=$1 text=$2
    grep -Fq -- "$text" "$path" || fail "$path does not contain: $text"
}

assert_count() {
    local expected=$1 text=$2 path=$3 actual
    actual=$(grep -Fc -- "$text" "$path" 2>/dev/null || true)
    [ "$actual" -eq "$expected" ] || fail "expected $expected x '$text' in $path, got $actual"
}

FIXTURE_JAR="$TMP_ROOT/fixture.jar"
mkdir -p "$TMP_ROOT/jar-content"
printf 'fixture\n' >"$TMP_ROOT/jar-content/Main.class"
(cd "$TMP_ROOT/jar-content" && jar --create --file "$FIXTURE_JAR" Main.class)

FIXTURE_DIST="$TMP_ROOT/dist"
mkdir -p "$FIXTURE_DIST/assets"
printf '<script type="module" src="/assets/index-test.js"></script>\n' >"$FIXTURE_DIST/index.html"
printf 'console.log("fixture")\n' >"$FIXTURE_DIST/assets/index-test.js"

setup_repo() {
    local name=$1
    printf 'CASE: %s\n' "$name"
    CASE_ROOT="$TMP_ROOT/$name"
    CASE_REPO="$CASE_ROOT/repo"
    CASE_REMOTE="$CASE_ROOT/origin.git"
    CASE_HOME="$CASE_ROOT/home"
    CASE_LOG="$CASE_ROOT/calls.log"
    CASE_REPORT="$CASE_ROOT/report.json"
    CASE_OUTPUT="$CASE_ROOT/output.log"
    local deploy_dir="$CASE_REPO/scripts/deploy"
    mkdir -p "$deploy_dir" "$CASE_REPO/scripts/lib" "$CASE_REPO/backend/java/cretas-api" \
        "$CASE_REPO/web-admin" "$CASE_HOME"
    cp "$ROOT_DIR/scripts/deploy/release-cretas.sh" "$deploy_dir/release-cretas.sh"
    cp "$ROOT_DIR/scripts/deploy/release-jar-manifest.sh" "$deploy_dir/real-jar-manifest.sh"
    cp "$ROOT_DIR/scripts/deploy/release-web-manifest.sh" "$deploy_dir/real-web-manifest.sh"
    printf 'java baseline\n' >"$CASE_REPO/backend/java/cretas-api/baseline.txt"
    printf 'web baseline\n' >"$CASE_REPO/web-admin/baseline.txt"
    printf '{}\n' >"$CASE_REPO/web-admin/package-lock.json"

    cat >"$CASE_REPO/scripts/lib/deploy-common.sh" <<'EOF'
#!/usr/bin/env bash
acquire_deploy_lock() { return 0; }
release_deploy_lock() { return 0; }
EOF
    # 模拟"别的会话把 main 推走, 并在共用的 .git 里 fetch 了": 远端和本地 origin/main 同时变。
    # 只触发一次 —— 重新执行后的入口不该再被它推一下。
    cat >"$deploy_dir/mock-move-main.sh" <<'EOF'
#!/usr/bin/env bash
[ -n "${MOCK_DRIFT_SHA:-}" ] || exit 0
[ ! -f "$MOCK_COUNTER_ROOT/drifted" ] || exit 0
: >"$MOCK_COUNTER_ROOT/drifted"
git --git-dir="$MOCK_REMOTE_PATH" update-ref refs/heads/main "$MOCK_DRIFT_SHA"
git fetch -q origin main
EOF
    cat >"$deploy_dir/release-jar-manifest.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$here/real-jar-manifest.sh"
if [ "${1:-}" = build ]; then
    printf 'JAVA_BUILD\n' >>"$MOCK_CALL_LOG"
    manifest=$(release_manifest_default_path)
    mkdir -p "$(dirname "$manifest")"
    cp "$MOCK_FIXTURE_JAR" "$(dirname "$manifest")/$RELEASE_JAR_NAME"
    sha=$(sha256sum "$MOCK_FIXTURE_JAR" | awk '{print $1}')
    [ "${MOCK_CORRUPT_JAR_SHA:-0}" != 1 ] || sha=$(printf 'x' | sha256sum | awk '{print $1}')
    {
        printf 'format=%s\nsuccess=true\n' "$RELEASE_MANIFEST_FORMAT"
        printf 'build_commit=%s\n' "$(git rev-parse HEAD)"
        printf 'backend_tree=%s\n' "$(git rev-parse "HEAD:$RELEASE_BACKEND_PATH")"
        printf 'jar_sha256=%s\njar_path=%s\n' "$sha" "$RELEASE_JAR_NAME"
        printf 'jdk_vendor=fixture\njdk_version=21\nmaven_command=mvn\ntarget_tests=StartupTest\n'
    } >"$manifest"
    [ -n "${MOCK_BUILD_BREAKS_FETCH:-}" ] && git remote set-url origin "$MOCK_BUILD_BREAKS_FETCH"
    [ "${MOCK_DRIFT_ON:-build}" != build ] || "$here/mock-move-main.sh"
    exit 0
fi
counter="$MOCK_COUNTER_ROOT/java-validate"
count=0; [ ! -f "$counter" ] || count=$(cat "$counter")
count=$((count + 1)); printf '%s\n' "$count" >"$counter"
[ "${MOCK_DRIFT_ON:-build}" != "validate-$count" ] || "$here/mock-move-main.sh"
release_manifest_main "$@"
EOF
    cat >"$deploy_dir/release-web-manifest.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$here/real-web-manifest.sh"
if [ "${1:-}" = build ]; then
    printf 'WEB_BUILD\n' >>"$MOCK_CALL_LOG"
    manifest=$(web_release_default_manifest)
    mkdir -p "$(dirname "$manifest")"
    archive="$(dirname "$manifest")/$WEB_RELEASE_ARCHIVE_NAME"
    tar czf "$archive" -C "$MOCK_FIXTURE_DIST" .
    {
        printf 'format=%s\nsuccess=true\n' "$WEB_RELEASE_MANIFEST_FORMAT"
        printf 'build_commit=%s\n' "$(git rev-parse HEAD)"
        printf 'web_tree=%s\n' "$(git rev-parse "HEAD:$WEB_RELEASE_SOURCE_PATH")"
        printf 'archive_path=%s\n' "$WEB_RELEASE_ARCHIVE_NAME"
        printf 'archive_sha256=%s\n' "$(sha256sum "$archive" | awk '{print $1}')"
        printf 'index_sha256=%s\n' "$(sha256sum "$MOCK_FIXTURE_DIST/index.html" | awk '{print $1}')"
        printf 'asset_count=1\n'
        printf 'package_lock_sha256=%s\n' "$(sha256sum web-admin/package-lock.json | awk '{print $1}')"
        printf 'node_version=v20\nnpm_version=10\nbuild_command=npm run build\n'
    } >"$manifest"
    [ "${MOCK_DRIFT_ON:-build}" != build ] || "$here/mock-move-main.sh"
    exit 0
fi
web_release_main "$@"
EOF
    cat >"$deploy_dir/release-cretas-artifacts.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
"$here/release-jar-manifest.sh" build
"$here/release-web-manifest.sh" build
EOF
    cat >"$deploy_dir/release-java-preflight.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    cat >"$deploy_dir/release-ci-artifact.sh" <<'EOF'
#!/usr/bin/env bash
echo "CI_ARTIFACT_UNAVAILABLE reason=mock_no_artifact" >&2
exit 1
EOF
    cat >"$deploy_dir/deploy-backend.sh" <<'EOF'
#!/usr/bin/env bash
printf 'JAVA_DEPLOY\n' >>"$MOCK_CALL_LOG"
printf '{"result":"SUCCESS","outcome":"deployed","exit_code":0}\n' >"$CRETAS_DEPLOY_REPORT_PATH"
EOF
    cat >"$deploy_dir/deploy-web-admin.sh" <<'EOF'
#!/usr/bin/env bash
printf 'WEB_DEPLOY\n' >>"$MOCK_CALL_LOG"
printf '{"result":"SUCCESS","outcome":"deployed","exit_code":0}\n' >"$CRETAS_WEB_DEPLOY_REPORT_PATH"
EOF
    cat >"$deploy_dir/verify-release.sh" <<'EOF'
#!/usr/bin/env bash
printf 'BACKEND_HEALTH=pass\nWEB_HTTP=200\n'
EOF
    chmod +x "$deploy_dir/"*.sh
    git -C "$CASE_REPO" init -q -b main
    git -C "$CASE_REPO" config user.email fixture@example.com
    git -C "$CASE_REPO" config user.name Fixture
    git -C "$CASE_REPO" add .
    git -C "$CASE_REPO" commit -qm baseline
    git init --bare -q "$CASE_REMOTE"
    git -C "$CASE_REPO" remote add origin "$CASE_REMOTE"
    git -C "$CASE_REPO" push -q -u origin main
    BASE_SHA=$(git -C "$CASE_REPO" rev-parse HEAD)
    : >"$CASE_LOG"
}

commit_change() {
    local path=$1
    mkdir -p "$(dirname "$CASE_REPO/$path")"
    printf 'change\n' >>"$CASE_REPO/$path"
    git -C "$CASE_REPO" add "$path"
    git -C "$CASE_REPO" commit -qm "change $path"
}

publish_head() {
    git -C "$CASE_REPO" push -q origin HEAD:main
    git -C "$CASE_REPO" fetch -q origin main
}

# 新 main = 本次 HEAD 之上再加一个 tree 不变的提交: 本次发布的提交在新 main 里。
make_descendant_main() {
    local tree
    tree=$(git -C "$CASE_REPO" rev-parse 'HEAD^{tree}')
    DRIFT_SHA=$(printf 'drift\n' | git -C "$CASE_REPO" commit-tree "$tree" -p HEAD)
    git -C "$CASE_REPO" push -q origin "$DRIFT_SHA:refs/heads/drift-fixture"
}

# 新 main 从本次提交之前分叉: 本次发布的提交【不在】新 main 里。
make_divergent_main() {
    local parent tree
    parent=$(git -C "$CASE_REPO" rev-parse HEAD~1)
    tree=$(git -C "$CASE_REPO" rev-parse "$parent^{tree}")
    DRIFT_SHA=$(printf 'divergent\n' | git -C "$CASE_REPO" commit-tree "$tree" -p "$parent")
    git -C "$CASE_REPO" push -q origin "$DRIFT_SHA:refs/heads/drift-fixture"
}

run_release() {
    (
        cd "$CASE_REPO"
        HOME="$CASE_HOME" \
        MOCK_CALL_LOG="$CASE_LOG" \
        MOCK_COUNTER_ROOT="$CASE_ROOT" \
        MOCK_REMOTE_PATH="$CASE_REMOTE" \
        MOCK_FIXTURE_JAR="$FIXTURE_JAR" \
        MOCK_FIXTURE_DIST="$FIXTURE_DIST" \
        CRETAS_RELEASE_FALLBACK_MAIN_GUARD_SECONDS=0 \
        CRETAS_RELEASE_REPORT_PATH="$CASE_REPORT" \
        bash scripts/deploy/release-cretas.sh --phase deploy --base-sha "$BASE_SHA" \
            --tests StartupTest --confirm-prod YES-PROD
    ) >"$CASE_OUTPUT" 2>&1
}

assert_recovered() {
    assert_contains "$CASE_OUTPUT" '本次发布的提交已确认在新 main 中'
    assert_count 1 'RELEASE_FINAL_STATUS=' "$CASE_OUTPUT"
    assert_contains "$CASE_OUTPUT" 'RELEASE_FINAL_STATUS=deployed'
    assert_contains "$CASE_REPORT" '"drift_recoveries": 1'
    assert_contains "$CASE_REPORT" '"main_guard": {"status": "passed"'
    [ "$(git -C "$CASE_REPO" rev-parse HEAD)" = "$DRIFT_SHA" ] \
        || fail 'recovery did not advance the release worktree to the new origin/main'
}

assert_refused() {
    assert_count 1 'RELEASE_FINAL_STATUS=failed' "$CASE_OUTPUT"
    assert_count 1 'RELEASE_FINAL_STATUS=' "$CASE_OUTPUT"
    assert_count 0 'JAVA_DEPLOY' "$CASE_LOG"
    assert_count 0 'WEB_DEPLOY' "$CASE_LOG"
}

# 真实事故形状: Java 回退构建期间 main 前进 → 自动前进, tree 没变所以不重建。
setup_repo java_drift_recovers
commit_change backend/java/cretas-api/Service.java
publish_head
make_descendant_main
MOCK_DRIFT_SHA=$DRIFT_SHA run_release \
    || fail 'Java fallback build did not recover from a main that contains the release commits'
assert_recovered
assert_count 1 'JAVA_BUILD' "$CASE_LOG"
assert_count 1 'JAVA_DEPLOY' "$CASE_LOG"

setup_repo web_drift_recovers
commit_change web-admin/src/app.ts
publish_head
make_descendant_main
MOCK_DRIFT_SHA=$DRIFT_SHA run_release \
    || fail 'Web fallback build did not recover from a main that contains the release commits'
assert_recovered
assert_count 1 'WEB_BUILD' "$CASE_LOG"
assert_count 1 'WEB_DEPLOY' "$CASE_LOG"

# 两边都要建: 走并行构建器, 它的收尾是第三处 validate。
setup_repo both_drift_recovers
commit_change backend/java/cretas-api/Service.java
commit_change web-admin/src/app.ts
publish_head
make_descendant_main
MOCK_DRIFT_SHA=$DRIFT_SHA run_release \
    || fail 'parallel fallback build did not recover from a main that contains the release commits'
assert_recovered
assert_count 1 'JAVA_DEPLOY' "$CASE_LOG"
assert_count 1 'WEB_DEPLOY' "$CASE_LOG"

# 守卫放行之后、validate 之前 main 才动: 只在 validate 前查一次还是会无声失败。
# 第 1 次 validate 是构建前的 manifest 探测, 第 2 次是构建后的收尾。
setup_repo java_drift_between_guard_and_validate
commit_change backend/java/cretas-api/Service.java
publish_head
make_descendant_main
MOCK_DRIFT_ON=validate-2 MOCK_DRIFT_SHA=$DRIFT_SHA run_release \
    || fail 'a drift landing right before the post-build validate was not recovered'
assert_recovered

# 超出重试预算: 明确 ERROR + 恰好一次 failed + 不部署。
setup_repo java_drift_budget_exhausted
commit_change backend/java/cretas-api/Service.java
publish_head
make_descendant_main
if CRETAS_RELEASE_DRIFT_RETRIES=0 MOCK_DRIFT_SHA=$DRIFT_SHA run_release; then
    fail 'release continued after the drift retry budget was exhausted'
fi
assert_contains "$CASE_OUTPUT" 'ERROR: origin/main 已连续前进'
assert_contains "$CASE_OUTPUT" 'ERROR: origin/main moved during'
assert_refused
assert_contains "$CASE_REPORT" '"main_guard": {"status": "failed"'
# validate 单独跑也要说出原因 —— 当天就是靠 bash -x 才看到 HEAD 与 origin/main 不等。
if (cd "$CASE_REPO" && HOME="$CASE_HOME" MOCK_CALL_LOG="$CASE_LOG" MOCK_COUNTER_ROOT="$CASE_ROOT" \
    bash scripts/deploy/release-jar-manifest.sh validate) >"$CASE_ROOT/validate.log" 2>&1; then
    fail 'Java manifest validated although HEAD differs from origin/main'
fi
assert_contains "$CASE_ROOT/validate.log" 'JAVA_MANIFEST_INVALID reason=head_not_origin_main'

# 新 main 不含本次提交: 绝不能前进, 同样要说清楚。
setup_repo web_drift_divergent
commit_change web-admin/src/app.ts
publish_head
make_divergent_main
if MOCK_DRIFT_SHA=$DRIFT_SHA run_release; then
    fail 'release auto-advanced onto a main that does not contain the release commits'
fi
assert_contains "$CASE_OUTPUT" '不是它的祖先'
assert_refused
assert_contains "$CASE_REPORT" '"main_guard": {"status": "failed"'
if (cd "$CASE_REPO" && HOME="$CASE_HOME" MOCK_CALL_LOG="$CASE_LOG" MOCK_COUNTER_ROOT="$CASE_ROOT" \
    bash scripts/deploy/release-web-manifest.sh validate) >"$CASE_ROOT/validate.log" 2>&1; then
    fail 'Web manifest validated although HEAD differs from origin/main'
fi
assert_contains "$CASE_ROOT/validate.log" 'WEB_MANIFEST_INVALID reason=head_not_origin_main'

# 阴性对照: main 没动、制品本身坏了。不许被当成漂移, 要报制品的原因, main_guard 如实 passed。
setup_repo java_invalid_artifact_no_drift
commit_change backend/java/cretas-api/Service.java
publish_head
if MOCK_CORRUPT_JAR_SHA=1 run_release; then
    fail 'release accepted a JAR whose hash does not match its manifest'
fi
assert_contains "$CASE_OUTPUT" 'JAVA_MANIFEST_INVALID reason=jar_sha256_mismatch'
assert_contains "$CASE_OUTPUT" 'ERROR: Java manifest validation failed'
assert_refused
assert_contains "$CASE_REPORT" '"main_guard": {"status": "passed"'
assert_contains "$CASE_REPORT" '"drift_recoveries": 0'

# 构建后 fetch 不通: 证明不了 exact main, 就要拒绝并说出来, 不能拿旧 ref 放行。
setup_repo java_guard_fetch_fails
commit_change backend/java/cretas-api/Service.java
publish_head
if MOCK_BUILD_BREAKS_FETCH="$CASE_ROOT/no-such-remote.git" run_release; then
    fail 'release continued although origin/main could not be fetched after the build'
fi
assert_contains "$CASE_OUTPUT" 'ERROR: cannot fetch origin/main during'
assert_refused
assert_contains "$CASE_REPORT" '"main_guard": {"status": "failed"'

# 入口闸拒绝发生在 EXIT trap 装上之前。drift 重新执行时也会走到这里, 同样要有终态。
setup_repo stale_main_at_entry
commit_change backend/java/cretas-api/Service.java
if run_release; then fail 'stale origin/main was accepted'; fi
assert_contains "$CASE_OUTPUT" 'deployment requires clean exact origin/main'
assert_refused

echo "PASS: fallback-build main drift recovers or fails loudly with exactly one final status"
