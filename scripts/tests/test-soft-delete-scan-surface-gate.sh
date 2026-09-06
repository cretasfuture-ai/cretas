#!/usr/bin/env bash
# 新增一个带 deleted_at 的实体, 必须在**那个 PR 上**就被说出来, 而不是等 main 变红。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么 (票 OPS-SOFT-DELETE-SCAN-SURFACE-DRIFTS-ON-NEW-ENTITY-20260907)
# ═══════════════════════════════════════════════════════════════════════════
# scripts/cron/soft-delete-orphans.sql 是生成物。新增/删除带 deleted_at 的实体会让它与
# 生成器产出不一致, 而这件事此前**只在 main 上**被 ops-script-tests 发现:
#   #665 一次; #685 又一次(430→431), 并把两个无关 PR 连坐红。
# ⇒ 代价落在下一个碰 CI 的人身上, 而不是引入漂移的那个 PR。
#
# 本测试守两件事:
#   A. 行为: **真造一个带 deleted_at 的新实体**, 证明生成器产出变了(= 那道权威检查会红);
#      删掉它就恢复。⛔ 不用「文件改没改」这种代理判据 —— 那会把「改了但改错」放过去。
#   B. 接线: 新 workflow 的 paths 必须覆盖生成器**实际扫描的两个根**(entity/ 与
#      repository/)。少一个, 闸就在那半棵树上静默不触发 —— 与「闸不存在」读数相同(形态 B)。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
GEN="$ROOT_DIR/scripts/check-soft-delete-orphans.sh"
WORKFLOW="$ROOT_DIR/.github/workflows/soft-delete-scan-surface.yml"
DRIFT_TEST="scripts/tests/test-soft-delete-orphans-sql-drift.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASSED=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "  ok  [$PASSED] $*"; }

[ -s "$GEN" ] || fail "生成器不存在: $GEN"
[ -s "$WORKFLOW" ] || fail "workflow 不存在: $WORKFLOW"

if ! command -v python >/dev/null 2>&1 && ! command -v python3 >/dev/null 2>&1; then
    echo "SKIPPED:没有 python, 生成器跑不了(CI 上有垫片)"
    exit 77
fi

# ---------------------------------------------------------------------------
# A. 行为: 真加一个带 deleted_at 的实体, 产出必须变
# ---------------------------------------------------------------------------
# 在临时根上做, ⛔ 不往真源码树里写文件 —— 并发 session 会看到它(concurrent-edit-safety)。
SRC_REL="backend/java/cretas-api/src/main/java/com/cretas/aims"
FAKE_ROOT="$TMP/root"
mkdir -p "$FAKE_ROOT/$SRC_REL" "$FAKE_ROOT/scripts/cron"
cp -r "$ROOT_DIR/$SRC_REL/entity" "$FAKE_ROOT/$SRC_REL/entity"
cp -r "$ROOT_DIR/$SRC_REL/repository" "$FAKE_ROOT/$SRC_REL/repository"
cp "$ROOT_DIR/scripts/cron/soft-delete-orphans.sql" "$FAKE_ROOT/scripts/cron/"
cp "$ROOT_DIR/scripts/cron/soft-delete-orphans.hasdel.txt" "$FAKE_ROOT/scripts/cron/"

regen() { SDO_REPO_ROOT="$FAKE_ROOT" bash "$GEN" --regen-offline 2>"$TMP/err.txt"; }

BEFORE="$TMP/before.sql"
regen > "$BEFORE" || { cat "$TMP/err.txt" >&2; fail "A 基线: --regen-offline 跑不起来 —— 仪器坏了, 本次读数作废"; }
[ -s "$BEFORE" ] || fail "A 基线: 产出为空 —— 仪器坏了"
BEFORE_LINES=$(wc -l < "$BEFORE")
# 阳性对照: 临时根重放出来的东西必须**与仓库里那份等价**(剔掉出处戳那几行),
# 否则后面「变了」可能只是我这套 harness 与真实不一致, 而不是新实体造成的。
norm() { grep -v -E '^-- (生成|provenance|generated|来源)' "$1" | sed -E 's/[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9:]+//g'; }
norm "$BEFORE" > "$TMP/before.norm"
norm "$ROOT_DIR/scripts/cron/soft-delete-orphans.sql" > "$TMP/repo.norm"
if ! diff -q "$TMP/before.norm" "$TMP/repo.norm" >/dev/null; then
    echo "READING: BASELINE_DIFF_LINES=$(diff -u "$TMP/repo.norm" "$TMP/before.norm" | grep -c '^[-+][^-+]' || true)"
    fail "A 阳性对照: 临时根重放的产出与仓库里那份不一致 —— 要么 main 上本来就漂着(先跑 --write), 要么这套 harness 不忠实, 两种都让下面的读数作废"
fi
echo "READING: BASELINE_LINES=$BEFORE_LINES baseline_matches_repo=yes"
ok "临时根重放 == 仓库里那份 (harness 忠实, 后面的「变了」才归因得到新实体)"

# 真加一个带 @Table + deleted_at 的实体 —— 与 #672 那次(platform_role_permission_audits)同形。
FAKE_ENTITY="$FAKE_ROOT/$SRC_REL/entity/ProbeScanSurfaceDriftEntity.java"
cat > "$FAKE_ENTITY" <<'JAVA'
package com.cretas.aims.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "probe_scan_surface_drift")
public class ProbeScanSurfaceDriftEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factory_id")
    private Factory factory;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
JAVA
# hasdel 快照是「库里哪些表有 deleted_at」——新表也要在里面, 否则生成器按「这张表没有软删列」跳过它。
printf 'probe_scan_surface_drift\n' >> "$FAKE_ROOT/scripts/cron/soft-delete-orphans.hasdel.txt"

AFTER="$TMP/after.sql"
regen > "$AFTER" || { cat "$TMP/err.txt" >&2; fail "A: 加了新实体之后生成器跑不起来"; }
norm "$AFTER" > "$TMP/after.norm"
DIFF_LINES=$(diff -u "$TMP/before.norm" "$TMP/after.norm" | grep -c '^[-+][^-+]' || true)
echo "READING: DRIFT_DIFF_LINES=$DIFF_LINES (新增一个带 deleted_at 的实体之后)"
# ⚠️ 这个信号目前**骑在生成物的统计头那一行**上(「带 @Table 的实体 N 个 …」), 而不是新增了一个
#    UNION ALL 扫描块 —— 探针实体的 @ManyToOne 没有产生新块(#705 终审实测)。闸断的是**全文件相等**,
#    所以今天成立; 但哪天有人把那段统计从生成物头部去掉, 本组变异就会**静默**。
#    ✅ 失败模式是安全的: 那时 DIFF_LINES=0, 下面这条断言自己会响。⇒ 换了生成物格式之后,
#    回来确认这条变异**还动得起来**, ⛔ 别只看它是不是绿的。
[ "$DIFF_LINES" -gt 0 ] \
    || fail "A: 新增一个带 deleted_at 的实体, 生成器产出**一点没变** —— 那这道闸对这类改动是瞎的(而它正是 #665/#685 两次的成因)"
ok "新增带 deleted_at 的实体 ⇒ 生成器产出变了 $DIFF_LINES 行 (权威检查会红)"

# 阴性对照: 把它删掉必须**完全恢复** —— 否则「变了」可能来自别的东西(例如我改了 hasdel)。
rm -f "$FAKE_ENTITY"
sed -i.bak '$d' "$FAKE_ROOT/scripts/cron/soft-delete-orphans.hasdel.txt" 2>/dev/null \
    || sed -i '' -e '$d' "$FAKE_ROOT/scripts/cron/soft-delete-orphans.hasdel.txt"
RESTORED="$TMP/restored.sql"
regen > "$RESTORED" || fail "A 阴性对照: 删掉之后生成器跑不起来"
norm "$RESTORED" > "$TMP/restored.norm"
diff -q "$TMP/before.norm" "$TMP/restored.norm" >/dev/null \
    || fail "A 阴性对照: 删掉那个实体之后没有恢复原状 —— 上面那 $DIFF_LINES 行的归因不成立"
ok "删掉之后逐字恢复 (那 $DIFF_LINES 行确实是这个实体造成的, 不是别的东西)"

# ---------------------------------------------------------------------------
# B. 接线: workflow 的 paths 必须覆盖生成器扫描的两个根
# ---------------------------------------------------------------------------
# 从生成器里**读出**它扫哪两个根, ⛔ 不在测试里再抄一遍常量(那就是第二份会漂的副本)。
ENTITY_REL=$(grep -E '^ENTITY_ROOT=' "$GEN" | sed -E 's|.*JAVA_SRC/||; s|"||g')
REPO_REL=$(grep -E '^REPOSITORY_ROOT=' "$GEN" | sed -E 's|.*JAVA_SRC/||; s|"||g')
[ -n "$ENTITY_REL" ] && [ -n "$REPO_REL" ] \
    || fail "B: 从生成器里读不出 ENTITY_ROOT / REPOSITORY_ROOT(它改写法了?) —— 读不出就不能假装钉住了"
echo "READING: GENERATOR_SCAN_ROOTS=$ENTITY_REL,$REPO_REL"
# ⚠️ 只在**解析出来的 `paths:` 块**里找, ⛔ 不 grep 整个 workflow 文件 ——
#    将来某条注释里出现字面量 `com/cretas/aims/entity/**`, 下面两组就会恒真
#    (与本文件后面那处 `|| true` 检查同一个坑: 那里已经先 sed 剥了注释, 这里补齐, 免得用得不均匀)。
PATHS_BLOCK=$(awk '
    /^[[:space:]]*paths:[[:space:]]*$/ { inb = 1; next }
    inb && /^[[:space:]]*-[[:space:]]/ { sub(/#.*$/, ""); print; next }
    inb && /^[[:space:]]*#/           { next }          # 块内的整行注释: 跳过, 不算条目
    inb && NF                          { inb = 0 }      # 块结束(下一个键)
' "$WORKFLOW")
PATHS_COUNT=$(printf '%s\n' "$PATHS_BLOCK" | grep -c . || true)
echo "READING: PATHS_ENTRIES=$PATHS_COUNT"
[ "$PATHS_COUNT" -ge 5 ] \
    || fail "B: 解析 on.pull_request.paths 只得到 $PATHS_COUNT 条 —— 解析器和 workflow 对不上了, 后面两组的读数作废"

in_paths() { printf '%s\n' "$PATHS_BLOCK" | grep -q -- "$1"; }

for rel in "$ENTITY_REL" "$REPO_REL"; do
    in_paths "com/cretas/aims/$rel/\*\*" \
        || fail "B: paths 没覆盖生成器扫描的 '$rel/' —— 改那半棵树时闸静默不触发(与闸不存在同形)"
done
ok "paths 覆盖生成器扫描的两个根 ($ENTITY_REL, $REPO_REL); 判据只看解析出的 paths 块"

for p in scripts/check-soft-delete-orphans.sh scripts/cron/soft-delete-orphans.sql \
         scripts/cron/soft-delete-orphans.hasdel.txt; do
    in_paths "$p" || fail "B: paths 少了 $p —— 改它自己时这道闸不跑"
done
ok "生成器与两份快照也在 paths 里"

grep -q "$DRIFT_TEST" "$WORKFLOW" \
    || fail "B: workflow 没有跑那道权威检查 ($DRIFT_TEST)"
# ⚠️ 先剥掉整行注释再 grep —— 这个 workflow 的说明文字里就写着「没有 `|| true`」几个字,
#    不剥就是「闸把自己的注释数进去」(本仓反复踩; 我在 test-ci-gate-runs-merge-result.sh
#    里刚踩过一次方向相反的: 用 `grep -n | grep -v '^\s*#'` 过滤, 而行号前缀让过滤器恒不命中)。
if sed -E 's/^[[:space:]]*#.*$//' "$WORKFLOW" | grep -qE '\|\| true|\| *tail|\| *head'; then
    fail "B: workflow 的**可执行行**里出现 '|| true' 或管道尾 —— 那会把闸的 rc 吞掉"
fi
ok "跑的是权威检查本身, 且 rc 没被吞"

# ⛔ 不许把这些路径塞进 ops-script-tests 的 paths(2026-08-28: 加宽别人的 glob 会把那边的
# 阴性对照变成恒真式)。这里断言实体路径**没有**出现在那个 workflow 里。
OPS_WF="$ROOT_DIR/.github/workflows/ops-script-tests.yml"
if [ -f "$OPS_WF" ] && grep -q "com/cretas/aims/$ENTITY_REL" "$OPS_WF"; then
    fail "B: 实体路径被塞进了 ops-script-tests.yml 的 paths —— 新闸该有自己的 workflow"
fi
ok "没有往 ops-script-tests 的 paths 里塞东西"

echo "PASS: test-soft-delete-scan-surface-gate.sh ($PASSED 组断言)"
