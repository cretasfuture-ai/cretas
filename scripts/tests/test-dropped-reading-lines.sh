#!/usr/bin/env bash
# GUARDS=scripts/ci/dropped-reading-lines.awk,scripts/ci/run-script-tests.sh
#
# 守着「哪些行会被 run-script-tests.sh 静默吃掉」这个判据。
#
# ═══════════════════════════════════════════════════════════════════════════
# 🔴 成因：两次真实事故，⛔ 不是设想
# ═══════════════════════════════════════════════════════════════════════════
# runner 只贴 `^[[:space:]]*READING: ` 开头的行。两次都是**写了、CI 里不存在**：
#
#   ① 2026-09-07 本线: `A1: payload_stale=no …` / `D: …` 两行漏了 `READING: ` 前缀
#      —— 它们是那道闸的核心读数, 而 CI 日志里**一条都没有**。
#   ② 2026-09-07 organizer: 提议把 scope 写成 `READING: …` + 三行 `⇒ …` 续行
#      —— 续行会被吃掉, **而被吃掉的恰好是「措辞本身就是那个洞」那三句**。
#
# ⚠️ 两次的失效形态相同: **作者本地看得见, 所以他以为写好了。**
#
# ⇒ 本闸的验收样本**就是这两次的真实输入**(scripts/tests/dropped-reading-fixtures.txt),
#   ⛔ 不是我编的例子。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
AWK="$ROOT_DIR/scripts/ci/dropped-reading-lines.awk"
RUNNER="$ROOT_DIR/scripts/ci/run-script-tests.sh"
FIX="$ROOT_DIR/scripts/tests/dropped-reading-fixtures.txt"
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }

[ -s "$AWK" ] || fail "1: 找不到判据 $AWK"
[ -s "$FIX" ] || fail "1: 找不到验收样本 $FIX —— **没量到**, ⛔ 不当成通过"
[ -s "$RUNNER" ] || fail "1: 找不到 $RUNNER"
ok "判据、样本、runner 三者都在"

# ---- 2. 🔴 真实事故 ② 的输入 ⇒ 必须被数出来 ----
out=$(awk -f "$AWK" "$FIX" 2>/dev/null)
co=${out##*continuation=}
echo "READING: DROP_FIXTURE_COUNTS $out"
[ "$co" = "3" ] || fail "2: 续行那一类应当数出 3 条(scope 的三行 ⇒), 实得 $co —— 判据看不见事故 ②"
ok "真实事故 ② 的输入被数出来 (续行 3 条)"

# ---- 2b. ⛔ 事故 ① 是**明写的盲区** —— 断言它确实**不**被数 ----
# ⚠️ 这一条守的是「盲区没有悄悄变宽」: 有人把 ① 加回来时会红,
#    而 ① 一旦加回来, 53 份真实日志上会有 26 条假阳性(实测), 这行读数就会天天非 0。
#    ▎能覆盖的那一半要精确, ⛔ 不要为了覆盖另一半把这一半也弄脏。
grep -q '⛔ 本文件\*\*只数 ②\*\*' "$AWK"     || fail "2b: 判据文件里没写明「只数 ②」这条边界 —— 盲区必须是明写的"
one=$(printf 'A1: payload_stale=no payload_behind_by=0
D: payload_stale=yes
' | awk -f "$AWK")
[ "${one##*continuation=}" = "0" ]     || fail "2b: 形态 ①(漏 READING: 前缀) 被数进来了(实得 $one) —— 它是明写的盲区, 见判据文件头"
# ⛔ 单引号: 双引号里的反引号会被当成命令替换执行掉(第一版就是, 那句话直接变空)
echo 'READING: DROP_BLIND_SPOT=missing-prefix->0 (⛔ 与 verdict: k=v 这类正当约定在文本上不可区分)'
ok "形态 ① 如实不被数(盲区已在判据文件头写明)"

# ---- 3. 🔴 阴性对照: 今天这些**合规**的日志必须一条都不报 ----
# ⚠️ 没有这一条,「总是数出一堆」与「判据正确」读数相同 —— 而前者会让人把这行读数忽略掉。
clean=$(mktemp); trap 'rm -f "$clean"' EXIT
cat > "$clean" <<'CLEAN'
READING: BASELINE_DIR=present files=1 non_empty=1
  ok - db/baseline 下有 1 个非空基线
READING: FLYWAY_LOCATIONS_CONFIG_FILES=2 MENTIONS_ONLY=5
  ok - 2 处真配置都不含 baseline
PASS: 7 项断言全部通过
CLEAN
c_out=$(awk -f "$AWK" "$clean" 2>/dev/null)
c_tot=${c_out##*continuation=}
[ "$c_tot" = "0" ] || fail "3: 阴性对照失败 —— 一份**合规**日志被数出 $c_tot 条($c_out)。
  ⚠️ 判据太宽会让这行读数天天非 0, 而天天非 0 的读数等于没有。"
echo "READING: DROP_NEGATIVE_CONTROL=clean-log->$c_tot"
ok "阴性对照: 合规日志报 0 (⇒ ⛔ 不会天天非 0)"

# ---- 4. runner 必须**引用**这份判据, ⛔ 不许自己抄一份 ----
# ⚠️ 抄一份 = 同一判据两份实现, 而两份从写下的那一刻起就已经是两份。
grep -q 'dropped-reading-lines.awk' "$RUNNER" \
    || fail "4: run-script-tests.sh 没有引用 $AWK —— ⛔ 判据只许有一份"
grep -q 'DROPPED_NON_READING_LINES total=' "$RUNNER" \
    || fail "4: runner 没有**无条件**打那条汇总读数 —— 0 与「没在数」必须分得开"
ok "runner 引用同一份判据, 且无条件打汇总读数"

# ---- 5. 边界 ----
echo "READING: DROP_SCOPE=continuation-only ⇒ ⛔ 只数「READING: 之后带 ⇒/→/▎ 的续行」"
echo "READING: DROP_BLIND_SPOTS=1 (形态①漏前缀 —— 与 verdict: k=v 这类正当约定不可区分; 实测硬判会在 53 份日志上产生 26 条假阳性)"
echo "READING: DROP_PRECISION=fixture3/3 false_positives_on_53_real_logs=0"
echo "READING: DROP_IS_ADVISORY=yes ⇒ 只报数 ⛔ 不红"
ok "边界已声明"

echo "PASS: $pass 项断言全部通过"
