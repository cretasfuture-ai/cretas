package com.cretas.aims.service.processentry;

import com.cretas.aims.service.processentry.IntermediateOwnershipGuard.Owner;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.service.processentry.impl.ReworkPolicyAutoDeduction;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回料按配比自动追加 —— {@link WorkflowReworkDeduction} 的落账端。
 *
 * <p>每条正向断言都配一条能让它红的对照：没政策的工序一个字不动 / 主料未知不兜 0 /
 * 手工投够了不追加 / 库存不足只记缺口 / 非回料 SFI 不当候选。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReworkPolicyAutoDeductionTest {

    private static final String FACTORY = "F-T";
    private static final String PLAN = "PLAN-1";
    private static final String TARGET = "PT-BURGER";
    private static final String REWORK_SKU = "PT-REWORK";
    private static final Long BATCH_ID = 77L;

    private static final String NODES = """
        [
          {"id":"material:rework","kind":"SEMI_FINISHED",
           "data":{"name":"金检剔出回料","skuId":"PT-REWORK","isRework":true}},
          {"id":"process:grind","kind":"PROCESS",
           "data":{"processName":"绞肉",
                   "reworkPolicy":{"sources":[{"reworkNodeId":"material:rework","inputRatioPercent":8.5}]}}},
          {"id":"process:pack","kind":"PROCESS","data":{"processName":"装箱"}}
        ]
        """;

    @Mock private WorkflowClerkSheetService sheetService;
    @Mock private ProductionWorkflowInstanceRepository instanceRepo;
    @Mock private SemiFinishedInventoryRepository sfiRepo;
    @Mock private WipInventoryService wip;
    @Mock private IntermediateOwnershipGuard ownershipGuard;

    private ReworkPolicyAutoDeduction sut;

    @BeforeEach
    void setUp() {
        sut = new ReworkPolicyAutoDeduction(sheetService, instanceRepo, sfiRepo, wip, ownershipGuard);
        org.mockito.Mockito.lenient().when(ownershipGuard.ofPlanId(FACTORY, PLAN)).thenReturn(Owner.COMPANY);
        WorkflowClerkSheetConfigDTO.ProcessDescriptor grind = new WorkflowClerkSheetConfigDTO.ProcessDescriptor();
        grind.setProcessOrder(4);
        grind.setWorkflowNodeId("process:grind");
        WorkflowClerkSheetConfigDTO.ProcessDescriptor pack = new WorkflowClerkSheetConfigDTO.ProcessDescriptor();
        pack.setProcessOrder(10);
        pack.setWorkflowNodeId("process:pack");
        when(sheetService.getWorkflowSheetConfig(FACTORY, PLAN)).thenReturn(
                WorkflowClerkSheetConfigDTO.builder()
                        .workflowBatchId(BATCH_ID)
                        .processes(List.of(grind, pack))
                        .build());
        // 实体只有受保护构造 + 静态工厂; 这里只要 getNodesJson, 直接 mock
        ProductionWorkflowInstance instance = org.mockito.Mockito.mock(ProductionWorkflowInstance.class);
        when(instance.getNodesJson()).thenReturn(NODES);
        when(instanceRepo.findByFactoryIdAndProductionBatchId(FACTORY, BATCH_ID)).thenReturn(Optional.of(instance));
        when(sfiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(FACTORY), any()))
                .thenReturn(Optional.empty());
    }

    private static ProcessSheetRowRequest req(int order, String inputKg) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setProcessOrder(order);
        r.setProductTypeId(TARGET);
        r.setInputQuantity(inputKg == null ? null : new BigDecimal(inputKg));
        r.setUpstreamSources(new ArrayList<>());
        return r;
    }

    private static WipRowDTO rework(String batchNo, String available, LocalDate date) {
        WipRowDTO w = new WipRowDTO();
        w.setIntermediateBatchNo(batchNo);
        w.setProductTypeId(REWORK_SKU);
        w.setAvailableQuantity(new BigDecimal(available));
        w.setIsRework(true);
        w.setProductionDate(date);
        return w;
    }

    @Test
    @DisplayName("主料 100kg × 8.5% = 8.5kg: 按生产日期 FIFO 从已授权回料批次追加, inputQuantity 变 108.5")
    void drawsByRatioFifoAndBumpsInput() {
        when(wip.listWipByFactory(FACTORY, TARGET, 4, Owner.COMPANY)).thenReturn(List.of(
                rework("RW-NEW", "50", LocalDate.of(2026, 9, 2)),
                rework("RW-OLD", "5", LocalDate.of(2026, 9, 1))));
        ProcessSheetRowRequest r = req(4, "100");

        ReworkPolicyAutoDeduction.Outcome out = sut.apply(FACTORY, PLAN, r);

        assertThat(out.lines()).singleElement().satisfies(line -> {
            assertThat(line.getOutcome()).isEqualTo("RESOLVED");
            assertThat(line.getTargetKg()).isEqualByComparingTo("8.5");
            assertThat(line.getDrawnKg()).isEqualByComparingTo("8.5");
            assertThat(line.getShortfallKg()).isEqualByComparingTo("0");
            assertThat(line.getBatches()).extracting(ProcessSheetRowResult.ReworkAutoDeductionBatch::getBatchNumber)
                    .containsExactly("RW-OLD", "RW-NEW");   // 先旧后新
        });
        assertThat(r.getUpstreamSources()).hasSize(2).allSatisfy(ref -> {
            assertThat(ref.isSemiFinished()).isTrue();
            assertThat(ref.isAutomaticRework()).isTrue();
            assertThat(ref.getSkuId()).isEqualTo(REWORK_SKU);
            assertThat(ref.isStockConsumedAtSubmit()).as("扣减不在这里做, 标记留给 consumeReworkInputs").isFalse();
        });
        assertThat(r.getUpstreamSources().get(0).getFeedQuantityKg()).isEqualByComparingTo("5");
        assertThat(r.getUpstreamSources().get(1).getFeedQuantityKg()).isEqualByComparingTo("3.5");
        assertThat(r.getInputQuantity()).as("本工序共投 = 主料 + 回料").isEqualByComparingTo("108.5");
        assertThat(out.warnings()).isEmpty();
    }

    @Test
    @DisplayName("阴性对照: 没配 reworkPolicy 的工序一个字不动, 也不查库存")
    void processWithoutPolicyIsUntouched() {
        ProcessSheetRowRequest r = req(10, "100");

        ReworkPolicyAutoDeduction.Outcome out = sut.apply(FACTORY, PLAN, r);

        assertThat(out.lines()).isEmpty();
        assertThat(r.getUpstreamSources()).isEmpty();
        assertThat(r.getInputQuantity()).isEqualByComparingTo("100");
        verify(wip, never()).listWipByFactory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("主料投入量未知 → UNRESOLVED, 不追加、不兜 0、不查库存, 只留 warning")
    void unknownMainInputIsUnresolvedNotZero() {
        ProcessSheetRowRequest r = req(4, null);

        ReworkPolicyAutoDeduction.Outcome out = sut.apply(FACTORY, PLAN, r);

        assertThat(out.lines()).singleElement().satisfies(line -> {
            assertThat(line.getOutcome()).isEqualTo("UNRESOLVED_MAIN_INPUT");
            assertThat(line.getTargetKg()).isNull();
            assertThat(line.getDrawnKg()).isEqualByComparingTo("0");
        });
        assertThat(r.getUpstreamSources()).isEmpty();
        assertThat(out.warnings()).singleElement().asString().contains("主料投入量未知");
        verify(wip, never()).listWipByFactory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("库存不足: 只有 3kg 回料 → 追加 3kg, 缺口 5.5kg 记 warning, 不抛 (不拦报工)")
    void shortfallIsRecordedNotThrown() {
        when(wip.listWipByFactory(FACTORY, TARGET, 4, Owner.COMPANY)).thenReturn(List.of(
                rework("RW-1", "3", LocalDate.of(2026, 9, 1))));
        ProcessSheetRowRequest r = req(4, "100");

        ReworkPolicyAutoDeduction.Outcome out = sut.apply(FACTORY, PLAN, r);

        assertThat(out.lines()).singleElement().satisfies(line -> {
            assertThat(line.getDrawnKg()).isEqualByComparingTo("3");
            assertThat(line.getShortfallKg()).isEqualByComparingTo("5.5");
        });
        assertThat(r.getInputQuantity()).isEqualByComparingTo("103");
        assertThat(out.warnings()).singleElement().asString().contains("缺口 5.5kg");
    }

    @Test
    @DisplayName("操作员已手工投 10kg 同 SKU 回料 (> 8.5 目标): 不再追加; 分母是主料 (100), 不是含回料的 110")
    void manualReworkCountsTowardTargetAndIsExcludedFromDenominator() {
        SemiFinishedInventory manual = SemiFinishedInventory.builder()
                .factoryId(FACTORY).intermediateBatchNo("RW-MANUAL").productTypeId(REWORK_SKU)
                .isRework(true).build();
        when(sfiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "RW-MANUAL"))
                .thenReturn(Optional.of(manual));
        ProcessSheetRowRequest r = req(4, "110");
        UpstreamRef ref = new UpstreamRef();
        ref.setSourceBatchNumber("RW-MANUAL");
        ref.setFeedQuantityKg(BigDecimal.TEN);
        ref.setSemiFinished(true);
        r.getUpstreamSources().add(ref);

        ReworkPolicyAutoDeduction.Outcome out = sut.apply(FACTORY, PLAN, r);

        assertThat(out.lines()).singleElement().satisfies(line -> {
            assertThat(line.getMainInputKg()).as("分母剔除已投回料").isEqualByComparingTo("100");
            assertThat(line.getTargetKg()).isEqualByComparingTo("8.5");
            assertThat(line.getManualKg()).isEqualByComparingTo("10");
            assertThat(line.getDrawnKg()).isEqualByComparingTo("0");
        });
        assertThat(r.getUpstreamSources()).hasSize(1);
        assertThat(r.getInputQuantity()).isEqualByComparingTo("110");
        verify(wip, never()).listWipByFactory(any(), any(), any(), any());
    }

    @Test
    @DisplayName("阴性对照: 候选只认 is_rework=true 且 SKU 相同的行 —— 普通半成品 / 别的回料 SKU 都不动")
    void onlyReworkRowsOfTheBoundSkuAreCandidates() {
        WipRowDTO plain = rework("SFI-PLAIN", "100", LocalDate.of(2026, 8, 1));
        plain.setIsRework(false);
        WipRowDTO otherSku = rework("RW-OTHER", "100", LocalDate.of(2026, 8, 1));
        otherSku.setProductTypeId("PT-OTHER-REWORK");
        when(wip.listWipByFactory(FACTORY, TARGET, 4, Owner.COMPANY)).thenReturn(List.of(plain, otherSku));
        ProcessSheetRowRequest r = req(4, "100");

        ReworkPolicyAutoDeduction.Outcome out = sut.apply(FACTORY, PLAN, r);

        assertThat(out.lines()).singleElement().satisfies(line -> {
            assertThat(line.getDrawnKg()).isEqualByComparingTo("0");
            assertThat(line.getShortfallKg()).isEqualByComparingTo("8.5");
        });
        assertThat(r.getUpstreamSources()).isEmpty();
        assertThat(r.getInputQuantity()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("幂等 (终审 #514): 载荷里已含 automaticRework=true 的引用 → 不再追加、不查库存、inputQuantity 不动")
    void payloadAlreadyCarryingAutoRefsIsNotAppliedTwice() {
        when(wip.listWipByFactory(FACTORY, TARGET, 4, Owner.COMPANY)).thenReturn(List.of(
                rework("RW-1", "50", LocalDate.of(2026, 9, 1))));
        ProcessSheetRowRequest r = req(4, "108.5");
        UpstreamRef echoed = new UpstreamRef();
        echoed.setSourceBatchNumber("RW-1");
        echoed.setFeedQuantityKg(new BigDecimal("8.5"));
        echoed.setSemiFinished(true);
        echoed.setAutomaticRework(true);
        r.getUpstreamSources().add(echoed);

        ReworkPolicyAutoDeduction.Outcome out = sut.apply(FACTORY, PLAN, r);

        assertThat(out.lines()).isEmpty();
        assertThat(r.getUpstreamSources()).hasSize(1);
        assertThat(r.getInputQuantity()).isEqualByComparingTo("108.5");
        verify(wip, never()).listWipByFactory(any(), any(), any(), any());
        verify(sfiRepo, never()).findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("没钉 Workflow 的计划 (config=null) → 空结果, 不动 req")
    void noPinnedWorkflowIsNoop() {
        when(sheetService.getWorkflowSheetConfig(FACTORY, PLAN)).thenReturn(null);
        ProcessSheetRowRequest r = req(4, "100");

        assertThat(sut.apply(FACTORY, PLAN, r).lines()).isEmpty();
        assertThat(r.getUpstreamSources()).isEmpty();
        verify(instanceRepo, never()).findByFactoryIdAndProductionBatchId(any(), any());
    }
}
