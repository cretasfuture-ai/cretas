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
 * 成本差异报表「按产品」汇总行的产品名: 多产出计划的批次挂在内部原料锚点上, 批次上冗余存的名字是
 * {@code __RAW_WORKFLOW_OWNER__<原料id>} —— 不许原样出现在汇总列表和导出里
 * (统一验收 2026-11-10: #1563 修了计划明细, 汇总层 productVariances 仍漏出 2 行)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CostVarianceReportProductNameContractTest {

    private static final String FACTORY = "F006";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 10);
    private static final String ANCHOR_ID = "RMT_F006_LSM_2bce8bda718ed689";
    private static final String ANCHOR_NAME = "__RAW_WORKFLOW_OWNER__" + ANCHOR_ID;

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

    private static ProductionBatch batch(String planId, String productTypeId, String batchProductName) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(FACTORY);
        b.setProductTypeId(productTypeId);
        b.setProductName(batchProductName);
        b.setProductionPlanId(planId);
        b.setTotalCost(new BigDecimal("100"));
        b.setActualQuantity(new BigDecimal("10"));
        return b;
    }

    private CostVarianceReportDTO report(String planId, String productTypeId, String batchProductName) {
        PlanCostVarianceDTO plan = PlanCostVarianceDTO.builder().planId(planId).productTypeId(productTypeId).comparable(true)
                .standardCost(new BigDecimal("100")).actualCost(new BigDecimal("110")).totalVariance(new BigDecimal("10"))
                .priceVariance(new BigDecimal("10")).usageVariance(BigDecimal.ZERO).substituteVariance(BigDecimal.ZERO)
                .reworkStatus(PlanCostVarianceDTO.REWORK_NONE).outputQuantity(BigDecimal.TEN)
                .lines(new ArrayList<>()).build();
        when(planCostVarianceService.computeForPlan(FACTORY, planId, false)).thenReturn(plan);
        when(productionBatchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any()))
                .thenReturn(List.of(batch(planId, productTypeId, batchProductName)));
        return service.getCostVarianceReport(FACTORY, DATE.minusDays(30), DATE);
    }

    @Test
    void anchorBatchShowsTheResolvedRawMaterialName() {
        when(planCostVarianceService.productName(FACTORY, ANCHOR_ID)).thenReturn("LY-FX-安格斯65碎肉");

        CostVarianceReportDTO r = report("P-ANCHOR", ANCHOR_ID, ANCHOR_NAME);

        assertThat(r.getProductVariances()).hasSize(1);
        assertThat(r.getProductVariances().getFirst().getProductName()).isEqualTo("LY-FX-安格斯65碎肉");
    }

    @Test
    void unresolvableAnchorStillNeverLeaksThePlaceholderPrefix() {
        when(planCostVarianceService.productName(FACTORY, ANCHOR_ID)).thenReturn(null);

        CostVarianceReportDTO r = report("P-ANCHOR", ANCHOR_ID, ANCHOR_NAME);

        assertThat(r.getProductVariances().getFirst().getProductName()).doesNotContain("__RAW_WORKFLOW_OWNER__");
    }

    @Test
    void ordinaryProductKeepsItsNameAndMissingNameFallsBackToUnknown() {
        when(planCostVarianceService.productName(FACTORY, "SKU-1")).thenReturn("卤牛肉");
        assertThat(report("P1", "SKU-1", "卤牛肉").getProductVariances().getFirst().getProductName())
                .as("阳性对照: 普通产品照旧").isEqualTo("卤牛肉");

        when(planCostVarianceService.productName(FACTORY, "SKU-2")).thenReturn(null);
        assertThat(report("P2", "SKU-2", null).getProductVariances().getFirst().getProductName())
                .isEqualTo("未知产品");
    }
}
