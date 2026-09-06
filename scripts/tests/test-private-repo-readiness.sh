#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
EMBEDDING="$ROOT_DIR/scripts/deploy/deploy-embedding.sh"
MODEL="$ROOT_DIR/scripts/deploy/deploy-embedding-model.sh"
PULL_JAR="$ROOT_DIR/scripts/pull-jar.sh"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
KB_WORKFLOW="$ROOT_DIR/.github/workflows/kb-drift-check.yml"
E2E_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-pr.yml"
POST_DEPLOY_WORKFLOW="$ROOT_DIR/.github/workflows/e2e-post-deploy.yml"
TOOL_AUDIT_WORKFLOW="$ROOT_DIR/.github/workflows/tool-isolation-audit.yml"
DAILY_WORKFLOW="$ROOT_DIR/.github/workflows/daily-integration.yml"
THRESHOLD_WORKFLOW="$ROOT_DIR/.github/workflows/threshold-parity-check.yml"

assert_contains() {
    local file=$1
    local expected=$2
    grep -Fq -- "$expected" "$file" || {
        echo "FAIL: missing '$expected' in $file" >&2
        exit 1
    }
}

assert_not_contains() {
    local file=$1
    local unexpected=$2
    if grep -Fq -- "$unexpected" "$file"; then
        echo "FAIL: unexpected '$unexpected' in $file" >&2
        exit 1
    fi
}

# Embedding JARs use SSH transport and never require a GitHub token on prod.
assert_contains "$EMBEDDING" 'REMOTE_INCOMING="$SERVER_DIR/$JAR_NAME.incoming.$VERSION"'
assert_contains "$EMBEDDING" 'EMBEDDING_DIR="$PROJECT_ROOT/backend/java/embedding-service"'
assert_contains "$EMBEDDING" 'SERVER_DIR="/www/wwwroot/cretas/embedding-service"'
# ⚠️ 原文钉的是字面量 `rsync -az --progress "$JAR_PATH" ...` / `scp "$JAR_PATH" ...`。
# 两处的 `"$JAR_PATH"` 都被**刻意**换成了 `"$(ssh_local_path "$JAR_PATH")"`:
# rsync/scp 按 `host:path` 解析参数, 于是 `C:/Users/x.jar` 里的 `C` 会被当成主机名,
# 传输直接失败 —— 而且是**静默**的(上传竞速里另一条通道会赢, 操作者只看到
# 「胜出: scp」和一次变慢的部署)。见 deploy-common.sh 里 ssh_local_path 的说明,
# 以及 deploy-embedding.sh 第 14 行那句注释。
#
# ⇒ 旧断言钉的是一个被有理由地替换掉的实现细节。改断言不是删断言(形态 C‴):
#   抬成「rsync 是主通道 / scp 是兜底 / 两者都必须经过 Windows 路径归一化」,
#   并且**归一化缺一处就红** —— 那才是当初修的那个 bug。
assert_contains "$EMBEDDING" 'rsync -az --progress "$(ssh_local_path "$JAR_PATH")" "$SERVER:$REMOTE_INCOMING"'
assert_contains "$EMBEDDING" 'scp "$(ssh_local_path "$JAR_PATH")" "$SERVER:$REMOTE_INCOMING"'
assert_contains "$EMBEDDING" 'source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"'
# rsync 必须排在 scp 前面(主通道 / 兜底), ⛔ 顺序反了就是每次都走慢通道。
embed_rsync_line=$(grep -Fn -- 'rsync -az --progress "$(ssh_local_path' "$EMBEDDING" | head -1 | cut -d: -f1)
embed_scp_line=$(grep -Fn -- 'scp "$(ssh_local_path' "$EMBEDDING" | head -1 | cut -d: -f1)
[ -n "$embed_rsync_line" ] && [ -n "$embed_scp_line" ] \
    || { echo "FAIL: 取不到 deploy-embedding.sh 的传输行号" >&2; exit 1; }
[ "$embed_rsync_line" -lt "$embed_scp_line" ] \
    || { echo "FAIL: deploy-embedding.sh 里 scp 排在 rsync 前面 —— 兜底通道成了主通道" >&2; exit 1; }
# 归一化不是可选的: **每一条**搬 $JAR_PATH 的 rsync/scp 都必须包 ssh_local_path。
# ⛔ 不写「有没有裸的」那种否定式正则 —— 第一版就是这么写的, 而 `[^|]*"\$JAR_PATH"`
#    连包好的那两行也一起命中(ssh_local_path "$JAR_PATH" 里面也有 "$JAR_PATH"),
#    于是它对**任何**写法都报红。改成正面枚举: 先取全部传输行, 再逐行要求它合规。
transfer_lines=$(grep -nE '^[[:space:]]*(&&[[:space:]]+)?(rsync|scp)[[:space:]]' "$EMBEDDING" \
    | grep -F 'JAR_PATH' || true)
transfer_count=$(printf '%s' "$transfer_lines" | grep -c . || true)
# 硬约束 9: 「没有裸传输」必须配一个「一定会发生」的读数 —— 一条都没采到时,
# 「全都合规」和「正则失效了」长得一模一样。
[ "$transfer_count" -eq 2 ] \
    || { echo "FAIL: 预期恰好 2 条 JAR 传输行(rsync 主 + scp 兜底), 实际 $transfer_count 条:" >&2
         printf '%s\n' "$transfer_lines" >&2; exit 1; }
echo "EMBEDDING_TRANSFER_LINES=$transfer_count"
while IFS= read -r tl; do
    [ -n "$tl" ] || continue
    case "$tl" in
        *'ssh_local_path'*) : ;;
        *) echo "FAIL: 这条传输没经 ssh_local_path, Windows 盘符会被当主机名: $tl" >&2; exit 1 ;;
    esac
done <<< "$transfer_lines"
assert_contains "$EMBEDDING" 'systemctl restart "$SERVICE_NAME"'
assert_not_contains "$EMBEDDING" 'ghproxy.cc'
assert_not_contains "$EMBEDDING" 'gh release create'
assert_not_contains "$EMBEDDING" 'nohup java -jar'

# Model upload keeps SSH candidates but must not attempt anonymous private assets.
assert_contains "$MODEL" 'REPO_PRIVATE=$(gh api "repos/$REPO" --jq '\''.private'\'''
assert_contains "$MODEL" 'private repo 的匿名镜像无法下载 Release asset'

# The legacy server pull path is explicitly authenticated for private releases.
assert_contains "$PULL_JAR" 'gh release download "$VERSION"'
assert_not_contains "$PULL_JAR" 'ghproxy.cc'

# GitHub Actions are an explicit independent fallback. Daily integration,
# pull-request checks and release gates run locally, so no workflow may consume
# remote runners automatically.
for workflow in \
    "$CI_WORKFLOW" \
    "$DAILY_WORKFLOW" \
    "$E2E_WORKFLOW" \
    "$POST_DEPLOY_WORKFLOW" \
    "$KB_WORKFLOW" \
    "$THRESHOLD_WORKFLOW" \
    "$TOOL_AUDIT_WORKFLOW"; do
    assert_contains "$workflow" '  workflow_dispatch:'
    assert_not_contains "$workflow" '  push:'
    assert_not_contains "$workflow" '  pull_request:'
    assert_not_contains "$workflow" '  schedule:'
done

# ⚠️ 2026-08-27 commit e0f7a6dbcb35 (#175): 打包整体搬去 java-artifact.yml,
# ci.yml 的 `package_java_artifact:` 这个 dispatch 输入随之删除, 制品的
# `retention-days: 1` 也跟着搬走了。两条断言从此钉在不存在的东西上。
#
# 守的性质没变 —— ①打包与那条一个多小时的全量审计**解耦**; ②部署制品**只留 1 天**
# (它每个 commit 一份, 留久了就是白烧配额)。⇒ 断言跟着制品走。
assert_contains "$CI_WORKFLOW" 'full_audit:'
assert_contains "$CI_WORKFLOW" 'if: inputs.full_audit'
assert_not_contains "$CI_WORKFLOW" 'dorny/paths-filter'
ARTIFACT_NAME_LITERAL='name: cretas-java-${{ github.sha }}'
artifact_owners=$(grep -Fl -- "$ARTIFACT_NAME_LITERAL" "$ROOT_DIR"/.github/workflows/*.yml 2>/dev/null | LC_ALL=C sort || true)
artifact_owner_count=$(printf '%s' "$artifact_owners" | grep -c . || true)
[ "$artifact_owner_count" = "1" ] \
    || { echo "FAIL: 恰好应有 1 个 workflow 上传 '$ARTIFACT_NAME_LITERAL', 实际 $artifact_owner_count 个" >&2; exit 1; }
ARTIFACT_WORKFLOW="$artifact_owners"
echo "ARTIFACT_WORKFLOW=$(basename "$ARTIFACT_WORKFLOW")"
# ⚠️ ⛔ 不能用 `grep -F 'retention-days: 1'` —— 它把 `retention-days: 14` 也算命中。
#    实测: 把 1 改成 14 的变异**不红**(HITS_AFTER 仍是 1)。锚到行尾才量得准。
grep -Eq '^[[:space:]]*retention-days: 1[[:space:]]*$' "$ARTIFACT_WORKFLOW" \
    || { echo "FAIL: $(basename "$ARTIFACT_WORKFLOW") 里没有 'retention-days: 1'(exact): $(grep -n 'retention-days' "$ARTIFACT_WORKFLOW")" >&2; exit 1; }
# 解耦: 那条一个多小时的全量审计不许再带着打包一起跑。
assert_not_contains "$CI_WORKFLOW" 'name: Package exact-commit deploy artifact'
assert_not_contains "$KB_WORKFLOW" '  schedule:'
assert_not_contains "$CI_WORKFLOW" 'retention-days: 14'
assert_not_contains "$E2E_WORKFLOW" 'retention-days: 14'
assert_not_contains "$POST_DEPLOY_WORKFLOW" 'retention-days: 14'
assert_not_contains "$POST_DEPLOY_WORKFLOW" 'retention-days: 7'
assert_contains "$TOOL_AUDIT_WORKFLOW" 'retention-days: 3'

echo "PASS: private repository deployment contracts are present"
