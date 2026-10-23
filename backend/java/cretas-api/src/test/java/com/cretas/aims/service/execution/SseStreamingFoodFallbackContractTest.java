package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SseStreamingFoodFallbackContractTest {
    private static final String QUESTION = "HACCP是什么？请用食品工厂能理解的语言解释。";
    private final AIIntentService intents = mock(AIIntentService.class);
    private final SemanticCacheService cache = mock(SemanticCacheService.class);
    private final IntentKnowledgeBase knowledge = mock(IntentKnowledgeBase.class);
    private final AnalysisRouterService analysis = mock(AnalysisRouterService.class);
    private final DashScopeClient llm = mock(DashScopeClient.class);
    private final DashScopeConfig config = mock(DashScopeConfig.class);
    private final SseStreamingService service = spy(new SseStreamingService(
            intents, cache, knowledge, analysis, new ObjectMapper(), mock(ToolRegistry.class),
            mock(ToolDispatchService.class), mock(DynamicToolSelectionService.class), llm, config));
    private final SseEmitter emitter = mock(SseEmitter.class);

    private void execute(Optional<AIIntentConfig> food, boolean isAnalysis) throws Exception {
        when(knowledge.detectQuestionType(QUESTION)).thenReturn(IntentKnowledgeBase.QuestionType.GENERAL_QUESTION);
        when(knowledge.matchPhrase(QUESTION)).thenReturn(Optional.of("FOOD_KNOWLEDGE_QUERY"));
        when(intents.getIntentByCode("F006", "FOOD_KNOWLEDGE_QUERY")).thenReturn(food);
        when(analysis.isAnalysisRequest(eq(QUESTION), any())).thenReturn(isAnalysis);
        when(config.getFastModel()).thenReturn("test-text-model");
        when(cache.queryCache("F006", QUESTION)).thenReturn(SemanticCacheHit.miss(0L));
        when(intents.recognizeIntentWithConfidence(eq(QUESTION), eq("F006"), eq(3), eq(77L),
                eq("factory_super_admin"), any(), any(), any(), eq(List.of()))).thenReturn(IntentMatchResult.builder()
                .matchMethod(IntentMatchResult.MatchMethod.NONE)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        doNothing().when(service).sendSseEvent(any(), any(), any());
        try (AuthenticatedActor.ActorScope ignored = trustedFactoryActor()) {
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, "F006",
                    IntentExecuteRequest.builder().userInput(QUESTION).build(), 77L, "factory_super_admin");
        }
    }

    private static AuthenticatedActor.ActorScope trustedFactoryActor() {
        return AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 77L, "F006", "factory_super_admin"));
    }

    private void assertConversationalOnly() {
        ArgumentCaptor<ChatCompletionRequest> request = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llm).chatCompletionStream(request.capture(), any(), any());
        assertThat(request.getValue().getMessages().get(1).getContent()).isEqualTo(QUESTION);
        verify(cache, never()).queryCache(any(), any());
        verify(intents, never()).recognizeIntentWithConfidence(any(), any(), anyInt(), any(), any(), any(), any(), any());
        verify(intents, never()).recognizeIntentWithConfidence(any(), any(), anyInt(), any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void missingFoodConfigAllowsConceptExplanationThroughExistingReadOnlyChat() throws Exception {
        execute(Optional.empty(), false);
        assertConversationalOnly();
    }

    @Test
    void explicitlyDisabledFoodConfigDoesNotReserveTheStaticPhrase() throws Exception {
        execute(Optional.of(AIIntentConfig.builder().intentCode("FOOD_KNOWLEDGE_QUERY").isActive(false).build()), false);
        assertConversationalOnly();
    }

    @Test
    void activeFoodConfigKeepsTheExistingKnowledgeRecognitionPath() throws Exception {
        execute(Optional.of(AIIntentConfig.builder().intentCode("FOOD_KNOWLEDGE_QUERY").isActive(true).build()), false);
        verify(llm, never()).chatCompletionStream(any(), any(), any());
        verify(intents).recognizeIntentWithConfidence(eq(QUESTION), eq("F006"), eq(3), eq(77L),
                eq("factory_super_admin"), any(), any(), any(), eq(List.of()));
    }

    @Test
    void analysisDetectionStillTakesPriorityOverGeneralChat() throws Exception {
        execute(Optional.of(AIIntentConfig.builder().intentCode("FOOD_KNOWLEDGE_QUERY").isActive(true).build()), true);
        verify(llm, never()).chatCompletionStream(any(), any(), any());
        verify(cache).queryCache("F006", QUESTION);
        verify(intents).recognizeIntentWithConfidence(eq(QUESTION), eq("F006"), eq(3), eq(77L),
                eq("factory_super_admin"), any(), any(), any(), eq(List.of()));
    }
}
