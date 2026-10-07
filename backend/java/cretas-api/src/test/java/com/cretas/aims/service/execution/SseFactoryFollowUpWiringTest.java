package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real executeStreamAsync entry: only tool/intent/LLM IO is stubbed. */
class SseFactoryFollowUpWiringTest {
    @Test
    void completedFactoryWriteCarriesPermissionFilteredReadFollowUpOnResultEvent() throws Exception {
        AIIntentService intents = mock(AIIntentService.class);
        SemanticCacheService cache = mock(SemanticCacheService.class);
        ToolRegistry registry = new ToolRegistry();
        ToolExecutor writeTool = tool("work_process_task_complete", ToolExecutor.AccessMode.WRITE);
        ToolExecutor readTool = tool("production_status_query", ToolExecutor.AccessMode.READ);
        toolMap(registry).put(writeTool.getToolName(), writeTool);
        toolMap(registry).put(readTool.getToolName(), readTool);
        ToolDispatchService dispatch = mock(ToolDispatchService.class);
        FactoryRepository factories = mock(FactoryRepository.class);
        Factory factory = new Factory(); factory.setId("F001"); factory.setType(FactoryType.FACTORY); factory.setIsActive(true);
        when(factories.findById("F001")).thenReturn(Optional.of(factory));
        when(cache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
        AIIntentConfig completed = intent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", "DATA_OP");
        AIIntentConfig read = intent("PRODUCTION_STATUS_QUERY", "production_status_query", "ANALYSIS");
        when(intents.recognizeIntentWithConfidence(anyString(), eq("F001"), eq(3), eq(7L), eq("operator"),
                any(), any(), any(), any())).thenReturn(IntentMatchResult.builder().bestMatch(completed)
                .confidence(0.99).matchMethod(IntentMatchResult.MatchMethod.KEYWORD).requiresConfirmation(false).build());
        when(intents.getIntentByCode("F001", "PRODUCTION_STATUS_QUERY")).thenReturn(Optional.of(read));
        when(intents.hasPermission("F001", "WORK_PROCESS_TASK_COMPLETE", "operator")).thenReturn(true);
        when(intents.hasPermission("WORK_PROCESS_TASK_COMPLETE", "operator")).thenReturn(true);
        when(intents.hasPermission("F001", "PRODUCTION_STATUS_QUERY", "operator")).thenReturn(true);
        when(dispatch.executeWithTool(eq(writeTool), eq("F001"), any(), eq(completed), eq(7L), eq("operator"), any()))
                .thenReturn(IntentExecuteResponse.builder().intentRecognized(true).intentCode(completed.getIntentCode())
                        .status("COMPLETED").message("已完成").build());
        FactoryCapabilityPackRoutingPolicy policy = new FactoryCapabilityPackRoutingPolicy(factories,
                new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false);
        var grants = new FrontlineAiWriteGrantService(mock(AiFrontlineIntentGrantRepository.class),
                new WriteGuardService(), mock(IntentConfigManagementService.class), registry);
        SseStreamingService service = spy(new SseStreamingService(intents, cache, mock(IntentKnowledgeBase.class),
                mock(AnalysisRouterService.class), new ObjectMapper(), registry, dispatch,
                mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
        ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", policy);
        ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
        ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(service, "frontlineWriteGrants", grants);
        ReflectionTestUtils.setField(service, "factoryFollowUpService",
                new FactoryFollowUpService(intents, grants, registry));
        doNothing().when(service).sendSseEvent(any(), anyString(), any());

        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("完成工序").build();
        ReflectionTestUtils.invokeMethod(service, "executeAndStreamResult", mock(SseEmitter.class), "F001",
                request, completed, 7L, "operator", System.currentTimeMillis(),
                IntentMatchResult.builder().bestMatch(completed).confidence(1.0).build(),
                policy.evaluate("F001", "operator", "完成工序"));

        var event = (IntentExecuteResponse) captureResult(service);
        assertThat(event.getFollowUps()).singleElement().extracting(IntentExecuteResponse.FollowUpSuggestion::getIntentCode)
                .isEqualTo("PRODUCTION_STATUS_QUERY");
    }

    private static Object captureResult(SseStreamingService service) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(any(), eq("result"), captor.capture());
        return captor.getValue();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, ToolExecutor> toolMap(ToolRegistry registry) {
        return (Map<String, ToolExecutor>) ReflectionTestUtils.getField(registry, "toolMap");
    }
    private static AIIntentConfig intent(String code, String tool, String category) {
        return AIIntentConfig.builder().intentCode(code).intentName(code).toolName(tool).intentCategory(category)
                .sensitivityLevel("LOW").isActive(true).build();
    }
    private static ToolExecutor tool(String name, ToolExecutor.AccessMode mode) {
        ToolExecutor tool = mock(ToolExecutor.class); when(tool.getToolName()).thenReturn(name);
        when(tool.getAccessMode()).thenReturn(mode); when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        return tool;
    }
}
