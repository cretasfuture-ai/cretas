package com.cretas.aims.service.intent.impl;

import com.cretas.aims.ai.tool.NegationTwinPolicy;
import com.cretas.aims.config.ArenaRLConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentMatchingConfig;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.UnifiedSemanticMatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemanticCacheConfigRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.AIIntentDomainDefaultService;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.FactoryConfigService;
import com.cretas.aims.service.IntentEmbeddingCacheService;
import com.cretas.aims.service.IntentFeedbackService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.KeywordLearningService;
import com.cretas.aims.service.KeywordPromotionService;
import com.cretas.aims.service.LlmIntentFallbackClient;
import com.cretas.aims.service.LongTextHandler;
import com.cretas.aims.service.MultiLabelIntentClassifier;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.SemanticRouterService;
import com.cretas.aims.service.TwoStageIntentClassifier;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.consent.TrainingConsentService;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.impl.IntentConfigRollbackService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图识别链路上收训练样本失败 (保密名单查库失败 / 样本写不进) 不能让识别请求失败,
 * 也不能把已经算好的识别结果丢掉 (2026-10-29)。
 *
 * <p>经真实入口 {@link IntentRecognitionPipelineServiceImpl#recognizeIntentWithConfidence} 跑两条收样本的路径:
 * <ul>
 *   <li>精确表达命中 (Layer 1, 原先异常被外层 catch 吞掉, 精确匹配结果被丢弃、继续往下猜)</li>
 *   <li>并行评分高置信 (原先没有 catch, 异常直接让识别请求失败)</li>
 * </ul>
 * 每条路径: 名单查得出来时的结果作基线, 名单查库抛错时结果必须与基线一致, 且不抛、不存样本、记计数;
 * 基线那一侧样本必须真的存了 —— 证明这条路径确实走到了收样本 (否则「没存」不说明任何事)。
 */
class IntentPipelineSampleCaptureFailureContractTest {

    static final String FACTORY = "F006";
    static final String INTENT = "PROCESSING_BATCH_LIST";
    /** ≥ 10 字, 不走短句关键词捷径 */
    static final String INPUT = "帮我看一下本周包装车间的批次汇总情况";

    /** 一套装好的识别管线: 真实的样本收集服务 + 真实的保密过滤器, 只有最底下的保密策略查询是桩。 */
    static final class Rig {
        final ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        final ProductTypeRepository products = mock(ProductTypeRepository.class);
        final ProcessSecretSampleFilter filter = new ProcessSecretSampleFilter(access, products);
        final TrainingSampleRepository sampleRepository = mock(TrainingSampleRepository.class);
        final TrainingConsentService consent = mock(TrainingConsentService.class);
        final ExpressionLearningService els;
        final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        final IntentEmbeddingCacheService embeddingCache = mock(IntentEmbeddingCacheService.class);
        final IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        final IntentMatchingConfig matchingConfig = new IntentMatchingConfig();
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final IntentRecognitionPipelineServiceImpl pipeline;

        Rig(boolean lookupFails) {
            ReflectionTestUtils.setField(filter, "meterRegistry", meters);
            if (lookupFails) {
                when(access.protectedProductTypeIds(FACTORY))
                        .thenThrow(new DataAccessResourceFailureException("connection reset"));
            } else {
                ProductType p = new ProductType();
                p.setId("P1");
                p.setFactoryId(FACTORY);
                p.setName("秘制牛肉干");
                when(access.protectedProductTypeIds(FACTORY)).thenReturn(Set.of("P1"));
                when(products.findAllById(any())).thenReturn(List.of(p));
            }
            when(consent.status(FACTORY)).thenReturn(TrainingConsentService.Status.AUTHORIZED);
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            els = spy(new ExpressionLearningServiceImpl(null, sampleRepository, null, null, null, consent, filter));

            AIIntentConfig intent = new AIIntentConfig();
            intent.setIntentCode(INTENT);
            intent.setIntentName("批次列表");
            when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
            when(configService.getAllIntents(FACTORY)).thenReturn(List.of(intent));
            when(configService.getIntentConfigByCode(FACTORY, INTENT)).thenReturn(intent);
            when(knowledgeBase.matchPhrase(anyString(), any())).thenReturn(Optional.empty());

            matchingConfig.getSemanticFirst().setEnabled(false); // 走 v4.0 并行评分 (收样本那一行)
            matchingConfig.getAutoLearn().setEnabled(false);
            assertThat(matchingConfig.isSampleCollectionEnabled()).as("收样本开关默认开").isTrue();

            pipeline = new IntentRecognitionPipelineServiceImpl(
                    mock(AIIntentConfigRepository.class), mock(IntentMatchRecordRepository.class), new ObjectMapper(),
                    mock(LlmIntentFallbackClient.class), mock(FactoryConfigService.class),
                    mock(KeywordEffectivenessService.class), mock(KeywordPromotionService.class),
                    mock(KeywordLearningService.class), mock(IntentConfigRollbackService.class), embeddingCache,
                    els, mock(SemanticCacheConfigRepository.class), mock(ConversationService.class),
                    mock(AIIntentDomainDefaultService.class), mock(QueryPreprocessorService.class),
                    mock(ConversationMemoryService.class), mock(MultiLabelIntentClassifier.class),
                    mock(IntentFeedbackService.class), mock(TwoStageIntentClassifier.class), matchingConfig,
                    mock(ArenaRLConfig.class), knowledgeBase, mock(SemanticRouterService.class),
                    mock(LongTextHandler.class), configService, mock(NegationTwinPolicy.class));
            ReflectionTestUtils.setField(pipeline, "meterRegistry", meters);
        }

        /** Layer 1 精确表达命中 */
        Rig exactExpressionHit() {
            doReturn(Optional.of(new ExpressionLearningService.ExpressionMatchResult("E1", INTENT, INPUT, 1.0, "EXACT")))
                    .when(els).matchExactExpression(FACTORY, INPUT);
            return this;
        }

        /** 精确表达不命中, 并行评分给出高置信 (语义 1.0 × 0.6 + 领域加分 0.25 = 0.85 ≥ 0.75) */
        Rig parallelScoreHit() {
            doReturn(Optional.empty()).when(els).matchExactExpression(FACTORY, INPUT);
            when(embeddingCache.matchIntentsWithExpressions(eq(FACTORY), eq(INPUT), anyDouble()))
                    .thenReturn(List.of(UnifiedSemanticMatch.builder().intentCode(INTENT).similarity(1.0).build()));
            when(knowledgeBase.detectDomain(anyString())).thenReturn(IntentKnowledgeBase.Domain.PROCESSING);
            when(knowledgeBase.getDomainFromIntentCode(INTENT)).thenReturn(IntentKnowledgeBase.Domain.PROCESSING);
            when(knowledgeBase.countDomainKeywords(anyString(), eq(IntentKnowledgeBase.Domain.PROCESSING))).thenReturn(3);
            return this;
        }

        IntentMatchResult recognize() {
            return pipeline.recognizeIntentWithConfidence(INPUT, FACTORY, 3, 1L, "factory_super_admin", null);
        }

        double captureFailedCount(String path) {
            Counter c = meters.find(IntentRecognitionPipelineServiceImpl.METRIC_SAMPLE_CAPTURE_FAILED)
                    .tag("path", path).counter();
            return c == null ? 0.0 : c.count();
        }

        double unverifiedCount() {
            Counter c = meters.find("cretas.training_sample.secrecy_unverified").tag("kind", "intent").counter();
            return c == null ? 0.0 : c.count();
        }
    }

    static void assertSameRecognition(IntentMatchResult actual, IntentMatchResult baseline) {
        assertThat(actual).isNotNull();
        assertThat(actual.getBestMatch()).isNotNull();
        assertThat(actual.getBestMatch().getIntentCode()).isEqualTo(baseline.getBestMatch().getIntentCode());
        assertThat(actual.getConfidence()).isEqualTo(baseline.getConfidence());
        assertThat(actual.getMatchMethod()).isEqualTo(baseline.getMatchMethod());
        assertThat(actual.getRequiresConfirmation()).isEqualTo(baseline.getRequiresConfirmation());
        assertThat(actual.getIsStrongSignal()).isEqualTo(baseline.getIsStrongSignal());
    }

    @Nested
    class ExactExpressionPath {

        @Test
        void lookupFailureKeepsTheExactMatchResult() {
            Rig ok = new Rig(false).exactExpressionHit();
            IntentMatchResult baseline = ok.recognize();
            // 基线: 精确匹配结果, 且样本真的存了 (这条路径确实走到收样本)
            assertThat(baseline.getMatchMethod()).isEqualTo(IntentMatchResult.MatchMethod.EXACT);
            assertThat(baseline.getBestMatch().getIntentCode()).isEqualTo(INTENT);
            verify(ok.sampleRepository, times(1)).save(any(TrainingSample.class));

            Rig failing = new Rig(true).exactExpressionHit();
            IntentMatchResult result = failing.recognize();
            assertSameRecognition(result, baseline);
            verify(failing.els, times(1)).recordSample(eq(FACTORY), eq(INPUT), eq(INTENT),
                    eq(TrainingSample.MatchMethod.EXACT), anyDouble(), isNull());
            verify(failing.sampleRepository, never()).save(any());
            assertThat(failing.unverifiedCount()).isEqualTo(1.0);
            assertThat(failing.captureFailedCount("exact")).isZero();
        }

        @Test
        void sampleStoreFailureKeepsTheExactMatchResult() {
            IntentMatchResult baseline = new Rig(false).exactExpressionHit().recognize();
            Rig failing = new Rig(false).exactExpressionHit();
            doThrow(new IllegalStateException("training_samples unavailable"))
                    .when(failing.els).recordSample(any(), any(), any(), any(), anyDouble(), any());
            assertSameRecognition(failing.recognize(), baseline);
            assertThat(failing.captureFailedCount("exact")).isEqualTo(1.0);
            assertThat(failing.captureFailedCount("parallel")).isZero();
        }
    }

    @Nested
    class ParallelScorePath {

        @Test
        void lookupFailureDoesNotFailRecognition() {
            Rig ok = new Rig(false).parallelScoreHit();
            IntentMatchResult baseline = ok.recognize();
            assertThat(baseline.getBestMatch().getIntentCode()).isEqualTo(INTENT);
            assertThat(baseline.getConfidence()).isGreaterThanOrEqualTo(0.75);
            verify(ok.els, times(1)).recordSample(eq(FACTORY), eq(INPUT), eq(INTENT),
                    eq(TrainingSample.MatchMethod.KEYWORD), anyDouble(), isNull());
            verify(ok.sampleRepository, times(1)).save(any(TrainingSample.class));

            Rig failing = new Rig(true).parallelScoreHit();
            IntentMatchResult result = failing.recognize();
            assertSameRecognition(result, baseline);
            verify(failing.els, times(1)).recordSample(eq(FACTORY), eq(INPUT), eq(INTENT),
                    eq(TrainingSample.MatchMethod.KEYWORD), anyDouble(), isNull());
            verify(failing.sampleRepository, never()).save(any());
            assertThat(failing.unverifiedCount()).isEqualTo(1.0);
        }

        @Test
        void sampleStoreFailureDoesNotFailRecognition() {
            IntentMatchResult baseline = new Rig(false).parallelScoreHit().recognize();
            Rig failing = new Rig(false).parallelScoreHit();
            doThrow(new IllegalStateException("training_samples unavailable"))
                    .when(failing.els).recordSample(any(), any(), any(), any(), anyDouble(), any());
            assertSameRecognition(failing.recognize(), baseline);
            assertThat(failing.captureFailedCount("parallel")).isEqualTo(1.0);
            assertThat(failing.captureFailedCount("exact")).isZero();
        }
    }
}
