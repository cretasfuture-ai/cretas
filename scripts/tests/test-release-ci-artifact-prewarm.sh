#!/usr/bin/env bash
# 预热短路的真值测试。
#
# 这条短路危险在于: 它的作用是【跳过一整段跨境运输 + 验签】。判松了就会把一份不适用的
# 描述符当成"预热成功", 然后在部署期晚失败(或者更糟, 部署一份内容不对的 jar)。
# 所以每一个否定条件都要有一条独立用例, 不能只测正路径。
#
# 用真临时 git 仓库 + 真的选择器展开; 只 stub 掉出网的三个命令(gh / pwsh / ssh)。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SRC="$ROOT_DIR/scripts/deploy/release-ci-artifact.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

pass_count=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass_count=$((pass_count + 1)); echo "  ok - $*"; }

bash -n "$SRC" || fail "语法错误"

REPO_DIR="$TMP_ROOT/repo"
mkdir -p "$REPO_DIR/scripts/deploy" "$REPO_DIR/scripts/lib" "$TMP_ROOT/bin" \
         "$REPO_DIR/backend/java/cretas-api/src/test/java/x"
cp "$SRC" "$REPO_DIR/scripts/deploy/"
cp "$ROOT_DIR/scripts/deploy/release-jar-manifest.sh" "$REPO_DIR/scripts/deploy/"
: > "$REPO_DIR/scripts/deploy/Publish-GitHubArtifactViaLightsailOss.ps1"

# 两个真实存在的测试类, 让 expand_test_selector 有东西可展开。
: > "$REPO_DIR/backend/java/cretas-api/src/test/java/x/AlphaRepositoryQueryValidationTest.java"
: > "$REPO_DIR/backend/java/cretas-api/src/test/java/x/BetaRepositoryQueryValidationTest.java"

(
    cd "$REPO_DIR"
    git init -q .
    git config user.email t@example.com
    git config user.name t
    git add -A
    git commit -qm base
    git update-ref refs/remotes/origin/main HEAD
) || fail "临时仓库准备失败"

TREE=$(git -C "$REPO_DIR" rev-parse "origin/main:backend/java/cretas-api")
[ -n "$TREE" ] || fail "拿不到 backend_tree"

# ---- stubs ----
cat > "$TMP_ROOT/bin/gh" <<'STUB'
#!/usr/bin/env bash
# 默认制品列表为空 —— 保证「走到这里」等于「短路没命中」, 用例才分得清。
# STUB_CANDIDATE_TSV 非空时才给出一行候选(id\tsha\trun_id), 供 2c 那段用。
echo "GH_CALLED" >> "${STUB_LOG:-/dev/null}"
for a in "$@"; do
    case "$a" in
        */jobs)
            echo "JOBS_QUERIED" >> "${STUB_LOG:-/dev/null}"
            # STUB_BUNDLE_STEP=none 模拟「读不到」(gh 出错 / 步骤被改名 ⇒ jq 空输出)。
            [ "${STUB_BUNDLE_STEP:-none}" = "none" ] && exit 0
            # 有 jq 就把【脚本自己传进来的过滤器】跑在一份真实形状的 jobs JSON 上 ——
            # 那份 JSON 里有两步带 attest 且结论相反, 所以「取第一个匹配」这个坑是被
            # **真的跑出来**的, 不是靠 grep 步骤名钉住的。没有 jq 就退回直给结论。
            if [ -n "${STUB_JOBS_JSON:-}" ] && [ -s "${STUB_JOBS_JSON:-}" ] && command -v jq >/dev/null 2>&1; then
                filter="" prev=""
                for x in "$@"; do
                    [ "$prev" = "--jq" ] && filter="$x"
                    prev="$x"
                done
                if [ -n "$filter" ]; then
                    echo "JQ_MODE" >> "${STUB_LOG:-/dev/null}"
                    jq -r "$filter" < "$STUB_JOBS_JSON"
                    exit 0
                fi
            fi
            printf '%s\n' "$STUB_BUNDLE_STEP"
            exit 0
            ;;
    esac
done
[ -z "${STUB_CANDIDATE_TSV:-}" ] || printf '%s\n' "$STUB_CANDIDATE_TSV"
exit 0
STUB
cat > "$TMP_ROOT/bin/pwsh" <<'STUB'
#!/usr/bin/env bash
# 运输一律失败 —— 用例只关心「有没有走到运输」, 由 PWSH_CALLED 判定。
echo "PWSH_CALLED" >> "${STUB_LOG:-/dev/null}"
exit 1
STUB
cat > "$TMP_ROOT/bin/ssh" <<'STUB'
#!/usr/bin/env bash
echo "SSH_CALLED" >> "${STUB_LOG:-/dev/null}"
exit "${STUB_SSH_RC:-0}"
STUB
chmod +x "$TMP_ROOT/bin/"*

DESC="$TMP_ROOT/desc.remote"
write_descriptor() {
    cat > "$DESC" <<EOF
format=${1:-cretas-remote-artifact-v1}
build_commit=$(git -C "$REPO_DIR" rev-parse HEAD)
backend_tree=${2:-$TREE}
jar_sha256=${3:-$(printf 'a%.0s' {1..64})}
jar_md5=$(printf 'b%.0s' {1..32})
jar_size_bytes=176289596
target_tests=${4:-*RepositoryQueryValidationTest}
attested=${5:-true}
staged_cache_path=/www/wwwroot/cretas/release-cache/sha256/x.jar
artifact_id=123
EOF
}

run() {
    # $1=期望退出码, $2=用例名, 其余透传给脚本
    local want=$1 name=$2; shift 2
    STUB_LOG="$TMP_ROOT/stub.log" : > "$TMP_ROOT/stub.log"
    set +e
    STUB_LOG="$TMP_ROOT/stub.log" STUB_SSH_RC="${STUB_SSH_RC:-0}" \
    STUB_CANDIDATE_TSV="${STUB_CANDIDATE_TSV:-}" STUB_BUNDLE_STEP="${STUB_BUNDLE_STEP:-none}" \
    STUB_JOBS_JSON="${STUB_JOBS_JSON:-}" \
    PATH="$TMP_ROOT/bin:$PATH" \
        bash "$REPO_DIR/scripts/deploy/release-ci-artifact.sh" \
            --descriptor "$DESC" "$@" > "$TMP_ROOT/out.log" 2>&1
    local rc=$?
    set -e
    [ "$rc" = "$want" ] || { cat "$TMP_ROOT/out.log" >&2; fail "$name: 期望 exit=$want 实得 $rc"; }
}

# ---- 1. 正路径: 描述符完整 + ECS 说文件在 → 短路命中, 且【没有】调用 gh ----
write_descriptor
STUB_SSH_RC=0 run 0 "正路径" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'CI_ARTIFACT_PREWARM_HIT' "$TMP_ROOT/out.log" || fail "正路径: 没打 PREWARM_HIT"
grep -q 'CI_ARTIFACT_READY' "$TMP_ROOT/out.log" || fail "正路径: 没打 READY(调用方靠它认)"
grep -q 'GH_CALLED' "$TMP_ROOT/stub.log" && fail "正路径: 短路后仍调了 gh —— 那 3.6s 没省掉"
ok "命中时跳过整段运输, 且不再调 gh"

# ---- 2..5 每个否定条件都必须【独立】挡住 ----
# 挡住 = 落到候选搜索(stub gh 返回空) → 非预热模式下 exit 1。
write_descriptor cretas-remote-artifact-v1 "$(printf 'c%.0s' {1..40})"
STUB_SSH_RC=0 run 1 "树不匹配" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'GH_CALLED' "$TMP_ROOT/stub.log" || fail "树不匹配: 没有落到候选搜索"
ok "backend_tree 不匹配 → 不短路"

write_descriptor cretas-remote-artifact-v1 "$TREE" "" "" false
STUB_SSH_RC=0 run 1 "未验签" --tests 'AlphaRepositoryQueryValidationTest'
ok "attested=false → 不短路"

write_descriptor cretas-remote-artifact-v1 "$TREE" "" 'AlphaRepositoryQueryValidationTest'
STUB_SSH_RC=0 run 1 "选择器未覆盖" --tests 'BetaRepositoryQueryValidationTest'
ok "要求 ⊄ 描述符记录的 CI 选择器 → 不短路"

write_descriptor wrong-format
STUB_SSH_RC=0 run 1 "格式不对" --tests 'AlphaRepositoryQueryValidationTest'
ok "format 不对 → 不短路"

write_descriptor
STUB_SSH_RC=1 run 1 "ECS 上没有" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'SSH_CALLED' "$TMP_ROOT/stub.log" || fail "ECS 用例: 根本没问服务器"
ok "ECS 缓存已被清理 → 不短路(而不是给一个必然晚失败的假命中)"

# ---- 6. --prewarm 语义: 还没有制品不算失败, 但只对"没有制品"放宽 ----
rm -f "$DESC"
STUB_SSH_RC=0 run 0 "prewarm 待定" --tests 'AlphaRepositoryQueryValidationTest' --prewarm
grep -q 'CI_ARTIFACT_PREWARM=pending' "$TMP_ROOT/out.log" || fail "prewarm: 没打 pending"
ok "--prewarm 且尚无制品 → 退 0 并打 pending"

STUB_SSH_RC=0 run 1 "非 prewarm 无制品" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'CI_ARTIFACT_UNAVAILABLE' "$TMP_ROOT/out.log" || fail "非 prewarm: 没打 UNAVAILABLE"
ok "不带 --prewarm 且尚无制品 → 仍是硬失败(语义没被放宽)"

# ---- 8. 车道结构性不可用 (2026-09-07, 票 OPS-CI-ARTIFACT-LANE-DEAD-IN-THIS-ORG) ----
#
# 背景: 发 44–49 六次预热全是 `pending reason=no_main_artifact_listed` 等满 7 分钟再回退本地构建。
# 两层: ① REPO 默认值指着旧 fork(列不到制品); ② 指对之后, 私有仓签不上 attestation ⇒ ECS 按设计
# 拒收。第 ② 层等下去没有意义, 所以要**立刻**说清楚(exit 3), ⛔ 不当 pending 轮询。
HEAD_SHA=$(git -C "$REPO_DIR" rev-parse HEAD)
CANDIDATE_TSV=$(printf '111\t%s\t999' "$HEAD_SHA")
rm -f "$DESC"

# 真实形状: 同一个 job 里【两步】名字都带 attest, 结论**相反** —— provenance 那步 success
# (它自己 continue-on-error), Ship 那步才是我们要的。取第一个匹配会读出完全相反的结论,
# 这份 fixture 让那个坑变成可跑出来的红, 而不是一句注释。
JOBS_JSON="$TMP_ROOT/jobs.json"
write_jobs() {
    cat > "$JOBS_JSON" <<EOF
{"jobs":[{"name":"Package exact-commit deploy artifact","steps":[
  {"name":"Attest deploy artifact provenance","conclusion":"success"},
  {"name":"Ship attestation bundle with the artifact","conclusion":"$1"}
]}]}
EOF
}
export STUB_JOBS_JSON="$JOBS_JSON"

# 8a. Ship 那步 skipped ⇒ exit 3, 且**一步都不运输**(阴性对照: PWSH 必须没被调用)。
write_jobs skipped
STUB_CANDIDATE_TSV="$CANDIDATE_TSV" STUB_BUNDLE_STEP=skipped \
    run 3 "bundle 未随制品下发" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'CI_ARTIFACT_LANE_UNAVAILABLE' "$TMP_ROOT/out.log" \
    || fail "8a: 没打 CI_ARTIFACT_LANE_UNAVAILABLE"
grep -q 'READING: ATTEST_BUNDLE_STEP=skipped' "$TMP_ROOT/out.log" \
    || fail "8a: 没把读数打出来(不贴读数就没法判是量到了还是没量到)"
grep -q 'PWSH_CALLED' "$TMP_ROOT/stub.log" \
    && fail "8a: 已知签不上却仍发起了跨境运输 —— 那 61s 白花了"
# 这条读数决定上面那句「坑是被真跑出来的」成不成立: 走了真 jq 才算, 否则只剩第 9 节的静态钉。
if command -v jq >/dev/null 2>&1; then
    echo "READING: JOBS_FILTER_MODE=real-jq"
    grep -q 'JQ_MODE' "$TMP_ROOT/stub.log" \
        || fail "8a: 本机有 jq 却没走真 jq 那条路 —— 「取第一个匹配」的坑没有被真的跑出来"
else
    echo "READING: JOBS_FILTER_MODE=stub-printf (本机无 jq; 「取第一个匹配」只由第 9 节静态钉住)"
fi
ok "Ship 步 skipped ⇒ exit 3 且不发起运输"

# 8b. 阳性对照: 签上了(success)就必须【照常往下走】—— 否则这道检查会把一条健康的车道也掐死。
write_jobs success
STUB_CANDIDATE_TSV="$CANDIDATE_TSV" STUB_BUNDLE_STEP=success \
    run 1 "bundle 已下发" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'PWSH_CALLED' "$TMP_ROOT/stub.log" \
    || fail "8b: bundle 签上了却没走到运输 —— 这道检查掐死了健康车道"
grep -q 'CI_ARTIFACT_LANE_UNAVAILABLE' "$TMP_ROOT/out.log" \
    && fail "8b: 签上了还报车道不可用"
ok "Ship 步 success ⇒ 照常运输(阳性对照: 检查不误伤)"

# 8c. 读不到(步骤改名 / gh 出错)⇒ unknown ⇒ 维持改动前的行为往下走, ⛔ 不猜。
STUB_CANDIDATE_TSV="$CANDIDATE_TSV" STUB_BUNDLE_STEP=none \
    run 1 "读不到 bundle 步" --tests 'AlphaRepositoryQueryValidationTest'
grep -q 'READING: ATTEST_BUNDLE_STEP=unknown' "$TMP_ROOT/out.log" \
    || fail "8c: 读不到时必须打 unknown, 而不是当成 success 或 skipped"
grep -q 'CI_ARTIFACT_LANE_UNAVAILABLE' "$TMP_ROOT/out.log" \
    && fail "8c: 没量到却下了「车道不可用」的结论"
grep -q 'PWSH_CALLED' "$TMP_ROOT/stub.log" \
    || fail "8c: 没量到时应维持原行为(继续往下走, 由后面的硬断言 fail closed)"
ok "读不到 ⇒ unknown ⇒ 不下结论, 行为与改动前一致"

# 8d. 🔴 票的核心: --prewarm 下这一种【不是 pending】。等待对它没有意义。
write_jobs skipped
STUB_CANDIDATE_TSV="$CANDIDATE_TSV" STUB_BUNDLE_STEP=skipped \
    run 3 "prewarm 遇到不可用车道" --tests 'AlphaRepositoryQueryValidationTest' --prewarm
grep -q 'CI_ARTIFACT_PREWARM=pending' "$TMP_ROOT/out.log" \
    && fail "8d: --prewarm 把结构性不可用读成 pending —— 那正是那 7 分钟轮询的来源"
ok "--prewarm 遇到不可用车道 ⇒ exit 3 而不是 pending(不再空等 7 分钟)"

# 8e. --probe-only 也要立刻说不可用 —— 发布靠它决定要不要走这条路。
write_jobs skipped
STUB_CANDIDATE_TSV="$CANDIDATE_TSV" STUB_BUNDLE_STEP=skipped \
    run 3 "probe 遇到不可用车道" --tests 'AlphaRepositoryQueryValidationTest' --probe-only
grep -q 'CI_ARTIFACT_PROBE_OK' "$TMP_ROOT/out.log" \
    && fail "8e: 车道不可用却报 PROBE_OK"
ok "--probe-only 遇到不可用车道 ⇒ exit 3"

# 8f. --probe-only 正路径没被这道检查改坏。
write_jobs success
STUB_CANDIDATE_TSV="$CANDIDATE_TSV" STUB_BUNDLE_STEP=success \
    run 0 "probe 正路径" --tests 'AlphaRepositoryQueryValidationTest' --probe-only
grep -q 'CI_ARTIFACT_PROBE_OK' "$TMP_ROOT/out.log" || fail "8f: 正路径没打 PROBE_OK"
ok "--probe-only 正路径仍是 exit 0 + PROBE_OK"

# ---- 9. 两处默认值 + 步骤名的漂移闸 (形态 D: 同一个东西有两份就会漂) ----
WEB_SRC="$ROOT_DIR/scripts/deploy/release-web-ci-artifact.sh"
WORKFLOW="$ROOT_DIR/.github/workflows/java-artifact.yml"
for f in "$SRC" "$WEB_SRC"; do
    grep -q 'REPO=${CRETAS_RELEASE_REPO:-cretasfuture-ai/cretas}' "$f" \
        || fail "9: $(basename "$f") 的 REPO 默认值不是 cretasfuture-ai/cretas"
done
# ⚠️ `grep -c` 数到 0 时退出码是 1 —— 在 set -e 下 `X=$(grep -c …)` 会【直接把测试杀掉】,
#    症状是最后一条 ok 之后什么都不打就 rc=1(实测踩到)。而 0 恰恰是这里期望的答案 ⇒ 一律 `|| true`。
OLD_FORK=$(grep -rc 'CRETAS_RELEASE_REPO:-Stevenjxie' "$SRC" "$WEB_SRC" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}' || true)
echo "READING: OLD_FORK_DEFAULTS=$OLD_FORK (改前 2, 改后必须 0)"
[ "$OLD_FORK" -eq 0 ] || fail "9: 还有 $OLD_FORK 处默认指着旧 fork(那边 main 零制品)"
ok "两个脚本的 REPO 默认值一致且指向本组织仓"

# 脚本按名字找那一步; 名字只存在于 workflow 里 ⇒ 两份必须对得上, 否则检查静默退化成 unknown。
PATTERN_IN_SCRIPT=$(grep -c 'contains("attestation bundle")' "$SRC" || true)
[ "$PATTERN_IN_SCRIPT" -eq 1 ] || fail "9: 脚本里匹配步骤名的那处不见了(实得 $PATTERN_IN_SCRIPT 处)"
STEP_HITS=$(grep -ci 'name:.*attestation bundle' "$WORKFLOW" || true)
echo "READING: WORKFLOW_BUNDLE_STEPS=$STEP_HITS"
[ "$STEP_HITS" -eq 1 ] \
    || fail "9: java-artifact.yml 里匹配 'attestation bundle' 的步骤有 $STEP_HITS 个(要恰好 1) —— 改了名就把 2c 那段变成恒 unknown"
# 同一个 job 里还有一步 `Attest deploy artifact provenance`(结论 success, 与 Ship 相反)。
# 这条断言钉住「不止一步带 attest」这个事实 —— 它正是「不能取第一个匹配」的理由。
ATTEST_STEPS=$(grep -ci 'name:.*attest' "$WORKFLOW" || true)
[ "$ATTEST_STEPS" -ge 2 ] \
    || fail "9: 预期 workflow 里带 attest 的步骤 ≥2(取第一个会读到相反结论), 实得 $ATTEST_STEPS"
ok "步骤名两份对得上, 且「带 attest 的步骤不止一个」这个陷阱被钉住"

# ---- 10. 包装脚本必须把 exit 3 原样透传, ⛔ 不折叠成 1, 更不进轮询 ----
PREWARM_SRC="$ROOT_DIR/scripts/deploy/prewarm-main-artifact.sh"
bash -n "$PREWARM_SRC" || fail "10: prewarm-main-artifact.sh 语法错误"
grep -q 'rc" -eq 3' "$PREWARM_SRC" || fail "10: prewarm 没有针对 rc=3 的分支"
grep -q 'PREWARM=unavailable' "$PREWARM_SRC" || fail "10: prewarm 没有 unavailable 这条措辞"
grep -q 'if ! "$SCRIPT_DIR/release-ci-artifact.sh"' "$PREWARM_SRC" \
    && fail "10: rc 还是从 \`if !\` 里取的 —— 那读到的是取反的结果, 不是被调脚本的退出码"
ok "prewarm 显式接 rc 并为 3 单独分支"

# ---- 7. 结构: 短路必须排在 gh api 之前 ----
sc=$(grep -n 'CI_ARTIFACT_PREWARM_HIT' "$SRC" | head -1 | cut -d: -f1)
gh_line=$(grep -n 'gh api' "$SRC" | head -1 | cut -d: -f1)
[ -n "$sc" ] && [ -n "$gh_line" ] && [ "$sc" -lt "$gh_line" ] \
    || fail "短路(行 ${sc:-?})必须排在 gh api(行 ${gh_line:-?})之前, 否则白付 API 往返"
ok "短路排在 gh api 之前 ($sc < $gh_line)"

echo "PASS: ${pass_count} 项断言全部通过"
