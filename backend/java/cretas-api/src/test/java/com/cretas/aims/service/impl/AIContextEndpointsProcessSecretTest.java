package com.cretas.aims.service.impl;

import com.cretas.aims.controller.AIController;
import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.ai.ProductionAIContext;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复审 L2: /ai/context/production-statistics、/cost-analysis、/cost-variance-summary 三个对外接口 (非 AI 视图) 按调用者
 * 只给他能看的成本 —— 走真实的 AIController 方法与真实 AIContextServiceImpl。
 */
class AIContextEndpointsProcessSecretTest {

    private final List<ProductionBatch> batches = List.of(
            batch(1L, SECRET, SECRET_PLAN, SECRET_COST, new BigDecimal("100")),
            batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, new BigDecimal("100")),
            batch(3L, NORMAL2, null, NORMAL2_COST, new BigDecimal("200")));

    private AIController controller(Set<String> protectedIds) {
        ReportService reports = mock(ReportService.class);
        when(reports.getProductionByProduct(eq(FACTORY), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(
                stat(SECRET, SECRET_NAME, "100"), stat(NORMAL, "普通2", "100"), stat(NORMAL2, "普通3", "200")));
        BomService bom = mock(BomService.class);
        when(bom.getProductTypesWithBom(FACTORY)).thenReturn(List.of(SECRET, NORMAL));
        // 人工有基线才出差异明细: 实际人工远高于 BOM 人工 ⇒ 每个产品都出一条主要差异
        when(bom.calculateProductCost(eq(FACTORY), anyString())).thenReturn(BomCostSummaryDTO.builder()
                .totalCost(new BigDecimal("10")).materialCostTotal(new BigDecimal("5")).laborCostTotal(new BigDecimal("1")).build());
        ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any())).thenReturn(batches);
        when(batchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY), any())).thenReturn(batches);
        ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
        when(productTypes.findByFactoryId(FACTORY)).thenReturn(List.of());
        when(productTypes.findById(anyString())).thenReturn(Optional.empty());
        ProcessSecretLlmCostScope scope = scope(protectedIds);
        AIContextServiceImpl contexts = new AIContextServiceImpl(bom, reports, batchRepository, productTypes, scope);
        AIController controller = new AIController();
        ReflectionTestUtils.setField(controller, "aiContextService", contexts);
        return controller;
    }

    private static ProductionByProductDTO stat(String productTypeId, String name, String quantity) {
        return ProductionByProductDTO.builder().productTypeId(productTypeId).productName(name)
                .totalQuantity(new BigDecimal(quantity)).unit("kg").build();
    }

    private <T> T callAs(AuthenticatedActor actor, java.util.function.Supplier<T> call) {
        try (var ignored = as(actor)) {
            return call.get();
        }
    }

    @Test
    void productionStatisticsContextIsTrimmedForAnUnauthorizedCaller() {
        AIController controller = controller(Set.of(SECRET));

        ProductionAIContext colleague = callAs(sameFactoryUser(),
                () -> controller.getProductionContext(FACTORY, null, null).getData());
        ProductionAIContext owner = callAs(owner(), () -> controller.getProductionContext(FACTORY, null, null).getData());

        assertThat(colleague.getTotalCost()).isEqualByComparingTo(NORMAL_COST.add(NORMAL2_COST));
        assertThat(colleague.getExcludedProtectedProductCount()).isEqualTo(1);
        assertThat(colleague.getProductionByProduct()).filteredOn(p -> SECRET.equals(p.getProductTypeId()))
                .singleElement().satisfies(p -> assertThat(p.getTotalCost()).isNull());
        assertThat(owner.getTotalCost()).isEqualByComparingTo(SECRET_COST.add(NORMAL_COST).add(NORMAL2_COST));
    }

    @Test
    void productCostContextOfAProtectedProductHasNoCostsForAnUnauthorizedCaller() {
        AIController controller = controller(Set.of(SECRET));

        CostAIContext colleague = callAs(platform(), () -> controller.getCostContext(FACTORY, SECRET, 10).getData());
        CostAIContext owner = callAs(owner(), () -> controller.getCostContext(FACTORY, SECRET, 10).getData());
        CostAIContext normal = callAs(platform(), () -> controller.getCostContext(FACTORY, NORMAL, 10).getData());

        assertThat(colleague.getAvgActualUnitCost()).isNull();
        assertThat(colleague.getBomTotalCost()).isNull();
        assertThat(colleague.getRecentBatchTrends()).isNull();
        assertThat(colleague.getProcessSecretNote()).contains("保密");
        assertThat(colleague.getTotalQuantity()).isEqualByComparingTo("100");             // 数量照常
        assertThat(owner.getAvgActualUnitCost()).isEqualByComparingTo("77.7777");
        assertThat(normal.getAvgActualUnitCost()).isEqualByComparingTo("12.3456");        // 普通产品照常
    }

    @Test
    void costVarianceSummaryLeavesOutTheProtectedProductForAnUnauthorizedCaller() {
        AIController controller = controller(Set.of(SECRET));

        List<?> colleague = callAs(sameFactoryUser(), () -> (List<?>) controller.getCostVarianceSummary(FACTORY).getData());
        List<?> owner = callAs(owner(), () -> (List<?>) controller.getCostVarianceSummary(FACTORY).getData());
        List<?> unprotected = callAs(sameFactoryUser(),
                () -> (List<?>) controller(Set.of()).getCostVarianceSummary(FACTORY).getData());

        assertThat(owner).hasSize(2);          // 一定发生: 两个产品都出了差异明细
        assertThat(colleague).hasSize(1);
        assertThat(unprotected).hasSize(2);
    }
}
