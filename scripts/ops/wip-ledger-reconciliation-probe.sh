#!/usr/bin/env bash
#
# 半成品流水账对账探针 —— 三态退出码 (BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906)
#
# 判据 (与 ReportReversalServiceImpl.replayMovingAverage /
#       SemiFinishedLedgerReconciliationContractTest 逐字同口径):
#
#   produced  = ΣIN + Σ(REVERSE where qty < 0)
#   consumed  = Σ|OUT| − Σ(REVERSE where qty > 0)
#   available = produced − consumed + adjustment
#
# 🔴 退出码是三态, ⛔ 不是两态 (.claude/rules/measurement-and-wiring.md 硬约束 4):
#
#   rc=0  全部对得上           —— 有读数, 且读数说没问题
#   rc=1  有行对不上           —— 有读数, 且读数指向缺陷 (逐行列出)
#   rc=2  这次没量到           —— 连不上库 / 查询失败 / 量到 0 行库存
#
# ⚠️ rc=2 与 rc=0 必须分得开: 「一行都没查到」和「每一行都对」在任何只看
#    「有没有报错行」的判据里长得一模一样, 而它们的含义相反。
#    本脚本的 rc=2 触发条件之一就是 measured=0 —— 一个**设计上必然 > 0** 的读数
#    (生产上任何时刻都有半成品库存行), 它归零只可能是仪器坏了。
#    这条对照来自硬约束 9: 验证「没有问题」时必须同时有一个「一定会发生」的读数。
#
# ============================================================================
# 已登记的存量不平行 (2026-09-06 实测, ⛔ 本 PR 有意不修 —— 硬约束 8 的「登记不改」)
# ----------------------------------------------------------------------------
# 首次跑生产会读到 rc=1 / DISAGREE=4, 全部在 F006 (owner 已定性为可丢弃的测试数据):
#
#   F006 331  NO_LEDGER      账面 produced 2.00, 流水 0 行 —— 记账补齐之前入的库
#   F006 342  PRODUCED_OFF   账面 240.00 而 ΣIN=0 (4 条 OUT 各 -60, 领用侧自洽)
#   F006 343  PRODUCED_OFF   账面 300.00 而 ΣIN=0; 三条 'manual-cleanup:' 带外 REVERSE
#   F006 344  PRODUCED_OFF   账面  10.00 而 ΣIN=0; 一条 interim-settle-reverse-in: REVERSE
#
# 这四条撞的是 replayMovingAverage 的 WIP_LEDGER_INCOMPLETE (produced > ΣIN), 与本 PR 修的
# 「分类器不看符号」是**两个**缺陷 —— 修法也不同 (那边要走「半成品盘点」补 ADJUST 流水)。
# ⚠️ LIUSHANMEN 15 行全部 OK, 所以真实客户侧此刻是干净的。
# ⇒ 接告警时先把这 4 条作为已知基线, 否则第一天就是误报, 而误报会让人把闸关掉 (形态 E)。
#
# 用法 (只读, 不写任何东西):
#   ./scripts/ops/wip-ledger-reconciliation-probe.sh
#   PGDATABASE=cretas_prod_db ./scripts/ops/wip-ledger-reconciliation-probe.sh
#   PROBE_SSH=root@47.100.235.168 ./scripts/ops/wip-ledger-reconciliation-probe.sh   # 从本机远程跑
#
# ⛔ 本脚本**没有**接任何定时任务 —— 接法见文件末尾「怎么接 cron」,
#    接之前先按硬约束 4 的要求把 rc=2 那一态**真的构造一次**。
#
set -uo pipefail

PGDATABASE="${PGDATABASE:-cretas_prod_db}"
PGUSER="${PGUSER:-postgres}"
PROBE_SSH="${PROBE_SSH:-}"
OUT="${PROBE_OUT:-/tmp/wip-ledger-recon-$(date +%Y%m%d-%H%M%S).txt}"

SQL=$(cat <<'PROBE_SQL'
WITH led AS (
  SELECT semi_finished_id,
         SUM(CASE WHEN txn_type = 'IN' THEN quantity ELSE 0 END)                          AS sum_in,
         SUM(CASE WHEN txn_type = 'REVERSE' AND quantity < 0 THEN quantity ELSE 0 END)    AS rev_neg,
         SUM(CASE WHEN txn_type = 'OUT' THEN abs(quantity) ELSE 0 END)                    AS sum_out_abs,
         SUM(CASE WHEN txn_type = 'REVERSE' AND quantity > 0 THEN quantity ELSE 0 END)    AS rev_pos,
         COUNT(*)                                                                         AS ledger_rows
  FROM semi_finished_inventory_transactions
  GROUP BY semi_finished_id
)
SELECT s.factory_id,
       s.id,
       s.intermediate_batch_no,
       CASE
         WHEN l.semi_finished_id IS NULL THEN 'NO_LEDGER'
         WHEN s.produced_quantity <> COALESCE(l.sum_in, 0) + COALESCE(l.rev_neg, 0) THEN 'PRODUCED_OFF'
         WHEN s.consumed_quantity <> COALESCE(l.sum_out_abs, 0) - COALESCE(l.rev_pos, 0) THEN 'CONSUMED_OFF'
         WHEN s.available_quantity <> s.produced_quantity - s.consumed_quantity
                                      + COALESCE(s.adjustment_quantity, 0) THEN 'AVAILABLE_OFF'
         ELSE 'OK'
       END AS verdict,
       s.produced_quantity, s.consumed_quantity, s.available_quantity,
       COALESCE(l.sum_in, 0), COALESCE(l.rev_neg, 0),
       COALESCE(l.sum_out_abs, 0), COALESCE(l.rev_pos, 0)
FROM semi_finished_inventory s
LEFT JOIN led l ON l.semi_finished_id = s.id
ORDER BY s.factory_id, s.id;
PROBE_SQL
)

# ⚠️ psql 少了 -v ON_ERROR_STOP=1 会在语句报错时**照样 exit 0** —— 那样一次查询失败
#    会被读成 measured=0, 落到 reason=zero_rows_measured 这个**错的**理由上
#    (2026-09-06 在一次性容器上实测到过, 当场补的)。两条调用都必须带上。
run_sql() {
    if [ -n "$PROBE_SSH" ]; then
        printf '%s\n' "$SQL" | ssh -o ConnectTimeout=20 "$PROBE_SSH" \
            "cat > /tmp/wip-recon-probe.sql && sudo -u $PGUSER psql -d $PGDATABASE -v ON_ERROR_STOP=1 -At -F '|' -f /tmp/wip-recon-probe.sql"
    else
        printf '%s\n' "$SQL" | psql -d "$PGDATABASE" -U "$PGUSER" -v ON_ERROR_STOP=1 -At -F '|' -f -
    fi
}

# ⚠️ 不用管道接 head/tail —— 管道会替换退出码 (硬约束 1)。全量落盘再判。
run_sql > "$OUT" 2>"$OUT.err"
QUERY_RC=$?

if [ "$QUERY_RC" -ne 0 ]; then
    echo "WIP_RECON_STATUS=INSTRUMENT_DEAD reason=query_failed rc=$QUERY_RC"
    echo "---- stderr ----"
    cat "$OUT.err"
    exit 2
fi

MEASURED=$(grep -c '|' "$OUT")
echo "WIP_RECON_MEASURED=$MEASURED   (设计上必然 > 0: 生产库任何时刻都有半成品库存行)"

if [ "$MEASURED" -eq 0 ]; then
    # 🔴 这一支存在的全部理由: 把「没量到」从「没问题」里拆出来。
    echo "WIP_RECON_STATUS=INSTRUMENT_DEAD reason=zero_rows_measured"
    exit 2
fi

# 逐类计数 —— 结论标签必须由实际值算出来, ⛔ 不无条件打印「(空=没问题)」(形态 A⁗ 第 6 例)。
OK_N=$(awk -F'|' '$4 == "OK"' "$OUT" | wc -l)
BAD_N=$(awk -F'|' '$4 != "OK"' "$OUT" | wc -l)
echo "WIP_RECON_OK=$OK_N  WIP_RECON_DISAGREE=$BAD_N  (OK + DISAGREE 必须等于 MEASURED)"

if [ $((OK_N + BAD_N)) -ne "$MEASURED" ]; then
    echo "WIP_RECON_STATUS=INSTRUMENT_DEAD reason=verdict_partition_does_not_sum"
    exit 2
fi

if [ "$BAD_N" -gt 0 ]; then
    echo "WIP_RECON_STATUS=DISAGREE"
    echo "factory|sfi_id|batch_no|verdict|produced|consumed|available|ΣIN|Σ(REV<0)|Σ|OUT||Σ(REV>0)"
    awk -F'|' '$4 != "OK"' "$OUT"
    echo "报告全文: $OUT"
    exit 1
fi

echo "WIP_RECON_STATUS=ALL_RECONCILE"
echo "报告全文: $OUT"
exit 0

# ============================================================================
# 怎么接 cron (⛔ 本 PR 没有接, 这是给运维的说明)
# ----------------------------------------------------------------------------
# 1. 先把 rc=2 那一态**真的构造一次**, 否则不知道它到不到得了 (硬约束 4):
#      PGDATABASE=no_such_db ./scripts/ops/wip-ledger-reconciliation-probe.sh; echo $?
#      # 期望: WIP_RECON_STATUS=INSTRUMENT_DEAD reason=query_failed, rc=2
#      # 再构造 measured=0: 指向一个建了表但没数据的库, 期望 reason=zero_rows_measured
# 2. 在 47.100.235.168 上放一条 systemd timer (与 scripts/systemd/ 下现有的同风格),
#    ExecStart 指向本脚本, 然后按三态分流告警:
#      rc=0 -> 不喊
#      rc=1 -> "WIP LEDGER DRIFT"  (有行对不上, 附 $OUT)
#      rc=2 -> "WIP PROBE DEAD"    (本次读数作废 —— 措辞必须与 rc=1 不同)
#    ⛔ 不要写成 `if [ $rc -ne 0 ]` 一把抓: 那会把「没量到」和「有问题」混成一种,
#       而本仓已有先例 (52 断言的回归电池随租户收敛一起死了 4 天, 天天报同一句)。
# 3. ⛔ 不要枚举失败码 (`rc==1 或 rc==2` 之外的值一律当「没量到」):
#    退出码是开放集合, 任何第四种值都不该静默落进「绿」(2026-08-27 nightly 实测)。
