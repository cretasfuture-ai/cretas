package com.cretas.aims.service.reversal;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 纪律排查 C5: 撤回<b>一个</b>生产批次, 只作废<b>这个批次</b>产出的成品 —— 修复前按计划 id 把整个计划下所有成品批次
 * 都标成「已撤回」, 同计划别的批次 (没撤回) 的成品跟着作废。
 *
 * <p>成品表只挂计划 id, 归属用与质检放行同一份判据 {@code PlanGoodsOwnership}: 血缘边 PRODUCTION_TO_FINISHED 优先,
 * 没有边时单批计划 / 批号相同算确定; 分不清就拒绝撤回并说明 (⛔ 不再「整个计划一起作废」, 也 ⛔ 不猜)。
 * 单批计划与修复前完全一样。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReversalVoidsOnlyThisBatchGoodsContractTest {

    private static final String F = "F006";
    private static final String PLAN_ID = "plan-c5";
    private static final Long MINE = 1924L;
    private static final Long OTHER = 1925L;
    private static final Long LOG_ID = 42L;

    @InjectMocks private ReportReversalServiceImpl service;

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private UserRepository userRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;
    @Mock private NotificationService notificationService;
    @Mock private ProcessSheetRowRepository processSheetRowRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionSettlementRepository settlementRepo;
    @Mock private ProductionSettlementConsumptionRepository consumptionRepo;
    @Mock private ProductionPlanRepository planRepo;
    @Mock private SalesOrderItemRepository salesOrderItemRepo;
    @Mock private BatchLineageEdgeRepository lineageRepo;

    private final List<ProductionBatch> planBatches = new ArrayList<>();
    private final List<FinishedGoodsBatch> planGoods = new ArrayList<>();
    private final List<BatchLineageEdge> edges = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "processSheetRowRepository", processSheetRowRepository);
        ReflectionTestUtils.setField(service, "materialBatchRepository", materialBatchRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", settlementRepo);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", consumptionRepo);
        ReflectionTestUtils.setField(service, "productionPlanRepository", planRepo);
        ReflectionTestUtils.setField(service, "salesOrderItemRepository", salesOrderItemRepo);
        ReflectionTestUtils.setField(service, "batchLineageEdgeRepository", lineageRepo);

        ReportReversalLog log = ReportReversalLog.builder()
                .id(LOG_ID).factoryId(F).batchId(MINE).planId(PLAN_ID)
                .status(ReportReversalLog.ReversalStatus.APPROVED)
                .reversalScope(ReportReversalLog.ReversalScope.WHOLE_ORDER)
                .build();
        when(reversalLogRepo.findById(LOG_ID)).thenReturn(Optional.of(log));
        when(reversalLogRepo.findByIdAndFactoryIdForUpdate(anyLong(), anyString()))
                .thenAnswer(i -> reversalLogRepo.findById(i.getArgument(0)));
        when(reversalLogRepo.save(any(ReportReversalLog.class))).thenAnswer(i -> i.getArgument(0));
        when(reportRepo.findYieldReportsByBatch(F, MINE)).thenReturn(Collections.emptyList());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(F, MINE)).thenReturn(Collections.emptyList());
        when(workProcessTaskRepository.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(F, MINE))
                .thenReturn(Collections.emptyList());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(F, PLAN_ID)).thenReturn(Collections.emptyList());
        when(settlementRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN_ID)).thenReturn(Optional.empty());
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(F);
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        when(planRepo.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));

        when(fgbRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN_ID)).thenReturn(planGoods);
        when(batchRepo.findByFactoryIdAndProductionPlanId(F, PLAN_ID)).thenReturn(planBatches);
        when(lineageRepo.findByFactoryIdAndTargetIdIn(eq(F), anyCollection())).thenReturn(edges);
        when(fgbRepo.save(any(FinishedGoodsBatch.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ProductionBatch batch(Long id, String number) {
        ProductionBatch b = ProductionBatch.builder().id(id).factoryId(F).productionPlanId(PLAN_ID)
                .batchNumber(number).status(ProductionBatchStatus.COMPLETED).unit("kg").build();
        planBatches.add(b);
        return b;
    }

    private FinishedGoodsBatch goods(String id, String number) {
        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId(id);
        fg.setFactoryId(F);
        fg.setBatchNumber(number);
        fg.setProductionPlanId(PLAN_ID);
        fg.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        fg.setProducedQuantity(new BigDecimal("10"));
        planGoods.add(fg);
        return fg;
    }

    private void edge(Long productionBatchId, FinishedGoodsBatch fg) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(F);
        e.setEdgeType(LineageEdgeRecorder.EDGE_PRODUCTION_TO_FINISHED);
        e.setSourceType(LineageEdgeRecorder.NODE_PRODUCTION_BATCH);
        e.setSourceId(String.valueOf(productionBatchId));
        e.setTargetType(LineageEdgeRecorder.NODE_FINISHED_BATCH);
        e.setTargetId(fg.getId());
        edges.add(e);
    }

    @Test
    void reversingOneBatchOfATwoBatchPlanVoidsOnlyItsOwnFinishedGoods() {
        batch(MINE, "PB-1924");
        batch(OTHER, "PB-1925");
        FinishedGoodsBatch mine = goods("fg-mine", "FG-plan-c5-S1-p");
        FinishedGoodsBatch theirs = goods("fg-theirs", "FG-plan-c5-S2-p");
        edge(MINE, mine);
        edge(OTHER, theirs);

        service.executeReversal(LOG_ID, F);

        assertThat(mine.getStatus()).as("本批成品作废").isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        assertThat(mine.getReversalLogId()).isEqualTo(LOG_ID);
        assertThat(theirs.getStatus()).as("同计划别的批次 (没撤回) 的成品不动 —— C5 的复现点")
                .isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(theirs.getReversalLogId()).isNull();
    }

    @Test
    void aSingleBatchPlanVoidsAllItsGoodsAsBefore() {
        batch(MINE, "PB-1924");
        FinishedGoodsBatch a = goods("fg-a", "FG-plan-c5-S1-p");
        FinishedGoodsBatch b = goods("fg-b", "FG-plan-c5-S2-p");

        service.executeReversal(LOG_ID, F);

        assertThat(a.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        assertThat(b.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
    }

    @Test
    void withoutLineageTheSameBatchNumberDecidesOwnership() {
        batch(MINE, "PB-1924");
        batch(OTHER, "PB-1925");
        FinishedGoodsBatch mine = goods("fg-mine", "PB-1924");
        FinishedGoodsBatch theirs = goods("fg-theirs", "PB-1925");

        service.executeReversal(LOG_ID, F);

        assertThat(mine.getStatus()).isEqualTo(FinishedGoodsBatch.Status.REVERSED);
        assertThat(theirs.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    void unclearOwnershipRefusesInsteadOfVoidingTheWholePlan() {
        batch(MINE, "PB-1924");
        batch(OTHER, "PB-1925");
        FinishedGoodsBatch a = goods("fg-a", "FG-plan-c5-S1-p");
        FinishedGoodsBatch b = goods("fg-b", "FG-plan-c5-S2-p");   // 没有血缘、批号也对不上

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, F))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("FG_OWNERSHIP_UNCLEAR");
                    assertThat(e.getActionHint()).isNotBlank();
                });
        assertThat(a.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        assertThat(b.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        verify(fgbRepo, never()).save(any(FinishedGoodsBatch.class));
    }

    @Test
    void goodsProducedJointlyWithAnotherBatchRefuseRatherThanHalfVoid() {
        batch(MINE, "PB-1924");
        batch(OTHER, "PB-1925");
        FinishedGoodsBatch joint = goods("fg-joint", "FG-plan-c5-S1-p");
        edge(MINE, joint);
        edge(OTHER, joint);   // 一批成品由两个生产批次合出

        assertThatThrownBy(() -> service.executeReversal(LOG_ID, F))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("FG_OWNERSHIP_UNCLEAR"));
        assertThat(joint.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
    }
}
