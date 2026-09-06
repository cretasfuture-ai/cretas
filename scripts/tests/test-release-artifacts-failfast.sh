#!/usr/bin/env bash
# Contracts for the parallel release artifact build:
#   1. A missing/unusable JDK is rejected before either build starts, instead of
#      being discovered ~1s in while the Web build burns another 60-110s.
#   2. When one side fails, the in-flight sibling is cancelled immediately rather
#      than run to completion for an already-doomed release.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
ARTIFACTS="$ROOT_DIR/scripts/deploy/release-cretas-artifacts.sh"
JAVA_PREFLIGHT="$ROOT_DIR/scripts/deploy/release-java-preflight.sh"
TMP_ROOT=$(mktemp -d)
trap 'rm -rf "$TMP_ROOT"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

TARGET_TESTS='ReleaseDecisionToolTest'

# ---------------------------------------------------------------------------
# 前提探测: 这台机器上有没有**发布级**的 JDK (21+)?
#
# 🔴 2026-09-06 实测: ubuntu-24.04 runner 的默认 JAVA_HOME 指向 **Temurin 17**
#    (runner image readme: `17.0.20+1 (default)`; 21 只以 JAVA_HOME_21_X64 存在,
#     而本 workflow 刻意不装 JDK —— 它只做源码级扫描)。
#    release-java-preflight.sh 要求 21+, 于是下面那条**阳性对照**
#    (「当前环境必须能过, 否则这道闸就是噪音」) 在 CI 上必然红, 报的是
#    「preflight rejected the current, working JDK」—— 措辞把「这台机器的 JDK 太老」
#    说成了「预检坏了」。
#    容器里用一个假装是 17 的 JAVA_HOME 复现过, 报错逐字相同。
#
# ⇒ 这不是脚本坏了, 也不是「没问题」, 是**这次量不了**(硬约束 4 的三态)。
#    量得了的部分照常量, 量不了的部分在文末以 rc=77 + `SKIPPED:` 如实上报。
#    ⛔ 不 return 0 假装绿, ⛔ 也不整份跳过。
# ---------------------------------------------------------------------------
probe_release_jdk_major() {
    local java_cmd= raw
    if [ -n "${JAVA_HOME:-}" ]; then
        if [ -x "$JAVA_HOME/bin/java" ]; then java_cmd="$JAVA_HOME/bin/java"
        elif [ -x "$JAVA_HOME/bin/java.exe" ]; then java_cmd="$JAVA_HOME/bin/java.exe"
        else echo "none JAVA_HOME=$JAVA_HOME(无可执行 bin/java)"; return; fi
    elif command -v java >/dev/null 2>&1; then
        java_cmd=$(command -v java)
    else
        echo "none 无 JAVA_HOME 且 PATH 上没有 java"; return
    fi
    raw=$("$java_cmd" -version 2>&1 | sed -nE '1s/.*version "([0-9]+)(\.[0-9]+)*.*/\1/p')
    case "$raw" in ''|*[!0-9]*) echo "none $java_cmd(版本读不出来)"; return ;; esac
    echo "$raw $java_cmd"
}
read -r JDK_MAJOR JDK_WHERE <<<"$(probe_release_jdk_major)"
echo "JDK_PROBE=major=$JDK_MAJOR where=$JDK_WHERE"
SKIP_REASON=""
if [ "$JDK_MAJOR" = "none" ] || [ "$JDK_MAJOR" -lt 21 ] 2>/dev/null; then
    SKIP_REASON="发布级 JDK 不可用 (探到 major=$JDK_MAJOR, 需要 >=21; $JDK_WHERE) —— 预检的阳性对照与 locale 段在此环境不可判定"
fi

# ---------------------------------------------------------------- JDK preflight
# A bogus JAVA_HOME must be rejected by the read-only preflight, not by Maven
# after the Web build has already been started.
# ⚠️ 这条**不需要**真 JDK: 它就是要证明「JAVA_HOME 指到不存在的地方会被拒」,
#    所以无论上面探到什么都照常跑(阴性对照不该被跳过)。
if JAVA_HOME="$TMP_ROOT/no-such-jdk" bash "$JAVA_PREFLIGHT" \
    --repo-root "$ROOT_DIR" --tests "$TARGET_TESTS" >"$TMP_ROOT/jdk.log" 2>&1; then
    fail "preflight accepted a JAVA_HOME that has no runnable java"
fi
grep -Fq 'JAVA_HOME' "$TMP_ROOT/jdk.log" \
    || fail "bogus JAVA_HOME was rejected without naming JAVA_HOME: $(cat "$TMP_ROOT/jdk.log")"

if [ -z "$SKIP_REASON" ]; then
    # The real environment must still pass, otherwise the guard is useless noise.
    bash "$JAVA_PREFLIGHT" --repo-root "$ROOT_DIR" --tests "$TARGET_TESTS" >/dev/null 2>&1 \
        || fail "preflight rejected the current, working JDK (JDK_PROBE major=$JDK_MAJOR)"
    echo "  ok - 阳性对照: 当前 JDK $JDK_MAJOR 能过预检"
else
    echo "  -- 跳过阳性对照: $SKIP_REASON"
fi

# ------------------------------------------------------------ locale hardening
# The import resolver classifies a package segment as a class name by testing
# for a leading capital. A `[A-Z]` RANGE is collation-dependent: under
# en_US.UTF-8 it also matches lowercase, so `com` in `com.cretas.aims.Foo` is
# read as the class name and every such import is falsely reported unresolvable.
# That turned any release whose selector imports project classes into a preflight
# rejection unless the operator remembered LC_ALL=C.
locale_probe="$ROOT_DIR/backend/java/cretas-api/src/test/java"
# `grep | head` would kill grep with SIGPIPE and trip pipefail; collect then slice.
locale_test_file=$(grep -rl '^import com\.cretas\.aims\.' "$locale_probe" --include=*.java 2>/dev/null || true)
locale_test_file=${locale_test_file%%$'\n'*}
[ -n "$locale_test_file" ] \
    || fail "no test class with project imports available to exercise the locale path"
locale_test_class=$(basename "$locale_test_file" .java)

# ⚠️ 这一段也要真的跑一遍 preflight ⇒ 同样需要 21+。JDK 不达标时它会**先**死在
#    require_release_jdk 上, 根本走不到 collation 那段, 读数无意义。
if [ -z "$SKIP_REASON" ]; then
    for probe_locale in en_US.UTF-8 C; do
        if ! LC_ALL="$probe_locale" LANG="$probe_locale" bash "$JAVA_PREFLIGHT" \
            --repo-root "$ROOT_DIR" --tests "$locale_test_class" >"$TMP_ROOT/locale.log" 2>&1; then
            fail "preflight failed under $probe_locale: $(head -2 "$TMP_ROOT/locale.log")"
        fi
        if grep -Fq 'cannot be resolved' "$TMP_ROOT/locale.log"; then
            fail "preflight falsely reported an unresolvable import under $probe_locale: $(grep -F 'cannot be resolved' "$TMP_ROOT/locale.log" | head -1)"
        fi
    done
    echo "  ok - locale 加固: en_US.UTF-8 与 C 两种 collation 下都不误报 import 无法解析"
else
    echo "  -- 跳过 locale 加固: $SKIP_REASON"
fi

# ---------------------------------------------------------------- fixture setup
# Each case gets a fresh clean git repo with stubbed child builders, so the
# orchestration contract is tested without invoking Maven or Vite.
make_fixture() {
    local name=$1 java_body=$2 web_body=$3
    local repo="$TMP_ROOT/$name"

    mkdir -p "$repo/scripts/deploy"
    cp "$ARTIFACTS" "$repo/scripts/deploy/release-cretas-artifacts.sh"
    cat > "$repo/scripts/deploy/release-java-preflight.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    printf '%s\n' "$java_body" > "$repo/scripts/deploy/release-jar-manifest.sh"
    printf '%s\n' "$web_body" > "$repo/scripts/deploy/release-web-manifest.sh"
    chmod +x "$repo/scripts/deploy/"*.sh
    (
        cd "$repo"
        git init -q -b main
        git config user.name test
        git config user.email test@example.com
        git add -A
        git commit -qm fixture
    )
    printf '%s\n' "$repo"
}

# The log must live outside the repo: an untracked file inside it would trip the
# builder's own clean-worktree guard before any of this is exercised.
run_log_for() {
    printf '%s/%s.run.log\n' "$TMP_ROOT" "$(basename "$1")"
}

run_case() {
    local repo=$1
    local started elapsed rc
    started=$(date +%s)
    set +e
    (cd "$repo" && bash scripts/deploy/release-cretas-artifacts.sh --tests "$TARGET_TESTS") \
        >"$(run_log_for "$repo")" 2>&1
    rc=$?
    set -e
    elapsed=$(( $(date +%s) - started ))
    printf '%s %s\n' "$rc" "$elapsed"
}

SLOW_SECONDS=12
CANCEL_BUDGET=6

# ------------------------------------------------- java fails, web must cancel
java_fast_fail=$(cat <<'EOF'
#!/usr/bin/env bash
echo "stub java build failed" >&2
exit 1
EOF
)
web_slow=$(cat <<EOF
#!/usr/bin/env bash
sleep $SLOW_SECONDS
touch "\$(dirname "\$0")/../../web-finished.marker"
EOF
)
repo=$(make_fixture java-fails "$java_fast_fail" "$web_slow")
read -r rc elapsed <<<"$(run_case "$repo")"
[ "$rc" -ne 0 ] || fail "java failure did not fail the artifact build"
[ "$elapsed" -lt "$CANCEL_BUDGET" ] \
    || fail "java failed instantly but the run still took ${elapsed}s waiting for Web"
[ ! -f "$repo/web-finished.marker" ] \
    || fail "cancelled Web build ran to completion anyway"
grep -Fq 'cancelling' "$(run_log_for "$repo")" \
    || fail "cancellation was not reported to the operator: $(cat "$(run_log_for "$repo")")"

# ------------------------------------------------- web fails, java must cancel
java_slow=$(cat <<EOF
#!/usr/bin/env bash
sleep $SLOW_SECONDS
touch "\$(dirname "\$0")/../../java-finished.marker"
EOF
)
web_fast_fail=$(cat <<'EOF'
#!/usr/bin/env bash
echo "stub web build failed" >&2
exit 1
EOF
)
repo=$(make_fixture web-fails "$java_slow" "$web_fast_fail")
read -r rc elapsed <<<"$(run_case "$repo")"
[ "$rc" -ne 0 ] || fail "web failure did not fail the artifact build"
[ "$elapsed" -lt "$CANCEL_BUDGET" ] \
    || fail "web failed instantly but the run still took ${elapsed}s waiting for Java"
[ ! -f "$repo/java-finished.marker" ] \
    || fail "cancelled Java build ran to completion anyway"

# ------------------------------------------------------ both succeed, still ok
ok_stub=$(cat <<'EOF'
#!/usr/bin/env bash
echo "stub ok $*"
EOF
)
repo=$(make_fixture both-ok "$ok_stub" "$ok_stub")
read -r rc elapsed <<<"$(run_case "$repo")"
[ "$rc" -eq 0 ] || fail "both-success run failed: $(cat "$(run_log_for "$repo")")"
grep -Fq 'JAVA_BUILD_WALL_SECONDS=' "$(run_log_for "$repo")" \
    || fail "successful run stopped reporting Java build wall time"
grep -Fq 'WEB_BUILD_WALL_SECONDS=' "$(run_log_for "$repo")" \
    || fail "successful run stopped reporting Web build wall time"
if grep -Fq 'cancelling' "$(run_log_for "$repo")"; then
    fail "successful run reported a cancellation"
fi

# ---------------------------------------------------------------------------
# 如实上报「这次没量到」。⛔ 不能因为跑到这里就 exit 0 —— 那样一台没有 JDK 21
# 的机器会把「预检的阳性对照根本没跑」报成「全绿」。
# rc=77 由 scripts/ci/run-script-tests.sh 判成 SKIPPED, 且它要求这行 `SKIPPED:`
# 必须真的打出来(崩在打印之前的 77 一律算红)。
# ---------------------------------------------------------------------------
if [ -n "$SKIP_REASON" ]; then
    echo "PARTIAL: 跨取消/失败快传那几段照常量了; 预检的 JDK 相关两段没量到"
    echo "SKIPPED:$SKIP_REASON"
    exit 77
fi

echo "PASS: release artifact JDK preflight and cross-cancelling parallel build"
