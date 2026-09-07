#!/usr/bin/env bash
# 取回 CI 已构建、provenance 已签名的 web dist 制品, 落进 web_release_build_reusable 会查的
# by-tree 缓存 —— 让发布窗口里那 86s 的 `npm ci` + `vite build` 变成一次 2s 的缓存命中。
#
# 为什么必须落到【本机】而不是像 Java 那样 Tokyo→OSS→ECS:
# web 部署是从本机 scp 到 139 网关(deploy-web-admin.sh), 目标不是 ECS。dist 字节终归要经过
# 本机, 所以 Java 那条链路对 web 不适用。
#
# 为什么必须经东京而不是直连 GitHub:
# 实测直连 GitHub 到本机 0.05 MB/s —— 8.6MB 要 161s, 比本地重新构建(86s)还慢。经东京取则是
# 秒级。这个结论改过一次设计, 别再试直连。
#
# 与 release-ci-artifact.sh(Java)的关系: 同一套思路(按内容树找制品 / 制品 commit 不必等于
# HEAD / attestation 按制品自己的 commit 验), 但终点和传输后半段不同。
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)

# shellcheck source=scripts/deploy/release-web-manifest.sh
. "$SCRIPT_DIR/release-web-manifest.sh"
# release_manifest_is_lower_hex 住在这里, 且刻意做成两边都 source 的共享件 ——
# `[0-9a-f]{64}` 这种区间在 en_US.UTF-8 下会匹配大写(collation 展开), 所以不能各写各的。
# shellcheck source=scripts/deploy/release-jar-manifest.sh
. "$SCRIPT_DIR/release-jar-manifest.sh"
# shellcheck source=scripts/lib/server-script-common.sh
. "$PROJECT_ROOT/scripts/lib/server-script-common.sh"

# 🔴 2026-09-07: 与 release-ci-artifact.sh 同一处缺陷 —— 默认值原来是旧 fork `Stevenjxie/cretas`,
#    它 main 上没有本仓的制品 ⇒ 候选列表恒空。两个脚本的默认值必须一致(测试里钉了)。
REPO=${CRETAS_RELEASE_REPO:-cretasfuture-ai/cretas}
SIGNER_WORKFLOW="$REPO/.github/workflows/web-dist.yml"
STAGER=${CRETAS_WEB_STAGER:-/usr/local/sbin/github-web-artifact-stage}
CACHE_ENDPOINT=${CRETAS_TOKYO_CACHE_ENDPOINT:-http://10.66.66.1:18081/artifacts}
MAX_CANDIDATES=${CRETAS_WEB_ARTIFACT_MAX_CANDIDATES:-20}
PROBE_ONLY=0

usage() {
    cat >&2 <<'USAGE'
usage: release-web-ci-artifact.sh [--probe-only]

  --probe-only   只判断"存不存在 web_tree 匹配的候选制品", 不传输、不落缓存。
                 用于让调用方廉价决定要不要走这条路; 探测通过【不】保证一定能用。

退出码:
  0  可用(或 --probe-only 下候选存在)
  1  这一趟没成(传输失败/验签不过/manifest 不一致/无匹配制品)
  2  用法错误
  3  **本组织这条车道结构性不可用** —— 候选制品里没有 attestation bundle。
     ⛔ 这一种重试没有意义, 调用方应直接走本地 npm ci + vite build。
     (与 release-ci-artifact.sh 的 exit 3 同义 —— 两条车道的退出码约定刻意一致。)
USAGE
    exit 2
}

while (($#)); do
    case "$1" in
        --probe-only) PROBE_ONLY=1; shift ;;
        -h|--help) usage ;;
        *) usage ;;
    esac
done

fail() { echo "WEB_CI_ARTIFACT_UNAVAILABLE reason=$1" >&2; exit 1; }

# 「这条车道在本组织根本通不了」——与 fail() 的区别是**重试没有意义**。
# ⛔ 不复用 1(那是"这一趟没成", 调用方可以下次再试), 也 ⛔ 不复用 2(那是用法错误)。
# 与 release-ci-artifact.sh 的 exit 3 是同一个约定, 两条车道刻意保持一致。
lane_unavailable() {
    echo "WEB_CI_ARTIFACT_LANE_UNAVAILABLE reason=$1" >&2
    cat >&2 <<'EOF'
  这份候选制品里【没有】attestation bundle ⇒ 第 7 步的 `gh attestation verify` 必然验不过,
  再等、再重试都不会变。
  已知成因(见 .github/workflows/web-dist.yml 里 attest 那步的注释): GitHub 的 artifact
  attestations 只对【公开仓】可用, 私有仓要 Enterprise Cloud; 本组织 plan=free / visibility=private
  ⇒ 签不上, `Ship attestation bundle with the artifact` 那一步恒 skipped, ZIP 里没有 bundle。
  ⇒ 发布会走本地 npm ci + vite build(慢约 86s, 但正确)。这不是故障, 是本组织的边界。
  ⛔ 不要为了省这一分半去放宽第 7 步那道验签: manifest 与 dist 在同一个 ZIP 里, 谁造得出 ZIP
     谁就能自己写「这是 CI 构建的」—— 那道闸 fail-closed 是对的。
EOF
    exit 3
}

command -v gh >/dev/null 2>&1 || fail gh_not_installed
command -v curl >/dev/null 2>&1 || fail curl_not_installed
command -v ssh >/dev/null 2>&1 || fail ssh_not_installed

# ---- 1. 当前 web 树 ----
# 锚 HEAD 而不是 origin/main —— 必须与 web_release_build_reusable 用的锚【一致】, 否则会取回
# 一份消费端根本不会去查的缓存条目。(web_release_validate_cached 锚 origin/main 是因为它服务
# 部署阶段; 两者不能混, 这个区分踩过一次。)
CURRENT_TREE=$(git -C "$PROJECT_ROOT" rev-parse "HEAD:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) \
    || fail cannot_resolve_web_tree
release_manifest_is_lower_hex "$CURRENT_TREE" 40 || fail web_tree_malformed

# ---- 2. 找一份可用制品 ----
# 制品的 commit 【不必】等于 HEAD: web_tree 是内容哈希, 一个只改后端/文档的 commit 之后,
# 上一个 web commit 的制品仍然完全正确 —— web_release_build_reusable 判的也正是树相等。
#
# name == "cretas-web-" + head_sha 这一条不能省: 任何人都能手工上传一个同名制品, 名字对不上
# 说明它不是 web-dist.yml 产出的。
#
# 只认 main: 发布只从 main 出, 候选分支上的制品即便树相同也不该被当作发布依据。
# 过滤全部交给 jq 并用 @tsv —— 少一个解释器就少一层 CRLF 之类的静默翻译
# (先前 Java 那条链用 python 转 TSV, MSYS 下 print() 输出 CRLF 让每个 sha 多一个 \r,
#  长度 41 校验不过, 报出来和"确实没有可用制品"一模一样)。
candidates=$(GH_HTTP_TIMEOUT=20 gh api \
    "repos/$REPO/actions/artifacts?per_page=100" \
    --jq '.artifacts[]
          | select(.expired == false)
          | select(.workflow_run.head_branch == "main")
          | select(.name == "cretas-web-" + .workflow_run.head_sha)
          | [(.id | tostring), .workflow_run.head_sha, (.size_in_bytes | tostring),
             (.workflow_run.id | tostring)]
          | @tsv' 2>/dev/null) \
    || fail gh_api_failed
[ -n "$candidates" ] || fail no_main_artifact_listed

ARTIFACT_ID="" ARTIFACT_COMMIT="" ARTIFACT_ZIP_SIZE="" ARTIFACT_RUN_ID=""
checked=0
# ⚠️ 第 4 列 run_id 是 2026-09-07 为下面 2c 那段签名检查加的。read 的变量个数必须跟着加 ——
# 少一个的话最后一个变量会把剩下的列**连在一起**吞掉(read 的语义), 而症状只是 size 里
# 多了个数字, 长得像"制品变大了"。
while IFS=$'\t' read -r cand_id cand_sha cand_size cand_run; do
    # 防御性剥 CR: 这条链路上任何一环在 Windows 上多写一个 \r, 都会让长度校验静默失败,
    # 而失败长得像"没有可用制品"。
    cand_id=${cand_id%$'\r'}; cand_sha=${cand_sha%$'\r'}; cand_size=${cand_size%$'\r'}
    cand_run=${cand_run%$'\r'}
    [ -n "${cand_id:-}" ] || continue
    [ "$checked" -lt "$MAX_CANDIDATES" ] || break
    checked=$((checked + 1))
    release_manifest_is_lower_hex "$cand_sha" 40 || continue
    # 本机可能还没有那个 commit(制品比本地 fetch 新)。先 cat-file 确认对象在, 否则
    # rev-parse 会报错而我们只想跳过这个候选。
    git -C "$PROJECT_ROOT" cat-file -e "${cand_sha}^{commit}" 2>/dev/null || continue
    cand_tree=$(git -C "$PROJECT_ROOT" rev-parse "${cand_sha}:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) || continue
    [ "$cand_tree" = "$CURRENT_TREE" ] || continue
    ARTIFACT_ID=$cand_id; ARTIFACT_COMMIT=$cand_sha; ARTIFACT_ZIP_SIZE=$cand_size
    ARTIFACT_RUN_ID=$cand_run
    break
done <<< "$candidates"

[ -n "$ARTIFACT_ID" ] || fail no_artifact_matching_current_web_tree
case "$ARTIFACT_ZIP_SIZE" in ''|*[!0123456789]*) fail zip_size_malformed ;; esac

echo "WEB_CI_ARTIFACT_CANDIDATE id=$ARTIFACT_ID commit=$ARTIFACT_COMMIT web_tree=$CURRENT_TREE"

# ---- 2c. 这份候选带签名 bundle 吗 —— 不带就别再往下走 (2026-09-07) ----
#
# 与 release-ci-artifact.sh 那段是**同一道检查**, 只是 Java 那边先落地、web 这边漏了
# (票 OPS-WEB-CI-ARTIFACT-LANE-HALF-FIXED-20260907: 当时只把 REPO 默认值那一处两边拉齐了)。
#
# 为什么在这里就问: 不问的话, 这条路要跑完整趟东京中转(ssh staging + 下载 + 解包)才在
# 第 4 步的 `attestation_absent_in_artifact` 上撞死 —— 白付一趟跨境往返。而且调用方
# (release-web-manifest.sh 的 web_release_build)会把它折叠成一句
# `WEB_CI_ARTIFACT=fallback`, 于是「本组织永远通不了」与「这一趟没成」读数完全相同。
#
# 判据取【产出这份制品的那次 run】里 `Ship attestation bundle …` 那一步的结论 ——
# 它是 `if: steps.attest.outcome == 'success'` 守着的, 签不上就 skipped, ZIP 里也就没有 bundle。
# ⚠️ 不能匹配「名字里有 attest」的第一步: 同一个 job 里 `Attest dist archive provenance`
#    的结论是 **success**(它自己 continue-on-error), 而 Ship 那步是 **skipped** ——
#    两步结论相反, 取第一个会得出完全相反的结论。实测 run 34068075476 就是这个形状。
#    所以只认名字里带 `attestation bundle` 的那一步。
# 🔴 三态, ⛔ 不猜: 读到 success 才继续; 读到别的结论(skipped/failure)才判车道不可用;
#    步骤找不到 / gh 出错 / 空 ⇒ unknown ⇒ **照旧往下走**(维持改动前的行为), 只把读数打出来。
#    (步骤改名会落进 unknown —— 由 test-release-ci-artifact-prewarm.sh 的漂移闸钉住名字。)
ATTEST_BUNDLE=unknown
if [ -n "$ARTIFACT_RUN_ID" ]; then
    ATTEST_BUNDLE=$(GH_HTTP_TIMEOUT=20 gh api \
        "repos/$REPO/actions/runs/$ARTIFACT_RUN_ID/jobs" \
        --jq '[ .jobs[]?.steps[]?
                | select(.name | ascii_downcase | contains("attestation bundle"))
                | .conclusion ] | first // "absent"' 2>/dev/null) || ATTEST_BUNDLE=unknown
    ATTEST_BUNDLE=${ATTEST_BUNDLE%$'\r'}
    [ -n "$ATTEST_BUNDLE" ] || ATTEST_BUNDLE=unknown
fi
echo "READING: WEB_ATTEST_BUNDLE_STEP=$ATTEST_BUNDLE run_id=${ARTIFACT_RUN_ID:-none}"
case "$ATTEST_BUNDLE" in
    success)         ;;   # 签上了 —— 照常走完运输与验签
    unknown|absent)  ;;   # 没量到 ⇒ 不下结论, 让后面的硬断言去判(fail closed 仍在)
    *) lane_unavailable "attestation_bundle_not_shipped(step=$ATTEST_BUNDLE)" ;;
esac

# --probe-only 到此为止。刻意【只】保证「存在 web_tree 匹配的候选」—— manifest 一致性与
# attestation 都要等制品真的送到才能判, 探测不做也不假装做。调用方必须为"晚失败"留回退。
if ((PROBE_ONLY)); then
    echo "WEB_CI_ARTIFACT_PROBE_OK id=$ARTIFACT_ID commit=$ARTIFACT_COMMIT"
    exit 0
fi

# 缓存条目已经在本机且自洽时, 一步网络都不用走。
CACHE_ROOT=$(web_release_cache_root)
TREE_DIR="$CACHE_ROOT/by-tree/$CURRENT_TREE"
if [ -f "$TREE_DIR/$WEB_RELEASE_MANIFEST_NAME" ] && [ -f "$TREE_DIR/$WEB_RELEASE_ARCHIVE_NAME" ]; then
    existing_sha=$(web_release_sha256_file "$TREE_DIR/$WEB_RELEASE_ARCHIVE_NAME" 2>/dev/null || true)
    claimed_sha=$(web_release_manifest_field "$TREE_DIR/$WEB_RELEASE_MANIFEST_NAME" archive_sha256 2>/dev/null || true)
    if [ -n "$existing_sha" ] && [ "$existing_sha" = "$claimed_sha" ]; then
        echo "WEB_CI_ARTIFACT=already-cached web_tree=$CURRENT_TREE"
        exit 0
    fi
fi

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

# ---- 3. 取签名 URL ----
# /zip 端点答 302 指向短时存储 URL。⚠️ 不要用 `gh api --include` 去解析 Location 头 ——
# 多字节内容会让 awk 那套解析挂掉(踩过)。curl 的 %{redirect_url} 直接给出结果, 且 token 与
# location 都不进 argv。
GH_TOKEN_VALUE=$(gh auth token 2>/dev/null) || fail gh_token_unavailable
[ -n "$GH_TOKEN_VALUE" ] || fail gh_token_empty

ZIP_URL=$(curl -sS -o /dev/null -w '%{redirect_url}' \
    -H "Authorization: Bearer $GH_TOKEN_VALUE" \
    -H "Accept: application/vnd.github+json" \
    --max-time 60 \
    "https://api.github.com/repos/$REPO/actions/artifacts/$ARTIFACT_ID/zip") \
    || fail signed_url_request_failed
case "$ZIP_URL" in https://*) ;; *) fail signed_url_not_returned ;; esac

# ---- 4. 东京侧下载 + 解包 + 落缓存 ----
# ⚠️ 不能用 server_script_host_opts 给的那组选项 —— 它含 `-n`(为漂移检查器的 while-read 主循环
# 防吞 stdin 而加)。这里恰恰【需要】stdin 把签名 URL 送进去, 带 -n 会让 stager 读到 EOF 后
# 报 url_missing。所以自己拼一组等价但不含 -n 的。
TOKYO_TARGET=$(server_script_host_target tokyo) || fail tokyo_target_unknown
tokyo_opts=(-o BatchMode=yes -o ConnectTimeout=15
            -i "$HOME/.ssh/ai-egress-tokyo-windows_ed25519" -o IdentitiesOnly=yes)

stage_log="$WORK_DIR/stage.log"
if ! printf '%s\n' "$ZIP_URL" | ssh "${tokyo_opts[@]}" "$TOKYO_TARGET" \
        "sudo -n $STAGER --zip-size $ARTIFACT_ZIP_SIZE" > "$stage_log" 2>"$WORK_DIR/stage.err"; then
    echo "--- tokyo stage log (tail) ---" >&2
    tail -20 "$WORK_DIR/stage.err" >&2 || true
    fail tokyo_stage_failed
fi
ZIP_URL=

field() {
    # 单行 key=value; 出现 0 次或多于 1 次都当失败, 不给静默空值。
    awk -v key="$1" '
        index($0, key "=") == 1 { count++; value = substr($0, length(key) + 2); sub(/\r$/, "", value) }
        END { if (count != 1) exit 1; print value }
    ' "$stage_log"
}

ARCHIVE_SHA=$(field archive_sha256) || fail archive_sha_field_unreadable
ARCHIVE_SIZE=$(field archive_size)  || fail archive_size_field_unreadable
MANIFEST_PRESENT=$(field manifest_present) || fail manifest_present_field_unreadable
ATTEST_PRESENT=$(field attestation_present) || fail attestation_present_field_unreadable

release_manifest_is_lower_hex "$ARCHIVE_SHA" 64 || fail archive_sha_malformed
[ "$MANIFEST_PRESENT" = "true" ] || fail manifest_absent_in_artifact
[ "$ATTEST_PRESENT" = "true" ] || fail attestation_absent_in_artifact

field manifest_b64 > "$WORK_DIR/manifest.b64" || fail manifest_b64_unreadable
base64 -d < "$WORK_DIR/manifest.b64" > "$WORK_DIR/$WEB_RELEASE_MANIFEST_NAME" || fail manifest_b64_undecodable
field attestation_b64 > "$WORK_DIR/attest.b64" || fail attestation_b64_unreadable
base64 -d < "$WORK_DIR/attest.b64" > "$WORK_DIR/attestation.jsonl" || fail attestation_b64_undecodable

# ---- 5. 从东京缓存取字节到本机 ----
started=$(date +%s)
if ! curl -fsS --max-time 300 --connect-timeout 15 \
        -o "$WORK_DIR/$WEB_RELEASE_ARCHIVE_NAME" \
        "$CACHE_ENDPOINT/$ARCHIVE_SHA.bin"; then
    fail cache_fetch_failed
fi
fetch_seconds=$(($(date +%s) - started))

LOCAL_SHA=$(web_release_sha256_file "$WORK_DIR/$WEB_RELEASE_ARCHIVE_NAME") || fail local_sha_failed
[ "$LOCAL_SHA" = "$ARCHIVE_SHA" ] || fail "archive_sha_mismatch(local=$LOCAL_SHA tokyo=$ARCHIVE_SHA)"
local_size=$(wc -c < "$WORK_DIR/$WEB_RELEASE_ARCHIVE_NAME" | tr -d ' ')
[ "$local_size" = "$ARCHIVE_SIZE" ] || fail "archive_size_mismatch(local=$local_size tokyo=$ARCHIVE_SIZE)"

# ---- 6. manifest 逐条硬断言 ----
# 判据: 构建期该拒绝的, 就是部署期会拒绝的那一套。少任何一条, 都会造出一份 build 阶段愉快
# 命中、而 web_release_validate 在部署期 exit 1 且【一个字都不打】的缓存条目。
m() { web_release_manifest_field "$WORK_DIR/$WEB_RELEASE_MANIFEST_NAME" "$1"; }

M_FORMAT=$(m format)             || fail manifest_format_unreadable
M_SUCCESS=$(m success)           || fail manifest_success_unreadable
M_TREE=$(m web_tree)             || fail manifest_tree_unreadable
M_COMMIT=$(m build_commit)       || fail manifest_commit_unreadable
M_ARCHIVE=$(m archive_path)      || fail manifest_archive_path_unreadable
M_ARCHIVE_SHA=$(m archive_sha256) || fail manifest_archive_sha_unreadable

[ "$M_FORMAT" = "$WEB_RELEASE_MANIFEST_FORMAT" ] || fail "manifest_format_mismatch($M_FORMAT)"
[ "$M_SUCCESS" = "true" ]                        || fail "manifest_not_success($M_SUCCESS)"
[ "$M_TREE" = "$CURRENT_TREE" ]                  || fail "manifest_tree_mismatch($M_TREE)"
[ "$M_ARCHIVE" = "$WEB_RELEASE_ARCHIVE_NAME" ]   || fail "manifest_archive_name_mismatch($M_ARCHIVE)"
[ "$M_ARCHIVE_SHA" = "$ARCHIVE_SHA" ]            || fail manifest_archive_sha_mismatch

# build_commit 必须【本地可解析】且其 web 树等于当前树 —— web_release_build_reusable 与
# web_release_validate 都会这么查。实测撞到过: 缓存里那份的 build_commit 来自一个已被删除的
# 临时 clone, build 报"复用成功"2s 返回, validate 随后 exit 1 且一个字都不打。
release_manifest_is_lower_hex "$(printf '%s' "$M_COMMIT" | tr 'A-F' 'a-f')" 40 \
    || fail manifest_commit_malformed
git -C "$PROJECT_ROOT" cat-file -e "${M_COMMIT}^{commit}" 2>/dev/null \
    || fail "manifest_commit_unresolvable_locally($M_COMMIT)"
commit_tree=$(git -C "$PROJECT_ROOT" rev-parse "${M_COMMIT}:$WEB_RELEASE_SOURCE_PATH" 2>/dev/null) \
    || fail manifest_commit_tree_unresolvable
[ "$commit_tree" = "$CURRENT_TREE" ] || fail "manifest_commit_tree_mismatch($commit_tree)"

# archive 自身完整性 + index/asset 引用完整性, 与本地构建走同一把闸。
web_release_verify_archive "$WORK_DIR/$WEB_RELEASE_ARCHIVE_NAME" || fail archive_verification_failed

# ---- 7. attestation ----
# 签名把 vouching 权绑在「那个 commit 上的 workflow 定义」上 —— 传输路径(东京/nginx/本机)
# 没有任何一方能伪造。source-digest 必须是【制品自己的 commit】: 拿 HEAD 去 pin 会验不过,
# 而不 pin 就等于任意 commit 的签名都能用。
if ! gh attestation verify "$WORK_DIR/$WEB_RELEASE_ARCHIVE_NAME" \
        --bundle "$WORK_DIR/attestation.jsonl" \
        --repo "$REPO" \
        --signer-workflow "$SIGNER_WORKFLOW" \
        --source-digest "$ARTIFACT_COMMIT" >"$WORK_DIR/attest.log" 2>&1; then
    echo "--- attestation verify (tail) ---" >&2
    tail -15 "$WORK_DIR/attest.log" >&2 || true
    fail attestation_verification_failed
fi

# ---- 8. 原子落进 by-tree ----
# 先在同一文件系统上建好完整目录再 mv —— 半个目录(有 manifest 没 archive, 或反过来)会让
# web_release_build_reusable 拿到一份自相矛盾的缓存。
stage_dir="$CACHE_ROOT/.by-tree.staging.$$"
old_dir="$CACHE_ROOT/.by-tree.old.$$"
rm -rf "$stage_dir" "$old_dir"
mkdir -p "$stage_dir" || fail staging_mkdir_failed
cp "$WORK_DIR/$WEB_RELEASE_ARCHIVE_NAME" "$stage_dir/$WEB_RELEASE_ARCHIVE_NAME" || fail staging_copy_archive_failed
cp "$WORK_DIR/$WEB_RELEASE_MANIFEST_NAME" "$stage_dir/$WEB_RELEASE_MANIFEST_NAME" || fail staging_copy_manifest_failed
mkdir -p "$CACHE_ROOT/by-tree" || fail by_tree_mkdir_failed
[ ! -d "$TREE_DIR" ] || mv "$TREE_DIR" "$old_dir"
if ! mv "$stage_dir" "$TREE_DIR"; then
    [ ! -d "$old_dir" ] || mv "$old_dir" "$TREE_DIR"
    rm -rf "$stage_dir"
    fail promote_failed
fi
rm -rf "$old_dir"

echo "WEB_CI_ARTIFACT=used id=$ARTIFACT_ID commit=$ARTIFACT_COMMIT web_tree=$CURRENT_TREE"
echo "WEB_CI_ARTIFACT_ARCHIVE sha256=$ARCHIVE_SHA size=$ARCHIVE_SIZE fetch_seconds=$fetch_seconds"
echo "WEB_CI_ARTIFACT_CACHE_DIR=$TREE_DIR"
