package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;

import java.math.BigDecimal;

/**
 * 半成品流水行的构造器（纯函数，不碰仓储、不依赖调用顺序）。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * <p>2026-08-17 F006 生产端到端走查实测：整条链走通后
 * {@code semi_finished_inventory_transactions} 里<b>只有 1 行 IN，一行 OUT 都没有</b>，
 * 而那一轮发生过<b>两次领用</b>。余额确实扣了，但流水查不到是谁领走的
 * ⇒ 半成品<b>只有进账没有出账</b>，对不了账、追不到去向。
 *
 * <p>这不是设计遗漏：{@link SemiFinishedInventoryTransaction.TxnType#OUT} 的语义
 * （「下道领用 / 二次加工，OUT: 负数量」）和
 * {@link SemiFinishedInventoryTransaction.SourceType#SECONDARY_CONSUME}
 * 早就写在实体里了，<b>只是没有任何地方写过 OUT 行</b>——机制在、契约在、没接上。
 *
 * <p>做成<b>纯函数</b>是有意的：余额快照由「扣减前状态 + 本次领用量」算出，
 * ⛔ 不读扣减后的实体字段。这样它不依赖「先改余额还是先记流水」的调用顺序，
 * 也就能被单独断言，不需要把整个 service 搭起来。
 */
public final class WipLedgerEntries {

    /** 专用于已审批报工数量更正；不能与 STOCKTAKE 或普通 REVERSAL 混用。 */
    public static final String REPORT_CORRECTION_SOURCE_TYPE = "PRODUCTION_REPORT_CORRECTION";
    /** 复用可正可负的 ADJUST 数量语义，来源由上面的专用 sourceType 明确区分。 */
    public static final String REPORT_CORRECTION_TXN_TYPE = SemiFinishedInventoryTransaction.TxnType.ADJUST;
    public static final String REPORT_CORRECTION_REF_PREFIX = "report-correction:";

    private WipLedgerEntries() {
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 构造一条「下道领用」的 OUT 流水行。
     *
     * @param sfi        被领用的半成品库存行（<b>扣减前</b>的状态）
     * @param input      本次领用量（正数）；{@code null} 或 {@code <= 0} 返回 {@code null}
     * @param report     触发领用的那条报工
     * @param task       领用方工序任务（下道）
     * @param operatorId 操作人
     * @return OUT 流水行；无需记账时返回 {@code null}
     */
    public static SemiFinishedInventoryTransaction consumptionRow(
            SemiFinishedInventory sfi, BigDecimal input,
            ProductionReport report, WorkProcessTask task, Long operatorId) {

        if (sfi == null || report == null || input == null
                || input.compareTo(BigDecimal.ZERO) <= 0) {
            return null;   // ⛔ 领 0 不凭空造流水
        }

        // 余额快照：由扣减前状态推出，与 consumeSourceWip 同口径。
        BigDecimal balanceAfter = nz(sfi.getProducedQuantity())
                .subtract(nz(sfi.getConsumedQuantity()).add(input))
                .add(nz(sfi.getAdjustmentQuantity()));

        // sourceRef 按实体 javadoc：「OUT → 下道 intermediateBatchNo 或 transferId」。
        // 这里领用方是工序任务，用 task id 定位得到「被谁领走」。
        String sourceRef = task == null || task.getId() == null
                ? sfi.getIntermediateBatchNo()
                : "TASK-" + task.getId();

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(report.getFactoryId())
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef(sourceRef)
                .quantity(input.negate())          // 按 javadoc：OUT 为负数量
                .unitCostAtTxn(sfi.getUnitCost())  // 诚实 null 传播：没有均价就不编一个
                .balanceAfter(balanceAfter)
                .balanceCostAfter(sfi.getUnitCost())
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 构造一条「本道产出入库」的 IN 流水行。
     *
     * <p><b>为什么补这一条</b>：2026-08-18 查 F006 生产库实测，
     * {@code semi_finished_inventory_transactions} 全表 8 行里
     * <b>IN 一行都没有</b>（4 行 OUT + 4 行 REVERSE），
     * 而 {@code semi_finished_inventory} 的 4 行各自挂着 2.00 / 240.00 / 300.00 / 10.00 的
     * {@code produced_quantity}。⇒ 余额<b>凭空出现</b>，流水账解释不了它是怎么来的。
     *
     * <p>最刺眼的是 id=344 那行：{@code produced=10.00}，流水里有一条 {@code REVERSE -10}，
     * 而 {@code IN} 是 <b>0 行</b> —— <b>冲销了一笔从来没记过账的入库</b>。
     *
     * <p>后果不止对不上账：{@code ReportReversalServiceImpl.replayMovingAverage} 把
     * 「ΣIN + Σ(负量 REVERSE)」当成净产出<b>回写</b>库存行，一旦净额 ≤ 0 就把
     * produced / available 一并清零并置 DEPLETED。流水账缺 IN 行时，
     * 这等于<b>拿「我没有证据」当「余额是 0」</b>，把真实库存抹掉。
     *
     * <p>⚠️ <b>余额口径与 {@link #consumptionRow} 相反</b>，与 {@link #reversalRow} 相同：
     * 本方法<b>直接读改完的</b> {@code availableQuantity}，要求调用方
     * <b>先把库存行累加完再调</b>。两种口径各自都对，混起来必错
     * （2026-08-17 就是把构造点放错边，生产上落过一条 {@code balance_after = -2.000000}）。
     *
     * @param sfi        <b>已经累加完</b>的半成品库存行
     * @param inQty      本次产出入库量（正数）；{@code null} 或 {@code <= 0} 返回 {@code null}
     * @param inUnitCost 本次产出的单位成本；🔴 诚实 null 传播 —— 算不出就传 {@code null}，⛔ 不要传 0
     * @param report     产生这笔入库的那条报工
     * @param sourceRef  定位串，按实体 javadoc：IN → {@code intermediateBatchNo}
     * @return IN 流水行；无需记账时返回 {@code null}
     */
    public static SemiFinishedInventoryTransaction productionRow(
            SemiFinishedInventory sfi, BigDecimal inQty, BigDecimal inUnitCost,
            ProductionReport report, String sourceRef, Long operatorId) {

        if (sfi == null || report == null || inQty == null
                || inQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;   // ⛔ 产出 0 不凭空造流水
        }

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(report.getFactoryId())
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .sourceRef(sourceRef)
                .quantity(inQty)                    // 按 javadoc：IN 为正数量
                .unitCostAtTxn(inUnitCost)          // 诚实 null 传播：没有成本就不编一个
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 逐道录入 / 小结 (clerk) 侧的 sourceRef 前缀。
     *
     * <p>🔴 <b>前缀不是装饰, 是安全边界</b>：整单撤回的 G1 守卫
     * {@code SemiFinishedInventoryTransactionRepository.existsDownstreamConsumed} 是按
     * <b>{@code sourceRef = String.valueOf(batchId)} 精确相等</b> 来判「这批 WIP 被下道领用了没有」的。
     * clerk 侧的 OUT 行如果把 sourceRef 写成一个裸批次号, 就会<b>凭空触发</b>那道守卫,
     * 把本来可以撤回的批次一律拦成 409 DOWNSTREAM_CONSUMED。
     * 带上前缀 ⇒ 与那个精确相等永不相撞。⛔ 改这两个常量前先读 {@code existsDownstreamConsumed}。
     */
    public static final String CLERK_IN_REF_PREFIX  = "interim-settle-in:";
    /** 见 {@link #CLERK_IN_REF_PREFIX}。 */
    /**
     * ⚠️ 命名债 (不改名): 这个前缀同时覆盖<b>两条</b>写入口 —— 小结的 SFI 投料扣减 和 正式报工的回料严格扣减, 都经
     * {@code consumeClerkSemiStrict} → {@link #clerkConsumptionRow}。整单撤回的「外部领用血缘」白名单按它识别
     * (ReportReversalServiceImpl.isExternalConsumptionOut), 改名要两边一起改。
     */
    public static final String CLERK_OUT_REF_PREFIX = "interim-settle-out:";

    /**
     * 构造一条<b>逐道录入产出入库</b>的 IN 流水行（clerk 路径，<b>没有报工</b>）。
     *
     * <p><b>为什么与 {@link #productionRow} 分开</b>：那一条要求 {@code report} 非空
     * （它从报工取 {@code factoryId} 和 {@code reportId}）。逐道录入
     * （{@code WipInventoryService.postClerkOutput}）压根不产生 {@link ProductionReport} ——
     * 硬塞一个假 report 只会在流水上留一个指向不存在报工的 {@code report_id}。
     * 这里 {@code reportId} 诚实留空。
     *
     * <p>⚠️ <b>余额口径</b>：与 {@link #productionRow} / {@link #reversalRow} 相同，
     * <b>直接读改完的</b> {@code availableQuantity}，要求调用方<b>先把库存行累加完再调</b>。
     * 与同族的 {@code reverseClerkOutput} / {@code restoreClerkSemi} 写 REVERSE 行的时机一致。
     *
     * @param factoryId  工厂
     * @param sfi        <b>已经累加完</b>的半成品库存行
     * @param inQty      本次入库量（正数）；{@code null} 或 {@code <= 0} 返回 {@code null}
     * @param inUnitCost 本次入库单位成本；🔴 诚实 null 传播 —— 算不出就传 {@code null}，⛔ 不要传 0
     * @param operatorId 操作人（clerk 路径常常拿不到，可为 null）
     * @return IN 流水行；无需记账时返回 {@code null}
     */
    public static SemiFinishedInventoryTransaction clerkProductionRow(
            String factoryId, SemiFinishedInventory sfi,
            BigDecimal inQty, BigDecimal inUnitCost, Long operatorId) {

        if (factoryId == null || sfi == null || inQty == null
                || inQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;   // ⛔ 产出 0 不凭空造流水
        }

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .sourceRef(CLERK_IN_REF_PREFIX + sfi.getIntermediateBatchNo())
                .quantity(inQty)                    // 按 javadoc：IN 为正数量
                .unitCostAtTxn(inUnitCost)          // 诚实 null 传播
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 构造一条<b>逐道录入投料出库</b>的 OUT 流水行（clerk 路径，<b>没有报工</b>）。
     *
     * <p>🔴 <b>{@code outQty} 必须是【实际扣减量】，不是【请求量】</b>。
     * {@code consumeClerkSemi} 在超扣时会 clamp 到 produced（少扣），
     * {@code consumeClerkSemiStrict} 在盒⇄kg 场景会把 kg 折算成盒数（换单位）——
     * 两处的「请求量」都不等于「真正扣掉多少」。流水记错就等于账本自己在说谎。
     *
     * <p>⚠️ 余额口径同 {@link #clerkProductionRow}：<b>读改完的</b> {@code availableQuantity}。
     * ⚠️ 这一点与 {@link #consumptionRow}（读扣减<b>前</b>状态自己推）<b>相反</b> —— 两种口径各自都对，
     * 混起来必错（2026-08-17 生产上落过一条 {@code balance_after = -2.000000}）。
     *
     * @param factoryId  工厂
     * @param sfi        <b>已经扣减完</b>的半成品库存行
     * @param outQty     <b>实际扣减量</b>（正数，本方法负号化）；{@code null} 或 {@code <= 0} 返回 {@code null}
     * @param operatorId 操作人（可为 null）
     * @return OUT 流水行；无需记账时返回 {@code null}
     */
    public static SemiFinishedInventoryTransaction clerkConsumptionRow(
            String factoryId, SemiFinishedInventory sfi,
            BigDecimal outQty, Long operatorId) {

        if (factoryId == null || sfi == null || outQty == null
                || outQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;   // ⛔ 领 0 / clamp 后实际没扣到 → 不凭空造流水
        }

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef(CLERK_OUT_REF_PREFIX + sfi.getIntermediateBatchNo())
                .quantity(outQty.negate())          // 按 javadoc：OUT 为负数量
                .unitCostAtTxn(sfi.getUnitCost())   // 诚实 null 传播
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
    }

    /**
     * <b>结单半成品领用</b>被整单撤回时，「还回领用」那条 REVERSE 行的 sourceRef 前缀。
     *
     * <p>🔴 与 {@link #CLERK_IN_REF_PREFIX} 同一个理由：整单撤回的 G1 守卫
     * {@code existsDownstreamConsumed} 按 <b>{@code sourceRef = String.valueOf(batchId)} 精确相等</b>
     * 判「这批 WIP 被下道领用了没有」。带前缀 ⇒ 与那个精确相等永不相撞。
     * （该守卫另外还要求 {@code txn_type = 'OUT'}，本行是 REVERSE，本来就撞不上；
     * 前缀是第二层保险，也让人一眼看出这行是谁写的。）
     */
    public static final String SETTLEMENT_RESTORE_REF_PREFIX = "reversal-settle-restore-out:";

    /** Exact source-ref prefix pairing a settlement consumption row with its OUT ledger row. */
    public static final String SETTLEMENT_CONSUMPTION_REF_PREFIX = "settlement-consumption-out:";

    /**
     * Construct the immutable OUT row for a forward settlement SFI consumption.  The settlement
     * and consumption ids are both part of source_ref so reversal preflight can prove a strict
     * one-to-one pair and reject orphan, duplicate, or partial history.
     */
    public static SemiFinishedInventoryTransaction settlementConsumptionRow(
            String factoryId, SemiFinishedInventory sfi, BigDecimal outQty,
            BigDecimal balanceAfter, String settlementId, Long consumptionId, Long operatorId) {
        if (factoryId == null || sfi == null || outQty == null
                || outQty.compareTo(BigDecimal.ZERO) <= 0
                || settlementId == null || consumptionId == null) {
            return null;
        }
        return SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME)
                .sourceRef(SETTLEMENT_CONSUMPTION_REF_PREFIX + settlementId + ":" + consumptionId)
                .quantity(outQty.negate())
                .unitCostAtTxn(sfi.getUnitCost())
                .balanceAfter(balanceAfter)
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 构造一条<b>「还回领用」</b>的 REVERSE 流水行 —— 整单撤回把<b>结单时领走的半成品</b>退还回去。
     *
     * <p><b>为什么补这一条（2026-08-18 取证）</b>：
     * {@code ReportReversalServiceImpl.restoreSemiFinishedConsumption} 直接改库存行并写入
     * 与结单消耗行配对的 REVERSE；正向
     * {@code ProductionPlanServiceImpl.postSemiFinishedConsumption} 则写入对应 OUT，
     * 因此余额变化和账本事实保持可追溯配对。
     *
     * <p>🔴 <b>符号必须是正的</b>。{@code REVERSE} 是<b>两种</b>语义共用的一个 txn_type，
     * 靠符号区分（见 {@code ReverseSignSemanticsContractTest}）：
     * <ul>
     *   <li><b>负量</b> = 退回产出（IN 的逆）→ 冲减 {@code producedQuantity}</li>
     *   <li><b>正量</b> = 还回领用（OUT 的逆）→ 抵扣 {@code consumedOut}，⛔ 与产出无关</li>
     * </ul>
     * 本方法记的是「结单领走的那批货又还回来了」，是 <b>OUT 的逆</b> ⇒ 正量。
     * 写成负量会被 {@code replayMovingAverage} 当成「退回产出」冲掉净产出，
     * 把一次<b>归还</b>记成一次<b>减产</b>（实测：变异成负量后，回放把净产出从 100 写成 70）。
     *
     * <p>🔴 <b>{@code restoredQty} 必须是已证明的实际反冲量</b>。撤回 preflight 会拒绝
     * 无法完整证明的数量，因此这里不以 clamp 掩盖部分历史。
     *
     * <p>⚠️ <b>余额口径</b>：同 {@link #reversalRow} / {@link #clerkProductionRow} ——
     * <b>直接读改完的</b> {@code availableQuantity}，要求调用方<b>先把库存行改完再调</b>。
     *
     * <p>⚠️ <b>与 {@link #reversalRow} 分开的理由</b>：那一条要求 {@code report} 非空
     * （它从报工取 {@code factoryId} 和 {@code reportId}）。结单消耗行
     * （{@link com.cretas.aims.entity.ProductionSettlementConsumption}）<b>不挂在任何一条报工上</b>，
     * 硬塞一个报工只会在流水上留一个撒谎的 {@code report_id}。这里 {@code reportId} 诚实留空，
     * 与 {@link #clerkProductionRow} 同一处理。
     *
     * @param factoryId      工厂
     * @param sfi            <b>已经改完</b>的半成品库存行
     * @param restoredQty    <b>实际反冲掉的消耗量</b>（正数）；{@code null} 或 {@code <= 0} 返回 {@code null}
     *                       —— ⛔ 一笔没还回去就不凭空造流水
     * @param reversalLogId  {@code ReportReversalLog.id}
     * @param settlementId   {@code ProductionSettlement.id}
     * @param consumptionId  {@code ProductionSettlementConsumption.id}
     * @param operatorId     操作人（审批人优先，其次提交人）
     * @return REVERSE 流水行（正量）；无需记账时返回 {@code null}
     */
    public static SemiFinishedInventoryTransaction settlementRestoreRow(
            String factoryId, SemiFinishedInventory sfi, BigDecimal restoredQty,
            Long reversalLogId, String settlementId, Long consumptionId, Long operatorId) {

        if (factoryId == null || sfi == null || restoredQty == null
                || restoredQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;   // ⛔ 一笔没还回去 → 不凭空造流水
        }

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef(SETTLEMENT_RESTORE_REF_PREFIX + reversalLogId + ":"
                        + settlementId + ":" + consumptionId)
                .quantity(restoredQty)              // 🔴 正量 = 还回领用（OUT 的逆）
                .unitCostAtTxn(sfi.getUnitCost())   // 诚实 null 传播
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 构造一条<b>冲销</b>流水行（驳回 / 修正一条报工时用）。
     *
     * <p>⚠️ 与 {@link #consumptionRow} 的余额口径<b>相反</b>：那一条由「扣减前」状态推出余额；
     * 这一条要求调用方<b>先把库存行改完再调</b>，直接读改完的 {@code availableQuantity}。
     * 两种口径各自都对，混起来必错 —— 2026-08-17 就是把构造点放错边，
     * 算出过 {@code balanceAfter = -2.000000}。所以这里<b>不自己推</b>，只读结果。
     *
     * @param sfi        <b>已经改完</b>的半成品库存行
     * @param signedQty  带符号的冲销量：退回产出为负、还回领用为正
     * @param report     被冲销的那条报工
     * @param sourceRef  定位串，如 {@code "REVERSE-REPORT-23814"}
     * @return 冲销流水行；{@code signedQty} 为 0 时返回 {@code null}（⛔ 不凭空造流水）
     */
    public static SemiFinishedInventoryTransaction reversalRow(
            SemiFinishedInventory sfi, BigDecimal signedQty,
            ProductionReport report, String sourceRef, Long operatorId) {

        if (sfi == null || report == null || signedQty == null
                || signedQty.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return SemiFinishedInventoryTransaction.builder()
                .factoryId(report.getFactoryId())
                .semiFinishedId(sfi.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                .sourceRef(sourceRef)
                .quantity(signedQty)
                .unitCostAtTxn(sfi.getUnitCost())   // 诚实 null 传播
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
    }

    /**
     * 构造一条已审批报工产出数量更正流水。
     * 数量是相对原报工的准确 delta，可正可负；成本未知时不伪造成本。
     */
    public static SemiFinishedInventoryTransaction correctionRow(
            SemiFinishedInventory sfi, BigDecimal delta, BigDecimal unitCostAtTxn,
            ProductionReport report, String correctionId, Long operatorId) {
        if (sfi == null || report == null || report.getId() == null
                || delta == null || delta.signum() == 0 || correctionId == null || correctionId.isBlank()) {
            return null;
        }
        return SemiFinishedInventoryTransaction.builder()
                .factoryId(report.getFactoryId())
                .semiFinishedId(sfi.getId())
                .txnType(REPORT_CORRECTION_TXN_TYPE)
                .sourceType(REPORT_CORRECTION_SOURCE_TYPE)
                .sourceRef(REPORT_CORRECTION_REF_PREFIX + correctionId + ":report:" + report.getId())
                .quantity(delta)
                .unitCostAtTxn(unitCostAtTxn)
                .balanceAfter(nz(sfi.getAvailableQuantity()))
                .balanceCostAfter(sfi.getUnitCost())
                .reportId(report.getId())
                .operatorId(operatorId)
                .build();
    }
}
