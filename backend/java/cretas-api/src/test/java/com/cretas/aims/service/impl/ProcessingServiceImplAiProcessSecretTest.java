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
    void sharedCacheIsNotReadInAFactoryWithProtectedProducts() {
        ReflectionTestUtils.setField(service, "llmCostScope", protectedScope());
        stubNormalBatch();

        service.analyzeWithAI(FACTORY, "2", null, null);

        verify(cacheService, never()).getAIAnalysisCache(anyString(), anyString());
        verify(cacheService, never()).setAIAnalysisCache(anyString(), anyString(), any());
        verify(aiAnalysisService).analyzeCost(eq(FACTORY), eq("2"), anyMap(), isNull(), isNull());
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

    private static Map<String, Object> aiOk() {
        Map<String, Object> ai = new HashMap<>();
        ai.put("success", true);
        ai.put("aiAnalysis", "新分析");
        return ai;
    }
}
