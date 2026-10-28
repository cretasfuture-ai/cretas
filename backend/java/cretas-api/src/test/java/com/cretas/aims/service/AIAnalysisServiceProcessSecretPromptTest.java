package com.cretas.aims.service;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.impl.AIContextServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 出口 1: 每次成本分析都会在提示词前面拼「预计算生产统计」(近 30 天全部产品的成本)。
 * 走真实入口 {@link AIAnalysisService#analyzeCost} + 真实 {@link AIContextServiceImpl},
 * 断言打在发给 Python 分析服务 (再到大模型) 的那一份正文上。
 */
class AIAnalysisServiceProcessSecretPromptTest {

    private final PythonSmartBIClient python = mock(PythonSmartBIClient.class);
    private final List<ProductionBatch> batches = List.of(
            batch(1L, SECRET, SECRET_PLAN, SECRET_COST, new BigDecimal("100")),
            batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, new BigDecimal("100")),
            batch(3L, NORMAL2, null, NORMAL2_COST, new BigDecimal("200")));

    private AIContextServiceImpl contextService(ProcessSecretLlmCostScope scope) {
        ReportService reports = mock(ReportService.class);
        when(reports.getProductionByProduct(eq(FACTORY), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(
                stat(SECRET, SECRET_NAME, "100"), stat(NORMAL, "普通2", "100"), stat(NORMAL2, "普通3", "200")));
        BomService bom = mock(BomService.class);
        when(bom.getProductTypesWithBom(FACTORY)).thenReturn(List.of());
        ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any())).thenReturn(batches);
        ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
        when(productTypes.findByFactoryId(FACTORY)).thenReturn(List.of());
        return new AIContextServiceImpl(bom, reports, batchRepository, productTypes, scope);
    }

    private AIAnalysisService service(ProcessSecretLlmCostScope scope) throws Exception {
        AIAnalysisService service = new AIAnalysisService();
        DashScopeConfig dashScope = mock(DashScopeConfig.class);
        when(dashScope.isAvailable()).thenReturn(false);
        ReflectionTestUtils.setField(service, "dashScopeConfig", dashScope);
        ReflectionTestUtils.setField(service, "pythonSmartBIClient", python);
        ReflectionTestUtils.setField(service, "aiContextService", contextService(scope));
        ReflectionTestUtils.setField(service, "llmCostScope", scope);
        when(python.analyzeGeneral(anyString(), nullable(String.class), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder().success(true).answer("好的").build());
        return service;
    }

    private String sentToModel() throws Exception {
        ArgumentCaptor<PythonGeneralAnalysisRequest> request = ArgumentCaptor.forClass(PythonGeneralAnalysisRequest.class);
        verify(python).analyzeGeneral(eq(FACTORY), nullable(String.class), request.capture());
        return request.getValue().getMessage();
    }

    @Test
    void precomputedProductionContextOmitsProtectedCostsAndTotalsOnlyNonProtectedRows() throws Exception {
        Map<String, Object> result = service(protectedScope())
                .analyzeCost(FACTORY, 42L, "batch-x", new HashMap<>(), null, "分析本月成本", true, 50);

        assertThat(result.get("success")).isEqualTo(true);
        String sent = sentToModel();
        // 合计 = 测试里逐行累加的非保密批次成本 (不是总数减保密)
        BigDecimal expectedTotal = BigDecimal.ZERO;
        for (ProductionBatch b : batches) {
            if (!SECRET.equals(b.getProductTypeId())) expectedTotal = expectedTotal.add(b.getTotalCost());
        }
        assertThat(sent)
                .contains("【预计算生产统计】")
                .contains("- 总成本: ¥" + expectedTotal.toPlainString() + "\n")
                .contains("不含 1 个保密产品")
                .contains("| " + SECRET_NAME + " | 100 kg | 保密 | 保密 |")
                // 普通产品的单位成本照常给 (阴性对照: 过滤没有把普通产品一起去掉)
                .contains("¥12.3456")
                .doesNotContain(SECRET_COST.toPlainString(), "77.7777", "13333.33");
    }

    @Test
    void negativeControlWithoutProtectionTheSameInstrumentSeesTheSecretProductCost() throws Exception {
        service(unprotectedScope()).analyzeCost(FACTORY, 42L, "batch-x", new HashMap<>(), null, "分析本月成本", true, 50);

        assertThat(sentToModel()).contains("¥77.7777", "¥13333.33").doesNotContain("保密产品");
    }

    @Test
    void costDataStillCarryingAProtectedRowIsNotSentAtAll() throws Exception {
        Map<String, Object> costData = new HashMap<>();
        costData.put("batches", weeklyRows());

        Map<String, Object> result = service(protectedScope())
                .analyzeCost(FACTORY, 42L, null, costData, null, "周报", true, 50);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("processSecret")).isEqualTo(true);
        verify(python, never()).analyzeGeneral(anyString(), any(), any());
    }

    @Test
    void aSingleBatchOfTheProtectedProductIsNotSentAtAll() throws Exception {
        AIAnalysisService service = service(protectedScope());
        ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
        when(batchRepository.findByIdAndFactoryId(1L, FACTORY)).thenReturn(Optional.of(batches.get(0)));
        when(batchRepository.findByIdAndFactoryId(2L, FACTORY)).thenReturn(Optional.of(batches.get(1)));
        ReflectionTestUtils.setField(service, "productionBatchRepository", batchRepository);

        Map<String, Object> secret = service.analyzeCost(FACTORY, 42L, "1", new HashMap<>(), null, "为什么贵", true, 50);
        assertThat(secret.get("success")).isEqualTo(false);
        verify(python, never()).analyzeGeneral(anyString(), any(), any());

        // 阴性对照: 普通产品的单批照常发
        Map<String, Object> normal = service.analyzeCost(FACTORY, 42L, "2", new HashMap<>(), null, "为什么贵", true, 50);
        assertThat(normal.get("success")).isEqualTo(true);
    }

    @Test
    void scopeLookupFailureMeansNothingIsSent() throws Exception {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.protectedProductTypeIds(anyString())).thenThrow(new IllegalStateException("db down"));
        ProcessSecretLlmCostScope broken = new ProcessSecretLlmCostScope(new ProcessSecretAggregateExclusions(access,
                mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                mock(com.cretas.aims.repository.bom.BomRecipeRepository.class)));

        Map<String, Object> result = service(broken).analyzeCost(FACTORY, 42L, null, new HashMap<>(), null, "x", true, 50);

        assertThat(result.get("success")).isEqualTo(false);
        verify(python, never()).analyzeGeneral(anyString(), any(), any());
    }

    @Test
    void productCostContextOfAProtectedProductCarriesNoNumbers() {
        AIContextServiceImpl contexts = contextService(protectedScope());

        String secret = contexts.formatCostContextForPrompt(costContext(SECRET, SECRET_NAME, "77.7777"));
        String normal = contexts.formatCostContextForPrompt(costContext(NORMAL, "普通2", "12.3456"));

        assertThat(secret).contains(ProcessSecretLlmCostScope.WITHHELD_REASON).doesNotContain("77.7777", "¥");
        assertThat(normal).contains("¥12.3456");
    }

    @Test
    void theUnfilteredProductionContextCannotBeFormattedIntoAPrompt() {
        AIContextServiceImpl contexts = contextService(protectedScope());
        LocalDate today = LocalDate.now();

        assertThatThrownBy(() -> contexts.formatProductionContextForPrompt(
                contexts.buildProductionContext(FACTORY, today.minusDays(30), today)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ProductionByProductDTO stat(String productTypeId, String name, String quantity) {
        return ProductionByProductDTO.builder().productTypeId(productTypeId).productName(name)
                .totalQuantity(new BigDecimal(quantity)).unit("kg").build();
    }

    private static CostAIContext costContext(String productTypeId, String name, String unitCost) {
        return CostAIContext.builder().factoryId(FACTORY).productTypeId(productTypeId).productName(name)
                .bomTotalCost(new BigDecimal("3.21")).bomMaterialCostRatio(BigDecimal.ONE)
                .batchCount(2).avgActualUnitCost(new BigDecimal(unitCost))
                .actualMaterialCostRatio(BigDecimal.TEN).actualLaborCostRatio(BigDecimal.ONE)
                .isCostIncreasing(false).build();
    }
}
