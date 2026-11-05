package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.service.workprocess.WorkflowStepUnlockGuard;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import com.cretas.aims.service.yield.impl.PendingInterimFeedServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S315-01: 返工计划的来源半成品怎么解锁工序、怎么记账。
 *
 * <p>解锁走真的 {@link WorkflowStepUnlockGuard}, 记账走真的 {@link WipInventoryServiceImpl} +
 * {@link SecondaryPlanFeedService}, 只有仓储层是桩, 流水存在内存里, 余额和流水都是真算出来的。
 *
 * <p>场景取自验收: 工艺图「腌制 → 半成品 → 返工加工」, 来源半成品 1.0kg, 返工计划用 0.6kg。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecondaryReworkFeedContractTest {

    private static final String F = "F006";
    private static final String PLAN_ID = "SEC-PLAN";
    private static final Long WIP_ID = 403L;
    private static final String WIP_NO = "CLK-SEMI-c58a5e65-672c41b4";
    private static final String SEMI_SKU = "SEMI-1";
    private static final Long INSTANCE = 900L;
    private static final Long REWORK_BATCH = 500L;
    private static final Long SOURCE_BATCH = 77L;

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private IntermediateOwnershipGuard ownershipGuard;
    @InjectMocks private WipInventoryServiceImpl wipService;

    @Mock private ProductionPlanRepository planRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private WorkflowTaskPortRepository portRepo;
    @Mock private ProcessSheetRowRepository rowRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<SemiFinishedInventoryTransaction> ledger = new ArrayList<>();
    private final List<WorkflowTaskPort> ports = new ArrayList<>();
    private final List<ProductionReport> reports = new ArrayList<>();
    private final List<ProcessSheetRow> rows = new ArrayList<>();

    private SecondaryPlanFeedService feed;
    private WorkflowStepUnlockGuard guard;
    private ProductionPlan plan;
    private SemiFinishedInventory wip;
    private WorkProcessTask cure;
    private WorkProcessTask rework;

    @BeforeEach
    void setUp() {
        feed = new SecondaryPlanFeedService(planRepo, batchRepo, wipRepo, txnRepo, taskRepo, portRepo, rowRepo,
                reportRepo, wipService, mapper);
        ReflectionTestUtils.setField(wipService, "secondaryPlanFeedService", feed);
        guard = new WorkflowStepUnlockGuard(taskRepo, portRepo, workProcessRepo, batchRepo, rowRepo, reportRepo,
                mapper, feed);

        wip = new SemiFinishedInventory();
        wip.setId(WIP_ID);
        wip.setFactoryId(F);
        wip.setBatchId(SOURCE_BATCH);
        wip.setIntermediateBatchNo(WIP_NO);
        wip.setProductTypeId(SEMI_SKU);
        wip.setUnit("kg");
        wip.setProducedQuantity(new BigDecimal("1.0"));
        wip.setConsumedQuantity(BigDecimal.ZERO);
        wip.setAvailableQuantity(new BigDecimal("1.0"));
        wip.setUnitCost(new BigDecimal("33.2"));
        wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);

        plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(F);
        plan.setPlanSourceType("SECONDARY");
        plan.setSecondarySourceWipId(WIP_ID);
        plan.setPlannedQuantity(new BigDecimal("0.6"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);

        ProductionBatch batch = new ProductionBatch();
        batch.setId(REWORK_BATCH);
        batch.setFactoryId(F);
        batch.setProductionPlanId(PLAN_ID);

        cure = task(1L, 1, "WP-CURE");
        rework = task(2L, 2, "WP-REWORK");
        port(cure, WorkflowTaskPort.Direction.INPUT, "RAW-NODE", "RAW-SALT");
        port(cure, WorkflowTaskPort.Direction.OUTPUT, "SEMI-NODE", SEMI_SKU);
        port(rework, WorkflowTaskPort.Direction.INPUT, "SEMI-NODE", SEMI_SKU);
        port(rework, WorkflowTaskPort.Direction.OUTPUT, "FG-NODE", "FG-1");

        when(wipRepo.findById(WIP_ID)).thenAnswer(inv -> Optional.of(wip));
        when(wipRepo.findSourceRefById(WIP_ID)).thenAnswer(inv -> Optional.of(sourceRef()));
        when(planRepo.findPlanStatusesByFactoryIdAndIdIn(eq(F), anyCollection())).thenAnswer(inv -> {
            List<Object[]> statuses = new ArrayList<>();
            statuses.add(new Object[] {PLAN_ID, plan.getStatus()});
            return statuses;
        });
        when(wipRepo.findByIdForUpdate(WIP_ID)).thenAnswer(inv -> Optional.of(wip));
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, WIP_NO))
                .thenAnswer(inv -> Optional.of(wip));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> {
            ledger.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(WIP_ID)).thenAnswer(inv -> new ArrayList<>(ledger));
        when(txnRepo.findByFactoryIdAndReportId(eq(F), any())).thenAnswer(inv -> ledger.stream()
                .filter(t -> inv.getArgument(1).equals(t.getReportId())).toList());
        when(reportRepo.sumPendingInputBySourceWipNo(eq(F), eq(WIP_NO), any())).thenReturn(BigDecimal.ZERO);
        when(reportRepo.findYieldReportsByBatch(F, REWORK_BATCH)).thenAnswer(inv -> new ArrayList<>(reports));
        when(rowRepo.findByFactoryIdAndPlanId(F, PLAN_ID)).thenAnswer(inv -> new ArrayList<>(rows));
        when(batchRepo.findById(REWORK_BATCH)).thenReturn(Optional.of(batch));
        when(batchRepo.findByFactoryIdAndProductionPlanId(F, PLAN_ID)).thenReturn(List.of(batch));
        when(planRepo.findByIdAndFactoryId(PLAN_ID, F)).thenAnswer(inv -> Optional.of(plan));
        when(taskRepo.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(F, INSTANCE))
                .thenReturn(List.of(cure, rework));
        when(taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, REWORK_BATCH))
                .thenReturn(List.of(cure, rework));
        when(portRepo.findByFactoryIdAndWorkflowInstanceId(F, INSTANCE)).thenAnswer(inv -> new ArrayList<>(ports));
        WorkProcess named = new WorkProcess();
        named.setId("WP-CURE");
        named.setProcessName("腌制");
        when(workProcessRepo.findByFactoryIdAndIdIn(eq(F), anyList())).thenReturn(List.of(named));
        when(ownershipGuard.ofProductionBatch(eq(F), any())).thenReturn(IntermediateOwnershipGuard.Owner.COMPANY);
    }

    private SemiFinishedInventoryRepository.SourceRef sourceRef() {
        return new SemiFinishedInventoryRepository.SourceRef() {
            @Override public Long getId() { return wip.getId(); }
            @Override public String getFactoryId() { return wip.getFactoryId(); }
            @Override public String getIntermediateBatchNo() { return wip.getIntermediateBatchNo(); }
            @Override public String getProductTypeId() { return wip.getProductTypeId(); }
            @Override public String getUnit() { return wip.getUnit(); }
        };
    }

    private WorkProcessTask task(Long id, int order, String processId) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setFactoryId(F);
        t.setProcessOrder(order);
        t.setWorkProcessId(processId);
        t.setWorkflowInstanceId(INSTANCE);
        t.setProductionBatchId(REWORK_BATCH);
        t.setStatus(WorkProcessTask.Status.PENDING);
        return t;
    }

    private WorkflowTaskPort port(WorkProcessTask t, WorkflowTaskPort.Direction direction, String node, String sku) {
        WorkflowTaskPort p = new WorkflowTaskPort();
        p.setId((long) ports.size() + 1);
        p.setFactoryId(F);
        p.setWorkflowInstanceId(INSTANCE);
        p.setTaskId(t.getId());
        p.setDirection(direction);
        p.setMaterialNodeId(node);
        p.setSkuId(sku);
        p.setRequired(true);
        ports.add(p);
        return p;
    }

    private void start() {
        wipService.deductForSecondaryPlan(WIP_ID, plan.getPlannedQuantity(), F, 7L,
                IntermediateOwnershipGuard.Owner.COMPANY, PLAN_ID);
    }

    /** 走 App 报工过账这条真实入口。 */
    private ProductionReport postReport(long id, String quantity) {
        ProductionReport report = new ProductionReport();
        report.setId(id);
        report.setFactoryId(F);
        report.setSourceWipNo(WIP_NO);
        report.setInputQuantity(new BigDecimal(quantity));
        report.setInputUnit("kg");
        report.setBatchId(REWORK_BATCH);
        report.setCustomFields(new HashMap<>(Map.of("sourceWipQuantity", new BigDecimal(quantity))));
        report.setApprovalStatus("APPROVED");
        wipService.postApprovedOutput(F, report, rework, 7L);
        reports.add(report);
        return report;
    }

    private void assertStock(String consumed, String available) {
        assertThat(wip.getConsumedQuantity()).as("consumed").isEqualByComparingTo(consumed);
        assertThat(wip.computeBalance()).as("available").isEqualByComparingTo(available);
    }

    private SecondaryPlanFeedService.Source source() {
        return feed.forPlan(F, plan).orElseThrow();
    }

    // ==================== 解锁 ====================

    @Test
    @DisplayName("返工计划: 接半成品的第 2 道解锁, 没有来源料的第 1 道按原规则 (只吃原料本来就开)")
    void reworkStepUnlocksWithoutUpstreamOutput() {
        assertThat(guard.evaluate(F, rework).unlocked()).isTrue();
        assertThat(guard.evaluate(F, cure).unlocked()).isTrue();
        assertThatCode(() -> guard.assertUnlocked(F, rework)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("阴性: 普通生产计划上游没产出仍被拦 (WORKFLOW_UPSTREAM_NOT_READY)")
    void ordinaryPlanStaysLocked() {
        plan.setPlanSourceType("NORMAL");
        plan.setSecondarySourceWipId(null);

        WorkflowStepUnlockGuard.Readiness readiness = guard.evaluate(F, rework);

        assertThat(readiness.unlocked()).isFalse();
        assertThat(readiness.waitingFor()).containsExactly("腌制");
        BusinessException ex = assertThrows(BusinessException.class, () -> guard.assertUnlocked(F, rework));
        assertThat(ex.getErrorCode()).isEqualTo(WorkflowStepUnlockGuard.ERROR_CODE);
    }

    @Test
    @DisplayName("阴性: 来源的物料和接料端口不匹配时不解锁")
    void mismatchedMaterialStaysLocked() {
        wip.setProductTypeId("SEMI-OTHER");

        assertThat(guard.evaluate(F, rework).unlocked()).isFalse();
    }

    @Test
    @DisplayName("阴性: 只解锁接这份料的端口 —— 同一道还有别的上游料没到, ALL_REQUIRED 仍锁")
    void otherRequiredInputStillBlocks() {
        WorkProcessTask sauce = task(3L, 1, "WP-CURE");
        port(sauce, WorkflowTaskPort.Direction.OUTPUT, "SAUCE-NODE", "SAUCE");
        port(rework, WorkflowTaskPort.Direction.INPUT, "SAUCE-NODE", "SAUCE");
        when(taskRepo.findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(F, INSTANCE))
                .thenReturn(List.of(cure, sauce, rework));

        assertThat(guard.evaluate(F, rework).unlocked()).isFalse();
    }

    // ==================== 开工保护 ====================

    @Test
    @DisplayName("开工保护: 工艺图有接料工序时放行")
    void acceptedRouteStarts() {
        assertThatCode(() -> feed.assertAcceptedBy(F, plan, REWORK_BATCH)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("开工保护: 工艺图没有工序接这份半成品 → 明确错误码 + 中文提示")
    void routeWithoutReceiverIsRejected() {
        ports.removeIf(p -> SEMI_SKU.equals(p.getSkuId()) && p.getDirection() == WorkflowTaskPort.Direction.INPUT);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> feed.assertAcceptedBy(F, plan, REWORK_BATCH));

        assertThat(ex.getErrorCode()).isEqualTo("SECONDARY_SOURCE_NOT_ACCEPTED");
        assertThat(ex.getMessage()).contains("没有工序接这份半成品").contains(WIP_NO);
    }

    // ==================== 记账 ====================

    @Test
    @DisplayName("takeHold 开工占用; 同一计划再来一次不重扣 (/start 和逐道录入先后到)")
    void takeHoldIsOncePerPlan() {
        feed.takeHold(F, plan, 7L);
        assertStock("0.6", "0.4");

        feed.takeHold(F, plan, 7L);

        assertStock("0.6", "0.4");
        assertThat(feed.heldQuantity(F, source())).isEqualByComparingTo("0.6");
    }

    @Test
    @DisplayName("takeHold 只管返工计划: 普通计划不动库存")
    void takeHoldIgnoresOrdinaryPlans() {
        plan.setPlanSourceType("NORMAL");

        feed.takeHold(F, plan, 7L);

        assertStock("0", "1.0");
    }

    @Test
    @DisplayName("开工占用: 库存 1/0.6/0.4, 流水带计划号, 占用余额 0.6")
    void startHoldsTheSource() {
        start();

        assertStock("0.6", "0.4");
        assertThat(feed.heldQuantity(F, source())).isEqualByComparingTo("0.6");
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getSourceRef()).contains(PLAN_ID);
    }

    @Test
    @DisplayName("只扣一次: 报工 0.6 后还是 1/0.6/0.4, 不再扣第二遍; 流水和血缘追到来源批")
    void reportConsumesTheHoldNotTheStockAgain() {
        start();

        postReport(11L, "0.6");

        assertStock("0.6", "0.4");
        assertThat(feed.heldQuantity(F, source())).isEqualByComparingTo("0");
        // 原批次的产量和批次记录都不动: 返工的合格率算在返工批次上, 不覆盖来源批
        assertThat(wip.getProducedQuantity()).isEqualByComparingTo("1.0");
        verify(batchRepo, never()).save(any());
        assertThat(ledger).anyMatch(t -> Long.valueOf(11L).equals(t.getReportId())
                && SemiFinishedInventoryTransaction.TxnType.OUT.equals(t.getTxnType())
                && t.getQuantity().abs().compareTo(new BigDecimal("0.6")) == 0
                && t.getUnitCostAtTxn().compareTo(new BigDecimal("33.2")) == 0);
        ArgumentCaptor<BatchLineageEdge> edge = ArgumentCaptor.forClass(BatchLineageEdge.class);
        verify(lineageEdgeRepo).save(edge.capture());
        assertThat(edge.getValue().getSourceId()).isEqualTo(String.valueOf(SOURCE_BATCH));
        assertThat(edge.getValue().getTargetId()).isEqualTo(String.valueOf(REWORK_BATCH));
        assertThat(edge.getValue().getQuantityUsed()).isEqualByComparingTo("0.6");
    }

    @Test
    @DisplayName("来源被自己占空也能领: 余额 0 不算领空")
    void fullyHeldSourceIsStillDrawable() {
        wip.setProducedQuantity(new BigDecimal("0.6"));
        wip.setAvailableQuantity(new BigDecimal("0.6"));
        start();
        assertStock("0.6", "0");

        postReport(11L, "0.6");

        assertStock("0.6", "0");
    }

    @Test
    @DisplayName("超量拦截: 单次超计划量 → 409, 库存不动")
    void overPlanInOneReportIsRejected() {
        start();

        BusinessException ex = assertThrows(BusinessException.class, () -> postReport(11L, "0.7"));

        assertThat(ex.getErrorCode()).isEqualTo("SECONDARY_SOURCE_OVER_PLAN");
        assertStock("0.6", "0.4");
    }

    @Test
    @DisplayName("重复消耗拦截: 累计超计划量 → 409; 驳回的报工不算")
    void repeatedConsumptionIsRejectedAndRejectedReportsDoNotCount() {
        start();
        ProductionReport first = postReport(11L, "0.4");

        BusinessException ex = assertThrows(BusinessException.class, () -> postReport(12L, "0.3"));
        assertThat(ex.getErrorCode()).isEqualTo("SECONDARY_SOURCE_OVER_PLAN");

        first.setApprovalStatus("REJECTED");
        assertThat(feed.remainingForPlan(F, source())).isEqualByComparingTo("0.6");
    }

    @Test
    @DisplayName("按实际回补: 计划 0.6 实际报 0.4, 结单还回没用的 0.2, 来源只少 0.4")
    void settlementReturnsWhatWasNotUsed() {
        start();
        postReport(11L, "0.4");

        BigDecimal released = feed.release(F, source(), null, 7L);

        assertThat(released).isEqualByComparingTo("0.2");
        assertStock("0.4", "0.6");
    }

    @Test
    @DisplayName("取消回补: 没报工取消全部还回")
    void cancelReturnsEverything() {
        start();

        feed.release(F, source(), null, 7L);

        assertStock("0", "1.0");
        assertThat(feed.heldQuantity(F, source())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("撤回再取消: 报工冲销还到库存, 取消只还剩下的占用, 不多还")
    void reversedReportThenCancelDoesNotReturnTwice() {
        start();
        ProductionReport report = postReport(11L, "0.4");

        wipService.reverseReportPosting(F, report, rework, 7L);
        reports.remove(report);
        assertStock("0.2", "0.8");

        feed.release(F, source(), null, 7L);

        assertStock("0", "1.0");
    }

    @Test
    @DisplayName("上线前从 /create-batch 开工的计划没有占用: 取消不还, 报工按普通领用扣库存")
    void withoutHoldNothingIsReturnedAndStockIsDrawnNormally() {
        assertThat(feed.heldQuantity(F, source())).isEqualByComparingTo("0");
        assertThat(feed.release(F, source(), null, 7L)).isEqualByComparingTo("0");
        assertStock("0", "1.0");

        postReport(11L, "0.4");

        assertStock("0.4", "0.6");
    }

    private SemiFinishedInventoryTransaction legacyRow(String ref, String quantity) {
        return SemiFinishedInventoryTransaction.builder()
                .semiFinishedId(WIP_ID).sourceRef(ref).quantity(new BigDecimal(quantity)).build();
    }

    @Test
    @DisplayName("上线前开工的计划: 流水认不出是谁占的 → 不还, 库存不动, 计划备注写明待人工核对")
    void unattributableHoldIsNotGuessed() {
        wip.setConsumedQuantity(new BigDecimal("0.6"));
        wip.refreshAvailableQuantity();
        ledger.add(legacyRow("secondary-plan-deduct:" + WIP_ID, "-0.6"));

        BigDecimal released = feed.closeHold(F, plan, 7L);

        assertThat(released).isEqualByComparingTo("0");
        assertStock("0.6", "0.4");
        assertThat(plan.getNotes()).contains("待人工核对").contains(WIP_NO);
    }

    @Test
    @DisplayName("旧格式的扣减已被旧格式的反冲抵消: 没有待核对的量, 不写备注")
    void legacyDeductAlreadyReversedIsNotReported() {
        ledger.add(legacyRow("secondary-plan-deduct:" + WIP_ID, "-0.6"));
        ledger.add(legacyRow("secondary-plan-reverse:" + WIP_ID, "0.6"));

        feed.closeHold(F, plan, 7L);

        assertThat(plan.getNotes()).isNull();
    }

    @Test
    @DisplayName("本计划有带号流水时, 同一来源上别的计划留下的旧流水不写到本计划头上")
    void taggedPlanIgnoresOtherPlansLegacyRows() {
        ledger.add(legacyRow("secondary-plan-deduct:" + WIP_ID, "-0.2"));
        start();

        feed.closeHold(F, plan, 7L);

        assertThat(plan.getNotes()).isNull();
    }

    @Test
    @DisplayName("直接完工: 已提交的逐道投料留在占用里当消耗, 只还没用的部分")
    void closeKeepsWhatRowsAlreadyFed() throws Exception {
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, WIP_NO))
                .thenAnswer(inv -> Optional.of(wip));
        start();
        savedRow(feedRow("r1", "0.4"), ProcessSheetRow.SUBMISSION_SUBMITTED);

        BigDecimal released = feed.closeHold(F, plan, 7L);

        assertThat(released).isEqualByComparingTo("0.2");
        assertStock("0.4", "0.6");
    }

    @Test
    @DisplayName("报工和取消的先后: 计划已取消再报工 → 409, 占用和库存都不动")
    void reportOnClosedPlanIsRejected() {
        start();
        plan.setStatus(ProductionPlanStatus.CANCELLED);

        BusinessException ex = assertThrows(BusinessException.class, () -> postReport(11L, "0.4"));

        assertThat(ex.getErrorCode()).isEqualTo("SECONDARY_PLAN_NOT_ACTIVE");
        assertStock("0.6", "0.4");
    }

    @Test
    @DisplayName("先锁计划再锁来源: 报工和取消同一顺序, 不互相死锁")
    void reportLocksPlanBeforeSource() {
        start();
        org.mockito.Mockito.clearInvocations(planRepo, wipRepo);

        postReport(11L, "0.4");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(planRepo, wipRepo);
        order.verify(planRepo).findByIdForUpdate(PLAN_ID);
        order.verify(wipRepo).findByIdForUpdate(WIP_ID);
    }

    @Test
    @DisplayName("解锁判定不把库存行读进持久化上下文: 否则之后的行锁拿到旧余额")
    void resolvingTheSourceDoesNotLoadTheEntity() {
        org.mockito.Mockito.clearInvocations(wipRepo);

        guard.evaluate(F, rework);
        feed.forPlan(F, plan);

        verify(wipRepo, never()).findById(any());
    }

    @Test
    @DisplayName("可领量看真实库存: 计划还能投 0.6, 但来源被盘亏后只剩 0.3, 显示 0.3")
    void remainingIsCappedByRealStock() {
        assertThat(feed.remainingForPlan(F, source())).isEqualByComparingTo("0.6");

        wip.setConsumedQuantity(new BigDecimal("0.7"));

        assertThat(feed.remainingForPlan(F, source())).isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("别的计划开工的量不算本计划的占用")
    void holdIsPerPlan() {
        start();
        ProductionPlan other = new ProductionPlan();
        other.setId("OTHER-PLAN");
        other.setFactoryId(F);
        other.setPlanSourceType("SECONDARY");
        other.setSecondarySourceWipId(WIP_ID);
        other.setPlannedQuantity(new BigDecimal("0.3"));

        assertThat(feed.heldQuantity(F, feed.forPlan(F, other).orElseThrow())).isEqualByComparingTo("0");
    }

    // ==================== 逐道录入 ====================

    private ProcessSheetRowRequest feedRow(String clientRowId, String quantity) {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(WIP_NO);
        ref.setSemiFinished(true);
        ref.setFeedQuantityKg(new BigDecimal(quantity));
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("REWORK");
        req.setUpstreamSources(List.of(ref));
        return req;
    }

    private void savedRow(ProcessSheetRowRequest req, String submission) throws Exception {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId(PLAN_ID);
        row.setClientRowId(req.getClientRowId());
        row.setProcessCode(req.getProcessCode());
        row.setSubmissionStatus(submission);
        row.setRowPayload(mapper.writeValueAsString(req));
        rows.add(row);
    }

    @Test
    @DisplayName("逐道录入超量拦截; 同一行重存不算它自己旧的量; 草稿不占额度")
    void webRowBudget() throws Exception {
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, WIP_NO))
                .thenAnswer(inv -> Optional.of(wip));
        savedRow(feedRow("r1", "0.5"), ProcessSheetRow.SUBMISSION_SUBMITTED);
        savedRow(feedRow("draft", "0.5"), ProcessSheetRow.SUBMISSION_DRAFT);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> feed.assertRowWithinPlan(F, PLAN_ID, feedRow("r2", "0.2")));
        assertThat(ex.getErrorCode()).isEqualTo("SECONDARY_SOURCE_OVER_PLAN");

        assertThatCode(() -> feed.assertRowWithinPlan(F, PLAN_ID, feedRow("r2", "0.1")))
                .doesNotThrowAnyException();
        assertThatCode(() -> feed.assertRowWithinPlan(F, PLAN_ID, feedRow("r1", "0.6")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("待扣不算两遍: 投自己占用的来源不进待扣; 占用没盖住的部分、别的计划的投料照常算")
    void pendingFeedSubtractsOnlyWhatTheHoldDoesNotCover() throws Exception {
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, WIP_NO))
                .thenAnswer(inv -> Optional.of(wip));
        start();
        ProcessSheetRow own = new ProcessSheetRow();
        own.setPlanId(PLAN_ID);
        own.setRowPayload(mapper.writeValueAsString(feedRow("r1", "0.7")));
        ProcessSheetRow other = new ProcessSheetRow();
        other.setPlanId("OTHER-PLAN");
        other.setRowPayload(mapper.writeValueAsString(feedRow("r2", "0.2")));
        when(rowRepo.findUnsettledStockFeedRows(F)).thenReturn(List.of(own, other));
        when(planRepo.findPlanStatusesByFactoryIdAndIdIn(eq(F), anyCollection())).thenReturn(List.of(
                new Object[] {PLAN_ID, ProductionPlanStatus.IN_PROGRESS},
                new Object[] {"OTHER-PLAN", ProductionPlanStatus.IN_PROGRESS}));
        PendingInterimFeedServiceImpl pending = new PendingInterimFeedServiceImpl(rowRepo, wipService, mapper, planRepo);
        ReflectionTestUtils.setField(pending, "secondaryPlanFeedService", feed);

        PendingInterimFeedService.Snapshot snapshot = pending.pendingSemiFeedSnapshot(F);

        // 0.7 投料里 0.6 已被占用盖住, 待扣只剩 0.1; 加上别的计划的 0.2
        assertThat(snapshot.pendingOf(WIP_NO)).isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("普通计划: 没有来源, 不查占用, 不拦投料")
    void ordinaryPlanHasNoSource() {
        plan.setPlanSourceType("NORMAL");

        assertThat(feed.forPlanId(F, PLAN_ID)).isEmpty();
        assertThatCode(() -> feed.assertRowWithinPlan(F, PLAN_ID, feedRow("r1", "99"))).doesNotThrowAnyException();
        assertThat(feed.coveredByHold(F, REWORK_BATCH, WIP_NO, new BigDecimal("99"), null)).isEqualByComparingTo("0");
    }
}
