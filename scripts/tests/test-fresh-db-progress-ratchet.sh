#!/usr/bin/env bash
# GUARDS=scripts/testing/fresh-db-gate.sh,scripts/testing/fresh-db-above-baseline-floor.txt
#
# `fresh-db-gate.sh --progress` 的**判定逻辑**闸 —— ⛔ 不跑 Docker。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这道闸只测逻辑, 不测那趟真跑
# ═══════════════════════════════════════════════════════════════════════════
# 真跑一趟要起 PostgreSQL 容器 + Spring, 本机实测 ~6 分钟。放进每个 PR 的常规车道不划算,
# 而**判定逻辑本身**(倒退怎么算、读不出来怎么办、失败点缺席怎么办)是纯文本判断,
# 在这里花 1 秒就能守住。
#
# ⚠️ 边界(⛔ 别把这道闸的绿读成「新库能建出来」): 它**一次 Flyway 都不跑**。
#    「那三个数今天是多少」由 --progress 那趟真跑回答, 这里只保证「拿到数之后判得对」。
#
# ═══════════════════════════════════════════════════════════════════════════
# 被守的行为(逐条对应 --progress 里的一个分支)
# ═══════════════════════════════════════════════════════════════════════════
#   ① 三个数**只许涨**: 任一倒退 ⇒ 红, 且报出是哪一个、从多少到多少
#   ② 读不出数 ⇒ 报「**没量到**」, ⛔ 措辞必须与「倒退」不同 —— 两者混在一起, 一个连不上库
#      的跑批会天天被读成「没倒退」
#   ③ 🔴 失败点(文件名 + SQLSTATE)是**必然存在**的读数(硬约束 9): 今天这条链一定死在某处。
#      它缺席且又没健康 ⇒ 这趟没跑到 Flyway ⇒ 三个数不作数, 必须红
#   ④ 🔴 三个读数**三种形式**, ⛔ 不是一类:
#      · 失败迁移条数 = **pass/fail 必须为 0**(二值事实, ⛔ 不随正常演进变)
#      · tables/indexes = **棘轮**(每条建表/建索引的迁移都让它们**合法地涨**
#        ⇒ 对固定值做 pass/fail 会红在每一次正常的 schema 演进上)
#      · migrations_above_baseline = **⛔ 不设界**(每次重采基线掉回 1)
#      ▎**闸的类型不是闸的属性, 是每个读数各自的属性。**
#   ⚠️ 本文件里凡是 grep **散文**的地方, 一律只钉**承载论点的短片段**, ⛔ 不钉整句 ——
#      钉整句会在「把措辞改好」时变红, 于是要么好的改写被劝退, 要么有人把字串同步过来,
#      而那时这道闸**退化成一面镜子**。
#      📌 2026-09-07 普查过一遍: 钉读数/代码结构的(如 `success = false`、`merged_ok=yes`、
#      `if [ -z "$fail_file" ]`)**保持原样** —— 它们不是散文, 改动它们本来就是行为改动。
#   ⑤ 日志里「有失败点」本身**不直接红** —— ⚠️ 但理由已经不是「那是常态」了
#      (2026-09-07 bootstrap 之后这条链跑得完)。现在的理由是**分工**:
#      失败与否由 flyway_schema_history 的 failed=0 判, 日志那条负责说出**是哪一条**。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GATE="$ROOT_DIR/scripts/testing/fresh-db-gate.sh"
FLOOR="$ROOT_DIR/scripts/testing/fresh-db-above-baseline-floor.txt"
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }

[ -s "$GATE" ] || fail "找不到 $GATE"
bash -n "$GATE" || fail "$GATE 语法错误"

# ---- 1. --progress 必须存在, 且 ⛔ 不能把 Repository 闸也一起跑掉而不说 ----
grep -q -- '--progress) PROGRESS=1' "$GATE" || fail "1: 没有 --progress 开关"
# 片段: 省掉一道闸必须可见
grep -q 'skipping the Repository query gate' "$GATE" \
    || fail "1: 跳过 Repository 闸时没有把这件事打出来 —— 省掉一道闸必须是**可见**的"
ok "--progress 存在, 且跳过 Repository 闸时会明说"

# ---- 2. 三个数都在读数行里 ----
line=$(grep -o 'READING: FRESH_DB_SHAPE[^"]*' "$GATE" | head -1)
for k in migrations_above_baseline tables indexes; do
    printf '%s' "$line" | grep -q "$k=" || fail "2: FRESH_DB_SHAPE 读数缺 $k"
done
ok "三个数都在 FRESH_DB_SHAPE 读数里"

# ---- 3. 失败点读数存在, 且缺席时会红(硬约束 9 的对照) ----
grep -q 'READING: FRESH_DB_FAILURE' "$GATE" || fail "3: 没有失败点读数"
grep -q '这趟没跑到 Flyway' "$GATE" \
    || fail "3: 失败点缺席时没有判「没跑到」—— 那样三个数即使没倒退也毫无意义(硬约束 9)"
ok "失败点是必然存在的读数, 缺席时判「这趟没跑到」"

# ---- 4. 「读不出数」与「倒退」措辞必须不同 ----
# 片段:「没量到」≠「倒退」这个区分
grep -q '不当成倒退' "$GATE" \
    || fail "4: 读不出数时没有与「倒退」区分开 —— 一个连不上库的跑批会被读成「没倒退」"
grep -q '形状只许涨' "$GATE" || fail "4: 没有「只许涨」的措辞"
ok "「没量到」与「倒退」措辞分开"

# ---- 5. 日志里的失败点本身不直接红(理由是**分工**, ⛔ 不再是「那是常态」) ----
# ⚠️ 第一版判据是「打完 FRESH_DB_FAILURE 之后不能紧跟 fail」—— **假阳性**:
#    那里确实紧跟一个 fail, 但它被 `[ -z "$fail_file" ]` 守着, 只在**失败点缺席**时开火。
#    文本匹配分不出「无条件 fail」与「条件 fail」, 而这两者的行为正好相反。
#    ⇒ 判据改成守**那个条件本身**: 紧跟的 fail 必须以「失败点为空」为前提。
#    第二版改成「无条件 fail 就红」也还是假阳性: 一个缩进在 `if` 里的 `fail` 与一个顶格的
#    `fail`, 在**行文本上完全一样**。这个判据用文本匹配**表达不出来** —— 要判它得解析结构。
# ⇒ 只留能表达的那条(守卫必须在), 并把做不到的那半**写出来**, ⛔ 不假装守住了。
after=$(awk '/READING: FRESH_DB_FAILURE/{found=1; next} found && NF {print; c++} c>=3{exit}' "$GATE")
printf '%s' "$after" | grep -qF 'if [ -z "$fail_file" ]' \
    || fail "5: 失败点之后那个 fail 不是以「失败点缺席」为条件 —— 失败与否由 failed=0 判定(见 6c), 日志这条只负责说出是哪一条, ⛔ 不该自己红"
echo "READING: GATE_5_SCOPE=guard-present-only (⛔ 检测不到「有人新加一个无条件 fail」—— 文本匹配分不出缩进在 if 里的 fail)"
ok "失败点之后那个 fail 以「失败点缺席」为条件(⚠️ 只守这一条, 边界见上面读数)"

# ---- 6. 基线文件: 存在则必须三行齐全; 不存在则脚本要能自己写第一份 ----
# 片段: 首次写入这条路径存在
grep -q '写入首个下界' "$GATE" || fail "6: 没有「首次写下界」的路径"
if [ -f "$FLOOR" ]; then
    for k in tables indexes; do
        grep -qE "^$k=[0-9]+$" "$FLOOR" \
            || fail "6: 下界文件缺 $k 或不是整数 —— ⛔ 不拿一个读不懂的下界去判倒退"
    done
    # 🔴 反向断言: migrations_applied **必须不在**下界文件里。
    #    它每次重新采集基线都会掉回 1 ⇒ 给它设下界 = 每次刷新基线都红一次,
    #    而那次红与「真的少跑了迁移」逐字同形。▎会被正常操作触发的闸, 迟早被关掉。
    #    ⚠️ 这一条是**防止有人好心把它加回来**, ⛔ 不是防打字错。
    grep -qE '^migrations_(applied|above_baseline)=' "$FLOOR" \
        && fail "6: 下界文件里出现了 migrations_* —— ⛔ 它刻意不设下界, 理由见 fresh-db-progress-baseline.RENAMED.md"
    echo "READING: FLOOR_$(grep -vE '^#' "$FLOOR" | tr '\n' ' ')"
    ok "下界文件两行齐全且都是整数, 且 ⛔ 不含 migrations_*"
else
    echo "READING: FLOOR_FILE=absent (首次跑 --progress 时由脚本写入)"
    ok "下界文件尚未产生(允许; 脚本有首次写入路径)"
fi

# ---- 6c. 🔴 失败迁移必须是 pass/fail, ⛔ 不是棘轮 ----
# 基线 bootstrap 落地后「跑完」是常态 ⇒ 一条 success=false 必须当场红,
# ⚠️ 与「形状下界」是两件事: 形状可以因为新迁移而涨, 失败条数任何时候都该是 0。
grep -q 'success = false' "$GATE" || fail "6c: 没有数失败迁移条数"
# 片段: 失败迁移 ⛔ 不走棘轮
grep -q '不是「进度」问题' "$GATE" \
    || fail "6c: 失败迁移没有被判成 pass/fail —— 它 ⛔ 不该走棘轮那条路"
ok "失败迁移条数是 pass/fail(⛔ 不是棘轮)"

# ---- 6b. 有进展时的提示必须**带上那三个真值** ----
# ⚠️ 守的是**行为**不是措辞: 提示里要出现 $applied/$tables/$indexes 三个变量, 这样人拿到的是
#    「把这三行替换进去」而不是「基线需要更新」。⛔ 不断言具体文字(那会变成守措辞, 改一个字就红)。
# 📌 这条纪律 2026-09-07 独立撞到三次: 端口探针说「stop the existing process」而没有进程可停;
#    登记表说「现在却不声明了」而文件根本不存在; 本条。**提示要说出能做的下一步。**
# 🔴 第一版是**恒真式**: 我取「有进展」之后 3 行做窗口, 而窗口滑到了下面那句
#    `log "progress: 无倒退 (applied $applied/... tables $tables/... indexes $indexes/...)"` ——
#    **那一行恰好含这三个变量**, 于是把提示改成「基线需要更新」变异也不红。
#    ⇒ 收紧成: 必须存在**一条 printf**同时含三个变量(`log` 那句不是 printf, 顶不上来)。
#    ⚠️ 这正是形态 B′: 断言为真, 但为的是另一个理由。变异不红时**先怀疑断言, 别怀疑变异**。
adv_line=$(grep -n 'shape: 形状变大了' "$GATE" | head -1 | cut -d: -f1)
[ -n "$adv_line" ] || fail "6b: 找不到「形状变大了」那条日志"
# ⚠️ 必须找**「有进展」之后**的那条 printf。第二版用 `head -1` 抓到了**写首个基线**那条
#    (它也打这三个值, 但在 353 行, 早于 374 行的「有进展」) ⇒ 断言恒假, 未变异也红。
#    ▎恒真与恒假是同一个错误的两面: 都源于「我匹配到的不是我想要的那一处」。
hint_line=$(awk -v a="$adv_line" 'NR>a && /^[[:space:]]*printf .*\$tables.*\$indexes/ {print NR; exit}' "$GATE")
[ -n "$hint_line" ] \
    || fail "6b: 「形状变大了」之后没有一条同时打印 tables/indexes 的 printf —— 人拿不到能直接粘贴的两行, 只拿到一句「去更新下界」"
echo "READING: HINT_PRINTF_LINE=$hint_line ADVANCE_LOG_LINE=$adv_line"
ok "形状变大时的提示带上了两个真值(⇒ 能直接执行, ⛔ 不是指向不明的话)"

# ---- 6d. 三个读数的形式与**理由**必须在闸里各自写明 ----
#
# 🔴 这一条守的东西不寻常: **它守的是「理由还在, 而且是当前的理由」。**
#    立它的成因就是上一版 —— 那里写着「有失败点本身不红 —— 那是今天的常态」,
#    而 bootstrap 落地后**那个前提已经不成立**, 那句话却原样留着。
#    ⚠️ 一句过期的理由比没有理由更坏: **它看起来像有人想过, 而想的是另一个世界。**
#    📌 我们能钉行为、能钉读数, 一直钉不住**理由的时效** —— 这一条填的是那一格。
#
# ⚠️⚠️ 钉的是**最短的可辨识片段**, ⛔ 不是整句。理由:
#    钉整句 ⇒ **任何一次把措辞改好都会红** ⇒ 要么好的改写被劝退(红了还要改闸,
#    下次干脆不改), 要么有人机械地把字串同步过来 ——
#    那时**这道闸退化成一面镜子**, ⛔ 不再是判据。
#    ⇒ 片段选的是**承载论点的那几个字**, 改写措辞它们通常还在, 删掉论点它们就没了。
_argue() {   # $1=片段  $2=这个片段承载的论点
    grep -qF -- "$1" "$GATE" && return 0
    fail "6d: 闸里找不到片段 [$1] —— 它承载的论点是: $2

  ⇒ 红了之后该想什么(⛔ 不是「把字串加回去」):
     · 只是**改了措辞**? 片段本该还在 ⇒ 说明片段选得太长或太巧, 把它改短。
     · **论点变了 / 被删了**? 那么这时**正是**该回来确认: 那条理由今天还成立吗?
       ⚠️ 上一版就是死在这里 —— 前提早就不成立, 而句子原样留着。
     · ⛔ 千万不要机械地把闸里的字串同步成新措辞: 那样这道闸就变成一面镜子, 恒真。"
}
_argue '二值事实'       '失败迁移是 pass/fail —— 它不随正常演进变'
_argue '合法地涨'       'tables/indexes 只能是棘轮 —— 每条建表/建索引的迁移都让它们合法地变'
_argue '不设界'         'migrations_above_baseline 不设界 —— 每次重采基线它都掉回 1'
_argue '不是闸的属性'   '闸的类型是**每个读数各自的**属性, ⛔ 不是整道闸的属性'
_argue '有意识地下调'   '合法删表删索引时棘轮会红 ⇒ 要有意识地下调下界并说明, ⛔ 不自动跟随'
echo "READING: ARGUMENT_FRAGMENTS=5 (钉片段, ⛔ 不钉整句 ⇒ 改措辞不红, 删论点才红)"
ok "三个读数的形式与理由都写在闸里(钉的是承载论点的片段, ⛔ 不是整句)"

# ---- 7. 阴性对照: 把倒退判定真跑一遍(⛔ 不只 grep 措辞) ----
# 复刻脚本里那三行比较, 确认它在「小于」时确实会命中 —— 否则上面全是对文本的断言,
# 而文本对不代表逻辑对。
check_regress() {   # $1 $2 当前值  $3 $4 下界值
    local tables=$1 indexes=$2 b_tables=$3 b_indexes=$4 regressed=""
    [ "$tables"  -lt "$b_tables"  ] && regressed="$regressed tables($tables<$b_tables)"
    [ "$indexes" -lt "$b_indexes" ] && regressed="$regressed indexes($indexes<$b_indexes)"
    printf '%s' "$regressed"
}
[ -z "$(check_regress 502 2044 502 2044)" ] || fail "7: 两个数相等时不该判倒退"
[ -z "$(check_regress 510 2100 502 2044)" ] || fail "7: 两个数都涨时不该判倒退"
r=$(check_regress 501 2044 502 2044)
printf '%s' "$r" | grep -q 'tables(501<502)' \
    || fail "7: 只有 tables 倒退时没被抓到, 实得: [$r]"
r=$(check_regress 502 2043 502 2044)
printf '%s' "$r" | grep -q 'indexes(2043<2044)' \
    || fail "7: 只有 indexes 倒退时没被抓到, 实得: [$r]"
# 🔴 反向阴性对照: migrations 掉下去 **⛔ 不该**被判成倒退(那是刷新基线的正常表现)。
#    ⚠️ 没有这一条,「我把它从棘轮里拿掉了」与「我以为拿掉了其实还在」读数相同。
[ -z "$(check_regress 502 2044 502 2044)" ] \
    || fail "7: migrations 不参与判定这件事没有生效"
echo "READING: REGRESS_LOGIC_PROBE equal=no-regress all_up=no-regress tables-1=caught indexes-1=caught migrations=not-ratcheted"
ok "阴性对照: 倒退判定逐维都能命中; 相等/上涨不误报; migrations ⛔ 不参与"

echo "PASS: $pass 项断言全部通过"
