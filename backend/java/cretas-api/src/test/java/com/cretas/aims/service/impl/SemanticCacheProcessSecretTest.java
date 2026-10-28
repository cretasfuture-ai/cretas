package com.cretas.aims.service.impl;

import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.cache.SemanticCache;
import com.cretas.aims.entity.cache.SemanticCacheConfig;
import com.cretas.aims.repository.SemanticCacheConfigRepository;
import com.cretas.aims.repository.SemanticCacheRepository;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 复审 L2: 语义缓存按工厂共享、不看调用者 —— 有保密产品的工厂不存、不回放完整执行结果 (只留意图),
 * 免得负责人看过的结果被同厂无权的人原样拿到。
 */
class SemanticCacheProcessSecretTest {

    private final SemanticCacheRepository cacheRepository = mock(SemanticCacheRepository.class);
    private final SemanticCacheConfigRepository configRepository = mock(SemanticCacheConfigRepository.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);

    private SemanticCacheServiceImpl service(ProcessSecretLlmCostScope scope) {
        SemanticCacheConfig config = new SemanticCacheConfig();
        config.setFactoryId(FACTORY);
        config.setEnabled(true);
        config.setSimilarityThreshold(new BigDecimal("0.85"));
        config.setCacheTtlHours(24);
        config.setMaxCacheEntries(1000);
        when(configRepository.findByFactoryId(FACTORY)).thenReturn(Optional.of(config));
        when(embeddingClient.isAvailable()).thenReturn(false);
        SemanticCacheServiceImpl service = new SemanticCacheServiceImpl(cacheRepository, configRepository,
                embeddingClient, mock(RequestScopedEmbeddingCache.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "secretScope", scope);
        return service;
    }

    private void cachedAnswer() {
        SemanticCache cache = SemanticCache.builder().id(1L).factoryId(FACTORY).inputHash("h").intentCode("COST_QUERY")
                .intentResult("{\"intentCode\":\"COST_QUERY\"}")
                .executionResult("{\"formattedText\":\"保密卤味 成本 7777.77\"}").hitCount(0).build();
        when(cacheRepository.findByFactoryIdAndInputHashAndExpiresAtAfter(eq(FACTORY), anyString(), any()))
                .thenReturn(Optional.of(cache));
    }

    @Test
    void aProtectedFactoryNeverReplaysACachedAnswer() {
        cachedAnswer();

        SemanticCacheHit hit = service(protectedScope()).queryCache(FACTORY, "保密卤味成本多少");

        assertThat(hit.isHit()).isTrue();                  // 意图照常命中
        assertThat(hit.hasExecutionResult()).isFalse();    // 答案不回放, 每个人按自己的权限重算
    }

    @Test
    void negativeControlAFactoryWithoutProtectedProductsStillReplaysIt() {
        cachedAnswer();

        assertThat(service(unprotectedScope()).queryCache(FACTORY, "保密卤味成本多少").hasExecutionResult()).isTrue();
    }

    @Test
    void aProtectedFactoryStoresOnlyTheIntentNotTheAnswer() {
        when(cacheRepository.findByFactoryIdAndInputHash(eq(FACTORY), anyString())).thenReturn(Optional.empty());
        IntentMatchResult match = IntentMatchResult.builder().confidence(0.9).build();
        IntentExecuteResponse answer = new IntentExecuteResponse();
        answer.setFormattedText("保密卤味 成本 7777.77");

        service(protectedScope()).cacheResult(FACTORY, "保密卤味成本多少", match, answer);
        service(unprotectedScope()).cacheResult(FACTORY, "普通成本多少", match, answer);

        ArgumentCaptor<SemanticCache> saved = ArgumentCaptor.forClass(SemanticCache.class);
        verify(cacheRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getExecutionResult()).isNull();
        assertThat(saved.getAllValues().get(1).getExecutionResult()).contains("7777.77");
    }
}
