package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.ReworkRecordRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.production.PlanCostVarianceService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 缺口 09 补齐: 期间成本差异报表汇总回料差 —— 回料差单列, 且计入材料差 (材料差 + 人工差 = 合计差);
 * 没有任何计划有可比回料时回料差是 null, ⛔ 不报 ¥0。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CostVarianceReportReworkContractTest {

    private static final String FACTORY = "F006";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 9);

    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock UserRepository userRepository;
    @Mock EquipmentRepository equipmentRepository;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock QualityInspectionRepository qualityInspectionRepository;
    @Mock ShipmentRecordRepository shipmentRecordRepository;
    @Mock SalesOrderRepository salesOrderRepository;
    @Mock ReworkRecordRepository reworkRecordRepository;
    @Mock ProductionLineRepository productionLineRepository;
    @Mock FactorySettingsRepository factorySettingsRepository;
    @Mock PlanCostVarianceService planCostVarianceService;

    @InjectMocks
    ProductionReportServiceImpl service;

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "planCostVarianceService", planCostVarianceService);
    }

    private static ProductionBatch batch(String planId) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(FACTORY);
        b.setProductTypeId("SKU-1");
        b.setProductName("卤牛肉");
        b.setProductionPlanId(planId);
        b.setTotalCost(new BigDecimal("100"));
        b.setActualQuantity(new BigDecimal("10"));
        return b;
    }

    private static PlanCostVarianceDTO plan(String id, String std, String act, String price, String rework) {
        return PlanCostVarianceDTO.builder().planId(id).productTypeId("SKU-1").comparable(true)
                .standardCost(new BigDecimal(std)).actualCost(new BigDecimal(act))
                .totalVariance(new BigDecimal(act).subtract(new BigDecimal(std)))
                .priceVariance(new BigDecimal(price)).usageVariance(BigDecimal.ZERO).substituteVariance(BigDecimal.ZERO)
                .reworkVariance(rework == null ? null : new BigDecimal(rework))
                .reworkStatus(rework == null ? PlanCostVarianceDTO.REWORK_NONE : PlanCostVarianceDTO.REWORK_COMPARABLE)
                .outputQuantity(BigDecimal.TEN)
                .lines(new ArrayList<>()).build();
    }

    private CostVarianceReportDTO report(PlanCostVarianceDTO... plans) {
        List<ProductionBatch> batches = new ArrayList<>();
        for (PlanCostVarianceDTO p : plans) {
            batches.add(batch(p.getPlanId()));
            when(planCostVarianceService.computeForPlan(FACTORY, p.getPlanId(), false)).thenReturn(p);
        }
        when(productionBatchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any())).thenReturn(batches);
        return service.getCostVarianceReport(FACTORY, DATE.minusDays(30), DATE);
    }

    @Test
    void reworkVarianceIsSummedAndCountedInsideMaterialVariance() {
        // P1: 标准 100, 实际 140 = 价格差 10 + 回料差 30; P2: 标准 50, 实际 55 = 价格差 5, 没有回料
        CostVarianceReportDTO r = report(plan("P1", "100", "140", "10", "30"), plan("P2", "50", "55", "5", null));

        assertThat(r.getReworkVariance()).isEqualByComparingTo("30");
        assertThat(r.getMaterialVariance()).as("价格 10 + 5, 回料 30").isEqualByComparingTo("45");
        assertThat(r.getTotalVariance()).isEqualByComparingTo("45");
        assertThat(r.getProductVariances().getFirst().getReworkVariance()).isEqualByComparingTo("30");
        assertThat(r.getProductVariances().getFirst().getMaterialVariance())
                .as("产品级材料差同样含回料差, 材料差 = 合计差 (本例无人工)")
                .isEqualByComparingTo(r.getProductVariances().getFirst().getTotalVariance());
        assertThat(r.getProductVariances().getFirst().getVarianceReason()).contains("回料差 +¥30.00");
    }

    @Test
    void noComparableReworkAnywhereIsNullNotZero() {
        CostVarianceReportDTO r = report(plan("P2", "50", "55", "5", null));
        assertThat(r.getReworkVariance()).as("⛔ 不报「回料差 ¥0」").isNull();
        assertThat(r.getMaterialVariance()).as("阳性读数: 材料差照常算出").isEqualByComparingTo("5");
        assertThat(r.getProductVariances().getFirst().getReworkVariance()).isNull();
    }
}
