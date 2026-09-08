#!/usr/bin/env bash
# 发布级 JDK 的前提探测 —— 被 test-release-artifacts-failfast.sh 与
# test-release-parallel-fastlane.sh 共用。
#
# ⚠️ 放在 scripts/tests/lib/ 而不是 scripts/tests/ 下: run-script-tests.sh 的
#    glob 是 `"$TESTS_DIR"/*.sh`(非递归), 放在上一层会被当成一个"用例"去执行。
#    ⛔ 也不要抄成两份 —— 两份一定会漂(形态 D)。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要它 (2026-09-06 实测)
# ═══════════════════════════════════════════════════════════════════════════
# `release-java-preflight.sh` 硬性要求 **JDK 21+**(它是发布预检)。而
# ubuntu-24.04 runner 的默认 `JAVA_HOME` 是 **Temurin 17**
# (runner image readme: `17.0.20+1 (default)`; 21 只以 `JAVA_HOME_21_X64` 存在)。
# ⇒ 两个真跑预检的用例在 CI 上必然红, 报错却是
#   "preflight rejected the current, working JDK" / "ERROR: release requires
#   JDK 21 or newer, found 17" —— 把「这台机器的 JDK 太老」说成了「预检坏了」。
#
# 处理顺序刻意是「先想办法量, 量不了才报没量到」:
#   ① 当前 JAVA_HOME / PATH 上的 java 够 21 ⇒ 直接用
#   ② 否则找 GitHub runner 的 `JAVA_HOME_21_X64` 之类, 以及 /usr/lib/jvm/*21*
#      ⇒ 找到就 export JAVA_HOME, **并把用了哪个打进日志**(⛔ 不许静默换)
#   ③ 都没有 ⇒ 调用方 rc=77 + `SKIPPED:`(硬约束 4 的三态), ⛔ 不 exit 0 假装绿

# _rjp_major <java可执行路径> -> 主版本号, 读不出来就是空
_rjp_major() {
    local java_cmd=$1 raw
    [ -x "$java_cmd" ] || return 1
    raw=$("$java_cmd" -version 2>&1 | sed -nE 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/p')
    case "$raw" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s' "$raw"
}

# _rjp_java_in <JAVA_HOME 目录> -> 可执行的 java 路径
_rjp_java_in() {
    local home=$1
    if [ -x "$home/bin/java" ]; then printf '%s' "$home/bin/java"
    elif [ -x "$home/bin/java.exe" ]; then printf '%s' "$home/bin/java.exe"
    else return 1
    fi
}

# ensure_release_jdk
#   成功: rc=0, 需要时已 export JAVA_HOME, 并打印 JDK_PROBE=...
#   失败: rc=1, 并把理由写进全局 RELEASE_JDK_SKIP_REASON
ensure_release_jdk() {
    RELEASE_JDK_SKIP_REASON=""
    local cand major seen=""

    # ① 现成的
    if [ -n "${JAVA_HOME:-}" ] && cand=$(_rjp_java_in "$JAVA_HOME"); then
        if major=$(_rjp_major "$cand"); then
            seen="JAVA_HOME=$major"
            if [ "$major" -ge 21 ] 2>/dev/null; then
                echo "READING: JDK_PROBE=major=$major via=JAVA_HOME where=$cand"
                return 0
            fi
        fi
    elif cand=$(command -v java 2>/dev/null) && major=$(_rjp_major "$cand"); then
        seen="PATH=$major"
        if [ "$major" -ge 21 ] 2>/dev/null; then
            echo "READING: JDK_PROBE=major=$major via=PATH where=$cand"
            return 0
        fi
    fi

    # ② 换一个够新的。⛔ 换了必须说出来 —— 静默换 JDK 等于换了被测对象还不报口径。
    local home
    for home in ${JAVA_HOME_21_X64:-} ${JAVA_HOME_21_ARM64:-} \
                ${JAVA_HOME_25_X64:-} ${JAVA_HOME_25_ARM64:-} \
                /usr/lib/jvm/temurin-21-jdk-amd64 /usr/lib/jvm/temurin-21-jdk-arm64 \
                /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-arm64; do
        [ -n "$home" ] && [ -d "$home" ] || continue
        cand=$(_rjp_java_in "$home") || continue
        major=$(_rjp_major "$cand") || continue
        if [ "$major" -ge 21 ] 2>/dev/null; then
            export JAVA_HOME="$home"
            echo "READING: JDK_PROBE=major=$major via=discovered where=$cand (原先 ${seen:-none})"
            return 0
        fi
    done

    # ③ 真的没有
    RELEASE_JDK_SKIP_REASON="发布级 JDK 不可用 (探到 ${seen:-无 java}, 需要 >=21; 也没找到 JAVA_HOME_21_* 或 /usr/lib/jvm/*21*) —— 真跑 release-java-preflight.sh 的断言在此环境不可判定"
    echo "READING: JDK_PROBE=major=none 探到 ${seen:-无 java}"
    return 1
}
