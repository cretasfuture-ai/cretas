#!/usr/bin/env bash
#
# 把本机可信的 release jar 刷到 Mac mini 测试环境 (canada-mini) 的后端。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么要有它 (2026-09-06)
# ═══════════════════════════════════════════════════════════════════════════
# 测试环境的后端从 8-28 起停在旧 jar 上, 直到 #640 验收时 POST 一个新端点拿到 404
# 才发现「没量到」—— 那次刷新是**手敲**的: scp、两侧 sha、cp -p 备份、覆盖、
# launchctl kickstart、盯 30s health、再去 psql 看 flyway_schema_history。
# 没有脚本 = 每次都可能少一步, 而少掉的那一步 (两侧 sha / flyway 前后读数) 正是
# 「刷上去了」与「刷对了」的区别。
#
# mini 上**没有 mvn/mvnw**、`java` 不在 PATH、checkout 的 origin 指向 runner 的
# _work 目录 ⇒ ⛔ 不在 mini 上构建。输入只能是本机 release 链的产物
# (~/.cache/cretas/java-deploy/current/, 带 release-jar.manifest)。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据 (每一条都对应一个退出码分支, 缺一条就是「刷上去了不知道刷的是什么」)
# ═══════════════════════════════════════════════════════════════════════════
#   ① 本机 jar 与 manifest 自洽 (sha256 相等), 且 manifest.success=true
#   ② manifest.build_commit 的 Java 树 == origin/main 的 Java 树
#      (`git diff --quiet <build_commit> origin/main -- backend/java`)。
#      ⛔ 落后就拒绝: 测试环境的意义是「等于 main」, 刷一个落后的 jar 上去,
#      下一个人会拿它验一个 main 上已经改了的行为 —— 那是形态 A (量错了东西)。
#   ③ 传输后两侧 sha256 相等 (170MB 走 Tailscale, 不比就是在赌)
#   ④ 覆盖前备份 (.bak.<UTC>), 覆盖后再比一次 sha
#   ⑤ kickstart 后 health 200 **且 pid 变了** —— 「health 200」在没重启时也成立,
#      pid 变化才是「重启真的发生了」的那条必发生读数 (硬约束 9)
#   ⑥ flyway_schema_history 最高版本 前/后 各读一次 —— Flyway 在重启时把新迁移
#      打到测试库, 这是「新 jar 真的起来了」的第二条独立证据
#   ⑦ jar 里没有 git.properties (实测), 所以把 manifest 作为 sidecar 装在 jar 旁边
#      (<jar>.manifest), 刷完读回 build_commit —— 下次有人问「测试环境跑的是哪个
#      commit」, 答案在机器上, 不在某人的记忆里
#
# ═══════════════════════════════════════════════════════════════════════════
# 退出码 (开放集合, 判定只测 -eq 0; ⛔ 调用方不要枚举失败码)
# ═══════════════════════════════════════════════════════════════════════════
#   0  REFRESHED / DRY_RUN / ALREADY_CURRENT     (三种都写明是哪一种, ⛔ 不折叠)
#   1  PROBLEM          测试环境上出了问题 (health 超时 / pid 没变 / 安装失败 …)
#   2  INSTRUMENT_DEAD  **没量到** (manifest 读不了 / ssh 不通 / 传输 sha 不一致 /
#                       flyway 读不到 …)。⛔ 不许读成「刷好了」也不许读成「坏了」
#   3  REFUSED          输入不合格 (jar 落后 main / 本机 jar 与 manifest 不符)。
#                       什么都没动。与 1 分开: 1 是「环境有问题」, 3 是「你给的东西不对」
#
# 每次运行**恰好一行**判决写进本机台账 ($RTB_LEDGER), 崩在半路也补一行
# INSTRUMENT_DEAD reason=aborted-before-verdict —— 「台账没涨」不许长成「没跑过」。
#
# 用法:
#   ./scripts/deploy/refresh-testenv-backend.sh                    # dry-run: 预检 + 读远端, 不写
#   ./scripts/deploy/refresh-testenv-backend.sh --confirm YES-TESTENV
#
# 测试: scripts/tests/test-refresh-testenv-backend.sh (假 ssh/scp 桩, 每个 rc=2
# 各构造一次)。下面 RTB_* 变量都是注入点, 默认值就是生产值; 覆盖它们只改变
# 「去哪里找东西」, 不改变判定。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT="${RTB_REPO_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

# 复用发布链的 manifest 解析 (形态 D: 字段名与格式只许有一份真相)。
if [ ! -f "$SCRIPT_DIR/release-jar-manifest.sh" ]; then
    echo "error=missing_helper $SCRIPT_DIR/release-jar-manifest.sh" >&2
    exit 2
fi
# shellcheck source=scripts/deploy/release-jar-manifest.sh
. "$SCRIPT_DIR/release-jar-manifest.sh"

# ── 注入点 ────────────────────────────────────────────────────────────────
JAR_DIR="${RTB_JAR_DIR:-$HOME/.cache/cretas/java-deploy/current}"
SSH_TARGET="${RTB_SSH_TARGET:-canada-mini}"
REMOTE_TARGET_DIR="${RTB_REMOTE_TARGET_DIR:-/Users/rex/cretas-testenv-new/backend/java/cretas-api/target}"
LAUNCHD_LABEL="${RTB_LAUNCHD_LABEL:-com.cretas.testenv.backend}"
REMOTE_PLIST="${RTB_REMOTE_PLIST:-\$HOME/Library/LaunchAgents/com.cretas.testenv.backend.plist}"
HEALTH_URL="${RTB_HEALTH_URL:-http://127.0.0.1:10010/api/mobile/health}"
HEALTH_TIMEOUT="${RTB_HEALTH_TIMEOUT:-150}"
HEALTH_INTERVAL="${RTB_HEALTH_INTERVAL:-5}"
DB_USER="${RTB_DB_USER:-cretas_user}"
MAIN_REF="${RTB_MAIN_REF:-origin/main}"
LEDGER="${RTB_LEDGER:-$HOME/.cache/cretas/java-deploy/refresh-testenv.ledger}"
# 非交互 ssh 到 macOS 的 PATH 没有 /opt/homebrew/bin (psql 在那儿), 每个远端步骤自己钉。
REMOTE_PATH="${RTB_REMOTE_PATH:-/opt/homebrew/bin:/opt/homebrew/opt/openjdk@21/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin}"

CONFIRM=""
ALLOW_STALE_MAIN=0
ALLOW_NEW_INSTALL=0

usage() {
    cat >&2 <<'EOF'
usage: refresh-testenv-backend.sh [--confirm YES-TESTENV] [--jar-dir <dir>] [--target <ssh-host>]
                                  [--health-timeout <s>] [--allow-stale-main]

不带 --confirm 时是 dry-run: 做完全部本机预检 + 读一遍远端状态, 不传、不写、不重启。

  --confirm YES-TESTENV   真的刷 (scp → 备份 → 覆盖 → kickstart → health → flyway 后读数)
  --jar-dir <dir>         可信 jar 目录, 须含 release-jar.manifest (默认 ~/.cache/cretas/java-deploy/current)
  --target <ssh-host>     ssh 别名 (默认 canada-mini)
  --health-timeout <s>    kickstart 后等 health 200 的上限 (默认 150)
  --allow-stale-main      `git fetch origin main` 失败时用本地缓存的 origin/main 继续
                          (⚠️ 会在判决行里标 main_ref=stale; 离线时才用)
  --allow-new-install     远端目标目录里**没有** jar 时也装 (默认 REFUSED: 目录在而 jar 不在,
                          多半 launchd 跑的是别处的 jar, 覆盖这里什么都不会变)

退出码: 0 REFRESHED|DRY_RUN|ALREADY_CURRENT / 1 PROBLEM / 2 INSTRUMENT_DEAD(没量到) / 3 REFUSED
EOF
    exit 2
}

while (($#)); do
    case "$1" in
        --confirm) (($# >= 2)) || usage; CONFIRM=$2; shift 2 ;;
        --jar-dir) (($# >= 2)) || usage; JAR_DIR=$2; shift 2 ;;
        --target) (($# >= 2)) || usage; SSH_TARGET=$2; shift 2 ;;
        --health-timeout) (($# >= 2)) || usage; HEALTH_TIMEOUT=$2; shift 2 ;;
        --allow-stale-main) ALLOW_STALE_MAIN=1; shift ;;
        --allow-new-install) ALLOW_NEW_INSTALL=1; shift ;;
        --dry-run) CONFIRM=""; shift ;;
        -h|--help) usage ;;
        *) echo "error=unknown_argument $1" >&2; usage ;;
    esac
done
case "$HEALTH_TIMEOUT$HEALTH_INTERVAL" in *[!0-9]*|'') echo "error=health_timeout_not_integer" >&2; exit 2 ;; esac
[ "$HEALTH_INTERVAL" -gt 0 ] || { echo "error=health_interval_zero" >&2; exit 2; }

WRITE=0
[ "$CONFIRM" = "YES-TESTENV" ] && WRITE=1

# ── 台账: 每次恰好一行判决 (硬约束 9) ────────────────────────────────────
STAMP="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
WORK="$(mktemp -d)"
mkdir -p "$(dirname "$LEDGER")" 2>/dev/null || true
VERDICT_WRITTEN=0
verdict() {  # $1 = rc, $2.. = 判决行正文
    local rc=$1; shift
    local line="$STAMP $* rc=$rc"
    printf '%s\n' "$line" >> "$LEDGER"
    printf '%s\n' "$line"
    VERDICT_WRITTEN=1
}
on_exit() {
    rm -rf "$WORK"
    if [ "$VERDICT_WRITTEN" != 1 ]; then
        printf '%s INSTRUMENT_DEAD reason=aborted-before-verdict target=%s rc=2\n' "$STAMP" "$SSH_TARGET" >> "$LEDGER"
    fi
}
trap on_exit EXIT

say() { printf '%s\n' "$*"; }
dead() {    # rc=2: 没量到。reason= 是分派键, 后面的行是给人看的。
    local reason=$1; shift
    verdict 2 "INSTRUMENT_DEAD reason=$reason target=$SSH_TARGET${CTX:+ $CTX}"
    local l; for l in "$@"; do [ -n "$l" ] && say "        $l"; done
    exit 2
}
refuse() {  # rc=3: 输入不合格, 什么都没动。
    local reason=$1; shift
    verdict 3 "REFUSED reason=$reason target=$SSH_TARGET${CTX:+ $CTX}"
    local l; for l in "$@"; do [ -n "$l" ] && say "        $l"; done
    exit 3
}
problem() { # rc=1: 测试环境上有问题。
    local reason=$1; shift
    verdict 1 "PROBLEM reason=$reason target=$SSH_TARGET${CTX:+ $CTX}"
    local l; for l in "$@"; do [ -n "$l" ] && say "        $l"; done
    exit 1
}
CTX=""

# ── 0. 本机工具 ───────────────────────────────────────────────────────────
for t in ssh scp git awk; do
    command -v "$t" >/dev/null 2>&1 || dead "local-tool-missing" "本机 PATH 下找不到 $t"
done
local_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print tolower($1)}'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print tolower($1)}'
    else return 1
    fi
}
command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 \
    || dead "local-tool-missing" "本机既没有 sha256sum 也没有 shasum"

# ── 1. manifest 与本机 jar 自洽 ───────────────────────────────────────────
MANIFEST="$JAR_DIR/$RELEASE_MANIFEST_NAME"
[ -f "$MANIFEST" ] || dead "manifest-missing" "找不到 $MANIFEST" \
    "先跑 release 链 (release-cretas.sh / release-jar-manifest.sh build) 产出可信 jar"
mf() { release_manifest_field "$MANIFEST" "$1"; }
FORMAT=$(mf format)           || dead "manifest-invalid" "manifest 缺 format: $MANIFEST"
SUCCESS=$(mf success)         || dead "manifest-invalid" "manifest 缺 success"
BUILD_COMMIT=$(mf build_commit) || dead "manifest-invalid" "manifest 缺 build_commit"
BACKEND_TREE=$(mf backend_tree) || dead "manifest-invalid" "manifest 缺 backend_tree"
JAR_SHA=$(mf jar_sha256)      || dead "manifest-invalid" "manifest 缺 jar_sha256"
JAR_REL=$(mf jar_path)        || dead "manifest-invalid" "manifest 缺 jar_path"
JAR_SHA=$(printf '%s' "$JAR_SHA" | tr '[:upper:]' '[:lower:]')
[ "$FORMAT" = "$RELEASE_MANIFEST_FORMAT" ] || dead "manifest-invalid" "format=$FORMAT (期望 $RELEASE_MANIFEST_FORMAT)"
release_manifest_is_lower_hex "$BUILD_COMMIT" 40 || dead "manifest-invalid" "build_commit 不是 40 位小写 hex: $BUILD_COMMIT"
release_manifest_is_lower_hex "$BACKEND_TREE" 40 || dead "manifest-invalid" "backend_tree 不是 40 位小写 hex"
release_manifest_is_lower_hex "$JAR_SHA" 64      || dead "manifest-invalid" "jar_sha256 不是 64 位 hex"
[ "$JAR_REL" = "$RELEASE_JAR_NAME" ] || dead "manifest-invalid" "jar_path=$JAR_REL (期望 $RELEASE_JAR_NAME)"
CTX="build_commit=${BUILD_COMMIT:0:10}"

[ "$SUCCESS" = "true" ] || refuse "manifest-not-success" "manifest.success=$SUCCESS —— 这不是一次成功构建的产物"
LOCAL_JAR="$JAR_DIR/$JAR_REL"
[ -f "$LOCAL_JAR" ] || dead "jar-missing" "manifest 在但 jar 不在: $LOCAL_JAR"
LOCAL_SHA=$(local_sha256 "$LOCAL_JAR") || dead "local-tool-missing" "算不出本机 sha256"
[ "$LOCAL_SHA" = "$JAR_SHA" ] || refuse "local-jar-sha-mismatch" \
    "manifest 说 $JAR_SHA" "本机 jar 是 $LOCAL_SHA" \
    "缓存目录不自洽 (半截写入? 手动替换过?), ⛔ 不拿一个说不清出处的 jar 去刷"
JAR_SIZE=$(wc -c < "$LOCAL_JAR" | tr -d '[:space:]')

# ── 2. jar 等于 origin/main 的 Java 树 ────────────────────────────────────
git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || dead "git-ref-unresolvable" "$REPO_ROOT 不是 git 工作树"
MAIN_STATE=fresh
REMOTE_NAME=${MAIN_REF%%/*}; REMOTE_BRANCH=${MAIN_REF#*/}
if ! git -C "$REPO_ROOT" fetch -q "$REMOTE_NAME" "$REMOTE_BRANCH" > "$WORK/fetch.err" 2>&1; then
    if [ "$ALLOW_STALE_MAIN" = 1 ]; then
        MAIN_STATE=stale
        say "⚠️ fetch $MAIN_REF 失败, 按 --allow-stale-main 用本地缓存的 ref 继续: $(head -1 "$WORK/fetch.err")"
    else
        dead "fetch-failed" "git fetch $REMOTE_NAME $REMOTE_BRANCH 失败: $(head -2 "$WORK/fetch.err" | tr '\n' ' ')" \
            "「等于 main」只对新鲜的 main 有意义; 离线时显式 --allow-stale-main"
    fi
fi
MAIN_SHA=$(git -C "$REPO_ROOT" rev-parse --verify "$MAIN_REF^{commit}" 2>/dev/null) \
    || dead "git-ref-unresolvable" "解析不了 $MAIN_REF"
git -C "$REPO_ROOT" cat-file -e "${BUILD_COMMIT}^{commit}" 2>/dev/null \
    || dead "build-commit-unknown-locally" "本地仓库没有 build_commit=$BUILD_COMMIT, 判不了它与 main 的关系" \
        "git fetch origin 之后再来; 它若从未推上 origin, 那这个 jar 本来就不该刷"
BUILT_TREE=$(git -C "$REPO_ROOT" rev-parse "${BUILD_COMMIT}:$RELEASE_BACKEND_PATH" 2>/dev/null) \
    || dead "git-ref-unresolvable" "取不到 $BUILD_COMMIT:$RELEASE_BACKEND_PATH"
[ "$BUILT_TREE" = "$BACKEND_TREE" ] || dead "manifest-inconsistent" \
    "manifest.backend_tree=$BACKEND_TREE" "而 git 说 $BUILD_COMMIT:$RELEASE_BACKEND_PATH=$BUILT_TREE" \
    "manifest 自己说的话对不上, 这个输入不可信"
CTX="$CTX main=${MAIN_SHA:0:10} main_ref=$MAIN_STATE"

# 判据 ②。⚠️ 用 backend/java 而不是 backend/java/cretas-api: embedding-service 也在里面。
# `git diff --quiet` 的 rc 是三态: 0 无差异 / 1 有差异 / 其它 = git 自己出错(128 …)。
# ⛔ 只有 1 才是「落后 main」; 其它非 0 是「没量到」, 不许折叠进 REFUSED。
git -C "$REPO_ROOT" diff --quiet "$BUILD_COMMIT" "$MAIN_SHA" -- backend/java 2>"$WORK/diff.err"
DIFF_RC=$?
if [ "$DIFF_RC" -eq 1 ]; then
    git -C "$REPO_ROOT" diff --stat "$BUILD_COMMIT" "$MAIN_SHA" -- backend/java > "$WORK/diff.stat" 2>&1
    N_FILES=$(grep -c '|' "$WORK/diff.stat" || true)
    CTX="$CTX java_diff_files=$N_FILES"
    refuse "jar-behind-main" \
        "jar 构建自 ${BUILD_COMMIT:0:10}, 而 $MAIN_REF=${MAIN_SHA:0:10}, backend/java 相差 $N_FILES 个文件:" \
        "$(tail -1 "$WORK/diff.stat")" \
        "⇒ 先在 main 上重跑 release 链 (prewarm-main-artifact.sh) 产出新 jar, 再来刷"
elif [ "$DIFF_RC" -ne 0 ]; then
    dead "git-ref-unresolvable" "git diff rc=$DIFF_RC: $(head -1 "$WORK/diff.err")"
fi
say "✅ 本机 jar 自洽且等于 $MAIN_REF 的 Java 树: build_commit=${BUILD_COMMIT:0:10} main=${MAIN_SHA:0:10} sha256=${JAR_SHA:0:12} size=$JAR_SIZE"

# ── 远端步骤: 一律 `ssh <host> "RTB_STEP=<name> bash -s"` + 脚本走 stdin ───
# 步骤名放在参数里是**为了可测**: 桩按 $* 分派 (与本仓其它 ssh 桩同形), 真跑时
# 它只是远端 bash 的一个环境变量。脚本走 stdin 是为了在 macOS 的 zsh 登录壳下
# 仍然由 bash 执行 (zsh 会把 `==` 之类当展开)。
REMOTE_JAR="$REMOTE_TARGET_DIR/$RELEASE_JAR_NAME"
REMOTE_TMP="${RTB_REMOTE_TMP:-/tmp/cretas-main-${BUILD_COMMIT:0:8}.jar}"
remote_step() {  # $1 = step 名, stdin = 脚本; stdout 收进 $WORK/<step>.out, rc 返回
    local step=$1
    # ServerAlive: Tailscale 上 health 轮询那一跳可能几分钟没输出, 别让中间设备静默掐断。
    ssh -o BatchMode=yes -o ConnectTimeout=25 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 \
        "$SSH_TARGET" "RTB_STEP=$step bash -s" \
        > "$WORK/$step.out" 2> "$WORK/$step.err"
}
kv() {  # $1 = step, $2 = key → 值 (第一处), 没有则空串
    awk -F= -v k="$2" '$1==k {print substr($0, length(k)+2); exit}' "$WORK/$1.out"
}
remote_preamble() {
    cat <<EOF
set -u
export PATH="$REMOTE_PATH"
D="$REMOTE_TARGET_DIR"; J="$REMOTE_JAR"; T="$REMOTE_TMP"; L="$LAUNCHD_LABEL"; P="$REMOTE_PLIST"; U="$HEALTH_URL"
sha() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "\$1" | awk '{print \$1}'; else sha256sum "\$1" | awk '{print \$1}'; fi; }
uid_=\$(id -u); echo "rtb_uid=\$uid_"
# ⛔ lpid 不再 2>/dev/null: 「Could not find service」被吞掉 ⇒ pid 空 ⇒ 预检过 ⇒ 覆盖之后才发现
#    标签不存在。标签存在性由 llabel 单独判 (rc, 不是 pid 空不空)。
llabel() { if launchctl print "gui/\$uid_/\$L" >/dev/null 2>"\$HOME/.rtb-llabel.err"; then echo ok; else echo "missing rc=\$? \$(head -1 "\$HOME/.rtb-llabel.err" | tr -d '\r')"; fi; }
lpid() { launchctl print "gui/\$uid_/\$L" 2>&1 | awk '/^[[:space:]]*pid = /{print \$3; exit}'; }
health() { local c; c=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "\$U" 2>/dev/null || true); printf '%s\n' "\${c:-000}"; }
flyway() {
    local pw dbn user row
    pw=\$(plutil -extract EnvironmentVariables.DB_PASSWORD raw "\$P" 2>&1) || { echo "flyway_error=plist-db-password: \$pw"; return 1; }
    dbn=\$(plutil -extract EnvironmentVariables.DB_NAME raw "\$P" 2>&1) || { echo "flyway_error=plist-db-name: \$dbn"; return 1; }
    user=\$(plutil -extract EnvironmentVariables.DB_USERNAME raw "\$P" 2>/dev/null) || user="$DB_USER"
    echo "flyway_db=\$dbn"; echo "flyway_user=\$user"
    row=\$(PGPASSWORD="\$pw" psql -h 127.0.0.1 -U "\$user" -d "\$dbn" -At -F '|' \
        -c "SELECT coalesce(max(version),'none'), count(*), coalesce(max(installed_on)::text,'none') FROM flyway_schema_history WHERE success" 2>&1) \
        || { echo "flyway_error=psql: \$(printf '%s' "\$row" | head -1)"; return 1; }
    echo "flyway_max=\${row%%|*}"; row=\${row#*|}; echo "flyway_count=\${row%%|*}"; echo "flyway_installed_on=\$(printf '%s' "\${row#*|}" | tr ' ' 'T')"
}
EOF
}

# ── 3. 远端预检 + 前读数 ──────────────────────────────────────────────────
{
    remote_preamble
    cat <<'EOF'
for t in shasum sha256sum unzip curl launchctl plutil psql; do
    if command -v "$t" >/dev/null 2>&1; then echo "tool_$t=ok"; else echo "tool_$t=missing"; fi
done
[ -d "$D" ] && echo "target_dir=ok" || echo "target_dir=missing"
if [ -f "$J" ]; then echo "remote_jar_sha256=$(sha "$J")"; echo "remote_jar_size=$(wc -c < "$J" | tr -d ' ')"; else echo "remote_jar_sha256=none"; fi
if [ -f "$J.manifest" ]; then echo "remote_sidecar_build_commit=$(awk -F= '$1=="build_commit"{print $2; exit}' "$J.manifest")"; else echo "remote_sidecar_build_commit=none"; fi
if [ -f "$T" ]; then echo "remote_tmp_sha256=$(sha "$T")"; else echo "remote_tmp_sha256=none"; fi
# launchd 标签在不在 (B): 用 launchctl print 的 rc, ⛔ 不用「pid 空不空」—— 那两个长得一样。
echo "launchd_label=$(llabel)"
# plist ↔ jar 钉住 (A): launchd 真的在跑 $J 这个文件吗? ⚠️ 用 xml1 而不是 json —— json 会把 / 转义成 \/,
# 字面 grep 永远 0 (2026-09-06 在真 plist 上实测)。
if plutil -extract ProgramArguments xml1 -o - "$P" 2>/dev/null | grep -qF "$J"; then echo "plist_refers_jar=yes"; else echo "plist_refers_jar=no"; fi
echo "pid_before=$(lpid)"
echo "health_before=$(health)"
flyway || true
echo "precheck_done=1"
EOF
} > "$WORK/precheck.sh"
remote_step precheck < "$WORK/precheck.sh"
PRE_RC=$?
if [ "$PRE_RC" -ne 0 ]; then
    dead "ssh-unreachable" "ssh $SSH_TARGET 失败 (rc=$PRE_RC): $(head -2 "$WORK/precheck.err" | tr '\n' ' ')" \
        "$(head -2 "$WORK/precheck.out" | tr '\n' ' ')"
fi
[ "$(kv precheck precheck_done)" = 1 ] || dead "ssh-unreachable" \
    "远端预检没跑完 (没有 precheck_done): $(head -3 "$WORK/precheck.err" | tr '\n' ' ')"
MISSING_TOOLS=""
for t in curl launchctl plutil psql; do
    [ "$(kv precheck "tool_$t")" = ok ] || MISSING_TOOLS="$MISSING_TOOLS $t"
done
{ [ "$(kv precheck tool_shasum)" = ok ] || [ "$(kv precheck tool_sha256sum)" = ok ]; } || MISSING_TOOLS="$MISSING_TOOLS shasum|sha256sum"
[ -z "$MISSING_TOOLS" ] || dead "remote-tool-missing" "远端 PATH=$REMOTE_PATH 下缺:$MISSING_TOOLS"
[ "$(kv precheck target_dir)" = ok ] || dead "remote-target-dir-missing" "远端没有 $REMOTE_TARGET_DIR"
# (B) launchd 标签不在 ⇒ 没有东西可以 kickstart, 覆盖了也白覆盖 —— 在**任何写之前**停。
LABEL_STATE=$(kv precheck launchd_label)
[ "$LABEL_STATE" = ok ] || dead "launchd-label-missing" \
    "launchctl print gui/<uid>/$LAUNCHD_LABEL: ${LABEL_STATE:-<no reading>}" \
    "标签名或 plist 不对; 装 plist 之前别刷 jar"
# (A) 目录在而 jar 不在 ⇒ launchd 多半在跑别处的 jar, 覆盖这里什么都不会变 ⇒ REFUSED,
#     除非显式 --allow-new-install。plist 里的 ProgramArguments 必须字面引用 $J, 否则同样拒绝。
[ "$(kv precheck plist_refers_jar)" = yes ] || refuse "plist-jar-mismatch" \
    "$REMOTE_PLIST 的 ProgramArguments 里没有 $REMOTE_JAR" \
    "launchd 跑的不是这个文件 —— 刷了也不会生效; 先对齐 RTB_REMOTE_TARGET_DIR / RTB_LAUNCHD_LABEL"
if [ "$(kv precheck remote_jar_sha256)" = none ] && [ "$ALLOW_NEW_INSTALL" != 1 ]; then
    refuse "remote-jar-missing" \
        "$REMOTE_JAR 不存在 (目录在)。这不是常态 —— 要么路径错, 要么有人挪走了" \
        "确认是首次安装再加 --allow-new-install"
fi
FLYWAY_ERR=$(kv precheck flyway_error)
[ -z "$FLYWAY_ERR" ] || dead "flyway-unreadable" "前读数拿不到 flyway_schema_history: $FLYWAY_ERR" \
    "⛔ 没有前读数就没有前后对比, 「新迁移打上了没有」会变成不可判定"
REMOTE_SHA_BEFORE=$(kv precheck remote_jar_sha256)
SIDECAR_BEFORE=$(kv precheck remote_sidecar_build_commit)
PID_BEFORE=$(kv precheck pid_before)
HEALTH_BEFORE=$(kv precheck health_before)
FLY_MAX_BEFORE=$(kv precheck flyway_max); FLY_N_BEFORE=$(kv precheck flyway_count)
TMP_SHA=$(kv precheck remote_tmp_sha256)
say "📡 远端 $SSH_TARGET: jar=${REMOTE_SHA_BEFORE:0:12} sidecar=${SIDECAR_BEFORE:0:10} pid=${PID_BEFORE:-none} health=$HEALTH_BEFORE flyway=$FLY_MAX_BEFORE/$FLY_N_BEFORE (db=$(kv precheck flyway_db) user=$(kv precheck flyway_user))"
CTX="$CTX flyway_before=$FLY_MAX_BEFORE/$FLY_N_BEFORE"

if [ "$REMOTE_SHA_BEFORE" = "$JAR_SHA" ] && [ "$SIDECAR_BEFORE" = "$BUILD_COMMIT" ]; then
    # 同一个 jar 已经在上面 (sha 与 sidecar 双重相等)。⛔ 不重启 —— 重启不是无害的,
    # 正在用测试环境的人会被打断。判决行写明 ALREADY_CURRENT, 不折叠进 REFRESHED。
    verdict 0 "ALREADY_CURRENT $CTX remote_jar_sha256=${JAR_SHA:0:12} pid=${PID_BEFORE:-none} health=$HEALTH_BEFORE"
    exit 0
fi

if [ "$WRITE" != 1 ]; then
    verdict 0 "DRY_RUN $CTX remote_jar_sha256=${REMOTE_SHA_BEFORE:0:12} remote_sidecar=${SIDECAR_BEFORE:0:10} pid=${PID_BEFORE:-none} health=$HEALTH_BEFORE would_upload=$([ "$TMP_SHA" = "$JAR_SHA" ] && echo reuse-tmp || echo scp)"
    say "dry-run: 未传、未写、未重启。要刷: $0 --confirm YES-TESTENV"
    exit 0
fi

# ── 4. 传输 + 两侧 sha (判据 ③) ───────────────────────────────────────────
UPLOAD=scp
if [ "$TMP_SHA" = "$JAR_SHA" ]; then
    UPLOAD=reuse-tmp
    say "⏩ 远端 $REMOTE_TMP 已是同一个 sha, 跳过 170MB 传输"
else
    say "⬆️  scp $LOCAL_JAR → $SSH_TARGET:$REMOTE_TMP ($JAR_SIZE 字节, Tailscale 约 2-3 分钟)"
    T0=$(date +%s)
    scp -o BatchMode=yes -q "$LOCAL_JAR" "$SSH_TARGET:$REMOTE_TMP" > "$WORK/scp.err" 2>&1 \
        || dead "scp-failed" "scp 失败 (rc=$?): $(head -2 "$WORK/scp.err" | tr '\n' ' ')"
    say "   传输耗时 $(( $(date +%s) - T0 ))s"
fi
scp -o BatchMode=yes -q "$MANIFEST" "$SSH_TARGET:$REMOTE_TMP.manifest" > "$WORK/scp2.err" 2>&1 \
    || dead "scp-failed" "scp manifest sidecar 失败: $(head -2 "$WORK/scp2.err" | tr '\n' ' ')"
{
    remote_preamble
    cat <<'EOF'
[ -f "$T" ] && echo "tmp_sha256=$(sha "$T")" || echo "tmp_sha256=none"
[ -f "$T.manifest" ] && echo "tmp_manifest_build_commit=$(awk -F= '$1=="build_commit"{print $2; exit}' "$T.manifest")" || echo "tmp_manifest_build_commit=none"
EOF
} > "$WORK/verify-upload.sh"
remote_step verify-upload < "$WORK/verify-upload.sh" \
    || dead "ssh-unreachable" "传输后校验那一跳 ssh 失败: $(head -2 "$WORK/verify-upload.err" | tr '\n' ' ')"
TMP_SHA=$(kv verify-upload tmp_sha256)
[ "$TMP_SHA" = "$JAR_SHA" ] || dead "transfer-sha-mismatch" \
    "本机 $JAR_SHA" "远端 $REMOTE_TMP = ${TMP_SHA:-none}" \
    "传输损坏或半截; 什么都没装。远端临时文件留着供排查, 重跑会重传。"
[ "$(kv verify-upload tmp_manifest_build_commit)" = "$BUILD_COMMIT" ] || dead "transfer-sha-mismatch" \
    "sidecar manifest 传过去后 build_commit 对不上: $(kv verify-upload tmp_manifest_build_commit)"
say "✅ 两侧 sha256 一致: ${JAR_SHA:0:12} (upload=$UPLOAD)"

# ── 5. 备份 → 覆盖 → 校验 → kickstart → health → 后读数 (判据 ④⑤⑥⑦) ─────
BAK_TS="$(date -u +%Y%m%dT%H%M%SZ)"
ATTEMPTS=$(( HEALTH_TIMEOUT / HEALTH_INTERVAL )); [ "$ATTEMPTS" -ge 1 ] || ATTEMPTS=1
{
    remote_preamble
    cat <<EOF
BAK="\$J.bak.$BAK_TS"; ATTEMPTS=$ATTEMPTS; INTERVAL=$HEALTH_INTERVAL; EXPECT="$JAR_SHA"; PID_BEFORE="$PID_BEFORE"
echo "install_stage=backup"
if [ -f "\$J" ]; then cp -p "\$J" "\$BAK" || { echo "install_failed=backup"; exit 0; }; echo "backup=\$BAK"; else echo "backup=none-no-previous-jar"; fi
echo "install_stage=copy"
cp "\$T" "\$J" || { echo "install_failed=copy"; exit 0; }
cp "\$T.manifest" "\$J.manifest" || { echo "install_failed=copy-manifest"; exit 0; }
echo "install_stage=verify"
GOT=\$(sha "\$J"); echo "installed_sha256=\$GOT"
[ "\$GOT" = "\$EXPECT" ] || { echo "install_failed=verify"; exit 0; }
echo "install_stage=kickstart"
launchctl kickstart -k "gui/\$uid_/\$L" 2>&1 | sed 's/^/kickstart_out=/'
echo "kickstart_rc=\${PIPESTATUS[0]}"
echo "install_stage=health"
# (C) 配对读数 (硬约束 9): 每一轮**先读 pid, 再探 health**, 只有「pid 已不是 PID_BEFORE
# 且这一轮 200」才算新进程答了。⛔ 不是「先等到 200, 事后再读一次 pid」—— 旧进程在
# kickstart -k 生效前那几百毫秒还会答 200, 事后读到的 pid 又已经翻了, 两个读数各自为真,
# 合起来是假的 (审查者在桩下构造出来过)。
i=0; code=000; p=""; paired=no
while [ "\$i" -lt "\$ATTEMPTS" ]; do
    i=\$((i+1)); sleep "\$INTERVAL"
    p=\$(lpid); code=\$(health)
    echo "health_attempt=\$i pid=\${p:-none} code=\$code"
    if [ "\$code" = 200 ] && [ -n "\$p" ] && [ "\$p" != "\$PID_BEFORE" ]; then paired=yes; break; fi
done
echo "health_after=\$code"; echo "health_attempts=\$i"; echo "health_paired=\$paired"
echo "pid_after=\$p"
echo "install_stage=readback"
[ -f "\$J.manifest" ] && echo "sidecar_after=\$(awk -F= '\$1=="build_commit"{print \$2; exit}' "\$J.manifest")" || echo "sidecar_after=none"
if unzip -p "\$J" BOOT-INF/classes/git.properties 2>/dev/null | grep -m1 -oE 'git\.commit\.id(\.full)?=[0-9a-f]{7,40}' >/dev/null; then
    echo "git_properties=\$(unzip -p "\$J" BOOT-INF/classes/git.properties 2>/dev/null | grep -m1 -oE 'git\.commit\.id(\.full)?=[0-9a-f]{7,40}' | cut -d= -f2)"
else
    echo "git_properties=absent"
fi
flyway || true
echo "install_done=1"
EOF
} > "$WORK/install.sh"
say "🔁 备份 → 覆盖 → kickstart $LAUNCHD_LABEL → 等 health 200 (≤${HEALTH_TIMEOUT}s)"
remote_step install < "$WORK/install.sh"
INSTALL_RC=$?
# 装到一半 ssh 断了: 与「应用起不来」是两回事 (memory: readiness 那条 ssh 断线曾被当成
# 死应用回滚)。这里判成没量到, 把远端已到的阶段贴出来, ⛔ 不动远端。
if [ "$INSTALL_RC" -ne 0 ] || [ "$(kv install install_done)" != 1 ]; then
    dead "ssh-dropped-during-install" \
        "ssh rc=$INSTALL_RC, 远端最后到达阶段: $(grep '^install_stage=' "$WORK/install.out" | tail -1 | cut -d= -f2)" \
        "$(head -2 "$WORK/install.err" | tr '\n' ' ')" \
        "⛔ 不要读成「应用起不来」: 先 ssh 上去看 launchctl / health, 再决定重跑还是回滚"
fi
BACKUP=$(kv install backup)
CTX="$CTX backup=$BACKUP upload=$UPLOAD"
FAILED_AT=$(kv install install_failed)
[ -z "$FAILED_AT" ] || problem "install-${FAILED_AT}-failed" \
    "远端在 $FAILED_AT 这一步失败, installed_sha256=$(kv install installed_sha256)" \
    "备份: $BACKUP"
HEALTH_AFTER=$(kv install health_after); ATT=$(kv install health_attempts)
PAIRED=$(kv install health_paired)
PID_AFTER=$(kv install pid_after)
KICK_RC=$(kv install kickstart_rc)
SIDECAR_AFTER=$(kv install sidecar_after)
GITPROPS=$(kv install git_properties)
FLY_ERR_AFTER=$(kv install flyway_error)
FLY_MAX_AFTER=$(kv install flyway_max); FLY_N_AFTER=$(kv install flyway_count)
CTX="$CTX health=$HEALTH_AFTER attempts=$ATT paired=${PAIRED:-none} pid_before=${PID_BEFORE:-none} pid_after=${PID_AFTER:-none}"
# (B) kickstart 自己失败了 (标签在预检时还在, 现在不在 / 权限) —— 打了 rc 就要判它。
[ "${KICK_RC:-0}" = 0 ] || problem "kickstart-failed" \
    "launchctl kickstart -k rc=$KICK_RC: $(grep '^kickstart_out=' "$WORK/install.out" | head -2 | cut -d= -f2- | tr '\n' ' ')" \
    "jar 已覆盖但进程没重启, 跑的还是旧 jar; 备份: $BACKUP"
if [ "$HEALTH_AFTER" != 200 ]; then
    problem "health-timeout" \
        "kickstart 后 $((ATT * HEALTH_INTERVAL))s 内 health 没到 200 (最后 $HEALTH_AFTER), kickstart_rc=$(kv install kickstart_rc)" \
        "看: ssh $SSH_TARGET 'tail -50 ~/testenv-logs/java.err'" \
        "回滚: ssh $SSH_TARGET 'cp \"$BACKUP\" \"$REMOTE_JAR\" && launchctl kickstart -k gui/\$(id -u)/$LAUNCHD_LABEL'"
fi
# (C) 200 必须来自新 pid: 远端循环里「同一轮 pid≠PID_BEFORE 且 200」才置 paired=yes。
#     ⛔ 「health=200 且事后 pid 变了」不是同一件事 —— 旧进程答的 200 + 事后翻的 pid 也长这样。
[ "$PAIRED" = yes ] || problem "health-not-paired-with-new-pid" \
    "远端循环 $ATT 轮内没有一轮同时满足 pid≠$PID_BEFORE 且 200 (最后 pid=${PID_AFTER:-none} code=$HEALTH_AFTER)" \
    "看: ssh $SSH_TARGET 'tail -50 ~/testenv-logs/java.err'; 备份: $BACKUP"
# 判据 ⑤ 的后半: health 200 在**没重启**时也成立。pid 变化才证明 kickstart 真的发生了。
if [ -n "$PID_BEFORE" ] && [ "$PID_BEFORE" = "$PID_AFTER" ]; then
    problem "pid-unchanged-after-kickstart" \
        "health=200 但 pid 仍是 $PID_BEFORE —— 进程没换, 跑的还是旧 jar" \
        "kickstart_rc=$(kv install kickstart_rc) $(grep '^kickstart_out=' "$WORK/install.out" | head -2 | tr '\n' ' ')"
fi
[ -n "$PID_AFTER" ] || problem "no-pid-after-restart" "health=200 却从 launchctl 读不到 pid —— 是别的进程在答 health?"
# 判据 ⑦: 读回出处。sidecar 是我们自己刚放的, 它对不上说明覆盖的不是我们以为的那个文件。
[ "$SIDECAR_AFTER" = "$BUILD_COMMIT" ] || problem "sidecar-readback-mismatch" \
    "读回 $REMOTE_JAR.manifest build_commit=$SIDECAR_AFTER, 期望 $BUILD_COMMIT"
PROV="manifest-sidecar"
if [ "$GITPROPS" != absent ] && [ -n "$GITPROPS" ]; then
    PROV="git-properties"
    case "$BUILD_COMMIT" in "$GITPROPS"*) ;; *) problem "git-properties-mismatch" "jar 里 git.properties=$GITPROPS ≠ build_commit=$BUILD_COMMIT" ;; esac
fi
# 判据 ⑥: 后读数。读不到是「没量到」(rc=2), 但 jar 已经上去了 —— 判决行必须说 deployed=yes。
[ -z "$FLY_ERR_AFTER" ] || dead "flyway-unreadable-after" \
    "deployed=yes health=200 pid=$PID_AFTER, 但后读数拿不到 flyway: $FLY_ERR_AFTER" \
    "⛔ 不要读成「刷坏了」: 应用是活的; 是 flyway 那条读数没拿到, 手工 psql 补一次"
CTX="$CTX flyway_after=$FLY_MAX_AFTER/$FLY_N_AFTER provenance=$PROV"
verdict 0 "REFRESHED $CTX jar_sha256=${JAR_SHA:0:12} installed_on=$(kv install flyway_installed_on)"
[ "$FLY_MAX_AFTER" = "$FLY_MAX_BEFORE" ] && [ "$FLY_N_AFTER" = "$FLY_N_BEFORE" ] \
    && say "   (flyway 前后相同: 这个 jar 没带新迁移, 或迁移已在 —— 不是缺陷, 只是没有第二条证据)"
exit 0
