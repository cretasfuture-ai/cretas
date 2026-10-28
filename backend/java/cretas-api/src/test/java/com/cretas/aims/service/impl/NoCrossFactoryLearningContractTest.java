package com.cretas.aims.service.impl;

import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.KeywordFactoryAdoptionRepository;
import com.cretas.aims.repository.learning.CrossFactoryKnowledgeAdoptionRepository;
import com.cretas.aims.repository.learning.CrossFactoryKnowledgeRepository;
import com.cretas.aims.service.KeywordEffectivenessService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工厂之间不共享学到的说法 (Steve 2026-10-22)。守的行为:
 * 3 家以上工厂用过的说法不再晋升为全局; 跨厂知识不能登记 / 列出 / 采用;
 * 系统从某家工厂学到的说法即使没有工厂字段也不进全局识别池 —— 人工预置的全局说法照常生效。
 */
class NoCrossFactoryLearningContractTest {

    @Test
    void keywordPromotionNeverWritesAGlobalKeyword() {
        KeywordFactoryAdoptionRepository adoptions = mock(KeywordFactoryAdoptionRepository.class);
        KeywordEffectivenessService effectiveness = mock(KeywordEffectivenessService.class);
        when(adoptions.findPromotionCandidates(anyInt())).thenReturn(List.<Object[]>of(new Object[]{"I", "说法", 3L}));
        when(adoptions.findFactoryIdsByKeyword(anyString(), anyString())).thenReturn(List.of("F1", "F2", "F3"));
        when(adoptions.getAverageEffectiveness(anyString(), anyString())).thenReturn(new BigDecimal("0.9"));
        KeywordPromotionServiceImpl service = new KeywordPromotionServiceImpl(adoptions, effectiveness);

        assertThat(service.runPromotionCheck(3, new BigDecimal("0.5"))).isZero();
        assertThat(service.promoteToGlobal("I", "说法")).isFalse();
        verifyNoInteractions(effectiveness);
        verify(adoptions, never()).markAsPromoted(anyString(), anyString());
    }

    @Test
    void crossFactoryKnowledgeCannotBeRegisteredListedOrAdopted() {
        CrossFactoryKnowledgeRepository knowledge = mock(CrossFactoryKnowledgeRepository.class);
        CrossFactoryKnowledgeAdoptionRepository adoptions = mock(CrossFactoryKnowledgeAdoptionRepository.class);
        ActiveLearningServiceImpl service = Mockito.mock(ActiveLearningServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "knowledgeRepository", knowledge);
        ReflectionTestUtils.setField(service, "adoptionRepository", adoptions);

        assertThatThrownBy(() -> service.registerKnowledge("F1",
                com.cretas.aims.entity.learning.CrossFactoryKnowledge.KnowledgeType.KEYWORD, "I", "说法"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(410));
        assertThatThrownBy(() -> service.adoptKnowledge("F2", 1L)).isInstanceOf(BusinessException.class);
        assertThat(service.getAvailableGlobalKnowledge("F2")).isEmpty();
        verifyNoInteractions(knowledge, adoptions);
    }

    static LearnedExpression expression(String id, String factoryId, LearnedExpression.SourceType source) {
        LearnedExpression e = new LearnedExpression();
        e.setId(id);
        e.setFactoryId(factoryId);
        e.setIntentCode("I");
        e.setExpression("说法-" + id);
        e.setSourceType(source);
        e.setIsActive(true);
        e.setEmbeddingFromFloatArray(new float[]{1f, 0f});
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void learnedExpressionsWithoutFactoryNeverJoinTheGlobalPoolButManualOnesDo() {
        com.cretas.aims.repository.learning.LearnedExpressionRepository repo =
                mock(com.cretas.aims.repository.learning.LearnedExpressionRepository.class);
        com.cretas.aims.service.EmbeddingClient embedding = mock(com.cretas.aims.service.EmbeddingClient.class);
        AIIntentConfigRepository intents = mock(AIIntentConfigRepository.class);
        when(embedding.isAvailable()).thenReturn(true);
        when(intents.findAllEnabled()).thenReturn(List.of());
        when(repo.findByIsActiveTrue()).thenReturn(List.of(
                expression("manual", "*", LearnedExpression.SourceType.MANUAL),
                // 复审 L3: 空白工厂字段同样是全局 (以前落在 " " 键下, 既不全局也不属于任何工厂)
                expression("manual-blank", " ", LearnedExpression.SourceType.MANUAL),
                expression("learned-blank", "", LearnedExpression.SourceType.SEMANTIC_HIGH),
                expression("learned-star", "*", LearnedExpression.SourceType.LLM_FALLBACK),
                expression("learned-null", null, LearnedExpression.SourceType.USER_FEEDBACK),
                expression("own", "F006", LearnedExpression.SourceType.LLM_FALLBACK)));
        when(embedding.encodeBatch(any())).thenReturn(List.of());
        IntentEmbeddingCacheServiceImpl service = new IntentEmbeddingCacheServiceImpl(intents,
                mock(com.cretas.aims.repository.SemanticCacheConfigRepository.class), embedding, repo,
                mock(com.cretas.aims.service.RequestScopedEmbeddingCache.class));
        service.initializeCache();
        // 运行时补缓存也走同一道判断
        service.cacheExpression(expression("learned-later", null, LearnedExpression.SourceType.LLM_FALLBACK));

        Map<String, Map<String, ?>> cache = (Map<String, Map<String, ?>>) ReflectionTestUtils.invokeMethod(
                service, "expressionCache");
        assertThat(cache.get("*")).containsOnlyKeys("manual", "manual-blank");
        assertThat(cache.get("F006")).containsOnlyKeys("own");
        assertThat(cache).containsOnlyKeys("*", "F006");
    }

    // ---------- 工艺保密 (Steve 2026-10-29): 保密产品名不许经由全局内容出现在别的工厂 ----------

    private static ActiveLearningServiceImpl activeLearning(
            com.cretas.aims.service.consent.ProcessSecretSampleFilter filter) {
        ActiveLearningServiceImpl service = Mockito.mock(ActiveLearningServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "processSecretSampleFilter", filter);
        com.cretas.aims.service.consent.TrainingConsentService consent =
                mock(com.cretas.aims.service.consent.TrainingConsentService.class);
        when(consent.status(anyString())).thenReturn(com.cretas.aims.service.consent.TrainingConsentService.Status.AUTHORIZED);
        when(consent.status("F_GONE")).thenReturn(com.cretas.aims.service.consent.TrainingConsentService.Status.WITHDRAWN);
        ReflectionTestUtils.setField(service, "trainingConsentService", consent);
        return service;
    }

    @Test
    void knowledgePromotionToGlobalIsOffWhileCrossFactorySharingIsOff() {
        CrossFactoryKnowledgeRepository knowledge = mock(CrossFactoryKnowledgeRepository.class);
        ActiveLearningServiceImpl service = activeLearning(
                mock(com.cretas.aims.service.consent.ProcessSecretSampleFilter.class));
        ReflectionTestUtils.setField(service, "knowledgeRepository", knowledge);

        assertThat(service.promoteKnowledgeToGlobal(new BigDecimal("0.1"), 1)).isZero();
        verifyNoInteractions(knowledge);
    }

    @Test
    void knowledgeNamingAnyFactorysProtectedProductIsNeverPromotable() {
        com.cretas.aims.service.consent.ProcessSecretSampleFilter filter =
                mock(com.cretas.aims.service.consent.ProcessSecretSampleFilter.class);
        when(filter.mentionsAnyFactoryProtectedProduct("秘制酱牛肉怎么卖", "秘制酱牛肉怎么卖")).thenReturn(true);
        ActiveLearningServiceImpl service = activeLearning(filter);

        com.cretas.aims.entity.learning.CrossFactoryKnowledge secret =
                com.cretas.aims.entity.learning.CrossFactoryKnowledge.builder().content("秘制酱牛肉怎么卖").build();
        com.cretas.aims.entity.learning.CrossFactoryKnowledge plain =
                com.cretas.aims.entity.learning.CrossFactoryKnowledge.builder().content("今天卖了多少").build();
        assertThat(service.promotableToGlobal(secret)).isFalse();
        assertThat(service.promotableToGlobal(plain)).isTrue();
        // 判断不了 (没注入 / 查库出错) 按「提到了」处理, 不晋升
        assertThat(activeLearning(null).promotableToGlobal(plain)).isFalse();
        when(filter.mentionsAnyFactoryProtectedProduct("今天卖了多少", "今天卖了多少"))
                .thenThrow(new IllegalStateException("db down"));
        assertThat(service.promotableToGlobal(plain)).isFalse();
    }

    @Test
    void suggestionNamingAProtectedProductIsNotAddedToTheSharedPhraseTable() {
        com.cretas.aims.service.consent.ProcessSecretSampleFilter filter =
                mock(com.cretas.aims.service.consent.ProcessSecretSampleFilter.class);
        when(filter.mentionsAnyFactoryProtectedProduct(anyString(), anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).contains("秘制酱牛肉"));
        ActiveLearningServiceImpl service = activeLearning(filter);
        com.cretas.aims.repository.learning.LearningSuggestionRepository suggestions =
                mock(com.cretas.aims.repository.learning.LearningSuggestionRepository.class);
        com.cretas.aims.config.IntentKnowledgeBase phrases = mock(com.cretas.aims.config.IntentKnowledgeBase.class);
        com.cretas.aims.service.ExpressionLearningService expressions =
                mock(com.cretas.aims.service.ExpressionLearningService.class);
        ReflectionTestUtils.setField(service, "suggestionRepository", suggestions);
        ReflectionTestUtils.setField(service, "adoptionRepository", mock(CrossFactoryKnowledgeAdoptionRepository.class));
        ReflectionTestUtils.setField(service, "intentKnowledgeBase", phrases);
        ReflectionTestUtils.setField(service, "expressionLearningService", expressions);
        when(phrases.getPhraseToIntentMapping()).thenReturn(Map.of());
        when(suggestions.findById(1L)).thenReturn(java.util.Optional.of(suggestion("秘制酱牛肉今天做了多少")));
        when(suggestions.findById(2L)).thenReturn(java.util.Optional.of(suggestion("今天做了多少")));

        service.applySuggestion(1L, "reviewer");
        verify(phrases, never()).addPhraseMapping(anyString(), anyString());
        verifyNoInteractions(expressions);

        // 阴性对照: 不提保密产品的建议照常应用
        service.applySuggestion(2L, "reviewer");
        verify(phrases).addPhraseMapping("今天做了多少", "PRODUCTION_QUERY");
    }

    /**
     * 复审 M3 (2026-10-29): 先过完整的本厂学习规则再动共用短语表 —— 撤回训练的工厂、没有工厂的建议、
     * 带客户名 / 配方参数的建议都不应用 (以前先 addPhraseMapping, 再无视 learnExpression 的结果)。
     */
    @Test
    void suggestionsFailingTheFactoryLearningRulesNeverReachThePhraseTable() {
        com.cretas.aims.service.consent.ProcessSecretSampleFilter filter =
                mock(com.cretas.aims.service.consent.ProcessSecretSampleFilter.class);
        ActiveLearningServiceImpl service = activeLearning(filter);
        com.cretas.aims.repository.learning.LearningSuggestionRepository suggestions =
                mock(com.cretas.aims.repository.learning.LearningSuggestionRepository.class);
        com.cretas.aims.config.IntentKnowledgeBase phrases = mock(com.cretas.aims.config.IntentKnowledgeBase.class);
        com.cretas.aims.service.ExpressionLearningService expressions =
                mock(com.cretas.aims.service.ExpressionLearningService.class);
        ReflectionTestUtils.setField(service, "suggestionRepository", suggestions);
        ReflectionTestUtils.setField(service, "adoptionRepository", mock(CrossFactoryKnowledgeAdoptionRepository.class));
        ReflectionTestUtils.setField(service, "intentKnowledgeBase", phrases);
        ReflectionTestUtils.setField(service, "expressionLearningService", expressions);
        when(suggestions.findById(1L)).thenReturn(java.util.Optional.of(suggestion("F_GONE", "今天做了多少")));
        when(suggestions.findById(2L)).thenReturn(java.util.Optional.of(suggestion(null, "今天做了多少")));
        when(suggestions.findById(3L)).thenReturn(java.util.Optional.of(suggestion("F006", "这个产品配方里盐放多少")));

        service.applySuggestion(1L, "reviewer");
        service.applySuggestion(2L, "reviewer");
        service.applySuggestion(3L, "reviewer");

        verify(phrases, never()).addPhraseMapping(anyString(), anyString());
        verifyNoInteractions(expressions);
        verify(suggestions, never()).applySuggestion(any(), any(), any(), any());
    }

    private static com.cretas.aims.entity.learning.LearningSuggestion suggestion(String content) {
        return suggestion("F006", content);
    }

    private static com.cretas.aims.entity.learning.LearningSuggestion suggestion(String factoryId, String content) {
        return com.cretas.aims.entity.learning.LearningSuggestion.builder()
                .factoryId(factoryId)
                .intentCode("PRODUCTION_QUERY")
                .suggestionType(com.cretas.aims.entity.learning.LearningSuggestion.SuggestionType.NEW_EXPRESSION)
                .content(content)
                .status(com.cretas.aims.entity.learning.LearningSuggestion.SuggestionStatus.APPROVED)
                .build();
    }
}
