#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
E2E_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-pr.yml"
MIGRATION="$ROOT_DIR/backend/java/cretas-api/src/main/resources/db/flyway/V20261028_68_1__bom_items_standard_quantity_compat.sql"
WAIT_FOR_HEALTH="$ROOT_DIR/tests/v1-e2e/scripts/wait-for-health.sh"
AGENTS_FILE="$ROOT_DIR/AGENTS.md"
DEPLOY_SKILL="$ROOT_DIR/.agents/skills/deploy-backend/SKILL.md"
L1_SMOKE="$ROOT_DIR/tests/v1-e2e/web/l1-smoke.spec.ts"
RELEASE_PREFLIGHT="$ROOT_DIR/scripts/deploy/release-preflight.sh"
RELEASE_ORCHESTRATOR="$ROOT_DIR/scripts/deploy/release-cretas.sh"
WEB_MANIFEST="$ROOT_DIR/scripts/deploy/release-web-manifest.sh"

assert_contains() {
    local file=$1
    local expected=$2
    grep -Fq -- "$expected" "$file" || {
        echo "FAIL: missing '$expected' in $file" >&2
        exit 1
    }
}

# ⚠️ 找不到时必须**出声**。原版是 `grep -Fnm1 ... | cut -d: -f1` —— 命中不了就返回
#    空串, 而调用点是 `x=$(line_number ...)`; 在 `set -euo pipefail` 下这条赋值语句
#    直接带着 rc=1 杀掉整个脚本, **一个字都不打**。2026-09-06 这个用例在 CI 里
#    正是这个形状: rc=1 且输出为空, 日志上看不出是哪一条断言炸的(硬约束 1 的反例)。
line_number() {
    local file=$1
    local expected=$2
    local hit
    hit=$(grep -Fn -- "$expected" "$file" || true)
    if [ -z "$hit" ]; then
        echo "FAIL: 在 $(basename "$file") 里找不到步骤 '$expected'" >&2
        exit 1
    fi
    hit=${hit%%$'\n'*}
    printf '%s' "${hit%%:*}"
}

# CI is manual-only now, so a second operator-triggered run must not silently
# cancel an in-flight release audit.
assert_contains "$CI_WORKFLOW" 'workflow_dispatch:'
assert_contains "$CI_WORKFLOW" 'cancel-in-progress: false'

# ── 打包/上传住在哪个 workflow 里? ⛔ 不写死文件名 ────────────────────────
# 2026-08-27 commit e0f7a6dbcb35 (#175) 把这三步从 ci.yml 整体搬到了
# java-artifact.yml: ci.yml 的 java-build-test 在 job 级挂了 `services: postgres`,
# 而服务容器只在 Linux runner 上支持 ⇒ 托管额度耗尽时它整个跑不起来, 每次 Java
# 合并都不产制品(实测发布回退本地 Maven, 204s vs 命中 25s)。
#
# 原来的排序断言(package < upload < 全量 verify)想守的是「JAR 先上传, 别被那条
# 一个多小时的全量 Maven 挡在后面」。搬开之后这一点由**工作流分离**保证得更彻底:
# 全量审计留在 ci.yml 且已改成纯手动, 打包在另一个文件里、由 push 触发。
# ⇒ 断言相应抬高: ①打包/上传在同一个 workflow 里且顺序正确;
#                  ②全量 verify 那个 workflow 里**不许**再有一份打包/上传
#                    (java-artifact.yml 顶部那句「⛔ 打包逻辑只有这一份」, 形态 D)。
ARTIFACT_NAME_LITERAL='name: cretas-java-${{ github.sha }}'
artifact_owners=$(grep -Fl -- "$ARTIFACT_NAME_LITERAL" "$ROOT_DIR"/.github/workflows/*.yml 2>/dev/null | LC_ALL=C sort || true)
artifact_owner_count=$(printf '%s' "$artifact_owners" | grep -c . || true)
if [ "$artifact_owner_count" != "1" ]; then
    echo "FAIL: 恰好应有 1 个 workflow 上传 '$ARTIFACT_NAME_LITERAL', 实际 $artifact_owner_count 个" >&2
    exit 1
fi
ARTIFACT_WORKFLOW="$artifact_owners"
echo "READING: ARTIFACT_WORKFLOW=$(basename "$ARTIFACT_WORKFLOW")"

# ⚠️ 必须带 `- ` 前缀锚**步骤**。java-artifact.yml 的 job 也叫
#    `name: Package exact-commit deploy artifact`(4 空格缩进), 不带前缀的话即使把
#    那一步整个改名, 断言仍会命中 job 名而保持绿 —— 实测变异过一次, 就是这个形状。
package_line=$(line_number "$ARTIFACT_WORKFLOW" '- name: Package exact-commit deploy artifact')
artifact_upload_line=$(line_number "$ARTIFACT_WORKFLOW" '- name: Upload exact-commit deploy artifact')
if ! (( package_line < artifact_upload_line )); then
    echo "FAIL: exact-main JAR 必须先打包再上传" >&2
    exit 1
fi
# 全量 verify 仍留在 ci.yml, 且那边不许再有第二份打包/上传。
full_verify_line=$(line_number "$CI_WORKFLOW" 'name: Maven compile and test')
[ -n "$full_verify_line" ] || { echo "FAIL: ci.yml 里找不到全量 Maven verify" >&2; exit 1; }
for dup in 'name: Package exact-commit deploy artifact' 'name: Upload exact-commit deploy artifact'; do
    if grep -Fq -- "$dup" "$CI_WORKFLOW"; then
        echo "FAIL: ci.yml 里又出现了 '$dup' —— 打包逻辑变成两份, 一定会漂(形态 D)" >&2
        exit 1
    fi
done

# 这条原先钉的是单行 `run: mvn -B package -Dmaven.test.skip=true -pl .`。
# #2013 之后打包步骤改成多行 `run: |` 且分两支: 给了选择器就【先跑测试再打包】(测试失败
# 就没有制品, 而不是产出一份没人把关过的制品), 没给才退回 skip。旧断言钉的是一个被刻意
# 废弃的行为, 所以它一直红着 —— 换成当前那个更强的契约。
#
# ⚠️ 搬家时顺带把裸 `mvn` 换成了 `"$MVN"`(自建 macOS runner 上没有系统 mvn, 只有
#    仓库自带的 ./mvnw, 解析收在 scripts/ci/resolve-maven.sh 一处)。所以这里钉的
#    是 `-B package -Dtest=...` 那一截, ⛔ 不再钉解释器名字。
#
# 不去 grep 注释文本(那太脆), 而是断言两支都在、且【带测试的那支是主支】。
tested_package_line=$(line_number "$ARTIFACT_WORKFLOW" '-B package -Dtest="$TARGET_TESTS"')
skip_package_line=$(line_number "$ARTIFACT_WORKFLOW" '-B package -Dmaven.test.skip=true -pl .')
if ! (( tested_package_line < skip_package_line )); then
    echo "FAIL: 带测试的打包必须是主支, -Dmaven.test.skip 只能是没给选择器时的兜底" >&2
    exit 1
fi

backend_line=$(line_number "$E2E_WORKFLOW" 'name: Start Java backend (background)')
web_deps_line=$(line_number "$E2E_WORKFLOW" 'name: Install web-admin deps')
browser_line=$(line_number "$E2E_WORKFLOW" 'name: Install playwright browsers (chromium only)')
health_line=$(line_number "$E2E_WORKFLOW" 'name: Wait for backend health (up to 4 min)')
if ! (( backend_line < web_deps_line && backend_line < browser_line && browser_line < health_line )); then
    echo "FAIL: Java startup must precede Node installs, and health wait must follow them" >&2
    exit 1
fi

assert_contains "$MIGRATION" 'ADD COLUMN IF NOT EXISTS standard_quantity DECIMAL(15, 4);'
assert_contains "$E2E_WORKFLOW" '240 "$backend_pid"'
assert_contains "$WAIT_FOR_HEALTH" 'kill -0 "$WATCH_PID"'
assert_contains "$AGENTS_FILE" '最终只读代码审查应安排在最后一次目标构建/测试之前'
assert_contains "$DEPLOY_SKILL" 'feature-head=$(git rev-parse HEAD)'
assert_contains "$DEPLOY_SKILL" 'feature-head == <pr-head>'
assert_contains "$DEPLOY_SKILL" 'git merge-base --is-ancestor <merge-commit> origin/main'
assert_contains "$DEPLOY_SKILL" 'git diff --quiet "$feature-head" <merge-commit>'
assert_contains "$DEPLOY_SKILL" 'git switch --detach origin/main'
assert_contains "$DEPLOY_SKILL" './scripts/deploy/release-preflight.sh'
assert_contains "$DEPLOY_SKILL" 'Never trigger or wait for an Artifact during a release.'
assert_contains "$DEPLOY_SKILL" '--stage-backend YES-STAGE'
assert_contains "$DEPLOY_SKILL" 'Candidate archives are also keyed by the exact `web-admin` Git tree.'
assert_contains "$AGENTS_FILE" '--stage-backend YES-STAGE'

# 合并后先预热这一步必须【三处都有】: AGENTS.md(Codex 的固定上下文) + 两份 deploy skill。
# 🔴 .agents/ 与 .claude/ 两份 SKILL.md 是【各自独立跟踪的副本, 且早已内容分叉】——
# 只改一份的话, 另一个 agent 永远看不到这一步。这正是本仓库反复出问题的
# 「同一件事由多处承载, 只改一处静默失效」那一类。
assert_contains "$AGENTS_FILE" 'prewarm-main-artifact.sh'
assert_contains "$DEPLOY_SKILL" 'prewarm-main-artifact.sh'
assert_contains "$ROOT_DIR/.claude/skills/deploy-backend/SKILL.md" 'prewarm-main-artifact.sh'
# ⚠️ 原文钉的是 AGENTS.md 里那句 '按 `web-admin` Git tree 保留可恢复缓存' —— 该句已
#    被改写(现在是「Web dist 缓存与四方哈希…由脚本强制」), 断言从此钉在一句不存在的
#    话上。守的性质没变: **AGENTS.md 必须告诉操作者 web dist 是按树复用缓存、不手工重建**,
#    而「按哪棵树」这条更强的措辞由下面 DEPLOY_SKILL 那条 'keyed by the exact
#    `web-admin` Git tree' 承担(形态 D: 一件事三处承载, 三处都要有)。
assert_contains "$AGENTS_FILE" 'Web dist 缓存'
assert_contains "$AGENTS_FILE" '不复制、不削弱、不手工重做'
assert_contains "$RELEASE_ORCHESTRATOR" '--stage-backend YES-STAGE'
assert_contains "$RELEASE_ORCHESTRATOR" 'ensure_exact_main_after_artifacts "artifact validation/fallback build"'
assert_contains "$WEB_MANIFEST" 'web_release_validate_cached'
assert_contains "$RELEASE_PREFLIGHT" 'Fast, read-only release gates.'
assert_contains "$L1_SMOKE" '/\b401\b|Unauthorized|500 Internal|NoResourceFoundException/i'

# A dead backend must fail immediately instead of consuming the full timeout.
set +e
bash "$WAIT_FOR_HEALTH" http://127.0.0.1:1/health 10 999999 >/dev/null 2>&1
dead_pid_rc=$?
set -e
if [ "$dead_pid_rc" -eq 0 ]; then
    echo "FAIL: wait-for-health accepted a dead watched PID" >&2
    exit 1
fi

echo "PASS: release pipeline acceleration contracts are present"
