package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 结单转入半成品库的成本划转 (Owner 2026-11-04): 走真的 {@link OrderCostBreakdownService#computeByPlan},
 * 这是成品入库单价、产出分摊、成本差异共用的那一个内核, 在这里扣一次, 各处都不重复。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementSemiTransferCostContractTest {

    private static final String F = "F006";
    private static final String PLAN = "PP1";

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private MaterialConsumptionRepository consumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionReportRepository productionReportRepository;
    @Mock private YieldReportService yieldReportService;
    @Mock private BomVersionRepository bomVersionRepository;
    @Mock private ProcessSheetRowRepository processSheetRowRepository;
    @Mock private ProductionSettlementRepository settlementRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private OrderCostBreakdownService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "settlementRepository", settlementRepository);
        ProductionBatch batch = new ProductionBatch();
        batch.setId(1L);
        batch.setQuantity(new BigDecimal("100"));
        batch.setProductionPlanId(PLAN);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of(batch));
        when(yieldReportService.getYield(F, 1L)).thenReturn(BatchYieldDTO.builder()
                .totalLaborCost(BigDecimal.ZERO)
                .steps(List.of(StepYieldDTO.builder().processOrder(1).materialCost(BigDecimal.ZERO).build()))
                .build());
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(F);
        consumption.setBatchId("MB1");
        consumption.setQuantity(new BigDecimal("78"));
        consumption.setUnitPrice(new BigDecimal("10"));
        consumption.setTotalCost(new BigDecimal("780"));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(1L, F)).thenReturn(List.of(consumption));
        MaterialBatch raw = new MaterialBatch();
        raw.setId("MB1");
        raw.setBatchNumber("原料A");
        raw.setQuantityUnit("kg");
        when(materialBatchRepository.findByIdAndFactoryId("MB1", F)).thenReturn(Optional.of(raw));
    }

    private void settledWith(String transferredCostJson) {
        settledWith(transferredCostJson, 1L);
    }

    private void settledWith(String transferredCostJson, Long producerBatchId) {
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setSemiStockingDetail("[{\"anchor\":\"CLK-SEMI-1\",\"productionBatchId\":" + producerBatchId + ","
                + "\"materialBatchId\":\"MB-W\",\"quantity\":0.4,\"storageQuantity\":0.4,"
                + "\"transferredCost\":" + transferredCostJson + "}]");
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                .thenReturn(Optional.of(settlement));
    }

    private MaterialConsumption terminalConsumesSource(String producedQuantity) {
        ProductionBatch terminal = new ProductionBatch();
        terminal.setId(1L);
        terminal.setFactoryId(F);
        terminal.setBatchNumber("PB-TERMINAL");
        terminal.setBatchType("REGULAR");
        terminal.setQuantity(new BigDecimal("5"));
        terminal.setProductionPlanId(PLAN);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of(terminal));
        when(batchRepository.findByFactoryIdAndBatchNumber(F, "PB-TERMINAL")).thenReturn(Optional.of(terminal));
        when(batchRepository.findByIdAndFactoryId(1L, F)).thenReturn(Optional.of(terminal));
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN);
        when(planRepository.findByFactoryIdAndSourceOrderId(F, "SO1")).thenReturn(List.of(plan));
        when(batchRepository.findByFactoryIdAndProductionPlanIdIn(F, List.of(PLAN))).thenReturn(List.of(terminal));
        MaterialConsumption parentRaw = new MaterialConsumption();
        parentRaw.setFactoryId(F);
        parentRaw.setBatchId("MB1");
        parentRaw.setQuantity(new BigDecimal("1.2"));
        parentRaw.setTotalCost(new BigDecimal("9.37"));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(501L, F))
                .thenReturn(List.of(parentRaw));
        MaterialBatch wip = new MaterialBatch();
        wip.setId("MB-W");
        wip.setFactoryId(F);
        wip.setBatchNumber("CLK-W-SOURCE");
        wip.setQuantityUnit("kg");
        wip.setReceiptQuantity(producedQuantity == null ? null : new BigDecimal(producedQuantity));
        wip.setSourceDocType("PRODUCTION_BATCH");
        wip.setSourceDocId("501");
        when(materialBatchRepository.findByIdAndFactoryId("MB-W", F)).thenReturn(Optional.of(wip));
        MaterialConsumption feed = new MaterialConsumption();
        feed.setFactoryId(F);
        feed.setBatchId("MB-W");
        feed.setSourceType("SEMI_FINISHED");
        feed.setQuantity(new BigDecimal("0.6"));
        feed.setUnitPrice(new BigDecimal("9.37"));
        feed.setTotalCost(new BigDecimal("5.622"));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(1L, F)).thenReturn(List.of(feed));
        when(yieldReportService.getYield(F, 1L)).thenReturn(BatchYieldDTO.builder()
                .totalLaborCost(new BigDecimal("4.23"))
                .steps(List.of(StepYieldDTO.builder().processOrder(2).materialCost(BigDecimal.ZERO).build()))
                .build());
        return feed;
    }

    @Test
    @DisplayName("末道仅领用上游0.6/1kg: 剩余0.4转库不能再扣已分摊之外的成本")
    void allocatedTerminalCostDoesNotDeductTheLeftoverAgain() {
        terminalConsumesSource("1");
        settledWith("3.748", 501L);

        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);

        assertThat(dto.getRawMaterialCost()).isEqualByComparingTo("5.622");
        assertThat(dto.getTotalCost()).isEqualByComparingTo("9.852");
        assertThat(dto.getSemiTransferredCost()).isNull();
        assertThat(service.computeByBatch(F, "PB-TERMINAL", false).getTotalCost()).isEqualByComparingTo("9.852");
        assertThat(service.compute(F, "SO1", false).getTotalCost()).isEqualByComparingTo("9.852");
        assertThat(service.computeByBatchIds(F, "group", List.of(1L), false).getTotalCost())
                .isEqualByComparingTo("9.852");
    }

    @Test
    @DisplayName("不在末道成本基数中的余料成本未知: 不污染已有可靠分摊")
    void unrelatedUnknownTransferDoesNotTaintAllocatedCost() {
        terminalConsumesSource("1");
        settledWith("null", 501L);
        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);
        assertThat(dto.getTotalCost()).isEqualByComparingTo("9.852");
        assertThat(dto.isCostComplete()).isTrue();
    }

    @Test
    @DisplayName("历史上游缺产量分母: 不能把全额继承当成已按领用分摊")
    void missingSourceDenominatorKeepsTransferredCostIncomplete() {
        terminalConsumesSource(null);
        settledWith("3.748", 501L);
        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);
        assertThat(dto.getTotalCost()).isNull();
        assertThat(dto.isCostComplete()).isFalse();
        assertThat(dto.getMissingCostItems()).contains("SETTLEMENT_SEMI_TRANSFER_COST");
    }

    @Test
    @DisplayName("上游只有人工且缺分母: 不能漏过原料追溯之外的全额继承")
    void laborOnlyLegacySourceAlsoKeepsCostIncomplete() {
        terminalConsumesSource(null);
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(501L, F)).thenReturn(List.of());
        when(yieldReportService.getYield(F, 501L)).thenReturn(BatchYieldDTO.builder()
                .totalLaborCost(new BigDecimal("9.37")).steps(List.of()).build());
        settledWith("3.748", 501L);
        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);
        assertThat(dto.getTotalCost()).isNull();
        assertThat(dto.getMissingCostItems()).contains("SETTLEMENT_SEMI_TRANSFER_COST");
    }

    @Test
    @DisplayName("缺实际领用量: 全额回落不能被误认为可靠的比例分摊")
    void missingFeedQuantityKeepsCostIncomplete() {
        terminalConsumesSource("1").setQuantity(null);
        settledWith("3.748", 501L);
        assertThat(service.computeByPlan(F, PLAN, false).getTotalCost()).isNull();
    }

    @Test
    @DisplayName("明确零领用: 上游原料与人工均不得继承")
    void zeroFeedDoesNotInheritUpstreamLabor() {
        MaterialConsumption feed = terminalConsumesSource("1");
        feed.setQuantity(BigDecimal.ZERO);
        feed.setTotalCost(BigDecimal.ZERO);
        when(yieldReportService.getYield(F, 501L)).thenReturn(BatchYieldDTO.builder()
                .totalLaborCost(new BigDecimal("9.37")).steps(List.of()).build());
        settledWith("3.748", 501L);
        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);
        assertThat(dto.getRawMaterialCost()).isZero();
        assertThat(dto.getLaborCost()).isEqualByComparingTo("4.23");
        assertThat(dto.getTotalCost()).isEqualByComparingTo("4.23");
    }

    @Test
    @DisplayName("划转缺来源生产批身份: 无法核对成本归属时保持未知")
    void missingProducerIdentityIsNotTreatedAsUnrelated() {
        settledWith("80", null);
        assertThat(service.computeByPlan(F, PLAN, false).getTotalCost()).isNull();
    }

    @Test
    @DisplayName("没有划转: 计划总成本不变")
    void noTransferLeavesCostAlone() {
        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);

        assertThat(dto.getTotalCost()).isEqualByComparingTo("780");
        assertThat(dto.getSemiTransferredCost()).isNull();
    }

    @Test
    @DisplayName("划走 80: 成品这边只剩 700, 账不重复")
    void transferredCostLeavesThePlan() {
        settledWith("80");

        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);

        assertThat(dto.getTotalCost()).isEqualByComparingTo("700");
        assertThat(dto.getSemiTransferredCost()).isEqualByComparingTo("80");
        assertThat(dto.isCostComplete()).isTrue();
    }

    @Test
    @DisplayName("划转成本未知: 本计划总成本保持未知, 不当 0, 也不把全额留给成品")
    void unknownTransferKeepsCostUnknown() {
        settledWith("null");

        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);

        assertThat(dto.getTotalCost()).isNull();
        assertThat(dto.isCostComplete()).isFalse();
        assertThat(dto.getMissingCostItems()).contains("SETTLEMENT_SEMI_TRANSFER_COST");
    }

    @Test
    @DisplayName("划走的比总成本还多: 账对不上, 按未知处理, 不出负成本")
    void transferAboveTotalIsNotTrusted() {
        settledWith("900");

        OrderCostBreakdownDTO dto = service.computeByPlan(F, PLAN, false);

        assertThat(dto.getTotalCost()).isNull();
        assertThat(dto.getMissingCostItems()).contains("SETTLEMENT_SEMI_TRANSFER_COST");
    }

    @Test
    @DisplayName("撤回后结单软删: 查不到结单, 成本全额回到计划上")
    void reversedSettlementGivesCostBack() {
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                .thenReturn(Optional.empty());

        assertThat(service.computeByPlan(F, PLAN, false).getTotalCost()).isEqualByComparingTo("780");
    }

    @Test
    @DisplayName("脱敏视图不露划转金额")
    void maskedViewHidesTheAmount() {
        settledWith("80");

        assertThat(service.computeByPlan(F, PLAN, true).getSemiTransferredCost()).isNull();
    }
}
