#!/usr/bin/env bash
# web dist 制品取回链路的结构闸。
#
# 这条链有三个特别容易悄悄失效的地方, 每一个在本仓库都有前科:
#   1. 开关只在某条路径生效 (#2031: --prefer-ci-artifact 当时只在 java-only 路径起作用,
#      功能形同不存在)。通向 web 构建的路径有三条, 所以预热必须插在它们共同落到的那个函数里。
#   2. 预热排在 ensure_dependencies 之后 —— npm ci 已经付掉了, 省下的只剩 vite build。
#   3. 东京侧两份 stager 的 URL 白名单各自演化 (安全边界漂移)。
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
FETCH="$ROOT_DIR/scripts/deploy/release-web-ci-artifact.sh"
WEB_MANIFEST="$ROOT_DIR/scripts/deploy/release-web-manifest.sh"
RELEASE="$ROOT_DIR/scripts/deploy/release-cretas.sh"
JAR_STAGER="$ROOT_DIR/scripts/deploy/lightsail/github-artifact-stage"
WEB_STAGER="$ROOT_DIR/scripts/deploy/lightsail/github-web-artifact-stage"
INVENTORY="$ROOT_DIR/scripts/deploy/server-script-inventory.conf"

pass_count=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass_count=$((pass_count + 1)); echo "  ok - $*"; }

for f in "$FETCH" "$WEB_STAGER"; do
    [ -f "$f" ] || fail "缺文件: $f"
    bash -n "$f" || fail "语法错误: $f"
done
ok "两个新脚本存在且可解析"

# ---- 1. 预热必须插在 web_release_build 里, 且排在两件事之前 ----
# 取 web_release_build 函数体的行号区间, 只在区间内找 —— 否则会匹配到别处的同名调用。
fn_start=$(grep -n '^web_release_build()' "$WEB_MANIFEST" | cut -d: -f1)
[ -n "$fn_start" ] || fail "找不到 web_release_build()"
fn_end=$(awk -v s="$fn_start" 'NR>s && /^}/ {print NR; exit}' "$WEB_MANIFEST")
[ -n "$fn_end" ] || fail "找不到 web_release_build() 的结尾"

# ⚠️ 必须跳过注释行。这几个名字在同一段注释里都被提到过(注释正是解释为什么要这个顺序的),
# 匹配到注释就会拿到一个与真实调用无关的行号 —— 第一版就这么把自己的注释当成了调用,
# 报出一个不存在的顺序错误。同类: 静态断言永远要先把注释剔掉。
line_in_fn() {
    awk -v s="$fn_start" -v e="$fn_end" -v pat="$1" '
        NR>=s && NR<=e {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            if (substr(line, 1, 1) == "#") next
            if (index($0, pat)) { print NR; exit }
        }' "$WEB_MANIFEST"
}

warm_line=$(line_in_fn 'release-web-ci-artifact.sh')
reuse_line=$(line_in_fn 'web_release_build_reusable')
deps_line=$(line_in_fn 'web_release_ensure_dependencies')

[ -n "$warm_line" ] || fail "web_release_build 里没有调用 release-web-ci-artifact.sh —— 取回没有调用方"
ok "预热在 web_release_build 内被调用 (行 $warm_line)"

[ -n "$reuse_line" ] && [ "$warm_line" -lt "$reuse_line" ] \
    || fail "预热(行 $warm_line)必须排在 web_release_build_reusable(行 ${reuse_line:-?})之前"
ok "预热排在复用判断之前 ($warm_line < $reuse_line)"

[ -n "$deps_line" ] && [ "$warm_line" -lt "$deps_line" ] \
    || fail "预热(行 $warm_line)必须排在 web_release_ensure_dependencies(行 ${deps_line:-?})之前, 否则 npm ci 已经付掉了"
ok "预热排在 ensure_dependencies 之前 ($warm_line < $deps_line)"

# ---- 2. 命令行 flag 必须导出成环境变量, 否则 Java 生效而 Web 不生效 ----
grep -q 'export CRETAS_RELEASE_PREFER_CI_ARTIFACT=1' "$RELEASE" \
    || fail "release-cretas.sh 没有把 --prefer-ci-artifact 导出成环境变量 —— Web 侧读不到"
ok "--prefer-ci-artifact 会被导出给 Web 侧"

# ---- 3. 东京侧两份 stager 的 URL 白名单必须逐字节一致 ----
# 两个脚本刻意分开(不动已在生产承重的 JAR 那份), 代价就是这段安全边界会重复。
# 重复本身可接受, 悄悄分叉不可接受。
extract_allowlist() {
    awk '/^import ipaddress$/{on=1} on{print} /raise SystemExit\(1\)$/{if(on)n++} on&&n>=3{exit}' "$1"
}
jar_list=$(extract_allowlist "$JAR_STAGER")
web_list=$(extract_allowlist "$WEB_STAGER")
[ -n "$jar_list" ] || fail "从 github-artifact-stage 取不到白名单(形态变了?)"
[ "$jar_list" = "$web_list" ] \
    || { diff <(printf '%s\n' "$jar_list") <(printf '%s\n' "$web_list") >&2 || true
         fail "两份 stager 的 URL 白名单已分叉"; }
ok "两份 stager 的 URL 白名单逐字节一致"

# ---- 4. 新服务器脚本必须登记进清单 ----
grep -q '^tokyo|/usr/local/sbin/github-web-artifact-stage|scripts/deploy/lightsail/github-web-artifact-stage|' "$INVENTORY" \
    || fail "github-web-artifact-stage 没登记进 server-script-inventory.conf, 漂移检查看不到它"
ok "已登记进服务器脚本清单"

# ---- 5. locale 陷阱: 取回脚本会被 LC_ALL=C 的统一入口调用 ----
offenders=$(grep -nE '(^|[^[:alnum:]_-])grep([[:space:]]+-[[:alnum:]]*P)' "$FETCH" \
    | grep -vE '^[0-9]+:[[:space:]]*#' || true)
[ -z "$offenders" ] || { printf '%s\n' "$offenders" >&2; fail "取回脚本用了 grep -P, LC_ALL=C 下会失效"; }
ok "取回脚本无 grep -P"

# ---- 6. 失败必须说出原因, 不能静默回退 ----
grep -q 'WEB_CI_ARTIFACT_UNAVAILABLE reason=' "$FETCH" \
    || fail "取回脚本失败时不打 reason —— 静默回退会让这条链"从没生效"长期无人发现"
grep -q 'WEB_CI_ARTIFACT=fallback' "$WEB_MANIFEST" \
    || fail "web_release_build 回退到本地构建时没有可见标记"
ok "失败路径可见 (reason + fallback 标记)"

# ---- 7. 制品必须按内容树认, 且 build_commit 本地可解析 ----
# 后者是那个"build 报复用成功 2s 返回, 部署期 validate exit 1 且一个字都不打"的根因。
grep -q 'manifest_commit_unresolvable_locally' "$FETCH" \
    || fail "取回脚本没有校验 build_commit 本地可解析"
grep -q 'manifest_commit_tree_mismatch' "$FETCH" \
    || fail "取回脚本没有校验 build_commit 的 web 树等于当前树"
ok "build_commit 可解析性与树一致性都有断言"

# ---- 8. 行为: 签名 pre-check 的三态 (2026-09-07) ----
#
# 前七节全是 grep, 而这一段要守的是**行为**: 「候选制品没签名时, 在付掉那趟东京往返之前
# 就退出, 并且退出码与『这一趟没成』不同」。grep 证明不了它 —— 所以这里真跑脚本。
# 用 --probe-only: 它在运输之前返回, 于是整节不碰网络(gh 是 stub, curl/ssh 根本走不到)。
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT
REPO_DIR="$TMP_ROOT/repo"
mkdir -p "$REPO_DIR/scripts/deploy" "$REPO_DIR/scripts/lib" "$REPO_DIR/web-admin/src" "$TMP_ROOT/bin"
cp "$FETCH" "$REPO_DIR/scripts/deploy/"
cp "$WEB_MANIFEST" "$ROOT_DIR/scripts/deploy/release-jar-manifest.sh" "$REPO_DIR/scripts/deploy/"
cp "$ROOT_DIR/scripts/lib/server-script-common.sh" "$REPO_DIR/scripts/lib/"
echo 'export const x = 1' > "$REPO_DIR/web-admin/src/app.ts"
(
    cd "$REPO_DIR"
    git init -q .
    git config user.email t@example.com
    git config user.name t
    git add -A
    git commit -qm base
) || fail "8: 临时仓库准备失败"
FIX_SHA=$(git -C "$REPO_DIR" rev-parse HEAD)
[ -n "$FIX_SHA" ] || fail "8: 拿不到 fixture 的 HEAD"

# gh stub: 制品列表给一行候选; /jobs 把**脚本自己传进来的 --jq 过滤器**跑在一份真实形状的
# jobs JSON 上 —— 「同一个 job 里两步都带 attest 且结论相反」这个坑因此是被**真跑出来**的,
# ⛔ 不是靠断言步骤名钉住的。没有 jq 的机器上退回直给结论(仍能分辨三态)。
cat > "$TMP_ROOT/bin/gh" <<'STUB'
#!/usr/bin/env bash
for a in "$@"; do
    case "$a" in
        */jobs)
            [ "${STUB_GH_JOBS_RC:-0}" = "0" ] || exit "$STUB_GH_JOBS_RC"
            if [ -s "${STUB_JOBS_JSON:-}" ] && command -v jq >/dev/null 2>&1; then
                filter="" prev=""
                for x in "$@"; do
                    [ "$prev" = "--jq" ] && filter="$x"
                    prev="$x"
                done
                [ -n "$filter" ] && { jq -r "$filter" < "$STUB_JOBS_JSON"; exit 0; }
            fi
            printf '%s\n' "${STUB_SHIP_CONCLUSION:-}"
            exit 0
            ;;
    esac
done
printf '%s\n' "${STUB_CANDIDATE_TSV:-}"
exit 0
STUB
chmod +x "$TMP_ROOT/bin/gh"

# 真实形状: 第一步 provenance 恒 success(它自己 continue-on-error), Ship 那步才是判据。
# 实测 run 34068075476(web-dist.yml @ main c6874100ca)就是 success / skipped 这个组合。
write_jobs() {   # $1=Ship 那步的结论; 空 = 那一步整个不存在(改名/被删)
    if [ -z "${1:-}" ]; then
        cat > "$TMP_ROOT/jobs.json" <<'JSON'
{"jobs":[{"steps":[{"name":"Attest dist archive provenance","conclusion":"success"}]}]}
JSON
    else
        cat > "$TMP_ROOT/jobs.json" <<JSON
{"jobs":[{"steps":[
  {"name":"Attest dist archive provenance","conclusion":"success"},
  {"name":"Ship attestation bundle with the artifact","conclusion":"$1"}
]}]}
JSON
    fi
}

# 🔴 这次到底走没走 jq, 必须自己说出来。没有 jq 时 stub 退回「直给结论」——
# 那条路上 8a 照样绿, 但它**没有**跑那个「两步 attest 结论相反」的坑 ⇒ 覆盖面不同。
# ⛔ 不许让两种覆盖面打出同一条绿(形态 A: 我量的这个数不是我想知道的那个数)。
if command -v jq >/dev/null 2>&1; then
    echo "READING: WEB_JOBS_FILTER_MODE=real-jq (脚本自己的过滤器跑在真实形状的 jobs JSON 上)"
else
    echo "READING: WEB_JOBS_FILTER_MODE=fallback (本机无 jq ⇒ 直给结论; 「取第一个 attest 步骤」那个坑本轮未覆盖)"
fi

CAND_FULL=$(printf '4242\t%s\t8388608\t99001' "$FIX_SHA")
web_probe() {   # $1=期望 rc  $2=用例名  $3=候选 tsv
    local want=$1 name=$2 tsv=$3 rc=0
    set +e
    STUB_CANDIDATE_TSV="$tsv" STUB_JOBS_JSON="$TMP_ROOT/jobs.json" \
    STUB_SHIP_CONCLUSION="${STUB_SHIP_CONCLUSION:-}" STUB_GH_JOBS_RC="${STUB_GH_JOBS_RC:-0}" \
    PATH="$TMP_ROOT/bin:$PATH" \
        bash "$REPO_DIR/scripts/deploy/release-web-ci-artifact.sh" --probe-only \
            > "$TMP_ROOT/out.log" 2>&1
    rc=$?
    set -e
    [ "$rc" -eq "$want" ] \
        || { sed -n '1,25p' "$TMP_ROOT/out.log" >&2; fail "8: $name 期望 rc=$want 实得 rc=$rc"; }
}

# 8a 🔴 本票核心: Ship 那步 skipped ⇒ 车道结构性不可用 ⇒ exit 3, 且**没有**报 PROBE_OK。
write_jobs skipped
STUB_SHIP_CONCLUSION=skipped web_probe 3 "候选未签名" "$CAND_FULL"
grep -q 'WEB_CI_ARTIFACT_LANE_UNAVAILABLE' "$TMP_ROOT/out.log" \
    || fail "8a: exit 3 了却没打 LANE_UNAVAILABLE —— 调用方看不出这是边界还是故障"
grep -q 'WEB_CI_ARTIFACT_PROBE_OK' "$TMP_ROOT/out.log" \
    && fail "8a: 车道不可用却报 PROBE_OK —— 调用方会照着走完整趟东京往返"
grep -q 'READING: WEB_ATTEST_BUNDLE_STEP=skipped run_id=99001' "$TMP_ROOT/out.log" \
    || { grep 'READING' "$TMP_ROOT/out.log" >&2 || true; fail "8a: 读数不对(要带 run_id, 证明 tsv 第 4 列真被读出来了)"; }
ok "候选未签名 ⇒ exit 3 + LANE_UNAVAILABLE, 且在运输之前(无 PROBE_OK)"

# 8b 正路径: 签上了 ⇒ 照常放行。⛔ 这道检查不许把好制品也拦下来。
write_jobs success
STUB_SHIP_CONCLUSION=success web_probe 0 "候选已签名" "$CAND_FULL"
grep -q 'WEB_CI_ARTIFACT_PROBE_OK' "$TMP_ROOT/out.log" || fail "8b: 正路径没打 PROBE_OK"
ok "候选已签名 ⇒ exit 0 + PROBE_OK(正路径没被这道检查改坏)"

# 8c 三态的第三态: 那一步整个不存在(改名/被删) ⇒ absent ⇒ **不下结论**, 维持改动前的行为。
# ⛔ 不许把「没量到」当成「不可用」—— 那会在 workflow 改名的当天把整条车道判死。
write_jobs ""
STUB_SHIP_CONCLUSION="" web_probe 0 "步骤不存在" "$CAND_FULL"
grep -q 'READING: WEB_ATTEST_BUNDLE_STEP=absent' "$TMP_ROOT/out.log" \
    || { grep 'READING' "$TMP_ROOT/out.log" >&2 || true; fail "8c: 步骤缺席时读数应为 absent"; }
grep -q 'WEB_CI_ARTIFACT_PROBE_OK' "$TMP_ROOT/out.log" || fail "8c: 没量到就该照旧放行"
ok "步骤缺席 ⇒ absent ⇒ 照旧放行(没量到 ≠ 不可用)"

# 8d gh 出错 ⇒ unknown ⇒ 同样照旧放行。
write_jobs skipped
STUB_GH_JOBS_RC=1 STUB_SHIP_CONCLUSION=skipped web_probe 0 "gh 出错" "$CAND_FULL"
grep -q 'READING: WEB_ATTEST_BUNDLE_STEP=unknown' "$TMP_ROOT/out.log" \
    || { grep 'READING' "$TMP_ROOT/out.log" >&2 || true; fail "8d: gh 出错时读数应为 unknown"; }
ok "gh 出错 ⇒ unknown ⇒ 照旧放行"
STUB_GH_JOBS_RC=0

# 8e 阴性对照: tsv 只给 3 列(= 改动前的形状) ⇒ 取不到 run_id ⇒ 这段检查退化成恒 unknown。
# 没有这一条,「8a 绿」与「第 4 列其实没接上、只是别处恰好也退 3」读数相同。
write_jobs skipped
STUB_SHIP_CONCLUSION=skipped web_probe 0 "tsv 缺 run_id 列" "$(printf '4242\t%s\t8388608' "$FIX_SHA")"
grep -q 'READING: WEB_ATTEST_BUNDLE_STEP=unknown run_id=none' "$TMP_ROOT/out.log" \
    || { grep 'READING' "$TMP_ROOT/out.log" >&2 || true; fail "8e: 缺第 4 列时应落成 unknown/run_id=none"; }
ok "阴性对照: tsv 缺 run_id 列 ⇒ 检查退化成 unknown(证明第 4 列是承重的)"

# ---- 9. 调用方必须把 exit 3 与 exit 1 分开说 ----
# 行为上两者都回退本地构建(这是对的), 但日志里必须分得出「该去修」与「这就是边界」。
grep -q 'WEB_CI_ARTIFACT=unavailable' "$WEB_MANIFEST" \
    || fail "9: web_release_build 没有为 rc=3 单独措辞 —— 结构性不可用被折叠进了「这一趟没成」"
grep -q 'ci_rc=\$?' "$WEB_MANIFEST" \
    || fail "9: 调用方没有显式接住退出码(还是 \`cmd || echo\` 的形状, 读不到 rc)"
ok "调用方对 rc=3 单独措辞, 且显式接住退出码"

echo "PASS: ${pass_count} 项断言全部通过"
