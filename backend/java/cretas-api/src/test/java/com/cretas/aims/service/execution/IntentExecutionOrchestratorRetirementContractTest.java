package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * W1b follow-up — VETO gate in the EXECUTION orchestrator.
 *
 * <p>The orchestrator's own contains-based phrase shortcut would execute "看订单" embedded
 * inside the negated read "别给我看订单", bypassing the recognition-layer veto. The fix adds an
 * early VETO gate to {@code execute()}; this test exercises the clarification response builder
 * that the gate returns for a VETO_READ — asserting nothing is recognized or executed.
 *
 * <p>Construction mirrors {@code ToolDispatchServiceBuildNoToolResponseTest}: all collaborators
 * are mocked because {@code buildNegationVetoClarificationResponse} uses none of them (only the
 * response builder + a timestamp).
 */

class IntentExecutionOrchestratorRetirementContractTest {

    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class),
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"restaurant_owner", "restaurant_manager", "restaurant_chef", "restaurant_purchaser"})
    void oldRolesCannotReachRecognitionExecutionOrConfirmation(String role) {
        var request = com.cretas.aims.dto.ai.IntentExecuteRequest.builder().userInput("你好").build();
        assertThat(orchestrator.execute("F006", request, 7L, role).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
        assertThat(orchestrator.executeWithExplicitIntent("F006", request, 7L, role).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
        assertThat(orchestrator.confirm("F006", null, 7L, role).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
        for (String field : new String[]{"aiIntentService", "dashScopeClient", "toolRegistry", "conversationService", "dynamicToolSelectionService"}) {
            org.mockito.Mockito.verifyNoInteractions(org.springframework.test.util.ReflectionTestUtils.getField(orchestrator, field));
        }
    }

    @Test
    void explicitRetiredIntentCannotFallThroughToFactoryRecognition() {
        var request = com.cretas.aims.dto.ai.IntentExecuteRequest.builder()
                .intentCode("RESTAURANT_BESTSELLER_QUERY").userInput("你好").build();
        assertThat(orchestrator.execute("F006", request, 7L, "factory_super_admin").getStatus())
                .isEqualTo("UNSUPPORTED_CAPABILITY");
        org.mockito.Mockito.verifyNoInteractions(org.springframework.test.util.ReflectionTestUtils.getField(orchestrator, "aiIntentService"));
    }

    @Test
    void knownLegacyRealTenantFailsClosedWhenDomainMetadataFails() {
        var config = mock(com.cretas.aims.service.intent.IntentConfigManagementService.class);
        org.mockito.Mockito.when(config.resolveBusinessDomain("R_QINGHUAJIAO_REAL"))
                .thenThrow(new IllegalStateException("metadata unavailable"));
        org.springframework.test.util.ReflectionTestUtils.setField(orchestrator, "configService", config);
        var request = com.cretas.aims.dto.ai.IntentExecuteRequest.builder().userInput("你好").build();
        assertThat(orchestrator.execute("R_QINGHUAJIAO_REAL", request, 7L, "factory_super_admin").getStatus())
                .isEqualTo("UNSUPPORTED_CAPABILITY");
        org.mockito.Mockito.verifyNoInteractions(org.springframework.test.util.ReflectionTestUtils.getField(orchestrator, "aiIntentService"));
    }
}
