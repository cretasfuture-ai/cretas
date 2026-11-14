package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 14c 回料验收缺陷 (2026-11-14 F006 PLAN-1794591097738-82F4762E, QA-3MIX 画布 v3):
 * 多产出工序里填了回料数量, 报工成功却不产生 is_rework 的半成品库存, 只在物料平衡里露出「未解释差额 3」。
 *
 * <p>守的行为:
 * <ol>
 *   <li>报工带回料产出 ⇒ 回料以 rework=true 入半成品库 (postClerkOutput 第 9 参), 主产出 rework=false。</li>
 *   <li>回料计入物料平衡: 投入 10 = 主产出 7 + 回料 3 ⇒ 不提示偏差; 漏掉回料 (阴性对照) ⇒ 提示 30%。</li>
 *   <li>回料不得当投入承载行 (首产出行带投入走 WIP material_batch, 不产生 is_rework SFI):
 *       回料排在前面也要把主产出换到首位; 只有回料没有主产出 ⇒ 明确拒绝。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessSheet 多产出: 回料产出入账为 is_rework 半成品并计入物料平衡")
class ProcessSheetReworkOutputEntryTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "PLAN-REWORK-OUT-1";
    private static final String MAIN = "PT-3MIX-SEMI";
    private static final String REWORK = "PT-3MIX-REWORK";
    private static final com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Thresholds DEFAULT_THRESHOLDS =
            com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Thresholds.DEFAULT;

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProcessSheetRowChangeLogRepository changeLogRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository processRepo;
    @Mock private ProductWorkProcessRepository productWorkProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;

    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepository, changeLogRepo,
                new ObjectMapper(), wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo,
                wipInventoryService, finishedGoodsFeedService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "intermediateOwnershipGuard",
                org.mockito.Mockito.mock(IntermediateOwnershipGuard.class));
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY)).thenReturn(Optional.of(plan));
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY), any()))
                .thenReturn(Optional.of(new SemiFinishedInventory()));
        when(clerkService.resolveLaborRate(eq(FACTORY), any())).thenReturn(new BigDecimal("20"));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(rowRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ProcessSheetRowRequest.OutputLine line(String sku, String qty, boolean rework) {
        ProcessSheetRowRequest.OutputLine o = new ProcessSheetRowRequest.OutputLine();
        o.setProductTypeId(sku);
        o.setWorkflowPortId("port-" + sku);
        o.setQuantity(new BigDecimal(qty));
        o.setUnit("kg");
        o.setFinished(false);
        o.setRework(rework);
        return o;
    }

    /** 腌制道: 投入 10kg (上一道半成品), 产出 = 给定的产出行。 */
    private static ProcessSheetRowRequest marinate(ProcessSheetRowRequest.OutputLine... outputs) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setClientRowId("row-rework-out");
        r.setProcessCode("yanzhi");
        r.setProcessName("腌制");
        r.setProcessOrder(1);
        r.setProductTypeId(MAIN);
        r.setFinished(false);
        r.setInputQuantity(new BigDecimal("10"));
        r.setOutputQuantity(new BigDecimal("7"));
        r.setUnit("kg");
        UpstreamRef u = new UpstreamRef();
        u.setSourceBatchNumber("SFI-UP");
        u.setFeedQuantityKg(new BigDecimal("10"));
        u.setSemiFinished(true);
        r.setUpstreamSources(List.of(u));
        r.setOutputs(List.of(outputs));
        return r;
    }

    @Test
    @DisplayName("报工带回料 3kg ⇒ 回料以 rework=true 入半成品库, 主产出 rework=false")
    void reworkOutputPostsReworkSemiFinishedInventory() {
        service.saveRow(FACTORY, PLAN_ID, marinate(line(MAIN, "7", false), line(REWORK, "3", true)), 7L);

        verify(wipInventoryService).postClerkOutput(
                eq(FACTORY), eq(WipInventoryService.clerkSemiAnchor(PLAN_ID, REWORK)), eq(REWORK),
                eq(new BigDecimal("3")), eq("kg"), any(), any(), eq(1), eq(true), any());
        verify(wipInventoryService).postClerkOutput(
                eq(FACTORY), any(), eq(MAIN), eq(new BigDecimal("7")), any(), any(), any(), eq(1), eq(false), any());
        verify(wipInventoryService, never()).postClerkOutput(
                any(), any(), eq(REWORK), any(), any(), any(), any(), any(), eq(false), any());
    }

    @Test
    @DisplayName("回料排在前面 ⇒ 主产出仍先入账承载投入, 回料仍以 rework=true 入库")
    void reworkListedFirstStillDoesNotCarryInputs() {
        service.saveRow(FACTORY, PLAN_ID, marinate(line(REWORK, "3", true), line(MAIN, "7", false)), 7L);

        InOrder order = inOrder(wipInventoryService);
        order.verify(wipInventoryService).postClerkOutput(
                eq(FACTORY), any(), eq(MAIN), any(), any(), any(), any(), any(), eq(false), any());
        order.verify(wipInventoryService).postClerkOutput(
                eq(FACTORY), any(), eq(REWORK), any(), any(), any(), any(), any(), eq(true), any());
    }

    @Test
    @DisplayName("回料计入物料平衡: 10 = 7 + 3 不提示; 漏掉回料 (阴性对照) 提示偏差 30%")
    void reworkCountsInMaterialBalance() {
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(
                marinate(line(MAIN, "7", false), line(REWORK, "3", true)), DEFAULT_THRESHOLDS)).isEmpty();
        assertThat(ProcessSheetServiceImpl.materialBalanceWarnings(
                marinate(line(MAIN, "7", false)), DEFAULT_THRESHOLDS))
                .singleElement().asString().startsWith("物料平衡偏差 30%");
    }

    @Test
    @DisplayName("只有回料没有主产出 ⇒ 明确拒绝, 不入任何库存")
    void reworkOnlyIsRejectedNotMisBooked() {
        assertThatThrownBy(() -> service.saveRow(FACTORY, PLAN_ID, marinate(line(REWORK, "3", true)), 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo("PROCESS_SHEET_REWORK_ONLY_OUTPUT");
        verify(wipInventoryService, never()).postClerkOutput(
                any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("重排保持非回料/回料各自的相对顺序")
    void carrierFirstKeepsRelativeOrder() throws Throwable {
        ProcessSheetRowRequest.OutputLine r1 = line("R1", "1", true);
        ProcessSheetRowRequest.OutputLine m1 = line("M1", "1", false);
        ProcessSheetRowRequest.OutputLine r2 = line("R2", "1", true);
        ProcessSheetRowRequest.OutputLine m2 = line("M2", "1", false);
        assertThat(carrierFirst(List.of(r1, m1, r2, m2))).containsExactly(m1, m2, r1, r2);
    }

    @SuppressWarnings("unchecked")
    private static List<ProcessSheetRowRequest.OutputLine> carrierFirst(List<ProcessSheetRowRequest.OutputLine> outs)
            throws Throwable {
        Method m = ProcessSheetServiceImpl.class.getDeclaredMethod("carrierFirstOutputs", List.class);
        m.setAccessible(true);
        try {
            return (List<ProcessSheetRowRequest.OutputLine>) m.invoke(null, outs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
