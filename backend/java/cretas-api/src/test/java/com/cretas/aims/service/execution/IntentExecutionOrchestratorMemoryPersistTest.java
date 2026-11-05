package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IntentExecutionOrchestratorMemoryPersistTest {
    private IntentExecutionOrchestrator orchestrator;
    private ConversationMemoryService memory;

    @BeforeEach
    void setUp() {
        memory = mock(ConversationMemoryService.class);
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class), mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class), mock(RuleEngineService.class),
                mock(ConversationService.class), memory, new ObjectMapper(),
                mock(DashScopeClient.class), mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class), mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class),
                mock(ToolDispatchService.class), mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
    }

    @Test
    void sameFactorySessionPersistsTwoMessagesAndLastIntent() {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("FACTORY_SUPPLIER_ETA").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-factory").userInput("供应商到货进度").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder().status("COMPLETED").build();
        when(memory.getOrCreateContext("F006", 9L, "sess-factory"))
                .thenReturn(context("F006", 9L, "sess-factory"));
        try (var ignored = actor("F006", 9L)) {
            orchestrator.persistConversationMemoryForExplicitIntent("F006", req, resp, intent, 9L);
        }
        verify(memory).getOrCreateContext("F006", 9L, "sess-factory");
        verify(memory).updateLastIntent("sess-factory", "FACTORY_SUPPLIER_ETA");
        verify(memory, times(2)).addMessage(eq("sess-factory"), any());
        verifyNoMoreInteractions(memory);
    }

    @Test
    void noSessionDoesNotTouchMemory() {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("FACTORY_BATCH_STATUS").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder().userInput("批次状态").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder().status("COMPLETED").build();
        try (var ignored = actor("F006", 9L)) {
            orchestrator.persistConversationMemoryForExplicitIntent("F006", req, resp, intent, 9L);
        }
        verifyNoInteractions(memory);
    }

    @Test
    void foreignSessionDoesNotWriteMemory() {
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("FACTORY_PRODUCT_STATUS").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("foreign-session").userInput("产品状态").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder().status("COMPLETED").build();
        when(memory.getOrCreateContext("F006", 9L, "foreign-session"))
                .thenReturn(context("OTHER", 99L, "foreign-session"));
        try (var ignored = actor("F006", 9L)) {
            orchestrator.persistConversationMemoryForExplicitIntent("F006", req, resp, intent, 9L);
        }
        verify(memory).getOrCreateContext("F006", 9L, "foreign-session");
        verifyNoMoreInteractions(memory);
    }

    private static com.cretas.aims.dto.conversation.ConversationContext context(
            String factoryId, long userId, String sessionId) {
        return com.cretas.aims.dto.conversation.ConversationContext.builder()
                .factoryId(factoryId).userId(userId).sessionId(sessionId).build();
    }

    private static AuthenticatedActor.ActorScope actor(String factoryId, long userId) {
        return AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, userId, factoryId, "operator"));
    }
}
