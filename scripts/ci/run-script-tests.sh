#!/usr/bin/env bash
# 跑 scripts/tests/ 下全部 bash 用例, **逐行打出每个脚本的 rc**, 按棘轮判定。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要它
# ═══════════════════════════════════════════════════════════════════════════
# 2026-09-06 实测: 全仓 22 个 workflow **没有一个**引用 scripts/tests/ ——
# 这里 48 个用例从来只在有人想起来的时候手跑。「机制在、没接上」(形态 B)。
# 接上的第一天就照出 11 条存量红。
#
# ── 判定五条 (⛔ 退出码是开放集合, 不枚举失败码) ─────────────────────────
#   ① 不在 known-failing 且 rc≠0  ⇒ 红。**任何非 0**, 含 timeout 的 124、
#      找不到命令的 127、没有执行位的 126。
#      2026-08-27 那次 nightly 假绿正是因为判定只枚举了 rc==1 和 rc==2, 而实际
#      是 127 —— 开放集合里的第四种值两条都不命中, 静默落进「绿」(硬约束 4)。
#      唯一的例外是 ⑤ 那个**显式**的 77, 且它必须自证。
#   ② 在 known-failing 且 rc=0    ⇒ 红,「已修好, 请从 known-failing.txt 删掉」。
#      ⛔ 不许静默绿 —— 那样名单只会变长, 棘轮就退化成了永久豁免。
#   ③ known-failing 里的文件不存在 ⇒ 红,「测试被删了不是修好了」。
#      2026-08-27 实测: 棘轮把「删掉一个红测试」报成了「已修好」。
#   ④ FLAKY 条目缺双向 run id / 两个 id 相同 / 登记超过 14 天 ⇒ 红。
#      FLAKY 不参与红绿判定, 所以它必须自带出口, 否则就是一张永久豁免。
#   ⑤ rc=77 ⇒ SKIPPED (「这次没量到」, 硬约束 4 的三态), 但**必须自证**:
#      · 日志里没有 `SKIPPED:<理由>` 那一行            ⇒ 红。
#        ⛔ 77 不是一张免死金牌 —— 一个崩在打印理由之前的用例, 它的 77 和
#        「我判断这里量不了」长得一模一样(硬约束 9)。理由行就是那个
#        「一定会发生」的读数。
#      · 用例同时还挂在 known-failing.txt 上          ⇒ 红「请重新分类」。
#        它要么是存量红(确定性失败), 要么是前提不成立(跳过), ⛔ 不能两头占。
#      · ⛔ SKIPPED **不计进 PASS** —— 下面 `n_pass > 0` 那道闸因此仍然成立:
#        48 个用例全部 77 时整批是「没量到」, 不是「全绿」。
#
# ── 另外两条纪律 ──────────────────────────────────────────────────────────
#   ⛔ 不 `|| true`、⛔ 不把测试管道进 tail。`cmd | tail` 的退出码是 **tail 的**,
#      失败会被读成成功且无声(硬约束 1)。诊断输出一律落文件, 事后再读。
#   ⛔ 名单里的用例**照常执行**, 不是 skip。skip 掉就永远发现不了它已经修好了。
set -uo pipefail
# glob 没命中时展开成字面量 `*.sh`, 于是会去"跑"一个不存在的文件并记成一条失败 ——
# 一个**假的读数**。nullglob 让它展开成空, 由下面的 zero_tests 闸如实报「没量到」。
shopt -s nullglob

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TESTS_DIR="$ROOT_DIR/scripts/tests"
KNOWN="$TESTS_DIR/known-failing.txt"
PER_TEST_TIMEOUT="${SCRIPT_TESTS_TIMEOUT:-300}"
LOG_DIR="${SCRIPT_TESTS_LOGDIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"

[ -d "$TESTS_DIR" ] || { echo "error=tests_dir_missing $TESTS_DIR" >&2; exit 2; }
[ -f "$KNOWN" ] || { echo "error=known_failing_missing $KNOWN" >&2; exit 2; }

# ── 解析棘轮名单 ──────────────────────────────────────────────────────────
# ⛔ 不用「筛掉不匹配的行」那种写法 —— 一条写错的条目会静默从名单里消失,
#    而它守的那个用例就变成了「不在名单」⇒ 一红就红, 或者更糟, 反过来。
#    (本 PR 里另一个闸刚栽过这个: 筛子把「写错了」变成了「我没看见」, 形态 A⁴)
declare -a LISTED=() FLAKY_LIST=()
missing_listed=""
bad_entry=""
stale_flaky=""
while IFS= read -r raw; do
    # ⚠️ 两种取法各有用途, ⛔ 别混:
    #   body_fields —— 去注释、**保留空格**, 用来切字段(FLAKY 行有 5 个字段);
    #   body        —— 再去掉所有空白, 用来判空 / 判前缀 / 当单个文件名。
    body_fields="${raw%%#*}"
    body="$(printf '%s' "$body_fields" | tr -d '[:space:]')"
    [ -n "$body" ] || continue
    reason="${raw#*#}"
    [ "$reason" != "$raw" ] && [ -n "$(printf '%s' "$reason" | tr -d '[:space:]')" ] \
        || bad_entry="$bad_entry $body"
    if [ "${body#FLAKY}" != "$body" ]; then
        # ── 第四态: 读数不可判定 ──────────────────────────────────────────
        # ⛔ 它是四态里**唯一不参与红绿判定**的, 所以必须自带出口, 否则就是一张
        #    永久豁免。known-failing 的出口是 FIXED 探测器; FLAKY 没有那个 ——
        #    时红时绿的东西, 任何单次读数都不能证明它已经稳定。
        # ⇒ 两道闸:
        #   ① **双向 run id**: 必须同时贴出一次 rc=0 和一次 rc≠0 的 run。
        #      只贴一个等于「我看它失败过」—— 那是**确定性失败**的证据, 不是 flaky 的。
        #   ② **日期过期**: 超过 14 天就红, 强制重新分类。
        #      时间会替你做判断: 它要么早修好了, 要么根本不是 flaky。
        # 格式: FLAKY <name> <YYYY-MM-DD> <run-id-rc0> <run-id-rc≠0>  # <理由>
        set -- $body_fields
        name="${2:-}"; fdate="${3:-}"; run_ok="${4:-}"; run_bad="${5:-}"
        if [ -z "$name" ] || [ -z "$fdate" ] || [ -z "$run_ok" ] || [ -z "$run_bad" ]; then
            bad_entry="$bad_entry ${name:-<无名>}(FLAKY-格式应为:FLAKY_name_日期_rc0run_rcNrun)"
        elif [ "$run_ok" = "$run_bad" ]; then
            # 同一个 run 不可能既 rc=0 又 rc≠0 —— 贴两遍同一个 id 是在凑格式。
            bad_entry="$bad_entry ${name}(FLAKY-两个run-id相同:$run_ok)"
        elif ! printf '%s' "$fdate" | grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'; then
            bad_entry="$bad_entry ${name}(FLAKY-日期应为YYYY-MM-DD:$fdate)"
        else
            entry_epoch=$(date -u -d "$fdate" +%s 2>/dev/null || echo "")
            if [ -z "$entry_epoch" ]; then
                bad_entry="$bad_entry ${name}(FLAKY-日期解析不了:$fdate)"
            else
                age_days=$(( ( $(date -u +%s) - entry_epoch ) / 86400 ))
                if [ "$age_days" -gt 14 ]; then
                    stale_flaky="$stale_flaky ${name}(登记于 $fdate, 已 ${age_days} 天)"
                fi
            fi
        fi
        [ -z "$name" ] || [ -f "$TESTS_DIR/$name" ] || missing_listed="$missing_listed $name"
        [ -z "$name" ] || FLAKY_LIST+=("$name")
    else
        [ -f "$TESTS_DIR/$body" ] || missing_listed="$missing_listed $body"
        LISTED+=("$body")
    fi
done < "$KNOWN"

[ -z "$bad_entry" ] || { echo "error=known_failing_entry_invalid$bad_entry (格式/理由/证据 有一项不合规)" >&2; exit 2; }
if [ -n "$stale_flaky" ]; then
    echo "FAIL: FLAKY 条目过期, 请重新分类:$stale_flaky" >&2
    echo "      FLAKY 是四态里唯一不参与红绿判定的, 所以它必须会过期 —— 否则就是永久豁免。" >&2
    echo "      ⇒ 要么它早就稳定了(移出 FLAKY), 要么它其实是确定性失败(移进 known-failing)," >&2
    echo "        要么它真还在抖(重新取两侧 run id 并把日期更新到今天)。" >&2
    exit 1
fi
if [ -n "$missing_listed" ]; then
    echo "FAIL: known-failing.txt 里这些用例**文件不存在**:$missing_listed" >&2
    echo "      ⛔ 测试被删了不是修好了。要么把文件找回来, 要么在同一个 commit 里" >&2
    echo "      说清为什么它该消失 —— ⛔ 不要只从名单里划掉。" >&2
    exit 1
fi

is_listed() {
    local x
    for x in ${LISTED[@]+"${LISTED[@]}"}; do [ "$x" = "$1" ] && return 0; done
    return 1
}
is_flaky() {
    local x
    for x in ${FLAKY_LIST[@]+"${FLAKY_LIST[@]}"}; do [ "$x" = "$1" ] && return 0; done
    return 1
}

n_total=0 n_pass=0 n_fail=0 n_known=0 n_fixed=0 n_flaky=0 n_skipped=0
n_dropped=0
DROPPED_IN=()
# 「本该是读数却被丢掉」的判据 —— ⛔ 只此一份, 本文件与 test-dropped-reading-lines.sh 都引用它。
DROP_AWK="$ROOT_DIR/scripts/ci/dropped-reading-lines.awk"
declare -a FAILED=() FIXED=() SKIPPED=()

# rc=77 的自证: 用例必须在自己的输出里打一行 `SKIPPED:<理由>`。
# ⛔ 取第一条匹配就够, 但**不能**用 `grep | head` —— head 提前关管道会给 grep
#    发 SIGPIPE, 在 pipefail 下把「有理由」读成失败(本仓 deploy-smartbi-python.sh
#    的 EPIPE 事故同形)。先收全再切。
skipped_reason() {
    local log=$1 hits
    hits=$(grep -aE '^[[:space:]]*SKIPPED:' "$log" 2>/dev/null || true)
    [ -n "$hits" ] || return 1
    printf '%s' "${hits%%$'\n'*}"
}

# ── 口径行: 绿的时候也要能看见「量的是哪一档」 ────────────────────────────
# 用例的输出**只在失败时**被 tail 出来, 于是绿的那次读数为零 —— 而这些用例里有
# 好几条会**按环境分支**(有没有 flock / JDK 几 / 制品住在哪个 workflow), 光看
# 「绿了」根本不知道它量的是哪一档。同一句「9 条」可能是两个集合(形态 A: 报数
# 同时报口径)。
# ⇒ 约定: 用例打 `READING: k=v`, 本 runner **无论红绿**都把这些行贴出来。
#   ⛔ 不做成硬编码的关键字清单(那是形态 D, 两处一定会漂)。
print_readings() {
    local log=$1 line
    grep -aE '^[[:space:]]*READING: ' "$log" 2>/dev/null | while IFS= read -r line; do
        printf '         %s\n' "${line#"${line%%[![:space:]]*}"}"
    done
}

for f in "$TESTS_DIR"/*.sh; do
    base=$(basename "$f")
    # test-helpers.sh 是被 source 的函数库, 不是用例。
    [ "$base" = "test-helpers.sh" ] && continue
    n_total=$((n_total + 1))

    # ── 单个用例的超时覆盖: 文件头 40 行内一行 `# SCRIPT_TEST_TIMEOUT=<秒>` ──────────
    # 2026-09-06 (卡 C 任务 5): test-release-cretas.sh 在 Windows 本地 ~470s (43 次 fixture 发布,
    # 每次 git init+push+fetch 约 2s 加 release 本体; CI ubuntu 8s), 全局 300s 把它砍成 rc=124,
    # 而它本身是绿的。⛔ 不把全局阈值抬到 900 —— 那会让别的用例挂死 15 分钟才报; 只让**这一个**
    # 文件自己声明, 理由写在它头部。⛔ 也不给「无标记的用例」任何默认放宽。
    # 标记写错 (非整数 / 超 3600) ⇒ 红, ⛔ 不静默退回默认 —— 那是「机制在、没接上」的形状。
    this_timeout=$PER_TEST_TIMEOUT
    marker=$(head -40 "$f" | grep -oE '^# SCRIPT_TEST_TIMEOUT=[^ ]*' | head -1 | cut -d= -f2)
    if [ -n "$marker" ]; then
        case "$marker" in
            ''|*[!0-9]*) printf 'rc=%-5s %-45s NEW_FAILURE(SCRIPT_TEST_TIMEOUT 标记不是整数: %s)\n' "?" "$base" "$marker"
                         n_fail=$((n_fail + 1)); FAILED+=("$base 标记无效"); : > "$LOG_DIR/$base.log"; continue ;;
        esac
        if [ "$marker" -lt 1 ] || [ "$marker" -gt 3600 ]; then
            printf 'rc=%-5s %-45s NEW_FAILURE(SCRIPT_TEST_TIMEOUT 超出 1..3600: %s)\n' "?" "$base" "$marker"
            n_fail=$((n_fail + 1)); FAILED+=("$base 标记越界"); : > "$LOG_DIR/$base.log"; continue
        fi
        this_timeout=$marker
    fi

    timeout "$this_timeout" bash "$f" > "$LOG_DIR/$base.log" 2>&1
    rc=$?
    # 口径: 用了覆盖值就追进该用例的日志 (下面 print_readings 会把 READING: 行贴出来),
    # 绿的时候也能看见这个用例是按哪个阈值量的。
    [ "$this_timeout" = "$PER_TEST_TIMEOUT" ] \
        || echo "READING: TIMEOUT_OVERRIDE=$this_timeout default=$PER_TEST_TIMEOUT" >> "$LOG_DIR/$base.log"

    if is_flaky "$base"; then
        # rc 照常打印(⛔ 不藏), 但不参与红绿判定 —— 它的读数在这台机器上不可判定。
        printf 'rc=%-5s %-45s FLAKY(读数不可判定, 不参与判定)\n' "$rc" "$base"
        n_flaky=$((n_flaky + 1))
    elif [ "$rc" -eq 77 ]; then
        # ── 判定⑤: 「这次没量到」是独立的一态, ⛔ 不是绿 ──────────────────
        reason=$(skipped_reason "$LOG_DIR/$base.log") || reason=""
        if [ -z "$reason" ]; then
            printf 'rc=%-5s %-45s NEW_FAILURE(77 但没打 SKIPPED: 理由行)\n' "$rc" "$base"
            n_fail=$((n_fail + 1)); FAILED+=("$base rc=$rc")
        elif is_listed "$base"; then
            printf 'rc=%-5s %-45s NEW_FAILURE(既在 known-failing 又报 SKIPPED — 请重新分类)\n' "$rc" "$base"
            n_fail=$((n_fail + 1)); FAILED+=("$base rc=$rc 既在 known-failing 又报 SKIPPED")
        else
            printf 'rc=%-5s %-45s SKIPPED — %s\n' "$rc" "$base" "$reason"
            n_skipped=$((n_skipped + 1)); SKIPPED+=("$base | $reason")
        fi
    elif is_listed "$base"; then
        if [ "$rc" -eq 0 ]; then
            printf 'rc=%-5s %-45s FIXED — 请从 known-failing.txt 删掉\n' "$rc" "$base"
            n_fixed=$((n_fixed + 1)); FIXED+=("$base")
        else
            printf 'rc=%-5s %-45s KNOWN_FAILING\n' "$rc" "$base"
            n_known=$((n_known + 1))
        fi
    elif [ "$rc" -eq 0 ]; then
        printf 'rc=%-5s %s\n' "$rc" "$base"
        n_pass=$((n_pass + 1))
    else
        printf 'rc=%-5s %-45s NEW_FAILURE\n' "$rc" "$base"
        n_fail=$((n_fail + 1)); FAILED+=("$base rc=$rc")
    fi
    # ⛔ 放在四个分支之外 —— 口径行跟红绿无关, 绿的那次尤其需要它。
    print_readings "$LOG_DIR/$base.log"

    # ── 有多少行「本该是读数」被上面那个 grep 吃掉了 ────────────────────────
    # ⚠️ 只数**像读数却进不去**的那两类(漏 READING: 前缀 / READING: 之后的续行),
    #    ⛔ 不数所有非 READING 行 —— 那是每一条 `ok - …`, 几百行噪声。
    #    判据在 scripts/ci/dropped-reading-lines.awk, 本文件与它的闸**都引用那一份**。
    drop_line=$(awk -f "$DROP_AWK" "$LOG_DIR/$base.log" 2>/dev/null || true)
    drop_n=${drop_line##*continuation=}
    case "$drop_n" in ''|*[!0-9]*) drop_n=0 ;; esac
    if [ "$drop_n" -gt 0 ]; then
        printf '         READING: DROPPED_NON_READING_LINES %s ⇒ 这些行在 CI 里**不存在**\n' "$drop_line"
        n_dropped=$((n_dropped + drop_n))
        DROPPED_IN+=("$base:$drop_n")
    fi
done

echo
echo "TOTAL=$n_total PASS=$n_pass FAIL=$n_fail KNOWN_FAILING=$n_known FIXED=$n_fixed FLAKY=$n_flaky SKIPPED=$n_skipped"
# ⚠️ 这一行**无论是否为 0 都打** —— 0 与「我根本没在数」必须分得开。
echo "READING: DROPPED_NON_READING_LINES total=$n_dropped tests_with_drops=${#DROPPED_IN[@]}"
if [ "$n_dropped" -gt 0 ]; then
    echo "⇒ 以下用例有「写了但 CI 里看不见」的行(⛔ 不红, 只报数): ${DROPPED_IN[*]}"
    echo "  ① 漏 \`READING: \` 前缀   ② 写成 READING: 一行 + 若干续行(只有第一行活下来)"
    echo "  ⇒ 每一行读数各自带 \`READING: \`, ⛔ 不要靠续行。"
fi

if [ "$n_skipped" -gt 0 ]; then
    echo
    echo "===== 前提不成立, 本次没量到 (SKIPPED) ====="
    for entry in ${SKIPPED[@]+"${SKIPPED[@]}"}; do echo "  $entry"; done
    echo "⇒ 这不是绿。前提变了(换 runner / 装了 JDK 21 / 换了 grep)就该重新量。"
fi

# 硬约束 9: 「没有新失败」必须配一个「一定会发生」的读数。glob 没命中 / 目录空
# 在 FAIL=0 上与「全绿」长得一模一样。
[ "$((n_pass + n_fail + n_known + n_fixed + n_flaky + n_skipped))" -eq "$n_total" ] \
    || { echo "error=census_mismatch —— 计数器和实际循环对不上, 本次读数作废" >&2; exit 2; }
[ "$n_pass" -gt 0 ] \
    || { echo "error=zero_tests_actually_passed —— FAIL=0 在这里没有意义" >&2; exit 2; }

rc_final=0
if [ "$n_fail" -gt 0 ]; then
    echo
    echo "===== 新增失败的日志尾部 ====="
    for entry in ${FAILED[@]+"${FAILED[@]}"}; do
        b=${entry%% *}
        echo "----- $entry -----"
        tail -25 "$LOG_DIR/$b.log"
        echo
    done
    rc_final=1
fi

if [ "$n_fixed" -gt 0 ]; then
    echo
    echo "===== 已修好, 但还挂在 known-failing.txt 上 ====="
    for b in ${FIXED[@]+"${FIXED[@]}"}; do echo "  $b"; done
    echo "⇒ 从 scripts/tests/known-failing.txt 删掉这些行。棘轮只许变短。"
    rc_final=1
fi

[ "$rc_final" -eq 0 ] && echo "NO_NEW_FAILURES (存量红 $n_known 条已记账, 读数不可判定 $n_flaky 条, 前提不成立 $n_skipped 条)"
exit "$rc_final"
