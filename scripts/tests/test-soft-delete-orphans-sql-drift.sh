#!/usr/bin/env bash
# 仓库里那份 soft-delete-orphans.sql 与「生成器现在会产出什么」必须一致。
#
# ═══════════════════════════════════════════════════════════════════════════
# 形态 D: 同一个东西有两份, 它一定会漂
# ═══════════════════════════════════════════════════════════════════════════
# 把生成物入库解决了「服务器上那份从哪来」, 但当场造出了新的一对: **仓库份** 与
# **生成器当前产出**。改了实体(加/删一个 @ManyToOne)而忘了重跑 --write, 两份就分家 ——
# 而它不报错、不变红: SQL 照样能跑, 只是扫描面停在改动之前。
#
# ⛔ 「记得重新生成」不构成约束力(这正是这个 watch 脚本自己存在的理由)。⇒ 让它自己会响。
#
# ── 为什么这道闸在 CI 里跑得起来 ────────────────────────────────────────
# 生成器唯一的数据库输入是「哪些表有 deleted_at」。那份名单已经被 --write 快照进
# scripts/cron/soft-delete-orphans.hasdel.txt, 于是生成变成了纯函数:
#     实体源码 + 快照  →  SQL
# `--regen-offline` 就是拿快照重放一次, ⛔ 不连库、不编译 Java、不需要 JDK。
#
# ── 比对时剔除两样东西, 各有理由 ────────────────────────────────────────
#   ① 第 1 行出处戳: 含 commit 与 UTC 时间, **每次重放必然不同**, 比它等于恒红。
#   ② BEGIN/END_MANUAL_SECTION 之间: 手工登记段由
#      test-soft-delete-orphans-manual-pairs.sh 单独守(它核对每一对是否解析到真实列)。
#      这里只守**注解扫描**那一段, 两道闸职责不重叠。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GEN="$ROOT_DIR/scripts/check-soft-delete-orphans.sh"
SQL="$ROOT_DIR/scripts/cron/soft-delete-orphans.sql"
HASDEL="$ROOT_DIR/scripts/cron/soft-delete-orphans.hasdel.txt"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

[ -s "$GEN" ] || fail "$GEN 不存在"
[ -s "$SQL" ] || fail "$SQL 不存在"
[ -s "$HASDEL" ] || fail "$HASDEL 不存在 —— 没有快照就没法离线重放, 跑一次 --write"

# 阳性对照: 快照本身得像个快照。个位数行数 = 抓的时候查询就坏了。
HN=$(grep -c . "$HASDEL")
[ "$HN" -ge 50 ] || fail "快照只有 $HN 行 (预期数百张) —— 本次读数作废"
ok "hasdel 快照 $HN 张表"

command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 \
    || fail "需要 python 才能跑生成器"

# ── 离线重放 ──────────────────────────────────────────────────────────────
if ! bash "$GEN" --regen-offline > "$TMP/regen.sql" 2> "$TMP/regen.err"; then
    echo "--- 生成器 stderr ---"; cat "$TMP/regen.err"
    fail "--regen-offline 跑失败 (rc≠0) —— 仪器坏了, 本次读数作废"
fi
[ -s "$TMP/regen.sql" ] || fail "--regen-offline 产出为空 —— 仪器坏了"

# 剔除出处戳(第 1 行) + 手工段
strip() {
    tail -n +2 "$1" \
        | awk '/^-- BEGIN_MANUAL_SECTION/{s=1} /^-- END_MANUAL_SECTION/{s=0;next} !s'
}
strip "$SQL"        > "$TMP/repo.norm"
strip "$TMP/regen.sql" > "$TMP/gen.norm"

# 阳性对照: 剔完不能把内容剔没了。⛔ 两个空文件也「逐字相同」——
# 那是最容易伪造的绿(硬约束 9: 「没差异」必须配一个「一定不为空」的读数)。
RL=$(grep -c . "$TMP/repo.norm"); GL=$(grep -c . "$TMP/gen.norm")
[ "$RL" -ge 10 ] && [ "$GL" -ge 10 ] \
    || fail "剔除后行数异常 (仓库 $RL / 重放 $GL) —— 标记块或出处戳的形状变了, 本次读数作废"
ok "剔除出处戳与手工段后: 仓库 $RL 行 / 重放 $GL 行"

# ── 形态 C″: 先把 diff 行数打出来, 再断言 ─────────────────────────────────
diff -u "$TMP/repo.norm" "$TMP/gen.norm" > "$TMP/d.txt"
DRC=$?
DL=$(grep -cE '^[+-][^+-]' "$TMP/d.txt" || true)
echo "    diff 行数 = $DL (rc=$DRC)"
if [ "$DRC" -ne 0 ]; then
    echo "--- 前 20 行差异 ---"
    head -20 "$TMP/d.txt"
    fail "仓库里的 soft-delete-orphans.sql 与生成器当前产出不一致 ⇒ 跑 ./scripts/check-soft-delete-orphans.sh --write 重新生成并提交"
fi
ok "注解扫描段逐字一致 (diff 0 行)"

# ── 手工段确实还在(⛔ 别把它一起剔没了) ───────────────────────────────────
grep -q '^-- BEGIN_MANUAL_SECTION' "$SQL" || fail "仓库份缺 BEGIN_MANUAL_SECTION 标记"
grep -q '^-- END_MANUAL_SECTION' "$SQL" || fail "仓库份缺 END_MANUAL_SECTION 标记"
MS=$(awk '/^-- BEGIN_MANUAL_SECTION/{s=1;next} /^-- END_MANUAL_SECTION/{s=0} s' "$SQL" | grep -c 'UNION ALL')
[ "$MS" -ge 1 ] || fail "手工段里一条都没有 —— 标记块位置不对?"
ok "手工段 $MS 条, 标记完整"

echo
echo "PASS: test-soft-delete-orphans-sql-drift.sh"
