#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DEPLOY_SCRIPT="$ROOT_DIR/scripts/deploy/deploy-backend.sh"

# ---------------------------------------------------------------------------
# 谁产出 exact-commit 制品? ⛔ 不写死文件名。
#
# 2026-08-27 commit e0f7a6dbcb35 (#175) 把打包/上传步骤从 ci.yml **整体搬到**
# .github/workflows/java-artifact.yml —— ci.yml 的那个 job 在 job 级声明了
# `services: postgres`, 而服务容器只在 Linux runner 上支持, 托管额度耗尽后它
# 整个跑不起来。搬完之后 ci.yml 退回成纯手动全量审计, **连 push 触发都没有了**。
#
# 这几条断言守的不变量一个字没变(「制品钉死在 exact commit 上, 且带两份完整性
# 清单」), 变的只是它住在哪个文件里。所以这里**反查**谁上传那个制品名, 并断言
# 全仓恰好一份 —— 两份就是形态 D(同一件事两处承载, 一定会漂), java-artifact.yml
# 顶部那句「⛔ 打包逻辑只有这一份 —— ci.yml 里对应的步骤已删除」正是这个约束。
# 下次再搬家, 这些断言自动跟过去, ⛔ 不会再变成「钉着一个已经空了的文件」。
# ---------------------------------------------------------------------------
ARTIFACT_NAME_LITERAL='name: cretas-java-${{ github.sha }}'
artifact_owners=$(grep -Fl -- "$ARTIFACT_NAME_LITERAL" "$ROOT_DIR"/.github/workflows/*.yml 2>/dev/null | LC_ALL=C sort || true)
artifact_owner_count=$(printf '%s' "$artifact_owners" | grep -c . || true)
if [ "$artifact_owner_count" != "1" ]; then
    echo "FAIL: 恰好应有 1 个 workflow 上传 '$ARTIFACT_NAME_LITERAL', 实际 $artifact_owner_count 个:" >&2
    printf '%s\n' "$artifact_owners" >&2
    echo "      0 个 = 制品链断了(deploy-backend.sh 按这个名字找); >1 个 = 两份打包逻辑会漂。" >&2
    exit 1
fi
CI_WORKFLOW="$artifact_owners"
echo "ARTIFACT_WORKFLOW=$(basename "$CI_WORKFLOW")"

assert_contains() {
    local file=$1
    local expected=$2
    if ! grep -Fq -- "$expected" "$file"; then
        echo "FAIL: missing '$expected' in $file" >&2
        exit 1
    fi
}

assert_not_contains() {
    local file=$1
    local unexpected=$2
    if grep -Fq -- "$unexpected" "$file"; then
        echo "FAIL: unexpected '$unexpected' in $file" >&2
        exit 1
    fi
}

# CI artifact must be tied to the exact commit and carry both integrity manifests.
assert_contains "$CI_WORKFLOW" 'name: cretas-java-${{ github.sha }}'
assert_contains "$CI_WORKFLOW" 'printf '\''%s\n'\'' "$GITHUB_SHA" > "$JAR.commit"'
assert_contains "$CI_WORKFLOW" 'sha256sum "$JAR" > "$JAR.sha256"'
assert_contains "$CI_WORKFLOW" 'compression-level: 0'
assert_contains "$DEPLOY_SCRIPT" 'ARTIFACT_NAME="cretas-java-$HEAD_SHA"'
assert_contains "$DEPLOY_SCRIPT" '.workflow_run.head_branch == \"main\"'
assert_contains "$DEPLOY_SCRIPT" '.workflow_run.head_sha == \"$HEAD_SHA\"'
assert_contains "$DEPLOY_SCRIPT" 'sha256sum -c "$JAR_NAME.sha256"'
assert_contains "$DEPLOY_SCRIPT" 'run_first_success_build_race "$BUILD_RACE_DIR" "ci" ci_build_candidate "maven" maven_build_candidate'
assert_contains "$DEPLOY_SCRIPT" '[ "${ENABLE_CI_ARTIFACT_REUSE:-0}" = "1" ] || return 1'

# Repeated startup crashes must fail before the full 150-second timeout and show logs.
assert_contains "$DEPLOY_SCRIPT" 'STARTUP_RESTART_LIMIT="${STARTUP_RESTART_LIMIT:-2}"'
assert_contains "$DEPLOY_SCRIPT" "''|*[!0-9]*|0)"
assert_contains "$DEPLOY_SCRIPT" 'RESTART_LIMIT:'
assert_contains "$DEPLOY_SCRIPT" 'set +e'
assert_contains "$DEPLOY_SCRIPT" 'journalctl -u $IDLE_SERVICE -n 80 --no-pager -o short-iso'

# The proven delayed-crash protection remains in place.
#
# ⚠️ 原先钉的是字面量 `for ROUND in 1 2 3 4 5; do`。2026-07-30 那个循环被**有依据地**
# 缩成 3 轮(见 deploy-backend.sh 里那段注释: 166 次部署留档 / 370 轮探测 / 0 次
# auto-rollback; 第 4-5 轮防的是没有历史证据的推测场景)。也就是说旧断言守的是一个
# 已经被刻意废弃的行为 —— 它红得理直气壮, 而**改断言不等于删断言**(形态 C‴):
# 从「字面(恰好 5 轮)」抬到「性质(多轮 + 有间隔 + 任何一轮不健康就回滚)」, 并把
# 下限钉住, 免得哪天悄悄缩成 1 轮。
round_line=$(grep -Eo '^[[:space:]]*for ROUND in [0-9 ]+; do' "$DEPLOY_SCRIPT" | head -1)
[ -n "$round_line" ] || { echo "FAIL: 切流后的多轮观察循环没了 (for ROUND in ...)" >&2; exit 1; }
round_count=$(printf '%s' "$round_line" | sed -E 's/.*for ROUND in ([0-9 ]+); do/\1/' | wc -w | tr -d ' ')
echo "POST_SWITCH_ROUNDS=$round_count"
[ "$round_count" -ge 3 ] \
    || { echo "FAIL: 切流后观察只剩 $round_count 轮 —— 少于 3 轮盖不住'启动后秒级 crash'那个窗口" >&2; exit 1; }
assert_contains "$DEPLOY_SCRIPT" 'sleep 6'
# 观察不过必须真的回滚, 而不是只打一行字。
assert_contains "$DEPLOY_SCRIPT" 'POST_SWITCH_HEALTHY=false'
assert_contains "$DEPLOY_SCRIPT" 'auto-rollback: 切回旧 upstream'

# Merely having gh authenticated must not fabricate a release URL.
assert_contains "$DEPLOY_SCRIPT" 'gh release view "$VERSION" --repo "$REPO"'
assert_not_contains "$DEPLOY_SCRIPT" '[ "$HAS_GH" = "true" ] && echo "  Release:'

# Flyway preflight scans the source once and reuses a sorted manifest. In
# particular, do not spawn one basename process per migration on Git Bash.
assert_contains "$DEPLOY_SCRIPT" 'SRC_FLY_PATHS=$(find "$FLYWAY_SRC_DIR" -type f -name '\''V*.sql'\'' -print'
assert_contains "$DEPLOY_SCRIPT" 'SRC_FLY_SORTED=$(printf '\''%s\n'\'' "$SRC_FLY_PATHS" | sed '\''s#^.*/##'\'' | LC_ALL=C sort)'
assert_contains "$DEPLOY_SCRIPT" 'if [ -n "$SRC_FLY_PATHS" ]; then'
assert_contains "$DEPLOY_SCRIPT" 'DUPS=$(printf '\''%s\n'\'' "$SRC_FLY_SORTED"'
assert_not_contains "$DEPLOY_SCRIPT" "-exec basename {}"

# Upload losers must be stopped by exact recorded PID trees. Raw Git Bash `$!`
# values are MSYS PIDs, so taskkill /PID is unsafe without PID conversion.
assert_contains "$DEPLOY_SCRIPT" 'terminate_process_tree() {'
assert_contains "$DEPLOY_SCRIPT" 'child_pids=$(ps -e 2>/dev/null | awk -v parent="$pid"'
assert_contains "$DEPLOY_SCRIPT" 'terminate_upload_tasks'
assert_not_contains "$DEPLOY_SCRIPT" 'taskkill.exe //PID "$pid"'
assert_not_contains "$DEPLOY_SCRIPT" 'pkill -9 -f "aws.*$JAR_NAME"'
assert_not_contains "$DEPLOY_SCRIPT" 'jobs -p 2>/dev/null | xargs'

# Behavioral probe: source only the PID-tree helper, create a private wrapper
# and child, then prove both are gone. No network or deployment is involved.
PROCESS_TREE_HELPER=$(awk '
    /^terminate_process_tree\(\) \{/ {copy = 1}
    copy {print}
    copy && /^}$/ {exit}
' "$DEPLOY_SCRIPT")
eval "$PROCESS_TREE_HELPER"
bash -c 'sleep 30 & wait' &
probe_parent=$!
sleep 1
if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* || "${OSTYPE:-}" == win32* ]]; then
    probe_child=$(ps -e 2>/dev/null | awk -v parent="$probe_parent" 'NR > 1 && $2 == parent {print $1; exit}')
else
    probe_child=$(pgrep -P "$probe_parent" 2>/dev/null | head -1 || true)
fi
[ -n "$probe_child" ] || { echo "FAIL: process-tree probe child not found" >&2; kill "$probe_parent" 2>/dev/null || true; exit 1; }
terminate_process_tree "$probe_parent"
wait "$probe_parent" 2>/dev/null || true
if kill -0 "$probe_parent" 2>/dev/null || kill -0 "$probe_child" 2>/dev/null; then
    echo "FAIL: process-tree cleanup left a probe process running" >&2
    exit 1
fi

echo "PASS: deploy optimization contracts are present"
