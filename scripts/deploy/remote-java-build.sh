#!/usr/bin/env bash
# 把 Cretas Java 发布构建的【Maven 那一步】放到远程构建机 (默认 canada-mini) 上跑, 再把 JAR
# 取回本机。只替代 `mvn clean package`, 不替代任何门禁:
#
#   - 构建的是本机 HEAD 那个提交 (必须已推到 origin), 远端核对 backend tree hash 与本机一致
#   - 同一条 Maven 命令: clean package -Dtest=<selector> -Dcretas.testIncludes=<narrowing>
#   - 取回后本机重算 sha256 + unzip -tqq, 必须等于远端报的 sha256
#   - manifest 仍由 release_manifest_write 在本机写 (clean 树 / 提交 / tree / sha 全照旧),
#     之后的 stage / validate / deploy / RELEASE_FINAL_STATUS 判据一行不改
#
# 由 release-jar-manifest.sh 的 release_manifest_build 在 CRETAS_RELEASE_REMOTE_BUILD_HOST 非空时
# 调用 (release-cretas.sh --remote-build <host> 会导出它)。也可单独跑来测远端:
#   scripts/deploy/remote-java-build.sh --host canada-mini --tests <selector> --pull-to <jar>
#
# 输出: stdout 上一组 REMOTE_*= / PULL_*= 行。退出码: 0 成功 / 1 失败 / 2 用法错 /
#       3 远端前置条件拒绝 (时间窗 / 内存 / 忙 / 提交未推), 原因在 REMOTE_BUILD_REFUSED 行
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
WORKER="$SCRIPT_DIR/remote-build/remote-java-build-worker.sh"
# ssh_local_path: Windows 盘符路径会被 rsync 当成主机名。
source "$PROJECT_ROOT/scripts/lib/deploy-common.sh"

HOST=
REPO_ROOT=$PROJECT_ROOT
TESTS=
INCLUDES=
PULL_TO=
BASIS=
STAGE_FROM_REMOTE=${CRETAS_REMOTE_BUILD_STAGE_FROM_REMOTE:-0}
REMOTE_ROOT='~/cretas-build'
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 -o ServerAliveCountMax=6)

usage() {
    sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Usage: remote-java-build.sh --host <ssh-alias> --tests <selector> --pull-to <local-jar>
                            [--includes <narrowing>] [--repo-root <dir>] [--basis <jar>]
                            [--stage-from-remote]
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --host) HOST=${2:-}; shift 2 ;;
        --repo-root) REPO_ROOT=${2:-}; shift 2 ;;
        --tests) TESTS=${2:-}; shift 2 ;;
        --includes) INCLUDES=${2:-}; shift 2 ;;
        --pull-to) PULL_TO=${2:-}; shift 2 ;;
        --basis) BASIS=${2:-}; shift 2 ;;
        --stage-from-remote) STAGE_FROM_REMOTE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[ -n "$HOST" ] && [ -n "$TESTS" ] && [ -n "$PULL_TO" ] || { usage >&2; exit 2; }
case "$HOST" in *[!A-Za-z0-9._@-]*|-*) echo "ERROR: invalid --host: $HOST" >&2; exit 2 ;; esac
# 参数要原样穿过远端登录 shell (mini 是 zsh), 单引号包裹; 拒绝任何能逃出单引号或换行的字符。
for value in "$TESTS" "$INCLUDES"; do
    case "$value" in *"'"*|*$'\n'*|*$'\r'*|*'\'*) echo "ERROR: unsupported character in test selector" >&2; exit 2 ;; esac
done
[ -f "$WORKER" ] || { echo "ERROR: worker missing: $WORKER" >&2; exit 1; }
if [ -z "$INCLUDES" ]; then
    source "$SCRIPT_DIR/release-jar-manifest.sh"
    INCLUDES=$(release_manifest_test_includes "$TESTS")
fi

COMMIT=$(git -C "$REPO_ROOT" rev-parse HEAD) || exit 1
TREE=$(git -C "$REPO_ROOT" rev-parse "HEAD:backend/java/cretas-api") || exit 1

# 1. 上传 worker 到内容寻址的文件名, 再核对远端哈希 (防 CRLF / 截断)。
worker_sha=$(sha256sum "$WORKER" | awk '{print $1}')
remote_worker="$REMOTE_ROOT/worker-$worker_sha.sh"
ssh "${SSH_OPTS[@]}" "$HOST" "mkdir -p $REMOTE_ROOT && cat > $remote_worker" < "$WORKER" \
    || { echo "ERROR: cannot upload worker to $HOST" >&2; exit 1; }
got_sha=$(ssh "${SSH_OPTS[@]}" "$HOST" "/usr/bin/shasum -a 256 $remote_worker" </dev/null | awk '{print $1}')
[ "$got_sha" = "$worker_sha" ] \
    || { echo "ERROR: worker hash mismatch on $HOST (got ${got_sha:-none})" >&2; exit 1; }

# 2. 远端构建。stdin 接 /dev/null, 远端任何读 stdin 的东西都拿不到本机输入。
echo "REMOTE_BUILD_HOST=$HOST commit=$COMMIT tree=$TREE"
out_log=$(mktemp)
trap 'rm -f "$out_log"' EXIT
ssh "${SSH_OPTS[@]}" "$HOST" \
    "/bin/bash $remote_worker '$COMMIT' '$TREE' '$TESTS' '$INCLUDES' '$STAGE_FROM_REMOTE'" \
    </dev/null > "$out_log"
rc=$?
cat "$out_log"
if [ "$rc" -ne 0 ]; then
    if grep -q '^REMOTE_BUILD_REFUSED ' "$out_log"; then
        echo "ERROR: remote build refused on $HOST: $(grep '^REMOTE_BUILD_REFUSED ' "$out_log" | head -1)" >&2
        exit 3
    fi
    echo "ERROR: remote build failed on $HOST (rc=$rc)" >&2
    exit 1
fi

field() { sed -n "s/^$1=//p" "$out_log" | tail -1; }
[ "$(field REMOTE_BUILD_OK)" = 1 ] || { echo "ERROR: remote build gave no REMOTE_BUILD_OK" >&2; exit 1; }
remote_jar=$(field REMOTE_JAR_PATH)
remote_sha=$(field REMOTE_JAR_SHA256)
[[ "$remote_sha" =~ ^[0-9a-f]{64}$ ]] || { echo "ERROR: invalid remote sha256: $remote_sha" >&2; exit 1; }
case "$remote_jar" in /*.jar) ;; *) echo "ERROR: invalid remote jar path: $remote_jar" >&2; exit 1 ;; esac

# 3. 取回。用本机已有的上一份 JAR 当 rsync 基准: fat jar 里的依赖 jar 是 STORED 的,
#    两次构建间大部分字节相同, 实测 186MB 只传 ~30MB (见 stage-backend-artifact.sh 同理)。
mkdir -p "$(dirname "$PULL_TO")" || exit 1
rm -f "$PULL_TO"
if [ -n "$BASIS" ] && [ -f "$BASIS" ]; then
    cp -f "$BASIS" "$PULL_TO" 2>/dev/null || rm -f "$PULL_TO"
fi
pull_started=$(date +%s)
rsync -az --stats --timeout=120 "$HOST:$remote_jar" "$(ssh_local_path "$PULL_TO")" > "$out_log.rsync" 2>&1
pull_rc=$?
literal=$(sed -nE 's/^Literal data: ([0-9,]+) bytes.*/\1/p' "$out_log.rsync" | head -1)
rm -f "$out_log.rsync"
if [ "$pull_rc" -ne 0 ]; then
    rm -f "$PULL_TO"
    echo "ERROR: rsync pull from $HOST failed (rc=$pull_rc)" >&2
    exit 1
fi
printf 'PULL_SECONDS=%s\n' "$(( $(date +%s) - pull_started ))"
printf 'PULL_LITERAL_BYTES=%s\n' "${literal//,/}"

local_sha=$(sha256sum "$PULL_TO" | awk '{print tolower($1)}')
[ "$local_sha" = "$remote_sha" ] \
    || { rm -f "$PULL_TO"; echo "ERROR: pulled JAR sha256 $local_sha != remote $remote_sha" >&2; exit 1; }
unzip -tqq "$PULL_TO" >/dev/null 2>&1 \
    || { rm -f "$PULL_TO"; echo "ERROR: pulled JAR failed unzip -tqq" >&2; exit 1; }
printf 'PULL_SHA256_VERIFIED=%s\n' "$local_sha"
exit 0
