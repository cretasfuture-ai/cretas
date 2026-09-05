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
#   ./scripts/check-soft-delete-orphans.sh --write         # 生成并写回仓库里那份 SQL
#
# 仓库里那份是 scripts/cron/soft-delete-orphans.sql —— 它是**装到服务器上给 cron 用的
# 那一份的源**。以前它只在服务器上存在(手 scp 上去的), 于是「跑的到底是哪一版」没人
# 答得出来, 而 watch 脚本只能靠比 mtime 猜(形态 D: 同一个东西有两份, 它一定会漂)。
#
# 生成的 SQL 头部会盖一行 `-- generated-from <commit> <UTC>` —— watch 脚本读它来判
# 扫描面的出处, ⛔ 不再比 mtime(jar 每次部署都变新, 那个判据永远为真)。
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
REPO_SQL="$REPO_ROOT/scripts/cron/soft-delete-orphans.sql"
SQL_ONLY=0
WRITE_MODE=0
case "${1:-}" in
  --sql-only) SQL_ONLY=1 ;;
  --write)    SQL_ONLY=1; WRITE_MODE=1 ;;
  "")         ;;
  *) echo "⛔ 未知参数: $1 (用法见文件头)" >&2; exit 2 ;;
esac

[ -d "$ENTITY_ROOT" ] || { echo "⛔ 找不到实体目录: $ENTITY_ROOT" >&2; exit 2; }

# ═══════════════════════════════════════════════════════════════════════════
# 手工登记的关联对 —— 扫描器**结构上看不见**的那些
# ═══════════════════════════════════════════════════════════════════════════
# 上面那个扫描器认的是 `@ManyToOne` 字段。**标量外键列(`@Column private String xxxId`)
# 对它完全不存在** —— 不是漏扫, 是它按定义就看不到。
#
# ⛔ 不要为此去改注解扫描器: 「哪些 String 字段其实是外键」在源码里没有可判定的标记,
#    照名字猜(`*Id` 结尾)会把 factoryId / batchNumber 这类一起卷进来, 做出一个误报
#    满地的闸, 然后被人关掉(形态 E)。
# ⇒ 显式登记, 每条写清**为什么它该在这儿**。登记是留痕, 不是豁免。
#
# ⚠️ 加一条之前先在 prod 上量一次「坏行数 / 总行数」, 把两个数都写进理由里 ——
#    只有坏行数看不出这条判据有没有区分度(形态 B⁴: 全是坏行的闸和恒真式一样没用)。
#
# 格式: 子表|外键列|父表|理由
# BEGIN_MANUAL_PAIRS
MANUAL_PAIRS='
process_sheet_rows|plan_id|production_plans|ProcessSheetRow.planId 是 @Column 标量 String, 不是 @ManyToOne ⇒ 注解扫描器看不见。prod 2026-09-06 实测 56 坏 / 132 总。
report_reversal_logs|plan_id|production_plans|ReportReversalLog.planId 同为 @Column 标量 String。prod 2026-09-06 实测 10 坏 / 15 总。
'
# END_MANUAL_PAIRS
#
# ⛔ 明确**不**登记(登记「故意不改」也是留痕):
#   · scheduling_alerts.plan_id           (prod 170 条) —— 告警日志表
#   · process_sheet_row_change_log.plan_id (prod  67 条) —— 变更日志表
#   这两张是**日志/审计**类: 指向一条后来被删掉的计划是它们的**正常状态**, 不是缺陷
#   (那正是「审计字段」那一档)。把它们登记进来等于每天报一次不需要行动的孤儿,
#   而天天误报的闸最后会被关掉。要收它们需要各自的理由和各自的修法。

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── 1. 问活进程要 DB 名 ────────────────────────────────────────────────────
#    ⛔ 不要用配置里的默认值: prod 是 cretas_prod_db, 而 properties 默认写的是
#       ${DB_NAME:cretas_db} —— 本仓已经因此误报过「迁移失败」。
# 🔴 PATH 要自己钉: `ss` 只在 /usr/sbin, 而非交互式 shell 的 PATH 不保证含它。
#    姊妹脚本 scripts/cron/soft-delete-orphans-watch.sh 就是栽在这上面 ——
#    cron 默认 PATH=/usr/bin:/bin ⇒ `ss: command not found` ⇒ 连续 15 天 rc=2。
REMOTE_ENV='export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
command -v ss >/dev/null || { echo "NO_SS" >&2; exit 2; }
PID=$(ss -lntp | grep -E ":(10010|10020)" | grep -oP "pid=\K[0-9]+" | head -1)
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
# 出处戳。⚠️ 工作树脏就带 -dirty 后缀 —— 那样它**永远匹配不上任何 jar 的 commit**,
# watch 脚本会报 STALE, 这正是想要的(一份从未提交的实体状态生成的 SQL 本来就说不清出处)。
PROV_COMMIT=$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)
if [ -n "$(git -C "$REPO_ROOT" status --porcelain -- "$ENTITY_ROOT" 2>/dev/null)" ]; then
  PROV_COMMIT="$PROV_COMMIT-dirty"
fi
PROV_STAMP="-- generated-from $PROV_COMMIT $(date -u '+%F %TZ')"

printf '%s\n' "$MANUAL_PAIRS" > "$WORK/manual.txt"

python - "$ENTITY_ROOT" "$WORK/hasdel.txt" "$SQL_ONLY" "$WORK/manual.txt" "$PROV_STAMP" > "$WORK/scan.sql" <<'PYEOF'
import io, os, re, sys
# ⛔ Windows 上 print 默认吐 CRLF —— 生成物会带着 \r 进仓库、再被装到 Linux 服务器上。
# 本仓已经因为「生成脚本自己造 CRLF」被打回过两个 PR(形态 C⁗)。⇒ 显式钉 LF。
sys.stdout.reconfigure(newline="\n")
entity_root, hasdel_path, sql_only = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
manual_path, prov_stamp = sys.argv[4], sys.argv[5]
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
scanned = len(found)

# ── 手工登记的标量外键对 ──────────────────────────────────────────────────
# ⛔ 被 hasdel 过滤掉的手工对是**硬错**, 不是静默跳过: 登记进来就是断言「这一对
#    该被扫」, 它落不进 SQL 说明登记本身写错了 —— 静默丢掉等于登记了个寂寞。
manual = []
for raw in io.open(manual_path, encoding="utf-8"):
    raw = raw.strip()
    if not raw or raw.startswith("#"):
        continue
    bits = raw.split("|")
    if len(bits) < 4:
        sys.stderr.write(f"BAD_MANUAL_ROW={raw}\n")
        sys.exit(2)
    t, c, tt = bits[0].strip(), bits[1].strip(), bits[2].strip()
    if hasdel and (t not in hasdel or tt not in hasdel):
        sys.stderr.write(f"MANUAL_PAIR_NO_DELETED_AT={t}.{c}->{tt}\n")
        sys.exit(2)
    manual.append((t, c, tt))

added = [p for p in manual if p not in found]
found.update(manual)

def clause(t, c, tt):
    return (f"SELECT '{t}.{c} -> {tt}' AS assoc, count(*) AS bad FROM public.{t} x "
            f"WHERE x.deleted_at IS NULL AND x.{c} IS NOT NULL AND NOT EXISTS "
            f"(SELECT 1 FROM public.{tt} y WHERE y.id::text = x.{c}::text AND y.deleted_at IS NULL)")

parts = [clause(t, c, tt) for t, c, tt in sorted(found)]
print(prov_stamp)
print(f"-- 关联 {len(found)} 对 = 注解扫描 {scanned} + 手工登记新增 {len(added)}")
print("-- ⛔ 这个文件是生成物。改它请改 scripts/check-soft-delete-orphans.sh 后跑 --write。")
print("SELECT * FROM (\n  " + "\n  UNION ALL ".join(parts) + "\n) q WHERE bad > 0 ORDER BY bad DESC;")
sys.stderr.write(f"SCANNED={scanned} MANUAL={len(manual)} MANUAL_ADDED={len(added)} TOTAL={len(found)}\n")
PYEOF
PY_RC=$?
[ "$PY_RC" -eq 0 ] || {
  echo "⛔ 扫描实体失败 (rc=$PY_RC)"
  echo "   rc=2 且上面打了 MANUAL_PAIR_NO_DELETED_AT / BAD_MANUAL_ROW ⇒ 是 MANUAL_PAIRS 登记错了。"
  exit 2
}

ASSOC_COUNT=$(grep -c "UNION ALL" "$WORK/scan.sql" 2>/dev/null || echo 0)
ASSOC_COUNT=$((ASSOC_COUNT + 1))
if [ ! -s "$WORK/scan.sql" ]; then
  echo "⛔ 一条关联都没扫到 —— 仪器坏了, 本次读数作废。"
  echo "   (正常应有数十条。实体目录结构变了? 注解写法变了?)"
  exit 2
fi

if [ "$WRITE_MODE" -eq 1 ]; then
  mkdir -p "$(dirname "$REPO_SQL")"
  cp "$WORK/scan.sql" "$REPO_SQL"
  echo "✓ 已写回 $REPO_SQL ($ASSOC_COUNT 条关联)"
  echo "  下一步: 提 PR 合进 main, 再由 scripts/deploy/install-server-scripts.sh 装到服务器。"
  exit 0
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
