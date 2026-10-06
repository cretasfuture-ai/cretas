package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SseStreamingClarificationOptionsContractTest {

    private static final String FACTORY = "F006";

    @Test
    void requiresConfirmationSseReturnsCanonicalChoicesAndCompletesWithoutToolCall() throws Exception {
        AIIntentService aiIntentService = mock(AIIntentService.class);
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        AnalysisRouterService analysisRouter = mock(AnalysisRouterService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
        DynamicToolSelectionService dynamic = mock(DynamicToolSelectionService.class);
        FactoryRepository factoryRepository = mock(FactoryRepository.class);
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);

        Factory factory = new Factory();
        factory.setId(FACTORY);
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(true);
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.of(factory));
        FactoryCapabilityPackRoutingPolicy packPolicy = new FactoryCapabilityPackRoutingPolicy(
                factoryRepository, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false);

        AIIntentConfig matched = config("MATERIAL_BATCH_QUERY", "当前库存", "inventory:read");
        AIIntentConfig second = config("PROCESSING_BATCH_LIST", "当前生产批次", null);
        AIIntentConfig denied = config("DENIED_QUERY", "DENIED_CANONICAL_SHOULD_NOT_SHOW", "inventory:read");
        AIIntentConfig inactive = config("INACTIVE_QUERY", "INACTIVE_CANONICAL_SHOULD_NOT_SHOW", null);
        inactive.setIsActive(false);
        AIIntentConfig foreign = config("FOREIGN_QUERY", "FOREIGN_CANONICAL_SHOULD_NOT_SHOW", null);
        foreign.setFactoryId("F999");
        AIIntentConfig write = config("MATERIAL_BATCH_UPDATE", "WRITE_CANONICAL_SHOULD_NOT_SHOW", null);
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        when(configService.getIntentByCode(FACTORY, matched.getIntentCode())).thenReturn(Optional.of(matched));
        when(configService.getIntentByCode(FACTORY, second.getIntentCode())).thenReturn(Optional.of(second));
        when(configService.getIntentByCode(FACTORY, denied.getIntentCode())).thenReturn(Optional.of(denied));
        when(configService.getIntentByCode(FACTORY, inactive.getIntentCode())).thenReturn(Optional.of(inactive));
        when(configService.getIntentByCode(FACTORY, foreign.getIntentCode())).thenReturn(Optional.of(foreign));
        when(configService.getIntentByCode(FACTORY, write.getIntentCode())).thenReturn(Optional.of(write));
        when(permissionGate.check(same(matched), eq(7L), eq("manager")))
                .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        when(permissionGate.check(same(denied), eq(7L), eq("manager")))
                .thenReturn(IntentPermissionGate.PermissionCheck.denied("inventory:read"));
        when(configService.hasPermission(FACTORY, second.getIntentCode(), "manager")).thenReturn(true);
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);
        when(accessModeFilter.isWriteIntent(same(write))).thenReturn(true);

        IntentMatchResult match = IntentMatchResult.builder()
                .bestMatch(matched)
                .topCandidates(List.of(
                        IntentMatchResult.CandidateIntent.builder()
                        .intentCode(matched.getIntentCode()).intentName("不可信名称").confidence(0.71).build(),
                        IntentMatchResult.CandidateIntent.builder()
                                .intentCode(second.getIntentCode()).intentName("另一个不可信名称").confidence(0.69).build(),
                        IntentMatchResult.CandidateIntent.builder()
                                .intentCode(denied.getIntentCode()).intentName("DENIED_RAW").confidence(0.68).build(),
                        IntentMatchResult.CandidateIntent.builder()
                                .intentCode(inactive.getIntentCode()).intentName("INACTIVE_RAW").confidence(0.67).build(),
                        IntentMatchResult.CandidateIntent.builder()
                                .intentCode(foreign.getIntentCode()).intentName("FOREIGN_RAW").confidence(0.66).build(),
                        IntentMatchResult.CandidateIntent.builder()
                                .intentCode(write.getIntentCode()).intentName("WRITE_RAW").confidence(0.65).build()))
                .confidence(0.71)
                .matchMethod(IntentMatchResult.MatchMethod.LLM)
                .requiresConfirmation(true)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                .clarificationQuestion("DENIED_RAW INACTIVE_RAW FOREIGN_RAW WRITE_RAW")
                .build();
        when(semanticCache.queryCache(FACTORY, "查看库存")).thenReturn(
                com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L));
        when(knowledgeBase.detectQuestionType("查看库存"))
                .thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
        when(aiIntentService.recognizeIntentWithConfidence(
                eq("查看库存"), eq(FACTORY), eq(3), eq(7L), eq("manager"), any(), eq("READ"), any(), any()))
                .thenReturn(match);

        DashScopeClient replyClient = mock(DashScopeClient.class);
        SseStreamingService service = spy(new SseStreamingService(
                aiIntentService, semanticCache, knowledgeBase, analysisRouter, new ObjectMapper(), toolRegistry,
                toolDispatch, dynamic, replyClient, mock(DashScopeConfig.class)));
        ClarificationCandidateService candidateService = new ClarificationCandidateService(
                configService, permissionGate, accessModeFilter, new WriteGuardService());
        ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", packPolicy);
        ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
        ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(service, "clarificationCandidateService", candidateService);

        List<Event> events = new ArrayList<>();
        doAnswer(invocation -> {
            events.add(new Event(invocation.getArgument(1), invocation.getArgument(2)));
            return null;
        }).when(service).sendSseEvent(any(), anyString(), any());

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("查看库存").mode("READ").build();
        ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                request, 7L, "manager", new F006BossQuestionClient.Cancellation());

        IntentExecuteResponse result = events.stream()
                .filter(event -> "result".equals(event.name()))
                .map(event -> (IntentExecuteResponse) event.data())
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> complete = events.stream()
                .filter(event -> "complete".equals(event.name()))
                .map(event -> (Map<String, Object>) event.data())
                .findFirst().orElseThrow();

        assertThat(result.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(result.getSuggestedActions()).filteredOn(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionName)
                .containsExactly("当前库存", "当前生产批次");
        assertThat(result.getSuggestedActions()).filteredOn(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .allSatisfy(a -> assertThat(a.getParameters()).containsKey("intentCode"));
        assertThat(result.getMessage()).doesNotContain("DENIED_RAW", "INACTIVE_RAW", "FOREIGN_RAW", "WRITE_RAW");
        assertThat(result.getMessage()).contains("当前库存", "当前生产批次");
        assertThat(result.getFormattedText()).isEqualTo(result.getMessage());
        assertThat(result.getFormattedText()).doesNotContain("DENIED_CANONICAL_SHOULD_NOT_SHOW",
                "INACTIVE_CANONICAL_SHOULD_NOT_SHOW", "FOREIGN_CANONICAL_SHOULD_NOT_SHOW",
                "WRITE_CANONICAL_SHOULD_NOT_SHOW", "不可信名称", "另一个不可信名称");
        assertThat(complete).containsEntry("status", "NEED_CLARIFICATION");
        verify(toolDispatch, never()).executeWithTool(any(), anyString(), any(), any(), any(), any(), any());
        verify(dynamic, never()).trySkillRoute(anyString(), anyString(), any(), any());
        verify(dynamic, never()).tryExplicitSkillRouteForIntent(any(), anyString(), anyString(), any(), any());
        verifyNoInteractions(replyClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "lookup-error", "domain-error"})
    void unavailableCandidatesReturnOnlyRephraseWithoutRawQuestionBody(String failureMode) throws Exception {
        AIIntentService aiIntentService = mock(AIIntentService.class);
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
        DynamicToolSelectionService dynamic = mock(DynamicToolSelectionService.class);
        FactoryRepository factoryRepository = mock(FactoryRepository.class);
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);

        Factory factory = new Factory();
        factory.setId(FACTORY);
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(true);
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.of(factory));
        FactoryCapabilityPackRoutingPolicy packPolicy = new FactoryCapabilityPackRoutingPolicy(
                factoryRepository, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false);
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        if ("domain-error".equals(failureMode)) {
            when(configService.resolveBusinessDomain(FACTORY)).thenThrow(new IllegalStateException("domain unavailable"));
        } else if ("lookup-error".equals(failureMode)) {
            when(configService.getIntentByCode(FACTORY, "MISSING_QUERY"))
                    .thenThrow(new IllegalStateException("lookup unavailable"));
        }

        AIIntentConfig matched = config("MATERIAL_BATCH_QUERY", "当前库存", "inventory:read");
        when(aiIntentService.recognizeIntentWithConfidence(
                eq("查看库存"), eq(FACTORY), eq(3), eq(7L), eq("manager"), any(), eq("READ"), any(), any()))
                .thenReturn(IntentMatchResult.builder()
                        .bestMatch(matched)
                        .topCandidates(List.of(IntentMatchResult.CandidateIntent.builder()
                                .intentCode("MISSING_QUERY").intentName("OLD_FORGED_NAME").confidence(0.8).build()))
                        .confidence(0.8).matchMethod(IntentMatchResult.MatchMethod.LLM)
                        .requiresConfirmation(true)
                        .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                        .clarificationQuestion("OLD_FORGED_NAME")
                        .build());
        when(semanticCache.queryCache(FACTORY, "查看库存"))
                .thenReturn(com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L));
        when(knowledgeBase.detectQuestionType("查看库存"))
                .thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);

        DashScopeClient replyClient = mock(DashScopeClient.class);
        SseStreamingService service = spy(new SseStreamingService(
                aiIntentService, semanticCache, knowledgeBase, mock(AnalysisRouterService.class),
                new ObjectMapper(), toolRegistry, toolDispatch, dynamic,
                replyClient, mock(DashScopeConfig.class)));
        ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", packPolicy);
        ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
        ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(service, "clarificationCandidateService",
                new ClarificationCandidateService(configService, permissionGate, accessModeFilter,
                        new WriteGuardService()));

        List<Event> events = new ArrayList<>();
        doAnswer(invocation -> {
            events.add(new Event(invocation.getArgument(1), invocation.getArgument(2)));
            return null;
        }).when(service).sendSseEvent(any(), anyString(), any());
        ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                IntentExecuteRequest.builder().userInput("查看库存").mode("READ").build(),
                7L, "manager", new F006BossQuestionClient.Cancellation());

        IntentExecuteResponse result = events.stream()
                .filter(event -> "result".equals(event.name()))
                .map(event -> (IntentExecuteResponse) event.data())
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> complete = events.stream()
                .filter(event -> "complete".equals(event.name()))
                .map(event -> (Map<String, Object>) event.data())
                .findFirst().orElseThrow();
        assertThat(result.getMessage()).isEqualTo("当前没有可安全提供的业务选项，请重新描述您的需求。");
        assertThat(result.getSuggestedActions()).extracting(IntentExecuteResponse.SuggestedAction::getActionCode)
                .containsExactly("REPHRASE");
        assertThat(result.getMessage()).doesNotContain("OLD_FORGED_NAME");
        assertThat(result.getFormattedText()).isEqualTo(result.getMessage()).doesNotContain("OLD_FORGED_NAME");
        assertThat(complete).containsEntry("status", "NEED_CLARIFICATION");
        verify(toolDispatch, never()).executeWithTool(any(), anyString(), any(), any(), any(), any(), any());
        verify(dynamic, never()).trySkillRoute(anyString(), anyString(), any(), any());
        verify(dynamic, never()).tryExplicitSkillRouteForIntent(any(), anyString(), anyString(), any(), any());
        verifyNoInteractions(replyClient);
    }

    private AIIntentConfig config(String code, String name, String permission) {
        AIIntentConfig config = AIIntentConfig.builder()
                .intentCode(code).intentName(name).intentCategory("QUERY")
                .businessType("FACTORY").requiredPermission(permission)
                .sensitivityLevel("LOW").build();
        config.setIsActive(true);
        return config;
    }

    private record Event(String name, Object data) { }
}
