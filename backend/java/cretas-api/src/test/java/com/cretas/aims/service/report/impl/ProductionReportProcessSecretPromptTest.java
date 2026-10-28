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

        ProductionReportServiceImpl service = new ProductionReportServiceImpl(plans, mock(MaterialBatchRepository.class),
                mock(UserRepository.class), mock(EquipmentRepository.class), mock(SupplierRepository.class),
                mock(CustomerRepository.class), ai, mock(ProcessingService.class), mock(ProductionBatchRepository.class),
                mock(QualityInspectionRepository.class), mock(ShipmentRecordRepository.class),
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
}
