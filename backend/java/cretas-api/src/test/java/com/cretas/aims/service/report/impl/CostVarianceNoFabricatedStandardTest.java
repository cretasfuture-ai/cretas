package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.dto.report.KpiMetricsDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.QualityInspection;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 🔒 成本差异报表不许拿「实际成本 × 0.95」冒充标准成本。
 *
 * <p>原实现: 有实际成本时 BOM 成本 = 实际 × 0.95, 没有时 = 实际产量 × 10;
 * 产品级差异原因再按 60/30/10 固定切成材料/人工/制造费用。于是差异率恒约 5.26%,
 * 标准随实际同比涨落, 而 KPI 综合评分还把这个恒定值当成本控制得分算了进去。</p>
 *
 * <p>止血口径: 没有独立于实际成本的标准成本 ⇒ 标准 / 差异 / 差异率为 null,
 * {@code comparable=false} + 原因; KPI 把成本这一维排除出加权(不加分, 也不按 0 记)。</p>
 *
 * <p>每条「该为空」都配一条阳性读数: 实际成本照样算得出来(⛔ 防「整张报表返回 null」也能过)。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("成本差异: 没有独立标准成本时说「无法比较」, 不拿实际 × 0.95 冒充")
class CostVarianceNoFabricatedStandardTest {

    private static final String FACTORY = "F006";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 24);

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

    @InjectMocks
    ProductionReportServiceImpl service;

    private static ProductionBatch batch(String productTypeId, String totalCost, String actualQuantity) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(FACTORY);
        b.setProductTypeId(productTypeId);
        b.setProductName("卤猪蹄");
        b.setTotalCost(totalCost == null ? null : new BigDecimal(totalCost));
        b.setActualQuantity(actualQuantity == null ? null : new BigDecimal(actualQuantity));
        return b;
    }

    private void givenBatches(ProductionBatch... batches) {
        when(productionBatchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any()))
                .thenReturn(new ArrayList<>(List.of(batches)));
    }

    private CostVarianceReportDTO reportWithActualCost(String actualCost) {
        givenBatches(batch("SKU-1", actualCost, "100"));
        return service.getCostVarianceReport(FACTORY, DATE.minusDays(30), DATE);
    }

    @Test
    @DisplayName("实际 1000 与 2000: 标准成本都不随实际同比变化 —— 两次都是 null, 不是 950 / 1900")
    void standardCostDoesNotScaleWithActualCost() {
        CostVarianceReportDTO r1000 = reportWithActualCost("1000");
        CostVarianceReportDTO r2000 = reportWithActualCost("2000");

        System.out.println("[observe] actual=1000 -> bom=" + r1000.getTotalBomCost()
                + " rate=" + r1000.getTotalVarianceRate() + " comparable=" + r1000.getComparable());
        System.out.println("[observe] actual=2000 -> bom=" + r2000.getTotalBomCost()
                + " rate=" + r2000.getTotalVarianceRate() + " comparable=" + r2000.getComparable());

        // 阳性读数: 实际成本是真实的, 照样算得出来
        assertThat(r1000.getTotalActualCost()).isEqualByComparingTo("1000.00");
        assertThat(r2000.getTotalActualCost()).isEqualByComparingTo("2000.00");

        assertThat(r1000.getTotalBomCost()).as("原来 = 1000 × 0.95 = 950").isNull();
        assertThat(r2000.getTotalBomCost()).as("原来 = 2000 × 0.95 = 1900").isNull();
        assertThat(r1000.getTotalVariance()).isNull();
        assertThat(r2000.getTotalVariance()).isNull();
    }

    @Test
    @DisplayName("不可比较时: 差异率为 null, comparable=false, 原因是「缺少独立标准成本」—— ⛔ 不是恒定 5.26%")
    void notComparableMeansNullRateAndExplicitReason() {
        CostVarianceReportDTO r = reportWithActualCost("1000");

        assertThat(r.getTotalVarianceRate()).as("原来恒约 5.26 (= 1/0.95 - 1)").isNull();
        assertThat(r.getComparable()).isFalse();
        assertThat(r.getComparabilityReason()).contains("缺少独立标准成本");
        assertThat(r.getVarianceStatus())
                .as("没有差异率就不能判 NORMAL —— 原来 5.26% 被判成 WARNING")
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("产品级: 单位标准成本 / 差异 / 差异率 / 差异原因全为 null; 实际单位成本照算")
    void productLevelHasNoFabricatedStandardOrReason() {
        CostVarianceReportDTO r = reportWithActualCost("1000");

        assertThat(r.getProductVariances()).hasSize(1);
        CostVarianceReportDTO.ProductCostVariance p = r.getProductVariances().get(0);
        assertThat(p.getActualUnitCost()).as("1000 / 100 —— 真实读数").isEqualByComparingTo("10.00");
        assertThat(p.getBomUnitCost()).as("原来 = 实际单位成本 × 0.95").isNull();
        assertThat(p.getUnitVariance()).isNull();
        assertThat(p.getVarianceRate()).isNull();
        assertThat(p.getTotalVariance()).isNull();
        assertThat(p.getVarianceReason())
                .as("原来按 60/30/10 切出「材料超支, 人工超支, 制造费用超支」")
                .isNull();
        assertThat(p.getComparable()).isFalse();
        assertThat(r.getAnomalyProducts()).as("没有差异率就没有「异常产品」").isEmpty();
    }

    @Test
    @DisplayName("缺实际成本的批次: 不再拿 实际产量 × 10 当标准成本")
    void missingActualCostDoesNotFallBackToQuantityTimesTen() {
        givenBatches(batch("SKU-1", null, "100"));

        CostVarianceReportDTO r = service.getCostVarianceReport(FACTORY, DATE.minusDays(30), DATE);

        assertThat(r.getTotalBomCost()).as("原来 = 100 × 10 = 1000").isNull();
        assertThat(r.getTotalVariance()).as("原来 = 0 - 1000 = -1000").isNull();
        assertThat(r.getTotalVarianceRate()).as("原来 = -100%").isNull();
        assertThat(r.getTotalActualCost()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("KPI: 成本不可比较时既不加分也不记 0 分 —— 综合评分只由真实输入(FPY 90)构成")
    void kpiScoreNeitherRewardsNorZeroesAnIncomparableCostVariance() {
        givenBatches(batch("SKU-1", "1000", "100"));
        List<QualityInspection> inspections = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            QualityInspection qi = new QualityInspection();
            qi.setResult(i < 9 ? "PASS" : "FAIL");
            inspections.add(qi);
        }
        when(qualityInspectionRepository.findByFactoryIdAndDateRange(eq(FACTORY), any(), any()))
                .thenReturn(inspections);

        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);
        System.out.println("[observe] kpi bomVarianceRate=" + kpi.getBomVarianceRate()
                + " fpy=" + kpi.getFpy() + " overallScore=" + kpi.getOverallScore());

        assertThat(kpi.getFpy()).as("阳性读数: FPY 是真实输入").isEqualByComparingTo("90.00");
        assertThat(kpi.getBomVarianceRate()).as("原来恒 5.26").isNull();
        // 原来: 成本得分 = 100 - 5.26*10 = 47.4, 权重 15 ⇒ (90*.15 + 47.4*.15)*100/30 = 68.70
        // 若把「不可比较」记成 0 分: (90*.15 + 0)*100/30 = 45.00
        // 正确: 成本这一维不进加权 ⇒ 只剩 FPY ⇒ 90.00
        assertThat(kpi.getOverallScore()).isEqualByComparingTo("90.00");
    }
}
