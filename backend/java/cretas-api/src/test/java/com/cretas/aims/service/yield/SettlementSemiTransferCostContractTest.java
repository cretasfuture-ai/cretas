package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
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
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setSemiStockingDetail("[{\"anchor\":\"CLK-SEMI-1\",\"productionBatchId\":501,"
                + "\"materialBatchId\":\"MB-W\",\"quantity\":0.4,\"storageQuantity\":0.4,"
                + "\"transferredCost\":" + transferredCostJson + "}]");
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                .thenReturn(Optional.of(settlement));
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
