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
        assertThat(cache.get("*")).containsOnlyKeys("manual");
        assertThat(cache.get("F006")).containsOnlyKeys("own");
    }
}
