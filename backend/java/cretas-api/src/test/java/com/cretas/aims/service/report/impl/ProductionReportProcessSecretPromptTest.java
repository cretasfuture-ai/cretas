package com.cretas.aims.service.report.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 出口 4: 异常检测与成本预测把全厂总成本发给模型。走真实入口 getAnomalyReport / getForecastReport
 * 与真实 {@link AIAnalysisService}, 断言打在发给 Python 分析服务的正文上。
 */
class ProductionReportProcessSecretPromptTest {

    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final PythonSmartBIClient python = mock(PythonSmartBIClient.class);
    private final ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
    private final ShipmentRecordRepository shipments = mock(ShipmentRecordRepository.class);
    private final LocalDateTime yesterday = LocalDate.now().minusDays(1).atTime(10, 0);
    /** 含保密产品的全厂总成本 (原查询的结果) —— 不许出现在提示词里。 */
    private final BigDecimal factoryWideTotal = SECRET_COST.add(NORMAL_COST).add(NORMAL2_COST);
    private final List<ProductionPlan> planRows = List.of(
            costed(plan(SECRET_PLAN, SECRET, yesterday), SECRET_COST),
            costed(plan(NORMAL_PLAN, NORMAL, yesterday), NORMAL_COST),
            costed(plan("PL-N2", NORMAL2, yesterday), NORMAL2_COST));

    private static ProductionPlan costed(ProductionPlan plan, BigDecimal material) {
        plan.setActualMaterialCost(material);
        return plan;
    }

    private ProductionReportServiceImpl service(Set<String> protectedIds) throws Exception {
        return service(protectedIds, planRows);
    }

    private ProductionReportServiceImpl service(Set<String> protectedIds, List<ProductionPlan> planRows) throws Exception {
        AIAnalysisService ai = new AIAnalysisService();
        DashScopeConfig dashScope = mock(DashScopeConfig.class);
        when(dashScope.isAvailable()).thenReturn(false);
        ReflectionTestUtils.setField(ai, "dashScopeConfig", dashScope);
        ReflectionTestUtils.setField(ai, "pythonSmartBIClient", python);
        when(python.analyzeGeneral(anyString(), nullable(String.class), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder().success(true).answer("好的").build());
        when(plans.findByFactoryId(FACTORY)).thenReturn(planRows);
        when(plans.calculateTotalCostBetweenDates(eq(FACTORY), any(), any())).thenReturn(factoryWideTotal);
        when(plans.calculateOutputBetweenDates(eq(FACTORY), any(), any())).thenReturn(new BigDecimal("400"));
        // 原查询的分项 (全厂, 含保密产品): 四项里只有材料有值
        when(plans.calculateMaterialCostBetweenDates(eq(FACTORY), any(), any())).thenReturn(factoryWideTotal);
        when(shipments.calculateTotalRevenue(eq(FACTORY), any(), any())).thenReturn(new BigDecimal("20000.00"));

        ProductionReportServiceImpl service = new ProductionReportServiceImpl(plans, mock(MaterialBatchRepository.class),
                mock(UserRepository.class), mock(EquipmentRepository.class), mock(SupplierRepository.class),
                mock(CustomerRepository.class), ai, mock(ProcessingService.class), batchRepository,
                mock(QualityInspectionRepository.class), shipments,
                mock(TimeClockRecordRepository.class), mock(ReworkRecordRepository.class), mock(SalesOrderRepository.class),
                mock(ProductionLineRepository.class), mock(FactorySettingsRepository.class));
        ProcessSecretLlmCostScope scope = scope(protectedIds, planRows);
        ReflectionTestUtils.setField(service, "llmCostScope", scope);
        ReflectionTestUtils.setField(ai, "llmCostScope", scope);
        return service;
    }

    private String sentToModel() throws Exception {
        ArgumentCaptor<PythonGeneralAnalysisRequest> request = ArgumentCaptor.forClass(PythonGeneralAnalysisRequest.class);
        verify(python).analyzeGeneral(eq(FACTORY), nullable(String.class), request.capture());
        return request.getValue().getMessage();
    }

    /** 测试里逐行算: 非保密计划的成本之和。 */
    private BigDecimal nonProtectedPlanTotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (ProductionPlan plan : planRows) {
            if (SECRET.equals(plan.getProductTypeId())) continue;
            sum = sum.add(plan.getActualMaterialCost());
        }
        return sum;
    }

    @Test
    void anomalyPromptTotalCostIsTheSumOfNonProtectedPlans() throws Exception {
        service(Set.of(SECRET)).getAnomalyReport(FACTORY, LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(sentToModel())
                .contains("- 总成本：" + nonProtectedPlanTotal().toPlainString() + "\n")
                .contains("不含 1 个保密产品")
                .doesNotContain(factoryWideTotal.toPlainString(), SECRET_COST.toPlainString());
    }

    @Test
    void anomalyForTheOwnerUsesTheFactoryWideTotal() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));
        try (var ignored = as(owner())) {
            service.getAnomalyReport(FACTORY, LocalDate.now().minusDays(7), LocalDate.now());
        }

        assertThat(sentToModel()).contains("- 总成本：" + factoryWideTotal.toPlainString()).doesNotContain("不含");
    }

    @Test
    void anomalyNoteCountsTheProtectedProductOnceEvenWithAnAnchorPlan() throws Exception {
        // 多产出计划锚在原料上、目标成品是保密产品: 两个计划都排除, 但保密产品只有 1 个
        ProductionPlan anchor = costed(plan("PL-ANCHOR", "RAW-ANCHOR", yesterday), new BigDecimal("100.00"));
        anchor.setTargetFinishedGoodIds(List.of(SECRET));
        List<ProductionPlan> withAnchor = new java.util.ArrayList<>(planRows);
        withAnchor.add(anchor);

        service(Set.of(SECRET), withAnchor).getAnomalyReport(FACTORY, LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(sentToModel()).contains("- 总成本：" + nonProtectedPlanTotal().toPlainString() + "\n")
                .contains("不含 1 个保密产品").doesNotContain("不含 2 个保密产品");
    }

    @Test
    void anomalyNegativeControlUsesTheFactoryWideTotalWhenNothingIsProtected() throws Exception {
        service(Set.of()).getAnomalyReport(FACTORY, LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(sentToModel()).contains("- 总成本：" + factoryWideTotal.toPlainString()).doesNotContain("保密产品");
    }

    @Test
    void costForecastPromptOnlyCarriesNonProtectedHistory() throws Exception {
        service(Set.of(SECRET)).getForecastReport(FACTORY, "cost", 7);

        BigDecimal total = nonProtectedPlanTotal();
        BigDecimal average = total.divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
        assertThat(sentToModel())
                .contains("历史数据总计：" + total.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .contains("日均值：" + average.toPlainString())
                .contains("不含 1 个保密产品")
                .doesNotContain(SECRET_COST.toPlainString());
    }

    @Test
    void costForecastNegativeControlUsesTheOriginalDailyQuery() throws Exception {
        service(Set.of()).getForecastReport(FACTORY, "cost", 7);

        BigDecimal thirtyDays = factoryWideTotal.multiply(BigDecimal.valueOf(30));
        assertThat(sentToModel()).contains("历史数据总计：" + thirtyDays.toPlainString()).doesNotContain("保密产品");
    }

    // ---------------- 报表数据本身 (页面与 AI 工具拿到的) ----------------

    private <T> T callAs(com.cretas.aims.security.AuthenticatedActor actor, java.util.function.Supplier<T> call) {
        try (var ignored = com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.as(actor)) {
            return call.get();
        }
    }

    @Test
    void productionReportTotalCostIsTheVisibleSumForAnUnauthorizedCallerAndTheFullTotalForTheOwner() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));
        LocalDate from = LocalDate.now().minusDays(7);

        Map<String, Object> colleague = callAs(sameFactoryUser(), () -> service.getProductionReport(FACTORY, from, LocalDate.now()));
        Map<String, Object> owner = callAs(owner(), () -> service.getProductionReport(FACTORY, from, LocalDate.now()));

        assertThat((BigDecimal) colleague.get("totalCost")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat((String) colleague.get("processSecretNote")).contains("不含 1 个保密产品");
        assertThat((BigDecimal) owner.get("totalCost")).isEqualByComparingTo(factoryWideTotal);
        assertThat(owner).doesNotContainKey("processSecretNote");
    }

    @Test
    void financeReportCostsAreVisibleRowsOnlyAndProfitIsNotDerivedFromAPartialCost() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));
        LocalDate from = LocalDate.now().minusDays(7);

        Map<String, Object> colleague = callAs(sameFactoryUser(), () -> service.getFinanceReport(FACTORY, from, LocalDate.now()));
        Map<String, Object> owner = callAs(owner(), () -> service.getFinanceReport(FACTORY, from, LocalDate.now()));

        assertThat((BigDecimal) colleague.get("materialCost")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat((BigDecimal) colleague.get("totalCost")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat(colleague.get("totalProfit")).isNull();
        assertThat(colleague.get("profitMargin")).isNull();
        assertThat((String) colleague.get("processSecretNote")).contains("不含 1 个保密产品");
        assertThat((BigDecimal) owner.get("materialCost")).isEqualByComparingTo(factoryWideTotal);
        assertThat((BigDecimal) owner.get("totalProfit")).isEqualByComparingTo(new BigDecimal("20000.00").subtract(factoryWideTotal));
    }

    @Test
    void costAnalysisComparativeAndPeriodComparisonUseVisibleRowsForUnauthorizedCallers() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));
        LocalDate from = LocalDate.now().minusDays(7);
        LocalDate to = LocalDate.now();

        Map<String, Object> analysis = callAs(platform(), () -> service.getCostAnalysisReport(FACTORY, from, to));
        Map<String, Object> comparative = callAs(sameFactoryUser(), () -> service.getComparativeAnalysis(FACTORY, from, to, from, to));
        Map<String, Object> period = callAs(otherFactoryUser(), () -> service.getPeriodComparisonReport(FACTORY, from, to, from, to));

        assertThat((BigDecimal) analysis.get("totalCost")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat((BigDecimal) ((Map<?, ?>) comparative.get("period1")).get("cost")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat((BigDecimal) ((Map<?, ?>) period.get("period2")).get("cost")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat(comparative.toString()).doesNotContain(factoryWideTotal.toPlainString());
        Map<String, Object> ownerAnalysis = callAs(owner(), () -> service.getCostAnalysisReport(FACTORY, from, to));
        assertThat((BigDecimal) ownerAnalysis.get("totalCost")).isEqualByComparingTo(factoryWideTotal);
    }

    @Test
    void trendAndForecastDataReturnedToTheToolOnlyCarryVisibleCosts() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));

        Map<String, Object> trend = callAs(sameFactoryUser(), () -> service.getTrendAnalysisReport(FACTORY, "cost", 7));
        Map<String, Object> forecast = callAs(sameFactoryUser(), () -> service.getForecastReport(FACTORY, "cost", 7));
        Map<String, Object> summaryForecast = callAs(sameFactoryUser(), () -> service.getForecastReport(FACTORY, 7));

        BigDecimal trendTotal = ((List<?>) trend.get("trendData")).stream()
                .map(p -> (BigDecimal) ((Map<?, ?>) p).get("value")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(trendTotal).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat((String) trend.get("processSecretNote")).contains("不含 1 个保密产品");
        assertThat((BigDecimal) ((Map<?, ?>) forecast.get("historicalSummary")).get("total")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat(summaryForecast.get("expectedProfit")).isNull();
        BigDecimal ownerTrend = ((List<?>) callAs(owner(), () -> service.getTrendAnalysisReport(FACTORY, "cost", 7)).get("trendData"))
                .stream().map(p -> (BigDecimal) ((Map<?, ?>) p).get("value")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ownerTrend).isEqualByComparingTo(factoryWideTotal.multiply(BigDecimal.valueOf(7)));   // 原按天查询
    }

    @Test
    void trendReportWithoutAPeriodDefaultsToThirtyDaysInsteadOfThrowing() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));

        Map<String, Object> colleague = callAs(sameFactoryUser(), () -> service.getTrendAnalysisReport(FACTORY, "cost", null));
        Map<String, Object> owner = callAs(owner(), () -> service.getTrendAnalysisReport(FACTORY, "production", null));

        assertThat((List<?>) colleague.get("trendData")).hasSize(30);
        assertThat(colleague.get("period")).isEqualTo(30);
        assertThat((List<?>) owner.get("trendData")).hasSize(30);
    }

    @Test
    void anomalyDataContextCarriesTheVisibleTotalForTheTool() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));
        Map<String, Object> report = callAs(sameFactoryUser(),
                () -> service.getAnomalyReport(FACTORY, LocalDate.now().minusDays(7), LocalDate.now()));

        Map<?, ?> dataContext = (Map<?, ?>) report.get("dataContext");
        assertThat((BigDecimal) dataContext.get("totalCost")).isEqualByComparingTo(nonProtectedPlanTotal());
        assertThat((String) dataContext.get("processSecretNote")).contains("不含 1 个保密产品");
    }

    @Test
    void costVarianceTotalsAreVisibleBatchesOnlyAndTheProtectedUnitCostIsWithheld() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any())).thenReturn(List.of(
                batch(1L, SECRET, SECRET_PLAN, SECRET_COST, new BigDecimal("100")),
                batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, new BigDecimal("100")),
                batch(3L, NORMAL2, null, NORMAL2_COST, new BigDecimal("200"))));
        LocalDate from = LocalDate.now().minusDays(7);

        com.cretas.aims.dto.report.CostVarianceReportDTO colleague =
                callAs(sameFactoryUser(), () -> service.getCostVarianceReport(FACTORY, from, LocalDate.now()));
        com.cretas.aims.dto.report.CostVarianceReportDTO owner =
                callAs(owner(), () -> service.getCostVarianceReport(FACTORY, from, LocalDate.now()));

        assertThat(colleague.getTotalActualCost()).isEqualByComparingTo(NORMAL_COST.add(NORMAL2_COST));
        assertThat(colleague.getProcessSecretNote()).contains("不含 1 个保密产品");
        assertThat(colleague.getProductVariances()).filteredOn(v -> SECRET.equals(v.getProductTypeId()))
                .singleElement().satisfies(v -> {
                    assertThat(v.getActualUnitCost()).isNull();
                    assertThat(v.getQuantity()).isEqualByComparingTo("100");   // 数量照常
                });
        assertThat(owner.getTotalActualCost()).isEqualByComparingTo(factoryWideTotal);
        assertThat(owner.getProcessSecretNote()).isNull();
    }

    private void stubBatches() {
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any())).thenReturn(List.of(
                batch(1L, SECRET, SECRET_PLAN, SECRET_COST, new BigDecimal("100")),
                batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, new BigDecimal("100")),
                batch(3L, NORMAL2, null, NORMAL2_COST, new BigDecimal("200"))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void kpiUnitCostIsNotAPartialCostOverTheFullOutput() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET));
        stubBatches();

        Map<String, Object> colleague = callAs(sameFactoryUser(), () -> service.getKPIMetrics(FACTORY, LocalDate.now()));
        Map<String, Object> owner = callAs(owner(), () -> service.getKPIMetrics(FACTORY, LocalDate.now()));
        com.cretas.aims.dto.report.KpiMetricsDTO colleagueDto =
                callAs(sameFactoryUser(), () -> service.getKpiMetricsDTO(FACTORY, LocalDate.now()));
        com.cretas.aims.dto.report.KpiMetricsDTO ownerDto =
                callAs(owner(), () -> service.getKpiMetricsDTO(FACTORY, LocalDate.now()));

        // 无权: 成本只算了能看的批次而产出是全厂的, 相除不是单位成本 —— 不给; 有权: 全厂成本 / 全厂产出
        assertThat(((Map<String, Object>) colleague.get("cost")).get("unitCost")).isNull();
        assertThat((BigDecimal) ((Map<String, Object>) owner.get("cost")).get("unitCost"))
                .isEqualByComparingTo(factoryWideTotal.divide(new BigDecimal("400"), 2, java.math.RoundingMode.HALF_UP));
        assertThat(colleagueDto.getUnitCost()).isNull();
        assertThat(ownerDto.getUnitCost()).isNotNull().isPositive();
    }

    @Test
    void comparativeAnalysisSurvivesASecondPeriodThatOnlyHasHiddenPlans() throws Exception {
        LocalDateTime tenDaysAgo = LocalDate.now().minusDays(10).atTime(10, 0);
        List<ProductionPlan> rows = List.of(
                costed(plan(SECRET_PLAN, SECRET, tenDaysAgo), SECRET_COST),       // 第二期只有保密计划
                costed(plan(NORMAL_PLAN, NORMAL, yesterday), NORMAL_COST));
        ProductionReportServiceImpl service = service(Set.of(SECRET), rows);
        LocalDate today = LocalDate.now();

        Map<String, Object> analysis = callAs(sameFactoryUser(), () -> service.getComparativeAnalysis(FACTORY,
                today.minusDays(3), today, today.minusDays(12), today.minusDays(8)));

        assertThat((BigDecimal) ((Map<?, ?>) analysis.get("period1")).get("cost")).isEqualByComparingTo(NORMAL_COST);
        assertThat((BigDecimal) ((Map<?, ?>) analysis.get("period2")).get("cost")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((Map<?, ?>) analysis.get("changeRate")).containsKey("costChange")).isFalse();   // 第二期没有可见成本, 不算环比
    }
}
