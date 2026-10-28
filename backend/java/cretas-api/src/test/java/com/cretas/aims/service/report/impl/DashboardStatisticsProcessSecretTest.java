package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.DashboardStatisticsDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.*;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 仪表盘财务合计 (按工厂缓存): 调用者对本厂某个保密产品无权时, 成本只算他能看的计划并注明「不含 N 个保密产品」,
 * 营收含保密产品而成本不含时利润 / 利润率不给; 负责人与没有保密产品的工厂照原查询。
 */
class DashboardStatisticsProcessSecretTest {

    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final ShipmentRecordRepository shipments = mock(ShipmentRecordRepository.class);
    /** 今天凌晨结束的计划: 落在本年与本月两个区间里, 与运行日期无关。 */
    private final LocalDateTime earlyToday = LocalDate.now().atTime(0, 0, 1);
    private final BigDecimal factoryWideTotal = SECRET_COST.add(NORMAL_COST).add(NORMAL2_COST);
    private final BigDecimal revenue = new BigDecimal("20000.00");
    private final List<ProductionPlan> planRows = List.of(
            costed(plan(SECRET_PLAN, SECRET, earlyToday), SECRET_COST),
            costed(plan(NORMAL_PLAN, NORMAL, earlyToday), NORMAL_COST),
            costed(plan("PL-N2", NORMAL2, earlyToday), NORMAL2_COST));

    private static ProductionPlan costed(ProductionPlan plan, BigDecimal material) {
        plan.setActualMaterialCost(material);
        return plan;
    }

    private DashboardStatisticsDTO.FinanceStatistics financeAs(Set<String> protectedIds, AuthenticatedActor actor) {
        when(plans.findByFactoryId(FACTORY)).thenReturn(planRows);
        when(plans.calculateTotalCostBetweenDates(eq(FACTORY), any(), any())).thenReturn(factoryWideTotal);
        when(shipments.calculateTotalRevenue(eq(FACTORY), any(), any())).thenReturn(revenue);
        DashboardStatisticsServiceImpl service = new DashboardStatisticsServiceImpl(plans, mock(MaterialBatchRepository.class),
                mock(UserRepository.class), mock(EquipmentRepository.class), mock(SupplierRepository.class),
                mock(CustomerRepository.class), mock(ProductionBatchRepository.class),
                mock(QualityInspectionRepository.class), shipments);
        ProcessSecretLlmCostScope scope = scope(protectedIds, planRows);
        ReflectionTestUtils.setField(service, "llmCostScope", scope);
        try (var ignored = as(actor)) {
            return service.getFinanceStatisticsAsync(FACTORY).join();
        }
    }

    /** 测试里逐行算: 非保密计划的成本之和。 */
    private BigDecimal visibleSum() {
        BigDecimal sum = BigDecimal.ZERO;
        for (ProductionPlan plan : planRows) {
            if (!SECRET.equals(plan.getProductTypeId())) sum = sum.add(plan.getActualMaterialCost());
        }
        return sum;
    }

    @Test
    void anUnauthorizedColleagueSeesTheVisibleCostWithANoteAndNoProfit() {
        DashboardStatisticsDTO.FinanceStatistics finance = financeAs(Set.of(SECRET), sameFactoryUser());

        assertThat(finance.getTotalCost()).isEqualByComparingTo(visibleSum());
        assertThat(finance.getMonthlyCost()).isEqualByComparingTo(visibleSum());
        assertThat(finance.getTotalProfit()).isNull();
        assertThat(finance.getMonthlyProfit()).isNull();
        assertThat(finance.getProfitMargin()).isNull();
        assertThat(finance.getProcessSecretNote()).contains("不含 1 个保密产品");
        assertThat(finance.getTotalRevenue()).isEqualByComparingTo(revenue);   // 营收照常
    }

    @Test
    void platformAndOtherFactoryCallersAreTrimmedToo() {
        for (AuthenticatedActor actor : List.of(platform(), otherFactoryUser())) {
            DashboardStatisticsDTO.FinanceStatistics finance = financeAs(Set.of(SECRET), actor);
            assertThat(finance.getTotalCost()).as("caller %s", actor).isEqualByComparingTo(visibleSum());
            assertThat(finance.getTotalProfit()).as("caller %s", actor).isNull();
        }
    }

    @Test
    void theOwnerGetsTheFactoryWideTotalsAndProfit() {
        DashboardStatisticsDTO.FinanceStatistics finance = financeAs(Set.of(SECRET), owner());

        assertThat(finance.getTotalCost()).isEqualByComparingTo(factoryWideTotal);
        assertThat(finance.getTotalProfit()).isEqualByComparingTo(revenue.subtract(factoryWideTotal));
        assertThat(finance.getProfitMargin()).isNotNull();
        assertThat(finance.getProcessSecretNote()).isNull();
    }

    @Test
    void negativeControlAFactoryWithoutProtectedProductsIsUnchanged() {
        DashboardStatisticsDTO.FinanceStatistics finance = financeAs(Set.of(), sameFactoryUser());

        assertThat(finance.getTotalCost()).isEqualByComparingTo(factoryWideTotal);
        assertThat(finance.getTotalProfit()).isEqualByComparingTo(revenue.subtract(factoryWideTotal));
        assertThat(finance.getProcessSecretNote()).isNull();
    }
}
