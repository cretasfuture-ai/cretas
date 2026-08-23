#!/usr/bin/env bash
#
# 软删孤儿扫描 —— 找出「会被序列化、且目标已软删」的 @ManyToOne 关联。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要它 (2026-08-21 实测)
# ═══════════════════════════════════════════════════════════════════════════
# 软删了 26 个账号之后, 客户列表整个 500:
#     EntityNotFoundException: Unable to find com.cretas.aims.entity.User with id 1610
# 成因: 客户的 created_by 指着那个账号, 而 User 继承 BaseEntity 带类级
# @Where(deleted_at IS NULL) —— Hibernate 解析 @ManyToOne 代理时「找不到」那一行, 抛异常。
#
# 软删是全局的(601 个实体里 347 个带), 所以这是个**全库形状**, 不是客户表独有。
#
# ═══════════════════════════════════════════════════════════════════════════
# ⛔ 判据必须是两个条件的**交集** —— 单看任何一边都会做错事
# ═══════════════════════════════════════════════════════════════════════════
#   条件 A: 关联会被序列化 (没有 @JsonIgnore)。有 @JsonIgnore 的代理**根本不会被解析**,
#           永远不炸。
#   条件 B: 真的有行指向已软删的目标。
#
# 实测教训 (同一天栽了三次):
#   ① 只看 B  → 差点去修 notifications(40 行) 和 internal_transfers(2 行),
#              而它们**都有 @JsonIgnore, 不会炸**。
#   ② 查 B 时忘了过滤 `x.deleted_at IS NULL` → 把**已经软删的行**也数成坏行,
#              执行修复时 `UPDATE 0`, 因为它们本来就是干净的。
#              ▎软删让每个不带 deleted_at 过滤的查询都在说谎。
#   ③ 假设「所有表都有 deleted_at」→ ai_usage_log 没有, SQL 直接报错。
#              ⇒ 只查**两端都有软删列**的关联。
#
#   只看 A 也不行: 会去改 60+ 个当前无害的关联, 而 @NotFound(IGNORE) 在 Hibernate 6 下
#   把关联**强制成 EAGER**, 大表上一起加会造成 N+1。
#
# ═══════════════════════════════════════════════════════════════════════════
# 用法
# ═══════════════════════════════════════════════════════════════════════════
#   ./scripts/check-soft-delete-orphans.sh                 # 扫 prod
#   ./scripts/check-soft-delete-orphans.sh --sql-only      # 只打印 SQL, 不连库
#
# ⚠️ **删任何东西之前跑一次** —— 账号、客户、供应商、单据都算。
#
# 退出码 (三态, ⛔ 不是两态):
#   0  没有孤儿
#   1  找到孤儿 (读数有效, 且指向缺陷)
#   2  **没量到** —— 连不上库 / 一条关联都没扫到 / 阳性对照失败。
#      ⛔ 不要把 2 读成 0。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENTITY_ROOT="$REPO_ROOT/backend/java/cretas-api/src/main/java/com/cretas/aims/entity"
SSH_HOST="${CRETAS_SSH_HOST:-root@47.100.235.168}"
SQL_ONLY=0
[ "${1:-}" = "--sql-only" ] && SQL_ONLY=1

[ -d "$ENTITY_ROOT" ] || { echo "⛔ 找不到实体目录: $ENTITY_ROOT" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── 1. 问活进程要 DB 名 ────────────────────────────────────────────────────
#    ⛔ 不要用配置里的默认值: prod 是 cretas_prod_db, 而 properties 默认写的是
#       ${DB_NAME:cretas_db} —— 本仓已经因此误报过「迁移失败」。
REMOTE_ENV='PID=$(ss -lntp 2>/dev/null | grep -E ":(10010|10020)" | grep -oP "pid=\K[0-9]+" | head -1)
[ -n "$PID" ] || { echo "NO_PID" >&2; exit 2; }
eval "$(tr "\0" "\n" < /proc/$PID/environ | grep -E "^DB_" | sed "s/^/export /")"
export PGPASSWORD="$DB_PASSWORD"'

# ⚠️ --sql-only 也要连库取「哪些表有 deleted_at」—— 它的用途是生成一份**能独立跑**的 SQL,
#    不过滤的话会混进无 deleted_at 的表(实测 ai_usage_log), 拿去跑当场报错。
if true; then
  ssh "$SSH_HOST" "$REMOTE_ENV"'
psql -h localhost -U "${DB_USERNAME:-cretas_user}" -d "$DB_NAME" -tAc \
  "SELECT table_name FROM information_schema.columns
   WHERE table_schema = current_schema() AND column_name = '"'"'deleted_at'"'"'"' \
    > "$WORK/hasdel.txt" 2>"$WORK/err.txt" || { echo "⛔ 连库失败:"; cat "$WORK/err.txt"; exit 2; }
  HASDEL_COUNT=$(grep -c . "$WORK/hasdel.txt" || true)
  # 阳性对照: 带软删的表应该有几百张。个位数 = 查询本身出问题了。
  if [ "${HASDEL_COUNT:-0}" -lt 50 ]; then
    echo "⛔ 阳性对照失败: 只查到 $HASDEL_COUNT 张带 deleted_at 的表 (预期数百张)。"
    echo "   本次读数作废 —— ⛔ 不要当成「没有孤儿」。"
    exit 2
  fi
fi

# ── 2. 扫实体, 生成 SQL ───────────────────────────────────────────────────
python - "$ENTITY_ROOT" "$WORK/hasdel.txt" "$SQL_ONLY" > "$WORK/scan.sql" <<'PYEOF'
import io, os, re, sys
entity_root, hasdel_path, sql_only = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
hasdel = set(l.strip() for l in io.open(hasdel_path, encoding="utf-8") if l.strip())

soft, ents = set(), {}
for dp, _, fs in os.walk(entity_root):
    for fn in fs:
        if not fn.endswith(".java"):
            continue
        s = io.open(os.path.join(dp, fn), encoding="utf-8", errors="replace").read()
        ents[fn[:-5]] = s
        # 软删来源有两种: 类级 @Where(deleted_at ...) 或继承 BaseEntity
        if re.search(r'@Where\s*\(\s*clause\s*=\s*"[^"]*deleted_at', s) or re.search(r"extends\s+BaseEntity", s):
            soft.add(fn[:-5])

found = set()
for cls, s in ents.items():
    lines = s.split("\n")
    tbl = re.search(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"', s)
    for i, line in enumerate(lines):
        m = re.match(r"\s*private\s+([A-Z]\w+)\s+(\w+)\s*;", line)
        if not m or m.group(1) not in soft:
            continue
        # 往上收这个字段的注解块(到上一个语句/块结束为止)
        blk, j = [], i - 1
        while j >= 0 and not re.search(r"[;}]\s*$", lines[j].strip()):
            blk.append(lines[j]); j -= 1
            if i - j > 12:
                break
        ann = "\n".join(blk)
        if "@ManyToOne" not in ann:
            continue
        # 条件 A: 有 @JsonIgnore 就不会被序列化 -> 代理不解析 -> 不会炸
        if "JsonIgnore" in ann or "NotFound" in ann:
            continue
        col = re.search(r'name\s*=\s*"([^"]+)"', ann)
        tt = re.search(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"', ents.get(m.group(1), ""))
        if not (tbl and col and tt):
            continue
        # ⛔ 只查两端都有 deleted_at 列的 —— 否则 SQL 直接报错(实测 ai_usage_log)。
        # ⚠️ --sql-only 也必须过滤: 第一版这里写的是 `if not sql_only and ...`,
        #    于是 --sql-only 生成的 SQL 里混进了 ai_usage_log(无 deleted_at),
        #    拿去服务器跑当场报错。--sql-only 的用途正是「生成一份能独立跑的 SQL」,
        #    跳过过滤等于生成一份跑不了的。
        #    ⇒ --sql-only 时用 fallback 名单(hasdel 为空则不过滤, 仅用于离线看结构)。
        if hasdel and (tbl.group(1) not in hasdel or tt.group(1) not in hasdel):
            continue
        found.add((tbl.group(1), col.group(1), tt.group(1)))

if not found:
    sys.stderr.write("NO_ASSOC\n")
    sys.exit(0)

parts = [
    f"SELECT '{t}.{c} -> {tt}' AS assoc, count(*) AS bad FROM public.{t} x "
    f"WHERE x.deleted_at IS NULL AND x.{c} IS NOT NULL AND NOT EXISTS "
    f"(SELECT 1 FROM public.{tt} y WHERE y.id::text = x.{c}::text AND y.deleted_at IS NULL)"
    for t, c, tt in sorted(found)
]
print("SELECT * FROM (\n  " + "\n  UNION ALL ".join(parts) + "\n) q WHERE bad > 0 ORDER BY bad DESC;")
sys.stderr.write(f"SCANNED={len(found)}\n")
PYEOF
PY_RC=$?
[ "$PY_RC" -eq 0 ] || { echo "⛔ 扫描实体失败 (rc=$PY_RC)"; exit 2; }

ASSOC_COUNT=$(grep -c "UNION ALL" "$WORK/scan.sql" 2>/dev/null || echo 0)
ASSOC_COUNT=$((ASSOC_COUNT + 1))
if [ ! -s "$WORK/scan.sql" ]; then
  echo "⛔ 一条关联都没扫到 —— 仪器坏了, 本次读数作废。"
  echo "   (正常应有数十条。实体目录结构变了? 注解写法变了?)"
  exit 2
fi

if [ "$SQL_ONLY" -eq 1 ]; then
  cat "$WORK/scan.sql"
  exit 0
fi

echo "扫到 $ASSOC_COUNT 条「会被序列化且无容错」的关联, 开始查坏行…"

# ── 3. 执行 ───────────────────────────────────────────────────────────────
scp -q "$WORK/scan.sql" "$SSH_HOST:/tmp/.sdo_scan.sql" || { echo "⛔ 上传 SQL 失败"; exit 2; }
ssh "$SSH_HOST" "$REMOTE_ENV"'
psql -h localhost -U "${DB_USERNAME:-cretas_user}" -d "$DB_NAME" -f /tmp/.sdo_scan.sql
rm -f /tmp/.sdo_scan.sql' > "$WORK/out.txt" 2>&1
RC=$?
cat "$WORK/out.txt"
[ "$RC" -eq 0 ] || { echo "⛔ 查询执行失败 —— 本次读数作废"; exit 2; }

if grep -qE "^\(0 rows\)" "$WORK/out.txt"; then
  echo
  echo "✓ 没有软删孤儿。"
  exit 0
fi
echo
echo "🔴 找到软删孤儿 —— 上面每一条现在都可能让对应页面 500。"
echo "   修法看情况, ⛔ 不要一刀切:"
echo "     · 子表指向父表(明细/单据)  → 多半是**软删父行时没级联**, 应补级联, ⛔ 不是加 @NotFound"
echo "     · 审计字段(created_by 等)  → 加 @NotFound(IGNORE)。那个人离职/注销是常态,"
echo "                                  ⛔ 不要改成「删之前先清引用」(那等于篡改历史)"
echo "     · 测试残留                 → 清掉数据即可, ⛔ 不动代码"
exit 1
