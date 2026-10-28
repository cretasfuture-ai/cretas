package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.processsecret.ProcessSecretReadTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 计划统计 (/production-plans/statistics): 已完成计划的成本合计。调用者对本厂某个保密产品无权时只算他能看的计划
 * 并注明「不含 N 个保密产品」; 负责人与没有保密产品的工厂照全部计划累加。
 */
@ExtendWith(MockitoExtension.class)
class ProductionPlanStatisticsProcessSecretTest {

    @Mock private ProductionPlanRepository productionPlanRepository;
    @InjectMocks private ProductionPlanServiceImpl service;

    private final List<ProductionPlan> planRows = List.of(
            done(plan(SECRET_PLAN, SECRET, null), SECRET_COST),
            done(plan(NORMAL_PLAN, NORMAL, null), NORMAL_COST),
            done(plan("PL-N2", NORMAL2, null), NORMAL2_COST),
            running(plan("PL-RUN", NORMAL, null), new BigDecimal("99.00")));   // 未完成: 不计入

    private static ProductionPlan done(ProductionPlan plan, BigDecimal material) {
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        plan.setActualMaterialCost(material);
        return plan;
    }

    private static ProductionPlan running(ProductionPlan plan, BigDecimal material) {
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setActualMaterialCost(material);
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statisticsAs(Set<String> protectedIds, AuthenticatedActor actor) {
        ProcessSecretLlmCostScope scope = scope(protectedIds, planRows);
        ReflectionTestUtils.setField(service, "llmCostScope", scope);
        when(productionPlanRepository.findAll(any(Specification.class))).thenReturn(planRows);
        try (var ignored = as(actor)) {
            return service.getProductionStatistics(FACTORY, null, null);
        }
    }

    /** 测试里逐行算: 已完成、非保密计划的成本之和。 */
    private BigDecimal visibleCompletedSum() {
        BigDecimal sum = BigDecimal.ZERO;
        for (ProductionPlan plan : planRows) {
            if (plan.getStatus() == ProductionPlanStatus.COMPLETED && !SECRET.equals(plan.getProductTypeId())) {
                sum = sum.add(plan.getActualMaterialCost());
            }
        }
        return sum;
    }

    @Test
    void anUnauthorizedColleagueGetsTheVisibleCompletedPlanCostWithANote() {
        Map<String, Object> stats = statisticsAs(Set.of(SECRET), sameFactoryUser());

        assertThat((BigDecimal) stats.get("totalCost")).isEqualByComparingTo(visibleCompletedSum());
        assertThat((String) stats.get("processSecretNote")).contains("不含 1 个保密产品");
        assertThat(stats.get("completedPlans")).isEqualTo(3L);   // 计数照常
    }

    @Test
    void platformAndOtherFactoryCallersAreTrimmedToo() {
        for (AuthenticatedActor actor : List.of(platform(), otherFactoryUser())) {
            assertThat((BigDecimal) statisticsAs(Set.of(SECRET), actor).get("totalCost"))
                    .as("caller %s", actor).isEqualByComparingTo(visibleCompletedSum());
        }
    }

    @Test
    void theOwnerAndAnUnprotectedFactoryGetAllCompletedPlans() {
        BigDecimal all = SECRET_COST.add(NORMAL_COST).add(NORMAL2_COST);
        Map<String, Object> owner = statisticsAs(Set.of(SECRET), owner());
        Map<String, Object> unprotected = statisticsAs(Set.of(), sameFactoryUser());

        assertThat((BigDecimal) owner.get("totalCost")).isEqualByComparingTo(all);
        assertThat(owner).doesNotContainKey("processSecretNote");
        assertThat((BigDecimal) unprotected.get("totalCost")).isEqualByComparingTo(all);
    }

    @Test
    void loadingThePlansForTheTrimmedTotalDoesNotMarkThemAsRead() {
        // 夹具外: 这里的计划仓库是 Mockito 桩, 让它像 PostLoad 一样走打标入口
        when(productionPlanRepository.findAll(any(Specification.class))).thenAnswer(inv -> {
            planRows.forEach(p -> com.cretas.aims.service.processsecret.ProcessSecretTestHooks.record(p));
            return planRows;
        });
        ReflectionTestUtils.setField(service, "llmCostScope", scope(Set.of(SECRET), planRows));
        try (var scopeOpen = ProcessSecretReadTracker.open(); var ignored = as(sameFactoryUser())) {
            Map<String, Object> stats = service.getProductionStatistics(FACTORY, null, null);
            assertThat(stats.get("totalPlans")).isEqualTo(4);   // 一定发生: 确实读了计划
            assertThat(ProcessSecretReadTracker.reads()).isEmpty();
        }
    }
}
