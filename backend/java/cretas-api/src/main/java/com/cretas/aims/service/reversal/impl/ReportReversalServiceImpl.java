package com.cretas.aims.service.reversal.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.dto.production.WipOutHistoryRepairRequestDTO;
import com.cretas.aims.dto.production.WipOutHistoryRepairResultDTO;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.reversal.ReportReversalService;
import com.cretas.aims.service.reversal.ReversalConsumptionCleanupService;
import com.cretas.aims.service.wip.WipLedgerEntries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SP2 整单撤回实现。
 *
 * <p><b>三层守卫</b> (与后续撤回写入同一事务，失败时不得留下半执行状态):
 * <ol>
 *   <li>Guard G1: 下游领用检查 — SIT 中存在 OUT/SECONDARY_CONSUME 行 → 409 DOWNSTREAM_CONSUMED</li>
 *   <li>Guard G2: 成品出货检查 — FinishedGoodsBatch shippedQuantity > 0 → 409 FG_SHIPPED</li>
 *   <li>Guard G3: 幂等检查 — 已有 DONE 直接返回; PENDING 返回 409 ALREADY_PENDING</li>
 * </ol>
 *
 * <p><b>SP12 快速撤回通道</b> (Fast Path):
 * <p>满足以下全部条件时，跳过审批直接执行撤回 (仍经过 G1/G2/G3 守卫，不绕过安全检查):
 * <ul>
 *   <li>提交人 == 本次操作人 (只有本人能快速撤回自己的报工)</li>
 *   <li>G1 通过 — 无下游领用消耗 (库存安全绝不妥协)</li>
 *   <li>报工提交时间 ≤ {@value #FAST_PATH_WINDOW_MINUTES} 分钟内 (短时间窗口, 防止滥用)</li>
 * </ul>
 * 不满足任一条件 → 退回完整审批流 (PENDING)，同时附上诚实提示 (为何需要审批)。
 *
 * <p><b>executeReversal 原子保证</b>: 单 @Transactional。软删报工 → 写 REVERSE SIT → 回放均价 → 更新 FGB
 * → 置 ReportReversalLog.DONE。任一失败 → 全部回滚 (Spring REQUIRED 默认)。
 *
 * <p><b>fail-soft 禁止</b>: 不使用 try/catch 吞掉内层异常 (历史事故:
 * REQUIRED 内抛异常后父事务标记 rollback-only, fail-soft catch 救不回)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportReversalServiceImpl implements ReportReversalService {

    /** Serializes repair requests so reverted_txn_ids cannot be lost by last-write-wins. */
    @jakarta.persistence.PersistenceContext
    private EntityManager entityManager;

    private final ReportReversalLogRepository reversalLogRepo;
    private final ProductionBatchRepository batchRepo;
    private final ProductionReportRepository reportRepo;
    private final SemiFinishedInventoryRepository wipRepo;
    private final SemiFinishedInventoryTransactionRepository txnRepo;
    private final FinishedGoodsBatchRepository fgbRepo;
    // BUG-4 修复: 批次加载用户姓名 (单次 IN query, 无 N+1)
    private final UserRepository userRepository;
    // BUG-1 联动: 整单撤回需复位 WorkProcessTask (COMPLETED → PENDING), 否则任务卡终态无法重报
    private final WorkProcessTaskRepository workProcessTaskRepository;
    // W6 #27: 驳回撤回申请时通知申请人 (App 内通知)
    private final NotificationService notificationService;

    /**
     * 断点2 修复 (2026-06-11, Fable E2E): 撤回时清 SalesOrderItem.costUnitPrice (置 null),
     * 让重报新成本事件能重新回填 (OrderCostBackfillListener 是 write-once — 仅 null 时写).
     * 否则撤回后 SO 挂着被撤回报工的成本, 重报因字段非 null 被跳过 → 永不刷新, 脏数据不可自愈.
     *
     * <p>field-inject (required=false): 不改 @RequiredArgsConstructor 生成的构造器,
     * 不破坏既有 ReportReversalListNamesTest 的显式构造调用. 未注入 (老 context / 单测未提供)
     * → 跳过清字段 (旧行为, 不抛异常).
     */
    @org.springframework.beans.factory.annotation.Autowired
    private ProductionPlanRepository productionPlanRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesOrderItemRepository salesOrderItemRepository;

    /**
     * R3 修复 (2026-06-14, 六扇门撤回库存恢复): 撤回必须回扣结单消耗的原料量。
     *
     * <p><b>为什么必须回扣</b>: 结单 ({@code ProductionPlanServiceImpl.postMaterialBatchConsumption})
     * 消耗原料时 {@code MaterialBatch.usedQuantity += 领用量}。撤回若不回扣, 原料仍显示已用
     * → 原料库存永久少账 ({@code currentQuantity = receiptQuantity - usedQuantity - reservedQuantity}
     * 是 @Transient 计算值, 回扣 usedQuantity 自动恢复 currentQuantity)。
     *
     * <p><b>消耗权威源</b>: {@link ProductionSettlementConsumption} —— 结单逐行迭代它写
     * usedQuantity, 每行记录精确的 {@code (materialBatchId, quantity, sourceType)}, 1:1 对应。
     * (非 {@code ProductionReport.materialBatchRefs} —— 那是报工时 JSONB 快照, 不是实际结单领用量。)
     *
     * <p><b>事务/异常</b>: 在 {@link #executeReversal} 单一 @Transactional 内执行 (禁 fail-soft,
     * 失败应整体回滚)。repo 未注入 (老 context / 单测未提供) → 跳过 (旧行为, 不抛异常)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MaterialBatchRepository materialBatchRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementRepository productionSettlementRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionSettlementConsumptionRepository productionSettlementConsumptionRepository;

    /**
     * 逐道录入闭环: 正式报工除了写 {@link ProductionReport}，还会保留一条物化后的
     * {@link ProcessSheetRow}。计划取消门禁把有效的物化行视为生产数据，因此整单撤回必须在同一
     * 回滚动作里软删对应批次的行；否则界面虽显示 DONE，计划仍永久报“已有报工/WIP”。
     *
     * <p>required=false 保持旧单测构造方式兼容。生产 Spring context 会正常注入；未注入时维持旧行为。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProcessSheetRowRepository processSheetRowRepository;

    /** 还回正式报工时扣掉的回料 (与 consumeClerkSemiStrict 成对); field-inject 同上, 缺失时 ①.0 loud-fail。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.wip.WipInventoryService wipInventoryService;

    /**
     * 生产仓占用的释放 (释放路径 2/3: <b>冲销</b>)。
     *
     * <p>整单撤回把报工软删了, 它在 {@code material_batches.reserved_quantity} 上占的原料
     * 必须同时放回 —— 否则报工没了、占用还挂着, 而且<b>再也找不到是谁占的</b>
     * (枚举在占报工的查询带 {@code deleted_at IS NULL})。与 ③.6 回扣 usedQuantity 是同一条纪律。
     *
     * <p>field-inject (required=false): 与本类既有几个字段同一惯例, 不破坏显式构造调用。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.production.WorkshopReportReservationService workshopReservationService;

    /** Exact-batch cleanup of unposted consumption during an atomic reversal. */
    @org.springframework.beans.factory.annotation.Autowired
    private ReversalConsumptionCleanupService reversalConsumptionCleanupService;

    /**
     * SP12 快速撤回时间窗口 (分钟)。
     * 在此窗口内, 本人提交 + 无下游消费 → 快速撤回无需审批。
     * 超出此窗口 → 退回完整审批流。
     */
    static final int FAST_PATH_WINDOW_MINUTES = 5;

    // ==================== 提交撤回申请 ====================

    @Override
    @Transactional
    public ReportReversalLog submitReversal(String factoryId, Long batchId, Long submittedBy, String reason) {
        // ---- 守卫与快速撤回同处外层事务，保证内部 self-invocation 仍有事务 ----

        // G1: 下游领用检查
        List<ProductionReport> currentReports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        Set<Long> currentReportIds = reportIds(currentReports);
        String batchRef = String.valueOf(batchId);
        if (txnRepo.existsDownstreamConsumed(factoryId, batchRef)
                || hasExternalDownstreamConsumption(factoryId, batchId, currentReportIds)) {
            throw new BusinessException(409, String.format(
                    "批次 %d 的半成品已被下道工序领用，无法整单撤回", batchId))
                    .withCode("DOWNSTREAM_CONSUMED")
                    .withHint("请先处理下游领用记录后再申请撤回");
        }

        // 加载批次获取 planId (G2 需要)
        ProductionBatch batch = batchRepo.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产批次不存在: " + batchId));

        // G2: 成品出货检查
        if (batch.getProductionPlanId() != null) {
            if (fgbRepo.existsShippedByFactoryIdAndProductionPlanId(factoryId, batch.getProductionPlanId())) {
                throw new BusinessException(409, String.format(
                        "批次 %d 对应计划的成品已出货，无法整单撤回", batchId))
                        .withCode("FG_SHIPPED")
                        .withHint("请确认出货记录后处理退货再申请撤回");
            }
        }

        // G3: 幂等检查
        Optional<ReportReversalLog> existing = reversalLogRepo.findByBatchIdAndReversalScopeAndDeletedAtIsNull(
                batchId, ReportReversalLog.ReversalScope.WHOLE_ORDER);
        if (existing.isPresent()) {
            ReportReversalLog ex = existing.get();
            if (ReportReversalLog.ReversalStatus.DONE == ex.getStatus()) {
                // 🔴 禁止降级 (2026-08-19): 已撤回过 ≠ 这次提交没事干。
                //
                // 撤回成功会把当时的报工全部软删, 所以"真正的幂等重发"必然**看不到活报工**。
                // 反过来: 还看得到活报工, 说明它们是**上次撤回之后新报的** —— 这次提交撤不掉它们
                // (uq_reversal_batch UNIQUE(batch_id, reversal_scope) 让本批次开不出第二张撤回单),
                // 而旧实现直接 return ex, 对外就是一句"撤回完成"。
                //
                // prod 实测代价 (F006 batch 10761, 2026-08-18; 全库普查该形态命中 1 个批次):
                //   07:19:24 撤回完成, 软删报工 23830/23831 ✅ (但 reverted_txn_ids=[], 没冲销库存)
                //   08:07    重报 23832/23833  →  09:03:33 再次提交 → 静默返回旧 DONE
                //            ⚠️ 这一批随后在 09:04:52 被【人工补账】清掉了(见下), 不是本接口做的
                //   13:33    重报 23836/23837  →  23:22:21 再次提交 → 静默返回旧 DONE, 一条没冲
                //   ⇒ 23836/23837 至今 deleted_at IS NULL, 同批次还挂着 75.00 盒半成品。
                //
                // 阴性对照(全库): 要求"撤回【之前】建的活报工"→ 0 批次 0 报工
                //   ⇒ 14 次撤回里该软删的全部正确软删了, 坏的只有"第二次撤回"这一条路。
                //
                // ⚠️ 判据必须是"有没有**活**报工", 不能退化成"有没有 DONE 记录" ——
                //    后者会把正常的幂等重发 (同一请求重复提交) 也一起挡掉。
                List<ProductionReport> liveReports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
                if (!liveReports.isEmpty()) {
                    LocalDateTime revertedAt = ex.getUpdatedAt() != null ? ex.getUpdatedAt() : ex.getCreatedAt();
                    String when = revertedAt != null
                            ? revertedAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            : "更早时间";
                    throw new BusinessException(409, String.format(
                            "批次 %d 已于 %s 撤回过。之后这个批次又新报了 %d 条报工, "
                                    + "它们不在那次撤回的范围里 —— 这次提交不会把它们撤掉",
                            batchId, when, liveReports.size()))
                            .withCode("REVERSAL_SUPERSEDED")
                            // 当前只有 WHOLE_ORDER 一种 scope, 没有"单条撤回"这个功能 ——
                            // ⛔ 不要给一句用户做不到的指引。
                            .withHint(String.format(
                                    "同一个批次目前只支持整单撤回一次。这 %d 条是上次撤回之后新报的, "
                                            + "需要联系管理员处理", liveReports.size()))
                            .withSeverity("BLOCKING")
                            .withHintTarget(String.valueOf(batchId));
                }
                // 兼容修复上线前已经完成的撤回：旧版本只软删 production_reports，没有清理
                // process_sheet_rows。重复点击撤回时只修复这批残留行，不重复冲销库存流水。
                softDeleteProcessSheetRows(factoryId, batchId, batch.getProductionPlanId(), LocalDateTime.now());
                log.info("[SP2] submitReversal idempotent DONE: batchId={} logId={} liveReports=0", batchId, ex.getId());
                return ex;
            }
            if (ReportReversalLog.ReversalStatus.PENDING == ex.getStatus()) {
                throw new BusinessException(409, String.format(
                        "批次 %d 已有待审批的撤回申请 (id=%d)", batchId, ex.getId()))
                        .withCode("ALREADY_PENDING")
                        .withHint("请等待审批结果或联系管理员");
            }
        }

        // 检查是否有报工数据
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        boolean hasReports = !reports.isEmpty();

        // ---- SP12 快速撤回路径判断 ----
        // 条件: 本人提交 + 无下游消费(G1已通过) + 最新报工在时间窗口内
        // 注意: G1/G2 守卫已经在上方通过，快速撤回不绕过任何安全守卫，只免审批人环节。
        String fastPathDeniedReason = null;
        if (hasReports) {
            fastPathDeniedReason = resolveFastPathDeniedReason(submittedBy, reports);
        }

        boolean isFastPath = hasReports && fastPathDeniedReason == null;
        // 无报工 → 真 no-op 直接 DONE | 快速撤回通过 → APPROVED(自动批准,待 executeReversal 执行后置 DONE) | 否则 → PENDING
        // ⚠️ BUG-GOLD-RERUN-FASTPATH-REVERSAL: 快速撤回不能预置 DONE —— executeReversal 对 DONE 幂等跳过
        //   (软删报工/冲销库存/清成本全不执行)。必须存非 DONE 状态让 executeReversal 真正跑完再由它置 DONE。
        ReportReversalLog.ReversalStatus initialStatus;
        if (!hasReports) {
            initialStatus = ReportReversalLog.ReversalStatus.DONE;
        } else if (isFastPath) {
            initialStatus = ReportReversalLog.ReversalStatus.APPROVED;
        } else {
            initialStatus = ReportReversalLog.ReversalStatus.PENDING;
        }

        // 若退回审批, 在 reason 中附上诚实提示 (防呆: 告诉操作员为何需要审批)
        String effectiveReason = reason;
        if (initialStatus == ReportReversalLog.ReversalStatus.PENDING && fastPathDeniedReason != null) {
            effectiveReason = (reason == null || reason.isBlank())
                    ? fastPathDeniedReason
                    : reason + " | 需审批原因: " + fastPathDeniedReason;
        }

        ReportReversalLog log_ = ReportReversalLog.builder()
                .factoryId(factoryId)
                .batchId(batchId)
                .planId(batch.getProductionPlanId())
                .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                .submittedBy(submittedBy)
                .reason(effectiveReason)
                .status(initialStatus)
                .fastPath(isFastPath)
                .build();
        ReportReversalLog saved = reversalLogRepo.save(log_);

        // 快速撤回 → 直通执行 (executeReversal 内部走完软删/冲销/清成本后置 DONE)。
        // 无报工 → 已是 DONE 的 no-op, 无需调 executeReversal (调了也会被 DONE 幂等跳过)。
        if (isFastPath) {
            executeReversal(saved.getId(), factoryId);
        }

        log.info("[SP2] submitReversal batchId={} status={} fastPath={} logId={}",
                batchId, initialStatus, isFastPath, saved.getId());
        return reversalLogRepo.findById(saved.getId()).orElse(saved);
    }

    // ==================== 执行撤回 (单一 @Transactional 原子) ====================

    @Override
    @Transactional
    public void executeReversal(Long logId, String factoryId) {
        ReportReversalLog log_ = findReversalLogForUpdate(logId, factoryId);
        if (!factoryId.equals(log_.getFactoryId())) {
            throw new BusinessException(403, "工厂 ID 不匹配");
        }
        // 幂等: 已 DONE 直接返回
        if (ReportReversalLog.ReversalStatus.DONE == log_.getStatus()) {
            log.warn("[SP2] executeReversal already DONE logId={}, skip", logId);
            return;
        }

        // A plan-scoped reversal must have every settlement dependency wired before
        // any report/WIP mutation. Missing optional beans are a configuration error,
        // not a reason to mark the reversal DONE without restoring the settlement.
        requireSettlementDependencies(log_.getPlanId());

        if (reversalConsumptionCleanupService != null) {
            reversalConsumptionCleanupService.lockReversalScope(factoryId, log_.getBatchId(), log_.getPlanId());
        }

        Long batchId = log_.getBatchId();
        List<Long> revertedTxnIds = new ArrayList<>();
        List<Long> revertedInTxnIds = new ArrayList<>();

        // ① 软删除该批次所有 YIELD 报工
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        Map<Long, List<SemiFinishedInventoryTransaction>> reportTxns = new HashMap<>();
        Set<Long> reportIds = reportIds(reports);
        Set<Long> involvedSfiIds = new HashSet<>();
        Set<Long> inSfiIds = new HashSet<>();
        List<SemiFinishedInventory> batchWips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        for (ProductionReport report : reports) {
            List<SemiFinishedInventoryTransaction> txns =
                    txnRepo.findByFactoryIdAndReportId(factoryId, report.getId());
            reportTxns.put(report.getId(), txns == null ? List.of() : txns);
            for (SemiFinishedInventoryTransaction txn : reportTxns.get(report.getId())) {
                if (txn.getSemiFinishedId() != null) {
                    involvedSfiIds.add(txn.getSemiFinishedId());
                    if (SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())) {
                        inSfiIds.add(txn.getSemiFinishedId());
                    }
                }
            }
        }
        if (batchWips != null) {
            for (SemiFinishedInventory wip : batchWips) {
                if (wip != null && wip.getId() != null) involvedSfiIds.add(wip.getId());
            }
        }
        lockInvolvedWips(factoryId, involvedSfiIds);
        List<ProductionReport> lockedReports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        Set<Long> lockedReportIds = reportIds(lockedReports);
        if (!reportIds.equals(lockedReportIds)) {
            throw new BusinessException(409, "撤回失败: 加锁期间报工集合发生变化，请重试")
                    .withCode("WIP_LEDGER_CHANGED");
        }
        Map<Long, List<SemiFinishedInventoryTransaction>> lockedReportTxns = new HashMap<>();
        Set<Long> lockedSnapshotSfiIds = new HashSet<>();
        Set<Long> lockedSnapshotInSfiIds = new HashSet<>();
        for (ProductionReport report : lockedReports) {
            List<SemiFinishedInventoryTransaction> txns =
                    txnRepo.findByFactoryIdAndReportId(factoryId, report.getId());
            List<SemiFinishedInventoryTransaction> safeTxns = txns == null ? List.of() : txns;
            List<SemiFinishedInventoryTransaction> beforeTxns = reportTxns.getOrDefault(report.getId(), List.of());
            if (!sameTransactionSnapshot(beforeTxns, safeTxns)) {
                throw new BusinessException(409, "撤回失败: 加锁期间半成品流水发生变化，请重试")
                        .withCode("WIP_LEDGER_CHANGED");
            }
            lockedReportTxns.put(report.getId(), safeTxns);
            for (SemiFinishedInventoryTransaction txn : safeTxns) {
                if (txn.getSemiFinishedId() != null) {
                    lockedSnapshotSfiIds.add(txn.getSemiFinishedId());
                    if (SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())) {
                        lockedSnapshotInSfiIds.add(txn.getSemiFinishedId());
                    }
                }
            }
        }
        if (!involvedSfiIds.containsAll(lockedSnapshotSfiIds)) {
            throw new BusinessException(409, "撤回失败: 加锁期间半成品流水发生变化，请重试")
                    .withCode("WIP_LEDGER_CHANGED");
        }
        reports = lockedReports;
        reportTxns = lockedReportTxns;
        reportIds = lockedReportIds;
        inSfiIds = lockedSnapshotInSfiIds;
        validateReportWipTransactions(factoryId, batchId, reports, reportTxns);
        if (hasExternalDownstreamConsumption(factoryId, batchId, reportIds)) {
            throw new BusinessException(409, String.format(
                    "批次 %d 的半成品已被下道工序领用，无法整单撤回", batchId))
                    .withCode("DOWNSTREAM_CONSUMED");
        }
        List<SemiFinishedInventory> lockedWips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        if (lockedWips == null) {
            throw new BusinessException(409, "撤回失败: 无法读取批次半成品库存")
                    .withCode("WIP_LEDGER_INCOMPLETE");
        }
        Set<Long> preMutationWipIds = new HashSet<>(involvedSfiIds);
        for (SemiFinishedInventory wip : lockedWips) {
            if (wip == null || wip.getId() == null) {
                throw malformedWipTransaction("批次半成品库存行为空", "WIP_LEDGER_INVALID");
            }
            preMutationWipIds.add(wip.getId());
        }
        for (Long wipId : preMutationWipIds) {
            SemiFinishedInventory lockedWip = wipRepo.findByIdForUpdate(wipId)
                    .orElseThrow(() -> malformedWipTransaction("半成品库存不存在", "WIP_NOT_FOUND"));
            BigDecimal adjustment = Optional.ofNullable(lockedWip.getAdjustmentQuantity())
                    .orElse(BigDecimal.ZERO);
            if (adjustment.signum() != 0) {
                throw new BusinessException(409, "撤回失败: 半成品存在未归零的盘点调整，不能自动猜测其归属")
                        .withCode("WIP_ADJUSTMENT_PRESENT");
            }
        }
        for (SemiFinishedInventory wip : lockedWips) {
            if (wip == null) continue;
            BigDecimal produced = wip.getProducedQuantity() == null ? BigDecimal.ZERO : wip.getProducedQuantity();
            BigDecimal available = wip.getAvailableQuantity() == null ? BigDecimal.ZERO : wip.getAvailableQuantity();
            if ((produced.signum() > 0 || available.signum() > 0)
                    && !inSfiIds.contains(wip.getId())) {
                BigDecimal strandedQuantity = available.signum() > 0 ? available : produced;
                throw new BusinessException(409, String.format(
                        "撤回失败: 半成品 %s 还有 %s 挂在账上，但缺少本次报工对应的入库流水",
                        wip.getIntermediateBatchNo(),
                        strandedQuantity.stripTrailingZeros().toPlainString()))
                        .withCode("WIP_NOT_LEDGERED")
                        .withHint("请先用「半成品盘点」把该批次余额校正到实际值，再重新提交撤回")
                        .withSeverity("BLOCKING")
                        .withHintTarget(String.valueOf(batchId));
            }
        }
        // Idempotency is a pre-mutation gate.  Validate the complete immutable original-to-REVERSE
        // multiset before releasing reservations, soft-deleting reports, or resetting tasks.
        Map<Long, Set<Long>> compensatedTxnIdsByReport = new HashMap<>();
        for (ProductionReport report : reports) {
            List<SemiFinishedInventoryTransaction> reportLedger =
                    reportTxns.getOrDefault(report.getId(), List.of());
            List<SemiFinishedInventoryTransaction> reversals = reportLedger.stream()
                    .filter(existing -> SemiFinishedInventoryTransaction.TxnType.REVERSE
                            .equals(existing.getTxnType()))
                    .filter(existing -> SemiFinishedInventoryTransaction.SourceType.REVERSAL
                            .equals(existing.getSourceType()))
                    .toList();
            List<SemiFinishedInventoryTransaction> originals = reportLedger.stream()
                    .filter(txn -> SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())
                            || SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType()))
                    .toList();
            compensatedTxnIdsByReport.put(report.getId(),
                    matchExistingCompensations(originals, reversals));
        }
        validateFullWipLedgersBeforeMutation(factoryId, involvedSfiIds, reportTxns);
        // Settlement SEMI lines are another SFI mutation path. Lock and scope-check them
        // before releasing reservations, soft-deleting reports, or resetting tasks.
        Set<Long> settlementSemiWipIds = preflightSettlementSemiFinished(
                factoryId, log_.getPlanId(), involvedSfiIds);
        SettlementReversalScope settlementScope = validateSettlementReversalScopeIfPresent(
                factoryId, batchId, log_.getPlanId());
        LocalDateTime now = LocalDateTime.now();
        // 🔴 释放路径 2/3 (冲销): 先把这些报工在生产仓占的原料放回去, 再软删。
        //    ⚠️ 顺序不可反 —— findYieldReportsByBatch 带 deletedAt IS NULL, 软删之后
        //    就再也枚举不到它们了; 那时 reserved 会永久挂着, 而追溯源已经没了。
        //    ⛔ 释放量不在这里算, 走与驳回/结单同一个出口。
        if (workshopReservationService != null) {
            for (ProductionReport r : reports) {
                workshopReservationService.releaseForReport(factoryId, r);
            }
        }
        for (ProductionReport r : reports) {
            r.setDeletedAt(now);
            reportRepo.save(r);
        }

        // ①.0 (2026-09-02 票) 还回正式报工时严格扣掉的回料: 那些 OUT 流水没有 reportId (consumeClerkSemiStrict
        //   不认识报工), ② 沿 report_id 的循环看不见它们; 真相在被软删的行的 row_payload 里
        //   (upstreamSources.stockConsumedAtSubmit=true)。必须在软删行之前读。
        restoreReworkConsumedAtSubmit(factoryId, batchId, log_, revertedTxnIds);

        // ①.1 软删除逐道录入物化行。取消计划的兜底门禁会读取这些行，必须与 YIELD 报工同进退。
        softDeleteProcessSheetRows(factoryId, batchId, log_.getPlanId(), now);

        // Remove stale unposted raw-consumption rows owned by this reversed batch.
        // Posted rows and quantities are never changed.
        if (reversalConsumptionCleanupService != null) {
            reversalConsumptionCleanupService.softDeleteUnpostedForReversedBatch(
                    factoryId, batchId, log_.getPlanId(), now);
        }

        // ①.5 复位该批次工序任务 (BUG-1 联动修复的撤回路径):
        // OUTPUT 报工提交现在会把 WorkProcessTask 置 COMPLETED(终态) — 整单撤回软删了全部
        // YIELD 报工后, 若任务留在 COMPLETED, RN 任务列表不再显示该道 → 无法重报, 撤回流程被打断。
        // 故: COMPLETED → PENDING + 清 completedBy/completedAt; 所有任务 actualQuantity 复位 null
        // (其派生源 YIELD 报工已全部软删)。SKIPPED/CANCELLED 是独立决策, 不动。
        List<WorkProcessTask> batchTasks = workProcessTaskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batchId);
        for (WorkProcessTask t : batchTasks) {
            if (WorkProcessTask.Status.COMPLETED == t.getStatus()) {
                t.setStatus(WorkProcessTask.Status.PENDING);
                t.setCompletedBy(null);
                t.setCompletedAt(null);
            }
            t.setActualQuantity(null);
        }
        if (!batchTasks.isEmpty()) {
            workProcessTaskRepository.saveAll(batchTasks);
        }

        // ② 每份报工: IN 写负 REVERSE；本整单内的正式 WIP OUT 写正 REVERSE。
        //    OUT 是本批次下游工序的领用，不是新的产出；正量 REVERSE 只恢复 consumed。
        for (ProductionReport r : reports) {
            List<SemiFinishedInventoryTransaction> inTxns = reportTxns.getOrDefault(r.getId(), List.of());
            if (inTxns == null) {
                continue;
            }
            List<SemiFinishedInventoryTransaction> reversibleTxns = inTxns.stream()
                    .filter(txn -> SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())
                            || SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType()))
                    .sorted(Comparator.comparing(
                            txn -> txn.getQuantity().abs(), Comparator.reverseOrder()))
                    .toList();
            Set<Long> compensatedTxnIds = compensatedTxnIdsByReport
                    .getOrDefault(r.getId(), Set.of());
            for (SemiFinishedInventoryTransaction inTxn : reversibleTxns) {
                if (!SemiFinishedInventoryTransaction.TxnType.IN.equals(inTxn.getTxnType())
                        && !SemiFinishedInventoryTransaction.TxnType.OUT.equals(inTxn.getTxnType())) {
                    continue;
                }
                BigDecimal quantity = inTxn.getQuantity();
                if (quantity == null || quantity.signum() == 0) {
                    continue;
                }
                BigDecimal reverseQuantity = SemiFinishedInventoryTransaction.TxnType.IN.equals(inTxn.getTxnType())
                        ? quantity.negate() : quantity.abs();
                if (compensatedTxnIds.contains(inTxn.getId())) {
                    continue;
                }
                // IN 的逆是负量；OUT 的逆是正量。原流水永远不改。
                SemiFinishedInventoryTransaction reverseTxn = SemiFinishedInventoryTransaction.builder()
                        .factoryId(factoryId)
                        .semiFinishedId(inTxn.getSemiFinishedId())
                        .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                        .sourceRef("reversal-log:" + logId)
                        .quantity(reverseQuantity)
                        .unitCostAtTxn(inTxn.getUnitCostAtTxn())
                        .reportId(r.getId())
                        .operatorId(reversalOperatorId(log_))
                        .build();
                SemiFinishedInventoryTransaction saved = txnRepo.save(reverseTxn);
                revertedTxnIds.add(saved.getId());
                if (SemiFinishedInventoryTransaction.TxnType.IN.equals(inTxn.getTxnType())) {
                    revertedInTxnIds.add(saved.getId());
                }

                // 回放移动均价到 SemiFinishedInventory
                replayMovingAverage(inTxn.getSemiFinishedId(), factoryId, now);
            }
        }

        // No reversal is complete until every locked WIP row has a coherent final ledger state.
        // This is deliberately after all replay writes and before the reversal log becomes DONE.
        Set<Long> finalWipIds = new HashSet<>(involvedSfiIds);
        for (SemiFinishedInventory wip : lockedWips) {
            if (wip == null || wip.getId() == null) {
                throw malformedWipTransaction("批次半成品库存行为空", "WIP_LEDGER_INVALID");
            }
            finalWipIds.add(wip.getId());
        }
        for (Long finalWipId : finalWipIds) {
            SemiFinishedInventory finalWip = wipRepo.findByIdForUpdate(finalWipId)
                    .orElseThrow(() -> malformedWipTransaction("回放后半成品库存不存在", "WIP_NOT_FOUND"));
            validateFinalWipState(finalWip, true);
        }

        // ②.5 🔴 禁止降级 (2026-08-18): 一行都没冲销, 就不许对外说撤回完成。
        //
        // 上面 ② 是【按报工找 IN 流水】来冲销的 —— 找不到 IN 行就一行不写、悄悄往下走,
        // 最后照样置 DONE。实测代价: F006 生产库 14 条 reversal_log <b>全部</b> status=DONE 且
        // reverted_txn_ids 长度为 0 (jsonb_array_length 逐条查过), 也就是说
        // <b>这个功能上线至今 (2026-06-10 起) 一次都没有真正冲销过库存</b>,
        // 而每一次都告诉用户"撤回完成"。报工被软删了(追溯源没了), 它产出的半成品还挂在账上,
        // 且流水账里没有任何一行解释那批货是怎么来的 —— 这就是 phantom WIP。
        //
        // 判据只看【这次撤回该负责的那批 WIP】: 按 batchId 取库存行 (报工产出的行都带 batchId;
        // 小结/逐道录入的 CLK-SEMI 行 batchId 为空, 不归整单撤回管, 不会被误伤)。
        // ⇒ 修好 IN 侧记账之后, 正常报工必有 IN 行 ⇒ 本闸只在补全之前的存量数据上响, 窄且可信。
        if (revertedInTxnIds.isEmpty() && !reports.isEmpty()) {
            BigDecimal stranded = BigDecimal.ZERO;
            for (SemiFinishedInventory w : wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId)) {
                BigDecimal avail = w.getAvailableQuantity();
                BigDecimal produced = w.getProducedQuantity();
                BigDecimal strandedForWip = avail == null ? BigDecimal.ZERO : avail.max(BigDecimal.ZERO);
                if (produced != null && produced.compareTo(BigDecimal.ZERO) > 0
                        && !inSfiIds.contains(w.getId())) {
                    strandedForWip = strandedForWip.max(produced);
                }
                if (strandedForWip.compareTo(BigDecimal.ZERO) > 0) {
                    stranded = stranded.add(strandedForWip);
                }
            }
            if (stranded.compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessException(409, String.format(
                        "撤回失败: 批次 %d 还有 %s 半成品挂在账上, 而流水账里找不到对应的入库(IN)行, "
                                + "撤回没法把它退回去 —— 若就这样标记完成, 报工没了而货还在, 账就对不上了",
                        batchId, stranded.stripTrailingZeros().toPlainString()))
                        .withCode("WIP_NOT_LEDGERED")
                        .withHint("这批半成品是流水账补全之前入的库。请先用「半成品盘点」把该批次余额"
                                + "校正到实际值 (盘点会补一条 ADJUST 流水), 再重新提交撤回")
                        .withSeverity("BLOCKING")
                        .withHintTarget(String.valueOf(batchId));
            }
        }

        // ③ 将对应 FinishedGoodsBatch 标记 REVERSED (如有)
        if (log_.getPlanId() != null) {
            List<FinishedGoodsBatch> fgbs =
                    fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, log_.getPlanId());
            for (FinishedGoodsBatch fgb : fgbs) {
                fgb.setStatus(FinishedGoodsBatch.Status.REVERSED);
                fgb.setReversalLogId(logId);
                fgbRepo.save(fgb);
            }
        }

        // ③.5 断点2 修复: 清回填的 SalesOrderItem.costUnitPrice (置 null), 让重报能重新回填.
        // 范围: 本批次 productTypeId 在对应计划全部关联 SO (sourceOrderIds, 兼容遗留主 SO) 的行项目.
        // 在同一 @Transactional 内执行 (禁 fail-soft — 撤回是已有事务, 清字段失败应整体回滚).
        // repo 未注入 (老 context) → 跳过 (旧行为). 详见字段注释.
        clearBackfilledCostUnitPrice(factoryId, batchId, log_.getPlanId());

        // ③.6 R3 修复: 回扣结单消耗的原料 usedQuantity (+ 恢复被领用的半成品), 并软删结单,
        // 让原料/半成品库存归还且允许重新结单。同一 @Transactional, 禁 fail-soft。
        boolean settlementRestored = restoreSettlementConsumption(
                factoryId, log_.getPlanId(), logId, reversalOperatorId(log_));
        for (Long settlementSemiWipId : settlementSemiWipIds) {
            SemiFinishedInventory restored = wipRepo.findByIdForUpdate(settlementSemiWipId)
                    .orElseThrow(() -> malformedWipTransaction(
                            "结单恢复后半成品库存不存在", "WIP_NOT_FOUND"));
            validateFinalWipState(restored, false);
        }

        // ③.7 结单成功撤回后恢复计划/批次的可重报状态。
        // 只在确实找到并恢复了活动结单时执行：报工未结单的撤回不应凭空改动一个
        // 合法计划。复位前重新按工厂读取 plan/batch，并锁住 plan，确保不会把跨厂或
        // 错关联数据写成 IN_PROGRESS；任何关联不一致都会让外层事务整体回滚。
        if (settlementRestored) {
            if (settlementScope == null) {
                throw new BusinessException(409, "撤回失败: 已恢复结单但缺少计划/批次作用域")
                        .withCode("REVERSAL_SCOPE_INVALID");
            }
            reopenPlanAndBatchAfterSettlementReversal(settlementScope);
        }

        // ④ 更新 ReportReversalLog → DONE
        log_.setStatus(ReportReversalLog.ReversalStatus.DONE);
        log_.setRevertedTxnIds(revertedTxnIds);
        reversalLogRepo.save(log_);

        log.info("[SP2] executeReversal DONE logId={} batchId={} revertedTxns={}",
                logId, batchId, revertedTxnIds.size());
    }

    /**
     * 这次撤回记在谁头上: 审批人优先, 其次提交人。
     *
     * <p>抽成一处是因为它现在有<b>两个</b>使用者 ({@link #executeReversal} ② 的 REVERSE 行、
     * {@link #restoreSemiFinishedConsumption} 的「还回领用」行)。同一个口径写两遍必漂。
     */
    private static Long reversalOperatorId(ReportReversalLog log_) {
        return log_.getApprovedBy() != null ? log_.getApprovedBy() : log_.getSubmittedBy();
    }

    private static Set<Long> reportIds(List<ProductionReport> reports) {
        Set<Long> ids = new HashSet<>();
        if (reports != null) {
            for (ProductionReport report : reports) {
                if (report != null && report.getId() != null) ids.add(report.getId());
            }
        }
        return ids;
    }

    /** Compare the complete report ledger, not merely report/SFI ids, before the first mutation. */
    private static boolean sameTransactionSnapshot(List<SemiFinishedInventoryTransaction> before,
                                                   List<SemiFinishedInventoryTransaction> after) {
        if (before == null || after == null || before.size() != after.size()) return false;
        Map<Long, TransactionFingerprint> beforeById = new HashMap<>();
        Map<Long, TransactionFingerprint> afterById = new HashMap<>();
        for (SemiFinishedInventoryTransaction t : before) {
            if (t == null || t.getId() == null
                    || beforeById.putIfAbsent(t.getId(), transactionFingerprint(t)) != null) {
                return false;
            }
        }
        for (SemiFinishedInventoryTransaction t : after) {
            if (t == null || t.getId() == null
                    || afterById.putIfAbsent(t.getId(), transactionFingerprint(t)) != null) {
                return false;
            }
        }
        return beforeById.equals(afterById);
    }

    private record TransactionFingerprint(Long id, String factoryId, Long semiFinishedId,
                                          String txnType, String sourceType, String sourceRef,
                                          BigDecimal quantity, BigDecimal unitCostAtTxn,
                                          Long reportId) {}

    private static TransactionFingerprint transactionFingerprint(SemiFinishedInventoryTransaction t) {
        return new TransactionFingerprint(t.getId(), t.getFactoryId(), t.getSemiFinishedId(),
                t.getTxnType(), t.getSourceType(), t.getSourceRef(), t.getQuantity(),
                t.getUnitCostAtTxn(), t.getReportId());
    }

    private static void validateFinalWipState(SemiFinishedInventory wip, boolean mustBeEmpty) {
        BigDecimal produced = Optional.ofNullable(wip.getProducedQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal consumed = Optional.ofNullable(wip.getConsumedQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal available = Optional.ofNullable(wip.getAvailableQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal adjustment = Optional.ofNullable(wip.getAdjustmentQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal expectedAvailable = produced.subtract(consumed).add(adjustment).max(BigDecimal.ZERO);
        if (produced.signum() < 0 || consumed.signum() < 0 || available.signum() < 0
                || available.compareTo(expectedAvailable) != 0
                || (mustBeEmpty && (produced.signum() != 0 || consumed.signum() != 0
                    || available.signum() != 0 || adjustment.signum() != 0
                    || wip.getStatus() != SemiFinishedInventory.Status.DEPLETED))
                || (!mustBeEmpty && ((available.signum() > 0
                    && wip.getStatus() != SemiFinishedInventory.Status.AVAILABLE)
                    || (available.signum() == 0
                    && wip.getStatus() != SemiFinishedInventory.Status.DEPLETED)))) {
            throw new BusinessException(409, "撤回失败: 半成品回放后的库存账本不一致")
                    .withCode("WIP_LEDGER_INVALID");
        }
    }

    /** Reject malformed report-owned WIP rows before any report/reservation mutation. */
    private void validateReportWipTransactions(String factoryId, Long batchId,
                                               List<ProductionReport> reports,
                                               Map<Long, List<SemiFinishedInventoryTransaction>> reportTxns) {
        if (reports == null || reportTxns == null) {
            throw malformedWipTransaction("无法读取报工流水集合", "WIP_LEDGER_INVALID");
        }
        for (ProductionReport report : reports) {
            if (report == null || report.getId() == null) {
                throw malformedWipTransaction("报工记录为空或缺少 ID", "WIP_LEDGER_INVALID");
            }
            for (SemiFinishedInventoryTransaction txn : reportTxns.getOrDefault(report.getId(), List.of())) {
                if (txn == null) {
                    throw malformedWipTransaction("流水为空", "WIP_LEDGER_INVALID");
                }
                if (!Objects.equals(factoryId, txn.getFactoryId())
                        || !Objects.equals(report.getId(), txn.getReportId())) {
                    throw malformedWipTransaction("流水工厂不匹配", "WIP_FACTORY_MISMATCH");
                }
                if (txn.getSemiFinishedId() == null) {
                    throw malformedWipTransaction("流水缺少半成品库存", "WIP_LEDGER_INVALID");
                }
                SemiFinishedInventory sfi = wipRepo.findByIdForUpdate(txn.getSemiFinishedId())
                        .orElseThrow(() -> malformedWipTransaction("半成品库存不存在", "WIP_NOT_FOUND"));
                if (!Objects.equals(factoryId, sfi.getFactoryId())
                        || !Objects.equals(batchId, sfi.getBatchId())) {
                    throw malformedWipTransaction("半成品不属于本工厂本批次", "WIP_SCOPE_INVALID");
                }
                if (SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())) {
                    if (!SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT.equals(txn.getSourceType())
                            || txn.getQuantity() == null || txn.getQuantity().signum() <= 0) {
                        throw malformedWipTransaction("IN 流水缺少半成品或有效数量", "WIP_LEDGER_INVALID");
                    }
                    continue;
                }
                if (SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(txn.getTxnType())) {
                    if (!SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(txn.getSourceType())
                            || txn.getQuantity() == null || txn.getQuantity().signum() == 0) {
                        throw malformedWipTransaction("报工关联 REVERSE 来源非法", "WIP_LEDGER_INVALID");
                    }
                    continue;
                }
                if (SemiFinishedInventoryTransaction.TxnType.ADJUST.equals(txn.getTxnType())) {
                    throw malformedWipTransaction("报工关联 ADJUST 不属于撤回集合", "WIP_LEDGER_INVALID");
                }
                if (!SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType()))
                    throw malformedWipTransaction("报工关联流水类型非法", "WIP_LEDGER_INVALID");
                if (!SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME.equals(txn.getSourceType())
                        || !Objects.equals(report.getId(), txn.getReportId())
                        || txn.getSemiFinishedId() == null || txn.getQuantity() == null
                        || txn.getQuantity().signum() >= 0) {
                    throw malformedWipTransaction("OUT 必须是同报工负量 SECONDARY_CONSUME 流水", "WIP_LEDGER_INVALID");
                }
                String ref = txn.getSourceRef();
                if (ref == null || !ref.startsWith("TASK-")) {
                    throw malformedWipTransaction("OUT 缺少可验证的 TASK 引用", "WIP_LEDGER_INVALID");
                }
                try {
                    Long taskId = Long.valueOf(ref.substring("TASK-".length()));
                    Optional<WorkProcessTask> task = workProcessTaskRepository.findByFactoryIdAndId(factoryId, taskId);
                    if (task == null || task.isEmpty()
                            || !Objects.equals(batchId, task.get().getProductionBatchId())
                            || !Objects.equals(taskId, report.getWorkProcessTaskId())) {
                        throw malformedWipTransaction("OUT 的 TASK 不属于本批次", "WIP_SCOPE_INVALID");
                    }
                } catch (NumberFormatException ex) {
                    throw malformedWipTransaction("OUT 的 TASK 引用无效", "WIP_LEDGER_INVALID");
                }
            }
        }
    }

    private static BusinessException malformedWipTransaction(String message, String code) {
        return new BusinessException(409, "撤回失败: " + message).withCode(code);
    }

    private void lockInvolvedWips(String factoryId, Set<Long> sfiIds) {
        if (sfiIds == null || sfiIds.isEmpty()) return;
        sfiIds.stream().sorted(Comparator.naturalOrder()).forEach(id -> {
            SemiFinishedInventory sfi = wipRepo.findByIdForUpdate(id).orElseThrow(() ->
                    new BusinessException(409, "撤回失败: 半成品库存行不存在或已删除: " + id)
                            .withCode("WIP_NOT_FOUND"));
            if (!factoryId.equals(sfi.getFactoryId())) {
                throw new BusinessException(403, "半成品库存工厂不匹配: " + id);
            }
        });
    }

    /**
     * Repairs only explicitly supplied historical OUT rows.  This is deliberately separate
     * from whole-order reversal: it never edits/deletes an existing ledger row or report.
     */
    @Override
    @Transactional
    public WipOutHistoryRepairResultDTO repairWipOutHistory(String factoryId,
                                                              WipOutHistoryRepairRequestDTO request,
                                                              Long operatorId) {
        if (request == null || request.getReversalLogId() == null
                || request.getOriginalOutTxnIds() == null
                || request.getOriginalOutTxnIds().isEmpty()
                || request.getOriginalOutTxnIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.getOriginalOutTxnIds()).size() != request.getOriginalOutTxnIds().size()) {
            throw repairRejected("reversalLogId 与 originalOutTxnIds 必须非空且不得重复", "INVALID_REQUEST");
        }
        ReportReversalLog reversal = findRepairLogForUpdate(request.getReversalLogId())
                .orElseThrow(() -> repairRejected("撤回日志不存在: " + request.getReversalLogId(), "NOT_FOUND"));
        if (!Objects.equals(factoryId, reversal.getFactoryId())) {
            throw repairRejected("撤回日志工厂不匹配", "FACTORY_MISMATCH");
        }
        if (reversal.getStatus() != ReportReversalLog.ReversalStatus.DONE) {
            throw repairRejected("仅允许修复已完成的撤回日志", "INVALID_STATUS");
        }

        // Read first, then acquire all WIP locks in one stable order, then re-read every row.
        Map<Long, SemiFinishedInventoryTransaction> originals = new HashMap<>();
        Set<Long> sfiIds = new HashSet<>();
        for (Long id : request.getOriginalOutTxnIds()) {
            SemiFinishedInventoryTransaction txn = txnRepo.findById(id)
                    .orElseThrow(() -> repairRejected("原 OUT 流水不存在: " + id, "OUT_NOT_FOUND"));
            validateRepairOut(factoryId, reversal, txn);
            originals.put(id, txn);
            sfiIds.add(txn.getSemiFinishedId());
        }
        lockInvolvedWips(factoryId, sfiIds);
        originals.clear();
        for (Long id : request.getOriginalOutTxnIds()) {
            SemiFinishedInventoryTransaction txn = txnRepo.findById(id)
                    .orElseThrow(() -> repairRejected("加锁后原 OUT 流水不存在: " + id, "LEDGER_CHANGED"));
            validateRepairOut(factoryId, reversal, txn);
            originals.put(id, txn);
        }

        List<WipOutHistoryRepairResultDTO.Item> result = new ArrayList<>();
        List<Long> auditTxnIdsToAppend = new ArrayList<>();
        Map<String, SemiFinishedInventoryTransaction> expectedCompensationOwners = request.getOriginalOutTxnIds().stream()
                .collect(Collectors.toMap(
                        id -> "wip-out-history-repair:reversal-log:" + reversal.getId() + ":out-txn:" + id,
                        originals::get));
        for (Long id : request.getOriginalOutTxnIds()) {
            SemiFinishedInventoryTransaction original = originals.get(id);
            SemiFinishedInventory sfi = wipRepo.findByIdForUpdate(original.getSemiFinishedId())
                    .orElseThrow(() -> repairRejected("半成品库存不存在: " + original.getSemiFinishedId(), "WIP_NOT_FOUND"));
            BigDecimal quantity = original.getQuantity().abs();
            BigDecimal beforeProduced = sfi.getProducedQuantity();
            BigDecimal beforeConsumed = sfi.getConsumedQuantity();
            BigDecimal beforeAvailable = sfi.getAvailableQuantity();
            String beforeStatus = sfi.getStatus();

            List<SemiFinishedInventoryTransaction> txns = txnRepo
                    .findBySemiFinishedIdOrderByCreatedAtAsc(sfi.getId());
            ProductionReport report = reportRepo.findById(original.getReportId()).orElseThrow(
                    () -> repairRejected("原 OUT 的 report 不存在", "REPORT_NOT_FOUND"));
            if (!isReversalOwnedOut(reversal, original, report, txns)) {
                throw repairRejected("原 OUT 无法证明属于该撤回日志，拒绝修复", "REVERSAL_LINK_UNPROVEN");
            }
            List<SemiFinishedInventoryTransaction> allPositiveCompensations = txns.stream()
                    .filter(t -> SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType()))
                    .filter(t -> t.getQuantity() != null && t.getQuantity().signum() > 0)
                    .filter(t -> Objects.equals(t.getReportId(), original.getReportId()))
                    .filter(t -> Objects.equals(t.getSemiFinishedId(), original.getSemiFinishedId()))
                    .collect(Collectors.toList());
            String expectedSourceRef = "wip-out-history-repair:reversal-log:" + reversal.getId() + ":out-txn:" + id;
            List<SemiFinishedInventoryTransaction> matching = allPositiveCompensations.stream()
                    .filter(t -> SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(t.getSourceType()))
                    .filter(t -> Objects.equals(t.getSourceRef(),
                            expectedSourceRef))
                    .collect(Collectors.toList());
            if (allPositiveCompensations.stream().anyMatch(t -> {
                SemiFinishedInventoryTransaction owner = expectedCompensationOwners.get(t.getSourceRef());
                return !Objects.equals(t.getSourceType(), SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                        || owner == null
                        || !Objects.equals(owner.getReportId(), t.getReportId())
                        || !Objects.equals(owner.getSemiFinishedId(), t.getSemiFinishedId());
            })) {
                throw repairRejected("原 OUT 存在非本次日志的正量补偿，无法判定归属", "AMBIGUOUS_COMPENSATION");
            }
            if (matching.size() > 1) {
                throw repairRejected("原 OUT 已存在多条正量 REVERSE，拒绝继续", "NON_UNIQUE_COMPENSATION");
            }
            Long reverseId = null;
            boolean idempotent = false;
            if (!matching.isEmpty()) {
                SemiFinishedInventoryTransaction existing = matching.get(0);
                if (existing.getQuantity().compareTo(quantity) != 0) {
                    throw repairRejected("原 OUT 已存在部分补偿，拒绝继续", "PARTIAL_COMPENSATION");
                }
                if (!sameCost(existing.getUnitCostAtTxn(), original.getUnitCostAtTxn())) {
                    throw repairRejected("原 OUT 已存在异成本补偿，拒绝继续", "COST_MISMATCH");
                }
                reverseId = existing.getId();
                idempotent = true;
            } else {
                SemiFinishedInventoryTransaction reverse = SemiFinishedInventoryTransaction.builder()
                        .factoryId(factoryId)
                        .semiFinishedId(sfi.getId())
                        .txnType(SemiFinishedInventoryTransaction.TxnType.REVERSE)
                        .sourceType(SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                        .sourceRef("wip-out-history-repair:reversal-log:" + reversal.getId() + ":out-txn:" + id)
                        .quantity(quantity)
                        .unitCostAtTxn(original.getUnitCostAtTxn())
                        .reportId(original.getReportId())
                        .operatorId(operatorId)
                        .build();
                reverseId = txnRepo.save(reverse).getId();
            }
            List<Long> existingAuditIds = reversal.getRevertedTxnIds() == null
                    ? List.of() : reversal.getRevertedTxnIds();
            if (reverseId != null && !existingAuditIds.contains(reverseId)
                    && !auditTxnIdsToAppend.contains(reverseId)) {
                auditTxnIdsToAppend.add(reverseId);
            }
            replayMovingAverage(sfi.getId(), factoryId, LocalDateTime.now());
            SemiFinishedInventory after = wipRepo.findByIdForUpdate(sfi.getId())
                    .orElseThrow(() -> repairRejected("回放后半成品库存不存在", "WIP_NOT_FOUND"));
            result.add(new WipOutHistoryRepairResultDTO.Item(id, reverseId, after.getId(), quantity,
                    beforeProduced, after.getProducedQuantity(), beforeConsumed, after.getConsumedQuantity(),
                    beforeAvailable, after.getAvailableQuantity(), beforeStatus, after.getStatus(), idempotent));
        }
        if (!auditTxnIdsToAppend.isEmpty()) {
            List<Long> auditIds = reversal.getRevertedTxnIds() == null
                    ? new ArrayList<>() : new ArrayList<>(reversal.getRevertedTxnIds());
            auditIds.addAll(auditTxnIdsToAppend);
            reversal.setRevertedTxnIds(auditIds);
            reversalLogRepo.save(reversal);
        }
        return new WipOutHistoryRepairResultDTO(reversal.getId(), result);
    }

    private Optional<ReportReversalLog> findRepairLogForUpdate(Long id) {
        if (entityManager == null) {
            throw repairRejected("历史 WIP 修复无法取得撤回日志锁，拒绝执行", "REPAIR_LOCK_UNAVAILABLE");
        }
        return Optional.ofNullable(entityManager.find(ReportReversalLog.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    private boolean isReversalOwnedOut(ReportReversalLog reversal,
                                       SemiFinishedInventoryTransaction original,
                                       ProductionReport report,
                                       List<SemiFinishedInventoryTransaction> txns) {
        List<Long> ids = reversal.getRevertedTxnIds();
        if (ids == null || ids.isEmpty() || txns == null || reversal.getApprovedAt() == null
                || report.getDeletedAt() == null || original.getCreatedAt() == null
                || original.getCreatedAt().isAfter(report.getDeletedAt())
                || original.getCreatedAt().isAfter(reversal.getApprovedAt())) {
            return false;
        }
        Set<Long> owned = new HashSet<>(ids);
        return txns.stream().anyMatch(t -> owned.contains(t.getId())
                && SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType())
                && t.getQuantity() != null && t.getQuantity().signum() < 0
                && SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(t.getSourceType())
                && Objects.equals(t.getSourceRef(), "reversal-log:" + reversal.getId())
                && Objects.equals(t.getSemiFinishedId(), original.getSemiFinishedId())
                && t.getCreatedAt() != null && report.getDeletedAt() != null
                && Math.abs(Duration.between(t.getCreatedAt(), reversal.getApprovedAt()).toMillis()) <= 10_000
                && Math.abs(Duration.between(report.getDeletedAt(), reversal.getApprovedAt()).toMillis()) <= 10_000);
    }

    private void validateRepairOut(String factoryId, ReportReversalLog reversal,
                                   SemiFinishedInventoryTransaction txn) {
        if (!Objects.equals(factoryId, txn.getFactoryId())
                || !SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())
                || !SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME.equals(txn.getSourceType())
                || txn.getSemiFinishedId() == null || txn.getQuantity() == null
                || txn.getQuantity().signum() >= 0 || txn.getReportId() == null) {
            throw repairRejected("原流水不是同厂、负量 SECONDARY_CONSUME OUT 或缺少 reportId", "INVALID_OUT");
        }
        ProductionReport report = reportRepo.findById(txn.getReportId())
                .orElseThrow(() -> repairRejected("原 OUT 的 report 不存在", "REPORT_NOT_FOUND"));
        if (!Objects.equals(factoryId, report.getFactoryId())
                || !Objects.equals(reversal.getBatchId(), report.getBatchId())
                || report.getDeletedAt() == null) {
            throw repairRejected("report 必须属于同厂同批且已软删除", "REPORT_SCOPE_INVALID");
        }
        String ref = txn.getSourceRef();
        if (ref == null || !ref.startsWith("TASK-")) {
            throw repairRejected("OUT sourceRef 不是 TASK-<id>", "TASK_REF_INVALID");
        }
        final Long taskId;
        try {
            taskId = Long.valueOf(ref.substring("TASK-".length()));
        } catch (NumberFormatException ex) {
            throw repairRejected("OUT sourceRef 的 taskId 无效", "TASK_REF_INVALID");
        }
        WorkProcessTask task = workProcessTaskRepository.findByFactoryIdAndId(factoryId, taskId)
                .orElseThrow(() -> repairRejected("OUT sourceRef 指向的 task 不存在", "TASK_NOT_FOUND"));
        if (!Objects.equals(reversal.getBatchId(), task.getProductionBatchId())
                || !Objects.equals(taskId, report.getWorkProcessTaskId())) {
            throw repairRejected("OUT sourceRef 指向跨批或非该 report 的 task", "TASK_SCOPE_INVALID");
        }
        SemiFinishedInventory sfi = wipRepo.findById(txn.getSemiFinishedId())
                .orElseThrow(() -> repairRejected("原 OUT 的半成品库存不存在", "WIP_NOT_FOUND"));
        if (!Objects.equals(factoryId, sfi.getFactoryId())
                || !Objects.equals(reversal.getBatchId(), sfi.getBatchId())) {
            throw repairRejected("半成品库存不属于同厂同生产批次", "WIP_SCOPE_INVALID");
        }
    }

    private static BusinessException repairRejected(String message, String code) {
        return new BusinessException(409, message).withCode(code);
    }

    /**
     * G1 的正式 WIP OUT 使用 {@code TASK-&lt;taskId&gt;} 作为 sourceRef，不是批次号。
     * 只有同一整单内的下游任务才属于本次撤回集合；跨批次/转出/无法解析的 OUT 必须拒绝。
     */
    private boolean hasExternalDownstreamConsumption(String factoryId, Long batchId,
                                                      Set<Long> currentReportIds) {
        if (wipRepo == null || workProcessTaskRepository == null || batchId == null) {
            return true;
        }
        List<SemiFinishedInventory> wips =
                wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(factoryId, batchId);
        if (wips == null) {
            return true;
        }
        for (SemiFinishedInventory wip : wips) {
            if (wip == null || wip.getId() == null) {
                continue;
            }
            List<SemiFinishedInventoryTransaction> txns =
                    txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(wip.getId());
            if (txns == null) {
                continue;
            }
            Map<String, BigDecimal> restoredByReportAndSfi = txns.stream()
                    .filter(t -> SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType())
                            && SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(t.getSourceType())
                            && t.getQuantity() != null && t.getQuantity().signum() > 0
                            && t.getReportId() != null)
                    .collect(Collectors.groupingBy(
                            t -> t.getReportId() + ":" + t.getSemiFinishedId(),
                            Collectors.reducing(BigDecimal.ZERO,
                                    SemiFinishedInventoryTransaction::getQuantity,
                                    BigDecimal::add)));
            // 外部领用血缘 (无 reportId): 按净额判 —— 被别批领走又在那批撤回时还回来的, 不再算「仍被领着」。
            BigDecimal externalNet = BigDecimal.ZERO;
            for (SemiFinishedInventoryTransaction txn : txns) {
                if (isExternalConsumptionOut(txn) && txn.getQuantity() != null) {
                    externalNet = externalNet.add(txn.getQuantity().abs());
                } else if (isExternalConsumptionReverse(txn) && txn.getQuantity() != null) {
                    externalNet = externalNet.subtract(txn.getQuantity().abs());
                }
            }
            if (externalNet.signum() < 0) {
                // 还回多于领用: 账不平 —— 与 SFI_REVERSE_OVER_CONSUMED 同一纪律, ⛔ 不静默放行
                throw malformedWipTransaction("外部领用净额为负 (还回多于领用), 账不平, 请先盘点该半成品批次",
                        "WIP_LEDGER_INVALID");
            }
            if (externalNet.signum() > 0) {
                return true; // 还有别批领走没还回来的量 ⇒ 业务拒绝 DOWNSTREAM_CONSUMED (这条要保留)
            }
            for (SemiFinishedInventoryTransaction txn : txns) {
                if (!SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())) {
                    continue;
                }
                if (isExternalConsumptionOut(txn)) {
                    continue; // 已按净额判过
                }
                if (!SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME.equals(txn.getSourceType())) {
                    return true;
                }
                // reverseReportPosting / prior whole-order repair may already have restored this exact report.
                if (txn.getReportId() != null && txn.getQuantity() != null) {
                    String key = txn.getReportId() + ":" + txn.getSemiFinishedId();
                    BigDecimal restored = restoredByReportAndSfi.getOrDefault(key, BigDecimal.ZERO);
                    BigDecimal required = txn.getQuantity().abs();
                    if (restored.compareTo(required) >= 0) {
                        restoredByReportAndSfi.put(key, restored.subtract(required));
                        continue;
                    }
                    if (restored.signum() > 0) return true; // partial compensation is ambiguous
                }
                if (txn.getReportId() == null || currentReportIds == null
                        || !currentReportIds.contains(txn.getReportId())) {
                    return true;
                }
                String ref = txn.getSourceRef();
                if (String.valueOf(batchId).equals(ref)) {
                    return true; // legacy batch-level reference: no task boundary to prove safe
                }
                if (ref == null || !ref.startsWith("TASK-")) {
                    return true; // fail closed for an unrecognised consumer
                }
                try {
                    Long taskId = Long.valueOf(ref.substring("TASK-".length()));
                    Optional<WorkProcessTask> consumerOpt = workProcessTaskRepository
                            .findByFactoryIdAndId(factoryId, taskId);
                    WorkProcessTask consumer = consumerOpt == null ? null : consumerOpt.orElse(null);
                    if (consumer == null || !batchId.equals(consumer.getProductionBatchId())) {
                        return true;
                    }
                } catch (NumberFormatException ex) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 「外部领用血缘」—— 不属于任何报工的 SFI 流水, 只有 sourceRef 说得清来路:
     * <ul>
     *   <li>OUT {@code interim-settle-out:<batchNo>}: 正式报工扣回料 / 小结 SFI 投料 ({@code consumeClerkSemiStrict});</li>
     *   <li>REVERSE {@code reversal-log:<id>} (#527 整单撤回还回回料) / {@code interim-settle-reverse-out:<batchNo>}
     *       (小结撤销 {@code restoreClerkSemi})。</li>
     * </ul>
     * 它们 {@code reportId} 必然为 null (挂谁的报工都会撞 validateReportWipTransactions 的批次作用域)。
     * 整单撤回对这类行的态度: <b>豁免 reportId 要求与报工级配对, 但余额照算</b>; 「还被领着」按同一 SFI 上
     * OUT − REVERSE 的净额判。⛔ 判据只认这几个前缀 —— 没有 sourceRef / 未知前缀的无 reportId 流水照旧拦。
     */
    static boolean isExternalConsumptionOut(SemiFinishedInventoryTransaction txn) {
        return txn != null
                && SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())
                && txn.getReportId() == null
                && txn.getSourceRef() != null
                && txn.getSourceRef().startsWith(com.cretas.aims.service.wip.WipLedgerEntries.CLERK_OUT_REF_PREFIX);
    }

    /**
     * 「外部领用<b>还回</b>」—— {@link #isExternalConsumptionOut} 的逆。
     *
     * <p>🔴 <b>必须是正量</b> (BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906)。
     * 「还回领用」按定义就是 OUT 的逆 ⇒ 正量 (见 {@code WipLedgerEntries.settlementRestoreRow}
     * 与 {@code replayMovingAverage} 的符号表: 负量 REVERSE = 退回产出, 正量 REVERSE = 还回领用)。
     * 本方法原来<b>只看前缀不看符号</b>, 于是把一类<b>负量</b>行也认成「还回」:
     *
     * <p>{@code e774ebf298} (#391) 之前的整单撤回代码路径写 IN 腿补偿时<b>不写 report_id</b>
     * (它当时把 {@code reportId=null} 当成「血缘由 sourceRef 承担」), sourceRef 正是
     * {@code reversal-log:<id>} ⇒ 这些行同时满足「REVERSE + REVERSAL + reportId 为 null +
     * reversal-log: 前缀」, 被误判成外部领用还回, 随即撞
     * {@link #validateFullWipLedgersBeforeMutation} 里那句 {@code signum() <= 0} 守卫,
     * 抛 {@code PARTIAL_COMPENSATION}「外部领用还回流水数量非法」——
     * <b>该 SFI 此后任何一次整单撤回都 409</b>。
     *
     * <p>实测锁死 (LIUSHANMEN {@code cretas_prod_db}, 2026-09-06 只读): sfi 370/371/372
     * (批次 …B10888-S1/S2/S3), 流水 141/142/143 = 负量 REVERSE + reportId 为 null +
     * {@code reversal-log:18}。
     *
     * <p>⛔ 修法<b>不是</b>把前缀白名单再放宽一条 —— 那正是形态 E (代理判据不断加启发式,
     * 直到有人把闸关掉)。加的是<b>符号</b>这一维: 它是这两种语义在账本上<b>本来就有</b>的区分,
     * 不是又一条猜测。负量那一支交给 {@link #isLegacyInLegCompensation} 按<b>撤回日志</b>认领。
     */
    static boolean isExternalConsumptionReverse(SemiFinishedInventoryTransaction txn) {
        return txn != null
                && SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(txn.getTxnType())
                && SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(txn.getSourceType())
                && txn.getReportId() == null
                // 🔴 还回领用 = OUT 的逆 ⇒ 正量。⛔ 去掉这一维, 负量的遗留 IN 腿补偿会被
                //    重新误判成「还回」并把整条 SFI 锁死 (见方法 javadoc)。
                && txn.getQuantity() != null
                && txn.getQuantity().signum() > 0
                && txn.getSourceRef() != null
                && (txn.getSourceRef().startsWith("reversal-log:")
                        || txn.getSourceRef().startsWith("interim-settle-reverse-out:"));
    }

    /**
     * 「遗留 IN 腿补偿」—— {@code e774ebf298} (#391) 之前写下的、<b>没有 report_id 的负量 REVERSE</b>。
     *
     * <p>🔴 <b>判据是「有没有撤回日志认领它」, 不是 sourceRef 长什么样。</b>
     * {@code report_reversal_logs.reverted_txn_ids} 是整单撤回自己写下的权威清单
     * (见 {@code executeReversal} ②: 每写一条 REVERSE 就 {@code revertedTxnIds.add(saved.getId())}),
     * 它<b>直接说出</b>「这条流水是那次撤回记的账」。前缀只是个代理 ——
     * 代理判据会同时造出假阳性和假阴性 (形态 E), 而这份清单不会。
     *
     * <p>这类行没有 report_id ⇒ 进不了 {@link #matchExistingCompensations} 的配对 multiset
     * (配对键的第一维就是 reportId)。但它<b>真的冲减过产出</b> ⇒ 余额必须照算。
     * 所以调用方的处理是「不进配对、计入 rawProduced」, 与
     * {@link #isExternalConsumptionOut} / {@link #isExternalConsumptionReverse} 同一条纪律。
     *
     * <p>⚠️ 这是<b>运行时兜底</b>, 与 {@code V20261101_33} 的数据回填互不替代:
     * 回填能证明出确切 report_id 的行 (回填后它们走正常配对路径), 兜底覆盖回填证明不出来的行。
     * 两者都证明不了的, 照旧 fail-closed 拦住。
     *
     * @param claimedTxnIds 本工厂所有<b>未软删</b>撤回日志认领过的流水 id 并集
     */
    static boolean isLegacyInLegCompensation(SemiFinishedInventoryTransaction txn,
                                             Set<Long> claimedTxnIds) {
        return txn != null
                && SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(txn.getTxnType())
                && SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(txn.getSourceType())
                && txn.getReportId() == null
                // 负量 = 退回产出 (IN 的逆)。正量那一支是「还回领用」, 归 isExternalConsumptionReverse。
                && txn.getQuantity() != null
                && txn.getQuantity().signum() < 0
                && txn.getId() != null
                && claimedTxnIds != null
                && claimedTxnIds.contains(txn.getId());
    }

    private record CompensationKey(Long reportId, Long semiFinishedId,
                                   int sign, BigDecimal quantity) {}

    /**
     * Matches the complete existing compensation multiset before any mutation.  Quantity and
     * provenance define idempotency; cost is deliberately not a key because a legitimate prior
     * posting may have cleared the WIP cost before writing its REVERSE row.
     */
    private static Set<Long> matchExistingCompensations(
            List<SemiFinishedInventoryTransaction> originals,
            List<SemiFinishedInventoryTransaction> reversals) {
        Map<CompensationKey, List<Long>> originalsByKey = new HashMap<>();
        for (SemiFinishedInventoryTransaction original : originals) {
            if (original == null || original.getId() == null || original.getReportId() == null
                    || original.getSemiFinishedId() == null || original.getQuantity() == null
                    || original.getQuantity().signum() == 0) {
                throw new BusinessException(409, "撤回失败: 原始半成品流水无法建立补偿键")
                        .withCode("WIP_LEDGER_INVALID");
            }
            BigDecimal reverseQuantity = SemiFinishedInventoryTransaction.TxnType.IN.equals(original.getTxnType())
                    ? original.getQuantity().negate() : original.getQuantity().abs();
            CompensationKey key = compensationKey(original.getReportId(), original.getSemiFinishedId(),
                    reverseQuantity);
            originalsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(original.getId());
        }

        Set<Long> compensated = new HashSet<>();
        for (SemiFinishedInventoryTransaction reversal : reversals) {
            if (reversal == null || reversal.getQuantity() == null || reversal.getQuantity().signum() == 0
                    || !SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(reversal.getTxnType())
                    || !SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(reversal.getSourceType())) {
                throw new BusinessException(409, "撤回失败: 既有补偿流水来源或数量非法")
                        .withCode("PARTIAL_COMPENSATION");
            }
            CompensationKey key = compensationKey(reversal.getReportId(), reversal.getSemiFinishedId(),
                    reversal.getQuantity());
            List<Long> owners = originalsByKey.get(key);
            if (owners == null || owners.isEmpty()) {
                throw new BusinessException(409, "撤回失败: 存在多余、部分或超量补偿流水")
                        .withCode("PARTIAL_COMPENSATION");
            }
            compensated.add(owners.remove(0));
        }
        return compensated;
    }

    /**
     * Validates each involved SFI's complete immutable ledger before any reversal mutation.
     * Report-scoped reads alone cannot see an orphan REVERSE with a null/wrong report id; replay
     * reads the whole SFI ledger and would otherwise clamp a negative raw balance back to zero.
     */
    private void validateFullWipLedgersBeforeMutation(
            String factoryId,
            Set<Long> involvedSfiIds,
            Map<Long, List<SemiFinishedInventoryTransaction>> reportTxns) {
        if (involvedSfiIds == null || reportTxns == null) {
            throw malformedWipTransaction("无法读取完整半成品流水", "WIP_LEDGER_INVALID");
        }
        // 撤回日志认领过的流水 id 并集 —— 只在真的读到「无 report_id 的负量 REVERSE」时才去查库,
        // 且整次校验只查一次 (工厂级日志条数是每批次一条, 量级很小)。
        // ⛔ 不写 jsonb @> 原生查询: 那样 H2 测试跑不动, 而这里的候选集小到在 Java 里扫完全够。
        Map<String, Set<Long>> claimedCache = new HashMap<>();
        Supplier<Set<Long>> claimedTxnIds =
                () -> claimedCache.computeIfAbsent(factoryId, this::loadClaimedTxnIds);
        for (Long sfiId : new TreeSet<>(involvedSfiIds)) {
            if (sfiId == null) {
                throw malformedWipTransaction("半成品流水缺少库存 ID", "WIP_LEDGER_INVALID");
            }
            List<SemiFinishedInventoryTransaction> ledger =
                    txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId);
            if (ledger == null) {
                throw malformedWipTransaction("无法读取完整半成品流水", "WIP_LEDGER_INVALID");
            }
            Set<Long> ledgerIds = new HashSet<>();
            List<SemiFinishedInventoryTransaction> originals = new ArrayList<>();
            List<SemiFinishedInventoryTransaction> reversals = new ArrayList<>();
            BigDecimal rawProduced = BigDecimal.ZERO;
            BigDecimal rawConsumed = BigDecimal.ZERO;
            for (SemiFinishedInventoryTransaction txn : ledger) {
                if (txn == null || txn.getId() == null || !ledgerIds.add(txn.getId())
                        || !Objects.equals(factoryId, txn.getFactoryId())
                        || !Objects.equals(sfiId, txn.getSemiFinishedId())
                        || txn.getQuantity() == null || txn.getQuantity().signum() == 0) {
                    throw malformedWipTransaction("完整半成品流水存在空值、重复 ID、跨工厂或零数量",
                            "WIP_LEDGER_INVALID");
                }
                if (isExternalConsumptionOut(txn)) {
                    // 外部领用: 不属于任何报工 ⇒ 不进配对 (originals), 但它真的动过余额 ⇒ 计入 consumed
                    if (txn.getQuantity().signum() >= 0) {
                        throw malformedWipTransaction("外部领用 OUT 流水数量非法", "WIP_LEDGER_INVALID");
                    }
                    rawConsumed = rawConsumed.add(txn.getQuantity().abs());
                    continue;
                }
                if (isExternalConsumptionReverse(txn)) {
                    // 外部领用的还回: 同理不进配对 (reversals), 余额上抵消 consumed
                    if (txn.getQuantity().signum() <= 0) {
                        throw malformedWipTransaction("外部领用还回流水数量非法", "PARTIAL_COMPENSATION");
                    }
                    rawConsumed = rawConsumed.subtract(txn.getQuantity());
                    continue;
                }
                if (SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())) {
                    if (txn.getQuantity().signum() <= 0 || txn.getReportId() == null) {
                        throw malformedWipTransaction("IN 流水来源或数量非法", "WIP_LEDGER_INVALID");
                    }
                    originals.add(txn);
                    rawProduced = rawProduced.add(txn.getQuantity());
                } else if (SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())) {
                    if (txn.getQuantity().signum() >= 0 || txn.getReportId() == null) {
                        throw malformedWipTransaction("OUT 流水来源或数量非法", "WIP_LEDGER_INVALID");
                    }
                    originals.add(txn);
                    rawConsumed = rawConsumed.add(txn.getQuantity().abs());
                } else if (SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(txn.getTxnType())) {
                    if (!SemiFinishedInventoryTransaction.SourceType.REVERSAL.equals(txn.getSourceType())) {
                        throw malformedWipTransaction("存在孤立或非法来源的 REVERSE 流水",
                                "PARTIAL_COMPENSATION");
                    }
                    if (txn.getReportId() == null) {
                        // 遗留 IN 腿补偿 (#391 之前的写法, 无 report_id): 血缘由撤回日志的
                        // reverted_txn_ids 认领 —— 认领得到就照算余额, 认领不到照旧 fail-closed。
                        if (!isLegacyInLegCompensation(txn, claimedTxnIds.get())) {
                            throw malformedWipTransaction("存在孤立或非法来源的 REVERSE 流水",
                                    "PARTIAL_COMPENSATION");
                        }
                        // ⛔ 不进 reversals: 没有 reportId 就配不成对 (CompensationKey 第一维就是它)。
                        //    但它确实冲减过产出 ⇒ rawProduced 照算 (负量相加 = 减)。
                        rawProduced = rawProduced.add(txn.getQuantity());
                        continue;
                    }
                    reversals.add(txn);
                    if (txn.getQuantity().signum() < 0) {
                        rawProduced = rawProduced.add(txn.getQuantity());
                    } else {
                        rawConsumed = rawConsumed.subtract(txn.getQuantity());
                    }
                } else if (!SemiFinishedInventoryTransaction.TxnType.ADJUST.equals(txn.getTxnType())) {
                    throw malformedWipTransaction("完整半成品流水包含未知类型", "WIP_LEDGER_INVALID");
                }
            }

            Set<Long> alreadyCompensated = matchExistingCompensations(originals, reversals);
            for (List<SemiFinishedInventoryTransaction> rows : reportTxns.values()) {
                if (rows == null) {
                    throw malformedWipTransaction("报工流水集合为空", "WIP_LEDGER_INVALID");
                }
                for (SemiFinishedInventoryTransaction original : rows) {
                    if (original == null || !Objects.equals(sfiId, original.getSemiFinishedId())
                            || alreadyCompensated.contains(original.getId())) {
                        continue;
                    }
                    if (SemiFinishedInventoryTransaction.TxnType.IN.equals(original.getTxnType())) {
                        rawProduced = rawProduced.subtract(original.getQuantity());
                    } else if (SemiFinishedInventoryTransaction.TxnType.OUT.equals(original.getTxnType())) {
                        rawConsumed = rawConsumed.subtract(original.getQuantity().abs());
                    }
                }
            }
            if (rawProduced.signum() < 0 || rawConsumed.signum() < 0) {
                throw new BusinessException(409,
                        "撤回失败: 应用本次冲销后半成品完整流水出现负产出或超额恢复")
                        .withCode("PARTIAL_COMPENSATION");
            }
        }
    }

    /**
     * 本工厂所有<b>未软删</b>撤回日志认领过的流水 id 并集 —— {@link #isLegacyInLegCompensation} 的证据源。
     *
     * <p>⚠️ 软删掉的日志不算认领 (fail-closed): 一条被撤销的撤回不该继续为流水背书。
     */
    private Set<Long> loadClaimedTxnIds(String factoryId) {
        Set<Long> claimed = new HashSet<>();
        List<ReportReversalLog> logs =
                reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);
        if (logs == null) {
            return claimed;
        }
        for (ReportReversalLog claimant : logs) {
            if (claimant != null && claimant.getRevertedTxnIds() != null) {
                for (Long claimedId : claimant.getRevertedTxnIds()) {
                    if (claimedId != null) {
                        claimed.add(claimedId);
                    }
                }
            }
        }
        return claimed;
    }

    private static CompensationKey compensationKey(Long reportId, Long semiFinishedId, BigDecimal quantity) {
        return new CompensationKey(reportId, semiFinishedId, quantity.signum(),
                quantity.abs().stripTrailingZeros());
    }

    private static boolean sameCost(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    /**
     * 还回正式报工时扣掉的回料 (手工选的 / 按配比自动追加的都长一样: {@code semiFinished && stockConsumedAtSubmit})。
     *
     * <p>行的选择与 {@link #softDeleteProcessSheetRows} 完全相同 (整单撤回按计划), 必须在软删之前读。
     * 每条引用经 {@link WipInventoryService#restoreClerkSemiStrict} 还回 —— 与扣减 {@code consumeClerkSemiStrict}
     * <b>成对</b>, 计数单位 (盒/个) 的折算在那一边只写一次 (终审 #527 ①)。REVERSE 流水 {@code reportId=null},
     * 血缘由 {@code sourceRef=reversal-log:<id>} 承担 (终审 #527 ②: 挂报工会让本批此后每次 executeReversal 撞
     * WIP_SCOPE_INVALID)。还回流水 id 进 {@code revertedTxnIds}; 源行不在的引用跳过并写进 reason 后缀, 不只留日志。
     * 幂等靠 {@code deletedAt} 过滤 (撤回软删过的行不再读到), ⛔ 不靠 sourceRef 判重。
     */
    private void restoreReworkConsumedAtSubmit(String factoryId, Long batchId, ReportReversalLog log_,
                                               List<Long> revertedTxnIds) {
        if (processSheetRowRepository == null || batchId == null) {
            return;
        }
        String planId = log_.getPlanId();
        List<ProcessSheetRow> rows = planId != null && !planId.isBlank()
                ? processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId)
                : processSheetRowRepository.findByFactoryIdAndBatchId(factoryId, batchId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = payloadMapper();
        int restoredRefs = 0;
        BigDecimal restoredKg = BigDecimal.ZERO;
        List<String> skipped = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (row == null || row.getDeletedAt() != null || row.getRowPayload() == null
                    || row.getRowPayload().isBlank()) {
                continue;
            }
            com.cretas.aims.dto.processentry.ProcessSheetRowRequest req;
            try {
                req = mapper.readValue(row.getRowPayload(),
                        com.cretas.aims.dto.processentry.ProcessSheetRowRequest.class);
            } catch (Exception e) {
                // 读不懂的载荷不能让整单撤回静默漏还 —— 抛出来 (与本方法其它 loud-fail 同口径)
                throw malformedWipTransaction("报工行载荷无法解析, 无法判断有没有提交时扣的回料: rowId="
                        + row.getId(), "PROCESS_SHEET_PAYLOAD_INVALID");
            }
            if (req.getUpstreamSources() == null) {
                continue;
            }
            for (com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                if (ref == null || !ref.isSemiFinished() || !ref.isStockConsumedAtSubmit()
                        || ref.getSourceBatchNumber() == null || ref.getSourceBatchNumber().isBlank()
                        || ref.getFeedQuantityKg() == null || ref.getFeedQuantityKg().signum() <= 0) {
                    continue;
                }
                if (wipInventoryService == null) {
                    throw new BusinessException(500, "半成品库存服务未就绪, 无法还回提交时扣的回料 (来源: "
                            + ref.getSourceBatchNumber() + ")").withCode("WIP_SERVICE_UNAVAILABLE");
                }
                String batchNo = ref.getSourceBatchNumber().trim();
                com.cretas.aims.service.wip.WipInventoryService.ClerkSemiRestore restore = wipInventoryService.restoreClerkSemiStrict(
                        factoryId, batchNo, ref.getFeedQuantityKg(),
                        "reversal-log:" + log_.getId(), reversalOperatorId(log_));
                if (restore == null) {
                    // 源行已不在 (被删/盘掉): 无处可还, 登记而不阻塞撤回, ⛔ 不造库存
                    skipped.add(batchNo + "×" + ref.getFeedQuantityKg().stripTrailingZeros().toPlainString() + "kg");
                    continue;
                }
                if (restore.ledgerTxnId() != null) {
                    revertedTxnIds.add(restore.ledgerTxnId());
                }
                restoredRefs++;
                restoredKg = restoredKg.add(ref.getFeedQuantityKg());
            }
        }
        if (!skipped.isEmpty()) {
            String note = " [撤回还回回料: 跳过 " + skipped.size() + " 条(源行不在): " + String.join(", ", skipped) + "]";
            log_.setReason((log_.getReason() == null ? "" : log_.getReason()) + note);
            log.warn("[SP2] 撤回还回回料: factoryId={} batchId={} 跳过 {} 条 (源行不在): {}",
                    factoryId, batchId, skipped.size(), skipped);
        }
        if (restoredRefs > 0) {
            log.info("[SP2] 撤回还回提交时扣的回料: factoryId={} planId={} batchId={} refs={} feedKg={}",
                    factoryId, planId, batchId, restoredRefs, restoredKg.stripTrailingZeros().toPlainString());
        }
    }

    private com.fasterxml.jackson.databind.ObjectMapper payloadMapper() {
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        m.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return m;
    }

    private void softDeleteProcessSheetRows(String factoryId, Long batchId, String planId,
                                            LocalDateTime deletedAt) {
        if (processSheetRowRepository == null || batchId == null) {
            return;
        }
        // WHOLE_ORDER reversal is plan-scoped. A clerk workflow materializes one output batch per
        // step, so deleting only the selected final batch would leave every upstream row active.
        List<ProcessSheetRow> rows = planId != null && !planId.isBlank()
                ? processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId)
                : processSheetRowRepository.findByFactoryIdAndBatchId(factoryId, batchId);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (ProcessSheetRow row : rows) {
            row.setDeletedAt(deletedAt);
        }
        processSheetRowRepository.saveAll(rows);
        log.info("[SP2] 撤回软删逐道录入行: factoryId={} planId={} batchId={} rows={}",
                factoryId, planId, batchId, rows.size());
    }

    // ==================== 审批操作 ====================

    @Override
    @Transactional
    public void approveReversal(String factoryId, Long logId, Long approvedBy) {
        ReportReversalLog log_ = findReversalLogForUpdate(logId, factoryId);
        // 跨租户校验: 撤回日志须属于当前工厂 (防止越权批准其它工厂的撤回执行)
        if (log_.getFactoryId() == null || !log_.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该撤回申请 / 该撤回申请不属于当前工厂")
                    .withHint("请确认撤回申请 ID 是否属于本工厂");
        }
        // 兼容旧版本在无外层事务时留下的 APPROVED 半执行记录。再次审批只恢复执行，
        // 不改原审批人/审批时间；executeReversal 本身按 DONE 幂等，并在本事务内原子收尾。
        if (ReportReversalLog.ReversalStatus.APPROVED == log_.getStatus()) {
            log.warn("[SP2] approveReversal resume previously approved logId={} approvedBy={}",
                    logId, log_.getApprovedBy());
            executeReversal(logId, log_.getFactoryId());
            return;
        }
        if (ReportReversalLog.ReversalStatus.PENDING != log_.getStatus()) {
            throw new BusinessException(409, String.format(
                    "只有待审批状态的撤回申请可审批，当前状态: %s (logId=%d)", log_.getStatus(), logId))
                    .withCode("INVALID_STATUS");
        }
        // W6 #27: 4 眼原则 — 审批人不能与提交人相同 (防自批漏洞)。
        // 撤回是高风险写操作 (软删报工 + 回滚库存均价), 提交人自己批准 = 无监督。
        // 参考 ArApServiceImpl 4-eyes 范式 (审批人 ≠ 提交人)。
        if (log_.getSubmittedBy() != null && log_.getSubmittedBy().equals(approvedBy)) {
            throw new BusinessException(403, "审批人不能与提交人相同 (4 眼原则)")
                    .withCode("SELF_APPROVAL_FORBIDDEN")
                    .withHint("请使用其他主管账号审批此撤回申请")
                    .withHintTarget("审批人");
        }
        log_.setApprovedBy(approvedBy);
        log_.setApprovedAt(LocalDateTime.now());
        log_.setStatus(ReportReversalLog.ReversalStatus.APPROVED);
        reversalLogRepo.save(log_);

        // 批准后自动执行撤回
        executeReversal(logId, log_.getFactoryId());
        log.info("[SP2] approveReversal logId={} approvedBy={}", logId, approvedBy);
    }

    @Override
    @Transactional
    public void rejectReversal(String factoryId, Long logId, Long approvedBy, String reason) {
        ReportReversalLog log_ = findReversalLogForUpdate(logId, factoryId);
        // 跨租户校验: 撤回日志须属于当前工厂 (防止越权驳回其它工厂的撤回申请)
        if (log_.getFactoryId() == null || !log_.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该撤回申请 / 该撤回申请不属于当前工厂")
                    .withHint("请确认撤回申请 ID 是否属于本工厂");
        }
        if (ReportReversalLog.ReversalStatus.PENDING != log_.getStatus()) {
            throw new BusinessException(409, String.format(
                    "撤回申请 %d 当前状态为 %s，无法拒绝", logId, log_.getStatus()))
                    .withCode("INVALID_STATUS");
        }
        Long submitter = log_.getSubmittedBy();
        log_.setApprovedBy(approvedBy);
        log_.setApprovedAt(LocalDateTime.now());
        log_.setStatus(ReportReversalLog.ReversalStatus.REJECTED);
        if (reason != null && !reason.isBlank()) {
            log_.setReason(log_.getReason() + " | 拒绝原因: " + reason);
        }
        reversalLogRepo.save(log_);
        log.info("[SP2] rejectReversal logId={} approvedBy={}", logId, approvedBy);

        // W6 #27: 驳回后通知申请人 (App 内通知)。fail-soft — 通知失败不回滚驳回事务
        // (reversal-log 已置 REJECTED 是核心结果, 通知只是 side-effect)。审批人自己驳回则不通知。
        if (submitter != null && !submitter.equals(approvedBy)) {
            try {
                String reasonText = (reason != null && !reason.isBlank()) ? reason : "未填写";
                notificationService.sendNotification(
                        log_.getFactoryId(),
                        submitter,
                        "报工撤回申请被驳回",
                        String.format("您对批次 %d 提交的报工撤回申请已被驳回。驳回原因: %s",
                                log_.getBatchId(), reasonText),
                        NotificationType.WARNING,
                        "REVERSAL",
                        String.valueOf(logId));
            } catch (Exception e) {
                log.warn("[SP2] rejectReversal 通知申请人失败 logId={} submitter={}", logId, submitter, e);
            }
        }
    }

    @Override
    public List<ReportReversalLog> listReversals(String factoryId, String status) {
        List<ReportReversalLog> logs;
        if (status == null || status.isBlank()) {
            // 返回所有状态 — 使用 no-status 方法, 避免传 null 给 enum 参数
            logs = reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId);
        } else {
            ReportReversalLog.ReversalStatus statusEnum;
            try {
                statusEnum = ReportReversalLog.ReversalStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "无效的状态值: " + status + "，合法值: PENDING/APPROVED/DONE/REJECTED");
            }
            logs = reversalLogRepo
                    .findByFactoryIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, statusEnum);
        }
        // BUG-4 修复: 批量加载用户姓名 (一次 IN query, 禁止 N+1)。
        // 收集所有非 null userId，去重后一次性 findAllById，构建 id→User map 填充 @Transient 姓名字段。
        if (!logs.isEmpty()) {
            List<Long> userIds = logs.stream()
                    .flatMap(l -> Stream.of(l.getSubmittedBy(), l.getApprovedBy()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!userIds.isEmpty()) {
                Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
                for (ReportReversalLog l : logs) {
                    if (l.getSubmittedBy() != null) {
                        User u = userMap.get(l.getSubmittedBy());
                        l.setSubmittedByName(u != null ? u.getFullName() : null);
                    }
                    if (l.getApprovedBy() != null) {
                        User u = userMap.get(l.getApprovedBy());
                        l.setApprovedByName(u != null ? u.getFullName() : null);
                    }
                }
            }
        }
        return logs;
    }

    // ==================== 私有辅助 ====================

    /** Approval/rejection/execution all enter through the same pessimistic row lock. */
    private ReportReversalLog findReversalLogForUpdate(Long logId, String factoryId) {
        return reversalLogRepo.findByIdAndFactoryIdForUpdate(logId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "撤回申请不存在: " + logId));
    }

    /**
     * 断点2 修复 (2026-06-11): 撤回时清回填的 {@code SalesOrderItem.costUnitPrice}.
     *
     * <p><b>为什么必须清</b>: {@link com.cretas.aims.event.listener.OrderCostBackfillListener}
     * 是 write-once (仅 costUnitPrice == null 时写). 撤回若不清此字段, 重报新成本事件
     * 因字段非 null 被跳过 → SO 永远挂着被撤回报工的旧成本, 不可自愈. 清 null 后,
     * 重报触发的 ProductionCostUpdatedEvent 会重新回填新成本 → 自愈链复活.
     *
     * <p><b>范围</b>: 仅清本批次 productTypeId 在对应计划全部关联 SO (sourceOrderIds,
     * 与断点1 回填同源) 的行项目 — 不误伤同 SO 其他产品的成本.
     *
     * <p><b>事务/异常</b>: 在 {@link #executeReversal} 的单一 @Transactional 内执行,
     * 不 try/catch 吞异常 (禁 fail-soft, 失败应整体回滚). repo 未注入 → 跳过 (向后兼容).
     *
     * @param factoryId 工厂ID
     * @param batchId   被撤回的生产批次ID (取其 productTypeId)
     * @param planId    撤回日志记录的计划ID (取其 sourceOrderIds)
     */
    private void clearBackfilledCostUnitPrice(String factoryId, Long batchId, String planId) {
        if (productionPlanRepository == null || salesOrderItemRepository == null) {
            log.debug("[SP2] clearBackfilledCostUnitPrice: repo 未注入, 跳过 batchId={}", batchId);
            return;
        }
        if (planId == null) {
            return;
        }

        // 批次 → productTypeId
        ProductionBatch batch = batchRepo.findByIdAndFactoryId(batchId, factoryId).orElse(null);
        if (batch == null || batch.getProductTypeId() == null) {
            log.debug("[SP2] clearBackfilledCostUnitPrice: 批次或 productTypeId 缺失, 跳过 batchId={}", batchId);
            return;
        }
        String productTypeId = batch.getProductTypeId();

        // 计划 → 关联 SO 列表 (与断点1 回填同源: 优先 sourceOrderIds, 兼容遗留主 SO)
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId).orElse(null);
        if (plan == null) {
            return;
        }
        List<String> targetOrderIds = new ArrayList<>();
        List<String> srcIds = plan.getSourceOrderIds();
        if (srcIds != null && !srcIds.isEmpty()) {
            for (String id : srcIds) {
                if (id != null && !targetOrderIds.contains(id)) {
                    targetOrderIds.add(id);
                }
            }
        }
        if (targetOrderIds.isEmpty() && plan.getSourceOrderId() != null) {
            targetOrderIds.add(plan.getSourceOrderId());
        }
        if (targetOrderIds.isEmpty()) {
            return;
        }

        int cleared = 0;
        for (String orderId : targetOrderIds) {
            List<SalesOrderItem> items = salesOrderItemRepository
                    .findBySalesOrderIdAndSalesOrderFactoryId(orderId, factoryId);
            for (SalesOrderItem item : items) {
                if (productTypeId.equals(item.getProductTypeId()) && item.getCostUnitPrice() != null) {
                    item.setCostUnitPrice(null);
                    salesOrderItemRepository.save(item);
                    cleared++;
                }
            }
        }
        if (cleared > 0) {
            log.info("[SP2] 撤回清 costUnitPrice → salesOrderIds={}, productTypeId={}, 共{}行 (自愈链待重报回填)",
                    targetOrderIds, productTypeId, cleared);
        }
    }

    /**
     * R3 修复 (2026-06-14): 撤回回扣结单消耗的原料 {@code usedQuantity} (+ 恢复被领用的半成品余量),
     * 并软删结单, 让库存归还且允许重新结单。
     *
     * <p><b>流程</b>: 撤回日志 planId → {@link ProductionSettlement} (按 factoryId+planId) →
     * 其 {@link ProductionSettlementConsumption} 行:
     * <ul>
     *   <li>非 SEMI_FINISHED 行: 悲观锁 {@link MaterialBatch} → {@code usedQuantity -= 领用量}
     *       (clamp ≥0 防脏数据为负) → usedQuantity 归 0 时若 status 为 USED_UP/DEPLETED 恢复 AVAILABLE。</li>
     *   <li>SEMI_FINISHED 行: 悲观锁 {@link SemiFinishedInventory} → {@code consumedQuantity -= 领用量},
     *       {@code availableQuantity += 领用量} (恢复被领走的量), 恢复 AVAILABLE。
     *       (否则软删结单后重新结单会二次扣减半成品 = 重复结算。不改 WipInventoryServiceImpl, 仅反冲 settlement 路径。)</li>
     * </ul>
     * 最后软删 settlement + 所有 consumption 行 ({@code @Where deleted_at IS NULL} 隐藏 →
     * 结单幂等检查 {@code findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull} 重新放行)。
     *
     * <p><b>事务/异常</b>: 在 {@link #executeReversal} 单一 @Transactional 内 (禁 fail-soft,
     * 失败整体回滚)。repo 未注入 → 跳过 (向后兼容)。无 settlement (报工未结单) → 无消耗可回扣, 直接返回。
     *
     * @param factoryId      工厂ID
     * @param planId         撤回日志的计划ID
     * @param reversalLogId  撤回日志ID (半成品「还回领用」流水的 sourceRef)
     * @param operatorId     记账人 (见 {@link #reversalOperatorId})
     */
    private boolean restoreSettlementConsumption(String factoryId, String planId,
                                                 Long reversalLogId, Long operatorId) {
        if (materialBatchRepository == null
                || productionSettlementRepository == null
                || productionSettlementConsumptionRepository == null) {
            if (planId != null) {
                throw settlementDependencyMissing();
            }
            log.debug("[SP2] restoreSettlementConsumption: repo 未注入, 跳过 planId={}", planId);
            return false;
        }
        if (planId == null) {
            return false;
        }

        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId)
                .orElse(null);
        if (settlement == null) {
            // 报工但未结单 → usedQuantity 未被结单路径增加, 无需回扣。
            log.debug("[SP2] restoreSettlementConsumption: 无结单记录, 跳过 planId={}", planId);
            return false;
        }

        List<ProductionSettlementConsumption> lines = productionSettlementConsumptionRepository
                .findBySettlementIdAndDeletedAtIsNull(settlement.getId());

        LocalDateTime now = LocalDateTime.now();
        int restoredMaterial = 0;
        int restoredSemi = 0;
        // fail-closed (修2): repo 已注入 (走到这里说明顶部未注入跳过分支没触发) → 每一笔应恢复的消耗
        //   都必须成功, 任一返回 false (批次缺失 / 工厂不匹配 / 数量异常) → 抛 BusinessException 整体回滚,
        //   绝不软删消耗行 / settlement (否则库存没还却把记录删了, reversal 置 DONE → 不可重跑, 静默丢库存)。
        //   软删放在全部恢复成功之后 (两阶段), 保证"还库存"与"删记录"原子一致。
        for (ProductionSettlementConsumption line : lines) {
            BigDecimal qty = line.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // 无可恢复量 — 正常跳过 (非失败)
            }
            if ("SEMI_FINISHED".equals(line.getSourceType())) {
                if (restoreSemiFinishedConsumption(factoryId, line.getSemiFinishedInventoryId(), qty,
                        reversalLogId, settlement.getId(), line.getId(), operatorId)) {
                    restoredSemi++;
                } else {
                    throw new BusinessException(409, String.format(
                            "撤回失败: 半成品消耗恢复失败 (sfiId=%s, qty=%s) — 库存未还, 已回滚, 请修正数据后重试",
                            line.getSemiFinishedInventoryId(), qty));
                }
            } else {
                if (restoreMaterialBatchConsumption(factoryId, line.getMaterialBatchId(), qty)) {
                    restoredMaterial++;
                } else {
                    throw new BusinessException(409, String.format(
                            "撤回失败: 原料批次消耗恢复失败 (materialBatchId=%s, qty=%s) — 库存未还, 已回滚, 请修正数据后重试",
                            line.getMaterialBatchId(), qty));
                }
            }
        }

        // 全部恢复成功后才软删消耗行 (防重新结单时重复回扣 / 重复统计)
        for (ProductionSettlementConsumption line : lines) {
            BigDecimal qty = line.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            line.setDeletedAt(now);
            productionSettlementConsumptionRepository.save(line);
        }

        // 软删结单本身 → 幂等检查放行, 允许重新录入结单
        settlement.setDeletedAt(now);
        productionSettlementRepository.save(settlement);

        log.info("[SP2] 撤回回扣结单消耗 planId={} settlementId={} 原料{}笔/半成品{}笔, settlement 已软删",
                planId, settlement.getId(), restoredMaterial, restoredSemi);
        return true;
    }

    /** Validate the settlement's plan/batch scope before any report or settlement mutation. */
    private SettlementReversalScope validateSettlementReversalScopeIfPresent(String factoryId,
                                                                               Long batchId,
                                                                               String planId) {
        if (planId == null) {
            return null;
        }
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdForUpdate(factoryId, planId)
                .orElse(null);
        if (settlement == null) {
            return null;
        }
        if (productionPlanRepository == null) {
            throw new BusinessException(409, "撤回失败: 生产计划仓储未配置")
                    .withCode("REVERSAL_SCOPE_INVALID");
        }
        if (!Objects.equals(factoryId, settlement.getFactoryId())
                || !Objects.equals(planId, settlement.getProductionPlanId())
                || settlement.getId() == null) {
            throw new BusinessException(409, "撤回失败: 结单不属于当前工厂或计划")
                    .withCode("REVERSAL_SCOPE_INVALID");
        }
        List<ProductionBatch> planBatches = batchRepo
                .findByFactoryIdAndProductionPlanId(factoryId, planId);
        List<ProductionBatch> validFormalBatches = planBatches == null ? List.of()
                : planBatches.stream()
                .filter(Objects::nonNull)
                .filter(batch -> batch.getStatus() != ProductionBatchStatus.CANCELLED)
                .filter(batch -> !"CLERK_WIP".equals(batch.getBatchType()))
                .toList();
        if (validFormalBatches.size() != 1
                || !Objects.equals(batchId, validFormalBatches.get(0).getId())) {
            throw new BusinessException(409, "撤回失败: 计划必须唯一对应一个可撤回正式批次")
                    .withCode("REVERSAL_SETTLEMENT_BATCH_SCOPE_INVALID");
        }
        ProductionBatch batch = batchRepo.findByIdAndFactoryIdForUpdate(batchId, factoryId).orElseThrow(() ->
                new BusinessException(409, "撤回失败: 批次不存在或不属于当前工厂")
                        .withCode("REVERSAL_SCOPE_INVALID"));
        if (!Objects.equals(factoryId, batch.getFactoryId())
                || !Objects.equals(planId, batch.getProductionPlanId())) {
            throw new BusinessException(409, "撤回失败: 批次工厂或计划关联不匹配")
                    .withCode("REVERSAL_SCOPE_INVALID");
        }
        ProductionPlan plan = productionPlanRepository.findByIdForUpdate(planId).orElseThrow(() ->
                new BusinessException(409, "撤回失败: 生产计划不存在或已删除")
                        .withCode("REVERSAL_SCOPE_INVALID"));
        if (!Objects.equals(factoryId, plan.getFactoryId())) {
            throw new BusinessException(409, "撤回失败: 生产计划不属于当前工厂")
                    .withCode("REVERSAL_SCOPE_INVALID");
        }
        return new SettlementReversalScope(batch, plan);
    }

    private void requireSettlementDependencies(String planId) {
        if (planId != null
                && (materialBatchRepository == null
                || productionSettlementRepository == null
                || productionSettlementConsumptionRepository == null
                || productionPlanRepository == null)) {
            throw settlementDependencyMissing();
        }
    }

    private BusinessException settlementDependencyMissing() {
        return new BusinessException(409, "撤回失败: 结单恢复依赖未配置")
                .withCode("REVERSAL_SETTLEMENT_DEPENDENCY_MISSING");
    }

    /**
     * Reopen only the plan/batch that were completed by the settlement being reversed.
     * Derived completion fields are cleared; immutable workflow/BOM/lineage fields are untouched.
     */
    private void reopenPlanAndBatchAfterSettlementReversal(SettlementReversalScope scope) {
        ProductionBatch batch = scope.batch();
        ProductionPlan plan = scope.plan();
        if (ProductionPlanStatus.COMPLETED == plan.getStatus()) {
            plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
            plan.setActualQuantity(null);
            plan.setEndTime(null);
            productionPlanRepository.save(plan);
        }

        // Only COMPLETED is a derived terminal state. An already-running batch may contain
        // legitimate partial output, so preserve its status, quantities, timestamps, and
        // immutable workflow/BOM/lineage fields.
        if (ProductionBatchStatus.COMPLETED == batch.getStatus()) {
            batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
            batch.setActualQuantity(null);
            batch.setGoodQuantity(null);
            batch.setDefectQuantity(null);
            batch.setEndTime(null);
            batchRepo.save(batch);
        }
    }

    private record SettlementReversalScope(ProductionBatch batch, ProductionPlan plan) {}

    private Set<Long> preflightSettlementSemiFinished(String factoryId, String planId,
                                                       Set<Long> reportSfiIds) {
        if (productionSettlementRepository == null || productionSettlementConsumptionRepository == null
                || planId == null) return Set.of();
        ProductionSettlement settlement = productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId).orElse(null);
        if (settlement == null) return Set.of();
        if (!Objects.equals(factoryId, settlement.getFactoryId())
                || !Objects.equals(planId, settlement.getProductionPlanId())
                || settlement.getId() == null) {
            throw new BusinessException(409, "撤回失败: 结单不属于当前工厂或计划")
                    .withCode("WIP_SCOPE_INVALID");
        }
        List<ProductionSettlementConsumption> lines = productionSettlementConsumptionRepository
                .findBySettlementIdAndDeletedAtIsNull(settlement.getId());
        if (lines == null) throw new BusinessException(409, "撤回失败: 无法读取结单消耗").withCode("WIP_LEDGER_INVALID");
        Set<Long> ids = new TreeSet<>();
        Map<Long, BigDecimal> requestedBySfi = new HashMap<>();
        Map<Long, List<ProductionSettlementConsumption>> linesBySfi = new HashMap<>();
        for (ProductionSettlementConsumption line : lines) {
            if (line == null || line.getId() == null
                    || !Objects.equals(settlement.getId(), line.getSettlementId())
                    || !Objects.equals(factoryId, line.getFactoryId())
                    || !Objects.equals(planId, line.getProductionPlanId())) {
                throw new BusinessException(409, "撤回失败: 结单消耗行不属于当前工厂、计划或结单")
                        .withCode("WIP_SCOPE_INVALID");
            }
            if (!"SEMI_FINISHED".equals(line.getSourceType())) {
                continue;
            }
            if (line.getQuantity() == null || line.getQuantity().signum() <= 0
                    || line.getSemiFinishedInventoryId() == null) {
                throw new BusinessException(409, "撤回失败: 结单半成品缺少库存行")
                        .withCode("WIP_LEDGER_INVALID");
            }
            Long sfiId = line.getSemiFinishedInventoryId();
            ids.add(sfiId);
            if (reportSfiIds != null && reportSfiIds.contains(sfiId)) {
                throw new BusinessException(409, "撤回失败: 同一半成品同时参与报工与结单恢复")
                        .withCode("WIP_SCOPE_INVALID");
            }
            requestedBySfi.merge(sfiId, line.getQuantity(), BigDecimal::add);
            linesBySfi.computeIfAbsent(sfiId, ignored -> new ArrayList<>()).add(line);
        }
        for (Long id : ids) {
            if (id == null) throw new BusinessException(409, "撤回失败: 结单半成品缺少库存行")
                    .withCode("WIP_NOT_FOUND");
            SemiFinishedInventory sfi = wipRepo.findByIdForUpdate(id)
                    .orElseThrow(() -> new BusinessException(409, "撤回失败: 结单半成品库存不存在: " + id)
                            .withCode("WIP_NOT_FOUND"));
            if (!Objects.equals(factoryId, sfi.getFactoryId())) {
                throw new BusinessException(403, "撤回失败: 结单半成品跨工厂")
                        .withCode("WIP_SCOPE_INVALID");
            }
            if (Optional.ofNullable(sfi.getAdjustmentQuantity()).orElse(BigDecimal.ZERO).signum() != 0) {
                throw new BusinessException(409, "撤回失败: 结单半成品存在未归零盘点调整")
                        .withCode("WIP_ADJUSTMENT_PRESENT");
            }

            BigDecimal requested = requestedBySfi.get(id);
            BigDecimal consumed = Optional.ofNullable(sfi.getConsumedQuantity()).orElse(BigDecimal.ZERO);
            if (consumed.compareTo(requested) < 0) {
                throw new BusinessException(409, "撤回失败: 结单半成品消耗量超过库存已领量，无法证明完整反冲")
                        .withCode("WIP_LEDGER_INCOMPLETE");
            }

            List<SemiFinishedInventoryTransaction> ledger = txnRepo
                    .findBySemiFinishedIdOrderByCreatedAtAsc(id);
            if (ledger == null) {
                throw new BusinessException(409, "撤回失败: 无法读取结单半成品流水")
                        .withCode("WIP_LEDGER_INVALID");
            }
            String currentSettlementPrefix = WipLedgerEntries.SETTLEMENT_CONSUMPTION_REF_PREFIX
                    + settlement.getId() + ":";
            Map<String, BigDecimal> expectedQtyByRef = new HashMap<>();
            for (ProductionSettlementConsumption line : linesBySfi.getOrDefault(id, List.of())) {
                String expectedRef = currentSettlementPrefix + line.getId();
                if (expectedQtyByRef.put(expectedRef, line.getQuantity().negate()) != null) {
                    throw new BusinessException(409, "撤回失败: 结单半成品消耗行 ID 重复")
                            .withCode("WIP_LEDGER_INVALID");
                }
            }
            Map<String, Integer> matchingByRef = new HashMap<>();
            for (SemiFinishedInventoryTransaction txn : ledger) {
                if (txn == null || !Objects.equals(factoryId, txn.getFactoryId())
                        || !Objects.equals(id, txn.getSemiFinishedId())) {
                    throw new BusinessException(409, "撤回失败: 结单半成品流水跨工厂或库存行")
                            .withCode("WIP_SCOPE_INVALID");
                }
                String sourceRef = txn.getSourceRef();
                // OUT rows belonging to other settlements are valid history for this SFI and
                // must not be mistaken for an orphan.  Only the current settlement namespace
                // participates in this one-to-one proof.
                if (sourceRef != null && sourceRef.startsWith(currentSettlementPrefix)) {
                    BigDecimal expectedQuantity = expectedQtyByRef.get(sourceRef);
                    if (expectedQuantity == null) {
                        throw new BusinessException(409, "撤回失败: 结单半成品存在孤立 OUT 流水")
                                .withCode("WIP_LEDGER_INVALID");
                    }
                    if (!SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())
                            || !SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME
                            .equals(txn.getSourceType())
                            || txn.getQuantity() == null
                            || txn.getQuantity().compareTo(expectedQuantity) != 0) {
                        throw new BusinessException(409, "撤回失败: 结单半成品存在孤立、部分或方向错误的 OUT 流水")
                                .withCode("WIP_LEDGER_INVALID");
                    }
                    int matching = matchingByRef.merge(sourceRef, 1, Integer::sum);
                    if (matching > 1) {
                        throw new BusinessException(409, "撤回失败: 结单半成品存在重复 OUT 配对")
                                .withCode("WIP_LEDGER_INVALID");
                    }
                }
            }
            for (String expectedRef : expectedQtyByRef.keySet()) {
            if (matchingByRef.getOrDefault(expectedRef, 0) != 1) {
                    throw new BusinessException(409, "撤回失败: 结单半成品消耗与 OUT 流水不是一一配对")
                            .withCode("WIP_LEDGER_INCOMPLETE");
                }
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * R3: 回扣单个原料批次的 {@code usedQuantity} (clamp ≥0), usedQuantity 归 0 时恢复可用状态。
     *
     * @return true = 已回扣并保存; false = 批次不存在或入参不合法 (跳过)
     */
    private boolean restoreMaterialBatchConsumption(String factoryId, String materialBatchId, BigDecimal qty) {
        if (materialBatchId == null) {
            log.warn("[SP2] restoreMaterialBatchConsumption: 消耗行缺 materialBatchId, 跳过 (qty={})", qty);
            return false;
        }
        MaterialBatch batch = materialBatchRepository
                .findByIdAndFactoryIdForUpdate(materialBatchId, factoryId)
                .orElse(null);
        if (batch == null) {
            log.warn("[SP2] restoreMaterialBatchConsumption: 原料批次不存在 id={} factoryId={}, 跳过",
                    materialBatchId, factoryId);
            return false;
        }
        BigDecimal used = batch.getUsedQuantity() != null ? batch.getUsedQuantity() : BigDecimal.ZERO;
        BigDecimal newUsed = used.subtract(qty);
        if (newUsed.compareTo(BigDecimal.ZERO) < 0) {
            // 脏数据防御: 回扣量超过已用量 → clamp 到 0 (不让 usedQuantity 为负, entity @PositiveOrZero)
            log.warn("[SP2] restoreMaterialBatchConsumption: 回扣量 {} 超过已用 {} (batchId={}), clamp 到 0",
                    qty, used, materialBatchId);
            newUsed = BigDecimal.ZERO;
        }
        batch.setUsedQuantity(newUsed);
        // usedQuantity 归 0 且原状态是消耗终态 → 恢复 AVAILABLE (撤回后批次重新可用)。
        // 其他状态 (EXPIRED/SCRAPPED/DEFECTIVE/RESERVED 等独立决策) 不动。
        if (newUsed.compareTo(BigDecimal.ZERO) == 0
                && (MaterialBatchStatus.USED_UP == batch.getStatus()
                    || MaterialBatchStatus.DEPLETED == batch.getStatus())) {
            batch.setStatus(MaterialBatchStatus.AVAILABLE);
        }
        materialBatchRepository.save(batch);
        return true;
    }

    /**
     * R3: 反冲被结单领用的半成品 (恢复 {@code availableQuantity}, 减 {@code consumedQuantity}),
     * 镜像 {@code ProductionPlanServiceImpl.postSemiFinishedConsumption} 的逆操作。
     * 防止软删结单后重新结单二次扣减半成品 (重复结算)。
     *
     * <h2>🔴 补正量必须留下一条 REVERSE 流水 (2026-08-18)</h2>
     *
     * <p>现在这里更新库存行并写入与结单消耗行一一配对的 REVERSE 流水；正向
     * {@code ProductionPlanServiceImpl.postSemiFinishedConsumption} 写入对应 OUT。
     * 撤回 preflight 会在任何 mutation 前证明这两个账本事实完整且数量一致。
     *
     * <p><b>方向是正量 REVERSE，不是 OUT 也不是负量 REVERSE</b>：本方法做的事是
     * {@code consumedQuantity -= qty} —— 那是「<b>还回领用</b>」，即 OUT 的逆。
     * 按 {@code ReverseSignSemanticsContractTest} 钉住的口径，REVERSE 靠符号分两种语义，
     * 「还回领用」是<b>正量</b> (抵扣 {@code consumedOut}，⛔ 不进 {@code producedQuantity})。
     * 写成 OUT 会被读成「又领走一笔」(方向反了)；写成负量 REVERSE 会被
     * {@code replayMovingAverage} 冲减净产出，把一次<b>归还</b>记成一次<b>减产</b>。
     *
     * <p><b>记的是已证明的反冲量</b>：撤回 preflight 先证明结单消耗量不超过已领量，
     * 且与正向 OUT 流水一一配对；因此这里不使用 clamp 掩盖部分历史。
     *
     * @param factoryId              工厂ID
     * @param semiFinishedInventoryId 半成品库存行ID
     * @param qty                    消耗行上记的领用量
     * @param reversalLogId          撤回日志ID (流水的 sourceRef)
     * @param settlementId           结单ID (流水的 sourceRef)
     * @param consumptionId          结单消耗行ID (流水的 sourceRef)
     * @param operatorId             记账人 (见 {@link #reversalOperatorId})
     * @return true = 已恢复并保存; false = 库存不存在或入参不合法 (跳过)
     */
    private boolean restoreSemiFinishedConsumption(String factoryId, Long semiFinishedInventoryId, BigDecimal qty,
                                                   Long reversalLogId, String settlementId,
                                                   Long consumptionId, Long operatorId) {
        if (semiFinishedInventoryId == null) {
            log.warn("[SP2] restoreSemiFinishedConsumption: 消耗行缺 semiFinishedInventoryId, 跳过 (qty={})", qty);
            return false;
        }
        SemiFinishedInventory sfi = wipRepo.findByIdForUpdate(semiFinishedInventoryId).orElse(null);
        if (sfi == null) {
            log.warn("[SP2] restoreSemiFinishedConsumption: 半成品库存不存在 id={}, 跳过", semiFinishedInventoryId);
            return false;
        }
        if (!Objects.equals(factoryId, sfi.getFactoryId())) {
            throw new BusinessException(403, "撤回失败: 结单半成品跨工厂")
                    .withCode("WIP_SCOPE_INVALID");
        }
        BigDecimal consumed = sfi.getConsumedQuantity() != null ? sfi.getConsumedQuantity() : BigDecimal.ZERO;
        if (qty == null || qty.signum() <= 0 || consumed.compareTo(qty) < 0) {
            throw new BusinessException(409, "撤回失败: 结单半成品消耗量超过已领量，无法完整反冲")
                    .withCode("WIP_LEDGER_INCOMPLETE");
        }
        BigDecimal newConsumed = consumed.subtract(qty);
        BigDecimal available = sfi.getAvailableQuantity() != null ? sfi.getAvailableQuantity() : BigDecimal.ZERO;
        // Preflight proves qty <= consumed, therefore the persisted reverse row is exactly
        // paired with the settlement consumption line; no clamp may hide partial history.
        BigDecimal restoredConsumption = qty;
        sfi.setConsumedQuantity(newConsumed);
        sfi.setAvailableQuantity(available.add(restoredConsumption));
        if (sfi.getAvailableQuantity().compareTo(BigDecimal.ZERO) > 0) {
            sfi.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        }
        wipRepo.save(sfi);

        // 🔴 流水在【改完之后】构造 —— settlementRestoreRow 直接读改完的 availableQuantity,
        //    与 consumptionRow(读扣减前自己推) 口径相反, 混用会算出错的 balanceAfter。
        //    正量 = 还回领用 (OUT 的逆); preflight 已证明这里一定产生一笔配对反冲。
        SemiFinishedInventoryTransaction restoreTxn = WipLedgerEntries.settlementRestoreRow(
                factoryId, sfi, restoredConsumption, reversalLogId, settlementId, consumptionId, operatorId);
        if (restoreTxn != null) {
            txnRepo.save(restoreTxn);
            log.info("[SP2] 撤回还回结单领用的半成品: sfiId={} 还回{} (消耗行记 {}), available={} → 已记 REVERSE 流水",
                    semiFinishedInventoryId, restoredConsumption, qty, sfi.getAvailableQuantity());
        } else {
            log.warn("[SP2] restoreSemiFinishedConsumption: sfiId={} 实际反冲量为 0 (已领 {} / 请求还 {}), "
                            + "不写流水 (⛔ 不凭空造账)",
                    semiFinishedInventoryId, consumed, qty);
        }
        return true;
    }

    /**
     * SP12 快速撤回资格检查。
     *
     * <p>返回 null 表示满足快速撤回全部条件 (提交人本人 + 报工在时间窗口内)。
     * 返回非 null 字符串表示不满足快速撤回，内容是诚实的拒绝原因 (展示给操作员)。
     *
     * <p><b>安全边界</b>: 本方法只判断"是否需要人审"，不影响 G1/G2 守卫。
     * 调用前 G1/G2 守卫必须已通过。
     *
     * @param submittedBy 本次提交人 userId
     * @param reports     该批次的报工列表 (非空)
     * @return null = 快速撤回放行; 非 null = 原因说明，退回完整审批
     */
    public String resolveFastPathDeniedReason(Long submittedBy, List<ProductionReport> reports) {
        // 条件1: 本人提交。submittedBy 为 null 时 (理论上不应发生) 退回审批
        if (submittedBy == null) {
            return "提交人未知，需要主管审批";
        }

        // 条件2: 最新报工必须在快速撤回时间窗口内
        // 取所有报工中 createdAt 最晚的，作为"最后一笔报工时间"判断
        LocalDateTime latestReportTime = reports.stream()
                .map(r -> r.getCreatedAt())
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        if (latestReportTime == null) {
            // 无 createdAt 信息 → 无法判断时间窗口，保守退回审批
            return "报工时间信息缺失，需要主管审批";
        }

        long minutesSinceReport = Duration.between(latestReportTime, LocalDateTime.now()).toMinutes();
        if (minutesSinceReport > FAST_PATH_WINDOW_MINUTES) {
            return String.format("报工提交已超 %d 分钟 (当前已过 %d 分钟)，超出快速撤回时限，需要主管审批",
                    FAST_PATH_WINDOW_MINUTES, minutesSinceReport);
        }

        // 条件3: 所有报工的 workerId 须等于 submittedBy (本人只能快速撤回自己的报工)
        boolean allBySubmitter = reports.stream().allMatch(r ->
                submittedBy.equals(r.getWorkerId()));
        if (!allBySubmitter) {
            return "批次含有其他操作员的报工记录，需要主管审批";
        }

        return null; // 全部条件满足 → 快速撤回放行
    }

    /**
     * 移动均价回放: 查询该 SFI 所有流水 (按 createdAt ASC), 重算 unitCost / producedQuantity /
     * availableQuantity。
     *
     * <p><b>三个量的口径</b>:
     * <ul>
     *   <li>{@code producedQuantity} (净产出) = ΣIN + Σ(<b>负量</b>REVERSE)。
     *       unitCost / accumulatedCost 只由这两类加权 (OUT 不影响单价)。</li>
     *   <li>{@code availableQuantity} (可领余量) = producedQuantity − ΣconsumedOut (R5 修复)。
     *       <b>OUT 已被下游领用</b>, 必须从可用量扣减, 否则把已发出的量算回可用 → 幻影库存。
     *       OUT 流水 quantity 存负量 (见 {@code WipInventoryServiceImpl} {@code qty.negate()}),
     *       这里取其绝对值累加到 consumedOut。</li>
     *   <li>{@code consumedOut} = Σ|OUT| − Σ(<b>正量</b>REVERSE) (clamp ≥ 0)。见下方「REVERSE 有两种」。</li>
     * </ul>
     * 若净产出 ≤ 0 → producedQuantity = 0, availableQuantity = 0, unitCost = null, status = DEPLETED。
     *
     * <h2>🔴 REVERSE 是<b>两种</b>语义共用的一个 txn_type, 靠<b>符号</b>区分 (2026-08-18 取证后修)</h2>
     *
     * <p>实体上 {@code TxnType.REVERSE} 的注释写的是「撤回冲销 (REVERSE: <b>负数量</b>)」——
     * 那句话<b>已经不成立</b>。全仓 5 个写 REVERSE 的地方, 两种符号各占一半:
     *
     * <table border="1">
     *   <caption>REVERSE 行的两种语义</caption>
     *   <tr><th>符号</th><th>语义</th><th>写它的地方</th><th>对净产出的影响</th></tr>
     *   <tr><td><b>负量</b></td><td>退回产出 (IN 的逆)</td>
     *       <td>{@link #executeReversal} ②、{@code WipInventoryServiceImpl.reverseClerkOutput}、
     *           {@code reverseReportPosting} ①</td>
     *       <td>减少 producedQty ✅</td></tr>
     *   <tr><td><b>正量</b></td><td>还回领用 (OUT 的逆)</td>
     *       <td>{@code WipInventoryServiceImpl.restoreClerkSemi}、{@code reverseReportPosting} ②</td>
     *       <td><b>抵扣 consumedOut</b>, ⛔ 与产出无关</td></tr>
     * </table>
     *
     * <p><b>修之前这里是 {@code producedQty = producedQty.add(q)} 一律相加、不看符号</b> ⇒
     * 正量 REVERSE <b>虚增净产出</b>。两个后果, 第二个更阴:
     * <ol>
     *   <li>净产出被写回库存行时偏大, 且 {@code weightedCost} 同步被虚增的量稀释 → 单价失真。</li>
     *   <li>回写后 {@code producedQuantity} 大于 ΣIN ⇒ <b>下一次</b>回放会撞上本方法开头那道
     *       {@code WIP_LEDGER_INCOMPLETE} 闸 (它比的正是「账面 produced vs ΣIN」) ⇒
     *       这条库存行被<b>永久锁死</b>, 之后任何撤回都 409, 而根因是我们自己算出来的数。</li>
     * </ol>
     *
     * <p><b>取证 (F006 生产库, 2026-08-18 只读)</b>: {@code semi_finished_inventory_transactions}
     * 全表 8 行, REVERSE 4 行<b>全部是负量</b> ⇒ 正量 REVERSE 目前 <b>0 行</b>, 这条缺陷<b>尚未在数据上引爆</b>。
     * ⚠️ 但它<b>已经可达</b>: {@code reverseReportPosting} ② 写的正量 REVERSE 落在<b>上道</b>库存行上,
     * 而那条行自己是有 report 关联的 IN 流水的 (PR#2870 起 FINISHED/legacy 路径也记 IN 了) ⇒
     * 只要那批报工之后被整单撤回, 回放就会读到这条正量 REVERSE。
     * 「0 行」是<b>还没发生</b>, 不是<b>不会发生</b> —— 所以修在它引爆之前。
     */
    private void replayMovingAverage(Long sfiId, String factoryId, LocalDateTime asOf) {
        // executeReversal already locked every involved SFI in stable id order before mutation.
        SemiFinishedInventory sfi = wipRepo.findByIdForUpdate(sfiId)
                .orElse(null);
        if (sfi == null) {
            log.warn("[SP2] replayMovingAverage: sfiId={} not found, skip", sfiId);
            return;
        }
        if (!factoryId.equals(sfi.getFactoryId())) {
            throw new BusinessException(403, "半成品库存工厂不匹配: " + sfiId);
        }

        List<SemiFinishedInventoryTransaction> allTxns =
                txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId);

        // producedQty = ΣIN + Σ(负量 REVERSE) (净产出); weightedCost 只由这两类加权。
        //   ⚠️ 2026-08-18 订正: 原来这里写的是「ΣIN + ΣREVERSE」并且代码也真的一律相加 ——
        //   正量 REVERSE 是「还回领用」, 算进产出就是把同一批货记两次生产。见方法 javadoc 那张表。
        // consumedOut = Σ|OUT| (下游已领用, R5: 必须从 availableQuantity 扣减)。
        // hasUnknownCost: 存在"有数量但 unitCostAtTxn 未知"的 IN/REVERSE 流水 → 成本不可信,
        //   honest-null: 不把未知量当 ¥0 加权稀释, 最终 unitCost/accumulatedCost 置 null (修1)。
        BigDecimal producedQty = BigDecimal.ZERO;
        BigDecimal weightedCost = BigDecimal.ZERO;
        BigDecimal consumedOut = BigDecimal.ZERO;
        // 本行历史上一共记过多少入库量 (ΣIN)。用来判「流水账解释得了当前余额吗」, 见下方守卫。
        BigDecimal ledgerInTotal = BigDecimal.ZERO;
        // 正量 REVERSE = 还回领用 (OUT 的逆) 的累计量 —— 抵扣 consumedOut, ⛔ 不进 producedQty。
        BigDecimal restoredConsumption = BigDecimal.ZERO;
        boolean hasUnknownCost = false;

        for (SemiFinishedInventoryTransaction txn : allTxns) {
            if (SemiFinishedInventoryTransaction.TxnType.IN.equals(txn.getTxnType())) {
                BigDecimal q = txn.getQuantity();
                if (q != null && q.compareTo(BigDecimal.ZERO) > 0) {
                    ledgerInTotal = ledgerInTotal.add(q);
                }
                if (q != null && q.compareTo(BigDecimal.ZERO) > 0 && txn.getUnitCostAtTxn() != null) {
                    producedQty = producedQty.add(q);
                    weightedCost = weightedCost.add(q.multiply(txn.getUnitCostAtTxn()));
                } else if (q != null && q.compareTo(BigDecimal.ZERO) > 0) {
                    // 有正数量但缺成本 — 数量计入净产出, 但标记成本未知 (不当 ¥0 稀释)
                    producedQty = producedQty.add(q);
                    hasUnknownCost = true;
                }
            } else if (SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(txn.getTxnType())) {
                // 🔴 REVERSE 必须按【符号】分流 —— 见方法 javadoc「REVERSE 是两种语义共用一个 txn_type」。
                //    ⛔ 不要退回成「一律 producedQty.add(q)」: 那会把「还回领用」算成「又产出了一批」。
                BigDecimal q = txn.getQuantity();
                if (q == null || q.signum() == 0) {
                    continue; // 0 量冲销不影响任何口径
                }
                if (q.signum() < 0) {
                    // ① 负量 = 退回产出 (IN 的逆) → 冲减净产出, 参与单价加权 (与 IN 同口径, 反向)
                    producedQty = producedQty.add(q); // 负数相加 = 减
                    if (txn.getUnitCostAtTxn() != null) {
                        weightedCost = weightedCost.add(q.multiply(txn.getUnitCostAtTxn()));
                    } else {
                        // 有 REVERSE 数量但缺成本 — 标记成本未知 (加权口径不可信)
                        hasUnknownCost = true;
                    }
                } else {
                    // ② 正量 = 还回领用 (OUT 的逆) → 只抵扣已领用量。
                    //    ⛔ 既不加进 producedQty (那批货从来没有被再生产一次),
                    //    ⛔ 也不进 weightedCost (它不是一笔入库, 没有自己的入库成本)。
                    restoredConsumption = restoredConsumption.add(q);
                }
            } else if (SemiFinishedInventoryTransaction.TxnType.OUT.equals(txn.getTxnType())) {
                // R5 修复: OUT 已被下游领用 — 计入 consumedOut, 从 availableQuantity 扣减。
                // OUT quantity 存负量 (WipInventoryServiceImpl qty.negate()), 取绝对值累加。
                // OUT 不影响 unitCost (单价只由 IN/REVERSE 加权, 这点现状对, 保留)。
                BigDecimal q = txn.getQuantity();
                if (q != null) {
                    consumedOut = consumedOut.add(q.abs());
                }
            }
        }

        // 正量 REVERSE 抵扣已领用量 (clamp ≥ 0)。
        // clamp 的理由: 还回量超过历史 OUT 只可能是脏数据 (还比领的多), 让 consumedOut 变负会把
        // availableQuantity 抬到净产出之上 = 凭空多出可领的货。宁可 clamp 并留 WARN, ⛔ 不产幻影库存。
        if (restoredConsumption.signum() > 0) {
            BigDecimal net = consumedOut.subtract(restoredConsumption);
            if (net.signum() < 0) {
                log.warn("[SP2] replayMovingAverage: sfiId={} 正量 REVERSE 合计 {} 超过 Σ|OUT| {} — "
                                + "clamp 到 0 (还回量比领用量还多, 数据异常)",
                        sfiId, restoredConsumption, consumedOut);
                net = BigDecimal.ZERO;
            }
            consumedOut = net;
        }
        consumedOut = consumedOut.max(BigDecimal.ZERO);
        sfi.setConsumedQuantity(consumedOut);

        // 🔴 禁止降级 (2026-08-18): 本方法把「ΣIN + Σ(负量 REVERSE)」当权威净产出【回写】库存行 ——
        //    这只有在「每一笔入库都记过流水」时才成立。实测 F006 生产库并非如此:
        //    全表 8 行流水 IN 为 0 行, 而 4 行库存分别挂着 2.00 / 240.00 / 300.00 / 10.00 的
        //    produced_quantity。在那种行上跑本方法, ΣIN=0 ⇒ producedQty ≤ 0 ⇒ 下面整段把
        //    produced / available 清零并置 DEPLETED —— 把「我没有流水证据」写成了「余额是 0」,
        //    真实库存被抹掉, 而且不报错。
        //
        //    判据: 账面 produced 超过历史 ΣIN ⇒ 有入库从来没记过流水 ⇒ 流水账解释不了这行余额,
        //    此时<b>拒绝回写</b>并整体回滚, 绝不用 0 糊过去。
        //    ⚠️ 反向不成立: 正常冲销过的行 produced 会小于 ΣIN (被 REVERSE 扣过), 不触发。
        BigDecimal producedOnRecord = sfi.getProducedQuantity() != null
                ? sfi.getProducedQuantity() : BigDecimal.ZERO;
        if (producedOnRecord.compareTo(ledgerInTotal) > 0) {
            throw new BusinessException(409, String.format(
                    "撤回失败: 半成品 %s 账面产出 %s, 而流水账里只记过 %s 的入库 —— "
                            + "差额 %s 没有入库流水可冲销, 强行重算会把这批库存抹平",
                    sfi.getIntermediateBatchNo(),
                    producedOnRecord.stripTrailingZeros().toPlainString(),
                    ledgerInTotal.stripTrailingZeros().toPlainString(),
                    producedOnRecord.subtract(ledgerInTotal).stripTrailingZeros().toPlainString()))
                    .withCode("WIP_LEDGER_INCOMPLETE")
                    .withHint("这批半成品是流水账补全之前入的库。请先用「半成品盘点」把该批次余额"
                            + "校正到实际值 (盘点会补一条 ADJUST 流水), 再重新提交撤回")
                    .withSeverity("BLOCKING")
                    .withHintTarget(sfi.getIntermediateBatchNo());
        }

        if (producedQty.compareTo(BigDecimal.ZERO) <= 0) {
            // 无剩余有效产出
            sfi.setProducedQuantity(BigDecimal.ZERO);  // BUG-R1 fix: IN 净额归零
            sfi.setAvailableQuantity(BigDecimal.ZERO);
            sfi.setUnitCost(null);
            sfi.setAccumulatedCost(null);
            sfi.setStatus(SemiFinishedInventory.Status.DEPLETED);
        } else {
            sfi.setProducedQuantity(producedQty);  // BUG-R1 fix: 更新 IN 净额
            // R5 修复: 可用 = 净产出 − 已领用 OUT (clamp ≥0)。旧 bug 直接用净产出, 忽略 OUT → 幻影库存。
            // 盘点修正量 (default 0) 纳入 (禁止 reversal 重算覆盖盘点修正; produced/成本不动)。
            BigDecimal sfiAdj = sfi.getAdjustmentQuantity() != null ? sfi.getAdjustmentQuantity() : BigDecimal.ZERO;
            BigDecimal available = producedQty.subtract(consumedOut).add(sfiAdj).max(BigDecimal.ZERO);
            sfi.setAvailableQuantity(available);
            // 修1 (honest-null 成本传导): 只要存在未知成本的产出量 → 成本不可信, 诚实置 null
            //   (不把未知量当 ¥0 稀释加权均价)。否则按已知成本算加权均价。
            //   #844 回归修复: 只用 hasUnknownCost 区分, 不再加 `weightedCost > 0` 守卫。
            //   旧守卫把"全已知但恰好 0 成本"(weightedCost==0, !hasUnknownCost) 误推进 else
            //   清成 null, 混淆了"未知成本→null (对)"与"已知 0 成本→应是 0"。
            //   producedQty>0 在此分支已由上面保证, divide 分母安全; weightedCost=0 → 0.0000 合法。
            if (!hasUnknownCost) {
                BigDecimal newUnitCost = weightedCost.divide(producedQty, 4, RoundingMode.HALF_UP);
                sfi.setUnitCost(newUnitCost);
                sfi.setAccumulatedCost(weightedCost.setScale(2, RoundingMode.HALF_UP));
            } else {
                // 仅未知成本才诚实置 null (成本不可信)。
                sfi.setUnitCost(null);
                sfi.setAccumulatedCost(null);
            }
            // status 反映可领余量: 还有余量 AVAILABLE, 全部领走 DEPLETED。
            sfi.setStatus(available.compareTo(BigDecimal.ZERO) > 0
                    ? SemiFinishedInventory.Status.AVAILABLE
                    : SemiFinishedInventory.Status.DEPLETED);
        }
        wipRepo.save(sfi);
    }
}
