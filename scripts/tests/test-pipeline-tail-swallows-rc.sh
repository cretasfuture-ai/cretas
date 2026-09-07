#!/usr/bin/env bash
# GUARDS=scripts/**
# SCRIPT_TEST_TIMEOUT=120
#
# 硬约束 1 的**后半**（退出码被管道尾部吞掉）—— 只拦**本次改动新增的行**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 🔴 为什么不是 shellcheck（2026-09-07 实测，⛔ 不是推想）
# ═══════════════════════════════════════════════════════════════════════════
# 我们一度打算接 shellcheck 的 SC2312（check-extra-masked-returns），依据是它的**描述**:
#   "Consider invoking this command separately to avoid masking its return value"
# ⇒ 措辞与硬约束 1 完全吻合。**而把本仓四次真实事故的形状原样喂给它, 命中 0/4。**
#
#   ① applied=$(psql -tAc "…")            set -e 下当场中止, 三态检查走不到
#   ② found=$(grep … | sed …) || echo     退出码恒 0, || 是死代码
#   ③ rc=$(cmd | head -1)                 用 | head 之后读退出码
#   ④ last=$(cmd | tail -1)               同上
#   shellcheck -o all 的报告: 只有 SC2249/SC2250/SC2292 三条**风格建议**。
#
# ⚠️ 而 SC2312 在本仓命中 **786 条 / 134 文件**, 其中 38% 直接落在 `echo "$(...)"` 上
#    —— 那是另一个族群(打个版本号、打个行数), **基本无害**。
# 🔴 ⇒ 接了它会得到「786 条债务 + 一条真事故都拦不住 + 从此认为硬约束 1 有闸了」。
#    ▎**判据的描述与判据的行为, 是两件要分别验证的事。**
#    📌 由此立的规矩: **给一条规矩选载体时, 先拿这条规矩真栽过的实例去测那个载体。**
#
# ═══════════════════════════════════════════════════════════════════════════
# ⛔ 本闸**明写的两个盲区** —— ⛔ 不许把它读成「硬约束 1 已有闸」
# ═══════════════════════════════════════════════════════════════════════════
# 盲区 1: 形态 ①（`x=$(cmd)` **不带管道**, 在 `set -e` 下当场中止）——
#         本闸**抓不到**。它与「正常的命令替换」在文本上完全一样,
#         要判它得知道「下面那段三态检查是不是本来该跑到」。⛔ 静态判不了。
# 🔴 盲区 2: 硬约束 1 的**前半**（`| head` 把要看的内容截断了）——
#         **完全没有载体, 且大概率造不出来**: `| head` 在绝大多数场合是正当的,
#         机械区分「诊断用途」与「浏览用途」做不到。
#         ⇒ 唯一可执行的纪律是: **凡是要据以下结论的输出, ⛔ 一律不截断。**
#         (2026-09-07 一晚栽 5 次: organizer ×2 · Line B ×1 · 本线 ×2)
#
# ⇒ 覆盖率如实写: **3/4**, 且 ① 与「内容截断」都留在盲区。
#
# ⚠️⚠️ 本文件自己 ⛔ 不许出现 `x=$(… | head/tail/sed/awk/grep -c …)` ——
#    **第一版就犯了两处**, 是本闸把它自己的作者拓下来的。
#    ⇒ 凡是要数行, 一律「先把输出落进变量(无管道), 再单独数」。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { pass=$((pass + 1)); echo "  ok - $*"; }
count_lines() { if [ -n "$1" ]; then awk 'END{print NR}' <<< "$1"; else echo 0; fi; }

# 管道尾部会吞掉前面命令退出码的那几个 —— ⛔ 刻意只列这几个(形态 E: 宁可窄而可信)。
TAIL_CMDS='head|tail|sed|awk|grep[[:space:]]+-c'
PAT="[A-Za-z_][A-Za-z0-9_]*=\\\$\\(.*\\|[[:space:]]*(${TAIL_CMDS})[[:space:]]"

# ⚠️ 跳过**注释行**: 一行 `# rc=$(cmd | head -1)` 是在**谈论**这个形状, ⛔ 不是犯它。
#    🔴 文档第十节那条(按文本特征找 X, 会把谈论 X 的文本算成 X)在本闸上第三次兑现 ——
#    本文件开头解释四个形状的那三行, 被它自己数了进去。
#    ⇒ 注释**不可能**吞掉退出码 ⇒ 排除它是按**语义**排除, ⛔ 不是为了让自己变绿。
#
# 🔴 `COMMENT_RE` **只定义一次**, 扫文件 / 扫 diff 两条路径共用 ——
#    第一版我只在扫文件那条上排除了注释, diff 那条忘了 ⇒ **同一个判据两份实现, 漂了**,
#    表现是本闸红在自己文件头解释四个形状的那三行上。▎判据只写一次, 两处引用。
COMMENT_RE='[[:space:]]*#'
scan() { grep -nE "$PAT" "$1" 2>/dev/null | grep -vE "^[0-9]+:${COMMENT_RE}" || true; }

# ---- 1. 存量普查(只报数, ⛔ 不断言) ----
# 📌 口径说明: 我先前手算报过 **168**, 本闸报 **219** —— ⛔ 不是分歧, 是**分母不同**,
#    拆开(同一棵树, 都排除注释):
#      168  先前口径: `[^)]*`(遇到第一个 `)` 就停) + ⛔ 不含 grep -c
#      194  改成 `.*` 之后 (+26) —— 能看见 `x=$(foo "$(bar)" | head -1)` 这种嵌套
#      219  再加上 `grep -c` (+25)
#    ⇒ 两次都是**刻意放宽**, 且各自的增量都数得出来。
#    ⚠️ 两个数并存而不解释, 正是今晚反复吃亏的地方。
# ⚠️ 存量两百多处 ⇒ ⛔ 不能做阻塞闸, 也 ⛔ 不做总量棘轮:
#    「修 3 条 + 新增 3 条 = 数不变 = 绿」, 而新增的那 3 条正是要拦的。
mapfile -t ALL < <(find "$ROOT_DIR/scripts" -name '*.sh' -type f | sort)
n_hit=0
n_file=0
for f in "${ALL[@]}"; do
    out=$(scan "$f" || true)
    [ -n "$out" ] || continue
    c=$(count_lines "$out")
    n_hit=$((n_hit + c))
    n_file=$((n_file + 1))
done
echo "READING: PIPE_RC_BACKLOG hits=$n_hit files=$n_file (⛔ 只报数, 不断言 —— 存量不红)"
[ "$n_hit" -gt 0 ] \
    || fail "1: 全仓一处都没扫到 —— 存量本应有上百处, **没量到**(正则坏了?), ⛔ 不当成「很干净」"
ok "存量普查跑通 (hits=$n_hit files=$n_file)"

# ---- 2. 🔴 验收判据: 那四个**真实事故**的形状 ----
# ⚠️ 本闸的验收 ⛔ 不是「它能红」, 是「它对这四个形状各自给出正确答案」——
#    ②③④ 必须命中, ① 必须**不**命中(它是明写的盲区)。
# ⚠️ 样本放在 **.txt**: 本闸只扫 `*.sh` ⇒ 样本**结构上**不会被自己扫到,
#    ⛔ 不需要任何排除名单。▎能用**位置**解决的, ⛔ 不要用名单解决 —— 名单会漂, 位置不会。
#    (把样本留在本文件的 heredoc 里, 本闸就会红在自己的测试样本上 —— 第一版正是如此。)
probe="$ROOT_DIR/scripts/tests/pipeline-tail-rc-shapes.txt"
[ -s "$probe" ] || fail "2: 找不到验收样本 $probe —— **没量到**, ⛔ 不当成通过"
probe_out=$(scan "$probe" || true)
hits=$(printf '%s' "$probe_out" | cut -d: -f1 | tr '\n' ' ')
# 样本文件: 前 4 行注释, 第 5 行 = 形态 ①, 第 6/7/8 行 = 形态 ②③④
for want in 6 7 8; do
    printf '%s' "$hits" | grep -qw "$want" \
        || fail "2: 样本第 ${want} 行的形状没被抓到 —— 本闸的验收判据就是这四个形状, 命中集实得 [$hits]"
done
if printf '%s' "$hits" | grep -qw 5; then
    fail "2: 形态 ①(不带管道的命令替换, 样本第 5 行) 被抓到了 —— 它**本应抓不到**(见文件头盲区 1)。
  ⚠️ 抓到它说明正则宽了, 而宽了的代价是存量涨到不可用。"
fi
echo "READING: PIPE_RC_ACCEPTANCE caught=[${hits% }] expect=[6 7 8] blind=[5:形态①] coverage=3/4"
ok "四个真实形状: ②③④ 命中, ① 如实抓不到(盲区已在文件头写明)"

# ---- 3. 只拦**新增/改动**的行 ----
# ⚠️ 拿不到 diff base 时报「没量到」, ⛔ 不静默通过 ——
#    「没有新增违规」与「我根本没在比」在退出码上同形。
BASE=""
for cand in "${PIPE_RC_DIFF_BASE:-}" origin/main main; do
    [ -n "$cand" ] || continue
    if git -C "$ROOT_DIR" rev-parse --verify -q "${cand}^{commit}" >/dev/null 2>&1; then
        BASE="$cand"
        break
    fi
done
if [ -z "$BASE" ]; then
    echo "READING: PIPE_RC_NEW base=<unavailable> —— **没量到**(浅克隆? 拿不到 origin/main), ⛔ 不是「没有新增」"
    ok "diff base 不可用, 已如实报告(⛔ 未当成通过)"
else
    MB=$(git -C "$ROOT_DIR" merge-base "$BASE" HEAD 2>/dev/null || true)
    [ -n "$MB" ] || MB="$BASE"
    raw=$(git -C "$ROOT_DIR" diff -U0 "${MB}...HEAD" -- 'scripts/*.sh' 'scripts/**/*.sh' 2>/dev/null || true)
    # 🔴 必须 `grep -vE`, ⛔ 不能是 `grep -v '^\+\+\+'` ——
    #    BRE 里 `\+` 是 GNU 的**「一个或多个」量词**, ⛔ 不是字面加号
    #    ⇒ 那个写法把 **146 行过滤成 0 行**, 于是 added_violations **恒为 0**, 本闸恒绿。
    #    ⚠️ 抓住它的是「加一行真违规再跑」这个变异, ⛔ 不是读代码 ——
    #      它坏掉时给出的正是「没有新增违规」这个让人放心的答案。
    plus=$(printf '%s\n' "$raw" | grep -E '^\+' | grep -vE '^\+\+\+' || true)
    # ⛔ 这两步刻意分开: 写成 `stripped=$(printf … | sed …)` 就是本闸要拦的形状本身,
    #    而**第一版我就是那么写的** —— 是本闸在自己的 diff 上把作者拓了下来。
    stripped=""
    while IFS= read -r ln; do stripped+="${ln#+}"$'\n'; done <<< "$plus"
    # ⛔ 与 scan() 用**同一个** COMMENT_RE —— 见上面那段: 这里漏掉它, 判据就分叉了。
    violating=$(printf '%s' "$stripped" | grep -E "$PAT" | grep -vE "^${COMMENT_RE}" || true)
    added=$(count_lines "$violating")
    echo "READING: PIPE_RC_NEW base=$BASE merge_base=${MB:0:10} added_violations=$added"
    if [ "$added" -ne 0 ]; then
        fail "3: 本次改动**新增** $added 处「管道尾部吞掉退出码」:
$(printf '%s\n' "$violating" | sed 's/^/     /')
  ⇒ 形如 x=\$(cmd | head/tail/sed/awk): **退出码是管道尾部那个命令的**, 前面的命令失败读不出来。
  ⇒ 改法: 先把输出落进变量(无管道)再单独判/数; 或显式检查 PIPESTATUS。
  ⚠️ 存量 $n_hit 处**不红**(本闸只拦新增) —— ⛔ 别顺手去改存量, 那是另一件事。"
    fi
    ok "本次改动没有新增此形状 (added=$added)"
fi

# ---- 4. 边界打成读数 ----
echo "READING: PIPE_RC_SCOPE=new-lines-only blind_spots=2 (①无管道的命令替换 / 硬约束1前半:内容截断)"
echo "READING: PIPE_RC_HARD_CONSTRAINT_1_COVERAGE=partial ⇒ ⛔ 不许读成「硬约束 1 已有闸」"
ok "边界已声明(两个盲区 + 覆盖率如实)"

echo "PASS: $pass 项断言全部通过"
