package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.YieldReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R5 (2026-11-09): 成本差异的实际侧按<b>净额</b> —— 扣掉结单转进半成品库的那部分。
 *
 * <p>走真的 {@link OrderCostBreakdownService#computeByPlan} (成品入库单价、毛利、成本差异共用的那一个内核)
 * 和真的 {@link PlanCostVarianceService}: 投 78kg×10 = 780, 结单转入半成品库划走 80, 成品 70kg。
 * 标准按成品 70kg × 1kg/kg × 10 = 700。按毛额会出 80 的假超支; 按净额差异为 0, 且
 * <pre>成本差异实际成本 = 计划净成本 = 成品入库单价 × 入库量 (毛利扣的就是它)</pre>
 */
class PlanCostVarianceNetOfSemiTransferContractTest {

    private static final String F = "F006";
    private static final String PLAN = "PP-R5";

    private final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
    private final ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
    private final MaterialConsumptionRepository consumptionRepository = mock(MaterialConsumptionRepository.class);
    private final MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
    private final ProductionSettlementRepository settlementRepository = mock(ProductionSettlementRepository.class);
    private final YieldReportService yieldReportService = mock(YieldReportService.class);
    private final PlanStandardCostLineRepository lineRepository = mock(PlanStandardCostLineRepository.class);
    private final UnitConversionService units = mock(UnitConversionService.class);

    private OrderCostBreakdownService breakdownService;
    private PlanCostVarianceService varianceService;
    private ProductionPlan plan;

    @BeforeEach
    void setUp() throws Exception {
        breakdownService = newBreakdownService();
        varianceService = new PlanCostVarianceService(lineRepository, planRepository, batchRepository,
                consumptionRepository, materialBatchRepository, mock(RawMaterialTypeRepository.class),
                breakdownService, units);
        when(units.convert(any(), eq("kg"), eq("kg"))).thenAnswer(inv -> inv.getArgument(0));

        plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(F);
        plan.setPlanNumber("P-R5");
        plan.setProductTypeId("FG-1");
        plan.setPlannedUnit("kg");
        plan.setActualQuantity(new BigDecimal("70"));
        when(planRepository.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(plan));

        ProductionBatch batch = new ProductionBatch();
        batch.setId(1L);
        batch.setFactoryId(F);
        batch.setQuantity(new BigDecimal("70"));
        batch.setProductionPlanId(PLAN);
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        when(batchRepository.findByFactoryIdAndProductionPlanId(F, PLAN)).thenReturn(List.of(batch));
        when(yieldReportService.getYield(F, 1L)).thenReturn(BatchYieldDTO.builder()
                .totalLaborCost(BigDecimal.ZERO)
                .steps(List.of(StepYieldDTO.builder().processOrder(1).materialCost(BigDecimal.ZERO).build()))
                .build());

        MaterialConsumption beef = new MaterialConsumption();
        beef.setFactoryId(F);
        beef.setBatchId("BEEF-LOT");
        beef.setMaterialTypeId("BEEF");
        beef.setQuantity(new BigDecimal("78"));
        beef.setUnitPrice(new BigDecimal("10"));
        beef.setTotalCost(new BigDecimal("780"));
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(1L, F)).thenReturn(List.of(beef));
        MaterialBatch lot = new MaterialBatch();
        lot.setId("BEEF-LOT");
        lot.setBatchNumber("牛肉-1");
        lot.setMaterialTypeId("BEEF");
        lot.setQuantityUnit("kg");
        lot.setUnitPrice(new BigDecimal("10"));
        lot.setOwnership(InventoryOwnership.COMPANY_OWNED);
        when(materialBatchRepository.findByIdAndFactoryId("BEEF-LOT", F)).thenReturn(Optional.of(lot));

        PlanStandardCostLine line = new PlanStandardCostLine();
        line.setLineType(PlanStandardCostLine.TYPE_MATERIAL);
        line.setMaterialTypeId("BEEF");
        line.setMaterialName("牛肉");
        line.setStandardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT);
        line.setStandardRate(BigDecimal.ONE);
        line.setStandardUnit("kg");
        line.setPriceUnit("kg");
        line.setUnitPrice(new BigDecimal("10"));
        line.setCapturedAt(LocalDateTime.of(2026, 11, 1, 8, 0));
        when(lineRepository.findByFactoryIdAndPlanIdOrderByIdAsc(F, PLAN)).thenReturn(List.of(line));
    }

    private OrderCostBreakdownService newBreakdownService() throws Exception {
        Constructor<?> ctor = Arrays.stream(OrderCostBreakdownService.class.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ProductionPlanRepository.class) args[i] = planRepository;
            else if (types[i] == ProductionBatchRepository.class) args[i] = batchRepository;
            else if (types[i] == MaterialConsumptionRepository.class) args[i] = consumptionRepository;
            else if (types[i] == MaterialBatchRepository.class) args[i] = materialBatchRepository;
            else if (types[i] == YieldReportService.class) args[i] = yieldReportService;
            else if (types[i] == ObjectMapper.class) args[i] = new ObjectMapper();
            else args[i] = mock(types[i]);
        }
        ctor.setAccessible(true);
        OrderCostBreakdownService service = (OrderCostBreakdownService) ctor.newInstance(args);
        ReflectionTestUtils.setField(service, "settlementRepository", settlementRepository);
        return service;
    }

    private void settledWithTransfer(String transferredCostJson) {
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setSemiStockingDetail("[{\"anchor\":\"CLK-SEMI-R5\",\"productionBatchId\":1,"
                + "\"materialBatchId\":\"MB-W\",\"quantity\":8,\"storageQuantity\":8,"
                + "\"transferredCost\":" + transferredCostJson + "}]");
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                .thenReturn(Optional.of(settlement));
    }

    /** 成品入库单价走结单入库的那条真路径 (ProductionPlanServiceImpl#resolveReceiptUnitCost)。 */
    private BigDecimal receiptUnitCost(BigDecimal received) throws Exception {
        Constructor<?> ctor = Arrays.stream(ProductionPlanServiceImpl.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes()).map(type -> (Object) mock(type)).toArray();
        ProductionPlanServiceImpl planService = (ProductionPlanServiceImpl) ctor.newInstance(args);
        ReflectionTestUtils.setField(planService, "orderCostBreakdownService", breakdownService);
        return ReflectionTestUtils.invokeMethod(planService, "resolveReceiptUnitCost", F, PLAN, received);
    }

    @Test
    void varianceActualSideIsNetOfTheSemiTransferAndMatchesUnitCostAndMargin() throws Exception {
        settledWithTransfer("80");

        OrderCostBreakdownDTO breakdown = breakdownService.computeByPlan(F, PLAN, false);
        PlanCostVarianceDTO variance = varianceService.computeForPlan(F, PLAN, false);
        BigDecimal received = new BigDecimal("70");
        BigDecimal unitCost = receiptUnitCost(received);

        assertThat(breakdown.getTotalCost()).as("计划净成本 = 780 − 80").isEqualByComparingTo("700");
        assertThat(variance.isComparable()).isTrue();
        assertThat(variance.getTotalVariance()).as("转走的 80 不是超支").isEqualByComparingTo("0");
        assertThat(variance.getUsageVariance()).isEqualByComparingTo("0");
        assertThat(variance.getSemiTransferredCost()).isEqualByComparingTo("80");
        PlanCostVarianceDTO.Line beef = variance.getLines().stream()
                .filter(l -> "BEEF".equals(l.getMaterialTypeId())).findFirst().orElseThrow();
        assertThat(beef.getActualQuantity()).as("78kg 里留给成品的那部分").isEqualByComparingTo("70");
        // 三处同一个数: 成本差异的实际成本 = 计划净成本 = 成品入库单价 × 入库量 (毛利按它扣成本)
        BigDecimal soldCost = unitCost.multiply(received).setScale(2, RoundingMode.HALF_UP);
        assertThat(variance.getActualCost()).isEqualByComparingTo(breakdown.getTotalCost());
        assertThat(soldCost).isEqualByComparingTo(breakdown.getTotalCost());
        BigDecimal revenue = new BigDecimal("1050");
        assertThat(revenue.subtract(soldCost)).as("毛利 = 收入 − 净成本").isEqualByComparingTo("350");
    }

    @Test
    void withoutTransferTheActualSideIsUnchanged() {
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(F, PLAN))
                .thenReturn(Optional.empty());

        PlanCostVarianceDTO variance = varianceService.computeForPlan(F, PLAN, false);

        assertThat(variance.getActualCost()).isEqualByComparingTo("780");
        assertThat(variance.getTotalVariance()).as("没有划转: 多用的 8kg 就是真超支").isEqualByComparingTo("80");
        assertThat(variance.getSemiTransferredCost()).isNull();
    }

    @Test
    void unknownTransferMarksTheVarianceIncompleteInsteadOfInventingZero() {
        settledWithTransfer("null");

        PlanCostVarianceDTO variance = varianceService.computeForPlan(F, PLAN, false);

        assertThat(variance.isComparable()).isFalse();
        assertThat(variance.getComparabilityReason()).contains("半成品");
        assertThat(variance.getTotalVariance()).isNull();
        assertThat(variance.getActualCost()).isNull();
    }

    @Test
    void maskedViewHidesTheTransferredAmount() {
        settledWithTransfer("80");
        lenient().when(units.convert(any(), anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        PlanCostVarianceDTO variance = varianceService.computeForPlan(F, PLAN, true);

        assertThat(variance.getSemiTransferredCost()).isNull();
        assertThat(variance.getTotalVariance()).as("差异总额本来就不脱敏, 仍是净额口径").isEqualByComparingTo("0");
    }

    /**
     * 与 #1549 (回料差) 合并后: 回投的回料成本也在成本核算的总成本里, 划转额里有它一份 ——
     * 回投量同比例扣 (单价不变); 回料产出转出是产出侧, 不扣; 没有划转时原样。
     */
    @Test
    void reworkFeedIsNettedLikeEveryOtherActualInputButReworkOutputIsNot() {
        PlanCostVarianceService.SemiTransferShare share =
                new PlanCostVarianceService.SemiTransferShare(new BigDecimal("40"), new BigDecimal("0.6"), null);
        PlanCostVarianceCalculator.ReworkUsage fed = new PlanCostVarianceCalculator.ReworkUsage("RW-1", "回料",
                new BigDecimal("10"), "kg", new BigDecimal("3"), false, List.of(), List.of(), false);
        PlanCostVarianceCalculator.ReworkUsage out = new PlanCostVarianceCalculator.ReworkUsage("RW-1", "回料",
                new BigDecimal("5"), "kg", new BigDecimal("3"), false, List.of(), List.of(), true);

        List<PlanCostVarianceCalculator.ReworkUsage> net = share.netRework(List.of(fed, out));

        assertThat(net.get(0).quantity()).as("回投量按留给成品的比例扣").isEqualByComparingTo("6");
        assertThat(net.get(0).unitCost()).as("单价不变").isEqualByComparingTo("3");
        assertThat(net.get(1)).as("回料产出转出不扣").isSameAs(out);
        List<PlanCostVarianceCalculator.ReworkUsage> gross = List.of(fed);
        assertThat(PlanCostVarianceService.SemiTransferShare.of(null).netRework(gross)).as("没有划转原样")
                .isSameAs(gross);
    }
}
