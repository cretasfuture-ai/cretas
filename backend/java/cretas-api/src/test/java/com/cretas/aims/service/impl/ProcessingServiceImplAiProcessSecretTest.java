package com.cretas.aims.service.impl;

import com.cretas.aims.repository.*;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 单批 AI 成本分析 ({@code analyzeWithAI}): 保密产品的批次既不发给 AI, 也不回放以前缓存的分析;
 * 本厂有保密产品时共享缓存 (按工厂 + 批次存、不看调用者) 不读不写。
 */
@ExtendWith(MockitoExtension.class)
class ProcessingServiceImplAiProcessSecretTest {

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private EquipmentAlertRepository equipmentAlertRepository;
    @Mock private BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SystemLogRepository systemLogRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private AIAnalysisService aiAnalysisService;
    @Mock private CacheService cacheService;
    @Mock private ProcessingStageRecordService processingStageRecordService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private QualityInspectionService qualityInspectionService;
    @Mock private ProductionAlertRepository productionAlertRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;

    @InjectMocks
    private ProcessingServiceImpl service;

    @Test
    void protectedBatchIsRefusedWithoutReadingTheCacheOrCallingTheModel() {
        ReflectionTestUtils.setField(service, "llmCostScope", protectedScope());
        when(productionBatchRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(batch(1L, SECRET, SECRET_PLAN, SECRET_COST, BigDecimal.TEN)));
        // 若判定被绕过, 让流程能一路走到缓存和模型 (由下面的断言判红, 而不是半路空指针)
        stubAnalysisPath(1L);

        Map<String, Object> result = service.analyzeWithAI(FACTORY, "1", null, null);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("processSecret")).isEqualTo(true);
        assertThat(result.toString()).doesNotContain(SECRET_COST.toPlainString());
        verifyNoInteractions(cacheService, aiAnalysisService);
    }

    @Test
    void theOwnerMayAnalyseTheProtectedBatch() {
        ReflectionTestUtils.setField(service, "llmCostScope", protectedScope());
        when(productionBatchRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(Optional.of(batch(1L, SECRET, SECRET_PLAN, SECRET_COST, BigDecimal.TEN)));
        stubAnalysisPath(1L);

        Map<String, Object> result;
        try (var ignored = as(owner())) {
            result = service.analyzeWithAI(FACTORY, "1", null, null);
        }

        assertThat(result.get("success")).isEqualTo(true);
        verify(aiAnalysisService).analyzeCost(eq(FACTORY), eq("1"), anyMap(), isNull(), isNull());
    }

    @Test
    void theSharedCacheKeyCarriesTheCallersSecrecyTier() {
        ReflectionTestUtils.setField(service, "llmCostScope", protectedScope());
        stubNormalBatch();
        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);

        try (var ignored = as(owner())) {
            service.analyzeWithAI(FACTORY, "2", null, null);
        }
        try (var ignored = as(grantee())) {
            service.analyzeWithAI(FACTORY, "2", null, null);
        }
        try (var ignored = as(sameFactoryUser())) {
            service.analyzeWithAI(FACTORY, "2", null, null);
        }

        verify(cacheService, times(3)).getAIAnalysisCache(eq(FACTORY), keys.capture());
        List<String> read = keys.getAllValues();
        assertThat(read).allSatisfy(k -> assertThat(k).startsWith("2|ps-"));   // 旧的无档位键 "2" 不再读
        assertThat(read.get(0)).isEqualTo(read.get(1));                           // 负责人与被授权人同档
        assertThat(read.get(2)).isNotEqualTo(read.get(0));                        // 无权同事另一档
    }

    @Test
    void cacheNegativeControlAFactoryWithoutProtectedProductsStillUsesIt() {
        ReflectionTestUtils.setField(service, "llmCostScope", unprotectedScope());
        when(productionBatchRepository.findByIdAndFactoryId(2L, FACTORY))
                .thenReturn(Optional.of(batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, BigDecimal.TEN)));
        Map<String, Object> cached = new HashMap<>();
        cached.put("aiAnalysis", "旧分析");
        when(cacheService.getAIAnalysisCache(FACTORY, "2")).thenReturn(cached);

        Map<String, Object> result = service.analyzeWithAI(FACTORY, "2", null, null);

        assertThat(result.get("fromCache")).isEqualTo(true);
        verifyNoInteractions(aiAnalysisService);
    }

    private void stubNormalBatch() {
        when(productionBatchRepository.findByIdAndFactoryId(2L, FACTORY))
                .thenReturn(Optional.of(batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, BigDecimal.TEN)));
        stubAnalysisPath(2L);
        when(aiAnalysisService.analyzeCost(eq(FACTORY), eq("2"), anyMap(), isNull(), isNull())).thenReturn(aiOk());
    }

    /** getEnhancedBatchCostAnalysis 一路要读的仓库 (lenient: 判定拦下时它们不会被用到)。 */
    private void stubAnalysisPath(long batchId) {
        // 未打桩的 Map 返回值是空 Map 而不是 null, 会被当成缓存命中: 明确「缓存里没有」
        lenient().when(cacheService.getAIAnalysisCache(anyString(), anyString())).thenReturn(null);
        lenient().when(equipmentRepository.findByIdIn(any())).thenReturn(java.util.List.of());
        lenient().when(batchWorkSessionRepository.findByBatchId(batchId)).thenReturn(java.util.List.of());
        lenient().when(qualityInspectionRepository.findByFactoryIdAndProductionBatchId(eq(FACTORY), eq(batchId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));
        lenient().when(qualityInspectionRepository.findByFactoryId(eq(FACTORY), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));
        lenient().when(processingStageRecordService.getByBatchIdWithComparison(FACTORY, batchId)).thenReturn(java.util.List.of());
        lenient().when(aiAnalysisService.analyzeCost(eq(FACTORY), eq(String.valueOf(batchId)), anyMap(), isNull(), isNull()))
                .thenReturn(aiOk());
    }

    // ---------------- 生产统计 / 生产看板的成本合计 ----------------

    /** 本月批次: 保密产品 7777.77 / 100kg, 普通 1234.56 / 100kg 与 4321.00 / 200kg; 原查询返回含保密的全厂数。 */
    private void stubMonthBatches() {
        java.time.LocalDateTime today = java.time.LocalDate.now().atTime(0, 0, 1);
        java.util.List<com.cretas.aims.entity.ProductionBatch> batches = java.util.List.of(
                completed(batch(1L, SECRET, SECRET_PLAN, SECRET_COST, new BigDecimal("100")), today),
                completed(batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, new BigDecimal("100")), today),
                completed(batch(3L, NORMAL2, null, NORMAL2_COST, new BigDecimal("200")), today));
        lenient().when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY), any())).thenReturn(batches);
        lenient().when(productionBatchRepository.calculateTotalCostAfter(eq(FACTORY), any()))
                .thenReturn(SECRET_COST.add(NORMAL_COST).add(NORMAL2_COST));
        lenient().when(productionBatchRepository.calculateTotalOutputAfter(eq(FACTORY), any())).thenReturn(new BigDecimal("400"));
        lenient().when(productTypeRepository.findByIdIn(any())).thenReturn(java.util.List.of());
    }

    private static com.cretas.aims.entity.ProductionBatch completed(com.cretas.aims.entity.ProductionBatch batch,
                                                                    java.time.LocalDateTime createdAt) {
        batch.setStatus(com.cretas.aims.entity.enums.ProductionBatchStatus.COMPLETED);
        batch.setCreatedAt(createdAt);
        return batch;
    }

    private Map<String, Object> statisticsAs(com.cretas.aims.security.AuthenticatedActor actor) {
        try (var ignored = as(actor)) {
            return service.getProductionStatistics(FACTORY, "month");
        }
    }

    @Test
    void productionStatisticsTotalCostIsTheVisibleSumForAnUnauthorizedCaller() {
        ReflectionTestUtils.setField(service, "llmCostScope", protectedScope());
        stubMonthBatches();

        Map<String, Object> colleague = statisticsAs(sameFactoryUser());
        Map<String, Object> owner = statisticsAs(owner());

        assertThat((BigDecimal) colleague.get("totalCost")).isEqualByComparingTo(NORMAL_COST.add(NORMAL2_COST));
        assertThat((String) colleague.get("processSecretNote")).contains("不含 1 个保密产品");
        assertThat((BigDecimal) owner.get("totalCost")).isEqualByComparingTo(SECRET_COST.add(NORMAL_COST).add(NORMAL2_COST));
        assertThat(owner).doesNotContainKey("processSecretNote");
    }

    @SuppressWarnings("unchecked")
    @Test
    void dashboardUnitCostIsVisibleCostOverVisibleOutputForAnUnauthorizedCaller() {
        ReflectionTestUtils.setField(service, "llmCostScope", protectedScope());
        stubMonthBatches();

        Map<String, Object> colleagueKpi;
        Map<String, Object> ownerKpi;
        try (var ignored = as(sameFactoryUser())) {
            colleagueKpi = (Map<String, Object>) service.getDashboardOverview(FACTORY).get("kpi");
        }
        try (var ignored = as(owner())) {
            ownerKpi = (Map<String, Object>) service.getDashboardOverview(FACTORY).get("kpi");
        }

        // 无权: (1234.56 + 4321.00) / (100 + 200) = 18.52; 有权: 13333.33 / 400 = 33.33 (原查询)
        BigDecimal visible = NORMAL_COST.add(NORMAL2_COST).divide(new BigDecimal("300"), 2, java.math.RoundingMode.HALF_UP);
        assertThat((BigDecimal) colleagueKpi.get("unitCost")).isEqualByComparingTo(visible);
        assertThat((String) colleagueKpi.get("processSecretNote")).contains("不含 1 个保密产品");
        assertThat((BigDecimal) ownerKpi.get("unitCost")).isEqualByComparingTo("33.33");
    }

    private static Map<String, Object> aiOk() {
        Map<String, Object> ai = new HashMap<>();
        ai.put("success", true);
        ai.put("aiAnalysis", "新分析");
        return ai;
    }
}
