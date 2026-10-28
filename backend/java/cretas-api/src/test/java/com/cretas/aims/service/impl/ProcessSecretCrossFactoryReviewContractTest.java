package com.cretas.aims.service.impl;

import com.cretas.aims.controller.IntentAnalysisController;
import com.cretas.aims.dto.smartbi.chart.AdaptiveChartRequest;
import com.cretas.aims.dto.smartbi.chart.DataFeatures;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentOptimizationSuggestion;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.repository.IntentOptimizationSuggestionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.repository.smartbi.SmartBiExcelUploadRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.FactoryConfigService;
import com.cretas.aims.service.IntentDisambiguationService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.KeywordLearningService;
import com.cretas.aims.service.LongTextHandler;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import com.cretas.aims.service.consent.LearningContentRules;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.LlmEgressRedactor;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.consent.TrainingConsentService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.smartbi.chart.impl.AdaptiveChartGeneratorImpl;
import com.cretas.aims.service.smartbi.chart.impl.ChartSufficiencyEvaluatorImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复审 (2026-10-29) 找到的跨工厂路径: 工艺保密产品名不许经由学习 / 全局 / 外发出现在别的工厂。
 * 每组都有阴性对照 (不提保密产品 / 本厂行 / 显式工厂 → 照常)。保密名单走真实的 {@link ProcessSecretSampleFilter}。
 */
class ProcessSecretCrossFactoryReviewContractTest {

    static final String FA = "FA";
    static final String FB = "FB";
    static final String SECRET = "秘制酱牛肉";

    ProcessSecretAccessService access;
    ProcessSecretSampleFilter filter;
    TrainingConsentService consent;

    @BeforeEach
    void setUp() {
        access = mock(ProcessSecretAccessService.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        when(access.factoriesWithProtectedProducts()).thenReturn(Set.of(FA));
        when(access.protectedProductTypeIds(FA)).thenReturn(Set.of("pA"));
        ProductType p = new ProductType();
        p.setId("pA");
        p.setFactoryId(FA);
        p.setName(SECRET);
        when(products.findAllById(Set.of("pA"))).thenReturn(List.of(p));
        filter = new ProcessSecretSampleFilter(access, products);
        consent = mock(TrainingConsentService.class);
        when(consent.status(anyString())).thenReturn(TrainingConsentService.Status.AUTHORIZED);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    static void bindPlatformAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, null, "super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    static AIIntentConfig intentRow(String factoryId, String keywordsJson) {
        AIIntentConfig c = new AIIntentConfig();
        c.setIntentCode("PRODUCTION_QUERY");
        c.setFactoryId(factoryId);
        c.setKeywords(keywordsJson);
        c.setIsActive(true);
        return c;
    }

    // ---------------------------------------------------------------- H1 关键词学习

    @Nested
    class KeywordLearning {
        AIIntentConfigRepository intents;
        KeywordLearningServiceImpl service;

        @BeforeEach
        void setUp() {
            intents = mock(AIIntentConfigRepository.class);
            FactoryConfigService factoryConfig = mock(FactoryConfigService.class);
            when(factoryConfig.getMaxKeywordsPerIntent(anyString())).thenReturn(50);
            when(factoryConfig.getLlmNewKeywordWeight(anyString())).thenReturn(BigDecimal.ONE);
            service = new KeywordLearningServiceImpl(intents, mock(KeywordEffectivenessService.class), factoryConfig,
                    new ObjectMapper(), consent, filter);
        }

        @Test
        void plainUtteranceIsLearnedIntoTheCallersOwnFactoryRow() {
            AIIntentConfig own = intentRow(FA, "[\"产量\"]");
            when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(own));

            int added = service.learnFromMatchedIntent("今天 出货情况", intentRow(null, "[]"), FA);

            assertThat(added).isPositive();
            verify(intents).save(own);
            assertThat(own.getKeywords()).contains("出货情况");
            verify(intents, never()).findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull(anyString());
        }

        @Test
        void utteranceNamingTheFactorysProtectedProductIsNotLearned() {
            when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY"))
                    .thenReturn(Optional.of(intentRow(FA, "[]")));

            assertThat(service.learnFromMatchedIntent(SECRET + " 今天产量", intentRow(FA, "[]"), FA)).isZero();
            verify(intents, never()).save(any());
        }

        @Test
        void withoutAnOwnFactoryRowNothingIsWrittenToThePlatformRow() {
            // 只有平台级行 (factory_id 为空): 不写 —— 以前按意图代码取到的就是它
            when(intents.findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull("PRODUCTION_QUERY"))
                    .thenReturn(Optional.of(intentRow(null, "[]")));
            when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.empty());

            assertThat(service.learnFromMatchedIntent("今天 出货情况", intentRow(null, "[]"), FA)).isZero();
            assertThat(service.addKeywordsToIntent(FA, "PRODUCTION_QUERY", List.of("出货情况"))).isZero();
            verify(intents, never()).save(any());
        }

        @Test
        void anotherFactorysRowIsNeverWritten() {
            when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY"))
                    .thenReturn(Optional.of(intentRow(FB, "[]")));
            assertThat(service.addKeywordsToIntent(FA, "PRODUCTION_QUERY", List.of("出货情况"))).isZero();
            verify(intents, never()).save(any());
        }

        @Test
        void withdrawnConsentAndGlobalFactoryLearnNothing() {
            when(consent.status(FB)).thenReturn(TrainingConsentService.Status.WITHDRAWN);
            when(intents.findByFactoryIdAndIntentCode(FB, "PRODUCTION_QUERY"))
                    .thenReturn(Optional.of(intentRow(FB, "[]")));
            assertThat(service.learnFromMatchedIntent("今天 出货情况", intentRow(FB, "[]"), FB)).isZero();
            assertThat(service.learnFromMatchedIntent("今天 出货情况", intentRow(null, "[]"), null)).isZero();
            verify(intents, never()).save(any());
        }
    }

    // ---------------------------------------------------------------- H3 读出口

    @Test
    void ragAndFewShotNeverServeSystemLearnedGlobalRows() {
        LearnedExpressionRepository repo = mock(LearnedExpressionRepository.class);
        LearnedExpression learnedGlobal = row("g1", null, LearnedExpression.SourceType.SEMANTIC_HIGH, SECRET + "今天产量");
        LearnedExpression blankGlobal = row("g0", " ", LearnedExpression.SourceType.LLM_FALLBACK, SECRET + "库存");
        LearnedExpression manualGlobal = row("g2", null, LearnedExpression.SourceType.MANUAL, "今天产量");
        when(repo.findByExpressionHash(anyString(), eq(FB))).thenReturn(List.of(learnedGlobal, blankGlobal));
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        RAGRetrievalServiceImpl rag = new RAGRetrievalServiceImpl(mock(IntentMatchRecordRepository.class), repo,
                mock(AIIntentConfigRepository.class), embedding,
                mock(com.cretas.aims.service.IntentEmbeddingCacheService.class), mock(RequestScopedEmbeddingCache.class));

        assertThat(rag.findDirectMatch(FB, SECRET + "今天产量", 0.0)).isEmpty();
        when(repo.findByExpressionHash(anyString(), eq(FB))).thenReturn(List.of(learnedGlobal, manualGlobal));
        assertThat(rag.findDirectMatch(FB, "今天产量", 0.0)).get()
                .satisfies(c -> assertThat(c.getUserInput()).isEqualTo("今天产量"));

        RequestScopedEmbeddingCache cache = mock(RequestScopedEmbeddingCache.class);
        when(cache.getOrCompute(anyString())).thenReturn(new float[]{1f, 0f});
        when(embedding.isAvailable()).thenReturn(true);
        when(repo.findActiveByFactory(FB)).thenReturn(List.of(learnedGlobal, blankGlobal, manualGlobal));
        DynamicFewShotService fewShot = new DynamicFewShotService(repo, mock(IntentMatchRecordRepository.class),
                mock(AIIntentConfigRepository.class), embedding, cache);
        ReflectionTestUtils.setField(fewShot, "minExamples", 1);
        ReflectionTestUtils.setField(fewShot, "maxExamples", 7);
        ReflectionTestUtils.setField(fewShot, "minSimilarity", 0.0);
        ReflectionTestUtils.setField(fewShot, "candidatePoolSize", 30);
        ReflectionTestUtils.setField(fewShot, "mmrLambda", 0.7);

        assertThat(fewShot.selectFewShotExamples(FB, "今天产量", 5))
                .extracting(DynamicFewShotService.FewShotExample::getUserInput)
                .containsExactly("今天产量");
    }

    @Test
    void blankFactoryCountsAsGlobal() {
        assertThat(LearningContentRules.systemLearnedGlobal(
                row("x", "  ", LearnedExpression.SourceType.LLM_FALLBACK, "说法"))).isTrue();
        assertThat(IntentEmbeddingCacheServiceImpl.learnedGlobalExpression(
                row("x", "", LearnedExpression.SourceType.SEMANTIC_HIGH, "说法"))).isTrue();
        assertThat(LearningContentRules.systemLearnedGlobal(
                row("y", FA, LearnedExpression.SourceType.LLM_FALLBACK, "说法"))).isFalse();
    }

    static LearnedExpression row(String id, String factoryId, LearnedExpression.SourceType source, String text) {
        LearnedExpression e = new LearnedExpression();
        e.setId(id);
        e.setFactoryId(factoryId);
        e.setIntentCode("PRODUCTION_QUERY");
        e.setExpression(text);
        e.setSourceType(source);
        e.setIsActive(true);
        e.setIsVerified(true);
        e.setHitCount(1);
        e.setConfidence(new BigDecimal("0.95"));
        e.setEmbeddingFromFloatArray(new float[]{1f, 0f});
        return e;
    }

    // ---------------------------------------------------------------- L4 大小写

    @Test
    void asciiProtectedNamesMatchCaseInsensitively() {
        assertThat(ProcessSecretSampleFilter.mentionsAny(Set.of("SecretX"), "secretx 今天做了多少")).isTrue();
        assertThat(ProcessSecretSampleFilter.mentionsAny(Set.of("secretx"), "SECRETX 库存")).isTrue();
        assertThat(ProcessSecretSampleFilter.mentionsAny(Set.of(SECRET), "秘制酱牛肉库存")).isTrue();
        assertThat(ProcessSecretSampleFilter.mentionsAny(Set.of("SecretX"), "SecretY 今天做了多少")).isFalse();
    }

    // ---------------------------------------------------------------- M1 / M4 图表

    @Test
    void chartsFromAnotherFactorysUploadAreRefusedBeforeAnythingIsSent() {
        SmartBiExcelUploadRepository uploads = mock(SmartBiExcelUploadRepository.class);
        com.cretas.aims.ai.client.DashScopeClient llm = mock(com.cretas.aims.ai.client.DashScopeClient.class);
        AdaptiveChartGeneratorImpl generator = new AdaptiveChartGeneratorImpl(
                mock(com.cretas.aims.service.smartbi.chart.DataFeatureExtractor.class),
                mock(com.cretas.aims.service.smartbi.chart.ChartSufficiencyEvaluator.class),
                mock(com.cretas.aims.service.smartbi.chart.ChartFusionService.class),
                mock(com.cretas.aims.service.smartbi.ChartTemplateService.class),
                uploads, llm, new ObjectMapper());

        assertThatThrownBy(() -> generator.generateAdaptive(FB, 7L, AdaptiveChartRequest.builder().build()))
                .isInstanceOf(BusinessException.class);
        verify(uploads).findByIdAndFactoryId(7L, FB);
        verify(uploads, never()).findById(any());
        org.mockito.Mockito.verifyNoInteractions(llm);
    }

    @Test
    void chartEvaluationMasksTheDataFactorysNamesForAPlatformAdmin() {
        bindPlatformAdmin();
        com.cretas.aims.ai.client.DashScopeClient llm = mock(com.cretas.aims.ai.client.DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        when(llm.chatLowTemp(anyString(), anyString())).thenReturn("{}");
        ChartSufficiencyEvaluatorImpl evaluator = new ChartSufficiencyEvaluatorImpl(llm, new ObjectMapper());
        ReflectionTestUtils.setField(evaluator, "llmEgressGuard", new LlmEgressGuard(new ObjectMapper(), filter));
        DataFeatures features = DataFeatures.builder().rowCount(3)
                .numericColumns(List.of(SECRET + "销量")).categoricalColumns(List.of()).timeColumns(List.of()).build();

        evaluator.evaluateInitial(FA, List.of(), features);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(llm).chatLowTemp(anyString(), sent.capture());
        assertThat(sent.getValue()).doesNotContain(SECRET).contains("[保密产品A]");

        // 阴性对照: 平台账号、不带工厂 → 不发 (走规则评估)
        evaluator.evaluateInitial(List.of(), features);
        verify(llm).chatLowTemp(anyString(), anyString());
    }

    // ---------------------------------------------------------------- M4 代表出口

    @Test
    void disambiguationWithTheRecognisedFactoryWorksForAPlatformAdmin() {
        bindPlatformAdmin();
        LlmEgressGuard guard = new LlmEgressGuard(new ObjectMapper(), filter);
        assertThat(guard.texts("t", null, SECRET + "多少钱").refused()).isTrue();
        try (var scope = LlmEgressGuard.analysing(FA)) {
            LlmEgressRedactor.Result r = guard.texts("t", null, SECRET + "多少钱");
            assertThat(r.refused()).isFalse();
            assertThat(r.text(0)).doesNotContain(SECRET);
        }
        // 作用域结束即还原
        assertThat(guard.texts("t", null, SECRET + "多少钱").refused()).isTrue();
    }

    @Test
    void longTextEntryWithFactoryBindsItForTheSummaryEgress() {
        bindPlatformAdmin();
        LlmEgressGuard guard = new LlmEgressGuard(new ObjectMapper(), filter);
        LongTextHandler handler = new LongTextHandler() {
            @Override public String processForIntent(String input) {
                LlmEgressRedactor.Result r = guard.texts("long-text", null, input);
                return r.refused() ? "REFUSED" : r.text(0);
            }
            @Override public String summarizeForIntent(String input) { return input; }
            @Override public String removeStopwords(String input) { return input; }
            @Override public boolean needsProcessing(String input) { return true; }
            @Override public String getStats() { return ""; }
            @Override public void clearCache() { }
        };
        assertThat(handler.processForIntent(SECRET + "今天做了多少", FA)).doesNotContain(SECRET).isNotEqualTo("REFUSED");
        assertThat(handler.processForIntent(SECRET + "今天做了多少")).isEqualTo("REFUSED");
    }

    // ---------------------------------------------------------------- M2 平台晋升

    @Nested
    class PlatformPromotion {
        AIIntentConfigRepository intents;
        IntentOptimizationSuggestionRepository suggestions;
        IntentAnalysisController controller;

        @BeforeEach
        void setUp() {
            intents = mock(AIIntentConfigRepository.class);
            suggestions = mock(IntentOptimizationSuggestionRepository.class);
            controller = new IntentAnalysisController(
                    mock(com.cretas.aims.service.ErrorAttributionAnalysisService.class),
                    mock(com.cretas.aims.scheduler.ErrorAttributionAnalysisScheduler.class),
                    mock(com.cretas.aims.repository.ErrorAttributionStatisticsRepository.class),
                    mock(IntentMatchRecordRepository.class), suggestions, intents,
                    mock(com.cretas.aims.service.SemanticRouterService.class), filter);
            when(suggestions.findById("s1")).thenReturn(Optional.of(IntentOptimizationSuggestion
                    .createPromotionSuggestion(FA, "PRODUCTION_QUERY", "产量", "通用", "admin")));
            // 审批只给平台账号 (复审 H-B); 工厂账号被拒见 ProcessSecretCrossFactoryRound3ContractTest
            bindPlatformAdmin();
        }

        IntentAnalysisController.ApprovalRequest approve() {
            IntentAnalysisController.ApprovalRequest req = new IntentAnalysisController.ApprovalRequest();
            req.setApproved(true);
            req.setNotes("ok");
            return req;
        }

        @Test
        void intentNamingAProtectedProductIsNotPromotedToPlatform() {
            AIIntentConfig row = intentRow(FA, "[\"" + SECRET + "\", \"产量\"]");
            when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> controller.approvePromotion("PLATFORM", "s1", approve()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
            verify(intents, never()).save(any());
            assertThat(row.getFactoryId()).isEqualTo(FA);
        }

        @Test
        void plainIntentIsStillPromoted() {
            AIIntentConfig row = intentRow(FA, "[\"产量\"]");
            row.setDescription("查产量");
            when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(row));

            controller.approvePromotion("PLATFORM", "s1", approve());
            verify(intents).save(row);
            assertThat(row.getFactoryId()).isNull();
        }
    }

    // ---------------------------------------------------------------- 全局批量写入只查一次

    @Test
    void globalSeedBatchLoadsAllFactoriesNamesOnce() {
        LearnedExpressionRepository repo = mock(LearnedExpressionRepository.class);
        when(repo.findOneByHashAndFactory(anyString(), any())).thenReturn(Optional.empty());
        when(repo.save(any(LearnedExpression.class))).thenAnswer(inv -> inv.getArgument(0));
        ExpressionLearningServiceImpl service = new ExpressionLearningServiceImpl(repo, null, null, null, null,
                consent, filter);

        int n = service.learnExpressions("*", "PRODUCTION_QUERY",
                List.of("今天产量", "本周产量", SECRET + "产量", "本月产量"), 1.0, LearnedExpression.SourceType.MANUAL);

        assertThat(n).isEqualTo(3);
        verify(access, org.mockito.Mockito.times(1)).factoriesWithProtectedProducts();
    }
}
