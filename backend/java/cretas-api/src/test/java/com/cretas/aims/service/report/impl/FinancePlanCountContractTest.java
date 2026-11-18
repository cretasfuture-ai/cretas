package com.cretas.aims.service.report.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.ProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LSM-COSTBI 第 12 项: 时间段成本分析屏「批次数量 / 平均单批成本」以前前端写死 0。
 * 财务报表现在给 planCount (结束时间在区间内的计划数, 与成本同口径) 和 avgCostPerPlan;
 * 有工艺保密时成本只算能看的计划, 计划数也只数能看的 (否则平均值分子分母不是同一组)。
 */
class FinancePlanCountContractTest {

    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final LocalDateTime yesterday = LocalDate.now().minusDays(1).atTime(10, 0);
    private final List<ProductionPlan> planRows = List.of(
            costed(plan(SECRET_PLAN, SECRET, yesterday), SECRET_COST),
            costed(plan(NORMAL_PLAN, NORMAL, yesterday), NORMAL_COST),
            costed(plan("PL-N2", NORMAL2, yesterday), NORMAL2_COST));
    private final BigDecimal factoryWideTotal = SECRET_COST.add(NORMAL_COST).add(NORMAL2_COST);

    private static ProductionPlan costed(ProductionPlan plan, BigDecimal material) {
        plan.setActualMaterialCost(material);
        return plan;
    }

    private ProductionReportServiceImpl service(Set<String> protectedIds, long endedPlans) {
        when(plans.findByFactoryId(FACTORY)).thenReturn(planRows);
        when(plans.calculateMaterialCostBetweenDates(eq(FACTORY), any(), any())).thenReturn(factoryWideTotal);
        when(plans.countByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any())).thenReturn(endedPlans);
        when(plans.countEndedBetweenDates(eq(FACTORY), any(), any())).thenReturn(endedPlans);
        ShipmentRecordRepository shipments = mock(ShipmentRecordRepository.class);
        when(shipments.calculateTotalRevenue(eq(FACTORY), any(), any())).thenReturn(new BigDecimal("20000.00"));
        ProductionReportServiceImpl service = new ProductionReportServiceImpl(plans, mock(MaterialBatchRepository.class),
                mock(UserRepository.class), mock(EquipmentRepository.class), mock(SupplierRepository.class),
                mock(CustomerRepository.class), new AIAnalysisService(), mock(ProcessingService.class),
                mock(ProductionBatchRepository.class), mock(QualityInspectionRepository.class), shipments,
                mock(TimeClockRecordRepository.class), mock(ReworkRecordRepository.class), mock(SalesOrderRepository.class),
                mock(ProductionLineRepository.class), mock(FactorySettingsRepository.class));
        ReflectionTestUtils.setField(service, "llmCostScope", scope(protectedIds, planRows));
        return service;
    }

    private <T> T callAs(com.cretas.aims.security.AuthenticatedActor actor, java.util.function.Supplier<T> call) {
        try (var ignored = as(actor)) {
            return call.get();
        }
    }

    @Test
    void ownerSeesAllEndedPlans_andAverageIsTotalOverThatCount() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET), 3);
        LocalDate from = LocalDate.now().minusDays(7);
        Map<String, Object> owner = callAs(owner(), () -> service.getFinanceReport(FACTORY, from, LocalDate.now()));

        assertThat(owner.get("planCount")).isEqualTo(3L);
        assertThat((BigDecimal) owner.get("avgCostPerPlan"))
                .isEqualByComparingTo(factoryWideTotal.divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_UP));
        verify(plans).countEndedBetweenDates(eq(FACTORY), eq(from.atStartOfDay()), eq(LocalDate.now().atTime(23, 59, 59)));
    }

    @Test
    void unauthorizedCaller_countsOnlyVisiblePlans_soAverageMatchesTheVisibleTotal() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(SECRET), 3);
        LocalDate from = LocalDate.now().minusDays(7);
        Map<String, Object> colleague = callAs(sameFactoryUser(),
                () -> service.getFinanceReport(FACTORY, from, LocalDate.now()));

        BigDecimal visible = NORMAL_COST.add(NORMAL2_COST);
        assertThat((BigDecimal) colleague.get("totalCost")).isEqualByComparingTo(visible);
        assertThat(colleague.get("planCount")).isEqualTo(2L);   // 3 − 1 个保密计划
        assertThat((BigDecimal) colleague.get("avgCostPerPlan"))
                .isEqualByComparingTo(visible.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP));
        assertThat(colleague.toString()).doesNotContain(SECRET_COST.toPlainString());
    }

    @Test
    void noEndedPlans_averageIsNull_notZero() throws Exception {
        ProductionReportServiceImpl service = service(Set.of(), 0);
        LocalDate from = LocalDate.now().minusDays(7);
        Map<String, Object> r = callAs(owner(), () -> service.getFinanceReport(FACTORY, from, LocalDate.now()));
        assertThat(r.get("planCount")).isEqualTo(0L);
        assertThat(r).containsKey("avgCostPerPlan");
        assertThat(r.get("avgCostPerPlan")).isNull();
    }
}
