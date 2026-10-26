package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.SemiFinishedYieldStatsDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.impl.SemiFinishedYieldStatsServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("半成品 SKU 全历史加权出成率")
class SemiFinishedYieldStatsServiceImplTest {

    @Mock
    private ProductionReportRepository productionReportRepository;

    @Mock
    private ProductionReportRepository.SemiFinishedYieldAggregate aggregate;

    @Mock
    private ProcessSecretAccessService accessService;

    @Mock
    private com.cretas.aims.repository.ProductionPlanRepository planRepository;

    private SemiFinishedYieldStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SemiFinishedYieldStatsServiceImpl(productionReportRepository,
                new ProcessSecretAggregateExclusions(accessService, planRepository));
    }

    @Test
    @DisplayName("用总产出除以总投入，不平均各批次百分比")
    void calculatesWeightedYieldFromAggregateSums() {
        when(productionReportRepository.aggregateSettledSemiFinishedYield("F006", "SKU-SEMI", List.of("__none__")))
                .thenReturn(aggregate);
        when(aggregate.getTotalInputKg()).thenReturn(new BigDecimal("110.000000"));
        when(aggregate.getTotalOutputKg()).thenReturn(new BigDecimal("60.000000"));
        when(aggregate.getBatchCount()).thenReturn(2L);

        SemiFinishedYieldStatsDTO result = service.getStats("F006", "SKU-SEMI");

        assertThat(result.getFactoryId()).isEqualTo("F006");
        assertThat(result.getSemiFinishedSkuId()).isEqualTo("SKU-SEMI");
        assertThat(result.getTotalInputKg()).isEqualByComparingTo("110.000000");
        assertThat(result.getTotalOutputKg()).isEqualByComparingTo("60.000000");
        assertThat(result.getWeightedYieldRate()).isEqualByComparingTo("0.545455");
        assertThat(result.getBatchCount()).isEqualTo(2L);
        assertThat(result.getSource()).isEqualTo("SETTLED_PROCESS_SHEET_ROWS");
    }

    @Test
    @DisplayName("无有效已小结批次时返回 null 出成率，不伪造 0%")
    void returnsNullRateWhenThereAreNoValidBatches() {
        when(productionReportRepository.aggregateSettledSemiFinishedYield("F006", "SKU-EMPTY", List.of("__none__")))
                .thenReturn(aggregate);
        when(aggregate.getTotalInputKg()).thenReturn(BigDecimal.ZERO);
        when(aggregate.getTotalOutputKg()).thenReturn(BigDecimal.ZERO);
        when(aggregate.getBatchCount()).thenReturn(0L);

        SemiFinishedYieldStatsDTO result = service.getStats("F006", "SKU-EMPTY");

        assertThat(result.getBatchCount()).isZero();
        assertThat(result.getWeightedYieldRate()).isNull();
        assertThat(result.getTotalInputKg()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTotalOutputKg()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("工艺保密: 半成品用在无权保密产品的计划里时, 那些计划的行不进全历史汇总 (第三轮 B)")
    void rowsOfPlansThatInvolveHiddenProtectedProductsAreLeftOut() {
        when(accessService.protectedProductTypeIds("F006")).thenReturn(Set.of("P17", "P18"));
        when(accessService.filterPermitted(any(), eq("F006"), anyList(),
                eq(com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL), eq(false), anyString()))
                .thenReturn(List.of("P18"));
        com.cretas.aims.entity.ProductionPlan hidden = new com.cretas.aims.entity.ProductionPlan();
        hidden.setId("plan-hidden");
        hidden.setProductTypeId("P17");
        com.cretas.aims.entity.ProductionPlan multiOutput = new com.cretas.aims.entity.ProductionPlan();
        multiOutput.setId("plan-multi");
        multiOutput.setProductTypeId("RM-BEEF");
        multiOutput.setTargetFinishedGoodIds(List.of("Q-OPEN", "P17"));
        com.cretas.aims.entity.ProductionPlan permitted = new com.cretas.aims.entity.ProductionPlan();
        permitted.setId("plan-permitted");
        permitted.setProductTypeId("P18");
        com.cretas.aims.entity.ProductionPlan open = new com.cretas.aims.entity.ProductionPlan();
        open.setId("plan-open");
        open.setProductTypeId("Q-OPEN");
        when(planRepository.findByFactoryId("F006")).thenReturn(List.of(hidden, multiOutput, permitted, open));
        when(productionReportRepository.aggregateSettledSemiFinishedYield("F006", "SKU-SEMI",
                List.of("plan-hidden", "plan-multi"))).thenReturn(aggregate);
        when(aggregate.getTotalInputKg()).thenReturn(new BigDecimal("50"));
        when(aggregate.getTotalOutputKg()).thenReturn(new BigDecimal("40"));
        when(aggregate.getBatchCount()).thenReturn(1L);

        SemiFinishedYieldStatsDTO result = service.getStats("F006", "SKU-SEMI");

        assertThat(result.getTotalInputKg()).isEqualByComparingTo("50");
        assertThat(result.getBatchCount()).isEqualTo(1L);
    }
}
